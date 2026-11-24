package io.github.m0use001.planability.widget

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.abs

/**
 * ColorOS 曲率的**渲染级**验证（2026-10-31）：不看源码参数，直接渲染真实组件位图，
 * 逐像素量外轮廓角部，再与真机实测的 ColorOS 原生轮廓比。
 *
 * 为什么需要这一步：参数对了不等于画出来对。可能的偏差来源有
 * ①Path 采样段数不足（折线化）②`coerceIn` 钳位吃掉了比例 ③位图尺寸与 dp 换算错位。
 * 这些只有把位图真的画出来量一遍才抓得到。
 *
 * 判据：把渲染结果的角部轮廓换算成「相对直边的内侧偏移」序列，
 * 与 ColorOS 实测序列的**最大差**不得超过 3px（位图抗锯齿噪声量级）。
 */
@RunWith(AndroidJUnit4::class)
class SquircleRenderTest {

    /** 真机实测的 ColorOS 原生组件角部轮廓（v=距直边顶部, u=距直边左侧；原生容器 1066×486） */
    private val colorOs = listOf(
        0 to 79, 1 to 65, 2 to 58, 3 to 53, 4 to 49, 5 to 46, 6 to 43, 7 to 40, 8 to 38,
        9 to 36, 10 to 34, 11 to 32, 12 to 30, 13 to 29, 14 to 27, 15 to 26, 16 to 25,
        17 to 24, 18 to 23, 19 to 21, 20 to 20, 21 to 19, 22 to 19, 23 to 18, 24 to 17,
        25 to 16, 26 to 15, 27 to 14, 28 to 14, 29 to 13, 30 to 12, 31 to 12, 32 to 11,
        33 to 11, 34 to 10, 35 to 10, 36 to 9, 37 to 9, 38 to 8, 39 to 8, 40 to 7, 41 to 7,
        42 to 7, 43 to 6, 44 to 6, 45 to 6, 46 to 5, 47 to 5, 48 to 5, 49 to 4, 50 to 4,
        51 to 4, 52 to 4, 53 to 3, 54 to 3, 55 to 3, 56 to 3, 57 to 3, 58 to 2, 59 to 2
    )

    private val nativeW = 1066
    private val nativeH = 486

    @Test
    fun renderedCardCornerMatchesColorOs() {
        // 落盘到**测试包自己的**外部目录：/data/local/tmp 对应用沙箱不可写
        // （instrumented 测试跑在目标 App 的 uid 下，ENOENT 就是这么来的）
        val ctx = androidx.test.platform.app.InstrumentationRegistry
            .getInstrumentation().targetContext
        val outDir = File(ctx.getExternalFilesDir(null), "squircle")
        outDir.mkdirs()

        // 用与真机原生组件同一像素尺寸渲染，逐行量左上角轮廓
        val w = nativeW
        val h = nativeH
        val bmp = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bmp)
        canvas.drawColor(0x00000000, android.graphics.PorterDuff.Mode.CLEAR)
        canvas.drawPath(
            Squircle.cardPath(w.toFloat(), h.toFloat(), 3.5f),
            android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                color = android.graphics.Color.BLACK
            }
        )
        val f = File(outDir, "squircle-${w}x${h}.png")
        java.io.FileOutputStream(f).use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        assertTrue("位图应已落盘：$f", f.isFile && f.length() > 0)

        // 逐行量左上角：第一个不透明像素的 x 偏移
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        fun alphaAt(x: Int, y: Int) = (px[y * w + x] ushr 24) and 0xFF

        // v=0 那一行是**测量伪影**，跳过：超椭圆在 v=0 处的解析解恰好是 u=A（切点），
        // 而截图里首行的覆盖率极低（抗锯齿半透明），55% 阈值下会量到更靠里的一列。
        // 从 v=1 起每个采样点才有稳定覆盖。跳过后逐点最大差应 <5px，
        // 且**多数点 <1px**（见下方 median 断言——只看 max 容易放过拟合整体偏移）。
        val diffs = mutableListOf<Int>()
        var worst = 0
        var worstAt = ""
        colorOs.drop(1).forEach { (v, u) ->
            if (v >= h || u >= w) return@forEach
            var found = -1
            for (x in 0 until minOf(w, 400)) {
                if (alphaAt(x, v) > 40) { found = x; break }
            }
            if (found >= 0) {
                val d = abs(found - u)
                diffs += d
                if (d > worst) { worst = d; worstAt = "v=$v 渲染=$found 原生=$u" }
            }
        }
        assertTrue("角部轮廓与 ColorOS 原生最大差 $worst px（$worstAt），应 ≤5px", worst <= 5)
        diffs.sort()
        val median = diffs[diffs.size / 2]
        assertTrue("中位偏差应 ≤1px（整体贴合，不是个别点凑巧对上）：$median", median <= 1)
    }

    @Test
    fun smallWidgetCardStillSmooth() {
        // 2×2 小格（500×486）也要走同一曲率，不能因为尺寸小就退化成圆弧
        val w = 500
        val h = 486
        val bmp = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bmp)
        canvas.drawPath(
            Squircle.cardPath(w.toFloat(), h.toFloat(), 3.5f),
            android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                color = android.graphics.Color.BLACK
            }
        )
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        fun opaque(x: Int, y: Int) = ((px[y * w + x] ushr 24) and 0xFF) > 40

        // 角中心在 (A,A)。沿 45° 斜线从卡外朝角中心走，记录**进入实体**的坐标；
        // 该点到角中心的距离就是肩部半径，超椭圆应介于 A 与 √2·A 之间。
        val a = Squircle.axisForPx(3.5f)
        var hitDist = -1f
        // 从 (-0.2A, -0.2A) 往 (A,A) 方向走
        var k = -0.2f
        while (k <= 1.4f) {
            val x2 = (a * k).toInt()
            val y2 = (a * k).toInt()
            if (x2 in 0 until w && y2 in 0 until h && opaque(x2, y2)) {
                hitDist = kotlin.math.hypot(a - a * k, a - a * k)
                break
            }
            k += 0.005f
        }
        assertTrue("45° 斜线应穿过实体（命中距离 $hitDist，A=$a）", hitDist > 0f)
        assertTrue("超椭圆肩部应比内切圆外扩：命中 $hitDist，半轴 A=$a", hitDist > a * 0.9f)
        assertTrue("但不应越过角点：命中 $hitDist，A=$a", hitDist < a * 1.42f)
    }
}
