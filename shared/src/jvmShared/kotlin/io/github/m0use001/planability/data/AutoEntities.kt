package io.github.m0use001.planability.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Update

/**
 * v43：常驻自主循环的任务表（用户定向「杀后台仍常驻」）。
 *
 * 为什么任务必须落库而不是放内存：进程随时会被 OEM 杀掉，内存队列一杀就没了；
 * 落库 + 状态机（queued/running/done/failed）+ 心跳时间戳，才能做到
 * 「执行中被杀 → 下次 tick 回收重试」。
 *
 * 状态机：
 *   queued --tick--> running --成功--> done（非周期）/ queued（周期，nextRunAt=now+interval）
 *                              \--失败--> queued（attempts<3，退避）| failed（attempts>=3）
 *   running 且 updatedAt 超过 STALE_MS → 下个 tick 视作"进程死了"，改回 queued 重试。
 */
@Entity(tableName = "auto_tasks")
data class AutoTask(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** 任务类型：selfcheck / daily_maintenance（后续可在 AutoLoop 注册新 kind） */
    val kind: String,
    val title: String = "",
    /** 任务参数（JSON，随 kind 解释） */
    val payload: String = "",
    /** queued / running / done / failed / cancelled */
    val status: String = "queued",
    val attempts: Int = 0,
    /** 断点（长任务续跑用；当前内置任务是无状态的，留字段给后续 kind） */
    val checkpoint: String = "",
    val lastError: String = "",
    /** 周期任务：跑完自动排下一次 */
    val recurring: Boolean = false,
    /** 周期（分钟），最小 60 */
    val intervalMin: Int = 0,
    /** 下一次到期时间（epoch ms）；0 = 立即 */
    val nextRunAt: Long = 0,
    val createdAt: Long = 0,
    /** 心跳：执行中每轮由 runner 更新；回收陈旧任务靠它 */
    val updatedAt: Long = 0
)

@Dao
interface AutoTaskDao {

    @Insert
    suspend fun insert(t: AutoTask): Long

    @Update
    suspend fun update(t: AutoTask)

    @Query("SELECT * FROM auto_tasks WHERE id = :id")
    suspend fun byId(id: Long): AutoTask?

    @Query("SELECT * FROM auto_tasks WHERE status = 'queued' AND nextRunAt <= :now ORDER BY nextRunAt, id LIMIT :limit")
    suspend fun due(now: Long, limit: Int): List<AutoTask>

    @Query("SELECT COUNT(*) FROM auto_tasks")
    suspend fun count(): Int

    @Query("SELECT * FROM auto_tasks ORDER BY id DESC LIMIT 50")
    suspend fun recent(): List<AutoTask>

    /** 回收"执行中但进程死了"的任务（下次 tick 重试） */
    @Query("UPDATE auto_tasks SET status = 'queued', updatedAt = :now, lastError = :note WHERE status = 'running' AND updatedAt < :staleBefore")
    suspend fun recoverStale(staleBefore: Long, now: Long, note: String = "心跳超时回收"): Int

    @Query("UPDATE auto_tasks SET status = :status, updatedAt = :now, lastError = :err WHERE id = :id")
    suspend fun setStatus(id: Long, status: String, now: Long, err: String)

    @Query("UPDATE auto_tasks SET attempts = :attempts, status = :status, nextRunAt = :nextRunAt, updatedAt = :now, lastError = :err, checkpoint = :checkpoint WHERE id = :id")
    suspend fun reschedule(
        id: Long, attempts: Int, status: String, nextRunAt: Long,
        now: Long, err: String, checkpoint: String
    )

    /** 清理历史（保留 7 天；done/failed/cancelled 才清） */
    @Query("DELETE FROM auto_tasks WHERE status IN ('done','failed','cancelled') AND updatedAt < :before")
    suspend fun prune(before: Long): Int

    @Query("DELETE FROM auto_tasks WHERE id = :id")
    suspend fun delete(id: Long)
}
