package io.github.m0use001.planability.ai.rag

import java.security.MessageDigest

/**
 * v53.7 第 2 期地基：**笔记块层**（派生视图，不改存储）。
 *
 * 借鉴（已读源码）：
 * - 思源 SiYuan：块是带稳定 id 的 AST 节点，块级编辑走
 *   `Transaction{ DoOperations:[{Action, Data, ParentID/PreviousID/NextID, LockType}] }`，
 *   先全量校验再原子提交 → 本文件抄的是「**先校验、再整体应用**」的事务形状。
 * - MarkText：块树才是真源、markdown 是序列化 → 印证「块」应当是编辑单位。
 *
 * 为什么**不**照抄稳定块 id：思源把 id 写进正文（IAL `{: id="…"}`），会污染用户手写的 md。
 * Planability 的笔记是纯 markdown 字符串，所以这里用「**行区间 + 内容指纹（sha）**」定位：
 * AI 读到的每个块都带 `sha`，写回时必须原样带上——指纹不符说明**人已经改过这块**，
 * 直接判冲突而不是覆盖。这就是思源 `LockType`/哈希校验的软化版，零正文污染。
 *
 * 块粒度与预览渲染保持一致（MdTextLines 的分支）：代码围栏 / 标题 / 引用 / 表格 /
 * 列表项 / 段落。空行是分隔符，本身不成块——否则每两段之间都会多出一个空块，索引噪声大。
 */
object NoteBlocks {

    /** 一个块：`startLine` 含、`endLine` 不含（`content.lines()` 的下标）。 */
    data class Block(
        val index: Int,
        val kind: String,
        val text: String,
        val sha: String,
        val startLine: Int,
        val endLine: Int
    )

    /** 一次块级修改：按 [Block.index] 定位，指纹不符即冲突。 */
    data class Edit(
        val index: Int,
        val expectSha: String,
        /** 整块的**新原文**（含 `## ` / `- ` 这类标记）；空串 = 删掉这块 */
        val newText: String
    )

    data class Result(val content: String, val applied: Int, val conflicts: List<Int>) {
        val ok: Boolean get() = conflicts.isEmpty()
    }

    private val HEADING = Regex("""^#{1,6}\s*\S""")
    private val LIST_ITEM = Regex("""^\s*([-*+]|\d+[.)])\s+""")

    /** 12 位十六进制内容指纹：够短（AI 生成的 token 少）、够长（碰撞可忽略）。 */
    fun sha(kind: String, text: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        md.update(kind.toByteArray(Charsets.UTF_8))
        md.update('\n'.code.toByte())
        md.update(text.toByteArray(Charsets.UTF_8))
        return md.digest().joinToString("") { "%02x".format(it) }.take(12)
    }

    fun split(content: String): List<Block> {
        val lines = content.lines()
        val out = ArrayList<Block>()
        var i = 0
        fun emit(kind: String, start: Int, end: Int) {
            val raw = lines.subList(start, end).joinToString("\n")
            out += Block(out.size, kind, raw, sha(kind, raw), start, end)
        }
        while (i < lines.size) {
            val line = lines[i]
            val t = line.trimStart()
            when {
                t.isBlank() -> i++
                t.startsWith("```") -> {
                    val start = i
                    i++
                    while (i < lines.size && !lines[i].trimStart().startsWith("```")) i++
                    if (i < lines.size) i++   // 收尾围栏（未闭合就到 EOF）
                    emit("code", start, i)
                }
                HEADING.containsMatchIn(t) -> { emit("heading", i, i + 1); i++ }
                t.startsWith(">") -> {
                    val start = i
                    while (i < lines.size && lines[i].trimStart().startsWith(">")) i++
                    emit("quote", start, i)
                }
                t.startsWith("|") -> {
                    val start = i
                    while (i < lines.size && lines[i].trimStart().startsWith("|")) i++
                    emit("table", start, i)
                }
                LIST_ITEM.containsMatchIn(t) -> { emit("item", i, i + 1); i++ }
                else -> {
                    // 段落：连续的「普通行」，遇到空行或任何上表结构即止
                    val start = i
                    while (i < lines.size) {
                        val s = lines[i].trimStart()
                        if (s.isBlank() || s.startsWith("```") || s.startsWith(">") ||
                            s.startsWith("|") || HEADING.containsMatchIn(s) ||
                            LIST_ITEM.containsMatchIn(s)
                        ) break
                        i++
                    }
                    emit("para", start, i)
                }
            }
        }
        return out
    }

    /**
     * 按 [edits] 改写 [content]：**全部校验通过才动手**（对应思源的事务语义）。
     * 任一指纹不符 / 下标越界 / 区间重叠 → 该项进 [Result.conflicts]，其余照常应用。
     *
     * 未命中的行**逐字节保留**：不做「重新拼装块」的重排，避免把用户原有的空行/缩进洗掉。
     */
    fun apply(content: String, edits: List<Edit>): Result {
        if (edits.isEmpty()) return Result(content, 0, emptyList())
        val lines = content.lines()
        val blocks = split(content).associateBy { it.index }
        val conflicts = ArrayList<Int>()
        data class Repl(val start: Int, val end: Int, val lines: List<String>, val index: Int)

        val repls = ArrayList<Repl>()
        for (e in edits) {
            val b = blocks[e.index]
            if (b == null) { conflicts += e.index; continue }
            if (b.sha != e.expectSha) { conflicts += e.index; continue }
            // 同一块的第二次编辑、以及任何区间交叠，都判冲突（AI 一次只该改一遍）
            if (repls.any { r -> r.start < b.endLine && b.startLine < r.end }) { conflicts += e.index; continue }
            val newLines = if (e.newText.isEmpty()) emptyList() else e.newText.lines()
            repls += Repl(b.startLine, b.endLine, newLines, e.index)
        }
        if (repls.isEmpty()) return Result(content, 0, conflicts)

        val out = ArrayList<String>(lines.size)
        var cursor = 0
        repls.sortedBy { it.start }.forEach { r ->
            while (cursor < r.start) { out += lines[cursor]; cursor++ }
            out += r.lines
            cursor = r.end
        }
        while (cursor < lines.size) { out += lines[cursor]; cursor++ }
        return Result(out.joinToString("\n"), repls.size, conflicts)
    }

    /** 给 AI 读的紧凑块清单（一行一块：`#0 heading 「…」`）。 */
    fun outline(content: String, maxChars: Int = 60): String =
        split(content).joinToString("\n") { b ->
            val one = b.text.replace("\n", " ⏎ ")
            val head = if (one.length > maxChars) one.take(maxChars) + "…" else one
            "#${b.index} ${b.kind} sha=${b.sha} 「$head」"
        }
}
