package io.github.m0use001.planability.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

/**
 * 「漏勾即消失」补口的纯函数闸（2026-11-03）：过期的重复待办在今日视图的显示副本。
 * 用户实测「四级背单词设了每天，漏一天后首页再也不显示」——date 只在勾选时前进。
 */
class OverdueDayViewTest {

    // dayKey 与 AppTime.dayKey 同构：那天 00:00（宿主时区）的 epoch ms。
    // 2026-11-03 是周二——weekly 用例取「今天的星期」动态构造，不依赖绝对星期。
    private val day = LocalDate.of(2026, 11, 3)
    private val dayKey = day.atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
    private val yesterdayKey = dayKey - 86_400_000L
    private val yesterday = day.minusDays(1)

    private fun todo(
        id: Long = 34L,
        title: String = "四级：背单词",
        date: Long = yesterdayKey,
        repeatType: String = "daily",
        repeatDays: String = "",
        repeatInterval: Int = 0,
        repeatEnd: Long = dayKey + 30 * 86_400_000L,
        permanent: Boolean = false,
        due: Long = 0L,
        endAt: Long = 0L,
        done: Boolean = false,
        trashed: Boolean = false
    ) = Todo(
        id = id, title = title, date = date, repeatType = repeatType, repeatDays = repeatDays,
        repeatInterval = repeatInterval, repeatEnd = repeatEnd, permanent = permanent,
        due = due, endAt = endAt, done = done, trashed = trashed
    )

    private val present = emptySet<Long>()
    private val noExcluded = emptySet<Long>()

    @Test
    fun `每天重复过期未勾_今天命中_生成今天的显示副本`() {
        val t = todo() // 昨天锚点 daily，今天在期内
        val v = overdueDayView(t, day, dayKey, present, noExcluded)
        assertNotNull(v)
        assertEquals(dayKey, v!!.date)
        assertEquals(false, v.done)
        assertEquals(t.id, v.id) // id 保留：勾这个副本走 completeTodo 正常滚期
    }

    @Test
    fun `每周重复_今天不在星期集合则不显示`() {
        val weekdayOfToday = day.dayOfWeek.value // 2026-11-03 是周二=2
        val hit = todo(repeatType = "weekly", repeatDays = weekdayOfToday.toString())
        val miss = todo(id = 35L, repeatType = "weekly", repeatDays = ((weekdayOfToday % 7) + 1).toString())
        assertNotNull(overdueDayView(hit, day, dayKey, present, noExcluded))
        assertNull(overdueDayView(miss, day, dayKey, present, noExcluded))
    }

    @Test
    fun `隔周相位不命中则不显示`() {
        // 锚点 yesterday=周一？不确定锚点星期——用锚点相对相位验证：
        // biweekly 规则下，锚点当天命中，下一周期命中，中间一周不命中。
        val anchorDay = yesterday
        val anchorKey = yesterdayKey
        val rec = Recurrence.parseTodoRule(
            todo(date = anchorKey, repeatType = "biweekly", repeatDays = anchorDay.dayOfWeek.value.toString())
        )
        val inPhase = anchorDay.plusDays(14) // 两周后同星期：命中
        val offPhase = anchorDay.plusDays(7) // 一周后同星期：不命中（biweekly）
        assertEquals(1, rec.occurrences(anchorDay, inPhase, inPhase).size)
        assertEquals(0, rec.occurrences(anchorDay, offPhase, offPhase).size)
    }

    @Test
    fun `已在当日列表_今天被例外_非重复_已勾_已过期截停_都不再出副本`() {
        val t = todo()
        assertNull(overdueDayView(t, day, dayKey, presentIds = setOf(t.id), excludedDates = noExcluded))
        assertNull(overdueDayView(t, day, dayKey, present, excludedDates = setOf(dayKey)))
        assertNull(overdueDayView(todo(repeatType = "none"), day, dayKey, present, noExcluded))
        assertNull(overdueDayView(todo(done = true), day, dayKey, present, noExcluded))
        assertNull(overdueDayView(todo(repeatEnd = dayKey - 1), day, dayKey, present, noExcluded)) // 截止日已过
        assertNotNull(overdueDayView(todo(repeatEnd = dayKey), day, dayKey, present, noExcluded)) // 截止日=今天仍在期内
        assertNull(overdueDayView(todo(date = dayKey), day, dayKey, present, noExcluded)) // date=今天不归这补口管
    }

