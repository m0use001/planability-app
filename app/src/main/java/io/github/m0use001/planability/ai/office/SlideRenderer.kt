package io.github.m0use001.planability.ai.office

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import io.github.m0use001.planability.shared.DocModel
import io.github.m0use001.planability.shared.SlideLayout
import java.io.File
import java.io.FileOutputStream

/**
 * v14.3：**幻灯片快照渲染**——把排好版的一页画成 PNG，供手机端翻页预览。
 *
 * ## 为什么是"画"而不是"渲染"
 *
 * 手机上没有任何能真渲染 pptx 的引擎（Office/WPS 都不给嵌入），LibreOffice 那套更别提。
 * 但我们其实**不需要**真渲染：我们自己的 [SlideLayout] 已经知道每个元素在哪、多大、什么颜色，
 * 用 Canvas 画一遍就行。这条路的代价是"与 PowerPoint 的渲染结果不完全一致"——
 * 字体度量、换行位置、自动缩放都可能差一点。这是**已知的、本地无法消除的差异**，
 * 用途是让人在手机上快速翻一遍、抓住明显的问题（比如标题写太长、图片选太小），
 * 而不是当作像素级预览。所以下面凡是要对齐的地方我都尽量对齐，不对齐的地方也写清楚为什么。
 *
 * ## 坐标从哪来
 *
 * 全部来自 [SlideLayout.Page]，与写 `.pptx` 文件用的是**同一趟排版**（坐标还是 EMU），
 * 渲染时用 [DocModel.Geometry.emuToPx] 按目标宽度等比缩放。所以版式只在 [SlideLayout]
 * 里定义一次，两边不会各画各的。
 */
object SlideRenderer {

    private val g get() = DocModel.Geometry

    /** 画一页。`widthPx` 决定清晰度与缩放比例；高度按 16:9 推出。 */
    fun render(deck: DocModel.Deck, pageIndex: Int, widthPx: Int): Bitmap? {
        if (pageIndex !in deck.slides.indices) return null
        val heightPx = (widthPx.toDouble() * g.SLIDE_H / g.SLIDE_W).toInt().coerceAtLeast(1)
        val bmp = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val theme = deck.theme
        canvas.drawColor(parseColor(theme.bgHex))

        val page = SlideLayout.page(deck.slides[pageIndex], theme)
        for (item in page.items) {
            runCatching { draw(canvas, item, widthPx, theme) }
            // 单个元素画崩不能让整页空白——预览的价值在于"整体大致对不对"
        }
        drawPageNumber(canvas, deck, pageIndex, widthPx, heightPx, theme)
        return bmp
    }

    /**
     * 渲染全部页并写成 PNG：`01.png`、`02.png`……
     *
     * 放在同名目录 `<名字>.slides/` 里，而不是把快照塞进 pptx 或散在别处：pptx 是要拿去
     * 分享/放映的正式产物，往里塞自己的中间产物属于污染；散在导出目录又和别的导出文件混在一起。
     * 目录名跟文件名绑定，用户删 pptx 时也认得出这堆 png 是它的预览。
     *
     * @return 实际写出的图片数
     */
    fun renderAllToDir(deck: DocModel.Deck, dir: File, widthPx: Int = 1280): Int {
        if (!dir.exists()) dir.mkdirs()
        var n = 0
        deck.slides.indices.forEach { i ->
            val bmp = runCatching { render(deck, i, widthPx) }.getOrNull() ?: return@forEach
            val f = File(dir, "%02d.png".format(i + 1))
            runCatching {
                FileOutputStream(f).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                n++
            }
            bmp.recycle()
        }
        return n
    }

    /** 快照目录名规则：`<pptx 文件名去掉后缀>.slides`。 */
    fun snapshotDirName(pptxFileName: String): String =
        pptxFileName.substringBeforeLast('.', pptxFileName) + ".slides"

    // ---------------- 绘制 ----------------

    private fun draw(canvas: Canvas, item: SlideLayout.Item, widthPx: Int, theme: DocModel.Theme) {
        when (item) {
            is SlideLayout.Item.Text ->
                drawLayout(
                    canvas, item.x, item.y, widthPx,
                    layoutOf(item.text, item.w, item.sz, item.bold, item.color, widthPx, item.align)
                )

            is SlideLayout.Item.Bullets -> drawBullets(canvas, item, widthPx)

            is SlideLayout.Item.Picture -> drawPicture(canvas, item, widthPx)

            is SlideLayout.Item.Table -> drawTable(canvas, item, widthPx, theme)
        }
    }

