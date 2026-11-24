package io.github.m0use001.planability.ui.focus

/**
 * 专注页宽屏摆位的纯函数（2026-11-19 首版；2026-11-20 v65 改两板）。
 *
 * 为什么单独提纯：这些数字决定「大而空」到底有没有被填掉，而边界值
 * （两板下界、矮窗口 700）在 Compose 里没法被单测直接断言——
 * 提纯后 [FocusLayoutTest] 可以对边界与最坏情况穷举，改代码时被带偏会立刻红。
 *
 * 口径说明：这里的宽度一律是**内容区实测可用宽度**（`BoxWithConstraints` 的 maxWidth），
 * 不是 `LocalConfiguration.screenWidthDp`——宽屏有 80dp 左侧 Rail，窗口宽会多算 80dp，
 * 用它分档会让临界值提前一档（1500dp 窗口实际只有 1420dp 可摆）。
 *
 * ## v65：两板（原来三栏）
 *
 * 原来 ≥1200dp 走三栏（计时 | 统计 | 待办），≥900dp 走两栏（计时 | 全部）。
 * v65 统一成**两块板**（与计划页/首页同一套 `SplitBoards` 材质）：
 * 左板 = 计时舞台，右板 = 今日专注 / 近 7 天 / 本周概览 / 接下来要做 / 最近专注。
 * 三栏形态退役——三块直角列在宽屏上是「三种材质」，与两板体系不合。
 */

/** 手机单列（可用宽 < 900dp）时环的直径：与改版前一致，手机布局一字不动。 */
const val FOCUS_RING_PHONE_DP = 280

/** 「矮宽窗口」的高门槛（dp）：小平板横屏 / 分屏实测 1000×560，环不能再往上顶。 */
private const val SHORT_PANE_HEIGHT_DP = 700

/**
 * 两板的下界（内容区实测宽 dp）。
 *
 * 为什么不是 600：两板形态下左板（计时舞台）实宽 = 内容宽 − 24（板壳左右 padding）
 * − 12（板间白缝）− 380（右板定宽）= 内容宽 − 416。900dp 时左板 484dp，
 * 320dp 的环放得下还有富余；800dp 时左板只剩 384dp，环几乎贴边。
 */
const val TWO_BOARD_MIN_CONTENT_DP = 900

/**
 * 左板（计时舞台）的**内容列**最大宽度。
 *
 * 与计划页左板同一尺度（760dp）：宽屏左板本身是弹性的，但计时环不该在大板中央
 * 孤零零飘着——内容列限宽居中，环与控制块聚成一组，读起来是一台"计时器"，
 * 而不是散在大片空白里的几个控件。
 */
const val FOCUS_STAGE_MAX_WIDTH_DP = 760

/**
 * 两板形态下左板的实宽（dp）。
 *
 * 口径 = 内容宽 − 板壳左右 padding(24) − 板间白缝(12) − 右板定宽(380)。
 * 与 `ui/common/SplitBoard.kt` 的 `SplitBoards` 结构一一对应：
 * 外层 padding start/end 各 12dp，Row 内一个 12dp 白缝，右板 380dp。
 *
 * 走 **Long** 运算：`Int.MIN_VALUE - 416` 会在 Int 里整型溢出成巨大的正数，
 * `coerceAtLeast(0)` 拦不住 —— 与 `profileLeftPaneWidthDp` 同族的退化事故。
 */
fun focusLeftPaneWidthDp(contentWidthDp: Int): Int =
    (contentWidthDp.toLong() - 24 - 12 - 380).coerceAtLeast(0L).toInt()

/**
 * 是否走两板（内容区实测宽够不够）。
 *
 * 宽窄判定只此一处，页面里不许再散落 `if (wide)` 决定形态。
 */
fun focusIsTwoPane(contentWidthDp: Int): Boolean =
    contentWidthDp >= TWO_BOARD_MIN_CONTENT_DP

/**
 * 计时环直径（dp）。
 *
 * - 手机：恒 [FOCUS_RING_PHONE_DP]（280），与改版前完全一致；
 * - 宽屏：取「栏宽 58% / 可用高 52%」的较小值，夹到 `[320, 480]`；
 * - 矮宽窗口（高 < 700dp）：夹到 `[240, 320]`——v60.1 真机实测，320dp 的环会把
 *   「开始专注」顶出视口。
 *
 * 两组上下界都是静态有序的（240 ≤ 320 ≤ 480），`coerceIn` 不会出现空区间；
 * 这正是 2026-11-18 真机崩溃（HScrollHint 滑块宽保底 > 封顶）那类事故的形状，
 * 所以 [FocusLayoutTest] 会穷举宽高组合断言 ring ≤ 栏宽 且 lo ≤ hi 恒成立。
 */
fun focusRingSizeDp(paneWidthDp: Int, paneHeightDp: Int, wide: Boolean): Int {
    if (!wide) return FOCUS_RING_PHONE_DP
    // 首帧测量前 / 异常尺寸：退回手机口径，绝不进 coerceIn 才算
    if (paneWidthDp <= 0 || paneHeightDp <= 0) return FOCUS_RING_PHONE_DP
    val short = paneHeightDp < SHORT_PANE_HEIGHT_DP
    val lo = if (short) 240 else 320
    val hi = if (short) 320 else 480
    val byW = (paneWidthDp * 0.58f).toInt()
    val byH = (paneHeightDp * 0.52f).toInt()
    // 栏宽是硬上限：分档下界已经保证栏宽 ≥ 320，这里再兜一次，防止以后改边距时环溢栏
    return minOf(byW, byH, paneWidthDp).coerceIn(lo, hi)
}
