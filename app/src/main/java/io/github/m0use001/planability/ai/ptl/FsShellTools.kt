package io.github.m0use001.planability.ai.ptl

import org.json.JSONObject
import java.io.File

/**
 * v43：真实文件面（fs_*）的工具实现。
 *
 * 为什么单独一个文件：`ToolExecImpls.execute` 的 when 已经逼近 JVM 单方法 64KB 上限
 * （加完这批工具后编译直接报 `Method too large`），新工具必须拆出来。
 *
 * 安全闸门都在调用侧/底层：
 * - 路径：core/FsGuard（外部存储根、Android/data·obb 恒拒、canonical 逃逸）
 * - 确认：ToolExecutor 按 Risk（CONFIRM/DANGER）
 * - 审计：ToolExecutor 统一写 tools_audit
 */
internal object FsShellTools {

    /** 处理 fs_*；不认识的名字返回 null（调用方继续走原分发）。 */
    suspend fun run(name: String, a: JSONObject): ToolExecResult? {
        return when (name) {
        "fs_list" -> {
            val g = io.github.m0use001.planability.core.FsGuard.resolve(a.optString("path"))
            g.error?.let { return ToolExecResult.fail(it) }
            val dir = g.file!!
            if (!dir.isDirectory) return ToolExecResult.fail("不是目录：" + io.github.m0use001.planability.core.FsGuard.pretty(dir))
            val showHidden = a.optBoolean("showHidden", false)
            val kids = (dir.listFiles() ?: emptyArray())
                .filter { showHidden || !it.name.startsWith(".") }
                .sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
            val shown = kids.take(300)
            val body = shown.joinToString("\n") { f ->
                if (f.isDirectory) "目录  " + f.name + "/"
                else "文件  " + f.name + "  " + io.github.m0use001.planability.core.FsGuard.humanSize(f.length())
            }.ifBlank { "（空目录）" }
            val more = if (kids.size > shown.size) "\n…（还有 ${kids.size - shown.size} 项未列出）" else ""
            ToolExecResult.ok(
                io.github.m0use001.planability.core.FsGuard.pretty(dir) + "（" + kids.size + " 项）\n" + body + more,
                "fs_list " + dir.name
            )
        }

        "fs_read_text" -> {
            val g = io.github.m0use001.planability.core.FsGuard.resolve(a.optString("path"))
            g.error?.let { return ToolExecResult.fail(it) }
            val f = g.file!!
            if (!f.isFile) return ToolExecResult.fail("不是文件：" + io.github.m0use001.planability.core.FsGuard.pretty(f))
            if (io.github.m0use001.planability.core.FileKinds.isBinary(f.name)) {
                return ToolExecResult.fail("." + f.extension + " 是二进制文件，不能按文本读取（文档请用 file_read_document，图片请用 file_read_image）")
            }
            val capKb = a.optInt("maxKB", 200).coerceIn(1, 512)
            val capBytes = capKb * 1024L
            // 2026-11-05 分页（真机会话现场）：原来只读前 capKB 字节、没有续读入口，
            // 用户 58 万字的稿子对 AI 只能看到开头。现在：
            //  · 小文件（≤capKB）整读后按 offset 窗口切，头部给位置与续读 offset；
            //  · 大文件先按字节读一段（保证不 OOM），同样走窗口（字符下标以读到的内容为准）。
            val raw = if (f.length() > capBytes) {
                f.inputStream().use { it.readNBytes(capBytes.toInt()) }.toString(Charsets.UTF_8)
            } else f.readText(Charsets.UTF_8)
            val page = TextWindows.page(raw, a.optInt("offset", 0),
                a.optInt("limit", TextWindows.DEFAULT_LIMIT).coerceAtLeast(4000))
            val note = if (f.length() > capBytes)
                "\n…（原文件 " + io.github.m0use001.planability.core.FsGuard.humanSize(f.length()) +
                    "，本次窗口基于前 " + capKb + "KB 内容）"
            else ""
            ToolExecResult.ok(page.render(f.name) + note, "fs_read_text " + f.name + (if (page.hasMore) "（未完）" else ""))
        }

        "fs_search" -> {
            val rootRaw = a.optString("root").ifBlank { io.github.m0use001.planability.core.FsGuard.root().absolutePath }
            val g = io.github.m0use001.planability.core.FsGuard.resolve(rootRaw)
            g.error?.let { return ToolExecResult.fail(it) }
            val q = a.optString("query").trim().lowercase()
            if (q.isEmpty()) return ToolExecResult.fail("query 不能为空")
            val lim = a.optInt("limit", 30).coerceIn(1, 100)
            val skip = io.github.m0use001.planability.core.FsGuard.denyPrefixes()
            val hits = mutableListOf<File>()
            // 深度 6 已覆盖手机常见目录结构；条数到上限立刻停
            fun walk(d: File, depth: Int) {
                if (hits.size >= lim || depth > 6) return
                if (skip.any { d.path == it || d.path.startsWith("$it/") }) return
                val kids = d.listFiles() ?: return
                for (f in kids) {
                    if (hits.size >= lim) return
                    if (f.name.startsWith(".")) continue
                    if (f.name.lowercase().contains(q)) hits += f
                    if (f.isDirectory) walk(f, depth + 1)
                }
            }
            walk(g.file!!, 0)
            if (hits.isEmpty()) {
                ToolExecResult.fail("在 " + io.github.m0use001.planability.core.FsGuard.pretty(g.file!!) + " 下没找到文件名含「" + q + "」的文件（深度≤6）")
            } else {
                val body = hits.joinToString("\n") { f ->
                    io.github.m0use001.planability.core.FsGuard.pretty(f) +
                        if (f.isDirectory) "  （目录）" else "  " + io.github.m0use001.planability.core.FsGuard.humanSize(f.length())
                }
                ToolExecResult.ok("找到 ${hits.size} 个（上限 $lim）\n" + body, "fs_search " + q)
            }
        }

        "fs_write_text" -> {
            val g = io.github.m0use001.planability.core.FsGuard.resolve(a.optString("path"), forWrite = true)
            g.error?.let { return ToolExecResult.fail(it) }
            val f = g.file!!
            val content = a.optString("content")
            if (content.length > 2 * 1024 * 1024) return ToolExecResult.fail("内容超过 2MB，拒绝写入（请分段或改用导出）")
            if (f.exists() && !a.optBoolean("overwrite", false)) {
                return ToolExecResult.fail("目标已存在：" + io.github.m0use001.planability.core.FsGuard.pretty(f) + "；要覆盖请传 overwrite=true")
            }
            if (!f.parentFile!!.isDirectory && !f.parentFile!!.mkdirs()) {
                return ToolExecResult.fail("父目录创建失败：" + io.github.m0use001.planability.core.FsGuard.pretty(f.parentFile!!))
            }
            f.writeText(content, Charsets.UTF_8)
            ToolExecResult.ok(
                "已写入 " + io.github.m0use001.planability.core.FsGuard.pretty(f) + "（" + content.length + " 字符 / " +
                    io.github.m0use001.planability.core.FsGuard.humanSize(f.length()) + "）",
                "fs_write_text " + f.name
            )
        }

        "fs_mkdir" -> {
            val g = io.github.m0use001.planability.core.FsGuard.resolve(a.optString("path"), forWrite = true)
            g.error?.let { return ToolExecResult.fail(it) }
            val d = g.file!!
            if (d.isDirectory) return ToolExecResult.ok("目录已存在：" + io.github.m0use001.planability.core.FsGuard.pretty(d), "fs_mkdir")
            if (!d.mkdirs()) return ToolExecResult.fail("创建失败：" + io.github.m0use001.planability.core.FsGuard.pretty(d))
            ToolExecResult.ok("已创建 " + io.github.m0use001.planability.core.FsGuard.pretty(d), "fs_mkdir " + d.name)
        }

        "fs_move" -> {
            val src = io.github.m0use001.planability.core.FsGuard.resolve(a.optString("from"), forWrite = true)
            src.error?.let { return ToolExecResult.fail("源路径：" + it) }
            val dst = io.github.m0use001.planability.core.FsGuard.resolve(a.optString("to"), forWrite = true)
            dst.error?.let { return ToolExecResult.fail("目标路径：" + it) }
            val from = src.file!!
            val to = dst.file!!
            if (!from.exists()) return ToolExecResult.fail("源不存在：" + io.github.m0use001.planability.core.FsGuard.pretty(from))
            if (to.exists()) return ToolExecResult.fail("目标已存在：" + io.github.m0use001.planability.core.FsGuard.pretty(to) + "（不覆盖；先 fs_delete 或换个名字）")
            to.parentFile?.mkdirs()
            if (!from.renameTo(to)) return ToolExecResult.fail("移动失败（跨卷或权限问题）：" + io.github.m0use001.planability.core.FsGuard.pretty(from))
            ToolExecResult.ok(
                "已移动 " + io.github.m0use001.planability.core.FsGuard.pretty(from) + " → " + io.github.m0use001.planability.core.FsGuard.pretty(to),
                "fs_move"
            )
        }

        "fs_delete" -> {
            val g = io.github.m0use001.planability.core.FsGuard.resolve(a.optString("path"), forWrite = true)
            g.error?.let { return ToolExecResult.fail(it) }
            val f = g.file!!
            if (!f.exists()) return ToolExecResult.fail("不存在：" + io.github.m0use001.planability.core.FsGuard.pretty(f))
            val recursive = a.optBoolean("recursive", false)
            if (f.isDirectory) {
                if (!recursive && (f.listFiles()?.isNotEmpty() == true)) {
                    return ToolExecResult.fail("目录非空：" + io.github.m0use001.planability.core.FsGuard.pretty(f) + "；确认要连内容一起删请传 recursive=true")
                }
                if (!f.deleteRecursively()) return ToolExecResult.fail("删除失败：" + io.github.m0use001.planability.core.FsGuard.pretty(f))
            } else if (!f.delete()) {
                return ToolExecResult.fail("删除失败：" + io.github.m0use001.planability.core.FsGuard.pretty(f))
            }
            ToolExecResult.ok(
                "已删除 " + io.github.m0use001.planability.core.FsGuard.pretty(f) +
                    "（不可撤销：回收站只覆盖应用内数据，真实文件删了就是删了）",
                "fs_delete"
            )
        }

        else -> null
        }
    }
}
