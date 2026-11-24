package io.github.m0use001.planability.ui.common

import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.text.AnnotatedString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 代码块自动涂色的词法断言（2026-10-30 用户「加上代码自动涂色」）。
 *
 * 高亮是**纯词法**的，出错不会崩、也不会有日志——只会把注释染成关键字色、把字符串截断成半截，
 * 或者干脆一个颜色都不上（看着像"功能没生效"）。所以这里钉住四类语义的落点：
 * 关键字、字符串、注释、数字；外加两条**反例**（未知语言不猜关键字；标识符里的数字不算数字）。
 */
class CodeHighlightTest {

    private val scheme = lightColorScheme()

    private fun AnnotatedString.stylesIn(start: Int, end: Int): Set<Any> =
        spanStyles.filter { it.start <= start && it.end >= end }
            .map { it.item.color }
            .toSet()

    private fun colored(code: String, lang: String): AnnotatedString =
        CodeHighlight.highlight(code, lang, scheme)

    @Test
    fun `关键字、字符串、注释、数字都要涂上颜色且互不相同`() {
        val code = "val msg = \"hi\" // note\nval n = 42"
        val out = colored(code, "kotlin")
        assertEquals("涂色不应该改变原文（只加 span）", code, out.text)

        val kw = out.stylesIn(0, 3)          // val
        val str = out.stylesIn(12, 13)        // "hi" 的引号
        val cmt = out.stylesIn(20, 21)        // // note
        val num = out.stylesIn(code.indexOf("42"), code.indexOf("42") + 2)
        assertTrue("关键字 val 没上色", kw.isNotEmpty())
        assertTrue("字符串没上色", str.isNotEmpty())
        assertTrue("注释没上色", cmt.isNotEmpty())
        assertTrue("数字 42 没上色", num.isNotEmpty())

        val all = listOf(kw, str, cmt, num)
        assertEquals("四类语义的颜色必须两两不同（否则等于没区分）", 4, all.map { it.first() }.toSet().size)
    }

    @Test
    fun `注释里的关键字不再单独上色（注释优先级最高）`() {
        val code = "// return val if"
        val out = colored(code, "kotlin")
        // 整段注释只应有一种颜色：注释色
        val colors = out.spanStyles.map { it.item.color }.toSet()
        assertEquals("注释里的关键字被单独染色了：$colors", 1, colors.size)
    }

    @Test
    fun `未知语言只涂注释字符串数字，不猜关键字`() {
        val code = "def x = \"s\" 7"
        val out = colored(code, "text")
        // def 在 python 里是关键字，但语言是 text → 不上关键字色
        assertTrue("未知语言不应给 def 上关键字色", out.stylesIn(0, 2).isEmpty())
        assertTrue("字符串仍应上色", out.stylesIn(8, 9).isNotEmpty())
        assertTrue("数字仍应上色", out.stylesIn(12, 12).isNotEmpty())
    }

    @Test
    fun `标识符里的数字不算数字常量`() {
        val code = "abc123"
        val out = colored(code, "kotlin")
        // 整个 abc123 是一个标识符 → 只有一发"变量"色，不是"数字"色
        assertEquals("abc123 应整体按标识符上色", 1, out.spanStyles.size)
        assertEquals(0, out.spanStyles.first().start)
        assertEquals(code.length, out.spanStyles.first().end)
    }

    @Test
    fun `配色必须等于 VS Code 默认主题的官方色值`() {
        fun colorOf(code: String, lang: String, dark: Boolean, at: Int) =
            CodeHighlight.highlight(code, lang, if (dark) darkColorScheme() else lightColorScheme())
                .spanStyles.first { it.start <= at && it.end > at }.item.color

        // 暗色 = Dark+
        assertEquals(Color(0xFF6A9955), colorOf("// c", "kotlin", true, 0))
        assertEquals(Color(0xFFCE9178), colorOf("\"s\"", "kotlin", true, 0))
        assertEquals(Color(0xFFB5CEA8), colorOf("42", "kotlin", true, 0))
        assertEquals(Color(0xFFC586C0), colorOf("return", "kotlin", true, 0))
        assertEquals(Color(0xFF569CD6), colorOf("val", "kotlin", true, 0))
        assertEquals(Color(0xFF4EC9B0), colorOf("Int", "kotlin", true, 0))
        assertEquals(Color(0xFFDCDCAA), colorOf("foo()", "kotlin", true, 0))
        assertEquals(Color(0xFF9CDCFE), colorOf("count", "kotlin", true, 0))
        // 亮色 = Light+
        assertEquals(Color(0xFF008000), colorOf("// c", "kotlin", false, 0))
        assertEquals(Color(0xFFA31515), colorOf("\"s\"", "kotlin", false, 0))
        assertEquals(Color(0xFFAF00DB), colorOf("return", "kotlin", false, 0))
        assertEquals(Color(0xFF0000FF), colorOf("val", "kotlin", false, 0))
    }

