package io.github.m0use001.planability.ai.rag

import io.github.m0use001.planability.core.AppTime
import io.github.m0use001.planability.data.Graph
import io.github.m0use001.planability.data.MemoryRepo
import io.github.m0use001.planability.data.NoteEntity
import io.github.m0use001.planability.data.NoteRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.exp

/** 笔记检索命中：分数用于排序/展示，snippet 给模型当证据（带出处由调用方拼） */
data class NoteHit(
    val note: NoteEntity,
    val score: Double,
    val snippet: String,
    /** title / body / both / like */
    val matchedBy: String
)

/**
 * v49 知识库检索底座：标题路 + 正文路双路 FTS 召回，RRF 融合后按
 * 「相关度 + 新鲜度 + 置顶」加权排序。
 *
 * 与记忆侧 `MemoryRepo.recall` 同一套机制（ReMe/TencentDB 的 RRF k=60），
 * 区别只在评分项：笔记没有 priority/accessCount，改用 pinned；
 * 未建索引时 FTS 会抛异常（旧库/异常 DDL），这里统一 runCatching → LIKE 兜底。
 */
object NoteSearch {

    /** 记忆路同款分词：ASCII 词 + CJK 二元组（FTS4 不切中文） */
    private fun terms(q: String): List<String> = MemoryRepo.tokenize(q, 10)

    suspend fun search(query: String, topK: Int = 8, tag: String = ""): List<NoteHit> =
        withContext(Dispatchers.IO) {
            val q = query.trim()
            if (q.isEmpty()) return@withContext emptyList()
            val dao = Graph.db.noteKbDao()
            val ts = terms(q)

            val titleHits = LinkedHashMap<Long, NoteEntity>()
            val bodyHits = LinkedHashMap<Long, NoteEntity>()
            var usedLike = false
            if (ts.isEmpty()) {
                // 纯符号/单字符查询：FTS 无词可查，直接 LIKE
                dao.likeSearch(q).forEach { titleHits.putIfAbsent(it.id, it) }
                usedLike = true
            } else {
                ts.forEach { t ->
                    val th = runCatching { dao.ftsSearch("title:$t*") }.getOrDefault(emptyList())
                    val bh = runCatching { dao.ftsSearch("content:$t*") }.getOrDefault(emptyList())
                    if (th.isEmpty() && bh.isEmpty()) {
                        // CJK 退化：FTS 二元组匹配不到时按原串 LIKE
                        val like = runCatching { dao.likeSearch(t) }.getOrDefault(emptyList())
                        if (like.isNotEmpty()) usedLike = true
                        like.forEach { titleHits.putIfAbsent(it.id, it) }
                    } else {
                        th.forEach { titleHits.putIfAbsent(it.id, it) }
                        bh.forEach { bodyHits.putIfAbsent(it.id, it) }
                    }
                }
            }

            val fused = fuse(titleHits.values.toList(), bodyHits.values.toList())
            // v51/v52 语义第三路：当前模型下载了才有；没下载时 fuseVector 原样返回 base
            val embedModel = io.github.m0use001.planability.ai.embed.ModelStore.selected(Graph.appContext)
            val vectorHits = if (io.github.m0use001.planability.ai.embed.NoteVectors.isAvailable(Graph.appContext, embedModel)) {
                io.github.m0use001.planability.ai.embed.NoteVectors.search(Graph.appContext, q, embedModel, 12)
                    .mapNotNull { (id, _) -> Graph.notes.noteById(id) }
            } else emptyList()
            val merged = fuseVector(fused, vectorHits)
            val titleIds = titleHits.keys
            val bodyIds = bodyHits.keys
            val vectorIds = vectorHits.map { it.id }.toSet()
            val now = AppTime.nowMillis()
            val tagNeedle = tag.trim().lowercase()

            merged
                .filter { n ->
                    tagNeedle.isEmpty() || n.tags.split(',').any { it.trim().lowercase() == tagNeedle }
                }
                .mapIndexed { rank, n -> Triple(n, rank, rankScore(n, rank, now)) }
                .sortedByDescending { it.third }
                .take(topK)
                .map { (n, _, score) ->
                    NoteHit(
                        note = n,
                        score = score,
                        snippet = snippet(n, ts.ifEmpty { listOf(q) }),
                        matchedBy = when {
                            n.id in vectorIds && n.id !in titleIds && n.id !in bodyIds -> "语义"
                            usedLike && n.id in titleIds -> "like"
                            n.id in titleIds && n.id in bodyIds -> "both"
                            n.id in titleIds -> "title"
                            else -> "body"
                        }
                    )
                }
        }

