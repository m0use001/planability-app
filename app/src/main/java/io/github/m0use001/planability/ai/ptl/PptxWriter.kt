package io.github.m0use001.planability.ai.ptl

import io.github.m0use001.planability.ai.office.DocModel
import io.github.m0use001.planability.ai.office.SlideLayout
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * v14.3：**PPTX 写出器**（零三方依赖，手写最小 OOXML PresentationML）。
 *
 * ## 为什么值得手写而不是塞个库
 *
 * 手机端加 Apache POI 意味着几 MB 依赖、且 POI 在 Android 上有一堆反射/文件 API 的坑；
 * 而我们要的只是"AI 描述 → 一份能打开的 pptx"。这类**结构固定、只写不读**的场景，
 * 手写序列化器其实是成本最低的写法（现有 `OfficeWriter` 的 docx/xlsx 就是这个路子）。
 *
 * ## 这里的每一条约束都是踩出来的
 *
 * - **元素顺序是强制的**：PowerPoint 按 schema 顺序解析，`p:cSld` 里 `p:bg` 必须在
 *   `p:spTree` 之前、`presentation.xml` 里 `sldMasterIdLst` → `notesMasterIdLst` →
 *   `sldIdLst` → `sldSz` → `notesSz` 一条都不能换位。顺序错了 PowerPoint 直接报
 *   "需要修复"，而手机上看不出来。
 * - **关系 id 必须自洽**：每张 slide 的 `.rels` 里 rId1 永远是 slideLayout，图片从 rId2 起；
 *   `presentation.xml.rels` 里 rId1 是 slideMaster、rId2.. 是各 slide。引用了不存在的
 *   rId 同样触发修复提示。
 * - **Content_Types 要覆盖所有 part**：少一个 Override 就是打不开。
 *
 * 验收方式：本机装了 `python-pptx` 做开发期校验（能打开 ≈ PowerPoint 能打开），
 * 另有单测检查 part 齐全、关系自洽、XML 可解析。python-pptx **不进产物**。
 */
internal object PptxWriter {

    // 版面几何一律取 DocModel.Geometry：写文件的坐标与 P1 画快照的坐标必须同源，
    // 否则"预览里看着对、打开偏了"，那比不给预览更坑人
    private val SLIDE_W = DocModel.Geometry.SLIDE_W
    private val SLIDE_H = DocModel.Geometry.SLIDE_H
    private val MARGIN = DocModel.Geometry.MARGIN

    private const val NS_P = "http://schemas.openxmlformats.org/presentationml/2006/main"
    private const val NS_A = "http://schemas.openxmlformats.org/drawingml/2006/main"
    private const val NS_R = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"

    /** 写出结果。图片抓不到时**跳过该图**而不是留一个坏关系——坏关系会让整个文件打不开。 */
    data class Report(val slides: Int, val pictures: Int, val picturesSkipped: List<String>)

    private fun esc(s: String): String = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")

