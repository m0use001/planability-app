package io.github.m0use001.planability.data

import io.github.m0use001.planability.core.AppTime
import io.github.m0use001.planability.core.toLocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * 2026-10-31：AI 建重复待办的能力（用户实锤「让 AI 写待办，它说连重复待办都建不了」）。
 *
 * 这组断言守的是**参数契约 → 待办四列 → 回来还是同一条规则**这条链：
 * 模型传 `weekly:1,3`，落库成 repeatType=weekly / repeatDays="1,3"，
 * 再由 `parseTodoRule` 读回来必须还是「每周一、三」，`occurrences` 也真的按周一二三出。
 * 中间任何一环漏写都表现为"AI 说建好了，列表里却是一次性待办"，不崩不报错。
 *
 * 语法解析复用 `Recurrence.parseScheduleRule`（与日程 repeatRule 同一套），
 * 所以这里同时锁住"两端语法不会各走各的"。
 */
class TodoRepeatToolContractTest {

    /** 模拟模型传参的最短路径：参数串 → Recurrence（PlanTools.repeatOf 的内核） */
    private fun rec(spec: String, until: Long = 0): Recurrence =
        Recurrence.parseScheduleRule(spec).copy(untilDayKey = until)

    /** Recurrence → 待办四列（PlanTools.todoWithRepeat 的同一组调用） */
    private fun apply(todo: Todo, r: Recurrence): Todo {
        val (type, days, interval) = Recurrence.toTodoParts(r)
        return todo.copy(
            repeatType = type, repeatDays = days, repeatInterval = interval,
            repeatEnd = r.untilDayKey, permanent = r.untilDayKey == 0L
        )
    }

    private val base = Todo(title = "背单词", date = AppTime.dayKey(LocalDate.of(2026, 11, 2)))  // 2026-11-02 是周一

    @Test
    fun weeklyMultiDayRoundTrips() {
        val r = rec("weekly:1,3,5")
        val t = apply(base, r)
        assertEquals("weekly", t.repeatType)
        assertEquals("1,3,5", t.repeatDays)
        assertTrue("缺省=永久", t.permanent)
        assertEquals(0L, t.repeatEnd)

        val back = Recurrence.parseTodoRule(t)
        assertEquals(Recurrence.Kind.WEEKLY, back.kind)
        assertEquals(setOf(1, 3, 5), back.weekdays)
        assertFalse(back.biweekly)

        // 真的按周一/三/五出：2026-11-02(一) ~ 11-08(日) 应命中 3 天
        val anchor = base.date.toLocalDate()
        val occ = back.occurrences(anchor, anchor, anchor.plusDays(6))
        assertEquals(listOf(2, 4, 6), occ.map { it.dayOfMonth })
    }

    @Test
    fun biweeklyKeepsParity() {
        val r = rec("biweekly:1")
        val t = apply(base, r)
        assertEquals("biweekly", t.repeatType)
        val back = Recurrence.parseTodoRule(t)
        assertTrue(back.biweekly)
        // 锚点周一 + 每两周：11-02、11-16、11-30
        val anchor = base.date.toLocalDate()
        val occ = back.occurrences(anchor, anchor, anchor.plusDays(30))
        assertEquals(listOf(2, 16, 30), occ.map { it.dayOfMonth })
    }

    @Test
    fun intervalDaysRoundTrips() {
        val r = rec("interval:14")
        val t = apply(base, r)
        assertEquals("interval", t.repeatType)
        assertEquals(14, t.repeatInterval)
        val back = Recurrence.parseTodoRule(t)
        assertEquals(Recurrence.Kind.INTERVAL, back.kind)
        assertEquals(14, back.intervalDays)
        val anchor = base.date.toLocalDate()
        val occ = back.occurrences(anchor, anchor, anchor.plusDays(41))
        assertEquals(listOf(2, 16, 30), occ.map { it.dayOfMonth })  // +0/+14/+28（窗口第 42 天是 12/14，不算）
    }

    @Test
    fun monthlyByWeekdayRoundTrips() {
        // 每月第 2 个周三：nth=2, weekday=3
        val r = rec("monthlyW:2:3")
        val t = apply(base, r)
        assertEquals("monthlyW:2:3", t.repeatType)
        val back = Recurrence.parseTodoRule(t)
        assertTrue(back.byWeekday)
        assertEquals(2, back.nth)
        assertEquals(3, back.monthWeekday)
        // 2026-11 第 2 个周三是 11/11
        val anchor = base.date.toLocalDate()
        val occ = back.occurrences(anchor, anchor, anchor.plusDays(40))
        assertEquals(11, occ.first().dayOfMonth)
    }

