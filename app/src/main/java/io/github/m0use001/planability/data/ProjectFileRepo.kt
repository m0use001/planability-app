package io.github.m0use001.planability.data

import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * 项目文件的内存投影（不是 Room 实体）。
 *
 * 字段与被删掉的旧 `project_files` 表**完全一致**，这样 UI 和 4 个 AI 工具
 * （file_library_list/read/write/delete）一行都不用改 —— 只是数据来源从 SQL 变成了磁盘。
 */
data class ProjectFile(
    /** 由 [ProjectFiles.idOf] 按文件名生成；新建时传 0 即可 */
    val id: Long = 0,
    val projectId: Long,
    val name: String,
    /** text / markdown / binary / dir（沿用旧口径，AI 工具会读） */
    val kind: String = "text",
    val content: String = "",
    val size: Int = 0,
    val trashed: Boolean = false,
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
    /**
     * 所在子目录的相对路径（`笔记/2026` 这样，`""` = 项目根）。
     *
     * **必须单列一个字段、不能塞进 [name]**：`name` 是 AI 的 `file_library_*` 工具
     * 用来精确匹配的键，动它就是改跨端契约。而 [id] 要跨子目录唯一
     * （两个目录各有一个 `笔记.md`），所以 id 改用 `sub + "/" + name` 生成。
     */
    val sub: String = ""
)

/**
 * v24：项目文件 —— **真文件**（不是数据库表）。
 *
 * ## 为什么要推翻原来的表设计
 * 原来 `project_files` 是一张 Room 表，而 [FolderRepo] 又在同一批项目下维护了真实的
 * `projects/<id>/` 目录。**同一件事有两套存储、互不知道对方存在**，于是用户在
 * 「信息页项目文件树」看到的和在「文件管理」里看到的不是同一批文件，
 * AI 写进去的东西也只会出现在 UI 的虚拟列表里、磁盘上根本没有 —— 这就是「到处放」的根因。
 *
 * 现在统一：**项目文件就是磁盘上的文件**，一个项目 = 一个目录。
 * 文件管理界面、信息页项目树、AI 的 file_library_* 工具，三者看到的是同一份数据。
 *
 * ## 落到哪个目录
 * `<项目数据>/`（2026-10-29 起，见 [ProjectDirs]；未分类 = `synced/projects/_global/`）。
 * 放进 `synced/` 是因为用户**已经把这个目录配进了 Syncthing**。
 * 这样项目文件天生可同步，不需要再教用户配一次。
 * 代价是卸载应用会被系统删除，所以 [ProjectFiles.warnIfUnsynced] 会在
 * 首次看到有项目文件而 Syncthing 没运行时提醒一次。
 *
 * ## 为什么保留 [ProjectFile] 这个类
 * UI 和 4 个 AI 工具都按它的字段取数（name/kind/content/size/updatedAt）。
 * 保留同一个形状，这些调用点一行都不用改 —— 只是数据来源从 SQL 变成了磁盘。
 * [id] 用 "文件名 → 稳定数字" 的映射（见 [idOf]），因为 UI 把它当 key 用。
 */
object ProjectFileRepo {

    private const val GLOBAL_ID = 0L

    /** 全局文件（不属于任何项目）的目录名 */
    private const val GLOBAL_DIR = "_global"

    // ---------- 目录 ----------

    /**
     * 项目目录。**必须转发给 [FolderRepo.projectDir]** ——
     * 同一个项目只能有一个目录，这是"两套存储合并成一个真相"的唯一保证点。
     * 这里绝不自己拼路径，否则哪天改了命名规则就会再次分叉。
     */
    /**
     * 项目文件的目录 = **app 数据容器**（2026-10-29 起，见 [ProjectDirs] 与
     * `◆/planability-project-file-storage.md`）。
     *
     * 绑定真实文件夹的项目 = `<真实文件夹>/项目数据`——app 从前直接铺在用户文件夹根上，
     * `对话/`、`新对话__284/` 和用户自己的文件混在一层；未绑定项目 = 项目目录本身。
     * 这里绝不自己拼路径，哪天改了命名规则就会再次分叉。
     */
    fun dirOf(projectId: Long): File =
        if (projectId == GLOBAL_ID) File(ProjectFiles.root(), GLOBAL_DIR)
        else ProjectDirs.data(projectId)

    private fun ensureDir(projectId: Long): File? {
        val d = dirOf(projectId)
        return if (d.isDirectory || d.mkdirs()) d else null
    }

    // ---------- 读 ----------

