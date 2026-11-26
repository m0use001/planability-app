package io.github.m0use001.planability.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Update

/** 工作进程清单文件首行标记：识别「这是可打勾的工作进程」 */
const val WORKFLOW_MARKER = "<!-- planability-workflow -->"

// ==================== v8 项目树：项目内子文件夹 ====================
// 项目（Project）= 树根；folders 表存项目内子文件夹（parentId=0 挂项目根）。
// 会话挂 folderId（0 = 直接挂项目根）；子文件夹不持有记忆（memories.projectId 只指项目根）。
// 项目同时映射真实目录 filesDir/projects/<projectId>/，树内"文件"叶子读真实文件。

@Entity(tableName = "folders", indices = [androidx.room.Index("projectId")])
data class Folder(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** 所属项目（必填，文件夹不跨项目） */
    val projectId: Long,
    /** 父文件夹（0 = 项目根） */
    val parentId: Long = 0,
    val name: String,
    val sort: Int = 0,
    val createdAt: Long = 0
)

@Dao
interface FolderDao {
    @Query("SELECT * FROM folders WHERE projectId = :projectId ORDER BY sort, id")
    suspend fun ofProject(projectId: Long): List<Folder>

    @Query("SELECT * FROM folders WHERE id = :id")
    suspend fun byId(id: Long): Folder?

    @Query("SELECT * FROM folders ORDER BY id")
    suspend fun all(): List<Folder>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(f: Folder): Long

    @Update
    suspend fun update(f: Folder)

    @Query("UPDATE folders SET name = :name WHERE id = :id")
    suspend fun rename(id: Long, name: String)

    @Query("DELETE FROM folders WHERE id = :id")
    suspend fun delete(id: Long)

    /** 删除项目时清掉其全部子文件夹（会话先由 Repo 回落到未分类） */
    @Query("DELETE FROM folders WHERE projectId = :projectId")
    suspend fun deleteOfProject(projectId: Long)

    /** 删除文件夹时其下会话上移到父级（或项目根） */
    @Query("UPDATE chat_sessions SET folderId = :newParentId WHERE folderId = :folderId")
    suspend fun liftSessions(folderId: Long, newParentId: Long)

    /** 删除文件夹时其子文件夹上移到父级 */
    @Query("UPDATE folders SET parentId = :newParentId WHERE parentId = :folderId")
    suspend fun liftChildren(folderId: Long, newParentId: Long)

    @Query("SELECT COUNT(*) FROM folders WHERE projectId = :projectId")
    suspend fun countOfProject(projectId: Long): Int
}
