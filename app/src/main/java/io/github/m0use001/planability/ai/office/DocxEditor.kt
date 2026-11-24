package io.github.m0use001.planability.ai.office

import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * v14.3：**docx 增量改稿**（P2 的 Word 部分）。
 *
 * ## 比 pptx 简单在哪，又在哪更危险
 *
 * 简单：docx 的段落都平铺在 `word/document.xml` 一个文件里，改一段就只是替换一段，
 * 没有关系表、编号、页码这些全局状态要照顾。
 *
 * 更危险：**`w:p` 会嵌套**。表格单元格里就是 `<w:tbl><w:tr><w:tc><w:p>`，
 * 用正则按 `<w:p` 全局切分会把表格单元格的文字也算进"第 N 段"，
 * 于是用户说的"改第 5 段"会命中一个表格单元格——改出来的东西看起来没毛病，
 * 但改的是完全不是他指的地方。所以这里和 [PptxEditor] 一样，只取 `<w:body>` 的
 * **直属**段落，跟用户心里的"第几段"对齐。
 *
 * ## 保真策略：留 pPr，换 runs
 *
 * 段落样式（标题级别、缩进、编号）都写在 `<w:pPr>` 里，字体加粗等在 `<w:rPr>` 里。
 * 我们**保留整个 `<w:pPr>`**，只把 runs 换成一个新 run（沿用原来的第一个 `<w:rPr>`）。
 * 这样"改文字"不会顺手把标题降级成正文——那是改文字时最容易被忽略的破坏。
 */
object DocxEditor {

    data class Paragraph(val text: String, val style: String)

    data class Edit(val index: Int, val text: String)   // index: 0 基，与"第 N 段"对应

    data class Result(val changed: Int, val failed: List<String>)

    class DocxException(msg: String) : Exception(msg)

    /** 读出正文段落（不含表格单元格内的段落）。 */
    fun read(file: File): List<Paragraph> {
        val xml = documentXml(file)
        return topLevelParagraphs(xml).map { p ->
            Paragraph(textOf(p), styleOf(p))
        }
    }

    /**
     * 应用编辑并写出到 [out]。
     *
     * 未点名的段落保持原样——与 [PptxEditor] 同一个原则：**没让改的地方一个字节都不动**。
     */
    fun apply(src: File, edits: List<Edit>, out: File): Result {
        val original = documentXml(src)
        // 在原文上替换：按段落序号定位，逐个切出边界再拼，避免"改了后面全部错位"
        val spans = paragraphSpans(original)
        val failed = ArrayList<String>()
        val byIndex = HashMap<Int, Edit>()
        for (e in edits) {
            if (e.index !in spans.indices) {
                failed += "第 ${e.index + 1} 段不存在（这份文档共 ${spans.size} 段）"
                continue
            }
            byIndex[e.index] = e
        }
        if (byIndex.isEmpty()) return Result(0, failed)

        val sb = StringBuilder()
        var cursor = 0
        var changed = 0
        for (i in spans.indices) {
            val (s, e) = spans[i]
            sb.append(original, cursor, s)
            val edit = byIndex[i]
            if (edit == null) {
                sb.append(original, s, e)
            } else {
                sb.append(rewriteParagraph(original.substring(s, e), edit.text))
                changed++
            }
            cursor = e
        }
        sb.append(original, cursor, original.length)

        ZipFile(src).use { z ->
            val out2 = FileOutputStream(out)
            ZipOutputStream(out2).use { zo ->
                val en = z.entries()
                while (en.hasMoreElements()) {
                    val ent = en.nextElement()
                    if (ent.isDirectory) continue
                    zo.putNextEntry(ZipEntry(ent.name))
                    val data = z.getInputStream(ent).readBytes()
                    if (ent.name == "word/document.xml") {
                        zo.write(sb.toString().toByteArray(Charsets.UTF_8))
                    } else {
                        zo.write(data)
                    }
                    zo.closeEntry()
                }
            }
        }
        return Result(changed, failed)
    }

