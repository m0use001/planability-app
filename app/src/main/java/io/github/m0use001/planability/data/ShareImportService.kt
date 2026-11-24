package io.github.m0use001.planability.data

import io.github.m0use001.planability.core.AppTime

/**
 * v24：把 [ShareLinkImporter] 解析出来的对话落成一条真实的本地会话。
 *
 * 与解析器分开：解析是"跟别人家的网页格式较劲"（易坏），
 * 落库是"写自己的库"（稳定），两件事的失败模式和解法都不一样。
 */
object ShareImportService {

    sealed class Outcome {
        /** 成功，sessionId 是新建的会话 */
        data class Ok(val sessionId: Long, val title: String, val count: Int, val degraded: Boolean, val note: String?) : Outcome()
        data class Failed(val reason: String) : Outcome()
    }

    /**
     * 抓取 → 解析 → 落库。
     *
     * **降级策略**：抓不到结构化数据但抠到了纯文本，照样导入（标 degraded），
     * 而不是丢掉用户已经贴进来的链接。实在什么都没有才失败，并把原因说清楚。
     */
    suspend fun import(url: String): Outcome {
        val trimmed = url.trim()
        if (trimmed.isBlank()) return Outcome.Failed("请先粘贴分享链接")

        // 嵌套 sealed 分支必须带外层名（ShareFetcher.Fetched.Bad）；再加 else 兜底，
        // 免得以后加分支又变成"when 不穷尽"的编译错误
        val html: String = when (val f = ShareFetcher.fetch(trimmed)) {
            is ShareFetcher.Fetched.Bad -> return Outcome.Failed(f.reason)
            is ShareFetcher.Fetched.Got -> f.html
            else -> return Outcome.Failed("抓取结果异常")
        }
        val parsed = ShareLinkImporter.parse(trimmed, html)
        if (parsed.messages.isEmpty()) {
            return Outcome.Failed(parsed.note ?: "没能从这个页面提取到对话内容")
        }
        val title = sanitizeTitle(parsed.title, trimmed)
        // createSession 返回 ChatSession（不是 id），要自己取 .id
        val sid = Graph.chat.createSession(title = title).id
        val base = AppTime.nowMillis()
        parsed.messages.forEachIndexed { i, m ->
            val role = ShareLinkImporter.normalizeRole(m.role)
            Graph.chat.insertMessage(
                ChatMessage(
                    sessionId = sid,
                    role = role,
                    content = m.text.take(20_000),
                    model = if (role == "assistant") parsed.source else "",
                    status = "done",
                    // 每条拉开 1 秒：导入的消息在时间线上应该是连贯的
                    createdAt = base + i * 1000L
                )
            )
        }
        return Outcome.Ok(
            sessionId = sid, title = title,
            count = parsed.messages.size,
            degraded = parsed.degraded, note = parsed.note
        )
    }

    /** 标题清洗：空/太长/含换行都换掉；顺带从 URL 里补平台名 */
    private fun sanitizeTitle(raw: String, url: String): String {
        var t = raw.replace(Regex("\\s+"), " ").trim()
        if (t.length < 2 || t.length > 60) {
            val platform = ShareLinkImporter.platformOf(url)
            t = platform?.let { "$it 导入对话" } ?: "导入的对话"
        }
        return t
    }
}