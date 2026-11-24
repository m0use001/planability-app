@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package io.github.m0use001.planability.ui.info

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material3.AlertDialog
import io.github.m0use001.planability.ui.common.motionEnter
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonMenu
import androidx.compose.material3.FloatingActionButtonMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ToggleFloatingActionButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.zIndex
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.m0use001.planability.ai.office.ExportStore
import io.github.m0use001.planability.app.LocalOverlayClose
import io.github.m0use001.planability.app.PredictiveBackOverlay
import io.github.m0use001.planability.core.HapticEngine
import io.github.m0use001.planability.core.LocalHaptics
import io.github.m0use001.planability.core.reachedEndHaptic
import io.github.m0use001.planability.data.Graph
import io.github.m0use001.planability.data.Project
import io.github.m0use001.planability.data.ProjectDirs
import io.github.m0use001.planability.data.ProjectFile
import io.github.m0use001.planability.data.ProjectFileRepo
import io.github.m0use001.planability.ui.common.AppIcons
import io.github.m0use001.planability.ui.common.ConfirmDialog
import io.github.m0use001.planability.ui.common.EmptyHint
import io.github.m0use001.planability.ui.common.pressScale
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import io.github.m0use001.planability.ui.common.filterChipColors

/**
 * v28 文件库：**按「信息 → 右滑菜单」的层级组织**，只列项目文件与 AI 生成的文件。
 *
 * ## 为什么改成照抄抽屉的层级
 *
 * 用户 2026-10-12 的原话：「文件库显示的包含与上下级关系参考信息界面右滑菜单，
 * 只不过只显示项目文件与 AI 生成的文件」。
 *
 * 从前文件库是「全局 + 各项目」两段平铺，跟抽屉里的项目/会话树对不上：
 * 用户在抽屉里看到的"这个对话的文件去哪了"，到文件库得自己猜。
 *
 * ## 树长什么样
 *
 * ```
 * 未分类                     ← 抽屉里的顶层伪节点
 *   <会话 A>                 ← 只有**自己拥有目录**的会话才出现（见下）
 *     x.docx                 ← 该会话的 AI 生成文件
 *   散落在全局根的文件        ← 直接挂在未分类下
 * 项目 A
 *   <子目录>                 ← 项目目录下的真实子目录（含会话文件夹）
 *     笔记.md
 *   另一份文件.md
 * 旧导出目录                 ← v14.3 之前的扁平 exports/，别让它变成"消失的文件"
 *   老文件.pptx
 * ```
 *
 * ## 为什么项目里的会话不单独列
 *
 * 因为**物理上它们没有自己的目录**：[ExportStore] 的落点规则决定了项目内会话的文件
 * 就写在项目根或会话文件夹里，和项目文件是同一批磁盘文件。硬给每个会话也挂一份列表，
 * 同一个文件会在树上出现两次——用户会以为存了两份，或者以为有一份删不掉。
 * 所以「长在哪棵树上」就等于「文件归谁管」，一个文件只出现一次。
 *
 * ## 折叠
 *
 * 折叠只藏**严格后代**，目录自己那一行永远留着。
 * 第一版把 `path == rel` 也算成"被折叠藏起来"，于是点一下收起、目录整行消失，
 * 再也点不回来（用户报的现象：收起后没法展开）。
 */

// ==================== 行模型 ====================

/** 文件负载（打开/编辑/删除共用）。[projectId] >= 0 = 项目文件（可编辑）；-1 = 会话/旧目录里的文件 */
// v77 S1b：以下行模型/取数/行绘制/对话框改为 internal——文件库的「板内钻入」形态
// （FileLibraryBoard.kt，宽屏「我的」入口）复用同一份，不复制第二套。
internal data class LibFile(
    val key: String,
    val name: String,
    val sub: String,
    val size: Long,
    val updatedAt: Long,
    val projectId: Long,
    val abs: File
)

/**
 * 一个可导航条目：目录（带子层）或文件。
 *
 * 2026-10-28 用户：「文件库要做成之前『文件』页面的样式，而不是复制右滑菜单的项目与会话」
 * ——从抽屉式的一整棵展开树改回**逐层目录浏览**（面包屑胶囊 + 每层只列当前目录，
 * 与旧 ui/files/FilesScreen 的浏览语义一致）。树在这里一次建成，UI 沿 path 下钻。
 *
 * 2026-10-29：文件库根改为**整个项目根**（用户真实文件夹整棵树都能看），
 * [dataRel] 标记条目是否在「项目数据」里（相对项目数据的路径；根自己 = ""，
 * 用户的文件 = null）：只有 dataRel != null 的条目才允许编辑/改名/删除——
 * app 不写「项目数据」以外的地方，用户文件只能看。
 */
internal data class LibEntry(
    val key: String,
    val name: String,
    val isDir: Boolean,
    val children: List<LibEntry> = emptyList(),
    /** 目录：所属项目 id（-1 = 不可删的虚拟目录/会话目录）+ 相对项目根的路径（"" = 根） */
    val ownerPid: Long = -1L,
    val relPath: String = "",
    /** 相对「项目数据」的路径；null = 不在项目数据里（用户自己的文件，只读） */
    val dataRel: String? = null,
    val file: LibFile? = null
)

