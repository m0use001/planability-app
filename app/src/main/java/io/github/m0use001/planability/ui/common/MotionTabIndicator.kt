package io.github.m0use001.planability.ui.common

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TabPosition
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp

/**
 * M3E 弹簧驱动的 TabRow 指示条——替代 material3 默认的 `tabIndicatorOffset`
 * （那是 material3 内置的定长 tween 硬曲线，不在这套弹簧体系里；全 app 分段/胶囊类选择都已上弹簧，
 * 唯独 5 处二级 TabRow 还是默认指示条，切起来是"匀速挪过去"，与 AppButtonGroup 的
 * 滑动胶囊手感不一致）。
 *
 * 用法（与 TabRow 的 indicator 槽对接）：
 * ```
 * TabRow(selectedTabIndex = tab, indicator = { pos -> MotionTabIndicator(pos, tab) }) { ... }
 * ```
 *
 * 几何与默认 SecondaryIndicator 完全一致：底部通栏、3dp 高、primary 色——只换动画。
 * 宽度与位移用**同一根** defaultSpatial 弹簧（0.8/380，[Motion.standardSpatial]），
 * 与 AppButtonGroup 滑动胶囊同源：切段时指示条"滑过去"并带轻过冲落位。
 * 位移/宽度都是对 tabPositions 的差值动画、不参与文字布局，文字不会被裁；
 * 首帧直接落位（animateDpAsState 初值即目标），无入场闪烁。
 */
@Composable
fun MotionTabIndicator(tabPositions: List<TabPosition>, selectedIndex: Int) {
    if (tabPositions.isEmpty()) return
    val idx = selectedIndex.coerceIn(0, tabPositions.lastIndex)
    val pos = tabPositions[idx]
    val width by animateDpAsState(
        targetValue = pos.width,
        animationSpec = Motion.standardSpatial(),
        label = "tabIndicatorWidth"
    )
    val left by animateDpAsState(
        targetValue = pos.left,
        animationSpec = Motion.standardSpatial(),
        label = "tabIndicatorOffset"
    )
    Box(
        Modifier
            .fillMaxWidth()
            .wrapContentSize(Alignment.BottomStart)
            .offset(x = left)
            .width(width)
            .height(3.dp)
            .clip(RoundedCornerShape(percent = 50))
            .background(MaterialTheme.colorScheme.primary)
    )
}
