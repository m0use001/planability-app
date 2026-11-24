package io.github.m0use001.planability.ui.common

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.material3.ToggleButtonShapes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.m0use001.planability.core.HapticEngine
import io.github.m0use001.planability.core.LocalHaptics

/**
 * 单选 connected button group——替代 M3E 已弃用的 SegmentedButton
 * （规范原文 2025-05："Use the connected button group instead"，见 M3E 审查报告差距 1）。
 *
 * 按官方 SingleSelectConnectedButtonGroupSample 的写法实现：
 * `ToggleButton` + `ButtonGroupDefaults.connectedLeading/Middle/TrailingButtonShapes()`
 * （`ButtonGroup(...)` 组合项本身是带溢出菜单的另一形态，单选场景官方不用它）。
 * 与旧 SegmentedButton 的形态对齐：40dp 高（ToggleButton 默认即 40dp）、两端全圆、
 * 内角方（4dp，按压收成 2dp 的 expressive 形变）、选中带 ✓；差异是不再有描边
 * （connected 规范为 2dp 间隙的无边框按钮），且**选中段会形变成全圆药丸**（checkedShape=CornerFull）。
 *
 * 配色沿用原 segmentedButtonColors 的理由（primary 家族，不走默认 secondaryContainer 的 +28° 色相；
 * 那个函数随 SegmentedButton 一并移除，理由记在这里）。
 *
 * 触感：**每次点击都给 Select**（2026-10-27 用户「同一行按钮震动效果却不同」——
 * 原来点已选中段静默、点未选中段才振，同一行里两种手感；现在统一成"点了就振"，
 * 与同区筛选芯片「每次点击都振」的口径一致）。
 * 只有**未选中段**才回调 onSelect（点已选中段不重复触发切换）。
 * 单选语义：选中态不翻转。
 *
 * 示例：
 * ```
 * AppButtonGroup(
 *     options = listOf("日程", "待办"),
 *     selectedIndex = if (tab == 0) 0 else 1,
 *     onSelect = { tab = it }
 * )
 * ```
 */
