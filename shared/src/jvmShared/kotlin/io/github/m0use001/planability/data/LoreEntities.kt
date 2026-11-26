package io.github.m0use001.planability.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import org.json.JSONArray

/**
 * v55：智能体卡挂载的世界书条目（设定卡）。
 *
 * 与 assistants 表是一对多：世界书随智能体卡走（SillyTavern `character_book` /
 * V2 卡的 `data.character_book` 同模型），导入导出直接映射，不引入项目域。
 *
 * 注入语义（ai/lore/LoreEngine.kt）：
 * - 每轮扫最近 [scanDepth] 条消息（0 = 全局默认），主键命中才入选；
 * - [constant] = 常驻，每轮必注（不扫键、不掷概率）；
 * - [priority] 决定预算内谁先入选，[order] 决定同槽位内的先后（RisuAI 的
 *   priority-selects / order-places 两段式）；
 * - [position]：desc_after（人设块之后）/ desc_before（人设块之前）/ depth
 *   （倒数第 [depth] 条消息后，[role] 定角色）。
 *
 * 明确不做：递归触发、向量条目、sticky/cooldown/delay 计时（ST 的历史包袱，
 * RisuAI 也已整体砍掉）。
 */
@Entity(tableName = "lore_entries", indices = [Index(value = ["assistantId"])])
data class LoreEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** 所属智能体（0 = 未挂载，正常不会出现） */
    val assistantId: Long = 0,
    /** 卡名（列表展示，如「◆（主角）」） */
    val name: String = "",
    /** 主键候选，JSON 字符串数组 */
    val keys: String = "[]",
    /** 副键候选，JSON 字符串数组 */
    val secondKeys: String = "[]",
    /** 副键逻辑：AND_ANY / NOT_ALL / NOT_ANY / AND_ALL（ST selectiveLogic 四态） */
    val logic: String = "AND_ANY",
    /** 设定正文（命中后注入的内容） */
    val content: String = "",
    /** 常驻：每轮无条件注入 */
    val constant: Boolean = false,
    /** 注入位置：desc_after / desc_before / depth */
    val position: String = "desc_after",
    /** position=depth 时：钉在倒数第几条消息后 */
    val depth: Int = 4,
    /** position=depth 时的消息角色：system / user / assistant */
    val role: String = "system",
    /** 同槽位排位（越大越靠前） */
    val order: Int = 100,
    /** 预算内选拔权重（越大越先入选） */
    val priority: Int = 1,
    /** 扫描深度覆盖（0 = 用全局默认） */
    val scanDepth: Int = 0,
    /** 命中后放行概率（0-100，100 = 必中） */
    val probability: Int = 100,
    val disabled: Boolean = false,
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
) {
    /** 主键列表（容错：坏 JSON 返回空）。 */
    fun keyList(): List<String> = parseKeys(keys)

    /** 副键列表（容错：坏 JSON 返回空）。 */
    fun secondKeyList(): List<String> = parseKeys(secondKeys)

    companion object {
        fun parseKeys(raw: String): List<String> = runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                arr.optString(i, "").trim().takeIf { it.isNotBlank() }
            }
        }.getOrDefault(emptyList())
    }
}

@Dao
interface LoreDao {
    @Query("SELECT * FROM lore_entries WHERE assistantId = :assistantId ORDER BY priority DESC, `order` DESC, id")
    suspend fun byAssistant(assistantId: Long): List<LoreEntry>

    @Query("SELECT * FROM lore_entries ORDER BY assistantId, priority DESC, `order` DESC, id")
    suspend fun all(): List<LoreEntry>

    @Query("SELECT * FROM lore_entries WHERE id = :id")
    suspend fun byId(id: Long): LoreEntry?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(e: LoreEntry): Long

    @Query("DELETE FROM lore_entries WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM lore_entries WHERE assistantId = :assistantId")
    suspend fun deleteForAssistant(assistantId: Long)

    @Query("SELECT COUNT(*) FROM lore_entries WHERE assistantId = :assistantId")
    suspend fun countFor(assistantId: Long): Int
}
