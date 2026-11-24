package io.github.m0use001.planability.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query

/** RAG-lite 分块（M6）：笔记正文按行分块，mtime 增量维护 */
@Entity(tableName = "rag_chunks", indices = [Index("sourceId"), Index("mtime")])
data class RagChunk(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** 目前仅 note；预留扩展 */
    val sourceType: String = "note",
    val sourceId: Long,
    val chunkIndex: Int,
    val text: String,
    val mtime: Long
)

@Dao
interface RagDao {
    @Query("SELECT * FROM rag_chunks")
    suspend fun all(): List<RagChunk>

    @Query("SELECT sourceId, mtime FROM rag_chunks GROUP BY sourceId, mtime")
    suspend fun sources(): List<RagSourceMtime>

    @Query("DELETE FROM rag_chunks WHERE sourceId = :sid")
    suspend fun removeSource(sid: Long)

    @Insert
    suspend fun insertAll(list: List<RagChunk>)

    @Query("SELECT COUNT(*) FROM rag_chunks")
    suspend fun count(): Int

    /** v49：某篇笔记被切成几块（检索结果标「片段 N/M」用） */
    @Query("SELECT COUNT(*) FROM rag_chunks WHERE sourceId = :sid")
    suspend fun chunkCount(sid: Long): Int
}

/** sourceId + mtime 的投影行（Room 聚合查询需要具体类型） */
data class RagSourceMtime(val sourceId: Long, val mtime: Long)
