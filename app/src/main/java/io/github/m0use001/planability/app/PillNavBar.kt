package io.github.m0use001.planability.app

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlin.math.roundToInt
import io.github.m0use001.planability.ui.common.Motion
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import io.github.m0use001.planability.core.HapticEngine
import io.github.m0use001.planability.core.LocalHaptics
import kotlinx.coroutines.launch

/** 底栏与屏幕底/左/右的**统一**外边距，见 PillNavBar 里的说明。 */
private val MARGIN = 16.dp

/** 胶囊自身的内边距。外边距要减掉它们，可见胶囊才真的三边等距。 */
private val PILL_INSET_H = 6.dp
private val PILL_INSET_V = 8.dp

data class TabSpec(
    val id: String,
    val label: String,
    val icon: ImageVector
)

/**
 * 药丸形底部导航栏：完全悬浮形态（水平 16dp 边距 + 底部悬空 8dp + 投影），只在一级界面展示。
 * v12：按在**当前页菜单按钮**上并左右滑动（超过触控斜率）即进入候选态——主色描边候选框
 * 锁定手指最近的按钮，跨按钮有触感，松手切换到该页；轻点仍直接切页。
 * v16：滑动期间页面跟手实时切换（onDragProgress 把手指在栏上的横向位置比例交给壳层驱动
 * pager），松手才吸附到候选页。
 * v16.2：吸附式——手指每跨过一次按钮边界，候选框即时跳到候选按钮、页面带左右滑动动画
 * 滑到该页停整；不会停留在页间叠加态。
 * B1（2026-10-24）：曾按 M3 规范把目的地收敛到 5、笔记/文件移工作台抽屉；
 * 当天用户要求「移回来」撤销收敛（7 格全量还原），仅保留药丸指示器形态——
 * 56dp 规范药丸在 7 格下按格宽自适应收缩（见下方注释）。
 */
