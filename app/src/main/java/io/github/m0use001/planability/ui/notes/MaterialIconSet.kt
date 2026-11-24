package io.github.m0use001.planability.ui.notes

import android.graphics.Paint
import android.graphics.Path as AndroidPath
import android.graphics.PathMeasure
import androidx.compose.material.icons.Icons
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathNode
import androidx.compose.ui.graphics.vector.VectorGroup
import androidx.compose.ui.graphics.vector.VectorNode

/**
 * Material 图标集合：全部 filled 图标，反射枚举而来。
 *
 * ## 为什么反射而不是手写清单
 * `material-icons-extended` 有三千多个图标，写一份清单既冗长又会随依赖升级而过时。
 *
 * 但图标**不在** `Icons.Filled` 上：源码里每个图标都是顶层扩展属性
 * ```kotlin
 * public val Icons.Filled.AddHome: ImageVector get() { ... }
 * ```
 * 编译成 `filled.AddHomeKt.getAddHome(Icons.Filled)` 这个**静态方法**。
 * 按字段类型、按无参 getter 去 `Icons.Filled::class` 上找，都只会得到空集
 * （真机日志 `MAT=0` 就是这么来的）。所以：
 *  1. [NAMES] 扫 dex 类名（只读字符串，不加载类，首次几十毫秒）；
 *  2. [vectorOf] 用到哪个图标才 `Class.forName` + 调那个静态 getter，结果缓存。
 * 新增/改名的图标自动跟上，代价是打开选择器时那一次 dex 扫描。
 *
 * ## 为什么不落成 PNG，也不用 Compose 的 `draw(ImageVector)`
 * 这些图标是矢量，画到封面时直接走原生路径，尺寸任意不糊，
 * 也不用把三千张图打进 APK（material-icons-extended 已在依赖里，体积早就付过了）。
 * Compose 的 `draw(ImageVector)` 扩展在 1.13 里不在 `ImageVectorKt` 中（实测找不到），
 * 而 `ImageVector` 本体、`getRoot()`、`VectorGroup`（实现了 `Iterable<VectorNode>`）与
 * `PathNode` 全是 public —— 只有 `PathComponent` 是 internal（拿不到 pathData）。
 * 所以这里自己遍历 group、自己把 `PathNode` 解析成 `android.graphics.Path`。
 *
 * ## 下标区间
 * ```
 * [0, COUNT)   Material 图标
 * ```
 * `coverIcon` 一个 Int 就是图标在 [NAMES] 里的下标。
 * 保留 [offset]/[isMaterial] 两个入口，是为了让调用点的语义不变。
 */
object MaterialIconSet {

    /** 图标所在的包（顶层扩展属性按文件归在这里，每个图标一个 `XxxKt` 类）。 */
    private const val PKG = "androidx.compose.material.icons.filled."

    /**
     * 全部 filled 图标名（去掉 `Kt` 后缀），按字母序——
     * 顺序稳定，同一份依赖里不会随升级乱跳。
     *
     * 只是**类名字符串**：`Class.forName` 一次都没有发生，所以这张表拿得起。
     * 真正的 ImageVector 延迟到 [vectorOf]，看哪个才构造哪个。
     */
    val NAMES: List<String> by lazy(LazyThreadSafetyMode.NONE) { scanNames() }

