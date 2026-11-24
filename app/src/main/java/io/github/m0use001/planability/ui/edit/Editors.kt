@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package io.github.m0use001.planability.ui.edit

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AccessTime
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material3.AlertDialog
import io.github.m0use001.planability.ui.common.motionEnter
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.TextButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.foundation.layout.navigationBarsPadding
import io.github.m0use001.planability.ui.common.editorContentEnter
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.zIndex
import androidx.compose.ui.unit.dp
import io.github.m0use001.planability.app.LocalOverlayClose
import io.github.m0use001.planability.app.PredictiveBackOverlay
import io.github.m0use001.planability.core.AppTime
import io.github.m0use001.planability.core.HapticEngine
import io.github.m0use001.planability.core.LocalHaptics
import io.github.m0use001.planability.core.formatMonthDay
import io.github.m0use001.planability.core.formatShort
import io.github.m0use001.planability.core.toLocalDate
import io.github.m0use001.planability.data.CategoryEntity
import io.github.m0use001.planability.data.CheckinFormat
import io.github.m0use001.planability.data.CheckinKind
import io.github.m0use001.planability.data.CheckinDay
import io.github.m0use001.planability.data.CheckinLedger
import io.github.m0use001.planability.data.CheckinRecord
import io.github.m0use001.planability.data.Graph
import io.github.m0use001.planability.data.PriorityMap
import io.github.m0use001.planability.data.Recurrence
import io.github.m0use001.planability.data.Schedule
import io.github.m0use001.planability.data.SupervisionStat
import io.github.m0use001.planability.data.Todo
import io.github.m0use001.planability.notify.Notify
import io.github.m0use001.planability.notify.ReminderScheduler
import io.github.m0use001.planability.ui.common.ConfirmDialog
import io.github.m0use001.planability.ui.common.categoryColor
import io.github.m0use001.planability.ui.common.priorityColor
import io.github.m0use001.planability.ui.common.DatePickerModal
import io.github.m0use001.planability.ui.common.SelectDropdownChip
import io.github.m0use001.planability.ui.common.TimePickerModal
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.roundToInt
import androidx.compose.foundation.layout.PaddingValues
import io.github.m0use001.planability.ui.common.filterChipColors

/**
 * 地点字段里的**占位词**，当空值处理。
 *
 * 存量的日程/待办里存着这些（早于 POI 搜索的年代，用户自己敲进去的），
 * 用户看到它们以为「地点已经填过了」，于是地图选点带回的地名不回填、
 * 保存下来还是「待定」——看着像功能没生效。
 */
/** LocalTime → HH:mm。不带秒/纳秒，否则按钮会被撑宽甚至换行。 */
private fun LocalTime.hm(): String = "%02d:%02d".format(hour, minute)

/**
 * 日期/时间的小按钮。
 *
 * 之前这里是 `OutlinedButton(Modifier.weight(1f))`：在 Row 里跟另一个 weight(1f) 平分宽度，
 * 加上 OutlinedButton 默认 40dp 最小高、左右内边距也大，真机看着就是**两颗巨型药丸**，
 * 一行放不下就换行、控件高度跟着跳。
 * 现在改成内容宽度 + 固定 32dp 高 + 文字强制单行不折行。
 */
