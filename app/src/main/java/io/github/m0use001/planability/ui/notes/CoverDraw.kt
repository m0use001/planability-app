package io.github.m0use001.planability.ui.notes

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin

/**
 * 矢量封面款的绘制辅助，收在 [CoverDraw] 里。
 *
 * ## 为什么要包一层 object（而不是顶层函数）
 * 同包里还有另一条线做的**位图款**封面（`CoverMc.kt`，◆ 图标）。
 * 两条线并行时，顶层函数会直接撞名（同名的 `X`/`Y`/`fillGradV` 互相解析到对方去，
 * 而且报出来的错完全指不到真正的原因）。
 * 收进 object 之后两边各自独立：位图款要自己那套就自己写，互不依赖。
 *
 * 约定不变：全部绘制都在设计空间 **1000 × 1120** 里写坐标，由 [CoverDraw.X] / [CoverDraw.Y] 换算。
 */
object CoverDraw {

    // ---------- 坐标换算 ----------

    fun DrawScope.X(v: Float): Float = v * size.width / 1000f
    fun DrawScope.Y(v: Float): Float = v * size.height / 1120f
    fun DrawScope.R(v: Float): Float = v * size.width / 1000f
    fun DrawScope.O(x: Float, y: Float): Offset = Offset(X(x), Y(y))

    /** 单位圆上第 i/n 个点的角度（度） */
    fun ringAngle(i: Int, n: Int, offsetDeg: Float = 0f): Float = offsetDeg + i * 360f / n

    /** 正八边形（返回 n 个顶点，角度以 offsetDeg 起始）。是扩展函数——内部要用 O() 换算 */
    fun DrawScope.octa(cx: Float, cy: Float, r: Float, n: Int = 8, offsetDeg: Float = 22.5f): List<Offset> =
        (0 until n).map {
            val a = ringAngle(it, n, offsetDeg) * Math.PI.toFloat() / 180f
            O(cx + r * cos(a), cy + r * sin(a))
        }

    /** 极坐标点（角度用度） */
    fun DrawScope.polar(cx: Float, cy: Float, r: Float, deg: Float): Offset {
        val a = deg * Math.PI.toFloat() / 180f
        return O(cx + r * cos(a), cy + r * sin(a))
    }

    // ---------- 基本图形 ----------

    fun DrawScope.fillRect(c: Color, x0: Float, y0: Float, x1: Float, y1: Float) =
        drawRect(c, topLeft = O(x0, y0), size = Size(X(x1 - x0), Y(y1 - y0)))

    fun DrawScope.fillGradV(c0: Color, c1: Color, x0: Float, y0: Float, x1: Float, y1: Float) =
        drawRect(
            Brush.verticalGradient(listOf(c0, c1), startY = Y(y0), endY = Y(y1)),
            topLeft = O(x0, y0), size = Size(X(x1 - x0), Y(y1 - y0))
        )

    /** 多档竖向渐变填充，stops = [(相对位置 0~1, 颜色)] */
    fun DrawScope.fillGradVStops(
        stops: List<Pair<Float, Color>>,
        x0: Float, y0: Float, x1: Float, y1: Float
    ) = drawRect(
        // colorStops 要的是 Array<Pair<Float,Color>>，不是 List
        Brush.verticalGradient(
            colorStops = stops.sortedBy { it.first }
                .map { it.first.coerceIn(0f, 1f) to it.second }
                .toTypedArray(),
            startY = Y(y0), endY = Y(y1)
        ),
        topLeft = O(x0, y0), size = Size(X(x1 - x0), Y(y1 - y0))
    )

    fun DrawScope.fillCircle(c: Color, cx: Float, cy: Float, r: Float) =
        drawCircle(c, radius = R(r), center = O(cx, cy))

    fun DrawScope.strokeCircle(c: Color, cx: Float, cy: Float, r: Float, w: Float) =
        drawCircle(c, radius = R(r), center = O(cx, cy), style = Stroke(width = R(w)))