    /**
     * 把已排好版的文字画到 (emuX, emuY)。
     *
     * [StaticLayout] 只能画在画布原点，所以要先把画布平移过去、画完再还原——
     * 直接 `canvas.drawBitmap` 那种写法在这里不成立。
     */
    private fun drawLayout(canvas: Canvas, emuX: Long, emuY: Long, widthPx: Int, lay: StaticLayout) =
        drawLayoutPx(canvas, g.emuToPx(emuX, widthPx).toFloat(), g.emuToPx(emuY, widthPx).toFloat(), lay)

    /** 同一件事的像素版。要点列表内部是逐行累加 px 的，用 EMU 版反而要来回换算，容易出错。 */
    private fun drawLayoutPx(canvas: Canvas, xPx: Float, yPx: Float, lay: StaticLayout) {
        val save = canvas.save()
        canvas.translate(xPx, yPx)
        lay.draw(canvas)
        canvas.restoreToCount(save)
    }

    /**
     * 要点列表：符号单独画，正文按缩进后的宽度排版。
     *
     * 这样做的原因是要**悬挂缩进**——文字换行后要对齐符号右边，而不是顶到最左。
     * 如果把 "• " 拼进文本里交给自动换行，第二行会顶到最左参差不齐，列表一长就很难看。
     */
    private fun drawBullets(canvas: Canvas, item: SlideLayout.Item.Bullets, widthPx: Int) {
        val left = g.emuToPx(item.x, widthPx).toFloat()
        val top = g.emuToPx(item.y, widthPx).toFloat()
        val fullW = g.emuToPx(item.w, widthPx)
        val sizePx = g.szToPx(item.sz, widthPx)
        val bulletW = (sizePx * 1.4f).toInt()
        val lineH = (sizePx * 1.5f).toInt()

        val bulletPaint = paint(sizePx, item.color, bold = false)
        var cy = top
        for (text in item.items) {
            canvas.drawText("•", left, cy + sizePx.toFloat(), bulletPaint)
            val lay = layoutOf(text, item.w, item.sz, false, item.color, widthPx, "l", maxWidthPx = fullW - bulletW)
            drawLayoutPx(canvas, left + bulletW, cy, lay)
            cy += maxOf(lay.height, lineH)
        }
    }

    private fun drawPicture(canvas: Canvas, item: SlideLayout.Item.Picture, widthPx: Int) {
        val bmp = loadBitmap(item.path) ?: return   // 取不到就留白，不画占位框
        val x = g.emuToPx(item.x, widthPx)
        val y = g.emuToPx(item.y, widthPx)
        val w = g.emuToPx(item.w, widthPx)
        val h = g.emuToPx(item.h, widthPx)
        // 等比缩放并居中：pptx 那边是 stretch（铺满），图片比例不对时会被拉伸变形。
        // 这里选择保比例居中——预览宁可留白边，也不给用户看一个变形的图
        val scale = minOf(w.toFloat() / bmp.width, h.toFloat() / bmp.height)
        val dw = (bmp.width * scale).toInt()
        val dh = (bmp.height * scale).toInt()
        val dst = Rect(x + (w - dw) / 2, y + (h - dh) / 2, x + (w - dw) / 2 + dw, y + (h - dh) / 2 + dh)
        canvas.drawBitmap(bmp, null, dst, Paint().apply {
            isAntiAlias = true
            isFilterBitmap = true
        })
        bmp.recycle()
        if (item.caption.isNotBlank()) {
            // 图注位置沿用 PptxWriter 里的 CAPTION_OFFSET，两边保持一致
            val capLay = layoutOf(item.caption, item.w, g.TABLE_SZ, false, "808080", widthPx, "l")
            drawLayout(canvas, item.x, item.y + item.h + g.CAPTION_OFFSET, widthPx, capLay)
        }
    }

