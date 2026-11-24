package io.github.m0use001.planability.ui.home

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.m0use001.planability.ui.common.categoryColor
import io.github.m0use001.planability.ui.common.parseLabels
import io.github.m0use001.planability.ui.common.pressScale
import androidx.compose.ui.zIndex
import io.github.m0use001.planability.core.AppTime
import io.github.m0use001.planability.data.CategoryEntity
import io.github.m0use001.planability.data.DayBundle
import io.github.m0use001.planability.data.Graph
import io.github.m0use001.planability.data.ScheduleOcc
import io.github.m0use001.planability.data.Todo
import io.github.m0use001.planability.data.TimelineEvent
import io.github.m0use001.planability.ui.common.categoryColor
import io.github.m0use001.planability.ui.common.planItemColor
import io.github.m0use001.planability.ui.theme.SeedColors
import kotlinx.coroutines.delay
import java.time.LocalDate
import java.time.LocalDateTime

const val HOUR_HEIGHT_DP = 64
const val GUTTER_DP = 52
const val MIN_BLOCK_DP = 26
/** v13：分割线/事件块右端不贴屏幕边缘 */
private const val GRID_END_INSET_DP = 14

/** 时间轴上的一个可定位块 */
data class TimelineBlock(
    val kind: String,          // schedule / todo / event
    val refId: Long,
    val title: String,
    val startMin: Int,
    val endMin: Int,
    val color: androidx.compose.ui.graphics.Color,
    val done: Boolean = false,
    val subtitle: String = "",
    /** 备注：是否显示由「我的 → 计划设置」的开关决定（默认关） */
    val note: String = "",
    /** v46：百分比完成（-1=未启用，0..99=启用未完成）。仅 todo 块带值，渲染块底边进度条 */
    val percentDone: Int = -1,
    /**
     * v73（2026-11-21 用户拍板）：重复项禁止在日历时间轴上拖动改时间——
     * true = 该块背后是重复系列（日程 repeatRule 非空 / 待办 repeatType != none），
     * 日历 MultiDayTimeline 对它只保留点击进编辑器；首页时间轴不传=默认 false 行为不变。
     */
    val repeatable: Boolean = false
)

data class LaidBlock(val block: TimelineBlock, val lane: Int, val lanes: Int)

/** 重叠分列：按开始排序，逐簇贪心泳道（簇=彼此重叠的连续事件段），簇内共用列数 */
internal fun layoutBlocks(blocks: List<TimelineBlock>): List<LaidBlock> {
    if (blocks.isEmpty()) return emptyList()
    val sorted = blocks.sortedBy { it.startMin }
    val fixed = mutableListOf<LaidBlock>()
    var curLaneEnds = mutableListOf<Int>()
    var curClusterLanes = 0
    var prevEnd = -1
    val tmp = mutableListOf<Triple<TimelineBlock, Int, Int>>()
    for (b in sorted) {
        if (b.startMin >= prevEnd) {
            // 新簇：收束上一簇
            tmp.forEach { fixed.add(LaidBlock(it.first, it.second, curClusterLanes)) }
            tmp.clear()
            curLaneEnds = mutableListOf()
            curClusterLanes = 0
        }
        var lane = 0
        while (lane < curLaneEnds.size && curLaneEnds[lane] > b.startMin) lane++
        if (lane >= curLaneEnds.size) curLaneEnds.add(b.endMin) else curLaneEnds[lane] = b.endMin
        curClusterLanes = maxOf(curClusterLanes, lane + 1)
        tmp.add(Triple(b, lane, curClusterLanes))
        prevEnd = maxOf(prevEnd, b.endMin)
    }
    tmp.forEach { fixed.add(LaidBlock(it.first, it.second, curClusterLanes)) }
    return fixed
}

