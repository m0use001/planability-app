package io.github.m0use001.planability.ai

import org.json.JSONArray
import org.json.JSONObject

/**
 * 容错 JSON 解析（**模型输出专用**）。
 *
 * ## 为什么存在
 * 凡是「解析模型吐出来的 JSON」的口子——内嵌组件卡（ChatComponents）、
 * 工具调用 arguments（ToolExecutor/AiStream）、记忆整理 ops（parseJsonArray）——
 * 都遇过模型输出**不是严格合法 JSON** 的情况。历史修法是遇一种坏法加一档降级
 * （DSML 归一化、逗号分隔 options、剥尾括号、2026-11-05 又见字符串值内
 * 未转义引号 `"给朋友看、要"好看""`），补丁越摞越多还是追不上。
 *
 * 根治 = 一台**扫描式修复器**：逐字符扫描，边判断字符串内外边修，一次覆盖
 * 实测与可预见的主要坏法：
 * ① 字符串值内未转义 `"`（真结束符判定：后随 `,:}]`/流尾，否则补 `\` 转义；
 *   全角标点不算结构符，中文语境几乎不误伤）
 * ② 全角定界符与结构位标点（`“”` 作定界符且**成对混用**、结构位的 `，：`）
 * ③ 单引号字符串
 * ④ 尾逗号
 * ⑤ 括号不配平/交换（`}` `]` 写反时先补配平的那个）
 * ⑥ 截断缺闭合（补引号、按栈逆序补括号——流式半截也能解）
 * ⑦ 顶层值之后的垃圾（闭合即截断）
 *
 * 入口一律**先试严格解析**（正常 JSON 零风险），失败才修。
 */
object JsonRepair {

    /** 解析为 JSONObject；严格失败后走修复。null = 连修复也救不回。 */
    fun parseObject(text: String): JSONObject? = parse(text) as? JSONObject

    /** 解析为 JSONArray；严格失败后走修复。 */
    fun parseArray(text: String): JSONArray? = parse(text) as? JSONArray

    private fun parse(text: String): Any? {
        val t = text.trim()
        if (t.isEmpty()) return null
        try { return if (t[0] == '[') JSONArray(t) else JSONObject(t) } catch (_: Exception) {}
        val r = repair(t) ?: return null
        return try { if (r[0] == '[') JSONArray(r) else JSONObject(r) } catch (_: Exception) { null }
    }

    /** 顶层开头必须是 `{` 或 `[`；找不到说明根本不是 JSON，返回 null（不硬造）。 */
    private fun repair(src: String): String? {
        val start = src.indexOfFirst { it == '{' || it == '[' }
        if (start < 0) return null
        val out = StringBuilder(src.length + 16)
        val stack = ArrayDeque<Char>()
        var inString = false
        // 当前字符串的**定界符类别**：双引号类（" “ ” —— 模型常混用/写一半，必须视作同一类）
        // 或单引号类。这样 “备注" 这种混写也能正确收尾。
        var delim = '"'
        var i = start
        while (i < src.length) {
            val c = src[i]
            if (!inString) {
                when (c) {
                    '"', '“', '”' -> { inString = true; delim = '"'; out.append('"') }
                    '\'' -> { inString = true; delim = '\''; out.append('"') }
                    '{' -> { stack.addLast('}'); out.append(c) }
                    '[' -> { stack.addLast(']'); out.append(c) }
                    '}', ']' -> {
                        val want = stack.lastOrNull() ?: break // 顶层已闭合 → 后面全是垃圾，截断
                        if (want != c) { out.append(want); stack.removeLast(); continue } // 括号写反 → 先补配平的
                        stack.removeLast(); out.append(c)
                        if (stack.isEmpty()) break // 顶层闭合即截断：之后的正文/残渣一律不进结果
                    }
                    ',' -> {
                        var j = i + 1
                        while (j < src.length && src[j].isWhitespace()) j++
                        if (src.getOrNull(j) !in arrayOf('}', ']', null)) out.append(c) // 尾逗号丢弃
                    }
                    '，' -> out.append(',')   // 全角标点只有落在结构位才会进到这里
                    '：' -> out.append(':')
                    else -> out.append(c)
                }
                i++
            } else {
                val isCloser = when (delim) {
                    '\'' -> c == '\''
                    else -> c == '"' || c == '“' || c == '”'
                }
                when {
                    c == '\\' -> { // 已转义序列照抄（含 \" —— 不参与下面的真结束符判定）
                        out.append(c)
                        if (i + 1 < src.length) { out.append(src[i + 1]); i++ }
                    }
                    isCloser -> {
                        var j = i + 1
                        while (j < src.length && src[j].isWhitespace()) j++
                        when (src.getOrNull(j)) {
                            // 真结束符：后随结构位字符（含全角逗号/冒号，模型常在结构位写全角）
                            null, ':', ',', '}', ']', '，', '：' -> { inString = false; out.append('"') }
                            else -> out.append('\\').append('"') // 内容引号 → 补转义
                        }
                    }
                    else -> out.append(c) // 另一类定界符/其他字符落在字符串内 = 内容，原样保留
                }
                i++
            }
        }
        if (inString) out.append('"')               // 截断在字符串中间 → 补收尾
        while (stack.isNotEmpty()) out.append(stack.removeLast()) // 截断缺闭合 → 逆序补齐
        return out.toString()
    }
}
