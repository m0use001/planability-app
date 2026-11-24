package io.github.m0use001.planability.ui.notes

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ManageSearch
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.m0use001.planability.ai.embed.ModelStore
import io.github.m0use001.planability.ai.embed.NoteVectors
import io.github.m0use001.planability.ai.rag.KbAsk
import io.github.m0use001.planability.ai.rag.NoteHit
import io.github.m0use001.planability.ai.rag.NoteSearch
import io.github.m0use001.planability.app.OverlayEnter
import io.github.m0use001.planability.app.PredictiveBackOverlay
import io.github.m0use001.planability.core.HapticEngine
import io.github.m0use001.planability.core.LocalHaptics
import io.github.m0use001.planability.data.Graph
import io.github.m0use001.planability.data.MemoryRepo
import io.github.m0use001.planability.data.NotebookEntity
import io.github.m0use001.planability.ui.common.filterChipColors
import kotlinx.coroutines.delay

/**
 * v54.0 笔记**全库搜索**（跨笔记本）；v56 扩为**双模式**：搜笔记 / 问笔记。
 *
 * 原来只有笔记本内的一行过滤：想找"那个提到误差曲线的笔记"得先猜它在哪个本子里。
 * 知识库的核心动作之一就是"不记得放哪也能找到"，所以这里直接复用
 * [NoteSearch] 的底座（标题/正文双路 FTS + RRF + LIKE 兜底），只补 UI 与命中高亮。
 *
 * v56（用户拍板「笔记右上角仅保留一个搜索按钮」）：独立问笔记屏并入本屏作第二模式——
 * 「记得词」用搜笔记（FTS 列表），「问一件事」用问笔记（[KbAsk] 端侧检索：
 * 多轮扩查+分块命中+语义门，证据=[VerdictBanner]+[EvidenceCard]，不生成答案——
 * 那是对话工作台的活）。语义模型管理同期收拢进「模型设置」。
 *
 * 高亮单独抽成纯函数（[SearchHighlight.segments]）：命中片段的分段是唯一
 * 会把中文切坏的环节（按字符扫、词元长短不一），必须能被单测钉住。
 */
object SearchHighlight {

    /**
     * 把 [text] 按 [needles] 切成「普通 / 命中」片段。
     *
     * 规则：
     * - 长词优先（`误差曲线` 命中时不要再被 `误差` 切两刀）；
     * - 大小写不敏感；
     * - 相邻/重叠命中合并成一段，避免出现一串零碎的背景块；
     * - 单字词元不参与（中文单字命中率太高，整段都会亮）。
     */
    fun segments(text: String, needles: List<String>): List<Pair<String, Boolean>> {
        val keys = needles.map { it.trim() }.filter { it.length >= 2 }.distinct().sortedByDescending { it.length }
        if (text.isEmpty() || keys.isEmpty()) return listOf(text to false)
        val out = ArrayList<Pair<String, Boolean>>()
        var i = 0
        while (i < text.length) {
            val hit = keys.firstOrNull { k ->
                i + k.length <= text.length && text.regionMatches(i, k, 0, k.length, ignoreCase = true)
            }
            if (hit != null) {
                // 与上一段命中相接就合并
                val last = out.lastOrNull()
                if (last != null && last.second) out[out.lastIndex] = (last.first + text.substring(i, i + hit.length)) to true
                else out += text.substring(i, i + hit.length) to true
                i += hit.length
            } else {
                val last = out.lastOrNull()
                if (last != null && !last.second) out[out.lastIndex] = (last.first + text[i]) to false
                else out += text[i].toString() to false
                i++
            }
        }
        return out
    }
}

/** 命中片段上色（主色 + 淡底 + 半粗），非命中保持 <style> 原样。 */
@Composable
fun HighlightedText(
    text: String,
    needles: List<String>,
    style: TextStyle = MaterialTheme.typography.bodySmall,
    maxLines: Int = 2
) {
    val marked = MaterialTheme.colorScheme.primary
    val bg = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
    val annotated: AnnotatedString = remember(text, needles) {
        buildAnnotatedString {
            SearchHighlight.segments(text, needles).forEach { (s, hit) ->
                if (hit) {
                    withStyle(SpanStyle(color = marked, background = bg, fontWeight = FontWeight.Medium)) { append(s) }
                } else append(s)
            }
        }
    }
    Text(annotated, style = style, maxLines = maxLines, overflow = TextOverflow.Ellipsis)
}

/**
 * 全库搜索 + 问笔记 双模式整屏。自带 `PredictiveBackOverlay`（本页是独立入口，不由「资料」Hub 之类嵌套打开）。
 */
