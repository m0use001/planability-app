@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package io.github.m0use001.planability.ui.info

import android.graphics.Bitmap
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.github.m0use001.planability.ai.office.OfficeText
import io.github.m0use001.planability.ai.office.PcBridge
import io.github.m0use001.planability.ai.office.SlideRenderer
import io.github.m0use001.planability.core.HapticEngine
import io.github.m0use001.planability.core.LocalHaptics
import io.github.m0use001.planability.ui.common.AppIcons
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * v28：**AI 生成的文件预览**与**电脑端高保真渲染**开关。
 *
 * ## 为什么从「导出文件」页搬到这里
 *
 * 用户 2026-10-12 要求删掉「导出文件」功能（入口 + 整页），文件改在**文件库**里看。
 * 但这两块东西不能跟着一起删：
 * - 预览（PDF 翻页 / 自绘快照 / 抽文字）是这些文件**唯一的查看方式**，删了文件就等于打不开；
 * - 「电脑端渲染（高保真）」开关是 pptx/docx 版式更好看的关键，而且用户是在看文件的时候
 *   最想顺手打开它——藏进设置里等于让人自己知道有这个东西。
 *
 * 所以它们跟着文件库走。这两个 composable 是**纯 UI**，不知道文件库长什么样；
 * 谁要预览谁调 [OfficeFilePreview]。
 */

/**
 * 底部悬浮胶囊导航要占掉的高度。
 *
 * `navigationBarsPadding` 只避让系统手势条，避不了本 App 自己浮在内容之上的胶囊栏——
 * 所以正文末尾还要另留这一块。取 96dp 与本线其他页面（文件库列表末尾）保持一致；
 * 胶囊栏的实际高度若改动，这里要跟着改。
 */
private const val PILL_NAV_CLEAR_DP = 96

/**
 * PDF 翻页（系统 `PdfRenderer`）。
 *
 * 为什么用它而不是自己画：PDF 的渲染规则**本来就在系统里**，自己实现一遍等于
 * 把字体、编码、矢量路径全重做，出来还和电脑上不一致。PdfRenderer 是 Android 原生 API，
 * 至少"这是系统认可的渲染"。
 *
 * 页面索引 auto-fit 到宽，高度按比例缩放——不然窄屏上横版 PPT 会被压成一条。
 */
@Composable
private fun OfficePdfBox(file: File, modifier: Modifier = Modifier) {
    // 翻页 = Prev/Next 选择 → Select（与 CalendarView 翻页同一档）
    val haptics = LocalHaptics.current
    var pageIndex by remember(file) { mutableIntStateOf(0) }
    var pageCount by remember(file) { mutableIntStateOf(0) }
    val bmp = remember(file, pageIndex) {
        runCatching {
            val pfd = android.os.ParcelFileDescriptor.open(
                file, android.os.ParcelFileDescriptor.MODE_READ_ONLY
            )
            val renderer = android.graphics.pdf.PdfRenderer(pfd)
            pageCount = renderer.pageCount
            val page = renderer.openPage(pageIndex)
            // 渲染宽 1080 起步；太高了浪费，太低了糊
            val scale = 1080f / page.width
            val out = Bitmap.createBitmap(
                (page.width * scale).toInt(), (page.height * scale).toInt(),
                Bitmap.Config.ARGB_8888
            )
            out.eraseColor(android.graphics.Color.WHITE)
            page.render(out, null, null, android.graphics.pdf.PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            page.close()
            renderer.close()
            pfd.close()
            out
        }.getOrNull()
    }

    Column(modifier.fillMaxWidth()) {
        Box(
            Modifier.fillMaxWidth().weight(1f).padding(horizontal = 8.dp),
            contentAlignment = Alignment.Center
        ) {
            if (bmp != null) {
                androidx.compose.foundation.Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = "第 ${pageIndex + 1} 页",
                    modifier = Modifier.fillMaxWidth(),
                    contentScale = ContentScale.Fit
                )
            } else {
                Text("这一页读不出来", color = MaterialTheme.colorScheme.error)
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = { haptics.perform(HapticEngine.Kind.Select); pageIndex = (pageIndex - 1).coerceAtLeast(0) },
                enabled = pageIndex > 0
                // R11：翻页 = 导航图标 → 中性；禁用态用同色透明度
            ) { Icon(AppIcons.Nav.Prev, contentDescription = "上一页", tint = if (pageIndex > 0) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)) }
            Text(
                "${pageIndex + 1} / $pageCount",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
            IconButton(
                onClick = { haptics.perform(HapticEngine.Kind.Select); pageIndex = (pageIndex + 1).coerceAtMost((pageCount - 1).coerceAtLeast(0)) },
                enabled = pageIndex < pageCount - 1
            ) { Icon(AppIcons.Nav.Next, contentDescription = "下一页", tint = if (pageIndex < pageCount - 1) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)) }
        }
    }
}

