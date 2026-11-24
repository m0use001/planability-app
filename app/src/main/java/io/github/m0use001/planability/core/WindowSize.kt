package io.github.m0use001.planability.core

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import kotlin.math.roundToInt

/**
 * v57 窗口尺寸类（安卓平板适配，2026-11-16）。
 *
 * ## 为什么自己写而不是引 `material3-window-size-class`
 * 仓库里没有这个依赖，而断点规则只有三条，自己写零依赖、零版本耦合。
 *
 * ## 为什么读 `LocalConfiguration` 而不是根部 `CompositionLocal`
 * 本 App 的 40+ 个二级页都是**独立 Dialog 窗口**（`app/PredictiveBackOverlay.kt`），
 * 根部提供的 CompositionLocal **跨窗口不传递**（Dialog 是另一个 View 树、另一个组合）；
 * 而每个窗口自己的 `Configuration` 都会带上**该窗口**的尺寸（Android 11+ 会按窗口 bounds 更新），
 * 所以在任何一个窗口内部读 `LocalConfiguration.current.screenWidthDp` 都是准的。
 *
 * ## 断点（沿用 Material 3 的 WindowSizeClass）
 * - Compact：< 600dp —— 手机竖屏（也含折叠屏外屏），走**药丸底栏 + 单栏**
 * - Medium：600–839dp —— 小平板竖屏 / 大手机横屏，走**左侧导航栏 + 双栏**
 * - Expanded：≥ 840dp —— 平板横屏 / 桌面窗口，走**左侧导航栏 + 双栏 + 更宽的正文**
 */
enum class WindowSizeClass {
    Compact,
    Medium,
    Expanded;

    /** 是否是「宽」形态（≥600dp）：决定左侧导航栏与双栏是否启用。 */
    val isWide: Boolean get() = this != Compact
}

/** Compact → Medium 的断点（dp）。 */
const val MEDIUM_WIDTH_BREAKPOINT_DP = 600

/** Medium → Expanded 的断点（dp）。 */
const val EXPANDED_WIDTH_BREAKPOINT_DP = 840

/**
 * 由窗口宽度（dp）算出尺寸类。纯函数，便于单测直接打边界值。
 */
fun windowSizeClassOf(widthDp: Int): WindowSizeClass = when {
    widthDp < MEDIUM_WIDTH_BREAKPOINT_DP -> WindowSizeClass.Compact
    widthDp < EXPANDED_WIDTH_BREAKPOINT_DP -> WindowSizeClass.Medium
    else -> WindowSizeClass.Expanded
}

/**
 * 当前窗口的尺寸类。
 *
 * 用 `screenWidthDp`（窗口宽度）而不是 `smallestScreenWidthDp`（设备最短边）：
 * 手机横屏时宽度可能 ≥600dp，按 M3 规范那属于 Medium（横向空间确实够双栏），
 * 且本 App 的手机已由 `MainActivity` 运行时锁竖屏，这条路径实际只在平板上出现。
 */
@Composable
@ReadOnlyComposable
fun rememberWindowSizeClass(): WindowSizeClass =
    windowSizeClassOf(LocalConfiguration.current.screenWidthDp)

/**
 * 实测窗口宽度（dp）。
 *
 * v64 根修（2026-11-20，用户「信息页面菜单栏与右划菜单非常不协调」）：
 * [rememberWindowSizeClass] 读 `LocalConfiguration.screenWidthDp`，在分屏 / 自由窗口 /
 * 桌面镜像里会报**显示器**宽而不是**窗口**宽——一个 452dp 的窗口被报成 1600dp，
 * 宽屏分支（Rail + 340dp 常驻侧栏）在中等宽度上提前触发，内容被挤成一条缝。
 *
 * 这里改读 [LocalWindowInfo.current.containerSize]——它由 `WindowMetricsCalculator
 * .computeCurrentWindowMetrics(context).bounds` 填充，是**当前窗口**的真实像素尺寸
 * （自由窗口 / 分屏 / 虚拟屏都准）。取不到（=0，极少数窗口初始化前）才回落
 * `screenWidthDp`，保留 Dialog 窗口的既有行为（Dialog 是独立 View 树、不继承根部
 * CompositionLocal，但它的 AndroidComposeView 自己提供 LocalWindowInfo，所以这里
 * 实际上对 Dialog 也更准）。
 *
 * 注意：[rememberWindowSizeClass] **保持读 screenWidthDp 不变**——它被 40+ 处页面
 * 双栏逻辑引用、且有守卫测试钉住 `Medium.isWide == true`；外壳与信息页的「上不上
 * Rail / 上不上两板」单独按本函数裁决，避免牵动其它页面。
 */
@Composable
@ReadOnlyComposable
fun rememberMeasuredWindowWidthDp(): Int {
    val density = LocalDensity.current
    val container = LocalWindowInfo.current.containerSize
    if (container.width > 0) {
        return with(density) { container.width.toDp() }.value.roundToInt()
    }
    return LocalConfiguration.current.screenWidthDp
}

/**
 * 外壳导航形态：宽窗（实测 ≥840dp）走 Rail + 两板，否则药丸底栏 + 抽屉（手机形态）。
 *
 * 用户拍板（2026-11-20）：600–840dp 的小平板竖屏 / 分屏 **退回手机形态**（药丸底栏
 * + 右划抽屉），只有真平板横屏（≥840dp）才上宽屏形态——否则信息页会出现
 * 「Rail + 常驻侧栏」两套导航同时在场，内容被挤成缝。
 */
@Composable
@ReadOnlyComposable
fun rememberShellRailForm(): Boolean =
    rememberMeasuredWindowWidthDp() >= EXPANDED_WIDTH_BREAKPOINT_DP
