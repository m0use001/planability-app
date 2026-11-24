package io.github.m0use001.planability.ui.notes

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 封面编号约定的钉子（v19.9）。
 *
 * 背景：新封面从 0 开始编号，而素色默认款原来占着 0（1..10 是同期下线的图案款）。
 * 于是素色让位 [COVER_SOLID] = -1，0 起留给新风格。这套约定横跨三处：
 * 数据（默认值/迁移/备份导入）、UI（选择器与归一化）、AI 工具（默认笔记本）。
 * 三处任一漏改的后果都很难在界面上看出来（画出来是"另一款封面"而不是报错），所以用源码断言钉住。
 */
class CoverNumberingConventionTest {

    private fun src(rel: String): String =
        File("src/main/java/io/github/m0use001/planability/$rel").readText()

    private val entities = src("data/NoteFocusEntities.kt")
    private val db = src("data/AppDatabase.kt")
    private val backup = src("data/BackupManager.kt")
    private val notes = src("ui/notes/NotesScreen.kt")
    private val tools = src("ai/ptl/ToolExecImpls.kt")

    @Test
    fun `素色默认款编号是 -1 且是实体默认值`() {
        assertTrue("COVER_SOLID 必须定义为 -1", entities.contains("const val COVER_SOLID = -1"))
        assertTrue(
            "NotebookEntity.coverStyle 默认值必须是 COVER_SOLID，" +
                "留 0 的话新建笔记本会直接套上新风格 0",
            entities.contains("val coverStyle: Int = COVER_SOLID")
        )
    }

    @Test
    fun `库迁移把 0到10 的历史编号统一改写成素色`() {
        // v19.47：原来是 `contains("version = 30")` 钉死号——DB 每升一版这条就红一次，
        // 而它真正要守的是「29→30 那次迁移已经在册」。改成读版本号再断言下界。
        val dbVersion = Regex("""version\s*=\s*(\d+)""").find(db)?.groupValues?.get(1)?.toInt()
        assertTrue("AppDatabase 里读不到 version = N（写法变了？）", dbVersion != null)
        assertTrue("数据库版本应 >= 30（素色让位 -1 那次迁移）", dbVersion!! >= 30)
        assertTrue("MIGRATION_29_30 必须注册", db.contains("MIGRATION_28_29, MIGRATION_29_30"))
        assertTrue(
            "迁移要把 0..10（素色旧编号 + 已下线图案款）整段归一到 -1",
            db.contains("UPDATE `notebooks` SET `coverStyle` = -1 WHERE `coverStyle` BETWEEN 0 AND 10")
        )
    }

    @Test
    fun `备份导入按版本归一旧封面编号`() {
        // v19.47：同上，别钉死号。备份版本已经走到 5（加 coverIcons），钉 3 必红。
        val bakVersion = Regex(""""version"\s*,\s*(\d+)""").find(backup)?.groupValues?.get(1)?.toInt()
        assertTrue("BackupManager 里读不到 root.put(\"version\", N)", bakVersion != null)
        assertTrue("导出格式版本应 >= 3（v3 起导入端才按版本归一旧封面编号）", bakVersion!! >= 3)
        assertTrue(
            "导入端必须按 version<3 判断是否归一——否则恢复旧备份会把素色当成新风格",
            backup.contains("val normalizeCovers = root.optInt(\"version\", 1) < 3")
        )
        assertTrue(
            "归一条件要覆盖 0..10 整段",
            backup.contains("if (normalizeCovers && rawStyle in 0..10) COVER_SOLID else rawStyle")
        )
    }

    @Test
    fun `选择器是列表驱动的且包含素色`() {
        assertTrue(
            "可用款式必须收敛到 COVER_PICKER_STYLES 列表（加新封面只改这一处）",
            notes.contains("items(COVER_PICKER_STYLES)")
        )
        // v19.33 起列表里除了素色还有 15 款新风格，所以这里断言「列表里含有素色」
        // 而不是「列表等于只有素色」——后者每加一款封面就得改一次测试。
        val listBody = Regex("""val COVER_PICKER_STYLES = listOf\(([\s\S]*?)\)""")
            .find(notes)?.groupValues?.get(1) ?: ""
        assertTrue(
            "COVER_PICKER_STYLES 里至少要有素色默认款 COVER_SOLID",
            listBody.contains("COVER_SOLID")
        )
        assertTrue(
            "素色必须在列表的第一项（选择器默认选中它）",
            listBody.trimStart().startsWith("COVER_SOLID")
        )
    }

    @Test
    fun `编辑弹窗把陌生编号归一为素色`() {
        assertTrue(
            "归一必须走白名单 normalizeCoverStyle —— 写死 1..10 区间会漏掉真机上存在的 13/18 遗留值",
            notes.contains("mutableIntStateOf(normalizeCoverStyle(rawStyle))")
        )
        // v19.33：编号里低位是款式、高位是配色，归一要拆开校验再编回去
        assertTrue(
            "归一必须先拆出款式再校验白名单",
            notes.contains("val base = coverBaseStyle(raw)")
        )
        // v19.34：素色进了配色表，于是它在 COVER_PICKER_STYLES 里是**合法款式**了，
        // 白名单不能再把它一并打成素色（那会把用户选的素色配色整个抹掉）。
        assertTrue(
            "白名单 = 选择器里的款式 + 90 文字款，其余才归素色",
            notes.contains("if (base !in COVER_PICKER_STYLES) return COVER_SOLID")
        )
        assertTrue(
            "文字款不在选择器里，必须单独放行，否则挑了文字预设一开框就被打成素色",
            notes.contains("if (base == COVER_TEXT) return encodeCoverStyle(COVER_TEXT, pal)")
        )
    }

    @Test
    fun `换色按钮已删除且颜色只能从配色方案选`() {
        // 用户 2026-10-14：「素色款也接进配色表，删除换色，直接在同一个界面就可以选择颜色」。
        // 换色按钮是 hue+=47° 去撞一条色环，既撞不到想要的颜色，又和配色芯片重复。
        assertTrue(
            "「换色」按钮必须删掉——它和「配色方案」那一排是同一个功能",
            !notes.contains("Text(\"换色\")")
        )
        assertTrue(
            "NotebookCover 不该再接 hue 参数：配色一律走 CoverPal，" +
                "留着它会让人以为改 hue 能换色",
            !Regex("""fun NotebookCover\(\s*style: Int,\s*hue: Float""").containsMatchIn(notes)
        )
        assertTrue(
            "写库时 hue 应填常量，不再从界面状态回写",
            // v19.47：旧断言把 createNotebook 的**实参列表**整个钉住了——后来给笔记本加了
            // 图标列（cicon/cicons），实参一变就红；它真正要守的只是
            // "hue 传的是常量、不是界面状态"。改成只钉常量出现在调用里。
            Regex("""createNotebook\([^)]*DEFAULT_COVER_HUE""").containsMatchIn(notes)
        )
    }

    @Test
    fun `新建笔记本与 AI 默认笔记本都用素色编号`() {
        assertTrue(
            "分享文本兜底建笔记本时不能用裸 0",
            notes.contains("createNotebook(\"默认笔记本\", COVER_SOLID, 210f)")
        )
        assertTrue(
            "AI 工具建默认笔记本时不能用裸 0",
            tools.contains("createNotebook(\"默认笔记本\", COVER_SOLID, 0f)")
        )
    }
}