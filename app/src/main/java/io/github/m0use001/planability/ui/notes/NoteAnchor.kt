package io.github.m0use001.planability.ui.notes

/**
 * v53.9 `[[笔记#小节]]` 的锚点定位。
 *
 * 为什么只做到「把光标停在小节标题行」而不做预览态滚动：
 * 预览是 `verticalScroll` 里的一整段 `MarkdownView`，没有按块暴露测量位置，
 * 想在预览里精确滚到某个标题只能把预览拆成块级列表重建一遍——那是第 3 期
 * 「块层 + 预览为主」的活。编辑器里定位是现成能力（大纲跳转早就在用），
 * 且"跳到小节"之后的下一步通常就是读或改，落在编辑器里不亏。
 *
 * 匹配口径（与 Obsidian/Logseq 的直觉一致）：
 * - 大小写不敏感；
 * - 锚点自带 `#`（`[[X### 小节]]` 这种手写）先剥掉；
 * - 标题级别不参与匹配（`## 结果` 与 `结果` 视为同一节）；
 * - 精确相等优先，其次「标题包含锚点」兜底（用户常只写半截标题）。
 */
object NoteAnchor {

    private val HEADING_LINE = Regex("""^(#{1,6})\s*(.+)$""")

    data class Heading(val offset: Int, val level: Int, val text: String)

    /** 按出现顺序列出所有标题及其**字符偏移**（用作 `TextRange` 的光标位置）。 */
    fun headings(content: String): List<Heading> {
        if (content.isEmpty()) return emptyList()
        val out = ArrayList<Heading>()
        var offset = 0
        for (line in content.split('\n')) {
            val t = line.trimStart()
            val m = HEADING_LINE.find(t)
            if (m != null) {
                out += Heading(offset + (line.length - t.length), m.groupValues[1].length, m.groupValues[2].trim())
            }
            offset += line.length + 1   // +1 = 换行符
        }
        return out
    }

    /** 锚点 → 光标偏移；找不到返回 null（调用方保持"打开就是顶部"，不报错）。 */
    fun findOffset(content: String, anchor: String): Int? {
        val want = anchor.trim().trimStart('#').trim()
        if (want.isEmpty()) return null
        val list = headings(content)
        list.firstOrNull { it.text.equals(want, ignoreCase = true) }?.let { return it.offset }
        list.firstOrNull { it.text.contains(want, ignoreCase = true) }?.let { return it.offset }
        return null
    }
}
