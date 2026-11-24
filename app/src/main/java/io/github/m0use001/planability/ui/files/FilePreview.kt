package io.github.m0use001.planability.ui.files

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color as AndroidColor
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.Xml
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.NavigateBefore
import androidx.compose.material.icons.automirrored.rounded.NavigateNext
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import io.github.m0use001.planability.app.LocalOverlayClose
import io.github.m0use001.planability.app.PredictiveBackOverlay
import io.github.m0use001.planability.data.WORKFLOW_MARKER
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.util.zip.ZipFile

/** PDF 只读查看器：PdfRenderer 逐页渲染，双指/按钮翻页 */
@Composable
fun PdfViewerScreen(file: File, onClose: () -> Unit, embedded: Boolean = false) {
    var pageCount by remember { mutableIntStateOf(0) }
    var pageIdx by remember { mutableIntStateOf(0) }

    val render by produceState<Pair<ImageBitmap, Pair<Float, Float>>?>(null, file, pageIdx) {
        value = null
        value = withContext(Dispatchers.IO) {
            runCatching {
                ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                    PdfRenderer(pfd).use { renderer ->
                        pageCount = renderer.pageCount
                        if (pageIdx >= renderer.pageCount) return@use null
                        renderer.openPage(pageIdx).use { page ->
                            val scale = 2
                            val bmp = Bitmap.createBitmap(
                                page.width * scale, page.height * scale,
                                Bitmap.Config.ARGB_8888
                            )
                            bmp.eraseColor(AndroidColor.WHITE)
                            page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            bmp.asImageBitmap() to (page.width.toFloat() to page.height.toFloat())
                        }
                    }
                }
            }.getOrNull()
        }
    }

    // v57 平板双栏：本体抽成 viewerBody——手机自包 PredictiveBackOverlay（原路径零变化），
    // 平板由 FilesScreen 宽屏分支的右栏直接承载（embedded=true）。
    val viewerBody: @Composable () -> Unit = {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp)
            ) {
                IconButton(onClick = LocalOverlayClose.current) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, "返回")
                }
                Text(
                    "${file.name} · ${pageIdx + 1}/${pageCount.coerceAtLeast(1)}",
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = { if (pageIdx > 0) pageIdx-- }, enabled = pageIdx > 0) {
                    Icon(Icons.AutoMirrored.Rounded.NavigateBefore, "上一页")
                }
                IconButton(
                    onClick = { if (pageIdx < pageCount - 1) pageIdx++ },
                    enabled = pageIdx < pageCount - 1
                ) { Icon(Icons.AutoMirrored.Rounded.NavigateNext, "下一页") }
            }
            val r = render
            if (r == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("渲染中…", style = MaterialTheme.typography.bodyMedium)
                }
            } else {
                // v47：渲染完成淡入（Crossfade），「渲染中…」→ 位图不再硬切闪一下
                androidx.compose.animation.Crossfade(
                    targetState = r,
                    animationSpec = io.github.m0use001.planability.ui.common.Motion.standardEffects(),
                    label = "pageFade"
                ) { page ->
                    Image(
                        bitmap = page.first,
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(page.second.first / page.second.second)
                            .background(MaterialTheme.colorScheme.surfaceContainerLowest)
                    )
                }
            }
        }
    }
    if (embedded) {
        androidx.compose.runtime.CompositionLocalProvider(LocalOverlayClose provides onClose) { viewerBody() }
    } else {
        PredictiveBackOverlay(onBack = onClose) { viewerBody() }
    }
}

