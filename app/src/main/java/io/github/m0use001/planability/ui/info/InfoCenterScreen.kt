package io.github.m0use001.planability.ui.info

import androidx.compose.ui.graphics.Color
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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.AttachFile
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.FolderOff
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SmartToy
import androidx.compose.material.icons.rounded.Sensors
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.m0use001.planability.ai.AiClient
import io.github.m0use001.planability.ai.AiException
import io.github.m0use001.planability.ai.ThinkingLevels
import io.github.m0use001.planability.app.PredictiveBackOverlay
import io.github.m0use001.planability.app.Router
import io.github.m0use001.planability.app.drawerSwipeToClose
import io.github.m0use001.planability.app.edgeSwipeDrawerOpen
import io.github.m0use001.planability.core.AppTime
import io.github.m0use001.planability.core.HapticEngine
import io.github.m0use001.planability.core.hScrollZone
import io.github.m0use001.planability.core.LocalHaptics
import io.github.m0use001.planability.core.formatMonthDay
import io.github.m0use001.planability.core.reachedEndHaptic
import io.github.m0use001.planability.core.toLocalDate
import io.github.m0use001.planability.data.ChatDigest
import io.github.m0use001.planability.data.Graph
import io.github.m0use001.planability.data.PendingItem
import io.github.m0use001.planability.ui.common.EmptyHint
import io.github.m0use001.planability.ui.common.adaptiveColumn
import androidx.compose.runtime.key
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.navigationBarsPadding
// 2026-11-19 平板批：宽屏已无竖线切割（侧栏靠底色分区），VerticalDivider 的 import 已删。
import io.github.m0use001.planability.ui.profile.ModelManagerScreen
import kotlinx.coroutines.launch
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.PictureInPictureAlt
import io.github.m0use001.planability.ui.notes.ChartOptions
import io.github.m0use001.planability.ui.notes.toChartOptions
import io.github.m0use001.planability.ui.common.motionEnter
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import io.github.m0use001.planability.ui.common.Motion
import io.github.m0use001.planability.ui.common.MotionTabIndicator

/**
 * 会话页右滑进抽屉时的会话上下文快照（2026-11-02）：抽屉里的返回要能回到刚才的会话。
 * 右滑回调会清空 chat* 状态再开抽屉；快照让 BackHandler 能原样恢复（滑关/遮罩关则丢弃）。
 */
internal data class ChatSwipeResume(
    val sessionId: Long?,
    val assistantId: Long,
    val prefill: String,
    val folderId: Long,
    val autoSend: Boolean,
    val files: List<PendingAttachment>,
    val linked: List<io.github.m0use001.planability.ai.LinkedRef>,
    val draftModel: String,
    val draftThinking: String,
    val draftPermJson: String
)

