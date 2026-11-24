package io.github.m0use001.planability.ui.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.m0use001.planability.ai.local.LocalEngine
import io.github.m0use001.planability.ai.local.LocalModelClient
import io.github.m0use001.planability.ai.local.ModelStorage
import io.github.m0use001.planability.core.FsGuard
import io.github.m0use001.planability.core.HapticEngine
import io.github.m0use001.planability.core.LocalHaptics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 本地模型（端侧 NPU）——与云端供应商同排，固定排在供应商列表最底部。
 * 行只显示摘要；管理动作在下方详情区，见 [LocalModelDetail]（模型列表 + 引擎开关）。
 * 数据源：:localai 进程的 OpenAI 兼容 server /v1/local/status（ai/local/LocalModelClient）。
 */

@Composable
fun LocalModelRow(selected: Boolean, onClick: () -> Unit) {
    val context = LocalContext.current
    var enabled by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<LocalModelClient.Status?>(null) }
    var spec by remember { mutableStateOf(LocalEngine.specFor(LocalEngine.selectedName(context))) }

    LaunchedEffect(Unit) { enabled = LocalModelClient.engineEnabled() }
    LaunchedEffect(Unit) {
        while (true) {
            spec = LocalEngine.specFor(LocalEngine.selectedName(context))
            status = if (enabled) LocalModelClient.status() else null
            delay(3000)
        }
    }

    val state = when {
        !enabled -> "已关闭"
        status == null -> "服务启动中"
        status?.downloaded == true -> "已下载"
        else -> "未下载"
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                else MaterialTheme.colorScheme.surfaceContainer
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 9.dp)
    ) {
        // R11：端侧模型标记 = 装饰性入口 → 中性
        Icon(Icons.Rounded.Bolt, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text("本地模型（端侧 NPU）", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Text(
                spec.label + " · " + state,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
        }
        Box(
            Modifier
                .size(8.dp)
                .background(
                    if (enabled) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.outlineVariant,
                    CircleShape
                )
        )
    }
}

@Composable
fun LocalModelDetail() {
    val haptics = LocalHaptics.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var enabled by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<LocalModelClient.Status?>(null) }
    var busy by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(LocalEngine.selectedName(context)) }
    // 2026-11-10 优化体检 P0-1：真机 files/geniex 实测 9.38GB、六模型全下载、仅 2 个被活引用。
    // 这里补三样治理信息：真实落盘大小（ approxMb 是估算）、活动槽位引用（worker/压缩槽在
    // 这页原来根本看不出来）、删除防误删（被 worker/压缩槽引用的模型删除按钮禁用）。
    var disk by remember { mutableStateOf<List<ModelStorage.OnDiskModel>>(emptyList()) }

    LaunchedEffect(Unit) { enabled = LocalModelClient.engineEnabled() }

    LaunchedEffect(enabled) {
        while (true) {
            status = if (enabled) LocalModelClient.status() else null
            delay(2000)
        }
    }

    LaunchedEffect(Unit) {
        while (true) {
            disk = withContext(Dispatchers.IO) {
                runCatching { ModelStorage.listOnDisk(context) }.getOrDefault(emptyList())
            }
            delay(5000)
        }
    }

    val stateText = when {
        !enabled -> "服务未启动（打开右侧开关）"
        status == null -> "服务启动中…"
        else -> status!!.label
    }

    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    ) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Bolt, null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text("本地模型（端侧 NPU）", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(
                        "引擎独立进程 · 127.0.0.1:18181",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = enabled,
                    onCheckedChange = { on ->
                        haptics.perform(HapticEngine.Kind.Select)
                        enabled = on
                        scope.launch { LocalModelClient.setEngineEnabled(context, on) }
                    }
                )
            }

            Text(
                stateText,
                style = MaterialTheme.typography.labelMedium,
                color = if (status?.failed == true) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurfaceVariant
            )

            val downloadedTotal = disk.sumOf { it.bytes }
            val reclaimable = disk.filter { !it.inUse }.sumOf { it.bytes }
            if (downloadedTotal > 0) {
                Text(
                    "实占 " + FsGuard.humanSize(downloadedTotal) +
                        " · 可回收 " + FsGuard.humanSize(reclaimable) +
                        "（标「提纯 worker/会话压缩槽」的在用不可删；删错可随时重新下载）",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Text(
                "模型列表（${LocalEngine.CATALOG.size}）",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold
            )

            LocalEngine.CATALOG.forEach { s ->
                val isCurrent = s.pullName == selected
                val present = status?.present?.contains(s.pullName) == true
                val onDisk = disk.firstOrNull { it.pullName == s.pullName }
                // 防误删：worker/压缩槽引用中的模型删除按钮禁用（引擎当前选择不拦——
                // 删掉只是下次请求报未下载，语义与旧行为一致）
                val slotRefs = onDisk?.refs?.filter { it != "引擎当前选择" }.orEmpty()
                val deleteBlocked = slotRefs.isNotEmpty()
                val downloadingThis = status?.downloading == true && isCurrent
                Card(
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                    )
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                haptics.perform(HapticEngine.Kind.Select)
                                selected = s.pullName
                                scope.launch {
                                    LocalModelClient.select(context, s.pullName)
                                    delay(200)
                                    status = if (enabled) LocalModelClient.status() else null
                                }
                            }
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    s.label,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f, fill = false)
                                )
                                if (isCurrent) {
                                    Text(
                                        "使用中",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary,
                                        maxLines = 1,
                                        modifier = Modifier.padding(start = 6.dp)
                                    )
                                }
                                slotRefs.forEach { ref ->
                                    Text(
                                        ref,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.error,
                                        maxLines = 1,
                                        modifier = Modifier.padding(start = 6.dp)
                                    )
                                }
                            }
                            Text(
                                listOfNotNull(
                                    s.precision,
                                    onDisk?.let { FsGuard.humanSize(it.bytes) } ?: (s.approxMb.toString() + "MB"),
                                    s.runtime
                                ).joinToString(" · "),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                when {
                                    downloadingThis -> "下载中…"
                                    present -> "已下载"
                                    else -> "未下载"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = if (present) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1
                            )
                        }
                        if (present) {
                            TextButton(
                                enabled = enabled && !busy && !deleteBlocked,
                                onClick = {
                                    haptics.perform(HapticEngine.Kind.Tap)
                                    scope.launch {
                                        busy = true
                                        LocalModelClient.select(context, s.pullName)
                                        selected = s.pullName
                                        delay(200)
                                        LocalModelClient.remove()
                                        delay(600)
                                        status = LocalModelClient.status()
                                        disk = withContext(Dispatchers.IO) {
                                            runCatching { ModelStorage.listOnDisk(context) }.getOrDefault(emptyList())
                                        }
                                        busy = false
                                    }
                                }
                            ) { Text(if (deleteBlocked) "占用中" else "删除") }
                        } else {
                            TextButton(
                                enabled = enabled && !busy && status?.downloading != true,
                                onClick = {
                                    haptics.perform(HapticEngine.Kind.Tap)
                                    scope.launch {
                                        busy = true
                                        LocalModelClient.select(context, s.pullName)
                                        selected = s.pullName
                                        delay(200)
                                        LocalModelClient.pull()
                                        delay(400)
                                        status = LocalModelClient.status()
                                        busy = false
                                    }
                                }
                            ) { Text("下载") }
                        }
                    }
                }
            }

            Text(
                "引擎单实例：一次只加载一个模型，切换后点下载即可。把某个模型设为 worker / 压缩槽即自动使用；" +
                    "主对话不切（仍走云端）。模型存应用私有目录，可随时删除。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
