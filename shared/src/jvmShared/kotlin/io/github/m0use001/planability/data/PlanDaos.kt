package io.github.m0use001.planability.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ScheduleDao {
    @Query("SELECT * FROM schedules WHERE trashed = 0 AND start <= :rangeEnd ORDER BY start")
    suspend fun activeUntil(rangeEnd: Long): List<Schedule>

    @Query("SELECT * FROM schedules WHERE trashed = 1 ORDER BY trashedAt DESC")
    suspend fun trashed(): List<Schedule>

    @Query("SELECT * FROM schedules WHERE id = :id")
    suspend fun byId(id: Long): Schedule?

    @Query("SELECT COUNT(*) FROM schedules WHERE trashed = 0")
    suspend fun count(): Int

    @Query("SELECT COUNT(*) FROM schedules WHERE remindMin != 15")
    suspend fun customRemindCount(): Int

    // v21：upsert/update 走实体，调用方负责带 updatedAt；下面几条裸 SQL 必须自己 bump，
    // 否则「丢进回收站/恢复」这类改动在 dump 合并里看不出发生过
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: Schedule): Long

    @Update
    suspend fun update(item: Schedule)

    @Query("UPDATE schedules SET trashed = 1, trashedAt = :now, updatedAt = :now WHERE id = :id")
    suspend fun trash(id: Long, now: Long)

    @Query("UPDATE schedules SET trashed = 0, trashedAt = 0, updatedAt = :now WHERE id = :id")
    suspend fun restore(id: Long, now: Long)

    @Query("DELETE FROM schedules WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM schedules WHERE trashed = 1")
    suspend fun emptyTrash()

    @Query("SELECT * FROM schedules")
    suspend fun all(): List<Schedule>

    /** 一次性迁移：把所有日程从项目里摘出来（2026-10-21 事故后日程不再归属项目） */
    @Query("UPDATE schedules SET projectId = 0 WHERE projectId != 0")
    suspend fun detachAllProjects()

    /** v41：老数据里"地图选点过的地名"本来就在 place 里，补到 placePoi 当绑定名 */
    @Query("UPDATE schedules SET placePoi = place WHERE placePoi = '' AND place != '' AND (lat != 0 OR lng != 0)")
    suspend fun backfillPlacePoi()
}

@Dao
interface TodoDao {
    @Query("SELECT * FROM todos WHERE trashed = 0 ORDER BY date, due, priority")
    suspend fun active(): List<Todo>

    @Query("SELECT * FROM todos WHERE trashed = 0 AND date = :dayKey ORDER BY due, priority")
    suspend fun byDate(dayKey: Long): List<Todo>

    /**
     * 2026-11-03 补「漏勾即消失」的洞（用户实测：每天背单词漏一天，date 停在那天，
     * 之后每天都从今日视图消失）：取 **date 已过期、未完成、仍在重复期内** 的重复待办。
     * 是否命中今天相位由 Repo 层用统一重复引擎判（SQL 写不出 biweekly/monthlyW 相位）；
     * excludedDates 例外与去重也由调用方过滤。
     */
    @Query(
        "SELECT * FROM todos WHERE trashed = 0 AND done = 0 " +
            "AND date > 0 AND date < :dayKey AND repeatType != 'none' " +
            "AND (permanent = 1 OR repeatEnd >= :dayKey) ORDER BY due, priority"
    )
    suspend fun overdueRepeating(dayKey: Long): List<Todo>

    /**
     * 单行滚期**滚过头**的嫌疑行（2026-11-04「背单词今天看不到」）：行日期落在今天之后。
     * 正常的每日重复最多到明天（今天已打卡时），更远只可能是「以行日期而不是被完成实例
     * 起算」的旧 bug 推出来的。是否真错、该拉回哪天，由 Repo 层按完成流水 + 重复规则判
     * （见 PlanRepo.repairRolledPast）。
     */
    @Query(
        "SELECT * FROM todos WHERE trashed = 0 AND done = 0 AND date > :dayKey " +
            "AND repeatType != 'none' ORDER BY date"
    )
    suspend fun repeatAheadOf(dayKey: Long): List<Todo>

    @Query("SELECT * FROM todos WHERE trashed = 0 AND supervisionEnabled = 1")
    suspend fun supervisionTodos(): List<Todo>

    @Query("SELECT * FROM todos WHERE trashed = 1 ORDER BY trashedAt DESC")
    suspend fun trashed(): List<Todo>

    @Query("SELECT * FROM todos WHERE id = :id")
    suspend fun byId(id: Long): Todo?

    @Query("SELECT COUNT(*) FROM todos WHERE trashed = 0")
    suspend fun count(): Int

