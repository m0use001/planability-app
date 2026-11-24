package io.github.m0use001.planability.ai.office

/**
 * v14.3：**排版趟**——把一页幻灯片排成"带坐标的元素列表"。
 *
 * ## 为什么要多这一层
 *
 * 我们要在两个地方呈现同一页：写进 `.pptx` 文件（[PptxWriter]），和画成 PNG 快照（P1 的
 * 手机端预览）。如果两边各写一遍"标题放哪、多高、字号多少"，迟早会漂——而漂了的代价很特别：
 * **预览不会报错，只会说谎**。用户在预览里看着排版没问题，用电脑打开发现错位，这时快照
 * 反而比没有更糟，因为它让人以为"排版准了"。
 *
 * 所以排版只在这里算一次，两边都消费同一份 [Page]。要改版式就改这个文件，改一处两边同时变；
 * 剩下各写各的只有"怎么把已定位的文字变成 XML / 像素"，那部分是互不相干的。
 *
 * ## 坐标为什么还是 EMU
 *
 * 沿用 pptx 的单位（1 英寸 = 914400 EMU）而不是直接用像素：文件里必须是 EMU，快照侧
 * 再用 [DocModel.Geometry.emuToPx] 按目标宽度等比缩放。这样同一份坐标能同时喂给两侧，
 * 且 16:9 的缩放关系天然成立（页宽 → 目标宽度，页高按同一比例）。
 */
object SlideLayout {

    /** 一个已定位的页面元素。坐标单位 EMU，颜色是 6 位十六进制（不带 `#`）。 */
    sealed interface Item {

        /** 单段文字。`align` 取 `l` / `ctr`。 */
        data class Text(
            val x: Long, val y: Long, val w: Long, val h: Long,
            val text: String, val sz: Int, val bold: Boolean,
            val color: String, val align: String = "l"
        ) : Item

        /** 项目符号列表。 */
        data class Bullets(
            val x: Long, val y: Long, val w: Long, val h: Long,
            val items: List<String>, val sz: Int, val color: String
        ) : Item

        /** 图片。[path] 可能是本地路径或 http(s) 地址，取不到时**由消费方跳过**，不占位。 */
        data class Picture(
            val x: Long, val y: Long, val w: Long, val h: Long,
            val path: String, val caption: String = ""
        ) : Item

        /** 表格，首行为表头。 */
        data class Table(
            val x: Long, val y: Long, val w: Long, val h: Long,
            val rows: List<List<String>>
        ) : Item
    }

    /** 一页排好版的结果。 */
    data class Page(val items: List<Item>)

    private val g get() = DocModel.Geometry

    /**
     * 排一遍整个演示文稿。
     *
     * 版式差异只体现在标题（封面页更大、居中）与标题块高度上——这是刻意从简的：
     * 真正的母版继承、母版占位符是 P3 电脑端引擎该干的事，手机端这套自造一套简化规则就够，
     * 而且**规则越少，两边越不容易分歧**。
     */
    fun of(deck: DocModel.Deck): List<Page> = deck.slides.map { page(it, deck.theme) }

    /** 排单页。 */
    fun page(slide: DocModel.Slide, theme: DocModel.Theme): Page {
        val out = ArrayList<Item>()
        val isTitle = slide.layout == DocModel.SlideLayout.TITLE
        var y = g.MARGIN
        val cw = g.CONTENT_W

        if (slide.title.isNotBlank()) {
            out += Item.Text(
                g.MARGIN, y, cw,
                if (isTitle) g.TITLE_H_BIG else g.TITLE_H,
                slide.title,
                if (isTitle) g.TITLE_SZ_BIG else g.TITLE_SZ,
                bold = true, color = theme.fgHex,
                align = if (isTitle) "ctr" else "l"
            )
            y += (if (isTitle) g.TITLE_H_BIG else g.TITLE_H) + g.GAP
        }
        if (slide.subtitle.isNotBlank()) {
            // 封面页的副标题跟标题一样居中：封面页只有这两行，居中才是它该有的样子
            out += Item.Text(
                g.MARGIN, y, cw, g.SUB_H, slide.subtitle, g.SUB_SZ, false, theme.mutedHex,
                align = if (isTitle) "ctr" else "l"
            )
            y += g.SUB_GAP
        }

        for (b in slide.blocks) {
            when (b) {
                is DocModel.Block.Para -> {
                    out += Item.Text(g.MARGIN, y, cw, g.BLOCK_H, b.text, g.BODY_SZ, b.bold, theme.fgHex)
                    y += g.BLOCK_GAP
                }
                is DocModel.Block.Bullets -> {
                    out += Item.Bullets(g.MARGIN, y, cw, g.BODY_H, b.items, g.BODY_SZ, theme.fgHex)
                    y += g.BODY_H
                }
                is DocModel.Block.Table -> {
                    val h = g.ROW_H * b.rows.size.coerceAtLeast(1)
                    out += Item.Table(g.MARGIN, y, cw, h, b.rows)
                    y += h + g.GAP
                }
                is DocModel.Block.Picture -> {
                    out += Item.Picture(g.MARGIN, y, cw, g.PIC_H, b.path, b.caption)
                    y += g.PIC_H + g.PIC_GAP
                }
            }
        }
        return Page(out)
    }
}