internal fun fileEntry(f: File, keyPrefix: String, pid: Long, sub: String, dataRel: String?): LibEntry {
    val key = "$keyPrefix/f:${f.name}"
    return LibEntry(
        key = key, name = f.name, isDir = false, dataRel = dataRel,
        file = LibFile(
            key = key, name = f.name, sub = sub, size = f.length(),
            updatedAt = f.lastModified(),
            // 只把「项目数据」里的文件当项目文件（可编辑）；用户的原样只读
            projectId = if (dataRel != null) pid else -1L, abs = f
        )
    )
}

/** 递归列一个真实目录：子目录在前、文件在后，各按名排序（旧文件页的浏览语义） */
internal suspend fun buildChildren(
    dir: File,
    keyPrefix: String,
    pid: Long,
    relBase: String,
    dataRelBase: String? = null
): List<LibEntry> =
    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val dirs = dir.listFiles { f -> f.isDirectory && !f.name.startsWith(".") }
            ?.sortedBy { it.name.lowercase() } ?: emptyList()
        val files = dir.listFiles { f -> f.isFile && !f.name.startsWith(".") }
            ?.sortedBy { it.name.lowercase() } ?: emptyList()
        val out = mutableListOf<LibEntry>()
        dirs.forEach { d ->
            val rel = if (relBase.isBlank()) d.name else "$relBase/${d.name}"
            // dataRelBase=null = 本层在「项目数据」外（绑定项目的用户区）；但遇名叫「项目数据」的
            // 目录就是数据容器本身（绑定项目的固定层），从它开始整个子树都归 app 管
            var dataRel = dataRelBase?.let { if (it.isBlank()) d.name else "$it/${d.name}" }
            if (dataRel == null && d.name == ProjectDirs.DATA) dataRel = ""
            val k = "$keyPrefix/d:${d.name}"
            // v19.62：会话目录显示名去掉 "__<会话id>" 后缀——用户只关心标题
            out += LibEntry(
                key = k, name = d.name.replace(Regex("__\\d+$"), ""), isDir = true,
                children = buildChildren(d, k, pid, rel, dataRel),
                ownerPid = pid, relPath = rel, dataRel = dataRel
            )
        }
        files.forEach { out += fileEntry(it, keyPrefix, pid, dataRelBase ?: "", dataRelBase) }
        out
    }

/**
 * 整棵文件库的取数。每段的来源：
 * - 未分类：已有目录的未分类会话（existingDirFor 不建目录）+ 散落全局根的文件；
 * - 各项目：项目目录（绑定的真实文件夹）整棵；
 * - 旧导出目录：v14.3 之前的扁平 exports/，别让它变成"消失的文件"。
 */
internal suspend fun buildLibraryTree(ctx: Context): List<LibEntry> =
    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val out = mutableListOf<LibEntry>()
        // 2026-10-31 用户：「文件库怎么又变成显示项目文件了，不是要做成完整文件管理器吗」。
        // 完整文件管理器（ui/files/FilesScreen）从根层显眼的「本机存储」入口卡进（见下方
        // StorageEntryCard）——不再作为一条普通目录行混在项目列表里（用户实测反馈混放）。
        // 数据容器（当前会话/已归档/项目记忆/项目文件）只出现在各项目的「项目数据」子目录里。
        val ps = Graph.projects.all()
        val known = ps.map { it.id }.toSet()
        val sessions = Graph.chat.sessions()

        // ---------- 未分类 ----------
        val unclassified = sessions
            .filter { (it.projectId == 0L || it.projectId !in known) && !it.archived }
            .sortedByDescending { it.updatedAt }
        val sessionDirs = unclassified.mapNotNull { s ->
            runCatching { ExportStore.existingDirFor(ctx, s.id) }.getOrNull()?.let { s to it }
        }
        val globalRoot = runCatching { ProjectFileRepo.dirOf(0L) }.getOrNull()
        val sessionDirPaths = sessionDirs.map { it.second.absolutePath }.toSet()
        val looseGlobal = globalRoot
            ?.listFiles { f -> f.isFile && !f.name.startsWith(".") }
            ?.filter { f -> sessionDirPaths.none { p -> f.absolutePath.startsWith("$p/") } }
            ?.sortedBy { it.name.lowercase() }
            .orEmpty()
        if (sessionDirs.isNotEmpty() || looseGlobal.isNotEmpty() || unclassified.isNotEmpty()) {
            val kids = mutableListOf<LibEntry>()
            sessionDirs.forEach { (s, dir) ->
                kids += LibEntry(
                    key = "u/s${s.id}", name = s.title.ifBlank { "新对话" }, isDir = true,
                    children = buildChildren(dir, "u/s${s.id}", -1L, "")
                )
            }
            looseGlobal.forEach { f -> kids += fileEntry(f, "u/g${f.name}", -1L, "", null) }
            out += LibEntry(key = "u", name = "未分类", isDir = true, children = kids)
        }

        // ---------- 各项目（2026-10-29：根 = 整个项目根，用户真实文件夹整棵都能看；
        // 「项目数据」钉在最前 + 数据图标，两边一眼可分）----------
        ps.forEach { p ->
            val dir = runCatching { ProjectDirs.root(p.id) }.getOrNull() ?: return@forEach
            // 项目根本身就是数据容器（未绑定项目）时，根层文件归 app 管；绑定项目则根层是用户区
            val isDataRoot = runCatching {
                ProjectDirs.data(p.id).canonicalPath == dir.canonicalPath
            }.getOrDefault(false)
            val kids = buildChildren(dir, "p${p.id}", p.id, "", if (isDataRoot) "" else null)
                .sortedByDescending { e -> e.isDir && e.name == ProjectDirs.DATA }
            out += LibEntry(
                key = "p${p.id}", name = p.name, isDir = true,
                children = kids,
                ownerPid = p.id, relPath = ""
            )
        }

        // ---------- 旧导出目录（v14.3 之前的扁平位置）----------
        val legacy = runCatching { ExportStore.legacyFiles(ctx, 200) }.getOrDefault(emptyList())
        if (legacy.isNotEmpty()) {
            out += LibEntry(
                key = "legacy", name = "旧导出目录", isDir = true,
                children = legacy.map { f -> fileEntry(f, "legacy/${f.name}", -1L, "", null) }
            )
        }
        out
    }

