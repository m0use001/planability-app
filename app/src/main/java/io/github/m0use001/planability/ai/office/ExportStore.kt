package io.github.m0use001.planability.ai.office

import android.content.Context
import io.github.m0use001.planability.data.ProjectDirs
import java.io.File

/**
 * v14.3：**导出目录的唯一定义**。
 *
 * ## 存放规则（2026-10-29 重定，权威文档 `设计文档`）
 *
 * AI 生成的文件要落在**该对话自己的文件夹**里——文件跟着对话走，不再平铺在 `exports/`，
 * 也不再直接铺在用户的项目真实文件夹根上（那样会和用户自己的文件混成一锅）。
 *
 * | 对话位置 | 文件落点 |
 * |---|---|
 * | 未归档 | `<项目数据>/当前会话/<标题__会话id>/` |
 * | 已归档 | `<项目数据>/已归档会话/<标题__会话id>/` |
 * | 未分类（已废除，仅兼容历史数据） | `<synced>/projects/_global/当前会话/<标题__id>/` |
 *
 * 「项目数据」这一层的定义在 [ProjectDirs]：绑定真实文件夹的项目 = `<真实文件夹>/项目数据`，
 * app 自建项目 = 项目目录本身（不套层）。
 *
 * 对 AI 的工具契约不变：`exports/xxx` 仍是**虚拟前缀**，运行期解析成"当前对话的文件夹"，
 * 所以历史记录里存的 `exports/xxx` 不需要迁移（见 [ToolExecImpls] 的解析点）。
 *
 * ## 旧位置
 *
 * v14.3 之前写在 `getExternalFilesDir()/exports/`；v14.3–2026-10-28 写在项目根（或
 * 项目内文件夹）下的 `<标题__id>/`。两处都仍在**读**候选里（[resolveExisting]），
 * 保证"以前生成的文件还能打开"，但新写入不再走它们。
 *
 * ## 谁在用
 *
 * 写入侧：AI 的 office 工具（`ToolExecImpls` 全部走 [dirFor]）。
 * 浏览侧：**文件库**（`ui/info/FileLibraryScreen`）——「导出文件」整页已按用户要求删除，
 * 文件改在文件库里按会话/项目层级查看，所以这里需要一个**不建目录**的读法（[existingDirFor]）。
 */
object ExportStore {

    private const val NAME = "exports"

    @Volatile
    private var cachedLegacy: File? = null

    /**
     * 旧的扁平导出目录（v14.3 之前的位置）。
     *
     * 保留是为了**读**：用户以前生成的文件还在那里，不能因为搬了目录就让他找不着。
     * 新写入不再走这里。
     */
    fun legacyDir(context: Context): File {
        cachedLegacy?.let { if (it.isDirectory) return it }
        val app = context.applicationContext
        val root = app.getExternalFilesDir(null) ?: app.filesDir
        val dir = File(root, NAME)
        if (!dir.exists()) dir.mkdirs()
        cachedLegacy = dir
        return dir
    }

    /** 兼容旧调用方的别名：等于 [legacyDir]。新代码请用 [dirFor]。 */
    fun dir(context: Context): File = legacyDir(context)

    /**
     * 该对话的文件目录（**AI 生成文件的落点**），目录不存在就建出来。
     *
     * ## 任何读库失败都退回旧目录，而不是猜一个路径
     *
     * 把文件写错地方比"落在过去的位置"糟糕得多——用户会以为文件丢了。
     * 取不到会话信息时一律回退 [legacyDir]：旧位置仍在、仍然可读、仍然在同步目录里，
     * 宁可"文件在以前的地方"也不落到某个猜出来的目录里。
     */
    suspend fun dirFor(context: Context, sessionId: Long): File =
        resolveDir(context, sessionId, create = true) ?: legacyDir(context)

    /**
     * 该对话的目录，**没有就返回 null，而且绝不创建**。
     *
     * 给文件库用：浏览时要判断"这个会话到底有没有属于自己的目录"。
     * 如果这里也用 [dirFor]，列一次文件库就会给**每个**会话都建出一个空目录——
     * 未分类那一栏会凭空冒出几十个空文件夹，用户还得逐个点开确认里面是空的。
     *
     * 命名规则与 [dirFor] 共用同一份实现（[resolveDir]），两边不可能分叉。
     */
    suspend fun existingDirFor(context: Context, sessionId: Long): File? =
        resolveDir(context, sessionId, create = false)

