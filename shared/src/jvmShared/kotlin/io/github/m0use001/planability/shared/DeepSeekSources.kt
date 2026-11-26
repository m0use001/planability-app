package io.github.m0use001.planability.shared

import org.json.JSONArray
import org.json.JSONObject

/**
 * 2026-11-14：DeepSeek 服务端联网搜索的**来源与轨迹**解析（纯函数，JVM 可测）。
 * M2 批 3 从 app/ai/ptl/DeepSeekSources.kt 迁入（jvmShared：org.json，零 Android 依赖）。
 *
 * ## 为什么 Shape 是这样（模拟器真探针实录，不是照文档猜）
 *
 * 官方文档给的是 Messages 协议的 `web_search_tool_result` / `citations_delta` 内容块；
 * 但 App 走的是 **Chat Completions 协议**，实测（DeepSeekToolsProbeTest，真 key、
 * `tools:[{"type":"deepseek_web_search"}]`）下发的是 **OpenAI 风格 delta**：
 *
 * ```
 * delta.annotations      = [{"type":"url_citation",
 *                            "url_citation":{url,title,start_index,end_index}}, …]
 * delta.server_tool_calls = [{"index":0,"reasoning_content":"搜索意图…",
 *                             "calls":[{"index":0,"id":"srvtoolu_…",
 *                                       "type":"deepseek_web_search","kind":"deep_search",
 *                                       "action":{"type":"search","queries":[…]},
 *                                       "status":"completed",
 *                                       "sources":[{url,title,snippet,site_name,site_icon,type}]}]}]
 * ```
 *
 * 两者的分工（本文件的选择）：
 * - **`server_tool_calls` 是来源清单的唯一真源**——它带 title/snippet/site_name，
 *   而且能看出模型搜了什么（queries）与推理意图（reasoning_content）；
 * - **`annotations` 是「正文哪一段引了哪篇」的细粒度映射**（start_index/end_index 是
 *   累积文本的字符区间）。App 不做逐段角标（正文是流式 Markdown 渲染，插角标会破坏
 *   公式/表格），所以这里只**按 URL 去重合并**成一份来源列表，
 *   区间信息保留下来给将来做「段落级引用」用。
 *
 * 失败契约：任何坏 JSON/未知 type 一律跳过，绝不抛（流式解析里抛异常会整条回复崩）。
 */
object DeepSeekSources {

    /** 一条来源（与 web_search 工具输出同形，便于复用同一套渲染） */
    data class Source(
        val title: String,
        val url: String,
        val snippet: String = "",
        val siteName: String = "",
        /** 服务端给的发布/更新日期（可能为空） */
        val publishedAt: String = ""
    )

    /** 一次服务端搜索的轨迹（「深度思考搜索」的可见证据） */
    data class Trace(
        /** 模型为什么搜（reasoning_content） */
        val reasoning: String = "",
        /** 实际发出的查询词 */
        val queries: List<String> = emptyList(),
        val kind: String = ""
    )

    data class Result(
        val sources: List<Source> = emptyList(),
        val traces: List<Trace> = emptyList()
    ) {
        val isEmpty: Boolean get() = sources.isEmpty() && traces.isEmpty()
    }

