package io.github.m0use001.planability.ui.home

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import io.github.m0use001.planability.core.AppTime
import io.github.m0use001.planability.core.HapticEngine
import io.github.m0use001.planability.core.LocalHaptics
import io.github.m0use001.planability.core.toLocalDate
import io.github.m0use001.planability.data.Graph
import io.github.m0use001.planability.ui.common.AppIcons
import io.github.m0use001.planability.ui.common.pressScale
import io.github.m0use001.planability.ui.common.categoryColor
import io.github.m0use001.planability.ui.common.parseLabels
import androidx.compose.ui.graphics.luminance
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.ZoneId
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * v73（2026-11-21 用户：3天/周视图直接拖块改时间，不改时长）拖动落点计算——**纯函数**。
 *
 * 手指位移（dp）→ 新起止时刻（epoch ms）：
 *  · 竖向：`deltaYDp / hourDp` 小时 → 吸附到 15 分钟刻度（40dp/小时下 15min=10dp，跟手且边界清晰）；
 *  · 横向：`deltaXDp / dayColWidthDp` 四舍五入 = 跨了几天（可负）；
 *  · 时长 `(origEndMs - origStartMs)` 毫秒**原样保留**——只平移，不改长度（用户明确要求）；
 *  · 越界钳制：start 不早于当天 0:00，end 不晚于当天 24:00（钳 start，时长仍不变）。
 *
 * [CalendarDropTest] 在整刻度/吸附/跨天/上下越界四类点钉几何。
 */
internal fun computeDropMillis(
    origStartMs: Long,
    origEndMs: Long,
    deltaXDp: Float,
    deltaYDp: Float,
    dayColWidthDp: Float,
    hourDp: Float,
    zone: ZoneId
): Pair<Long, Long> {
    val durationMs = (origEndMs - origStartMs).coerceAtLeast(0)
    val deltaMin = Math.round(deltaYDp / hourDp * 60f / 15f) * 15L
    val deltaDays = Math.round(deltaXDp / dayColWidthDp)
    val orig = java.time.Instant.ofEpochMilli(origStartMs).atZone(zone)
    // 时间分量钳在 [0, 1440-duration]，保证整块落在一天内（跨日不切割）
    val durMin = (durationMs / 60000L).coerceAtLeast(0)
    val minuteOfDay = (orig.hour * 60 + orig.minute + deltaMin)
        .coerceIn(0L, (24L * 60 - durMin).coerceAtLeast(0L))
    val newStart = orig.toLocalDate()
        .plusDays(deltaDays.toLong())
        .atStartOfDay(zone)
        .plusMinutes(minuteOfDay)
    return newStart.toInstant().toEpochMilli() to newStart.toInstant().toEpochMilli() + durationMs
}

/**
 * 日历多日时间轴拖动中的瞬时状态（v73）。refId+kind 定位块，delta* 是手指累计位移（dp）；
 * 其余字段是**拖起那一刻**的块快照（起止时刻从展示值重建、几何与外观），拖动渲染
 * （浮起块 + 原位 ghost + 目标列高亮）全部从这份快照画，不依赖 bundles 实时值。
 */
internal data class CalDragState(
    val refId: Long,
    val kind: String,               // "schedule" / "todo"
    val origStartMs: Long,
    val origEndMs: Long,
    val origDayIndex: Int,
    val origTopDp: Float,
    val laneXDp: Float,             // 块在列内的 x（laneW*lane）
    val blockWDp: Float,
    val blockHDp: Float,
    val title: String,
    val color: Color,
    val done: Boolean,
    val deltaXDp: Float = 0f,
    val deltaYDp: Float = 0f
)

