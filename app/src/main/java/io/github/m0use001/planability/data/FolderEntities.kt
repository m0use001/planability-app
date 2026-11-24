package io.github.m0use001.planability.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Update
import io.github.m0use001.planability.core.AppTime
import java.io.File

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

class FolderRepo(private val dao: FolderDao, private val version: MutableVersion) {

    suspend fun ofProject(projectId: Long): List<Folder> =
        if (projectId <= 0) emptyList() else dao.ofProject(projectId)

    suspend fun all(): List<Folder> = dao.all()

    suspend fun byId(id: Long): Folder? = if (id <= 0) null else dao.byId(id)

    suspend fun create(projectId: Long, parentId: Long, name: String): Long {
        val id = dao.insert(
            Folder(projectId = projectId, parentId = parentId, name = name.trim(), createdAt = AppTime.nowMillis())
        )
        // v19.7：项目下的子文件夹同时是真实目录（软件里的文件树 = 手机里的文件树）
        folderDir(projectId, name.trim())
        version.bump()
        return id
    }

    suspend fun rename(id: Long, name: String) {
        if (name.isNotBlank()) {
            val old = dao.byId(id)
            dao.rename(id, name.trim())
            // 同步真实目录名
            if (old != null) {
                val from = folderDir(old.projectId, old.name, create = false)
                val to = folderDir(old.projectId, name.trim(), create = false)
                if (from != null && to != null && from.exists() && !to.exists()) from.renameTo(to)
            }
            version.bump()
        }
    }

    /** 删除文件夹：子文件夹与其下会话上移到父级（不删数据）；v19.7 连带删除真实目录与其内容 */
    suspend fun delete(id: Long) {
        val f = dao.byId(id) ?: return
        dao.liftChildren(id, f.parentId)
        dao.liftSessions(id, f.parentId)
        dao.delete(id)
        folderDir(f.projectId, f.name, create = false)?.deleteRecursively()
        version.bump()
    }

    /** 删除项目时调用：其下会话已由 ProjectRepo 回落未分类，这里清文件夹记录 */
    suspend fun deleteOfProject(projectId: Long) {
        dao.deleteOfProject(projectId)
        version.bump()
    }

    // ---------- 项目 = 真实目录（externalFilesDir/synced/projects/<projectId>/） ----------

    /**
     * v24：项目目录落点从 `filesDir/projects/` 改为 `externalFilesDir/synced/projects/`。
     *
     * 两个原因：
     * ①**统一**：原来这里（附件/工作进程/子文件夹）和 [ProjectFileRepo] 各管一套，
     *    同一个项目两个目录。合并到一处后，"项目文件"只有一个真相。
     * ②**可同步**：`synced/` 是用户**已经配进 Syncthing** 的目录（大学文件 / Planability 数据），
     *    放这里项目文件天生就能同步，不用再教用户配一次。
     * 代价是卸载即删，所以 [ProjectFiles] 里有未同步提醒。
     */
    fun projectDir(projectId: Long): File {
        // v42：项目显式绑定了真实文件夹 → 直接用它（项目=用户的真实工作区，
        // AI 生成物与对话镜像 data/SessionMirror 都落在这里）。缓存随 refreshProjectNames 刷新。
        ProjectFiles.dirPathOf(projectId)?.takeIf { it.isNotBlank() }?.let { return File(it) }
        val safe = ProjectFiles.projectNameOf(projectId)
            .replace(Regex("[\\\\/:*?\"<>|]"), "_").take(40)
        // 目录名带项目名：它会出现在手机文件管理界面和 Syncthing 面板里，
        // 纯数字 id 用户完全看不懂。名字在前 + id 在后，重名也不会撞。
        val dirName = if (safe.isBlank()) projectId.toString() else "${safe}__$projectId"
        return File(ProjectFiles.root(), dirName)
    }

    /**
     * 项目重命名时把目录一起改名 → 实现在 [ProjectFiles.renameProjectDir]
     * （FolderRepo 是 class，静态调用要走 object，所以实现放在那边）。
     */
    fun renameProjectDir(projectId: Long, oldName: String, newName: String) =
        ProjectFiles.renameProjectDir(projectId, oldName, newName)

    /**
     * 目录名 → projectId（不是项目目录返回 null）。兼容老的纯数字目录名。
     *
     * 放在这里（而不是 ProjectFiles）是因为它和 projectDir 成对；
     * 但 FolderRepo 是 class、拿不到静态引用，所以真正的实现在
     * [ProjectFiles.projectIdOfDirName]，这里只做转发便于同文件阅读。
     */
    fun projectIdOfDirName(dirName: String): Long? = ProjectFiles.projectIdOfDirName(dirName)

    /** 惰性创建「项目数据」结构；返回数据容器 File（失败返回 null） */
    fun ensureDir(projectId: Long): File? {
        if (projectId <= 0) return null
        return ProjectDirs.ensureStructure(projectId)
    }

    /** 「项目文件」子目录里的真实文件（不含子目录，按名排序） */
    fun listProjectFiles(projectId: Long): List<File> {
        val d = ProjectDirs.filesDir(projectId)
        if (!d.isDirectory) return emptyList()
        return d.listFiles { f -> f.isFile && !f.name.startsWith(".") }
            ?.sortedWith(compareBy({ it.name.lowercase() }))
            ?: emptyList()
    }

    /** 删除「项目文件」里的一个文件（长按菜单用）；返回是否删掉 */
    fun deleteProjectFile(projectId: Long, file: File): Boolean {
        val dir = ProjectDirs.filesDir(projectId)
        // 只允许删「项目文件」里的文件，防止传进来别处的 File（比如用户自己的）
        if (file.parentFile?.canonicalPath != dir.canonicalPath) return false
        val ok = runCatching { file.delete() }.getOrDefault(false)
        if (ok) version.bump()
        return ok
    }

