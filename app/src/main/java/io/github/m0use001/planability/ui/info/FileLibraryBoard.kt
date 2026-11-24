@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package io.github.m0use001.planability.ui.info

import androidx.activity.compose.PredictiveBackHandler
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FloatingActionButtonMenu
import androidx.compose.material3.FloatingActionButtonMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ToggleFloatingActionButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.m0use001.planability.app.LocalNav
import io.github.m0use001.planability.app.NavPane
import io.github.m0use001.planability.app.PredictiveBackOverlay
import io.github.m0use001.planability.core.HapticEngine
import io.github.m0use001.planability.core.LocalHaptics
import io.github.m0use001.planability.core.reachedEndHaptic
import io.github.m0use001.planability.data.Graph
import io.github.m0use001.planability.data.Project
import io.github.m0use001.planability.data.ProjectFile
import io.github.m0use001.planability.data.ProjectFileRepo
import io.github.m0use001.planability.ui.common.AppIcons
import io.github.m0use001.planability.ui.common.BoardHeader
import io.github.m0use001.planability.ui.common.ConfirmDialog
import io.github.m0use001.planability.ui.common.EmptyHint
import io.github.m0use001.planability.ui.common.HapticBackButton
import io.github.m0use001.planability.ui.common.motionEnter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.io.File

/**
 * v77 S1b（文件库拆级，2026-11-22）：文件库的**板内钻入**形态——「我的」宽屏把它作为
 * [io.github.m0use001.planability.ui.common.NavHost] 栈里的一块透明瓦片（Rail 常驻、两框不动）。
 *
 * ## 逐级单板分解（用户拍板的钻入手感）
 *
 * 旧 [FileLibraryScreen] 是整页覆盖层：**目录下钻**靠页内 `path` 状态换内容，
 * 「预览 / 沙箱 / 本机存储」都是页内浮层——层级散落在页里，返回要自己分层收口
 * （先关预览、再退目录、最后关页，历史上反复出 bug）。搬上层级栈后这些**全部免费**：
 *
 *  · 本瓦片（`depth = 0`）= 根层（本机存储卡 + 项目文件）；
 *  · 点目录 → [pushLevel] 推一层 [FileLibLevelTile]（depth+1）——主板让位给新目录、
 *    上一级缩进副框（宽度连续改排版），返回集体左移；
 *  · 点文件 → 预览也推一层（[OfficeFilePreview] 瓦片）——「返回分层」变成栈的天然语义；
 *  · 沙箱访问设置 / 本机存储管理器同样是推层（管理器钉死 [io.github.m0use001.planability.ui.files.FilesScreen]
 *    的单板形态——瓦片槽宽在超宽窗口下会超过它的两板阈值，框套框禁）；
 *  · 面包屑点前面的层级 = [io.github.m0use001.planability.app.NavController.popTo] 瞬跳（与旧
 *    `path.take(n)` 同语义）。
 *
 * 树/行绘制/对话框**复用 [FileLibraryScreen] 的 internal 件**（[LibEntry]/[buildLibraryTree]/
 * [DirRow]/[FileRowView]/[StorageEntryCard]/[NewDirDialog]/[ProjectFileEditDialog]），
 * 不复制第二套。共享状态挂 [FileLibBoardState]（hub 持有，各层瓦片经闭包读最新值）；
 * 「当前在哪层」的唯一真源是导航栈：hub 数自己之上连续的 [FileLibLevel] 瓦片
 * （中间夹了别的页会截断计数），`chain` 只是下钻时留下的名字链（弹层后的尾巴要裁掉）。
 *
 * 预览/管理器瓦片的返回由 NavHost 统一接管（预测返回逐层左移），瓦片内不自装返回；
 * 整屏版 [FileLibraryScreen] 保留给资料 Hub / 信息页等尚未拆级的入口（S3 收）。
 */

/** 层级瓦片的 key：hub 据此数「自己之上还活着几层文件夹」 */
private data class FileLibLevel(val depth: Int)

