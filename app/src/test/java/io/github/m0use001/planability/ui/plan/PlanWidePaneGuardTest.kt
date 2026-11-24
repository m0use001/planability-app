package io.github.m0use001.planability.ui.plan

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * v62 平板主从的**接线**守卫（2026-11-19）。
 *
 * 为什么需要它：这一版把计划页宽屏从「一列 + 右边三张卡」改成「两板 + 右栏双态」，
 * 每一条都是**结构性**约定，编译不会拦——退回去照样能编译、照样能跑，只是用户又会看见
 * 「内容块割裂」或者「点一行弹出整屏窗口把两栏盖掉」。这些都是 2026-11-19 截图里
 * 明确点名过的问题，所以钉在源码上，谁改回去谁红。
 *
 * 路径安全沿用 TabletAdaptationGuardTest：仓库根靠固定目录名向上找，
 * 源码文件用白名单定位，不接受任何路径参数。
 */
class PlanWidePaneGuardTest {

    private val appDir = "app/src/main"
    private val planScreenPath = "$appDir/java/io/github/m0use001/planability/ui/plan/PlanScreen.kt"
    private val planPanePath = "$appDir/java/io/github/m0use001/planability/ui/plan/PlanWidePane.kt"
    private val editorsPath = "$appDir/java/io/github/m0use001/planability/ui/edit/Editors.kt"
    // v64（2026-11-20）：两板材质抽成了共享层，几处结构断言跟着搬到 SplitBoard.kt。
    private val splitBoardPath = "$appDir/java/io/github/m0use001/planability/ui/common/SplitBoard.kt"

    private val allowed = setOf(planScreenPath, planPanePath, editorsPath, splitBoardPath)

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

    /**
     * 去掉注释：解释性注释里会引用旧实现（比如「去掉 take(8)」），不剥掉会制造假红/假绿。
     * 行注释与块注释都要剥——KDoc 里同样会写旧写法。
     */
    private fun codeOnly(src: String): String =
        src.replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
            .lineSequence().joinToString("\n") { line ->
                val i = line.indexOf("//")
                if (i >= 0) line.substring(0, i) else line
            }

    /** 压平空白：结构断言的锚点不该被缩进/换行方式绑死。 */
    private fun squash(src: String): String = src.replace(Regex("\\s+"), " ")

    @Test
    fun `宽屏形态只由 PlanWideLayout 决定 不再散落 if(wideControls)`() {
        val plan = codeOnly(readRepoFile(planScreenPath))
        assertTrue(
            "计划页没有走纯函数决策机 PlanWideLayout.paneOf——宽度判断散在页面里，改断点必漏",
            plan.contains("PlanWideLayout.paneOf(")
        )
        assertFalse(
            "计划页又用 if (wideControls) 决定要不要右栏——这正是 600dp 平板列表只剩 220dp 的老开关",
            plan.contains("if (wideControls) {")
        )
    }

    @Test
    fun `两栏之间只留白缝 不再画那根切开页面的 1dp 竖线`() {
        val plan = codeOnly(readRepoFile(planScreenPath))
        assertFalse(
            "计划页又出现了 VerticalDivider——用户 2026-11-19 截图里那根离内容很远、只起切割作用的线",
            plan.contains("VerticalDivider(")
        )
        assertTrue(
            "计划页没有走共享的两板骨架 SplitBoards（v64 起材质只有一份）",
            plan.contains("SplitBoards(")
        )
    }