    // ---------------- 内部 ----------------

    private fun documentXml(f: File): String =
        ZipFile(f).use { z ->
            z.getEntry("word/document.xml")
                ?: throw DocxException("不是有效的 docx（缺 word/document.xml）")
            z.getInputStream(z.getEntry("word/document.xml")!!).readBytes().toString(Charsets.UTF_8)
        }

    /**
     * `<w:body>` 直属 `<w:p>` 的下标区间（半开区间）。
     *
     * 下标是**相对整份 XML** 的，扫描时用的是 body 切片，所以最后要加回偏移量——
     * 这里踩过一次：返回 body 内的相对下标却拿去切整份 XML，切出来的是错位碎片
     * （表现为读不出文字、改完文件结构损坏）。
     */
    private fun paragraphSpans(xml: String): List<Pair<Int, Int>> {
        val bs = xml.indexOf("<w:body>")
        if (bs < 0) return emptyList()
        val start = bs + "<w:body>".length
        val end = xml.indexOf("</w:body>", start)
        val body = if (end < 0) xml.substring(start) else xml.substring(start, end)

        val spans = ArrayList<Pair<Int, Int>>()
        var i = 0
        var depth = 0
        var pStart = -1
        while (i < body.length) {
            val lt = body.indexOf('<', i)
            if (lt < 0) break
            val gt = body.indexOf('>', lt)
            if (gt < 0) break
            val tag = body.substring(lt + 1, gt)
            val name = tag.trimStart('/').substringBefore(' ').substringBefore('>')
            val isClose = tag.startsWith("/")
            if (tag.endsWith("/")) {
                i = gt + 1
                continue
            }
            if (depth == 0 && pStart < 0 && name == "w:p") pStart = lt
            if (isClose) {
                depth--
                if (depth == 0 && pStart >= 0) {
                    spans.add((start + pStart) to (start + gt + 1))
                    pStart = -1
                }
            } else {
                depth++
            }
            i = gt + 1
        }
        return spans
    }

    private fun topLevelParagraphs(xml: String): List<String> =
        paragraphSpans(xml).map { (s, e) -> xml.substring(s, e) }

    private fun textOf(pXml: String): String =
        Regex("<w:t(?:\\s[^>]*)?>(.*?)</w:t>", RegexOption.DOT_MATCHES_ALL).findAll(pXml)
            .map { unescape(it.groupValues[1]) }.joinToString("")

    /** 段落样式名（Heading1 之类），用于判断是不是标题。 */
    private fun styleOf(pXml: String): String =
        Regex("<w:pStyle w:val=\"([^\"]*)\"").find(pXml)?.groupValues?.get(1).orEmpty()

    /**
     * 重写一个段落：保留 `<w:pPr>`（标题级别/缩进/编号都在这里），只换 runs。
     *
     * 为什么留 pPr：改个错别字却把标题降成正文，是这类工具最常见的破坏，而且很难被察觉。
     */
    private fun rewriteParagraph(orig: String, newText: String): String {
        val open = orig.indexOf('>') + 1
        val pPr = Regex("<w:pPr>.*?</w:pPr>", RegexOption.DOT_MATCHES_ALL).find(orig)?.value.orEmpty()
        val rPr = Regex("<w:rPr>.*?</w:rPr>", RegexOption.DOT_MATCHES_ALL).find(orig)?.value
        val head = orig.substring(0, open)
        val body = buildString {
            if (pPr != null) append(pPr)
            if (newText.isEmpty()) {
                // 空段落要写成自闭合的空段落，否则 Word 可能判为损坏
            } else {
                append("<w:r>")
                if (rPr != null) append(rPr)
                append("<w:t xml:space=\"preserve\">").append(escape(newText)).append("</w:t></w:r>")
            }
        }
        return head + body + "</w:p>"
    }

    private fun escape(s: String): String = s
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private fun unescape(s: String): String = s
        .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
        .replace("&apos;", "'").replace("&amp;", "&")
}
