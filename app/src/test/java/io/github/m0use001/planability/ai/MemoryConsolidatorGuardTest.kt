package io.github.m0use001.planability.ai

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 记忆整理「无限重试」守卫（2026-11-10 真机事故）。
 *
 * 现象：手机持续发烫，`localai` 进程 CPU 262%（2.6 核）数分钟不退；日志里
 * `chat request msgs=2 maxTokens=512` 每 3 秒一次，全部失败于
 * `LLM generate failed: Input prompt too long`。
 *
 * 根因链：
 * ①转录只有单条上限（用户 8000/助手 2000），**没有总量上限**——采集提纯写出的
 *   「信息提取·」会话动辄几百轮，转录破万字符，端侧 4096 ctx 必然超长；
 * ②失败路径**不推进进度**（`advance()` 只在成功路径调）→ 会话永远「待整理」；
 * ③调度器每次切后台/锁屏/进程启动都重跑 → 每 3 秒一次失败 ×2 并发，永远烧。
 *
 * 守卫钉三件事：
 * ①转录必须有总量预算（本地 6000 字符）；
 * ②"Input prompt too long" 这类确定性失败必须推进进度跳过（留 EXTRACT_SKIP_LONG 证据），
 *   不得原样 return 留在待整理队列；
 * ③预算与跳过的判定都要在**重试之前**——超长的重试是纯发热。
 */
class MemoryConsolidatorGuardTest {

    private val src: String = run {
        val candidates = listOf(
            File("src/main/java/io/github/m0use001/planability/ai/MemoryConsolidator.kt"),
            File("app/src/main/java/io/github/m0use001/planability/ai/MemoryConsolidator.kt"),
        )
        candidates.firstOrNull { it.exists() }?.readText()
            ?: error("找不到 MemoryConsolidator.kt")
    }

    @Test
    fun `转录必须有总量预算且超长时截断`() {
        assertTrue("缺 inputBudget（整理输入总量预算）", src.contains("private suspend fun inputBudget()"))
        assertTrue("预算必须区分本地/主模型", src.contains("127.0.0.1") && src.contains("30000"))
        // 超预算截断：保留最近轮次
        assertTrue("缺超预算截断逻辑", src.contains("EXTRACT_TRUNCATED"))
        assertTrue("截断必须从最旧往新砍（reversed）", src.contains("pending.turns.reversed()"))
    }

    @Test
    fun `确定性超长失败必须推进进度而不是留在待整理队列`() {
        val idx = src.indexOf("if (retried == null)")
        assertTrue("失败分支不见了", idx > 0)
        val body = src.substring(idx, minOf(src.length, idx + 1400))
        assertTrue("失败分支必须识别 too long/prompt（确定性失败）",
            body.contains("too long") && body.contains("prompt"))
        assertTrue("超长失败必须 advance 推进进度（否则会话永远待整理 → 无限重烧）",
            body.contains("advance(sessionId"))
        // 跳过路径：EXTRACT_SKIP_LONG 之后紧跟一个【不带 error】的 return Outcome——
        // 若带 error，调度器会标 stalled 并在下次触发时整轮重跑（发烫复发）
        val skipOk = Regex(
            """EXTRACT_SKIP_LONG[\s\S]{0,200}?return Outcome\(turns = pending\.totalTurns\)"""
        ).containsMatchIn(body)
        assertTrue("跳过路径必须 return Outcome(turns=…) 且不带 error", skipOk)
    }

    @Test
    fun `重试之前必须先有截断（超长的重试是纯发热）`() {
        val budgetIdx = src.indexOf("EXTRACT_TRUNCATED")
        val failIdx = src.indexOf("if (retried == null)")
        assertTrue("预算截断必须发生在模型调用失败处理之前", budgetIdx in 0 until failIdx)
    }
}
