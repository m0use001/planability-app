package io.github.m0use001.planability.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Update

// ==================== v7 多供应商模型体系 ====================
// ai_providers + ai_models 两表；active/worker 模型 id 存 settings KV（ai.activeModelId / ai.workerModelId）。
// 迁移后旧 settings.model / modelw.* 不再读取（保留兼容不清理）。
// M5：实体/DAO 真源在 shared；AiProviderRepo（SecretBox 加密/图标映射/种子）留在 app 同包。

/** openai / anthropic 的鉴权风格字面量（跨端契约，实体默认值引用） */
const val AUTH_BEARER = "bearer"

@Entity(tableName = "ai_providers")
data class AiProvider(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val iconKey: String = "cloud",
    val baseUrl: String = "",
    /** openai / anthropic（anthropic 本期仅占位，transport 未实现） */
    val apiFormat: String = "openai",
    val apiKey: String = "",
    val authStyle: String = AUTH_BEARER,
    val enabled: Boolean = true,
    val builtin: Boolean = false,
    val sort: Int = 0
)

@Entity(tableName = "ai_models", indices = [Index("providerId")])
data class AiModel(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val providerId: Long,
    val name: String,
    /** 逗号分隔标签，如 "1M,视觉" */
    val tags: String = "",
    val enabled: Boolean = true
)

@Dao
interface AiProviderDao {
    @Query("SELECT * FROM ai_providers ORDER BY sort, id")
    suspend fun all(): List<AiProvider>

    @Query("SELECT * FROM ai_providers WHERE id = :id")
    suspend fun byId(id: Long): AiProvider?

    @Query("SELECT COUNT(*) FROM ai_providers")
    suspend fun count(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(p: AiProvider): Long

    @Update
    suspend fun update(p: AiProvider)

    @Query("DELETE FROM ai_providers WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface AiModelDao {
    @Query("SELECT * FROM ai_models")
    suspend fun all(): List<AiModel>

    @Query("SELECT * FROM ai_models WHERE id = :id")
    suspend fun byId(id: Long): AiModel?

    @Query("SELECT * FROM ai_models WHERE providerId = :pid ORDER BY id")
    suspend fun ofProvider(pid: Long): List<AiModel>

    @Query("SELECT COUNT(*) FROM ai_models")
    suspend fun count(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(m: AiModel): Long

    @Update
    suspend fun update(m: AiModel)

    @Query("DELETE FROM ai_models WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM ai_models WHERE providerId = :pid")
    suspend fun deleteOfProvider(pid: Long)
}
