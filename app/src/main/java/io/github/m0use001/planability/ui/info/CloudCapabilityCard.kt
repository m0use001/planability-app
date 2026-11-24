package io.github.m0use001.planability.ui.info

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.m0use001.planability.ai.ptl.DeepSeekCloud
import io.github.m0use001.planability.data.CLOUD_OCR_FALLBACK
import io.github.m0use001.planability.data.CLOUD_OCR_OFF
import io.github.m0use001.planability.data.CLOUD_OCR_PREFER
import io.github.m0use001.planability.data.Graph
import kotlinx.coroutines.launch

/**
 * 2026-11-14：DeepSeek 应用能力云开关卡（方案 设计文档，
 * 用户拍板：Tavily 优先云兜底；语音不上云）。
 *
 * 开关语义：
 * - 云搜索：web_search 的 Tavily→端侧 都空手后才调 DeepSeek Search API（5 元/千次）；
 * - 云抓取：web_fetch 的 静态抓→无头渲染 都空手后（5 元/千次）；
 * - 读文件（三档，2026-11-14 用户「优先走云端」）：关闭 / 兜底 / 优先（DeepSeek 系列模型下
 *   先交云端解析，支持 PDF/Word/PPT + 图片，1.5 元/千页；失败才回端侧）。
 *
 * key 复用 DeepSeek 供应商配置（模型管理页里配好 key 即生效，不在此重复录入）。
 * 日预算（默认 5 元）超限自动回退降级链并在工具审计标注「云预算尽」。
 */
@Composable
fun CloudCapabilityCard() {
    val settings by Graph.settings.snapshot.collectAsState()
    val scope = rememberCoroutineScope()
    var today by remember { mutableStateOf("0") }
    // 当日用量是同步读（本地文件），开关变化后刷一次
    androidx.compose.runtime.LaunchedEffect(
        settings.cloudSearchEnabled, settings.cloudFetchEnabled, settings.cloudOcrEnabled
    ) { today = DeepSeekCloud.usedTodayYuan() }

    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    ) {
        Column(
            Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                "DeepSeek 应用能力",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                "联网/抓取/读文件的云端能力（按次计费）。用模型管理页里 DeepSeek 供应商的 key；" +
                    "搜索与抓取是兜底，读文件可按需切「优先」。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            CloudToggle(
                title = "云搜索兜底",
                desc = "搜索/抓取全部空手时，用 DeepSeek 搜索（含引用）",
                checked = settings.cloudSearchEnabled
            ) { on ->
                scope.launch {
                    Graph.settings.update { it.copy(cloudSearchEnabled = on) }
                    today = DeepSeekCloud.usedTodayYuan()
                }
            }
            CloudToggle(
                title = "云抓取兜底",
                desc = "网页正文抓不到（反爬/渲染站）时，云端取正文",
                checked = settings.cloudFetchEnabled
            ) { on ->
                scope.launch {
                    Graph.settings.update { it.copy(cloudFetchEnabled = on) }
                    today = DeepSeekCloud.usedTodayYuan()
                }
            }
            // 2026-11-14 用户：「AI 读文件改成优先走云端」（DeepSeek 系列模型）。
            // 三档而不是开关——「兜底」与「优先」是两种工作流，用户要能切回去。
            Column(Modifier.fillMaxWidth()) {
                Text("读文件（图片/PDF/Word/PPT）", style = MaterialTheme.typography.bodyMedium)
                Text(
                    when (settings.cloudOcrMode) {
                        CLOUD_OCR_PREFER ->
                            "优先：DeepSeek 系列模型下先交云端解析（PDF/Word/PPT + 图片，按页计费），失败才回端侧"
                        CLOUD_OCR_FALLBACK -> "兜底：端侧识别不出文字时才上云（旧行为）"
                        else -> "关闭：一次都不上云"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(
                    Modifier.fillMaxWidth().padding(top = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf(
                        CLOUD_OCR_OFF to "关闭",
                        CLOUD_OCR_FALLBACK to "兜底",
                        CLOUD_OCR_PREFER to "优先"
                    ).forEach { (mode, label) ->
                        FilterChip(
                            selected = settings.cloudOcrMode == mode,
                            onClick = {
                                scope.launch {
                                    Graph.settings.update {
                                        it.copy(
                                            cloudOcrMode = mode,
                                            cloudOcrEnabled = mode != CLOUD_OCR_OFF
                                        )
                                    }
                                    today = DeepSeekCloud.usedTodayYuan()
                                }
                            },
                            label = { Text(label, style = MaterialTheme.typography.labelMedium) }
                        )
                    }
                }
            }
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "今日 ¥" + today + " / 预算 ¥" + (settings.cloudDailyBudgetFen / 100.0),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    if (DeepSeekCloud.apiKey() == null) "未配置 DeepSeek key"
                    else "key 已就绪",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (DeepSeekCloud.apiKey() == null) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.tertiary
                )
            }
            // 2026-11-14 用量面板细化：分能力条数（按一次=0.005 元、一页 OCR 反算）
            // + 本月累计 + 预算进度条。条数取整（尾差由「今日」总额兜住）。
            val day = DeepSeekCloud.usageToday()
            val month = DeepSeekCloud.usageThisMonth()
            if (day.total > 0 || month.total > 0) {
                val searchN = day.search / 5
                val fetchN = day.fetch / 5
                val ocrN = (day.ocr * 2 + 1) / 3   // 1.5 tenths/页 的反算（近似）
                Text(
                    "今日：搜索 " + searchN + " 次 · 抓取 " + fetchN + " 次 · OCR " + ocrN + " 页",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    "本月累计 ¥" + String.format(java.util.Locale.ROOT, "%.3f", month.total / 1000.0),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                val frac = (day.total.toFloat() / (settings.cloudDailyBudgetFen * 10).coerceAtLeast(1))
                    .coerceIn(0f, 1f)
                androidx.compose.material3.LinearProgressIndicator(
                    progress = { frac },
                    modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                    color = if (frac >= 0.9f) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceContainerHighest
                )
            }
        }
    }
}

@Composable
private fun CloudToggle(title: String, desc: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(
                desc,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
