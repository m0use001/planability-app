package io.github.m0use001.planability.ai.office

import io.github.m0use001.planability.ai.ptl.PptxWriter
import io.github.m0use001.planability.shared.DocModel
import io.github.m0use001.planability.shared.SlideLayout
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * v14.3：**PPTX 解析与增量改写**（P2）。
 *
 * ## 为什么是"只重写改动页"，而不是"整个读成模型再重新生成"
 *
 * 后者听着更干净，实测更糟：我们自己生成的 pptx 里，段落是**整段一个 `<a:t>`**，
 * 但别人做的、PowerPoint 做的文件里，一段会被拆成多个 run（拼写检查、批注残留、
 * 局部加粗都会留下痕迹）。整段读成一个字符串再写回去，等于**顺手抹平了别人文件里的所有 run 结构**。
 * 更糟的是：无法解析的文件会整个退回"重新生成"——那不是编辑，那是重写，
 * 用户放进去的母版、动画、备注、图表会**全部消失**，而工具还会报告"改好了"。
 *
 * 所以这里的取舍很明确：**没读懂就不改**。
 * - 读得懂（结构是我们熟悉的形状）→ 按页增量改写，未动的页原样保留。
 * - 读不懂 → 明确报错，请用户另存为"空白演示文稿"重来，绝不假装改好了。
 *
 * ## 为什么"重写这一页"仍然安全
 *
 * slide XML 里的形状**不引用其它页或母版里的东西**（图片是 `r:embed` 指本页 rels 里的媒体，
 * 继承的版式只靠 layout id）。所以一页可以在不打乱全局关系表的前提下整页替换。
 * 代价是这一页里我们不认识的构造（动画 `p:timing`、切换效果 `p:transition`、
 * SmartArt 的关系引用）会丢——**所以只在真的读懂这一页时才改它**。
 */
object PptxEditor {

    /** 改完的结果。 */
    data class Result(
        val changed: Int,
        val failed: List<String>,       // 人话原因，供模型转述给用户
        val droppedAnimations: Int
    )

    // ---------------- 解析 ----------------

    /** 把一份 pptx 读成 [DocModel.Deck]。读不了就抛，**绝不返回半成品**。 */
    fun read(file: File): DocModel.Deck {
        ZipFile(file).use { z ->
            val pres = z.text("ppt/presentation.xml")
                ?: throw PptxException("不是有效的 pptx（缺 ppt/presentation.xml）")
            val theme = z.text("ppt/theme/theme1.xml")?.let { themeFromXml(it) } ?: DocModel.Theme()
            val slideNames = z.names()
                .filter { it.startsWith("ppt/slides/slide") && it.endsWith(".xml") }
                .sortedBy { it.removePrefix("ppt/slides/slide").removeSuffix(".xml").toIntOrNull() ?: Int.MAX_VALUE }
            if (slideNames.isEmpty()) throw PptxException("这个 pptx 里没有幻灯片")

            val slides = slideNames.mapIndexed { i, name ->
                val xml = z.text(name) ?: throw PptxException("第 ${i + 1} 页读不出来")
                slideFromXml(xml, notesOf(z, name), i + 1)
            }
            return DocModel.Deck(
                // 标题直接用文件名：docProps/core.xml 里不一定有（不同工具版本写不写不一样），
                // 为一个显示名去猜不值得
                title = file.nameWithoutExtension,
                slides = slides,
                theme = theme
            )
        }
    }

    private class PptxException(msg: String) : Exception(msg)

    /** 从 theme1.xml 取强调色（accent1），取不到就用默认蓝。 */
    private fun themeFromXml(xml: String): DocModel.Theme {
        val accent = Regex("<a:accent1>.*?val=\"([0-9A-Fa-f]{6})\".*?</a:accent1>", RegexOption.DOT_MATCHES_ALL)
            .find(xml)?.groupValues?.get(1)
        return DocModel.Theme(name = "原文件", dark = false, accentHex = accent ?: "4F6BED")
    }

