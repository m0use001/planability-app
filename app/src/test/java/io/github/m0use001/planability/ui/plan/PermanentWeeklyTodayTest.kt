package io.github.m0use001.planability.ui.plan

import io.github.m0use001.planability.data.Todo
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * 2026-11-13 用户「为什么今天出现了根本没设置在今天的待办」守卫。
 *
 * 真库实锤（只读快照）：常驻 weekly 待办 repeatDays=1,2,3,5（周一二三五，无周四），
 * 2026-11-12（周四）的「今天」筛选里却出现了——根因是 PlanScreen「今天」范围对
 * `permanent` **无条件放行**，从不看 weekly 相位。修：weekly+显式 weekdays 的常驻
 * 按 Recurrence 相位判命中（occurrences 统一引擎）。夹具字段取自真库两行：
 * id66 背单词（1,2,3,5）、id68 精听20分钟（1,2,3,5,6,7），date 已滚到 11/13（周五）。
 */
class PermanentWeeklyTodayTest {

    /** 真库形状：常驻 weekly，date=已滚到的下一期（2026-11-13 周五） */
    private fun t(days: String, date: Long) = Todo(
        id = 66, title = "四级：背单词60个", date = date,
        repeatType = "weekly", repeatDays = days, permanent = true
    )

    private val thu112 = LocalDate.of(2026, 11, 12)   // 周四
    private val fri113 = LocalDate.of(2026, 11, 13)   // 周五
    private val nextFri = LocalDate.of(2026, 11, 20)

    @Test
    fun `周一三五的常驻待办 周四不进今天`() {
        val t = t("1,2,3,5", 1794499200000L)   // 真库原值
        assertTrue(permanentWeeklyOffToday(t, thu112))
    }

    @Test
    fun `周一到周日的常驻待办 date 已滚到周五时 周四不进今天（滚期口径：下一期那天才出现）`() {
        // 锚点 11/13（周五，date 已滚）在 from 之前被 occurrences 跳过 → 周四不显示。
        // 与首页 loadDay 同口径：单行滚期模型里「出现日」=锚点那天。
        val t = t("1,2,3,4,5,6,7", 1794499200000L)
        assertTrue(permanentWeeklyOffToday(t, thu112))
    }

    @Test
    fun `date 已滚到下周五时 周四与本周五都不命中下下次前的那天 - 相位由锚点保`() {
        // date=11/13（周五）：周四（10 天内最近的周四）不命中 → 剔除
        val t = t("1,2,3,5", 1794499200000L)
        assertTrue(permanentWeeklyOffToday(t, thu112))
        // 到了周五那天：start=当天 ∈ {1,3,5} → 命中
        assertFalse(permanentWeeklyOffToday(t, fri113))
    }

    @Test
    fun `date 还在过去（漏勾）但今天命中星期 - 不剔除（欠账副本另有相位守卫）`() {
        // date=11/9（周一）漏勾至今：周五命中 1,3,5 → 常驻行仍进今天（欠账副本并显由
        // dayOverdueViews 负责，这里不重复剔）
        val t = t("1,2,3,5", java.time.LocalDate.of(2026, 11, 9).toEpochDay() * 86400000L)
        assertFalse(permanentWeeklyOffToday(t, fri113))
    }

    @Test
    fun `非 weekly 或无显式 weekdays 的常驻不收敛 - 保持旧行为`() {
        val daily = Todo(id = 78, title = "睡眠复盘", repeatType = "daily", permanent = true)
        assertFalse(permanentWeeklyOffToday(daily, thu112))
        val noDays = Todo(id = 1, title = "锚点星期型", repeatType = "weekly", repeatDays = "", permanent = true)
        assertFalse(permanentWeeklyOffToday(noDays, thu112))
        val weeklyNoDate = Todo(id = 2, title = "未排日期", repeatType = "weekly", repeatDays = "1,2,3,5", permanent = true)
        assertFalse(permanentWeeklyOffToday(weeklyNoDate, thu112))
    }

    @Test
    fun `每两周相位由锚点保 - 奇数周不命中`() {
        // 锚点 11/6（周五）每两周：11/13 是奇数周 → 不命中；11/20 偶数周 → 命中
        val t = Todo(
            id = 9, title = "双周报", date = LocalDate.of(2026, 11, 6).toEpochDay() * 86400000L,
            repeatType = "biweekly", repeatDays = "5", permanent = true
        )
        assertTrue(permanentWeeklyOffToday(t, fri113))
        assertFalse(permanentWeeklyOffToday(t, nextFri))
    }
}