/** 单日时间轴：块按起止分钟绝对定位、重叠分列、现在线（只读展示，编辑走块点击） */
@Composable
fun TimelineGrid(
    date: LocalDate,
    bundle: DayBundle?,
    categories: List<CategoryEntity>,
    onBlockClick: (TimelineBlock) -> Unit,
    /** 长按（2026-10-29 用户：首页长按待办/日程 = 跳详细编辑；轻点待办 = 打卡/取消） */
    onBlockLongClick: (TimelineBlock) -> Unit = {},
    automations: List<io.github.m0use001.planability.data.Automation> = emptyList(),
    /**
     * v59：把纵向布局回传给宿主（首页的「红线滚到视口中央」要按同一份映射算）。
     * 位置与命中测试共用一份映射——各算一套就是当年「时间轴和首页对不上」的病根。
     */
    onLayout: (TimelineLayout) -> Unit = {},
    /**
     * v59：可视高度（dp）——时间轴比屏幕矮时，空闲小时会回弹撑满，不留死白。
     * 0 = 不撑（宿主拿不到视口高度时）。
     */
    viewportHeightDp: androidx.compose.ui.unit.Dp = 0.dp
) {
    val isToday = date == AppTime.today()
    var nowMin by remember { mutableIntStateOf(LocalDateTime.now(AppTime.zone).let { it.hour * 60 + it.minute }) }
    // 现在线每 30 秒自动刷新（组合期只算一次会停在进入页面时刻）
    LaunchedEffect(isToday) {
        if (!isToday) return@LaunchedEffect
        while (true) {
            delay(30_000)
            nowMin = LocalDateTime.now(AppTime.zone).let { it.hour * 60 + it.minute }
        }
    }
    val tagHue = categories.associate { it.id to it.hue }

    // v28：与计划页共用同一套着色（标签色 / 紧急·重要，开关在我的）。
    // 同一条日程在首页时间轴和计划页必须是同一个颜色 —— 两屏各配一套色，
    // 用户看到的会是"同一件事两个颜色"，比不改色更糟。
    val planSettings by Graph.settings.snapshot.collectAsState()
    val dataVersion by Graph.dataVersion.flow.collectAsState()
    var labelHues by remember { mutableStateOf<Map<String, Float>>(emptyMap()) }
    LaunchedEffect(dataVersion) { labelHues = Graph.plan.labelColors() }

    // 每次组合直接构建（块内颜色依赖组合期的明暗状态）
    val blocks = buildList {
        bundle?.schedules?.forEach { occ: ScheduleOcc ->
            val s = LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(occ.start), AppTime.zone)
            val e = LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(occ.end), AppTime.zone)
            val sm = s.hour * 60 + s.minute
            var em = e.hour * 60 + e.minute
            if (em <= sm) em = sm + 30
            // 2026-10-20：首页时间轴**按标签色**染色（用户要求）。
            // 有标签 → 第一个标签的颜色；没标签 → primary（与旧版日程块同色，白字可读）。
            // 不再跟随「紧急·重要着色」开关：时间轴要的是"一眼分清是哪类事"。
            val first = parseLabels(occ.schedule.labels).firstOrNull()
            val blockColor = if (first != null) categoryColor(labelHues[first] ?: 210f)
            else MaterialTheme.colorScheme.primary
            add(
                TimelineBlock(
                    kind = "schedule", refId = occ.schedule.id, title = occ.schedule.title,
                    startMin = sm.coerceIn(0, 1439), endMin = em.coerceAtMost(1440),
                    color = blockColor,
                    // 2026-10-20：时间轴块补上地点（用户要求）。只在块足够高时显示（见渲染处）
                    subtitle = occ.schedule.place,
                    note = occ.schedule.note
                )
            )
        }
        bundle?.todos?.forEach { t: Todo ->
            if (t.due > 0) {
                val s = LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(t.due), AppTime.zone)
                val sm = (s.hour * 60 + s.minute).coerceIn(0, 1410)
                // 时间段：due → endAt（旧数据 endAt=0 视为 30 分钟）
                val e = if (t.endAt > 0) {
                    LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(t.endAt), AppTime.zone)
                } else s.plusMinutes(30)
                var em = e.hour * 60 + e.minute
                if (em <= sm) em = sm + 30
                add(
                    TimelineBlock(
                        kind = "todo", refId = t.id, title = t.title,
                        startMin = sm, endMin = em.coerceAtMost(1440),
                        // 待办块同样按标签色
                        color = run {
                            val first = parseLabels(t.labels).firstOrNull()
                            if (first != null) categoryColor(labelHues[first] ?: 210f)
                            else MaterialTheme.colorScheme.primary
                        },
                        done = t.done,
                        subtitle = t.place,
                        note = t.note,
                        percentDone = t.percentDone
                    )
                )
            }
        }
        bundle?.events?.forEach { ev: TimelineEvent ->
            add(
                TimelineBlock(
                    kind = "event", refId = ev.id, title = ev.title,
                    startMin = ev.startMin.coerceIn(0, 1439),
                    endMin = (ev.startMin + ev.durMin).coerceAtMost(1440),
                    color = SeedColors.tone(ev.hue, false)
                )
            )
        }
        // v12：自动化任务同步至时间轴——与其他计划并行显示（参与泳道分列，互不挤占）。
        // 每日时刻型画在触发点，间隔型画在下次触发；占用 10 分钟泳道位仅用于分列
        val tertiary = MaterialTheme.colorScheme.tertiary
        automations.forEach { a ->
            val startMin: Int? = if (a.dailyHour in 0..23) {
                (a.dailyHour * 60 + a.dailyMinute.coerceIn(0, 59))
            } else if (a.intervalMinutes > 0) {
                val next = a.lastRun + a.intervalMinutes * 60_000L
                val ldt = LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(next), AppTime.zone)
                if (ldt.toLocalDate() == date) ldt.hour * 60 + ldt.minute else null
            } else null
            if (startMin != null) {
                add(
                    TimelineBlock(
                        kind = "automation", refId = a.id, title = "🔄 ${a.name}",
                        startMin = startMin.coerceIn(0, 1439),
                        endMin = (startMin + 10).coerceAtMost(1440),
                        color = tertiary
                    )
                )
            }
        }
    }
    val laid = remember(blocks) { layoutBlocks(blocks) }

    // 2026-11-19 用户（16:10 截图 + 「无论什么比例就都搞成手机模式下原本的时间轴」）：
    // **宽屏不再自制一套时间轴**。v59/v60 试过「白天 64dp / 夜里压到 22dp」的分档，
    // 一次比一次更乱（锯齿、挤成一团的小时数字、块跨格溢出），用户判定「越改问题越大」。
    // 现在只保留一条口径，与手机完全一致：24 小时恒 HOUR_HEIGHT_DP（64dp），
    // 不压缩、不分档、不因窗口比例变样。
    val timelineLayout = remember(viewportHeightDp) {
        TimelineLayout.build(
            contentHours = (0..23).toSet(),
            fillHeightDp = viewportHeightDp.value
        )
    }
    // 把布局回传给宿主：首页的「红线滚到视口中央」要按同一份映射算，
    // 否则滚动定位会和画出来的位置对不上（这正是当年时间轴/首页各算一套的坑）。
    val layoutCallback by androidx.compose.runtime.rememberUpdatedState(onLayout)
    androidx.compose.runtime.LaunchedEffect(timelineLayout) {
        layoutCallback(timelineLayout)
    }

    // v59 平板：整块 grid（**含网格线**）限宽居中——v58 只限了一级页的正文列，
    // 时间轴自己仍然吃满容器宽度，事件块被拉成上千 dp 的横条（用户 16:10 截图点名）。
    // 限的是整块而不是 colW：线和块必须同宽，否则线的右端会伸到块外面去。
    // 次序：widthIn 必须在 fillMaxWidth 之前（v58 教训：fillMaxWidth 在前会把 minWidth 钉死）。
    // 手机宽度 < 680dp，天然 no-op。
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
    BoxWithConstraints(
        Modifier
            .widthIn(max = io.github.m0use001.planability.ui.common.WideTimelineMaxWidth)
            .fillMaxWidth()
            .height((timelineLayout.totalHeightDp + 8).dp)
    ) {
        val colW = maxWidth - GUTTER_DP.dp - GRID_END_INSET_DP.dp
        // v13：小时数字垂直居中对齐到小时分割线（文字行高的一半上移）
        // 2026-10-25 用户「首页时间数字大一点」：titleSmall(14sp) → titleMedium(16sp)
        val hourLabelH = with(androidx.compose.ui.platform.LocalDensity.current) {
            MaterialTheme.typography.titleMedium.lineHeight.toDp()
        }
        // 空闲小时的钟点用小一号字（11sp）——它被压扁了，大字会糊成一片
        val idleLabelH = with(androidx.compose.ui.platform.LocalDensity.current) {
            MaterialTheme.typography.labelSmall.lineHeight.toDp()
        }

        // 刻度与网格线
        Row(Modifier.fillMaxSize()) {
            Column(Modifier.width(GUTTER_DP.dp)) {
                repeat(24) { h ->
                    val content = timelineLayout.isContentHour(h)
                    Box(Modifier.height(timelineLayout.hourHeightsDp[h].dp)) {
                        Text(
                            "%02d".format(h),
                            style = if (content) MaterialTheme.typography.titleMedium
                            else MaterialTheme.typography.labelSmall,
                            fontWeight = if (content) FontWeight.SemiBold else FontWeight.Normal,
                            color = when {
                                isToday && h == nowMin / 60 -> MaterialTheme.colorScheme.primary
                                content -> MaterialTheme.colorScheme.onSurface
                                else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
                            },
                            modifier = Modifier
                                .align(Alignment.TopStart)
                                // 0 点数字不上移（避免越出网格顶部压到卡片），其余居中骑在线上
                                .offset(
                                    y = if (h == 0) 0.dp
                                    else if (content) -(hourLabelH / 2)
                                    else -(idleLabelH / 2)
                                )
                                .padding(start = 6.dp)
                        )
                    }
                }
            }
            Column(Modifier.weight(1f).padding(end = GRID_END_INSET_DP.dp)) {
                // v11：更浅更粗（暗色模式可读）
                val gridColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f)
                // 2026-10-27 滚动卡顿优化：原来 24 条线 = 24 个 Box + 24 个 Canvas，
                // 滚动时每帧 24 个绘制节点全过一遍；合成一张画布、一次 drawLine×24，
                // 布局节点从 24 个降到 1 个，内容与线位置逐像素不变。
                // v59：线按小时顶边画（不再是等分），**空闲小时的线画细画淡**——
                // 压扁的那几小时一眼能看出是「压缩过的空档」，不是真的一小时。
                Canvas(Modifier.fillMaxWidth().height(timelineLayout.totalHeightDp.dp)) {
                    repeat(24) { i ->
                        // 2026-11-19 用户真机「时间轴整点横线全挤上面了」：hourTopDp 返回的是
                        // **dp 值**，而 DrawScope 的 Offset / size 全是**像素**。原来直接把 dp
                        // 当 px 用 → 24 条线被压在画布顶部 1/density 的位置：密度 3.5x 的手机上
                        // 只剩顶部 ~29% 有（几条挤成一坨），2x 平板上也只有上半屏。
                        // 小时数字与事件块走的是 Modifier.offset(dp)，所以它们一直是对的 ——
                        // 只有这张画布错位。必须 .dp.toPx()。
                        val y = timelineLayout.hourTopDp(i).dp.toPx()
                        val content = timelineLayout.isContentHour(i)
                        drawLine(
                            color = if (content) gridColor else gridColor.copy(alpha = 0.35f),
                            start = Offset(0f, y), end = Offset(size.width, y),
                            strokeWidth = if (content) 5f else 1.5f,
                            cap = StrokeCap.Round
                        )
                    }
                }
            }
        }

        // 现在线
        if (isToday) {
            val nowY = timelineLayout.minuteToDp(nowMin).dp
            Box(
                Modifier
                    .offset(y = nowY - 1.dp)
                    .padding(start = GUTTER_DP.dp, end = GRID_END_INSET_DP.dp)
                    .fillMaxWidth()
                    .zIndex(6f)
            ) {
                // 2026-10-25 用户「时间线也要变色」：原来用 error（固定红，不跟配色）。
                // 现在线表达「此刻 / 活动状态」，按 M3 归 primary；事件块是 primaryContainer 的
                // 浅底，实心 2dp primary 压在上面仍分得清。
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(2.dp)
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.75f), RoundedCornerShape(1.dp))
                )
                Box(
                    Modifier
                        .offset(x = (-4).dp, y = (-4).dp)
                        .size(10.dp)
                        .background(MaterialTheme.colorScheme.primary, CircleShape)
                )
            }
        }

        // 事件块
        laid.forEach { laidBlock ->
            val b = laidBlock.block
            // v12：自动化是点时间事件——点状样式（24dp 高、贴在触发线正下方），
            // 参与泳道与其他计划并行分列（自己车道内不与块重叠）
            val isPoint = b.kind == "automation"
            // v59：y 坐标走统一映射（有事的小时 64dp、空闲 22dp），比例仍然正确
            val rawTop = timelineLayout.minuteToDp(b.startMin).dp
            val topDp = if (isPoint) rawTop + 2.dp else rawTop
            val hDp = if (isPoint) 24.dp
            else (timelineLayout.minuteToDp(b.endMin) - timelineLayout.minuteToDp(b.startMin))
                .coerceAtLeast(MIN_BLOCK_DP.toFloat()).dp
            val laneW = colW / laidBlock.lanes
            val xDp = GUTTER_DP.dp + laneW * laidBlock.lane
            // 2026-11-15 用户（截图）：「设置完成百分比后时间轴对应代表出现胶囊按照百分比
            // 填充，但整体没有变暗」——原来 isDim 只认勾选完成的 b.done，百分比进度
            // 完全不参与变暗：块保持满亮度，看上去和一件没开始的事一模一样。
            // 现在完成度 = 勾选(1.0) 或 percentDone/100；**底色/文字/标题色都按它渐进**，
            // 而不是二值跳变（30% 微暗、90% 接近"已完成"的暗度，视觉上有"推进感"）。
            val progress = when {
                b.done -> 1f
                b.percentDone in 0..100 -> b.percentDone / 100f
                else -> 0f
            }
            // v47：块入场淡入+短上滑——只按**日期**播一次（remember(date)），数据刷新
            // （编辑保存/增删块）不重播；换天回看重播一次，营造"新的一天铺开"感。
            val entered = remember(date) { mutableStateOf(false) }
            val appear by androidx.compose.animation.core.animateFloatAsState(
                targetValue = if (entered.value) 1f else 0f,
                animationSpec = io.github.m0use001.planability.ui.common.Motion.quick(),
                label = "blockAppear"
            )
            androidx.compose.runtime.LaunchedEffect(date) {
                kotlinx.coroutines.delay(120)
                entered.value = true
            }
            Box(
                Modifier
                    .offset(x = xDp + 2.dp, y = topDp + 1.dp)
                    .graphicsLayer {
                        alpha = appear
                        translationY = (1f - appear) * 8.dp.toPx()
                    }
                    .width(laneW - 5.dp)
                    .height(if (isPoint) hDp else hDp - 2.dp)
                    // pressScale 必须在 clip/background **之前**：graphicsLayer 只包住
                    // 链上比它靠里的绘制，放后面等于只缩文字不缩底块（模拟器实测踩过）
                    .pressScale()
                    .clip(RoundedCornerShape(if (isPoint) 12.dp else 9.dp))
                    .background(
                        if (isPoint) MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.95f)
                        else {
                            // 底色随完成度从 0.9 淡到 0.28（=用户要的"整体变暗"）。
                            // 用 progress 插值而不是二值：百分比是连续量，块也该连续地暗下去。
                            val bgAlpha = 0.9f - (0.9f - 0.28f) * progress
                            b.color.copy(alpha = bgAlpha)
                        }
                    )
                    .combinedClickable(
                        onClick = { onBlockClick(b) },
                        onLongClick = { onBlockLongClick(b) }
                    )
                    .padding(horizontal = 8.dp, vertical = if (isPoint) 2.dp else 3.dp)
                    .zIndex(3f)
            ) {
                TimelineBlockBody(
                    b = b,
                    hDp = hDp,
                    progress = progress,
                    isPoint = isPoint,
                    showNote = planSettings.timelineShowNote
                )
                // v46.2：底边进度条已由标题左边的状态胶囊取代（见 TimelineBlockBody）。
            }
        }
    }
    }
}

