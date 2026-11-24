package io.github.m0use001.planability.ui.info

import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.FolderOff
import androidx.compose.material.icons.rounded.Info
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material3.AlertDialog
import io.github.m0use001.planability.ui.common.motionEnter
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import io.github.m0use001.planability.app.PredictiveBackOverlay
import io.github.m0use001.planability.core.AppTime
import io.github.m0use001.planability.core.formatMonthDay
import io.github.m0use001.planability.core.toLocalDate
import io.github.m0use001.planability.data.Graph
import io.github.m0use001.planability.data.Project
import kotlinx.coroutines.launch

// ==================== v5 项目大体系 UI：创建/选择/移动/详情 ====================

/** 新建项目对话框。v42：可选定（或新建）手机里的真实文件夹作为项目文件夹——项目=AI 的真实工作区。 */
@Composable
fun ProjectCreateDialog(
    onDismiss: () -> Unit,
    onCreate: (String, String, String) -> Unit
) {
    var name by remember { mutableStateOf("") }
    /** 图标选择已按用户要求取消（2026-10-22）：项目统一用默认文件夹图标 */
    val iconKey = "folder"
    /** 绑定的真实文件夹；空 = 默认（应用同步目录 synced/projects/，可被 Syncthing 同步） */
    var dirPath by remember { mutableStateOf("") }
    var pickFolder by remember { mutableStateOf(false) }
    AlertDialog(
        // 2026-11-12 用户「不能新建项目了」＝软键盘盖住「创建」按钮：AlertDialog 默认不避让
        // IME，键盘一弹确认键就点不到。decorFitsSystemWindows=false + imePadding 让整块
        // 对话框在键盘弹出时上移（TimeRangePicker 同一参数口径）。
        modifier = Modifier.motionEnter().imePadding(),
        properties = DialogProperties(decorFitsSystemWindows = false),
        onDismissRequest = onDismiss,
        title = { Text("新建项目", style = MaterialTheme.typography.titleSmall) },
        text = {
            Column {
                OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    singleLine = true, placeholder = { Text("项目名称") }
                )
                Spacer(Modifier.height(10.dp))
                Text("项目文件夹", style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(4.dp))
                Card(
                    shape = RoundedCornerShape(10.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainer
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { pickFolder = true }
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)
                    ) {
                        Icon(
                            Icons.Rounded.Folder, null, Modifier.size(18.dp),
                            // R11：对话框行首文件夹图标 → 中性
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                if (dirPath.isBlank()) "默认（应用同步目录）"
                                else java.io.File(dirPath).name,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                            )
                            if (dirPath.isNotBlank()) {
                                Text(
                                    dirPath,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                )
                            }
                        }
                        Text(
                            if (dirPath.isBlank()) "选择" else "更改",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
                if (dirPath.isNotBlank()) {
                    Text(
                        "对话与 AI 生成文件将真实存放在这个文件夹里",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { if (name.isNotBlank()) onCreate(name.trim(), iconKey, dirPath.trim()) },
                enabled = name.isNotBlank()
            ) { Text("创建") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
    if (pickFolder) {
        FolderPickDialog(
            initial = dirPath,
            onDismiss = { pickFolder = false },
            onPick = {
                dirPath = it
                pickFolder = false
            }
        )
    }
}

/**
 * v42：真实文件夹选择器——浏览手机存储、进入目录、可「新建文件夹」，选定绝对路径。
 *
 * 为什么不用系统的 SAF 选择器（ACTION_OPEN_DOCUMENT_TREE）：项目文件体系是
 * java.io.File 一套（FolderRepo/ProjectFileRepo/文件管理页/AI file_library_* 工具全部
 * 以真实路径为地基），SAF 的 content:// tree URI 混进来等于再造第二套存储；
 * 本应用已有「所有文件访问」授权（文件页同款），直接浏览真实路径即可与全线打通。
 */
@Composable
private fun FolderPickDialog(
    initial: String,
    onDismiss: () -> Unit,
    onPick: (String) -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var granted by remember { mutableStateOf(android.os.Environment.isExternalStorageManager()) }
    val rootPath = remember { android.os.Environment.getExternalStorageDirectory().absolutePath }
    var cur by remember { mutableStateOf(initial.ifBlank { rootPath }) }
    var newDirName by remember { mutableStateOf("") }
    var showNew by remember { mutableStateOf(false) }

    fun dirsOf(): List<java.io.File> =
        if (!granted) emptyList()
        else java.io.File(cur).listFiles { f -> f.isDirectory && !f.name.startsWith(".") }
            ?.sortedBy { it.name.lowercase() } ?: emptyList()
    var dirs by remember(cur, granted) { mutableStateOf(dirsOf()) }

    // 授权后从设置返回时刷新（与文件页同款观察）
    androidx.compose.runtime.DisposableEffect(Unit) {
        val activity = context as? androidx.activity.ComponentActivity
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e ->
            if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                granted = android.os.Environment.isExternalStorageManager()
            }
        }
        activity?.lifecycle?.addObserver(obs)
        onDispose { activity?.lifecycle?.removeObserver(obs) }
    }

    AlertDialog(
        // 选文件夹对话框同样避让 IME：展开「新建文件夹」输入行时键盘会盖住底部按钮
        modifier = Modifier.motionEnter().imePadding(),
        properties = DialogProperties(decorFitsSystemWindows = false),
        onDismissRequest = onDismiss,
        title = { Text("选择项目文件夹", style = MaterialTheme.typography.titleSmall) },
        text = {
            Column {
                if (!granted) {
                    Text(
                        "需要「所有文件访问」权限才能浏览手机文件夹（与文件页共用同一授权）。",
                        style = MaterialTheme.typography.bodySmall
                    )
                    TextButton(onClick = {
                        runCatching {
                            context.startActivity(
                                android.content.Intent(
                                    "android.settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION",
                                    android.net.Uri.parse("package:${context.packageName}")
                                )
                            )
                        }.onFailure {
                            runCatching {
                                context.startActivity(
                                    android.content.Intent("android.settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION")
                                )
                            }
                        }
                    }) { Text("去授权") }
                } else {
                    Text(
                        cur,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(
                            onClick = { cur = java.io.File(cur).parent ?: rootPath },
                            enabled = cur != rootPath && java.io.File(cur).parentFile?.isDirectory == true
                        ) { Text("上一级") }
                        TextButton(onClick = { showNew = !showNew }) {
                            Icon(Icons.Rounded.CreateNewFolder, null, Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("新建文件夹")
                        }
                    }
                    if (showNew) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(
                                value = newDirName, onValueChange = { newDirName = it },
                                singleLine = true,
                                placeholder = { Text("文件夹名") },
                                modifier = Modifier.weight(1f)
                            )
                            TextButton(onClick = {
                                val clean = newDirName.trim().replace(Regex("[\\\\/:*?\"<>|]"), "_").take(60)
                                val d = java.io.File(cur, clean)
                                if (clean.isNotBlank() && (d.isDirectory || d.mkdirs())) {
                                    cur = d.absolutePath
                                    dirs = dirsOf()
                                    newDirName = ""
                                    showNew = false
                                }
                            }, enabled = newDirName.isNotBlank()) { Text("创建") }
                        }
                    }
                    Column(
                        Modifier
                            .fillMaxWidth()
                            // 2026-11-12：键盘避让后总高要留头空间，300→240；列表自身可滚，不丢内容
                            .height(240.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        dirs.forEach { d ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable {
                                        cur = d.absolutePath
                                        newDirName = ""
                                        showNew = false
                                    }
                                    .padding(horizontal = 4.dp, vertical = 10.dp)
                            ) {
                                Icon(
                                    Icons.Rounded.Folder, null, Modifier.size(18.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(d.name, style = MaterialTheme.typography.bodyLarge)
                            }
                        }
                        if (dirs.isEmpty()) {
                            Text(
                                "（空目录——可以直接在这里新建项目文件夹）",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "将选定：$cur",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 2,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                    )
                }
            }
        },
        confirmButton = {
            TextButton(enabled = granted, onClick = { onPick(cur) }) { Text("选定此文件夹") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

/** 项目选择 sheet：未分类 + 全部项目 + 新建入口（首页输入卡与移动会话共用） */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectPickerSheet(
    selectedId: Long,
    showDetailEntry: Boolean = false,
    onDismiss: () -> Unit,
    onPick: (Long) -> Unit,
    onOpenDetail: (Long) -> Unit = {}
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var projects by remember { mutableStateOf<List<Project>>(emptyList()) }
    var showCreate by remember { mutableStateOf(false) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val version by Graph.dataVersion.flow.collectAsState()
    // v19.62 修：原来只有 LaunchedEffect(version) 一条读取路径 —— 弹层首次组合时 dataVersion
    // 往往还没 emit，而之后版本号若不变（没新增/改任何数据）就再也不会重读，面板就一直空着，
    // 真机表现：新建项目能用、但两个已有项目一个都不列。改成**首次进来也读一次** +
    // 版本变化再读（新建项目后立即刷新），两条路都覆盖。
    // v19.62：首次组合也要读一次（只靠 dataVersion 时，弹层首次打开常常拿不到数据）。
    LaunchedEffect(Unit) { projects = Graph.projects.all() }
    LaunchedEffect(version) { projects = Graph.projects.all() }

    ModalBottomSheet(contentWindowInsets = { WindowInsets.navigationBars }, dragHandle = { io.github.m0use001.planability.ui.common.SheetDragHandleOnly() }, onDismissRequest = onDismiss, sheetState = sheetState) {
        // 2026-11-12 用户「不能新建项目了」：底部「新建项目」是 sheet 最后一行，
        // 手势条/导航栏可能压上来——navigationBarsPadding 兜住可视区
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(start = 20.dp, end = 20.dp, bottom = 28.dp)
        ) {
            Text("选择项目", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                "对话、笔记、记忆、日程、待办都必须归属一个真实项目。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp, bottom = 8.dp)
            )
            // 2026-11-12 用户「不能新建项目了」=「按钮遮住了」真因（真机截图实证）：
            // 项目列表是不可滚的 Column，项目一多（现 10 个）就把底部「新建项目」
            // 顶到屏幕外——按钮不是被盖住，是根本不在可视区。列表改可滚 + 按钮固定底部。
            LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false)) {
                items(projects, key = { it.id }) { p ->
                    PickerRow(
                        icon = io.github.m0use001.planability.data.WorkbenchIcons.project(p.iconKey),
                        title = p.name,
                        selected = selectedId == p.id,
                        trailing = if (showDetailEntry) {
                            {
                                IconButton(onClick = { onDismiss(); onOpenDetail(p.id) }) {
                                    Icon(
                                        Icons.Rounded.Info, "项目详情",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        } else null
                    ) { onDismiss(); onPick(p.id) }
                }
            }
            TextButton(onClick = { showCreate = true }) {
                Icon(Icons.Rounded.Add, null, Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("新建项目")
            }
        }
    }
    if (showCreate) {
        ProjectCreateDialog(
            onDismiss = { showCreate = false },
            onCreate = { name, iconKey, dirPath ->
                showCreate = false
                scope.launch {
                    val id = Graph.projects.create(name, iconKey, dirPath)
                    onDismiss()
                    onPick(id)
                }
            }
        )
    }
}

@Composable
private fun PickerRow(
    icon: ImageVector,
    title: String,
    selected: Boolean,
    trailing: (@Composable () -> Unit)? = null,
    onClick: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(
                // 2026-10-25：单选项的选中态统一主色族（与 AppButtonGroup / 筛选 chip 同一口径）。
                // 原来用 secondaryContainer@0.5——secondary 修成可读的粉之后，这一行会在
                // 「绿的分段按钮」旁边显出粉色，正是用户说的「多选一按钮组有些红有些绿」。
                if (selected) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surfaceContainer
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 10.dp)
    ) {
        // R11：项目类型图标 = 行首标记 → 中性（选中态由容器底色表达）
        Icon(icon, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(10.dp))
        Text(
            title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        trailing?.invoke()
    }
}

/** 把某个会话移动到项目（会话列表 / 对话页顶栏用） */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectMoveSheet(
    sessionId: Long,
    onDismiss: () -> Unit,
    onMoved: (Long) -> Unit = {}
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var projects by remember { mutableStateOf<List<Project>>(emptyList()) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    LaunchedEffect(Unit) { projects = Graph.projects.all() }

    ModalBottomSheet(contentWindowInsets = { WindowInsets.navigationBars }, dragHandle = { io.github.m0use001.planability.ui.common.SheetDragHandleOnly() }, onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 28.dp)) {
            Text("移动到项目", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            projects.forEach { p ->
                PickerRow(
                    icon = io.github.m0use001.planability.data.WorkbenchIcons.project(p.iconKey),
                    title = p.name,
                    selected = false
                ) {
                    scope.launch {
                        Graph.chat.setSessionProject(sessionId, p.id)
                        onMoved(p.id)
                        onDismiss()
                    }
                }
            }
        }
    }
}

/** 项目详情聚合页：该项目下的 对话/笔记/记忆/日程/待办 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectDetailScreen(projectId: Long, onDone: () -> Unit) {
    val version by Graph.dataVersion.flow.collectAsState()
    var project by remember { mutableStateOf<Project?>(null) }
    var sessions by remember { mutableStateOf<List<io.github.m0use001.planability.data.ChatSession>>(emptyList()) }
    var notes by remember { mutableStateOf<List<io.github.m0use001.planability.data.NoteEntity>>(emptyList()) }
    var memories by remember { mutableStateOf<List<io.github.m0use001.planability.data.MemoryEntity>>(emptyList()) }

    LaunchedEffect(version, projectId) {
        project = Graph.projects.byId(projectId)
        sessions = Graph.projects.sessionsOf(projectId)
        notes = Graph.projects.notesOf(projectId)
        memories = Graph.projects.memoriesOf(projectId)
    }
    val p = project

    PredictiveBackOverlay(onBack = onDone) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (p != null) {
                                    Icon(
                                        io.github.m0use001.planability.data.WorkbenchIcons.project(p.iconKey),
                                        null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Spacer(Modifier.width(8.dp))
                                }
                                Text(
                                    p?.name ?: "项目详情",
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                            Text(
                                "对话 ${sessions.size} · 笔记 ${notes.size} · 记忆 ${memories.size}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = onDone) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "返回") }
                    }
                )
            }
        ) { pad ->
            if (p == null) {
                Column(Modifier.padding(pad).fillMaxWidth()) { Spacer(Modifier.height(60.dp)) }
                return@Scaffold
            }
            LazyColumn(
                Modifier.fillMaxWidth().padding(pad),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 10.dp)
            ) {
                fun section(title: String, rows: List<Pair<String, String>>) {
                    if (rows.isEmpty()) return
                    item(key = "sec_$title") {
                        Text(
                            title, style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(top = 12.dp, bottom = 4.dp, start = 4.dp)
                        )
                    }
                    items(rows.size, key = { "r_$title$it" }) { i ->
                        val (t, sub) = rows[i]
                        Card(
                            shape = RoundedCornerShape(14.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                            modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)
                        ) {
                            Column(Modifier.padding(horizontal = 12.dp, vertical = 9.dp)) {
                                Text(t, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                if (sub.isNotBlank()) Text(sub, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
                section("对话", sessions.map { it.title to relTime(it.updatedAt) })
                section("笔记", notes.map { it.title to AppTime.millisToTime(it.updatedAt) + " " + it.updatedAt.toLocalDate().formatMonthDay() })
                section("记忆", memories.map { "${it.name}（${it.type}）" to it.description.take(40) })
                // 日程/待办已与项目解耦（2026-10-21），这里不再列——它们只属于日历
                item { Spacer(Modifier.height(60.dp)) }
            }
        }
    }
}