    fun DrawScope.strokeLine(c: Color, x0: Float, y0: Float, x1: Float, y1: Float, w: Float) =
        drawLine(c, O(x0, y0), O(x1, y1), strokeWidth = R(w))

    fun DrawScope.strokePath(c: Color, pts: List<Offset>, w: Float, closed: Boolean = true) {
        if (pts.size < 2) return
        val p = Path().apply {
            moveTo(pts[0].x, pts[0].y)
            for (i in 1 until pts.size) lineTo(pts[i].x, pts[i].y)
            if (closed) close()
        }
        drawPath(p, c, style = Stroke(width = R(w)))
    }

    /** 闭合多边形的描边（矩形框、菱形框等） */
    fun DrawScope.strokePoly(c: Color, pts: List<Offset>, w: Float, closed: Boolean = true) =
        strokePath(c, pts, w, closed)

    fun DrawScope.fillPoly(c: Color, pts: List<Offset>) {
        if (pts.size < 3) return
        val p = Path().apply {
            moveTo(pts[0].x, pts[0].y)
            for (i in 1 until pts.size) lineTo(pts[i].x, pts[i].y)
            close()
        }
        drawPath(p, c)
    }

    fun DrawScope.fillPolyStroke(c: Color, stroke: Color, pts: List<Offset>, w: Float) {
        if (pts.size < 3) return
        val p = Path().apply {
            moveTo(pts[0].x, pts[0].y)
            for (i in 1 until pts.size) lineTo(pts[i].x, pts[i].y)
            close()
        }
        drawPath(p, c)
        drawPath(p, stroke, style = Stroke(width = R(w)))
    }

    fun DrawScope.px(vararg xy: Float): List<Offset> =
        xy.toList().chunked(2).map { O(it[0], it[1]) }

    /**
     * 扇形/圆弧。角度约定与 PIL 一致：0° 在 3 点钟方向，顺时针为正——
     * 这样预渲染脚本里的角度可以直接照抄，不用逐个换算（换算过一次就会错）。
     */
    fun DrawScope.pieSlice(c: Color, cx: Float, cy: Float, r: Float, a0: Float, a1: Float) {
        val rr = R(r)
        drawArc(
            color = c, startAngle = a0, sweepAngle = a1 - a0, useCenter = true,
            topLeft = O(cx - r, cy - r), size = Size(rr * 2f, rr * 2f)
        )
    }

    fun DrawScope.arcStroke(c: Color, cx: Float, cy: Float, r: Float, a0: Float, a1: Float, w: Float) {
        val rr = R(r)
        drawArc(
            color = c, startAngle = a0, sweepAngle = a1 - a0, useCenter = false,
            topLeft = O(cx - r, cy - r), size = Size(rr * 2f, rr * 2f),
            style = Stroke(width = R(w))
        )
    }

    /** 不填充的扇形轮廓（Art Deco 用） */
    fun DrawScope.pieSliceStroke(c: Color, cx: Float, cy: Float, r: Float, a0: Float, a1: Float, w: Float) =
        arcStroke(c, cx, cy, r, a0, a1, w)

    fun DrawScope.drawRoundRectCompat(c: Color, x0: Float, y0: Float, x1: Float, y1: Float, r: Float) {
        drawRoundRect(
            color = c, topLeft = O(x0, y0), size = Size(X(x1 - x0), Y(y1 - y0)),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(R(r), R(r))
        )
    }

    fun DrawScope.strokeRoundRectCompat(
        c: Color, x0: Float, y0: Float, x1: Float, y1: Float, r: Float, w: Float
    ) {
        drawRoundRect(
            color = c, topLeft = O(x0, y0), size = Size(X(x1 - x0), Y(y1 - y0)),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(R(r), R(r)),
            style = Stroke(width = R(w))
        )
    }

    /** 把已经换算成像素的一串点整体下移 dy 像素（做投影/厚度时用） */
    fun DrawScope.shiftDown(pts: List<Offset>, dyPx: Float): List<Offset> =
        pts.map { Offset(it.x, it.y + dyPx) }

