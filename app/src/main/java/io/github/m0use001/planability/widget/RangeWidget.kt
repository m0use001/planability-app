package io.github.m0use001.planability.widget

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import androidx.collection.LruCache
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
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.ContentScale
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxHeight
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import io.github.m0use001.planability.R
import io.github.m0use001.planability.core.AppTime
import io.github.m0use001.planability.data.Graph
import io.github.m0use001.planability.data.ThemeConfig
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 「多天总览」小组件：一次看 **3 天 / 1 周 / 1 月** 的日程情况，范围点右上角胶囊切换
 * （三段直选，选择持久化）。**固定 4×4**（2026-11-01 用户「锁死 4×4 体积」，见
 * res/xml/widget_range.xml：targetCell 4×4 + resizeMode=none），版式按 4×4 的卡高做密：
 *  - 月：本月月历（周一起），今天主色圆底、每格最多 3 条日程色条，超出「+n」；
 *  - 周：本周 7 列，列头=星期·当日场数，列内日程条+开始时间（每列最多 6 行）；
 *  - 3 天：3 列，每列直接列当天日程标题+开始时间（最多 6 行）。
 *  - 主标题=真实日期区间（如「10/31–11/2」；月档是「2026年11月」），选中档位由胶囊高亮表达。
 *
 * ## 交互
 * 位图本身不能分区点击，Glance 层在右上角叠**三个透明热区**（几何对齐位图三段胶囊），
 * 点哪段进哪段（直选），点其余区域落到计划页日程表。
 * 热区用 [R.drawable.widget_pill_ripple] 作为 rippleOverride：Glance 默认水波按
 * 矩形 view 边界画（用户报「点胶囊弹长方形」），自定义 ripple 的胶囊 mask 让水波
 * 与位图里画出的范围胶囊同形。
 *
 * ## 性能（2026-10-28 两轮）
 * 切档链路原本 = **广播 trampoline** → onAction → updateAll → 重查数据 → 重画位图 → 推 RemoteViews：
 * ColorOS 对后台广播限流 + 进程冷启动，用户报「点击后切换延迟极大」甚至整段被吞（「完全无法切换」）。
 * 现改为：
 *  - 点击走**透明中转 Activity**（组件点按启动 Activity 不受后台启动限制，毫秒级）；
 *  - 渲染结果按「尺寸|主题明暗字体|数据内容|档位」缓存（[RangeBitmapCache]）；
 *  - 每次内容就绪后把**另外两档也预热**渲染好——切档时直接命中缓存，链路只剩
 *    重查数据（校验新鲜度用，几 ms）+ 推送。数据一变（增删日程/跨零点）数据签名
 *    随之变化，旧缓存自然失效，不会画出旧数据。
 *
 * ## 范围持久化
 * 组件自己的小偏好（SharedPreferences `widget_prefs`），不进 settings 表——
 * 它不属于应用功能状态，也不需要跨端同步。
 */
