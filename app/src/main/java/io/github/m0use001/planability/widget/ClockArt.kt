package io.github.m0use001.planability.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import androidx.compose.ui.unit.DpSize
import io.github.m0use001.planability.ui.theme.AppFonts
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 大时钟两款（数字/堆叠）共用的**位图画布**。
 *
 * ## 为什么要画位图，而不是继续用 Glance 的 Text
 * 1. **字体同步**：Glance 1.1.1 的 `TextStyle.fontFamily` 在 appwidget 翻译层里
 *    **完全没有被消费**（`glance-appwidget` 1.1.1 的 classes 里搜不到 setTypeface；
 *    1.2.0 才有 `TextTranslator` 用 `TypefaceSpan(String)`，而它只认**系统**字体族）。
 *    也就是说 Text 永远渲染成系统字体。应用里选的是 Google Sans Flex / 中文圆体
 *    （含 CustomFallbackBuilder 回退链），只能自己拿 Canvas 画才能一致。
 * 2. **版面可控**：Text 的行高含 font padding（≈1.35 × 字号），三行内容会被它吃掉
 *    一个整行；Canvas 直接按字形墨迹（`getTextBounds`）定位，能精确复刻原生实测的
 *    「时间墨迹 = 卡片高 38.7%、日期 8.8%」。
 *
 * 代价：整块组件是一张图，TalkBack 只能读 `contentDescription`；好处是点击、深链、
 * 配色、字号、字体全部可控。刷新频率很低（数据变更/跨零点/30 分钟周期），可忽略。
 *
 * 绘制尺寸 = `LocalSize`（dp）× 屏幕密度，1:1 出图不做超采样——启动器把这个尺寸
 * 报小了就会放大一点，模糊度可接受（此前 Text 同样受 LocalSize 影响）。
 */
internal object ClockArt {

    enum class Style { Digital, Stacked }

    /** 一套颜色（ARGB），由 `WidgetPalette` 的时钟色算好传进来 */
    class Colors(val card: Int, val time: Int, val hours: Int, val text: Int)

    /**
     * 版面比例（全部按卡片高度归一）。数字款三行 = 日期/时间/完成率，
     * 0.088 / 0.387 / 0.088 与真机同屏实测的原生 Solid 大时钟一致；两个 gap 是拆出来的
     * 垂直留白（原生两行版没有完成率，这里按同样的呼吸感分配）。
     */
    private class Spec(
        val dateInk: Float,
        val timeInk: Float,
        val rateInk: Float,
        val gap1: Float,
        val gap2: Float,
        val padX: Float
    )

    private val digital = Spec(
        dateInk = 0.088f, timeInk = 0.420f, rateInk = 0.088f,
        gap1 = 0.060f, gap2 = 0.060f, padX = 0.04f
    )

    /** 堆叠款：两行大字各 23%、中间留 3.5% 呼吸缝、小字 4.5%（用户：数字再大、两行隔开） */
    private val stacked = Spec(
        dateInk = 0.045f, timeInk = 0.230f, rateInk = 0.045f,
        gap1 = 0.080f, gap2 = 0.150f, padX = 0.06f
    )

    /** 一行按「墨迹高度」定尺寸的文字 */
    private class Line(
        val text: String,
        val paint: Paint,
        val bounds: Rect
    ) {
        val inkHeight: Float get() = bounds.height().toFloat()
        val width: Float get() = paint.measureText(text)
        fun shrinkTo(maxWidth: Float) {
            if (width > maxWidth && width > 0f) {
                paint.textSize = paint.textSize * maxWidth / width
                paint.getTextBounds(text, 0, text.length, bounds)
            }
        }
    }

