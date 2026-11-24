package io.github.m0use001.planability.widget

import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathNode
import androidx.compose.ui.graphics.vector.VectorGroup
import androidx.compose.ui.graphics.vector.VectorPath
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 把 Compose [ImageVector]（material-icons-extended 官方图标）画到小组件位图上。
 *
 * 为什么存在：Glance 小组件走位图渲染（android.graphics.Canvas，字体/Compose 都吃不到），
 * 行首图标因此曾用手绘 Path 近似；用户要求换官方 Material 图标，而依赖里的
 * `Icons.Filled.*` 是 Compose ImageVector，无法直接进 android Canvas——
 * 这里把 PathNode 手工翻译成 android Path：相对命令→绝对（跟踪当前点）、
 * S/T 反射控制点、SVG 端点弧→中心弧（compose 的 ArcTo 是端点参数，
 * android 用 addArc(椭圆, 起角, 扫角)，带 x 轴旋转时用矩阵旋转子路径）。
 *
 * 只支持**填充型**图标（material filled 集全是填充；stroke 参数忽略）；
 * 组级 transform（rotate/scale/translate）常用 material 图标未用到，忽略。
 * 2026-10-31：替代 TodayStatsArt 的手绘行首图标（用户「组件应该用 material_icon」）。
 */
object MaterialIconPainter {

