package io.github.m0use001.planability.data

import io.github.m0use001.planability.core.formatShort
import io.github.m0use001.planability.core.toLocalDate
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit

/**
 * 统一重复引擎（日程与待办共用）。
 *
 * 覆盖全部重复形态：
 * - 不重复 / 每天 / 每周(星期多选，可切每两周) / 每月(按日号 或 按"第N个星期X") / 每年 / 每 N 天
 * - 结束条件：永久 或 截止日(dayKey)
 * - 月内缺日（31 日遇小月）约定：顺延到当月最后一天
 *
 * 存储：日程 repeatRule 单字符串（例 "weekly:1,3;until:20459"、"monthlyW:2:3"、"interval:14"）；
 * 待办沿用 repeatType/repeatDays/repeatInterval/repeatEnd 四列（monthly/monthlyW/yearly 编码进 repeatType）。
 */
data class Recurrence(
    val kind: Kind = Kind.NONE,
    val weekdays: Set<Int> = emptySet(),   // WEEKLY：1=周一..7=周日；空集 = 用锚点星期
    val biweekly: Boolean = false,         // WEEKLY 修饰：每两周
    val intervalDays: Int = 1,             // INTERVAL：每 N 天
    val byWeekday: Boolean = false,        // MONTHLY：false=按日号(nth=日号, 0=跟随锚点) true=按第N个星期
    val nth: Int = 0,                      // MONTHLY 按日号：几号(0=锚点日)；按星期：第几个(1-4，5=最后)
    val monthWeekday: Int = 1,             // MONTHLY 按星期：星期几(1-7)
    val untilDayKey: Long = 0              // 0 = 永久
) {
    enum class Kind { NONE, DAILY, WEEKLY, MONTHLY, YEARLY, INTERVAL }

    val repeats: Boolean get() = kind != Kind.NONE
    private val until: LocalDate? get() = if (untilDayKey > 0) untilDayKey.toLocalDate() else null

    /** [from, to] 区间内的出现日（跳过锚点之前的日期；截至截止日） */
    fun occurrences(anchor: LocalDate, from: LocalDate, to: LocalDate): List<LocalDate> {
        if (!repeats) return emptyList()
        val start = if (from.isBefore(anchor)) anchor else from
        if (start.isAfter(to)) return emptyList()
        val out = mutableListOf<LocalDate>()
        val u = until
        when (kind) {
            Kind.DAILY, Kind.INTERVAL -> {
                val step = if (kind == Kind.DAILY) 1L else intervalDays.coerceAtLeast(1).toLong()
                var d = anchor
                val diff = ChronoUnit.DAYS.between(anchor, start)
                if (diff > 0) d = anchor.plusDays(((diff + step - 1) / step) * step)
                while (!d.isAfter(to)) {
                    out += d
                    d = d.plusDays(step)
                    if (out.size > 1500) break
                }
            }
            Kind.WEEKLY -> {
                val days = if (weekdays.isEmpty()) setOf(anchor.dayOfWeek.value) else weekdays
                var d = start
                while (!d.isAfter(to)) {
                    if (d.dayOfWeek.value in days) {
                        if (!biweekly || ChronoUnit.WEEKS.between(anchor, d) % 2 == 0L) out += d
                    }
                    d = d.plusDays(1)
                    if (out.size > 1500) break
                }
            }
            Kind.MONTHLY -> {
                var ym = YearMonth.from(start)
                val lastYm = YearMonth.from(to)
                while (!ym.isAfter(lastYm)) {
                    val d = if (byWeekday) {
                        nthWeekdayOfMonth(ym, nth.coerceIn(1, 5), monthWeekday.coerceIn(1, 7))
                    } else {
                        val dayNum = (if (nth in 1..31) nth else anchor.dayOfMonth).coerceAtMost(ym.lengthOfMonth())
                        ym.atDay(dayNum)
                    }
                    if (d != null && !d.isBefore(anchor) && !d.isBefore(start)) out += d
                    ym = ym.plusMonths(1)
                }
            }
            Kind.YEARLY -> {
                var year = start.year
                val lastYear = to.year
                while (year <= lastYear) {
                    val monthLen = YearMonth.of(year, anchor.month).lengthOfMonth()
                    val d = LocalDate.of(year, anchor.month, anchor.dayOfMonth.coerceAtMost(monthLen))
                    if (!d.isBefore(anchor) && !d.isBefore(start)) out += d
                    year++
                }
            }
            else -> {}
        }
        return out.filter { !it.isAfter(to) && (u == null || !it.isAfter(u)) }
    }

    /** 严格晚于 after 的下一个出现日（用于待办完成后滚动）；无（含超出截止）返回 null */
    fun nextAfter(anchor: LocalDate, after: LocalDate): LocalDate? {
        val bound = until ?: after.plusDays(366L * 4)
        return occurrences(anchor, after.plusDays(1), bound).firstOrNull()
    }

    /** 锚点当日或之后的首个出现日（编辑器频率→日期联动用）；无匹配返回 null */
    fun firstOccurrenceOnOrAfter(anchor: LocalDate): LocalDate? {
        if (!repeats) return anchor
        val bound = until ?: anchor.plusMonths(3)
        return occurrences(anchor, anchor, bound).firstOrNull()
    }

    /**
     * **相位对齐地反推** [from, to] 内的出现日（2026-10-29 加，监督账本「过期」用）。
     *
     * 为什么需要它：重复待办完成后 `todo.date` 被滚到**下一期**（可能在未来），
     * 往回推历史应完成日时锚点已经在 from 之后，[occurrences] 会直接返回空。
     * 这里先按规则类型把锚点前移到 from 之前**并保持相位**，再交给同一个引擎枚举——
     * 不另写一套判定，避免和 [occurrences] 漂移。
     *
     * 相位怎么保：
     * - INTERVAL：整周期往前挪（`(from−anchor)/step·step`），步长相位不变；
     * - WEEKLY：取 from 之前最近的**同星期**日（整周平移，每两周的周序也保住），
     *   显式 weekdays 时锚点星期本来就不参与判定；
     * - MONTHLY：按规则字段（第几个星期/几号）判定的，任何 ≤from 的锚点都行；
     *   跟随锚点日号的（nth=0），往前找**有这一号**的月份，避免 31 日被小月钳成 30 后漂移；
     * - YEARLY：往前一年、同月同日（2/29 遇平年按引擎口径钳到月末）。
     */
    fun occurrencesSince(anchor: LocalDate, from: LocalDate, to: LocalDate): List<LocalDate> {
        if (!repeats) return emptyList()
        // 锚点已经在 from 之后（滚期后的未来锚点）才需要前移；否则原样交给引擎
        if (!anchor.isAfter(from)) return occurrences(anchor, from, to)
        val a = when (kind) {
            Kind.DAILY -> from.minusDays(1)
            Kind.INTERVAL -> {
                val step = intervalDays.coerceAtLeast(1).toLong()
                val diff = ChronoUnit.DAYS.between(anchor, from)
                anchor.plusDays((diff / step) * step)
            }
            Kind.WEEKLY -> {
                if (biweekly) {
                    // 每两周的周序 = 锚点周 ± 偶数周；奇偶挪错一周，"该打的日子"就整组错开
                    // （between(anchor, from) 为负，所以是 plusWeeks 往回走）
                    var weeks = ChronoUnit.DAYS.between(anchor, from) / 7L
                    if (weeks % 2 != 0L) weeks -= 1
                    anchor.plusWeeks(weeks)
                } else {
                    var d = from.minusDays(1)
                    while (d.dayOfWeek != anchor.dayOfWeek) d = d.minusDays(1)
                    d
                }
            }
            Kind.MONTHLY -> {
                if (byWeekday || nth in 1..31) {
                    from.minusDays(1)
                } else {
                    val floorYm = YearMonth.from(from).minusMonths(3)
                    var ym = YearMonth.from(from).minusMonths(1)
                    while (ym.lengthOfMonth() < anchor.dayOfMonth && ym.isAfter(floorYm)) {
                        ym = ym.minusMonths(1)
                    }
                    ym.atDay(minOf(anchor.dayOfMonth, ym.lengthOfMonth()))
                }
            }
            Kind.YEARLY -> {
                val y = from.year - 1
                val len = YearMonth.of(y, anchor.monthValue).lengthOfMonth()
                LocalDate.of(y, anchor.monthValue, minOf(anchor.dayOfMonth, len))
            }
            else -> anchor
        }
        return occurrences(a, from, to)
    }

    fun describe(anchor: LocalDate): String {
        val base = when (kind) {
            Kind.NONE -> "不重复"
            Kind.DAILY -> "每天"
            Kind.WEEKLY -> {
                val days = (if (weekdays.isEmpty()) listOf(anchor.dayOfWeek.value) else weekdays).sorted()
                val names = days.map { "周" + DayOfWeek.of(it).getDisplayName(java.time.format.TextStyle.NARROW, java.util.Locale.CHINESE) }
                (if (biweekly) "每两周 " else "每周 ") + names.joinToString("、")
            }
            Kind.MONTHLY ->
                if (byWeekday) "每月${nthName(nth)}星期${DayOfWeek.of(monthWeekday).getDisplayName(java.time.format.TextStyle.NARROW, java.util.Locale.CHINESE)}"
                else "每月 ${if (nth in 1..31) nth else anchor.dayOfMonth} 日"
            Kind.YEARLY -> "每年 ${anchor.monthValue} 月 ${anchor.dayOfMonth} 日"
            Kind.INTERVAL -> "每 $intervalDays 天"
        }
        return base + (until?.let { " · 至 ${it.formatShort()}" } ?: "")
    }

    /**
     * 列表行用的**紧凑**描述（2026-10-21 用户：「频率显示也改得整洁一点」）。
     *
     * 老文案 `每周 周一、周四 · 自 9月14日 · 至 12月28日` 太长，
     * 一行里被挤成糊成一团的灰字。紧凑版：
     * `每周一、四 · 9/14–12/28`（跨年才带年份，如 `9/14–2027/1/15`）。
     * 次数不在这里：列表里单独右对齐显示，方便对账又不会把频率挤扁。
     */
    fun describeCompact(anchor: LocalDate): String {
        val base = when (kind) {
            Kind.NONE -> "不重复"
            Kind.DAILY -> "每天"
            Kind.WEEKLY -> {
                val days = (if (weekdays.isEmpty()) listOf(anchor.dayOfWeek.value) else weekdays).sorted()
                val names = days.joinToString("、") { "一二三四五六日"[it - 1].toString() }
                if (biweekly) "每两周 周$names" else "每周$names"
            }
            Kind.MONTHLY ->
                if (byWeekday) {
                    val nthText = if (nth in 1..4) "第${nth}个" else "最后一个"
                    "每月${nthText}周" + "一二三四五六日"[monthWeekday - 1]
                } else "每月 ${if (nth in 1..31) nth else anchor.dayOfMonth} 日"
            Kind.YEARLY -> "每年 ${anchor.monthValue}/${anchor.dayOfMonth}"
            Kind.INTERVAL -> "每 $intervalDays 天"
        }
        val end = until ?: return base
        val startText = "${anchor.monthValue}/${anchor.dayOfMonth}"
        val endText = if (end.year == anchor.year) "${end.monthValue}/${end.dayOfMonth}"
        else "${end.year}/${end.monthValue}/${end.dayOfMonth}"
        return "$base · $startText–$endText"
    }

    private fun nthName(n: Int): String = when (n) {
        1 -> "第1个"; 2 -> "第2个"; 3 -> "第3个"; 4 -> "第4个"; else -> "最后一个"
    }

    companion object {
        val NONE = Recurrence()

        // ---------- 日程：repeatRule 单字符串 ----------

        fun parseScheduleRule(rule: String): Recurrence {
            val parts = rule.split(";").map { it.trim() }.filter { it.isNotEmpty() }
            val until = parts.firstOrNull { it.startsWith("until:") }
                ?.removePrefix("until:")?.toLongOrNull() ?: 0L
            val head = parts.firstOrNull { !it.startsWith("until:") } ?: ""
            val seg = head.split(":")
            val rec = when (seg[0]) {
                "daily" -> Recurrence(Kind.DAILY, untilDayKey = until)
                "weekly" -> Recurrence(
                    Kind.WEEKLY,
                    weekdays = seg.getOrNull(1)?.split(",")?.mapNotNull { it.trim().toIntOrNull() }?.toSet() ?: emptySet(),
                    untilDayKey = until
                )
                "biweekly" -> Recurrence(
                    Kind.WEEKLY, biweekly = true,
                    weekdays = seg.getOrNull(1)?.split(",")?.mapNotNull { it.trim().toIntOrNull() }?.toSet() ?: emptySet(),
                    untilDayKey = until
                )
                "monthly" -> Recurrence(Kind.MONTHLY, untilDayKey = until)
                "monthlyW" -> Recurrence(
                    Kind.MONTHLY, byWeekday = true,
                    nth = seg.getOrNull(1)?.toIntOrNull() ?: 1,
                    monthWeekday = seg.getOrNull(2)?.toIntOrNull() ?: 1,
                    untilDayKey = until
                )
                "yearly" -> Recurrence(Kind.YEARLY, untilDayKey = until)
                "interval" -> Recurrence(Kind.INTERVAL, intervalDays = seg.getOrNull(1)?.toIntOrNull() ?: 1, untilDayKey = until)
                else -> Recurrence(untilDayKey = until)
            }
            return rec
        }

        fun toScheduleRule(rec: Recurrence): String {
            if (!rec.repeats) return ""
            val head = when (rec.kind) {
                Kind.WEEKLY ->
                    if (rec.biweekly) "biweekly" + rec.weekdays.sorted().joinToString(",").let { if (it.isEmpty()) "" else ":$it" }
                    else "weekly" + rec.weekdays.sorted().joinToString(",").let { if (it.isEmpty()) "" else ":$it" }
                Kind.MONTHLY ->
                    if (rec.byWeekday) "monthlyW:${rec.nth}:${rec.monthWeekday}" else "monthly"
                Kind.INTERVAL -> "interval:${rec.intervalDays.coerceAtLeast(1)}"
                Kind.DAILY -> "daily"
                Kind.YEARLY -> "yearly"
                else -> ""
            }
            return if (rec.untilDayKey > 0) "$head;until:${rec.untilDayKey}" else head
        }

        // ---------- 待办：repeatType/repeatDays/repeatInterval/repeatEnd ----------

        fun parseTodoRule(todo: Todo): Recurrence {
            val until = if (todo.permanent) 0L else todo.repeatEnd
            val days = todo.repeatDays.split(",").mapNotNull { it.trim().toIntOrNull() }.toSet()
            val seg = todo.repeatType.split(":")
            return when (seg[0]) {
                "daily" -> Recurrence(Kind.DAILY, untilDayKey = until)
                "weekly" -> Recurrence(Kind.WEEKLY, weekdays = days, untilDayKey = until)
                "biweekly" -> Recurrence(Kind.WEEKLY, biweekly = true, weekdays = days, untilDayKey = until)
                "interval" -> Recurrence(Kind.INTERVAL, intervalDays = todo.repeatInterval.coerceAtLeast(1), untilDayKey = until)
                "monthly" -> Recurrence(Kind.MONTHLY, untilDayKey = until)
                "monthlyW" -> Recurrence(
                    Kind.MONTHLY, byWeekday = true,
                    nth = seg.getOrNull(1)?.toIntOrNull() ?: 1,
                    monthWeekday = seg.getOrNull(2)?.toIntOrNull() ?: 1,
                    untilDayKey = until
                )
                "yearly" -> Recurrence(Kind.YEARLY, untilDayKey = until)
                else -> Recurrence()
            }
        }

        /** 返回 repeatType / repeatDays / repeatInterval（repeatEnd 直接取 rec.untilDayKey） */
        fun toTodoParts(rec: Recurrence): Triple<String, String, Int> = when {
            !rec.repeats -> Triple("none", "", 0)
            rec.kind == Kind.WEEKLY && rec.biweekly ->
                Triple("biweekly", rec.weekdays.sorted().joinToString(","), 0)
            rec.kind == Kind.WEEKLY ->
                Triple("weekly", rec.weekdays.sorted().joinToString(","), 0)
            rec.kind == Kind.MONTHLY && rec.byWeekday ->
                Triple("monthlyW:${rec.nth}:${rec.monthWeekday}", "", 0)
            rec.kind == Kind.MONTHLY -> Triple("monthly", "", 0)
            rec.kind == Kind.YEARLY -> Triple("yearly", "", 0)
            rec.kind == Kind.INTERVAL -> Triple("interval", "", rec.intervalDays.coerceAtLeast(1))
            else -> Triple("daily", "", 0)
        }

        private fun nthWeekdayOfMonth(ym: YearMonth, nth: Int, weekday: Int): LocalDate? {
            val first = ym.atDay(1)
            val offset = (weekday - first.dayOfWeek.value + 7) % 7
            return if (nth >= 5) {
                // 最后一个星期X
                var d = ym.atEndOfMonth()
                while (d.dayOfWeek.value != weekday) d = d.minusDays(1)
                d
            } else {
                val d = first.plusDays(offset.toLong() + (nth - 1) * 7L)
                if (d.month == ym.month) d else null
            }
        }
    }
}
