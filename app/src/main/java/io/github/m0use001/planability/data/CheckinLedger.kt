package io.github.m0use001.planability.data

import io.github.m0use001.planability.core.AppTime
import io.github.m0use001.planability.core.toLocalDate
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * v23：打卡账本 —— 把「哪些天该打、打了没有、是不是补交、该记几分」算成一张逐日清单。
 *
 * 为什么要独立成纯函数（不碰数据库、不碰 Android）：
 * 「已过期」列表、待办行的完成率、编辑器里的打卡情况、数据中心的趋势，
 * 四处都要同一套判定。任何一处自己写一遍，几天后必然漂移。
 *
 * 口径（用户 2026-10-08 定）：
 * - 「该打的天」= 常驻或每天重复的监督待办，从统计起点到今天**每一天**；
 * - 迟 n 天补交 → 那天只记 `100 − 25n`%（n≥4 记 0），未完成记 0；
 * - 完成率 = 逐日得分之和 / 应完成天数。
 */
object CheckinLedger {

    /** 每迟一天扣的百分点 */
    const val PENALTY_PER_DAY = 25

    /** 那天得分：按时 100，迟 n 天 100−25n，下限 0 */
    fun credit(delayDays: Int): Int =
        (100 - PENALTY_PER_DAY * delayDays.coerceAtLeast(0)).coerceIn(0, 100)

    /** 实际打卡时刻比应打日期晚几天；at=0（老数据）按 0 天算 */
    fun delayDays(date: Long, at: Long, zone: ZoneId): Int {
        if (at <= 0) return 0
        val target = Instant.ofEpochMilli(at).atZone(zone).toLocalDate()
        return ChronoUnit.DAYS.between(date.toLocalDate(), target).toInt().coerceAtLeast(0)
    }

    /** 是否补交（迟于当天） */
    fun isLate(date: Long, at: Long, zone: ZoneId): Boolean = delayDays(date, at, zone) > 0

    /**
     * 算逐日账本。[checks] 是 dayKey → 实际打卡时刻，[records] 是 dayKey → 载荷（可空）。
     * 返回按日期倒序，最后一项是最早的一天（dayNo 从统计起点起算，1 开始）。
     */
    fun build(
        start: LocalDate,
        end: LocalDate,
        checks: Map<Long, Long>,
        records: Map<Long, CheckinRecord> = emptyMap(),
        zone: ZoneId
    ): List<CheckinDay> {
        if (end.isBefore(start)) return emptyList()
        val todayKey = AppTime.dayKey(AppTime.today())
        val total = ChronoUnit.DAYS.between(start, end).toInt() + 1
        val out = ArrayList<CheckinDay>(total)
        for (i in 0 until total) {
            val d = start.plusDays(i.toLong())
            val key = AppTime.dayKey(d)
            val at = checks[key]
            val rec = records[key]
            out += when {
                // 今天还没到点就欠账是荒谬的（早上八点报"欠一天"），
                // 今天单列 PENDING：不进缺卡、不进完成率分母，只在明细里显示"今天"
                at == null && key == todayKey -> CheckinDay(
                    date = key, dayNo = i + 1, state = CheckinDay.PENDING,
                    delayDays = 0, credit = 0, record = rec
                )
                at == null -> CheckinDay(
                    date = key, dayNo = i + 1, state = CheckinDay.MISSED,
                    delayDays = 0, credit = 0, record = rec
                )
                else -> {
                    val delay = delayDays(key, at, zone)
                    CheckinDay(
                        date = key, dayNo = i + 1,
                        state = if (delay > 0) CheckinDay.LATE else CheckinDay.DONE,
                        delayDays = delay, credit = credit(delay), record = rec
                    )
                }
            }
        }
        return out.asReversed()
    }

    /**
     * 完成率（0..100）：逐日得分均值。没记录整天则 0。
     *
     * 注意别再乘 100 —— [credit] 本身就是百分制（100/75/50/…），
     * 乘一次会算出 5000 然后被 coerce 成 100，于是"欠 3 天"看起来也是满分。
     */
    fun rate(days: List<CheckinDay>): Int {
        // PENDING（今天还没打）不进分母 —— 一天还没过完，不能先当欠账扣分
        val scored = days.filter { it.state != CheckinDay.PENDING }
        if (scored.isEmpty()) return if (days.any { it.checked }) 100 else 0
        val sum = scored.sumOf { it.credit }
        return (sum / scored.size).coerceIn(0, 100)
    }

    fun missedCount(days: List<CheckinDay>): Int = days.count { it.state == CheckinDay.MISSED }

    fun lateCount(days: List<CheckinDay>): Int = days.count { it.state == CheckinDay.LATE }

    /**
     * 非逐日重复待办的「应完成日」集合（dayKey）。2026-10-29 加：
     * 原来账本对 weekly/interval 这类只列"有记录的那几天"，永远造不出 MISSED，
     * 「过期 N」对它们恒为 0 —— 用户实测"过期机制没用"。
     *
     * 起始口径：`max(今天−[maxLookbackDays], 创建日, 首次打卡日, 当前锚点)` 里最晚的那个 ——
     * 不往前造待办还不存在的欠账，也不让老待办一口气造出几百天；
     * 结束封在 `repeatEnd`（规则自身的 until 也会再挡一道）。
     *
     * 纯函数（不碰 Room），与 [build] 一样由单测直接钉住。
     */
    fun expectedDays(
        todo: Todo,
        checks: List<SupervisionCheck>,
        today: LocalDate,
        maxLookbackDays: Long = 90L
    ): Set<Long> {
        val rec = Recurrence.parseTodoRule(todo)
        if (!rec.repeats || todo.permanent) return emptySet()
        val anchor = when {
            todo.date > 0 -> todo.date.toLocalDate()
            todo.createdAt > 0 -> todo.createdAt.toLocalDate()
            else -> today
        }
        val origin = listOfNotNull(
            todo.createdAt.takeIf { it > 0 }?.toLocalDate(),
            checks.minOfOrNull { it.date }?.toLocalDate(),
            todo.date.takeIf { it > 0 }?.toLocalDate()
        ).minOrNull() ?: today
        val from = maxOf(today.minusDays(maxLookbackDays), origin)
        val end = if (todo.repeatEnd > 0) minOf(today, todo.repeatEnd.toLocalDate()) else today
        if (from.isAfter(end)) return emptySet()
        return rec.occurrencesSince(anchor, from, end).map { AppTime.dayKey(it) }.toSet()
    }
}

/**
 * 账本里的一天。
 *
 * state 三态：按时完成 / 补交完成 / 未完成。
 * [dayNo] 是「这是该待办统计起点以来的第几天」，用户要看「第几天」用它。
 */
data class CheckinDay(
    val date: Long,
    val dayNo: Int,
    val state: String,
    val delayDays: Int,
    val credit: Int,
    val record: CheckinRecord?
) {
    val checked: Boolean get() = state != MISSED
    val late: Boolean get() = state == LATE

    companion object {
        const val DONE = "done"
        const val LATE = "late"
        const val MISSED = "missed"
        /** 今天，还没到点：不算欠账也不进完成率 */
        const val PENDING = "pending"

        fun label(state: String): String = when (state) {
            DONE -> "按时完成"
            LATE -> "补交"
            PENDING -> "今天"
            else -> "未完成"
        }
    }
}