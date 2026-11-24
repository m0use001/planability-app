package io.github.m0use001.planability.ui.common

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import io.github.m0use001.planability.R
import io.github.m0use001.planability.data.Graph
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 自定义开屏动画（2026-10-28 用户要求）。冷启动时盖在最上层播一次：
 * 应用图标 + 名称按用户选的预设动效浮现，随后整层退场；点按任意处跳过。
 *
 * ## 为什么用浮层而不是系统 SplashScreen
 * 系统 splash（Android 12+）的图标/退出动画样式受限（AVD + 系统品牌位），
 * 且 ColorOS 还会再套一层自家开屏逻辑；浮层画在应用主题里，
 * 背景/字体/动效曲线全走本应用的 M3E 弹簧体系（Motion.kt），才叫"自定义"。
 *
 * ## 时机与设置
 * `launch.anim`：0 关 / 1 淡入浮现 / 2 缩放浮现 / 3 上滑浮现（默认 1）。
 * snapshot 在 Application 里异步 load()，首帧可能还是默认值——所以动效启动前
 * 先等 `Graph.settings.loaded`（1.5s 兜底），用户设了"关"就一帧都不播。
 * 只在 Activity 冷启动组合时播一次；热启动不重建 Activity，天然不重播。
 *
 * ## 美化层（2026-10-29，全部不新增设置项、盖在原三档之上）
 * - 三档统一的次级缩放：淡入/上滑档图标也带 0.96→1 的轻微弹簧（standard 档，轻过冲），
 *   缩放档仍走 emphasized（0.6→1 回弹）——三档都有"活"感，不只是透明度硬切。
 * - 图标底光晕：primaryContainer 径向柔光，与图标同根弹簧扩散开（视觉上先亮一圈再见图标）。
 * - 落定涟漪：图标弹簧落位后两圈细描边从图标边缘线性扩散渐隐（几何扩散用线性 tween，
 *   弹簧的变速会让涟漪"弹"出去，不像水波）。
 * - 标题逐字上浮：一根整体进度 Animatable 按字符切窗（错峰 6%、窗宽 52%），
 *   每字独立上浮 + 淡入，替代整串跟着浮层淡入。
 * - 退场"飞入应用"：整层淡出时图标再放大 8%，暗示进入应用；上滑档从"上移 32dp 再淡出"
 *   改为整层真正滑出屏。点按跳过保留。
 * - **等第一帧再起表**（2026-10-30 修"没展示完就直接进软件"）：冷启动主线程初始化停顿
 *   发生在组合后、前几帧之间，动画按墙钟推进会被整段吃掉；`withFrameNanos` 等一帧后
 *   从干净状态起播。涟漪 650→500ms、停留 260/340→220/280ms 同步收紧（总长 ~1.5s）。
 */
