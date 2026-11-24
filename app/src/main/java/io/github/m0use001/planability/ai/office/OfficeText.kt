package io.github.m0use001.planability.ai.office

import java.io.File
import java.util.zip.ZipFile

/**
 * v14.3：**办公文件的纯文本抽取**（docx / xlsx / pptx / csv / 文本类）。
 *
 * ## 定位：不是编辑器，是"让人看见里面写了什么"
 *
 * 手机上没有任何能真渲染 office 版式的引擎，所以对 docx/xlsx 这类我们**只做文字预览**，
 * 不假装能还原排版。这是有意的取舍：给一个半真的排版预览，会让人以为"文件排版没问题"，
 * 而实际上那只是我们瞎猜的。
 *
 * ## 为什么和 `FilePreview` 里已有的那份重复
 *
 * 文件页那边已经有一套 zip+XML 解析（在 `ui/files/FilePreview.kt` 里，属 B 线文件）。
 * 这里另写一份是为了**不越线改对方的文件**，代价是有一段重复。更好的做法是文件页将来
 * 改为调用这里（已在 `agents_log/phone-a.md` 里 assign 出去），届时这份可以删掉。
 * 在那之前，两边互不依赖比"抽成公共方法但要跨线协调"更稳。
 *
 * ## 抽取方式：正则扫 `a:t` / `w:t`
 *
 * 不用 XML 解析器，因为这里要的只是"把文字捞出来给人看"，而正文里的文字节点
 * 无论嵌在什么结构里，最终都落在 `<a:t>` / `<w:t>` 上。正则一行能解决，还不会有
 * 解析器在不同 Android 版本上行为不一致的问题。代价是：如果正文里恰好有转义的
 * 尖括号，会被原样显示——对预览来说无所谓。
 */
object OfficeText {

    /** 读一个文件的内容预览；失败时抛异常由调用方兜底。 */
    fun read(f: File): String = when (f.extension.lowercase()) {
        "docx" -> fromDocx(f)
        "pptx" -> fromPptx(f)
        "ppt" -> fromPpt(f)      // 老版二进制 PPT（OLE2）
        "xlsx" -> fromXlsx(f)
        "pdf" -> "[PDF 文档]" + f.length() / 1024 + " KB（本机只支持翻页渲染，暂不抽文字）"
        else -> f.readText()   // csv / md / txt / json 等
    }

    /**
     * 老版 `.ppt`（PowerPoint 97-2003）：OLE2 复合文档里的「PowerPoint Document」
     * 记录流按 Slide 容器逐页抽文字。解析细节与边界见 [PptBinary]。
     *
     * 失败（加密文档 / 文件损坏）返回一句人话而不是抛——"读不了"也是信息，
     * 不该让整条读取链路炸掉。
     */
    fun fromPpt(f: File, withIndex: Boolean = false): String {
        val slides = runCatching { PptBinary.extract(f) }
            .getOrElse { return "[读不了 .ppt：" + (it.message ?: "未知原因") + "]" }
        if (slides.isEmpty()) return "[没有找到幻灯片文字]"
        val body = slides.mapIndexed { i, s ->
            val head = if (withIndex) "[第 ${i + 1} 页]" else "—— 第 ${i + 1} 页 ——"
            val text = if (withIndex) s.texts.mapIndexed { k, t -> "[S$i.$k] $t" }.joinToString("\n")
            else s.texts.joinToString("\n")
            "$head\n$text"
        }.joinToString("\n\n")
        // 2026-11-12 用户「PPT 还是读不了，只能读到标题」：这类数学课件的正文是
        // WMF 矢量图（定理/例题公式），文本流里只有页眉标题。矢量图里其实带真实字符，
        // 按坐标还原后作为附录附上——标注来源，不假装是页内文字（页归属拿不到）。
        val picTexts = runCatching { PptBinary.pictureTexts(f) }.getOrDefault(emptyList())
        if (picTexts.isEmpty()) return body
        return body + "\n\n—— 课件里的矢量图文字（正文公式/定理多为图片，以下按图片顺序从图中还原，可能有错序）——\n" +
            picTexts.mapIndexed { i, t -> "图 ${i + 1}：" + t }.joinToString("\n")
    }