class RangeWidget : GlanceAppWidget() {

    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val range = WidgetRangeStore.current(context)
        val theme = Graph.settings.snapshot.value.theme
        val data = runCatching { buildRangeData(range) }.getOrDefault(
            WidgetArt.RangeData(range, "", null, null, "")
        )
        // 2026-11-01 真机实锤（用户「卡死在月视图」）：SizeMode.Exact 的 LocalSize 报的是
        // **可伸展最大区域**（4×4 桌面卡报 412×412dp > 屏幕短边 360dp！），不是实际占位。
        // 位图按 412dp 画（1443px=8.3MB）被桌面压回 ~249dp，渲染/重绘负担翻倍还爱掉；
        // **右上角三个直选热区按 412dp 在 249dp 的真实视图里摆**，比画出来的胶囊大 1.65 倍
        // 且左移——点画着的「3天」命中的热区是「周」，点「周」命中「月」，月档点「月」
        // =已是当前档，看起来就是"卡死"。修法同 TodayStatsWidget（phone-b 2026-11-01）：
        // 渲染/热区一律用 launcher 实际占位（AppWidgetOptions 的 MIN_WIDTH/MAX_HEIGHT）。
        val opts = android.appwidget.AppWidgetManager.getInstance(context)
            .getAppWidgetOptions(androidx.glance.appwidget.GlanceAppWidgetManager(context).getAppWidgetId(id))
        val realDpW = opts.getInt(android.appwidget.AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0)
        val realDpH = opts.getInt(android.appwidget.AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 0)
        android.util.Log.i(
            "RangeWidget",
            "组合尺寸 real=${realDpW}x${realDpH}dp local=LocalSize(组合期才可读)"
        )
        provideContent {
            val ctx = LocalContext.current
            // 尺寸三级：launcher 真实占位 > LocalSize（0×0/虚报时不可信）> 持久化上次有效
            // > 4×4 标称 250dp。0×0 见 effectiveSize（ColorOS 重加后首组合拿空 options 实锤）。
            val size = if (realDpW > 0 && realDpH > 0)
                DpSize(realDpW.dp, realDpH.dp)
            else effectiveSize(LocalSize.current, ctx)
            val colors = artColors(ctx, size)
            val themeKey = rangeThemeKey(theme, ctx)
            val dataKey = rangeDataKey(data)
            val bitmap = RangeBitmapCache.obtain(ctx, size, colors, themeKey, dataKey, range, theme, data)
            // contentAlignment=TopEnd：位图铺满（不受对齐影响），热区 Box 固定尺寸后锚在右上
            Box(
                GlanceModifier
                    .fillMaxSize()
                    // 2026-11-01：多天总览画的就是日历（3天/周/月），对应页是首页的月历视图
                    // （CalendarView 有同样的 3天/周/月/年四档），而不是计划页的日程表。
                    .clickOpenApp(
                        "home",
                        open = io.github.m0use001.planability.app.Router.WIDGET_OPEN_CALENDAR
                    ),
                contentAlignment = Alignment.TopEnd
            ) {
                Image(
                    provider = ImageProvider(bitmap),
                    contentDescription = null,
                    modifier = GlanceModifier.fillMaxSize(),
                    contentScale = ContentScale.FillBounds
                )
                RangeSegmentHotSpots()
            }
            // 把另外两档预热出来：下一次点切换直接命中缓存（用户报切换延迟高）。
            // 2026-10-31：延后 600ms——切档瞬间 launcher 正在重绑大位图，预热抢核会
            // 加重卡顿（用户反复报切档卡）。
            RangeBitmapCache.prewarmDelayed(ctx.applicationContext, size, colors, themeKey, range, theme)
        }
    }
}

/**
 * 组合画布尺寸防御：[WidgetArt.px] 对 0 会钳成 2×2（不崩但画出来是纯色块），
 * 而热区 [RangeSegmentHotSpots] 是按比例算的、0×0 时就是零尺寸——切档点击全死。
 * raw 无效时回退 [RangeBitmapCache.lastGoodSize]。
 */
internal fun effectiveSize(raw: DpSize, ctx: Context): DpSize {
    if (raw.width.value > 0f && raw.height.value > 0f) return raw
    return RangeBitmapCache.lastGoodSize(ctx)
}

