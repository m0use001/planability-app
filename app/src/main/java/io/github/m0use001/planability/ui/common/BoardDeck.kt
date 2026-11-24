package io.github.m0use001.planability.ui.common

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import io.github.m0use001.planability.ui.common.Motion.pageSlide
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * 「水平纸条」板组里第 [index] 块板的矩形（2026-11-21 用户拍板「笔记三级全做成滑动」）。
 *
 * 一条横向胶片上排着任意多块板，相框（视口）压着**两块**：主框（弹性宽 mainW）+
 * 副框（定宽 secW）。`progress` 是「现在哪块板在主框」（可为小数，动画中）：
 *
 *  · progress == index：这块板在**主框**（x=0，宽 mainW = windowW − secW − gap）。
 *  · progress == index − 1：这块板滑进了**副框**（x = mainW + gap，宽从 mainW 连续
 *    收窄到 secW = 内容逐帧自动改排版）——这是 [drillTiles] 主板几何的推广。
 *  · progress == index + 1：这块板从左缘**滑入主框**（x = −mainW → 0）。
 *  · progress == index − 2：这块板完全滑出**右缘**（x ≥ windowW）。
 *    更深（index 更大）的板停屏外左侧，更浅的停屏外右侧。
 *
 * 纯函数（无 Compose 依赖）：[DeckGeometryTest] 在整数点/中点/出屏/越界钉几何。
 *
 * @param index 板在胶片上的序号（0 = 最浅）
 * @param progress 当前层级（主框里那块板的序号，动画中为小数）
 * @param windowW 视口宽（主+副+缝的总宽）
 * @param secW 副框宽（[BoardLayout.DetailWidth] 口径）
 * @param gap 板间白缝（[BoardLayout.Gap]）
 */
data class DeckSlot(val x: Float, val w: Float)

fun deckSlot(index: Int, progress: Float, windowW: Float, secW: Float, gap: Float): DeckSlot {
    val mainW = (windowW - secW - gap).coerceAtLeast(0f)
    // delta = 0 → 主框；1 → 副框；-1 → 左缘外整板宽；2 → 右缘外
    val delta = progress - index
    return when {
        // 屏外左侧 / 从左缘滑入主框（与 drillTiles 的 detail 完全同式）
        delta <= 0f -> DeckSlot(x = delta * mainW, w = mainW)
        // 主框 → 副框：x 走 mainW+gap，宽从 mainW 连续插值到 secW（「自动改排版」）
        delta <= 1f -> DeckSlot(
            x = delta * (mainW + gap),
            w = mainW + (secW - mainW) * delta
        )
        // 副框 → 滑出右缘（delta=2 时左缘贴视口右缘 = 完全出屏）
        else -> DeckSlot(
            x = (mainW + gap) + (delta - 1f) * secW,
            w = secW
        )
    }
}

/**
 * 「纸条钻入」的**多级**兄弟（[BoardDrill] 是它只有两级时的特例，保留给我的页）。
 *
 * v69d（2026-11-21 用户拍板「笔记三级全做成滑动」）：笔记页宽屏的层级导航——
 * `[笔记本网格|最近笔记]` → 点笔记本 `[笔记列表|笔记本网格]` → 点笔记 `[编辑器|笔记列表]`
 * ——不再整块换内容，而是**相框不动、一条横向纸条整体右抽**：上一级主板滑进副框
 * （宽度连续改排版）、更早的副板滑出右缘、更深一层的板从左缘滑进主框；返回集体左移。
 *
 *  · [level] 目标层级（主框里那块板的序号；外部状态变化即触发抽动）。
 *  · [onBack] 预测返回手势完成时回调（外部把层级状态退一级）。
 *  · [panes] 胶片上的板，按下标从浅到深；下标 0 的板只在 level ≥ 1 时可见（它默认是副框）。
 *
 * 瓦片**透明**（不画板底、无外距/inset）——两块圆角板的相框由壳层固定绘制，瓦片作为
 * 纸条内容画在框上滑动；clip 保留（板内内容裁进圆角）。外距/inset 由壳层的框区裁剪盒
 * （[boardFrameViewport]）统一提供。
 *
 * 返回手势跟手：纸条从 level 被拖回 level−1；取消弹回；完成回调 [onBack]。
 * 门控在 [io.github.m0use001.planability.core.LocalTabActive]——宿主页常驻组合，
 * 别的 tab 上不得吃系统返回。
 *
 * 滑出内容不蒸发的约定：**板内容自己保留最后一次非空数据**（见调用侧 NotesListBoard /
 * NotesEditorBoard 的 lastBook/lastNote），板滑出的路上内容还在，动画走完自然停在屏外。
 */
@Composable
fun BoardDeck(
    level: Int,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    detailWidth: Dp = BoardLayout.DetailWidth,
    panes: List<@Composable () -> Unit>,
) {
    val scope = rememberCoroutineScope()
    // 初值直接落在当前层级：窗口跨过两板阈值（旋转/分屏）瞬间接到深层时不再从 0 播全程。
    val progress = remember { Animatable(level.toFloat()) }
    LaunchedEffect(level) { progress.animateTo(level.toFloat(), pageSlide()) }

    // 系统预测返回跟手：纸条从 level 被拖回 level−1；取消弹回；完成回调 [onBack]
    // （外部退一级状态，上面的 LaunchedEffect 对新 level 的 animateTo 是零距离空转）。
    androidx.activity.compose.PredictiveBackHandler(
        enabled = level > 1 &&
            io.github.m0use001.planability.core.LocalTabActive.current
    ) { prog ->
        try {
            prog.collect { p ->
                progress.snapTo((level - p.progress).coerceIn(level - 1f, level.toFloat()))
            }
            onBack()
        } catch (e: kotlinx.coroutines.CancellationException) {
            scope.launch { progress.animateTo(level.toFloat(), pageSlide()) }
            throw e
        }
    }

    val density = LocalDensity.current
    val gapPx = with(density) { BoardLayout.Gap.toPx() }
    val secWpx = with(density) { detailWidth.toPx() }
    // v77 统一引擎：布局收进 DeckStrip（与 BoardDrill/NavHost 共用同一份 deckSlot 渲染）。
    DeckStrip(panes = panes, progress = progress.value, gapPx = gapPx, secWpx = secWpx, modifier = modifier)
}
