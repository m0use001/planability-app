package io.github.m0use001.planability.ui.profile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「我的」页宽屏摆位的纯函数测试（2026-11-19 首版；2026-11-20 v65 改两板）。
 *
 * 这些数字决定 3200×2000 上「我的」是**两块 24dp 圆角板 + 12dp 白缝**，
 * 还是退回「一列 840dp 居中、两侧各空 340dp」的旧形态 —— 边界值在 Compose 里
 * 没法被断言，所以提纯成 [profileIsTwoPane] / [profileLeftPaneWidthDp] 之后在这里穷举。
 *
 * 口径（见 [ProfileWideLayout]）：
 * - 两板下界 = 820dp 内容宽（内容宽 − 板壳 24 − 白缝 12 − 右板 380 = 左板 ≥ 380）；
 * - **内容宽 = 窗宽 − 80dp Rail**（1600dp 窗口 → 1520dp → 两板）；
 * - 一板时整列限宽仍是 840dp，与改版前 `adaptiveColumn()` 默认值完全一致。
 */
class ProfileWideLayoutTest {

    @Test
    fun `手机与小平板恒单板 旧口径不变`() {
        // 手机内容宽 328~430dp、7~8 吋竖屏平板 ~533dp：都远低于两板下界 820
        for (w in intArrayOf(0, 1, 100, 328, 392, 430, 533, 700, 819)) {
            assertFalse("内容宽 $w 不该走两板", profileIsTwoPane(w))
        }
        // 单板的整列限宽必须仍是 840 —— 改宽了手机以外的窄窗会跟着变
        assertEquals(840, PROFILE_ONE_COLUMN_MAX_WIDTH_DP)
    }

    @Test
    fun `820 起两板 边界值不许漂`() {
        assertFalse("819dp 还没到两板下界", profileIsTwoPane(819))
        assertTrue("820dp 恰好进两板", profileIsTwoPane(820))
        assertTrue(profileIsTwoPane(821))
        assertTrue(profileIsTwoPane(1199))
        assertTrue(profileIsTwoPane(1200))
        // 目标屏：3200×2000 @2.0 = 1600×1000dp，减 Rail 80dp = 1520dp 内容
        assertTrue(profileIsTwoPane(1520))
        // 1280×800 @2.0 = 1280dp 窗口 → 1200dp 内容 → 两板
        assertTrue(profileIsTwoPane(1200))
        // 600dp 小平板竖屏 → 520dp 内容 → 单板（与改版前完全一致）
        assertFalse(profileIsTwoPane(520))
    }

    @Test
    fun `左板实宽按两板结构算 恒能放下一列设置行`() {
        // 两板结构：内容宽 − 板壳左右 padding(24) − 板间白缝(12) − 右板定宽(380)
        assertEquals(820 - 24 - 12 - 380, profileLeftPaneWidthDp(820))
        assertEquals(404, profileLeftPaneWidthDp(820))
        assertEquals(1104, profileLeftPaneWidthDp(1520))
        assertEquals(784, profileLeftPaneWidthDp(1200))
        // 两板下界处左板必须放得下一列设置行（≥380dp 才有可读宽度）
        assertTrue("两板下界处左板放不下一列设置行", profileLeftPaneWidthDp(820) >= 380)
    }

    @Test
    fun `负宽与首帧异常值退化单板 不进空区间`() {
        // 2026-11-18 真机崩溃（HScrollHint 滑块保底 > 封顶）是同款空区间事故：
        // 异常输入必须落到最保守的一档，而不是抛异常/算出非法组合。
        for (w in intArrayOf(-1, -80, Int.MIN_VALUE)) {
            assertFalse("内容宽 $w 该退化成单板", profileIsTwoPane(w))
        }
        // 负宽不能算出负的左板宽（负宽进布局会崩）
        assertEquals(0, profileLeftPaneWidthDp(0))
        assertEquals(0, profileLeftPaneWidthDp(-100))
        assertEquals(0, profileLeftPaneWidthDp(Int.MIN_VALUE))
        assertTrue("单板限宽必须是正数（否则 widthIn(max) 与 fillMaxWidth 打架）",
            PROFILE_ONE_COLUMN_MAX_WIDTH_DP > 0)
    }

    @Test
    fun `组宽与缝钉死 与首页右栏同一尺度`() {
        // 380dp = WideSecondaryWidth：全 app 只有一种「侧栏/单列」宽度心智。
        // 改成别的数，「我的」就会引入第二套尺度（用户看两屏对不上）。
        assertEquals(380, PROFILE_GROUP_WIDTH_DP)
        assertEquals(12, PROFILE_GROUP_GAP_DP)
    }

    @Test
    fun `两板下界与左板最小宽是同一个式子的两面`() {
        // 「够不够分两板」的下界与「左板实宽」必须出自同一个式子：
        // 若两者脱节，边界上会分了板却把左板压成一条缝。
        val min = TWO_BOARD_MIN_CONTENT_DP
        assertEquals(
            "两板下界处的左板宽度对不上",
            min - 24 - 12 - PROFILE_GROUP_WIDTH_DP,
            profileLeftPaneWidthDp(min)
        )
        // 差 1dp 退到单板，且退板后不再出现「窄到 380dp 以下却分了两板」
        assertFalse(profileIsTwoPane(min - 1))
        for (w in min..2000) {
            assertTrue("内容宽 $w 分了两板却把左板压到 $w", profileLeftPaneWidthDp(w) >= 380)
        }
    }
}