    @Query("SELECT COUNT(*) FROM todos WHERE trashed = 0 AND done = 1")
    suspend fun doneCount(): Int

    @Query("SELECT COUNT(*) FROM todos WHERE trashed = 0 AND remindMin != 15")
    suspend fun customRemindCount(): Int

    // v21：同 schedules，裸 SQL 自己 bump updatedAt
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: Todo): Long

    @Update
    suspend fun update(item: Todo)

    @Query("UPDATE todos SET trashed = 1, trashedAt = :now, updatedAt = :now WHERE id = :id")
    suspend fun trash(id: Long, now: Long)

    @Query("UPDATE todos SET trashed = 0, trashedAt = 0, updatedAt = :now WHERE id = :id")
    suspend fun restore(id: Long, now: Long)

    @Query("DELETE FROM todos WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM todos WHERE trashed = 1")
    suspend fun emptyTrash()

    @Query("SELECT * FROM todos")
    suspend fun all(): List<Todo>

    /** 一次性迁移：把所有待办从项目里摘出来（2026-10-21 事故后待办不再归属项目） */
    @Query("UPDATE todos SET projectId = 0 WHERE projectId != 0")
    suspend fun detachAllProjects()

    /** v41：老数据的绑定地名回填（同 ScheduleDao.backfillPlacePoi） */
    @Query("UPDATE todos SET placePoi = place WHERE placePoi = '' AND place != '' AND (lat != 0 OR lng != 0)")
    suspend fun backfillPlacePoi()
}

@Dao
interface SupervisionDao {
    @Query("SELECT * FROM supervision_checks WHERE todoId = :todoId ORDER BY date")
    suspend fun ofTodo(todoId: Long): List<SupervisionCheck>

    @Query("SELECT * FROM supervision_checks WHERE date = :dayKey")
    suspend fun byDate(dayKey: Long): List<SupervisionCheck>

    @Query("SELECT * FROM supervision_checks")
    suspend fun all(): List<SupervisionCheck>

    @Query("DELETE FROM supervision_checks WHERE todoId = :todoId AND date = :dayKey")
    suspend fun deleteCheck(todoId: Long, dayKey: Long)

    @Query("DELETE FROM supervision_checks WHERE todoId = :todoId")
    suspend fun deleteOfTodo(todoId: Long)

    @Insert
    suspend fun insert(check: SupervisionCheck): Long
}

/** v22：打卡载荷。与 SupervisionCheck 分表，一天最多一条（见 [PlanRepo.addCheckin]）。 */
@Dao
interface CheckinDao {
    @Query("SELECT * FROM checkin_records WHERE todoId = :todoId ORDER BY date DESC, at DESC")
    suspend fun ofTodo(todoId: Long): List<CheckinRecord>

    @Query("SELECT * FROM checkin_records WHERE todoId = :todoId AND date = :dayKey LIMIT 1")
    suspend fun ofTodoDay(todoId: Long, dayKey: Long): CheckinRecord?

    @Query("SELECT * FROM checkin_records WHERE todoId IN (:ids) ORDER BY date DESC, at DESC")
    suspend fun ofTodos(ids: List<Long>): List<CheckinRecord>

    @Query("SELECT * FROM checkin_records")
    suspend fun all(): List<CheckinRecord>

    @Query("SELECT DISTINCT todoId FROM checkin_records")
    suspend fun todoIds(): List<Long>

    @Query("DELETE FROM checkin_records WHERE todoId = :todoId AND date = :dayKey")
    suspend fun deleteOfDay(todoId: Long, dayKey: Long)

    @Query("DELETE FROM checkin_records WHERE todoId = :todoId")
    suspend fun deleteOfTodo(todoId: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(record: CheckinRecord): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(list: List<CheckinRecord>)
}

@Dao
interface BackupDao {
    @Query("SELECT * FROM todo_done_logs")
    suspend fun allDoneLogs(): List<TodoDoneLog>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDoneLogs(list: List<TodoDoneLog>)

    @Query("SELECT * FROM hour_tasks")
    suspend fun allHourTasks(): List<HourTask>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertHourTasks(list: List<HourTask>)