@Composable
fun PillNavBar(
    tabs: List<TabSpec>,
    current: Int,
    onSelect: (Int) -> Unit,
    onDragProgress: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    val haptics = LocalHaptics.current
    val cur by androidx.compose.runtime.rememberUpdatedState(current)
    var barWidthPx by remember { mutableIntStateOf(0) }
    // 滑动选页候选框：-1 = 未进入
    var hoverIndex by remember { mutableIntStateOf(-1) }
    fun tabIndexAt(x: Float): Int =
        if (barWidthPx <= 0) 0 else (x / barWidthPx * tabs.size).toInt().coerceIn(0, tabs.size - 1)
    Surface(
        color = Color.Transparent,
        modifier = modifier
            // 原来左右固定 16.dp、底部 = 导航栏 inset + 8.dp，三者各算各的。
            // 注意还要减掉胶囊自身的内边距——统一外边距 ≠ 等距，差在内边距这一层。
            .padding(WindowInsets.navigationBars.asPaddingValues())
            .padding(horizontal = (MARGIN - PILL_INSET_H))
            .padding(bottom = (MARGIN - PILL_INSET_V))
            .onSizeChanged { barWidthPx = it.width }
            .pointerInput(tabs.size) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val downX = down.position.x
                    // 只在按到当前页按钮上才启用滑动选页；其他按钮保留轻点
                    val active = tabIndexAt(downX) == cur
                    var crossed = false
                    while (true) {
                        val event = awaitPointerEvent()
                        val pressedChange = event.changes.firstOrNull { it.pressed }
                        if (pressedChange == null) break
                        if (active) {
                            val dx = pressedChange.position.x - downX
                            if (!crossed && kotlin.math.abs(dx) > viewConfiguration.touchSlop) {
                                crossed = true
                                hoverIndex = tabIndexAt(pressedChange.position.x)
                                haptics.perform(HapticEngine.Kind.Rigid)
                            }
                            if (crossed) {
                                pressedChange.consume()
                                if (barWidthPx > 0) {
                                    onDragProgress(pressedChange.position.x / barWidthPx)
                                }
                                val next = tabIndexAt(pressedChange.position.x)
                                if (next != hoverIndex) {
                                    hoverIndex = next
                                    haptics.perform(HapticEngine.Kind.Select)
                                }
                            }
                        }
                    }
                    if (crossed) {
                        val target = hoverIndex
                        hoverIndex = -1
                        if (target in tabs.indices) {
                            // 无论是否回到原页都结算——拖动后 pager 可能停在页间，必须吸附
                            if (target != cur) haptics.perform(HapticEngine.Kind.Rigid)
                            onSelect(target)
                        }
                    }
                }
            }
    ) {
        Box(contentAlignment = Alignment.TopCenter) {
            // 底栏阴影分档：浅色模式下 10dp 纯黑会在浅底上糊出一圈明显的灰晕
            // （真机反馈「正常模式下底部菜单栏有异常阴影」就是它）。
            // 浅色给 4dp + 10% 黑，只保留一点分离感；深色保持 8dp（深底上本来就几乎看不见）。
            val navIsLight = MaterialTheme.colorScheme.surface.luminance() > 0.5f
            val navShadow = Color.Black.copy(alpha = if (navIsLight) 0.10f else 0.45f)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .shadow(
                        elevation = if (navIsLight) 4.dp else 8.dp,
                        shape = RoundedCornerShape(32.dp),
                        clip = false,
                        ambientColor = navShadow,
                        spotColor = navShadow
                    )
                    .clip(RoundedCornerShape(32.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .padding(horizontal = PILL_INSET_H, vertical = PILL_INSET_V),
                horizontalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                tabs.forEachIndexed { index, tab ->
                    // 长按拖动期间高亮跟随候选框（手指最近按钮），否则跟随当前页
                    val selected = index == (if (hoverIndex >= 0) hoverIndex else current)
                    // 选中指示器用**第二色容器**：原生（时钟页底部导航）就是这么配的——
                    // 导航指示属于"面"，不是可操作按钮族（按钮/开关/勾选统一主色）。
                    // 2026-10-24 拼色规则 R4：第二色只用于容器面与导航指示。
                    val bg by animateColorAsState(
                        if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
                        // v16.1：拖动选页期间高亮跟随候选框即时切换（不留渐隐残影）。
                        // P2-5（第二轮审查）：淡出方向一律 snap、淡入方向弹簧——旧 pill 瞬时消失、
                        // 新 pill 弹簧淡入，消除"两枚半透明 pill 并存"的中间帧
                        // （规范 "Fully fade out content before fading new content in"）。
                        animationSpec = if (hoverIndex >= 0 || !selected) snap() else spring(),
                        label = "navBg"
                    )
                    val contentColor = if (selected) MaterialTheme.colorScheme.onSecondaryContainer
                    else MaterialTheme.colorScheme.onSurfaceVariant
                    // 选中胶囊的内边距随选中态弹簧过渡：选中那颗"鼓"一圈，未选中缩回常规尺寸。
                    // v57 批 0：两处裸 spring(0.6/800) 字面量收口进 Motion.quickSpatial()——
                    // 参数与 fastSpatial 完全相同，行为零差，只是不再绕过 Motion 单源
                    // （MotionConventionTest 反向钉：owned 文件禁裸 spring 字面量）。
                    val navPadH by androidx.compose.animation.core.animateDpAsState(
                        targetValue = if (selected) 13.dp else 11.dp,
                        animationSpec = Motion.quickSpatial(),
                        label = "navPadH"
                    )
                    val navPadV by androidx.compose.animation.core.animateDpAsState(
                        targetValue = if (selected) 6.dp else 4.dp,
                        animationSpec = Motion.quickSpatial(),
                        label = "navPadV"
                    )
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                        modifier = Modifier
                            .weight(1f)
                            .height(52.dp),
                    ) {
                        // 2026-10-25 用户「底部菜单栏改成选中特效包住图标+文字」：
                        // 指示器从「只裹图标」改为**整格内容**（图标 + 标签）一起包住。
                        // 内边距固定（选中/未选中一致），内容位置不跳，只有底色在变；
                        // 底色仍是第二色容器（R4：导航指示属于"面"，不是操作按钮）。
                        //
                        // 2026-10-25 二改（用户：选中特效与水波大小不一致）：
                        // clickable 从**整格**移到这层胶囊上——水波纹的尺寸/轮廓跟胶囊完全一致，
                        // 不再出现「大矩形水波 vs 小圆胶囊」两种形状。clip 必须在 clickable 之前
                        // （水波按修改链当时的 clip 裁剪），background 放在 clip 之后才被圆角裁到。
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                            modifier = Modifier
                                .clip(RoundedCornerShape(percent = 50))
                                .background(bg)
                                .clickable {
                                    // 当前页签**也要**上报：再点一次 = 回到顶部（NavShell 里判断）。
                                    // 原来这里 `if (index != current)` 直接把当前页的点击吞掉了，
                                    // 于是"重复点当前页"这个手势根本不存在。
                                    if (index != current) {
                                        // v8：切页触感从 Select(8ms/170) 升级为 Rigid(16ms/230)，更明确
                                        haptics.perform(HapticEngine.Kind.Rigid)
                                    } else {
                                        // 再点当前页 = 回顶，也是一次真实动作，给最轻的 Tap
                                        haptics.perform(HapticEngine.Kind.Tap)
                                    }
                                    onSelect(index)
                                }
                                // v12：滑动选页的候选框——主色描边，醒目区分普通选中态
                                .then(
                                    if (index == hoverIndex) Modifier.border(
                                        2.dp, MaterialTheme.colorScheme.primary,
                                        RoundedCornerShape(percent = 50)
                                    ) else Modifier
                                )
                                // 2026-10-25 用户「选中框大一点」：内边距 8/3 → 11/4dp。
                                // 2026-10-27 用户「选中按钮加大，整体仍是胶囊」：内边距按选中态
                                // 动画放大（11/4 → 13/6，quick 弹簧），选中胶囊明显大一圈、
                                // 未选中保持原尺寸；图标/文字尺寸不动，整栏外轮廓仍是胶囊。
                                .padding(
                                    horizontal = navPadH,
                                    vertical = navPadV
                                )
                        ) {
                            Icon(
                                tab.icon, contentDescription = tab.label,
                                tint = contentColor,
                                modifier = Modifier.size(24.dp)
                            )
                            Text(
                                tab.label,
                                style = MaterialTheme.typography.labelSmall,
                                color = contentColor,
                                maxLines = 1
                            )
                        }
                    }
                }
            }
        }
    }
}
