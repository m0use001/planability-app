package io.github.m0use001.planability.ui.focus

import io.github.m0use001.planability.data.DayBundle
import io.github.m0use001.planability.data.Schedule
import io.github.m0use001.planability.data.ScheduleOcc
import io.github.m0use001.planability.data.Todo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * 专注页「接下来要做」的挑条规则（2026-11-19 平板「大而空」批）。
 *
 * 这里最容易出的错是「把已结束的日程又摆出来」和「逾期的被排到最后」——
 * 前者是谎报接下来要做什么，后者会让用户早上看到的是下午的活。
 */
class FocusNextUpTest {

    private val zone: ZoneId = ZoneId.systemDefault()
    private fun at(h: Int, m: Int = 0): Long =
        LocalDateTime.of(2026, 11, 19, h, m).atZone(zone).toInstant().toEpochMilli()

    private fun bundle(todos: List<Todo> = emptyList(), schedules: List<ScheduleOcc> = emptyList()) =
        DayBundle(
            date = LocalDate.of(2026, 11, 19),
            schedules = schedules,
            todos = todos,
            hourTasks = emptyList(),
            events = emptyList(),
            checks = emptyList(),
            doneCount = 0
        )

    private fun occ(id: Long, title: String, startH: Int, endH: Int): ScheduleOcc {
        val start = at(startH)
        val end = at(endH)
        return ScheduleOcc(
            schedule = Schedule(id = id, title = title, start = start, end = end),
            start = start,
            end = end
        )
    }

    @Test
    fun `逾期待办在最先 无时刻待办殿后`() {
        val now = at(10)
        val items = nextUpItems(
            bundle(
                todos = listOf(
                    Todo(id = 1, title = "无时刻的", due = 0, urgency = 4, importance = 4),
                    Todo(id = 2, title = "下午的", due = at(15)),
                    Todo(id = 3, title = "早上就逾期", due = at(9))
                )
            ),
            nowMillis = now
        )
        assertEquals(listOf("早上就逾期", "下午的", "无时刻的"), items.map { it.title })
    }

    @Test
    fun `已结束的日程不再出现 进行中的算接下来`() {
        val now = at(10)
        val items = nextUpItems(
            bundle(
                schedules = listOf(
                    occ(11, "早八已结束", 8, 9),
                    occ(12, "正在进行的组会", 9, 11),
                    occ(13, "下午课", 14, 15)
                )
            ),
            nowMillis = now
        )
        assertTrue("已结束的日程被摆出来了：${items.map { it.title }}", items.none { it.title == "早八已结束" })
        assertEquals(listOf("正在进行的组会", "下午课"), items.map { it.title })
    }

    @Test
    fun `待办与日程按时刻合并 且只取 limit 条`() {
        val now = at(10)
        val items = nextUpItems(
            bundle(
                todos = listOf(
                    Todo(id = 1, title = "14 点的作业", due = at(14)),
                    Todo(id = 2, title = "16 点的复习", due = at(16))
                ),
                schedules = listOf(occ(11, "11 点的实验", 11, 12))
            ),
            nowMillis = now,
            limit = 2
        )
        assertEquals(listOf("11 点的实验", "14 点的作业"), items.map { it.title })
    }

    @Test
    fun `徽标口径 时刻 百分比 待办`() {
        val items = nextUpItems(
            bundle(
                todos = listOf(
                    Todo(id = 1, title = "有时刻", due = at(13, 30)),
                    Todo(id = 2, title = "百分比", due = 0, percentDone = 40),
                    Todo(id = 3, title = "普通", due = 0)
                )
            ),
            nowMillis = at(10)
        )
        val byTitle = items.associateBy { it.title }
        assertTrue("有时刻的徽标不是时刻：${byTitle["有时刻"]?.badge}", byTitle.getValue("有时刻").badge.contains(":"))
        assertEquals("40%", byTitle.getValue("百分比").badge)
        assertEquals("待办", byTitle.getValue("普通").badge)
        assertTrue("日程/待办的深链口径不能混", items.none { it.isSchedule })
    }

    @Test
    fun `已完成的待办不出现`() {
        val items = nextUpItems(
            bundle(todos = listOf(Todo(id = 1, title = "做完了", done = true, due = at(9)))),
            nowMillis = at(10)
        )
        assertTrue("已完成的待办被摆出来了", items.isEmpty())
    }
}