    /**
     * docx：按**段落序号**编号输出（`[P1]`、`[P2]`…，**1 基**）。
     *
     * 为什么带编号：改稿（P2）要按段定位，模型得能指认"第 3 段"。只给纯文本的话，
     * 模型只能猜位置，一改就错位。编号只出现在预览里，不写回文件。
     *
     * **必须是 1 基**，与 `file_edit_document` 的"index 从 1 开始"一致。
     * 这里原来打成 `[P0]`、`[P1]`…，真机验收时后果很具体：让人改第 3 段
     * （读到的是 `[P2]`），模型按 1 基理解传了 index=2，结果改掉的是**第 2 段**——
     * 工具报告成功，文件也能打开，只是改错了地方。这类"看起来成功"的错误最危险。
     */
    fun fromDocx(f: File, withIndex: Boolean = false): String =
        textOf(f, "word/document.xml") { xml ->
            xml.split(Regex("<w:p[ >]")).drop(1).mapIndexed { i, p ->
                val text = tags(p, "w:t", preserveWhitespace = true).joinToString("")
                if (withIndex) "[P${i + 1}] $text" else text
            }.joinToString("\n")
        }

    /** pptx：按 `[第 N 页]` 分段，页内每段一行。 */
    fun fromPptx(f: File, withIndex: Boolean = false): String {
        val slides = slideNames(f)
        if (slides.isEmpty()) return "[没有找到幻灯片]"
        return slides.mapIndexed { i, name ->
            val head = if (withIndex) "[第 ${i + 1} 页]" else "—— 第 ${i + 1} 页 ——"
            val body = textOf(f, name) { xml ->
                if (!withIndex) tags(xml, "a:t", preserveWhitespace = true).joinToString("\n")
                else tags(xml, "a:t", preserveWhitespace = true).mapIndexed { k, t -> "[S$i.$k] $t" }.joinToString("\n")
            }
            "$head\n$body"
        }.joinToString("\n\n")
    }

    /**
     * xlsx：单元格文字多半**不在 sheet 里**，而是存在 `xl/sharedStrings.xml` 里、由 sheet
     * 用下标引用（`t="s"`）。只扫 sheet 的 `<t>` 会得到一份几乎空白的预览——所以这里必须
     * 先取共享字符串表再按下标取回。
     */
    private fun fromXlsx(f: File): String {
        val shared = runCatching {
            rawOf(f, "xl/sharedStrings.xml")?.let { xml -> tags(xml, "t") } ?: emptyList()
        }.getOrDefault(emptyList())
        // 数字格式（2026-10-20）：45% 在文件里是 0.45、日期是 46319，
        // 不按样式还原成文字，模型读到的就是"0.45"和"46319"，答非所问。
        val formats = runCatching {
            XlsxEditor.parseCellFormats(rawOf(f, "xl/styles.xml"))
        }.getOrDefault(emptyList())
        val sheets = entryNames(f)
            .filter { it.startsWith("xl/worksheets/sheet") && it.endsWith(".xml") }
            .sorted()
        if (sheets.isEmpty()) return "[没有找到工作表]"
        return sheets.mapIndexed { i, name ->
            "—— ${f.nameWithoutExtension} 第 ${i + 1} 表 ——\n" +
                (runCatching {
                    rawOf(f, name)?.let { xml -> sheetToText(xml, shared, formats) } ?: "[读不了]"
                }.getOrDefault("[读不了]"))
        }.joinToString("\n\n")
    }