/** 三档共用的取数（从 RangeWidget 提出来给预热协程用）：按范围查日程并组装位图数据 */
internal suspend fun buildRangeData(range: WidgetArt.Range): WidgetArt.RangeData {
    val today = AppTime.today()
    return when (range) {
        WidgetArt.Range.Month -> {
            val first = today.withDayOfMonth(1)
            val last = today.withDayOfMonth(today.lengthOfMonth())
            val byDay = Graph.plan.scheduleOccurrences(first, last)
                .groupBy { Instant.ofEpochMilli(it.start).atZone(AppTime.zone).toLocalDate() }
            val gridStart = first.with(DayOfWeek.MONDAY)
            val cells = (0 until 42).map { off ->
                val d: LocalDate = gridStart.plusDays(off.toLong())
                WidgetArt.MonthCell(
                    label = d.dayOfMonth.toString(),
                    inMonth = d.month == today.month,
                    isToday = d == today,
                    eventKinds = List(
                        // 上限 4 = 月历格能画的 3 条 + 「+n」还能再进位一位
                        //（2026-11-01 锁 4×4：每格从 2 条提到 3 条）
                        (byDay[d]?.size ?: 0).coerceAtMost(4)
                    ) { WidgetArt.EventKind.TIMED.ordinal }
                )
            }
            WidgetArt.RangeData(
                range = range,
                monthTitle = today.format(MONTH_YEAR_FMT),
                monthCells = cells,
                columns = null,
                // v50.7（用户 2026-11-05「取消场数统计」）：底部汇总行整体撤除。
                // 2026-11-04 验收轮加它是为补 4×4 底部空白，用户实测后判定场数是噪声。
                footer = ""
            )
        }
        WidgetArt.Range.Week, WidgetArt.Range.ThreeDays -> {
            val start = if (range == WidgetArt.Range.Week) today.with(DayOfWeek.MONDAY) else today
            val days = if (range == WidgetArt.Range.Week) 7 else 3
            val end = start.plusDays((days - 1).toLong())
            val byDay = Graph.plan.scheduleOccurrences(start, end)
                .groupBy { Instant.ofEpochMilli(it.start).atZone(AppTime.zone).toLocalDate() }
            val columns = (0 until days).map { off ->
                val d: LocalDate = start.plusDays(off.toLong())
                val events = byDay[d].orEmpty().sortedBy { it.start }
                WidgetArt.DayColumn(
                    // 周款 7 列每列只有 ~1/7 宽：列头给单字「六」；3 天款列宽够，给「周六」
                    head = weekdayChar(d).let { c ->
                        if (range == WidgetArt.Range.Week) "$c" else "周$c"
                    },
                    dateLabel = d.dayOfMonth.toString(),
                    events = events.map {
                        val dt = java.time.Instant.ofEpochMilli(it.start).atZone(AppTime.zone)
                            .toLocalDateTime()
                        // 胶囊下写开始时间（2026-10-28 用户：周视图"看不出几点"）
                        WidgetArt.TodayEvent(
                            WidgetArt.EventKind.TIMED, it.schedule.title,
                            "%02d:%02d".format(dt.hour, dt.minute)
                        )
                    },
                    isToday = d == today
                )
            }
            // v50.7（用户）：场数统计取消——「共 N 场」撤除，total 不再需要
            // 2026-11-01 4×4 密度改版：标题位让给真实日期区间（右上胶囊已高亮选中档，
            // 「本周/近 3 天」是零信息量占位）。monthTitle 字段即卡面主标题。
            val rangeLabel = "${start.monthValue}/${start.dayOfMonth}" +
                "–${end.monthValue}/${end.dayOfMonth}"
            WidgetArt.RangeData(
                range = range,
                monthTitle = rangeLabel,
                monthCells = null,
                columns = columns,
                footer = ""
            )
        }
    }
}

private val MONTH_YEAR_FMT = DateTimeFormatter.ofPattern("yyyy年M月", Locale.CHINESE)

/** 单字星期（一二三四五六日）：中文 EEE/EEEE 在部分 ROM 上会输出「周六/星期六」，单字最稳 */
private fun weekdayChar(d: LocalDate): Char = "一二三四五六日"[d.dayOfWeek.value - 1]

/**
 * 位图缓存 + 三档预热。键 = 尺寸|主题明暗字体|数据内容|档位——任何一项变化都自然失效，
 * 命中即免重画。LRU 上限 24MB（按 bitmap.byteCount 计），多实例/多主题共存时自动淘汰。
 */