@Composable
private fun DateTimePill(text: String, onClick: () -> Unit) {
    val haptics = LocalHaptics.current
    OutlinedButton(
        onClick = { haptics.perform(HapticEngine.Kind.Tap); onClick() },
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
        modifier = Modifier.height(32.dp)
    ) {
        Text(text, maxLines = 1, softWrap = false)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScaffold(
    title: String,
    onBack: () -> Unit,
    onDelete: (() -> Unit)? = null,
    /** 删除确认框里被删对象的名字。默认屏标题（「编辑待办」），调用方应传条目自己的标题。 */
    deleteName: String = title,
    actions: @Composable () -> Unit = {},
    /**
     * v62 平板主从：true = 由父页（计划页宽屏右栏）**直接承载**，不再自包全屏 Dialog。
     * 手机路径恒 false，行为零变化（与笔记编辑器 v57 的 embedded 同一套语义）。
     */
    embedded: Boolean = false,
    content: @Composable () -> Unit
) {
    var confirmDelete by remember { mutableStateOf(false) }
    val haptics = LocalHaptics.current
    // 板内返回：宽屏右栏承载时**直接调 onBack**，不读 LocalOverlayClose——
    // 那个 CompositionLocal 的默认值是空实现（PredictiveBackOverlay.kt:35），
    // 内联路径上读它得到的正是「点了没反应」。
    val back: () -> Unit = { haptics.perform(HapticEngine.Kind.Tap); onBack() }
    if (confirmDelete) {
        ConfirmDialog(
            title = "删除",
            text = "确定删除「$deleteName」吗？删除后可在回收站恢复。",
            onConfirm = { onDelete?.invoke() },
            onDismiss = { confirmDelete = false }
        )
    }
    // 正文外壳抽出来：手机自包全屏 Dialog（原路径一个字都没动），
    // 宽屏（embedded）由父页右栏直接承载——inset、返回语义都由宿主负责。
    val shellBody: @Composable () -> Unit = {
    if (embedded) {
        // 板内头：52dp 一行，与右栏宽度同尺度。不再用 M3ETitleBar 的 64dp 圆角浮层——
        // 那层是「盖在内容上的一块独立材质」，嵌进面板里就是新的割裂源。
        Column(Modifier.fillMaxSize()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().height(52.dp)
                    .padding(start = 4.dp, end = 8.dp)
            ) {
                IconButton(onClick = back) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "返回")
                }
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                actions()
                if (onDelete != null) {
                    IconButton(onClick = { confirmDelete = true }) {
                        Icon(
                            Icons.Rounded.Delete, contentDescription = "删除",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            // 高度必须有界才能套 verticalScroll：这层由右栏的 fillMaxHeight 撑出确定高度。
            Column(
                Modifier.weight(1f).fillMaxWidth()
                    .imePadding()
                    .verticalScroll(rememberScrollState())
                    .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                content()
                Spacer(Modifier.height(24.dp))
            }
        }
    } else {
    PredictiveBackOverlay(onBack = onBack) {
        // 必须在 PredictiveBackOverlay 内部读：close 是它 provide 进来的，
        // 放到外面读到的是默认空实现（点了返回没反应）；也不能在 onClick 里读——那是 @Composable getter
        val overlayClose = LocalOverlayClose.current
        // 2026-10-24 用户精修拍板：顶栏换 M3ETitleBar 固定形态（fraction=1）——
        // 底部 28dp 圆角浮层替代直线分割（原生 Settings 同款）；内容铺满全屏、顶部垫出标题栏
        // 高度后**从圆角下钻过**（真浮层，不是矩形截断）。
        val editorStatusPad = androidx.compose.foundation.layout.WindowInsets.statusBars
            .asPaddingValues().calculateTopPadding()
        androidx.compose.foundation.layout.Box(Modifier.fillMaxSize()) {
            // 内容错峰进场：顶栏立即在场，正文 8dp 短上滑+快淡入（editorContentEnter）——
            // 全局 32dp 上滑会把「标题」字段带着飞（用户点名）。竖直滚动在进场层内部。
            Column(
                Modifier
                    .fillMaxSize()
                    .editorContentEnter()
                    .verticalScroll(rememberScrollState())
                    .padding(
                        start = 16.dp, end = 16.dp, bottom = 8.dp,
                        top = editorStatusPad + 64.dp + 8.dp
                    )
                    .navigationBarsPadding(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                content()
                Spacer(Modifier.height(24.dp))
            }
            io.github.m0use001.planability.ui.common.M3ETitleBar(
                title = title,
                collapsedFraction = 1f,
                modifier = Modifier.align(androidx.compose.ui.Alignment.TopCenter),
                navigationIcon = {
                    // 返回 = 中性轻触（Tap），与「选择=Select」「破坏性=ImpactHeavy」分开
                    IconButton(onClick = { haptics.perform(HapticEngine.Kind.Tap); overlayClose() }) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    actions()
                    if (onDelete != null) {
                        IconButton(onClick = { confirmDelete = true }) {
                            // 顶栏垃圾桶中性（点击只是打开确认框）；红色在确认框里
                            Icon(Icons.Rounded.Delete, contentDescription = "删除",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            )
        }
    }
    }
    }
    // 手机：原样走全屏 Dialog 外壳；宽屏：直接渲染，父页右栏已有 inset 与返回语义。
    if (embedded) shellBody() else PredictiveBackOverlay(onBack = onBack) { shellBody() }
}

// ==================== 待办编辑器 ====================

@Composable
fun TodoEditScreen(
    todoId: Long,
    presetDate: LocalDate?,
    onDone: () -> Unit,
    /**
     * v54.5（用户 2026-11-07「打卡与数据不要混在详细编辑界面」）：
     * 编辑器只管**设置**（开关/格式/字段名），统计/账本/补交/全部记录收进打卡面板详情页，
     * 这里只留一行入口。null = 宿主没接面板（不显示入口）。
     */
    onOpenCheckin: ((Long) -> Unit)? = null,
    /** v62：true = 计划页宽屏右栏就地编辑（不自包全屏 Dialog）。手机恒 false。 */
    embedded: Boolean = false
) {
    val scope = rememberCoroutineScope()
    val haptics = LocalHaptics.current
    val context = LocalContext.current
    var loaded by remember { mutableStateOf(todoId == 0L) }
    var title by remember { mutableStateOf("") }
    var hasDate by remember { mutableStateOf(presetDate != null) }
    var date by remember { mutableStateOf(presetDate ?: AppTime.today()) }
    var time by remember { mutableStateOf(LocalTime.of(9, 0)) }
    var endTime by remember { mutableStateOf(LocalTime.of(9, 30)) }
    // 2026-11-02 用户：「允许待办不设置时间（但日期要设置）」——原实现新建恒带
    // 9:00–9:30 默认段、AI 建的无时间待办（due=0）打开编辑器后也显示这两个合成值，
    // 一保存就把假时间写成真时间。noTime=true 时 due/endAt 落 0，时间轴不画它、
    // 首页底部「未设置时间计划」胶囊汇总。
    var noTime by remember { mutableStateOf(false) }
    // v28：三档 priority 从 UI 退休（它还在库里、还给 PC 导出，但只当 importance 的投影）
    var urgency by remember { mutableStateOf(-1) }
    var importance by remember { mutableStateOf(-1) }
    var place by remember { mutableStateOf("") }
    var placePoi by remember { mutableStateOf("") }
    var lat by remember { mutableStateOf(0.0) }
    var lng by remember { mutableStateOf(0.0) }
    var showPlacePicker by remember { mutableStateOf(false) }
    var tagId by remember { mutableStateOf(0L) }
    var labels by remember { mutableStateOf("") }
    /** v28：标签名 → 色相（长按标签改色，存 label_colors） */
    var labelHues by remember { mutableStateOf<Map<String, Float>>(emptyMap()) }
    // 全局统一标签：清单与颜色对所有页面同一套算法，见 ui/common/LabelStore.kt
    val globalLabels = io.github.m0use001.planability.ui.common.rememberGlobalLabels()
    var rec by remember { mutableStateOf(Recurrence.NONE) }
    var permanent by remember { mutableStateOf(false) }
    var supervision by remember { mutableStateOf(false) }
    // ---------- v46 百分比完成（独立于监督开关：只想填进度不用连打卡一起开） ----------
    var percentOn by remember { mutableStateOf(false) }
    var percentVal by remember { mutableStateOf(0) }
    var percentText by remember { mutableStateOf("") }
    // ---------- v22 打卡数据记录 ----------
    var dataRecord by remember { mutableStateOf(false) }
    var dataKind by remember { mutableStateOf(CheckinKind.TIME) }
    var dataLabel by remember { mutableStateOf("") }
    var dataUnit by remember { mutableStateOf("") }
    var dataOptions by remember { mutableStateOf("") }
    var records by remember { mutableStateOf<List<CheckinRecord>>(emptyList()) }
    // v23：逐日账本 + 补交入口
    var ledger by remember { mutableStateOf<List<CheckinDay>>(emptyList()) }
    var ledgerExpanded by remember { mutableStateOf(false) }
    /** 点「补交/改打卡」但不指定日子（走日期选择器） */
    var backfillTarget by remember { mutableStateOf<LedgerBackfill?>(null) }
    /** 直接点某一天补交 */
    var backfillDay by remember { mutableStateOf<Pair<Long, CheckinDay>?>(null) }
    // 已保存的待办本体：回看摘要要用它的 title/字段名，不能拿编辑态（可能已被改过）去拼
    var loadedTodo by remember { mutableStateOf<Todo?>(null) }
    // 待确认删除的一条记录（点记录行 → 问一句再删）
    var pendingDelete by remember { mutableStateOf<CheckinRecord?>(null) }
    var note by remember { mutableStateOf("") }
    var remindMin by remember { mutableStateOf(15) }
    var stat by remember { mutableStateOf<SupervisionStat?>(null) }
    /** v27：绑了坐标的地点分类，一键带出「地点名 + 坐标」（地点分类不属于要退休的「分类」体系） */
    var geoTags by remember { mutableStateOf<List<CategoryEntity>>(emptyList()) }
    var showDatePicker by remember { mutableStateOf(false) }
    var showTimePicker by remember { mutableStateOf(false) }
    var showEndTimePicker by remember { mutableStateOf(false) }
    // 2026-10-20：开始/结束都走时间轴拖拽（一次选一段），不再用两个转盘
    var showRange by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        labelHues = Graph.plan.labelColors()
        geoTags = Graph.plan.categories(1).filter { it.lat != 0.0 || it.lng != 0.0 }
        if (todoId != 0L) {
            Graph.plan.todoById(todoId)?.let { t ->
                title = t.title
                hasDate = t.date > 0
                if (t.date > 0) date = t.date.toLocalDate()
                if (t.due <= 0) noTime = true   // 无时间待办：别把 9:00 的默认值当成它的真时间
                if (t.due > 0) {
                    val dt = java.time.Instant.ofEpochMilli(t.due).atZone(AppTime.zone)
                    time = LocalTime.of(dt.hour, dt.minute)
                }
                if (t.endAt > 0) {
                    val et = java.time.Instant.ofEpochMilli(t.endAt).atZone(AppTime.zone)
                    endTime = LocalTime.of(et.hour, et.minute)
                } else if (t.due > 0) {
                    val d30 = java.time.Instant.ofEpochMilli(t.due + 30 * 60_000L).atZone(AppTime.zone)
                    endTime = LocalTime.of(d30.hour, d30.minute)
                }
                urgency = t.urgency
                importance = t.importance
                tagId = t.tagId
                place = t.place; placePoi = t.placePoi; lat = t.lat; lng = t.lng
                labels = t.labels
                rec = Recurrence.parseTodoRule(t)
                permanent = t.permanent
                supervision = t.supervisionEnabled
                loadedTodo = t
                // v46：>=0 即启用（完成的待办存 100）——判据只走 TodoProgress（单一事实源守卫）
                percentOn = io.github.m0use001.planability.data.TodoProgress.isEnabled(t.percentDone)
                percentVal = t.percentDone.coerceAtLeast(0).coerceAtMost(100)
                percentText = if (percentOn) percentVal.toString() else ""
                dataRecord = t.dataRecordEnabled
                dataKind = t.dataRecordKind.ifBlank { CheckinKind.TIME }
                dataLabel = t.dataRecordLabel
                dataUnit = t.dataRecordUnit
                dataOptions = t.dataRecordOptions
                note = t.note
                remindMin = t.remindMin
                if (t.supervisionEnabled) {
                    stat = Graph.plan.supervisionStat(t.id)
                    records = Graph.plan.checkinsOf(t.id)
                    // v23：长期计划在编辑器里也要能看账本
                    ledger = Graph.plan.checkinLedger(t)
                }
            }
            loaded = true
        }
    }

    fun buildTodo(): Todo {
        val dayKey = if (hasDate) AppTime.dayKey(date) else 0L
        // 无时间：due/endAt 落 0（日期照存）。时间段必须完整：结束早于等于开始时兜底为开始 + 30 分钟
        val due = if (hasDate && !noTime) AppTime.millisOf(date, time.hour, time.minute) else 0L
        val endAt = if (hasDate && !noTime) {
            val e = AppTime.millisOf(date, endTime.hour, endTime.minute)
            if (e <= due) due + 30 * 60_000L else e
        } else 0L
        val (rtype, rdays, rinterval) = Recurrence.toTodoParts(rec)
        // 2026-11-19：最后统一收口 —— 日期被挪过重复截止时按截止日落库
        // （上面已有红色提示；不拦死保存，但绝不让「系列之外的行」写进库）。
        return io.github.m0use001.planability.data.clampIntoSeries(Todo(
            id = todoId,
            title = title.trim(),
            date = dayKey,
            due = due,
            endAt = endAt,
            // priority 由重要性派生：库里两处都要对，PC 侧读 priority 才不会和本端打架
            priority = PriorityMap.priorityOf(importance),
            urgency = urgency,
            importance = importance,
            tagId = tagId,
            place = place.trim(),
            placePoi = placePoi.trim(),
            lat = lat,
            lng = lng,
            labels = labels,
            repeatType = rtype,
            repeatDays = rdays,
            repeatInterval = rinterval,
            repeatEnd = rec.untilDayKey,
            permanent = permanent,
            supervisionEnabled = supervision,
            // v46：百分比完成。-1=未启用；100 由 saveTodo 折算成打卡完成
            percentDone = if (percentOn) percentVal.coerceIn(0, 100) else -1,
            // v22：关掉打卡监督就一并关掉数据记录 —— completeTodo 只在 supervisionEnabled 时才落记录，
            // 留着 enabled=true 只会造出「开关显示开着但永远不记录」的假状态
            dataRecordEnabled = supervision && dataRecord,
            dataRecordKind = if (supervision && dataRecord) dataKind else "",
            dataRecordLabel = if (supervision && dataRecord) dataLabel.trim() else "",
            dataRecordUnit = if (supervision && dataRecord) dataUnit.trim() else "",
            dataRecordOptions = if (supervision && dataRecord) dataOptions.trim() else "",
            note = note.trim(),
            remindMin = remindMin
        ).let { built ->
            // 编辑器没暴露这些列，而 buildTodo 是从零 new 的 —— 不接回去，编辑一次已完成待办
            // 就会把它变回未完成（done/doneAt 清零），分类、重复例外日、回收站状态也会一起丢。
            val base = loadedTodo ?: return@let built
            built.copy(
                folderId = base.folderId,
                done = base.done,
                doneAt = base.doneAt,
                excludedDates = base.excludedDates,
                trashed = base.trashed,
                trashedAt = base.trashedAt,
                projectId = base.projectId,
                createdAt = base.createdAt
            )
        })
    }

    // 2026-11-02 用户「把保存按钮移到标题栏」：保存动作从按钮内联逻辑抽成函数，
    // 顶栏 actions 的「保存」和正文里的按钮共用同一份实现（避免两处逻辑漂移）。
    val saveTodo: () -> Unit = {
        if (title.isNotBlank()) {
            haptics.perform(HapticEngine.Kind.Success)
            scope.launch {
                val b = buildTodo()
                // v46：百分比拉到 100 = 打卡完成（避免「满进度却还没完成」的悬空态）。
                // 反向不限：勾了卡再降百分比是用户明确动作，保持 done 不动。
                // 2026-11-14：满态自动打卡**不再手工 copy(done=true)**——那条旁路只写行，
                // 完成流水/百分比流水/监督卡/数据记录全都不写，打卡面板、AI、已归档、
                // 滚期全都看不到这次打卡（用户实测「编辑器里已打卡，哪都对不上」）。
                // 改为行先落库，再走 completeTodo 正规打卡（滚期/流水/监督卡全链一致）。
                // 满态判定仍只走 TodoProgress.showsCheck（单一事实源，SingleSourceTest 钉死）。
                // 2026-11-14：showsDoneStyle 拆成「勾」（showsCheck）与「实心态」（showsSolidChecked）——
                // 这里问的是「算不算完成」，即勾那一条（满进度 100 = 该走正规打卡）。
                val autoComplete = !b.done &&
                    io.github.m0use001.planability.data.TodoProgress.showsCheck(b.done, b.percentDone)
                val newId = Graph.plan.saveTodo(b)
                if (autoComplete) {
                    Graph.plan.todoById(newId)?.let { t ->
                        Graph.plan.completeTodo(t, if (t.date > 0) t.date.toLocalDate() else AppTime.today())
                    }
                }
                val todo = b
                val alarmId = (ReminderScheduler.TODO_BASE + newId % ReminderScheduler.TODO_BASE).toInt()
                if (hasDate && todo.due > AppTime.nowMillis() && todo.remindMin >= 0) {
                    ReminderScheduler.schedule(
                        context, alarmId, todo.due - todo.remindMin * 60_000L,
                        "待办：${todo.title}", remindText(todo.due),
                        Notify.ROUTE_TODO, newId
                    )
                } else {
                    ReminderScheduler.cancel(context, alarmId)
                }
                onDone()
            }
        }
    }

    EditorScaffold(
        title = if (todoId == 0L) "新建待办" else "编辑待办",
        deleteName = title.trim().ifBlank { "该待办" },
        onBack = onDone,
        embedded = embedded,
        onDelete = if (todoId != 0L) ({
            scope.launch {
                ReminderScheduler.cancel(context, (ReminderScheduler.TODO_BASE + todoId % ReminderScheduler.TODO_BASE).toInt())
                Graph.plan.deleteTodo(todoId)
                onDone()
            }
        }) else null,
        // 2026-11-02 用户：保存移到标题栏右侧（正文底部那颗同时撤掉，避免两个保存入口）
        actions = {
            androidx.compose.material3.TextButton(
                onClick = saveTodo,
                enabled = title.isNotBlank()
            ) {
                Text("保存", style = MaterialTheme.typography.labelLarge)
            }
        }
    ) {
        OutlinedTextField(
            value = title, onValueChange = { title = it },
            label = { Text("标题") }, modifier = Modifier.fillMaxWidth(), singleLine = true
        )

        SectionLabel("日期与时间段")
        // 日期和两个时间**分行**放。挤在同一行时，"9月27日 周日 + 开始09:00 + 结束09:30"
        // 三段总宽超屏，"结束"那颗会被右边缘裁掉（真机实测：只显示成「结束 09:3」）。
        // 2026-10-23：chip 只管开/关「有日期」，日期本体拆成独立药丸点开日历——
        // 之前日期文字就印在 chip 上而点击语义是"切换有无"，导致日期**永远改不了**
        // （新建恒为今天；showDatePicker 有状态有弹窗却没有入口置真）。
        // CenterVertically 必须显式给：FilterChip 布局高度被 48dp 最小触控目标撑高（视觉 32 居中其中），
        // DateTimePill 钉死 32dp，Row 默认顶对齐会把药丸顶到 chip 上沿（真机截图 2026-10-25）。
        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            FilterChip(
                colors = filterChipColors(),
                selected = hasDate,
                onClick = { hasDate = !hasDate; haptics.perform(HapticEngine.Kind.Select) },
                label = { Text(if (hasDate) "有日期" else "无日期") },
                leadingIcon = { Icon(Icons.Rounded.CalendarMonth, null, Modifier.size(16.dp)) }
            )
            if (hasDate) {
                DateTimePill(date.formatShort()) { showDatePicker = true }
            }
        }
        if (hasDate) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                // 2026-11-02：时间可缺省（日期仍必填）。选「无时间」时时间药丸收起，
                // 保存落 due=0 —— 时间轴不排它，首页底部胶囊汇总。
                FilterChip(
                    colors = filterChipColors(),
                    selected = !noTime,
                    onClick = { noTime = !noTime; haptics.perform(HapticEngine.Kind.Select) },
                    label = { Text(if (noTime) "无时间" else "有时间") },
                    leadingIcon = { Icon(Icons.Rounded.AccessTime, null, Modifier.size(16.dp)) }
                )
                if (!noTime) {
                    // 小药丸，不再 weight(1f) 平分宽度；时间只显示 HH:mm
                    // 开始/结束合并成一个胶囊（2026-10-20 用户要求）
                    DateTimePill("${time.hm()} – ${endTime.hm()}") { showRange = true }
                }
            }
            // 重复时把跨度摆出来（永久重复只有频率没有截止）
            if (rec.repeats) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "跨度：" + rec.describe(date),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            // 2026-11-19 用户「早已结束的待办突然出现在今天」：日期被挪到截止之后时，
            // 上面那行跨度会自相矛盾（「至 11月7日」而日期是 11月19日）却照存不误，
            // 存出来的行既不在系列里、也永远滚不回来。这里明说一句 + 保存时收回截止日。
            if (rec.untilDayKey > 0 && AppTime.dayKey(date) > rec.untilDayKey) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "日期晚于重复截止（${rec.untilDayKey.toLocalDate().formatShort()}），保存时收回截止日",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
        if (hasDate && !noTime) {
            val dueMillis = AppTime.millisOf(date, time.hour, time.minute)
            val endMillis = AppTime.millisOf(date, endTime.hour, endTime.minute)
            if (endMillis <= dueMillis) {
                Text(
                    "结束时间需晚于开始时间（保存时自动顺延 30 分钟）",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }

        // v28：原「优先级」三档按钮 + 「分类」区块 → 紧急性/重要性双滑块（分类体系随后删除）
        SectionLabel("重复与结束")
        RepeatEditor(anchor = if (hasDate) date else AppTime.today(), rec = rec, onChange = { next ->
            rec = next
            // 2026-10-25 用户：这里的「结束 = 永久」和下面原来的「常驻显示」是同一件事，
            // 两个控件并排存在只会让人以为要各选一次。合并成一条口径：
            //   重复且没有截止 → 常驻（permanent=true，天天可见）
            //   选了截止 / 改回不重复 → 不再常驻
            permanent = next.repeats && next.untilDayKey == 0L
            // 频率与日期联动：所选频率的首个出现日与当前日期不一致时，日期自动跳到首个出现日
            if (hasDate) {
                val target = next.firstOccurrenceOnOrAfter(date)
                if (target != null && target != date) date = target
            }
        })
        // 原来的「常驻显示（不设截止，一直存在）」开关已并入上面的「结束」，不再重复给入口。

        SectionLabel("紧急性 · 重要性")
        UrgencyImportanceSliders(
            urgency = urgency, onUrgency = { urgency = it },
            importance = importance, onImportance = { importance = it }
        )

        // v11：地点与地图选点
        // 地点不再手打：只能「选新点」或「点已添加的地点」。
        // 手打有两个坏处——名字和坐标对不上（错一个字就查不到），以及同一地点每次存成
        // 不同字符串、地点分类再也复用不上。所以输入框整个去掉。
        SectionLabel("地点")
        // 2026-10-25 用户设计：地点栏只显示「绑定地名 + 显示地名」和一个编辑按钮。
        // 具体选哪个绑定、哪间房去独立的「选择地点」页（按绑定地名分组、展开成显示地名胶囊）；
        // 地图选点入口也在那一页（新建绑定地点）。
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f)) {
                Text(
                    placePoi.ifBlank { "未选择地点" },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                Text(
                    place.ifBlank {
                        if (placePoi.isNotBlank()) "未选择显示地名（点「编辑」补）" else "点「编辑」选择地点"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            }
            OutlinedButton(onClick = { showPlacePicker = true }) { Text("编辑") }
        }

        RemindChipRow(remindMin) { remindMin = it }

        // v28：原「分类」区块在这里移除（分类体系随后整套删除，见 2026-10-11 decision）。
        // tagId 字段仍照旧保存：存量数据的分类不丢，只是不再有新的设置入口。
        SectionLabel("标签")
        io.github.m0use001.planability.ui.common.LabelEditorRow(
            value = labels,
            // 全局清单：以前写 emptyList()，候选里只剩预置词，
            // 已经用过的自定义标签一个都选不到，只能重新手打一遍同样的词。
            known = globalLabels.names,
            onChange = { labels = it },
            hues = labelHues,
            tints = globalLabels.tints,
            onColor = { name, tint ->
                // 改色落库后立刻刷新本屏的圆点：其他屏靠 dataVersion 变更自行重查
                scope.launch {
                    // **先存浓淡再存色相**：setLabelColor 会 bump dataVersion 触发全局重读，
                    // 顺序反过来的话重读可能先于浓淡落库，界面会闪回默认深浅。
                    io.github.m0use001.planability.ui.common.saveLabelTint(name, tint)
                    Graph.plan.setLabelColor(name, tint?.hue)
                    labelHues = Graph.plan.labelColors()
                }
            },
            onRename = { old, new ->
                scope.launch {
                    io.github.m0use001.planability.ui.common.renameLabelEverywhere(old, new)
                    labelHues = Graph.plan.labelColors()
                }
            }
        )

        SectionLabel("打卡监督")
        ToggleRow("开启打卡监督（连续天数统计）", supervision) { supervision = !supervision }

        // ---------- v46：百分比完成（与监督开关并列，不互相要求） ----------
        Spacer(Modifier.height(6.dp))
        ToggleRow("百分比完成（部分完成也计入完成率）", percentOn) { percentOn = !percentOn }
        if (percentOn) {
            Text(
                "拉到/填 100% 自动打卡完成；未完成时按完成度计入当天完成率",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(4.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                listOf(25, 50, 75).forEach { p ->
                    FilterChip(
                        colors = filterChipColors(),
                        selected = percentVal == p,
                        onClick = {
                            percentVal = p; percentText = p.toString()
                            haptics.perform(HapticEngine.Kind.Select)
                        },
                        label = { Text("$p%") }
                    )
                }
                Spacer(Modifier.weight(1f))
                OutlinedTextField(
                    value = percentText,
                    onValueChange = { raw ->
                        percentText = raw.filter { it.isDigit() }.take(3)
                        percentVal = percentText.toIntOrNull()?.coerceIn(0, 100) ?: 0
                    },
                    label = { Text("填数字") },
                    singleLine = true,
                    modifier = Modifier.width(110.dp)
                )
            }
            Slider(
                value = percentVal.toFloat(),
                onValueChange = { f ->
                    val v = f.roundToInt()
                    if (v != percentVal) {
                        percentVal = v; percentText = v.toString()
                        haptics.perform(HapticEngine.Kind.Select)
                    }
                },
                valueRange = 0f..100f
            )
        }

        // ---------- v22：打卡数据记录 ----------
        // 整块只在「开启打卡监督」时出现 —— 打卡是打卡监督带出来的，没开监督就无从打卡
        if (supervision) {
            Spacer(Modifier.height(6.dp))
            ToggleRow(
                "数据记录（每次打卡记下这次的${CheckinKind.label(dataKind)}，AI 也能看到）",
                dataRecord
            ) { dataRecord = !dataRecord }

            if (dataRecord) {
                Text(
                    CheckinKind.hint(dataKind),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(6.dp))
                // 2026-11-22 用户「可滑动项不要碰到框/圆角矩形边缘」：类型横滑胶囊行
                // （宽屏嵌在计划页右板里会滑进板圆角）改单个下拉胶囊。
                SelectDropdownChip(
                    summary = CheckinKind.label(dataKind),
                    options = CheckinKind.ALL.map { CheckinKind.label(it) },
                    selectedIndex = CheckinKind.ALL.indexOf(dataKind),
                    onSelect = { idx -> dataKind = CheckinKind.ALL[idx]; haptics.perform(HapticEngine.Kind.Select) }
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = dataLabel, onValueChange = { dataLabel = it },
                    label = { Text("字段名（如「早餐时间」「体重」）") },
                    singleLine = true, modifier = Modifier.fillMaxWidth()
                )
                if (dataKind == CheckinKind.NUMBER) {
                    OutlinedTextField(
                        value = dataUnit, onValueChange = { dataUnit = it },
                        label = { Text("单位（如 kg / ml，可留空）") },
                        singleLine = true, modifier = Modifier.fillMaxWidth()
                    )
                }
                if (dataKind == CheckinKind.TAGS) {
                    OutlinedTextField(
                        value = dataOptions, onValueChange = { dataOptions = it },
                        label = { Text("预设选项（逗号或顿号分隔，如「豆浆,油条,鸡蛋」）") },
                        singleLine = true, modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            // v54.5（用户 2026-11-07「打卡与数据不要混在详细编辑界面」）：
            // 账本/逐日明细/补交/历史记录不再铺在编辑器里——设置与查看分开：
            // 这里是"怎么记"，打卡面板是"记得怎么样"。
        }

        // v54.5 二次拍板「两个入口合并到打卡监督栏目底部」：唯一的查看入口钉在
        // 本栏目（监督/百分比/数据记录）的最末尾，不再在监督开关下面另放一个。
        // 三次拍板：入口改成胶囊按钮（与格式 chips 同语言），不再整行文字。
        if (supervision && onOpenCheckin != null && todoId != 0L) {
            Spacer(Modifier.height(10.dp))
            FilterChip(
                colors = filterChipColors(),
                selected = false,
                onClick = {
                    haptics.perform(HapticEngine.Kind.Tap)
                    onOpenCheckin(todoId)
                },
                label = { Text("打卡记录与统计 ›") }
            )
        }

        SectionLabel("备注")
        OutlinedTextField(
            value = note, onValueChange = { note = it },
            placeholder = { Text("补充说明（可选）") },
            modifier = Modifier.fillMaxWidth(), minLines = 2
        )

        AttachmentSection("todo", todoId)
        // 底部那颗「保存」已按用户要求移到标题栏（见 EditorScaffold 的 actions），这里不再重复
    }

    if (showDatePicker) DatePickerModal(date, { showDatePicker = false }, { date = it; showDatePicker = false })
    if (showRange) {
        TimeRangePickerScreen(
            day = if (hasDate) date else AppTime.today(),
            initialStart = time,
            initialEnd = endTime,
            includeTodos = true,
            excludeTodoId = if (todoId != 0L) todoId else null,
            onDismiss = { showRange = false },
            onPick = { s, e -> time = s; endTime = e; showRange = false }
        )
    }
    // 「选择地点」独立子页（自己用 PredictiveBackOverlay 承载，避开"塞进编辑器 Dialog 被裁"的坑）
    if (showPlacePicker) {
        io.github.m0use001.planability.ui.plan.PlacePickerScreen(
            currentBound = placePoi,
            currentDisplay = place,
            onDismiss = { showPlacePicker = false },
            onPick = { bound, pLat, pLng, display ->
                placePoi = bound
                lat = pLat
                lng = pLng
                place = display
                showPlacePicker = false
            }
        )
    }
    // v23：补交某一天（两种入口：点某天 → 直接开；点「补交/改打卡」→ 先选日）
    backfillDay?.let { (tid, d) ->
        val todoObj = loadedTodo
        if (todoObj != null && tid == todoObj.id) {
            var existing by remember(tid, d.date) { mutableStateOf<CheckinRecord?>(null) }
            var loaded by remember(tid, d.date) { mutableStateOf(false) }
            LaunchedEffect(tid, d.date) {
                existing = Graph.plan.checkinOn(tid, d.date.toLocalDate())
                loaded = true
            }
            if (loaded) {
                io.github.m0use001.planability.ui.common.BackfillCheckinDialog(
                    todo = todoObj,
                    day = d.date.toLocalDate(),
                    existing = existing,
                    onDismiss = { backfillDay = null },
                    onSave = { rec ->
                        scope.launch {
                            if (rec == null) {
                                // 2026-11-14：撤销统一走 undoCheckinDay（原 removeCheckin+toggleCheck
                                // 没卡时会加卡、且不撤 done/完成流水）
                                Graph.plan.undoCheckinDay(tid, d.date.toLocalDate())
                            } else {
                                Graph.plan.backfillCheck(tid, d.date.toLocalDate(), rec)
                            }
                            // 账本/回看都要立刻跟上，不然改完看着没生效
                            Graph.plan.todoById(tid)?.let { fresh ->
                                ledger = Graph.plan.checkinLedger(fresh)
                            }
                            records = Graph.plan.checkinsOf(tid)
                            stat = Graph.plan.supervisionStat(tid)
                        }
                        backfillDay = null
                    }
                )
            }
        } else {
            backfillDay = null
        }
    }
    backfillTarget?.let { bf ->
        val todoObj = loadedTodo
        if (todoObj != null && bf.todoId == todoObj.id) {
            var pick by remember { mutableStateOf<java.time.LocalDate?>(null) }
            // 只给过去的日期：今天的打卡走列表勾选，那条路已经通了
            val candidates = remember(ledger, todoObj.id) {
                ledger.map { it.date.toLocalDate() }
                    .filter { it.isBefore(AppTime.today()) }
                    .distinct()
                    .sortedDescending()
                    .take(60)
            }
            AlertDialog(
                modifier = Modifier.motionEnter(),
                onDismissRequest = { backfillTarget = null },
                title = { Text("补交 / 修改打卡") },
                text = {
                    Column {
                        Text(
                            "选一天。过去的补交会记为补交，迟一天扣 ${CheckinLedger.PENALTY_PER_DAY}%。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(8.dp))
                        if (candidates.isEmpty()) {
                            Text("没有可补交的日子", style = MaterialTheme.typography.bodySmall)
                        } else {
                            candidates.take(14).forEach { d ->
                                val day = ledger.first { it.date.toLocalDate() == d }
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { pick = d }
                                        .padding(vertical = 8.dp)
                                ) {
                                    Text(
                                        d.formatMonthDay() + " · 第${day.dayNo}天",
                                        style = MaterialTheme.typography.bodyMedium,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Text(
                                        CheckinDay.label(day.state),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (day.state == CheckinDay.MISSED)
                                            MaterialTheme.colorScheme.error
                                        else MaterialTheme.colorScheme.tertiary
                                    )
                                }
                            }
                            if (candidates.size > 14) {
                                Text(
                                    "更早的 ${candidates.size - 14} 天在「已过期」里展开",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(
                        enabled = pick != null,
                        onClick = {
                            val d = pick
                            if (d != null) {
                                backfillDay = todoObj.id to ledger.first { it.date.toLocalDate() == d }
                            }
                            backfillTarget = null
                        }
                    ) { Text("下一步") }
                },
                dismissButton = { TextButton(onClick = { backfillTarget = null }) { Text("取消") } }
            )
        } else {
            backfillTarget = null
        }
    }
    pendingDelete?.let { r ->
        AlertDialog(
            modifier = Modifier.motionEnter(),
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除这条打卡记录？") },
            text = {
                Text(
                    CheckinFormat.line(r, AppTime.zone),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val id = loadedTodo?.id ?: r.todoId
                    scope.launch { Graph.plan.removeCheckin(id, r.date) }
                    // 立刻改本地列表，不等重进页面，否则看着像没删掉
                    records = records.filterNot { it.id == r.id }
                    pendingDelete = null
                }) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("取消") } }
        )
    }
}

/** v23：编辑器里点「补交/改打卡」但还没选日子的状态 */
private class LedgerBackfill(val todoId: Long)

@Composable
fun SectionLabel(text: String) {
    Text(
        text, style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold
    )
}

/**
 * v28：五档滑块（0-4），一个滑块 = 一个维度，右侧显示档位文案与它的颜色。
 *
 * 为什么不用按钮组：三档按钮（高/中/低）表达不了「偏高/偏低」，五档按钮在窄屏上要挤两行；
 * 滑块一拉到位，`steps = 3` 自带档位吸附（不会停在 2.7 这种值上）。
 */
@Composable
private fun LevelSlider(
    label: String,
    value: Int,
    onChange: (Int) -> Unit,
    /**
     * 档位色。默认走三档 priorityColor，但「紧急性 / 重要性」各传自己的五档色阶——
     * 共用三档色会让「偏低 / 低」「偏高 / 高」显示成同一个颜色。
     */
    colorOf: @Composable (Int) -> androidx.compose.ui.graphics.Color = { priorityColor(PriorityMap.priorityOf(it)) }
) {
    val haptics = LocalHaptics.current
    // -1 = 未设置：滑块停在"中"的位置但文案写明未设置；第一次拖动才真正赋值
    val unset = value < 0
    val shown = if (unset) 2 else value
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.weight(1f))
            Text(
                if (unset) "未设置" else PriorityMap.labelOf(value),
                style = MaterialTheme.typography.labelMedium,
                color = if (unset) MaterialTheme.colorScheme.outline else colorOf(value)
            )
        }
        // 轨道颜色跟着**当前档位**走。
        // 之前用的是 Slider 默认配色（固定 primary + secondaryContainer），
        // 拖到「紧急」「重要」哪一档都长得一样，等于颜色锁死（用户反馈「滑动条锁死颜色」）。
        val levelColor = if (unset) MaterialTheme.colorScheme.outline else colorOf(value)
        Slider(
            value = shown.toFloat().coerceIn(0f, 4f),
            onValueChange = { v ->
                val level = v.roundToInt().coerceIn(0, 4)
                if (unset || level != value) {
                    onChange(level)
                    haptics.perform(HapticEngine.Kind.Select)
                }
            },
            valueRange = 0f..4f,
            steps = 3,
            colors = androidx.compose.material3.SliderDefaults.colors(
                thumbColor = levelColor,
                activeTrackColor = levelColor,
                inactiveTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                activeTickColor = MaterialTheme.colorScheme.surface,
                inactiveTickColor = MaterialTheme.colorScheme.outlineVariant
            ),
            modifier = Modifier.fillMaxWidth()
        )
    }
}

/**
 * v28：日程与待办共用的「紧急性 · 重要性」双滑块——**顶掉原来的「分类」区块**
 * （分类体系随后整套删除，见 2026-10-11 的 decision）与三档「优先级/重要程度」。
 *
 * 两维分开滑、分开存：紧急性是时间压力、重要性是价值权重，混成一维就没法按其中一维筛——
 * 计划页新加的那两个筛选维度、以及「按紧急·重要着色」开关，都建立在它们各自独立之上。
 */
@Composable
fun UrgencyImportanceSliders(
    urgency: Int, onUrgency: (Int) -> Unit,
    importance: Int, onImportance: (Int) -> Unit
) {
    // 2026-10-20：改成**开关**——默认关闭（两维都是 -1），关闭时连滑块都不显示。
    // 打开时给一个中性起点（中=2），用户再按需拖。关掉 = 两维一起清空。
    val enabled = urgency >= 0 || importance >= 0
    ToggleRow("设置紧急 · 重要性", enabled) {
        if (enabled) {
            onUrgency(-1); onImportance(-1)
        } else {
            if (urgency < 0) onUrgency(2)
            if (importance < 0) onImportance(2)
        }
    }
    if (enabled) {
        LevelSlider("紧急性", urgency, onUrgency) { level ->
            io.github.m0use001.planability.ui.common.urgencyColor(level)
        }
        LevelSlider("重要性", importance, onImportance) { level ->
            io.github.m0use001.planability.ui.common.importanceColor(level)
        }
    } else {
        Text(
            "不设置就不参与紧急·重要着色",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** 提前提醒选择：不提醒(-1)/准时(0)/提前 N 分钟 */
@Composable
fun RemindChipRow(remindMin: Int, onChange: (Int) -> Unit) {
    val haptics = LocalHaptics.current
    SectionLabel("提醒")
    // FlowRow（审查差距 13）：原来单行横滚，「提前3…」在屏幕右缘被裁掉且无滚动提示；换行后全可见
    @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
    androidx.compose.foundation.layout.FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        listOf(-1 to "不提醒", 0 to "准时", 5 to "提前5分", 15 to "提前15分", 30 to "提前30分", 60 to "提前1小时")
            .forEach { (v, label) ->
                FilterChip(
                    colors = filterChipColors(),
                    selected = remindMin == v,
                    onClick = { onChange(v); haptics.perform(HapticEngine.Kind.Select) },
                    label = { Text(label) }
                )
            }
    }
}

private fun remindText(millis: Long): String {
    val dt = java.time.Instant.ofEpochMilli(millis).atZone(AppTime.zone).toLocalDateTime()
    return "${dt.monthValue}月${dt.dayOfMonth}日 %02d:%02d".format(dt.hour, dt.minute)
}

@Composable
fun ToggleRow(label: String, checked: Boolean, onChange: () -> Unit) {
    val haptics = LocalHaptics.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        // 开/关都算"选择"：最轻的 Select（HIG selection → 见 core/Haptics.kt 映射表）
        Switch(checked = checked, onCheckedChange = {
            haptics.perform(HapticEngine.Kind.Select)
            onChange()
        })
    }
}

// ==================== 日程编辑器 ====================

@Composable
fun ScheduleEditScreen(
    scheduleId: Long,
    presetDate: LocalDate?,
    onDone: () -> Unit,
    /** v62：true = 计划页宽屏右栏就地编辑（不自包全屏 Dialog）。手机恒 false。 */
    embedded: Boolean = false
) {
    val scope = rememberCoroutineScope()
    val haptics = LocalHaptics.current
    val context = LocalContext.current
    var title by remember { mutableStateOf("") }
    var startDate by remember { mutableStateOf(presetDate ?: AppTime.today()) }
    var startTime by remember { mutableStateOf(LocalTime.of(9, 0)) }
    var endDate by remember { mutableStateOf(presetDate ?: AppTime.today()) }
    var endTime by remember { mutableStateOf(LocalTime.of(10, 0)) }
    var tagId by remember { mutableStateOf(0L) }
    var labels by remember { mutableStateOf("") }
    /** v28：标签名 → 色相（长按标签改色，存 label_colors） */
    var labelHues by remember { mutableStateOf<Map<String, Float>>(emptyMap()) }
    // 全局统一标签：清单与颜色对所有页面同一套算法，见 ui/common/LabelStore.kt
    val globalLabels = io.github.m0use001.planability.ui.common.rememberGlobalLabels()
    var place by remember { mutableStateOf("") }
    var placePoi by remember { mutableStateOf("") }
    // v28：三档重要程度退休 → 五档紧急性/重要性（priority 仍存，由 importance 派生）
    var urgency by remember { mutableStateOf(-1) }
    var importance by remember { mutableStateOf(-1) }
    var lat by remember { mutableStateOf(0.0) }
    var lng by remember { mutableStateOf(0.0) }
    var showPlacePicker by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf("") }
    var rec by remember { mutableStateOf(Recurrence.NONE) }
    var remindMin by remember { mutableStateOf(15) }
    /** v27：绑了坐标的地点分类，一键带出「地点名 + 坐标」 */
    var geoTags by remember { mutableStateOf<List<CategoryEntity>>(emptyList()) }
    var showStart by remember { mutableStateOf(false) }
    var showStartTime by remember { mutableStateOf(false) }
    // 2026-10-20：开始/结束都走时间轴拖拽（一次选一段），不再用两个转盘
    var showRange by remember { mutableStateOf(false) }
    var showEnd by remember { mutableStateOf(false) }
    var showEndTime by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        labelHues = Graph.plan.labelColors()
        geoTags = Graph.plan.categories(1).filter { it.lat != 0.0 || it.lng != 0.0 }
        if (scheduleId != 0L) {
            Graph.plan.scheduleById(scheduleId)?.let { s ->
                title = s.title
                val sDt = java.time.Instant.ofEpochMilli(s.start).atZone(AppTime.zone)
                val eDt = java.time.Instant.ofEpochMilli(s.end).atZone(AppTime.zone)
                startDate = sDt.toLocalDate(); startTime = sDt.toLocalTime()
                endDate = eDt.toLocalDate(); endTime = eDt.toLocalTime()
                tagId = s.tagId; place = s.place; placePoi = s.placePoi; note = s.note
                labels = s.labels
                urgency = s.urgency; importance = s.importance; lat = s.lat; lng = s.lng
                rec = Recurrence.parseScheduleRule(s.repeatRule)
                remindMin = s.remindMin
            }
        }
    }

    // v12：结束不得早于开始——早于时红色提示并禁用保存。
    // 2026-11-02 保存按钮移到标题栏后，顶栏要拿它做 enabled 判断，所以从 content 内部提出来。
    val endBeforeStart = java.time.LocalDateTime.of(endDate, endTime)
        .isBefore(java.time.LocalDateTime.of(startDate, startTime))

    // 2026-11-02 用户「把保存按钮移到标题栏」：保存动作抽成函数，顶栏「保存」与
    // （已撤掉的）正文按钮共用同一份实现。
    val saveSchedule: () -> Unit = {
        if (title.isNotBlank() && !endBeforeStart) {
            haptics.perform(HapticEngine.Kind.Success)
            scope.launch {
                val start = AppTime.millisOf(startDate, startTime.hour, startTime.minute)
                val end = AppTime.millisOf(endDate, endTime.hour, endTime.minute)
                val id = Graph.plan.saveSchedule(
                    Schedule(
                        id = scheduleId, title = title.trim(), start = start,
                        end = maxOf(start, end), tagId = tagId, place = place.trim(),
                        placePoi = placePoi.trim(),
                        note = note.trim(), repeatRule = Recurrence.toScheduleRule(rec), remindMin = remindMin,
                        priority = PriorityMap.priorityOf(importance),
                        urgency = urgency, importance = importance,
                        lat = lat, lng = lng, labels = labels
                    )
                )
                val alarmId = (ReminderScheduler.SCHEDULE_BASE + id % ReminderScheduler.SCHEDULE_BASE).toInt()
                if (start > AppTime.nowMillis() && remindMin >= 0) {
                    ReminderScheduler.schedule(
                        context, alarmId, start - remindMin * 60_000L,
                        "日程：${title.trim()}", remindText(start),
                        Notify.ROUTE_SCHEDULE, id
                    )
                } else {
                    ReminderScheduler.cancel(context, alarmId)
                }
                onDone()
            }
        }
    }

    EditorScaffold(
        title = if (scheduleId == 0L) "新建日程" else "编辑日程",
        deleteName = title.trim().ifBlank { "该日程" },
        onBack = onDone,
        embedded = embedded,
        onDelete = if (scheduleId != 0L) ({
            scope.launch {
                ReminderScheduler.cancel(context, (ReminderScheduler.SCHEDULE_BASE + scheduleId % ReminderScheduler.SCHEDULE_BASE).toInt())
                Graph.plan.deleteSchedule(scheduleId); onDone()
            }
        }) else null,
        // 2026-11-02 用户：保存移到标题栏右侧
        actions = {
            androidx.compose.material3.TextButton(
                onClick = saveSchedule,
                enabled = title.isNotBlank() && !endBeforeStart
            ) {
                Text("保存", style = MaterialTheme.typography.labelLarge)
            }
        }
    ) {
        OutlinedTextField(
            value = title, onValueChange = { title = it },
            label = { Text("标题") }, modifier = Modifier.fillMaxWidth(), singleLine = true
        )

        SectionLabel("时间")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            DateTimePill(startDate.formatMonthDay()) { showStart = true }
            // 开始/结束合并成一个胶囊（2026-10-20 用户要求）；跨天的老数据标「次日」
            val dayMark = if (endDate != startDate) "次日 " else ""
            DateTimePill("${startTime.hm()} – $dayMark${endTime.hm()}") { showRange = true }
        }
        // 重复日程把跨度摆出来：只显示一个日期会让人以为只有这一天（用户 2026-10-20）
        if (rec.repeats) {
            Spacer(Modifier.height(4.dp))
            Text(
                "跨度：" + rec.describe(startDate),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        // v12：结束不得早于开始——早于时红色提示并禁用保存（endBeforeStart 已提到 scaffold 之前）
        if (endBeforeStart) {
            Text(
                "结束时间不能早于开始时间",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }

        // v28：原「分类」区块在这里移除（分类体系随后整套删除）；tagId 照旧保存，存量分类不丢
        SectionLabel("重复与结束")
        RepeatEditor(anchor = startDate, rec = rec, onChange = { next ->
            rec = next
            // 频率与日期联动：日期自动跳到首个出现日（保持时长，结束日同步平移）
            val target = next.firstOccurrenceOnOrAfter(startDate)
            if (target != null && target != startDate) {
                val delta = java.time.temporal.ChronoUnit.DAYS.between(startDate, target)
                startDate = target
                endDate = endDate.plusDays(delta)
            }
        })

        SectionLabel("紧急性 · 重要性")
        UrgencyImportanceSliders(
            urgency = urgency, onUrgency = { urgency = it },
            importance = importance, onImportance = { importance = it }
        )

        SectionLabel("标签")
        io.github.m0use001.planability.ui.common.LabelEditorRow(
            value = labels,
            // 全局清单：以前写 emptyList()，候选里只剩预置词，
            // 已经用过的自定义标签一个都选不到，只能重新手打一遍同样的词。
            known = globalLabels.names,
            onChange = { labels = it },
            hues = labelHues,
            tints = globalLabels.tints,
            onColor = { name, tint ->
                // 改色落库后立刻刷新本屏的圆点：其他屏靠 dataVersion 变更自行重查
                scope.launch {
                    // **先存浓淡再存色相**：setLabelColor 会 bump dataVersion 触发全局重读，
                    // 顺序反过来的话重读可能先于浓淡落库，界面会闪回默认深浅。
                    io.github.m0use001.planability.ui.common.saveLabelTint(name, tint)
                    Graph.plan.setLabelColor(name, tint?.hue)
                    labelHues = Graph.plan.labelColors()
                }
            },
            onRename = { old, new ->
                scope.launch {
                    io.github.m0use001.planability.ui.common.renameLabelEverywhere(old, new)
                    labelHues = Graph.plan.labelColors()
                }
            }
        )

        // 地点不再手打：只能「选新点」或「点已添加的地点」。
        // 手打有两个坏处——名字和坐标对不上（错一个字就查不到），以及同一地点每次存成
        // 不同字符串、地点分类再也复用不上。所以输入框整个去掉。
        SectionLabel("地点")
        // 2026-10-25 用户设计：地点栏只显示「绑定地名 + 显示地名」和一个编辑按钮。
        // 具体选哪个绑定、哪间房去独立的「选择地点」页（按绑定地名分组、展开成显示地名胶囊）；
        // 地图选点入口也在那一页（新建绑定地点）。
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f)) {
                Text(
                    placePoi.ifBlank { "未选择地点" },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                Text(
                    place.ifBlank {
                        if (placePoi.isNotBlank()) "未选择显示地名（点「编辑」补）" else "点「编辑」选择地点"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            }
            OutlinedButton(onClick = { showPlacePicker = true }) { Text("编辑") }
        }

        // v28：原「重要程度」三档 chips 删除（用户明确要求），双滑块已在「分类」原位接过这件事
        RemindChipRow(remindMin) { remindMin = it }

        SectionLabel("备注")
        OutlinedTextField(
            value = note, onValueChange = { note = it },
            placeholder = { Text("补充说明（可选）") },
            modifier = Modifier.fillMaxWidth(), minLines = 2
        )

        AttachmentSection("schedule", scheduleId)
        // 底部那颗「保存」已按用户要求移到标题栏（见 EditorScaffold 的 actions），这里不再重复
    }

    if (showStart) DatePickerModal(startDate, { showStart = false }, {
        // 单日日程：改开始日期时结束日期跟着走；跨天老数据保留原结束日期
        if (endDate == startDate) endDate = it
        startDate = it
        showStart = false
    })
    if (showRange) {
        TimeRangePickerScreen(
            day = startDate,
            initialStart = startTime,
            initialEnd = endTime,
            excludeId = if (scheduleId != 0L) scheduleId else null,
            onDismiss = { showRange = false },
            onPick = { s, e ->
                startTime = s; endTime = e
                endDate = startDate      // 时间轴选的是同一天的一段
                showRange = false
            }
        )
    }
    // 选点面板必须开**独立窗口**：本编辑器本身就是 PredictiveBackOverlay 的独立 Dialog 窗口，
    // 「选择地点」独立子页（自己用 PredictiveBackOverlay 承载，避开"塞进编辑器 Dialog 被裁"的坑）
    if (showPlacePicker) {
        io.github.m0use001.planability.ui.plan.PlacePickerScreen(
            currentBound = placePoi,
            currentDisplay = place,
            onDismiss = { showPlacePicker = false },
            onPick = { bound, pLat, pLng, display ->
                placePoi = bound
                lat = pLat
                lng = pLng
                place = display
                showPlacePicker = false
            }
        )
    }
}

// ==================== 自由事件编辑器（拖拽手柄版） ====================

@Composable
fun TimelineEventEditor(
    eventId: Long,
    date: LocalDate,
    onDone: () -> Unit,
    /** v76：true = 由首页钻入板内的瓦片直接承载（不自包全屏覆盖层）。 */
    embedded: Boolean = false
) {
    val scope = rememberCoroutineScope()
    var title by remember { mutableStateOf("") }
    var startMin by remember { mutableStateOf(AppTime.now().hour * 60 + 30) }
    var endMin by remember { mutableStateOf(AppTime.now().hour * 60 + 90) }
    var hue by remember { mutableStateOf(210f) }

    LaunchedEffect(Unit) {
        if (eventId != 0L) {
            Graph.db.timelineEventDao().byId(eventId)?.let {
                title = it.title; startMin = it.startMin
                endMin = it.startMin + it.durMin; hue = it.hue
            }
        }
    }

    fun fmt(min: Int) = "%02d:%02d".format(min / 60, min % 60)

    EditorScaffold(
        title = "自由事件",
        onBack = onDone,
        embedded = embedded,
        onDelete = if (eventId != 0L) ({
            scope.launch { Graph.plan.deleteEvent(eventId); onDone() }
        }) else null
    ) {
        OutlinedTextField(
            value = title, onValueChange = { title = it },
            label = { Text("事件") }, modifier = Modifier.fillMaxWidth(), singleLine = true
        )

        SectionLabel("拖动圆点调整起止（拖到时间数字自动吸附）")
        // ---- mini 时间轴 + 拖拽手柄 ----
        val hourHeight = 320.dp
        val density = LocalDensity.current
        BoxWithConstraints(
            Modifier
                .fillMaxWidth()
                .height(hourHeight + 30.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(MaterialTheme.colorScheme.surfaceContainer)
        ) {
            val trackHeight = hourHeight
            val pxPerMin = with(density) { trackHeight.toPx() } / (24 * 60f)
            // 2026-10-25 用户「取消最小 5 分钟分度值」：拖动按 1 分钟粒度，
            // 只在贴近整点刻度（±3 分钟）时吸到整点。
            fun snap(v: Float): Int {
                val m = v.roundToInt()
                val hour = ((m + 30) / 60) * 60
                val snapped = if (kotlin.math.abs(m - hour) <= 3) hour else m
                return snapped.coerceIn(0, 1410)
            }

            Column(Modifier.fillMaxSize()) {
                repeat(24) { h ->
                    Box(Modifier.fillMaxWidth().height((hourHeight / 24))) {
                        Text(
                            "%02d".format(h),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 8.dp, top = 2.dp)
                        )
                    }
                }
            }

            // 块
            val topDp = (trackHeight * (startMin / 1440f))
            val hDp = (trackHeight * ((endMin - startMin) / 1440f)).coerceAtLeast(28.dp)
            Box(
                Modifier
                    .offset(x = 34.dp, y = topDp)
                    .width(maxWidth - 48.dp)
                    .height(hDp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(io.github.m0use001.planability.ui.theme.SeedColors.tone(hue, false).copy(alpha = 0.85f))
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            ) {
                Text(
                    "${title.ifBlank { "事件" }}  ${fmt(startMin)}-${fmt(endMin)}",
                    style = MaterialTheme.typography.labelMedium,
                    color = androidx.compose.ui.graphics.Color.White,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            }

            // 起点手柄
            Box(
                Modifier
                    .offset(x = 24.dp, y = topDp - 12.dp)
                    .size(24.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary)
                    .pointerInput(Unit) {
                        // 2026-10-25：绝对位移 + 补回被 detectDragGestures 吞掉的 touchSlop
                        // （首个 onDrag 只给越过 slop 的部分）——与日程/待办时间轴同一套口径，
                        // 否则手指拖到整点刻度上时值总会差几分钟。
                        var base = 0
                        var total = 0f
                        detectDragGestures(
                            onDragStart = {
                                base = startMin
                                total = viewConfiguration.touchSlop
                            },
                            onDrag = { change, drag ->
                                change.consume()
                                total += drag.y
                                startMin = snap(base + total / pxPerMin)
                                if (endMin - startMin < 30) startMin = endMin - 30
                            }
                        )
                    },
                contentAlignment = Alignment.Center
            ) { Box(Modifier.size(8.dp).clip(CircleShape).background(androidx.compose.ui.graphics.Color.White)) }

            // 终点手柄
            Box(
                Modifier
                    .offset(x = 24.dp, y = topDp + hDp - 12.dp)
                    .size(24.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.tertiary)
                    .pointerInput(Unit) {
                        // 同起点手柄：绝对位移 + touchSlop 补偿，拖到刻度线即落在刻度上
                        var base = 0
                        var total = 0f
                        detectDragGestures(
                            onDragStart = {
                                base = endMin
                                total = viewConfiguration.touchSlop
                            },
                            onDrag = { change, drag ->
                                change.consume()
                                total += drag.y
                                endMin = snap(base + total / pxPerMin)
                                if (endMin - startMin < 30) endMin = startMin + 30
                            }
                        )
                    },
                contentAlignment = Alignment.Center
            ) { Box(Modifier.size(8.dp).clip(CircleShape).background(androidx.compose.ui.graphics.Color.White)) }
        }

        Text(
            "时间：${fmt(startMin)} — ${fmt(endMin)} · 时长 ${endMin - startMin} 分钟",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold
        )

        SectionLabel("颜色")
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            listOf(210f, 340f, 28f, 145f, 262f, 188f).forEach { h ->
                Box(
                    Modifier
                        .size(if (hue == h) 38.dp else 32.dp)
                        .clip(CircleShape)
                        .background(categoryColor(h))
                        .clickable { hue = h }
                )
            }
        }
        Button(
            onClick = {
                scope.launch {
                    Graph.plan.saveEvent(
                        io.github.m0use001.planability.data.TimelineEvent(
                            id = eventId, date = AppTime.dayKey(date), startMin = startMin,
                            durMin = endMin - startMin,
                            title = title.trim().ifEmpty { "（未命名）" }, hue = hue
                        )
                    )
                    onDone()
                }
            },
            enabled = title.isNotBlank(),
            modifier = Modifier.fillMaxWidth()
        ) { Text("保存") }
    }
}
