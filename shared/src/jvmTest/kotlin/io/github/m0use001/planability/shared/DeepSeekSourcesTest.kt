package io.github.m0use001.planability.shared

import org.json.JSONArray
import org.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 2026-11-14：DeepSeek 服务端联网搜索的**来源解析**（上云 P1）。
 * M2 批 3 从 app 侧迁入（只迁行为测试；原文件后 4 条是读 app 源码的结构守卫，留在 app）。
 *
 * 样本全部来自模拟器真探针（DeepSeekToolsProbeTest，真 key、Chat Completions、
 * `tools:[{"type":"deepseek_web_search"}]`）实录，不是照文档猜——
 * 文档写的是 Messages 协议的 content_block，App 走 Chat Completions 收到的是
 * `delta.annotations` + `delta.server_tool_calls`（OpenAI 风格）。
 */
class DeepSeekSourcesTest {

    /** 实录：annotations（同一篇被多次引用，start/end 不同区间） */
    private val annotationsJson = """
        [{"type":"url_citation","url_citation":{"url":"http://bj.weather.com.cn/a.shtml",
          "title":"今天天空云量较多","start_index":0,"end_index":93}},
         {"type":"url_citation","url_citation":{"url":"http://bj.weather.com.cn/a.shtml",
          "title":"今天天空云量较多","start_index":93,"end_index":156}},
         {"type":"url_citation","url_citation":{"url":"https://m.gmw.cn/b.htm",
          "title":"今明两天北京以多云或阴为主","start_index":93,"end_index":156}}]
    """.trimIndent()

    /** 实录：server_tool_calls（带推理意图、查询词、sources 全文） */
    private val toolCallsJson = """
        [{"index":0,"reasoning_content":"用户想知道北京今天的天气。我需要搜索北京天气信息。",
          "calls":[{"index":0,"id":"srvtoolu_ds_00_377a","type":"deepseek_web_search",
                    "kind":"deep_search",
                    "action":{"type":"search","queries":["北京 天气 2026-04-24","北京 今日天气"]},
                    "status":"completed",
                    "sources":[
                      {"type":"url","url":"http://bj.weather.com.cn/a.shtml",
                       "title":"今天天空云量较多","snippet":"最高气温 26℃，午后山区有雷阵雨",
                       "site_name":"天气网","published_at":"2026-04-23T16:00:00+00:00"},
                      {"type":"url","url":"https://m.gmw.cn/b.htm",
                       "title":"今明两天北京以多云或阴为主","snippet":"今天多云，午后山区有分散雷阵雨",
                       "site_name":"光明网","published_at":"2026-04-23T16:00:00+00:00"}
                    ]}]}]
    """.trimIndent()

    @Test
    fun `解析 annotations 逐条产出`() {
        val delta = JSONObject().put("annotations", JSONArray(annotationsJson))
        val r = DeepSeekSources.parseDelta(delta)!!
        // parseDelta 只管抽（3 条 url_citation 原样产出，含同一 URL 的两段引用）；
        // **去重是 merge 的职责**（见 merge 用例）——流式里每帧都会来，绝不能在这里丢信息。
        assertEquals(3, r.sources.size)
        assertTrue(r.sources.any { it.url.contains("bj.weather.com.cn") })
        assertTrue(r.sources.any { it.url.contains("gmw.cn") })
    }

    @Test
    fun `解析 server_tool_calls 的来源与搜索轨迹`() {
        val delta = JSONObject().put("server_tool_calls", JSONArray(toolCallsJson))
        val r = DeepSeekSources.parseDelta(delta)!!
        assertEquals(2, r.sources.size)
        // 来源带 snippet / siteName（annotations 里没有的丰富信息）
        val w = r.sources.first { it.url.contains("bj.weather.com.cn") }
        assertEquals("天气网", w.siteName)
        assertTrue(w.snippet.contains("26℃"), "snippet 丢了")
        assertEquals("2026-04-23", w.publishedAt.take(10))
        // 轨迹：推理意图 + 两个查询词 = 深度思考搜索的可见证据
        assertTrue(r.traces.isNotEmpty())
        assertEquals(
            listOf("北京 天气 2026-04-24", "北京 今日天气"),
            r.traces.flatMap { it.queries }.distinct()
        )
        assertTrue(r.traces.any { it.reasoning.contains("用户想知道") })
        assertEquals("deep_search", r.traces.first().kind)
    }

    /** 合并：同一 URL 的 annotations 条目与 server 条目合并成一条，取信息更全的那份 */
    @Test
    fun `合并同类来源取更全信息`() {
        val a = DeepSeekSources.parseDelta(JSONObject().put("annotations", JSONArray(annotationsJson)))!!
        val b = DeepSeekSources.parseDelta(JSONObject().put("server_tool_calls", JSONArray(toolCallsJson)))!!
        val merged = DeepSeekSources.merge(a, b)
        assertEquals(2, merged.sources.size, "同一 URL 只留一条")
        val w = merged.sources.first { it.url.contains("bj.weather.com.cn") }
        // annotations 只有标题，server 有 snippet/siteName —— 合并后应取到后者
        assertEquals("天气网", w.siteName)
        assertTrue(w.snippet.isNotEmpty())
        // 轨迹去重累积（只有 server 侧带 queries）
        assertEquals(2, merged.traces.flatMap { it.queries }.distinct().size)
    }

    @Test
    fun `纯文本 delta 返回 null`() {
        assertNull(DeepSeekSources.parseDelta(JSONObject().put("content", "北京今天多云")))
        assertNull(DeepSeekSources.parseDelta(JSONObject().put("reasoning_content", "思考…")))
        assertNull(DeepSeekSources.parseDelta(JSONObject()))
    }

    @Test
    fun `坏数据不抛异常`() {
        // 未知 type / 缺字段 / 空数组：一律返回 null 或空来源
        assertNull(DeepSeekSources.parseDelta(JSONObject().put("annotations", JSONArray("[]"))))
        val odd = DeepSeekSources.parseDelta(
            JSONObject().put("annotations", JSONArray("""[{"type":"other"}]"""))
        )
        assertNull(odd, "未知类型应被跳过")
        // server_tool_calls 里没有 calls 但有 reasoning：只出轨迹不出来源
        val onlyReasoning = DeepSeekSources.parseDelta(
            JSONObject().put("server_tool_calls", JSONArray("""[{"reasoning_content":"想搜一下"}]"""))
        )!!
        assertTrue(onlyReasoning.sources.isEmpty())
        assertEquals(1, onlyReasoning.traces.size)
    }

    @Test
    fun `渲染成编号来源列表`() {
        val r = DeepSeekSources.parseDelta(JSONObject().put("server_tool_calls", JSONArray(toolCallsJson)))!!
        val text = DeepSeekSources.render(r.sources, r.traces)
        assertTrue(text.contains("北京 天气 2026-04-24"), "没列出查询词：$text")
        assertTrue(text.startsWith("（本次服务端搜索了"), "没编号：$text")
        assertTrue(text.contains("1. "), "来源没编号：$text")
        assertTrue(text.contains("http://bj.weather.com.cn/a.shtml"), "没带 URL：$text")
        assertTrue(text.contains("天气网"), "没带站点信息：$text")
        // 空来源 → 空串（调用方据此不追加任何东西 = 无来源时行为完全不变）
        assertEquals("", DeepSeekSources.render(emptyList()))
    }
}
