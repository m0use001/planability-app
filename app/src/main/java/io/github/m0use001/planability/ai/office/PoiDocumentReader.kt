package io.github.m0use001.planability.ai.office

import android.content.Context
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.File
import java.io.FileInputStream
import org.apache.poi.hwpf.HWPFDocument
import org.apache.poi.hwpf.extractor.WordExtractor
import org.apache.poi.ss.usermodel.CellType
import org.apache.poi.ss.usermodel.DataFormatter
import org.apache.poi.ss.usermodel.WorkbookFactory
import org.apache.poi.xwpf.extractor.XWPFWordExtractor
import org.apache.poi.xwpf.usermodel.XWPFDocument

/**
 * 成熟库文档解析（2026-11-09 第一刀）。
 *
 * ## 为什么存在
 *
 * `OfficeText`（ui/notes/NotesScreen.kt 里那套正则）是自研轮子：
 * - docx 按 `<w:t>` 逐段正则，曾在 `tags()` 里逐 run `.trim()` → "Many studentsput theirphones"；
 * - 老 .doc（OLE2 二进制）完全读不了，只能报"是 .doc 二进制文档"；
 * - PDF 只做翻页渲染，没有文字层，扫描件只能让用户截图。
 *
 * 这里换成两个被大量真实文件磨过的库（与具体产品无关的通用库）：
 * - Apache POI：docx（XWPF，按 run 拼、不额外 trim）、doc（HWPF）、xlsx（WorkbookFactory）
 * - PDFBox-Android：PDF 文字层；文字层几乎没有时由调用方走 OcrHelper（这里只做渲染）
 *
 * ## 失败语义
 *
 * 全部返回 `null`（不是空串）表示"这条路读不了"，由调用方决定：
 * 降级到别的解析器，或把明确原因交给模型/用户。**绝不返回空串假装成功**。
 */
object PoiDocumentReader {

    /** docx / doc → 纯文本 */
    fun readWord(f: File): String? = runCatching {
        when (f.extension.lowercase()) {
            "docx" -> FileInputStream(f).use { fis ->
                XWPFDocument(fis).use { docx ->
                    XWPFWordExtractor(docx).use { it.text }
                }
            }
            "doc" -> FileInputStream(f).use { fis ->
                HWPFDocument(fis).use { doc -> WordExtractor(doc).use { it.text } }
            }
            else -> null
        }
    }.getOrNull()?.takeIf { it.isNotBlank() }?.let { normalize(it) }

    /** xlsx 工作表清单：名称 → 有内容的行数（模型先看结构，再决定读哪张/多少行） */
    fun listSheets(f: File): List<Pair<String, Int>>? = runCatching {
        FileInputStream(f).use { fis ->
            WorkbookFactory.create(fis).use { wb ->
                (0 until wb.numberOfSheets).map { i ->
                    val s = wb.getSheetAt(i)
                    s.sheetName to s.physicalNumberOfRows
                }
            }
        }
    }.getOrNull()

    /**
     * xlsx → 逐行制表符分隔的纯文本（单元格按 DataFormatter 格式化，日期/百分比不丢）。
     *
     * 2026-11-09：每张表前打 `[工作表 N：名称（M 行）]`、每行前带**1 基行号**。
     * 原来只给一段没有坐标的文本：模型引用单元格（B3）只能靠数、问题定位到"第几行"也没法说清。
     */
    fun readSheet(f: File): String? = runCatching {
        FileInputStream(f).use { fis ->
            WorkbookFactory.create(fis).use { wb ->
                val fmt = DataFormatter()
                buildString {
                    for (s in 0 until wb.numberOfSheets) {
                        val sheet = wb.getSheetAt(s)
                        append("[工作表 ").append(s + 1).append("：").append(sheet.sheetName)
                            .append("（").append(sheet.physicalNumberOfRows).append(" 行）]\n")
                        for (r in sheet) {
                            val cells = r.map { c ->
                                if (c.cellType == CellType.BLANK) "" else fmt.formatCellValue(c)
                            }
                            if (cells.any { it.isNotBlank() }) {
                                append(r.rowNum + 1).append('\t')
                                append(cells.joinToString("\t")).append('\n')
                            }
                        }
                    }
                }
            }
        }
    }.getOrNull()?.takeIf { it.isNotBlank() }

