package io.github.m0use001.planability.ui.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 横向滚动条滑块几何（2026-11-18 真机闪退回归网）。
 *
 * 病灶：`coerceIn(保底 40dp, 视口×50%)` 在视口窄于 80dp 时是**空区间**，
 * Kotlin 的 coerceIn 直接抛 IllegalArgumentException（真机日志：
 * `maximum 101.5 is less than minimum 140.0`，3.5x 密度、视口 58dp），
 * 发生在 drawWithContent 里 = 一进含该元素的会话就闪退，且每秒重绘都崩。
 *
 * 本测试穷举窄视口边界：任何 w ≥ 0 都不得抛异常，且结果必须落在
 * [0, w×50%] 内（页面上滑块不许比封顶宽，也不许为负）。
 */
class HScrollHintGeometryTest {

    private val min40dpAt35x = 40f * 3.5f   // 140px，真机值

    @Test
    fun `窄视口不再抛空区间异常（闪退原样复现）`() {
        // 真机崩溃现场：w=203px（58dp），保底 140px > 封顶 101.5px
        val w = thumbWidthPx(203f, rangePx = 900, minPx = min40dpAt35x)
        assertEquals(203f * 0.5f, w, 0.01f)
    }

    @Test
    fun `任意视口宽度与滚动范围都不抛且不越界`() {
        val widths = listOf(0f, 0.5f, 1f, 10f, 79f, 80f, 81f, 203f, 400f, 1080f, 3000f)
        val ranges = listOf(1, 10, 100, 1011, 100_000)
        for (w in widths) for (r in ranges) {
            val thumb = thumbWidthPx(w, r, min40dpAt35x)
            assertTrue("w=$w r=$r thumb=$thumb 为负", thumb >= 0f)
            assertTrue("w=$w r=$r thumb=$thumb 超过封顶", thumb <= w * 0.5f + 0.01f)
        }
    }

    @Test
    fun `宽视口口径不变 保底40dp且封顶一半`() {
        // 内容远超视口：比例宽会小到 0 附近，被保底顶到 140px
        assertEquals(140f, thumbWidthPx(1080f, 100_000, min40dpAt35x), 0.01f)
        // 内容与视口等宽附近：比例宽 ~w/2，封顶正好一半
        assertEquals(540f, thumbWidthPx(1080f, 1, min40dpAt35x), 0.5f)
    }

    @Test
    fun `极小视口退化到零不作画`() {
        assertEquals(0f, thumbWidthPx(0f, 10, min40dpAt35x), 0f)
    }
}
