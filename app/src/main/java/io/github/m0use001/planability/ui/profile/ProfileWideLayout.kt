package io.github.m0use001.planability.ui.profile

/**
 * 「我的」页宽屏摆位的纯函数（2026-11-19 首版；2026-11-20 v65 改两板）。
 *
 * ## 为什么要有这个文件
 * 「我的」在改之前是**一列 `adaptiveColumn()` 840dp 居中**：1600dp 宽的窗口里
 * 左右各空 340dp，三组设置项（基础 / Agent / 数据）竖着叠到 1500dp 高 ——
 * 横向两片死区、纵向一条长条，正是用户点名的「大而空」的典型形态。
 *
 * iPadOS HIG 对这个场景的口径是**自适应多栏填满窗口**（不是把一列拉宽，
 * 也不是留白）。
 *
 * ## v65：两板（原来三栏浮卡）
 *
 * 原来 ≥1164dp 走三栏，每栏是**一张浮起的圆角卡**（`SettingsGroup` 的 Card 外壳）
 * ——板与板之间是 12dp 空隙，栏内再套浮卡，两种材质叠在一起。
 * v65 统一成与计划页/首页同一套 `SplitBoards`：两块 24dp 圆角板 + 12dp 白缝，
 * 板内靠「分组标题 + 扁平行 + 细线」分层，不再有浮起卡。
 *
 * 分栏判定改由**内容区实测宽**决定（不看 `LocalConfiguration.screenWidthDp`
 * ——宽屏有 80dp 左侧 Rail，窗口宽会多算 80dp，临界值提前一档 —— FocusLayout 踩过）。
 *
 * 提成纯函数的理由与 `ui/focus/FocusLayout.kt` 相同：这些边界在
 * Compose 里没法被单测直接断言，提纯后 [ProfileWideLayoutTest] 能穷举边界，
 * 改代码时被带偏会立刻红。
 */

/**
 * 单个设置组的舒适宽度（dp）。
 *
 * 与首页/计划页的右栏同值（`WideSecondaryWidth = 380.dp`）：用户在宽屏上已经
 * 建立「一栏 380dp」的尺度感，「我的」再引入第二个宽度数字就是又一套心智。
 */
const val PROFILE_GROUP_WIDTH_DP = 380

/** 组与组之间的缝（dp）。与 `PlanWideLayout.PanelGap` 同一档，读成「同一套网格」。 */
const val PROFILE_GROUP_GAP_DP = 12

/** 一栏时整列的最大宽度：与 v57 的 `adaptiveColumn()` 默认值完全一致，不改变旧口径。 */
const val PROFILE_ONE_COLUMN_MAX_WIDTH_DP = 840

/**
 * 两板形态的下界（内容区实测宽 dp）。
 *
 * 两板结构：内容宽 − 板壳左右 padding(24) − 板间白缝(12) − 右板定宽(380)
 * = 内容宽 − 416 留给左板。左板要放得下一列设置行（≥380dp 才有可读宽度），
 * 所以内容宽 ≥ 796。取 820 留一点余量（行内文字不折行的舒适下限）。
 */
const val TWO_BOARD_MIN_CONTENT_DP = 820

/**
 * 是否走两板（内容区实测宽够不够）。
 *
 * 负宽（首帧测量前的异常值）一律落到单列，不进 `coerceIn` 才算
 * ——2026-11-18 真机崩溃（HScrollHint 滑块保底 > 封顶）就是这类空区间事故。
 */
fun profileIsTwoPane(contentWidthDp: Int): Boolean =
    contentWidthDp >= TWO_BOARD_MIN_CONTENT_DP

/**
 * 两板形态下左板的实宽（dp）。
 *
 * 口径 = 内容宽 − 板壳左右 padding(24) − 板间白缝(12) − 右板定宽(380)。
 * 与 `ui/common/SplitBoard.kt` 的 `SplitBoards` 结构一一对应。
 *
 * 走 **Long** 运算：`Int.MIN_VALUE - 416` 会在 Int 里整型溢出成一个巨大的正数
 * （2147483232），`coerceAtLeast(0)` 拦不住 —— 那是「异常输入退化」最该防的一类事故。
 */
fun profileLeftPaneWidthDp(contentWidthDp: Int): Int =
    (contentWidthDp.toLong() - 24 - 12 - PROFILE_GROUP_WIDTH_DP).coerceAtLeast(0L).toInt()
