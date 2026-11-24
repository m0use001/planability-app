package io.github.m0use001.planability.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * v57：平板适配的**接线**守卫（2026-11-16）。
 *
 * 平板适配的地基是三件事，缺一件整套就塌：
 * ① manifest 不再对 MainActivity 锁竖屏（锁是**全设备生效**的，没法按屏幕尺寸分叉——
 *    手机要保竖屏、平板要自由旋转，只能把锁挪进代码）；
 * ② MainActivity 在运行时给手机（smallestScreenWidthDp < 600）补回竖屏锁——
 *    没有它，手机会跟着转，竖屏单列布局会被打乱；
 * ③ MainActivity 声明 configChanges，旋转时**不重建 Activity**（重建会丢壳层状态、
 *    闪一下白屏，还会把已开的子页状态冲掉）。
 *
 * 路径安全：本文件**不接受任何路径参数**——仓库根靠固定目录名向上找，
 * 源码文件用白名单常量定位，不做相对路径拼接。
 */
class TabletAdaptationGuardTest {

    private val appDir = "app/src/main"

    /** 仓库根：从当前工作目录向上找带 [appDir] 的那一层。 */
    private val repoRoot: File by lazy {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null && !File(dir, appDir).isDirectory) {
            dir = dir.parentFile
        }
        requireNotNull(dir) { "找不到仓库根" }.canonicalFile
    }

    /** 读仓库内某个**白名单**文件（名字必须是本文件里列出的常量）。 */
    private fun readRepoFile(relative: String): String {
        require(relative in ALLOWED) { "不在白名单里的文件：$relative" }
        val root = repoRoot.canonicalFile
        val f = File(root, relative).canonicalFile
        assertTrue("路径越界：$relative", f.path.startsWith(root.path + File.separator))
        assertTrue("文件不在了：$relative", f.isFile)
        // 结构断言里有跨行字面量（如 "widthIn(...)\n            .fillMaxWidth()"）。
        // 仓库里存的是 LF，但 Windows 上 core.autocrlf 检出/回滚后工作树会是 CRLF，
        // 跨行断言就会假红。先归一化成 LF 再匹配——**别靠"别让文件变 CRLF"来维持**。
        return f.readText(Charsets.UTF_8).replace("\r\n", "\n")
    }

    private fun manifest(): String = readRepoFile("$appDir/AndroidManifest.xml")

    private fun mainActivitySource(): String =
        readRepoFile("$appDir/java/io/github/m0use001/planability/MainActivity.kt")

    /** 取出 manifest 里 MainActivity 那个 <activity ...> 开始标签。 */
    private fun mainActivityTag(): String {
        val m = manifest()
        val at = m.indexOf("android:name=\".MainActivity\"")
        assertTrue("manifest 里找不到 MainActivity", at > 0)
        val start = m.lastIndexOf("<activity", at)
        val end = m.indexOf(">", at)
        assertTrue("MainActivity 的 <activity> 标签没有闭合", start >= 0 && end > start)
        return m.substring(start, end + 1)
    }

    @Test
    fun `MainActivity 不再在 manifest 锁竖屏`() {
        val tag = mainActivityTag()
        assertFalse(
            "MainActivity 仍有 screenOrientation=portrait——平板会被强制竖屏，平板适配做不了",
            tag.contains("screenOrientation")
        )
    }

    @Test
    fun `MainActivity 声明 resizeableActivity`() {
        val tag = mainActivityTag()
        assertTrue(
            "MainActivity 缺 resizeableActivity=true（多窗口/大屏下窗口尺寸可能不跟随）",
            tag.contains("android:resizeableActivity=\"true\"")
        )
    }

    @Test
    fun `MainActivity 声明 configChanges 且旋转不重建`() {
        val tag = mainActivityTag()
        assertTrue("MainActivity 缺 configChanges", tag.contains("android:configChanges="))
        val cfg = Regex("android:configChanges=\"([^\"]*)\"").find(tag)?.groupValues?.get(1).orEmpty()
        assertTrue("configChanges 缺 orientation", cfg.contains("orientation"))
        assertTrue("configChanges 缺 screenSize", cfg.contains("screenSize"))
        assertFalse(
            "configChanges 不该含 uiMode——深色切换要靠 Activity 重建重算主题与系统栏",
            cfg.contains("uiMode")
        )
    }

    @Test
    fun `MainActivity 运行时给手机补回竖屏锁`() {
        val src = mainActivitySource()
        assertTrue(
            "MainActivity 没按 smallestScreenWidthDp 分叉——手机会跟着旋转，竖屏单列布局会乱",
            src.contains("smallestScreenWidthDp < PHONE_MAX_SHORT_SIDE_DP")
        )
        assertTrue(
            "MainActivity 没在运行时锁回竖屏",
            src.contains("SCREEN_ORIENTATION_PORTRAIT")
        )
    }

    @Test
    fun `尺寸类工具存在且断点常量在位`() {
        val src = readRepoFile(
            "$appDir/java/io/github/m0use001/planability/core/WindowSize.kt"
        )
        assertTrue("WindowSize.kt 不见了", src.contains("enum class WindowSizeClass"))
        assertTrue("缺 600dp 断点", src.contains("MEDIUM_WIDTH_BREAKPOINT_DP = 600"))
        assertTrue("缺 840dp 断点", src.contains("EXPANDED_WIDTH_BREAKPOINT_DP = 840"))
    }

    @Test
    fun `adaptiveColumn 的 widthIn 必须在 fillMaxWidth 之前`() {
        val src = readRepoFile(
            "$appDir/java/io/github/m0use001/planability/ui/common/Adaptive.kt"
        )
        assertTrue(
            "adaptiveColumn 次序错误——fillMaxWidth() 在前会把 minWidth 钉死成父宽，" +
                "后面的 widthIn(max) 压不住（v58 平板实测列宽纹丝不动）",
            src.contains("widthIn(max = maxWidth).fillMaxWidth()")
        )
    }

    @Test
    fun `二级页 overlay 宽屏收成居中模态卡且双栏宿主可退出`() {
        val overlay = readRepoFile(
            "$appDir/java/io/github/m0use001/planability/app/PredictiveBackOverlay.kt"
        )
        assertTrue(
            "PredictiveBackOverlay 缺 fullscreen 参数——宽屏二级页会整宽拉稀",
            overlay.contains("fullscreen: Boolean = false")
        )
        assertTrue(
            "PredictiveBackOverlay 缺居中限宽（widthIn max 1000dp）",
            overlay.contains("widthIn(max = 1000.dp)")
        )
        val info = readRepoFile(
            "$appDir/java/io/github/m0use001/planability/ui/info/InfoCenterScreen.kt"
        )
        // 2026-11-19 平板批：会话页的双栏**已从 overlay 改成页内两栏**（NavRail 常驻），
        // 所以这里反过来钉：InfoCenterScreen 不许再出现 fullscreen overlay——
        // 有一块全屏 Dialog 就说明整窗又被盖住了（用户报的「内容块割裂」）。
        assertFalse(
            "会话页又退回 fullscreen overlay 了——它会整块盖住 NavRail 与 App 外壳",
            info.contains("fullscreen = true")
        )
    }

    @Test
    fun `DataHub 宽屏走侧栏分栏`() {
        val hub = readRepoFile(
            "$appDir/java/io/github/m0use001/planability/ui/info/DataHub.kt"
        )
        assertTrue(
            "DataHub 缺宽屏侧栏分支（三段切换是手机口径）",
            hub.contains("DataHubSidebar")
        )
        // 2026-11-20 v65 两板推广：分栏壳换 SplitBoards（那根 VerticalDivider 是全仓最后一根）
        assertTrue(
            "DataHub 宽屏没走 SplitBoards（还是手写 Row + 竖线切割的旧材质）",
            hub.contains("SplitBoards(")
        )
        assertFalse(
            "DataHub 的 VerticalDivider 又回来了（栏间只留 12dp 白缝）",
            hub.contains("VerticalDivider(")
        )
    }

    // ==================== v59「别空」批（2026-11-17） ====================
    // 前两批只解决了「别破版」：限宽居中之后 16:10 平板的右半屏仍然是空的
    //（用户截图点名「仍有许多像图中这种大而空的界面」）。这批的判据是
    // **宽屏的空必须被第二栏真内容填掉**，下面五条钉住对应接线。

    @Test
    fun `首页宽屏走双栏且右侧栏有真内容`() {
        val home = readRepoFile(
            "$appDir/java/io/github/m0use001/planability/ui/home/HomeScreen.kt"
        )
        assertTrue(
            "首页没有宽屏分栏（时间轴会继续铺满整宽，右侧全空）",
            home.contains("WideSplitPane")
        )
        assertTrue(
            "首页宽屏侧栏不见了——那是「别空」的第二栏",
            home.contains("HomeTodayPane")
        )
        val cards = readRepoFile(
            "$appDir/java/io/github/m0use001/planability/ui/home/HomeCards.kt"
        )
        assertTrue(
            "HomeTodayPane 不见了或挪了位置",
            cards.contains("fun HomeTodayPane(")
        )
    }

    @Test
    fun `单日时间轴整块限宽 事件块不再拉成横条`() {
        val grid = readRepoFile(
            "$appDir/java/io/github/m0use001/planability/ui/home/TimelineGrid.kt"
        )
        assertTrue(
            "TimelineGrid 没有宽度上限——事件块会被拉成上千 dp 的横条（用户截图根因）",
            grid.contains("WideTimelineMaxWidth")
        )
        // 次序：widthIn 必须在 fillMaxWidth 之前（v58 实测：反过来 minWidth 被钉死，压不住）
        assertTrue(
            "TimelineGrid 的 widthIn / fillMaxWidth 次序反了（v58 同款坑）",
            grid.contains("widthIn(max = io.github.m0use001.planability.ui.common.WideTimelineMaxWidth)\n            .fillMaxWidth()")
        )
    }

    @Test
    fun `专注页外层 Box 居中 且宽屏两栏`() {
        val focus = readRepoFile(
            "$appDir/java/io/github/m0use001/planability/ui/focus/FocusScreen.kt"
        )
        assertTrue(
            "FocusScreen 外层 Box 漏了 contentAlignment = Alignment.TopCenter —— " +
                "adaptiveColumn 限宽后整列会钉在屏幕左侧（圆环偏左、右边全空）",
            focus.contains("Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter)")
        )
        assertTrue(
            "专注页没有宽屏两栏（环 + 今日专注统计并排）",
            focus.contains("focusRing") && focus.contains("TodayFocusSummary(goalMin)")
        )
    }

    @Test
    fun `日历月视图等宽 7 列且年视图用自适应列`() {
        val cal = readRepoFile(
            "$appDir/java/io/github/m0use001/planability/ui/home/CalendarView.kt"
        )
        assertTrue(
            "月视图没有限宽（SpaceEvenly + 42dp 小圆在 1248dp 下会散成一把点）",
            cal.contains("WideCalendarMaxWidth")
        )
        assertTrue(
            "年视图还在用 GridCells.Fixed —— 宽屏下每月格会被撑成超大单格",
            cal.contains("GridCells.Adaptive(")
        )
        assertFalse(
            "年视图仍有 GridCells.Fixed(4)",
            cal.contains("GridCells.Fixed(4)")
        )
    }

    @Test
    fun `笔记本网格的表头不再写死跨 2 列`() {
        val notes = readRepoFile(
            "$appDir/java/io/github/m0use001/planability/ui/notes/NotesScreen.kt"
        )
        assertFalse(
            "NotesScreen 仍有 GridItemSpan(2) 写死——宽屏 7 列时筛选行只占 2 列、右侧空",
            notes.contains("GridItemSpan(2)")
        )
    }

    // ==================== v59「时间轴别空」批 ====================

    @Test
    fun `时间轴不分档 宽窄同一个手机口径`() {
        val grid = readRepoFile(
            "$appDir/java/io/github/m0use001/planability/ui/home/TimelineGrid.kt"
        )
        assertTrue(
            "TimelineGrid 没用 TimelineLayout——时间轴的纵向映射必须只有一份",
            grid.contains("TimelineLayout.build")
        )
        // 2026-11-19 用户：「无论什么比例就都搞成手机模式下原本的时间轴」。
        // v59 压白天、v60 压夜里，两版都被判「越改问题越大」；以后不许再按窗口宽度分档。
        assertTrue(
            "时间轴没有 24 小时恒 64dp 的口径（宽屏必须与手机完全一致）",
            grid.contains("contentHours = (0..23).toSet()")
        )
        assertFalse(
            "时间轴又按窗口宽度分档了（isWide / DAY_HOURS）——宽屏自制时间轴已被用户否决两次",
            grid.contains("isWide") || grid.contains("DAY_HOURS")
        )
        val layoutFile = readRepoFile(
            "$appDir/java/io/github/m0use001/planability/ui/home/TimelineLayout.kt"
        )
        assertTrue(
            "TimelineLayout.kt 不见了或改了常量——空闲小时高度必须钉住",
            layoutFile.contains("IDLE_HOUR_HEIGHT_DP = 22")
        )
    }

    @Test
    fun `时间轴网格线必须先 dp 转像素再画`() {
        val grid = readRepoFile(
            "$appDir/java/io/github/m0use001/planability/ui/home/TimelineGrid.kt"
        )
        // 2026-11-19 用户真机反馈「时间轴整点横线全挤上面了」。
        // 根因 = 单位混用：`hourTopDp()` 返回 **dp**，而 DrawScope 的 `Offset`/`size` 是 **像素**。
        // 直接把 dp 当 px 传给 drawLine，24 条线就被压在画布顶部 1/density 处
        // （密度 3.5x 的手机上只剩顶部 ~29%），而小时数字与事件块走
        // `Modifier.offset(dp)` 一直是对的 —— 只有这张 Canvas 错位。
        // 这条断言钉两件事：①必须 `.dp.toPx()`；②不许再出现裸 dp 直接画。
        assertTrue(
            "时间轴网格线的 y 没有 .dp.toPx() —— hourTopDp 是 dp、Offset 吃像素，" +
                "直接混用会让整点横线全挤到画布顶部（真机 3.5x 只剩顶部三成有）",
            grid.contains("timelineLayout.hourTopDp(i).dp.toPx()")
        )
        assertFalse(
            "又出现裸 dp 当像素用（drawLine 的 Offset 必须走 toPx）",
            grid.contains("val y = timelineLayout.hourTopDp(i)\n")
        )
    }

    @Test
    fun `首页红线定位用时间轴回传的同一份映射`() {
        val home = readRepoFile(
            "$appDir/java/io/github/m0use001/planability/ui/home/HomeScreen.kt"
        )
        assertTrue(
            "首页没有接收时间轴的布局回传——红线定位会和画出来的位置错位",
            home.contains("onLayout = { timelineLayout = it }")
        )
        assertTrue(
            "首页红线定位没走 minuteToDp（还在用旧的 hour×64dp 公式）",
            home.contains("timelineLayout?.minuteToDp(")
        )
    }

    @Test
    fun `首页打开要自动滚到当前时刻 不许停在零点`() {
        val home = readRepoFile(
            "$appDir/java/io/github/m0use001/planability/ui/home/HomeScreen.kt"
        )
        // 2026-11-19 用户「要自动滚到当前时刻」。原来经常停在 00:00 的根因：
        // 首帧 scrollState.viewportSize 还是 0，那一次 scrollTo 的目标被 maxValue(0) 钳成 0，
        // 而 homeAutoScrolled 已经被置 true —— 之后再也不会重试。
        assertTrue(
            "自动定位没有 key 住 viewportSize（视口后到也不会重试，页面就停在 00:00）",
            home.contains("LaunchedEffect(selectedDate, timelineLayout, scrollState.viewportSize)")
        )
        assertTrue(
            "自动定位没有等视口量出来就先结算了（目标会被钳到 0）",
            home.contains("if (scrollState.viewportSize == 0) return@LaunchedEffect")
        )
        assertTrue(
            "自动定位还是整点近似（now.hour - 1）× 60——应与红线用同一份分钟级映射",
            home.contains("tl.minuteToDp((nowMin - 60).coerceAtLeast(0))")
        )
    }

    @Test
    fun `聊天页顶栏下沿不许再出现那条没意义的渐变带`() {
        val chat = readRepoFile(
            "$appDir/java/io/github/m0use001/planability/ui/info/ChatScreen.kt"
        )
        // v59 踩过的坑：给聊天列表上缘加「surfaceContainer → Transparent」渐隐，
        // 想着让滑上去的消息淡出——但那条浅色带与聊天页底色不是一个色，
        // 用户在真机上直接判「标题栏下面有个意义不明的渐变特效」（phone-b 已在 692be312 撤掉）。
        // 这条断言钉住它别被加回来。
        assertFalse(
            "聊天页又出现了顶栏下沿那条没意义的渐变带（用户 2026-11-17 判定无意义并撤除）",
            chat.contains("surfaceContainer, Color.Transparent")
        )
    }

    @Test
    fun `计划页控制行在宽屏收窄 不跟着 840dp 列拉满`() {
        val plan = readRepoFile(
            "$appDir/java/io/github/m0use001/planability/ui/plan/PlanScreen.kt"
        )
        assertTrue(
            "计划页控制行没有宽度治理——分段按钮每段 420dp，像被充气（用户 16:10 截图）",
            plan.contains("wideControls")
        )
        assertTrue(
            "计划页没有按宽屏收窄分段控件",
            plan.contains("widthIn(max = 260.dp)")
        )
    }

    @Test
    fun `小平板不再被当成手机锁竖屏 阈值降到 480`() {
        val main = mainActivitySource()
        // v60 用户反馈：「在一些小平板上又会被识别成手机然后强制竖屏」。
        // 根因：判定阈值写了 600dp，而 7~8 寸小平板的 smallestScreenWidthDp
        // 常见是 533~599dp → 被判成手机 → 强制竖屏。阈值降到 480：
        // 真机手机短边几乎都 < 480dp（旗舰 360~430），小平板不再被误判。
        assertTrue(
            "MainActivity 里找不到平板/手机判定阈值常量",
            main.contains("PHONE_MAX_SHORT_SIDE_DP")
        )
        assertTrue(
            "竖屏锁阈值还是 600（小平板 sw=533~599 会被误判成手机强制竖屏）",
            main.contains("PHONE_MAX_SHORT_SIDE_DP = 480")
        )
        assertTrue(
            "竖屏锁判定没有走常量阈值",
            main.contains("smallestScreenWidthDp < PHONE_MAX_SHORT_SIDE_DP")
        )
    }

    @Test
    fun `Rail 退场立即释放宽度 页面抽屉从屏幕左缘出来`() {
        val shell = readRepoFile("$appDir/java/io/github/m0use001/planability/app/NavShell.kt")
        // v60 用户反馈：「工作台右滑菜单的位置不对，应该是从屏幕左边出来」。
        // 根因：Rail 的 AnimatedVisibility 退场带动画，动画期间它的 80dp 宽度继续占位，
        // 页面的 ModalNavigationDrawer 只能从 x=80dp 起步。退场改成 None 即刻释放。
        assertTrue(
            "NavShell 里 Rail 的退场动画还占着宽度（抽屉会从 Rail 右缘而不是屏幕左缘出来）",
            shell.contains("ExitTransition.None")
        )
    }

    @Test
    fun `专注页宽屏右栏有内容 左栏垂直居中`() {
        val focus = readRepoFile("$appDir/java/io/github/m0use001/planability/ui/focus/FocusScreen.kt")
        assertTrue(
            "专注页宽屏右栏少了「近 7 天」",
            focus.contains("FocusWeekBars()")
        )
        // v70（2026-11-21 用户拍板）：「最近专注」整节撤出概览板（概览板不滚动、一屏收纳），
        // 反转为「不得回归」断言。
        assertFalse(
            "专注页概览板又出现了「最近专注」（已按用户拍板撤出）",
            focus.contains("FocusRecentSessions")
        )
        assertTrue(
            "专注页宽屏右栏少了「本周概览」",
            focus.contains("FocusWeekStats()")
        )
        assertTrue(
            "专注页左栏没有垂直居中（内容全堆在上半屏，下面大片空）",
            focus.contains("verticalArrangement = Arrangement.Center")
        )
        // v60 踩过的坑：外层滚动容器会把子节点按无限高测量，
        // 内层再套 verticalScroll 直接崩（IllegalStateException）。
        assertTrue(
            "专注页宽屏没有把外层滚动换成有界高度（内层再滚动会崩）",
            focus.contains("widePaneHeight")
        )
    }

    @Test
    fun `首页侧栏不许再套一层滚动`() {
        val home = readRepoFile("$appDir/java/io/github/m0use001/planability/ui/home/HomeScreen.kt")
        val cards = readRepoFile("$appDir/java/io/github/m0use001/planability/ui/home/HomeCards.kt")
        // HomeTodayPane 内部已经是 verticalScroll；外面再套一层 = 嵌套滚动 = 无限高约束，
        // 2026-11-18 在真机虚拟屏上首帧必崩
        // （IllegalStateException: Vertically scrollable component was measured with an infinity maximum height）。
        assertTrue(
            "HomeTodayPane 自己没滚动了（矮窗口里侧栏会被裁掉）",
            cards.contains("fun HomeTodayPane(") && cards.substringAfter("fun HomeTodayPane(").take(2000).contains("verticalScroll")
        )
        val call = home.substringAfter("HomeTodayPane(").take(500)
        assertFalse(
            "HomeScreen 给 HomeTodayPane 外面又套了一层 verticalScroll——嵌套滚动会让首页一启动就崩",
            call.contains("verticalScroll")
        )
    }

    @Test
    fun `计划页与笔记页宽屏都有常驻右栏`() {
        val plan = readRepoFile("$appDir/java/io/github/m0use001/planability/ui/plan/PlanScreen.kt")
        assertTrue(
            "计划页宽屏没有右侧「今天」栏（列表短的时候下面几百 dp 全空）",
            plan.contains("PlanTodayPane(")
        )
        assertTrue(
            "计划页左列没有收窄（宽屏下还按 840dp 拉满，右栏没位置）",
            plan.contains("adaptiveColumn(maxWidth = 760.dp)")
        )
        val notes = readRepoFile("$appDir/java/io/github/m0use001/planability/ui/notes/NotesScreen.kt")
        assertTrue(
            "笔记页宽屏没有「最近笔记」右栏",
            notes.contains("NotesRecentPane(")
        )
        // 2026-11-20 v65 两板推广：右栏从手搓 align(TopEnd) 浮层盒升格成 SplitBoards 右板
        assertTrue(
            "笔记页顶层网格没有走共享两板骨架 SplitBoards（右栏还是手搓浮层盒）",
            notes.contains("SplitBoards(")
        )
        assertTrue(
            "笔记页宽屏板头没有走 BoardHeader（浮层标题栏还压在网格上层）",
            notes.contains("BoardHeader(")
        )
    }

    @Test
    fun `工作台首页宽屏补今日安排与最近会话`() {
        val info = readRepoFile("$appDir/java/io/github/m0use001/planability/ui/info/InfoCenterScreen.kt")
        assertTrue(
            "工作台首页宽屏没有「今日安排」面板",
            info.contains("InfoTodayPlanPanel(")
        )
        assertTrue(
            "工作台首页宽屏没有「最近会话」面板",
            info.contains("InfoRecentSessionsPanel(")
        )
    }

    // ==================== v63「3200×2000 全量批」（2026-11-19） ====================
    // 目标屏 3200×2000 @2.0 = **1600×1000dp**（内容区 = 1600 − Rail 80 = 1520dp）。
    // 那一屏上最重的两处「大而空」是信息页与我的页：都还是 `adaptiveColumn()` 840dp
    // 单列居中 —— 1520dp 里左右各空 340dp。这两条钉住它们各自的分栏接线。

    @Test
    fun `信息页宽屏两栏 输入卡与侧栏不再挤一条单列`() {
        val info = readRepoFile(
            "$appDir/java/io/github/m0use001/planability/ui/info/InfoCenterScreen.kt"
        )
        assertTrue(
            "信息页主列没有抽成 lambda —— 宽窄两分支会各留一份内容，下次改必漏一份",
            info.contains("val leftContent: @Composable () -> Unit = {")
        )
        assertTrue(
            "信息页没有宽屏右栏（今日安排 / 最近会话还在压同一条单列，横着更空竖着更长）",
            info.contains("val rightContent: @Composable () -> Unit = {")
        )
        // 两块面板必须长在 rightContent 里，而不是原来的 `if (isWide)` 内联块
        val right = info.substringAfter("val rightContent: @Composable () -> Unit = {")
        assertTrue("rightContent 里少了「今日安排」", right.contains("InfoTodayPlanPanel("))
        assertTrue("rightContent 里少了「最近会话」", right.contains("InfoRecentSessionsPanel("))
        assertTrue(
            "宽屏侧栏宽度没有接到 ChatWideLayout.SidebarWidth——宽度会散在页面里（改断点必漏）",
            info.contains("ChatWideLayout.SidebarWidth")
        )
        assertTrue(
            "信息页宽屏形态没有走纯函数决策机 ChatWideLayout.paneOf",
            info.contains("ChatWideLayout.paneOf(")
        )
        assertTrue(
            "左主列没有限宽 —— 1139dp 会被拉成一整行输入框（HIG Layout：内容列要有读长上限）",
            info.contains("WideWorkbenchMaxWidth")
        )
        assertTrue(
            "两分支没有共用 leftContent（复制两份 = 下次改漏一份）",
            info.contains("leftContent()") && info.contains("rightContent()")
        )
        // 2026-11-19 实测抓到：宽屏左列漏了横向 padding，窗口 840~1421dp 之间
        // WideWorkbenchMaxWidth(960) 压不住、列被铺满，问候语直接贴到左侧 Rail 上
        //（1280dp 截图：起点 x≈79.5dp = Rail 右缘）。宽窄两分支各需要一段 20dp。
        val hPad = Regex("""\.padding\(horizontal = 20\.dp\)""").findAll(info).count()
        assertEquals(
            "信息页宽/窄两分支的横向 padding 数量不对（少一段 = 某个分支的正文贴边）",
            2,
            hPad
        )
    }

    @Test
    fun `我的页宽屏多栏 行清单只有一份`() {
        val profile = readRepoFile(
            "$appDir/java/io/github/m0use001/planability/ui/profile/ProfileScreens.kt"
        )
        assertTrue(
            "我的页没有量内容区实测宽（用 screenWidthDp 会把 80dp Rail 多算进去，临界值提前一档）",
            profile.contains("androidx.compose.foundation.layout.BoxWithConstraints(")
        )
        assertTrue(
            "我的页没有走分档纯函数（仍是写死的一列 840dp，1520dp 里左右各空 340dp）",
            profile.contains("profileIsTwoPane(maxWidth.value.toInt())")
        )
        assertTrue(
            "我的页单板限宽没钉在 840（v57 旧口径）",
            profile.contains(".adaptiveColumn(PROFILE_ONE_COLUMN_MAX_WIDTH_DP.dp)")
        )
        // v68「纸条导航」→ v77「全局层级栈」：两板分支升级成钻入宿主（BoardDrill 或 NavHost，同几何）。
        assertTrue(
            "我的页缺宽屏钻入分支（BoardDrill/NavHost：根层与 SplitBoards 同几何、可钻入细分页）",
            profile.contains("BoardDrill(") || profile.contains("NavHost(")
        )
        // 宽窄两种摆位必须共用同一份行清单：抽成**条目列表**之后，任何一条 SettingEntry
        // 只应出现一次 —— 出现两次说明有人复制了第二套内容（下次改必漏）。
        val paletteRows = Regex("""SettingEntry\(Icons\.Rounded\.Palette, "外观"\)""")
            .findAll(profile).count()
        assertEquals(
            "设置行清单被复制了第二份（宽窄两分支必须共用同一份条目列表）",
            1,
            paletteRows
        )
        val memoryRows = Regex("""SettingEntry\(Icons\.Rounded\.SmartToy, "记忆库"\)""")
            .findAll(profile).count()
        assertEquals("记忆库行被复制了第二份", 1, memoryRows)
    }

    @Test
    fun `平板分档纯函数各自成文 不许散落在组合代码里`() {
        // 三份分档（专注 / 我的 / 计划）都必须是可单测的纯函数文件：
        // 散落在 composable 里的 if 链没有测试能钉住，改一个数字没人发现。
        val focus = readRepoFile("$appDir/java/io/github/m0use001/planability/ui/focus/FocusLayout.kt")
        // 2026-11-20 v65：专注页从三档（单/两/三栏）收敛成两档（单列 / 两板），
        // 判定函数随之改名。
        assertTrue("FocusLayout.kt 不见了", focus.contains("fun focusIsTwoPane("))
        val profile = readRepoFile(
            "$appDir/java/io/github/m0use001/planability/ui/profile/ProfileWideLayout.kt"
        )
        // 2026-11-20 v65：我的页从三档（单/两/三栏）收敛成两档（单列 / 两板），
        // 判定函数随之改名。
        assertTrue("ProfileWideLayout.kt 不见了", profile.contains("fun profileIsTwoPane("))
        assertTrue(
            "我的页组宽必须钉在 380 —— 与首页右栏同一尺度，改了就是第二套心智",
            profile.contains("const val PROFILE_GROUP_WIDTH_DP = 380")
        )
        val plan = readRepoFile("$appDir/java/io/github/m0use001/planability/ui/plan/PlanWideLayout.kt")
        assertTrue("PlanWideLayout.kt 不见了", plan.contains("fun paneOf("))
        // 2026-11-19 平板批：会话页（信息页 + 聊天）也走纯函数决策机
        val chat = readRepoFile("$appDir/java/io/github/m0use001/planability/ui/info/ChatWideLayout.kt")
        assertTrue("ChatWideLayout.kt 不见了", chat.contains("fun paneOf("))
    }

    @Test
    fun `宽度上限 token 集中在 AdaptivePane 一处`() {
        // 侧栏宽度 / 时间轴限宽 / 工作台主列限宽必须都来自同一个文件，
        // 否则三个页面各写各的 380/680/960，平板网格就散了。
        val pane = readRepoFile("$appDir/java/io/github/m0use001/planability/ui/common/AdaptivePane.kt")
        assertTrue("宽屏侧栏宽度 token 不见了", pane.contains("val WideSecondaryWidth: Dp = 380.dp"))
        assertTrue("时间轴限宽 token 不见了", pane.contains("val WideTimelineMaxWidth: Dp = 680.dp"))
        assertTrue(
            "工作台主列限宽 token 不见了（输入卡会被拉成 1139dp 整行）",
            pane.contains("val WideWorkbenchMaxWidth: Dp = 960.dp")
        )
    }

    @Test
    fun `大标题栏折叠时副标题要跟着缩 不许被拦腰裁掉`() {
        val bar = readRepoFile("$appDir/java/io/github/m0use001/planability/ui/common/M3ETitleBar.kt")
        // 2026-11-19 用户「授权全改」第 1 件：首页副标题「2026年11月19日」在折叠途中
        // 只剩上半截字。根因 = 盒子 `height(18dp × (1f-f)) + clipToBounds()`，
        // 而文字保持自然行高 ~15dp —— f 只要大于 0 字就比盒子高，f=0.5 时盒子只剩 9dp，
        // 从字腰切过去。首页自动滚到当前时刻后，f 在滚动途中与停在中段时都会长期存在。
        // 修法：缩放系数与盒子高度同为 (1-f)，任意 f 下 15×(1-f) ≤ 18×(1f-f)。
        assertTrue(
            "大标题栏没有把副标题/元信息的缩放系数与盒子高度绑在一起",
            bar.contains("val subScale = if (fixedSubtitle) 1f else 1f - f")
        )
        assertTrue(
            "副标题没接缩放（只淡出不缩放 → clipToBounds 仍会把字切成两半）",
            bar.contains("scaleX = subScale") && bar.contains("scaleY = subScale")
        )
        assertTrue(
            "固定态必须恒为 1（expandable=false 时 f 恒 1，照 (1-f) 缩会让会话页副标题直接消失）",
            bar.contains("if (fixedSubtitle) 1f else 1f - f")
        )
        // 元信息那行（首页「日程 · 待办」）走的是同一条路径，必须一起修
        assertTrue("展开态元信息漏了缩放（同款拦腰裁切）", bar.contains("expandedMeta()"))
    }

    private companion object {
        /** 允许读取的仓库文件白名单（相对仓库根；appDir 是实例属性，companion 里用字面量）。 */
        val ALLOWED = setOf(
            "app/src/main/AndroidManifest.xml",
            "app/src/main/java/io/github/m0use001/planability/MainActivity.kt",
            "app/src/main/java/io/github/m0use001/planability/core/WindowSize.kt",
            "app/src/main/java/io/github/m0use001/planability/ui/common/Adaptive.kt",
            "app/src/main/java/io/github/m0use001/planability/app/PredictiveBackOverlay.kt",
            "app/src/main/java/io/github/m0use001/planability/app/NavShell.kt",
            "app/src/main/java/io/github/m0use001/planability/ui/info/InfoCenterScreen.kt",
            "app/src/main/java/io/github/m0use001/planability/ui/info/DataHub.kt",
            // v59 批
            "app/src/main/java/io/github/m0use001/planability/ui/home/HomeScreen.kt",
            "app/src/main/java/io/github/m0use001/planability/ui/home/HomeCards.kt",
            "app/src/main/java/io/github/m0use001/planability/ui/home/TimelineGrid.kt",
            "app/src/main/java/io/github/m0use001/planability/ui/home/TimelineLayout.kt",
            "app/src/main/java/io/github/m0use001/planability/ui/home/CalendarView.kt",
            "app/src/main/java/io/github/m0use001/planability/ui/focus/FocusScreen.kt",
            "app/src/main/java/io/github/m0use001/planability/ui/notes/NotesScreen.kt",
            "app/src/main/java/io/github/m0use001/planability/ui/info/ChatScreen.kt",
            "app/src/main/java/io/github/m0use001/planability/ui/plan/PlanScreen.kt",
            // v63 批（2026-11-19 3200×2000 全量）
            "app/src/main/java/io/github/m0use001/planability/ui/common/AdaptivePane.kt",
            "app/src/main/java/io/github/m0use001/planability/ui/profile/ProfileScreens.kt",
            "app/src/main/java/io/github/m0use001/planability/ui/profile/ProfileWideLayout.kt",
            "app/src/main/java/io/github/m0use001/planability/ui/focus/FocusLayout.kt",
            "app/src/main/java/io/github/m0use001/planability/ui/plan/PlanWideLayout.kt",
            // v63 第二批（用户「授权全改」）
            "app/src/main/java/io/github/m0use001/planability/ui/common/M3ETitleBar.kt",
            // v64 批（2026-11-19 平板会话页：页内两栏 + 单一内容列）
            "app/src/main/java/io/github/m0use001/planability/ui/info/ChatWideLayout.kt",
        )
    }
}