// v50.3（状态记忆审查 #1/#2）：首页输入卡的记忆键。
// 草稿文本 600ms 去抖；档位/项目/附件清单低频直写。发送清空时一并清空，
// 否则恢复逻辑会把旧草稿注回（恢复只在「当前为空」时注入，见 InfoCenterScreen）。
private const val KEY_HOME_DRAFT = "shell.freeze.homeinput.text"
private const val KEY_HOME_MODEL = "shell.freeze.homeinput.model"
private const val KEY_HOME_THINK = "shell.freeze.homeinput.thinking"
private const val KEY_HOME_ASSIST = "shell.freeze.homeinput.assistant"
private const val KEY_HOME_PROJECT = "shell.freeze.homeinput.project"
private const val KEY_HOME_ATTACH = "shell.freeze.homeinput.attach"

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun InfoCenterScreen() {
    val scope = rememberCoroutineScope()
    val drawerState = androidx.compose.material3.rememberDrawerState(
        androidx.compose.material3.DrawerValue.Closed
    )
    // 2026-11-19 平板会话页批：宽窄判断**只此一处**（形态由 ui/info/ChatWideLayout.kt 的
    // 决策机裁决，页面里不再散落 if (wide) 决定长什么样）。
    // v64 根修（2026-11-20）：宽窄判断改**实测窗宽**（≥840dp）。原来读
    // `screenWidthDp`——分屏 / 自由窗口里它报显示器宽，452dp 的窗口被当成宽屏，
    // 宽屏两板 + Rail 把内容挤成一条缝；现在 600–840dp 退回手机形态（抽屉 + 药丸底栏）。
    val wide = io.github.m0use001.planability.core.rememberShellRailForm()
    // 宽屏：工作台树**常驻成左栏**，抽屉这个浮层形态整个不用了（它和左栏画的是同一批会话，
    // 两处都画 = 用户报的「重复」）。所以宽屏下一切"关抽屉"都变成空操作。
    fun closeDrawer() {
        if (!wide) scope.launch { drawerState.close() }
    }
    var showCapture by remember { mutableStateOf(false) }
    var showGroupDigest by remember { mutableStateOf(false) }
    var showPending by remember { mutableStateOf(false) }
    // v53 助理中枢：抽屉「待处理」升级入口（草稿卡片流 + 私人助理对话）
    var showAssistantHub by remember { mutableStateOf(false) }
    // 2026-11-11：中枢页（1198 行）与对话页（1449 行）同级叠放——对话页后组合=画在上层，
    // 从会话里点「中枢功能」中枢其实开了但被会话页盖住（点胶囊"没反应"的根因，原 ⋮ 菜单
    // 路径同病）。修法：进中枢前先收对话页；中枢返回时若刚才是从会话进来的，回到该会话。
    var hubReturnSessionId by remember { mutableStateOf<Long?>(null) }
    var showModel by remember { mutableStateOf(false) }
    var showChat by remember { mutableStateOf(false) }
    var chatSessionId by remember { mutableStateOf<Long?>(null) }
    var chatAssistantId by remember { mutableStateOf(0L) }
    var chatPrefill by remember { mutableStateOf("") }
    // 十三期：首页输入卡 v2 草稿（附件/@勾连/模型/思考档/权限档），发送时随 draft 传入新会话
    var chatFiles by remember { mutableStateOf(listOf<PendingAttachment>()) }
    var chatLinked by remember { mutableStateOf(listOf<io.github.m0use001.planability.ai.LinkedRef>()) }
    var chatDraftModel by remember { mutableStateOf("") }
    var chatDraftThinking by remember { mutableStateOf("") }
    var chatDraftPermJson by remember { mutableStateOf("") }
    // 2026-11-02 用户「右滑出抽屉再返回，直接到信息首页了」：右滑回调会关会话开抽屉，
    // 抽屉里的返回（BackHandler）只关抽屉——用户预期是**回到刚才的会话**。右滑时把
    // 会话上下文快照在这里，抽屉返回时若有快照则恢复会话；滑关/遮罩关则丢弃（用户放弃）。
    var chatResume by remember {
        mutableStateOf<io.github.m0use001.planability.ui.info.ChatSwipeResume?>(null)
    }
    var showSessions by remember { mutableStateOf(false) }
    // 图表默认值的实时快照（见下方 chartDefaults 的派生）
    val settingsChartSnapshot by io.github.m0use001.planability.data.Graph.settings.snapshot.collectAsState()
    var showMemory by remember { mutableStateOf(false) }
    // 图表全局默认设置（抽屉里「能力设置」正下方那个入口）
    var showChartDefaults by remember { mutableStateOf(false) }
    var showAiHealth by remember { mutableStateOf(false) }
    // 全局图表默认：图表右上角三点菜单的初值来源。
    //
    // 从 settings snapshot **响应式派生**，不自己存一份 state：
    // 这份默认值有三个写入点（图表三点菜单「存为全局默认」、抽屉里的设置页、
    // 「我的」页里的设置页），自己存 state 就得在每个写入点手动同步，
    // 漏一处就出现「在别的入口改完，这里的图不跟着变」。派生自同一个 snapshot 就没这问题。
    val chartDefaults = settingsChartSnapshot.toChartOptions()
    // v6 首页：只留输入框；其余进抽屉
    var homeInput by remember { mutableStateOf("") }
    // v50.3（状态记忆审查 #1/#2）：首页输入卡的草稿与档位落 KV（shell.freeze.homeinput.*）。
    // 此前只有执行模式（perm.home）持久化——同一张卡上模型/思考档/人格/项目与草稿文本
    // 重启即丢，与聊天草稿（chatdraft 体系）行为不一致。
    // 恢复/保存逻辑见下方 LaunchedEffect；发送清空时一并清 KV（否则恢复会注回旧草稿）。
    // 十三期：首页输入卡 v2 状态
    var homeFiles by remember { mutableStateOf(listOf<PendingAttachment>()) }
    var homeLinked by remember { mutableStateOf(listOf<io.github.m0use001.planability.ai.LinkedRef>()) }
    var homeModel by remember { mutableStateOf("") }
    var homeThinking by remember { mutableStateOf("") }
    var homePermKey by remember { mutableStateOf("confirm") }
    // 2026-10-29：首页选的档位持久化。原先每次启动都重置成「变更前确认」，
    // 界面看着像一个已保存的全局默认，实际重启就丢 —— 名实不符。
    LaunchedEffect(Unit) {
        Graph.settings.kvGet("perm.home")?.takeIf { it in PermModes.ALL.map { m -> m.key } }
            ?.let { homePermKey = it }
    }
    var showHomeModelPicker by remember { mutableStateOf(false) }
    var showHomePermMenu by remember { mutableStateOf(false) }
// v19.10：输入卡人格（智能体）选择
var showHomeAssistantPicker by remember { mutableStateOf(false) }
var homeAssistantId by remember { mutableStateOf(0L) }
var homeAssistantName by remember { mutableStateOf("") }
    var showHomeLinkPicker by remember { mutableStateOf(false) }
    var homeMemoryQuick by remember { mutableStateOf<String?>(null) }
    var chatProjectId by remember { mutableStateOf(0L) }
    var chatProjectName by remember { mutableStateOf("未选择项目") }
    var chatProjectIconKey by remember { mutableStateOf("folder") }
    var chatFolderId by remember { mutableStateOf(0L) }
    var chatAutoSend by remember { mutableStateOf(false) }
    var showProjectPicker by remember { mutableStateOf(false) }
// v19.12：输入区第二层的「设置摘要」面板（人格 / 模型 / 思考）
var showHomeSetup by remember { mutableStateOf(false) }
    // v50.3（状态记忆审查 #1/#2）：首页输入卡的草稿与档位恢复/保存。
    // 必须放在所有相关 state 声明之后（Compose 的 effect 体虽延迟执行，但引用
    // 未声明的局部变量直接编译不过——这一版真被编译器拦过一次）。
    // 恢复时机：UiFreeze 就绪后；文本只在当前为空时注入（绝不覆盖正在打的字）；
    // 人格/项目查不到就忽略（被删了不留悬空 id）。
    LaunchedEffect(Unit) {
        io.github.m0use001.planability.app.UiFreeze.awaitLoaded()
        Graph.settings.kvGet(KEY_HOME_MODEL)?.takeIf { it.isNotBlank() }?.let { homeModel = it }
        Graph.settings.kvGet(KEY_HOME_THINK)?.takeIf { it.isNotBlank() }?.let { homeThinking = it }
        Graph.settings.kvGet(KEY_HOME_ASSIST)?.toLongOrNull()?.takeIf { it > 0L }?.let { id ->
            val a = runCatching { Graph.db.assistantDao().all().firstOrNull { it.id == id } }.getOrNull()
            if (a != null) { homeAssistantId = a.id; homeAssistantName = a.name }
        }
        Graph.settings.kvGet(KEY_HOME_PROJECT)?.toLongOrNull()?.takeIf { it > 0L }?.let { pid ->
            val p = runCatching { Graph.db.projectDao().all().firstOrNull { it.id == pid } }.getOrNull()
            if (p != null) {
                chatProjectId = p.id
                chatProjectName = p.name
                chatProjectIconKey = p.iconKey.ifBlank { "folder" }
            }
        }
        if (homeInput.isBlank()) {
            Graph.settings.kvGet(KEY_HOME_DRAFT)?.takeIf { it.isNotBlank() }?.let { homeInput = it }
        }
        // 附件/@勾连清单：JSON 往返（纯标量字段）
        runCatching {
            Graph.settings.kvGet(KEY_HOME_ATTACH)?.takeIf { it.isNotBlank() }?.let { s ->
                val o = org.json.JSONObject(s)
                o.optJSONArray("files")?.let { arr ->
                    homeFiles = List(arr.length()) { i ->
                        val f = arr.getJSONObject(i)
                        PendingAttachment(f.getString("relPath"), f.getString("name"), f.getString("tool"))
                    }
                }
                o.optJSONArray("linked")?.let { arr ->
                    homeLinked = List(arr.length()) { i ->
                        val l = arr.getJSONObject(i)
                        io.github.m0use001.planability.ai.LinkedRef(
                            l.getString("kind"), l.getLong("id"), l.getString("label")
                        )
                    }
                }
            }
        }
    }
    // 文本 600ms 去抖；档位/项目/附件低频直写。UiFreeze 关闭时 save 为 no-op（同 chatdraft 体系）。
    LaunchedEffect(homeInput) {
        io.github.m0use001.planability.app.UiFreeze.save(KEY_HOME_DRAFT, homeInput, debounceMs = 600)
    }
    LaunchedEffect(
        homeModel, homeThinking, homeAssistantId, chatProjectId,
        chatProjectName, chatProjectIconKey, chatFolderId, homeFiles, homeLinked
    ) {
        Graph.settings.kvPut(KEY_HOME_MODEL, homeModel)
        Graph.settings.kvPut(KEY_HOME_THINK, homeThinking)
        Graph.settings.kvPut(KEY_HOME_ASSIST, homeAssistantId.toString())
        Graph.settings.kvPut(KEY_HOME_PROJECT, chatProjectId.toString())
        val o = org.json.JSONObject()
            .put("projectName", chatProjectName)
            .put("projectIconKey", chatProjectIconKey)
            .put("folderId", chatFolderId)
        o.put(
            "files", org.json.JSONArray().apply {
                homeFiles.forEach {
                    put(org.json.JSONObject().put("relPath", it.relPath).put("name", it.name).put("tool", it.tool))
                }
            }
        )
        o.put(
            "linked", org.json.JSONArray().apply {
                homeLinked.forEach {
                    put(org.json.JSONObject().put("kind", it.kind).put("id", it.id).put("label", it.label))
                }
            }
        )
        io.github.m0use001.planability.app.UiFreeze.save(KEY_HOME_ATTACH, o.toString())
    }
    var showProjectDetail by remember { mutableStateOf<Long?>(null) }
    var showUsage by remember { mutableStateOf(false) }
    var showAutomation by remember { mutableStateOf(false) }
    // v12：抽屉新入口
    var showFileLibrary by remember { mutableStateOf(false) }
    // 同步中心（2026-10-24 用户拍板：文件页签撤除，原文件页的 Syncthing 入口移到这里）
    var showSync by remember { mutableStateOf(false) }
    // 已配置的远程设备与连接状态（同步中心胶囊下方的胶囊列）。内核在跑才轮询本机 REST
    // （/rest/config/devices + /rest/system/connections），15s 一拍；DeviceRow 复用
    // SyncthingScreen 的行模型。engine 没跑 = 空列表 = 该区块只剩「同步中心」入口胶囊。
    var syncDevices by remember {
        mutableStateOf(emptyList<io.github.m0use001.planability.ui.files.DeviceRow>())
    }
    LaunchedEffect(Unit) {
        while (true) {
            val ctx = io.github.m0use001.planability.data.Graph.appContext
            syncDevices = if (io.github.m0use001.planability.syncthing.SyncthingEngine.isRunning) {
                runCatching {
                    val dArr = org.json.JSONArray(
                        io.github.m0use001.planability.syncthing.SyncthingEngine.configDevices(ctx)
                    )
                    val conns = org.json.JSONObject(
                        io.github.m0use001.planability.syncthing.SyncthingEngine.connections(ctx)
                    ).optJSONObject("connections")
                    (0 until dArr.length()).map { i ->
                        val o = dArr.getJSONObject(i)
                        val id = o.getString("deviceID")
                        io.github.m0use001.planability.ui.files.DeviceRow(
                            id = id,
                            name = o.optString("name"),
                            connected = conns?.optJSONObject(id)?.optBoolean("connected") == true
                        )
                    }.filter { it.id != io.github.m0use001.planability.syncthing.SyncthingEngine.state.value.myId }
                    // /rest/config/devices 恒含本机自己，过滤逻辑与 SyncthingScreen 一致
                }.getOrDefault(emptyList())
            } else emptyList()
            kotlinx.coroutines.delay(15_000)
        }
    }
    // AI能力开关（原能力设置+插件设置的合并态，见 ui/info/SettingsHub.kt）
    var showAiCap by remember { mutableStateOf(false) }
    // v50.5：工具与扩展总汇（能力与插件 / MCP / 命令）
    var showToolsHub by remember { mutableStateOf(false) }
    // v53.3：资料总汇（记忆库 / 文件库 / 归档项目），抽屉底部瘦身后三者共用这一个入口
    var showDataHub by remember { mutableStateOf(false) }
// v19.33：phone use 的 AI 可视/可操作区域设置
var showPhoneUseRegion by remember { mutableStateOf(false) }
    var automationTab by remember { mutableIntStateOf(0) }
    var showCommands by remember { mutableStateOf(false) }
    var showVoiceInput by remember { mutableStateOf(false) }

    // 2026-11-01 交互审查：切到别的 tab 时收掉页内二级页（同 HomeScreen/PlanScreen 的教训）。
    // 本页常驻组合，二十来个 showXxx 的 Dialog 若留着，会浮在用户刚跳去的那页上面；
    // 而且 chat（对话全屏页）也有独立 Dialog 窗口，同样会盖住目标页。
    // 只在「**从本页切走**」那一刻清：冷启动 currentTab 从空串变目标页时不动它，
    // 否则冻结恢复（会话/草稿）会被这一刀抹掉。
    var prevInfoTab by remember { mutableStateOf(io.github.m0use001.planability.app.ShellNav.currentTab) }
    LaunchedEffect(io.github.m0use001.planability.app.ShellNav.currentTab) {
        val t = io.github.m0use001.planability.app.ShellNav.currentTab
        if (prevInfoTab == "info" && t != "info") {
            showCapture = false; showGroupDigest = false; showPending = false
            showModel = false; showChat = false; chatSessionId = null
            showSessions = false; showMemory = false; showChartDefaults = false; showAiHealth = false
            showHomeModelPicker = false; showHomePermMenu = false
            showHomeAssistantPicker = false; showHomeLinkPicker = false
            showProjectPicker = false; showHomeSetup = false; showProjectDetail = null
            showUsage = false; showAutomation = false; showFileLibrary = false
            showSync = false; showAiCap = false; showPhoneUseRegion = false
            showCommands = false; showVoiceInput = false; showToolsHub = false
            showDataHub = false
            scope.launch { drawerState.close() }
        }
        prevInfoTab = t
    }
    var pendingCount by remember { mutableIntStateOf(0) }
    var sessionCount by remember { mutableIntStateOf(0) }
    var memoryCount by remember { mutableIntStateOf(0) }
    // 抽屉数据：项目 + 会话 + 自动化（v18.1：自动化文件夹）
    var drawerProjects by remember { mutableStateOf<List<io.github.m0use001.planability.data.Project>>(emptyList()) }
    var drawerSessions by remember { mutableStateOf<List<io.github.m0use001.planability.data.ChatSession>>(emptyList()) }
    var drawerAutomations by remember { mutableStateOf<List<io.github.m0use001.planability.data.Automation>>(emptyList()) }
    var autoAnchors by remember { mutableStateOf<Map<Long, Long>>(emptyMap()) }
    val version by Graph.dataVersion.flow.collectAsState()
    val settings by Graph.settings.snapshot.collectAsState()
    val hapticsHome = LocalHaptics.current

    // 十三期：首页附件选择器（逻辑同 ChatScreen：复制进 attachments/ → 待发送列表）
    val homePickImage = androidx.activity.compose.rememberLauncherForActivityResult(
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
                        homeFiles = homeFiles + PendingAttachment("attachments/" + name, name, "file_read_image")
                    }
                }.onFailure {
                    android.widget.Toast.makeText(
                        io.github.m0use001.planability.data.Graph.appContext, "图片导入失败：" + it.message,
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }
    val homePickFile = androidx.activity.compose.rememberLauncherForActivityResult(
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
                        homeFiles = homeFiles + PendingAttachment("attachments/" + name, safeName, toolForFile(safeName))
                    }
                }.onFailure {
                    android.widget.Toast.makeText(
                        io.github.m0use001.planability.data.Graph.appContext, "附件导入失败：" + it.message,
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    LaunchedEffect(version) {
        pendingCount = Graph.info.pendingCount()
        sessionCount = Graph.chat.sessionCount()
        memoryCount = Graph.memories.count()
        // v54：助理中枢是特殊项目——有专属界面，不进「项目与会话」树（会话都在中枢界面里看）
        val hubPid = runCatching { io.github.m0use001.planability.data.HubProject.idOrNull() }.getOrNull()
        drawerProjects = Graph.projects.all().filter { it.id != hubPid }
        drawerSessions = Graph.chat.sessions()
        // v18.1：抽屉自动化文件夹数据（全部任务 + 锚点会话映射）
        val autos = Graph.db.automationDao().all()
        drawerAutomations = autos
        autoAnchors = autos.mapNotNull { a ->
            runCatching { Graph.settings.kvGet("auto.session:${a.id}")?.toLongOrNull() }
                .getOrNull()?.let { a.id to it }
        }.toMap()
    }
    // key 带上 version（2026-10-28 用户：输入框项目名与抽屉不一致）——项目改名后
    // chatProjectId 不变，只有 id 做 key 时 chip 会一直留着改名前的旧名字
    LaunchedEffect(chatProjectId, version) {
        val p = Graph.projects.byId(chatProjectId)
        chatProjectName = p?.name ?: "未分类"
        chatProjectIconKey = p?.iconKey ?: "folder"
    }
    // 跨屏标志一律用**以标志本身为 key** 的 effect 消费，别放回 LaunchedEffect(Unit)：
    // NavShell 里各 tab 首次访问后就一直处于组合状态（beyondViewportPageCount = tabs.size），
    // 本页早就在组合时 LaunchedEffect(Unit) 不会重跑 —— 表现为「tab 切过去了、页面没开」，
    // 而标志已经被读掉（2026-10-06 region 入口第一次就这么没开成；phone-b 01:32 又点了另外三个同款）。
    //
    // 后台冻结（2026-10-30 用户需求）：进程被杀后重进，回到上次的会话界面。
    // 深链（分享直达/会话直达）必须在**组合期**捕获——下面那些深链 effect 排在前面、
    // 跑完就清标志，等本 effect 挂起读库回来看是空的，会把深链刚开好的会话又覆盖掉。
    // 会话必须仍存在才恢复（防恢复进已删除的会话）；恢复完成前记录 effect 不开写。
    val chatDeepLinkAtCompose =
        io.github.m0use001.planability.app.Router.pendingSharedToChat != null ||
            io.github.m0use001.planability.app.Router.pendingSessionId != 0L
    var chatStateRestored by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        io.github.m0use001.planability.app.UiFreeze.awaitLoaded()
        if (io.github.m0use001.planability.app.UiFreeze.enabled && !chatDeepLinkAtCompose) {
            val open = Graph.settings.kvGet("shell.freeze.info.chatOpen")
            val sid = Graph.settings.kvGet("shell.freeze.info.session")?.toLongOrNull() ?: 0L
            if (open == "1" && sid > 0L) {
                val exists = runCatching { Graph.chat.sessions().any { it.id == sid } }
                    .getOrDefault(false)
                if (exists) {
                    chatSessionId = sid
                    showChat = true
                }
            }
        }
        chatStateRestored = true
    }
    LaunchedEffect(chatStateRestored, showChat, chatSessionId) {
        if (!chatStateRestored || !io.github.m0use001.planability.app.UiFreeze.enabled) return@LaunchedEffect
        Graph.settings.kvPut(
            "shell.freeze.info.chatOpen", if (showChat && chatSessionId != null) "1" else "0"
        )
        Graph.settings.kvPut("shell.freeze.info.session", chatSessionId?.toString() ?: "")
    }

    // M5 分享直达对话：ACTION_SEND → 本页 → AI 对话预填
    LaunchedEffect(io.github.m0use001.planability.app.Router.pendingSharedToChat) {
        val shared = io.github.m0use001.planability.app.Router.pendingSharedToChat
        if (shared != null) {
            // 消费后置 null：key 随之变化会再进一次 effect，此时 shared == null 直接空转，不会重复开页
            io.github.m0use001.planability.app.Router.pendingSharedToChat = null
            chatSessionId = null
            chatAssistantId = homeAssistantId
            chatPrefill = shared
            showChat = true
        }
    }
    // v24：导入分享对话后跳到那条会话（phone-b 10-08 加，形状照上面 pendingSharedToChat）
    LaunchedEffect(io.github.m0use001.planability.app.Router.pendingSessionId) {
        val sid = io.github.m0use001.planability.app.Router.pendingSessionId
        if (sid != 0L) {
            io.github.m0use001.planability.app.Router.pendingSessionId = 0L
            chatSessionId = sid
            showChat = true
        }
    }
    // v12：时间轴自动化块 → 打开自动化管理页
    LaunchedEffect(io.github.m0use001.planability.app.Router.pendingAutomationOpen) {
        if (io.github.m0use001.planability.app.Router.pendingAutomationOpen) {
            io.github.m0use001.planability.app.Router.pendingAutomationOpen = false
            showAutomation = true
        }
    }
    // v16 批：跨页跳转信息获取专属界面
    LaunchedEffect(io.github.m0use001.planability.app.Router.pendingGroupDigestOpen) {
        if (io.github.m0use001.planability.app.Router.pendingGroupDigestOpen) {
            io.github.m0use001.planability.app.Router.pendingGroupDigestOpen = false
            showGroupDigest = true
        }
    }
    // v21：phone-b 在「我的 → 设置」加了手机操控区域入口，跨屏路由由本页消费
    LaunchedEffect(io.github.m0use001.planability.app.Router.pendingPhoneUseRegionOpen) {
        if (io.github.m0use001.planability.app.Router.pendingPhoneUseRegionOpen) {
            io.github.m0use001.planability.app.Router.pendingPhoneUseRegionOpen = false
            showPhoneUseRegion = true
        }
    }

    // 抽屉打开期间锁外壳：隐藏底栏（非一级界面）——展开动画一开始就锁（与搜索抽屉同款），
    // 只用 isOpen 会等动画走完才隐藏，右滑过程中底栏仍亮着
    if (drawerState.isOpen || drawerState.isAnimationRunning && drawerState.targetValue == androidx.compose.material3.DrawerValue.Open) {
        io.github.m0use001.planability.core.LockShellChrome()
    }
    // v10：back 键显式关抽屉（全面屏手势冲突时兜底）。
    // 2026-11-02：从会话页右滑进来的返回要**恢复刚才的会话**（用户「右滑出抽屉再返回
    // 直接掉到信息首页」）——手势滑关/点遮罩关不恢复（用户主动放弃），快照在抽屉
    // 关闭时统一清理（见下方 LaunchedEffect）。
    androidx.activity.compose.BackHandler(enabled = drawerState.isOpen) {
        val resume = chatResume
        if (resume != null) {
            chatResume = null
            chatSessionId = resume.sessionId
            chatAssistantId = resume.assistantId
            chatPrefill = resume.prefill
            chatFolderId = resume.folderId
            chatAutoSend = resume.autoSend
            chatFiles = resume.files
            chatLinked = resume.linked
            chatDraftModel = resume.draftModel
            chatDraftThinking = resume.draftThinking
            chatDraftPermJson = resume.draftPermJson
            showChat = true
        }
        scope.launch { drawerState.close() }
    }
    // 抽屉以非返回方式关闭（手势滑关/点遮罩/抽屉内点入口）→ 丢弃会话快照
    LaunchedEffect(drawerState.isOpen) {
        if (!drawerState.isOpen) chatResume = null
    }

    // v19.14：**从抽屉进入的子页，返回时先回抽屉**，而不是直接退回工作台。
    //
    // 为什么统一包一层：抽屉能进的子页有二十来个。逐个去各自的 onDone 手写
    // "关完再打开抽屉"，漏一个就是一个「按返回跳回工作台」的 bug——
    // 而这种 bug 只有真机按返回键才暴露，单测和 code review 都很难发现。
    // 包一层之后调用点统一成 `onDone = backToDrawer { ... }`，
    // 有没有漏一眼能看出来（原写法是 `onDone = {`）。
    //
    // 为什么不在 PredictiveBackOverlay 里做：子页都在它的 Dialog（独立窗口）里，
    // 返回键先被 Dialog 吃掉。而抽屉是 InfoCenterScreen 的局部状态，
    // 让通用外壳知道"抽屉"会把局部状态泄漏出去，所以在这一层解决。
    // v19.14：记住当前子页是不是**从抽屉进来的**。
    //
    // 为什么需要：自动化、手机操控区域这些既能从抽屉进，也能从首页经
    // Router.openAutomations 进，而且原代码已有 returnToSourceTab 机制
    // （原注释：「从信息页抽屉进来时没记来源，直接返回、原地不动」）。
    // 所以返回该去抽屉、还是回来源 tab，只有记下入口才知道。
    var enteredFromDrawer by remember { mutableStateOf(false) }

    // 返回到"进来的地方"：抽屉进的回抽屉，别的 tab 进的回那个 tab。
    // 用于既有 returnToSourceTab 机制、又新增了"回抽屉"要求的子页。
    val backToEntry: (() -> Unit) -> () -> Unit = { closeIt ->
        {
            closeIt()
            // 宽屏：左栏就是那棵树，返回时**不弹抽屉**（弹了 = 同一份内容画第二遍）。
            if (enteredFromDrawer) {
                if (!wide) scope.launch { drawerState.open() }
            } else io.github.m0use001.planability.app.ShellNav.returnToSourceTab("info")
            enteredFromDrawer = false
        }
    }

    val backToDrawer: (() -> Unit) -> () -> Unit = { closeIt ->
        {
            closeIt()
            if (!wide) scope.launch { drawerState.open() }
        }
    }

    // 关掉聊天相关的全部状态（手机路径 onDone 与宽屏返回共用）
    fun closeChatAll() {
        showChat = false; chatSessionId = null; chatAssistantId = homeAssistantId; chatPrefill = ""; chatAutoSend = false; chatFolderId = 0L
        chatFiles = emptyList(); chatLinked = emptyList(); chatDraftModel = ""; chatDraftThinking = ""; chatDraftPermJson = ""
        showSessions = false
        showProjectDetail = null
        enteredFromDrawer = false
    }

    // 宽屏：只关右栏对话，侧栏树留在场（树还开着就说明用户还在挑会话）
    fun closeChatPane() {
        showChat = false; chatSessionId = null; chatAssistantId = homeAssistantId; chatPrefill = ""; chatAutoSend = false; chatFolderId = 0L
        chatFiles = emptyList(); chatLinked = emptyList(); chatDraftModel = ""; chatDraftThinking = ""; chatDraftPermJson = ""
    }

    // chatNonce：宽屏右栏常驻后，新建会话**不回写** chatSessionId（参数不变、同步 effect 不触发），
    // 右栏会一直停在旧会话上（平板实测抓到）。这个 nonce 强制 ChatScreen 整体重组。
    var chatNonce by remember { mutableIntStateOf(0) }

    // 把全局图表默认注入整棵组合树：图表右上角三点菜单的初值从这里来。
    // 必须包住**整个界面**（抽屉 + 对话区 + 所有二级页），而不是只包某一块 ——
    // 图表会出现在聊天气泡里、笔记里、弹出的文件预览里，各处 provide 一份
    // 就会出现「笔记里的图变了、聊天里的图没变」。
    androidx.compose.runtime.CompositionLocalProvider(
        io.github.m0use001.planability.ui.notes.LocalChartDefaults provides chartDefaults
    ) {
    // 抽屉底部安全区：在抽屉**之外**量好再传进去（抽屉自身 WindowInsets(0)，内部读到的是 0）
    val drawerBottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    // 2026-11-19 平板批：工作台内容抽成**唯一一份** lambda（窄屏进抽屉，宽屏常驻成左栏）。
    // 原来平板左栏另画了一份 ChatSessionsBody（AI 会话 + 每行 ⋮），和抽屉里的
    // 「项目与会话」树画的是同一批会话——两处都画 = 用户报的「重复」。
    // 现在全局只有这一份：宽屏它是侧栏，窄屏它是抽屉。
    val drawerBody: @Composable (androidx.compose.ui.unit.Dp, Boolean) -> Unit = { bottomInsetArg, dismissableArg ->
        WorkbenchDrawerContent(
            onChartDefaults = {
                enteredFromDrawer = true
                closeDrawer()
                showChartDefaults = true
            },
            onAiHealth = {
                enteredFromDrawer = true
                closeDrawer()
                showAiHealth = true
            },
            projects = drawerProjects,
            sessions = drawerSessions,
            memoryCount = memoryCount,
            pendingCount = pendingCount,
            onClose = { enteredFromDrawer = true
                closeDrawer() },
            onNewChat = {
                enteredFromDrawer = true
                closeDrawer()
                chatSessionId = null
                chatPrefill = ""
                chatFolderId = 0L
                showChat = true
            },
            onNewChatIn = { pid, fid ->
                enteredFromDrawer = true
                closeDrawer()
                chatSessionId = null
                chatPrefill = ""
                chatProjectId = pid
                chatFolderId = fid
                showChat = true
            },
            onOpenSession = { id ->
                enteredFromDrawer = true
                closeDrawer()
                chatSessionId = id
                showChat = true
            },
            onManageSessions = {
                enteredFromDrawer = true
                closeDrawer()
                showSessions = true
            },
            onMemory = { enteredFromDrawer = true
                closeDrawer(); showMemory = true },
            // v16 批：信息获取入口改开专属界面（群聊采集/提纯归档）
            onCapture = { enteredFromDrawer = true
                closeDrawer(); showGroupDigest = true },
            // 2026-11-08 用户「助理中枢点进来应该是直接到 AI 会话界面」：
            // 抽屉入口直达中枢主会话；功能首页（审核/会话/更多）走会话顶栏 ⋮ 里的
            // 「中枢功能」，不再要求"先发一条消息才进对话"。
            onPending = {
                enteredFromDrawer = true
                closeDrawer()
                scope.launch {
                    val aid = runCatching {
                        Graph.assistants.byKey(io.github.m0use001.planability.data.AssistantRepo.KEY_HUB)?.id ?: 0L
                    }.getOrDefault(0L)
                    val pid = runCatching { io.github.m0use001.planability.data.HubProject.ensureId() }.getOrDefault(0L)
                    val sid = runCatching {
                        io.github.m0use001.planability.data.HubProject.ensureMainSession(aid, pid)
                    }.getOrNull()
                    chatSessionId = sid
                    chatAssistantId = aid
                    chatProjectId = pid
                    chatPrefill = ""
                    chatAutoSend = false
                    showChat = true
                }
            },
            onModelHub = { enteredFromDrawer = true
                closeDrawer(); showModel = true },
            onUsageStats = { enteredFromDrawer = true
                closeDrawer(); showUsage = true },
            onSearch = {
                enteredFromDrawer = true
                closeDrawer()
                io.github.m0use001.planability.core.ShellChrome.searchOpen = true
            },
            onProjectDetail = { pid ->
                enteredFromDrawer = true
                closeDrawer()
                showProjectDetail = pid
            },
            // v12：重排后的新入口
            // v53.3：抽屉底部瘦身后「记忆库/文件库/归档项目」三行收成「资料」一行；
            // 原三个直达回调保留（抽屉签名不动、别处深链仍可直达单页）。
            onDataHub = { enteredFromDrawer = true
                closeDrawer(); showDataHub = true },
            onFileLibrary = { enteredFromDrawer = true
                closeDrawer(); showFileLibrary = true },
            // v50.5：抽屉「工具与扩展」= ToolsHub（能力与插件 / MCP / 命令 三 tab）。
            // 原来这里是「AI能力开关」单页，MCP 与命令各占一行；合并后由 Hub 内部切换。
            onAiCapability = { enteredFromDrawer = true
                closeDrawer(); showToolsHub = true },
            onPhoneUseRegion = { enteredFromDrawer = true
                closeDrawer(); showPhoneUseRegion = true },
            // 抽屉已不再单独露 MCP / 命令（收进 ToolsHub），但回调保留：
            // 别处（自动化文件夹等）仍可能按需直达，且删掉会改动 Drawer 的签名。
            onMcp = {
                enteredFromDrawer = true
                closeDrawer()
                automationTab = 1
                showAutomation = true
            },
            onCommands = { enteredFromDrawer = true
                closeDrawer(); showCommands = true },
            onImport = {
                enteredFromDrawer = true
                closeDrawer()
                automationTab = 2
                showAutomation = true
            },
            onVoice = { enteredFromDrawer = true
                closeDrawer(); showVoiceInput = true },
            // v18.1：自动化文件夹
            automations = drawerAutomations,
            autoAnchors = autoAnchors,
            onOpenAutomations = {
                enteredFromDrawer = true
                closeDrawer()
                automationTab = 0
                showAutomation = true
            },
            bottomInset = bottomInsetArg,
            dismissable = dismissableArg
        )
    }
    androidx.compose.material3.ModalNavigationDrawer(
        drawerState = drawerState,
        // 内置手势必须恒关：其拖拽在整段手势期间持有 anchoredDrag 互斥锁，程序化 close()
        // 会在锁上饿死；开启走 edgeSwipeDrawerOpen、收回走 drawerSwipeToClose
        gesturesEnabled = false,
        drawerContent = {
            // B2-A（2026-10-24 用户拍板）：**回归库默认**——v19.44 曾刻意铺满全宽 + 方角
            // （当年为规避真机 363.4dp 屏宽下 360dp 抽屉右侧 3.4dp 缝 + 圆角缺口），
            // 但 M3E 已弃用全宽方角形态：规范 token 是 360dp 宽 + 右缘 16dp 圆角 + 可见遮罩，
            // 全宽方角等于把「导航抽屉」画成了「第二块全屏内容」。缝在圆角 + 遮罩形态下
            // 就是浮层的正常观感，不再规避。
            // 2026-11-01 用户「抽屉底部被全面屏横杠截断」：ModalDrawerSheet 的 windowInsets
            // 会把抽屉高度压到导航条上沿。这里改成不消费任何 inset（WindowInsets(0)），
            // 抽屉背景铺到屏幕最底；顶部状态栏用 statusBarsPadding 让位，
            // 底部安全区由 WorkbenchDrawerContent 的 contentPadding（已含 navInset）负责。
            androidx.compose.material3.ModalDrawerSheet(
                windowInsets = WindowInsets(0)
            ) {
                Box(Modifier.drawerSwipeToClose(drawerState).statusBarsPadding()) {
                drawerBody(drawerBottomInset, true)
                }
            }
        }
    ) {
        // 区域限定抽屉手势：左缘触发、剔除底部导航条一整行；仅当没有子页面覆盖时启用
        // 抽屉右滑手势的生效范围。
        //
        // v10 曾用 `!showChat` 把对话页排除在外 —— 于是对话页只剩顶部汉堡按钮可以
        // 打开抽屉，右滑不响应，看起来像「对话界面不支持右滑菜单」。这里放开 showChat。
        //
        // 放开是安全的（edgeSwipeDrawerOpen 自己已经把该让的都让了）：
        //   · 只在 `dx > slop && dx > abs(dy)` 时才判定为右滑，纵向滚动 dx 始终落后 dy，
        //     永远不会被抢；列表上下滑不受影响
        //   · 按在横向滚动条上（chips / 模型选择条 / 月历周带）时让位，不开抽屉
        //   · 底部导航条一整行不作为触发区，不会误触
        // 二级页（会话管理 / 记忆库 / 模型设置 / 采集等）仍保持禁用 —— 它们有自己的返回，
        // 右滑抢走会让人退不出去；对话页没有这个顾虑。
        //
        // `!showSessions || showChat`：打开聊天后父页（会话列表）**不再被关掉**（见 onOpen 的注释），
        // 但列表本身是二级页、要禁用右滑，所以补一个"聊天开着就放行"的条件——
        // 否则从列表进聊天后右滑菜单会失效，等于退回去才恢复的怪体验。
        // 2026-11-19 平板批：宽屏下**禁用边缘右滑抽屉**——平板左栏常驻的就是那份工作台树，
        // 右滑再弹抽屉 = 同一份内容画第二遍（用户报的重复）。
        val drawerGestureEnabled = !wide && (!showSessions || showChat) && !showMemory && !showModel && !showAiCap && !showCapture && !showGroupDigest && !showPending && !showAssistantHub && showProjectDetail == null
        Box(
            Modifier.fillMaxSize().edgeSwipeDrawerOpen(drawerState, drawerGestureEnabled),
            contentAlignment = Alignment.TopCenter
        ) {
            // ── v63 平板全量批（2026-11-19 用户「3200×2000 全量调查调整优化，按 M3E / iPadOS 规范」）──
            // 信息页原来是「单列 adaptiveColumn 840dp 居中」：1600dp 宽的窗口里左右各空
            // 340dp，而 v59 补的「今日安排 / 最近会话」两块还继续往**同一列**下面叠——
            // 横着更空、竖着更长，两头不讨好（1600×1000 实测截图：内容止于 68% 高）。
            //
            // 按 iPadOS HIG「侧栏 + 内容」的分栏口径改成两栏：
            //   左 = 问候 + 输入卡 + 同步中心（限 [io.github.m0use001.planability.ui.common.WideWorkbenchMaxWidth]
            //        居中——输入卡不被拉成整行，行长仍读得动）
            //   右 = 常驻侧栏，宽 [io.github.m0use001.planability.ui.common.WideSecondaryWidth]——
            //        与首页 / 计划页同一个「侧栏宽度」心智，全 app 只有一种右栏
            // 手机路径是下面 else 分支里原来那一列，内容一行都不动。
            // 宽窄判断只有一处（文件头的 val wide），这里只是别名，别再单独读一遍 Configuration。
            val wideInfo = wide
            val leftScroll = rememberScrollState()
            val rightScroll = rememberScrollState()
            // 主列内容抽成 lambda：宽窄两分支**共用同一份内容**，不复制粘贴第二套
            //（复制出来的那份下次改一定会漏，本仓已有的教训是五工具漏路由）。
            val leftContent: @Composable () -> Unit = {
                // v19.1：汉堡钮换 PageHeader 统一结构——与其他页顶部按钮同基准
                //（56dp 头行、图标垂直居中、glyph 距边 32dp），旧裸 Row 高度/边距均不一致
                //
                // 2026-11-19 平板批：宽屏下**不画汉堡**——左栏常驻的就是那份工作台树，
                // 再摆一个"打开抽屉"的钮 = 给同一个东西两个入口（用户报的重复）。
                if (!wideInfo) {
                io.github.m0use001.planability.ui.common.PageHeader(
                    title = "",
                    // 父 Column 已有 horizontal 20dp，此处 0 防叠加（总 20 → glyph 距边 32，与其他页一致）
                    horizontalPadding = 0.dp,
                    leading = {
                        IconButton(onClick = {
                            // 打开右侧抽屉 = 中性轻触（HIG Tap），与底栏复点/其他"打开面板"同档；
                            // 原来这里没有振动反馈（用户反馈「三横杠按钮没有震动」）
                            hapticsHome.perform(io.github.m0use001.planability.core.HapticEngine.Kind.Tap)
                            scope.launch { drawerState.open() }
                        }) {
                            Icon(Icons.Rounded.Menu, "菜单")
                        }
                    }
                )
                }
                val now = AppTime.now()
                // v12 曾在这里放 88dp 空白"整体下移方便单手操作"，结果「≡」行与大标题之间
                // 形成约 133dp 死区、含副标题的头部约 199dp，超过 M3E large flexible app bar
                // 带副标题的 152dp 上限（审查报告差距 4）。
                // v45 收到 24dp：头部仍保留呼吸感（≈135dp），但不再是"空半屏"。
                // 只回收空白，不动信息架构（欢迎语/副标题/下方内容位置关系不变）。
                Spacer(Modifier.height(24.dp))
                Text(
                    greetingText(now.hour),
                    style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold
                )
                Text(
                    "${now.monthValue}月${now.dayOfMonth}日 · 工作台已就绪",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp)
                )
                Spacer(Modifier.height(16.dp))

                // 工作台输入卡 v3（v12：两层——上层图片/附件+预览，下层模型/思考/权限/分类）
                Card(
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(14.dp)) {
                        // / 指令二级菜单（输入以 / 开头时显示，图1 式分区）
                        if (homeInput.trimStart().startsWith("/")) {
                            HomeSlashMenu(
                                onPickFile = { homePickFile.launch(arrayOf("*/*")) },
                                onPickImage = {
                                    homePickImage.launch(androidx.activity.result.PickVisualMediaRequest(
                                        androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageOnly
                                    ))
                                },
                                onModel = { showHomeModelPicker = true },
                                onPerm = { showHomePermMenu = true },
                                onMemory = { homeMemoryQuick = "/memory" },
                                onSkill = { homeMemoryQuick = "/skill" },
                                onClearInput = { homeInput = "" }
                            )
                        }
                        // 选择项目（2026-10-24 用户拍板：从工具行移到输入框上方左对齐——
                        // 工具行三枚图标加宽后空间吃紧，它是被挤掉的第一个）。
                        // 2026-10-25 修"歪"：图标 14→18dp 与下行权限 chip 同档、与输入框
                        // 拉开 8dp（原来零间距贴着框边，看着像斜靠在上面）
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .padding(start = 4.dp, bottom = 8.dp)
                                .clip(RoundedCornerShape(percent = 50))
                                .clickable {
                                    hapticsHome.perform(HapticEngine.Kind.Select)
                                    showProjectPicker = true
                                }
                                .padding(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            Icon(
                                io.github.m0use001.planability.data.WorkbenchIcons.project(chatProjectIconKey),
                                null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                if (chatProjectId > 0L) chatProjectName else "选择项目",
                                style = MaterialTheme.typography.labelMedium,
                                color = if (chatProjectId > 0L) MaterialTheme.colorScheme.onSurface
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.widthIn(max = 200.dp)
                            )
                        }
                        OutlinedTextField(
                            value = homeInput,
                            onValueChange = {
                                homeInput = it
                                if (it.endsWith("@")) showHomeLinkPicker = true
                            },
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = { Text("向工作台提问…（/ 快捷菜单 · @ 勾连）") },
                            minLines = 3, maxLines = 6,
                            shape = RoundedCornerShape(14.dp)
                        )
                        Spacer(Modifier.height(8.dp))
                        // 第一层：图片/附件按钮 + 已选预览（勾连与附件 chips 可滚动）
                        // 统一规格（2026-10-24 用户反馈"做整齐"）：同高药丸触控区、条目间 8dp；
                        // 2026-10-25 用户反馈"图标太小"：圆钮 30→36dp，字形 18→20dp（M3 图标标准档）
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(
                                Icons.Rounded.Image, "添加图片",
                                Modifier
                                    .size(36.dp)
                                    .clip(RoundedCornerShape(percent = 50))
                                    .clickable {
                                        hapticsHome.perform(HapticEngine.Kind.Select)
                                        homePickImage.launch(androidx.activity.result.PickVisualMediaRequest(
                                            androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageOnly
                                        ))
                                    }
                                    .padding(8.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.width(8.dp))
                            Icon(
                                Icons.Rounded.AttachFile, "添加附件",
                                Modifier
                                    .size(36.dp)
                                    .clip(RoundedCornerShape(percent = 50))
                                    .clickable {
                                        hapticsHome.perform(HapticEngine.Kind.Select)
                                        homePickFile.launch(arrayOf("*/*"))
                                    }
                                    // 三枚统一 8dp 内边距 = 同一 20dp 图标框：字形高矮由 Material 图标
                                    // 自身的光学尺寸决定（纸夹天然细长），不再给悬浮模式单独加减 padding
                                    .padding(8.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            // 首页也给「转换为悬浮模式」（用户反馈首页没有这个入口）。
                            // 这里**不传** EXTRA_SESSION_ID：首页此刻还没有会话，
                            // 悬浮窗侧拿到 sessionId=0 会先自己建一个会话再渲染（见 FloatChatRoot）。
                            Spacer(Modifier.width(8.dp))
                            Icon(
                                Icons.Rounded.PictureInPictureAlt, "转换为悬浮模式",
                                Modifier
                                    .size(36.dp)
                                    .clip(RoundedCornerShape(percent = 50))
                                    .clickable {
                                        hapticsHome.perform(HapticEngine.Kind.Select)
                                        val ctx = io.github.m0use001.planability.data.Graph.appContext
                                        ctx.startActivity(
                                            android.content.Intent(ctx, FloatChatActivity::class.java)
                                                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                        )
                                    }
                                    // 三枚统一 8dp 内边距 = 同一 20dp 图标框（与图片/附件完全一致的图标尺寸；
                                    // 之前这里单独留 9dp 让悬浮模式看起来明显小一圈，用户点名"大小不一样"）
                                    .padding(8.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            // v53.3：语音输入从抽屉迁来（抽屉底部 13→6 行，用户拍板）。
                            // 原抽屉行的目的地就是这张卡的输入框（听写结果直接进 homeInput）——
                            // 与其隔着抽屉绕一层，麦克风放在输入框旁边才是它的家（笔记页同款入口早已有）。
                            Spacer(Modifier.width(8.dp))
                            Icon(
                                Icons.Rounded.Mic, "语音输入",
                                Modifier
                                    .size(36.dp)
                                    .clip(RoundedCornerShape(percent = 50))
                                    .clickable {
                                        hapticsHome.perform(HapticEngine.Kind.Select)
                                        showVoiceInput = true
                                    }
                                    .padding(8.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            // v19.10：权限档 + 分类（项目归属）移到与图片/附件同一行
                            Spacer(Modifier.width(8.dp))
                            Box {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .height(36.dp)
                                        .clip(RoundedCornerShape(percent = 50))
                                        .clickable { showHomePermMenu = true }
                                        .padding(horizontal = 8.dp)
                                ) {
                                    Icon(
                                        Icons.Rounded.Build, null, Modifier.size(20.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Spacer(Modifier.width(3.dp))
                                    Text(
                                        // 2026-10-24 去掉「权限·」前缀：扳手图标已表达语义，
                                        // 行宽紧张（三枚 30dp 图标+两 chip）时给「选择项目」让位
                                        PermModes.ALL.firstOrNull { it.key == homePermKey }?.label ?: "确认",
                                        style = MaterialTheme.typography.labelSmall,
                                        // 与右侧「选择项目」用同一档颜色：两者是同一行的同权动作提示，
                                        // 一个 74% 亮度、一个 91% 亮度并排看着就是"没对齐"（用户反馈）
                                        color = MaterialTheme.colorScheme.onSurface,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                PermModeMenu(
                                    expanded = showHomePermMenu,
                                    currentKey = homePermKey,
                                    onDismiss = { showHomePermMenu = false },
                                    onPick = { key ->
                                        showHomePermMenu = false
                                        hapticsHome.perform(HapticEngine.Kind.Select)
                                        homePermKey = key
                                        scope.launch {
                                            Graph.settings.kvPut("perm.home", key)
                                        }
                                    }
                                )
                            }
                            Spacer(Modifier.width(8.dp))
                            // 2026-10-24：「选择项目」已上移到输入框上方（左对齐）——
                            // 工具行加宽图标后空间吃紧，它是被挤掉的那个
                            // fill=false：勾连区只吃剩余宽度——空着时不挤占权限 chip
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.weight(1f, fill = false).hScrollZone().horizontalScroll(rememberScrollState())
                            ) {
                                homeLinked.forEach { ref ->
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(MaterialTheme.colorScheme.secondaryContainer)
                                            .clickable { homeLinked = homeLinked.filterNot { it === ref } }
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
                                homeFiles.forEach { pf ->
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                                            .clickable { homeFiles = homeFiles.filterNot { it === pf } }
                                            .padding(horizontal = 6.dp, vertical = 3.dp)
                                    ) {
                                        Icon(Icons.Rounded.AttachFile, null, Modifier.size(11.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                        Spacer(Modifier.width(3.dp))
                                        Text(
                                            pf.name, style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1, overflow = TextOverflow.Ellipsis
                                        )
                                        Spacer(Modifier.width(3.dp))
                                        Icon(Icons.Rounded.Close, "移除", Modifier.size(11.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    Spacer(Modifier.width(6.dp))
                                }
                            }
                        }
                        Spacer(Modifier.height(8.dp))
// v19.12：第二层压成一行——左边一条设置摘要（点开设置面板），右边发送
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth().padding(end = 28.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(10.dp))
                                    .clickable { showHomeSetup = true }
                                    .padding(horizontal = 6.dp, vertical = 6.dp)
                            ) {
                                Icon(
                                    Icons.Rounded.Tune, null, Modifier.size(20.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    listOfNotNull(
                                        homeAssistantName.ifBlank { "通用助手" },
                                        homeModel.ifBlank { null },
                                        "思考·" + ThinkingLevels.label(homeThinking)
                                    ).joinToString(" · "),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                                Icon(
                                    Icons.Rounded.ExpandMore, "发送设置",
                                    // 跟左边那颗 Tune 同一个主题色：原来写死 Color.White，
                                    // 浅色模式下等于隐形，换主题也不跟着变
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Spacer(Modifier.width(8.dp))
                            androidx.compose.material3.Button(
                                onClick = {
                                    // v19.62 严格项目制：没选项目就拦在这一步（首页），
                                    // 不把用户带进会话页再弹选择器——那时字已经被搬走了。
                                    // 此时 homeInput 原样留着，选完项目再点发送即可。
                                    if (chatProjectId <= 0L) { showProjectPicker = true; return@Button }
                                    chatSessionId = null
                                    chatAssistantId = homeAssistantId
                                    chatPrefill = homeInput
                                    chatFiles = homeFiles
                                    chatLinked = homeLinked
                                    chatDraftModel = homeModel
                                    chatDraftThinking = homeThinking
                                    chatDraftPermJson = PermModes.permJsonFor(homePermKey)
                                    homeInput = ""
                                    homeFiles = emptyList()
                                    homeLinked = emptyList()
                                    // v50.3：草稿已随消息搬进会话，这里必须同步清 KV，
                                    // 否则下一轮「恢复只在空时注入」会把旧草稿注回来
                                    io.github.m0use001.planability.app.UiFreeze.save(KEY_HOME_DRAFT, "")
                                    io.github.m0use001.planability.app.UiFreeze.save(KEY_HOME_ATTACH, "")
                                    chatAutoSend = true
                                    showChat = true
                                },
                                enabled = homeInput.isNotBlank() || homeFiles.isNotEmpty()
                            ) {
                                Text("发送")
                            }
                        }
                    }
                }
        // v19.10：自动化列表移出输入卡——灰色圆角背景只包输入区，不再罩住下方内容
        Column(Modifier.fillMaxWidth()) {
                // 与输入卡拉开距离（2026-10-24 用户反馈胶囊区"往下移"）
                Spacer(Modifier.height(16.dp))
                // 2026-11-01 顶部预算（meta-ai 方案 §六）：「自动化」入口胶囊与右滑抽屉重复
                // （抽屉有「自动化与扩展」行 + 自动化文件夹，时间轴的自动化块也能进），
                // 删页面内这份，只留抽屉。计划文档说「同步中心也与抽屉重复」与实测不符——
                // 抽屉里没有同步入口，且它是 2026-10-24 用户拍板从文件页迁来的信息侧唯一入口，
                // 故「同步中心」连同设备状态胶囊保留。
                InfoHomeCapsule("同步中心", icon = Icons.Rounded.Sync) {
                    hapticsHome.perform(HapticEngine.Kind.Select)
                    showSync = true
                }
                syncDevices.forEach { d ->
                    InfoHomeCapsule(
                        d.name.ifBlank { "未命名设备" },
                        dotColor = if (d.connected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.outline,
                        sub = if (d.connected) "已连接" else "未连接",
                        modifier = Modifier.padding(start = 14.dp, top = 6.dp)
                    ) {
                        hapticsHome.perform(HapticEngine.Kind.Select)
                        showSync = true
                    }
                }
                // v19.2：自动化与扩展入口移至右滑抽屉（用户要求，信息页正文减负）
                // v59 宽屏的「今日安排 + 最近会话」两块**已挪出本列**（v63）：
                // 它们现在长在下面的 rightContent 里、摆在常驻右栏，不再继续压这条单列。
                // 窄屏仍然不渲染它们（内容在计划页 / 抽屉里已有）。
                Spacer(Modifier.height(io.github.m0use001.planability.app.navAvoidPadding().calculateBottomPadding()))
            }
            // ↑ Column(Modifier.fillMaxWidth()) 收口；↓ 这一条才是 leftContent lambda 的收口
            //（漏了它 rightContent / 分栏 if 就落进 lambda 里，调 leftContent() 会自递归）
            }
            // 宽屏右栏内容（只在 wideInfo 分支被调用；窄屏连组合都不进）。
            val rightContent: @Composable () -> Unit = {
                Spacer(Modifier.height(16.dp))
                InfoTodayPlanPanel(
                    onOpenTodo = { id ->
                        io.github.m0use001.planability.app.Router.openTodo("plan", id)
                    },
                    onOpenSchedule = { id ->
                        io.github.m0use001.planability.app.Router.openSchedule("plan", id)
                    }
                )
                Spacer(Modifier.height(12.dp))
                InfoRecentSessionsPanel(
                    onOpenSession = { id ->
                        chatSessionId = id
                        showChat = true
                    }
                )
                Spacer(Modifier.height(16.dp))
            }
            if (wideInfo) {
                // 2026-11-19 平板批：宽屏信息页 = **常驻工作台树侧栏 + 内容区**。
                // 原来这里是「左主列 + 一根竖线 + 侧栏两块面板」，而会话页另有一个
                // fullscreen overlay 整块盖住窗口（连 NavRail 一起盖）——两套网格两种材质
                // = 用户报的「内容块割裂」；而且会话树被画了两遍（抽屉一份 + overlay 左栏一份）
                // = 用户报的「重复」。
                //
                // 2026-11-20 v65 两板推广：侧栏从「通栏贴边」改成与计划页/首页/专注页同一套
                // **定宽圆角板**（SplitBoards，24dp 圆角 + 12dp 白缝）。用户拍板「所有界面都要
                // 改成计划界面这种 2 个圆角矩形框」——原先按 HIG「侧栏通栏、不切圆角」刻意
                // 做的口径**就此作废**：全 app 只保留一种材质。
                //
                // 左板 = 内容区（三态由 ChatWideLayout.paneOf 裁决：首页 / 会话列表 / 对话），
                // 右栏 = 唯一一份工作台树（WorkbenchDrawerContent，定宽板在右——与其他页对齐）。
                val pane = ChatWideLayout.paneOf(
                    isWide = true,
                    showChat = showChat,
                    showSessions = showSessions
                )
                io.github.m0use001.planability.ui.common.SplitBoards(
                    twoPane = true,
                    // v69「框不动」：板底由壳层固定框绘制（phone-b 代改一行并已在 ◆/phone-b.md 报备）
                    transparent = true,
                    // 2026-11-21 用户拍板：**内容板在左、树板在右**，与首页/计划/专注/日历
                    // （定宽详情板都在右）同一套左右关系；fixedFirst 默认 false 即右定宽。
                    fixedFirst = false,
                    detailWidth = ChatWideLayout.SidebarWidth,
                    // 2026-11-21 用户拍板「上下也要对齐」：顶部状态栏 / 底部避让改回由壳
                    // （SplitBoards 默认 true）统一垫——与首页/计划/专注同一套上下几何；
                    // 内容里原来自己垫的那几层（树板 statusBarsPadding、底 inset）随之撤掉。
                    // 定宽板（右）= 常驻工作台树。同一份内容只画一次：
                    // 窄屏它是抽屉，宽屏它是这块板。
                    secondary = {
                        Box(Modifier.fillMaxSize()) {
                            // 常驻侧栏：没有"收起"，所以 dismissable = false；
                            // 底部避让壳已垫，内层传 0 防双倍。
                            drawerBody(0.dp, false)
                        }
                    },
                    // 弹性板（右）= 内容区
                    primary = {
                        // v70h（2026-11-21 用户「会话界面由于自身设置，动画改成点击副框，
                        // 只是主框内容左右移动变化；同时也要补齐框」）：主框三态原来硬切。
                        // 信息页的侧栏（副框）是**常驻工作台树**、没有层级语义——不套计划页
                        // 那套 BoardDrill 整条抽（会把侧栏也拖走），改为**只推主框内容**：
                        // 进一级（首页→会话列表→对话）新内容从右滑入、旧内容向左让位，
                        // 返回反向；已在对话中点另一条会话（pane 不变、sessionId 变）也按
                        // 「从右滑入」轻推一次（targetState 带上 sessionId/nonce）。
                        // 全程裁在主框圆角瓦片内（SplitBoards 各板自带 clip）——缝与侧栏
                        // 纹丝不动，框始终完整。
                        androidx.compose.animation.AnimatedContent(
                            targetState = Triple(
                                pane,
                                if (pane == ChatPane.Conversation) chatSessionId else null,
                                if (pane == ChatPane.Conversation) chatNonce else 0
                            ),
                            transitionSpec = {
                                val forward = targetState.first.ordinal >= initialState.first.ordinal
                                if (forward) {
                                    (slideInHorizontally(Motion.standardSpatial()) { it } + fadeIn(Motion.quick())) togetherWith
                                        (slideOutHorizontally(Motion.standardSpatial()) { -it / 3 } + fadeOut(Motion.quick()))
                                } else {
                                    (slideInHorizontally(Motion.standardSpatial()) { -it / 3 } + fadeIn(Motion.quick())) togetherWith
                                        (slideOutHorizontally(Motion.standardSpatial()) { it } + fadeOut(Motion.quick()))
                                }
                            },
                            label = "chat-pane-push",
                            modifier = Modifier.fillMaxSize()
                        ) { paneKey ->
                        when (paneKey.first) {
                            ChatPane.Conversation -> {
                                key(chatNonce) {
                                    ChatScreen(
                                        sessionId = chatSessionId,
                                        assistantId = chatAssistantId,
                                        initialInput = chatPrefill,
                                        projectId = chatProjectId,
                                        initialFolderId = chatFolderId,
                                        autoSend = chatAutoSend,
                                        initialFiles = chatFiles,
                                        initialLinked = chatLinked,
                                        draftModel = chatDraftModel,
                                        draftThinking = chatDraftThinking,
                                        draftPermJson = chatDraftPermJson,
                                        // 宽屏双栏：右滑手势让位给左栏（宽屏抽屉已整体禁用）
                                        onSwipeOpenDrawer = null,
                                        embedded = true,
                                        onDone = { closeChatPane() },
                                        onOpenHub = {
                                            hubReturnSessionId = chatSessionId
                                            showChat = false
                                            showAssistantHub = true
                                        }
                                    )
                                }
                            }
                            ChatPane.Sessions -> {
                                // 状态栏 inset 壳（SplitBoards）已垫，这里不再自己垫
                                Column(Modifier.fillMaxSize()) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.fillMaxWidth().padding(start = 8.dp, end = 20.dp, top = 8.dp)
                                    ) {
                                        IconButton(onClick = { showSessions = false }) {
                                            Icon(Icons.AutoMirrored.Rounded.ArrowBack, "返回")
                                        }
                                        Text(
                                            "AI 会话",
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                    }
                                    ChatSessionsBody(
                                        onOpen = { id, aid ->
                                            chatSessionId = if (id == 0L) null else id
                                            chatAssistantId = aid
                                            showSessions = false
                                            showChat = true
                                        },
                                        onOpenProject = { showProjectDetail = it }
                                    )
                                }
                            }
                            ChatPane.Home -> {
                                // 首页内容收进一条限宽列：左栏被工作台树占了，
                                // 问候语 / 输入卡 / 今日安排 / 最近会话全在这条列里（不能再摊成两栏）。
                                Column(
                                    Modifier
                                        .fillMaxHeight()
                                        .adaptiveColumn(io.github.m0use001.planability.ui.common.WideWorkbenchMaxWidth)
                                        .verticalScroll(leftScroll)
                                        // 状态栏 inset 壳（SplitBoards）已垫，这里不再自己垫
                                        .padding(horizontal = 20.dp)
                                ) {
                                    leftContent()
                                    rightContent()
                                }
                            }
                            }
                        }
                    }
                )
            } else {
                Column(
                    Modifier
                        .fillMaxHeight()
                        .adaptiveColumn() // v57 平板：长页收进 840dp，手机天然 no-op
                        .statusBarsPadding()
                        .verticalScroll(leftScroll)
                        .padding(horizontal = 20.dp)
                ) {
                    leftContent()
                }
            }
        }

    // 2026-11-26 开源根治：采集三页经 DeviceFeatures.screens 槽位移入可选源集；
    // 公开版槽位为 null → 这两个入口（连同抽屉里的对应项）都不出现。
    val deviceScreens = io.github.m0use001.planability.core.DeviceFeatures.screens
    if (showGroupDigest) deviceScreens.groupDigest?.invoke(
            backToDrawer { showGroupDigest = false },
            { showGroupDigest = false; showCapture = true },
            // 2026-11-03 项目化 P4：群头「会话」→ 打开该群的提纯会话（挂「信息提取」项目）
            { sourceGroup ->
                scope.launch {
                    val sid = runCatching {
                        io.github.m0use001.planability.data.Graph.settings.kvGet("extract.session:$sourceGroup")
                            ?.toLongOrNull() ?: 0L
                    }.getOrDefault(0L)
                    if (sid > 0L && io.github.m0use001.planability.data.Graph.chat.session(sid) != null) {
                        showGroupDigest = false
                        chatSessionId = sid
                        chatAssistantId = homeAssistantId
                        showChat = true
                    }
                }
            }
        )
    if (showCapture) deviceScreens.infoCapture?.invoke({ showCapture = false })
    // 2026-11-08：原「待处理」页收敛为「审核历史」——入口搬进「待处理审核」顶栏，
    // 中枢主界面不再有第二个待处理入口
    if (showPending) ReviewHistoryScreen(onDone = backToDrawer { showPending = false })
    // v53 助理中枢：抽屉「待处理」的升级版。对话从这里发 → 复用下面的 ChatScreen 挂载
    //（chatSessionId 等草稿变量已就位），对话返回时中枢仍在场（showAssistantHub 不动），列表自动刷新。
    // v54.1 中枢=导航枢纽：审核/会话各一个专属二级页，主界面只做入口
    var showHubReview by remember { mutableStateOf(false) }
    var showHubSessions by remember { mutableStateOf(false) }
    if (showAssistantHub) {
        AssistantHubScreen(
            onDone = {
                // 从会话进来的 → 回到那个会话（2026-11-08 定案「返回仍在当前会话」的实际落地）；
                // 其它入口（现仅此处）退回抽屉首页
                val ret = hubReturnSessionId
                hubReturnSessionId = null
                if (ret != null) {
                    showAssistantHub = false
                    chatSessionId = ret
                    showChat = true
                } else {
                    backToDrawer { showAssistantHub = false }.invoke()
                }
            },
            onOpenReview = { showHubReview = true },
            onOpenSessions = { showHubSessions = true },
            // v54 中枢=特殊项目：项目数据入口（普通项目同款全局页）
            onOpenMemory = { showMemory = true },
            onOpenFiles = { showFileLibrary = true }
        )
    }
    if (showHubReview) {
        HubReviewScreen(
            onDone = { showHubReview = false },
            onOpenHistory = { showPending = true },
            // 2026-11-12 用户：审核页输入框直发「点中枢功能时所在的会话」（hubReturnSessionId
            // 在 onOpenHub 里记下；为 null 时退回中枢主会话）
            preferredSessionId = hubReturnSessionId,
            onChat = { sid, aid, pid, text, autoSend ->
                chatSessionId = sid
                chatAssistantId = aid
                chatProjectId = pid
                chatPrefill = text
                chatAutoSend = autoSend
                showChat = true
            }
        )
    }
    if (showHubSessions) {
        HubSessionsScreen(
            onDone = { showHubSessions = false },
            onChat = { sid, aid, pid, text, autoSend ->
                chatSessionId = sid
                chatAssistantId = aid
                chatProjectId = pid
                chatPrefill = text
                chatAutoSend = autoSend
                showChat = true
            }
        )
    }
    // v50.5：工具与扩展总汇（能力与插件 / MCP / 命令 三 tab）。
    // 原来这里是「AI能力开关」单页 + 另外两行 MCP/命令，现由这一个入口进。
    if (showToolsHub) ToolsHub(onDone = backToDrawer { showToolsHub = false })
    // v53.3：资料总汇（记忆库 / 文件库 / 归档项目 三 tab）
    if (showDataHub) DataHub(onDone = backToDrawer { showDataHub = false })
    // 模型设置/智能体设置/模型显示 三 tab 总汇
    if (showModel) ModelSettingsHub(onDone = backToDrawer { showModel = false })
    if (showMemory) MemoryScreen(onDone = backToDrawer { showMemory = false })
    if (showAiHealth) AiHealthScreen(onDone = backToDrawer { showAiHealth = false })
    if (showChartDefaults) io.github.m0use001.planability.ui.notes.ChartDefaultsDialog(
        onDismiss = { showChartDefaults = false },
        // 只写库：chartDefaults 派生自 settings snapshot，写完自动刷新
        onDefaultsChanged = {}
    )
    if (showFileLibrary) FileLibraryScreen(onDone = backToDrawer { showFileLibrary = false })
    if (showSync) io.github.m0use001.planability.ui.files.SyncthingScreen(onDone = { showSync = false })
    if (showCommands) MemoryScreen(onDone = backToDrawer { showCommands = false }, initialType = "skill")
    if (showPhoneUseRegion) deviceScreens.phoneUseRegion?.invoke(backToEntry {
        showPhoneUseRegion = false
        // 跨 tab 打开（我的 → 手机操控区域）：返回先回来源页，不落在信息页根。
        // 从信息页右滑菜单进来时没记来源，这里直接返回、原地不动。
        io.github.m0use001.planability.app.ShellNav.returnToSourceTab("info")
    })
// v19.12：发送设置面板——原来第二层平铺 3 个 chip，现在收成一行摘要，点开在这里改
    if (showHomeSetup) {
        androidx.compose.material3.ModalBottomSheet(contentWindowInsets = { WindowInsets.navigationBars }, 
            dragHandle = { io.github.m0use001.planability.ui.common.SheetDragHandleOnly() },
            onDismissRequest = { showHomeSetup = false },
            sheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ) {
            Column(Modifier.padding(start = 20.dp, end = 20.dp, bottom = 28.dp)) {
                Text(
                    "发送设置",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(14.dp))
                // 智能体
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .clickable {
                            showHomeSetup = false
                            showHomeAssistantPicker = true
                        }
                        .padding(vertical = 12.dp)
                ) {
                    Icon(Icons.Rounded.SmartToy, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(12.dp))
                    Text("智能体", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    Text(
                        homeAssistantName.ifBlank { "通用助手" },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                SyncDivider()
                // 模型
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .clickable {
                            showHomeSetup = false
                            showHomeModelPicker = true
                        }
                        .padding(vertical = 12.dp)
                ) {
                    Icon(Icons.Rounded.Bolt, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(12.dp))
                    Text("模型", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    Text(
                        homeModel.ifBlank { "默认模型" },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                SyncDivider()
                // 思考档：五选一（v54：关/自适应/low/high/max，DeepSeek 力度档）
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
                    ThinkingLevels.ALL.forEach { k ->
                        val label = ThinkingLevels.label(k)
                        val on = homeThinking == k
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
                                    hapticsHome.perform(HapticEngine.Kind.Select)
                                    homeThinking = k
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
            }
        }
    }

    if (showProjectPicker) {
        ProjectPickerSheet(
            selectedId = chatProjectId,
            showDetailEntry = true,
            onDismiss = { showProjectPicker = false },
            onPick = { chatProjectId = it },
            onOpenDetail = { showProjectDetail = it }
        )
    }
    // AI 会话列表（父页）。组合顺序有意排在「项目详情」**之前**：
    // 两层同时在时，后组合的窗口盖在上层、后注册的返回回调优先 —— 详情必须在列表之上，
    // 返回才会先关详情、把列表留在下面（顺序反了会先关掉还盖在下面的父页，等于跳级）。
    //
    // v57 平板适配：宽屏（≥600dp）时「会话列表 + 对话」合并为**一个** overlay 的左右两栏——
    // 原来两层叠 Dialog 在平板上会互相遮死整屏。点会话只换右栏内容；系统返回关整层，
    // 右栏返回钮只关对话、列表留在场。手机路径（下方 !wide 的两个 if）保持原样。
    // 2026-11-19 平板批：宽屏的「会话列表 + 对话」**不再是 overlay**——
    // 它现在长在信息页内部（见上方 wideInfo 分支：常驻工作台树侧栏 + 内容区），
    // NavRail 因此常驻可切 tab，整窗不再被一块 Dialog 盖住（用户报的「内容块割裂」）。
    // 手机路径（下方 !wide 的两个 if）保持原样。
    //
    // 宽屏系统返回：先收右栏对话回到首页（侧栏树留在场——用户还在挑会话）。
    androidx.activity.compose.BackHandler(enabled = wide && showChat) {
        closeChatAll()
    }

    // （宽屏的「会话列表 / 对话」已并入上方 wideInfo 分支：常驻工作台树侧栏 + 内容区。
    //   这里只剩手机路径：会话列表页与对话页各自仍是全屏 Dialog。）
    if (!wide && showSessions) {
        ChatSessionsScreen(
            onDone = backToDrawer { showSessions = false },
            onOpen = { id, aid ->
                // 打开聊天**不关父页**（AI 会话列表）：返回要一层一层退回列表，
                // 原来这里 `showSessions = false`，返回一步跳回信息页根、整层跳过列表。
                // 窗口与返回的层序仍然正确：chat(980) 比 sessions(905) 后组合、后注册，
                // 所以聊天盖在列表之上、返回先关聊天。父页留着还有个好处——
                // 回到列表时滚动位置与筛选都还在，不像原来那样被整个销毁重建。
                chatSessionId = if (id == 0L) null else id
                chatAssistantId = aid
                showChat = true
            },
            onOpenProject = {
                // 打开详情**不关父页**：返回要一层一层退回会话列表。
                // 原来这里先 `showSessions = false`，于是返回一步跳回信息页根、
                // 把父页（AI 会话列表）整层跳过，与项目选择器那条路径的行为也不一致。
                showProjectDetail = it
            }
        )
    }
    if (showProjectDetail != null) {
        ProjectDetailScreen(
            projectId = showProjectDetail!!,
            onDone = { showProjectDetail = null }
        )
    }
    if (showUsage) {
        io.github.m0use001.planability.ui.profile.UsageStatsScreen(onDone = backToDrawer { showUsage = false })
    }
    if (showAutomation) {
        AutomationScreen(onDone = backToEntry {
            showAutomation = false
            // 跨 tab 打开（首页时间轴的自动化块 → Router.openAutomations）：
            // 返回先回来源页，不落在信息页根。信息页抽屉/聊天 /automation 进来时
            // 没记来源，这里直接返回、原地不动。
            io.github.m0use001.planability.app.ShellNav.returnToSourceTab("info")
        }, initialTab = automationTab)
    }
    if (showVoiceInput) {
        io.github.m0use001.planability.ui.voice.VoiceInputSheet(title = "语音输入") { text ->
            showVoiceInput = false
            if (text.isNotBlank()) {
                homeInput = if (homeInput.isBlank()) text else homeInput + " " + text
            }
        }
    }
    // 十三期：首页输入卡 v2 弹层（模型选择/权限档/@ 勾连/快捷记忆）
    if (showHomeModelPicker) {
        ModelPickerSheet(
            currentModel = homeModel.ifBlank { AiClient.config.modelName },
            onDismiss = { showHomeModelPicker = false },
            onPick = { name ->
                showHomeModelPicker = false
                homeModel = name
            }
        )
    }
    if (showHomeLinkPicker) {
        LinkPickSheet(
            onDismiss = { showHomeLinkPicker = false },
            onPick = { ref, token ->
                showHomeLinkPicker = false
                if (homeLinked.none { it.kind == ref.kind && it.id == ref.id }) homeLinked = homeLinked + ref
                homeInput = if (homeInput.endsWith("@")) homeInput.dropLast(1) + token + " "
                            else homeInput + " " + token + " "
            }
        )
    }
    homeMemoryQuick?.let { t ->
        MemoryQuickDialog(
            initialType = if (t == "/skill") "skill" else "feedback",
            suggestedBody = homeInput.trimStart().removePrefix("/memory").removePrefix("/skill").trim(),
            onDismiss = {
                homeMemoryQuick = null
                homeInput = ""
            }
        )
    }
    if (!wide && showChat) {
        ChatScreen(
            sessionId = chatSessionId,
            assistantId = chatAssistantId,
            initialInput = chatPrefill,
            projectId = chatProjectId,
            initialFolderId = chatFolderId,
            autoSend = chatAutoSend,
            initialFiles = chatFiles,
            initialLinked = chatLinked,
            draftModel = chatDraftModel,
            draftThinking = chatDraftThinking,
            draftPermJson = chatDraftPermJson,
            // 对话页是 Dialog 窗口，父页的 edgeSwipeDrawerOpen 收不到触摸——
            // 右滑手势在 ChatScreen 内部识别，这里负责"关对话 + 开抽屉"。
            // 2026-11-02：右滑前把会话上下文快照进 chatResume——抽屉里的返回要能
            // 回到刚才的会话（用户实测「右滑再返回掉到信息首页」）。
            onSwipeOpenDrawer = {
                chatResume = ChatSwipeResume(
                    sessionId = chatSessionId, assistantId = chatAssistantId, prefill = chatPrefill,
                    folderId = chatFolderId, autoSend = chatAutoSend, files = chatFiles, linked = chatLinked,
                    draftModel = chatDraftModel, draftThinking = chatDraftThinking, draftPermJson = chatDraftPermJson
                )
                showChat = false
                chatSessionId = null; chatAssistantId = homeAssistantId; chatPrefill = ""; chatAutoSend = false; chatFolderId = 0L
                chatFiles = emptyList(); chatLinked = emptyList(); chatDraftModel = ""; chatDraftThinking = ""; chatDraftPermJson = ""
                enteredFromDrawer = true
                scope.launch { drawerState.open() }
            },
            // 2026-10-30 用户：会话页的返回按钮**直接回信息界面首页**。
            // 两处都要收：①不再用 backToDrawer（原来返回必弹抽屉）；②连父页「AI 会话列表」
            // 一起关掉——列表是聊天的下一层，只关聊天会先露出列表（实测返回落在列表页）。
            // 抽屉仍可右滑打开（onSwipeOpenDrawer 未改），只是返回不再是它的触发器。
            onDone = { closeChatAll() },
            // 2026-11-08：中枢功能首页（审核/会话/更多）从抽屉入口降级为会话里的入口——
            // 点开回到中枢功能页，返回仍在当前会话
            // 2026-11-11：先收对话页再开中枢（z-order 根因见 hubReturnSessionId 注），
            // 返回经 hubReturnSessionId 回到当前会话
            onOpenHub = {
                hubReturnSessionId = chatSessionId
                showChat = false
                showAssistantHub = true
            }
        )
    }
    if (showHomeAssistantPicker) {
        AssistantPickerSheet(onDismiss = { showHomeAssistantPicker = false }) { a ->
            showHomeAssistantPicker = false
            homeAssistantId = a.id
            homeAssistantName = a.name
        }
    }

/** v12：自动化下次触发的人类描述（每日时刻型 / 间隔型） */
}
        }   // CompositionLocalProvider(LocalChartDefaults)
}
/** v19.12：设置面板里的行分隔线 */
@Composable
private fun SyncDivider() {
    androidx.compose.material3.HorizontalDivider(
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
    )
}

/** 首页输入卡下方的入口/状态胶囊（自动化 / 同步中心及其下属条目）：
 *  药丸形、surfaceContainer 底；前导位要么是主色图标、要么是状态圆点（设备连接态），
 *  `sub` 是尾部副文本（下次触发时间 / 已连接·未连接）。 */
@Composable
private fun InfoHomeCapsule(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    sub: String? = null,
    dotColor: androidx.compose.ui.graphics.Color? = null,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .clip(RoundedCornerShape(percent = 50))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp)
    ) {
        if (dotColor != null) {
            Box(
                Modifier
                    .size(10.dp)
                    .background(dotColor, CircleShape)
            )
        } else if (icon != null) {
            Icon(icon, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurface)
        if (sub != null) {
            Spacer(Modifier.width(6.dp))
            Text(
                sub, style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/** 十三期：首页 / 快捷菜单（图1 式分区：添加 / 指令），点项即执行 */
@Composable
private fun HomeSlashMenu(
    onPickFile: () -> Unit,
    onPickImage: () -> Unit,
    onModel: () -> Unit,
    onPerm: () -> Unit,
    onMemory: () -> Unit,
    onSkill: () -> Unit,
    onClearInput: () -> Unit
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 6.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(vertical = 4.dp)
    ) {
        Text(
            "添加", style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
        )
        HomeSlashRow(Icons.Rounded.AttachFile, "附件", "选择任意文件作为附件") { onPickFile() }
        HomeSlashRow(Icons.Rounded.Image, "图片", "选择相册图片（端侧 OCR）") { onPickImage() }
        Text(
            "指令", style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
        )
        HomeSlashRow(Icons.Rounded.Bolt, "/model", "选择对话模型") { onClearInput(); onModel() }
        HomeSlashRow(Icons.Rounded.Build, "/perm", "设置 AI 工具权限档位（与系统文件授权无关）") { onClearInput(); onPerm() }
        HomeSlashRow(Icons.Rounded.CheckCircle, "/memory", "把想法沉淀为一条记忆") { onClearInput(); onMemory() }
        HomeSlashRow(Icons.Rounded.Sensors, "/skill", "把可复用做法存为技能") { onClearInput(); onSkill() }
        Text(
            "输入内容以搜索 · @ 勾连上下文",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
        )
    }
}

@Composable
private fun HomeSlashRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, sub: String, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            // 2026-11-20：整行可点但无底 → 水波是一条直角横杠，补 12dp 圆角裁剪
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Icon(icon, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(10.dp))
        Text(title, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
        Spacer(Modifier.width(8.dp))
        Text(
            sub, style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1, overflow = TextOverflow.Ellipsis
        )
    }
}

/** v6 抽屉（图2 侧栏形态）：项目分组的会话列表 + 功能入口。v8 起实现迁移至 WorkbenchDrawer.kt（项目树形态） */

@Composable
private fun EntryCard(title: String, sub: String, modifier: Modifier = Modifier, onClick: () -> Unit) {    Card(
        onClick = onClick,
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = modifier
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text(sub, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// ==================== 自动采集（通知 + 读屏） ====================

/** 主页一行采集状态：点击进入信息获取二级页（采集控件已整体移入二级页） */
@Composable
private fun CaptureStatusCard(todayCount: Int, onClick: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var listenerEnabled by remember {
        mutableStateOf(
            androidx.core.app.NotificationManagerCompat
                .getEnabledListenerPackages(context).contains(context.packageName)
        )
    }
    var a11yEnabled by remember {
        mutableStateOf(
            android.provider.Settings.Secure.getString(
                context.contentResolver,
                android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            )?.contains(context.packageName) == true
        )
    }
    androidx.compose.runtime.DisposableEffect(Unit) {
        val activity = context as? androidx.activity.ComponentActivity
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e ->
            if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                listenerEnabled = androidx.core.app.NotificationManagerCompat
                    .getEnabledListenerPackages(context).contains(context.packageName)
                a11yEnabled = android.provider.Settings.Secure.getString(
                    context.contentResolver,
                    android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
                )?.contains(context.packageName) == true
            }
        }
        activity?.lifecycle?.addObserver(obs)
        onDispose { activity?.lifecycle?.removeObserver(obs) }
    }
    val allOn = listenerEnabled && a11yEnabled
    Card(
        onClick = onClick,
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(14.dp)) {
            Icon(
                Icons.Rounded.Sensors,
                null,
                tint = if (allOn) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("自动采集", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(
                    when {
                        allOn -> "通知和读屏开启 · 今日已捕获 $todayCount 条"
                        listenerEnabled || a11yEnabled -> "部分开启 · 点按进入管理"
                        else -> "未开启 · 点按进入开启与管理"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(Icons.AutoMirrored.Rounded.ArrowForward, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * 审核历史：**只读档案 + 删除**（已入库 / 已忽略）。
 *
 * 2026-11-08 用户「待处理审核与待处理，2 个界面但功能相似」——原来这里叫「待处理」，
 * 三个 tab（待确认/已入库/已忽略），与中枢的「待处理审核」卡片流做的是同一件事，
 * 两个入口两个页面，用户不知道点哪个。
 *
 * 现在分工是硬边界：
 * - **审核 = 待处理审核（HubReviewScreen）**：待确认的审批流（✓ 入库 / ✕ 忽略 / 编辑 / 跟助理说）；
 * - **历史 = 本页**：只剩已入库、已忽略两档，用来回看和清理（已忽略可删）。
 * 入口也只有一个层级：从「待处理审核」页顶栏进历史，中枢主界面不再有第二个待处理入口。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReviewHistoryScreen(onDone: () -> Unit) {
    val haptics = LocalHaptics.current
    val scope = rememberCoroutineScope()
    var tab by remember { mutableIntStateOf(0) }          // 0 = 已入库, 1 = 已忽略
    val version by Graph.dataVersion.flow.collectAsState()

    var lists by remember { mutableStateOf<Pair<List<PendingItem>, List<PendingItem>>>(emptyList<PendingItem>() to emptyList()) }
    LaunchedEffect(version) {
        lists = Graph.info.importedItems() to Graph.info.ignoredItems()
    }
    val items = if (tab == 0) lists.first else lists.second

    PredictiveBackOverlay(onBack = onDone) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("审核历史", fontWeight = FontWeight.SemiBold) },
                    navigationIcon = {
                        val overlayClose = io.github.m0use001.planability.app.LocalOverlayClose.current
                        IconButton(onClick = { haptics.perform(HapticEngine.Kind.Tap); overlayClose() }) {
                            Icon(Icons.AutoMirrored.Rounded.ArrowBack, "返回")
                        }
                    }
                )
            }
        ) { pad ->
            Column(Modifier.fillMaxSize().padding(pad)) {
                TabRow(selectedTabIndex = tab, indicator = { pos -> MotionTabIndicator(pos, tab) }) {
                    listOf("已入库", "已忽略").forEachIndexed { i, label ->
                        Tab(
                            selected = tab == i,
                            onClick = { tab = i; haptics.perform(HapticEngine.Kind.Select) },
                            text = { Text(label) }
                        )
                    }
                }
                androidx.compose.runtime.key(tab) {
                    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
                    LazyColumn(
                        Modifier.fillMaxSize().padding(horizontal = 16.dp).reachedEndHaptic(listState),
                        state = listState
                    ) {
                        if (items.isEmpty()) item { EmptyHint("暂无条目") }
                        items(items, key = { it.id }) { item ->
                            Card(
                                shape = RoundedCornerShape(16.dp),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                                modifier = Modifier.animateItem().fillMaxWidth().padding(vertical = 5.dp)
                            ) {
                                Column(Modifier.padding(12.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            if (item.kind == "schedule") "日程" else "待办",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier
                                                .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(6.dp))
                                                .padding(horizontal = 6.dp, vertical = 2.dp)
                                        )
                                        Spacer(Modifier.width(8.dp))
                                        Text(item.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                                    }
                                    if (item.timeText.isNotBlank() || item.place.isNotBlank() || item.extra.isNotBlank()) {
                                        Text(
                                            listOf(item.timeText, item.place, item.extra).filter { it.isNotBlank() }.joinToString(" · "),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    // 已忽略可删（用户 2026-10-25 要求，删掉这条记录本身）
                                    if (tab == 1) {
                                        Row(Modifier.padding(top = 6.dp)) {
                                            TextButton(onClick = {
                                                haptics.perform(HapticEngine.Kind.ImpactHeavy)
                                                scope.launch { Graph.info.deletePending(item.id) }
                                            }) {
                                                Text("删除", color = MaterialTheme.colorScheme.error)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        item { Spacer(Modifier.height(60.dp)) }
                    }
                }
            }
        }
    }
}
