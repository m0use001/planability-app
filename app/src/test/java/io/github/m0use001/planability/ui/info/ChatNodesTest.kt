package io.github.m0use001.planability.ui.info

import io.github.m0use001.planability.data.ChatMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「过程链」分组器（2026-10-31）。
 *
 * 为什么钉它：分组器决定"哪些消息被折叠进一行摘要"。分错只有两种后果，且都很难在
 * 界面上看出是分组的错：
 *   1. 把回答正文吞进折叠区 → 用户以为模型没回答；
 *   2. 跨 user 消息合并 → 两次提问的过程被串成一段，阅读顺序错乱。
 * 所以这里把"绝不吞正文 / 绝不跨用户消息"当成硬断言。
 */
class ChatNodesTest {

    private fun msg(
        id: Long,
        role: String,
        content: String = "",
        toolCalls: String = "",
        toolCallId: String = ""
    ) = ChatMessage(
        id = id, sessionId = 1, role = role, content = content,
        toolCallsJson = toolCalls, toolCallId = toolCallId
    )

    @Test
    fun `思考加两次工具调用加两条回执合成一条过程链`() {
        val list = listOf(
            msg(1, "user", "帮我排课"),
            msg(2, "assistant", content = "", toolCalls = """[{"id":"c1"}]"""),
            msg(3, "tool", "[OK] 查到 3 条", toolCallId = "c1"),
            msg(4, "tool", "[OK] 查到 2 条", toolCallId = "c2"),
            msg(5, "assistant", content = "好的，计划如下")
        )
        val nodes = buildChatNodes(list)
        assertEquals("应为 用户 / 过程链 / 回答 三个节点", 3, nodes.size)
        assertTrue(nodes[0] is ChatNode.Single)
        val chain = nodes[1] as ChatNode.Chain
        assertEquals("过程链应包含 assistant + 两条回执", 3, chain.msgs.size)
        assertTrue("最后的回答必须单独成节点", nodes[2] is ChatNode.Single)
        assertEquals("好的，计划如下", (nodes[2] as ChatNode.Single).msg.content)
    }

    @Test
    fun `带正文的 assistant 永远不并入过程链`() {
        // 即使它带了 toolCallsJson，正文也是回答本身，合并等于把回答藏起来
        val list = listOf(
            msg(1, "assistant", content = "这是回答", toolCalls = """[{"id":"c1"}]"""),
            msg(2, "tool", "[OK] x", toolCallId = "c1")
        )
        val nodes = buildChatNodes(list)
        assertEquals(2, nodes.size)
        assertTrue(nodes[0] is ChatNode.Single)
        assertEquals("这是回答", (nodes[0] as ChatNode.Single).msg.content)
    }

    @Test
    fun `过程链不跨用户消息合并`() {
        val list = listOf(
            msg(1, "user", "第一问"),
            msg(2, "assistant", content = "", toolCalls = """[{"id":"c1"}]"""),
            msg(3, "tool", "[OK] a", toolCallId = "c1"),
            msg(4, "user", "第二问"),
            msg(5, "assistant", content = "", toolCalls = """[{"id":"c2"}]"""),
            msg(6, "tool", "[OK] b", toolCallId = "c2")
        )
        val nodes = buildChatNodes(list)
        assertEquals("两次提问各自成链：用户 / 链1 / 用户 / 链2", 4, nodes.size)
        assertTrue(nodes[0] is ChatNode.Single)   // user
        assertTrue(nodes[1] is ChatNode.Chain)    // 链 1
        assertTrue(nodes[2] is ChatNode.Single)   // user
        assertTrue(nodes[3] is ChatNode.Chain)    // 链 2
        // 两条链不能互相污染
        assertEquals(2, (nodes[1] as ChatNode.Chain).msgs.size)
        assertEquals(2, (nodes[3] as ChatNode.Chain).msgs.size)
    }

    @Test
    fun `孤立的工具调用与孤立回执保持单条渲染`() {
        val list = listOf(
            msg(1, "assistant", content = "", toolCalls = """[{"id":"c1"}]"""),
            msg(2, "assistant", content = "直接回答")
        )
        val nodes = buildChatNodes(list)
        assertEquals("没有回执的过程消息不成链", 2, nodes.size)
        assertTrue(nodes[0] is ChatNode.Single)
        assertTrue(nodes[1] is ChatNode.Single)
    }

    @Test
    fun `普通对话不受影响`() {
        val list = listOf(
            msg(1, "user", "你好"),
            msg(2, "assistant", content = "你好呀"),
            msg(3, "system", "已切换模型")
        )
        val nodes = buildChatNodes(list)
        assertEquals(3, nodes.size)
        assertTrue(nodes.all { it is ChatNode.Single })
    }
}