    /**
     * 从一条 SSE delta 里抽来源与轨迹（没有就返回 null——调用方按 null 跳过，
     * 绝大多数 chunk 都是纯文本/思考增量）。
     */
    fun parseDelta(delta: JSONObject): Result? {
        val sources = mutableListOf<Source>()
        val traces = mutableListOf<Trace>()

        // ① annotations：url_citation 列表（细粒度位置信息，按 url 去重）
        delta.optJSONArray("annotations")?.let { arr ->
            for (i in 0 until arr.length()) {
                val a = arr.optJSONObject(i) ?: continue
                if (a.optString("type") != "url_citation") continue
                val c = a.optJSONObject("url_citation") ?: continue
                val url = c.optString("url").trim()
                if (url.isEmpty()) continue
                sources.add(
                    Source(
                        title = c.optString("title").trim().ifBlank { url },
                        url = url
                    )
                )
            }
        }

        // ② server_tool_calls：真正的来源清单 + 搜索轨迹
        delta.optJSONArray("server_tool_calls")?.let { arr ->
            for (i in 0 until arr.length()) {
                val entry = arr.optJSONObject(i) ?: continue
                val reasoning = entry.optString("reasoning_content").trim()
                val calls = entry.optJSONArray("calls")
                if (calls == null) {
                    if (reasoning.isNotEmpty()) traces.add(Trace(reasoning = reasoning))
                    continue
                }
                for (j in 0 until calls.length()) {
                    val call = calls.optJSONObject(j) ?: continue
                    val queries = mutableListOf<String>()
                    call.optJSONObject("action")?.optJSONArray("queries")?.let { qs ->
                        for (k in 0 until qs.length()) {
                            qs.optString(k).trim().takeIf { it.isNotEmpty() }?.let { queries.add(it) }
                        }
                    }
                    traces.add(
                        Trace(
                            reasoning = if (j == 0) reasoning else "",
                            queries = queries,
                            kind = call.optString("kind").trim()
                        )
                    )
                    call.optJSONArray("sources")?.let { ss ->
                        for (k in 0 until ss.length()) {
                            val s = ss.optJSONObject(k) ?: continue
                            val url = s.optString("url").trim()
                            if (url.isEmpty()) continue
                            sources.add(
                                Source(
                                    title = s.optString("title").trim().ifBlank { url },
                                    url = url,
                                    snippet = s.optString("snippet").replace(Regex("\\s+"), " ").trim(),
                                    siteName = s.optString("site_name").trim(),
                                    publishedAt = s.optString("published_at").trim()
                                )
                            )
                        }
                    }
                }
            }
        }

        if (sources.isEmpty() && traces.isEmpty()) return null
        return Result(sources, traces)
    }

    /**
     * 合并多次 delta 的结果：**来源按 URL 去重**（同一篇被引用多次只留一条，
     * 但优先保留信息更全的那条——带 snippet/siteName 的优先），轨迹按出现顺序累积。
     */
    fun merge(acc: Result, next: Result): Result {
        val byUrl = LinkedHashMap<String, Source>()
        for (s in acc.sources + next.sources) {
            val prev = byUrl[s.url]
            byUrl[s.url] = if (prev == null) s else richer(prev, s)
        }
        return Result(
            sources = byUrl.values.toList(),
            traces = acc.traces + next.traces.filter { it.queries.isNotEmpty() || it.reasoning.isNotEmpty() }
        )
    }

    /** 信息更全的那条（snippet>siteName>publishedAt 优先，空字段用另一条补） */
    private fun richer(a: Source, b: Source): Source = Source(
        title = a.title.ifBlank { b.title },
        url = a.url,
        snippet = when {
            a.snippet.length >= b.snippet.length -> a.snippet
            else -> b.snippet
        }.ifBlank { a.snippet.ifBlank { b.snippet } },
        siteName = a.siteName.ifBlank { b.siteName },
        publishedAt = a.publishedAt.ifBlank { b.publishedAt }
    )

    /**
     * 渲染成给模型/用户看的来源清单（与 web_search 工具的输出同一套编号列表形状，
     * 模型能直接引用「[1]」）。
     */
    fun render(sources: List<Source>, traces: List<Trace> = emptyList(), limit: Int = 8): String {
        if (sources.isEmpty()) return ""
        val sb = StringBuilder()
        val queries = traces.flatMap { it.queries }.distinct()
        if (queries.isNotEmpty()) {
            sb.append("（本次服务端搜索了：").append(queries.joinToString("、")).append("）\n\n")
        }
        sources.take(limit).forEachIndexed { i, s ->
            sb.append(i + 1).append(". ").append(s.title).append('\n')
            sb.append(s.url).append('\n')
            if (s.snippet.isNotEmpty()) sb.append(s.snippet).append('\n')
            if (s.siteName.isNotEmpty() || s.publishedAt.isNotEmpty()) {
                sb.append('[').append(
                    listOfNotNull(
                        s.siteName.takeIf { it.isNotEmpty() },
                        s.publishedAt.takeIf { it.isNotEmpty() }?.take(10)
                    ).joinToString(" · ")
                ).append("]\n")
            }
            sb.append('\n')
        }
        if (sources.size > limit) sb.append("（另有 ").append(sources.size - limit).append(" 条未列出）\n")
        return sb.toString().trimEnd()
    }

    /** 兜底：把一段 JSON 文本（server_tool_calls 的字符串形态）当 delta 解析，坏 JSON 返回 null */
    fun parseRaw(json: String, key: String): Result? = runCatching {
        val obj = JSONObject()
        obj.put(key, JSONArray(json))
        parseDelta(obj)
    }.getOrNull()
}
