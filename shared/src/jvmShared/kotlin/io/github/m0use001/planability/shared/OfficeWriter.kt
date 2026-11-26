package io.github.m0use001.planability.shared

import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * v7 插件能力包的最小实现器：
 * - 办公三件套：手写最小 OOXML（docx/xlsx），零三方依赖
 * - 网页阅读：抓取 HTML 并提取正文文本
 * - 日历交换：生成 .ics
 * M2 从 app/ai/ptl/OfficeWriter.kt 迁入（jvmShared：java.io/net/time，无 Android 依赖）；
 * 表格行切分改用同模块的 [splitTableRow]（原 core.splitTableRow，同口径）。
 */
object OfficeWriter {

    private fun esc(s: String): String = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")

    // ---------- docx：轻量 Markdown → 带真样式的 Word ----------
    //
    // 2026-10-20：用户问"能不能用手机自带文档软件、不借助电脑"。
    // 手机上的文档应用（这台机子是 OPPO 自带 andes.oplus.documentsreader）
    // 只能**打开/编辑**，没有"按内容生成 docx"的接口；而 docx 本身只是一包 XML，
    // 生成不需要电脑，缺的是**样式**。旧实现每行一个裸段落、只有加粗，
    // 所以打开后是"记事本风"。这里把它补成真正的排版：
    //   # / ## / ###  → 标题 1/2/3（黑体、分级字号、分色）
    //   - / * / 1.    → 项目符号 / 编号（缩进悬挂）
    //   | a | b |     → 表格（首行表头底色、细边框、按列均分）
    //   ---           → 分隔线
    //   **粗体** / `代码` / *斜体*
    // 版式：A4、上下左右 2.54cm、正文 1.5 倍行距、标题段前段后留白。
    private const val DOCX_FONT_BODY = "宋体"
    private const val DOCX_FONT_HEAD = "黑体"
    private const val DOCX_TEXT_WIDTH = 9360   // A4 21cm - 左右 2.54cm 各一

    fun writeDocx(out: File, content: String) {
        val lines = content.lines()
        val body = StringBuilder()
        var i = 0
        while (i < lines.size) {
            val t = lines[i].trim()
            when {
                t.isEmpty() -> { body.append("<w:p/>"); i++ }
                // 表格：连续的 | 行；| --- | 是分隔行，丢掉
                t.startsWith("|") && t.endsWith("|") -> {
                    val rows = mutableListOf<List<String>>()
                    while (i < lines.size && lines[i].trim().startsWith("|")) {
                        val cur = lines[i].trim()
                        // 2026-11-12：与渲染端同口径——`$…$` 里的竖线不是列分隔符
                        val cells = splitTableRow(cur)
                        val isRule = cells.isNotEmpty() &&
                            cells.all { it.matches(Regex(":?-{2,}:?")) }
                        if (!isRule) rows += cells
                        i++
                    }
                    if (rows.isNotEmpty()) {
                        body.append(docxTable(rows))
                        body.append("<w:p/>")   // Word 要求表格后有一个段落收尾
                    }
                }
                t == "---" || t == "***" || t == "___" -> { body.append(docxRule()); i++ }
                t.startsWith("### ") -> { body.append(docxPara(t.removePrefix("### "), "Heading3", 24)); i++ }
                t.startsWith("## ") -> { body.append(docxPara(t.removePrefix("## "), "Heading2", 28)); i++ }
                t.startsWith("# ") -> { body.append(docxPara(t.removePrefix("# "), "Heading1", 32)); i++ }
                // 逐行首尾都是 ** 的，当标题用（AI 常这么写）
                t.startsWith("**") && t.endsWith("**") && t.length > 4 && !t.drop(2).dropLast(2).contains("**") ->
                    { body.append(docxPara(t.drop(2).dropLast(2), "Heading2", 28)); i++ }
                t.matches(Regex("^[-*•]\\s+.*")) ->
                    { body.append(docxPara(t.drop(2).trim(), null, 21, bullet = true)); i++ }
                t.matches(Regex("^\\d+[.)]\\s+.*")) -> {
                    val num = t.takeWhile { it.isDigit() }
                    val text = t.drop(num.length + 1).trim()
                    body.append(docxPara(num + ". " + text, null, 21, indent = 420))
                    i++
                }
                t.startsWith("> ") -> { body.append(docxQuote(t.removePrefix("> "))); i++ }
                else -> { body.append(docxPara(t, null, 21)); i++ }
            }
        }
        val document = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
            "<w:document xmlns:w=\"$W_NS\"><w:body>$body${docxSectPr()}</w:body></w:document>"
        zip(
            out,
            mapOf(
                "[Content_Types].xml" to "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
                    "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">" +
                    "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>" +
                    "<Default Extension=\"xml\" ContentType=\"application/xml\"/>" +
                    "<Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/>" +
                    "<Override PartName=\"/word/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml\"/>" +
                    "</Types>",
                "_rels/.rels" to "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
                    "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
                    "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"word/document.xml\"/>" +
                    "</Relationships>",
                "word/_rels/document.xml.rels" to
                    "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
                        "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
                        "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" Target=\"styles.xml\"/>" +
                        "</Relationships>",
                "word/styles.xml" to docxStyles(),
                "word/document.xml" to document
            )
        )
    }

