package io.github.m0use001.planability.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 全 app 的「主从两栏」材质与分栏决策（2026-11-20，从计划页提取）。
 *
 * 为什么要有这个文件：计划页（05064fa3）那套「两块板 + 白缝 + 板头」在 2026-11-19 治好了
 * 「内容块割裂」，随后首页顶栏、会话页都各自又在抄同样的常量。同一套视觉语言被抄三遍，
 * 下次调圆角/间距就得记着改 N 处——这里把它收成一份，计划页也回接过来。
 *
 * 三条硬约定（都有单测/守卫钉住）：
 *  1. **两板同网格**：同一个圆角、同一个底色、同样的 statusBar/navBar inset，栏间是
 *     12dp **白缝**而不是 `VerticalDivider`（竖线只起切割作用，不表达任何层级）。
 *  2. **板头长在板面上**（[BoardHeader]）：宽屏不再往板里嵌浮起的 `M3ETitleBar`
 *     （28dp 圆角 + 3dp 阴影 = 第二种材质）。
 *  3. **分栏门槛是算出来的**（[MasterDetail.paneOf]）：内容宽 − 详情宽 ≥ [BoardLayout.MinListWidth]
 *     才分栏。600~839dp 的「小平板硬上两栏」把左板压成 220dp 的老缺陷就是这么来的。
 */
object BoardLayout {
    /** 两块板的圆角。 */
    val Shape: Dp = 24.dp

    /** 栏间白缝：的分隔靠留白与底色，不靠线。 */
    val Gap: Dp = 12.dp

    /** 板内统一内缩（板头、分节内容）。 */
    val Padding: Dp = 16.dp

    /** 表单/列表型详情板宽度：与首页/计划页侧栏同宽，全 app 只有一种「详情宽」心智。 */
    val DetailWidth: Dp = WideSecondaryWidth

    /** 内容型详情板（文件预览、笔记编辑器）的最大宽度——留给正文的读长。 */
    val ContentDetailWidth: Dp = 480.dp

    /** 左板最小可用宽度：比一部手机的内容宽度略宽，分组列表才读得开。 */
    val MinListWidth: Dp = 380.dp

    /** 宽屏「扁平行」之间的 1dp 细线两端内缩量。 */
    val RowDividerInset: Dp = 14.dp
}

/** 一页在宽屏下的三态：单列 / 两栏且右栏是概览 / 两栏且右栏是选中项详情。 */
enum class DetailPane {
    /** 单列（手机与窄平板）：行为与改造前完全一致。 */
    Single,

    /** 两栏，右栏是「这一页的概览」（未选中时不许空着）。 */
    Overview,

    /** 两栏，右栏是选中条目的内联详情/编辑器。 */
    Detail,
}

object MasterDetail {
    /**
     * 详情板宽度：
     *  · 表单/列表型详情 → 固定 [BoardLayout.DetailWidth]（380dp）；
     *  · 内容型详情（文件预览、笔记编辑器）→ 取内容宽的 40%，夹在 380~480dp。
     *    宽屏给足读长，窄一点也不会比侧栏还窄。
     */
    fun detailWidthFor(contentWidth: Dp, content: Boolean): Dp =
        if (!content) BoardLayout.DetailWidth
        else (contentWidth * 0.40f).coerceIn(BoardLayout.DetailWidth, BoardLayout.ContentDetailWidth)

    /**
     * 分栏判定：**先算宽度够不够，再看选中态**。
     * 宽度不够时选中态没有任何分栏含义——直接 [DetailPane.Single]，
     * 页面继续走原来的全屏二级页路径。
     */
    fun paneOf(contentWidth: Dp, detailWidth: Dp, hasSelection: Boolean): DetailPane = when {
        contentWidth - detailWidth < BoardLayout.MinListWidth -> DetailPane.Single
        hasSelection -> DetailPane.Detail
        else -> DetailPane.Overview
    }
}

/**
 * 两栏骨架。`twoPane = false` 时**只渲染 [primary]**——手机路径由页面自己保持原样，
 * 这个壳只在宽屏介入。详情板宽度走 [detailWidth]（内容型页面传 `detailWidthFor(..., true)`）。
 *
 * v69（2026-11-21「框不动」修正）：[transparent] = true 时**只留几何与裁剪，不画板底**——
 * 一级页在壳层 pager 里，两块圆角板的「相框」由壳层固定绘制（框不动），页面内容作为
 * 「纸条」画在框上、随切页纵向滑动。透明模式同时**去掉外距与系统栏 inset**（由壳层的
 * 框区裁剪盒统一提供，页面内容行从框顶开始，与框逐像素对位）；`clip(shape)` 保留——
 * 板内滚动内容仍被裁进圆角框内。模态卡里的两板（DataHub/记忆库/自动化/文件库等
 * 不在壳层 pager 里）保持默认 false，自带板底。
 */
