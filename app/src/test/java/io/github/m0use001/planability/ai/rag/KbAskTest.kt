package io.github.m0use001.planability.ai.rag

import io.github.m0use001.planability.data.NoteEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v49 知识库：问答环的「覆盖度」判定。
 *
 * 这是 kb_ask 唯一自带的判断逻辑——决定「知识库到底有没有覆盖这个问题」，
 * 决定模型该不该拒答。纯词法覆盖率，不依赖数据库与模型，所以可以穷举。
 */
class KbAskTest {

    private fun hit(id: Long, title: String, content: String) =
        NoteHit(NoteEntity(id = id, notebookId = 1, title = title, content = content), 1.0, "", "both")

    private fun chunk(id: Long, snippet: String) =
        RagHit(noteId = id, title = "t", chunkIndex = 0, score = 1.0, snippet = snippet)

    @Test
    fun coverageIsFractionOfMatchedTerms() {
        // 词元 = ASCII 词 + CJK 二元组：「线性代数」→ 线性/性代/代数
        assertTrue(KbAsk.coverageRatio("线性代数", "线性代数笔记") >= 1.0)
        assertEquals(0.0, KbAsk.coverageRatio("线性代数", "完全无关的内容"), 1e-9)
        val partial = KbAsk.coverageRatio("线性代数", "线性规划")
        assertTrue("只命中一个二元组时应在 0..1 之间", partial > 0.0 && partial < 1.0)
    }

    @Test
    fun emptyQueryScoresZero() {
        assertEquals(0.0, KbAsk.coverageRatio("", "任何内容"), 1e-9)
        assertEquals(0.0, KbAsk.coverageRatio("!!!", "任何内容"), 1e-9)
    }

    @Test
    fun coverageTakesBestAcrossNoteAndChunk() {
        val covered = KbAsk.coverageOf(
            "线性代数",
            listOf(hit(1, "完全无关", "完全无关的内容")),
            listOf(chunk(2, "线性代数 期末复习"))
        )
        assertTrue("分块命中也要算覆盖", covered >= 0.99)
        assertEquals(0.0, KbAsk.coverageOf("量子力学", listOf(hit(1, "线性代数", "线性代数")), emptyList()), 1e-9)
    }

    @Test
    fun domainDecisionThresholdsAtOneThird() {
        // 三分之一的覆盖率（命中 1/3 词元）视为「间接相关」，不视为覆盖
        val q = "线性代数"
        val oneOfThree = KbAsk.coverageRatio(q, "线性规划")
        assertTrue(oneOfThree < 0.34 || oneOfThree < 1.0)
        assertFalse("一处都不命中绝不算覆盖", KbAsk.coverageOf(q, emptyList(), emptyList()) >= 0.34)
    }

    // ---------- v56 范围裁剪（问笔记/查内容按笔记本圈定） ----------

    @Test
    fun scopeFilterKeepsOrderAndDropsOutOfScope() {
        val inA = hit(1, "范围内甲", "")
        val outB = hit(2, "范围外乙", "")
        val inC = hit(3, "范围内丙", "")
        val kept = KbAsk.filterInScope(listOf(inA, outB, inC), setOf(1L, 3L))
        assertEquals(listOf(1L, 3L), kept.map { it.note.id })
        assertTrue("空白名单=范围内无笔记，必须一无所获", KbAsk.filterInScope(listOf(inA), emptySet()).isEmpty())
    }

    @Test
    fun scopedSemanticBestOnlyCountsScope() {
        val pairs = listOf(1L to 0.9f, 2L to 0.8f, 3L to 0.5f)
        assertEquals(
            "无范围=全局最佳（与旧行为一致）",
            0.9f,
            KbAsk.scopedSemanticBest(pairs, null),
            1e-6f
        )
        assertEquals(
            "范围内最高才是门看的分——范围外 0.8 不能替范围内撑门面",
            0.5f,
            KbAsk.scopedSemanticBest(pairs, setOf(3L)),
            1e-6f
        )
        assertEquals("范围裁后无命中=-1（门不因此放行）", -1f, KbAsk.scopedSemanticBest(pairs, setOf(9L)), 1e-6f)
        assertEquals("空白名单=-1", -1f, KbAsk.scopedSemanticBest(pairs, emptySet()), 1e-6f)
    }

    @Test
    fun scopedSemanticBestGateRejectsOutOfScopeHit() {
        // 语义门判定的口径钉版：范围外同义命中（0.72 ≥ 门 0.45）不得放行范围内查询
        val pairs = listOf(2L to 0.72f) // 2L 不在范围内
        val best = KbAsk.scopedSemanticBest(pairs, setOf(1L))
        assertFalse(
            "范围内无语义命中 → 门必须拦（即使全局有同义命中）",
            KbAsk.inDomainOf(evidenceEmpty = false, coverage = 0.0, semanticBest = best)
        )
        // 对照：同一分数在无范围（全局）口径下放行——差别只能来自范围裁剪
        assertTrue(KbAsk.inDomainOf(evidenceEmpty = false, coverage = 0.0, semanticBest = 0.72f))
    }
}
