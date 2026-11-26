package io.github.m0use001.planability.data

import java.io.File

/**
 * 2026-10-29：**项目数据容器的唯一定义**（规则见
 * `◆/planability-project-file-storage.md`）。
 *
 * ## 一句话规则
 * app 只允许写 [data] 里面；[root] 是用户看得见、能自己往里放东西的那一层，绑定项目时
 * 它是一整个真实文件夹（`projects.dirPath`），app 碰它里面的其它东西就是灾难。
 *
 * ## 为什么要把"项目根"和"项目数据"分开
 * v42 允许项目绑定用户真实文件夹，而 app 之前把 `对话/`、`新对话__284/`、`项目文件/`
 * 直接铺在这个真实文件夹里，项目一多，用户的文件树就再也分不清哪是自己建的。
 * 现在所有落点都经过这里，四个固定子目录之外不允许出现任何 app 生成的条目。
 *
 * ## 落点
 * | 项目类型 | [root] | [data] |
 * |---|---|---|
 * | 绑定真实文件夹 | `projects.dirPath` | `<root>/项目数据` |
 * | 未绑定（app 自建） | `synced/projects/<名>__<id>` | **= root**（这目录本来就只归 app，不再套一层） |
 * | 未分类（0） | `synced/projects/_global` | **= root** |
 */
object ProjectDirs {

    /** app 数据容器（绑定项目才多这一层） */
    const val DATA = "项目数据"
    /** 会话产物 + 对话镜像；归档后整包搬到 [ARCHIVED] */
    const val SESSIONS = "当前会话"
    const val ARCHIVED = "已归档会话"
    /** 记忆的单向导出（DB 是唯一真源） */
    const val MEMORY = "项目记忆"
    /** 用户显式导入 / AI 主动写的长期文件 / 工作进程 / 项目内子文件夹 */
    const val FILES = "项目文件"

    /** 会话镜像的固定文件名 */
    const val MIRROR = "对话.md"

    /** 会话目录名的标题截断长度：带上 `__<id>` 后仍远低于 255 字节，避免 Windows 同步路径过长 */
    private const val TITLE_MAX = 30

    // ---------- 三层路径 ----------

    /** 项目根（用户看得见的那层；未分类 = `_global`） */
    fun root(projectId: Long): File =
        if (projectId <= 0L) File(ProjectFiles.root(), "_global")
        else Graph.folders.projectDir(projectId)

    /**
     * app 数据容器。
     *
     * 绑定判断取 [ProjectFiles.dirPathOf]（随 [ProjectFiles.refreshProjectNames] 刷新的同步快照）：
     * 拿不到缓存时按"未绑定"处理会写到 `synced/projects/` 下，而不是往用户文件夹里乱写——
     * 宁可文件落在旧位置（[ProjectFiles.root]，仍可同步），也不要多造一个用户看不懂的目录。
     */
    fun data(projectId: Long): File {
        if (projectId <= 0L) return root(0L)
        val r = root(projectId)
        return if (ProjectFiles.dirPathOf(projectId).isNullOrBlank()) r else File(r, DATA)
    }

    /** 四个固定子目录（不存在就建；[ensureStructure] 的单项版本） */
    fun sub(projectId: Long, name: String): File {
        val d = File(data(projectId), name)
        if (!d.isDirectory) d.mkdirs()
        return d
    }

    fun sessionsDir(projectId: Long, archived: Boolean = false): File =
        File(data(projectId), if (archived) ARCHIVED else SESSIONS)

    fun filesDir(projectId: Long): File = File(data(projectId), FILES)

    fun memoryDir(projectId: Long): File = File(data(projectId), MEMORY)

    /**
     * 幂等建出四个子目录并返回数据容器。
     *
     * 为什么提前建空目录：Syncthing 的文件夹监视器对"新建目录"有一轮延迟，
     * 预先建好能让电脑端立刻看见结构；空目录不影响任何列表（列表只列文件）。
     * 绑定项目的真实文件夹不可写时返回的目录写不进去，调用方按 null/失败处理。
     */
    fun ensureStructure(projectId: Long): File? {
        val d = data(projectId)
        if (!d.isDirectory && !d.mkdirs()) return null
        listOf(SESSIONS, ARCHIVED, MEMORY, FILES).forEach { n ->
            val s = File(d, n)
            if (!s.isDirectory) s.mkdirs()
        }
        return d
    }

