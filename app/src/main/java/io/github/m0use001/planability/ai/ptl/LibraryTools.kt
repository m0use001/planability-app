package io.github.m0use001.planability.ai.ptl

import io.github.m0use001.planability.data.Graph
import io.github.m0use001.planability.data.ProjectDirs
import io.github.m0use001.planability.data.ProjectFile
import io.github.m0use001.planability.data.ProjectFileRepo
import org.json.JSONObject

/**
 * 文件库的**目录树管理**工具实现：file_library_mkdir / file_library_rename / file_library_delete。
 *
 * 为什么单独一个文件：`ToolExecImpls.execute` 是个巨型 when，已经被 JVM 单方法 64KB
 * 上限卡过一次（当时把 fs_* 拆成 FsShellTools）；2026-10-31 加「建文件夹/改名」后
 * 再次报 `Method too large`，按同一先例把这三个分支挪出来。
 *
 * 语义（与 ToolDefs 的描述一致，给模型看的承诺都在那边）：
 * - mkdir：可多级；裸路径默认落「项目文件」（与 file_library_write 的裸名默认一致）
 * - rename：文件可借 new_name 的目录前缀移动（目标目录必须已存在）；文件夹只改本级名
 * - delete：文件软删（可撤销）；命中文件夹则递归删（ProjectFileRepo 拒绝删项目根）
 *
 * 安全：路径穿越在 ProjectFileRepo.safeSegments 拦；确认弹窗由 ToolExecutor 按 Risk.CONFIRM 走。
 */
internal object LibraryTools {

    /** 处理三个树管理工具；不认识的名字返回 null（调用方继续原分发）。 */
    suspend fun run(name: String, a: JSONObject, sessionId: Long): ToolExecResult? {
        return when (name) {
            "file_library_mkdir" -> {
                val raw = a.optString("path").trim()
                if (raw.isEmpty()) return ToolExecResult.fail("文件夹路径不能为空")
                val projArg = a.optString("project").trim()
                val pid = if (projArg.isEmpty() || projArg == "全局" || projArg.equals("global", true)) 0L
                else Graph.projects.all().firstOrNull { it.name == projArg }?.id
                    ?: return ToolExecResult.fail("项目「" + projArg + "」不存在")
                // 裸路径（不含数据容器前缀）= 相对「项目文件」，与 file_library_write 的默认一致
                var rel = raw.replace('\\', '/').trim('/')
                if (!rel.startsWith(ProjectDirs.FILES + "/") && rel != ProjectDirs.FILES) {
                    rel = ProjectDirs.FILES + "/" + rel
                }
                val made = ProjectFileRepo.mkdirs(pid, rel)
                    ?: return ToolExecResult.fail("建不出这个文件夹：路径非法，或同名文件占了位置")
                ToolExecResult.ok(
                    "文件夹已就绪：「" + (if (made.isBlank()) "项目数据根" else made) + "」（" +
                        (if (pid == 0L) "全局" else "项目") + "）",
                    "新建文件夹 " + rel
                )
            }
            "file_library_rename" -> {
                val raw = a.optString("path").trim().replace('\\', '/').trim('/')
                val newName = a.optString("new_name").trim().replace('\\', '/').trim('/')
                if (raw.isEmpty() || newName.isEmpty()) return ToolExecResult.fail("原路径与新名字都不能为空")
                val projArg = a.optString("project").trim()
                val pid = if (projArg.isEmpty() || projArg == "全局" || projArg.equals("global", true)) 0L
                else Graph.projects.all().firstOrNull { it.name == projArg }?.id
                    ?: return ToolExecResult.fail("项目「" + projArg + "」不存在")
                val nodes = ProjectFileRepo.tree(pid)
                val dirPath = nodes.filterIsInstance<ProjectFileRepo.Node.Dir>()
                    .map { it.path }.firstOrNull { it == raw }
                val file = nodes.filterIsInstance<ProjectFileRepo.Node.File>()
                    .firstOrNull { f -> (if (f.sub.isBlank()) f.f.name else f.sub + "/" + f.f.name) == raw }
                when {
                    // 文件夹改名：只改本级名字（整目录搬迁要重写所有子文件的 sub 路径，不做）
                    dirPath != null -> {
                        if (newName.contains('/')) return ToolExecResult.fail("文件夹只支持改本级名字，不支持移动到别的目录")
                        val ok = ProjectFileRepo.renameDir(pid, raw, newName)
                        if (!ok) return ToolExecResult.fail("改名失败：目标已存在，或路径非法")
                        ToolExecResult.ok("文件夹已改名为「" + newName + "」", "改名 " + raw + " → " + newName)
                    }
                    // 文件改名/移动：新名带目录前缀时目标目录必须已存在（不隐式建，防止手滑写出杂目录）
                    file != null -> {
                        val cut = newName.lastIndexOf('/')
                        val justName = if (cut < 0) newName else newName.substring(cut + 1)
                        val newSub = if (cut < 0) file.sub else newName.substring(0, cut)
                        if (newSub != file.sub) {
                            val dirs = nodes.filterIsInstance<ProjectFileRepo.Node.Dir>().map { it.path }.toSet()
                            if (newSub !in dirs) {
                                return ToolExecResult.fail("目标目录「" + newSub + "」不存在，先 file_library_mkdir 建好再移动")
                            }
                        }
                        val ok = ProjectFileRepo.rename(pid, file.f.name, justName, newSub)
                        if (!ok) return ToolExecResult.fail("改名失败：目标已存在，或路径非法")
                        ToolExecResult.ok(
                            "文件已" + (if (newSub != file.sub) "移动并改名为" else "改名为") + "「" + newName + "」",
                            "改名 " + raw
                        )
                    }
                    else -> ToolExecResult.fail("「" + raw + "」不存在（文件与文件夹里都没有）")
                }
            }
            "file_library_delete" -> {
                val name = a.optString("name").trim().replace('\\', '/').trim('/')
                val projName = a.optString("project").trim()
                fun matches(f: ProjectFile): Boolean =
                    f.name == name || (if (f.sub.isBlank()) f.name else f.sub + "/" + f.name) == name
                val projects = Graph.projects.all()
                val pid = if (projName.isEmpty() || projName == "全局" || projName.equals("global", true)) 0L
                else projects.firstOrNull { it.name == projName }?.id
                    ?: return ToolExecResult.fail("项目「" + projName + "」不存在")
                val nodes = ProjectFileRepo.tree(pid)
                // 先文件后文件夹：文件精确命中优先（同名文件/目录并存时按文件处理）
                val f = nodes
                    .filterIsInstance<ProjectFileRepo.Node.File>()
                    .map { it.f }
                    .firstOrNull { matches(it) }
                if (f != null) {
                    ProjectFileRepo.softDelete(f.id)
                    return ToolExecResult.ok("已删除文件「" + name + "」（可撤销）", "删除 " + name)
                }
                // 2026-10-31：支持删文件夹（递归；ProjectFileRepo 拒绝删项目根）
                val dirHit = nodes.filterIsInstance<ProjectFileRepo.Node.Dir>()
                    .firstOrNull { it.path == name || it.name == name }
                if (dirHit != null) {
                    val ok = ProjectFileRepo.deleteDir(pid, dirHit.path)
                    return if (ok) ToolExecResult.ok("已删除文件夹「" + dirHit.path + "」及其全部内容", "删除文件夹 " + dirHit.path)
                    else ToolExecResult.fail("删除文件夹失败：" + dirHit.path)
                }
                ToolExecResult.fail("「" + name + "」不存在（文件与文件夹里都没有）")
            }
            else -> null
        }
    }
}