    /**
     * 项目下的文件列表（含子目录？不含 —— 子目录交给文件管理界面，这里只列文件，
     * 免得同一份内容在两处以文件夹和文件两种样子出现）。
     *
     * **只列顶层**是 AI 的 `file_library_*` 工具依赖的语义（它们按 name 精确匹配），
     * 改它就等于改契约。要看子目录请用 [filesIn] / [tree]。
     */
    suspend fun byProject(projectId: Long): List<ProjectFile> = filesIn(projectId, "")

    // ---------- 子目录（阶段 4）----------

    /**
     * 解析相对子目录 → 真实目录，**防目录穿越**。
     *
     * 为什么必须防：项目文件现在就是磁盘文件，[sub] 一旦能带 `../..`，
     * AI 或 UI 传进来就等于往项目目录外面写/删文件。三道闸：
     * ①逐段拒 `.`/`..`/空段；②段名必须等于 [sanitize] 后的结果（不含路径分隔符等）；
     * ③最终 canonicalPath 必须仍在项目目录内。
     */
    fun resolveDir(projectId: Long, sub: String): File? {
        val root = dirOf(projectId)
        val segs = safeSegments(sub) ?: return null
        var d = root
        for (s in segs) {
            d = File(d, s)
            // 不许「穿过文件」：中间某段是普通文件时直接判非法
            if (!d.isDirectory) return null
        }
        if (!d.isDirectory) return null
        // 双重保险：符号链接等极端情况下 canonicalPath 可能跑出项目目录
        return if (d.canonicalPath.startsWith(root.canonicalPath)) d else null
    }

    /** 合法路径段；不合法返回 null。空串=项目根目录（合法，返回空列表） */
    private fun safeSegments(sub: String): List<String>? {
        val clean = sub.trim().replace('\\', '/').trim('/')
        if (clean.isBlank()) return emptyList()
        val segs = clean.split('/')
        if (segs.any { it.isBlank() || it == "." || it == ".." || it == "…" }) return null
        // 段名必须「本来就是干净的」：sanitize 会把非法字符换成 _，
        // 结果与原串不同就说明用户/AI 塞了路径分隔符之类的东西
        if (segs.any { sanitize(it) != it }) return null
        return segs
    }

    /** 某目录下的直接子目录名（相对 [projectId] 根，"" 表示根） */
    fun subDirs(projectId: Long, sub: String = ""): List<String> {
        val d = resolveDir(projectId, sub) ?: return emptyList()
        return d.listFiles { f -> f.isDirectory && !f.name.startsWith(".") }
            ?.map { it.name }
            ?.sortedBy { it.lowercase() }
            ?: emptyList()
    }

    /** 某目录下的文件（[ProjectFile.name] 是**纯文件名**，不含目录） */
    suspend fun filesIn(projectId: Long, sub: String = ""): List<ProjectFile> {
        val d = resolveDir(projectId, sub) ?: return emptyList()
        return d.listFiles { f -> f.isFile && !f.name.startsWith(".") }
            ?.sortedWith(compareBy({ it.name.lowercase() }))
            ?.map { it.toProjectFile(projectId, sub) }
            ?: emptyList()
    }

    /**
     * 新建子目录（可多级：`a/b/c`）。成功返回规范化后的相对路径（"" 表示根，已存在）。
     * 失败返回 null（非法路径 / 建不出来），调用方据此提示。
     */
    fun mkdirs(projectId: Long, sub: String): String? {
        val root = dirOf(projectId)
        val segs = safeSegments(sub) ?: return null
        if (segs.isEmpty()) return ""
        var d = root
        for (s in segs) {
            d = File(d, s)
        }
        if (d.isDirectory) return sub.trim().replace('\\', '/').trim('/')
        if (d.exists()) return null          // 同名文件占着，建不了
        return if (d.mkdirs()) {
            Graph.dataVersion.bump()
            sub.trim().replace('\\', '/').trim('/')
        } else null
    }

    /** 递归删子目录（**拒绝删项目根目录**）。返回是否删掉。 */
    fun deleteDir(projectId: Long, sub: String): Boolean {
        val segs = safeSegments(sub) ?: return false
        if (segs.isEmpty()) return false                     // 根目录不给删
        val d = resolveDir(projectId, sub) ?: return false
        val ok = deleteRecursive(d)
        if (ok) Graph.dataVersion.bump()
        return ok
    }

    private fun deleteRecursive(f: File): Boolean {
        if (f.isDirectory) f.listFiles()?.forEach { deleteRecursive(it) }
        return f.delete()
    }