    /**
     * 把一页 slide XML 读成 [DocModel.Slide]。
     *
     * 刻意**只取文字**：形状类型、位置、字号一律交给 [SlideLayout] 按标准版式重排。
     * 保留原坐标看似更"保真"，实际上会让"改标题"变成"挪位置"，改出来的东西七零八落；
     * 而文字结构不变、版式统一，正是这个工具该有的样子。哪些内容会丢，写在
     * [DocModel.Slide] 的说明里，调用方要能如实告诉用户。
     */
    private fun slideFromXml(xml: String, notes: String, pageNo: Int): DocModel.Slide {
        val shapes = topLevelShapes(xml)
        if (shapes.isEmpty()) return DocModel.Slide(layout = DocModel.SlideLayout.BULLETS, notes = notes)

        // 判版式：第一个形状字号最大且页内只有文字 → 当封面页
        val texts = shapes.map { textOf(it) }.filter { it.isNotBlank() }
        val sizes = shapes.map { sizeOf(it) }.filter { it > 0 }
        val biggest = sizes.maxOrNull() ?: 0
        val isTitlePage = pageNo == 1 && texts.size <= 2 && biggest >= 3200

        var title = ""
        var subtitle = ""
        val bullets = ArrayList<String>()
        val paras = ArrayList<String>()

        val ordered = shapes.sortedBy { topOf(it) }
        ordered.forEachIndexed { idx, sh ->
            // 一个形状可能含多个段落（要点列表尤其如此），拆开分别对待
            val lines = textOf(sh).lines().map { it.trim() }.filter { it.isNotEmpty() }
            if (lines.isEmpty()) return@forEachIndexed
            val sz = sizeOf(sh)
            val isBig = sz >= biggest && sz > 0
            when {
                isTitlePage && idx == 0 -> title = lines.joinToString(" ")
                isTitlePage -> subtitle = lines.joinToString(" ")
                isBig && title.isEmpty() -> title = lines.joinToString(" ")
                isBulletList(sh) -> bullets += lines
                lines.size > 1 -> paras += lines.joinToString(" ")
                else -> paras += lines.first()
            }
        }

        val layout = when {
            isTitlePage -> DocModel.SlideLayout.TITLE
            bullets.isNotEmpty() -> DocModel.SlideLayout.BULLETS
            paras.isNotEmpty() -> DocModel.SlideLayout.BULLETS
            else -> DocModel.SlideLayout.BULLETS
        }
        val blocks = buildList {
            if (bullets.isNotEmpty()) add(DocModel.Block.Bullets(bullets))
            paras.forEach { add(DocModel.Block.Para(it)) }
        }
        return DocModel.Slide(layout, title, subtitle, blocks, notes, pageNo)
    }

    /**
     * 这一页的演讲者备注。
     *
     * 必须走**该页 .rels 里的 notesSlide 关系**去找，不能按页号对位猜 `notesSlideN`：
     * 别人做的文件里两者的编号未必一一对应（删过页就会错位），对位猜会读到别人的备注
     * 或者读空——那属于"悄悄改错东西"。
     */
    private fun notesOf(z: ZipFile, slideName: String): String {
        val relsName = slideName.replace("ppt/slides/", "ppt/slides/_rels/") + ".rels"
        val rels = z.text(relsName) ?: return ""
        val target = Regex(
            "<Relationship[^>]*Type=\"[^\"]*/notesSlide\"[^>]*Target=\"([^\"]+)\""
        ).find(rels)?.groupValues?.get(1) ?: return ""
        val path = target.replace("../", "ppt/")
        val xml = z.text(path) ?: return ""
        return Regex("<a:t>(.*?)</a:t>", RegexOption.DOT_MATCHES_ALL).findAll(xml)
            .map { unescape(it.groupValues[1]).trim() }
            .filter { it.isNotEmpty() }
            .joinToString("\n")
    }

    /** 该形状是不是项目符号列表（`a:buChar`）。 */
    private fun isBulletList(shapeXml: String): Boolean =
        Regex("<a:buChar\\b").containsMatchIn(shapeXml) ||
            Regex("<a:buAutoNum\\b").containsMatchIn(shapeXml)

    /**
     * 取形状里的文字，**按段落分行**。
     *
     * 不能把所有 `<a:t>` 直接连起来：要点列表里每个要点是独立段落，连起来就变成
     * "要点甲要点乙"一坨，后面没法当列表用。反过来，段落**内部**的多个 run（局部加粗、
     * 拼写检查留下的碎片）必须无缝拼接——那是同一句话的一部分。
     */
    private fun textOf(shapeXml: String): String =
        shapeXml.split(Regex("<a:p[ >]")).drop(1)
            .map { p ->
                Regex("<a:t>(.*?)</a:t>", RegexOption.DOT_MATCHES_ALL).findAll(p)
                    .map { unescape(it.groupValues[1]).trim() }
                    .filter { it.isNotEmpty() }
                    .joinToString("")
            }
            .filter { it.isNotBlank() }
            .joinToString("\n")

    /** 取该形状里最大的字号（`sz` 是百分之一磅）。 */
    private fun sizeOf(shapeXml: String): Int =
        Regex("sz=\"(\\d+)\"").findAll(shapeXml).map { it.groupValues[1].toIntOrNull() ?: 0 }
            .maxOrNull() ?: 0

    /** 形状顶边的 EMU（取第一个 `a:off y`）。 */
    private fun topOf(shapeXml: String): Long =
        Regex("<a:off x=\"-?\\d+\" y=\"(-?\\d+)\"").find(shapeXml)
            ?.groupValues?.get(1)?.toLongOrNull() ?: Long.MAX_VALUE