@Composable
fun GlobalNoteSearchScreen(onDone: () -> Unit, onOpenNote: (Long) -> Unit) {
    val haptics = LocalHaptics.current
    val ctx = LocalContext.current
    var query by remember { mutableStateOf("") }
    // 0 搜笔记（FTS 列表）/ 1 问笔记（KbAsk 证据）
    var mode by remember { mutableStateOf(0) }
    var hits by remember { mutableStateOf<List<NoteHit>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var askResult by remember { mutableStateOf<KbAsk.Result?>(null) }
    var asking by remember { mutableStateOf(false) }
    var bookNames by remember { mutableStateOf<Map<Long, String>>(emptyMap()) }
    // 语义模型在位吗：没下载也能查（FTS/BM25 全程可用），只是少了「意思相近」路与语义门
    var semanticReady by remember { mutableStateOf(true) }
    var showModelDialog by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        bookNames = runCatching { Graph.notes.notebooks().associate { it.id to it.name } }.getOrDefault(emptyMap())
        semanticReady = runCatching { NoteVectors.isAvailable(ctx, ModelStore.selected(ctx)) }.getOrDefault(false)
        runCatching { focus.requestFocus() }
    }
    // 防抖 250ms：每敲一个字就查一次会让 FTS 与主线程抢，中文输入法组词时更明显
    LaunchedEffect(query, mode) {
        val q = query.trim()
        if (mode != 0 || q.isEmpty()) { hits = emptyList(); searching = false; return@LaunchedEffect }
        searching = true
        delay(250)
        hits = runCatching { NoteSearch.search(q, topK = 30) }.getOrDefault(emptyList())
        searching = false
    }
    // 问句比关键词长，防抖放宽到 600ms；检索本身毫秒级（重排几十毫秒），连打不构成负担
    LaunchedEffect(query, mode) {
        val q = query.trim()
        if (mode != 1 || q.isEmpty()) { askResult = null; asking = false; return@LaunchedEffect }
        asking = true
        delay(600)
        askResult = runCatching { KbAsk.ask(q, topK = 8) }.getOrNull()
        asking = false
    }

    val needles = remember(query) {
        (listOf(query.trim()) + MemoryRepo.tokenize(query.trim(), 8)).distinct()
    }

    PredictiveBackOverlay(onBack = onDone, enter = OverlayEnter.Forward) {
        Column(
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surface)
                .statusBarsPadding()
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(start = 4.dp, end = 12.dp, top = 4.dp)
            ) {
                IconButton(onClick = onDone) {
                    Icon(Icons.Rounded.ArrowBack, contentDescription = "返回")
                }
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text(if (mode == 1) "问个问题，查笔记里的内容" else "搜索全部笔记") },
                    leadingIcon = {
                        Icon(if (mode == 1) Icons.Rounded.ManageSearch else Icons.Rounded.Search, null, Modifier.size(18.dp))
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.weight(1f).padding(vertical = 4.dp).focusRequester(focus)
                )
            }
            // v56 双模式切换：记得词 → 搜笔记；问一件事 → 问笔记（端侧检索，只给证据不生成答案）
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth().padding(start = 12.dp, top = 2.dp, bottom = 2.dp)
            ) {
                FilterChip(
                    colors = filterChipColors(),
                    selected = mode == 0,
                    onClick = { haptics.perform(HapticEngine.Kind.Select); mode = 0 },
                    label = { Text("搜笔记") }
                )
                FilterChip(
                    colors = filterChipColors(),
                    selected = mode == 1,
                    onClick = { haptics.perform(HapticEngine.Kind.Select); mode = 1 },
                    label = { Text("问笔记") }
                )
            }
            Text(
                when {
                    mode == 1 -> if (asking) "检索中…" else if (query.isBlank()) "" else "${askResult?.evidence?.size ?: 0} 条证据"
                    searching -> "搜索中…"
                    query.isBlank() -> ""
                    else -> "${hits.size} 条结果"
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 20.dp, top = 2.dp, bottom = 4.dp)
            )
            if (mode == 1) askResult?.let {
                VerdictBanner(it, semanticReady, onRequestModel = { showModelDialog = true })
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

            val r = askResult
            when {
                mode == 1 && query.isBlank() -> SearchEmpty(
                    "查笔记、查内容都行：同时检索标题、正文与分块，\n" +
                        "下载语义模型后「意思相近」的内容也能查到。\n全部在本机完成，不出设备。"
                )
                mode == 1 && (r == null || (r.evidence.isEmpty() && asking)) -> SearchEmpty("检索中…")
                mode == 1 && r != null && (r.evidence.isEmpty() || isNoise(r)) ->
                    SearchEmpty("笔记里没有查到「${query.trim()}」相关的内容")
                mode == 1 && r != null -> LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
                    items(r.evidence, key = { it.noteId }) { ev ->
                        EvidenceCard(
                            ev = ev,
                            needles = needles,
                            bookName = bookNames[ev.noteId].orEmpty(),
                            onClick = { onOpenNote(ev.noteId) }
                        )
                    }
                    item { Spacer(Modifier.height(24.dp)) }
                }
                query.isBlank() -> SearchEmpty("输入关键词，搜全部笔记的标题与正文")
                hits.isEmpty() && !searching -> SearchEmpty("没有找到「${query.trim()}」")
                else -> LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
                    items(hits, key = { it.note.id }) { hit ->
                        SearchHitRow(
                            hit = hit,
                            needles = needles,
                            bookName = bookNames[hit.note.notebookId].orEmpty(),
                            onClick = { onOpenNote(hit.note.id) }
                        )
                    }
                    item { Spacer(Modifier.height(24.dp)) }
                }
            }
        }
    }

    if (showModelDialog) {
        SemanticIndexDialog(onDismiss = { showModelDialog = false })
    }
}

@Composable
private fun SearchHitRow(hit: NoteHit, needles: List<String>, bookName: String, onClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                // v54.0：标题也可能带 `#` / `[[ ]]`（标题取首行原文），与列表行同口径洗一遍
                NotePreview.stripLine(hit.note.title).ifBlank { "无标题" },
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false)
            )
            if (bookName.isNotBlank()) {
                Text(
                    "  · $bookName",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            }
        }
        Spacer(Modifier.height(3.dp))
        // 命中片段优先；没有片段（只命中标题）时退回正文预览，保持每行都有内容。
        // v54.0：片段也要洗一遍——检索给的是原文窗口，直接显示会把 `#`、`[[ ]]` 糊到脸上。
        HighlightedText(
            text = NotePreview.flatten(hit.snippet.ifBlank { hit.note.content }, hit.note.title, 90),
            needles = needles
        )
    }
}

@Composable
private fun SearchEmpty(text: String) {
    Box(Modifier.fillMaxSize().padding(top = 96.dp), contentAlignment = Alignment.TopCenter) {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

