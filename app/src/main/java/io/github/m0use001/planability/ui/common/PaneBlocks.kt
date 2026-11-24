package io.github.m0use001.planability.ui.common

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.m0use001.planability.ui.theme.ShapeTokens

/**
 * v72：宽屏右板/副框的**图形词汇表**（2026-11-21 用户「文字太多了，仅靠排版难以区分，
 * 应该加入圆角矩形来区分」+ 拍板一次全扫 12 个双板宿主）。
 *
 * 材质纪律：板内禁浮起 Card（SplitBoard.kt 板已是圆角矩形+容器底，再嵌卡=第二种材质；
 * PlanWidePaneGuardTest 也钉着禁 Card()）。本套组件是**扁平色调块**——同材质家族的
 * surfaceContainer 系平铺、无描边无阴影，既给每个区一个「圆角矩形信封」又不割裂。
 * 先例：NotesRecentPane 的行级块（clip 14dp + surfaceContainer 0.55）与 PlanStatCell
 * （surfaceContainerHigh 12dp），本文件把它们收编成共享词汇并统一形状走 [ShapeTokens]。
 *
 * 每板圆角块 ≤4（防密度反向爆炸）；进度色只走 primary 家族（轨 surfaceVariant）。
 */

/**
 * 板内一律用**同一个不透明色阶** surfaceContainerHigh（v72.1：用户实拍「圆角矩形颜色不同
 * 导致排版反而更乱」——节块半透明 0.55、入口块 High、统计区自带同色底=三种色在打架）。
 * 板底是 surfaceContainerLow，块统一高一阶：一块块同色、靠留白分块，克制且干净。
 */
@Composable
private fun Modifier.paneBlockBg(): Modifier = this
    .clip(ShapeTokens.listRow)
    .background(MaterialTheme.colorScheme.surfaceContainerHigh)

/**
 * 节级：**一行标题 + 一个圆角矩形**（v72.2 用户「严格改成一行标题+一个圆角矩形」——
 * 标题在矩形**外**，所有内容装进同一枚矩形里；不再把标题塞进块内，也不再一格一块）。
 */
@Composable
fun PaneSectionBlock(
    title: String,
    modifier: Modifier = Modifier,
    titleTrailing: (@Composable RowScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                title,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            if (titleTrailing != null) titleTrailing()
        }
        Spacer(Modifier.height(6.dp))
        Column(
            Modifier
                .fillMaxWidth()
                .paneBlockBg()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            content = content
        )
    }
}

/** 独立节标题行（块由 [PaneStatRing]/[TodayStatsCard] 这类自带矩形提供时用）。 */
@Composable
fun PaneTitleRow(
    title: String,
    modifier: Modifier = Modifier,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            title,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        if (trailing != null) trailing()
    }
    Spacer(Modifier.height(6.dp))
}

/**
 * 数值行：**一个**圆角矩形里并排 N 格（v72.2 严格版——原来是 N 个独立小矩形，实拍「太挤」）。
 * 格间用细竖线分隔（outlineVariant 0.5），值 titleSmall Bold + 标签 labelSmall。
 */
@Composable
fun PaneStatRow(
    items: List<Pair<String, String>>,
    modifier: Modifier = Modifier,
) {
    if (items.isEmpty()) return
    Row(
        modifier
            .fillMaxWidth()
            .paneBlockBg()
            .padding(vertical = 12.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        items.forEachIndexed { i, (value, label) ->
            if (i > 0) {
                Box(
                    Modifier
                        .width(1.dp)
                        .height(28.dp)
                        .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                )
            }
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    value,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            }
        }
    }
}

/**
 * 内容级圆角块（无标题）：节标题由外面的 FocusBoardSection/标题行承担时，
 * 只给内容一个色调信封。定高不滚的板（专注右板）用它控高度增量（纵向内距比
 * [PaneSectionBlock] 更紧）。
 */
@Composable
fun PaneContentBlock(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier
            .fillMaxWidth()
            .paneBlockBg()
            .padding(horizontal = 14.dp, vertical = 10.dp),
        content = content
    )
}

/**
 * 空态占位块：同族色调底 + 灰图标/文案居中。替代裸 Text 空态——
 * 「入口无内容应退化不消失」（占位块比一行灰字更像一个「装内容的格子暂时空着」）。
 */
