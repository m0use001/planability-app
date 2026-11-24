package io.github.m0use001.planability.ai

import io.github.m0use001.planability.data.ChatMessage

/**
 * 上下文/摘要用到的**纯函数**（不碰 Graph / Room / Android），给单测用。
 *
 * ## 为什么要单独一个文件
 * 这个仓库的单测依赖只有 junit（没有 Robolectric / MockK），凡是 `ContextEngine.assemble`
 * 那种要 Graph 的路径在 JVM 里跑不起来，只能"钉源码"——那种断言一改实现就瞎。
 * 所以把「哪些消息算会话内容」「切点怎么算」「摘要怎么合并」抽成纯函数，
 * 测试可以给出**确定性断言**（输入 → 输出），而不是 grep 源码。
 *
 * 来源：`设计文档` §7.4 的第一档。
 */

/** 每轮摘要保留最近多少条（tool 消息也计入） */
const val KEEP_RECENT = 8

/**
 * 会话内容的**唯一**过滤口径。
 *
 * 改之前全仓有 4 份不同的过滤（摘要输入 F1、读侧载荷 F2、上下文显示 F3、记忆检索 F4），
 * 其中 F1 只收 `user/assistant`、F2 还收 `tool`，于是 `lastSummarizeIndex`（按 F1 算）
 * 被 F2 当成下标用 → 下标漂移：**该摘要的没摘要掉**（F1 ⊆ F2，只会少不会多）。
 *
 * 现在两处都走这里。规则：
 * - `id > clearBefore`（用户手动"从这里重新开始"之后的不算）
 * - 角色只收 user / assistant / tool
 * - 正文与 toolCalls 同时为空的消息不要（流式占位、被清空的空壳）
 */
fun effectiveTranscript(msgs: List<ChatMessage>, clearBefore: Long): List<ChatMessage> =
    msgs.filter {
        it.id > clearBefore &&
            it.role in listOf("user", "assistant", "tool") &&
            (it.content.isNotBlank() || it.toolCallsJson.isNotBlank())
    }

/**
 * 摘要切点：前 [n] 条里，最近 [keepRecent] 条不摘要。
 * 返回 0 表示"没有可摘要的"。
 */
fun summarizeCut(n: Int, keepRecent: Int = KEEP_RECENT): Int =
    (n - keepRecent).coerceAtLeast(0)

/**
 * 把切点吸附到"工具轮边界"上：切点若落在一条 `tool` 消息上，说明它的 assistant 调用
 * 在切点之前 —— 这样切出来的历史会以"没有对应调用的工具结果"开头，模型会 400 或开始编造。
 *
 * 处理：把切点**往后**推到第一条非 tool 消息（被跳过的 tool 结果算进已摘要部分）。
 * 只往前推、不往后退：往后退会让索引倒退（重复摘要）。
 */
fun snapCutToToolSafe(msgs: List<ChatMessage>, cut: Int): Int {
    var c = cut.coerceIn(0, msgs.size)
    while (c < msgs.size && msgs[c].role == "tool") c++
    return c
}

/**
 * 单条消息的 token 估算：正文 + 工具调用 JSON，再 +4 作为消息框架开销。
 *
 * 之前只算 `content`，于是**带 toolCalls 的 assistant 消息被估成 0**，
 * 而工具结果（一次 OCR 可以到 1.2 万 token）恰恰是最容易撑爆窗口的东西。
 */
fun estMessageTokens(m: ChatMessage): Int =
    ContextEngine.estTokens(m.content) + ContextEngine.estTokens(m.toolCallsJson) + 4

/**
 * 发出去的 `ChatApiMessage` 的同一条估算（content + toolCallsJson + 4）。
 *
 * 读侧 `capHistory` 曾经只算 `estTokens(content)`：带大 toolCallsJson 的 assistant 被估成 0，
 * 预算形同虚设；而摘要侧早就用 [estMessageTokens] 算了 toolCalls —— 两套数必然对不上。
 */
fun estApiMessageTokens(m: ChatApiMessage): Int =
    ContextEngine.estTokens(m.content) + ContextEngine.estTokens(m.toolCallsJson) + 4

/**
 * 摘要锚点（消息 id）→ transcript 里的起始下标：第一条 `id > 锚点` 的消息。
 *
 * 这是 `lastSummarizedMessageId`（v39，M6）唯一的换算口径，读侧 `assemble` 与摘要侧
 * `summarizeNow` 都走它。要求列表按 id 升序（`ChatDao.messagesOf` 的 `ORDER BY id` 保证）：
 * - 锚点 0（没摘要过）→ 0；
 * - 锚点在列表里 → 它后面那条的下标；
 * - 锚点 id 比现存所有消息都大（它自己及以后被删过）→ `msgs.size`：
 *   剩下的都是比锚点更早的消息，**本来就都已摘要**（摘要正文在 memoryPrompt 里），
 *   全部裁掉是正确语义，重发才是冗余。
 */
