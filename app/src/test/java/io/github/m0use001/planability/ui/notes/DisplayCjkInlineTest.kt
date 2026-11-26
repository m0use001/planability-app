package io.github.m0use001.planability.ui.notes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 2026-11-17 用户第三轮报「怎么还是中文被挤出」（图 1，真机会话 288 消息 6915 原文）：
 *
 * ```
 * $$\boxed{\det A=(-1)^{s}\prod_{k=1}^{n}a_{kk}^{(\text{主元})}}$$
 * ```
 *
 * 旧渲染：中文被抽成公式**下方**孤零零一行「主元」，公式里留一对空括号 `a_{kk}^{()}`。
 * 用户拍板「中文回到公式行内」→ 这块改走行内混排 + 括号再平衡：
 * `\det A=(-1)^{s}\prod_{k=1}^{n}a_{kk}` + `(主元)`，读起来就是 `det A=(−1)ˢ ∏ a_{kk} (主元)`。
 */
class DisplayCjkInlineTest {

    private fun segs(body: String) = splitMathSegments("$$$body$$")

    private fun joined(body: String): String = segs(body).joinToString("") {
        if (it is MathSeg.Math) it.latex else (it as MathSeg.Txt).text
    }

    @Test
    fun 用户实录_主元回到公式行内_不留空括号() {
        val body = """\boxed{\det A=(-1)^{s}\prod_{k=1}^{n}a_{kk}^{(\text{主元})}}"""
        val s = segs(body)
        val maths = s.filterIsInstance<MathSeg.Math>()
        assertTrue("应当切成「公式 + 中文」同段：$s", maths.isNotEmpty())
        assertTrue("公式段必须走行内（与中文同段）", maths.none { it.display })
        val txt = s.filterIsInstance<MathSeg.Txt>().joinToString("") { it.text }
        assertEquals("中文必须跟着括号回到行内", "(主元)", txt)
        val latex = maths.joinToString("|") { it.latex }
        assertFalse("不该再留空括号：$latex", latex.contains("{(") || latex.contains("()"))
        assertFalse("`\\boxed` 外框去掉了（中文要拆开，框没法跟）", latex.contains("boxed"))
        assertTrue("公式本体丢了：$latex", latex.contains("\\prod"))
        // 顺序：先公式后中文
        val firstTxtIdx = s.indexOfFirst { it is MathSeg.Txt }
        val lastMathIdx = s.indexOfLast { it is MathSeg.Math }
        assertTrue("中文必须在公式之后", firstTxtIdx > 0 && lastMathIdx < firstTxtIdx)
    }

    @Test
    fun underbrace标注留在花括号下_20261122拍板反转() {
        // 909b74f5 时代 jlatexmath 画不了 CJK，标注只能挂在公式下方；用户截图
        // 「文字飞了」后注册 CJK 外部字体，标注回到花括号正下方（不再抽标签）。
        val body = """f(x)-f(c)=\underbrace{\frac{f(x)-f(c)}{x-c}}_{\text{被 }f'(c)\text{ 控制}}\cdot\underbrace{(x-c)}_{\to 0}"""
        val m = segs(body).filterIsInstance<MathSeg.Math>().single()
        assertTrue("块级公式被拆散了", m.display)
        assertEquals("标注被剥走了：${m.labels}", "", m.labels)
        assertTrue("标注没留在公式里：${m.latex}", m.latex.contains("\\text{被 }"))
        assertTrue("数学本体丢了：${m.latex}", m.latex.contains("\\underbrace"))
    }

    @Test
    fun 不带括号的上标标注也回到行内() {
        val body = """x^{\text{ 主元 }}"""
        val txt = segs(body).filterIsInstance<MathSeg.Txt>().joinToString("") { it.text }
        assertTrue("标注没回到行内：$txt", txt.contains("主元"))
        val latex = segs(body).filterIsInstance<MathSeg.Math>().joinToString("|") { it.latex }
        assertFalse("残留空上标壳：$latex", latex.contains("^{"))
    }

    @Test
    fun 纯公式不受影响_仍是块级() {
        val m = segs("\\det A=(-1)^{s}").filterIsInstance<MathSeg.Math>().single()
        assertTrue("纯公式被降级成行内了", m.display)
        assertEquals("", m.labels)
    }
}
