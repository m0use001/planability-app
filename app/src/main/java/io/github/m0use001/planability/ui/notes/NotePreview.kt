package io.github.m0use001.planability.ui.notes

import io.github.m0use001.planability.data.NoteEntity
import java.util.Calendar

/**
 * v53.8 笔记列表的**纯文本层**：标题去重、Markdown 洗白、字数、相对时间。
 *
 * 为什么单独抽出来：列表行原来直接 `content.take(30)`，于是每一行都在**重复标题**
 * （标题本来就是首行）并把 `#`、`[[ ]]`、`|` 这些标记原样漏出来（真机截图：
 * 「Alpha / # Alpha」「Links [[Alpha]] and [[Beta|B]]」）。这类问题属于"看着丑"，
 * 但它同时也是测试点——纯函数才好钉住边界。
 */
object NotePreview {

    private val HEADING = Regex("""^#{1,6}\s*""")
    private val TASK = Regex("""^[-*+]\s+\[[ xX]]\s*""")
    private val BULLET = Regex("""^[-*+]\s+""")
    private val ORDERED = Regex("""^\d+[.)]\s+""")
    private val QUOTE = Regex("""^>\s*""")
    private val WIKI = Regex("""!?\[\[([^\[\]|#\n]{1,120})(?:#[^\[\]|\n]{0,60})?(?:\|([^\[\]\n]{0,60}))?]]""")
    private val MD_LINK = Regex("""\[([^\]]+)]\(([^)\s]+)\)""")
    private val IMAGE = Regex("""!\[[^\]]*]\([^)]*\)""")
    private val EMPHASIS = Regex("""(\*\*|__|~~|`)""")
    private val WS = Regex("""\s+""")

    /** 与编辑器同口径的标题：首个非空行去掉 `#` 与空格、截 30 字。 */
    fun titleOf(content: String): String =
        content.lineSequence().firstOrNull { it.isNotBlank() }
            ?.trimStart()?.removePrefix("#")?.trimStart()?.take(30)?.trim()
            ?: ""

    /**
     * 标题行 → (级别, 正文)。
     *
     * **只吃掉 `#` 与随后的空白**。这里踩过一次真机 bug：用 `^(#{1,6})\s*\S` 匹配后
     * 按匹配长度截断，`\S` 是"后面有内容"的判据、却把标题首字一起切掉了——
     * `# Gamma` 渲染成 "amma"。所以匹配与截断必须是两件事。
     */
    fun splitHeading(line: String): Pair<Int, String>? {
        val t = line.trimStart()
        val hashes = t.takeWhile { it == '#' }.length
        if (hashes !in 1..6) return null
        val body = t.drop(hashes).trim()
        // 后面必须有内容，否则 `###` 这种空标题不进标题分支
        if (body.isEmpty()) return null
        return hashes to body
    }
    /**
     * 列表行的正文预览：**跳过与标题重复的首行**，洗掉 Markdown 标记，压成一行。
     *
     * 首行判定用「去标记后相等」，这样 `# Alpha` 与标题 `Alpha` 也能认出是同一行，
     * 否则列表里每条都会出现"标题 + 一模一样的副行"，那就是用户说的「简陋」里最扎眼的一条。
     */
    fun body(note: NoteEntity, maxChars: Int = 80): String =
        flatten(note.content, note.title, maxChars)

    /**
     * 任意文本 → 一行可读摘要（列表行与**搜索结果片段**共用。
     * 检索返回的 snippet 是原文窗口，同样会带 `#`、`[[ ]]`、`|`，不洗就是截图里那种"raw markdown 直接糊脸上"）。
     */
    fun flatten(text: String, title: String = "", maxChars: Int = 80): String {
        val lines = text.lines()
        var start = 0
        while (start < lines.size && lines[start].isBlank()) start++
        if (start < lines.size && title.isNotBlank()) {
            val first = stripLine(lines[start]).trim()
            if (first.equals(title.trim(), ignoreCase = true)) start++
        }
        val sb = StringBuilder()
        for (i in start until lines.size) {
            if (sb.length > maxChars * 2) break
            val s = stripLine(lines[i])
            if (s.isNotBlank()) {
                if (sb.isNotEmpty()) sb.append(' ')
                sb.append(s)
            }
        }
        val flat = sb.toString().replace(WS, " ").trim()
        return when {
            flat.isEmpty() -> "（还没有正文）"
            flat.length > maxChars -> flat.take(maxChars) + "…"
            else -> flat
        }
    }

