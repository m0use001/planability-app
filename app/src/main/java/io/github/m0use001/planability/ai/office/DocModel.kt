package io.github.m0use001.planability.ai.office

import org.json.JSONArray
import org.json.JSONObject

/**
 * v14.3：**与格式无关的文档模型**（IR）。AI 产出这个，各格式写出器负责落盘。
 *
 * ## 为什么不直接让 AI 写 XML
 *
 * 让模型直接吐 OOXML 是这类需求最常见的做法，也是最靠不住的做法：几百行 XML 里错一个
 * 属性名、错一个元素顺序，PowerPoint 就报"需要修复"——而**这种错在手机上看不出来**，
 * 只能等用户拿到电脑打开才发现。所以这里的分工是：
 *
 * - 模型只描述**内容与意图**（标题、要点、图片、表格、备注），它擅长的部分
 * - 序列化由 [io.github.m0use001.planability.ai.ptl.OfficeWriter] 负责，元素顺序、关系 id、
 *   内容类型声明都只有一处代码在管，改错了立刻有单测/校验器挡
 *
 * ## 为什么模型里带版式（layout/主题色）
 *
 * 用户抱怨过"高德路网过期""只有卫星图没标注"这类**观感**问题；PPT 是同一类需求。
 * 模型里保留 `layout` 与 `theme` 两个字段，是为了让 P1 的"每页 PNG 快照"能用**同一份
 * 信息**画出来——快照和文件不一致的话，预览就是骗人的。
 *
 * ## 刻意保持扁平
 *
 * 没有继承、没有嵌套列表、没有样式继承表。字段多了，模型填错率就上去；
 * 真正需要复杂排版时（P3 交给 PC 端引擎），那部分在 PC 侧做，不污染这里的模型。
 */
object DocModel {

    /** 版式。取值与写出器/P1 渲染器三方约定一致，改一处要改三处。 */
    enum class SlideLayout(val cn: String) {
        TITLE("标题页"),
        SECTION("章节页"),
        BULLETS("要点页"),
        TWO_COLUMN("两栏页"),
        IMAGE("整页图"),
        TABLE("表格页"),
        QUOTE("引言页")
    }

    /** 一个块：幻灯片里能放的东西。 */
    sealed interface Block {
        data class Para(val text: String, val bold: Boolean = false) : Block
        data class Bullets(val items: List<String>) : Block

        /** @param path 本地文件路径或 http(s) URL；写出器负责内嵌（http 的会先下载） */
        data class Picture(val path: String, val caption: String = "") : Block

        /** 首行为表头。列宽按内容数均分，写出器不再细调——先保证不溢出。 */
        data class Table(val rows: List<List<String>>) : Block
    }

    data class Slide(
        val layout: SlideLayout = SlideLayout.BULLETS,
        val title: String = "",
        val subtitle: String = "",
        val blocks: List<Block> = emptyList(),
        /** 演讲者备注。写进 pptx 的 notesSlide，别人在电脑上放映时才看得到。 */
        val notes: String = "",
        /**
         * 页码（1 基）。
         *
         * 生成时用不上；但**改稿时是命中的关键**——它标出这一页在原文件里的位置，
         * 改写时才能只替换该替换的那一页。没有它就只能整份重建，那不是编辑是重写。
         * [PptxEditor] 读入时会填上，模型改完后必须原样带回。
         */
        val pageNo: Int = 0
    )

    /**
     * 主题。只保留真正影响观感的三项：底色、深浅、强调色。
     * 字号/字体差异由版式隐含，不给模型选——给多了它会乱填。
     */
    data class Theme(
        val name: String = "默认",
        /** 深色主题用深底浅字，浅色反之 */
        val dark: Boolean = false,
        val accentHex: String = "4F6BED"
    ) {
        val bgHex: String get() = if (dark) "11131A" else "FFFFFF"
        val fgHex: String get() = if (dark) "F2F4F8" else "1A1C22"
        val mutedHex: String get() = if (dark) "9AA3B2" else "5A6272"
    }