internal object RangeBitmapCache {
    /** 进程级后台作用域：预热 + 直选页的推送闭环（2026-11-02 起中转页开门即 finish，
     *  闭环不能挂在 Activity 的 lifecycleScope 上——页面退了推送还得继续）。 */
    internal val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val inFlight = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<String, Boolean>())
    private val lru = object : LruCache<String, android.graphics.Bitmap>(24 shl 20) {
        override fun sizeOf(key: String, value: android.graphics.Bitmap): Int = value.byteCount
    }

    /**
     * 最近一次组合用的布局参数（尺寸/配色/主题键）。中转页预热新档位时要用同一套参数渲染，
     * 否则键对不上、白画。进程冷启动时从**持久化**恢复（2026-10-31：ColorOS 杀后台后点切档，
     * 组合期同步查库+画图是卡顿主因——现在冷启动也能先渲染再推送）；从未组合过则 null。
     */
    @Volatile var lastLayout: Layout? = null
    class Layout(val size: DpSize, val colors: WidgetArt.Colors, val themeKey: String)

    /**
     * 最近一次**组合期** [obtain] 的时间戳（`SystemClock.elapsedRealtime()`）。
     * 直选页用它当「重组确实发生了」的证据（[pushRangeWidgetVerified]）：只有真组合
     * 会进 obtain，预热/渲染缓存都不走这里。updateAll 的分支 A（新建 session）是
     * 异步组合——worker 起来才画，所以证据要轮询等，不能在 updateAll 返回处判。
     */
    @Volatile var lastCompositionAt: Long = 0

    private const val PREFS = "widget_prefs"
    private const val LAYOUT_KEY = "range_last_layout"
    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * 最近一次**有效**布局尺寸（持久化）；从未有过则给 4×4 标称 250×250dp——
     * 2026-11-01 起组件锁死 4×4（res/xml/widget_range.xml），这个兜底值与真实占位
     * 同数量级，位图 FillBounds 拉伸无损观感。
     */
    internal fun lastGoodSize(ctx: Context): DpSize =
        loadLayout(ctx)?.size ?: DpSize(250.dp, 250.dp)

    /** 组合期把布局参数落盘：冷启动切档时用来重建渲染参数（键与组合期一致）。
     *  2026-11-01：**0×0 不落盘**——ColorOS 重加组件后的首次组合可能拿到空 options
     *  （真机实锤：12:55:59 一次组合 persist 了 w:0,h:0，把上次的好布局冲掉，
     *  冷启动切档预热全废）。 */
    fun persistLayout(ctx: Context, size: DpSize, colors: WidgetArt.Colors, themeKey: String) {
        if (size.width.value <= 0f || size.height.value <= 0f) return
        val o = org.json.JSONObject()
            .put("w", size.width.value.toDouble())
            .put("h", size.height.value.toDouble())
            .put("theme", themeKey)
            .put("colors", org.json.JSONArray().apply {
                listOf(
                    colors.card, colors.onCard, colors.sub, colors.primary, colors.onPrimary,
                    colors.primaryContainer, colors.onPrimaryContainer, colors.secondaryContainer,
                    colors.onSecondaryContainer, colors.tertiaryContainer,
                    colors.onTertiaryContainer, colors.clockFace, colors.heroNumber
                ).forEach { put(it) }
            })
        prefs(ctx).edit().putString(LAYOUT_KEY, o.toString()).apply()
    }

    private fun loadLayout(ctx: Context): Layout? = runCatching {
        val s = prefs(ctx).getString(LAYOUT_KEY, null) ?: return null
        val o = org.json.JSONObject(s)
        // 2026-11-01：0×0（空 options 组合残留）当无效处理，走 lastGoodSize 兜底
        if (o.getDouble("w") <= 0.0 || o.getDouble("h") <= 0.0) return null
        val a = o.getJSONArray("colors")
        Layout(
            DpSize(o.getDouble("w").toFloat().dp, o.getDouble("h").toFloat().dp),
            WidgetArt.Colors(
                card = a.getInt(0), onCard = a.getInt(1), sub = a.getInt(2),
                primary = a.getInt(3), onPrimary = a.getInt(4),
                primaryContainer = a.getInt(5), onPrimaryContainer = a.getInt(6),
                secondaryContainer = a.getInt(7), onSecondaryContainer = a.getInt(8),
                tertiaryContainer = a.getInt(9), onTertiaryContainer = a.getInt(10),
                clockFace = a.getInt(11),
                // 第 13 项是 2026-11-01 加的 heroNumber；旧缓存只有 12 项，
                // optInt 拿到 0 → 渲染时退回 primary（不崩、只是那一档旧图重画时用旧色）
                heroNumber = a.optInt(12, 0)
            ),
            o.getString("theme")
        )
    }.getOrNull()

    /** 当前档取图：未命中同步画一张（首次/数据刚变过时），画完入缓存 */
    fun obtain(
        ctx: Context,
        size: DpSize,
        colors: WidgetArt.Colors,
        themeKey: String,
        dataKey: String,
        range: WidgetArt.Range,
        theme: ThemeConfig,
        data: WidgetArt.RangeData
    ): android.graphics.Bitmap {
        // 与 effectiveSize 同防御：obtain 任何调用点都不允许再把 0×0 写进 lastLayout/缓存键
        if (size.width.value <= 0f || size.height.value <= 0f) {
            return obtain(ctx, RangeBitmapCache.lastGoodSize(ctx), colors, themeKey, dataKey, range, theme, data)
        }
        lastCompositionAt = android.os.SystemClock.elapsedRealtime()
        lastLayout = Layout(size, colors, themeKey)
        persistLayout(ctx, size, colors, themeKey)
        val key = keyOf(size, themeKey, dataKey, range)
        lru.get(key)?.let { return it }
        val bmp = WidgetArt.renderRange(
            ctx, size, colors, 400 + theme.fontWeight, theme.fontFamily, theme.fontScale, data
        )
        lru.put(key, bmp)
        return bmp
    }

    /**
     * 预热**指定档位**（数据已备好）：中转页切档时先调这个，把新档位图画好入缓存，
     * 随后的 updateAll 组合期就是纯命中。lastLayout 为空（进程冷启动）时改用
     * **持久化布局**——这正是冷启动卡顿的解药：切档的取数+渲染在推送前完成，
     * 组合期纯命中，不再同步查库+画图。
     */
    fun warmOne(appCtx: Context, range: WidgetArt.Range, data: WidgetArt.RangeData) {
        val lay = lastLayout ?: loadLayout(appCtx) ?: return
        if (lastLayout == null) lastLayout = lay
        val theme = Graph.settings.snapshot.value.theme
        val key = keyOf(lay.size, lay.themeKey, rangeDataKey(data), range)
        if (lru.get(key) != null) return
        if (!inFlight.add(key)) return
        try {
            lru.put(
                key,
                WidgetArt.renderRange(
                    appCtx, lay.size, lay.colors, 400 + theme.fontWeight,
                    theme.fontFamily, theme.fontScale, data
                )
            )
        } finally {
            inFlight.remove(key)
        }
    }

    /** 后台预热另外两档（各自的数据单独取，键用自己的数据签名）。
     *  [delayMs] = 延迟启动：切档推送后 launcher 正在重绑位图，预热晚点再抢核 */
    fun prewarmDelayed(
        appCtx: Context,
        size: DpSize,
        colors: WidgetArt.Colors,
        themeKey: String,
        current: WidgetArt.Range,
        theme: ThemeConfig,
        delayMs: Long = 600
    ) {
        scope.launch {
            kotlinx.coroutines.delay(delayMs)
            prewarm(appCtx, size, colors, themeKey, current, theme)
        }
    }

    /** 后台预热另外两档（各自的数据单独取，键用自己的数据签名） */
    fun prewarm(
        appCtx: Context,
        size: DpSize,
        colors: WidgetArt.Colors,
        themeKey: String,
        current: WidgetArt.Range,
        theme: ThemeConfig
    ) {
        WidgetArt.Range.entries.filter { it != current }.forEach { r ->
            scope.launch {
                val data = runCatching { buildRangeData(r) }.getOrNull() ?: return@launch
                val key = keyOf(size, themeKey, rangeDataKey(data), r)
                if (lru.get(key) != null) return@launch
                if (!inFlight.add(key)) return@launch
                try {
                    lru.put(
                        key,
                        WidgetArt.renderRange(
                            appCtx, size, colors, 400 + theme.fontWeight,
                            theme.fontFamily, theme.fontScale, data
                        )
                    )
                } finally {
                    inFlight.remove(key)
                }
            }
        }
    }

    private fun keyOf(size: DpSize, themeKey: String, dataKey: String, range: WidgetArt.Range) =
        "${size.width.value.roundToInt()}x${size.height.value.roundToInt()}|$themeKey|$dataKey|${range.ordinal}"
}

