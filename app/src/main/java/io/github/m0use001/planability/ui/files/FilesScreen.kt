package io.github.m0use001.planability.ui.files

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.os.StatFs
import android.webkit.MimeTypeMap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.BookmarkBorder
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DriveFileRenameOutline
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Sync
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import io.github.m0use001.planability.core.HapticEngine
import io.github.m0use001.planability.core.LocalHaptics
import io.github.m0use001.planability.core.reachedEndHaptic
import io.github.m0use001.planability.data.Graph
import io.github.m0use001.planability.ui.common.ConfirmDialog
import io.github.m0use001.planability.ui.common.EmptyHint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import io.github.m0use001.planability.ui.common.motionEnter
import io.github.m0use001.planability.ui.common.pressScale
import io.github.m0use001.planability.ui.theme.ShapeTokens

private val SYNC_PACKAGES = listOf(
    "com.nutomic.syncthingandroid",
    "com.github.catfriend1.syncthingandroid"
)

/**
 * 文件页：完整文件管理器 v1（浏览/新建/重命名/删除/复制剪切粘贴/打开/详情）+ Syncthing 入口。
 *
 * [onBack]：作为覆盖层（文件库 →「本机存储」）打开时的退出回调——顶栏出返回键，
 * 根目录按返回也走它。当年它是底栏页签，根层没有"上一级"，默认 null 时行为完全不变。
 *
 * [forceSinglePane]：v77 S1b 作为文件库钻入栈的一块**单板瓦片**打开时传 true——
 * 瓦片槽宽在超宽窗口下可能超过本页的两板阈值，那时本页会在一块板里画出自己的
 * 两块板（框套框，禁）。钉死单板形态（内部目录下钻照旧）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilesScreen(onBack: (() -> Unit)? = null, forceSinglePane: Boolean = false) {
    val context = LocalContext.current
    val haptics = LocalHaptics.current
    val scope = rememberCoroutineScope()
    var granted by remember { mutableStateOf(Environment.isExternalStorageManager()) }
    // 全权限根 = 内部存储；受限模式根 = 应用专属目录（无需权限，功能完整）
    val fullRoot = remember { Environment.getExternalStorageDirectory() }
    val appRoot = remember { context.getExternalFilesDir(null) ?: context.filesDir }
    val root = if (granted) fullRoot else appRoot
    var dir by remember { mutableStateOf(root) }
    var items by remember { mutableStateOf<List<File>>(emptyList()) }
    var tick by remember { mutableLongStateOf(0) }
    var sandboxTick by remember { mutableStateOf(0) }   // v19.17 沙箱状态变化计数
    var clipboard by remember { mutableStateOf<Pair<File, Boolean>?>(null) } // file, isCut
    var actionTarget by remember { mutableStateOf<File?>(null) }
    var showRename by remember { mutableStateOf<File?>(null) }
    var renameText by remember { mutableStateOf("") }
    var showNew by remember { mutableStateOf(false) }
    var newKindFolder by remember { mutableStateOf(false) }
    var viewer by remember { mutableStateOf<File?>(null) }
    var newFolderName by remember { mutableStateOf("") }
    var confirmDelete by remember { mutableStateOf<File?>(null) }
    var showDetails by remember { mutableStateOf<File?>(null) }
    var showSync by remember { mutableStateOf(false) }
    // v19.16：沙箱访问设置页（右上角入口）。统一走文件页，不再单开「其他应用数据」浏览页
    var showSandbox by remember { mutableStateOf(false) }
    // v76：宽窄判定上提——宽屏同步中心/沙箱改由 BoardDrill 钻入承载，窄屏覆盖层据此兜底防双渲染。
    var filesTwoPane by remember { mutableStateOf(false) }
    var toast by remember { mutableStateOf<String?>(null) }

    fun reload() {
        val d = dir
        // 在主线程判定一次：state() 会注册广播，放到 IO 里调容易出意外，
        // 而且 reload 每次翻目录都会跑，正好靠这里的线程边界把它固定在主线程。
        val sandbox = io.github.m0use001.planability.data.SandboxAccess.isReady()
        scope.launch(Dispatchers.IO) {
            var list = (d.listFiles() ?: emptyArray())
                .filter { !it.name.startsWith(".") }

            // v19.16 **统一访问**：普通 File 读不到的目录（典型是别的 App 的 Android/data，
            // Android 11+ 作用域存储硬规则、MANAGE_EXTERNAL_STORAGE 也不管用）
            // 改走特权通道，用户点进同一个目录、看同一个列表，不需要换页面。
            // **只在已就绪时**才走特权通道。
            // 没就绪时 listDir 会拿一条 15s 超时的连接去握手，而握手要等用户点允许；
            // 一旦它超时，AdbShellClient.closeQuietly() 会把**整条连接**关掉，
            // 连带把 refresh 的握手一起作废 —— 于是不断重来、不断弹授权框，
            // refresh 的诊断日志一次都打不出来。
            if (list.isEmpty() && d.isDirectory &&
                io.github.m0use001.planability.data.SandboxAccess.isReady()
            ) {
                val names = io.github.m0use001.planability.data.SandboxAccess.listDir(d.absolutePath)
                if (names != null) {
                    list = names.filter { !it.startsWith(".") }.map { File(d, it) }
                }
            }

            // 未授权时先隐藏 Android/data —— 没权限点进去也是死路，
            // 先不给入口，等授权后自己出现。（Android/obb 同理，暂时按需求只隐藏 data。）
            if (!sandbox && d.absolutePath.trimEnd('/').endsWith("/Android")) {
                list = list.filterNot { it.name == "data" }
            }

            val sorted = list
                .sortedWith(compareBy<File> { !it.isDirectory }.thenBy { it.name.lowercase() })
            withContext(Dispatchers.Main) { items = sorted }
        }
    }

    androidx.compose.runtime.LaunchedEffect(dir, tick, granted, sandboxTick) { reload() }

    // v19.17：**这里不再自动连沙箱通道。**
    // 原来这里是 `LaunchedEffect(tick) { refresh() }`，每进一次文件页就发起
    // 一次连接——而每次连接尝试都会在手机上留一个「允许 USB 调试吗？」弹窗，
    // adbd 又只服务单 host、新连接必是新挑战，于是弹窗刷屏（用户反馈
    // 「一直反复弹窗调试申请，严重干扰开发」）。
    // 现在改为：**只有用户在沙箱设置页主动点「重新检测」才发起连接**。
    // sandboxTick 仍保留，由设置页返回时 bump，让 Android/data 显隐跟着状态走。
    // v13：默认打开路径（进入文件页直达设置层级）；授权状态变化时在有效范围内重算落点
    var defaultPath by remember { mutableStateOf<String?>(null) }
    androidx.compose.runtime.LaunchedEffect(granted) {
        defaultPath = Graph.settings.filesDefaultPath().ifBlank { null }
        dir = defaultPath
            ?.takeIf { it.startsWith(root.absolutePath) }
            ?.let(::File)
            ?.takeIf { it.isDirectory }
            ?: root
    }

    // 授权后从设置返回时刷新
    DisposableEffect(Unit) {
        val activity = context as? androidx.activity.ComponentActivity
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_RESUME) {
                granted = Environment.isExternalStorageManager()
            }
        }
        activity?.lifecycle?.addObserver(obs)
        onDispose { activity?.lifecycle?.removeObserver(obs) }
    }

    fun doDelete(f: File) {
        scope.launch(Dispatchers.IO) {
            deleteRecursive(f)
            withContext(Dispatchers.Main) { tick++; toast = "已删除 ${f.name}" }
        }
    }

    fun doPaste() {
        val (src, cut) = clipboard ?: return
        val target = File(dir, src.name)
        if (target.absolutePath == src.absolutePath) {
            toast = "源与目标相同"; return
        }
        scope.launch(Dispatchers.IO) {
            runCatching { copyRecursive(src, target) }
                .onSuccess {
                    if (cut) deleteRecursive(src)
                    withContext(Dispatchers.Main) {
                        if (cut) clipboard = null
                        tick++; toast = if (cut) "已移动" else "已粘贴"
                    }
                }
                .onFailure { withContext(Dispatchers.Main) { toast = "操作失败：${it.message}" } }
        }
    }

    // v57 平板双栏：列表体抽成 filesBody（含 Box 与返回手势）——手机全屏（原路径零变化），
    // 平板作双栏左栏，预览（viewer）进右栏（见下方挂载处的宽屏分支）。
    // v64（2026-11-20）：断点不再用窗口宽（isWide=600dp 时右栏 520dp → 左栏只剩 80dp），
    // 改成**量内容宽**再问共享决策机 MasterDetail：内容宽 − 详情宽 ≥ 380 才分栏。
    // `inBoard` = 这一份正在两板里的左板里渲染（板已提供 statusBarsPadding，别叠）。
    val filesBody: @Composable (Boolean) -> Unit = { inBoard ->
    Box(Modifier.fillMaxSize()) {
        // v12：进入非顶层文件夹隐藏底栏；返回手势（全面屏侧滑）= 上一级
        // v68：锁经 LockShellChrome 按 tab 记账、返回手势按 LocalTabActive 门控
        // （文件库常驻在信息页的组合里，别的 tab 上不得吃返回）。
        val filesTabActive = io.github.m0use001.planability.core.LocalTabActive.current
        if (dir != root) io.github.m0use001.planability.core.LockShellChrome()
        androidx.activity.compose.BackHandler(
            enabled = filesTabActive && dir != root && viewer == null && !showSync && !showSandbox
        ) { dir = dir.parentFile ?: root }
        // 覆盖层模式（onBack != null）：根层返回 = 退出文件管理器。
        // 单独一条而不是改上面那条：上面的条件在子目录时才启用，两条件互斥，不会双触发。
        androidx.activity.compose.BackHandler(enabled = filesTabActive && dir == root && onBack != null) { onBack?.invoke() }
        Column(
            Modifier.fillMaxSize().then(if (inBoard) Modifier else Modifier.statusBarsPadding())
        ) {
            io.github.m0use001.planability.ui.common.PageHeader(
                "文件",
                leading = onBack?.let {
                    {
                        io.github.m0use001.planability.ui.common.HapticBackButton(it)
                    }
                },
                trailing = {
                    // v19.44：三颗图标**给同一个常量 tint**，跟笔记页顶栏同一惯例。
                    // 原来粘贴按「有没有内容」在 primary / onSurfaceVariant 之间跳、
                    // 沙箱按「就绪没」同样跳、新建走默认 —— 三颗三个亮度，站一起像三种操作等级。
                    // 现在显式 tint 还有个作用：它会**覆盖 IconButton 禁用态的自动压暗**，
                    // 于是没东西可粘贴时粘贴那颗也是同样亮度（用户 10-16 要的就是"三颗一样亮"）。
                    // 语义没丢：它仍然 `enabled = clipboard != null`，点不动、无障碍也报 disabled；
                    // 状态由**图标形状**表达（沙箱用开锁/上锁）。
                    val headerIcon = MaterialTheme.colorScheme.onSurface
                    IconButton(
                        onClick = { doPaste() },
                        enabled = clipboard != null,
                        modifier = Modifier.pressScale()
                    ) {
                        Icon(Icons.Rounded.ContentPaste, contentDescription = "粘贴", tint = headerIcon)
                    }
                    IconButton(onClick = { newFolderName = ""; showNew = true }, modifier = Modifier.pressScale()) {
                        Icon(Icons.Rounded.CreateNewFolder, contentDescription = "新建", tint = headerIcon)
                    }
                    // v19.16：沙箱访问设置入口。图标兼做状态指示——
                    // 已就绪=开锁 / 未就绪=上锁，一眼能看出「别的 App 的 Android/data 现在能不能看」。
                    // 点进去是设置页，**不是**另一个浏览页：所有文件都走本页的统一列表。
                    IconButton(onClick = { showSandbox = true }, modifier = Modifier.pressScale()) {
                        val ready = io.github.m0use001.planability.data.SandboxAccess.isReady()
                        Icon(
                            if (ready) Icons.Rounded.LockOpen else Icons.Rounded.Lock,
                            contentDescription = "沙箱访问设置",
                            tint = headerIcon
                        )
                    }
                }
            )

            if (!granted) {
                Card(
                    shape = ShapeTokens.card,
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(12.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text("受限模式：仅应用文件夹", style = MaterialTheme.typography.titleSmall)
                            Text(
                                "授予「所有文件访问」后可管理全机文件",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        TextButton(onClick = {
                            runCatching {
                                context.startActivity(
                                    Intent(
                                        Settings_ACTION_MANAGE_ALL,
                                        Uri.parse("package:${context.packageName}")
                                    )
                                )
                            }.onFailure {
                                runCatching {
                                    context.startActivity(Intent(Settings_ACTION_MANAGE_ALL_FALLBACK))
                                }
                            }
                        }) { Text("去授权") }
                    }
                }
            }

            // 路径导航（v12：完整面包屑路径，段可点跳转）
            //
            // v19.18：**根目录时把「存储占比」并进这一行，收成一个胶囊**。
            // 原来是「内部存储」一行 + 下面再挂一个占比胶囊一行，根目录下
            // 两行都写着「内部存储」，重复且散。现在下面那行没了，
            // 面包屑标题和占比是**同一个胶囊里的一个整体**：
            //     [环] 内部存储            159.5 GB / 489.2 GB  32% [书签]
            Row(
                    verticalAlignment = Alignment.CenterVertically,
                    // **必须给 4dp 垂直外边距**：同步栏(Card)自带 4dp、文件行自带 4dp，
                    // 三方两两之间的间隙才能都是 4+4=8dp。
                    // 原来这行没有垂直 padding，「胶囊 → 同步栏」只有 4dp，
                    // 用户看到的正是「顶部胶囊、同步栏、文件行没等距」。
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)
                ) {
            // 胶囊**始终存在**（根目录和子目录都在）：根目录放存储环，
            // 进了子目录就**把环换成返回按钮** —— 位置不变、形状不变，
            // 右边一律显示路径面包屑，所以这行一直待在同一个地方不跳动。
            Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(percent = 50))   // 胶囊
                            .background(MaterialTheme.colorScheme.surfaceContainer)
                            .padding(start = 8.dp, end = 4.dp,
                                top = 4.dp, bottom = 4.dp)
                    ) {
                val stat = rememberStorageInfo(root)
                if (dir == root) {
                    if (stat != null) {
                        // v72：环收编成 ui/common 共享词汇（PaneBlocks.CompletionRing）
                        io.github.m0use001.planability.ui.common.CompletionRing(stat.usedFrac, sizeDp = 24, strokeDp = 3)
                        Spacer(Modifier.width(8.dp))
                    }
                } else {
                    // 上一级 = 顶栏返回语义（中性轻触 Tap），和列表点击的 Tap 同档。
                    // 用 IconButton 但**显式压到 30dp**，否则默认 48dp 会把胶囊撑高，
                    // 跟根目录那一版不是同一个高度。
                    IconButton(
                        onClick = { haptics.perform(HapticEngine.Kind.Tap); dir = dir.parentFile ?: root },
                        modifier = Modifier.size(30.dp)
                    ) {
                        // R11：上一级 = 导航图标 → 中性
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "上一级",
                            modifier = Modifier.size(17.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Spacer(Modifier.width(4.dp))
                }
                // 只留「环 + 目录名 + 百分比」一行，**不放 GB 占用数字**
                // （用户要求压扁；占用数字放进来必然把目录名挤到截断）。
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .weight(1f)
                        .horizontalScroll(rememberScrollState())
                ) {
                        val crumbs = remember(dir, root) {
                            buildList {
                                var f: File? = dir
                                while (f != null && f.absolutePath != root.absolutePath) {
                                    add(f); f = f.parentFile
                                }
                                add(root)
                                reverse()
                            }
                        }
                        crumbs.forEachIndexed { i, f ->
                            if (i > 0) {
                                Text(
                                    " / ",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            val isLast = i == crumbs.lastIndex
                            Text(
                                if (f == root) (if (granted) "内部存储" else "应用文件") else f.name,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = if (isLast) MaterialTheme.colorScheme.onSurface
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable {
                                    if (!isLast) dir = f
                                }.padding(horizontal = 2.dp)
                            )
                        }
                        }   // 面包屑滚动区结束
                // 占比数字**去掉了**（用户要求）：环本身就是占比，
                // 再写一个 32% 只是重复，还把胶囊撑高。
                // v13：书签——把当前文件夹设为默认打开路径（再点一次取消）
                IconButton(
                    onClick = {
                    haptics.perform(HapticEngine.Kind.Select)
                    scope.launch {
                        if (defaultPath == dir.absolutePath) {
                            Graph.settings.setFilesDefaultPath("")
                            defaultPath = null
                            toast = "已取消默认路径"
                        } else {
                            Graph.settings.setFilesDefaultPath(dir.absolutePath)
                            defaultPath = dir.absolutePath
                            toast = "已设为默认打开路径"
                        }
                    }
                },
                    // 默认 48dp 会把胶囊撑到 48dp 高；压到 32dp 才叫"继续压扁"。
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        if (defaultPath == dir.absolutePath) Icons.Rounded.Bookmark
                        else Icons.Rounded.BookmarkBorder,
                        contentDescription = "默认打开路径",
                        modifier = Modifier.size(17.dp),
                        tint = if (defaultPath == dir.absolutePath) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }   // 胶囊结束
        }   // 路径导航整行结束

                if (dir == root) {
                    // 存储占比已并进上面那一行胶囊（见「路径导航」处），
                    // 不再单独占一行 —— 否则根目录会同时出现两个「内部存储」。
                    SyncthingCard(context) { showSync = true }
                }

                val listState = androidx.compose.foundation.lazy.rememberLazyListState()
                // 再点一次「文件」图标 → 文件列表回到顶部（含子目录，返回上一级不重置）
                io.github.m0use001.planability.app.ScrollToTopOnReselect("files", listState)
                LazyColumn(
                    Modifier.fillMaxSize().padding(horizontal = 12.dp).reachedEndHaptic(listState),
                    state = listState
                ) {
                    if (items.isEmpty()) {
                        item { EmptyHint("此文件夹为空") }
                    }
                    items(items, key = { it.absolutePath }) { f ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.animateItem()
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                                .pressScale()
                                .clip(ShapeTokens.listRow)
                                .background(MaterialTheme.colorScheme.surfaceContainer)
                                .combinedClickable(
                                    onClick = {
                                        haptics.perform(HapticEngine.Kind.Tap)
                                        when {
                                            f.isDirectory -> dir = f
                                            isPreviewable(f.name) -> viewer = f
                                            else -> actionTarget = f
                                        }
                                    },
                                    onLongClick = {
                                        // 长按 = 打开操作面板，按 Haptics.kt 五档表用 ImpactLight
                                        haptics.perform(HapticEngine.Kind.ImpactLight)
                                        actionTarget = f
                                    }
                                )
                                .padding(horizontal = 12.dp, vertical = 10.dp)
                        ) {
                            Icon(
                                imageVector = if (f.isDirectory) Icons.Rounded.Folder else Icons.Rounded.Description,
                                contentDescription = null,
                                // R11：文件夹/文件都是"类型标记"，不给其中一个染主色
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(22.dp)
                            )
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(f.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    if (f.isDirectory) "目录" else "${formatSize(f.length())} · " +
                                        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(f.lastModified())),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                    item { Spacer(Modifier.height(io.github.m0use001.planability.app.navAvoidPadding().calculateBottomPadding())) }
                }
        }

        // v13.1：新建只保留顶栏按钮（原 FAB 与顶栏重复）

        toast?.let { msg ->
            androidx.compose.material3.Surface(
                color = MaterialTheme.colorScheme.inverseSurface,
                contentColor = MaterialTheme.colorScheme.inverseOnSurface,
                shape = ShapeTokens.card,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = io.github.m0use001.planability.app.navAvoidPadding().calculateBottomPadding() + 14.dp)
            ) {
                Text(msg, Modifier.padding(horizontal = 16.dp, vertical = 10.dp), style = MaterialTheme.typography.bodySmall)
            }
            LaunchedEffectOneShot { toast = null }
        }
    }
    }   // filesBody 关闭（v57 平板双栏）

    if (showSync && !filesTwoPane) {
        io.github.m0use001.planability.ui.files.SyncthingScreen(onDone = { showSync = false })
    }

    // v19.16：沙箱访问设置（右上角的锁进入），见 SandboxSettingsScreen.kt
    if (showSandbox && !filesTwoPane) {
        // 返回时 bump tick，让 reload() 重跑：授权成功后 Android/data 才会重新出现
        io.github.m0use001.planability.ui.files.SandboxSettingsScreen(onDone = { showSandbox = false; tick++ })
    }


    // 应用内预览/编辑（按扩展名路由：文本编辑 / PDF / 图片 / Office）
    // v64（2026-11-20）：改成共享的两板主从——左板=当前目录，右板=预览（内容型 380~480dp），
    // 未选中=右板给目录概览（不许空着）。手机仍是原来的全屏 overlay。
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
        val contentW = maxWidth
        val detailW = io.github.m0use001.planability.ui.common.MasterDetail
            .detailWidthFor(contentW, content = true)
        val pane = io.github.m0use001.planability.ui.common.MasterDetail
            .paneOf(contentW, detailW, hasSelection = viewer != null)
        // forceSinglePane（v77 S1b）：作为文件库的单板瓦片打开时禁两板（防框套框）
        val twoPane = !forceSinglePane && pane != io.github.m0use001.planability.ui.common.DetailPane.Single
        // 右板在时，系统返回先收回预览，而不是退出文件管理器。
        // v68：按 LocalTabActive 门控（本页常驻组合，别的 tab 上不得吃返回）。
        androidx.activity.compose.BackHandler(
            enabled = twoPane && viewer != null &&
                io.github.m0use001.planability.core.LocalTabActive.current
        ) { viewer = null }
        filesTwoPane = twoPane
        val filesSub = when {
            showSync -> "sync"
            showSandbox -> "sandbox"
            else -> null
        }
        if (twoPane) {
            // v76（「二级+全量纳入钻入」）：宽屏主从两板根改走 BoardDrill——列表主板 + 预览/概览副板；
            // 同步中心 / 沙箱访问从整页覆盖层改为钻进主板（上一页列表缩副框、Rail 常驻）。level=0 几何 == SplitBoards。
            io.github.m0use001.planability.ui.common.BoardDrill(
                detailKey = filesSub,
                onBack = { showSync = false; showSandbox = false },
                detailWidth = detailW,
                detail = { k ->
                    when (k) {
                        "sync" -> io.github.m0use001.planability.ui.files.SyncthingScreen(
                            onDone = { showSync = false }, deferBack = true
                        )
                        else -> io.github.m0use001.planability.ui.files.SandboxSettingsScreen(
                            onDone = { showSandbox = false; tick++ }, deferBack = true
                        )
                    }
                },
                main = { filesBody(true) },
                secondary = {
                    when (pane) {
                        io.github.m0use001.planability.ui.common.DetailPane.Detail ->
                            FileViewerRoute(
                                file = viewer!!,
                                onClose = { viewer = null; tick++ },
                                embedded = true
                            )
                        else -> FilesOverviewPane(dir = dir, items = items, onPick = { viewer = it })
                    }
                }
            )
        } else {
            io.github.m0use001.planability.ui.common.SplitBoards(
                twoPane = false,
                primary = { filesBody(false) }
            )
            viewer?.let { f ->
                FileViewerRoute(file = f, onClose = { viewer = null; tick++ })
            }
        }
    }

    // ---------- 文件操作 ----------

    actionTarget?.let { f ->
        AlertDialog(
            onDismissRequest = { actionTarget = null },
            modifier = Modifier.motionEnter(),
            title = { Text(f.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
            text = {
                Text(if (f.isDirectory) "目录 · " + countEntries(f) + " 项" else formatSize(f.length()))
            },
            confirmButton = {
                Column {
                    if (!f.isDirectory) {
                        TextButton(onClick = {
                            actionTarget = null
                            val uri = FileProvider.getUriForFile(
                                context, "${context.packageName}.fileprovider", f
                            )
                            val mime = mimeOf(f.name)
                            runCatching {
                                context.startActivity(
                                    Intent(Intent.ACTION_VIEW)
                                        .setDataAndType(uri, mime)
                                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                )
                            }.onFailure { toast = "没有可以打开此文件的应用" }
                        }) {
                            Icon(Icons.Rounded.OpenInNew, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("打开")
                        }
                    }
                    TextButton(onClick = { renameText = f.name; showRename = f; actionTarget = null }) {
                        Icon(Icons.Rounded.DriveFileRenameOutline, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("重命名")
                    }
                    TextButton(onClick = {
                        clipboard = f to false; actionTarget = null; toast = "已复制，选择粘贴"
                    }) {
                        Icon(Icons.Rounded.ContentCopy, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("复制")
                    }
                    TextButton(onClick = {
                        clipboard = f to true; actionTarget = null; toast = "已剪切，选择粘贴"
                    }) {
                        Icon(Icons.Rounded.ContentPaste, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("剪切")
                    }
                    TextButton(onClick = { confirmDelete = f; actionTarget = null }) {
                        // 2026-10-25：菜单里的删除项中性化（同条菜单的其它项也是中性），
                        // 红色留给确认对话框（confirmDelete 那个 AlertDialog）。
                        Icon(Icons.Rounded.Delete, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(8.dp)); Text("删除", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (f.isDirectory) {
                        TextButton(onClick = {
                            scope.launch { Graph.settings.setFilesDefaultPath(f.absolutePath) }
                            defaultPath = f.absolutePath
                            actionTarget = null
                            toast = "已设为默认打开路径"
                        }) {
                            Icon(Icons.Rounded.Bookmark, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("设为默认路径")
                        }
                    }
                    TextButton(onClick = { showDetails = f; actionTarget = null }) {
                        Icon(Icons.Rounded.Info, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("详情")
                    }
                }
            },
            dismissButton = { TextButton(onClick = { actionTarget = null }) { Text("取消") } }
        )
    }

    showRename?.let { f ->
        AlertDialog(
            onDismissRequest = { showRename = null },
            modifier = Modifier.motionEnter(),
            title = { Text("重命名") },
            text = {
                OutlinedTextField(value = renameText, onValueChange = { renameText = it }, singleLine = true)
            },
            confirmButton = {
                TextButton(onClick = {
                    val target = File(f.parentFile, renameText.trim())
                    if (renameText.isNotBlank() && target.absolutePath != f.absolutePath) {
                        scope.launch(Dispatchers.IO) {
                            runCatching { f.renameTo(target) }
                                .onSuccess { ok ->
                                    withContext(Dispatchers.Main) {
                                        tick++
                                        if (!ok) toast = "重命名失败"
                                    }
                                }
                        }
                    }
                    showRename = null
                }) { Text("确定") }
            },
            dismissButton = { TextButton(onClick = { showRename = null }) { Text("取消") } }
        )
    }

    if (showNew) {
        AlertDialog(
            onDismissRequest = { showNew = false },
            modifier = Modifier.motionEnter(),
            title = { Text("新建") },
            text = {
                Column {
                    // M3E connected button group（替代已弃用的 SegmentedButton；切换触感由组件内置）
                    io.github.m0use001.planability.ui.common.AppButtonGroup(
                        options = listOf("文件", "文件夹"),
                        selectedIndex = if (newKindFolder) 1 else 0,
                        onSelect = { newKindFolder = it == 1 }
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = newFolderName,
                        onValueChange = { newFolderName = it },
                        singleLine = true,
                        label = { Text("名称") }
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val name = newFolderName.trim()
                    if (name.isNotBlank()) {
                        val target = File(dir, name)
                        scope.launch(Dispatchers.IO) {
                            runCatching {
                                if (newKindFolder) target.mkdirs() else target.createNewFile()
                            }
                            withContext(Dispatchers.Main) {
                                if (!newKindFolder && isPreviewable(name)) viewer = target
                                tick++
                            }
                        }
                    }
                    showNew = false
                }) { Text("创建") }
            },
            dismissButton = { TextButton(onClick = { showNew = false }) { Text("取消") } }
        )
    }

    confirmDelete?.let { f ->
        ConfirmDialog(
            title = "删除",
            text = "确定删除「${f.name}」" + if (f.isDirectory) "及其全部内容吗？此操作不可恢复。" else "吗？此操作不可恢复。",
            onConfirm = { doDelete(f) },
            onDismiss = { confirmDelete = null }
        )
    }

    showDetails?.let { f ->
        AlertDialog(
            onDismissRequest = { showDetails = null },
            modifier = Modifier.motionEnter(),
            title = { Text("详情") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("路径：${f.absolutePath}", style = MaterialTheme.typography.bodySmall)
                    Text("类型：${if (f.isDirectory) "目录" else mimeOf(f.name)}", style = MaterialTheme.typography.bodySmall)
                    if (!f.isDirectory) Text("大小：${formatSize(f.length())}", style = MaterialTheme.typography.bodySmall)
                    Text(
                        "修改时间：" + SimpleDateFormat("yyyy年M月d日 HH:mm", Locale.getDefault()).format(Date(f.lastModified())),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            },
            confirmButton = { TextButton(onClick = { showDetails = null }) { Text("知道了") } }
        )
    }
}

private const val Settings_ACTION_MANAGE_ALL = "android.settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION"
private const val Settings_ACTION_MANAGE_ALL_FALLBACK = "android.settings.MANAGE_ALL_FILES_ACCESS_PERMISSION"

/**
 * 一整行的存储占比已经**并进顶上的面包屑那一行**（见「路径导航」处），
 * 这里只留两个小件：占比数据 + 一个环。
 *
 * 之所以拆开：环要放进胶囊里当装饰，而百分比要按胶囊剩余宽度排，
 * 塞成一个自成一行的 Composable 就没法和目录名共处一行了。
 *
 * **不存占用数字**（用户要求压扁）：`159.8 GB / 489.2 GB` 放进胶囊必然把
 * 目录名挤到截断（实测标题被截成「内部存」、数字被截成「159.8 GB / …」），
 * 要看具体占用可以去系统设置。
 */
