package io.github.m0use001.planability.ui.notes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * v53.9 `[[笔记#小节]]` 定位。
 *
 * 这里的偏移会被直接塞进 `TextRange`，所以每一条都关系到「会不会跳到半个字里」：
 * 缩进标题、多字节中文、CRLF 混入、层级不参与匹配、半截标题兜底。
 */
class NoteAnchorTest {

    @Test
    fun offsetPointsAtLineStart() {
        val doc = "# 总览\n正文\n## 结果\n数据"
        val off = NoteAnchor.findOffset(doc, "结果")!!
        assertEquals(doc.indexOf("## 结果"), off)
    }

    @Test
    fun levelAndCaseDoNotMatter() {
        val doc = "## 结果\n### RESULT"
        assertEquals(0, NoteAnchor.findOffset(doc, "结果"))
        assertEquals(doc.indexOf("### RESULT"), NoteAnchor.findOffset(doc, "result"))
    }

    @Test
    fun anchorMayCarryHashes() {
        val doc = "## 结果\n"
        assertEquals(0, NoteAnchor.findOffset(doc, "### 结果"))
    }

    @Test
    fun indentedHeadingOffsetsToHeadingText() {
        val doc = "   ## 缩进标题\n"
        // 偏移落在 `##` 上而不是行首空格（否则光标会停在缩进里）
        assertEquals(3, NoteAnchor.findOffset(doc, "缩进标题"))
    }

    @Test
    fun multiByteContentOffsetsAreCharBased() {
        val doc = "中文标题一\n\n## 目标小节\n"
        val off = NoteAnchor.findOffset(doc, "目标小节")!!
        assertEquals(doc.indexOf("## 目标小节"), off)
        // 用偏移取子串应当正好是标题行
        assertEquals("## 目标小节", doc.substring(off).substringBefore('\n'))
    }

    @Test
    fun firstMatchWins() {
        val doc = "## 重复\n中间\n## 重复"
        assertEquals(0, NoteAnchor.findOffset(doc, "重复"))
    }

    @Test
    fun partialTitleFallsBackToContains() {
        val doc = "## 第二章 定积分\n"
        assertEquals(0, NoteAnchor.findOffset(doc, "定积分"))
    }

    @Test
    fun missingAnchorIsNull() {
        assertNull(NoteAnchor.findOffset("# 只有这节\n", "根本没有"))
        assertNull(NoteAnchor.findOffset("没有标题\n", "任意"))
        assertNull(NoteAnchor.findOffset("## 有标题\n", "  "))
    }

    @Test
    fun hashOnlyAnchorIsIgnored() {
        assertNull(NoteAnchor.findOffset("## 结果\n", "#"))
    }
}
