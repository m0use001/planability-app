package io.github.m0use001.planability.ui.notes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v54.0 全库搜索的命中高亮分段。
 *
 * 唯一真正容易错的地方是「怎么切」：切错会丢字（拼回去对不上原文）或把中文切碎
 * （满屏零碎高亮块）。所以最重的一条断言是**拼回去必须等于原文**。
 */
class SearchHighlightTest {

    private fun join(segs: List<Pair<String, Boolean>>) = segs.joinToString("") { it.first }

    @Test
    fun noNeedlesIsAllPlain() {
        val segs = SearchHighlight.segments("误差曲线要重新画", emptyList())
        assertEquals(1, segs.size)
        assertEquals(false, segs[0].second)
        assertEquals("误差曲线要重新画", join(segs))
    }

    @Test
    fun singleHitSplitsIntoThree() {
        val segs = SearchHighlight.segments("实验误差偏大", listOf("误差"))
        assertEquals(3, segs.size)
        assertEquals("实验" to false, segs[0])
        assertEquals("误差" to true, segs[1])
        assertEquals("偏大" to false, segs[2])
    }

    @Test
    fun reconstructionAlwaysEqualsSource() {
        val samples = listOf(
            "实验误差曲线和误差分析",
            "Kotlin 与 kotlin 都算",
            " 前后空格 在 ",
            "no-match-here",
            "误差误差误差"
        )
        val needles = listOf("误差", "kotlin", "空格")
        samples.forEach { s ->
            assertEquals("分段必须无损：$s", s, join(SearchHighlight.segments(s, needles)))
        }
    }

    @Test
    fun caseInsensitive() {
        val segs = SearchHighlight.segments("Learning Kotlin now", listOf("kotlin"))
        assertTrue(segs.any { it.second && it.first == "Kotlin" })
        assertEquals("Learning Kotlin now", join(segs))
    }

    @Test
    fun longestNeedleWins() {
        // 「误差曲线」在时不该被「误差」切成两块
        val segs = SearchHighlight.segments("误差曲线回来了", listOf("误差", "误差曲线"))
        assertEquals("误差曲线" to true, segs[0])
        assertEquals("误差曲线回来了", join(segs))
    }

    @Test
    fun adjacentHitsMergeIntoOneBlock() {
        val segs = SearchHighlight.segments("误差误差", listOf("误差"))
        assertEquals(1, segs.size)
        assertEquals("误差误差" to true, segs[0])
    }

    @Test
    fun singleCharNeedlesAreIgnored() {
        // 单字命中率太高，整段都会亮
        val segs = SearchHighlight.segments("一二三", listOf("二"))
        assertEquals(1, segs.size)
        assertEquals(false, segs[0].second)
    }

    @Test
    fun repeatedHitsAreEachMarked() {
        val segs = SearchHighlight.segments("abc-abc", listOf("abc"))
        assertEquals(3, segs.size)
        assertTrue(segs[0].second)
        assertEquals("abc-abc", join(segs))
    }
}
