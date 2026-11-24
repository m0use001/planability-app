package io.github.m0use001.planability.ui.notes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 2026-11-19 用户「文字被挤出」再犯（真机截图：对齐环境里每行公式前面挂着一对
 * **空括号「( )」**，中文注解整段不见了）。
 *
 * 病根两条，都在这一批修掉：
 * 1. **对齐环境拆行路径没剥 CJK**（2026-11-18 拆行修引入）：每行直接进 jlatexmath，
 *    `\text{定义}` 里的中文它画不了 → 渲染成空字形，屏幕上只剩空括号；
 * 2. **纯括注 `(\text{定义})` 不被识别**：剥 CJK 的括注正则要求括号前面有反斜杠命令
 *    （`\quad(…)`），纯括号不认 → `\text` 被剥走后括号留在公式里成空壳。
 */
class AlignedCjkLabelTest {

    /** 被掏空的括号壳：「( )」「(\,)」——中文被剥走后剩下的空壳。 */
    private val EMPTY_PAREN = Regex("""\(\s*(\\[,;:! ])?\s*\)""")

    private fun mathSegs(md: String): List<MathSeg.Math> =
        splitMathSegments(md).filterIsInstance<MathSeg.Math>()

    @Test
    fun `对齐环境每行的中文注解要剥成标签而不是留在公式里`() {
        val md = "\$\$" +
            "\\begin{aligned} (\\text{定义}) f'(x) &= \\lim_{\\Delta x\\to 0}\\frac{\\Delta y}{\\Delta x} " +
            "\\\\ (\\text{微分}) dx &:= \\Delta x " +
            "\\\\ (\\text{结论}) \\frac{dy}{dx} &= f'(x) \\end{aligned}\$\$"
        val segs = mathSegs(md)
        assertEquals(3, segs.size)
        segs.forEach { s ->
            assertFalse(
                "公式里还留着 \\text{CJK}——jlatexmath 画不了 CJK，渲出来是空字形（空括号）\n${s.latex}",
                s.latex.contains("\\text{")
            )
            assertFalse(
                "公式里还留着被掏空的一对括号「( )」\n${s.latex}",
                EMPTY_PAREN.containsMatchIn(s.latex)
            )
        }
        // 中文一条都不许丢
        val labels = segs.joinToString(" | ") { it.labels }
        assertTrue("中文注解丢了：$labels", labels.contains("定义"))
        assertTrue("中文注解丢了：$labels", labels.contains("微分"))
        assertTrue("中文注解丢了：$labels", labels.contains("结论"))
    }

    @Test
    fun `纯括注里的中文要剥成标签且不留空括号`() {
        val md = "\$\$f'(x) = \\lim_{\\Delta x\\to 0}\\frac{\\Delta y}{\\Delta x},\\quad (\\text{一阶导数})\$\$"
        val segs = mathSegs(md)
        assertEquals(1, segs.size)
        assertTrue("中文丢了：${segs[0].labels}", segs[0].labels.contains("一阶导数"))
        assertFalse(
            "公式里还留着被掏空的一对括号「( )」：${segs[0].latex}",
            EMPTY_PAREN.containsMatchIn(segs[0].latex)
        )
    }

    @Test
    fun `行首纯括注没有反斜杠命令也要剥成标签`() {
        // 行首的 `(\text{一阶导数})` —— 前面没有 \quad / \, 这种命令，
        // 老正则（只认带反斜杠前缀的括注）匹配不上 → 中文被剥走后只剩空括号。
        val md = "\$\$(\\text{一阶导数}) f'(x) = x\$\$"
        val segs = mathSegs(md)
        assertEquals(1, segs.size)
        assertTrue("中文丢了：${segs[0].labels}", segs[0].labels.contains("一阶导数"))
        assertFalse(
            "公式里还留着被掏空的一对括号「( )」：${segs[0].latex}",
            EMPTY_PAREN.containsMatchIn(segs[0].latex)
        )
    }

    @Test
    fun `括号里还夹着数学时不许整组吃掉公式`() {
        // `(\text{其中} f'(x))` —— 括注里还有公式本体，只能剥中文、括号与数学都要留住
        val md = "\$\$f'(x) = (\\text{其中} f'(x))\$\$"
        val segs = mathSegs(md)
        assertEquals(1, segs.size)
        assertTrue("公式本体被吃掉了：${segs[0].latex}", segs[0].latex.contains("f'(x)"))
    }

    @Test
    fun `没有中文的纯括号括注原样保留`() {
        val md = "\$\$f(x) = (g(n))$\$"
        val segs = mathSegs(md)
        assertEquals(1, segs.size)
        assertTrue("纯数学括号不该被抽走：${segs[0].latex}", segs[0].latex.contains("(g(n))"))
    }
}
