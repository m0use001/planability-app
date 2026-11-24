package io.github.m0use001.planability.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.toArgb
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
import androidx.glance.layout.ContentScale as GlanceContentScale
import androidx.glance.layout.fillMaxSize
import io.github.m0use001.planability.core.AppTime
import io.github.m0use001.planability.data.Graph
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * 「今日」小组件：**今日总览 + 今日日程的合并款**（2026-10-24 用户拍板合并）。
 *
 * ## 形态（2026-11-02 时间轴改版，用户定案：强制 4×3 / ±1 小时窗口 / 去加号）
 * 一块中性 surfaceContainer 卡：
 *  - 顶部：日期一行（"11/2" 大 + "周日" 小）——保留；
 *  - 主体：**垂直时间轴**，只展示过去 1 小时到未来 1 小时内的定时日程——
 *    左侧三档刻度（-1h/现在/+1h）+ 主色 now 线，日程按起止时刻定位、并行分列；
 *  - 底部：一行统计（待办 / 日程 / 专注）——保留。
 *
 * ## 位图渲染
 * 与大时钟同一条路（[WidgetArt]）：Glance 的 Text 吃不到应用字体，
 * 整块画成图后字体/字重/字号跟随外观页，版面按卡片尺寸归一。
 * 旧的勾选待办行没有搬进来——位图上做不了可交互的勾选框，
 * 合并后的定位是「看」，勾待办去组件点开后的待办页。
 */