    /** 目录树的一行（文件库 UI 直接按深度缩进渲染） */
    sealed interface Node {
        val depth: Int

        /** 一个子目录。[path] 是相对项目根的路径（"" 表示根） */
        data class Dir(val path: String, val name: String, override val depth: Int, val childCount: Int) : Node

        /** 一个文件。[sub] 是它所在目录的相对路径（"" 表示根） */
        data class File(val f: ProjectFile, val sub: String, override val depth: Int) : Node
    }

    /**
     * 递归展开成「扁平的行列表」（深度优先，目录排在文件前）。
     *
     * ## 为什么要摊平而不是返回嵌套结构
     * UI 要塞进 `LazyColumn`，嵌套结构得自己递归 `item {}`，写起来啰嗦且容易在
     * 折叠状态下仍占位。摊平成带 [Node.depth] 的行，渲染就是一个 for 循环。
     *
     * @param maxDepth 深度上限。项目文件量小，但目录来自磁盘（Syncthing 会同步进来），
     *   不设上限的话一个软链环或误同步进来的深目录能把列表撑爆。
     */
    suspend fun tree(projectId: Long, maxDepth: Int = 8): List<Node> =
        treeOf(dirOf(projectId), projectId, maxDepth)

    /**
     * 任意目录的树 —— [tree] 的真正实现。
     *
     * 为什么要按目录取而不是按 projectId：**AI 生成的文件目录不一定属于某个项目**。
     * 未分类的每个会话各有一个自己的目录（`_global/<标题__id>/`，见
     * `ExportStore.existingDirFor`），文件库要按会话分组显示它们，只能从目录出发。
     *
     * 目录名全部来自 [File.listFiles]，不是外部输入，所以这里不需要 [resolveDir] 那套
     * 防目录穿越校验（没有分隔符可穿越）；外部传入的路径仍然只能走 `resolveDir`。
     *
     * @param projectId 写进 [ProjectFile.projectId] 的归属标记。从目录出发、
     *   拿不到项目时调用方传 -1：**它不是某个项目的文件**，别让下游误以为是
     *   （删除/编辑走的是各自的路径，不看这个字段）。
     */
    suspend fun treeOf(dir: File, projectId: Long = -1L, maxDepth: Int = 8): List<Node> {
        val out = mutableListOf<Node>()
        suspend fun walk(base: File, sub: String, depth: Int) {
            if (depth > maxDepth) return
            val dirs = base.listFiles { f -> f.isDirectory && !f.name.startsWith(".") }
                ?.sortedBy { it.name.lowercase() } ?: emptyList()
            val files = base.listFiles { f -> f.isFile && !f.name.startsWith(".") }
                ?.sortedBy { it.name.lowercase() } ?: emptyList()
            dirs.forEach { d ->
                val child = (if (sub.isBlank()) "" else "$sub/") + d.name
                out += Node.Dir(child, d.name, depth, (d.listFiles() ?: emptyArray()).size)
                walk(d, child, depth + 1)
            }
            files.forEach { out += Node.File(it.toProjectFile(projectId, sub), sub, depth) }
        }
        walk(dir, "", 0)
        return out
    }

    /** 按文件名精确取一个（**只找顶层** —— 这是 AI 工具依赖的语义） */
    suspend fun byName(projectId: Long, name: String): ProjectFile? {
        val f = File(dirOf(projectId), sanitize(name))
        return if (f.isFile) f.toProjectFile(projectId, "") else null
    }

    /**
     * 按 id 取。id 是"文件名→数字"的映射，所以先反查文件名。
     * 找不到就按 id 当文件名再试一次（兼容旧调用点）。
     */
    suspend fun byId(id: Long): ProjectFile? {
        val rel = ProjectFiles.nameOf(id) ?: return null
        // 不知道属于哪个项目就扫一遍（项目数量是几十级别，全扫可接受）
        // rel 可能是 "笔记/x.md" 这种带子目录的相对路径，必须逐段校验后再拼，
        // 否则一个坏 id 就能顺着 .. 把项目目录外面的文件读出来
        val cut = rel.lastIndexOf('/')
        val sub = if (cut < 0) "" else rel.substring(0, cut)
        val name = if (cut < 0) rel else rel.substring(cut + 1)
        ProjectFiles.allProjectDirs().forEach { (pid, _) ->
            val base = resolveDir(pid, sub) ?: return@forEach
            val f = File(base, name)
            if (f.isFile && f.canonicalPath.startsWith(base.canonicalPath)) {
                return f.toProjectFile(pid, sub)
            }
        }
        return null
    }

