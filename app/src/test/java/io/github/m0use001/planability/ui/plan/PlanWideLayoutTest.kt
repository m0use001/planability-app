package io.github.m0use001.planability.ui.plan

import androidx.compose.ui.unit.dp
import io.github.m0use001.planability.core.WindowSizeClass
import io.github.m0use001.planability.ui.common.WideSecondaryWidth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 计划页宽屏形态决策的穷举单测（2026-11-19）。
 *
 * 为什么值得单独抽一个纯函数来测：断点的作用在两个方向上都会出错，而且只有真机才看得见——
 * 600dp 也上 380dp 右栏，列表剩 220dp 是「挤」；1280dp 上选中一行却整页跳版是「晃」。
 * 这两类都不会让编译失败，也不会让截图看着「明显坏了」，只能靠穷举把边界钉住。
 *
 * 每条断言都做过植入故障验证（把 `!size.isExpanded` 改成 `!size.isWide`、把 selected
 * 分支写反），必须各自转红。
 */
class PlanWideLayoutTest {

    // ---------- 真值表：宽度 × 选中态 = 6 种组合 ----------

    @Test
    fun `Compact 手机恒为单列 选中态不产生分栏`() {
        assertEquals(PlanPane.Single, PlanWideLayout.paneOf(WindowSizeClass.Compact, false))
        assertEquals(PlanPane.Single, PlanWideLayout.paneOf(WindowSizeClass.Compact, true))
    }

    @Test
    fun `Medium 小平板恒为单列 不得分栏`() {
        // 600~839dp 减掉 Rail 80 与右栏 380 后列表只剩 140~379dp——
        // 这正是改动前「600dp 也上右栏」把列表压成 220dp 的那条缺陷。
        assertEquals(PlanPane.Single, PlanWideLayout.paneOf(WindowSizeClass.Medium, false))
        assertEquals(PlanPane.Single, PlanWideLayout.paneOf(WindowSizeClass.Medium, true))
    }

    @Test
    fun `Expanded 未选中为今天概览`() {
        assertEquals(PlanPane.Overview, PlanWideLayout.paneOf(WindowSizeClass.Expanded, false))
    }

    @Test
    fun `Expanded 选中为内联编辑器`() {
        assertEquals(PlanPane.Editor, PlanWideLayout.paneOf(WindowSizeClass.Expanded, true))
    }

    // ---------- 不变量 ----------

    @Test
    fun `取消选中必须回到概览 不能退回单列`() {
        // 宽度没变，只有选中态变了：整页从两栏跳回单列就是「点一下跳版」。
        val editing = PlanWideLayout.paneOf(WindowSizeClass.Expanded, true)
        val afterClose = PlanWideLayout.paneOf(WindowSizeClass.Expanded, false)
        assertEquals(PlanPane.Editor, editing)
        assertEquals(PlanPane.Overview, afterClose)
    }

    @Test
    fun `右栏宽度与首页侧栏同宽 全 app 只有一种侧栏心智`() {
        assertEquals(WideSecondaryWidth, PlanWideLayout.DetailWidth)
    }

    @Test
    fun `Expanded 分栏后左板仍不窄于手机内容宽`() {
        // Expanded 的最小窗口 840dp 减 Rail 80dp 减右栏 380dp = 380dp。
        val listWidth = 840.dp - 80.dp - PlanWideLayout.DetailWidth
        assertTrue(
            "分栏后左板只剩 ${listWidth}dp，比手机内容列还窄，分组列表会挤成一列碎词",
            listWidth.value >= PlanWideLayout.MinListWidth.value
        )
    }

    @Test
    fun `两栏圆角与栏间距是显式常量 不留散落的魔法数`() {
        assertTrue(PlanWideLayout.PanelShape.value > 0f)
        assertTrue(PlanWideLayout.PanelGap.value > 0f)
        // 栏间距必须小于圆角，否则两块板的边缘读不出「同一套网格」
        assertTrue(PlanWideLayout.PanelGap.value < PlanWideLayout.PanelShape.value)
    }

    @Test
    fun `板内内容宽度上限与既有单列口径一致`() {
        // 单列分支用 adaptiveColumn(maxWidth = 760.dp)；两栏分支的主板不该比它更宽，
        // 否则同一页在两种形态下换行位置完全不同。
        assertEquals(760f, PlanWideLayout.ListMaxWidth.value, 0.01f)
    }
}