@OptIn(
    ExperimentalMaterial3Api::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class
)
@Composable
fun AppButtonGroup(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    /**
     * 段高（2026-10-25 用户「做紧凑一点」）：ToggleButton 默认 40dp 且被 48dp 最小触控
     * 目标再撑高，计划页三行控制区占了半屏。钉 36dp；个别页面要更大可显式传。
     */
    height: androidx.compose.ui.unit.Dp = 36.dp,
    /**
     * 外部持有的动画位置（0f..lastIndex，可带小数=滑动中间态）。默认 null = 组件内部弹簧。
     *
     * 为什么需要它：调用点若被 `key(...)` 包住整棵重建（计划页 `key(segment)` 换 LazyColumn
     * 消除换段旧行淡出叠影），本组件会被销毁重建，内部 `remember` 的弹簧以**新索引为初值**
     * 直接落位——滑动胶囊一次都不会动（2026-10-29 用户「日程-待办切换还是没动画」就是这个）。
     * 把 Animatable 提到重建范围之外传进来，重建后继续骑同一根弹簧。
     */
    animatedIndex: Float? = null
) {
    val haptics = LocalHaptics.current
    // 2026-10-25 用户「日程-待办切换的水波特效还是溢出」：
    // connected button group 的默认造型是「外端全圆 + 内端 4dp 方角」，但 selected 用的是
    // `connectedButtonCheckedShape` = CornerFull（**两端全圆**）。于是同一个分段有两套轮廓：
    // 按下水波按方角矩形裁剪 → 盖住内端方角；选中态却是圆头的 → 水波看起来"溢出"了选中胶囊。
    // 修法：三态（常态/按下/选中）统一成同一个 stadium，水波、按压态、选中面逐像素同形。
    // 代价是分组不再"内端拼合"（选中段本来就是圆头，视觉无回归）。
    val pillShape = androidx.compose.foundation.shape.RoundedCornerShape(percent = 50)
    val pillShapes = ToggleButtonShapes(
        shape = pillShape,
        pressedShape = pillShape,
        checkedShape = pillShape
    )
    // 2026-10-27 用户「日程-待办切换全是瞬时切换」：两段各自 fade 的 crossfade 太快、读不出动画。
    // 换成**滑动指示胶囊**：选中胶囊是一层独立的 Box，在两段底下滑动（standard 弹簧、轻过冲），
    // 按钮本身恒透明——切段时胶囊"滑过去"而不是"这边亮那边灭"，动画一目了然。
    androidx.compose.foundation.layout.BoxWithConstraints(modifier.fillMaxWidth()) {
        val gap = ButtonGroupDefaults.ConnectedSpaceBetween
        val cellW = (maxWidth - gap * (options.size - 1)) / options.size
        // v53.2：段数 ≥3 时省掉选中 ✓ —— 滑动胶囊本身已经指明"当前在哪段"，
        // 勾只是重复语义，却白吃 26dp（560dpi 机型三段每格 ~101dp，勾 + 4 字标签必裁字）。
        val showCheck = options.size < 3
        Box {
            // 指示胶囊（画在按钮层下面）：位置随「动画索引」滑动。
            // 内部动画用 Float 索引而不是 Dp 坐标：外部注入的 animatedIndex 也是 Float 索引，
            // 两条路共用同一个几何公式 (cellW + gap) × 索引，位移/宽度口径不会分叉。
            val internalIndex by androidx.compose.animation.core.animateFloatAsState(
                targetValue = selectedIndex.toFloat(),
                animationSpec = Motion.standard(),
                label = "segIndicatorIndex"
            )
            val lastIndex = (options.size - 1).coerceAtLeast(0).toFloat()
            val slideIndex = (animatedIndex ?: internalIndex).coerceIn(0f, lastIndex)
            val indicatorX = (cellW + gap) * slideIndex
            Box(
                Modifier
                    .offset(x = indicatorX)
                    .width(cellW)
                    .height(height)
                    .clip(pillShape)
                    .background(MaterialTheme.colorScheme.primaryContainer)
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(gap),
                verticalAlignment = Alignment.CenterVertically
            ) {
                options.forEachIndexed { index, label ->
                    val selected = index == selectedIndex
                    // 内容色随选中态过渡：胶囊滑到谁头上，谁的字/勾变成 onPrimaryContainer
                    val animContent by animateColorAsState(
                        if (selected) MaterialTheme.colorScheme.onPrimaryContainer
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        animationSpec = Motion.effects(),
                        label = "segContent"
                    )
                    ToggleButton(
                        checked = selected,
                        onCheckedChange = {
                            // 点了就振（含已选中段，口径统一，用户「同一行按钮震动效果却不同」）；
                            // 只有未选中段才真正切换
                            haptics.perform(HapticEngine.Kind.Select)
                            if (!selected) onSelect(index)
                        },
                        shapes = pillShapes,
                        // 36dp 段高下默认纵向内边距会把文字下缘截掉（用户 2026-10-25 截图）——
                        // 纵向收 0，文字/图标交给按钮的 Row 居中。
                        // v53.2 横向也分段数收（3 段起 10dp）：固定 16dp 时「模型设置」这类 4 字标签
                        // 在 560dpi 机型上被选中勾挤到裁字（模拟器实测「模型设」「能力与」）。
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(
                            horizontal = if (options.size >= 3) 10.dp else 16.dp, vertical = 0.dp
                        ),
                        // 容器恒透明：选中面由底下的滑动胶囊负责，避免两层选中面打架
                        colors = ToggleButtonDefaults.colors(
                            containerColor = Color.Transparent,
                            contentColor = animContent,
                            checkedContainerColor = Color.Transparent,
                            checkedContentColor = animContent
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .height(height)
                            .pressScale()
                            .semantics { role = Role.RadioButton }
                    ) {
                        // ✓ 随选中弹性进出（expandHorizontally/shrinkHorizontally）：原来瞬间插进/抽走
                        // 会把文字横向挤一下，动画感全无；现在文字被平滑推开/合拢。
                        AnimatedVisibility(
                            visible = selected && showCheck,
                            enter = fadeIn(Motion.standardEffects()) + expandHorizontally(Motion.standardSpatial()),
                            exit = fadeOut(Motion.quickEffects()) + shrinkHorizontally(Motion.quickSpatial())
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Rounded.Check, contentDescription = null)
                                Spacer(Modifier.width(ToggleButtonDefaults.IconSpacing))
                            }
                        }
                        Text(label, maxLines = 1)
                    }
                }
            }
        }
    }
}