@Composable
fun LaunchOverlay() {
    // null = 设置还没装载，先不决定播不播
    var playing by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(Unit) {
        withTimeoutOrNull(1500) {
            while (!Graph.settings.loaded) delay(16)
        }
        playing = Graph.settings.snapshot.value.launchAnim != 0
    }
    val kind = Graph.settings.snapshot.collectAsState().value.launchAnim
    if (playing != true || kind == 0) return

    val enter = remember { Animatable(0f) }   // 整层透明度/上滑入场
    val icon = remember { Animatable(0f) }    // 图标缩放弹簧（三档共用，A1）
    val title = remember { Animatable(0f) }   // 标题逐字总进度（A4）
    val ripple = remember { Animatable(0f) }  // 落定涟漪（A3）
    val exit = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    fun finish() {
        if (exit.value > 0f) return
        scope.launch {
            exit.animateTo(1f, Motion.quickEffects())
            playing = false
        }
    }
    LaunchedEffect(kind) {
        // 等第一帧真正画出来再起表（2026-10-30 修"没展示完就直接进软件"）：
        // 冷启动主线程的初始化停顿发生在组合后、前几帧之间（真机实测 t=50→450ms），
        // 弹簧/补间按墙钟推进——不等一帧，整段入场会被停顿整段吃掉，用户只看到
        // 尾巴（涟漪/停留）然后进软件。等一帧 = 动画从停顿结束后的干净状态起播。
        androidx.compose.runtime.withFrameNanos { }
        coroutineScope {
            launch {
                enter.animateTo(1f, if (kind == 2) Motion.emphasized() else Motion.quickEffects())
            }
            val rippleJob = launch {
                icon.animateTo(1f, if (kind == 2) Motion.emphasized() else Motion.standard())
                // A3 涟漪：图标弹簧落位后才起（同一协程顺序执行，不需要 join）
                ripple.animateTo(1f, tween(500, easing = LinearEasing))
            }
            // 标题稍晚于图标起步，逐字错峰交给下面的切窗公式
            val titleJob = launch {
                delay(if (kind == 2) 180L else 120L)
                title.animateTo(1f, Motion.standard())
            }
            // 停留到点后**等动画本体收尾**再退场。旧写法固定 220/280ms 就切走：
            // 标题在 t≈120/180 才起步、弹簧 ~350ms 才落位，最后几个字母每次都被切一半
            // （用户 2026-10-30 两次实锤「最后几个字母没浮上来就进软件」）；
            // 涟漪是实体圆环，切一半同样看得见，一并等完。
            delay(if (kind == 1) 220L else 280L)
            titleJob.join()
            rippleJob.join()
        }
        finish()
    }

    val dark = isSystemInDarkTheme()
    // Canvas 的 draw 块不是组合上下文，颜色必须在这里先取出
    val primaryColor = MaterialTheme.colorScheme.primary
    Box(
        Modifier
            .fillMaxSize()
            .pointerInput(Unit) { detectTapGestures { finish() } }
            .graphicsLayer {
                // 整层退场：一律淡出；上滑档整层滑出屏（B1）
                alpha = 1f - exit.value
                translationY = if (kind == 3) -size.height * exit.value else 0f
            }
            // 与应用根背景同一套：都是 colorScheme.surface（= 左侧 Rail 底色）
            .background(androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.surface))
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.align(Alignment.Center)
        ) {
            // 光晕/涟漪的画布比图标大一圈（200dp），图标居中叠在上面
            Box(Modifier.size(200.dp), contentAlignment = Alignment.Center) {
                // A2 底光晕：先于图标"亮开一圈"，同一根缩放弹簧驱动
                Spacer(
                    Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            alpha = icon.value.coerceIn(0f, 1f)
                            val p = 0.55f + 0.45f * icon.value
                            scaleX = p
                            scaleY = p
                        }
                        .background(
                            Brush.radialGradient(
                                listOf(
                                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.40f),
                                    androidx.compose.ui.graphics.Color.Transparent
                                )
                            )
                        )
                )
                // A3 落定涟漪：两圈细描边从图标边缘扩散、渐隐
                Canvas(Modifier.fillMaxSize()) {
                    val iconR = 48.dp.toPx()
                    val maxR = size.minDimension / 2f
                    listOf(0f, 0.18f).forEach { lag ->
                        val p = ((ripple.value - lag) / (1f - lag)).coerceIn(0f, 1f)
                        if (p > 0f && p < 1f) {
                            drawCircle(
                                color = primaryColor.copy(alpha = 0.32f * (1f - p)),
                                radius = iconR + (maxR - iconR) * p,
                                style = Stroke(width = 2.5.dp.toPx() * (1f - p) + 0.5f)
                            )
                        }
                    }
                }
                // 启动器图标复刻：底（渐变）+ 前（几何），当前主题对应的明暗套
                Box(
                    Modifier
                        .size(96.dp)
                        .graphicsLayer {
                            val p = icon.value
                            val base = if (kind == 2) 0.6f + 0.4f * p else 0.96f + 0.04f * p
                            // B1：退场轻放大，"飞进应用"
                            val s = base * (1f + 0.08f * exit.value)
                            scaleX = s
                            scaleY = s
                            if (kind == 3) translationY = 28.dp.toPx() * (1f - p)
                            alpha = enter.value.coerceIn(0f, 1f)
                        }
                        .clip(CircleShape)
                ) {
                    Image(
                        painterResource(if (dark) R.drawable.ic_launcher_bg_night else R.drawable.ic_launcher_bg_day),
                        null, modifier = Modifier.fillMaxSize()
                    )
                    Image(
                        painterResource(if (dark) R.drawable.ic_launcher_fg_pal0_night else R.drawable.ic_launcher_fg_pal0_day),
                        null, modifier = Modifier.fillMaxSize()
                    )
                }
            }
            Spacer(Modifier.height(18.dp))
            // A4 逐字上浮：一根总进度按字符切窗，每字独立 alpha + 上浮 10dp；
            // 整层退场的 alpha 叠在外面。错峰与窗宽**按字母数推导**，保证最后一个字母
            // 恰好在总进度 1.0 落位——旧写死「错峰 6%、窗宽 52%」在 11 个字母时最后
            // 一个字母需要总进度 1.12 才到终值，永远浮不上来（用户 2026-10-30 实锤：
            // 最后几个字母没浮上来就进了软件 → 既有切窗算漏，也有退场不等动画收尾）。
            val word = "Planability"
            val stagger = 0.5f / (word.length - 1).coerceAtLeast(1)
            val letterWindow = 1f - (word.length - 1) * stagger
            Row {
                word.forEachIndexed { i, ch ->
                    val p = ((title.value - i * stagger) / letterWindow).coerceIn(0f, 1f)
                    Text(
                        ch.toString(),
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.graphicsLayer {
                            alpha = p
                            translationY = 10.dp.toPx() * (1f - p)
                        }
                    )
                }
            }
        }
    }
}