    /** 单行洗白：去结构前缀 + 行内标记，链接留文字、图片整段去掉。 */
    fun stripLine(raw: String): String {
        var s = raw.trim()
        if (s.isEmpty()) return ""
        s = IMAGE.replace(s, "")
        s = WIKI.replace(s) { m -> m.groupValues[2].ifBlank { m.groupValues[1] } }
        s = MD_LINK.replace(s) { m -> m.groupValues[1] }
        s = HEADING.replace(s, "")
        s = TASK.replace(s, "")
        s = BULLET.replace(s, "· ")
        s = ORDERED.replace(s, "")
        s = QUOTE.replace(s, "")
        // 2026-11-12：与正文表格同口径——`$…$` 里的竖线不当分隔符
        if (s.startsWith("|")) s = io.github.m0use001.planability.core.splitTableRow(s).joinToString(" · ") { it.trim() }
        s = EMPHASIS.replace(s, "")
        return s.trim()
    }

    /** 字数：CJK 一字算一字，拉丁按词；用来在列表上给出"这篇有多大"的量感。 */
    fun wordCount(content: String): Int {
        if (content.isBlank()) return 0
        var cjk = 0
        var latinWords = 0
        var inWord = false
        for (ch in content) {
            val isLatin = ch.isLetterOrDigit() && ch.code < 0x2E80
            if (ch.code in 0x3400..0x9FFF || ch.code in 0xF900..0xFAFF) { cjk++; inWord = false }
            else if (isLatin) { if (!inWord) { latinWords++; inWord = true } }
            else inWord = false
        }
        return cjk + latinWords
    }

    /**
     * 相对时间：刚刚 / N 分钟前 / 今天 HH:mm / 昨天 / N 天前 / M月d日 / yyyy年M月d日。
     * 用日历日计算（不是 24 小时差）——「昨天 23:50」在今天 00:10 看应该还是"昨天"。
     */
    fun relativeTime(at: Long, now: Long = System.currentTimeMillis()): String {
        if (at <= 0L) return ""
        val diff = now - at
        // 未来时间戳（时钟偏差 / 旧版本按"当天 0 点"落库）一律当"刚刚"：
        // 真机上真的踩到过 updatedAt=次日 00:00，直接按日期显示会变成"明天更新过"。
        if (diff < 60_000) return "刚刚"
        if (diff < 3_600_000) return "${diff / 60_000} 分钟前"
        val a = Calendar.getInstance().apply { timeInMillis = at }
        val b = Calendar.getInstance().apply { timeInMillis = now }
        val aDay = a.get(Calendar.YEAR) * 1000 + a.get(Calendar.DAY_OF_YEAR)
        val bDay = b.get(Calendar.YEAR) * 1000 + b.get(Calendar.DAY_OF_YEAR)
        val days = bDay - aDay
        val hm = "%02d:%02d".format(a.get(Calendar.HOUR_OF_DAY), a.get(Calendar.MINUTE))
        return when {
            days == 0 -> "今天 $hm"
            days == 1 -> "昨天"
            days in 2..6 -> "$days 天前"
            a.get(Calendar.YEAR) == b.get(Calendar.YEAR) ->
                "${a.get(Calendar.MONTH) + 1}月${a.get(Calendar.DAY_OF_MONTH)}日"
            else -> "${a.get(Calendar.YEAR)}年${a.get(Calendar.MONTH) + 1}月${a.get(Calendar.DAY_OF_MONTH)}日"
        }
    }
}
