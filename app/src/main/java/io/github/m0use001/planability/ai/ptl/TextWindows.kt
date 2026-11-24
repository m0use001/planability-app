package io.github.m0use001.planability.ai.ptl

/**
 * 读取类工具的**分页窗口**（2026-11-05 用户现场）。
 *
 * ## 为什么需要
 * 真机会话「小说写作助手」：用户让 AI 读 `林默手记.md`（**58 万字 / 572KB**），
 * 工具返回前 1.2 万字就结束，AI 如实告诉用户「工具每次只能从文件开头取」——
 * 这不是模型偷懒，是**工具设计里根本没有"从哪儿开始读"这个旋钮**：
 * `file_read_text` / `file_library_read` / `fs_read_text` / `file_read_document`
 * 全部是 `text.take(N)`，第 N 字之后的内容对 AI 永久不可见。
 * 长文档（小说稿、代码库、长笔记）因此只能靠用户手工粘片段，工具等于半残。
 *
 * ## 做法
 * 统一给这几个工具加 `offset`（**从 0 开始的起始字符下标**），并始终把
 * 「本次读了哪一段、总共多长、下一段从哪儿开始」写进返回正文——
 * 模型不是靠猜，而是靠这行元信息决定要不要续读。三件事必须同时成立：
 *  ① 片段本身（slice）；
 *  ② 明确的 `[第 X–Y 字 / 共 N 字]` 头，让模型看见自己在文件里的位置；
 *  ③ 还有内容时给出 `offset=Y` 的确切续读指令（没有这行，模型会以为读完了）。
 *
 * 纯字符串处理、无 IO，单测直接覆盖（见 TextWindowsTest）。
 */
internal object TextWindows {

    /** 一次默认吐给模型的字符数（≈ 上下文安全区，四条读取工具统一口径） */
    const val DEFAULT_LIMIT = 8000

    /** 返回给模型的分页结果 */
    data class Page(
        /** 本页正文 */
        val text: String,
        /** 本页在全文中的起止（左闭右开，字符下标） */
        val start: Int,
        val end: Int,
        /** 全文总字符数 */
        val total: Int
    ) {
        val hasMore: Boolean get() = end < total

        /** 头部元信息：位置 + 总量 + 续读指令（模型据此决定下一页） */
        fun header(fileLabel: String = ""): String {
            val where = if (fileLabel.isBlank()) "" else "$fileLabel · "
            if (total == 0) return "[${where}文件为空]"
            val sb = StringBuilder()
            sb.append("[").append(where)
                .append("第 ").append(start + 1).append("–").append(end)
                .append(" 字 / 共 ").append(total).append(" 字")
            if (hasMore) {
                sb.append("；后面还有 ").append(total - end).append(" 字。要看后续内容，"
                    + "**再调一次本工具并传 offset=").append(end).append("**（同名参数），不要以为读完了")
            } else {
                sb.append("；已到文件末尾")
            }
            sb.append("]")
            return sb.toString()
        }

        /** 完整返回正文 = 头部元信息 + 本页 */
        fun render(fileLabel: String = ""): String =
            if (text.isEmpty()) header(fileLabel) else header(fileLabel) + "\n" + text
    }

    /**
     * 按字符窗口切一页。
     *
     * offset 越界（负数→0；超过总量→空页，头里写明"已到末尾/越界"）都不抛异常：
     * 模型传错参数时应该拿到可读的纠正信息，而不是一个 [FAIL] 让它反复试。
     */
    fun page(text: String, offset: Int, limit: Int = DEFAULT_LIMIT): Page {
        val total = text.length
        val cap = limit.coerceIn(200, 200_000)
        val start = offset.coerceIn(0, total)
        val end = (start + cap).coerceAtMost(total)
        return Page(text.substring(start, end), start, end, total)
    }
}
