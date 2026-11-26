package io.github.m0use001.planability.ui.plan

import io.github.m0use001.planability.data.Todo
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「今天」视图欠账副本判据（2026-11-24）。
 *
 * 回归门：常驻(permanent)重复待办**不能**再补 date=今天的副本 —— 它的主行本就通过
 * inRange 的 `t.permanent && !offToday` 显示，再补副本就是同一条两行
 * （用户截图「四级：背单词70个（工作日）」双显）。见 [needsTodayOverdueCopy]。
 */
class PlanTodayOverdueCopyTest {

    // dayKey 是「当天零点」的 epoch 毫秒（不是 20260101 这种整数），必须用真实毫秒量级
    private val today = 1_767_225_600_000L // 2026-01-01 00:00 UTC 附近的 dayKey 量级
    private val day = 86_400_000L

    private fun todo(
        date: Long,
        done: Boolean = false,
        repeatType: String = "weekly",
        permanent: Boolean = false,
    ) = Todo(title = "t", date = date, done = done, repeatType = repeatType, permanent = permanent)

    @Test
    fun `常驻重复待办日期已过未完成_不补副本_这是双显修复`() {
        // 这正是截图里「四级：背单词70个（工作日）」：常驻 weekly、date 在过去、今天未完成
        assertFalse(needsTodayOverdueCopy(todo(date = today - day, permanent = true), today))
    }

    @Test
    fun `非永久重复待办日期已过未完成_补副本`() {
        assertTrue(needsTodayOverdueCopy(todo(date = today - day, permanent = false), today))
    }

    @Test
    fun `日期是今天_不补副本`() {
        assertFalse(needsTodayOverdueCopy(todo(date = today), today))
    }

    @Test
    fun `日期在未来_不补副本`() {
        assertFalse(needsTodayOverdueCopy(todo(date = today + day), today))
    }

    @Test
    fun `已完成_不补副本`() {
        assertFalse(needsTodayOverdueCopy(todo(date = today - day, done = true), today))
    }

    @Test
    fun `不重复_不补副本`() {
        assertFalse(needsTodayOverdueCopy(todo(date = today - day, repeatType = "none"), today))
    }

    @Test
    fun `无日期_不补副本`() {
        assertFalse(needsTodayOverdueCopy(todo(date = 0), today))
    }
}