    private const val W_NS = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"

    /** 段落：style=Heading1/2/3 走样式表；bullet/indent 用于列表；sizeHalf=字号（半磅） */
    private fun docxPara(
        text: String,
        style: String?,
        sizeHalf: Int,
        bold: Boolean = style != null,
        bullet: Boolean = false,
        indent: Int = 0
    ): String {
        val sb = StringBuilder("<w:p><w:pPr>")
        if (style != null) sb.append("<w:pStyle w:val=\"$style\"/>")
        when {
            bullet -> sb.append("<w:ind w:left=\"420\" w:hanging=\"210\"/>")
            indent > 0 -> sb.append("<w:ind w:left=\"$indent\"/>")
        }
        if (style == null && !bullet) sb.append("<w:spacing w:before=\"60\" w:after=\"120\" w:line=\"300\" w:lineRule=\"auto\"/>")
        sb.append("</w:pPr>")
        val prefix = if (bullet) "• " else ""
        sb.append(docxRuns(prefix + text, bold, sizeHalf, if (style != null) DOCX_FONT_HEAD else DOCX_FONT_BODY))
        sb.append("</w:p>")
        return sb.toString()
    }

    private fun docxQuote(text: String): String = "<w:p><w:pPr>" +
        "<w:pBdr><w:left w:val=\"single\" w:sz=\"18\" w:space=\"8\" w:color=\"9AA5B1\"/></w:pBdr>" +
        "<w:ind w:left=\"420\"/><w:spacing w:before=\"60\" w:after=\"120\"/></w:pPr>" +
        docxRuns(text, false, 21, DOCX_FONT_BODY, color = "4A5568") + "</w:p>"

    private fun docxRule(): String = "<w:p><w:pPr><w:pBdr>" +
        "<w:bottom w:val=\"single\" w:sz=\"6\" w:space=\"1\" w:color=\"C9D1DB\"/>" +
        "</w:pBdr><w:spacing w:before=\"120\" w:after=\"120\"/></w:pPr></w:p>"

