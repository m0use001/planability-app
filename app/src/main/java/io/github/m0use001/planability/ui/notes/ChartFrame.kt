package io.github.m0use001.planability.ui.notes

import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.m0use001.planability.data.Graph
import kotlinx.coroutines.launch

/** 菜单里的一行：[checked] 非 null 时右侧显示勾。 */
@Composable
private fun OptRow(
    label: String,
    checked: Boolean?,
    onClick: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            // 2026-11-20：菜单行可点无底，水波本来是直角横杠——补 10dp 圆角裁剪
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Spacer(Modifier.width(10.dp))
        if (checked == true) {
            Icon(Icons.Rounded.Check, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
        } else {
            Spacer(Modifier.width(16.dp))
        }
    }
}

@Composable
private fun OptHeader(t: String) {
    Text(
        t,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
    )
}

/**
 * 图表外壳：右上角三点 → 详细编辑菜单（对应 Office 的"编辑数据 / 更改图表类型"那层）。
 *
 * ## 为什么做成通用外壳而不是每种图表各写一份菜单
 * 这套选项（标注数值 / 趋势线 / 图例 / 网格 / 类目名 / 配色 / 高度）对所有图表都成立。
 * 每种图表各写一份，第三个图表就会漏掉某一项 —— 和卡片协议那次的教训一样。
 * 所以统一收口：`content` 拿到的是**当前这张图**的 [ChartOptions]。
 *
 * ## 单图 vs 全局两层
 * 菜单里的开关只影响这张图；「存为全局默认」写进 SettingsRepo，
 * 之后新出现的图表以它当初值 —— 这就是 Office "改单图 vs 改模板"。
 */
@Composable
fun ChartFrame(
    chartType: String,
    title: String,
    modifier: Modifier = Modifier,
    switchableTypes: List<String> = emptyList(),
    onTypeChange: (String) -> Unit = {},
    defaultLegend: Boolean = false,
    content: @Composable (ChartOptions) -> Unit
) {
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    // LocalChartDefaults 必须在 remember **外面**读 —— remember 的计算 lambda 不是
    // @Composable 上下文，在里面读 CompositionLocal 会编译不过。
    val base = LocalChartDefaults.current
    var opts by remember(chartType) {
        mutableStateOf(base.copy(showLegend = defaultLegend))
    }
    // 这张图有没有被单独改过。
    //
    // 有了它才能同时满足两件事：①全局默认改了，这张图跟着变；②这张图单独调过之后，
    // 全局改动不要把它覆盖掉（不然"单图覆盖"就名存实亡了）。
    // 只加 base 到 remember 的 key 会把已经单独调过的图一并重置，所以用 touched 单独判。
    var touched by remember(chartType) { mutableStateOf(false) }
    LaunchedEffect(base) {
        if (!touched) opts = base.copy(showLegend = defaultLegend)
    }
    var menuOpen by remember { mutableStateOf(false) }

    fun change(f: (ChartOptions) -> ChartOptions) {
        opts = f(opts)
        touched = true
    }

    Box(modifier.fillMaxWidth()) {
        content(opts)

        Box(Modifier.align(Alignment.TopEnd)) {
            IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(30.dp)) {
                Icon(
                    Icons.Rounded.MoreVert, "编辑这张图",
                    Modifier.size(17.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            DropdownMenu(
                expanded = menuOpen,
                onDismissRequest = { menuOpen = false },
                modifier = Modifier.widthIn(max = 230.dp)
            ) {
                OptRow("标注数值", opts.showValues) {
                    change { it.copy(showValues = !it.showValues) }
                }
                OptRow("显示趋势线", opts.showTrendLine) { change { it.copy(showTrendLine = !it.showTrendLine) } }
                OptRow("显示图例", opts.showLegend) { change { it.copy(showLegend = !it.showLegend) } }
                OptRow("显示网格", opts.showGrid) { change { it.copy(showGrid = !it.showGrid) } }
                OptRow("显示类目名", opts.showDataLabels) { change { it.copy(showDataLabels = !it.showDataLabels) } }

                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                OptRow("配色：第 ${opts.paletteIdx + 1} 套", null) {
                    change { it.copy(paletteIdx = (it.paletteIdx + 1) % 5) }
                }
                OptRow(
                    "高度：${if (opts.heightDp == 0) "默认" else "${opts.heightDp}dp"}",
                    null
                ) {
                    change {
                        it.copy(
                            heightDp = when {
                                it.heightDp == 0 -> 200
                                it.heightDp >= 280 -> 0
                                else -> it.heightDp + 80
                            }
                        )
                    }
                }

                if (switchableTypes.isNotEmpty()) {
                    HorizontalDivider(Modifier.padding(vertical = 4.dp))
                    OptHeader("更改类型")
                    switchableTypes.forEach { ty ->
                        OptRow(TYPE_LABELS[ty] ?: ty, chartType == ty) { onTypeChange(ty) }
                    }
                }

                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                OptRow("存为全局默认", null) {
                    scope.launch {
                        runCatching { Graph.settings.saveChartDefaults(opts) }
                        android.widget.Toast.makeText(
                            ctx, "已设为所有图表的默认", android.widget.Toast.LENGTH_SHORT
                        ).show()
                    }
                }
                OptRow("还原这张图", null) {
                    opts = base.copy(showLegend = defaultLegend)
                    touched = false
                }
            }
        }
    }
}

/** 语言槽 → 中文名（菜单里给用户看的是名字，不是围栏关键字） */
val TYPE_LABELS: Map<String, String> = mapOf(
    "bar" to "柱形图", "line" to "折线图", "area" to "面积图", "hbar" to "条形图",
    "pie" to "饼图", "donut" to "圆环图", "scatter" to "散点图", "bubble" to "气泡图",
    "radar" to "雷达图", "treemap" to "树状图", "sunburst" to "旭日图", "heatmap" to "热力图"
)