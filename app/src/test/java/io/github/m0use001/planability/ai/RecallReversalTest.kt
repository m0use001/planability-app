package io.github.m0use001.planability.ai

import io.github.m0use001.planability.data.ChatMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecallReversalTest {

    private fun msg(
        role: String,
        content: String = "",
        toolCallsJson: String = "",
        createdAt: Long = 100,
    ) = ChatMessage(sessionId = 1, role = role, content = content, toolCallsJson = toolCallsJson, createdAt = createdAt)

    private fun calls(vararg pairs: Pair<String, String>): String {
        // args 是字符串字段：JSON 转义后内嵌
        val items = pairs.joinToString(",") { (name, args) ->
            val esc = args.replace("\\", "\\\\").replace("\"", "\\\"")
            """{"id":"c${name.hashCode()}","name":"$name","args":"$esc"}"""
        }
        return "[$items]"
    }

    @Test
    fun toolCallsJson提取memory_add内部名() {
        val m = msg(
            role = "assistant",
            toolCallsJson = calls("memory_add" to """{"name":"主角设定","body":"17岁"}""")
        )
        assertEquals(listOf("主角设定"), RecallReversal.memoryAddInternalNames(listOf(m)))
    }

    @Test
    fun 非memory_add工具忽略() {
        val m = msg(
            role = "assistant",
            toolCallsJson = calls("todo_create" to """{"title":"作业"}""")
        )
        assertTrue(RecallReversal.memoryAddInternalNames(listOf(m)).isEmpty())
    }

    @Test
    fun tool结果行兜底提取内部名称() {
        val m = msg(role = "tool", content = "已记住：湘西赶尸传人（内部名称 主角设定）")
        assertEquals(listOf("主角设定"), RecallReversal.memoryAddInternalNames(listOf(m)))
    }

    @Test
    fun 双层并集去重() {
        val a = msg(role = "assistant", toolCallsJson = calls("memory_add" to """{"name":"白帝城","body":"据点"}"""))
        val t = msg(role = "tool", content = "已记住：据点（内部名称 白帝城）")
        assertEquals(listOf("白帝城"), RecallReversal.memoryAddInternalNames(listOf(a, t)))
    }

    @Test
    fun 坏JSON不炸() {
        val m = msg(role = "assistant", toolCallsJson = "[broken")
        assertTrue(RecallReversal.memoryAddInternalNames(listOf(m)).isEmpty())
        val badArgs = msg(
            role = "assistant",
            toolCallsJson = """[{"id":"c1","name":"memory_add","args":"not json"}]"""
        )
        assertTrue(RecallReversal.memoryAddInternalNames(listOf(badArgs)).isEmpty())
    }

    @Test
    fun 无名args跳过() {
        val m = msg(
            role = "assistant",
            toolCallsJson = calls("memory_add" to """{"body":"只有正文"}""")
        )
        assertTrue(RecallReversal.memoryAddInternalNames(listOf(m)).isEmpty())
    }

    @Test
    fun 空输入返回空() {
        assertTrue(RecallReversal.memoryAddInternalNames(emptyList()).isEmpty())
    }
}