    private fun docxTable(rows: List<List<String>>): String {
        val cols = rows.maxOf { it.size }.coerceAtLeast(1)
        val w = DOCX_TEXT_WIDTH / cols
        val border = "<w:top w:val=\"single\" w:sz=\"4\" w:space=\"0\" w:color=\"C9D1DB\"/>" +
            "<w:left w:val=\"single\" w:sz=\"4\" w:space=\"0\" w:color=\"C9D1DB\"/>" +
            "<w:bottom w:val=\"single\" w:sz=\"4\" w:space=\"0\" w:color=\"C9D1DB\"/>" +
            "<w:right w:val=\"single\" w:sz=\"4\" w:space=\"0\" w:color=\"C9D1DB\"/>"
        val sb = StringBuilder("<w:tbl><w:tblPr>")
        sb.append("<w:tblW w:w=\"$DOCX_TEXT_WIDTH\" w:type=\"dxa\"/>")
        sb.append("<w:tblBorders>$border</w:tblBorders><w:tblLayout w:type=\"fixed\"/>")
        sb.append("</w:tblPr>")
        // 列宽
        sb.append("<w:tblGrid>")
        repeat(cols) { sb.append("<w:gridCol w:w=\"$w\"/>") }
        sb.append("</w:tblGrid>")
        rows.forEachIndexed { ri, row ->
            sb.append("<w:tr>")
            val cells = (0 until cols).map { row.getOrElse(it) { "" } }
            if (ri == 0) sb.append("<w:trPr><w:tblHeader/></w:trPr>")
            cells.forEach { c ->
                sb.append("<w:tc><w:tcPr><w:tcW w:w=\"$w\" w:type=\"dxa\"/>")
                if (ri == 0) sb.append("<w:shd w:val=\"clear\" w:color=\"auto\" w:fill=\"EEF2F7\"/>")
                sb.append("<w:vAlign w:val=\"center\"/></w:tcPr>")
                sb.append("<w:p><w:pPr><w:spacing w:before=\"40\" w:after=\"40\" w:line=\"240\" w:lineRule=\"auto\"/></w:pPr>")
                sb.append(docxRuns(c, ri == 0, 20, DOCX_FONT_BODY))
                sb.append("</w:p></w:tc>")
            }
            sb.append("</w:tr>")
        }
        sb.append("</w:tbl>")
        return sb.toString()
    }

    private fun docxSectPr(): String = "<w:sectPr>" +
        "<w:pgSz w:w=\"11906\" w:h=\"16838\"/>" +
        "<w:pgMar w:top=\"1440\" w:right=\"1440\" w:bottom=\"1440\" w:left=\"1440\" w:header=\"851\" w:footer=\"992\" w:gutter=\"0\"/>" +
        "</w:sectPr>"

    private fun docxStyles(): String = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
        "<w:styles xmlns:w=\"$W_NS\">" +
        "<w:docDefaults><w:rPrDefault><w:rPr>" +
        "<w:rFonts w:ascii=\"Calibri\" w:hAnsi=\"Calibri\" w:eastAsia=\"$DOCX_FONT_BODY\" w:cs=\"Calibri\"/>" +
        "<w:sz w:val=\"21\"/><w:szCs w:val=\"21\"/><w:lang w:val=\"en-US\" w:eastAsia=\"zh-CN\"/>" +
        "</w:rPr></w:rPrDefault>" +
        "<w:pPrDefault><w:pPr><w:spacing w:after=\"120\" w:line=\"300\" w:lineRule=\"auto\"/></w:pPr></w:pPrDefault>" +
        "</w:docDefaults>" +
        "<w:style w:type=\"paragraph\" w:default=\"1\" w:styleId=\"Normal\"><w:name w:val=\"Normal\"/><w:qFormat/></w:style>" +
        docxHeadingStyle("Heading1", "heading 1", 1, 32, "1F3864") +
        docxHeadingStyle("Heading2", "heading 2", 2, 28, "2E5496") +
        docxHeadingStyle("Heading3", "heading 3", 3, 24, "44546A") +
        "</w:styles>"

    private fun docxHeadingStyle(id: String, name: String, level: Int, sizeHalf: Int, color: String): String =
        "<w:style w:type=\"paragraph\" w:styleId=\"$id\"><w:name w:val=\"$name\"/>" +
            "<w:basedOn w:val=\"Normal\"/><w:next w:val=\"Normal\"/><w:qFormat/>" +
            "<w:pPr><w:keepNext/><w:outlineLvl w:val=\"${level - 1}\"/>" +
            "<w:spacing w:before=\"${if (level == 1) 320 else 240}\" w:after=\"140\"/></w:pPr>" +
            "<w:rPr><w:rFonts w:eastAsia=\"$DOCX_FONT_HEAD\"/><w:b/>" +
            "<w:color w:val=\"$color\"/><w:sz w:val=\"$sizeHalf\"/><w:szCs w:val=\"$sizeHalf\"/></w:rPr></w:style>"

