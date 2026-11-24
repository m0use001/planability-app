@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package io.github.m0use001.planability.ui.info

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import io.github.m0use001.planability.ui.common.motionEnter
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import io.github.m0use001.planability.app.LocalOverlayClose
import io.github.m0use001.planability.app.PredictiveBackOverlay
import io.github.m0use001.planability.core.HapticEngine
import io.github.m0use001.planability.core.LocalHaptics
import io.github.m0use001.planability.core.reachedEndHaptic
import io.github.m0use001.planability.ai.AgentCard
import io.github.m0use001.planability.ai.lore.LoreEngine
import io.github.m0use001.planability.data.Assistant
import io.github.m0use001.planability.data.Graph
import io.github.m0use001.planability.data.LoreEntry
import io.github.m0use001.planability.data.card.CardIo
import io.github.m0use001.planability.data.Project
import io.github.m0use001.planability.data.ProjectFile
import io.github.m0use001.planability.data.ProjectFileRepo
import io.github.m0use001.planability.ui.common.AppIcons
import io.github.m0use001.planability.ui.common.EmptyHint
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ==================== v12 智能体设置 ====================

/**
 * 进程级缓存：上一次加载成功的智能体列表。
 *
 * 为什么需要：[AssistantsScreen] 是 `remember { mutableStateOf(emptyList()) }` + `LaunchedEffect` 异步读库，
 * 合并屏（ModelSettingsHub）切到这一页时 composition 重建，list 先回到空 →
 * 先闪一帧「暂无智能体」再弹出列表 —— 用户 10-18 反馈的"切换会闪一下"就是这个。
 * 有了这份缓存，第二次起画面时列表**第一帧就在**，闪态消失。
 * （首次进页面仍会空一帧，那是 DB 异步读不可避免的；刷新逻辑不变，
 *   任何增删都走 reload() 覆盖缓存。）
 */
private object AssistantsCache {
    @Volatile
    var list: List<Assistant> = emptyList()
}