    /**
     * 扫 dex 拿图标类名。
     *
     * `DexFile.entries()` 返回的是字符串，代价只是遍历一遍（几十毫秒）；
     * 不能用 `ClassLoader`——它没有"列出我有哪些类"的入口。
     * 顺带把 `automirrored.filled` 排除在外：前缀不同，天然不会混进来。
     */
    private fun scanNames(): List<String> {
        // Application 用反射拿：android.app.ActivityThread 不在公开 SDK 里，直接 import 编不过。
        val app = runCatching {
            Class.forName("android.app.ActivityThread")
                .getMethod("currentApplication")
                .invoke(null)
        }.getOrNull() as? android.content.Context ?: return emptyList()

        val apks = listOfNotNull(app.applicationInfo.sourceDir) +
            (app.applicationInfo.splitSourceDirs?.toList() ?: emptyList())

        val out = HashSet<String>(4096)
        for (apk in apks) {
            runCatching {
                val dex = dalvik.system.DexFile(apk)
                try {
                    val it = dex.entries()
                    while (it.hasMoreElements()) {
                        val cls = it.nextElement() ?: continue
                        if (!cls.startsWith(PKG) || !cls.endsWith("Kt")) continue
                        val name = cls.substring(PKG.length, cls.length - 2)
                        // 内部类（Foo$Bar）与空名都不算图标
                        if (name.isEmpty() || name.indexOf('$') >= 0) continue
                        out += name
                    }
                } finally {
                    dex.close()
                }
            }
        }
        return out.sorted()
    }

    val COUNT: Int get() = NAMES.size

    /**
     * 反射取一个图标的 ImageVector：类 + 一个「以 Icons.Filled 为唯一参数」的静态方法。
     *
     * `getOrPut` 不行——它对算出 null 的 key 会一直重算，而这里失败的图标
     * 每次绘制都要重试一遍。所以自己用 `containsKey` 判缓存，把 null 也记下来。
     */
    private val vectors = HashMap<Int, ImageVector?>()

    fun vectorOf(entry: Int): ImageVector? {
        if (vectors.containsKey(entry)) return vectors[entry]
        val name = NAMES.getOrNull(entry)
        val v = if (name == null) null else runCatching {
            val cls = Class.forName(PKG + name + "Kt")
            val m = cls.declaredMethods.firstOrNull { f ->
                f.returnType == ImageVector::class.java &&
                    f.parameterCount == 1 &&
                    f.parameterTypes[0] == Icons.Filled::class.java
            } ?: return@runCatching null
            m.invoke(null, Icons.Filled) as? ImageVector
        }.getOrNull()
        vectors[entry] = v
        return v
    }

    /** Material 图标在 `coverIcon` 里的起点。位图素材剔除后归零。 */
    val offset: Int get() = 0

    fun isMaterial(index: Int): Boolean = index >= offset

    fun entryOf(index: Int): Int = (index - offset).coerceIn(0, (COUNT - 1).coerceAtLeast(0))

    /**
     * 在 `(cx, cy)` 中心、边长 [cellDesign] 的范围内画一个 Material 图标。
     *
     * [cellDesign] 与 [cx]/[cy] 都是**设计空间**坐标，内部走 X() 换算——
     * 与其余封面款同一套约定，见 [drawCoverIcon]。
     * 高度也用 X() 而不是 Y()：Y() 的分母是 1120、封面 aspectRatio 却按 1000/1120 之外的
     * 比例出现过，两者不一致时 Y() 会比 X() 大，图标被纵向拉成竖条。
     *
     * @param tint 图标颜色。这些图标是单色矢量，默认黑色；画在深底上要传亮色。
     */
    fun DrawScope.drawMaterialIcon(
        index: Int,
        cellDesign: Float,
        cx: Float,
        cy: Float,
        tint: Color? = null,
        alpha: Float = 1f
    ) {
        val entry = entryOf(index)
        val vector = vectorOf(entry) ?: return

        val side = X(cellDesign)   // 统一按水平比例换算，保持正方形
        val tlX = X(cx) - side / 2f
        val tlY = Y(cy) - side / 2f
        val scale = side / vector.viewportWidth

        val paint = Paint().apply {
            // Material 图标是矢量细线，抗锯齿会把 1px 的线糊成灰毛边；关掉后边缘干净。
            isAntiAlias = false
            color = (tint ?: Color.Black).toArgb()
            this.alpha = (alpha * 255f).toInt().coerceIn(0, 255)
        }

        drawIntoCanvas { canvas ->
            // Compose 的 drawIntoCanvas 给的是 androidx Canvas；转原生才能画 Path。
            val nc: android.graphics.Canvas = canvas.nativeCanvas
            nc.save()
            nc.translate(tlX, tlY)
            nc.scale(scale, scale)
            drawGroup(nc, vector.root, paint)
            nc.restore()
        }
    }