/** 图片查看器：BitmapFactory 解码 + 双指缩放/拖动/双击复位 */
@Composable
fun ImageViewerScreen(file: File, onClose: () -> Unit, embedded: Boolean = false) {
    var bitmap by remember { mutableStateOf<ImageBitmap?>(null) }
    var error by remember { mutableStateOf(false) }

    LaunchedEffect(file) {
        val decoded = withContext(Dispatchers.IO) {
            runCatching {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.absolutePath, bounds)
                var sample = 1
                val maxDim = maxOf(bounds.outWidth, bounds.outHeight)
                while (maxDim / (sample * 2) >= 2048) sample *= 2
                BitmapFactory.decodeFile(
                    file.absolutePath,
                    BitmapFactory.Options().apply { inSampleSize = sample }
                )
            }.getOrNull()
        }
        if (decoded != null) bitmap = decoded.asImageBitmap() else error = true
    }

    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    val transformState = rememberTransformableState { zoom, pan, _ ->
        scale = (scale * zoom).coerceIn(1f, 10f)
        offset += pan
    }

    // v57 平板双栏：本体抽成 viewerBody——手机自包 PredictiveBackOverlay（原路径零变化），
    // 平板由 FilesScreen 宽屏分支的右栏直接承载（embedded=true）。
    val viewerBody: @Composable () -> Unit = {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp)
            ) {
                IconButton(onClick = LocalOverlayClose.current) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, "返回")
                }
                Text(
                    file.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
            }
            Box(
                Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                val bmp = bitmap
                when {
                    bmp != null -> Image(
                        bitmap = bmp,
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxSize()
                            .pointerInput(Unit) {
                                detectTapGestures(onDoubleTap = {
                                    scale = 1f; offset = Offset.Zero
                                })
                            }
                            .transformable(transformState)
                            .graphicsLayer {
                                scaleX = scale; scaleY = scale
                                translationX = offset.x; translationY = offset.y
                            }
                    )
                    error -> Text("无法解码此图片", style = MaterialTheme.typography.bodyMedium)
                    else -> Text("加载中…", style = MaterialTheme.typography.bodyMedium)
                }
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
 * 工作流清单屏（首行 WORKFLOW_MARKER 的 md）：可打勾，勾选**直接写回文件本身**。
 *
 * 为什么不走 OfficePreviewScreen：那边的 parseOfficePreview 只认 docx/xlsx/pptx，
 * md 进去就是「不支持的格式」；清单的宿主不该绑死文件格式。
 * 写回策略=就地改文件里第 idx 个可勾选行（- [ ] ⇄ - [x]），不经过 Graph.folders 的
 * rel 路径——对话附件是真实文件的拷贝、文件库文件是真实文件，两种宿主都成立。
 */
@Composable
fun WorkflowChecklistScreen(file: File, onClose: () -> Unit, embedded: Boolean = false) {
    var lines by remember(file) {
        mutableStateOf(runCatching { file.readLines() }.getOrDefault(emptyList()))
    }
    // v57 平板双栏：本体抽成 viewerBody——手机自包 PredictiveBackOverlay（原路径零变化），
    // 平板由 FilesScreen 宽屏分支的右栏直接承载（embedded=true）。
    val viewerBody: @Composable () -> Unit = {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp)
            ) {
                IconButton(onClick = LocalOverlayClose.current) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, "返回")
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        file.name,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        "工作流清单",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            WorkflowChecklistView(
                lines = lines,
                onToggle = { idx, checked ->
                    var seen = -1
                    val newLines = lines.map { raw ->
                        val t = raw.trimStart()
                        val isStep = t.startsWith("- [ ]") || t.startsWith("- [x]") || t.startsWith("- [X]")
                        if (!isStep) return@map raw
                        seen += 1
                        if (seen != idx) return@map raw
                        val prefix = raw.substringBefore("- [")
                        if (checked) prefix + "- [x]" + t.substring(5) else prefix + "- [ ]" + t.substring(5)
                    }
                    if (newLines != lines) {
                        lines = newLines
                        runCatching { file.writeText(newLines.joinToString("\n")) }
                    }
                }
            )
        }
    }
    if (embedded) {
        androidx.compose.runtime.CompositionLocalProvider(LocalOverlayClose provides onClose) { viewerBody() }
    } else {
        PredictiveBackOverlay(onBack = onClose) { viewerBody() }
    }
}

