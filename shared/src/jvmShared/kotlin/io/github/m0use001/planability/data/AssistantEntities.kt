package io.github.m0use001.planability.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

/**
 * 助手（M3）：人格 = systemPrompt（时间与用户上下文 chips 由 ContextEngine 统一附加）。
 * builtin 的助手缺种子时自动补回；key 是动作会话（提纯/抽取）定位助手的稳定键。
 */
@Entity(tableName = "assistants", indices = [Index(value = ["key"], unique = true)])
data class Assistant(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val key: String,
    val name: String,
    val emoji: String = "🤖",
    /** 一句话介绍（选择器展示） */
    val slogan: String = "",
    val systemPrompt: String = "",
    /**
     * v55 智能体卡（JSON，见 ai/AgentCard.kt）。
     * 空 = 旧版单字符串人格，ContextEngine 用 [systemPrompt] 兜底（零行为变化）。
     * 非空时卡内字段全面接管：人设/文风示例/收尾禁令/深度注/脚手架策略/温度。
     */
    val card: String = "",
    val temperature: Double = 0.7,
    val builtin: Boolean = false,
    val createdAt: Long = 0
)

@Dao
interface AssistantDao {
    @Query("SELECT * FROM assistants ORDER BY builtin DESC, id")
    suspend fun all(): List<Assistant>

    @Query("SELECT * FROM assistants WHERE id = :id")
    suspend fun byId(id: Long): Assistant?

    @Query("SELECT * FROM assistants WHERE `key` = :key LIMIT 1")
    suspend fun byKey(key: String): Assistant?

    @Query("SELECT COUNT(*) FROM assistants")
    suspend fun count(): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(list: List<Assistant>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(a: Assistant): Long

    @Query("DELETE FROM assistants WHERE id = :id AND builtin = 0")
    suspend fun deleteCustom(id: Long)
}