    /**
     * 递归画一个 group：先应用它自身的变换，再画它的 path 与子 group。
     *
     * group 的变换顺序与 Compose 一致（pivot 点的 rotation、scale、translate），
     * 填充图标基本只有一层 root，嵌套 group 极少见，但递归能保证不画错。
     */
    private fun drawGroup(nc: android.graphics.Canvas, g: VectorGroup, paint: Paint) {
        val hasXform = g.rotation != 0f || g.scaleX != 1f || g.scaleY != 1f ||
            g.translationX != 0f || g.translationY != 0f
        nc.save()
        if (hasXform) {
            nc.translate(g.translationX, g.translationY)
            if (g.scaleX != 1f || g.scaleY != 1f) nc.scale(g.scaleX, g.scaleY)
            if (g.rotation != 0f) nc.rotate(g.rotation, g.pivotX, g.pivotY)
        }
        for (node in g) {
            if (node is VectorGroup) {
                drawGroup(nc, node, paint)
            } else {
                // path 数据在 PathComponent 里，而它在源码里是 internal（编译期不可见），
                // 但运行时是 public 类、`getPathData()` 也是 public 方法。
                // 拿不到它就只能画出空图——ImageVector 根本没有公开的 paths 访问器，
                // VectorGroup 自身只有 clipPathData（那是裁剪用的）。
                val nodes = runCatching {
                    @Suppress("UNCHECKED_CAST")
                    node.javaClass.getMethod("getPathData").invoke(node) as? List<PathNode>
                }.getOrNull()
                if (!nodes.isNullOrEmpty()) {
                    nc.drawPath(pathOf(nodes, fillTypeOf(node)), paint)
                }
            }
        }
        nc.restore()
    }

    /**
     * 把一组 `PathNode` 解析成 `android.graphics.Path`。
     *
     * 这里是手写的——Compose 没把 `PathParser` 的 public 解析函数暴露出来，
     * 但 `PathNode` 的七个子类都是 public，按类别分支即可，语义直白。
     * 用 android.graphics.Path 是因为原生 Canvas 只吃它。
     */
    /**
     * 每条 path 的填充规则：Material 里两种都有（EvenOdd 的必须挖空）。
     * `VectorPath.fillType` 是 public，但枚举在 Compose 侧，走名字比对；
     * 反射不到就退回 WINDING——最坏是该镂空的地方没镂空，比画碎好。
     */
    private fun fillTypeOf(node: VectorNode): android.graphics.Path.FillType {
        val name = runCatching { node.javaClass.getMethod("getFillType").invoke(node) }
            .getOrNull()?.toString() ?: return android.graphics.Path.FillType.WINDING
        return if (name.contains("EvenOdd")) android.graphics.Path.FillType.EVEN_ODD
        else android.graphics.Path.FillType.WINDING
    }

