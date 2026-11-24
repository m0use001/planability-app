package io.github.m0use001.planability.ai.rag

import io.github.m0use001.planability.ai.embed.ModelStore
import io.github.m0use001.planability.ai.embed.NoteVectors
import io.github.m0use001.planability.data.MemoryRepo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * v49 知识库问答环（DeepSearcher 思路的端侧简化版）：
 *
 * 1. 第一轮：笔记级双路 FTS（[NoteSearch]）+ 分块 BM25（[RagIndex]）并发召回；
 * 2. 覆盖度不足时第二轮：拿首轮命中的标题关键词扩查询再召回一次（最多 2 轮）；
 * 3. 一跳链接扩展（wikilink 邻居）补上下文；
 * 4. 按覆盖度判定「知识库是否覆盖这个问题」——不覆盖就明确说不知道，
 *    而不是把边角料硬拼成答案（茴香豆式拒答）。
 *
 * **不在这里生成答案**：工具返回带编号的证据包，由主模型组织语言并标引用，
 * 避免一次工具调用再烧一次完整生成。
 */
object KbAsk {

    data class Evidence(
        val noteId: Long,
        val title: String,
        val snippet: String,
        /** 命中来源：标题 / 正文 / 分块 / 链接 */
        val source: String
    )

    data class Result(
        val inDomain: Boolean,
        val evidence: List<Evidence>,
        val queries: List<String>,
        /** 首轮最佳命中对查询词的覆盖率（0..1），用于诊断 */
        val coverage: Double
    )

    private const val SNIPPET_CHARS = 320

    /**
     * v56 范围参数：[notebookId] > 0 时只在该笔记本内检索（查笔记/查内容共用）。
     * 三路召回同步裁剪：笔记路召回后过滤、分块路按范围内笔记白名单裁剪、
     * 语义门的向量最佳分也只在范围内取——范围外再相关也不能替范围内撑门面，
     * 否则「问本本」会被全库的高分命中假性放行。
     */
    suspend fun ask(query: String, topK: Int = 6, tag: String = "", notebookId: Long = 0L): Result =
        withContext(Dispatchers.IO) {
            val q = query.trim()
            if (q.isEmpty()) return@withContext Result(false, emptyList(), emptyList(), 0.0)
            // 范围白名单只取 id（范围空=范围内无笔记，直接按无证据处理）
            val allowed: Set<Long>? = if (notebookId > 0L) {
                val ids = runCatching {
                    io.github.m0use001.planability.data.Graph.notes.notesOf(notebookId).map { it.id }
                }.getOrDefault(emptyList()).toSet()
                if (ids.isEmpty()) return@withContext Result(false, emptyList(), listOf(q), 0.0)
                ids
            } else null

            val rounds = mutableListOf(q)
            var (noteHits, chunkHits) = recall(q, topK, tag, allowed)

            // 第二轮：首轮覆盖不足，用命中标题里的实词扩容查询（不调模型，纯词法）
            if (coverageOf(q, noteHits, chunkHits) < 0.5) {
                val extra = expansionTerms(noteHits)
                if (extra.isNotEmpty()) {
                    val q2 = (q + " " + extra.joinToString(" ")).trim()
                    rounds += q2
                    val second = recall(q2, topK, tag, allowed)
                    noteHits = mergeNotes(noteHits, second.first)
                    chunkHits = mergeChunks(chunkHits, second.second)
                }
            }

            // v53 分块级向量重排（零新模型）：现有 bge 向量对 title+snippet 算余弦，
            // 与 BM25 名次 RRF 融合——补「零词元重叠的真命中」（含中→英跨语言）。
            // 模型没下载时原样返回。重排要在**两轮合并之后**：第二轮的块也参与排序。
            chunkHits = KbRerank.rerankChunks(q, chunkHits)
            // v53 语义门旁路：词面覆盖度对「换个说法的内容」失效（web_search 同款教训），
            // 向量路有 ≥VEC_GATE 的语义命中时，即使词面覆盖率不足也判 inDomain
            //（不覆盖就拒答的判定从纯词面升级为词面 ∪ 语义）。
            val semanticBest = semanticBestCosine(q, allowed)

            // 链接一跳扩展：只做 2 条，避免证据包膨胀
            val linkHits = if (noteHits.isNotEmpty()) {
                NoteSearch.expandOneHop(noteHits.take(2)).take(2)
            } else emptyList()

            val evidence = buildEvidence(noteHits, chunkHits, linkHits, topK)
            val best = coverageOf(q, noteHits, chunkHits)
            Result(
                inDomain = inDomainOf(evidence.isEmpty(), best, semanticBest),
                evidence = evidence,
                queries = rounds,
                coverage = maxOf(best, if (semanticBest >= SEMANTIC_GATE) 1.0 else 0.0)
            )
        }

