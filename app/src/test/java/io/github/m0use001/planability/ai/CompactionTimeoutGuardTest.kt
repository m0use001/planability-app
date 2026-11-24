package io.github.m0use001.planability.ai

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * P1-3 守卫：上下文压缩**无超时出口**（2026-11-09 审计动态实录：压缩卡 30+ 分钟，
 * 期间输入区一直「正在压缩上下文…」、撤销 chips 点击全被吞）。
 *
 * 事故形状：summarizeNow 里 `AiClient.chatWithCounted(primary, ...)` 是裸调用，
 * 本地/弱模型卡住时永不见返回值 → compacting 状态不落 → 会话界面冻结。
 * 期望：压缩模型调用有硬超时（≤180s），超时给明确原因并跳过本次压缩，历史保持不动。
 */
class CompactionTimeoutGuardTest {

    private fun source(): String {
        val f = File("src/main/java/io/github/m0use001/planability/ai/ChatEngine.kt")
        assertTrue("源码不在了（cwd 应为 app 模块目录）", f.isFile)
        return f.readText(Charsets.UTF_8)
    }

    @Test
    fun `压缩模型调用必须有硬超时`() {
        val src = source()
        assertTrue(
            "summarizeNow 的压缩调用必须包在 withTimeout/withTimeoutOrNull 里（否则模型卡住=会话永久冻结）",
            src.contains("COMPACT_TIMEOUT_MS") && src.contains("withTimeoutOrNull"),
        )
    }

    @Test
    fun `超时后必须给明确原因并跳过`() {
        val src = source()
        assertTrue(
            "超时分支必须写 lastCompactBlockedReason（「压缩超时…已跳过」），用户/模型能看到发生了什么",
            src.contains("压缩超时"),
        )
    }
}