/** 日历族：月视图（完成度环+倒数日角标）/ 3天与周多日时间轴 / 年视图。v12：返回键并入导航行，压缩顶部占用 */
@Composable
fun CalendarView(
    selected: LocalDate,
    onPick: (LocalDate) -> Unit,
    onCountdowns: () -> Unit = {},
    onBack: () -> Unit = {},
    // v54.4-P2：多天时间轴的块可点（点块=进编辑器），不再"只读必须先选那天"
    onEditTodo: (Long) -> Unit = {},
    onEditSchedule: (Long) -> Unit = {}
) {
    var monthIndex by remember(selected) {
        mutableIntStateOf(YearMonth.from(selected).year * 100 + YearMonth.from(selected).monthValue)
    }
    var anchor by remember(selected) { mutableStateOf(selected) }
    var mode by remember { mutableIntStateOf(0) } // v10：0 三天 1 周 2 月 3 年
    val haptics = LocalHaptics.current
    // v59：宽屏月视图右侧补「选中日事项」栏（否则 620dp 的月历浮在 1200dp 中间，四周仍是大片空白）
    val wide = io.github.m0use001.planability.core.rememberWindowSizeClass().isWide
    val ym = YearMonth.of(monthIndex / 100, monthIndex % 100)

    // 数据：月内倒数日角标
    var countdownMarks by remember(ym) { mutableStateOf(emptySet<LocalDate>()) }
    androidx.compose.runtime.LaunchedEffect(ym) {
        val from = ym.atDay(1)
        val to = ym.atEndOfMonth()
        countdownMarks = buildSet {
            Graph.plan.countdowns().forEach { c ->
                if (c.weekly) {
                    // 每周几型：月内所有该周几都标角标
                    var d = from
                    while (!d.isAfter(to)) {
                        if (d.dayOfWeek.value == c.weekday) add(d)
                        d = d.plusDays(1)
                    }
                } else if (c.monthly) {
                    // 每月几号型：月内该号（小月取月末）都标角标
                    val day = minOf(c.dayOfMonth, ym.lengthOfMonth())
                    add(ym.atDay(day))
                } else {
                    val t = c.target.toLocalDate()
                    if (!t.isBefore(from) && !t.isAfter(to)) add(t)
                }
            }
        }
    }

    // 数据：月内每天完成度 done/total（口径同首页 ScoreAndCompletionRow）
    // v71 卡顿根修：31 次 loadDay 挪到 IO——原来跑在主线程，切到月视图整帧冻结
    //（叠加 boardFrames 互刷 = 用户报的「每切换视图都卡顿一下」）。
    var monthCompletion by remember(ym) { mutableStateOf(emptyMap<LocalDate, Pair<Int, Int>>()) }
    androidx.compose.runtime.LaunchedEffect(ym) {
        val map = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val m = mutableMapOf<LocalDate, Pair<Int, Int>>()
            (1..ym.lengthOfMonth()).forEach { d ->
                val date = ym.atDay(d)
                val b = Graph.plan.loadDay(date)
                val total = b.todos.size + b.hourTasks.size
                val done = b.todos.count { it.done } + b.hourTasks.count { it.done } + b.doneCount
                m[date] = done to total
            }
            m
        }
        monthCompletion = map
    }

    // v71：年视图逐日数据——**全年**按月分批 IO 加载、渐进填充（原来误传了「当月」的
    // monthCompletion，其余 11 个月永远没有逐日状态）。仅在年视图需要时才加载。
    val viewYear = monthIndex / 100
    var yearCompletion by remember(viewYear) { mutableStateOf(emptyMap<LocalDate, Pair<Int, Int>>()) }
    var yearMarks by remember(viewYear) { mutableStateOf(emptySet<LocalDate>()) }
    androidx.compose.runtime.LaunchedEffect(viewYear, mode) {
        if (mode != 3) return@LaunchedEffect
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            for (m in 1..12) {
                val ymY = YearMonth.of(viewYear, m)
                val md = mutableMapOf<LocalDate, Pair<Int, Int>>()
                (1..ymY.lengthOfMonth()).forEach { d ->
                    val date = ymY.atDay(d)
                    val b = Graph.plan.loadDay(date)
                    val total = b.todos.size + b.hourTasks.size
                    val done = b.todos.count { it.done } + b.hourTasks.count { it.done } + b.doneCount
                    md[date] = done to total
                }
                val marks = buildSet {
                    Graph.plan.countdowns().forEach { c ->
                        if (c.weekly) {
                            var d = ymY.atDay(1)
                            while (!d.isAfter(ymY.atEndOfMonth())) {
                                if (d.dayOfWeek.value == c.weekday) add(d)
                                d = d.plusDays(1)
                            }
                        } else if (c.monthly) {
                            add(ymY.atDay(minOf(c.dayOfMonth, ymY.lengthOfMonth())))
                        } else {
                            val t = c.target.toLocalDate()
                            if (t.year == viewYear) add(t)
                        }
                    }
                }
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    yearCompletion = yearCompletion + md
                    yearMarks = yearMarks + marks
                }
            }
        }
    }

    Box(Modifier.fillMaxSize()) {
        // ---- 导航行（返回 / 翻页 / 期间标题 / 倒数日）----
        // 抽成 lambda：宽屏两板与窄屏单列共用一份（复制第二套 = 下次改必漏）。
        val calendarNavRow: @Composable () -> Unit = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // v12：返回键并入本行（日历顶部只保留这一行导航）
                // 顶栏返回 = 中性点击 → Tap
                androidx.compose.material3.IconButton(onClick = { haptics.perform(HapticEngine.Kind.Tap); onBack() }) {
                    // R11：导航图标（返回/翻页/入口）不表态 → 中性；主色只留给选中/主操作/状态
                    Icon(AppIcons.Nav.Back, contentDescription = "返回", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                // 翻页是 Prev/Next 语义 → Select
                androidx.compose.material3.IconButton(onClick = {
                    haptics.perform(HapticEngine.Kind.Select)
                    when (mode) {
                        0 -> anchor = anchor.minusDays(3)
                        1 -> anchor = anchor.minusDays(7)
                        3 -> monthIndex -= 100
                        else -> {
                            val p = ym.minusMonths(1); monthIndex = p.year * 100 + p.monthValue
                        }
                    }
                }) { Icon(AppIcons.Nav.Prev, contentDescription = "上一期", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                Text(
                    when (mode) {
                        0 -> "${shortDay(anchor.minusDays(1))}–${shortDay(anchor.plusDays(1))}"
                        1 -> "${shortDay(anchor.with(java.time.DayOfWeek.MONDAY))}–${shortDay(anchor.with(java.time.DayOfWeek.MONDAY).plusDays(6))}"
                        3 -> "${ym.year}年"
                        else -> "${ym.year}年${ym.monthValue}月"
                    },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
                androidx.compose.material3.IconButton(onClick = {
                    haptics.perform(HapticEngine.Kind.Select)
                    when (mode) {
                        0 -> anchor = anchor.plusDays(3)
                        1 -> anchor = anchor.plusDays(7)
                        3 -> monthIndex += 100
                        else -> {
                            val n = ym.plusMonths(1); monthIndex = n.year * 100 + n.monthValue
                        }
                    }
                }) { Icon(AppIcons.Nav.Next, contentDescription = "下一期", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                androidx.compose.material3.IconButton(onClick = onCountdowns) {
                    Icon(AppIcons.Kind.Flag, contentDescription = "倒数日", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        // ---- 视图切换（3天 / 周 / 月 / 年）----
        val calendarModeGroup: @Composable () -> Unit = {
            // M3E connected button group（替代已弃用的 SegmentedButton；切换触感由组件内置）
            io.github.m0use001.planability.ui.common.AppButtonGroup(
                options = listOf("3天", "周", "月", "年"),
                selectedIndex = mode,
                onSelect = { mode = it },
                // v59 平板：四段按钮在 1248dp 宽下每段 300dp（被拉成大按钮）。
                // 分段控件按内容宽度走，最长 420dp。
                modifier = Modifier.widthIn(max = 420.dp)
            )
        }
        // 2026-11-20 v65：宽屏月视图改走共享两板骨架（SplitBoards）——
        // 左板=导航行 + 视图切换 + 月历，右板=选中日事项，
        // 两块 24dp 圆角板 + 12dp 白缝，与首页/计划页同一套材质。
        // 原来这里是手写 Row + 一根 VerticalDivider（直角分栏，全 app 旧网格的最后两处之一）。
        //
        // 导航行与视图切换从「通栏压在最上面」移进**左板板头**（与计划页「板头 + 控件 + 列表」
        // 同一结构）：通栏导航会把右板顶部盖掉，两板也就不是并排的两块了。
        // v71（2026-11-21 用户拍板）：日历**不搞主副框**——它现在是右移入场的整页覆盖层，
        // 所有模式（3 天/周/月/年）都走单列整宽；宽屏月视图原先把「选中日事项」放右板的
        // SplitBoards 形态退役（覆盖层里再分两块板 = 框套框）。
        // 壳层相框由 HomeScreen 按 showCalendar 统一写（v71 卡顿根修：两处 SideEffect
        // 互相翻转 = 每次切视图整层重组风暴）；这里不再写。
        Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
            calendarNavRow()
            Spacer(Modifier.height(4.dp))
            calendarModeGroup()
            Spacer(Modifier.height(8.dp))

            when (mode) {
                0 -> MultiDayTimeline((1..3).map { anchor.plusDays(it - 2L) }, selected, onPick, onEditTodo, onEditSchedule)
                1 -> MultiDayTimeline(
                    (0..6).map { anchor.with(java.time.DayOfWeek.MONDAY).plusDays(it.toLong()) },
                    selected, onPick, onEditTodo, onEditSchedule
                )
                2 -> MonthGrid(ym, selected, countdownMarks, monthCompletion, onPick)
                else -> YearGrid(ym, selected, yearCompletion, yearMarks, onPick) { m ->
                    monthIndex = ym.year * 100 + m; mode = 2
                }
            }
        }
        // v12：悬浮球——一键回到今天（原导航行「今天」按钮迁移至此）
        Box(
            Modifier
                .align(Alignment.BottomEnd)
                .padding(24.dp)
        ) {
            androidx.compose.material3.Surface(
                shape = CircleShape,
                shadowElevation = 8.dp,
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.size(56.dp).pressScale().clickable {
                    // 一键回今天 = 选择语义 → Select
                    haptics.perform(HapticEngine.Kind.Select)
                    val t = AppTime.today()
                    anchor = t
                    monthIndex = t.year * 100 + t.monthValue
                    onPick(t)
                }
            ) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                    Text(
                        "今",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
        }
    }
}

private fun shortDay(d: LocalDate): String = "${d.monthValue}/${d.dayOfMonth}"

@Composable
private fun MonthGrid(
    month: YearMonth,
    selected: LocalDate,
    countdownMarks: Set<LocalDate>,
    completion: Map<LocalDate, Pair<Int, Int>>,
    onPick: (LocalDate) -> Unit
) {
    val haptics = io.github.m0use001.planability.core.LocalHaptics.current
    val first = month.atDay(1)
    // v59 平板：7 列**等宽** + 整体限宽居中。原来「42dp 小圆 + SpaceEvenly」在 1248dp 宽下
    // 相邻两圆之间空出约 130dp——看起来像散落的一把点，而不是一个月历（审计点名）。
    // 等宽列在手机上（360dp）每列约 47dp，与原来 42dp 的观感几乎一致。
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
    Column(
        Modifier
            .widthIn(max = io.github.m0use001.planability.ui.common.WideCalendarMaxWidth)
            .fillMaxWidth()
    ) {
    Row(Modifier.fillMaxWidth()) {
        listOf("一", "二", "三", "四", "五", "六", "日").forEach {
            Box(Modifier.weight(1f).height(28.dp), contentAlignment = Alignment.Center) {
                Text(it, style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    // v13：与首行完成率环留出间距，不再与星期汉字重叠
    Spacer(Modifier.height(6.dp))
    val lead = (first.dayOfWeek.value + 6) % 7
    val days = month.lengthOfMonth()
    val rows = (lead + days + 6) / 7
    Column(Modifier.fillMaxWidth()) {
        repeat(rows) { r ->
            Row(Modifier.fillMaxWidth()) {
                repeat(7) { c ->
                    val idx = r * 7 + c - lead
                    Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    if (idx in 0 until days) {
                        val d = month.atDay(idx + 1)
                        val isSel = d == selected
                        val isToday = d == AppTime.today()
                        val hasCountdown = d in countdownMarks
                        val (dayDone, dayTotal) = completion[d] ?: (0 to 0)
                        val progress = if (dayTotal > 0) (dayDone.toFloat() / dayTotal).coerceIn(0f, 1f) else 0f
                        val cellBg = when {
                            isSel -> MaterialTheme.colorScheme.primary
                            isToday -> MaterialTheme.colorScheme.primaryContainer
                            else -> Color.Transparent
                        }
                        val numColor = when {
                            isSel -> MaterialTheme.colorScheme.onPrimary
                            isToday -> MaterialTheme.colorScheme.onPrimaryContainer
                            else -> MaterialTheme.colorScheme.onSurface
                        }
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(42.dp)
                                .pressScale()
                                .clip(CircleShape)
                                .background(cellBg)
                                .clickable {
                                    // 选日期 = 选择语义 → Select（映射表见 core/Haptics.kt）
                                    haptics.perform(HapticEngine.Kind.Select)
                                    onPick(d)
                                }
                        ) {
                            // 完成度环：围绕日期数字（v12：0 完成度也画轨道环）
                            run {
                                val ringTrack = if (isSel) MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.6f)
                                    else MaterialTheme.colorScheme.surfaceVariant
                                val ringFg = if (isSel) MaterialTheme.colorScheme.onPrimary
                                    else MaterialTheme.colorScheme.primary
                                Canvas(Modifier.size(38.dp)) {
                                    val sw = 3.5.dp.toPx()
                                    drawArc(
                                        color = ringTrack, startAngle = -90f, sweepAngle = 360f, useCenter = false,
                                        style = Stroke(sw)
                                    )
                                    if (progress > 0f) {
                                        drawArc(
                                            color = ringFg, startAngle = -90f, sweepAngle = 360f * progress,
                                            useCenter = false, style = Stroke(sw, cap = StrokeCap.Round)
                                        )
                                    }
                                }
                            }
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    "${idx + 1}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = numColor
                                )
                                if (hasCountdown) {
                                    Box(
                                        Modifier
                                            .size(5.dp)
                                            .background(MaterialTheme.colorScheme.tertiary, CircleShape)
                                    )
                                }
                            }
                        }
                    } else {
                        Spacer(Modifier.size(42.dp))
                    }
                    }
                }
            }
        }
    }
    }
    }
}

/** 多日时间轴（3天/周共用，v10 时间轴化）：小时刻度列 + 网格线 + 带标题文字的日程块，可垂直滚动 */
@Composable
private fun MultiDayTimeline(
    days: List<LocalDate>,
    selected: LocalDate,
    onPick: (LocalDate) -> Unit,
    onEditTodo: (Long) -> Unit = {},
    onEditSchedule: (Long) -> Unit = {}
) {
    val haptics = io.github.m0use001.planability.core.LocalHaptics.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var bundles by remember(days) {
        mutableStateOf(emptyMap<LocalDate, io.github.m0use001.planability.data.DayBundle>())
    }

    // v73：监听 dataVersion——拖块落库后 saveSchedule/saveTodo 内部 changed() 推版本，
    // 这里自动重拉 bundles，块才会移到新位置（原来只认 days，拖完不动）
    val dataVersion by io.github.m0use001.planability.data.Graph.dataVersion.flow
        .collectAsState(initial = 0L)
    androidx.compose.runtime.LaunchedEffect(days, dataVersion) {
        val map = mutableMapOf<LocalDate, io.github.m0use001.planability.data.DayBundle>()
        days.forEach { d -> map[d] = Graph.plan.loadDay(d) }
        bundles = map
    }

    // v73 拖动状态：正在被拖的块及其手指累计位移（dp）
    var dragState by remember { mutableStateOf<CalDragState?>(null) }

    // v78（用户「周/3天视图显示当前时间线」）：现在线只在今天落在可见列内时画，
    // 每 30 秒刷新一次——与首页时间轴同一口径（组合期只算一次会停在进入页面时刻）。
    val isTodayVisible = AppTime.today() in days
    var nowMin by remember {
        mutableIntStateOf(LocalDateTime.now(AppTime.zone).let { it.hour * 60 + it.minute })
    }
    androidx.compose.runtime.LaunchedEffect(isTodayVisible) {
        if (!isTodayVisible) return@LaunchedEffect
        while (true) {
            delay(30_000)
            nowMin = LocalDateTime.now(AppTime.zone).let { it.hour * 60 + it.minute }
        }
    }
    // v78：块内备注是否显示——与首页时间轴同一开关（我的 → 计划设置）
    val planSettings by Graph.settings.snapshot.collectAsState()

    // 标签色相（2026-10-27 用户：日程块按标签染色）。与首页时间轴同一口径：第一个标签的颜色，
    // 没登记的标签按 210 兜底，没标签回退 primary
    var labelHues by remember { mutableStateOf<Map<String, Float>>(emptyMap()) }
    androidx.compose.runtime.LaunchedEffect(days) { labelHues = Graph.plan.labelColors() }

    val scroll = androidx.compose.foundation.rememberScrollState()
    // 今天时滚到当前时段
    androidx.compose.runtime.LaunchedEffect(days) {
        if (AppTime.today() in days) {
            val h = (AppTime.now().hour - 1).coerceAtLeast(0)
            scroll.scrollTo((h * DAY_COL_HOUR_DP).toInt())
        } else scroll.scrollTo(0)
    }

    // v12：横向滚动（表头与网格共用，保证列对齐同步）；每天固定列宽，周视图可横滑、每天分到更多宽度
    val scrollH = androidx.compose.foundation.rememberScrollState()
    BoxWithConstraints(Modifier.fillMaxSize()) {
        // v57 平板：列宽按容器宽度自适应——一周放得下（平板横屏 ~1000dp+）就整周平铺免横滑；
        // 放不下仍回固定 140dp + 横滑（手机口径一分不变）。
        val dayColWidth: Dp = ((maxWidth - GUTTER_W_DP.dp) / days.size)
            .coerceAtLeast(DAY_COL_WIDTH_DP.dp)
        Column(Modifier.fillMaxSize()) {
        // 列头（纵向冻结——不随小时网格竖滚；横向与网格同步）
        Row(Modifier.fillMaxWidth()) {
            Spacer(Modifier.width(GUTTER_W_DP.dp))
            Row(Modifier.horizontalScroll(scrollH)) {
            days.forEach { d ->
                val isSel = d == selected
                val isToday = d == AppTime.today()
                val b = bundles[d]
                val total = b?.let { it.todos.size + it.hourTasks.size } ?: 0
                val done = b?.let { it.todos.count { t -> t.done } + it.hourTasks.count { t -> t.done } + it.doneCount } ?: 0
                val progress = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else 0f
                // v12：暗色模式可读性——选中日画实心主色圆底（深字压亮圆），今天非选中用主色数字
                val primaryC = MaterialTheme.colorScheme.primary
                val onPrimaryC = MaterialTheme.colorScheme.onPrimary
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.width(dayColWidth)
                        .pressScale()
                        .clickable {
                            // 选日期 = 选择语义 → Select
                            haptics.perform(HapticEngine.Kind.Select)
                            onPick(d)
                        }
                ) {
                    Text(
                        when (d.dayOfWeek.value) {
                            1 -> "一"; 2 -> "二"; 3 -> "三"; 4 -> "四"
                            5 -> "五"; 6 -> "六"; else -> "日"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isSel) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    // v13：星期字与完成率环留 2dp 间距，避免字形下缘压到环
                    Spacer(Modifier.height(2.dp))
                    // 完成率环包住日期数字；无任务日只有纯数字（今天仍有细环高亮）
                    // 选中日：亮紫实心圆上画淡深轨道 + 实深加粗进度弧，层次分明
                    val ringTrack = if (isSel) onPrimaryC.copy(alpha = 0.35f)
                        else MaterialTheme.colorScheme.surfaceVariant
                    val ringFg = if (isSel) onPrimaryC
                        else MaterialTheme.colorScheme.primary
                    val selProgressSw = 4.5.dp
                    val dayColor = when {
                        isSel -> onPrimaryC
                        isToday -> primaryC
                        progress > 0f -> MaterialTheme.colorScheme.onSurface
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    }
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(34.dp)) {
                        // 选中日：实心主色圆底 + 内缩 26dp 环（环完全落在圆内，不再被边缘裁切）
                        Canvas(Modifier.size(34.dp)) {
                            if (isSel) drawCircle(color = primaryC, radius = size.minDimension / 2f)
                        }
                        Canvas(Modifier.size(if (isSel) 26.dp else 34.dp)) {
                            val sw = 3.dp.toPx()
                            drawArc(color = ringTrack, startAngle = -90f, sweepAngle = 360f, useCenter = false, style = Stroke(sw))
                            if (progress > 0f) {
                                drawArc(
                                    color = ringFg, startAngle = -90f, sweepAngle = 360f * progress, useCenter = false,
                                    style = Stroke(if (isSel) 4.dp.toPx() else sw, cap = StrokeCap.Round)
                                )
                            }
                        }
                        Text(
                            "${d.dayOfMonth}",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = if (isSel || isToday) FontWeight.Bold else FontWeight.Medium,
                            color = dayColor
                        )
                    }
                }
            }
            }
        }
        Spacer(Modifier.height(4.dp))
        // v78：网格线与首页时间轴同款（onSurface 35% + 5px 粗线，圆头）——
        // 首页因为「24 小时全按内容小时」画的就是这一种线，多日视图照抄才叫密度/观感一致。
        val gridColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f)
        val todayColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
        Column(Modifier.fillMaxSize().verticalScroll(scroll)) {
            Box {
            Row(Modifier.height((24 * DAY_COL_HOUR_DP).dp)) {
                // v78：小时数字与首页时间轴同款（titleMedium 16sp SemiBold、onSurface），
                // 数字骑在小时分割线上（0 点上移例外，避免压出网格顶）。
                val hourLabelH = with(androidx.compose.ui.platform.LocalDensity.current) {
                    MaterialTheme.typography.titleMedium.lineHeight.toDp()
                }
                Column(Modifier.width(GUTTER_W_DP.dp)) {
                    repeat(24) { h ->
                        Box(Modifier.height(DAY_COL_HOUR_DP.dp)) {
                            Text(
                                "%02d".format(h),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = if (isTodayVisible && h == nowMin / 60) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier
                                    .align(Alignment.TopStart)
                                    .offset(y = if (h == 0) 0.dp else -(hourLabelH / 2))
                                    .padding(start = 6.dp)
                            )
                        }
                    }
                }
                Row(Modifier.horizontalScroll(scrollH)) {
                days.forEachIndexed { dayIndex, d ->
                    val isToday = d == AppTime.today()
                    // v73 拖动中：这列是不是当前落点（origDayIndex + 横向跨列数，钳在可见范围内）
                    val isDropTarget = dragState?.let { ds ->
                        (ds.origDayIndex + Math.round(ds.deltaXDp / dayColWidth.value))
                            .toInt().coerceIn(0, days.lastIndex) == dayIndex
                    } == true
                    Box(
                        Modifier
                            .width(dayColWidth)
                            .fillMaxHeight()
                            .padding(horizontal = 1.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (isToday) todayColor else MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.4f))
                    ) {
                        // v73：落点列高亮——拖块悬停到哪列，哪列浮一层主容器色
                        if (isDropTarget) {
                            Box(
                                Modifier
                                    .fillMaxSize()
                                    .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.22f))
                            )
                        }
                        // 小时网格线
                        Column(Modifier.fillMaxSize()) {
                            repeat(24) {
                                Box(Modifier.fillMaxWidth().height(DAY_COL_HOUR_DP.dp)) {
                                    Canvas(Modifier.fillMaxSize()) {
                                        drawLine(
                                            gridColor, Offset(0f, 0f), Offset(size.width, 0f),
                                            strokeWidth = 5f, cap = StrokeCap.Round
                                        )
                                    }
                                }
                            }
                        }
                        // 日程+待办统一成块（2026-10-27 用户：①待办在 3 天/周视图不显示 ②颜色按标签）。
                        // 口径与首页时间轴 TimelineGrid 完全一致：按第一个标签染色（labelHues 缺失按
                        // 210 兜底）、无标签回退 primary、完成待办减淡+✓ 前缀、按亮度翻转文字色；
                        // 重叠块复用首页 layoutBlocks 分泳道，互不叠压
                        val dayBlocks = buildList {
                            bundles[d]?.schedules?.forEach { occ ->
                                val s = java.time.Instant.ofEpochMilli(occ.start).atZone(AppTime.zone).toLocalDateTime()
                                val e = java.time.Instant.ofEpochMilli(occ.end).atZone(AppTime.zone).toLocalDateTime()
                                val sm = s.hour * 60 + s.minute
                                var em = e.hour * 60 + e.minute
                                if (em <= sm) em = sm + 30
                                val first = parseLabels(occ.schedule.labels).firstOrNull()
                                add(
                                    TimelineBlock(
                                        kind = "schedule", refId = occ.schedule.id, title = occ.schedule.title,
                                        startMin = sm.coerceIn(0, 1439), endMin = em.coerceAtMost(1440),
                                        color = if (first != null) categoryColor(labelHues[first] ?: 210f)
                                        else MaterialTheme.colorScheme.primary,
                                        // v78：与首页时间轴同口径的详情字段（地点/备注）
                                        subtitle = occ.schedule.place,
                                        note = occ.schedule.note,
                                        // v73：重复系列禁止拖动改时间（用户拍板），只保留点击进编辑器
                                        repeatable = occ.schedule.repeatRule.isNotBlank()
                                    )
                                )
                            }
                            bundles[d]?.todos?.forEach { t ->
                                // 与首页同口径：只画有时段的待办（due>0）；due→endAt，旧数据 30 分钟兜底
                                if (t.due > 0) {
                                    val s = java.time.Instant.ofEpochMilli(t.due).atZone(AppTime.zone).toLocalDateTime()
                                    val e = if (t.endAt > 0) java.time.Instant.ofEpochMilli(t.endAt).atZone(AppTime.zone).toLocalDateTime()
                                    else s.plusMinutes(30)
                                    val sm = (s.hour * 60 + s.minute).coerceIn(0, 1410)
                                    var em = e.hour * 60 + e.minute
                                    if (em <= sm) em = sm + 30
                                    val first = parseLabels(t.labels).firstOrNull()
                                    add(
                                        TimelineBlock(
                                            kind = "todo", refId = t.id, title = t.title,
                                            startMin = sm, endMin = em.coerceAtMost(1440),
                                            color = if (first != null) categoryColor(labelHues[first] ?: 210f)
                                            else MaterialTheme.colorScheme.primary,
                                            done = t.done,
                                            subtitle = t.place,
                                            note = t.note,
                                            percentDone = t.percentDone,
                                            // v73：重复待办同样禁止拖动
                                            repeatable = t.repeatType != "none"
                                        )
                                    )
                                }
                            }
                        }
                        layoutBlocks(dayBlocks).forEach { lb ->
                            val b = lb.block
                            val topDp = DAY_COL_HOUR_DP * (b.startMin / 60f)
                            // v78：最小块高与首页一致（MIN_BLOCK_DP），密度同为 64dp/小时
                            val hDp = (DAY_COL_HOUR_DP * ((b.endMin - b.startMin) / 60f))
                                .coerceAtLeast(MIN_BLOCK_DP.toFloat())
                            val laneW = dayColWidth / lb.lanes
                            // v73：非重复、非完成块可拖（长按拖起）；拖动中本块画成原位 ghost，
                            // 真身浮在网格层（见下方 dragState 浮块）
                            val isDragged = dragState?.let { it.refId == b.refId && it.kind == b.kind } == true
                            val canDrag = !b.repeatable && !b.done
                            val tapOpen: () -> Unit = {
                                haptics.perform(HapticEngine.Kind.Tap)
                                if (b.kind == "todo") onEditTodo(b.refId) else onEditSchedule(b.refId)
                            }
                            Box(
                                Modifier
                                    .offset(x = laneW * lb.lane, y = topDp.dp)
                                    .width(laneW - 2.dp)
                                    .height(hDp.dp)
                                    .padding(1.dp)
                                    .clip(RoundedCornerShape(5.dp))
                                    .then(
                                        if (isDragged) Modifier
                                            .border(
                                                1.dp,
                                                MaterialTheme.colorScheme.outlineVariant,
                                                RoundedCornerShape(5.dp)
                                            )
                                            .background(b.color.copy(alpha = 0.18f))
                                        else Modifier
                                            .background(b.color.copy(alpha = if (b.done) 0.3f else 0.85f))
                                    )
                                    .then(
                                        if (canDrag) Modifier.pointerInput(b) {
                                            // v73：长按拖起（400ms）→ 跟手移动；短按留给 clickable 进编辑器；
                                            // 拖起后逐帧消费位移，外层竖滚/横滑不再抢手势
                                            detectDragGesturesAfterLongPress(
                                                onDragStart = { _ ->
                                                    val origStartMs = d
                                                        .atTime(b.startMin / 60, b.startMin % 60)
                                                        .atZone(AppTime.zone).toInstant().toEpochMilli()
                                                    dragState = CalDragState(
                                                        refId = b.refId, kind = b.kind,
                                                        origStartMs = origStartMs,
                                                        origEndMs = origStartMs + (b.endMin - b.startMin) * 60000L,
                                                        origDayIndex = dayIndex,
                                                        origTopDp = topDp,
                                                        laneXDp = (laneW * lb.lane).value,
                                                        blockWDp = (laneW - 2.dp).value,
                                                        blockHDp = hDp,
                                                        title = b.title, color = b.color, done = b.done
                                                    )
                                                    haptics.perform(HapticEngine.Kind.ImpactLight)
                                                },
                                                onDrag = { change, amt ->
                                                    change.consume()
                                                    dragState = dragState?.copy(
                                                        deltaXDp = dragState!!.deltaXDp + amt.x.toDp().value,
                                                        deltaYDp = dragState!!.deltaYDp + amt.y.toDp().value
                                                    )
                                                },
                                                onDragEnd = {
                                                    val st = dragState
                                                    dragState = null
                                                    if (st != null) scope.launch { commitDrop(st, dayColWidth.value) }
                                                },
                                                onDragCancel = { dragState = null }
                                            )
                                        }
                                        else Modifier
                                    )
                                    .clickable(enabled = !isDragged, onClick = tapOpen)
                                    .padding(horizontal = 5.dp, vertical = 2.dp)
                            ) {
                                // v78：块内信息与首页时间轴同一份渲染（标题/地点/时间/备注，
                                // 阈值相同；dense=列窄时字号降一档）
                                TimelineBlockBody(
                                    b = b,
                                    hDp = hDp.dp,
                                    progress = when {
                                        b.done -> 1f
                                        b.percentDone in 0..100 -> b.percentDone / 100f
                                        else -> 0f
                                    },
                                    showNote = planSettings.timelineShowNote,
                                    dense = true
                                )
                            }
                        }
                    }
                }
                }
            }
            // v78（用户「周/3天视图显示当前时间线」）：与首页时间轴同款——
            // 2dp 主色半透明横线 + 左端 10dp 圆点，只在今天落在可见列内时画。
            if (isTodayVisible) {
                val nowY = DAY_COL_HOUR_DP * (nowMin / 60f)
                Box(
                    Modifier
                        .offset(y = nowY.dp - 1.dp)
                        .padding(start = GUTTER_W_DP.dp, end = 1.dp)
                        .fillMaxWidth()
                        .zIndex(6f)
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(2.dp)
                            .background(
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.75f),
                                RoundedCornerShape(1.dp)
                            )
                    )
                    Box(
                        Modifier
                            .offset(x = (-4).dp, y = (-4).dp)
                            .size(10.dp)
                            .background(MaterialTheme.colorScheme.primary, CircleShape)
                    )
                }
            }
            // v73：拖动中的块浮在网格层（Box 第二子，画在所有天列之上）——跨列移动时
            // 不被本列圆角裁掉。y 与列内容同一竖滚空间不需补偿；x 减去横滚位移换算到
            // Box 坐标（内容坐标 → 视口）。
            dragState?.let { ds ->
                val scrollXDp = with(androidx.compose.ui.platform.LocalDensity.current) {
                    scrollH.value.toDp().value
                }
                // v78（用户「手按着时显示半透明的这个日程，提示松手会吸附到哪里」）：
                // 吸附预览框——按与落库完全相同的规则（15 分钟吸附 + 四舍五入跨天）算出
                // 目标天列与目标分钟，画一块半透明主色描边框。松手后块就落在这块里。
                run {
                    val durMin = ((ds.origEndMs - ds.origStartMs) / 60000L).toInt().coerceAtLeast(0)
                    val origStartMin = Math.round(ds.origTopDp / DAY_COL_HOUR_DP * 60f)
                    val deltaMin = Math.round(ds.deltaYDp / DAY_COL_HOUR_DP * 60f / 15f) * 15
                    val snapStart = (origStartMin + deltaMin)
                        .coerceIn(0, (24 * 60 - durMin).coerceAtLeast(0))
                    val targetDay = (ds.origDayIndex + Math.round(ds.deltaXDp / dayColWidth.value))
                        .toInt().coerceIn(0, days.lastIndex)
                    Box(
                        Modifier
                            .offset(
                                x = (GUTTER_W_DP + targetDay * dayColWidth.value + ds.laneXDp - scrollXDp).dp,
                                y = (DAY_COL_HOUR_DP * (snapStart / 60f)).dp
                            )
                            .width(ds.blockWDp.dp)
                            .height(ds.blockHDp.dp)
                            .clip(RoundedCornerShape(5.dp))
                            .background(ds.color.copy(alpha = 0.28f))
                            .border(1.5.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(5.dp))
                            .zIndex(4f)
                    )
                }
                val onDrag = if (ds.color.luminance() > 0.6f) Color(0xFF1A1C22) else Color.White
                Box(
                    Modifier
                        .offset(
                            x = (GUTTER_W_DP + ds.origDayIndex * dayColWidth.value + ds.laneXDp + ds.deltaXDp - scrollXDp).dp,
                            y = (ds.origTopDp + ds.deltaYDp).dp
                        )
                        .width(ds.blockWDp.dp)
                        .height(ds.blockHDp.dp)
                        .shadow(4.dp, RoundedCornerShape(5.dp))
                        .clip(RoundedCornerShape(5.dp))
                        .background(ds.color.copy(alpha = 0.95f))
                        .padding(horizontal = 3.dp, vertical = 1.dp)
                        .zIndex(5f)
                ) {
                    Text(
                        ds.title,
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, lineHeight = 11.sp),
                        color = onDrag,
                        maxLines = 2,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                    )
                }
            }
            }
            Spacer(Modifier.height(8.dp))
        }
        }
    }
}