    /**
     * 版面几何（EMU，1 英寸 = 914400 EMU）与字号（百分之一磅，即 pptx 的 `sz`）。
     *
     * 放在模型里、且只此一份，是为了让 [PptxWriter 写出的文件] 与 [SlideRenderer 画的快照]
     * 用**同一套坐标**。两者一旦各写各的，预览就会骗人——用户在预览里看到的位置和实际文件
     * 差一截，这种"半对"的预览比没有预览更糟糕，因为它会让人以为排版准了。
     */
    object Geometry {
        const val SLIDE_W = 12192000L      // 16:9 = 13.333in
        const val SLIDE_H = 6858000L       // 7.5in
        const val MARGIN = 685800L         // 0.75in
        const val TITLE_H = 900000L
        const val TITLE_H_BIG = 1400000L
        const val GAP = 200000L
        const val SUB_H = 700000L
        const val SUB_GAP = 900000L
        const val BODY_H = 4200000L
        const val BLOCK_H = 600000L
        const val BLOCK_GAP = 700000L
        const val ROW_H = 380000L
        const val PIC_H = 4200000L
        const val PIC_GAP = 150000L
        /** 图注紧跟在图片下方这么远（PptxWriter 与 SlideRenderer 共用，免得两边错开） */
        const val CAPTION_OFFSET = 40000L

        const val TITLE_SZ = 2800
        const val TITLE_SZ_BIG = 4000
        const val SUB_SZ = 1800
        const val BODY_SZ = 1600
        const val TABLE_SZ = 1200

        /** 内容区宽度 = 页宽 - 左右页边距 */
        const val CONTENT_W = SLIDE_W - MARGIN * 2

        /** EMU → 像素（按目标宽度等比缩放，16:9 恒成立） */
        fun emuToPx(emu: Long, targetWidthPx: Int): Int =
            (emu.toDouble() / SLIDE_W * targetWidthPx).toInt()

        /** 字号（百分之一磅）→ 像素，与上面的缩放比例一致 */
        fun szToPx(sz: Int, targetWidthPx: Int): Int =
            (sz / 100.0 / 72.0 * 914400.0 / SLIDE_W * targetWidthPx).toInt().coerceAtLeast(1)
    }

    data class Deck(
        val title: String,
        val slides: List<Slide>,
        val theme: Theme = Theme(),
        val author: String = "Planability"
    )

    // ---------- 从 AI 的 JSON 解析（宽容优先：宁可少字段也别整篇失败） ----------

    /**
     * 解析模型给出的幻灯片数组。
     *
     * 宽容的具体含义：未知 layout 落到 BULLETS、缺 title 用第一段文字顶上、
     * 单页解析失败只丢那一页而不是整份报废——**生成 5 页错 1 页，好过 5 页全废**。
     */
    fun deckFromJson(title: String, theme: Theme, json: JSONArray): Deck {
        val slides = ArrayList<Slide>()
        for (i in 0 until json.length()) {
            val o = json.optJSONObject(i) ?: continue
            runCatching { slides.add(slideFromJson(o)) }
                .onFailure { /* 丢这一页，继续 */ }
        }
        return Deck(title = title, slides = slides, theme = theme)
    }

