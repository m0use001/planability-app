package io.github.m0use001.planability.ai.office

import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDFont
import com.tom_roush.pdfbox.pdmodel.font.PDType0Font
import java.io.File
import java.io.FileInputStream

/**
 * **离线** PDF 生成（2026-11-09 夜，办公补齐附加项）。
 *
 * ## 为什么存在
 *
 * 此前 PDF 预览只有一条路：电脑端 LibreOffice 桥。电脑不在线（断网/没带电脑）
 * 时 docx/xlsx 导出后没有任何 PDF 可看。这里用 PDFBox + **系统自带 CJK 字体**
 * 生成一份"文字齐全、版式朴素"的 PDF——不追求还原，追求**离线也能看**。
 *
 * ## 字体策略
 *
 * 只用系统字体（只读引用，不拷贝不打包）：TTC 用 TrueTypeCollection 逐字体试，
 * OTF（CFF）走 OTFParser、TTF 走 TTFParser，全部经 PDType0Font 子集内嵌。
 * 用"汉字宽度>0"验证真的是 CJK 字体。全失败返回 false，调用方维持"无 PDF"现状。
 *
 * ## 资源时序（容易踩）
 *
 * 子集内嵌在 `doc.save()` 时才真正读字体数据——**TrueTypeFont/Collection 必须活到
 * save 之后**才能关，提前关会在 save 时 IOException。
 */
object SimplePdfWriter {

    private val FONT_CANDIDATES = listOf(
        "/system/fonts/NotoSansCJK-Regular.ttc",
        "/system/fonts/NotoSansCJKsc-Regular.otf",
        "/system/fonts/NotoSansSC-Regular.otf",
        "/system/fonts/NotoSansSC-Regular.ttf",
        "/system/fonts/DroidSansFallbackFull.ttf",
        "/system/fonts/DroidSansFallback.ttf"
    )

    private const val FONT_SIZE = 11f
    private const val LEADING = 15f
    private const val MARGIN = 40f
    /** 单文件页数上限：预览用，防超大文本把内存/磁盘打爆 */
    private const val MAX_PAGES = 60

    /** 按像素宽度折行（CJK 与拉丁混排都用 getStringWidth 量） */
    private fun wrap(line: String, font: PDFont, maxWidth: Float): List<String> {
        if (line.isEmpty()) return listOf("")
        val out = ArrayList<String>()
        val sb = StringBuilder()
        var width = 0f
        for (ch in line) {
            val w = runCatching { font.getStringWidth(ch.toString()) / 1000f * FONT_SIZE }
                .getOrDefault(FONT_SIZE)
            if (width + w > maxWidth && sb.isNotEmpty()) {
                out.add(sb.toString())
                sb.clear()
                width = 0f
            }
            sb.append(ch)
            width += w
        }
        if (sb.isNotEmpty()) out.add(sb.toString())
        return out.ifEmpty { listOf("") }
    }

    /**
     * 把多行文本写成单栏 PDF。成功 true；字体找不到/写失败 false（调用方按"无 PDF"处理）。
     */
    fun writeLines(lines: List<String>, out: File): Boolean {
        var doc: PDDocument? = null
        // 字体资源（ttc/ttf/otf 的底层句柄）必须活到 doc.save() 之后，见类注释
        var fontRes: AutoCloseable? = null
        return try {
            val d = PDDocument()
            doc = d
            var font: PDFont? = null
            for (path in FONT_CANDIDATES) {
                val f = File(path)
                if (!f.exists()) continue
                runCatching {
                    when {
                        path.endsWith(".ttc") -> {
                            val tc = com.tom_roush.fontbox.ttf.TrueTypeCollection(f)
                            var found: PDFont? = null
                            tc.processAllFonts { ttf ->
                                if (found == null) found = runCatching {
                                    val pf = PDType0Font.load(d, ttf, true)
                                    if (pf.getStringWidth("中") > 0f) pf else null
                                }.getOrNull()
                            }
                            if (found != null) {
                                font = found
                                fontRes = tc   // collection 活到 save 后
                            } else {
                                runCatching { tc.close() }
                            }
                        }
                        path.endsWith(".ttf") -> runCatching {
                            val ttf = com.tom_roush.fontbox.ttf.TTFParser().parse(f)
                            val pf = PDType0Font.load(d, ttf, true)
                            if (pf.getStringWidth("中") > 0f) {
                                font = pf
                                fontRes = ttf
                            } else {
                                runCatching { ttf.close() }
                            }
                        }
                        // .otf（CFF 轮廓）在 fontbox-android 的移植里没有解析器，跳过
                    }
                }
                if (font != null) break
            }
            // 兜底：按名字扫描 /system/fonts 里的 .ttf（ColorOS 的 OPPO Sans / 旧设备
            // DroidSansFallback 等都可能是唯一可用的 CJK 字体；CFF 轮廓的 .otf 读不了，跳过）
            if (font == null) {
                File("/system/fonts").listFiles { f2 -> f2.name.endsWith(".ttf") }
                    ?.sortedBy { it.name }?.take(40)?.forEach { f ->
                        if (font == null) runCatching {
                            val ttf = com.tom_roush.fontbox.ttf.TTFParser().parse(f)
                            val pf = PDType0Font.load(d, ttf, true)
                            if (pf.getStringWidth("中") > 0f) {
                                font = pf
                                fontRes = ttf
                            } else {
                                runCatching { ttf.close() }
                            }
                        }
                    }
            }
            if (font == null) {
                d.close()
                return false
            }
            val fnt = font
            val pageRect = PDRectangle.A4
            var page = PDPage(pageRect)
            d.addPage(page)
            var cs = PDPageContentStream(d, page)
            var y = pageRect.height - MARGIN
            var pages = 1
            try {
                outer@ for (raw in lines) {
                    for (seg in wrap(raw, fnt, pageRect.width - MARGIN * 2)) {
                        if (y < MARGIN) {
                            cs.close()
                            if (pages >= MAX_PAGES) break@outer
                            page = PDPage(pageRect)
                            d.addPage(page)
                            cs = PDPageContentStream(d, page)
                            y = pageRect.height - MARGIN
                            pages++
                        }
                        cs.beginText()
                        cs.setFont(fnt, FONT_SIZE)
                        cs.newLineAtOffset(MARGIN, y)
                        cs.showText(seg)
                        cs.endText()
                        y -= LEADING
                    }
                }
            } finally {
                runCatching { cs.close() }
            }
            d.save(out)
            true
        } catch (t: Throwable) {
            false
        } finally {
            // save 之后才允许关字体资源；doc 自己也在这里收尾
            runCatching { doc?.close() }
            runCatching { fontRes?.close() }
        }
    }
}