// ==================== 主屏 ====================

@Composable
fun FileLibraryScreen(
    onDone: () -> Unit,
    /**
     * v53.3「资料」Hub 的顶部切换器（记忆库 / 文件库 / 归档项目）；null = 本屏自己顶栏。
     * 与 ModelDisplayScreen / PluginMarketScreen / AutomationScreen / MemoryScreen 同一套嵌入契约。
     */
    topSwitcher: (@Composable () -> Unit)? = null,
    /**
     * 调用方已经套了 [PredictiveBackOverlay]（「资料」Hub 的做法）时为 true：
     * 本屏只画内容，不再自己开一层 Dialog——两个 Dialog 接连关+开会透出一帧宿主窗口，
     * 用户看到页面闪一下（见 ModelManagerScreen 同处注释）。
     */
    insideOverlay: Boolean = false
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val haptics = LocalHaptics.current
    val version by Graph.dataVersion.flow.collectAsState()
    var rootEntries by remember { mutableStateOf<List<LibEntry>>(emptyList()) }
    var projects by remember { mutableStateOf<List<Project>>(emptyList()) }
    var editing by remember { mutableStateOf<ProjectFile?>(null) }
    var showEditor by remember { mutableStateOf(false) }
    var showNewDir by remember { mutableStateOf(false) }
    /** 从根到当前目录的下钻链；空 = 根层（旧文件页：靠面包屑/返回逐层走，不是一整棵树） */
    var path by remember { mutableStateOf<List<LibEntry>>(emptyList()) }
    var fabMenu by remember { mutableStateOf(false) }
    var dirToDelete by remember { mutableStateOf<Pair<Long, String>?>(null) }
    var fileToDelete by remember { mutableStateOf<LibFile?>(null) }
    var renameFile by remember { mutableStateOf<LibFile?>(null) }
    var renameText by remember { mutableStateOf("") }
    var preview by remember { mutableStateOf<File?>(null) }
    var err by remember { mutableStateOf<String?>(null) }
    var showPcBridge by remember { mutableStateOf(false) }
    // 收藏/默认入口：KV「filelib.pin」存的是 key 链（如 "p7/d:项目数据|p7/d:项目数据/d:项目文件"），
    // 打开文件库时按链逐层下钻。非空 path 时点亮。
    var pinKeys by remember { mutableStateOf<String?>(null) }
    // 沙箱访问设置（2026-10-24 文件页签撤除，原文件页右上角的锁迁入这里）
    var showSandbox by remember { mutableStateOf(false) }
    // 2026-10-31：完整文件管理器（ui/files/FilesScreen）作为一层叠在本页上——
    // 根层「本机存储」进入，返回回到文件库。文件页签撤除后它成了死代码，这里接回来。
    var showManager by remember { mutableStateOf(false) }
    // 沙箱设置可能改变可见路径，回来后强制重扫（与 version 并列触发 LaunchedEffect）
    var sandboxTick by remember { mutableStateOf(0) }

    LaunchedEffect(version, sandboxTick) {
        projects = Graph.projects.all()
        rootEntries = runCatching { buildLibraryTree(ctx) }.getOrElse {
            err = it.message ?: "文件库读不出来"
            emptyList()
        }
        // 收藏/默认入口（2026-10-29 用户：顶部胶囊加收藏按钮，可设默认打开的路径）。
        // 只在还停在根层时跳：中途刷新不能把人从正在看的目录拽回收藏位置。
        if (path.isEmpty()) {
            val pin = Graph.settings.kvGet("filelib.pin")
            if (pin.isNullOrBlank()) {
                pinKeys = null
            } else {
                val chain = mutableListOf<LibEntry>()
                var cur = rootEntries
                var ok = true
                pin.split("|").filter { it.isNotBlank() }.forEach { k ->
                    if (!ok) return@forEach
                    val e = cur.firstOrNull { it.key == k }
                    if (e == null) ok = false else { chain += e; cur = e.children }
                }
                if (ok && chain.isNotEmpty()) {
                    path = chain
                    pinKeys = pin
                } else {
                    // 收藏的路径已经不存在（被删/改名）：回落根并清掉，别让按钮永远悬空
                    pinKeys = null
                    Graph.settings.kvDelete("filelib.pin")
                }
            }
        }
    }

    val current = path.lastOrNull()?.children ?: rootEntries
    fun descend(e: LibEntry) { path = path + e }
    fun popDir() { if (path.isNotEmpty()) path = path.dropLast(1) }

    // 返回键分层：先关预览、再退目录层级、最后才关文件库。原来无条件交给 onDone，结果在预览里按返回
    // 会把整个文件库一起关掉 —— 用户想回列表却直接被弹回上一层（2026-10-12 真机踩到）。
    // v53.3：抽成 shell lambda——「资料」Hub（AiSettingsHubs.kt）传 insideOverlay=true 时只画内容，
    // PredictiveBackOverlay 由 Hub 在最外层只套一层（防双 Dialog 闪帧，同 SettingsHub 注释）。
    val shell: @Composable () -> Unit = shell@{
        // 预览**必须画在这个 Dialog 窗口里**。画在 overlay 外面（主窗口）的话，
        // 文件库这个 Dialog 会把它整个盖住 —— 点文件看着像"没反应"（2026-10-12 真机踩到）。
        val opening = preview
        if (opening != null) {
            OfficeFilePreview(opening, onBack = { preview = null })
            return@shell
        }
        Scaffold(
            topBar = {
                Column {
                    TopAppBar(
                        title = { Text("文件库", fontWeight = FontWeight.Bold) },
                        navigationIcon = {
                            // 顶栏返回 = 中性点击 → Tap
                            // 2026-11-01：与 onBack（手势/系统返回）同一条分层规则——预览开着关预览、
                            // 目录内退层级，都不是"关页面"，不能再走 overlayClose 的收缩动画：
                            // 收缩只是把 fraction 动画到 1 再调 onBack，之前分层 pop 后覆盖层留着，
                            // 页面就永久卡在下陷收场态（2026-10-12 分层返回引入，用户复报多次）。
                            val overlayClose = LocalOverlayClose.current
                            IconButton(onClick = {
                                haptics.perform(HapticEngine.Kind.Tap)
                                when {
                                    preview != null -> preview = null
                                    path.isNotEmpty() -> popDir()
                                    else -> overlayClose()
                                }
                            }) {
                                Icon(AppIcons.Nav.Back, "返回", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        },
                        actions = {
                            // 沙箱访问设置：图标兼做状态指示——已就绪=开锁 / 未就绪=上锁
                            // （原文件页 v19.16 的入口，文件页签撤除后迁入）
                            IconButton(onClick = { showSandbox = true }) {
                                val ready = io.github.m0use001.planability.data.SandboxAccess.isReady()
                                Icon(
                                    if (ready) Icons.Rounded.LockOpen else Icons.Rounded.Lock,
                                    contentDescription = "沙箱访问设置",
                                    // R11：工具栏图标/状态图形都不染主色（状态由锁形表达）
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            IconButton(onClick = { showPcBridge = !showPcBridge }) {
                                Icon(
                                    AppIcons.Kind.Computer, "电脑端渲染设置",
                                    tint = if (showPcBridge) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)
                                )
                            }
                        }
                    )
                    if (topSwitcher != null) {
                        Column(Modifier.fillMaxWidth()) { topSwitcher() }
                    }
                }
            },
        ) { pad ->
            val listState = androidx.compose.foundation.lazy.rememberLazyListState()
            androidx.activity.compose.BackHandler(enabled = fabMenu || path.isNotEmpty()) {
                if (fabMenu) fabMenu = false else popDir()
            }
            Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().padding(pad)) {
                // ── 面包屑胶囊（旧文件页样式）：根层是「文件库」标签，进层后原位变返回键，
                // 面包屑可点跳层，整行一个胶囊、内容长了自己横向滚 ──
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
                        if (path.isEmpty()) {
                            Icon(
                                AppIcons.Setting.FileLibrary, null, Modifier.size(20.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.width(8.dp))
                        } else {
                            IconButton(
                                onClick = { haptics.perform(HapticEngine.Kind.Tap); popDir() },
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
                            val crumbs = buildList {
                                add("文件库" to -1)
                                path.forEachIndexed { i, e -> add(e.name to i) }
                            }
                            crumbs.forEachIndexed { i, crumb ->
                                val (label, idx) = crumb
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
                                                path = path.take(idx + 1)
                                            }
                                        }
                                        .padding(horizontal = 2.dp)
                                )
                            }
                        }
                        // 收藏 = 把当前路径记为文件库的默认打开入口；再点取消。
                        // R11：顶栏图形不染主色，状态由点亮/描边星形表达
                        val currentChain = path.joinToString("|") { it.key }
                        val pinned = path.isNotEmpty() && pinKeys == currentChain
                        IconButton(
                            onClick = {
                                haptics.perform(HapticEngine.Kind.Tap)
                                if (pinned) {
                                    pinKeys = null
                                    scope.launch { Graph.settings.kvDelete("filelib.pin") }
                                } else if (path.isNotEmpty()) {
                                    pinKeys = currentChain
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
                if (err != null) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
                    ) {
                        Text(
                            err!!, style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = { err = null }) { Text("知道了") }
                    }
                }
                if (showPcBridge) {
                    PcBridgeSettingCard()
                }
                val listState = androidx.compose.foundation.lazy.rememberLazyListState()
                LazyColumn(
                    Modifier.fillMaxSize().reachedEndHaptic(listState),
                    state = listState,
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    // 2026-10-31 用户：「本机存储入口与项目文件夹混放一起」——入口提为
                    // 独立的卡片区，与项目列表之间加分区头，一眼两段。
                    if (path.isEmpty()) {
                        item(key = "fs-entry") { StorageEntryCard(onOpen = { showManager = true }) }
                        item(key = "sec-projects") { SectionHeader("项目文件") }
                    }
                    if (current.isEmpty() && !showPcBridge) {
                        item(key = "empty") { EmptyHint("此文件夹为空") }
                    }
                    items(current.filter { it.key != "fs" }, key = { it.key }) { e ->
                        Box(Modifier.animateItem()) {
                            if (e.isDir) {
                                DirRow(
                                    e,
                                    onOpen = { descend(e) },
                                    // 2026-10-29：只有「项目数据」里的目录才可删（用户的目录只能看）
                                    onDelete = if (e.ownerPid >= 0 && !e.dataRel.isNullOrBlank()) {
                                        { dirToDelete = e.ownerPid to e.dataRel!! }
                                    } else null
                                )
                            } else {
                                val fl = e.file!!
                                FileRowView(
                                    fl,
                                    onOpen = {
                                        if (fl.projectId >= 0) {
                                            editing = ProjectFile(
                                                projectId = fl.projectId, name = fl.name, sub = fl.sub,
                                                content = readTextOrEmpty(fl.abs),
                                                size = fl.size.toInt(), updatedAt = fl.updatedAt
                                            )
                                            showEditor = true
                                        } else {
                                            preview = fl.abs
                                        }
                                    },
                                    // 用户的文件（项目数据之外）只能预览，不给删——
                                    // app 不动「项目数据」以外的东西，误删用户原件就是事故
                                    onDelete = if (fl.projectId >= 0) ({ fileToDelete = fl }) else null,
                                    onRename = if (fl.projectId >= 0) {
                                        { renameFile = fl; renameText = fl.name }
                                    } else null
                                )
                            }
                        }
                    }
                    item { Spacer(Modifier.height(96.dp)) }
                }
            }
            // B3-A（用户拍板）：官方 FloatingActionButtonMenu 替代「FAB + 手搓 DropdownMenu」
            // （M3E 规范：FabMenu 应取代 speed dial 与堆叠小 FAB）。官方摆放 = 内容 Box 对齐
            // BottomEnd、不占 Scaffold 的 FAB 槽——菜单展开时条目列在按钮上方生长。
            FloatingActionButtonMenu(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .navigationBarsPadding()
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
                        // 加号 ⇄ 关闭号随按压缩放形变（checkedProgress 由库的 expressive 弹簧驱动）
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
                    onClick = { fabMenu = false; editing = null; showEditor = true },
                    text = { Text("新建文件") },
                    icon = { Icon(AppIcons.Kind.File, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                )
                FloatingActionButtonMenuItem(
                    onClick = { fabMenu = false; showNewDir = true },
                    text = { Text("新建文件夹") },
                    icon = { Icon(AppIcons.Act.NewFolder, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                )
            }
            }
            // 2026-10-31：完整文件管理器覆盖层。**必须是同窗覆盖、不能再套一层
            // PredictiveBackOverlay**——那会开第二个 Dialog 窗口叠在文件库上，两层
            // PredictiveBackHandler 抢同一个返回事件，实测卡死在半途的"下陷"动画
            // （fraction 停在中途不归位）。同窗后返回键由 FilesScreen 自己的
            // BackHandler 链处理：目录内退层级、根层走 onBack 关掉本覆盖层。
            if (showManager) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .zIndex(3f)
                        .background(MaterialTheme.colorScheme.background)
                ) {
                    io.github.m0use001.planability.ui.files.FilesScreen(onBack = { showManager = false })
                }
            }
        }
    }
    // v53.3：Hub 借用时 overlay 已在最外层（防双 Dialog 闪帧）；独立打开时自己套。
    // onBack 分层（预览→目录→关页）交给 Hub 的 onBack；Hub 的 onBack 里也按同样的
    // 分层规则收口——FileLibraryScreen 自身不再各持一个返回入口。
    if (insideOverlay) shell() else PredictiveBackOverlay(
        onBack = {
            when {
                preview != null -> preview = null
                path.isNotEmpty() -> popDir()
                else -> onDone()
            }
        }
    ) { shell() }

    if (showEditor) {
        ProjectFileEditDialog(
            initial = editing,
            projects = projects,
            onDismiss = { showEditor = false },
            onSave = { f ->
                scope.launch {
                    // 磁盘写失败（同名目录占位、路径非法、无权限…）必须让用户看见，
                    // 否则「保存了但文件没进去」又是一桩查不到原因的玄案
                    err = runCatching { ProjectFileRepo.save(f) }.exceptionOrNull()?.message
                }
                showEditor = false
            }
        )
    }

    if (showNewDir) {
        NewDirDialog(
            onDismiss = { showNewDir = false },
            onCreate = { pid, path ->
                scope.launch {
                    val ok = ProjectFileRepo.mkdirs(pid, path) != null
                    err = if (ok) null else "建不出这个目录（路径非法，或被同名文件占了位置）"
                }
                showNewDir = false
            }
        )
    }

    // 沙箱访问设置（全屏覆盖层；回来后重扫目录，让新授权的路径立刻出现在列表里）
    if (showSandbox) {
        io.github.m0use001.planability.ui.files.SandboxSettingsScreen(
            onDone = { showSandbox = false; sandboxTick++ }
        )
    }

    // 2026-10-31：完整文件管理器覆盖层已移进上面的 PredictiveBackOverlay content（同窗）。
    // 旧实现在此处再套一层 PredictiveBackOverlay 开第二个 Dialog 窗口，两层返回手势
    // 抢事件，实测卡死在半途下陷动画——嵌套全屏子页绝不能再开嵌套窗口。

    dirToDelete?.let { (pid, path) ->
        AlertDialog(
            modifier = Modifier.motionEnter(),
            onDismissRequest = { dirToDelete = null },
            title = { Text("删除文件夹") },
            text = { Text("删除「$path」及其全部内容？此操作不可恢复。") },
            confirmButton = {
                TextButton(onClick = {
                    // 破坏性确认 → ImpactHeavy（与 ConfirmDialog 的 danger 分支同一档，
                    // 这里是裸 AlertDialog，补上同一手感，别让"删文件夹"比"删文件"轻）
                    haptics.perform(HapticEngine.Kind.ImpactHeavy)
                    scope.launch { ProjectFileRepo.deleteDir(pid, path) }
                    dirToDelete = null
                }) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { dirToDelete = null }) { Text("取消") } }
        )
    }

    fileToDelete?.let { f ->
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
                    else err = "删不掉这个文件（可能已被电脑端 Syncthing 同步走了）"
                }
                fileToDelete = null
            },
            onDismiss = { fileToDelete = null }
        )
    }

    // 重命名（旧文件页能力回归）：项目文件走 ProjectFileRepo.rename（原位替换，防穿越）
    renameFile?.let { f ->
        AlertDialog(
            modifier = Modifier.motionEnter(),
            onDismissRequest = { renameFile = null },
            title = { Text("重命名文件") },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    singleLine = true,
                    label = { Text("文件名（含扩展名）") },
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(
                    enabled = renameText.isNotBlank(),
                    onClick = {
                        scope.launch {
                            val ok = runCatching {
                                ProjectFileRepo.rename(f.projectId, f.name, renameText.trim(), f.sub)
                            }.getOrDefault(false)
                            if (!ok) err = "重命名失败：名称非法或同名文件已存在"
                        }
                        renameFile = null
                    }
                ) { Text("重命名") }
            },
            dismissButton = { TextButton(onClick = { renameFile = null }) { Text("取消") } }
        )
    }
}

