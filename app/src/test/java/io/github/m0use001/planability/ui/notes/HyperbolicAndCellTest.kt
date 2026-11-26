package io.github.m0use001.planability.ui.notes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 2026-11-18 真机第二轮「又出现乱码」（◆ 消息 7721）。两条独立病因：
 *
 * ① 表格单元格渲染前被 `take(120)` 截断，长格从公式中间切断 → `$` 配对失败，
 *    后半截连 `$` 一起当正文裸显（截图里「得 $\dfrac{4x^4-9x^2+8x}{(4x^2-3)^」）。
 * ② 模型用中文教材的双曲简写 `\sh/\ch/\th`、`\arsh/\arch/\arth` 与 `\tfrac`，
 *    jlatexmath 宏表里都没有 → 整块/整格解析失败，整列公式裸显。
 */
class HyperbolicAndCellTest {

    private fun latexOf(src: String) = splitMathSegments(src)
        .filterIsInstance<MathSeg.Math>().joinToString(" | ") { it.latex }

    @Test
    fun `双曲简写改写成 jlatexmath 认识的名字`() {
        val D = "$"
        val l = latexOf(D + "\\sh x=\\frac{e^x-e^{-x}}{2}" + D)
        assertTrue("\\sh 没换：$l", !Regex("""\\sh(?![A-Za-z])""").containsMatchIn(l))
        assertTrue("没有 \\sinh：$l", l.contains("\\sinh"))

        val c = latexOf(D + "\\ch^2x-\\sh^2x=1" + D)
        assertTrue("\\ch 没换：$c", c.contains("\\cosh") && c.contains("\\sinh"))

        val t = latexOf(D + "(\\th x)'=\\frac{1}{\\ch^2x}" + D)
        assertTrue("\\th 没换：$t", t.contains("\\tanh"))
    }

    @Test
    fun `反双曲与 tfrac 改写且不误伤 sharp`() {
        val D = "$"
        val l = latexOf(D + "\\arsh x=\\ln(x+\\sqrt{x^2+1})" + D)
        assertTrue("\\arsh 没换：$l", !l.contains("\\arsh"))
        assertTrue("没有 arsinh：$l", l.contains("arsinh"))

        val f = latexOf(D + "\\arth x=\\tfrac12\\ln\\tfrac{1+x}{1-x}" + D)
        assertTrue("\\tfrac 残留：$f", !f.contains("\\tfrac"))
        assertTrue("没有 \\frac：$f", f.contains("\\frac"))

        val sharp = latexOf(D + "\\sharp" + D)
        assertEquals("\\sharp 被误伤：$sharp", "\\sharp", sharp)
    }

    @Test
    fun `单元格渲染取全文不被截断`() {
        val cell = "前".repeat(130) + "尾巴"
        val got = cellText(listOf(cell, "x"), 0, 2)
        assertEquals("单元格被截断了", cell, got)
        // 末列合并口径不变
        assertEquals("b / c", cellText(listOf("a", "b", "c"), 1, 2))
    }

    @Test
    fun `真机长单元格不再留下半截美元符`() {
        // 真机原格（截断点正好落在最后一个公式中间）
        val cell = "例1 " + "$4x^2y-3y=x^3-1$" + "：其实 " + "${'$'}y=\\dfrac{x^3-1}{4x^2-3}${'$'}" +
            " 解得出，但代进 " + "${'$'}y'=\\dfrac{3x^2-8xy}{4x^2-3}${'$'}" +
            " 得 " + "${'$'}\\dfrac{4x^4-9x^2+8x}{(4x^2-3)^2}${'$'}" + "，纯属自找麻烦"
        val rendered = cellText(listOf(cell, "x"), 0, 2)
        val txt = splitMathSegments(rendered).filterIsInstance<MathSeg.Txt>().joinToString("") { it.text }
        assertTrue("正文残留 \$（配对断了）：$txt", !txt.contains("$"))
        assertEquals("公式段数不对", 4, splitMathSegments(rendered).count { it is MathSeg.Math })
    }
}
