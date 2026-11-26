package io.github.m0use001.planability.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update

@Dao
interface FocusSessionDao {
    @Query("SELECT * FROM focus_sessions WHERE startDay BETWEEN :from AND :to ORDER BY start")
    suspend fun between(from: Long, to: Long): List<FocusSession>

    @Query("SELECT * FROM focus_sessions")
    suspend fun all(): List<FocusSession>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(list: List<FocusSession>)

    @Insert
    suspend fun insert(session: FocusSession): Long

    /** 2026-10-31：AI 补记专注的撤销入口（删掉刚补的那一条） */
    @Query("DELETE FROM focus_sessions WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("SELECT COALESCE(SUM((end - start) / 60000), 0) FROM focus_sessions WHERE completed = 1")
    suspend fun totalMinutes(): Int

    @Query("SELECT MAX(plannedMin) FROM focus_sessions WHERE completed = 1")
    suspend fun maxCompletedPlanned(): Int?

    @Query("SELECT COUNT(*) FROM focus_sessions WHERE completed = 1")
    suspend fun completedCount(): Int

    @Query("SELECT * FROM focus_sessions WHERE completed = 1 ORDER BY start DESC LIMIT 1")
    suspend fun lastCompleted(): FocusSession?
}

@Dao
interface NotebookDao {
    @Query("SELECT * FROM notebooks ORDER BY createdAt")
    suspend fun all(): List<NotebookEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: NotebookEntity): Long

    @Query("DELETE FROM notebooks WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT * FROM notebooks WHERE id = :id")
    suspend fun byId(id: Long): NotebookEntity?

    @Query("SELECT COUNT(*) FROM notebooks")
    suspend fun count(): Int
}

@Dao
interface NoteDao {
    @Query("SELECT * FROM notes WHERE notebookId = :notebookId AND trashed = 0 ORDER BY updatedAt DESC")
    suspend fun ofNotebook(notebookId: Long): List<NoteEntity>

    @Query("SELECT * FROM notes WHERE trashed = 0 ORDER BY updatedAt DESC")
    suspend fun all(): List<NoteEntity>

    @Query("SELECT * FROM notes WHERE trashed = 1 ORDER BY trashedAt DESC")
    suspend fun trashed(): List<NoteEntity>

    @Query("SELECT * FROM notes")
    suspend fun allIncludingTrashed(): List<NoteEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(list: List<NoteEntity>)

    @Query("SELECT * FROM notes WHERE id = :id")
    suspend fun byId(id: Long): NoteEntity?

    @Query("SELECT COUNT(*) FROM notes WHERE trashed = 0")
    suspend fun count(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: NoteEntity): Long

    @Update
    suspend fun update(item: NoteEntity)

    @Query("UPDATE notes SET trashed = 1, trashedAt = :now WHERE id = :id")
    suspend fun trash(id: Long, now: Long)

    @Query("UPDATE notes SET trashed = 0, trashedAt = 0 WHERE id = :id")
    suspend fun restore(id: Long)

    @Query("DELETE FROM notes WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM notes WHERE trashed = 1")
    suspend fun emptyTrash()

    // ---------- 2026-11-07 P0-2 列表投影（不含 content/inkJson，打开笔记再取全文） ----------

    @Query(
        "SELECT id, notebookId, title, kind, createdAt, updatedAt, trashed, trashedAt, pinned, projectId, tags, " +
            "substr(content, 1, 240) AS preview, LENGTH(content) AS size " +
            "FROM notes WHERE notebookId = :notebookId AND trashed = 0 ORDER BY updatedAt DESC"
    )
    suspend fun rowsOfNotebook(notebookId: Long): List<NoteRow>

    @Query(
        "SELECT id, notebookId, title, kind, createdAt, updatedAt, trashed, trashedAt, pinned, projectId, tags, " +
            "substr(content, 1, 240) AS preview, LENGTH(content) AS size " +
            "FROM notes WHERE trashed = 1 ORDER BY trashedAt DESC"
    )
    suspend fun trashedRows(): List<NoteRow>

    /** 笔记本网格「N 篇 · 最近更新」：一次聚合查询替代 allNotes() 全量加载 */
    @Query(
        "SELECT notebookId, COUNT(*) AS cnt, MAX(updatedAt) AS latest FROM notes WHERE trashed = 0 GROUP BY notebookId"
    )
    suspend fun notebookStats(): List<NotebookNoteStat>

    /**
     * 2026-11-07 P0-1：`[[` 补全的标题候选（同标题去重、按最近更新序）。
     * 原来每敲一个字符 `allNotes()` 全量读正文再内存过滤——标题子串口径不变，只把活儿交给 SQL。
     */
    @Query(
        "SELECT title FROM notes WHERE trashed = 0 AND kind != 'freeform' AND id != :self " +
            "AND (:q = '' OR title LIKE '%' || :q || '%') " +
            "GROUP BY title ORDER BY MAX(updatedAt) DESC LIMIT :lim"
    )
    suspend fun distinctTitles(self: Long, q: String, lim: Int): List<String>
}

@Dao
interface ChatDigestDao {
    @Query("SELECT * FROM chat_digests ORDER BY date DESC, sourceGroup")
    suspend fun allOrdered(): List<ChatDigest>

    @Query("SELECT * FROM chat_digests WHERE date = :dayKey ORDER BY sourceGroup")
    suspend fun byDate(dayKey: Long): List<ChatDigest>

    @Query("DELETE FROM chat_digests")
    suspend fun clearAll()

    @Query("SELECT COUNT(*) FROM chat_digests")
    suspend fun count(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: ChatDigest): Long