    fun draw(canvas: Canvas, icon: ImageVector, cx: Float, cy: Float, size: Float, ink: Int) {
        val vw = icon.viewportWidth.coerceAtLeast(0.1f)
        val vh = icon.viewportHeight.coerceAtLeast(0.1f)
        val scale = size / maxOf(vw, vh)
        val path = Path()
        appendGroup(path, icon.root)
        val m = Matrix()
        m.postScale(scale, scale)
        m.postTranslate(cx - vw * scale / 2f, cy - vh * scale / 2f)
        path.transform(m)
        canvas.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ink })
    }

    private fun appendGroup(path: Path, group: VectorGroup) {
        for (node in group) {
            when (node) {
                is VectorPath -> path.addPath(buildSubPath(node))
                is VectorGroup -> appendGroup(path, node)
            }
        }
    }

    private fun buildSubPath(node: VectorPath): Path {
        val sub = Path()
        sub.fillType = when (node.pathFillType) {
            PathFillType.EvenOdd -> android.graphics.Path.FillType.EVEN_ODD
            else -> android.graphics.Path.FillType.WINDING
        }
        NodeWalker(sub).run(node.pathData)
        return sub
    }

    /** SVG 路径状态机：PathNode → android Path（相对命令展开、S/T 反射、端点弧转换） */
    private class NodeWalker(val path: Path) {
        var x = 0f; var y = 0f      // 当前点
        var sx = 0f; var sy = 0f    // 子路径起点（Close 回归）
        var rcx = 0f; var rcy = 0f  // 上一个三次曲线的第二控制点（S 反射基准）
        var qcx = 0f; var qcy = 0f  // 上一个二次曲线的控制点（T 反射基准）
        var prev = ' '              // 'C' / 'Q'，反射只认同族前驱

        fun run(nodes: List<PathNode>) {
            for (n in nodes) when (n) {
                is PathNode.Close -> { path.close(); x = sx; y = sy; prev = ' ' }
                is PathNode.MoveTo -> moveTo(n.x, n.y)
                is PathNode.RelativeMoveTo -> moveTo(x + n.dx, y + n.dy)
                is PathNode.LineTo -> line(n.x, n.y)
                is PathNode.RelativeLineTo -> line(x + n.dx, y + n.dy)
                is PathNode.HorizontalTo -> line(n.x, y)
                is PathNode.RelativeHorizontalTo -> line(x + n.dx, y)
                is PathNode.VerticalTo -> line(x, n.y)
                is PathNode.RelativeVerticalTo -> line(x, y + n.dy)
                is PathNode.CurveTo -> curve(n.x1, n.y1, n.x2, n.y2, n.x3, n.y3)
                is PathNode.RelativeCurveTo ->
                    curve(x + n.dx1, y + n.dy1, x + n.dx2, y + n.dy2, x + n.dx3, y + n.dy3)
                is PathNode.ReflectiveCurveTo -> {
                    val (rx, ry) = reflectC()
                    curve(rx, ry, n.x1, n.y1, n.x2, n.y2)
                }
                is PathNode.RelativeReflectiveCurveTo -> {
                    val (rx, ry) = reflectC()
                    curve(rx, ry, x + n.dx1, y + n.dy1, x + n.dx2, y + n.dy2)
                }
                is PathNode.QuadTo -> quad(n.x1, n.y1, n.x2, n.y2)
                is PathNode.RelativeQuadTo -> quad(x + n.dx1, y + n.dy1, x + n.dx2, y + n.dy2)
                is PathNode.ReflectiveQuadTo -> {
                    val (qx, qy) = reflectQ()
                    quad(qx, qy, n.x, n.y)
                }
                is PathNode.RelativeReflectiveQuadTo -> {
                    val (qx, qy) = reflectQ()
                    quad(qx, qy, x + n.dx, y + n.dy)
                }
                is PathNode.ArcTo ->
                    arc(n.horizontalEllipseRadius, n.verticalEllipseRadius, n.theta,
                        n.isMoreThanHalf, n.isPositiveArc, n.arcStartX, n.arcStartY)
                is PathNode.RelativeArcTo ->
                    arc(n.horizontalEllipseRadius, n.verticalEllipseRadius, n.theta,
                        n.isMoreThanHalf, n.isPositiveArc, x + n.arcStartDx, y + n.arcStartDy)
            }
        }

        private fun moveTo(nx: Float, ny: Float) {
            path.moveTo(nx, ny); x = nx; y = ny; sx = nx; sy = ny; prev = ' '
        }

        private fun line(nx: Float, ny: Float) {
            path.lineTo(nx, ny); x = nx; y = ny; prev = 'L'
        }

        private fun curve(x1: Float, y1: Float, x2: Float, y2: Float, ex: Float, ey: Float) {
            path.cubicTo(x1, y1, x2, y2, ex, ey)
            rcx = x2; rcy = y2; x = ex; y = ey; prev = 'C'
        }

        private fun quad(x1: Float, y1: Float, ex: Float, ey: Float) {
            path.quadTo(x1, y1, ex, ey)
            qcx = x1; qcy = y1; x = ex; y = ey; prev = 'Q'
        }

        private fun reflectC(): Pair<Float, Float> =
            if (prev == 'C') Pair(2 * x - rcx, 2 * y - rcy) else Pair(x, y)

        private fun reflectQ(): Pair<Float, Float> =
            if (prev == 'Q') Pair(2 * x - qcx, 2 * y - qcy) else Pair(x, y)

        /** SVG 端点弧 → 中心弧（W3C F.6.5）；带 x 轴旋转时旋转子路径再并入 */
        private fun arc(
            a: Float, b: Float, thetaDeg: Float,
            large: Boolean, sweep: Boolean, ex: Float, ey: Float
        ) {
            var rx = kotlin.math.abs(a)
            var ry = kotlin.math.abs(b)
            if (rx == 0f || ry == 0f) { line(ex, ey); return }
            val phi = Math.toRadians(thetaDeg.toDouble())
            val cosP = cos(phi); val sinP = sin(phi)
            val dx2 = (x - ex) / 2f; val dy2 = (y - ey) / 2f
            val x1p = cosP * dx2 + sinP * dy2
            val y1p = -sinP * dx2 + cosP * dy2
            val lambda = x1p * x1p / (rx * rx) + y1p * y1p / (ry * ry)
            if (lambda > 1f) {
                val s = sqrt(lambda); rx *= s.toFloat(); ry *= s.toFloat()
            }
            val sign = if (large != sweep) 1f else -1f
            val num = rx * rx * ry * ry - rx * rx * y1p * y1p - ry * ry * x1p * x1p
            val den = rx * rx * y1p * y1p + ry * ry * x1p * x1p
            val ratio = (num / den).coerceAtLeast(0.0)
            val co = (sign.toDouble() * sqrt(ratio)).toFloat()
            val cxp = co * rx * y1p / ry
            val cyp = -co * ry * x1p / rx
            val ccx = (cosP * cxp - sinP * cyp).toFloat() + (x + ex) / 2f
            val ccy = (sinP * cxp + cosP * cyp).toFloat() + (y + ey) / 2f
            val ux = (x1p - cxp) / rx; val uy = (y1p - cyp) / ry
            val vx = (-x1p - cxp) / rx; val vy = (-y1p - cyp) / ry
            val dot = (ux * vx + uy * vy).toDouble()
            val uLen = hypot(ux.toDouble(), uy.toDouble())
            val vLen = hypot(vx.toDouble(), vy.toDouble())
            var sweepRad = acos((dot / (uLen * vLen)).coerceIn(-1.0, 1.0))
            if (!sweep && sweepRad > 0) sweepRad = 2 * Math.PI - sweepRad
            if (sweep && sweepRad < 0) sweepRad += 2 * Math.PI
            val start = Math.toDegrees(atan2(uy.toDouble(), ux.toDouble()))
            val sweepDeg = Math.toDegrees(sweepRad)
            val oval = RectF(ccx - rx, ccy - ry, ccx + rx, ccy + ry)
            if (thetaDeg == 0f) {
                path.arcTo(oval, start.toFloat(), sweepDeg.toFloat())
            } else {
                val tmp = Path()
                tmp.arcTo(oval, start.toFloat(), sweepDeg.toFloat())
                val rm = Matrix()
                rm.postRotate(thetaDeg, ccx, ccy)
                tmp.transform(rm)
                path.addPath(tmp)
            }
            x = ex; y = ey; prev = 'A'
        }
    }
}
