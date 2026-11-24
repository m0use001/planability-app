package io.github.m0use001.planability.ai.office

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.TextPaint

/**
 * 极简 WMF（Windows 元文件）渲染器——只画老课件公式实际用到的 GDI 子集。
 *
 * ## 为什么是子集
 *
 * 公式 blip（Equation Editor/MathType 存成 WMF）的内容不是任意 GDI 程序，实测真机课件
 * 全部是这几类记录：CreatePen/CreateBrushIndirect/CreateFontIndirect/SelectObject、
 * MoveTo/LineTo（分数线、根号横线）、ExtTextOut/TextOut（变量与数字）、Rectangle/
 * Ellipse/Polygon/Polyline（根号、括号、积分号）。别的记录（调色板、DIB 块）忽略不画。
 *
 * ## 坐标映射
 *
 * 不信任 SetWindowOrg/Ext（Equation Editor 写的窗口和实际图形对不上），统一走
 * **两遍法**：第一遍收集所有坐标求 bbox，第二遍把 bbox 线性映射到锚点矩形
 * （等比缩放、居中）。
 *
 * ## 2026-11-11 全链撞测三修（公式页从"一团叠印"到可读）
 *
 * ①**文本要进 bbox**：EE 的 MoveTo 只给基线，第一版 bbox 只收基点 → 字高远大于
 *   行距、按锚点框放大会互相叠印。现在文本按 字高×0.62 估宽、按字高上探。
 * ②**Symbol 字符集映射**：公式里的数学符号是 Symbol 字体的**编码字节**（0xA2='′'、
 *   0xAE='→'、0x51='Θ'…），照普通字体画就是 `¢£()0` 乱码。CreateFontIndirect 的
 *   faceName 含 Symbol 时逐字符过 [SYMBOL_MAP]。
 * ③**dx 数组逐字符定位**：ExtTextOut 的逐字步进存在 dx 数组里，按自然字宽整串画，
 *   紧排的公式片段会叠字（`fbfafcba…` 那种堆叠）。有 dx 就一字一步进。
 */
object WmfRenderer {

    private const val FN_SELECT_OBJECT = 0x012D
    private const val FN_SET_TEXT_ALIGN = 0x012E
    private const val FN_SET_TEXT_COLOR = 0x0209
    private const val FN_SET_WINDOW_ORG = 0x020B
    private const val FN_LINETO = 0x0213
    private const val FN_MOVETO = 0x0214
    private const val FN_CREATE_BRUSH = 0x01FC
    private const val FN_CREATE_PEN = 0x01F0
    private const val FN_CREATE_PEN_INDIRECT = 0x01FD
    private const val FN_CREATE_FONT = 0x02FB
    private const val FN_TEXTOUT = 0x0521
    private const val FN_POLYGON = 0x0324
    private const val FN_POLYLINE = 0x0325
    private const val FN_RECTANGLE = 0x041B
    private const val FN_ELLIPSE = 0x0418
    private const val FN_EXT_TEXTOUT = 0x0A32

    /** 一条记录：功能号 + 16 位参数 + 原始字节（字体脸名要从 raw 里取）。 */
    private data class Rec(val fn: Int, val pr: IntArray, val raw: ByteArray)

    /** 字体对象：逻辑高度（WMF 单位）+ 是否 Symbol 字符集（按脸名判定）。 */
    private class FontObj(val height: Float, val symbol: Boolean)