    @Test
    fun `带时刻的due_endAt挪到今天同时刻_无时刻保持空`() {
        val due = yesterdayKey + 9 * 3600_000L + 30 * 60_000L // 昨天 09:30
        val endAt = due + 45 * 60_000L
        val v = overdueDayView(todo(due = due, endAt = endAt), day, dayKey, present, noExcluded)!!
        // 副本 due 的时刻=09:30、endAt=10:15，但日期=今天（相对 yesterdayKey 恰差一天）
        assertEquals(due + 86_400_000L, v.due)
        assertEquals(endAt + 86_400_000L, v.endAt)
        // 无时刻版本
        val v2 = overdueDayView(todo(id = 35L), day, dayKey, present, noExcluded)!!
        assertEquals(0L, v2.due)
        assertEquals(0L, v2.endAt)
    }

    // ================== 滚期目标（2026-11-04「点一下出现一模一样的待办」） ==================

    private val daily = Recurrence.parseTodoRule(todo())

    @Test
    fun `补昨天的账_滚期落今天_今天的实例不被吞`() {
        // 完成日=昨天（欠账副本），今天=11-04：目标必须是今天而不是明天
        val target = nextRollTarget(daily, yesterday, yesterday, day, noExcluded)
        assertEquals(day, target)
    }

    @Test
    fun `完成今天的实例_滚到明天_原语义不变`() {
        val target = nextRollTarget(daily, day, day, day, noExcluded)
        assertEquals(day.plusDays(1), target)
    }

    @Test
    fun `补三天前的账_折叠到今天`() {
        val target = nextRollTarget(daily, day.minusDays(3), day.minusDays(3), day, noExcluded)
        assertEquals(day, target)
    }

    @Test
    fun `今天被例外掉_顺延到明天`() {
        val target = nextRollTarget(daily, yesterday, yesterday, day, excludedDayKeys = setOf(dayKey))
        assertEquals(day.plusDays(1), target)
    }

    @Test
    fun `超期截止_滚期返回null并标记完成`() {
        val ended = Recurrence.parseTodoRule(todo(repeatEnd = dayKey - 1))
        val target = nextRollTarget(ended, day.minusDays(3), day.minusDays(3), day, noExcluded)
        assertEquals(null, target)
    }
}

/**
 * 「背单词无法删除」回归钉版（2026-11-07 真机取证）。
 *
 * 删除只给行打 trashed=1、完成流水仍在；三个还原点（loadDay / todosOfDay /
 * doneOccurrences）原来不看 trashed → 已删的待办被流水还原成显示副本，
 * 用户看到它「删了又回来」，再长按删的还是同一条已删行 = 纯空操作。
 * 钉死：trashed 的行任何情况下都不还原。
 */
class CompletedDayViewTrashTest {

    private val dayKey = LocalDate.of(2026, 11, 3)
        .atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()

    private fun log(todoId: Long = 34L) =
        TodoDoneLog(todoId = todoId, title = "四级：背单词", date = dayKey, at = dayKey + 9 * 3600_000L)

    @Test
    fun `未删除的行照常还原成已勾选副本`() {
        val live = Todo(id = 34L, title = "四级：背单词", date = dayKey, repeatType = "daily")
        val v = completedDayView(live, log())
        assertNotNull(v)
        assertEquals(true, v!!.done)
        assertEquals(dayKey, v.date)
    }

    @Test
    fun `已删除的行不还原_这是无法删除的根因`() {
        val trashed = Todo(
            id = 34L, title = "四级：背单词", date = dayKey,
            repeatType = "daily", trashed = true, trashedAt = dayKey
        )
        assertNull(completedDayView(trashed, log()))
    }
}
