package io.github.m0use001.planability.ai

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 思考模型 tool 轮的 `reasoning_content` 回传（源码级可测的纯函数断言）。
 *
 * ## 为什么钉这条
 * 2026-10-31 真机（会话「英语四级备考安排」）复现 400：
 * "The `reasoning_content` in the thinking mode must be passed back to the API"。
 * 查库发现失败前那条 assistant **发起了工具调用但 reasoning 长度为 0**，我们按
 * "blank 就省略字段" 发出去 → DeepSeek 直接拒。实测对照：
 *   省略 reasoning_content → 400；`reasoning_content: ""` → 200。
 * 所以 DeepSeek 系必须补空串；其它厂商保持省略（不塞未知字段，免得它们 400）。
 */
class AiStreamReasoningTest {

    private fun toolCallMessage(reasoning: String) = ChatApiMessage(
        role = "assistant",
        content = "",
        toolCallsJson = """[{"id":"call_1","type":"function","function":{"name":"ping","arguments":"{}"}}]""",
        reasoning = reasoning
    )

    @Test
    fun `DeepSeek 系 tool 轮缺 reasoning 时必须补空串`() {
        val msgs = listOf(
            ChatApiMessage("user", "hi"),
            toolCallMessage(""),
            ChatApiMessage("tool", "pong", toolCallId = "call_1")
        )
        val arr: JSONArray = toApiMessages(msgs, echoEmptyReasoning = true)
        val assist = arr.getJSONObject(1)
        assertTrue(
            "tool 轮的 assistant 必须带 reasoning_content 字段（哪怕是空串），否则 DeepSeek 400",
            assist.has("reasoning_content")
        )
        assertEquals("", assist.getString("reasoning_content"))
    }

    @Test
    fun `非 DeepSeek 厂商保持省略，不塞未知字段`() {
        val msgs = listOf(toolCallMessage(""))
        val arr = toApiMessages(msgs, echoEmptyReasoning = false)
        assertFalse(
            "reasoning 为空时不应凭空多出 reasoning_content（GLM 等可能会拒未知字段）",
            arr.getJSONObject(0).has("reasoning_content")
        )
    }

    @Test
    fun `有 reasoning 时任何分支都要原样回传`() {
        val reasoning = "先想清楚再调用工具"
        val arrTool = toApiMessages(listOf(toolCallMessage(reasoning)), echoEmptyReasoning = false)
        assertEquals(reasoning, arrTool.getJSONObject(0).getString("reasoning_content"))

        val arrText = toApiMessages(
            listOf(ChatApiMessage("assistant", "答案", reasoning = reasoning)),
            echoEmptyReasoning = false
        )
        assertEquals(reasoning, arrText.getJSONObject(0).getString("reasoning_content"))
    }

    @Test
    fun `user 与 tool 消息永远不带 reasoning_content`() {
        val msgs = listOf(
            ChatApiMessage("user", "q"),
            ChatApiMessage("tool", "r", toolCallId = "call_1")
        )
        val arr = toApiMessages(msgs, echoEmptyReasoning = true)
        assertFalse(arr.getJSONObject(0).has("reasoning_content"))
        assertFalse(arr.getJSONObject(1).has("reasoning_content"))
    }
}