    /**
     * 按 `exports/xxx` 找到**真实存在**的文件。
     *
     * 为什么不能只认 [dirFor]（2026-10-20 实测踩到）：
     * 新会话里 AI 刚导出的文件，用户点「打开」却报"文件不存在或路径不支持"——
     * 同一个 `exports/` 前缀，写入时落在当前会话目录，读取时如果会话解析出别的位置
     * （新会话的项目/文件夹还没定、历史文件在旧扁平目录、或读库失败退回了 legacy），
     * 就永远差一个目录。工具写得进去、用户打不开，比直接报错更让人困惑。
     *
     * 所以这里按"越新越优先"依次找：当前会话目录 → 旧扁平目录 → filesDir/exports。
     */
    suspend fun resolveExisting(context: Context, sessionId: Long, rel: String): File? {
        val name = rel.removePrefix("exports/").trim().removePrefix("/")
        if (name.isEmpty() || name.contains("..")) return null
        val dirs = mutableListOf<File>()
        val session = runCatching {
            io.github.m0use001.planability.data.Graph.chat.session(sessionId)
        }.getOrNull()
        if (session != null) {
            val pid = session.projectId
            // 新位置（当前会话 / 已归档会话，含旧标题自愈）+ 旧位置（项目根下的 <标题__id>）
            runCatching {
                ProjectDirs.ensureSessionDir(pid, sessionId, session.title, session.archived, create = false)
            }.getOrNull()?.let { dirs += it }
            runCatching { ProjectDirs.findSessionDirs(pid, sessionId) }.getOrDefault(emptyList())
                .forEach { dirs += it }
            runCatching {
                ProjectDirs.root(pid).listFiles()
                    ?.filter { it.isDirectory && it.name.endsWith("__$sessionId") }
                    ?.forEach { dirs += it }
            }
        }
        runCatching { legacyDir(context) }.getOrNull()?.let { dirs += it }
        dirs += File(context.filesDir, NAME)
        val candidates = dirs.distinct()
        candidates.map { File(it, name) }.firstOrNull { it.exists() && it.isFile }?.let { return it }

        // ── 兜底：按**文件名**再找一遍（2026-11-17 用户「怎么打开不了」实报）──
        //
        // 卡片里的 path 是模型自己写的，经常和实际落盘位置对不上：AI 用
        // file_library_write 把文件写进了「项目文件/xxx/」，却在回复里钓出
        // `exports/xxx.md`；或者文件其实在项目文件根下、卡片多写了一层目录。
        // 精确路径必然 miss → 用户点「打开」只看到「文件不存在或路径不支持」。
        //
        // 这里按**同名**在会话目录、项目文件目录（含一级子目录）、legacy exports 里
        // 再搜一次：命中就打开（用户要的是"看到那个文件"，不是"验证路径写法"）。
        // 只按文件名匹配、只在 app 自己的目录里找，绝不越出项目范围。
        val tail = name.substringAfterLast('/')
        if (tail.isBlank()) return null
        val searchDirs = buildList {
            addAll(candidates)
            if (session != null) {
                runCatching { ProjectDirs.filesDir(session.projectId) }.getOrNull()?.let { add(it) }
            }
        }
        val seen = mutableSetOf<String>()
        for (d in searchDirs) {
            if (!d.isDirectory) continue
            val direct = File(d, tail)
            if (direct.exists() && direct.isFile) return direct
            // 一级子目录（项目文件/<分类>/xxx.md 这种）
            val kids = runCatching { d.listFiles() }.getOrNull() ?: continue
            for (k in kids) {
                if (!k.isDirectory) continue
                val hit = File(k, tail)
                if (hit.exists() && hit.isFile && seen.add(hit.absolutePath)) return hit
            }
        }
        return null
    }

    /**
     * 目录落点的**唯一**实现：[dirFor] 与 [existingDirFor] 都走这里。
     *
     * 两者的区别只有 `create` 这一个参数，规则本身必须只有一份——
     * 否则"写进去的地方"和"找出来的地方"迟早对不上，而这类分叉的表现是
     * **文件明明生成了、列表里却找不到**，不报错、不崩溃。
     *
     * @param create 是否把缺失的目录建出来：写入 true，浏览 false。
     */
    private suspend fun resolveDir(context: Context, sessionId: Long, create: Boolean): File? {
        val session = runCatching {
            io.github.m0use001.planability.data.Graph.chat.session(sessionId)
        }.getOrNull()
        if (session == null || sessionId <= 0) return null

        // 2026-10-29：落点收口到「项目数据/[当前会话|已归档会话]/<标题__id>/」。
        // 从前这里是"项目根（或项目内文件夹）下再套一层会话目录"，于是用户的真实文件夹里
        // 长出一堆 新对话__284/，和用户自己的 2026.9.27/ 混成一锅。
        val pid = session.projectId
        val container = runCatching { ProjectDirs.data(pid) }.getOrNull() ?: return null
        if (create) {
            runCatching { ProjectDirs.ensureStructure(pid) }
        } else if (!container.isDirectory) return null
        return runCatching {
            ProjectDirs.ensureSessionDir(pid, sessionId, session.title, session.archived, create)
        }.getOrNull()
    }

    /**
     * 旧扁平目录（v14.3 之前）里的文件，按修改时间倒序。
     *
     * 「导出文件」整页已删除，这些历史文件改由**文件库**底部的「旧导出目录」分组收着——
     * 入口没了不等于文件不存在了，假装它没了等于让用户以为文件被删了。
     */
    fun legacyFiles(context: Context, limit: Int = 60): List<File> {
        val out = ArrayList<File>()
        collect(legacyDir(context), out, depth = 0)
        return out.sortedByDescending { it.lastModified() }.take(limit)
    }

    /**
     * 深度优先收集 [dir] 下的文件到 [out]。
     *
     * 限深 [MAX_SCAN_DEPTH]：这是 Syncthing 同步目录，可能有环，无界递归会栈溢出。
     * 而业务上只有两层，三层足够——更深的不该是我们生成的。
     */
    private fun collect(dir: File, out: MutableList<File>, depth: Int) {
        if (depth > MAX_SCAN_DEPTH) return
        val kids = dir.listFiles() ?: return
        for (f in kids) {
            if (f.isHidden) continue
            if (f.isDirectory) collect(f, out, depth + 1)
            else if (f.isFile) out.add(f)
        }
    }

    /** 扫描深度上限，见 [collect] */
    private const val MAX_SCAN_DEPTH = 3
}
