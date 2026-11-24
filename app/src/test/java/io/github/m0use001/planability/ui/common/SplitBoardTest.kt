package io.github.m0use001.planability.ui.common

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「主从两栏」共享层（ui/common/SplitBoard.kt，2026-11-20）的真值表。
 *
 * 为什么值得单独钉：分栏门槛是一个**减法**（内容宽 − 详情宽 ≥ 左板最小宽），
 * 写错方向的后果不是崩，而是 600~839dp 的小平板上左板被压成 220dp、列表读不了
 * ——这正是计划页 v60 之前的老缺陷。这里把边界两侧各取一个值钉死。
 *
 * 每条断言都做过**植入故障验证**：把 `>=` 改成 `>`、把 clamp 上下界对调、
 * 把“先算宽度再看选中态”的顺序换掉，各自会红。
 */
class SplitBoardTest {

    // ---------- 详情板宽度 ----------

    @Test
    fun `表单型详情宽恒为侧栏宽`() {
        assertEquals(
            "表单/列表型详情板的宽度必须与首页/计划页侧栏一致（全 app 一种详情宽）",
            BoardLayout.DetailWidth.value,
            MasterDetail.detailWidthFor(1200.dp, content = false).value,
            0.01f
        )
        assertEquals(
            "内容宽变化不该影响表单型详情板宽度",
            BoardLayout.DetailWidth.value,
            MasterDetail.detailWidthFor(2560.dp, content = false).value,
            0.01f
        )
    }

    @Test
    fun `内容型详情宽按四成夹在上下界之间`() {
        // 1200 × 0.4 = 480 → 正好上界
        assertEquals(480f, MasterDetail.detailWidthFor(1200.dp, content = true).value, 0.01f)
        // 900 × 0.4 = 360 → 低于下界，取下界
        assertEquals(
            "内容型详情不能比侧栏还窄",
            BoardLayout.DetailWidth.value,
            MasterDetail.detailWidthFor(900.dp, content = true).value,
            0.01f
        )
        // 1600 × 0.4 = 640 → 高于上界，取上界
        assertEquals(
            "内容型详情再宽也不能宽过读长上限",
            BoardLayout.ContentDetailWidth.value,
            MasterDetail.detailWidthFor(1600.dp, content = true).value,
            0.01f
        )
    }

    // ---------- 分栏门槛 ----------

    @Test
    fun `分栏门槛等于左板最小宽`() {
        // 刚好等于：760 − 380 = 380 = MinListWidth → 可以分栏
        assertEquals(
            "内容宽 − 详情宽 正好等于左板最小宽时应分栏（>= 边界）",
            DetailPane.Overview,
            MasterDetail.paneOf(760.dp, 380.dp, hasSelection = false)
        )
        // 差 1dp：379 < 380 → 不许分栏
        assertEquals(
            "左板不足 380dp 时必须退回单列——600~839dp 硬分栏把列表压成 220dp 是老缺陷",
            DetailPane.Single,
            MasterDetail.paneOf(759.dp, 380.dp, hasSelection = false)
        )
    }

    @Test
    fun `窄屏即使有选中也恒为单列`() {
        assertEquals(
            "手机（内容宽 411dp）没有分栏含义",
            DetailPane.Single,
            MasterDetail.paneOf(411.dp, 380.dp, hasSelection = false)
        )
        assertEquals(
            "手机选中一条也不许分栏——窄屏继续走原来的全屏二级页",
            DetailPane.Single,
            MasterDetail.paneOf(411.dp, 380.dp, hasSelection = true)
        )
    }

    @Test
    fun `选中态只决定右栏角色 不决定分不分栏`() {
        assertEquals(
            "宽屏未选中 = 概览（右栏不许空着）",
            DetailPane.Overview,
            MasterDetail.paneOf(1200.dp, 380.dp, hasSelection = false)
        )
        assertEquals(
            "宽屏选中 = 详情/内联编辑器",
            DetailPane.Detail,
            MasterDetail.paneOf(1200.dp, 380.dp, hasSelection = true)
        )
    }

    @Test
    fun `内容型详情下 1280 平板仍分得开`() {
        // 1280 窗口 − Rail 80 = 1200 内容宽；内容型详情 = 480 → 左板 720
        val detail = MasterDetail.detailWidthFor(1200.dp, content = true)
        assertEquals(
            "1280dp 平板上文件预览/笔记编辑器应取 480dp 右栏",
            480f,
            detail.value,
            0.01f
        )
        assertEquals(
            "左板必须仍有 720dp（远超 380 门槛）",
            DetailPane.Overview,
            MasterDetail.paneOf(1200.dp, detail, hasSelection = false)
        )
    }

    // ---------- 常量不变量 ----------

    @Test
    fun `材质常量自洽`() {
        assertEquals(
            "详情板宽度必须复用 AdaptivePane 的侧栏 token（全 app 一种详情宽）",
            WideSecondaryWidth.value,
            BoardLayout.DetailWidth.value,
            0.01f
        )
        assertTrue(
            "栏间白缝必须小于圆角，否则读不出「同一套网格」",
            BoardLayout.Gap.value < BoardLayout.Shape.value
        )
        assertTrue(
            "内容型详情上限必须大于表单型详情",
            BoardLayout.ContentDetailWidth.value > BoardLayout.DetailWidth.value
        )
        assertEquals("左板最小宽 380dp", 380f, BoardLayout.MinListWidth.value, 0.01f)
        assertTrue(
            "行间细线缩进必须小于板内 padding",
            BoardLayout.RowDividerInset.value < BoardLayout.Padding.value
        )
    }
}
