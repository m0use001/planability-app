package io.github.m0use001.planability.ui.common

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.m0use001.planability.core.HapticTicks
import io.github.m0use001.planability.core.LocalHaptics
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * 色相色环：拖动指针挑颜色。
 *
 * 为什么不用滑块：色相是一条**环**，滑块是一条线——滑到最右和最左是相邻的两种颜色
 * （品红与红），但在线上它们隔着整个量程，一眼看不出"其实挨着"。色环把它画成本来的样子，
 * 哪个颜色在哪一眼可见，也不用拖着找起点。
 *
 * 按下即取色（`detectDragGestures` 的 onDragStart 在 down 时就触发），所以轻点也能用，
 * 拖动则连续跟手。
 */
@Composable
fun HueRing(
    hue: Float,
    onHueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    diameter: Dp = 200.dp,
    ringWidth: Dp = 26.dp,
) {
    val haptics = LocalHaptics.current
    // 每跨 10° 振一次（共 36 格）：连续振=一片麻木，跨格振才对得上"颜色跳了一档"
    val ticks = remember(haptics) { HapticTicks(haptics, 10f) }

    // sweepGradient 首尾要同色，否则 0°/360° 接缝处有一道明显色差
    val ringBrush = remember {
        val steps = 36
        Brush.sweepGradient(List(steps + 1) { i -> Color.hsv(i * 360f / steps, 0.72f, 0.92f) })
    }
    val curColor = categoryColor(hue)

    Canvas(
        modifier = modifier
            .size(diameter)
            .pointerInput(Unit) {
                // pointerInput 作用域自带 size（IntSize），不用再从外面传进来
                fun pick(p: Offset) {
                    val c = Offset(size.width / 2f, size.height / 2f)
                    var deg = Math.toDegrees(atan2((p.y - c.y).toDouble(), (p.x - c.x).toDouble())).toFloat()
                    if (deg < 0f) deg += 360f
                    ticks.onValue(deg)
                    onHueChange(deg)
                }
                detectDragGestures(
                    onDragStart = { pick(it) },
                    onDrag = { change, _ -> pick(change.position) }
                )
            }
    ) {
        val c = center
        val r = this.size.minDimension / 2f
        val stroke = ringWidth.toPx()
        val ringR = r - stroke / 2f

        drawCircle(brush = ringBrush, radius = ringR, center = c, style = Stroke(width = stroke))

        // 当前色相在环上的位置，画个指示点
        val rad = hue * (Math.PI / 180.0)
        val p = Offset(
            c.x + (cos(rad) * ringR).toFloat(),
            c.y + (sin(rad) * ringR).toFloat()
        )
        drawCircle(Color.White, radius = stroke * 0.55f, center = p)
        drawCircle(curColor, radius = stroke * 0.32f, center = p)

        // 环心放当前色的实心预览，看颜色不用眯眼
        drawCircle(curColor, radius = ringR - stroke - 12.dp.toPx())
    }
}