class TodayWidget : GlanceAppWidget() {

    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val today = AppTime.today()
        val data = runCatching { buildData(today) }.getOrDefault(
            WidgetArt.TodayData(
                dayNumber = today.dayOfMonth.toString(),
                weekday = today.format(WEEKDAY),
                month = today.format(MONTH),
                stats = "",
                events = emptyList(),
                emptyText = "未来 1 小时没有安排"
            )
        )
        provideContent { ArtFace { ctx, size, theme ->
            WidgetArt.renderToday(
                ctx, size, artColors(ctx, size), 400 + theme.fontWeight, theme.fontFamily,
                theme.fontScale, data
            )
        } }
    }

    private suspend fun buildData(today: java.time.LocalDate): WidgetArt.TodayData {
        val now = AppTime.nowMillis()
        // 统计口径保持「今天」全天；时间轴只取 [now-1h, now+1h] 窗口（2026-11-02 用户定案：
        // 只展示过去 1 小时到未来 1 小时）。窗口会跨午夜，所以查询范围扩到前后各一天，
        // 再按窗口过滤——展开两天的开销可忽略（本地 DB）。
        val occurrences = Graph.plan.scheduleOccurrences(today, today)
        val around = Graph.plan.scheduleOccurrences(today.minusDays(1), today.plusDays(1))
            .sortedBy { it.start }
        val timeline = around.filter { WidgetArt.inTimelineWindow(it.start, it.end, now) }
        val todos = Graph.plan.todosOfDay(AppTime.dayKey(today))
        val done = todos.count { it.done }
        // v51（2026-11-13 用户「没把当前待办与今天的未设置事件待办分开」）：待办按
        // **有没有设置时刻**分两路——due>0 的进时间轴（和日程同语言、按时刻定位，
        // now 线照常穿过=进行中），due==0 的才挂轴尾「不限时」圆点区。
        // 旧口径「待办没有时刻、放不进时间轴」是错的：Todo.due/endAt 一直是完整时段。
        val pendingTodos = todos.filter { !it.done }
        // v51.1（真库实锤）：重复待办的 due 停在**旧锚点**的时刻（背单词 date=11-13 但
        // due=11-12 20:30）——时刻只用 due 的 time-of-day，日子一律取 date 当天。
        // 非重复待办 due 本来就落在 date 当天，两种口径同值。
        val zone = AppTime.zone
        fun occStart(t: io.github.m0use001.planability.data.Todo): Long =
            Instant.ofEpochMilli(t.date).atZone(zone).toLocalDate()
                .atTime(Instant.ofEpochMilli(t.due).atZone(zone).toLocalTime())
                .atZone(zone).toInstant().toEpochMilli()
        val timedTodos = pendingTodos.filter { it.due > 0 }
            .map { t -> t to occStart(t) }
            .sortedBy { it.second }
        val untimedTodos = pendingTodos.filter { it.due == 0L }.sortedByDescending { it.percentDone }
        // 不限时区只给 3 行，多出的交给 todoMore（渲染侧收口为「还有 n 项」）
        val shownTodos = untimedTodos.take(3).map { WidgetArt.TodoLine(it.title, it.percentDone) }
        val todoMore = (untimedTodos.size - shownTodos.size).coerceAtLeast(0)
        // 有时刻的待办 → 时间轴块；endAt<=due（旧数据）按 30 分钟时长，与首页同口径
        val todoEvents = timedTodos.mapNotNull { (t, st) ->
            val end = if (t.endAt > t.due) st + (t.endAt - t.due) else st + 1800_000L
            if (!WidgetArt.inTimelineWindow(st, end, now)) return@mapNotNull null
            val s = Instant.ofEpochMilli(st).atZone(zone)
            val e = Instant.ofEpochMilli(end).atZone(zone)
            WidgetArt.TodayEvent(
                WidgetArt.EventKind.TODO_PENDING, t.title,
                "%02d:%02d–%02d:%02d".format(s.hour, s.minute, e.hour, e.minute),
                startMs = st, endMs = end
            )
        }
        // v51.1：窗外（±1h 之外）的有时刻待办不应凭空消失——下一条还没到点的，
        // 以「15:45 标题」一行挂在圆点区末尾（时刻主色，与不限时行区分）。
        val nextTodoText = timedTodos.firstOrNull { (t, st) ->
            val end = if (t.endAt > t.due) st + (t.endAt - t.due) else st + 1800_000L
            st > now && !WidgetArt.inTimelineWindow(st, end, now)
        }?.let { (t, st) ->
            val s = Instant.ofEpochMilli(st).atZone(zone)
            "%02d:%02d %s".format(s.hour, s.minute, t.title)
        } ?: ""
        // 2026-11-15 用户（截图）：组件把**明天**的日程画进了今天的时间轴（「下一场 ·
        // 明天 14:00 身体素质」出现在 17:00–18:00 轴段里），而当天 19:00 真正接下来的
        // 计划只在轴上一条普通行、不明显。根因就是下面这份 nextText 旧逻辑：它把
        // **任何**首条 start>now 的场次（含明天）当成补位文案塞进轴中部。
        //
        // 拆成两个语义清楚的东西：
        //  ① upcomingToday —— 今天、还没开始、且**不在 ±1h 窗内**的场次（按时刻升序）。
        //     它们不该进轴（轴是"离现在多久"的坐标系），但必须被看见 → 轴下单独一块。
        //  ② nextOtherDayText —— 今天确实再没有安排了，给出下一条（可能是明天）。
        //     只在①为空时才有意义，同样画在轴下那块里，不进轴。
        val upcomingToday = around.filter { occ ->
            val st = Instant.ofEpochMilli(occ.start).atZone(zone)
            st.toLocalDate() == today && occ.start > now &&
                !WidgetArt.inTimelineWindow(occ.start, occ.end, now)
        }.sortedBy { it.start }
            .map { occ ->
                val st = Instant.ofEpochMilli(occ.start).atZone(zone)
                val en = Instant.ofEpochMilli(occ.end).atZone(zone)
                WidgetArt.TodayEvent(
                    WidgetArt.EventKind.TIMED, occ.schedule.title,
                    "%02d:%02d–%02d:%02d".format(st.hour, st.minute, en.hour, en.minute),
                    startMs = occ.start, endMs = occ.end
                )
            }
        val nextOtherDayText = if (upcomingToday.isNotEmpty()) "" else run {
            // 今天（含窗内已进轴的）都没了，才看别的日子
            val next = around.firstOrNull { occ ->
                occ.start > now && Instant.ofEpochMilli(occ.start).atZone(zone).toLocalDate() != today
            }
            if (next == null) "" else {
                val st = Instant.ofEpochMilli(next.start).atZone(zone)
                val dayLabel = when (st.toLocalDate()) {
                    today -> "今天"
                    today.plusDays(1) -> "明天"
                    else -> "${st.monthValue}/${st.dayOfMonth}"
                }
                "下一场 · $dayLabel ${"%02d:%02d".format(st.hour, st.minute)} ${next.schedule.title}"
            }
        }
        val focusMin = Graph.focus.sessionsBetween(today, today)
            .filter { it.completed }.sumOf { (it.end - it.start) / 60000 }.toInt()
        // 时间轴事件：定时日程（TIMED）+ 有时刻的待办（TODO_PENDING，块底色不同以区分）
        val events = (timeline.map { occ ->
            val dt = Instant.ofEpochMilli(occ.start).atZone(AppTime.zone).toLocalDateTime()
            val dtEnd = Instant.ofEpochMilli(occ.end).atZone(AppTime.zone).toLocalDateTime()
            WidgetArt.TodayEvent(
                WidgetArt.EventKind.TIMED, occ.schedule.title,
                "%02d:%02d–%02d:%02d".format(dt.hour, dt.minute, dtEnd.hour, dtEnd.minute),
                startMs = occ.start, endMs = occ.end
            )
        } + todoEvents).sortedBy { it.startMs }
        val stats = buildString {
            append("待办 ").append(done).append("/").append(todos.size)
            append(" · 日程 ").append(occurrences.size).append(" 场")
            if (focusMin > 0) {
                val h = focusMin / 60
                val m = focusMin % 60
                append(" · 专注 ")
                if (h > 0) append(h).append(" 时 ") else append("")
                append(m).append(" 分")
            }
        }
        return WidgetArt.TodayData(
            dayNumber = today.dayOfMonth.toString(),
            weekday = today.format(WEEKDAY),
            month = today.format(MONTH),
            stats = stats,
            events = events,
            emptyText = "未来 1 小时没有安排",
            nowMs = now,
            todos = shownTodos,
            todoMore = todoMore,
            nextTodoText = nextTodoText,
            upcomingToday = upcomingToday,
            nextOtherDayText = nextOtherDayText
        )
    }

    private val WEEKDAY = DateTimeFormatter.ofPattern("EEE", Locale.CHINESE)
    private val MONTH = DateTimeFormatter.ofPattern("M月", Locale.CHINESE)
}

