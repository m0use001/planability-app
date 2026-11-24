package io.github.m0use001.planability.ui.notes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 2026-11-20 用户「对话流又密又乱——尤其复杂场景（公式密集长答案）」批的守卫。
 *
 * 三条根因（真机截图 + JVM 探针实锤）：
 * ① 块级公式阶梯没有拆行档（LatexBlock 只 Fits/Scaled/Scroll），超宽 1.6 倍直接横滑——
 *    补「顶层逗号公式组」档（splitAtTopCommaGroups）与 unicode 关系符（⟹⇒→…）两处断点，
 *    LatexBlock 进 Scroll 档先试 gatherIfNeeded，拆得动就不横滑；
 * ② 单 * 斜体跨公式段配不上对 → 星号裸显（`*（$\Delta x$ 是负的…）*`，截图原句）；
 * ③ 行内公式基准 max(行,正文)×1.25 是 2026-11-16「太小」投诉抬的，双向钉死，本批不动。
 */
class DisplaySplitCrossItalTest {

    // ---------- ① 顶层逗号公式组 ----------

    @Test
    fun `逗号加空距是公式组断点`() {
        assertEquals(
            listOf("f(x) = x^{1/10},", "x_0 = 1024,"),
            splitAtTopCommaGroups("f(x) = x^{1/10}, \\qquad x_0 = 1024,")
        )
    }

    @Test
    fun `裸逗号与括号内逗号不拆`() {
        assertNull("集合记号 a, b ∈ S 不能被拆", splitAtTopCommaGroups("a, b \\in S"))
        assertNull("函数参数里的逗号不是断点", splitAtTopCommaGroups("f(x, y) = x + y"))
    }

    @Test
    fun `gatherIfNeeded 逗号组档优先于等号档`() {
        // 探针实锤：旧档序落等号档切成 3 条碎片（f(x) / = x^{1/10}, \qquad x_0 / = 1024,），
        // 等式的头尾互相粘连读不出
        assertEquals(
            "\\begin{gathered}f(x) = x^{1/10}, \\\\ x_0 = 1024,\\end{gathered}",
            gatherIfNeeded("f(x) = x^{1/10}, \\qquad x_0 = 1024,", 34f, 22f)
        )
    }

    // ---------- ① unicode 关系符 ----------

    @Test
    fun `unicode 箭头是顶层断点`() {
        assertEquals(listOf("a = b", "⟹ c = d"), splitAtTopRelations("a = b ⟹ c = d"))
        // 行首悬挂不带空格——与命令档（\implies）同一形态
        assertEquals(listOf("p", "⇒q"), splitAtTopRelations("p⇒q"))
    }

    @Test
    fun `unicode 箭头公式不再退等号档`() {
        // 探针实锤：⟹ 旧版认不出 → 等号档把 ⟹ 连同半行内容粘在超宽行里
        assertEquals(
            "\\begin{gathered}f'(x) = \\frac{1}{10}x^{-9/10} \\\\ ⟹ f'(1024)\\end{gathered}",
            gatherIfNeeded("f'(x) = \\frac{1}{10}x^{-9/10} ⟹ f'(1024)", 40f, 22f)
        )
    }

    // ---------- ② 跨段斜体 ----------

    @Test
    fun `截图句星号不裸显（跨公式段配对）`() {
        val mid = " 是负的，所以结果一定比 2 略小——先有这个预感，算完能自查。）"
        val tracker = InlineMarkTracker(listOf("*（", "$mid*"))
        val spans = tracker.spansOf("*（") + tracker.spansOf("$mid*")
        assertTrue("星号还在文本里裸显", spans.none { it.text.contains("*") })
        assertTrue("跨段斜体没接上", spans.all { it.ital })
    }

    @Test
    fun `裸乘号不吃斜体（奇数单星不配对）`() {
        val tracker = InlineMarkTracker(listOf("2*3=6"))
        val spans = tracker.spansOf("2*3=6")
        assertEquals(1, spans.size)
        assertFalse("2*3=6 被吃成斜体了", spans[0].ital)
        assertTrue("乘号被吃掉了", spans[0].text.contains("*"))
    }

    @Test
    fun `段内成对斜体不受影响`() {
        val tracker = InlineMarkTracker(listOf("前 *斜* 后"))
        val spans = tracker.spansOf("前 *斜* 后")
        assertEquals(3, spans.size)
        assertFalse(spans[0].ital)
        assertTrue(spans[1].ital)
        assertEquals("斜", spans[1].text)
        assertFalse(spans[2].ital)
    }

    @Test
    fun `双星旁的单星对由跟踪器正常配对`() {
        val tracker = InlineMarkTracker(listOf("**粗** 与 *斜*"))
        val spans = tracker.spansOf("**粗** 与 *斜*")
        // 单星计数 = 2（** 成对不计，尾部闭星计入）= 偶数 → 跟踪器配对；
        // ** 整体跳过留给 inlineMd 处理粗体
        assertEquals(2, spans.size)
        assertFalse(spans[0].ital)
        assertEquals("**粗** 与 ", spans[0].text)
        assertTrue(spans[1].ital)
        assertEquals("斜", spans[1].text)
    }

    @Test
    fun `删除线跨段延续不变（旧行为保护）`() {
        val tracker = InlineMarkTracker(listOf("~~因为 ", " 是", "极限~~（注）"))
        val spans = tracker.spansOf("~~因为 ") + tracker.spansOf(" 是") + tracker.spansOf("极限~~（注）")
        // 开标记后的内容都在划线态里（~~因为 是 极限~~），闭标记后恢复
        assertEquals(listOf(true, true, true, false), spans.map { it.strike })
    }

    // ---------- 源码级：块级拆行阶梯不许被拆掉 ----------

    @Test
    fun `LatexBlock 有拆行优先阶梯`() {
        val f = File("src/main/java/io/github/m0use001/planability/ui/notes/MarkdownMath.kt")
        assertTrue("找不到 MarkdownMath.kt（测试的工作目录不对？）", f.exists())
        val code = f.readText().lines()
            .filterNot { it.trimStart().startsWith("//") }.joinToString("\n")
        assertTrue(
            "LatexBlock 的 Scroll 档没接 gatherIfNeeded（块级拆行阶梯被拆了）",
            code.contains("val g = gatherIfNeeded(latex, naturalPx / fontPx, effPx / fontPx)")
        )
        assertTrue("LatexBlock 没有拆行重渲状态 gatheredForDisplay", code.contains("gatheredForDisplay"))
    }
}
