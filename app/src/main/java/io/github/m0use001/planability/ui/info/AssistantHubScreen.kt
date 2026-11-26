package io.github.m0use001.planability.ui.info

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Article
import androidx.compose.material.icons.rounded.AttachFile
import androidx.compose.material.icons.rounded.AudioFile
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.CheckCircleOutline
import androidx.compose.material.icons.rounded.Checklist
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Event
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.FolderZip
import androidx.compose.material.icons.rounded.Forum
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Inbox
import androidx.compose.material.icons.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.PictureAsPdf
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Slideshow
import androidx.compose.material.icons.rounded.TableChart
import androidx.compose.material.icons.rounded.TaskAlt
import androidx.compose.material.icons.rounded.VideoFile
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.m0use001.planability.app.PredictiveBackOverlay
import io.github.m0use001.planability.core.AppTime
import io.github.m0use001.planability.core.HapticEngine
import io.github.m0use001.planability.core.LocalHaptics
import io.github.m0use001.planability.data.AssistantRepo
import io.github.m0use001.planability.data.Graph
import io.github.m0use001.planability.data.HubProject
import io.github.m0use001.planability.data.PendingItem
import io.github.m0use001.planability.ui.common.M3ETitleBar
import io.github.m0use001.planability.ui.common.AppIcons
import io.github.m0use001.planability.ui.common.Motion
import io.github.m0use001.planability.ui.common.motionEnter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * v54.1 AI 助理中枢：**特殊项目**的专属界面——导航枢纽。
 *
 * 主界面只做路由 + 状态总览（一屏一件事，防疲劳）：🧭 主会话快捷卡 + 四张入口卡
 * （待处理审核 / 会话 / 项目文件 / 项目记忆），每个功能一个专属二级页：
 * - HubReviewScreen  待处理审核（筛选 chips + 卡片流 + 底部输入条，全屏空间）
 * - HubSessionsScreen 会话（列表 + 新建；进入后可传附件、换智能体）
 * - 项目记忆/项目文件 → 普通项目同款全局页
 *
 * v57 界面对齐正常 AI 会话（ChatScreen）：顶栏换 M3ETitleBar 固定态圆角浮层 +
 * 状态胶囊副标题行（ChatScreen 的「工作流/上下文/权限」同款语法），输入条换
 * 「倒置顶栏」输入面板（HubComposer，把手拉高就地变高、透明输入框、圆形发送键），
 * 附件同款：＋ 菜单（图片/文件）→ 复制进应用 attachments/ → 面板内预览带（×移除）；
 * 发送时与 ChatScreen.sendCurrent 合成同一格式标记拼进正文，经 onChat 桥到会话页
 * 发送（宿主 InfoCenterScreen 的 chatFiles 桥在并行 WIP 里暂不可动，标记文本是
 * 同一条线上格式）。
 * 中枢仍是项目（HubProject.ensureId）：普通项目有的它也有，只是入口都收进这页。
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun AssistantHubScreen(
    onDone: () -> Unit,
    /** 入口卡跳转（父页挂二级页/全局页） */
    onOpenReview: () -> Unit,
    onOpenSessions: () -> Unit,
    onOpenMemory: () -> Unit = {},
    onOpenFiles: () -> Unit = {},
    /** 顶栏历史图标 → 旧版待处理三 tab 列表（已入库/已忽略找回） */

) {
    val haptics = LocalHaptics.current
    val version by Graph.dataVersion.flow.collectAsState()

    var pendingTotal by remember { mutableStateOf(0) }
    var pendingTodos by remember { mutableStateOf(0) }
    var sessionCount by remember { mutableStateOf(0) }
    var hubAssistantId by remember { mutableStateOf(0L) }
    var hubProjectId by remember { mutableStateOf(0L) }
    LaunchedEffect(version) {
        runCatching {
            val all = Graph.info.pendingItems()
            pendingTotal = all.size
            pendingTodos = all.count { it.kind != "schedule" }
        }
        runCatching { hubAssistantId = Graph.assistants.byKey(AssistantRepo.KEY_HUB)?.id ?: 0L }
        runCatching {
            hubProjectId = HubProject.ensureId()
            sessionCount = HubProject.sessionsOf(hubProjectId).size
            // 主会话由抽屉入口直接打开（见 InfoCenterScreen.onPending），本页不再需要它
            HubProject.ensureMainSession(hubAssistantId, hubProjectId)
        }
    }

    PredictiveBackOverlay(onBack = onDone) {
        Scaffold(
            // 与 ChatScreen 同款：insets 归零，顶栏/输入面板各自处理状态栏与手势条
            contentWindowInsets = WindowInsets(0),
            topBar = {
                // 2026-11-11 用户：「更多」（项目文件/项目记忆）从入口卡移到标题栏右上角——
                // 首屏只留高频的审核与会话两张卡。
                var moreOpen by remember { mutableStateOf(false) }
                HubTitleBar(
                    title = "助理中枢",
                    onDone = onDone,
                    // 2026-11-08 用户「中枢界面怎么有 2 个待处理，会让用户疑惑」：
                    // 原来这里有一排「待审 N / 会话 N」胶囊，而下面第一屏的功能入口卡
                    // 又写着「待处理审核 · N 条待审（待办 X · 日程 Y）」「会话 · N 个会话」——
                    // 同一个数字同屏出现两遍，用户不知道是不是两个东西。**计数只留入口卡**
                    // （那里还能给待办/日程拆分），胶囊行撤掉；入口本身不减少（点卡片直达）。
                    actions = {
                        Box {
                            IconButton(onClick = { haptics.perform(HapticEngine.Kind.Tap); moreOpen = true }) {
                                Icon(Icons.Rounded.MoreHoriz, "更多", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            androidx.compose.material3.DropdownMenu(
                                expanded = moreOpen,
                                onDismissRequest = { moreOpen = false }
                            ) {
                                androidx.compose.material3.DropdownMenuItem(
                                    modifier = Modifier.clip(RoundedCornerShape(10.dp)), // 2026-11-15 点击高亮圆角化（原来是整行直角方块）
                                    text = { Text("项目文件") },
                                    onClick = { moreOpen = false; onOpenFiles() }
                                )
                                androidx.compose.material3.DropdownMenuItem(
                                    modifier = Modifier.clip(RoundedCornerShape(10.dp)), // 2026-11-15 点击高亮圆角化（原来是整行直角方块）
                                    text = { Text("项目记忆") },
                                    onClick = { moreOpen = false; onOpenMemory() }
                                )
                            }
                        }
                    }
                )
            },
        ) { pad ->
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(pad)
                    .verticalScroll(rememberScrollState())
            ) {
                // ---- 功能入口卡：每个功能一个专属二级页，主界面不堆内容 ----
                HubEntryCard(
                    icon = Icons.Rounded.Inbox,
                    title = "待处理审核",
                    subtitle = if (pendingTotal > 0)
                        "$pendingTotal 条待审（待办 $pendingTodos · 日程 ${pendingTotal - pendingTodos}）" else "没有待处理草稿",
                    onClick = { haptics.perform(HapticEngine.Kind.Tap); onOpenReview() }
                )
                HubEntryCard(
                    icon = Icons.Rounded.Forum,
                    title = "会话",
                    subtitle = "$sessionCount 个会话（新建 / 继续）",
                    onClick = { haptics.perform(HapticEngine.Kind.Tap); onOpenSessions() }
                )
                // 2026-11-08 用户「项目记忆与文件不是常用项，可以放进二级菜单」：
                // 原来两张卡占掉首屏三分之一，而真正高频的只有审核与会话。
                // 2026-11-11 用户：「更多」入口移到顶栏右上角（见 topBar），首屏两张卡收尾。
            }
        }
    }
}

/** 入口卡：图标 + 标题 + 副标题（状态）+ chevron */
@Composable
private fun HubEntryCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 5.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = title, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(
                subtitle, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
        }
        Icon(
            Icons.AutoMirrored.Rounded.KeyboardArrowRight, "进入",
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * v54.1 待处理审核二级页：筛选 chips + 卡片流（全屏）+ 底部输入条（跟助理说）。
 * 从中枢主界面迁出——审核是重活，值得整屏空间。
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun HubReviewScreen(
    onDone: () -> Unit,
    /** 审核历史（已入库 / 已忽略）：history 是审核页的下级视图，不再做成并列入口 */
    onOpenHistory: () -> Unit = {},
    /**
     * 2026-11-12 用户：审核页底部输入框要**直发到「点开中枢功能时所在的那个会话」**——
     * 用户每次都是先在某个会话里点「中枢功能」按钮进来的，发回那里上下文才是连续的
     * （会话可能有多个，但入口必然经过一个会话）。传 InfoCenterScreen 的 hubReturnSessionId。
     * 来源会话已不存在/被归档时才退回中枢主会话。
     */
    preferredSessionId: Long? = null,
    onChat: (sessionId: Long?, assistantId: Long, projectId: Long, text: String, autoSend: Boolean) -> Unit
) {
    val scope = rememberCoroutineScope()
    val haptics = LocalHaptics.current
    val version by Graph.dataVersion.flow.collectAsState()

    var items by remember { mutableStateOf<List<PendingItem>>(emptyList()) }
    var expanded by remember { mutableStateOf(true) }
    var input by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf<PendingItem?>(null) }
    // v57.1 附件（与主界面/ChatScreen 同一套）
    var pendingFiles by remember { mutableStateOf<List<PendingAttachment>>(emptyList()) }
    val pickImage = rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                runCatching {
                    val name = "img_" + System.currentTimeMillis() + ".jpg"
                    val dst = java.io.File(Graph.appContext.filesDir, "attachments/" + name)
                    dst.parentFile?.mkdirs()
                    Graph.appContext.contentResolver.openInputStream(uri)?.use { ins ->
                        dst.outputStream().use { out -> ins.copyTo(out) }
                    }
                    if (dst.exists() && dst.length() > 0) {
                        pendingFiles = pendingFiles + PendingAttachment("attachments/" + name, name, "file_read_image")
                    }
                }.onFailure {
                    android.widget.Toast.makeText(Graph.appContext, "图片导入失败：" + it.message, android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
    val pickFile = rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                runCatching {
                    val ctx = Graph.appContext
                    var display = "file"
                    ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
                        val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                        if (idx >= 0 && c.moveToFirst()) display = c.getString(idx) ?: display
                    }
                    val safeName = display.replace('/', '_').replace('\\', '_')
                    val name = "file_" + System.currentTimeMillis() + "_" + safeName
                    val dst = java.io.File(ctx.filesDir, "attachments/" + name)
                    dst.parentFile?.mkdirs()
                    ctx.contentResolver.openInputStream(uri)?.use { ins ->
                        dst.outputStream().use { out -> ins.copyTo(out) }
                    }
                    if (dst.exists() && dst.length() > 0) {
                        pendingFiles = pendingFiles + PendingAttachment("attachments/" + name, safeName, toolForFile(safeName))
                    }
                }.onFailure {
                    android.widget.Toast.makeText(Graph.appContext, "附件导入失败：" + it.message, android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    fun composedInput(): String {
        val marks = pendingFiles.joinToString(" ") { "[附件已附: " + it.relPath + " 请用 " + it.tool + " 读取]" }
        return if (marks.isEmpty()) input else (input.trim() + (if (input.isBlank()) "" else " ") + marks)
    }
    var filter by remember { mutableStateOf("all") }   // all / todo / schedule / session / capture
    var projNames by remember { mutableStateOf<Map<Long, String>>(emptyMap()) }
    // 2026-11-12 用户「待处理审核还是一千多条」：capture 两天提纯 1249 条互不相同草稿，
    // 判重管不住产量——审核页必须有批量操作。多选模式：点行勾选，底部条批量忽略（可在历史找回）
    var selectMode by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var confirmBatchIgnore by remember { mutableStateOf(false) }
    // 2026-11-12 用户：批量模式除了批量忽略还要能批量入库（上千条里总有该留的）
    var confirmBatchImport by remember { mutableStateOf(false) }
    // 2026-11-12 用户：✓（入库）向右抽走、✕（忽略）下沉消失——先播 260ms 退场动画再真正
    // 落库，卡片去向一眼可辨（右=进时间轴，下=进忽略堆）。存 id→方向，退场中防重复点。
    // 2026-11-12 用户「动画极其卡顿（补位极慢）」：普通 Map 一变就让**所有**读它的
    // 可见行全部重组（上千条时每删一条全列表重排）。改 SnapshotStateMap——按 key
    // 粒度失效，只有退场中那一行重组。
    val exiting = remember { androidx.compose.runtime.mutableStateMapOf<Long, PendingExit>() }
    fun startExit(id: Long, kind: PendingExit, commit: () -> Unit) {
        haptics.perform(if (kind == PendingExit.Import) HapticEngine.Kind.Success else HapticEngine.Kind.Tap)
        exiting[id] = kind
        scope.launch {
            kotlinx.coroutines.delay(300)
            commit()
            exiting.remove(id)
        }
    }
    var hubAssistantId by remember { mutableStateOf(0L) }
    var hubSessionId by remember { mutableStateOf<Long?>(null) }
    var hubProjectId by remember { mutableStateOf(0L) }

    // 2026-11-12 用户「补位极慢」：判重清扫原来挂在 (version, hubProjectId) effect 里，
    // 每删/入库一条（version bump）都对全表跑一遍 Jaccard 聚簇——上千条时一次几百毫秒，
    // 补位就卡在那。它是「进页自愈」，只该进页跑一次。
    LaunchedEffect(Unit) {
        runCatching { Graph.info.healPendingDuplicates() }
    }
    // key 带 hubProjectId：项目 id 在下面 Unit effect 里才就绪，就绪后要重跑把会话装进来
    LaunchedEffect(version, hubProjectId) {
        items = runCatching { Graph.info.pendingItems() }.getOrDefault(emptyList())
        projNames = runCatching { Graph.projects.all().associate { it.id to it.name } }.getOrDefault(emptyMap())
        // 批量模式选中的行被别的通道（清扫/单条操作/入库）处理后要从选区里剔除
        selected = selected intersect items.map { it.id }.toSet()
        // 退场中的行被别的通道处理掉的话，动画状态一并清掉（防幽灵条目卡住防重点击）
        exiting.keys.removeAll { id -> items.none { it.id == id } }
    }
    LaunchedEffect(Unit) {
        runCatching {
            var aid = Graph.assistants.byKey(AssistantRepo.KEY_HUB)?.id ?: 0L
            if (aid == 0L) {
                Graph.assistants.ensureSeeded()
                aid = Graph.assistants.byKey(AssistantRepo.KEY_HUB)?.id ?: 0L
            }
            hubAssistantId = aid
            hubProjectId = HubProject.ensureId()
            hubSessionId = HubProject.ensureMainSession(hubAssistantId, hubProjectId)
            // 2026-11-12 用户：输入框直发来源会话（见参数注释）。助手/项目跟随目标会话——
            // ChatScreen 打开已有会话时本来就以会话自身的助手/项目为准（ChatScreen.load 分支），
            // 这里传会话自己的值，保证 onChat 的 draft 变量与目标会话一致、不发错上下文。
            if (preferredSessionId != null && preferredSessionId != hubSessionId) {
                runCatching { Graph.chat.session(preferredSessionId) }.getOrNull()?.let { s ->
                    if (!s.archived) {
                        hubSessionId = s.id
                        hubAssistantId = s.assistantId
                        hubProjectId = if (s.projectId > 0) s.projectId else hubProjectId
                    }
                }
            }
        }
    }

    /** 卡片 ✓：入库。统一走 PendingImporter——payload 忠实重建，与 pending_commit 同一条通道。 */
    fun quickImport(item: PendingItem) {
        scope.launch {
            runCatching {
                val (_, text, clash) = io.github.m0use001.planability.ai.ptl.PendingImporter.commit(item)
                android.widget.Toast.makeText(
                    Graph.appContext,
                    text + (clash?.let { "\n$it" } ?: ""),
                    android.widget.Toast.LENGTH_LONG
                ).show()
            }.onFailure {
                android.widget.Toast.makeText(
                    Graph.appContext, "入库失败：" + (it.message ?: "未知错误"),
                    android.widget.Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    fun quickIgnore(item: PendingItem) {
        scope.launch {
            Graph.info.markPending(item.id, "ignored")
        }
    }

    // v64：宽屏整屏（不再收成居中 1000dp 模态卡）。
    // 本页是「列表 + 底部对话式输入」的混排（批量条 / 输入面板都横跨底部），
    // 硬拆两板会把输入面板切窄——两板留待输入面板单独成行之后再做。
    PredictiveBackOverlay(
        onBack = onDone,
        fullscreen = io.github.m0use001.planability.core.rememberWindowSizeClass().isWide
    ) {
        Scaffold(
            contentWindowInsets = WindowInsets(0),
            topBar = {
                HubTitleBar(
                    title = "待处理审核",
                    onDone = onDone,
                    subtitleContent = {
                        // 常驻计数胶囊（0 也显示）：点一下回到「全部」筛选
                        HubPill("待审 ${items.size} 条") { filter = "all" }
                    },
                    actions = {
                        // 2026-11-12：批量模式开关——草稿积压上千条时逐条点不可用
                        IconButton(onClick = {
                            haptics.perform(HapticEngine.Kind.Tap)
                            selectMode = !selectMode
                            if (!selectMode) selected = emptySet()
                        }) {
                            Icon(
                                Icons.Rounded.Checklist, "批量管理",
                                tint = if (selectMode) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        // 2026-11-08：历史入口收进审核页（已入库/已忽略是审核的存档视图），
                        // 中枢主界面不再单放一个「待处理」入口
                        IconButton(onClick = { haptics.perform(HapticEngine.Kind.Tap); onOpenHistory() }) {
                            Icon(Icons.Rounded.History, "审核历史")
                        }
                    }
                )
            }
        ) { pad ->
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(pad)
            ) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { expanded = !expanded }
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                        contentDescription = if (expanded) "收起" else "展开",
                        Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        when {
                            items.isEmpty() -> "没有待处理的草稿"
                            selectMode -> "批量模式：点条目勾选，选完点底部「入库所选 / 忽略所选」（已忽略可在历史找回）"
                            else -> "点卡片右侧 ✓ 直接入库，或跟助理说怎么排"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                }
                if (items.isNotEmpty()) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        val todoN = items.count { it.kind != "schedule" }
                        FilterChip2("all", "全部 ${items.size}", filter) { filter = it }
                        FilterChip2("todo", "待办 $todoN", filter) { filter = it }
                        FilterChip2("schedule", "日程 ${items.size - todoN}", filter) { filter = it }
                        FilterChip2("session", "会话 ${items.count { it.source == "session" }}", filter) { filter = it }
                        FilterChip2("capture", "提取 ${items.count { it.source == "capture" }}", filter) { filter = it }
                    }
                }
                val shown = when (filter) {
                    "todo" -> items.filter { it.kind != "schedule" }
                    "schedule" -> items.filter { it.kind == "schedule" }
                    "session" -> items.filter { it.source == "session" }
                    "capture" -> items.filter { it.source == "capture" }
                    else -> items
                }
                // v57：列表不可见时必须由常驻 Spacer 接住 weight——AnimatedVisibility 收起后
                // 自身高度归零，weight 随之塌缩，下面的输入面板会骑到提示行下面悬空
                //（空列表+展开态实测踩中，输入面板吊在页面上部）。
                val listShown = expanded && shown.isNotEmpty()
                AnimatedVisibility(
                    visible = listShown,
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically(),
                    modifier = Modifier.weight(1f)
                ) {
                    LazyColumn(
                        Modifier
                            .fillMaxSize()
                            .padding(horizontal = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 8.dp)
                    ) {
                        items(shown, key = { it.id }) { item ->
                            // animateItem：删/入库后其余行平滑补位（2026-11-12 用户「补位极慢」
                            // 的另一半——原来补位是瞬跳，配合退场动画观感割裂）
                            PendingHubRow(
                                modifier = Modifier.animateItem(),
                                item, projNames,
                                exitingKind = exiting[item.id],
                                onImport = {
                                    if (exiting[item.id] == null)
                                        startExit(item.id, PendingExit.Import) { quickImport(item) }
                                },
                                onIgnore = {
                                    if (exiting[item.id] == null)
                                        startExit(item.id, PendingExit.Ignore) { quickIgnore(item) }
                                },
                                onEdit = { editing = item },
                                selectMode = selectMode,
                                checked = selected.contains(item.id),
                                onToggle = {
                                    haptics.perform(HapticEngine.Kind.Select)
                                    selected = if (selected.contains(item.id)) selected - item.id
                                    else selected + item.id
                                }
                            )
                        }
                    }
                }
                if (!listShown) Spacer(Modifier.weight(1f))

                // 2026-11-12 批量操作条：多选模式下替代逐条操作——
                // 「全选当前筛选」跟筛选 chip 联动（比如筛「提取」后全选再忽略，两步清掉几百条）；
                // 忽略走批量确认弹窗（量大气粗的操作必须先过一道），已忽略可在审核历史找回。
                // 2026-11-12 用户截图实证：三个按钮 + 长文本一行塞不下，「入库所选」被挤出
                // 屏幕右缘（忽略所选都折行了）——改两行：全选/忽略一行，入库通栏主按钮。
                if (selectMode) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        val allShownSelected = shown.isNotEmpty() && shown.all { selected.contains(it.id) }
                        androidx.compose.material3.OutlinedButton(
                            enabled = shown.isNotEmpty(),
                            modifier = Modifier.weight(1f),
                            onClick = {
                                haptics.perform(HapticEngine.Kind.Select)
                                selected = if (allShownSelected) selected - shown.map { it.id }.toSet()
                                else selected + shown.map { it.id }.toSet()
                            }
                        ) {
                            Text(
                                if (allShownSelected) "取消全选" else "全选当前筛选（${shown.size}）",
                                maxLines = 1, overflow = TextOverflow.Ellipsis
                            )
                        }
                        androidx.compose.material3.OutlinedButton(
                            enabled = selected.isNotEmpty(),
                            modifier = Modifier.weight(1f),
                            onClick = { confirmBatchIgnore = true }
                        ) {
                            Text(
                                "忽略所选（${selected.size}）",
                                maxLines = 1, overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    androidx.compose.material3.Button(
                        enabled = selected.isNotEmpty(),
                        onClick = { confirmBatchImport = true },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 4.dp)
                    ) {
                        Text("入库所选（${selected.size}）")
                    }
                }

                // 底部输入面板（ChatScreen 同款）：审核页里直接跟助理说怎么排
                HubComposer(
                    value = input,
                    onValueChange = { input = it },
                    placeholder = "跟助理说：把周会排到明天下午…",
                    pendingFiles = pendingFiles,
                    onRemoveAttachment = { pendingFiles = pendingFiles - it },
                    onPickImage = {
                        haptics.perform(HapticEngine.Kind.Select)
                        pickImage.launch(androidx.activity.result.PickVisualMediaRequest(
                            androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageOnly
                        ))
                    },
                    onPickFile = {
                        haptics.perform(HapticEngine.Kind.Select)
                        pickFile.launch(arrayOf("*/*"))
                    },
                    sendEnabled = (input.isNotBlank() || pendingFiles.isNotEmpty()) && hubSessionId != null,
                    onSend = {
                        val text = composedInput()
                        // 2026-11-12：来源会话可能是通用助手（assistantId=0），守卫只看目标会话在不在
                        if (text.isNotEmpty() && hubSessionId != null) {
                            haptics.perform(HapticEngine.Kind.Tap)
                            input = ""
                            pendingFiles = emptyList()
                            onChat(hubSessionId, hubAssistantId, hubProjectId, text, true)
                        }
                    },
                    onExpandToChat = {
                        if (hubSessionId != null) {
                            val text = composedInput()
                            input = ""
                            pendingFiles = emptyList()
                            onChat(hubSessionId, hubAssistantId, hubProjectId, text, false)
                        }
                    }
                )
            }
        }
    }

        // 2026-11-12 批量忽略确认（上千条一次操作不可撤销感太强，必须先过一道）
        if (confirmBatchIgnore) {
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { confirmBatchIgnore = false },
                modifier = Modifier.motionEnter(),
                title = { Text("忽略 ${selected.size} 条草稿？") },
                text = {
                    Text(
                        "这些草稿会移到「已忽略」，不再出现在待处理里；" +
                            "要找回就去右上角「审核历史 → 已忽略」。不会删除任何数据。"
                    )
                },
                confirmButton = {
                    androidx.compose.material3.TextButton(onClick = {
                        val ids = selected.toList()
                        confirmBatchIgnore = false
                        selected = emptySet()
                        selectMode = false
                        scope.launch {
                            runCatching {
                                Graph.info.batchMarkPending(ids, "ignored")
                                haptics.perform(HapticEngine.Kind.Tap)
                            }.onFailure {
                                android.widget.Toast.makeText(
                                    Graph.appContext, "批量忽略失败：" + (it.message ?: "未知错误"),
                                    android.widget.Toast.LENGTH_LONG
                                ).show()
                            }
                        }
                    }) { Text("忽略 ${selected.size} 条") }
                },
                dismissButton = {
                    androidx.compose.material3.TextButton(onClick = { confirmBatchIgnore = false }) { Text("取消") }
                }
            )
        }

        // 2026-11-12 批量入库确认：入库=直接进时间轴（比忽略更"重"的一侧），同样先过一道
        if (confirmBatchImport) {
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { confirmBatchImport = false },
                modifier = Modifier.motionEnter(),
                title = { Text("入库 ${selected.size} 条草稿？") },
                text = {
                    Text(
                        "这些草稿会按各自的内容入库为日程/待办（与卡片 ✓ 同一条通道），" +
                            "直接进时间轴；缺时间的条目会入库失败，结束时统一报告。"
                    )
                },
                confirmButton = {
                    androidx.compose.material3.TextButton(onClick = {
                        val ids = selected.toList()
                        confirmBatchImport = false
                        selected = emptySet()
                        selectMode = false
                        scope.launch {
                            var ok = 0; var fail = 0
                            val targets = runCatching { Graph.info.pendingItems() }
                                .getOrDefault(emptyList()).filter { it.id in ids }
                            for (t in targets) {
                                runCatching {
                                    io.github.m0use001.planability.ai.ptl.PendingImporter.commit(t)
                                }.onSuccess { ok++ }.onFailure { fail++ }
                            }
                            haptics.perform(if (fail == 0) HapticEngine.Kind.Success else HapticEngine.Kind.Error)
                            android.widget.Toast.makeText(
                                Graph.appContext,
                                if (fail == 0) "已入库 $ok 条" else "已入库 $ok 条，失败 $fail 条（多为缺时间，逐条看 ✓ 可补）",
                                android.widget.Toast.LENGTH_LONG
                            ).show()
                        }
                    }) { Text("入库 ${selected.size} 条") }
                },
                dismissButton = {
                    androidx.compose.material3.TextButton(onClick = { confirmBatchImport = false }) { Text("取消") }
                }
            )
        }

        // 2026-11-08：编辑能力从原「待处理」页搬到这里（那页已收敛成纯历史）。
        // 待确认的草稿常常只差一个字/一个时段，为这个去开对话不划算。
        editing?.let { item ->
            var title by remember(item.id) { mutableStateOf(item.title) }
            var timeText by remember(item.id) { mutableStateOf(item.timeText) }
            var place by remember(item.id) { mutableStateOf(item.place) }
            var kind by remember(item.id) { mutableStateOf(item.kind) }
            // 2026-11-24：把「重要/紧急/标签」也放进编辑弹窗——AI 常把它们全拉满/漏设，
            // 用户审核时可当场改正，保存时写回 payload（PendingImporter.commit 读的就是 payload）。
            val pj0 = remember(item.id) {
                runCatching { if (item.payload.isNotBlank()) org.json.JSONObject(item.payload) else org.json.JSONObject() }
                    .getOrDefault(org.json.JSONObject())
            }
            fun selOf(key: String): Int = when (pj0.optString(key).lowercase()) {
                "high" -> 1; "normal" -> 2; "low" -> 3; else -> 0
            }
            var impSel by remember(item.id) { mutableStateOf(selOf("priority")) }
            var urgSel by remember(item.id) { mutableStateOf(selOf("urgency")) }
            var labelText by remember(item.id) { mutableStateOf(pj0.optString("labels")) }
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { editing = null },
                modifier = Modifier.motionEnter(),
                title = { Text("编辑条目") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        io.github.m0use001.planability.ui.common.AppButtonGroup(
                            options = listOf("待办", "日程"),
                            selectedIndex = if (kind == "todo") 0 else 1,
                            onSelect = { kind = if (it == 0) "todo" else "schedule" }
                        )
                        androidx.compose.material3.OutlinedTextField(
                            value = title, onValueChange = { title = it },
                            label = { Text("标题") }, singleLine = true
                        )
                        androidx.compose.material3.OutlinedTextField(
                            value = timeText, onValueChange = { timeText = it },
                            label = { Text("时间") }, singleLine = true
                        )
                        androidx.compose.material3.OutlinedTextField(
                            value = place, onValueChange = { place = it },
                            label = { Text("地点") }, singleLine = true
                        )
                        Text("重要程度", style = MaterialTheme.typography.labelMedium)
                        io.github.m0use001.planability.ui.common.AppButtonGroup(
                            options = listOf("未设", "高", "中", "低"),
                            selectedIndex = impSel,
                            onSelect = { impSel = it }
                        )
                        Text("紧急程度", style = MaterialTheme.typography.labelMedium)
                        io.github.m0use001.planability.ui.common.AppButtonGroup(
                            options = listOf("未设", "高", "中", "低"),
                            selectedIndex = urgSel,
                            onSelect = { urgSel = it }
                        )
                        androidx.compose.material3.OutlinedTextField(
                            value = labelText, onValueChange = { labelText = it },
                            label = { Text("自由标签（逗号分隔，可留空）") }, singleLine = true
                        )
                    }
                },
                confirmButton = {
                    androidx.compose.material3.TextButton(onClick = {
                        scope.launch {
                            val np = runCatching { org.json.JSONObject(item.payload) }.getOrElse { org.json.JSONObject() }
                            np.put("title", title.trim())
                            if (place.trim().isNotBlank()) np.put("place", place.trim()) else np.remove("place")
                            fun putLv(key: String, sel: Int) {
                                when (sel) {
                                    1 -> np.put(key, "high")
                                    2 -> np.put(key, "normal")
                                    3 -> np.put(key, "low")
                                    else -> np.remove(key)
                                }
                            }
                            putLv("priority", impSel)
                            putLv("urgency", urgSel)
                            if (labelText.trim().isNotBlank()) np.put("labels", labelText.trim()) else np.remove("labels")
                            Graph.info.updatePending(
                                item.copy(
                                    title = title.trim(), timeText = timeText.trim(),
                                    place = place.trim(), kind = kind, payload = np.toString()
                                )
                            )
                        }
                        editing = null
                    }) { Text("保存") }
                },
                dismissButton = {
                    androidx.compose.material3.TextButton(onClick = { editing = null }) { Text("取消") }
                }
            )
        }
}

/**
 * v54.1 会话二级页：中枢项目的会话列表（全屏）+ 新建。
 * 进入会话 = 纯打开（autoSend=false），附件/换智能体在 ChatScreen 里做。
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun HubSessionsScreen(
    onDone: () -> Unit,
    onChat: (sessionId: Long?, assistantId: Long, projectId: Long, text: String, autoSend: Boolean) -> Unit
) {
    val haptics = LocalHaptics.current
    val version by Graph.dataVersion.flow.collectAsState()
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    var sessions by remember { mutableStateOf<List<io.github.m0use001.planability.data.ChatSession>>(emptyList()) }
    var hubProjectId by remember { mutableStateOf(0L) }
    var mainSessionId by remember { mutableStateOf<Long?>(null) }
    // 行尾 ⋮ 菜单与二级对话框（抽屉 SessionTreeRow 同款能力：重命名/归档/删除）
    var menuFor by remember { mutableStateOf<io.github.m0use001.planability.data.ChatSession?>(null) }
    var renameFor by remember { mutableStateOf<io.github.m0use001.planability.data.ChatSession?>(null) }
    var renameText by remember { mutableStateOf("") }
    var deleteFor by remember { mutableStateOf<io.github.m0use001.planability.data.ChatSession?>(null) }

    LaunchedEffect(version, hubProjectId) {
        if (hubProjectId > 0L) {
            sessions = runCatching { HubProject.sessionsOf(hubProjectId) }.getOrDefault(emptyList())
        }
    }
    LaunchedEffect(Unit) {
        runCatching {
            val aid = Graph.assistants.byKey(AssistantRepo.KEY_HUB)?.id ?: 0L
            hubProjectId = HubProject.ensureId()
            mainSessionId = HubProject.ensureMainSession(aid, hubProjectId)
        }
    }

    PredictiveBackOverlay(onBack = onDone) {
        Scaffold(
            floatingActionButtonPosition = androidx.compose.material3.FabPosition.Center,
            topBar = {
                HubTitleBar(
                    title = "会话",
                    onDone = onDone,
                    subtitleContent = {
                        HubPill("${sessions.size} 个会话")
                    }
                )
            },
            floatingActionButton = {
                androidx.compose.material3.ExtendedFloatingActionButton(
                    onClick = {
                        haptics.perform(HapticEngine.Kind.Tap)
                        onChat(null, 0L, hubProjectId, "", false)   // 新建会话（通用助手起步）
                    },
                    icon = { Icon(Icons.Rounded.Add, null) },
                    text = { Text("新建会话") }
                )
            }
        ) { pad ->
            if (sessions.isEmpty()) {
                Box(
                    Modifier.fillMaxSize().padding(pad),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "还没有会话\n点下方「新建会话」开始",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                // 2026-11-11 用户：会话行与抽屉「项目与会话」文件夹下的会话（SessionTreeRow）对齐——
                // 40dp 行高 / 15dp 图标 / bodySmall 标题 / labelSmall 时间 / 48dp 尾块 ⋮，去掉卡片底色
                LazyColumn(
                    Modifier
                        .fillMaxSize()
                        .padding(pad)
                        .padding(horizontal = 12.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 10.dp)
                ) {
                    items(sessions, key = { it.id }) { s ->
                        val isMain = s.id == mainSessionId
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(40.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .clickable {
                                    haptics.perform(HapticEngine.Kind.Tap)
                                    onChat(s.id, s.assistantId, hubProjectId, "", false)
                                }
                                .padding(start = 22.dp, end = 6.dp)
                        ) {
                            if (isMain) {
                                Text("🧭", style = MaterialTheme.typography.bodySmall)
                            } else {
                                Icon(
                                    AppIcons.Kind.Chat, null,
                                    Modifier.size(15.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Spacer(Modifier.width(7.dp))
                            Text(
                                s.title,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                sessionTime(s.updatedAt),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            // 尾块与抽屉会话行同一套几何（48dp ⋮，时间右缘对齐文件夹计数）
                            Spacer(Modifier.width(6.dp))
                            Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                                IconButton(
                                    onClick = { haptics.perform(HapticEngine.Kind.Tap); menuFor = s },
                                    modifier = Modifier.fillMaxSize()
                                ) {
                                    Icon(
                                        Icons.Rounded.MoreVert, "更多",
                                        Modifier.size(16.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                DropdownMenu(
                                    expanded = menuFor?.id == s.id,
                                    onDismissRequest = { menuFor = null }
                                ) {
                                    DropdownMenuItem(
                                        modifier = Modifier.clip(RoundedCornerShape(10.dp)), // 2026-11-15 点击高亮圆角化（原来是整行直角方块）
                                        text = { Text("重命名") },
                                        onClick = {
                                            menuFor = null
                                            renameText = s.title
                                            renameFor = s
                                        }
                                    )
                                    DropdownMenuItem(
                                        modifier = Modifier.clip(RoundedCornerShape(10.dp)), // 2026-11-15 点击高亮圆角化（原来是整行直角方块）
                                        text = { Text("归档") },
                                        onClick = {
                                            menuFor = null
                                            scope.launch { Graph.chat.setArchived(s.id, true) }
                                        }
                                    )
                                    DropdownMenuItem(
                                        modifier = Modifier.clip(RoundedCornerShape(10.dp)), // 2026-11-15 点击高亮圆角化（原来是整行直角方块）
                                        text = { Text("删除", color = MaterialTheme.colorScheme.error) },
                                        onClick = { menuFor = null; deleteFor = s }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    // 重命名 / 删除确认（与抽屉会话行二级菜单同构）
    renameFor?.let { target ->
        AlertDialog(
            onDismissRequest = { renameFor = null },
            title = { Text("重命名会话") },
            text = {
                OutlinedTextField(
                    value = renameText, onValueChange = { renameText = it },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val t = renameText.trim()
                    if (t.isNotEmpty()) {
                        val id = target.id
                        renameFor = null
                        scope.launch { Graph.chat.renameSession(id, t) }
                    }
                }) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { renameFor = null }) { Text("取消") } }
        )
    }
    deleteFor?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteFor = null },
            title = { Text("删除会话") },
            text = { Text("删除「${target.title}」？删除后不可恢复。") },
            confirmButton = {
                TextButton(onClick = {
                    val id = target.id
                    deleteFor = null
                    scope.launch { Graph.chat.deleteSession(id) }
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deleteFor = null }) { Text("取消") } }
        )
    }
}

/** 会话列表时间：当天显示 HH:mm，今年显示 M/d，更早显示 yyyy/M/d */
private fun sessionTime(millis: Long): String {
    val d = java.time.Instant.ofEpochMilli(millis).atZone(java.time.ZoneId.systemDefault()).toLocalDate()
    val today = java.time.LocalDate.now()
    return when {
        d == today -> AppTime.millisToTime(millis)
        d.year == today.year -> "${d.monthValue}/${d.dayOfMonth}"
        else -> "${d.year}/${d.monthValue}/${d.dayOfMonth}"
    }
}

/** 待处理草稿卡片：类型图标 + 标题 + 来源/时间徽标 + AI 建议时段高亮 + ✓/✕ */
@Composable
private fun PendingHubCard(
    item: PendingItem,
    projNames: Map<Long, String>,
    onImport: () -> Unit,
    onIgnore: () -> Unit,
    onEdit: () -> Unit = {},
    // 2026-11-12 批量模式：整卡可点勾选，逐条操作按钮收起，防误触
    selectMode: Boolean = false,
    checked: Boolean = false,
    onToggle: () -> Unit = {}
) {
    val isSchedule = item.kind == "schedule"
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(
                if (checked) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
                else MaterialTheme.colorScheme.surfaceContainerLow
            )
            .then(
                if (selectMode) Modifier.clickable { onToggle() } else Modifier
            )
            // 2026-11-12 用户「每个日程待办上下宽一点，太挤了」：竖向 10→15，行间再垫 5dp
            .padding(horizontal = 14.dp, vertical = 15.dp)
    ) {
        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (selectMode) {
                    // 勾选框画在整卡点击层之上：点框与点卡同义，只是可视锚点
                    Icon(
                        if (checked) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked,
                        contentDescription = if (checked) "已选" else "未选",
                        Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.width(8.dp))
                } else {
                    Icon(
                        if (isSchedule) Icons.Rounded.Event else Icons.Rounded.TaskAlt,
                        contentDescription = if (isSchedule) "日程" else "待办",
                        Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.width(8.dp))
                }
                Text(
                    item.title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    // 2026-11-24 用户「草稿内容本体只给一行显示不下，多选还更压缩」：
                    // 标题放开到 3 行；多选勾选框占的 28dp 宽度靠行数持平，不再双重压缩
                    maxLines = 3, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
            }
            Spacer(Modifier.height(5.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                val srcCn = when (item.source) {
                    "session" -> "会话"
                    "manual" -> "手动"
                    // 2026-11-12：自动化流水线的 todo_add/schedule_add 也落草稿了，
                    // 不映射会掉进 else 被标成「提取」，审核时看不出是谁产的
                    "automation" -> "自动化"
                    else -> "提取"
                }
                // v54：会话产生的草稿带上来源项目名——审核时才知道是哪件事的上下文
                val proj = if (item.source == "session" && item.originProjectId > 0)
                    "·" + (projNames[item.originProjectId] ?: "") else ""
                Badge("· $srcCn$proj", MaterialTheme.colorScheme.onSurfaceVariant)
                if (item.timeText.isNotBlank()) Badge("· ${item.timeText}", MaterialTheme.colorScheme.onSurfaceVariant)
                if (item.place.isNotBlank()) Badge("· @${item.place}", MaterialTheme.colorScheme.onSurfaceVariant)
                // 2026-11-24 用户「AI 建的条目不设标签、重要/紧急直接全拉满，审核时又看不到」：
                // 从 payload 解析重要/紧急/标签，在审核卡片上直接暴露（顶格或缺失用 error 色高亮），
                // 用户在入库前一眼能发现并去「编辑」里改，而不是 commit 后才发现。
                val pj = remember(item.payload) {
                    runCatching { if (item.payload.isNotBlank()) org.json.JSONObject(item.payload) else null }.getOrNull()
                }
                if (pj != null) {
                    fun lvOf(key: String): Int = when (pj.optString(key).lowercase()) {
                        "high", "4", "0" -> 4; "normal", "2", "1" -> 2; "low", "0" -> 0; else -> -1
                    }
                    fun lvTxt(v: Int) = when {
                        v >= 4 -> "高"; v == 3 -> "偏高"; v == 2 -> "中"; v == 1 -> "偏低"; v == 0 -> "低"; else -> "未设"
                    }
                    val impLv = lvOf("priority")
                    val urgLv = lvOf("urgency")
                    val tagName = pj.optString("tag").ifBlank { pj.optString("labels") }
                    Badge(
                        "· 重要" + lvTxt(impLv),
                        if (impLv >= 4) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Badge(
                        "· 紧急" + lvTxt(urgLv),
                        if (urgLv >= 4) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (tagName.isNotBlank()) Badge("· 🏷$tagName", MaterialTheme.colorScheme.onSurfaceVariant)
                    else Badge("· 无标签", MaterialTheme.colorScheme.error)
                }
            }
            // 2026-11-12 用户「建议栏被强制换行了」：建议时段原来挤在徽标行里抢宽度，
            // 剩余空间不够就折行——改成独立一行、单行不折（超宽省略），与徽标互不挤压
            if (item.suggestedStart > 0) {
                Spacer(Modifier.height(5.dp))
                Text(
                    "建议 " + AppTime.millisToTime(item.suggestedStart) + "-" + AppTime.millisToTime(item.suggestedEnd),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.tertiary,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(MaterialTheme.colorScheme.tertiaryContainer)
                        .padding(horizontal = 8.dp, vertical = 2.dp)
                )
            }
            if (item.extra.isNotBlank()) {
                Spacer(Modifier.height(5.dp))
                Text(
                    item.extra,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2, overflow = TextOverflow.Ellipsis
                )
            }
            // 2026-11-12 用户「3 个按键应该独立一行在每个草稿底部」：编辑/入库/忽略
            // 从标题行右侧挪到卡片底部右侧独立一行，标题拿回整行宽度
            if (!selectMode) {
                Spacer(Modifier.height(2.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onEdit, Modifier.size(34.dp)) {
                        Icon(Icons.Rounded.Edit, "编辑", Modifier.size(17.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Spacer(Modifier.width(2.dp))
                    IconButton(onClick = onImport, Modifier.size(34.dp)) {
                        Icon(Icons.Rounded.CheckCircleOutline, "确认入库", Modifier.size(22.dp), tint = MaterialTheme.colorScheme.primary)
                    }
                    Spacer(Modifier.width(2.dp))
                    IconButton(onClick = onIgnore, Modifier.size(34.dp)) {
                        Icon(Icons.Rounded.Close, "忽略", Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun Badge(text: String, color: androidx.compose.ui.graphics.Color) {
    Text(
        text, style = MaterialTheme.typography.labelSmall, color = color,
        maxLines = 1, overflow = TextOverflow.Ellipsis
    )
}

/** 2026-11-12 用户拍板：卡片退场方向——入库=向右抽走（进时间轴），忽略=下沉消失（进忽略堆） */
private enum class PendingExit { Import, Ignore }

/**
 * 待处理卡片的退场动画包装（2026-11-12 用户「同意向右抽走、不同意下沉消失」）：
 * - Import：translationX 推出卡片宽度——像把这条日程**抽进时间轴**；
 * - Ignore：translationY 下沉 + 轻微缩小 + 渐隐——像掉进**忽略堆**。
 * 真正落库由 HubReviewScreen.startExit 在动画播完（300ms）后执行，动画期间防重复点击。
 */
@Composable
private fun PendingHubRow(
    modifier: Modifier = Modifier,
    item: PendingItem,
    projNames: Map<Long, String>,
    exitingKind: PendingExit?,
    onImport: () -> Unit,
    onIgnore: () -> Unit,
    onEdit: () -> Unit,
    selectMode: Boolean,
    checked: Boolean,
    onToggle: () -> Unit
) {
    var cardW by remember { mutableStateOf(0f) }
    var cardH by remember { mutableStateOf(0f) }
    val t by animateFloatAsState(
        targetValue = if (exitingKind != null) 1f else 0f,
        animationSpec = tween(260, easing = FastOutLinearInEasing),
        label = "pendingExit"
    )
    Box(
        modifier
            .fillMaxWidth()
            .onSizeChanged { cardW = it.width.toFloat(); cardH = it.height.toFloat() }
            .graphicsLayer {
                when (exitingKind) {
                    PendingExit.Import -> translationX = t * cardW
                    PendingExit.Ignore -> {
                        translationY = t * cardH
                        val s = 1f - 0.08f * t
                        scaleX = s; scaleY = s
                    }
                    null -> {}
                }
                alpha = 1f - t
            }
    ) {
        PendingHubCard(
            item, projNames,
            onImport = onImport, onIgnore = onIgnore, onEdit = onEdit,
            selectMode = selectMode, checked = checked, onToggle = onToggle
        )
    }
}

/** v54 防疲劳筛选 chip（单选；选中=primary 容器底） */
@Composable
private fun FilterChip2(key: String, label: String, current: String, onPick: (String) -> Unit) {
    val selected = key == current
    Text(
        label,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
        else MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(
                if (selected) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surfaceContainerHigh
            )
            .clickable { onPick(key) }
            .padding(horizontal = 10.dp, vertical = 5.dp)
    )
}

// ==================== v57 与正常 AI 会话（ChatScreen）对齐的共用件 ====================

/**
 * 中枢三页共用顶栏：ChatScreen 同款 M3ETitleBar 固定态（collapsedFraction=1、
 * expandable=false）——圆角浮层常驻 + 小标题 + 胶囊副标题行，与计划/笔记/会话页一条视觉线。
 */
@Composable
private fun HubTitleBar(
    title: String,
    onDone: () -> Unit,
    subtitleContent: (@Composable () -> Unit)? = null,
    actions: (@Composable () -> Unit)? = null
) {
    val haptics = LocalHaptics.current
    M3ETitleBar(
        title = title,
        collapsedFraction = 1f,
        expandable = false,
        subtitleContent = subtitleContent,
        navigationIcon = {
            IconButton(onClick = { haptics.perform(HapticEngine.Kind.Tap); onDone() }) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, "返回")
            }
        },
        actions = actions
    )
}

/** ChatScreen 顶栏同款胶囊：surfaceContainerHigh 底 + labelSmall；onClick 可空=纯信息胶囊 */
@Composable
private fun HubPill(text: String, onClick: (() -> Unit)? = null) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        modifier = Modifier
            .clip(RoundedCornerShape(percent = 50))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
            .padding(horizontal = 8.dp, vertical = 3.dp)
    )
}

/**
 * 中枢输入面板 —— 与 ChatScreen.InputBar 同一版式（2026-11-01 会话页定稿）：
 * 整宽贴底「倒置顶栏」（上双圆角 24dp、surfaceContainerHigh，底部含手势条同色铺满），
 * 面板顶上把手（点按/上拉就地拉高输入框，长按进全屏会话），文字行下排按钮行
 * （左：私人助理身份胶囊；右：＋附件菜单 + 圆形发送键，有字或带附件=primary、否则灰）。
 * 附件与 ChatScreen 同一套：＋ → 图片/文件 → 复制进应用 attachments/ → 面板内预览带；
 * 发送时由调用方把「 [附件已附: …]」标记合成进正文。中枢是「转发」输入（发送即带文
 * 跳进全屏会话），没有生成/停止态；预览带不放假点击（预览打开依赖会话页内部的
 * 文件预览框，这里只有 × 移除）。
 */
@Composable
private fun HubComposer(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    onSend: () -> Unit,
    sendEnabled: Boolean,
    onExpandToChat: (() -> Unit)? = null,
    // 附件（v57.1 对齐 ChatScreen.InputBar）
    pendingFiles: List<PendingAttachment> = emptyList(),
    onRemoveAttachment: (PendingAttachment) -> Unit = {},
    onPickImage: () -> Unit = {},
    onPickFile: () -> Unit = {}
) {
    Column(Modifier.imePadding()) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .padding(start = 8.dp, end = 6.dp, top = 6.dp, bottom = 2.dp)
        ) {
            // 把手：与 ChatScreen.InputBar 同一交互（上拉展开/下拉收回/点一下切换；长按进全屏）
            var tall by remember { mutableStateOf(false) }
            var dragAcc by remember { mutableStateOf(0f) }
            // 高度插值只这一条动画（同 ChatScreen：避免两套动画叠加抖动）
            val fieldMinH by animateDpAsState(
                targetValue = if (tall) 168.dp else 56.dp,
                animationSpec = Motion.standardSpatial(),
                label = "hubComposerFieldHeight"
            )
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(16.dp)
                    .pointerInput(Unit) {
                        detectVerticalDragGestures(
                            onDragStart = { dragAcc = 0f },
                            onDragEnd = {
                                if (dragAcc < -12f) tall = true
                                if (dragAcc > 12f) tall = false
                                dragAcc = 0f
                            },
                            onDragCancel = { dragAcc = 0f }
                        ) { _, dy -> dragAcc += dy }
                    }
                    .combinedClickable(
                        onClick = { tall = !tall },
                        onLongClick = { onExpandToChat?.invoke() }
                    )
            ) {
                Box(
                    Modifier
                        .size(width = 32.dp, height = 4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f))
                )
            }
            // 待发送附件预览带（ChatScreen.PendingAttachmentStrip 同款，画在面板内部）
            if (pendingFiles.isNotEmpty()) {
                HubAttachmentStrip(files = pendingFiles, onRemove = onRemoveAttachment)
            }
            TextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = fieldMinH),
                placeholder = { Text(placeholder) },
                maxLines = if (tall) 12 else 4,
                shape = RoundedCornerShape(20.dp),
                colors = TextFieldDefaults.colors(
                    // 面板本身提供底色，输入框透明——同 ChatScreen，避免"框中框"
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    disabledIndicatorColor = Color.Transparent
                )
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(bottom = 2.dp, end = 2.dp)
            ) {
                // 身份胶囊（ChatScreen 的「模型·思考」位）：中枢固定发往私人助理
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clip(RoundedCornerShape(percent = 50))
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                        .padding(start = 10.dp, end = 8.dp, top = 4.dp, bottom = 4.dp)
                ) {
                    Text("🧭", style = MaterialTheme.typography.labelSmall)
                    Spacer(Modifier.width(4.dp))
                    Text(
                        "私人助理",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
                }
                Spacer(Modifier.weight(1f))
                // ＋ 附件菜单（ChatScreen 同款：图片 / 文件）
                var attachMenu by remember { mutableStateOf(false) }
                Box {
                    IconButton(onClick = { attachMenu = true }, modifier = Modifier.size(34.dp)) {
                        Icon(
                            Icons.Rounded.Add, "添加附件",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    DropdownMenu(expanded = attachMenu, onDismissRequest = { attachMenu = false }) {
                        DropdownMenuItem(
                            modifier = Modifier.clip(RoundedCornerShape(10.dp)), // 2026-11-15 点击高亮圆角化（原来是整行直角方块）
                            text = { Text("图片") },
                            leadingIcon = { Icon(Icons.Rounded.Image, null, Modifier.size(18.dp)) },
                            onClick = { attachMenu = false; onPickImage() }
                        )
                        DropdownMenuItem(
                            modifier = Modifier.clip(RoundedCornerShape(10.dp)), // 2026-11-15 点击高亮圆角化（原来是整行直角方块）
                            text = { Text("文件") },
                            leadingIcon = { Icon(Icons.Rounded.AttachFile, null, Modifier.size(18.dp)) },
                            onClick = { attachMenu = false; onPickFile() }
                        )
                    }
                }
                Spacer(Modifier.width(8.dp))
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(38.dp)
                        .clip(RoundedCornerShape(percent = 50))
                        .background(
                            if (sendEnabled) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.surfaceContainerHighest
                        )
                        .clickable(enabled = sendEnabled) { onSend() }
                ) {
                    Icon(
                        Icons.AutoMirrored.Rounded.Send,
                        "发送",
                        tint = if (sendEnabled) MaterialTheme.colorScheme.onPrimary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(19.dp)
                    )
                }
            }
            // 手势操作条区域留在面板内部：面板底色铺到屏幕底边（同 ChatScreen）
            Spacer(
                Modifier
                    .fillMaxWidth()
                    .windowInsetsBottomHeight(WindowInsets.navigationBars)
            )
        }
    }
}

// ==================== 附件渲染（与 ChatScreen 私有件同源复刻，hub 前缀防歧义） ====================

/** 待发送附件横向预览带：图片 56dp 缩略图、文件卡；右上 × 移除（不放假点击） */
@Composable
private fun HubAttachmentStrip(
    files: List<PendingAttachment>,
    onRemove: (PendingAttachment) -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(start = 16.dp, end = 12.dp, top = 6.dp)
    ) {
        files.forEach { pf ->
            val isImage = remember(pf.name) { hubIsImageAttachment(pf.name) }
            val thumb = hubAttachmentThumbnail(pf.relPath, isImage, maxSide = 128)
            Box(Modifier.padding(end = 8.dp)) {
                if (thumb != null) {
                    Image(
                        bitmap = thumb.asImageBitmap(),
                        contentDescription = pf.name,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(56.dp)
                            .clip(RoundedCornerShape(10.dp))
                    )
                } else {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .height(56.dp)
                            .widthIn(max = 200.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                            .padding(horizontal = 10.dp)
                    ) {
                        Icon(
                            hubAttachmentTypeIcon(pf.name), null,
                            Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            pf.name,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                // 移除角标：压在右上角，点击区域 18dp
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(4.dp)
                        .size(18.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                        .clickable { onRemove(pf) }
                ) {
                    Icon(
                        Icons.Rounded.Close, "移除",
                        Modifier.size(12.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

/** 附件是否图片（缩略图与预览带共用同一判定） */
private fun hubIsImageAttachment(name: String): Boolean =
    Regex("(?i)\\.(png|jpe?g|webp|gif|bmp|heic)$").containsMatchIn(name)

/** 采样解码附件缩略图（IO 线程，最大边约 maxSide px）；解不出返回 null，调用方退化成图标 */
@Composable
private fun hubAttachmentThumbnail(relPath: String, isImage: Boolean, maxSide: Int = 96): android.graphics.Bitmap? {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val thumb by produceState<android.graphics.Bitmap?>(null, relPath, isImage, maxSide) {
        value = if (!isImage) null else withContext(Dispatchers.IO) {
            runCatching {
                val f = java.io.File(ctx.filesDir, relPath)
                if (!f.exists()) return@runCatching null
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(f.absolutePath, bounds)
                var s = 1
                while (bounds.outWidth / s > maxSide && s < 8) s *= 2
                BitmapFactory.decodeFile(f.absolutePath, BitmapFactory.Options().apply { inSampleSize = s })
            }.getOrNull()
        }
    }
    return thumb
}

/** 附件的类型图标（与 ChatScreen 同一张表：同一文件类型全 App 同一图标） */
private fun hubAttachmentTypeIcon(name: String): ImageVector {
    val ext = name.substringAfterLast('.', "").lowercase()
    return when (ext) {
        "doc", "docx", "rtf", "odt", "pages" -> Icons.Rounded.Description
        "xls", "xlsx", "csv", "ods", "numbers" -> Icons.Rounded.TableChart
        "ppt", "pptx", "odp", "key" -> Icons.Rounded.Slideshow
        "pdf" -> Icons.Rounded.PictureAsPdf
        "md", "txt", "log", "readme" -> Icons.Rounded.Article
        "kt", "kts", "java", "py", "js", "ts", "json", "xml", "html", "css",
        "yml", "yaml", "sql", "sh", "gradle" -> Icons.Rounded.Code
        "zip", "7z", "rar", "tar", "gz", "bz2" -> Icons.Rounded.FolderZip
        "mp3", "wav", "m4a", "flac", "ogg", "aac" -> Icons.Rounded.AudioFile
        "mp4", "mov", "webm", "mkv", "avi" -> Icons.Rounded.VideoFile
        "png", "jpg", "jpeg", "webp", "gif", "bmp", "heic" -> Icons.Rounded.Image
        else -> Icons.Rounded.InsertDriveFile
    }
}
