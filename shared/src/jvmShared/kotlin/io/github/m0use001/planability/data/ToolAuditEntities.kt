package io.github.m0use001.planability.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

/** 工具调用审计（M4 PTL）：全留痕 */
@Entity(tableName = "tools_audit", indices = [Index("sessionId"), Index("at")])
data class ToolAudit(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val tool: String,
    val args: String = "",
    /** ok / fail / denied */
    val outcome: String = "ok",
    val ms: Long = 0,
    /** auto / confirm / danger */
    val risk: String = "auto",
    val detail: String = "",
    val at: Long = 0
)

@Dao
interface ToolAuditDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(a: ToolAudit): Long

    @Query("SELECT * FROM tools_audit WHERE sessionId = :sessionId ORDER BY at DESC LIMIT 50")
    suspend fun ofSession(sessionId: Long): List<ToolAudit>

    @Query("SELECT * FROM tools_audit ORDER BY at DESC LIMIT 100")
    suspend fun recent(): List<ToolAudit>

    @Query("SELECT COUNT(*) FROM tools_audit")
    suspend fun count(): Int

    /**
     * 搜索质量降级留痕计数（2026-11-08）：web_search 的 detail 带「（质量差）」= 那一次
     * 结果被判为跑偏。体检面板拿它显示"最近 N 天降级几次"——把"等用户抱怨"变成可见的数字。
     */
    @Query("SELECT COUNT(*) FROM tools_audit WHERE tool = 'web_search' AND detail LIKE '%质量差%' AND at >= :since")
    suspend fun degradedSearches(since: Long): Int

    /** v44：保留期清理（每日维护调用；没有保留期这张表会无限长） */
    @Query("DELETE FROM tools_audit WHERE at < :before")
    suspend fun prune(before: Long): Int

    @Query("SELECT * FROM tools_audit WHERE tool IN ('fs_write_text','fs_mkdir','fs_move','fs_delete','◆') ORDER BY at DESC LIMIT 100")
    suspend fun recentDangerous(): List<ToolAudit>
}
