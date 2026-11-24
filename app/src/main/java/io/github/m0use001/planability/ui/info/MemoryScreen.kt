package io.github.m0use001.planability.ui.info

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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.FolderOff
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.AlertDialog
import io.github.m0use001.planability.ui.common.motionEnter
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.m0use001.planability.app.PredictiveBackOverlay
import io.github.m0use001.planability.core.HapticEngine
import io.github.m0use001.planability.core.LocalHaptics
import io.github.m0use001.planability.core.formatMonthDay
import io.github.m0use001.planability.core.reachedEndHaptic
import io.github.m0use001.planability.core.toLocalDate
import io.github.m0use001.planability.data.Graph
import io.github.m0use001.planability.data.MemoryEntity
import io.github.m0use001.planability.data.Project
import io.github.m0use001.planability.ui.common.EmptyHint
import io.github.m0use001.planability.ui.common.SelectDropdownChip
import kotlinx.coroutines.launch
import io.github.m0use001.planability.ui.common.filterChipColors

private val MEMORY_TYPES = listOf("project", "feedback", "reference", "skill")

/** v8 记忆库类型筛选（null = 全部） */
private val MEMORY_FILTERS: List<Pair<String?, String>> = listOf(
    null to "全部", "project" to "项目", "feedback" to "反馈",
    "reference" to "参考", "skill" to "技能", "handoff" to "交接"
)

private fun typeLabel(t: String) = when (t) {
    "project" -> "项目"
    "feedback" -> "反馈"
    "reference" -> "参考"
    "skill" -> "技能"
    "handoff" -> "交接"
    else -> t
}