@Composable
fun PaneEmptyBlock(
    text: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
) {
    Box(
        modifier
            .fillMaxWidth()
            .paneBlockBg()
            .heightIn(min = 64.dp)
            .padding(horizontal = 14.dp, vertical = 16.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                Icon(
                    icon, null,
                    Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
                Spacer(Modifier.width(8.dp))
            }
            Text(
                text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/** 数值小胶囊（非交互点缀）：底 surfaceContainerHighest 0.6（NotesWidePane 类型胶囊同款）。 */
@Composable
fun PaneChip(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        modifier = modifier
            .clip(ShapeTokens.pill)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.6f))
            .padding(horizontal = 8.dp, vertical = 3.dp)
    )
}

/**
 * 环形占比（共享版）：底槽 surfaceVariant、进度 primary、-90° 顺时针、圆头——
 * 从 FilesScreen 的 StorageRing 收编（颜色只走 primary 家族的纪律随行）。
 * 进度带 standard 档弹簧（M3E：数值变化要看得见来路）。
 */
@Composable
fun CompletionRing(
    fraction: Float,
    modifier: Modifier = Modifier,
    sizeDp: Int = 56,
    strokeDp: Int = 6,
) {
    val track = MaterialTheme.colorScheme.surfaceVariant
    val fg = MaterialTheme.colorScheme.primary
    val f by animateFloatAsState(
        targetValue = fraction.coerceIn(0f, 1f),
        animationSpec = Motion.standard(),
        label = "completionRing"
    )
    Canvas(modifier.size(sizeDp.dp)) {
        val stroke = strokeDp.dp.toPx()
        val inset = stroke / 2f
        val arcSize = Size(size.width - stroke, size.height - stroke)
        drawArc(
            color = track, startAngle = 0f, sweepAngle = 360f, useCenter = false,
            topLeft = Offset(inset, inset), size = arcSize,
            style = Stroke(width = stroke, cap = StrokeCap.Round)
        )
        if (f > 0f) {
            drawArc(
                color = fg, startAngle = -90f, sweepAngle = 360f * f, useCenter = false,
                topLeft = Offset(inset, inset), size = arcSize,
                style = Stroke(width = stroke, cap = StrokeCap.Round)
            )
        }
    }
}

/**
 * 完成率主卡（用户拍板的「环+大数字卡」）：块内左边一枚环、右边大字百分数 +
 * 副行（如「27/38 项」/「暂无任务」），可选尾行小胶囊（日程数/待办数）。
 * 0 任务时环只剩轨道圆——本身就是空态图形，不需要另画。
 */
@Composable
fun PaneStatRing(
    fraction: Float,
    headline: String,
    subline: String?,
    modifier: Modifier = Modifier,
    chips: List<String> = emptyList(),
) {
    Column(modifier.fillMaxWidth().paneBlockBg().padding(horizontal = 14.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CompletionRing(fraction)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    headline,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1
                )
                if (!subline.isNullOrBlank()) {
                    Text(
                        subline,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
        if (chips.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                chips.forEach { PaneChip(it) }
            }
        }
    }
}

/** 数值格（大数+小标签）：与所有块同一色阶（surfaceContainerHigh，16dp）。 */
@Composable
fun PaneStatCell(
    value: String,
    label: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .paneBlockBg()
            .padding(vertical = 10.dp, horizontal = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            value,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            maxLines = 1
        )
        Spacer(Modifier.height(2.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1
        )
    }
}

/** 块内入口行（无自带底色——底色由外层 [PaneSectionBlock] 的一枚矩形提供）。 */
@Composable
fun PaneActionRow(
    onClick: () -> Unit,
    icon: ImageVector,
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .clip(ShapeTokens.card)
            .clickable(onClick = onClick)
            .padding(horizontal = 2.dp, vertical = 8.dp)
    ) {
        Icon(icon, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, maxLines = 1)
            Text(
                subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (trailing != null) trailing()
    }
}

/**
 * 可交互行块（独立入口，自带底色）：仅在无节块包裹的单行入口用；多个入口并排/上下
 * 应改用 [PaneSectionBlock] + [PaneActionRow]（一个矩形里多行）。
 */
@Composable
fun PaneActionBlock(
    onClick: () -> Unit,
    icon: ImageVector,
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .paneBlockBg()
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        Icon(icon, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, maxLines = 1)
            Text(
                subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (trailing != null) trailing()
    }
}
