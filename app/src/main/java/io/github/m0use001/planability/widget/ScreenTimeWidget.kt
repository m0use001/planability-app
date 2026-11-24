package io.github.m0use001.planability.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.ActionParameters
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.ContentScale
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.height
import androidx.glance.layout.width
import io.github.m0use001.planability.data.Graph
import io.github.m0use001.planability.data.ScreenTimeStats

/**
 * 「屏幕使用时间」小组件（谷歌同款布局，配色/字体跟随应用主题）：
 *  - 标题行：屏幕使用时间 + 右上角刷新（透明热区，点了立即重算重推）；
 *  - 大字：当日总时长「7h 25m」（手动填写的值优先，与评分页口径一致）；
 *  - 分 App 行：**色圆里写该 App 用时**（5h / 1h / 29m），圆右给应用名，
 *    按用时降序；圆色轮换 主色/第三色/主色容器（拼色规则：组件是第二三色少数能露脸的地方）。
 *
 * ## 自适应（2026-10-28 重排）
 * 默认落位 2×1（minHeight 40dp —— Pixel 系启动器按 **min 尺寸**算格数，70dp 会被算成 2 行），
 * 扁卡走 flat 分支：总时长居左、叠圆列居右。竖卡（宽 ≤1.35×高）行数随可用高度增加（最多 8 行），
 * 圆径取 min(0.145w, 0.115h)。
 * - **缩小时砍信息不砍骨架**：标题双向自适应（11–18dp，刷新 = 1.5×标题跟随）、hero 19dp 下限；
 *   flat 叠圆圆径缩到 22dp 下限以下就减行（3→2→1，大卡画满 3 行）；
 *   竖卡**圆径从「占满行区」反推**（行少撑大、行多摊薄，22dp 下限减行，上限 12 行），卡片始终填满。
 * - **放大跟住格子**：maxResize 240×180dp 曾在启动器把格子拖过上限时让内容停在 180dp、
 *   下半截空白（「底部消失」）——上限放宽到 300×320dp，行数上限 8 兜底。
 * - 刷新热区 48×28dp 下限，矮卡上仍可点中。
 *
 * ## 渲染
 * 与 [WidgetArt] 同一套位图路线（Glance Text 吃不到应用字体），字体/字重/字号跟随外观页。
 */
class ScreenTimeWidget : GlanceAppWidget() {

    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val data = runCatching { buildData() }.getOrNull()
            ?: ScreenTimeArt.Data("—", emptyList(), "无法读取使用时间（未授权或无数据）")
        provideContent {
            val ctx = LocalContext.current
            val size = LocalSize.current
            val theme = Graph.settings.snapshot.value.theme
            val bitmap = ScreenTimeArt.render(
                ctx, size, artColors(ctx, size), 400 + theme.fontWeight,
                theme.fontFamily, theme.fontScale, data
            )
            // contentAlignment=TopEnd：位图铺满，刷新热区锚在右上（同 RangeWidget 的做法）
            Box(
                GlanceModifier.fillMaxSize().clickOpenApp(),
                contentAlignment = Alignment.TopEnd
            ) {
                Image(
                    provider = ImageProvider(bitmap),
                    contentDescription = null,
                    modifier = GlanceModifier.fillMaxSize(),
                    contentScale = ContentScale.FillBounds
                )
                Box(
                    GlanceModifier
                        // 热区跟图标走比例，但给绝对下限——矮卡上图标有 11dp 骨架下限，
                        // 热区太小会点不中（下限 48×28dp）
                        .width((size.width.value * 0.20f).dp.coerceAtLeast(48.dp))
                        .height((size.height.value * 0.14f).dp.coerceAtLeast(28.dp))
                        .clickable(actionRunCallback<RefreshScreenTimeAction>())
                ) { }
            }
        }
    }

    private suspend fun buildData(): ScreenTimeArt.Data {
        val stats = ScreenTimeStats.today() ?: return ScreenTimeArt.Data(
            "—", emptyList(), "无法读取使用时间（未授权或无数据）"
        )
        val rows = stats.apps.map {
            ScreenTimeArt.Row(ShortDur.of(it.minutes), it.label, it.minutes)
        }
        return ScreenTimeArt.Data(TotalDur.of(stats.totalMinutes), rows, "今天还没有使用记录")
    }
}

/** 总时长：≥1h →「7h 25m」，不足 1h →「45m」 */
internal object TotalDur {
    fun of(minutes: Int): String =
        if (minutes >= 60) "${minutes / 60}h ${minutes % 60}m" else "${minutes}m"
}

/** 圆内短时长：≥1h 只给小时（5h），不足给分钟（29m）——照参考图的口径 */
internal object ShortDur {
    fun of(minutes: Int): String = if (minutes >= 60) "${minutes / 60}h" else "${minutes}m"
}

