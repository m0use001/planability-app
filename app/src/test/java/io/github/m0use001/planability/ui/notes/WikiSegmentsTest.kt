package io.github.m0use001.planability.ui.notes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v53.6 第 1 期「链接可走」：正文按 `[[…]]` 切片（渲染用）。
 *
 * 与 WikiLinks.parse 的分工：parse 是**去重建边**（同一标题一篇只留一条），
 * 这里是**逐处渲染**（同一标题出现三次要画三个胶囊）。口径必须一致，否则会出现
 * 「反链里有、正文点不了」这类只在用户手上才暴露的错位——所以边界逐条钉住。
 */
class WikiSegmentsTest {

    private fun links(s: String) = splitWikiSegments(s).filterIsInstance<WikiSeg.Link>()
    private fun texts(s: String) = splitWikiSegments(s).filterIsInstance<WikiSeg.Txt>().map { it.text }

    @Test
    fun plainTextStaysOneSegment() {
        val segs = splitWikiSegments("没有链接的一行")
        assertEquals(1, segs.size)
        assertTrue(segs[0] is WikiSeg.Txt)
    }

    @Test
    fun splitsIntoTextAndLinks() {
        // 文本/链接交替：看·[线代]· 和 ·[概率论]· 两门 = 5 段
        val segs = splitWikiSegments("看 [[线性代数]] 和 [[概率论]] 两门")
        assertEquals(5, segs.size)
        assertEquals(listOf("看 ", " 和 ", " 两门"), texts("看 [[线性代数]] 和 [[概率论]] 两门"))
        assertEquals(listOf("线性代数", "概率论"), links("看 [[线性代数]] 和 [[概率论]] 两门").map { it.target })
    }

    /** 去重是建图的口径，不是渲染的口径：同一标题出现三次就得画三个可点胶囊。 */
    @Test
    fun repeatedLinkRendersEachOccurrence() {
        assertEquals(3, links("[[甲]] [[甲]] [[甲]]").size)
    }

    @Test
    fun aliasAndAnchor() {
        val l = links("[[微积分#第二章|范围]]").single()
        assertEquals("微积分", l.target)
        assertEquals("第二章", l.anchor)
        assertEquals("范围", l.label)
    }

    @Test
    fun labelFallsBackToTitle() {
        assertEquals("微积分", links("[[微积分#第二章]]").single().label)
    }

    @Test
    fun unclosedOrEmptyBracketsStayText() {
        assertEquals(0, links("[[没闭合").size)
        assertEquals(0, links("[[]]").size)
        assertEquals(0, links("[[   ]]").size)
        assertEquals(0, links("[[#只有锚点]]").size)
    }

    /** 行内码里的 `[[x]]` 是示例代码，不该变成可点胶囊。 */
    @Test
    fun linkInsideInlineCodeIsNotRendered() {
        assertEquals(0, links("写法是 `[[标题]]` 这样").size)
        // 反引号闭合之后的行内码外链接仍要渲染
        assertEquals(1, links("`[[示例]]` 然后 [[真链接]]").size)
    }

    @Test
    fun overlongTitleIsPlainText() {
        val long = "长".repeat(121)
        assertEquals(0, links("[[$long]]").size)
    }
}