/** 记忆库管理页（v8）：全局记忆置顶 + 项目文件夹树（文件管理器形态）+ 类型筛选；搜索跨组平铺 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MemoryScreen(
    onDone: () -> Unit,
    initialType: String? = null,
    /** 合并屏（ui/info/ToolsHub.kt）的顶部切换器；null=本屏自己顶栏 */
    topSwitcher: (@Composable () -> Unit)? = null,
    /** 合并屏已在外层套 PredictiveBackOverlay → 本屏只画内容（防双 Dialog 闪帧） */
    insideOverlay: Boolean = false,
    /** 合并屏只借本页内容时隐藏本屏顶栏（切换器已说明当前页，避免标题重复） */
    hideTopBar: Boolean = false,
    /** v77：板内钻入瓦片（工具与扩展 Hub 的「命令」tab）——透明底 + 0 inset + 强制单板 */
    embedded: Boolean = false
) {
    val haptics = LocalHaptics.current
    var all by remember { mutableStateOf<List<MemoryEntity>>(emptyList()) }
    var projects by remember { mutableStateOf<List<Project>>(emptyList()) }
    var query by remember { mutableStateOf("") }
    var typeFilter by remember { mutableStateOf<String?>(initialType) }
    var expandedProjects by rememberSaveable { mutableStateOf(listOf<Long>()) }
    var editing by remember { mutableStateOf<MemoryEntity?>(null) }
    var creating by remember { mutableStateOf(false) }
    // 交接记忆（handoff）是否被用户关掉（2026-10-21：用户反馈"有无法删除的记忆"）
    var handoffOff by remember { mutableStateOf(false) }
    val version by Graph.dataVersion.flow.collectAsState()
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current

    LaunchedEffect(version) {
        all = Graph.memories.all()
        projects = Graph.projects.all()
        handoffOff = Graph.settings.raw("memory.handoff.off") == "1"
    }
    LaunchedEffect(query, version) {
        all = if (query.isBlank()) Graph.memories.all() else Graph.memories.searchByText(query)
    }
    // v8 类型筛选（与搜索叠加）
    val filtered = all.filter { m -> typeFilter == null || m.type == typeFilter }

    fun toggleProject(id: Long) {
        expandedProjects = if (id in expandedProjects) expandedProjects - id else expandedProjects + id
    }

    // v36 B 项：整理状态（待整理轮数 / 正在整理 / 上次结果），供顶部按钮与状态行用
    val memState by io.github.m0use001.planability.ai.MemoryConsolidationScheduler.status.collectAsState()

    // v50.5：抽成 shell lambda——合并屏（ToolsHub）传 insideOverlay=true 时只画内容，
    // PredictiveBackOverlay 由合并屏在最外层只套一层（防双 Dialog 闪帧，同 SettingsHub 注释）。
    val shell: @Composable () -> Unit = {
        Scaffold(
            containerColor = if (embedded) androidx.compose.ui.graphics.Color.Transparent
            else MaterialTheme.colorScheme.background,
            contentWindowInsets = if (embedded)
                androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0)
            else androidx.compose.material3.ScaffoldDefaults.contentWindowInsets,
            topBar = {
                if (hideTopBar) {
                    // 合并屏借用（ToolsHub「命令设置」tab）：切换器已标明当前页，
                    // 这里只保留切换器，不再画一遍标题与返回键（返回键由合并屏外层负责）。
                    if (topSwitcher != null) {
                        Column(Modifier.fillMaxWidth().then(if (embedded) Modifier else Modifier.statusBarsPadding())) { topSwitcher() }
                    }
                } else Column {
                    // v53.3：顶栏 + 切换器同列——「资料」Hub 借用本页时既要保留
                    // 「立即整理 / + 新增」这些页内动作，也要显示 记忆库/文件库/归档 的切换器。
                    // 所以不能走 hideTopBar（那会把动作一起藏掉）。
                    TopAppBar(
                    // v50.4（用户「命令设置怎么跳转记忆库了」）：本页是记忆库的**复用**实现——
                    // 「命令设置」与 `/skill` 命令预设 typeFilter="skill" 后打开同一屏，
                    // 但标题一直写死「记忆库」，用户看到的就是"点命令设置却进了记忆库"。
                    // 入口语义由 initialType 决定：技能预设 → 标题「命令设置」+ 副标题说明；
                    // 其余（含 null=记忆库本入口）保持原样。
                    title = {
                        val asCommands = initialType == "skill"
                        Column {
                            Text(
                                if (asCommands) "命令设置" else "记忆库",
                                fontWeight = FontWeight.SemiBold
                            )
                            if (asCommands) {
                                Text(
                                    "斜杠命令与技能，存在记忆库里（技能类）",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    },
                    navigationIcon = {
                        // 返回 = 中性轻触（Tap），见 core/Haptics.kt 的 HIG 映射表
                        IconButton(onClick = { haptics.perform(HapticEngine.Kind.Tap); onDone() }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "返回") }
                    },
                    actions = {
                        // v36 B 项：手动「立即整理」。与自动整理的区别只是允许删 + 不看失败标记，
                        // 语义见 MemoryConsolidationScheduler.organizeNow()
                        val memState by io.github.m0use001.planability.ai.MemoryConsolidationScheduler.status
                            .collectAsState()
                        TextButton(
                            onClick = {
                                haptics.perform(HapticEngine.Kind.Tap)
                                io.github.m0use001.planability.ai.MemoryConsolidationScheduler.organizeNow()
                            },
                            enabled = !memState.running
                        ) {
                            // 有东西可整理才叫"立即整理"；什么都没有时按钮说清楚，别让用户反复点
                            Text(
                                when {
                                    memState.running -> "整理中…"
                                    memState.pendingTurns > 0 -> "整理 ${memState.pendingTurns} 轮"
                                    else -> "立即整理"
                                }
                            )
                        }
                        IconButton(onClick = { creating = true }) { Icon(Icons.Rounded.Add, "新增") }
                    }
                    )
                    if (topSwitcher != null) {
                        Column(Modifier.fillMaxWidth()) { topSwitcher() }
                    }
                }
            }
        ) { pad ->
            // v64（2026-11-20）：宽屏主从两板——左板=筛选与列表，右板=选中条目的就地编辑
            // （未选中=概览，不许空）。模态编辑框只留给窄屏。
            androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
            val contentW = maxWidth
            val detailW = io.github.m0use001.planability.ui.common.MasterDetail.detailWidthFor(contentW, content = false)
            val memoPane = io.github.m0use001.planability.ui.common.MasterDetail
                .paneOf(contentW, detailW, hasSelection = creating || editing != null)
            val twoPane = !embedded && memoPane != io.github.m0use001.planability.ui.common.DetailPane.Single
            // 右板在编辑时，系统返回先收右板，而不是退出记忆库
            androidx.activity.compose.BackHandler(enabled = twoPane && (creating || editing != null)) {
                creating = false; editing = null
            }
            io.github.m0use001.planability.ui.common.SplitBoards(
                twoPane = twoPane,
                // pad 必须垫在**壳**上：只垫左板内容的话，右板会顶到屏幕最上沿
                modifier = Modifier.padding(pad),
                // 页面在 Scaffold 里（pad 已含系统栏 inset），不要再叠一层
                applyTopInset = false,
                applyBottomInset = false,
                primary = {
            // v77 S1b（记忆库拆级）：列表正文抽成 [MemoryListBody]——与板内钻入的
            // [MemorySubBoard] 瓦片共用同一份（抄两遍的列表迟早漏一处）。
            MemoryListBody(
                all = all,
                projects = projects,
                filtered = filtered,
                query = query,
                onQueryChange = { query = it },
                typeFilter = typeFilter,
                onTypeFilterChange = { typeFilter = it },
                expandedProjects = expandedProjects,
                onToggleProject = { id -> toggleProject(id) },
                handoffOff = handoffOff,
                onHandoffOffChange = { off ->
                    handoffOff = off
                    scope.launch {
                        Graph.settings.putRawValue("memory.handoff.off", if (off) "1" else "0")
                    }
                },
                memState = memState,
                onEdit = { m -> editing = m }
            )
                },
                secondary = {
                    when {
                        creating || editing != null -> MemoryDetailBoard(
                            initial = editing,
                            projects = projects,
                            onBack = { creating = false; editing = null }
                        )
                        else -> MemoryOverviewPane(all = all, projects = projects, onCreate = { creating = true })
                    }
                }
            )
            if (!twoPane && (creating || editing != null)) {
                MemoryEditDialog(
                    initial = editing,
                    projects = projects,
                    onDismiss = { creating = false; editing = null },
                    onSave = { m ->
                        scope.launch { Graph.memories.upsert(m) }
                        creating = false; editing = null
                    },
                    onDelete = editing?.let { m ->
                        {
                            scope.launch {
                                Graph.memories.softDelete(m.id)
                                // 删的是交接记忆：顺手关掉自动写入，否则下一轮又被写回来，
                                // 用户看到的就是"这条删不掉"（2026-10-21）。
                                if (m.type == "handoff") {
                                    handoffOff = true
                                    Graph.settings.putRawValue("memory.handoff.off", "1")
                                    android.widget.Toast.makeText(
                                        context, "已删除，交接记忆不再自动写入（可在此页重新打开）",
                                        android.widget.Toast.LENGTH_SHORT
                                    ).show()
                                }
                            }
                            editing = null
                        }
                    }
                )
            }
            }
        }
    }

    // v64：模态编辑框挪进了 shell 的 Scaffold 内容里（跟 twoPane 一起判）——
    // 宽屏右板就地编辑，窄屏才弹框。
    if (insideOverlay) shell() else PredictiveBackOverlay(onBack = onDone) { shell() }
}

