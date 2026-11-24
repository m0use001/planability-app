@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package io.github.m0use001.planability.ui.info

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import io.github.m0use001.planability.ui.common.MotionTabIndicator
import io.github.m0use001.planability.ui.common.motionEnter
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.m0use001.planability.app.LocalOverlayClose
import io.github.m0use001.planability.app.PredictiveBackOverlay
import io.github.m0use001.planability.ai.mcp.McpClient
import io.github.m0use001.planability.ai.mcp.McpRepo
import io.github.m0use001.planability.ai.mcp.McpServer
import io.github.m0use001.planability.core.ActionRegistry
import io.github.m0use001.planability.core.AutomationStep
import io.github.m0use001.planability.core.FlowRunner
import io.github.m0use001.planability.core.HapticEngine
import io.github.m0use001.planability.core.LocalHaptics
import io.github.m0use001.planability.core.reachedEndHaptic
import io.github.m0use001.planability.data.Automation
import io.github.m0use001.planability.data.AutomationRepo
import io.github.m0use001.planability.data.AutomationRun
import io.github.m0use001.planability.notify.AutomationSignal
import io.github.m0use001.planability.notify.AutomationSweep
import io.github.m0use001.planability.data.Graph
import io.github.m0use001.planability.data.MemoryEntity
import io.github.m0use001.planability.ui.common.AppIcons
import io.github.m0use001.planability.ui.common.MediumFab
import io.github.m0use001.planability.ui.edit.GeoPoint
import io.github.m0use001.planability.ui.edit.MapPickerSheet
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AddToHomeScreen
import androidx.compose.material.icons.rounded.PlayArrow
import kotlinx.coroutines.launch
import io.github.m0use001.planability.ui.common.filterChipColors

/**
 * v11：AI 扩展中心——自动化任务 / MCP 服务器 / 导入（SKILL.md、MCP JSON、插件开关）。
 * AI 工具（automation_*、mcp_*、plugin_toggle 等）与本页共用同一数据层。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AutomationScreen(
    onDone: () -> Unit,
    initialTab: Int = 0,
    /** 合并屏（ui/info/ToolsHub.kt）的顶部切换器；null=本屏自己顶栏 */
    topSwitcher: (@Composable () -> Unit)? = null,
    /** 合并屏已在外层套 [PredictiveBackOverlay] → 本屏只画内容（两个 Dialog 接连关开会闪一帧） */
    insideOverlay: Boolean = false,
    /** 合并屏只借一个子页（如仅 MCP）时隐藏本屏自己的 TabRow，避免与合并屏切换器重复 */
    hideTabs: Boolean = false,
    /** v77：板内钻入瓦片——透明底 + 板内页头 + 0 inset；AutomationTab 强制单板（无框套框） */
    embedded: Boolean = false
) {
    val scope = rememberCoroutineScope()
    val haptics = LocalHaptics.current
    var tab by remember { mutableIntStateOf(initialTab.coerceIn(0, 3)) }

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
                            title = "自动化与扩展",
                            onBack = onDone
                        )
                    } else {
                    TopAppBar(
                        title = { Text("自动化与扩展", fontWeight = FontWeight.SemiBold) },
                        navigationIcon = {
                            // 顶栏返回 = 中性点击 → Tap（HIG 映射表：Tap 用于顶栏返回/展开收起）
                            val overlayClose = LocalOverlayClose.current
                            IconButton(onClick = { haptics.perform(HapticEngine.Kind.Tap); overlayClose() }) {
                                Icon(AppIcons.Nav.Back, "返回", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    )
                    }
                    if (topSwitcher != null) {
                        Column(Modifier.fillMaxWidth()) { topSwitcher() }
                    }
                }
            }
        ) { pad ->
            Column(Modifier.fillMaxSize().padding(pad)) {
                // 指示条换 M3E 弹簧滑动（MotionTabIndicator）：默认是定长 tween 硬曲线，不在弹簧体系
                if (!hideTabs) {
                    TabRow(selectedTabIndex = tab, indicator = { pos -> MotionTabIndicator(pos, tab) }) {
                        // 「跳转」追加在**末尾**而不是插在自动化旁边：三处调用方
                        // （ProfileScreens / InfoCenterScreen）按 0=自动化 1=MCP 2=导入 传 initialTab，
                        // 插中间会让那三个入口全部错位到隔壁标签。
                        listOf("自动化", "MCP", "导入", "跳转").forEachIndexed { i, label ->
                            // 二级 tab 切换 = 选择 → Select（全 app 同语义同手感）
                            Tab(selected = tab == i, onClick = { tab = i; haptics.perform(HapticEngine.Kind.Select) }, text = { Text(label) })
                        }
                    }
                }
                when (tab) {
                    0 -> AutomationTab(embedded)
                    1 -> McpTab()
                    3 -> AppJumpPane()
                    else -> ImportTab()
                }
            }
        }
    }
    // v64：宽屏整屏承载两板（不再收成居中 1000dp 模态卡；用户拍板）
    val autoWide = io.github.m0use001.planability.core.rememberWindowSizeClass().isWide
    if (insideOverlay) shell() else PredictiveBackOverlay(onBack = onDone, fullscreen = autoWide) { shell() }
}

// ==================== 自动化 ====================

