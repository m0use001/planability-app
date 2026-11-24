package io.github.m0use001.planability.ui.info

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.m0use001.planability.app.PredictiveBackOverlay
import io.github.m0use001.planability.core.HapticEngine
import io.github.m0use001.planability.core.LocalHaptics
import io.github.m0use001.planability.data.Graph
import io.github.m0use001.planability.data.Project
import io.github.m0use001.planability.ui.common.AppButtonGroup
import io.github.m0use001.planability.ui.common.AppIcons
import io.github.m0use001.planability.ui.common.ConfirmDialog
import kotlinx.coroutines.launch

/**
 * v53.3：「资料」总汇页（用户 2026-11-06 拍板「6 行：删重 + 资料 hub」）。
 *
 * 抽屉底部原本 13 行，其中「记忆库 / 文件库 / 归档项目」三行都是**只读数据入口**
 * （看记忆、看文件、看归档的项目），既不配置 AI 也不改系统设置，所以合成一屏三 tab：
 *
 * - 记忆库   = [MemoryScreen]（保留它自己的顶栏动作：立即整理 / + 新增）
 * - 文件库   = [FileLibraryScreen]
 * - 归档项目 = [ArchivedProjectsScreen]（本文件；原来是抽屉里的 AlertDialog，
 *              挪进 Hub 后才有一屏之地，不再被 dialog 的 360dp 高度限制）
 *
 * 图标的选型：抽屉行与「我的」页同名入口共用 [AppIcons.Setting.DataHub]。
 */
private const val KEY_DATA_TAB = "shell.freeze.hub.data"

@Composable
fun DataHub(onDone: () -> Unit) {
    var tab by remember { mutableStateOf(0) }
    var restored by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        Graph.settings.kvGet(KEY_DATA_TAB)?.toIntOrNull()?.takeIf { it in 0..2 }?.let { tab = it }
        restored = true
    }
    LaunchedEffect(tab, restored) {
        if (restored) Graph.settings.kvPut(KEY_DATA_TAB, tab.toString())
    }

    val switcher: @Composable () -> Unit = {
        Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp)) {
            AppButtonGroup(
                options = listOf("记忆库", "文件库", "归档项目"),
                selectedIndex = tab,
                onSelect = { tab = it },
                // 不写死宽度：560dpi 机型可用宽约 308dp，钉死会裁字（见 AppButtonGroup v53.2）
                modifier = Modifier.fillMaxWidth()
            )
        }
    }

    // 返回键分层：文件库内部有自己的「预览→目录层级→关页」三层，
    // 所以 onBack 交给当前页自己处理——这里只负责「Hub 自身的返回」。
    PredictiveBackOverlay(onBack = onDone) {
        if (io.github.m0use001.planability.core.rememberWindowSizeClass().isWide) {
            // v58 平板（iPad HIG sidebar）：宽屏把三段切换升级为**侧栏 + 详情分栏**——
            // 段控横拉 1000dp 是手机口径，侧栏是平板上这类「少数固定目的地」的标准形态
            // （层级 ≤2，符合 HIG sidebar 规范）。详情页沿用各自的 Scaffold/顶栏。
            // 2026-11-20 v65 两板推广：分栏壳从「手写 Row + VerticalDivider」换成共享的
            // SplitBoards（定宽板在左）——这是全 app 最后一根 VerticalDivider，自此
            // 「栏间只留白缝」的材质规则全仓成立（WideBoardLayoutGuardTest 钉住）。
            io.github.m0use001.planability.ui.common.SplitBoards(
                twoPane = true,
                fixedFirst = true,
                detailWidth = 280.dp,
                // 侧栏与详情页各自自理系统栏 inset（与改造前一致，壳再垫一遍就是双倍）
                applyTopInset = false,
                applyBottomInset = false,
                // 定宽板（左）= 侧栏；弹性板（右）= 详情
                secondary = {
                    DataHubSidebar(
                        tab = tab,
                        onSelect = { tab = it },
                        onBack = onDone
                    )
                },
                primary = {
                    when (tab) {
                        0 -> MemoryScreen(onDone = onDone, topSwitcher = null, insideOverlay = true)
                        1 -> FileLibraryScreen(onDone = onDone, topSwitcher = null, insideOverlay = true)
                        else -> ArchivedProjectsScreen(onDone = onDone, topSwitcher = null)
                    }
                }
            )
        } else {
            when (tab) {
                0 -> MemoryScreen(
                    onDone = onDone, topSwitcher = switcher, insideOverlay = true
                )
                1 -> FileLibraryScreen(
                    onDone = onDone, topSwitcher = switcher, insideOverlay = true
                )
                else -> ArchivedProjectsScreen(
                    onDone = onDone, topSwitcher = switcher
                )
            }
        }
    }
}

/**
 * v58 宽屏侧栏：记忆库 / 文件库 / 归档项目三个目的地竖排（HIG sidebar：层级浅、
 * 常驻、选中态明确）。返回钮收进侧栏顶部——详情页自身的返回钮也还开着，两条路等价。
 */
