package io.github.m0use001.planability.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp

/**
 * 两板钻入几何的**纯函数**（[drillTiles] 的历史口径，[DrillGeometryTest] 继续钉它）。
 *
 * v77 起 [BoardDrill] 的**渲染**已改为委托给 [BoardDeck]（统一引擎，`deckSlot`）；
 * 本函数保留为几何参考实现 + 供 `DrillGeometryTest` 校验「deckSlot 在该两板映射下与 drillTiles 等值」。
 */
data class DrillTiles(
    val detailX: Float, val detailW: Float,
    val mainX: Float, val mainW: Float,
    val secX: Float, val secW: Float,
)

fun drillTiles(progress: Float, windowW: Float, secW: Float, gap: Float): DrillTiles {
    val p = progress.coerceIn(0f, 1f)
    val mainW = (windowW - secW - gap).coerceAtLeast(0f)
    return DrillTiles(
        // 细分页：-mainW → 0（从左缘向右滑进主框位）
        detailX = -mainW + p * mainW,
        detailW = mainW,
        // 主板：0 → mainW+gap，宽 mainW → secW 连续插值（「自动改排版」的来源）
        mainX = p * (mainW + gap),
        mainW = mainW + (secW - mainW) * p,
        // 副板（概览）：mainW+gap → windowW（p=1 时左缘贴视口右缘 = 完全出屏）
        secX = mainW + gap + p * secW,
        secW = secW,
    )
}

/**
 * 两板骨架的「钻入」宿主。**v77 起是 [BoardDeck] 的薄适配**（统一引擎）：
 * panes = `[副板(概览), 主板(列表), 细分页]`，`level` 在 1（根层两板）↔ 2（已钻入）间切换。
 *
 * 为什么能等价：`deckSlot` 在「浅→深 = 副板/主板/细分页、level=1↔2」这套映射下，与
 * [drillTiles] 逐像素一致（[DrillGeometryTest]/[DeckGeometryTest] 双钉）。保留 [detail] 的
 * 最后一次非空 key（滑出路上内容不蒸）。旧独立实现已删除，Plan/Home 从此跑在同一份
 * [DeckStrip] 渲染核心上（与 [BoardDeck]/[NavHost] 同源）。
 *
 * @param detailKey 钻入目标（null = 根层两板）
 * @param onBack 用户要求返回（预测返回手势完成时回调）
 */
@Composable
fun <K : Any> BoardDrill(
    detailKey: K?,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    detailWidth: Dp = BoardLayout.DetailWidth,
    applyTopInset: Boolean = true,
    applyBottomInset: Boolean = true,
    detail: @Composable (K) -> Unit,
    main: @Composable () -> Unit,
    secondary: @Composable () -> Unit,
) {
    // 保留最后一次非空 detailKey：滑出的路上内容不蒸（等价原实现的 lastKey/detailVisible）。
    var lastKey by remember { mutableStateOf<K?>(null) }
    if (detailKey != null) lastKey = detailKey
    val k = detailKey ?: lastKey
    BoardDeck(
        level = if (detailKey != null) 2 else 1,
        onBack = onBack,
        modifier = modifier,
        detailWidth = detailWidth,
        panes = listOf(
            { secondary() },
            { main() },
            { k?.let { detail(it) } },
        )
    )
}
