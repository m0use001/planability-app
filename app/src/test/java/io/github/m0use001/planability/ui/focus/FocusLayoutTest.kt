package io.github.m0use001.planability.ui.focus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 专注页宽屏摆位的边界（2026-11-19 首版；2026-11-20 v65 改两板）。
 *
 * 这些数字直接决定「大而空」有没有被填掉：
 * - 分档错了 → 大屏仍走单列，右半屏继续空；
 * - 左板实宽算错 → 环溢出/被裁；
 * - 环径的上下界顺序错了 → `coerceIn` 抛异常，首帧崩（HScrollHint 2026-11-18 同款）。
 *
 * v65：形态从三档（单/两/三栏）收敛成两档（单列 / 两板），
 * 与计划页/首页统一走 `SplitBoards` 的两块圆角板。
 */
class FocusLayoutTest {

    @Test
    fun `两板下界 900 边界值不许漂`() {
        // 手机 / 7~8 吋竖屏平板 / 小平板横屏：单列（原布局）
        for (w in intArrayOf(0, 1, 100, 393, 599, 600, 700, 899)) {
            assertFalse("内容宽 $w 不该走两板", focusIsTwoPane(w))
        }
        // 两板：内容宽 ≥ 900
        for (w in intArrayOf(900, 1000, 1199, 1200, 1500, 1920, 2400)) {
            assertTrue("内容宽 $w 该走两板", focusIsTwoPane(w))
        }
    }

    @Test
    fun `左板实宽按两板结构算 恒能放下 320dp 的环`() {
        // 两板结构：内容宽 − 板壳左右 padding(24) − 板间白缝(12) − 右板定宽(380)
        assertEquals(900 - 24 - 12 - 380, focusLeftPaneWidthDp(900))
        assertEquals(484, focusLeftPaneWidthDp(900))
        assertEquals(1184, focusLeftPaneWidthDp(1600))
        // 异常输入不得算出负宽（负宽进 coerceIn 会崩）
        assertEquals(0, focusLeftPaneWidthDp(0))
        assertEquals(0, focusLeftPaneWidthDp(-100))
        // Int.MIN_VALUE − 416 在 Int 里会整型溢出成巨大正数，coerceAtLeast 拦不住
        //（ProfileWideLayoutTest 首先抓到的同族 bug，这里钉住防复发）
        assertEquals(0, focusLeftPaneWidthDp(Int.MIN_VALUE))
        // 两板下界处左板必须放得下 320dp 的环
        assertTrue("两板下界处左板放不下最小环", focusLeftPaneWidthDp(900) >= 320)
    }

    @Test
    fun `环径在真机与多种平板尺寸上的取值`() {
        // 手机：与改版前一致
        assertEquals(280, focusRingSizeDp(353, 781, wide = false))
        // 1600×1000 窗口（内容 1520 → 左板 1104）：宽侧 640 / 高侧 472 → 472
        assertEquals(472, focusRingSizeDp(1104, 908, wide = true))
        // 1280×800 窗口（内容 1200 → 左板 784）：宽侧 454 / 高侧 368 → 368
        assertEquals(368, focusRingSizeDp(784, 708, wide = true))
        // 1000×560 矮宽窗口（内容 920 → 左板 504）：宽侧 292 / 高侧 243 → 243
        assertEquals(243, focusRingSizeDp(504, 468, wide = true))
        // 首帧未测量：退回手机口径而不是进 coerceIn
        assertEquals(280, focusRingSizeDp(0, 0, wide = true))
        assertEquals(280, focusRingSizeDp(-1, 500, wide = true))
    }

    @Test
    fun `穷举宽高组合 环永不溢栏也不越界`() {
        for (w in 0..2600 step 10) {
            if (!focusIsTwoPane(w)) continue
            val left = focusLeftPaneWidthDp(w)
            for (h in 0..1600 step 20) {
                val ring = focusRingSizeDp(left, h, wide = true)
                if (h <= 0) {
                    assertEquals("异常尺寸应退回手机口径", 280, ring)
                    continue
                }
                assertTrue("w=$w h=$h 环径 $ring 越上界", ring <= 480)
                assertTrue("w=$w h=$h 环径 $ring 越下界", ring >= 240)
                if (h < 700) {
                    assertTrue("矮窗口（h=$h）环径 $ring 应 ≤320", ring <= 320)
                } else {
                    assertTrue("w=$w h=$h 环径 $ring 应 ≥320", ring >= 320)
                }
                assertTrue(
                    "w=$w h=$h 环 $ring 溢出了左板 $left",
                    ring <= left
                )
            }
        }
    }

    @Test
    fun `舞台内容列限宽 760 与计划页左板同尺度`() {
        // 计时环不该在大板中央孤零零飘着——内容列限宽居中。
        assertEquals(760, FOCUS_STAGE_MAX_WIDTH_DP)
    }
}
