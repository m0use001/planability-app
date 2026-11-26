package io.github.m0use001.planability.ui.info

/**
 * 超长助手消息 → 多个列表条目（2026-11-21，用户拍板「切，但保住跨块选词」）。
 *
 * ## 为什么要切
 *
 * LazyColumn 的条目测量是**原子的**：一个 item 一旦进场，就得在一帧里量完它全部内容。
 * 会话里 AI 的长回答实测有 **7298px（≈ 2085dp ≈ 7 个屏幕）**，量一次 20~50ms——
 * 快速滑动经过它时就是那一下「抽搐」。原始采样（tmp/trace4.txt）里每次停留都精确
 * 对应某个巨型条目 `as` 突变：5315px→50ms、4243px→37ms、3458px→31ms。
 *
 * 按块切开后，一帧只需量「刚进场的那一两块」，成本随可见块分摊到多帧。
 *
 * ## 为什么按行切、而不是按围栏段重拼
 *
 * 切点必须能**逐字节还原**原文，否则同一份 markdown 会被渲染成两种样子。
 * 所以这里只按行切、在行边界处切，`chunks.joinToString("\n") == 原文` 由构造保证
 * （单测钉死）。切点安全条件：不在 ``` 围栏内、前一行是空行（表格/列表内部没有空行，
 * 因此天然不会切在表格或列表中间）。
 *
 * ## 选词
 *
 * 切开会让「一条消息 = 一个 SelectionContainer」失效，所以配合把 SelectionContainer
 * 上提到列表层（ChatScreen.MessageList）：所有可见块共用同一个选区注册器，
 * 跨块拖选仍然成立。
 */

/** 小于这个长度的消息不切（切开只为省测量，短消息本身就不贵）。 */
internal const val MSG_PART_MIN_CHARS = 800

/** 每块的字符上限。中文在手机宽度上约 22 字/行、20dp/行 —— 400 字 ≈ 360dp ≈ 1260px，
 *  量一次 4~6ms（实测巨型条目 5315px 要 50ms）。 */
internal const val MSG_PART_CHUNK_CHARS = 400

/** 可以在其**前面**切行的块起始行：列表项/标题/引用。
 *  列表序号是**原文里的字面量**（MdTextLines 直接画 `1.`），所以切开不会重新编号。 */
private fun isBlockStart(trimmed: String): Boolean =
    trimmed.startsWith("- ") || trimmed.startsWith("* ") || trimmed.startsWith("> ") ||
        trimmed.startsWith("#") ||
        Regex("^\\d+[.)]\\s").containsMatchIn(trimmed)

/**
 * 把 markdown 按安全行边界切成 ≤[maxChars] 的块。
 * 保证：`chunks.joinToString("\n") == md`（逐字节）；围栏代码块绝不横跨两块；
 * 不在表格/列表内部切（前一行必须是空行）。
 */
internal fun markdownChunks(md: String, maxChars: Int = MSG_PART_CHUNK_CHARS): List<String> {
    if (md.length <= maxChars) return listOf(md)
    val lines = md.split('\n')
    val out = ArrayList<String>(4)
    var start = 0
    var inFence = false
    var chars = 0
    // 最近一个「安全切点」（行号：切在 lines[cut] 之前）
    var safeCut = -1
    for (i in lines.indices) {
        val line = lines[i]
        val trimmed = line.trimStart()
        val fenceTicks = countTicks(trimmed)
        // 单行内联围栏（```lang code```，一行里两处 ```）不改变 inCode 状态
        val fenceOpenClose = fenceTicks == 1 && trimmed.startsWith("```")
        if (fenceOpenClose) inFence = !inFence
        chars += line.length + 1
        // 切点候选：围栏外，且满足下列之一
        //  · 上一行是空行 —— 段/块之间（表格、列表内部没有空行，天然不切在表格中间）
        //  · 本行是块起始行（列表项/标题/引用）—— 列表序号是原文字面量，切开不重新编号
        val safeHere = !inFence && !fenceOpenClose && i > start &&
            (lines[i - 1].isBlank() || isBlockStart(trimmed))
        if (safeHere) {
            safeCut = i
        }
        if (chars >= maxChars && safeCut > start) {
            out += lines.subList(start, safeCut).joinToString("\n")
            start = safeCut
            chars = 0
            for (j in start..i) chars += lines[j].length + 1
            safeCut = -1
        }
    }
    if (start < lines.size) out += lines.subList(start, lines.size).joinToString("\n")
    return out.ifEmpty { listOf(md) }
}

/** 一行里 ``` 出现的次数（用于区分「围栏开关行」与「单行内联围栏」）。 */
private fun countTicks(line: String): Int {    var n = 0
    var i = 0
    while (i + 2 < line.length) {
        if (line[i] == '`' && line[i + 1] == '`' && line[i + 2] == '`') {
            n++
            i += 3
        } else {
            i++
        }
    }
    return n
}

