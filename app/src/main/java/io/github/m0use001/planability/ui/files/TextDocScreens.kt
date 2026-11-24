package io.github.m0use001.planability.ui.files

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import io.github.m0use001.planability.app.LocalOverlayClose
import io.github.m0use001.planability.app.PredictiveBackOverlay
import io.github.m0use001.planability.ui.common.hScrollHint
import io.github.m0use001.planability.ui.notes.MarkdownView
import java.io.File

/**
 * 纯文本格式的**渲染预览**（用户 2026-10-20：「纯文本格式的预览呢？例如 html」）。
 *
 * 之前所有文本类文件都直接进 `TextEditorScreen`——对代码是对的，对 .html/.md/.csv 就很别扭：
 * 用户生成一个网页，点开看到的是一堆标签；生成一份表格，看到的是一串逗号。
 * 所以按"文件本身想表达什么"分流：
 *
 * - `.md/.markdown/.mdx` → 渲染后的 Markdown（复用笔记页同一套 MarkdownView）
 * - `.htm/.html/.svg`     → WebView 真渲染（网页就是网页）
 * - `.csv/.tsv`           → 表格（表头吸顶、列宽固定、可横向滚动）
 * - 其余文本                → 仍然进编辑器（代码/配置要看原文，也要能改）
 *
 * 三个预览页都保留「源码 / 编辑」入口：预览是为了看效果，不代表不让改。
 */

/** 预览页公共头：返回 + 文件名 + 一句副标题 + 右侧动作 */
@Composable
private fun PreviewHeader(
    title: String,
    subtitle: String,
    actions: @Composable () -> Unit = {}
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp)
    ) {
        IconButton(onClick = LocalOverlayClose.current) {
            Icon(Icons.AutoMirrored.Rounded.ArrowBack, "返回")
        }
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
        }
        actions()
    }
}

/** 用系统应用打开（网页/表格交给浏览器、表格软件） */
private fun openExternally(context: android.content.Context, file: File) {
    runCatching {
        val uri = androidx.core.content.FileProvider.getUriForFile(
            context, context.packageName + ".fileprovider", file
        )
        context.startActivity(
            android.content.Intent(android.content.Intent.ACTION_VIEW)
                .setDataAndType(uri, mimeOf(file.name))
                .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        )
    }
}

/** Markdown：渲染预览 + 「编辑」 */
@Composable
fun MarkdownDocScreen(file: File, onClose: () -> Unit, embedded: Boolean = false) {
    var editing by remember { mutableStateOf(false) }
    var version by remember { mutableIntStateOf(0) }
    if (editing) {
        // 编辑完回来重新读一遍：预览必须反映刚保存的内容
        TextEditorScreen(file, onClose = { editing = false; version++ }, embedded = embedded)
        return
    }
    val text = remember(file, version) {
        runCatching { file.readText() }.getOrDefault("（读取失败）")
    }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    // v57 平板双栏：本体抽成 viewerBody——手机自包 PredictiveBackOverlay（原路径零变化），
    // 平板由 FilesScreen 宽屏分支的右栏直接承载（embedded=true）。
    val viewerBody: @Composable () -> Unit = {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            PreviewHeader(file.name, "Markdown 预览") {
                TextButton(onClick = { editing = true }) { Text("编辑") }
                IconButton(onClick = { openExternally(ctx, file) }) {
                    Icon(Icons.Rounded.OpenInNew, "系统打开")
                }
            }
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                MarkdownView(text)
            }
        }
    }
    if (embedded) {
        androidx.compose.runtime.CompositionLocalProvider(LocalOverlayClose provides onClose) { viewerBody() }
    } else {
        PredictiveBackOverlay(onBack = onClose) { viewerBody() }
    }
}

/**
 * HTML / SVG：WebView 真渲染 + 「源码」。
 * v57：embedded 参数**刻意忽略**——WebView 的绘制层按窗口坐标合成（见下方注释），
 * 强塞进双栏右栏会糊版；HTML/SVG 永远走全屏 Dialog，只把「源码」编辑透传 embedded。
 */
