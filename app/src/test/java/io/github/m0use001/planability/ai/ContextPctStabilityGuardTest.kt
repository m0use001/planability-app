package io.github.m0use001.planability.ai

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 上下文百分比稳定性守卫（2026-11-17 用户报障）。
 *
 * 报障形态：微积分会话（590 条消息 / 92 万字）顶栏的上下文环在 **37%** 与
 * **百分之几**之间来回跳。
 *
 * 根因（数字能对上）：两个口径抢同一个 `ctxTokens`
 * ① `ChatEngine.load()` 写的是**裸历史**估算（清游标后逐条估 token，不受发送预算裁剪）
 *    ≈ 37 万 token ÷ 1M 窗口 = 37%；
 * ② 发送 / 压缩 / 重算写的是 `ContextEngine.assemble()` 的**实际载荷**
 *    （历史按 `MAX_HISTORY_TOKENS = 12000` 裁剪后）≈ 1.3 万 token = 1%。
 * 进会话→37%，发一轮→1%，于是来回跳。
 *
 * 判据：进会话也必须走 assemble 口径（唯一真源），不许再出现"另一套估算"。
 */
class ContextPctStabilityGuardTest {

    /** 源码读取：模块相对路径优先，其次仓库根相对路径（不做向上遍历） */
    private fun source(moduleRel: String): String {
        val cands = listOf(File(moduleRel), File("app", moduleRel))
        val f = cands.firstOrNull { it.isFile }
        assertTrue("源码不在了：$moduleRel（候选：${cands.map { it.path }}）", f != null)
        return f!!.readText(Charsets.UTF_8).replace("\r\n", "\n")
    }

    private val engineSrc: String
        get() = source("src/main/java/io/github/m0use001/planability/ai/ChatEngine.kt")

    @Test
    fun `进会话必须用assemble口径_不许再写裸历史估算`() {
        val src = engineSrc
        val load = src.substringAfter("suspend fun load(sessionId: Long)").substringBefore("private suspend fun resolveSessionCfg")
        assertTrue("load() 必须走 recomputeCtxTokens（唯一真源）", load.contains("recomputeCtxTokens(sessionId)"))
        assertTrue(
            "load() 里不得再出现 ctxTokens = est（裸历史估算＝另一个口径＝会跳）",
            !load.contains("ctxTokens = est")
        )
        assertTrue(
            "load() 里不得再直接按消息逐条 sumOf 估 token",
            !load.contains("sumOf { ContextEngine.estTokens")
        )
    }

    @Test
    fun `ctxTokens只写assemble出来的载荷`() {
        val src = engineSrc
        val writes = Regex("ctxTokens = ([A-Za-z0-9_.]+)").findAll(src).map { it.groupValues[1] }.toList()
        assertTrue("ctxTokens 写入点异常：$writes", writes.isNotEmpty())
        writes.forEach {
            assertTrue(
                "ctxTokens 只能写 assemble 的 totalTokens，发现：$it",
                it == "p.totalTokens" || it == "plan.totalTokens"
            )
        }
        assertTrue("est 口径已废，ctxTokens 不得再写它", !writes.contains("est"))
    }

    @Test
    fun `重算不得在主线程做百万字符的token估算`() {
        val src = engineSrc
        val seg = src.substringAfter("private suspend fun recomputeCtxTokens(").take(900)
        assertTrue("recomputeCtxTokens 必须切到 Default 线程（长会话逐条估 token 会掉帧）",
            seg.contains("withContext(Dispatchers.Default)"))
    }
}
