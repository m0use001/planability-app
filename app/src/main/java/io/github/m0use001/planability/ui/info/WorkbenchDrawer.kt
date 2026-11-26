package io.github.m0use001.planability.ui.info

import android.provider.OpenableColumns
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.WindowInsets
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import io.github.m0use001.planability.ui.common.motionEnter
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import io.github.m0use001.planability.core.HapticEngine
import io.github.m0use001.planability.core.LocalHaptics
import io.github.m0use001.planability.core.reachedEndHaptic
import io.github.m0use001.planability.data.ChatSession
import io.github.m0use001.planability.data.AutoBackup
import io.github.m0use001.planability.data.Folder
import io.github.m0use001.planability.data.Graph
import io.github.m0use001.planability.data.NoteEntity
import io.github.m0use001.planability.data.Project
import io.github.m0use001.planability.ui.common.AppIcons
import io.github.m0use001.planability.ui.files.mimeOf
import java.io.File
import kotlinx.coroutines.launch

/**
 * v8 抽屉：项目树（文件管理器形态）。
 * 项目根 → 子文件夹（folders 表）→ 叶子：会话 / 笔记 / 项目目录真实文件。
 * 未分类为顶层伪节点（仅会话）；已归档保留在每项目底部半透明子分组。
 * 记忆只存在项目根（memories.projectId），树内不展开记忆。
 */
