package io.github.m0use001.planability.ui.info

import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
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
import io.github.m0use001.planability.ai.ChatEngine
import io.github.m0use001.planability.ai.ChatRound
import io.github.m0use001.planability.ai.chatRounds
import io.github.m0use001.planability.ai.effectiveTranscript
import kotlinx.coroutines.flow.first
import io.github.m0use001.planability.core.HapticEngine
import io.github.m0use001.planability.core.LocalHaptics
import io.github.m0use001.planability.data.CompactMarkerEntity
import kotlinx.coroutines.launch

/**
 * v47：**详细上下文编辑**（用户 2026-11-03 拍板）。
 *
 * 从「上下文」面板进入：把会话按轮分组（1 轮 = 1 条 user + AI 对它的全部回答），
 * 用户选中**连续的几轮**做选择性压缩——压缩后这几轮在会话界面只显示摘要
 * （自读书签同步变为摘要）；点摘要卡「查看原文」跳独立界面看原文（v50，
 * 卡内展开/长按切换已废除）。
 *
 * 交互（2026-11-04 用户改版拍板）：
 * - 顶栏/底栏 = 会话界面同款圆角栏（底栏上圆角 24dp、surfaceContainerHigh，顶栏镜像下圆角）；
 * - 标题下不解释「一轮」是什么，改放**当前总上下文胶囊**（共 N 轮 · M tok · 已压缩 K tok）；
 * - 列表占满标题栏与底栏之间的全部剩余空间；每轮上下更宽松、预览展示 3 行原文；
 * - 点轮 = 选（已选中的再点 = 取消）；选区强制**连续**：与选区相邻则扩选、在选区边缘
 *   则缩选、不相邻则从该轮重新开选（绝不允许出现断开的选区）；
 * - 底部左列：压缩用模型胶囊（上）+ 压缩胶囊按钮（下）；右侧圆角矩形框实时显示
 *   选中情况（N 轮 · M tok），选区全落在同一条区间标记内时框内出现「取消压缩」；
 * - 已压缩的轮被**更大的圆角矩形框**框在一起（组头显示摘要 + 「恢复」按钮），
 *   框内各轮仍可点选（用于取消压缩）。
 */