    /** 一个 sheet → 每行一行、行内每格用 ` | ` 分隔。空单元格会丢（可读优先于完全还原）。 */
    private fun sheetToText(xml: String, shared: List<String>, formats: List<String> = emptyList()): String =
        ROW.findAll(xml).joinToString("\n") { m ->
            CELL.findAll(m.value).joinToString(" | ") { cm ->
                val cellXml = cm.value
                val t = attr(cellXml, "t")
                when (t) {
                    "s" -> cellXml.vIndex()?.toIntOrNull()?.let { shared.getOrNull(it) }.orEmpty()
                    "inlineStr" -> tags(cellXml, "t").joinToString("")
                    else -> {
                        val raw = cellXml.vIndex().orEmpty()
                        val fmt = attr(cellXml, "s")?.toIntOrNull()
                            ?.let { formats.getOrNull(it) } ?: ""
                        if (raw.isEmpty() || fmt.isEmpty()) raw else XlsxEditor.formatNumber(raw, fmt)
                    }
                }
            }
        }

    private val ROW = Regex("<row\\b[^>]*>.*?</row>", RegexOption.DOT_MATCHES_ALL)
    private val CELL = Regex("<c\\b[^>]*/>|<c\\b[^>]*>.*?</c>", RegexOption.DOT_MATCHES_ALL)
    private val V = Regex("<v>(.*?)</v>", RegexOption.DOT_MATCHES_ALL)

    private fun String.vIndex(): String? = V.find(this)?.groupValues?.get(1)?.let(::unescape)

    private fun attr(cellXml: String, name: String): String? =
        Regex("$name=\"([^\"]*)\"").find(cellXml)?.groupValues?.get(1)

    // ---------------- zip 辅助 ----------------

    /** 取某个 entry 的文本。文件里没有这个 entry 时抛异常（调用方决定怎么兜）。 */
    private fun textOf(f: File, entry: String, block: (String) -> String): String =
        rawOf(f, entry)?.let(block) ?: throw IllegalStateException("缺少 $entry")

    /** 同上，但缺 entry 时返回 null 而不是抛——预览里"这张表没读到"不必炸掉整个文件。 */
    private fun rawOf(f: File, entry: String): String? =
        runCatching {
            ZipFile(f).use { z ->
                val e = z.getEntry(entry) ?: return@use null
                z.getInputStream(e).readBytes().toString(Charsets.UTF_8)
            }
        }.getOrNull()

    private fun entryNames(f: File): List<String> =
        ZipFile(f).use { z ->
            val en = z.entries()
            val out = ArrayList<String>()
            while (en.hasMoreElements()) out.add(en.nextElement().name)
            out
        }

    /** 幻灯片按**页码排序**（slide10 在 slide2 后面，按字符串排会翻车）。 */
    private fun slideNames(f: File): List<String> =
        entryNames(f)
            .filter { it.startsWith("ppt/slides/slide") && it.endsWith(".xml") }
            .sortedBy { it.removePrefix("ppt/slides/slide").removeSuffix(".xml").toIntOrNull() ?: Int.MAX_VALUE }

    /**
     * 抽出指定标签里的所有文本（按出现顺序）。
     *
     * [preserveWhitespace]：OOXML 常把一句话拆成多个 run（`<w:t>Kris</w:t>` +
     * `<w:t> Wang</w:t>`），run 首尾的空格是内容的一部分——trim 掉就会把
     * "Kris Wang" 粘成 "KrisWang"（2026-11-09 真机：王子昂范文全文单词粘连）。
     * docx/pptx 正文必须保留；xlsx 共享字符串保持 trim（单元格级，空白单元格没必要留）。
     */
    private fun tags(xml: String, tag: String, preserveWhitespace: Boolean = false): List<String> =
        Regex("<$tag(?:\\s[^>]*)?>(.*?)</$tag>", RegexOption.DOT_MATCHES_ALL)
            .findAll(xml)
            .map {
                val raw = unescape(it.groupValues[1])
                if (preserveWhitespace) raw else raw.trim()
            }
            .filter { it.isNotEmpty() }
            .toList()

    private fun unescape(s: String): String = s
        .replace("&lt;", "<").replace("&gt;", ">")
        .replace("&quot;", "\"").replace("&apos;", "'")
        .replace("&amp;", "&")
}
