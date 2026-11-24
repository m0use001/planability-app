package io.github.m0use001.planability.ui.notes

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.mutableStateOf

/**
 * 单个图表的呈现选项 —— 单图编辑、全局默认、AI 输出三处共用**同一份**模型。
 *
 * ## 为什么要一份模型而不是三份
 * 「右上角三点改这张图」和「设置里改所有图」必须是同一个结构，否则会出现
 * 「全局开了标注数值、单图菜单里却没有这个开关」这类对不上的情况。
 * 单图改完还能「存为全局默认」，所以这里所有字段都可序列化。
 *
 * @param showValues柱顶/点上标数值
 * @param showTrendLine 折线/面积图叠加趋势线
 * @param showLegend图例（饼图、雷达多系列等）
 * @param showGrid网格线
 * @param showDataLabelsX 轴类目名
 * @param paletteIdx调色板序号（0=默认，后续加配色时用）
 * @param heightDp 图表高度，0=用类型默认高度
 */
data class ChartOptions(
    val showValues: Boolean = false,
    val showTrendLine: Boolean = false,
    val showLegend: Boolean = true,
    val showGrid: Boolean = true,
    val showDataLabels: Boolean = true,
    val paletteIdx: Int = 0,
    val heightDp: Int = 0
) {
    /** 该类型实际使用的高度：没指定就用 [defaultHeightFor]。 */
    fun heightOr(default: Int): Int = if (heightDp > 0) heightDp else default

    companion object {
        /** 各类型的默认高度（dp）—— 高度跟图形的可读密度相关，不该各处硬编码。 */
        fun defaultHeightFor(type: String): Int = when (type) {
            "pie", "donut", "radar" -> 220
            "hbar", "treemap", "sunburst" -> 240
            else -> 140
        }
    }
}

/** 从设置快照读出图表默认项（设置页初值用）。 */
fun io.github.m0use001.planability.data.SettingsSnapshot.toChartOptions(): ChartOptions = ChartOptions(
    showValues = chartShowValues,
    showTrendLine = chartShowTrend,
    showLegend = chartShowLegend,
    showGrid = chartShowGrid,
    showDataLabels = chartShowLabels,
    paletteIdx = chartPalette,
    heightDp = chartHeightDp
)

/**
 * 全局默认选项。存 [SettingsRepo]，设置页改它，单图菜单读它作为初值。
 *
 * 用 [staticCompositionLocalOf] 而不是 [compositionLocalOf]：它在同一个 Composition 里
 * 不变（图表渲染时不需要逐层重组），只有真正切换默认值时才整体刷新。
 */
val LocalChartDefaults = staticCompositionLocalOf { ChartOptions() }

/** 设置里改了全局默认 → 通知所有图表重读。用一个可观察的盒子承载。 */
class ChartDefaultsHolder(initial: ChartOptions) {
    var value by mutableStateOf(initial)
    fun set(o: ChartOptions) { value = o }
}

@Composable
fun rememberChartDefaults(): ChartDefaultsHolder = ChartDefaultsHolder(LocalChartDefaults.current)