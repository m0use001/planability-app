package io.github.m0use001.planability.ui.info

import io.github.m0use001.planability.data.ChatMessage
import io.github.m0use001.planability.data.CompactMarkerEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 2026-11-15 用户：「列表中被压缩轮次顶部、会话界面显示的压缩概要顶部，都要显示
 * 从多少 tok 压缩到多少 tok，还有压缩率」。
 *
 * 钉死 token 口径（不是字符口径）：原文 = 各原始消息正文累计 estTokens；摘要 = 摘要文本 estTokens。
 */
class CompactionStatsTest {

    private fun msg(id: Long, content: String) =
        ChatMessage(id = id, sessionId = 1, role = "user", content = content, createdAt = id)

    @Test
    fun 摘要行给出原始tokens_摘要tokens_压缩率() {
        val originals = listOf(
            msg(1, "啊".repeat(4000)),
            msg(2, "啊".repeat(4000)),
        )
        val summary = "啊".repeat(200)
        val s = compactionStats(originals, summary)!!
        assertTrue("原文 token 必须远大于摘要", s.origTok > s.sumTok)
        assertEquals(
            "压缩率 = 1 - 摘要/原文（百分比取整）",
            ((s.origTok - s.sumTok) * 100 / s.origTok),
            s.percent
        )
        val line = compactionSubline(
            CompactMarkerEntity(
                id = 1, sessionId = 1, summary = summary,
                lastCompactedMessageId = 2, firstKeptMessageId = 3, compactedCount = 2,
                firstCompactedMessageId = 1, source = "manual_range", createdAt = 0L
            ),
            originals, emptyList()
        )
        assertTrue("摘要行必须同时出现原文 token 与摘要 token：$line",
            line.contains("${s.origTok} tok") && line.contains("${s.sumTok} tok"))
        assertTrue("摘要行必须出现压缩率：$line", line.contains("压缩 ${s.percent}%"))
    }

    @Test
    fun 空原文或空摘要不编比例() {
        assertNull(compactionStats(emptyList(), "摘要"))
        assertNull(compactionStats(listOf(msg(1, "正文")), "   "))
    }

    @Test
    fun 工具调用骨架也算原文量() {
        val withCalls = ChatMessage(
            id = 1, sessionId = 1, role = "assistant", content = "",
            createdAt = 1, toolCallsJson = "{\"name\":\"a\"}"
        )
        val s = compactionStats(listOf(withCalls), "摘要正文")!!
        assertTrue("空正文但有工具调用的行不能算 0 原文（会除零/失真）", s.origTok > 0)
    }
}