    /**
     * 行内格式：`**粗体**`、`` `代码` ``、`*斜体*`。
     * 解析失败（没闭合）时按原文输出——宁可少一点样式，也不能把文字吃掉。
     */
    private fun docxRuns(
        text: String,
        bold: Boolean,
        sizeHalf: Int,
        eastAsia: String,
        color: String? = null
    ): String {
        val sb = StringBuilder()
        var i = 0
        fun emit(s: String, b: Boolean, mono: Boolean = false, italic: Boolean = false) {
            if (s.isEmpty()) return
            val rPr = StringBuilder("<w:rPr>")
            rPr.append("<w:rFonts w:eastAsia=\"$eastAsia\"")
            if (mono) rPr.append(" w:ascii=\"Consolas\" w:hAnsi=\"Consolas\"")
            rPr.append("/>")
            if (b) rPr.append("<w:b/>")
            if (italic) rPr.append("<w:i/>")
            if (color != null) rPr.append("<w:color w:val=\"$color\"/>")
            rPr.append("<w:sz w:val=\"$sizeHalf\"/><w:szCs w:val=\"$sizeHalf\"/></w:rPr>")
            sb.append("<w:r>").append(rPr)
                .append("<w:t xml:space=\"preserve\">").append(esc(s)).append("</w:t></w:r>")
        }
        while (i < text.length) {
            when {
                text.startsWith("**", i) -> {
                    val j = text.indexOf("**", i + 2)
                    if (j > i) { emit(text.substring(i + 2, j), true); i = j + 2 }
                    else { emit(text.substring(i), bold); i = text.length }
                }
                text.startsWith("`", i) -> {
                    val j = text.indexOf('`', i + 1)
                    if (j > i) { emit(text.substring(i + 1, j), bold, mono = true); i = j + 1 }
                    else { emit(text.substring(i), bold); i = text.length }
                }
                text[i] == '*' && !text.startsWith("**", i) -> {
                    val j = text.indexOf('*', i + 1)
                    if (j > i) { emit(text.substring(i + 1, j), bold, italic = true); i = j + 1 }
                    else { emit(text.substring(i), bold); i = text.length }
                }
                else -> {
                    var j = i
                    while (j < text.length && text[j] != '*' && text[j] != '`') j++
                    emit(text.substring(i, j), bold)
                    i = j
                }
            }
        }
        return sb.toString()
    }

    // ---------- xlsx：TSV 行（单元格用制表符分隔），带表头样式/列宽/冻结首行 ----------

    // 内容 → 数字类型识别（2026-10-20）：金额千分位 / 百分比 / 日期
    private val DATE_RE = Regex("^(\\d{4})[-/](\\d{1,2})[-/](\\d{1,2})$")
    private val PCT_RE = Regex("^(-?\\d+(?:\\.\\d+)?)\\s*%$")
    private val GROUP_RE = Regex("^-?\\d{1,3}(?:,\\d{3})+(?:\\.\\d+)?$")

