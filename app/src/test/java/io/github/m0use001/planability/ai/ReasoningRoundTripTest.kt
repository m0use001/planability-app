package io.github.m0use001.planability.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 思考模式的 `reasoning_content` 回传规则。
 *
 * ## 为什么值得单独钉
 *
 * 这是**只在第二轮才会炸**的 bug：第一轮回答一切正常，第二轮请求把上一轮的 assistant
 * 消息序列化进历史，少了 `reasoning_content`，服务端直接 400：
 *
 * > The `reasoning_content` in the thinking mode must be passed back to the API.
 *
 * 2026-10-20 真机用 `z-ai/glm-5.3-flash` 复现。原来的代码只给"带工具调用"的分支加了
 * 这个字段，普通 assistant 消息走 else 分支被漏掉。这类 bug 人手点一遍很容易漏
 * （要点两轮、还要用思考模型），但一条纯函数断言就能永久钉住。
 */
class ReasoningRoundTripTest {

    @Test
    fun `普通 assistant 消息必须带回 reasoning_content`() {
        val arr = toApiMessages(
            listOf(
                ChatApiMessage(role = "user", content = "生成一张流程图"),
                ChatApiMessage(role = "assistant", content = "好的，给你一张……", reasoning = "先想节点，再连边"),
                ChatApiMessage(role = "user", content = "再画一张"),
            )
        )
        val assistant = arr.getJSONObject(1)
        assertEquals("assistant", assistant.getString("role"))
        assertTrue(
            "第二轮请求里 assistant 少了 reasoning_content —— 思考模式下服务端会 400",
            assistant.has("reasoning_content")
        )
        assertEquals("先想节点，再连边", assistant.getString("reasoning_content"))
    }

    @Test
    fun `没有思考内容的 assistant 不写这个字段（非思考模型不能多出未知字段）`() {
        val arr = toApiMessages(listOf(ChatApiMessage(role = "assistant", content = "纯文本回答")))
        assertFalse(arr.getJSONObject(0).has("reasoning_content"))
    }

    @Test
    fun `user 消息即使带了 reasoning 也不写（那不是 assistant 的历史）`() {
        val arr = toApiMessages(listOf(ChatApiMessage(role = "user", content = "你好", reasoning = "不该出现")))
        assertFalse(arr.getJSONObject(0).has("reasoning_content"))
    }

    @Test
    fun `带工具调用的 assistant 仍然回传 reasoning（原有行为不能回退）`() {
        val calls = """[{"id":"c1","type":"function","function":{"name":"todo_query","arguments":"{}"}}]"""
        val arr = toApiMessages(
            listOf(
                ChatApiMessage(
                    role = "assistant",
                    content = "",
                    toolCallsJson = calls,
                    reasoning = "先查待办",
                )
            )
        )
        val a = arr.getJSONObject(0)
        assertTrue(a.has("reasoning_content"))
        assertEquals("先查待办", a.getString("reasoning_content"))
        assertTrue(a.has("tool_calls"))
    }
}
