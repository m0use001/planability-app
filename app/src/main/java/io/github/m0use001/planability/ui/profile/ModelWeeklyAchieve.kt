package io.github.m0use001.planability.ui.profile

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material3.AlertDialog
import io.github.m0use001.planability.ui.common.motionEnter
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.m0use001.planability.ai.AiClient
import io.github.m0use001.planability.app.PredictiveBackOverlay
import io.github.m0use001.planability.core.AppTime
import io.github.m0use001.planability.core.HapticEngine
import io.github.m0use001.planability.core.LocalHaptics
import io.github.m0use001.planability.core.formatMonthDay
import io.github.m0use001.planability.core.toLocalDate
import io.github.m0use001.planability.data.AchievementDef
import io.github.m0use001.planability.data.AchievementDefs
import io.github.m0use001.planability.data.AiModel
import io.github.m0use001.planability.data.AiProvider
import io.github.m0use001.planability.data.Graph
import io.github.m0use001.planability.data.MedalIcon
import io.github.m0use001.planability.data.ModelConfig
import io.github.m0use001.planability.data.WeeklyRecord
import io.github.m0use001.planability.ui.common.EmptyHint
import io.github.m0use001.planability.ui.common.formatMinutes
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters
import androidx.compose.material3.Checkbox
import androidx.compose.foundation.layout.heightIn
import io.github.m0use001.planability.ui.common.filterChipColors

// ==================== 模型设置（v7 多供应商） ====================

/** 本地模型在供应商列表里的哨兵 id（负数，不会与 Room 自增 id 冲突） */
private const val LOCAL_MODEL_ID = -1L

/**
 * 端侧引擎的 OpenAI 兼容端点（:localai 进程）。
 * 这条 provider 是给 AiClient/worker/压缩槽解析用的**数据记录**，不是云端供应商——
 * 列表里不单独显示，统一由最底部的「本地模型」条目管理（否则会出现两个本地模型框）。
 */
private fun io.github.m0use001.planability.data.AiProvider.isLocalEndpoint(): Boolean =
    baseUrl.trimEnd('/').startsWith("http://127.0.0.1:18181")

private fun providerIcon(p: io.github.m0use001.planability.data.AiProvider): ImageVector = when (p.iconKey) {
    "bolt" -> Icons.Rounded.Bolt
    else -> Icons.Rounded.Cloud
}

