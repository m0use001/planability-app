package io.github.m0use001.planability.core

/**
 * 表格行切分（2026-11-12「数学显示严重问题」根修）。
 *
 * 旧实现在整行上 `split('|')`——**行内公式里的竖线也被当成了列分隔符**：
 * `| 5–6 | 用 $|b_n-1|<\frac1{100}$ 逐步逼近 |` 被劈成「用 $」「b_n-1」「<\frac1{100}」…，
 * 于是表格错列、`$` 裸奔、公式整段不渲染（用户数学课件某次会话实况截图）。
 *
 * 规则：`$…$` 段落内部的 `|` 一律不当分隔符；`\|` 是转义（LaTeX 范数/竖线），
 * 反斜杠与竖线原样保留；首尾成对的 `|` 剥掉。
 *
 * 调用点：笔记/聊天渲染（NotesScreen）、笔记卡预览（NotePreview）、
 * AI 导出 Word（OfficeWriter）——三处必须同口径，否则"渲染对、导出错"。
 */
fun splitTableRow(row: String): List<String> {
    var t = row.trim()
    if (t.startsWith("|")) t = t.substring(1)
    val cells = ArrayList<String>()
    val cur = StringBuilder()
    var inMath = false
    var i = 0
    while (i < t.length) {
        val c = t[i]
        when {
            c == '\\' && i + 1 < t.length -> { cur.append(c).append(t[i + 1]); i += 2; continue }
            c == '$' -> { inMath = !inMath; cur.append(c) }
            c == '|' && !inMath -> { cells.add(cur.toString().trim()); cur.clear() }
            else -> cur.append(c)
        }
        i++
    }
    // 行尾成对 `|` 会留下一个空壳尾格：有其它内容格时丢掉
    val tail = cur.toString().trim()
    if (tail.isNotEmpty() || cells.isEmpty()) cells.add(tail)
    return cells
}
