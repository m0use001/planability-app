package io.github.m0use001.planability.ui.notes

import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.NavigateBefore
import androidx.compose.material.icons.automirrored.rounded.NavigateNext
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import io.github.m0use001.planability.app.LocalOverlayClose
import io.github.m0use001.planability.app.OverlayEnter
import io.github.m0use001.planability.app.PredictiveBackOverlay
import io.github.m0use001.planability.core.HapticEngine
import io.github.m0use001.planability.core.HapticTicks
import io.github.m0use001.planability.core.LocalHaptics
import io.github.m0use001.planability.data.Graph
import io.github.m0use001.planability.data.NoteEntity
import io.github.m0use001.planability.ui.common.ConfirmDialog
import io.github.m0use001.planability.ui.theme.SeedColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import io.github.m0use001.planability.ui.common.filterChipColors

data class PdfPageRender(val bitmap: ImageBitmap?, val widthPt: Float, val heightPt: Float)

/**
 * PDF 批注：PdfRenderer 逐页渲染 + 单指墨迹批注（按页存储 pdfMarksJson）。
 * 笔迹坐标使用 PDF 页面点（1/72 英寸）坐标系，跨屏一致。
 */
@Composable
fun PdfNoteScreen(noteId: Long, onDone: () -> Unit) {
    // 非一级界面：隐藏底部导航栏
    io.github.m0use001.planability.core.LockShellChrome()
    val scope = rememberCoroutineScope()
    val haptics = LocalHaptics.current
    var note by remember { mutableStateOf<NoteEntity?>(null) }
    var pdfFile by remember { mutableStateOf<File?>(null) }
    var pageCount by remember { mutableIntStateOf(0) }
    var pageIdx by remember { mutableIntStateOf(0) }
    var pages by remember { mutableStateOf<Map<Int, List<InkStroke>>>(emptyMap()) }
    var colorIdx by remember { mutableIntStateOf(0) }
    var strokeWidth by remember { mutableStateOf(4f) }
    var liveStroke by remember { mutableStateOf<InkStroke?>(null) }
    var showClear by remember { mutableStateOf(false) }
    var dirty by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    // 批注模式：0 笔 / 1 荧光笔 / 2 橡皮；荧光笔存储编码 = colorIdx + 100
    var mode by remember { mutableIntStateOf(0) }
    var exportHint by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current

    LaunchedEffect(noteId) {
        note = Graph.notes.noteById(noteId)
        note?.let { n ->
            pages = parsePdfMarks(n.inkJson)
            if (n.content.isNotBlank()) {
                val f = File(n.content)
                if (f.exists()) {
                    pdfFile = f
                    runCatching {
                        withContext(Dispatchers.IO) {
                            ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                                PdfRenderer(pfd).use { it.pageCount }
                            }
                        }
                    }.onSuccess { pageCount = it }
                        .onFailure { error = "无法打开 PDF：${it.message}" }
                } else {
                    error = "PDF 文件不存在（可能来自备份导入），请删除后重新导入"
                }
            } else {
                error = "PDF 文件缺失，请删除后重新导入"
            }
        }
    }

    // 渲染当前页（IO 线程），同时返回页面点尺寸
    val render by produceState<PdfPageRender?>(null, pdfFile, pageIdx) {
        value = null
        val f = pdfFile ?: return@produceState
        value = withContext(Dispatchers.IO) {
            runCatching {
                ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                    PdfRenderer(pfd).use { renderer ->
                        if (pageIdx >= renderer.pageCount) return@use null
                        renderer.openPage(pageIdx).use { page ->
                            val scale = 2
                            val bmp = Bitmap.createBitmap(
                                page.width * scale, page.height * scale,
                                Bitmap.Config.ARGB_8888
                            )
                            bmp.eraseColor(AndroidColor.WHITE)
                            page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            PdfPageRender(bmp.asImageBitmap(), page.width.toFloat(), page.height.toFloat())
                        }
                    }
                }
            }.getOrNull()
        }
    }

    fun save() {
        val n = note ?: return
        scope.launch {
            Graph.notes.saveNote(n.copy(inkJson = pdfMarksToJson(pages), title = n.title.ifBlank { "PDF 批注" }))
        }
        dirty = false
    }

    /** 导出带批注的 PDF：每页渲染位图 + 墨迹重绘，写入私有 exports 目录并拉起分享 */
    fun exportAnnotatedPdf() {
        val n = note ?: return
        val src = pdfFile ?: return
        scope.launch(Dispatchers.IO) {
            runCatching {
                val dir = java.io.File(context.filesDir, "exports").apply { mkdirs() }
                val out = java.io.File(dir, "${n.title.ifBlank { "PDF批注" }.replace('/', '_')}_${System.currentTimeMillis()}.pdf")
                val doc = android.graphics.pdf.PdfDocument()
                ParcelFileDescriptor.open(src, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                    PdfRenderer(pfd).use { renderer ->
                        for (i in 0 until renderer.pageCount) {
                            renderer.openPage(i).use { page ->
                                val scale = 2
                                val bmp = Bitmap.createBitmap(
                                    page.width * scale, page.height * scale, Bitmap.Config.ARGB_8888
                                )
                                bmp.eraseColor(AndroidColor.WHITE)
                                page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                val pdfPage = doc.startPage(
                                    android.graphics.pdf.PdfDocument.PageInfo
                                        .Builder(page.width * scale, page.height * scale, i + 1).create()
                                )
                                val c = pdfPage.canvas
                                c.drawBitmap(bmp, 0f, 0f, null)
                                val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                                    style = android.graphics.Paint.Style.STROKE
                                    strokeCap = android.graphics.Paint.Cap.ROUND
                                    strokeJoin = android.graphics.Paint.Join.ROUND
                                }
                                (pages[i] ?: emptyList()).forEach { s ->
                                    val hl = s.colorIdx >= 100
                                    val base = SeedColors.inkPalette[(if (hl) s.colorIdx - 100 else s.colorIdx) % SeedColors.inkPalette.size]
                                    paint.color = base.copy(alpha = if (hl) 0.45f else 1f).toArgb()
                                    paint.strokeWidth = s.width * (if (hl) 3f else 1f) * scale
                                    val pts = s.points
                                    if (pts.size >= 4) {
                                        val path = android.graphics.Path()
                                        path.moveTo(pts[0] * scale, pts[1] * scale)
                                        var j = 2
                                        while (j + 3 < pts.size) {
                                            path.quadTo(
                                                pts[j] * scale, pts[j + 1] * scale,
                                                (pts[j] + pts[j + 2]) / 2 * scale, (pts[j + 1] + pts[j + 3]) / 2 * scale
                                            )
                                            j += 2
                                        }
                                        path.lineTo(pts[pts.size - 2] * scale, pts[pts.size - 1] * scale)
                                        c.drawPath(path, paint)
                                    }
                                }
                                doc.finishPage(pdfPage)
                                bmp.recycle()
                            }
                        }
                    }
                }
                java.io.FileOutputStream(out).use { doc.writeTo(it) }
                doc.close()
                withContext(Dispatchers.Main) {
                    exportHint = "已导出：${out.name}"
                    runCatching {
                        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", out)
                        context.startActivity(
                            android.content.Intent(android.content.Intent.ACTION_SEND)
                                .setType("application/pdf")
                                .putExtra(android.content.Intent.EXTRA_STREAM, uri)
                                .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        )
                    }
                }
            }.onFailure {
                withContext(Dispatchers.Main) { exportHint = "导出失败：${it.message}" }
            }
        }
    }

    // Forward：列表→PDF 批注也是层级导航，与 md/无边记编辑器同一进场方向
    // fullscreen=true：PDF 画布批注要整屏
    PredictiveBackOverlay(
        onBack = { if (dirty) save(); onDone() },
        enter = OverlayEnter.Forward,
        fullscreen = true
    ) {
        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
            // 顶栏
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 4.dp)
            ) {
                // overlayClose 必须在组合期读一次存下来：放进 onClick 里读是 @Composable getter，编译不过
                val overlayClose = LocalOverlayClose.current
                IconButton(onClick = { haptics.perform(HapticEngine.Kind.Tap); overlayClose() }) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, "返回")
                }
                Text(
                    "PDF 批注 · ${pageIdx + 1}/$pageCount",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                IconButton(
                    onClick = { if (pageIdx > 0) pageIdx-- },
                    enabled = pageIdx > 0
                ) { Icon(Icons.AutoMirrored.Rounded.NavigateBefore, "上一页") }
                IconButton(
                    onClick = { if (pageIdx < pageCount - 1) pageIdx++ },
                    enabled = pageIdx < pageCount - 1
                ) { Icon(Icons.AutoMirrored.Rounded.NavigateNext, "下一页") }
            }

            if (error != null) {
                Box(
                    Modifier.weight(1f).fillMaxWidth().padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(error!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                }
            } else {
                // 页面 + 墨迹
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    val r = render
                    if (r == null || r.bitmap == null) {
                        Text(
                            "渲染中…", style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        val bmp = r.bitmap!!
                        val strokes = pages[pageIdx] ?: emptyList()
                        Canvas(
                            Modifier
                                .fillMaxWidth()
                                .aspectRatio(r.widthPt / r.heightPt)
                                .clip(RoundedCornerShape(6.dp))
                                .pointerInput(pageIdx, r.widthPt, mode) {
                                    awaitEachGesture {
                                        val first = awaitFirstDown(requireUnconsumed = false)
                                        var current: FloatArray? = null
                                        val w = size.width.toFloat()
                                        val h = size.height.toFloat()
                                        while (true) {
                                            val event = awaitPointerEvent()
                                            val pressed = event.changes.filter { it.pressed }
                                            if (pressed.size == 1) {
                                                val pt = pressed[0].position
                                                val px = pt.x / w * r.widthPt
                                                val py = pt.y / h * r.heightPt
                                                if (mode == 2) {
                                                    // 橡皮：拖动命中删除本页笔画
                                                    pages = pages.toMutableMap().also { cur ->
                                                        cur[pageIdx] = (cur[pageIdx] ?: emptyList())
                                                            .filterNot { hitStroke(it, px, py, 12f) }
                                                    }
                                                    dirty = true
                                                } else {
                                                    current = if (current == null) {
                                                        floatArrayOf(px, py)
                                                    } else {
                                                        (current ?: FloatArray(0)) + floatArrayOf(px, py)
                                                    }
                                                    val effColor = if (mode == 1) colorIdx + 100 else colorIdx
                                                    liveStroke = InkStroke(effColor, strokeWidth, current ?: FloatArray(0))
                                                }
                                                pressed[0].consume()
                                            }
                                            if (pressed.isEmpty()) break
                                        }
                                        val c = current
                                        if (mode != 2 && c != null && c.size >= 4) {
                                            val effColor = if (mode == 1) colorIdx + 100 else colorIdx
                                            val cur = pages.toMutableMap()
                                            cur[pageIdx] = (cur[pageIdx] ?: emptyList()) +
                                                InkStroke(effColor, strokeWidth, c.copyOf())
                                            pages = cur
                                            dirty = true
                                            haptics.perform(HapticEngine.Kind.Tap)
                                        }
                                        liveStroke = null
                                    }
                                }
                        ) {
                            drawImage(
                                bmp,
                                srcOffset = IntOffset.Zero,
                                srcSize = IntSize(bmp.width, bmp.height),
                                dstOffset = IntOffset.Zero,
                                dstSize = IntSize(size.width.toInt(), size.height.toInt())
                            )
                            drawInkPt(strokes + listOfNotNull(liveStroke), r.widthPt, r.heightPt)
                        }
                    }
                }
            }

            // 工具条
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)
            ) {
                listOf(0 to "笔", 1 to "荧光", 2 to "橡皮").forEach { (m, label) ->
                    FilterChip(
                        colors = filterChipColors(),
                        selected = mode == m,
                        onClick = { mode = m; haptics.perform(HapticEngine.Kind.Select) },
                        label = { Text(label) }
                    )
                }
                TextButton(onClick = { exportAnnotatedPdf() }) { Text("导出") }
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
            ) {
                SeedColors.inkPalette.forEachIndexed { idx, c ->
                    Box(
                        Modifier
                            .size(if (colorIdx == idx) 24.dp else 20.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(c)
                            .clickable { colorIdx = idx; haptics.perform(HapticEngine.Kind.Select) }
                    )
                }
                Spacer(Modifier.width(4.dp))
                Text("粗细", style = MaterialTheme.typography.labelSmall)
                // 笔粗滑块：按"跨过一档"振一下（Select），不是每次 onValueChange 都振（见 core/Haptics.kt 的 HapticTicks）
                val strokeTicks = remember(haptics) { HapticTicks(haptics, 1f) }
                Slider(
                    value = strokeWidth,
                    onValueChange = { strokeWidth = it; strokeTicks.onValue(it) },
                    valueRange = 2f..14f, modifier = Modifier.weight(1f)
                )
                IconButton(onClick = {
                    val cur = pages.toMutableMap()
                    val list = cur[pageIdx]?.toMutableList()
                    if (!list.isNullOrEmpty()) {
                        list.removeAt(list.size - 1)
                        cur[pageIdx] = list
                        pages = cur
                        dirty = true
                        // 撤销一笔 = 中性轻触（Tap），与无边记的撤销同档
                        haptics.perform(HapticEngine.Kind.Tap)
                    }
                }) { Icon(Icons.Rounded.Refresh, "撤销") }
                IconButton(onClick = { showClear = true }) {
                    Icon(Icons.Rounded.Delete, "清空本页")
                }
            }
            exportHint?.let {
                Text(
                    it, style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
            }
            Spacer(Modifier.height(12.dp))
        }
    }

    if (showClear) {
        ConfirmDialog(
            title = "清空本页",
            text = "将删除第 ${pageIdx + 1} 页的全部批注。",
            onConfirm = {
                val cur = pages.toMutableMap()
                cur[pageIdx] = emptyList()
                pages = cur
                dirty = true
            },
            onDismiss = { showClear = false }
        )
    }
}