    /** 全局 + 所有项目的全部文件 */
    suspend fun all(): List<ProjectFile> =
        ProjectFiles.allProjectDirs().flatMap { (pid, _) -> byProject(pid) }

    /**
     * 观察流：目录变化时刷新。
     * 磁盘没有 Room 的 Flow，用「轮询 + 数据版本戳」代替 —— 项目文件量小（几十个），
     * 1 秒一次的文件名 diff 完全够用，不值得为它引一个文件系统观察库。
     */
    fun observe(projectId: Long): kotlinx.coroutines.flow.Flow<List<ProjectFile>> =
        kotlinx.coroutines.flow.flow {
            while (true) {
                emit(byProject(projectId))
                kotlinx.coroutines.delay(1000)
            }
        }

    // ---------- 写 ----------

    /**
     * 写入（同名覆盖）。返回 id。
     *
     * [ProjectFile.sub] 非空时写进子目录，**目录不存在会自动创建**——
     * 否则 UI 选了个目录、磁盘上还没建，保存就会静默失败。
     */
    suspend fun save(f: ProjectFile): Long {
        if (ensureDir(f.projectId) == null) {
            throw IllegalStateException("无法创建项目目录：${dirOf(f.projectId).absolutePath}")
        }
        val name = sanitize(f.name)
        if (name.isBlank()) throw IllegalArgumentException("文件名不能为空")
        val sub = f.sub.trim().replace('\\', '/').trim('/')
        if (sub.isNotEmpty()) {
            if (safeSegments(sub) == null) throw IllegalArgumentException("目录路径非法：$sub")
            if (mkdirs(f.projectId, sub) == null) {
                throw IllegalStateException("无法创建目录：$sub")
            }
        }
        val dir = resolveDir(f.projectId, sub)
            ?: throw IllegalStateException("目录不可用：${sub.ifBlank { "项目根" }}")
        File(dir, name).writeText(f.content, Charsets.UTF_8)
        Graph.dataVersion.bump()
        return ProjectFiles.idOf(if (sub.isBlank()) name else "$sub/$name")
    }

    /**
     * 删除。**真删除**（不是软删）—— 项目文件现在就是普通文件，
     * 放进回收站需要额外一套影子副本，得不偿失；误删由 Syncthing/回收站兜底。
     * 但要保留一个「延迟删除」窗口内的兼容：见 [ProjectFiles.recentlyDeleted]。
     */
    suspend fun softDelete(id: Long) {
        val f = byId(id) ?: return
        val dir = resolveDir(f.projectId, f.sub) ?: return
        val target = File(dir, f.name)
        if (target.isFile) target.delete()
        Graph.dataVersion.bump()
    }

    suspend fun purge(id: Long) = softDelete(id)

    /** 供 UI 用的重命名（真实文件改名）。[sub] = 文件所在子目录（"" = 项目根） */
    suspend fun rename(projectId: Long, oldName: String, newName: String, sub: String = ""): Boolean {
        val dir = resolveDir(projectId, sub) ?: return false
        val from = File(dir, sanitize(oldName))
        val to = File(dir, sanitize(newName))
        if (!from.isFile) return false
        val ok = from.renameTo(to)
        if (ok) Graph.dataVersion.bump()
        return ok
    }

    /**
     * 重命名**子目录**（[sub] 是目录相对项目根的路径，[newName] 只是新目录名）。
     * 拒绝：项目根、源不存在、目标已存在（不覆盖）。2026-10-31 给 AI 的
     * file_library_rename 用——此前只有项目整体改名会动目录，目录级改名没有入口。
     */
    fun renameDir(projectId: Long, sub: String, newName: String): Boolean {
        val segs = safeSegments(sub) ?: return false
        if (segs.isEmpty()) return false                       // 根目录不给改名
        val from = resolveDir(projectId, sub) ?: return false
        if (!from.isDirectory) return false
        val to = File(from.parentFile ?: return false, sanitize(newName))
        if (to.exists()) return false
        val ok = from.renameTo(to)
        if (ok) Graph.dataVersion.bump()
        return ok
    }

    // ---------- 工具 ----------

    /** 文件名清洗：去掉路径分隔符和控制字符，防目录穿越 */
    fun sanitize(raw: String): String =
        raw.trim()
            .replace(Regex("[\\\\/:*?\"<>|]"), "_")
            .replace(Regex("[\\u0000-\\u001F]"), "")
            .take(120)
            .ifBlank { "未命名" }

