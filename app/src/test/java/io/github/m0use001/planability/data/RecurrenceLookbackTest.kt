package io.github.m0use001.planability.data

import io.github.m0use001.planability.core.AppTime
import java.time.DayOfWeek
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [Recurrence.occurrencesSince] 的相位回归（2026-10-29 加）。
 *
 * 背景：重复待办勾完会把 `date` 滚到**下一期**（可能在未来），监督账本要反推
 * 「过去哪些天本该打卡」时锚点已经在区间之后，`occurrences` 会直接返回空——
 * `occurrencesSince` 负责先把锚点相位对齐地前移。这里钉的就是"相位不漂"：
 * 每周型星期不变、每 N 天型的步长相位不变、每两周型的周序不变、
 * 每月型日号/第几个星期不变、每年型同月同日，以及 until（重复截止）仍是硬边界。
 */
class RecurrenceLookbackTest {

    /** 待办 22 的真实状态：今天(10-29)打卡后滚到 11-04（周三），是个未来锚点 */
    private val anchor: LocalDate = LocalDate.of(2026, 11, 4)

    @Test
    fun `每周多选：跨过未来锚点反推出每个星期`() {
        val rec = Recurrence(Recurrence.Kind.WEEKLY, weekdays = setOf(3, 4))
        val days = rec.occurrencesSince(anchor, LocalDate.of(2026, 10, 19), LocalDate.of(2026, 10, 29))
        // 10/19(一)~10/29(四) 内的周三、周四
        assertEquals(listOf(21, 22, 28, 29), days.map { it.dayOfMonth })
        assertTrue(days.all { it.dayOfWeek == DayOfWeek.WEDNESDAY || it.dayOfWeek == DayOfWeek.THURSDAY })
    }

    @Test
    fun `每 N 天：步长相位不漂（锚点往前整周期挪）`() {
        val rec = Recurrence(Recurrence.Kind.INTERVAL, intervalDays = 3)
        val days = rec.occurrencesSince(anchor, LocalDate.of(2026, 10, 20), LocalDate.of(2026, 10, 29))
        assertEquals(listOf(20, 23, 26, 29), days.map { it.dayOfMonth })
    }

    @Test
    fun `每两周：周序相位保住（隔一周才出现）`() {
        val rec = Recurrence(Recurrence.Kind.WEEKLY, weekdays = setOf(3), biweekly = true)
        val days = rec.occurrencesSince(anchor, LocalDate.of(2026, 10, 5), LocalDate.of(2026, 10, 29))
        // 锚点周(11-04)往回：10-21 同相位、10-07 同相位；10-14 / 10-28 是隔周，排除
        assertEquals(listOf(7, 21), days.map { it.dayOfMonth })
    }

    @Test
    fun `每月按日号：跟随锚点日号且不被小月钳出漂移`() {
        val rec = Recurrence(Recurrence.Kind.MONTHLY)
        val days = rec.occurrencesSince(anchor, LocalDate.of(2026, 9, 1), LocalDate.of(2026, 10, 31))
        assertEquals(listOf(4, 4), days.map { it.dayOfMonth })   // 9/4、10/4
    }

    @Test
    fun `每月第 N 个星期：规则字段说了算`() {
        val rec = Recurrence(
            Recurrence.Kind.MONTHLY, byWeekday = true, nth = 2, monthWeekday = 3
        )
        val days = rec.occurrencesSince(anchor, LocalDate.of(2026, 9, 1), LocalDate.of(2026, 10, 31))
        assertEquals(listOf(9, 14), days.map { it.dayOfMonth })  // 9 月第二个周三、10 月第二个周三
        assertTrue(days.all { it.dayOfWeek == DayOfWeek.WEDNESDAY })
    }

    @Test
    fun `每年：同月同日，窗口内只有当年一次`() {
        val rec = Recurrence(Recurrence.Kind.YEARLY)
        val days = rec.occurrencesSince(anchor, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 12, 31))
        assertEquals(listOf(LocalDate.of(2026, 11, 4)), days)
    }

    @Test
    fun `重复截止日仍是硬边界`() {
        val until = AppTime.dayKey(LocalDate.of(2026, 10, 25))
        val rec = Recurrence(Recurrence.Kind.DAILY, untilDayKey = until)
        val days = rec.occurrencesSince(anchor, LocalDate.of(2026, 10, 23), LocalDate.of(2026, 10, 29))
        assertEquals(listOf(23, 24, 25), days.map { it.dayOfMonth })
    }

    @Test
    fun `不重复的规则反推为空（不凭空造欠账）`() {
        val rec = Recurrence()
        assertTrue(rec.occurrencesSince(anchor, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 29)).isEmpty())
    }
}
