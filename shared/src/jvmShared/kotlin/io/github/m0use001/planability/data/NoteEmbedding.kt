package io.github.m0use001.planability.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * v51/v52 知识库语义检索：笔记级向量（512 维 bge / 1024 维 Qwen3-Embedding）。
 *
 * **按模型隔离**：两个模型的空间不可比，所以主键是 (noteId, modelId)，切换模型不会
 * 互相污染；切回来时旧模型的向量还在（如果没删）。
 *
 * 为什么是笔记级而不是块级：块级要存 N 倍向量、也要 N 次推理；而"找哪篇笔记讲过这个"
 * 是笔记级问题，块级细节交给已有的 BM25 分块检索。三路（FTS + BM25 + 向量）在 RRF 里融合。
 *
 * `mtime` = 计算时笔记的 updatedAt：与当前值不一致就说明该重算（惰性增量，同 RagIndex）。
 */
@Entity(tableName = "note_embeddings", primaryKeys = ["noteId", "modelId"])
data class NoteEmbedding(
    val noteId: Long,
    val modelId: String,
    val dim: Int,
    val vec: ByteArray,
    val mtime: Long
) {
    override fun equals(other: Any?): Boolean =
        other is NoteEmbedding && other.noteId == noteId && other.modelId == modelId && other.mtime == mtime

    override fun hashCode(): Int = noteId.hashCode() * 31 + modelId.hashCode() * 17 + mtime.hashCode()
}

@Dao
interface NoteEmbeddingDao {
    @Query("SELECT noteId, mtime FROM note_embeddings WHERE modelId = :modelId")
    suspend fun stamps(modelId: String): List<EmbeddingStamp>

    @Query("SELECT * FROM note_embeddings WHERE modelId = :modelId")
    suspend fun all(modelId: String): List<NoteEmbedding>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: NoteEmbedding)

    @Query("DELETE FROM note_embeddings WHERE modelId = :modelId AND noteId = :noteId")
    suspend fun delete(modelId: String, noteId: Long)

    @Query("DELETE FROM note_embeddings WHERE modelId = :modelId")
    suspend fun clearModel(modelId: String)

    @Query("DELETE FROM note_embeddings")
    suspend fun clear()

    @Query("SELECT COUNT(*) FROM note_embeddings WHERE modelId = :modelId")
    suspend fun count(modelId: String): Int
}

data class EmbeddingStamp(val noteId: Long, val mtime: Long)