    @Query("DELETE FROM chat_digests WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface GroupTargetDao {
    @Query("SELECT * FROM chat_targets ORDER BY createdAt")
    suspend fun all(): List<GroupTarget>

    @Query("SELECT * FROM chat_targets WHERE enabled = 1 ORDER BY createdAt")
    suspend fun allEnabled(): List<GroupTarget>

    @Query("SELECT * FROM chat_targets WHERE id = :id")
    suspend fun byId(id: Long): GroupTarget?

    @Query("SELECT * FROM chat_targets WHERE name = :name LIMIT 1")
    suspend fun byName(name: String): GroupTarget?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: GroupTarget): Long

    @Query("DELETE FROM chat_targets WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface PendingItemDao {
    @Query("SELECT * FROM pending_items WHERE status = :status ORDER BY createdAt DESC")
    suspend fun byStatus(status: String): List<PendingItem>

    @Query("SELECT * FROM pending_items")
    suspend fun all(): List<PendingItem>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(list: List<PendingItem>)

    @Query("SELECT * FROM pending_items WHERE id = :id")
    suspend fun byId(id: Long): PendingItem?

    @Query("SELECT COUNT(*) FROM pending_items WHERE status = 'pending'")
    suspend fun pendingCount(): Int

    @Query("SELECT COUNT(*) FROM pending_items WHERE status = 'imported'")
    suspend fun importedCount(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: PendingItem): Long

    @Query("DELETE FROM pending_items WHERE id = :id")
    suspend fun delete(id: Long)

    /** v53：批量改状态（助理中枢批量确认/忽略） */
    @Query("UPDATE pending_items SET status = :status WHERE id IN (:ids)")
    suspend fun batchMarkStatus(ids: List<Long>, status: String)

    /** v53：按来源查（助理中枢按项目/采集来源筛选） */
    @Query("SELECT * FROM pending_items WHERE status = 'pending' AND source = :source ORDER BY createdAt DESC")
    suspend fun pendingBySource(source: String): List<PendingItem>

    /** v53：按来源项目查 */
    @Query("SELECT * FROM pending_items WHERE status = 'pending' AND originProjectId = :projectId ORDER BY createdAt DESC")
    suspend fun pendingByProject(projectId: Long): List<PendingItem>

    /**
     * 2026-11-09：清空**采集来源**的草稿（完整重置采集用）。
     * 只删 source='capture' 的行——项目会话产出的 session 草稿与用户手动加的不动。
     */
    @Query("DELETE FROM pending_items WHERE source = 'capture'")
    suspend fun clearCapture()
}

@Dao
interface WeeklyRecordDao {
    @Query("SELECT * FROM weekly_records ORDER BY weekStart DESC")
    suspend fun allOrdered(): List<WeeklyRecord>

    @Query("SELECT * FROM weekly_records WHERE weekStart = :weekStart ORDER BY createdAt DESC LIMIT 1")
    suspend fun latestOfWeek(weekStart: Long): WeeklyRecord?

    @Insert
    suspend fun insert(record: WeeklyRecord): Long
}

@Dao
interface SettingsDao {
    @Query("SELECT * FROM settings")
    suspend fun all(): List<SettingKV>

    @Query("SELECT value FROM settings WHERE `key` = :key")
    suspend fun get(key: String): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(item: SettingKV)

    @Query("DELETE FROM settings WHERE `key` = :key")
    suspend fun delete(key: String)

    @Query("DELETE FROM settings WHERE `key` LIKE 'capture.full:%'")
    suspend fun clearCaptureFullMarks()
}

@Dao
interface AchievementDao {
    @Query("SELECT * FROM achievements")
    suspend fun all(): List<AchievementRec>

    @Query("SELECT COUNT(*) FROM achievements")
    suspend fun count(): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(rec: AchievementRec)
}

@Dao
interface SleepRecordDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(record: SleepRecord)

    @Query("SELECT * FROM sleep_records ORDER BY dateEpochDay DESC LIMIT 1")
    suspend fun latest(): SleepRecord?

    @Query("SELECT * FROM sleep_records ORDER BY dateEpochDay DESC LIMIT :n")
    suspend fun last(n: Int): List<SleepRecord>

    @Query("SELECT * FROM sleep_records WHERE dateEpochDay >= :since ORDER BY dateEpochDay ASC")
    suspend fun since(since: Long): List<SleepRecord>

    @Query("SELECT * FROM sleep_records WHERE dateEpochDay = :epochDay LIMIT 1")
    suspend fun byDay(epochDay: Long): SleepRecord?

    @Query("SELECT COUNT(*) FROM sleep_records")
    suspend fun count(): Int
}

@Dao
interface NoteAudioDao {
    @Query("SELECT * FROM note_audio WHERE noteId = :noteId ORDER BY createdAt")
    suspend fun ofNote(noteId: Long): List<NoteAudio>

    @Query("SELECT * FROM note_audio")
    suspend fun all(): List<NoteAudio>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(list: List<NoteAudio>)

    @Insert
    suspend fun insert(item: NoteAudio): Long

    @Query("DELETE FROM note_audio WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM note_audio WHERE noteId = :noteId")
    suspend fun deleteOfNote(noteId: Long)
}

@Dao
interface AttachmentDao {
    @Query("SELECT * FROM attachments WHERE ownerKind = :kind AND ownerId = :ownerId ORDER BY createdAt")
    suspend fun ofOwner(kind: String, ownerId: Long): List<Attachment>

    @Query("SELECT * FROM attachments")
    suspend fun all(): List<Attachment>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(list: List<Attachment>)

    @Insert
    suspend fun insert(item: Attachment): Long

    @Query("DELETE FROM attachments WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM attachments WHERE ownerKind = :kind AND ownerId = :ownerId")
    suspend fun deleteOfOwner(kind: String, ownerId: Long)
}
