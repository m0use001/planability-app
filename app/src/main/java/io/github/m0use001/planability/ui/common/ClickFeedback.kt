package io.github.m0use001.planability.ui.common

import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import io.github.m0use001.planability.ui.theme.ShapeTokens

/**
 * 可点区域的**圆角水波**（2026-11-20 用户「平板模式下许多地方的选中特效是突兀的正方形」）。
 *
 * ## 病根
 *
 * `Modifier.clickable` 的 indication（水波 / 悬停 state layer）由链上**最近的那个 clip**
 * 裁剪。所以只有两种结果：
 * - 链上没 clip  → 水波是**整块矩形**，一张卡片上按下去先出现一个直角方块；
 * - 链上 `clip(0.dp)`（宽屏 flat 行）→ 同样是直角，而且只在平板上出现。
 *
 * 反过来的同族病：`Card(shape = 16.dp, modifier = Modifier.clickable(...))` 里 clickable
 * 挂在卡片**形状裁剪的外面**，水波在四个圆角处戳出去。那种应改用 `Card(onClick = ...)`
 * 重载（M3 自己把 indication 放进 shape 里）。
 *
 * ## 用法
 *
 * 裸 Row/Column/Box 整行可点、又没有自己的圆角底时，用它代替 `.clickable(...)`：
 * ```
 * Modifier.fillMaxWidth().clickableRounded(onClick = onOpen).padding(...)
 * ```
 * 有自己圆角底的容器（`clip(shape).background(color)` 那种）本来就已经裁剪了水波，
 * 不要重复套——外面再 clip 一层只是多一次 graphicsLayer。
 *
 * @param shape 默认 [ShapeTokens.listRow]（16dp，列表行口径）；紧凑行（菜单项、过程链行）
 *   传 [ShapeTokens.card]（12dp）或更小的自定义圆角。
 */
fun Modifier.clickableRounded(
    shape: Shape = ShapeTokens.listRow,
    enabled: Boolean = true,
    onClick: () -> Unit
): Modifier = this.clip(shape).clickable(enabled = enabled, onClick = onClick)

/** [clickableRounded] 的长按版。 */
fun Modifier.combinedClickableRounded(
    shape: Shape = ShapeTokens.listRow,
    enabled: Boolean = true,
    onClick: () -> Unit = {},
    onLongClick: (() -> Unit)? = null
): Modifier = this.clip(shape).combinedClickable(
    enabled = enabled,
    onClick = onClick,
    onLongClick = onLongClick
)

/**
 * 扁平行（宽屏分组列表）的**行底形状**。
 *
 * 宽屏列表行为了「成组」故意去掉卡片底与行间空隙（`flat = true`），但当时把 clip 半径也
 * 一起归零了，于是选中高亮与水波变成直角方块——用户在平板上看到的「突兀的正方形」。
 * 底可以是透明的，**裁剪不能是直的**：透明底 + 圆角裁剪 = 平时看不出形状、按下/选中时是圆角。
 *
 * 手机档（`flat = false`）仍是 16dp 卡片圆角，与原视觉一致。
 */
fun flatRowShape(flat: Boolean): Shape =
    if (flat) RoundedCornerShape(12.dp) else ShapeTokens.listRow