    @Test
    fun untilDateStopsRepeatAndClearsPermanent() {
        val until = AppTime.dayKey(LocalDate.of(2026, 11, 20))
        val t = apply(base, rec("weekly:1", until))
        assertFalse("给了截止日就不该是永久", t.permanent)
        assertEquals(until, t.repeatEnd)
        val back = Recurrence.parseTodoRule(t)
        assertEquals(until, back.untilDayKey)
        val anchor = base.date.toLocalDate()
        val occ = back.occurrences(anchor, anchor, anchor.plusDays(60))
        // 只有 11/02、11/09、11/16 三个周一，之后的被 until 挡住
        assertEquals(listOf(2, 9, 16), occ.map { it.dayOfMonth })
    }

    @Test
    fun noneMeansNoRepeat() {
        val t = apply(base, Recurrence.NONE)
        assertEquals("none", t.repeatType)
        assertEquals("", t.repeatDays)
        assertFalse(Recurrence.parseTodoRule(t).repeats)
    }

    @Test
    fun yearlyAndMonthlyPlainRoundTrip() {
        listOf("yearly" to "yearly", "monthly" to "monthly", "daily" to "daily").forEach { (spec, expect) ->
            val t = apply(base, rec(spec))
            assertEquals("规则 $spec 落库类型不对", expect, t.repeatType)
            val back = Recurrence.parseTodoRule(t)
            assertTrue("规则 $spec 读回来变成不重复了", back.repeats)
        }
    }

    @Test
    fun weeklyWithoutExplicitDaysUsesAnchorWeekday() {
        // weekly（不带冒号日号）= 跟随锚点星期：锚点 2026-11-02 是周一
        val t = apply(base, rec("weekly"))
        assertEquals("weekly", t.repeatType)
        val back = Recurrence.parseTodoRule(t)
        val anchor = base.date.toLocalDate()
        val occ = back.occurrences(anchor, anchor, anchor.plusDays(14))
        assertTrue("没显式日号时要按锚点星期出", occ.isNotEmpty())
        occ.forEach { assertEquals(java.time.DayOfWeek.MONDAY, it.dayOfWeek) }
    }

    @Test
    fun describeCompactIsReadableForModel() {
        // 模型看到的/用户看到的同一套措辞；这里只锁「说出来不是空串、不是英文键名」
        val t = apply(base, rec("weekly:1,3"))
        val text = Recurrence.parseTodoRule(t).describeCompact(base.date.toLocalDate())
        assertTrue("重复描述不该空着：$text", text.isNotBlank())
        assertFalse("别把内部键名漏给用户：$text", text.contains("weekly"))
        assertTrue("周几要说清楚：$text", text.contains("周一"))
    }

    @Test
    fun scheduleRuleSyntaxMatchesTodoSyntax() {
        // 两端共用同一语法：日程侧解析器和待办侧参数串必须是同一份
        listOf("daily", "weekly:2,4", "biweekly:6", "monthly", "monthlyW:1:1", "yearly", "interval:3")
            .forEach { spec ->
                val a = Recurrence.parseScheduleRule(spec)
                assertTrue("日程侧解析不出 $spec", a.repeats)
                // 转回待办列再读回来，kind 必须一致（weekdays 只对 WEEKLY 有意义）
                val t = apply(base, a)
                val b = Recurrence.parseTodoRule(t)
                assertEquals("$spec 往返后规则类型变了", a.kind, b.kind)
                if (a.kind == Recurrence.Kind.WEEKLY) {
                    assertEquals("$spec 往返后星期集合变了", a.weekdays, b.weekdays)
                    assertEquals("$spec 往返后每两周标记变了", a.biweekly, b.biweekly)
                }
            }
    }

    @Test
    fun unknownSpecIsRejectedNotSilentlyIgnored() {
        // PlanTools.repeatOf 判据：解析出来不重复 = 看不懂的串（不能当成"建了个不重复的"
        assertFalse(Recurrence.parseScheduleRule("every-other-fortnight").repeats)
        assertNotNull(Recurrence.parseScheduleRule("daily"))
    }
}
