package io.github.m0use001.planability.ui.notes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 2026-11-24 真机三连报障（◆）的回归网：
 *  · 消息 8089：`\xrightarrow{\ \text{切}\ }` 链渲染成「}→」（标签结尾悬空反斜杠被
 *    解析成转义右括号 `\}`）。
 *  · 消息 8097：`\begin{cases}` 里带 `\text{CJK}` 时整块被按行拆散文，每段解析失败 →
 *    整屏裸 LaTeX。
 * 两条都已由纯函数修复（[sanitizeArrowLabel] / [isSingleMathEnv]）。
 */
class ArrowLabelAndCasesTest {

    // Kotlin 字符串里 `$$` 的第二个 $ 会被当模板头，价格符统一用 D 拼接
    private val D = "\u0024"

    private fun display(body: String) = D + D + body + D + D

    private fun math(src: String) = splitMathSegments(src).filterIsInstance<MathSeg.Math>()

    // ---------- xrightarrow 标签清洗（消息 8089） ----------

    @Test
    fun `xrightarrow_纯间距标签退化为纯箭头_不产生转义右括号`() {
        val m = math(display("P\\ \\xrightarrow{\\ \\text{切}\\ }\\ \\Delta x_i")).single()
        assertFalse("不得出现 \\} 转义右括号（「}→」的真源）", m.latex.contains("\\}"))
        assertFalse("不得再有未转换的 xrightarrow", m.latex.contains("\\xrightarrow"))
        assertTrue("空标签箭头应退化为纯箭头", m.latex.contains("\\rightarrow"))
    }

    @Test
    fun `xrightarrow_数学标签保留_结尾悬空反斜杠被清`() {
        val m = math(display("\\xrightarrow{\\ \\bar x_i\\ }R_P")).single()
        assertTrue("数学标签应保留在箭头上方", m.latex.contains("\\overset{\\bar x_i}{\\rightarrow}"))
        assertFalse(m.latex.contains("\\}"))
    }

    @Test
    fun `sanitizeArrowLabel_间距命令清空_悬挂反斜杠清掉`() {
        assertEquals("", sanitizeArrowLabel("\\ \\,\\ "))
        assertEquals("", sanitizeArrowLabel("\\,"))
        assertEquals("", sanitizeArrowLabel("\\ "))
        assertEquals("x\\to0", sanitizeArrowLabel("x\\to0"))
        assertEquals("\\bar x_i", sanitizeArrowLabel("\\ \\bar x_i\\ "))
    }

    @Test
    fun `真实消息8089链式箭头_全链无转义右括号`() {
        val src = display(
            "P\\ \\xrightarrow{\\ \\text{切}\\ }\\ \\Delta x_i\\ " +
                "\\xrightarrow{\\ \\text{每段取}\\ \\bar x_i\\ }\\ f(\\bar x_i)\\Delta x_i\\ " +
                "\\xrightarrow{\\ \\sum\\ }\\ R_P\\ \\xrightarrow{\\ \\|P\\|\\to0\\ }\\ \\int_a^b f(x)\\,dx"
        )
        val m = math(src).single()
        assertFalse("链上任何箭头都不许渲染成 }→", m.latex.contains("\\}"))
        assertFalse(m.latex.contains("\\xrightarrow"))
        assertTrue("标签应抽出（切/每段取）", m.labels.contains("切") && m.labels.contains("每段取"))
    }

    // ---------- cases 环境不可按行拆（消息 8097） ----------

    @Test
    fun `isSingleMathEnv_只认整块单环境_允许纯数学前缀`() {
        assertTrue(isSingleMathEnv("\\begin{cases}a\\\\b\\end{cases}"))
        assertTrue(isSingleMathEnv("  \\begin{aligned}x\\end{aligned}  "))
        assertTrue(isSingleMathEnv("\\begin{array}{ll}a&b\\end{array}"))
        // 纯数学前缀（真实消息 8097 的形态）也算整块单环境
        assertTrue(isSingleMathEnv("a=\\begin{cases}x\\end{cases}"))
        assertTrue(isSingleMathEnv("f(\\bar x_i)=\n\\begin{cases}x\\end{cases}"))
        assertFalse("环境后面还挂内容 = 不是整块", isSingleMathEnv("\\begin{cases}a\\end{cases}+b"))
        assertFalse(isSingleMathEnv("x+1"))
    }

    @Test
    fun `cases含中文_整块一个公式段_环境保留_中文进标签`() {
        val segs = math(display("y=\\begin{cases}a,&\\text{正}\\\\ b,&\\text{负}\\end{cases}"))
        assertEquals("整块应只产出一个 Math 段（不许按行拆散）", 1, segs.size)
        val m = segs.single()
        assertTrue("cases 环境必须保留", m.latex.contains("\\begin{cases}"))
        assertTrue(m.latex.contains("\\end{cases}"))
        assertFalse("不得泄漏裸 \\text 组", m.latex.contains("\\text{正}"))
        assertTrue("中文应剥进标签", m.labels.contains("正") && m.labels.contains("负"))
    }

    @Test
    fun `真实消息8097三种取法_整块不拆`() {
        val src = display(
            "f(\\bar x_i)=\n\\begin{cases}\nf(x_{i-1}) & \\text{左端点}\\\\[2pt]\n" +
                "f(x_i) & \\text{右端点}\\\\[2pt]\n" +
                "f\\!\\left(\\dfrac{x_{i-1}+x_i}{2}\\right) & \\text{中点}\n\\end{cases}"
        )
        val segs = math(src)
        assertEquals("整块一个公式段", 1, segs.size)
        val m = segs.single()
        assertTrue(m.latex.contains("\\begin{cases}") && m.latex.contains("\\end{cases}"))
        assertFalse("不许把 \\begin{cases} 当正文裸显", m.latex.contains("\\text{左端点}"))
        assertTrue(
            "三个中文标注都应进标签",
            m.labels.contains("左端点") && m.labels.contains("右端点") && m.labels.contains("中点")
        )
    }
}
