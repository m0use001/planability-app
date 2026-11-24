package io.github.m0use001.planability.app

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * v70f（2026-11-21 用户两条新规之二：「在二级界面点击跨页面跳转按钮，也要接入
 * 上下切换的页面动画」+「全量检查，把所有不合规定的地方都改过来」）：
 *
 * 切页动效按**跳转来源**分两条线——
 * 1. UI 里的跨页跳转按钮（一级或二级界面里的都算：专注页跳计划、我的→手机操控区域、
 *    搜索抽屉行、时间轴块、聊天 /automation……）= **纵滑抽纸条**（slideSwitchTo），
 *    与 Rail/底栏点按同款。实现=Router.pendingJumpSlide 默认 true，所有 UI 调用点
 *    零改动自动接入（Router 是全部跨页跳转的唯一漏斗）。
 * 2. 系统深链（小组件/快捷方式/分享/通知/调试钩子，全部经 MainActivity.handleShortcut）
 *    = quick fade（v68 拍板保留：冷启动/后台拉起不是用户在本 app 里抽纸条）。
 *    handleShortcut 统一置 false，NavShell 消费后经 consumeTab 归位。
 */
class NavJumpAnimationGuardTest {

    private fun readRepoFile(relative: String): String {
        val f = listOf(
            File("src/main/java/io/github/m0use001/planability/$relative"),
            File("app/src/main/java/io/github/m0use001/planability/$relative")
        ).firstOrNull { it.isFile }
        assertTrue("找不到 $relative", f != null)
        return f!!.readText(Charsets.UTF_8).replace("\r\n", "\n")
    }

    private fun codeOnly(src: String): String = src.lines().map { line ->
        val i = line.indexOf("//")
        if (line.trimStart().startsWith("//")) "" else if (i >= 0) line.substring(0, i) else line
    }.joinToString("\n")

    @Test
    fun `UI 跳转默认纵滑 Router 旗标默认 true 且消费后归位`() {
        val router = codeOnly(readRepoFile("app/Router.kt"))
        assertTrue(
            "Router 没有 pendingJumpSlide 旗标——UI 跨页跳转按钮没接入纵滑切页（v70f 用户拍板）",
            router.contains("var pendingJumpSlide by mutableStateOf(true)")
        )
        val consume = router.substringAfter("fun consumeTab()", "")
        assertTrue("找不到 consumeTab", consume.isNotEmpty())
        assertTrue(
            "consumeTab 没有把 pendingJumpSlide 归位回 true——一次深链降级会把后续所有 UI 跳转一起按成 fade",
            consume.contains("pendingJumpSlide = true")
        )
    }

    @Test
    fun `Router 收集器必须按旗标分叉 纵滑与 fade 两条路都在`() {
        val nav = codeOnly(readRepoFile("app/NavShell.kt"))
        val collector = nav.substringAfter("snapshotFlow { Router.targetTab }", "")
        assertTrue("找不到 Router 收集器", collector.isNotEmpty())
        val body = collector.substringBefore("}\n    }", collector)
        assertTrue(
            "Router 收集器没有读 pendingJumpSlide——跨页跳转动效没有按来源分",
            body.contains("val slide = Router.pendingJumpSlide")
        )
        assertTrue(
            "Router 收集器没有按旗标分叉（纵滑 slideSwitchTo / 深链 fadeSwitchTo 两条路都要在）",
            body.contains("if (slide) slideSwitchTo(idx) else fadeSwitchTo(idx)")
        )
    }

    @Test
    fun `系统深链统一降级 fade（handleShortcut 置 false）`() {
        val main = codeOnly(readRepoFile("MainActivity.kt"))
        val handler = main.substringAfter("private fun handleShortcut(", "")
        assertTrue("找不到 handleShortcut", handler.isNotEmpty())
        val head = handler.substringBefore("when (intent?.action)", "")
        assertTrue(
            "handleShortcut 没有在入口置 pendingJumpSlide = false——小组件/通知等系统深链会跟着纵滑（v68 拍板保留 fade）",
            head.contains("Router.pendingJumpSlide = false")
        )
        assertTrue(
            "DEBUG_STREAM 调试钩子的 openSession 没有降级 fade（系统拉起不算 UI 按钮）",
            main.contains("Router.pendingJumpSlide = false").let { _ ->
                Regex("Router\\.pendingJumpSlide = false").findAll(main).count() >= 3
            }
        )
    }
}
