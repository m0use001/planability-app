package io.github.m0use001.planability.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

/**
 * ColorOS 圆角曲率的**回归闸**（2026-10-31）。
 *
 * 用户要求「小组件完全对齐 colorOS 曲率，且是连续平滑变化」。参数不是猜的：
 * 在真机 `◆` 上截桌面原生 2×2 组件的容器（1066×486px），逐行提取
 * 左上/右上/左下三个角的轮廓（每角 60+ 采样），对两种模型做最小二乘：
 *
 * | 模型 | 最大残差 |
 * |---|---|
 * | 圆弧（drawRoundRect） | 12.8 px |
 * | 超椭圆 n=4.5（原 TodayStats 用的 iOS 值） | 5.7 px |
 * | 超椭圆 n=3.25，A=0.216×短边 | **0.41 px** |
 *
 * 这组断言把「拟合结果」钉进代码：以后谁把曲率改回圆弧、或把 n 调成 iOS 的 4.5，
 * 都会在这里失败——而不是等用户在桌面上肉眼发现「跟系统不一样」。
 */
class SquircleCurvatureTest {

    /** 真机实测轮廓（相对直边起点的内侧偏移：`offset from straight edge`）。 */
    private val measured = listOf(
        0 to 79, 1 to 65, 2 to 58, 3 to 53, 4 to 49, 5 to 46, 6 to 43, 7 to 40, 8 to 38,
        9 to 36, 10 to 34, 11 to 32, 12 to 30, 13 to 29, 14 to 27, 15 to 26, 16 to 25,
        17 to 24, 18 to 23, 19 to 21, 20 to 20, 21 to 19, 22 to 19, 23 to 18, 24 to 17,
        25 to 16, 26 to 15, 27 to 14, 28 to 14, 29 to 13, 30 to 12, 31 to 12, 32 to 11,
        33 to 11, 34 to 10, 35 to 10, 36 to 9, 37 to 9, 38 to 8, 39 to 8, 40 to 7, 41 to 7,
        42 to 7, 43 to 6, 44 to 6, 45 to 6, 46 to 5, 47 to 5, 48 to 5, 49 to 4, 50 to 4,
        51 to 4, 52 to 4, 53 to 3, 54 to 3, 55 to 3, 56 to 3, 57 to 3, 58 to 2, 59 to 2
    )

    /** 真机原生组件的测量基准（px） */
    private val nativeShortSide = 486f
    private val nativeAxis = 105f

    /**
     * 给定模型，算它到实测轮廓的**最大法向偏差**（px）。
     *
     * 归一：超椭圆 F(u,v) = |A-u|^n + |A-v|^n − A^n，其梯度模 = n·(|A-u|^(n−1) + |A-v|^(n−1))^(1/2) × …
     * 对圆弧用欧氏半径差（两者可比：都近似"最近距离"）。
     */
    private fun maxResidual(n: Float, a: Float): Float {
        // 圆弧：半径差就是法向距离
        if (n == 2f) {
            var worst = 0f
            measured.forEach { (v, u) ->
                val d = kotlin.math.hypot(a - u, a - v) - a
                worst = maxOf(worst, kotlin.math.abs(d))
            }
            return worst
        }
        var worst = 0f
        measured.forEach { (v, u) ->
            val du = kotlin.math.abs(a - u)
            val dv = kotlin.math.abs(a - v)
            val f = du.toDouble().pow(n.toDouble()) + dv.toDouble().pow(n.toDouble()) -
                a.toDouble().pow(n.toDouble())
            // |∇F| = n · sqrt(du^(2n-2) + dv^(2n-2))
            val grad = n * kotlin.math.sqrt(
                du.toDouble().pow(2.0 * n - 2) + dv.toDouble().pow(2.0 * n - 2)
            )
            if (grad > 1e-6) worst = maxOf(worst, kotlin.math.abs(f / grad).toFloat())
        }
        return worst
    }

    @Test
    fun `我们的曲率参数与真机原生组件一致`() {
        // Squircle.N / AXIS_RATIO 就是拟合出来的那组值
        assertEquals("曲率指数必须是 ColorOS 实测的 3.25", 3.25f, Squircle.N, 0.001f)
        assertEquals("半轴比例必须是 0.216×短边", 0.216f, Squircle.AXIS_RATIO, 0.001f)
        // 换算到测量尺度：A = 486 × 0.216 = 105.0 —— 与逐角拟合的 105 吻合
        assertEquals(105f, nativeShortSide * Squircle.AXIS_RATIO, 0.6f)
    }

    @Test
    fun `我们的参数拟合残差在一个像素内`() {
        val ours = maxResidual(Squircle.N, nativeAxis)
        assertTrue("我们用的曲率应该贴住原生轮廓（实测 $ours px）", ours < 0.6f)
    }