private class StorageInfo(val usedFrac: Float)

@Composable
private fun rememberStorageInfo(root: File): StorageInfo? = remember(root) {
    val stat = runCatching { StatFs(root.path) }.getOrNull() ?: return@remember null
    val total = stat.totalBytes
    val avail = stat.availableBytes
    if (total <= 0L) return@remember null
    StorageInfo(((total - avail).toFloat() / total).coerceIn(0f, 1f))
}

/**
 * v72：环形占比收编成 ui/common 的 [io.github.m0use001.planability.ui.common.CompletionRing]
 * （底槽 surfaceVariant、进度 primary、-90° 顺时针、带 standard 弹簧）——此处留名，删除实现。
 */

@Composable
private fun SyncthingCard(context: Context, onOpenSync: () -> Unit) {
    val installed = remember {
        SYNC_PACKAGES.any { pkg -> runCatching { context.packageManager.getPackageInfo(pkg, 0) }.isSuccess }
    }
    val pkg = remember {
        SYNC_PACKAGES.firstOrNull { pkg -> runCatching { context.packageManager.getPackageInfo(pkg, 0) }.isSuccess }
    }
    Card(
        shape = ShapeTokens.card,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(14.dp)) {
            // R11：卡头行首图标 = 装饰性入口 → 中性（主色只给选中/主操作/状态）
            Icon(Icons.Rounded.Sync, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Syncthing 同步", style = MaterialTheme.typography.titleSmall)
                Text(
                    if (installed) "外部 Syncthing 已安装 · 也可使用内置内核"
                    else "内置 Syncthing 内核 · 点按进入同步中心与电脑配对",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            TextButton(onClick = onOpenSync) { Text("同步中心") }
            if (installed && pkg != null) {
                TextButton(onClick = {
                    runCatching {
                        context.startActivity(context.packageManager.getLaunchIntentForPackage(pkg))
                    }.onFailure { }
                }) { Text("外部") }
            }
        }
    }
}

@Composable
private fun LaunchedEffectOneShot(onTimeout: () -> Unit) {
    androidx.compose.runtime.LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(2200)
        onTimeout()
    }
}