@Composable
fun SplitBoards(
    twoPane: Boolean,
    modifier: Modifier = Modifier,
    detailWidth: Dp = BoardLayout.DetailWidth,
    /**
     * 定宽那一板放**左边**（默认在右）。
     * 笔记页是反过来的形态：左边是宽的编辑器、右边是定宽的笔记列表。
     */
    fixedFirst: Boolean = false,
    /**
     * 顶部/底部系统栏 inset 由这个壳自己加（默认 true）。
     * 宿主是 **Scaffold**（pad 已含系统栏）时要传 false——再叠一遍就是双倍空白。
     */
    applyTopInset: Boolean = true,
    applyBottomInset: Boolean = true,
    /** v69：板底由壳层固定框绘制（见 KDoc）。壳层 pager 里的一级页传 true。 */
    transparent: Boolean = false,
    primary: @Composable BoxScope.() -> Unit,
    secondary: @Composable BoxScope.() -> Unit = {},
) {
    val navAvoid = io.github.m0use001.planability.app.navAvoidPadding().calculateBottomPadding()
    val boardColor = MaterialTheme.colorScheme.surfaceContainerLow
    val shape = RoundedCornerShape(BoardLayout.Shape)
    if (twoPane && transparent) {
        // v69 透明模式：无外距/inset/底色（框在壳层），两列精确填满壳层的框区裁剪盒；
        // clip(shape) 保留——板内滚动内容仍裁进圆角框内。
        Row(modifier.fillMaxSize()) {
            if (fixedFirst) {
                Box(Modifier.width(detailWidth).fillMaxHeight().clip(shape), contentAlignment = Alignment.TopCenter) { secondary() }
                Spacer(Modifier.width(BoardLayout.Gap))
                Box(Modifier.weight(1f).fillMaxHeight().clip(shape), contentAlignment = Alignment.TopCenter) { primary() }
            } else {
                Box(Modifier.weight(1f).fillMaxHeight().clip(shape), contentAlignment = Alignment.TopCenter) { primary() }
                Spacer(Modifier.width(BoardLayout.Gap))
                Box(Modifier.width(detailWidth).fillMaxHeight().clip(shape), contentAlignment = Alignment.TopCenter) { secondary() }
            }
        }
        return
    }
    Row(
        modifier
            .fillMaxSize()
            .then(
                if (twoPane) Modifier
                    .padding(
                        start = BoardLayout.Gap,
                        end = BoardLayout.Gap,
                        top = BoardLayout.Gap - 4.dp,
                        bottom = if (applyBottomInset) navAvoid else 0.dp
                    )
                    .then(if (applyTopInset) Modifier.statusBarsPadding() else Modifier)
                else Modifier
            )
    ) {
        val fixedModifier = Modifier.width(detailWidth).clip(shape).background(boardColor)
        val flexibleModifier = Modifier
            .weight(1f)
            .then(if (twoPane) Modifier.clip(shape).background(boardColor) else Modifier)
        if (twoPane && fixedFirst) {
            Box(fixedModifier.fillMaxHeight(), contentAlignment = Alignment.TopCenter) { secondary() }
            Spacer(Modifier.width(BoardLayout.Gap))
            Box(flexibleModifier.fillMaxHeight(), contentAlignment = Alignment.TopCenter) { primary() }
        } else {
            Box(flexibleModifier.fillMaxHeight(), contentAlignment = Alignment.TopCenter) { primary() }
            if (twoPane) {
                Spacer(Modifier.width(BoardLayout.Gap))
                Box(fixedModifier.fillMaxHeight(), contentAlignment = Alignment.TopCenter) { secondary() }
            }
        }
    }
}

/**
 * v69：壳层框区的**内容裁剪盒**（框不动、内容动——纸条在这个盒里纵向滑动）。
 * 外距与系统栏 inset 公式与 [SplitBoards] 的框外距**逐字节同源**（同一文件，改一处必须
 * 同步另一处，否则页面内容与框错位）。框隐（[io.github.m0use001.planability.core.ShellChrome.boardFrames]
 * = false 的单列形态）时壳层不套这个盒，直接整页区。
 *
 * v70g 备注（2026-11-21 用户「又出现了从框上方飞出的问题」）：纵向切页时纸条带着板的
 * 圆角从框顶上方/框底下方探进墙区——这层裁剪盒裁不到（墙在盒外）。修法在壳层：
 * NavShell 在切页动画进行中给框顶上/框底下两条墙带盖底色（见 NavShell「墙带遮罩」），
 * 纸条看起来从相框后面穿行。不能在这里做逐框并集裁剪——v68 起宽屏 modal 卡
 * （PredictiveBackOverlay 页内整页层，居中 1000dp）横跨缝区，逐框裁剪会把卡腰斩。
 */