/** 文件库钻入形态的共享状态（hub 持有；各层瓦片闭包引用同一实例） */
private class FileLibBoardState {
    var rootEntries by mutableStateOf<List<LibEntry>>(emptyList())
    var projects by mutableStateOf<List<Project>>(emptyList())
    /** 沙箱设置回来后强制重扫（与 version 并列触发取数） */
    var sandboxTick by mutableStateOf(0)
    var err by mutableStateOf<String?>(null)
    /** 收藏/默认入口：KV「filelib.pin」存的 key 链（与整屏版同一份 KV） */
    var pinKeys by mutableStateOf<String?>(null)
    var showPcBridge by mutableStateOf(false)
    var editing by mutableStateOf<ProjectFile?>(null)
    var showEditor by mutableStateOf(false)
    var showNewDir by mutableStateOf(false)
    var dirToDelete by mutableStateOf<Pair<Long, String>?>(null)
    var fileToDelete by mutableStateOf<LibFile?>(null)
    var renameFile by mutableStateOf<LibFile?>(null)
    var renameText by mutableStateOf("")
    /** 已下钻的名字链（可能残留已弹层的尾巴；用前先 take(liveLevels)） */
    var chain by mutableStateOf<List<LibEntry>>(emptyList())
}

/** 沿 key 链在（可能已重建的）树里解析目录节点；链断（被删/改名）返回 null */
private fun resolveByKeys(root: List<LibEntry>, keys: List<String>): LibEntry? {
    var cur = root
    var node: LibEntry? = null
    for (k in keys) {
        node = cur.firstOrNull { it.key == k } ?: return null
        cur = node.children
    }
    return node
}

@Composable
fun FileLibrarySubBoard(onDone: () -> Unit) {
    val nav = LocalNav.current
    val st = remember { FileLibBoardState() }
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val version by Graph.dataVersion.flow.collectAsState()
    // 本瓦片在栈里的下标：首次组合时 push 已发生（栈顶就是本瓦片），此后不变（栈只在上面长）
    val hubIndex = remember { (nav?.depth ?: 1) - 1 }

    fun pushLevel(depth: Int) {
        nav?.push(
            NavPane(FileLibLevel(depth)) {
                FileLibLevelTile(st = st, hubIndex = hubIndex, depth = depth, onDone = onDone)
            }
        )
    }

    LaunchedEffect(version, st.sandboxTick) {
        st.projects = Graph.projects.all()
        st.rootEntries = runCatching { buildLibraryTree(ctx) }.getOrElse {
            st.err = it.message ?: "文件库读不出来"
            emptyList()
        }
        // 收藏/默认入口：只在还停在根层时跳（中途刷新不把人从正看的目录拽走，与整屏版同款）
        if (st.chain.take(
                nav?.stack?.drop(hubIndex + 1)?.takeWhile { it.key is FileLibLevel }?.size ?: 0
            ).isEmpty()
        ) {
            val pin = Graph.settings.kvGet("filelib.pin")
            if (pin.isNullOrBlank()) {
                st.pinKeys = null
            } else {
                // 沿 key 链逐层找；链断（目录被删/改名）就清收藏回落根，别让按钮永远悬空
                var cur = st.rootEntries
                val found = mutableListOf<LibEntry>()
                var ok = true
                for (k in pin.split("|").filter { it.isNotBlank() }) {
                    val e = cur.firstOrNull { it.key == k }
                    if (e == null) { ok = false; break }
                    found += e
                    cur = e.children
                }
                if (ok && found.isNotEmpty()) {
                    st.chain = found
                    st.pinKeys = pin
                    // deck 还原：把每层推回栈（NavHost 对 targetLevel 跳变只播一次动画）
                    found.forEachIndexed { i, _ -> pushLevel(i + 1) }
                } else {
                    st.pinKeys = null
                    Graph.settings.kvDelete("filelib.pin")
                }
            }
        }
    }

    FileLibLevelTile(st = st, hubIndex = hubIndex, depth = 0, onDone = onDone)

    // —— 对话框（窗口浮在最上，与所在瓦片的槽位无关；状态挂 st，任何层都能触发）——
    if (st.showEditor) {
        ProjectFileEditDialog(
            initial = st.editing,
            projects = st.projects,
            onDismiss = { st.showEditor = false },
            onSave = { f ->
                scope.launch {
                    // 磁盘写失败必须让用户看见（与整屏版同款）
                    st.err = runCatching { ProjectFileRepo.save(f) }.exceptionOrNull()?.message
                }
                st.showEditor = false
            }
        )
    }
    if (st.showNewDir) {
        NewDirDialog(
            onDismiss = { st.showNewDir = false },
            onCreate = { pid, path ->
                scope.launch {
                    val ok = ProjectFileRepo.mkdirs(pid, path) != null
                    st.err = if (ok) null else "建不出这个目录（路径非法，或被同名文件占了位置）"
                }
                st.showNewDir = false
            }
        )
    }
    st.dirToDelete?.let { (pid, rel) ->
        AlertDialog(
            modifier = Modifier.motionEnter(),
            onDismissRequest = { st.dirToDelete = null },
            title = { Text("删除文件夹") },
            text = { Text("删除「$rel」及其全部内容？此操作不可恢复。") },
            confirmButton = {
                TextButton(onClick = {
                    st.dirToDelete = null
                    scope.launch { ProjectFileRepo.deleteDir(pid, rel) }
                }) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { st.dirToDelete = null }) { Text("取消") } }
        )
    }
    st.fileToDelete?.let { f ->
        ConfirmDialog(
            title = "删除文件",
            text = "确定删除「${f.name}」？此操作不可恢复。",
            onConfirm = {
                scope.launch {
                    val target = if (f.projectId >= 0) {
                        runCatching {
                            val dir = ProjectFileRepo.resolveDir(f.projectId, f.sub)
                            if (dir != null) File(dir, f.name) else null
                        }.getOrNull()
                    } else {
                        f.abs
                    }
                    if (target != null && target.delete()) Graph.dataVersion.bump()
                    else st.err = "删不掉这个文件（可能已被电脑端 Syncthing 同步走了）"
                }
                st.fileToDelete = null
            },
            onDismiss = { st.fileToDelete = null }
        )
    }
    st.renameFile?.let { f ->
        AlertDialog(
            modifier = Modifier.motionEnter(),
            onDismissRequest = { st.renameFile = null },
            title = { Text("重命名文件") },
            text = {
                androidx.compose.material3.OutlinedTextField(
                    value = st.renameText,
                    onValueChange = { st.renameText = it },
                    singleLine = true,
                    label = { Text("文件名（含扩展名）") },
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(
                    enabled = st.renameText.isNotBlank(),
                    onClick = {
                        scope.launch {
                            val ok = runCatching {
                                ProjectFileRepo.rename(f.projectId, f.name, st.renameText.trim(), f.sub)
                            }.getOrDefault(false)
                            if (!ok) st.err = "重命名失败：名称非法或同名文件已存在"
                        }
                        st.renameFile = null
                    }
                ) { Text("重命名") }
            },
            dismissButton = { TextButton(onClick = { st.renameFile = null }) { Text("取消") } }
        )
    }
}