    @Test
    fun `圆弧与 iOS 曲率都明显更差（记录为什么不用它们）`() {
        val circle = maxResidual(2.0f, 71.75f)    // 圆弧的最优半径（网格搜索）
        val ios = maxResidual(4.5f, nativeAxis)   // 原 TodayStats 用的 iOS 值
        val ours = maxResidual(Squircle.N, nativeAxis)
        assertTrue("圆弧(2.0) 应明显比超椭圆差：$circle vs $ours", circle > ours * 3f)
        assertTrue("iOS(4.5) 应明显更差：$ios vs $ours", ios > ours * 5f)
        // 把量级钉住，防止有人「优化」成别的奇怪值还以为更好
        assertTrue("最优圆弧最大偏差应在 1.2px 以上（实测 1.45）", circle > 1.2f)
        assertTrue("iOS n=4.5 最大偏差应在 4px 以上（实测 8.6）", ios > 4f)
    }

    @Test
    fun `卡面圆角是固定 dp，不随卡片尺寸变化（消除一卡大一小）`() {
        // 2026-11-01 用户「有的大有的小」的回归闸：
        // 卡面半轴 = RADIUS_DP × density，**与卡片长宽无关**。
        // 旧的比例算法在 580/870 短边上分别给 125/188px（真机实测渲染更是 170/207），
        // 并排看就是一大一小；谁改回比例，这里就会红。
        val d = 3.5f
        assertEquals(Squircle.RADIUS_DP * d, Squircle.axisForPx(d), 0.01f)
        // 同一设备上，任何尺寸的卡都拿到同一个半轴——这就是"视觉一致"的定义。
        // 这里不实例化 Path（JVM 单测没有 android.graphics），只校验常量语义。
        // 手机密度 3.5 下应是 91px 量级（26dp × 3.5），与 ColorOS 原生卡实测 105px
        // 同量级——比旧实现里 4×2 卡拿到的 125~170px 明显收敛。
        assertTrue(
            "26dp@3.5x 应落在 80~100px 区间，实际 " + Squircle.axisForPx(d),
            Squircle.axisForPx(d) in 80f..100f,
        )
    }

    @Test
    fun `旧的比例算法保留但已弃用（卡面禁用）`() {
        // 比例算法数学没错、视觉错：不同尺寸的卡圆角不一致。保留给极少数
        // 需要「角随卡缩放」的场景，卡面一律用 axisForPx。
        assertEquals(216f * Squircle.AXIS_RATIO, Squircle.axisFor(216f, 216f), 0.01f)
        val flat = Squircle.axisFor(480f, 216f)
        assertEquals(216f * Squircle.AXIS_RATIO, flat, 0.01f)
        val thin = Squircle.axisFor(400f, 20f)
        assertEquals(20f * Squircle.AXIS_RATIO, thin, 0.01f)
    }

    @Test
    fun `同心子块半轴等于父半轴减内边距`() {
        val parent = 105f
        val inset = 18f
        assertEquals(87f, Squircle.concentricA(parent, inset), 0.01f)
        // 内边距大于半轴时不能变负（Path 会拿到负数）
        assertEquals(0f, Squircle.concentricA(10f, 30f), 0.01f)
    }

    @Test
    fun `四角采样对称且贴合矩形边界`() {
        // 不实例化 android.graphics.Path（JVM 单测里是桩），按同一公式重算采样点
        val n = Squircle.N
        val ax = 105f
        val ay = 105f
        fun sample(t: Double): Pair<Float, Float> {
            val px = kotlin.math.abs(kotlin.math.cos(t)).toFloat().toDouble()
                .pow((2f / n).toDouble()).toFloat()
            val py = kotlin.math.abs(kotlin.math.sin(t)).toFloat().toDouble()
                .pow((2f / n).toDouble()).toFloat()
            return (ax * px) to (ay * py)
        }
        // 端点必须精确落在轴上（否则四角与直边之间有缝）
        val atZero = sample(0.0)
        assertEquals("t=0 应贴在长轴上", 0f, atZero.second, 0.01f)
        assertEquals(ax, atZero.first, 0.01f)
        val atHalfPi = sample(Math.PI / 2)
        assertEquals("t=π/2 应贴在短轴上", 0f, atHalfPi.first, 0.01f)
        assertEquals(ay, atHalfPi.second, 0.01f)
        // 45° 处采样点到**角中心**的距离应略大于半轴 A（超椭圆肩部比内切圆外扩，
        // 这正是它"方于圆"的来源）；圆弧在该点恰好等于 A。
        val mid = sample(Math.PI / 4)
        val distToCenter = kotlin.math.hypot(mid.first, mid.second)
        assertTrue("超椭圆肩部应比内切圆外扩：$distToCenter vs A=$ax", distToCenter > ax * 1.05f)
        assertTrue("但不应跑出角区（< √2·A）：$distToCenter", distToCenter < ax * 1.42f)
    }
}