/**
 * 打开一个 AI 生成的文件：有电脑端转的 PDF 就翻 PDF，否则翻自绘快照，再不行显示抽出来的文字。
 *
 * ## 幻灯片看快照，其余看文本
 *
 * `.pptx` 有我们渲染好的快照 png，翻页看效果；docx/xlsx/csv/md 这类只能抽文本——
 * 手机上做不了版式渲染，硬做一个"近似排版"反而会误导。
 */
@Composable
fun OfficeFilePreview(f: File, onBack: () -> Unit) {
    val haptics = LocalHaptics.current
    val shotsDir = remember(f) { File(f.parentFile, SlideRenderer.snapshotDirName(f.name)) }
    val pages = remember(f) {
        shotsDir.listFiles()
            ?.filter { it.name.endsWith(".png") }
            ?.sortedBy { it.name }
            .orEmpty()
    }
    // 真·预览：电脑端用 LibreOffice 转出来的 PDF。它比自绘快照可信——
    // 快照反映的是手机端自己对排版的理解，而 PDF 就是文件本身的样子。
    val pdf = remember(f) {
        listOf("${f.nameWithoutExtension}.pdf", "preview.pdf").firstOrNull { n ->
            File(f.parentFile, n).exists()
        }
    }
    var index by remember(f) { mutableIntStateOf(0) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(f.name, maxLines = 1) },
                navigationIcon = {
                    // 顶栏返回 = 中性点击 → Tap
                    IconButton(onClick = { haptics.perform(HapticEngine.Kind.Tap); onBack() }) {
                        Icon(AppIcons.Nav.Back, contentDescription = "返回", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            )
        }
    ) { pad ->
        // 底部胶囊导航浮在内容之上，得自己避开，否则翻页按钮点不到
        Column(Modifier.padding(pad).navigationBarsPadding().fillMaxSize()) {
            if (pdf != null) {
                OfficePdfBox(File(f.parentFile, pdf), Modifier.weight(1f))
                Row(
                    Modifier.fillMaxWidth().padding(bottom = 6.dp),
                    horizontalArrangement = Arrangement.Center
                ) {
                    Text(
                        "预览为电脑端转出的 PDF，与文件实际版式一致",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                // 胶囊导航是浮在内容之上的，留净空——否则横幅和上面的翻页控件全被它盖住。
                // 自绘快照分支早有这一条（见下），PDF 分支当初漏了：真机上表现为
                // 「预览为电脑端转出的 PDF」这句话用户根本看不见（2026-10-11 实测取证）。
                Spacer(Modifier.height(PILL_NAV_CLEAR_DP.dp))
            } else if (pages.isNotEmpty()) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    val bmp by produceState<android.graphics.Bitmap?>(null, pages, index) {
                        value = runCatching {
                            android.graphics.BitmapFactory.decodeFile(pages[index].absolutePath)
                        }.getOrNull()
                    }
                    if (bmp != null) {
                        androidx.compose.foundation.Image(
                            bitmap = bmp!!.asImageBitmap(),
                            contentDescription = "第 ${index + 1} 页",
                            modifier = Modifier.fillMaxWidth(),
                            contentScale = ContentScale.Fit
                        )
                    } else {
                        Text("这一页读不出来", color = MaterialTheme.colorScheme.error)
                    }
                }
                Row(
                    Modifier.fillMaxWidth().padding(bottom = 12.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = { haptics.perform(HapticEngine.Kind.Select); index = (index - 1).coerceAtLeast(0) },
                        enabled = index > 0
                    ) { Icon(AppIcons.Nav.Prev, contentDescription = "上一页", tint = if (index > 0) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)) }
                    Text(
                        "${index + 1} / ${pages.size}",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )
                    IconButton(
                        onClick = { haptics.perform(HapticEngine.Kind.Select); index = (index + 1).coerceAtMost(pages.size - 1) },
                        enabled = index < pages.size - 1
                    ) { Icon(AppIcons.Nav.Next, contentDescription = "下一页", tint = if (index < pages.size - 1) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)) }
                }
                Text(
                    "预览由本机按排版数据渲染，与电脑上的 PowerPoint 可能有细微差别。" +
                        "电脑端开着时，生成/改稿会自动附带 PDF 预览。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
                Spacer(Modifier.height(PILL_NAV_CLEAR_DP.dp))
            } else if (f.extension.equals("docx", true)) {
                // 2026-11-09：docx 走 mammoth.js 本机排版预览（标题/加粗/表格/图片都在），
                // 纯文本兜底退到下一分支。有电脑端 PDF 时上面那个分支优先。
                DocxHtmlPreview(f, Modifier.weight(1f).fillMaxWidth())
                Text(
                    "本机排版预览（与 Word 版式接近，非精确还原）；电脑端开着时生成/改稿会附带 PDF 预览，那才是逐像素一致的版本。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
                Spacer(Modifier.height(PILL_NAV_CLEAR_DP.dp))
            } else {
                val text by produceState("读取中…", f) {
                    value = runCatching { OfficeText.read(f) }.getOrElse { "读不了：${it.message}" }
                }
                Text(
                    text,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = if (f.extension.equals("csv", true)) FontFamily.Monospace else null,
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp)
                )
            }
        }
    }
}

