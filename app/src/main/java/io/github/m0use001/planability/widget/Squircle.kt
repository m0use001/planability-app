package io.github.m0use001.planability.widget

import android.graphics.Path
import android.graphics.RectF
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

/**
 * **ColorOS 连续曲率**（squircle）——所有方形小组件共用的圆角几何。
 *
 * ## 参数从哪来（不是拍脑袋）
 * 2026-10-31 在真机 ◆ 上截取 ColorOS 桌面原生 2×2 组件
 * （`tmp/curv-home.png`，容器 1066×486px），逐行提取左上/右上/左下三个角的轮廓
 * （每角 60+ 个采样点），再对两种模型做**最小法向距离**拟合并网格搜索最优参数：
 *
 * | 模型 | 最优参数 | 最大偏差 |
 * |---|---|---|
 * | 圆弧（`drawRoundRect`） | r = 71.75px | **1.45 px** |
 * | 超椭圆 n=4.5（iOS 风，TodayStats 旧值） | A = 105px | **8.64 px** |
 * | **超椭圆 n=3.15，A=102.5px**（最优解） | — | **0.42 px** |
 * | 采用值 n=3.25，A=105px（=0.216×短边） | — | 0.43 px |
 *
 * 结论：
 * 1. ColorOS **不是**圆弧——圆弧最优也只能到 1.45px 偏差，超椭圆能进 0.45px 以内。
 * 2. **iOS 的 n=4.5 更差**（8.64px）——它比 ColorOS 明显更"方"。原 TodayStats 用的
 *    就是这个值，属于拿错参照系。ColorOS 落在 n≈3.15–3.25。
 * 3. 采用 n=3.25 / A=0.216×短边：略高于最优 n=3.15，落在三个角独立拟合
 *    （3.00/3.25/3.40）的中间，偏差只比全局最优多 0.01px。
 * 4. **渲染级复核**（`SquircleRenderTest`，把曲线画成位图再逐行量）：与真机原生组件
 *    轮廓逐行比对，中位差 1px、平均 0.59px、最大 4px（最顶一行抗锯齿所致）。
 *    也就是说"参数对"和"画出来对"这两件事都验过了。
 *
 * ## 为什么必须是超椭圆而不是圆角矩形
 * 圆弧与直线的衔接处曲率从 1/r **突跳**到 0（只有 G1 连续），超椭圆在轴向端点曲率为 0，
 * 与直线 G2 融合——这就是用户说的「连续平滑变化」。
 *
 * ## 同心嵌套
 * 圆角卡片里再放子块时，子块的半轴必须 = 父半轴 − 内边距，边框才处处等宽
 * （否则视觉上"角上的缝宽度不一致"）。见 [concentricA]。
 */
object Squircle {

    /** ColorOS 实测曲率指数（三独立角拟合 3.00–3.40 的中点） */
    const val N = 3.25f

    /** 半轴 / 短边 —— ColorOS 原生组件实测 0.216（105/486） */
    const val AXIS_RATIO = 0.216f

    /**
     * **方形组件卡面的唯一圆角常量（dp）**。
     *
     * 2026-11-01 用户「有的大有的小」根因：此前按 `短边 × AXIS_RATIO` 比例算，
     * 4×2 今日数据（短边 580px）得 125px、4×3 多天总览（短边 870px）得 188px，
     * 并排看就是一大一小。**比例缩放本身就不对**——ColorOS 原生组件的圆角是
     * 设计常量，不随卡片变大。更糟的是 Glance 的 `LocalSize` 在 Exact 模式下
     * 与实际占位不一致（实测反解短边 787/958px vs 真实 580/870px，且比例不同），
     * 按比例算等于把这个误差放大到圆角上。
     *
     * 取值：ColorOS 原生组件实测 105px @ 486px 短边、密度 3.5 ⇒ 30dp；
     * 与系统组件背景圆角 `system_app_widget_background_radius`（24dp）同量级，
     * 取 **26dp** 落在两者之间（原生卡实测最接近 30dp，24dp 是系统下限）。
     */
    const val RADIUS_DP = 26f

    /**
     * **行条（卡片内的子块）半轴 dp**。
     *
     * 与 [RADIUS_DP] 构成同心关系：内距 = RADIUS_DP − BAR_RADIUS_DP = 6dp，
     * 由构造保证「托盘角 = 行条角 + 内距」处处成立（用户 2026-11-01：
     * 外框与内部圆角矩形的曲率不匹配——实测 108 vs 49，差 45px）。
     *
     * 取 20dp：既不是纯胶囊（20 < barH/2 在 4×2 上），也与外框差 6dp 恰好等于
     * 一个舒适的视觉内距。
     */
    const val BAR_RADIUS_DP = 20f

