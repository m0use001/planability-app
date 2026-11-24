package io.github.m0use001.planability.ui.common

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * v59 平板：「别空」批的宽屏分栏骨架（2026-11-17）。
 *
 * ## 已退役（2026-11-20 v65）
 *
 * 这个骨架是「左栏 + 一根 `VerticalDivider` + 右栏」的**直角分栏**：没有圆角板、
 * 没有白缝、栏内容直接铺在页面底色上。它比计划页那套（`ui/common/SplitBoard.kt`
 * 的 `SplitBoards`：两块 24dp 圆角板 + 12dp 白缝 + 板头长在板面上）**少了整整一套材质**，
 * 于是全 app 出现两种网格 —— 用户报的「所有界面都要改成计划界面这种 2 个圆角矩形框」。
 *
 * 首页是最后一个使用者（已改走 `SplitBoards`），故本函数删除。
 * 保留 [WideSecondaryWidth] 等常量：它们仍是全 app 唯一的侧栏宽度口径。
 */

/** 宽屏第二栏（今日侧栏）的固定宽度：够放统计卡 + 一列待办行。 */
val WideSecondaryWidth: Dp = 380.dp

/**
 * 宽屏**主列**（工作台首页左栏）的最大宽度。
 *
 * v63（2026-11-19，3200×2000 = 1600×1000dp 实测）：信息页去掉 840dp 单列之后，
 * 左栏实测可用宽是 1600 − Rail 80 − 右栏 380 − 分隔线 1 ≈ **1139dp**——
 * 不限宽的话输入卡会变成「一整行 1139dp 的输入框」，行长读不动（HIG Layout：
 * 内容列要有可读宽度上限，别把窗口宽度当内容宽度）。
 *
 * 960dp 是「输入卡仍占主导、两侧各留约 90dp 呼吸」的取值：
 * 1139 − 960 = 179dp，左右各约 90dp；同时 960 > 既有的 `adaptiveColumn()` 默认 840dp，
 * 不会比改之前更窄。与 [WideSecondaryWidth] 一起构成「380 侧栏 + 960 主列」这一套平板网格。
 */
val WideWorkbenchMaxWidth: Dp = 960.dp

/**
 * 单日时间轴列的最大宽度。超宽的事件块（截图里 1200dp 的大横条）就是它没上限造成的：
 * 标题贴最左边、右边全空。680dp 下块宽约 614dp，一行标题读起来正好。
 */
val WideTimelineMaxWidth: Dp = 680.dp

/** 日历月视图的最大宽度：7 列等宽后每列约 87dp，日圈不散架。 */
val WideCalendarMaxWidth: Dp = 620.dp