    /**
     * 写后回读闸（2026-11-09）：文件非空 ≠ 内容对。
     *
     * 生成 docx/xlsx 之后用**同一个解析器**读回来，读不出字就判失败——防的是
     * "导出成功、用户拿到空壳文件"这类假成功（写器 bug、R8 裁类、电脑桥返回空壳都踩过）。
     *
     * @param expectedChars 原内容的可见字符数；回读量低于 1/4 视为内容丢失。
     * @return null = 回读正常；否则返回给人看的原因。
     */
    fun verifyWritten(f: File, expectedChars: Int): String? {
        val back = when (f.extension.lowercase()) {
            "docx" -> readWord(f)
            "xlsx" -> readSheet(f)
            else -> return null   // 其它格式没有可靠解析器，不做这道闸
        }
        if (back.isNullOrBlank()) return "回读不到任何文字（文件是空壳）"
        if (expectedChars >= 200) {
            val got = back.count { it.isLetterOrDigit() }
            if (got < expectedChars / 4) return "回读只有 $got 字，不足原文 $expectedChars 字的 1/4（内容丢失）"
        }
        return null
    }

    /**
     * PDF 文字层。返回 null = 没有可用文字层（扫描件），调用方应走 OCR。
     * [minChars] 是"算作有文字层"的下限；低于它按扫描件处理。
     *
     * 2026-11-09：按页输出 `[第N页]` 锚点。原来整份 PDF 抽成一坨，模型没法回答
     * "第 3 页那句怎么写的"、改稿也无从定位；页码是 PDF 唯一的天然坐标。
     */
    fun readPdfText(f: File, minChars: Int = 20): String? = runCatching {
        PDDocument.load(f).use { doc ->
            if (doc.isEncrypted) runCatching { doc.setAllSecurityToBeRemoved(true) }
            val out = StringBuilder()
            val total = doc.numberOfPages
            for (p in 1..total) {
                val pageText = PDFTextStripper().apply {
                    startPage = p
                    endPage = p
                }.getText(doc).replace("\u0000", "").trim()
                if (pageText.isEmpty()) continue
                out.append("[第").append(p).append("页]\n").append(pageText).append("\n\n")
            }
            out.toString()
        }
    }.getOrNull()?.takeIf { it.trim().length >= minChars }

    /**
     * 只渲染落盘（不 OCR）：把 PDF 前 [cap] 页存成 `01.png / 02.png …` 到 [snapDir]。
     *
     * 2026-11-14 用途：文字改由云端解析时，**版式/公式图的视觉兜底不能丢**——模型拿到
     * 云端文本后仍可用 file_read_image 读某一页快照（AiStream 视觉直通按 exports/ 路径附图）。
     * 渲染是本地操作，零费用；失败一律返回空表（快照是增强不是必需品）。
     */
    suspend fun snapshotPdfPages(
        context: Context, f: File, snapDir: File, cap: Int = 24
    ): List<File> = runCatching {
        var fd: ParcelFileDescriptor? = null
        var renderer: PdfRenderer? = null
        try {
            fd = ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
            renderer = PdfRenderer(fd)
            if (renderer.pageCount == 0) return emptyList()
            snapDir.mkdirs()
            val out = ArrayList<File>()
            for (i in 0 until minOf(renderer.pageCount, maxOf(0, cap))) {
                val page = renderer.openPage(i)
                val bmp = Bitmap.createBitmap(page.width * 2, page.height * 2, Bitmap.Config.ARGB_8888)
                page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                page.close()
                val sink = File(snapDir, "%02d.png".format(i + 1))
                java.io.FileOutputStream(sink).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bmp.recycle()
                out.add(sink)
            }
            out
        } finally {
            runCatching { renderer?.close() }
            runCatching { fd?.close() }
        }
    }.getOrDefault(emptyList())

    /**
     * 扫描件兜底：逐页渲染成图交给 OCR（同样带 `[第N页]` 锚点）。
     * [ocr] 由调用方注入（Planability 用 OcrHelper），避免这个叶子依赖 Android 服务层。
     *
     * v56.1：[snapDir] 非空时把前 [snapPageCap] 页的快照 PNG 持久化到该目录（会话导出目录）
     * ——视觉直通（AiStream tool 分支按结果文本里的 exports/ 路径附图）需要文件活着；
     * 老行为是页图用完即删，扫描 PDF 的公式/版式页 OCR 不出来时模型没有任何「看图」手段。
     * null = 老行为（临时文件用完即删）。
     */
    suspend fun readPdfByOcr(
        context: Context, f: File,
        snapDir: File? = null,
        snapPageCap: Int = 24,
        ocr: suspend (Context, Uri) -> String
    ): String? = runCatching {
        var fd: ParcelFileDescriptor? = null
        var renderer: PdfRenderer? = null
        try {
            fd = ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
            renderer = PdfRenderer(fd)
            if (renderer.pageCount == 0) return null
            val out = StringBuilder()
            val tmp = File(context.cacheDir, "pdf_ocr_page_${System.currentTimeMillis()}.png")
            try {
                for (i in 0 until renderer.pageCount) {
                    val page = renderer.openPage(i)
                    // 2 倍渲染：小字在 1x 下 OCR 识别率明显下降
                    val bmp = Bitmap.createBitmap(page.width * 2, page.height * 2, Bitmap.Config.ARGB_8888)
                    page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    page.close()
                    // cap 内的页写快照（保留），cap 外沿用临时文件（OCR 完即删）
                    val sink = if (snapDir != null && i < snapPageCap)
                        File(snapDir.apply { mkdirs() }, "%02d.png".format(i + 1)) else tmp
                    java.io.FileOutputStream(sink).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    bmp.recycle()
                    out.append("[第").append(i + 1).append("页]\n")
                        .append(ocr(context, Uri.fromFile(sink))).append("\n\n")
                }
            } finally {
                runCatching { tmp.delete() }
            }
            return out.toString().takeIf { it.isNotBlank() }
        } finally {
            runCatching { renderer?.close() }
            runCatching { fd?.close() }
        }
    }.getOrNull()

