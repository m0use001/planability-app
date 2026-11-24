package io.github.m0use001.planability.ui.common

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 平板「两板推广」全量守卫（2026-11-20，用户「所有界面都要改成计划界面这种
 * 2 个圆角矩形框的排版」批 7）。
 *
 * 六个一级 tab（首页/计划/专注/笔记/信息/我的）+ 日历月视图的**宽屏**形态必须走
 * 共享骨架 [SplitBoards]（两块 24dp 圆角板 + 12dp 白缝），这些文件里不许再出现
 * `VerticalDivider`（竖线切割 = 旧材质）， retired 的 `WideSplitPane` 不许复活。
 *
 * 路径安全：与 TabletAdaptationGuardTest 同款——仓库根靠固定目录向上找，不接受路径参数。
 */
class WideBoardLayoutGuardTest {

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

    /**
     * 六个一级 tab → 文件（都在宽屏分支要求 SplitBoards）。
     * v71（2026-11-21 用户「日历界面不搞主副框，改成右移入场覆盖原有界面」）：
     * 日历从这份清单里**退役**——它不再是两板页，而是覆盖层单列（见下面专属断言）。
     */
    private val firstLevelPages: Map<String, String> = mapOf(
        "首页" to "java/io/github/m0use001/planability/ui/home/HomeScreen.kt",
        "计划" to "java/io/github/m0use001/planability/ui/plan/PlanScreen.kt",
        "专注" to "java/io/github/m0use001/planability/ui/focus/FocusScreen.kt",
        "笔记" to "java/io/github/m0use001/planability/ui/notes/NotesScreen.kt",
        "信息" to "java/io/github/m0use001/planability/ui/info/InfoCenterScreen.kt",
        "我的" to "java/io/github/m0use001/planability/ui/profile/ProfileScreens.kt"
    )

    @Test
    fun `六个一级页宽屏都走共享两板材质`() {
        firstLevelPages.forEach { (name, rel) ->
            val src = read("$appDir/$rel")
            // v68「纸条导航」起，一级页宽屏可用三种**同材质**骨架：
            //  · SplitBoards（两块 24dp 圆角板 + 12dp 白缝，静态两板）
            //  · BoardDrill（SplitBoards 的两级钻入兄弟，计划/我的/专注…）
            //  · BoardDeck（多级钻入兄弟，笔记）
            // 三者同在 ui/common、共用一份 BoardLayout 材质常量——不是第三种材质。
            // v76（「二级+全量纳入钻入」）：不再逐个按名字钉，统一接受这三种之一。
            val material = "SplitBoards/BoardDrill/BoardDeck/NavHost（共享两板材质）"
            assertTrue(
                "$name 宽屏没有走共享两板材质 $material——又长出了别的分栏材质",
                src.contains("SplitBoards(") || src.contains("BoardDrill(") ||
                    src.contains("BoardDeck(") || src.contains("NavHost(")
            )
        }
    }

    @Test
    fun `日历是右移入场覆盖层 不再分主副框`() {
        val cal = read("$appDir/java/io/github/m0use001/planability/ui/home/CalendarView.kt")
        val home = read("$appDir/java/io/github/m0use001/planability/ui/home/HomeScreen.kt")
        assertFalse(
            "日历里还有 SplitBoards——v71 起日历是整页覆盖层，不许再分主副框（框套框）",
            cal.contains("SplitBoards(")
        )
        // v74（2026-11-22 守卫清偿）：v71 卡顿根修后**单一来源**——CalendarView 不再自己
        // 写 setBoardFrames（它和 HomeScreen 常驻组合互相翻转，壳层框显隐每帧抖 = 用户
        // 实锤「每切一次视图卡一下」），改由 HomeScreen 按 showCalendar 统一写
        // （日历开着=false、关着=按形态）。守卫从「CalendarView 写 false」改钉新契约。
        assertTrue(
            "首页没有按 showCalendar 统一收放相框（v71 单一来源：日历开着=false、关着=按形态）",
            home.contains("setBoardFrames(\"home\", !showCalendar)")
        )
        assertFalse(
            "CalendarView 还在自己写 setBoardFrames——v71 起单一来源在 HomeScreen（双写互相翻转=切视图卡顿根修）",
            cal.contains("setBoardFrames(")
        )
        assertTrue(
            "日历覆盖层没走右移入场（HomeScreen 缺 translationX 驱动的日历层）",
            home.contains("calendarSlide")
        )
        assertTrue(
            "年视图没有逐日情况（用户要求「年视图下要展示每一天情况」）",
            cal.contains("YearDayCell(")
        )
    }

