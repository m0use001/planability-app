package io.github.m0use001.planability.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query

/**
 * v48：**待办百分比进度流水**（2026-11-03 用户）。
 *
 * 「每次设置完完成百分比也要记录数据；循环/长期百分比待办按照每个打卡日百分比记录，
 * 展示成一个百分比列表，AI 也能看到」。
 *
 * ## 为什么单独建表，而不是塞 checkin_records
 * checkin_records 的语义是「一天一条数据记录」（PlanRepo.addCheckin / completeTodo 都
 * 按 (todoId, date) 先删后插）。百分比是**每次调整都记一笔的历史曲线**，同一天可能改几次
 * （最后一次为准），而且不能和用户选的数据记录类型互相删——混在一张表里迟早互踩。
 *
 * ## 口径
 * - `date` 是 dayKey（该日本地零点 epoch millis，与 Todo.date 同口径）
 * - 同一天多次设置：**覆盖**（保留最后一次的值与时刻）
 * - 值为 0..100 的整数百分比
 */
@Entity(
    tableName = "todo_percent_logs",
    indices = [Index("todoId"), Index("todoId", "date", unique = true)]
)
data class TodoPercentLog(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val todoId: Long,
    /** dayKey（该日本地零点 epoch millis） */
    val date: Long,
    val percent: Int,
    /** 记录时刻 epoch millis */
    val at: Long = 0
)

@Dao
interface TodoPercentLogDao {

    /** REPLACE：同日重复写入直接覆盖（并发下 delete+insert 交错会撞唯一索引，真机闪退过） */
    @Insert(onConflict = androidx.room.OnConflictStrategy.REPLACE)
    suspend fun insert(log: TodoPercentLog): Long

    /** 同一天覆盖：先删当天再插（与 checkin 的"一天一条"同风格，避免同日多条曲线） */
    @Query("DELETE FROM todo_percent_logs WHERE todoId = :todoId AND date = :dayKey")
    suspend fun deleteOfDay(todoId: Long, dayKey: Long)

    /**
     * 原子「同日覆盖」：删+插在同一事务里。
     * 真机闪退教训（2026-11-03）：浮层「打卡并记录」会**并发**触发两条写入——滑动条 onSet
     * 的 setPercentDone 与 completeTodo 的打卡记录各跑一条协程；两条都先 delete 后 insert
     * 时交错执行，后一条 insert 撞 (todoId,date) 唯一索引 → SQLiteConstraintException 闪退。
     */
    @androidx.room.Transaction
    suspend fun replace(log: TodoPercentLog) {
        deleteOfDay(log.todoId, log.date)
        insert(log)
    }

    /** 某待办的百分比流水（新→旧） */
    @Query("SELECT * FROM todo_percent_logs WHERE todoId = :todoId ORDER BY date DESC LIMIT :limit")
    suspend fun ofTodo(todoId: Long, limit: Int): List<TodoPercentLog>

    /**
     * 某一天所有待办的百分比流水（2026-11-04「切到昨天填进度记到今天」）：
     * 视图按天展示进度时以这天的流水为准，行上的 percentDone 只代表「今天」。
     */
    @Query("SELECT * FROM todo_percent_logs WHERE date = :dayKey")
    suspend fun ofDay(dayKey: Long): List<TodoPercentLog>

    @Query("SELECT COUNT(*) FROM todo_percent_logs")
    suspend fun count(): Int

    @Query("DELETE FROM todo_percent_logs WHERE todoId = :todoId")
    suspend fun deleteOfTodo(todoId: Long)
}

/** 百分比流水的展示/给 AI 的文本（纯函数：UI 与 AI 共用同一份措辞）。 */
object PercentLogFormat {
    /** 单行：「10/31 · 40%」 */
    fun line(date: Long, percent: Int, zone: java.time.ZoneId): String =
        if (date <= 0) "$percent%"
        else java.time.Instant.ofEpochMilli(date).atZone(zone)
            .format(java.time.format.DateTimeFormatter.ofPattern("M/d")) + " · $percent%"

    /** 最近 N 条的紧凑列表（新→旧）：「10/31 40% → 11/1 60% → 11/2 80%」 */
    fun recent(logs: List<TodoPercentLog>, zone: java.time.ZoneId, limit: Int = 14): String =
        logs.take(limit).joinToString(" → ") { line(it.date, it.percent, zone) }
}