@Composable
fun Modifier.boardFrameViewport(
    applyTopInset: Boolean = true,
    applyBottomInset: Boolean = true,
): Modifier {
    val navAvoid = io.github.m0use001.planability.app.navAvoidPadding().calculateBottomPadding()
    return this
        .padding(
            start = BoardLayout.Gap,
            end = BoardLayout.Gap,
            top = BoardLayout.Gap - 4.dp,
            bottom = if (applyBottomInset) navAvoid else 0.dp
        )
        .then(if (applyTopInset) Modifier.statusBarsPadding() else Modifier)
        .clipToBounds()
}

/**
 * 板头：标题 +（可选）副标题 + 前后插槽，直接长在板面上。
 * 宽屏**不要**在板内再嵌 `M3ETitleBar`（那是浮起圆角 + 投影 = 第二种材质）。
 */
@Composable
fun BoardHeader(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    /**
     * 副标题的**富内容**插槽（会话页的胶囊行用）。
     *
     * 与 [subtitle] 互斥：给了这个就忽略 [subtitle]。
     * 为什么要富版本：板头副标题不总是一句话——会话页那行是「权限档位 + 上下文占比」
     * 两枚胶囊，纯字符串装不下，而板头是内容区的一部分（不是浮起栏），
     * 所以不能用 M3ETitleBar 的 subtitleContent 那一套（它带状态栏高度与折叠动画）。
     */
    subtitleContent: (@Composable () -> Unit)? = null,
    leading: (@Composable () -> Unit)? = null,
    actions: (@Composable () -> Unit)? = null,
    /**
     * 整行点击（首页原来的「点顶栏进日历」就是这条语义）。
     *
     * 给了就整块板头可点；leading/actions 里的按钮会先消费掉自己的点击，
     * 不会连带触发整行。
     */
    onClick: (() -> Unit)? = null,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (onClick != null) Modifier.clickable { onClick() }
                else Modifier
            )
            .padding(
                start = if (leading != null) 4.dp else BoardLayout.Padding,
                end = 8.dp,
                top = 8.dp,
                bottom = 8.dp
            )
    ) {
        leading?.invoke()
        // v69（2026-11-21 用户「标题没有对齐」）：标题列恒定 ≥48dp（与 IconButton 同高）——
        // 带 actions 的板头行高被按钮撑到 64dp、标题在行内居中；没 actions 的板头行矮一截，
        // 标题就浮高 ~10dp，两块板的板头不在同一水平线（专注页左右板实锤）。
        // 标题列钉同一最小高后，无论带不带按钮，标题中心都落在行顶下 8+24dp 同一水平线。
        Column(
            Modifier.weight(1f).heightIn(min = 48.dp),
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center
        ) {
            Text(
                title,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (subtitleContent != null) {
                Spacer(Modifier.height(4.dp))
                subtitleContent()
            } else if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        actions?.invoke()
    }
}

/**
 * v70（2026-11-21 用户截图「还有很多地方框是不完整的」）：宽屏「纸条」细节瓦片里的子页顶栏。
 *
 * 瓦片是透明的（相框由壳层固定绘制）——子页里嵌整屏版 `TopAppBar` 会把它自带的
 * surface 底色带到瓦片里：框顶被盖出一条直角色条、圆角看不见、板底断色 =「框不完整」。
 * 瓦片内一律用 [BoardHeader]（与所在板板头同材质同版式、标题同线）；
 * 整屏模态形态（!deferBack）继续走 TopAppBar。
 */
@Composable
fun BoardSubHeader(
    title: String,
    onBack: () -> Unit,
    actions: (@Composable () -> Unit)? = null,
) {
    BoardHeader(title = title, leading = { HapticBackButton(onBack) }, actions = actions)
}

/**
 * 板内分组标题（替代「主色小标题 + 一张浮起 Card」的旧组合）。
 *
 * 为什么板内不再用 Card：板本身已经是「圆角矩形 + 容器底」，
 * 板里再嵌一张同材质的圆角卡就是**两种材质叠在一起**——那正是宽屏
 * 「内容块割裂」的来源。板内的分组靠**标题 + 扁平行 + 细线**表达层次。
 */
@Composable
fun BoardSectionTitle(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.SemiBold,
        modifier = modifier.padding(start = BoardLayout.Padding, top = 12.dp, bottom = 4.dp)
    )
}

/** 板内分节线（板头 / 控制区 与列表之间）。 */
@Composable
fun BoardDivider() {
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
}

/** 分组列表的行间细线（两端缩进 [BoardLayout.RowDividerInset]）。 */
@Composable
fun BoardRowDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(horizontal = BoardLayout.RowDividerInset),
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
    )
}