    @Test
    fun `两板同网格 顶到底都有 inset`() {
        val plan = codeOnly(readRepoFile(planScreenPath))
        assertTrue(
            "右栏宽度没有取 PlanWideLayout.DetailWidth（全 app 侧栏同宽的唯一来源）",
            plan.contains("PlanWideLayout.DetailWidth")
        )
        // 白缝 / 同圆角 / 上下 inset 都在共享层里了，钉在 SplitBoard.kt 上。
        val board = squash(codeOnly(readRepoFile(splitBoardPath)))
        assertTrue(
            "共享两板骨架没有状态栏 inset——右栏第一张卡会顶进状态栏（2026-11-19 截图的原始症状）",
            board.contains("statusBarsPadding()")
        )
        assertTrue(
            "共享两板骨架没有底部导航条避让（navAvoid 应受 applyBottomInset 开关控制）",
            board.contains("navAvoid") && board.contains("applyBottomInset")
        )
        assertTrue(
            "两板没有用同一个圆角常量 BoardLayout.Shape",
            board.contains("RoundedCornerShape(BoardLayout.Shape)")
        )
        assertTrue(
            "两栏之间没有留白缝（应 Spacer(Modifier.width(BoardLayout.Gap))）",
            board.contains("Spacer(Modifier.width(BoardLayout.Gap))")
        )
    }

    @Test
    fun `标题栏只在单列分支出现 两栏时由板头承担`() {
        val plan = squash(codeOnly(readRepoFile(planScreenPath)))
        assertTrue(
            "两栏分支没有把 M3ETitleBar 收起来——那层 28dp 圆角 + 3dp 阴影的浮起圆角栏就是第二种材质",
            plan.contains(
                "if (!twoPane) { io.github.m0use001.planability.ui.common.M3ETitleBar("
            )
        )
        assertTrue(
            "宽屏板头没接上共享的 BoardHeader（v64 起标题长在板面上）",
            plan.contains("BoardHeader(title = \"计划\"") &&
                plan.contains("planOverflowMenu()")
        )
    }

    @Test
    fun `宽屏右栏承载内联编辑器 不再弹全屏窗口`() {
        val plan = squash(codeOnly(readRepoFile(planScreenPath)))
        assertTrue(
            "宽屏右栏没有用 TodoEditScreen(embedded = true) 就地编辑待办",
            plan.contains("io.github.m0use001.planability.ui.edit.TodoEditScreen(") &&
                plan.contains("embedded = true")
        )
        assertTrue(
            "宽屏右栏没有用 ScheduleEditScreen(embedded = true) 就地编辑日程",
            plan.contains("io.github.m0use001.planability.ui.edit.ScheduleEditScreen(")
        )
        assertTrue(
            // v70d（2026-11-21 用户拍板）：右栏从 SplitBoards 换成 BoardDrill——钻入目标
            // 必须是稳定 key（segment+id）。BoardDrill 内部按 key Crossfade：换一条=
            // 换 key=换内容，编辑器内部的 remember 草稿不会串条（等价于旧的 key(selectedId)）。
            "右栏编辑器没有按选中项给稳定钻入 key（PlanDrillKey）——编辑器内部是 remember 状态，换条不换 key 会读到上一条的草稿",
            plan.contains("detailKey = drillKey") && plan.contains("PlanDrillKey(")
        )
        assertTrue(
            // v70d：返回收纸条由 BoardDrill 自带的 PredictiveBackHandler 接管
            // （页级 BackHandler 会跟它抢返回，已按拍板撤掉）。
            "计划页没有接 BoardDrill（纸条钻入）——v70d 起宽屏进二级必须整条推挤，返回由 BoardDrill 的预测返回接管",
            plan.contains("BoardDrill(") && plan.contains("onBack = {")
        )
    }

    @Test
    fun `宽屏行是分组列表 行间细线而不是一堆各自浮着的圆角卡`() {
        val plan = squash(codeOnly(readRepoFile(planScreenPath)))
        assertTrue("宽屏行没有扁平行开关 flat = twoPane", plan.contains("flat = twoPane"))
        assertTrue("宽屏行没有选中高亮 selected = twoPane &&", plan.contains("selected = twoPane &&"))
        assertTrue("宽屏行间没有 BoardRowDivider()", plan.contains("BoardRowDivider()"))
        assertTrue(
            "BoardRowDivider 没有用 HorizontalDivider + 缩进实现",
            squash(codeOnly(readRepoFile(splitBoardPath)))
                .contains("HorizontalDivider( modifier = Modifier.padding(horizontal = BoardLayout.RowDividerInset)")
        )
    }

