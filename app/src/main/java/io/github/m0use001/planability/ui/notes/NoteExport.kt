package io.github.m0use001.planability.ui.notes

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import io.github.m0use001.planability.data.NoteEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * v54.3 笔记导出为 `.md`（落到系统「下载/Planability/notes」）。
 *
 * 为什么要有这个：笔记目前只活在 App 的 Room 库里——换台设备、换套工具就读不到。
 * 「真知识库」的一个硬指标是**资产可携带**：纯文本 + 开放格式，任何编辑器都能打开，
 * 拔掉数据线就能拿走。这里只负责「把这篇写成一个 .md 文件」，不做反向导入（那是下一步）。
 *
 * 落点选 `Download/Planability/notes` 而不是应用私有目录：后者卸载即删，
 * 且电脑连上手机看不到——那就失去了"带走"的意义。
 */
object NoteExport {

    private val ILLEGAL = Regex("""[\\/:*?"<>|\r\n\t]""")
    private val WS = Regex("\\s+")

    /** 文件名：洗掉路径非法字符、压空白、限长；空标题给兜底名。 */
    fun fileName(note: NoteEntity): String {
        val raw = NotePreview.stripLine(note.title).ifBlank { "无标题" }
        val safe = raw.replace(ILLEGAL, "_").replace(WS, " ").trim().take(60).trimEnd('.', ' ')
        return (safe.ifBlank { "无标题" }) + ".md"
    }

    /**
     * 文件内容：**YAML front matter + 原文**。
     * front matter 只放 title / updated / tags 三项——多了就成了另一套元数据体系，
     * 与「笔记内容以正文为准」冲突。
     */
    fun document(note: NoteEntity, exportedAt: Long = System.currentTimeMillis()): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
        val sb = StringBuilder()
        sb.append("---\n")
        sb.append("title: ").append(yaml(note.title.ifBlank { "无标题" })).append('\n')
        sb.append("updated: ").append(fmt.format(Date(note.updatedAt))).append('\n')
        val tags = note.tags.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        if (tags.isNotEmpty()) sb.append("tags: ").append(tags.joinToString(", ")).append('\n')
        sb.append("exported: ").append(fmt.format(Date(exportedAt))).append('\n')
        sb.append("---\n\n")
        sb.append(note.content.trimEnd()).append('\n')
        return sb.toString()
    }

    /** 极简 YAML 转义：含冒号/引号/井号开头时整体加双引号并转义引号 */
    private fun yaml(s: String): String {
        val needQuote = s.startsWith("#") || s.contains(':') || s.contains('"') || s.contains('\'')
        return if (needQuote) "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"" else s
    }

    /**
     * 写入系统下载目录，返回对用户可读的目录名（失败返回 null）。
     * MediaStore 会自己处理同名文件（自动加 (1)），不需要我们抢名。
     */
    suspend fun exportToDownloads(context: Context, note: NoteEntity): String? = withContext(Dispatchers.IO) {
        runCatching {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName(note))
                put(MediaStore.MediaColumns.MIME_TYPE, "text/markdown")
                put(
                    MediaStore.MediaColumns.RELATIVE_PATH,
                    Environment.DIRECTORY_DOWNLOADS + "/Planability/notes"
                )
            }
            val uri = context.contentResolver.insert(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI, values
            ) ?: return@runCatching null
            context.contentResolver.openOutputStream(uri)?.use { out ->
                out.write(document(note).toByteArray(Charsets.UTF_8))
            } ?: return@runCatching null
            "下载/Planability/notes/" + fileName(note)
        }.getOrNull()
    }
}