    /**
     * 从**老 .doc**（OLE2）里解出内嵌图片逐张 OCR。
     * HWPF 的 PicturesTable 会把非图片记录也列进来，按扩展名过滤。
     */
    suspend fun readDocPictures(
        context: Context,
        f: File,
        ocr: suspend (Context, Uri) -> String
    ): String? = runCatching {
        if (f.extension.lowercase() != "doc") return null
        FileInputStream(f).use { fis ->
            val doc = HWPFDocument(fis)
            val pics = doc.picturesTable.allPictures
            if (pics.isEmpty()) return null
            val out = StringBuilder()
            pics.forEachIndexed { i, p ->
                val ext = (p.suggestFileExtension() ?: "").lowercase()
                if (ext !in setOf("png", "jpg", "jpeg", "wmf", "emf")) return@forEachIndexed
                // wmf/emf 是矢量图，ML Kit 读不了；只处理位图
                if (ext == "wmf" || ext == "emf") return@forEachIndexed
                val img = File(context.cacheDir, "doc_img_${System.currentTimeMillis()}_$i.$ext")
                try {
                    img.writeBytes(p.content)
                    val txt = ocr(context, Uri.fromFile(img))
                    if (txt.isNotBlank()) {
                        out.append("[文档内图片 ").append(i + 1).append("]\n")
                            .append(txt).append("\n\n")
                    }
                } finally {
                    img.delete()
                }
            }
            out.toString().takeIf { it.isNotBlank() }
        }
    }.getOrNull()

    /**
     * 这份 Word 是不是"字太少、八成是整页截图"？——判定后由调用方补图片 OCR。
     *
     * 判据用**文件体积 vs 可见字符**：外教/课件常把整页讲义截图贴进 docx，
     * POI 只能抽到图外的零星文字；不补 OCR 的话模型看到的是一份"几乎空白"的文档。
     */
    fun wordLooksLikeImages(text: String?, f: File): Boolean {
        val visible = text?.count { it.isLetterOrDigit() } ?: 0
        return f.length() / 1024 >= 40 && visible < 200
    }

    /**
     * 从 docx 里解出内嵌图片逐张 OCR（返回带 `[文档内图片 N]` 锚点的文本）。
     * [ocr] 由调用方注入，叶子不直接依赖 Android 服务层。无图/非 docx 返回 null。
     */
    suspend fun readWordPictures(
        context: Context,
        f: File,
        ocr: suspend (Context, Uri) -> String
    ): String? = runCatching {
        if (f.extension.lowercase() != "docx") return null
        FileInputStream(f).use { fis ->
            XWPFDocument(fis).use { docx ->
                val pics = docx.allPictures
                if (pics.isEmpty()) return null
                val out = StringBuilder()
                pics.forEachIndexed { i, p ->
                    val ext = (p.suggestFileExtension() ?: "png").lowercase()
                    if (ext !in setOf("png", "jpg", "jpeg", "webp", "gif", "bmp")) return@forEachIndexed
                    val img = File(context.cacheDir, "docx_img_${System.currentTimeMillis()}_$i.$ext")
                    try {
                        img.writeBytes(p.data)
                        val txt = ocr(context, Uri.fromFile(img))
                        if (txt.isNotBlank()) {
                            out.append("[文档内图片 ").append(i + 1).append("]\n")
                                .append(txt).append("\n\n")
                        }
                    } finally {
                        img.delete()
                    }
                }
                out.toString().takeIf { it.isNotBlank() }
            }
        }
    }.getOrNull()

    /**
     * 归一化：压缩 3 个以上连续空行；**不动行内空格**（这正是今天粘连 bug 的所在）。
     */
    private fun normalize(text: String): String {
        val lines = text.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        val out = ArrayList<String>(lines.size)
        var blank = 0
        for (line in lines) {
            if (line.isBlank()) {
                blank++
                if (blank <= 2) out.add("")
            } else {
                out.add(line)
                blank = 0
            }
        }
        return out.joinToString("\n").trim()
    }
}
