package io.github.m0use001.planability.ui.notes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v56.3 中文入公式「同类潜在 bug」电池：模型真实产出的 CJK 公式形态逐条钉住
 * （标签不碎、句子桥并入一条、连接词回正文、推导链老形态不变）。
 * 每条失败都对应一类回归（桥接字符集收窄/合并规则回退/连接词误挤）。
 */
class CjkFormulaBatteryTest {

    private fun mathSegs(body: String): List<MathSeg.Math> =
        splitMathSegments("$$" + body + "$$").filterIsInstance<MathSeg.Math>()

    private fun singleMath(body: String): MathSeg.Math {
        val segs = mathSegs(body)
        assertEquals("应合成一条块级公式：" + segs, 1, segs.size)
        return segs[0]
    }

    @Test
    fun 实录_underbrace标注留在花括号下() {
        // 2026-11-22 用户截图「文字飞了」拍板反转 909b74f5：CJK 外部字体注册后
        // （LatexRender.cjkFontRegistered），`\underbrace{…}_{\text{…}}` 的标注真正
        // 渲在花括号正下方，不再剥出来挂到公式下方一行（花括号下标空壳 + 标注飞走）。
        val m = singleMath("f(x)-f(c)=\\underbrace{\\frac{f(x)-f(c)}{x-c}}_{\\text{被 }f'(c)\\text{ 控制}}\\cdot\\underbrace{(x-c)}_{\\to 0}")
        assertEquals("标注被剥走了：${m.labels}", "", m.labels)
        assertTrue("标注没留在公式里：\n${m.latex}", m.latex.contains("\\text{被 }") && m.latex.contains("\\text{ 控制}"))
        assertTrue("差商本体丢了：\n${m.latex}", m.latex.contains("\\frac"))
    }

    @Test
    fun 实录_underbrace纯中文标注不飞() {
        val m = singleMath("u\\,v+\\underbrace{\\Delta u\\,\\Delta v}_{\\text{二阶小量}}")
        assertEquals("标注被剥走了：${m.labels}", "", m.labels)
        assertTrue("标注没留在公式里：\n${m.latex}", m.latex.contains("\\text{二阶小量}"))
        assertTrue("underbrace 丢了：\n${m.latex}", m.latex.contains("\\underbrace"))
    }

    @Test
    fun 结构区间_underbrace_protect范围() {
        val s = "a+\\underbrace{x^{2}}_{\\text{项}}+b"
        val rs = braceScriptRanges(s)
        assertEquals(1, rs.size)
        val seg = s.substring(rs[0].first, rs[0].last + 1)
        assertEquals("\\underbrace{x^{2}}_{\\text{项}}", seg)
        // 没有 script 组的 underbrace 不保护
        assertTrue(braceScriptRanges("\\underbrace{x}+1").isEmpty())
        // overbrace 上标同保护
        assertEquals(1, braceScriptRanges("\\overbrace{ab}^{\\text{和}}").size)
    }

    @Test
    fun 行内高公式_压缩超两成判块级() {
        assertFalse("轻微压缩仍留行内", tallInlineSqueezed(2.0f, 2.2f))
        assertTrue("压到 0.7 倍必须提升块级", tallInlineSqueezed(3.0f, 2.2f))
        assertFalse("自然高为 0 不判", tallInlineSqueezed(0f, 2.2f))
    }

    @Test
    fun 条件句_整句行内混排不抽标签() {
        // v56.4：多段中文（当/时，有）改为**整句行内流式**——以前抽成一条标签挂在公式下方，
        // 公式那头只剩 `f(x)>0`；现在中文留在原位：「当 x>1 时，有 f(x)>0」。
        val segs = splitMathSegments("$$\\text{当 }x>1\\text{ 时，有 }f(x)>0$$")
        assertEquals(
            listOf("当", "x>1", "时，有", "f(x)>0"),
            segs.map { if (it is MathSeg.Math) it.latex else (it as MathSeg.Txt).text }
        )
        assertTrue("行内混排的数学段必须 display=false", segs.filterIsInstance<MathSeg.Math>().none { it.display })
        assertTrue("不许再抽标签", segs.filterIsInstance<MathSeg.Math>().all { it.labels.isBlank() })
    }

    @Test
    fun 减号桥_整句行内混排不抽标签() {
        val segs = splitMathSegments("$$\\text{由 }x-c\\text{ 知 }x>c$$")
        assertEquals(
            listOf("由", "x-c", "知", "x>c"),
            segs.map { if (it is MathSeg.Math) it.latex else (it as MathSeg.Txt).text }
        )
        assertTrue(segs.filterIsInstance<MathSeg.Math>().none { it.display })
    }

    @Test
    fun 推导链整行走标签老路() {
        val m = singleMath("1 \\neq -1 \\Rightarrow \\text{双侧极限不存在} \\Rightarrow \\text{不可导}")
        assertEquals("⇒ 双侧极限不存在 ⇒ 不可导", m.labels)
        assertTrue(!m.latex.contains("\\Rightarrow"))
    }

    @Test
    fun 反斜杠命令桥不合并_留在公式里() {
        val m = singleMath("A\\text{可逆}\\iff A\\mathbf{x}=\\mathbf{b}\\text{对任意}\\mathbf{b}\\text{有唯一解}\\iff A\\text{的 }n\\text{个列向量线性无关}")
        assertEquals(
            "可逆 ⟺ · 对任意 · 有唯一解 ⟺ A 的 n 个列向量线性无关",
            m.labels
        )
        assertTrue(m.latex.contains("\\mathbf{b}\\,\\mathbf{b}"))
    }

    @Test
    fun 无箭头连接词拆成顺序块() {
        val segs = splitMathSegments("\$\$D(x)=f'(c)+\\alpha(x)，其中 \\lim_{x\\to c}\\alpha(x)=0\$\$")
        val txt = segs.filterIsInstance<MathSeg.Txt>().joinToString("|") { it.text }
        assertTrue("连接词没回正文：$txt", txt.contains("其中"))
        val maths = segs.filterIsInstance<MathSeg.Math>()
        assertTrue("两段公式没了：$maths", maths.size == 2)
        assertTrue(maths[0].latex.startsWith("D(x)"))
        assertTrue(maths[1].latex.contains("\\lim_{x\\to c}"))
    }

    @Test
    fun 行内中文混排_回归() {
        val segs = splitMathSegments("""$\text{体积}=\frac{4}{3}\pi r^{3}$""")
        assertTrue(segs.filterIsInstance<MathSeg.Math>().any { it.latex.contains("\\frac{4}{3}") })
        assertTrue(segs.filterIsInstance<MathSeg.Txt>().any { it.text.contains("体积") })
    }
}