@Composable
internal fun ContextEditorScreen(
    sessionId: Long,
    onDismiss: () -> Unit,
    /** 压缩/取消成功后回调（会话界面刷新摘要卡） */
    onCompacted: () -> Unit
) {
    val haptics = LocalHaptics.current
    val scope = rememberCoroutineScope()
    var rounds by remember { mutableStateOf<List<ChatRound>>(emptyList()) }
    /** 每轮 token 数（与 rounds 同下标；2026-11-04 用户：每行显示本轮 token） */
    var roundTokens by remember { mutableStateOf<List<Int>>(emptyList()) }
    var markers by remember { mutableStateOf<List<CompactMarkerEntity>>(emptyList()) }
    var selFirst by remember { mutableStateOf(-1L) }   // 选区第一轮 firstId
    var selLast by remember { mutableStateOf(-1L) }    // 选区最后一轮 firstId
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf("") }         // 操作结果提示
    /** 压缩用模型（null=跟随压缩槽→worker 兜底链；2026-11-04 用户要可选） */
    var compactModel by remember { mutableStateOf<String?>(null) }
    /** 2026-11-15：自动书签下拉（跳到某一轮）的展开态 */
    var bookmarkOpen by remember { mutableStateOf(false) }

    // 会话内消息按 effectiveTranscript 口径取（与上下文组装一致：游标后、有内容的 user/assistant/tool）
    suspend fun refresh() {
        val s = io.github.m0use001.planability.data.Graph.chat.session(sessionId) ?: return
        val msgs = effectiveTranscript(
            io.github.m0use001.planability.data.Graph.chat.messagesOf(sessionId), s.clearBefore
        )
        rounds = chatRounds(msgs)
        roundTokens = rounds.map { r ->
            r.indexRange.sumOf { io.github.m0use001.planability.ai.ContextEngine.estTokens(msgs[it].content) }
        }
        markers = runCatching { io.github.m0use001.planability.data.Graph.compactMarkers.rangesOf(sessionId) }
            .getOrDefault(emptyList())
    }
    LaunchedEffect(sessionId) { refresh() }

    // 某轮被哪个区间标记覆盖（null=未压缩）
    fun markerCovering(r: ChatRound): CompactMarkerEntity? =
        markers.firstOrNull { r.firstId >= it.firstCompactedMessageId && r.lastId <= it.lastCompactedMessageId }

    // 选区（按 rounds 的下标）
    val selIdx: IntRange = run {
        val a = rounds.indexOfFirst { it.firstId == selFirst }
        val b = rounds.indexOfFirst { it.firstId == selLast }
        if (a < 0 || b < 0) IntRange.EMPTY else if (a <= b) a..b else b..a
    }
    val selRounds = if (selIdx.isEmpty()) emptyList() else selIdx.map { rounds[it] }
    val selAllCompacted = selRounds.isNotEmpty() && selRounds.all { markerCovering(it) != null } &&
        selRounds.map { markerCovering(it) }.distinct().size == 1

    // ── 统计（2026-11-04 用户：顶部统计条 + 选中实时 token）──
    val selTok = selIdx.sumOf { roundTokens.getOrElse(it) { 0 } }
    val compactedTok = rounds.indices.filter { markerCovering(rounds[it]) != null }
        .sumOf { roundTokens.getOrElse(it) { 0 } }
    // 2026-11-15 用户：标题栏要「现在上下文多少」+「该会话总计压缩多少」。
    // **当前总上下文**取 ChatEngine 的权威值（= 下一轮真正会发出去的载荷总量，含系统提示/
    // 记忆/工具定义），与「上下文」面板和顶栏百分比同口径——三个地方不再各说各话。
    val uiState by io.github.m0use001.planability.ai.ChatEngine.stateOf(sessionId).collectAsState()

    // 2026-11-15 用户：「上下文详细编辑界面加上自动书签与批量选中」。
    // 列表从 verticalScroll 换成 **LazyColumn**：书签要能精确跳到某一轮（scrollToItem），
    // 顺带 80+ 轮的组合开销也降下来。
    // 条目 = 单轮，或被同一个压缩标记覆盖的一段连续轮（组头 + 组内各轮）。
    val entries: List<Pair<CompactMarkerEntity?, List<Int>>> = run {
        val out = mutableListOf<Pair<CompactMarkerEntity?, List<Int>>>()
        var i = 0
        while (i < rounds.size) {
            val cov = markerCovering(rounds[i])
            if (cov == null) {
                out += (null to listOf(i)); i++
            } else {
                val g = mutableListOf(i)
                var j = i + 1
                while (j < rounds.size && markerCovering(rounds[j])?.id == cov.id) { g += j; j++ }
                out += (cov to g); i = j
            }
        }
        out
    }
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    // 进屏默认贴底（最新轮次）；只首次贴，之后压缩/恢复不抢位置。
    var autoScrolled by remember(sessionId) { mutableStateOf(false) }
    LaunchedEffect(rounds.size) {
        if (rounds.isEmpty() || autoScrolled) return@LaunchedEffect
        // item 0 = 作者注卡，其后才是各条目 → 最后一个条目的下标 = entries.size
        runCatching { listState.scrollToItem(entries.size) }
        autoScrolled = true
    }

    /** 批量选中：选中**最近的一段**未被压缩的连续轮（压缩动作要求选区连续，
     *  跨过已压缩段会把它们再压一遍）。全都没压缩时=全选。 */
    fun selectAllUncompacted() {
        val free = rounds.indices.filter { markerCovering(rounds[it]) == null }
        if (free.isEmpty()) { selFirst = -1; selLast = -1; return }
        var start = free.last()
        while (start - 1 in free) start--
        selFirst = rounds[start].firstId
        selLast = rounds[free.last()].firstId
        haptics.perform(HapticEngine.Kind.Select)
    }

    // 单击一轮：维护**连续**选区
    //  已选中的单轮再点 = 取消；
    //  未选时点 = 从该轮起选；
    //  已选时点相邻的轮 = 扩选（向下/向上都自然覆盖）；
    //  已选时点选区边缘那一轮 = 缩选（把边缘还回去）；
    //  已选时点隔开的轮 = 从该轮重新开选（不产生断开的选区）。
    fun toggleRound(idx: Int) {
        haptics.perform(HapticEngine.Kind.Select)
        val cur = rounds[idx].firstId
        if (selIdx.isEmpty()) {
            selFirst = cur; selLast = cur; return
        }
        if (selIdx.first == selIdx.last && selIdx.first == idx) {
            selFirst = -1; selLast = -1; return
        }
        when {
            idx == selIdx.first -> {
                // 边缘缩选：把左边缘还回去（剩单轮时成为新的单选）
                val next = selIdx.first + 1
                selFirst = rounds[next].firstId; selLast = rounds[selIdx.last].firstId
            }
            idx == selIdx.last -> {
                val prev = selIdx.last - 1
                selFirst = rounds[selIdx.first].firstId; selLast = rounds[prev].firstId
            }
            idx in selIdx -> { selFirst = cur; selLast = cur }
            else -> {
                // 与选区相邻则把选区拉过去，否则从该轮重新开选
                if (idx == selIdx.first - 1) selFirst = cur
                else if (idx == selIdx.last + 1) selLast = cur
                else { selFirst = cur; selLast = cur }
            }
        }
    }

    /** 列表里的一轮：选中圈 + 原文预览（3 行）+ 右侧「第N轮 / N tok」 */
    @Composable
    fun RoundRow(r: ChatRound, idx: Int, covered: CompactMarkerEntity?, selected: Boolean) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(
                    when {
                        selected -> MaterialTheme.colorScheme.primaryContainer
                        covered != null -> MaterialTheme.colorScheme.surfaceContainerHigh
                        else -> MaterialTheme.colorScheme.surfaceContainer
                    }
                )
                .clickable { toggleRound(idx) }
                .padding(horizontal = 12.dp, vertical = 11.dp)
        ) {
            Box(
                Modifier
                    .size(16.dp)
                    .border(
                        1.5.dp,
                        if (selected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.outlineVariant,
                        RoundedCornerShape(50)
                    )
                    .background(
                        if (selected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.surface,
                        RoundedCornerShape(50)
                    )
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    r.preview.ifBlank { "（无文字）" },
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 3, overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.width(10.dp))
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    "第 ${idx + 1} 轮",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
                Text(
                    "${roundTokens.getOrElse(idx) { 0 }} tok",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    modifier = Modifier.padding(top = 1.dp)
                )
            }
        }
    }

    // 返回=直接调 onDismiss。**不能**走 LocalOverlayClose：本屏由 ChatScreen 直接挂载
    // （不在任何父 overlay 里），组合期捕获到的是空实现——返回键与手势都会变成空操作
    // （2026-11-04 用户「详细编辑界面返回不了」的根因）。本屏又不是分层覆盖层，
    // 没有需要向上传递的父级关闭，onDismiss 就是唯一正确的关闭动作。
    io.github.m0use001.planability.app.PredictiveBackOverlay(onBack = {
        haptics.perform(HapticEngine.Kind.Tap)
        onDismiss()
    }) {
        Column(
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surface)
        ) {
            // ---- 顶栏：会话界面同款圆角栏（输入栏是「整宽贴底、上面两角圆」，
            //      顶栏镜像过来 = 整宽贴顶、下面两角圆）。标题下不再解释「一轮」，
            //      改放当前总上下文胶囊。 ----
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(bottomStart = 24.dp, bottomEnd = 24.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .statusBarsPadding()
                    .padding(start = 4.dp, end = 12.dp, top = 4.dp, bottom = 10.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = {
                        haptics.perform(HapticEngine.Kind.Tap)
                        onDismiss()
                    }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "返回") }
                    Text(
                        "详细上下文编辑", style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.weight(1f))
                    // 2026-11-15 用户「加上自动书签」：与会话界面同款——每条用户提问一个书签，
                    // 点条目直接跳到那一轮（列表已 LazyColumn，scrollToItem 精确落位）。
                    Box {
                        IconButton(onClick = { haptics.perform(HapticEngine.Kind.Tap); bookmarkOpen = true }) {
                            Icon(
                                Icons.Rounded.Bookmark, "自动书签",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        androidx.compose.material3.DropdownMenu(
                            expanded = bookmarkOpen,
                            onDismissRequest = { bookmarkOpen = false },
                            modifier = Modifier.heightIn(max = 420.dp)
                        ) {
                            if (rounds.isEmpty()) {
                                androidx.compose.material3.DropdownMenuItem(
                                    modifier = Modifier.clip(RoundedCornerShape(10.dp)),
                                    text = { Text("还没有可跳转的轮次", style = MaterialTheme.typography.bodySmall) },
                                    onClick = { bookmarkOpen = false }
                                )
                            }
                            entries.forEachIndexed { ei, e ->
                                val gi = e.second.first()
                                val cov = e.first
                                androidx.compose.material3.DropdownMenuItem(
                                    modifier = Modifier.clip(RoundedCornerShape(10.dp)),
                                    text = {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(
                                                "第 ${gi + 1} 轮",
                                                style = MaterialTheme.typography.labelSmall,
                                                fontWeight = FontWeight.SemiBold,
                                                color = if (cov != null) MaterialTheme.colorScheme.tertiary
                                                else MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                            if (cov != null) {
                                                Text(
                                                    " · 已压缩",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.tertiary
                                                )
                                            }
                                            Spacer(Modifier.width(6.dp))
                                            Text(
                                                rounds[gi].preview.ifBlank { "（空）" },
                                                style = MaterialTheme.typography.labelSmall,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                // 预览限宽：不限的话菜单会被长句撑到接近全屏宽
                                                modifier = Modifier.widthIn(max = 150.dp)
                                            )
                                        }
                                    },
                                    onClick = {
                                        bookmarkOpen = false
                                        // item 0 = 作者注卡 → 条目下标整体 +1
                                        scope.launch { runCatching { listState.scrollToItem(ei + 1) } }
                                    }
                                )
                            }
                        }
                    }
                }
                // 2026-11-15 用户：标题栏显示「现在上下文多少」+「该会话总计压缩多少 token」；
                // 文案靠左贴边、**同一行**（不要分行），也不要有"省 xxx"这种没说清的字眼。
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(top = 2.dp)
                ) {
                    StatChip("当前总上下文 ${uiState.ctxTokens} tok")
                    if (compactedTok > 0) {
                        StatChip(
                            "累计压缩 $compactedTok tok",
                            tint = MaterialTheme.colorScheme.tertiary
                        )
                    }
                }
            }

            // ---- 轮列表：占满顶栏与底栏之间的全部剩余空间（weight(1f)）。
            //      LazyColumn：书签精确跳转 + 长会话组合开销都靠它。
            //      已压缩的连续轮被同一个大圆角框框在一起。 ----
            androidx.compose.foundation.lazy.LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = 14.dp, end = 14.dp, top = 10.dp, bottom = 10.dp
                ),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // ---- v55 作者注卡（会话级，注入在倒数第 N 条消息后） ----
                item(key = "author-note") { AuthorNoteCard(sessionId) }
                if (rounds.isEmpty()) {
                    item(key = "empty") {
                        Text(
                            "还没有可压缩的对话", style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 24.dp)
                        )
                    }
                }
                itemsIndexed(
                    items = entries,
                    key = { ei, e -> "e" + (e.second.firstOrNull() ?: ei) }
                ) { _, entry ->
                    val cov = entry.first
                    val group = entry.second
                    if (cov == null) {
                        val gi = group.first()
                        RoundRow(rounds[gi], gi, null, selected = gi in selIdx)
                    } else {
                        val groupTok = group.sumOf { roundTokens.getOrElse(it) { 0 } }
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(16.dp))
                                .background(MaterialTheme.colorScheme.surfaceContainerLow)
                                .border(
                                    1.dp,
                                    MaterialTheme.colorScheme.tertiary.copy(alpha = 0.45f),
                                    RoundedCornerShape(16.dp)
                                )
                                .padding(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Column(Modifier.weight(1f)) {
                                    // 2026-11-15 用户：被压缩轮次组头要显示「从多少 tok 压到多少 tok + 压缩率」
                                    val sumTok = io.github.m0use001.planability.ai.ContextEngine
                                        .estTokens(cov.summary)
                                    val pct = if (groupTok > 0) {
                                        ((groupTok - sumTok) * 100 / groupTok).coerceIn(0, 100)
                                    } else 0
                                    Text(
                                        "已压缩 · $groupTok tok → $sumTok tok" +
                                            if (pct > 0) " · 压缩 $pct%" else "",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.tertiary,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Text(
                                        cov.summary,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 2, overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.padding(top = 1.dp)
                                    )
                                }
                                TextButtonCompat("恢复") {
                                    haptics.perform(HapticEngine.Kind.Tap)
                                    busy = true
                                    scope.launch {
                                        ChatEngine.manualUncompact(sessionId, cov.id)
                                        msg = "已恢复原文进上下文"
                                        refresh(); onCompacted(); busy = false
                                    }
                                }
                            }
                            group.forEach { gi ->
                                RoundRow(rounds[gi], gi, cov, selected = gi in selIdx)
                            }
                        }
                    }
                }
            }
            msg.takeIf { it.isNotBlank() }?.let {
                Text(
                    it, style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)
                )
            }

            // ---- 底栏：会话输入栏同款圆角栏（整宽贴底、上面两角圆）。
            //      左列：压缩用模型胶囊（上）+ 压缩胶囊按钮（下）；
            //      右侧：圆角矩形框实时显示选中情况（N 轮 · M tok）。 ----
            val providerState by io.github.m0use001.planability.data.Graph.aiProviders.state.collectAsState()
            val enabledModels = providerState.models.filter { m ->
                m.enabled && providerState.providers.any { it.id == m.providerId && it.enabled }
            }
            var modelMenu by remember { mutableStateOf(false) }
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 10.dp)
                ) {
                    // 左列：模型胶囊 + 压缩按钮（纵向堆叠）
                    Column(horizontalAlignment = Alignment.Start) {
                        // 模型胶囊 + 批量选中（同一行）
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Surface(
                                shape = RoundedCornerShape(50),
                                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                                onClick = { modelMenu = true }
                            ) {
                                Text(
                                    compactModel ?: "跟随压缩槽",
                                    style = MaterialTheme.typography.labelMedium,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier
                                        .widthIn(max = 150.dp)
                                        .padding(horizontal = 12.dp, vertical = 5.dp)
                                )
                            }
                            Spacer(Modifier.width(6.dp))
                            // 2026-11-15 用户「加上批量选中」：一次选中最近那段未压缩的轮
                            // （跨过已压缩段会让它们被重复压缩，所以只取连续段）。
                            Surface(
                                shape = RoundedCornerShape(50),
                                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                                onClick = {
                                    if (selRounds.isEmpty()) selectAllUncompacted()
                                    else { selFirst = -1; selLast = -1 }
                                }
                            ) {
                                Text(
                                    if (selRounds.isEmpty()) "全选未压缩" else "取消选择",
                                    style = MaterialTheme.typography.labelMedium,
                                    maxLines = 1,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                                )
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                        Surface(
                            shape = RoundedCornerShape(50),
                            color = if (selRounds.isNotEmpty() && !busy && !selAllCompacted)
                                MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.surfaceContainerHighest,
                            onClick = if (selRounds.isNotEmpty() && !busy && !selAllCompacted) ({
                                haptics.perform(HapticEngine.Kind.ImpactLight)
                                val first = selRounds.first().firstId
                                val last = selRounds.last().lastId
                                busy = true
                                scope.launch {
                                    try {
                                        val err = ChatEngine.manualCompactRange(sessionId, first, last, compactModel)
                                        selFirst = -1; selLast = -1
                                        msg = err ?: buildString {
                                            append("已压缩：这几轮在会话里只显示摘要")
                                            compactModel?.let { append("（模型 $it）") }
                                        }
                                        refresh(); onCompacted()
                                    } catch (e: Exception) {
                                        // 2026-11-15 用户「压缩一次后就也不能用了」：manualCompactRange
                                        // 在模型成功后的 DB 写入阶段(markCompact/recomputeCtxTokens)抛异常时，
                                        // 原代码 busy=true 永不复位 → 压缩按钮永久禁用。try/finally 兜底。
                                        msg = "压缩出错：${e.message ?: e.javaClass.simpleName}"
                                        selFirst = -1; selLast = -1
                                    } finally {
                                        busy = false
                                    }
                                }
                            }) else ({}),
                            enabled = selRounds.isNotEmpty() && !busy && !selAllCompacted
                        ) {
                            Text(
                                if (selRounds.isEmpty()) "压缩所选" else "压缩 ${selRounds.size} 轮",
                                style = MaterialTheme.typography.labelLarge,
                                color = if (selRounds.isNotEmpty() && !busy && !selAllCompacted)
                                    MaterialTheme.colorScheme.onPrimary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp)
                            )
                        }
                    }
                    Spacer(Modifier.width(10.dp))
                    // 右侧：选中情况框（圆角矩形，实时跟随选区）
                    Column(
                        Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(14.dp))
                            .background(MaterialTheme.colorScheme.surface)
                            .border(
                                1.dp, MaterialTheme.colorScheme.outlineVariant,
                                RoundedCornerShape(14.dp)
                            )
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        Text(
                            if (selRounds.isEmpty()) "未选择"
                            else "已选 ${selRounds.size} 轮",
                            style = MaterialTheme.typography.labelMedium,
                            color = if (selRounds.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant
                            else MaterialTheme.colorScheme.primary
                        )
                        Text(
                            if (selRounds.isEmpty()) "点列表中的轮开始选择"
                            else "≈ $selTok tok",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 1.dp)
                        )
                        if (selAllCompacted) {
                            TextButtonCompat("取消压缩") {
                                haptics.perform(HapticEngine.Kind.Tap)
                                val m = markerCovering(selRounds.first()) ?: return@TextButtonCompat
                                busy = true
                                scope.launch {
                                    ChatEngine.manualUncompact(sessionId, m.id)
                                    selFirst = -1; selLast = -1
                                    msg = "已取消压缩"
                                    refresh(); onCompacted(); busy = false
                                }
                            }
                        }
                    }
                }
                androidx.compose.material3.DropdownMenu(
                    expanded = modelMenu,
                    onDismissRequest = { modelMenu = false },
                    modifier = Modifier.padding(start = 12.dp)
                ) {
                    androidx.compose.material3.DropdownMenuItem(
                        modifier = Modifier.clip(RoundedCornerShape(10.dp)), // 2026-11-15 点击高亮圆角化（原来是整行直角方块）
                        text = { Text("跟随压缩槽（默认）") },
                        onClick = { compactModel = null; modelMenu = false }
                    )
                    enabledModels.forEach { m ->
                        androidx.compose.material3.DropdownMenuItem(
                            modifier = Modifier.clip(RoundedCornerShape(10.dp)), // 2026-11-15 点击高亮圆角化（原来是整行直角方块）
                            text = { Text(m.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            onClick = { compactModel = m.name; modelMenu = false }
                        )
                    }
                }
                // 底部导航条避让：圆角栏颜色一直铺到手势横杠底下
                Box(Modifier.navigationBarsPadding())
            }
        }
    }
}

