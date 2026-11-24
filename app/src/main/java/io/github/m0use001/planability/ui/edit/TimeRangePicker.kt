@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package io.github.m0use001.planability.ui.edit

import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import io.github.m0use001.planability.app.PredictiveBackOverlay
import io.github.m0use001.planability.core.AppTime
import io.github.m0use001.planability.core.HapticEngine
import io.github.m0use001.planability.core.LocalHaptics
import io.github.m0use001.planability.core.formatCn
import io.github.m0use001.planability.core.formatShort
import io.github.m0use001.planability.data.Graph
import io.github.m0use001.planability.ui.common.categoryColor
import io.github.m0use001.planability.ui.common.parseLabels
import io.github.m0use001.planability.ui.home.TimelineBlock
import io.github.m0use001.planability.ui.home.layoutBlocks
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import kotlin.math.roundToInt

/**
 * 拖拽式时间选择（2026-10-20 用户要求）。
 *
 * 打开一个全屏页：左边是当天 0-24 点的时间轴（已有日程画在右侧），
 * **最左边空出一列**放"正在编辑的这一条"，上下两个把手拖动开始/结束。
 *
 * 为什么不用 M3 的 TimePicker：日程的核心是"和当天已有什么错开/衔接"，
 * 两个转盘只让用户看到自己选的那个点，看不到上下文。时间轴把上下文直接画出来。
 *
 * 2026-10-29 用户「时间轴要同步首页，标注时间等信息」：刻度/分割线/现时线/已有块
 * 全部改成首页时间轴同款 —— 两位数字（titleMedium）、25 条同款分割线合成一张画布、
 * 今天画主色现时线；已有日程与待办按**标签色**着色并标出标题 + 时间段（≥46dp）+ 地点
 * （≥64dp），文字色按底色亮度翻转。待办/日程的区别不在颜色（首页也不分）。
 */

/** 时间轴里已有日程/待办的一条（分钟为单位，便于布局）。2026-10-29：带上标签/地点/备注，与首页同源渲染 */
private data class DayBlock(
    val startMin: Int,
    val endMin: Int,
    val title: String,
    val todo: Boolean = false,
    val labels: String = "",
    val place: String = ""
)

private const val MIN_DURATION = 5          // 最小时长（分钟）；拖动分度已改为 1 分钟
/**
 * 只有把手指拖到**左侧那列时间数字**上才吸附（2026-10-25 用户口径）：
 * 数字高度约 16dp ≈ 5.7 分钟，所以在数字上下 ±5 分钟内都算"落在数字上"。
 * 拖在右侧（时间轴上/整点分割线上）一律不吸附，按 1 分钟自由落点。
 * 2026-10-29 起数字改与首页同款 titleMedium（行高更大），吸附区按 ±6 分钟放宽。
 */
private const val HOUR_MAGNET = 6
private val HOUR_HEIGHT = 64.dp
/** 标签列：要容得下选中态药丸（pill 背景），52dp 会把药丸压到分割线上 */
private val LABEL_W = 64.dp
private val EDIT_W = 76.dp
private val GAP = 8.dp
/** 把手高 26dp 的一半：把手中心骑在块边界上（拖到整点线 = 值落在整点） */
private val HANDLE_HALF = 13.dp
/** 小时数字选中态高度（骑在刻度线上，= 药丸高度）；titleMedium 行高 24 + 上下 3dp 余量 */
private val HOUR_LABEL_H = 30.dp