/**
 * v73：拖块落库——非重复日程/待办按 [computeDropMillis] 平移（时长不变、15 分钟吸附、
 * 可跨天），保存走既有 saveSchedule/saveTodo（内部 changed() 推版本 → bundles 自动重拉）。
 * 没动（吸附回原点）就不写库。日程跨天 = start/end 同平移；待办跨天还要同步 date dayKey。
 */
private suspend fun commitDrop(st: CalDragState, dayColWidthDp: Float) {
    val (ns, ne) = computeDropMillis(
        st.origStartMs, st.origEndMs,
        st.deltaXDp, st.deltaYDp,
        dayColWidthDp, DAY_COL_HOUR_DP.toFloat(), AppTime.zone
    )
    if (ns == st.origStartMs && ne == st.origEndMs) return
    runCatching {
        if (st.kind == "schedule") {
            Graph.plan.scheduleById(st.refId)?.let {
                Graph.plan.saveSchedule(it.copy(start = ns, end = ne))
            }
        } else {
            Graph.plan.todoById(st.refId)?.let {
                Graph.plan.saveTodo(
                    it.copy(due = ns, endAt = ne, date = AppTime.dayKey(ns.toLocalDate()))
                )
            }
        }
    }
}

/**
 * v59 宽屏月视图右栏：**选中那天**的事项清单。
 *
 * 为什么要有：月历本身是「索引」，选中某天之后用户想看的就是那天的明细——
 * iPad 日历在宽屏就是这个形态。没有它，620dp 的月历浮在 1200dp 中间，
 * 四周照样是大片空白（用户「大而空」投诉的同类）。
 * 点行 = 进编辑器，与多天时间轴点块同一语义。
 */