    @Test
    fun `函数名、类型、变量三类按 VS Code 规则分开`() {
        val code = "foo bar Baz"
        val out = colored(code, "kotlin")
        // foo 后面没括号 → 变量；Baz 首字母大写 → 类型
        val functionCall = colored("foo(1)", "kotlin")
        val fn = functionCall.stylesIn(0, 3)
        assertTrue("foo( 应识别为函数名", fn.isNotEmpty())
        assertTrue("首字母大写应识别为类型", out.stylesIn(8, 11).isNotEmpty())
    }

    @Test
    fun `超长代码直接返回原文不涂色（流式期间每帧重算会拖帧）`() {
        val code = "val a = 1\n".repeat(CodeHighlight.MAX_CHARS / 8 + 10)
        val out = colored(code, "kotlin")
        assertEquals(code, out.text)
        assertTrue("超长块不该产生 span", out.spanStyles.isEmpty())
    }

    @Test
    fun `SQL 关键字按小写匹配`() {
        val out = colored("select * from t", "sql")
        assertTrue("小写 select 没被识别成 SQL 关键字", out.stylesIn(0, 5).isNotEmpty())
    }

    @Test
    fun `主题派生方案保留 VS Code 的色相间距，只整体旋转`() {
        fun hue(c: Color): Float {
            val r = c.red; val g = c.green; val b = c.blue
            val max = maxOf(r, g, b); val min = minOf(r, g, b)
            if (max == min) return 0f
            val d = max - min
            return (when (max) {
                r -> ((g - b) / d + if (g < b) 6f else 0f)
                g -> ((b - r) / d + 2f)
                else -> ((r - g) / d + 4f)
            } * 60f)
        }
        fun gap(a: Float, b: Float): Float = (a - b).let { if (it > 180f) it - 360f else if (it < -180f) it + 360f else it }

        val scheme = darkColorScheme()
        val vs = CodeHighlight.paletteFor(scheme, CodeHighlight.Style.VSCODE)
        val th = CodeHighlight.paletteFor(scheme, CodeHighlight.Style.THEME)

        // 派生方案里"声明关键字"应落在当前主题主色的色相上
        val drift = gap(hue(th.keywordDecl), hue(scheme.primary))
        assertTrue("派生方案的声明关键字应等于主题主色色相（偏差 $drift）", kotlin.math.abs(drift) < 2f)

        // 各 token 之间的相对色相间隔保持不变（这正是"沿用 VS Code 色相关系"的含义）
        listOf(
            vs.string to th.string,
            vs.comment to th.comment,
            vs.number to th.number,
            vs.keywordControl to th.keywordControl,
            vs.type to th.type,
            vs.function to th.function
        ).forEach { (a, b) ->
            val expect = gap(hue(a), hue(vs.keywordDecl))
            val actual = gap(hue(b), hue(th.keywordDecl))
            assertTrue(
                "色相间距被破坏：期望 $expect 实际 $actual",
                kotlin.math.abs(expect - actual) < 2f
            )
        }
        // 派生后各 token 仍然两两不同（不然旋转后就糊成一片）
        val hues = listOf(th.comment, th.string, th.number, th.keywordControl, th.type, th.function)
            .map { Math.round(hue(it)) }
        assertEquals("派生后存在同色相 token：$hues", hues.size, hues.toSet().size)
    }

    @Test
    fun `两套方案的默认值与回落`() {
        assertEquals(CodeHighlight.Style.VSCODE, CodeHighlight.Style.of(null))
        assertEquals(CodeHighlight.Style.VSCODE, CodeHighlight.Style.of("bogus"))
        assertEquals(CodeHighlight.Style.THEME, CodeHighlight.Style.of("theme"))
    }
}