internal fun readTextOrEmpty(f: File): String =
    runCatching { if (f.isFile && f.length() < 512 * 1024) f.readText(Charsets.UTF_8) else "" }
        .getOrDefault("")

// ==================== 行的绘制（旧文件页样式） ====================

/**
 * 「本机存储」入口卡（2026-10-31 用户：不要与项目行混放）——文件库根层的独立区块，
 * 点开完整文件管理器（ui/files/FilesScreen 同窗覆盖层）。卡样式（tonal 底 + 尾箭头）
 * 与普通目录行区分开：它不是可下钻的数据目录，是去另一个界面的门。
 */
@Composable
internal fun StorageEntryCard(onOpen: () -> Unit) {
    val haptics = LocalHaptics.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .pressScale()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .clickable { haptics.perform(HapticEngine.Kind.Tap); onOpen() }
            .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Icon(
            Icons.Rounded.PhoneAndroid,
            null, Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.onSecondaryContainer
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                "本机存储", style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )
            Text(
                "完整文件管理器 · 浏览全机文件",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )
        }
        Icon(
            AppIcons.Nav.Next, "进入", Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.onSecondaryContainer
        )
    }
}

/** 分区头（根层「本机存储」卡与项目列表之间的一段小标） */
@Composable
internal fun SectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 14.dp, top = 8.dp, bottom = 2.dp)
    )
}

