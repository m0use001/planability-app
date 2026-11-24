package io.github.m0use001.planability.ai.rag

import io.github.m0use001.planability.data.NoteEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v49 知识库：双路 FTS 的 RRF 融合与排序权重。
 *
 * 钉死三件事：
 * 1. 只在单路命中的笔记**不许丢**（标题沾边也是命中）；
 * 2. 两路都命中 > 只中一路（RRF 分数相加，名次相同）；
 * 3. 标题路权重更高（0.72）：同名词条出现在标题比出现在正文更能说明「这篇是关于什么的」。
 */
class NoteSearchTest {

    private fun note(id: Long, title: String, content: String = "", pinned: Boolean = false, updatedAt: Long = 0) =
        NoteEntity(id = id, notebookId = 1, title = title, content = content, pinned = pinned, updatedAt = updatedAt)

    @Test
    fun singleRouteHitsSurvive() {
        val fused = NoteSearch.fuse(
            title = listOf(note(1, "线性代数")),
            body = listOf(note(2, "杂记", "线性代数 笔记"))
        )
        assertEquals(setOf(1L, 2L), fused.map { it.id }.toSet())
    }

    @Test
    fun bothRoutesBeatSingleRoute() {
        val both = note(1, "线性代数", "线性代数")
        val onlyTitle = note(2, "线性代数")
        val fused = NoteSearch.fuse(listOf(both, onlyTitle), listOf(both))
        assertEquals(listOf(1L, 2L), fused.map { it.id })
    }

    @Test
    fun titleRouteOutweighsBodyRoute() {
        val titleHit = note(1, "线性代数")
        val bodyHit = note(2, "杂记", "线性代数")
        val fused = NoteSearch.fuse(listOf(titleHit), listOf(bodyHit))
        assertEquals("标题路应排在正文路前", listOf(1L, 2L), fused.map { it.id })
    }

    @Test
    fun emptyRouteReturnsOther() {
        val only = listOf(note(1, "a"))
        assertEquals(only, NoteSearch.fuse(emptyList(), only))
        assertEquals(only, NoteSearch.fuse(only, emptyList()))
    }

    // ---------- v51 语义第三路 ----------

    @Test
    fun vectorOnlyHitJoinsResults() {
        val lexical = note(1, "线性代数")
        val semantic = note(2, "矩阵笔记", "这道题要用特征值")
        val merged = NoteSearch.fuseVector(listOf(lexical), listOf(semantic))
        assertEquals(setOf(1L, 2L), merged.map { it.id }.toSet())
    }

    @Test
    fun emptyVectorKeepsLexicalOrder() {
        val lexical = listOf(note(1, "a"), note(2, "b"))
        assertEquals(lexical, NoteSearch.fuseVector(lexical, emptyList()))
    }

    @Test
    fun bothRoutesOutrankVectorOnlyMayVaryButNotDrop() {
        // 词法第一名 + 语义第三名同一条：分数叠加，不许被挤掉
        val both = note(1, "线性代数")
        val merged = NoteSearch.fuseVector(
            listOf(both, note(2, "其它")),
            listOf(note(3, "语义甲"), note(4, "语义乙"), both)
        )
        assertTrue("两路都命中的必须在前列", merged.indexOfFirst { it.id == 1L } <= 1)
        assertEquals(4, merged.size)
    }

    @Test
    fun rankRewardsFresherAndPinned() {
        val now = 1_700_000_000_000L
        val fresh = NoteSearch.rankScore(note(1, "a", updatedAt = now), rank = 0, now = now)
        val old = NoteSearch.rankScore(note(2, "a", updatedAt = now - 365L * 86_400_000), rank = 0, now = now)
        val pinned = NoteSearch.rankScore(note(3, "a", pinned = true, updatedAt = now), rank = 0, now = now)
        assertTrue("新笔记应比一年前的高", fresh > old)
        assertTrue("置顶应加成", pinned > fresh)
    }

    @Test
    fun rankDecaysWithPosition() {
        val now = 1_700_000_000_000L
        val first = NoteSearch.rankScore(note(1, "a", updatedAt = now), 0, now)
        val fifth = NoteSearch.rankScore(note(1, "a", updatedAt = now), 5, now)
        assertTrue(first > fifth)
    }

    @Test
    fun snippetCentersOnTerm() {
        val body = "前言".repeat(100) + "目标概念" + "后文".repeat(100)
        val s = NoteSearch.snippet(note(1, "t", body), listOf("目标概念"))
        assertTrue(s.contains("目标概念"))
        assertTrue("截取片段应带省略号", s.startsWith("…") && s.endsWith("…"))
        assertTrue("片段不应长于全文", s.length < body.length)
    }

    @Test
    fun snippetFallsBackToHeadOrTitle() {
        val s = NoteSearch.snippet(note(1, "t", "第一行\n第二行"), listOf("不存在的词"))
        assertTrue(s.startsWith("第一行"))
        assertEquals("空标题", NoteSearch.snippet(note(1, "空标题", ""), listOf("x")))
    }
}
