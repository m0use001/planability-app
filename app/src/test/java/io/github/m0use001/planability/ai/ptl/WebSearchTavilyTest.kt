package io.github.m0use001.planability.ai.ptl

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 2026-11-14：联网搜索主源从「端侧抓 Bing/360」换成 Tavily（用户拍板：不要再反复
 * 试错修 selector，也拒绝远程依赖自家电脑跑 SearXNG）。本文件守三件事：
 *
 * 1. Tavily 响应能解析成可用结果（真实响应形状：results[].{title,url,content}）；
 * 2. 失败契约：错误响应/无结果/非法 URL 一律空手，绝不抛异常（否则工具层会把
 *    「搜索源挂了」变成「工具崩了」，模型没法解释给用户）；
 * 3. **优先级不许被后人调换**：配了 key 必须先打 Tavily，端侧链只在它空手/质量
 *    不过时触发——顺序反了等于每次都先冒被风控/改版的风险跑一遍。
 */
class WebSearchTavilyTest {

    private fun src(rel: String): String {
        val roots = listOf("app/src/main/java/io/github/m0use001/planability/", "src/main/java/io/github/m0use001/planability/")
        roots.forEach { r ->
            val f = java.io.File(r + rel)
            if (f.isFile) return f.readText()
        }
        return java.io.File("app/src/main/java/io/github/m0use001/planability/" + rel).readText()
    }

    /** 真实响应形状（2026-11-14 用真 key 打「期末考试 高数 复习方法」的裁剪样本） */
    private val realShape = """
        {"query":"期末考试 高数 复习方法","answer":null,
         "results":[
           {"title":"高等数学 | 钱院学辅信息站","url":"https://qyxf.github.io/course/advanced-mathematics",
            "content":"### 课后总结\n\n 上课前有没有预习（做完书上例题，理解所有概念），完全可以看做是两节完全…","score":0.93},
           {"title":"[PDF] 交大高等数学期末考试复习要点","url":"https://www.math.miami.edu/~dzgao/Keynote.pdf",
            "content":"交大高等数学期末考试复习要点 第七章 微分方程…","score":0.88},
           {"title":"【无链接】坏条目","url":"ftp://example.com/x","content":"非法协议应被丢掉"}
         ]}
    """.trimIndent()

    @Test
    fun `解析真实形状响应`() {
        val hits = WebSearch.parseTavily(realShape, 5)
        // 两条合法结果保留，ftp:// 那条丢掉
        assertEquals(2, hits.size)
        assertEquals("高等数学 | 钱院学辅信息站", hits[0].title)
        assertEquals("https://qyxf.github.io/course/advanced-mathematics", hits[0].url)
        // 摘要里的换行折叠成单空格（模型读到的是一行）
        assertFalse("摘要没折叠换行：${hits[0].snippet}", hits[0].snippet.contains('\n'))
        assertTrue("摘要丢了正文：${hits[0].snippet}", hits[0].snippet.contains("课后总结"))
    }

    @Test
    fun `count 截断`() {
        assertEquals(1, WebSearch.parseTavily(realShape, 1).size)
    }

    /** 失败契约：Tavily 的错误体（401/432 额度用尽都是这个形状）与空 results 都空手返回 */
    @Test
    fun `错误响应与空结果都返回空表`() {
        assertTrue(WebSearch.parseTavily("""{"detail":{"error":"Invalid API key"}}""", 5).isEmpty())
        assertTrue(WebSearch.parseTavily("""{"results":[]}""", 5).isEmpty())
        assertTrue(WebSearch.parseTavily("not json at all", 5).isEmpty())
        assertTrue(WebSearch.parseTavily("", 5).isEmpty())
    }

    /** 摘要截断 240 字：Tavily 的 content 常常上千字，全塞进上下文会把 token 预算打爆 */
    @Test
    fun `摘要截断到 240 字`() {
        val long = "字".repeat(1200)
        val json = """{"results":[{"title":"t","url":"https://a.com/x","content":"$long"}]}"""
        assertEquals(240, WebSearch.parseTavily(json, 5).single().snippet.length)
    }

    /** Tavily 结果也必须过同一道质量门（API 稳 ≠ 不过滤跑题） */
    @Test
    fun `Tavily 结果同样过质量门`() {
        val good = listOf(
            WebSearch.Hit("【上海天气预报】上海天气预报7天_全国天气网", "https://tianqi.so.com/weather/101020100", "上海 今天 晴"),
            WebSearch.Hit("上海天气预报,上海7天天气预报", "https://www.weather.com.cn/weather/101020100.shtml", "上海 预报")
        )
        assertTrue("正常结果被误杀", WebSearch.isGoodEnough(good, "今天 上海 天气 预报"))
        val off = listOf(
            WebSearch.Hit("曼恩卡车 官网", "https://mann.example.com", "卡车"),
            WebSearch.Hit("重型卡车报价", "https://truck.example.com/a", "卡车")
        )
        assertFalse("零相关结果没被质量门拦下", WebSearch.isGoodEnough(off, "背单词软件 永久会员 排行榜"))
    }

