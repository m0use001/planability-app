package io.github.m0use001.planability.ai.rag

import io.github.m0use001.planability.ai.rag.KbAsk.inDomainOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v53 分块级向量重排的**纯函数断言**（融合/门判定；向量推理本身走真机）。
 *
 * 被钉住的真机事故 / 设计决策：
 * - 「零词元重叠的真命中」：BM25 给不出名次的块，靠语义门旁路进 inDomain；
 * - RRF 二次融合的权重取 0.5/0.5：chunk 片段短、向量噪声比整篇笔记大，
 *   纯余弦排序会淹掉 BM25 的精确命中（用户搜确切词的场景）。
 */
class KbRerankTest {

    private fun hit(noteId: Long, chunk: Int = 0, title: String = "笔记$noteId") =
        RagHit(noteId, title, chunk, 1.0, "片段")

    // ------------------------------------------------------------------ RRF 融合

    @Test
    fun `余弦冠军即使词法垫底也显著上升`() {
        // 场景：D 词面最弱（BM25 第 4）但语义最贴（余弦第 1）。
        // RRF k=60 对位置差不敏感——D 不会跳到第 1（A 两路都靠前），但应从第 4
        // 升到第 2：这就是「语义信号参与排序」的可观测性质。
        val bm25 = listOf(hit(1), hit(2), hit(3), hit(4))
        val byCos = listOf(hit(4), hit(1), hit(2), hit(3))
        val out = KbRerank.fuseChunkCosine(bm25, byCos)
        assertEquals(1L, out.first().noteId)
        assertEquals("余弦冠军应升到第 2", 4L, out[1].noteId)
    }

    @Test
    fun `两路名次完全一致时顺序不变`() {
        val bm25 = listOf(hit(3), hit(1), hit(2))
        val out = KbRerank.fuseChunkCosine(bm25, bm25)
        assertEquals(listOf(3L, 1L, 2L), out.map { it.noteId })
    }

    @Test
    fun `余弦路为空时原样返回词法排序`() {
        val bm25 = listOf(hit(1), hit(2))
        val out = KbRerank.fuseChunkCosine(bm25, emptyList())
        assertEquals(bm25, out)
    }

    @Test
    fun `只在余弦路出现的块也参与融合`() {
        // 防御：两路候选集不一致时（上游过滤差异），单路块不得被丢弃
        val bm25 = listOf(hit(1), hit(2))
        val byCos = listOf(hit(2), hit(9))
        val out = KbRerank.fuseChunkCosine(bm25, byCos)
        assertEquals(setOf(1L, 2L, 9L), out.map { it.noteId }.toSet())
    }

    @Test
    fun `同 note 不同分块是不同候选`() {
        val bm25 = listOf(hit(1, 0), hit(1, 1))
        val byCos = listOf(hit(1, 1), hit(1, 0))
        val out = KbRerank.fuseChunkCosine(bm25, byCos)
        assertEquals(2, out.size)
        assertEquals(setOf(0, 1), out.map { it.chunkIndex }.toSet())
    }

    // ------------------------------------------------------------------ 重排输入

    @Test
    fun `重排文本为标题拼片段且截断到上限`() {
        val long = "字".repeat(500)
        val t = KbRerank.rerankText(RagHit(1, "标题", 0, 1.0, long))
        assertTrue("应含标题", t.startsWith("标题\n"))
        val body = t.removePrefix("标题\n")
        assertTrue("片段应截断到 240 字内（标题不计入）", body.length <= KbRerank.RERANK_CHARS)
    }

    // ------------------------------------------------------------------ 语义门

    @Test
    fun `词面覆盖不足但语义达标时放行`() {
        // 场景：中文查询查英文笔记——词面覆盖 0，余弦 0.5 达标 → inDomain
        assertTrue(inDomainOf(evidenceEmpty = false, coverage = 0.0, semanticBest = 0.5f))
    }

    @Test
    fun `词面达标单独放行 语义不足不拖后腿`() {
        assertTrue(inDomainOf(false, coverage = 0.5, semanticBest = 0.2f))
    }

    @Test
    fun `两路都不达标则拒答`() {
        assertFalse(inDomainOf(false, coverage = 0.2, semanticBest = 0.3f))
    }

    @Test
    fun `没有证据永远拒答`() {
        assertFalse(inDomainOf(evidenceEmpty = true, coverage = 0.9, semanticBest = 0.9f))
    }

    @Test
    fun `语义门阈值在实测同义与无关之间`() {
        // bge-small-zh v52 采样：同义 ≈0.72 / 无关 ≈0.24
        assertTrue(
            "门 0.45 应落在 (0.24, 0.72) 内",
            KbAsk.SEMANTIC_GATE in 0.24f..0.72f
        )
    }
}