    /**
     * 生成 xlsx。
     *
     * 2026-10-20 用户要求「表格处理纯手机端」之后又补的样式：之前只有裸数据，
     * 打开就是一屏没有边框的白格子，看不出哪行是表头。现在给：
     * - 首行当表头：加粗、浅蓝底、居中、细边框；数据行细边框 + 自动换行；
     * - 列宽按内容估算（中文按 1.8 个字宽算），夹在 10~60 之间；
     * - 冻结首行，往下翻表头不跑。
     * 依然零依赖（手写 OOXML），不需要电脑。
     */
    fun writeXlsx(out: File, content: String) {
        val rows = content.lines().filter { it.isNotBlank() }.map { it.split("\t") }
        // 数字格式（2026-10-20 补）：金额/百分比/日期如果只按"数字/文本"处理，
        // 用户看到的是 1234.5 而不是 1,234.50、0.52 而不是 52%、46319 而不是 2026-10-15。
        // 这里按内容识别并套对应 numFmt（样式 3/4/5）。
        fun cell(ref: String, raw: String, header: Boolean): String {
            val v = raw.trim()
            if (header) return "<c r=\"$ref\" s=\"1\" t=\"inlineStr\"><is><t xml:space=\"preserve\">${esc(v)}</t></is></c>"
            if (v.isEmpty()) return "<c r=\"$ref\" s=\"2\"/>"
            DATE_RE.find(v)?.let { m ->
                val serial = java.time.temporal.ChronoUnit.DAYS.between(
                    java.time.LocalDate.of(1899, 12, 30),
                    java.time.LocalDate.of(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt())
                )
                return "<c r=\"$ref\" s=\"5\"><v>$serial</v></c>"
            }
            PCT_RE.find(v)?.let { m ->
                val n = m.groupValues[1].toDouble() / 100.0
                // 有小数位用 0.0%，整数用 0%：52% 不该显示成 52.0%
                val style = if (m.groupValues[1].contains('.')) 4 else 6
                return "<c r=\"$ref\" s=\"$style\"><v>$n</v></c>"
            }
            if (GROUP_RE.matches(v)) {
                return "<c r=\"$ref\" s=\"3\"><v>${v.replace(",", "")}</v></c>"
            }
            return if (v.toDoubleOrNull() != null) "<c r=\"$ref\" s=\"2\"><v>$v</v></c>"
            else "<c r=\"$ref\" s=\"2\" t=\"inlineStr\"><is><t xml:space=\"preserve\">${esc(v)}</t></is></c>"
        }
        val sheetRows = rows.mapIndexed { ri, cells ->
            val r = ri + 1
            val cols = cells.mapIndexed { ci, v -> cell(colName(ci) + r, v, ri == 0) }.joinToString("")
            "<row r=\"$r\">$cols</row>"
        }.joinToString("")
        // 列宽：每列最长内容（中文/全角按 1.8 个字符算）
        val colCount = rows.maxOfOrNull { it.size } ?: 0
        val widthXml = (0 until colCount).map { ci ->
            val longest = rows.maxOfOrNull { row ->
                (row.getOrElse(ci) { "" }).sumOf { ch ->
                    if (ch.code > 0x2E80) 1.8 else 1.0
                }
            } ?: 0.0
            val w = (longest * 1.1 + 3).coerceIn(10.0, 60.0)
            "<col min=\"${ci + 1}\" max=\"${ci + 1}\" width=\"${"%.1f".format(w)}\" customWidth=\"1\"/>"
        }.joinToString("")
        val freeze = if (rows.size > 1)
            "<pane ySplit=\"1\" topLeftCell=\"A2\" activePane=\"bottomLeft\" state=\"frozen\"/>"
        else ""
        val sheet = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
            "<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">" +
            "<sheetViews><sheetView workbookViewId=\"0\">$freeze</sheetView></sheetViews>" +
            (if (widthXml.isEmpty()) "" else "<cols>$widthXml</cols>") +
            "<sheetData>$sheetRows</sheetData></worksheet>"
        val workbook = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
            "<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" " +
            "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\">" +
            "<sheets><sheet name=\"Sheet1\" sheetId=\"1\" r:id=\"rId1\"/></sheets></workbook>"
        val border =
            "<left style=\"thin\"><color rgb=\"FFC9D1DB\"/></left>" +
                "<right style=\"thin\"><color rgb=\"FFC9D1DB\"/></right>" +
                "<top style=\"thin\"><color rgb=\"FFC9D1DB\"/></top>" +
                "<bottom style=\"thin\"><color rgb=\"FFC9D1DB\"/></bottom>" +
                "<diagonal/>"
        val styles = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
            "<styleSheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">" +
            "<fonts count=\"2\">" +
            "<font><sz val=\"11\"/><name val=\"Calibri\"/><family val=\"2\"/></font>" +
            "<font><b/><sz val=\"11\"/><color rgb=\"FF1F3864\"/><name val=\"Calibri\"/><family val=\"2\"/></font>" +
            "</fonts>" +
            "<fills count=\"3\">" +
            "<fill><patternFill patternType=\"none\"/></fill>" +
            "<fill><patternFill patternType=\"gray125\"/></fill>" +
            "<fill><patternFill patternType=\"solid\"><fgColor rgb=\"FFEEF2F7\"/><bgColor indexed=\"64\"/></patternFill></fill>" +
            "</fills>" +
            "<borders count=\"3\"><border><left/><right/><top/><bottom/><diagonal/></border>" +
            "<border>$border</border>" +
            "<border>$border</border></borders>" +
            "<cellStyleXfs count=\"1\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\"/></cellStyleXfs>" +
            // 自定义数字格式：千分位 / 百分比 / 日期（Excel 内置的 164 起是自定义区）
            "<numFmts count=\"4\">" +
            "<numFmt numFmtId=\"164\" formatCode=\"#,##0.00\"/>" +
            "<numFmt numFmtId=\"165\" formatCode=\"0.0%\"/>" +
            "<numFmt numFmtId=\"166\" formatCode=\"yyyy-mm-dd\"/>" +
            "<numFmt numFmtId=\"167\" formatCode=\"0%\"/>" +
            "</numFmts>" +
            "<cellXfs count=\"7\">" +
            "<xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\"/>" +
            // 1 = 表头：加粗 + 浅蓝底 + 边框 + 居中
            "<xf numFmtId=\"0\" fontId=\"1\" fillId=\"2\" borderId=\"1\" xfId=\"0\"" +
            " applyFont=\"1\" applyFill=\"1\" applyBorder=\"1\" applyAlignment=\"1\">" +
            "<alignment horizontal=\"center\" vertical=\"center\"/></xf>" +
            // 2 = 数据：边框 + 垂直居中 + 自动换行
            "<xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"2\" xfId=\"0\"" +
            " applyBorder=\"1\" applyAlignment=\"1\">" +
            "<alignment vertical=\"center\" wrapText=\"1\"/></xf>" +
            // 3 = 千分位金额
            "<xf numFmtId=\"164\" fontId=\"0\" fillId=\"0\" borderId=\"2\" xfId=\"0\"" +
            " applyNumberFormat=\"1\" applyBorder=\"1\" applyAlignment=\"1\">" +
            "<alignment vertical=\"center\"/></xf>" +
            // 4 = 百分比
            "<xf numFmtId=\"165\" fontId=\"0\" fillId=\"0\" borderId=\"2\" xfId=\"0\"" +
            " applyNumberFormat=\"1\" applyBorder=\"1\" applyAlignment=\"1\">" +
            "<alignment vertical=\"center\"/></xf>" +
            // 5 = 日期
            "<xf numFmtId=\"166\" fontId=\"0\" fillId=\"0\" borderId=\"2\" xfId=\"0\"" +
            " applyNumberFormat=\"1\" applyBorder=\"1\" applyAlignment=\"1\">" +
            "<alignment vertical=\"center\"/></xf>" +
            // 6 = 整数百分比（52% 而不是 52.0%）
            "<xf numFmtId=\"167\" fontId=\"0\" fillId=\"0\" borderId=\"2\" xfId=\"0\"" +
            " applyNumberFormat=\"1\" applyBorder=\"1\" applyAlignment=\"1\">" +
            "<alignment vertical=\"center\"/></xf>" +
            "</cellXfs></styleSheet>"
        zip(
            out,
            mapOf(
                "[Content_Types].xml" to "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
                    "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">" +
                    "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>" +
                    "<Default Extension=\"xml\" ContentType=\"application/xml\"/>" +
                    "<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>" +
                    "<Override PartName=\"/xl/worksheets/sheet1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>" +
                    "<Override PartName=\"/xl/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml\"/>" +
                    "</Types>",
                "_rels/.rels" to "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
                    "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
                    "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/>" +
                    "</Relationships>",
                "xl/workbook.xml" to workbook,
                "xl/_rels/workbook.xml.rels" to "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
                    "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
                    "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet1.xml\"/>" +
                    "<Relationship Id=\"rId2\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" Target=\"styles.xml\"/>" +
                    "</Relationships>",
                "xl/styles.xml" to styles,
                "xl/worksheets/sheet1.xml" to sheet
            )
        )
    }

