package io.github.m0use001.planability.ui.info

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.HelpOutline
import androidx.compose.material.icons.rounded.Assignment
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Event
import androidx.compose.material.icons.rounded.FactCheck
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import io.github.m0use001.planability.core.toLocalDate
import io.github.m0use001.planability.ui.common.Motion
import io.github.m0use001.planability.ui.notes.MdInlineText
import io.github.m0use001.planability.core.LocalHaptics
import io.github.m0use001.planability.core.HapticEngine
import androidx.compose.animation.animateContentSize
import org.json.JSONArray
import io.github.m0use001.planability.ai.JsonRepair
import io.github.m0use001.planability.data.ChatMessage
import org.json.JSONObject
import io.github.m0use001.planability.ui.common.motionEnter

// ==================== 对话内嵌组件（AI 工作台 v4/v6） ====================
// 协议：助手在回答中输出 ```plan / ```qa / ```schedule / ```checklist / ```file 围栏 + 合法 JSON，
// 解析为原生交互卡片；普通文本段仍走 MarkdownView。流式期间未闭合的围栏按普通代码块展示。
// v6 留痕：卡片交互结果以标记行（[qa-result]/[sched-sent]/[check-done]）写入消息尾部，
// 解析时剥离标记行不渲染，卡片自身读取标记还原"已发生什么"。

/**
 * 宽松地把一段文本解析成 JSON 对象。
 *
 * ## 为什么需要它
 * 卡片的围栏正文直接来自模型，**经常不是严格合法的 JSON** —— 实测见过
 * ① 多余的尾括号 ② 结尾缺一个 `}` ③ 字符串值内未转义引号（`"要"好看""`）
 * ④ 全角定界符。严格 `JSONObject(text)` 一失败，`[ChatComp.parse]` 就按设计
 * 把整块回落成**代码块**，用户看到的是一坨 JSON 而不是能点的卡片 ——
 * 模型明明按提示输出了，界面却装作没收到。
 *
 * 2026-11-05 根治：历史上一路「遇一种坏法加一档降级」（剥尾括号、引号转义……）
 * 追不上模型的新坏法，改为统一走 [io.github.m0use001.planability.ai.JsonRepair]——
 * 一台扫描式修复器覆盖未转义引号/全角定界符/尾逗号/括号不配平/截断缺闭合等
 * 主要坏法；保留「截取第一个 `{` 到最后一个 `}`」作为围栏前后混入说明文字时
 * 的前置步骤。严格解析永远先走，正常 JSON 零风险。
 */
fun looseJsonObject(text: String): JSONObject? {
    val t = text.trim()
    val a = t.indexOf('{')
    val b = t.lastIndexOf('}')
    if (a > 0 || b in 1 until t.length - 1) {
        if (a >= 0 && b > a) JsonRepair.parseObject(t.substring(a, b + 1))?.let { return it }
    }
    return JsonRepair.parseObject(t)
}

/**
 * 组件标记归一化（2026-11-04 用户「AI 调用会话组件失败了」）。
 *
 * 协议要求的写法是 ` ```qa {"questions":[…]}` ``` `围栏，但 deepseek-flash 实测会
 * 输出 **DSML**（它内部的工具调用标记格式）：
 * ```
 * <｜DSML｜ qa>
 * {"questions":[…]}
 * </｜DSML｜>
 * ```
 * 解析器只认围栏 → 整块回落成普通文本，用户看到一串生标记（真机◆ 末尾）。
 * 这里在解析前把 DSML 标记**翻译成围栏**，让两种写法走同一条解析路径；
 * 顺带剥掉孤立的 DSML 残渣行（例如只剩 `<｜DSML｜ calls>` 那种半截工具调用）。
 *
 * 纯字符串处理（不碰 JSON），单测直接覆盖。
 */
internal fun normalizeComponentMarkup(content: String): String {
    if (!content.contains("DSML", ignoreCase = true)) return content
    var s = content
    // 开标记 <｜DSML｜ qa> → ```qa（只翻译我们认识的组件名；其余开标记直接丢）
    val open = Regex("<[｜|]\\s*DSML\\s*[｜|]\\s*([A-Za-z]+)\\s*>", RegexOption.IGNORE_CASE)
    s = open.replace(s) { m ->
        val name = m.groupValues[1].lowercase()
        if (name in ChatComp.TYPES) "```$name" else ""
    }
    // 闭标记 </｜DSML｜> / </｜DSML｜ qa> → ```
    val close = Regex("</[｜|]\\s*DSML\\s*[｜|]\\s*[A-Za-z_]*\\s*>", RegexOption.IGNORE_CASE)
    s = close.replace(s) { "```" }
    // 残留的孤立 DSML 行整行剥掉（模型有时只吐 `<｜DSML｜ calls>` 这种半截）
    s = s.lines()
        .filterNot { line ->
            val t = line.trim()
            t.startsWith("<｜DSML｜") || t.startsWith("<|DSML|") ||
                t.startsWith("</｜DSML｜") || t.startsWith("</|DSML|")
        }
        .joinToString("\n")
    return s
}

sealed class ChatSegment {    /** 常规 markdown 文本段 */
    data class Md(val text: String) : ChatSegment()

    /** 内嵌组件卡（type ∈ ChatComp.TYPES） */
    data class Card(val type: String, val json: JSONObject) : ChatSegment()
}

