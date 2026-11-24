package io.github.m0use001.planability.ui.notes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 2026-11-11 用户截图（公式/表格渲染四处漏原文）的回归网：
 * ①勾选清单加粗裸露（ChatWorkflowSheet 改走 MdInlineText，源码守卫）；
 * ②表格硬折行 → 列错位、`\sq`/`rt3` 腰斩（[mergeTableRows]）；
 * ③行内公式被折行写断（`\Longrightar` + `row`）两侧配不上 `$`（[prejoinSplitMathLines]）；
 * ④整行裸 LaTeX（无 `$`）漏原文（[bareLatexLine]）。
 */
class MarkdownSplitMathGuardTest {

    @Test
    fun `折行断在命令字中间 - 无空格拼回`() {
        val raw = listOf(
            "a_2=\\frac{4+3}{4}=1.75<2\\quad\\Longrightar",
            "row\\quadA=1",
            "正常一行"
        )
        val out = prejoinSplitMathLines(raw)
        assertEquals(2, out.size)
        assertTrue("命令必须被拼回：${out[0]}", out[0].contains("\\Longrightarrow"))
        assertEquals("正常一行", out[1])
    }

    @Test
    fun `下一行是块开始时不吞结构`() {
        val raw = listOf("价格 \$5 和", "- 列表项")
        val out = prejoinSplitMathLines(raw)
        assertEquals(2, out.size)
    }

    @Test
    fun `配对正常的行不动`() {
        val raw = listOf("这是 \$a+b\$ 公式", "\$x\$")
        val out = prejoinSplitMathLines(raw)
        assertEquals(raw, out)
    }

    @Test
    fun `裸 LaTeX 行识别`() {
        assertEquals(
            "a_2=\\frac{4+3}{4}=1.75<2\\quad\\Longrightarrow A=1",
            bareLatexLine("a_2=\\frac{4+3}{4}=1.75<2\\quad\\Longrightarrow A=1")
        )
        assertNull(bareLatexLine("普通正文，含 \\frac 一个命令"))      // 含中文不算
        assertNull(bareLatexLine("已有 \$a+b\$ 定界符"))               // 已有 $
        assertNull(bareLatexLine("`\\frac{1}{2}` 行内码"))             // 代码
        assertNull(bareLatexLine("路径 C:\\Users\\test"))              // 命令数不足 2
    }

    @Test
    fun `表格硬折行并回上一行`() {
        val grid = listOf(
            listOf("项", "误差界 \$a_n-\\sq", "实际值"),
            listOf("rt3 \$"),
            listOf("n = 1", "0.7321", "0.7321")
        )
        val out = mergeTableRows(grid)
        assertEquals(2, out.size)
        assertEquals(3, out[0].size)
        assertTrue("折行片段要并回上一行：${out[0]}", out[0][2].contains("rt3"))
        assertTrue("原文片段不能丢：${out[0]}", out[0][1].contains("\\sq"))
    }

    @Test
    fun `间距命令粘字母要拆开`() {
        // 真机实录：`…\quadA=1` 被 jlatexmath 读成未知命令 quadA → 整块回退裸文本
        val segs = splitMathSegments("\$\$a=1\\quadA=2\$\$")
        val math = segs.filterIsInstance<MathSeg.Math>().firstOrNull()
        assertTrue("必须是公式段：$segs", math != null)
        assertTrue("\\quad 后必须补空格：${math!!.latex}", math.latex.contains("\\quad A"))
    }

    @Test
    fun `text英文夹中文按源码顺序行内混排`() {
        // 真机截图：`\text{第 }i\text{ 行、第 }j\text{ 列的元素}` 剥标签只留 CJK
        // → 用户看到「第 · 行、第 · 列的元」。v56.4 起整句按源码顺序行内混排：
        // 「a_ij = 第 i 行、第 j 列的元素」——字母 i/j 与中文都在原位，不再有「 · 」拼接。
        val segs = splitMathSegments("\$\$a_{ij}=\\text{第 }i\\text{ 行、第 }j\\text{ 列的元素}\$\$")
        val texts = segs.joinToString("") { if (it is MathSeg.Math) it.latex else (it as MathSeg.Txt).text }
        assertTrue("变量 i 丢了：$texts", texts.contains("i"))
        assertTrue("变量 j 丢了：$texts", texts.contains("j"))
        assertTrue("中文丢了：$texts", texts.contains("行") && texts.contains("列的元素"))
        assertFalse("又出现「第 · 行」拼接：$texts", texts.contains("第 · 行"))
        assertTrue("混排的数学段必须 display=false", segs.filterIsInstance<MathSeg.Math>().none { it.display })
    }

    @Test
    fun `删除线跨公式段不得裸露标记`() {
        // 真机截图：`~~"因为 $a_n\to\sqrt5$…"~~（…）` 的 ~~ 跨公式段，各段独立解析配对失败
        val src = java.io.File("app/src/main/java/io/github/m0use001/planability/ui/notes/MarkdownMath.kt")
            .let { if (it.exists()) it else java.io.File("src/main/java/io/github/m0use001/planability/ui/notes/MarkdownMath.kt") }
            .readText()
        // 2026-11-20 起跨段跟踪统一改名 InlineMarkTracker/appendMarkAware（扩了单 * 斜体跨段，
        // 用户截图 `*（$\Delta x$ …）*` 星号裸显）；~~ 跨段延续语义不变，钉新名字。
        assertTrue("退格删除线必须跨段跟踪（appendMarkAware）", src.contains("appendMarkAware"))
    }

    @Test
    fun `计划清单必须走行内富文本`() {
        val f = java.io.File("app/src/main/java/io/github/m0use001/planability/ui/info/ChatWorkflowSheet.kt")
        val src = if (f.exists()) f.readText()
        else java.io.File("src/main/java/io/github/m0use001/planability/ui/info/ChatWorkflowSheet.kt").readText()
        assertTrue("计划项必须用 MdInlineText（否则 **加粗** 与公式裸露）", src.contains("MdInlineText("))
    }
}
