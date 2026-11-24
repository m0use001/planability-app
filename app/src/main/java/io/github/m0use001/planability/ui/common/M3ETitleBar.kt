package io.github.m0use001.planability.ui.common

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * M3E 折叠标题栏 —— 替代 material3 1.5.0-beta02 的 `LargeFlexibleTopAppBar`。
 *
 * 为什么不用库组件（2026-10-24 用户精修动效拍板）：库的这一版在折叠区间做**两枚标题的
 * 透明度交叉**（展开的大标题淡出、pinned 小标题淡入，中段两份半透明同屏）；而 Android 16
 * 原生（Settings 5x 慢放实测）是**一枚标题随滚动连续缩放**，任何时刻只有一行字。库实现
 * 落后于原生行为，这里按原生语义自研：
 *
 * 1. 标题 = 一枚 Text，[collapsedFraction] 驱动 graphicsLayer 连续插值
 *    （scale 1.0 → 0.786 ≈ headlineMedium 28sp → titleLarge 22sp），锚点左下——
 *    滚动无级跟手，无交叉、无透明度渐变；
 * 2. 副标题随折叠淡出（连续滚动驱动，非状态切换动画，原生同款）；
 * 3. 顶栏与正文的分割 = 原生同款**底部圆角浮层**：背景色 containerLow→container、
 *    底部圆角 0→28dp、轻投影，全部随 fraction 连续渐入（直线分割消灭）；
 * 4. 折叠跟手由 [collapseScrollConnection] 在 nestedScroll 前置消费完成（exitUntilCollapsed
 *    语义：上滑先折叠、下滑先展开），无 fling 吸附——半途停留是合法状态（原生同款）。
 *
 * 固定形态（编辑器等无滚动的全屏子页）传 [collapsedFraction] = 1f，即只取第 3 条的
 * 圆角浮层 + 小标题，一行直线都不画。
 */
class CollapseState {
    var fraction by mutableFloatStateOf(0f)
}

/**
 * exitUntilCollapsed 语义的 nestedScroll 连接：上滑**先**折叠标题栏再滚内容，
 * 下滑**先**展开标题栏再滚内容；消费量恰好是标题栏吃掉的部分，剩余全部放行给内容。
 *
 * [invert]：个别滚动容器给上滑的 dy 是负的（首页时间轴实测，2026-10-24 logcat：
 * 常规 LazyColumn 上滑 = 正 dy，时间轴上滑 = 负 dy），接线前先真机/模拟器验证方向。
 */
fun collapseScrollConnection(
    state: CollapseState,
    invert: Boolean = false,
    /**
     * 是否允许"展开"（fraction 变小）。首页传 `scrollState.value ≲ 0`：
     * 2026-10-25 用户「首页标题放大态要到快划到 0 点才展开」——时间轴滚到 8 点时往回划，
     * 标题不再立刻变大，而是一路保持折叠，等内容真正回到零点附近才展开。
     */
    canExpand: (() -> Boolean)? = null,
    extraPx: () -> Float
): NestedScrollConnection =
    object : NestedScrollConnection {
        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
            val limit = extraPx()
            if (limit <= 0f) return Offset.Zero
            val dy = if (invert) -available.y else available.y
            val raw = state.fraction * limit + dy
            val new = raw.coerceIn(0f, limit)
            val applied = new - state.fraction * limit
            // applied < 0 = 正在展开；不到顶部附近就把这段位移让给内容本身（不展开标题）
            if (applied < 0f && canExpand != null && !canExpand()) return Offset.Zero
            state.fraction = new / limit
            return Offset(0f, applied)
        }

        override suspend fun onPreFling(available: Velocity): Velocity {
            // fling 不做吸附：折叠是无级连续的（原生 Settings 同款），半途停留是合法状态
            return Velocity.Zero
        }
    }

/** 固定行高度（折叠态整条标题栏的高度，不含状态栏）——各页内容垫高/折叠行程都从这里取。 */
val M3ETitleBarPinnedH: androidx.compose.ui.unit.Dp = 64.dp

/**
 * 展开态比折叠态多出的高度（= 折叠行程）。
 * 2026-10-24 用户反馈"展开后大而空"：从 72dp 收到 56dp——标题行与固定行之间不再空出一大段。
 * 2026-10-25 首页要在展开态加第三行「日程 N · 待办 M」（[expandedMeta]），
 * 36dp 标题 + 18dp 副标题 + 18dp 元信息正好 72dp，故调回 72dp；
 * 这次三段都有内容，不会回到"大而空"。本库其余调用点都是 expandable=false，不受影响。
 */
