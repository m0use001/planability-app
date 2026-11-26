package io.github.m0use001.planability.data

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.floor

/**
 * v22：打卡数据记录的格式。
 *
 * 存在的原因：Todo 上只存一个 kind 字符串，读历史时**以记录自带的 kind 为准**——
 * 用户改了待办的格式，老记录的读法不能跟着变，否则历史数据会被新格式重新解释成另一种含义。
 */
object CheckinKind {
    const val NONE = ""
    /** 打卡时刻，零输入（例：早餐时间） */
    const val TIME = "time"
    /** 数值 + 单位（例：体重 62.5 kg） */
    const val NUMBER = "number"
    /** 计数（例：喝水 8 次） */
    const val COUNT = "count"
    /** 一句话短文本 */
    const val TEXT = "text"
    /** 多选标签 */
    const val TAGS = "tags"

    val ALL = listOf(TIME, NUMBER, COUNT, TEXT, TAGS)

    fun label(kind: String): String = when (kind) {
        TIME -> "时间"
        NUMBER -> "数值"
        COUNT -> "计数"
        TEXT -> "短文本"
        TAGS -> "多选"
        else -> "未设置"
    }

    /** 除 time 外都要弹录入框 */
    fun needsInput(kind: String): Boolean = kind.isNotEmpty() && kind != TIME

    fun hint(kind: String): String = when (kind) {
        TIME -> "直接记录打卡时刻，无需填写"
        NUMBER -> "填一个数值，单位可写在这里"
        COUNT -> "填次数"
        TEXT -> "填一句话（AI 能看到内容）"
        TAGS -> "从预设选项里多选"
        else -> ""
    }
}

/**
 * 打卡载荷的渲染与摘要。
 *
 * **纯函数、零 Android 依赖**（zone 由调用方传入），所以 UI 与 AI 侧能用同一套措辞，
 * 也便于单元测试。措辞一致性是有意义的：AI 看到的字符串和用户在界面上看到的是同一份。
 */
object CheckinFormat {

    private val hhmm = DateTimeFormatter.ofPattern("HH:mm")
    private val md = DateTimeFormatter.ofPattern("M/d")

    fun time(at: Long, zone: ZoneId): String =
        if (at <= 0) "" else Instant.ofEpochMilli(at).atZone(zone).format(hhmm)

    /** dayKey 是该日本地零点的 epoch millis（AppTime.dayKey 的口径，不是 epochDay） */
    fun dayLabel(dayKey: Long, zone: ZoneId): String =
        if (dayKey <= 0) "" else Instant.ofEpochMilli(dayKey).atZone(zone).toLocalDate().format(md)

    /** 整数不带小数点，小数最多两位且去掉末尾 0 */
    fun trimNum(v: Double): String {
        if (v.isNaN() || v.isInfinite()) return ""
        return if (v == floor(v)) v.toLong().toString() else {
            var s = "%.2f".format(v)
            s = s.trimEnd('0').trimEnd('.')
            s.ifEmpty { "0" }
        }
    }

    /** 一条记录的值（不含日期）：「08:12」「62.5 kg」「3 次」「豆浆、油条」 */
    fun value(r: CheckinRecord, zone: ZoneId): String = when (r.kind) {
        CheckinKind.TIME -> time(r.at, zone)
        CheckinKind.NUMBER -> trimNum(r.numValue) + unitSuffix(r.unit)
        CheckinKind.COUNT -> trimNum(r.numValue) + " 次"
        CheckinKind.TEXT -> r.textValue
        CheckinKind.TAGS -> r.tags.split(',').map { it.trim() }.filter { it.isNotEmpty() }.joinToString("、")
        else -> ""
    }

    private fun unitSuffix(unit: String): String =
        if (unit.isBlank()) "" else " " + unit.trim()

    /** 「10/7 08:12」 */
    fun line(r: CheckinRecord, zone: ZoneId): String =
        (dayLabel(r.date, zone) + " " + value(r, zone)).trim()

    /** 按日期倒序（同一天内按打卡时刻倒序） */
    fun sortForDisplay(rs: List<CheckinRecord>): List<CheckinRecord> =
        rs.sortedWith(compareByDescending<CheckinRecord> { it.date }.thenByDescending { it.at })

    /**
     * 给 AI 的一段摘要：按格式挑**真正有意义的统计**，而不是把所有格式都套同一种平均。
     * - 时间型：最早/最晚/平均 + 波动（够 3 条才算波动，才敢说「规律/不规律」）
     * - 数值/计数型：最近一次 + 均值 + 极值
     * - 文本/多选：最近 3 条原文（AI 自己读语义，统计没意义）
     */
    fun summary(todo: Todo, rs: List<CheckinRecord>, zone: ZoneId): String {
        if (rs.isEmpty()) return ""
        val ordered = sortForDisplay(rs)
        val kind = ordered.first().kind
        val name = todo.dataRecordLabel.ifBlank { todo.title }
        val sb = StringBuilder()
        sb.append("【").append(todo.title).append("】")
            .append(name).append("（").append(CheckinKind.label(kind))
            .append("型，共 ").append(ordered.size).append(" 次）")

        when (kind) {
            CheckinKind.TIME -> {
                val times = ordered.map { it.at }.filter { it > 0 }.sorted()
                if (times.isNotEmpty()) {
                    val lo = times.first()
                    val hi = times.last()
                    sb.append(" 最早 ").append(time(lo, zone)).append("，最晚 ").append(time(hi, zone))
                    if (times.size >= 2) {
                        sb.append("，平均 ").append(time(times.average().toLong(), zone))
                    }
                    if (times.size >= 3) {
                        val spreadMin = (hi - lo) / 60_000
                        sb.append("，最早与最晚相差 ").append(spreadMin).append(" 分钟")
                        sb.append(if (spreadMin <= 45) "（比较规律）" else "（波动较大）")
                    }
                    sb.append("。最近：")
                        .append(ordered.take(5).joinToString("、") { line(it, zone) })
                }
            }
            CheckinKind.NUMBER, CheckinKind.COUNT -> {
                val same = ordered.filter { it.kind == kind }
                val vs = same.map { it.numValue }
                if (vs.isNotEmpty()) {
                    sb.append(" 最近一次 ").append(value(same.first(), zone))
                    sb.append("，均值 ").append(trimNum(vs.average()))
                    sb.append("，最低 ").append(trimNum(vs.min()))
                    sb.append("，最高 ").append(trimNum(vs.max()))
                    sb.append("。最近：")
                        .append(ordered.take(5).joinToString("、") { line(it, zone) })
                }
            }
            else -> {
                sb.append(" 最近：")
                    .append(ordered.take(3).joinToString("；") { line(it, zone) })
            }
        }
        return sb.toString()
    }

    /** 新建一条记录时的默认值：time 型自动填当前时刻，其余留空等用户填 */
    fun blank(todo: Todo, date: Long, nowMillis: Long): CheckinRecord = CheckinRecord(
        todoId = todo.id,
        date = date,
        at = nowMillis,
        kind = todo.dataRecordKind.ifBlank { CheckinKind.TIME },
        unit = todo.dataRecordUnit
    )

    /** 多选型的预设选项（用户在编辑器里用顿号/逗号分隔输入） */
    fun parseOptions(raw: String): List<String> =
        raw.split(',', '，', '、')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()

    fun formatOptions(list: List<String>): String = list.joinToString(",")
}
