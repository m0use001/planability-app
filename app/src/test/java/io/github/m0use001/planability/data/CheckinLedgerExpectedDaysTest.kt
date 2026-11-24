package io.github.m0use001.planability.data

import io.github.m0use001.planability.core.AppTime
import io.github.m0use001.planability.core.toLocalDate
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [CheckinLedger.expectedDays]：非逐日重复待办的「应完成日」反推（2026-10-29 加）。
 *
 * 这是「过期 N」对 weekly/interval 待办从恒为 0 变成真正生效的入口：
 * 滚期后的锚点在**未来**，必须靠 [Recurrence.occurrencesSince] 相位对齐往回补。
 * 起点不能被造到待办存在之前，也不能无限往前（90 天封顶）。
 */
class CheckinLedgerExpectedDaysTest {

    /** 用户实测的待办 22：每周三/四、10-29 创建并打卡、勾完滚到 11-04 */
    private fun weeklyTodo(
        date: LocalDate,
        created: LocalDate?,
        repeatEnd: LocalDate? = null
    ) = Todo(
        id = 22,
        title = "慢跑20分钟",
        date = AppTime.dayKey(date),
        repeatType = "weekly",
        repeatDays = "3,4",
        repeatEnd = repeatEnd?.let { AppTime.dayKey(it) } ?: 0,
        supervisionEnabled = true,
        createdAt = created?.let { AppTime.dayKey(it) } ?: 0
    )

    @Test
    fun `刚创建的待办只反推出今天（不往前造欠账）`() {
        val today = LocalDate.of(2026, 10, 29)          // 周四
        val todo = weeklyTodo(date = LocalDate.of(2026, 11, 4), created = today)
        val checks = listOf(SupervisionCheck(todoId = 22, date = AppTime.dayKey(today), at = AppTime.dayKey(today)))
        val days = CheckinLedger.expectedDays(todo, checks, today)
        assertEquals(setOf(AppTime.dayKey(today)), days)
    }

    @Test
    fun `创建已 4 周：中间漏掉的周三周四都算应完成日`() {
        val today = LocalDate.of(2026, 10, 29)
        val todo = weeklyTodo(date = LocalDate.of(2026, 11, 4), created = LocalDate.of(2026, 10, 1))
        val days = CheckinLedger.expectedDays(todo, emptyList(), today)
        val expect = listOf(1, 7, 8, 14, 15, 21, 22, 28, 29).map { AppTime.dayKey(LocalDate.of(2026, 10, it)) }
        assertEquals(expect.toSet(), days)
    }

    @Test
    fun `重复截止日之后不再算应完成`() {
        val today = LocalDate.of(2026, 10, 29)
        val todo = weeklyTodo(
            date = LocalDate.of(2026, 11, 4),
            created = LocalDate.of(2026, 10, 1),
            repeatEnd = LocalDate.of(2026, 10, 10)
        )
        val days = CheckinLedger.expectedDays(todo, emptyList(), today)
        val expect = listOf(1, 7, 8).map { AppTime.dayKey(LocalDate.of(2026, 10, it)) }
        assertEquals(expect.toSet(), days)
    }

    @Test
    fun `回溯封顶 90 天（老待办不会造出几百天欠账）`() {
        val today = LocalDate.of(2026, 10, 29)
        val todo = weeklyTodo(date = LocalDate.of(2026, 11, 4), created = LocalDate.of(2020, 1, 1))
        val days = CheckinLedger.expectedDays(todo, emptyList(), today)
        val floor = AppTime.dayKey(today.minusDays(90))
        assertTrue("应有应完成日", days.isNotEmpty())
        assertTrue("早于 90 天前的不该出现", days.all { it >= floor })
        assertTrue("都是周三或周四", days.all {
            it.toLocalDate().dayOfWeek.value in setOf(3, 4)
        })
    }

    @Test
    fun `不重复的待办不产生应完成日`() {
        val today = LocalDate.of(2026, 10, 29)
        val todo = Todo(id = 1, title = "一次性", date = AppTime.dayKey(today), createdAt = AppTime.dayKey(today))
        assertTrue(CheckinLedger.expectedDays(todo, emptyList(), today).isEmpty())
    }
}
