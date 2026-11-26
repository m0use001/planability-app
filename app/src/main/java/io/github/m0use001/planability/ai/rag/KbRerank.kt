package io.github.m0use001.planability.ai.rag

import io.github.m0use001.planability.ai.embed.Embedder
import io.github.m0use001.planability.ai.embed.ModelStore
import io.github.m0use001.planability.ai.embed.NoteVectors
import io.github.m0use001.planability.data.Graph
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * v53 知识库**分块级向量重排**（端侧重排第一步，零新模型）。
 *
 * ## 为什么先做这一步
 * 可行性研究（◆/planability-ondevice-rerank-feasibility.md）的结论：
 * cross-encoder 重排（bge-reranker-base 265MB）收益真实但工程重（SentencePiece Unigram
 * 分词器 ~300 行 + 265MB 下载 + 1-3s 延迟）。第一步先用**现有的向量模型**当廉价替身：
 * 对分块命中的 title+snippet 现场算查询向量余弦，与 BM25 名次做 RRF 二次融合。
 * 8 条 × 5-15ms ≈ **60-120ms**，搜索框与 kb_ask 都扛得住。
 *
 * ## 语义重排补的是什么
 * BM25/FTS 是词面匹配——「零词元重叠的真命中」（换个说法写的内容）词法路永远给低分。
 * 向量余弦是语义分：中文查询 → 英文笔记这类跨语言命中，词法路完全瞎，只有它能看见。
 *
 * ## 边界
 * - 模型没下载 / 向量算不出 → 原样返回，词法排序兜底（与 NoteVectors 同一可选增强哲学）；
 * - 融合是 RRF（k=60）而不是拿余弦直接覆盖 BM25——词法精确命中不该被向量噪声淹掉。
 */
object KbRerank {

    /** 重排输入的文本上限（字符）：控制 seq 长度即控制延迟 */
    internal const val RERANK_CHARS = 240

    /**
     * 对分块命中做向量重排。返回重排后的列表（原 [hits] 的同一批元素，不增不减）。
     */
    suspend fun rerankChunks(query: String, hits: List<RagHit>): List<RagHit> =
        withContext(Dispatchers.IO) {
            if (hits.size < 2) return@withContext hits
            val ctx = Graph.appContext
            val model = ModelStore.selected(ctx)
            if (!NoteVectors.isAvailable(ctx, model)) return@withContext hits
            runCatching {
                val q = Embedder.embed(ctx, query, model, isQuery = true)
                    ?: return@withContext hits
                val scored = hits.map { h ->
                    val v = Embedder.embed(ctx, rerankText(h), model)
                    h to (v?.let { Embedder.cosine(q, it) } ?: Float.NEGATIVE_INFINITY)
                }
                val byCos = scored.sortedByDescending { it.second }.map { it.first }
                fuseChunkCosine(hits, byCos)
            }.getOrDefault(hits)
        }

    /** 重排输入：标题 + 片段（片段已含命中词上下文，足够判相关） */
    internal fun rerankText(h: RagHit): String {
        val body = h.snippet.take(RERANK_CHARS)
        return if (h.title.isBlank()) body else h.title + "\n" + body
    }

    /**
     * 纯函数：BM25 名次 × 余弦名次 的 RRF 二次融合（k=60，余弦路权重 0.5）。
     * 两路都是同一批块的不同排序；只在一路出现的块照常参与。
     * 余弦路权重取 0.5（不是 fuseVector 的 0.45/0.55 偏置）：chunk 片段短、
     * 向量噪声比整篇笔记大，不该压过 BM25 的精确命中。
     */
    internal fun fuseChunkCosine(
        bm25: List<RagHit>,
        byCos: List<RagHit>,
        k: Int = 60,
        cosW: Double = 0.5
    ): List<RagHit> {
        if (byCos.isEmpty()) return bm25
        val score = HashMap<String, Double>()
        val byKey = HashMap<String, RagHit>()
        fun key(h: RagHit) = h.noteId.toString() + ":" + h.chunkIndex
        bm25.forEachIndexed { i, h ->
            score.merge(key(h), (1 - cosW) / (k + i + 1), Double::plus)
            byKey[key(h)] = h
        }
        byCos.forEachIndexed { i, h ->
            score.merge(key(h), cosW / (k + i + 1), Double::plus)
            byKey.putIfAbsent(key(h), h)
        }
        return score.entries.sortedByDescending { it.value }.map { byKey.getValue(it.key) }
    }
}