@Composable
fun TimeRangePickerScreen(
    day: LocalDate,
    initialStart: LocalTime,
    initialEnd: LocalTime,
    /** 正在编辑的日程 id：从"已有日程"里排除自己，避免画出重叠的幽灵块 */
    excludeId: Long? = null,
    title: String = "选择时间",
    /** 把当天已有时段的**待办**也画进时间轴（待办编辑器用；日程编辑器保持只画日程） */
    includeTodos: Boolean = false,
    /** 正在编辑的待办 id：与 excludeId 分开传，两张表 id 独立，混用会误伤同号日程 */
    excludeTodoId: Long? = null,
    onDismiss: () -> Unit,
    onPick: (LocalTime, LocalTime) -> Unit
) {
    var startMin by remember { mutableIntStateOf(initialStart.hour * 60 + initialStart.minute) }
    var endMin by remember { mutableIntStateOf(initialEnd.hour * 60 + initialEnd.minute) }
    var blocks by remember { mutableStateOf<List<DayBlock>>(emptyList()) }
    // 2026-10-29：与首页时间轴同款着色 —— 标签名下取色，没标签落主色
    var labelHues by remember { mutableStateOf<Map<String, Float>>(emptyMap()) }
    val haptics = LocalHaptics.current
    val scroll = rememberScrollState()
    val density = LocalDensity.current
    val hourPx = with(density) { HOUR_HEIGHT.toPx() }
    val minPx = hourPx / 60f

    LaunchedEffect(day, excludeId, includeTodos, excludeTodoId) {
        labelHues = runCatching { Graph.plan.labelColors() }.getOrDefault(emptyMap())
        val sched = runCatching {
            Graph.plan.scheduleOccurrences(day, day)
                .filter { excludeId == null || it.schedule.id != excludeId }
                .map { occ ->
                    val s = Instant.ofEpochMilli(occ.start).atZone(AppTime.zone).toLocalTime()
                    val e = Instant.ofEpochMilli(occ.end).atZone(AppTime.zone).toLocalTime()
                    DayBlock(
                        s.hour * 60 + s.minute, e.hour * 60 + e.minute, occ.schedule.title,
                        labels = occ.schedule.labels, place = occ.schedule.place
                    )
                }
        }.getOrDefault(emptyList())
        // 待办块只按 DB 当日行取：重复待办的虚拟展开没有可复用的按日助手，
        // 这里不为它另起一套口径（画块是参考上下文，不是权威列表）
        val todos = if (!includeTodos) emptyList() else runCatching {
            Graph.plan.todosOfDay(AppTime.dayKey(day))
                .filter { it.id != excludeTodoId && it.due > 0 }
                .map { t ->
                    val s = Instant.ofEpochMilli(t.due).atZone(AppTime.zone).toLocalTime()
                    // 老数据可能没有结束时间（endAt=0），按编辑器同款兜底 30 分钟
                    val eMs = if (t.endAt > t.due) t.endAt else t.due + 30 * 60_000L
                    val e = Instant.ofEpochMilli(eMs).atZone(AppTime.zone).toLocalTime()
                    DayBlock(
                        s.hour * 60 + s.minute, e.hour * 60 + e.minute, t.title,
                        todo = true, labels = t.labels, place = t.place
                    )
                }
        }.getOrDefault(emptyList())
        blocks = sched + todos
    }
    // 初始滚动到开始时间上方 1 小时
    LaunchedEffect(Unit) {
        scroll.scrollTo((startMin * minPx - hourPx).toInt().coerceAtLeast(0))
    }

    // v75（2026-11-22 用户平板截图「选择时间弹成裸全屏、逃出主副框」）：
    // 原来自己开 `Dialog(usePlatformDefaultWidth=false)` 裸全屏窗口——两块圆角板相框消失、
    // 无钻入动画，是唯一几个彻底逃出「主副框」框架的页面之一。改用页内 PredictiveBackOverlay：
    // 手机整屏；宽屏里它填满宿主（计划页钻入的编辑器瓦片 = 主框位置），右侧保留列表副框，
    // 呈现第三级钻入视觉；返回手势/关闭动画归本层，不再自开窗口。fullscreen=true 保持整块
    // 填满宿主（不缩成居中 1000dp 卡——时间轴要整幅宽度）。
    PredictiveBackOverlay(onBack = onDismiss, fullscreen = true, lockShell = false) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            // 安全区：v75 起本层是页内 overlay（不再是独立 Dialog 窗口），仍在 activity 窗口里，
            // statusBars/navigationBars padding 继续有效——时间轴顶栏避开状态栏、滚到底最后一行
            // 不被全面屏横杠盖住。（宽屏作为钻入瓦片时这两条 padding 会多垫一点状态栏高，可接受。）
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
                // ---- 顶栏 ----
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 10.dp)
                ) {
                    IconButton(onClick = {
                        haptics.perform(HapticEngine.Kind.Tap)
                        onDismiss()
                    }) { Icon(Icons.Rounded.Close, "取消") }
                    Column(Modifier.weight(1f)) {
                        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        // 日期友好化（跨年带年份）：date 可为任意一天后，光看 ISO 串对不上「哪天」
                        val dayLabel = if (day.year != AppTime.today().year) day.formatCn() else day.formatShort()
                        Text(
                            "%s  %s – %s".format(dayLabel, hm(startMin), hm(endMin)),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    IconButton(onClick = {
                        haptics.perform(HapticEngine.Kind.Tap)
                        // 2026-11-01 真机闪退（crash 缓冲 16:10）：时间轴底端 = 1440 分钟 = 24 点，
                        // LocalTime.of(24,…) 直接 DateTimeException 崩掉。LocalTime 表达不了
                        // 24:00，1440 收口成 23:59（差一分钟只影响"正好钉在午夜底端"的极端情形）
                        onPick(minToLocalTime(startMin), minToLocalTime(endMin))
                    }) { Icon(Icons.Rounded.Check, "确定") }
                }

                // ---- 时间轴 ----
                Box(Modifier.fillMaxSize().verticalScroll(scroll)) {
                    androidx.compose.foundation.layout.BoxWithConstraints(
                        Modifier.fillMaxWidth().height(HOUR_HEIGHT * 24 + 32.dp)
                    ) {
                        // 小时刻度：数字垂直居中骑在整点线上（2026-10-25：吸附只认左侧这列数字）。
                        // 2026-10-29 同步首页：两位数字 + titleMedium/SemiBold + 首页同款配色
                        // （当前小时主色）；起止正好落在整点时仍带选中药丸（picker 专有的"可吸附"提示）。
                        val labelHalfPx = with(density) { (HOUR_LABEL_H / 2).toPx() }.toInt()
                        val nowMin = remember { LocalTime.now(AppTime.zone).let { it.hour * 60 + it.minute } }
                        val isToday = day == AppTime.today()
                        for (h in 0..23) {
                            val onHour = (h * 60 == startMin) || (h * 60 == endMin)
                            Box(
                                Modifier
                                    .offset {
                                        IntOffset(0, ((h * 60) * minPx).roundToInt() - labelHalfPx)
                                    }
                                    .width(LABEL_W)
                                    .height(HOUR_LABEL_H)
                                    .padding(start = 8.dp),
                                contentAlignment = Alignment.CenterStart
                            ) {
                                Box(
                                    Modifier.then(
                                        if (onHour) Modifier
                                            .background(
                                                MaterialTheme.colorScheme.primaryContainer,
                                                RoundedCornerShape(percent = 50)
                                            )
                                            .padding(horizontal = 8.dp, vertical = 3.dp)
                                        else Modifier
                                    )
                                ) {
                                    Text(
                                        "%02d".format(h),
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        color = when {
                                            onHour -> MaterialTheme.colorScheme.onPrimaryContainer
                                            isToday && h == nowMin / 60 -> MaterialTheme.colorScheme.primary
                                            else -> MaterialTheme.colorScheme.onSurface
                                        }
                                    )
                                }
                            }
                        }
                        // 整点分割线：与首页同一张画布（24 条一次画完，滚动时不逐个节点过绘制）
                        val gridColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f)
                        Canvas(
                            Modifier
                                .padding(start = LABEL_W)
                                .fillMaxWidth()
                                .height(HOUR_HEIGHT * 24)
                        ) {
                            val hourPxDraw = size.height / 24f
                            repeat(24) { i ->
                                val y = i * hourPxDraw
                                drawLine(
                                    color = gridColor,
                                    start = Offset(0f, y), end = Offset(size.width, y),
                                    strokeWidth = 5f, cap = StrokeCap.Round
                                )
                            }
                        }
                        // 现时线：与首页一致（只在今天画；活动状态用主色，压在块上层）
                        if (isToday) {
                            Box(
                                Modifier
                                    .offset(y = HOUR_HEIGHT * (nowMin / 60f) - 1.dp)
                                    .padding(start = LABEL_W)
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

                        // 已有日程/待办（右侧区域）。2026-10-27 用户：重叠时段原来全部画在同一列互相盖住，
                        // 改用与首页时间轴同一套 layoutBlocks 按簇自适应分泳道。
                        // 2026-10-29 同步首页：标签色底、标题 + 时间段（≥46dp）+ 地点（≥64dp），
                        // 文字色按底色亮度翻转（浅色块写白字会看不见）。
                        val existingX = with(density) { (LABEL_W + EDIT_W + GAP * 2).toPx() }.toInt()
                        // 泳道可用宽 = 时间轴右端到屏幕右缘留 12dp；单条时退化为原来的整行
                        val laneAreaPx = with(density) { (maxWidth - (LABEL_W + EDIT_W + GAP * 2 + 12.dp)).toPx() }
                        val laid = remember(blocks) {
                            layoutBlocks(
                                blocks.mapIndexed { i, b ->
                                    TimelineBlock(
                                        kind = if (b.todo) "todo" else "schedule", refId = i.toLong(),
                                        title = b.title, startMin = b.startMin, endMin = b.endMin,
                                        color = Color.Unspecified
                                    )
                                }
                            )
                        }
                        laid.forEach { lb ->
                            val b = lb.block
                            val src = blocks.getOrNull(b.refId.toInt())
                            val top = (b.startMin * minPx).roundToInt()
                            val h = ((b.endMin - b.startMin).coerceAtLeast(10) * minPx).roundToInt()
                            val lanePx = (laneAreaPx / lb.lanes).toInt()
                            val hDp = with(density) { h.toDp() }
                            val firstLabel = parseLabels(src?.labels.orEmpty()).firstOrNull()
                            val blockColor = if (firstLabel != null) {
                                categoryColor(labelHues[firstLabel] ?: 210f)
                            } else MaterialTheme.colorScheme.primary
                            val onBlock = if (blockColor.luminance() > 0.6f) Color(0xFF1A1C22) else Color.White
                            Box(
                                Modifier
                                    .offset { IntOffset(existingX + lanePx * lb.lane, top) }
                                    .width(with(density) { (lanePx - 6).toDp() })
                                    .height(hDp)
                                    .clip(RoundedCornerShape(9.dp))
                                    .background(blockColor.copy(alpha = 0.9f))
                                    .padding(horizontal = 8.dp, vertical = 3.dp)
                            ) {
                                Column {
                                    Text(
                                        b.title,
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.SemiBold,
                                        color = onBlock,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    if (hDp >= 46.dp) {
                                        Text(
                                            "%02d:%02d".format(b.startMin / 60, b.startMin % 60) +
                                                "-%02d:%02d".format(b.endMin / 60, b.endMin % 60),
                                            style = MaterialTheme.typography.labelMedium,
                                            color = onBlock.copy(alpha = 0.75f),
                                            maxLines = 1
                                        )
                                    }
                                    if (hDp >= 64.dp && src != null && src.place.isNotBlank()) {
                                        Text(
                                            src.place,
                                            style = MaterialTheme.typography.labelMedium,
                                            color = onBlock.copy(alpha = 0.85f),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }
                        }

                        // 正在编辑的这一条（最左列）
                        val editX = with(density) { (LABEL_W + GAP).toPx() }.toInt()
                        val editTop = (startMin * minPx).roundToInt()
                        val editH = ((endMin - startMin).coerceAtLeast(MIN_DURATION) * minPx).roundToInt()
                        val editHDp = with(density) { editH.toDp() }
                        // 短块（< 44dp ≈ 40 分钟以内）：块内两行时间会溢出块外，被上下把手压住
                        // （用户 2026-10-25「时间太短把手会把时间挡住」）→ 时间文字改到块右侧
                        // 悬浮显示；块内不再画字，手指也不会挡住它。
                        val showLabels = editHDp >= 44.dp
                        Box(
                            Modifier
                                .offset { IntOffset(editX, editTop) }
                                .width(EDIT_W)
                                .height(editHDp)
                                .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(12.dp))
                        ) {
                            // 整块拖动 = 平移。2026-10-25：**绝对位移 + 1 分钟粒度**——
                            // 原来按事件增量累积，慢拖时余数会漂。整块平移不做整点吸附
                            // （吸附只发生在把手被拖到左侧时间数字上时）。
                            Box(Modifier.fillMaxSize().pointerInput(minPx) {
                                var base = 0
                                var total = 0f
                                var firstDrag = true
                                detectDragGestures(
                                    onDragStart = {
                                        base = startMin
                                        total = 0f
                                        firstDrag = true
                                    },
                                    onDrag = { change, drag ->
                                        change.consume()
                                        if (firstDrag) {
                                            firstDrag = false
                                            // 首个 onDrag 只给"越过 slop 的那部分"，slop 本身被吞掉。
                                            // 按拖动方向补回（纯竖拖补满 slop；拖向左侧数字时横向占大头，
                                            // 竖补齐只剩几像素），否则斜拖会白白多出 7~8 分钟。
                                            val len = kotlin.math.hypot(drag.x, drag.y)
                                            total = if (len > 0.5f) {
                                                viewConfiguration.touchSlop * (drag.y / len) + drag.y
                                            } else {
                                                viewConfiguration.touchSlop + drag.y
                                            }
                                        } else {
                                            total += drag.y
                                        }
                                        val dur = endMin - startMin
                                        val ns = clampMinute(base + (total / minPx).roundToInt())
                                            .coerceIn(0, 24 * 60 - dur)
                                        if (ns != startMin) {
                                            // 整块平移不做整点吸附（用户：只有拖到左侧数字才吸附），
                                            // 每跨 5 分钟给一次轻震，避免 1 分钟粒度下持续嗡嗡。
                                            val crossed = ns / 5 != startMin / 5
                                            startMin = ns
                                            endMin = ns + dur
                                            if (crossed) haptics.perform(HapticEngine.Kind.Select)
                                        }
                                    }
                                )
                            })
                            if (showLabels) {
                                Column(
                                    Modifier.fillMaxSize(),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.Center
                                ) {
                                    Text(hm(startMin), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                                    Text(hm(endMin), style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            } else {
                                // 短块：块内放不下两行时间（把手会压住）→ 时间挪到块右侧悬浮。
                                // 2026-10-25：这个胶囊没法和块做兄弟（父 Box 高 = 块高，子元素会被
                                // 压成同高、文字消失），所以画在**块之后的外层**（见下方 FloatingTimeChip）。
                            }
                            // 上把手：拖开始（值 = 当前开始分钟）
                            // 2026-10-25 用户口径：**只有把手跟着手指移到左侧时间数字上才吸附**；
                            // 在右侧拖动（包括拖到整点分割线上）一律不吸附，按 1 分钟自由落点。
                            Handle(
                                modifier = Modifier.align(Alignment.TopCenter).offset(y = -HANDLE_HALF),
                                minPx = minPx,
                                value = startMin,
                                range = 0..(endMin - MIN_DURATION),
                                labelWpx = with(density) { LABEL_W.toPx() },
                                handleLeftPx = with(density) {
                                    (LABEL_W + GAP + (EDIT_W - 64.dp) / 2).toPx()
                                }
                            ) { startMin = it }
                            // 下把手：拖结束
                            Handle(
                                modifier = Modifier.align(Alignment.BottomCenter).offset(y = HANDLE_HALF),
                                minPx = minPx,
                                value = endMin,
                                range = (startMin + MIN_DURATION)..(24 * 60),
                                labelWpx = with(density) { LABEL_W.toPx() },
                                handleLeftPx = with(density) {
                                    (LABEL_W + GAP + (EDIT_W - 64.dp) / 2).toPx()
                                }
                            ) { endMin = it }
                        }
                        // 短块（< 44dp）的时间胶囊：画在块**外层**（块 Box 高 = 块高，
                        // 子元素会被压成同高、文字看不见），位置 = 块右侧 6dp、垂直居中。
                        // 用户 2026-10-25「时间太短把手会把时间挡住」：时间要看得见，
                        // 只是不能被把手盖住。
                        if (!showLabels) {
                            val chipX = editX + with(density) { EDIT_W.toPx() }.toInt() +
                                with(density) { 6.dp.toPx() }.toInt()
                            val chipHalf = with(density) { 10.dp.toPx() }.toInt()
                            Surface(
                                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.offset {
                                    IntOffset(chipX, editTop + editH / 2 - chipHalf)
                                }
                            ) {
                                Text(
                                    "${hm(startMin)} – ${hm(endMin)}",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 拖动把手：绝对位移（拖动起点为基准，1 分钟粒度）。
 *
 * 2026-10-25 用户口径：**只有手指把把手移到左侧那列时间数字上才吸附**——
 * [labelWpx] 是数字列右边界（刻度线起点），[handleLeftPx] 是把手在时间轴里的左缘，
 * 手指的横向位置落在这条线左边才算"在数字上"。在右侧拖动（含整点分割线）不吸附。
 * 吸附到整点时给一次 Select 震动（与底部标题栏选中药丸同档）；中间分钟不震。
 */
@Composable
private fun Handle(
    modifier: Modifier,
    minPx: Float,
    value: Int,
    range: IntRange,
    labelWpx: Float,
    handleLeftPx: Float,
    onValue: (Int) -> Unit
) {
    val haptics = LocalHaptics.current
    // 手势块只建一次（key 不含 value），当前值/回调必须用最新态，否则拖到一半还在用旧起点
    val current by rememberUpdatedState(value)
    val onChange by rememberUpdatedState(onValue)
    Box(
        modifier
            .width(64.dp)
            .height(26.dp)
            .pointerInput(minPx, range.first, range.last, labelWpx, handleLeftPx) {
                var base = 0
                var total = 0f
                var last = 0
                var firstDrag = true
                detectDragGestures(
                    onDragStart = {
                        base = current
                        total = 0f
                        last = current
                        firstDrag = true
                    },
                    onDrag = { change, drag ->
                        change.consume()
                        if (firstDrag) {
                            firstDrag = false
                            // 与整块拖动同一套：slop 按拖动方向补回（拖向左侧数字是横向为主，
                            // 竖向补得就少；纯竖拖补满 slop，手指与把手才 1:1）
                            val len = kotlin.math.hypot(drag.x, drag.y)
                            total = if (len > 0.5f) {
                                viewConfiguration.touchSlop * (drag.y / len) + drag.y
                            } else {
                                viewConfiguration.touchSlop + drag.y
                            }
                        } else {
                            total += drag.y
                        }
                        val raw = (base + (total / minPx).roundToInt()).coerceIn(0, 24 * 60)
                        val overLabels = handleLeftPx + change.position.x <= labelWpx
                        val nearestHour = ((raw + 30) / 60) * 60
                        val target = if (overLabels && kotlin.math.abs(raw - nearestHour) <= HOUR_MAGNET) {
                            nearestHour
                        } else raw
                        val next = target.coerceIn(range.first, range.last)
                        if (next != last) {
                            last = next
                            onChange(next)
                            // 只在"落到整点数字上"时震动：1 分钟粒度下每格都振会持续嗡嗡
                            if (next % 60 == 0) haptics.perform(HapticEngine.Kind.Select)
                        }
                    }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        Box(
            Modifier.width(32.dp).height(4.dp)
                .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(2.dp))
        )
    }
}

/** 时间轴落点：1 分钟粒度（分度值 5 分钟已按用户要求取消），只做范围收敛 */
private fun clampMinute(min: Int): Int = min.coerceIn(0, 24 * 60)

private fun hm(min: Int): String = "%02d:%02d".format(min / 60, min % 60)

/** 分钟 → LocalTime：1440（24 点，时间轴底端）收口成 23:59 —— LocalTime.of(24,…) 会抛
 *  DateTimeException（2026-11-01 真机闪退，见「确定」按钮处的注释） */
private fun minToLocalTime(min: Int): LocalTime =
    if (min >= 24 * 60) LocalTime.of(23, 59) else LocalTime.of(min / 60, min % 60)