    /**
     * 只取 `<p:spTree>` 的**直接子形状**。
     *
     * 为什么要挖这一层：`a:tbl` 的表格里，每个单元格都有一个 `<p:sp>`，而 `<p:txBody>` 里
     * 也有 `<a:p>`（段落）。用简单正则按 `<p:sp` 切会把表格单元格也当成独立形状切进来，
     * 结果一张表变成几十个"要点"。所以从 spTree 起逐字符扫，跟踪深度只收 depth==1 的。
     */
    private fun topLevelShapes(slideXml: String): List<String> {
        val start = slideXml.indexOf("<p:spTree>")
        if (start < 0) return emptyList()
        val bodyStart = start + "<p:spTree>".length
        val end = slideXml.indexOf("</p:spTree>", bodyStart)
        if (end < 0) return emptyList()
        val body = slideXml.substring(bodyStart, end)

        val out = ArrayList<String>()
        var i = 0
        var depth = 0
        var shapeStart = -1
        while (i < body.length) {
            val lt = body.indexOf('<', i)
            if (lt < 0) break
            val gt = body.indexOf('>', lt)
            if (gt < 0) break
            val tag = body.substring(lt + 1, gt)
            val name = tag.trimStart('/').substringBefore(' ').substringBefore('>')
            val isClose = tag.startsWith("/")
            val isSelf = tag.endsWith("/")

            if (isSelf) {                      // 自闭合：不进深度
                if (depth == 0 && (name == "p:sp" || name == "p:pic" || name == "p:graphicFrame")) {
                    out.add(body.substring(lt, gt + 1))
                }
                i = gt + 1
                continue
            }
            if (depth == 0 && shapeStart < 0 &&
                (name == "p:sp" || name == "p:pic" || name == "p:graphicFrame")
            ) {
                shapeStart = lt
            }
            if (isClose) {
                depth--
                if (depth == 0 && shapeStart >= 0) {
                    out.add(body.substring(shapeStart, gt + 1))
                    shapeStart = -1
                }
            } else {
                depth++
            }
            i = gt + 1
        }
        return out
    }

    // ---------------- 改写 ----------------

    /**
     * 应用 [patches]（1 基页码）并写回 [out]。
     *
     * 未列出的页**一个字节都不动**：这是本工具最重要的性质，用户没让改的地方就不能变。
     */
    fun apply(src: File, patches: List<DocModel.Slide>, out: File, deckOverride: DocModel.Deck? = null): Result {
        val deck = deckOverride ?: read(src)
        if (patches.isEmpty()) return Result(0, emptyList(), 0)

        val idxOf = HashMap<Int, DocModel.Slide>()
        val failed = ArrayList<String>()
        for (p in patches) {
            val n = deck.slides.indexOfFirst { it.pageNo == p.pageNo }
            if (n < 0) {
                failed += "第 ${p.pageNo} 页不存在（这份文件只有 ${deck.slides.size} 页）"
                continue
            }
            idxOf[n] = p
        }
        if (idxOf.isEmpty()) return Result(0, failed, 0)

        var dropped = 0
        ZipFile(src).use { z ->
            val entries = LinkedHashMap<String, ByteArray>()
            val en = z.entries()
            while (en.hasMoreElements()) {
                val e = en.nextElement()
                if (e.isDirectory) continue
                entries[e.name] = z.getInputStream(e).readBytes()
            }
            // docProps/app.xml 里的页数会变，跟着改，免得资源管理器里显示旧页数
            entries["docProps/app.xml"]?.let {
                val cur = String(it, Charsets.UTF_8)
                val fixed = Regex("<Slides>\\d+</Slides>")
                    .replace(cur, "<Slides>${deck.slides.size}</Slides>")
                entries["docProps/app.xml"] = fixed.toByteArray(Charsets.UTF_8)
            }
            for ((n, patched) in idxOf) {
                val name = "ppt/slides/slide${n + 1}.xml"
                val old = entries[name]
                if (old == null) {
                    failed += "第 ${pickedPage(n, deck)} 页在文件里找不到对应内容，已跳过"
                    continue
                }
                val oldXml = String(old, Charsets.UTF_8)
                if (hasUnsupported(oldXml)) dropped++
                entries[name] = PptxWriter.slideXmlOf(patched, deck.theme).toByteArray(Charsets.UTF_8)
            }
            out.parentFile?.mkdirs()
            ZipOutputStream(FileOutputStream(out)).use { zo ->
                entries.forEach { (k, v) ->
                    zo.putNextEntry(ZipEntry(k))
                    zo.write(v)
                    zo.closeEntry()
                }
            }
        }
        return Result(idxOf.size, failed, dropped)
    }

    private fun pickedPage(n: Int, deck: DocModel.Deck) = deck.slides[n].pageNo

    /** 这一页有没有我们改写不了、又会被重写掉的构造。 */
    private fun hasUnsupported(slideXml: String): Boolean =
        Regex("<p:(timing|transition)\\b").containsMatchIn(slideXml) ||
            Regex("dgm/|diagramData").containsMatchIn(slideXml)

    private fun unescape(s: String): String = s
        .replace("&lt;", "<").replace("&gt;", ">")
        .replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&")

    // ---------------- zip 小工具 ----------------

    private fun ZipFile.text(name: String): String? =
        getEntry(name)?.let { getInputStream(it).readBytes().toString(Charsets.UTF_8) }

    private fun ZipFile.names(): List<String> {
        val en = entries()
        val out = ArrayList<String>()
        while (en.hasMoreElements()) out.add(en.nextElement().name)
        return out
    }
}