val M3ETitleBarExtra: androidx.compose.ui.unit.Dp = 72.dp

private val PINNED_H = M3ETitleBarPinnedH
private val EXPANDED_EXTRA = M3ETitleBarExtra
private val ROUND = 28.dp
/** 固定态副标题占用的额外高度（会话页的「助手 · 模型 · 权限」一行） */
private val FIXED_SUB_H = 18.dp

@Composable
fun M3ETitleBar(
    title: String,
    collapsedFraction: Float,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    /**
     * 固定态副标题的**自定义内容**（会话页用：权限/上下文两枚胶囊）。
     * 与 [subtitle] 二选一；传了它就用它，高度按胶囊给（26dp）。
     */
    subtitleContent: (@Composable () -> Unit)? = null,
    /**
     * 展开态附加行（首页「日程 N · 待办 M」用）：在副标题下面再加一行 12sp 元信息。
     * 与副标题同一套折叠语义——高度收零 + 淡出，折叠态不占位（否则会把标题顶离小标题槽位）。
     */
    expandedMeta: (@Composable () -> Unit)? = null,
    /**
     * 折叠态跟在标题右侧的紧凑文字（首页传 "10/25"）：副标题折叠后高度收零，
     * 没有它折叠栏里就只剩「今天」看不到日期（2026-10-25 用户要求）。
     * 展开态 alpha=0，不占视觉。
     */
    collapsedSuffix: String? = null,
    /**
     * v56.1 跟在标题文字**右侧**的自定义内容（笔记本列表页：封面概览小图）。
     * 底对齐贴着标题基线，宽度自控（调用方给 size）——只在小空间放小物件，
     * 别拿来放大块头（会把标题挤没）。
     */
    titleTrailing: (@Composable () -> Unit)? = null,
    navigationIcon: (@Composable () -> Unit)? = null,
    actions: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    /**
     * 底部右侧插槽（首页评分胶囊用）：钉在标题块同一底边上。
     * 2026-10-25 用户「评分与完成率胶囊没对齐，而且整体标题栏还是很空」——
     * 胶囊原来在 64dp 工具行右侧，展开区右下角整块空着；放到这里把空白用起来，
     * 与「今天 / 日期」同一视觉行。
     */
    trailing: (@Composable () -> Unit)? = null,
    /**
     * 把 [trailing] 从"标题块底边"上移多少（首页评分胶囊用）。
     * 2026-10-25 用户「首页标题栏不整齐」：展开态有了第三行「日程/待办数量」后，
     * 胶囊仍贴底边 → 骑在日期与数量两行之间，和任何一行都对不齐。
     * 首页传 34dp 把胶囊中心抬到**大标题那一行**的中心（40dp 胶囊 / 72dp 三行块）。
     */
    trailingLift: androidx.compose.ui.unit.Dp = 0.dp,
    /**
     * false = 恒定固定态：不画展开区（高度恒 64dp），标题恒为小标题、圆角浮层常驻。
     * 2026-10-24 用户指令「计划、笔记取消下拉标题增大动画但保留圆角标题栏」。
     * 没有展开区后 [subtitle] 不显示（它原本只在展开区里），调用点不必再传。
     */
    expandable: Boolean = true,
    /**
     * 2026-11-20 用户「首页顶部这个标题栏也太突兀了，够不到两边，又是个圆角」：
     * flat = **平铺形态**——无底部圆角、无投影，是一条通到两边的实色带，底部压一条
     * 1dp 细线与滚动的正文分开。宽屏一级页（首页）用：680dp 居中的浮起圆角卡在
     * 大屏上是第二种材质。窄屏路径不传，圆角浮层照旧。
     */
    flat: Boolean = false,
    /**
     * 这根顶栏要不要**自己**吃掉状态栏高度。
     *
     * 默认 true（页面级顶栏：它自己从屏幕最顶端开始画，所以要垫状态栏）。
     * 宽屏「板内顶栏」要传 false：那时整页已经被 `SplitBoards` 的
     * `statusBarsPadding()` 推下去了（两板顶边对齐），顶栏再垫一次就是双倍空白。
     */
    statusBarInset: Boolean = true
) {
    // 固定态直接把分数钉在 1：底色/圆角/投影/标题缩放全走折叠分支，
    // 但没有任何展开行程——于是「下拉变大」不再存在，圆角栏一直在。
    val f = if (expandable) collapsedFraction.coerceIn(0f, 1f) else 1f
    val statusBarH = if (statusBarInset) {
        WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    } else {
        0.dp
    }
    // 2026-11-01：固定态也允许显示副标题（会话页要在圆角栏里一眼看到 助手·模型·权限）。
    // 之前副标题只属于展开区，固定态传了也看不见——这里给固定态补一行高度。
    val fixedSubtitle = !expandable && (subtitle != null || subtitleContent != null)
    // 胶囊副标题比纯文字高一条；高度跟着内容走，避免胶囊被裁掉。
    // 2026-11-08 用户「胶囊太靠近标题栏底部」：26→32dp，多出的 6dp 是胶囊行与
    // 圆角底边之间的呼吸距（行内底距，见下方胶囊 Row）。
    val fixedSubH = if (subtitleContent != null) 32.dp else FIXED_SUB_H
    // 2026-11-01 用户「顶部空了一大片」：固定态带副标题时，标题不需要 64dp 的大行高——
    // 收成 40dp 让标题与胶囊贴紧（用户第二轮反馈「胶囊再往上移」后又一次收紧）。
    val pinnedRowH = if (fixedSubtitle) 40.dp else PINNED_H
    val headerH = statusBarH + pinnedRowH +
        (if (fixedSubtitle) fixedSubH else 0.dp) +
        EXPANDED_EXTRA * (1f - f)
    // 2026-10-25 用户「标题栏与正文有区别吗」：展开态之前与正文同色（原生同款），
    // 但用户要求**两个状态都可辨**——现在展开 = containerLow（一档）、折叠 = container（两档），
    // 圆角与投影仍只随折叠出现。
    val bg = lerp(
        MaterialTheme.colorScheme.surfaceContainerLow,
        MaterialTheme.colorScheme.surfaceContainer,
        f
    )
    val radius = if (flat) 0.dp else ROUND * f
    val shape = RoundedCornerShape(bottomStart = radius, bottomEnd = radius)
    val navIsLight = MaterialTheme.colorScheme.surface.luminance() > 0.5f
    val shadowColor = Color.Black.copy(alpha = if (navIsLight) 0.10f else 0.45f)
    // flat 形态的底部分界线颜色（在组合期取好——drawBehind 不是 composable 上下文）
    val flatLineColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)

    Box(
        modifier
            .fillMaxWidth()
            .height(headerH)
            // 投影只随折叠出现（原生折叠后顶栏是"浮"起来的；展开时与正文同层无阴影）。
            // flat 形态整条不浮：没有投影，与正文的分界交给底部那根 1dp 细线。
            .shadow(
                elevation = if (flat) 0.dp else 3.dp * f, shape = shape, clip = false,
                ambientColor = shadowColor, spotColor = shadowColor
            )
            .clip(shape)
            .background(bg)
            .then(
                if (flat) Modifier.drawBehind {
                    // 平铺形态的"底边"：一条 1dp 细线代替圆角+投影的分层信号
                    val stroke = 1.dp.toPx()
                    drawLine(
                        color = flatLineColor,
                        start = Offset(0f, size.height - stroke / 2),
                        end = Offset(size.width, size.height - stroke / 2),
                        strokeWidth = stroke
                    )
                } else Modifier
            )
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
    ) {
        // ── 固定行：导航图标 + 动作（始终钉在 64dp 区，垂直居中）──
        // 2026-10-24 用户反馈"大标题时其它组件不响应很突兀"：图标/动作随折叠**同向缩放**
        // （折叠态 1.0 = 标准尺寸，展开态 1.15），各自锚定所在边缘（左图标锚左、动作锚右），
        // 既不越出屏幕，又和大标题保持同一套比例语言。
        val pinnedScale = 1.15f - 0.15f * f
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = statusBarH)
                .height(pinnedRowH)
                .fillMaxWidth()
                .padding(horizontal = 4.dp)
        ) {
            if (navigationIcon != null) {
                Box(
                    Modifier.graphicsLayer {
                        scaleX = pinnedScale
                        scaleY = pinnedScale
                        transformOrigin = TransformOrigin(0f, 0.5f)
                    }
                ) { navigationIcon() }
            }
            Spacer(Modifier.weight(1f))
            if (actions != null) {
                // Row 而不是 Box：actions 插槽是 M3 语义的"动作区"，可以有多个按钮
                //（专注页 3 个）。Box 会让多个 IconButton 同位叠加（2026-10-25 真机截图）。
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.graphicsLayer {
                        scaleX = pinnedScale
                        scaleY = pinnedScale
                        transformOrigin = TransformOrigin(1f, 0.5f)
                    }
                ) { actions() }
            }
        }
        // ── 标题：一枚 Text 连续缩放，锚点左下；同时横移进小标题槽位（导航图标右侧）——
        //    原生大标题折叠就是"缩小+归位"两段连续几何运动，只有一行字、无交叉无淡出。
        //    展开态 x=20dp（正文左缘），折叠态 x=60dp（导航图标 48dp 区结束于 52dp + 8dp 间距）。
        //    2026-10-25 用户「折叠态搜索图标/今天/评分要一条直线，'今天'要靠近搜索按钮」：
        //    72dp → 60dp 收掉那段空档；底边距折叠时收到 14dp 让标题行与 64dp 行垂直居中。
        //    固定态（expandable=false，计划/笔记）**没有导航图标**，标题按正文左缘 20dp
        //    左对齐即可——用户 2026-10-24「笔记、计划标题左对齐」。──
        Column(
            Modifier
                // 固定态带副标题时改用顶对齐 + 顶部内距：标题要和返回键**同一行**，
                // 副标题落在它下面一行（原来靠 bottom 对齐会把标题压到副标题那一行去）。
                .align(if (fixedSubtitle) Alignment.TopStart else Alignment.BottomStart)
                .padding(
                    // 折叠态 56dp：搜索按钮触控区结束于 52dp，只留 4dp，视觉上紧贴图标；
                    // 标题缩放到 0.786 且锚点左下 → 视觉中心比布局中心低约 4dp，
                    // 底边距折叠时给 18dp 才能让"今天"与左右控件落在同一条水平线上。
                    // 2026-11-01 会话页在固定态里需要返回键（之前固定态假定没有导航图标，
                    // 标题会压到图标上）：传了 navigationIcon 就把标题右移一档让位。
                    start = 20.dp + if (expandable) 36.dp * f
                    else if (navigationIcon != null) 40.dp else 0.dp,
                    end = 20.dp,
                    top = if (fixedSubtitle) statusBarH else 0.dp,
                    bottom = if (expandable) 16.dp + 2.dp * f
                    else if (fixedSubtitle) 0.dp else 16.dp
                )
        ) {
            Row(
                // 2026-11-01：固定态带副标题时，标题放进与导航图标同高的 64dp 行里垂直居中——
                // 之前用底对齐+内距推算，实测标题比返回键低一截（用户截图指出）。
                verticalAlignment = if (fixedSubtitle) Alignment.CenterVertically else Alignment.Bottom,
                modifier = if (fixedSubtitle) Modifier.height(pinnedRowH) else Modifier
            ) {
                Text(
                    title,
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.alignByBaseline().graphicsLayer {
                        val s = 1f - 0.214f * f
                        scaleX = s
                        scaleY = s
                        transformOrigin = TransformOrigin(0f, 1f)
                    }
                )
                if (titleTrailing != null) {
                    // v56.2b 标题右侧插槽（封面小图等）：与标题**靠紧**（start 4dp）且**底对齐**——
                    // 标题缩放锚点在底边（TransformOrigin(0f,1f)），视觉底=行底，插槽不额外抬高
                    Box(
                        Modifier
                            .align(Alignment.Bottom)
                            .padding(start = 4.dp)
                    ) { titleTrailing() }
                }
                if (collapsedSuffix != null) {
                    // 折叠态在大标题右侧补一个紧凑日期（"今天 10/25"）：折叠后副标题收零，
                    // 不补日期的话视图只剩"今天"，与用户"折叠态也要显示当前日期"的要求不符。
                    Text(
                        collapsedSuffix,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        modifier = Modifier
                            .alignByBaseline()
                            .padding(start = 8.dp)
                            .alpha(f)
                    )
                }
            }
            // 展开态的副标题/元信息：**缩放系数与盒子高度同为 (1-f)**。
            // 2026-11-19 修「首页副标题被拦腰裁掉」：盒子 `height(18dp×(1-f)) + clipToBounds`
            // 而文字保持自然行高 ~15dp，f 一旦大于 0，字就比盒子高 —— f=0.5 时盒子只剩
            // 9dp，日期行从字腰上被切掉（实测首页自动滚到当前时刻后 f 常年停在中段，
            // 于是这条从「滚动瞬间偶发」变成「打开就一直挂着」）。让文字按同一系数
            // 缩放，任意 f 下墨迹高度 = 15×(1-f) ≤ 盒子 18×(1-f)，**任何时刻都不再被切**；
            // 顺带与标题自己的连续缩放（TransformOrigin(0,1)）同一套语言。
            // 固定态（expandable=false）f 恒为 1，subScale 恒为 1 —— 常显、不缩，行为不变。
            val subScale = if (fixedSubtitle) 1f else 1f - f
            if (subtitle != null) {
                // 副标题随折叠**高度收零**（不只是 alpha）：否则隐形空间会把标题顶离
                // 小标题槽位（折叠态标题要垂直居中到 64dp 固定行，与导航图标同轴）
                // 2026-11-01：固定态（expandable=false）反过来——常显，供会话页展示
                // 「助手 · 模型 · 权限」这类一眼可见的会话上下文。
                Box(
                    Modifier
                        .height(if (fixedSubtitle) FIXED_SUB_H else 18.dp * (1f - f))
                        .clipToBounds()
                ) {
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .alpha(subScale)
                            .graphicsLayer {
                                scaleX = subScale
                                scaleY = subScale
                                transformOrigin = TransformOrigin(0f, 0f)
                            }
                    )
                }
            }
            if (expandedMeta != null) {
                // 展开态第二行（首页：日程/待办数量）。同一套收零+淡出+缩放；
                // 展开区 72dp 装得下「标题 + 副标题 + 元信息」三行（2026-10-25 用户要求）。
                // 行内留 2dp 上间距：紧贴日期行时两行字会糊成一块（用户「标题栏不整齐」）。
                Box(
                    Modifier
                        .height(18.dp * (1f - f))
                        .clipToBounds()
                ) {
                    Box(
                        Modifier
                            .alpha(subScale)
                            .graphicsLayer {
                                scaleX = subScale
                                scaleY = subScale
                                transformOrigin = TransformOrigin(0f, 0f)
                            }
                            .padding(top = 2.dp)
                    ) { expandedMeta() }
                }
            }
        }
        // ── 固定态胶囊副标题行：**脱离标题缩进、横向铺满顶栏** ──
        // 2026-11-07 用户「AI 会话顶部标题栏，小胶囊无需与会话标题对齐，而是要占满整行」：
        // 原来胶囊组嵌在标题 Column 里，随标题缩进（start=20dp+导航让位 40dp），
        // 右侧还留着标题的 20dp 内距——整行左缩进、右留白，看着像标题的附属。
        // 现在作为外层 Box 的直接子级铺满整行（左右 20dp=本组件内容基线距；
        // 12dp 实测「又太左了」，用户二轮纠正），**具体怎么分布交给 subtitleContent**——
        // 2026-11-08 用户「应该用满一行」：会话页传 fillMaxWidth+SpaceBetween 的内容自己铺满，
        // 中枢页传靠左的内容维持靠左。行高 26dp + 底距 6dp（fixedSubH=32），
        // 胶囊不再贴着圆角底边。内容**自己保证不溢出**（会话页五胶囊实测放得下），
        // 这里不再包 horizontalScroll——包了会给出无限宽约束，调用方的 fillMaxWidth 失效。
        if (subtitleContent != null && fixedSubtitle) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(top = statusBarH + pinnedRowH, bottom = 6.dp)
                    .fillMaxWidth()
                    .height(26.dp)
                    .padding(start = 20.dp, end = 20.dp)
            ) { subtitleContent() }
        }
        // ── 底部右侧插槽：与标题块同一底边（16dp）、右缘 20dp ──
        // 首页评分胶囊放这里（原来挂在 64dp 工具行里，展开区右下整块空着）。
        if (trailing != null) {
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 20.dp, bottom = 16.dp)
                    // 上移量只作用于展开态：折叠态胶囊必须与搜索图标/标题同轴（一条直线）
                    .offset(y = -(trailingLift * (1f - f)))
            ) { trailing() }
        }
    }
}

/**
 * 编辑器等全屏子页的**内容错峰进场**：顶栏立即在场（圆角浮层），正文 8dp 短上滑 + 快淡入。
 * 全局 PredictiveBackOverlay 的 32dp 上滑在这里显多余——"标题"字段被带着上升淡入，
 * 5x 慢放下读作"标题二字飞进来"（用户点名）。8dp 的距离短到读不出飞入，只有一层
 * "轻轻落定"：alpha 走 quickEffects（透明度不过冲），位移走 quick（快档弹簧）。
 */
fun Modifier.editorContentEnter(): Modifier = composed {
    val alpha = remember { Animatable(0f) }
    val rise = remember { Animatable(1f) }
    LaunchedEffect(Unit) {
        launch { alpha.animateTo(1f, Motion.quickEffects()) }
        launch { rise.animateTo(0f, Motion.quick()) }
    }
    graphicsLayer {
        this.alpha = alpha.value
        translationY = 8.dp.toPx() * rise.value
    }
}
