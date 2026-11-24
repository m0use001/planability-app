package io.github.m0use001.planability.ai.embed

import android.content.Context
import io.github.m0use001.planability.data.EmbeddingStamp
import io.github.m0use001.planability.data.Graph
import io.github.m0use001.planability.data.NoteEmbedding
import io.github.m0use001.planability.data.NoteEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * v52 知识库语义检索：笔记向量的增量索引与相似度检索（按模型隔离）。
 *
 * 语义检索是**可选增强**：当前模型没下载时这里全部返回空，上层
 * （[io.github.m0use001.planability.ai.rag.NoteSearch] / KbAsk）自动退回词法检索。
 *
 * 索引策略：
 * - 每次最多算 [batchSize] 条（轻量 24 条 / 重型 4 条——重型每条 0.3–1s，
 *   一次跑太多会把手机烤热且迟迟不退出后台）；
 * - 记录 `mtime = note.updatedAt`，改过的才重算；孤儿（笔记删了）自动清；
 * - 维度写进行里：换模型/换精度后 [noteId, modelId] 主键天然隔离，不会混用。
 */
object NoteVectors {

    private const val INDEX_BATCH_LIGHT = 24
    private const val INDEX_BATCH_HEAVY = 4
    private const val EMBED_CHARS = 1200

    fun batchSize(model: EmbeddingModel): Int =
        if (model.dim >= 1024) INDEX_BATCH_HEAVY else INDEX_BATCH_LIGHT

    fun isAvailable(context: Context, model: EmbeddingModel = ModelStore.selected(context)): Boolean =
        ModelStore.isReady(context, model)

    suspend fun indexedCount(model: EmbeddingModel = EmbeddingModels.LIGHT): Int = withContext(Dispatchers.IO) {
        Graph.db.noteEmbeddingDao().count(model.id)
    }

    suspend fun indexedCountFor(context: Context, model: EmbeddingModel): Int =
        withContext(Dispatchers.IO) { Graph.db.noteEmbeddingDao().count(model.id) }

    /** 待算的笔记（按 updatedAt 新→旧），最多 [limit] 条 */
    suspend fun pending(context: Context, model: EmbeddingModel, limit: Int): List<NoteEntity> =
        withContext(Dispatchers.IO) {
            if (!isAvailable(context, model)) return@withContext emptyList()
            val stamps = Graph.db.noteEmbeddingDao().stamps(model.id).associate { it.noteId to it.mtime }
            Graph.notes.allNotes().filter { stamps[it.id] != it.updatedAt }.take(limit)
        }

    /** 总待算条数（UI 显示进度用） */
    suspend fun pendingCount(model: EmbeddingModel): Int = withContext(Dispatchers.IO) {
        val stamps: List<EmbeddingStamp> = Graph.db.noteEmbeddingDao().stamps(model.id)
        val done = stamps.associate { it.noteId to it.mtime }
        Graph.notes.allNotes().count { done[it.id] != it.updatedAt }
    }

    /** 增量索引一批；返回本次算了几条 */
    suspend fun ensureIndexed(
        context: Context,
        model: EmbeddingModel = ModelStore.selected(context),
        limit: Int = batchSize(model)
    ): Int = withContext(Dispatchers.IO) {
        if (!isAvailable(context, model)) return@withContext 0
        val dao = Graph.db.noteEmbeddingDao()
        val notes = Graph.notes.allNotes()
        val ids = notes.map { it.id }.toSet()
        dao.stamps(model.id).map { it.noteId }.filter { it !in ids }.forEach { dao.delete(model.id, it) }
        if (notes.isEmpty()) return@withContext 0
        val stamps = dao.stamps(model.id).associate { it.noteId to it.mtime }
        var done = 0
        notes.asSequence()
            .filter { stamps[it.id] != it.updatedAt }
            .take(limit)
            .forEach { n ->
                val vec = Embedder.embed(context, embedText(n), model, isQuery = false) ?: return@forEach
                dao.upsert(NoteEmbedding(n.id, model.id, vec.size, Embedder.toBytes(vec), n.updatedAt))
                done++
            }
        done
    }

    /** 单篇立即算（保存笔记时调；模型没下载时是空操作） */
    suspend fun indexOne(context: Context, note: NoteEntity, model: EmbeddingModel = ModelStore.selected(context)) =
        withContext(Dispatchers.IO) {
            if (!isAvailable(context, model)) return@withContext
            runCatching {
                val vec = Embedder.embed(context, embedText(note), model, isQuery = false) ?: return@runCatching
                Graph.db.noteEmbeddingDao().upsert(
                    NoteEmbedding(note.id, model.id, vec.size, Embedder.toBytes(vec), note.updatedAt)
                )
            }
        }

    /** 向量检索：返回 (noteId, 余弦) 降序；模型没下载 → 空 */
    suspend fun search(
        context: Context,
        query: String,
        model: EmbeddingModel = ModelStore.selected(context),
        topK: Int = 8
    ): List<Pair<Long, Float>> = withContext(Dispatchers.IO) {
        if (!isAvailable(context, model)) return@withContext emptyList()
        runCatching { ensureIndexed(context, model) }
        val q = Embedder.embed(context, query, model, isQuery = true) ?: return@withContext emptyList()
        val rows = Graph.db.noteEmbeddingDao().all(model.id)
        if (rows.isEmpty()) return@withContext emptyList()
        val active = Graph.notes.allNotes().map { it.id }.toSet()
        rows.asSequence()
            .filter { it.noteId in active }
            .map { it.noteId to Embedder.cosine(q, Embedder.fromBytes(it.vec)) }
            .filter { it.second > 0.25f }
            .sortedByDescending { it.second }
            .take(topK)
            .toList()
    }

    /** 向量化文本：标题（bge 没有标题列，拼在开头；Qwen 同理） */
    private fun embedText(n: NoteEntity): String {
        val body = n.content.take(EMBED_CHARS)
        return if (n.title.isBlank()) body else n.title + "\n" + body
    }

    suspend fun clear(model: EmbeddingModel) = withContext(Dispatchers.IO) {
        Graph.db.noteEmbeddingDao().clearModel(model.id)
    }

    suspend fun clearAll() = withContext(Dispatchers.IO) {
        Graph.db.noteEmbeddingDao().clear()
    }
}