    /**
     * 2026-11-07 P0-1 用户搜索框专用：笔记本内检索，返回列表行投影。
     *
     * 与 [search]（AI 工具路）的差异：不要 RRF/新鲜度权重——列表沿用「置顶 → 更新时间」
     * 的固定序（用户对列表位置的预期不变）；范围钉在本笔记本（FTS 里带 notebookId 条件，
     * 不像工具路先全局召回再过滤）；词元循环与 LIKE 兜底同款。上限由调用方给
     * （超出靠更具体的关键词，列表本来也翻不到那么多）。
     */
    suspend fun searchInNotebook(query: String, notebookId: Long, limit: Int = 200): List<NoteRow> =
        withContext(Dispatchers.IO) {
            val q = query.trim()
            if (q.isEmpty()) return@withContext emptyList()
            val dao = Graph.db.noteKbDao()
            val out = LinkedHashMap<Long, NoteRow>()
            val ts = terms(q)
            if (ts.isEmpty()) {
                dao.likeRowsInNotebook(q, notebookId, limit).forEach { out.putIfAbsent(it.id, it) }
            } else {
                ts.forEach { t ->
                    val rows = runCatching {
                        dao.ftsRowsInNotebook("title:$t*", notebookId, limit) +
                            dao.ftsRowsInNotebook("content:$t*", notebookId, limit)
                    }.getOrDefault(emptyList())
                    if (rows.isEmpty()) {
                        dao.likeRowsInNotebook(t, notebookId, limit).forEach { out.putIfAbsent(it.id, it) }
                    } else {
                        rows.forEach { out.putIfAbsent(it.id, it) }
                    }
                }
            }
            out.values.sortedWith(
                compareByDescending<NoteRow> { it.pinned }.thenByDescending { it.updatedAt }
            )
        }

    /** 命中的一跳扩展（出链 + 反链），给 kb_ask 补全上下文 */
    suspend fun expandOneHop(hits: List<NoteHit>): List<NoteHit> = withContext(Dispatchers.IO) {
        val dao = Graph.db.noteKbDao()
        val seen = hits.map { it.note.id }.toMutableSet()
        val extra = mutableListOf<NoteHit>()
        hits.forEach { h ->
            val ids = (dao.outLinks(h.note.id).map { it.toId } + dao.inLinks(h.note.id).map { it.fromId })
                .filter { it > 0 }.distinct()
            ids.forEach { id ->
                if (seen.add(id)) {
                    Graph.notes.noteById(id)?.let { n ->
                        extra += NoteHit(n, 0.0, snippet(n, emptyList()), "link")
                    }
                }
            }
        }
        extra
    }

    // ---------- 纯函数（单测覆盖） ----------

    /**
     * 双路 RRF：k=60 抑制名次差；标题路权重 0.72（笔记标题比正文更能说明「这篇讲什么」）。
     * 只在一路命中的条目照常参与排序，不会被丢弃。
     */
    internal fun fuse(
        title: List<NoteEntity>,
        body: List<NoteEntity>,
        k: Int = 60,
        titleWeight: Double = 0.72
    ): List<NoteEntity> {
        if (title.isEmpty()) return body
        if (body.isEmpty()) return title
        val score = HashMap<Long, Double>()
        val byId = HashMap<Long, NoteEntity>()
        title.forEachIndexed { i, n ->
            score.merge(n.id, titleWeight / (k + i + 1), Double::plus)
            byId[n.id] = n
        }
        body.forEachIndexed { i, n ->
            score.merge(n.id, (1 - titleWeight) / (k + i + 1), Double::plus)
            byId.putIfAbsent(n.id, n)
        }
        return score.entries.sortedByDescending { it.value }.map { byId.getValue(it.key) }
    }

    /**
     * 词法融合序 + 语义路 的二次 RRF。
     *
     * 语义路权重 0.45：一个纯语义命中（近义、换个说法）应该能挤进前列，但仍不如
     * 「标题直接命中」——用户搜一个确切的词时，FTS 的精确性不该被向量淹掉。
     * [vector] 为空时原样返回 [base]（模型没下载 / 没算出向量）。
     */
    internal fun fuseVector(
        base: List<NoteEntity>,
        vector: List<NoteEntity>,
        k: Int = 60,
        vectorWeight: Double = 0.45
    ): List<NoteEntity> {
        if (vector.isEmpty()) return base
        if (base.isEmpty()) return vector
        val score = HashMap<Long, Double>()
        val byId = HashMap<Long, NoteEntity>()
        base.forEachIndexed { i, n ->
            score.merge(n.id, (1 - vectorWeight) / (k + i + 1), Double::plus)
            byId[n.id] = n
        }
        vector.forEachIndexed { i, n ->
            score.merge(n.id, vectorWeight / (k + i + 1), Double::plus)
            byId.putIfAbsent(n.id, n)
        }
        return score.entries.sortedByDescending { it.value }.map { byId.getValue(it.key) }
    }

    /** 融合序 + 新鲜度（90 天半衰）+ 置顶。与记忆侧 5 项加权的同构简化。 */
    internal fun rankScore(note: NoteEntity, rank: Int, now: Long): Double {        val days = ((now - note.updatedAt).coerceAtLeast(0L)) / 86_400_000.0
        return 0.55 * (1.0 / (1 + rank)) +
            0.30 * exp(-days / 90.0) +
            0.15 * (if (note.pinned) 1.0 else 0.0)
    }

    /** 证据片段：命中词附近 160 字；无命中返回首行（最多 120 字） */
    internal fun snippet(note: NoteEntity, terms: List<String>): String {
        val text = note.content
        if (text.isBlank()) return note.title.take(80)
        val pos = terms.firstNotNullOfOrNull { t -> text.indexOf(t, ignoreCase = true).takeIf { it >= 0 } }
            ?: return text.replace("\n", " ").trim().take(120)
        val start = (pos - 70).coerceAtLeast(0)
        val end = (pos + 90).coerceAtMost(text.length)
        val prefix = if (start > 0) "…" else ""
        val suffix = if (end < text.length) "…" else ""
        return prefix + text.substring(start, end).replace("\n", " ") + suffix
    }
}
