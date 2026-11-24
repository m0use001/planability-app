package io.github.m0use001.planability.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.compose.ui.graphics.toArgb
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.ImageProvider
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.provideContent
import androidx.glance.layout.ContentScale
import androidx.glance.layout.fillMaxSize
import androidx.glance.LocalSize
import io.github.m0use001.planability.core.AppTime
import io.github.m0use001.planability.data.Graph
import java.time.temporal.ChronoUnit
import kotlin.math.abs

/**
 * 倒数日小组件——**币形（用户草图 2026-10-28 第五稿，确认项：正圆整卡 / 只显示最近
 * 一条 / 配色参考胶囊时钟且不加描边 / 下弧只排名字）**。
 *
 * ## 形状依据的修订
 * M3E 规范稿（上一版）按 shape 档位制定为矩形卡；但 shape--corner-radius-scale 原页
 * 同时写明「customization is … encouraged for **hero moments** or custom components」——
 * 币形即 hero 自定义形状，豁免成立。本轮依据用户草图 + 四项确认定稿：
 *
 * - **底座**：正圆 R=0.49S 整卡（S=min(W,H)），胶囊时钟同源卡面（`p.cardColor`），
 *   **无描边**；四角透壁纸（币形接受）。
 * - **中央**：天数大数字（Display 档，primary，emphasized 700），墨迹按
 *   「可行宽 0.72S / 可行高 0.52S / 上限 0.50S」自适应——位数多自动缩、只剩几天的
 *   短位数放大占满（2026-10-29 用户：天数/日期/名字加大）；0=「今天」（随宽度解自动缩）；
 *   「天」= 0.30× 数字墨迹（同轮缩小），同基线缀在数字右下（如草图）。
 * - **上弧**：目标日期 `2027.1.1`（自适应：短文本沿 100° 弧撑长、上限 0.075S，
 *   基档/长文本停靠 0.062S，onSurfaceVariant），沿内缘弧排
 *   （Canvas 逐字符旋转，字顶朝外，读向左→右）。
 * - **下弧**：倒数名（自适应：撑长上限 0.10S、停靠 0.068S、超长缩到 0.030S 才截尾，
 *   emphasized，onSurface），沿下内缘弧排、
 *   字顶朝心（硬币铭文惯例，读向左→右）；长名缩字，0.03S 下限才截断。备注不显示。
 * - 只显示最近一条（第二条不占币面）；落位 2×2（2026-10-29 用户：占地 3×3→2×2，
 *   币形全按 S 归一、等比缩）；resizeMode=none。
 * 文字统一走 [WidgetArt.TextPainter]：应用字体 + CJK/拉丁混排 run 拆分（防字符漂移）。
 */
class CountdownWidget : GlanceAppWidget() {

    /** Exact：拉伸时按真实格子重算位图（外部审查 P1-2/P2-9 同款） */
    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val today = AppTime.today()
        val entry = Graph.plan.countdowns().minByOrNull { it.nextDate(today) }

        provideContent {
            val ctx = androidx.glance.LocalContext.current
            val size = LocalSize.current
            val p = WidgetTheme.palette(context, size)
            val theme = Graph.settings.snapshot.value.theme
            val density = ctx.resources.displayMetrics.density
            val wPx = (size.width.value * density).toInt().coerceAtLeast(1)
            val hPx = (size.height.value * density).toInt().coerceAtLeast(1)

            val e = entry?.let {
                val target = it.nextDate(today)
                BlobEntry(
                    it.title,
                    target.year * 10000 + target.monthValue * 100 + target.dayOfMonth,
                    ChronoUnit.DAYS.between(today, target),
                    it.note
                )
            }

            androidx.glance.Image(
                provider = ImageProvider(
                    countdownCoinBitmap(
                        wPx, hPx, p.scale, ctx,
                        theme.fontFamily, 400 + theme.fontWeight,
                        p.cardColor.toArgb(),                  // 币面=胶囊时钟同源
                        // 2026-11-01 统一：数字与胶囊时钟的时间同色（此前用 accentColor 原值，
                        // 深色下比时钟时间暗一档，并排看是两个绿）。
                        p.heroNumberColor.toArgb(),            // 数字=主色强调数字（同时钟时间）
                        p.artOnCard.toArgb(),                  // 名字=首位文字（统一口径）
                        p.subColor.toArgb(),                   // 日期/「天」=onSurfaceVariant
                        e
                    )
                ),
                contentDescription = "倒数日",
                // 2026-11-01 用户实锤「点倒数日小组件就应该跳转倒数日」：此前落的是计划页
                // （clickOpenApp("plan")），而倒数日管理是首页的页内覆盖层。
                // 现在带 open="countdown" → MainActivity 转成 pendingWidgetCountdown → 首页打开它。
                modifier = GlanceModifier.fillMaxSize().clickOpenApp(
                    "home",
                    open = io.github.m0use001.planability.app.Router.WIDGET_OPEN_COUNTDOWN
                ),
                contentScale = ContentScale.FillBounds
            )
        }
    }
}