object ChatComp {
    val TYPES = setOf("plan", "qa", "schedule", "checklist", "file")

    /**
     * 自定义选项的哨兵值。
     *
     * 为什么用哨兵字符串而不是多存一个"哪几题选了自定义"的集合：
     * 留痕串 `0=1;1=2` 是**选项下标**，用 `-1` 表示自定义会和"未选择"撞车
     * （未选择也是 -1，见 [qaPicksFromMarker]）。所以自定义项在 options 列表里
     * **永远追加在最后一位**，下标 = options.size，用这个常量当标签。
     */
    // 标签里**不放 emoji**：chip 的 leadingIcon 已经画了一支铅笔，
    // 两处都放就会并排出现两个铅笔（真机一眼看出来的）。
    const val CUSTOM_LABEL = "自己填"
    const val CUSTOM_INDEX = -2
    // qa-custom 存"自己填"的文本：[qa-result] 里只有选项下标，自定义项的下标是 -2，
    // 光看它还原不出用户到底写了什么，答完再回看会显示成空的 "→ "。
    val MARKERS = listOf("[qa-result]", "[qa-custom]", "[sched-sent]", "[check-done]")

    /** 提取消息尾部留痕标记行 → token→值 */
    fun metaOf(content: String): Map<String, String> {
        val out = mutableMapOf<String, String>()
        content.lines().forEach { line ->
            val t = line.trim()
            MARKERS.forEach { m ->
                if (t.startsWith(m)) out[m.trim('[', ']')] = t.removePrefix(m).trim()
            }
        }
        return out
    }

    /** 把 assistant 正文拆成 文本段 / 组件卡 段（未闭合或非法 JSON 的围栏回落为文本段；留痕标记行剥离） */
    fun parse(content: String): List<ChatSegment> {
        val out = mutableListOf<ChatSegment>()
        val buf = StringBuilder()
        val lines = normalizeComponentMarkup(content).lines()
        var i = 0
        fun flush() {
            if (buf.isNotBlank()) out += ChatSegment.Md(buf.toString().trimEnd('\n'))
            buf.clear()
        }
        while (i < lines.size) {
            val trimmed = lines[i].trim()
            if (MARKERS.any { trimmed.startsWith(it) }) { i++; continue } // 留痕行不渲染
            val after = trimmed.removePrefix("```").trim()
            val fenceName = after.substringBefore(' ').trim()
            if (trimmed.startsWith("```") && fenceName in TYPES) {
                // 内联 JSON：```qa {...}```（模型常把 JSON 写在围栏同一行）
                var inline = after.substringAfter(' ', "").trim()
                var closedInline = false
                if (inline.endsWith("```")) {
                    inline = inline.removeSuffix("```").trim(); closedInline = true
                }
                if (closedInline) {
                    val j = looseJsonObject(inline)
                    if (j != null) {
                        flush()
                        out += ChatSegment.Card(fenceName, j)
                        i++
                        continue
                    }
                }
                // 单行闭合写法（```lang {...}```）且 JSON 解析失败 —— 说明模型就写了这一个块，
                // 后面每一行都是**正文**。原逻辑会让 while 一路读到文末，把整段正文塞进 body，
                // 于是它跟着坏 JSON 一起被渲染成等宽代码框（真机踩过：正文里的 **粗体**
                // 星号全裸露）。这里直接放弃这个围栏、只保留它自己那一行。
                if (closedInline) {
                    flush()
                    buf.append(trimmed).append('\n')
                    continue
                }
                // 未闭合的单行写法（```lang 部分JSON…）仍需把行内残余算进围栏正文。
                val body = StringBuilder(inline)
                if (inline.isNotEmpty()) body.append('\n')
                i++
                var closed = false
                var jsonOk = false
                while (i < lines.size) {
                    val lt = lines[i].trim()
                    if (lt.startsWith("```")) { closed = true; i++; break }
                    if (MARKERS.any { lt.startsWith(it) }) { i++; continue } // 围栏内的留痕行防御性跳过
                    body.append(lines[i]).append('\n'); i++
                    // 未闭合围栏的止损（2026-10-12 真机）：模型经常漏写收尾的 ```，
                    // 旧逻辑会把**后面所有正文**都吞进 body，于是同一段里的正文被
                    // MarkdownView 当成一个未闭合围栏 → 整段渲染成等宽代码，
                    // 连正文里的 **粗体** 星号都原样露在屏幕上（实测一条 qa 卡把
                    // 「**当前状态**：明天日程为空…**先睡觉…**」全变成了等宽灰框）。
                    //
                    // 止损条件：JSON 型组件（TYPES 全部是「首行语言槽 + 其后一段 JSON」）
                    // 一旦当前 body 已经能解析成合法 JSON，后面的行就不再属于这个组件 ——
                    // 直接 break，剩下的行留给正常 markdown 渲染。
                    // 只在候选以 } 结尾时才试解析，避免每行都解析一遍（O(n^2)）。
                    // 注意要 trimEnd：body 每行都 append('\n')，直接看 body.last() 永远是换行符。
                    val cand = body.toString().trimEnd()
                    if (cand.isNotEmpty() && cand.endsWith("}")) {
                        if (looseJsonObject(cand) != null) { jsonOk = true; break }
                    }
                }
                if (closed || jsonOk) {
                    val json = looseJsonObject(body.toString())
                    if (json != null) {
                        // jsonOk 止损 break 时 i 停在 JSON 收尾行的**下一行**——若模型其实写了
                        // 闭合围栏（或 DSML 闭标记归一出来的 ```），这里要一并吃掉；
                        // 否则它单独落成 Md 段，被渲染成 qa 卡末尾悬着一个空代码块
                        // （2026-11-08 真机◆ 英语课，用户报告）。
                        if (!closed && i < lines.size && lines[i].trim().startsWith("```")) i++
                        flush()
                        out += ChatSegment.Card(fenceName, json)
                        continue
                    }
                }
                // 未闭合/非法：围栏原样回落为文本（保留 buf 中已有的前置文本）
                buf.append(trimmed).append('\n').append(body)
                if (closed) buf.append("```\n")
                continue
            }
            buf.append(lines[i]).append('\n')
            i++
        }
        flush()
        return out
    }
}

