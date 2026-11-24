package io.github.m0use001.planability.ui.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 「主从两栏推广」的**接线**守卫（2026-11-20）。
 *
 * 六页里已接五页（文件库/记忆库/自动化/模型与智能体/笔记），每一条都是结构性约定，
 * 编译不会拦——退回去照样编译、照样跑，只是用户又会看到：同一页面两套材质、
 * 模态框盖掉两板、宽屏二级页缩回 1000dp 模态卡、600~839dp 硬分栏把列表压扁。
 * 钉在源码上，谁改回去谁红。
 *
 * 中枢审核页是**刻意不做两板**的例外：它是「列表 + 底部对话式输入」的混排，
 * 硬拆两板会把输入面板切窄——只钉它的「宽屏整屏」。
 *
 * 路径安全沿用 PlanWidePaneGuardTest：仓库根向上找，源码文件白名单。
 */
class MasterDetailGuardTest {

    private val appDir = "app/src/main"
    private val files = mapOf(
        "splitBoard" to "$appDir/java/io/github/m0use001/planability/ui/common/SplitBoard.kt",
        "files" to "$appDir/java/io/github/m0use001/planability/ui/files/FilesScreen.kt",
        "notes" to "$appDir/java/io/github/m0use001/planability/ui/notes/NotesScreen.kt",
        "memory" to "$appDir/java/io/github/m0use001/planability/ui/info/MemoryScreen.kt",
        "automation" to "$appDir/java/io/github/m0use001/planability/ui/info/AutomationScreen.kt",
        "model" to "$appDir/java/io/github/m0use001/planability/ui/profile/ModelWeeklyAchieve.kt",
        "assistants" to "$appDir/java/io/github/m0use001/planability/ui/info/WorkbenchExtraScreens.kt",
        "hub" to "$appDir/java/io/github/m0use001/planability/ui/info/AssistantHubScreen.kt"
    )

    private val repoRoot: File by lazy {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null && !File(dir, appDir).isDirectory) {
            dir = dir.parentFile
        }
        requireNotNull(dir) { "找不到仓库根" }.canonicalFile
    }

    private fun codeOf(key: String): String {
        val rel = requireNotNull(files[key]) { "未知文件键：$key" }
        val root = repoRoot.canonicalFile
        val f = File(root, rel).canonicalFile
        assertTrue("路径越界：$rel", f.path.startsWith(root.path + File.separator))
        assertTrue("文件不在了：$rel", f.isFile)
        return f.readText(Charsets.UTF_8)
            // 坑：`"*/*"`（MIME 通配串）里含 `*/`，会把朴素的块注释剥离器从别的 `/*` 一路吃到这里——
            // 先把它摘掉再剥注释（WorkbenchExtraScreens.kt 实测踩中）。
            .replace("\"*/*\"", "MIME_WILDCARD")
            // 结构断言看代码不看注释：注释里会引用旧实现（「删掉 VerticalDivider」），不剥会假红
            .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
            .lineSequence().joinToString("\n") { line ->
                val i = line.indexOf("//")
                if (i >= 0) line.substring(0, i) else line
            }
    }

    @Test
    fun `共享层自己也不许有竖线 决策机与材质常量在位`() {
        val board = codeOf("splitBoard")
        assertFalse("SplitBoard.kt 里出现了 VerticalDivider——白缝是唯一分隔语言", board.contains("VerticalDivider("))
        assertTrue("BoardLayout 常量丢了", board.contains("val DetailWidth") && board.contains("val MinListWidth"))
        assertTrue("决策机 paneOf 丢了", board.contains("fun paneOf("))
        assertEquals(
            "表单型详情宽必须复用侧栏 token",
            "WideSecondaryWidth",
            Regex("val DetailWidth: Dp = (\\w+)").find(board)?.groupValues?.get(1) ?: ""
        )
    }

    @Test
    fun `五页都走 SplitBoards 且没有竖线`() {
        for (key in listOf("files", "notes", "memory", "automation", "model")) {
            val src = codeOf(key)
            assertTrue("$key 没有走共享两板骨架 SplitBoards", src.contains("SplitBoards("))
            assertFalse(
                "$key 又出现了 VerticalDivider——两板之间只许白缝",
                src.contains("VerticalDivider(")
            )
        }
    }

    @Test
    fun `笔记内页与全 app 同向 主框左弹_副框右定宽`() {
        // v69（2026-11-21 用户拍板「不同页面的主、副框宽度比要相同」）：笔记内页从
        // fixedFirst（列表 380 在左）翻转为与首页/计划/专注/我的/信息同一套
        // 「主框（概览/编辑器，弹性）在左、副框（列表 380dp）在右」，且宽度统一
        // BoardLayout.DetailWidth——反向形态退役。
        val notes = codeOf("notes")
        assertFalse(
            "笔记内页还在 fixedFirst 反向形态（列表在左）——v69 已统一为主左弹/副右定宽",
            notes.contains("fixedFirst = true")
        )
        assertTrue(
            "笔记内页右板宽度没统一到 BoardLayout.DetailWidth（360dp 旧口径）",
            notes.contains("detailWidth = io.github.m0use001.planability.ui.common.BoardLayout.DetailWidth")
        )
    }

    @Test
    fun `记忆库模态框只留给窄屏`() {
        val memory = codeOf("memory")
        assertTrue(
            "记忆库右板没有就地编辑（MemoryDetailBoard，v77 由 MemoryDetailPane 升级为公开自持久化）",
            (memory.contains("MemoryDetailBoard(") || memory.contains("MemoryDetailPane(")) &&
                memory.contains("MemoryFormBody(st, projects)")
        )
        assertTrue(
            "记忆库在宽屏还在弹编辑模态框——模态框必须以 !twoPane 为条件",
            memory.contains("if (!twoPane && (creating || editing != null))")
        )
    }

    @Test
    fun `自动化右板是详情板 编辑仍走模态`() {
        val automation = codeOf("automation")
        assertTrue("自动化右板详情板丢了", automation.contains("AutomationDetailPane("))
        assertTrue("自动化未选中右板概览丢了", automation.contains("AutomationOverviewPane("))
        assertTrue(
            "自动化宽屏 overlay 没有整屏（fullscreen = autoWide）",
            automation.contains("fullscreen = autoWide")
        )
    }

    @Test
    fun `模型管理列表与详情分板 宽屏整屏`() {
        val model = codeOf("model")
        assertTrue("模型管理没有拆 listPart/detailPart", model.contains("listPart") && model.contains("detailPart"))
        assertTrue(
            "模型管理宽屏 overlay 没有整屏（fullscreen = mmWide）",
            model.contains("fullscreen = mmWide")
        )
    }

    @Test
    fun `智能体编辑页进右板 未选中给概览`() {
        val assistants = codeOf("assistants")
        assertTrue("智能体列表没有接两板", assistants.contains("SplitBoards("))
        assertTrue("智能体未选中右板概览丢了", assistants.contains("AssistantOverviewPane("))
        assertTrue(
            "智能体宽屏 overlay 没有整屏",
            assistants.contains("fullscreen = io.github.m0use001.planability.core.rememberWindowSizeClass().isWide")
        )
    }

    @Test
    fun `中枢审核是刻意的例外 宽屏整屏但不拆两板`() {
        val hub = codeOf("hub")
        assertTrue(
            "中枢审核宽屏 overlay 没有整屏",
            hub.contains("fullscreen = io.github.m0use001.planability.core.rememberWindowSizeClass().isWide")
        )
    }
}
