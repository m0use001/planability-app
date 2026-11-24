package io.github.m0use001.planability.data

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * v42：项目对话写穿（用户需求：项目对话要真实存储在项目文件夹里）。2026-10-29 调整落点：
 * 会话镜像固定写 `<项目数据>/[当前会话|已归档会话]/<标题__id>/对话.md`（见 [ProjectDirs]）。
 *
 * 从前是 `<项目文件夹>/对话/<sessionId>__<标题>.md` —— 在用户的真实工作文件夹里凭空多出一个
 * 「对话」目录，和用户自己的 `2026.9.27/` 混在一起。现在所有 app 数据都在「项目数据」里。
 *
 * 设计要点：
 * - **全量重写、幂等**：调用方只管在状态变化后喊一声 [mirror]，不用算增量；
 *   会话内容多大都只产生一次 IO（会话有压缩上限，不会无限膨胀）。
 * - **目录名带 sessionId**：自动改名/手动改名后目录跟着改（[ProjectDirs.ensureSessionDir] 自愈），
 *   不留陈旧副本；归档后整个目录搬到「已归档会话」。
 * - **只镜像 user/assistant 两种行**：工具调用行是过程痕迹，库里有完整轨迹（含审计），
 *   镜像面向"人翻开文件夹看"，掺进去反而没法读。
 * - done/error/stopped 都镜像：镜像要反映库里现状，失败与中止的内容也如实落盘。
 *
 * 挂点（ai/ChatEngine）：流结束统一收尾处、failMessage（未配置模型等早退路径）、自动命名成功。
 */
object SessionMirror {

    suspend fun mirror(sessionId: Long) {
        runCatching {
            val s = Graph.chat.session(sessionId) ?: return
            val dir = ProjectDirs.ensureSessionDir(
                s.projectId, s.id, s.title.ifBlank { "会话" }, s.archived
            ) ?: return

            val title = s.title.ifBlank { "会话" }
            val fmt = SimpleDateFormat("MM-dd HH:mm", Locale.CHINA)
            val md = buildString {
                append("# ").append(title).append("\n\n")
                Graph.chat.messagesOf(sessionId)
                    .filter { it.role == "user" || it.role == "assistant" }
                    .forEach { m ->
                        append("## ").append(if (m.role == "user") "用户" else "AI").append(" · ")
                            .append(fmt.format(Date(m.createdAt))).append('\n')
                        append(m.content.ifBlank { if (m.status == "streaming") "（生成中）" else "（无文本内容）" })
                        append("\n\n")
                    }
            }
            File(dir, ProjectDirs.MIRROR).writeText(md, Charsets.UTF_8)
        }
    }
}