    /** 一段平滑曲线（山脊/水波），用中点二次贝塞尔近似 */
    fun DrawScope.strokeSmoothPoly(c: Color, pts: List<Offset>, w: Float) {
        if (pts.size < 2) return
        val p = Path().apply {
            moveTo(pts[0].x, pts[0].y)
            for (i in 0 until pts.size - 1) {
                val a = pts[i]
                val b = pts[i + 1]
                quadraticTo(a.x, a.y, (a.x + b.x) / 2f, (a.y + b.y) / 2f)
            }
            lineTo(pts[pts.size - 1].x, pts[pts.size - 1].y)
        }
        drawPath(p, c, style = Stroke(width = R(w)))
    }

    /** 点划线（工程图中心线） */
    fun DrawScope.dash(
        x0: Float, y0: Float, x1: Float, y1: Float, c: Color, w: Float,
        on: Float = 26f, off: Float = 18f
    ) {
        val dx = x1 - x0
        val dy = y1 - y0
        val len = kotlin.math.hypot(dx, dy)
        if (len <= 0.001f) return
        val ux = dx / len
        val uy = dy / len
        val period = on + off * 2f
        var t = 0f
        while (t < len) {
            val e = minOf(t + on, len)
            strokeLine(c, x0 + ux * t, y0 + uy * t, x0 + ux * e, y0 + uy * e, w)
            t += period
        }
    }

    /** 山脊线：几段正弦叠加，横向铺满 */
    fun DrawScope.ridgeLine(
        w: Float, baseY: Float, amp: Float, peaks: Int, seed: Int, n: Int = 120
    ): List<Offset> {
        val rnd = Rng(seed)
        val phases = List(peaks) {
            Triple(rnd.range(0f, 6.28f), rnd.range(0.5f, 1.5f), rnd.range(0.3f, 1f))
        }
        return (0..n).map { i ->
            val x = w * i / n.toFloat()
            var y = baseY
            for ((ph, fr, wgt) in phases) {
                y -= amp * wgt * (0.5f + 0.5f * sin(ph + fr * x / w * 6.28318f))
            }
            O(x, y)
        }
    }

    /** 高斯峰山脊（平滑山形用） */
    fun gaussianRidge(peaks: List<Triple<Float, Float, Float>>, floor: Float, w: Int = 50): List<Float> =
        (0 until w).map { x ->
            var y = floor
            for ((cx, hgt, wid) in peaks) y -= hgt * exp(-((x - cx) * (x - cx)) / (2f * wid * wid))
            y
        }

    /** 归一化到 [0,1] */
    fun clamp01(v: Float) = v.coerceIn(0f, 1f)
}

/**
 * 确定性伪随机。封面在网格里会同时画几十个，同一本笔记本每次重组都要长一样，
 * 所以不能用 kotlin.random（它没给种子，重组就会换排版）。
 */
class Rng(seed: Int) {
    private var s = (seed * 1103515245 + 12345) and 0x7fffffff
    fun next(): Float {
        s = (s * 1103515245 + 12345) and 0x7fffffff
        return ((s shr 8) and 0xffffff) / 16777215f
    }

    fun range(a: Float, b: Float): Float = a + (b - a) * next()
    fun int(a: Int, b: Int): Int = a + ((b - a) * next()).toInt().coerceAtMost(b - a)
}

// ============================================================
// 顶层转发：让 15 款矢量封面的代码读起来和以前一样短。
// 只做转发，不放实现——实现全在 CoverDraw 里。
// ============================================================

private typealias CD = CoverDraw

fun DrawScope.X(v: Float) = CD.run { X(v) }
fun DrawScope.Y(v: Float) = CD.run { Y(v) }
fun DrawScope.R(v: Float) = CD.run { R(v) }
fun DrawScope.O(x: Float, y: Float) = CD.run { O(x, y) }
fun DrawScope.ringAngle(i: Int, n: Int, offsetDeg: Float = 0f) = CD.ringAngle(i, n, offsetDeg)
fun DrawScope.octa(cx: Float, cy: Float, r: Float, n: Int = 8, offsetDeg: Float = 22.5f) =
    CD.run { octa(cx, cy, r, n, offsetDeg) }