/** 一条倒数给画布用：标题、yyyymmdd 整数、剩余天数（0=今天）、备注（币形不显示） */
internal data class BlobEntry(val title: String, val date: Int, val days: Long, val note: String)

/**
 * 币形位图（几何/字阶见类 KDoc；比例全按 S=min(W,H) 归一）。
 * 弧排文字 = Canvas 逐字符旋转：上弧 rotate(θ) 画在 (0,−r)（字顶朝外），
 * 下弧 rotate(δ) 画在 (0,+r)（字顶朝心，硬币铭文惯例），读向均左→右。
 */
internal fun countdownCoinBitmap(
    wPx: Int, hPx: Int, scale: Float,
    ctx: Context, fontKey: String, fontWeight: Int,
    shell: Int, accent: Int, ink: Int, sub: Int,
    entry: BlobEntry?
): Bitmap {
    val bmp = Bitmap.createBitmap(wPx, hPx, Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)
    val s = minOf(wPx, hPx).toFloat()
    val cx = wPx / 2f
    val cy = hPx / 2f
    val cardR = s * 0.49f
    c.drawCircle(cx, cy, cardR, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = shell })

    val tp = WidgetArt.TextPainter(ctx, fontKey, fontWeight)
    val tpEm = WidgetArt.TextPainter(ctx, fontKey, fontWeight + 300)

    if (entry == null) {
        tpEm.sized("还没有倒数日", s * 0.055f * scale, ink)
            .drawCentered(c, cx, cy - s * 0.05f * scale)
        tp.sized("去计划页创建", s * 0.045f * scale, sub)
            .drawCentered(c, cx, cy + s * 0.025f * scale)
        return bmp
    }

    val padArc = s * 0.035f          // 弧排文字外缘到圆边的间隙
    // 2026-10-29 二轮：日期/名字**自适应放大**——短文本沿弧撑长（占 100° 弧跨的 75%/80%），
    // 上限 date 0.075S / name 0.10S（再大不是压中央天数就是贴圆缘）；基档 0.062S / 0.068S
    // 是长文本的停靠档，超长仍走缩字→截尾（名字下限 0.030S）。天数与「天」维持上轮定档。
    val dateInkBase = s * 0.062f * scale
    val nameInkBase = s * 0.068f * scale
    val dateStr = "%d.%d.%d".format(entry.date / 10000, entry.date / 100 % 100, entry.date % 100)

    // —— 上弧：目标日期（字顶朝外，基线圆半径 = R − pad − 基档墨高）——
    val dateBaseR = cardR - padArc - dateInkBase
    val spanD = Math.toRadians(100.0).toFloat() * dateBaseR
    val dUnit = tp.sized(dateStr, 100f, sub).bounds.width() / 100f
    val dateInk = (spanD * 0.75f / dUnit.coerceAtLeast(0.5f))
        .coerceIn(dateInkBase, s * 0.075f * scale)
    arcText(c, cx, cy, dateBaseR, dateStr, dateInk, sub, tp, topArc = true)

    // —— 下弧：倒数名（字顶朝心，基线圆半径 = R − pad）——
    val nameBaseR = cardR - padArc
    val spanN = Math.toRadians(100.0).toFloat() * nameBaseR
    val nUnit = tpEm.sized(entry.title, 100f, ink).bounds.width() / 100f
    var nInk = (spanN * 0.80f / nUnit.coerceAtLeast(0.5f))
        .coerceIn(nameInkBase, s * 0.10f * scale)
    // 超长保护沿用旧档：按 π·r·0.85 的可用弧长压回（arcText 内部还有 100° 跨度兜底）
    val arcAvail = (cardR - padArc) * 3.14159f * 0.85f
    if (nUnit * nInk > arcAvail) {
        nInk = (arcAvail / nUnit).coerceAtLeast(s * 0.030f)
    }
    arcText(c, cx, cy, nameBaseR, entry.title, nInk, ink, tpEm, topArc = false)

    // —— 中央天数：宽 0.72S / 高 0.52S / 上限 0.50S 自适应；「天」0.30× 同基线缀右下 ——
    val numText = if (entry.days == 0L) "今天" else abs(entry.days).toString()
    val probeN = tpEm.sized(numText, 100f, accent)
    val probeU = if (entry.days == 0L) null else tp.sized("天", 100f, sub)
    val kN = probeN.bounds.width() / 100f
    val kU = (probeU?.bounds?.width() ?: 0) / 100f
    val numGap = s * 0.018f
    val numInk = minOf(
        s * 0.50f,
        (s * 0.72f - numGap) / (kN + 0.30f * kU).coerceAtLeast(0.5f),
        s * 0.52f
    ).coerceAtLeast(s * 0.06f) * scale
    val num = tpEm.sized(numText, numInk, accent)
    val unit = if (probeU == null) null else tp.sized("天", numInk * 0.30f, sub)
    val totalW = num.bounds.width() + (unit?.let { numGap + it.bounds.width() } ?: 0f)
    // 墨迹垂直居中（行盒 fontMetrics 居中会让数字整体偏低）
    val baseline = cy + num.bounds.height() / 2f
    var x = cx - totalW / 2f
    c.drawText(numText, x - num.bounds.left, baseline, num.paint)
    if (unit != null) {
        c.drawText("天", x + num.bounds.width() + numGap - unit.bounds.left, baseline, unit.paint)
    }
    return bmp
}