    /** 相对项目根的路径（`sub + "/" + name`，根目录时就是 name）—— id 的键 */
    private fun ProjectFile.relPath(): String = if (sub.isBlank()) name else "$sub/$name"

    private fun File.toProjectFile(projectId: Long, sub: String = "") = ProjectFile(
        // id 必须跨子目录唯一：两个目录里各有一个「笔记.md」不能撞号
        id = ProjectFiles.idOf(if (sub.isBlank()) name else "$sub/$name"),
        projectId = projectId,
        name = name,
        sub = sub,
        kind = kindOf(this),
        // 只读文本内容；二进制文件（图片等）读成乱码没有意义，返回占位
        content = if (isTextLike()) readText(Charsets.UTF_8) else "",
        size = if (isTextLike()) length().toInt() else 0,
        trashed = false,
        createdAt = lastModified(),
        updatedAt = lastModified()
    )

    private fun File.isTextLike(): Boolean {
        val n = name.lowercase()
        return n.endsWith(".txt") || n.endsWith(".md") || n.endsWith(".json") ||
            n.endsWith(".csv") || n.endsWith(".xml") || n.endsWith(".yaml") ||
            n.endsWith(".yml") || n.endsWith(".log") || n.endsWith(".html") ||
            n.endsWith(".kt") || n.endsWith(".kts") || n.endsWith(".ts") ||
            n.endsWith(".tsx") || n.endsWith(".jsx") || n.endsWith(".js") ||
            // 2026-10-28 用户「项目内文件显示有问题」：编程项目里 .cpp 全显示「0 字」、
            // AI 的 file_library_read 也读不到内容 —— isTextLike 漏了常见源码扩展名，补齐
            n.endsWith(".py") || n.endsWith(".sql") ||
            n.endsWith(".toml") || n.endsWith(".ini") || n.endsWith(".conf") ||
            n.endsWith(".cpp") || n.endsWith(".cc") || n.endsWith(".c") ||
            n.endsWith(".h") || n.endsWith(".hpp") || n.endsWith(".java") ||
            n.endsWith(".cs") || n.endsWith(".go") || n.endsWith(".rs") ||
            n.endsWith(".swift") || n.endsWith(".dart") || n.endsWith(".php") ||
            n.endsWith(".rb") || n.endsWith(".lua") || n.endsWith(".sh") ||
            n.endsWith(".bat") || n.endsWith(".ps1") || n.endsWith(".css") ||
            n.endsWith(".scss") || n.endsWith(".less") || n.endsWith(".proto")
    }

    private fun kindOf(f: File): String = when {
        f.isDirectory -> "dir"
        f.name.endsWith(".md", true) -> "markdown"
        f.isTextLike() -> "text"
        else -> "binary"
    }
}

/** 项目文件的目录级工具（root / id 映射 / 未同步提醒） */
object ProjectFiles {

    /** 项目文件根目录：externalFilesDir/synced/projects —— 用户已把 synced 配进 Syncthing */
    fun root(): File {
        val ext = Graph.appContext.getExternalFilesDir(null) ?: Graph.appContext.filesDir
        return File(File(ext, "synced"), "projects")
    }

    /**
     * 项目 id → 项目名 的同步缓存。
     *
     * 为什么需要缓存：[FolderRepo.projectDir] 是**同步**函数（文件路径解析到处在用），
     * 而 `Graph.projects.byId` 是 suspend。目录名又要带项目名（用户看得懂），
     * 所以只能在这里留一份同步可读的快照。
     *
     * 由 [refreshProjectNames] 在数据版本变化时刷新；刷新失败时退化成纯数字目录名，
     * 不会抛 —— 目录名难看远好过整个文件功能打不开。
     */
    private val nameCache = ConcurrentHashMap<Long, String>()

    /**
     * 项目 id → 绑定的真实文件夹（v42）。与 nameCache 同一套刷新机制（[refreshProjectNames]），
     * 给同步的 [FolderRepo.projectDir] 用。空串 = 未绑定（走默认目录）。
     */
    private val dirCache = ConcurrentHashMap<Long, String>()

    /** 绑定的真实文件夹；未绑定返回 null */
    fun dirPathOf(projectId: Long): String? = dirCache[projectId]

    suspend fun refreshProjectNames() {
        runCatching {
            Graph.projects.all().forEach {
                nameCache[it.id] = it.name
                dirCache[it.id] = it.dirPath
            }
        }
    }

