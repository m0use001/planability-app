package io.github.m0use001.planability.shared

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * v55 中枢批量能力：草稿筛选谓词（M2 从 app 侧 PendingFilterTest 迁入，断言逐条不变）。
 *
 * 背景（真机数据）：1360 条待处理的 createdAt 全在 11 月（信息提取批量产生），
 * 所以「某月」只能按 timeText 里的事件日期判；无时间描述的 540 条一律不算命中某月。
 */
class PendingFilterTest {

    @Test
    fun `月份归一_三种写法都认`() {
        assertEquals("10", PendingFilter.normMonth("10"))
        assertEquals("10", PendingFilter.normMonth("10月"))
        assertEquals("10", PendingFilter.normMonth("2026-10"))
        assertEquals("09", PendingFilter.normMonth("9"))
        assertEquals("09", PendingFilter.normMonth(" 9月 "))
        assertNull(PendingFilter.normMonth(""), "空串不认")
        assertNull(PendingFilter.normMonth("十月"), "非月份不认")
        assertNull(PendingFilter.normMonth("13月x"), "越界月份不认")
    }

    @Test
    fun `时间描述按月命中_覆盖真机写法`() {
        val oct = "10"
        assertTrue(PendingFilter.timeTextInMonth("10月24日（周六）", oct))
        assertTrue(PendingFilter.timeTextInMonth("2026年10月10日 17:00前", oct))
        assertTrue(PendingFilter.timeTextInMonth("10月21日 21:00", oct))
        assertTrue(PendingFilter.timeTextInMonth("10/24", oct))
        assertTrue(PendingFilter.timeTextInMonth("2026-10-11", oct))
        assertTrue(PendingFilter.timeTextInMonth("2026年10月5日", oct), "2026年10月 里的 10月")
    }

    @Test
    fun `时间描述按月_不误伤`() {
        val oct = "10"
        assertFalse(PendingFilter.timeTextInMonth("9月28日", oct), "9 月不算 10 月")
        assertFalse(PendingFilter.timeTextInMonth("11月10日", oct), "11 月不算 10 月")
        assertFalse(PendingFilter.timeTextInMonth("", oct), "无时间不算命中某月")
        assertFalse(PendingFilter.timeTextInMonth("第1/10组", oct), "单号 1/10 不误判为 10 月")
        assertFalse(PendingFilter.timeTextInMonth("110月", oct), "110月不误判")
    }

    @Test
    fun `九月在真机数据里就是零命中`() {
        assertTrue(PendingFilter.timeTextInMonth("9月28日", PendingFilter.normMonth("9")!!))
        assertFalse(PendingFilter.timeTextInMonth("10月28日", PendingFilter.normMonth("9")!!))
    }

    @Test
    fun `has_time三态`() {
        assertEquals(true, PendingFilter.normTriState("true"))
        assertEquals(false, PendingFilter.normTriState("FALSE"))
        assertNull(PendingFilter.normTriState(""))
        assertNull(PendingFilter.normTriState("随便"))
    }

    @Test
    fun `首个日期解析_覆盖真机写法`() {
        val y = 2026
        assertEquals(java.time.LocalDate.of(2026, 10, 10), PendingFilter.firstDateInText("2026年10月10日 17:00", y))
        assertEquals(java.time.LocalDate.of(2026, 10, 24), PendingFilter.firstDateInText("10月24日（周六）", y))
        assertEquals(java.time.LocalDate.of(2026, 11, 8), PendingFilter.firstDateInText("11/8", y))
        assertEquals(java.time.LocalDate.of(2026, 11, 9), PendingFilter.firstDateInText("11月9日 9:00-17:00", y))
        assertEquals(java.time.LocalDate.of(2026, 10, 16), PendingFilter.firstDateInText("10月16日截止", y))
        // 上/中/下旬 → 5/15/25
        assertEquals(java.time.LocalDate.of(2026, 11, 5), PendingFilter.firstDateInText("11月上旬", y))
        assertEquals(java.time.LocalDate.of(2026, 11, 25), PendingFilter.firstDateInText("11月下旬", y))
        // 仅月份 → 该月 1 号
        assertEquals(java.time.LocalDate.of(2026, 11, 1), PendingFilter.firstDateInText("11月起", y))
        // 解析不到 → null
        assertNull(PendingFilter.firstDateInText("尽快", y))
        assertNull(PendingFilter.firstDateInText("测评后", y))
        assertNull(PendingFilter.firstDateInText("", y))
    }

    @Test
    fun `边界日解析`() {
        val today = java.time.LocalDate.of(2026, 11, 22)
        assertEquals(java.time.LocalDate.of(2026, 11, 15), PendingFilter.parseDay("2026-11-15", today))
        assertEquals(java.time.LocalDate.of(2026, 11, 15), PendingFilter.parseDay("11-15", today))
        assertEquals(java.time.LocalDate.of(2026, 11, 15), PendingFilter.parseDay("11月15日", today))
        assertEquals(today, PendingFilter.parseDay("今天", today))
        assertEquals(java.time.LocalDate.of(2026, 11, 21), PendingFilter.parseDay("昨天", today))
        assertNull(PendingFilter.parseDay("", today))
        assertNull(PendingFilter.parseDay("胡说", today))
    }

    @Test
    fun `日期区间过滤语义_早于某日只清过期`() {
        val y = 2026
        val cut = java.time.LocalDate.of(2026, 11, 15)
        val early = PendingFilter.firstDateInText("11月3日", y)!!
        val late = PendingFilter.firstDateInText("11月20日", y)!!
        assertTrue(early.isBefore(cut))
        assertFalse(late.isBefore(cut))
    }
}