    /**
     * 画一条 SVG 弧：把「终点式」参数转成 Android 的「圆心式」arcTo。
     *
     * Compose 的 [PathNode.ArcTo]/[PathNode.RelativeArcTo] 只给终点、椭圆半径、
     * x 轴转角、两个布尔旗标——没有圆心也没有起始角，Android 的
     * `Path.arcTo` 又恰好反过来，所以中间必须走一遍 SVG 规范 F.6.5 的参数转换。
     *
     * 拿不准时**宁可少画不能跳走**：rx/ry 为 0 直接直线到终点。
     */
    private fun drawSvgArc(
        p: AndroidPath,
        x1: Float, y1: Float,          // 当前点
        x2: Float, y2: Float,          // 终点
        rxIn: Float, ryIn: Float,
        thetaDeg: Float,               // x 轴转角（度）
        largeArc: Boolean,
        positive: Boolean
    ) {
        val rx = kotlin.math.abs(rxIn)
        val ry = kotlin.math.abs(ryIn)
        if (rx == 0f || ry == 0f || (x1 == x2 && y1 == y2)) {
            p.lineTo(x2, y2)
            return
        }
        val phi = Math.toRadians(thetaDeg.toDouble())
        val cosP = kotlin.math.cos(phi)
        val sinP = kotlin.math.sin(phi)
        val dx = (x1 - x2) / 2.0
        val dy = (y1 - y2) / 2.0
        val x1p = cosP * dx + sinP * dy
        val y1p = -sinP * dx + cosP * dy

        // 半径不够大时按比例放大（F.6.6）
        var rrX = rx.toDouble()
        var rrY = ry.toDouble()
        val lam = (rrX * rrX * rrY * rrY - rrX * rrX * y1p * y1p - rrY * rrY * x1p * x1p) /
            (rrX * rrX * y1p * y1p + rrY * rrY * x1p * x1p)
        if (lam > 0.0) {
            val s = kotlin.math.sqrt(lam)
            rrX *= s
            rrY *= s
        }

        val sign = if (largeArc == positive) -1.0 else 1.0
        val num = rrX * rrX * rrY * rrY - rrX * rrX * y1p * y1p - rrY * rrY * x1p * x1p
        val den = rrX * rrX * y1p * y1p + rrY * rrY * x1p * x1p
        val co = sign * kotlin.math.sqrt(kotlin.math.max(0.0, num / den))
        val cxp = co * (rrX * y1p / rrY)
        val cyp = -co * (rrY * x1p / rrX)
        val cx = cosP * cxp - sinP * cyp + (x1 + x2) / 2.0
        val cy = sinP * cxp + cosP * cyp + (y1 + y2) / 2.0

        fun angle(ux: Double, uy: Double, vx: Double, vy: Double): Double {
            val d = (ux * vx + uy * vy) /
                (kotlin.math.hypot(ux, uy) * kotlin.math.hypot(vx, vy))
            val a = kotlin.math.acos(d.coerceIn(-1.0, 1.0))
            return if (ux * vy - uy * vx < 0) -a else a
        }
        val theta1 = angle(1.0, 0.0, (x1p - cxp) / rrX, (y1p - cyp) / rrY)
        var sweep = angle((x1p - cxp) / rrX, (y1p - cyp) / rrY,
            (-x1p - cxp) / rrX, (-y1p - cyp) / rrY)
        if (!positive && sweep > 0) sweep -= 2 * Math.PI
        if (positive && sweep < 0) sweep += 2 * Math.PI

        // ---- 世界系里取圆上一点与切向（把 phi 旋转加回去）----
        fun ptAt(a: Double): DoubleArray {
            val ux = rrX * kotlin.math.cos(a)
            val uy = rrY * kotlin.math.sin(a)
            return doubleArrayOf(cosP * ux - sinP * uy + cx, sinP * ux + cosP * uy + cy)
        }

        val startPt = ptAt(theta1)
        // 自检：算出来的起点必须落在当前点上。对不上说明中间哪一步换算错了，
        // 宁可退回直线（形状还能认），也不要画一条碎裂的路径出去。
        if (kotlin.math.hypot(startPt[0] - x1, startPt[1] - y1) >
            kotlin.math.max(rrX, rrY) * 0.5
        ) {
            p.lineTo(x2, y2)
            return
        }

        // 每段最多 90°：cubic 的 k = 4/3·tan(Δ/4)，段越大误差越明显
        val segCount = kotlin.math.max(
            1, kotlin.math.ceil(kotlin.math.abs(sweep) / (Math.PI / 2)).toInt()
        )
        val seg = sweep / segCount
        var a = theta1
        repeat(segCount) {
            val a1 = a + seg
            val s = ptAt(a)
            val e = ptAt(a1)
            // 端点切向（未旋转的椭圆切线），再按 phi 转到世界系
            val t0x = -rrX * kotlin.math.sin(a)
            val t0y = rrY * kotlin.math.cos(a)
            val t1x = -rrX * kotlin.math.sin(a1)
            val t1y = rrY * kotlin.math.cos(a1)
            val w0x = cosP * t0x - sinP * t0y
            val w0y = sinP * t0x + cosP * t0y
            val w1x = cosP * t1x - sinP * t1y
            val w1y = sinP * t1x + cosP * t1y
            val k = 4.0 / 3.0 * kotlin.math.tan((a1 - a) / 4.0)
            p.cubicTo(
                (s[0] + k * w0x).toFloat(), (s[1] + k * w0y).toFloat(),
                (e[0] - k * w1x).toFloat(), (e[1] - k * w1y).toFloat(),
                e[0].toFloat(), e[1].toFloat()
            )
            a = a1
        }
        // 最后一段的理论终点就是 SVG 的终点；浮点误差置零，避免收尾时留一条发丝
        p.lineTo(x2, y2)
    }