fun summarizeStartIndex(msgs: List<ChatMessage>, anchorId: Long): Int {
    if (anchorId <= 0L) return 0
    val first = msgs.indexOfFirst { it.id > anchorId }
    return if (first < 0) msgs.size else first
}

/**
 * 摘要锚点只前进不后退（两条摘要路径并发时，先算完的那条不会被后来者覆盖回去）。
 * 消息 id 全会话单调递增，max 就是"覆盖到更晚"。
 */
fun nextSummarizeAnchor(currentAnchor: Long, coveredId: Long): Long = maxOf(currentAnchor, coveredId)

/**
 * 把请求历史对半拆开的切点：从 [desired] 往回吸附到**组边界**，
 * 绝不把 tool_calls 声明与它的结果拆开（拆开 = 下次请求 400 或模型编造，与 capHistory 同一条铁律）。
 * 返回值 ∈ [1, msgs.size]；开头就是一整组拆不动时返回 1（至少拆掉一条，主循环还有次数兜底）。
 */
fun splitSafeIndex(msgs: List<ChatApiMessage>, desired: Int): Int {
    var i = desired.coerceIn(1, msgs.size)
    // i 不是组起点：它是 tool 结果，或它的前一条是带 toolCalls 的 assistant（它的结果还没收完）。
    // i == msgs.size 是合法切点（保留段为空，调用方自己拒绝），此时没有 msgs[i] 可看
    while (i > 1 && i < msgs.size && (msgs[i].role == "tool" ||
                (msgs[i - 1].role == "assistant" && msgs[i - 1].toolCallsJson.isNotBlank()))
    ) i--
    return i
}

/**
 * 工具结果的**线上**预算（token）。只裁 wire 副本：DB 与 UI 永远是全文，可回溯
 * （学 Coomi"压缩后完整历史仍可回溯"）。一条工具结果 6000 token ≈ 一次超大读文件/OCR，
 * 超过它的会话里 capHistory 整组整组地丢——先就地裁它，保住工具对与最近对话。
 */
const val TOOL_RESULT_WIRE_TOKENS = 6000

/**
 * 头部占比。错误信息、退出码、结论几乎都在**尾部**（PURE ToolResultTruncation 的上游 bug 记录：
 * 只留头 32KB，把最有价值的尾巴恰恰丢了），所以头拿 60%、尾拿 40%。
 */
const val TOOL_RESULT_HEAD_RATIO = 0.6

/** 省略标记自身约 20 token，从预算里预留，免得标记把结果顶回超预算（PURE 同款手法） */
private const val ELISION_RESERVE_TOKENS = 32

data class ToolWireCopy(val text: String, val truncated: Boolean, val elidedChars: Int, val base64Runs: Int)

/**
 * ≥[minChars] 的连续 base64 段是内联二进制（图片/附件），对文本模型只有成本没有信息
 * （Coomi `sanitize_long_encoded_data` 同思想，ref-cost-study §3.4）。返回替换后文本与命中段数。
 */
fun elideInlineBase64(text: String, minChars: Int = 4096): Pair<String, Int> {
    if (text.length < minChars) return text to 0
    var hits = 0
    val replaced = Regex("[A-Za-z0-9+/]{$minChars,}={0,2}").replace(text) { mr ->
        hits++
        "[base64 内联数据省略 ${mr.value.length} 字符]"
    }
    return replaced to hits
}

/**
 * 工具结果的线上副本：先洗 base64，再按 [maxTokens] 预算做"头 + 省略标记 + 尾"。
 * 纯函数、零 IO——DB 里存的还是全文，wire 上只发裁过的。预算太小（头尾会重叠）时只留头：
 * 标记是元数据，丢了它 = 模型看到的数据静默缺失（PURE 同款取舍）。
 */
