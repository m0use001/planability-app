package io.github.m0use001.planability.ui.home

import io.github.m0use001.planability.data.Todo
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 2026-11-02 用户：「允许待办不设置时间（但日期要设置），无时间待办/日程就在首页
 * 底部栏上方显示一个胶囊，写着「未设置时间计划：」然后显示到底几个」。
 *
 * 无时间待办（due=0）在时间轴排不了位（TimelineGrid 只画 due>0），以前建了首页看不见。
 * 胶囊计数只数**今天列表里、未完成**的：已完成的不需要再提醒去补时间。
 */
class UntimedPlanCountTest {

    private fun todo(due: Long, done: Boolean, id: Long = 1L) = Todo(
        id = id, title = "t", date = 1793548800000L, due = due,
        priority = 1, done = done
    )

    @Test
    fun `只数未完成且无时间的待办`() {
        val list = listOf(
            todo(due = 0L, done = false, id = 1),
            todo(due = 0L, done = false, id = 2),
            todo(due = 0L, done = true, id = 3),
            todo(due = 1793619000000L, done = false, id = 4)
        )
        assertEquals(2, untimedPendingCount(list))
        assertEquals(0, untimedPendingCount(emptyList()))
    }
}
