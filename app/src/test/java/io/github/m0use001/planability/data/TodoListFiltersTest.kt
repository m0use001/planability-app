package io.github.m0use001.planability.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v53：计划页「进行中」筛选的判定（用户「循环的背单词待办，今天看不到」）。
 *
 * 钉的口径：
 * - 未完成 → 进行中（任何范围）；
 * - 一次性待办完成 → 归档（原行为不变）；
 * - **循环待办今天的副本完成 → 仍在「今天」的进行中里**（勾了还是今天的事，
 *   能回看、能撤销）；但换到近 7 天/月/全部范围就不再放宽，避免堆历史。
 */
class TodoListFiltersTest {

    private val todayKey = 20_000L

    private fun todo(
        done: Boolean,
        repeatType: String = "none",
        date: Long = todayKey
    ) = Todo(title = "t", date = date, repeatType = repeatType, done = done)

    @Test
    fun pendingIsAlwaysActive() {
        assertTrue(TodoListFilters.inActiveList(todo(false), todayKey, "today"))
        assertTrue(TodoListFilters.inActiveList(todo(false, "daily"), todayKey, "week"))
        assertTrue(TodoListFilters.inActiveList(todo(false, "none"), todayKey, "all"))
    }

    @Test
    fun doneOneOffIsArchived() {
        assertFalse(TodoListFilters.inActiveList(todo(true), todayKey, "today"))
        assertFalse(TodoListFilters.inActiveList(todo(true), todayKey, "all"))
    }

    @Test
    fun doneRepeatingTodayStaysInTodayList() {
        assertTrue(TodoListFilters.inActiveList(todo(true, "daily"), todayKey, "today"))
        // 周/月/全部范围不放宽
        assertFalse(TodoListFilters.inActiveList(todo(true, "daily"), todayKey, "week"))
        assertFalse(TodoListFilters.inActiveList(todo(true, "daily"), todayKey, "month"))
        assertFalse(TodoListFilters.inActiveList(todo(true, "daily"), todayKey, "all"))
    }

    @Test
    fun doneRepeatingOnOtherDayIsArchived() {
        assertFalse(TodoListFilters.inActiveList(todo(true, "daily", todayKey - 1), todayKey, "today"))
    }

    @Test
    fun donePermanentTodoIsStillArchived() {
        // 常驻（无日期）不是循环，勾完就归档
        assertFalse(TodoListFilters.inActiveList(todo(true, "none", 0L), todayKey, "today"))
    }
}