    @Test
    fun `所有编辑器入口都走 openXxxEditor 宽屏不会漏一处弹全屏窗口`() {
        val plan = squash(codeOnly(readRepoFile(planScreenPath)))
        // 行点击 / 长按菜单 / FAB / 概览面板 / Router 深链 / 范围询问，全部必须经过这两个 lambda。
        assertTrue("没有 openTodoEditor 集中入口", plan.contains("val openTodoEditor: (Long) -> Unit"))
        assertTrue("没有 openScheduleEditor 集中入口", plan.contains("val openScheduleEditor: (Long) -> Unit"))
        // 兜底：除了两个 lambda 内部，别处不许再直接写 editingTodo = <id>（null = 关闭，不算入口）。
        val hits = Regex("editingTodo = (?!null)[A-Za-z_]").findAll(plan).count() +
            Regex("editingSchedule = (?!null)[A-Za-z_]").findAll(plan).count()
        assertTrue(
            "还有 $hits 处直接写 editingTodo/editingSchedule——宽屏会从那里弹全屏窗口盖掉两栏",
            hits <= 2 // 只允许两个 lambda 内部各一处
        )
    }

    @Test
    fun `今天概览是一张卡内部分节 不是三张独立卡片`() {
        val pane = codeOnly(readRepoFile(planPanePath))
        assertFalse(
            "PlanTodayPane 又用 Card() 画成三张独立卡片——正是「内容块割裂」的那个形状",
            pane.contains("Card(")
        )
        // v72（2026-11-21 用户「应该加入圆角矩形来区分」）：节升级成共享词汇表的
        // 扁平色调块（PaneSectionBlock，无描边无阴影，与板同材质家族）——禁的仍是浮起卡。
        assertTrue("PlanTodayPane 没有用 PaneSectionBlock 装节（裸文本节=用户点名的杂乱）", pane.contains("PaneSectionBlock("))
        assertTrue("节计数没有挂 PaneChip 小胶囊", pane.contains("PaneChip("))
        assertTrue(
            "空段没有占位块，整段消失会让人分不清「真没有」还是「没加载」",
            pane.contains("PaneEmptyBlock(")
        )
        assertFalse(
            "PlanTodayPane 又静默截断到 8 条（第 9 条被藏起来且不说）",
            pane.contains("take(8)")
        )
    }

    @Test
    fun `EditorScaffold 有 embedded 分支 且内联返回不走空实现`() {
        val editors = squash(codeOnly(readRepoFile(editorsPath)))
        assertTrue(
            "EditorScaffold 没有 embedded 参数",
            editors.contains("embedded: Boolean = false")
        )
        assertTrue(
            "两个计划编辑器没有把 embedded 透传给 EditorScaffold",
            editors.contains("embedded = embedded,")
        )
        assertTrue(
            "外壳没有按 embedded 跳过全屏 Dialog",
            editors.contains("if (embedded) shellBody() else PredictiveBackOverlay(onBack = onBack) { shellBody() }")
        )
        // 内联分支（if (embedded) 之后、else 之前）必须用显式 onBack，不许读 LocalOverlayClose：
        // 那个 CompositionLocal 默认是空实现，内联路径上读它 = 返回键哑掉（笔记编辑器踩过）。
        val start = editors.indexOf("val shellBody: @Composable () -> Unit = {")
        val end = editors.indexOf("} else { PredictiveBackOverlay(", start)
        assertTrue("找不到 shellBody 的 embedded 分支", start >= 0 && end > start)
        val embeddedBranch = editors.substring(start, end)
        assertTrue(
            "内联板头没有用显式 back 回调",
            embeddedBranch.contains("IconButton(onClick = back)")
        )
        assertFalse(
            "内联板头读了 LocalOverlayClose——内联路径上它是空实现，返回键会哑掉",
            embeddedBranch.contains("LocalOverlayClose")
        )
    }
}