/** 手动刷新：重算当日分 App 数据并只重推本组件 */
class RefreshScreenTimeAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val ok = runCatching { ScreenTimeWidget().updateAll(context) }.isSuccess
        android.util.Log.i("ScreenTimeWidget", "手动刷新，重推=$ok")
    }
}

/** 位图画布（与 [WidgetArt] 同一套 TextPainter/比例体系，独立成对象避免继续膨胀） */
internal object ScreenTimeArt {

    class Row(val circle: String, val name: String, val minutes: Int)

    class Data(val total: String, val rows: List<Row>, val emptyText: String)

    /**
     * 总时长拆两行（「4h 10m」→ ["4h", "10m"]，用户 2026-10-28：宽度不够时换行显示，上小时下分钟）。
     * 纯分钟（"45m"）不拆。拆行让窄卡上的时间能用**更大的字号**——单行塞不下被迫缩字号，
     * 两行各自很短，通常基准字号就放得下。
     */
    private fun heroTexts(total: String): List<String> =
        if (total.contains("h") && total.contains("m")) {
            listOf(total.substringBefore("h").trim() + "h", total.substringAfter("h").trim())
        } else {
            listOf(total)
        }

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
        fun ink(frac: Float) = frac * h * fs
        // 可读下限（px）：标题 / 总时长 / 刷新是这张卡的「骨架」，随卡片缩小会一起缩到看不清。
        // 2026-10-28 用户要求：缩小时应该**砍信息行**（圆/名字），不砍骨架——所以这三样给密度下限，
        // 缩小后行数变少、骨架保持可读；放大后行数变多，骨架只按原比例缓增。
        val density = ctx.resources.displayMetrics.density
        fun floorInk(dp: Float) = dp * density * fs