@Composable
private fun AutomationTab(embedded: Boolean = false) {
    val scope = rememberCoroutineScope()
    val haptics = LocalHaptics.current
    var list by remember { mutableStateOf<List<Automation>>(emptyList()) }
    var editing by remember { mutableStateOf<Automation?>(null) }
    var showEditor by remember { mutableStateOf(false) }
    // v64：宽屏右板的选中态（窄屏没有右板，点行仍直接弹编辑框）
    var selectedId by remember { mutableStateOf<Long?>(null) }
    // v35：当前要看执行日志的任务（null = 不弹）
    var logFor by remember { mutableStateOf<Automation?>(null) }
    // v19.40：点了「运行」的任务（null = 没在跑）
    var running by remember { mutableStateOf<Automation?>(null) }
    val version by Graph.dataVersion.flow.collectAsState()
    LaunchedEffect(version) { list = AutomationRepo.all() }
    // v19.43：每次列表刷新顺手把桌面快捷方式重新发一遍。
    // 不挂 Application.onCreate（那是所有人的文件），代价是「改了指令、没进这个页面」
    // 桌面长按菜单还是旧的——而桌面图标本身仍指向正确的 id，不至于跑错指令。
    val context = LocalContext.current
    LaunchedEffect(version) { io.github.m0use001.planability.app.AutomationShortcuts.publishAsync(context) }
    // v19.42：进页面就把「打开应用时」的观察器拉起来。
    // 不这么做的话，用户刚建完这条触发、立刻切到那个 App 去试，会没反应——
    // 而它其实要等巡检（≤15 分钟）才起监听。看起来就是「功能没生效」。
    LaunchedEffect(Unit) { AutomationSignal.ensureAppObserver() }

    // v19.40：真跑一次。execute() **不看 hasTrigger**，所以无触发的快捷指令也能走它；
    // 它同时写 v35 的执行记录并照常发通知。跑完直接把日志弹开——
    // 点「运行」最想知道的就是"成没成、没成是为什么"。
    // LaunchedEffect 的 key 是 running：同一个任务重复点不会并发起第二个。
    val runContext = LocalContext.current
    LaunchedEffect(running) {
        val a = running ?: return@LaunchedEffect
        runCatching { AutomationSweep.execute(runContext, a) }
        running = null
        logFor = a
    }

    // v64（2026-11-20）：宽屏主从两板——左板=任务列表，右板=选中任务的详情板
    //（运行/日志/编辑都在板上；编辑表单本体仍是模态——四百行的表单塞进 380dp 板里读不了）。
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
        val contentW = maxWidth
        val detailW = io.github.m0use001.planability.ui.common.MasterDetail.detailWidthFor(contentW, content = false)
        val pane = io.github.m0use001.planability.ui.common.MasterDetail
            .paneOf(contentW, detailW, hasSelection = selectedId != null)
        val twoPane = !embedded && pane != io.github.m0use001.planability.ui.common.DetailPane.Single
        androidx.activity.compose.BackHandler(enabled = twoPane && selectedId != null) { selectedId = null }
        io.github.m0use001.planability.ui.common.SplitBoards(
            twoPane = twoPane,
            detailWidth = detailW,
            applyTopInset = false,
            applyBottomInset = false,
            primary = {
        val autoState = androidx.compose.foundation.lazy.rememberLazyListState()
        LazyColumn(
            Modifier.fillMaxSize().padding(horizontal = 16.dp).reachedEndHaptic(autoState),
            state = autoState
        ) {
            if (list.isEmpty()) {
                item {
                    io.github.m0use001.planability.ui.common.EmptyHint(
                        "还没有自动化任务\n点右下角 + 新建（也可直接让 AI 创建）"
                    )
                }
            }
            // v19.40：按 Apple 快捷指令的两栏分组——「快捷指令」**无触发**、手动点运行；
            // 「自动化」有触发、到点自己跑。分两段是因为**使用时机不同**：
            // 一个是你主动想要（点它），一个是系统替你做（等它）。
            // 判据直接用 hasTrigger，不需要新字段：sweep 本来就只跑 hasTrigger 的。
            val manual = list.filter { !it.hasTrigger }
            val auto = list.filter { it.hasTrigger }
            val rows: List<Any> = buildList {
                if (manual.isNotEmpty()) {
                    add(SectionHeader("快捷指令", "点 ▶ 才执行 · 不占定时器", manual.size))
                    addAll(manual)
                }
                if (auto.isNotEmpty()) {
                    add(SectionHeader("自动化", "到点自动执行", auto.size))
                    addAll(auto)
                }
            }
            items(
                rows,
                key = { r -> if (r is SectionHeader) "h-" + r.title else "a-" + (r as Automation).id }
            ) { row ->
                if (row is SectionHeader) {
                    ListSection(row)
                    return@items
                }
                val a = row as Automation
                val trigger = when {
                    a.intervalMinutes > 0 -> "每 " + a.intervalMinutes + " 分钟"
                    a.dailyHour >= 0 -> "每日 %02d:%02d".format(a.dailyHour, a.dailyMinute)
                    // v24：位置触发的文案要说清是「到」还是「走」，以及范围多大
                    a.geoTrigger == "arrive" -> "到达 %.4f,%.4f %d 米内".format(a.geoLat, a.geoLng, a.geoRadiusM)
                    a.geoTrigger == "leave" -> "离开 %.4f,%.4f %d 米".format(a.geoLat, a.geoLng, a.geoRadiusM)
                    // v19.42：事件触发优先显示——一条只配了事件的自动化 interval/daily 都是空值，
                    // 排在后面的话文案会掉到「手动」那一档去，看着像快捷指令。
                    a.hasEventTrigger -> io.github.m0use001.planability.notify.AutomationSignal
                        .describe(a.triggerKind, a.triggerArg)
                    else -> "手动"
                }
                // 多行卡片：原来一行里塞了「标题+触发+描述 + 4 个图标按钮」，
                // 屏幕上被压得几乎没有留白（用户反馈「每个自动化任务改成占多行，不然太挤」）。
                // 现在拆成：① 标题 + 开关 ② 触发方式 ③ 描述 ④ 操作行（运行/日志/钉桌面/删除）。
                Column(
                    modifier = Modifier.animateItem()
                        .fillMaxWidth()
                        .padding(vertical = 5.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(
                            // 宽屏选中高亮（与计划页选中行同一语言）；窄屏没有选中态
                            if (twoPane && selectedId == a.id) MaterialTheme.colorScheme.secondaryContainer
                            else MaterialTheme.colorScheme.surfaceContainer
                        )
                        .clickable {
                            selectedId = a.id
                            // 窄屏没有右板：点行直接进编辑框（原路径）
                            if (!twoPane) { editing = a; showEditor = true }
                        }
                        .padding(horizontal = 12.dp, vertical = 10.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            a.name,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        Switch(checked = a.enabled, onCheckedChange = { on ->
                            haptics.perform(HapticEngine.Kind.Select)
                            scope.launch { AutomationRepo.setEnabled(a.id, on) }
                        })
                    }
                    Text(
                        trigger,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        softWrap = false
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        if (a.kind == "flow")
                            "步骤流水线 · " + AutomationStep.decodeList(a.stepsJson)
                                .joinToString(" → ") { ActionRegistry.labelOf(it.type) }
                        else a.prompt.take(60),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // v19.40：**运行**。快捷指令全靠它——没有触发器时这是唯一的执行入口；
                        // 有触发器的也照样能"现在就跑一次"（试手动触发，不影响它的定时）。
                        IconButton(onClick = {
                            haptics.perform(HapticEngine.Kind.Tap)
                            running = a
                        }) {
                            // PlayArrow 是**扩展属性**（定义在 androidx.compose.material.icons.rounded 包里），
                            // 只靠 FQN 引用 Icons.Rounded 是解析不到的——必须 import 扩展本身。
                            Icon(Icons.Rounded.PlayArrow, "运行", tint = MaterialTheme.colorScheme.primary)
                        }
                        // v35：执行日志。放在运行和删除之间——
                        // 「跑成什么样」是只读查询，比删除更常用，不该排在删除后面被挤掉。
                        IconButton(onClick = {
                            haptics.perform(HapticEngine.Kind.Tap)
                            logFor = a
                        }) {
                            Icon(
                                AppIcons.State.Pending,
                                "执行日志",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        // v19.43：**钉到桌面**。把这条指令变成桌面上的独立图标，点一下就跑。
                        // 被拒绝（启动器不支持 / 用户关了「允许添加快捷方式」）不算故障，
                        // 长按 App 图标里那条快捷方式照样在，所以只给一次轻触感不弹提示。
                        IconButton(onClick = {
                            haptics.perform(HapticEngine.Kind.Tap)
                            io.github.m0use001.planability.app.AutomationShortcuts.pin(context, a)
                        }) {
                            Icon(
                                Icons.Rounded.AddToHomeScreen,
                                "钉到桌面",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        IconButton(onClick = {
                            haptics.perform(HapticEngine.Kind.Error)
                            scope.launch {
                                AutomationRepo.delete(a.id)
                                io.github.m0use001.planability.app.AutomationShortcuts.publishAsync(context)
                            }
                        }) {
                            Icon(AppIcons.Act.Delete, null, tint = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(90.dp)) }
        }
        // B3-B：主 FAB 升 Medium（80dp/20dp，M3E 最推荐档位；试点）
        // v64：两板时贴左板右下角（在 SplitBoards 的板 Box 里 align）
        MediumFab(
            // FAB = 打开新建弹层的中性点击 → Tap（与 PlanScreen 打开弹层用 Tap 一致）
            onClick = { haptics.perform(HapticEngine.Kind.Tap); editing = null; showEditor = true },
            modifier = if (twoPane) {
                Modifier.align(Alignment.BottomEnd).padding(end = 20.dp, bottom = 20.dp)
            } else {
                Modifier.align(Alignment.BottomCenter).padding(bottom = 20.dp)
            }
        ) { Icon(AppIcons.Act.Add, contentDescription = "新建自动化") }
        },
        secondary = {
            val sel = list.firstOrNull { it.id == selectedId }
            if (sel == null) {
                AutomationOverviewPane(auto = list.count { it.hasTrigger }, manual = list.count { !it.hasTrigger })
            } else {
                AutomationDetailPane(
                    a = sel,
                    onRun = { haptics.perform(HapticEngine.Kind.Tap); running = sel },
                    onLog = { haptics.perform(HapticEngine.Kind.Tap); logFor = sel },
                    onEdit = { haptics.perform(HapticEngine.Kind.Tap); editing = sel; showEditor = true }
                )
            }
        }
    )
    }

    if (showEditor) {
        AutomationEditorDialog(
            initial = editing,
            onDismiss = { showEditor = false },
            onSave = { a ->
                scope.launch {
                    AutomationRepo.save(a)
                    // 保存完立刻重发：桌面长按菜单里要马上能看到新指令，
                    // 否则用户得再进一次页面（而他刚保存完，通常就在这一页）
                    io.github.m0use001.planability.app.AutomationShortcuts
                        .publishAsync(context)
                }
                showEditor = false
            }
        )
    }

    logFor?.let { a ->
        RunLogDialog(a) { logFor = null }
    }
}

/** v19.40：列表分组表头。快捷指令 / 自动化 两栏的栏头 */
private data class SectionHeader(val title: String, val sub: String, val count: Int)

@Composable
private fun ListSection(h: SectionHeader) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp, start = 2.dp)
    ) {
        Text(h.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.width(8.dp))
        Text(
            h.count.toString(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.weight(1f))
        Text(
            h.sub,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * v35：一个任务的执行记录。
 *
 * ## 为什么值得单独一个弹层
 * 在这之前，「到点跑没跑、跑成什么样」只有两处痕迹：一条会被划掉的系统通知，
 * 和会话里那轮对话（AI 路径失败时**连会话都不一定落下**）。
 * 失败信息更是只进 logcat——界面上没有任何回溯点。PC 端早有 TaskLog，手机端一直缺。
 *
 * ## 几个观感上的取舍
 *  - **成功与失败同一张列表**，只用图标和文字色区分。分成「全部/失败」两个 tab
 *    在手机上反而要多点一次，而失败本来就是少数，混排时更扎眼。
 *  - **明细默认展开但限行数**：flow 的每步结果是这次执行最重要的信息，藏起来就没意义了；
 *    只用 maxLines 兜住长度，避免一条长任务把整个列表撑破。
 *  - 加载态先显示「加载中…」而不是空转圈：空态和加载态长得像的话，
 *    用户会把「正在读」误读成「没有记录」，进而以为功能坏了。
 */
@Composable
private fun RunLogDialog(a: Automation, onDismiss: () -> Unit) {
    var rows by remember { mutableStateOf<List<AutomationRun>?>(null) }
    LaunchedEffect(a.id) { rows = AutomationRepo.runsOf(a.id, 50) }

    AlertDialog(
        modifier = Modifier.motionEnter(),
        onDismissRequest = onDismiss,
        title = {
            Text(
                "执行记录 · " + a.name,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        text = {
            val list = rows
            when {
                list == null -> Text("加载中…", style = MaterialTheme.typography.bodyMedium)
                list.isEmpty() -> Text(
                    "还没有执行记录。\n\n任务到点跑过一次之后，成功与失败都会出现在这里——" +
                        "包括失败原因和每一步的结果。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                else -> LazyColumn(
                    Modifier.fillMaxWidth().heightIn(max = 420.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    items(list, key = { it.id }) { r -> RunLogRow(r) }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } }
    )
}

@Composable
private fun RunLogRow(r: AutomationRun) {
    Row(
        verticalAlignment = Alignment.Top,
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)
    ) {
        Icon(
            if (r.ok) AppIcons.State.Done else AppIcons.State.Warn,
            null,
            Modifier.size(18.dp),
            tint = if (r.ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                RUN_LOG_TIME.format(java.time.Instant.ofEpochMilli(r.at)) +
                    " · " + (if (r.kind == "flow") "流水线" else "AI") +
                    " · " + r.durationMs + "ms",
                style = MaterialTheme.typography.labelSmall,
                color = if (r.ok) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.error
            )
            if (r.summary.isNotBlank()) {
                Text(
                    r.summary,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
            if (r.detail.isNotBlank()) {
                Text(
                    r.detail,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 8,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }
    }
}

private val RUN_LOG_TIME: java.time.format.DateTimeFormatter =
    java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm")
        .withZone(io.github.m0use001.planability.core.AppTime.zone)

@Composable
private fun AutomationEditorDialog(
    initial: Automation?,
    onDismiss: () -> Unit,
    onSave: (Automation) -> Unit
) {
    // 编辑器里的 FilterChip 全是「选择」语义 → Select
    val haptics = LocalHaptics.current
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var prompt by remember { mutableStateOf(initial?.prompt ?: "") }
    var assistantKey by remember { mutableStateOf(initial?.assistantKey ?: "general") }
    var useDaily by remember { mutableStateOf(initial?.let { it.dailyHour >= 0 } ?: true) }
    var interval by remember { mutableStateOf((initial?.intervalMinutes ?: 60).toString()) }
    // v24：位置触发的编辑态
    var useGeo by remember { mutableStateOf(initial?.let { it.geoTrigger != "none" } ?: false) }
    // v19.40：第四种触发 = **不自动触发**，也就是 Apple 快捷指令里的"快捷指令"本身
    // （有"自动化"才有触发器；快捷指令是手动点的）。
    // 落库上不需要任何新字段：存成 `hasTrigger == false` 天然就不会被调度——
    // AutomationSweep 本来就只跑 `enabled && hasTrigger`，这正好是它该有的行为。
    var useManual by remember { mutableStateOf(initial?.let { !it.hasTrigger } ?: false) }
    // v19.42：事件触发。初始化从已有任务反推——编辑一条事件型任务时不能退回「按间隔」默认档，
    // 否则一进编辑页就把它悄悄改成另一种触发。
    var useEvent by remember { mutableStateOf(initial?.triggerKind?.isNotBlank() ?: false) }
    var eventKind by remember { mutableStateOf(initial?.triggerKind?.takeIf { it.isNotBlank() } ?: AutomationSignal.KIND_BOOT) }
    var eventArg by remember {
        mutableStateOf(initial?.takeIf { it.triggerKind == AutomationSignal.KIND_APP_OPEN }?.triggerArg ?: "")
    }
    /** 保存失败原因。原实现是**静默**的：条件不满足时按钮点了毫无反应，看着像卡死 */
    var saveErr by remember { mutableStateOf<String?>(null) }
    var geoTrigger by remember { mutableStateOf(initial?.geoTrigger?.takeIf { it != "none" } ?: "arrive") }
    var geoLat by remember { mutableStateOf(initial?.geoLat ?: 0.0) }
    var geoLng by remember { mutableStateOf(initial?.geoLng ?: 0.0) }
    var geoRadius by remember { mutableStateOf((initial?.geoRadiusM?.takeIf { it > 0 } ?: 300).toString()) }
    var geoPicked by remember { mutableStateOf(false) }
    var hour by remember { mutableStateOf((initial?.dailyHour ?: 8).toString()) }
    var minute by remember { mutableStateOf((initial?.dailyMinute ?: 0).toString()) }
    // v26：执行方式与流水线草稿（保存时才 encode，编辑过程全程持有 List<AutomationStep>）
    var kind by remember { mutableStateOf(initial?.kind ?: "ai") }
    var steps by remember { mutableStateOf(AutomationStep.decodeList(initial?.stepsJson ?: "[]")) }
    var showAddStep by remember { mutableStateOf(false) }
    var trial by remember { mutableStateOf<FlowRunner.Result?>(null) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val assistants = remember { mutableStateOf(listOf<io.github.m0use001.planability.data.Assistant>()) }
    LaunchedEffect(Unit) { assistants.value = Graph.assistants.all() }

    AlertDialog(
        modifier = Modifier.motionEnter(),
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "新建自动化" else "编辑自动化") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.verticalScroll(rememberScrollState())
            ) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("名称") }, singleLine = true)
                Text("执行方式", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(colors = filterChipColors(), selected = kind == "ai", onClick = { kind = "ai"; haptics.perform(HapticEngine.Kind.Select) }, label = { Text("AI 指令") })
                    FilterChip(colors = filterChipColors(), selected = kind == "flow", onClick = { kind = "flow"; haptics.perform(HapticEngine.Kind.Select) }, label = { Text("步骤流水线") })
                }
                Text(
                    if (kind == "ai") "触发时把指令交给模型执行，结果发通知。"
                    else "固定步骤按顺序自动跑：不调模型、不烧 token，遇错即停并出通知。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (kind == "ai") {
                    OutlinedTextField(value = prompt, onValueChange = { prompt = it }, label = { Text("触发时执行的 AI 指令") }, minLines = 4)
                    Text("执行者", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    // v24：改成横向滚动 —— 原来 5 个 FilterChip 塞在不滚动的 Row 里，
                    // 一行放不下就把最后一个挤到屏幕外被截断（用户报"界面显示有问题"）
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.horizontalScroll(rememberScrollState())
                    ) {
                        assistants.value.forEach { s ->
                            FilterChip(
                                colors = filterChipColors(),
                                selected = assistantKey == s.key,
                                onClick = { assistantKey = s.key; haptics.perform(HapticEngine.Kind.Select) },
                                label = { Text(s.emoji + s.name, maxLines = 1, softWrap = false) }
                            )
                        }
                    }
                } else {
                    FlowStepsSection(
                        steps = steps,
                        onChange = { steps = it },
                        onAdd = { showAddStep = true },
                        onTrial = {
                            // 试运行不落库：automationId=0，直接把当前草稿 encode 后喂执行器
                            scope.launch {
                                trial = FlowRunner.run(
                                    context, 0,
                                    name.trim().ifBlank { "试运行" },
                                    AutomationStep.encodeList(steps)
                                )
                            }
                        }
                    )
                }
                Text("触发", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    // 前三个 chip 的 selected 都要过 `!useManual`：否则选「手动」之后
                    // 原来那个触发方式仍然显示成选中，两个同时亮着，存的时候谁生效看不出来。
                    FilterChip(colors = filterChipColors(), selected = !useManual && !useDaily && !useGeo, onClick = { useManual = false; useDaily = false; useGeo = false; haptics.perform(HapticEngine.Kind.Select) }, label = { Text("按间隔") })
                    FilterChip(colors = filterChipColors(), selected = !useManual && useDaily && !useGeo, onClick = { useManual = false; useDaily = true; useGeo = false; haptics.perform(HapticEngine.Kind.Select) }, label = { Text("每日时刻") })
                    // v24：位置触发（进/出某个地方）。三种触发互斥——同一条自动化
                    // 同时按时间和按位置跑会让用户搞不清是哪条规则生效了。
                    FilterChip(colors = filterChipColors(), selected = !useManual && useGeo, onClick = { useGeo = true; useDaily = false; useManual = false; haptics.perform(HapticEngine.Kind.Select) }, label = { Text("到地点") })
                    // v19.40：快捷指令——不触发，只在你点「运行」时执行
                    FilterChip(colors = filterChipColors(), selected = useManual, onClick = { useManual = true; useGeo = false; useDaily = false; haptics.perform(HapticEngine.Kind.Select) }, label = { Text("手动") })
                    // v19.42：事件触发（充电/解锁/开机/打开应用）——单独一档而不是把五个事件
                    // 全塞进这一行：一行挤九个 chip 会折行，折行后被挤下去的那个最容易被忽略，
                    // 而「被忽略的触发条件」等于没有。
                    FilterChip(colors = filterChipColors(), selected = !useManual && useEvent, onClick = { useEvent = true; useManual = false; useGeo = false; useDaily = false; haptics.perform(HapticEngine.Kind.Select) }, label = { Text("事件") })
                }
                if (useEvent) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(
                            "开机后" to (AutomationSignal.KIND_BOOT to ""),
                            "插上电" to (AutomationSignal.KIND_POWER to "on"),
                            "拔掉电" to (AutomationSignal.KIND_POWER to "off"),
                            "解锁后" to (AutomationSignal.KIND_UNLOCK to "")
                        ).forEach { (label, v) ->
                            FilterChip(
                                colors = filterChipColors(),
                                selected = eventKind == v.first && (v.first != AutomationSignal.KIND_POWER || eventArg == v.second),
                                onClick = {
                                    eventKind = v.first; eventArg = v.second
                                    haptics.perform(HapticEngine.Kind.Select)
                                },
                                label = { Text(label) }
                            )
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(
                            colors = filterChipColors(),
                            selected = eventKind == AutomationSignal.KIND_APP_OPEN,
                            onClick = {
                                // 顺手清空参数：上一个事件若是「插上电」，eventArg 里还留着 "on"，
                                // 直接切过来会存成 app_open + arg="on" —— 永远匹配不到任何窗口，
                                // 而且**没有任何报错**（没匹配上就是没匹配上）
                                eventKind = AutomationSignal.KIND_APP_OPEN; eventArg = ""
                                haptics.perform(HapticEngine.Kind.Select)
                            },
                            label = { Text("打开应用") }
                        )
                    }
                    if (eventKind == AutomationSignal.KIND_APP_OPEN) {
                        OutlinedTextField(
                            value = eventArg,
                            onValueChange = { eventArg = it.trim() },
                            label = { Text("应用包名") },
                            placeholder = { Text("如 com.tencent.mm") },
                            singleLine = true,
                            supportingText = {
                                Text("用包名，不用中文名。跳转页里能看到每个应用的包名")
                            }
                        )
                        Text(
                            "要**开着无障碍**这条才有效（它复用无障碍服务已经在跑的窗口监听）。" +
                                "没开的话什么都不发生——不会报错。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                if (useManual) {
                    Text(
                        "不自动触发 = 存成快捷指令：只在列表里点「运行」时执行，" +
                            "不会占用任何定时器，也不进后台巡检。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (useGeo) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(
                            colors = filterChipColors(),
                            selected = geoTrigger == "arrive",
                            onClick = { geoTrigger = "arrive"; haptics.perform(HapticEngine.Kind.Select) },
                            label = { Text("到达时") }
                        )
                        FilterChip(
                            colors = filterChipColors(),
                            selected = geoTrigger == "leave",
                            onClick = { geoTrigger = "leave"; haptics.perform(HapticEngine.Kind.Select) },
                            label = { Text("离开时") }
                        )
                    }
                    OutlinedTextField(
                        value = geoRadius,
                        onValueChange = { geoRadius = it.filter { ch -> ch.isDigit() }.take(5) },
                        label = { Text("范围半径（米，≥50）") }, singleLine = true, modifier = Modifier.fillMaxWidth()
                    )
                    // 判据用**编辑态**而不是 initial：新建时 initial 为 null，而 `null != 0.0`
                    // 在 Kotlin 里恒为 true，会把「重新选点」误判成已有坐标；选完点也要立刻切过来。
                    if (geoLat != 0.0 || geoLng != 0.0) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                ("围栏中心 %.4f, %.4f".format(geoLat, geoLng)) +
                                    (if (initial?.geoInside == true) "（当前在范围内）" else ""),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.width(8.dp))
                            TextButton(onClick = { geoPicked = true }) { Text("重新选点") }
                        }
                    } else {
                        TextButton(onClick = { geoPicked = true }) { Text("在地图上选围栏中心") }
                    }
                    // 选点面板必须开**独立窗口**：编辑器本身是 AlertDialog（独立 window），
                    // 把 MapSheet 直接塞进它的 Column 里，MapSheet 根节点的 fillMaxSize 只能拿到
                    // 剩余高度，整块被裁成底部一条（真机实测），地图和按钮都点不到。
                    if (geoPicked) {
                        // 选点面板自带全屏窗口，这里不再包 Dialog
                        MapPickerSheet(
                            initial = if (geoLat != 0.0 || geoLng != 0.0) GeoPoint(geoLat, geoLng) else null,
                            onDismiss = { geoPicked = false },
                            onPick = { p, _ -> geoLat = p.lat; geoLng = p.lng; geoPicked = false }
                        )
                    }
                    // 说清代价：这是会常驻听位置的
                    Text(
                        "开启后会在后台持续监听位置（每 30 秒 / 移动 50 米取一次）。" +
                            "把这条自动化关掉或删掉，监听会立刻停止。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    // 没选中心时保存会静默失败（hasTrigger 判不过），把原因摆出来
                    if (geoLat == 0.0 && geoLng == 0.0) {
                        Text(
                            "还没选围栏中心，选好后才能保存。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
                if (useManual) {
                    // 手动模式下不展示间隔/时刻输入框——它们在这一档里没有意义，
                    // 留着反而让人以为"填了就会跑"。
                    Text(
                        "（这条不会自己跑；要在列表里点它的「运行」）",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else if (useEvent) {
                    // 事件档同样不展示间隔/时刻：这一档的时间由系统事件决定，
                    // 留一个「间隔分钟」输入框在那儿只会让人填了之后发现没用。
                    Text(
                        "（由系统事件触发，不按时间）",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else if (useDaily) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = hour, onValueChange = { hour = it.filter { ch -> ch.isDigit() }.take(2) },
                            label = { Text("时(0-23)") }, singleLine = true, modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = minute, onValueChange = { minute = it.filter { ch -> ch.isDigit() }.take(2) },
                            label = { Text("分(0-59)") }, singleLine = true, modifier = Modifier.weight(1f)
                        )
                    }
                } else {
                    OutlinedTextField(
                        value = interval, onValueChange = { interval = it.filter { ch -> ch.isDigit() }.take(5) },
                        label = { Text("间隔分钟（≥15）") }, singleLine = true
                    )
                }
                saveErr?.let {
                    Text(
                        it, style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        // v24：取消/保存 合并进同一个 confirmButton 槽，各占一半宽。
        // 分成 dismiss+confirm 两个槽时 AlertDialog 给的是各自 wrap 内容的宽度，
        // 两个中文按钮放不下就自己折行（「保存」变"显/示"竖排），实测必现。
        confirmButton = {
            Row(Modifier.fillMaxWidth()) {
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f)
                ) { Text("取消", maxLines = 1, softWrap = false) }
                TextButton(
                    onClick = {
                        val h = hour.toIntOrNull() ?: -1
                        val m = minute.toIntOrNull() ?: -1
                        val iv = interval.toIntOrNull() ?: 0
                        val gr = geoRadius.toIntOrNull() ?: 300
                        val base = Automation(
                            id = initial?.id ?: 0, name = name.trim(), prompt = prompt.trim(), assistantKey = assistantKey,
                            kind = kind,
                            stepsJson = AutomationStep.encodeList(steps),
                            enabled = initial?.enabled ?: true,
                            // 位置态要一起存：换触发方式时不能把围栏坐标丢掉
                            geoTrigger = if (useGeo) geoTrigger else "none",
                            geoLat = if (useGeo) geoLat else 0.0,
                            geoLng = if (useGeo) geoLng else 0.0,
                            geoRadiusM = if (useGeo) gr.coerceAtLeast(50) else 0,
                            geoInside = if (useGeo) (initial?.geoInside ?: false) else false
                        )
                        val a = when {
                            // 手动 = 快捷指令：三种触发字段全部清空，hasTrigger 从此为 false，
                            // 巡检天然跳过它、而"运行"按钮直接调 execute()（execute 不看 hasTrigger）
                            useManual -> base.copy(intervalMinutes = 0, dailyHour = -1, dailyMinute = -1)
                            // v19.42：事件触发。清空定时字段、靠 triggerKind/triggerArg 立着。
                            // 切换档位时**必须显式清空** triggerKind——否则「把一条事件型改成按间隔」
                            // 会变成「按间隔 + 事件同时成立」，两个触发器一起响。
                            useEvent -> base.copy(
                                intervalMinutes = 0, dailyHour = -1, dailyMinute = -1,
                                triggerKind = eventKind,
                                triggerArg = if (eventKind == AutomationSignal.KIND_APP_OPEN) eventArg else ""
                            )
                            useGeo -> base.copy(intervalMinutes = 0, dailyHour = -1, dailyMinute = -1, triggerKind = "", triggerArg = "")
                            useDaily -> base.copy(intervalMinutes = 0, dailyHour = h.coerceIn(0, 23), dailyMinute = m.coerceIn(0, 59), triggerKind = "", triggerArg = "")
                            else -> base.copy(intervalMinutes = iv.coerceAtLeast(15), dailyHour = -1, dailyMinute = -1, triggerKind = "", triggerArg = "")
                        }
                        // 流水线没 prompt 也要能存（它根本不读 prompt），正文判据换成「至少一步」
                        val hasBody = if (a.kind == "flow") steps.isNotEmpty() else a.prompt.isNotBlank()
                        // **把原因说出来**：原来条件不满足时什么都不发生，按钮点了毫无反应，
                        // 用户会以为卡住/没点上，反复点（这条是真踩过的观感问题）
                        saveErr = when {
                            a.name.isBlank() -> "先给它起个名字"
                            !hasBody -> if (a.kind == "flow") "流水线至少要有一步" else "AI 指令不能为空"
                            // app_open 没有包名 = 匹配不到任何窗口，等于一条永不触发的空壳；
                            // 而它**不会报错**（没匹配上就是没匹配上），所以只能在这里挡
                            useEvent && eventKind == AutomationSignal.KIND_APP_OPEN && eventArg.isBlank() ->
                                "选了「打开应用」就得填包名（跳转页里能看到每个应用的包名）"
                            !a.hasTrigger && !useManual -> "触发没配好：间隔 / 每日时刻 / 到地点 / 事件 四选一，或者选「手动」"
                            else -> null
                        }
                        if (saveErr == null) onSave(a)
                    },
                    modifier = Modifier.weight(1f)
                ) { Text("保存", maxLines = 1, softWrap = false) }
            }
        }
    )

    // v26：从注册表里挑一个动作追加到步骤尾部（说明一行 = 注册表里的 desc）
    if (showAddStep) {
        AlertDialog(
            modifier = Modifier.motionEnter(),
            onDismissRequest = { showAddStep = false },
            title = { Text("添加步骤") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    ActionRegistry.defs.forEach { d ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .clickable {
                                    steps = steps + AutomationStep(d.key)
                                    showAddStep = false
                                }
                                .padding(vertical = 10.dp, horizontal = 4.dp)
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(d.label, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                                Text(d.desc, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showAddStep = false }) { Text("取消") }
            }
        )
    }

    // 试运行结果：summary 是 ✅/❌ 一行总括，log 是逐步明细
    val t = trial
    if (t != null) {
        AlertDialog(
            modifier = Modifier.motionEnter(),
            onDismissRequest = { trial = null },
            title = { Text("试运行结果") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(
                        t.summary,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (t.ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                    )
                    if (t.log.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        t.log.forEach {
                            Text("· " + it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { trial = null }) { Text("关闭") }
            }
        )
    }
}

/**
 * v26：步骤流水线编辑区（在编辑器的 AlertDialog 里内联渲染）。
 *
 * 每步一张卡：序号 + 动作名 + 上移/下移/删除 + 按注册表 [io.github.m0use001.planability.core.ParamDef] 生成的参数输入框。
 * 参数输入框是**受控**的（value 来自 steps），增删步骤时文本不会串位。
 */
@Composable
private fun FlowStepsSection(
    steps: List<AutomationStep>,
    onChange: (List<AutomationStep>) -> Unit,
    onAdd: () -> Unit,
    onTrial: () -> Unit
) {
    Text("步骤", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
    if (steps.isEmpty()) {
        Text(
            "还没有步骤 —— 点下方「＋ 添加步骤」；至少一步才能保存。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.error
        )
    }
    steps.forEachIndexed { i, s ->
        val def = ActionRegistry.byKey(s.type)
        Column(
            Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .padding(10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${i + 1}. ${def?.label ?: s.type}",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                if (def != null && steps.size > 1) {
                    IconButton(
                        onClick = {
                            onChange(steps.toMutableList().also { l -> val x = l[i]; l[i] = l[i - 1]; l[i - 1] = x })
                        },
                        enabled = i > 0,
                        modifier = Modifier.size(36.dp)
                    // R11：重排箭头是辅助动作 → 中性；禁用态同色透明度
                    ) { Icon(AppIcons.Nav.Up, "上移", modifier = Modifier.size(18.dp), tint = if (i > 0) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)) }
                    IconButton(
                        onClick = {
                            onChange(steps.toMutableList().also { l -> val x = l[i]; l[i] = l[i + 1]; l[i + 1] = x })
                        },
                        enabled = i < steps.lastIndex,
                        modifier = Modifier.size(36.dp)
                    ) { Icon(AppIcons.Nav.Down, "下移", modifier = Modifier.size(18.dp), tint = if (i < steps.lastIndex) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)) }
                }
                IconButton(
                    onClick = { onChange(steps.filterIndexed { j, _ -> j != i }) },
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(AppIcons.Act.Delete, "删除这一步", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                }
            }
            when {
                def == null -> Text(
                    "未知动作类型：${s.type}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error
                )
                // editable=false（如回收站）：参数固定，不渲染输入框，只把说明摆出来
                !def.editable -> Text(def.desc, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                else -> def.params.forEach { pd ->
                    OutlinedTextField(
                        value = s.params[pd.key] ?: "",
                        onValueChange = { v ->
                            onChange(steps.toMutableList().also { l -> l[i] = s.copy(params = s.params + (pd.key to v)) })
                        },
                        label = { Text(pd.label) },
                        placeholder = if (pd.hint.isEmpty()) null else ({ Text(pd.hint) }),
                        singleLine = pd.key != "prompt",
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
        Spacer(Modifier.height(6.dp))
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = onAdd) { Text("＋ 添加步骤") }
        Spacer(Modifier.weight(1f))
        TextButton(onClick = onTrial, enabled = steps.isNotEmpty()) { Text("试运行") }
    }
}

// ==================== MCP ====================

@Composable
private fun McpTab() {
    val scope = rememberCoroutineScope()
    val haptics = LocalHaptics.current
    var list by remember { mutableStateOf<List<McpServer>>(emptyList()) }
    var showAdd by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { list = McpRepo.all() }

    androidx.compose.foundation.layout.Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
            Text(
                "MCP 服务器（Streamable HTTP）：启用后其工具自动注入 AI 对话，调用走确认卡",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 8.dp)
            )
            val mcpState = androidx.compose.foundation.lazy.rememberLazyListState()
            LazyColumn(
                Modifier.fillMaxSize().reachedEndHaptic(mcpState),
                state = mcpState
            ) {
                if (list.isEmpty()) item { io.github.m0use001.planability.ui.common.EmptyHint("还没有 MCP 服务器\n点右下角 + 添加") }
                items(list, key = { it.id }) { s ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.animateItem()
                            .fillMaxWidth()
                            .padding(vertical = 5.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainer)
                            .padding(12.dp)
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(s.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                            Text(
                                s.url, style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1
                            )
                        }
                        Switch(checked = s.enabled, onCheckedChange = { on ->
                            // 开关 = 选择 → Select（与自动化列表里的开关同一手感）
                            haptics.perform(HapticEngine.Kind.Select)
                            scope.launch { McpRepo.save(s.copy(enabled = on)) }
                        })
                        TextButton(onClick = {
                            haptics.perform(HapticEngine.Kind.Select)
                            scope.launch {
                                val n = runCatching { McpClient.listTools(s).size }.getOrDefault(-1)
                                android.widget.Toast.makeText(
                                    Graph.appContext,
                                    if (n >= 0) "连接成功，$n 个工具" else "连接失败",
                                    android.widget.Toast.LENGTH_SHORT
                                ).show()
                            }
                        }) { Text("测试") }
                        IconButton(onClick = {
                            haptics.perform(HapticEngine.Kind.Error)
                            scope.launch { McpRepo.delete(s.id) }
                        }) {
                            Icon(AppIcons.Act.Delete, null, tint = MaterialTheme.colorScheme.error)
                        }
                    }
                }
                item { Spacer(Modifier.height(90.dp)) }
            }
        }
        MediumFab(
            onClick = { haptics.perform(HapticEngine.Kind.Tap); showAdd = true },
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 20.dp)
        ) { Icon(AppIcons.Act.Add, contentDescription = "添加 MCP") }
    }

    if (showAdd) {
        var name by remember { mutableStateOf("") }
        var url by remember { mutableStateOf("") }
        var headers by remember { mutableStateOf("") }
        AlertDialog(
            modifier = Modifier.motionEnter(),
            onDismissRequest = { showAdd = false },
            title = { Text("添加 MCP 服务器") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("名称") }, singleLine = true)
                    OutlinedTextField(value = url, onValueChange = { url = it }, label = { Text("端点 URL") }, singleLine = true)
                    OutlinedTextField(value = headers, onValueChange = { headers = it }, label = { Text("请求头 JSON（可选）") }, minLines = 2)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (name.isNotBlank() && url.isNotBlank()) {
                        scope.launch {
                            McpRepo.save(McpServer(name = name.trim(), url = url.trim(), headersJson = headers.trim()))
                            list = McpRepo.all()
                        }
                        showAdd = false
                    }
                }) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { showAdd = false }) { Text("取消") } }
        )
    }
}

// ==================== 导入 ====================

@Composable
private fun ImportTab() {
    val scope = rememberCoroutineScope()
    val haptics = LocalHaptics.current
    var skillText by remember { mutableStateOf("") }
    var mcpJson by remember { mutableStateOf("") }
    var msg by remember { mutableStateOf("") }
    // v24：导入他平台分享的对话
    var shareUrl by remember { mutableStateOf("") }
    var shareBusy by remember { mutableStateOf(false) }
    var shareMsg by remember { mutableStateOf("") }
    var shareErr by remember { mutableStateOf("") }
    var shareOk by remember { mutableStateOf<io.github.m0use001.planability.data.ShareImportService.Outcome.Ok?>(null) }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
    ) {
        // ---------- v24：分享链接导入对话 ----------
        Text("导入分享的对话", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Text(
            "粘贴 ChatGPT / Claude / DeepSeek 等平台的分享链接，把对话原文导入成本地会话",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        OutlinedTextField(
            value = shareUrl, onValueChange = { shareUrl = it; shareErr = ""; shareOk = null },
            label = { Text("分享链接") },
            placeholder = { Text("https://…/share/…") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)
        )
        // v24：imePadding —— 键盘弹起时否则按钮被顶到键盘后面看不见（IME 弹出
        // 会把整页可视区缩小，不避让就点不到「导入对话」）
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.imePadding()
        ) {
            TextButton(
                enabled = !shareBusy && shareUrl.isNotBlank(),
                onClick = {
                    shareBusy = true; shareErr = ""; shareOk = null; shareMsg = ""
                    scope.launch {
                        val r = io.github.m0use001.planability.data.ShareImportService.import(shareUrl)
                        shareBusy = false
                        when (r) {
                            is io.github.m0use001.planability.data.ShareImportService.Outcome.Ok -> {
                                haptics.perform(HapticEngine.Kind.Success)
                                shareOk = r
                                shareMsg = "已导入「${r.title}」共 ${r.count} 条消息"
                            }
                            is io.github.m0use001.planability.data.ShareImportService.Outcome.Failed -> {
                                haptics.perform(HapticEngine.Kind.Error)
                                shareErr = r.reason
                            }
                        }
                    }
                }
            ) { Text(if (shareBusy) "导入中…" else "导入对话", maxLines = 1, softWrap = false) }
            if (shareBusy) {
                Spacer(Modifier.width(10.dp))
                // v45 M3E：分享链接导入是 ≤5s 级活跃等待 → LoadingIndicator
                androidx.compose.material3.LoadingIndicator(Modifier.size(16.dp))
            }
            Spacer(Modifier.weight(1f))
            shareOk?.let { ok ->
                TextButton(onClick = {
                    scope.launch { io.github.m0use001.planability.app.Router.openSession(ok.sessionId) }
                }) { Text("打开", maxLines = 1, softWrap = false) }
            }
        }
        if (shareErr.isNotBlank()) {
            Text(shareErr, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
        }
        shareOk?.let { r ->
            r.note?.let { n ->
                Text(n, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary)
            }
        }
        // 已知限制照实说，不让用户以为"我粘贴格式没错怎么失败"
        if (shareErr.isBlank() && shareOk == null) {
            Text(
                "注意：ChatGPT 在中国大陆通常无法直连；Claude/DeepSeek 的分享页多需登录，" +
                    "未开启「公开可见」时抓不到内容。失败时会把原因写在这里。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(Modifier.height(16.dp))
        Text("导入技能（SKILL.md）", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Text(
            "粘贴 Claude/Cursor 风格的 SKILL.md（frontmatter 的 name/description 可选）",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        OutlinedTextField(
            value = skillText, onValueChange = { skillText = it },
            label = { Text("SKILL.md 内容") }, minLines = 4,
            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)
        )
        TextButton(onClick = {
            if (skillText.isNotBlank()) {
                scope.launch {
                    val parsed = parseSkillMd(skillText)
                    Graph.memories.upsert(
                        MemoryEntity(
                            name = parsed.first, type = "skill",
                            description = parsed.second.take(120),
                            body = skillText.trim().take(2000),
                            inIndex = false, importance = 2, originSession = 0
                        )
                    )
                    haptics.perform(HapticEngine.Kind.Success)
                    msg = "已导入技能「" + parsed.first + "」"
                }
            }
        }) { Text("导入为技能") }

        Spacer(Modifier.height(14.dp))
        Text("导入 MCP 配置（JSON）", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Text(
            "常见平台格式：{\"mcpServers\":{\"名称\":{\"url\":\"…\"}}}（http/sse 端点）",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        OutlinedTextField(
            value = mcpJson, onValueChange = { mcpJson = it },
            label = { Text("MCP 配置 JSON") }, minLines = 3,
            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)
        )
        TextButton(onClick = {
            scope.launch {
                val n = importMcpJson(mcpJson)
                haptics.perform(if (n > 0) HapticEngine.Kind.Success else HapticEngine.Kind.Error)
                msg = if (n > 0) "已导入 $n 个 MCP 服务器" else "未解析到可导入的服务器"
            }
        }) { Text("导入 MCP 服务器") }

        Spacer(Modifier.height(14.dp))
        Text("插件能力包", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        var pluginState by remember { mutableStateOf<Set<String>?>(null) }
        LaunchedEffect(msg) { pluginState = io.github.m0use001.planability.ai.ptl.ToolRegistry.enabledPlugins() }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 6.dp)) {
            listOf("office", "web", "ics").forEach { p ->
                FilterChip(
                    colors = filterChipColors(),
                    selected = pluginState?.contains(p) ?: true,
                    onClick = {
                        haptics.perform(HapticEngine.Kind.Select)
                        scope.launch {
                            val cur = pluginState ?: listOf("office", "web", "ics").toSet()
                            val next = if (p in cur) cur - p else cur + p
                            val allSet = listOf("office", "web", "ics").toSet()
                            Graph.db.settingsDao().put(
                                io.github.m0use001.planability.data.SettingKV("plugin.enabled", if (next == allSet) "" else next.joinToString(","))
                            )
                            pluginState = next
                        }
                    },
                    label = { Text(p) }
                )
            }
        }

        // v44：本机快照（Download/Planability）——加密状态、急救明文开关、解密出可恢复副本
        LocalBackupSection()

        if (msg.isNotBlank()) {
            Spacer(Modifier.height(10.dp))
            Text(msg, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        }
        Spacer(Modifier.height(80.dp))
    }
}

/** SKILL.md → (name, description)：frontmatter 优先，回退首行标题 */
internal fun parseSkillMd(text: String): Pair<String, String> {
    val fmName = Regex("""^name:\s*(.+)$""", RegexOption.MULTILINE).find(text)?.groupValues?.get(1)?.trim()
    val fmDesc = Regex("""^description:\s*(.+)$""", RegexOption.MULTILINE).find(text)?.groupValues?.get(1)?.trim()
    val h1 = text.lineSequence().firstOrNull { it.startsWith("# ") }?.removePrefix("# ")?.trim()
    val name = (fmName ?: h1 ?: "skill-" + (System.currentTimeMillis() / 1000 % 100000))
        .take(40)
        .replace(Regex("\\s+"), "-")
        .lowercase()
    return name to (fmDesc ?: "")
}

/** mcpServers JSON → 入库数量 */
internal suspend fun importMcpJson(json: String): Int {
    val root = runCatching { org.json.JSONObject(json) }.getOrNull() ?: return 0
    val servers = root.optJSONObject("mcpServers") ?: root
    var n = 0
    servers.keys().forEach { name ->
        val o = servers.optJSONObject(name) ?: return@forEach
        val url = o.optString("url").ifBlank { o.optString("serverUrl") }
        if (url.isNotBlank()) {
            val headers = o.optJSONObject("headers")?.toString() ?: ""
            McpRepo.save(McpServer(name = name.take(40), url = url, headersJson = headers))
            n++
        }
    }
    return n
}

/** v64 宽屏右板：选中任务的详情板——运行 / 日志 / 编辑都在板上。 */
@Composable
private fun AutomationDetailPane(
    a: Automation,
    onRun: () -> Unit,
    onLog: () -> Unit,
    onEdit: () -> Unit
) {
    Column(Modifier.fillMaxSize()) {
        io.github.m0use001.planability.ui.common.BoardHeader(
            title = a.name,
            subtitle = if (a.hasTrigger) "自动化" else "快捷指令",
            leading = {
                IconButton(onClick = onEdit) {
                    Icon(AppIcons.Nav.Back, contentDescription = "返回")
                }
            },
            actions = {
                IconButton(onClick = onEdit) {
                    Icon(AppIcons.Act.Edit, "编辑", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        )
        io.github.m0use001.planability.ui.common.BoardDivider()
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = io.github.m0use001.planability.ui.common.BoardLayout.Padding, vertical = 12.dp)
        ) {
            val trigger = when {
                a.intervalMinutes > 0 -> "每 " + a.intervalMinutes + " 分钟"
                a.dailyHour >= 0 -> "每日 %02d:%02d".format(a.dailyHour, a.dailyMinute)
                a.geoTrigger == "arrive" -> "到达 %.4f,%.4f %d 米内".format(a.geoLat, a.geoLng, a.geoRadiusM)
                a.geoTrigger == "leave" -> "离开 %.4f,%.4f %d 米".format(a.geoLat, a.geoLng, a.geoRadiusM)
                a.hasEventTrigger -> io.github.m0use001.planability.notify.AutomationSignal.describe(a.triggerKind, a.triggerArg)
                else -> "手动"
            }
            Text("触发方式", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text(trigger, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(14.dp))
            Text("执行方式", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text(
                if (a.kind == "flow") "步骤流水线（" + AutomationStep.decodeList(a.stepsJson).size + " 步）" else "AI 指令",
                style = MaterialTheme.typography.bodyMedium
            )
            if (a.kind == "ai" && a.prompt.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    a.prompt,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onRun) { Text("▶ 运行") }
                OutlinedButton(onClick = onLog) { Text("执行日志") }
                OutlinedButton(onClick = onEdit) { Text("编辑") }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** v64 宽屏右板（未选中）：这一栏不许空着。 */
@Composable
private fun AutomationOverviewPane(auto: Int, manual: Int) {
    Column(Modifier.fillMaxSize()) {
        io.github.m0use001.planability.ui.common.BoardHeader(
            title = "自动化与扩展",
            subtitle = "点左侧任务在此查看"
        )
        io.github.m0use001.planability.ui.common.BoardDivider()
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = io.github.m0use001.planability.ui.common.BoardLayout.Padding, vertical = 12.dp)
        ) {
            // v72（2026-11-21 用户「应该加入圆角矩形来区分」）：裸统计行 → 数值格，说明段装色调块
            io.github.m0use001.planability.ui.common.PaneStatRow(
                listOf("$auto" to "自动化", "$manual" to "快捷指令")
            )
            Spacer(Modifier.height(10.dp))
            io.github.m0use001.planability.ui.common.PaneContentBlock {
                Text(
                    "自动化到点自己跑；快捷指令点 ▶ 才执行。选中一条在这里看触发方式、执行方式，运行或进编辑。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