private fun deleteRecursive(f: File) {
    if (f.isDirectory) f.listFiles()?.forEach { deleteRecursive(it) }
    f.delete()
}

private fun copyRecursive(src: File, dst: File) {
    if (src.isDirectory) {
        dst.mkdirs()
        src.listFiles()?.forEach { copyRecursive(it, File(dst, it.name)) }
    } else {
        src.inputStream().use { input -> dst.outputStream().use { output -> input.copyTo(output) } }
    }
}

private fun countEntries(f: File): Int = f.listFiles()?.size ?: 0

fun formatSize(bytes: Long): String = when {
    bytes >= 1 shl 30 -> "%.1f GB".format(bytes / 1e9)
    bytes >= 1 shl 20 -> "%.1f MB".format(bytes / 1e6)
    bytes >= 1 shl 10 -> "%.1f KB".format(bytes / 1e3)
    else -> "$bytes B"
}

fun mimeOf(name: String): String {
    val ext = name.substringAfterLast('.', "").lowercase()
    // Android 的 MimeTypeMap 不认识 .md/.ics/.log 这些，返回 null 会退成
    // application/octet-stream，系统分享/打开时就找不到合适的应用。
    val fallback = when (ext) {
        "md", "markdown", "mdx" -> "text/markdown"
        "htm", "html" -> "text/html"
        "csv" -> "text/csv"
        "tsv" -> "text/tab-separated-values"
        "json", "jsonl", "ndjson" -> "application/json"
        "xml" -> "text/xml"
        "yaml", "yml" -> "text/yaml"
        "log", "txt" -> "text/plain"
        "ics" -> "text/calendar"
        "vcf" -> "text/vcard"
        "svg" -> "image/svg+xml"
        "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
        else -> null
    }
    return fallback ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
}
