package io.github.m0use001.planability.data

import io.github.m0use001.planability.core.AppTime

// M5 KMP 化：Project 实体 + ProjectDao 已迁 `:shared`（Room 真源），同包跨模块直解。
// 本文件只留 ProjectRepo（ProjectFiles/ProjectDirs/Graph/IO）。

class ProjectRepo(
    private val dao: ProjectDao,
    private val folderDao: FolderDao,
    private val version: MutableVersion
) {

    suspend fun all(): List<Project> = dao.all()

    suspend fun byId(id: Long): Project? = if (id <= 0) null else dao.byId(id)

    suspend fun create(name: String, iconKey: String = "folder", dirPath: String = ""): Long {
        val now = AppTime.nowMillis()
        val id = dao.insert(
            Project(
                name = name.trim(), iconKey = iconKey,
                dirPath = dirPath.trim(),
                createdAt = now, updatedAt = now
            )
        )
        // v19.62：目录名走 ProjectFiles.nameCache（同步读，给非 suspend 的路径拼目录用）。
        // 新建项目不刷新它，**第一条对话的目录会退化成 "projects/<id>/"**（真机实测：
        // projects/1/新对话__1 与 projects/WorkProj__1/ 并存，前者在文件库里看不到）。
        // rename 一直有刷新，create 这里补上。
        ProjectFiles.refreshProjectNames()
        // 2026-11-01 用户：「新建了项目但对应文件夹没有立刻新建」——「项目数据」四子目录
        // 原来只在启动时 ensureAll() 建一次，运行中新建的项目要等下一次冷启动才出现，
        // 文件库和电脑端 Syncthing 面板都看不到结构。这里立刻备齐：放在 refreshProjectNames
        // 之后，绑定项目的落点此刻才是准的；目录建不出（绑定路径不可写）按失败静默，
        // 与 ensureAll 的口径一致，下次启动还会对账。
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching { ProjectDirs.ensureStructure(id) }
        }
        version.bump()
        return id
    }

    suspend fun rename(id: Long, name: String, iconKey: String? = null) {
        dao.byId(id)?.let { p ->
            dao.update(p.copy(name = name.trim(), iconKey = iconKey ?: p.iconKey, updatedAt = AppTime.nowMillis()))
            // v24：目录名带项目名，重命名必须连目录一起改，否则名字和目录就漂了
            ProjectFiles.renameProjectDir(id, p.name, name.trim())
            ProjectFiles.refreshProjectNames()
            version.bump()
        }
    }

    suspend fun setPinned(id: Long, pinned: Boolean) {
        dao.setPinned(id, pinned, AppTime.nowMillis())
        version.bump()
    }

    /** 归档项目 = 从正常抽屉里消失，数据一字不动 */
    suspend fun setArchived(id: Long, archived: Boolean) {
        dao.setArchived(id, archived, AppTime.nowMillis())
        version.bump()
    }

    suspend fun archivedAll(): List<Project> = dao.archived()

    /**
     * **强制删除**：项目行 + 其下对话/消息/笔记/记忆/文件夹/项目目录。
     *
     * ⚠️ 2026-10-21 真事故：用户删掉一个（当初为测试建的）项目，**整个课表跟着没了**——
     * 因为课表导入时把 13 条课程日程挂在了那个项目下，而这里原来是 `purgeSchedules`。
     * 日程/待办是**日历数据**，不属于"项目的内容"，删项目不该把它们一起带走。
     * 现在改成 **detach**（转成全局），只删真正属于项目的东西。
     *
     * 与 [delete] 的区别：那个是"有数据就拒绝"，这个是不问、全清（对话/笔记/记忆）。
     * 调用方必须先让用户做一次显式选择（抽屉里是二选一弹层），并且**先落一份整库快照**。
     */
    suspend fun deleteForce(id: Long) {
        // 先删子表，再删父表；倒过来会留下没有归属的消息/历史
        dao.purgeMessages(id)
        dao.purgeSessions(id)
        dao.purgeNotes(id)
        dao.purgeMemoryHistory(id)
        dao.purgeMemoryLinks(id)
        dao.purgeMemories(id)
        // 日程与待办：**保留**，转为全局（projectId = 0）
        dao.detachSchedules(id)
        dao.detachTodos(id)
        folderDao.deleteOfProject(id)
        // 真实目录：删目录记录不等于删文件。
        // v42：绑定了用户真实文件夹的项目**绝不**递归删——那是用户自己的数据。
        // 2026-10-29 收口：app 数据已全部收进「项目数据」（[ProjectDirs]），
        // 两种项目统一只删这一个容器——绑定项目删 <真实文件夹>/项目数据，
        // 未绑定项目删它自己的项目目录（=数据容器），容器之外的用户文件一律不碰。
        // 2026-11-16 用户「彻底删除项目有延迟」：这里原来是**裸调** deleteRecursively()，
        // 而 deleteForce 的调用方是 rememberCoroutineScope()（Main）——递归删目录在主线程
        // 上跑，项目里的会话目录/项目文件越多越久，整帧卡住，弹窗早关了但行还在。
        // 改到 IO（与 ChatEntities 删单个会话的正确写法同款：281-295 行）。
        //
        // 对照实测（模拟器 plan36，同一份 3.5GB / 14400 文件数据集）：
        // 旧写法 → logcat `Skipped 83 frames!`（主线程冻结 ~1.4 秒），
        //         删除期间连续 5 帧截图像素完全一致（转圈不转，画面死住）；
        // 改 IO  → 零掉帧、零 ANR，每帧截图都不同（UI 活着）。
        //
        // 同日第二刀（归档项目删完留孤儿目录）：**不能**再用 ProjectDirs.data(id) 拼路径——
        // 它经 projectDir(id) → 名字缓存，而 refreshProjectNames() 的 DAO 是
        // `WHERE archived = 0`，归档项目永远进不了缓存 → 名字为空 → 退化成纯数字目录名
        // `7` → 删掉一个不存在的路径，真正的 ArchivedReal__7 原封不动留在磁盘（实测 612M）。
        // 改成按 projectIdOfDirName 扫 root 反查：命名目录 / 纯数字 legacy 目录 /
        // 改名残留全都能命中，且不依赖任何缓存状态；绑定项目仍只删自己的「项目数据」容器。
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                // ① root 下所有映射到这个 id 的目录（app 自己的容器，容器外一律不碰）
                ProjectFiles.root().listFiles()?.forEach { d ->
                    if (d.isDirectory && ProjectFiles.projectIdOfDirName(d.name) == id) {
                        d.deleteRecursively()
                    }
                }
                // ② 绑定项目：只删 <真实文件夹>/项目数据，用户文件夹本体绝不碰
                dao.byId(id)?.dirPath?.takeIf { it.isNotBlank() }?.let { p ->
                    java.io.File(p, ProjectDirs.DATA).deleteRecursively()
                }
            }
        }
        dao.delete(id)
        ProjectFiles.refreshProjectNames()
        version.bump()
    }

    /**
     * 删除项目（v19.62 严格项目制）。
     *
     * **不再有"回落未分类"这回事**——「未分类」已在信息界面废除，回落后那些会话/笔记
     * 会从所有分组里消失（数据还在库里，但没有任何入口能看到它，等于看不见的丢失）。
     * 所以改成：项目下还有数据时**拒绝删除**，由调用方提示用户先移走。
     *
     * @return true = 已删除；false = 拒绝（项目下还有会话/笔记/记忆/日程/待办）
     */
    suspend fun delete(id: Long): Boolean {
        // 日程/待办不再算"项目下的数据"：它们已经与项目解耦（2026-10-21 事故后）
        val hasData = sessionsOf(id).isNotEmpty() || notesOf(id).isNotEmpty() ||
            memoriesOf(id).isNotEmpty()
        if (hasData) return false
        // 只有空项目才走到这里。detach 仍然保留：万一有别旁路写入，至少不留下悬空引用。
        dao.detachSessions(id)
        dao.detachNotes(id)
        dao.detachMemories(id)
        dao.detachSchedules(id)
        dao.detachTodos(id)
        folderDao.deleteOfProject(id)
        dao.delete(id)
        version.bump()
        return true
    }

    suspend fun sessionsOf(id: Long): List<ChatSession> = dao.sessionsOf(id)
    suspend fun notesOf(id: Long): List<NoteEntity> = dao.notesOf(id)
    suspend fun memoriesOf(id: Long): List<MemoryEntity> = dao.memoriesOf(id)
    suspend fun schedulesOf(id: Long): List<Schedule> = dao.schedulesOf(id)
    suspend fun todosOf(id: Long): List<Todo> = dao.todosOf(id)
}
