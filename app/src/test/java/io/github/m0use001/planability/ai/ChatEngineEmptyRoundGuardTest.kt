package io.github.m0use001.planability.ai

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 2026-11-13 手机侧取证指派（真机会话实录）：小说助理那轮模型**整轮零内容
 * 返回**（0 字 assistant 消息，位置在读完 场景.md 之后、三个文件读取之前，tools_audit
 * 该轮无调用）——旧逻辑 `toolAcc.isEmpty()` 分支直接 finalize 成 done+空内容=静默
 * 空气泡，用户以为 AI 死了。
 *
 * 守卫钉死：无内容+无工具调用的轮必须置 error（走统一错误收尾，红条+可重新生成），
 * 不许把「没话说」装成「说完了」。
 */
class ChatEngineEmptyRoundGuardTest {

    private fun read(rel: String): String {
        val roots = listOf("app/src/main/java/io/github/m0use001/planability/", "src/main/java/io/github/m0use001/planability/")
        roots.forEach { r ->
            val f = java.io.File(r + rel)
            if (f.isFile) return f.readText()
        }
        return java.io.File("app/src/main/java/io/github/m0use001/planability/" + rel).readText()
    }

    @Test
    fun `空内容且无工具调用的轮必须置 error 而非静默空气泡`() {
        val src = read("ai/ChatEngine.kt")
        // 守卫必须落在 toolAcc.isEmpty() 分支内、finalizeRow 之前
        val branch = src
            .substringAfter("if (toolAcc.isEmpty()) {")
            .substringBefore("finalizeRow(currentRowId, roundContent")
        assertTrue(
            "无内容+无工具调用的轮没置 error（静默空气泡回归：用户会以为 AI 死了）",
            branch.contains("roundContent.isBlank() && roundReasoning.isBlank()")
        )
        assertTrue(
            "置 error 后必须 break 出轮循环（交给统一错误收尾）",
            branch.contains("break@rounds")
        )
        assertTrue(
            "错误文案要告诉用户怎么办（重新生成/换模型）",
            branch.contains("重新生成")
        )
    }
}