    private fun pathOf(nodes: List<PathNode>, fillType: android.graphics.Path.FillType = android.graphics.Path.FillType.WINDING): AndroidPath {
        val p = AndroidPath()
        p.fillType = fillType
        var cx = 0f
        var cy = 0f
        var startX = 0f
        var startY = 0f

        // 反射曲线（S/s/T/t）的基准是**上一个曲线的第二控制点**，不是子路径起点。
        // 前一个命令不是曲线时按 SVG 规范「反射点 = 当前点」，这里靠把它设成当前点来等价。
        var lastC2x = 0f
        var lastC2y = 0f

        fun anchor(x: Float, y: Float) {
            startX = x; startY = y
            lastC2x = x; lastC2y = y
        }

        fun nonCurve() {
            lastC2x = cx; lastC2y = cy
        }

        for (n in nodes) {
            when (n) {
                is PathNode.MoveTo -> {
                    cx = n.x; cy = n.y
                    p.moveTo(cx, cy); anchor(cx, cy)
                }
                is PathNode.LineTo -> {
                    cx = n.x; cy = n.y
                    p.lineTo(cx, cy); nonCurve()
                }
                is PathNode.HorizontalTo -> {
                    cx = n.x
                    p.lineTo(cx, cy); nonCurve()
                }
                is PathNode.VerticalTo -> {
                    cy = n.y
                    p.lineTo(cx, cy); nonCurve()
                }
                is PathNode.RelativeMoveTo -> {
                    cx += n.dx; cy += n.dy
                    p.moveTo(cx, cy); anchor(cx, cy)
                }
                is PathNode.RelativeLineTo -> {
                    cx += n.dx; cy += n.dy
                    p.lineTo(cx, cy); nonCurve()
                }
                is PathNode.RelativeHorizontalTo -> {
                    cx += n.dx
                    p.lineTo(cx, cy); nonCurve()
                }
                is PathNode.RelativeVerticalTo -> {
                    cy += n.dy
                    p.lineTo(cx, cy); nonCurve()
                }
                is PathNode.CurveTo -> {
                    p.cubicTo(n.x1, n.y1, n.x2, n.y2, n.x3, n.y3)
                    cx = n.x3; cy = n.y3
                    lastC2x = n.x2; lastC2y = n.y2
                }
                is PathNode.RelativeCurveTo -> {
                    // 终点字段是 dx3/dy3（不是 dx/dy）——RelativeCurveTo 有三对值
                    val sx = cx
                    val sy = cy
                    p.cubicTo(
                        sx + n.dx1, sy + n.dy1,
                        sx + n.dx2, sy + n.dy2,
                        sx + n.dx3, sy + n.dy3
                    )
                    cx += n.dx3; cy += n.dy3
                    lastC2x = sx + n.dx2; lastC2y = sy + n.dy2
                }
                is PathNode.QuadTo -> {
                    p.quadTo(n.x1, n.y1, n.x2, n.y2)
                    cx = n.x2; cy = n.y2
                    lastC2x = n.x1; lastC2y = n.y1
                }
                is PathNode.RelativeQuadTo -> {
                    val qx = cx + n.dx1
                    val qy = cy + n.dy1
                    cx += n.dx2; cy += n.dy2
                    p.quadTo(qx, qy, cx, cy)
                    lastC2x = qx; lastC2y = qy
                }
                is PathNode.ReflectiveCurveTo -> {
                    // `S x1 y1 x2 y2`：(x1,y1) 是**第二控制点**，(x2,y2) 才是终点。
                    // 旧实现把 n.x1 当成终点、n.x2/y2 整个丢掉——圆形轮廓因此画不闭合，
                    // 真机上表现为封面上的图标轮廓扭曲、多出尖刺。
                    val c1x = 2f * cx - lastC2x
                    val c1y = 2f * cy - lastC2y
                    p.cubicTo(c1x, c1y, n.x1, n.y1, n.x2, n.y2)
                    cx = n.x2; cy = n.y2
                    lastC2x = n.x1; lastC2y = n.y1
                }
                is PathNode.RelativeReflectiveCurveTo -> {
                    // 同上，相对版：(dx1,dy1) 第二控制点、(dx2,dy2) 相对终点。
                    val c1x = 2f * cx - lastC2x
                    val c1y = 2f * cy - lastC2y
                    val c2x = cx + n.dx1
                    val c2y = cy + n.dy1
                    val ex = cx + n.dx2
                    val ey = cy + n.dy2
                    p.cubicTo(c1x, c1y, c2x, c2y, ex, ey)
                    cx = ex; cy = ey
                    lastC2x = c2x; lastC2y = c2y
                }
                is PathNode.ReflectiveQuadTo -> {
                    // `T x y`：终点 (x,y)，控制点由上一个控制点反射得到。
                    val qx = 2f * cx - lastC2x
                    val qy = 2f * cy - lastC2y
                    p.quadTo(qx, qy, n.x, n.y)
                    cx = n.x; cy = n.y
                    lastC2x = qx; lastC2y = qy
                }
                is PathNode.RelativeReflectiveQuadTo -> {
                    val qx = 2f * cx - lastC2x
                    val qy = 2f * cy - lastC2y
                    cx += n.dx; cy += n.dy
                    p.quadTo(qx, qy, cx, cy)
                    lastC2x = qx; lastC2y = qy
                }
                is PathNode.ArcTo -> {
                    // arcStartX/Y 是绝对终点（名字有误导，实际是 SVG `A` 的 x,y）。
                    val nx = n.arcStartX
                    val ny = n.arcStartY
                    drawSvgArc(p, cx, cy, nx, ny,
                        n.horizontalEllipseRadius, n.verticalEllipseRadius,
                        n.theta, n.isMoreThanHalf, n.isPositiveArc)
                    cx = nx; cy = ny
                    nonCurve()
                }
                is PathNode.RelativeArcTo -> {
                    // 相对终点 = 当前点 + 偏移。旧版写成 `cx = arcStartDx`（拿偏移当绝对值），
                    // path 直接跳到左上角，封面上那条飞出去的白线就是它。
                    val nx = cx + n.arcStartDx
                    val ny = cy + n.arcStartDy
                    drawSvgArc(p, cx, cy, nx, ny,
                        n.horizontalEllipseRadius, n.verticalEllipseRadius,
                        n.theta, n.isMoreThanHalf, n.isPositiveArc)
                    cx = nx; cy = ny
                    nonCurve()
                }
                is PathNode.Close -> {
                    p.close()
                    cx = startX; cy = startY
                    nonCurve()
                }
            }
        }
        return p
    }
}

/** Compose 的 Color -> ARGB int。 */
private fun Color.toArgb(): Int = android.graphics.Color.argb(
    (alpha * 255f).toInt().coerceIn(0, 255),
    (red * 255f).toInt().coerceIn(0, 255),
    (green * 255f).toInt().coerceIn(0, 255),
    (blue * 255f).toInt().coerceIn(0, 255)
)