/** v8 项目根文件夹行（文件管理器形态）：点击展开/收起该项目记忆 */
@Composable
private fun ProjectFolderRow(project: Project, count: Int, expanded: Boolean, onToggle: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onToggle)
            .padding(horizontal = 6.dp, vertical = 8.dp)
    ) {
        Icon(
            if (expanded) Icons.Rounded.FolderOpen else io.github.m0use001.planability.data.WorkbenchIcons.project(project.iconKey),
            null, Modifier.size(17.dp),
            // R11：项目行首图标 → 中性（行标题不是彩色）
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.width(8.dp))
        Text(
            project.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Text(
            count.toString() + " 条",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.width(6.dp))
        Icon(
            Icons.Rounded.KeyboardArrowDown,
            null,
            Modifier
                .size(16.dp)
                .graphicsLayer { rotationZ = if (expanded) 180f else 0f },
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun SectionHeaderRow(icon: @Composable () -> Unit, text: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(start = 4.dp, top = 10.dp, bottom = 4.dp)
    ) {
        icon()
        Spacer(Modifier.width(6.dp))
        Text(
            text, style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun MemoryCard(m: MemoryEntity, project: Project?, onEdit: () -> Unit) {
    // 2026-11-20：改走 Card(onClick=) 重载。原来把 .clickable 挂在 Card 的 modifier 上，
    // 水波画在卡片 shape 裁剪的**外面**，四个圆角处戳出直角方块（用户报的「突兀的正方形」）。
    // Card 的可点重载会把 indication 画进自己的 shape 里。
    Card(
        onClick = onEdit,
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    typeLabel(m.type),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                )
                Spacer(Modifier.width(6.dp))
                if (m.inIndex) {
                    Icon(
                        Icons.Rounded.PushPin, "工作层",
                        Modifier.size(13.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.width(4.dp))
                }
                Text(
                    m.name, style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    // 2026-11-17 用户「记忆名称太长会把同一行其他东西挤到一边」：
                    // 名称原来没有权重，按内容固有宽铺开 → 后面的日期被顶出卡片。
                    // 给名称 weight(1f)（占满剩余宽、超长省略号），日期钉在右端。
                    modifier = Modifier.weight(1f)
                )
                Text(
                    m.updatedAt.toLocalDate().formatMonthDay(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (m.description.isNotBlank()) {
                Text(
                    m.description, style = MaterialTheme.typography.bodySmall,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            }
            if (m.body.isNotBlank() && m.body != m.description) {
                Text(
                    m.body, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2, overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/**
 * v64（2026-11-20）：表单状态与字段本体抽出来——**模态框与宽屏右板共用同一份实现**，
 * 不再各写一遍（抄两遍的表单迟早漏一处）。
 */
private class MemoryFormState(initial: MemoryEntity?) {
    var name by mutableStateOf(initial?.name ?: "")
    var type by mutableStateOf(initial?.type ?: "project")
    var description by mutableStateOf(initial?.description ?: "")
    var body by mutableStateOf(initial?.body ?: "")
    var inIndex by mutableStateOf(initial?.inIndex ?: true)
    var projectId by mutableStateOf(initial?.projectId ?: 0L)
    var projectMenu by mutableStateOf(false)
    val canSave: Boolean get() = name.isNotBlank()

    fun toEntity(initial: MemoryEntity?): MemoryEntity = MemoryEntity(
        id = initial?.id ?: 0,
        name = name.trim().take(40),
        type = type,
        description = description.trim().take(120),
        body = body.trim().take(2000),
        inIndex = inIndex,
        importance = initial?.importance ?: 2,
        originSession = initial?.originSession ?: 0,
        createdAt = initial?.createdAt ?: 0,
        updatedAt = initial?.updatedAt ?: 0,
        projectId = projectId
    )
}

@Composable
private fun MemoryFormBody(st: MemoryFormState, projects: List<Project>) {
    val haptics = LocalHaptics.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            MEMORY_TYPES.forEach { t ->
                FilterChip(
                    colors = filterChipColors(),
                    selected = st.type == t,
                    // 记忆类型三选一 = 选择（HIG selection → 最轻的 Select）
                    onClick = { st.type = t; haptics.perform(HapticEngine.Kind.Select) },
                    label = { Text(typeLabel(t)) }
                )
            }
        }
        OutlinedTextField(
            value = st.name, onValueChange = { st.name = it },
            label = { Text("唯一名（英文短词，如 prefer-quiet）") }, singleLine = true
        )
        OutlinedTextField(
            value = st.description, onValueChange = { st.description = it },
            label = { Text("一句话描述") }, singleLine = true
        )
        OutlinedTextField(
            value = st.body, onValueChange = { st.body = it },
            label = { Text("正文（支持 [[wikilink]] 关联其他记忆）") }, minLines = 3
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("工作层常驻注入", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            Switch(checked = st.inIndex, onCheckedChange = {
                haptics.perform(HapticEngine.Kind.Select)
                st.inIndex = it
            })
        }
        // 所属项目（全局 = 不属于任何项目）
        Box {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainer)
                    .clickable { st.projectMenu = !st.projectMenu }
                    .padding(horizontal = 10.dp, vertical = 8.dp)
            ) {
                Icon(
                    if (st.projectId == 0L) Icons.Rounded.Public
                    else io.github.m0use001.planability.data.WorkbenchIcons.project(
                        projects.firstOrNull { it.id == st.projectId }?.iconKey ?: "folder"
                    ),
                    null, Modifier.size(15.dp),
                    // R11：归属选择器的类型图标 → 中性
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    if (st.projectId == 0L) "所属：全局记忆"
                    else "所属：" + (projects.firstOrNull { it.id == st.projectId }?.name ?: "未分类"),
                    style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f)
                )
                Icon(
                    Icons.Rounded.KeyboardArrowDown, null,
                    Modifier.size(15.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            DropdownMenu(expanded = st.projectMenu, onDismissRequest = { st.projectMenu = false }) {
                DropdownMenuItem(
                    modifier = Modifier.clip(RoundedCornerShape(10.dp)), // 2026-11-15 点击高亮圆角化（原来是整行直角方块）
                    text = { Text("全局记忆（不属于任何项目）") },
                    onClick = { st.projectId = 0L; st.projectMenu = false }
                )
                projects.forEach { p ->
                    DropdownMenuItem(
                        modifier = Modifier.clip(RoundedCornerShape(10.dp)), // 2026-11-15 点击高亮圆角化（原来是整行直角方块）
                        text = { Text(p.name) },
                        onClick = { st.projectId = p.id; st.projectMenu = false }
                    )
                }
            }
        }
    }
}

@Composable
private fun MemoryEditDialog(
    initial: MemoryEntity?,
    projects: List<Project>,
    onDismiss: () -> Unit,
    onSave: (MemoryEntity) -> Unit,
    onDelete: (() -> Unit)?
) {
    val st = remember(initial) { MemoryFormState(initial) }
    AlertDialog(
        modifier = Modifier.motionEnter(),
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "新增记忆" else "编辑记忆") },
        text = { MemoryFormBody(st, projects) },
        confirmButton = {
            TextButton(
                onClick = { if (st.canSave) onSave(st.toEntity(initial)) }
            ) { Text("保存") }
        },
        dismissButton = {
            Row {
                // 2026-10-21：交接记忆原来被排除在删除之外（type != "handoff"），
                // 用户看到的只有"这条删不掉"。现在一律可删；删交接记忆会同时关掉自动写入。
                if (onDelete != null) {
                    TextButton(onClick = onDelete) { Text("删除", color = MaterialTheme.colorScheme.error) }
                }
                TextButton(onClick = onDismiss) { Text("取消") }
            }
        }
    )
}

/**
 * v64 宽屏右板：选中一条记忆时的**就地编辑**（模态框只留给窄屏）。
 * 保存/删除长在板头——与计划页「编辑器板头」同一套语言。
 */
/**
 * v77 S1b：记忆条目的**表单一级**（板内钻入的细节瓦片；也供整屏宽屏右板复用）。
 *
 * 保存/删除在瓦片内自己落库（upsert / softDelete；删交接记忆顺手关自动写入并 Toast），
 * 完成后回调 [onBack]。表单本体与模态框共用 [MemoryFormBody]（v64 决策不复制第二遍）。
 */
@Composable
fun MemoryDetailBoard(
    initial: MemoryEntity?,
    projects: List<Project>,
    onBack: () -> Unit
) {
    val st = remember(initial) { MemoryFormState(initial) }
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    Column(Modifier.fillMaxSize()) {
        io.github.m0use001.planability.ui.common.BoardHeader(
            title = if (initial == null) "新增记忆" else "编辑记忆",
            leading = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "返回")
                }
            },
            actions = {
                if (initial != null) {
                    TextButton(onClick = {
                        scope.launch {
                            Graph.memories.softDelete(initial.id)
                            // 删的是交接记忆：顺手关掉自动写入，否则下一轮又被写回来
                            if (initial.type == "handoff") {
                                Graph.settings.putRawValue("memory.handoff.off", "1")
                                android.widget.Toast.makeText(
                                    context, "已删除，交接记忆不再自动写入（可在此页重新打开）",
                                    android.widget.Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                        onBack()
                    }) { Text("删除", color = MaterialTheme.colorScheme.error) }
                }
                TextButton(onClick = {
                    if (st.canSave) {
                        scope.launch { Graph.memories.upsert(st.toEntity(initial)) }
                        onBack()
                    }
                }) { Text("保存") }
            }
        )
        io.github.m0use001.planability.ui.common.BoardDivider()
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = io.github.m0use001.planability.ui.common.BoardLayout.Padding, vertical = 12.dp)
        ) {
            MemoryFormBody(st, projects)
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** v64 宽屏右板（未选中）：这一栏不许空着——给出记忆库的规模与分布。 */
@Composable
private fun MemoryOverviewPane(all: List<MemoryEntity>, projects: List<Project>, onCreate: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        io.github.m0use001.planability.ui.common.BoardHeader(
            title = "记忆库",
            subtitle = "点左侧条目在此编辑"
        )
        io.github.m0use001.planability.ui.common.BoardDivider()
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = io.github.m0use001.planability.ui.common.BoardLayout.Padding, vertical = 12.dp)
        ) {
            // v72（2026-11-21 用户「应该加入圆角矩形来区分」）：裸统计行 → 数值格，类型构成装色调块
            io.github.m0use001.planability.ui.common.PaneStatRow(
                listOf("${all.size}" to "条记忆", "${projects.size}" to "项目文件夹")
            )
            Spacer(Modifier.height(14.dp))
            io.github.m0use001.planability.ui.common.PaneSectionBlock("类型构成") {
                MEMORY_TYPES.forEach { t ->
                    val n = all.count { it.type == t }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .clickable(onClick = onCreate)
                            .padding(horizontal = 4.dp, vertical = 6.dp)
                    ) {
                        Text(typeLabel(t), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        Text(
                            "$n 条",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
            TextButton(onClick = onCreate) { Text("＋ 新增记忆") }
        }
    }
}

/**
 * v77 S1b（记忆库拆级）：记忆库的**板内钻入**形态——「我的」宽屏把它作为 NavHost 栈里的一块
 * 透明瓦片（可钻入、Rail 常驻）。本瓦片只画**列表一级**（板头 + 搜索 + 筛选 + 树）；
 * 点记忆卡片 / ＋新增 → 经 [io.github.m0use001.planability.app.LocalNav].push 推入 [MemoryDetailBoard]
 * （表单一级：主板让位、本列表缩副框）。整屏版 [MemoryScreen] 保留给资料 Hub / 信息页等
 * 尚未拆级的入口（S3）。
 */
@Composable
fun MemorySubBoard(
    /** 入口语义：null=记忆库；"skill"=命令设置（技能类记忆） */
    initialType: String?,
    onDone: () -> Unit
) {
    val nav = io.github.m0use001.planability.app.LocalNav.current
    val haptics = LocalHaptics.current
    var all by remember { mutableStateOf<List<MemoryEntity>>(emptyList()) }
    var projects by remember { mutableStateOf<List<Project>>(emptyList()) }
    var query by remember { mutableStateOf("") }
    var typeFilter by remember { mutableStateOf<String?>(initialType) }
    var expandedProjects by rememberSaveable { mutableStateOf(listOf<Long>()) }
    var handoffOff by remember { mutableStateOf(false) }
    val version by Graph.dataVersion.flow.collectAsState()
    val scope = rememberCoroutineScope()
    val memState by io.github.m0use001.planability.ai.MemoryConsolidationScheduler.status.collectAsState()

    LaunchedEffect(version) {
        all = Graph.memories.all()
        projects = Graph.projects.all()
        handoffOff = Graph.settings.raw("memory.handoff.off") == "1"
    }
    LaunchedEffect(query, version) {
        all = if (query.isBlank()) Graph.memories.all() else Graph.memories.searchByText(query)
    }
    val filtered = all.filter { m -> typeFilter == null || m.type == typeFilter }

    fun openDetail(m: MemoryEntity?) {
        nav?.push(
            io.github.m0use001.planability.app.NavPane(if (m == null) "memory-new" else "memory-${m.id}") {
                MemoryDetailBoard(initial = m, projects = projects, onBack = { nav?.pop() })
            }
        )
    }

    io.github.m0use001.planability.app.PredictiveBackOverlay(onBack = onDone, deferBack = true) {
        Column(Modifier.fillMaxSize()) {
            io.github.m0use001.planability.ui.common.BoardHeader(
                title = if (initialType == "skill") "命令设置" else "记忆库",
                subtitle = if (initialType == "skill") "斜杠命令与技能，存在记忆库里（技能类）" else null,
                leading = { io.github.m0use001.planability.ui.common.HapticBackButton(onDone) },
                actions = {
                    TextButton(
                        onClick = {
                            haptics.perform(HapticEngine.Kind.Tap)
                            io.github.m0use001.planability.ai.MemoryConsolidationScheduler.organizeNow()
                        },
                        enabled = !memState.running
                    ) {
                        Text(
                            when {
                                memState.running -> "整理中…"
                                memState.pendingTurns > 0 -> "整理 ${memState.pendingTurns} 轮"
                                else -> "立即整理"
                            }
                        )
                    }
                    IconButton(onClick = { openDetail(null) }) { Icon(Icons.Rounded.Add, "新增") }
                }
            )
            io.github.m0use001.planability.ui.common.BoardDivider()
            MemoryListBody(
                all = all,
                projects = projects,
                filtered = filtered,
                query = query,
                onQueryChange = { query = it },
                typeFilter = typeFilter,
                onTypeFilterChange = { typeFilter = it },
                expandedProjects = expandedProjects,
                onToggleProject = { id ->
                    expandedProjects = if (id in expandedProjects) expandedProjects - id else expandedProjects + id
                },
                handoffOff = handoffOff,
                onHandoffOffChange = { off ->
                    handoffOff = off
                    scope.launch { Graph.settings.putRawValue("memory.handoff.off", if (off) "1" else "0") }
                },
                memState = memState,
                onEdit = { m -> openDetail(m) }
            )
        }
    }
}

/**
 * v77 S1b：记忆库的**列表正文**（状态行 / 搜索 / 类型筛选 / 交接开关 / 树列表）——
 * 整屏 [MemoryScreen] 的 SplitBoards 左板与板内钻入的 [MemorySubBoard] 瓦片共用同一份。
 */
@Composable
private fun MemoryListBody(
    all: List<MemoryEntity>,
    projects: List<Project>,
    filtered: List<MemoryEntity>,
    query: String,
    onQueryChange: (String) -> Unit,
    typeFilter: String?,
    onTypeFilterChange: (String?) -> Unit,
    expandedProjects: List<Long>,
    onToggleProject: (Long) -> Unit,
    handoffOff: Boolean,
    onHandoffOffChange: (Boolean) -> Unit,
    memState: io.github.m0use001.planability.ai.MemoryConsolidationScheduler.Status,
    onEdit: (MemoryEntity) -> Unit
) {
    val haptics = LocalHaptics.current
    Column(Modifier.fillMaxSize()) {
        // v36 B 项：整理状态行。
        if (memState.pendingTurns > 0 || memState.running || memState.lastRunNote.isNotBlank()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = when {
                        memState.running -> memState.lastRunNote.ifBlank { "正在整理…" }
                        memState.pendingTurns > 0 -> "还有 ${memState.pendingTurns} 轮对话待整理"
                        else -> memState.lastRunNote
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                if (memState.pendingTurns > 0 && !memState.running) {
                    Text(
                        text = "空闲 2 分钟或退出应用后自动整理",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
            placeholder = { Text("搜索记忆（名称/描述/正文）") },
            leadingIcon = { Icon(Icons.Rounded.Search, null) },
            singleLine = true,
            shape = RoundedCornerShape(14.dp)
        )
        // v8 类型筛选——2026-11-22 用户「可滑动项不要碰到框/圆角矩形边缘」：板内横滑胶囊行改下拉胶囊
        Row(Modifier.padding(horizontal = 16.dp)) {
            SelectDropdownChip(
                summary = MEMORY_FILTERS.firstOrNull { it.first == typeFilter }?.second ?: "全部",
                options = MEMORY_FILTERS.map { it.second },
                selectedIndex = MEMORY_FILTERS.indexOfFirst { it.first == typeFilter },
                // 类型筛选 = 选择（HIG selection → 最轻的 Select）
                onSelect = { idx -> onTypeFilterChange(MEMORY_FILTERS[idx].first); haptics.perform(HapticEngine.Kind.Select) }
            )
        }
        Spacer(Modifier.height(4.dp))
        // 交接记忆开关：系统每轮回写的「当前状态」，默认开。
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
        ) {
            Column(Modifier.weight(1f)) {
                Text("交接记忆自动写入", style = MaterialTheme.typography.bodySmall)
                Text(
                    "AI 每轮把「正在做什么 + 下一步」写进一条交接记忆；关掉后不再自动生成",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.width(8.dp))
            Switch(
                checked = !handoffOff,
                // 开关 = 选择（HIG）
                onCheckedChange = { on ->
                    haptics.perform(HapticEngine.Kind.Select)
                    onHandoffOffChange(!on)
                }
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            "全局记忆常驻顶部；项目记忆只存在项目根文件夹。工作层（图钉）条目常驻注入，每轮对话后 AI 自动沉淀记忆。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp)
        )
        Spacer(Modifier.height(6.dp))
        if (all.isEmpty()) {
            EmptyHint(if (query.isBlank()) "还没有记忆；对话几轮后 AI 会自动沉淀，或点 + 手工添加" else "没有匹配的记忆")
        } else if (query.isNotBlank()) {
            // 搜索：跨组平铺（叠加类型筛选）
            val memSearchState = androidx.compose.foundation.lazy.rememberLazyListState()
            LazyColumn(
                Modifier.reachedEndHaptic(memSearchState),
                state = memSearchState,
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 4.dp)
            ) {
                items(filtered, key = { it.id }) { m ->
                    androidx.compose.foundation.layout.Box(Modifier.animateItem()) { MemoryCard(m, null) { onEdit(m) } }
                }
                item { Spacer(Modifier.height(60.dp)) }
            }
        } else {
            // v8 分组树：全局置顶；之下项目根文件夹行（点击展开该项目记忆）
            val global = filtered.filter { it.projectId == 0L }
            val grouped = projects.map { p -> p to filtered.filter { it.projectId == p.id } }
            val knownIds = projects.map { it.id }.toSet()
            val orphan = filtered.filter { it.projectId != 0L && it.projectId !in knownIds }
            val memTreeState = androidx.compose.foundation.lazy.rememberLazyListState()
            LazyColumn(
                Modifier.reachedEndHaptic(memTreeState),
                state = memTreeState,
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 4.dp)
            ) {
                if (global.isNotEmpty()) {
                    item(key = "sec_global") {
                        SectionHeaderRow(
                            icon = { Icon(Icons.Rounded.Public, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary) },
                            text = "全局记忆（不属于任何项目）"
                        )
                    }
                    items(global, key = { it.id }) { m ->
                        androidx.compose.foundation.layout.Box(Modifier.animateItem()) { MemoryCard(m, null) { onEdit(m) } }
                    }
                }
                grouped.forEach { (p, ms) ->
                    val open = p.id in expandedProjects
                    item(key = "folder_${p.id}") {
                        ProjectFolderRow(
                            project = p, count = ms.size, expanded = open,
                            // 展开/收起项目分组 = 中性轻触（Tap）
                            onToggle = { onToggleProject(p.id); haptics.perform(HapticEngine.Kind.Tap) }
                        )
                    }
                    if (open && ms.isNotEmpty()) {
                        items(ms, key = { it.id }) { m ->
                            androidx.compose.foundation.layout.Box(Modifier.animateItem()) { MemoryCard(m, p) { onEdit(m) } }
                        }
                    }
                }
                if (orphan.isNotEmpty()) {
                    item(key = "sec_orphan") {
                        SectionHeaderRow(
                            icon = { Icon(Icons.Rounded.FolderOff, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                            text = "历史项目记忆"
                        )
                    }
                    items(orphan, key = { it.id }) { m -> MemoryCard(m, null) { onEdit(m) } }
                }
                item { Spacer(Modifier.height(60.dp)) }
            }
        }
    }
}