// ---------- 卡片动作文本与留痕（确定性构造，用于状态还原与回传消息） ----------

/**
 * 读每题的选项。
 *
 * 模型常把 options 写成**逗号分隔的字符串**而不是 JSON 数组（实测 mimo-v2.6 就会），
 * 这种情况下 optJSONArray 返回 null，整张问答卡的选项会全空 —— 卡片看着像坏了。
 * 所以字符串也收，按中英文逗号切。
 */
fun qaOptions(json: JSONObject): List<List<String>> {
    val qs = json.optJSONArray("questions") ?: return emptyList()
    return (0 until qs.length()).map { i ->
        val o = qs.optJSONObject(i)
        o?.optJSONArray("options")?.let { arr ->
            (0 until arr.length()).map { arr.optString(it) }
        } ?: o?.optString("options")
            ?.split(",", "，")
            ?.map { it.trim().trim('"', '\'') }
            ?.filter { it.isNotBlank() }
            ?: emptyList()
    }
}

fun qaQuestions(json: JSONObject): List<String> {
    val qs = json.optJSONArray("questions") ?: return emptyList()
    return (0 until qs.length()).map { i -> qs.optJSONObject(i)?.optString("q") ?: "" }
}

/** 某题最终显示的答案文本：自定义项用用户输入，其余用选项原文 */
private fun qaAnsweredText(opts: List<String>?, pick: Int, custom: String?): String =
    if (pick == ChatComp.CUSTOM_INDEX) custom.orEmpty()
    else opts?.getOrNull(pick) ?: ""

/** 从留痕串 "0=1;1=2" 还原选择 */
private fun qaPicksFromMarker(marker: String?, size: Int): List<Int>? {
    if (marker.isNullOrBlank()) return null
    val map = marker.split(";").mapNotNull {
        val kv = it.split("=")
        if (kv.size == 2) kv[0].trim().toIntOrNull() to kv[1].trim().toIntOrNull() else null
    }.toMap()
    if (map.size < size) return null
    return (0 until size).map { map[it] ?: -1 }
}

/**
 * 把选择拼成回传文本。[custom] 是每题的自定义输入，键为题号。
 *
 * 自定义题的答案**优先用用户输入**；输入为空时退回选项文本，
 * 免得把"✏️ 自己填"这个占位词当成用户真的选了它。
 */
fun qaAnswerText(json: JSONObject, picks: List<Int>, custom: Map<Int, String> = emptyMap()): String {
    val qs = qaQuestions(json)
    val opts = qaOptions(json)
    return qs.mapIndexed { qi, q ->
        val p = picks.getOrElse(qi) { ChatComp.CUSTOM_INDEX }
        val raw = opts.getOrNull(qi)?.getOrNull(p) ?: ""
        val a = if (p == ChatComp.CUSTOM_INDEX) {
            custom[qi]?.trim()?.takeIf { it.isNotEmpty() } ?: raw
        } else raw
        "${qi + 1}. $q → $a"
    }.joinToString("\n")
}

fun qaSent(json: JSONObject, userTexts: List<String>): Boolean {
    val q0 = qaQuestions(json).firstOrNull()?.take(12) ?: return false
    return q0.isNotBlank() && userTexts.any { it.contains(q0) }
}

fun scheduleItems(json: JSONObject): List<Triple<String, String, String>> {
    val arr = json.optJSONArray("items") ?: return emptyList()
    return (0 until arr.length()).map { i ->
        val o = arr.optJSONObject(i) ?: JSONObject()
        Triple(
            o.optString("title"),
            o.optString("start") + (o.optString("end").takeIf { it.isNotBlank() }?.let { "-$it" } ?: ""),
            o.optString("location")
        )
    }
}

/** 从留痕串 "title|time|loc;;…" 还原终稿条目 */
private fun scheduleFromMarker(marker: String?): List<Triple<String, String, String>>? {
    if (marker.isNullOrBlank()) return null
    return marker.split(";;").mapNotNull { seg ->
        val p = seg.split("|")
        if (p.isNotEmpty() && p[0].isNotBlank()) Triple(p[0], p.getOrElse(1) { "" }, p.getOrElse(2) { "" }) else null
    }.takeIf { it.isNotEmpty() }
}

fun scheduleRequestText(items: List<Triple<String, String, String>>): String =
    "请用 schedule_create 帮我创建以下日程（逐条创建，时间照原样解析）：\n" +
        items.mapIndexed { i, (t, time, loc) ->
            "${i + 1}. $t（$time）" + if (loc.isNotBlank()) " @ $loc" else ""
        }.joinToString("\n")

