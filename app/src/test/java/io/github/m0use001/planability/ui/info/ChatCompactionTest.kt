package io.github.m0use001.planability.ui.info

import io.github.m0use001.planability.data.ChatMessage
import io.github.m0use001.planability.data.CompactMarkerEntity
import io.github.m0use001.planability.ai.chatRounds
import io.github.m0use001.planability.ai.compactedIdSet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v47「按轮选择性压缩」的纯函数防回归钉：
 * - 轮分组（1 轮 = 1 user + AI 对它的全部回答）的边界；
 * - 节点切分时被压缩消息被摘要卡取代、位置正确；
 * - 自读书签跟着变（压缩区间出「[已压缩] …」书签，淡紫底）；
 * - compactedIdSet 口径唯一。
 */
class ChatCompactionTest {

    private fun m(id: Long, role: String, content: String = "x") =
        ChatMessage(id = id, sessionId = 1, role = role, content = content)

    private fun marker(firstId: Long, lastId: Long, summary: String = "摘要") = CompactMarkerEntity(
        sessionId = 1, summary = summary,
        lastCompactedMessageId = lastId, firstCompactedMessageId = firstId,
        compactedCount = (lastId - firstId + 1).toInt(), source = "manual_range"
    )

    @Test
    fun `轮分组_一轮是user到下一条user之前`() {
        val msgs = listOf(
            m(1, "user", "问题一"),
            m(2, "assistant", "回答一"),
            m(3, "tool", "结果"),
            m(4, "user", "问题二"),
            m(5, "assistant", "回答二")
        )
        val rounds = chatRounds(msgs)
        assertEquals(2, rounds.size)
        assertEquals(1L, rounds[0].firstId)
        assertEquals(3L, rounds[0].lastId)
        assertEquals(4L, rounds[1].firstId)
        assertEquals(5L, rounds[1].lastId)
    }

    @Test
    fun `轮分组_连续多条user各自成轮_开头无user也归第一轮`() {
        val msgs = listOf(
            m(1, "assistant", "开场白"),   // 极端情况：开头不是 user
            m(2, "user", "a"),
            m(3, "user", "b")
        )
        val rounds = chatRounds(msgs)
        assertEquals(3, rounds.size)
        assertEquals(1L, rounds[0].firstId)
        assertEquals(2L, rounds[1].firstId)
        assertEquals(3L, rounds[2].firstId)
        assertTrue(rounds.all { it.lastId >= it.firstId })
    }

    @Test
    fun `compactedIdSet_区间全闭_起点为0的旧行忽略`() {
        val set = compactedIdSet(
            listOf(marker(5, 7), marker(firstId = 0, lastId = 100, summary = "旧整历史滚动"))
        )
        assertEquals(setOf(5L, 6L, 7L), set)
    }

    @Test
    fun `节点切分_被压缩区间只剩摘要卡_位置在区间终点`() {
        val msgs = listOf(
            m(1, "user", "q1"),
            m(2, "assistant", "a1"),
            m(3, "user", "q2"),
            m(4, "assistant", "a2"),
            m(5, "user", "q3"),
            m(6, "assistant", "a3")
        )
        val nodes = buildChatNodes(msgs, listOf(marker(3, 4, "第二轮摘要")))
        // 节点序列：q1, a1, Compact(摘要卡), q3, a3
        assertEquals(5, nodes.size)
        val compact = nodes.filterIsInstance<ChatNode.Compact>()
        assertEquals(1, compact.size)
        assertEquals(4L, compact[0].marker.lastCompactedMessageId)
        assertEquals("第二轮摘要", compact[0].marker.summary)
        // 摘要卡前面是 q1/a1（第一轮原文），后面是 q3/a3
        val idx = nodes.indexOf(compact[0])
        assertEquals("q1", (nodes[0] as ChatNode.Single).msg.content)
        assertEquals("q3", (nodes[idx + 1] as ChatNode.Single).msg.content)
        // 被压缩的原文（q2/a2）不再出现在任何节点里
        assertTrue(nodes.none { n ->
            (n as? ChatNode.Single)?.msg?.id in 3L..4L
        })
    }

    @Test
    fun `自读书签_压缩区间显示摘要_计数含压缩卡`() {
        val msgs = listOf(
            m(1, "user", "第一问"),
            m(2, "assistant", "第一答"),
            m(3, "user", "第二问"),
            m(4, "assistant", "第二答")
        )
        val nodes = buildChatNodes(msgs, listOf(marker(3, 4, "第二轮的摘要内容")))
        val bookmarks = buildChatBookmarks(nodes)
        assertEquals(2, bookmarks.size)
        assertEquals("第一问", bookmarks[0].label)
        // 2026-11-15 用户：书签里压缩条目改成「[已压缩] + 概要」（去掉摘要自带的块头）
        assertEquals("[已压缩] 第二轮的摘要内容", bookmarks[1].label)
        assertTrue("压缩条目必须带 compacted 标记（淡紫底靠它）", bookmarks[1].compacted)
        assertEquals(2, bookmarks[1].total)
    }
}
