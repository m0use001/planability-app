package io.github.m0use001.planability.ai.rag

import io.github.m0use001.planability.core.AppTime
import io.github.m0use001.planability.data.Graph
import io.github.m0use001.planability.data.RagChunk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlin.math.ln

data class RagHit(
    val noteId: Long,
    val title: String,
    val chunkIndex: Int,
    val score: Double,
    val snippet: String
)

/**
 * M6 RAG-lite（guIDE ragEngine 模式）：
 * - 笔记正文按行分块（500 行窗口 / 50 行重叠；短笔记单块）
 * - mtime 增量维护：查询前惰性 diff，只重建变化的笔记
 * - 无嵌入 BM25（k1=1.5, b=0.75），查询时全量打分（个人规模数千块毫秒级）
 * - 分词复用记忆层规则：ASCII 词 + CJK 二元组
 */
object RagIndex {

    private const val CHUNK_LINES = 500
    private const val OVERLAP_LINES = 50
    private const val MAX_CHUNK_CHARS = 8000
    private const val K1 = 1.5
    private const val B = 0.75

    /** 惰性增量索引：新增/修改/删除的笔记在下次查询前对齐 */
    suspend fun ensureIndexed() = withContext(Dispatchers.IO) {
        runCatching {
            val notes = Graph.notes.allNotes()
            val dao = Graph.db.ragDao()
            val existing = dao.sources().associate { it.sourceId to it.mtime }
            val noteMap = notes.associate { it.id to it.updatedAt }
            // 删除：笔记被删/进回收站/内容变化
            existing.forEach { (sid, mtime) ->
                if (noteMap[sid] != mtime) dao.removeSource(sid)
            }
            // 重建新增/变化
            notes.forEach { n ->
                if (existing[n.id] != n.updatedAt) {
                    dao.removeSource(n.id)
                    val chunks = chunkNote(n.id, n.title, n.content, n.updatedAt)
                    if (chunks.isNotEmpty()) dao.insertAll(chunks)
                }
            }
        }
    }

    private fun chunkNote(sourceId: Long, title: String, content: String, mtime: Long): List<RagChunk> {
        val lines = content.lines()
        if (lines.all { it.isBlank() } && title.isBlank()) return emptyList()
        val chunks = mutableListOf<RagChunk>()
        if (lines.size <= CHUNK_LINES) {
            chunks += buildChunk(sourceId, title, lines, 0, mtime)
        } else {
            val step = CHUNK_LINES - OVERLAP_LINES
            var idx = 0
            var start = 0
            while (start < lines.size) {
                val end = minOf(start + CHUNK_LINES, lines.size)
                chunks += buildChunk(sourceId, title, lines.subList(start, end), idx, mtime)
                idx++
                if (end >= lines.size) break
                start += step
            }
        }
        return chunks
    }

    private fun buildChunk(sourceId: Long, title: String, lines: List<String>, index: Int, mtime: Long): RagChunk {
        var text = (if (title.isNotBlank()) "《" + title + "》\n" else "") + lines.joinToString("\n")
        if (text.length > MAX_CHUNK_CHARS) text = text.take(MAX_CHUNK_CHARS)
        return RagChunk(sourceType = "note", sourceId = sourceId, chunkIndex = index, text = text, mtime = mtime)
    }

    suspend fun search(query: String, topK: Int = 5): List<RagHit> = withContext(Dispatchers.IO) {
        ensureIndexed()
        val terms = tokenize(query, 10)
        if (terms.isEmpty()) return@withContext emptyList()
        val chunks = Graph.db.ragDao().all()
        if (chunks.isEmpty()) return@withContext emptyList()
        val notes = Graph.notes.allNotes()
        val titles = notes.associate { it.id to it.title }

        // 词频与文档频率
        data class Doc(val chunk: RagChunk, val tf: Map<String, Int>, val dl: Int)
        val docs = chunks.map { c -> Doc(c, termCounts(c.text), termCounts(c.text).values.sum()) }
        val df = HashMap<String, Int>()
        terms.forEach { t ->
            df[t] = docs.count { d -> d.tf.containsKey(t) }
        }
        val N = docs.size
        val avgdl = if (N == 0) 1.0 else docs.sumOf { it.dl.toDouble() } / N

        val hits = docs.mapNotNull { d ->
            var score = 0.0
            terms.forEach { t ->
                val tf = d.tf[t]?.toDouble() ?: return@forEach
                val n = df[t] ?: 0
                if (n == 0) return@forEach
                val idf = ln((N - n + 0.5) / (n + 0.5) + 1.0)
                score += idf * (tf * (K1 + 1)) / (tf + K1 * (1 - B + B * d.dl / avgdl))
            }
            if (score <= 0.0) null else d.chunk to score
        }.sortedByDescending { it.second }.take(topK)

        hits.map { (chunk, score) ->
            RagHit(
                noteId = chunk.sourceId,
                title = titles[chunk.sourceId] ?: "笔记" + chunk.sourceId,
                chunkIndex = chunk.chunkIndex,
                score = score,
                snippet = makeSnippet(chunk.text, terms)
            )
        }
    }

    private fun makeSnippet(text: String, terms: List<String>): String {
        val pos = terms.firstNotNullOfOrNull { t -> text.indexOf(t).takeIf { it >= 0 } } ?: return text.take(120)
        val start = (pos - 50).coerceAtLeast(0)
        val end = (pos + 110).coerceAtMost(text.length)
        val prefix = if (start > 0) "…" else ""
        val suffix = if (end < text.length) "…" else ""
        return prefix + text.substring(start, end).replace("\n", " ") + suffix
    }

    // ---------- 分词（与 MemoryRepo 同规则：ASCII 词 + CJK 二元组） ----------

    private fun tokenize(text: String, maxTerms: Int): List<String> {
        val out = mutableListOf<String>()
        Regex("[A-Za-z0-9_]{2,}").findAll(text).forEach { m -> out += m.value.lowercase() }
        Regex("[\\u2E80-\\u9FFF\\u3000-\\u303F]+").findAll(text).forEach { run ->
            val s = run.value
            if (s.length == 1) out += s
            else for (i in 0 until s.length - 1) out += s.substring(i, i + 2)
        }
        return out.distinct().take(maxTerms)
    }

    private fun termCounts(text: String): Map<String, Int> {
        val counts = HashMap<String, Int>()
        Regex("[A-Za-z0-9_]{2,}").findAll(text).forEach { m ->
            val k = m.value.lowercase()
            counts[k] = (counts[k] ?: 0) + 1
        }
        Regex("[\\u2E80-\\u9FFF\\u3000-\\u303F]+").findAll(text).forEach { run ->
            val s = run.value
            if (s.length == 1) counts[s] = (counts[s] ?: 0) + 1
            else for (i in 0 until s.length - 1) {
                val k = s.substring(i, i + 2)
                counts[k] = (counts[k] ?: 0) + 1
            }
        }
        return counts
    }
}
