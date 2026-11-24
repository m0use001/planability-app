package io.github.m0use001.planability.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * v64「宽窄判定改实测窗口宽」守卫（2026-11-20）。
 *
 * 用户报障：「信息页面这个菜单栏与右划菜单非常不协调」——分屏 / 虚拟屏里 452dp 的窗口
 * 被当成 1600dp 宽屏，Rail + 340dp 常驻侧栏把内容挤成一条缝。根因是
 * `LocalConfiguration.screenWidthDp` 报的是**显示器**宽而不是**窗口**宽。
 *
 * 本守卫只钉「宽度口径 + 外壳/信息页按实测 ≥840dp 判宽屏」这一层（不涉及信息页宽屏
 * 具体版式——那由 WideBoardLayoutGuardTest 钉圆角两板）。退回去编译照样过、跑照样跑，
 * 只是用户又会看见那条缝，所以结构必须钉在源码上。
 */
class NavWideMergeGuardTest {

    private val appDir = "app/src/main"

    private val windowSizePath = "$appDir/java/io/github/m0use001/planability/core/WindowSize.kt"
    private val navShellPath = "$appDir/java/io/github/m0use001/planability/app/NavShell.kt"
    private val infoPath = "$appDir/java/io/github/m0use001/planability/ui/info/InfoCenterScreen.kt"

    private val allowed = setOf(windowSizePath, navShellPath, infoPath)

    private val repoRoot: File by lazy {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null && !File(dir, appDir).isDirectory) {
            dir = dir.parentFile
        }
        requireNotNull(dir) { "找不到仓库根" }.canonicalFile
    }

    private fun readRepoFile(relative: String): String {
        require(relative in allowed) { "不在白名单里的文件：$relative" }
        val root = repoRoot.canonicalFile
        val f = File(root, relative).canonicalFile
        assertTrue("路径越界：$relative", f.path.startsWith(root.path + File.separator))
        assertTrue("文件不在了：$relative", f.isFile)
        return f.readText(Charsets.UTF_8)
    }

    @Test
    fun `宽度判定读实测窗口 而不是 Configuration 的显示器宽`() {
        val size = readRepoFile(windowSizePath)
        assertTrue(
            "core/WindowSize.kt 没有提供实测窗宽（LocalWindowInfo.containerSize）——" +
                "分屏 / 自由窗口 / 虚拟屏里 screenWidthDp 报的是显示器宽，宽屏分支会提前触发",
            size.contains("LocalWindowInfo.current.containerSize")
        )
        assertTrue("实测窗宽函数 rememberMeasuredWindowWidthDp 不在位",
            size.contains("fun rememberMeasuredWindowWidthDp("))
        assertTrue("外壳形态判定函数 rememberShellRailForm 不在位",
            size.contains("fun rememberShellRailForm("))
        assertTrue(
            "外壳形态没按 840dp 断点裁决（用户拍板：600–840dp 退回手机形态）",
            size.contains("EXPANDED_WIDTH_BREAKPOINT_DP")
        )
    }

    @Test
    fun `外壳的 Rail 与底部避让都跟实测形态走`() {
        val shell = readRepoFile(navShellPath)
        assertTrue(
            "NavShell 没走实测形态判定 rememberShellRailForm()——分屏里仍按显示器宽上 Rail，内容被挤成缝",
            shell.contains("rememberShellRailForm()")
        )
        assertFalse(
            "NavShell 里还有地方按 rememberWindowSizeClass().isWide 判形态——" +
                "那读的是显示器宽（分屏 / 自由窗口里谎报成宽屏）",
            shell.contains("rememberWindowSizeClass().isWide")
        )
    }

    @Test
    fun `信息页宽窄判定用实测形态`() {
        val info = readRepoFile(infoPath)
        assertTrue(
            "信息页的宽窄判定没走实测形态判定 rememberShellRailForm()——分屏里会提前上宽屏两板",
            info.contains("rememberShellRailForm()")
        )
        assertFalse(
            "信息页还在按 rememberWindowSizeClass().isWide 判宽窄（读显示器宽，谎报）",
            info.contains("rememberWindowSizeClass().isWide")
        )
    }
}