    @Test
    fun `一级页与日历不许再有竖线切割`() {
        firstLevelPages.forEach { (name, rel) ->
            val src = read("$appDir/$rel")
            assertFalse(
                "$name 里还有 VerticalDivider——栏间只留 12dp 白缝（竖线只起切割作用，" +
                    "不表达层级，是计划页改造前就淘汰的材质）",
                src.contains("VerticalDivider(")
            )
        }
    }

    @Test
    fun `WideSplitPane 已从全仓退役 不许复活`() {
        // 批 1 后无人使用即删（竖线直角分栏）。全仓扫 app 源码；
        // 只认**调用形态** `WideSplitPane(`——KDoc 里"原来的 WideSplitPane"这类
        // 历史注释不算复活（朴素 contains 会被注释绊倒，本仓踩过）。
        val root = File(repoRoot, "$appDir/java")
        assertTrue("app 源码目录不见了", root.isDirectory)
        val offenders = root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { it.readText().contains("WideSplitPane(") }
            .map { it.relativeTo(repoRoot).path }
            .toList()
        assertTrue("WideSplitPane 又回来了：$offenders", offenders.isEmpty())
    }

    @Test
    fun `主从两栏推广过的页面仍走 SplitBoards 不许退化`() {
        // 文件库 / 记忆库 / 自动化 / 智能体：主从两栏推广（4c210109/9b5219f9）已落，
        // 这里钉住不许有人改回去。
        listOf(
            "文件库" to "java/io/github/m0use001/planability/ui/files/FilesScreen.kt",
            "记忆库" to "java/io/github/m0use001/planability/ui/info/MemoryScreen.kt",
            "自动化" to "java/io/github/m0use001/planability/ui/info/AutomationScreen.kt",
            "智能体" to "java/io/github/m0use001/planability/ui/info/WorkbenchExtraScreens.kt"
        ).forEach { (name, rel) ->
            val src = read("$appDir/$rel")
            assertTrue("$name 没有走 SplitBoards（两板推广退化）", src.contains("SplitBoards("))
        }
    }

    @Test
    fun `VerticalDivider 全仓清零 不许复活`() {
        // 2026-11-20 v65 二级页推广（批 8）后，DataHub 那根是全仓最后一根；
        // 「栏间只留白缝」自此在全 app 成立。只认调用形态，KDoc 历史注释不算。
        val root = File(repoRoot, "$appDir/java")
        assertTrue("app 源码目录不见了", root.isDirectory)
        val offenders = root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .map { it to it.readText() }
            .filter { (_, txt) -> txt.contains("androidx.compose.material3.VerticalDivider()") }
            .map { (f, _) -> f.relativeTo(repoRoot).path }
            .toList()
        assertTrue("VerticalDivider 又回来了：$offenders", offenders.isEmpty())
    }

    @Test
    fun `能力与插件页宽屏走两板`() {
        val src = read("$appDir/java/io/github/m0use001/planability/ui/profile/PluginUsageScreens.kt")
        assertTrue(
            "能力与插件页宽屏没走 SplitBoards（整窗单列 = 大而空）",
            src.contains("SplitBoards(")
        )
        // 板内扁平行（CapabilityRow 的 flat 形态）必须在位——板内不嵌浮起 Card
        assertTrue("CapabilityRow 缺 flat 板内形态", src.contains("flat: Boolean = false"))
    }

    @Test
    fun `板内不许嵌浮起 Card 的写法回流到我的页`() {
        // 铁律：板本身是圆角矩形 + 容器底，板里再套同材质卡 = 两种材质叠在一起。
        // 「我的」页宽屏分节只许走 BoardSectionTitle + 扁平行（BoardEntryList）。
        val profile = read("$appDir/java/io/github/m0use001/planability/ui/profile/ProfileScreens.kt")
        assertTrue("我的页宽屏分节没走 BoardSectionTitle", profile.contains("BoardSectionTitle("))
        assertTrue("我的页右板概览（ProfileOverviewBoard）不见了", profile.contains("ProfileOverviewBoard("))
        // 右板不许空：账号、统计、版本/存储三段必须在
        assertTrue("右板概览缺「数据与统计」分节", profile.contains("数据与统计"))
        assertTrue("右板概览缺版本信息行（InfoRow）", profile.contains("InfoRow("))
    }
}
