package io.github.m0use001.planability.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.provideContent
import androidx.glance.layout.ContentScale
import androidx.glance.layout.fillMaxSize
import io.github.m0use001.planability.core.AppTime
import io.github.m0use001.planability.data.Graph
import io.github.m0use001.planability.data.ScreenTimeStats
import kotlin.math.roundToInt

/**
 * 「今日数据」小组件（**固定 4×2，四胶囊组版式**，2026-11-02 晚用户点名改版）：
 * 2×2 四个胶囊组，每个胶囊 = 一块比托盘略亮的浮层，内含小进度环（环弧=进度、
 * 0% = 纯轨道空环）+ 名称/数值两行；百分比画在环心里（内孔放得下才画）。
 * 历史：四环版式（2026-11-02 白天）的环直接浮在托盘上、四组各踞一角 → 用户
 * 「拆分成 4 个胶囊组」；组边界改由胶囊承担，卡面不再散。
 *
 * 配色延续组件家族口径：
 *  - 托盘 = 第二色家族彩面（clockFace，与大时钟/倒数日同一张脸，拼色规则 R4）；
 *  - 环弧四环统一主色（R2 同类控件同屏同色），按胶囊底做对比修正；
 *  - 环心百分比 = 主色强调数字统一口径（heroNumberColor，与胶囊时钟时间同色）。
 *
 * 四个维度与口径（与首页/评分页一致，不另算一套）：
 *  - 待办：完成数/今日待办数（2026-11-03 用户把「完成」改「待办」）；睡眠：昨晚睡眠时长（OPPO 健康采集，Graph.sleep）
 *    /外观设置里的睡眠目标；专注：今日已完成专注分钟/每日专注目标；
 *    屏幕：今日亮屏使用/评分页的屏幕上限（口径见 ScreenTimeStats）。
 *  - 旧的「达标」对勾徽标退役：环画满一圈本身就是达标。
 *
 * 位图渲染与其它组件同套路（Glance Text 吃不到应用字体），配色跟随主题。
 * 托盘圆角 = 方形组件卡面固定 dp（Squircle.axisForPx，26dp）；胶囊自己的圆角
 * 按自身高度取半（=胶囊），不与托盘同心推导。整卡点击打开应用首页。
 */
class TodayStatsWidget : GlanceAppWidget() {

    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        // 渲染尺寸用 launcher 实际分配的占位（AppWidgetOptions 的 MIN_WIDTH/MAX_HEIGHT），
        // 不用 LocalSize——SizeMode.Exact 报的是可伸展最大区域，位图会被 FillBounds 压扁
        //（2026-11-01 实锤，详见 Squircle.RADIUS_DP 注释）。
        val mgr = androidx.glance.appwidget.GlanceAppWidgetManager(context)
        val appWidgetId = mgr.getAppWidgetId(id)
        val opts = android.appwidget.AppWidgetManager.getInstance(context)
            .getAppWidgetOptions(appWidgetId)
        val realDpW = opts.getInt(
            android.appwidget.AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0
        )
        val realDpH = opts.getInt(
            android.appwidget.AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 0
        )
        android.util.Log.i(
            "TodayStatsSize",
            "appWidgetId=$appWidgetId LocalSize/real=$realDpW x $realDpH dp"
        )
        val data = runCatching { buildData() }.getOrElse { TodayStatsArt.Data(emptyList()) }
        provideContent {
            val size = if (realDpW > 0 && realDpH > 0)
                androidx.compose.ui.unit.DpSize(realDpW.dp, realDpH.dp)
            else LocalSize.current
            val ctx = LocalContext.current
            val theme = Graph.settings.snapshot.value.theme
            val bitmap = TodayStatsArt.render(
                ctx, size, artColors(ctx, size), 400 + theme.fontWeight,
                theme.fontFamily, theme.fontScale, data
            )
            Image(
                provider = ImageProvider(bitmap),
                contentDescription = data.rows.joinToString("，") {
                    "${it.name} ${it.detail}，进度 ${(it.ratio * 100).roundToInt()}%"
                },
                modifier = GlanceModifier.fillMaxSize().clickOpenApp(),
                contentScale = ContentScale.FillBounds
            )
        }
    }

    /** 今日四维数据；任何一条查询失败都退化成 0，组件不因取数异常消失 */
    private suspend fun buildData(): TodayStatsArt.Data {
        val today = AppTime.today()
        val todos = runCatching { Graph.plan.todosOfDay(AppTime.dayKey(today)) }
            .getOrDefault(emptyList())
        // v49：百分比权重走 TodoProgress.contribution（与首页 HomeCards 同一实现）。
        // 此前这里与 HomeCards.kt:94 各抄一份完全相同的公式——B 类病因活标本，
        // 现在只看一处，SingleSourceTest 挡住第三份。
        val done = todos.sumOf {
            io.github.m0use001.planability.data.TodoProgress.contribution(it.done, it.percentDone)
        }.toFloat()
        val focusMin = runCatching {
            Graph.focus.sessionsBetween(today, today)
                .filter { it.completed }
                .sumOf { (it.end - it.start) / 60000 }.toInt()
        }.getOrDefault(0)
        val settings = Graph.settings.snapshot.value
        val sleepMin = runCatching { Graph.sleep.latest()?.durationMin ?: -1 }.getOrDefault(-1)
        val screenMin = runCatching { ScreenTimeStats.today(today)?.totalMinutes ?: 0 }
            .getOrDefault(0)

        fun ratio(a: Float, b: Int): Float = if (b > 0) (a / b).coerceIn(0f, 1f) else 0f
        fun ratio(a: Int, b: Int): Float = if (b > 0) (a.toFloat() / b).coerceIn(0f, 1f) else 0f
        // v46：带小数保留 1 位（1.5/3），整数显示原样（2026-11-03 用户：去掉「项」）
        val doneStr =
            if (done % 1f == 0f) done.toInt().toString()
            else String.format(java.util.Locale.US, "%.1f", done)
        return TodayStatsArt.Data(
            listOf(
                // 2026-11-03 用户：「完成」改「待办」、「项」去掉
                TodayStatsArt.Row(
                    "待办",
                    if (todos.isEmpty()) "暂无待办" else "$doneStr/${todos.size}",
                    ratio(done, todos.size)
                ),
                TodayStatsArt.Row(
                    "睡眠",
                    if (sleepMin >= 0) shortDur(sleepMin) else "未记录",
                    ratio(sleepMin, settings.sleepGoalMin)
                ),
                // 2026-11-03 用户：专注的「分」改 m（与睡眠/屏幕的 h/m 口径一致）
                TodayStatsArt.Row(
                    "专注",
                    "${focusMin}m",
                    ratio(focusMin, settings.focusGoalMin)
                ),
                TodayStatsArt.Row(
                    "屏幕",
                    shortDur(screenMin),
                    ratio(screenMin, settings.screenLimitMin)
                ),
            )
        )
    }

    private fun shortDur(minutes: Int): String =
        if (minutes >= 60) "${minutes / 60}h${minutes % 60}m" else "${minutes}m"
}

