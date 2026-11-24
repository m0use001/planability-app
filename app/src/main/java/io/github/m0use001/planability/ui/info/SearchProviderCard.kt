package io.github.m0use001.planability.ui.info

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import io.github.m0use001.planability.data.Graph
import kotlinx.coroutines.launch

/**
 * 2026-11-14：**联网搜索主源（Tavily）的 key 入口**。
 *
 * 为什么需要这个卡：web_search 的端侧链（Bing/360 抓页面）每被引擎改版一次就要修一次
 * selector，用户明确不要这种反复试错。Tavily 是正规 API，免费档 1000 次/月、不要信用卡，
 * 配上 key 后 WebSearch 先打它、失败才退回端侧链（顺序见 WebSearch 文件头）。
 *
 * 存储：`search.tavilyKey`，**走 SecretBox.seal 落库**（与 provider key 同口径）——
 * v44 起整库备份是加密的，明文 key 进备份等于把 key 一起拷走。
 *
 * 交互：输入框不回显（PasswordVisualTransformation），右侧眼睛临时可见，
 * 保存后卡头显示「已配置」。清空 = 回到纯端侧链（行为与加这个功能之前一致）。
 */
@Composable
fun SearchProviderCard() {
    val settings by Graph.settings.snapshot.collectAsState()
    val scope = rememberCoroutineScope()
    var text by remember(settings.searchTavilyKey) { mutableStateOf(settings.searchTavilyKey) }
    var visible by remember { mutableStateOf(false) }
    var savedHint by remember { mutableStateOf<String?>(null) }
    val configured = settings.searchTavilyKey.isNotBlank()
    val dirty = text.trim() != settings.searchTavilyKey

    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    ) {
        Column(
            Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                "联网搜索 · Tavily",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                if (configured) "已配置：web_search 优先走 Tavily（每个号 1000 次/月，额度用尽自动换下一个）。"
                else "未配置：web_search 走端侧抓取（Bing→360），引擎改版会失效。填一个 Tavily key 更稳。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            OutlinedTextField(
                value = text,
                onValueChange = { text = it; savedHint = null },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("API Key（tvly-…，多个用换行分隔）") },
                singleLine = false,
                minLines = 1,
                maxLines = 3,
                textStyle = MaterialTheme.typography.bodySmall,
                visualTransformation = if (visible) VisualTransformation.None
                else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = { visible = !visible }) {
                        Icon(
                            if (visible) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                            contentDescription = if (visible) "隐藏" else "显示",
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            )
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                FilledTonalButton(
                    enabled = dirty,
                    onClick = {
                        val v = text.trim()
                        // 顺手挡住粘贴整个 JSON/说明的情况：只认 tvly- 开头的字面量，
                        // 多个 key 统一成换行分隔（WebSearch.parseKeys 也做同一件事，双保险）
                        val keys = Regex("tvly-[A-Za-z0-9_-]+").findAll(v)
                            .map { it.value }.distinct().toList()
                        val cleaned = if (keys.isEmpty()) v else keys.joinToString("\n")
                        scope.launch {
                            Graph.settings.update { it.copy(searchTavilyKey = cleaned) }
                            text = cleaned
                            savedHint = if (cleaned.isEmpty()) "已清空，回到端侧链"
                            else "已保存" + if (keys.size > 1) "（${keys.size} 个 key）" else ""
                        }
                    }
                ) { Text(if (configured) "更新" else "保存") }
                if (configured) TextButton(onClick = {
                    scope.launch {
                        Graph.settings.update { it.copy(searchTavilyKey = "") }
                        text = ""
                        savedHint = "已清空，回到端侧链"
                    }
                }) { Text("清空") }
                savedHint?.let {
                    Text(it, style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.tertiary)
                }
            }
        }
    }
}
