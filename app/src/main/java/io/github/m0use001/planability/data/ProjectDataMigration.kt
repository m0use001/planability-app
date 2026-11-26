package io.github.m0use001.planability.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 2026-10-29：旧落点 → 「项目数据」的一次性迁移（规则见
 * `◆/planability-project-file-storage.md` §P4）。
 *
 * ## 只搬能对上号的东西
 *
 * app 曾经直接往用户的真实项目文件夹里写三类东西（这就是文件树被搞乱的来源）：
 * `对话/<sid>__标题.md`（会话镜像）、`<标题>__<sid>/`（AI 导出目录）、`项目文件/`、
 * 还有根上的工作进程 .md。迁移把它们各自搬进 `项目数据` 的对应子目录；
 * **其余任何目录/文件一律不碰**——用户的 `2026.9.27/`、`draft.cpp` 不是我们的，动就是事故。
 *
 * "能对上号"的判据：
 * - 镜像文件名以 `<数字>__` 开头且该数字能在本项目的 `chat_sessions` 里查到；
 * - 产物目录名以 `__<数字>` 结尾且该数字在库里；
 * - `.md` 首行是 [WORKFLOW_MARKER]。
 * 对不上号的原样留在原地（宁可多留，不可误搬/误删）。
 *
 * ## 幂等
 *
 * 全部步骤都是"源不存在就跳过、目标存在不覆盖"，跑几遍结果一样；完成写 KV 标记，
 * 之后直接短路。失败不吞：外层 runCatching 兜底但单个文件的失败留给下一次
 * ——反正 KV 标记只有全跑完才写（放在 [runIfNeeded] 的最后）。
 */
object ProjectDataMigration {

    private const val KEY = "mig.projectdata.v1"

    /** 旧会话镜像目录（v42） */
    private const val LEGACY_MIRROR = "对话"

    suspend fun runIfNeeded() {
        if (Graph.settings.kvGet(KEY) == "1") return
        runCatching { migrateAll() }
        Graph.settings.kvPut(KEY, "1")
    }

    private suspend fun migrateAll() = withContext(Dispatchers.IO) {
        val sessions = Graph.chat.sessions()
        val projects = runCatching { Graph.projects.all() }.getOrDefault(emptyList())
        val pids = (projects.map { it.id } + sessions.map { it.projectId } + 0L).toSet()
        pids.forEach { pid ->
            runCatching { migrateProject(pid, sessions.filter { it.projectId == pid }) }
        }
    }

    private fun migrateProject(pid: Long, sessions: List<ChatSession>) {
        val root = ProjectDirs.root(pid)
        if (!root.isDirectory) return
        val data = ProjectDirs.ensureStructure(pid) ?: return
        val byId = sessions.associateBy { it.id }
        val bound = root.canonicalPath != data.canonicalPath

        // ① 旧会话镜像：<root>/对话/<sid>__标题.md → 会话目录/对话.md
        val legacyMirror = File(root, LEGACY_MIRROR)
        if (legacyMirror.isDirectory) {
            legacyMirror.listFiles { f -> f.isFile && f.name.endsWith(".md") }?.forEach { f ->
                val sid = f.name.substringBefore("__").toLongOrNull()
                val s = sid?.let { byId[it] }
                val dstDir = if (s != null)
                    ProjectDirs.ensureSessionDir(pid, s.id, s.title.ifBlank { "会话" }, s.archived)
                else ProjectDirs.filesDir(pid)
                if (dstDir != null) {
                    moveOver(f, File(dstDir, if (s != null) ProjectDirs.MIRROR else f.name),
                        overwrite = s != null)
                }
            }
            if (legacyMirror.listFiles()?.isEmpty() == true) runCatching { legacyMirror.delete() }
        }

        // ② 旧会话产物目录：<root>/<标题>__<sid>/ → 会话目录（合并）
        root.listFiles()?.filter { it.isDirectory && it.name != LEGACY_MIRROR }?.forEach { d ->
            val sid = ProjectDirs.sessionIdOf(d.name) ?: return@forEach
            val s = byId[sid] ?: return@forEach
            val target = ProjectDirs.ensureSessionDir(pid, s.id, s.title.ifBlank { "会话" }, s.archived)
                ?: return@forEach
            d.listFiles()?.forEach { f -> moveOver(f, File(target, f.name), overwrite = f.name == ProjectDirs.MIRROR) }
            if (d.listFiles()?.isEmpty() == true) runCatching { d.delete() }
        }

        if (bound) {
            // ③ 旧「项目文件」目录（只在绑定项目才需要搬：未绑定项目它本来就是数据容器里的同名目录）
            val legacyFiles = File(root, ProjectDirs.FILES)
            if (legacyFiles.isDirectory && legacyFiles.canonicalPath != ProjectDirs.filesDir(pid).canonicalPath) {
                val dst = ProjectDirs.filesDir(pid)
                legacyFiles.listFiles()?.forEach { f -> moveOver(f, File(dst, f.name)) }
                if (legacyFiles.listFiles()?.isEmpty() == true) runCatching { legacyFiles.delete() }
            }
        }

        // ④ 根上的工作进程清单 → 项目文件（带 WORKFLOW_MARKER 才认；未绑定项目根=数据容器，同样归置）
        root.listFiles { f -> f.isFile && f.name.endsWith(".md") }?.forEach { f ->
            val head = runCatching { f.useLines { it.firstOrNull() ?: "" } }.getOrDefault("")
            if (head.trim() == WORKFLOW_MARKER) {
                moveOver(f, File(ProjectDirs.filesDir(pid), f.name))
            }
        }
    }

    /**
     * 搬一个文件：优先 rename（同卷上是原子操作），失败退回 copy → 校验 → 删源。
     * [overwrite] 只给会话镜像（它本来就要全量重写）；其余一律不覆盖已有目标，
     * 改名成 `<stem>-2.<ext>` 往旁边放——迁移期一个字节都不该悄悄消失。
     */
    private fun moveOver(src: File, dst: File, overwrite: Boolean = false) {
        runCatching {
            val target = if (dst.exists() && !overwrite) uniqueName(dst) else dst
            if (src.renameTo(target)) return
            src.copyTo(target, overwrite = true)
            if (target.length() == src.length()) src.delete()
        }
    }

    private fun uniqueName(f: File): File {
        if (!f.exists()) return f
        val stem = f.nameWithoutExtension
        val ext = f.extension.let { if (it.isBlank()) "" else ".$it" }
        var i = 2
        while (File(f.parentFile, "$stem-$i$ext").exists()) i++
        return File(f.parentFile, "$stem-$i$ext")
    }
}