    /**
     * Adobe Symbol 编码 → Unicode（0x20..0xFF 常用段；未列出的原样保留）。
     * Equation Editor 的希腊字母/箭头/关系符全靠这张表，缺了就是乱码。
     */
    private val SYMBOL_MAP: IntArray = IntArray(256) { it }.apply {
        fun m(c: Int, s: Char) { this[c] = s.code }
        m(0x22, '∀'); m(0x24, '∃'); m(0x27, '∋'); m(0x2A, '∗'); m(0x2D, '−'); m(0x40, '≅')
        m(0x41, 'Α'); m(0x42, 'Β'); m(0x43, 'Χ'); m(0x44, 'Δ'); m(0x45, 'Ε'); m(0x46, 'Φ')
        m(0x47, 'Γ'); m(0x48, 'Η'); m(0x49, 'Ι'); m(0x4A, 'ϑ'); m(0x4B, 'Κ'); m(0x4C, 'Λ')
        m(0x4D, 'Μ'); m(0x4E, 'Ν'); m(0x4F, 'Ο'); m(0x50, 'Π'); m(0x51, 'Θ'); m(0x52, 'Ρ')
        m(0x53, 'Σ'); m(0x54, 'Τ'); m(0x55, 'Υ'); m(0x56, 'ς'); m(0x57, 'Ω'); m(0x58, 'Ξ')
        m(0x59, 'Ψ'); m(0x5A, 'Ζ'); m(0x5C, '∴'); m(0x5E, '⊥'); m(0x60, '‾')
        m(0x61, 'α'); m(0x62, 'β'); m(0x63, 'χ'); m(0x64, 'δ'); m(0x65, 'ε'); m(0x66, 'φ')
        m(0x67, 'γ'); m(0x68, 'η'); m(0x69, 'ι'); m(0x6A, 'ϕ'); m(0x6B, 'κ'); m(0x6C, 'λ')
        m(0x6D, 'μ'); m(0x6E, 'ν'); m(0x6F, 'ο'); m(0x70, 'π'); m(0x71, 'θ'); m(0x72, 'ρ')
        m(0x73, 'σ'); m(0x74, 'τ'); m(0x75, 'υ'); m(0x76, 'ϖ'); m(0x77, 'ω'); m(0x78, 'ξ')
        m(0x79, 'ψ'); m(0x7A, 'ζ'); m(0x7E, '∼')
        m(0xA1, 'ϒ'); m(0xA2, '′'); m(0xA3, '≤'); m(0xA4, '⁄'); m(0xA5, '∞'); m(0xA6, 'ƒ')
        m(0xA7, '♣'); m(0xA8, '♦'); m(0xA9, '♥'); m(0xAA, '♠'); m(0xAB, '↔'); m(0xAC, '←')
        m(0xAD, '↑'); m(0xAE, '→'); m(0xAF, '↓'); m(0xB0, '°'); m(0xB1, '±'); m(0xB2, '″')
        m(0xB3, '≥'); m(0xB4, '×'); m(0xB5, '∝'); m(0xB6, '∂'); m(0xB7, '•'); m(0xB8, '÷')
        m(0xB9, '≠'); m(0xBA, '≡'); m(0xBB, '≈'); m(0xBC, '…'); m(0xBF, '↵')
        m(0xC0, 'ℵ'); m(0xC1, 'ℑ'); m(0xC2, 'ℜ'); m(0xC3, '℘'); m(0xC4, '⊗'); m(0xC5, '⊕')
        m(0xC6, '∅'); m(0xC7, '∩'); m(0xC8, '∪'); m(0xC9, '⊃'); m(0xCA, '⊇'); m(0xCB, '⊄')
        m(0xCC, '⊂'); m(0xCD, '⊆'); m(0xCE, '∈'); m(0xCF, '∉'); m(0xD0, '∠'); m(0xD1, '∇')
        m(0xD5, '∏'); m(0xD6, '√'); m(0xD7, '⋅'); m(0xD8, '¬'); m(0xD9, '∧'); m(0xDA, '∨')
        m(0xDB, '⇔'); m(0xDC, '⇐'); m(0xDD, '⇑'); m(0xDE, '⇒'); m(0xDF, '⇓')
        m(0xE0, '◊'); m(0xE1, '⟨'); m(0xE5, '∑'); m(0xF2, '∫')
    }