/** 智能体（assistants 表）可视化管理：内置 5 个可改人格提示词，自定义可增删（温度为内部默认值，不暴露） */
@Composable
fun AssistantsScreen(
    onDone: () -> Unit,
    /** 合并屏的顶部切换器（模型设置 ↔ 智能体设置）。见 ui/info/SettingsHub.kt */
    topSwitcher: (@Composable () -> Unit)? = null,
    /**
     * 调用方已经套了 [PredictiveBackOverlay]（ModelSettingsHub）时为 true。
     *
     * 与 ModelManagerScreen 同名参数同一理由：那个 overlay 是全屏 Dialog（独立窗口），
     * 两个 tab 各套一层的话一切换就销毁+重建两个窗口，屏幕会闪一下。
     * 这里为 true 时只画内容，返回键经 LocalOverlayClose 走调用方那层 overlay。
     */
    insideOverlay: Boolean = false,
    /** v77：板内钻入瓦片——透明底 + 板内页头 + 单板（列表/编辑器同板切换，防框套框），Rail 常驻 */
    embedded: Boolean = false
) {
    val scope = rememberCoroutineScope()
    val haptics = LocalHaptics.current
    var list by remember { mutableStateOf(AssistantsCache.list) }
    var editing by remember { mutableStateOf<Assistant?>(null) }
    var showEditor by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<Assistant?>(null) }

    suspend fun reload() {
        val a = Graph.assistants.all()
        AssistantsCache.list = a
        list = a
    }
    LaunchedEffect(Unit) { reload() }

    // v55：导入角色卡（JSON / PNG 内嵌卡 / charx ZIP）→ 新建智能体 + 内嵌世界书
    val ctx = LocalContext.current
    // v56：导入后问一句「角色扮演 / 工作助手」——脚手架默认值直接决定这张卡像不像那个人。
    // 酒馆社区下载的卡全是角色扮演，带着 ## 标题 / plan/qa 组件卡 / 工具循环说话
    // 正是「AI 味」的主要来源（用户 2026-11-15：想按二次元人设说话）。
    var pendingImport by remember { mutableStateOf<CardIo.ImportedCard?>(null) }
    val importPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val bytes = runCatching {
                ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            }.getOrNull() ?: return@launch
            val text = when {
                bytes.size > 8 && bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() ->
                    CardIo.cardTextFromPng(bytes)
                bytes.size > 4 && bytes[0] == 0x50.toByte() && bytes[1] == 0x4B.toByte() ->
                    CardIo.cardTextFromCharx(bytes)
                else -> String(bytes, Charsets.UTF_8)
            } ?: return@launch
            pendingImport = CardIo.parseCharacterCard(text) ?: return@launch
        }
    }

    /** 落库：creative=true 走创作预设（脚手架全关）。 */
    suspend fun saveImported(imported: CardIo.ImportedCard, creative: Boolean) {
        haptics.perform(HapticEngine.Kind.Success)
        val card = if (creative) imported.card.creative() else imported.card
        // v56：角色扮演档补默认收尾禁令——卡作者自己写了就尊重作者的，不覆盖。
        val finalCard = if (creative && card.postHistory.isBlank()) {
            card.copy(postHistory = AgentCard.ROLEPLAY_POST_HISTORY)
        } else {
            card
        }
        val newId = Graph.assistants.upsert(
            Assistant(
                key = "custom_" + System.currentTimeMillis(),
                name = imported.name.ifBlank { "导入卡" },
                emoji = "📥",
                slogan = if (creative) "角色扮演（从角色卡导入）" else "从角色卡导入",
                systemPrompt = finalCard.persona,
                card = finalCard.toJson(),
                // 角色扮演默认给高温度：人物语气需要发散，0.3 那种「摘要机」温度最像 AI
                temperature = if (creative) 0.85 else 0.6,
                builtin = false,
            )
        )
        imported.lore.forEach { Graph.lore.upsert(it.copy(id = 0, assistantId = newId)) }
        reload()
    }

    // 内容抽成 lambda：insideOverlay=true 时直接画（overlay 由 ModelSettingsHub 提供），
    // false 时自己套一层 —— 两个 tab 共用一个 Dialog 窗口，切换不再闪。
    val shell: @Composable () -> Unit = {
        Scaffold(
            // v77 板内钻入：embedded 时透明底 + 0 inset（框归壳层）
            containerColor = if (embedded) androidx.compose.ui.graphics.Color.Transparent
            else MaterialTheme.colorScheme.background,
            contentWindowInsets = if (embedded)
                androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0)
            else androidx.compose.material3.ScaffoldDefaults.contentWindowInsets,
            topBar = {
                // embedded 且正在编辑：让编辑页自己的板头显示，外层不再叠一层
                if (!(embedded && showEditor && editing != null)) {
                Column(Modifier.fillMaxWidth()) {
                if (embedded) {
                    io.github.m0use001.planability.ui.common.BoardSubHeader(
                        title = "智能体设置",
                        onBack = onDone,
                        actions = {
                            TextButton(onClick = {
                                haptics.perform(HapticEngine.Kind.Tap)
                                runCatching {
                                    importPicker.launch(
                                        arrayOf("application/json", "image/png", "application/octet-stream", "*/*")
                                    )
                                }
                            }) { Text("导入卡") }
                        }
                    )
                } else {
                TopAppBar(
                    title = { Text("智能体设置", fontWeight = FontWeight.Bold) },
                    actions = {
                        TextButton(onClick = {
                            haptics.perform(HapticEngine.Kind.Tap)
                            runCatching {
                                importPicker.launch(
                                    arrayOf("application/json", "image/png", "application/octet-stream", "*/*")
                                )
                            }
                        }) { Text("导入卡") }
                    },
                    navigationIcon = {
                        // 顶栏返回 = 中性点击 → Tap
                        val overlayClose = LocalOverlayClose.current
                        IconButton(onClick = { haptics.perform(HapticEngine.Kind.Tap); overlayClose() }) {
                            Icon(AppIcons.Nav.Back, "返回", tint = MaterialTheme.colorScheme.onSurfaceVariant)
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


            },
            floatingActionButton = {
                if (!(embedded && showEditor && editing != null)) {
                FloatingActionButton(onClick = {
                    // FAB = 打开新建弹层的中性点击 → Tap
                    haptics.perform(HapticEngine.Kind.Tap)
                    editing = Assistant(key = "custom_" + System.currentTimeMillis(), name = "")
                    showEditor = true
                }) {
                    Icon(AppIcons.Act.Add, contentDescription = "新建智能体")
                }
                }
            }
        ) { pad ->
            // v64（2026-11-20）：宽屏两板——左板=智能体列表，右板=选中智能体的编辑页
            // （AssistantEditorPage 原样放进板里：它本来就是 Scaffold + 平顶栏，天然可嵌）。
            androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
            val astContentW = maxWidth
            val astDetailW = io.github.m0use001.planability.ui.common.MasterDetail.detailWidthFor(astContentW, content = false)
            val astPane = io.github.m0use001.planability.ui.common.MasterDetail
                .paneOf(astContentW, astDetailW, hasSelection = showEditor && editing != null)
            val astTwo = !embedded && astPane != io.github.m0use001.planability.ui.common.DetailPane.Single
            val astState = androidx.compose.foundation.lazy.rememberLazyListState()
            val listItems: androidx.compose.foundation.lazy.LazyListScope.() -> Unit = {
                if (list.isEmpty()) {
                    item { EmptyHint("暂无智能体") }
                }
                items(list, key = { it.id }) { a ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.animateItem()
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainer)
                            .clickable { editing = a; showEditor = true }
                            .padding(horizontal = 12.dp, vertical = 10.dp)
                    ) {
                        Text(a.emoji, style = MaterialTheme.typography.headlineSmall)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(a.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                                if (a.builtin) {
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        "内置", style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            if (a.slogan.isNotBlank()) {
                                Text(
                                    a.slogan, style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                        if (!a.builtin) {
                            IconButton(onClick = { deleteTarget = a }) {
                                Icon(AppIcons.Act.Delete, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                }
                item {
                    Text(
                        "智能体即「助手」：ask_expert 深度任务与动作会话都按这里的人格提示词执行。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 8.dp)
                    )
                }
                item { Spacer(Modifier.height(96.dp)) }
            }
            if (astTwo) {
                // v64 宽屏两板：右板=编辑页（原样嵌入，Scaffold+平顶栏天然可嵌）
                io.github.m0use001.planability.ui.common.SplitBoards(
                    twoPane = true,
                    detailWidth = astDetailW,
                    applyTopInset = false,
                    applyBottomInset = false,
                    // pad 垫在壳上（左板内容在窄分支里靠 .padding(pad)，两板时统一在这里垫）
                    modifier = Modifier.padding(pad),
                    primary = {
                        LazyColumn(
                            Modifier.fillMaxSize().reachedEndHaptic(astState),
                            state = astState,
                            // v77：板内首行留白（用户「顶到了」：原来只垫 horizontal，首卡贴死板上缘）
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                                start = 16.dp, end = 16.dp, top = 16.dp
                            ),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) { listItems() }
                    },
                    secondary = {
                        if (showEditor && editing != null) {
                            AssistantEditorPage(
                                assistant = editing!!,
                                onDismiss = { showEditor = false },
                                onSaved = { reload() },
                                // v77：嵌右板 → 透明底 + BoardSubHeader（框归板、不双垫状态栏）
                                embedded = true
                            )
                        } else {
                            AssistantOverviewPane(count = list.size)
                        }
                    }
                )
            } else if (embedded && showEditor && editing != null) {
                // v77 板内钻入：单板里点智能体 → 就地切到编辑器（板头在 AssistantEditorPage 自己身上）
                AssistantEditorPage(
                    assistant = editing!!,
                    onDismiss = { showEditor = false },
                    onSaved = { reload() },
                    embedded = true
                )
            } else {
                LazyColumn(
                    Modifier.fillMaxSize().padding(pad).padding(horizontal = 16.dp).reachedEndHaptic(astState),
                    state = astState,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) { listItems() }
            }
            }
        }
    }
    // v64：宽屏编辑器在右板里，不再走全屏页（astTwo 在 Scaffold 内容里量出；这里的
    // 窗口宽口径与它一致——本页是整屏 Dialog，无 Rail 差）。
    val assistWide = io.github.m0use001.planability.core.rememberWindowSizeClass().isWide
    if (!assistWide && showEditor && editing != null) {
        // v55：编辑器升级为**独立全屏界面**——同窗口内切页（文件库嵌套 PredictiveBack 卡死教训：
        // 全屏子页禁再套窗口），系统返回键由 BackHandler 拦截，顶栏返回与保存双出口
        // v64：宽屏不再走这里——编辑器在右板里（上方 secondary）
        val a = editing!!
        BackHandler { showEditor = false }
        AssistantEditorPage(assistant = a, onDismiss = { showEditor = false }, onSaved = { reload() })
    } else {
        // v64：宽屏整屏承载两板（不再收成居中 1000dp 模态卡）
        if (insideOverlay) shell() else PredictiveBackOverlay(
            onBack = onDone,
            fullscreen = io.github.m0use001.planability.core.rememberWindowSizeClass().isWide
        ) { shell() }
    }

    deleteTarget?.let { a ->
        AlertDialog(
            modifier = Modifier.motionEnter(),
            onDismissRequest = { deleteTarget = null },
            title = { Text("删除智能体") },
            text = { Text("删除「${a.name}」？已挂靠的会话将回退默认人格。") },
            confirmButton = {
                TextButton(onClick = {
                    // 破坏性确认 → ImpactHeavy（与 ConfirmDialog 的 danger 分支同一档；
                    // 这里是裸 AlertDialog，不补的话"删智能体"比其它删除安静）
                    haptics.perform(HapticEngine.Kind.ImpactHeavy)
                    scope.launch {
                        Graph.assistants.deleteCustom(a.id)
                        reload()
                    }
                    deleteTarget = null
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("取消") } }
        )
    }

    // v56：导入卡的用途选择——决定脚手架与默认温度（见 saveImported 注释）
    pendingImport?.let { imp ->
        AlertDialog(
            modifier = Modifier.motionEnter(),
            onDismissRequest = { pendingImport = null },
            title = { Text("导入「${imp.name.ifBlank { "未命名角色" }}」") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "这张卡要当什么用？决定它会不会用 Markdown 排版、" +
                            "内嵌组件卡和工具循环——角色扮演卡关掉这些才像那个人说话。",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    if (imp.card.greeting.isNotBlank()) {
                        Text(
                            "开场白：「" + imp.card.greeting.take(60).replace("\n", " ") + "」",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (imp.lore.isNotEmpty()) {
                        Text(
                            "内嵌设定卡 ${imp.lore.size} 条（随卡导入，关键词触发）",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (imp.card.postHistory.isBlank()) {
                        Text(
                            "选「角色扮演」会补一段默认收尾禁令（去掉模型口癖、" +
                                "不推销设定、不用 Markdown），导入后可在编辑页改。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    val macroNames = io.github.m0use001.planability.ai.card.CardMacros
                        .macroNames(imp.card.persona + "\n" + imp.card.styleExamples)
                    if (macroNames.isNotEmpty()) {
                        Text(
                            "卡内宏：" + macroNames.joinToString("、") { "{{" + it + "}}" },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val i = imp
                    pendingImport = null
                    scope.launch { saveImported(i, creative = true) }
                }) { Text("角色扮演") }
            },
            dismissButton = {
                TextButton(onClick = {
                    val i = imp
                    pendingImport = null
                    scope.launch { saveImported(i, creative = false) }
                }) { Text("工作助手") }
            }
        )
    }
}

// ==================== v55 智能体卡 / 设定卡（世界书）编辑 ====================

/** 逗号分隔文本 → JSON 字符串数组（支持中英文逗号与顿号）。 */
private fun keysToJson(text: String): String =
    org.json.JSONArray(
        text.split('，', ',', '、').map { it.trim() }.filter { it.isNotBlank() }
    ).toString()

@Composable
private fun SwitchRow(label: String, sub: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(sub, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/**
 * v55 智能体编辑**独立全屏界面**（用户：别挤在弹层里）。
 * 同窗口内切页：外层用 BackHandler 拦返回键；顶栏返回与保存双出口。
 * 卡字段（人设/文风示例/收尾禁令/深度注/脚手架/温度）+ 设定卡管理入口。
 */
/**
 * 智能体编辑页。
 *
 * [embedded]：v77（2026-11-23 用户实拍「编辑智能体页框呢」）——嵌进宽屏右板时传 true。
 * 根因：本页原 `Scaffold` 默认 `containerColor=background`（不透明底）把**右板的圆角相框
 * 整块盖掉**（右板看上去无框，与 phone-c 会话「框不完整」同一类病），且 `TopAppBar` 自带
 * 状态栏 inset 在板内是**双垫**。embedded=true 时：透明底 + `BoardSubHeader`（板内页头、
 * 不带状态栏 inset）+ `contentWindowInsets=0`，框与间距归 SplitBoards 的板所有。
 */
@Composable
private fun AssistantEditorPage(
    assistant: Assistant,
    onDismiss: () -> Unit,
    onSaved: suspend () -> Unit,
    embedded: Boolean = false,
) {
    val scope = rememberCoroutineScope()
    val haptics = LocalHaptics.current
    var name by remember(assistant.id) { mutableStateOf(assistant.name) }
    var emoji by remember(assistant.id) { mutableStateOf(assistant.emoji) }
    var slogan by remember(assistant.id) { mutableStateOf(assistant.slogan) }
    var card by remember(assistant.id) {
        mutableStateOf(AgentCard.parse(assistant.card, assistant.systemPrompt))
    }
    var showLore by remember(assistant.id) { mutableStateOf(false) }
    // v56：导出结果提示（路径或失败原因）；null = 还没点过
    var exportPath by remember(assistant.id) { mutableStateOf<String?>(null) }

    val title = if (assistant.id == 0L) "新建智能体" else "编辑智能体"
    // 保存动作抽一份：板内页头(BoardSubHeader)与全屏(TopAppBar) 共用，不复制两遍。
    val saveAction: () -> Unit = {
        if (name.isNotBlank()) {
            haptics.perform(HapticEngine.Kind.Success)
            val finalCard = card
            val finalName = name.trim()
            val finalEmoji = emoji.ifBlank { "🤖" }
            val finalSlogan = slogan.trim()
            scope.launch {
                Graph.assistants.upsert(
                    assistant.copy(
                        name = finalName,
                        emoji = finalEmoji,
                        slogan = finalSlogan,
                        // 兼容：systemPrompt 与卡内人设同步（card 丢失时回落旧字段）
                        systemPrompt = finalCard.persona.trim(),
                        card = finalCard.toJson(),
                    )
                )
                onSaved()
            }
            onDismiss()
        }
    }

    Scaffold(
        // embedded：透明底让出 SplitBoards 的圆角板；contentWindowInsets=0 防板内双垫状态栏
        containerColor = if (embedded) androidx.compose.ui.graphics.Color.Transparent
        else MaterialTheme.colorScheme.background,
        contentWindowInsets = if (embedded)
            androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0)
        else androidx.compose.material3.ScaffoldDefaults.contentWindowInsets,
        topBar = {
            if (embedded) {
                io.github.m0use001.planability.ui.common.BoardSubHeader(
                    title = title,
                    onBack = onDismiss,
                    actions = {
                        TextButton(enabled = name.isNotBlank(), onClick = saveAction) { Text("保存") }
                    }
                )
            } else {
            TopAppBar(
                title = {
                    Text(title, fontWeight = FontWeight.Bold)
                },
                navigationIcon = {
                    IconButton(onClick = { haptics.perform(HapticEngine.Kind.Tap); onDismiss() }) {
                        Icon(AppIcons.Nav.Back, "返回", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                actions = {
                    TextButton(enabled = name.isNotBlank(), onClick = saveAction) { Text("保存") }
                }
            )
            }
        }
    ) { pad ->
        Column(
            Modifier.fillMaxSize()
                .padding(pad)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = emoji, onValueChange = { emoji = it.take(2) },
                    label = { Text("图标") }, singleLine = true, modifier = Modifier.width(84.dp)
                )
                Spacer(Modifier.width(8.dp))
                OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    label = { Text("名称") }, singleLine = true, modifier = Modifier.weight(1f)
                )
            }
            OutlinedTextField(
                value = slogan, onValueChange = { slogan = it },
                label = { Text("一句话介绍") }, singleLine = true, modifier = Modifier.fillMaxWidth()
            )
            CardSections(card = card, onCard = { card = it })
            if (assistant.id != 0L) {
                TextButton(onClick = { showLore = true }) { Text("管理设定卡（世界书）") }
            }
            // v56：导出为 V2 角色卡 JSON——卡是资产，导得进也得拿得出（换机/备份/分享）
            val fileCtx = LocalContext.current
            TextButton(onClick = {
                haptics.perform(HapticEngine.Kind.Tap)
                scope.launch {
                    val lore = runCatching {
                        if (assistant.id != 0L) Graph.lore.forAssistant(assistant.id) else emptyList()
                    }.getOrDefault(emptyList())
                    val json = io.github.m0use001.planability.data.card.CardIo.exportCharacterCard(
                        name = name.ifBlank { "未命名角色" },
                        card = card,
                        lore = lore,
                    )
                    runCatching {
                        val dir = java.io.File(fileCtx.getExternalFilesDir(null), "exports")
                        dir.mkdirs()
                        val safe = (name.ifBlank { "card" }).replace(Regex("[\\\\/:*?\"<>|]"), "_")
                        val f = java.io.File(dir, safe + ".card.json")
                        f.writeText(json, Charsets.UTF_8)
                        exportPath = f.absolutePath
                    }.onFailure { exportPath = "导出失败：${it.message}" }
                }
            }) { Text("导出角色卡（V2 JSON）") }
            exportPath?.let {
                Text(
                    if (it.startsWith("导出失败")) it else "已导出：$it",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (it.startsWith("导出失败")) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Spacer(Modifier.height(48.dp))
        }
    }
    if (showLore) LoreManagerDialog(assistantId = assistant.id, onDismiss = { showLore = false })
}

/** 卡编辑区：人设 / 文风示例 / 收尾禁令 / 深度注 / 脚手架 / 温度。 */
@Composable
private fun CardSections(card: AgentCard, onCard: (AgentCard) -> Unit) {
    val hint = MaterialTheme.typography.labelSmall
    val hintColor = MaterialTheme.colorScheme.onSurfaceVariant
    OutlinedTextField(
        value = card.persona, onValueChange = { onCard(card.copy(persona = it)) },
        label = { Text("人设正文") }, minLines = 4, maxLines = 10, modifier = Modifier.fillMaxWidth()
    )
    Text("只写「这个人是谁、怎么做事」；规则放下面的收尾禁令。", style = hint, color = hintColor)
    // v56：宏提示——用户从社区下载的卡里全是 {{char}}/{{user}}，得让他知道这些会展开
    val macroHint = remember(card.persona, card.styleExamples) {
        val all = card.persona + "\n" + card.styleExamples
        val names = io.github.m0use001.planability.ai.card.CardMacros.macroNames(all)
        val unsupported = names.filter {
            io.github.m0use001.planability.ai.card.CardMacros.hasActionMacros("{{" + it + "}}")
        }
        when {
            unsupported.isNotEmpty() ->
                "⚠ " + unsupported.joinToString("、") { "{{" + it + "}}" } +
                    " 是酒馆的动态宏，本 App 不执行，会原样留在提示词里。"
            names.isNotEmpty() ->
                names.joinToString("、") { "{{" + it + "}}" } + " 会在发送时自动替换成实际名字。"
            else -> null
        }
    }
    macroHint?.let {
        Text(it, style = hint, color = if (it.startsWith("⚠")) MaterialTheme.colorScheme.error else hintColor)
    }
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(
        value = card.styleExamples, onValueChange = { onCard(card.copy(styleExamples = it)) },
        label = { Text("文风示例（教文风，比形容词管用）") }, minLines = 2, maxLines = 6,
        modifier = Modifier.fillMaxWidth()
    )
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(
        value = card.postHistory, onValueChange = { onCard(card.copy(postHistory = it)) },
        label = { Text("收尾禁令（注入在聊天记录之后）") }, minLines = 2, maxLines = 6,
        modifier = Modifier.fillMaxWidth()
    )
    Text("贴生成点、权重最高：先问再动 / 没说不写 / 不用 Markdown。", style = hint, color = hintColor)
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(
        value = card.depthText, onValueChange = { onCard(card.copy(depthText = it)) },
        label = { Text("深度注（卡级方向舵）") }, minLines = 2, maxLines = 4, modifier = Modifier.fillMaxWidth()
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("钉在倒数第", style = hint, color = hintColor)
        TextButton(onClick = { onCard(card.copy(depth = (card.depth - 1).coerceAtLeast(0))) }) { Text("−") }
        Text("${card.depth}", style = MaterialTheme.typography.bodyMedium)
        TextButton(onClick = { onCard(card.copy(depth = (card.depth + 1).coerceAtMost(50))) }) { Text("＋") }
        Text("条消息后", style = hint, color = hintColor)
    }
    Spacer(Modifier.height(4.dp))
    Text("脚手架", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        val allOff = !card.scaffoldMarkdown && !card.scaffoldComponents &&
            !card.scaffoldCharts && !card.scaffoldTools
        val allOn = card.scaffoldMarkdown && card.scaffoldComponents &&
            card.scaffoldCharts && card.scaffoldTools
        FilterChip(selected = allOff, onClick = { onCard(card.creative()) }, label = { Text("创作预设") })
        FilterChip(
            selected = allOn,
            onClick = {
                onCard(
                    card.copy(
                        scaffoldMarkdown = true, scaffoldComponents = true,
                        scaffoldCharts = true, scaffoldTools = true,
                    )
                )
            },
            label = { Text("全开") }
        )
    }
    SwitchRow("回答排版", "## 标题 / 列表 / 粗体", card.scaffoldMarkdown) { onCard(card.copy(scaffoldMarkdown = it)) }
    SwitchRow("组件卡", "plan / qa / checklist", card.scaffoldComponents) { onCard(card.copy(scaffoldComponents = it)) }
    SwitchRow("图表块", "chart / mindmap 等", card.scaffoldCharts) { onCard(card.copy(scaffoldCharts = it)) }
    SwitchRow("工具循环", "待办 / 日程 / 记忆 / 搜索", card.scaffoldTools) { onCard(card.copy(scaffoldTools = it)) }
    Spacer(Modifier.height(4.dp))
    val t = card.temperature
    Text(
        "温度 " + (t?.let { String.format(Locale.US, "%.2f", it) } ?: "默认"),
        style = MaterialTheme.typography.bodyMedium
    )
    Slider(
        value = (t ?: 0.7).toFloat(),
        onValueChange = { v -> onCard(card.copy(temperature = (v * 100).toInt() / 100.0)) },
        valueRange = 0f..1.5f,
    )
}

/** 设定卡（世界书）管理：列表 ↔ 条目编辑，单 Dialog 状态机（避免嵌套 Dialog）。 */
@Composable
private fun LoreManagerDialog(assistantId: Long, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    val haptics = LocalHaptics.current
    var entries by remember { mutableStateOf<List<LoreEntry>>(emptyList()) }
    var editing by remember { mutableStateOf<LoreEntry?>(null) }
    suspend fun reload() {
        entries = Graph.lore.forAssistant(assistantId)
    }
    LaunchedEffect(assistantId) { reload() }
    // v55：导入 ST 世界书 JSON（{entries:{uid:{...}}}）到当前智能体
    val ctx2 = LocalContext.current
    val worldbookPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val bytes = runCatching {
                ctx2.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            }.getOrNull() ?: return@launch
            val list = CardIo.parseWorldbook(String(bytes, Charsets.UTF_8))
            if (list.isNotEmpty()) {
                haptics.perform(HapticEngine.Kind.Success)
                list.forEach { Graph.lore.upsert(it.copy(id = 0, assistantId = assistantId)) }
                reload()
            }
        }
    }
    val current = editing
    AlertDialog(
        modifier = Modifier.motionEnter(),
        onDismissRequest = { if (current != null) editing = null else onDismiss() },
        title = { Text(if (current == null) "设定卡（世界书）" else "编辑设定卡") },
        text = {
            if (current == null) {
                Column {
                    Text(
                        "键命中才注入；常驻每轮注入；预算按优先级砍。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    if (entries.isEmpty()) {
                        EmptyHint("还没有设定卡")
                    } else {
                        LazyColumn(Modifier.heightIn(max = 380.dp)) {
                            items(entries, key = { it.id }) { e ->
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.fillMaxWidth()
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(MaterialTheme.colorScheme.surfaceContainer)
                                        .clickable { editing = e }
                                        .padding(horizontal = 10.dp, vertical = 8.dp)
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(
                                                e.name.ifBlank { "未命名" },
                                                style = MaterialTheme.typography.titleSmall
                                            )
                                            if (e.constant) {
                                                Spacer(Modifier.width(6.dp))
                                                Text(
                                                    "常驻", style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.primary
                                                )
                                            }
                                        }
                                        Text(
                                            e.keyList().joinToString("、").ifBlank { "（无触发词）" },
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1, overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    Switch(
                                        checked = !e.disabled,
                                        onCheckedChange = { on ->
                                            haptics.perform(HapticEngine.Kind.Tap)
                                            scope.launch {
                                                Graph.lore.upsert(
                                                    e.copy(disabled = !on, updatedAt = System.currentTimeMillis())
                                                )
                                                reload()
                                            }
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            } else {
                LoreEntryForm(current, onChange = { editing = it })
            }
        },
        confirmButton = {
            if (current == null) {
                Row {
                    TextButton(onClick = {
                        runCatching {
                            worldbookPicker.launch(
                                arrayOf("application/json", "application/octet-stream", "*/*")
                            )
                        }
                    }) { Text("导入世界书") }
                    TextButton(onClick = {
                        editing = LoreEntry(assistantId = assistantId, name = "")
                    }) { Text("新建") }
                }
            } else {
                TextButton(onClick = {
                    haptics.perform(HapticEngine.Kind.Success)
                    scope.launch {
                        Graph.lore.upsert(current.copy(updatedAt = System.currentTimeMillis()))
                        editing = null
                        reload()
                    }
                }) { Text("保存") }
            }
        },
        dismissButton = {
            if (current == null) {
                TextButton(onClick = onDismiss) { Text("关闭") }
            } else {
                Row {
                    if (current.id != 0L) {
                        TextButton(onClick = {
                            haptics.perform(HapticEngine.Kind.ImpactHeavy)
                            scope.launch {
                                Graph.lore.delete(current.id)
                                editing = null
                                reload()
                            }
                        }) { Text("删除", color = MaterialTheme.colorScheme.error) }
                    }
                    TextButton(onClick = { editing = null }) { Text("取消") }
                }
            }
        }
    )
}

/** 世界书条目编辑表单。keys 用本地文本态，避免逗号在输入过程中被归一化吃掉。 */
@Composable
private fun LoreEntryForm(e: LoreEntry, onChange: (LoreEntry) -> Unit) {
    var keyText by remember(e.id) { mutableStateOf(e.keyList().joinToString("，")) }
    var secondText by remember(e.id) { mutableStateOf(e.secondKeyList().joinToString("，")) }
    Column(Modifier.verticalScroll(rememberScrollState())) {
        OutlinedTextField(
            value = e.name, onValueChange = { onChange(e.copy(name = it)) },
            label = { Text("卡名") }, singleLine = true, modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = keyText,
            onValueChange = { keyText = it; onChange(e.copy(keys = keysToJson(it))) },
            label = { Text("触发词（逗号分隔）") }, singleLine = true, modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = secondText,
            onValueChange = { secondText = it; onChange(e.copy(secondKeys = keysToJson(it))) },
            label = { Text("副键（可空）") }, singleLine = true, modifier = Modifier.fillMaxWidth()
        )
        if (e.secondKeyList().isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(
                    LoreEngine.LOGIC_AND_ANY to "任一",
                    LoreEngine.LOGIC_AND_ALL to "全要",
                    LoreEngine.LOGIC_NOT_ANY to "全无",
                    LoreEngine.LOGIC_NOT_ALL to "非全",
                ).forEach { (v, label) ->
                    FilterChip(
                        selected = e.logic == v,
                        onClick = { onChange(e.copy(logic = v)) },
                        label = { Text(label) }
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = e.content, onValueChange = { onChange(e.copy(content = it)) },
            label = { Text("设定正文") }, minLines = 3, maxLines = 8, modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))
        SwitchRow("常驻", "每轮无条件注入", e.constant) { onChange(e.copy(constant = it)) }
        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(
                LoreEngine.POS_DESC_AFTER to "人设后",
                LoreEngine.POS_DESC_BEFORE to "人设前",
                LoreEngine.POS_DEPTH to "倒 N 条",
            ).forEach { (v, label) ->
                FilterChip(
                    selected = e.position == v,
                    onClick = { onChange(e.copy(position = v)) },
                    label = { Text(label) }
                )
            }
        }
        if (e.position == LoreEngine.POS_DEPTH) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("倒数第", style = MaterialTheme.typography.labelSmall)
                TextButton(onClick = { onChange(e.copy(depth = (e.depth - 1).coerceAtLeast(0))) }) { Text("−") }
                Text("${e.depth}")
                TextButton(onClick = { onChange(e.copy(depth = (e.depth + 1).coerceAtMost(50))) }) { Text("＋") }
                Text("条", style = MaterialTheme.typography.labelSmall)
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("优先级", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = { onChange(e.copy(priority = (e.priority - 1).coerceAtLeast(0))) }) { Text("−") }
            Text("${e.priority}")
            TextButton(onClick = { onChange(e.copy(priority = (e.priority + 1).coerceAtMost(99))) }) { Text("＋") }
        }
    }
}

/** v64 宽屏右板（未选中）：这一栏不许空着。 */
@Composable
private fun AssistantOverviewPane(count: Int) {
    Column(Modifier.fillMaxSize()) {
        io.github.m0use001.planability.ui.common.BoardHeader(
            title = "智能体",
            subtitle = "点左侧智能体在此编辑"
        )
        io.github.m0use001.planability.ui.common.BoardDivider()
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = io.github.m0use001.planability.ui.common.BoardLayout.Padding, vertical = 12.dp)
        ) {
            // v72：裸统计行 → 数值格，说明段装色调块（PaneBlocks 词汇表）
            io.github.m0use001.planability.ui.common.PaneStatRow(listOf("$count" to "智能体"))
            Spacer(Modifier.height(10.dp))
            io.github.m0use001.planability.ui.common.PaneContentBlock {
                Text(
                    "智能体即「助手」：ask_expert 深度任务与动作会话都按这里的人格提示词执行。选中一条在右侧编辑人格卡、开场白与设定。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
