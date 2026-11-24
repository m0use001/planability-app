package io.github.m0use001.planability.ai.ptl

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 工具分发接线守卫（2026-11-07 真机验收踩出来的）。
 *
 * 事故：`kb_ask`/`note_link`/`pending_commit`/`pending_ignore`/`pending_suggest` 五个工具在
 * `LifeTools` 内部 when 里挂着处理器，但 `ToolExecImpls.execute` 的 when 没把它们路由到
 * `LifeTools.run`——落到 else → `PlanTools.run` 返回 null → 「工具 X 未实现」。
 * kb_ask 一死，检索触发策略整条失效；pending_commit 一死，中枢 AI 入库通道只剩 UI 卡片。
 *
 * 单测/EvalSuite 当时没抓到，因为它们直接调 `KbAsk.ask()`，没走 `ToolExecutor`→
 * `ToolExecImpls.execute` 分发链——这是「定义在、接线断」型 bug 的典型盲区。
 *
 * 本守卫补这个盲区：**每个登记进 DOMAINS/PLUGINS 的工具名，都必须在执行器源码
 * （`ToolExecImpls.kt` 或它路由的委托 LifeTools/PlanTools/LibraryTools/FsShellTools/BrowserTools
 * /OfficeWriter/DeepPlanner/PendingImporter 之一）里以字面量出现**。少一个就是「定义了但分发不到」。
 *
 * 源码级断言（同 ContextCostTest / everyToolIsReachableSomewhere 路子）：无 Robolectric，
 * 钉不住行为就钉住结构——比什么都不钉强。
 *
 * 注：Gradle :app:testDebugUnitTest 的工作目录是 app 模块目录，源码用相对路径读，不上溯。
 */
class ToolDispatchWiringTest {

    private fun source(rel: String): String {
        val f = File(rel)
        assertTrue("源码不在了（cwd 应为 app 模块目录）：$rel", f.isFile)
        return f.readText(Charsets.UTF_8)
    }

    /** 执行器源码 + 所有委托文件，合并成一个文本搜名字字面量 */
    private fun dispatchSurface(): String = listOf(
        "src/main/java/io/github/m0use001/planability/ai/ptl/ToolExecImpls.kt",
        "src/main/java/io/github/m0use001/planability/ai/ptl/LifeTools.kt",
        "src/main/java/io/github/m0use001/planability/ai/ptl/PlanTools.kt",
        "src/main/java/io/github/m0use001/planability/ai/ptl/LibraryTools.kt",
        "src/main/java/io/github/m0use001/planability/ai/ptl/FsShellTools.kt",
        "src/main/java/io/github/m0use001/planability/ai/ptl/BrowserTools.kt",
        "src/main/java/io/github/m0use001/planability/ai/ptl/OfficeWriter.kt",
        "src/main/java/io/github/m0use001/planability/ai/ptl/DeepPlanner.kt",
        "src/main/java/io/github/m0use001/planability/ai/ptl/PendingImporter.kt",
    ).joinToString("\n") { source(it) }

    /** 权威注册集 = ToolRegistry.ALL（直接读对象，不解析 map 文本，避免误抓类型枚举词） */
    private fun registeredToolNames(): Set<String> =
        ToolRegistry.ALL.map { it.name }.toSet()

    @Test
    fun `每个登记工具都必须在执行器分发面出现字面量`() {
        val surface = dispatchSurface()
        val missing = registeredToolNames().filter { "\"$it\"" !in surface }.sorted()
        assertTrue(
            "以下登记工具在执行器/委托源码里找不到字面量——定义了但分发不到（kb_ask 类断线）：" +
                missing.joinToString(", "),
            missing.isEmpty()
        )
    }

    @Test
    fun `kb_ask 必须被路由到 LifeTools 而非落到 else`() {
        // 这是踩坑的那条：单独钉住，防回归。LifeTools.run 的路由分支（ToolExecImpls 的 when）
        // 必须显式包含 kb_ask——否则它落到 else → PlanTools.run → null → 「未实现」。
        val exec = source("src/main/java/io/github/m0use001/planability/ai/ptl/ToolExecImpls.kt")
        val lifeBranch = exec.indexOf("LifeTools.run(name, a, sessionId)")
        assertTrue("LifeTools 路由分支没了", lifeBranch >= 0)
        // 分支头到 LifeTools.run 调用之间必须含 kb_ask
        val branch = exec.substring(exec.lastIndexOf("\"focus_query\"", lifeBranch), lifeBranch)
        assertTrue("kb_ask 没被路由到 LifeTools（落 else 会报未实现）", "\"kb_ask\"" in branch)
    }
}