fun scheduleSent(json: JSONObject, userTexts: List<String>): Boolean {
    val t = scheduleItems(json).firstOrNull()?.first?.take(10) ?: return false
    return t.isNotBlank() && userTexts.any { it.contains(t) }
}

/** 解析条目时间串为 [start,end) 窗口（兼容"明天 21:00-22:00"/"9月30日 20:00-21:00"等）；解析失败返回 null */
fun parseScheduleWindow(time: String): Pair<Long, Long>? {
    val t = time.replace("月", "/").replace("日", " ").trim()
    val range = Regex("(\\d{1,2}:\\d{2})\\s*-\\s*(\\d{1,2}:\\d{2})").find(t)
    val start: Long
    val end: Long
    if (range != null) {
        val prefix = t.substring(0, range.range.first)
        start = io.github.m0use001.planability.ai.ptl.ToolExecImpls.parseDateTime(prefix + range.groupValues[1]) ?: return null
        end = io.github.m0use001.planability.ai.ptl.ToolExecImpls.parseDateTime(prefix + range.groupValues[2]) ?: (start + 3600_000L)
    } else {
        start = io.github.m0use001.planability.ai.ptl.ToolExecImpls.parseDateTime(t) ?: return null
        end = start + 3600_000L
    }
    return if (end > start) start to end else start to start + 3600_000L
}

/** 与本地日程求重叠（异步：查询 occurrences）；返回冲突的日程名 */
suspend fun scheduleConflicts(time: String): List<String> {
    val w = parseScheduleWindow(time) ?: return emptyList()
    val day = w.first.toLocalDate()
    val occ = runCatching { io.github.m0use001.planability.data.Graph.plan.scheduleOccurrences(day, day) }.getOrNull() ?: return emptyList()
    return occ.filter { it.start < w.second && it.end > w.first }
        .map { it.schedule.title }
        .distinct()
        .take(2)
}

fun checklistItems(json: JSONObject): List<String> {
    val arr = json.optJSONArray("items") ?: return emptyList()
    return (0 until arr.length()).map { arr.optString(it) }.filter { it.isNotBlank() }
}

private fun checkDoneFromMarker(marker: String?, size: Int): Set<Int>? {
    if (marker.isNullOrBlank()) return null
    return marker.split(",").mapNotNull { it.trim().toIntOrNull() }.filter { it in 0 until size }.toSet()
}

fun checklistRequestText(json: JSONObject, checked: List<Int>): String =
    "请把以下条目逐条创建为待办：\n" +
        checked.mapIndexed { j, idx -> "${j + 1}. ${checklistItems(json)[idx]}" }.joinToString("\n")

fun checklistSent(json: JSONObject, userTexts: List<String>): Boolean {
    val it0 = checklistItems(json).firstOrNull()?.take(10) ?: return false
    return it0.isNotBlank() && userTexts.any { it.contains(it0) }
}

// ---------- 交互卡失效判定 ----------

/**
 * 哪些消息里的交互卡应该作废。
 *
 * ## 要解决的真实问题
 * 问答/日程/清单卡弹出后，用户可能**不选它、直接发一条新消息**。AI 于是开始答新问题，
 * 而上一轮那张卡还留在上文、选项还能点 —— 用户会以为点了仍然生效，点了却被当成对
 * 早已过去那一轮的回答，AI 拿着一个过期答案往下走。
 *
 * ## 判定规则（一条消息作废，当且仅当它同时满足）
 * 1. 它是 assistant 消息，且含有**还没作答**的交互卡
 *    —— 已经作答过的卡（留痕标记 / 文本里已回传）不是"失效"，那是**历史记录**，
 *    得留着给人回看，不能因为后来又聊了几轮就灰掉。
 * 2. 它后面**还有用户消息**。也就是用户已经用新输入把这轮对话带走了。
 *
 * ## 为什么不看"AI 是否已经在思考"
 * 用户要求的原话是"发了新消息且 AI 已经开始思考"。但把 `status == "streaming"`
 * 也纳入条件会带来一个坏处：用户发新消息后、AI 还没开始流式的那一两秒里，卡片
 * 短暂还是可点的 —— 用户在这个缝隙里点下去，答案会被当成新问题的回答，比一直
 * 灰着更糟。既然"发了新消息"本身就已经代表这轮被跳过了，直接用用户消息做条件
 * 既更早生效（没有闪烁的可点窗口），判定也更简单可测。
 *
 * @return 需要作废的 assistant 消息 id 集合
 */
fun staleCardIds(messages: List<ChatMessage>): Set<Long> {
    val lastUserIdx = messages.indexOfLast { it.role == "user" }
    if (lastUserIdx <= 0) return emptySet()
    return messages.subList(0, lastUserIdx)
        .filter { it.role == "assistant" && it.content.isNotBlank() }
        .filter { msg -> !msg.alreadyAnswered() }
        .filter { msg ->
            ChatComp.parse(msg.content).any { seg ->
                seg is ChatSegment.Card && seg.type in INTERACTIVE_CARDS
            }
        }
        .map { it.id }
        .toSet()
}

