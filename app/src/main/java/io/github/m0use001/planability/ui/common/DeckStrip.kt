package io.github.m0use001.planability.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.Constraints
import kotlin.math.roundToInt

/**
 * v77「统一引擎」：横向纸条 deck 的**唯一布局核心**（2026-11-22 用户拍板「B：把 Plan/Home/Notes
 * 统一到 NavHost 引擎、纯引擎统一、视觉不变、低风险」）。
 *
 * 之前 [BoardDrill]/[BoardDeck]/[io.github.m0use001.planability.ui.common.NavHost] 各写了一份
 * 「按 [deckSlot] 测量摆放 N 块瓦片 + 缝带盖片」的 `Layout`。三份复制 = 下次调几何要改三处。
 * 这里收成一份：三套宿主都调 [DeckStrip]，只负责各自算 `progress` 与拼 `panes`。
 *
 * 几何完全由 [deckSlot] 决定（浅→深排列，第 `progress` 块在主框、`progress-1` 在副框、
 * 更浅出右缘、更深停左缘）。瓦片透明（框由壳层固定绘制），`clip(圆角)` 保留；最后一块永远是
 * 缝带盖片（用壳层底色把两框之间的缝盖回最上层，纸条从相框**后面**穿过）。
 *
 * @param panes 板序列（浅 → 深），每块透明单板，宽度随 progress 在 mainW↔secW 间连续插值。
 * @param progress 当前主框里那块板的序号（动画中为小数）。
 */
@Composable
fun DeckStrip(
    panes: List<@Composable () -> Unit>,
    progress: Float,
    gapPx: Float,
    secWpx: Float,
    modifier: Modifier = Modifier,
) {
    val seamColor = MaterialTheme.colorScheme.background
    val boardModifier = Modifier
        .fillMaxSize()
        .clip(RoundedCornerShape(BoardLayout.Shape))
    Box(modifier.fillMaxSize()) {
        androidx.compose.ui.layout.Layout(
            content = {
                panes.forEach { pane ->
                    Box(boardModifier, contentAlignment = Alignment.TopCenter) { pane() }
                }
                // 缝带盖片（最后一块 child）
                Box(Modifier.background(seamColor))
            },
            measurePolicy = { measurables, constraints ->
                val windowW = constraints.maxWidth.toFloat()
                val h = constraints.maxHeight
                val p = progress
                val tileCount = (measurables.size - 1).coerceAtLeast(0) // 最后一块是缝带盖片
                val placed = measurables.take(tileCount).mapIndexed { i, m ->
                    val slot = deckSlot(i, p, windowW, secWpx, gapPx)
                    Triple(m.measure(Constraints.fixed(slot.w.roundToInt(), h)), slot.x.roundToInt(), i)
                }
                val seamX = (windowW - secWpx - gapPx).roundToInt().coerceAtLeast(0)
                val seamTile = measurables.lastOrNull()
                    ?.measure(Constraints.fixed(gapPx.roundToInt().coerceAtLeast(1), h))
                layout(constraints.maxWidth, constraints.maxHeight) {
                    placed.forEach { (pl, x, _) -> pl.place(x, 0) }
                    seamTile?.place(seamX, 0)
                }
            }
        )
    }
}
