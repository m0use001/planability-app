package io.github.m0use001.planability.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Update

// ==================== v5 项目大体系：文件夹式分组 ====================
// 对话/笔记/记忆/日程/待办全部挂 projectId（0 = 未分类）；
// 文件页本身是真实文件系统目录，天然自成体系，不强行并入。

@Entity(tableName = "projects")
data class Project(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val emoji: String = "📁",
    /** v7 图标统一：material 图标 key（folder/laptop/library/target/science/home/work/terminal） */
    val iconKey: String = "folder",
    val pinned: Boolean = false,
    /**
     * v42：项目绑定的**真实文件夹**（绝对路径，如 /storage/emulated/0/Documents/课题）。
     * 空 = 旧默认（externalFilesDir/synced/projects/<名>__<id>/）。
     * 非空时：项目文件、AI 生成物、对话镜像（data/SessionMirror）全部落在这个真实文件夹里，
     * 项目=AI 的真实工作区（对齐 PC 端「创建项目选源文件夹」的形态）。
     * 绑定后 rename **不再改目录名**、deleteForce **绝不**递归删这个文件夹（那是用户的真实数据）。
     */
    val dirPath: String = "",
    /**
     * 已归档：正常抽屉里不出现，只活在「归档项目」里。
     *
     * 这是「删除项目」的新分流之一：以前只有「删掉」，但项目下还有数据时会被拒绝，
     * 用户只剩「先搬走所有东西」这一条路。归档=把整个项目挪到一个看不见的地方，
     * 等于「先不看了」而不销毁任何数据。
     */
    val archived: Boolean = false,
    val createdAt: Long = 0,
    val updatedAt: Long = 0
)

@Dao
interface ProjectDao {
    @Query("SELECT * FROM projects WHERE archived = 0 ORDER BY pinned DESC, updatedAt DESC")
    suspend fun all(): List<Project>

    /** 归档项目（正常抽屉里看不到，只给「归档项目」用） */
    @Query("SELECT * FROM projects WHERE archived = 1 ORDER BY updatedAt DESC")
    suspend fun archived(): List<Project>

    @Query("UPDATE projects SET archived = :archived, updatedAt = :now WHERE id = :id")
    suspend fun setArchived(id: Long, archived: Boolean, now: Long)

    @Query("SELECT * FROM projects WHERE id = :id")
    suspend fun byId(id: Long): Project?

    @Query("SELECT COUNT(*) FROM projects")
    suspend fun count(): Int

    // ---------- 强制删除：连数据一起清（顺序必须是先子表后父表） ----------
    // 用原生 DELETE 而不是逐个走 Repo：Repo 里都是「软删/进回收站」语义，
    // 而这里要的是"真的没了"。表名见各自的 @Entity(tableName=...)。

    @Query("DELETE FROM chat_messages WHERE sessionId IN (SELECT id FROM chat_sessions WHERE projectId = :id)")
    suspend fun purgeMessages(id: Long)

    @Query("DELETE FROM chat_sessions WHERE projectId = :id")
    suspend fun purgeSessions(id: Long)

    @Query("DELETE FROM notes WHERE projectId = :id")
    suspend fun purgeNotes(id: Long)

    @Query("DELETE FROM memory_history WHERE memoryId IN (SELECT id FROM memories WHERE projectId = :id)")
    suspend fun purgeMemoryHistory(id: Long)

    @Query("DELETE FROM memory_links WHERE fromId IN (SELECT id FROM memories WHERE projectId = :id) OR toId IN (SELECT id FROM memories WHERE projectId = :id)")
    suspend fun purgeMemoryLinks(id: Long)

    @Query("DELETE FROM memories WHERE projectId = :id")
    suspend fun purgeMemories(id: Long)

    @Query("DELETE FROM schedules WHERE projectId = :id")
    suspend fun purgeSchedules(id: Long)

    @Query("DELETE FROM todos WHERE projectId = :id")
    suspend fun purgeTodos(id: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(p: Project): Long

    @Update
    suspend fun update(p: Project)

    @Query("UPDATE projects SET pinned = :pinned, updatedAt = :now WHERE id = :id")
    suspend fun setPinned(id: Long, pinned: Boolean, now: Long)

    @Query("DELETE FROM projects WHERE id = :id")
    suspend fun delete(id: Long)

    /** 删除项目时其下数据回落未分类 */
    @Query("UPDATE chat_sessions SET projectId = 0 WHERE projectId = :id")
    suspend fun detachSessions(id: Long)

    @Query("UPDATE notes SET projectId = 0 WHERE projectId = :id")
    suspend fun detachNotes(id: Long)

    @Query("UPDATE memories SET projectId = 0 WHERE projectId = :id")
    suspend fun detachMemories(id: Long)

    @Query("UPDATE schedules SET projectId = 0 WHERE projectId = :id")
    suspend fun detachSchedules(id: Long)

    @Query("UPDATE todos SET projectId = 0 WHERE projectId = :id")
    suspend fun detachTodos(id: Long)

    // ---------- 项目详情聚合查询 ----------

    @Query("SELECT * FROM chat_sessions WHERE projectId = :id ORDER BY updatedAt DESC")
    suspend fun sessionsOf(id: Long): List<ChatSession>

    @Query("SELECT * FROM notes WHERE projectId = :id AND trashed = 0 ORDER BY updatedAt DESC")
    suspend fun notesOf(id: Long): List<NoteEntity>

    @Query("SELECT * FROM memories WHERE projectId = :id AND isDeleted = 0 ORDER BY updatedAt DESC")
    suspend fun memoriesOf(id: Long): List<MemoryEntity>

    @Query("SELECT * FROM schedules WHERE projectId = :id AND trashed = 0 ORDER BY start DESC LIMIT 30")
    suspend fun schedulesOf(id: Long): List<Schedule>

    @Query("SELECT * FROM todos WHERE projectId = :id AND trashed = 0 ORDER BY date DESC LIMIT 30")
    suspend fun todosOf(id: Long): List<Todo>
}