/** Office 预览：zip + XML 解析（docx 段落 / xlsx 网格 / pptx 逐页文本），失败走系统打开 */
@Composable
fun OfficePreviewScreen(file: File, onClose: () -> Unit, embedded: Boolean = false) {
    val context = LocalContext.current
    var lines by remember { mutableStateOf<List<String>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    // 2026-11-05：中文对照阅读（手机版 PDFMathTranslate 的核心价值——逐段原文+译文）
    var bilingual by remember { mutableStateOf(false) }

    LaunchedEffect(file) {
        val result = withContext(Dispatchers.IO) {
            runCatching { parseOfficePreview(file) }
        }
        result.onSuccess { lines = it }
            .onFailure { error = it.message ?: "解析失败" }
    }

    fun openExternal() {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        runCatching {
            context.startActivity(
                android.content.Intent(android.content.Intent.ACTION_VIEW)
                    .setDataAndType(uri, mimeOf(file.name))
                    .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            )
        }
    }

    // v57 平板双栏：本体抽成 viewerBody——手机自包 PredictiveBackOverlay（原路径零变化），
    // 平板由 FilesScreen 宽屏分支的右栏直接承载（embedded=true）。
    val viewerBody: @Composable () -> Unit = {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp)
            ) {
                IconButton(onClick = LocalOverlayClose.current) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, "返回")
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        file.name,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        "${file.extension.uppercase()} 预览",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                TextButton(onClick = { bilingual = !bilingual }) {
                    Text(if (bilingual) "原文" else "中文对照")
                }
                TextButton(onClick = { openExternal() }) {
                    Icon(Icons.Rounded.OpenInNew, null, Modifier.padding(end = 4.dp))
                    Text("系统打开")
                }
            }
            val l = lines
            when {
                error != null -> Box(Modifier.fillMaxSize().padding(24.dp)) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("无法解析预览：$error", style = MaterialTheme.typography.bodyMedium)
                        Text("可用其他应用打开此文件。", style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = { openExternal() }) { Text("用其他应用打开") }
                    }
                }
                l == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("解析中…", style = MaterialTheme.typography.bodyMedium)
                }
                // v19.7：工作进程清单（首行带标记的 md）→ 可打勾视图，勾选直接写回真实文件
                l.firstOrNull()?.trim() == io.github.m0use001.planability.data.WORKFLOW_MARKER -> {
                    WorkflowChecklistView(
                        lines = l,
                        onToggle = { idx, checked ->
                            val rel = "projects/" + file.parentFile?.name + "/" + file.name
                            val ok = io.github.m0use001.planability.data.Graph.folders.setChecklistChecked(rel, idx, checked)
                            if (ok) lines = runCatching { file.readLines() }.getOrDefault(l)
                        }
                    )
                }
                bilingual -> BilingualReader(lines = l, modifier = Modifier.fillMaxSize())
                else -> LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
                    itemsIndexed(l.take(3000)) { _, line ->
                        Text(
                            line,
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontFamily = if (file.extension.equals("xlsx", true)) FontFamily.Monospace
                                else FontFamily.Default,
                                fontSize = 14.sp,
                                lineHeight = 20.sp
                            ),
                            modifier = Modifier.padding(vertical = 2.dp)
                        )
                    }
                    if (l.size > 3000) {
                        item { Text("…仅显示前 3000 行", style = MaterialTheme.typography.labelSmall) }
                    }
                }
            }
        }
    }
    if (embedded) {
        androidx.compose.runtime.CompositionLocalProvider(LocalOverlayClose provides onClose) { viewerBody() }
    } else {
        PredictiveBackOverlay(onBack = onClose) { viewerBody() }
    }
}

// ==================== Office 解析 ====================

/**
 * 工作进程清单视图：把 `- [ ] / - [x]` 行渲染成可点勾选列表，
 * 顶部显示进度（已完成/总数），勾选写回项目文件夹里的真实文件（v19.7）。
 */