    /**
     * 语义门阈值：bge-small-zh 实测同义 ≈0.72 / 无关 ≈0.24（v52 采样），
     * 0.45 取两者之间偏保守——宁漏过给主模型看，不误拒。
     */
    internal const val SEMANTIC_GATE = 0.45f

    /** inDomain 判定（纯函数）：词面覆盖率达标 **或** 语义余弦达标，且必须有证据 */
    internal fun inDomainOf(evidenceEmpty: Boolean, coverage: Double, semanticBest: Float): Boolean =
        !evidenceEmpty && (coverage >= 0.34 || semanticBest >= SEMANTIC_GATE)

    /** 查询的语义最佳余弦（笔记级向量路）；模型没下载返回 -1。[allowed] 非空时只在范围内取 */
    private suspend fun semanticBestCosine(query: String, allowed: Set<Long>? = null): Float {
        val ctx = io.github.m0use001.planability.data.Graph.appContext
        val model = ModelStore.selected(ctx)
        if (!NoteVectors.isAvailable(ctx, model)) return -1f
        return runCatching {
            io.github.m0use001.planability.ai.embed.NoteVectors
                .search(ctx, query, model, topK = if (allowed == null) 3 else 16)
                .let { pairs -> scopedSemanticBest(pairs, allowed) }
        }.getOrDefault(-1f)
    }

    private suspend fun recall(
        q: String,
        topK: Int,
        tag: String,
        allowed: Set<Long>? = null
    ): Pair<List<NoteHit>, List<RagHit>> {
        // 有范围时超量召回再裁剪：topK 是全局口径，过滤后不该缩水
        val notes = runCatching { NoteSearch.search(q, if (allowed != null) topK * 3 else topK, tag) }
            .getOrDefault(emptyList())
            .let { if (allowed == null) it else filterInScope(it, allowed) }
            .take(topK)
        // RagIndex 不做标签/笔记本过滤（chunk 表没有这两列）；有条件时按 note 集合裁剪。
        // 标签与范围叠加时取交集：标签在本范围内没有命中 → 块也全裁（与无标签命中同语义）
        val gate: Set<Long>? = when {
            allowed != null && tag.isNotBlank() -> allowed.intersect(notes.map { it.note.id }.toSet())
            allowed != null -> allowed
            tag.isNotBlank() -> notes.map { it.note.id }.toSet()
            else -> null
        }
        val chunks = runCatching { RagIndex.search(q, topK) }.getOrDefault(emptyList())
            .let { hits -> if (gate == null) hits else hits.filter { it.noteId in gate } }
        return notes to chunks
    }

    private fun expansionTerms(hits: List<NoteHit>): List<String> {
        val terms = hits.take(3).flatMap { MemoryRepo.tokenize(it.note.title, 4) }
            .distinct().filter { it.length >= 2 }
        return terms.take(4)
    }

    private fun mergeNotes(a: List<NoteHit>, b: List<NoteHit>): List<NoteHit> {
        val seen = a.map { it.note.id }.toMutableSet()
        return a + b.filter { seen.add(it.note.id) }
    }

    private fun mergeChunks(a: List<RagHit>, b: List<RagHit>): List<RagHit> {
        val seen = a.map { it.noteId to it.chunkIndex }.toMutableSet()
        return a + b.filter { seen.add(it.noteId to it.chunkIndex) }
    }