/** 两款位图组件共用的「画一张图铺满 + 点击」外壳；[openPlan] 时落计划页日程表 */
@Composable
internal fun ArtFace(
    openPlan: Boolean = true,
    render: (ctx: Context, size: androidx.compose.ui.unit.DpSize, theme: io.github.m0use001.planability.data.ThemeConfig) -> android.graphics.Bitmap
) {
    val ctx = LocalContext.current
    val size = LocalSize.current
    val theme = Graph.settings.snapshot.value.theme
    val bitmap = render(ctx, size, theme)
    Image(
        provider = ImageProvider(bitmap),
        contentDescription = null,
        modifier = GlanceModifier
            .fillMaxSize()
            .then(
                if (openPlan) GlanceModifier.clickOpenApp("plan", "schedule")
                else GlanceModifier
            ),
        contentScale = GlanceContentScale.FillBounds
    )
}

/** 从色板取一套位图原始色（ARGB）；容器/on 色直接取自 ColorScheme（拼色改版已算好） */
internal fun artColors(ctx: Context, size: androidx.compose.ui.unit.DpSize): WidgetArt.Colors {
    val p = WidgetTheme.palette(ctx, size)
    val s = p.scheme
    return WidgetArt.Colors(
        card = p.artCard.toArgb(),
        onCard = p.artOnCard.toArgb(),
        sub = p.subColor.toArgb(),
        primary = p.accentColor.toArgb(),
        onPrimary = s.onPrimary.toArgb(),
        primaryContainer = p.artPrimaryContainer.toArgb(),
        onPrimaryContainer = p.artOnPrimaryContainer.toArgb(),
        secondaryContainer = s.secondaryContainer.toArgb(),
        onSecondaryContainer = s.onSecondaryContainer.toArgb(),
        tertiaryContainer = s.tertiaryContainer.toArgb(),
        onTertiaryContainer = s.onTertiaryContainer.toArgb(),
        clockFace = p.clockCardColor.toArgb(),
        heroNumber = p.heroNumberColor.toArgb()
    )
}

class TodayWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = TodayWidget()
}