/**
 * 这条消息里的交互卡是否**已经作答过**（留痕标记行已写入）。
 *
 * 判据是「**存在**任意一行以某个标记开头」。这里曾经写成
 * `MARKERS.none { marker -> lines.none { line -> line.startsWith(marker) } }` ——
 * 两层 none 套在一起，语义成了「对每个标记都没有标记行」，在有标记行时同样会
 * 因为内外 none 的组合而给出错误结果，导致**没作答过的卡被判成已作答**、
 * staleIds 恒为空集（真机踩了整整三轮才对上：卡片死活不变灰）。
 * 一层 any 就是唯一正确的写法。
 */
private fun ChatMessage.alreadyAnswered(): Boolean =
    ChatComp.MARKERS.any { marker ->
        content.lineSequence().any { line -> line.trim().startsWith(marker) }
    }

/** 需要"作答才生效"的卡片类型 —— 只有这些会被 stale 判定作废 */
private val INTERACTIVE_CARDS = setOf("qa", "schedule", "checklist")

// ---------- 卡片公共骨架 ----------

/**
 * 卡片失效时统一显示的一行说明。
 *
 * 为什么需要这个：交互卡（问答/日程/清单）弹出后，用户可能不选它、直接发新消息，
 * AI 就开始答新问题了。这时候卡片**还留在上文**，选项还能点 —— 用户会以为点了还能生效，
 * 点了却被当成对已过去那一轮的回答。所以要显式作废并说清为什么。
 */