/**
 * 沿圆弧逐字符排文字。[topArc]=true 时字顶朝外、居中于圆顶；false 时字顶朝心、
 * 居中于圆底（读向都为左→右）。[baseR] 为基线圆半径（[topArc] 时为墨高内收后的值）。
 * 长文本按弧长可用度自动缩墨（缩到 [minInk] 仍超长才截断掉尾部字符）。
 */
private fun arcText(
    c: Canvas, cx: Float, cy: Float, baseR: Float,
    text: String, ink: Float, color: Int, tp: WidgetArt.TextPainter, topArc: Boolean
) {
    if (text.isEmpty() || baseR <= 0f) return
    val minInk = ink * 0.55f
    var use = ink
    var chars = text
    // 弧长可用度：上/下弧各占约 100°（避开正上/正下与水平极点），留 6% 字间隙
    val spanAvail = Math.toRadians(100.0).toFloat() * baseR
    fun totalLen(inkH: Float): Float {
        var len = 0f
        val p = tp.sized("国", inkH, color).paint
        for (ch in chars) len += p.measureText(ch.toString())
        return len
    }
    while (totalLen(use) > spanAvail && use > minInk) use -= (ink * 0.04f).coerceAtLeast(0.5f)
    if (totalLen(use) > spanAvail) {                       // 仍超长 → 截尾
        val p = tp.sized("国", use, color).paint
        var len = 0f
        var cut = chars.length
        for (i in chars.indices) {
            len += p.measureText(chars[i].toString())
            if (len > spanAvail) { cut = i; break }
        }
        chars = chars.take(cut)
    }
    val paint = tp.sized("国", use, color).paint.apply { textAlign = Paint.Align.CENTER }
    val widths = chars.map { paint.measureText(it.toString()) }
    val total = widths.sum()
    var cum = 0f
    for ((i, ch) in chars.withIndex()) {
        val mid = cum + widths[i] / 2f
        // 上弧：θ 从 −total/2r（左）到 +total/2r（右），rotate(θ) 后画在 (0,−r)，字顶朝外；
        // 下弧：δ 从 +total/2r（左）到 −total/2r（右），rotate(δ) 后画在 (0,+r)，字顶朝心。
        // 全程弧度 = 弧长/半径，mid 与 total 同为弧长（px），一起除以 baseR。
        val theta = (if (topArc) mid - total / 2f else total / 2f - mid) / baseR
        val deg = Math.toDegrees(theta.toDouble()).toFloat()
        c.save()
        c.rotate(deg, cx, cy)
        if (topArc) c.drawText(ch.toString(), cx, cy - baseR, paint)
        else c.drawText(ch.toString(), cx, cy + baseR, paint)
        c.restore()
        cum += widths[i]
    }
}

class CountdownWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = CountdownWidget()
}
