package io.github.m0use001.planability.ui.common

import androidx.compose.foundation.ScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.dp

/** 滚动条几何（2026-11-14 用户：滑块要与轨道同高——统一 6dp，形态差只靠颜色） */
private val TRACK_H = 6.dp
private val THUMB_MIN = 40.dp

/**
 * 横向滚动条（2026-11-13 三轮校准：①要看得见能滑 ②要真滑块不是灰线 ③要能读出
 * 滑到哪了、且是 **M3E 风格**——用户否掉了 Windows 桌面样式）。
 *
 * M3 映射（语料无 scrollbar 组件规范，按色彩角色 + 项目 R11「装饰件中性化」约定）：
 * - **轨道**：4dp 细 pill，`surfaceContainerHighest`（M3 的"静默填充"角色，两主题
 *   都可读不抢眼），全宽 = 内容全长的标尺；
 * - **滑块**：8dp 圆 pill（比轨道厚、居中骑在轨道上=经典双层位置感），`onSurfaceVariant`
 *   （中性，不用 primary——装饰件不上强调色）。
 *
 * **自适应**：内容装得下（maxValue==0）什么都不画；装不下时常显（要的就是看得见）。
 * 滑块宽 = 视口²/内容宽（真滚动条口径），封顶 50% 视口（永远留走程）、保底 40dp。
 *
 * 用法：与 horizontalScroll **同一个** ScrollState 实例，挂在它**之前**（外层=视口层，
 * 画出来的 track/thumb 用视口坐标、盖在滚动内容之上）。挂在 horizontalScroll 之后是
 * 内容层——size=内容宽，thumb 会被裁得只剩视口大、看着永远是一条灰线（2026-11-13
 * 模拟器 logcat 实锤 w=2930=内容宽，五个接入点同修）：
 * ```
 * val hs = rememberScrollState()
 * Row(Modifier.hScrollHint(hs).horizontalScroll(hs)) { … }
 * ```
 * 纯 drawWithContent：state 是 Snapshot 状态、绘制期读取自动失效重绘，零重组、零布局。
 */
/**
 * 滑块宽度（像素）。纯函数，供 JVM 单测穷举边界。
 *
 * 口径：视口²/内容宽（真滚动条比例）→ 封顶视口 50%（永远留走程）→ 保底 40dp。
 * **保底与封顶必须按同一上限收敛**：视口本身比 2×保底还窄时，保底要退到封顶值，
 * 否则 `coerceIn(min, max)` 拿到空区间会抛异常（2026-11-18 真机闪退根因）。
 */
internal fun thumbWidthPx(viewportPx: Float, rangePx: Int, minPx: Float): Float {
    if (viewportPx <= 0f) return 0f
    val maxW = viewportPx * 0.5f
    val minW = minOf(minPx, maxW)
    val ratioW = viewportPx * (viewportPx / (viewportPx + rangePx.coerceAtLeast(1)))
    return ratioW.coerceIn(minW, maxW)
}

@Composable
fun Modifier.hScrollHint(state: ScrollState): Modifier {
    // 同一基色的透明度阶梯：轨道 10% / 滑块 45% —— 两主题下亮度差都 ≥0.35，滑块
    // 永远可辨。不用 surfaceContainerHighest vs onSurfaceVariant 的角色对：实测本主题
    // 两者 Δluma ≈0（同 phone-b 小组件 blockBg 0.03 的坑），滑块会溶进轨道。
    val trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f)
    val thumbColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)
    return drawWithContent {
        drawContent()
        val range = state.maxValue
        if (range <= 0) return@drawWithContent   // 装得下：不画
        val w = size.width
        val barH = TRACK_H.toPx()
        // 2026-11-14 用户「滑块应该跟滑动条一样高」：同高同 y，形态差只靠颜色（10% vs 45%）
        val y = size.height - barH - 3.dp.toPx()
        drawRoundRect(
            color = trackColor,
            topLeft = Offset(0f, y),
            size = Size(w, barH),
            cornerRadius = CornerRadius(barH / 2f, barH / 2f)
        )
        // 滑块：比例宽（封顶 50% 视口永远留走程、保底 40dp），位置随滚动
        // 2026-11-18 闪退根修：视口窄于 2×THUMB_MIN（如 58dp 的公式横滑区）时
        // 保底值 > 封顶值，coerceIn 收到空区间直接抛 IllegalArgumentException
        // （真机 3.5x：min 140px > max 101.5px，进会话绘制首帧必崩）。
        val thumbW = thumbWidthPx(w, range, THUMB_MIN.toPx())
        val x = (w - thumbW) * (state.value.toFloat() / range)
        drawRoundRect(
            color = thumbColor,
            topLeft = Offset(x, y),
            size = Size(thumbW, barH),
            cornerRadius = CornerRadius(barH / 2f, barH / 2f)
        )
    }
}
