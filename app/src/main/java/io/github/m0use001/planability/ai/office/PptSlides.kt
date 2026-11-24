package io.github.m0use001.planability.ai.office

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.DataFormatException
import java.util.zip.Inflater

/**
 * 老版 .ppt 的**每页整体快照**：把 Escher 内嵌图（公式/图表 WMF、截图 PNG/JPEG）按
 * 各自的锚点矩形重画到画布上，加上文本框文字——给视觉模型一份"这一页长什么样"。
 *
 * ## 为什么是重建而不是截图
 *
 * 手机上没有任何能渲染 .ppt 的引擎；但 [PptBinary] 已经能走通 PowerPoint Document 记录流，
 * 每页的图形都在 `PPDrawing → EscherDg → SpgrContainer → SpContainer` 里，每个
 * SpContainer 携带三样东西：
 * - `Opt(0xF00B)` 里的 `pib` 属性（0x104）＝ Picture 流里的第几张图（1 基）；
 * - `ClientAnchor(0xF010)`＝8 字节 4×int16（**top, left, right, bottom**，主坐标 576/英寸、
 *   页面 5760×4320；真机课件实测校准——按 ChildAnchor 的 (l,t,r,b) 顺序解释会把
 *   级联公式画到页面外）；
 * - `ClientTextbox(0xF00D)`＝文本原子（与文字抽取同源）。
 *
 * 图片本体在 CFB 的独立 `Pictures` 流里：PNG(0xF01E)/JPEG(0xF01D) 是 [16B UID + 0xFF +
 * 图片字节]；WMF(0xF01A/0xF01B) 是 **zlib 压缩的标准 WMF**（公式几乎全是这种——
 * Equation Editor 把公式存成矢量元文件，所以只抽文字的老链路对公式页完全失明）。
 *
 * ## 渲染取舍
 *
 * WMF 只实现公式实际用到的 GDI 子集（TextOut/ExtTextOut/MoveTo/LineTo/Pen/Brush/Font、
 * Rectangle/Ellipse/Polygon/PolyLine），bbox→锚点矩形线性映射。结论与 [SlideRenderer] 相同：
 * 这是"能看清这一页画了什么"的近似快照，不是像素级还原；够视觉模型读公式与版式。
 */
object PptSlides {

    private const val RT_SLIDE = 1006
    private const val RT_TEXT_CHARS = 4000
    private const val RT_TEXT_BYTES = 4008
    private const val RT_ESCHER_FIRST = 0xF000
    private const val RT_ESCHER_LAST = 0xF00F
    /** 真正的 Escher 容器（ver=0 的包装例外靠类型表兜底；Sp/Opt/Anchor 是叶子） */
    private val ESCHER_CONTAINERS = setOf(0xF000, 0xF001, 0xF002, 0xF003, 0xF004)
    private const val RT_SP_CONTAINER = 0xF004
    private const val RT_OPT = 0xF00B
    private const val RT_PIB = 0x104               // EscherOpt 属性：图片引用（1 基）
    private const val RT_CLIENT_ANCHOR = 0xF010
    private const val RT_CLIENT_TEXTBOX = 0xF00D

    /** 页面主坐标：10"×7.5" × 576/英寸（真机课件 DocumentAtom 实测）。 */
    private const val PAGE_W = 5760f
    private const val PAGE_H = 4320f

    /** 一页快照的位图宽（高按 4:3 推出）。 */
    private const val SNAPSHOT_W = 1080

    class Shape(val pib: Int?, val rect: IntArray?, val texts: List<String>)
    class SlideVisual(val index: Int, val shapes: List<Shape>)

    /** 抽取每页的形状（图片引用 + 锚点 + 文本）。失败抛异常（调用方兜底）。 */
    fun extract(f: File): List<SlideVisual> {
        val bytes = f.readBytes()
        val stream = PptBinary.Cfb(bytes).readStream("PowerPoint Document")
            ?: throw IllegalStateException("不是有效的 .ppt")
        val blips = parsePictures(PptBinary.Cfb(bytes).readStream("Pictures") ?: ByteArray(0))

        val slides = ArrayList<SlideVisual>()
        collectShapes(stream, slides, blips)
        for (i in slides.indices) slides[i] = SlideVisual(i + 1, slides[i].shapes)
        return slides
    }