/**
 * 目录行：点 = 进入下一层；长按 = 菜单（项目目录可删）。
 * 行样式对齐旧 ui/files/FilesScreen：pressScale 胶囊 + 图标 + 名称 + 计数，无缩进树。
 */@Composable
internal fun DirRow(e: LibEntry, onOpen: () -> Unit, onDelete: (() -> Unit)?) {
    val haptics = LocalHaptics.current
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .pressScale()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .combinedClickable(
                onClick = { haptics.perform(HapticEngine.Kind.Tap); onOpen() },
                onLongClick = if (onDelete != null) {
                    { haptics.perform(HapticEngine.Kind.ImpactLight); menuOpen = true }
                } else null
            )
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        // 「项目数据」用数据库形图标（用户 2026-10-28：与普通文件夹一眼区分）
        val isDataRoot = e.name == io.github.m0use001.planability.data.ProjectDirs.DATA && e.dataRel == ""
        Icon(
            if (isDataRoot) AppIcons.Kind.ProjectData else AppIcons.Kind.Folder,
            null, Modifier.size(18.dp),
            // R11：行首类型标记 → 中性
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                e.name, style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            Text(
                e.children.size.toString() + " 项",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (onDelete != null) {
            Box {
                IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(32.dp)) {
                    Icon(
                        AppIcons.Act.More, "更多", Modifier.size(17.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                androidx.compose.material3.DropdownMenu(
                    expanded = menuOpen,
                    onDismissRequest = { menuOpen = false }
                ) {
                    androidx.compose.material3.DropdownMenuItem(
                        modifier = Modifier.clip(RoundedCornerShape(10.dp)), // 2026-11-15 点击高亮圆角化（原来是整行直角方块）
                        text = { Text("进入", maxLines = 1) },
                        onClick = { menuOpen = false; haptics.perform(HapticEngine.Kind.Tap); onOpen() },
                        leadingIcon = { Icon(AppIcons.Kind.FolderOpen, null, Modifier.size(17.dp)) }
                    )
                    androidx.compose.material3.DropdownMenuItem(
                        modifier = Modifier.clip(RoundedCornerShape(10.dp)), // 2026-11-15 点击高亮圆角化（原来是整行直角方块）
                        text = { Text("删除文件夹", color = MaterialTheme.colorScheme.error) },
                        onClick = { menuOpen = false; haptics.perform(HapticEngine.Kind.ImpactHeavy); onDelete() },
                        leadingIcon = {
                            Icon(
                                AppIcons.Act.Delete, null, Modifier.size(17.dp),
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                    )
                }
            }
        }
    }
}

/** 文件行：点 = 打开（项目文件进编辑器，其余预览）；长按 = 菜单（打开/重命名/删除——后两者仅项目文件有） */
@Composable
internal fun FileRowView(
    r: LibFile,
    onOpen: () -> Unit,
    onDelete: (() -> Unit)?,
    onRename: (() -> Unit)?
) {
    val haptics = LocalHaptics.current
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .pressScale()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .combinedClickable(
                onClick = { haptics.perform(HapticEngine.Kind.Tap); onOpen() },
                onLongClick = {
                    haptics.perform(HapticEngine.Kind.ImpactLight)
                    menuOpen = true
                }
            )
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        officeFileIcon(r.abs, Modifier.size(17.dp))
        Spacer(Modifier.width(9.dp))
        Column(Modifier.weight(1f)) {
            Text(
                r.name, style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            Text(
                SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(r.updatedAt)) +
                    " · " + officeSizeLabel(r.size) +
                    if (r.projectId < 0) " · 只读" else "",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Box {
            IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(32.dp)) {
                Icon(
                    AppIcons.Act.More, "更多", Modifier.size(17.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            androidx.compose.material3.DropdownMenu(
                expanded = menuOpen,
                onDismissRequest = { menuOpen = false }
            ) {
                androidx.compose.material3.DropdownMenuItem(
                    modifier = Modifier.clip(RoundedCornerShape(10.dp)), // 2026-11-15 点击高亮圆角化（原来是整行直角方块）
                    text = { Text("打开", maxLines = 1) },
                    onClick = { menuOpen = false; haptics.perform(HapticEngine.Kind.Tap); onOpen() },
                    leadingIcon = { Icon(AppIcons.Act.Open, null, Modifier.size(17.dp)) }
                )
                if (onRename != null) {
                    androidx.compose.material3.DropdownMenuItem(
                        modifier = Modifier.clip(RoundedCornerShape(10.dp)), // 2026-11-15 点击高亮圆角化（原来是整行直角方块）
                        text = { Text("重命名", maxLines = 1) },
                        onClick = { menuOpen = false; haptics.perform(HapticEngine.Kind.Tap); onRename() },
                        leadingIcon = { Icon(AppIcons.Act.Rename, null, Modifier.size(17.dp)) }
                    )
                }
                if (onDelete != null) {
                    androidx.compose.material3.DropdownMenuItem(
                        modifier = Modifier.clip(RoundedCornerShape(10.dp)), // 2026-11-15 点击高亮圆角化（原来是整行直角方块）
                        text = { Text("删除", color = MaterialTheme.colorScheme.error) },
                        onClick = { menuOpen = false; haptics.perform(HapticEngine.Kind.ImpactHeavy); onDelete() },
                        leadingIcon = {
                            Icon(
                                AppIcons.Act.Delete, null, Modifier.size(17.dp),
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                    )
                }
            }
        }
    }
}

// ==================== 新建 / 编辑 ====================

/** 新建文件夹：选归属 + 填路径（可多级 `a/b`，不存在会自动建） */
@Composable
internal fun NewDirDialog(onDismiss: () -> Unit, onCreate: (Long, String) -> Unit) {
    // 归属选择 chips = 选择 → Select
    val haptics = LocalHaptics.current
    var pid by remember { mutableStateOf(0L) }
    var path by remember { mutableStateOf("") }
    var projects by remember { mutableStateOf<List<Project>>(emptyList()) }
    LaunchedEffect(Unit) { projects = Graph.projects.all() }
    AlertDialog(
        modifier = Modifier.motionEnter(),
        onDismissRequest = onDismiss,
        title = { Text("新建文件夹") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    "归属", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                Row(Modifier.horizontalScroll(rememberScrollState())) {
                    androidx.compose.material3.FilterChip(
                        colors = filterChipColors(),
                        selected = pid == 0L, onClick = { pid = 0L; haptics.perform(HapticEngine.Kind.Select) }, label = { Text("未分类") }
                    )
                    Spacer(Modifier.width(6.dp))
                    projects.forEach { p ->
                        androidx.compose.material3.FilterChip(
                            colors = filterChipColors(),
                            selected = pid == p.id, onClick = { pid = p.id; haptics.perform(HapticEngine.Kind.Select) }, label = { Text(p.name) }
                        )
                        Spacer(Modifier.width(6.dp))
                    }
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = path, onValueChange = { path = it },
                    label = { Text("目录路径") }, placeholder = { Text("如 笔记/2026，留空=根目录") },
                    singleLine = true, modifier = Modifier.fillMaxWidth()
                )
                val dirs = remember(path, pid) { ProjectFileRepo.subDirs(pid) }
                if (dirs.isNotEmpty()) {
                    Text(
                        "已有目录（点一下填进来）", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(Modifier.horizontalScroll(rememberScrollState())) {
                        dirs.forEach { d ->
                            androidx.compose.material3.AssistChip(
                                onClick = { path = if (path.isBlank()) d else "$path/$d" },
                                label = { Text(d, maxLines = 1, softWrap = false) }
                            )
                            Spacer(Modifier.width(6.dp))
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = path.isNotBlank(),
                onClick = { onCreate(pid, path.trim()) }
            ) { Text("创建") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable
internal fun ProjectFileEditDialog(
    initial: ProjectFile?,
    projects: List<Project>,
    onDismiss: () -> Unit,
    onSave: (ProjectFile) -> Unit
) {
    // 归属选择 chips = 选择 → Select
    val haptics = LocalHaptics.current
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var content by remember { mutableStateOf(initial?.content ?: "") }
    var projectId by remember { mutableStateOf(initial?.projectId ?: 0L) }
    // 目录：空 = 目录根。可以敲 `笔记/2026` 这种多级路径，保存时会自动建
    var sub by remember { mutableStateOf(initial?.sub ?: "") }
    AlertDialog(
        modifier = Modifier.motionEnter(),
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "新建文件" else "编辑文件") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = name, onValueChange = { name = it }, label = { Text("名称") },
                    singleLine = true, modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "归属", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                    androidx.compose.material3.FilterChip(
                        colors = filterChipColors(),
                        selected = projectId == 0L,
                        onClick = { projectId = 0L; sub = ""; haptics.perform(HapticEngine.Kind.Select) },
                        label = { Text("未分类") }
                    )
                    Spacer(Modifier.width(6.dp))
                    projects.forEach { p ->
                        androidx.compose.material3.FilterChip(
                            colors = filterChipColors(),
                            selected = projectId == p.id,
                            onClick = { projectId = p.id; sub = ""; haptics.perform(HapticEngine.Kind.Select) },
                            label = { Text(p.name) }
                        )
                        Spacer(Modifier.width(6.dp))
                    }
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = sub, onValueChange = { sub = it },
                    label = { Text("所在目录") },
                    placeholder = { Text("留空 = 目录根；可写 笔记/2026") },
                    singleLine = true, modifier = Modifier.fillMaxWidth()
                )
                val dirs = remember(sub, projectId) { ProjectFileRepo.subDirs(projectId) }
                if (dirs.isNotEmpty()) {
                    Row(Modifier.horizontalScroll(rememberScrollState())) {
                        dirs.forEach { d ->
                            androidx.compose.material3.AssistChip(
                                onClick = { sub = if (sub.isBlank()) d else "$sub/$d" },
                                label = { Text(d, maxLines = 1, softWrap = false) }
                            )
                            Spacer(Modifier.width(6.dp))
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = content, onValueChange = { content = it }, label = { Text("内容") },
                    minLines = 4, maxLines = 10, modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank(),
                onClick = {
                    onSave(
                        (initial ?: ProjectFile(projectId = projectId, name = name.trim()))
                            .copy(
                                projectId = projectId, name = name.trim(), content = content,
                                sub = sub.trim().trim('/')
                            )
                    )
                }
            ) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}