fun DrawScope.polar(cx: Float, cy: Float, r: Float, deg: Float) = CD.run { polar(cx, cy, r, deg) }
fun DrawScope.fillRect(c: Color, x0: Float, y0: Float, x1: Float, y1: Float) =
    CD.run { fillRect(c, x0, y0, x1, y1) }
fun DrawScope.fillGradV(c0: Color, c1: Color, x0: Float, y0: Float, x1: Float, y1: Float) =
    CD.run { fillGradV(c0, c1, x0, y0, x1, y1) }
fun DrawScope.fillGradVStops(s: List<Pair<Float, Color>>, x0: Float, y0: Float, x1: Float, y1: Float) =
    CD.run { fillGradVStops(s, x0, y0, x1, y1) }
fun DrawScope.fillCircle(c: Color, cx: Float, cy: Float, r: Float) = CD.run { fillCircle(c, cx, cy, r) }
fun DrawScope.strokeCircle(c: Color, cx: Float, cy: Float, r: Float, w: Float) =
    CD.run { strokeCircle(c, cx, cy, r, w) }
fun DrawScope.strokeLine(c: Color, x0: Float, y0: Float, x1: Float, y1: Float, w: Float) =
    CD.run { strokeLine(c, x0, y0, x1, y1, w) }
fun DrawScope.strokePath(c: Color, pts: List<Offset>, w: Float, closed: Boolean = true) =
    CD.run { strokePath(c, pts, w, closed) }
fun DrawScope.strokePoly(c: Color, pts: List<Offset>, w: Float, closed: Boolean = true) =
    CD.run { strokePoly(c, pts, w, closed) }
fun DrawScope.fillPoly(c: Color, pts: List<Offset>) = CD.run { fillPoly(c, pts) }
fun DrawScope.fillPolyStroke(c: Color, s: Color, pts: List<Offset>, w: Float) =
    CD.run { fillPolyStroke(c, s, pts, w) }
fun DrawScope.px(vararg xy: Float): List<Offset> = CD.run { px(*xy) }
fun DrawScope.pieSlice(c: Color, cx: Float, cy: Float, r: Float, a0: Float, a1: Float) =
    CD.run { pieSlice(c, cx, cy, r, a0, a1) }
fun DrawScope.arcStroke(c: Color, cx: Float, cy: Float, r: Float, a0: Float, a1: Float, w: Float) =
    CD.run { arcStroke(c, cx, cy, r, a0, a1, w) }
fun DrawScope.pieSliceStroke(c: Color, cx: Float, cy: Float, r: Float, a0: Float, a1: Float, w: Float) =
    CD.run { pieSliceStroke(c, cx, cy, r, a0, a1, w) }
fun DrawScope.drawRoundRectCompat(c: Color, x0: Float, y0: Float, x1: Float, y1: Float, r: Float) =
    CD.run { drawRoundRectCompat(c, x0, y0, x1, y1, r) }
fun DrawScope.strokeRoundRectCompat(c: Color, x0: Float, y0: Float, x1: Float, y1: Float, r: Float, w: Float) =
    CD.run { strokeRoundRectCompat(c, x0, y0, x1, y1, r, w) }
fun DrawScope.shiftDown(pts: List<Offset>, dyPx: Float) = CD.run { shiftDown(pts, dyPx) }
fun DrawScope.strokeSmoothPoly(c: Color, pts: List<Offset>, w: Float) = CD.run { strokeSmoothPoly(c, pts, w) }
fun DrawScope.dash(x0: Float, y0: Float, x1: Float, y1: Float, c: Color, w: Float, on: Float = 26f, off: Float = 18f) =
    CD.run { dash(x0, y0, x1, y1, c, w, on, off) }
fun DrawScope.ridgeLine(w: Float, baseY: Float, amp: Float, peaks: Int, seed: Int, n: Int = 120): List<Offset> =
    CD.run { ridgeLine(w, baseY, amp, peaks, seed, n) }