    /**
     * @param size     组件当前尺寸（`LocalSize`）
     * @param weight   基础字重（400）+ 外观页的字重偏移
     * @param fontKey  外观页选中的字体族（`ThemeConfig.fontFamily`）
     * @param fontScale 外观页的字号缩放（只做温和缩放并夹在 0.85–1.20，避免撑破版面）
     */
    fun render(
        ctx: Context,
        size: DpSize,
        colors: Colors,
        weight: Int,
        fontKey: String,
        fontScale: Float,
        data: ClockData,
        style: Style
    ): Bitmap {
        val dm = ctx.resources.displayMetrics
        val w = (size.width.value * dm.density).roundToInt().coerceIn(2, 4096)
        val h = (size.height.value * dm.density).roundToInt().coerceIn(2, 4096)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val spec = if (style == Style.Digital) digital else stacked
        val fs = fontScale.coerceIn(0.85f, 1.20f)
        val typeface = AppFonts.typeface(ctx, fontKey, weight)
        // 时间行加粗（用户要求）：在基础字重上再 +200（GS Flex 可变轴 / 圆体的 Bold 档），
        // 日期与完成率保持基础字重——大字更实、小字更轻，层级拉开
        val timeTypeface = AppFonts.typeface(ctx, fontKey, (weight + 200).coerceAtMost(900))

        // 胶囊：半径取短边一半（与 Glance 的 999dp 同一结果）
        val card = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = colors.card }
        val r = min(w, h) / 2f
        canvas.drawRoundRect(RectF(0f, 0f, w.toFloat(), h.toFloat()), r, r, card)

        val maxW = w * (1f - 2f * spec.padX)
        val base = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.typeface = typeface }

        fun line(text: String, targetInk: Float, color: Int, boldTime: Boolean = false): Line {
            val paint = Paint(base)
            if (boldTime) paint.typeface = timeTypeface
            paint.color = color
            // 先用 100px 量出该串的墨迹高度，再等比换算到目标墨迹高
            paint.textSize = 100f
            val probe = Rect()
            paint.getTextBounds(text, 0, text.length, probe)
            paint.textSize = 100f * (targetInk * fs) / probe.height().toFloat().coerceAtLeast(1f)
            val bounds = Rect()
            paint.getTextBounds(text, 0, text.length, bounds)
            return Line(text, paint, bounds).also { it.shrinkTo(maxW) }
        }

        if (style == Style.Digital) {
            val date = line(data.dateText, spec.dateInk * h, colors.text)
            val time = line(data.timeText, spec.timeInk * h, colors.time, boldTime = true)
            val rate = line(data.rateText, spec.rateInk * h, colors.text)
            drawColumn(
                canvas, h, w,
                listOf(date, time, rate),
                listOf(spec.gap1 * h, spec.gap2 * h)
            )
        } else {
            val date = line(data.dateText, spec.dateInk * h, colors.text)
            val hours = line(data.hh, spec.timeInk * h, colors.hours, boldTime = true)
            val minutes = line(data.mm, spec.timeInk * h, colors.time, boldTime = true)
            val rate = line(data.rateText, spec.rateInk * h, colors.text)
            drawColumn(
                canvas, h, w,
                listOf(date, hours, minutes, rate),
                // 中间 3.5% 呼吸缝：两行大数字贴在一起会糊成四位（用户要求隔开）
                listOf(spec.gap1 * h, 0.035f * h, spec.gap2 * h)
            )
        }
        return bmp
    }

    /** 竖排：整体垂直居中，逐行按墨迹顶对齐（不用字体行高，避免 font padding 把版面撑开） */
    private fun drawColumn(
        canvas: Canvas,
        h: Int,
        w: Int,
        lines: List<Line>,
        gaps: List<Float>
    ) {
        val total = lines.sumOf { it.inkHeight.toDouble() }.toFloat() + gaps.sum()
        var top = (h - total) / 2f
        val cx = w / 2f
        lines.forEachIndexed { i, line ->
            // bounds.top 是相对 baseline 的负值：baseline = 墨迹顶 - bounds.top
            val baseline = top - line.bounds.top
            val x = cx - (line.bounds.left + line.bounds.right) / 2f
            canvas.drawText(line.text, x, baseline, line.paint)
            top += line.inkHeight + (gaps.getOrNull(i) ?: 0f)
        }
    }
}