        // 卡面：中性 surfaceContainer；圆角走 **ColorOS 连续曲率**（超椭圆 n=3.25，
        // 见 Squircle.kt），半轴 = 固定 26dp × density ——不按短边比例，否则与其它
        // 组件圆角一大一小（2026-11-01 用户「有的大有的小」）。
        canvas.drawPath(
            Squircle.cardPath(w.toFloat(), h.toFloat(), density),
            Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = colors.card }
        )
        val padX = w * 0.07f
        // **扁长方卡**（宽 > 1.35×高）：所有元素按 h 一刀切会缩成极小（用户实测被压扁后
        // 文字与圆看不清）——切换扁卡专用布局：标题一行，总时长居左、叠圆居右
        val flat = w > 1.35f * h

        // ── 标题行 ──（双向自适应：下限 11dp 保小卡可读、上限 18dp 防大卡上头部显小留大空白；
        // 刷新图标 = 1.5×标题，跟随整条链）
        val titleInk = ink(if (flat) 0.055f else 0.036f)
            .coerceIn(floorInk(11f), floorInk(18f))
        val titleTop = if (flat) h * 0.115f else h * 0.095f
        tp.sized("屏幕使用时间", titleInk, colors.onCard)
            .draw(canvas, padX, titleTop)
        drawRefreshIcon(canvas, w - padX - titleInk * 1.4f, titleTop + titleInk / 2f, titleInk * 1.5f, colors.sub)

        if (data.rows.isEmpty()) {
            tp.sized(data.emptyText, ink(0.045f), colors.sub)
                .ellipsize(w - padX * 2)
                .draw(canvas, padX, h * 0.66f)
            return bmp
        }

        // 一行分 App（圆 + 圆内数字 + 应用名）：常规/扁卡两套布局共用。
        // 圆色与卡面对比不足（如暗色下的深紫第三色）→ 提亮/压暗到可用，否则糊在卡上；
        // 圆上文字按**圆底亮度**选黑/白字（谷歌原件同款黑白两极）。
        fun drawRow(i: Int, row: Row, cx: Float, cy: Float, d: Float, nameX: Float, nameInk: Float) {
            val base = when (i % 3) {
                0 -> colors.primary
                1 -> colors.tertiary
                else -> colors.primaryContainer
            }
            val circleColor = contrastFix(base, colors.card, dark = luma(colors.card) < 0.5f)
            val onCircle = if (luma(circleColor) > 0.55f) 0xFF1B1B1F.toInt() else 0xFFF2F2F7.toInt()
            canvas.drawCircle(cx, cy, d / 2f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.color = circleColor
            })
            val cInk = d * 0.32f * fs
            tp.sized(row.circle, cInk, onCircle)
                .drawCentered(canvas, cx, cy - cInk / 2f)
            // 应用名与圆内时长共用同一条墨迹中线（cy）：名字墨迹顶 = cy - nameInk/2，
            // 之前是 cy + nameInk/2（墨迹顶坠到中线下面半格，用户指出「用时和名称没对齐」）
            tp.sized(row.name, nameInk, colors.onCard)
                .ellipsize(w - padX - nameX)
                .draw(canvas, nameX, cy - nameInk / 2f)
        }

        if (flat) {
            // 总时长居左：墨迹 0.26 卡高（不再按整卡 h 一刀切），最宽 0.34 卡宽；
            // 19dp 下限——矮卡上 hero 不跟着缩没（宽度不够时仍按宽收缩，宽是硬约束）。
            // **单行放不下 → 拆「上小时下分钟」两行**（行短，基准字号通常直接放得下，
            // 比把单行缩到下限以下 readable 得多）；两行整块在内容带里居中。
            val contentTop = h * 0.30f
            val contentBot = h * 0.94f
            val contentH = contentBot - contentTop
            val heroPainter = WidgetArt.TextPainter(ctx, fontKey, (weight + 200).coerceAtMost(900))
            val heroBase = maxOf(h * 0.26f, floorInk(19f))
            val heroMaxW = w * 0.34f - padX
            val texts = heroTexts(data.total)
            val singleW = heroPainter.sized(data.total, heroBase, colors.onCard).paint.measureText(data.total)
            if (singleW <= heroMaxW || texts.size == 1) {
                var hero = heroPainter.sized(data.total, heroBase, colors.onCard)
                if (singleW > heroMaxW) {
                    hero = heroPainter.sized(data.total, heroBase * heroMaxW / singleW, colors.onCard)
                }
                hero.draw(canvas, padX, contentTop + (contentH - hero.inkHeight) / 2f)
            } else {
                var ink = minOf(heroBase, contentH / 2.6f)
                texts.forEach { t ->
                    val tw = heroPainter.sized(t, ink, colors.onCard).paint.measureText(t)
                    if (tw > heroMaxW) ink *= heroMaxW / tw
                }
                val lh = ink * 1.32f
                val top0 = contentTop + (contentH - lh * 2) / 2f
                texts.forEachIndexed { i, t ->
                    heroPainter.sized(t, ink, colors.onCard).draw(canvas, padX, top0 + lh * i)
                }
            }
            // 叠圆右列：**大卡画满 3 行**（圆径随行数微缩，旧行为——2026-10-28 真机打脸：
            // "圆径不随行数压缩"在宽卡上撑成巨圆，contentH 只装得下 1 行，白白浪费整卡），
            // 圆径缩到可读下限（22dp）以下就**减一行换可读**：小卡 3→2→1 递减，
            // 这才是「缩小时显示更少信息」的正确实现——信息量跟着可读性走，不是二选一。
            // 圆径仍受卡宽封顶：只有 1 条数据时纯按高度会撑成巨圆、名字挤成「P…」（2×1 实机实测）。
            val x0 = w * 0.42f
            val dMin = floorInk(22f)
            var n = minOf(data.rows.size, 3)
            var d = minOf(contentH / (1f + 0.85f * (n - 1)) * 0.98f, w * 0.22f)
            while (n > 1 && d < dMin) {
                n -= 1
                d = minOf(contentH / (1f + 0.85f * (n - 1)) * 0.98f, w * 0.22f)
            }
            val step = d * 0.85f
            val stackTop = contentTop + (contentH - (d + step * (n - 1))) / 2f
            val nameX = x0 + d + w * 0.03f
            val nameInk = maxOf(minOf(ink(0.085f), d * 0.34f), floorInk(10f)).coerceAtMost(d * 0.42f)
            data.rows.take(n).forEachIndexed { i, row ->
                drawRow(i, row, x0 + d / 2f, stackTop + d / 2f + step * i, d, nameX, nameInk)
            }
            return bmp
        }

        // ── 总时长（hero）：用户连续反馈「太大」，在此前基础上再收一档；19dp 下限（同骨架原则）
        // **单行放不下 → 拆「上小时下分钟」两行**（每行短，基准字号通常放得下）；
        // 行区顶部随 hero 块的实际底部走 ──
        val heroPainter = WidgetArt.TextPainter(ctx, fontKey, (weight + 200).coerceAtMost(900))
        val heroInkBase = maxOf(ink(0.118f), floorInk(19f))
        val heroMax = w - padX * 2
        val texts = heroTexts(data.total)
        var heroInk = heroInkBase
        var heroLines = listOf(data.total)
        if (heroPainter.sized(data.total, heroInkBase, colors.onCard).paint.measureText(data.total) > heroMax &&
            texts.size == 2
        ) {
            // 两行的行高也要塞得下 hero 带（0.215h～0.44h）
            heroLines = texts
            heroInk = minOf(heroInkBase, (h * 0.44f - h * 0.215f) / 2.6f)
        }
        heroLines.forEachIndexed { i, t ->
            var p = heroPainter.sized(t, heroInk, colors.onCard)
            val tw = p.paint.measureText(t)
            if (tw > heroMax) p = heroPainter.sized(t, heroInk * heroMax / tw, colors.onCard)
            p.draw(canvas, padX, h * 0.215f + heroInk * 1.3f * i)
        }

        // 行区顶部：随 hero 块（单行/两行）的实际底部走
        val heroBlockBottom = h * 0.215f + heroInk * (1.3f * (heroLines.size - 1) + 1f)
        val top = maxOf(h * 0.440f, heroBlockBottom + heroInk * 0.35f)
        val bottom = h - h * 0.065f
        val band = bottom - top
        // **圆径从「占满行区」反推**（用户 2026-10-28：内容还是太小，要尽可能占满）：
        // 假设画 n 行（上限 12），圆径 = 行区高按 0.85d 叠压摊分、与卡宽 20% 取小者——
        // 行少时圆自动撑大、行多时摊薄，卡片始终被填满；圆径跌破 22dp 可读档就减行
        // （行数是结果不是参数）。旧写法把圆径钉死在 0.145w/0.115h，行一少卡片下半截全空。
        // **固定叠压**（安卓原生同款）：圆心距 = 0.85d，后圆被前圆压住 ~15%。
        val nameInkTarget = ink(0.042f)
        fun circleD(n: Int) = minOf(w * 0.20f, band / (1f + 0.85f * (n - 1)) * 0.96f)
        var n = minOf(data.rows.size, 12)
        var d = circleD(n)
        while (n > 1 && d < floorInk(22f)) {
            n -= 1
            d = circleD(n)
        }
        val shown = data.rows.take(n)
        val step = d * 0.85f
        val nameX = padX + d + w * 0.05f
        val nameInk = maxOf(minOf(nameInkTarget, d * 0.45f), floorInk(9f)).coerceAtMost(d * 0.55f)

        // **倒序绘制**：谷歌原件是上面的圆压住下面的圆（第一行完整、往下逐行被盖），
        // 正序画会反过来（用户实测指出的「重叠顺序不对」）。原生**不加描边**。
        // 整叠在行区内居中（圆径本就按 band 摊分，居中只是数据不足 n=行数 时的兜底）。
        val stackH = d + step * (shown.size - 1).coerceAtLeast(0)
        val start = top + (band - stackH).coerceAtLeast(0f) / 2f
        shown.indices.reversed().forEach { i ->
            val row = shown[i]
            val cy = start + d / 2f + step * i
            drawRow(i, row, padX + d / 2f, cy, d, nameX, nameInk)
        }
        return bmp
    }

    /** 0–1 相对亮度（sRGB 简化式，够用：只做黑/白字与对比判断） */
    private fun luma(argb: Int): Float {
        val r = (argb shr 16 and 0xFF) / 255f
        val g = (argb shr 8 and 0xFF) / 255f
        val b = (argb and 0xFF) / 255f
        return 0.299f * r + 0.587f * g + 0.114f * b
    }

    /** 圆色压在卡上分不清（亮度差 < 0.25）→ 按明暗模式把明度拉到可用档 */
    private fun contrastFix(base: Int, card: Int, dark: Boolean): Int {
        if (kotlin.math.abs(luma(base) - luma(card)) >= 0.25f) return base
        val hsv = FloatArray(3)
        android.graphics.Color.colorToHSV(base, hsv)
        hsv[2] = if (dark) 0.85f else 0.45f
        return android.graphics.Color.HSVToColor(hsv)
    }

    /** Material Icons「refresh」的标准 24dp path（apache 2.0），按目标边长缩放绘制 */
    private const val REFRESH_PATH =
        "M17.65,6.35C16.2,4.9 14.21,4 12,4c-4.42,0 -7.99,3.58 -7.99,8s3.57,8 7.99,8c3.73,0 " +
            "6.84,-2.55 7.73,-6h-2.08c-0.82,2.33 -3.04,4 -5.65,4 -3.31,0 -6,-2.69 -6,-6s2.69,-6 " +
            "6,-6c1.66,0 3.14,0.69 4.22,1.78L13,11h7V4l-2.35,2.35z"

    private fun drawRefreshIcon(canvas: Canvas, cx: Float, cy: Float, size: Float, tint: Int) {
        val path = androidx.core.graphics.PathParser.createPathFromPathData(REFRESH_PATH)
        val s = size / 24f
        val m = android.graphics.Matrix().apply {
            postScale(s, s)
            postTranslate(cx - 12f * s, cy - 12f * s)
        }
        path.transform(m)
        canvas.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = tint })
    }
}

class ScreenTimeWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = ScreenTimeWidget()
}