/**
 * 只**数**一条消息会被切成几片（2026-11-23 v78 崩溃根修）。
 *
 * 背景：进超大会话（◆ 微积分：681 条消息 / 123 条长回复）时，原实现为了
 * 先拿到「条目数」而对**每条已完成长消息**跑 `ChatComp.parse + splitMessageParts`
 * ——几百次全量正则解析 + 段对象常驻，主线程被灌满 → ANR（实测 5s 输入超时，
 * 三次）→ 系统杀进程（用户看到的「闪退」），PSS 一度 768MB。
 *
 * 现在切条信息改成两级：
 *  · **数片**只做 O(字符) 的纯扫描（本函数），不 parse、不建段、不留字符串；
 *  · **每片的内容**在真正滚到它时才 `markdownChunks(...)[i]` + `ChatComp.parse`。
 * 于是进任何长度会话，主线程成本都只与「文本总字节数」有关，不再与「历史长消息条数」有关。
 *
 * 口径与 [markdownChunks] 完全一致（同一份切点规则），所以片数、片内容与老路径可逐字节对上。
 */
internal fun countMessageParts(
    md: String,
    maxChars: Int = MSG_PART_CHUNK_CHARS,
    minChars: Int = MSG_PART_MIN_CHARS,
): Int {
    if (md.length < minChars) return 1
    if (md.length <= maxChars) return 1
    val lines = md.split('\n')
    var count = 0
    var start = 0
    var inFence = false
    var chars = 0
    var safeCut = -1
    for (i in lines.indices) {
        val line = lines[i]
        val trimmed = line.trimStart()
        val fenceTicks = countTicks(trimmed)
        val fenceOpenClose = fenceTicks == 1 && trimmed.startsWith("```")
        if (fenceOpenClose) inFence = !inFence
        chars += line.length + 1
        val safeHere = !inFence && !fenceOpenClose && i > start &&
            (lines[i - 1].isBlank() || isBlockStart(trimmed))
        if (safeHere) safeCut = i
        if (chars >= maxChars && safeCut > start) {
            count++
            start = safeCut
            chars = 0
            for (j in start..i) chars += lines[j].length + 1
            safeCut = -1
        }
    }
    if (start < lines.size) count++
    return count.coerceAtLeast(1)
}

/** 取第 [index] 片原文（与 [countMessageParts] 同一套切点，越界取末片）。 */
internal fun messageChunkAt(
    md: String,
    index: Int,
    maxChars: Int = MSG_PART_CHUNK_CHARS,
): String {
    val chunks = markdownChunks(md, maxChars)
    return chunks.getOrElse(index) { chunks.lastOrNull() ?: md }
}

/**
 * 把一条消息的显示段切成若干「片」，每片一个列表条目。
 *
 * · 总字符数 < [MSG_PART_MIN_CHARS] → 原样一片（不改变现有渲染）；
 * · 每个 `ChatSegment.Md` 内部按 [markdownChunks] 再切；`ChatSegment.Card` 不可切，
 *   作为当前片的收尾（卡片是交互组件，跨片会拆散它的状态）；
 * · 返回顺序 = 原文顺序；返回单元素时调用方按老路径渲染。
 */
internal fun splitMessageParts(
    segments: List<ChatSegment>,
    maxChars: Int = MSG_PART_CHUNK_CHARS,
    minChars: Int = MSG_PART_MIN_CHARS,
): List<List<ChatSegment>> {
    val total = segments.sumOf { if (it is ChatSegment.Md) it.text.length else 0 }
    if (total < minChars) return listOf(segments)

    val parts = ArrayList<MutableList<ChatSegment>>(4)
    var cur = ArrayList<ChatSegment>(4)
    var curChars = 0

    fun close() {
        if (cur.isNotEmpty()) {
            parts += cur
            cur = ArrayList(4)
            curChars = 0
        }
    }

    for (seg in segments) {
        when (seg) {
            is ChatSegment.Md -> {
                val chunks = markdownChunks(seg.text, maxChars)
                for (chunk in chunks) {
                    // 已有内容且装不下了 → 先收片（新块另起一片）
                    if (cur.isNotEmpty() && curChars + chunk.length > maxChars) close()
                    cur += ChatSegment.Md(chunk)
                    curChars += chunk.length
                    if (curChars >= maxChars) close()
                }
            }
            is ChatSegment.Card -> {
                // 卡片跟在当前片末尾；当前片已经有实质正文时也可以独占一片
                if (curChars >= maxChars) close()
                cur += seg
            }
        }
    }
    close()
    return if (parts.size <= 1) listOf(segments) else parts
}
