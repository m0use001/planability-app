package io.github.m0use001.planability.ui.profile

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.m0use001.planability.app.PredictiveBackOverlay
import io.github.m0use001.planability.core.HapticEngine
import io.github.m0use001.planability.core.LocalHaptics
import io.github.m0use001.planability.data.Graph
import androidx.compose.runtime.rememberCoroutineScope
import io.github.m0use001.planability.ui.notes.SemanticIndexDialog
import io.github.m0use001.planability.ui.theme.ShapeTokens
import kotlinx.coroutines.launch

/**
 * v19.51 模型显示管理：一屏平铺所有供应商的模型，每行一个开关。
 *
 * ## 与「模型与智能体」的分工
 * - 那一屏 = **管理**：按供应商走，能加模型、改名字、删模型、测连接；
 * - 这一屏 = **选择**：只看"哪些模型参与显示与可选"，和用户要的截图一致。
 *   把两件事混在一屏，每次想改开关都得先选中供应商——用户明确不想要那个。
 *
 * ## 语义
 * 开关直接写 `AiModel.enabled`，与供应商卡里的开关**同一个字段**，不搞第二套状态。
 * 好处：在两处改是同一份数据，不会出现"那边显示开、这边显示关"。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelDisplayScreen(
    onDone: () -> Unit,
    /** 合并屏的顶部切换器（见 ui/info/SettingsHub.kt 的 ModelSettingsHub） */
    topSwitcher: (@Composable () -> Unit)? = null,
    /** 调用方已套 [PredictiveBackOverlay]（合并屏做法）时为 true。否则两个 Dialog 窗口
     *  接连关+开会让宿主窗口透出一帧——用户的"页面闪一下"（见 ModelManagerScreen 同处注释）。 */
    insideOverlay: Boolean = false,
    /** v77：板内钻入瓦片——透明底 + 板内页头 + 0 inset（框归壳层），Rail 常驻 */
    embedded: Boolean = false
) {
    val haptics = LocalHaptics.current
    val st by Graph.aiProviders.state.collectAsState()
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    val shell: @Composable () -> Unit = {
        Scaffold(
            // v77 板内钻入：embedded 时透明底 + 0 inset（框归壳层）
            containerColor = if (embedded) androidx.compose.ui.graphics.Color.Transparent
            else MaterialTheme.colorScheme.background,
            contentWindowInsets = if (embedded)
                androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0)
            else androidx.compose.material3.ScaffoldDefaults.contentWindowInsets,
            topBar = {
                Column {
                    if (embedded) {
                        io.github.m0use001.planability.ui.common.BoardSubHeader(
                            title = "模型显示",
                            onBack = onDone
                        )
                    } else {
                    TopAppBar(
                        navigationIcon = {
                            IconButton(onClick = { haptics.perform(HapticEngine.Kind.Tap); onDone() }) {
                                Icon(Icons.AutoMirrored.Rounded.ArrowBack, "返回")
                            }
                        },
                        title = { Text("模型显示管理", fontWeight = FontWeight.SemiBold) }
                    )
                    }
                    if (topSwitcher != null) {
                        Column(Modifier.fillMaxWidth()) { topSwitcher() }
                    }
                }
            }
        ) { pad ->
            Column(
                Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp)
            ) {
                val enabled = st.models.count { it.enabled }
                Text(
                    "共 ${st.models.size} 个模型，$enabled 个参与显示",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "关掉的模型不会出现在选择列表里，但不会被删除。要增删模型请去「模型与智能体」。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(10.dp))

                if (st.models.isEmpty()) {
                    Text(
                        "还没有任何模型。去「模型与智能体」添加，或用供应商卡里的「扫描模型」一键拉取。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                val byProvider = st.providers.associateBy { it.id }
                val grouped = st.models.groupBy { it.providerId }
                st.providers.forEach { p ->
                    val list = grouped[p.id].orEmpty()
                    if (list.isEmpty()) return@forEach
                    Row(
                        Modifier.padding(top = 12.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            p.name,
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            "${list.count { it.enabled }}/${list.size}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    list.forEach { m ->
                        ModelToggleRow(
                            name = m.name,
                            tags = m.tags,
                            enabled = m.enabled,
                            providerEnabled = p.enabled,
                            onToggle = { on ->
                                haptics.perform(HapticEngine.Kind.Select)
                                // updateModel 是 suspend，开关回调里必须起协程
                                scope.launch { Graph.aiProviders.updateModel(m.copy(enabled = on)) }
                            }
                        )
                    }
                }
                // v56 本地模型：语义检索（向量重排/语义门/问笔记共用同一批 bge/Qwen3 嵌入）
                // 的下载与档位管理收拢到「模型设置」——原来挂在笔记页顶栏，
                // 用户拍板笔记右上角只留一个搜索键后搬到这里。
                Text(
                    "本地模型",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 12.dp, bottom = 4.dp)
                )
                var showSemanticModels by remember { mutableStateOf(false) }
                Card(
                    shape = ShapeTokens.card,
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 3.dp)
                        .clickable {
                            haptics.perform(HapticEngine.Kind.Tap)
                            showSemanticModels = true
                        }
                ) {
                    Row(
                        Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                "语义检索模型（本地向量）",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                "bge 中文/英文与 Qwen3 重型档的下载、切换与重建索引；问笔记与 kb_ask 共用",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Text(
                            "管理 ›",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
                if (showSemanticModels) {
                    SemanticIndexDialog(onDismiss = { showSemanticModels = false })
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
    if (insideOverlay) shell() else PredictiveBackOverlay(onBack = onDone) { shell() }
}

@Composable
private fun ModelToggleRow(
    name: String,
    tags: String,
    enabled: Boolean,
    providerEnabled: Boolean,
    onToggle: (Boolean) -> Unit
) {
    val haptics = LocalHaptics.current
    Card(
        shape = ShapeTokens.card,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(name, style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium)
                val note = when {
                    !providerEnabled -> "供应商已停用，整个不参与"
                    tags.isNotBlank() -> tags
                    else -> null
                }
                if (note != null) {
                    Text(note, style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Switch(checked = enabled, onCheckedChange = {
                haptics.perform(HapticEngine.Kind.Select)
                onToggle(it)
            })
        }
    }
}
