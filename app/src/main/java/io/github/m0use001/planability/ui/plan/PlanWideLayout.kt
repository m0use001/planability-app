package io.github.m0use001.planability.ui.plan

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.m0use001.planability.core.WindowSizeClass
import io.github.m0use001.planability.ui.common.WideSecondaryWidth

/**
 * 计划页宽屏形态的决策机（2026-11-19）。
 *
 * 背景：计划页原来用「是否 isWide」一个布尔值同时决定了三件事——控制行限宽、要不要右栏、
 * 右栏多宽——于是 600dp 的小平板上右栏硬占 380dp，列表只剩 220dp；而 1280dp 的大平板上
 * 右栏又只是三张「今天」卡片，点一行仍然弹全屏编辑器。**宽度断层与交互形态被绑成了一个开关**，
 * 这是本页在平板上既挤又割裂的结构性原因。
 *
 * 这一版把「宽度 → 形态」抽成**纯函数**，两个维度各自独立：
 *  · `Expanded`（≥840dp）才允许两栏。840 是 [WindowSizeClass] 的既有断点：减去左侧
 *    Rail 80dp 与右栏 380dp 后，左板仍有 380dp，比手机还宽，分组列表读得开；
 *    600~839dp 一律单列——那里的「两栏」只会把列表压成 220dp。
 *  · 两栏时右栏有两种角色：没选中 = 今天概览；选中某一行 = 就地编辑（主从）。
 *    选中态只影响右栏内容，不影响分栏与否——否则点一下整页跳版。
 *
 * 纯函数的好处：断点组合能穷举单测（见 PlanWideLayoutTest），不靠截图目测；
 * 将来调断点只改这一处，不会漏掉某个调用点。
 */
enum class PlanPane {
    /** 单列：手机与 Medium 平板，行为与改动前完全一致（全屏编辑器）。 */
    Single,

    /** 两栏，右栏是「今天」概览。 */
    Overview,

    /** 两栏，右栏是选中条目的内联编辑器。 */
    Editor,
}

object PlanWideLayout {

    /** 右栏宽度。与首页 `WideSplitPane` 的第二栏同宽，全 app 只有一种「侧栏宽度」心智。 */
    val DetailWidth: Dp = WideSecondaryWidth

    /**
     * 左板的最小可用宽度。Expanded 断点（840dp）减 Rail（80dp）减右栏（380dp）= 380dp，
     * 正好等于一部手机的内容宽度；再窄就不该分栏了。
     */
    // v64（2026-11-20）：下面这几个材质常量已抽到 ui/common/SplitBoard.kt 的 BoardLayout，
    // 这里保留同名别名只为不动既有单测与调用点——**值只有一份**，改一处全页面跟着变。
    val MinListWidth: Dp = io.github.m0use001.planability.ui.common.BoardLayout.MinListWidth

    /** 两栏的圆角与栏间距：两块板用同一个值，才读得出「同一套网格」。 */
    val PanelShape: Dp = io.github.m0use001.planability.ui.common.BoardLayout.Shape
    val PanelGap: Dp = io.github.m0use001.planability.ui.common.BoardLayout.Gap

    /** 板内统一内缩（板头 / 概览正文）。 */
    val BoardPadding: Dp = io.github.m0use001.planability.ui.common.BoardLayout.Padding

    /**
     * 主板内容的最大宽度。分组列表在超宽屏上拉太长会难以扫读，
     * 与既有的 `adaptiveColumn(maxWidth = 760.dp)` 保持同一口径。
     */
    val ListMaxWidth: Dp = 760.dp

    /** 宽屏下「上一行与下一行」之间的细分隔线两端内缩量。 */
    val RowDividerInset: Dp = io.github.m0use001.planability.ui.common.BoardLayout.RowDividerInset

    /**
     * 单个状态查询：给宽度与选中态，得到这一帧该画什么。
     *
     * 不变量（单测钉住）：
     *  1. 非 Expanded 恒为 [PlanPane.Single]——选中态在手机上没有任何分栏含义；
     *  2. Expanded 且无选中 = [PlanPane.Overview]，有选中 = [PlanPane.Editor]；
     *  3. 从 Editor 取消选中必须回到 Overview，不能退回 Single（宽度没变，整页跳版是 bug）。
     */
    fun paneOf(size: WindowSizeClass, hasSelection: Boolean): PlanPane = when {
        !size.isExpanded -> PlanPane.Single
        hasSelection -> PlanPane.Editor
        else -> PlanPane.Overview
    }
}

/** 只有 Expanded 才分栏；其余宽度（手机与 Medium 平板）走单列。 */
val WindowSizeClass.isExpanded: Boolean
    get() = this == WindowSizeClass.Expanded