    /**
     * 表格：手绘格子。
     *
     * 文件那边用的是真 `a:tbl`（在 PowerPoint 里能整张选中、能改行列）；快照这边只是画，
     * 所以自己画矩形和线。想要"预览里也能改表"是 P2 改稿的事，不在渲染器里做。
     */
    private fun drawTable(
        canvas: Canvas,
        item: SlideLayout.Item.Table,
        widthPx: Int,
        theme: DocModel.Theme
    ) {
        val rows = item.rows
        if (rows.isEmpty()) return
        val cols = rows.maxOf { it.size }.coerceAtLeast(1)
        val x = g.emuToPx(item.x, widthPx)
        val y = g.emuToPx(item.y, widthPx)
        val w = g.emuToPx(item.w, widthPx)
        val h = g.emuToPx(item.h, widthPx)
        val colW = w / cols
        val rowH = h / rows.size
        val sizePx = g.szToPx(g.TABLE_SZ, widthPx)

        val fill = Paint().apply { isAntiAlias = true }
        val line = Paint().apply {
            isAntiAlias = true
            color = parseColor("D0D4DC"); style = Paint.Style.STROKE; strokeWidth = 1f
        }
        val textPaint = paint(sizePx, theme.fgHex, bold = false)
        // 深色主题下"白底黑字"会变成一块刺眼的白斑，用比背景亮一档的深灰代替
        val cellFill = parseColor(if (theme.dark) "1E2230" else "FFFFFF")

        rows.forEachIndexed { ri, row ->
            val isHeader = ri == 0
            repeat(cols) { ci ->
                val cx = x + colW * ci
                val cy = y + rowH * ri
                fill.color = if (isHeader) parseColor(theme.accentHex) else cellFill
                canvas.drawRect(cx.toFloat(), cy.toFloat(), (cx + colW).toFloat(), (cy + rowH).toFloat(), fill)
                canvas.drawRect(cx.toFloat(), cy.toFloat(), (cx + colW).toFloat(), (cy + rowH).toFloat(), line)
                val v = row.getOrElse(ci) { "" }
                if (v.isNotBlank()) {
                    // 表头是强调色底，字就得反过来用白色，否则深色强调色上黑字看不清
                    textPaint.color = parseColor(if (isHeader) "FFFFFF" else theme.fgHex)
                    textPaint.textSize = sizePx.toFloat()
                    canvas.drawText(
                        v, cx + sizePx.toFloat(), cy + rowH / 2f + textPaint.textSize / 3f, textPaint
                    )
                }
            }
        }
    }

    private fun drawPageNumber(
        canvas: Canvas, deck: DocModel.Deck, index: Int, widthPx: Int, heightPx: Int, theme: DocModel.Theme
    ) {
        val total = deck.slides.size
        if (total <= 1) return
        val p = paint((widthPx * 0.022f).toInt(), theme.mutedHex, bold = false).apply { textAlign = Paint.Align.RIGHT }
        canvas.drawText(
            "${index + 1} / $total", (widthPx * 0.96f).toInt().toFloat(),
            (heightPx * 0.965f).toInt().toFloat(), p
        )
    }

    // ---------------- 文本与图片工具 ----------------

    private fun paint(sizePx: Int, colorHex: String, bold: Boolean): TextPaint = TextPaint(
        Paint.ANTI_ALIAS_FLAG
    ).apply {
        textSize = sizePx.toFloat()
        color = parseColor(colorHex)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun layoutOf(
        text: String, emuW: Long, sz: Int, bold: Boolean, colorHex: String,
        widthPx: Int, align: String, maxWidthPx: Int = -1
    ): StaticLayout {
        val w = if (maxWidthPx > 0) maxWidthPx else g.emuToPx(emuW, widthPx).coerceAtLeast(1)
        return StaticLayout.Builder
            .obtain(text, 0, text.length, paint(g.szToPx(sz, widthPx), colorHex, bold), w)
            .setAlignment(
                if (align == "ctr") Layout.Alignment.ALIGN_CENTER else Layout.Alignment.ALIGN_NORMAL
            )
            .setLineSpacing(0f, 1.15f)
            .setIncludePad(false)
            .build()
    }

    /** 本地路径或 http(s) 地址取图；取不到返回 null。 */
    private fun loadBitmap(path: String): Bitmap? = runCatching {
        if (path.startsWith("http")) {
            val conn = java.net.URL(path).openConnection()
            conn.connectTimeout = 8_000
            conn.readTimeout = 15_000
            conn.setRequestProperty("User-Agent", "Planability/1.0 (Android)")
            BitmapFactory.decodeStream(conn.getInputStream())
        } else {
            val f = File(path)
            if (f.exists()) BitmapFactory.decodeFile(f.absolutePath) else null
        }
    }.getOrNull()

    /** 6 位十六进制（不带 #）→ Color；解析不了就返回黑色而不是抛——预览不该因为颜色崩掉。 */
    private fun parseColor(hex: String): Int = runCatching {
        Color.parseColor(if (hex.startsWith("#")) hex else "#$hex")
    }.getOrDefault(Color.BLACK)
}