    private fun colName(index: Int): String {
        var i = index
        val sb = StringBuilder()
        while (i >= 0) {
            sb.insert(0, ('A' + i % 26))
            i = i / 26 - 1
        }
        return sb.toString()
    }

    private fun zip(out: File, entries: Map<String, String>) {
        ZipOutputStream(FileOutputStream(out)).use { zos ->
            entries.forEach { (name, xml) ->
                zos.putNextEntry(ZipEntry(name))
                zos.write(xml.toByteArray(Charsets.UTF_8))
                zos.closeEntry()
            }
        }
    }

    // ---------- web_fetch：抓取并提取正文 ----------

    fun fetchText(url: String): String? = runCatching {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 20_000
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Planability workbench)")
        conn.instanceFollowRedirects = true
        val body = conn.inputStream.use { ins ->
            val buf = ByteArray(64 * 1024)
            val sb = StringBuilder()
            var total = 0
            while (total < 800 * 1024) {
                val n = ins.read(buf)
                if (n < 0) break
                total += n
                sb.append(String(buf, 0, n, Charsets.UTF_8))
            }
            sb.toString()
        }
        conn.disconnect()
        extractText(body)
    }.getOrNull()

    private fun extractText(html: String): String {
        val noScript = html
            .replace(Regex("(?is)<script[^>]*>.*?</script>"), " ")
            .replace(Regex("(?is)<style[^>]*>.*?</style>"), " ")
            .replace(Regex("(?is)<(br|p|div|li|tr|h[1-6])[^>]*>"), "\n")
        val text = noScript
            .replace(Regex("<[^>]*>"), " ")
            .replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<")
            .replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'")
            .replace(Regex("[ \\t\\x0B\\f\\r]+"), " ")
            .replace(Regex("\\n{3,}"), "\n\n")
            .trim()
        return text
    }

    // ---------- ics ----------

    fun buildIcs(title: String, start: Long, end: Long, place: String, note: String): String {
        val fmt = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC)
        return buildString {
            append("BEGIN:VCALENDAR\r\n")
            append("VERSION:2.0\r\n")
            append("PRODID:-//Planability//Workbench//CN\r\n")
            append("BEGIN:VEVENT\r\n")
            append("UID:planability-").append(start).append("-").append(title.hashCode()).append("@local\r\n")
            append("DTSTART:").append(fmt.format(Instant.ofEpochMilli(start))).append("\r\n")
            append("DTEND:").append(fmt.format(Instant.ofEpochMilli(maxOf(end, start + 3600_000L)))).append("\r\n")
            append("SUMMARY:").append(escIcs(title)).append("\r\n")
            if (place.isNotBlank()) append("LOCATION:").append(escIcs(place)).append("\r\n")
            if (note.isNotBlank()) append("DESCRIPTION:").append(escIcs(note)).append("\r\n")
            append("END:VEVENT\r\n")
            append("END:VCALENDAR\r\n")
        }
    }

    private fun escIcs(s: String): String = s
        .replace("\\", "\\\\")
        .replace(";", "\\;")
        .replace(",", "\\,")
        .replace("\n", "\\n")
}