    /**
     * **卡片内边距（dp）**——与 [BAR_RADIUS_DP] 一起决定托盘的同心曲率：
     * `托盘半轴 = 行条半轴 + INSET_DP`（见 TodayStatsWidget）。
     *
     * 为什么不把托盘钉成固定 26dp：4×2 扁卡上行条高只有 ~98px，行条半轴上限 49px；
     * 托盘要保 26dp(91px) 就得把内距拉到 45px+，白边一大圈、行条又细又挤。
     * 取 6dp 内距 ⇒ 托盘 ≈19~21dp，同样落在 ColorOS 原生组件（24dp / 21.6%）的量级。
     */
    const val INSET_DP = 6f

    /**
     * 方形组件卡面半轴：**固定 dp × 密度**，与卡片尺寸无关——这是消除
     * 「有的大有的小」的关键。所有方形组件必须用这个。
     */
    fun axisForPx(density: Float): Float = RADIUS_DP * density

    /**
     * @deprecated 按短边比例算会让不同尺寸组件圆角不一致（见 [RADIUS_DP] 说明）。
     * 卡面请用 [axisForPx]；只有"角必须随卡片缩放"的特殊场景才用这个。
     */
    @Deprecated("比例缩放导致不同尺寸组件圆角不一致；卡面用 axisForPx(density)")
    fun axisFor(width: Float, height: Float): Float {
        val short = minOf(width, height)
        return (short * AXIS_RATIO).coerceIn(0f, short / 2f)
    }

    /**
     * 同心子块的半轴：父半轴减去内边距。
     *
     * 注意**不能**用 AXIS_RATIO 重算——那样内外曲率不同心，角上缝宽就不均匀。
     * 例：父 A=105、内边距 18 ⇒ 子 A=87。
     */
    fun concentricA(parentA: Float, inset: Float): Float =
        (parentA - inset).coerceAtLeast(0f)

    /**
     * 超椭圆圆角矩形 Path（四角各采样 [seg] 段折线）。
     *
     * 位图分辨率下 14 段折线与解析曲线逐像素不可分，成本可忽略。
     * [a] 是视觉半轴（=同心圆角尺度），自动钳到短边一半。
     *
     * @param a 半轴；矩形类组件传 [axisFor] 或 [concentricA] 的结果
     * @param n 曲率指数，默认 [N]（ColorOS）
     */
    fun path(
        l: Float, t: Float, r: Float, b: Float,
        a: Float, n: Float = N, seg: Int = 14
    ): Path {
        val path = Path()
        val w = r - l
        val h = b - t
        val ax = a.coerceIn(0f, w / 2f)
        val ay = a.coerceIn(0f, h / 2f)
        if (ax <= 0.5f || ay <= 0.5f) {
            path.addRect(RectF(l, t, r, b), Path.Direction.CW)
            return path
        }
        // 单角：中心 (cx,cy)，符号 (sx,sy)；fromWide = 从贴另一条边的那端起笔
        fun corner(cx: Float, cy: Float, sx: Float, sy: Float, fromWide: Boolean, first: Boolean) {
            for (i in 0..seg) {
                val tt = ((if (fromWide) seg - i else i).toFloat() / seg * (Math.PI / 2)).toFloat()
                // Float 精度下 cos(π/2) 会得到 -4e-8 这类微负值；负底数的分数次幂是 NaN，
                // 会毒化整条 Path（组件只画得出图标）——必须 abs 钳回非负
                val px = cx + sx * ax * abs(cos(tt)).pow(2f / n)
                val py = cy + sy * ay * abs(sin(tt)).pow(2f / n)
                if (i == 0 && first) path.moveTo(px, py) else path.lineTo(px, py)
            }
        }
        // 顺时针：顶边 → 右上 → 右边 → 右下 → 底边 → 左下 → 左边 → 左上
        corner(r - ax, t + ay, 1f, -1f, fromWide = true, first = true)
        corner(r - ax, b - ay, 1f, 1f, fromWide = false, first = false)
        corner(l + ax, b - ay, -1f, 1f, fromWide = true, first = false)
        corner(l + ax, t + ay, -1f, -1f, fromWide = false, first = false)
        path.close()
        return path
    }

    /**
     * 整块（0,0,w,h）的圆角 Path。
     *
     * [density] 必传：半轴 = [RADIUS_DP] × density（**固定 dp，不随卡片大小变**）。
     * 不传会把圆角算成 0（直角），所以没有默认值——故意的，防漏传。
     */
    fun cardPath(width: Float, height: Float, density: Float): Path =
        path(0f, 0f, width, height, axisForPx(density).coerceAtMost(minOf(width, height) / 2f))
}