@Composable
private fun SelectedDayAgenda(
    date: LocalDate,
    onEditTodo: (Long) -> Unit,
    onEditSchedule: (Long) -> Unit
) {
    var bundle by remember(date) {
        mutableStateOf<io.github.m0use001.planability.data.DayBundle?>(null)
    }
    androidx.compose.runtime.LaunchedEffect(date) {
        bundle = runCatching { Graph.plan.loadDay(date) }.getOrNull()
    }
    val haptics = LocalHaptics.current
    Column(
        Modifier
            .fillMaxHeight()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
    ) {
        Spacer(Modifier.height(12.dp))
        Text(
            "${date.monthValue}月${date.dayOfMonth}日",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.height(8.dp))
        val schedules = bundle?.schedules.orEmpty()
        val todos = bundle?.todos.orEmpty().filter { it.due > 0 }
        if (schedules.isEmpty() && todos.isEmpty()) {
            Text(
                "这一天还没有安排",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 12.dp)
            )
        }
        schedules.forEach { occ ->
            Row(
                verticalAlignment = Alignment.Top,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .clickable {
                        haptics.perform(HapticEngine.Kind.Tap)
                        onEditSchedule(occ.schedule.id)
                    }
                    .padding(vertical = 6.dp, horizontal = 4.dp)
            ) {
                Text(
                    AppTime.millisToTime(occ.start),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.width(46.dp)
                )
                Text(
                    occ.schedule.title,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
            }
        }
        todos.forEach { t ->
            Row(
                verticalAlignment = Alignment.Top,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .clickable {
                        haptics.perform(HapticEngine.Kind.Tap)
                        onEditTodo(t.id)
                    }
                    .padding(vertical = 6.dp, horizontal = 4.dp)
            ) {
                Text(
                    if (t.done) "✓" else AppTime.millisToTime(t.due),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(46.dp)
                )
                Text(
                    t.title,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (t.done) MaterialTheme.colorScheme.onSurfaceVariant
                    else androidx.compose.ui.graphics.Color.Unspecified,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
            }
        }
        Spacer(Modifier.height(io.github.m0use001.planability.app.navAvoidPadding().calculateBottomPadding()))
    }
}

/**
 * v78（2026-11-23 用户「日历周/3天视图小时线密度同步首页时间轴」）：
 * 每小时行高与首页时间轴统一为 64dp（[HOUR_HEIGHT_DP]），不再自持 40dp。
 * 事件块的「几分钟落哪儿」仍由 `DAY_COL_HOUR_DP * 分钟/60` 线性换算，口径单一。
 */
private const val DAY_COL_HOUR_DP = HOUR_HEIGHT_DP
/** 刻度列宽：随小时数字字号（titleMedium 16sp）放宽到 44dp，容得下两位数字+边距 */
private const val GUTTER_W_DP = 44
/** v12：多日视图每天固定列宽（周视图横滑，每天分到更多宽度） */
private const val DAY_COL_WIDTH_DP = 140

@Composable
private fun YearGrid(
    month: YearMonth,
    selected: LocalDate,
    completion: Map<LocalDate, Pair<Int, Int>>,
    countdownMarks: Set<LocalDate>,
    onPick: (LocalDate) -> Unit,
    onMonth: (Int) -> Unit
) {
    val haptics = io.github.m0use001.planability.core.LocalHaptics.current
    // v71（2026-11-21 用户「年视图下要展示每一天情况，反正屏幕大」）：
    // 原来每个格只有「N月 / 30 天」两行字（大屏上等于 12 张空白卡片）。
    // 现在每格是**一张迷你月历**：7 列日号，每天一个小格，用与月视图同一套语义着色——
    //  · 今天 = primaryContainer 底
    //  · 选中日 = primary 底 + onPrimary 字
    //  · 有事项完成度 = 日号下一条主色进度条（done/total，0 完成不画）
    //  · 倒数日 = 右上角一个小点（tertiary）
    // 点某一天 = 直接选中那天（等价于月视图里点它）；点月份标题 = 进该月月视图。
    LazyVerticalGrid(
        // 宽屏一行放得下 4~6 张迷你月历；手机 2 张。格宽自适应。
        columns = GridCells.Adaptive(260.dp),
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        items((1..12).toList()) { m ->
            val ym = java.time.YearMonth.of(month.year, m)
            val isCurMonth = m == month.monthValue
            Column(
                Modifier
                    .clip(RoundedCornerShape(14.dp))
                    .background(
                        if (isCurMonth) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                        else MaterialTheme.colorScheme.surfaceContainer
                    )
                    .padding(horizontal = 10.dp, vertical = 8.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .clickable {
                            haptics.perform(HapticEngine.Kind.Select)
                            onMonth(m)
                        }
                        .padding(vertical = 2.dp)
                ) {
                    Text(
                        "${m}月",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = if (isCurMonth) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        // 月份汇总：这个月有多少天有事（完成度>0 或 有倒数日）
                        "${ym.lengthOfMonth()} 天",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.height(2.dp))
                // 星期表头
                Row(Modifier.fillMaxWidth()) {
                    listOf("一", "二", "三", "四", "五", "六", "日").forEach {
                        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                            Text(
                                it,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                                fontSize = 9.sp
                            )
                        }
                    }
                }
                val lead = (ym.atDay(1).dayOfWeek.value + 6) % 7
                val days = ym.lengthOfMonth()
                val rows = (lead + days + 6) / 7
                repeat(rows) { r ->
                    Row(Modifier.fillMaxWidth()) {
                        repeat(7) { c ->
                            val idx = r * 7 + c - lead
                            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                                if (idx in 0 until days) {
                                    val d = ym.atDay(idx + 1)
                                    YearDayCell(
                                        date = d,
                                        selected = d == selected,
                                        today = d == AppTime.today(),
                                        progress = (completion[d] ?: (0 to 0)).let { (dn, tt) ->
                                            if (tt > 0) (dn.toFloat() / tt).coerceIn(0f, 1f) else 0f
                                        },
                                        hasCountdown = d in countdownMarks,
                                        onClick = {
                                            haptics.perform(HapticEngine.Kind.Select)
                                            onPick(d)
                                        }
                                    )
                                } else {
                                    Spacer(Modifier.size(20.dp))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 年视图迷你月历中的一天：日号 + 完成度条 + 今天/选中底色 + 倒数日点。 */
@Composable
private fun YearDayCell(
    date: LocalDate,
    selected: Boolean,
    today: Boolean,
    progress: Float,
    hasCountdown: Boolean,
    onClick: () -> Unit
) {
    val bg = when {
        selected -> MaterialTheme.colorScheme.primary
        today -> MaterialTheme.colorScheme.primaryContainer
        else -> Color.Transparent
    }
    val fg = when {
        selected -> MaterialTheme.colorScheme.onPrimary
        today -> MaterialTheme.colorScheme.onPrimaryContainer
        else -> MaterialTheme.colorScheme.onSurface
    }
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(20.dp)
            .clip(RoundedCornerShape(5.dp))
            .background(bg)
            .clickable(onClick = onClick)
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                "${date.dayOfMonth}",
                style = MaterialTheme.typography.labelSmall,
                color = fg,
                fontSize = 9.sp
            )
            // 完成度条：一条 1.5dp 的小横线，按 done/total 填充（0 不画 = 空着不代表差）
            if (progress > 0f) {
                Box(
                    Modifier
                        .padding(top = 1.dp)
                        .width(12.dp)
                        .height(1.5.dp)
                        .clip(RoundedCornerShape(50))
                        .background(
                            if (selected) MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.35f)
                            else MaterialTheme.colorScheme.surfaceVariant
                        )
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth(progress)
                            .fillMaxHeight()
                            .background(
                                if (selected) MaterialTheme.colorScheme.onPrimary
                                else MaterialTheme.colorScheme.primary
                            )
                    )
                }
            }
        }
        if (hasCountdown) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 1.dp, end = 1.dp)
                    .size(3.dp)
                    .background(MaterialTheme.colorScheme.tertiary, CircleShape)
            )
        }
    }
}