/** 编辑屏内的小文字按钮（避免到处 import TextButton 的高亮色配置漂移） */
@Composable
private fun TextButtonCompat(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    androidx.compose.material3.TextButton(onClick = onClick, enabled = enabled) {
        Text(label, style = MaterialTheme.typography.labelLarge)
    }
}

/** 统计条里的小胶囊（surfaceContainerHigh 底 + 可选彩色文字） */
@Composable
private fun StatChip(text: String, tint: androidx.compose.ui.graphics.Color? = null) {
    androidx.compose.material3.Surface(
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            color = tint ?: MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp)
        )
    }
}

// ==================== v55 作者注（会话级方向舵，酒馆 Author's Note 同款） ====================

/**
 * 作者注卡：自治组件（自己加载 / 编辑 / 持久化），宿主只管摆放。
 * 写 chat_sessions.authorNote / authorNoteDepth，ContextEngine.injectAtDepth 消费
 * （钉在倒数第 N 条消息后，下一轮生成生效）。上下文编辑器与 ChatScreen ⋮ 菜单两处复用。
 */
@Composable
internal fun AuthorNoteCard(sessionId: Long) {
    val haptics = LocalHaptics.current
    val scope = rememberCoroutineScope()
    var noteText by remember(sessionId) { mutableStateOf("") }
    var noteDepth by remember(sessionId) { mutableStateOf(4) }
    var msg by remember { mutableStateOf("") }
    LaunchedEffect(sessionId) {
        io.github.m0use001.planability.data.Graph.chat.session(sessionId)?.let {
            noteText = it.authorNote
            noteDepth = it.authorNoteDepth
        }
    }
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "作者注", style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "钉在倒数第 $noteDepth 条消息后 · 下一轮生效",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            OutlinedTextField(
                value = noteText,
                onValueChange = { noteText = it },
                placeholder = { Text("本章基调 / 节奏 / 禁区……（空 = 无）") },
                minLines = 2, maxLines = 5,
                modifier = Modifier.fillMaxWidth()
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("深度", style = MaterialTheme.typography.labelSmall)
                TextButton(onClick = { noteDepth = (noteDepth - 1).coerceAtLeast(0) }) { Text("−") }
                Text("$noteDepth", style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = { noteDepth = (noteDepth + 1).coerceAtMost(50) }) { Text("＋") }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = {
                    haptics.perform(HapticEngine.Kind.Tap)
                    noteText = ""
                    scope.launch {
                        runCatching {
                            val s = io.github.m0use001.planability.data.Graph.chat.session(sessionId) ?: return@launch
                            io.github.m0use001.planability.data.Graph.chat.updateSessionRaw(
                                s.copy(authorNote = "", authorNoteDepth = noteDepth)
                            )
                        }
                        haptics.perform(HapticEngine.Kind.Success)
                        msg = "作者注已清除"
                    }
                }) { Text("清除") }
                TextButton(onClick = {
                    haptics.perform(HapticEngine.Kind.Tap)
                    scope.launch {
                        runCatching {
                            val s = io.github.m0use001.planability.data.Graph.chat.session(sessionId) ?: return@launch
                            io.github.m0use001.planability.data.Graph.chat.updateSessionRaw(
                                s.copy(authorNote = noteText.trim(), authorNoteDepth = noteDepth)
                            )
                        }
                        haptics.perform(HapticEngine.Kind.Success)
                        msg = if (noteText.isBlank()) "作者注已清除" else "作者注已更新，下一轮生成生效"
                    }
                }) { Text("应用") }
            }
            if (msg.isNotBlank()) {
                Text(
                    msg, style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

/** 作者注底部面板（ChatScreen ⋮ 菜单入口；上下文编辑器顶部有同款卡）。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AuthorNoteSheet(sessionId: Long, onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    androidx.compose.material3.ModalBottomSheet(dragHandle = { io.github.m0use001.planability.ui.common.SheetDragHandleOnly() }, onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("作者注", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                "会话级方向舵：改章节基调不用动人设卡。上下文编辑器顶部有同一张卡。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            AuthorNoteCard(sessionId)
            Spacer(Modifier.height(16.dp))
            Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
        }
    }
}