    /** 逐页渲染快照（JPEG 字节）。pib 指向的图解不出来就跳过该形状，不让整页空白。 */
    fun renderSnapshots(f: File, maxWidth: Int = SNAPSHOT_W): List<ByteArray> {
        val bytes = f.readBytes()
        val cfb = PptBinary.Cfb(bytes)
        val stream = cfb.readStream("PowerPoint Document")
            ?: throw IllegalStateException("不是有效的 .ppt")
        val blips = parsePictures(cfb.readStream("Pictures") ?: ByteArray(0))

        val slides = ArrayList<SlideVisual>()
        collectShapes(stream, slides, blips)
        for (i in slides.indices) slides[i] = SlideVisual(i + 1, slides[i].shapes)

        val out = ArrayList<ByteArray>(slides.size)
        val h = (maxWidth * PAGE_H / PAGE_W).toInt()
        for (s in slides) {
            val bmp = Bitmap.createBitmap(maxWidth, h, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            c.drawColor(Color.WHITE)
            for (sh in s.shapes) {
                runCatching { drawShape(c, sh, maxWidth.toFloat(), h.toFloat(), sh.pib?.let { blips[it] }) }
            }
            out.add(encodeJpeg(bmp))
            bmp.recycle()
        }
        return out
    }

    // ── SpContainer 收集 ──

    private fun collectShapes(stream: ByteArray, slides: MutableList<SlideVisual>, blips: Map<Int, Blip>) {
        fun walk(buf: ByteArray, start: Int, end: Int, depth: Int) {
            if (depth > 64) return
            var q = start
            while (q + 8 <= end) {
                val verInst = le16(buf, q)
                val recType = le16(buf, q + 2)
                val recLen = le32(buf, q + 4)
                if (recLen < 0 || q + 8L + recLen > end) return
                val body = q + 8
                val bodyEnd = body + recLen
                val isContainer = (((verInst shr 4) and 0xF) == 0xF ||
                    recType in ESCHER_CONTAINERS || recType == 1036)
                when {
                    recType == RT_SLIDE -> {
                        // 每个顶层 Slide 容器开一页；容器内的 SpContainer 归这一页
                        slides.add(SlideVisual(slides.size + 1, emptyList()))
                        walk(buf, body, bodyEnd, depth + 1)
                    }
                    recType == RT_SP_CONTAINER -> {
                        val sh = readSpContainer(buf, body, bodyEnd)
                        if (sh != null && slides.isNotEmpty()) {
                            val cur = slides[slides.size - 1]
                            slides[slides.size - 1] = SlideVisual(
                                cur.index, cur.shapes + sh
                            )
                        }
                    }
                    isContainer -> walk(buf, body, bodyEnd, depth + 1)
                }
                q = bodyEnd
            }
        }
        walk(stream, 0, stream.size, 0)
    }

    private fun readSpContainer(buf: ByteArray, start: Int, end: Int): Shape? {
        var pib: Int? = null
        var rect: IntArray? = null
        val texts = ArrayList<String>()
        var q = start
        while (q + 8 <= end) {
            val verInst = le16(buf, q)
            val recType = le16(buf, q + 2)
            val recLen = le32(buf, q + 4)
            if (recLen < 0 || q + 8L + recLen > end) break
            val body = q + 8
            when (recType) {
                RT_OPT -> {
                    var k = body
                    val e = body + recLen
                    while (k + 6 <= e) {
                        val flags = le16(buf, k)
                        val v = le32(buf, k + 2)
                        val pid = flags and 0x3FFF
                        val complex = (flags shr 14) and 1 == 1
                        if (pid == RT_PIB && v > 0 && v <= 0xFFFF) pib = v
                        k += 6
                        if (complex) {
                            val adv = (v + 3) and -4          // 4 字节对齐（-4 = 0xFFFFFFFC）
                            if (v < 0 || k + adv > e) break
                            k += adv
                        }
                    }
                }
                RT_CLIENT_ANCHOR -> if (recLen >= 8) {
                    rect = intArrayOf(
                        s16(buf, body), s16(buf, body + 2),
                        s16(buf, body + 4), s16(buf, body + 6)
                    )
                }
                RT_CLIENT_TEXTBOX -> {
                    var k = body
                    val e = body + recLen
                    while (k + 8 <= e) {
                        val tvi = le16(buf, k)
                        val trt = le16(buf, k + 2)
                        val tln = le32(buf, k + 4)
                        if (tln < 0 || k + 8L + tln > e) break
                        when (trt) {
                            RT_TEXT_CHARS ->
                                texts += cleanTexts(String(buf, k + 8, tln, Charsets.UTF_16LE))
                            RT_TEXT_BYTES -> {
                                val tb = buf.copyOfRange(k + 8, k + 8 + tln)
                                val cs = if (tb.any { it.toInt() and 0xFF >= 0x80 })
                                    runCatching { charset("GBK") }.getOrDefault(Charsets.ISO_8859_1)
                                else Charsets.ISO_8859_1
                                texts += cleanTexts(String(tb, cs))
                            }
                        }
                        k += 8 + tln
                    }
                }
            }
            q = body + recLen
        }
        if (pib == null && rect == null && texts.isEmpty()) return null
        return Shape(pib?.takeIf { it > 0 }, rect, texts)
    }

    // ── Pictures 流 ──

    private class Blip(val kind: Kind, val data: ByteArray)
    private enum class Kind { PNG, JPEG, WMF }

    /** Pictures 流 → pib(1 基) → 图片。解不出的（EMF/PICT/损坏）跳过。 */
    private fun parsePictures(stream: ByteArray): Map<Int, Blip> {
        val out = HashMap<Int, Blip>()
        var p = 0
        var idx = 0
        while (p + 8 <= stream.size) {
            val verInst = le16(stream, p)
            val recType = le16(stream, p + 2)
            val recLen = le32(stream, p + 4)
            if (recLen <= 0 || p + 8L + recLen > stream.size) break
            val body = stream.copyOfRange(p + 8, p + 8 + recLen)
            idx++
            val blip: Blip? = when (recType) {
                0xF01E -> pngOrJpeg(body, PNG_MAGIC)?.let { Blip(Kind.PNG, it) }
                0xF01D -> pngOrJpeg(body, JPEG_MAGIC)?.let { Blip(Kind.JPEG, it) }
                0xF01A, 0xF01B -> wmfBytes(body)?.let { Blip(Kind.WMF, it) }
                else -> null
            }
            if (blip != null) out[idx] = blip
            p += 8 + recLen
        }
        return out
    }

    private val PNG_MAGIC = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)
    private val JPEG_MAGIC = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())

    /** [16B UID][0xFF tag][图片字节]；从 tag 之后找魔数最稳（有的写入方在 UID 前还有变体）。 */
    private fun pngOrJpeg(body: ByteArray, magic: ByteArray): ByteArray? {
        var i = 16
        while (i + magic.size <= body.size) {
            if (body[i] == magic[0] && body[i + 1] == magic[1] &&
                body[i + 2] == magic[2] && (magic.size < 4 || body[i + 3] == magic[3])
            ) return body.copyOfRange(i, body.size)
            i++
        }
        return null
    }

    /** WMF blip：zlib 压缩的标准 WMF（真机课件 100% 是这种）。找 0x78 0x9C 解压。 */
    private fun wmfBytes(body: ByteArray): ByteArray? {
        var i = 16
        while (i + 2 <= body.size) {
            if (body[i] == 0x78.toByte() && body[i + 1] == 0x9C.toByte()) {
                try {
                    val inf = Inflater()
                    inf.setInput(body, i, body.size - i)
                    val out = ByteArrayOutputStream(body.size * 2)
                    val buf = ByteArray(8192)
                    while (!inf.finished()) {
                        val n = inf.inflate(buf)
                        if (n == 0) break
                        out.write(buf, 0, n)
                    }
                    val wmf = out.toByteArray()
                    // 标准 WMF 头：type=0x0001, headerSize=9, version=0x0300
                    if (wmf.size > 22 && le16(wmf, 0) == 1 && le16(wmf, 4) == 0x0300) return wmf
                } catch (_: DataFormatException) {
                }
            }
            i++
        }
        return null
    }

    // ── 画 ──

    private fun drawShape(c: Canvas, sh: Shape, pageW: Float, pageH: Float, blip: Blip?) {
        val rect = sh.rect
        val box = if (rect != null && rect[2] > rect[0] && rect[3] > rect[1])
            android.graphics.RectF(
                rect[1] / PAGE_W * pageW, rect[0] / PAGE_H * pageH,
                rect[2] / PAGE_W * pageW, rect[3] / PAGE_H * pageH
            ) else null
        if (blip != null && box != null) {
            when (blip.kind) {
                Kind.PNG, Kind.JPEG -> {
                    val bmp = BitmapFactory.decodeByteArray(blip.data, 0, blip.data.size)
                    if (bmp != null) {
                        c.drawBitmap(bmp, null, box, Paint(Paint.FILTER_BITMAP_FLAG))
                        bmp.recycle()
                        return
                    }
                }
                Kind.WMF -> {
                    if (WmfRenderer.draw(c, blip.data, box)) return
                }
            }
        }
        // 没有图或图解不出：文本框文字照画（文本形状的主要路径）
        if (sh.texts.isNotEmpty() && box != null) {
            val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.BLACK
                textSize = (box.height() / 6f).coerceIn(10f, 48f)
                typeface = Typeface.SANS_SERIF
            }
            val body = sh.texts.joinToString("\n")
            val layout = StaticLayout.Builder
                .obtain(body, 0, body.length, tp, box.width().toInt().coerceAtLeast(40))
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .build()
            c.save()
            c.translate(box.left, box.top)
            layout.draw(c)
            c.restore()
        }
    }

    private fun encodeJpeg(bmp: Bitmap): ByteArray {
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 82, out)
        return out.toByteArray()
    }

    private fun cleanTexts(raw: String): List<String> =
        raw.replace('\u000B', '\n')
            .replace('\r', '\n')
            .replace("\u0000", "")
            .split('\n')
            .map { it.trim() }
            .filter { it.isNotEmpty() }

    private fun le16(b: ByteArray, i: Int): Int =
        (b[i].toInt() and 0xFF) or ((b[i + 1].toInt() and 0xFF) shl 8)

    private fun le32(b: ByteArray, i: Int): Int =
        (b[i].toInt() and 0xFF) or ((b[i + 1].toInt() and 0xFF) shl 8) or
            ((b[i + 2].toInt() and 0xFF) shl 16) or ((b[i + 3].toInt() and 0xFF) shl 24)

    private fun s16(b: ByteArray, i: Int): Int = le16(b, i).let { if (it >= 0x8000) it - 0x10000 else it }
}