@Composable
private fun DataHubSidebar(
    tab: Int,
    onSelect: (Int) -> Unit,
    onBack: () -> Unit
) {
    val haptics = LocalHaptics.current
    Column(
        Modifier
            // 2026-11-20 v65：底色由 SplitBoards 板壳给（surfaceContainerLow），
            // 这里再画一层 surfaceContainer = 板内第二种材质
            .width(280.dp)
            .fillMaxHeight()
            .statusBarsPadding()
            .padding(vertical = 8.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp)
        ) {
            IconButton(onClick = {
                haptics.perform(HapticEngine.Kind.Tap); onBack()
            }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "返回") }
            Text(
                "资料",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f)
            )
        }
        Spacer(Modifier.height(8.dp))
        val items = listOf(
            Triple(AppIcons.Setting.MemoryBank, "记忆库", 0),
            Triple(AppIcons.Setting.FileLibrary, "文件库", 1),
            Triple(AppIcons.Act.Archive, "归档项目", 2)
        )
        items.forEach { (icon, label, idx) ->
            val selected = idx == tab
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 3.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(
                        if (selected) MaterialTheme.colorScheme.secondaryContainer
                        else androidx.compose.ui.graphics.Color.Transparent
                    )
                    .clickable {
                        haptics.perform(HapticEngine.Kind.Select)
                        onSelect(idx)
                    }
                    .padding(horizontal = 12.dp, vertical = 12.dp)
            ) {
                Icon(
                    icon, null, Modifier.size(20.dp),
                    tint = if (selected) MaterialTheme.colorScheme.onSecondaryContainer
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    label,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (selected) MaterialTheme.colorScheme.onSecondaryContainer
                    else MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}

/**
 * 归档项目整屏（v53.3 从抽屉的 AlertDialog 升格）。
 *
 * 为什么必须升格：AlertDialog 的文本区最多 360dp 高，归档项目一多就得在弹窗里滚动，
 * 而且装在 Hub 里必须是一个能嵌进 tab 的页面（弹窗不能嵌 tab）。
 *
 * 两条出路保持原样：**恢复**（回到抽屉项目树，数据一字不动）/ **删除**（强制删除，
 * 与项目 ⋮ 菜单同一个 `deleteForce`，连对话/笔记/文件一起清）。
 * 删除必须二次确认——它不可恢复，而原来的弹窗里点「删除」是**立刻执行**的。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ArchivedProjectsScreen(onDone: () -> Unit, topSwitcher: (@Composable () -> Unit)?) {
    val haptics = LocalHaptics.current
    val scope = rememberCoroutineScope()
    val version by Graph.dataVersion.flow.collectAsState()
    var list by remember { mutableStateOf<List<Project>>(emptyList()) }
    var pendingDelete by remember { mutableStateOf<Project?>(null) }
    // 2026-11-16：正在强制删除的项目名。删除期间该行显示「删除中…」并禁用按钮——
    // 快照 + 递归删目录要几秒，此前弹窗一关就毫无反馈，用户以为没生效。
    var deletingName by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(version) {
        list = runCatching { Graph.projects.archivedAll() }.getOrDefault(emptyList())
    }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = { Text("归档项目", fontWeight = FontWeight.SemiBold) },
                    navigationIcon = {
                        IconButton(onClick = {
                            haptics.perform(HapticEngine.Kind.Tap); onDone()
                        }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "返回") }
                    }
                )
                if (topSwitcher != null) {
                    Column(Modifier.fillMaxWidth()) { topSwitcher() }
                }
            }
        }
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState())) {
            if (list.isEmpty()) {
                Text(
                    "还没有归档的项目。\n在项目 ⋮ 菜单里选「归档」，项目就会挪到这里。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                )
            } else {
                Text(
                    "归档不会删任何数据：恢复后项目连同对话、笔记、文件一起回到抽屉。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                )
                list.forEach { p ->
                    val isDeleting = deletingName == p.name
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 3.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainer)
                            .padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 6.dp)
                    ) {
                        if (isDeleting) {
                            androidx.compose.material3.CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp
                            )
                        } else {
                            Icon(
                                AppIcons.Act.Archive, null, Modifier.size(18.dp),
                                // R11：行首图标中性
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Text(
                            if (isDeleting) "${p.name} · 删除中…" else p.name,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (isDeleting) MaterialTheme.colorScheme.onSurfaceVariant
                            else MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = {
                            haptics.perform(HapticEngine.Kind.Select)
                            scope.launch {
                                Graph.projects.setArchived(p.id, false)
                                list = Graph.projects.archivedAll()
                            }
                        }, enabled = !isDeleting) { Text("恢复") }
                        TextButton(onClick = { pendingDelete = p }, enabled = !isDeleting) {
                            Text("删除", color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
            }
        }
    }

    pendingDelete?.let { p ->
        ConfirmDialog(
            title = "删除项目「${p.name}」",
            text = "项目、对话、消息、笔记、记忆、项目文件夹一并删除，不可恢复。" +
                "\n\n日程与待办会保留，自动转为全局。\n\n删除前会自动存一份整库快照。",
            onConfirm = {
                haptics.perform(HapticEngine.Kind.ImpactHeavy)
                // 2026-11-16 用户「彻底删除项目有延迟」：原来点完立刻 `pendingDelete = null`
                // （ConfirmDialog 自身 onConfirm 后也会马上 onDismiss），而快照 + 递归删目录
                // 还在后台跑几秒——列表里那行没消失、也没有任何提示。改成**先关列表交互、
                // 在列表处显示「正在删除…」**，删完再刷新列表。
                deletingName = p.name
                pendingDelete = null
                scope.launch {
                    // 与抽屉项目 ⋮ 的「强制删除」同一个保险：先落快照再动数据
                    runCatching { io.github.m0use001.planability.data.AutoBackup.snapshot(force = true) }
                    runCatching { Graph.projects.deleteForce(p.id) }
                    list = runCatching { Graph.projects.archivedAll() }.getOrDefault(emptyList())
                    deletingName = null
                }
            },
            onDismiss = { pendingDelete = null }
        )
    }
}