/** 以 PDF 点坐标绘制墨迹（view 已按 aspectRatio 映射，只需等比缩放）；colorIdx≥100 = 荧光笔 */
private fun DrawScope.drawInkPt(strokes: List<InkStroke>, pageW: Float, pageH: Float) {
    val sx = size.width / pageW
    val sy = size.height / pageH
    strokes.forEach { s ->
        if (s.points.size >= 4) {
            val hl = s.colorIdx >= 100
            val path = Path()
            path.moveTo(s.points[0] * sx, s.points[1] * sy)
            var i = 2
            while (i + 3 < s.points.size) {
                val mx = (s.points[i] + s.points[i + 2]) / 2 * sx
                val my = (s.points[i + 1] + s.points[i + 3]) / 2 * sy
                path.quadraticBezierTo(s.points[i] * sx, s.points[i + 1] * sy, mx, my)
                i += 2
            }
            path.lineTo(s.points[s.points.size - 2] * sx, s.points[s.points.size - 1] * sy)
            drawPath(
                path,
                color = SeedColors.inkPalette[(if (hl) s.colorIdx - 100 else s.colorIdx) % SeedColors.inkPalette.size]
                    .copy(alpha = if (hl) 0.45f else 1f),
                style = Stroke(
                    width = s.width * (if (hl) 3f else 1f) * ((sx + sy) / 2),
                    cap = StrokeCap.Round, join = StrokeJoin.Round
                )
            )
        }
    }
}

/** 橡皮命中：任一折点距目标点小于 r 即视为命中 */
private fun hitStroke(s: InkStroke, px: Float, py: Float, r: Float): Boolean {
    val rr = r * r
    var i = 0
    while (i + 1 < s.points.size) {
        val dx = s.points[i] - px
        val dy = s.points[i + 1] - py
        if (dx * dx + dy * dy <= rr) return true
        i += 2
    }
    return false
}
