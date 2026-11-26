package io.github.m0use001.planability.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query

/**
 * v49 知识库：notes 的 FTS 外部内容虚表（标题路 / 正文路分开召回）。
 *
 * 与 `memories_fts` 同构（MIGRATION_5_6）：content=notes，rowid 对齐 `notes.id`，
 * 触发器由 Room 在 `onPostMigrate` 自动建（room_fts_content_sync_notes_fts_*），
 * 所以迁移里只建虚表、不手写触发器。
 */
@Fts4(contentEntity = NoteEntity::class)
@Entity(tableName = "notes_fts")
data class NotesFts(
    val title: String,
    val content: String
)

/**
 * v49 知识库：`[[标题]]` 链接图（借鉴思源/记忆 MemoryLink）。
 *
 * - `toId > 0`：已解析到本库某条笔记；
 * - `toId = 0`：悬空引用（目标笔记还没建/被删），保留 `target` 原文，
 *   等目标标题出现时由 [NoteKbDao.resolveLinks] 回填。
 */
@Entity(tableName = "note_links", indices = [Index("fromId"), Index("toId")])
data class NoteLink(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val fromId: Long,
    val toId: Long,
    /** 未解析目标的原始标题（已归一化：去空格、小写比较） */
    val target: String = "",
    val at: Long = 0
)

@Dao
interface NoteKbDao {
    // ---------- 双路 FTS（标题 / 正文） ----------
    @Query(
        "SELECT notes.* FROM notes JOIN notes_fts ON notes.rowid = notes_fts.rowid " +
            "WHERE notes_fts MATCH :match AND notes.trashed = 0 LIMIT :limit"
    )
    suspend fun ftsSearch(match: String, limit: Int = 40): List<NoteEntity>

    /** 中文退化路径（FTS4 不分词；CJK 二元组匹配不到时用 LIKE 兜底） */
    @Query(
        "SELECT * FROM notes WHERE trashed = 0 AND " +
            "(title LIKE '%'||:t||'%' OR content LIKE '%'||:t||'%') LIMIT :limit"
    )
    suspend fun likeSearch(t: String, limit: Int = 40): List<NoteEntity>

    // ---------- 链接图 ----------
    @Insert
    suspend fun insertLinks(list: List<NoteLink>)

    @Query("DELETE FROM note_links WHERE fromId = :fromId")
    suspend fun clearLinks(fromId: Long)

    @Query("SELECT * FROM note_links WHERE fromId = :fromId")
    suspend fun outLinks(fromId: Long): List<NoteLink>

    @Query("SELECT * FROM note_links WHERE toId = :toId")
    suspend fun inLinks(toId: Long): List<NoteLink>

    @Query("SELECT * FROM notes WHERE trashed = 0 AND title = :title COLLATE NOCASE LIMIT 1")
    suspend fun byTitle(title: String): NoteEntity?

    /** 目标笔记出现后，把之前悬空的引用回填成真链接（按标题，大小写不敏感） */
    @Query("UPDATE note_links SET toId = :toId, target = '' WHERE toId = 0 AND target = :title COLLATE NOCASE")
    suspend fun resolveLinks(title: String, toId: Long): Int

    /** 笔记被删/改名时，把指向它的链接退回悬空态（target 用新标题或旧标题） */
    @Query("UPDATE note_links SET toId = 0, target = :title WHERE toId = :toId")
    suspend fun detachLinks(toId: Long, title: String): Int

    @Query("SELECT DISTINCT target FROM note_links WHERE toId = 0 AND target != ''")
    suspend fun danglingTargets(): List<String>

    // ---------- 2026-11-07 P0-1 用户搜索框：笔记本内检索（列表行投影） ----------

    @Query(
        "SELECT notes.id AS id, notes.notebookId AS notebookId, notes.title AS title, notes.kind AS kind, " +
            "notes.createdAt AS createdAt, notes.updatedAt AS updatedAt, notes.trashed AS trashed, " +
            "notes.trashedAt AS trashedAt, notes.pinned AS pinned, notes.projectId AS projectId, notes.tags AS tags, " +
            "substr(notes.content, 1, 240) AS preview, LENGTH(notes.content) AS size " +
            "FROM notes JOIN notes_fts ON notes.rowid = notes_fts.rowid " +
            "WHERE notes_fts MATCH :match AND notes.trashed = 0 AND notes.notebookId = :notebookId LIMIT :limit"
    )
    suspend fun ftsRowsInNotebook(match: String, notebookId: Long, limit: Int): List<NoteRow>

    /** 中文退化路径：FTS 二元组匹配不到时按原串 LIKE（投影同上） */
    @Query(
        "SELECT id, notebookId, title, kind, createdAt, updatedAt, trashed, trashedAt, pinned, projectId, tags, " +
            "substr(content, 1, 240) AS preview, LENGTH(content) AS size FROM notes " +
            "WHERE trashed = 0 AND notebookId = :notebookId AND " +
            "(title LIKE '%'||:t||'%' OR content LIKE '%'||:t||'%') LIMIT :limit"
    )
    suspend fun likeRowsInNotebook(t: String, notebookId: Long, limit: Int): List<NoteRow>
}