    /** 多 key 解析（用户当天给了两个号：一个用完自动换下一个） */
    @Test
    fun `多 key 解析`() {
        val a = "tvly-3oCfFP-RftLPnBBBK2JaqnE4KudzmsY5d593LzPITkGYuuma6"
        val b = "tvly-1InYfZ-fHMDXUARFWasOpo0vTRreTOEPnOVqnKCC1tw12An60"
        assertEquals(listOf(a, b), WebSearch.parseKeys("$a\n$b"))
        assertEquals(listOf(a, b), WebSearch.parseKeys("$a, $b"))
        // 粘贴整段 JSON/说明也只认 key 字面量
        assertEquals(listOf(a), WebSearch.parseKeys("""{"api_key":"$a"}"""))
        // 重复去重
        assertEquals(listOf(a), WebSearch.parseKeys("$a\n$a"))
        assertTrue(WebSearch.parseKeys("").isEmpty())
        assertTrue(WebSearch.parseKeys("   ").isEmpty())
    }

    /** 结构守卫：Tavily 必须先于端侧链；没配 key 时端侧先答、云兜底在其后（默认关=行为一致） */
    @Test
    fun `Tavily 优先级不许被调换`() {
        val w = src("ai/ptl/WebSearch.kt")
        // 2026-11-14 云兜底插入后：keys.isEmpty 分支 = 端侧先答 → 云兜底 → 空手返回；
        // 「端侧先答」的形状还在（块内），行为与上云前一致（云默认关）。
        val body = w.substringAfter("fun searchWithSource")
        assertTrue("没配 key 时的端侧直通丢了",
            body.contains(Regex("if \\(keys\\.isEmpty\\(\\)\\) \\{\\s*val legacy = legacySearch\\(query, count\\)")))
        // 主路径锚定在 Tavily 循环之后（keys.isEmpty 分支里的 legacySearch 在循环前出现是合法的）
        val tavCall = body.indexOf("for (k in keys)")
        val legacyCall = body.indexOf("legacySearch(query, count)", tavCall)
        val cloudCall = body.indexOf("DeepSeekCloud.searchIfEnabled(query, count)", tavCall)
        assertTrue("fetchTavily 调用丢了", tavCall >= 0)
        assertTrue("Tavily 循环后必须跑端侧链（顺序被调换=每次先冒风控风险）",
            tavCall >= 0 && legacyCall > tavCall)
        assertTrue("云兜底必须在 Tavily 与端侧之后（拍板：Tavily 优先，云兜底）",
            cloudCall >= 0 && cloudCall > tavCall && cloudCall > legacyCall)
        assertTrue("Tavily 结果没过质量门（API 稳也要拦跑题）",
            body.contains("if (isGoodEnough(tav, query)) return Outcome(tav, \"tavily\")"))
        assertTrue("两条链都空手时必须退回 Tavily 原始结果", body.contains("return Outcome(lastTav, \"tavily\")"))
        // 多 key 轮换：额度用尽的号返回空表 → 继续下一个
        assertTrue("多 key 轮换丢了", body.contains("for (k in keys)") && body.contains("if (tav.isEmpty()) continue"))
        // 认证方式：Bearer tvly-…
        assertTrue("请求没带 Bearer 认证", w.contains(".header(\"Authorization\", \"Bearer \" + key)"))
        assertTrue("endpoint 变了", w.contains("https://api.tavily.com/search"))
    }

    /** key 落库必须加密（v44 整库备份是加密的，明文 key 进备份等于把 key 拷走） */
    @Test
    fun `Tavily key 加密落库`() {
        val s = src("data/SettingsRepo.kt")
        assertTrue("没有 searchTavilyKey 字段", s.contains("val searchTavilyKey: String = \"\""))
        assertTrue("写入没走 SecretBox.seal", s.contains("putRaw(\"search.tavilyKey\", SecretBox.seal(new.searchTavilyKey))"))
        assertTrue("读取没走 SecretBox.open", s.contains("searchTavilyKey = SecretBox.open(all[\"search.tavilyKey\"] ?: \"\")"))
    }

    /** 工具层必须标注来源（出问题时能一眼看出这次是谁答的） */
    @Test
    fun `工具审计标注搜索来源`() {
        val t = src("ai/ptl/ToolExecImpls.kt")
        assertTrue("web_search 没走带来源的入口", t.contains("WebSearch.searchWithSource(q, n)"))
        assertTrue("审计摘要没标来源", t.contains("（Tavily）") && t.contains("（端侧）"))
    }
}
