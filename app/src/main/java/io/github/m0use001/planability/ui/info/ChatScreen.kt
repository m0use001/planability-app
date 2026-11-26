@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package io.github.m0use001.planability.ui.info

import android.graphics.BitmapFactory
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.Offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Archive
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.BookmarkBorder
import androidx.compose.material.icons.rounded.AttachFile
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Create
import androidx.compose.material.icons.rounded.DataUsage
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.FiberManualRecord
import androidx.compose.material.icons.rounded.FormatQuote
import androidx.compose.material.icons.rounded.FolderCopy
import androidx.compose.material.icons.rounded.FolderOff
import androidx.compose.material.icons.rounded.GppBad
import androidx.compose.material.icons.rounded.GppMaybe
import androidx.compose.material.icons.rounded.Article
import androidx.compose.material.icons.rounded.AudioFile
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.FolderZip
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.PictureAsPdf
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Slideshow
import androidx.compose.material.icons.rounded.TableChart
import androidx.compose.material.icons.rounded.VideoFile
import androidx.compose.material.icons.rounded.Inbox
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material.icons.rounded.PictureInPictureAlt
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.SmartToy
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.UnfoldMore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.sp
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import io.github.m0use001.planability.ui.common.adaptiveColumn
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.m0use001.planability.ai.AiClient
import io.github.m0use001.planability.ai.ChatEngine
import io.github.m0use001.planability.ai.ChatUiState
import io.github.m0use001.planability.ai.ContextEngine
import io.github.m0use001.planability.ai.ContextPlan
import io.github.m0use001.planability.ai.ThinkingLevels
import io.github.m0use001.planability.app.PredictiveBackOverlay
import io.github.m0use001.planability.app.swipeRightToOpen
import io.github.m0use001.planability.core.AppTime
import io.github.m0use001.planability.core.HapticEngine
import io.github.m0use001.planability.core.LocalHaptics
import io.github.m0use001.planability.core.reachedEndHaptic
import io.github.m0use001.planability.core.formatMonthDay
import io.github.m0use001.planability.core.toLocalDate
import io.github.m0use001.planability.data.ChatMessage
import io.github.m0use001.planability.data.ChatSession
import io.github.m0use001.planability.data.Graph
import io.github.m0use001.planability.ui.common.EmptyHint
import io.github.m0use001.planability.ui.common.MediumFab
import io.github.m0use001.planability.ui.common.Motion
import io.github.m0use001.planability.ui.common.motionEnter
import io.github.m0use001.planability.ui.notes.MarkdownView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import androidx.compose.runtime.rememberUpdatedState
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import androidx.compose.runtime.snapshotFlow
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.scrollBy
import kotlinx.coroutines.launch
import androidx.compose.foundation.border

// ==================== 会话页（M1 流式对话 + 交互打磨） ====================

/**
 * 输入草稿（内存级，按桶保存/恢复；桶键见 [key]）。
 *
 * v14 改成 `internal`：手机端悬浮窗输入框（`FloatChatActivity`）要和主窗共用同一份草稿，
 * 否则「收起为悬浮窗」会丢字 —— 这正是 PC 侧 R7 踩过的坑（草稿必须活在两边都能看到的地方）。
 */
internal object ChatDrafts {
    // 2026-11-01：key 由 Long 改 String——未创建的新会话**按项目分桶**。
    private val map = mutableMapOf<String, String>()

    /** 草稿桶：已存会话 = 会话 id；未创建的新会话（id=0）= "p<projectId>"。
     *  原来新会话全项目共用一个 key 0：A 项目没发出去的草稿会自动填进 B 项目的新对话
     *  （2026-11-01 用户实测：线代新建对话自动填充了英语项目下写的草稿）。 */
    fun key(sessionId: Long, projectId: Long): String =
        if (sessionId > 0) sessionId.toString() else "p$projectId"

