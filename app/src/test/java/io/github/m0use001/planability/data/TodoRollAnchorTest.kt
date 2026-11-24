package io.github.m0use001.planability.data

import io.github.m0use001.planability.core.AppTime
import io.github.m0use001.planability.core.toLocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

/**
 * 滚期锚点闸（2026-11-04「背单词今天看不到」）。
 *
 * 单行滚期模型里，行（todo.date）代表**下一份待办**：勾选后它就前进到下一期。
 * 于是「再勾一次」时拿行日期当锚点等于从下一期再往后数一期——补昨天的账会被推到后天，
 * 直接滚进未来；而未来日期在当日视图的两个查询（byDate / overdueRepeating）里都不命中，
 * 待办对首页彻底隐身且自己滚不回来。真机取证：背单词 date 被推到 11-06（今天 11-04）。
 *
 * 不变量：锚点（相位参照）无论怎么脏，都不得让 target 越过「被完成那天之后的第一期」。
 */
class TodoRollAnchorTest {

    // 2026-11-02 是周一——weekly/biweekly 用绝对星期构造，读起来最直观
    private val monday = LocalDate.of(2026, 11, 2)
    private val today = LocalDate.of(2026, 11, 4) // 周三
    private val yesterday = today.minusDays(1)

    private fun todo(
        date: LocalDate,
        repeatType: String = "daily",
        repeatDays: String = "",
        repeatInterval: Int = 0,
        repeatEnd: Long = AppTime.dayKey(today) + 30L * 86_400_000L,
        permanent: Boolean = false
    ) = Todo(
        id = 34L,
        title = "四级：背单词",
        date = AppTime.dayKey(date),
        repeatType = repeatType,
        repeatDays = repeatDays,
        repeatInterval = repeatInterval,
        repeatEnd = repeatEnd,
        permanent = permanent
    )

    private val noExcluded = emptySet<Long>()

    private fun roll(t: Todo, completedOn: LocalDate, excluded: Set<Long> = noExcluded): LocalDate? =
        nextRollTarget(Recurrence.parseTodoRule(t), t.date.toLocalDate(), completedOn, today, excluded)

    @Test
    fun `行已被滚进未来_补昨天的账_回今天而不是再跳一期`() {
        // 真机原始状态：行 date=11-06（被旧 bug 推的），用户切到 11-03 打卡
        val t = todo(date = LocalDate.of(2026, 11, 6))
        assertEquals(today, roll(t, yesterday))
    }

    @Test
    fun `行已被滚进未来_完成今天的实例_落在明天`() {
        val t = todo(date = LocalDate.of(2026, 11, 6))
        assertEquals(today.plusDays(1), roll(t, today))
    }

    @Test
    fun `正常行_完成今天_明天_原语义不变`() {
        val t = todo(date = today)
        assertEquals(today.plusDays(1), roll(t, today))
    }

    @Test
    fun `补前天的账_折叠到今天`() {
        val t = todo(date = today.minusDays(2))
        assertEquals(today, roll(t, today.minusDays(2)))
    }

    @Test
    fun `每周_行已到下周_完成本周一_仍落下周一_相位不被夹乱`() {
        val t = todo(date = monday.plusDays(7), repeatType = "weekly", repeatDays = "1")
        assertEquals(monday.plusDays(7), roll(t, monday))
    }

    @Test
    fun `隔周_完成本周一_落两周后`() {
        val t = todo(date = monday.plusDays(12), repeatType = "biweekly", repeatDays = "1")
        // 行日期（monday+12，周日）在完成日之后 → 锚点按完成日对齐；biweekly 相位=周一
        assertEquals(monday.plusDays(14), roll(t, monday))
    }

    @Test
    fun `今天被例外掉_顺延到明天`() {
        val t = todo(date = LocalDate.of(2026, 11, 6))
        val excluded = setOf(AppTime.dayKey(today))
        val target = roll(t, yesterday, excluded)
        assertEquals(today.plusDays(1), target)
    }

    @Test
    fun `系列已过 repeatEnd_返回 null`() {
        val ended = todo(date = today, repeatEnd = AppTime.dayKey(today) - 86_400_000L)
        assertNull(roll(ended, today))
    }

    // ---------------- 自愈判据（repairRolledPast 的纯逻辑同构） ----------------

    /**
     * 自愈只处理每日重复，目标日可判定：今天有完成流水（lastDone ≥ today）→ 明天，
     * 否则 → 今天。这里用纯函数形式把判据钉住，避免以后又退化成"看行日期算"。
     */
    private fun repairTarget(date: LocalDate, lastDoneUpToToday: LocalDate): LocalDate =
        if (AppTime.dayKey(lastDoneUpToToday) >= AppTime.dayKey(today)) today.plusDays(1) else today

    @Test
    fun `自愈_今天没完成_未来行拉回今天`() {
        assertEquals(today, repairTarget(LocalDate.of(2026, 11, 6), yesterday))
    }

    @Test
    fun `自愈_今天已完成_未来行拉回明天`() {
        assertEquals(today.plusDays(1), repairTarget(LocalDate.of(2026, 11, 9), today))
    }
}