/** 主题+明暗+字体签名：任一变化都让位图缓存失效（artColors/palette 的全部输入） */
internal fun rangeThemeKey(theme: ThemeConfig, ctx: Context): String {
    val dark = (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
        Configuration.UI_MODE_NIGHT_YES
    return "cs${theme.colorSource}|v${theme.wallpaperVariant}|s${theme.seed}|d$dark" +
        "|f${theme.fontFamily}|w${theme.fontWeight}|z${theme.fontScale}"
}

/** 数据内容签名：日程一变（增删改/跨零点）签名就变，旧缓存不会被复用 */
internal fun rangeDataKey(d: WidgetArt.RangeData): String = buildString {
    append(d.range.ordinal).append('|').append(d.monthTitle).append('|').append(d.footer)
    d.monthCells?.forEach { c ->
        append(c.label).append(if (c.inMonth) 'M' else '-').append(if (c.isToday) 'T' else '-')
            .append(c.eventKinds.size).append(';')
    }
    d.columns?.forEach { c ->
        append(c.head).append(c.dateLabel).append(c.events.size)
        c.events.forEach { append(it.title) }
        append(';')
    }
}.hashCode().toString()

/**
 * 右上角范围段的**三个直选热区**（2026-10-28 重做）：
 * - 位图画的是三段直选（3 天/周/月），点击必须点哪段进哪段——旧单热区循环与 UI 不匹配，
 *   用户点「周」却循环到「3 天」，体感就是「无法切换时间范围」。
 * - 直选需要三份 PendingIntent。Glance 的 actionRunCallback 走广播 trampoline，且
 *   PendingIntent 去重按 filterEquals（**不含 extras**），同组件多 callback 全命中第一份
 *   （829303c7 实测）。解法 = 每个 Intent 带不同的 **data Uri**（filterEquals 包含 data）
 *   + 透明中转 [RangeSwitchActivity]。
 * - 中转是 Activity 而不是广播：ColorOS 对后台广播限流 + 进程冷启动，用户报
 *   「点击后切换延迟极大」甚至整段被吞；组件点按启动 Activity 不受后台启动限制，毫秒级。
 * - 几何对齐位图（WidgetArt.renderRange）：胶囊 = 右缘 5%w 起、宽 34%w、顶 3.5%h、高 8.5%h；
 *   热区行宽 40%w（内边距 start 1%w / end 5%w → 内容正好 0.61w..0.95w 三等分）、
 *   高 14%h（覆盖胶囊 0.035h..0.12h 并留防误触余量）。
 * - 水波形状用自定义胶囊 mask（[R.drawable.widget_pill_ripple]，用户报默认按热区矩形画）。
 */
/**
 * 右上角范围段的**三个直选热区**——全比例 weight 网格（2026-11-01 重做）。
 *
 * 历史教训：旧实现按 LocalSize 折算**绝对 dp** 摆热区，而 ColorOS 谎报组件尺寸
 * （4×4 报 412dp，比实际占位 ~276dp 虚大 50%，且组合时有 0×0），绝对 dp 热区在
 * 真实视图里比位图胶囊大 1.65 倍且左移——点画着的「3天」命中的是「周」，点「周」
 * 命中「月」，月档点「月」=已是当前档 → 用户看到的就是「卡死在月视图」。
 * weight 网格由 launcher 按**真实视图**解算，与谎报的 dp 无关，位图（FillBounds
 * 压进同一视图）只要按同一套比例画就严格对齐（见 WidgetArt.renderRange 注释）。
 *
 * 结构（Glance 1.1 只有等权 defaultWeight，任意整数比例用「多放几个子项」表达）：
 *  - 外层 Column 三等分卡高；顶部 1/3 内部再五等分，第 2、3 行是热区带（高 6.7%..20%）；
 *  - 每行 Row **九**等分卡宽：左 6 份不设 clickable（点击穿透到卡面=打开应用），
 *    右 3 份=三档热区，各占卡宽 11.1%——与位图胶囊区（0.667w..1.0w 三等分）同式。
 * - 水波 mask 用 [R.drawable.widget_pill_ripple]（Glance 默认水波按矩形 view 边界画，
 *   用户报「点胶囊弹长方形」）。
 */
@Composable
private fun RangeSegmentHotSpots() {
    val ctx = LocalContext.current
    Column(GlanceModifier.fillMaxSize()) {
        Column(GlanceModifier.fillMaxWidth().defaultWeight()) {  // 顶部 1/3 卡高
            Spacer(GlanceModifier.defaultWeight())               // 让给标题行（0..6.7%h）
            segmentRow(ctx, GlanceModifier.defaultWeight())      // 热区带 6.7%..13.3%h
            segmentRow(ctx, GlanceModifier.defaultWeight())      // 13.3%..20%h（两行同映射，命中带加宽）
            Spacer(GlanceModifier.defaultWeight())
            Spacer(GlanceModifier.defaultWeight())
        }
        Spacer(GlanceModifier.defaultWeight())                   // 下 2/3 卡高留给内容
        Spacer(GlanceModifier.defaultWeight())
    }
}

/** 一条热区行：卡宽九等分，右 3 份对应 3 天/周/月（与位图胶囊三段同式）。
 *  rowModifier 从调用点传（defaultWeight 只在 Row/Column content 作用域内可解析）。 */
@Composable
private fun segmentRow(ctx: Context, rowModifier: GlanceModifier) {
    Row(rowModifier.fillMaxWidth()) {
        Spacer(GlanceModifier.defaultWeight())
        Spacer(GlanceModifier.defaultWeight())
        Spacer(GlanceModifier.defaultWeight())
        Spacer(GlanceModifier.defaultWeight())
        Spacer(GlanceModifier.defaultWeight())
        Spacer(GlanceModifier.defaultWeight())
        WidgetArt.Range.entries.forEach { r ->
            Box(
                GlanceModifier
                    .defaultWeight()
                    .fillMaxHeight()
                    .clickable(actionStartActivity(rangeTapIntent(ctx, r)), R.drawable.widget_pill_ripple)
            ) {}
        }
    }
}

/** 直选 Intent：data Uri 各不相同（filterEquals 因此不同），三份 PendingIntent 不会被去重合并。
 *  FLAG_ACTIVITY_NO_ANIMATION：关掉「从组件打开应用」的弹跳动画（用户：组件向上弹一下）——
 *  透明中转页本来无感，动画反而让人误以为点错了。 */
private fun rangeTapIntent(context: Context, r: WidgetArt.Range): Intent =
    Intent(Intent.ACTION_VIEW, Uri.parse("planability://range/${r.ordinal}"))
        .setClass(context, RangeSwitchActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)

/**
 * 拆掉 Glance session 的 unique work 并**等到 WorkInfo 真正离开 RUNNING**。
 *
 * Glance 1.1.1 的组合跑在 WorkManager unique work 里，名字 = `"appWidget-<id>"`
 * （`AppWidgetUtilsKt.createUniqueRemoteUiName`，版本耦合——升级 Glance 必须复核）。
 * `GlanceAppWidget.update()` 先查 `isSessionRunning`（WorkInfo==RUNNING）：在跑就只给
 * 既有 session **发事件**（updateGlance → sendEvent），不重组。ColorOS 在 app 退后台后
 * 会停掉 session worker，WorkInfo 却仍标 RUNNING——事件进了死协程，`updateAll`
 * **静默无效**。真机实测（23:38）：冷启动 tap1 走新建分支必好，tap2 起全灭——
 * 用户原话「点第一下可以，剩下就不行了」。
 *
 * **isSessionRunning 是双判据**（23:49 字节码取证）：WorkInfo ∈ {RUNNING, **ENQUEUED**}
 * **或** SessionManagerImpl 内存 `sessions` 映射里有该 key。cancel 只清 WM 侧——
 * ColorOS 杀 worker 时 session 协程没跑 finally，**映射里的尸体 session 仍在**，
 * 第一次 updateAll 仍走分支 B（发事件进死通道）；尸体随第一次 sendEvent/收尾被清，
 * 第二次 updateAll 才走分支 A。这就是「总慢一轮」的完整解释，也是重试闭环
 * （[pushRangeWidgetVerified]）存在的理由——单次 cancel 无法保证分支 A。
 *
 * **cancel 本身也是异步的**（23:44 实测）：不 await 落库事务，cancel 后 10ms 查
 * WorkInfo 仍是 RUNNING、~1-2.8s 后才生效——所以取消后必须 `Operation.result.get()`
 * 等事务落库 + 轮询复核。
 */
internal suspend fun cancelGlanceSessionWork(context: Context, receiver: Class<*>, waitMs: Long = 600) {
    runCatching {
        val wm = androidx.work.WorkManager.getInstance(context)
        val ids = android.appwidget.AppWidgetManager.getInstance(context)
            .getAppWidgetIds(android.content.ComponentName(context, receiver))
        for (id in ids) {
            val key = "appWidget-$id"
            // **await cancel 的落库事务**：Operation.result 是同步屏障，等到它完成，
            // 后续 getWorkInfosForUniqueWork 才保证读到 CANCELLED（isSessionRunning 的判据）
            runCatching { wm.cancelUniqueWork(key).result.get() }
            val deadline = android.os.SystemClock.elapsedRealtime() + waitMs
            while (android.os.SystemClock.elapsedRealtime() < deadline) {
                val infos = runCatching { wm.getWorkInfosForUniqueWork(key).get() }.getOrNull()
                if (infos.isNullOrEmpty() || infos.all { it.state != androidx.work.WorkInfo.State.RUNNING }) break
                kotlinx.coroutines.delay(40)
            }
        }
    }.onFailure { android.util.Log.e("RangeWidget", "取消 Glance session 失败", it) }
}

/**
 * 直选页的**推送闭环**：拆 session → updateAll → 等重组证据。
 *
 * 为什么不能只调 updateAll：分支 A（新建 session）的组合在 worker 里**异步**跑，
 * updateAll 返回时画没画未知；分支 B（死 session）则根本不画。用
 * [RangeBitmapCache.lastCompositionAt] 作组合证据（只有真组合会碰 obtain），
 * ≤1s 没等到就整轮重试一次（再拆再推）。返回是否确认重组发生。
 */
internal suspend fun pushRangeWidgetVerified(appCtx: Context): Boolean {
    for (attempt in 1..2) {
        cancelGlanceSessionWork(appCtx, RangeWidgetReceiver::class.java)
        val before = RangeBitmapCache.lastCompositionAt
        runCatching { RangeWidget().updateAll(appCtx) }
            .onFailure { android.util.Log.e("RangeWidget", "推送失败(第${attempt}轮)", it) }
        // 分支 A 的组合正常 24-60ms 内开跑（obtain 入口即打证据）；250ms 没见到就算失败。
        // 尸体 session 的第一轮 updateAll 注定走分支 B（发事件进死通道），靠这轮把尸体清掉，
        // 第二轮走分支 A——所以别把单轮等待拖长，快速失败进第二轮更划算。
        val deadline = android.os.SystemClock.elapsedRealtime() + 250
        while (android.os.SystemClock.elapsedRealtime() < deadline) {
            if (RangeBitmapCache.lastCompositionAt != before) {
                android.util.Log.i("RangeWidget", "推送确认：重组已发生（第${attempt}轮）")
                return true
            }
            kotlinx.coroutines.delay(30)
        }
        android.util.Log.w("RangeWidget", "第${attempt}轮 updateAll 未见到重组（疑似死 session），重试")
    }
    android.util.Log.e("RangeWidget", "两轮推送均未确认重组，组件可能不换图")
    return false
}

/** 循环范围：3 天 → 周 → 月 → 3 天。**遗留类**——直选改走 [RangeSwitchActivity] 后不再被新
 *  RemoteViews 引用，但已放置组件的旧 PendingIntent 仍指向它，类必须保留在清单里。 */
class CycleRangeAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val t0 = android.os.SystemClock.elapsedRealtime()
        WidgetRangeStore.cycle(context)
        // 只重推本组件：数据没变，不必触发全量刷新；位图缓存命中时这次重推只是换图。
        // 拆 session + 等重组证据（见 [pushRangeWidgetVerified]）：退后台后的 updateAll
        // 不做这两步就是发事件进死通道。
        val ok = pushRangeWidgetVerified(context.applicationContext)
        android.util.Log.i(
            "RangeWidget",
            "范围切到 ${WidgetRangeStore.current(context)}，重推=$ok，耗时 ${android.os.SystemClock.elapsedRealtime() - t0}ms"
        )
    }
}

/** 范围偏好（组件本地，SharedPreferences；默认月视图——参考图就是月历） */
object WidgetRangeStore {
    private const val PREFS = "widget_prefs"
    private const val KEY = "range_ordinal"

    internal fun current(context: Context): WidgetArt.Range {
        val idx = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY, WidgetArt.Range.Month.ordinal)
        return WidgetArt.Range.entries.getOrElse(idx) { WidgetArt.Range.Month }
    }

    fun set(context: Context, ordinal: Int) {
        val next = WidgetArt.Range.entries.getOrElse(ordinal) { WidgetArt.Range.Month }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putInt(KEY, next.ordinal).apply()
    }

    fun cycle(context: Context) {
        val next = WidgetArt.Range.entries[(current(context).ordinal + 1) % WidgetArt.Range.entries.size]
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putInt(KEY, next.ordinal).apply()
    }
}

class RangeWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = RangeWidget()
}
