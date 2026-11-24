package io.github.m0use001.planability.ui.notes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v54.2 编辑态标记弱化：只着色、不改字。
 *
 * 这类功能最容易出的 bug 不是颜色错，而是**区间越界/重叠**导致渲染崩或高亮串位，
 * 所以每条都断言区间在文本内、且 isMarker 判定符合直觉。
 */
class MarkdownMarkersTest {

    private fun marks(text: String): List<String> =
        MarkdownMarkers.ranges(text).map { text.substring(it.first, it.last + 1) }

    @Test
    fun headingHashesAreMarked() {
        assertEquals(listOf("#"), marks("# 标题"))
        assertEquals(listOf("##"), marks("## 小节"))
        assertEquals(listOf("######"), marks("###### 六级"))
    }

    @Test
    fun listQuotesAndOrderedAreMarked() {
        assertEquals(listOf("-"), marks("- 条目"))
        assertEquals(listOf(">"), marks("> 引用"))
        assertEquals(listOf("1."), marks("1. 第一步"))
        assertEquals(listOf("[ ]"), marks("[ ] 待办"))
        assertEquals(listOf("[x]"), marks("[x] 完成"))
    }

    @Test
    fun codeFenceWholeTokenIsMarked() {
        assertEquals(listOf("```"), marks("```"))
        assertEquals(listOf("```kotlin"), marks("```kotlin"))
    }

    @Test
    fun inlineMarkersAreMarked() {
        assertEquals(listOf("**", "**"), marks("**粗**"))
        assertEquals(listOf("`", "`"), marks("`code`"))
        assertEquals(listOf("[[", "]]"), marks("[[链接]]"))
    }

    @Test
    fun indentedMarkersOffsetIsCorrect() {
        // 缩进后标记仍应标在 `-` 上，而不是行首空白
        val text = "    - 缩进条目"
        val r = MarkdownMarkers.ranges(text).first { text.substring(it) .startsWith("-") }
        assertEquals(4, r.first)
    }

    @Test
    fun plainTextHasNoMarks() {
        assertTrue(MarkdownMarkers.ranges("就是一句话，没有标记").isEmpty())
    }

    @Test
    fun allRangesAreInBoundsAndNonEmpty() {
        val text = "# T\n\n- a\n> b\n**c** `d` [[e]]\n```\ncode\n```\n"
        MarkdownMarkers.ranges(text).forEach { r ->
            assertTrue("区间越界: $r", r.first >= 0 && r.last < text.length)
            assertTrue("空区间: $r", r.first <= r.last)
        }
    }

    @Test
    fun emptyTextIsSafe() {
        assertTrue(MarkdownMarkers.ranges("").isEmpty())
    }
}
