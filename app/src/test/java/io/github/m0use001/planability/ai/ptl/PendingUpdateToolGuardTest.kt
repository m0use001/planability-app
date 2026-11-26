package io.github.m0use001.planability.ai.ptl

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 2026-11-12 用户「AI说无法处理草稿内容」回归守卫（◆ 晚 12:49）。
 *
 * 真机实锤：模型两次调 `pending_update` → **「未知工具 pending_update」**，随后对用户说
 * 「我这边没有能修改草稿时刻的工具」——这次不是幻觉，是**真缺工具**：草稿只有
 * add/query/commit/ignore/suggest/purge，没有"改内容"。
 *
 * 本守卫钉死四件事：
 * 1. 工具已注册（ToolDefs）且在 dispatch 链上（ToolExecImpls 字面量 + LifeTools 分支）；
 * 2. 进召回地板（模型点名用它时永远可见）；
 * 3. 修改**同步 payload**——入库按 payload 优先重建，只改 timeText 不删 payload 的
 *    date/due/start/end = 入库还是旧时间（静默失效）；
 * 4. 非 pending 状态的草稿拒改（已入库/已忽略的不能偷偷改）。
 */
class PendingUpdateToolGuardTest {

    private fun read(rel: String): String {
        val roots = listOf("app/src/main/java/io/github/m0use001/planability/", "src/main/java/io/github/m0use001/planability/")
        roots.forEach { r ->
            val f = java.io.File(r + rel)
            if (f.isFile) return f.readText()
        }
        return java.io.File("app/src/main/java/io/github/m0use001/planability/" + rel).readText()
    }

    private val life = read("ai/ptl/LifeTools.kt")
    private val exec = read("ai/ptl/ToolExecImpls.kt")

    @Test
    fun `pending_update 已注册且在执行链上`() {
        assertTrue("ToolDefs 没有 pending_update 定义", ToolRegistry.ALL.any { it.name == "pending_update" })
        assertTrue("info 域没登记 pending_update（不开这个域就永远看不见）",
            ToolRegistry.DOMAINS["info"]?.contains("pending_update") == true)
        assertTrue("ToolExecImpls 分发表缺 pending_update 字面量", exec.contains("\"pending_update\""))
        assertTrue("LifeTools 没有 pendingUpdate 分支", life.contains("\"pending_update\" -> pendingUpdate"))
    }

    @Test
    fun `pending_update 必须进召回地板`() {
        assertTrue("pending_update 必须在召回地板（模型点名用它的轮次不能看不见）",
            "pending_update" in ToolRouter.FLOOR)
    }

    @Test
    fun `改时间必须同步 payload 否则入库还原旧时间`() {
        val block = life.substringAfter("private suspend fun pendingUpdate")
            .substringBefore("private suspend fun pendingSuggest")
        assertTrue("没删 payload 的时间键（date/due/start/end）——commit 按 payload 优先，改了等于没改",
            Regex("listOf\\(\"date\", \"due\", \"time\", \"start\", \"end\"\\)\\.forEach").containsMatchIn(block))
        assertTrue("没把改动写回 payload", block.contains("nt = nt.copy(payload = pb.toString())"))
        assertTrue("改时间没落到 suggestedStart/End（commit 的兜底来源）",
            block.contains("suggestedStart = at"))
        // 改标题/地点也要同步 payload（commit 的 title 取值是 payload 优先）
        assertTrue("改标题没同步 payload", block.contains("pb.put(\"title\", nt.title)"))
        assertTrue("改地点没同步 payload", block.contains("pb.put(\"place\", nt.place)"))
    }

    @Test
    fun `非 pending 草稿拒绝修改`() {
        val block = life.substringAfter("private suspend fun pendingUpdate")
            .substringBefore("private suspend fun pendingSuggest")
        assertTrue("没有 status 守卫（已入库/已忽略的草稿不能被改）",
            block.contains("if (item.status != \"pending\")"))
    }
}
