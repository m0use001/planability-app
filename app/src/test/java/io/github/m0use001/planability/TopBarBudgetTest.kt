package io.github.m0use001.planability

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 2026-11-01：**顶部预算规则**的源码级断言（设计文档 §六）。
 *
 * 规则：主页面顶栏 actions **最多 1 枚**，且固定为溢出菜单 `⋮`；低频操作一律进菜单
 * 或抽屉。琐碎感的根因是「每个功能都配常驻按钮」——加一条防回归的闸，防止以后又长回去。
 *
 * 覆盖本轮三处收敛：
 * - 计划页：分类管理收进 ⋮（原来单独占一枚常驻图标）
 * - 笔记列表：右滑露出的 置顶/移动/删除 三图标 → 长按菜单（行常驻只剩内容）
 * - 信息首页：删与抽屉重复的「自动化」入口胶囊（同步中心是信息侧唯一入口，保留）
 */
class TopBarBudgetTest {

    private fun src(rel: String): String {
        val root = generateSequence(File(System.getProperty("user.dir") ?: ".").absoluteFile) { it.parentFile }
            .firstOrNull { File(it, "app/src/main/java/io/github/m0use001/planability").isDirectory }
        assertTrue("找不到仓库根", root != null)
        val f = File(root, rel)
        assertTrue("源码不在了：$rel", f.isFile)
        return f.readText(Charsets.UTF_8)
    }

    /** 取 [from] 之后第一个 `actions = {` 起的花括号配平块（含两端）。 */
    private fun actionsBlock(text: String, from: Int): String {
        val start = text.indexOf("actions = {", from)
        assertTrue("from 之后没有 actions 块", start >= 0)
        var depth = 0
        var i = start + "actions = ".length
        while (i < text.length) {
            when (text[i]) {
                '{' -> depth++
                '}' -> { depth--; if (depth == 0) return text.substring(start, i + 1) }
            }
            i++
        }
        assertTrue("actions 块没闭合", false)
        return ""
    }

    /** 从 [from] 起第一个 `{` 开始的配平块（含两端）——不依赖前面写过 "actions ="。 */
    private fun bracedBlock(text: String, from: Int): String {
        val start = text.indexOf('{', from)
        assertTrue("from 之后没有 {", start >= 0)
        var depth = 0
        var i = start
        while (i < text.length) {
            when (text[i]) {
                '{' -> depth++
                '}' -> { depth--; if (depth == 0) return text.substring(start, i + 1) }
            }
            i++
        }
        assertTrue("块没闭合", false)
        return ""
    }

    @Test
    fun `计划页顶栏只有一枚⋮且分类管理进了溢出菜单`() {
        val s = src("app/src/main/java/io/github/m0use001/planability/ui/plan/PlanScreen.kt")
        // 锚点注释在 ⋮ 块内部——向前找最近的块起点。
        // v62（2026-11-19）：计划页的这枚 ⋮ 从 M3ETitleBar 的 actions 提成了 planOverflowMenu 这个
        // lambda（宽屏板头与手机标题栏共用同一份），所以块起点跟着挪到 lambda 上；
        // 断言的对象与预算规则（actions 最多 1 枚、分类管理在菜单里）一个字都不变。
        val anchor = s.indexOf("顶栏 actions 固定 1 枚 ⋮")
        assertTrue("计划页顶栏锚点注释找不到了", anchor >= 0)
        val blockStart = s.indexOf("val planOverflowMenu", anchor)
        assertTrue("锚点前面没有 planOverflowMenu 块", blockStart >= 0)
        val block = bracedBlock(s, blockStart)
        val count = Regex("\\bIconButton\\(").findAll(block).count()
        assertTrue("计划页顶栏 actions 有 $count 枚 IconButton（预算=1）", count == 1)
        assertTrue("计划页顶栏那枚不是 ⋮", block.contains("Icons.Rounded.MoreVert"))
        assertFalse(
            "分类管理图标又回到顶栏常驻了——它应该在 ⋮ 菜单里",
            block.contains("Icons.Rounded.Label")
        )
        assertTrue("⋮ 菜单里没有「分类管理」项", block.contains("分类管理"))
    }

    @Test
    fun `会话页顶栏只有一枚⋮`() {
        val s = src("app/src/main/java/io/github/m0use001/planability/ui/info/ChatScreen.kt")
        // 锚点注释（v19.8）在 actions 块**内部**——向前找最近的块起点，别向后找块。
        // v65（2026-11-20）：actions 从 M3ETitleBar 内联提成 chatTopActions 这个 lambda
        // （宽屏 BoardHeader 与手机标题栏共用同一份），所以块起点跟着挪到 lambda 上；
        // 断言的对象与预算规则（actions 最多 1 枚）一个字都不变。
        val anchor = s.indexOf("v19.8：齿轮 = 原 ⋮ 的会话功能菜单")
        assertTrue("会话页顶栏锚点注释找不到了", anchor >= 0)
        val blockStart = s.lastIndexOf("val chatTopActions", anchor)
        assertTrue("锚点前面没有 chatTopActions 块", blockStart >= 0)
        val block = bracedBlock(s, blockStart)
        val count = Regex("\\bIconButton\\(").findAll(block).count()
        assertTrue("会话页顶栏 actions 有 $count 枚 IconButton（预算=1）", count == 1)
        assertTrue("会话页顶栏那枚不是 ⋮", block.contains("Icons.Rounded.MoreVert"))
    }

    @Test
    fun `笔记行的右滑三图标已改长按菜单`() {
        val s = src("app/src/main/java/io/github/m0use001/planability/ui/notes/NotesScreen.kt")
        // 右滑露出的状态机制（dragX/revealPx/onRevealState）必须不在——操作已收进长按菜单。
        // 注意 detectHorizontalDragGestures 本身要留着（只消费不响应），否则老用户的横滑
        // 肌肉记忆会被 combinedClickable 误判成长按、凭空弹菜单（plan36b 实测过）。
        for (gone in listOf("dragX", "revealPx", "onRevealState")) {
            assertFalse("右滑露出机制「$gone」又回来了——三图标已收进长按菜单", s.contains(gone))
        }
        for (item in listOf("取消置顶", "移动到笔记本", "删除", "多选")) {
            assertTrue("长按菜单缺「$item」项", s.contains("\"$item\""))
        }
        assertFalse(
            "批量多选还能从长按直接进——长按手势已让给菜单，多选只能走菜单末项",
            Regex("onLongClick = \\(\\)[^\\n]*\\n[^\\n]*selectionMode = true").find(s) != null
        )
    }

    @Test
    fun `信息首页不再有与抽屉重复的自动化入口胶囊`() {
        val s = src("app/src/main/java/io/github/m0use001/planability/ui/info/InfoCenterScreen.kt")
        assertFalse(
            "「自动化」入口胶囊回到信息首页了——它与抽屉重复（抽屉有「自动化与扩展」+自动化文件夹）",
            s.contains("InfoHomeCapsule(\"自动化\"")
        )
        // 同步中心是信息侧唯一入口（抽屉无同步项，2026-10-24 用户拍板从文件页迁来），必须保留
        assertTrue("「同步中心」入口胶囊被误删了", s.contains("InfoHomeCapsule(\"同步中心\""))
    }
}