/**
 * 文件库栈里的一块瓦片（`depth = 0` 根层 / ≥1 目录层）。
 *
 * 「是不是栈顶」决定两件事：面包屑/收藏等页级控件只在栈顶那块有浮层语义；本机存储
 * 管理器（不透明整层）只画在栈顶，否则副框里的旧目录会透出一层背景板。
 */
@Composable
private fun FileLibLevelTile(
    st: FileLibBoardState,
    hubIndex: Int,
    depth: Int,
    onDone: () -> Unit
) {
    val nav = LocalNav.current
    val haptics = LocalHaptics.current
    val scope = rememberCoroutineScope()
    val node = if (depth == 0) null else
        resolveByKeys(st.rootEntries, st.chain.take(depth).map { it.key })
    val entries = when {
        depth == 0 -> st.rootEntries
        node != null -> node.children
        else -> emptyList()
    }
    // (label, keep) — keep = 跳到这条面包屑后保留的层数（「文件库」= 0）
    val crumbs = buildList {
        add("文件库" to 0)
        st.chain.take(depth).forEachIndexed { i, e -> add(e.name to i + 1) }
    }
    val currentChain = st.chain.take(depth).joinToString("|") { it.key }
    val pinned = depth > 0 && st.pinKeys == currentChain
    var fabMenu by remember { mutableStateOf(false) }

    fun pushLevel(next: Int) {
        nav?.push(
            NavPane(FileLibLevel(next)) {
                FileLibLevelTile(st = st, hubIndex = hubIndex, depth = next, onDone = onDone)
            }
        )
    }

    fun descend(e: LibEntry) {
        val live = nav?.stack?.drop(hubIndex + 1)?.takeWhile { it.key is FileLibLevel }?.size ?: 0
        st.chain = st.chain.take(live) + e
        pushLevel(live + 1)
    }

    fun openFile(fl: LibFile) {
        if (fl.projectId >= 0) {
            st.editing = ProjectFile(
                projectId = fl.projectId, name = fl.name, sub = fl.sub,
                content = readTextOrEmpty(fl.abs),
                size = fl.size.toInt(), updatedAt = fl.updatedAt
            )
            st.showEditor = true
        } else {
            // 只读文件（用户原件/会话文件）→ 预览瓦片（返回逐层左移，不再需要页内分层收口）
            nav?.push(
                NavPane("filelib-preview-${fl.abs.absolutePath.hashCode()}") {
                    PredictiveBackOverlay(onBack = { nav?.pop() }, deferBack = true) {
                        OfficeFilePreview(fl.abs, onBack = { nav?.pop() })
                    }
                }
            )
        }
    }

    fun openSandbox() {
        nav?.push(
            NavPane("filelib-sandbox") {
                io.github.m0use001.planability.ui.files.SandboxSettingsScreen(
                    onDone = { st.sandboxTick++; nav?.pop() },
                    deferBack = true
                )
            }
        )
    }

    fun openManager() {
        // 本机存储（完整文件管理器）：推成一块不透明瓦片；forceSinglePane 防超宽窗口下
        // 它在单板槽里画出自己的两板（框套框）
        nav?.push(
            NavPane("filelib-manager") {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background)
                ) {
                    io.github.m0use001.planability.ui.files.FilesScreen(
                        onBack = { nav?.pop() },
                        forceSinglePane = true
                    )
                }
            }
        )
    }

    // FAB 菜单开着时系统返回先收菜单（瓦片内层 handler 比 NavHost 的优先接管）
    PredictiveBackHandler(enabled = fabMenu) { prog ->
        try {
            prog.collect { }
            fabMenu = false
        } catch (e: CancellationException) {
            fabMenu = false
            throw e
        }
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            BoardHeader(
                title = node?.name ?: "文件库",
                leading = {
                    HapticBackButton {
                        if (depth == 0) onDone() else nav?.pop()
                    }
                },
                actions = {
                    // 沙箱访问设置：图标兼做状态指示（与整屏版同款）
                    IconButton(onClick = { haptics.perform(HapticEngine.Kind.Tap); openSandbox() }) {
                        val ready = io.github.m0use001.planability.data.SandboxAccess.isReady()
                        Icon(
                            if (ready) Icons.Rounded.LockOpen else Icons.Rounded.Lock,
                            contentDescription = "沙箱访问设置",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(onClick = { st.showPcBridge = !st.showPcBridge }) {
                        Icon(
                            AppIcons.Kind.Computer, "电脑端渲染设置",
                            tint = if (st.showPcBridge) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)
                        )
                    }
                }
            )
            // ── 面包屑胶囊（整屏版同款）：根层是「文件库」标签，层内原位变返回键 + 可点跳层 ──
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(percent = 50))
                        .background(MaterialTheme.colorScheme.surfaceContainer)
                        .padding(start = 8.dp, end = 4.dp, top = 4.dp, bottom = 4.dp)
                ) {
                    if (depth == 0) {
                        Icon(
                            AppIcons.Setting.FileLibrary, null, Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.width(8.dp))
                    } else {
                        IconButton(
                            onClick = { haptics.perform(HapticEngine.Kind.Tap); nav?.pop() },
                            modifier = Modifier.size(30.dp)
                        ) {
                            Icon(
                                AppIcons.Nav.Back, "上一级", Modifier.size(17.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(Modifier.width(4.dp))
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .weight(1f)
                            .horizontalScroll(rememberScrollState())
                    ) {
                        crumbs.forEachIndexed { i, crumb ->
                            val (label, keep) = crumb
                            if (i > 0) {
                                Text(
                                    " / ",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            val isLast = i == crumbs.lastIndex
                            Text(
                                label,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = if (isLast) MaterialTheme.colorScheme.onSurface
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1, softWrap = false,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .clickable {
                                        if (!isLast) {
                                            haptics.perform(HapticEngine.Kind.Tap)
                                            nav?.popTo(hubIndex + 1 + keep)
                                        }
                                    }
                                    .padding(horizontal = 2.dp)
                            )
                        }
                    }
                    // 收藏 = 把当前路径记为默认打开入口（与整屏版同一份 KV）
                    IconButton(
                        onClick = {
                            haptics.perform(HapticEngine.Kind.Tap)
                            if (pinned) {
                                st.pinKeys = null
                                scope.launch { Graph.settings.kvDelete("filelib.pin") }
                            } else if (depth > 0) {
                                st.pinKeys = currentChain
                                scope.launch { Graph.settings.kvPut("filelib.pin", currentChain) }
                            }
                        },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            if (pinned) AppIcons.Act.Pin else AppIcons.Act.PinOutline,
                            contentDescription = if (pinned) "取消默认入口" else "设为默认打开路径",
                            Modifier.size(18.dp),
                            tint = if (pinned) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            if (st.err != null) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
                ) {
                    Text(
                        st.err!!, style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = { st.err = null }) { Text("知道了") }
                }
            }
            if (st.showPcBridge) {
                PcBridgeSettingCard()
            }
            val listState = androidx.compose.foundation.lazy.rememberLazyListState()
            LazyColumn(
                Modifier.fillMaxSize().reachedEndHaptic(listState),
                state = listState,
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                if (depth == 0) {
                    item(key = "fs-entry") { StorageEntryCard(onOpen = { openManager() }) }
                    item(key = "sec-projects") { SectionHeader("项目文件") }
                }
                if (entries.isEmpty() && !st.showPcBridge) {
                    item(key = "empty") {
                        EmptyHint(if (depth == 0) "此文件夹为空" else "目录不存在或已为空")
                    }
                }
                items(entries.filter { it.key != "fs" }, key = { it.key }) { e ->
                    Box(Modifier.animateItem()) {
                        if (e.isDir) {
                            DirRow(
                                e,
                                onOpen = { descend(e) },
                                // 只有「项目数据」里的目录才可删（与整屏版同规则）
                                onDelete = if (e.ownerPid >= 0 && !e.dataRel.isNullOrBlank()) {
                                    { st.dirToDelete = e.ownerPid to e.dataRel!! }
                                } else null
                            )
                        } else {
                            val fl = e.file!!
                            FileRowView(
                                fl,
                                onOpen = { openFile(fl) },
                                // 用户的文件（项目数据之外）只能预览，不给删
                                onDelete = if (fl.projectId >= 0) ({ st.fileToDelete = fl }) else null,
                                onRename = if (fl.projectId >= 0) {
                                    { st.renameFile = fl; st.renameText = fl.name }
                                } else null
                            )
                        }
                    }
                }
                item { Spacer(Modifier.height(96.dp)) }
            }
        }
        // FAB（官方 FloatingActionButtonMenu；瓦片内不垫 navigationBarsPadding——壳层视口已垫）
        FloatingActionButtonMenu(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp),
            expanded = fabMenu,
            button = {
                ToggleFloatingActionButton(
                    checked = fabMenu,
                    onCheckedChange = {
                        haptics.perform(HapticEngine.Kind.Tap)
                        fabMenu = it
                    }
                ) {
                    val imageVector by remember {
                        derivedStateOf {
                            if (checkedProgress > 0.5f) AppIcons.Nav.Close else AppIcons.Act.Add
                        }
                    }
                    Icon(
                        painter = rememberVectorPainter(imageVector),
                        contentDescription = "新建"
                    )
                }
            }
        ) {
            FloatingActionButtonMenuItem(
                onClick = { fabMenu = false; st.editing = null; st.showEditor = true },
                text = { Text("新建文件") },
                icon = { Icon(AppIcons.Kind.File, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) }
            )
            FloatingActionButtonMenuItem(
                onClick = { fabMenu = false; st.showNewDir = true },
                text = { Text("新建文件夹") },
                icon = { Icon(AppIcons.Act.NewFolder, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) }
            )
        }
    }
}
