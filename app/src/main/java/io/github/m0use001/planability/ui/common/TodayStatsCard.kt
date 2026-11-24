package io.github.m0use001.planability.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.m0use001.planability.core.AppTime
import io.github.m0use001.planability.data.Graph

/**
 * 「今日」数据可视化框：完成率 / 屏幕时间 / 睡眠 三条进度条 + 右上角综合评分。
 * 数据与首页评分拆解**同一份来源**（PlanRepo.loadDay + loadScreenMinutes + Graph.sleep.latest
 * → scoreBreakdown 同一公式），dataVersion 变化即重载——任何界面放它，数字都和首页一致。
 * 2026-10-24 用户要求：先落工作台抽屉，后落全局搜索抽屉（搜索框下方）。
 */
@Composable
fun TodayStatsCard(modifier: Modifier = Modifier) {
    var bundle by remember { mutableStateOf<io.github.m0use001.planability.data.DayBundle?>(null) }
    var screenMin by remember { mutableStateOf(-1) }
    var sleep by remember { mutableStateOf<io.github.m0use001.planability.data.SleepRecord?>(null) }
    val goals = Graph.settings.snapshot.collectAsState().value
    val bd = remember(bundle, screenMin, sleep, goals) {
        io.github.m0use001.planability.ui.home.scoreBreakdown(
            bundle, screenMin, sleep,
            goals.screenLimitMin, goals.screenPenaltyPer30,
            goals.sleepGoalMin, goals.sleepPenaltyPer30
        )
    }
    LaunchedEffect(Graph.dataVersion.flow.collectAsState().value) {
        val today = AppTime.now().toLocalDate()
        bundle = runCatching { Graph.plan.loadDay(today) }.getOrNull()
        screenMin = runCatching {
            io.github.m0use001.planability.ui.home.loadScreenMinutes(today)
        }.getOrDefault(-1)
        sleep = runCatching { Graph.sleep.latest() }.getOrNull()
    }

    Box(
        modifier
            // v72.1：改成与 PaneBlocks 同色阶（surfaceContainerHigh，16dp）——原来用板底同色
            // surfaceContainerLow 等于隐形，评分区看着像一段裸文本。
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "今日评分", style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                io.github.m0use001.planability.ui.common.PaneChip("${bd.score} 分")
            }
            Spacer(Modifier.height(10.dp))
            TodayStatRow(
                "完成率",
                if (bd.total > 0) bd.done / bd.total else 0f,
                // v46：done 是加权值（Float），带小数时保留 1 位（如 1.5/2 项）
                if (bd.total > 0)
                    "${if (bd.done % 1f == 0f) bd.done.toInt().toString() else String.format(java.util.Locale.US, "%.1f", bd.done)}/${bd.total} 项"
                else "暂无任务"
            )
            Spacer(Modifier.height(6.dp))
            TodayStatRow(
                "屏幕",
                if (screenMin >= 0 && goals.screenLimitMin > 0)
                    (screenMin.toFloat() / goals.screenLimitMin).coerceIn(0f, 1f) else 0f,
                if (screenMin >= 0) "${screenMin / 60}小时${screenMin % 60}分" else "无数据"
            )
            Spacer(Modifier.height(6.dp))
            val sleepMinutes = sleep?.durationMin ?: -1
            TodayStatRow(
                "睡眠",
                if (sleepMinutes >= 0 && goals.sleepGoalMin > 0)
                    (sleepMinutes.toFloat() / goals.sleepGoalMin).coerceIn(0f, 1f) else 0f,
                if (sleepMinutes >= 0) "${sleepMinutes / 60}小时${sleepMinutes % 60}分" else "未记录"
            )
        }
    }
}

/** 今日数据框的单行统计：左标签 + 中进度条 + 右数值。
 *  v72.1：LinearProgressIndicator 默认色在绿主题下呈粉红，与其它进度条不一致——改自绘
 *  （轨 surfaceVariant、进度 primary、6dp 高 / 3dp 圆角，与 FocusGoalBar/完成率同款纪律）。 */
@Composable
private fun TodayStatRow(label: String, progress: Float, valueText: String) {
    val track = MaterialTheme.colorScheme.surfaceVariant
    val fg = MaterialTheme.colorScheme.primary
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            label, style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(44.dp)
        )
        Spacer(Modifier.width(8.dp))
        Box(
            Modifier
                .weight(1f)
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(track)
        ) {
            if (progress > 0f) {
                Box(
                    Modifier
                        .fillMaxWidth(progress.coerceIn(0f, 1f))
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(fg)
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        Text(
            valueText, style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.End,
            modifier = Modifier.width(84.dp)
        )
    }
}