/** 文件行左侧的图标：幻灯片 / 图片 / 普通文档，一眼能分出能不能翻页 */
@Composable
fun officeFileIcon(f: File, modifier: Modifier = Modifier) {
    val shots = remember(f) { File(f.parentFile, SlideRenderer.snapshotDirName(f.name)) }
    val pageCount = remember(f) { shots.listFiles()?.count { it.name.endsWith(".png") } ?: 0 }
    val isDeck = pageCount > 0 || f.extension.equals("pptx", true)
    Icon(
        if (isDeck) AppIcons.Kind.Slides
        else if (f.extension.lowercase() in setOf("png", "jpg", "jpeg", "webp", "gif")) AppIcons.Kind.Picture
        else AppIcons.Kind.Note,
        contentDescription = null,
        // R11：文件类型标记 → 中性
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier
    )
}

/**
 * **电脑端高保真渲染**的开关与地址。
 *
 * 为什么放在文件库而不是设置页：它是**生成/查看办公文件这条链路的一部分**，
 * 用户看文件时顺手就能打开/关掉，不用先去设置里翻。
 *
 * 状态实时探测（而不是只显示"已开启"）：电脑没开服务是常态，
 * 一个只说"已开启"却不告诉你能不能用的开关会让人反复白跑。
 */
@Composable
fun PcBridgeSettingCard(modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    val haptics = LocalHaptics.current
    var on by remember { mutableStateOf(false) }
    var url by remember { mutableStateOf(PcBridge.DEFAULT_URL) }
    var status by remember { mutableStateOf<String?>(null) }
    var probing by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        on = PcBridge.enabled()
        url = PcBridge.baseUrl()
        status = if (!on) "未开启" else "检测中…"
    }

    fun probe() {
        if (!on) {
            status = "未开启"
            return
        }
        probing = true
        scope.launch {
            val h = withContext(Dispatchers.IO) { PcBridge.health() }
            status = when {
                !h.ok -> "连不上（${h.detail}）"
                h.caps.isEmpty() -> "已连接，但对方没声明支持的格式"
                else -> "已连接 · 支持 ${h.caps.joinToString("/")}"
            }
            probing = false
        }
    }
    LaunchedEffect(on) { probe() }

    Card(
        modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
        )
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    AppIcons.Kind.Computer,
                    contentDescription = null,
                    // R11：桥接卡行首图标 → 中性（连接状态仍由文字/开关表达）
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(22.dp)
                )
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("电脑端渲染（高保真）", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        status ?: "",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (status?.startsWith("已连接") == true)
                            MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = on,
                    onCheckedChange = { v ->
                        // 开关 = 选择 → Select
                        haptics.perform(HapticEngine.Kind.Select)
                        on = v
                        scope.launch {
                            PcBridge.setEnabled(v)
                            probe()
                        }
                    }
                )
            }
            if (on) {
                Spacer(Modifier.height(6.dp))
                if (editing) {
                    OutlinedTextField(
                        value = url,
                        onValueChange = { url = it },
                        label = { Text("电脑端地址") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(onClick = { editing = false }) { Text("取消") }
                        TextButton(onClick = {
                            editing = false
                            scope.launch {
                                PcBridge.setBaseUrl(url)
                                url = PcBridge.baseUrl()
                                probe()
                            }
                        }) { Text("保存") }
                    }
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            url,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = { editing = true }) { Text("改地址") }
                        TextButton(onClick = { probe() }, enabled = !probing) { Text("重试") }
                    }
                }
                Text(
                    "电脑端跑 desktop 的 npm run office。同机可用 adb reverse tcp:4097 tcp:4097；" +
                        "跨设备填电脑的局域网 IP。开着时生成 PPT 走电脑端（版式更好），" +
                        "连不上会自动退回手机端简版并在结果里说明。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/** 文件大小文案（字节 → B/KB/MB），文件库与预览共用一套口径 */
fun officeSizeLabel(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    else -> String.format("%.1f MB", bytes / 1024.0 / 1024.0)
}