@Composable
private fun StaleHint() {
    Row(
        Modifier.fillMaxWidth().padding(bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Rounded.Info, null, Modifier.size(13.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.width(5.dp))
        Text(
            "本轮已跳过：你在回答前发了新消息，这张卡不再生效",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun CompCard(
    icon: ImageVector,
    label: String,
    status: String?,
    statusTint: androidx.compose.ui.graphics.Color? = null,
    stale: Boolean = false,
    onCopyAll: (() -> Unit)?,
    content: @Composable () -> Unit
) {
    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            // 失效卡片整体压暗，和还能点的卡片一眼区分开
            containerColor = if (stale) MaterialTheme.colorScheme.surfaceContainerLowest
            else MaterialTheme.colorScheme.surfaceContainerLow
        ),
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    icon, null, Modifier.size(15.dp),
                    tint = if (stale) MaterialTheme.colorScheme.onSurfaceVariant
                    else MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    label,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (stale) MaterialTheme.colorScheme.onSurfaceVariant
                    else MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.weight(1f))
                if (status != null) {
                    Text(
                        status,
                        style = MaterialTheme.typography.labelSmall,
                        color = statusTint ?: MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (onCopyAll != null) {
                    IconButton(onClick = onCopyAll, modifier = Modifier.size(26.dp)) {
                        Icon(
                            Icons.Rounded.ContentCopy, "复制",
                            Modifier.size(13.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            content()
        }
    }
}

// ---------- 计划卡（目标 + 步骤 + 查看完整计划） ----------

@Composable
fun PlanCard(json: JSONObject) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var expanded by remember { mutableStateOf(false) }
    // 展开/收起 = 中性轻触（Tap）：见 core/Haptics.kt 的 HIG 映射表
    val haptics = LocalHaptics.current
    val title = json.optString("title")
    val goal = json.optString("goal")
    // 2026-11-05 真机 1290：模型常把序号写进步骤名（"name":"1 锁定范围与验收标准"），
    // 卡片自己又渲染 1. 2. 3. → 两组序号。渲染前剥掉行首序号（含「1.」「2、」「3）」变体）。
    // 2026-11-19 用户实报：「自动编号把模型输出吃了」——步骤名「3.8 反导数：定义与公式表」
    // 里的「3.」被当序号剥掉，屏幕成了「1. 8 反导数…」。口径改由
    // [planStepLabel] 统一裁决：列表序号照剥；小数/章节号（3.8）原样保留且**不再加自动序号**。
    val steps = json.optJSONArray("steps")?.let { arr ->
        (0 until arr.length()).mapNotNull { i ->
            arr.optJSONObject(i)?.let {
                io.github.m0use001.planability.ui.common.planStepLabel(it.optString("name")) to it.optString("detail")
            }
        }
    } ?: emptyList()
    val note = json.optString("note")

    CompCard(
        icon = Icons.Rounded.Assignment,
        label = "计划" + if (title.isNotBlank()) " · $title" else "",
        status = "${steps.size} 步",
        onCopyAll = {
            val text = "计划：$title\n目标：$goal\n" + steps.mapIndexed { i, (label, d) ->
                (if (label.showIndex) "${i + 1}. " else "") + label.text +
                    (if (d.isNotBlank()) " — $d" else "")
            }.joinToString("\n") + if (note.isNotBlank()) "\n备注：$note" else ""
            // 2026-11-12：写后读回校验（焦点策略会静默拒绝，见 core/ClipboardCompat）
            val err = io.github.m0use001.planability.core.ClipboardCompat.copy(ctx, text)
            android.widget.Toast.makeText(
                ctx, err ?: "已复制", android.widget.Toast.LENGTH_SHORT
            ).show()
        }
    ) {
        // 步骤多寡切换 = 卡片高度变化：用 animateContentSize（spring）而不是定长 tween——
        // 高度的终点取决于步骤数，tween 会在步骤多时提前断掉、步骤少时拖尾；
        // spring 按「还差多少」推进，大小两种情况都在各自合适的节奏里收住（临界阻尼，不回弹）。
        Column(Modifier.fillMaxWidth().animateContentSize(Motion.contentSize)) {
            if (goal.isNotBlank()) {
                Text(
                    "目标",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                MdInlineText(goal, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(6.dp))
            }
            steps.take(if (expanded) steps.size else 3).forEachIndexed { i, (label, d) ->
                Row(Modifier.padding(vertical = 2.dp)) {
                    // 2026-11-19：模型自带章节号（"3.8 反导数"）时**不再套自动序号**，
                    // 否则「1. 3.8 反导数」两套编号打架（模型输出被吃掉就是这么来的）。
                    if (label.showIndex) {
                        Text(
                            "${i + 1}. ",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Column {
                        MdInlineText(label.text, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
                        if (d.isNotBlank() && expanded) {
                            MdInlineText(
                                d,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
            if (steps.size > 3 || note.isNotBlank()) {
                androidx.compose.material3.TextButton(
                    // 展开/收起计划 = 中性轻触（Tap），与聊天里其它展开同一档
                    onClick = { haptics.perform(HapticEngine.Kind.Tap); expanded = !expanded },
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp, vertical = 0.dp)
                ) {
                    Text(
                        if (expanded) "收起计划" else "查看完整计划 →",
                        style = MaterialTheme.typography.labelMedium
                    )
                }
            }
            if (expanded && note.isNotBlank()) {
                MdInlineText(
                    "备注：$note",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

// ---------- 问答卡（逐题选项 → 一键发送回答；留痕：已答显示完整问答记录） ----------

@Composable
fun QaCard(
    json: JSONObject,
    msgContent: String,
    onSend: (String) -> Unit,
    onTrace: (String) -> Unit,
    stale: Boolean = false
) {
    val questions = qaQuestions(json)
    val options = qaOptions(json)
    if (questions.isEmpty()) return
    val picks = remember(msgContent) { qaPicksFromMarker(ChatComp.metaOf(msgContent)["qa-result"], questions.size) }
    // 自定义答案的留痕："题号=文本;题号=文本"
    val customMap = remember(msgContent) {
        ChatComp.metaOf(msgContent)["qa-custom"]
            ?.split(";")?.mapNotNull {
                val kv = it.split("=", limit = 2)
                if (kv.size == 2) kv[0].trim().toIntOrNull() to kv[1] else null
            }?.toMap().orEmpty()
    }
    val already = picks != null
    // 失效 = 没作答过，且用户已经发新消息把话题带走了（见 ChatScreen 的 stale 判定）
    val dead = stale && !already
    val sel = remember(msgContent) { mutableStateListOf(*IntArray(questions.size) { -1 }.toList().toTypedArray()) }
    // 每题的自定义输入，键为题号
    val custom = remember(msgContent) { mutableStateMapOf<Int, String>() }
    // 正在填哪一题的自定义输入
    var customFor by remember(msgContent) { mutableStateOf(-1) }

    CompCard(
        icon = Icons.AutoMirrored.Rounded.HelpOutline,
        label = "问答",
        status = when {
            already -> "已回复"
            dead -> "已失效"
            else -> "待回答"
        },
        stale = dead,
        onCopyAll = null
    ) {
        if (dead) StaleHint()
        if (already) {
            // 留痕：完整问答记录
            questions.forEachIndexed { qi, q ->
                MdInlineText(q, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
                MdInlineText(
                    "→ " + qaAnsweredText(options.getOrNull(qi), picks!![qi], customMap[qi]),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 10.dp, top = 1.dp, bottom = 5.dp)
                )
            }
            Text(
                "回答已发送",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            questions.forEachIndexed { qi, q ->
                MdInlineText(q, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(4.dp))
                // v19.11：选项改为 FlowRow——原来一行挤两个，长选项被压成竖排单字（不可读）
                @OptIn(ExperimentalLayoutApi::class)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    options.getOrNull(qi)?.forEachIndexed { oi, opt ->
                        AssistChip(
                            // 失效卡片整体不可交互：不然用户还能点开"自己填"的输入框，
                            // 填完发现没有"发送回答"可按，纯粹白填
                            onClick = { if (!dead) sel[qi] = if (sel[qi] == oi) -1 else oi },
                            label = { MdInlineText(opt, style = MaterialTheme.typography.labelSmall) },
                            colors = AssistChipDefaults.assistChipColors(
                                containerColor = if (sel[qi] == oi) MaterialTheme.colorScheme.primaryContainer
                                else MaterialTheme.colorScheme.surfaceContainer
                            )
                        )
                    }
                    // 自定义选项：模型给的选择永远覆盖不全用户真正想说的话，
                    // 没有这一项时用户只能去输入框另发一条 —— 那张卡片就白pop出来了。
                    AssistChip(
                        enabled = !dead,
                        onClick = {
                            sel[qi] = ChatComp.CUSTOM_INDEX
                            customFor = qi
                        },
                        label = { Text(ChatComp.CUSTOM_LABEL, style = MaterialTheme.typography.labelSmall) },
                        leadingIcon = {
                            Icon(Icons.Rounded.Edit, null, Modifier.size(13.dp))
                        },
                        colors = AssistChipDefaults.assistChipColors(
                            containerColor = if (sel[qi] == ChatComp.CUSTOM_INDEX) MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.surfaceContainer
                        )
                    )
                }
                if (sel[qi] == ChatComp.CUSTOM_INDEX && !dead) {
                    OutlinedTextField(
                        value = custom[qi] ?: "",
                        onValueChange = { custom[qi] = it },
                        placeholder = { Text("写下你的想法", style = MaterialTheme.typography.labelSmall) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp)
                    )
                }
                Spacer(Modifier.height(6.dp))
            }
            // 每题都要有选择，且选了自定义的那题必须真的写了字
            // 注意 `p >= 0` 会把自定义选项（-2）也判成"没选"，
            // 于是选了"自己填"并写了字，按钮依然灰着 —— 真机踩过。
            // 所以显式放行 CUSTOM_INDEX 这一支。
            val allPicked = questions.indices.all { qi ->
                val p = sel[qi]
                (p >= 0 || p == ChatComp.CUSTOM_INDEX) &&
                    (p != ChatComp.CUSTOM_INDEX || custom[qi]?.isNotBlank() == true)
            }
            Button(
                onClick = {
                    onTrace("[qa-result] " + questions.indices.joinToString(";") { "$it=${sel[it]}" })
                    if (custom.isNotEmpty()) {
                        onTrace("[qa-custom] " + custom.entries.joinToString(";") { "${it.key}=${it.value}" })
                    }
                    onSend(qaAnswerText(json, questions.indices.map { sel[it] }, custom))
                },
                enabled = allPicked,
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp, vertical = 4.dp)
            ) {
                Text("发送回答", style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

// ---------- 日程建议卡（本地冲突检测 + 时间修改 + 留痕终稿） ----------

@Composable
fun ScheduleCard(
    json: JSONObject,
    msgContent: String,
    userTexts: List<String>,
    onSend: (String) -> Unit,
    onTrace: (String) -> Unit,
    stale: Boolean = false
) {
    val sentMarker = remember(msgContent) { scheduleFromMarker(ChatComp.metaOf(msgContent)["sched-sent"]) }
    val initial = sentMarker ?: scheduleItems(json)
    if (initial.isEmpty()) return
    val already = sentMarker != null || scheduleSent(json, userTexts)
    val dead = stale && !already
    // 可编辑条目（「修改」按钮调整时间后替换）
    val items = remember(msgContent) { mutableStateListOf(*initial.toTypedArray()) }
    // 本地冲突检测：解析每条时间窗与既有日程求重叠
    val conflicts = remember { mutableStateOf<List<List<String>>>(items.map { emptyList() }) }
    val conflictKey = items.joinToString("|") { it.second }
    LaunchedEffect(conflictKey) {
        conflicts.value = items.map { scheduleConflicts(it.second) }
    }
    // 修改时间对话框
    var editIdx by remember { mutableStateOf(-1) }
    var editText by remember { mutableStateOf("") }

    CompCard(
        icon = Icons.Rounded.Event,
        label = "日程建议",
        status = when {
            already -> "已发送"
            dead -> "已失效"
            conflicts.value.any { it.isNotEmpty() } -> "⚠ 时间冲突"
            else -> "${items.size} 条"
        },
        statusTint = if (already || dead) null else if (conflicts.value.any { it.isNotEmpty() }) MaterialTheme.colorScheme.error else null,
        stale = dead,
        onCopyAll = null
    ) {
        if (dead) StaleHint()
        items.forEachIndexed { idx, (t, time, loc) ->
            Row(Modifier.padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.foundation.layout.Box(
                    Modifier
                        .size(6.dp)
                        // RoundedCornerShape(Int) 的重载本来就是「百分比」；写明 percent 只为消除误读
                        .clip(RoundedCornerShape(percent = 50))
                        .background(
                            if (conflicts.value.getOrNull(idx)?.isNotEmpty() == true) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.primary
                        )
                )
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    MdInlineText(t, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
                    MdInlineText(
                        time + if (loc.isNotBlank()) " · $loc" else "",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    conflicts.value.getOrNull(idx)?.takeIf { it.isNotEmpty() && !already }?.let { names ->
                        Text(
                            "⚠ 与「" + names.joinToString("」「") + "」时间冲突",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
                if (!already && !dead) {
                    TextButton(
                        onClick = { editIdx = idx; editText = time },
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp, vertical = 0.dp)
                    ) {
                        Text("修改", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        if (!already && !dead) {
            Button(
                onClick = {
                    onTrace("[sched-sent] " + items.joinToString(";;") { "${it.first}|${it.second}|${it.third}" })
                    onSend(scheduleRequestText(items.toList()))
                },
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp, vertical = 4.dp)
            ) {
                Text("发送创建请求", style = MaterialTheme.typography.labelMedium)
            }
        } else if (already) {
            Text(
                "创建请求已发送（以上为最终时间）",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    if (editIdx >= 0 && editIdx < items.size) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { editIdx = -1 },
            modifier = Modifier.motionEnter(),
            title = { Text("修改时间", style = MaterialTheme.typography.titleSmall) },
            text = {
                Column {
                    Text(
                        items[editIdx].first,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    androidx.compose.material3.OutlinedTextField(
                        value = editText,
                        onValueChange = { editText = it },
                        singleLine = true,
                        placeholder = { Text("如：明天 21:00-22:00") }
                    )
                    conflicts.value.getOrNull(editIdx)?.takeIf { it.isNotEmpty() }?.let { names ->
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "当前与「" + names.joinToString("」「") + "」冲突",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val v = editText.trim()
                    if (v.isNotEmpty()) items[editIdx] = items[editIdx].copy(second = v)
                    editIdx = -1
                }) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { editIdx = -1 }) { Text("取消") }
            }
        )
    }
}

// ---------- 清单卡（可勾选 → 一键转待办；留痕：已勾选条目划线保留） ----------

@Composable
fun ChecklistCard(
    json: JSONObject,
    msgContent: String,
    userTexts: List<String>,
    onSend: (String) -> Unit,
    onTrace: (String) -> Unit,
    stale: Boolean = false
) {
    val items = checklistItems(json)
    if (items.isEmpty()) return
    val done = remember(msgContent) { checkDoneFromMarker(ChatComp.metaOf(msgContent)["check-done"], items.size) }
    val already = done != null || checklistSent(json, userTexts)
    val dead = stale && !already
    val checked = remember(msgContent) { mutableStateListOf(*BooleanArray(items.size) { false }.toList().toTypedArray()) }

    CompCard(
        icon = Icons.Rounded.FactCheck,
        label = "清单" + (json.optString("title").takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""),
        status = if (already) "已发送" else if (dead) "已失效" else null,
        stale = dead,
        onCopyAll = null
    ) {
        if (dead) StaleHint()
        items.forEachIndexed { i, item ->
            val isDone = done?.contains(i) == true
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = isDone || checked[i],
                    onCheckedChange = { if (!already && !dead) checked[i] = it },
                    enabled = !already && !dead
                )
                MdInlineText(
                    item,
                    style = MaterialTheme.typography.bodySmall.copy(
                        textDecoration = if (isDone) TextDecoration.LineThrough else null
                    )
                )
            }
        }
        Spacer(Modifier.height(2.dp))
        if (!already && !dead) {
            val anyChecked = checked.any { it }
            Button(
                onClick = {
                    val picked = checked.indices.filter { checked[it] }
                    onTrace("[check-done] " + picked.joinToString(","))
                    onSend(checklistRequestText(json, picked))
                },
                enabled = anyChecked,
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp, vertical = 4.dp)
            ) {
                Text("把勾选项转为待办", style = MaterialTheme.typography.labelMedium)
            }
        } else {
            Text(
                "已发送创建请求",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// ---------- 文件卡（模型"钓起"某个文件，点击打开预览） ----------

@Composable
fun FileCard(json: JSONObject, onOpenFile: (String) -> Unit) {
    val path = json.optString("path").trim()
    if (path.isBlank()) return
    val name = json.optString("name").ifBlank { path.substringAfter('/') }
    CompCard(
        icon = Icons.Rounded.Description,
        label = "文件",
        status = null,
        onCopyAll = null
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .padding(horizontal = 10.dp, vertical = 8.dp)
        ) {
            Column(Modifier.weight(1f)) {
                Text(name, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
                Text(
                    path,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.width(8.dp))
            TextButton(
                onClick = { onOpenFile(path) },
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 0.dp)
            ) {
                Text("打开", style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

/** 组件段分发渲染（msgContent 供留痕还原；onTrace 写入留痕行） */
@Composable
fun ComponentCard(
    seg: ChatSegment.Card,
    msgContent: String,
    userTexts: List<String>,
    onSend: (String) -> Unit,
    onOpenFile: (String) -> Unit = {},
    onTrace: (String) -> Unit = {},
    stale: Boolean = false
) {
    when (seg.type) {
        "plan" -> PlanCard(seg.json)
        "qa" -> QaCard(seg.json, msgContent, onSend, onTrace, stale)
        "schedule" -> ScheduleCard(seg.json, msgContent, userTexts, onSend, onTrace, stale)
        "checklist" -> ChecklistCard(seg.json, msgContent, userTexts, onSend, onTrace, stale)
        "file" -> FileCard(seg.json, onOpenFile)
    }
}

// ---------- 空态问候与建议 ----------

fun greetingText(hour: Int): String = when (hour) {
    in 5..10 -> "早上好，今天从哪开始？"
    in 11..12 -> "中午好，上午进展如何？"
    in 13..17 -> "下午好，继续推进"
    in 18..22 -> "晚上好呀，今天辛苦啦"
    else -> "夜深了，早点休息"
}

val ASSISTANT_SUGGESTIONS: Map<String, List<String>> = mapOf(
    "planner" to listOf(
        "用plan组件给我一份两周学习计划",
        "查一下明天日程并给优化建议",
        "帮我安排一场30分钟技术分享",
        "哪些待办可以今天顺手做完"
    ),
    "general" to listOf(
        "帮我规划今天剩下的时间",
        "总结我的待办并给建议",
        "用qa组件帮我决定今晚做什么",
        "这周有什么日程冲突"
    ),
    "digest" to listOf(
        "今天群聊有什么重要信息",
        "把今天的提纯整理成要点",
        "哪些采集信息需要我跟进"
    ),
    "tutor" to listOf(
        "用费曼学习法解释什么是复利效应",
        "给我出三道检验理解的小题",
        "帮我制定一周学习计划"
    ),
    "writer" to listOf(
        "帮我把一段话润色得更专业",
        "用markdown表格对比两个方案",
        "帮我列一份文章大纲"
    )
)

fun suggestionsFor(assistantKey: String?): List<String> =
    ASSISTANT_SUGGESTIONS[assistantKey] ?: ASSISTANT_SUGGESTIONS.getValue("general")