@Composable
private fun FieldLabel(text: String) {
    Text(
        text, style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun ProviderRow(
    p: io.github.m0use001.planability.data.AiProvider,
    selected: Boolean,
    onClick: () -> Unit
) {
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
        // R11：供应商图标是身份标记（装饰），选中态由容器承担 → 中性
        Icon(providerIcon(p), null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(p.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Text(
                p.baseUrl, style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
            )
        }
        Box(
            Modifier
                .size(8.dp)
                .background(
                    if (p.enabled) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.outlineVariant,
                    androidx.compose.foundation.shape.CircleShape
                )
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelManagerScreen(
    onDone: () -> Unit,
    /** 合并屏的顶部切换器（模型设置 ↔ 智能体设置）。见 ui/info/SettingsHub.kt */
    topSwitcher: (@Composable () -> Unit)? = null,
    /**
     * 调用方已经套了 [PredictiveBackOverlay]（合并屏 ModelSettingsHub 的做法）时为 true。
     *
     * 为什么需要：那个 overlay 是**全屏 Dialog（独立窗口）**。合并屏切 tab 是
     * `when(tab) { 0 -> 模型设置屏; 1 -> 智能体屏 }`，两个屏各自再套一层 overlay，
     * 一切换就是**两个 Dialog 窗口接连关掉再打开** —— 宿主窗口会从背后透出来一帧，
     * 用户看到的就是"页面闪一下"（2026-10-18 真机反馈）。
     * 所以由合并屏在最外层只套一层，这里收到 insideOverlay=true 就只画内容。
     */
    insideOverlay: Boolean = false,
    /** v77：板内钻入瓦片——透明底 + 板内页头 + 单列（列表+详情同列，防框套框），Rail 常驻 */
    embedded: Boolean = false
) {
    val state by Graph.aiProviders.state.collectAsState()
    val haptics = LocalHaptics.current
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var selectedId by remember { mutableStateOf<Long?>(null) }
    var apiKeyVisible by remember { mutableStateOf(false) }
    var showAddProvider by remember { mutableStateOf(false) }
    var showAddModel by remember { mutableStateOf(false) }
// v19.51：扫描模型弹层
var showScan by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var renameDialog by remember { mutableStateOf<io.github.m0use001.planability.data.AiProvider?>(null) }
    var renameText by remember { mutableStateOf("") }
    var confirmDelete by remember { mutableStateOf<io.github.m0use001.planability.data.AiProvider?>(null) }
    var editModel by remember { mutableStateOf<io.github.m0use001.planability.data.AiModel?>(null) }
    var editModelName by remember { mutableStateOf("") }
    var editModelTags by remember { mutableStateOf("") }
    var testStatus by remember { mutableStateOf<String?>(null) }
    var testing by remember { mutableStateOf(false) }

    LaunchedEffect(state.providers.size) {
        // 本地模型是列表里的哨兵项（id=LOCAL_MODEL_ID），不能被"首选供应商"逻辑覆盖；
        // 指向隐藏的本地端点 provider 时也重置（它不参与列表显示）。
        if (selectedId == LOCAL_MODEL_ID) return@LaunchedEffect
        val sel = state.providers.firstOrNull { it.id == selectedId }
        if (sel == null || sel.isLocalEndpoint()) {
            selectedId = state.providers.firstOrNull { !it.isLocalEndpoint() }?.id ?: LOCAL_MODEL_ID
        }
    }

    val provider = state.providers.firstOrNull { it.id == selectedId && !it.isLocalEndpoint() }
    val providerModels = state.models.filter { it.providerId == provider?.id }.sortedBy { it.id }

    // 内容抽成 lambda：insideOverlay=true 时直接画（overlay 由调用方提供），
    // false 时自己套一层。这样两个 tab 共用一个 Dialog 窗口，切换不再闪。
    val shell: @Composable () -> Unit = {
        Scaffold(
            // v77 板内钻入：embedded 时透明底 + 0 inset（框归 SplitBoards/壳层）
            containerColor = if (embedded) androidx.compose.ui.graphics.Color.Transparent
            else MaterialTheme.colorScheme.background,
            contentWindowInsets = if (embedded)
                androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0)
            else androidx.compose.material3.ScaffoldDefaults.contentWindowInsets,
            topBar = {
                Column(Modifier.fillMaxWidth()) {
                if (embedded) {
                    io.github.m0use001.planability.ui.common.BoardSubHeader(
                        title = "模型设置",
                        onBack = onDone,
                        actions = {
                            TextButton(onClick = { showAddProvider = true }) {
                                Icon(Icons.Rounded.Add, null, Modifier.size(16.dp))
                                Spacer(Modifier.width(2.dp))
                                Text("添加供应商")
                            }
                        }
                    )
                } else {
                TopAppBar(
                    title = { Text("模型设置", fontWeight = FontWeight.Bold) },
                    navigationIcon = {
                        // 顶栏返回 = 中性点击 → Tap
                        IconButton(onClick = { haptics.perform(HapticEngine.Kind.Tap); onDone() }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "返回") }
                    },
                    actions = {
                        TextButton(onClick = { showAddProvider = true }) {
                            Icon(Icons.Rounded.Add, null, Modifier.size(16.dp))
                            Spacer(Modifier.width(2.dp))
                            Text("添加供应商")
                        }
                    }
                )
                }
                // 切换器放在标题栏**之下**（与 TopAppBar 同 Column 合成单个 placeable）
                if (topSwitcher != null) {
                    Column(Modifier.fillMaxWidth()) { topSwitcher() }
                }

                }

            }
        ) { pad ->
            // v64（2026-11-20）：宽屏两板——左板=供应商列表 + 联网搜索/云端兜底，
            // 右板=选中供应商的详情（原来详情挂在同一列的最底部，宽屏上要滚到 very bottom）。
            androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize().padding(pad)) {
            val mmContentW = maxWidth
            val mmDetailW = io.github.m0use001.planability.ui.common.MasterDetail.detailWidthFor(mmContentW, content = false)
            val mmPane = io.github.m0use001.planability.ui.common.MasterDetail
                .paneOf(mmContentW, mmDetailW, hasSelection = selectedId != null)
            val mmTwo = !embedded && mmPane != io.github.m0use001.planability.ui.common.DetailPane.Single
            val listPart: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit = {
                val builtin = state.providers.filter { it.builtin }
                val custom = state.providers.filter { !it.builtin && !it.isLocalEndpoint() }
                Text("内置供应商", style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                builtin.forEach { p ->
                    ProviderRow(p, selected = p.id == selectedId) {
                        selectedId = p.id; haptics.perform(HapticEngine.Kind.Select)
                    }
                }
                Text("自定义供应商", style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                if (custom.isEmpty()) {
                    Text("还没有自定义供应商，点右上「添加供应商」", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                custom.forEach { p ->
                    ProviderRow(p, selected = p.id == selectedId) {
                        selectedId = p.id; haptics.perform(HapticEngine.Kind.Select)
                    }
                }

                // 本地模型（端侧 NPU）：与云端供应商同排，固定排在列表最底部；
                // 选中后管理动作在下方详情区（LocalModelDetail）。
                LocalModelRow(selected = selectedId == LOCAL_MODEL_ID) {
                    selectedId = LOCAL_MODEL_ID; haptics.perform(HapticEngine.Kind.Select)
                }

                // 2026-11-14：联网搜索主源（Tavily）的 key —— 它不属于「模型」但同属
                // AI 自己的能力配置，放这里用户找得到（模型设置页是填 key 的唯一入口页）。
                // 卡片实现在 ui/info/SearchProviderCard.kt（本文件只挂一行，phone-b 的
                // 供应商编辑逻辑一行未动）。
                Spacer(Modifier.height(6.dp))
                Text(
                    "联网搜索",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold
                )
                io.github.m0use001.planability.ui.info.SearchProviderCard()

                // 2026-11-14：DeepSeek 应用能力云开关（云搜索/云抓取/云 OCR 兜底）——
                // 同上，能力配置不放模型行内，挂模型设置页尾部（卡片实现在
                // ui/info/CloudCapabilityCard.kt，供应商编辑逻辑一行未动）。
                Spacer(Modifier.height(6.dp))
                Text(
                    "云端兜底",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold
                )
                io.github.m0use001.planability.ui.info.CloudCapabilityCard()
            }
            val detailPart: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit = {
                Spacer(Modifier.height(6.dp))
                Text(
                    "供应商详情 · " + if (selectedId == LOCAL_MODEL_ID) "本地模型" else (provider?.name ?: "未选择"),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold
                )
                val p = provider
                if (selectedId == LOCAL_MODEL_ID) {
                    LocalModelDetail()
                } else if (p == null) {
                    EmptyHint("先在上方选择一个供应商")
                } else {
                    Card(
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
                    ) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            // 2026-10-31 用户报「填 key 的输入框光标位置不对」：输入框原来直接绑定
                            // 仓库态（value = p.apiKey），每敲一键就 scope.launch 异步回写 + state 刷新，
                            // 回包一滞后，OutlinedTextField 内部维护的光标选择就被重置/跳到别处。
                            // 改成本地 TextFieldValue 持有文本+光标，落库防抖（500ms），
                            // 且落库时读**最新** provider——避免用滞后的 p 覆盖刚切的 apiFormat/enabled。
                            var baseUrlEdit by remember(p.id) { mutableStateOf(TextFieldValue(p.baseUrl)) }
                            var keyEdit by remember(p.id) { mutableStateOf(TextFieldValue(p.apiKey)) }
                            androidx.compose.runtime.LaunchedEffect(baseUrlEdit.text) {
                                if (baseUrlEdit.text == p.baseUrl) return@LaunchedEffect
                                kotlinx.coroutines.delay(500)
                                val fresh = Graph.aiProviders.state.value.providers
                                    .firstOrNull { it.id == p.id } ?: p
                                Graph.aiProviders.updateProvider(fresh.copy(baseUrl = baseUrlEdit.text))
                            }
                            androidx.compose.runtime.LaunchedEffect(keyEdit.text) {
                                if (keyEdit.text == p.apiKey) return@LaunchedEffect
                                kotlinx.coroutines.delay(500)
                                val fresh = Graph.aiProviders.state.value.providers
                                    .firstOrNull { it.id == p.id } ?: p
                                Graph.aiProviders.updateProvider(fresh.copy(apiKey = keyEdit.text))
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(providerIcon(p), null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                Spacer(Modifier.width(8.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(p.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                    Text(
                                        if (p.builtin) "内置供应商" else "自定义供应商",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Switch(
                                    checked = p.enabled,
                                    onCheckedChange = { on ->
                                        // 开关 = 选择 → Select
                                        haptics.perform(HapticEngine.Kind.Select)
                                        scope.launch { Graph.aiProviders.updateProvider(p.copy(enabled = on)) }
                                    }
                                )
                                Box {
                                    IconButton(onClick = { menuOpen = true }) { Icon(Icons.Rounded.MoreVert, "更多", Modifier.size(18.dp)) }
                                    androidx.compose.material3.DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                        androidx.compose.material3.DropdownMenuItem(
                                            modifier = Modifier.clip(RoundedCornerShape(10.dp)), // 2026-11-15 点击高亮圆角化（原来是整行直角方块）
                                            text = { Text("重命名") },
                                            onClick = { menuOpen = false; renameDialog = p; renameText = p.name },
                                            leadingIcon = { Icon(Icons.Rounded.Edit, null, Modifier.size(17.dp)) }
                                        )
                                        androidx.compose.material3.DropdownMenuItem(
                                            modifier = Modifier.clip(RoundedCornerShape(10.dp)), // 2026-11-15 点击高亮圆角化（原来是整行直角方块）
                                            text = { Text("删除", color = MaterialTheme.colorScheme.error) },
                                            onClick = { menuOpen = false; confirmDelete = p },
                                            leadingIcon = { Icon(Icons.Rounded.Delete, null, Modifier.size(17.dp), tint = MaterialTheme.colorScheme.error) }
                                        )
                                    }
                                }
                            }
                            FieldLabel("Base URL")
                            OutlinedTextField(
                                value = baseUrlEdit,
                                onValueChange = { baseUrlEdit = it },
                                singleLine = true, modifier = Modifier.fillMaxWidth()
                            )
                            FieldLabel("API 格式")
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                // API 格式 = 选择 → Select
                                FilterChip(
                                    colors = filterChipColors(),
                                    selected = p.apiFormat == "openai",
                                    onClick = { haptics.perform(HapticEngine.Kind.Select); scope.launch { Graph.aiProviders.updateProvider(p.copy(apiFormat = "openai")) } },
                                    label = { Text("OpenAI Completions") }
                                )
                                FilterChip(
                                    colors = filterChipColors(),
                                    selected = p.apiFormat == "anthropic",
                                    onClick = { haptics.perform(HapticEngine.Kind.Select); scope.launch { Graph.aiProviders.updateProvider(p.copy(apiFormat = "anthropic")) } },
                                    label = { Text("Anthropic Messages") }
                                )
                            }
                            FieldLabel("API Key")
                            OutlinedTextField(
                                value = keyEdit,
                                onValueChange = { keyEdit = it },
                                singleLine = true,
                                visualTransformation = if (apiKeyVisible) androidx.compose.ui.text.input.VisualTransformation.None
                                else androidx.compose.ui.text.input.PasswordVisualTransformation(),
                                trailingIcon = {
                                    IconButton(onClick = { apiKeyVisible = !apiKeyVisible }) {
                                        Icon(
                                            if (apiKeyVisible) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                                            "显示/隐藏", Modifier.size(18.dp)
                                        )
                                    }
                                }
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                OutlinedButton(
                                    onClick = {
                                        testing = true; testStatus = null
                                        scope.launch {
                                            val started = System.currentTimeMillis()
                                            val m = providerModels.firstOrNull { it.enabled } ?: providerModels.firstOrNull()
                                            if (m == null) {
                                                testStatus = "该供应商还没有可用模型"; testing = false
                                            } else {
                                                val cfg = ModelConfig(
                                                    provider = "p" + p.id, baseUrl = p.baseUrl, apiKey = p.apiKey,
                                                    modelName = m.name, enabled = true, authStyle = p.authStyle,
                                                    apiFormat = p.apiFormat
                                                )
                                                runCatching { AiClient.chatWith(cfg, "你是一个回声测试器。", "回复：连接成功", 0.0) }
                                                    .onSuccess {
                                                        testStatus = "连接成功 ✓ ${cfg.modelName}（${System.currentTimeMillis() - started}ms）"
                                                        haptics.perform(HapticEngine.Kind.Success)
                                                    }
                                                    .onFailure { testStatus = "失败：${it.message}" }
                                                testing = false
                                            }
                                        }
                                    },
                                    enabled = !testing
                                ) { Text(if (testing) "测试中…" else "测试连接") }
                                if (p.name.contains("BigModel") || p.baseUrl.contains("bigmodel")) {
                                    TextButton(onClick = {
                                        runCatching {
                                            context.startActivity(
                                                android.content.Intent(
                                                    android.content.Intent.ACTION_VIEW,
                                                    android.net.Uri.parse("https://open.bigmodel.cn/usercenter/apikeys")
                                                )
                                            )
                                        }
                                    }) { Text("获取 API Key ↗") }
                                }
                            }
                            testStatus?.let {
                                Text(it, style = MaterialTheme.typography.bodySmall,
                                    color = if (it.startsWith("连接成功")) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.error)
                            }
                        }
                    }

                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "模型列表（${providerModels.size}）",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = { showAddModel = true }) {
                            Icon(Icons.Rounded.Add, null, Modifier.size(15.dp))
                            Spacer(Modifier.width(2.dp))
                            Text("添加模型")
                        }
                            TextButton(onClick = { showScan = true }) {
                                Icon(Icons.Rounded.CloudDownload, null, Modifier.size(15.dp))
                                Spacer(Modifier.width(2.dp))
                                Text("扫描模型")
                            }
                    }
                    if (providerModels.isEmpty()) {
                        Text("还没有模型，点上方「添加模型」", style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    providerModels.forEach { m ->
                        Card(
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                                Column(Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(m.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                                        m.tags.split(",").map { it.trim() }.filter { it.isNotEmpty() }.forEach { t ->
                                            Text(
                                                t, style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.padding(start = 6.dp)
                                            )
                                        }
                                    }
                                    Text(
                                        if (m.enabled) "已启用" else "已停用",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                IconButton(onClick = { editModel = m; editModelName = m.name; editModelTags = m.tags }, modifier = Modifier.size(30.dp)) {
                                    Icon(Icons.Rounded.Edit, "编辑", Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Switch(
                                    checked = m.enabled,
                                    onCheckedChange = { on ->
                                        // 开关 = 选择 → Select
                                        haptics.perform(HapticEngine.Kind.Select)
                                        scope.launch { Graph.aiProviders.updateModel(m.copy(enabled = on)) }
                                    }
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.height(4.dp))
                Text(
                    "主模型：" + (state.active?.modelName ?: "未设置") + "（" + (state.active?.providerName ?: "-") + "）" +
                        " · 轻任务/智能体：" + (state.worker?.modelName ?: "跟随主模型"),
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    "说明：所有 AI 能力均为本地发起的 HTTPS 请求，Key 仅保存在本机数据库。对话页输入卡可按会话切换模型；支持 OpenAI Completions 与 Anthropic Messages 两种协议。",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(20.dp))
            }
            // v64：两板（左=列表板，右=详情板）或原来的单列
            if (mmTwo) {
                io.github.m0use001.planability.ui.common.SplitBoards(
                    twoPane = true,
                    detailWidth = mmDetailW,
                    applyTopInset = false,
                    applyBottomInset = false,
                    primary = {
                        Column(
                            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) { listPart() }
                    },
                    secondary = {
                        Column(
                            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) { detailPart() }
                    }
                )
            } else {
                Column(
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    listPart()
                    detailPart()
                }
            }
            }
        }
    }
    // v64：宽屏整屏承载两板（不再收成居中 1000dp 模态卡）
    val mmWide = io.github.m0use001.planability.core.rememberWindowSizeClass().isWide
    if (insideOverlay) shell() else PredictiveBackOverlay(onBack = onDone, fullscreen = mmWide) { shell() }

    if (showAddProvider) {
        AddProviderDialog(
            onDismiss = { showAddProvider = false },
            onCreate = { name, url, fmt, key ->
                scope.launch {
                    val id = Graph.aiProviders.createProvider(name, url, fmt, key)
                    showAddProvider = false
                    selectedId = id
                }
            }
        )
    }
    if (showScan && provider != null) {
        ScanModelsDialog(
            provider = provider,
            existing = providerModels.map { it.name }.toSet(),
            onDismiss = { showScan = false },
            onAdd = { names ->
                scope.launch {
                    names.forEach { n ->
                        if (n.isNotBlank()) Graph.aiProviders.addModel(provider.id, n, "")
                    }
                    showScan = false
                }
            }
        )
    }
    if (showAddModel && provider != null) {
        AddModelDialog(
            onDismiss = { showAddModel = false },
            onAdd = { name, tags ->
                scope.launch {
                    Graph.aiProviders.addModel(provider.id, name, tags)
                    showAddModel = false
                }
            }
        )
    }
    renameDialog?.let { rp ->
        AlertDialog(
            modifier = Modifier.motionEnter(),
            onDismissRequest = { renameDialog = null },
            title = { Text("重命名供应商", style = MaterialTheme.typography.titleSmall) },
            text = { OutlinedTextField(value = renameText, onValueChange = { renameText = it }, singleLine = true) },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch { Graph.aiProviders.updateProvider(rp.copy(name = renameText.trim())) }
                    renameDialog = null
                }) { Text("确定") }
            },
            dismissButton = { TextButton(onClick = { renameDialog = null }) { Text("取消") } }
        )
    }
    confirmDelete?.let { cd ->
        io.github.m0use001.planability.ui.common.ConfirmDialog(
            title = "删除供应商",
            text = "将删除「${cd.name}」及其模型列表（对话数据不受影响）。",
            onConfirm = {
                scope.launch { Graph.aiProviders.deleteProvider(cd.id) }
                if (selectedId == cd.id) selectedId = null
                confirmDelete = null
            },
            onDismiss = { confirmDelete = null }
        )
    }
    editModel?.let { em ->
        AlertDialog(
            modifier = Modifier.motionEnter(),
            onDismissRequest = { editModel = null },
            title = { Text("编辑模型", style = MaterialTheme.typography.titleSmall) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = editModelName, onValueChange = { editModelName = it }, singleLine = true, label = { Text("模型名") })
                    OutlinedTextField(value = editModelTags, onValueChange = { editModelTags = it }, singleLine = true, label = { Text("标签（逗号分隔，如 1M,视觉）") })
                }
            },
            confirmButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = {
                        scope.launch { Graph.aiProviders.deleteModel(em.id) }
                        editModel = null
                    }) { Text("删除", color = MaterialTheme.colorScheme.error) }
                    TextButton(onClick = {
                        scope.launch { Graph.aiProviders.updateModel(em.copy(name = editModelName.trim(), tags = editModelTags.trim())) }
                        editModel = null
                    }) { Text("确定") }
                }
            },
            dismissButton = { TextButton(onClick = { editModel = null }) { Text("取消") } }
        )
    }
}

@Composable
private fun AddProviderDialog(onDismiss: () -> Unit, onCreate: (String, String, String, String) -> Unit) {
    var name by remember { mutableStateOf("") }
    var baseUrl by remember { mutableStateOf("") }
    var apiKey by remember { mutableStateOf("") }
    AlertDialog(
        modifier = Modifier.motionEnter(),
        onDismissRequest = onDismiss,
        title = { Text("添加供应商", style = MaterialTheme.typography.titleSmall) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true, label = { Text("名称") })
                OutlinedTextField(value = baseUrl, onValueChange = { baseUrl = it }, singleLine = true, label = { Text("Base URL（OpenAI 兼容）") })
                OutlinedTextField(value = apiKey, onValueChange = { apiKey = it }, singleLine = true, label = { Text("API Key") })
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onCreate(name, baseUrl, "openai", apiKey) },
                enabled = name.isNotBlank() && baseUrl.isNotBlank()
            ) { Text("创建") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable
private fun AddModelDialog(onDismiss: () -> Unit, onAdd: (String, String) -> Unit) {
    var name by remember { mutableStateOf("") }
    var tags by remember { mutableStateOf("") }
    AlertDialog(
        modifier = Modifier.motionEnter(),
        onDismissRequest = onDismiss,
        title = { Text("添加模型", style = MaterialTheme.typography.titleSmall) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true, label = { Text("模型名") })
                OutlinedTextField(value = tags, onValueChange = { tags = it }, singleLine = true, label = { Text("标签（逗号分隔，可空）") })
            }
        },
        confirmButton = {
            TextButton(onClick = { onAdd(name, tags) }, enabled = name.isNotBlank()) { Text("添加") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}


// ==================== 周小结 ====================

@Composable
private fun VoiceModelSection() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val state by io.github.m0use001.planability.core.VoskManager.state.collectAsState()
    val ready = io.github.m0use001.planability.core.VoskManager.isReady(context)

    Card(shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                when (val s = state) {
                    is io.github.m0use001.planability.core.VoskManager.State.Downloading -> "下载中 ${s.progress}%"
                    is io.github.m0use001.planability.core.VoskManager.State.Unpacking -> "解压中 ${s.progress}%"
                    is io.github.m0use001.planability.core.VoskManager.State.Error -> s.message
                    else -> if (ready) "已就绪 · vosk-model-small-cn（可离线识别）" else "未下载"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = if (state is io.github.m0use001.planability.core.VoskManager.State.Error)
                    MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!ready) {
                    OutlinedButton(
                        onClick = {
                            if (state !is io.github.m0use001.planability.core.VoskManager.State.Downloading) {
                                scope.launch { io.github.m0use001.planability.core.VoskManager.downloadModel(context) }
                            }
                        },
                        enabled = state is io.github.m0use001.planability.core.VoskManager.State.Idle
                            || state is io.github.m0use001.planability.core.VoskManager.State.Error
                    ) { Text("下载（约 45MB）") }
                } else {
                    OutlinedButton(onClick = {
                        io.github.m0use001.planability.core.VoskManager.deleteModel(context)
                    }) { Text("删除模型") }
                }
            }
            Text(
                "模型来自 alphacephei.com 开源项目 Vosk，下载后完全离线运行，可用于首页语音输入与笔记录音转写。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WeeklySummaryScreen(onDone: () -> Unit, deferBack: Boolean = false) {
    val scope = rememberCoroutineScope()
    val haptics = LocalHaptics.current
    val monday = AppTime.today().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    var current by remember { mutableStateOf<WeeklyRecord?>(null) }
    var history by remember { mutableStateOf<List<WeeklyRecord>>(emptyList()) }
    var statsText by remember { mutableStateOf("") }
    var generating by remember { mutableStateOf(false) }
    val version by Graph.dataVersion.flow.collectAsState()

    LaunchedEffect(version) {
        current = Graph.db.weeklyRecordDao().latestOfWeek(AppTime.dayKey(monday))
        history = Graph.info.weeklyRecords().filter { it.weekStart != AppTime.dayKey(monday) }
        val from = monday
        val to = AppTime.today()
        val focus = Graph.focus.sessionsBetween(from, to).filter { it.completed }
            .sumOf { (it.end - it.start) / 60000 }.toInt()
        val done = Graph.plan.doneLogs(from, to).size
        val sleepWeek = Graph.sleep.since(from.toEpochDay())
        val sleepAvg = if (sleepWeek.isNotEmpty()) sleepWeek.sumOf { it.durationMin } / sleepWeek.size else 0
        statsText = "本周（${monday.formatMonthDay()} 起）：完成待办 $done 个，专注 ${formatMinutes(focus)}。" +
            if (sleepWeek.isNotEmpty()) " 睡眠 ${sleepWeek.size} 天，平均 ${sleepAvg / 60} 小时 ${sleepAvg % 60} 分。" else ""
    }

    fun generate() {
        generating = true
        scope.launch {
            val focus = Graph.focus.sessionsBetween(monday, AppTime.today()).filter { it.completed }
                .sumOf { (it.end - it.start) / 60000 }.toInt()
            val done = Graph.plan.doneLogs(monday, AppTime.today()).size
            val schedules = Graph.plan.scheduleOccurrences(monday, AppTime.today()).size
            val notes = Graph.notes.noteCount()
            val sleepWeek = Graph.sleep.since(monday.toEpochDay())
            val sleepPart = if (sleepWeek.isNotEmpty()) {
                val avg = sleepWeek.sumOf { it.durationMin } / sleepWeek.size
                "，睡眠 ${sleepWeek.size} 天（平均 ${avg / 60} 小时 ${avg % 60} 分，平均入睡 " +
                    "${sleepWeek.map { it.fallMin }.average().toInt() / 60}:${"%02d".format(sleepWeek.map { it.fallMin }.average().toInt() % 60)}）"
            } else ""
            val prompt = "本周完成待办 $done 个；专注 ${formatMinutes(focus)}（约 ${focus / 25} 个番茄）；安排日程 $schedules 场；写下笔记 $notes 篇$sleepPart。"
            val (text, source) = runCatching { AiClient.chat(AiClient.WEEKLY_SYSTEM, prompt) }
                .map { it to "llm" }
                .getOrElse {
                    val parts = mutableListOf<String>()
                    parts += if (done >= 5) "本周完成了 $done 件待办，节奏保持得不错。" else "本周完成 $done 件待办，还有余力可以再推进一些。"
                    parts += if (focus >= 120) "累计专注 ${formatMinutes(focus)}，专注习惯正在成形。" else "专注 ${formatMinutes(focus)}，试着每天留一个完整的番茄钟给自己。"
                    parts += "下周挑一件最重要的事，提前放进日程里。"
                    parts.joinToString(" ") to "template"
                }
            Graph.info.saveWeekly(
                WeeklyRecord(weekStart = AppTime.dayKey(monday), text = text, source = source)
            )
            haptics.perform(HapticEngine.Kind.Success)
            generating = false
        }
    }

    PredictiveBackOverlay(onBack = onDone, deferBack = deferBack) {
        Scaffold(
            containerColor = if (deferBack) androidx.compose.ui.graphics.Color.Transparent else androidx.compose.material3.MaterialTheme.colorScheme.background,topBar = { if (deferBack) io.github.m0use001.planability.ui.common.BoardSubHeader("周小结", onDone) else TopAppBar(navigationIcon = {
            // 顶栏返回 = 中性点击 → Tap
            IconButton(onClick = { haptics.perform(HapticEngine.Kind.Tap); onDone() }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "返回") }
        }, title = { Text("周小结", fontWeight = FontWeight.SemiBold) }) }) { pad ->
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(pad)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                statsText.let {
                    Card(shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                        Text(it, Modifier.padding(14.dp), style = MaterialTheme.typography.bodyMedium)
                    }
                }
                OutlinedButton(onClick = { generate() }, enabled = !generating, modifier = Modifier.fillMaxWidth()) {
                    Text(if (generating) "生成中…" else "生成本周小结")
                }
                current?.let { rec ->
                    Card(shape = RoundedCornerShape(18.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                        Column(Modifier.padding(16.dp)) {
                            Text("本周小结 · ${if (rec.source == "llm") "AI 生成" else "模板"}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer)
                            Spacer(Modifier.height(6.dp))
                            Text(rec.text, style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onPrimaryContainer)
                        }
                    }
                }
                if (history.isNotEmpty()) {
                    Text("历史", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                    history.forEach { rec ->
                        Card(shape = RoundedCornerShape(14.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                            Column(Modifier.padding(12.dp)) {
                                Text(rec.weekStart.toLocalDate().formatMonthDay() + " 那周",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(rec.text, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
                Spacer(Modifier.height(30.dp))
            }
        }
    }
}

// ==================== 成就墙（Canvas 程序化勋章） ====================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AchievementWallScreen(onDone: () -> Unit, deferBack: Boolean = false) {
    val haptics = LocalHaptics.current
    var unlocked by remember { mutableStateOf<Map<String, Long>>(emptyMap()) }

    LaunchedEffect(Unit) { unlocked = Graph.achievements.unlocked() }

    PredictiveBackOverlay(onBack = onDone, deferBack = deferBack) {
        Scaffold(
            containerColor = if (deferBack) androidx.compose.ui.graphics.Color.Transparent else androidx.compose.material3.MaterialTheme.colorScheme.background,topBar = { if (deferBack) io.github.m0use001.planability.ui.common.BoardSubHeader("成就墙", onDone) else TopAppBar(navigationIcon = {
            // 顶栏返回 = 中性点击 → Tap
            IconButton(onClick = { haptics.perform(HapticEngine.Kind.Tap); onDone() }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "返回") }
        }, title = { Text("成就墙", fontWeight = FontWeight.SemiBold) }) }) { pad ->
            Column(Modifier.fillMaxSize().padding(pad).statusBarsPadding()) {
                Text(
                    "已解锁 ${unlocked.size} / ${AchievementDefs.ALL.size}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
                )
                LazyVerticalGrid(
                    // v59 平板：Fixed(3) 在 1000dp 模态卡里每格 ~320dp、奖章只有 84dp，
                    // 格内大片空白。Adaptive(140dp)：手机仍 2–3 列，宽屏自然增多列。
                    columns = GridCells.Adaptive(140.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(AchievementDefs.ALL, key = { it.key }) { def ->
                        val isUnlocked = unlocked.containsKey(def.key)
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Medal(def, isUnlocked, Modifier.size(84.dp))
                            Spacer(Modifier.height(6.dp))
                            Text(def.title, style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = if (isUnlocked) MaterialTheme.colorScheme.onSurface
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center)
                            Text(def.desc, style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center, maxLines = 2)
                        }
                    }
                }
            }
        }
    }
}

/** 统一圆形奖章底座（外环+圆盘），中心图案按类别几何绘制；未解锁灰化+锁 */
@Composable
fun Medal(def: AchievementDef, isUnlocked: Boolean, modifier: Modifier = Modifier) {
    val accent = if (isUnlocked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
    val disc = if (isUnlocked) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
    val fg = if (isUnlocked) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.outline
    Canvas(modifier) {
        val w = size.width
        drawCircle(accent, radius = w / 2)
        drawCircle(disc, radius = w / 2 - w * 0.07f)
        drawCircle(accent, radius = w / 2 - w * 0.10f, style = Stroke(w * 0.02f))
        translate(w * 0.28f, w * 0.28f) {
            drawMedalIcon(def.icon, fg, accent)
        }
        if (!isUnlocked) {
            drawCircle(Color.Gray.copy(alpha = 0.35f), radius = w / 2 - w * 0.10f)
            // 锁形
            val lw = w * 0.3f
            val lx = w / 2 - lw / 2
            val ly = w / 2 - lw * 0.1f
            drawRoundRect(
                Color(0xFF616161),
                topLeft = Offset(lx, ly),
                size = androidx.compose.ui.geometry.Size(lw, lw * 0.72f),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(lw * 0.16f, lw * 0.16f)
            )
            drawCircle(
                Color(0xFF616161), radius = lw * 0.22f,
                center = Offset(w / 2, ly),
                style = Stroke(lw * 0.09f)
            )
        }
    }
}

private fun DrawScope.drawMedalIcon(icon: MedalIcon, fg: Color, accent: Color) {
    // 图标绘制在 0..1 逻辑坐标，映射到勋章中心 0.44 宽的区域
    val s = size.minDimension * 0.44f
    fun p(x: Float, y: Float) = Offset(x * s, y * s)
    val stroke = Stroke(s * 0.07f, cap = StrokeCap.Round)
    when (icon) {
        MedalIcon.Tree -> {
            drawLine(fg, p(0.5f, 1f), p(0.5f, 0.55f), strokeWidth = s * 0.08f)
            drawCircle(fg, radius = s * 0.3f, center = p(0.5f, 0.38f))
        }
        MedalIcon.Star -> repeat(5) { i ->
            rotate(i * 72f, pivot = p(0.5f, 0.5f)) {
                drawLine(accent, p(0.5f, 0.12f), p(0.5f, 0.42f), strokeWidth = s * 0.08f)
            }
        }
        MedalIcon.Fire -> {
            drawCircle(accent, radius = s * 0.22f, center = p(0.5f, 0.62f))
            drawCircle(fg, radius = s * 0.12f, center = p(0.5f, 0.35f))
        }
        MedalIcon.Clock -> {
            drawCircle(fg, radius = s * 0.36f, center = p(0.5f, 0.5f), style = stroke)
            drawLine(fg, p(0.5f, 0.5f), p(0.5f, 0.28f), strokeWidth = s * 0.06f)
            drawLine(fg, p(0.5f, 0.5f), p(0.66f, 0.56f), strokeWidth = s * 0.06f)
        }
        MedalIcon.Bolt -> {
            val path = androidx.compose.ui.graphics.Path().apply {
                moveTo(0.55f * s, 0.1f * s); lineTo(0.3f * s, 0.55f * s)
                lineTo(0.48f * s, 0.55f * s); lineTo(0.42f * s, 0.95f * s)
                lineTo(0.72f * s, 0.45f * s); lineTo(0.52f * s, 0.45f * s)
                close()
            }
            drawPath(path, accent)
        }
        MedalIcon.Shield -> {
            val path = androidx.compose.ui.graphics.Path().apply {
                moveTo(0.5f * s, 0.1f * s); lineTo(0.85f * s, 0.25f * s)
                lineTo(0.85f * s, 0.55f * s); lineTo(0.5f * s, 0.92f * s)
                lineTo(0.15f * s, 0.55f * s); lineTo(0.15f * s, 0.25f * s)
                close()
            }
            drawPath(path, fg, style = stroke)
        }
        MedalIcon.Moon -> drawArc(
            fg, startAngle = 90f, sweepAngle = 270f, useCenter = true,
            topLeft = p(0.2f, 0.2f), size = androidx.compose.ui.geometry.Size(s * 0.6f, s * 0.6f)
        )
        MedalIcon.Pen -> {
            drawLine(fg, p(0.2f, 0.85f), p(0.7f, 0.25f), strokeWidth = s * 0.09f)
            drawLine(accent, p(0.7f, 0.25f), p(0.82f, 0.12f), strokeWidth = s * 0.09f)
        }
        MedalIcon.Folder -> drawRoundRect(
            fg, topLeft = p(0.15f, 0.3f),
            size = androidx.compose.ui.geometry.Size(s * 0.7f, s * 0.45f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(s * 0.06f, s * 0.06f)
        )
        MedalIcon.Doc -> {
            drawRoundRect(fg, topLeft = p(0.25f, 0.12f),
                size = androidx.compose.ui.geometry.Size(s * 0.5f, s * 0.76f),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(s * 0.05f, s * 0.05f), style = stroke)
            for (i in 0..2) drawLine(fg, p(0.34f, 0.32f + i * 0.16f), p(0.66f, 0.32f + i * 0.16f), strokeWidth = s * 0.04f)
        }
        MedalIcon.Wave -> for (i in 0..2) {
            drawArc(fg, startAngle = 180f, sweepAngle = 180f, useCenter = false,
                topLeft = p(0.2f, 0.3f + i * 0.18f),
                size = androidx.compose.ui.geometry.Size(s * 0.3f, s * 0.16f), style = stroke)
            drawArc(fg, startAngle = 0f, sweepAngle = 180f, useCenter = false,
                topLeft = p(0.5f, 0.3f + i * 0.18f),
                size = androidx.compose.ui.geometry.Size(s * 0.3f, s * 0.16f), style = stroke)
        }
        MedalIcon.Brush -> {
            drawLine(fg, p(0.3f, 0.8f), p(0.65f, 0.3f), strokeWidth = s * 0.08f)
            drawCircle(accent, radius = s * 0.12f, center = p(0.72f, 0.2f))
        }
        MedalIcon.Building -> {
            drawRect(fg, topLeft = p(0.25f, 0.3f), size = androidx.compose.ui.geometry.Size(s * 0.5f, s * 0.65f))
            for (r in 0..2) for (c in 0..1)
                drawRect(accent, topLeft = p(0.34f + c * 0.2f, 0.4f + r * 0.16f),
                    size = androidx.compose.ui.geometry.Size(s * 0.1f, s * 0.08f))
        }
        MedalIcon.Lotus -> {
            for (i in 0..4) rotate((i - 2) * 25f, pivot = p(0.5f, 0.85f)) {
                drawArc(accent, startAngle = 200f, sweepAngle = 140f, useCenter = true,
                    topLeft = p(0.38f, 0.3f), size = androidx.compose.ui.geometry.Size(s * 0.24f, s * 0.6f))
            }
        }
        MedalIcon.Check -> {
            drawLine(fg, p(0.22f, 0.52f), p(0.45f, 0.75f), strokeWidth = s * 0.12f)
            drawLine(fg, p(0.45f, 0.75f), p(0.82f, 0.25f), strokeWidth = s * 0.12f)
        }
        MedalIcon.Flag -> {
            drawLine(fg, p(0.3f, 0.1f), p(0.3f, 0.95f), strokeWidth = s * 0.07f)
            drawRoundRect(accent, topLeft = p(0.33f, 0.14f),
                size = androidx.compose.ui.geometry.Size(s * 0.45f, s * 0.3f),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(s * 0.04f, s * 0.04f))
        }
    }
}

/**
 * v19.51 扫描模型弹层：拉供应商的 /models 目录，多选后批量加入。
 *
 * ## 为什么做成"拉取 + 多选"而不是"一键全加"
 * NVIDIA NIM 一次返回几百款（含各种语言/尺寸/专用模型），全加会把选择列表撑爆，
 * 而且大多数人只想要其中两三款。所以默认勾选免费档那几个，其余留给用户按需勾。
 *
 * ## 关于"免费"标记
 * **NIM 的 /models 不返回价格，运行时判断不了免费**，所以免费档是一份内置清单
 * [NVIDIA_FREE_MODELS]（用户 10-18 给的那 5 个，id 已用 /models 核对过）。
 * 清单会过期，但作用只是"默认勾几个、省得翻 81 行"——用户随时可改，
 * 而模型名与 tag 都以实时拉回来的目录为准。
 */
@Composable
private fun ScanModelsDialog(
    provider: io.github.m0use001.planability.data.AiProvider,
    existing: Set<String>,
    onDismiss: () -> Unit,
    onAdd: (List<String>) -> Unit
) {
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var found by remember { mutableStateOf<List<String>>(emptyList()) }
    val checked = remember { mutableStateOf(setOf<String>()) }

    androidx.compose.runtime.LaunchedEffect(provider.id) {
        loading = true; error = null
        val cfg = io.github.m0use001.planability.data.ModelConfig(
            provider = "p" + provider.id, baseUrl = provider.baseUrl, apiKey = provider.apiKey,
            modelName = "", enabled = true, authStyle = provider.authStyle, apiFormat = provider.apiFormat
        )
        runCatching { io.github.m0use001.planability.ai.AiClient.fetchModels(cfg) }
            .onSuccess { list ->
                // 免费款**置顶**：NIM 一次返回 81 行，用户真正想要的免费那 5 个按原始顺序
                // 散在目录中段，得翻半天（用户 10-18 反馈"我说的那几个模型也没看到"）。
                // sortedWith 走 TimSort，同类内部保持目录原顺序，不会打乱。
                found = list.sortedWith(compareByDescending { isFreeModel(it) })
                // 默认勾选：免费的（NVIDIA 清单命中）+ DeepSeek 全量（只两款）
                checked.value = found.filter { it !in existing && isFreeModel(it) }.toSet()
                loading = false
            }
            .onFailure {
                error = it.message ?: "扫描失败"
                loading = false
            }
    }

    AlertDialog(
        modifier = Modifier.motionEnter(),
        onDismissRequest = onDismiss,
        title = { Text("扫描 ${provider.name} 的模型") },
        text = {
            when {
                loading -> Text("正在拉取模型目录…")
                error != null -> Text("失败：$error\n\n请检查网络与 API Key。")
                found.isEmpty() -> Text("该供应商没有返回任何模型。")
                else -> Column {
                    val marked = found.count { isFreeModel(it) }
                    Text(
                        "共 ${found.size} 款，其中免费 $marked 款已默认勾选。已有 " +
                            "${found.count { it in existing }} 款在列表里，不会重复添加。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                        found.forEach { name ->
                            val has = name in existing
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    // 2026-11-20：可点行无底，水波本来是直角横杠 → 10dp 圆角裁剪
                                    .clip(RoundedCornerShape(10.dp))
                                    .clickable(enabled = !has) {
                                        checked.value = if (name in checked.value)
                                            checked.value - name else checked.value + name
                                    }
                                    .padding(vertical = 4.dp)
                            ) {
                                Checkbox(
                                    checked = name in checked.value,
                                    onCheckedChange = { on ->
                                        checked.value = if (on) checked.value + name
                                        else checked.value - name
                                    },
                                    enabled = !has
                                )
                                Text(
                                    name,
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.weight(1f),
                                    maxLines = 1
                                )
                                if (has) {
                                    Text("已有", style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                } else if (isFreeModel(name)) {
                                    Text("免费", style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary)
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onAdd(checked.value.toList()) },
                enabled = checked.value.isNotEmpty()
            ) { Text("添加${if (checked.value.isNotEmpty()) " ${checked.value.size} 款" else ""}") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

/** v19.51：NVIDIA 免费档清单（离线内置；NIM 的 /models 不返回价格，只能本地判） */
private val NVIDIA_FREE_MODELS = setOf(
    // v19.57：用户 10-18 给的免费档清单（他 OpenCode 里 NVIDIA provider 那 5 个）。
    // id 已用 https://integrate.api.nvidia.com/v1/models 核对过（不是照名字猜的，
    // 名字和 id 对不上是常态：GLM-5.3 的真实 id 前缀是 z-ai/，Step 5 是 step-5-preview）。
    //   DeepSeek V4.1 Flash   -> deepseek-ai/deepseek-v4.1-flash
    //   DiffusionGemma 26B    -> google/diffusiongemma-26b-a4b-it
    //   GLM-5.3               -> z-ai/glm-5.3
    //   GLM-5.3 Flash         -> z-ai/glm-5.3-flash
    //   Step 5 Preview        -> stepfun-ai/step-5-preview
    "deepseek-ai/deepseek-v4.1-flash",
    "google/diffusiongemma-26b-a4b-it",
    "z-ai/glm-5.3",
    "z-ai/glm-5.3-flash",
    "stepfun-ai/step-5-preview"
)

private fun isFreeModel(name: String): Boolean =
    // 「deepseek-」前缀这条**只认裸形式**（DeepSeek 官方目录就是 deepseek-flash /
    // deepseek-v4-pro 这种没斜杠的）。v19.58：原来只写 startsWith("deepseek-")，
    // 结果把 NVIDIA 目录里的 deepseek-ai/** 一整类都命中了
    // （前 9 个字符正好是 "deepseek-"）—— 实测把 deepseek-coder-6.7b-instruct
    // 也标成免费并默认勾选，而它不在用户的免费清单里。NVIDIA 的免费款只认
    // NVIDIA_FREE_MODELS 里那 5 个，别用前缀猜。
    (name.startsWith("deepseek-") && !name.contains('/')) || name in NVIDIA_FREE_MODELS