    fun write(out: File, deck: DocModel.Deck): Report {
        val th = deck.theme
        val notesNeeded = deck.slides.any { it.notes.isNotBlank() }

        // 先排版一次：图片与 slide XML 都按这份结果走，避免"图片序号"和"图元序号"对不上
        val pages = SlideLayout.of(deck)

        // ---- 图片：先落地，拿到 (文件名, 扩展名, 字节) ----
        // 顺序必须与 pages 里的图元顺序严格一致：关系 id 要按出现次序递增，
        // 所以每页存一份"与图元一一对应"的媒体文件名（取不到的是 null，写出时跳过）
        val media = LinkedHashMap<String, ByteArray>()      // ppt/media/imageN.ext -> bytes
        val skipped = ArrayList<String>()
        var picSeq = 0
        val slidePictures = ArrayList<List<String?>>()        // 每页图元对应的媒体文件名（null=已跳过）
        for (page in pages) {
            val usedHere = ArrayList<String?>()
            for (item in page.items) {
                if (item !is SlideLayout.Item.Picture) continue
                val got = loadPicture(item.path)
                if (got == null) {
                    skipped += item.path
                    usedHere += null
                    continue
                }
                val (ext, bytes) = got
                val name = "image${++picSeq}.$ext"
                media[name] = bytes
                usedHere += name
            }
            slidePictures.add(usedHere)
        }

        val entries = LinkedHashMap<String, ByteArray>()

        // ---------- [Content_Types].xml ----------
        val ct = StringBuilder()
        ct.append(xmlDecl).append("<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">")
        ct.append("<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>")
        ct.append("<Default Extension=\"xml\" ContentType=\"application/xml\"/>")
        ct.append("<Default Extension=\"png\" ContentType=\"image/png\"/>")
        ct.append("<Default Extension=\"jpg\" ContentType=\"image/jpeg\"/>")
        ct.append("<Default Extension=\"jpeg\" ContentType=\"image/jpeg\"/>")
        ct.append(part("/ppt/presentation.xml", "presentationml.presentation.main+xml"))
        ct.append(part("/ppt/slideMasters/slideMaster1.xml", "presentationml.slideMaster+xml"))
        ct.append(part("/ppt/slideLayouts/slideLayout1.xml", "presentationml.slideLayout+xml"))
        ct.append(part("/ppt/theme/theme1.xml", "theme+xml"))
        for (i in deck.slides.indices) ct.append(part("/ppt/slides/slide${i + 1}.xml", "presentationml.slide+xml"))
        if (notesNeeded) {
            ct.append(part("/ppt/notesMasters/notesMaster1.xml", "presentationml.notesMaster+xml"))
            ct.append(part("/ppt/theme/theme2.xml", "theme+xml"))
            for (i in deck.slides.indices) ct.append(part("/ppt/notesSlides/notesSlide${i + 1}.xml", "presentationml.notesSlide+xml"))
        }
        ct.append(part("/docProps/core.xml", "application/vnd.openxmlformats-package.core-properties+xml"))
        ct.append(part("/docProps/app.xml", "application/vnd.openxmlformats-officedocument.extended-properties+xml"))
        ct.append("</Types>")
        entries["[Content_Types].xml"] = str(ct)

        // ---------- _rels/.rels ----------
        entries["_rels/.rels"] = str(
            rels(
                rel("rId1", "$NS_R/officeDocument", "ppt/presentation.xml"),
                rel("rId2", "http://schemas.openxmlformats.org/package/2006/relationships/metadata/core-properties", "docProps/core.xml"),
                rel("rId3", "$NS_R/extended-properties", "docProps/app.xml")
            )
        )

        // ---------- docProps ----------
        entries["docProps/core.xml"] = str(
            "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
                "<cp:coreProperties xmlns:cp=\"http://schemas.openxmlformats.org/package/2006/metadata/core-properties\" " +
                "xmlns:dc=\"http://purl.org/dc/elements/1.1/\" xmlns:dcterms=\"http://purl.org/dc/terms/\" " +
                "xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\">" +
                "<dc:title>" + esc(deck.title) + "</dc:title>" +
                "<dc:creator>" + esc(deck.author) + "</dc:creator>" +
                "<cp:lastModifiedBy>" + esc(deck.author) + "</cp:lastModifiedBy>" +
                "</cp:coreProperties>"
        )
        entries["docProps/app.xml"] = str(
            "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
                "<Properties xmlns=\"http://schemas.openxmlformats.org/officeDocument/2006/extended-properties\" " +
                "xmlns:vt=\"http://schemas.openxmlformats.org/officeDocument/2006/docPropsVTypes\">" +
                "<Application>Planability</Application><Slides>" + deck.slides.size + "</Slides>" +
                "</Properties>"
        )

        // ---------- ppt/presentation.xml（顺序：sldMasterIdLst → notesMasterIdLst → sldIdLst → sldSz → notesSz） ----------
        val presRels = StringBuilder()
        val sb = StringBuilder()
        sb.append(xmlDecl)
        sb.append("<p:presentation xmlns:a=\"").append(NS_A).append("\" xmlns:r=\"").append(NS_R)
            .append("\" xmlns:p=\"").append(NS_P).append("\" saveSubsetFonts=\"1\">")
        sb.append("<p:sldMasterIdLst><p:sldMasterId id=\"2147483648\" r:id=\"rId1\"/></p:sldMasterIdLst>")
        presRels.append(rel("rId1", "$NS_R/slideMaster", "slideMasters/slideMaster1.xml"))
        var nextRid = 2
        if (notesNeeded) {
            sb.append("<p:notesMasterIdLst><p:notesMasterId r:id=\"rId${nextRid}\"/></p:notesMasterIdLst>")
            presRels.append(rel("rId${nextRid}", "$NS_R/notesMaster", "notesMasters/notesMaster1.xml"))
            nextRid++
        }
        sb.append("<p:sldIdLst>")
        deck.slides.indices.forEach { i ->
            sb.append("<p:sldId id=\"").append(256 + i).append("\" r:id=\"rId${nextRid + i}\"/>")
            presRels.append(rel("rId${nextRid + i}", "$NS_R/slide", "slides/slide${i + 1}.xml"))
        }
        sb.append("</p:sldIdLst>")
        sb.append("<p:sldSz cx=\"").append(SLIDE_W).append("\" cy=\"").append(SLIDE_H).append("\"/>")
        sb.append("<p:notesSz cx=\"6858000\" cy=\"9144000\"/>")
        sb.append("</p:presentation>")
        entries["ppt/presentation.xml"] = str(sb)
        entries["ppt/_rels/presentation.xml.rels"] = rels(presRels.toString())

        // ---------- 母版 / 版式 / 主题 ----------
        val bg = th.bgHex
        entries["ppt/slideMasters/slideMaster1.xml"] = str(
            xmlDecl +
                "<p:sldMaster xmlns:a=\"$NS_A\" xmlns:r=\"$NS_R\" xmlns:p=\"$NS_P\">" +
                "<p:cSld>" + solidBg(bg) + emptyTree() + "</p:cSld>" +
                clrMap() +
                "<p:sldLayoutIdLst><p:sldLayoutId id=\"2147483649\" r:id=\"rId1\"/></p:sldLayoutIdLst>" +
                "</p:sldMaster>"
        )
        entries["ppt/slideMasters/_rels/slideMaster1.xml.rels"] = rels(
            rel("rId1", "$NS_R/slideLayout", "../slideLayouts/slideLayout1.xml"),
            rel("rId2", "$NS_R/theme", "../theme/theme1.xml")
        )
        entries["ppt/slideLayouts/slideLayout1.xml"] = str(
            xmlDecl +
                "<p:sldLayout xmlns:a=\"$NS_A\" xmlns:r=\"$NS_R\" xmlns:p=\"$NS_P\" type=\"blank\" preserve=\"1\">" +
                "<p:cSld name=\"空白\">" + emptyTree() + "</p:cSld>" +
                "<p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr>" +
                "</p:sldLayout>"
        )
        entries["ppt/slideLayouts/_rels/slideLayout1.xml.rels"] = rels(
            rel("rId1", "$NS_R/slideMaster", "../slideMasters/slideMaster1.xml")
        )
        entries["ppt/theme/theme1.xml"] = str(themeXml(th.accentHex))
        if (notesNeeded) {
            entries["ppt/notesMasters/notesMaster1.xml"] = str(
                xmlDecl +
                    "<p:notesMaster xmlns:a=\"$NS_A\" xmlns:r=\"$NS_R\" xmlns:p=\"$NS_P\">" +
                    "<p:cSld>" + solidBg("FFFFFF") + emptyTree() + "</p:cSld>" +
                    clrMap() +
                    "<p:notesStyle/>" +
                    "</p:notesMaster>"
            )
            entries["ppt/notesMasters/_rels/notesMaster1.xml.rels"] = rels(
                rel("rId1", "$NS_R/theme", "../theme/theme2.xml")
            )
            entries["ppt/theme/theme2.xml"] = str(themeXml(th.accentHex))
        }

        // ---------- 每页 ----------
        deck.slides.forEachIndexed { i, slide ->
            val pics = slidePictures[i]
            val (slideXml, usedPicCount) = slideXml(pages[i], th, pics)
            entries["ppt/slides/slide${i + 1}.xml"] = str(slideXml)
            val r = StringBuilder()
            r.append(rel("rId1", "$NS_R/slideLayout", "../slideLayouts/slideLayout1.xml"))
            // 图片关系只给"真的嵌进去了"的那些，序号从 rId2 起，与 XML 里 r:embed 对得上
            pics.filterNotNull().forEachIndexed { k, mediaName ->
                r.append(rel("rId${2 + k}", "$NS_R/image", "../media/$mediaName"))
            }
            if (notesNeeded) {
                r.append(rel("rId${2 + usedPicCount}", "$NS_R/notesSlide", "../notesSlides/notesSlide${i + 1}.xml"))
            }
            entries["ppt/slides/_rels/slide${i + 1}.xml.rels"] = rels(r.toString())

            if (notesNeeded) {
                entries["ppt/notesSlides/notesSlide${i + 1}.xml"] = str(notesXml(slide.notes))
                entries["ppt/notesSlides/_rels/notesSlide${i + 1}.xml.rels"] = rels(
                    rel("rId1", "$NS_R/notesMaster", "../notesMasters/notesMaster1.xml"),
                    rel("rId2", "$NS_R/slide", "../slides/slide${i + 1}.xml")
                )
            }
        }

        // ---------- 媒体 ----------
        media.forEach { (name, bytes) -> entries["ppt/media/$name"] = bytes }

        // ---------- 打包 ----------
        zipBytes(out, entries)
        return Report(deck.slides.size, media.size, skipped)
    }

    // ---------------- 单页 XML ----------------

    private var shapeId = 1
    private fun nextId(): Int = ++shapeId

    /**
     * 把一页已排版的 [Page] 序列化成 slide XML。
     *
     * @param pics 与页面里 Picture 图元**一一对应**的媒体文件名（null = 图没读到，已跳过）
     * @return XML 与实际用掉的图片数（决定 notesSlide 关系从哪个 rId 起）
     */
    /**
     * 渲染一页的 slide XML（**无图**：不产出 `r:embed` 与图片关系）。
     *
     * [io.github.m0use001.planability.ai.office.PptxEditor] 改稿时用它整页重写。
     * 那边能这么干，是因为一页 slide 不引用别的页或母版里的资源（图片只经本页 rels，
     * 继承的版式只靠 layout id），所以整页替换不会打乱全局关系表。
     */
    fun slideXmlOf(slide: DocModel.Slide, theme: DocModel.Theme): String =
        slideXml(SlideLayout.page(slide, theme), theme, emptyList()).first

    private fun slideXml(
        page: SlideLayout.Page,
        th: DocModel.Theme,
        pics: List<String?>
    ): Pair<String, Int> {
        shapeId = 1
        val body = StringBuilder()
        var picIdx = 0          // 已成功嵌入的图片数，也就是下一个 r:embed 的序号
        var picItem = 0         // 当前处理到第几个 Picture 图元

        for (item in page.items) {
            when (item) {
                is SlideLayout.Item.Text -> {
                    body.append(
                        textBox(item.x, item.y, item.w, item.h, listOf(item.text), item.sz,
                            item.bold, item.color, align = item.align)
                    )
                }
                is SlideLayout.Item.Bullets -> {
                    body.append(bulletBox(item.x, item.y, item.w, item.h, item.items, item.sz, item.color))
                }
                is SlideLayout.Item.Table -> {
                    body.append(tableXml(item.x, item.y, item.w, item.h, item.rows, th))
                }
                is SlideLayout.Item.Picture -> {
                    // 取不到图的图元直接不画：留一个指向不存在媒体的 r:embed 会让整份文件
                    // 被 PowerPoint 判为"需要修复"，代价远大于少一张图
                    val mediaName = pics.getOrNull(picItem)
                    picItem++
                    if (mediaName == null) continue
                    body.append(picture(item.x, item.y, item.w, item.h, "rId${2 + picIdx}", item.caption))
                    picIdx++
                }
            }
        }

        val xml = xmlDecl +
            "<p:sld xmlns:a=\"$NS_A\" xmlns:r=\"$NS_R\" xmlns:p=\"$NS_P\">" +
            "<p:cSld>" + solidBg(th.bgHex) + "<p:spTree>" + treeHead() + body + "</p:spTree></p:cSld>" +
            "<p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr>" +
            "</p:sld>"
        return xml to picIdx
    }

    private fun textBox(
        x: Long, y: Long, w: Long, h: Long,
        lines: List<String>, sz: Int, bold: Boolean, color: String,
        align: String = "l"
    ): String {
        val paras = lines.joinToString("") { line ->
            "<a:p><a:pPr algn=\"$align\"/><a:r><a:rPr lang=\"zh-CN\" altLang=\"en-US\" sz=\"$sz\"" +
                (if (bold) " b=\"1\"" else "") + " dirty=\"0\">" +
                "<a:solidFill><a:srgbClr val=\"$color\"/></a:solidFill>" +
                "<a:latin typeface=\"+mn-lt\"/><a:ea typeface=\"+mn-ea\"/></a:rPr>" +
                "<a:t>" + esc(line) + "</a:t></a:r></a:p>"
        }
        return "<p:sp><p:nvSpPr><p:cNvPr id=\"${nextId()}\" name=\"文本 ${nextId()}\"/>" +
            "<p:cNvSpPr txBox=\"1\"/><p:nvPr/></p:nvSpPr>" +
            "<p:spPr><a:xfrm><a:off x=\"$x\" y=\"$y\"/><a:ext cx=\"$w\" cy=\"$h\"/></a:xfrm>" +
            "<a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom><a:noFill/></p:spPr>" +
            "<p:txBody><a:bodyPr wrap=\"square\" lIns=\"0\" tIns=\"0\" rIns=\"0\" bIns=\"0\"><a:normAutofit/></a:bodyPr>" +
            "<a:lstStyle/>$paras</p:txBody></p:sp>"
    }

    private fun bulletBox(x: Long, y: Long, w: Long, h: Long, items: List<String>, sz: Int, color: String): String {
        val paras = items.joinToString("") { item ->
            "<a:p><a:pPr marL=\"285750\" indent=\"-285750\"><a:buChar char=\"•\"/></a:pPr>" +
                "<a:r><a:rPr lang=\"zh-CN\" altLang=\"en-US\" sz=\"$sz\" dirty=\"0\">" +
                "<a:solidFill><a:srgbClr val=\"$color\"/></a:solidFill>" +
                "<a:latin typeface=\"+mn-lt\"/><a:ea typeface=\"+mn-ea\"/></a:rPr>" +
                "<a:t>" + esc(item) + "</a:t></a:r></a:p>"
        }
        return "<p:sp><p:nvSpPr><p:cNvPr id=\"${nextId()}\" name=\"要点 ${nextId()}\"/>" +
            "<p:cNvSpPr txBox=\"1\"/><p:nvPr/></p:nvSpPr>" +
            "<p:spPr><a:xfrm><a:off x=\"$x\" y=\"$y\"/><a:ext cx=\"$w\" cy=\"$h\"/></a:xfrm>" +
            "<a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom><a:noFill/></p:spPr>" +
            "<p:txBody><a:bodyPr wrap=\"square\" lIns=\"0\" tIns=\"0\" rIns=\"0\" bIns=\"0\"><a:normAutofit/></a:bodyPr>" +
            "<a:lstStyle/>$paras</p:txBody></p:sp>"
    }

    private fun picture(x: Long, y: Long, w: Long, h: Long, embedId: String, caption: String): String {
        val cap = if (caption.isBlank()) "" else
            "<p:sp><p:nvSpPr><p:cNvPr id=\"${nextId()}\" name=\"图注\"/><p:cNvSpPr/><p:nvPr/></p:nvSpPr>" +
                "<p:spPr><a:xfrm><a:off x=\"$x\" y=\"${y + h + DocModel.Geometry.CAPTION_OFFSET}\"/><a:ext cx=\"$w\" cy=\"320000\"/></a:xfrm>" +
                "<a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom><a:noFill/></p:spPr>" +
                "<p:txBody><a:bodyPr wrap=\"square\"/><a:lstStyle/><a:p><a:r><a:rPr lang=\"zh-CN\" sz=\"1200\">" +
                "<a:solidFill><a:srgbClr val=\"808080\"/></a:solidFill></a:rPr><a:t>" + esc(caption) +
                "</a:t></a:r></a:p></p:txBody></p:sp>"
        return "<p:pic><p:nvPicPr><p:cNvPr id=\"${nextId()}\" name=\"图片 ${nextId()}\"/>" +
            "<p:cNvPicPr><a:picLocks noChangeAspect=\"1\"/></p:cNvPicPr><p:nvPr/></p:nvPicPr>" +
            "<p:blipFill><a:blip r:embed=\"$embedId\"/><a:stretch><a:fillRect/></a:stretch></p:blipFill>" +
            "<p:spPr><a:xfrm><a:off x=\"$x\" y=\"$y\"/><a:ext cx=\"$w\" cy=\"$h\"/></a:xfrm>" +
            "<a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom></p:spPr></p:pic>" + cap
    }

    /**
     * 表格：真 `a:tbl`（graphicFrame），**不是**一堆画出来的格子。
     *
     * 这点很要紧：拼图形格子在 PowerPoint 里"看着像表格"，但用户没法选中整张表、不能改行列、
     * 不能套表格样式，AI 改稿时也无从下手——那只是"看着像"。真表格能被选中、编辑、换样式，
     * python-pptx 也能认出来（`shape.has_table`），这轮验收就是靠它确认表格真的成了。
     */
    private fun tableXml(x: Long, y: Long, w: Long, h: Long, rows: List<List<String>>, th: DocModel.Theme): String {
        val cols = rows.maxOf { it.size }.coerceAtLeast(1)
        val colW = w / cols
        val rowH = h / rows.size.coerceAtLeast(1)
        val grid = (0 until cols).joinToString("") { "<a:gridCol w=\"$colW\"/>" }
        val trs = rows.mapIndexed { ri, row ->
            val cells = (0 until cols).joinToString("") { ci ->
                val v = row.getOrElse(ci) { "" }
                val isHeader = ri == 0
                val fill = if (isHeader) th.accentHex else "FFFFFF"
                val fg = if (isHeader) "FFFFFF" else th.fgHex
                "<a:tc><a:txBody><a:bodyPr/><a:lstStyle/><a:p><a:r>" +
                    "<a:rPr lang=\"zh-CN\" altLang=\"en-US\" sz=\"${DocModel.Geometry.TABLE_SZ}\"" + (if (isHeader) " b=\"1\"" else "") + ">" +
                    "<a:solidFill><a:srgbClr val=\"$fg\"/></a:solidFill></a:rPr>" +
                    "<a:t>" + esc(v) + "</a:t></a:r></a:p></a:txBody>" +
                    "<a:tcPr marL=\"45720\" marR=\"45720\" marT=\"22860\" marB=\"22860\" anchor=\"ctr\">" +
                    "<a:lnB w=\"12700\"><a:solidFill><a:srgbClr val=\"D0D4DC\"/></a:solidFill></a:lnB>" +
                    "<a:solidFill><a:srgbClr val=\"$fill\"/></a:solidFill></a:tcPr></a:tc>"
            }
            "<a:tr h=\"$rowH\">$cells</a:tr>"
        }.joinToString("")
        return "<p:graphicFrame><p:nvGraphicFramePr>" +
            "<p:cNvPr id=\"${nextId()}\" name=\"表格 ${nextId()}\"/>" +
            "<p:cNvGraphicFramePr><a:graphicFrameLocks noGrp=\"1\"/></p:cNvGraphicFramePr><p:nvPr/>" +
            "</p:nvGraphicFramePr>" +
            "<p:xfrm><a:off x=\"$x\" y=\"$y\"/><a:ext cx=\"$w\" cy=\"$h\"/></p:xfrm>" +
            "<a:graphic><a:graphicData uri=\"http://schemas.openxmlformats.org/drawingml/2006/table\">" +
            "<a:tbl><a:tblPr firstRow=\"1\"/><a:tblGrid>$grid</a:tblGrid>$trs</a:tbl>" +
            "</a:graphicData></a:graphic></p:graphicFrame>"
    }

    private fun notesXml(text: String): String = xmlDecl +
        "<p:notes xmlns:a=\"$NS_A\" xmlns:r=\"$NS_R\" xmlns:p=\"$NS_P\">" +
        "<p:cSld><p:spTree>" + treeHead() +
        "<p:sp><p:nvSpPr><p:cNvPr id=\"2\" name=\"备注\"/><p:cNvSpPr txBox=\"1\"/>" +
        "<p:nvPr><p:ph type=\"body\" idx=\"1\"/></p:nvPr></p:nvSpPr>" +
        "<p:spPr><a:xfrm><a:off x=\"685800\" y=\"4343400\"/><a:ext cx=\"5486400\" cy=\"4114800\"/></a:xfrm>" +
        "<a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom><a:noFill/></p:spPr>" +
        "<p:txBody><a:bodyPr wrap=\"square\"/><a:lstStyle/><a:p><a:r><a:rPr lang=\"zh-CN\" sz=\"1200\"/>" +
        "<a:t>" + esc(text) + "</a:t></a:r></a:p></p:txBody></p:sp>" +
        "</p:spTree></p:cSld><p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr></p:notes>"

    // ---------------- 公共片段 ----------------

    private const val xmlDecl = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"

    private fun str(s: String): ByteArray = s.toByteArray(Charsets.UTF_8)
    private fun str(b: ByteArray): ByteArray = b

    private fun str(s: StringBuilder): ByteArray = s.toString().toByteArray(Charsets.UTF_8)

    private fun solidBg(color: String): String =
        "<p:bg><p:bgPr><a:solidFill><a:srgbClr val=\"$color\"/></a:solidFill><a:effectLst/></p:bgPr></p:bg>"

    private fun treeHead(): String =
        "<p:nvGrpSpPr><p:cNvPr id=\"1\" name=\"\"/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr>" +
            "<p:grpSpPr><a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"0\" cy=\"0\"/>" +
            "<a:chOff x=\"0\" y=\"0\"/><a:chExt cx=\"0\" cy=\"0\"/></a:xfrm></p:grpSpPr>"

    private fun emptyTree(): String = "<p:spTree>" + treeHead() + "</p:spTree>"

    private fun clrMap(): String = "<p:clrMap bg1=\"lt1\" tx1=\"dk1\" bg2=\"lt2\" tx2=\"dk2\" " +
        "accent1=\"accent1\" accent2=\"accent2\" accent3=\"accent3\" accent4=\"accent4\" " +
        "accent5=\"accent5\" accent6=\"accent6\" hlink=\"hlink\" folHlink=\"folHlink\"/>"

    private fun part(name: String, ct: String): String =
        "<Override PartName=\"$name\" ContentType=\"application/vnd.openxmlformats-officedocument.$ct\"/>"

    private fun rel(id: String, type: String, target: String): String =
        "<Relationship Id=\"$id\" Type=\"$type\" Target=\"$target\"/>"

    /** 关系文件：多个 Relationship 片段拼成一个 .rels。 */
    private fun rels(vararg parts: String): ByteArray = StringBuilder()
        .append(xmlDecl)
        .append("<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">")
        .apply { parts.forEach { append(it) } }
        .append("</Relationships>")
        .toString().toByteArray(Charsets.UTF_8)

    /** 主题：clrScheme / fontScheme / fmtScheme 三件套齐全才算合法，缺一 PowerPoint 就修复。 */
    private fun themeXml(accent: String): String = xmlDecl +
        "<a:theme xmlns:a=\"$NS_A\" name=\"Planability\">" +
        "<a:themeElements>" +
        "<a:clrScheme name=\"Planability\">" +
        "<a:dk1><a:sysClr val=\"windowText\" lastClr=\"000000\"/></a:dk1>" +
        "<a:lt1><a:sysClr val=\"window\" lastClr=\"FFFFFF\"/></a:lt1>" +
        "<a:dk2><a:srgbClr val=\"1A1C22\"/></a:dk2><a:lt2><a:srgbClr val=\"F2F4F8\"/></a:lt2>" +
        "<a:accent1><a:srgbClr val=\"$accent\"/></a:accent1>" +
        "<a:accent2><a:srgbClr val=\"2FB67A\"/></a:accent2>" +
        "<a:accent3><a:srgbClr val=\"F2A93B\"/></a:accent3>" +
        "<a:accent4><a:srgbClr val=\"E0453A\"/></a:accent4>" +
        "<a:accent5><a:srgbClr val=\"8A5CF6\"/></a:accent5>" +
        "<a:accent6><a:srgbClr val=\"12B0C9\"/></a:accent6>" +
        "<a:hlink><a:srgbClr val=\"1A6BE0\"/></a:hlink>" +
        "<a:folHlink><a:srgbClr val=\"8A5CF6\"/></a:folHlink>" +
        "</a:clrScheme>" +
        "<a:fontScheme name=\"Planability\">" +
        "<a:majorFont><a:latin typeface=\"+mj-lt\"/><a:ea typeface=\"+mj-ea\"/><a:cs typeface=\"\"/></a:majorFont>" +
        "<a:minorFont><a:latin typeface=\"+mn-lt\"/><a:ea typeface=\"+mn-ea\"/><a:cs typeface=\"\"/></a:minorFont>" +
        "</a:fontScheme>" +
        "<a:fmtScheme name=\"Planability\">" +
        "<a:fillStyleLst>" +
        "<a:solidFill><a:schemeClr val=\"phClr\"/></a:solidFill>" +
        "<a:solidFill><a:schemeClr val=\"phClr\"/></a:solidFill>" +
        "<a:solidFill><a:schemeClr val=\"phClr\"/></a:solidFill>" +
        "</a:fillStyleLst>" +
        "<a:lnStyleLst>" +
        "<a:ln w=\"6350\" cap=\"flat\" cmpd=\"sng\" algn=\"ctr\"><a:solidFill><a:schemeClr val=\"phClr\"/></a:solidFill>" +
        "<a:prstDash val=\"solid\"/></a:ln>" +
        "<a:ln w=\"12700\" cap=\"flat\" cmpd=\"sng\" algn=\"ctr\"><a:solidFill><a:schemeClr val=\"phClr\"/></a:solidFill>" +
        "<a:prstDash val=\"solid\"/></a:ln>" +
        "<a:ln w=\"19050\" cap=\"flat\" cmpd=\"sng\" algn=\"ctr\"><a:solidFill><a:schemeClr val=\"phClr\"/></a:solidFill>" +
        "<a:prstDash val=\"solid\"/></a:ln>" +
        "</a:lnStyleLst>" +
        "<a:effectStyleLst>" +
        "<a:effectStyle><a:effectLst/></a:effectStyle>" +
        "<a:effectStyle><a:effectLst/></a:effectStyle>" +
        "<a:effectStyle><a:effectLst/></a:effectStyle>" +
        "</a:effectStyleLst>" +
        "<a:bgFillStyleLst>" +
        "<a:solidFill><a:schemeClr val=\"phClr\"/></a:solidFill>" +
        "<a:solidFill><a:schemeClr val=\"phClr\"/></a:solidFill>" +
        "<a:solidFill><a:schemeClr val=\"phClr\"/></a:solidFill>" +
        "</a:bgFillStyleLst>" +
        "</a:fmtScheme>" +
        "</a:themeElements>" +
        "</a:theme>"

    // ---------------- 图片与打包 ----------------

    /** 取本地文件或 http(s) 图片；失败返回 null（调用方跳过该图）。 */
    private fun loadPicture(pathOrUrl: String): Pair<String, ByteArray>? {
        val lower = pathOrUrl.lowercase()
        val ext = when {
            lower.endsWith(".png") -> "png"
            lower.endsWith(".jpg") || lower.endsWith(".jpeg") -> "jpeg"
            lower.startsWith("http") -> "png"      // 网络图不猜类型，按 png 存；解码失败就是那张图不显示
            else -> return null
        }
        val bytes = if (lower.startsWith("http")) {
            runCatching {
                val conn = URL(pathOrUrl).openConnection() as HttpURLConnection
                conn.connectTimeout = 10_000
                conn.readTimeout = 15_000
                conn.setRequestProperty("User-Agent", "Planability/1.0 (Android)")
                conn.inputStream.use { it.readBytes() }
            }.getOrNull()
        } else {
            runCatching { File(pathOrUrl).takeIf { it.exists() }?.readBytes() }.getOrNull()
        }
        if (bytes == null || bytes.isEmpty()) return null
        return ext to bytes
    }

    private fun zipBytes(out: File, entries: Map<String, ByteArray>) {
        ZipOutputStream(FileOutputStream(out)).use { zos ->
            entries.forEach { (name, data) ->
                zos.putNextEntry(ZipEntry(name))
                zos.write(data)
                zos.closeEntry()
            }
        }
    }
}