    fun get(k: String): String = map[k] ?: ""
    fun set(k: String, text: String) {
        if (text.isEmpty()) map.remove(k) else map[k] = text
        // 后台冻结（2026-10-30 用户需求）：草稿同步落 KV（shell.freeze.chatdraft.<桶>），
        // 进程被杀后重进不再丢。600ms 去抖——逐键落库太浪费；内存 map 仍是
        // 跨屏共享的第一层（本 object 的既有关注点，见上）。
        io.github.m0use001.planability.app.UiFreeze.save(
            "shell.freeze.chatdraft.$k", text, debounceMs = 600
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    sessionId: Long?,
    /** 2026-11-08：中枢功能首页（审核/会话/更多）入口——父页提供才显示；
     *  私人助理会话在标题栏底部胶囊行、普通会话在 ⋮ 菜单 */
    onOpenHub: (() -> Unit)? = null,
    assistantId: Long = 0L,
    initialInput: String = "",
    projectId: Long = 0L,
    initialFolderId: Long = 0L,
    autoSend: Boolean = false,
    initialFiles: List<PendingAttachment> = emptyList(),
    initialLinked: List<io.github.m0use001.planability.ai.LinkedRef> = emptyList(),
    draftModel: String = "",
    draftThinking: String = "",
    /** v56：新会话草稿的联网档（'' 跟随全局 / '1' 开 / '0' 关） */
    draftWebSearch: String = "",
    draftPermJson: String = "",
    /**
     * 右滑打开工作台菜单（抽屉在父页 InfoCenterScreen）。
     * null = 该入口不提供右滑（浮窗/子页等）；父页传入后本页根容器挂手势，
     * 因为本页是 Dialog 窗口，父页的 edgeSwipeDrawerOpen 收不到触摸。
     */
    onSwipeOpenDrawer: (() -> Unit)? = null,
    /**
     * v57 平板双栏：true = 本页由父页（InfoCenterScreen 宽屏分支）的双栏 Row **直接承载**——
     * 父页自备 PredictiveBackOverlay，本页不再自包全屏 Dialog、不再挂右滑手势
     * （左栏会话列表在场，右滑该归列表/父页管）。手机路径恒 false，行为零变化。
     */
    embedded: Boolean = false,
    onDone: () -> Unit
) {
    var currentId by remember { mutableStateOf(sessionId ?: 0L) }
    // v57 平板双栏：外层（左栏会话列表）换选会话时把参数同步进 currentId。
    // 手机路径 showChat 每次都是整页重建（remember 重新执行），参数变化不经过本 effect——零行为变化。
    LaunchedEffect(sessionId) {
        val target = sessionId ?: 0L
        if (target != currentId) currentId = target
    }
    var chatProjectId by remember { mutableStateOf(projectId) }
    // v8 项目树：新会话落点（来自抽屉「在文件夹内新建对话」），建会话后一次性归属
    var pendingFolderId by remember { mutableStateOf(initialFolderId) }
    var chatProjectName by remember { mutableStateOf(if (projectId > 0) "" else "未选择项目") }
    var showProjectMove by remember { mutableStateOf(false) }
    // v19.62 严格项目制：新会话（currentId==0）必须先落一个真实项目。
    // 没选就发送 = 拦住并弹项目选择，而不是默默建一条谁都看不见的会话。
    var showPickProject by remember { mutableStateOf(false) }
    var chatModelName by remember { mutableStateOf(draftModel.ifBlank { AiClient.config.modelName }) }
    var showModelPicker by remember { mutableStateOf(false) }
    var sessionTitle by remember { mutableStateOf("新对话") }
    var assistantLabel by remember { mutableStateOf("通用助手") }
    // 2026-11-16 用户：「顶部标题栏显示智能体不要加上 emoji，不然顶部放不下」。
    // 顶栏副标题是一行**固定宽度**的胶囊行（M3ETitleBar 里 fillMaxWidth 的 Row），
    // 「🤖 通用助手」比「通用助手」多占一个 emoji 宽，加上权限/书签/工作流几枚胶囊
    // 就挤爆换行。所以顶栏单独用纯名字；带 emoji 的 assistantLabel 仍给下方
    // 「发送设置」「运行面板」等有富余空间的地方用（那里 emoji 是有信息量的）。
    var assistantPlainName by remember { mutableStateOf("通用助手") }
    // 2026-10-29 输入区「发送设置」：智能体可中途切换——草稿（新会话）+ 已存会话落库
    var assistantDraft by remember { mutableStateOf(assistantId) }
    var showChatSetup by remember { mutableStateOf(false) }
    var showAssistantPick by remember { mutableStateOf(false) }
    // 2026-11-02 自动书签：展开态由顶栏按钮（subtitleContent 智能体胶囊右边）与右侧面板共享
    val bookmarksExpanded = rememberChatBookmarksExpanded()
    var pinned by remember { mutableStateOf(false) }
    var input by remember(currentId) { mutableStateOf(ChatDrafts.get(ChatDrafts.key(currentId, chatProjectId))) }
    // 后台冻结（2026-10-30 用户需求）：进程被杀后 ChatDrafts（内存）清零，从 KV 注水回
    // 草稿；只在本会话草稿为空时注，绝不覆盖用户正在打的字。打字中节流落盘（打字停
    // ~600ms 后经 ChatDrafts.set → KV），杀进程最多丢最后半秒。
    // 桶键按项目分（新会话），所以 keys 带 chatProjectId：换项目后空输入会读新桶。
    LaunchedEffect(currentId, chatProjectId) {
        if (!io.github.m0use001.planability.app.UiFreeze.enabled) return@LaunchedEffect
        if (input.isNotEmpty()) return@LaunchedEffect
        val saved = Graph.settings.kvGet("shell.freeze.chatdraft." + ChatDrafts.key(currentId, chatProjectId))
            ?: return@LaunchedEffect
        if (saved.isNotEmpty()) {
            ChatDrafts.set(ChatDrafts.key(currentId, chatProjectId), saved)
            input = saved
        }
    }
    LaunchedEffect(currentId, input) {
        if (!io.github.m0use001.planability.app.UiFreeze.enabled) return@LaunchedEffect
        kotlinx.coroutines.delay(600)
        ChatDrafts.set(ChatDrafts.key(currentId, chatProjectId), input)   // 内存 map + KV（set 内部再去抖落 KV）
    }
    var confirmDelete by remember { mutableStateOf(false) }
    var actionMsg by remember { mutableStateOf<ChatMessage?>(null) }
    var showContext by remember { mutableStateOf(false) }
    // v53 工作流胶囊 → 面板
    var showWorkflow by remember { mutableStateOf(false) }
    // v19.4：顶栏 ⋮ 二级界面支持查看调用轨迹（与会话列表同款 CallTraceSheet）
    var showTrace by remember { mutableStateOf(false) }
    // v55 作者注面板（⋮ 菜单 → 底部面板；上下文编辑器顶部有同款卡）
    var showAuthorNote by remember { mutableStateOf(false) }
    // v8 输入区：思考档 / 附件 / @勾连 / 会话权限
    var thinking by remember { mutableStateOf(draftThinking) }
    /**
     * 2026-11-14 会话级联网开关（四象限的另一半）：'' 跟随全局 / '1' 开 / '0' 关。
     * 新会话先记草稿，建会话时随行落库；已有会话从库读（见下方 session 快照 effect）。
     */
    var webSearchMode by remember { mutableStateOf(draftWebSearch) }
    var pendingFiles by remember { mutableStateOf(initialFiles) }
    var linkedRefs by remember { mutableStateOf(initialLinked) }
    var showLinkPicker by remember { mutableStateOf(false) }
    // 十三期：权限档位（新会话=草稿，建会话时落库；已有会话读库）
    var permJsonDraft by remember { mutableStateOf(draftPermJson) }
    var sessionPermJson by remember { mutableStateOf("") }
    var showPermMenu by remember { mutableStateOf(false) }
    // 2026-11-01 用户：点权限胶囊只弹「权限」二级面板（原来会拉开整张发送设置）
    var showPermSheet by remember { mutableStateOf(false) }
    // v12：顶栏「更多」次级菜单
    var showMoreMenu by remember { mutableStateOf(false) }
    // v19.8：运行面板（⋮）——流程推进 + 智能体运行情况，可跳详细页
    var showRunPanel by remember { mutableStateOf(false) }
    var runAudit by remember { mutableStateOf<List<io.github.m0use001.planability.data.ToolAudit>>(emptyList()) }
    val dvVersion by io.github.m0use001.planability.data.Graph.dataVersion.flow.collectAsState()
    var showMemoryQuick by remember { mutableStateOf<String?>(null) } // "feedback"/"skill"
    var slashBody by remember { mutableStateOf("") }
    var showExpand by remember { mutableStateOf(false) } // v10：全屏编辑
    val dpProgress by io.github.m0use001.planability.ai.ptl.DeepPlanner.progressFlow().collectAsState()
    val scope = rememberCoroutineScope()

    // M 增强：相册选图 → 复制进应用附件 → 待发送附件列表（发送时合成标记）
    val pickImage = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                runCatching {
                    val name = "img_" + System.currentTimeMillis() + ".jpg"
                    val dst = java.io.File(io.github.m0use001.planability.data.Graph.appContext.filesDir, "attachments/" + name)
                    dst.parentFile?.mkdirs()
                    io.github.m0use001.planability.data.Graph.appContext.contentResolver.openInputStream(uri)?.use { ins ->
                        dst.outputStream().use { out -> ins.copyTo(out) }
                    }
                    if (dst.exists() && dst.length() > 0) {
                        pendingFiles = pendingFiles + PendingAttachment("attachments/" + name, name, "file_read_image")
                    }
                }.onFailure { Toast.makeText(io.github.m0use001.planability.data.Graph.appContext, "图片导入失败：" + it.message, Toast.LENGTH_SHORT).show() }
            }
        }
    }
    // v8 通用附件：任意 mime → attachments/file_<ts>.<ext>
    val pickFile = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                runCatching {
                    val ctx = io.github.m0use001.planability.data.Graph.appContext
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
                }.onFailure { Toast.makeText(io.github.m0use001.planability.data.Graph.appContext, "附件导入失败：" + it.message, Toast.LENGTH_SHORT).show() }
            }
        }
    }
    val haptics = LocalHaptics.current
    val clipboard = LocalClipboardManager.current
    val appContext = androidx.compose.ui.platform.LocalContext.current
    val listState = rememberLazyListState()
    val state by ChatEngine.stateOf(currentId).collectAsState()
    val approval by ChatEngine.pendingApproval().collectAsState()
    // v47：手动区间压缩标记；compactVersion 由详细编辑屏压缩/取消后 +1 触发重载
    var compactVersion by remember { mutableStateOf(0) }
    val compactMarkers by produceState(
        initialValue = emptyList<io.github.m0use001.planability.data.CompactMarkerEntity>(),
        currentId, compactVersion, dvVersion
    ) {
        value = runCatching { Graph.compactMarkers.rangesOf(currentId) }.getOrDefault(emptyList())
    }
    var showContextEditor by remember { mutableStateOf(false) }
    // v50：压缩原文独立查看页（摘要卡「查看原文」进入；0=关闭）
    var compactOriginalFor by remember { mutableStateOf(0L) }
    // v47.1：本会话正在跑的多智能体协作（ask_expert/deep_plan）——聊天列表底部活行数据源
    val subAgentRunsAll by io.github.m0use001.planability.ai.ptl.SubAgentRuns.runs.collectAsState()
    val subAgentsRunning = remember(subAgentRunsAll, currentId) {
        subAgentRunsAll.firstOrNull { it.sessionId == currentId && it.status == "running" }
    }

    // 打开已有会话 → 拉历史（含异常退出残留 streaming 行的修复）
    LaunchedEffect(currentId) {
        if (currentId > 0L) {
            ChatEngine.load(currentId)
            Graph.chat.session(currentId)?.let { s ->
                sessionTitle = s.title
                pinned = s.pinned
                chatModelName = s.model.ifBlank { AiClient.config.modelName }
                thinking = s.thinking
                Graph.assistants.byId(s.assistantId)?.let {
                    assistantLabel = "${it.emoji} ${it.name}"
                    assistantPlainName = it.name
                    assistantDraft = s.assistantId
                }
            }
        } else if (assistantId > 0L) {
            Graph.assistants.byId(assistantId)?.let {
                assistantLabel = "${it.emoji} ${it.name}"
                assistantPlainName = it.name
            }
        }
    }
    LaunchedEffect(initialInput) {
        if (currentId == 0L && input.isEmpty() && initialInput.isNotBlank()) input = initialInput
    }
    // 流结束 → 标题可能被自动命名改过，刷新
    LaunchedEffect(state.streaming) {
        if (!state.streaming && currentId > 0L) {
            Graph.chat.session(currentId)?.let {
                sessionTitle = it.title
                pinned = it.pinned
                chatProjectId = it.projectId
            }
        }
    }
    // 项目名展示（顶栏副标题）
    LaunchedEffect(chatProjectId) {
        chatProjectName = if (chatProjectId > 0L) {
            Graph.projects.byId(chatProjectId)?.name ?: "未选择项目"
        } else "未选择项目"
    }
    // 十三期：权限档位——已有会话读库判定当前档；选档即写库/写草稿
    val currentPermMode = PermModes.modeForPermJson(if (currentId > 0L) sessionPermJson else permJsonDraft)
    LaunchedEffect(currentId) {
        if (currentId > 0L) {
            sessionPermJson = runCatching { Graph.chat.session(currentId)?.permJson ?: "" }.getOrDefault("")
            // v56：已有会话的联网档从库里读回（新会话用 draftWebSearch，默认跟随全局）
            webSearchMode = runCatching { Graph.chat.session(currentId)?.webSearch ?: "" }.getOrDefault("")
        }
    }
    val applyPermMode: (String) -> Unit = { key ->
        haptics.perform(HapticEngine.Kind.Select)
        val json = PermModes.permJsonFor(key)
        if (currentId > 0L) {
            sessionPermJson = json
            scope.launch { Graph.chat.setSessionPerm(currentId, json) }
        } else {
            permJsonDraft = json
        }
    }
    // 新会话创建后一次性应用草稿（思考档/联网档/权限档/项目树落点）
    val applyNewSessionDrafts: suspend (Long) -> Unit = { newId ->
        if (thinking.isNotBlank()) Graph.chat.setSessionThinking(newId, thinking)
        // v56：联网档只在用户显式选过时才写（默认空串=跟随全局，不必写库）
        if (webSearchMode.isNotBlank()) Graph.chat.setSessionWebSearch(newId, webSearchMode)
        if (permJsonDraft.isNotBlank()) Graph.chat.setSessionPerm(newId, permJsonDraft)
        if (pendingFolderId > 0L) {
            Graph.chat.setSessionFolder(newId, chatProjectId, pendingFolderId)
            pendingFolderId = 0L
        }
    }

    // 首页大输入框直接开聊：进入即自动发送一次（direct 用 initialInput，避免与预填 effect 竞态）
    LaunchedEffect(Unit) {
        if (autoSend && initialInput.isNotBlank()) {
            // v19.62 严格项目制：**先判项目，再动输入**。这个 effect 下面会把 input /
            // pendingFiles / linkedRefs 全部清空（它们刚从 initialInput 搬过来），
            // 守卫放晚了用户的字就丢了——拦在这里，字还留在输入框里，选完项目还能重发。
            if (currentId == 0L && chatProjectId <= 0L) { showPickProject = true; return@LaunchedEffect }
            val marks = pendingFiles.joinToString(" ") { "[附件已附: " + it.relPath + " 请用 " + it.tool + " 读取]" }
            val text = if (marks.isEmpty()) initialInput else (initialInput.trim() + " " + marks)
            input = ""
            pendingFiles = emptyList()
            val linked = linkedRefs
            linkedRefs = emptyList()
            val isNew = currentId == 0L
            val newId = ChatEngine.send(
                currentId, text, assistantDraft, chatProjectId,
                modelOverride = if (isNew) chatModelName else "", linked = linked
            )
            currentId = newId
            if (isNew && newId > 0L) {
                // 文字已成会话首条消息，项目桶必须清掉——否则该项目的下一个新对话
                // 会把发出去的内容当草稿自动填回来（按项目分桶后的配套收尾）
                ChatDrafts.set(ChatDrafts.key(0L, chatProjectId), "")
                applyNewSessionDrafts(newId)
            }
        }
    }
    // 离开页面时保存草稿（桶键读当下值：currentId 已切到新会话时落新桶，与原行为一致）
    DisposableEffect(currentId) {
        onDispose { ChatDrafts.set(ChatDrafts.key(currentId, chatProjectId), input) }
    }
    // M5 分享直达：新会话时预填分享文本（autoSend 直发路径不做预填）
    LaunchedEffect(Unit) {
        if (sessionId == null && currentId == 0L && initialInput.isNotBlank() && input.isEmpty() && !autoSend) {
            input = initialInput
        }
    }
    // 生成中保持屏幕常亮
    val view = androidx.compose.ui.platform.LocalView.current
    DisposableEffect(state.streaming) {
        view.keepScreenOn = state.streaming
        onDispose { view.keepScreenOn = false }
    }
    // 错误通知 8s 自动消失
    LaunchedEffect(state.notice, currentId) {
        if (state.notice != null) {
            delay(8000)
            ChatEngine.clearNotice(currentId)
        }
    }
    // 空态建议 chips 按助手定制（图3 工作台空态）
    var assistantKey by remember { mutableStateOf("general") }
    LaunchedEffect(assistantDraft) {
        if (assistantDraft > 0L) {
            Graph.assistants.byId(assistantDraft)?.let { assistantKey = it.key } ?: run { assistantKey = "general" }
        } else assistantKey = "general"
    }
    // 2026-11-11 用户：私人助理界面锁死「私人助理」智能体——
    // ①中枢功能入口收进标题栏底部胶囊行（三定：从标题右侧大胶囊撤下，占智能体选择位）；
    // ②不显示更换智能体胶囊（发送设置里的智能体行一并收走，切智能体的两个入口全关）。
    val isHubAssistant = assistantKey == io.github.m0use001.planability.data.AssistantRepo.KEY_HUB
    // 2026-11-22 发送接管跟随的信号（sendCard/sendCurrent 各自 ++；MessageList 收到即翻回跟随）
    val chatFollowTick = remember { androidx.compose.runtime.mutableIntStateOf(0) }
    // 内嵌组件动作（问答/日程/清单）→ 以构造消息发送，复用既有确认流
    val sendCard: (String) -> Unit = { text ->
        // v19.62：val lambda 上用 return@sendCard 编译器不认（只认函数名标签），改用 if/else
        if (currentId == 0L && chatProjectId <= 0L) {
            showPickProject = true
        } else scope.launch {
            // 2026-11-22 与 sendCurrent 同步：发送 = 要看最新，接管跟随；同样放挂起的
            // send **之前**（迟到接管=视口钉在旧阅读位置十几秒）。
            chatFollowTick.intValue++
            // 2026-11-22：发送即放弃「阅读位置恢复」——否则冷启动首次进会话的恢复可能与
            // 发送接管相撞（先蹦到旧锚点、再跳底）。
            chatReadingRestorePending = false
            val isNew = currentId == 0L
            currentId = ChatEngine.send(currentId, text, assistantDraft, chatProjectId)
            if (isNew && currentId > 0L) ChatDrafts.set(ChatDrafts.key(0L, chatProjectId), "")
            // 2026-11-01：发送后不再主动滚——2026-11-05 八修正序化后，新 user 气泡靠
            // MessageList 的跟随 effect（内容增长 → scrollToItem(末尾, MAX)）自然贴底；
            // 这里若再滚一次会与那条 effect 抢，且会把刚关掉的 autoFollow 又翻回来。
        }
    }
    // 代码块「导出」→ 直接写入 exports/（本地操作，不经 AI）
    val exportCode: (String, String) -> Unit = { lang, code ->
        val ext = when (lang.lowercase()) {
            "kotlin" -> "kt"
            "python" -> "py"
            "javascript", "typescript" -> "js"
            "java" -> "java"
            "json" -> "json"
            "xml" -> "xml"
            "markdown", "md" -> "md"
            else -> if (lang.isNotBlank() && lang.length <= 4) lang.lowercase() else "txt"
        }
        // 与 AI 生成的文件同一规则：写进**当前对话**的目录（见 ExportStore.dirFor）。
        // 这是普通 lambda 不是 suspend，所以必须 launch——dirFor 要读库才知道对话在哪个项目。
        scope.launch {
            runCatching {
                val dir = io.github.m0use001.planability.ai.office.ExportStore
                    .dirFor(Graph.appContext, currentId)
                val f = java.io.File(dir, "chat_" + System.currentTimeMillis() + "." + ext)
                f.writeText(code)
                // 不能说"已导出 exports/"：落点已跟着对话走，路径里没有 exports 这一级
                Toast.makeText(appContext, "已保存：" + f.name, Toast.LENGTH_SHORT).show()
            }.onFailure {
                Toast.makeText(appContext, "导出失败：" + it.message, Toast.LENGTH_SHORT).show()
            }
        }
    }
    // 文件卡「打开」→ 应用内预览（限 attachments/ exports/ 相对路径，防穿越）
    var openFilePath by remember { mutableStateOf<String?>(null) }
    // exports/ 是虚拟前缀 = 当前对话的文件目录（见 ExportStore.dirFor）。
    // 存**绝对路径**：后续预览不必再拼一次——原来每处都自己拼 filesDir，
    // attachments 拼对了、exports 拼错了，所以点导出的文件永远弹"文件不存在"。
    val openFilePreview: (String) -> Unit = { p ->
        scope.launch {
            val supported = !p.contains("..") &&
                (p.startsWith("attachments/") || p.startsWith("exports/"))
            val f = when {
                p.contains("..") -> null
                p.startsWith("attachments/") -> java.io.File(Graph.appContext.filesDir, p)
                // exports/ 是虚拟前缀：可能落在当前会话目录、旧扁平目录或 filesDir，
                // 由 ExportStore 按"越新越优先"找到真实存在的那一个；
                // 精确路径找不到时它还会按**同名**在项目文件目录里兜底搜一遍
                // （AI 常把文件写进「项目文件/分类/」，卡片却写 exports/xxx.md）
                p.startsWith("exports/") -> io.github.m0use001.planability.ai.office.ExportStore
                    .resolveExisting(Graph.appContext, currentId, p)
                else -> null
            }
            if (f != null && f.exists() && f.isFile) openFilePath = f.absolutePath
            else {
                android.util.Log.w("PlanabilityFile", "打开失败：p=$p session=$currentId")
                // 2026-11-17 用户「怎么打开不了」：两种失败要分开说，
                // 不然用户以为是 App 坏了——路径不支持=卡片路径写法不对，
                // 文件不存在=AI 根本没把这个文件生成出来（改了内容但没落盘）。
                Toast.makeText(
                    appContext,
                    if (supported) "文件不存在：AI 可能只改了内容，没有真的导出这个文件"
                    else "这个路径打不开（只支持 attachments/ 与 exports/）",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    // 2026-10-31：nearBottom 只服务于已删除的「回到底部」FAB（重复按钮），一并移除。
    // 「是否在底部」的唯一判定留在 MessageList 的 atBottom（!canScrollForward）。

    // v57 平板双栏：页面本体抽成 chatBody 供两个宿主复用——
    // 手机 = 自包 PredictiveBackOverlay（独立 Dialog 窗口，原路径零变化）；
    // 平板 = 父页的双栏 Row 直接承载（父页自备 overlay），本页只渲染内容层。
    // 2026-11-20 v65 两板推广：顶栏的「胶囊副标题行」与「⋮ 动作区」抽成局部 lambda——
    // 宽屏（embedded）的 BoardHeader 与窄屏的 M3ETitleBar **共用同一份内容**，
    // 两种壳只是摆位不同（内容从 M3ETitleBar 调用里原样搬出，一字未改）。
    val chatCapsuleRow: @Composable () -> Unit = {
                        // 2026-11-08 用户「应该用满一行」→ 2026-11-11 用户改「靠紧一点」：
                        // 撤 SpaceBetween+fillMaxWidth，改固定 6dp 间距靠左成组
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            // v53.5 工作流胶囊改**常驻**。v53 的门槛是「AI 消息里含 ≥2 个 - [ ] 勾选清单」
                            // 才显示——真机全库 1900+ 条 assistant 消息里这种清单出现 0 次（AI 排计划
                            // 用正文/工具，从不写 Markdown checkbox），用户从没见过这颗胶囊，等于没做。
                            // 面板一半是「会话速览」（智能体/模型/思考/权限/上下文），不依赖计划，入口不该有条件：
                            // 有计划时显示「计划 m/n」推进度，没有时退化为「工作流」字样入口。
                            val planNow = remember(state.messages) { latestPlan(state.messages) }
                            Text(
                                planNow?.let { "计划 ${it.doneCount}/${it.totalCount}" } ?: "工作流",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = if (planNow != null && planNow.doneCount == planNow.totalCount)
                                    MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(percent = 50))
                                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                                    .clickable { showWorkflow = true }
                                    .padding(horizontal = 8.dp, vertical = 3.dp)
                            )
                            // 上下文：环 + 百分比
                            val ctxWindow = io.github.m0use001.planability.data.contextWindowOf(chatModelName)
                            val ctxFrac = if (ctxWindow > 0) (state.ctxTokens.toFloat() / ctxWindow).coerceIn(0f, 1f) else 0f
                            val ringFg = when {
                                ctxFrac > 0.85f -> MaterialTheme.colorScheme.error
                                ctxFrac > 0.6f -> MaterialTheme.colorScheme.tertiary
                                else -> MaterialTheme.colorScheme.primary
                            }
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(percent = 50))
                                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                                    .clickable { showContext = true }
                                    .padding(horizontal = 8.dp, vertical = 3.dp)
                            ) {
                                androidx.compose.foundation.Canvas(Modifier.size(11.dp)) {
                                    drawArc(
                                        color = androidx.compose.ui.graphics.Color.Gray.copy(alpha = 0.42f),
                                        startAngle = -90f, sweepAngle = 360f, useCenter = false,
                                        style = androidx.compose.ui.graphics.drawscope.Stroke(
                                            width = 8f,
                                            cap = androidx.compose.ui.graphics.StrokeCap.Round
                                        )
                                    )
                                    if (ctxFrac > 0.01f) {
                                        drawArc(
                                            color = ringFg,
                                            startAngle = -90f, sweepAngle = 360f * ctxFrac, useCenter = false,
                                            style = androidx.compose.ui.graphics.drawscope.Stroke(
                                                width = 8f,
                                                cap = androidx.compose.ui.graphics.StrokeCap.Round
                                            )
                                        )
                                    }
                                }
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    "${(ctxFrac * 100).toInt()}%",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            // 权限：只显示所选档位
                            Text(
                                PermModes.ALL.firstOrNull { it.key == currentPermMode }?.label ?: "—",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(percent = 50))
                                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                                    .clickable { showPermSheet = true }
                                    .padding(horizontal = 8.dp, vertical = 3.dp)
                            )
                            // 2026-11-01 用户：智能体选择移到顶部、权限胶囊右边
                            // 2026-11-11 用户三定：中枢功能从标题右侧撤下、落进底部胶囊行——
                            // 同款小胶囊，占智能体选择的位置（分布参考普通会话，只是不加智能体选择）；
                            // 私人助理界面锁定私人助理，不显示更换智能体胶囊
                            if (isHubAssistant && onOpenHub != null) Text(
                                "中枢功能",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(percent = 50))
                                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                                    .clickable { haptics.perform(HapticEngine.Kind.Tap); onOpenHub() }
                                    .padding(horizontal = 8.dp, vertical = 3.dp)
                            ) else if (!isHubAssistant) Text(
                                assistantPlainName.ifBlank { "智能体" },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(percent = 50))
                                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                                    .clickable { showAssistantPick = true }
                                    .padding(horizontal = 8.dp, vertical = 3.dp)
                            )
                            // 2026-11-02 自动书签：入口从右缘把手移进顶栏（用户定案）；
                            // 展开中图标实心+主色（选中态），点按切换右侧面板。
                            // 2026-11-08 用户「‘书签’2 字要加上」：裸图标钮改成与其它胶囊同款的
                            // 文字胶囊——一行里只有它不是胶囊，读起来不知道那是什么。
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(percent = 50))
                                    .background(
                                        if (bookmarksExpanded.value) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
                                        else MaterialTheme.colorScheme.surfaceContainerHigh
                                    )
                                    .clickable {
                                        haptics.perform(HapticEngine.Kind.Tap)
                                        bookmarksExpanded.value = !bookmarksExpanded.value
                                    }
                                    .padding(start = 8.dp, end = 8.dp, top = 3.dp, bottom = 3.dp)
                            ) {
                                Icon(
                                    if (bookmarksExpanded.value) Icons.Rounded.Bookmark else Icons.Rounded.BookmarkBorder,
                                    contentDescription = "书签",
                                    tint = if (bookmarksExpanded.value) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(13.dp)
                                )
                                Spacer(Modifier.width(3.dp))
                                Text(
                                    "书签",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (bookmarksExpanded.value) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
    }
    val chatTopActions: @Composable () -> Unit = {
                        // v19.8：齿轮 = 原 ⋮ 的会话功能菜单；⋮ = 运行面板（流程推进/智能体）
                        // 本轮：原 ⋮ 的运行面板入口并进齿轮的二级菜单 —— 顶部少一个按钮，
                        // 同时和抽屉那边「入口并进来」的思路保持一致。
                        if (currentId > 0L) {
                            Box {
                                // 2026-10-31 顶部预算：齿轮（设置语义）→ ⋮（M3 里 overflow 固定三点）。
                                // 菜单内容不变；只是不再用"设置"图标承担"更多"的语义。
                                IconButton(onClick = { showMoreMenu = true }) {
                                    Icon(Icons.Rounded.MoreVert, "更多", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                androidx.compose.material3.DropdownMenu(
                                    expanded = showMoreMenu,
                                    onDismissRequest = { showMoreMenu = false }
                                ) {
                                    // 2026-11-01：助手/模型/思考/权限/上下文全部收进这张面板后，
                                    // 它必须有个入口——放在 ⋮ 菜单第一项（原来由输入区的模型药丸进入）。
                                    androidx.compose.material3.DropdownMenuItem(
                                        modifier = Modifier.clip(RoundedCornerShape(10.dp)), // 2026-11-15 点击高亮圆角化（原来是整行直角方块）
                                        text = { Text("发送设置") },
                                        leadingIcon = { Icon(Icons.Rounded.Tune, null, Modifier.size(17.dp)) },
                                        onClick = { showMoreMenu = false; showChatSetup = true }
                                    )
                                    // 运行面板入口从顶部 ⋮ 挪到这一级菜单的第一项
                                    // 2026-11-11：私人助理界面的中枢功能已收进底部胶囊行，
                                    // 这里的菜单项只留给非私人助理会话
                                    if (onOpenHub != null && !isHubAssistant) {
                                        androidx.compose.material3.DropdownMenuItem(
                                            modifier = Modifier.clip(RoundedCornerShape(10.dp)), // 2026-11-15 点击高亮圆角化（原来是整行直角方块）
                                            text = { Text("中枢功能") },
                                            leadingIcon = { Icon(Icons.Rounded.Inbox, null, Modifier.size(17.dp)) },
                                            onClick = { showMoreMenu = false; onOpenHub() }
                                        )
                                    }
                                    androidx.compose.material3.DropdownMenuItem(
                                        modifier = Modifier.clip(RoundedCornerShape(10.dp)), // 2026-11-15 点击高亮圆角化（原来是整行直角方块）
                                        text = { Text("运行面板") },
                                        leadingIcon = { Icon(Icons.Rounded.MoreVert, null, Modifier.size(17.dp)) },
                                        onClick = { showMoreMenu = false; showRunPanel = true }
                                    )
                                    androidx.compose.material3.DropdownMenuItem(
                                        modifier = Modifier.clip(RoundedCornerShape(10.dp)), // 2026-11-15 点击高亮圆角化（原来是整行直角方块）
                                        text = { Text("移动到项目") },
                                        leadingIcon = { Icon(Icons.Rounded.FolderCopy, null, Modifier.size(17.dp)) },
                                        onClick = { showMoreMenu = false; showProjectMove = true }
                                    )
                                    androidx.compose.material3.DropdownMenuItem(
                                        modifier = Modifier.clip(RoundedCornerShape(10.dp)), // 2026-11-15 点击高亮圆角化（原来是整行直角方块）
                                        text = { Text("上下文") },
                                        leadingIcon = { Icon(Icons.Rounded.Tune, null, Modifier.size(17.dp)) },
                                        onClick = { showMoreMenu = false; showContext = true }
                                    )
                                    if (currentId > 0L) {
                                        androidx.compose.material3.DropdownMenuItem(
                                            modifier = Modifier.clip(RoundedCornerShape(10.dp)), // 2026-11-15 点击高亮圆角化（原来是整行直角方块）
                                            text = { Text("作者注") },
                                            leadingIcon = { Icon(Icons.Rounded.Create, null, Modifier.size(17.dp)) },
                                            onClick = { showMoreMenu = false; showAuthorNote = true }
                                        )
                                    }
                                    androidx.compose.material3.DropdownMenuItem(
                                        modifier = Modifier.clip(RoundedCornerShape(10.dp)), // 2026-11-15 点击高亮圆角化（原来是整行直角方块）
                                        text = { Text("调用轨迹") },
                                        leadingIcon = { Icon(Icons.Rounded.BarChart, null, Modifier.size(17.dp)) },
                                        onClick = { showMoreMenu = false; showTrace = true }
                                    )
                                    androidx.compose.material3.DropdownMenuItem(
                                        modifier = Modifier.clip(RoundedCornerShape(10.dp)), // 2026-11-15 点击高亮圆角化（原来是整行直角方块）
                                        text = { Text(if (pinned) "取消置顶" else "置顶") },
                                        leadingIcon = { Icon(Icons.Rounded.PushPin, null, Modifier.size(17.dp)) },
                                        onClick = {
                                            showMoreMenu = false
                                            val to = !pinned
                                            pinned = to
                                            scope.launch { Graph.chat.setPinned(currentId, to) }
                                        }
                                    )
                                    androidx.compose.material3.DropdownMenuItem(
                                        modifier = Modifier.clip(RoundedCornerShape(10.dp)), // 2026-11-15 点击高亮圆角化（原来是整行直角方块）
                                        text = { Text("删除", color = MaterialTheme.colorScheme.error) },
                                        leadingIcon = { Icon(Icons.Rounded.Delete, null, Modifier.size(17.dp), tint = MaterialTheme.colorScheme.error) },
                                        onClick = { showMoreMenu = false; confirmDelete = true }
                                    )
                                }
                            }
                        }
    }

    val chatBody: @Composable () -> Unit = {
        androidx.compose.foundation.layout.Box(
            Modifier
                .fillMaxSize()
                .swipeRightToOpen(enabled = onSwipeOpenDrawer != null && !embedded) { onSwipeOpenDrawer?.invoke() }
        ) {
            Scaffold(
            // 2026-11-01 用户「底部还是被横杠截断，一个黑色条」：
            // Scaffold 默认 contentWindowInsets = systemBars，会把内容底边压在导航条上沿——
            // 输入面板再怎么铺也铺不到屏幕底，手势条那条露出窗口底色（黑条）。
            // 置零后由各部件自己处理：顶栏自带 statusBar 高度、输入面板内部补 nav 高度。
            contentWindowInsets = WindowInsets(0),
            // 2026-11-22 用户「AI 会话界面框不完整」根修：宽屏 embedded 时本页长在壳层
            // SplitBoards(transparent=true) 的左板瓦片里，板底（surfaceContainerLow 相框）
            // 由壳层绘制——Scaffold 默认 containerColor=background 会把左板整个盖成窗口底色，
            // 左板看起来没有框（右板工作台树没被盖、框完好，实拍左右不对称实锤）。
            // 嵌入态改透明让壳层板底透出来；窄屏全屏路径保持原底色不变。
            containerColor = if (embedded) androidx.compose.ui.graphics.Color.Transparent
            else MaterialTheme.colorScheme.background,
            topBar = {
                // 2026-11-01 用户：顶栏改成「计划页那种圆角标题栏」——复用 M3ETitleBar 固定态
                // （collapsedFraction=1、expandable=false，与计划/笔记一致：圆角浮层常驻、无下拉放大）。
                // 原来 TopAppBar 里的副标题（助手·模型·项目）撤掉：那些属于会话配置，
                // 已收进 ⋮ → 发送设置，顶栏只留「会话名 + ⋮」。
                //
                // 2026-11-20 v65 两板推广：宽屏（embedded）改走 **BoardHeader**（板头长在板面上），
                // 不再往板里嵌浮起的 M3ETitleBar（圆角 + 投影 = 板内的第二种材质）；
                // 窄屏路径一字不动。胶囊副标题 / 动作区 / 返回键与窄屏同一份
                // （chatCapsuleRow / chatTopActions，定义在 chatBody 之前）。
                if (embedded) {
                    io.github.m0use001.planability.ui.common.BoardHeader(
                        title = sessionTitle,
                        subtitleContent = chatCapsuleRow,
                        leading = {
                            // 返回 = 中性轻触（Tap），见 core/Haptics.kt 的 HIG 映射表
                            IconButton(onClick = { haptics.perform(HapticEngine.Kind.Tap); onDone() }) {
                                Icon(Icons.AutoMirrored.Rounded.ArrowBack, "返回")
                            }
                        },
                        actions = chatTopActions,
                        // 2026-11-21 信息页两板改由壳（SplitBoards 默认 inset）统一垫状态栏，
                        // 与首页/计划页板头同一套上下几何——这里不再自己垫（否则双倍）。
                    )
                } else
                io.github.m0use001.planability.ui.common.M3ETitleBar(
                    title = sessionTitle,
                    subtitleContent = chatCapsuleRow,
                    collapsedFraction = 1f,
                    expandable = false,
                    navigationIcon = {
                        // 返回 = 中性轻触（Tap），见 core/Haptics.kt 的 HIG 映射表
                        IconButton(onClick = { haptics.perform(HapticEngine.Kind.Tap); onDone() }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "返回") }
                    },
                    actions = chatTopActions
                )
            }
        ) { pad ->
            // M3ETitleBar 自己把状态栏高度算进栏高（见 M3ETitleBar 的 statusBarH），
            // Scaffold 给的 pad 已经包含它——这里再 statusBarsPadding() 会多出一条空白。
            Column(Modifier.fillMaxSize().padding(pad)) {
                val cfgOk = AiClient.configured
                if (!cfgOk) {
                    Text(
                        "尚未启用模型：先到 我的 → 设置 → 模型管理 完成接入。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
                    )
                }
                Box(Modifier.weight(1f)) {
                    MessageList(
                        state = state,
                        sessionId = currentId,
                        listState = listState,
                        bookmarksExpanded = bookmarksExpanded.value,
                        followTick = chatFollowTick.intValue,
                        // v47：手动压缩区间（压缩/取消后 compactVersion++ 触发重载）
                        compactMarkers = compactMarkers,
                        // v47.1：本会话正在跑的多智能体协作（加载动画+第 k/n 步+实时秒数）
                        runningAgent = subAgentsRunning,
                        // v50：摘要卡「查看原文」→ 顶层挂独立查看页
                        onOpenCompactOriginal = { compactOriginalFor = it },
                        onBookmarksExpanded = { bookmarksExpanded.value = it },
                        // 长按消息 = 长按手势，按 Haptics.kt 映射表应为 ImpactLight（原来用 Select 偏轻，
                        // 手感更像"点中了一个选项"而不是"压住了这条消息"）
                        onLongPress = { haptics.perform(HapticEngine.Kind.ImpactLight); actionMsg = it },
                        onSuggestion = { input = it },
                        assistantKey = assistantKey,
                        onSendCard = sendCard,
                        onExportCode = exportCode,
                        onOpenFile = openFilePreview
                    )
                    // 2026-11-17 用户（截图 2）：「标题栏下面有个意义不明的渐变特效」——
                    // 这里原有 18dp 的 `surfaceContainer → Transparent` 竖直渐变，本意是让
                    // 滑到列表上缘的消息「淡出」而不是被硬切；但它的起始色取的是**顶栏折叠态
                    // 底色**，与聊天页自己的背景不是一个色，视觉上就是凭空一条浅色带。
                    // 用户判定无意义 → 撤掉。顶栏自身底色足以接住内容。
                    // 2026-10-31 用户「会出现两个滑到最底按钮」：这里原来是右下角的
                    // 「回到底部」小 FAB（!nearBottom 时显示），而 MessageList 里还挂着
                    // 「↓ 最新」胶囊（!atBottom 时显示）。两处判定条件不同：往上翻时
                    // nearBottom=false 且 atBottom=false，两个**同时出现**。
                    // 保留「最新」胶囊（它贴着输入框、有完整动效说明与统一观感），
                    // 删掉这枚 FAB 及其 nearBottom 状态——一个动作一个入口。
                }
                ErrorNoticeBar(state, currentId)
                // v19.10 曾在这加一条「正在生成 · 点此停止」的整宽红条（当时发送键落在右缘死区）。
                // 用户要求取消：它挂在文字流末尾比输入区还显眼，白占一整行。
                // 停止能力没有丢 —— InputBar 在 streaming 分支下发送键本身就是停止键
                // （见 InputBar 的 `streaming -> onStop(...)`），这条只是冗余的第二个入口。
                // 2026-11-01 用户「输入框与下层文字间隔着一个黑条」：这条 20dp 进度槽
                // 原来在面板**外面**且背景透明，露出的正是窗口底色。已挪进输入面板内部
                // （InputBar 的 progressText 槽位），这里不再占位，列表直接接到面板上沿。
                approval?.takeIf { it.sessionId == currentId }?.let { req ->
                    ApprovalCard(req)
                }
                // 2026-11-01 用户：助手 / 模型 / 权限 / 思考程度 / 上下文**不再做成悬浮胶囊**，
                // 全部收进顶栏 ⋮ → 「发送设置」面板（原来这行 3-4 枚药丸横排，视觉最碎）。
                // 输入区上方现在是干净的：没有信息行。
                // v8 已勾连 chips（可移除）
                if (linkedRefs.isNotEmpty()) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(start = 16.dp, top = 2.dp)
                    ) {
                        linkedRefs.forEach { ref ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(MaterialTheme.colorScheme.secondaryContainer)
                                    .clickable { linkedRefs = linkedRefs.filterNot { it === ref } }
                                    .padding(horizontal = 6.dp, vertical = 3.dp)
                            ) {
                                Text(
                                    "@" + ref.label, style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis
                                )
                                Spacer(Modifier.width(3.dp))
                                Icon(Icons.Rounded.Close, "移除", Modifier.size(11.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer)
                            }
                            Spacer(Modifier.width(6.dp))
                        }
                    }
                }
                // 2026-11-01 用户「加的附件、图片也要被输入框包住」：
                // 预览带从面板外挪进 InputBar 内部（见 InputBar 里的 PendingAttachmentStrip），
                // 这里不再单独渲染。
                // v8 / 指令提示（输入以 / 开头时）
                if (input.trimStart().startsWith("/")) {
                    SlashMenu(
                        typed = input.trimStart(),
                        onExecute = { cmd ->
                            input = ""
                            when (cmd) {
                                "/model" -> showModelPicker = true
                                "/compact" -> Graph.appScope.launch {
                                    if (currentId > 0L) {
                                        // M4：只有**真的压成功了**才提示。原来无条件弹"已压缩"，
                                        // 用户会以为历史已经裁过（实际可能一条都没压）
                                        val ok = ChatEngine.maybeScheduleSummary(currentId, force = true)
                                        if (ok) {
                                            ChatEngine.insertSystemNote(currentId, "已压缩当前上下文：较早对话已并入摘要")
                                        } else {
                                            // v53：把**真实原因**带出来（没有可压内容 / 流式中 / 模型调用失败），
                                            // 原来只有一句「要么刚压过，要么正在生成中」——用户分不清是哪种。
                                            val why = ChatEngine.lastCompactBlockedReason
                                            ChatEngine.insertSystemNote(
                                                currentId,
                                                if (why != null) "压缩没执行：$why" else "压缩没执行（原因未知）"
                                            )
                                        }
                                    }
                                }
                                "/clear" -> scope.launch {
                                    if (currentId > 0L && ChatEngine.clearCursor(currentId)) {
                                        ChatEngine.insertSystemNote(currentId, "已清除此前上下文")
                                    }
                                }
                                "/memory", "/skill" -> {
                                    if (currentId > 0L) {
                                        val lastA = state.messages.lastOrNull { it.role == "assistant" && it.status == "done" }?.content ?: ""
                                        val lastU = state.messages.lastOrNull { it.role == "user" }?.content ?: ""
                                        showMemoryQuick = cmd
                                        slashBody = (lastU.take(120) + "\n" + lastA).trim()
                                    }
                                }
                                // ---- v19.15 扩充命令 ----
                                "/phone" -> {
                                    // 勾连手机插件：本轮强制启用 phone 域（与 @ 菜单「插件」同一机制）
                                    linkedRefs = linkedRefs + io.github.m0use001.planability.ai.LinkedRef("plugin", 0L, "phone")
                                    input = "用手机插件帮我"
                                }
                                "/perm" -> showPermMenu = true
                                "/plugins" -> input = "列出当前启用的插件和工具域，并告诉我怎么切换"
                                "/usage" -> input = "统计一下本轮的 token 用量"
                                "/today" -> input = "汇总今天的日程与待办"
                                "/recall" -> input = "在记忆库里检索："
                                "/new" -> {
                                    // currentId=0 即「未发送」态，和新开会话同一条路径
                                    currentId = 0L
                                    input = ""
                                }
                                "/automation" -> io.github.m0use001.planability.app.Router.openAutomations()
                                "/undo" -> scope.launch {
                                    if (currentId > 0L) {
                                        val d = io.github.m0use001.planability.ai.ptl.ToolExecutor.undoLast(currentId)
                                        ChatEngine.insertSystemNote(currentId, d ?: "没有可撤销的改动")
                                    }
                                }
                                "/export" -> scope.launch {
                                    val f = io.github.m0use001.planability.data.PhoneDump.write(appContext)
                                    ChatEngine.insertSystemNote(
                                        currentId,
                                        if (f != null) "已导出到 ${f.parent}" else "导出失败（目录不可写）"
                                    )
                                }
                            }
                        }
                    )
                }
                // v10：发送逻辑提升（输入栏与全屏编辑共用）
                val sendCurrent: () -> Unit = {
                    // v19.62 严格项目制：新会话没选项目就拦住（放在最前面，
                    // 否则下面的 input = "" 会先把用户的输入擦掉）。
                    // 不用 return@sendCurrent（val lambda 不认这个标签），整块包进 else。
                    if (currentId == 0L && chatProjectId <= 0L) {
                        showPickProject = true
                    } else {
                    val marks = pendingFiles.joinToString(" ") { "[附件已附: " + it.relPath + " 请用 " + it.tool + " 读取]" }
                    val text = if (marks.isEmpty()) input else (input.trim() + (if (input.isBlank()) "" else " ") + marks)
                    val linked = linkedRefs
                    input = ""
                    pendingFiles = emptyList()
                    linkedRefs = emptyList()
                    // 2026-11-22 用户「发送消息后屏幕飞到之前的消息」配套修复：发送 =
                    // 用户明确要看最新回复，必须接管跟随（重新贴底）。否则冷启动恢复过
                    // 阅读位置（autoFollow=false）的会话里发消息，视口留在旧位置、回复
                    // 在屏幕外长、「最新」胶囊常驻。tick++ 放 ChatEngine.send **之前**——
                    // send 是挂起调用，放后面接管会迟到（实测迟到 ~10s，期间视口钉在
                    // 旧阅读位置）。发送后不主动 scrollToItem（理由见 sendCard 同款注释）：
                    // 翻回 true 后由流式钳底把视口带下去，不与跟随 effect 抢方向盘。
                    chatFollowTick.intValue++
                    // 2026-11-22：发送即放弃「阅读位置恢复」（同 sendCard，防蹦顶再跳底）。
                    chatReadingRestorePending = false
                    scope.launch {
                        val isNew = currentId == 0L
                        val newId = ChatEngine.send(
                            currentId, text, assistantDraft, chatProjectId,
                            modelOverride = if (isNew) chatModelName else "", linked = linked
                        )
                        currentId = newId
                        if (isNew && newId > 0L) {
                            // 同 autoSend 路径：项目桶清掉，防「下一个新对话自动填回已发送的内容」
                            ChatDrafts.set(ChatDrafts.key(0L, chatProjectId), "")
                            applyNewSessionDrafts(newId)
                        }
                    }
                    }
                }
                InputBar(
                    value = input,
                    onValueChange = { v ->
                        input = v
                        // v8 @勾连：输入以 @ 结尾时弹选择器（光标在末尾的简化判定）
                        if (v.endsWith("@")) showLinkPicker = true
                    },
                    streaming = state.streaming,
                    onSend = sendCurrent,
                    onStop = { ChatEngine.stop(currentId) },
                    onImage = {
                        haptics.perform(HapticEngine.Kind.Select)
                        pickImage.launch(androidx.activity.result.PickVisualMediaRequest(
                            androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageOnly
                        ))
                    },
                    onAttach = {
                        haptics.perform(HapticEngine.Kind.Select)
                        pickFile.launch(arrayOf("*/*"))
                    },
                    onExpand = { showExpand = true },
                    // 模型 + 思考合并：点开同一张面板（模型列表 + 思考强度拖动条）
                    thinking = thinking,
                    modelName = chatModelName.ifBlank { "未配置模型" },
                    onOpenModelThinking = { showModelPicker = true },
                    pendingFiles = pendingFiles,
                    onOpenAttachment = openFilePreview,
                    onRemoveAttachment = { pf -> pendingFiles = pendingFiles.filterNot { it === pf } },
                    // v53：压缩中指示——复用输入区进度文字槽位（流式优先；两者不同时发生）
                    progressText = when {
                        state.streaming -> dpProgress
                        state.compacting -> "正在压缩上下文…"
                        else -> null
                    },
                )

                // v10：全屏编辑（微信式扩大输入）
                if (showExpand) {
                    val focusRequester = androidx.compose.ui.focus.FocusRequester()
                    // fullscreen=true：全屏文本编辑要整屏
                    PredictiveBackOverlay(onBack = { showExpand = false }, fullscreen = true) {
                        Scaffold(
                            topBar = {
                                TopAppBar(
                                    title = { Text("输入消息", fontWeight = FontWeight.SemiBold) },
                                    navigationIcon = {
                                        IconButton(onClick = { showExpand = false }) {
                                            Icon(Icons.AutoMirrored.Rounded.ArrowBack, "返回")
                                        }
                                    },
                                    actions = {
                                        TextButton(
                                            onClick = { showExpand = false; sendCurrent() },
                                            enabled = input.isNotBlank() || pendingFiles.isNotEmpty()
                                        ) { Text("发送", fontWeight = FontWeight.SemiBold) }
                                    }
                                )
                            }
                        ) { pad ->
                                OutlinedTextField(
                                    value = input,
                                    onValueChange = { input = it },
                                    modifier = Modifier
                                        .fillMaxSize()
                                        // 2026-11-02 键盘筛查：全屏展开编辑器原无 ime 避让，
                                        // 键盘盖住下半屏文本且无法滚回（与笔记编辑器同坑）
                                        .imePadding()
                                        .padding(pad)
                                        .padding(horizontal = 14.dp, vertical = 8.dp)
                                        .focusRequester(focusRequester),
                                    placeholder = { Text("一次性输入大量文字…") }
                                )
                            androidx.compose.runtime.LaunchedEffect(Unit) {
                                runCatching { focusRequester.requestFocus() }
                            }
                        }
                    }
                }
            }
            }
        }
    }

    // v8 弹层：@勾连 / 会话权限 / 快捷记忆
    if (showLinkPicker) {
        LinkPickSheet(
            onDismiss = { showLinkPicker = false },
            onPick = { ref, token ->
                showLinkPicker = false
                if (linkedRefs.none { it.kind == ref.kind && it.id == ref.id }) linkedRefs = linkedRefs + ref
                // 替换输入框末尾的 @ 为完整 token
                if (input.endsWith("@")) input = input.dropLast(1) + token + " "
                else input = input + " " + token + " "
            }
        )
    }
    // 十三期：权限入口已改为 chips 行内的档位下拉（PermModeMenu），SessionPermSheet 不再挂载
    showMemoryQuick?.let { t ->
        MemoryQuickDialog(
            initialType = if (t == "/skill") "skill" else "feedback",
            suggestedBody = slashBody,
            onDismiss = {
                showMemoryQuick = null
                if (currentId > 0L && slashBody.isNotBlank()) {
                    scope.launch { ChatEngine.insertSystemNote(currentId, "已" + (if (t == "/skill") "新建技能" else "写入记忆") + "（见记忆库）") }
                }
                slashBody = ""
            }
        )
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            modifier = Modifier.motionEnter(),
            title = { Text("删除会话") },
            text = { Text("将删除「$sessionTitle」及其全部消息，不可恢复。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    val id = currentId
                    currentId = 0L
                    scope.launch {
                        Graph.chat.deleteSession(id)
                        onDone()
                    }
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("取消") } }
        )
    }

    actionMsg?.let { target ->
        MessageActionSheet(
            msg = target,
            isLastAssistant = state.messages.lastOrNull()?.id == target.id &&
                target.role == "assistant" && !state.streaming,
            onDismiss = { actionMsg = null },
            onCopy = {
                // 2026-11-12：写后读回校验（见 core/ClipboardCompat——焦点策略静默拒绝）
                val err = io.github.m0use001.planability.core.ClipboardCompat.copy(appContext, target.content)
                haptics.perform(if (err == null) HapticEngine.Kind.Success else HapticEngine.Kind.Error)
                Toast.makeText(appContext, err ?: "已复制", Toast.LENGTH_SHORT).show()
            },
            onRetryUser = {
                input = target.content
                scope.launch { ChatEngine.deleteFromId(currentId, target.id) }
            },
            onRegenerate = { scope.launch { ChatEngine.regenerate(currentId) } },
            onDeleteFrom = { scope.launch { ChatEngine.deleteFromId(currentId, target.id) } },
            // 2026-11-04：引用到下一轮。正文已可长按选词（SelectionContainer），
            // 但 Compose 1.12 的选词工具条走平台（朗读/搜索），自定义「引用」挂不进去，
            // 所以引用入口放这里：选中→复制→「引用选中」（只接受本消息内的片段，防串味），
            // 或直接「引用本条」。
            onQuote = { text ->
                val q = text.trim().lines().joinToString("\n") { if (it.isBlank()) ">" else "> $it" }
                if (q.isNotBlank()) {
                    input = if (input.isBlank()) "$q\n\n" else "$q\n\n$input"
                    haptics.perform(HapticEngine.Kind.Select)
                }
            }
        )
    }

    if (showContext && currentId > 0L) {
        ContextPanelSheet(
            sessionId = currentId,
            onDismiss = { showContext = false },
            // v47：面板里的「详细上下文编辑」按钮 → 关面板开编辑屏
            onEditContext = { showContext = false; showContextEditor = true }
        )
    }
    // v53 工作流面板：常看速览 + AI 计划推进（从顶栏「工作流 / 计划 m/n」胶囊进入）
    // v53.5：不再要求 currentId>0——草稿会话（还没落库）里胶囊同样可点，
    // 面板内容（速览 + 计划）全来自内存状态，不需要库里的会话行；否则新会话里点了没反应。
    if (showWorkflow) {
        WorkflowPanelSheet(
            plans = remember(state.messages) { parsePlanChecklists(state.messages) },
            assistantLabel = assistantLabel,
            modelName = chatModelName,
            thinkingLevel = thinking,
            permLabel = PermModes.ALL.firstOrNull { it.key == currentPermMode }?.label ?: "—",
            ctxTokens = state.ctxTokens,
            ctxWindow = io.github.m0use001.planability.data.contextWindowOf(chatModelName),
            onDismiss = { showWorkflow = false }
        )
    }
    // v47：详细上下文编辑（按轮选择性压缩）
    if (showContextEditor && currentId > 0L) {
        ContextEditorScreen(
            sessionId = currentId,
            onDismiss = { showContextEditor = false },
            onCompacted = { compactVersion++ }
        )
    }
    // v55 作者注面板（⋮ 菜单进入；上下文编辑器顶部有同款卡）
    if (showAuthorNote && currentId > 0L) {
        AuthorNoteSheet(sessionId = currentId, onDismiss = { showAuthorNote = false })
    }
    // v50：压缩原文独立查看页（摘要卡「查看原文」进入）
    if (compactOriginalFor > 0L && currentId > 0L) {
        val om = compactMarkers.firstOrNull { it.id == compactOriginalFor }
        if (om != null) {
            CompactOriginalScreen(
                sessionId = currentId,
                marker = om,
                onDismiss = { compactOriginalFor = 0L }
            )
        }
    }
    if (showTrace && currentId > 0L) {
        CallTraceSheet(sessionId = currentId, onDismiss = { showTrace = false })
    }
    // v19.8：运行面板（⋮）
    if (showRunPanel && currentId > 0L) {
        io.github.m0use001.planability.ui.info.RunPanelSheet(
            streaming = state.streaming,
            modelName = chatModelName,
            assistantLabel = assistantLabel,
            plannerProgress = dpProgress,
            audit = runAudit,
            sessionId = currentId,
            onDismiss = { showRunPanel = false },
            onOpenTrace = { showRunPanel = false; showTrace = true },
            onOpenContext = { showRunPanel = false; showContext = true }
        )
    }
    androidx.compose.runtime.LaunchedEffect(currentId, dvVersion, state.streaming) {
        if (currentId > 0L) {
            runAudit = runCatching { io.github.m0use001.planability.data.Graph.db.toolAuditDao().ofSession(currentId) }
                .getOrDefault(emptyList())
        }
    }

    // v19.62：新会话选项目（与首页输入卡、抽屉共用同一个 ProjectPickerSheet）
    if (showPickProject) {
        io.github.m0use001.planability.ui.info.ProjectPickerSheet(
            selectedId = chatProjectId,
            onDismiss = { showPickProject = false },
            onPick = { pid ->
                chatProjectId = pid
                showPickProject = false
                scope.launch { chatProjectName = Graph.projects.byId(pid)?.name ?: "未选择项目" }
            }
        )
    }
    if (showProjectMove && currentId > 0L) {
        io.github.m0use001.planability.ui.info.FolderMoveSheet(
            sessionId = currentId,
            onDismiss = { showProjectMove = false },
            onMoved = { pid ->
                chatProjectId = pid
                scope.launch { chatProjectName = Graph.projects.byId(pid)?.name ?: "未选择项目" }
            }
        )
    }

    if (showModelPicker) {
        ModelPickerSheet(
            currentModel = chatModelName,
            onDismiss = { showModelPicker = false },
            thinking = thinking,
            onSetThinking = { next ->
                thinking = next
                haptics.perform(HapticEngine.Kind.Tap)
                if (currentId > 0L) scope.launch { Graph.chat.setSessionThinking(currentId, next) }
            },
            onPick = { name ->
                chatModelName = name
                if (currentId > 0L) {
                    scope.launch {
                        Graph.chat.setSessionModel(currentId, name)
                        ChatEngine.insertSystemNote(currentId, "已切换模型：$name")
                    }
                }
            }
        )
    }

    // 2026-11-01 权限二级面板：只调权限档，不再连带智能体/模型/上下文
    if (showPermSheet) {
        androidx.compose.material3.ModalBottomSheet(contentWindowInsets = { WindowInsets.navigationBars }, 
            dragHandle = { io.github.m0use001.planability.ui.common.SheetDragHandleOnly() },
            onDismissRequest = { showPermSheet = false },
            sheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ) {
            Column(Modifier.padding(start = 20.dp, end = 20.dp, bottom = 28.dp)) {
                Text("会话权限", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    "决定 AI 在本会话里能做什么；越往下授权越多。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp, bottom = 10.dp)
                )
                PermModes.ALL.forEach { mode ->
                    val on = mode.key == currentPermMode
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .clickable {
                                haptics.perform(HapticEngine.Kind.Select)
                                applyPermMode(mode.key)
                                showPermSheet = false
                            }
                            .padding(horizontal = 6.dp, vertical = 10.dp)
                    ) {
                        Icon(
                            mode.icon, null, Modifier.size(20.dp),
                            tint = if (on) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(mode.label, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                mode.desc,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (on) {
                            Icon(
                                Icons.Rounded.CheckCircle, null, Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }
        }
    }

    // 2026-10-29 输入区「发送设置」弹层：与信息首页同款——智能体/模型/思考一次选完
    if (showChatSetup) {
        androidx.compose.material3.ModalBottomSheet(contentWindowInsets = { WindowInsets.navigationBars }, 
            dragHandle = { io.github.m0use001.planability.ui.common.SheetDragHandleOnly() },
            onDismissRequest = { showChatSetup = false },
            sheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ) {
            // 2026-11-14：加了「联网」一整段后内容超出面板高度。三次试错后的定论
            // （verticalScroll 整列 / weight(1f) / heightIn(max) 在 ModalBottomSheet 的
            // 无界测量下**都不产生可滚节点**，实测 dump 里 scrollable=false、底部被截）：
            // 用 **LazyColumn** 承载全部内容——仓库里「模型与思考」面板的模型列表就是
            // 这个形态，是 ModalBottomSheet 内唯一实测可滚的容器。
            Column(
                Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(start = 20.dp, end = 20.dp, bottom = 28.dp)
            ) {
                Text(
                    "发送设置",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                /* 整列 verticalScroll + **屏幕高度百分比**限高。
                   两个真机/模拟器实测教训：
                   ① Modifier 顺序：verticalScroll 必须在 heightIn 之前（反过来没有可滚余量）；
                   ② 限高必须**明显小于可用空间**——面板只占屏幕下半时，460dp（≈1610px）
                      比可用 825px 还大，于是"没超出"→不出现滚动区（实测 dump scrollable=0）。
                   用 fillsMaxHeight(0.42f) 取屏高比例：设备无关，小屏大屏都是"面板内容区约 4 成屏高"。 */
                Column(
                    Modifier
                        .verticalScroll(rememberScrollState())
                        .fillMaxHeight(0.42f)
                ) {
                Spacer(Modifier.height(14.dp))
                // 智能体（2026-11-11：私人助理界面锁定私人助理——发送设置里也不给换）
                if (!isHubAssistant) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .clickable {
                                showChatSetup = false
                                showAssistantPick = true
                            }
                            .padding(vertical = 12.dp)
                    ) {
                        Icon(Icons.Rounded.SmartToy, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(12.dp))
                        Text("智能体", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                        Text(
                            assistantLabel,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    androidx.compose.material3.HorizontalDivider()
                    Spacer(Modifier.height(4.dp))
                }
                // 模型
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .clickable {
                            showChatSetup = false
                            showModelPicker = true
                        }
                        .padding(vertical = 12.dp)
                ) {
                    Icon(Icons.Rounded.Bolt, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(12.dp))
                    Text("模型", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    Text(
                        chatModelName.ifBlank { "默认模型" },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.height(4.dp))
                androidx.compose.material3.HorizontalDivider()
                Spacer(Modifier.height(4.dp))
                // 思考档：五选一（与首页发送设置同款）
                Text(
                    "思考",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 14.dp, bottom = 8.dp)
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    // v54 思考档五档：关 / 自适应 / low / high / max（DeepSeek 力度档）
                    ThinkingLevels.ALL.forEach { k ->
                        val label = ThinkingLevels.label(k)
                        val on = thinking == k
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(10.dp))
                                .background(
                                    if (on) MaterialTheme.colorScheme.primaryContainer
                                    else MaterialTheme.colorScheme.surfaceContainer
                                )
                                .clickable {
                                    haptics.perform(HapticEngine.Kind.Select)
                                    thinking = k
                                    if (currentId > 0L) scope.launch { Graph.chat.setSessionThinking(currentId, k) }
                                }
                                .padding(vertical = 10.dp),
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Text(
                                label,
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (on) MaterialTheme.colorScheme.onPrimaryContainer
                                else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                // 2026-11-01：权限档与上下文用量也从输入区药丸行搬进来——
                // 「这一轮怎么发」相关的开关集中在同一张面板，输入区上方不再有任何胶囊。
                Spacer(Modifier.height(4.dp))
                androidx.compose.material3.HorizontalDivider()
                Spacer(Modifier.height(4.dp))
                // 2026-11-14 四象限的另一半：**联网**（与上方思考档正交组合——
                // 思考关+联网开 = 直接搜；思考开+联网开 = 云端「深度思考搜索」；
                // 都关 = 纯模型知识；思考开+联网关 = 只推理不搜索）。
                Text(
                    "联网",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 14.dp, bottom = 8.dp)
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    // 三档：跟随全局 / 开 / 关。默认「跟随」= 行为与上云前一致（存量会话不受影响）
                    listOf(
                        "" to "跟随设置",
                        "1" to "开",
                        "0" to "关"
                    ).forEach { (key, label) ->
                        val on = webSearchMode == key
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(10.dp))
                                .background(
                                    if (on) MaterialTheme.colorScheme.primaryContainer
                                    else MaterialTheme.colorScheme.surfaceContainer
                                )
                                .clickable {
                                    haptics.perform(HapticEngine.Kind.Select)
                                    webSearchMode = key
                                    if (currentId > 0L) {
                                        scope.launch { Graph.chat.setSessionWebSearch(currentId, key) }
                                    }
                                }
                                .padding(vertical = 10.dp),
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Text(
                                label,
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (on) MaterialTheme.colorScheme.onPrimaryContainer
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1
                            )
                        }
                    }
                }
                if (webSearchMode == "1") {
                    Text(
                        if (thinking == "off") "开：模型会先搜再答（会引用来源，按次计费）"
                        else "开：思考档也开着 = 云端深度思考搜索（多步推理中自主搜+读链接）",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                }
                }   // 滚动区结束（智能体/模型/思考/联网）
                Spacer(Modifier.height(4.dp))
                androidx.compose.material3.HorizontalDivider()
                Spacer(Modifier.height(4.dp))
                // 权限段**钉在面板底部**：不在滚动区里，永远可见可点（与「模型与思考」面板
                // 把思考强度钉底同一形态——这是 ModalBottomSheet 里唯一可靠的排布方式）。
                Text(
                    "权限",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 14.dp, bottom = 8.dp)
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    PermModes.ALL.forEach { mode ->
                        val on = mode.key == currentPermMode
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(10.dp))
                                .background(
                                    if (on) MaterialTheme.colorScheme.primaryContainer
                                    else MaterialTheme.colorScheme.surfaceContainer
                                )
                                .clickable {
                                    haptics.perform(HapticEngine.Kind.Select)
                                    applyPermMode(mode.key)
                                }
                                .padding(vertical = 10.dp),
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Text(
                                mode.label,
                                style = MaterialTheme.typography.bodySmall,
                                color = if (on) MaterialTheme.colorScheme.onPrimaryContainer
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1
                            )
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
                androidx.compose.material3.HorizontalDivider()
                Spacer(Modifier.height(4.dp))
                // 上下文：只显示占用，点开看明细（原来的环 + 百分比药丸不再常驻输入区）
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .clickable {
                            showChatSetup = false
                            showContext = true
                        }
                        .padding(vertical = 12.dp)
                ) {
                    Icon(
                        Icons.Rounded.DataUsage, null,
                        Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.width(12.dp))
                    Text("上下文", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    val ctxWindow = io.github.m0use001.planability.data.contextWindowOf(chatModelName)
                    val ctxPct = if (ctxWindow > 0) (state.ctxTokens * 100 / ctxWindow) else 0
                    Text(
                        if (state.ctxTokens <= 0) "空" else "$ctxPct%",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                // 2026-10-31 信息行减密：低频的「悬浮窗输入」从输入区药丸行挪进设置面板。
                // 权限药丸那行原来 4 枚药丸横排（模型/上下文/权限/悬浮窗），小屏上挤成一片；
                // 悬浮窗是会话级开关且低频，放在这里与智能体/模型/思考同属"这一轮怎么发"。
                if (currentId > 0L) {
                    Spacer(Modifier.height(4.dp))
                    androidx.compose.material3.HorizontalDivider()
                    Spacer(Modifier.height(4.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .clickable {
                                showChatSetup = false
                                haptics.perform(HapticEngine.Kind.Select)
                                // 与原来一样是**搬运**不是新建——草稿共用 ChatDrafts，
                                // 关掉浮窗回主窗时字还在，所以不做任何清理。
                                appContext.startActivity(
                                    android.content.Intent(
                                        appContext, FloatChatActivity::class.java
                                    )
                                        .putExtra(FloatChatActivity.EXTRA_SESSION_ID, currentId)
                                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                )
                            }
                            .padding(vertical = 12.dp)
                    ) {
                        Icon(
                            Icons.Rounded.PictureInPictureAlt, null,
                            Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.width(12.dp))
                        Text("悬浮窗输入", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                        Text(
                            "当前会话",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }

    // 智能体选择器（发送设置里的「智能体」行）：新会话记草稿，已存会话落库
    if (showAssistantPick) {
        AssistantPickerSheet(
            onDismiss = { showAssistantPick = false },
            onPick = { a ->
                showAssistantPick = false
                assistantLabel = "${a.emoji} ${a.name}"
                assistantPlainName = a.name
                assistantKey = a.key.ifBlank { "general" }
                assistantDraft = a.id
                if (currentId > 0L) scope.launch { Graph.chat.setSessionAssistant(currentId, a.id) }
            }
        )
    }

    // 文件卡「打开」：能应用内预览的走统一预览器（docx/xlsx/pptx 解出文字、pdf、图片、文本），
    // 预览不了的（zip/apk/db…）明确说清楚并给「用其他应用打开」。
    // 2026-10-20 用户截图：打开 AI 生成的「示例文档.docx」，弹窗里是一屏 ZIP 二进制乱码——
    // 旧实现把任何非图片文件都 readText()，docx 是 zip，必然乱码。
    openFilePath?.let { p ->
        val f = remember(p) { java.io.File(p) }
        if (io.github.m0use001.planability.ui.files.isPreviewable(f.name)) {
            io.github.m0use001.planability.ui.files.FileViewerRoute(file = f, onClose = { openFilePath = null })
        } else {
            AlertDialog(
                onDismissRequest = { openFilePath = null },
                modifier = Modifier.motionEnter(),
                title = { Text(f.name, style = MaterialTheme.typography.titleSmall) },
                text = {
                    Text(
                        "这种格式应用内不能预览（二进制文件），可以用手机上的其他应用打开。",
                        style = MaterialTheme.typography.bodySmall
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        val file = f
                        openFilePath = null
                        runCatching {
                            val uri = androidx.core.content.FileProvider.getUriForFile(
                                appContext, appContext.packageName + ".fileprovider", file
                            )
                            appContext.startActivity(
                                android.content.Intent(android.content.Intent.ACTION_VIEW)
                                    .setDataAndType(uri, io.github.m0use001.planability.ui.files.mimeOf(file.name))
                                    .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            )
                        }.onFailure {
                            Toast.makeText(appContext, "没有能打开该文件的应用", Toast.LENGTH_SHORT).show()
                        }
                    }) { Text("用其他应用打开") }
                },
                dismissButton = {
                    TextButton(onClick = { openFilePath = null }) { Text("关闭") }
                }
            )
        }
    }
    if (embedded) {
        chatBody()
    } else {
        PredictiveBackOverlay(onBack = onDone) { chatBody() }
    }
}

/**
 * 2026-11-22 长会话发消息卡顿/掉帧根修（◆「辅助学习微积分」：681 条消息、
 * 123 条长回复、列表 870+ 条目）。
 *
 * 病根：`MessageList` 的 `listBuild` 每次流式刷新都整体重建——流式时每 55ms 改一次
 * `state.messages`（flushStateRowThrottled）→ `nodes` 重建 → `listBuild` 重建 → 对**全部
 * 已完成长消息**重跑 `ChatComp.parse + splitMessageParts`（O(全history) 正则扫描）。主线程
 * 被这堆重解析灌满：计时器冻在「思考中·0秒」、下拉无响应，攒一阵才挤出一帧（用户看到的
 * 「内容一股脑蹦出」）+ 严重掉帧 + 每次 GC 30MB/118ms。
 *
 * 已完成的助手消息在流式期间**内容不变**，切条结果可跨帧/跨组合复用。按
 * `id:hashCode:length` 做模块级 LRU（hashCode 由 String 自身缓存，查键 O(1)，不扫全串）。
 * 只缓存「真切成多片」的结果；单片/无需切返回 null（调用方走 Whole 老路径，行为不变）。
 */
/**
 * 2026-11-23 v78 崩溃根修（◆「微积分」超长会话进场即 ANR/闪退）。
 *
 * 老实现 `msgSplitCache` 缓存的坑：它缓存「整条消息 parse 出来的段列表」，且为了算出
 * **条目数**，首帧必须把全部已完成长消息都 parse 一遍（681 条消息 / 123 条长回复 →
 * 主线程秒级阻塞 + 段对象常驻数百 MB，实测 PSS 768MB、连续三次 Input dispatch timeout）。
 *
 * 改为两级：
 *  · [cachedPartCount] 只缓存**片数**（Int），计算是纯字符扫描、不 parse、不建段；
 *  · [cachedChunks] 只缓存**当前在屏那一两条**消息的切块文本（LRU=4）；
 *  · 每片内容在真正滚动到它时才 parse —— 单测见 ChatMessagePartsTest / 会话进场压测。
 */
private val msgPartCountCache = androidx.collection.LruCache<String, Int>(1024)
private val msgChunkCache = androidx.collection.LruCache<String, List<String>>(4)

private fun partKey(msg: ChatMessage): String =
    msg.id.toString() + ":" + msg.content.hashCode() + ":" + msg.content.length

private fun cachedPartCount(msg: ChatMessage): Int {
    val key = partKey(msg)
    msgPartCountCache.get(key)?.let { return it }
    val n = countMessageParts(msg.content)
    msgPartCountCache.put(key, n)
    return n
}

private fun cachedChunks(msg: ChatMessage): List<String> {
    val key = partKey(msg)
    msgChunkCache.get(key)?.let { return it }
    val res = markdownChunks(msg.content)
    msgChunkCache.put(key, res)
    return res
}

@Composable
private fun MessageList(
    state: ChatUiState,
    sessionId: Long,
    listState: androidx.compose.foundation.lazy.LazyListState,
    bookmarksExpanded: Boolean,
    /** 2026-11-22：发送方（sendCard/sendCurrent）每发一条就 ++——发送 = 要看最新，
     *  MessageList 收到信号即接管跟随（含冷启动恢复过阅读位置 autoFollow=false 的会话）。 */
    followTick: Int = 0,
    onBookmarksExpanded: (Boolean) -> Unit,
    /** v47：手动区间压缩标记（区间内的消息渲染成摘要卡） */
    compactMarkers: List<io.github.m0use001.planability.data.CompactMarkerEntity>,
    /** v47.1：本会话正在跑的多智能体协作（无则 null）——列表底部显示加载动画+第 k/n 步+实时秒数 */
    runningAgent: io.github.m0use001.planability.ai.ptl.SubAgentRun?,
    /** v50：点摘要卡「查看原文」→ 回调 marker.id，独立查看页由 ChatScreen 顶层挂载 */
    onOpenCompactOriginal: (Long) -> Unit,
    onLongPress: (ChatMessage) -> Unit,
    onSuggestion: (String) -> Unit,
    assistantKey: String,
    onSendCard: (String) -> Unit,
    onExportCode: (String, String) -> Unit,
    onOpenFile: (String) -> Unit
) {
    // 「最新」胶囊的点击触感（2026-10-31 从被删除的重复 FAB 迁移过来）
    val haptics = LocalHaptics.current
    // 2026-11-11 用户「自动书签要加记忆，不然每次都是从顶部 1 开始」：面板列表状态
    // 抬到 ChatBookmarksPanel 之外——AnimatedVisibility 收起会卸载面板内容，
    // 面板内 remember 的状态每收一次就丢一次；放这里随 MessageList 常驻，开合不丢。
    val bookmarkPanelListState = androidx.compose.foundation.lazy.rememberLazyListState()
    if (state.messages.isEmpty() && !state.streaming) {
        Column(
            Modifier.fillMaxSize().padding(horizontal = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                greetingText(AppTime.now().hour),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(6.dp))
            Text(
                // 原来这里写的是「或直接输入 / 语音」——聊天输入栏的麦克风按钮已经删了，
                // 提"语音"会让用户去找一个不存在的入口（2026-10-12 用户指出）。
                // 语音输入本体还在抽屉与笔记里，只是**不在这块输入区**。
                "工作台已就绪 · 点建议快速开始，或直接输入",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(20.dp))
            suggestionsFor(assistantKey).forEach { s ->
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .clickable { onSuggestion(s) }
                ) {
                    Text(
                        s, style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
                    )
                }
            }
        }
        return
    }
    // 正序布局：列表从上到下 = 从前到后，最后一条消息紧邻输入框（列表末尾）。
    // 2026-11-05 八修：不再 asReversed / reverseLayout（见上方 atBottom 处的长注释）。
    // userTexts 传给每个气泡（引用菜单用）。2026-11-11 掉帧修：原来每次重组都 filter+map
    // 出**新 List 实例**——List 是不稳定参数，会把所有可见 MessageBubble 拖着重组合。
    // 用与 staleKey 同节奏的 remember（用户消息在流式期间不变，条数+末条 id 不变即命中）。
    val staleKey = state.messages.size to state.messages.lastOrNull()?.id
    val userTexts = remember(staleKey) {
        state.messages.filter { it.role == "user" }.map { it.content }
    }
    // staleIds：交互卡该作废的消息 id。判定见 staleCardIds 的注释。
    //
    // remember 的 key 用「消息条数 + 最后一条的 id」而不是 state.messages 本身：
    // 流式期间 ChatEngine 会用 copy() 生成内容不断变化的同一条消息，List.equals
    // 按元素比，只要条数和最后一条没变就相等 → 缓存不失效，卡片永远不变灰
    // （真机踩过：staleIds 一直是空集）。
    val staleIds = remember(staleKey) { staleCardIds(state.messages) }
    // 2026-10-31「过程链」：思考+工具调用+回执先切成显示节点（ChatNodes.kt）。
    // 放在 LazyColumn 外面算：LazyListScope 的 lambda 不是 @Composable 上下文，不能 remember。
    // v47：手动区间压缩标记参与切节点——被覆盖的消息不渲染，区间终点处放一张摘要卡。
    val nodes = remember(state.messages, compactMarkers) {
        buildChatNodes(state.messages, compactMarkers)
    }
    // 2026-11-21 巨型消息切条（用户拍板「切，但保住跨块选词」）：把 node 列表摊平成
    // **列表条目**列表——超长助手消息按块拆成多片，其余节点一对一。
    // 同时算出 `nodeStartItem[i]`：第 i 个 node 从第几个条目开始（末尾多一格 = 总条目数），
    // 供阅读位置恢复、书签面板、跳转做「节点序号 ⇄ 条目序号」换算。
    val listBuild = remember(nodes, state.streaming) {
        val items = ArrayList<MessageListItem>(nodes.size + 8)
        val start = IntArray(nodes.size + 1)
        nodes.forEachIndexed { i, n ->
            start[i] = items.size
            val single = n as? ChatNode.Single
            var partCount = 1
            if (single != null) {
                val msg = single.msg
                if (msg.role == "assistant" && msg.status != "streaming" &&
                    msg.content.length >= MSG_PART_MIN_CHARS
                ) {
                    // v78：这里只取「片数」（纯字符扫描、不 parse）；每片内容等滚到才解析
                    partCount = cachedPartCount(msg)
                }
            }
            if (single != null && partCount > 1) {
                repeat(partCount) { pi -> items += MessageListItem.Part(single, pi, partCount) }
            } else {
                items += MessageListItem.Whole(n)
            }
        }
        start[nodes.size] = items.size
        MessageListBuild(items, start)
    }
    val listItems = listBuild.items
    val nodeStartItem = listBuild.nodeStartItem
    // 「回到最新」按钮：不做成浮动球（占位又挡内容），做成贴在**输入框上方**的一枚
    // 小胶囊，只在滚离底部时出现。消息重复/翻旧了的时候，点一下直接回到底部最新一条。
    val scope = rememberCoroutineScope()
    // 2026-11-05 八修（彻底解）：**放弃 reverseLayout，改普通正序排列**。
    // reverseLayout 的几何结构决定了「冻结」是一个需要持续追赶的动态目标——新内容
    // 出现在视口底缘（布局原点），尾巴在长，视口与尾巴的相对位置永远在变；前六修
    // 在补偿方向/判据/限位上打转，全是在给这个结构缺陷打补丁。正序布局下：
    //  · 冻结 = 几何必然（内容在列表末尾增长，视口锚在顶部不动，分毫不动）；
    //  · 跟随 = 内容增长时 scrollToItem(last, MAX)（正序下无方向歧义，一帧到位）；
    //  · 打断 = scrollToItem 是瞬时滚（非动画），不与手势/惯性冲突。
    // 唯一代价是跟随需要显式滚动——这正是所有聊天 App（Telegram/微信）的标准做法。
    //
    // 「在底部」判定：!canScrollForward（正序下等价于"看不到更晚的内容"），
    // 不依赖 index/offset 的方向语义，永远正确。
    // 2026-11-20：「在底部」判定带**容差**（见 ChatScrollBottomSlack.kt）。
    // 流式时最后那条气泡一直在长，严格判（canScrollForward）会把「长了几像素」
    // 当成离底：胶囊反复冒出来（点一下本来就在底、什么也不动 = 跳转失效），
    // 跟随 effect 的「上一帧在底才准贴」闸被一次误判永久关死（不再跟）。
    val bottomSlackPx = with(androidx.compose.ui.platform.LocalDensity.current) {
        BOTTOM_SLACK_DP.dp.toPx()
    }
    val atBottom by remember(bottomSlackPx) {
        derivedStateOf {
            if (!listState.canScrollForward) {
                true
            } else {
                val info = listState.layoutInfo
                val last = info.visibleItemsInfo.lastOrNull()
                last != null && last.index == info.totalItemsCount - 1 &&
                    isAtBottomWithSlack(
                        (last.offset + last.size).toFloat(),
                        info.viewportEndOffset.toFloat(),
                        bottomSlackPx
                    )
            }
        }
    }
    // 2026-11-19：**手指是不是按在列表上**——贴底时判断「用户在拖」的唯一可靠信号。
    // `isScrollInProgress` 分不清「我们自己的 scrollToItem」和「用户的手势」：
    // 长列表上我们那一记滚动要跨好几帧，用它判手势会把自己当成用户（见 snapToBottom）。
    val listDragged by listState.interactionSource.collectIsDraggedAsState()
    // 2026-10-31 「自动跟随」改成一等状态（借鉴 Operit 的 autoScrollToBottom 模型）：
    //   autoFollow=true  → 新内容来了就跟着走（钉在底部）
    //   autoFollow=false → 用户在看别处，内容长多少都别动他的视口
    // 2026-11-05 八修踩坑修正：关跟随**只看「滚动刚结束且停在半路」**这一条转移。
    // 原规则 `!scrolling && !bottom → 关` 在组合首帧就会触发——正序布局开局停在
    // 顶部（bottom=false）且没在滚动，跟随当场被关死，流式期间永远不跟
    // （reverseLayout 时代初始必在底部，这个坑不存在；实测 A/B 阶段全程钉在顶部）。
    var autoFollow by remember { mutableStateOf(true) }
    var lastScrolling by remember { mutableStateOf(false) }
    // 「最新」胶囊的显示条件（2026-11-20 终版）：
    //  · 基础仍是纯位置判定（2026-11-08 的「显示延迟」修：惯性中也要立刻给入口）；
    //  · 但**流式跟随活着时必须压住**——最后那条气泡一帧能长出一大截（新段落/
    //    工具卡进场），任何固定容差都会被击穿，胶囊会跟着闪现；用户点它时列表
    //    其实就在底，观感就是「跳转失效」。跟随活着=用户本来就在底，胶囊没有意义。
    //  · 非流式时维持旧行为（长距离惯性中胶囊立刻出现，不迟到的老修复保留）。
    val showLatestPill by remember {
        derivedStateOf { !atBottom && !(state.streaming && autoFollow) }
    }
    // 2026-11-20 钳制模型重写（用户拍板「壮士断腕」）：贴底不再是「反复发 scrollToItem
    // 命令的 1600ms 循环」。旧原语的自指反馈环（自己的滚动把自己判成手势 / 两驱动互相
    // 取消 / 等帧读数）是 11-18、11-20 三轮大修的共同病根，补丁修不动原语。
    // 新原语两步，全部**幂等**：
    //  ① `requestScrollToItem(末项, MAX)`：fire-and-forget（非 suspend，foundation 1.12.0），
    //     一次跳到列表尾部，不占当前协程、不与手势竞争；
    //  ② `scrollBy(Float.MAX_VALUE)`：钳掉末项是短行（活行/系统痕迹）时剩下的残余像素。
    // 两个动作都是「到底」，谁先谁后 / 被谁取消结果一样——不存在「贴底进行中需要让路」。
    // 手势判定随之删除：requestScrollToItem 是瞬时定位（不逐帧滚动），用户手指按着时
    // 松手前它也已经落位，不存在「循环重发被当成用户手势」的自伤场景。
    // 仍打 `ProgrammaticScroll` 标记 + 抑制端点触感（外层「滚动停稳不在底→关跟随」
    // 的探测器需要知道这不是用户离开底部；程序滚动撞底不该震）。
    val snapToBottom: suspend () -> Unit = {
        io.github.m0use001.planability.core.ProgrammaticScroll.begin()
        io.github.m0use001.planability.core.ScrollHapticSuppress.suppress(1400)
        try {
            // 2026-11-22 真根因（fi 逐帧日志实锤）：原来先 `requestScrollToItem(末项, 巨大offset)`
            // 再 scrollBy。`requestScrollToItem` 会把 `firstVisibleItemScrollOffset` 留成一个
            // **超大值**；紧接着新消息追加（items +2）后 LazyColumn 发现该锚点 offset 越界 →
            // 把 firstVisibleItemIndex **重置到 0**（日志实测 firstKey 变回最首条 7803#0），
            // 用户看到「发送后蹦顶」，直到下一次贴底才拉回。
            //
            // 2026-11-23 v78 再根修（◆ 微积分进场 ANR/闪退）：上一版改成
            // `repeat(2){ scrollBy(1000万px) }` 在**超大会话**上仍有致命缺陷——正序
            // LazyColumn 里向后滚一个远超已知内容的距离，需要逐项组合中间所有条目才能
            // 定位（实测 870+ 条目一帧全组合 → Input dispatch timeout 5s → 系统杀进程）。
            // 正解是**按索引**跳：`scrollToItem(末项)` 直接定位，绝不逐项测量中间内容；
            // 再把末项自身的残余高度滚掉（此时末项已组合，成本 = 一条）。
            listState.settleToLatest()
        } finally {
            io.github.m0use001.planability.core.ProgrammaticScroll.end()
        }
    }
    // 2026-11-22 发送接管：tick 变化即翻回跟随并贴底一记（发的那条要能看见）。
    // 用 tick 而不是把 autoFollow 抬出去：跟随状态与列表几何强耦合，抬出去会引入
    // 第二个可写入口；发送方只该表达「我要看最新」，怎么滚仍由 MessageList 决定。
    LaunchedEffect(followTick) {
        if (followTick > 0) {
            if (io.github.m0use001.planability.BuildConfig.DEBUG) android.util.Log.d("ChatFollow", "tick=$followTick take over, autoFollow->true")
            autoFollow = true
            snapToBottom()
        }
    }
    var lastSavedAnchor by remember(sessionId) { mutableStateOf<Long?>(null) }
    // 2026-11-22 发送优先于「阅读位置恢复」：进会话时 sessionId effect 会等 ~120ms 后把视口
    // 恢复到上次阅读锚点（可能在上方），若用户进会话后很快发送，followTick 接管贴底会与这次
    // 恢复相撞——用户看到「先蹦到顶部、又跳到底部」。恢复前读最新值：已发送/正在流式就不恢复旧锚点。
    val followTickNow by rememberUpdatedState(followTick)
    val streamingNow by rememberUpdatedState(state.streaming)
    // effect 只挂 listState：nodes 会换新实例，collect 里必须读最新值（rememberUpdatedState）
    val nodesNow by rememberUpdatedState(nodes)
    // 2026-11-22 锚点修复配套：切条后 LazyColumn 条目序号 ≠ 节点序号（超长消息一片多节点），
    // 保存阅读锚点必须经 nodeStartItem 换算，否则存错消息、下次冷启动恢复跳错位置。
    val nodeStartItemNow by rememberUpdatedState(nodeStartItem)
    // 2026-11-12 用户「杀后台后 AI 会话里划到的位置也要记忆（而不是重置到最新）」：
    // 滚动停稳时把**末条可见消息 id** 记到会话 KV（anchor 没变就不写）；进程冷启动的
    // 第一次进会话恢复到那里（见下方 sessionId effect），进程内切会话仍按 11-08
    // 指令贴底——锚点在每次停稳都刷新，回到底部后再杀后台=恢复到最新，两种预期都对。
    // 2026-11-22 用户「发送消息后屏幕直接飞到之前的消息」根修：关跟随的唯一可信信号是
    // **用户的手真的拖过列表**（sawDrag）。旧判据「停稳且不在底就关」会被钳底差一步击穿——
    // 流式内容一帧长一大截，钳底那记 scrollBy 收尾时可能已被新增长度顶离底
    // （ProgrammaticScroll 标记随 scrollBy 结束即撤，探测窗口内 active() 已是 false），
    // 程序滚动被当成「用户离开」→ autoFollow 关死 → 视口钉在旧消息上、回复在屏幕外
    // 疯长、「最新」胶囊常驻 = 三连报障。fling 离场（手指已抬起）靠拖拽期置位的
    // sawDrag 记账，不受抬起后 isDragged 翻 false 影响。
    LaunchedEffect(listState) {
        var sawDrag = false
        snapshotFlow { Triple(listState.isScrollInProgress, atBottom, listDragged) }
            .collect { (scrolling, bottom, dragged) ->
                if (dragged) sawDrag = true
                // 到底即恢复跟随（手指把列表送回底部 / 程序滚动贴底 / 点「最新」落底）
                if (bottom) {
                    if (io.github.m0use001.planability.BuildConfig.DEBUG && !autoFollow) android.util.Log.d("ChatFollow", "collector: bottom -> autoFollow=true")
                    autoFollow = true
                    sawDrag = false
                } else if (lastScrolling && !scrolling &&
                    !io.github.m0use001.planability.core.ProgrammaticScroll.active() &&
                    sawDrag
                ) {
                    if (io.github.m0use001.planability.BuildConfig.DEBUG) android.util.Log.d("ChatFollow", "collector: stop not-at-bottom sawDrag -> autoFollow=false")
                    autoFollow = false
                }
                lastScrolling = scrolling
                if (lastScrolling && !scrolling) {
                    val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()
                    // 条目序号 → 节点序号（切条后两者错位；活行等附加条目落到最后一个节点）
                    val fi = last?.index ?: -1
                    val ni = if (fi < 0) -1
                    else nodesNow.indices.lastOrNull { nodeStartItemNow[it] <= fi } ?: -1
                    val node = nodesNow.getOrNull(ni)
                    val anchor = when (node) {
                        is ChatNode.Single -> node.msg.id
                        is ChatNode.Chain -> node.msgs.lastOrNull()?.id
                        else -> null
                    }
                    if (anchor != null && anchor != lastSavedAnchor) {
                        lastSavedAnchor = anchor
                        scope.launch { Graph.settings.kvPut("chat.readpos.$sessionId", anchor.toString()) }
                    }
                }
            }
    }
    // 2026-11-20 用户诊断需求（跨线 ui/info）：「加一个追踪器，盯着屏幕上某个点相较于
    // 整体会话的位置（例如从下往上看 20% 处），我滚动几下，你看看位置变化是不是平滑曲线」。
    // 只在 debug 包且**滚动进行中**采样（视口固定点 → 会话内容坐标 + 像素速度来源），
    // 停稳后把结果交给 debug 浮层（画曲线）；不参与任何滚动决策，release 包恒关。
    //
    // 2026-11-21 采样改为**事件驱动**：把 `isScrollInProgress` 和 `layoutInfo` 绑进同一个
    // snapshotFlow——滚动时布局每帧变化 → 每帧采一条；停手时 `isScrollInProgress` 翻 false
    // → 收尾出结果。原来的 `collectLatest + while(true){ withFrameNanos }` 在多次手势后
    // 偶发拿不到数据（用户实测浮层不出现），机制太重且难以取证，故换掉。
    var lastTrace by remember { mutableStateOf<io.github.m0use001.planability.core.ScrollTrace.Result?>(null) }
    val traceCtx = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(listState) {
        val trace = io.github.m0use001.planability.core.ScrollTrace
        if (!trace.enabled) return@LaunchedEffect
        var wasScrolling = false
        snapshotFlow { listState.isScrollInProgress to listState.layoutInfo }
            .collect { (scrolling, info) ->
                if (scrolling) {
                    if (!wasScrolling) {
                        wasScrolling = true
                        trace.beginScroll()
                    }
                    val vpStart = info.viewportStartOffset
                    val vpEnd = info.viewportEndOffset
                    val span = (vpEnd - vpStart).toFloat()
                    val frac = trace.anchoredFractionFromBottom
                    val pointY = vpEnd - span * frac
                    val item = info.visibleItemsInfo.firstOrNull {
                        pointY >= it.offset && pointY < it.offset + it.size
                    } ?: info.visibleItemsInfo.minByOrNull {
                        kotlin.math.abs((it.offset + it.size / 2).toFloat() - pointY)
                    }
                    if (item != null) {
                        trace.sample(
                            frameNanos = System.nanoTime(),
                            firstIndex = listState.firstVisibleItemIndex,
                            firstOffset = listState.firstVisibleItemScrollOffset,
                            pointY = pointY,
                            ptIndex = item.index,
                            ptOffset = item.offset,
                            ptSize = item.size,
                            dragged = listDragged,
                            programmatic = io.github.m0use001.planability.core.ProgrammaticScroll.active(),
                            canScrollForward = listState.canScrollForward,
                            anchorSize = info.visibleItemsInfo
                                .firstOrNull { it.index == listState.firstVisibleItemIndex }?.size ?: -1,
                        )
                    }
                } else if (wasScrolling) {
                    wasScrolling = false
                    val r = trace.endScroll()
                    if (r != null) {
                        // 2026-11-21：浮层**延后 140ms 挂载**——它自己也是 Surface+Canvas，
                        // 若和「停手那一帧」同帧组合，会让调试版多出一次几十毫秒的抽动，
                        // 正好污染用户对「停止时才卡一下」的判断（release 无浮层）。
                        scope.launch {
                            kotlinx.coroutines.delay(140)
                            lastTrace = r
                        }
                        // 原始采样落盘（debug 专属）：等停手后 1s 的帧监视跑完再写，
                        // 文件里带上「停手后哪几帧掉」——用来分辨是真渲/重排，还是浮层/Toast。
                        // 必须离开主线程（停手那一帧同步写十几 KB 文件本身就是卡顿来源）。
                        val raw = r.raw
                        val dir = traceCtx.filesDir
                        Thread {
                            Thread.sleep(1000)
                            runCatching {
                                java.io.File(dir, "scrolltrace.txt")
                                    .writeText(raw + trace.postStopText())
                            }
                        }.start()
                    }
                    // 2026-11-21：停手不再弹 Toast——每次手势一次 IPC + 系统窗口插入，
                    // 正是「停手顿一下」的可疑来源之一；浮层 140ms 后照样给读数。
                }
            }
    }
    // 2026-11-14 用户「上下滑动还是卡」三段修：会话打开即**后台预热全部公式**——
    // 之前只有滑到哪渲到哪，首滑必有一段 fallback→位图换高跳变（正文在指下重排）。
    // 预热排 background 档、永远让位给可见 item 的交互渲染（见 MarkdownMath
    // warmUpFormulas）。key 取**结构签名**（条数+末条id+streaming），不是 nodes：
    // 2026-11-17 滚动流畅性 P0——nodes 每 55ms 因流式 state 刷新而重建，若拿它当
    // key，预热协程每 55ms 取消重启一次、每次 map 全部消息文本重扫（流式文本必然
    // cache miss）。结构签名在流式期间不变（流式行进场后 size/lastId 都不动），
    // 只在 ①新消息进场 ②流式开始/结束（streaming 翻转，结束时补热刚定稿的正文）时重启。
    // 缓存命中的跳过、只补渲缺的；解析缓存（splitMathSegments 模块级）也顺带焐热。
    // 字号取 bodyLarge 实际值（含用户 fontScale 缩放）——与 MdInlineText 的
    // style.fontSize 同源，否则 cache key（含字号位模式）对不上，预热白渲
    val warmFontPx = with(androidx.compose.ui.platform.LocalDensity.current) {
        MaterialTheme.typography.bodyLarge.fontSize.toPx()
    }
    val warmColorArgb = MaterialTheme.colorScheme.onSurface.toArgb()
    // 容器宽（px）：给预热算「宽公式要不要拆行」，把 gathered 变体（行内口径 + 块级口径）
    // 也提前渲好——否则它们到货时同样换高（用户说的「压缩毛巾」）。
    val warmScreenPx = with(androidx.compose.ui.platform.LocalDensity.current) {
        androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp.dp.toPx()
    }
    LaunchedEffect(state.messages.size, state.messages.lastOrNull()?.id, state.streaming) {
        if (state.messages.isEmpty()) return@LaunchedEffect
        val fontPx = warmFontPx
        val colorArgb = warmColorArgb
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            io.github.m0use001.planability.ui.notes.warmUpFormulas(
                state.messages.map { it.content }, fontPx, colorArgb,
                screenPx = warmScreenPx,
            )
        }
    }
    // 2026-11-21 用户「刚才不是叫你把所有已经产生的公式都渲染了吗」：上面的预热是**按会话**跑的，
    // 切到别的会话/别的历史的公式仍没落盘，滑过去就是裸 LaTeX。这里在**进程内跑一次**
    // 全库预热（所有会话里含 `$` 的消息 + 全部笔记正文）；已在磁盘的会被 warmUpFormulas
    // 直接跳过，所以第二次进来几乎零成本。
    LaunchedEffect(Unit) {
        val fontPx = warmFontPx
        val colorArgb = warmColorArgb
        val screenPx = warmScreenPx
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            runCatching {
                val all = Graph.chat.allMathContents() +
                    runCatching { Graph.notes.allNotes().map { it.content } }.getOrDefault(emptyList())
                io.github.m0use001.planability.ui.notes.warmUpFormulas(
                    all, fontPx, colorArgb, screenPx = screenPx,
                )
            }
        }
    }
    // 2026-11-20 钳制模型重写（用户拍板「壮士断腕」）：流式跟随不再是「ScrollFollow
    // 状态机 + scrollToItem 命令」。旧原语的自指反馈环（自己的滚动把自己判成手势 /
    // 等帧读数 wasAtBottom 被一次误判永久关死 / 容差被一帧长一大截击穿）是三轮大修的
    // 共同病根。新执行层：
    //  · 「要不要钳」由纯函数 BottomClamp.decide 决定（可穷举单测，32 组合）；
    //  · 执行只有一个动作：`scrollBy(Float.MAX_VALUE)` —— 幂等钳到底（只滚残余像素，
    //    不组合中间 item；不管 contentPadding 的数学；两个「到底」互取消无后果）；
    //  · snapshotFlow 挂 layoutInfo：内容长高 → layoutInfo 变 → 判定通过 → 钳一次 →
    //    贴平 → canScrollForward=false → 判定不再通过，自然收敛，无循环无重发。
    // 「上一帧在底才准贴」的闸不再需要：pinned 是自己维护的状态（见上方 autoFollow），
    // 末可见项不是末项时根本不钳——用户在看历史，内容长多少都不动他的视口。
    LaunchedEffect(state.streaming) {
        if (!state.streaming) {
            // 流式收尾补一记钳底——最后一帧增长留下的几十像素缝，
            // 会让「最新」胶囊在回复结束后仍然挂着（用户点它=没反应=「跳转失效」）。
            // 用户在看历史（autoFollow=false）时不许动他的视口。
            if (autoFollow) snapToBottom()
            return@LaunchedEffect
        }
        // 2026-11-24「流式跟一会就停」根修（用户实测）：详见 BottomClamp.Input.cleanFollow。
        var cleanFollow = false
        snapshotFlow {
            val info = listState.layoutInfo
            io.github.m0use001.planability.core.BottomClamp.Input(
                streaming = true,
                pinned = autoFollow,
                dragged = listDragged,
                lastVisibleIsLast =
                    info.visibleItemsInfo.lastOrNull()?.index == info.totalItemsCount - 1,
                canScrollForward = listState.canScrollForward,
                userScrolling = listState.isScrollInProgress &&
                    !io.github.m0use001.planability.core.ProgrammaticScroll.active(),
                cleanFollow = cleanFollow,
            )
        }.collect { input ->
            // 记账在判定之前：贴平在底 = 跟随干净；见到用户拖拽/惯性滚动 = 人在别处。
            if (input.dragged || input.userScrolling) cleanFollow = false
            if (!input.canScrollForward) cleanFollow = true
            if (!io.github.m0use001.planability.core.BottomClamp.decide(input)) return@collect
            // 跟随不该震：每帧钳底都会撞底部端点，压掉端点触感。
            io.github.m0use001.planability.core.ScrollHapticSuppress.suppress(600)
            // 打「程序滚动」标记——外层「滚动刚结束且不在底 → 关掉跟随」的探测器
            // 需要知道这次滚动不是用户干的，否则跟随会被误关（11-20 的老 bug）。
            io.github.m0use001.planability.core.ProgrammaticScroll.begin()
            try {
                // 2026-11-23 v78：按索引跳到末项（见 settleToLatest 注释），
                // 不再用 scrollBy(千万 px)——超大会话会逐项组合中间条目导致 ANR。
                listState.settleToLatest(steps = 1)
            } finally {
                io.github.m0use001.planability.core.ProgrammaticScroll.end()
            }
        }
    }
    // v50「过程链」/ v47 压缩节点渲染在下方 items(nodes) 里；「活行」的两个 item 挪到它之后
    // （2026-11-05 八修正序化：活行必须待在列表**末尾**才在屏幕底部）。
    Box(Modifier.fillMaxSize().reachedEndHaptic(listState), contentAlignment = Alignment.TopCenter) {
        // 2026-11-21（切条配套）：SelectionContainer **上提到列表层**，所有可见条目共用
        // 同一个选区注册器——长按选词仍然跨条目、跨切块连续拖选。
        // 以前是每条消息的每个 Md 段各自包一个容器（跨段都选不了），切条后更必须上提。
        SelectionContainer(Modifier.fillMaxSize()) {
            LazyColumn(
                state = listState,
                // 2026-11-19 平板批：会话区**唯一**的限宽来源。
                // 原来助手正文自己限 760dp 居中（MessageBubble 内），而工具调用行 / 过程链 /
                // 工具回执行是列表顶层的**全宽**行——两组左缘差 (栏宽−760)/2，
                // 用户气泡又另贴一条右缘，全页三四条基线（用户「会话内容不整齐」）。
                // 现在限宽落在**列表**上：每一条消息/工具行都在同一条列里，左缘自然对齐；
                // 列表本身在内容区里居中（上方/下方留白对称）。
                modifier = Modifier.fillMaxHeight().adaptiveColumn(ChatWideLayout.ContentMaxWidth),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 10.dp),
            // 2026-11-21 切条后条目间距改由**每个条目自己的顶部留白**给：
            // 同一条消息的续片之间不能再插 12dp（否则气泡里凭空多出段落缝）。
            verticalArrangement = Arrangement.Top
        ) {
            // 2026-10-31「过程链」：先把消息切成显示节点，思考+工具+回执合成一个容器
            // （见 ChatNodes.kt）。以前三条各渲染各的块，同一个动作有三个「详情」入口。
            itemsIndexed(listItems, key = { _, it -> it.key }) { itemIdx, item ->
                // 续片（Part.index > 0）不加顶距；其余条目照旧 12dp
                val topPad = if (itemIdx == 0) 0.dp
                else if (item is MessageListItem.Part && item.index > 0) 0.dp
                else 12.dp
                // v19.11：去掉 animateItem —— 流式气泡每个增量都在长高，animateItem 会不断重启动画
                // 位移动画，导致整页上下抽搐（用户反馈）
                if (item is MessageListItem.Part) {
                    val msg = item.node.msg
                    val isLastMsg = state.messages.lastOrNull()?.id == msg.id
                    // v78：片内容按需解析——只有真正滚到这一片才 parse（缓存只在屏 1~2 条）
                    val partSegs = remember(msg.id, msg.content.hashCode(), item.index) {
                        runCatching {
                            ChatComp.parse(cachedChunks(msg).getOrElse(item.index) { cachedChunks(msg).lastOrNull() ?: msg.content })
                        }.getOrElse { listOf(ChatSegment.Md(msg.content)) }
                    }
                    Box(
                        Modifier.padding(top = topPad)
                            .chatItemMeasureProbe().chatItemDrawProbe()
                    ) {
                        MessageBubble(
                            msg, isLastMsg, onLongPress, userTexts, onSendCard, onExportCode, onOpenFile,
                            stale = msg.id in staleIds,
                            turnRunning = state.streaming,
                            callingTool = state.callingTool,
                            contentMaxWidth = null,
                            partSegs = partSegs,
                            partIndex = item.index,
                            partCount = item.count,
                        )
                    }
                } else when (val node = (item as MessageListItem.Whole).node) {
                    is ChatNode.Chain -> Box(
                        Modifier.padding(top = topPad)
                            .chatItemMeasureProbe().chatItemDrawProbe()
                    ) {
                        ProcessChainBlock(
                            node.msgs, onOpenFile,
                            // 活跃判定：streamingId 还指着链内任一条消息 = 这条链的某轮工具
                            // 正在执行（2026-11-12 用户「看起来跟卡了一样」：执行期链头是
                            // 静态图标+冻结秒数，带动画的活行又远在列表底部）
                            active = state.streamingId != null && node.msgs.any { it.id == state.streamingId }
                        )
                    }
                    is ChatNode.Single -> {
                        val msg = node.msg
                        val isLastMsg = state.messages.lastOrNull()?.id == msg.id
                        Box(
                            Modifier.padding(top = topPad)
                                .chatItemMeasureProbe().chatItemDrawProbe()
                        ) {
                            MessageBubble(
                                msg, isLastMsg, onLongPress, userTexts, onSendCard, onExportCode, onOpenFile,
                                stale = msg.id in staleIds,
                                turnRunning = state.streaming,
                                callingTool = state.callingTool,
                                // 2026-11-19 平板批：会话页不自带限宽——限宽统一由消息列表做，
                                // 正文/工具行/过程链/回执行共用同一条列（对齐的唯一真相）。
                                contentMaxWidth = null
                            )
                        }
                    }
                    // v50：被压缩区间 → 摘要卡（默认显示完整摘要）；「查看原文」跳独立界面
                    // （v47 的长按菜单与卡内展开原文已废除）
                    is ChatNode.Compact -> {
                        val marker = node.marker
                        val originals = remember(marker.id, state.messages) {
                            state.messages.filter {
                                it.id >= marker.firstCompactedMessageId && it.id <= marker.lastCompactedMessageId
                            }
                        }
                        CompactSummaryCard(
                            marker = marker,
                            originals = originals,
                            onOpenOriginal = { onOpenCompactOriginal(marker.id) }
                        )
                    }
                }
            }
            // 2026-11-01 用户「子代理工作没痕迹，以为调用失败（实际在工作）」：
            // 工具轮**执行期间**——streamingId 行带 toolCallsJson、回执还没落齐——
            // 列表末尾（屏幕底部）显示一行活的进度：正在跑哪个工具 + 秒数跳动。
            // ask_expert/deep_plan 一跑几十秒，以前这期间界面完全静止（带正文的委托句
            // 不满足过程链的分组条件 isProcessMessage，链块只有回执落地后才出现）。
            val streamRow = state.messages.firstOrNull { it.id == state.streamingId }
            // 2026-11-14 用户：「工具调用运行时要有加载动画，不然 AI 写个文件用户以为卡死了」。
            // 两个"看起来卡住"的时段里，**参数生成期**此前完全没有信号：
            // - 执行期：streamRow 已有 toolCallsJson（引擎在轮末落库）→ 老条件覆盖；
            // - 参数生成期：模型把几千字文件正文当**工具参数**吐出来（一两分钟），
            //   toolCallsJson 还是空、行内却已有正文 → ThinkingRow 不显示（它只在
            //   正文为空时出现），界面只剩一个光标。callingTool 在首个工具名到达时
            //   就置位（ChatEngine StreamEvent.ToolCallDelta），拿它当挂载条件兜住。
            val argsPhase = state.streaming && streamRow != null &&
                streamRow.toolCallsJson.isBlank() && state.callingTool.isNotBlank()
            if (state.streaming && streamRow != null &&
                (streamRow.toolCallsJson.isNotBlank() || argsPhase)
            ) {
                item(key = "live-tools") {
                    LiveToolRow(streamRow, state.messages, callingTool = state.callingTool)
                }
            } else if (runningAgent != null) {
                item(key = "live-agent") { LiveAgentRow(runningAgent) }
            }
        }
        }   // SelectionContainer（选区注册器：跨条目/跨切块拖选）
        // 2026-11-02「自动书签」：每条用户消息自动成书签（≥3 条才显示）；入口在标题栏
        //（智能体胶囊右边），面板贴标题栏下方右缘滑出；跳转锚顶见 jumpChatBookmark。
        val bookmarks = remember(nodes) { buildChatBookmarks(nodes) }
        // v50.2（用户「自动书签加上状态记忆」）：记住**读到哪**，重进会话回到原位。
        // 以当前书签 ordinal 为锚（不存像素偏移——字号/气泡宽度变了偏移就没意义）。
        // bookmarks 是消息异步到达后才非空的，两处都用 rememberUpdatedState +
        // snapshotFlow 等"就绪"，不能在 effect 启动帧读空列表就放弃。
        val bookmarksNow by rememberUpdatedState(bookmarks)
        // 2026-11-08 用户「每次进入 AI 会话是从最新的会话开始」：**进会话一律贴底**。
        // 原来的「阅读位置恢复」（saveChatReadingPos/loadChatReadingPos 成对撤除）会在
        // 上次读到中途时把用户落回旧位置，与这条新指令直接冲突。
        // 2026-11-12 用户「杀后台后 AI 会话里划到的位置也要记忆（而不是重置到最新）」：
        // 冷启动（进程重启）后的**第一次**进会话恢复到上次停稳的锚点（chat.readpos KV）；
        // 进程内切会话仍贴底（11-08 指令继续成立）。锚点由滚动停稳 effect 持续刷新，
        // 回到底部再杀后台=锚点就是最新一条，恢复即贴底，两种预期自然统一。
        LaunchedEffect(sessionId) {
            if (sessionId <= 0L) return@LaunchedEffect
            withTimeoutOrNull(1500) {
                snapshotFlow { listState.layoutInfo.totalItemsCount }.first { it > 0 }
            }
            kotlinx.coroutines.delay(120)   // 等首帧布局稳定
            var restored = false
            if (chatReadingRestorePending) {
                chatReadingRestorePending = false
                // 2026-11-22：进会话后若用户已发送/正在流式，发送接管（followTick）已把视口拉到
                // 底部——此时再恢复到旧阅读锚点会把视口「先蹦到顶部、再跳回底部」。发送优先：跳过恢复。
                if (followTickNow > 0 || streamingNow) {
                    if (io.github.m0use001.planability.BuildConfig.DEBUG) android.util.Log.d("ChatFollow", "restore skipped: send/streaming active (tick=$followTickNow)")
                } else {
                    val anchor = Graph.settings.kvGet("chat.readpos.$sessionId")?.toLongOrNull()
                    if (anchor != null) {
                        val idx = nodes.indexOfFirst {
                            (it is ChatNode.Single && it.msg.id == anchor) ||
                                (it is ChatNode.Chain && it.msgs.any { m -> m.id == anchor })
                        }
                        // 锚点不在末尾才需要恢复；在末尾=本来就要贴底
                        if (idx >= 0 && idx < nodes.size - 1) {
                            // 切条后节点序号 ≠ 条目序号：换成条目序号再跳
                            if (io.github.m0use001.planability.BuildConfig.DEBUG) android.util.Log.d("ChatFollow", "restore: anchor=$anchor idx=$idx -> scrollToItem, autoFollow=false")
                            listState.scrollToItem(nodeStartItem[idx.coerceIn(0, nodes.size)])
                            autoFollow = false
                            restored = true
                        }
                    }
                }
            }
            if (!restored) {
                autoFollow = true
                // 2026-11-17：与「最新」胶囊共用同一个贴底函数（原来这里是另一份 while 循环，
                // 两处策略不一致、也都没有程序滚动标记 / 触感抑制）。
                snapToBottom()
            }
        }
        ChatBookmarksPanel(
            bookmarks, listState, nodes.size,
            expanded = bookmarksExpanded,
            onExpandedChange = onBookmarksExpanded,
            modifier = Modifier.align(Alignment.TopEnd),
            // 2026-11-11 用户「自动书签要加记忆」：状态抬到面板外，收起再展开不丢滚动位置
            panelListState = bookmarkPanelListState,
            // 2026-11-21 切条：节点序号 → 条目序号
            itemIndexOfNode = { nodeStartItem[it.coerceIn(0, nodes.size)] },
        )
        // 2026-11-21 用户「停手时还是会顿一下」实测：停手后 0~100ms 内**没有**长帧，
        // 长帧出现在 t≈168ms / 351ms —— 正是这个调试浮层挂载/绘制的那两帧（25ms）。
        // 所以浮层默认**不挂**（数据照样写 files/scrolltrace.txt，需要读数去取），
        // 别让调试面板冒充产品缺陷。要看面板时把 ScrollTrace.showOverlay 打开。
        if (io.github.m0use001.planability.BuildConfig.DEBUG &&
            io.github.m0use001.planability.core.ScrollTrace.showOverlay
        ) {
            lastTrace?.let { r ->
                val stallSnippet = remember(r, nodesNow) {
                    // 切条后 stallFi 是**条目**序号，转回节点序号再取摘要
                    val fi = r.stallFi
                    val ni = if (fi < 0) -1
                    else nodesNow.indices.lastOrNull { nodeStartItem[it] <= fi } ?: -1
                    nodesNow.getOrNull(ni)?.let { nd ->
                        when (nd) {
                            is ChatNode.Single -> nd.msg.content.take(14)
                            is ChatNode.Chain -> nd.msgs.lastOrNull()?.content?.take(14)
                            else -> null
                        }
                    }
                }
                ScrollTraceOverlay(r, stallSnippet, Modifier.align(Alignment.BottomStart))
            }
        }
        // 2026-11-08 用户「『最新』按钮显示延迟」：原来跟着 autoFollow 走，而 autoFollow
        // 要等**滚动彻底停止**才置 false——长距离惯性滚动期间胶囊一直不出现，看起来就是"延迟"。
        // 改成**位置判定**：只要视口离了底部（!atBottom，含惯性中）就显示，跟随中（钉底）
        // 自然是 atBottom=true 不显示。autoFollow 仍负责"要不要跟"，两者各管各的。
        if (showLatestPill) {
            Row(
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 2.dp)
                    .height(26.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .border(
                        0.5.dp,
                        MaterialTheme.colorScheme.outlineVariant,
                        CircleShape
                    )
                    // 2026-10-31：删掉重复的 FAB 后，这是唯一的「回到最新」入口，补一记 Tap
                    // （导航类动作；原 FAB 用 Select，与返回键/展开块的 Tap 口径对齐成 Tap）
                    .clickable {
                        haptics.perform(HapticEngine.Kind.Tap)
                        // 点了就是"我要回到最新"：先把跟随状态打开，再滚过去
                        autoFollow = true
                        // 正序下「最新」= 列表末尾。2026-11-08 用户「点击后卡顿」：
                        // animateScrollToItem 是动画滚动，会把**中间几百个重型 markdown item
                        // 逐个组合出来**，列表越长越卡；scrollToItem 是瞬移，只组合目标窗口
                        // 的几项——与流式跟随同款，也和"打断零成本"的口径一致。
                        // 2026-11-11 用户「有时候不能直达底部，会卡住」：一次 snap 不保险——
                        // ①点击瞬间 totalItemsCount 可能已过期（流式/异步消息恰在进场），
                        //   snap 停在旧末尾、canScrollForward 仍真，胶囊不消失；而上面 1736 的
                        //   effect 看到「滚动结束且不在底」还会把 autoFollow 翻回 false，
                        //   结果就是「点了没到底、也不跟了」的卡住观感；
                        // ②末项是短行（LiveToolRow/系统痕迹行）时，snap 的钳制同样可能
                        //   留下残余可滚距离。修：贴底以 **!canScrollForward** 为准，
                        //   没到就按**最新计数**再 snap（最多 3 轮）；末轮仍差一口气就
                        //   正向 scrollBy 钳到绝对末端（只跨残余像素，不组合新 item）。
                        scope.launch {
                            runCatching { snapToBottom() }
                        }
                    }
                    .padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Icon(
                    Icons.Rounded.ArrowDownward,
                    contentDescription = null,
                    modifier = Modifier.size(13.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    "最新",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * v14：`internal` 而不是 `private` —— 悬浮窗的输出窗要复用**同一套**气泡渲染，
 * 不能另写一份简化版，否则主窗和浮窗对同一条消息的画法会慢慢分叉。
 * 回调全部由调用方给；浮窗只需要长按和打开文件，其余传空实现。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun MessageBubble(
    msg: ChatMessage,
    isLastMsg: Boolean,
    onLongPress: (ChatMessage) -> Unit,
    userTexts: List<String>,
    onSendCard: (String) -> Unit,
    onExportCode: (String, String) -> Unit,
    onOpenFile: (String) -> Unit,
    stale: Boolean = false,
    /**
     * 整轮是否还在跑（= 会话页 state.streaming）。2026-11-08 用户「中途突然闪了一下
     * 复制/重新生成，即使没输出完」：多工具轮里每轮之间会把上一行 finalize 成 done、
     * 再插新的 streaming 行——那个空档里 `msg.status == "streaming"` 已经是 false，
     * 于是操作行闪现一下。**判定要用"整轮"而不是"这一行"**。
     */
    turnRunning: Boolean = false,
    /**
     * 2026-11-11：本轮正在生成哪个工具的调用参数（ChatUiState.callingTool）。
     * 非空时，空消息行的「思考中」升级为「正在调用 X · N 秒」——参数流式生成
     * 可能长达一两分钟（本地模型），此前这段时间界面没有工具名，用户以为卡死。
     */
    callingTool: String = "",
    /**
     * 2026-11-19 平板批：助手正文列的**自限宽上限**。
     * · null = 不自限宽（会话页：限宽由消息列表统一做，ChatWideLayout.ContentMaxWidth，
     *   工具行/过程链/回执行与正文共用同一条列，左缘自然对齐）；
     * · 默认 760dp = 自限宽居中（桌面浮窗 FloatChatActivity 复用本气泡时保留原行为）。
     */
    contentMaxWidth: androidx.compose.ui.unit.Dp? = 760.dp,
    /**
     * 超长消息切片（2026-11-21，见 ChatMessageParts.kt）：本片要渲染的显示段。
     * null = 老路径（自己 `ChatComp.parse(msg.content)`）。
     *
     * 为什么切片：LazyColumn 的条目测量是原子的，一条 7298px（≈7 屏）的回复
     * 进场要在一帧里量完（实测 20~50ms）。切成多片后每帧只量刚进场的那一两片。
     * 推理块/工具块只在首片渲染，操作行与「已停止生成」只在末片。
     */
    partSegs: List<ChatSegment>? = null,
    partIndex: Int = 0,
    partCount: Int = 1,
) {
    val isFirstPart = partIndex == 0
    val isLastPart = partIndex >= partCount - 1
    val scope = rememberCoroutineScope()
    val sessionId = msg.sessionId
    val streaming = msg.status == "streaming"
    if (msg.role == "tool") {
        ToolResultRow(msg, onOpenFile)
        return
    }
    if (msg.role == "system") {
        // 痕迹行（如"已切换模型：X"）：居中小字，不进 API 上下文
        Text(
            msg.content,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
        )
        return
    }
    val longPress = if (streaming) ({}) else ({ onLongPress(msg) })
    if (msg.role == "user") {
        // 2026-10-31 附件更优雅：正文里的 `[附件已附: … 请用 … 读取]` 是给模型的指令，
        // 气泡里只显示人该看的文字；附件单独渲染成 chip（见 splitAttachmentMarks）。
        val (userText, attachPaths) = remember(msg.content) { splitAttachmentMarks(msg.content) }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            // 2026-11-20：用户气泡是 Surface(shape=16,16,4,16)，但 combinedClickable 挂在
            // Surface 的 modifier 上——Surface 的内部形状裁剪在链的**内侧**，水波画在裁剪之外，
            // 于是气泡一按就是一个直角方块。自己先 clip 同形状再挂点击。
            val bubbleShape = RoundedCornerShape(16.dp, 16.dp, 4.dp, 16.dp)
            // 2026-11-21 真机实测：列表层挂选区容器后，用户气泡长按会**同时**弹出
            // 「复制/全选/朗读/搜索」系统文本条和我们的「消息操作」面板——选区把手还画在
            // 气泡上，观感是两个菜单打架。用户气泡要退出选区（长按=操作菜单）。
            // Compose 的 LocalSelectionRegistrar 在 Kotlin 层是 internal，只能反射拿。
            val userBubble: @Composable () -> Unit = {
                Surface(
                    color = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    shape = bubbleShape,
                    modifier = Modifier.clip(bubbleShape).combinedClickable(onClick = {}, onLongClick = longPress)
                ) {
                    Column(Modifier.padding(horizontal = 12.dp, vertical = 9.dp)) {
                        if (userText.isNotBlank()) {
                            Text(userText, style = MaterialTheme.typography.bodyLarge)
                        }
                        if (attachPaths.isNotEmpty()) {
                            if (userText.isNotBlank()) Spacer(Modifier.height(6.dp))
                            AttachmentChips(attachPaths, onOpenFile)
                        }
                    }
                }
            }
            val noSelection = noSelectionRegistrar
            androidx.compose.runtime.CompositionLocalProvider(noSelection provides null) { userBubble() }
        }
        return
    }
    // assistant：全宽排版。2026-11-04 用户「长按就进光标选文字状态，支持引用到下一轮」：
    // 曾把正文包进 SelectionContainer（长按=选词），因 283 叠印两度回滚（见下方正文处注释）；
    // 长按弹操作菜单 2026-11-08 已回接（长按=复制/引用/重新生成/删除）。
    val body: @Composable () -> Unit = {
        if (isFirstPart && msg.reasoning.isNotBlank()) ReasoningBlock(msg)
        if (isFirstPart && msg.toolCallsJson.isNotBlank()) ToolCallsBlock(msg.toolCallsJson)
        when {
            // 切片渲染：只铺本片自己的段（状态/推理/工具行已在上面按片过滤）
            partSegs != null -> {
                partSegs.forEach { seg ->
                    when (seg) {
                        is ChatSegment.Md ->
                            MarkdownView(
                                seg.text,
                                chatCodeBar = true,
                                onExportCode = onExportCode,
                                selectable = false
                            )
                        is ChatSegment.Card ->
                            ComponentCard(
                                seg, msg.content, userTexts, onSendCard, onOpenFile,
                                onTrace = { line ->
                                    scope.launch { ChatEngine.appendToMessage(msg.sessionId, msg.id, line) }
                                },
                                stale = stale
                            )
                    }
                }
                if (isLastPart && msg.status == "stopped") {
                    Text(
                        "已停止生成",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            msg.status == "streaming" -> {
                if (msg.content.isEmpty()) {
                    ThinkingRow(callingTool, msg.createdAt)
                } else {
                    // v10：写完一段渲染一段——完整段落实时走 MarkdownView，未写完的尾巴纯文本+光标
                    val (stable, tail) = splitStreamingContent(msg.content)
                    if (stable.isNotBlank()) {
                        MarkdownView(stable, chatCodeBar = false)
                    }
                    if (tail.isNotEmpty() || stable.isBlank()) {
                        Text(tail + " ▌", style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
            msg.status == "error" -> {
                Card(
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Text(
                        "生成失败：${msg.error.ifBlank { "未知错误" }}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(10.dp)
                    )
                }
            }
            else -> {
                if (msg.content.isNotBlank()) {
                    // 2026-11-11 掉帧修：ChatComp.parse 是逐行扫描+正则，原来每次重组都重算
                    //（流式起止 turnRunning 翻转会把**所有可见气泡**各重组一遍）。记忆化后
                    // 内容不变就零成本。注意 key 用 msg.content（留痕行剥离在 parse 内部）。
                    remember(msg.content) { ChatComp.parse(msg.content) }.forEach { seg ->
                        when (seg) {
                            is ChatSegment.Md ->
                                // 2026-11-21：选词容器上提到列表层（跨片连续拖选），
                                // 这里不再逐段包 SelectionContainer。
                                MarkdownView(
                                    seg.text,
                                    chatCodeBar = true,
                                    onExportCode = onExportCode,
                                    selectable = false
                                )
                            is ChatSegment.Card ->
                                ComponentCard(
                                    seg, msg.content, userTexts, onSendCard, onOpenFile,
                                    onTrace = { line ->
                                        scope.launch { ChatEngine.appendToMessage(msg.sessionId, msg.id, line) }
                                    },
                                    stale = stale
                                )
                        }
                    }
                    if (msg.status == "stopped") {
                        Text(
                            "已停止生成",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
    // v57 平板：assistant 正文限宽 760dp 并居中——平板 1000dp+ 宽屏上一行 Markdown 会拉出
    // 上百字符的行长；手机宽度 <760dp，这一串是无条件 no-op。
    //
    // 2026-11-19 平板批：会话页把限宽**上提**到消息列表（ChatWideLayout.ContentMaxWidth），
    // 这里传 contentMaxWidth = null —— 否则正文会先在自己这里收 760dp 再居中一次，
    // 与同列的工具行/过程链左缘错开（用户「会话内容不整齐」）。
    // 桌面浮窗（FloatChatActivity）复用本气泡时仍走默认 760dp 自限宽。
    Column(
        if (contentMaxWidth != null) {
            Modifier.fillMaxWidth()
                .wrapContentWidth(Alignment.CenterHorizontally)
                .widthIn(max = contentMaxWidth)
        } else {
            Modifier.fillMaxWidth()
        }
    ) {
        // 流式期间内容每帧都在变，不给选词（会不停重置选区）
        // 2026-11-05 **回滚长按选词**：重新包 SelectionContainer 后，模拟器实拍复现了
        // 会话 283 的「双层叠印」——AI 正文两段文字叠着画（"好的，按四步推进"与
        // "收集上周的实验数据"重叠成一行，截图在 tmp/wf_panel.png 顶部可见）。
        // 283 的因果至此确认：就是 SelectionContainer 对 markdown 分块渲染的干扰，
        // 不是 283 特有。在 Compose 修掉之前不再尝试（只包单条 Text 未验证，大概率同病）。
        // 2026-11-08 用户「长按 AI 回复要进文字选择（像系统那样拖选+复制条）」：
        // 长按选词挂在 **Md 段**这一级（见上面 ChatSegment.Md），不包整个 body()——
        // 整块包（combinedClickable / SelectionContainer 都试过，21:12/21:26 装机对照）
        // 会让 qa 卡、表格和正文互相叠印。操作菜单仍在末条的「⋯」行。
        body()
        // 操作行放在选区容器外：按钮要能点，不能被选词手势吃掉
        if (msg.status == "error") {
            if (isLastPart) MiniActionsRow(
                msg, showRegenerate = true,
                onRegenerate = { scope.launch { ChatEngine.regenerate(sessionId) } },
                onMore = { onLongPress(msg) }
            )
        } else if (isLastPart && !streaming && !turnRunning && isLastMsg && msg.content.isNotBlank()) {
            MiniActionsRow(
                msg,
                showRegenerate = msg.status == "done" || msg.status == "stopped",
                onRegenerate = { scope.launch { ChatEngine.regenerate(sessionId) } },
                onMore = { onLongPress(msg) }
            )
        }
    }
}

// ==================== 选词工具条（2026-11-04：长按选词 + 引用到下一轮） ====================

/**
 * **当前未接线**（保留实现备查）。Compose 1.12 的选区工具条改走
 * `PlatformSelectionBehaviorsImpl`（系统浮动菜单：复制/全选/朗读/搜索），
 * 即使 `SelectionContainer` 读到了 `LocalTextToolbar`，实际弹出的仍是平台菜单，
 * 自定义动作（引用）挂不上去——真机实测（13:15 长按 AI 回复）确认。
 * 所以「引用」入口改挂在消息的「⋯」→ 引用选中/引用本条（见 MessageActionSheet），
 * 长按选词 + 系统「复制」仍然可用。若将来 Compose 恢复 TextToolbar 通道，这个对象可以直接接回。
 *
 * 说明为什么当时这么写：TextToolbar 的 `showMenu` 回调**不带选中文本**，
 * 唯一现成通道是借「复制」把选区写进剪贴板再读回来（Android ClipboardManager 同步写，同帧可读）。
 */
private data class QuoteMenu(
    val rect: androidx.compose.ui.geometry.Rect,
    val onCopy: () -> Unit,
    val onSelectAll: () -> Unit,
    val onQuote: () -> Unit
)

@Composable
private fun rememberQuoteSelectionToolbar(onQuote: (String) -> Unit): androidx.compose.ui.platform.TextToolbar {
    val menu = remember { mutableStateOf<QuoteMenu?>(null) }
    val clipboard = LocalClipboardManager.current
    val toolbar = remember {
        object : androidx.compose.ui.platform.TextToolbar {
            override val status: androidx.compose.ui.platform.TextToolbarStatus
                get() = if (menu.value != null) androidx.compose.ui.platform.TextToolbarStatus.Shown
                else androidx.compose.ui.platform.TextToolbarStatus.Hidden

            override fun hide() { menu.value = null }

            override fun showMenu(
                rect: androidx.compose.ui.geometry.Rect,
                onCopyRequested: (() -> Unit)?,
                onPasteRequested: (() -> Unit)?,
                onCutRequested: (() -> Unit)?,
                onSelectAllRequested: (() -> Unit)?,
                onAutofillRequested: (() -> Unit)?
            ) {
                menu.value = QuoteMenu(
                    rect = rect,
                    onCopy = { onCopyRequested?.invoke() },
                    onSelectAll = { onSelectAllRequested?.invoke() },
                    onQuote = {
                        onCopyRequested?.invoke()
                        menu.value = null
                        val txt = runCatching { clipboard.getText()?.text }.getOrNull()
                        if (!txt.isNullOrBlank()) onQuote(txt.trim())
                    }
                )
            }

            override fun showMenu(
                rect: androidx.compose.ui.geometry.Rect,
                onCopyRequested: (() -> Unit)?,
                onPasteRequested: (() -> Unit)?,
                onCutRequested: (() -> Unit)?,
                onSelectAllRequested: (() -> Unit)?
            ) {
                showMenu(rect, onCopyRequested, onPasteRequested, onCutRequested, onSelectAllRequested, null)
            }
        }
    }
    menu.value?.let { m ->
        androidx.compose.ui.window.Popup(
            popupPositionProvider = remember(m) {
                object : androidx.compose.ui.window.PopupPositionProvider {
                    override fun calculatePosition(
                        anchorBounds: androidx.compose.ui.unit.IntRect,
                        windowSize: androidx.compose.ui.unit.IntSize,
                        layoutDirection: androidx.compose.ui.unit.LayoutDirection,
                        popupContentSize: androidx.compose.ui.unit.IntSize
                    ): androidx.compose.ui.unit.IntOffset {
                        val x = (m.rect.center.x - popupContentSize.width / 2f).toInt()
                            .coerceIn(8, (windowSize.width - popupContentSize.width - 8).coerceAtLeast(8))
                        val y = (m.rect.top - popupContentSize.height - 8).toInt().coerceAtLeast(8)
                        return androidx.compose.ui.unit.IntOffset(x, y)
                    }
                }
            },
            onDismissRequest = { menu.value = null }
        ) {
            androidx.compose.material3.Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                shadowElevation = 8.dp,
                tonalElevation = 3.dp
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 4.dp)) {
                    TextButton(onClick = { m.onQuote() }) {
                        Text("引用", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                    }
                    TextButton(onClick = { m.onCopy(); menu.value = null }) {
                        Text("复制", style = MaterialTheme.typography.labelLarge)
                    }
                    TextButton(onClick = { m.onSelectAll(); menu.value = null }) {
                        Text("全选", style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }
    }
    return toolbar
}

/** assistant 消息常驻小操作行：复制 + （末条）重新生成 + 更多（长按菜单在这里找回） */
@Composable
private fun MiniActionsRow(
    msg: ChatMessage,
    showRegenerate: Boolean,
    onRegenerate: () -> Unit,
    onMore: (() -> Unit)? = null
) {
    val haptics = LocalHaptics.current
    val ctx = androidx.compose.ui.platform.LocalContext.current
    Row(Modifier.padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        TextButton(
            onClick = {
                // 2026-11-12 用户「复制点了但没到剪切板」：写后读回校验——ColorOS 的
                // 焦点策略会静默拒绝（Dialog 窗口高发），失败必须弹人话而不是假「已复制」
                val err = io.github.m0use001.planability.core.ClipboardCompat.copy(ctx, msg.content)
                haptics.perform(if (err == null) HapticEngine.Kind.Success else HapticEngine.Kind.Error)
                Toast.makeText(ctx, err ?: "已复制", Toast.LENGTH_SHORT).show()
            },
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 0.dp)
        ) {
            Icon(Icons.Rounded.ContentCopy, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(4.dp))
            Text("复制", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (showRegenerate) {
            TextButton(
                onClick = onRegenerate,
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 0.dp)
            ) {
                // 主操作文字按钮（重新生成）→ 图标与文字同为主色
                Icon(Icons.Rounded.Refresh, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(4.dp))
                Text("重新生成", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            }
        }
        // 2026-11-04：长按改成选词了，原长按菜单（重试/删除）必须另留入口
        if (onMore != null) {
            TextButton(
                onClick = { haptics.perform(HapticEngine.Kind.Tap); onMore() },
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 0.dp)
            ) {
                Icon(Icons.Rounded.MoreVert, "更多", Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

// ==================== M4 工具块 / 结果行 / 审批卡 ====================

/** 过程链的一次工具调用（id 匹配回执，名称与参数用于展示） */
internal data class ChainCall(val id: String, val name: String, val args: String)

/** 回执的一行摘要（"返回 · 查到 14 条日程"），完整内容展开后看 */
private fun resultSummary(r: ChatMessage): String {
    val body = r.content
        .removePrefix("[OK] ").removePrefix("[FAIL] ").removePrefix("[DENIED] ")
    val firstLine = body.lineSequence().firstOrNull().orEmpty().trim()
    return if (firstLine.length > 24) firstLine.take(24) + "…" else firstLine
}

/** 从一串过程消息里抽出工具调用（id + 名称 + 参数原文），供过程链渲染 */
internal fun parseChainCalls(msgs: List<ChatMessage>): List<ChainCall> =
    msgs.flatMap { m ->
        if (m.toolCallsJson.isBlank()) return@flatMap emptyList()
        val arr = runCatching { org.json.JSONArray(m.toolCallsJson) }.getOrNull()
            ?: return@flatMap emptyList()
        (0 until arr.length()).mapNotNull { i ->
            val obj = arr.optJSONObject(i) ?: return@mapNotNull null
            val fn = obj.optJSONObject("function") ?: return@mapNotNull null
            ChainCall(obj.optString("id"), fn.optString("name"), fn.optString("arguments"))
        }
    }

/**
 * 「过程链」容器（2026-10-31，借鉴 Operit 的分组渲染思路）。
 *
 * 一次动作在数据上其实是三条消息（assistant 带 toolCallsJson、若干 role=tool 回执），
 * 用户眼里却是**一件事**。以前三条各渲染各的块：三个「详情」入口、三套独立折叠状态，
 * 展开一层还得再展开一层。
 *
 * 2026-10-31 用户定稿「真链」：链本身不折叠——每一步一行客观记录（思考 N 字 / 查阅 N 次），
 * 点某一行才展开这一步的实际内容（二级）。不再显示工具回执正文（用户：不显示返回内容）。
 * 也不加容器底色：链是旁注，回答正文才是视觉主体。
 */
/**
 * 工具轮执行中的活进度行（2026-11-01）：委托句（带正文）不满足过程链分组条件，
 * 执行期间链块不存在、界面完全静止——ask_expert 跑 69 秒用户以为调用失败了。
 * 这行显示首个未完成调用的工具名 + 秒数（每秒跳），回执落齐后整行自动消失，
 * 由正常的过程链接管。
 */
@Composable
// 2026-11-12：浮窗会话也要挂这行（执行期浮窗此前整窗静止，看起来像卡住）——
// 同包同模块，internal 让 FloatChatActivity 复用同一份画法
internal fun LiveToolRow(head: ChatMessage, msgs: List<ChatMessage>, callingTool: String = "") {
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(head.id) {
        while (true) {
            kotlinx.coroutines.delay(1000)
            tick++
        }
    }
    val done = remember(msgs.size) {
        msgs.filter { it.role == "tool" }.mapNotNull { it.toolCallId }.toSet()
    }
    val calls = remember(head.id) { parseChainCalls(listOf(head)) }
    val pending = calls.firstOrNull { it.id !in done }
    // 2026-11-14：**参数生成期**（调用还没落、调用名已到）也要有活行——AI 写文件时把
    // 上千字正文当参数吐出来，行内已有正文、ThinkingRow 不显示，界面只剩光标（用户
    // 「以为卡死了」）。这时 calls 还是空的，标签取引擎的 callingTool。
    val argsPhase = calls.isEmpty() && callingTool.isNotBlank()
    val label = toolLabel(pending?.name ?: calls.firstOrNull()?.name ?: callingTool)
    val secs = run {
        tick   // 订阅：只写不读的 State 不触发重组，秒数会冻死（2026-11-11 真机实报）
        ((System.currentTimeMillis() - head.createdAt) / 1000L).coerceAtLeast(0L)
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
    ) {
        // v47.1 用户「多智能体协作要加加载动画与实时工作时长」：文字行前挂活转圈
        // （形状 morph 循环，比静态文字明显"在工作"）
        androidx.compose.material3.LoadingIndicator(
            Modifier.size(14.dp),
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.width(6.dp))
        Text(
            // 参数生成期说清是"正在准备参数"——用户看了一眼就知道没卡死
            if (argsPhase) "⚙ 正在准备 $label · $secs 秒" else "⚙ $label · $secs 秒",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
    }
}

/**
 * v47.1：**多智能体协作活行**（ask_expert / deep_plan 运行中）。
 *
 * 为什么要在聊天列表里再放一行：运行面板是手动打开的，协作一跑几十秒，用户盯着聊天
 * 界面时完全没有任何信号（"以为调用失败"）。这行给三样东西：
 * 加载动画（LoadingIndicator）+ 第 k/n 步 + 从 startedAt 起跳的实时秒数；
 * 步骤名（currentLabel）跟在后面，长了下省略。
 * 工具轮期间（streaming + toolCallsJson）优先显示更细的 LiveToolRow，这行兜住其余时段。
 */
@Composable
private fun LiveAgentRow(run: io.github.m0use001.planability.ai.ptl.SubAgentRun) {
    // tick 只为了每秒重组一次（秒数由当前时间算，不累加，进程切挂也不漂）
    var tick by remember(run.id) { mutableIntStateOf(0) }
    LaunchedEffect(run.id) {
        while (true) {
            kotlinx.coroutines.delay(1000)
            tick++
        }
    }
    val secs = run {
        tick   // 订阅：只写不读的 State 不触发重组，秒数会冻死（2026-11-11 真机实报）
        ((System.currentTimeMillis() - run.startedAt) / 1000L).coerceAtLeast(0L)
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
    ) {
        androidx.compose.material3.LoadingIndicator(
            Modifier.size(14.dp),
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.width(6.dp))
        Text(
            "🤝 ${run.title} · 第 ${run.stepNo}/${run.total} 步 · $secs 秒" +
                (run.currentLabel?.let { " · $it" } ?: ""),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun ProcessChainBlock(
    msgs: List<ChatMessage>,
    onOpenFile: (String) -> Unit,
    active: Boolean = false
) {
    val haptics = LocalHaptics.current
    val reasoning = msgs.firstOrNull { it.reasoning.isNotBlank() }?.reasoning.orEmpty()
    val calls = remember(msgs) { parseChainCalls(msgs) }
    val results = msgs.filter { it.role == "tool" }
    // 2026-11-01 用户：过程链**默认收起，只显示工作耗时**——展开才看得到逐条步骤。
    var chainOpen by remember(msgs.first().id) { mutableStateOf(false) }
    // 哪一步展开着（-1 = 全收起）。二级展开：链始终可见，内容按需展开。
    var openStep by remember(msgs.first().id) { mutableIntStateOf(-1) }
    // 返回内容再深一层：点「返回 …」才展开某次调用的回执正文
    var openResultId by remember(msgs.first().id) { mutableStateOf<String?>(null) }
    val thinkingIndex = if (reasoning.isNotBlank()) 0 else -1
    val toolsIndex = when {
        calls.isEmpty() -> -1
        thinkingIndex == 0 -> 1
        else -> 0
    }
    // 工作耗时：这条链从第一句到最后的回执之间的跨度（毫秒）
    val spanMs = remember(msgs) {
        (msgs.maxOfOrNull { it.createdAt } ?: 0L) - (msgs.minOfOrNull { it.createdAt } ?: 0L)
    }
    val workLabel = if (spanMs <= 0L) "已工作" else "已工作 ${(spanMs / 1000L).coerceAtLeast(0L)} 秒"
    // 2026-11-12 用户实录（「工具运行时还是缺运行中动画与计时，看起来跟卡了一样」）：
    // 链头「已工作 N 秒」的 N 是**已落库消息之间的跨度**（remember(msgs) 冻结缓存），
    // 工具执行期间数字不跳、图标也静态——而带动画+计时的 LiveToolRow 挂在列表最底部，
    // 用户往上翻就看不见。活跃轮改为：链头挂 LoadingIndicator + 从链首到**当前时刻**
    // 的实时秒数（每秒跳）；链结束后回到冻结跨度（active=false，tick 停）。
    var tick by remember(msgs.first().id) { mutableIntStateOf(0) }
    LaunchedEffect(active) {
        if (active) while (true) { kotlinx.coroutines.delay(1000); tick++ }
    }
    val chainStart = remember(msgs) { msgs.minOfOrNull { it.createdAt } ?: 0L }
    val liveLabel = if (active) {
        tick   // 订阅：只写不读的 State 不触发重组，秒数会冻死（2026-11-11 真机实报同源）
        val s = ((System.currentTimeMillis() - chainStart) / 1000L).coerceAtLeast(1L)
        "正在工作 $s 秒"
    } else null

    Column(Modifier.fillMaxWidth()) {
        ChainStepRow(
            icon = Icons.Rounded.Schedule,
            label = liveLabel ?: workLabel,
            open = chainOpen,
            leading = if (liveLabel != null) {
                @Composable { LoadingIndicator(Modifier.size(14.dp), color = MaterialTheme.colorScheme.primary) }
            } else null,
            onClick = {
                haptics.perform(HapticEngine.Kind.Tap)
                chainOpen = !chainOpen
            }
        )
        AnimatedVisibility(chainOpen, enter = Motion.expandIn, exit = Motion.collapseOut) {
            Column(Modifier.padding(start = 22.dp)) {
        if (thinkingIndex >= 0) {
            ChainStepRow(
                icon = Icons.Rounded.Psychology,
                label = "思考 · ${reasoning.length} 字",
                open = openStep == thinkingIndex,
                onClick = {
                    haptics.perform(HapticEngine.Kind.Tap)
                    openStep = if (openStep == thinkingIndex) -1 else thinkingIndex
                }
            )
            AnimatedVisibility(openStep == thinkingIndex, enter = Motion.expandIn, exit = Motion.collapseOut) {
                ChainDetail {
                    Text(
                        reasoning.take(2000),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        if (toolsIndex >= 0) {
            val names = calls.map { toolLabel(it.name) }.distinct().joinToString("、")
            ChainStepRow(
                icon = Icons.Rounded.Build,
                label = "查阅 · ${calls.size} 次（$names）",
                open = openStep == toolsIndex,
                onClick = {
                    haptics.perform(HapticEngine.Kind.Tap)
                    openStep = if (openStep == toolsIndex) -1 else toolsIndex
                }
            )
            AnimatedVisibility(openStep == toolsIndex, enter = Motion.expandIn, exit = Motion.collapseOut) {
                ChainDetail {
                    calls.forEachIndexed { idx, call ->
                        if (idx > 0) Spacer(Modifier.height(8.dp))
                        Text(
                            toolLabel(call.name),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        val argLines = toolArgLines(call.name, call.args)
                        if (argLines.isNotBlank()) {
                            Text(
                                argLines,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        // 返回内容再深一层：点「返回」才展开（用户 2026-10-31：返回内容要多点一下）
                        val result = results.firstOrNull { r ->
                            val k = r.toolCallId
                            k == call.id || k.substringBefore('#') == call.id || k.substringAfterLast('#') == call.id
                        }
                        if (result != null) {
                            val expandedResult = openResultId == call.id
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .clickable {
                                        haptics.perform(HapticEngine.Kind.Tap)
                                        openResultId = if (expandedResult) null else call.id
                                    }
                                    .padding(top = 3.dp, bottom = 2.dp)
                            ) {
                                Text(
                                    if (expandedResult) "收起返回" else "返回 · ${resultSummary(result)}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Spacer(Modifier.width(3.dp))
                                Text(
                                    if (expandedResult) "⌄" else "›",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            AnimatedVisibility(
                                expandedResult,
                                enter = Motion.expandIn,
                                exit = Motion.collapseOut
                            ) {
                                // 2026-11-14 同 ToolResultRow：回执正文走 MarkdownView，
                                // 别再让 `**粗体**`/`$公式$`/表格原样裸显示。
                                MarkdownView(
                                    result.content
                                        .removePrefix("[OK] ").removePrefix("[FAIL] ")
                                        .removePrefix("[DENIED] ").take(2000)
                                )
                            }
                        }
                    }
                }
            }
        }
            }
        }
    }
}

/** 过程链的一行：图标 + 客观记录 + 展开箭头（无底色，见 ProcessChainBlock 说明） */
@Composable
private fun ChainStepRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    open: Boolean,
    onClick: () -> Unit,
    // 2026-11-12：活跃链的链头用 LoadingIndicator 换掉静态图标（运行中动画）；
    // 其余调用点不传 = 原样静态 Icon
    leading: (@Composable () -> Unit)? = null
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            // 2026-11-20：过程链/工具链行可点但没有底，水波会是一整条直角横杠——
            // 给 12dp 圆角裁剪（紧凑行口径）。
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 5.dp)
    ) {
        if (leading != null) {
            Box(Modifier.size(14.dp), contentAlignment = Alignment.Center) { leading() }
        } else {
            Icon(
                icon, null, Modifier.size(14.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Text(
            if (open) "⌄" else "›",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * 链上某一步 / 过程行展开后的详情容器：左侧一条细竖线表示从属，不套第二层容器。
 * 2026-11-20 降噪批起为「详情展开」的唯一形态：思考块、工具块、工具回执行的展开体都包这里，
 * 与过程链展开同款。
 */
@Composable
private fun ChainDetail(content: @Composable () -> Unit) {
    val line = MaterialTheme.colorScheme.outlineVariant
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 22.dp, top = 2.dp, bottom = 6.dp)
            .drawBehind {
                drawLine(
                    color = line,
                    start = androidx.compose.ui.geometry.Offset(0f, 0f),
                    end = androidx.compose.ui.geometry.Offset(0f, size.height),
                    strokeWidth = 1.dp.toPx()
                )
            }
            .padding(start = 10.dp)
    ) {
        Column(Modifier.weight(1f)) { content() }
    }
}

/**
 * 过程行统一安静款（2026-11-20 用户「对话流又密又乱」降噪批）：
 * 思考块 / 工具调用块的收起态与过程链 ChainStepRow 同款——无底色、14dp 中性图标 +
 * labelSmall 灰字、右侧 ⌄/› 箭头，「详情」字样退场。此前同类信息有两种画法
 * （消息内带底色横条 vs 列表层无底色行）交替堆叠，是视觉噪音主源。
 * open = null 表示流式进行中不出箭头，否则决定箭头方向。
 * （刻意不与 ChainStepRow 合并：其函数体被 ChatToolRunLiveTest 逐字钉住，动它风险大。）
 */
@Composable
private fun ProcessStepRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    open: Boolean?,
    onClick: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            // 2026-11-20：过程链/工具链行可点但没有底，水波会是一整条直角横杠——
            // 给 12dp 圆角裁剪（紧凑行口径）。
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 5.dp)
    ) {
        Icon(icon, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(8.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        if (open != null) {
            Text(
                if (open) "⌄" else "›",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
/** assistant 消息携带的工具调用块（折叠展开） */
@Composable
private fun ToolCallsBlock(callsJson: String) {
    var expanded by remember { mutableStateOf(false) }
    // 展开/收起 = 中性轻触（Tap）：不是"选中"，也不是不可逆动作，就是"点开看看"
    val haptics = LocalHaptics.current
    val calls = runCatching { org.json.JSONArray(callsJson) }.getOrNull() ?: return
    if (calls.length() == 0) return
    val names = (0 until calls.length()).mapNotNull { i ->
        calls.optJSONObject(i)?.optJSONObject("function")?.optString("name")
    }
    // 2026-11-20 降噪批（用户「对话流又密又乱」）：secondaryContainer 底色横条退役，
    // 收起态改走 ProcessStepRow（与过程链 ChainStepRow 同款安静行），
    // 展开体改 ChainDetail 旁注——同类信息一种长相，正文才是视觉主体。
    Column(Modifier.fillMaxWidth()) {
        ProcessStepRow(
            icon = Icons.Rounded.Build,
            // 工具名太长单行省略（2026-11-04：长名字不许把尾部 affordance 顶成换行，
            // 该约束已内化在 ProcessStepRow 的 weight(1f) 单行省略里）
            label = names.map { toolLabel(it) }.distinct().joinToString("、").ifBlank { "工具调用" },
            open = expanded,
            onClick = { haptics.perform(HapticEngine.Kind.Tap); expanded = !expanded }
        )
        // 展开/收起走 Motion.expandIn / collapseOut：被下方内容**推**开而不是跳开（2026-10-31）。
        AnimatedVisibility(expanded, enter = Motion.expandIn, exit = Motion.collapseOut) {
            ChainDetail {
                (0 until calls.length()).forEach { i ->
                    val fn = calls.optJSONObject(i)?.optJSONObject("function") ?: return@forEach
                    val name = fn.optString("name")
                    // 2026-10-31 参数人话化：复用 toolArgLines（中文键：值逐行），不甩原始 JSON
                    if (i > 0) Spacer(Modifier.height(6.dp))
                    Text(
                        toolLabel(name),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    val args = toolArgLines(name, fn.optString("arguments"))
                    if (args.isNotBlank()) {
                        Text(
                            args,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.9f)
                        )
                    }
                }
            }
        }
    }
    Spacer(Modifier.height(8.dp))
}

/**
 * 从工具回执里挑出「写出来的文件」路径（exports/xxx.docx、attachments/xxx.png）。
 *
 * 只认能应用内预览的扩展名：路径挑错了，「打开」点下去还是弹一句"不能预览"，
 * 比没有按钮更糟。工具回执形如：
 * `[OK] 已导出 exports/示例文档.docx（12 KB，由手机端生成（朴素版式）），可在…`
 */
internal fun toolWrittenPath(content: String): String? {
    if (!content.startsWith("[OK]")) return null
    val m = Regex("""(?:exports|attachments)/[^\s（()）,，。；;"'<>]+""").find(content) ?: return null
    val path = m.value.trimEnd('.', '，', '。', '）', ')', '、')
    val ext = path.substringAfterLast('.', "").lowercase()
    // 用同一份文件类型真相判断：文本类（含 .md/.htm/.json…）+ 办公三件套/pdf 都给「打开」入口，
    // 之前写死了一小串扩展名，结果是刚生成的 .htm 没有出口（真机 2026-10-20 看到只有「撤销」）。
    return if (io.github.m0use001.planability.core.FileKinds.isText(path) ||
        ext in setOf("docx", "xlsx", "pptx", "pdf")
    ) path else null
}

/**
 * 从用户消息正文里剥出附件协议标记（2026-10-31 用户「附件更优雅」）。
 *
 * 发送时为了把附件喂给模型，正文尾部会拼上
 * `[附件已附: attachments/xxx.png 请用 file_read_image 读取]`——这是**给模型的指令**，
 * 不该出现在人的气泡里（实拍：气泡里一坨断行的英文路径与工具名）。
 * 渲染侧剥出来单独显示成附件 chip，存量消息零迁移也能变干净。
 *
 * 返回 (纯正文, 附件相对路径列表)。没有标记时原样返回、列表为空。
 */
internal fun splitAttachmentMarks(content: String): Pair<String, List<String>> {
    // 文件名里可能有空格（safeName 只替换了 / 和 \），所以路径用惰性匹配到 " 请用 " 为止
    val marks = Regex("""\[附件已附:\s*(.+?)\s+请用\s+\S+\s+读取]""")
    val paths = marks.findAll(content).map { it.groupValues[1].trim() }.toList()
    if (paths.isEmpty()) return content to emptyList()
    val text = content.replace(marks, "").replace(Regex("[ \t]{2,}"), " ").trim()
    return text to paths
}

/** 用户气泡内的附件 chip 列表（图片给缩略图，其它给类型图标；点击走应用内预览） */
@Composable
private fun AttachmentChips(paths: List<String>, onOpen: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        paths.forEach { AttachmentChip(it, onOpen) }
    }
}

/** 附件是否图片（缩略图与预览带共用同一判定） */
private fun isImageAttachment(name: String): Boolean =
    Regex("(?i)\\.(png|jpe?g|webp|gif|bmp|heic)$").containsMatchIn(name)

/**
 * 采样解码附件缩略图（IO 线程，最大边约 [maxSide] px）。
 * 大图直接在组合期 decode 会掉帧，所以统一走 produceState；解不出返回 null，调用方退化成图标。
 */
@Composable
private fun attachmentThumbnail(relPath: String, isImage: Boolean, maxSide: Int = 96): android.graphics.Bitmap? {
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

/**
 * 附件的类型图标（2026-11-01 用户「文件显示类型图标，图片展示小图」）。
 * 图片走缩略图，这里只服务非图片；同一个文件类型在全 App 用同一个图标，别处要加就复用这张表。
 */
private fun attachmentTypeIcon(name: String): androidx.compose.ui.graphics.vector.ImageVector {
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

@Composable
private fun AttachmentChip(path: String, onOpen: (String) -> Unit) {
    // 导入附件时的存储名形如 file_<ts>_原名.png；展示时去掉时间戳前缀，用户才认得
    val name = remember(path) {
        path.substringAfterLast('/').replace(Regex("^file_\\d+_"), "")
    }
    val isImage = remember(name) { isImageAttachment(name) }
    val thumb = attachmentThumbnail(path, isImage)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            // onPrimary 上的半透明层：气泡底色是主色，用同色低透明度叠出分区感
            .background(MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.14f))
            .clickable { onOpen(path) }
            .padding(horizontal = 6.dp, vertical = 4.dp)
    ) {
        // 2026-11-01 用户「图片附件不要显示名称」：图片只给缩略图，名字留给文件类。
        if (isImage) {
            if (thumb != null) {
                Image(
                    bitmap = thumb.asImageBitmap(),
                    contentDescription = name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(40.dp).clip(RoundedCornerShape(6.dp))
                )
            } else {
                Icon(
                    attachmentTypeIcon(name), null,
                    Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.85f)
                )
            }
        } else {
            Icon(
                attachmentTypeIcon(name), null,
                Modifier.size(14.dp),
                tint = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.85f)
            )
            Spacer(Modifier.width(6.dp))
            Text(
                name,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 180.dp)
            )
        }
    }
}

/**
 * 待发送附件的横向预览带（2026-10-31）：图片给 56dp 缩略图，其它给文件卡；
 * 右上角 × 移除。替换原来一排只有小图标+文件名的小 chips——发图前能看清是哪张。
 */
@Composable
private fun PendingAttachmentStrip(
    files: List<PendingAttachment>,
    onOpen: (String) -> Unit,
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
            val isImage = remember(pf.name) { isImageAttachment(pf.name) }
            val thumb = attachmentThumbnail(pf.relPath, isImage, maxSide = 128)
            Box(Modifier.padding(end = 8.dp)) {
                if (thumb != null) {
                    Image(
                        bitmap = thumb.asImageBitmap(),
                        contentDescription = pf.name,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(56.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .clickable { onOpen(pf.relPath) }
                    )
                } else {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .height(56.dp)
                            .widthIn(max = 200.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                            .clickable { onOpen(pf.relPath) }
                            .padding(horizontal = 10.dp)
                    ) {
                        Icon(
                            attachmentTypeIcon(pf.name), null,
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
                // 移除角标：压在缩略图右上角，点击区域 18dp 但外围留白在卡片内，不挡预览点击
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

/**
 * 把工具调用参数格式化成「中文键：值」逐行文本（2026-10-31）。
 *
 * 原来工具块展开后直接把 `arguments` 原始 JSON 甩给用户看
 * （`schedule_list({"from":"2026-10-31","to":"2026-12-20"})`）——审批卡早就做过人话化，
 * 这里复用同一份逻辑，避免两处口径漂移。
 */
internal fun toolArgLines(toolName: String, argsJson: String): String = runCatching {
    val def = io.github.m0use001.planability.ai.ptl.ToolRegistry.byName(toolName)
    val cnParam: Map<String, String> = (def?.params ?: emptyList()).associate { p ->
        p.name to p.description.substringBefore('，').substringBefore('（')
            .substringBefore("如").substringBefore(' ').trim()
            .ifBlank { argKeyCn(p.name) }
    }
    val obj = io.github.m0use001.planability.ai.JsonRepair.parseObject(argsJson) ?: org.json.JSONObject()
    val lines = mutableListOf<String>()
    val keys = obj.keys()
    while (keys.hasNext()) {
        val key = keys.next()
        lines.add("${cnParam[key] ?: argKeyCn(key)}：${obj.opt(key)}")
    }
    lines.joinToString("\n")
}.getOrDefault(argsJson.take(200))

/** role=tool 结果行：单行摘要；长结果（智能体/深度规划/文档解析等）整行点击展开看全文；写类工具带撤销按钮 */
@Composable
private fun ToolResultRow(msg: ChatMessage, onOpenFile: (String) -> Unit) {
    val ok = msg.content.startsWith("[OK]")
    val denied = msg.content.startsWith("[DENIED]")
    val (icon, tint) = when {
        // 2026-11-20 降噪批：成功回执转中性（每个常规回执都亮 primary 是视觉噪音），失败仍红
        ok -> Icons.Rounded.CheckCircle to MaterialTheme.colorScheme.onSurfaceVariant
        denied -> Icons.Rounded.Block to MaterialTheme.colorScheme.onSurfaceVariant
        else -> Icons.Rounded.ErrorOutline to MaterialTheme.colorScheme.error
    }
    // 工具写了文件就把路径挑出来：卡片「打开」原来只依赖模型自己吐 ```file 块，
    // 模型少写一段（或写了别的格式）用户就没有入口，只能去文件库里翻。
    // 这里直接给结果行挂一个「打开」，路径来自工具回执，不靠模型。
    val writtenPath = remember(msg.content) { toolWrittenPath(msg.content) }
    val toolName = msg.toolCallId.substringBefore('#')
    val writeTools = remember {
        setOf(
            "schedule_create", "schedule_update", "todo_create", "todo_complete", "todo_edit",
            "checkin_add", "focus_add", "pending_add", "trash_restore",
            "note_create", "note_append", "note_edit", "note_delete", "notebook_create",
            "memory_add", "memory_forget",
            "file_export", "file_export_document"
        )
    }
    var revoked by remember(msg.id) { mutableStateOf(msg.content.contains("[已撤销")) }
    var expanded by remember(msg.id) { mutableStateOf(false) }
    // 展开/收起 = 中性轻触（Tap）：和工具调用块、计划卡的展开同一档手感
    val haptics = LocalHaptics.current
    val scope = rememberCoroutineScope()
    val body = msg.content
        .removePrefix("[OK] ").removePrefix("[FAIL] ").removePrefix("[DENIED] ")
    val firstLine = body.lineSequence().firstOrNull()?.take(80) ?: ""
    val expandable = body.length > 90 || body.contains('\n')
    // 2026-11-20 降噪批：行距与 ProcessStepRow 对齐（vertical 5dp、图标后 8dp）
    Column(
        Modifier
            .fillMaxWidth()
            .let { m ->
                if (expandable) m.clickable { haptics.perform(HapticEngine.Kind.Tap); expanded = !expanded } else m
            }
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 5.dp)
        ) {
            Icon(icon, null, Modifier.size(14.dp), tint = tint)
            Spacer(Modifier.width(8.dp))
            Text(
                firstLine,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            // 2026-11-20 降噪批：「详情/收起」→ ⌄/› 箭头，与过程链/思考行/工具行同一 affordance
            if (expandable) {
                Text(
                    if (expanded) "⌄" else "›",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.width(4.dp))
            }
            if (msg.content.contains("[已撤销")) {
                Text(
                    "已撤销",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.width(6.dp))
            }
            if (ok && !revoked && toolName in writeTools) {
                TextButton(
                    onClick = {
                        revoked = true
                        scope.launch {
                            val d = io.github.m0use001.planability.ai.ptl.ToolExecutor.undoLast(msg.sessionId)
                            ChatEngine.markToolRowRevoked(msg.sessionId, msg.id, d ?: "没有可撤销项")
                        }
                    },
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp, vertical = 0.dp)
                ) {
                    Text("撤销", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                }
            }
            if (ok && writtenPath != null) {
                TextButton(
                    onClick = { onOpenFile(writtenPath) },
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp, vertical = 0.dp)
                ) {
                    Text("打开", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                }
            }
            if (!ok && !denied) {
                // 2026-11-09 夜·逃生口：失败行一键复制全文（含工具名与替代建议），
                // 粘回输入框就是现成的重试指令——不把用户卡在死路上。
                val clip = LocalClipboardManager.current
                TextButton(
                    onClick = {
                        clip.setText(AnnotatedString("[${toolName} 失败] " + body))
                        haptics.perform(HapticEngine.Kind.Tap)
                    },
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp, vertical = 0.dp)
                ) {
                    Text("复制", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                }
            }
        }
        // v12：展开=标题行固定，全文限高内部滚动（同 ReasoningBlock 规范）
        // 展开走 Motion.expandIn / 收起走 collapseOut：全文高度大，硬切会让下面的气泡整体跳一下
        AnimatedVisibility(
            visible = expanded,
            enter = Motion.expandIn,
            exit = Motion.collapseOut
        ) {
            // 2026-11-20 降噪批：展开体从裸缩进改 ChainDetail（左细竖线旁注），与过程链同款；
            // 落位触感维持删除口径（点击的 Tap 已给过，2026-10-31「展开震动2次」）。
            ChainDetail {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp)
                ) {
                    Column(
                        Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                    ) {
                        // 2026-11-14 用户「表格内（和其他一些地方）md 格式没渲染」：
                        // 工具回执正文原来是一行 `Text(body)` 纯文本——报告里的表格、粗体、
                        // 行内公式全是原文（`**D_x(k)=0**`、`$\ln(1+\alpha)$`）。改走
                        // MarkdownView（与助手正文同一套：表格/公式/粗体/列表）。
                        MarkdownView(body)
                    }
                }
            }
        }
    }
}

/** M4 审批确认卡（v12 人话化：中文说明 + 风险徽标 + 参数逐行），confirm 可"总是允许"，danger 仅允许/拒绝 */
@Composable
internal fun ApprovalCard(
    req: ChatEngine.ApprovalRequest,
    onDeny: () -> Unit = { ChatEngine.resolveApproval(false, false) },
    onAlways: () -> Unit = { ChatEngine.resolveApproval(true, true) },
    onAllow: () -> Unit = { ChatEngine.resolveApproval(true, false) },
) {
    val danger = req.risk == "danger"
    val def = remember(req.toolName) { io.github.m0use001.planability.ai.ptl.ToolRegistry.byName(req.toolName) }
    // 参数逐行渲染："中文键：值"（v19.11：键名取工具定义里的中文说明，原来的 title/date/note 是英文）
    // 2026-10-31：逻辑抽到 toolArgLines，与工具调用块共用一份口径（两处各写一遍会漂移）
    val argLines = remember(req.argsJson, req.toolName) { toolArgLines(req.toolName, req.argsJson) }
    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (danger) MaterialTheme.colorScheme.errorContainer
            else MaterialTheme.colorScheme.secondaryContainer
        ),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            // 封顶：卡片最高 340dp，超出部分由上面的参数区滚动消化，避免与输入区/键盘抢高度
            .heightIn(max = 340.dp)
    ) {
        Column(Modifier.padding(12.dp)) {
            // 2026-11-08 用户第二次报「创建文件许可按钮又被压扁了」：这卡在会话页里是
            // `Column` 的**非 weight 子项**，拿到的是"剩余高度"（顶栏两行 chips + 键盘 +
            // 输入面板之后可能只剩 ~200dp），而里头的内容是固定高度堆起来的
            // （说明 + 参数区 caps 152dp + 按钮行）≈ 330dp —— 超出部分被 Surface 裁掉，
            // 被裁的正是**最底下的按钮行**，看起来就是"按钮被压扁成一条"。
            // 10-31 那次只给参数区加了滚动，治标；真正的结构问题是没有"谁先让位"。
            // 现在：上半区可滚动且 weight(fill=false) —— 空间不够时**先压缩文字区**，
            // 按钮行在滚动区之外，永远按自己的高度（40dp+）画完整。
            Column(
                Modifier
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState())
            ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (danger) Icons.Rounded.GppBad else Icons.Rounded.GppMaybe, null,
                    Modifier.size(18.dp),
                    tint = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "AI 想执行一个操作",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.weight(1f))
                Text(
                    if (danger) "危险操作" else "需要你确认",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                )
            }
            Spacer(Modifier.height(6.dp))
            // 人话说明：工具的中文用途描述
            Text(
                def?.cnDescription ?: req.toolName,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium
            )
            Text(
                // v19.11：不再向用户直露英文工具代号
                "操作：" + toolLabel(req.toolName),
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            // 2026-10-31 用户「对话框压扁」：长参数原来 maxLines=5 直接省略号、卡片高度还被
            // 上方的对话区挤（键盘弹出时更甚），整张卡看起来被压成一条。
            // 2026-11-08 二次报障后：参数区不再自己滚（会和外层滚动打架），统一由上面那个
            // weight 滚动区消化；这里只保留"别让参数无上限地长"的封顶，保证按钮行需要时总能让位。
            if (argLines.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    argLines,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 12,
                    overflow = TextOverflow.Ellipsis
                )
            }
            }
            // ── 滚动区到此结束：按钮行在它之外，任何高度都不会被裁 ──
            Spacer(Modifier.height(6.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onDeny) { Text("拒绝") }
                Spacer(Modifier.weight(1f))
                if (req.canAlways) {
                    TextButton(onClick = onAlways) { Text("总是允许") }
                }
                androidx.compose.material3.Button(onClick = onAllow) {
                    Text("允许")
                }
            }
        }
    }
}

@Composable
private fun ThinkingRow(callingTool: String = "", startedAt: Long = 0L) {
    // 2026-11-11：工具调用参数生成期改显「正在调用 X · N 秒」——本地模型吐数千字 JSON
    // 参数要一两分钟，此前空消息行只剩「思考中」，用户以为卡死（session 1293 实录）。
    // 秒数从行 createdAt 算、每秒跳一次（tick 只为重组，秒数由当前时间算，不累加，
    // 进程切挂也不漂）。**tick 必须在组合期被读到**：只写不读的 State 不触发重组，
    // 秒数会冻死（真机实录，LiveToolRow/LiveAgentRow 同病同修）。
    val toolNonBlank = callingTool.isNotBlank()
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(toolNonBlank) {
        if (toolNonBlank) {
            while (true) { kotlinx.coroutines.delay(1000); tick++ }
        }
    }
    val secs = if (toolNonBlank && startedAt > 0L) {
        tick   // 订阅：读一次让本作用域随每秒自增重组，秒数才会跳
        ((System.currentTimeMillis() - startedAt) / 1000L).coerceAtLeast(0L)
    } else 0L
    Row(verticalAlignment = Alignment.CenterVertically) {
        // M3E 原生形变加载指示（形状轮换+旋转），表示 AI 正在跑；尺寸对齐 HomeCards 睡眠采集同款
        LoadingIndicator(Modifier.size(24.dp))
        Spacer(Modifier.width(10.dp))
        Text(
            if (toolNonBlank) "正在调用 ${toolLabel(callingTool)} · ${secs} 秒"
            else "思考中",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * 思考过程折叠块（三态）：
 * 流式中 = Preview（限高渐隐 + 自动滚到尾部 + 实时秒数）；完成后默认收起，点击展开全文。
 */
@Composable
private fun ReasoningBlock(msg: ChatMessage) {
    val streaming = msg.status == "streaming"
    var expanded by remember(msg.id) { mutableStateOf(false) }
    var elapsedSec by remember(msg.id) { mutableIntStateOf(0) }
    if (streaming) {
        LaunchedEffect(msg.id) {
            while (true) {
                elapsedSec = ((System.currentTimeMillis() - msg.createdAt) / 1000L).toInt().coerceAtLeast(1)
                delay(1000)
            }
        }
    }
    val scroll = rememberScrollState()
    // 展开触感补齐（2026-10-31）：工具块/结果行的展开都是 Tap 一记，深度思考块原来
    // 完全没配——同一个界面里"点开看看"的手感应当一致。
    val haptics = LocalHaptics.current
    // Preview 模式自动滚到最新思考文本
    // v19.11：改成随内容高度变化的瞬时滚动（snapshotFlow + scrollTo）。
    // 原来每个增量都重启动画（animateScrollTo），动画被打断重来 → 整块狂抖。
    LaunchedEffect(msg.id, expanded, streaming) {
        if (!streaming || expanded) return@LaunchedEffect
        snapshotFlow { scroll.maxValue }
            .collect { v -> if (v > 0f) runCatching { scroll.scrollTo(v) } }
    }

    // 2026-11-20 降噪批（用户「对话流又密又乱」）：收起态从带底色横条改成与过程链
    // ChainStepRow 同款的安静行（ProcessStepRow）——同类信息一种长相，正文才是视觉主体。
    // 展开正文/流式预览包进 ChainDetail（左细竖线旁注），与过程链展开同款。
    // 半透明背景在这里是被禁的（真机事故 2026-10-20：copy(alpha) 直接当背景会把下层
    // 窗口内容透出来）——现在连不透明的底色也一并退场，不再需要 compositeOver 兜底。
    Column(Modifier.fillMaxWidth()) {
        ProcessStepRow(
            icon = Icons.Rounded.Psychology,
            label = when {
                streaming && !expanded -> "深度思考 $elapsedSec s"
                streaming -> "深度思考 ▾"
                else -> "深度思考 · ${msg.reasoning.length} 字"
            },
            // 流式收起态不出箭头（沿用原「详情」只在 !streaming || expanded 出现的口径）
            open = if (streaming && !expanded) null else expanded,
            onClick = { haptics.perform(HapticEngine.Kind.Tap); expanded = !expanded }
        )
        AnimatedVisibility(expanded, enter = Motion.expandIn, exit = Motion.collapseOut) {
            // v12：标题固定在上方，内容限高内部滚动——下滑到顶不把标题推走，随时点行收起
            ChainDetail {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp)
                ) {
                    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                        Text(
                            msg.reasoning,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
        AnimatedVisibility(streaming && !expanded, enter = Motion.expandIn, exit = Motion.collapseOut) {
            // 限高预览 + 上下渐隐蒙版
            ChainDetail {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(84.dp)
                        // BlendMode.DstIn 必须在**自己的图层**里做，否则它会拿整窗的背景当混合对象：
                        // 真机上就是这里让思考卡变成"半透明窗户"，透出工作台首页。
                        // CompositingStrategy.Offscreen 给这段内容单独开一层离屏缓冲，蒙版只作用在本段。
                        .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                        .drawWithContent {
                            drawContent()
                            drawRect(
                                brush = Brush.verticalGradient(
                                    0f to Color.Transparent,
                                    0.45f to Color.Black,
                                    1f to Color.Black
                                ),
                                blendMode = BlendMode.DstIn
                            )
                        }
                ) {
                    Column(Modifier.fillMaxWidth().verticalScroll(scroll)) {
                        Text(
                            msg.reasoning,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
    Spacer(Modifier.height(4.dp))
}

/** 底部错误通知条：errorContainer + 复制 + 自动消失（引擎侧 8s） */
@Composable
private fun ErrorNoticeBar(state: ChatUiState, sessionId: Long) {
    val notice = state.notice ?: return
    val clipboard = LocalClipboardManager.current
    val ctx = androidx.compose.ui.platform.LocalContext.current
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 12.dp)) {
            Text(
                notice, style = MaterialTheme.typography.bodySmall,
                maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)
            )
            IconButton(onClick = {
                clipboard.setText(AnnotatedString(notice))
                Toast.makeText(ctx, "已复制错误信息", Toast.LENGTH_SHORT).show()
            }) {
                Icon(Icons.Rounded.ContentCopy, "复制", modifier = Modifier.size(16.dp))
            }
            IconButton(onClick = { ChatEngine.clearNotice(sessionId) }) {
                Icon(Icons.Rounded.Close, "关闭", modifier = Modifier.size(16.dp))
            }
        }
    }
}

// ==================== 上下文面板（M2 token manifest） ====================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ContextPanelSheet(sessionId: Long, onDismiss: () -> Unit, onEditContext: () -> Unit = {}) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var plan by remember { mutableStateOf<ContextPlan?>(null) }
    var cursorSet by remember { mutableStateOf(false) }
    var tokensInSum by remember { mutableIntStateOf(0) }
    var tokensOutSum by remember { mutableIntStateOf(0) }
    // 2026-11-15 用户：「上下文这里应该显示的是当前总上下文」——原来那行是本次 assemble
    // 的估算（不含工具定义、历史口径也不同），和顶栏百分比/工作流派生的 ctxTokens 对不上。
    // 权威值取 ChatEngine 的 UI 状态（= 下一轮真正会发出去的载荷总量）。
    val uiState by io.github.m0use001.planability.ai.ChatEngine.stateOf(sessionId).collectAsState()
    val scope = rememberCoroutineScope()
    // 面板里的开关是「选择」语义（HIG selection → 最轻的 Select），与 chip/分段按钮同档
    val haptics = LocalHaptics.current

    suspend fun refresh() {
        val s = Graph.chat.session(sessionId) ?: return
        cursorSet = s.clearBefore > 0
        plan = ContextEngine.assemble(sessionId, s)
        val msgs = Graph.chat.messagesOf(sessionId)
        tokensInSum = msgs.sumOf { it.tokensIn }
        tokensOutSum = msgs.sumOf { it.tokensOut }
    }
    LaunchedEffect(sessionId) { refresh() }

    ModalBottomSheet(dragHandle = { io.github.m0use001.planability.ui.common.SheetDragHandleOnly() }, contentWindowInsets = { WindowInsets.navigationBars }, onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(start = 20.dp, end = 20.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("上下文", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                Text(
                    // 权威口径：当前总上下文（= 下一轮会发出去的全部载荷）
                    "当前总上下文 ≈ ${uiState.ctxTokens} tokens",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Text(
                "个人上下文在每次请求时注入（来源标注在系统提示里），可逐项关闭。" +
                    "下列各项合计 ≈ ${plan?.totalTokens ?: 0} tokens（不含工具定义，与总量口径不同）。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(6.dp))
            val p = plan
            if (p == null) {
                Text("计算中…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                p.items.forEach { item ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.weight(1f)) {
                            Text(item.label, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                if (item.type == "history" && !item.enabled) "已裁剪（游标/摘要后）" else "≈ ${item.tokens} tokens",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (item.type in ContextEngine.CHIP_TYPES) {
                            androidx.compose.material3.Switch(
                                checked = item.enabled,
                                onCheckedChange = { on ->
                                    // 开关切换 = 选择（HIG selection → 最轻的 Select）：高频动作，重了两下就烦
                                    haptics.perform(HapticEngine.Kind.Select)
                                    ContextEngine.setChipEnabled(item.type, on)
                                    scope.launch { refresh() }
                                }
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            // v47：进入「详细上下文编辑」——按轮选择性压缩（压缩后这几轮在会话里只显示摘要）
            androidx.compose.material3.OutlinedButton(
                onClick = { haptics.perform(HapticEngine.Kind.Tap); onEditContext() },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Rounded.Tune, null, Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("详细上下文编辑（按轮压缩）", style = MaterialTheme.typography.labelLarge)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("清除之前上下文", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        if (cursorSet) "游标已启用：更早的消息不再发送"
                        else "把游标推到最后一条消息，此后只发送增量",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                TextButton(
                    onClick = {
                        scope.launch {
                            if (ChatEngine.clearCursor(sessionId)) refresh()
                        }
                    },
                    enabled = !cursorSet
                ) { Text(if (cursorSet) "已清除" else "清除") }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "本会话累计：输入 $tokensInSum · 输出 $tokensOutSum tokens" +
                    if (tokensInSum + tokensOutSum == 0) "（服务端未回传 usage 时按 0 计）" else "",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                "审计：每次请求的 token 清单落盘 ai_context/，用量追加 ai_usage/usage.jsonl",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// ==================== 长按消息操作菜单 ====================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MessageActionSheet(
    msg: ChatMessage,
    isLastAssistant: Boolean,
    onDismiss: () -> Unit,
    onCopy: () -> Unit,
    onRetryUser: () -> Unit,
    onRegenerate: () -> Unit,
    onDeleteFrom: () -> Unit,
    onQuote: ((String) -> Unit)? = null
) {
    val clipboard = LocalClipboardManager.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(contentWindowInsets = { WindowInsets.navigationBars }, dragHandle = { io.github.m0use001.planability.ui.common.SheetDragHandleOnly() }, onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(start = 16.dp, end = 16.dp, bottom = 28.dp)) {
            Text(
                if (msg.role == "user") "消息操作" else "回复操作",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)
            )
            if (msg.role == "user") {
                SheetMenuRow(Icons.Rounded.ContentCopy, "复制", "复制这条消息原文", false) { onDismiss(); onCopy() }
                SheetMenuRow(Icons.Rounded.Refresh, "重试", "删除这条消息及其后回复，重新编辑发送", false) {
                    onDismiss(); onRetryUser()
                }
                SheetMenuRow(Icons.Rounded.Delete, "删除", "删除这条消息及其后所有内容", true) {
                    onDismiss(); onDeleteFrom()
                }
            } else {
                SheetMenuRow(Icons.Rounded.ContentCopy, "复制", "复制回复内容", false) { onDismiss(); onCopy() }
                // 2026-11-04 用户「支持引用到下一轮对话」：正文长按选词 → 复制 → 这里引用那段；
                // 没选也行，直接引用本条。剪贴板内容必须是本消息的子串，防止把别处的文字引进来。
                if (onQuote != null) {
                    val picked = runCatching { clipboard.getText()?.text?.trim() }.getOrNull()
                    val selQuote = picked?.takeIf { it.isNotBlank() && it.length <= msg.content.length && msg.content.contains(it) }
                    if (selQuote != null) {
                        SheetMenuRow(Icons.Rounded.FormatQuote, "引用选中", "把刚复制的那段作为引用带到输入框", false) {
                            onDismiss(); onQuote(selQuote)
                        }
                    }
                    SheetMenuRow(Icons.Rounded.FormatQuote, "引用本条", "把整条回复作为引用带到输入框", false) {
                        onDismiss(); onQuote(msg.content)
                    }
                }
                if (isLastAssistant) {
                    SheetMenuRow(Icons.Rounded.Refresh, "重新生成", "删除此回复并重新生成", false) {
                        onDismiss(); onRegenerate()
                    }
                }
                SheetMenuRow(Icons.Rounded.Delete, "删除", "删除此回复及其后所有内容", true) {
                    onDismiss(); onDeleteFrom()
                }
            }
        }
    }
}

@Composable
private fun SheetMenuRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    desc: String,
    danger: Boolean,
    onClick: () -> Unit
) {
    val tint = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 10.dp)
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(38.dp)
                .clip(RoundedCornerShape(19.dp))
                .background(if (danger) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceContainerHighest)
        ) {
            Icon(icon, null, Modifier.size(19.dp), tint = tint)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, color = tint)
            Text(
                desc, style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// ==================== 输入栏 ====================

/**
 * v10 流式分段：取最后一个"块边界"（代码围栏外的空行）为界，
 * 界之前的完整段落实时渲染 Markdown，之后未写完的尾巴保持纯文本。
 */
internal fun splitStreamingContent(content: String): Pair<String, String> {
    if (content.length < 40) return "" to content
    var fenceOpen = false
    var lastBreak = -1
    var i = 0
    while (i < content.length) {
        if (content.startsWith("```", i)) {
            fenceOpen = !fenceOpen
            i += 3
            continue
        }
        if (!fenceOpen && content.startsWith("\n\n", i)) lastBreak = i + 2
        i++
    }
    return if (lastBreak > 0) content.substring(0, lastBreak) to content.substring(lastBreak)
    else "" to content
}

@Composable
private fun InputBar(
    value: String,
    onValueChange: (String) -> Unit,
    streaming: Boolean,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onImage: () -> Unit,
    onAttach: () -> Unit = {},
    onExpand: (() -> Unit)? = null,
    modelName: String = "",
    thinking: String = "",
    onOpenModelThinking: () -> Unit = {},
    // 待发送附件（2026-11-01：挪进面板内部渲染，与输入框同一个容器）
    pendingFiles: List<PendingAttachment> = emptyList(),
    onOpenAttachment: (String) -> Unit = {},
    onRemoveAttachment: (PendingAttachment) -> Unit = {},
    // 生成进度（原面板外那条 20dp 槽，挪进来避免露出窗口底色）
    progressText: String? = null
) {
    Surface(color = MaterialTheme.colorScheme.surface) {
      // v19.14：灰色要铺满屏幕底部，包括全面屏那条横杠（手势操作条）。
      //
      // 原来靠 Row 上的 navigationBarsPadding() 撑出那段高度，但它**无效**：
      // InputBar 在 Scaffold 的 content lambda 里（不是 bottomBar 槽），
      // Scaffold 的 contentWindowInsets 已经消费了导航栏 inset，那个 padding 返回 0。
      // 于是 Surface 只画到横杠上沿，下面漏出下层背景——就是用户说的"输入框后面没铺满"。
      //
      // 不去改 Scaffold 的 contentWindowInsets：那会同时牵动 topBar、消息列表、
      // 滚动到底部的行为，牵连面远大于这个视觉问题。改成用 insets 直接量出
      // 导航栏高度、补一段同色底：横杠区拿到底色，输入内容本身也不必上移。
      Column(Modifier.imePadding()) {
        // 2026-11-01 用户：输入区要像「倒过来的顶部标题栏」——整宽贴底，
        // 只有上面两个角是圆的，底部（含手势操作条）与面板同色、不留缝。
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .padding(start = 8.dp, end = 6.dp, top = 6.dp, bottom = 2.dp)
        ) {
            // 2026-11-19 平板批：面板**背景**整宽铺底（下面那段注释说的规矩不许动），
            // 但内容（把手 / 文字 / 按钮）跟消息列同宽同缘——
            // 否则平板上输入框铺满 1160dp、消息正文收在 880dp 里，左右缘对不上
            //（用户「会话内容不整齐」）。
            // 次序要紧：限宽必须用 adaptiveColumn（widthIn 在 fillMaxWidth 之前）；
            // 写成 fillMaxWidth().widthIn(...) 会把宽度钉死成父宽（v58 踩过的坑）。
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                // 2026-11-22 用户截图「文字与按钮全挤在中间」（太窄挤在一起）：内容列原来
                // 跟消息列表共用 880dp 限宽——宽屏上输入框、模型胶囊、发送键被压在中间
                // 一条窄列里、左右留大片灰底没用上。输入区不是阅读列，撑满面板宽
                //（消息列表的 880dp 限宽不动，那是阅读度量）。
                Column(Modifier.fillMaxWidth()) {
                // 2026-11-01 用户：把手只负责**把输入框拉高**（就地在页面里变高），
                // 不切页面。上拉展开 / 下拉收回 / 点一下切换；长按才进全屏编辑（备用入口）。
                // 位置：面板**最顶部**（用户要求把把手提到输入框顶上，进度槽之下移到把手后面）。
                var tall by remember { mutableStateOf(false) }
                var dragAcc by remember { mutableStateOf(0f) }
                // 2026-11-23 用户「优化超长文字输入体验：输入栏应该更高 + 自适应竖直滑动条」：
                // 高度改成**内容自适应**——默认最小 96dp（约 4 行，比原来的 56dp 明显更高），
                // 随输入行数增长，到上限后内部竖向滚动；超出时右侧出现细滚动条（自适应=只有
                // 溢出才出现）。把手上下拉切换「常规上限 / 加高上限」（不再突变最小高度）。
                val fieldMaxH by androidx.compose.animation.core.animateDpAsState(
                    targetValue = if (tall) 420.dp else 220.dp,
                    animationSpec = Motion.standardSpatial(),
                    label = "composerFieldMaxHeight"
                )
                val fieldMinH = 96.dp
                // 输入区内部滚动状态（供自绘滚动条读取）
                val inputScroll = androidx.compose.foundation.rememberScrollState()
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
                        // 2026-11-20：整条通栏把手，水波原本是一条直角横杠（在宽屏 880dp 列上尤其明显）
                        .clip(RoundedCornerShape(8.dp))
                        .combinedClickable(
                            onClick = { tall = !tall },
                            onLongClick = { onExpand?.invoke() }
                        )
                ) {
                    Box(
                        Modifier
                            .size(width = 32.dp, height = 4.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f))
                    )
                }
                // 生成进度槽：只在生成中占高度（20dp），空闲收零。原来常驻 20dp 空槽
                // （"固定防抖"），把手和文字行之间永远隔着一条空带——用户 2026-11-01
                // 「显示文字行离把手太远，中间空太多」。出现/消失走 quick 展开收起，
                // 高度变化是动画不是跳变，列表不会抽搐。
                androidx.compose.animation.AnimatedVisibility(
                    visible = progressText != null,
                    enter = androidx.compose.animation.expandVertically() +
                        androidx.compose.animation.fadeIn(Motion.quick()),
                    exit = androidx.compose.animation.shrinkVertically() +
                        androidx.compose.animation.fadeOut(Motion.quick())
                ) {
                    Box(
                        modifier = Modifier.fillMaxWidth().height(20.dp),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        Text(
                            "⚙ " + (progressText ?: ""),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(start = 6.dp)
                        )
                    }
                }
                // 2026-11-01 用户：待发送的附件/图片也要被输入框「包住」——
                // 预览带画在面板内部、文字行上方，成为同一个容器的一部分。
                if (pendingFiles.isNotEmpty()) {
                    PendingAttachmentStrip(
                        files = pendingFiles,
                        onOpen = onOpenAttachment,
                        onRemove = onRemoveAttachment
                    )
                }
                Box(Modifier.fillMaxWidth()) {
                    androidx.compose.foundation.text.BasicTextField(
                        value = value,
                        onValueChange = onValueChange,
                        modifier = Modifier
                            .fillMaxWidth()
                            // 内容自适应 + 上限：没超时就只有内容那么高（≥96dp），超了内部滚动
                            .heightIn(min = fieldMinH, max = fieldMaxH)
                            .verticalScroll(inputScroll)
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        textStyle = MaterialTheme.typography.bodyLarge.copy(
                            color = MaterialTheme.colorScheme.onSurface
                        ),
                        cursorBrush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.primary),
                        decorationBox = { inner ->
                            if (value.isEmpty()) {
                                Text(
                                    "输入消息…",
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            inner()
                        }
                    )
                    // 自适应竖直滚动条：只有内容溢出（maxValue>0）才出现，位置随滚动走。
                    // 必须 matchParentSize（不参与父尺寸计算）——用 fillMaxHeight 会在
                    // 高度无上限的父容器里把输入面板撑成整屏（面板下方一大块空白）。
                    InputBarScrollbar(
                        state = inputScroll,
                        modifier = Modifier.matchParentSize()
                    )
                }
                // 2026-10-31 用户定稿：文字只在上排，下面这排**只放按钮**（＋ 与 发送并排右对齐），
                // 它存在的意义是让整个输入区看起来是一块完整的容器，而不是"框 + 一排按钮"。
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 2.dp, end = 2.dp)
                ) {
                    // 2026-11-01 用户：模型与思考合并成一个控件，点开是**同一张面板**
                    // （模型列表 + 思考强度拖动条，参考 Codex 的设置面板）。
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .clip(RoundedCornerShape(percent = 50))
                            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                            .clickable { onOpenModelThinking() }
                            .padding(start = 10.dp, end = 6.dp, top = 4.dp, bottom = 4.dp)
                    ) {
                        Text(
                            (modelName.ifBlank { "模型" }) + " · " + ThinkingLevels.label(thinking),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1
                        )
                        Icon(
                            Icons.Rounded.ArrowDropDown, null,
                            Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    // v10：全屏编辑入口（文字 >120 字才出现），放在这一行最左，不打断右侧按钮组
                    if (onExpand != null && value.length > 120) {
                        IconButton(onClick = onExpand, modifier = Modifier.size(34.dp)) {
                            Icon(
                                Icons.Rounded.UnfoldMore, "扩大输入",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(19.dp)
                            )
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    // 附件入口：单个 + 菜单（图片 / 文件）
                    var attachMenu by remember { mutableStateOf(false) }
                    Box {
                        IconButton(
                            onClick = { attachMenu = true },
                            enabled = !streaming,
                            modifier = Modifier.size(34.dp)
                        ) {
                            Icon(
                                Icons.Rounded.Add, "添加附件",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        androidx.compose.material3.DropdownMenu(
                            expanded = attachMenu,
                            onDismissRequest = { attachMenu = false }
                        ) {
                            androidx.compose.material3.DropdownMenuItem(
                                modifier = Modifier.clip(RoundedCornerShape(10.dp)), // 2026-11-15 点击高亮圆角化（原来是整行直角方块）
                                text = { Text("图片") },
                                leadingIcon = { Icon(Icons.Rounded.Image, null, Modifier.size(18.dp)) },
                                onClick = { attachMenu = false; onImage() }
                            )
                            androidx.compose.material3.DropdownMenuItem(
                                modifier = Modifier.clip(RoundedCornerShape(10.dp)), // 2026-11-15 点击高亮圆角化（原来是整行直角方块）
                                text = { Text("文件") },
                                leadingIcon = { Icon(Icons.Rounded.AttachFile, null, Modifier.size(18.dp)) },
                                onClick = { attachMenu = false; onAttach() }
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
                                when {
                                    streaming -> MaterialTheme.colorScheme.errorContainer
                                    value.isNotBlank() -> MaterialTheme.colorScheme.primary
                                    else -> MaterialTheme.colorScheme.surfaceContainerHighest
                                }
                            )
                            .clickable {
                                when {
                                    streaming -> onStop()
                                    else -> onSend()
                                }
                            }
                    ) {
                        Icon(
                            if (streaming) Icons.Rounded.Stop else Icons.AutoMirrored.Rounded.Send,
                            if (streaming) "停止" else "发送",
                            tint = when {
                                streaming -> MaterialTheme.colorScheme.error
                                value.isNotBlank() -> MaterialTheme.colorScheme.onPrimary
                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            modifier = Modifier.size(19.dp)
                        )
                    }
                }
                }
            }
            // 手势操作条区域放在**面板内部**：面板背景要一直铺到屏幕底边，
            // 不然圆角面板下面会露出另一层底色，看起来"没贴底"。
            Spacer(
                Modifier
                    .fillMaxWidth()
                    .windowInsetsBottomHeight(WindowInsets.navigationBars)
            )
            }
      }   // Column 收尾
    }   // Surface 收尾
}

// ==================== 助手选择器（M3） ====================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AssistantPickerSheet(onDismiss: () -> Unit, onPick: (io.github.m0use001.planability.data.Assistant) -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var assistants by remember { mutableStateOf<List<io.github.m0use001.planability.data.Assistant>>(emptyList()) }
    LaunchedEffect(Unit) { assistants = Graph.assistants.all() }
    ModalBottomSheet(contentWindowInsets = { WindowInsets.navigationBars }, dragHandle = { io.github.m0use001.planability.ui.common.SheetDragHandleOnly() }, onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(start = 20.dp, end = 20.dp, bottom = 28.dp)) {
            Text("选择助手", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                "不同助手有不同人格与温度；用户上下文与记忆对所有助手生效。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp, bottom = 10.dp)
            )
            assistants.forEach { a ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .clickable { onDismiss(); onPick(a) }
                        .padding(horizontal = 6.dp, vertical = 10.dp)
                ) {
                    Text(a.emoji, style = MaterialTheme.typography.headlineSmall)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(a.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                            if (a.builtin) {
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    "内置", style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                        Text(
                            a.slogan, style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

// ==================== 会话列表 ====================

// ==================== 会话列表（工作台任务列表样式） ====================

/** 相对时间：刚刚 / N 分钟前 / N 小时前 / 昨天 / M/d（会话列表与项目详情共用） */
fun relTime(millis: Long): String {
    val m = (System.currentTimeMillis() - millis) / 60000
    return when {
        m < 1 -> "刚刚"
        m < 60 -> "$m 分钟前"
        m < 60 * 24 -> "${m / 60} 小时前"
        m < 60 * 48 -> "昨天"
        else -> millis.toLocalDate().formatMonthDay()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatSessionsScreen(onDone: () -> Unit, onOpen: (Long, Long) -> Unit, onOpenProject: (Long) -> Unit = {}) {
    // 顶栏返回的触感（Tap）：返回是中性点击，不是"选中"也不是"破坏"
    val haptics = LocalHaptics.current
    var showPicker by remember { mutableStateOf(false) }

    PredictiveBackOverlay(onBack = onDone) {
        Scaffold(
            floatingActionButtonPosition = androidx.compose.material3.FabPosition.Center,
            topBar = {
                TopAppBar(
                    title = { Text("AI 会话", fontWeight = FontWeight.SemiBold) },
                    navigationIcon = {
                        // 返回 = 中性轻触（Tap），与聊天页顶栏返回同一档
                        IconButton(onClick = { haptics.perform(HapticEngine.Kind.Tap); onDone() }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "返回") }
                    },
                    actions = {}
                )
            },
            floatingActionButton = {
                // FAB = 打开"新对话"选择弹层的中性点击 → Tap（与计划/自动化/文件库的 FAB 同档）
                // B3-B：升 Medium（80dp/20dp，M3E 最推荐档位；试点）
                MediumFab(onClick = {
                    haptics.perform(HapticEngine.Kind.Tap)
                    showPicker = true
                }) { Icon(Icons.Rounded.Add, "新对话") }
            }
        ) { pad ->
            ChatSessionsBody(contentPadding = pad, onOpen = onOpen, onOpenProject = onOpenProject)
        }
    }

    if (showPicker) {
        AssistantPickerSheet(
            onDismiss = { showPicker = false },
            onPick = { a -> showPicker = false; onOpen(0L, a.id) }
        )
    }
}

/**
 * v57 平板适配：AI 会话列表的**列表体**（数据装载 + 项目分组 LazyColumn + 各操作 sheet）。
 *
 * 从 [ChatSessionsScreen] 拆出来供两个宿主复用：
 * - 手机（Compact）：Scaffold 壳 + FAB，见 [ChatSessionsScreen]；
 * - 平板（Medium/Expanded）：聊天双栏的**左栏**（InfoCenterScreen 宽屏分支），外壳由父页自备。
 * 列表数据、分组逻辑、移动/重命名/轨迹/删除操作只有这一份真相。
 */
@Composable
fun ChatSessionsBody(
    contentPadding: androidx.compose.foundation.layout.PaddingValues = androidx.compose.foundation.layout.PaddingValues(0.dp),
    onOpen: (Long, Long) -> Unit,
    onOpenProject: (Long) -> Unit = {}
) {
    var sessions by remember { mutableStateOf<List<ChatSession>>(emptyList()) }
    var assistantsById by remember { mutableStateOf<Map<Long, io.github.m0use001.planability.data.Assistant>>(emptyMap()) }
    var projects by remember { mutableStateOf<List<io.github.m0use001.planability.data.Project>>(emptyList()) }
    var moveSessionId by remember { mutableStateOf<Long?>(null) }
    var renameSessionId by remember { mutableStateOf<Long?>(null) }
    var renameText by remember { mutableStateOf("") }
    var traceSessionId by remember { mutableStateOf<Long?>(null) }
    var confirmDeleteId by remember { mutableStateOf<Long?>(null) }
    val clipboard = LocalClipboardManager.current
    val version by Graph.dataVersion.flow.collectAsState()
    val scope = rememberCoroutineScope()

    var healedUntitled by remember { mutableStateOf(false) }
    LaunchedEffect(version) {
        sessions = Graph.chat.sessions()
        assistantsById = Graph.assistants.all().associateBy { it.id }
        projects = Graph.projects.all()
        if (!healedUntitled) {
            healedUntitled = true
            ChatEngine.healUntitledSessions() // 历史遗留的无标题动作会话补起名
        }
    }

    // 项目大体系：项目文件夹分组，组内再分「进行中 / 已归档」子组。
    // v19.62：**未分类分组已废除**——原来这里有个兜底组收 projectId=0/悬空的会话；
    // 现在 createSession 保证落真实项目，悬空理论上不再产生，所以只按项目渲染。
    // v54：助理中枢是特殊项目（专属界面），不在这里显示——它的会话在中枢界面里看。
    val hubPid = remember { kotlinx.coroutines.runBlocking { runCatching { io.github.m0use001.planability.data.HubProject.idOrNull() }.getOrNull() } }
    val visibleProjects = projects.filter { it.id != hubPid }
    val sections: List<Triple<String, List<ChatSession>, Long>> =
        visibleProjects.map { p -> Triple(p.name, sessions.filter { it.projectId == p.id }.sortedByDescending { it.updatedAt }, p.id) }
            .filter { it.second.isNotEmpty() }

    if (sessions.isEmpty()) {
        Column(
            Modifier.fillMaxSize().padding(contentPadding),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(60.dp))
            EmptyHint("还没有会话，点 + 开始新对话")
        }
    } else {
        val mgrState = androidx.compose.foundation.lazy.rememberLazyListState()
        LazyColumn(
            Modifier.fillMaxSize().padding(contentPadding).reachedEndHaptic(mgrState),
            state = mgrState,
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 10.dp)
        ) {
            sections.forEach { (header, items, pid) ->
                val proj = projects.firstOrNull { it.id == pid }
                item(key = "sec_$header") {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().padding(start = 4.dp, top = 10.dp, bottom = 4.dp)
                    ) {
                        Icon(
                            if (pid == 0L) Icons.Rounded.FolderOff else io.github.m0use001.planability.data.WorkbenchIcons.project(proj?.iconKey ?: "folder"),
                            null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            header,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f)
                        )
                        if (pid > 0L) {
                            IconButton(onClick = { onOpenProject(pid) }, modifier = Modifier.size(24.dp)) {
                                Icon(
                                    Icons.Rounded.Info, "项目详情",
                                    Modifier.size(15.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
                items(items.filter { !it.archived }, key = { it.id }) { s ->
                    // 2026-11-17 用户「会话列表也常常中途卡住然后震动」：列表数据在
                    // Graph.dataVersion 每次变化时**整体重装**，叠加 animateItem 会在滚动中
                    // 反复重启动画/重排（与消息列表 v19.11 去掉 animateItem 是同一个教训）。
                    androidx.compose.foundation.layout.Box {
                        SessionCard(
                            s = s,
                            assistant = assistantsById[s.assistantId],
                            dimmed = false,
                            onOpen = { onOpen(s.id, 0L) },
                            onMove = { moveSessionId = s.id },
                            onDelete = { confirmDeleteId = s.id },
                            onTrace = { traceSessionId = s.id },
                            onRename = { renameSessionId = s.id; renameText = s.title },
                            onTogglePin = { scope.launch { Graph.chat.setPinned(s.id, !s.pinned) } },
                            onToggleArchive = { scope.launch { Graph.chat.setArchived(s.id, !s.archived) } }
                        )
                    }
                }
                val arch = items.filter { it.archived }
                if (arch.isNotEmpty()) {
                    item(key = "archsec_$header") {
                        Text(
                            "已归档（${arch.size}）",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 14.dp, top = 4.dp, bottom = 2.dp)
                        )
                    }
                    items(arch, key = { "arch" + it.id }) { s ->
                        // 已归档组也接上落位动画：把会话归档进来的瞬间没有 animateItem，
                        // 卡片是"凭空出现"的，和上面「进行中」那一组的手感对不上
                        androidx.compose.foundation.layout.Box(Modifier.animateItem()) {
                            SessionCard(
                                s = s,
                                assistant = assistantsById[s.assistantId],
                                dimmed = true,
                                onOpen = { onOpen(s.id, 0L) },
                                onMove = { moveSessionId = s.id },
                                onDelete = { confirmDeleteId = s.id },
                                onTrace = { traceSessionId = s.id },
                                onRename = { renameSessionId = s.id; renameText = s.title },
                                onTogglePin = { scope.launch { Graph.chat.setPinned(s.id, !s.pinned) } },
                                onToggleArchive = { scope.launch { Graph.chat.setArchived(s.id, !s.archived) } }
                            )
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(80.dp)) }
        }
    }

    moveSessionId?.let { sid ->
        io.github.m0use001.planability.ui.info.FolderMoveSheet(sessionId = sid, onDismiss = { moveSessionId = null })
    }

    renameSessionId?.let { rid ->
        AlertDialog(
            onDismissRequest = { renameSessionId = null },
            modifier = Modifier.motionEnter(),
            title = { Text("重命名会话", style = MaterialTheme.typography.titleSmall) },
            text = { OutlinedTextField(value = renameText, onValueChange = { renameText = it }, singleLine = true) },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch { Graph.chat.renameSession(rid, renameText.trim()) }
                    renameSessionId = null
                }) { Text("确定") }
            },
            dismissButton = { TextButton(onClick = { renameSessionId = null }) { Text("取消") } }
        )
    }

    traceSessionId?.let { tid ->
        CallTraceSheet(sessionId = tid, onDismiss = { traceSessionId = null })
    }

    confirmDeleteId?.let { did ->
        io.github.m0use001.planability.ui.common.ConfirmDialog(
            title = "删除会话",
            text = "将删除该会话及其全部消息，不可恢复。",
            onConfirm = {
                scope.launch { Graph.chat.deleteSession(did) }
                confirmDeleteId = null
            },
            onDismiss = { confirmDeleteId = null }
        )
    }
}

/** 会话卡片（项目分组列表）：助手图标 + 标题 + ⋮ 菜单（图2 形态） */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SessionCard(
    s: ChatSession,
    assistant: io.github.m0use001.planability.data.Assistant?,
    dimmed: Boolean,
    onOpen: () -> Unit,
    onMove: () -> Unit,
    onDelete: () -> Unit,
    onTrace: () -> Unit,
    onRename: () -> Unit,
    onTogglePin: () -> Unit,
    onToggleArchive: () -> Unit
) {
    var menuOpen by remember(s.id) { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    val ctx = androidx.compose.ui.platform.LocalContext.current
    Card(
        onClick = onOpen,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .alpha(if (dimmed) 0.55f else 1f)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)
        ) {
            if (assistant != null) {
                Icon(
                    io.github.m0use001.planability.data.WorkbenchIcons.assistant(assistant.key),
                    null, Modifier.size(22.dp),
                    // R11：助手身份图标 = 行首类型标记 → 中性
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.width(10.dp))
            }
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (s.pinned) {
                        Icon(Icons.Rounded.PushPin, null, Modifier.size(13.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(4.dp))
                    }
                    Text(
                        s.title, style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                }
                Text(
                    (assistant?.name?.let { "$it · " } ?: "") + relTime(s.updatedAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(
                        Icons.Rounded.MoreVert, "更多",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                }
                androidx.compose.material3.DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    androidx.compose.material3.DropdownMenuItem(
                        modifier = Modifier.clip(RoundedCornerShape(10.dp)), // 2026-11-15 点击高亮圆角化（原来是整行直角方块）
                        text = { Text(if (s.pinned) "取消置顶" else "置顶") },
                        onClick = { menuOpen = false; onTogglePin() },
                        leadingIcon = { Icon(Icons.Rounded.PushPin, null, Modifier.size(17.dp)) }
                    )
                    androidx.compose.material3.DropdownMenuItem(
                        modifier = Modifier.clip(RoundedCornerShape(10.dp)), // 2026-11-15 点击高亮圆角化（原来是整行直角方块）
                        text = { Text("重命名") },
                        onClick = { menuOpen = false; onRename() },
                        leadingIcon = { Icon(Icons.Rounded.Edit, null, Modifier.size(17.dp)) }
                    )
                    androidx.compose.material3.DropdownMenuItem(
                        modifier = Modifier.clip(RoundedCornerShape(10.dp)), // 2026-11-15 点击高亮圆角化（原来是整行直角方块）
                        text = { Text(if (s.archived) "取消归档" else "归档") },
                        onClick = { menuOpen = false; onToggleArchive() },
                        leadingIcon = { Icon(Icons.Rounded.Archive, null, Modifier.size(17.dp)) }
                    )
                    androidx.compose.material3.DropdownMenuItem(
                        modifier = Modifier.clip(RoundedCornerShape(10.dp)), // 2026-11-15 点击高亮圆角化（原来是整行直角方块）
                        text = { Text("复制会话 ID") },
                        onClick = {
                            menuOpen = false
                            clipboard.setText(AnnotatedString(s.id.toString()))
                            Toast.makeText(ctx, "已复制会话 ID：${s.id}", Toast.LENGTH_SHORT).show()
                        },
                        leadingIcon = { Icon(Icons.Rounded.ContentCopy, null, Modifier.size(17.dp)) }
                    )
                    androidx.compose.material3.DropdownMenuItem(
                        modifier = Modifier.clip(RoundedCornerShape(10.dp)), // 2026-11-15 点击高亮圆角化（原来是整行直角方块）
                        text = { Text("查看调用轨迹") },
                        onClick = { menuOpen = false; onTrace() },
                        leadingIcon = { Icon(Icons.Rounded.BarChart, null, Modifier.size(17.dp)) }
                    )
                    androidx.compose.material3.DropdownMenuItem(
                        modifier = Modifier.clip(RoundedCornerShape(10.dp)), // 2026-11-15 点击高亮圆角化（原来是整行直角方块）
                        text = { Text("移动到项目") },
                        onClick = { menuOpen = false; onMove() },
                        leadingIcon = { Icon(Icons.Rounded.FolderCopy, null, Modifier.size(17.dp)) }
                    )
                    androidx.compose.material3.DropdownMenuItem(
                        modifier = Modifier.clip(RoundedCornerShape(10.dp)), // 2026-11-15 点击高亮圆角化（原来是整行直角方块）
                        text = { Text("删除", color = MaterialTheme.colorScheme.error) },
                        onClick = { menuOpen = false; onDelete() },
                        leadingIcon = { Icon(Icons.Rounded.Delete, null, Modifier.size(17.dp), tint = MaterialTheme.colorScheme.error) }
                    )
                }
            }
        }
    }
}

/** 会话模型选择（v7）：按供应商分组的启用模型列表 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelPickerSheet(
    currentModel: String,
    onDismiss: () -> Unit,
    onPick: (String) -> Unit,
    // 2026-11-01：思考强度并入同一张面板（拖动条），与模型一起选
    thinking: String = "",
    onSetThinking: (String) -> Unit = {}
) {
    // 2026-11-15 用户「模型/思考面板被弄了个与其他弹出菜单明显不同的弹出动效」：
    // 上一版自绘 FixedBottomSheet（Dialog + 自定义滑入）修了抽搐，但动效/遮罩/把手都跟
    // 其他面板不一致。终版回到 **M3 ModalBottomSheet**，只把**拖拽手势关掉**
    // （sheetGesturesEnabled = false）——动效、遮罩、圆角、把手全部与其他面板同源，
    // 而 anchoredDraggable 不参与，列表滚动不再与面板位移抢手势（抽搐的根被摘掉）。
    // 关闭仍走：点遮罩 / 系统返回 / 选中模型。
    ModalBottomSheet(contentWindowInsets = { WindowInsets.navigationBars }, 
        dragHandle = { io.github.m0use001.planability.ui.common.SheetDragHandleOnly() },
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        sheetGesturesEnabled = false
    ) {
        val state by Graph.aiProviders.state.collectAsState()
        // 2026-11-12 用户三报「最底下思考强度汉字被遮住」终修（结构重排）：
        // ①12:20 navigationBarsPadding 前置——无效（ModalBottomSheet 里 inset 被上游消费，
        //   padding 返回 0，v19.14 同款教训）；②20:20 尾部显式 Spacer——能滚到但**打开时
        //   档位行仍在折叠线以下**（20:59 真机截图实证），用户不知道要滚。
        // 现在模型列表自己滚（LazyColumn weight(1f,fill=false)，项目选择 sheet 同形态），
        // 思考强度整段**钉在底部永远可见**——不再依赖任何滚动或 inset 生效。
        Column(
            Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 20.dp)
        ) {
            Text("模型与思考", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                "切换后本会话后续轮次使用新模型；思考强度决定模型是否先想再答。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp, bottom = 8.dp)
            )
            val anyEnabled = state.providers.any { p ->
                p.enabled && state.models.any { it.providerId == p.id && it.enabled }
            }
            if (!anyEnabled) {
                Text(
                    "没有启用中的模型：到 我的 → 设置 → 模型设置 添加并启用。",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error
                )
            }
            androidx.compose.foundation.lazy.LazyColumn(
                modifier = Modifier.weight(1f, fill = false)
            ) {
                state.providers.forEach { p ->
                    val ms = state.models.filter { it.providerId == p.id && it.enabled }
                    if (ms.isEmpty() || !p.enabled) return@forEach
                    item(key = "p" + p.id) {
                        Text(
                            p.name, style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(top = 8.dp, bottom = 2.dp)
                        )
                    }
                    items(ms.size, key = { "m" + p.id + "_" + ms[it].name }) { mi ->
                        val m = ms[mi]
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .clickable { onDismiss(); onPick(m.name) }
                                .padding(horizontal = 8.dp, vertical = 8.dp)
                        ) {
                            Icon(
                                if (m.name == currentModel) Icons.Rounded.CheckCircle else Icons.Rounded.FiberManualRecord,
                                null, Modifier.size(15.dp),
                                tint = if (m.name == currentModel) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(m.name, style = MaterialTheme.typography.bodyMedium)
                            m.tags.split(",").map { it.trim() }.filter { it.isNotEmpty() }.forEach { t ->
                                Text(
                                    t, style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(start = 6.dp)
                                )
                            }
                        }
                    }
                }
            }
            // 2026-11-01：思考强度与模型同屏——拖动条（v54 起五档：关/自适应/low/high/max），
            // 参照 Codex 的「模型列表 + 思考强度滑块」面板形态。2026-11-12 起整段钉底。
            androidx.compose.material3.HorizontalDivider(Modifier.padding(top = 10.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 2.dp)
            ) {
                Text(
                    "思考强度",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.weight(1f))
                Text(
                    ThinkingLevels.label(thinking),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            // v54 五档：关 / 自适应 / low / high / max；旧三档的 "on" 在滑条上落位 high
            val levels = ThinkingLevels.ALL
            val idx = ThinkingLevels.sliderIndex(thinking).toFloat()
            androidx.compose.material3.Slider(
                value = idx,
                onValueChange = { v -> onSetThinking(levels[v.toInt().coerceIn(0, levels.lastIndex)]) },
                valueRange = 0f..(levels.lastIndex).toFloat(),
                steps = (levels.size - 2).coerceAtLeast(0),
                // 未选中段用中性灰：默认的 inactiveTrackColor 会跟着主题偏红，和卡片底反差突兀
                colors = androidx.compose.material3.SliderDefaults.colors(
                    thumbColor = MaterialTheme.colorScheme.primary,
                    activeTrackColor = MaterialTheme.colorScheme.primary,
                    inactiveTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    activeTickColor = Color.Transparent,
                    inactiveTickColor = Color.Transparent
                ),
                modifier = Modifier.padding(top = 4.dp)
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                levels.forEach { lv ->
                    Text(
                        ThinkingLevels.label(lv),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            // 尾部仍垫 16dp + 显式导航栏高（windowInsetsBottomHeight 直读根窗口 inset，
            // 不受上游消费影响）——sheet 底缘与手势条之间留白（7c1203b1 同款机制）
            Spacer(Modifier.height(16.dp))
            Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
        }
    }
}

/**
 * v19.8 运行面板（会话顶栏 ⋮）：一眼看「流程推进到哪一步 / 有哪些智能体在跑 / 各自什么结果」，
 * 点条目可跳到更详细的页面（完整调用轨迹 / 上下文）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RunPanelSheet(
    streaming: Boolean,
    modelName: String,
    assistantLabel: String,
    plannerProgress: String?,
    audit: List<io.github.m0use001.planability.data.ToolAudit>,
    sessionId: Long,
    onDismiss: () -> Unit,
    onOpenTrace: () -> Unit,
    onOpenContext: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val runs by io.github.m0use001.planability.ai.ptl.SubAgentRuns.runs.collectAsState()
    val myRuns = remember(runs, sessionId) { runs.filter { it.sessionId == sessionId } }
    var detailRun by remember { mutableStateOf<io.github.m0use001.planability.ai.ptl.SubAgentRun?>(null) }
    val subAgents = audit.filter { it.tool in SUBAGENT_TOOLS }
    val running = streaming || myRuns.any { it.status == "running" }

    ModalBottomSheet(contentWindowInsets = { WindowInsets.navigationBars }, dragHandle = { io.github.m0use001.planability.ui.common.SheetDragHandleOnly() }, onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(start = 20.dp, end = 20.dp, bottom = 28.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("运行面板", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                // 状态灯：空闲/生成中
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(
                                if (running) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                            )
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        if (running) "运行中" else "空闲",
                        style = MaterialTheme.typography.labelMedium,
                        color = if (running) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Text(
                "$assistantLabel · " + modelName.ifBlank { "未配置模型" },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp)
            )

            // —— 流程推进 ——
            Text(
                "流程推进",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 14.dp, bottom = 4.dp)
            )
            if (!plannerProgress.isNullOrBlank()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // v45 M3E：规划推进是 ≤5s 级的活跃等待 → LoadingIndicator（形状 morph 循环）
                    androidx.compose.material3.LoadingIndicator(
                        Modifier.size(14.dp),
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(plannerProgress, style = MaterialTheme.typography.bodyMedium)
                }
            } else if (running) {
                Text(
                    "正在生成回复…（工具调用会逐条出现在下面）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Text(
                    "当前没有进行中的流程。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // —— 智能体 / 工具 ——
            Text(
                "智能体",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 14.dp, bottom = 4.dp)
            )
            // v19.9：运行中的智能体实时进度（点进去看每一步）
            if (myRuns.isEmpty()) {
                Text(
                    "本会话还没有智能体运行记录。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                myRuns.forEach { r ->
                    val live = detailRun?.id == r.id
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainer)
                            .clickable { detailRun = if (live) null else r }
                            .padding(horizontal = 12.dp, vertical = 10.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                if (r.status == "running") Icons.Rounded.PlayCircle else Icons.Rounded.CheckCircle,
                                contentDescription = null,
                                tint = if (r.status == "running") MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                r.title,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                if (r.status == "running") "第 ${r.stepNo}/${r.total} 步" else "已完成",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (r.status == "running") MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (r.status == "running") {
                            Spacer(Modifier.height(6.dp))
                            // v45 M3E：智能体多步执行是几十秒级的长过程 → 确定进度用波浪进度条。
                            // 不再自己 clip 圆角/压高（会把波形切平），高度由组件描边+振幅决定。
                            androidx.compose.material3.LinearWavyProgressIndicator(
                                progress = { (r.finishedCount.toFloat() / r.total.coerceAtLeast(1)).coerceIn(0f, 1f) },
                                modifier = Modifier.fillMaxWidth()
                            )
                            r.currentLabel?.let {
                                Text(
                                    "当前：" + it,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(top = 4.dp)
                                )
                            }
                        }
                        // 展开：逐步清单
                        if (live) {
                            r.steps.forEach { s ->
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp)
                                ) {
                                    Icon(
                                        when (s.state) {
                                            "done" -> Icons.Rounded.CheckCircle
                                            "running" -> Icons.Rounded.PlayCircle
                                            "failed" -> Icons.Rounded.ErrorOutline
                                            "skipped" -> Icons.Rounded.RadioButtonUnchecked
                                            else -> Icons.Rounded.RadioButtonUnchecked
                                        },
                                        contentDescription = null,
                                        tint = when (s.state) {
                                            "done" -> MaterialTheme.colorScheme.primary
                                            "running" -> MaterialTheme.colorScheme.primary
                                            "failed" -> MaterialTheme.colorScheme.error
                                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                                        },
                                        modifier = Modifier.size(14.dp)
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        s.label,
                                        style = MaterialTheme.typography.labelMedium,
                                        color = if (s.state == "pending" || s.state == "skipped")
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                        else MaterialTheme.colorScheme.onSurface
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Text(
                if (subAgents.isEmpty()) "工具调用" else "工具调用（${subAgents.size}）",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 14.dp, bottom = 4.dp)
            )
            if (audit.isEmpty()) {
                Text(
                    "本会话还没有工具或智能体调用。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                audit.take(8).forEach { a ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp)
                    ) {
                        Icon(
                            when (a.outcome) {
                                "ok" -> Icons.Rounded.CheckCircle
                                "denied" -> Icons.Rounded.Block
                                else -> Icons.Rounded.ErrorOutline
                            },
                            contentDescription = null,
                            tint = when (a.outcome) {
                                "ok" -> MaterialTheme.colorScheme.primary
                                "denied" -> MaterialTheme.colorScheme.onSurfaceVariant
                                else -> MaterialTheme.colorScheme.error
                            },
                            modifier = Modifier.size(17.dp)
                        )
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                toolLabel(a.tool),
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1, overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                (if (a.detail.isNotBlank()) a.detail else a.risk) + " · " + relTime(a.at),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1, overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
                if (audit.size > 8) {
                    Text(
                        "…共 ${audit.size} 条",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // —— 跳详细页 ——
            Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                androidx.compose.material3.OutlinedButton(onClick = onOpenTrace, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Rounded.BarChart, null, Modifier.size(16.dp).padding(end = 4.dp))
                    Text("完整调用轨迹")
                }
                androidx.compose.material3.OutlinedButton(onClick = onOpenContext, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Rounded.Tune, null, Modifier.size(16.dp).padding(end = 4.dp))
                    Text("上下文")
                }
            }
        }
    }
}

/**
 * 2026-11-12 用户「杀后台后 AI 会话里划到的位置也要记忆」：进程级冷启动旗标。
 * 进程被杀再进来后的**第一次**进会话恢复上次阅读位置（chat.readpos KV）；
 * 之后进程内切会话仍按 2026-11-08 指令贴底。进程活着时永远 false。
 */
private var chatReadingRestorePending = true

/** 视为「智能体」的调用（其余为普通工具） */
private val SUBAGENT_TOOLS = setOf("ask_expert", "deep_plan")

/** 工具名 → 用户能看懂的中文标签（v19.10：气泡里不再裸露 web_fetch 这类原始名） */
/** v19.11：审批卡参数键兜底中文名（工具定义里没有的参数才用） */
private fun argKeyCn(key: String): String = when (key) {
    "title" -> "标题"
    "content" -> "内容"
    "text" -> "内容"
    "body" -> "内容"
    "date" -> "日期"
    "start" -> "开始时间"
    "end" -> "结束时间"
    // 2026-10-31：查询类工具用 from/to 表示时间范围，之前直接漏出英文键
    "from" -> "开始日期"
    "to" -> "结束日期"
    "note" -> "备注"
    "place" -> "地点"
    "priority" -> "重要程度"
    "query" -> "关键词"
    "path" -> "路径"
    "name" -> "名称"
    "id" -> "编号"
    "reason" -> "理由"
    else -> key
}

internal fun toolLabel(tool: String): String = when (tool) {
    // v19.11：卡片型消息也会进工具气泡，原始 kind 是英文（qa/plan/…），一并中文化
    "qa" -> "追问"
    "plan" -> "出方案"
    "schedule" -> "排日程"
    "checklist" -> "出清单"
    "file" -> "文件卡片"
    "ask_expert" -> "智能体协作"
    "deep_plan" -> "深度规划"
    "web_fetch" -> "联网抓取"
    "web_search" -> "联网搜索"
    "web_extract" -> "网页正文提取"
    "file_read" -> "读取文件"
    "file_read_image" -> "查看图片"
    "file_write_document" -> "写文档"
    "file_export_document" -> "导出文档"
    "memory_search" -> "检索记忆"
    "memory_write" -> "写入记忆"
    "memory_delete" -> "删除记忆"
    "todo_create" -> "新建待办"
    // 2026-10-31：todo_edit 是真实工具名（todo_update 是当年预留的 PC 风格名字，工具并不存在）
    "todo_update" -> "修改待办"
    "todo_edit" -> "修改待办"
    "todo_complete" -> "完成待办"
    "todo_delete" -> "删除待办"
    "schedule_create" -> "新建日程"
    "schedule_update" -> "修改日程"
    "schedule_delete" -> "删除日程"
    "schedule_delete_today" -> "日程例外日"
    "checkin_query" -> "查打卡"
    "checkin_add" -> "记一笔打卡"
    "screen_time_query" -> "查屏幕时间"
    "plan_category_list" -> "查分类"
    "note_create" -> "新建笔记"
    "note_update" -> "修改笔记"
    "note_search" -> "搜索笔记"
    "note_read" -> "读取笔记"
    "data_history" -> "查历史数据"
    "data_query" -> "查数据"
    "plan_query" -> "查计划"
    "plan_create" -> "新建计划"
    "plan_update" -> "修改计划"
    "notebook_list" -> "查笔记本"
    "notebook_create" -> "建笔记本"
    "note_edit" -> "修改笔记"
    "note_delete" -> "删除笔记"
    "focus_query" -> "查专注"
    "focus_add" -> "记专注"
    "weekly_query" -> "查周小结"
    "achievement_query" -> "查成就"
    "pending_add" -> "存待确认草稿"
    "trash_query" -> "查回收站"
    "trash_restore" -> "回收站恢复"
    "automation_create" -> "建自动化"
    "automation_list" -> "看自动化"
    // 手机端的工具名是 automation_edit（automation_update 是 PC 端的名字，见
    // ◆/planability-cross-end-feature-gap.md §3.10）。
    // 写成 update 的话这里永远匹配不上，「调用轨迹」里就会显示成原始英文 tool 名。
    "automation_edit" -> "改自动化"
    "automation_delete" -> "删自动化"
    "automation_log" -> "查执行日志"
    "assistant_list" -> "看智能体"
    "assistant_create" -> "建智能体"
    "assistant_edit" -> "改智能体"
    "assistant_delete" -> "删智能体"
    "skill_list" -> "看技能"
    "model_switch" -> "切换模型"
    "plugin_toggle" -> "插件开关"
    // phone_* 的中文短标签已已移入可选设备功能包
    // （DeviceToolContribution.toolLabel，由下方 else 兜底经 DeviceFeatures 查询）。
    "mcp_list" -> "看 MCP"
    "mcp_connect" -> "接入 MCP"
    "usage_report" -> "用量统计"
    "undo_last_write" -> "撤销上次改动"
    "sleep_query" -> "查睡眠"
    "countdown_query" -> "查倒数日"
    "capture_list" -> "看采集"
    // v19.11：兜底改用工具注册表里的中文说明——原来未列入映射的工具
    // （schedule_query / todo_query 等）会直接把英文名露给用户
    else -> io.github.m0use001.planability.core.DeviceFeatures.tools?.toolLabel(tool)
        // 设备包的私密工具短标签（phone_* 等）——开源版 tools=null 直接跳过
        ?: io.github.m0use001.planability.ai.ptl.ToolRegistry.ALL
            .firstOrNull { it.name == tool }
            ?.cnDescription
            ?.substringBefore('，')
            ?.substringBefore('（')
            ?.trim()
            ?.takeIf { it.isNotBlank() }
        ?: tool
}

/** 调用轨迹（图2「查看调用轨迹」）：本会话的工具调用审计 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CallTraceSheet(sessionId: Long, onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var audits by remember { mutableStateOf<List<io.github.m0use001.planability.data.ToolAudit>>(emptyList()) }
    LaunchedEffect(sessionId) { audits = Graph.db.toolAuditDao().ofSession(sessionId) }

    ModalBottomSheet(contentWindowInsets = { WindowInsets.navigationBars }, dragHandle = { io.github.m0use001.planability.ui.common.SheetDragHandleOnly() }, onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(start = 20.dp, end = 20.dp, bottom = 28.dp)) {
            Text("调用轨迹", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                "本会话的工具调用审计（时间 / 工具 / 风险 / 结果）",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp, bottom = 8.dp)
            )
            if (audits.isEmpty()) {
                Text(
                    "本会话还没有工具调用。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            audits.forEach { a ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
                    Icon(
                        when (a.outcome) {
                            "ok" -> Icons.Rounded.CheckCircle
                            "denied" -> Icons.Rounded.Block
                            else -> Icons.Rounded.ErrorOutline
                        },
                        null, Modifier.size(14.dp),
                        tint = if (a.outcome == "ok") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                    )
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        // 2026-11-05 用户「工具名太长会把详情挤成换行」：原来名称+风险+结果
                        // 挤在一个 Text 里，长名字（还没中文别名的英文工具名）把整行撑成两行，
                        // 下面的详情被顶走。改成「名字单行省略 + 右侧结果标签」两列。
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                // v19.11：轨迹表也用中文名（原来直接露英文工具名）
                                toolLabel(a.tool),
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                a.risk + " · " + a.outcome,
                                style = MaterialTheme.typography.labelSmall,
                                color = if (a.outcome == "ok") MaterialTheme.colorScheme.onSurfaceVariant
                                else MaterialTheme.colorScheme.error,
                                maxLines = 1
                            )
                        }
                        Text(
                            a.detail.take(60) + " · " + AppTime.millisToTime(a.at),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

/**
 * 2026-11-20 滚动诊断浮层（debug 专属）：把一轮滚动的「固定屏幕点（距底 20%）对应的
 * 内容坐标」随时间画成折线，并标出方向翻转 / 最大回退 / 速度突跳。
 * 正常滚动应为一条单调、平滑、无红段的曲线；出现红段或翻转 = 位置被反向拽/抖动。
 */
@Composable
private fun ScrollTraceOverlay(
    r: io.github.m0use001.planability.core.ScrollTrace.Result,
    stallSnippet: String?,
    modifier: Modifier = Modifier,
) {
    val pos = r.pos
    val minP = pos.minOrNull() ?: 0f
    val maxP = pos.maxOrNull() ?: 1f
    val span = (maxP - minP).takeIf { it > 1e-4f } ?: 1f
    val ok = r.flips == 0 && r.backPx <= 2 &&
        (r.jumpDtMs <= 0 || r.jumpPx.toFloat() / r.jumpDtMs <= 60f)  // ≤60px/ms ≈ 6万px/s 视作正常
    Surface(
        modifier = modifier.width(310.dp).padding(10.dp),
        shape = RoundedCornerShape(10.dp),
        color = Color.Black.copy(alpha = 0.78f),
    ) {
        Column(Modifier.padding(8.dp)) {
            Text(
                "滚动轨迹 · 距底${(r.frac * 100).toInt()}% · n=${r.n} · ${r.durMs}ms · 主导${if (r.dir >= 0) "前进" else "后退"}",
                color = Color(0xFFD0D0D0), fontSize = 10.sp,
            )
            Text(
                "逆向 ${r.flips} 帧 · 最大逆拽 ${r.backPx}px · 突跳 ${r.jumpPx}px" +
                    "(${if (r.jumpV0.isNaN()) "—" else r.jumpV0.toInt()}→" +
                    "${if (r.jumpV1.isNaN()) "—" else r.jumpV1.toInt()})" +
                    "/${r.jumpDtMs}ms@${r.jumpAtMs}ms" +
                    " [手${if (r.jumpDragged) 1 else 0} 程${if (r.jumpProg) 1 else 0} 底${if (r.jumpCf) 1 else 0}]",
                color = if (ok) Color(0xFF6EE08A) else Color(0xFFFF6B6B), fontSize = 11.sp,
            )
            Text(
                "帧 ${r.frames} · 掉帧>20ms×${r.jank20} >34ms×${r.jank40} · 最差 ${r.worstDtMs}ms@${r.worstAtMs}ms",
                color = when {
                    r.jank40 > 0 -> Color(0xFFFF6B6B)
                    r.jank20 > 3 -> Color(0xFFFFB74D)
                    else -> Color(0xFF6EE08A)
                }, fontSize = 11.sp,
            )
            if (r.stallFi >= 0) {
                Text(
                    "卡点条目 #${r.stallFi}" + (stallSnippet?.let { " 「$it…」" } ?: ""),
                    color = Color(0xFFFFB74D), fontSize = 10.sp,
                )
            }
            if (r.worstDtMs > 0 && (r.worstFrameComposeMs > 0 || r.worstFrameMeasureMs > 0 ||
                    r.worstFrameDrawMs > 0)
            ) {
                Text(
                    "最差帧合计 组合${r.worstFrameComposeMs} 测量${r.worstFrameMeasureMs}" +
                        "(${r.worstFrameItems}条) 绘制${r.worstFrameDrawMs}ms",
                    color = Color(if (r.worstFrameComposeMs + r.worstFrameMeasureMs + r.worstFrameDrawMs >
                        r.worstDtMs / 2) 0xFFFF6B6B else 0xFF9AA0A6),
                    fontSize = 10.sp,
                )
            }
            if (r.composeMs > 0) {
                Text(
                    "重组合 ${r.composeMs}ms len=${r.composeLen} 表${r.composeTables} 公式${r.composeMath}@${r.composeAtMs}ms",
                    color = Color(0xFFFFB74D), fontSize = 10.sp,
                )
                if (r.composeParts.isNotBlank()) {
                    Text(
                        "分解 ${r.composeParts}",
                        color = Color(0xFFFFB74D), fontSize = 10.sp,
                    )
                }
            }
            if (r.measureMaxMs >= 0) {
                Text(
                    "测量峰值 ${r.measureMaxMs}ms",
                    color = if (r.measureMaxMs > 16) Color(0xFFFF6B6B) else Color(0xFF6EE08A),
                    fontSize = 10.sp,
                )
            }
            if (r.drawMaxMs >= 0) {
                Text(
                    "绘制录制峰值 ${r.drawMaxMs}ms",
                    color = if (r.drawMaxMs > 16) Color(0xFFFF6B6B) else Color(0xFF6EE08A),
                    fontSize = 10.sp,
                )
            }
            Text(
                "GC 阻塞 ${r.blockingGcCount}次/${r.blockingGcMs}ms · 全部 ${r.gcCount}次/${r.gcMs}ms",
                color = if (r.blockingGcMs > 30) Color(0xFFFF6B6B)
                else if (r.gcMs > 60) Color(0xFFFFB74D) else Color(0xFF6EE08A),
                fontSize = 10.sp,
            )
            if (r.latexCount > 0 || r.latexDeferred > 0) {
                Text(
                    "公式 ${r.latexCount}张·最慢 ${r.latexMaxMs}ms@${r.latexAtMs}ms" +
                        (if (r.latexDeferred > 0) " · 滚动中推迟 ${r.latexDeferred}" else ""),
                    color = if (r.latexMaxMs > 80) Color(0xFFFFB74D) else Color(0xFF9AA0A6),
                    fontSize = 10.sp,
                )
            }
            Canvas(Modifier.fillMaxWidth().height(64.dp).padding(top = 4.dp)) {
                val nn = r.v.size
                if (nn >= 1) {
                    // 2026-11-21 画**像素速度**而不是内容坐标：内容坐标的斜率被条目高矮扭曲
                    // （短条目陡、长条目平），同速滚动也会呈现多个陡峭段，误导判读。
                    // 速度是真正判据：一次手势应是一条平滑「上升-减速」弧；卡顿=速度掉到 0
                    // 再跳起。跨条目边界的 NaN 按住上一值（否则每次过条目都假掉速）。
                    val vv = FloatArray(nn)
                    var lastV = 0f
                    for (i in 0 until nn) {
                        val x = r.v[i]
                        if (!x.isNaN()) lastV = x
                        vv[i] = lastV
                    }
                    val maxAbs = (vv.maxOfOrNull { kotlin.math.abs(it) } ?: 0f).coerceAtLeast(1f)
                    val dx = if (nn > 1) size.width / (nn - 1) else size.width
                    fun yOf(v: Float) = size.height - (kotlin.math.abs(v) / maxAbs) * size.height
                    var py = yOf(vv[0])
                    for (i in 1 until nn) {
                        val y = yOf(vv[i])
                        val along = if (r.dir >= 0) vv[i] >= 0 else vv[i] <= 0
                        drawLine(
                            color = if (along) Color(0xFF7FB2FF) else Color(0xFFFF4444),
                            start = Offset(dx * (i - 1), py),
                            end = Offset(dx * i, y),
                            strokeWidth = 2.5f,
                        )
                        py = y
                    }
                }
            }
            Text(
                "像素速度随时间（应一条平滑弧；掉到0再跳起=卡顿）",
                color = Color(0xFF9AA0A6), fontSize = 9.sp,
            )
        }
    }
}

/**
 * 选区注册器 CompositionLocal（2026-11-21）。
 *
 * Compose 的 `LocalSelectionRegistrar` 在 Kotlin 层是 **internal**，没有公开的「把某棵
 * 子树摘出选区」的办法。会话列表把 SelectionContainer 上提到列表级之后，用户气泡的
 * 长按必须仍然是「消息操作」面板——所以直接引用这个 internal local 并在用户气泡子树里
 * provide null。`INVISIBLE_REFERENCE` 是社区通行做法（引用靠符号，不靠反射，
 * debug 的 R8 改名也不影响；Compose 升版若改了可见性会在编译期暴露，而不是静默失效）。
 */
@Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")
private val noSelectionRegistrar:
    androidx.compose.runtime.ProvidableCompositionLocal<androidx.compose.foundation.text.selection.SelectionRegistrar?> =
    androidx.compose.foundation.text.selection.LocalSelectionRegistrar

/** 会话列表的**条目**（2026-11-21）：一个显示节点通常对应一条，超长助手消息拆成多片。 */
private sealed class MessageListItem(val key: Any) {
    /** 整节点（过程链 / 单条消息 / 压缩卡），老路径渲染。
     *  2026-11-22 用户「回复定稿后排版剧烈变化」根修：单条消息的 key 用 **id#0**
     *  （与切条后的首片同 key）——流式定稿瞬间「整条 → 多片」翻转时，旧 key 不再
     *  消失，LazyColumn 的 key 锚点得以保留，视口不会因丢锚回退序号而瞬移。 */
    class Whole(val node: ChatNode) : MessageListItem(
        when (node) {
            is ChatNode.Single -> "${node.msg.id}#0"
            else -> node.key
        }
    )

    /** 超长助手消息的一片（见 ChatMessageParts.kt）。
     *  v78：只存**片下标**，片内容在渲染时按需 `markdownChunks(content)[index]` + parse，
     *  避免进场时把全部长消息都 parse 一遍（ANR/闪退根因，见 cachedPartCount 注释）。 */
    class Part(
        val node: ChatNode.Single,
        val index: Int,
        val count: Int,
    ) : MessageListItem("${node.msg.id}#$index")
}

/** 条目表 + 「第 i 个节点从第几个条目开始」的换算表（末位 = 总条目数）。 */
private class MessageListBuild(
    val items: List<MessageListItem>,
    val nodeStartItem: IntArray,
)

/**
 * 「到会话末尾」的唯一执行原语（2026-11-23 v78）。
 *
 * 为什么不用 `scrollBy(巨大值)`：正序 LazyColumn 向后滚动时，若目标超出当前已测量范围，
 * 它必须**逐项组合**经过的每个条目才能得知高度 —— 超大会话（870+ 条目 / 含多条长回复）
 * 会在同一帧里把整段历史全部组合出来：主线程 5s+ 无响应（ANR 三次）→ 系统杀进程，
 * 用户看到「进会话就闪退」。`scrollToItem(末项)` 按索引直接定位，只组合末项一条。
 *
 * [steps]：两拍幂等（分片/活行在两次布局之间可能追加一两条，第二拍再收一次）。
 * 末项自身的「残余高度」用末项已测量的 size 精确滚掉，不再猜像素。
 */
private suspend fun androidx.compose.foundation.lazy.LazyListState.settleToLatest(steps: Int = 2) {
    repeat(steps) {
        androidx.compose.runtime.withFrameNanos { }
        val total = layoutInfo.totalItemsCount
        if (total <= 0) return@repeat
        runCatching { scrollToItem(total - 1) }
        androidx.compose.runtime.withFrameNanos { }
        val last = layoutInfo.visibleItemsInfo.lastOrNull { it.index == total - 1 }
            ?: layoutInfo.visibleItemsInfo.lastOrNull()
        if (last != null) {
            // 2026-11-24 修复「贴底后末条顶到视口顶、下面一片空白」（跟随断流的另一半）：
            // scrollToItem(末项) 把末项**顶对齐**；末项比视口矮时（流式开头/活行）残余为负，
            // 旧代码 residual>0 才滚 → 末条停在视口顶、下方空白、canScrollForward 翻 false
            // 还被当成「已贴平」。负残余同样要滚：往回滚把上一屏条目带回来、末条落回视口底。
            val residual = (last.offset + last.size - layoutInfo.viewportEndOffset).toFloat()
            if (residual != 0f) runCatching { scrollBy(residual) }
        }
    }
}

/** 2026-11-21 测量探针（debug）：记录单次消息条目 measure 耗时峰值，上报滚动追踪器。
 *  组合已证实 <10ms，剩余卡顿在 measure/layout——此探针直接量 `measurable.measure()`。 */
private fun Modifier.chatItemMeasureProbe(): Modifier =
    if (!io.github.m0use001.planability.BuildConfig.DEBUG) this
    else layout { measurable, constraints ->
        val t0 = android.os.SystemClock.elapsedRealtime()
        val p = measurable.measure(constraints)
        io.github.m0use001.planability.core.ScrollTrace.noteMeasure(
            (android.os.SystemClock.elapsedRealtime() - t0).toInt()
        )
        layout(p.width, p.height) { p.place(0, 0) }
    }

/** 2026-11-21 绘制探针（debug）：记录单条消息**绘制录制**（主线程生成 display list）峰值。
 *  组合 <10ms、测量 22ms 却仍有 80ms 卡帧时，第三个嫌疑人就是绘制录制与 GC。 */
private fun Modifier.chatItemDrawProbe(): Modifier =
    if (!io.github.m0use001.planability.BuildConfig.DEBUG) this
    else drawWithContent {
        val t0 = android.os.SystemClock.elapsedRealtime()
        drawContent()
        io.github.m0use001.planability.core.ScrollTrace.noteDraw(
            (android.os.SystemClock.elapsedRealtime() - t0).toInt()
        )
    }

/**
 * 输入框的自适应竖直滚动条（2026-11-23 用户「自适应竖直滑动条」）。
 *
 * 只在内容**溢出**时出现（`state.maxValue > 0`）；滑条长度按「视口/内容」比例算，
 * 位置随 `state.value` 移动。3dp 宽、圆头、低对比——不抢文字，但一眼能看出「还有更多」。
 * 不参与任何输入逻辑，纯展示。
 */
@Composable
private fun InputBarScrollbar(
    state: androidx.compose.foundation.ScrollState,
    modifier: Modifier = Modifier
) {
    if (state.maxValue <= 0) return
    androidx.compose.foundation.layout.BoxWithConstraints(
        modifier.padding(top = 8.dp, bottom = 8.dp, end = 2.dp)
    ) {
        val density = androidx.compose.ui.platform.LocalDensity.current
        val viewportPx = constraints.maxHeight.toFloat()
        if (viewportPx <= 0f) return@BoxWithConstraints
        val contentPx = viewportPx + state.maxValue
        val minThumbPx = with(density) { 24.dp.toPx() }
        val thumbPx = (viewportPx * viewportPx / contentPx).coerceIn(minThumbPx, viewportPx)
        val travelPx = (viewportPx - thumbPx).coerceAtLeast(0f)
        val frac = if (state.maxValue > 0) state.value.toFloat() / state.maxValue else 0f
        androidx.compose.foundation.layout.Box(
            Modifier
                .align(Alignment.TopEnd)
                .width(3.dp)
                .height(with(density) { thumbPx.toDp() })
                .offset(y = with(density) { (travelPx * frac).toDp() })
                .clip(RoundedCornerShape(percent = 50))
                .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.42f))
        )
    }
}