fun truncateToolResultWire(
    text: String,
    maxTokens: Int = TOOL_RESULT_WIRE_TOKENS,
    headRatio: Double = TOOL_RESULT_HEAD_RATIO,
): ToolWireCopy {
    val (cleaned, base64Runs) = elideInlineBase64(text)
    if (ContextEngine.estTokens(cleaned) <= maxTokens) return ToolWireCopy(cleaned, false, 0, base64Runs)
    val budget = (maxTokens - ELISION_RESERVE_TOKENS).coerceAtLeast(1)
    val headBudget = (budget * headRatio.coerceIn(0.0, 1.0)).toInt().coerceAtMost(budget)
    val tailBudget = budget - headBudget
    val headEnd = advanceByBudget(cleaned, 0, cleaned.length, headBudget)
    val tailStart = advanceByBudget(cleaned, cleaned.length, 0, tailBudget)
    if (tailBudget <= 0 || tailStart <= headEnd) {
        val head = cleaned.substring(0, headEnd).trimEnd()
        val elided = cleaned.length - head.length
        return ToolWireCopy(head + elisionMarker(elided), true, elided, base64Runs)
    }
    val head = cleaned.substring(0, headEnd).trimEnd()
    val tail = cleaned.substring(tailStart).trimStart()
    val elided = cleaned.length - head.length - tail.length
    return ToolWireCopy(head + "\n\n" + elisionMarker(elided) + "\n" + tail, true, elided, base64Runs)
}

private fun elisionMarker(elidedChars: Int): String =
    "…[中段省略约 $elidedChars 字；全文在会话记录里，用户可查看]…"

/**
 * 与 [ContextEngine.estTokens] 同一公式（CJK 1/字、其余 3.5 字符/token）的按预算取边界。
 * 正向（limit ≥ start）：返回**独占**终点下标（跨线字符算进头部）；
 * 反向（limit < start）：返回**含**的起点下标（跨线字符算进尾部）。
 * 预算 [budget] ≤ 0 时直接返回 start（不取任何内容）。
 */
private fun advanceByBudget(text: String, start: Int, limit: Int, budget: Int): Int {
    if (budget <= 0) return start
    var cjk = 0
    var other = 0
    if (limit >= start) {
        var i = start
        while (i < limit) {
            if (text[i].code >= 0x2E80) cjk++ else other++
            if ((cjk + other / 3.5).toInt() >= budget) return i + 1
            i++
        }
        return limit
    }
    var i = start - 1          // 反向：start 是独占端，读取要从 start-1 开始
    while (i >= limit) {
        if (text[i].code >= 0x2E80) cjk++ else other++
        if ((cjk + other / 3.5).toInt() >= budget) return i
        i--
    }
    return limit
}

/**
 * 合并滚动摘要：旧摘要在前、新摘要在后，超 [cap] 时保留**最新**的部分。
 *
 * 原来是 `merged.take(cap)` —— Kotlin 的 take 取的是**开头**，所以超限时被砍掉的
 * 正是刚写进去的新摘要，而 `lastSummarizeIndex` 照样推进 → 那段内容既不在 history
 * 也不在 summary 里（静默丢）。这里是 `takeLast`。
 */
fun mergeSummary(prev: String, addition: String, cap: Int): String {
    val base = if (prev.isBlank()) "" else prev + "\n\n"
    return (base + addition).takeLast(cap)
}

/**
 * v47：**轮分组**——「详细上下文编辑」的选取单位。
 * 1 轮 = 1 条 user 消息 + AI 对它的全部回答（assistant / tool 往返），到下一条 user 前。
 * 纯函数（只依赖入参），单测钉住边界：开头无 user、连续多条 user、空列表。
 */
data class ChatRound(
    /** 本轮第一条（user）消息 id */
    val firstId: Long,
    /** 本轮最后一条消息 id */
    val lastId: Long,
    /** 在入参列表里的下标区间（含两端），取原文/算 token 用 */
    val indexRange: IntRange,
    /** 首条 user 消息的第一行截断（列表预览用） */
    val preview: String
)

fun chatRounds(msgs: List<ChatMessage>): List<ChatRound> {
    if (msgs.isEmpty()) return emptyList()
    val rounds = ArrayList<ChatRound>()
    var start = 0
    for (i in 1..msgs.size) {
        val isBoundary = i == msgs.size || msgs[i].role == "user"
        if (isBoundary) {
            val first = msgs[start]
            rounds += ChatRound(
                firstId = first.id,
                lastId = msgs[i - 1].id,
                indexRange = start..(i - 1),
                preview = first.content.lineSequence().firstOrNull()?.take(60) ?: ""
            )
            start = i
        }
    }
    return rounds
}

/**
 * v47：手动区间压缩覆盖的**消息 id 集合**（区间 [first..last] 全闭）。
 * 组装过滤 / 自动摘要排除 / 会话渲染三处共用同一口径，改这里改一处。
 */
fun compactedIdSet(markers: List<io.github.m0use001.planability.data.CompactMarkerEntity>): Set<Long> = buildSet {
    for (m in markers) {
        if (m.firstCompactedMessageId > 0 && m.lastCompactedMessageId >= m.firstCompactedMessageId) {
            for (id in m.firstCompactedMessageId..m.lastCompactedMessageId) add(id)
        }
    }
}
