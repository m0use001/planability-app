package io.github.m0use001.planability.ui.common

import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.m0use001.planability.app.LocalNav
import io.github.m0use001.planability.app.NavController
import io.github.m0use001.planability.core.LocalTabActive
import io.github.m0use001.planability.core.LocalTabId
import io.github.m0use001.planability.ui.common.Motion.pageSlide
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * v77 全局层级栈的**统一宿主**（「钻进板」渲染器）。
 *
 * 把「页面的两块根板 + 栈里每一级」拼成一条横向纸条渲染（复用 [deckSlot] 几何）：
 *  · 根基 = `[副板(概览) , 主板(列表)]`——`rootSecondary`/`rootMain` 两块透明单板，level=1 时
 *    与 [SplitBoards] 两板逐像素一致。
 *  · 之上每 push 一级（单板 pane）= 纸条右抽一格：当前页占主框、上一页滑进副框、更早滑出右缘。
 *  · 返回（手势跟手 / 程序化 pop）= 集体左移；弹出动画走完才摘掉顶级 pane（滑出的路上内容不蒸）。
 *
 * 透明瓦片 + 壳层相框：本宿主只画纸条内容（`clip(圆角)`），两块圆角板的相框由壳层固定绘制
 * （框不动）。Rail 不锁（[LocalNav] 页面不 `LockShellChrome`），全程常驻。
 *
 * 深度唯一真源是 [io.github.m0use001.planability.app.NavState]；本宿主把 tab 的 [NavController]
 * 经 [LocalNav] 下发给所有后代（[io.github.m0use001.planability.ui.common.SubPageTile] 等用它返回）。
 *
 * @param rootSecondary 根层副板（概览）内容
 * @param rootMain 根层主板（列表）内容
 */
@Composable
fun NavHost(
    rootSecondary: @Composable () -> Unit,
    rootMain: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    detailWidth: Dp = BoardLayout.DetailWidth,
) {
    val tab = LocalTabId.current ?: "global"
    val nav = remember(tab) { NavController(tab) }
    val stack = nav.stack
    val scope = rememberCoroutineScope()

    // level = 当前主框里那块 pane 的下标：panes = [sec(0), main(1), sub(2), …] → 根=1、push 一级=2…
    val targetLevel = 1 + stack.size
    val progress = remember { Animatable(targetLevel.toFloat()) }

    // 只在「钻入（push）」时播动画；「瞬时重置」（popToRoot/切 tab）用 snap。
    // 弹出手势/pop 的动画由下面 animatePop / 手势处理器负责（先动画、后摘 pane，内容不蒸）。
    LaunchedEffect(targetLevel) {
        if (targetLevel > progress.value) progress.animateTo(targetLevel.toFloat(), pageSlide())
        else if (targetLevel < progress.value) progress.snapTo(targetLevel.toFloat())
    }

    val animatePop: () -> Unit = {
        if (stack.isNotEmpty()) {
            scope.launch {
                progress.animateTo((progress.value - 1f).coerceAtLeast(1f), pageSlide())
                nav.popImmediate()
            }
        }
    }
    // 把带动画 pop 挂到 controller：页面里 LocalNav.pop() / SubPageTile 返回键走同一动画。
    SideEffect { nav.backHandler = animatePop }
    DisposableEffect(tab) { onDispose { nav.backHandler = null } }

    // 系统预测返回：跟着手指把纸条从 targetLevel 拖回 targetLevel-1；取消弹回；完成摘 pane。
    val tabActive = LocalTabActive.current
    PredictiveBackHandler(enabled = stack.isNotEmpty() && tabActive) { prog ->
        try {
            prog.collect { p ->
                progress.snapTo(
                    (targetLevel - p.progress).coerceIn((targetLevel - 1).toFloat(), targetLevel.toFloat())
                )
            }
            nav.popImmediate()
        } catch (e: CancellationException) {
            scope.launch { progress.animateTo(targetLevel.toFloat(), pageSlide()) }
            throw e
        }
    }

    // panes：根基两块 + 栈内各级（栈里仍含正被拖出的顶级 → 滑出路上内容在）
    val panes: List<@Composable () -> Unit> = buildList {
        add(rootSecondary)
        add(rootMain)
        stack.forEach { add(it.content) }
    }

    val density = LocalDensity.current
    val gapPx = with(density) { BoardLayout.Gap.toPx() }
    val secWpx = with(density) { detailWidth.toPx() }

    androidx.compose.runtime.CompositionLocalProvider(LocalNav provides nav) {
        // v77 统一引擎：与 BoardDeck/BoardDrill 共用同一份 DeckStrip 布局核心。
        DeckStrip(panes = panes, progress = progress.value, gapPx = gapPx, secWpx = secWpx, modifier = modifier)
    }
}

/**
 * 栈内页的**标准瓦片壳**：透明单板 + `BoardSubHeader` 页头 + 滚动正文。
 * 宽度自适应用法：直接作为 `LocalNav.push { SubPageTile(...) { ... } }` 的内容；
 * 复杂页（懒列表/双区）可不用它、自己排布（只要保持透明、不自装返回）。
 */
@Composable
fun SubPageTile(
    title: String,
    onBack: () -> Unit,
    actions: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        BoardSubHeader(title = title, onBack = onBack, actions = actions)
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = BoardLayout.Padding),
            content = content
        )
    }
}
