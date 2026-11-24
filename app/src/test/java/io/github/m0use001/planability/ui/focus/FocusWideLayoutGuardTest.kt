package io.github.m0use001.planability.ui.focus

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 专注页平板「大而空」批的**接线**守卫（2026-11-19）。
 *
 * 为什么要守卫：这一批的改动全是「布局几何」，Compose 里没法单测——
 * 一旦有人把分档改回整页宽、把 `heightIn(min=)` 删掉（Center/SpaceBetween 又会变回空操作）、
 * 或者把侧栏写死回 340dp，功能测试全绿但屏幕上立刻复现用户那张截图。
 * 数值本身由 [FocusLayoutTest] 穷举，这里只钉**接线**：这些 API 必须被真的用上。
 *
 * 路径安全：与 TabletAdaptationGuardTest 同款——仓库根靠固定目录向上找，不接受路径参数。
 */
class FocusWideLayoutGuardTest {

    private val appDir = "app/src/main"

    private val repoRoot: File by lazy {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null && !File(dir, appDir).isDirectory) {
            dir = dir.parentFile
        }
        requireNotNull(dir) { "找不到仓库根" }.canonicalFile
    }

    private fun read(relative: String): String {
        val root = repoRoot.canonicalFile
        val f = File(root, relative).canonicalFile
        assertTrue("路径越界：$relative", f.path.startsWith(root.path + File.separator))
        assertTrue("文件不存在：$relative", f.isFile)
        return f.readText()
    }

    private val focusScreen: String
        get() = read("$appDir/java/io/github/m0use001/planability/ui/focus/FocusScreen.kt")

    @Test
    fun `分档用内容区实测宽高 不用窗口宽`() {
        val src = focusScreen
        assertTrue(
            "FocusScreen 没有 BoxWithConstraints —— 分档会退回 screenWidthDp（含 80dp Rail，多算一档）",
            src.contains("BoxWithConstraints(Modifier.fillMaxSize())")
        )
        assertTrue("没有按可用宽分档", src.contains("focusIsTwoPane(usableWidthDp)"))
        assertTrue("环径没有按左板实宽算（会溢板）", src.contains("focusLeftPaneWidthDp(usableWidthDp)"))
    }

    @Test
    fun `每块板钉最小高度 居中与均分才会真的生效`() {
        val src = focusScreen
        // 两块板都要钉（漏一块就等于那块板的 Center 退回空操作）
        val pinned = src.split("heightIn(min = paneMinHeight)").size - 1
        assertTrue(
            "宽屏板缺少 heightIn(min = …)（现有 $pinned 处，应为 2）——滚动容器按无限高测量，" +
                "verticalArrangement 会被忽略（v60 的垂直居中其实从未生效）",
            pinned >= 2
        )
        assertTrue("左板没有 Center 排列", src.contains("verticalArrangement = Arrangement.Center"))
        // 2026-11-19 用户「授权全改」：侧栏从 SpaceBetween 改成整组 Center。
        // 每栏只有 3 张卡时 SpaceBetween 会把余下 ~760dp 全摊进卡与卡之间
        //（1600×1000dp 实测缝隙 120dp ≈ 卡高的 60~75%），卡片读成互不相干的浮块。
        val centered = src.split("verticalArrangement = Arrangement.Center").size - 1
        assertTrue(
            "两块板没都走整组居中（现有 $centered 处，应为 2）",
            centered >= 2
        )
        assertFalse(
            "侧栏又退回 SpaceBetween —— 缝隙会重新摊成 120dp 大空档（卡片割裂）",
            src.contains("verticalArrangement = Arrangement.SpaceBetween")
        )
    }

    @Test
    fun `宽屏走共享两板骨架 不许再有直角三栏`() {
        val src = focusScreen
        assertTrue(
            "宽屏专注页没有走共享两板骨架（SplitBoards/BoardDrill）——" +
                "直角列 + 浮起小卡是旧材质，与计划页/首页不是同一套网格",
            src.contains("SplitBoards(") || src.contains("BoardDrill(") ||
                src.contains("BoardDeck(") || src.contains("NavHost(")
        )
        assertFalse(
            "专注页还有旧的宽屏三栏分支（FocusColumns）——三栏已退役",
            src.contains("FocusColumns")
        )
        assertFalse(
            "宽屏专注页还在用 VerticalDivider 分栏——栏间只留白缝",
            src.contains("VerticalDivider(")
        )
    }

    @Test
    fun `侧栏填真内容 且不再写死 340dp`() {
        val src = focusScreen
        assertTrue("侧栏没有「接下来要做」", src.contains("FocusNextUpPane("))
        assertTrue("统计三块被删了", src.contains("TodayFocusSummary(goalMin, inBoard = true)") &&
            src.contains("FocusWeekBars()") && src.contains("FocusWeekStats()"))
        // v70（2026-11-21 用户拍板）：「最近专注」整节撤出概览板（概览板不滚动，
        // 内容按视口一屏收纳——删掉这一节后正好放得下）。
        assertFalse("「最近专注」被要求撤出概览板后又回来了", src.contains("FocusRecentSessions"))
        assertFalse(
            "侧栏又写死 width(340.dp)——超宽屏右侧会重新留出一大片空",
            src.contains("width(340.dp)")
        )
        assertTrue("右板宽度没有走共享板宽常量", src.contains("BoardLayout.DetailWidth"))
    }

    @Test
    fun `手机单列分支保持原顺序`() {
        val src = focusScreen
        val single = src.substringAfter("if (!wide)", "")
        assertTrue("窄屏分支不见了", single.isNotEmpty())
        val order = listOf("focusRing()", "focusOverlaySwitch()", "focusDurations()", "focusControls()", "TodayFocusSummary(goalMin)")
        var last = -1
        order.forEach { token ->
            val idx = single.indexOf(token)
            assertTrue("窄屏分支缺 $token", idx >= 0)
            assertTrue("窄屏分支顺序被改了（$token）", idx > last)
            last = idx
        }
    }

    @Test
    fun `纯函数与待办口径文件在位`() {
        val layout = read("$appDir/java/io/github/m0use001/planability/ui/focus/FocusLayout.kt")
        assertTrue(layout.contains("fun focusIsTwoPane("))
        assertTrue(layout.contains("fun focusLeftPaneWidthDp("))
        assertTrue(layout.contains("fun focusRingSizeDp("))
        val nextUp = read("$appDir/java/io/github/m0use001/planability/ui/focus/FocusNextUp.kt")
        assertTrue(nextUp.contains("fun nextUpItems("))
        // 焦点页不许长出第二条写路径：挑条模块里不允许出现写库调用
        assertFalse("FocusNextUp 里出现了写库调用", nextUp.contains("insert") || nextUp.contains("update"))
    }
}