/**
 * 块内容的**唯一一份**渲染（2026-11-23 v78 用户：「日程块信息详细度同步首页时间轴」）。
 *
 * 首页时间轴与日历 3天/周视图共用同一份「显示什么、多高才显示哪一行」：
 *  - 待办状态胶囊（TodoStatusPill）恒在标题左侧；
 *  - 标题恒显示；
 *  - 地点 ≥64dp、时间区间 ≥46dp、备注（开关打开时）≥88dp，备注 ≥128dp 才放到 3 行。
 * 完成度 [progress] 同时压暗标题与副文字（0.85→0.35）。
 *
 * [dense]=true 用于日历多列（列窄）——**字号降一档、标题最多 2 行**，但行与阈值一字不改，
 * 所以两个界面的「详细度」永远一致。
 */
@Composable
internal fun TimelineBlockBody(
    b: TimelineBlock,
    hDp: androidx.compose.ui.unit.Dp,
    progress: Float,
    isPoint: Boolean = false,
    showNote: Boolean = false,
    dense: Boolean = false,
) {
    // v28：块底色可能是白色（无标签=白），标题写死白色=白底白字；按亮度翻转
    val onBlock = if (b.color.luminance() > 0.6f)
        androidx.compose.ui.graphics.Color(0xFF1A1C22)
    else androidx.compose.ui.graphics.Color.White
    val dimTitle = MaterialTheme.colorScheme.onSurfaceVariant
    val baseTitle = if (isPoint) MaterialTheme.colorScheme.onTertiaryContainer else onBlock
    val titleColor = androidx.compose.ui.graphics.lerp(
        baseTitle, dimTitle, (progress * 0.85f).coerceIn(0f, 1f)
    )
    val subAlpha = 0.85f - (0.85f - 0.35f) * progress

    val titleStyle = when {
        isPoint -> MaterialTheme.typography.labelSmall
        dense -> MaterialTheme.typography.labelMedium
        else -> MaterialTheme.typography.titleSmall
    }
    val subStyle = if (dense) MaterialTheme.typography.labelSmall
    else MaterialTheme.typography.labelMedium

    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (b.kind == "todo") {
                // v46.2：状态=标题左侧胶囊（空=未开始 / 跑道=百分比 / 勾=完成）
                io.github.m0use001.planability.ui.common.TodoStatusPill(
                    done = b.done,
                    percentDone = b.percentDone,
                    tint = titleColor
                )
                Spacer(Modifier.width(if (dense) 5.dp else 7.dp))
            }
            Text(
                b.title,
                style = titleStyle,
                fontWeight = FontWeight.SemiBold,
                color = titleColor,
                maxLines = if (dense && hDp.value >= 64) 2 else 1,
                overflow = TextOverflow.Ellipsis,
                lineHeight = titleStyle.lineHeight,
                modifier = Modifier.weight(1f)
            )
        }
        if (!isPoint && hDp.value >= 64 && b.subtitle.isNotBlank()) {
            Text(
                b.subtitle,
                style = subStyle,
                color = onBlock.copy(alpha = subAlpha.coerceAtMost(0.85f)),
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
        }
        if (!isPoint && hDp.value >= 46) {
            // ≥46dp 一律给完整区间（2026-10-25「C++作业只显示起始时间」）
            Text(
                "%02d:%02d".format(b.startMin / 60, b.startMin % 60) +
                    "-%02d:%02d".format(b.endMin / 60, b.endMin % 60),
                style = subStyle,
                color = onBlock.copy(alpha = (subAlpha * 0.88f).coerceAtMost(0.75f)),
                maxLines = 1
            )
        }
        // 备注压在时间之后（2026-10-21「备注跟时间挤在一起」），高度不够先截备注
        if (!isPoint && showNote && b.note.isNotBlank() && hDp.value >= 88) {
            Spacer(Modifier.height(2.dp))
            Text(
                b.note.replace('\n', ' ').trim(),
                style = MaterialTheme.typography.labelSmall,
                color = onBlock.copy(alpha = (subAlpha * 0.73f).coerceAtMost(0.62f)),
                maxLines = if (hDp.value >= 128) 3 else 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