    /**
     * 启动时给所有项目（含未分类）把四子目录备齐，并把会话目录对齐库里的归档状态
     * （幂等；Syncthing 电脑端也能立刻看见结构）。
     *
     * 为什么要在启动时对账：归档搬迁是 best-effort（可能被占用/权限/进程被杀打断），
     * 库才是真相。启动时按库把目录挪回去，崩溃中断留下的"归档了但文件还在当前会话"
     * 会自愈，不会越积越乱。
     */
    suspend fun ensureAll() = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        runCatching { ensureStructure(0L) }
        runCatching {
            Graph.projects.all().forEach { p ->
                if (p.archived) return@forEach
                ensureStructure(p.id)
            }
        }
        runCatching {
            Graph.chat.sessions().forEach { s ->
                moveSession(s.projectId, s.id, s.title.ifBlank { "会话" }, s.archived)
            }
        }
    }

    // ---------- 会话目录 ----------

    /** `<标题>__<会话id>`；标题截断 30 字，空标题退化成「对话」 */
    fun sessionDirName(title: String, sessionId: Long): String {
        val safe = title.trim()
            .replace(Regex("[\\\\/:*?\"<>|]"), "_")
            .take(TITLE_MAX)
            .ifBlank { "对话" }
        return "${safe}__$sessionId"
    }

    /** 目录名里的会话 id（不是会话目录返回 null） */
    fun sessionIdOf(dirName: String): Long? {
        val i = dirName.lastIndexOf("__")
        return if (i < 0) null else dirName.substring(i + 2).toLongOrNull()
    }

    fun sessionDir(projectId: Long, sessionId: Long, title: String, archived: Boolean): File =
        File(sessionsDir(projectId, archived), sessionDirName(title, sessionId))

    /**
     * 取（必要时建）某个会话的目录，并把"标题改过名"的旧目录自愈过来。
     *
     * 标题是目录名的一部分，而标题会被首条消息自动改写；不自愈的话同一个会话的文件
     * 会留在旧标题目录里、列表里再也列不出来（v41 真机踩过）。
     * 自愈同时跨「当前会话 / 已归档会话」：归档状态变了就整包搬过去，而不是留两份。
     */
    fun ensureSessionDir(
        projectId: Long,
        sessionId: Long,
        title: String,
        archived: Boolean,
        create: Boolean = true
    ): File? {
        val target = sessionDir(projectId, sessionId, title, archived)
        if (target.isDirectory) return target
        // 旧标题 / 另一侧（当前 ↔ 归档）的同 id 目录
        val stale = findSessionDirs(projectId, sessionId).firstOrNull()
        if (stale != null) {
            if (!target.parentFile!!.isDirectory && !target.parentFile!!.mkdirs()) return null
            if (runCatching { stale.renameTo(target) }.getOrDefault(false)) return target
            // 跨卷/被占用时退化成逐个搬文件
            if (moveChildren(stale, target)) {
                runCatching { stale.delete() }
                return target
            }
        }
        if (!create) return null
        val parent = target.parentFile!!
        if (!parent.isDirectory && !parent.mkdirs()) return null
        return if (target.mkdirs()) target else if (target.isDirectory) target else null
    }

    /** 该项目下这个会话的所有现存目录（当前 + 归档 + 任意旧标题） */
    fun findSessionDirs(projectId: Long, sessionId: Long): List<File> =
        listOf(false, true).flatMap { archived ->
            sessionsDir(projectId, archived)
                .listFiles { f -> f.isDirectory && sessionIdOf(f.name) == sessionId }
                ?.toList().orEmpty()
        }

    /** 归档 / 取消归档：整包在 当前会话 ⇄ 已归档会话 之间移动（best-effort） */
    fun moveSession(projectId: Long, sessionId: Long, title: String, archived: Boolean): Boolean {
        if (findSessionDirs(projectId, sessionId).isEmpty()) return false   // 没落盘过，不用搬
        val target = ensureSessionDir(projectId, sessionId, title, archived, create = false) ?: return false
        val wantParent = sessionsDir(projectId, archived).canonicalPath
        return target.parentFile?.canonicalPath == wantParent
    }

    /** 删会话目录（真删；会话数据在库里，这里只是它的产物） */
    fun deleteSession(projectId: Long, sessionId: Long) {
        findSessionDirs(projectId, sessionId).forEach { f ->
            runCatching { deleteRecursive(f) }
        }
    }

    private fun moveChildren(from: File, to: File): Boolean {
        if (!to.isDirectory && !to.mkdirs()) return false
        var ok = true
        from.listFiles()?.forEach { f ->
            val dest = File(to, f.name)
            val moved = if (dest.exists()) true else f.renameTo(dest)
            if (!moved) ok = false
        }
        return ok
    }

    fun deleteRecursive(f: File) {
        if (f.isDirectory) f.listFiles()?.forEach { deleteRecursive(it) }
        f.delete()
    }

    /** [f] 是否在 app 数据容器内（删除/写入前的越界闸） */
    fun isInsideData(projectId: Long, f: File): Boolean = runCatching {
        val base = data(projectId).canonicalPath
        val p = f.canonicalPath
        p == base || p.startsWith(base + File.separator)
    }.getOrDefault(false)

    /**
     * 把路径转成相对 `项目数据` 的路径（不在里面返回 null）。
     * 文件库用它判断"这个条目归不归 app 管"：归才允许改名/删除/编辑。
     */
    fun relToData(projectId: Long, f: File): String? {
        val base = runCatching { data(projectId).canonicalPath }.getOrNull() ?: return null
        val p = runCatching { f.canonicalPath }.getOrNull() ?: return null
        if (p == base) return ""
        if (!p.startsWith(base + File.separator)) return null
        return p.removePrefix(base + File.separator).replace(File.separatorChar, '/')
    }
}
