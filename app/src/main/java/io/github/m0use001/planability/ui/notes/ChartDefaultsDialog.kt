package io.github.m0use001.planability.ui.notes

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import io.github.m0use001.planability.ui.common.motionEnter
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.m0use001.planability.data.Graph
import kotlinx.coroutines.launch
import io.github.m0use001.planability.ui.common.filterChipColors

/**
 * 图表全局默认设置 —— 单图右上角三点菜单里「存为全局默认」写的就是这里。
 *
 * 入口放在信息页右滑菜单、**紧贴「能力设置」正下方**（用户指定）。
 *
 * 改这里的值立刻生效：写完 SettingsRepo 后重新 load()，同时通过
 * [onDefaultsChanged] 把新的 [ChartOptions] 交给调用方更新 [LocalChartDefaults]，
 * 否则已渲染的图不会重读全局默认。
 */
@Composable
fun ChartDefaultsDialog(
    onDismiss: () -> Unit,
    onDefaultsChanged: (ChartOptions) -> Unit
) {
    val scope = rememberCoroutineScope()
    var cur by remember { mutableStateOf(Graph.settings.snapshot.value.toChartOptions()) }

    fun commit(next: ChartOptions) {
        cur = next
        scope.launch {
            runCatching { Graph.settings.saveChartDefaults(next) }
            onDefaultsChanged(next)
        }
    }

    AlertDialog(
        modifier = Modifier.motionEnter(),
        onDismissRequest = onDismiss,
        title = { Text("图表默认设置", style = MaterialTheme.typography.titleSmall) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    "这里改的是所有图表的默认值；单张图可以在它右上角的三点菜单里单独调整。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(10.dp))

                ToggleRow("显示数值标注", "在柱顶/数据点旁标出具体数值", cur.showValues) {
                    commit(cur.copy(showValues = it))
                }
                ToggleRow("显示趋势线", "折线/面积图叠加最小二乘趋势线", cur.showTrendLine) {
                    commit(cur.copy(showTrendLine = it))
                }
                ToggleRow("显示图例", "饼图/圆环/雷达等的色块图例", cur.showLegend) {
                    commit(cur.copy(showLegend = it))
                }
                ToggleRow("显示网格", "折线/散点等图上的辅助网格线", cur.showGrid) {
                    commit(cur.copy(showGrid = it))
                }
                ToggleRow("显示类目名", "X 轴下方的类别文字", cur.showDataLabels) {
                    commit(cur.copy(showDataLabels = it))
                }

                Spacer(Modifier.height(12.dp))
                Text("配色", style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.height(6.dp))
                // FlowRow 而不是 Row：5 个配色 chip 在窄屏上会把「第 4 套」挤成两行。
                // 同时把文字缩成「配1/配2…」省宽度。
                @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
                androidx.compose.foundation.layout.FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    (0 until 5).forEach { i ->
                        FilterChip(
                            colors = filterChipColors(),
                            selected = cur.paletteIdx == i,
                            onClick = { commit(cur.copy(paletteIdx = i)) },
                            label = { Text("配${i + 1}", style = MaterialTheme.typography.labelSmall) }
                        )
                    }
                }

                Spacer(Modifier.height(12.dp))
                Text("高度", style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(0 to "默认", 200 to "高", 280 to "更高").forEach { (v, label) ->
                        FilterChip(
                            colors = filterChipColors(),
                            selected = cur.heightDp == v,
                            onClick = { commit(cur.copy(heightDp = v)) },
                            label = { Text(label, style = MaterialTheme.typography.labelSmall) }
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("完成") } }
    )
}

@Composable
private fun ToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onChange(!checked) }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(
                subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.width(8.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}