package io.github.m0use001.planability.widget

import android.content.Context
import android.appwidget.AppWidgetManager
import androidx.compose.runtime.Composable
import androidx.glance.layout.ContentScale
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
import androidx.glance.layout.fillMaxSize
import io.github.m0use001.planability.core.AppTime
import io.github.m0use001.planability.data.Graph
import java.time.Instant
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

/**
 * 大时钟 · 数字（4×2 巨型胶囊；宽度可横向缩放——窄卡由 [ClockArt] 逐行 shrinkTo 按宽收缩，2026-10-29 解锁）。
 *
 * ## 形态
 * 参考安卓原生大时钟的「Digital」：整块**胶囊**（第二色实心面），中间一行巨型时间，
 * 上下各一行小字——日期在上、「今日完成率」在下。
 *
 * ## 为什么整块是一张位图
 * 内容由 [ClockArt] 用 Canvas 画：Glance 的 Text 用不了应用字体（见 ClockArt 的注释），
 * 也控制不了墨迹位置。位图让我们同时对齐三件东西——应用里选的字体、原生实测的
 * 字面占比（时间 38.7%/日期 8.8%）、以及配色。点击、深链、TalkBack 描述都照旧。
 *
 * ## 刷新
 * 时间是分钟级的：[WidgetClockTicker] 每分钟一次只重推两款时钟（熄屏跳过重绘、
 * 没有实例就断链）；日期/完成率由 [WidgetRefresh.all] 与跨零点 [WidgetDailyRefresh] 覆盖。
 *
 * ## 预览
 * AOSP/Pixel 启动器读 `widget_preview_clock.xml`（previewLayout），
 * ColorOS 只读 `widget_preview_clock.png`（previewImage，另有 -night 位图）。
 * 两套必须是同一个设计，改一处要同步另一处。
 */
class ClockWidget : GlanceAppWidget() {

    /** Exact：拉伸时重算 LocalSize（位图按真实像素重绘） */
    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val data = loadClockData()
        provideContent { ClockFace(ClockArt.Style.Digital, data) }
    }
}

/**
 * 大时钟 · 堆叠（2×3 竖版巨型胶囊）。
 *
 * 参考原生「Stacked」：小时在上、分钟在下各占一行大字（小时用第三色、分钟用主色，
 * 与原生双色数字同语义），日期贴顶、今日完成率贴底。
 */
class ClockStackedWidget : GlanceAppWidget() {

    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val data = loadClockData()
        provideContent { ClockFace(ClockArt.Style.Stacked, data) }
    }
}

/**
 * 两款共用：按当前尺寸/配色/字体画一张图铺满格子，点任意处打开首页。
 *
 * 字体与字号全部取**应用内的外观设置**（字体族、字重偏移、字号缩放），
 * 这就是「小组件同步字体」的落点——用户在外观页换成圆体，桌面时钟跟着换。
 */
@Composable
private fun ClockFace(style: ClockArt.Style, data: ClockData) {
    val ctx = LocalContext.current
    val size = LocalSize.current
    val palette = WidgetTheme.palette(ctx, size)
    val theme = Graph.settings.snapshot.value.theme
    val bitmap = ClockArt.render(
        ctx = ctx,
        size = size,
        colors = palette.clockPaintColors,
        weight = 400 + theme.fontWeight,
        fontKey = theme.fontFamily,
        fontScale = theme.fontScale,
        data = data,
        style = style
    )
    Image(
        provider = ImageProvider(bitmap),
        contentDescription = "${data.dateText} ${data.timeText} ${data.rateText}",
        modifier = GlanceModifier.fillMaxSize().clickOpenApp("home"),
        contentScale = ContentScale.FillBounds
    )
}

/** 两款共用的一份「此刻」数据 */
internal data class ClockData(
    /** 数字款整行时间：小时不补零（原生 Digital 就是 1:26，不是 01:26） */
    val timeText: String,
    /** 堆叠款小时：保两位，两行数字等宽对齐（原生 Stacked 是 01 / 26） */
    val hh: String,
    val mm: String,
    /** 日期：纯数字 + 斜杠（如 10/24，与「即将到来」等组件的 11/18 同一口径） */
    val dateText: String,
    val rateText: String
)

/**
 * **今日完成率**的口径与首页评分胶囊、`TodayStatsCard` 完全一致：
 * 分母 = 待办 + 整点任务；分子 = 两者已完成 + `doneCount`（完成后滚出当日列表的补位）。
 * 旧口径只数列表里的 `done`，在补位存在时会漏计——首页为此改过一次，这里直接沿用。
 * 拿不到数据（库未就绪等）就显示「—」，绝不让组件因取数异常整块消失。
 */
private suspend fun loadClockData(): ClockData {
    val now = AppTime.nowMillis()
    val zdt = Instant.ofEpochMilli(now).atZone(AppTime.zone)
    val today = AppTime.today()
    val rateText = runCatching {
        val day = Graph.plan.loadDay(today)
        val total = day.todos.size + day.hourTasks.size
        if (total == 0) {
            "今日完成率 —"
        } else {
            val done = day.todos.count { it.done } + day.hourTasks.count { it.done } + day.doneCount
            val pct = (done.toFloat() / total * 100).roundToInt().coerceIn(0, 100)
            "今日完成率 $pct%"
        }
    }.getOrDefault("今日完成率 —")
    return ClockData(
        timeText = "%d:%02d".format(zdt.hour, zdt.minute),
        hh = "%02d".format(zdt.hour),
        mm = "%02d".format(zdt.minute),
        dateText = zdt.toLocalDate().format(DateTimeFormatter.ofPattern("M/d")),
        rateText = rateText
    )
}

class ClockWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = ClockWidget()

    /** 放置/调整尺寸时把分钟级心跳续上（时钟延迟的修复链入口之一，见 WidgetClockTicker） */
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        super.onUpdate(context, appWidgetManager, appWidgetIds)
        WidgetClockTicker.schedule(context)
    }
}

class ClockStackedWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = ClockStackedWidget()

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        super.onUpdate(context, appWidgetManager, appWidgetIds)
        WidgetClockTicker.schedule(context)
    }
}