    private fun buildEvidence(
        notes: List<NoteHit>,
        chunks: List<RagHit>,
        links: List<NoteHit>,
        topK: Int
    ): List<Evidence> {
        val out = LinkedHashMap<Long, Evidence>()
        notes.forEach { h ->
            out.putIfAbsent(
                h.note.id,
                Evidence(h.note.id, h.note.title, clip(h.snippet), when (h.matchedBy) {
                    "title" -> "标题"
                    "body" -> "正文"
                    "both" -> "标题+正文"
                    "语义" -> "语义"
                    else -> "关键词"
                })
            )
        }
        chunks.forEach { c ->
            out.putIfAbsent(
                c.noteId,
                Evidence(c.noteId, c.title, clip(c.snippet), "分块 " + (c.chunkIndex + 1))
            )
        }
        links.forEach { h ->
            out.putIfAbsent(h.note.id, Evidence(h.note.id, h.note.title, clip(h.snippet), "链接"))
        }
        return out.values.take(topK)
    }

    private fun clip(s: String): String = if (s.length <= SNIPPET_CHARS) s else s.take(SNIPPET_CHARS) + "…"

    // ---------- 纯函数（单测覆盖） ----------

    /** 查询词在证据文本里的覆盖率：命中词元数 / 总词元数 */
    internal fun coverageRatio(query: String, text: String): Double {
        val terms = MemoryRepo.tokenize(query, 10)
        if (terms.isEmpty()) return 0.0
        val lower = text.lowercase()
        val hit = terms.count { lower.contains(it.lowercase()) }
        return hit.toDouble() / terms.size
    }

    /** 首轮召回的整体覆盖率：取笔记命中与分块命中的最大值 */
    internal fun coverageOf(query: String, notes: List<NoteHit>, chunks: List<RagHit>): Double {
        val noteBest = notes.take(3).maxOfOrNull { coverageRatio(query, it.note.title + "\n" + it.note.content.take(2000)) } ?: 0.0
        val chunkBest = chunks.take(3).maxOfOrNull { coverageRatio(query, it.snippet) } ?: 0.0
        return maxOf(noteBest, chunkBest)
    }

    /** 范围裁剪（纯函数）：按白名单过滤笔记命中，保序；空白名单=范围内无笔记→无命中 */
    internal fun filterInScope(hits: List<NoteHit>, allowed: Set<Long>): List<NoteHit> =
        if (allowed.isEmpty()) emptyList() else hits.filter { it.note.id in allowed }

    /** 范围内的语义最佳余弦（纯函数）：先裁剪再取最大；空白名单或裁后无命中 = -1（门不因此放行） */
    internal fun scopedSemanticBest(pairs: List<Pair<Long, Float>>, allowed: Set<Long>?): Float {
        if (allowed == null) return pairs.maxOfOrNull { it.second } ?: -1f
        if (allowed.isEmpty()) return -1f
        return pairs.filter { it.first in allowed }.maxOfOrNull { it.second } ?: -1f
    }
}

/**
 * kb_ask 文件库补扫的纯函数（2026-11-11「查错地方」修复，见 LifeTools.kbFileSweep）。
 * 拆出来是为了 JVM 单测不用碰磁盘与 Graph。
 */
internal object KbSweep {

    /** 返回内容中首个命中的查询词元（大小写不敏感）；无命中 null */
    internal fun firstHitTerm(content: String, terms: List<String>): String? {
        if (content.isEmpty()) return null
        val lower = content.lowercase()
        return terms.firstOrNull { it.length >= 2 && lower.contains(it.lowercase()) }
    }

    /** 命中词元前后各 [pad] 字的节选；越界截断，尾部补省略号 */
    internal fun snippetAround(content: String, term: String, pad: Int = 160): String {
        val pos = content.indexOf(term, ignoreCase = true)
        if (pos < 0) return content.take(pad)
        val start = (pos - pad).coerceAtLeast(0)
        val end = (pos + term.length + pad).coerceAtMost(content.length)
        return (if (start > 0) "…" else "") + content.substring(start, end) +
            (if (end < content.length) "…" else "")
    }
}
