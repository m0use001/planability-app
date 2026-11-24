package io.github.m0use001.planability.ui.notes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 对齐环境（`\begin{aligned}`）整块拆行（2026-11-18 真机「又出现乱码」）。
 *
 * 真源：「辅助学习微积分」会话消息原文。用户截图是满屏
 * `\begin{aligned} … & … &&\text{…}` 裸 LaTeX —— jlatexmath 画不了这种
 * 「双列分隔 + 行内 CJK」的结构，整块解析失败回退原文。
 *
 * 判据：拆完**任何一段（公式或标签/正文）都不许再出现** `\begin`、`&`、`\text{`
 * ——那三样正是用户看到的「乱码」。同时推导顺序与每行注解必须一一对上。
 */
class AlignedRowsSplitTest {

    private val src = """
$$
\begin{aligned}
D_x(y^3)+D_x(7y)&=D_x(x^3)\\
3y^2\cdot y'+7y'&=3x^2 &&\text{左边：链式——外层 }u^3\text{ 出 }3u^2\text{，内层 }y(x)\text{ 出 }y'\\
&&&\text{中间：常数倍直接提}\\
&&&\text{右边：}x\text{ 是自变量，不乘任何东西}\\
y'(3y^2+7)&=3x^2 &&\text{把 }y'\text{ 提取公因式}\\[4pt]
y'&=\frac{3x^2}{3y^2+7}&&\text{解出}
\end{aligned}
$$
""".trimIndent()

    private fun segs() = splitMathSegments(src)

    @Test
    fun `真机原文不再回退裸 LaTeX`() {
        val all = segs()
        val dump = all.joinToString("\n") {
            when (it) {
                is MathSeg.Math -> "MATH[${it.latex}] LABEL[${it.labels}]"
                is MathSeg.Txt -> "TXT[${it.text}]"
            }
        }
        assertTrue("残留 \begin：\n$dump", !dump.contains("\\begin"))
        assertTrue("残留对齐符 &：\n$dump", !dump.contains("&"))
        assertTrue("残留 \\text{ 命令：\n$dump", !dump.contains("\\text{"))
        assertTrue("残留间距参数 [4pt]：\n$dump", !dump.contains("[4pt]"))
    }

    @Test
    fun `每行拆成独立公式且注解落到行标签`() {
        val maths = segs().filterIsInstance<MathSeg.Math>()
        assertEquals("应 4 条公式行（另两行纯注解）", 4, maths.size)
        assertEquals("D_x(y^3)+D_x(7y)=D_x(x^3)", maths[0].latex)
        assertTrue("第 2 行公式：${maths[1].latex}", maths[1].latex.startsWith("3y^2\\cdot y'+7y'=3x^2"))
        assertTrue("第 2 行注解：${maths[1].labels}", maths[1].labels.contains("左边：链式"))
        assertTrue("第 2 行注解：${maths[1].labels}", maths[1].labels.contains("外层"))
        assertTrue("第 3 行注解：${maths[2].labels}", maths[2].labels.contains("提取公因式"))
        assertEquals("解出", maths[3].labels)
    }

    @Test
    fun `纯注解行回正文`() {
        val txt = segs().filterIsInstance<MathSeg.Txt>().joinToString("") { it.text }
        assertTrue("缺「中间：常数倍直接提」：$txt", txt.contains("中间：常数倍直接提"))
        assertTrue("缺「右边：x 是自变量」：$txt", txt.contains("右边：x 是自变量"))
    }

    @Test
    fun `非对齐环境不受影响`() {
        val D = "$"
        val plain = D + D + "x=1" + D + D
        assertEquals(1, splitMathSegments(plain).filterIsInstance<MathSeg.Math>().size)
        // 对齐环境只是块的一部分（前后还有内容）时不接管
        val mixed = D + D + "a=1\\quad\\begin{aligned}x&=1\\\\y&=2\\end{aligned}" + D + D
        assertTrue(
            "非整块对齐环境不该被拆",
            splitAlignedRows("a=1 \\begin{aligned}x&=1\\\\y&=2\\end{aligned}") == null
        )
        assertTrue(splitMathSegments(mixed).isNotEmpty())
    }
}
