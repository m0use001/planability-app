package io.github.m0use001.planability.ai.rag

/**
 * v49 知识库：`[[标题]]` / `[[标题|别名]]` 解析。
 *
 * 借鉴思源/Obsidian 的块引用形态，但只做**笔记级**链接（跨笔记关联 + 反链），
 * 不做块级锚点：手机上块级引用没有稳定的编辑体验，收益低于成本。
 *
 * 规则：
 * - 代码块（``` 围栏）里的 `[[x]]` 不解析（写代码/示例时不该产生知识库边）；
 * - 标题归一化 = 去首尾空白 + 连续空白压成一个（大小写不敏感比较交给 SQL 的 NOCASE）；
 * - 标题最多 120 字、别名最多 60 字，超长视为普通文本（防止正则病态匹配）；
 * - `![[x]]`（嵌入语法）同样算链接。
 */
object WikiLinks {

    /**
     * 一条 `[[标题#锚点|别名]]`。
     *
     * v53.6（第 1 期「链接可走」）：补 `#锚点` —— Obsidian/Logseq 的 `[[笔记#小节]]` 写法。
     * 锚点只作为**渲染/跳转提示**保留，不参与链接图的边（边仍按标题建：同一篇里的
     * `[[X#a]]`/`[[X#b]]` 合成一条边，与人的直觉一致）；锚点定位在第 1 期后段补。
     */
    data class Ref(val title: String, val alias: String = "", val anchor: String = "")

    // 组：1=标题，2=锚点（可空），3=别名（可空）。标题里不许出现 # —— 那是锚点分隔符。
    private val REF = Regex("""!?\[\[([^\[\]|#\n]{1,120})(?:#([^\[\]|\n]{0,60}))?(?:\|([^\[\]\n]{0,60}))?]]""")

    fun parse(content: String): List<Ref> {
        if (content.isEmpty() || !content.contains("[[")) return emptyList()
        val body = stripFences(content)
        val out = LinkedHashMap<String, Ref>()
        REF.findAll(body).forEach { m ->
            val title = normalize(m.groupValues[1])
            if (title.isEmpty()) return@forEach
            val anchor = normalize(m.groupValues[2])
            val alias = m.groupValues[3].trim()
            out.putIfAbsent(title.lowercase(), Ref(title, alias, anchor))
        }
        return out.values.toList()
    }

    /** 归一化：去首尾空白 + 内部连续空白压成一个 */
    fun normalize(title: String): String = title.trim().replace(Regex("\\s+"), " ")

    /** 删掉指向某标题的引用（保留其余文本），用于 unlink；带锚点/别名的变体一并删 */
    fun remove(content: String, title: String): String {
        val t = normalize(title)
        if (t.isEmpty()) return content
        val re = Regex("""!?\[\[\Q$t\E(?:#[^\[\]|\n]{0,60})?(?:\|[^\[\]\n]{0,60})?]]""", RegexOption.IGNORE_CASE)
        return re.replace(content, "")
    }

    /**
     * v53.6 第 1 期 b：**提及但未链接**判定（Logseq `get-unlinked-references` 的口径）。
     *
     * 规则（逐条对应一个会真实发生的输入）：
     * - 只认**纯文本段**：先剥掉 `[[…]]` 引用本身，否则「已经链接过」的文本会被算成"提及"，
     *   面板就变成反链的复读机；
     * - 代码块不算（写示例时提到标题不该产生建议）；
     * - 大小写不敏感的子串匹配——中文没有词边界，英文按子串也比按单词更宽松可控；
     * - 标题至少 2 个字，单字标题（「我」「A」）会命中满篇，只制造噪音。
     */
    fun mentions(content: String, title: String): Boolean {
        val t = normalize(title)
        if (t.length < 2) return false
        val body = stripFences(content).replace(REF, " ")
        return body.contains(t, ignoreCase = true)
    }

    /** 去掉 ``` 围栏块（含未闭合的尾巴），保留行结构 */
    private fun stripFences(content: String): String {
        if (!content.contains("```")) return content
        val sb = StringBuilder(content.length)
        var inFence = false
        content.lineSequence().forEach { line ->
            if (line.trimStart().startsWith("```")) {
                inFence = !inFence
                sb.append('\n')
            } else if (!inFence) {
                sb.append(line).append('\n')
            } else {
                sb.append('\n')
            }
        }
        return sb.toString()
    }
}