@Composable
private fun WorkflowChecklistView(lines: List<String>, onToggle: (Int, Boolean) -> Unit) {
    data class Step(val index: Int, val text: String, val done: Boolean)
    val steps = remember(lines) {
        val out = mutableListOf<Step>()
        lines.forEach { raw ->
            val t = raw.trimStart()
            if (t.startsWith("- [ ]")) out += Step(out.size, t.substring(5).trim(), false)
            else if (t.startsWith("- [x]") || t.startsWith("- [X]")) out += Step(out.size, t.substring(5).trim(), true)
        }
        out
    }
    val title = remember(lines) {
        lines.firstOrNull { it.trimStart().startsWith("# ") }
            ?.trimStart()?.removePrefix("# ")?.trim().orEmpty()
    }
    val head = remember(lines) {
        lines.filter {
            val t = it.trim()
            t.isNotEmpty() && !t.startsWith("#") && !t.startsWith("- [") && !t.startsWith("<!--")
        }.joinToString("\n")
    }
    val doneCount = steps.count { it.done }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        if (title.isNotBlank()) {
            item {
                Text(
                    title, style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp)
                )
            }
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 6.dp)) {
                // M3E：确定性进度换 WavyProgressIndicator（波浪比直条更"活"）。
                // 高度给足 12dp 留出波浪振幅——旧代码的 height(6dp)+clip 会把它压成一条直线。
                //
                // amplitude 必须显式给 { 1f }：库默认的 indicatorAmplitude 在进度 ≤10% / ≥95%
                // 时返回 0（波浪压平成一条 4dp 细线）——工作清单最常见的两个状态恰好是
                // 0%（还没开始）和 100%（全部完成），不覆写的话波浪条在这两头"看不见"，
                // 正是第二轮审查「进度条不可见」的根因。
                androidx.compose.material3.LinearWavyProgressIndicator(
                    progress = { if (steps.isEmpty()) 0f else doneCount.toFloat() / steps.size },
                    amplitude = { 1f },
                    modifier = Modifier.weight(1f).height(12.dp)
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    "$doneCount/${steps.size}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
        if (head.isNotBlank()) {
            item {
                Text(
                    head, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 6.dp)
                )
            }
        }
        itemsIndexed(steps) { _, s ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .clickable { onToggle(s.index, !s.done) }
                    .padding(vertical = 6.dp, horizontal = 2.dp)
            ) {
                androidx.compose.material3.Checkbox(checked = s.done, onCheckedChange = { onToggle(s.index, it) })
                Spacer(Modifier.width(6.dp))
                Text(
                    s.text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (s.done) MaterialTheme.colorScheme.onSurfaceVariant
                    else MaterialTheme.colorScheme.onSurface,
                    textDecoration = if (s.done) TextDecoration.LineThrough else null
                )
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

/** 判定「这是工作进程清单」：md/markdown/mdx 且首行（trim 后）是 WORKFLOW_MARKER。
 *  独立成函数是为了能进 JVM 单测（路由在 Composable 里，测不到）。 */
fun isWorkflowMdFile(file: File): Boolean =
    file.extension.lowercase() in setOf("md", "markdown", "mdx") &&
        runCatching { file.useLines { it.firstOrNull()?.trim() == WORKFLOW_MARKER } }
            .getOrDefault(false)

/** 按扩展名路由到对应的应用内预览/编辑器 */
@Composable
fun FileViewerRoute(file: File, onClose: () -> Unit, embedded: Boolean = false) {
    val ext = file.extension.lowercase()
    // v19.7 的工作流清单（首行 WORKFLOW_MARKER 的 md → 可打勾视图）在 1172daef 把 .md
    // 整体改路由到 Markdown 渲染后被绕过了——带标记的 md 也进 MarkdownDocScreen，
    // 下面的 OfficePreviewScreen 分支永远到不了，可打勾视图与 Wavy 进度条成了死代码。
    // 这里把「首行是标记的 md」接回清单视图（读首行一次并记住，不在重组里反复读盘）。
    val isWorkflowMd = ext in setOf("md", "markdown", "mdx") && remember(file) {
        isWorkflowMdFile(file)
    }
    when {
        isWorkflowMd -> WorkflowChecklistScreen(file, onClose, embedded)
        // 有"渲染形态"的文本先走渲染预览，其余文本才进编辑器：
        // 网页看网页、Markdown 看排版、表格看表格，而不是一律甩源码给用户
        ext in setOf("md", "markdown", "mdx") -> MarkdownDocScreen(file, onClose, embedded)
        ext in setOf("htm", "html", "xhtml", "svg") -> HtmlDocScreen(file, onClose, embedded)
        ext in setOf("csv", "tsv") -> CsvDocScreen(file, onClose, embedded)
        // xlsx 走"真表格"预览（手机端直接读共享字符串表按网格渲染），
        // 比抽成文字行好读；要看数字格式/合并效果仍可「系统打开」
        ext == "xlsx" -> XlsxDocScreen(file, onClose, embedded)
        ext in TEXT_EXTS -> TextEditorScreen(file, onClose, embedded)
        ext == "pdf" -> PdfViewerScreen(file, onClose, embedded)
        ext in IMAGE_EXTS -> ImageViewerScreen(file, onClose, embedded)
        ext in OFFICE_EXTS -> OfficePreviewScreen(file, onClose, embedded)
        else -> {}
    }
}


private fun parseOfficePreview(file: File): List<String> = when (file.extension.lowercase()) {
    "docx" -> parseDocx(file)
    "xlsx" -> parseXlsx(file)
    "pptx" -> parsePptx(file)
    else -> throw IllegalArgumentException("不支持的格式")
}

/** PTL file_read_document：把 docx/xlsx/pptx 解析为纯文本（供工具层调用） */
fun parseDocumentForTool(file: File): String = runCatching {
    parseOfficePreview(file).joinToString("\n")
}.getOrElse { "" }

/** docx：word/document.xml 的 <w:p> 段落与 <w:t> 文本 */
private fun parseDocx(file: File): List<String> {
    ZipFile(file).use { zip ->
        val entry = zip.getEntry("word/document.xml")
            ?: throw IllegalArgumentException("不是有效的 Word 文档")
        val paras = mutableListOf<String>()
        val sb = StringBuilder()
        var inT = false
        zip.getInputStream(entry).use { input ->
            val p = Xml.newPullParser()
            p.setInput(input, "UTF-8")
            var e = p.eventType
            while (e != XmlPullParser.END_DOCUMENT) {
                when (e) {
                    XmlPullParser.START_TAG -> when (p.name) {
                        "p" -> sb.clear()
                        "t" -> inT = true
                    }
                    XmlPullParser.TEXT -> if (inT) sb.append(p.text)
                    XmlPullParser.END_TAG -> when (p.name) {
                        "t" -> inT = false
                        "p" -> paras += sb.toString()
                    }
                }
                e = p.next()
            }
        }
        return paras.filter { it.isNotBlank() }.ifEmpty { listOf("(空文档)") }
    }
}

/** xlsx：xl/sharedStrings.xml 字符串表 + xl/worksheets/sheet1.xml 单元格网格 */
private fun parseXlsx(file: File): List<String> {
    ZipFile(file).use { zip ->
        val shared = mutableListOf<String>()
        zip.getEntry("xl/sharedStrings.xml")?.let { entry ->
            val sb = StringBuilder()
            var inT = false
            zip.getInputStream(entry).use { input ->
                val p = Xml.newPullParser()
                p.setInput(input, "UTF-8")
                var e = p.eventType
                while (e != XmlPullParser.END_DOCUMENT) {
                    when (e) {
                        XmlPullParser.START_TAG -> when (p.name) {
                            "si" -> sb.clear()
                            "t" -> inT = true
                        }
                        XmlPullParser.TEXT -> if (inT) sb.append(p.text)
                        XmlPullParser.END_TAG -> when (p.name) {
                            "t" -> inT = false
                            "si" -> shared += sb.toString()
                        }
                    }
                    e = p.next()
                }
            }
        }
        val sheet = zip.getEntry("xl/worksheets/sheet1.xml")
            ?: throw IllegalArgumentException("未找到工作表")
        val out = mutableListOf<String>()
        var cells = mutableListOf<Pair<Int, String>>()
        var colIdx = 0
        var cellType = ""
        val cellVal = StringBuilder()
        var inV = false
        var inInlineT = false
        zip.getInputStream(sheet).use { input ->
            val p = Xml.newPullParser()
            p.setInput(input, "UTF-8")
            var e = p.eventType
            while (e != XmlPullParser.END_DOCUMENT) {
                when (e) {
                    XmlPullParser.START_TAG -> when (p.name) {
                        "row" -> cells.clear()
                        "c" -> {
                            colIdx = colFromRef(p.getAttributeValue(null, "r"))
                            cellType = p.getAttributeValue(null, "t") ?: ""
                            cellVal.clear()
                        }
                        "v" -> inV = true
                        "t" -> inInlineT = true
                    }
                    XmlPullParser.TEXT -> if (inV || inInlineT) cellVal.append(p.text)
                    XmlPullParser.END_TAG -> when (p.name) {
                        "v" -> inV = false
                        "t" -> inInlineT = false
                        "c" -> {
                            val v = when (cellType) {
                                "s" -> shared.getOrNull(cellVal.toString().trim().toIntOrNull() ?: -1) ?: ""
                                else -> cellVal.toString()
                            }
                            if (v.isNotBlank()) cells += colIdx to v
                        }
                        "row" -> if (cells.isNotEmpty()) {
                            out += cells.sortedBy { it.first }.joinToString(" | ") { it.second }
                        }
                    }
                }
                e = p.next()
            }
        }
        return out.ifEmpty { listOf("(空工作表)") }
    }
}

/** pptx：ppt/slides/slideN.xml 逐页 <a:p> 段落文本 */
private fun parsePptx(file: File): List<String> {
    ZipFile(file).use { zip ->
        val slideNum = Regex("ppt/slides/slide(\\d+)\\.xml")
        val slides = zip.entries().asSequence()
            .mapNotNull { e -> slideNum.matchEntire(e.name)?.groupValues?.get(1)?.toIntOrNull()?.let { it to e } }
            .sortedBy { it.first }
            .toList()
        if (slides.isEmpty()) throw IllegalArgumentException("不是有效的演示文稿")
        val out = mutableListOf<String>()
        slides.forEach { (num, entry) ->
            out += "—— 第 $num 页 ——"
            val sb = StringBuilder()
            var inT = false
            zip.getInputStream(entry).use { input ->
                val p = Xml.newPullParser()
                p.setInput(input, "UTF-8")
                var e = p.eventType
                while (e != XmlPullParser.END_DOCUMENT) {
                    when (e) {
                        XmlPullParser.START_TAG -> when (p.name) {
                            "p" -> sb.clear()
                            "t" -> inT = true
                        }
                        XmlPullParser.TEXT -> if (inT) sb.append(p.text)
                        XmlPullParser.END_TAG -> when (p.name) {
                            "t" -> inT = false
                            "p" -> if (sb.isNotBlank()) out += sb.toString()
                        }
                    }
                    e = p.next()
                }
            }
        }
        return out
    }
}

/** "B3" → 列号（A=0） */private fun colFromRef(ref: String?): Int {
    if (ref.isNullOrBlank()) return 0
    var n = 0
    for (c in ref) {
        if (c in 'A'..'Z') n = n * 26 + (c - 'A' + 1)
        else if (c in 'a'..'z') n = n * 26 + (c - 'a' + 1)
        else break
    }
    return n - 1
}