    fun slideFromJson(o: JSONObject): Slide {
        val layout = runCatching { SlideLayout.valueOf(o.optString("layout").uppercase()) }
            .getOrDefault(SlideLayout.BULLETS)
        val blocks = ArrayList<Block>()
        val arr = o.optJSONArray("blocks")
        if (arr != null) {
            for (i in 0 until arr.length()) {
                val b = arr.optJSONObject(i) ?: continue
                when (b.optString("type").lowercase()) {
                    "para", "text" -> {
                        val t = b.optString("text")
                        if (t.isNotBlank()) blocks.add(Block.Para(t, b.optBoolean("bold")))
                    }
                    "bullets", "list" -> {
                        val items = ArrayList<String>()
                        val listArr = b.optJSONArray("items")
                        if (listArr != null) for (j in 0 until listArr.length()) {
                            val v = listArr.optString(j)
                            if (v.isNotBlank()) items.add(v)
                        }
                        // 也接受 "a|b|c" 这种扁平写法：模型很爱这么输出
                        if (items.isEmpty()) {
                            val t = b.optString("text")
                            if (t.isNotBlank()) items.addAll(t.split('|', '\n').map { it.trim() }.filter { it.isNotBlank() })
                        }
                        if (items.isNotEmpty()) blocks.add(Block.Bullets(items))
                    }
                    "image", "picture" -> {
                        val p = b.optString("path").ifBlank { b.optString("url") }
                        if (p.isNotBlank()) blocks.add(Block.Picture(p, b.optString("caption")))
                    }
                    "table" -> {
                        val rows = ArrayList<List<String>>()
                        val r = b.optJSONArray("rows")
                        if (r != null) for (j in 0 until r.length()) {
                            val rowArr = r.optJSONArray(j) ?: continue
                            val row = ArrayList<String>()
                            for (k in 0 until rowArr.length()) row.add(rowArr.optString(k))
                            if (row.isNotEmpty()) rows.add(row)
                        }
                        if (rows.isNotEmpty()) blocks.add(Block.Table(rows))
                    }
                }
            }
        }
        // 标题页/章节页允许把要点直接写在 title/subtitle 上，不强制 blocks
        val title = o.optString("title")
        val subtitle = o.optString("subtitle")
        val notes = o.optString("notes")
        return Slide(layout, title, subtitle, blocks, notes)
    }

    /** 主题从 JSON 读；缺省值都合理，不必报错。 */
    fun themeFromJson(o: JSONObject?): Theme {
        if (o == null) return Theme()
        return Theme(
            name = o.optString("name").ifBlank { "默认" },
            dark = o.optBoolean("dark"),
            accentHex = o.optString("accent").ifBlank { "4F6BED" }
        )
    }

    // ---------- 序列化（给电脑端渲染引擎用） ----------

    /**
     * 把 Deck 导出成 JSON，交给 PC 端的高保真引擎（见 [PcBridge]）。
     *
     * ## 为什么把这套格式当**契约**而不是随手写的
     *
     * 两端没有共享代码库，格式只能靠这份文档 + 单测 + 真机对拍维持一致。
     * 所以这里的规则要守住：
     * - 字段名用英文小驼峰（PC 端 JavaScript 直接用）
     * - **不省略有意义的默认值**：宁可多写几个字段，也不要让 PC 端"猜"默认值——
     *   两边默认值不一致时，产物会静默不一样，而没人知道是哪边猜错了
     * - 数组一律输出（空数组也输出），避免"缺失"与"空"混淆
     *
     * 格式一旦发布就**只能加字段不能改字段**（老版本 PC 端要能读新版本手机端的数据）。
     */
    fun deckToJson(deck: Deck): JSONObject {
        val slides = JSONArray()
        deck.slides.forEach { s -> slides.put(slideToJson(s)) }
        val theme = JSONObject()
            .put("name", deck.theme.name)
            .put("dark", deck.theme.dark)
            .put("accent", deck.theme.accentHex)
        return JSONObject()
            .put("title", deck.title)
            .put("author", deck.author)
            .put("theme", theme)
            .put("slides", slides)
    }

    private fun slideToJson(s: Slide): JSONObject {
        val blocks = JSONArray()
        s.blocks.forEach { b ->
            when (b) {
                is Block.Para -> blocks.put(
                    JSONObject().put("type", "para").put("text", b.text).put("bold", b.bold)
                )
                is Block.Bullets -> blocks.put(
                    JSONObject().put("type", "bullets").put("items", JSONArray().apply { b.items.forEach { put(it) } })
                )
                is Block.Picture -> blocks.put(
                    JSONObject().put("type", "image").put("path", b.path).put("caption", b.caption)
                )
                is Block.Table -> {
                    val rows = JSONArray()
                    b.rows.forEach { r ->
                        rows.put(JSONArray().apply { r.forEach { put(it) } })
                    }
                    blocks.put(JSONObject().put("type", "table").put("rows", rows))
                }
            }
        }
        return JSONObject()
            .put("layout", s.layout.name.lowercase())
            .put("title", s.title)
            .put("subtitle", s.subtitle)
            .put("notes", s.notes)
            .put("pageNo", s.pageNo)
            .put("blocks", blocks)
    }
}