    /** 把一份 WMF 字节画进 [box]（页面上锚点矩形，px）。成功返回 true。 */
    fun draw(canvas: Canvas, wmf: ByteArray, box: RectF): Boolean {
        val recs = parse(wmf) ?: return false

        // pass1: bbox（含文本外接框：MoveTo 只给基线，字高要按字体上探）
        val xs = ArrayList<Int>(); val ys = ArrayList<Int>()
        var cpX = 0; var cpY = 0
        var curH = 200
        val fontHeights = HashMap<Int, Int>()
        var fontSeq = 0
        for ((fn, pr, _) in recs) {
            when (fn) {
                FN_CREATE_FONT -> {
                    if (pr.isNotEmpty()) fontHeights[fontSeq++] = kotlin.math.abs(s16(pr[0])).coerceAtLeast(8)
                }
                FN_SELECT_OBJECT -> if (pr.isNotEmpty()) fontHeights[pr[0]]?.let { curH = it }
                FN_MOVETO, FN_LINETO -> if (pr.size >= 2) {
                    cpX = s16(pr[1]); cpY = s16(pr[0]); xs.add(cpX); ys.add(cpY)
                }
                FN_EXT_TEXTOUT -> if (pr.size >= 4) {
                    val cnt = pr[2]
                    // EE 全部走 UPDATECP（记录里的 x,y 是 0），真实位置在当前点
                    xs.add(cpX); xs.add(cpX + (cnt * curH * 0.62f).toInt())
                    ys.add(cpY); ys.add(cpY - curH)
                }
                FN_TEXTOUT -> if (pr.size >= 3) {
                    val cnt = pr[0]
                    if (pr.size >= cnt + 3) {
                        val x = s16(pr[cnt + 2]); val y = s16(pr[cnt + 1])
                        xs.add(x); xs.add(x + (cnt * curH * 0.62f).toInt())
                        ys.add(y); ys.add(y - curH)
                    }
                }
                FN_RECTANGLE, FN_ELLIPSE -> if (pr.size >= 4) {
                    xs.add(s16(pr[0])); ys.add(s16(pr[1])); xs.add(s16(pr[2])); ys.add(s16(pr[3]))
                }
                FN_POLYGON, FN_POLYLINE -> if (pr.isNotEmpty()) {
                    val cnt = pr[0]
                    var i = 1
                    var k = 0
                    while (i + 1 < pr.size && k < cnt) { xs.add(s16(pr[i + 1])); ys.add(s16(pr[i])); i += 2; k++ }
                }
            }
        }
        if (xs.size < 2) return false
        val x0 = xs.min(); val x1 = xs.max(); val y0 = ys.min(); val y1 = ys.max()
        val bw = (x1 - x0).coerceAtLeast(1).toFloat()
        val bh = (y1 - y0).coerceAtLeast(1).toFloat()
        val sc = minOf(box.width() / bw, box.height() / bh)
        val ox = box.left + (box.width() - bw * sc) / 2f - x0 * sc
        val oy = box.top + (box.height() - bh * sc) / 2f - y0 * sc

        // pass2: 状态机执行
        val pen = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Color.BLACK }
        val brush = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL_AND_STROKE; color = Color.WHITE }
        val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            typeface = Typeface.SERIF
        }
        var curFont = FontObj(200f, false)
        var textColor = Color.BLACK
        val objs = HashMap<Int, Any>()
        var align = 0
        cpX = 0; cpY = 0
        fun s(x: Int, y: Int) = Pair(x * sc + ox, y * sc + oy)
        fun col(lo: Int, hi: Int): Int {
            val c = (lo and 0xFFFF) or ((hi and 0xFFFF) shl 16)
            return (0xFF shl 24) or ((c and 0xFF) shl 16) or (c and 0xFF00) or ((c shr 16) and 0xFF)
        }
        // WMF 里字符串按字节存（两字节一字）：低字节先，必要时高字节补位
        fun decodeBytes(pr: IntArray, from: Int, cnt: Int): String {
            val sb = StringBuilder(cnt)
            var got = 0
            var k = from
            while (k < pr.size && got < cnt) {
                sb.append((pr[k] and 0xFF).toChar()); got++
                if (got < cnt) { sb.append((pr[k] shr 8 and 0xFF).toChar()); got++ }
                k++
            }
            return sb.toString()
        }
        fun mapText(s: String): String =
            if (!curFont.symbol) s
            else s.map { ch -> SYMBOL_MAP.getOrElse(ch.code) { ch.code }.toChar() }.joinToString("")
        for ((fn, pr, raw) in recs) {
            runCatching {
                when (fn) {
                    FN_CREATE_BRUSH -> objs[objs.size] = Paint(brush).apply { color = col(pr[1], pr[2]) }
                    FN_CREATE_PEN, FN_CREATE_PEN_INDIRECT -> if (pr.size >= 5) {
                        objs[objs.size] = Paint(pen).apply { color = col(pr[3], pr[4]) }
                    }
                    FN_CREATE_FONT -> {
                        // 脸名在 raw 偏移 18（16 位 LOGFONT：5 word 尺寸/权重 + 8 字节属性）
                        val face = if (raw.size >= 18) {
                            var end = 18
                            while (end < raw.size && end < 50 && raw[end] != 0.toByte()) end++
                            String(raw, 18, end - 18, Charsets.ISO_8859_1)
                        } else ""
                        val h = kotlin.math.abs(s16(pr[0])).coerceAtLeast(8).toFloat()
                        objs[objs.size] = FontObj(h, face.contains("Symbol", ignoreCase = true))
                    }
                    FN_SELECT_OBJECT -> objs[pr[0]]?.let { ob ->
                        when (ob) {
                            is Paint -> pen.color = ob.color
                            is FontObj -> curFont = ob
                        }
                    }
                    FN_SET_TEXT_COLOR -> textColor = col(pr[0], pr[1])
                    FN_SET_TEXT_ALIGN -> align = pr[0]
                    FN_MOVETO -> if (pr.size >= 2) { cpX = s16(pr[1]); cpY = s16(pr[0]) }
                    FN_LINETO -> if (pr.size >= 2) {
                        val (x2, y2) = s(s16(pr[1]), s16(pr[0]))
                        val (x1, y1) = s(cpX, cpY)
                        canvas.drawLine(x1, y1, x2, y2, pen)
                        cpX = s16(pr[1]); cpY = s16(pr[0])
                    }
                    FN_TEXTOUT -> if (pr.size >= 3) {
                        val cnt = pr[0]
                        if (pr.size >= cnt + 3) {
                            val s2 = mapText(decodeBytes(pr, 1, cnt))
                            val (px, py) = s(s16(pr[cnt + 2]), s16(pr[cnt + 1]))
                            tp.textSize = curFont.height * sc
                            tp.color = textColor
                            tp.textAlign = Paint.Align.LEFT
                            canvas.drawText(s2, px, py, tp)
                        }
                    }
                    FN_EXT_TEXTOUT -> if (pr.size >= 4) {
                        val cnt = pr[2]
                        val opts = pr[3]
                        var i = 4
                        if (opts and 4 != 0) i += 4
                        val nw = (cnt + 1) / 2
                        if (pr.size >= i + nw) {
                            val s2 = mapText(decodeBytes(pr, i, cnt))
                            tp.textSize = curFont.height * sc
                            tp.color = textColor
                            // TA_LEFT=0/RIGHT=2/CENTER=6；0x18 是垂直基线，不是居中
                            tp.textAlign = when (align and 0x6) {
                                2 -> Paint.Align.RIGHT
                                6 -> Paint.Align.CENTER
                                else -> Paint.Align.LEFT
                            }
                            val isCp = align and 1 != 0
                            val px = if (isCp) cpX else s16(pr[1])
                            val py = if (isCp) cpY else s16(pr[0])
                            val dxa = if (pr.size >= i + nw + cnt) pr.copyOfRange(i + nw, i + nw + cnt) else IntArray(0)
                            if (dxa.size == cnt && cnt > 0) {
                                // dx 逐字符步进：EE 的紧排公式靠它，整串自然字宽必叠字
                                var cx = px
                                for (k in 0 until cnt) {
                                    val (dxp, dyp) = s(cx, py)
                                    canvas.drawText(s2[k].toString(), dxp, dyp, tp)
                                    cx += s16(dxa[k])
                                }
                                if (isCp) cpX = cx
                            } else {
                                val (dxp, dyp) = s(px, py)
                                canvas.drawText(s2, dxp, dyp, tp)
                                if (isCp) cpX = px + (cnt * curFont.height * 0.62f).toInt()
                            }
                        }
                    }
                    FN_RECTANGLE, FN_ELLIPSE -> if (pr.size >= 4) {
                        val (x1, y1) = s(s16(pr[0]), s16(pr[1]))
                        val (x2, y2) = s(s16(pr[2]), s16(pr[3]))
                        val rf = RectF(minOf(x1, x2), minOf(y1, y2), maxOf(x1, x2), maxOf(y1, y2))
                        if (fn == FN_RECTANGLE) canvas.drawRect(rf, brush)
                        else canvas.drawOval(rf, brush)
                    }
                    FN_POLYGON, FN_POLYLINE -> if (pr.isNotEmpty()) {
                        val cnt = pr[0]
                        var i = 1; var k = 0
                        val pts = FloatArray(cnt * 4); var n = 0
                        while (i + 1 < pr.size && k < cnt) {
                            val p0 = s(s16(pr[i + 1]), s16(pr[i]))
                            pts[n++] = p0.first; pts[n++] = p0.second
                            i += 2; k++
                        }
                        if (n >= 4) {
                            if (fn == FN_POLYGON) canvas.drawPolygon(pts.copyOf(n), brush)
                            else {
                                var j = 0
                                while (j + 3 < n) {
                                    canvas.drawLine(pts[j], pts[j + 1], pts[j + 2], pts[j + 3], pen)
                                    j += 2
                                }
                            }
                        }
                    }
                }
            }
        }
        return true
    }

    /** Canvas 没有 drawPolygon(float[])——用 Path 补。 */
    private fun Canvas.drawPolygon(pts: FloatArray, paint: Paint) {
        val path = android.graphics.Path()
        path.moveTo(pts[0], pts[1])
        var i = 2
        while (i + 1 < pts.size) { path.lineTo(pts[i], pts[i + 1]); i += 2 }
        path.close()
        drawPath(path, paint)
    }

    private fun parse(wmf: ByteArray): List<Rec>? {
        if (wmf.size < 22) return null
        if (s16a(wmf, 0) != 1 || s16a(wmf, 2) != 9) return null   // 标准头：type=1, headerSize=9 词
        val recs = ArrayList<Rec>()
        var p = 18
        var guard = 0
        while (p + 6 <= wmf.size && guard++ < 1_000_000) {
            val sizeWords = u32(wmf, p)
            val fn = s16a(wmf, p + 4)
            if (sizeWords < 3 || p + sizeWords * 2 > wmf.size) break
            val n = sizeWords - 3
            val pr = IntArray(n)
            for (k in 0 until n) pr[k] = s16a(wmf, p + 6 + k * 2)
            recs.add(Rec(fn, pr, wmf.copyOfRange(p + 6, p + 6 + n * 2)))
            p += sizeWords * 2
        }
        return if (recs.isEmpty()) null else recs
    }

    private fun s16(w: Int): Int = if (w >= 0x8000) w - 0x10000 else w

    private fun s16a(b: ByteArray, i: Int): Int {
        val v = (b[i].toInt() and 0xFF) or ((b[i + 1].toInt() and 0xFF) shl 8)
        return if (v >= 0x8000) v - 0x10000 else v
    }
    private fun u32(b: ByteArray, i: Int): Int =
        (b[i].toInt() and 0xFF) or ((b[i + 1].toInt() and 0xFF) shl 8) or
            ((b[i + 2].toInt() and 0xFF) shl 16) or ((b[i + 3].toInt() and 0xFF) shl 24)
}