internal object TodayStatsArt {

    /** 一项数据：环弧=ratio，文字块=名称（上行）+数值（下行） */
    class Row(val name: String, val detail: String, val ratio: Float)

    class Data(val rows: List<Row>)

    fun render(
        ctx: Context,
        size: DpSize,
        colors: WidgetArt.Colors,
        weight: Int,
        fontKey: String,
        fontScale: Float,
        data: Data
    ): Bitmap {
        val (w, h) = WidgetArt.sizeOf(size, ctx)
        val (bmp, canvas) = WidgetArt.newBitmap(w, h)
        val fs = fontScale.coerceIn(0.85f, 1.25f)
        val tp = WidgetArt.TextPainter(ctx, fontKey, weight)
        // 卡底色 = 第二色家族彩面（clockFace，与大时钟/倒数日同一张脸，R4）；
        // **2026-11-03 用户「圆角矩形背景删掉」**：不再铺整卡托盘，四个胶囊自己带底色，
        // 组件透出壁纸——face 只作为胶囊底与深/浅判定的源色使用。
        val face = if (colors.clockFace != 0) colors.clockFace else colors.card
        val dark = luma(face) < 0.5f

        val rows = data.rows
        if (rows.isEmpty()) return bmp

        // ── 2×2 四个胶囊组（2026-11-02 晚用户定案：「锁死 4×2（宽 4）并拆分成 4 个胶囊组」，
        //    形态取「胶囊内嵌小环」）：每个维度一块独立胶囊，胶囊内左侧小环（轨道+进度弧+
        //    环心百分比）、右侧名称/数值两行。2026-11-03 起**不再画整卡托盘**——
        //    「组」的边界完全由胶囊承担，组件背景透明，浮在壁纸上。
        val shown = rows.take(4)
        if (shown.isEmpty()) return bmp

        // 2026-11-06 验收返工（A9/B8）：marginX 在窄卡上只有 4.9dp、列隙 3.8dp 与行隙不等。
        // marginX 抬到 ≥8dp 口径（h*0.058），列隙与行隙取同一值。
        val marginX = maxOf(w * 0.035f, h * 0.058f)
        val marginY = h * 0.075f
        val gapX = h * 0.075f
        val gapY = h * 0.075f
        val capW = (w - marginX * 2f - gapX) / 2f
        val capH = (h - marginY * 2f - gapY) / 2f
        val capR = capH / 2f
        // 胶囊底：从托盘提亮/压暗 7%（深色提亮、浅色压暗）——像一张浮起来的组卡，
        // 又能让环轨道在它上面读出层次（轨道色基于胶囊底再算）。
        // **注意本文件的 blend(base, top, alpha) 与 WidgetArt.blend(fg, bg, alpha) 参数序相反**：
        // 这里 base 是底色、top 是叠加色（2026-11-02 晚首版写反 → 胶囊变近白 + 浅字不可见）
        val capBg = blend(face, if (dark) 0xFFFFFFFF.toInt() else 0xFF000000.toInt(), 0.07f)
        val capTrack = blend(
            capBg,
            if (dark) 0xFF000000.toInt() else 0xFFFFFFFF.toInt(),
            if (dark) 0.30f else 0.55f
        )
        val arcColor = contrastFix(colors.primary, capBg, dark)
        val pctColor = if (colors.heroNumber != 0) colors.heroNumber else colors.primary

        // 胶囊内几何：内距按胶囊高取，环径同时受宽高约束（窄胶囊时环缩、不把文字挤没）。
        // 2026-11-03 用户「环大一点」：内距 16%→9%，环径从 0.68·capH 提到 0.82·capH；
        // 字号改锚 capH（不再随环径长），环变大但文字不会跟着变粗挤爆胶囊。
        val pad = capH * 0.09f
        val ringD = minOf(capH - pad * 2f, capW * 0.46f).coerceAtLeast(capH * 0.34f)
        val stroke = (ringD * 0.13f).coerceAtLeast(2f)
        val textGap = capH * 0.10f
        val nameInk = capH * 0.20f * fs
        val valInk = capH * 0.28f * fs
        val pctInk = ringD * 0.30f * fs
        val lineGap = capH * 0.04f

        val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = stroke; color = capTrack
        }
        val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = stroke; color = arcColor
            strokeCap = Paint.Cap.ROUND
        }
        val capPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = capBg }

        shown.forEachIndexed { i, row ->
            val col = i % 2
            val r = i / 2
            val capL = marginX + col * (capW + gapX)
            val capT = marginY + r * (capH + gapY)
            canvas.drawRoundRect(
                RectF(capL, capT, capL + capW, capT + capH), capR, capR, capPaint
            )
            val ratio = row.ratio.coerceIn(0f, 1f)

            // 排布判定（2026-11-06 视觉验收返工）：原来只看 `capW < capH * 1.15f` 一个几何阈值，
            // 2×2 摆位（capW=166 capH=139）差 6px 不触发 → 走横排 → 右侧只剩五十来像素，
            // 「3/11」被截成「3…」，文字还越出胶囊（评审实测 129px 亮像素落在胶囊 alpha<200 区）。
            // 改为**按内容能否放下**判定：横排放不下「环 + 文字」就改竖排。
            val nameS0 = tp.sized(row.name, nameInk, colors.sub)
            val valFull = tp.sized(row.detail, valInk, colors.onCard)
            val nameH0 = nameS0.bounds.height().toFloat().coerceAtLeast(nameInk)
            val valH0 = valFull.bounds.height().toFloat().coerceAtLeast(valInk)
            val needW = ringD + textGap + maxOf(
                nameS0.paint.measureText(row.name),
                valFull.paint.measureText(row.detail)
            )
            val stacked = capW - pad * 2f < needW

            // 竖排时环径要给下方两行文字让位（原来环径只按宽高算，tall 摆位把文字顶到胶囊外）
            val ringDUse = if (stacked) {
                minOf(capH - pad * 2f - (nameH0 + lineGap + valH0), capW * 0.70f)
                    .coerceAtLeast(capH * 0.22f)
            } else ringD
            val cy = if (stacked) capT + pad + ringDUse / 2f else capT + capH / 2f
            val ringCx = if (stacked) capL + capW / 2f else capL + pad + ringDUse / 2f
            val arcR = ringDUse / 2f - stroke / 2f
            canvas.drawCircle(ringCx, cy, arcR, trackPaint)
            if (ratio > 0f) {
                canvas.drawArc(
                    RectF(ringCx - arcR, cy - arcR, ringCx + arcR, cy + arcR),
                    -90f, ratio * 360f, false, arcPaint
                )
            }

            // 环心百分比：按内孔弦长判（文字框不得碰环壁）。胶囊里环径小，定字号时
            // 「27%」「100%」全被跳过（2026-11-02 晚实拍：环心一片空）——
            // 改为**按弦长自动缩字号**，最少缩到环径 16%，保证百分比一定画得出来。
            val pct = "${(ratio * 100).roundToInt()}%"
            val innerR = arcR - stroke / 2f
            fun chordFor(h: Float) = 2f * kotlin.math.sqrt(
                (innerR * innerR - (h / 2f) * (h / 2f)).coerceAtLeast(0f)
            )
            var pctInkUse = pctInk
            var pctS = tp.sized(pct, pctInkUse, pctColor)
            var pctW = pctS.paint.measureText(pct)
            var pctH = pctS.bounds.height().toFloat().coerceAtLeast(pctInkUse)
            var guard = 0
            while (pctW > chordFor(pctH) && pctInkUse > ringDUse * 0.16f && guard++ < 8) {
                pctInkUse *= 0.88f
                pctS = tp.sized(pct, pctInkUse, pctColor)
                pctW = pctS.paint.measureText(pct)
                pctH = pctS.bounds.height().toFloat().coerceAtLeast(pctInkUse)
            }
            if (pctW <= chordFor(pctH)) {
                pctS.draw(canvas, ringCx - pctW / 2f, cy - pctH / 2f)
            }

            // 右侧两行（竖排时改为环下居中两行）：上行名称（次级色）、下行数值（主文字色）。
            // 2026-11-06 验收返工：`nameS0`/`valFull` 已在上方为排布判定算过，这里复用。
            if (stacked) {
                // 2026-11-06 第 3 轮返工：堆叠模式的可用宽不再是 `capW - pad*2`——
                // 胶囊近似圆形，文字所在高度的**弦宽**比直径窄，按 capW 给宽就会
                // 视觉越界或被截断（实拍「6h5…」）。按每行文字中心的弦宽给宽，
                // 放不下先缩字号、缩到底再省略（数值优先保完整，名称可省略）。
                val rCap = capW / 2f
                val capCy = capT + capH / 2f
                fun chordAt(centerY: Float): Float {
                    val dy = (centerY - capCy).coerceAtLeast(0f)
                    return 2f * kotlin.math.sqrt(((rCap * rCap) - dy * dy).coerceAtLeast(0f))
                }
                val textTop = cy + ringDUse / 2f + textGap
                val nameCenter = textTop + nameH0 / 2f
                val valCenter = textTop + nameH0 + lineGap + valH0 / 2f
                val nameAvail = chordAt(nameCenter) - pad * 0.6f
                val valAvail = chordAt(valCenter) - pad * 0.6f
                var valS = valFull
                var valInkUse = valInk
                var guard = 0
                while (valS.paint.measureText(row.detail) > valAvail &&
                    valInkUse > valInk * 0.62f && guard++ < 6
                ) {
                    valInkUse *= 0.90f
                    valS = tp.sized(row.detail, valInkUse, colors.onCard)
                }
                var nameS = nameS0
                if (nameS.paint.measureText(row.name) > nameAvail) nameS = nameS.ellipsize(nameAvail)
                if (valS.paint.measureText(row.detail) > valAvail) valS = valS.ellipsize(valAvail)
                nameS.drawCentered(canvas, capL + capW / 2f, textTop)
                valS.drawCentered(canvas, capL + capW / 2f, textTop + nameH0 + lineGap)
            } else {
                val textX = ringCx + ringDUse / 2f + textGap
                val maxTextW = (capL + capW - pad) - textX
                var valS = valFull
                if (valS.paint.measureText(row.detail) > maxTextW) valS = valS.ellipsize(maxTextW)
                val blockH = nameH0 + lineGap + valH0
                nameS0.draw(canvas, textX, cy - blockH / 2f)
                valS.draw(canvas, textX, cy - blockH / 2f + nameH0 + lineGap)
            }
        }
        return bmp
    }

    private fun luma(argb: Int): Float {
        val r = (argb shr 16 and 0xFF) / 255f
        val g = (argb shr 8 and 0xFF) / 255f
        val b = (argb and 0xFF) / 255f
        return 0.299f * r + 0.587f * g + 0.114f * b
    }

    /** 把 top 色按 alpha 叠到 base 上（轨道色用） */
    private fun blend(base: Int, top: Int, alpha: Float): Int {
        fun ch(shift: Int): Int {
            val b = (base shr shift and 0xFF)
            val t = (top shr shift and 0xFF)
            return (b + (t - b) * alpha).toInt().coerceIn(0, 255)
        }
        return android.graphics.Color.rgb(ch(16), ch(8), ch(0))
    }

    /** 主色压在卡上分不清时按明暗模式提亮/压暗（与屏幕使用时间组件同法） */
    private fun contrastFix(base: Int, card: Int, dark: Boolean): Int {
        if (kotlin.math.abs(luma(base) - luma(card)) >= 0.25f) return base
        val hsv = FloatArray(3)
        android.graphics.Color.colorToHSV(base, hsv)
        hsv[2] = if (dark) 0.85f else 0.45f
        return android.graphics.Color.HSVToColor(hsv)
    }
}

class TodayStatsWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = TodayStatsWidget()
}