@Composable
internal fun WorkbenchDrawerContent(
    projects: List<Project>,
    sessions: List<ChatSession>,
    memoryCount: Int,
    pendingCount: Int,
    onClose: () -> Unit,
    onNewChat: () -> Unit,
    onNewChatIn: (projectId: Long, folderId: Long) -> Unit,
    onOpenSession: (Long) -> Unit,
    onManageSessions: () -> Unit,
    onMemory: () -> Unit,
    onCapture: () -> Unit,
    onPending: () -> Unit,
    onUsageStats: () -> Unit,
    onSearch: () -> Unit,
    onProjectDetail: (Long) -> Unit,
    // v12 扩展入口
    // v53.3：抽屉已不再单独露「记忆库 / 文件库」，两者与「归档项目」一起收进
    // [onDataHub]（ui/info/DataHub.kt）；回调保留是因为别处（深链、其他页面）
    // 仍可能直达单个页面，删掉会改动全仓签名。
    onFileLibrary: () -> Unit = {},
    /** v53.3「资料」Hub（记忆库 / 文件库 / 归档项目 三 tab） */
    onDataHub: () -> Unit = {},
    // 模型设置与智能体设置已合并成一屏（顶部按钮切换，见 ui/info/AiSettingsHubs.kt）
    onModelHub: () -> Unit = {},
    // 能力设置与插件设置已合并成「工具与扩展」Hub 的第一 tab
    onAiCapability: () -> Unit = {},
    // AI 能力体检（2026-11-05 用户「一次踩一个坑」整改：出门学习前 30 秒查一遍能力是否可用）
    onAiHealth: () -> Unit = {},
    onMcp: () -> Unit = {},
    onCommands: () -> Unit = {},
    onImport: () -> Unit = {},
    onVoice: () -> Unit = {},
    // v19.33：phone use 的 AI 可视/可操作区域
    onPhoneUseRegion: () -> Unit = {},
    // 图表全局默认设置（用户要求入口紧贴「能力设置」正下方）
    onChartDefaults: () -> Unit = {},
    // v18.1：自动化文件夹（每个任务一个夹，叶子=它的 AI 会话）
    automations: List<io.github.m0use001.planability.data.Automation> = emptyList(),
    autoAnchors: Map<Long, Long> = emptyMap(),
    onOpenAutomations: () -> Unit = {},
    /**
     * 底部系统栏（手势条）高度：由调用方在**抽屉之外**量好传进来。
     * 2026-11-01 抽屉改成 WindowInsets(0) 全高铺底后，内部读不到 navigationBars
     * （已被消费成 0），只能外部传；否则最后一项会滑到手势条底下被盖住。
     */
    bottomInset: androidx.compose.ui.unit.Dp = 0.dp,
    /**
     * 2026-11-19 平板批：this 内容是**抽屉**（可收起）还是**常驻侧栏**（宽屏）。
     * 常驻侧栏传 false —— 头部的 × 收起钮不渲染（关不掉的东西不该给按钮）。
     */
    dismissable: Boolean = true
) {
    val scope = rememberCoroutineScope()
    val haptics = LocalHaptics.current
    val version by Graph.dataVersion.flow.collectAsState()

    var foldersByProject by remember { mutableStateOf<Map<Long, List<Folder>>>(emptyMap()) }
    var notesByProject by remember { mutableStateOf<Map<Long, List<NoteEntity>>>(emptyMap()) }
    // 2026-10-29 用户：抽屉撤出完整文件树（文件浏览在文件库），filesByProject/subFilesByProject 随之删除
    // v17.14：信息提取目录树（filesDir/信息提取/<群>/<日期>/）
    var extractGroups by remember { mutableStateOf<List<String>>(emptyList()) }
    var extractDays by remember { mutableStateOf<Map<String, List<String>>>(emptyMap()) }
    var extractFiles by remember { mutableStateOf<Map<String, List<Pair<String, String>>>>(emptyMap()) }
    var previewExtractFile by remember { mutableStateOf<Pair<String, String>?>(null) }
    LaunchedEffect(version) {
        runCatching {
            val root = java.io.File(Graph.appContext.filesDir, EXTRACT_DIR_NAME)
            // 2026-11-15：先把老结构 <群>/ 整体下移一层到 聊天原文/<群>/（幂等、可重入）
            runCatching { migrateExtractRawIntoSubdir(root) }
                .getOrNull()?.logTag()?.let { android.util.Log.i("WorkbenchDrawer", it) }
            // 迁移后产物树在「聊天原文」下；万一迁移没成功（权限/占用）仍退回根目录读，
            // 不能让用户已有产物直接消失。
            val rawRoot = java.io.File(root, EXTRACT_RAW_NAME).takeIf { it.isDirectory } ?: root
            val gs = rawRoot.listFiles()
                ?.filter { it.isDirectory && it.name != EXTRACT_CHAT_NAME }
                ?.map { it.name }?.sorted().orEmpty()
            val days = mutableMapOf<String, List<String>>()
            val files = mutableMapOf<String, List<Pair<String, String>>>()
            gs.forEach { g ->
                val dayDirs = java.io.File(rawRoot, g).listFiles()?.filter { it.isDirectory }
                    ?.sortedByDescending { it.name }.orEmpty()
                days[g] = dayDirs.map { it.name }
                dayDirs.forEach { d ->
                    files["$g/${d.name}"] = d.listFiles()?.filter { it.isFile }
                        ?.sortedBy { it.name }
                        ?.map { it.name to runCatching { it.readText() }.getOrDefault("") }
                        .orEmpty()
                }
            }
            extractGroups = gs
            extractDays = days
            extractFiles = files
        }
    }
    LaunchedEffect(version, projects) {
        val mapF = mutableMapOf<Long, List<Folder>>()
        val mapN = mutableMapOf<Long, List<NoteEntity>>()
        projects.forEach { p ->
            mapF[p.id] = Graph.folders.ofProject(p.id)
            mapN[p.id] = Graph.projects.notesOf(p.id)
        }
        foldersByProject = mapF
        notesByProject = mapN
    }

    // 展开/收起状态（列表保存，Set 不可 Bundle 直存）
    var expanded by rememberSaveable { mutableStateOf(emptyList<String>()) }

    // 抽屉列表状态**必须声明在 toggle 之前**（见下）。
    val drawerScroll = rememberLazyListState()

    // 折叠时把「视口第一项」当锚点：记下它的 key 和偏移，重排后按 key 找回来。
    //
    // 为什么不再用 firstVisibleItemIndex - 1：那个值在两种状态下含义不同 ——
    //   · 文件夹**贴顶**时，贴顶的 header 不在可见项里，firstVisibleItemIndex 指的是
    //     它下面第一条会话，减一才是文件夹自己；
    //   · 文件夹**只是可见、没贴顶**时，视口第一项可能就是文件夹本身或它上面的项，
    //     再减一就指到了别人身上。
    // 于是「把文件夹内部翻到一半再折叠」时那一行会跳一下（真机实测：未分类 y 183→246，
    // 位移正好等于 contentPadding 的 18dp）。改成按 key 找锚点，两种情况都对：
    // 锚点还在就把它挪回原偏移；锚点自己随折叠消失（它是被折叠文件夹的子项）就退回文件夹本身。
    var collapseIndex by remember { mutableIntStateOf(-1) }
    var collapseOffset by remember { mutableIntStateOf(0) }
    // 贴顶时补一个 contentPadding.top：sticky header 钉在**视口顶**，
    // 而 scrollToItem(i, 0) 把行放到**内容起点**，差正好一个 top padding。
    // 不补这一下，折叠后行会下移 18dp（真机量到 63px，正是用户看到的那一跳）。
    val collapseTopPadPx = with(androidx.compose.ui.platform.LocalDensity.current) {
        18.dp.roundToPx()
    }

    /** 某个分组下面那个"装它全部内容"的 item 的 key（树是一层一个 item） */
    fun groupChildKey(key: String) = when (key) {
        "u" -> "u-ch"
        "ext" -> "ext-ch"
        "auto" -> "auto-ch"
        else -> key + "-ch"
    }

    fun toggle(key: String) {
        // 展开/收起 = 中性点击 → Tap（HIG 映射表；原来用的是 Select，与计划页/记忆库的
        // 折叠手感不一致——同一种语义在全 app 只能有一种手感）
        haptics.perform(HapticEngine.Kind.Tap)
        val collapsing = key in expanded
        val info = drawerScroll.layoutInfo.visibleItemsInfo
        if (!collapsing) {
            expanded = expanded + key
            return
        }

        // ## 折叠为什么**不再**「折完再滚回去」
        // 旧做法是：折叠 → 等一帧重排 → `scrollToItem(记下的 index, offset)` 把行拉回来。
        // 位置最终是对的，但中间那一帧列表已经跳走了，**看上去就是「闪一下」**（用户反馈）。
        //
        // 现在改成**先滚再折**：重排前先把列表摆到「折叠后应有的位置」，此时列表还没变、
        // 滚动位置是准的；紧接着再改 `expanded`，重排后那一行原地不动，没有需要补的帧。
        val self = info.firstOrNull { it.key == key }
        if (self != null && self.offset >= 0) {
            // ① 这一行**完整**可见：折叠只影响它下面的内容，上面的都不动 → 什么都不用滚。
            expanded = expanded - key
        } else if (self != null || info.firstOrNull()?.key == groupChildKey(key)) {
            // ② **半切**（行已顶出一截，offset < 0）或**完全钉顶**（行已移出可见项、内容项是第一可见项）：
            //    折叠前先把**文件夹行本身**滚到视口顶，让它成为第一可见项——它的尺寸不变，
            //    折叠只清空它下面的内容项，行原地不动。
            //
            //    为什么不能直接折（2026-10-20 真机日志实测）：半切/钉顶时第一可见项就是**要被
            //    清空的内容项**；内容高度一归零，LazyColumn 仍拿它当滚动锚点（保持其偏移），
            //    于是整个头部被顶出屏幕——日志：折后 firstIdx=3、文件夹行 absent（用户："又飞了"）。
            //    requestScrollToItem 同帧方案同样会输给这条锚定逻辑。锚点必须换成"折叠后仍在、
            //    且尺寸不变"的文件夹行本身：先滚后折在**未折叠**列表上落点确定，且与
            //    expanded 同帧生效，没有中间帧。
            scope.launch {
                val headerIndex = self?.index
                    ?: (drawerScroll.firstVisibleItemIndex - 1).coerceAtLeast(0)
                // 2026-11-17 用户「会话列表常常中途卡住然后震动」：折叠也是**程序滚动**，
                // 撞到列表端点会触发放「滚到尽头」的重震（core/Haptics.reachedEndHaptic）。
                io.github.m0use001.planability.core.ScrollHapticSuppress.suppress(600)
                drawerScroll.scrollToItem(headerIndex, collapseTopPadPx)
                expanded = expanded - key
            }
        } else {
            // ③ 它在视口外很远：折叠它不会改变视口内容，不要动滚动
            expanded = expanded - key
        }
    }

    // 操作弹层
    var renameTarget by remember { mutableStateOf<RenameTarget?>(null) }
    var newFolderParent by remember { mutableStateOf<Pair<Long, Long>?>(null) } // projectId to parentId
    var moveSessionId by remember { mutableStateOf<Long?>(null) }
    var deleteTarget by remember { mutableStateOf<DeleteTarget?>(null) }
    // 2026-11-16：强制删除进行中（快照 + 递归删目录要几秒）。置位期间按钮转圈并禁用，
    // 删完才关弹窗——否则弹窗先消失、行还在，用户看不出删除是否生效。
    var deleting by remember { mutableStateOf(false) }
    // 项目根目录文件的长按菜单（2026-10-21：右滑菜单里的文件/文件夹长按也要弹二级菜单）
    // v53.3：「归档项目」列表弹层已升格为整屏（ui/info/DataHub.kt 的 ArchivedProjectsScreen），
    // 由抽屉「资料」Hub 进入；原来这里那个 AlertDialog 连同 showArchived 状态一并删除。
    var deleteBlocked by remember { mutableStateOf<String?>(null) }
    // v19.62：删除项目被拒（项目下还有数据）时的提示
    var addFileProjectId by remember { mutableStateOf<Long?>(null) }
    val context = LocalContext.current

    val addFileLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val pid = addFileProjectId
        addFileProjectId = null
        if (uri != null && pid != null) {
            scope.launch {
                runCatching {
                    var name = "file"
                    context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                        val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (idx >= 0 && c.moveToFirst()) name = c.getString(idx) ?: name
                    }
                    val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    if (bytes != null) Graph.folders.addFileToProject(pid, name, bytes)
                }.onFailure {
                    android.widget.Toast.makeText(context, "添加文件失败：" + it.message, android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // v19.39：抽屉容器从 Column + verticalScroll 换成 LazyColumn。
    // 换的原因是「分组头粘顶」是 LazyColumn 独有的能力：文件夹碰到屏幕上沿先冻住、
    // 其下的文件继续滑走，等最后一个文件滑到顶文件夹才跟着走 —— Column 里怎么写都做不到。
    // （drawerScroll 已上移到 toggle 之前声明）
    // 一键滑动（下方「详细设置」）期间不让分组头冻结：让它的位移跟随滚动，滚完恢复。
    // 手动滑才有 IDE 那种"当前代码块"的提示；程序化直接跳过去时一路挂着几个文件夹只会晃眼。
    var jumpScrolling by remember { mutableStateOf(false) }
    val jumpLift by remember {
        derivedStateOf { if (jumpScrolling) drawerScroll.firstVisibleItemScrollOffset else 0 }
    }
    LazyColumn(
        state = drawerScroll,
        modifier = Modifier
            .fillMaxSize()
            .reachedEndHaptic(drawerScroll),
        // v19.45：内边距从 `Modifier.padding` 换成 **`contentPadding`**，这一处就是
        // 「底部被截断 + 下面还空一条」的根因：
        //   · `Modifier.padding` 收窄的是**视口** —— 内容在视口底边（= 屏高 - 导航条 - 24dp，
        //     实测 y≈2688）就被切掉，而下方那条 84px 的空带里浮着手势条，
        //     看上去就是「列表被截断，然后空了一块」；
        //   · `contentPadding` 只给内容让位、视口一直铺到**屏幕底边** —— 滚动时行在屏幕边缘
        //     被切是列表本来就该有的样子，而滚到底时最后一项仍在手势条上方
        //     （底部留的 navInset + 24dp 就是为这一条）。
        // 用户 10-16 给的截图正是前者：最后一行切一半、下面一条空白。
        contentPadding = PaddingValues(
            start = 14.dp,
            end = 14.dp,
            top = 18.dp,
            // asPaddingValues 是**扩展函数**（在 androidx.compose.foundation.layout 包里），
            // 写 FQN 不管用、必须 import —— 和 Icons.Rounded.X / glance 的 updateAll 同一个坑。
            // 2026-11-01：这里改用外部传入的 bottomInset（抽屉已 WindowInsets(0)，内部读不到）。
            bottom = 24.dp + bottomInset
        )
    ) {
        // ---------- 头部 ----------
        item(key = "hdr") {
            // v10：显式关闭按钮（全面屏手势与滑回手势冲突时兜底）
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text("工作台", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                // 2026-11-19 平板批：宽屏它是**常驻侧栏**，没有"收起"这回事——
                // 摆一个 × 只会让人以为能把侧栏关掉（关不掉，而且与抽屉语义冲突）。
                if (dismissable) {
                    IconButton(onClick = onClose) {
                        Icon(AppIcons.Nav.Close, contentDescription = "收起", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            DrawerActionRow(AppIcons.Act.Add, "新建对话") { onNewChat() }
            DrawerActionRow(AppIcons.Act.Search, "搜索") { onSearch() }
            DrawerActionRow(AppIcons.Setting.Usage, "使用统计") { onUsageStats() }
            // v28：需求 1 —— 「详细设置」不跳页，只把抽屉滚到底部那一组设置。
            // 抽屉是 Column + verticalScroll，"滚到底"就是 maxValue，不需要知道最后一项在哪
            // （哪天底部又加了条目，这行也不用改）。
            DrawerActionRow(AppIcons.Nav.Down, "详细设置") {
                scope.launch {
                    // 跳到底：滚到最后一个 item。旧写法是 animateScrollTo(maxValue)，
                    // 那是 ScrollState 的 API，LazyListState 上没有，必须换成按 index 滚。
                    jumpScrolling = true
                    // 2026-11-17：程序滚动撞底同样会放「滚到尽头」重震，先压掉
                    io.github.m0use001.planability.core.ScrollHapticSuppress.suppress(1200)
                    drawerScroll.animateScrollToItem(drawerScroll.layoutInfo.totalItemsCount - 1)
                    jumpScrolling = false
                }
            }
            Spacer(Modifier.height(12.dp))
            Text(
                "项目与会话", style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = 4.dp, bottom = 6.dp)
            )
        }

        // ---------- 项目树 ----------
        // 2026-11-15 用户「项目与会话界面有 2 个信息提取入口」：抽屉里那个 📁 行是
        // **真项目**（projects.name="信息提取"，提纯会话必须挂它，见 GroupCaptureEngine
        // 的 EXTRACT_PROJECT_NAME / ExtractProjectContractTest），和下面 🔽 漏斗（磁盘产物树）
        // 撞了名。修法：项目树里**不再渲染**这个项目行，它的会话改为在漏斗下的
        // 「聊天提纯」分组里渲染——数据层 projectId 一行不动（记忆/画像/交接照旧走项目作用域）。
        val extractProject = projects.firstOrNull { it.name == EXTRACT_DIR_NAME }
        val extractSessions = extractProject
            ?.let { ep -> sessions.filter { it.projectId == ep.id }.sortedByDescending { it.updatedAt } }
            .orEmpty()
        projects.filter { it.name != EXTRACT_DIR_NAME }.forEach { p ->
                val pkey = "p" + p.id
                val pFolders = foldersByProject[p.id] ?: emptyList()
                val pSessions = sessions.filter { it.projectId == p.id && !it.archived }
                val pNotes = notesByProject[p.id] ?: emptyList()
                val pArchived = sessions.filter { it.projectId == p.id && it.archived }.sortedByDescending { it.updatedAt }
                // 项目数据里「项目文件」行的人数小计（顶层文件数，唯一还读磁盘的地方）
                val pFiles = Graph.folders.listProjectFiles(p.id) // 顶层文件数（同步小盘读，几十个以内）
                val count = pSessions.size + pNotes.size
            stickyHeader(key = "p${p.id}") {
                Box(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerLow).graphicsLayer { translationY = jumpLift.toFloat() }) {
                    TreeGroupRow(
                        depth = 0, expanded = pkey in expanded, hasChildren = true, // 项目数据文件夹常在，恒可展开
                        icon = if (pkey in expanded) AppIcons.Kind.FolderOpen else AppIcons.Kind.project(p.iconKey),
                        // 2026-10-28 用户定案+配色合规：项目行胶囊用 surfaceContainerHighest——
                        // 曾试 secondaryContainer 被用户指出违反 M3E 配色规范（R4 第二色只给
                        // 底栏选中 pill/组件内容块，R11 行首图标中性）。层级改用 M3 surface 色阶：
                        // 项目行 Highest > 文件夹行 surfaceContainer > 会话/文件叶透明，全中性。
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        title = p.name, badge = if (count > 0) count.toString() else "",
                        pillColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                        onToggle = { toggle(pkey) },
                        menu = { close ->
                            DropdownMenuItem(
                                modifier = Modifier.clip(RoundedCornerShape(10.dp)), // 2026-11-15 点击高亮圆角化（原来是整行直角方块）
                                text = { Text("新建对话") },
                                leadingIcon = { Icon(AppIcons.Kind.Chat, null, Modifier.size(17.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                                onClick = { close(); haptics.perform(HapticEngine.Kind.Tap); onNewChatIn(p.id, 0L) }
                            )
                            DropdownMenuItem(
                                modifier = Modifier.clip(RoundedCornerShape(10.dp)), // 2026-11-15 点击高亮圆角化（原来是整行直角方块）
                                text = { Text("新建子文件夹") },
                                leadingIcon = { Icon(AppIcons.Kind.Folder, null, Modifier.size(17.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                                onClick = { close(); haptics.perform(HapticEngine.Kind.Tap); newFolderParent = p.id to 0L }
                            )
                            DropdownMenuItem(
                                modifier = Modifier.clip(RoundedCornerShape(10.dp)), // 2026-11-15 点击高亮圆角化（原来是整行直角方块）
                                text = { Text("添加文件") },
                                leadingIcon = { Icon(AppIcons.Kind.File, null, Modifier.size(17.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                                onClick = { close(); haptics.perform(HapticEngine.Kind.Tap); addFileProjectId = p.id; addFileLauncher.launch(arrayOf("*/*")) }
                            )
                            DropdownMenuItem(
                                modifier = Modifier.clip(RoundedCornerShape(10.dp)), // 2026-11-15 点击高亮圆角化（原来是整行直角方块）
                                text = { Text("重命名") },
                                leadingIcon = { Icon(AppIcons.Act.Rename, null, Modifier.size(17.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                                onClick = { close(); haptics.perform(HapticEngine.Kind.Tap); renameTarget = RenameTarget(p.id, p.name, RenameKind.PROJECT) }
                            )
                            DropdownMenuItem(
                                modifier = Modifier.clip(RoundedCornerShape(10.dp)), // 2026-11-15 点击高亮圆角化（原来是整行直角方块）
                                text = { Text("项目详情") },
                                leadingIcon = { Icon(AppIcons.State.Info, null, Modifier.size(17.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                                onClick = { close(); haptics.perform(HapticEngine.Kind.Tap); onProjectDetail(p.id) }
                            )
                            DropdownMenuItem(
                                modifier = Modifier.clip(RoundedCornerShape(10.dp)), // 2026-11-15 点击高亮圆角化（原来是整行直角方块）
                                text = { Text("归档") },
                                leadingIcon = { Icon(AppIcons.Act.Archive, null, Modifier.size(17.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                                onClick = {
                                    haptics.perform(HapticEngine.Kind.Tap)
                                    scope.launch { Graph.projects.setArchived(p.id, true) }
                                }
                            )
                            DropdownMenuItem(
                                modifier = Modifier.clip(RoundedCornerShape(10.dp)), // 2026-11-15 点击高亮圆角化（原来是整行直角方块）
                                text = { Text("删除", color = MaterialTheme.colorScheme.error) },
                                leadingIcon = { Icon(AppIcons.Act.Delete, null, Modifier.size(17.dp), tint = MaterialTheme.colorScheme.error) },
                                onClick = { close(); haptics.perform(HapticEngine.Kind.Tap); deleteTarget = DeleteTarget(p.id, p.name, DeleteKind.PROJECT) }
                            )
                        }
                    )
                }
            }
            item(key = "p${p.id}-ch") {
                if (pkey in expanded) {
                    // 子文件夹（递归一层层画）
                    DrawFolderChildren(
                        projectId = p.id, parentId = 0L, depth = 1,
                        folders = pFolders, sessions = pSessions,
                        expanded = expanded, onToggle = ::toggle,
                        onOpenSession = onOpenSession,
                        onNewChatIn = onNewChatIn,
                        onNewSubFolder = { newFolderParent = p.id to it },
                        onRenameFolder = { f -> renameTarget = RenameTarget(f.id, f.name, RenameKind.FOLDER) },
                        onDeleteFolder = { f -> deleteTarget = DeleteTarget(f.id, f.name, DeleteKind.FOLDER) },
                        onMoveSession = { moveSessionId = it },
                        onRenameSession = { s -> renameTarget = RenameTarget(s.id, s.title, RenameKind.SESSION) },
                        onArchiveSession = { s -> scope.launch { Graph.chat.setArchived(s.id, !s.archived) } },
                        onDeleteSession = { s -> deleteTarget = DeleteTarget(s.id, s.title, DeleteKind.SESSION) }
                    )
                    // 2026-10-29 用户两条重定：
                    // ①「取消右滑菜单展示完整文件树」——文件浏览已在文件库（面包屑逐层式），
                    //   抽屉里点开项目只看「项目数据」与会话（+笔记），文件叶/真实子文件夹行全部撤出；
                    // ②项目数据聚合为一个文件夹：项目记忆 / 项目文件（叶，跳文件库）/ 已归档会话。
                    // 空文件夹一律钉回收起态：内容删光后仍挂着「展开」图标是用户报过的 bug。
                    val pdkey = "pd" + p.id
                    TreeGroupRow(
                        depth = 1, expanded = pdkey in expanded, hasChildren = true,
                        icon = AppIcons.Kind.ProjectData, tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        title = "项目数据", badge = "",
                        onToggle = { toggle(pdkey) }, menu = null
                    )
                    if (pdkey in expanded) {
                        LeafRow(2, AppIcons.Kind.Memory, MaterialTheme.colorScheme.primary, "项目记忆", "") { onMemory() }
                        LeafRow(
                            2, AppIcons.Kind.File, MaterialTheme.colorScheme.onSurfaceVariant,
                            "项目文件", if (pFiles.isEmpty()) "空" else "${pFiles.size} 项"
                        ) { onFileLibrary() }
                        if (pArchived.isNotEmpty()) {
                            val akey = "pa" + p.id
                            TreeGroupRow(
                                depth = 2, expanded = akey in expanded && pArchived.isNotEmpty(),
                                hasChildren = pArchived.isNotEmpty(),
                                icon = AppIcons.Act.Archive, tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                title = "已归档（" + pArchived.size + "）", badge = "",
                                onToggle = { toggle(akey) }, menu = null
                            )
                            if (akey in expanded && pArchived.isNotEmpty()) pArchived.forEach { s ->
                                SessionTreeRow(3, s, onOpenSession, dimmed = true, onMove = { moveSessionId = s.id }, onRename = { renameTarget = RenameTarget(s.id, s.title, RenameKind.SESSION) }, onArchive = { scope.launch { Graph.chat.setArchived(s.id, false) } }, onDelete = { deleteTarget = DeleteTarget(s.id, s.title, DeleteKind.SESSION) })
                            }
                        }
                    }
                }
            }
        }

            // v17.14：信息提取（项目与对话区间内的产物目录树：群 → 日期 → 文件）
            val exkey = "extract"
        stickyHeader(key = "ext") {
            Box(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerLow).graphicsLayer { translationY = jumpLift.toFloat() }) {
            TreeGroupRow(
                depth = 0, expanded = exkey in expanded,
                // v53.4：恒 true —— 文件夹里现在**恒有**「信息获取 · 提纯」入口行，
                // 空产物时也必须能展开（原来 hasChildren=extractGroups.isNotEmpty()
                // 会让空产物文件夹点不开，入口就死了）。
                hasChildren = true,
                icon = AppIcons.Kind.Extract,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                title = "信息提取", badge = if (extractGroups.isNotEmpty()) extractGroups.size.toString() else "",
                // 顶级分组行与项目行同档胶囊（2026-10-28 用户：信息提取与自动化也算一级文件夹）
                pillColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                onToggle = { toggle(exkey) }, menu = null
            )
            }
        }
        item(key = "ext-ch") {
            if (exkey in expanded) {
                // v53.4（用户 2026-11-06「信息提取、自动化设置全部移动到项目与会话的
                // 信息提取、自动化文件夹下」）：「信息获取 · 提纯」从抽屉底部整行迁进本文件夹——
                // 提纯页（群聊采集/提纯归档/设置）与它的产物树同处一个语义单位。
                // 2026-11-11 用户：提纯入口与文件夹同款胶囊背景（见 LeafRow.pillColor 注）
                LeafRow(1, AppIcons.Setting.Capture, MaterialTheme.colorScheme.primary, "信息获取 · 提纯", "",
                    pillColor = MaterialTheme.colorScheme.surfaceContainer
                ) {
                    onCapture()
                }

                // 2026-11-15 用户定案：漏斗下面**两组**
                //   ① 聊天原文 —— 磁盘产物树（原 <群>/<日期>/，整体下移一层）
                //   ② 聊天提纯 —— 「信息提取」项目下的提纯会话（视觉搬家，projectId 不动）
                // 顶层因此只剩一个「信息提取」，不再和同名项目行重复。

                // ---- ① 聊天原文（磁盘产物：群 → 日期 → 文件） ----
                val rawKey = "exraw"
                TreeGroupRow(
                    depth = 1, expanded = rawKey in expanded,
                    hasChildren = true,
                    icon = if (rawKey in expanded) AppIcons.Kind.FolderOpen else AppIcons.Kind.Folder,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    // 标题用常量：与写入侧（capture/）同一份名字，改名只改一处
                    title = EXTRACT_RAW_NAME,
                    badge = if (extractGroups.isNotEmpty()) extractGroups.size.toString() else "",
                    onToggle = { toggle(rawKey) }, menu = null
                )
                if (rawKey in expanded) {
                    if (extractGroups.isEmpty()) {
                        LeafRow(2, AppIcons.Kind.File, MaterialTheme.colorScheme.onSurfaceVariant, "暂无聊天原文", "") { }
                    }
                    extractGroups.forEach { g ->
                        val gkey = "ex$g"
                        TreeGroupRow(
                            depth = 2, expanded = gkey in expanded,
                            hasChildren = (extractDays[g] ?: emptyList()).isNotEmpty(),
                            icon = if (gkey in expanded) AppIcons.Kind.FolderOpen else AppIcons.Kind.Folder,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            title = g, badge = "",
                            onToggle = { toggle(gkey) }, menu = null
                        )
                        if (gkey in expanded) (extractDays[g] ?: emptyList()).forEach { day ->
                            val dkey = "exd$g$day"
                            TreeGroupRow(
                                depth = 3, expanded = dkey in expanded, hasChildren = true,
                                icon = AppIcons.Kind.Folder, tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                title = day, badge = "",
                                onToggle = { toggle(dkey) }, menu = null
                            )
                            if (dkey in expanded) (extractFiles["$g/$day"] ?: emptyList()).forEach { (name, content) ->
                                LeafRow(4, AppIcons.Kind.File, MaterialTheme.colorScheme.primary, name, "${content.length} 字") {
                                    previewExtractFile = name to content
                                }
                            }
                        }
                    }
                }

                // ---- ② 聊天提纯（「信息提取」项目下的提纯会话；projectId 保持不变） ----
                val purifyKey = "expurify"
                TreeGroupRow(
                    depth = 1, expanded = purifyKey in expanded,
                    hasChildren = extractSessions.isNotEmpty(),
                    icon = AppIcons.Kind.Chat,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    title = EXTRACT_CHAT_NAME,
                    badge = if (extractSessions.isNotEmpty()) extractSessions.size.toString() else "",
                    onToggle = { toggle(purifyKey) }, menu = null
                )
                if (purifyKey in expanded) {
                    if (extractSessions.isEmpty()) {
                        LeafRow(2, AppIcons.Kind.File, MaterialTheme.colorScheme.onSurfaceVariant, "还没有提纯会话", "") { }
                    }
                    extractSessions.forEach { s ->
                        SessionTreeRow(
                            2, s, onOpenSession, dimmed = s.archived,
                            onMove = { moveSessionId = s.id },
                            onRename = { renameTarget = RenameTarget(s.id, s.title, RenameKind.SESSION) },
                            onArchive = { scope.launch { Graph.chat.setArchived(s.id, !s.archived) } },
                            onDelete = { deleteTarget = DeleteTarget(s.id, s.title, DeleteKind.SESSION) }
                        )
                    }
                }
            }
        }

            // v18.1：自动化（每个任务一个文件夹，叶子=它的 AI 会话 + 编辑入口）
            val akey = "auto"
            val enabledAutos = automations.count { it.enabled && it.hasTrigger }
        stickyHeader(key = "auto") {
            Box(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerLow).graphicsLayer { translationY = jumpLift.toFloat() }) {
            TreeGroupRow(
                depth = 0, expanded = akey in expanded,
                // v53.4：恒 true —— 文件夹里恒有「自动化设置」入口行（同信息提取的口径）
                hasChildren = true,
                // 自动化只认 Autorenew 一个图形（原折叠态 Timer 与底栏「专注」撞车）；
                // 该行 tint 原为 tertiary —— 本主题 tertiary = 种子蓝 +62° = 紫，图标上禁用。
                icon = AppIcons.Setting.Automation,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                title = "自动化", badge = if (enabledAutos > 0) "$enabledAutos" else "",
                // 顶级分组行与项目行同档胶囊（2026-10-28 用户：信息提取与自动化也算一级文件夹）
                pillColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                onToggle = { toggle(akey) }, menu = null
            )
            }
        }
        item(key = "auto-ch") {
            if (akey in expanded) {
                // v53.4：自动化设置入口进本文件夹（用户同上指令）——任务树与它的设置页
                // 同处一地；AutomationScreen 四 tab（自动化/MCP/导入/跳转）全量在这后面。
                // 编辑任务叶子仍留在每个任务夹里（直达 tab0 的管理页）。
                // 2026-11-11 用户：自动化文件夹下的两个入口行同样上胶囊背景
                LeafRow(1, AppIcons.Setting.Automation, MaterialTheme.colorScheme.primary, "自动化设置", "任务 · MCP · 导入",
                    pillColor = MaterialTheme.colorScheme.surfaceContainer
                ) {
                    onOpenAutomations()
                }
                if (automations.isEmpty()) {
                    // 设置行已在上面（同一个落点），这行退化为**纯提示**，不再做第二个可点目标
                    LeafRow(1, AppIcons.Setting.Automation, MaterialTheme.colorScheme.onSurfaceVariant, "暂无自动化任务", "",
                        pillColor = MaterialTheme.colorScheme.surfaceContainer
                    ) { }
                }
                automations.forEach { a ->
                    val skey = "a${a.id}"
                    val trigger = when {
                        a.intervalMinutes > 0 -> "每${a.intervalMinutes}分"
                        a.dailyHour >= 0 -> "%02d:%02d".format(a.dailyHour, a.dailyMinute)
                        else -> "无触发"
                    }
                    TreeGroupRow(
                        depth = 1, expanded = skey in expanded, hasChildren = true,
                        icon = if (skey in expanded) AppIcons.Kind.FolderOpen else AppIcons.Kind.Folder,
                        tint = if (a.enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = 0.6f),
                        title = a.name, badge = if (a.enabled) trigger else "已停用",
                        onToggle = { toggle(skey) }, menu = null
                    )
                    if (skey in expanded) {
                        // 该任务的会话：锚点会话（KV auto.session:<id>）+ 历史同名会话，去重按更新时间倒序
                        val anchor = autoAnchors[a.id]
                        val title = "自动化·" + a.name
                        val autoSessions = (sessions.filter { it.id == anchor || it.title == title })
                            .distinctBy { it.id }
                            .sortedByDescending { it.updatedAt }
                        if (autoSessions.isEmpty()) {
                            LeafRow(2, AppIcons.Kind.Chat, MaterialTheme.colorScheme.onSurfaceVariant, "暂无会话（执行后出现在这里）", "") { }
                        }
                        autoSessions.forEach { s ->
                            SessionTreeRow(
                                2, s, onOpenSession, dimmed = s.archived,
                                onMove = { moveSessionId = s.id },
                                onRename = { renameTarget = RenameTarget(s.id, s.title, RenameKind.SESSION) },
                                onArchive = { scope.launch { Graph.chat.setArchived(s.id, !s.archived) } },
                                onDelete = { deleteTarget = DeleteTarget(s.id, s.title, DeleteKind.SESSION) }
                            )
                        }
                        LeafRow(2, AppIcons.Act.Edit, MaterialTheme.colorScheme.primary, "编辑任务", if (!a.enabled) "已停用" else "") { onOpenAutomations() }
                    }
                }
                }
        }

        // ---------- 尾部 ----------
        item(key = "ftr") {
            Spacer(Modifier.height(10.dp))
            // 动作链接用中性色（拼色规则：主色只留操作焦点，列表类动作不染绿）
            TextButton(
                onClick = onManageSessions,
                colors = androidx.compose.material3.ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.onSurface
                )
            ) { Text("管理全部会话 →") }
            // 上下等距，分界线才**刚好落在两行中间**。
            // 这处上方是 TextButton（自己的文字上下还有内边距）、下方是 DrawerActionRow，
            // 按可见文字算要把上面那截放长一点才居中（10/10 偏上，实测差约 3.6dp）。
            Spacer(Modifier.height(14.dp))
            Box(Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)))
            Spacer(Modifier.height(10.dp))
            // v12 重排：信息区（提纯 → 语音输入 → 待处理 → 记忆库 → 文件库）
            // v19.2：自动化与扩展入口从信息页正文迁入抽屉（定时任务 / MCP / 技能导入）
            // v53.3（用户 2026-11-06 拍板「6 行：删重 + 资料 hub」）：底部 13 行 → 6 行。
            // 撤走的行去向：
            //   语音输入     → 首页输入卡麦克风（v53.3 新增；原抽屉行是把文本塞进首页输入框）
            //   自动化与扩展 → 抽屉顶部「自动化」文件夹 + 「我的」页（同一页面的第三份重复）
            //   记忆库/文件库/归档项目 → 「资料」Hub（ui/info/DataHub.kt）三 tab
            //   图表默认设置 / 手机操控区域 / 导入 → 「我的」页（本来就有同名同位入口）
            // v53.4（用户「信息提取、自动化设置全部移动到项目与会话的信息提取、自动化
            // 文件夹下」）：「信息获取 · 提纯」也撤出底部，迁进树上「信息提取」文件夹首行；
            // 底部只剩纯数据/设置入口（待处理 / 资料 + AI 两行）。
            // v53.6：待处理升级成「助理中枢」——草稿卡片流 + 私人助理对话（排期/查冲突/入库）
            DrawerActionRow(AppIcons.State.Pending, "助理中枢（待处理 $pendingCount 条）") { onPending() }
            DrawerActionRow(AppIcons.Setting.DataHub, "资料") { onDataHub() }
            // 导出目录在 getExternalFilesDir 下，scoped storage 规定任何文件管理器（包括本 App
            // 自己的文件页）都列不出 /sdcard/Android/data —— 真机实测显示"此文件夹为空"。
            // 上下等距（原来是 6/10，明显偏上）
            Spacer(Modifier.height(10.dp))
            Box(Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)))
            Spacer(Modifier.height(10.dp))
            // v50.5（用户 2026-11-05 拍板「两行：本体 + 扩展」）：AI 设置从 4 行压成 2 行。
            // 语义边界见 ui/info/AiSettingsHubs.kt：
            //   模型与智能体 = AI 自己是谁（模型 / 人格 / 模型显示）
            //   工具与扩展   = AI 能调动什么（能力与插件 / MCP / 命令）
            // 不压成一行 6 tab 的原因：切换器必然超宽，且"本体/扩展"是两种心智。
            DrawerActionRow(AppIcons.Setting.Model, "模型与智能体") { onModelHub() }
            DrawerActionRow(AppIcons.Setting.AiCapability, "工具与扩展") { onAiCapability() }
            DrawerActionRow(AppIcons.Setting.Health, "AI 能力体检") { onAiHealth() }
        }
    }

    // ---------- 弹层 ----------
    previewExtractFile?.let { (name, content) ->
        AlertDialog(
            modifier = Modifier.motionEnter(),
            onDismissRequest = { previewExtractFile = null },
            title = { Text(name, style = MaterialTheme.typography.titleSmall) },
            text = {
                Text(
                    content.take(20000), style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp).verticalScroll(rememberScrollState())
                )
            },
            confirmButton = { TextButton(onClick = { previewExtractFile = null }) { Text("关闭") } }
        )
    }
    renameTarget?.let { t ->
        var text by remember(t) { mutableStateOf(t.current) }
        AlertDialog(
            modifier = Modifier.motionEnter(),
            onDismissRequest = { renameTarget = null },
            title = { Text("重命名", style = MaterialTheme.typography.titleSmall) },
            text = {
                OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = true, modifier = Modifier.fillMaxWidth())
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        when (t.kind) {
                            RenameKind.PROJECT -> Graph.projects.rename(t.id, text)
                            RenameKind.FOLDER -> Graph.folders.rename(t.id, text)
                            RenameKind.SESSION -> Graph.chat.renameSession(t.id, text)
                        }
                    }
                    renameTarget = null
                }) { Text("确定") }
            },
            dismissButton = { TextButton(onClick = { renameTarget = null }) { Text("取消") } }
        )
    }
    newFolderParent?.let { (pid, parentId) ->
        var text by remember { mutableStateOf("") }
        AlertDialog(
            modifier = Modifier.motionEnter(),
            onDismissRequest = { newFolderParent = null },
            title = { Text("新建子文件夹", style = MaterialTheme.typography.titleSmall) },
            text = {
                OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = true, placeholder = { Text("文件夹名称") }, modifier = Modifier.fillMaxWidth())
            },
            confirmButton = {
                TextButton(enabled = text.isNotBlank(), onClick = {
                    scope.launch { Graph.folders.create(pid, parentId, text) }
                    newFolderParent = null
                }) { Text("创建") }
            },
            dismissButton = { TextButton(onClick = { newFolderParent = null }) { Text("取消") } }
        )
    }
    moveSessionId?.let { sid ->
        FolderMoveSheet(sessionId = sid, onDismiss = { moveSessionId = null })
    }
    deleteTarget?.let { t ->
        if (t.kind == DeleteKind.PROJECT) {
            // 删除项目不再是"能不能删"的问题，而是"想怎么处理"：
            // 归档（数据一字不动、随时可恢复） vs 强制删除（项目与其下全部数据一起清）。
            // 两个按钮都在这一层给全，别让用户先点删除、再被"请先移走"打回来。
            AlertDialog(
                modifier = Modifier.motionEnter(),
                onDismissRequest = { deleteTarget = null },
                title = { Text("项目「" + t.name + "」", style = MaterialTheme.typography.titleSmall) },
                text = {
                    Text(
                        "· 归档：项目从抽屉里消失，数据一字不动，可在「归档项目」里恢复。\n\n" +
                            "· 强制删除：项目、对话、消息、笔记、记忆、项目文件夹一并删除，不可恢复。\n" +
                            "· **日程与待办会保留**，自动转为全局（不会再跟着项目消失）。\n\n" +
                            "删除前会自动存一份整库快照到 Download/Planability（v44 起加密，" +
                                "要拿明文在「自动化 → 导入 → 本机快照」里解密导出）。"
                    )
                },
                confirmButton = {
                    // 2026-11-16 用户「彻底删除项目有延迟」：原来点完就 `deleteTarget = null`，
                    // 弹窗瞬间消失但删除还在跑（快照整库拷贝 + 递归删项目目录，大项目要几秒），
                    // 期间抽屉里那行还在、也没有任何提示 → 用户以为没生效，反复点。
                    // 现在：按钮切成「删除中…」禁用态，**等删完再关**弹窗。
                    if (deleting) {
                        TextButton(onClick = {}, enabled = false) {
                            androidx.compose.material3.CircularProgressIndicator(
                                modifier = Modifier.size(15.dp),
                                strokeWidth = 2.dp
                            )
                            Spacer(Modifier.width(8.dp))
                            Text("删除中…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    } else {
                        TextButton(onClick = {
                            haptics.perform(HapticEngine.Kind.ImpactHeavy)
                            deleting = true
                            scope.launch {
                                // 2026-10-21 真事故（删项目把整张课表带走）之后加的保险：
                                // 先强制落一份整库快照，再动数据。
                                runCatching { AutoBackup.snapshot(force = true) }
                                runCatching { Graph.projects.deleteForce(t.id) }
                                // finally 语义：无论成功失败都要解锁并关掉弹窗，否则卡在
                                // 「删除中…」再也点不动。
                                deleting = false
                                deleteTarget = null
                            }
                        }) { Text("强制删除", color = MaterialTheme.colorScheme.error) }
                    }
                },
                dismissButton = {
                    // 删除进行中不许从旁路关掉对话框（数据正在被清，界面必须给一个确定的落点）
                    Row {
                        TextButton(onClick = { if (!deleting) deleteTarget = null }, enabled = !deleting) { Text("取消") }
                        TextButton(onClick = {
                            haptics.perform(HapticEngine.Kind.Select)
                            scope.launch { Graph.projects.setArchived(t.id, true) }
                            deleteTarget = null
                        }, enabled = !deleting) { Text("归档") }
                    }
                }
            )
        } else {
        AlertDialog(
            modifier = Modifier.motionEnter(),
            onDismissRequest = { deleteTarget = null },
            title = { Text("删除", style = MaterialTheme.typography.titleSmall) },
            text = {
                Text(
                    when (t.kind) {
                        DeleteKind.PROJECT -> "删除项目「" + t.name + "」？项目下的对话/笔记/记忆会阻止删除，需先移走。"
                        DeleteKind.FOLDER -> "删除文件夹「" + t.name + "」？其中内容将上移到上级，不丢数据。"
                        DeleteKind.SESSION -> "删除对话「" + t.name + "」？消息将一并删除。"
                    }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        when (t.kind) {
                            // v19.62：delete 返回 false = 项目下还有数据。原来会"回落未分类"，
                            // 现在未分类没了，只能拒绝并告诉用户先去移走。
                            DeleteKind.PROJECT -> if (!Graph.projects.delete(t.id)) {
                                deleteBlocked = "项目下还有对话/笔记/记忆/日程/待办，请先把它们移走再删项目。"
                            }
                            DeleteKind.FOLDER -> Graph.folders.delete(t.id)
                            DeleteKind.SESSION -> Graph.chat.deleteSession(t.id)
                        }
                    }
                    deleteTarget = null
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("取消") } }
        )
        }
    }

    // v19.62：删除项目被拒（项目下还有数据）。未分类废除后没有"回落"这个选项，
    // 只能明说：先移走数据。用 Dialog 而不是 toast——本层没有 Snackbar/ toast 通道，
    // 而这类"为什么我没删掉"必须让人看见。
    deleteBlocked?.let { msg ->
        AlertDialog(
            modifier = Modifier.motionEnter(),
            onDismissRequest = { deleteBlocked = null },
            title = { Text("删不掉这个项目") },
            text = { Text(msg) },
            confirmButton = { TextButton(onClick = { deleteBlocked = null }) { Text("知道了") } }
        )
    }
}

// ---------- 数据类型 ----------

private enum class RenameKind { PROJECT, FOLDER, SESSION }
private data class RenameTarget(val id: Long, val current: String, val kind: RenameKind)
private enum class DeleteKind { PROJECT, FOLDER, SESSION }
private data class DeleteTarget(val id: Long, val name: String, val kind: DeleteKind)

/** 用系统方式打开项目目录内的文件（与文件页打开逻辑一致） */
internal fun openProjectFile(context: android.content.Context, f: File) {
    runCatching {
        val uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", f)
        val mime = mimeOf(f.name)
        context.startActivity(
            android.content.Intent(android.content.Intent.ACTION_VIEW)
                .setDataAndType(uri, mime)
                .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        )
    }
}

private fun formatSize(bytes: Long): String = when {
    bytes >= 1 shl 20 -> (bytes / (1 shl 20)).toString() + "." + (bytes % (1 shl 20)) * 10 / (1 shl 20) + "MB"
    bytes >= 1 shl 10 -> (bytes / (1 shl 10)).toString() + "KB"
    else -> bytes.toString() + "B"
}

/** 递归画某父节点下的子文件夹与其会话 */
@Composable
private fun DrawFolderChildren(
    projectId: Long,
    parentId: Long,
    depth: Int,
    folders: List<Folder>,
    sessions: List<ChatSession>,
    expanded: List<String>,
    onToggle: (String) -> Unit,
    onOpenSession: (Long) -> Unit,
    onNewChatIn: (Long, Long) -> Unit,
    onNewSubFolder: (Long) -> Unit,
    onRenameFolder: (Folder) -> Unit,
    onDeleteFolder: (Folder) -> Unit,
    onMoveSession: (Long) -> Unit,
    onRenameSession: (ChatSession) -> Unit,
    onArchiveSession: (ChatSession) -> Unit,
    onDeleteSession: (ChatSession) -> Unit
) {
    val scope = rememberCoroutineScope()
    // 文件夹菜单里的命令点击 → Tap（与抽屉其余行同一档）
    val haptics = LocalHaptics.current
    val children = folders.filter { it.parentId == parentId }
    children.forEach { f ->
        val key = "f" + f.id
        val fSessions = sessions.filter { it.folderId == f.id }.sortedByDescending { it.updatedAt }
        // hasChildren=false（内容删光）时展开态一并钉回收起——否则文件夹卡在「展开」图标
        val folderHasKids = fSessions.isNotEmpty() || folders.any { it.parentId == f.id }
        TreeGroupRow(
            depth = depth, expanded = key in expanded && folderHasKids, hasChildren = folderHasKids,
            icon = if (key in expanded) AppIcons.Kind.FolderOpen else AppIcons.Kind.Folder,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            title = f.name, badge = if (fSessions.isNotEmpty()) fSessions.size.toString() else "",
            onToggle = { onToggle(key) },
            menu = { close ->
                DropdownMenuItem(
                    modifier = Modifier.clip(RoundedCornerShape(10.dp)), // 2026-11-15 点击高亮圆角化（原来是整行直角方块）
                    text = { Text("新建对话") },
                    leadingIcon = { Icon(AppIcons.Kind.Chat, null, Modifier.size(17.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                    onClick = { close(); haptics.perform(HapticEngine.Kind.Tap); onNewChatIn(projectId, f.id) }
                )
                DropdownMenuItem(
                    modifier = Modifier.clip(RoundedCornerShape(10.dp)), // 2026-11-15 点击高亮圆角化（原来是整行直角方块）
                    text = { Text("新建子文件夹") },
                    leadingIcon = { Icon(AppIcons.Kind.Folder, null, Modifier.size(17.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                    onClick = { close(); haptics.perform(HapticEngine.Kind.Tap); onNewSubFolder(f.id) }
                )
                DropdownMenuItem(
                    modifier = Modifier.clip(RoundedCornerShape(10.dp)), // 2026-11-15 点击高亮圆角化（原来是整行直角方块）
                    text = { Text("重命名") },
                    leadingIcon = { Icon(AppIcons.Act.Rename, null, Modifier.size(17.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                    onClick = { close(); haptics.perform(HapticEngine.Kind.Tap); onRenameFolder(f) }
                )
                DropdownMenuItem(
                    modifier = Modifier.clip(RoundedCornerShape(10.dp)), // 2026-11-15 点击高亮圆角化（原来是整行直角方块）
                    text = { Text("删除", color = MaterialTheme.colorScheme.error) },
                    leadingIcon = { Icon(AppIcons.Act.Delete, null, Modifier.size(17.dp), tint = MaterialTheme.colorScheme.error) },
                    onClick = { close(); haptics.perform(HapticEngine.Kind.Tap); onDeleteFolder(f) }
                )
            }
        )
        if (key in expanded) {
            fSessions.forEach { s ->
                SessionTreeRow(depth + 1, s, onOpenSession, onMove = { onMoveSession(s.id) }, onRename = { onRenameSession(s) }, onArchive = { onArchiveSession(s) }, onDelete = { onDeleteSession(s) })
            }
            DrawFolderChildren(
                projectId, f.id, depth + 1, folders, sessions, expanded,
                onToggle, onOpenSession, onNewChatIn, onNewSubFolder,
                onRenameFolder, onDeleteFolder, onMoveSession, onRenameSession, onArchiveSession, onDeleteSession
            )
        }
    }
    // 直接挂在该节点的会话（folderId == parentId）
    val direct = sessions.filter { it.folderId == parentId }.sortedByDescending { it.updatedAt }
    if (parentId == 0L) {
        direct.forEach { s ->
            SessionTreeRow(depth, s, onOpenSession, onMove = { onMoveSession(s.id) }, onRename = { onRenameSession(s) }, onArchive = { onArchiveSession(s) }, onDelete = { onDeleteSession(s) })
        }
    }
}

// ==================== 抽屉行高 ====================
//
// 行高以前是「垂直内边距 + 各行自己内容的自然高度」凑出来的，于是三处出问题：
//  ① TreeGroupRow 里有 ⋮ 菜单的行 42dp、没有的行 64dp —— 占位用的是 Spacer(48.dp)，
//     依据是「IconButton 会被最小触摸目标撑回 48dp」。这个依据是错的：
//     Modifier.size(26.dp) 从外层就把约束钳成了 26，根本撑不回去。
//     实测（真机截图反推行距）是 42 / 55 / 63dp —— 正好等于 42、42↔64 的均值、64。
//     也就是用户圈出来的「不整齐」。
//  ② DrawerActionRow 靠 padding(vertical = 20.dp) 顶到 64dp 去对齐分组行，
//     结果是「上下间距太大」，而且对的还是 ① 里那个本来就不稳定的 64。
//  ③ LeafRow / ArchivedSubRow 是 34dp、SessionTreeRow 40dp，全是隐式值，没人对过。
//
// 现在改成**显式行高**，高度是写死的常量而不是算出来的，内容再怎么改
// （加图标/换文案）都不会把行撑歪。
//
// v19.39 **统一成一档 40dp**：原来是「父级 48dp、子级 40dp」两档，
// 结果动作行（新建对话/搜索/…）和分组行（未分类/项目根）比会话行**散**，
// 每两行之间的间距对不齐 —— 用户要求「所有按钮行间距对齐每两个会话的行间距，
// 即使是最后一个会话与下一个文件夹之间」。分组行现在靠胶囊底色区分，
// 不再靠"比别人高"来制造层级。
// ==================== 信息提取目录结构（2026-11-15） ====================
//
// 目录约定（与 capture/ 的写入侧对齐）：
//   filesDir/信息提取/聊天原文/<群>/<日期>/<文件>
//   filesDir/信息提取/聊天提纯/             ← 预留（会话本体在数据库，不落盘）
//
// 老结构是 filesDir/信息提取/<群>/<日期>/ —— 迁移把每个群目录整体下移一层。
// 幂等：目标已存在就跳过（不覆盖用户可能已产生的同名新目录），可重入。
internal const val EXTRACT_DIR_NAME = "信息提取"
internal const val EXTRACT_RAW_NAME = "聊天原文"
internal const val EXTRACT_CHAT_NAME = "聊天提纯"

/**
 * 把老的 `信息提取/<群>/…` 下移到 `信息提取/聊天原文/<群>/…`（2026-11-15）。
 *
 * 规则（用户手机上已有 10 个群目录与历史日期目录，必须一个不丢）：
 * - 只处理**目录**；根下的散落文件不动（不属于任何群）；
 * - 名字是「聊天原文」/「聊天提纯」的目录是**新结构自身**，绝不能被当成群搬走；
 * - 目标已存在同名目录 → 跳过并记日志（不合并、不覆盖；合并的语义风险远大于收益）；
 * - 单个群 rename 失败（占用/权限）→ 记日志继续下一个，不中断整批。
 *
 * @return 实际搬走的群目录个数
 */
internal fun migrateExtractRawIntoSubdir(root: java.io.File): ExtractMigrationResult {
    if (!root.isDirectory) return ExtractMigrationResult()
    val rawRoot = java.io.File(root, EXTRACT_RAW_NAME)
    val groups = root.listFiles()
        ?.filter { it.isDirectory && it.name != EXTRACT_RAW_NAME && it.name != EXTRACT_CHAT_NAME }
        .orEmpty()
    if (groups.isEmpty()) return ExtractMigrationResult()
    if (!rawRoot.exists() && !rawRoot.mkdirs()) {
        return ExtractMigrationResult(cannotCreateRoot = true)
    }
    val moved = mutableListOf<String>()
    val skippedExisting = mutableListOf<String>()
    val failed = mutableListOf<String>()
    groups.forEach { g ->
        val dst = java.io.File(rawRoot, g.name)
        when {
            dst.exists() -> skippedExisting += g.name
            g.renameTo(dst) -> moved += g.name
            else -> failed += g.name
        }
    }
    return ExtractMigrationResult(moved, skippedExisting, failed)
}

/**
 * 迁移结果（纯数据，不打日志）：调用侧负责 Log。
 * 之所以不在迁移函数里直接 `android.util.Log`——纯函数才能在单测里跑（Log 在 JVM 单测里未 mock）。
 */
internal data class ExtractMigrationResult(
    val moved: List<String> = emptyList(),
    /** 目标已存在同名目录 → 跳过（不合并不覆盖） */
    val skippedExisting: List<String> = emptyList(),
    /** rename 失败（占用/权限），下一轮会重试 */
    val failed: List<String> = emptyList(),
    /** 连 聊天原文/ 都建不出来（存储异常） */
    val cannotCreateRoot: Boolean = false
) {
    /** 只有「确实搬了」或「有异常」才值得打日志，避免每次开抽屉刷屏 */
    fun logTag(): String? = when {
        cannotCreateRoot -> "信息提取迁移：无法创建 $EXTRACT_RAW_NAME/ 目录，本次跳过"
        moved.isNotEmpty() -> "信息提取迁移：${moved.size} 个群目录下移到 $EXTRACT_RAW_NAME/"
        skippedExisting.isNotEmpty() -> "信息提取迁移：目标已存在，跳过 ${skippedExisting.size} 个"
        failed.isNotEmpty() -> "信息提取迁移：${failed.size} 个群目录 rename 失败，下次重试"
        else -> null
    }
}

/** 父级行：动作行 + 分组行 */
private val DRAWER_ROW_PARENT = 40.dp

/** 子级行：会话叶 / 笔记文件叶 / 已归档分组（与父级同高，行距才齐） */
private val DRAWER_ROW_CHILD = 40.dp

/** 分组行尾部（⋮ 菜单或它的占位）宽度。两种分支必须同宽，否则计数右边缘参差。 */
private val DRAWER_TRAIL_W = 48.dp

/** 展开/收起分组行（项目根/文件夹/未分类/已归档） */
@Composable
private fun TreeGroupRow(
    depth: Int,
    expanded: Boolean,
    hasChildren: Boolean,
    icon: ImageVector,
    tint: androidx.compose.ui.graphics.Color,
    title: String,
    badge: String,
    onToggle: () -> Unit,
    menu: (@Composable (closeMenu: () -> Unit) -> Unit)?,
    /** 胶囊底色。默认中性灰；内容型文件夹行传 secondaryContainer（2026-10-28 用户：与次级文件区分） */
    pillColor: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.surfaceContainer
) {
    var menuOpen by remember { mutableStateOf(false) }
    val haptics = LocalHaptics.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            // 显式行高：有菜单的行和没菜单的行必须是同一个高度，
            // 不能靠「内容自然高度 + 垂直内边距」去凑（那正是 42/64dp 参差的来源）。
            .height(DRAWER_ROW_PARENT)
            // ⚠ 缩进必须放在**底色之前**。
            // 原来 background 写在 padding(start = 10 + depth*16) 前面，而 background 的绘制
            // 区域取的是当时的大小 —— 胶囊永远满宽。真机量过：「项目数据」(depth=1) 与
            // 「信息提取」(depth=0) 的胶囊左边缘都是 x=50px，像素级相同，
            // 于是子文件夹看着和大文件夹平级（用户反馈）。挪到前面后，胶囊自己跟着缩进。
            //
            // 上下各让 4dp：相邻两颗分组行（比如折叠状态下紧挨着的「信息提取」+「自动化」）
            // 原来严丝合缝，两段圆角贴成一条长条（用户反馈「胶囊粘一起」，实测 3 行连成
            // 420px 的一整带）。行高仍是 DRAWER_ROW_PARENT，所以粘顶位移/折叠还原那套
            // 计算（collapseTopPadPx 等）完全不受影响。
            //
            // ⚠ 这 4dp（上下共 8dp）是**透的**：分组头被钉在视口顶 —— 也就是用户口径的
            // 「暂时锁定」—— 时，下面滚过去的会话/文件会从这两条缝里露出来。
            // 2026-10-19 用户反馈：「胶囊进入暂时锁定状态时胶囊上面出现缝隙能看到滑过去的文件」。
            // 所以三个 `stickyHeader` 的 Box 都铺了一层 `fillMaxWidth().background(...)` 填缝。
            // 填色必须**精确等于抽屉底色**，否则钉住时会看到一条色带：
            // `ModalDrawerSheet` 默认 containerColor = `DrawerDefaults.modalContainerColor`
            // = `NavigationDrawerTokens.ModalContainerColor` = `SurfaceContainerLow`
            // （已于 material3 1.4.0 sources 核实；且 ModalDrawerElevation = Level0，无色调叠加，
            //  所以直接用 `MaterialTheme.colorScheme.surfaceContainerLow` 就是同色）。
            .padding(start = (depth * 16).dp, top = 4.dp, bottom = 4.dp)
            // 胶囊包裹：整行收成胶囊（percent=50 全圆角 + surfaceContainer），
            // 和文件页的存储胶囊、底部导航的胶囊是同一套语言。
            .background(pillColor, RoundedCornerShape(percent = 50))
            .clip(RoundedCornerShape(percent = 50))
            // 分组行：点击 = 展开/收起；**长按 = 弹同一个 ⋮ 菜单**（2026-10-21 用户要求）。
            .combinedClickable(
                onClick = {
                    // 展开/收起是中性点击（Haptics.kt：Tap 用于"顶栏返回、展开/收起"这类），
                    // 原来这条没有反馈，只有长按有 —— 手指按下去"没反应"最容易被当成没点上
                    if (hasChildren) {
                        haptics.perform(HapticEngine.Kind.Tap)
                        onToggle()
                    }
                },
                onLongClick = if (menu != null) {
                    {
                        haptics.perform(HapticEngine.Kind.ImpactLight)
                        menuOpen = true
                    }
                } else null
            )
            // 内容缩进 22dp（层级的 16dp 已经在上面那层 padding 里给过了，别加两遍）。
            //
            // 为什么是 22 而不是 10：`SessionTreeRow` 的内容起点是 `22 + depth*16`
            // （会话行自己的 padding），而分组行原来是 `depth*16 + 10` —— 同一个 depth=1，
            // 会话图标在 38dp、分组图标在 26dp，差 12dp，于是「项目数据」看着像
            // 它下面那些对话的**父级**而不是兄弟（用户反馈）。
            // 现在分组行与叶子的内容起点统一成 `22 + depth*16`，图标落在同一根竖线上。
            .padding(start = 22.dp, end = 6.dp)
    ) {
        Icon(icon, null, Modifier.size(18.dp), tint = tint)
        Spacer(Modifier.width(8.dp))
        Text(
            title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        if (badge.isNotEmpty()) {
            // 标题 weight(1f) 先吃掉剩余宽度，所以后面的计数会被推到右边，
            // 右边缘统一落在「尾部 48dp 块的左边线 - 6dp」上 → 152/4/2/1 严格右对齐。
            Text(badge, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(6.dp))
        }
        if (menu != null) {
            Box(Modifier.size(DRAWER_TRAIL_W), contentAlignment = Alignment.Center) {
                // ⋮ 本身也要撑满尾部块：**触摸目标** 48dp（原来写 size(26.dp) 只有 26dp，
                // 低于 Material 的最小触摸目标），**宽度** 48dp（与另一分支的占位等宽）。
                // 把它写成 fillMaxSize 而不是 size(26)，两个目的一次满足。
                IconButton(
                    onClick = { haptics.perform(HapticEngine.Kind.Tap); menuOpen = true },
                    modifier = Modifier.fillMaxSize()
                ) {
                    Icon(AppIcons.Act.More, "更多", Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) { menu { menuOpen = false } }
            }
        } else {
            // 宽度占位：只管把左边的计数顶到同一根竖线上。
            // 高度不用管——行高已经由 height(DRAWER_ROW_PARENT) 定死了。
            Spacer(Modifier.width(DRAWER_TRAIL_W))
        }
    }
}

/** 会话叶 */
@Composable
private fun SessionTreeRow(
    depth: Int,
    s: ChatSession,
    onOpenSession: (Long) -> Unit,
    dimmed: Boolean = false,
    onMove: () -> Unit,
    onRename: () -> Unit,
    onArchive: () -> Unit,
    onDelete: () -> Unit
) {
    var menuOpen by remember { mutableStateOf(false) }
    val haptics = LocalHaptics.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable { haptics.perform(HapticEngine.Kind.Tap); onOpenSession(s.id) }
            .height(DRAWER_ROW_CHILD)
            // end 与分组行同为 6dp：尾部 48dp 块 + Spacer(6) 才能和文件夹的
            // 计数/⋮ 落在同一根竖线上。
            .padding(start = (22 + depth * 16).dp, end = 6.dp)
            .let { m -> if (dimmed) m.alpha(0.55f) else m }
    ) {
        Icon(AppIcons.Kind.Chat, null, Modifier.size(15.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(7.dp))
        Text(
            s.title, style = MaterialTheme.typography.bodySmall,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Text(relTime(s.updatedAt), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        // 尾部必须和分组行（文件夹胶囊）**同一套几何**，否则
        // 「文件夹的计数 / ⋮」和「会话的时间 / ⋮」两条竖线对不齐（用户要求）。
        // 分组行是：badge + Spacer(6) + 48dp 尾块（⋮ 居中），这里照抄一份。
        Spacer(Modifier.width(6.dp))
        Box(Modifier.size(DRAWER_TRAIL_W), contentAlignment = Alignment.Center) {
            IconButton(onClick = { haptics.perform(HapticEngine.Kind.Tap); menuOpen = true }, modifier = Modifier.fillMaxSize()) {
                Icon(AppIcons.Act.More, "更多", Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                // 菜单项 = 命令类中性点击 → Tap（与 LeafRow/DrawerActionRow 同档）
                DropdownMenuItem(
                    modifier = Modifier.clip(RoundedCornerShape(10.dp)), // 2026-11-15 点击高亮圆角化（原来是整行直角方块）
                    text = { Text("移动到…") },
                    // 动作 ≠ 内容：移动到…不再借用 FolderCopy（那是分组节点的形）
                    leadingIcon = { Icon(AppIcons.Act.Move, null, Modifier.size(17.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                    onClick = { menuOpen = false; haptics.perform(HapticEngine.Kind.Tap); onMove() }
                )
                DropdownMenuItem(
                    modifier = Modifier.clip(RoundedCornerShape(10.dp)), // 2026-11-15 点击高亮圆角化（原来是整行直角方块）
                    text = { Text("重命名") },
                    leadingIcon = { Icon(AppIcons.Act.Rename, null, Modifier.size(17.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                    onClick = { menuOpen = false; haptics.perform(HapticEngine.Kind.Tap); onRename() }
                )
                DropdownMenuItem(
                    modifier = Modifier.clip(RoundedCornerShape(10.dp)), // 2026-11-15 点击高亮圆角化（原来是整行直角方块）
                    text = { Text(if (s.archived) "取消归档" else "归档") },
                    leadingIcon = { Icon(AppIcons.Act.Archive, null, Modifier.size(17.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                    onClick = { menuOpen = false; haptics.perform(HapticEngine.Kind.Tap); onArchive() }
                )
                DropdownMenuItem(
                    modifier = Modifier.clip(RoundedCornerShape(10.dp)), // 2026-11-15 点击高亮圆角化（原来是整行直角方块）
                    text = { Text("删除", color = MaterialTheme.colorScheme.error) },
                    leadingIcon = { Icon(AppIcons.Act.Delete, null, Modifier.size(17.dp), tint = MaterialTheme.colorScheme.error) },
                    onClick = { menuOpen = false; haptics.perform(HapticEngine.Kind.Tap); onDelete() }
                )
            }
        }
    }
}

/** 笔记/文件叶 */
@Composable
private fun LeafRow(
    depth: Int,
    icon: ImageVector,
    tint: androidx.compose.ui.graphics.Color,
    title: String,
    sub: String,
    /** 长按弹出的二级菜单（null = 不可长按）；与分组行的 ⋮ 菜单同构 */
    menu: (@Composable (closeMenu: () -> Unit) -> Unit)? = null,
    /**
     * 胶囊底色（null = 透明，普通叶子）。2026-11-11 用户：信息获取·提纯、自动化文件夹
     * 下的入口行要有与文件夹/会话同款的胶囊背景——传 surfaceContainer 即可对齐
     * depth 同档的文件夹行（同一套缩进/行高/圆角几何，见下）。
     */
    pillColor: androidx.compose.ui.graphics.Color? = null,
    onClick: () -> Unit
) {
    val haptics = LocalHaptics.current
    var menuOpen by remember { mutableStateOf(false) }
    Box {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .let { m ->
                    if (pillColor == null) m.clip(RoundedCornerShape(10.dp)) else m
                        // 胶囊版与 TreeGroupRow 同一套几何：缩进先于底色（胶囊跟缩进收窄）、
                        // 上下各让 4dp、行高不变——相邻文件夹行严格等距。
                        .height(DRAWER_ROW_CHILD)
                        .padding(start = (depth * 16).dp, top = 4.dp, bottom = 4.dp)
                        .background(pillColor, RoundedCornerShape(percent = 50))
                        .clip(RoundedCornerShape(percent = 50))
                }
                // 长按 = 弹二级菜单（2026-10-21 用户要求：右滑菜单里的文件/文件夹长按也要有二级菜单）。
                // 点击行为不变；没有菜单的行（笔记等）退回普通 clickable。
                .then(
                    if (menu != null) {
                        Modifier.combinedClickable(
                            onClick = { haptics.perform(HapticEngine.Kind.Tap); onClick() },
                            onLongClick = {
                                haptics.perform(HapticEngine.Kind.ImpactLight)
                                menuOpen = true
                            }
                        )
                    } else {
                        Modifier.clickable { haptics.perform(HapticEngine.Kind.Tap); onClick() }
                    }
                )
                .then(if (pillColor == null) Modifier.height(DRAWER_ROW_CHILD) else Modifier)
                .padding(start = (if (pillColor == null) (22 + depth * 16) else 22).dp, end = 6.dp)
        ) {
            Icon(icon, null, Modifier.size(15.dp), tint = tint)
            Spacer(Modifier.width(7.dp))
            Text(
                title, style = MaterialTheme.typography.bodySmall,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Text(sub, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            // 空尾块：**只为对齐** —— 让这个 sub（时间）和会话时间、文件夹计数
            // 落在同一根竖线上。没有它，文件叶的副标题会比别的行更靠右。
            Spacer(Modifier.width(6.dp))
            Box(Modifier.size(DRAWER_TRAIL_W))
        }
        if (menu != null) {
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) { menu { menuOpen = false } }
        }
    }
}

@Composable
private fun ArchivedSubRow(depth: Int, count: Int, expanded: List<String>, onToggle: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onToggle)
            .height(DRAWER_ROW_CHILD)
            .padding(start = (6 + depth * 16).dp, end = 10.dp)
    ) {
        Icon(AppIcons.Act.Archive, null, Modifier.size(15.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(7.dp))
        Text("已归档（$count）", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun DrawerActionRow(icon: ImageVector, title: String, onClick: () -> Unit) {
    val haptics = LocalHaptics.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            // 行高与分组行同一个常量：折叠抽屉里「动作行」和「分组行」必须严格等距，
            // 否则两段之间看着就是断的。以前这里靠 padding(vertical = 20.dp) 凑 64dp，
            // 一来对的是分组行那个本来就不稳的 64，二来 40dp 的垂直内边距就是
            // 用户说的「上下间距太大」。现在行高写死、内边距只管上下留白。
            .height(DRAWER_ROW_PARENT)
            .clip(RoundedCornerShape(12.dp))
            .clickable { haptics.perform(HapticEngine.Kind.Tap); onClick() }
            // 行高从 48 降到 40（与会话行齐平）后，垂直内边距必须跟着从 12 降到 8，
            // 内容框才是 40-16=24dp，跟原来 48-24=24dp 一样 —— 图标 18dp 不会被裁。
            .padding(horizontal = 10.dp, vertical = 8.dp)
    ) {
        Icon(icon, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(12.dp))
        // 行高是定死的 48dp（内容区 48-24=24dp）：系统字体调大时不能让它折成两行再被裁掉，
        // 单行省略号比垂直裁切好看得多。
        Text(
            title, style = MaterialTheme.typography.bodyMedium,
            maxLines = 1, overflow = TextOverflow.Ellipsis
        )
    }
}

/** v8 移动到项目/文件夹：树形两级选择（未分类 + 项目根 + 项目内子文件夹） */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun FolderMoveSheet(
    sessionId: Long,
    onDismiss: () -> Unit,
    onMoved: (projectId: Long) -> Unit = {}
) {
    val scope = rememberCoroutineScope()
    val version by Graph.dataVersion.flow.collectAsState()
    var projects by remember { mutableStateOf<List<Project>>(emptyList()) }
    var folders by remember { mutableStateOf<List<Folder>>(emptyList()) }
    var session by remember { mutableStateOf<ChatSession?>(null) }
    LaunchedEffect(version) {
        projects = Graph.projects.all()
        folders = Graph.folders.all()
        session = Graph.chat.session(sessionId)
    }
    val openProjects = projects.filter { it.id == session?.projectId } + projects.filter { it.id != session?.projectId }
    androidx.compose.material3.ModalBottomSheet(contentWindowInsets = { WindowInsets.navigationBars }, 
        dragHandle = { io.github.m0use001.planability.ui.common.SheetDragHandleOnly() },
        onDismissRequest = onDismiss,
        sheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        Column(Modifier.padding(horizontal = 18.dp, vertical = 8.dp)) {
            Text("移动到…", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(10.dp))
            Column(Modifier.verticalScroll(rememberScrollState()).heightIn(max = 480.dp)) {
                openProjects.forEach { p ->
                    PickerRow2(AppIcons.Kind.project(p.iconKey), p.name, session?.projectId == p.id && (session?.folderId ?: 0L) == 0L) {
                        scope.launch { Graph.chat.setSessionFolder(sessionId, p.id, 0L) }
                        onMoved(p.id)
                        onDismiss()
                    }
                    folders.filter { it.projectId == p.id }.forEach { f ->
                        PickerRow2(AppIcons.Kind.Folder, "　" + f.name, session?.folderId == f.id) {
                            scope.launch { Graph.chat.setSessionFolder(sessionId, p.id, f.id) }
                            onMoved(p.id)
                            onDismiss()
                        }
                    }
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun PickerRow2(icon: ImageVector, title: String, selected: Boolean, onClick: () -> Unit) {
    val haptics = LocalHaptics.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable { haptics.perform(HapticEngine.Kind.Select); onClick() }
            .padding(horizontal = 10.dp, vertical = 12.dp)
    ) {
        Icon(icon, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(12.dp))
        Text(title, style = MaterialTheme.typography.bodyMedium, color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
    }
}