    @Query("SELECT * FROM timeline_events")
    suspend fun allEvents(): List<TimelineEvent>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEvents(list: List<TimelineEvent>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSupervisions(list: List<SupervisionCheck>)

    // v22
    @Query("SELECT * FROM checkin_records")
    suspend fun allCheckins(): List<CheckinRecord>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCheckins(list: List<CheckinRecord>)

    @Query("DELETE FROM checkin_records")
    suspend fun clearCheckins()

    // v48：百分比进度流水（与 checkin 并列，同属"用户辛苦记的数据"，备份必带）
    @Query("SELECT * FROM todo_percent_logs")
    suspend fun allPercentLogs(): List<TodoPercentLog>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPercentLogs(list: List<TodoPercentLog>)

    @Query("DELETE FROM todo_percent_logs")
    suspend fun clearPercentLogs()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSchedules(list: List<Schedule>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTodos(list: List<Todo>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCountdowns(list: List<Countdown>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCategories(list: List<CategoryEntity>)

    @Query("DELETE FROM schedules")
    suspend fun clearSchedules()

    @Query("DELETE FROM todos")
    suspend fun clearTodos()

    @Query("DELETE FROM supervision_checks")
    suspend fun clearSupervisions()

    @Query("DELETE FROM todo_done_logs")
    suspend fun clearDoneLogs()

    @Query("DELETE FROM hour_tasks")
    suspend fun clearHourTasks()

    @Query("DELETE FROM timeline_events")
    suspend fun clearEvents()

    @Query("DELETE FROM countdowns")
    suspend fun clearCountdowns()

    @Query("DELETE FROM categories")
    suspend fun clearCategories()

    @Query("DELETE FROM focus_sessions")
    suspend fun clearFocusSessions()

    @Query("DELETE FROM notebooks")
    suspend fun clearNotebooks()

    @Query("DELETE FROM notes")
    suspend fun clearNotes()

    @Query("DELETE FROM chat_digests")
    suspend fun clearChatDigests()

    @Query("SELECT * FROM chat_targets")
    suspend fun allChatTargets(): List<GroupTarget>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertChatTargets(list: List<GroupTarget>)

    @Query("DELETE FROM chat_targets")
    suspend fun clearChatTargets()

    @Query("DELETE FROM pending_items")
    suspend fun clearPendingItems()

    @Query("DELETE FROM weekly_records")
    suspend fun clearWeeklyRecords()

    @Query("SELECT * FROM sleep_records ORDER BY dateEpochDay DESC")
    suspend fun allSleepRecords(): List<SleepRecord>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSleepRecords(list: List<SleepRecord>)

    @Query("DELETE FROM sleep_records")
    suspend fun clearSleepRecords()

    @Query("DELETE FROM settings")
    suspend fun clearSettings()

    @Query("DELETE FROM achievements")
    suspend fun clearAchievements()

    @Query("SELECT * FROM note_audio")
    suspend fun allNoteAudio(): List<NoteAudio>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertNoteAudio(list: List<NoteAudio>)

    @Query("DELETE FROM note_audio")
    suspend fun clearNoteAudio()

    @Query("SELECT * FROM attachments")
    suspend fun allAttachments(): List<Attachment>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAttachments(list: List<Attachment>)

    @Query("DELETE FROM attachments")
    suspend fun clearAttachments()

    // ---- v39(M8)：AI 会话进备份。只恢复 JSON 备份不再丢会话、消息与滚动摘要 ----

    @Query("SELECT * FROM chat_sessions ORDER BY id")
    suspend fun allChatSessions(): List<ChatSession>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertChatSessions(list: List<ChatSession>)

    @Query("SELECT * FROM chat_messages ORDER BY id")
    suspend fun allChatMessages(): List<ChatMessage>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertChatMessages(list: List<ChatMessage>)

    /** 压缩标记是摘要的审计记录（锚点/条数/来源），跟会话一起走，恢复后对得上账 */
    @Query("SELECT * FROM compact_markers ORDER BY id")
    suspend fun allCompactMarkers(): List<CompactMarkerEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCompactMarkers(list: List<CompactMarkerEntity>)

    /** 清库顺序：先 markers（外键指向 chat_sessions），再 messages，最后 sessions */
    @Query("DELETE FROM compact_markers")
    suspend fun clearCompactMarkers()

    @Query("DELETE FROM chat_messages")
    suspend fun clearChatMessages()

    @Query("DELETE FROM chat_sessions")
    suspend fun clearChatSessions()
}

@Dao
interface DoneLogDao {
    @Query("SELECT * FROM todo_done_logs WHERE date BETWEEN :from AND :to ORDER BY at")
    suspend fun between(from: Long, to: Long): List<TodoDoneLog>

    @Query("SELECT * FROM todo_done_logs WHERE date = :dayKey")
    suspend fun byDate(dayKey: Long): List<TodoDoneLog>

    @Query("SELECT COUNT(*) FROM todo_done_logs WHERE date = :dayKey")
    suspend fun countOn(dayKey: Long): Int

    /**
     * 「已滚出当日列表」的完成数（DayBundle.doneCount 用它，**别用 countOn**）。
     *
     * 为什么：完成流水对**每次**完成都写一行，但非重复待办完成后仍留在当日列表里（done=true），
     * 评分公式的 `todos.count{done}` 已经数过它——再把流水原样加起来就是**同一件事数两遍**：
     * 当天 2 个待办全完成 → total=2、done=4 → 完成率 200%（真机复现）。
     * 流水补位的本意只针对按周期滚走的重复待办：它完成后原行 date 挪到下一期、离开当日列表，
     * 光靠 done 标记会把它整个漏掉。所以只统计「流水落在当日、但 todoId 已不在当日列表」的行。
     */
    @Query(
        "SELECT COUNT(*) FROM todo_done_logs WHERE date = :dayKey " +
            "AND todoId NOT IN (SELECT id FROM todos WHERE date = :dayKey)"
    )
    suspend fun countRolledOffOn(dayKey: Long): Int

    /**
     * 「已滚出当日列表」的完成流水（逐行版，2026-10-29 加）。
     * 与 [countRolledOffOn] 同一口径（相关子查询逐行判 date 相同），
     * 用于把「今天打完卡就被滚到下一期」的重复待办在当日视图里还原出来。
     */
    @Query(
        "SELECT * FROM todo_done_logs AS l WHERE l.date BETWEEN :from AND :to " +
            "AND l.todoId NOT IN (SELECT id FROM todos WHERE date = l.date) ORDER BY l.date, l.at"
    )
    suspend fun rolledOffBetween(from: Long, to: Long): List<TodoDoneLog>

    /** 撤销某天的完成流水（取消勾选时与勾选对称，不然同一件事会留两条） */
    @Query("DELETE FROM todo_done_logs WHERE todoId = :todoId AND date = :dayKey")
    suspend fun deleteOfDay(todoId: Long, dayKey: Long)

    /** 截至某天（含）最近一次完成的日期；没完成过返回 null（自愈滚过头用，见 PlanRepo.repairRolledPast） */
    @Query("SELECT MAX(date) FROM todo_done_logs WHERE todoId = :todoId AND date <= :dayKey")
    suspend fun lastDoneUpTo(todoId: Long, dayKey: Long): Long?

    @Insert
    suspend fun insert(log: TodoDoneLog): Long
}

@Dao
interface HourTaskDao {
    @Query("SELECT * FROM hour_tasks WHERE date = :dayKey ORDER BY hour")
    suspend fun byDate(dayKey: Long): List<HourTask>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: HourTask): Long

    @Query("DELETE FROM hour_tasks WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT * FROM hour_tasks WHERE id = :id")
    suspend fun byId(id: Long): HourTask?

    @Query("SELECT COUNT(*) FROM hour_tasks WHERE done = 1")
    suspend fun doneCount(): Int
}

@Dao
interface TimelineEventDao {
    @Query("SELECT * FROM timeline_events WHERE date = :dayKey ORDER BY startMin")
    suspend fun byDate(dayKey: Long): List<TimelineEvent>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: TimelineEvent): Long

    @Query("DELETE FROM timeline_events WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT * FROM timeline_events WHERE id = :id")
    suspend fun byId(id: Long): TimelineEvent?

    @Query("SELECT COUNT(*) FROM timeline_events")
    suspend fun count(): Int
}

@Dao
interface CountdownDao {
    @Query("SELECT * FROM countdowns ORDER BY target")
    suspend fun allOrdered(): List<Countdown>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: Countdown): Long

    @Query("DELETE FROM countdowns WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface CategoryDao {
    @Query("SELECT * FROM categories WHERE kind = :kind ORDER BY id")
    suspend fun byKind(kind: Int): List<CategoryEntity>

    @Query("SELECT * FROM categories ORDER BY kind, id")
    suspend fun all(): List<CategoryEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: CategoryEntity): Long

    /**
     * 只改坐标（2026-10-29）：改坐标的入口一律走这里，**不许**整行 upsert 旧快照 ——
     * 面板打开期间显示地名可能已被增删（ensureDisplayName 直改 displayNames 列），
     * 整行写回会把新增的名字覆盖掉（用户实测"加的显示名保存后消失"的根因）。
     */
    @Query("UPDATE categories SET lat = :lat, lng = :lng WHERE id = :id")
    suspend fun updateCoords(id: Long, lat: Double, lng: Double)

    @Query("DELETE FROM categories WHERE id = :id")
    suspend fun delete(id: Long)
}

/** v28：自由标签（Todo.labels / Schedule.labels 里的词）→ 颜色 */
@Dao
interface LabelColorDao {
    @Query("SELECT * FROM label_colors")
    suspend fun all(): List<LabelColor>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: LabelColor)

    /** 恢复默认色 = 删掉这一行（没存过就等于用默认 210f，语义一致） */
    @Query("DELETE FROM label_colors WHERE name = :name")
    suspend fun delete(name: String)
}
