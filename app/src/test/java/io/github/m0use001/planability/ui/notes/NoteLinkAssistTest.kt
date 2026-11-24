package io.github.m0use001.planability.ui.notes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * v49 知识库：`[[` 补全的触发判定。
 *
 * 这决定了键盘上那条补全什么时候弹——弹错比不弹更烦（正常打字时条子乱闪），
 * 所以边界写得细：跨行不弹、已闭合不弹、别名段不弹、`[[a[[b` 不弹。
 */
class NoteLinkAssistTest {

    @Test
    fun justTypedOpenBracketsShowsAllCandidates() {
        val q = linkQueryAt("看 [[", 4)
        assertNotNull(q)
        assertEquals(2, q!!.start)
        assertEquals("", q.query)
    }

    @Test
    fun partialTitleIsTheQuery() {
        val text = "见 [[线性代"
        val q = linkQueryAt(text, text.length)
        assertEquals("线性代", q!!.query)
        assertEquals(2, q!!.start)
    }

    @Test
    fun closedLinkDoesNotTrigger() {
        assertNull(linkQueryAt("见 [[线性代数]] 之后", "见 [[线性代数]] ".length))
    }

    @Test
    fun cursorOutsideLinkDoesNotTrigger() {
        val text = "[[线性代数]] 后面再写"
        assertNull(linkQueryAt(text, text.length))
    }

    @Test
    fun newlineBreaksLink() {
        assertNull(linkQueryAt("[[线性\n代数", 7))
    }

    @Test
    fun nestedBracketBreaksLink() {
        // 单个 [ 夹在中间：不是合法链接，不弹
        assertNull(linkQueryAt("[[a[b", 5))
        // 连续 [[ 视为最近一个是对的（与 multipleLinksUsesNearestOpen 同规则）
        val q = linkQueryAt("[[a[[b", 6)
        assertEquals("b", q!!.query)
        assertEquals(3, q.start)
    }

    @Test
    fun aliasSectionStopsCompletion() {
        assertNull(linkQueryAt("[[线性代数|那门", 8))
    }

    @Test
    fun cursorAtStartOrEmptyIsNull() {
        assertNull(linkQueryAt("", 0))
        assertNull(linkQueryAt("[", 1))
        assertNull(linkQueryAt("[[", 0))
    }

    @Test
    fun multipleLinksUsesNearestOpen() {
        val text = "[[旧]] 新 [[新目标"
        val q = linkQueryAt(text, text.length)
        assertEquals("新目标", q!!.query)
        assertEquals(text.lastIndexOf("[["), q.start)
    }
}