    fun projectNameOf(projectId: Long): String = nameCache[projectId] ?: ""

    /** 目录名 → projectId（不是项目目录返回 null）。兼容老的纯数字目录名 */
    fun projectIdOfDirName(dirName: String): Long? {
        val i = dirName.lastIndexOf("__")
        if (i < 0) return dirName.toLongOrNull()
        return dirName.substring(i + 2).toLongOrNull()
    }

    /**
     * 项目重命名时把目录一起改名。
     *
     * 找不到旧目录就当没有 —— 大不了重建一个空目录，
     * 不能因为改名失败把用户的文件卡住。目标已存在时也不动：
     * 宁可通过滤器看到旧名字，也不能覆盖掉已有文件。
     */
    fun renameProjectDir(projectId: Long, oldName: String, newName: String) {
        // v42：绑定了真实文件夹的项目，目录是用户自己的真实数据，改名**不动它**
        if (!dirPathOf(projectId).isNullOrBlank()) return
        runCatching {
            val root = root()
            if (!root.isDirectory) return@runCatching
            fun dirFor(n: String): File {
                val s = n.trim().replace(Regex("[\\\\/:*?\"<>|]"), "_").take(40)
                return if (s.isBlank()) File(root, projectId.toString()) else File(root, "${s}__$projectId")
            }
            val from = dirFor(oldName)
            if (!from.isDirectory) return@runCatching
            val to = dirFor(newName)
            if (from.absolutePath == to.absolutePath) return@runCatching
            if (to.exists()) return@runCatching
            from.renameTo(to)
        }
    }

    /**
     * 启动时预建根目录 + 全局目录。
     *
     * 为什么重要：目录不存在时 Syncthing 的文件夹监视器扫不到，用户在电脑那边
     * 要等下一轮扫描才看得到新建的 projects/。先建好能省掉这段延迟。
     */
    fun warmUp() {
        runCatching {
            val r = root()
            if (!r.isDirectory) r.mkdirs()
            val g = File(r, "_global")
            if (!g.isDirectory) g.mkdirs()
            // 项目名缓存：目录名要用它（见 nameCache 说明）
            kotlinx.coroutines.runBlocking { refreshProjectNames() }
        }
    }

    // ---- 文件名 ↔ id 的双向映射（UI 把 id 当稳定 key 用） ----

    private val nameToId = ConcurrentHashMap<String, Long>()
    private val idToName = ConcurrentHashMap<Long, String>()
    private var nextId = 10_000L

    @Synchronized
    fun idOf(name: String): Long {
        nameToId[name]?.let { return it }
        val id = nextId++
        nameToId[name] = id
        idToName[id] = name
        return id
    }

    fun nameOf(id: Long): String? = idToName[id]

    /** 所有项目目录（含全局 + v42 绑定的真实文件夹），用于跨项目查找 */
    fun allProjectDirs(): List<Pair<Long, File>> {
        val root = root()
        if (!root.isDirectory) return emptyList()
        val out = ArrayList<Pair<Long, File>>()
        root.listFiles()?.forEach { d ->
            if (!d.isDirectory) return@forEach
            if (d.name == "_global") { out += 0L to d; return@forEach }
            val pid = projectIdOfDirName(d.name) ?: return@forEach
            out += pid to d
        }
        // v42：绑定了真实文件夹的项目，其目录不在 root 下，补进来（项目创建即绑定，
        // 理论上不会与上面的扫描重号；万一有历史残留，以绑定的为准）
        dirCache.forEach { (pid, path) ->
            if (path.isNotBlank()) {
                val d = File(path)
                if (d.isDirectory) {
                    out.removeAll { it.first == pid }
                    out += pid to d
                }
            }
        }
        return out
    }

    /** 项目目录下的真实文件（File 视图，给文件管理界面用） */
    fun rawFilesOf(projectId: Long): List<File> {
        val d = ProjectFileRepo.dirOf(projectId)
        if (!d.isDirectory) return emptyList()
        return d.listFiles { f -> f.isFile && !f.name.startsWith(".") }
            ?.sortedWith(compareBy({ it.name.lowercase() }))
            ?: emptyList()
    }

    /** 有没有任何项目文件（用于「首次提示同步」） */
    fun hasAnyFiles(): Boolean = allProjectDirs().any { (_, d) ->
        d.listFiles { f -> f.isFile && !f.name.startsWith(".") }?.isNotEmpty() == true
    }
}