@Composable
fun HtmlDocScreen(file: File, onClose: () -> Unit, embedded: Boolean = false) {
    var showSource by remember { mutableStateOf(false) }
    if (showSource) {
        TextEditorScreen(file, onClose = { showSource = false }, embedded = embedded)
        return
    }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val isSvg = file.extension.equals("svg", true)
    // 为什么 WebView 铺满整窗、标题栏用 Popup：
    // 真机（ColorOS + Compose Dialog）实测，WebView 的绘制层按**窗口坐标**合成——
    // 视图 bounds 已经是 [0,309]（标题栏下方），画面却从 y=0 开始，正好糊住标题栏。
    // 先后试过 weight(1f)、Box 包裹、去掉 PredictiveBackOverlay 的 graphicsLayer，都没用。
    // 既然"偏移量"本身不可靠，就让它待在原点（铺满），标题栏改用 Popup——
    // Popup 是独立窗口，天然画在 WebView 之上，这是唯一稳定的层级关系。
    androidx.compose.ui.window.Dialog(
        onDismissRequest = onClose,
        properties = androidx.compose.ui.window.DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        androidx.compose.runtime.CompositionLocalProvider(LocalOverlayClose provides onClose) {
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { c ->
                        android.webkit.WebView(c).apply {
                            setBackgroundColor(android.graphics.Color.WHITE)
                            settings.javaScriptEnabled = true   // 生成的页面可能带交互
                            settings.domStorageEnabled = true
                            settings.useWideViewPort = true
                            settings.loadWithOverviewMode = true
                            settings.setSupportZoom(true)
                            settings.builtInZoomControls = true
                            settings.displayZoomControls = false
                            // 只读本地这一份文件：允许 file 访问是为了同目录图片/样式能加载
                            settings.allowFileAccess = true
                            settings.allowContentAccess = false
                            webViewClient = object : android.webkit.WebViewClient() {
                                override fun onPageFinished(view: android.webkit.WebView, url: String) {
                                    // 给悬浮的标题栏让出高度，否则页面大标题被压在下面
                                    view.evaluateJavascript(
                                        "document.body&&(document.body.style.paddingTop='76px');", null
                                    )
                                }

                                // 站外跳转不让本 WebView 接管：点链接交给系统浏览器
                                override fun shouldOverrideUrlLoading(
                                    view: android.webkit.WebView,
                                    request: android.webkit.WebResourceRequest
                                ): Boolean {
                                    val u = request.url.toString()
                                    if (u.startsWith("file://")) return false
                                    runCatching {
                                        view.context.startActivity(
                                            android.content.Intent(android.content.Intent.ACTION_VIEW, request.url)
                                        )
                                    }
                                    return true
                                }
                            }
                            loadUrl("file://" + file.absolutePath)
                        }
                    },
                    onRelease = { it.destroy() }
                )
                androidx.compose.ui.window.Popup(
                    alignment = Alignment.TopCenter,
                    properties = androidx.compose.ui.window.PopupProperties(focusable = false)
                ) {
                    Surface(
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.97f),
                        tonalElevation = 3.dp
                    ) {
                        Column(Modifier.fillMaxWidth().statusBarsPadding()) {
                            PreviewHeader(file.name, if (isSvg) "SVG 预览" else "网页预览") {
                                TextButton(onClick = { showSource = true }) { Text("源码") }
                                IconButton(onClick = { openExternally(ctx, file) }) {
                                    Icon(Icons.Rounded.OpenInNew, "系统打开")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 表格内容（CSV/TSV 与 xlsx 预览共用）：表头吸顶、列宽固定、可横滚 */
@Composable
private fun GridTable(rows: List<List<String>>) {
    if (rows.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("文件是空的", style = MaterialTheme.typography.bodyMedium)
        }
        return
    }
    val cols = rows.maxOfOrNull { it.size }?.coerceIn(1, 12) ?: 1
    val cellWidth = 132.dp
    // 2026-11-13 用户「滑动条要可见」：宽表格横滑加指示条（装得下时不画）
    val csvHs = rememberScrollState()
    Row(Modifier.fillMaxSize().hScrollHint(csvHs).horizontalScroll(csvHs)) {
        LazyColumn(Modifier.fillMaxWidth()) {
            item {
                Row(Modifier.background(MaterialTheme.colorScheme.surfaceVariant)) {
                    repeat(cols) { i ->
                        Text(
                            rows.first().getOrElse(i) { "" },
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.width(cellWidth).padding(8.dp),
                            maxLines = 2, overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
            itemsIndexed(rows.drop(1)) { idx, row ->
                Surface(
                    color = if (idx % 2 == 0) MaterialTheme.colorScheme.surface
                    else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                ) {
                    Row {
                        repeat(cols) { i ->
                            Text(
                                row.getOrElse(i) { "" },
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.width(cellWidth).padding(8.dp),
                                maxLines = 3, overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }
    }
}

/** xlsx：手机端直接读包渲染成表格（不需要电脑，也不上传文件） */
@Composable
fun XlsxDocScreen(file: File, onClose: () -> Unit, embedded: Boolean = false) {
    var loaded by remember { mutableStateOf<Pair<String, List<List<String>>>?>(null) }
    androidx.compose.runtime.LaunchedEffect(file) {
        loaded = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching { io.github.m0use001.planability.ai.office.XlsxEditor.readGrid(file) }
                .getOrDefault("表格" to emptyList())
        }
    }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val data = loaded
    // v57 平板双栏：本体抽成 viewerBody（手机自包 overlay 原路径零变化，平板进右栏）
    val viewerBody: @Composable () -> Unit = {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            PreviewHeader(
                file.name,
                if (data == null) "表格预览 · 读取中…"
                else "表格预览 · ${data.first} · ${data.second.size} 行"
            ) {
                IconButton(onClick = { openExternally(ctx, file) }) {
                    Icon(Icons.Rounded.OpenInNew, "系统打开")
                }
            }
            if (data == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("读取中…", style = MaterialTheme.typography.bodyMedium)
                }
            } else {
                GridTable(data.second)
            }
        }
    }
    if (embedded) {
        androidx.compose.runtime.CompositionLocalProvider(LocalOverlayClose provides onClose) { viewerBody() }
    } else {
        PredictiveBackOverlay(onBack = onClose) { viewerBody() }
    }
}

/** CSV / TSV：表格预览（表头吸顶、可横滚）+ 「源码」 */
@Composable
fun CsvDocScreen(file: File, onClose: () -> Unit, embedded: Boolean = false) {
    var showSource by remember { mutableStateOf(false) }
    if (showSource) {
        TextEditorScreen(file, onClose = { showSource = false }, embedded = embedded)
        return
    }
    val rows = remember(file) {
        runCatching { parseDelimited(file.readText(), if (file.extension.equals("tsv", true)) '\t' else ',') }
            .getOrDefault(emptyList())
    }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val cols = rows.maxOfOrNull { it.size }?.coerceIn(1, 12) ?: 1
    // v57 平板双栏：本体抽成 viewerBody（手机自包 overlay 原路径零变化，平板进右栏）
    val viewerBody: @Composable () -> Unit = {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            PreviewHeader(file.name, "表格预览 · ${rows.size} 行 × $cols 列") {
                TextButton(onClick = { showSource = true }) { Text("源码") }
                IconButton(onClick = { openExternally(ctx, file) }) {
                    Icon(Icons.Rounded.OpenInNew, "系统打开")
                }
            }
            GridTable(rows)
        }
    }
    if (embedded) {
        androidx.compose.runtime.CompositionLocalProvider(LocalOverlayClose provides onClose) { viewerBody() }
    } else {
        PredictiveBackOverlay(onBack = onClose) { viewerBody() }
    }
}

/**
 * 极简 CSV/TSV 解析：支持双引号包裹、`""` 转义、引号内换行。
 * 不做类型推断——预览只需要"能对齐地看"，判断数字/日期是别的层的事。
 */
internal fun parseDelimited(text: String, sep: Char): List<List<String>> {
    val rows = mutableListOf<List<String>>()
    var row = mutableListOf<String>()
    val cell = StringBuilder()
    var inQuotes = false
    var i = 0
    while (i < text.length) {
        val c = text[i]
        when {
            inQuotes && c == '"' && i + 1 < text.length && text[i + 1] == '"' -> { cell.append('"'); i++ }
            c == '"' -> inQuotes = !inQuotes
            !inQuotes && c == sep -> { row.add(cell.toString()); cell.clear() }
            !inQuotes && (c == '\n' || c == '\r') -> {
                if (c == '\r' && i + 1 < text.length && text[i + 1] == '\n') i++
                row.add(cell.toString()); cell.clear()
                rows.add(row); row = mutableListOf()
            }
            else -> cell.append(c)
        }
        i++
    }
    if (cell.isNotEmpty() || row.isNotEmpty()) {
        row.add(cell.toString())
        rows.add(row)
    }
    return rows.filter { it.any { c -> c.isNotBlank() } }
}