    /** 重命名「项目文件」里的文件；重名/非法名返回 false */
    fun renameProjectFile(projectId: Long, file: File, newName: String): Boolean {
        val dir = ProjectDirs.filesDir(projectId)
        if (file.parentFile?.canonicalPath != dir.canonicalPath) return false
        val clean = newName.trim().replace('/', '_').replace('\\', '_')
        if (clean.isBlank() || clean == file.name) return false
        val target = File(dir, clean)
        if (target.exists()) return false
        val ok = runCatching { file.renameTo(target) }.getOrDefault(false)
        if (ok) version.bump()
        return ok
    }

    /**
     * 把外部文件**复制**进「项目文件」（2026-10-29 定案：原件一动不动，剪切永不使用）；
     * 重名追加 `-2`/`-3`（可读，不再用时间戳）。返回相对 "projects/<id>/<name>" 失败返回 null。
     */
    fun addFileToProject(projectId: Long, displayName: String, content: ByteArray): String? {
        val dir = ProjectDirs.filesDir(projectId)
        if (!dir.isDirectory && !dir.mkdirs()) return null
        var base = displayName.trim().ifBlank { "file" }
        base = base.replace('/', '_').replace('\\', '_')
        var name = base
        var i = 1
        while (File(dir, name).exists()) {
            val dot = base.lastIndexOf('.')
            name = if (dot > 0) base.substring(0, dot) + "-" + (i + 1) + base.substring(dot)
            else base + "-" + (i + 1)
            if (++i > 99) break
        }
        val out = File(dir, name)
        return try {
            out.writeBytes(content)
            version.bump()
            "projects/" + projectId + "/" + name
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 相对路径 "projects/<id>/<name>" → File（防穿越）。
     *
     * 2026-10-29 起物理落点是「项目数据/项目文件/」，但库里存的 rel 不变（当 id 用），
     * 按候选顺序找：项目文件 → 项目数据 → 旧两代位置（v42 绑定根、v24 synced/projects、v14 filesDir）。
     * 每代都留着，历史工作进程清单才不会一夜之间"打不开"。
     */
    fun resolveProjectFile(rel: String): File? {
        val t = rel.trim().removePrefix("/").replace("..", "")
        if (!t.startsWith("projects/")) return null
        val rest = t.removePrefix("projects/")
        val slash = rest.indexOf('/')
        if (slash <= 0) return null
        val pid = rest.substring(0, slash).toLongOrNull() ?: return null
        val name = rest.substring(slash + 1)
        if (name.isBlank()) return null
        val candidates = listOf(
            File(ProjectDirs.filesDir(pid), name),
            File(ProjectDirs.data(pid), name),
            File(ProjectDirs.root(pid), name),                 // v42：绑定项目曾把导入文件放项目根
            File(ProjectFiles.root(), rest),                   // v24：synced/projects/<id>/<name>
            File(Graph.appContext.filesDir, t)                 // v14：filesDir/projects/<id>/<name>
        )
        return candidates.firstOrNull { it.exists() && it.isFile }
    }

    // ---------- v19.7 工作进程（AI 计划 → 项目文件夹里的真实清单文件） ----------

    /** 工作进程清单文件首行标记：识别「这是可打勾的工作进程」（顶层常量 WORKFLOW_MARKER） */
    val WORKFLOW_MARKER = io.github.m0use001.planability.data.WORKFLOW_MARKER

    /**
     * 项目内子文件夹（folders 表）对应的真实目录。
     * 2026-10-29：落点从项目根改到「项目数据/项目文件/」——子文件夹是 app 建的，不能铺在
     * 用户的真实工作文件夹里；收进项目文件后 Syncthing 电脑端也只看到一棵规整的树。
     */
    fun folderDir(projectId: Long, folderName: String, create: Boolean = true): File? {
        val data = if (create) ensureDir(projectId) ?: return null else ProjectDirs.data(projectId)
        val f = File(data, ProjectDirs.FILES + "/" + folderName.trim().replace('/', '_').replace('\\', '_'))
        if (f.isDirectory) return f
        return if (create && f.mkdirs()) f else null
    }

    // 2026-10-31：writeWorkPlan 随 work_plan_start 工具一起删除（用户拍板删掉"AI 主动开工"
    // 那条线）。清单文件格式（WORKFLOW_MARKER + `- [ ]`）与文件预览里的可打勾渲染**保留**，
    // 历史清单照常能打开、能勾。

    /** 打勾/取消：按清单行序（0 起，仅数 "- [ ] / - [x]" 行）改写真实文件；成功返回 true */
    fun setChecklistChecked(rel: String, index: Int, checked: Boolean): Boolean {
        val f = resolveProjectFile(rel) ?: return false
        return try {
            val lines = f.readLines().toMutableList()
            var seen = -1
            var hit = -1
            for (i in lines.indices) {
                val t = lines[i].trimStart()
                if (t.startsWith("- [ ]") || t.startsWith("- [x]") || t.startsWith("- [X]")) {
                    seen++
                    if (seen == index) { hit = i; break }
                }
            }
            if (hit < 0) return false
            val t = lines[hit].trimStart()
            val body = t.substring(5).trim()
            lines[hit] = (if (checked) "- [x] " else "- [ ] ") + body
            f.writeText(lines.joinToString("\n"))
            version.bump()
            true
        } catch (e: Exception) {
            false
        }
    }
}
