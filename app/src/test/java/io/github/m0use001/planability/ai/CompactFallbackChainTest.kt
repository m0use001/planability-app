package io.github.m0use001.planability.ai

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 2026-11-15 用户两报：
 * ① 「本地模型也总是压缩失败」——压缩槽指向本机端点时，只要端点 TCP 活着，
 *    usableWorkerConfig 就返回同一份本地配置 → 与主槽 sameCfg → 不重试 →
 *    本地调用一失败就整条压缩失败，永远没有云端兜底。
 * ② 「选云端模型，压缩一次后就也不能用了」——详细上下文编辑器里
 *    `busy = true` 之后若 manualCompactRange 抛异常，`busy = false` 执行不到，
 *    压缩按钮永久禁用。
 *
 * 这两条都是**单测跑不到**的（需要真端点/真异常），按仓库惯例用源码守卫钉住结构。
 */
class CompactFallbackChainTest {

    private fun src(rel: String) = File(rel).readText(Charsets.UTF_8)

    private fun code(rel: String) = src(rel).lines()
        .filterNot { it.trimStart().startsWith("//") || it.trimStart().startsWith("*") }
        .joinToString("\n")

    @Test
    fun 压缩三级回退链_本地失败能兜到云端() {
        val ai = code("src/main/java/io/github/m0use001/planability/ai/AiClient.kt")
        assertTrue(
            "AiClient 缺 summaryFallback（本机主槽必须给云端备槽）",
            ai.contains("fun summaryFallback(")
        )
        assertTrue(
            "summaryFallback 没按 loopback 分流：本机端点应直接给云端主模型",
            ai.contains("if (isLoopback(primary.baseUrl)) config else usableWorkerConfig()")
        )
        assertTrue("AiClient 缺 isLoopback 工具函数", ai.contains("fun isLoopback("))

        val eng = code("src/main/java/io/github/m0use001/planability/ai/ChatEngine.kt")
        assertTrue(
            "summarizeNow/askSummaryModel 没接 summaryFallback —— 本地端点活着但调用失败时仍无兜底",
            eng.contains("AiClient.summaryFallback(")
        )
        assertTrue(
            "压缩调用链又退回「sameCfg 就跳过」的两级逻辑（本地失败=整条失败）",
            !eng.contains("val sameCfg = fallback.modelName == primary.modelName")
        )
        // 三级链：primary / fallback / 云端主模型 都要进链
        assertTrue(
            "压缩链里没有云端主模型兜底（AiClient.config）",
            eng.contains("val cloudMain = AiClient.config")
        )
    }

    @Test
    fun 区间压缩按钮_异常后必须复位busy() {
        val ed = code("src/main/java/io/github/m0use001/planability/ui/info/ChatContextEditor.kt")
        val start = ed.indexOf("ChatEngine.manualCompactRange(")
        assertTrue("找不到 manualCompactRange 调用点（挪位置了？更新锚点）", start > 0)
        val block = ed.substring(maxOf(0, start - 200), minOf(ed.length, start + 1800))
        assertTrue(
            "manualCompactRange 调用没有 try/finally —— 抛异常时 busy 永真、压缩按钮永久禁用",
            block.contains("finally") && block.contains("busy = false")
        )
    }
}
