package io.github.m0use001.planability.ui.info

import io.github.m0use001.planability.data.ChatMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「自动书签」纯函数回归闸（2026-11-02）。
 * 抽取/摘要/倒序 index 换算/当前位联动——面板 UI 之外的全部逻辑。
 */
class ChatBookmarksTest {

    private fun user(id: Long, content: String) =
        ChatMessage(id = id, sessionId = 1, role = "user", content = content)
    private fun assistant(id: Long, content: String = "回答") =
        ChatMessage(id = id, sessionId = 1, role = "assistant", content = content)
    private fun toolCall(id: Long, content: String = "") =
        ChatMessage(id = id, sessionId = 1, role = "assistant", content = content, toolCallsJson = "[{\"id\":\"t\"}]")
    private fun tool(id: Long) = ChatMessage(id = id, sessionId = 1, role = "tool")

    @Test
    fun `每条用户消息一个书签_过程链不算书签`() {
        val nodes = buildChatNodes(listOf(
            user(1, "第一问"), assistant(2), toolCall(3), tool(4),
            assistant(5, "第一答"), user(6, "第二问"), assistant(7, "第二答")
        ))
        val bms = buildChatBookmarks(nodes)
        assertEquals(2, bms.size)
        assertEquals("第一问", bms[0].label)
        assertEquals(1, bms[0].ordinal)
        assertEquals(0, bms[0].nodeIndex)          // user(1) 是正序第 0 个节点
        assertEquals(2, bms[1].ordinal)
        assertEquals("第二问", bms[1].label)
    }

    @Test
    fun `摘要取首行截40字_空消息有占位`() {
        val raw = "生成一篇20000字小说，内容是毒舌机械龙小周，能从所有还原性物质中获取能量，血液可以治疗机械，非常追求有格调的生活"
        val l40 = raw.take(40)
        assertEquals(l40 + "…", chatBookmarkSummary(raw + "\n\n第二行不进摘要"))
        assertTrue(chatBookmarkSummary(l40).startsWith(l40.dropLast(1)))
        assertEquals("短消息", chatBookmarkSummary("短消息"))
        assertEquals("前后空白", chatBookmarkSummary("  \n 　前后空白 \n"))
        assertEquals("（无文字内容）", chatBookmarkSummary(""))
        assertEquals("（无文字内容）", chatBookmarkSummary("   \n  \n"))
    }

    @Test
    fun `正序布局下节点下标就是列表下标`() {
        // 2026-11-05 八修：items(nodes) 不再 asReversed → 恒等映射
        assertEquals(0, chatLazyIndex(nodeIndex = 0, nodeCount = 10))
        assertEquals(9, chatLazyIndex(nodeIndex = 9, nodeCount = 10))
        assertEquals(5, chatLazyIndex(nodeIndex = 5, nodeCount = 10))
    }

    @Test
    fun `当前位联动_视口顶之前最后一条_滚到底亮最新`() {
        // 8 条消息 → 2 个 user 节点（正序 0 与 2）
        val msgs = listOf(
            user(1, "q1"), assistant(2), user(3, "q2"), assistant(4)
        )
        val nodes = buildChatNodes(msgs)
        val bms = buildChatBookmarks(nodes)
        val n = nodes.size
        // 停在最上方（firstVisible=0）：还没过顶任何更晚的书签 → 亮第一条
        assertEquals(bms.first(), currentChatBookmark(bms, 0, n))
        // 滚到底（firstVisible = 最后一个节点）：亮最后一条
        assertEquals(bms.last(), currentChatBookmark(bms, n - 1, n))
        // 视口顶正好落在 q2 上 → 亮 q2
        val q2 = bms.last()
        assertEquals(q2, currentChatBookmark(bms, chatLazyIndex(q2.nodeIndex, n), n))
        // 视口顶落在 q2 之后（超过它）→ 仍然亮 q2（之前最后一条）
        assertEquals(q2, currentChatBookmark(bms, n - 1, n))
    }

    @Test
    fun `当前位联动_视口顶在两条书签之间亮更早那条`() {
        val msgs = listOf(
            user(1, "q1"), assistant(2), user(3, "q2"), assistant(4), user(5, "q3"), assistant(6)
        )
        val nodes = buildChatNodes(msgs)
        val bms = buildChatBookmarks(nodes)
        // q2 在正序 2，q3 在正序 4：视口顶落在 3（q2 之后、q3 之前）→ 亮 q2
        assertEquals(bms[1], currentChatBookmark(bms, 3, nodes.size))
    }

    @Test
    fun `空会话与纯助手会话不炸`() {
        assertTrue(buildChatBookmarks(emptyList()).isEmpty())
        val bms = buildChatBookmarks(buildChatNodes(listOf(assistant(1, "只有回答"))))
        assertTrue(bms.isEmpty())
    }
}
