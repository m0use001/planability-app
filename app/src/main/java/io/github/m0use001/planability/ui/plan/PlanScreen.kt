@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class
)

package io.github.m0use001.planability.ui.plan

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.HorizontalDivider
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Flag
import androidx.compose.material.icons.rounded.Label
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import io.github.m0use001.planability.app.LocalOverlayClose
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.VerticalDivider
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.m0use001.planability.app.PredictiveBackOverlay
import io.github.m0use001.planability.app.Router
import io.github.m0use001.planability.core.AppTime
import io.github.m0use001.planability.core.HapticEngine
import io.github.m0use001.planability.core.hScrollZone
import io.github.m0use001.planability.core.LocalHaptics
import io.github.m0use001.planability.core.reachedEndHaptic
import io.github.m0use001.planability.core.formatCn
import io.github.m0use001.planability.core.formatMonthDay
import io.github.m0use001.planability.core.toLocalDate
// v64（2026-11-20）：两板材质已抽到共享层（全 app 主从页面共用一份）
import io.github.m0use001.planability.ui.common.SplitBoards
import io.github.m0use001.planability.ui.common.BoardDrill
import io.github.m0use001.planability.data.CategoryEntity
import io.github.m0use001.planability.data.Countdown
import io.github.m0use001.planability.data.Graph
import io.github.m0use001.planability.data.ScheduleOcc
import io.github.m0use001.planability.data.SupervisionOverview
import io.github.m0use001.planability.data.SupervisionStat
import io.github.m0use001.planability.data.CheckinFormat
import io.github.m0use001.planability.data.CheckinKind
import io.github.m0use001.planability.data.CheckinRecord
import io.github.m0use001.planability.data.CheckinDay
import io.github.m0use001.planability.data.CheckinLedger
import io.github.m0use001.planability.data.Todo
import io.github.m0use001.planability.ui.common.ConfirmDialog
import io.github.m0use001.planability.ui.common.EmptyHint
import io.github.m0use001.planability.ui.common.MotionTabIndicator
import io.github.m0use001.planability.ui.common.adaptiveColumn
import io.github.m0use001.planability.ui.common.MediumFab
import io.github.m0use001.planability.ui.common.DotCapsule
import io.github.m0use001.planability.ui.common.LabelTint
import io.github.m0use001.planability.ui.common.importanceColor
import io.github.m0use001.planability.ui.common.urgencyColor
import io.github.m0use001.planability.ui.common.urgencyImportanceColor
import io.github.m0use001.planability.ui.common.filterChipColors
import io.github.m0use001.planability.ui.common.pressScale
import io.github.m0use001.planability.ui.common.Motion
import io.github.m0use001.planability.ui.common.TagChip
import io.github.m0use001.planability.ui.common.categoryColor
import io.github.m0use001.planability.ui.common.priorityColor
import io.github.m0use001.planability.ui.common.priorityLabel
import io.github.m0use001.planability.ui.common.DatePickerModal
import io.github.m0use001.planability.ui.edit.ScheduleEditScreen
import io.github.m0use001.planability.ui.edit.TodoEditScreen
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import io.github.m0use001.planability.ui.common.motionEnter

/** v70d：BoardDrill 的钻入目标（segment + 条目 id，稳定值——相等不触发失效）。 */
private data class PlanDrillKey(val segment: Int, val id: Long)

/**
 * v28：筛选维度清单。前三项是老的，后两项是这次需求加的（按重要性/紧急性分类显示）。
 * 抽成常量是因为下拉标题和下拉项必须是同一份清单 —— 各写一个 `listOf` 就会一个加了另一个没加，
 * 表现为"标题写重要性、点开却没有这一项"。
 */
private val FILTER_DIMS = listOf("状态", "分类", "标签", "重要性", "紧急性")

/**
 * 筛选胶囊（2026-10-25 用户：时间范围与状态「分的太开」，要 2 行内显示完）。
 *
 * 横向策略由 [LocalFilterFill] 决定：
 * - 选项少（≤5 项）：`weight(1f)` 等分整行 → 铺满、没有大片空洞（用户反馈的"分的太开"）；
 * - 选项多（分类/标签/重要性/紧急性）：保持自然宽度换行，绝不压缩文字。
 * 内边距收到 6dp，让「过期 3 / 全部」这类标签在 360dp 级宽度下也能单行放下。
 */
private val LocalFilterFill = androidx.compose.runtime.compositionLocalOf { true }

@Composable
private fun androidx.compose.foundation.layout.FlowRowScope.FillFilterChip(
    selected: Boolean,
    onClick: () -> Unit,
    label: @Composable () -> Unit
) {
    FilterChip(
        colors = filterChipColors(),
        selected = selected,
        onClick = onClick,
        horizontalArrangement = Arrangement.Center,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp),
        label = label,
        // 2026-10-25 用户「做紧凑一点」：钉 32dp，去掉 48dp 最小触控目标的撑高
        modifier = (if (LocalFilterFill.current) Modifier.weight(1f) else Modifier)
            .height(32.dp)
            .pressScale()
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlanScreen() {
    val haptics = LocalHaptics.current
    val scope = rememberCoroutineScope()
    // v59 平板：控制行（分段 + 范围筛选）不能跟着 840dp 的列一起拉满——
    // 每段按钮 420dp、每枚筛选 chip 200dp，看着像被充气（用户 16:10 截图点名）。
    // 宽屏时收成「正常控件」的宽度；手机口径（占满整行，用户 2026-10-25 明确要的）不变。
    val sizeClass = io.github.m0use001.planability.core.rememberWindowSizeClass()
    val wideControls = sizeClass.isWide
    var segment by remember { mutableIntStateOf(0) } // 0 日程 1 待办
    // 分段滑动胶囊的动画进度必须**活在这里**（key(segment) 外面）：切段时下面
    // key(segment) 会整棵重建 LazyColumn（消除旧行淡出叠影），控制行里的 AppButtonGroup
    // 也跟着重建——组件内部 remember 的弹簧会以新索引为初值直接落位，胶囊一次都不动
    // （2026-10-29 用户「日程-待办切换还是没动画」）。提到这一层后重建只是换了个消费者，
    // 弹簧本身继续跑完，中间帧照常渲染。
    // 2026-10-30 用户要求「日程-待办切换按钮改成非线性动画」：上一版走 Motion.standard()
    // 弹簧，曲线对称、加速度均匀，看起来接近匀速平推。改成 emphasized 缓动
    // （0.2,0,0,1：起手快、收尾长减速），与底栏切页的滑动同一条曲线，摸起来"溜进去"
    // 而不是"被推过去"。时长取 emphasized 档（300ms），不缩短——缩短了就看不出尾巴。
    val segmentSlide = remember { androidx.compose.animation.core.Animatable(segment.toFloat()) }
    LaunchedEffect(segment) {
        segmentSlide.animateTo(
            segment.toFloat(),
            androidx.compose.animation.core.tween(
                durationMillis = Motion.emphasizedMs,
                easing = Motion.emphasizedEasing
            )
        )
    }

    // 小组件深链：组件里点「待办区 / 日程区」时，Router 会带一个 pendingPlanSubTab 过来。
    // 用 key 是 pendingPlanSubTab 的 effect 而不是 LaunchedEffect(Unit)：
    // 计划页可能已经在组合里活着（只是被切走），Unit 版只在首次进入时消费一次，
    // 之后再从组件点进来就失效了。
    LaunchedEffect(Router.pendingPlanSubTab) {
        val sub = Router.pendingPlanSubTab
        if (sub != null) {
            segment = if (sub == "todo") 1 else 0
            Router.pendingPlanSubTab = null
        }
    }

    var range by remember { mutableStateOf("all") }   // all / today / week / month
    var statusFilter by remember { mutableStateOf("active") } // all / active / done / overdue
    var schedules by remember { mutableStateOf<List<ScheduleOcc>>(emptyList()) }
    var todos by remember { mutableStateOf<List<Todo>>(emptyList()) }
    var stats by remember { mutableStateOf<Map<Long, SupervisionStat>>(emptyMap()) }
    var overviews by remember { mutableStateOf<Map<Long, SupervisionOverview>>(emptyMap()) }
    // v23：逐日账本（已过期列表 + 展开明细 + 补交共用同一份）
    var ledgers by remember { mutableStateOf<Map<Long, List<CheckinDay>>>(emptyMap()) }
    var tags by remember { mutableStateOf<List<CategoryEntity>>(emptyList()) }
    var tagFilter by remember { mutableStateOf<Long?>(null) }
    // v12：多体系标签筛选（null=全部）
    var labelFilter by remember { mutableStateOf<String?>(null) }
    // 全局统一标签（筛选行候选 + 编辑器候选都取它）：
    // 三处来源汇总、统一排序，见 ui/common/LabelStore.kt。
    // 原来这里只并「日程 + 待办」，笔记本上的标签在本页选不到。
    val globalLabels = io.github.m0use001.planability.ui.common.rememberGlobalLabels()
    // v13：待办筛选维度 0 状态 / 1 分类 / 2 标签 /（v28）3 重要性 / 4 紧急性
    var filterDim by remember { mutableIntStateOf(0) }
    var dimMenu by remember { mutableStateOf(false) }
    // v28：重要性/紧急性筛选（null = 不限）；档位 0-4，与编辑器双滑块同一套
    var impFilter by remember { mutableStateOf<Int?>(null) }
    var urgFilter by remember { mutableStateOf<Int?>(null) }
    // v28：标签名 → 色相（行首色条与标签 chip 都用它）
    var labelHues by remember { mutableStateOf<Map<String, Float>>(emptyMap()) }
    // v28：着色模式开关在「我的」（plan.colorByUrgency）
    val planSettings by Graph.settings.snapshot.collectAsState()
    var editingTodo by remember { mutableStateOf<Long?>(null) }
    // v62 平板主从：宽屏右栏当前承载的条目（null = 今天概览；0 = 新建）。
    // 与 editingTodo/editingSchedule 的区别：那两个是**全屏 Dialog** 的入口，手机路径继续用；
    // 宽屏下点行只设 selected，右栏就地编辑，不再盖一层窗口把整页遮住。
    var selectedTodo by remember { mutableStateOf<Long?>(null) }
    var selectedSchedule by remember { mutableStateOf<Long?>(null) }
    // v22：勾选完成后需要手填的打卡数据（time 型不用问，仓库里已自动落库）
    var pendingCheckin by remember { mutableStateOf<PendingCheckin?>(null) }
    var editingSchedule by remember { mutableStateOf<Long?>(null) }
    // 所有「打开编辑器」的入口都走这两个 lambda：宽屏（Expanded）收进右栏就地编辑，
    // 其余宽度仍是原来的全屏 Dialog。集中一处，避免十几处入口各自判宽屏而漏掉某个
    // （漏掉的那一处就会在平板上一按弹出整屏窗口，把两栏盖掉）。
    val wideDetail = sizeClass == io.github.m0use001.planability.core.WindowSizeClass.Expanded
    val openTodoEditor: (Long) -> Unit = { id ->
        if (wideDetail) selectedTodo = id else editingTodo = id
    }
    val openScheduleEditor: (Long) -> Unit = { id ->
        if (wideDetail) selectedSchedule = id else editingSchedule = id
    }
    var showCategories by remember { mutableStateOf(false) }
    // v54.4 打卡面板（监督 + 数据记录的统一查看面；顶栏预算内收进 ⋮ 菜单）
    var showBoard by remember { mutableStateOf(false) }
    // v54.5：从待办编辑器点「查看打卡记录」跳面板详情（null = 只开面板列表）
    var showBoardFocus by remember { mutableStateOf<Long?>(null) }
    // v54.5（2026-11-08 用户）：从编辑器点进面板详情，退出要回到编辑器而不是停在计划页
    var boardReturnToEditor by remember { mutableStateOf<Long?>(null) }
    // v54.4-P2：行长按快菜单（编辑/改期/删除）——删除与改期从「进编辑器翻找」降到两步
    var rowMenuTodo by remember { mutableStateOf<Todo?>(null) }
    var rowMenuSchedule by remember { mutableStateOf<io.github.m0use001.planability.data.ScheduleOcc?>(null) }
    var redateTodo by remember { mutableStateOf<Todo?>(null) }
    var redateSchedule by remember { mutableStateOf<io.github.m0use001.planability.data.Schedule?>(null) }
    var deleteTodoTarget by remember { mutableStateOf<Todo?>(null) }
    var deleteScheduleTarget by remember { mutableStateOf<io.github.m0use001.planability.data.Schedule?>(null) }
    // v54.4-P4：重复项动作的范围（仅此一份 / 此条及以后 / 整个系列）——单次项直通不弹
    var scopeTodo by remember { mutableStateOf<Todo?>(null) }
    var scopeTodoAction by remember { mutableStateOf("") }
    var scopeSchedule by remember { mutableStateOf<io.github.m0use001.planability.data.Schedule?>(null) }
    var scopeScheduleDay by remember { mutableStateOf(0L) }
    var scopeScheduleAction by remember { mutableStateOf("") }
    // 2026-11-01 顶部预算：分类管理收进 ⋮ 后的溢出菜单开关
    var showCatMenu by remember { mutableStateOf(false) }
    // v23：已过期 → 展开明细 → 点某天补交
    var expandedOverdue by remember { mutableStateOf<Long?>(null) }
    var backfillTarget by remember { mutableStateOf<Pair<Todo, CheckinDay>?>(null) }

    // v68（2026-11-21「纸条导航」）：上面 2026-11-01 的「切走就收编辑器/分类管理」
    // watcher 删除。原来收层是因为子页是独立 Dialog 窗口、会浮在新 tab 之上；v68 起
    // PredictiveBackOverlay 页内化，子页随本页纸条一起滑走、跨切页原样保留（页面记忆：
    // 编辑器开一半切去专注，回来接着写）。锁按 tab 记账、返回手势由 LocalTabActive
    // 门控，别的 tab 不再被这页的子页锁住或吃返回。
    val navAvoid = io.github.m0use001.planability.app.navAvoidPadding().calculateBottomPadding()
    // 后台冻结（2026-10-30 用户需求）：进程被杀后重进，恢复上次的筛选状态。
    // 本页常驻组合（pager beyondViewportPageCount），LaunchedEffect(Unit) 生命周期 =
    // 进程生命周期，恢复只在冷启动发生一次。组件深链（pendingPlanSubTab）在场时
    // segment 让位给它——小组件点名要去的段位优先于"上次"。深链标记必须在
    // **组合期**捕获：上面那个深链 effect 排在前面、跑完就把它清空，等本 effect
    // 的挂起读库回来再看已经是 null，会把深链刚设好的段位又覆盖掉。
    val pendingSubAtCompose = Router.pendingPlanSubTab
    // 同上理由：直达编辑器的深链（搜索等入口）在组合期就被下面的 effect 消费掉，
    // 恢复侧必须用组合期的快照判断"这次是深链开的编辑器"，不能等读库回来再看
    val pendingEditorAtCompose =
        Router.pendingTodoId != null || Router.pendingScheduleId != null
    // 恢复完成闸门：必须等恢复读完库，记录 effect 才准开写——否则首组合会把
    // 默认值先写进 KV，恢复读到的就是刚被覆盖的默认（自覆盖竞态）
    var planRestored by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        io.github.m0use001.planability.app.UiFreeze.awaitLoaded()
        if (!io.github.m0use001.planability.app.UiFreeze.enabled) return@LaunchedEffect
        val kv = Graph.settings
        val seg = kv.kvGet("shell.freeze.plan.segment")?.toIntOrNull()
        val rangeS = kv.kvGet("shell.freeze.plan.range")
        val statusS = kv.kvGet("shell.freeze.plan.status")
        val dim = kv.kvGet("shell.freeze.plan.dim")?.toIntOrNull()
        val tag = kv.kvGet("shell.freeze.plan.tag")
        val label = kv.kvGet("shell.freeze.plan.label")
        val imp = kv.kvGet("shell.freeze.plan.imp")
        val urg = kv.kvGet("shell.freeze.plan.urg")
        if (pendingSubAtCompose == null && seg != null && seg in 0..1) segment = seg
        if (rangeS != null && rangeS in listOf("all", "today", "week", "month")) range = rangeS
        if (statusS != null && statusS in listOf("all", "active", "done", "overdue")) statusFilter = statusS
        if (dim != null && dim in 0..4) filterDim = dim
        // 空串 = "全部"（null 档位）；键不存在才保持默认
        if (tag != null) tagFilter = tag.toLongOrNull()
        if (label != null) labelFilter = label.ifEmpty { null }
        if (imp != null) impFilter = imp.toIntOrNull()
        if (urg != null) urgFilter = urg.toIntOrNull()
        // 编辑器层（用户 2026-10-30 实锤：进待办编辑界面退出后只回列表）：
        // 只还原**编辑既有对象**（id>0），新建表单（0L）不还原——空表单调出来没有意义。
        // 两页（计划/首页）都常驻组合、编辑弹窗是独立窗口：只还原与冻结页签匹配的那一个，
        // 否则会出现「计划页上飘着首页的编辑弹窗」。对象已删则安静不恢复。
        if (!pendingEditorAtCompose && kv.kvGet("shell.freeze.tab") == "plan") {
            val editor = kv.kvGet("shell.freeze.editor.plan").orEmpty()
            val kind = editor.substringBefore(':')
            val id = editor.substringAfter(':', "").toLongOrNull() ?: 0L
            if (id > 0L) {
                when (kind) {
                    "todo" -> if (Graph.plan.todoById(id) != null) openTodoEditor(id)
                    "schedule" -> if (Graph.plan.scheduleById(id) != null) openScheduleEditor(id)
                }
            }
        }
        planRestored = true
    }
    // 筛选变化即记（UiFreeze.save 内部判开关、走 appScope 落库）。恢复完成（planRestored）
    // 之前不写，防止首组合把默认值抢先写进 KV、恢复读到刚被覆盖的默认。
    LaunchedEffect(planRestored, segment, range, statusFilter, filterDim, tagFilter, labelFilter, impFilter, urgFilter) {
        if (!planRestored || !io.github.m0use001.planability.app.UiFreeze.enabled) return@LaunchedEffect
        val kv = Graph.settings
        kv.kvPut("shell.freeze.plan.segment", segment.toString())
        kv.kvPut("shell.freeze.plan.range", range)
        kv.kvPut("shell.freeze.plan.status", statusFilter)
        kv.kvPut("shell.freeze.plan.dim", filterDim.toString())
        kv.kvPut("shell.freeze.plan.tag", tagFilter?.toString() ?: "")
        kv.kvPut("shell.freeze.plan.label", labelFilter ?: "")
        kv.kvPut("shell.freeze.plan.imp", impFilter?.toString() ?: "")
        kv.kvPut("shell.freeze.plan.urg", urgFilter?.toString() ?: "")
    }
    // 编辑器层记录：id>0（编辑既有）才记，新建表单（0L）与关闭一律写空串——
    // 空表单调出来没有意义，恢复侧也按"空串=不恢复"处理
    LaunchedEffect(planRestored, editingTodo, editingSchedule) {
        if (!planRestored || !io.github.m0use001.planability.app.UiFreeze.enabled) return@LaunchedEffect
        val v = when {
            (editingTodo ?: 0L) > 0L -> "todo:$editingTodo"
            (editingSchedule ?: 0L) > 0L -> "schedule:$editingSchedule"
            else -> ""
        }
        Graph.settings.kvPut("shell.freeze.editor.plan", v)
    }
    val version by Graph.dataVersion.flow.collectAsState()
    // 「过期 N」角标：筛选 chip 上直接给个数，省得切过去数。
    // 2026-10-30 扩口径：除了监督账本的欠卡，**普通待办日期已过且未完成**的也计入
    // （在下面的 LaunchedEffect 里一并算，两路去重）。
    var overdueCount by remember { mutableStateOf(0) }

    // 2026-11-02 性能：计划页重活整段在 Dispatchers.Default 里算完，一次性带回主线程。
    // 逐个 state 回填会在 Default 线程碰 Compose state（非法），所以打包成一个不可变结果。
    data class PlanData(
        val tags: List<CategoryEntity>,
        val hues: Map<String, Float>,
        val schedules: List<ScheduleOcc>,
        val stats: Map<Long, SupervisionStat>,
        val overviews: Map<Long, SupervisionOverview>,
        val ledgers: Map<Long, List<CheckinDay>>,
        val overdueCount: Int,
        val todos: List<Todo>
    )

    LaunchedEffect(version, range, statusFilter, tagFilter, labelFilter, impFilter, urgFilter) {
        // 2026-11-02 性能（用户「软件用起来很卡」）：本 effect 的 6 个 key 任一变化都会
        // 全量重算——scheduleOccurrences 在「全部」档要展开 730 天的重复实例、
        // activeTodos+过滤+排序+多个监督统计串行跑。LaunchedEffect 在主线程，
        // 这些组合/过滤/排序（suspend 的 DB 读走 IO，但 CPU 段全在主线程）正是
        // 切页 99th=93ms 的来源。整段重活挪到 Default 线程，只在回填 state 时回主线程。
        val r = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            val tagsX = Graph.plan.categories(0)
            val huesX = Graph.plan.labelColors()
            val today = AppTime.today()
            val (from, to) = when (range) {
                "today" -> today to today
                // 「近 N 天」= 过去 N 天 + 未来 N 天（2026-10-30 用户：近7天/近30天看不到待办——
                // 原窗口从今天起算，跨零点后昨天的待办整体出窗；"近"要含最近的过去，
                // 未完成的一次性待办不该因为过了一夜就从所有范围视图里消失）
                "week" -> today.minusDays(6) to today.plusDays(7)
                "month" -> today.minusDays(29) to today.plusDays(30)
                // 2026-11-02 性能：原来 ±365 天（展开 730 天的重复实例是大头），
                // 与下方「已归档只往回看 90 天」的口径也不一致。收到 ±90 天：
                // 一年前的重复实例既没人翻，还会把列表撑爆；90 天是既有滚期口径的同一把尺。
                else -> today.minusDays(90) to today.plusDays(90)
            }
            val rawSchedules = Graph.plan.scheduleOccurrences(from, to)
        // v14：日程侧补上分类和状态两个维度（数据层本来就有 Schedule.tagId，编辑器也能设，
        // 只是一直没在只读侧暴露过）。口径与待办对齐：tagFilter 命中 tagId，0 = 未分类。
        val nowMs = AppTime.nowMillis()
        val schedulesX = rawSchedules.filter { occ ->
            val tg = tagFilter == null || occ.schedule.tagId == tagFilter
            val lf = labelFilter == null ||
                io.github.m0use001.planability.ui.common.parseLabels(occ.schedule.labels).contains(labelFilter)
            val st = when (statusFilter) {
                "active" -> occ.start >= nowMs
                "done" -> occ.start < nowMs
                else -> true
            }
            // v28：重要性/紧急性两维（与编辑器双滑块同一套 0-4 档），null = 不限
            val im = impFilter == null || occ.schedule.importance == impFilter
            val ug = urgFilter == null || occ.schedule.urgency == urgFilter
            tg && lf && st && im && ug
        }
        val all = Graph.plan.activeTodos()
        val supIds = all.filter { it.supervisionEnabled }.map { it.id }.toSet()
        val statsX = if (supIds.isEmpty()) emptyMap()
        else supIds.associateWith { Graph.plan.supervisionStat(it) }
        val overviewsX = if (supIds.isEmpty()) emptyMap()
        else Graph.plan.supervisionOverview().associateBy { it.todoId }
        // v23：账本只给「打卡监督 + 常驻/重复」算 —— 一次性待办过了日子也不该天天挂「欠打卡」
        val ledgerTodos = all.filter {
            it.supervisionEnabled && (it.permanent || it.repeatType != "none")
        }
        val ledgersX = if (ledgerTodos.isEmpty()) emptyMap()
        else Graph.plan.checkinLedgers(ledgerTodos).associate { it.first.id to it.second }

        // 2026-10-29：重复待办勾完被滚到下一期，原行离开当天列表 —— 「今天/已归档」里
        // 要把当日完成流水还原成显示副本（done=true、date=那天），否则打卡完就"消失"。
        // 只并入当前范围里的那些天；副本 id 与原行相同，所以列表 key 要带 date。
        // 「全部」只往回看 90 天：再往前每天一条副本会把列表撑爆，也不是"今天视图"要回答的问题。
        val rolledFrom = if (range == "all") today.minusDays(90) else from
        val rolledViews = Graph.plan.doneOccurrences(AppTime.dayKey(rolledFrom), AppTime.dayKey(to))
        // 过期口径（2026-10-30 扩）：①监督账本欠卡（原有）②**普通待办日期已过且未完成**——
        // 与账本"今天还没过完不算欠"同口径，当天的不算；完成的不算；未排日期（date=0）不算。
        // 两路按 id 去重后给角标计数。
        val todayKey = AppTime.dayKey(today)
        val plainOverdueIds = (all + rolledViews)
            .filter { it.date > 0 && it.date < todayKey && !it.done }
            .map { it.id }.toSet()
        val ledgerOverdueIds = ledgersX.filterValues { days -> days.any { it.state == CheckinDay.MISSED } }.keys
        val overdueX = (plainOverdueIds + ledgerOverdueIds).size
        // 2026-11-03 用户实测「设了每天的背单词，今天-全部视图看不到」：本页「今天」范围
        // 此前只认 date==今天 / 永久 / 无日期，漏了**过期未勾、今天仍命中相位的重复待办**
        // （单行滚期漏勾的欠账）——与首页 loadDay 是同一批洞。补今日欠账副本（date=今天，
        // 其余字段不动、id 保留），**只在「今天」范围并入**：近 7/30 天/全部范围里原行
        // 按旧日期已可见，再并副本就是双显。
        val dayOverdueViews = if (range == "today") {
            all.filter { needsTodayOverdueCopy(it, todayKey) }
                .mapNotNull { t ->
                    val excluded = t.excludedDates.split(",")
                        .mapNotNull { it.trim().toLongOrNull() }.toSet()
                    io.github.m0use001.planability.data.overdueDayView(t, today, todayKey, emptySet(), excluded)
                }
        } else emptyList()
        val todosX = (all + rolledViews + dayOverdueViews).filter { t ->
            val inRange = when (range) {
                "today" -> t.date == todayKey || (t.date == 0L) || (t.permanent && !permanentWeeklyOffToday(t, today))
                // 与上面 (from, to) 同源：近 N 天 = 过去 N 天 + 未来 N 天
                "week" -> t.date in AppTime.dayKey(from)..AppTime.dayKey(to) || t.permanent || (t.date == 0L)
                "month" -> t.date in AppTime.dayKey(from)..AppTime.dayKey(to) || t.permanent || (t.date == 0L)
                else -> true
            }
            val st = when (statusFilter) {
                // v53：循环待办「今天」那份即使已完成也算进行中（勾完不该从今天消失，
                // 见 TodoListFilters 注释）。一次性待办仍是勾完即归档。
                "active" -> io.github.m0use001.planability.data.TodoListFilters.inActiveList(t, todayKey, range)
                "done" -> t.done
                // 过期 = 欠打卡（监督）或日期已过未完成（普通）；完成的不用再提醒
                "overdue" -> (t.date > 0 && t.date < todayKey && !t.done) ||
                    (ledgersX[t.id] ?: emptyList()).any { it.state == CheckinDay.MISSED }
                else -> true
            }
            // 范围筛选对「已过期」没意义（欠的是过去的日子），只保留分类/标签筛选
            val rangeOk = if (statusFilter == "overdue") true else inRange
            val tg = tagFilter == null || t.tagId == tagFilter
            val lf = labelFilter == null || io.github.m0use001.planability.ui.common.parseLabels(t.labels).contains(labelFilter)
            val im = impFilter == null || t.importance == impFilter
            val ug = urgFilter == null || t.urgency == urgFilter
            rangeOk && st && tg && lf && im && ug
        }.let { list ->
            // 已过期排前面：先按「欠打卡天数」，普通过期的按日期越久越靠前
            if (statusFilter == "overdue") list.sortedWith(
                compareByDescending<Todo> { CheckinLedger.missedCount(ledgersX[it.id] ?: emptyList()) }
                    .thenBy { it.date }
            ) else list
        }
            // 结果打包回主线程（Default 线程里不碰 Compose state）
            PlanData(tagsX, huesX, schedulesX, statsX, overviewsX, ledgersX, overdueX, todosX)
        }
        // 主线程：只做 state 赋值
        tags = r.tags
        labelHues = r.hues
        schedules = r.schedules
        stats = r.stats
        overviews = r.overviews
        ledgers = r.ledgers
        overdueCount = r.overdueCount
        todos = r.todos
    }

    // Router：全局搜索等入口直达编辑器
    // 键必须是标志本身，不能是 Unit：NavShell 用 beyondViewportPageCount = tabs.size 让各页常驻组合，
    // LaunchedEffect(Unit) 早在首帧就跑完了，之后标志翻转不会重跑 —— 点开只会切 tab、编辑器不开
    // （HomeScreen 倒数日、InfoCenterScreen 手机操控区域是同一个坑）。
    LaunchedEffect(Router.pendingTodoId) {
        if (Router.pendingTodoId != null) {
            segment = 1
            openTodoEditor(Router.pendingTodoId!!)
            Router.pendingTodoId = null
        }
    }
    LaunchedEffect(Router.pendingScheduleId) {
        if (Router.pendingScheduleId != null) {
            segment = 0
            openScheduleEditor(Router.pendingScheduleId!!)
            Router.pendingScheduleId = null
        }
    }

    // 2026-11-01 顶部预算（meta-ai 方案 §六）：顶栏 actions 固定 1 枚 ⋮，
    // 分类管理是低频设置，收进溢出菜单——不再单独占一枚常驻图标。
    // 差距 14：不传 tint 会落到 LocalContentColor=onSurface（近黑），与全站图标不一致。
    // v62：从 M3ETitleBar 的 actions 里提出来——宽屏板头与手机标题栏共用同一个菜单。
    val planOverflowMenu: @Composable () -> Unit = {
        Box {
            IconButton(onClick = { showCatMenu = true; haptics.perform(HapticEngine.Kind.Tap) }) {
                Icon(
                    Icons.Rounded.MoreVert,
                    contentDescription = "更多",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            androidx.compose.material3.DropdownMenu(
                expanded = showCatMenu,
                onDismissRequest = { showCatMenu = false }
            ) {
                androidx.compose.material3.DropdownMenuItem(
                    modifier = Modifier.clip(RoundedCornerShape(10.dp)), // 2026-11-15 点击高亮圆角化（原来是整行直角方块）
                    text = { Text("打卡面板") },
                    onClick = {
                        showCatMenu = false
                        haptics.perform(HapticEngine.Kind.Select)
                        showBoard = true
                    }
                )
                androidx.compose.material3.DropdownMenuItem(
                    modifier = Modifier.clip(RoundedCornerShape(10.dp)), // 2026-11-15 点击高亮圆角化（原来是整行直角方块）
                    text = { Text("分类管理") },
                    onClick = {
                        showCatMenu = false
                        haptics.perform(HapticEngine.Kind.Select)
                        showCategories = true
                    }
                )
            }
        }
    }

    // 2026-10-24 用户指令：计划页「取消下拉标题增大动画，但保留圆角标题栏」。
    // 因此不再接 collapseScrollConnection、不保留展开区（M3ETitleBarExtra 行程为 0），
    // 标题栏恒定在固定态：64dp 圆角浮层 + 小标题，内容照旧从圆角下滚过。
    // 2026-10-30 用户指令：控制行**表头冻结**——分段/范围/维度筛选不再作为 LazyColumn
    // 头条目随内容滚动，而是固定钉在标题栏下方（见下面 Box 里的重组），列表区单独滚。
    val planControls: @Composable () -> Unit = {
        // 分段：日程 | 待办（M3E connected button group，替代已弃用的 SegmentedButton；
        // 触感由 AppButtonGroup 内置 Select，调用点不再补）
        // 横向 16dp 由 LazyColumn 的 contentPadding 供给（控制行是列表头部件）。
        // 2026-10-24：分类管理图标上移到标题栏动作区——它要跟大标题一起响应折叠缩放
        io.github.m0use001.planability.ui.common.AppButtonGroup(
            options = listOf("日程", "待办"),
            selectedIndex = segment,
            onSelect = { segment = it },
            // 动画进度从 key(segment) 外面传进来，重建不打断滑动（见 segmentSlide 注释）
            animatedIndex = segmentSlide.value,
            // 行间距显式给：钉矮后 48dp 触控目标自带的留白没了，三行会贴死（用户 2026-10-25）
            // v59 平板：分段按钮收成 260dp（两段各 ~114dp），不再每段 420dp
            modifier = (if (wideControls) Modifier.widthIn(max = 260.dp) else Modifier)
                .padding(horizontal = 16.dp).padding(bottom = 8.dp).fillMaxWidth()
        )
            // 范围：四项等分占满整行（用户 2026-10-25：筛选行没有占满屏幕）。
            // v59 平板：整行收成 480dp（每项 ~110dp），手机仍是占满整行。
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = (if (wideControls) Modifier.widthIn(max = 480.dp) else Modifier)
                    .fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 8.dp)
            ) {
                listOf("all" to "全部", "today" to "今天", "week" to "近7天", "month" to "近30天").forEach { (k, label) ->
                    FilterChip(
                        colors = filterChipColors(),
                        selected = range == k,
                        onClick = { range = k; haptics.perform(HapticEngine.Kind.Select) },
                        // 等宽后内容居中（默认 Start 会贴在每格左边，四格看起来参差）
                        horizontalArrangement = Arrangement.Center,
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp),
                        label = { Text(label, maxLines = 1) },
                        modifier = Modifier.weight(1f).height(32.dp).pressScale()
                    )
                }
            }
            // 2026-10-25 用户「时间范围与状态分的太开、要 2 行内显示完」：
            // 选项 ≤5 时等分整行（没有空洞）；更多选项（分类/标签/重要性/紧急性）保持自然宽度
            // 换行，不压文字。行距从 8dp 收到 4dp，两行紧挨着。
            val dimCount = when (filterDim) {
                0 -> if (segment == 1) 5 else 4
                1 -> 3 + tags.size
                2 -> 2 + globalLabels.names.size
                else -> 8
            }
            androidx.compose.runtime.CompositionLocalProvider(
                LocalFilterFill provides (dimCount <= 5)
            ) {
            androidx.compose.foundation.layout.FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 10.dp)
            ) {
                // v14：维度按钮对日程和待办都开放（用户要求「日程与待办都要加入分类维度选择」）。
                // 时间范围仍然是上面独立那一行，不动。
                Box {
                    // 维度按钮 = 下拉触发器：用**中性 tonal**（surfaceContainerHighest）与
                    // 「选中值」的 periwinkle 容器分层（审查差距 13：原来 selected=true，
                    // 和「进行中」同屏两个"已选中"外观）。它不是选中态，所以 selected=false。
                    FilterChip(
                        colors = FilterChipDefaults.filterChipColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                            labelColor = MaterialTheme.colorScheme.onSurface,
                            selectedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                            selectedLabelColor = MaterialTheme.colorScheme.onSurface
                        ),
                        selected = false,
                        // 打开下拉是中性点击不是"选中"，用 Tap（Select 留给真正改变筛选值的那些 chip）
                        onClick = { dimMenu = true; haptics.perform(HapticEngine.Kind.Tap) },
                        horizontalArrangement = Arrangement.Center,
                        label = { Text(FILTER_DIMS[filterDim] + " ▾", maxLines = 1) },
                        modifier = Modifier.height(32.dp).pressScale()
                    )
                    androidx.compose.material3.DropdownMenu(
                        expanded = dimMenu,
                        onDismissRequest = { dimMenu = false }
                    ) {
                        FILTER_DIMS.forEachIndexed { i, name ->
                            androidx.compose.material3.DropdownMenuItem(
                                modifier = Modifier.clip(RoundedCornerShape(10.dp)), // 2026-11-15 点击高亮圆角化（原来是整行直角方块）
                                text = { Text(name) },
                                onClick = {
                                    filterDim = i
                                    dimMenu = false
                                    // v28 修：换维度就把「新维度看不见的筛选」清掉。
                                    // 否则切回「状态」后列表还按隐藏的紧急性档位/分类/标签过滤，
                                    // 界面上一个提示都没有，用户只看到「条目凭空少了」——
                                    // 真机验收时在这一步栽过：紧急性=偏低 切回状态，6 条只剩 1 条。
                                    // 每个维度只拥有自己的筛选，切走即失效。
                                    if (i != 1) tagFilter = null
                                    if (i != 2) labelFilter = null
                                    if (i != 3) impFilter = null
                                    if (i != 4) urgFilter = null
                                    haptics.perform(HapticEngine.Kind.Select)
                                }
                            )
                        }
                    }
                }
                when (filterDim) {
                    0 -> {
                        // 日程没有「已归档/欠打卡」这类待办专属状态，只给两个真存在的
                        if (segment == 1) {
                            FillFilterChip(
                                selected = statusFilter == "active",
                                onClick = { statusFilter = "active"; haptics.perform(HapticEngine.Kind.Select) },
                                label = { Text("进行中") }
                            )
                            // v23：用户口径 —— 已完成叫「已归档」（完成是不可逆的既成事实，不是状态名）
                            FillFilterChip(
                                selected = statusFilter == "done",
                                onClick = { statusFilter = "done"; haptics.perform(HapticEngine.Kind.Select) },
                                label = { Text("已归档") }
                            )
                            // v23：长期待办缺了打卡的日子进这里（只算打卡监督的常驻/重复待办）
                            FillFilterChip(
                                selected = statusFilter == "overdue",
                                onClick = { statusFilter = "overdue"; haptics.perform(HapticEngine.Kind.Select) },
                                label = {
                                    Text(if (overdueCount > 0) "过期 $overdueCount" else "过期")
                                }
                            )
                            FillFilterChip(
                                selected = statusFilter == "all",
                                onClick = { statusFilter = "all"; haptics.perform(HapticEngine.Kind.Select) },
                                label = { Text("全部") }
                            )
                        } else {
                            // 日程只有「今天 / 以后」这一层状态差别：过去的日程翻篇即忘，
                            // 不像待办有归档和欠打卡。给这两个就够，别硬凑。
                            FillFilterChip(
                                selected = statusFilter == "active",
                                onClick = { statusFilter = "active"; haptics.perform(HapticEngine.Kind.Select) },
                                label = { Text("未开始") }
                            )
                            FillFilterChip(
                                selected = statusFilter == "done",
                                onClick = { statusFilter = "done"; haptics.perform(HapticEngine.Kind.Select) },
                                label = { Text("已过去") }
                            )
                            FillFilterChip(
                                selected = statusFilter == "all",
                                onClick = { statusFilter = "all"; haptics.perform(HapticEngine.Kind.Select) },
                                label = { Text("全部") }
                            )
                        }
                    }
                    1 -> {
                        FillFilterChip(
                            selected = tagFilter == null,
                            onClick = { tagFilter = null; haptics.perform(HapticEngine.Kind.Select) },
                            label = { Text("全部") }
                        )
                        // v13：全部分类都列出（原来只显示前 3 个）
                        tags.forEach { t ->
                            FillFilterChip(
                                selected = tagFilter == t.id,
                                onClick = { tagFilter = if (tagFilter == t.id) null else t.id; haptics.perform(HapticEngine.Kind.Select) },
                                label = { Text(t.name) }
                            )
                        }
                        FillFilterChip(
                            selected = tagFilter == 0L,
                            onClick = { tagFilter = if (tagFilter == 0L) null else 0L; haptics.perform(HapticEngine.Kind.Select) },
                            label = { Text("未分类") }
                        )
                    }
                    2 -> {
                        FillFilterChip(
                            selected = labelFilter == null,
                            onClick = { labelFilter = null; haptics.perform(HapticEngine.Kind.Select) },
                            label = { Text("全部") }
                        )
                        globalLabels.names.forEach { lb ->
                            FillFilterChip(
                                selected = labelFilter == lb,
                                onClick = { labelFilter = if (labelFilter == lb) null else lb; haptics.perform(HapticEngine.Kind.Select) },
                                label = { Text(lb) }
                            )
                        }
                    }
                    // v28：需求 5 —— 按重要性/紧急性分类显示。同一行内 5 个档位 chip，
                    // 再点一次选中的档位 = 取消（与分类/标签筛选同口径：筛不是开关的"套娃"）
                    3, 4 -> {
                        val isImp = filterDim == 3
                        val current = if (isImp) impFilter else urgFilter
                        FillFilterChip(
                            selected = current == null,
                            onClick = { if (isImp) impFilter = null else urgFilter = null; haptics.perform(HapticEngine.Kind.Select) },
                            label = { Text("全部") }
                        )
                        // 2026-10-20：未设置（-1）也是一个筛选档
                        FillFilterChip(
                            selected = current == -1,
                            onClick = {
                                val next = if (current == -1) null else -1
                                if (isImp) impFilter = next else urgFilter = next
                                haptics.perform(HapticEngine.Kind.Select)
                            },
                            label = { Text("未设置") }
                        )
                        (0..4).forEach { lv ->
                            FillFilterChip(
                                selected = current == lv,
                                onClick = {
                                    val next = if (current == lv) null else lv
                                    if (isImp) impFilter = next else urgFilter = next
                                    haptics.perform(HapticEngine.Kind.Select)
                                },
                                label = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Box(
                                            Modifier.size(7.dp).background(
                                                priorityColor(io.github.m0use001.planability.data.PriorityMap.priorityOf(lv)),
                                                CircleShape
                                            )
                                        )
                                        Spacer(Modifier.width(4.dp))
                                        Text(io.github.m0use001.planability.data.PriorityMap.labelOf(lv))
                                    }
                                }
                            )
                        }
                    }
                }
            }
            }
    }

        // 日程/待办是同一页的两套**同级**列表，控制行（分段/范围/状态筛选）是两条列表的共享头条目。
        // 原来用 AnimatedContent 做方向性横移：控制行在两份内容里各有一份 → 切换瞬间屏幕上同时
        // 出现两套「日程/待办 + 筛选 chips」互相错位叠加（用户 2026-10-25 报「切换有问题」正是这个）。
        // 改成**单条 LazyColumn 原地换内容**：控制行只有一份、永远不会重复；行的进出交给每行自己的
        // animateItem() 入场/退场，屏幕上不会再出现两层内容叠加的帧。
        // 两个子页各自保留一份 LazyListState，切回来时回到原来的位置。
        // v58 平板：列表与控制行收进 840dp 居中（手机天然 no-op）——整宽行在 1280dp 下拉稀
        // ── v62 平板主从 ──────────────────────────────────────────────────────
        // 形态由纯函数决定（PlanWideLayout），页面里不再散落 if (wide)：
        //   Expanded(≥840) → 左板列表 + 右板（今天概览 / 选中条目的内联编辑器）
        //   其余宽度      → 原来的单列，一行都不用改（手机布局零变化）
        // 选中态按段各自保留：切到另一段再切回来，右栏还停在上次那一条——
        // 与两条列表各自记住滚动位置同一套语义。**不在切段时清空**：Router 深链
        // 会先设 segment 再设选中，清空会把刚从外部点进来的那条立刻抹掉。
        val selectedId = if (segment == 0) selectedSchedule else selectedTodo
        val pane = PlanWideLayout.paneOf(sizeClass, selectedId != null)
        val twoPane = pane != PlanPane.Single
        // v70d（2026-11-21 用户拍板「方向错了…」）：两板从 SplitBoards 换成 BoardDrill——
        // 点列表条目不再是右板原地换内容，而是整条纸条三向推挤：今日概览划出屏幕右缘、
        // 计划列表从主框滑进副框（宽度连续改排版）、编辑器从左缘滑进主框；返回集体左移，
        // 系统预测返回跟手拖纸条（「我的」页 v68 同款）。
        // 返回由 BoardDrill 自带的 PredictiveBackHandler 接管，本页原来的 BackHandler 撤。
        val drillKey = if (segment == 0) {
            selectedSchedule?.let { PlanDrillKey(0, it) }
        } else {
            selectedTodo?.let { PlanDrillKey(1, it) }
        }
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        // 列表板内容抽成局部 lambda（BoxScope：FAB/标题栏用 align 定位）：
        // 两板形态进 BoardDrill 的 main 瓦片，单列原样直出。
        val planBoard: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit = {
        // 2026-11-24 用户「宽屏内容莫名比框小很多、右边一大片空」：两板时主列表就是「主瓦片」
        // 本身，应撑满主瓦片宽度（内容 = 框），不再被 adaptiveColumn(760) 封顶后左贴一大片空；
        // 单列（手机/Medium 平板）仍保留 760dp 可读性上限。
        Column(
            Modifier.fillMaxHeight().then(
                if (twoPane) Modifier.fillMaxWidth() else Modifier.adaptiveColumn(maxWidth = 760.dp)
            )
        ) {
        Box(Modifier.weight(1f)) {
            val schedState = androidx.compose.foundation.lazy.rememberLazyListState()
            val todoState = androidx.compose.foundation.lazy.rememberLazyListState()
            // 再点一次「计划」图标 → 当前子页回到顶部
            io.github.m0use001.planability.app.ScrollToTopOnReselect(
                "plan", if (segment == 0) schedState else todoState
            )
            // 2026-10-30 表头冻结：固定表头（控制行）+ 列表区两段式。控制行不再随内容滚动，
            // 列表在自己的视口里滚，内容到表头下缘直接裁掉（表头底下就是页面底色，无需遮罩）。
            // headerClear = 标题栏浮层占位（状态栏 + 64dp 圆角栏 + 8dp 缝），与旧 contentPadding 同值。
            val headerClear = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() +
                io.github.m0use001.planability.ui.common.M3ETitleBarPinnedH + 8.dp
            Column(Modifier.fillMaxSize()) {
                if (twoPane) {
                    // 板头：标题 + ⋮ 直接长在板子底面上，不再是压在内容上的一层浮起圆角栏
                    // （那层 28dp 圆角 + 3dp 阴影是宽屏「内容块割裂」的头号来源）。
                    io.github.m0use001.planability.ui.common.BoardHeader(title = "计划", actions = { planOverflowMenu() })
                } else {
                    Spacer(Modifier.height(headerClear))
                }
                planControls()
                if (twoPane) {
                    // 表头与列表之间的分界：板内的「分组列表」语义——一行细线，而不是再插一块卡片。
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
                }
                // key(segment)：日程⇄待办时换一棵 LazyColumn。只换数据还不够——行带 key +
                // Modifier.animateItem()，旧行会做退场淡化，和新内容同屏叠一瞬（用户 2026-10-25
                // 「横切界面切换时重叠」就是这个）。换树后旧列表整棵消失，屏幕上永远只有一套内容。
                // LazyListState 在外面，切回来仍记得各自滚动位置。
                androidx.compose.runtime.key(segment) {
                LazyColumn(
                    Modifier.fillMaxSize()
                        .reachedEndHaptic(if (segment == 0) schedState else todoState),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        top = 2.dp,
                        // 宽屏：行是扁平的分组列表行，左右不留内缩（与板头/chip 的 16dp 对齐，
                        // 行内自己给 16dp）；分隔线由 BoardRowDivider 缩进 14dp。
                        // 手机仍是 16dp 的卡片行口径。
                        start = if (twoPane) 0.dp else 16.dp,
                        end = if (twoPane) 0.dp else 16.dp,
                        // 宽屏 FAB 在板内右下角，列表尾部要让出它的高度，否则最后一行被按钮压住。
                        bottom = navAvoid + if (twoPane) 88.dp else 0.dp
                    ),
                    state = if (segment == 0) schedState else todoState
                ) {
                if (segment == 0) {
                    if (range == "all") {
                            // 「全部」按**规则**渲染：一条重复日程只占一行，副标题显示重复频率。
                            // 为什么只在这个视图这么做：「今天/近7天」要的是"哪天哪节"，
                            // 按规则渲染会让同一门课每天都出现（或只出现一次），两种都不对。
                            val rules = schedules.groupBy { it.schedule.id }.values
                                .map { occs -> occs.minByOrNull { it.start }!! }
                                .sortedWith(
                                    compareBy(
                                        { it.start.toLocalDate().dayOfWeek.value },
                                        { AppTime.millisToTime(it.start) }
                                    )
                                )
                            if (rules.isEmpty()) {
                                item { EmptyHint("这段时间还没有日程\n点右下角 + 新建") }
                            }
                            itemsIndexed(rules, key = { _, occ -> "rule-" + occ.schedule.id }) { idx, occ ->
                                androidx.compose.foundation.layout.Box(Modifier.animateItem()) {
                                  Column {
                                    ScheduleRow(
                                        occ, tags,
                                        labelHues = labelHues,
                                        labelTints = globalLabels.tints,
                                        byUrgencyImportance = planSettings.planColorByUrgencyImportance,
                                        ruleView = true,
                                        flat = twoPane,
                                        selected = twoPane && selectedSchedule == occ.schedule.id,
                                        onLongPress = { rowMenuSchedule = occ }
                                    ) { openScheduleEditor(occ.schedule.id) }
                                    if (twoPane && idx < rules.lastIndex) io.github.m0use001.planability.ui.common.BoardRowDivider()
                                  }
                                    PlanRowMenu(
                                        expanded = rowMenuSchedule?.let {
                                            it.schedule.id == occ.schedule.id && it.start == occ.start
                                        } == true,
                                        onDismiss = { rowMenuSchedule = null },
                                        onEdit = {
                                            if (io.github.m0use001.planability.data.Recurrence.parseScheduleRule(occ.schedule.repeatRule).repeats) {
                                                scopeSchedule = occ.schedule; scopeScheduleDay = occ.start; scopeScheduleAction = "edit"
                                            } else openScheduleEditor(occ.schedule.id)
                                        },
                                        onRedate = {
                                            if (io.github.m0use001.planability.data.Recurrence.parseScheduleRule(occ.schedule.repeatRule).repeats) {
                                                scopeSchedule = occ.schedule; scopeScheduleDay = occ.start; scopeScheduleAction = "redate"
                                            } else redateSchedule = occ.schedule
                                        },
                                        onDelete = {
                                            if (io.github.m0use001.planability.data.Recurrence.parseScheduleRule(occ.schedule.repeatRule).repeats) {
                                                scopeSchedule = occ.schedule; scopeScheduleDay = occ.start; scopeScheduleAction = "delete"
                                            } else deleteScheduleTarget = occ.schedule
                                        },
                                        onCopy = {
                                            // 复制成本次的单条日程（重复系列不整串复制），时间沿用这次出现
                                            haptics.perform(HapticEngine.Kind.Select)
                                            scope.launch {
                                                Graph.plan.saveSchedule(
                                                    occ.schedule.copy(
                                                        id = 0L,
                                                        start = occ.start,
                                                        end = occ.end,
                                                        repeatRule = "",
                                                        excludedDates = ""
                                                    )
                                                )
                                            }
                                        }
                                    )
                                }
                            }
                        } else {
                            if (schedules.isEmpty()) {
                                item { EmptyHint("这段时间还没有日程\n点右下角 + 新建") }
                            }
                            itemsIndexed(schedules, key = { _, occ -> occ.schedule.id to occ.start }) { idx, occ ->
                                androidx.compose.foundation.layout.Box(Modifier.animateItem()) {
                                  Column {
                                    ScheduleRow(
                                        occ, tags,
                                        labelHues = labelHues,
                                        labelTints = globalLabels.tints,
                                        byUrgencyImportance = planSettings.planColorByUrgencyImportance,
                                        flat = twoPane,
                                        selected = twoPane && selectedSchedule == occ.schedule.id,
                                        onLongPress = { rowMenuSchedule = occ }
                                    ) { openScheduleEditor(occ.schedule.id) }
                                    if (twoPane && idx < schedules.lastIndex) io.github.m0use001.planability.ui.common.BoardRowDivider()
                                  }
                                    PlanRowMenu(
                                        expanded = rowMenuSchedule?.let {
                                            it.schedule.id == occ.schedule.id && it.start == occ.start
                                        } == true,
                                        onDismiss = { rowMenuSchedule = null },
                                        onEdit = {
                                            if (io.github.m0use001.planability.data.Recurrence.parseScheduleRule(occ.schedule.repeatRule).repeats) {
                                                scopeSchedule = occ.schedule; scopeScheduleDay = occ.start; scopeScheduleAction = "edit"
                                            } else openScheduleEditor(occ.schedule.id)
                                        },
                                        onRedate = {
                                            if (io.github.m0use001.planability.data.Recurrence.parseScheduleRule(occ.schedule.repeatRule).repeats) {
                                                scopeSchedule = occ.schedule; scopeScheduleDay = occ.start; scopeScheduleAction = "redate"
                                            } else redateSchedule = occ.schedule
                                        },
                                        onDelete = {
                                            if (io.github.m0use001.planability.data.Recurrence.parseScheduleRule(occ.schedule.repeatRule).repeats) {
                                                scopeSchedule = occ.schedule; scopeScheduleDay = occ.start; scopeScheduleAction = "delete"
                                            } else deleteScheduleTarget = occ.schedule
                                        },
                                        onCopy = {
                                            // 复制成本次的单条日程（重复系列不整串复制），时间沿用这次出现
                                            haptics.perform(HapticEngine.Kind.Select)
                                            scope.launch {
                                                Graph.plan.saveSchedule(
                                                    occ.schedule.copy(
                                                        id = 0L,
                                                        start = occ.start,
                                                        end = occ.end,
                                                        repeatRule = "",
                                                        excludedDates = ""
                                                    )
                                                )
                                            }
                                        }
                                    )
                                }
                            }
                        }
                        item { Spacer(Modifier.height(navAvoid)) }
                } else {
                        if (todos.isEmpty()) {
                            item {
                                EmptyHint(
                                    if (statusFilter == "overdue") "没有过期的待办\n欠打卡和过期未完成的都清完了"
                                    else "没有符合条件的待办\n点右下角 + 新建"
                                )
                            }
                        }
                        itemsIndexed(todos, key = { _, todo -> todo.id to todo.date }) { idx, todo ->
                            androidx.compose.foundation.layout.Box(Modifier.animateItem()) {
                            Column {
                            TodoRow(
                                todo = todo,
                                stat = if (todo.supervisionEnabled) stats[todo.id] else null,
                                overview = if (todo.supervisionEnabled) overviews[todo.id] else null,
                                ledger = ledgers[todo.id],
                                expanded = statusFilter == "overdue" && expandedOverdue == todo.id,
                                tags = tags,
                                labelHues = labelHues,
                                labelTints = globalLabels.tints,
                                byUrgencyImportance = planSettings.planColorByUrgencyImportance,
                                onToggleExpand = {
                                    expandedOverdue = if (expandedOverdue == todo.id) null else todo.id
                                },
                                onPickDay = { d -> backfillTarget = todo to d },
                                onLongPress = { rowMenuTodo = todo },
                                onCheck = {
                                    scope.launch {
                                        val wasDone = todo.done
                                        val today = AppTime.today()
                                        // v54.4-P2：勾选记在**这份实例自己的日子**——过去日期的行
                                        // （一次性过期待办 / 没滚走的欠账行）勾了记回那天：完成流水、
                                        // 监督卡（迟交按 at 自动算补交分）、数据记录都落在那天；
                                        // 未来日期（已滚到明天的行）与无日期仍记今天。
                                        // 滚期目标由 nextRollTarget 夹 ≥今天，补昨天的账不会吞今天的实例。
                                        val onDay = if (todo.date > 0 && todo.date < AppTime.dayKey(today))
                                            todo.date.toLocalDate() else today
                                        Graph.plan.completeTodo(todo, onDay)
                                        // 只有「这次真的打了卡」且格式需要手填才问；
                                        // 取消勾选、或 time 型（已自动落库）都不打断
                                        if (!wasDone && todo.dataRecordEnabled &&
                                            CheckinKind.needsInput(todo.dataRecordKind)
                                        ) {
                                            pendingCheckin = PendingCheckin(
                                                todo,
                                                CheckinFormat.blank(todo, AppTime.dayKey(onDay), AppTime.nowMillis())
                                            )
                                        }
                                    }
                                },
                                flat = twoPane,
                                selected = twoPane && selectedTodo == todo.id,
                                onClick = {
                                    openTodoEditor(todo.id)
                                }
                            )
                            if (twoPane && idx < todos.lastIndex) io.github.m0use001.planability.ui.common.BoardRowDivider()
                            }
                            PlanRowMenu(
                                expanded = rowMenuTodo?.let { it.id == todo.id && it.date == todo.date } == true,
                                onDismiss = { rowMenuTodo = null },
                                onEdit = {
                                    // v54.4-P4：重复项先问范围（仅此一份拆单次 / 整个系列）；单次直通
                                    if (todo.repeatType != "none" && todo.date > 0) { scopeTodo = todo; scopeTodoAction = "edit" }
                                    else openTodoEditor(todo.id)
                                },
                                onRedate = {
                                    if (todo.repeatType != "none" && todo.date > 0) { scopeTodo = todo; scopeTodoAction = "redate" }
                                    else redateTodo = todo
                                },
                                onDelete = {
                                    if (todo.repeatType != "none" && todo.date > 0) { scopeTodo = todo; scopeTodoAction = "delete" }
                                    else deleteTodoTarget = todo
                                },
                                onCopy = {
                                    // 复制成一条新的单次待办（清空完成态与重复规则），日期/时段沿用
                                    haptics.perform(HapticEngine.Kind.Select)
                                    scope.launch {
                                        Graph.plan.saveTodo(
                                            todo.copy(
                                                id = 0L,
                                                done = false,
                                                doneAt = 0,
                                                // 启用状态判断走 TodoProgress 单一出处（SingleSourceTest 钉）
                                                percentDone = if (
                                                    io.github.m0use001.planability.data.TodoProgress.isEnabled(todo.percentDone)
                                                ) 0 else -1,
                                                repeatType = "none",
                                                repeatDays = "",
                                                repeatInterval = 0,
                                                repeatEnd = 0
                                            )
                                        )
                                    }
                                }
                            )
                            }
                        }
                        item { Spacer(Modifier.height(navAvoid)) }
                }
                }
                }
            }
            // 标题栏压在列表上层（最后声明 = 最上层）：列表内容从圆角下滚过；
            // 控制行已表头冻结，不再钻圆角（2026-10-30）
            // v62：两栏时板头已经承担了标题与 ⋮，这里不再叠一层浮起的 28dp 圆角栏——
            // 那一层（带 3dp 阴影、只盖住左列宽度）正是宽屏「内容块割裂」的头号来源。
            if (!twoPane) {
            io.github.m0use001.planability.ui.common.M3ETitleBar(
                title = "计划",
                // 固定态：没有展开区，副标题（条数统计）无处可放——用户 2026-10-24 拍板
                // 「取消下拉标题增大动画，保留圆角标题栏」，条数信息一并让位。
                collapsedFraction = 1f,
                expandable = false,
                modifier = Modifier.align(Alignment.TopCenter).widthIn(max = 840.dp),
                actions = { planOverflowMenu() }
            )
            }
            }
        }   // 关列表列（FAB 归左栏 Box，用它的 BoxScope.align 摆到底部）
        // B3-B：主 FAB（56dp 标准档，2026-10-24 回退）
        // v60：挪进左栏容器——宽屏下右栏占了 380dp，FAB 留在整窗正中会飘到列表右边去。
        // v62：两栏时贴左板右下角；单列仍是底边居中（手机口径不变）。
        MediumFab(
            onClick = {
                haptics.perform(HapticEngine.Kind.Tap)
                if (segment == 0) openScheduleEditor(0L) else openTodoEditor(0L)
            },
            modifier = if (twoPane) {
                Modifier.align(Alignment.BottomEnd).padding(end = 20.dp, bottom = 20.dp)
            } else {
                Modifier.align(Alignment.BottomCenter).padding(bottom = navAvoid + 10.dp)
            }
        ) { Icon(Icons.Rounded.Add, contentDescription = "新建") }
        }
        // ^ 关 planBoard。两板形态 = BoardDrill 纸条钻入（v70d）；单列 = 原单板路径原样直出。
        if (twoPane) {
            BoardDrill(
                detailKey = drillKey,
                onBack = {
                    selectedTodo = null
                    selectedSchedule = null
                },
                detailWidth = PlanWideLayout.DetailWidth,
                detail = { k ->
                    if (k.segment == 0) {
                        io.github.m0use001.planability.ui.edit.ScheduleEditScreen(
                            scheduleId = k.id,
                            presetDate = null,
                            onDone = { selectedSchedule = null },
                            embedded = true
                        )
                    } else {
                        io.github.m0use001.planability.ui.edit.TodoEditScreen(
                            todoId = k.id,
                            presetDate = null,
                            onDone = { selectedTodo = null },
                            // 打卡面板是全屏二级页：先收回纸条，退出时再按 openTodoEditor 还原
                            onOpenCheckin = { tid ->
                                selectedTodo = null
                                boardReturnToEditor = tid
                                showBoardFocus = tid
                                showBoard = true
                            },
                            embedded = true
                        )
                    }
                },
                main = { androidx.compose.foundation.layout.Box(Modifier.fillMaxSize()) { planBoard() } },
                secondary = {
                    PlanTodayPane(
                        onOpenTodo = { openTodoEditor(it) },
                        onOpenSchedule = { openScheduleEditor(it) },
                        modifier = Modifier.fillMaxSize()
                    )
                }
            )
        } else {
            SplitBoards(twoPane = false, primary = { planBoard() })
        }
    }

    editingTodo?.let { id ->
        TodoEditScreen(
            todoId = id, presetDate = null,
            onDone = { editingTodo = null },
            // v54.5：编辑器不再内嵌打卡统计——跳面板详情（先关编辑器，回来不会叠两层）
            onOpenCheckin = { tid ->
                editingTodo = null
                boardReturnToEditor = tid
                showBoardFocus = tid
            }
        )
    }
    editingSchedule?.let { id ->
        ScheduleEditScreen(scheduleId = id, presetDate = null, onDone = { editingSchedule = null })
    }
    pendingCheckin?.let { p ->
        CheckinCaptureDialog(
            todo = p.todo,
            draft = p.draft,
            onDismiss = { pendingCheckin = null },
            onSave = { rec ->
                scope.launch { Graph.plan.addCheckin(rec) }
                pendingCheckin = null
            }
        )
    }
    // v23：已过期明细里点某天 → 补交/改那天；onSave(null) = 撤销那天
    backfillTarget?.let { (t, d) ->
        val existing = remember(t.id, d.date) { mutableStateOf<CheckinRecord?>(null) }
        var loaded by remember(t.id, d.date) { mutableStateOf(false) }
        LaunchedEffect(t.id, d.date) {
            existing.value = Graph.plan.checkinOn(t.id, d.date.toLocalDate())
            loaded = true
        }
        if (loaded) {
            io.github.m0use001.planability.ui.common.BackfillCheckinDialog(
                todo = t,
                day = d.date.toLocalDate(),
                existing = existing.value,
                onDismiss = { backfillTarget = null },
                onSave = { rec ->
                    scope.launch {
                        val day = d.date.toLocalDate()
                        if (rec == null) {
                            // 2026-11-14：撤销统一走 undoCheckinDay（原 removeCheckin+toggleCheck
                            // 连监督开关都不判、没卡时「撤销」会打出一张卡，且不撤 done）
                            Graph.plan.undoCheckinDay(t.id, day)
                        } else {
                            Graph.plan.backfillCheck(t.id, day, rec)
                        }
                    }
                    backfillTarget = null
                }
            )
        }
    }
    if (showCategories) {
        CategoryManagerScreen(onDone = { showCategories = false })
    }
    if (showBoard || showBoardFocus != null) {
        CheckinBoardScreen(
            initialTodoId = showBoardFocus,
            onDone = {
                // 从编辑器进来的：退出面板后回到那个待办的编辑器（你当时在编辑）
                val back = boardReturnToEditor
                showBoard = false
                showBoardFocus = null
                boardReturnToEditor = null
                if (back != null) openTodoEditor(back)
            }
        )
    }

    // v54.4-P2：长按菜单的动作落点
    redateTodo?.let { t ->
        DatePickerModal(
            t.date.takeIf { it > 0 }?.toLocalDate() ?: AppTime.today(),
            { redateTodo = null }
        ) { d ->
            scope.launch {
                // 与编辑器改日期同语义：整个实例平移，时段（due/endAt 的时刻部分）保持不变
                val old = t.date.takeIf { it > 0 }?.toLocalDate() ?: d
                val shift = java.time.temporal.ChronoUnit.DAYS.between(old, d) * 86_400_000L
                Graph.plan.saveTodo(
                    t.copy(
                        date = AppTime.dayKey(d),
                        due = if (t.due > 0) t.due + shift else 0L,
                        endAt = if (t.endAt > 0) t.endAt + shift else 0L
                    )
                )
            }
            redateTodo = null
        }
    }
    redateSchedule?.let { s ->
        DatePickerModal(s.start.toLocalDate(), { redateSchedule = null }) { d ->
            scope.launch {
                // 系列锚点整体平移（与编辑器改开始日期同语义），结束随同移
                val shift = java.time.temporal.ChronoUnit.DAYS.between(s.start.toLocalDate(), d) * 86_400_000L
                Graph.plan.saveSchedule(
                    s.copy(
                        start = s.start + shift,
                        end = if (s.end > 0) s.end + shift else 0L
                    )
                )
            }
            redateSchedule = null
        }
    }
    deleteTodoTarget?.let { t ->
        ConfirmDialog(
            title = "删除待办",
            text = "「${t.title}」将移入回收站。",
            onConfirm = { scope.launch { Graph.plan.deleteTodo(t.id) } },
            onDismiss = { deleteTodoTarget = null }
        )
    }
    deleteScheduleTarget?.let { s ->
        ConfirmDialog(
            title = "删除日程",
            text = "「${s.title}」整个重复系列将被删除。",
            onConfirm = { scope.launch { Graph.plan.deleteSchedule(s.id) } },
            onDismiss = { deleteScheduleTarget = null }
        )
    }

    // v54.4-P4：重复项动作范围（仅此一份 / 此条及以后 / 整个系列）
    scopeTodo?.let { t ->
        val day = t.date.takeIf { it > 0 }?.toLocalDate() ?: AppTime.today()
        SeriesScopeDialog(
            title = when (scopeTodoAction) { "edit" -> "编辑范围"; "redate" -> "改期范围"; else -> "删除范围" },
            item = "「${t.title}」是重复待办，当前这份在 ${day.formatMonthDay()}",
            options = if (scopeTodoAction == "delete") listOf("仅这一份（${day.formatMonthDay()} 不再出现）", "整个系列")
            else listOf("仅这一份（拆成单次来改）", "整个系列"),
            onPick = { i ->
                scopeTodo = null
                when (scopeTodoAction) {
                    "edit" -> if (i == 0) scope.launch {
                        // 拆单之后打开的是**新拆出来的那一份**：openTodoEditor 在宽屏会把它送进右栏。
                        openTodoEditor(Graph.plan.splitTodoOccurrence(t, day))
                    } else openTodoEditor(t.id)
                    "redate" -> if (i == 0) scope.launch {
                        val copy = Graph.plan.todoById(Graph.plan.splitTodoOccurrence(t, day))
                        if (copy != null) redateTodo = copy
                    } else redateTodo = t
                    "delete" -> if (i == 0) scope.launch { Graph.plan.excludeTodoOccurrence(t, day) } else deleteTodoTarget = t
                }
            },
            onDismiss = { scopeTodo = null }
        )
    }
    scopeSchedule?.let { s ->
        val day = java.time.Instant.ofEpochMilli(scopeScheduleDay).atZone(AppTime.zone).toLocalDate()
        SeriesScopeDialog(
            title = when (scopeScheduleAction) { "edit" -> "编辑范围"; "redate" -> "改期范围"; else -> "删除范围" },
            item = "「${s.title}」是重复日程，这一份在 ${day.formatMonthDay()}",
            options = if (scopeScheduleAction == "delete") listOf(
                "仅这一份（${day.formatMonthDay()} 不再出现）",
                "此条及以后都不再出现",
                "整个系列"
            ) else listOf("仅这一份（拆成单次来改）", "整个系列"),
            onPick = { i ->
                scopeSchedule = null
                when (scopeScheduleAction) {
                    "edit" -> if (i == 0) scope.launch { openScheduleEditor(Graph.plan.excludeScheduleForToday(s, day)) } else openScheduleEditor(s.id)
                    "redate" -> if (i == 0) scope.launch { redateSchedule = Graph.plan.scheduleById(Graph.plan.excludeScheduleForToday(s, day)) } else redateSchedule = s
                    "delete" -> when (i) {
                        0 -> scope.launch { Graph.plan.excludeScheduleOccurrence(s, day) }
                        1 -> scope.launch { Graph.plan.endScheduleFrom(s, day) }
                        else -> deleteScheduleTarget = s
                    }
                }
            },
            onDismiss = { scopeSchedule = null }
        )
    }
}

/**
 * v54.4-P4：重复项动作的范围选择。只对重复项出现——单次项保持原直通动作，
 * 常见的"就改一下"不多一步；真需要区分系列与单份时才问。
 */
@Composable
private fun SeriesScopeDialog(
    title: String,
    item: String,
    options: List<String>,
    onPick: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.motionEnter(),
        title = { Text(title) },
        text = {
            Column {
                Text(
                    item,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                options.forEachIndexed { i, label ->
                    Text(
                        label,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .clickable { onPick(i) }
                            .padding(vertical = 12.dp, horizontal = 6.dp)
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

/**
 * v54.4-P2：计划行长按快菜单的公共壳（待办/日程行四个调用点共用一份条目定义）。
 * 锚在各行自己的 Box 里，展开位置贴着长按的那行。
 */
@Composable
private fun PlanRowMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    onEdit: () -> Unit,
    onRedate: () -> Unit,
    onDelete: () -> Unit,
    /** 2026-11-23 用户：长按菜单加「复制」——复制成一条**新的单次项**（重复系列不复制成整串，
     *  避免一次变两串；复制出的这条放在同一时段/同一天，用户再按需改）。 */
    onCopy: () -> Unit = {}
) {
    androidx.compose.material3.DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        androidx.compose.material3.DropdownMenuItem(
            modifier = Modifier.clip(RoundedCornerShape(10.dp)), // 2026-11-15 点击高亮圆角化（原来是整行直角方块）
            text = { Text("编辑") },
            onClick = { onDismiss(); onEdit() }
        )
        androidx.compose.material3.DropdownMenuItem(
            modifier = Modifier.clip(RoundedCornerShape(10.dp)),
            text = { Text("复制") },
            onClick = { onDismiss(); onCopy() }
        )
        androidx.compose.material3.DropdownMenuItem(
            modifier = Modifier.clip(RoundedCornerShape(10.dp)), // 2026-11-15 点击高亮圆角化（原来是整行直角方块）
            text = { Text("改期…") },
            onClick = { onDismiss(); onRedate() }
        )
        androidx.compose.material3.DropdownMenuItem(
            modifier = Modifier.clip(RoundedCornerShape(10.dp)), // 2026-11-15 点击高亮圆角化（原来是整行直角方块）
            text = { Text("删除") },
            onClick = { onDismiss(); onDelete() }
        )
    }
}

@Composable
private fun ScheduleRow(
    occ: ScheduleOcc,
    tags: List<CategoryEntity>,
    labelHues: Map<String, Float> = emptyMap(),
    labelTints: Map<String, LabelTint> = emptyMap(),
    byUrgencyImportance: Boolean = false,
    /** true = 「全部」按规则渲染：时间显示时段、并多一行重复频率 */
    ruleView: Boolean = false,
    /**
     * v62 宽屏分组列表：true = 扁平行（无行间空隙、无圆角底），行与行之间由调用点画细线。
     * 手机恒 false，仍是 16dp 圆角卡。
     */
    flat: Boolean = false,
    /** v62：宽屏右栏正在编辑这一条——行底高亮，让「左列表 ↔ 右编辑器」指向明确。 */
    selected: Boolean = false,
    onLongPress: () -> Unit = {},
    onClick: () -> Unit
) {
    val haptics = LocalHaptics.current
    val rowBg = when {
        selected -> MaterialTheme.colorScheme.surfaceContainerHigh
        flat -> Color.Transparent
        else -> MaterialTheme.colorScheme.surfaceContainer
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = if (flat) 0.dp else 6.dp)
            // 2026-11-20：宽屏扁平行**不能把圆角也归零**。原写法 `if (flat) 0.dp else 16.dp`
            // 让选中底（surfaceContainerHigh）和按压水波在平板上一律变成直角方块——
            // 用户截图报的「突兀的正方形」。扁平只该是"没有卡片底、没有行间空隙"，
            // 裁剪仍要圆角：平时透明看不出形状，按下/选中时才是圆角。
            .clip(io.github.m0use001.planability.ui.common.flatRowShape(flat))
            .background(rowBg)
            .combinedClickable(
                onClick = onClick,
                onLongClick = { haptics.perform(HapticEngine.Kind.Tap); onLongPress() }
            )
            .padding(
                horizontal = if (flat) 16.dp else 14.dp,
                vertical = if (flat) 12.dp else 14.dp
            )
    ) {
        // 行首不再画竖色条：标签已经用胶囊表达了（用户要求「日程与计划不要使用竖线或点标记标签」）。
        // 竖条只能表达"第一个标签"的颜色，多个标签时反而误导；胶囊能一个个列出来。
        Column(Modifier.weight(1f)) {
            // 层级（2026-10-21 用户「计划界面文字太乱，要更有层次感」）：
            // 标题 16sp SemiBold → 时间 14sp Medium 主色 → 地点/频率 11sp 灰。
            // 原来三行几乎同字号同灰度，扫一眼分不出主次。
            Text(
                occ.schedule.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(3.dp))
            // 时间（主色、加大）单独一行；地点另起一行（用户 2026-10-21：地点放到时间下面）
            val timeText = when {
                // 重复日程：规则视图只显示时段（日期由「每周…至…」的频率行表达）
                ruleView && occ.schedule.repeatRule.isNotBlank() ->
                    AppTime.millisToTime(occ.schedule.start) + "–" + AppTime.millisToTime(occ.schedule.end)
                // 非重复日程：必须带日期——不然像期末考（2027-01-15）这种，行里只有时间，
                // 完全看不出是哪天（用户 2026-10-20 反馈「只显示时间」）
                else -> dateLabel(occ) + "–" + AppTime.millisToTime(occ.schedule.end)
            }
            Text(
                timeText,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1
            )
            // 地点恒渲染：没设置也占一行（同一屏行高才对得齐，用户 2026-10-20）
            Spacer(Modifier.height(2.dp))
            Text(
                occ.schedule.place,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.78f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            // 重复频率行：规则视图显示频率；课次视图留空（保持行高一致）。
            // 2026-10-21 排整洁：频率+跨度用紧凑写法（每周一、四 · 9/14–12/28），
            // 「共 N 次」右对齐单独一档——对账要它，但它不该把频率挤成一团。
            val repeatInfo = if (ruleView) scheduleRepeatInfo(occ.schedule) else null
            if (repeatInfo != null) {
                Spacer(Modifier.height(3.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Rounded.Repeat, null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                        modifier = Modifier.size(12.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        repeatInfo.text,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.62f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    if (repeatInfo.count > 0) {
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "共${repeatInfo.count}次",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.48f),
                            maxLines = 1
                        )
                    }
                }
            }
            // v14：日程行把分类露出来。待办行早就有（TagChip），日程侧一直没有——
            // 分类能筛却看不见，等于筛完还得靠记性。
            val labels = io.github.m0use001.planability.ui.common.parseLabels(occ.schedule.labels)
            val schedTag = tags.firstOrNull { it.id == occ.schedule.tagId }
            if (byUrgencyImportance || schedTag != null || labels.isNotEmpty()) {
                // 靠右对齐：紧急·重要模式下多一颗行色胶囊，靠右后标签仍在同一条右边线上
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.End),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                ) {
                    if (byUrgencyImportance) {
                        UrgencyImportanceChips(occ.schedule.importance, occ.schedule.urgency)
                    }
                    schedTag?.let { TagChip(it.name, it.hue) }
                    // v28：自由标签用它自己的颜色（没设过=默认 210f），不再一律蓝色
                    labels.forEach { lb -> TagChip(lb, labelTints[lb] ?: LabelTint(labelHues[lb] ?: 210f)) }
                }
            }
        }
    }
}

/** 「紧急」「重要」各一颗胶囊：和标签胶囊同款，点了各自的档位色 */
@Composable
private fun UrgencyImportanceChips(importance: Int, urgency: Int) {
    val level = io.github.m0use001.planability.data.PriorityMap
    // 分开显示：一个维度一颗胶囊。合在一颗里（"急偏高·重高"）只能靠文案区分两段，
    // 颜色却只有一颗，用户没法一眼看出是哪一维高。
    // 两维各有**自己的连续色阶**（紧急性暖色、重要性冷色，5 档全不同），
    // 不再共用三档 priorityColor —— 那样「偏低/低」同色、「偏高/高」同色。
    // 未设置（-1）的那一维不画胶囊——不要用"低"冒充未设置（用户 2026-10-20 要求可空）
    if (urgency >= 0) {
        DotCapsule(
            text = "急 " + level.labelOf(urgency),
            dot = urgencyColor(urgency)
        )
    }
    if (importance >= 0) {
        DotCapsule(
            text = "重 " + level.labelOf(importance),
            dot = importanceColor(importance)
        )
    }
}

private fun dateLabel(occ: ScheduleOcc): String {
    val d = java.time.Instant.ofEpochMilli(occ.start).atZone(AppTime.zone).toLocalDateTime()
    val today = AppTime.today()
    val day = d.toLocalDate()
    val dayText = when (day) {
        today -> "今天"
        today.plusDays(1) -> "明天"
        today.minusDays(1) -> "昨天"
        else -> day.formatMonthDay()
    }
    return "$dayText ${"%02d:%02d".format(d.hour, d.minute)}"
}

/**
 * 规则视图的重复频率文案（**不含时间**，时间由行内单独显示）：
 * 「每周一、四 · 9/14–12/28」。不重复返回 null。
 *
 * 「共 N 次」是把规则在 ±1 年内展开后的**实际课次数**——用户对账就靠它
 * （2026-10-20：用户数出来的行数和 148 对不上，因为「全部」视图一条规则只占一行）。
 * 2026-10-21：次数从文案里拆出去，由行内右对齐单独显示（[RepeatInfo.count]）。
 */
private data class RepeatInfo(val text: String, val count: Int)

/**
 * 「今天」视图是否要为这条待办补一份「欠账副本」（date=今天）。
 *
 * 2026-11-24 用户截图实锤「四级：背单词70个（工作日）」在「今天」里出现两行。根因：
 * 2026-11-03 补副本逻辑只排除了「非今天范围」，漏了 **permanent（常驻）**——常驻待办
 * 主行本就通过 inRange 的 `t.permanent && !offToday` 显示，再补一份 date=今天的副本
 * → 同一待办两行（LazyColumn key=(id,date) 不同，都渲染）。
 * 修：常驻待办一律不补副本（主行已显示）；只有「非永久、日期已过、未完成、会重复」才补。
 * 抽成纯函数供单测钉住。
 */
internal fun needsTodayOverdueCopy(t: io.github.m0use001.planability.data.Todo, todayKey: Long): Boolean =
    !t.permanent && t.date > 0 && t.date < todayKey && !t.done && t.repeatType != "none"

/**
 * 2026-11-13 用户「为什么今天出现了根本没设置在今天的待办」：
 * 常驻（permanent）weekly 待办在「今天」筛选里原来**无条件放行**——
 * 「四级：背单词60个（工作日）」repeatDays=1,2,3,5（无周四）、「真题精听1篇（20分钟）」
 * repeatDays=1,2,3,5,6,7（也无周四），周四全部出现在「今天」。修：weekly/biweekly 且
 * **显式 weekdays** 的常驻按 Recurrence 相位判今天是否命中（occurrences 统一引擎，跳过
 * 锚点之前的日期=已滚到下一期的自然排除）；其余频率（每天/每月/无星期限制/不重复）保持
 * 原 permanent 行为不收敛——窄修，防把别的水位搅浑。
 * 返回 true = 今天不命中，应从「今天」剔除。
 */
internal fun permanentWeeklyOffToday(t: io.github.m0use001.planability.data.Todo, today: java.time.LocalDate): Boolean {
    if (t.date <= 0) return false
    // 注意 repeatType 有 "weekly"/"biweekly" 两态——交给 parseTodoRule 统一解析，
    // 这里只按解析结果的 Kind 判断（按字符串先挡会把 biweekly 漏放行）
    val rec = io.github.m0use001.planability.data.Recurrence.parseTodoRule(t)
    if (rec.kind != io.github.m0use001.planability.data.Recurrence.Kind.WEEKLY || rec.weekdays.isEmpty()) return false
    return rec.occurrences(t.date.toLocalDate(), today, today).isEmpty()
}

private fun scheduleRepeatInfo(s: io.github.m0use001.planability.data.Schedule): RepeatInfo? {
    if (s.repeatRule.isBlank()) return null
    val rec = io.github.m0use001.planability.data.Recurrence.parseScheduleRule(s.repeatRule)
    if (rec.kind == io.github.m0use001.planability.data.Recurrence.Kind.NONE) return null
    val anchor = s.start.toLocalDate()
    // 展开数一下实际课次（±1 年，与列表的「全部」口径一致）
    val cnt = rec.occurrences(anchor, anchor.minusYears(1), anchor.plusYears(1)).size
    return RepeatInfo(rec.describeCompact(anchor), cnt)
}

@Composable
private fun TodoRow(
    todo: Todo,
    stat: SupervisionStat?,
    overview: SupervisionOverview? = null,
    ledger: List<CheckinDay>? = null,
    expanded: Boolean = false,
    tags: List<CategoryEntity>,
    labelHues: Map<String, Float> = emptyMap(),
    labelTints: Map<String, LabelTint> = emptyMap(),
    byUrgencyImportance: Boolean = false,
    /** v62 宽屏分组列表：扁平行（无行间空隙、无圆角底），行与行之间由调用点画细线。 */
    flat: Boolean = false,
    /** v62：宽屏右栏正在编辑这一条——行底高亮。 */
    selected: Boolean = false,
    onToggleExpand: () -> Unit = {},
    onPickDay: (CheckinDay) -> Unit = {},
    onLongPress: () -> Unit = {},
    onCheck: () -> Unit,
    onClick: () -> Unit
) {
    val haptics = LocalHaptics.current
    val missed = ledger?.let { CheckinLedger.missedCount(it) } ?: 0
    val late = ledger?.let { CheckinLedger.lateCount(it) } ?: 0
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = if (flat) 0.dp else 5.dp)
            // v57 批 2b：行级 wobbleOn 撤除——状态胶囊本体（TodoStatusPill）已挂组件级
            // wobbleOn（三态翻转抖），勾选时行+胶囊双抖违反「一时刻一抖」红线；
            // 组件级挂点还让时间轴/首页的胶囊自动获得同一反馈。
            // 2026-11-20：同上，宽屏扁平行不再把圆角归零（选中底/水波会是直角方块）。
            .clip(io.github.m0use001.planability.ui.common.flatRowShape(flat))
            .background(
                when {
                    selected -> MaterialTheme.colorScheme.surfaceContainerHigh
                    flat -> Color.Transparent
                    else -> MaterialTheme.colorScheme.surfaceContainer
                }
            )
    ) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = { haptics.perform(HapticEngine.Kind.Tap); onLongPress() }
            )
            .padding(horizontal = 8.dp, vertical = if (flat) 12.dp else 10.dp)
    ) {
        // v46.3 用户：胶囊与标题**同一行**，下面的日期/地点/统计/标签**不跟标题缩进**
        // （原来胶囊占一列、整块内容都被推到右边）。胶囊可点=打卡/取消。
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            // v46.4 用户：「标题下方数据对齐胶囊左边」——胶囊盒子宽度=胶囊本身（20dp），
            // 不再用 40dp 最小触控盒把胶囊居中（那样胶囊左缘比行左缘缩进 10dp、与下方数据错位）；
            // 触控靠 32dp 高盒子纵向放大，横向接受 20dp 精度。
            Box(
                modifier = Modifier
                    .clickable(onClick = { onCheck(); haptics.perform(HapticEngine.Kind.Select) })
                    .width(20.dp)
                    .height(32.dp),
                contentAlignment = Alignment.Center
            ) {
                io.github.m0use001.planability.ui.common.TodoStatusPill(
                    done = todo.done,
                    percentDone = todo.percentDone,
                    // 2026-11-03 用户：填充满的胶囊也用绿色（与未填充的边框同色）——
                    // 原 done 分支给了 onSurfaceVariant 灰，「填满了反而换色」不连贯。
                    // 首页时间轴的胶囊用标题色是它自己的语言，不动。
                    tint = MaterialTheme.colorScheme.primary
                )
            }
            // v46.4：胶囊与标题间距 7dp（用户「胶囊和标题间距离大一点」）
            Spacer(Modifier.width(7.dp))
            Text(
                todo.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                textDecoration = if (todo.done) TextDecoration.LineThrough else null,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
        }
        Spacer(Modifier.height(3.dp))
            // 层级同日程行：日期+时间 14sp 主色 → 地点/重复 11sp 灰
            val dateText = when {
                todo.permanent -> "常驻"
                todo.date == 0L -> "未安排"
                else -> dateKeyLabel(todo.date)
            }
            val timeText = buildString {
                if (todo.due > 0) {
                    append(AppTime.millisToTime(todo.due))
                    if (todo.endAt > todo.due) append("–").append(AppTime.millisToTime(todo.endAt))
                }
            }
            Text(
                if (timeText.isBlank()) dateText else "$dateText  $timeText",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1
            )
            Spacer(Modifier.height(3.dp))
            // 地点恒渲染（未设置留空），与日程行同构；重复频率放同一行的右侧。
            // 两者都为空时**不占行**——空 Text 也会撑出一行高度，卡片底部会凭空多出一块。
            if (todo.place.isNotBlank() || todo.repeatType != "none") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        todo.place,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.78f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    if (todo.repeatType != "none") {
                        Text(
                            " · ",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
                        )
                        // 重复频率用紧凑文字（与日程行同构），一个图标说明不了频率
                        val anchor = if (todo.date != 0L) todo.date.toLocalDate() else AppTime.today()
                        Text(
                            io.github.m0use001.planability.data.Recurrence.parseTodoRule(todo).describeCompact(anchor),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.62f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
            // 统计单独一行：连续/完成率/欠/补交 —— 不和日期时间挤在同一行
            val hasStats = stat != null || (overview?.expectedDays ?: 0) > 1 || missed > 0 || late > 0
            if (hasStats) {
                Spacer(Modifier.height(2.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    stat?.let {
                        Text("连续${it.streak}天", style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.tertiary)
                    }
                    // v23：长期待办给加权完成率；欠账和补交一起亮出来，别只报"打过 N 天"的好话
                    overview?.let { o ->
                        if (o.expectedDays > 1) {
                            Spacer(Modifier.width(10.dp))
                            Text(
                                "完成率 ${o.rate}%",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = if (o.rate >= 80) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.error
                            )
                        }
                    }
                    if (missed > 0) {
                        Spacer(Modifier.width(10.dp))
                        Text(
                            "欠${missed}天",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    if (late > 0) {
                        Spacer(Modifier.width(10.dp))
                        Text("补交${late}天", style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.tertiary)
                    }
                }
            }
            // 标签胶囊单独一行、统一靠右。
            // ① 紧急·重要模式下会多一颗"行色胶囊"，放行内会把右侧标签推走；
            //    两种模式都靠右之后，标签贴的是同一条右边线（上下对齐）。
            // ② 元信息（日期/欠账/完成率）长短不一，胶囊跟在后面会一行一个位置。
            val rowTag = tags.firstOrNull { it.id == todo.tagId }
            val rowLabels = io.github.m0use001.planability.ui.common.parseLabels(todo.labels)
            if (byUrgencyImportance || rowTag != null || rowLabels.isNotEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 3.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.End),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (byUrgencyImportance) {
                        UrgencyImportanceChips(todo.importance, todo.urgency)
                    }
                    rowTag?.let { TagChip(it.name, it.hue) }
                    rowLabels.forEach { lb ->
                        TagChip(lb, labelTints[lb] ?: LabelTint(labelHues[lb] ?: 210f))
                    }
                }
            }
    }
    // v46.2：行底进度条与百分比小字已撤销（用户：空间太小不显示百分比）——
    // 完成度由标题左边的状态胶囊表达，与时间轴同语言。
    // 已过期视图下点行头展开逐日明细（普通列表点行是进编辑器，所以展开单独给一个角标按钮）
    if (ledger != null && missed > 0) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                // 2026-11-20：整行可点但无底，水波本来是直角横杠——补 10dp 圆角裁剪
                .clip(RoundedCornerShape(10.dp))
                // 展开/收起：中性轻触（Tap）。HIG 没有对应概念——它不是"选中"（Select）
                // 也不是不可逆动作，就是"点一下纸角翻开"
                .clickable(onClick = { haptics.perform(HapticEngine.Kind.Tap); onToggleExpand() })
                .padding(start = 12.dp, end = 14.dp, bottom = 8.dp)
        ) {
            Text(
                if (expanded) "收起明细" else "展开哪几天没完成",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.width(4.dp))
            Text(
                if (expanded) "▾" else "▸",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.weight(1f))
            Text(
                // 按时数用 state == DONE 而不是 checked && !late：PENDING（今天）虽然"没欠"，
                // 但它还没打卡，算进"按时"会让今天白送一天满分
                "共 ${ledger.size} 天 · 按时 ${ledger.count { it.state == CheckinDay.DONE }} · " +
                    "补交 ${late} · 未完成 ${missed}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        // 展开逐日明细：AnimatedVisibility 走 Motion.expandIn（defaultSpatial 弹簧：淡入+纵向展开）/
        // Motion.collapseOut（fastSpatial 纵向收缩+淡出）——原来是 if 硬切，下方的行被瞬间顶下去，
        // 用户看不出"多了几行"。用高度动画（而不是只做淡入）是为了让下面的卡片被**推**开而不是跳开；
        // 收起比展开快是因为收起只是回到原状，越干脆越好。
        // 外面套一层 Column：AnimatedVisibility 只负责一个子节点的进出场，多子节点要自己给容器。
        androidx.compose.animation.AnimatedVisibility(
            visible = expanded,
            enter = Motion.expandIn,
            exit = Motion.collapseOut
        ) {
            Column {
                // 落位触感：AnimatedVisibility 没有 onExpanded 回调；监听 scope 的 transition——
                // 状态到 Visible 且动画停了（=弹簧落定）给最轻的 Select；收起（回 Invisible）不配。
                // 2026-10-30 加守卫：必须先见过 running=true 才算"真的展开过"——列表重组/滚动回收
                // 会带着 expanded=true 重进组合，首帧就是 Visible+未运行动画，原写法会虚发一记
                // Select（用户实测：过期视角与进行中视角点同一个范围 chip，震感不一致）。
                androidx.compose.runtime.LaunchedEffect(Unit) {
                    var sawRunning = false
                    androidx.compose.runtime.snapshotFlow {
                        transition.currentState to transition.isRunning
                    }.collect { (state, running) ->
                        if (state == androidx.compose.animation.EnterExitState.Visible && running)
                            sawRunning = true
                        if (state == androidx.compose.animation.EnterExitState.Visible && !running && sawRunning) {
                            sawRunning = false
                            haptics.perform(HapticEngine.Kind.Select)
                        }
                    }
                }
                io.github.m0use001.planability.ui.common.CheckinDayList(
                    days = ledger,
                    onPickDay = onPickDay
                )
                Spacer(Modifier.height(8.dp))
            }
        }
    }
    }
}

fun dateKeyLabel(dayKey: Long): String {
    val d = dayKey.toLocalDate()
    val today = AppTime.today()
    return when (d) {
        today -> "今天"
        today.plusDays(1) -> "明天"
        today.minusDays(1) -> "昨天"
        else -> d.formatMonthDay()
    }
}

// ==================== 倒数日管理 ====================

@Composable
fun CountdownManageScreen(onDone: () -> Unit, deferBack: Boolean = false) {
    val scope = rememberCoroutineScope()
    // 倒数日增删改后立即推桌面组件（用户 2026-10-26「新建后更新延迟」）
    val context = androidx.compose.ui.platform.LocalContext.current
    val haptics = LocalHaptics.current
    var list by remember { mutableStateOf<List<Countdown>>(emptyList()) }
    var editing by remember { mutableStateOf<Countdown?>(null) }
    var showEditor by remember { mutableStateOf(false) }
    val version by Graph.dataVersion.flow.collectAsState()

    LaunchedEffect(version) { list = Graph.plan.countdowns() }

    PredictiveBackOverlay(onBack = onDone, deferBack = deferBack) {
        // close 是 PredictiveBackOverlay provide 的：必须在这里读一次存下来，
        // 放到 overlay 外面读到的是默认空实现（返回点了没反应），在 onClick 里读则编译不过（@Composable getter）
        val overlayClose = LocalOverlayClose.current
        Scaffold(
            // v76：deferBack（首页钻入瓦片）时板底由壳层固定框绘制 → 透明 + 板面头；
            // 整屏模态时照旧自带底色 + TopAppBar。
            containerColor = if (deferBack) androidx.compose.ui.graphics.Color.Transparent
            else androidx.compose.material3.MaterialTheme.colorScheme.background,
            floatingActionButtonPosition = androidx.compose.material3.FabPosition.Center,
            topBar = {
                if (deferBack) {
                    io.github.m0use001.planability.ui.common.BoardSubHeader("倒数日", onDone)
                } else {
                TopAppBar(
                    title = { Text("倒数日", fontWeight = FontWeight.SemiBold) },
                    navigationIcon = {
                        // 返回 = 中性轻触（Tap），见 core/Haptics.kt 的 HIG 映射表
                        IconButton(onClick = { haptics.perform(HapticEngine.Kind.Tap); overlayClose() }) {
                            Icon(Icons.AutoMirrored.Rounded.ArrowBack, "返回")
                        }
                    }
                )
                }
            },
            floatingActionButton = {
                MediumFab(onClick = {
                    // FAB = 中性轻触（Tap），与计划页主 FAB / 文件库 FAB 同档
                    haptics.perform(HapticEngine.Kind.Tap)
                    editing = null; showEditor = true
                }) {
                    Icon(Icons.Rounded.Add, contentDescription = "新建")
                }
            }
        ) { pad ->
            val cdState = androidx.compose.foundation.lazy.rememberLazyListState()
            LazyColumn(
                Modifier.fillMaxSize().padding(pad).padding(horizontal = 16.dp).reachedEndHaptic(cdState),
                state = cdState,
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (list.isEmpty()) item { EmptyHint("记录值得纪念的日子") }
                items(list, key = { it.id }) { c ->
                    // v16：每周几型按下一次出现推算；固定日每年重复推进周年；已过期显示"已过 N 天"
                    val targetDate = c.nextDate(AppTime.today())
                    val days = ChronoUnit.DAYS.between(AppTime.today(), targetDate)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.animateItem()
                            .fillMaxWidth()
                            .pressScale()
                            .clip(RoundedCornerShape(18.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainer)
                            .clickable { editing = c; showEditor = true }
                            .padding(16.dp)
                    ) {
                        Column(Modifier.weight(1f)) {
                    // 2026-10-28 用户：名称/日期/剩余天数字号加大，"天"字保持原档
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(c.title, style = MaterialTheme.typography.titleLarge)
                                c.kindLabel()?.let { kind ->
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        kind,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f))
                                            .padding(horizontal = 5.dp, vertical = 1.dp)
                                    )
                                }
                                if (c.repeatYearly && !c.weekly && !c.monthly) {
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        "每年",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f))
                                            .padding(horizontal = 5.dp, vertical = 1.dp)
                                    )
                                }
                            }
                            Text(
                                when {
                                    c.weekly -> "每" + (c.weekdayLabel() ?: "")
                                    c.monthly -> "每月${c.dayOfMonth}号（大月取月末）"
                                    else -> c.target.toLocalDate().formatCn()
                                },
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (days == 0L) {
                            Text(
                                "就是今天",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Bold
                            )
                        } else {
                            // 数字单独放大一档（titleMedium→headlineSmall），"还有/天"保持原字号
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    if (days > 0) "还有 " else "已过 ",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    (if (days > 0) days else -days).toString(),
                                    style = MaterialTheme.typography.headlineSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    " 天",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                        IconButton(onClick = {
                            haptics.perform(HapticEngine.Kind.Error)
                            scope.launch {
                                Graph.plan.deleteCountdown(c.id)
                                io.github.m0use001.planability.widget.WidgetRefresh.countdownsNow(context)
                            }
                        }) {
                            // 2026-10-25：行内垃圾桶中性化，红色只在确认步骤（与笔记/文件页一致）
                            Icon(Icons.Rounded.Delete, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                item { Spacer(Modifier.height(60.dp)) }
            }
        }
    }

    if (showEditor) {
        var title by remember { mutableStateOf(editing?.title ?: "") }
        var date by remember { mutableStateOf(editing?.target?.toLocalDate() ?: AppTime.today().plusDays(7)) }
        var yearly by remember { mutableStateOf(editing?.repeatYearly ?: false) }
        var pickDate by remember { mutableStateOf(false) }
        // v16/v17：日期类型 0 固定日期 / 1 每周几 / 2 每月几号
        var kind by remember {
            mutableIntStateOf(
                when {
                    editing?.weekly == true -> 1
                    editing?.monthly == true -> 2
                    else -> 0
                }
            )
        }
        var weekday by remember { mutableIntStateOf(if (editing?.weekly == true) editing!!.weekday else 5) }
        var monthDay by remember { mutableIntStateOf(if (editing?.monthly == true) editing!!.dayOfMonth else 1) }
        AlertDialog(
            onDismissRequest = { showEditor = false },
            modifier = Modifier.motionEnter(),
            title = { Text(if (editing == null) "新建倒数日" else "编辑倒数日") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text("名称") }, singleLine = true)
                    io.github.m0use001.planability.ui.common.AppButtonGroup(
                        options = listOf("固定日期", "每周", "每月"),
                        selectedIndex = kind,
                        onSelect = { kind = it }
                    )
                    if (kind == 0) {
                        OutlinedButton(onClick = { pickDate = true }) {
                            Text("目标日：${date.formatCn()}")
                        }
                        // v10：每年重复（生日/节日/周年）
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            FilterChip(
                                colors = filterChipColors(),
                                selected = yearly,
                                onClick = { yearly = !yearly; haptics.perform(HapticEngine.Kind.Select) },
                                label = { Text("每年重复") }
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "按周年自动推进倒数",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    } else if (kind == 1) {
                        // v16：每周几（如把每个周五列为倒数日）
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.horizontalScroll(rememberScrollState())
                        ) {
                            listOf("一", "二", "三", "四", "五", "六", "日").forEachIndexed { i, name ->
                                FilterChip(
                                    colors = filterChipColors(),
                                    selected = weekday == i + 1,
                                    onClick = { weekday = i + 1; haptics.perform(HapticEngine.Kind.Select) },
                                    label = { Text("周$name") }
                                )
                            }
                        }
                        Text(
                            "每个所选日都是倒数日，距下一次的天数实时计算",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        // v17：每月几号（大月超出自动取月末）
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.horizontalScroll(rememberScrollState())
                        ) {
                            (1..31).forEach { d ->
                                FilterChip(
                                    colors = filterChipColors(),
                                    selected = monthDay == d,
                                    onClick = { monthDay = d; haptics.perform(HapticEngine.Kind.Select) },
                                    label = { Text("$d") }
                                )
                            }
                        }
                        Text(
                            "每月该号都是倒数日；小月没有该号时取月末",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (title.isNotBlank()) {
                        scope.launch {
                            Graph.plan.saveCountdown(
                                Countdown(
                                    id = editing?.id ?: 0, title = title.trim(),
                                    target = AppTime.dayKey(date),
                                    repeatYearly = yearly && kind == 0,
                                    weekday = if (kind == 1) weekday else -1,
                                    dayOfMonth = if (kind == 2) monthDay else -1
                                )
                            )
                            // 立刻重推桌面组件，不等启动/周期刷新
                            io.github.m0use001.planability.widget.WidgetRefresh.countdownsNow(context)
                        }
                    }
                    showEditor = false
                }) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { showEditor = false }) { Text("取消") } }
        )
        if (pickDate) DatePickerModal(date, { pickDate = false }, { date = it; pickDate = false })
    }
}

// ==================== 分类管理 ====================

@Composable
fun CategoryManagerScreen(onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    val haptics = LocalHaptics.current
    var tab by remember { mutableIntStateOf(0) }
    var list by remember { mutableStateOf<List<CategoryEntity>>(emptyList()) }
    var showAdd by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }
    // v27：地点分类绑坐标（复用地图选点面板，POI 搜索 + 逆地理都在里面）
    var geoTarget by remember { mutableStateOf<CategoryEntity?>(null) }
    // v40（2026-10-20 用户反馈）：地点管理接入地图选点——「添加地点」直接开选点面板，
    // 名称取 POI/逆地理结果（地图面板里名称本就只读，避免手写名字与坐标对不上），一次拿到名字+坐标。
    var addingPlace by remember { mutableStateOf(false) }
    val version by Graph.dataVersion.flow.collectAsState()

    LaunchedEffect(version, tab) { list = Graph.plan.categories(tab) }

    // 「标签」页展示**真实标签**（日程/待办/笔记上的 labels 自由文本 + label_colors），
    // 而不是已退役的 CategoryEntity(kind=0)：分类体系在 v14 之后就不再由编辑器写入，
    // 那一页此前永远是空的（用户 2026-10-19 反馈「一个标签都看不到」）。
    val globalLabels = io.github.m0use001.planability.ui.common.rememberGlobalLabels()
    var labelEdit by remember { mutableStateOf<String?>(null) }
    var labelDelete by remember { mutableStateOf<String?>(null) }

    PredictiveBackOverlay(onBack = onDone) {
        // close 由 PredictiveBackOverlay provide：必须在这里读，读晚了/读外了都不对（详见 CountdownManageScreen 同处注释）
        val overlayClose = LocalOverlayClose.current
        Scaffold(
            floatingActionButtonPosition = androidx.compose.material3.FabPosition.Center,
            topBar = {
                TopAppBar(
                    title = { Text("分类管理", fontWeight = FontWeight.SemiBold) },
                    navigationIcon = {
                        IconButton(onClick = { haptics.perform(HapticEngine.Kind.Tap); overlayClose() }) {
                            Icon(Icons.AutoMirrored.Rounded.ArrowBack, "返回")
                        }
                    }
                )
            },
            floatingActionButton = {
                MediumFab(onClick = {
                    haptics.perform(HapticEngine.Kind.Tap)
                    // 地点页：添加地点 = 开地图选点（名称+坐标一次完成）；标签页仍是纯文字预置
                    if (tab == 1) addingPlace = true else { newName = ""; showAdd = true }
                }) {
                    Icon(Icons.Rounded.Add, contentDescription = "添加")
                }
            }
        ) { pad ->
            Column(Modifier.fillMaxSize().padding(pad)) {
                // 指示条换 M3E 弹簧滑动（MotionTabIndicator）：默认是定长 tween 硬曲线，不在弹簧体系
                TabRow(selectedTabIndex = tab, indicator = { pos -> MotionTabIndicator(pos, tab) }) {
                    // v13：删去「文件夹」子页面（文件夹分组由 AI 工作台项目树承担），只留标签/地点
                    listOf("标签", "地点").forEachIndexed { i, label ->
                        // 二级 tab = 选择（HIG selection → 最轻的 Select），与 chip/分段按钮同档
                        Tab(selected = tab == i, onClick = { tab = i; haptics.perform(HapticEngine.Kind.Select) }, text = { Text(label) })
                    }
                }
                // 两个 tab 各自一份 LazyListState：AnimatedContent 横移期间两段列表**同时在世**，
                // 共用一份 state 会互相抢滚动、并让两段内容在同一帧里叠着渲染
                // （2026-10-25 用户截图：「暂无地点分类」与标签页空态提示叠成乱码）。
                val labelsState = androidx.compose.foundation.lazy.rememberLazyListState()
                val placesState = androidx.compose.foundation.lazy.rememberLazyListState()
                // 二级 Tab（标签/地点）：**硬切内容**（TabRow 指示条自身仍有动画）。
                // 2026-10-25 用户两次反馈「分类管理叠字」：横向过渡的中间帧会把
                // 「还没有标签」和「暂无地点分类」并排画在同一行，长文案叠在一起就是乱码。
                // 这两页都是长提示文案，任何同帧双页渲染都会被读成错字——宁可不做内容过渡。
                if (tab == 0) {
                    // 标签页：真实标签清单（名称 + 使用次数 + 改名/改色/删除）
                    LazyColumn(
                        Modifier.fillMaxSize().padding(horizontal = 16.dp),
                        state = labelsState
                    ) {
                        if (globalLabels.names.isEmpty()) {
                            item {
                                EmptyHint("还没有标签\n在日程/待办/笔记的编辑器里添加，或点右下角 + 预置一个")
                            }
                        }
                        items(globalLabels.names, key = { "lbl-$it" }) { name ->
                            LabelManageRow(
                                name = name,
                                count = globalLabels.counts[name] ?: 0,
                                tint = globalLabels.tintOf(name),
                                onEdit = { labelEdit = name },
                                onDelete = { labelDelete = name }
                            )
                        }
                        item { Spacer(Modifier.height(60.dp)) }
                    }
                } else {
                    // 地点页（2026-10-25 两级模型）：行 = 绑定地名；**长按展开**显示地名胶囊，
                    // 点行仍是「改坐标」。一个绑定地名可挂多个显示地名（同一栋楼不同房间）。
                    var expandedPlace by remember { mutableStateOf<Long?>(null) }
                    // 2026-10-28 用户：分类管理的地点页只保留「长按展开查看显示地名」，
                    // 显示地名的编辑/删除/新增全部移到选点界面（PlacePickerScreen）
                    LazyColumn(
                        Modifier.fillMaxSize().padding(horizontal = 16.dp).reachedEndHaptic(placesState),
                        state = placesState
                    ) {
                        if (list.isEmpty()) {
                            item {
                                EmptyHint("暂无地点分类\n点右下角 +，在地图上选一个地点（名称与坐标一次带上）")
                            }
                        }
                        items(list, key = { it.id }) { c ->
                            val names = io.github.m0use001.planability.data.Graph.plan.displayNamesOf(c)
                            Column(Modifier.animateItem()) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        // pressScale 在 clip 之前（Motion.kt 摆放铁律）
                                        .pressScale()
                                        // 2026-11-20：可点行无底，水波本来是直角横杠 → 12dp 圆角裁剪
                                        .clip(RoundedCornerShape(12.dp))
                                        // 点行 = 改坐标；**长按 = 展开显示地名**（用户 2026-10-25 要求）
                                        .combinedClickable(
                                            onClick = { geoTarget = c },
                                            onLongClick = {
                                                haptics.perform(HapticEngine.Kind.ImpactLight)
                                                expandedPlace = if (expandedPlace == c.id) null else c.id
                                            }
                                        )
                                        .padding(vertical = 8.dp)
                                ) {
                                    Box(Modifier.size(14.dp).background(categoryColor(c.hue), CircleShape))
                                    Spacer(Modifier.width(12.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(c.name, style = MaterialTheme.typography.bodyLarge)
                                        Text(
                                            (if (c.lat != 0.0 || c.lng != 0.0) "已绑坐标 · " else "未绑坐标 · ") +
                                                "${names.size} 个显示地名 · 长按展开",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    if (c.lat != 0.0 || c.lng != 0.0) {
                                        IconButton(onClick = {
                                            // 只清坐标，别整行写回（c 是列表快照，displayNames 可能已变）
                                            scope.launch { Graph.plan.updatePlaceCoords(c.id, 0.0, 0.0) }
                                        }) {
                                            Icon(
                                                Icons.Rounded.MyLocation, "清除坐标",
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                    }
                                    IconButton(onClick = {
                                        haptics.perform(HapticEngine.Kind.ImpactHeavy)
                                        scope.launch { Graph.plan.deleteCategory(c.id) }
                                    }) {
                                        Icon(Icons.Rounded.Delete, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                                    }
                                }
                                if (expandedPlace == c.id) {
                                    // 只读徽标：这里不能改名/删除/新增（那些都在选点界面做），
                                    // 自动换行保证长显示名也读得全
                                    Column(Modifier.fillMaxWidth().padding(start = 26.dp, bottom = 8.dp)) {
                                    androidx.compose.foundation.layout.FlowRow(
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        names.forEach { n ->
                                            Text(
                                                n,
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier
                                                    .clip(RoundedCornerShape(10.dp))
                                                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                                                    .padding(horizontal = 12.dp, vertical = 6.dp)
                                            )
                                        }
                                    }
                                    }
                                }
                            }
                        }
                        item { Spacer(Modifier.height(60.dp)) }
                    }
                }
            }
        }
    }

    // 标签改名/改色（复用编辑器里那套色环 + 全局改名）
    labelEdit?.let { name ->
        io.github.m0use001.planability.ui.common.LabelEditDialog(
            name = name,
            current = globalLabels.tints[name],
            knownNames = globalLabels.names,
            onDismiss = { labelEdit = null },
            onSave = { newName, tint ->
                scope.launch {
                    if (newName != name) {
                        io.github.m0use001.planability.ui.common.renameLabelEverywhere(name, newName)
                    }
                    io.github.m0use001.planability.ui.common.saveLabelTint(newName, tint)
                    Graph.plan.setLabelColor(newName, tint?.hue)
                    labelEdit = null
                }
            }
        )
    }
    // 删标签：从所有条目上摘掉 + 清颜色
    labelDelete?.let { name ->
        io.github.m0use001.planability.ui.common.ConfirmDialog(
            title = "删除标签「$name」？",
            text = "会从所有日程/待办/笔记上移除这个标签，并清掉它的颜色；条目本身不会被删。",
            onConfirm = {
                scope.launch { io.github.m0use001.planability.ui.common.deleteLabelEverywhere(name) }
            },
            onDismiss = { labelDelete = null }
        )
    }

    // 选点面板自带全屏窗口（见 MapPickerSheet），这里不用再包一层 Dialog
    geoTarget?.let { target ->
            io.github.m0use001.planability.ui.edit.MapPickerSheet(
                initial = if (target.lat != 0.0 || target.lng != 0.0)
                    io.github.m0use001.planability.ui.edit.GeoPoint(target.lat, target.lng, target.name) else null,
                initialName = target.name,
                onDismiss = { geoTarget = null },
                onPick = { p, picked ->
                    geoTarget = null
                    scope.launch {
                        // 2026-10-29 根修「面板里新加的显示地名保存后消失」：target 是面板打开时的
                        // 分类快照，saveCategory(target.copy(lat,lng)) 整行写回会把面板期间经
                        // ensureDisplayName 新增的显示名覆盖掉。坐标只更新两列。
                        Graph.plan.updatePlaceCoords(target.id, p.lat, p.lng)
                        // 2026-11-24 用户「绑定真实地点后真名被覆盖」：这里原来把选点面板返回的
                        // 显示名 picked 直接丢了、只写坐标——用户选的真实地名没了、条目还叫旧名。
                        // 现在把 picked（用户选的/POI 自动填的真名）转正为分类名，旧 AI 占位名
                        // 沉为别名显示名（renamePlaceKeepOldAsAlias），真名不再丢、旧名不丢、也不并列两条。
                        val chosen = picked.trim().ifBlank { p.label.trim() }
                        // 只在「原本未绑坐标的占位地点首次绑定」时把真名转正（避免只是挪一下
                        // 已绑地点图钉也被改名）；已绑地点仅更新坐标、名字保持。
                        val wasUnbound = target.lat == 0.0 && target.lng == 0.0
                        if (wasUnbound && chosen.isNotBlank() && chosen != target.name) {
                            Graph.plan.renamePlaceKeepOldAsAlias(target.id, chosen)
                        }
                    }
                }
            )
    }

    // v40（2026-10-20）：添加地点 = 地图选点。名称取 POI/逆地理结果（MapPicker 里名称只读）；
    // 重名则更新已有点的坐标，避免同一个地方在列表里出现两行。
    if (addingPlace) {
        io.github.m0use001.planability.ui.edit.MapPickerSheet(
            initial = null,
            onDismiss = { addingPlace = false },
            onPick = { p, picked ->
                addingPlace = false
                // 地点名取面板地名框（显示名，自动填过绑定名），空了退回绑定名
                val name = picked.trim().ifBlank { p.label.trim().ifBlank { "地点" } }
                scope.launch {
                    // 现查最新行：面板打开期间显示地名可能已被增删，整行写回旧快照会覆盖掉
                    val existing = Graph.plan.categories(1).firstOrNull { it.name == name }
                    if (existing != null) {
                        Graph.plan.updatePlaceCoords(existing.id, p.lat, p.lng)
                    } else {
                        Graph.plan.saveCategory(
                            CategoryEntity(
                                name = name, kind = 1,
                                hue = kotlin.random.Random.nextFloat() * 360f,
                                lat = p.lat, lng = p.lng
                            )
                        )
                    }
                }
            }
        )
    }

    // 添加弹窗现在只服务「标签」页（地点页的 + 直接开地图选点，见 addingPlace）
    if (showAdd) {
        AlertDialog(
            onDismissRequest = { showAdd = false },
            modifier = Modifier.motionEnter(),
            title = { Text("预置一个标签") },
            text = {
                OutlinedTextField(
                    value = newName, onValueChange = { newName = it },
                    label = { Text("标签文字（之后在编辑器里勾选）") },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (newName.isNotBlank()) {
                        scope.launch {
                            // 预置标签 = 先在 label_colors 里占一个颜色；清单（按数据汇总）会把它列出来
                            Graph.plan.setLabelColor(newName.trim(), kotlin.random.Random.nextFloat() * 360f)
                        }
                    }
                    showAdd = false
                }) { Text("添加") }
            },
            dismissButton = { TextButton(onClick = { showAdd = false }) { Text("取消") } }
        )
    }
}

/** 分类管理「标签」页的一行：名称 + 使用次数 + 改名/改色/删除。 */
@Composable
private fun LabelManageRow(
    name: String,
    count: Int,
    tint: LabelTint,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    val haptics = LocalHaptics.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
    ) {
        Box(Modifier.size(14.dp).background(categoryColor(tint), CircleShape))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.bodyLarge)
            Text(
                if (count > 0) "$count 处使用" else "未使用（预置色）",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        IconButton(onClick = { haptics.perform(HapticEngine.Kind.Tap); onEdit() }) {
            Icon(Icons.Rounded.Edit, "改名 / 改色", Modifier.size(18.dp))
        }
        IconButton(onClick = { haptics.perform(HapticEngine.Kind.ImpactHeavy); onDelete() }) {
            Icon(
                Icons.Rounded.Delete, "删除标签",
                tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp)
            )
        }
    }
}

// ==================== v22：打卡数据记录 ====================

/** 勾选完成后待填的打卡数据。v48.1：internal——首页（时间轴/未设置时间胶囊/百分比浮层）复用同一套录入弹窗 */
internal class PendingCheckin(val todo: Todo, val draft: CheckinRecord)

/**
 * 打卡录入弹窗：只在「需要手填」的格式（number/count/text/tags）出现，
 * time 型由 [io.github.m0use001.planability.data.PlanRepo.completeTodo] 自动落库，不打断用户。
 * v48.1：internal——首页三个打卡入口共用（此前只有计划页能录，用户在首页勾完一直没地方填数据）。
 */
@Composable
internal fun CheckinCaptureDialog(
    todo: Todo,
    draft: CheckinRecord,
    onDismiss: () -> Unit,
    onSave: (CheckinRecord) -> Unit,
) {
    val kind = draft.kind
    val haptics = LocalHaptics.current
    val label = todo.dataRecordLabel.ifBlank { todo.title }
    var numText by remember { mutableStateOf("") }
    var textValue by remember { mutableStateOf("") }
    var picked by remember { mutableStateOf(setOf<String>()) }
    var extraTag by remember { mutableStateOf("") }
    val options = remember(todo.dataRecordOptions) { CheckinFormat.parseOptions(todo.dataRecordOptions) }

    val valid = when (kind) {
        CheckinKind.NUMBER, CheckinKind.COUNT -> numText.trim().toDoubleOrNull() != null
        CheckinKind.TEXT -> textValue.isNotBlank()
        CheckinKind.TAGS -> picked.isNotEmpty() || CheckinFormat.parseOptions(extraTag).isNotEmpty()
        else -> true
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.motionEnter(),
        title = { Text("打卡记录 · $label") },
        text = {
            Column {
                Text(
                    CheckinKind.hint(kind),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                when (kind) {
                    CheckinKind.NUMBER -> OutlinedTextField(
                        value = numText, onValueChange = { numText = it },
                        label = { Text("数值" + if (todo.dataRecordUnit.isNotBlank()) "（${todo.dataRecordUnit}）" else "") },
                        singleLine = true, modifier = Modifier.fillMaxWidth()
                    )
                    CheckinKind.COUNT -> OutlinedTextField(
                        value = numText, onValueChange = { numText = it },
                        label = { Text("次数") }, singleLine = true, modifier = Modifier.fillMaxWidth()
                    )
                    CheckinKind.TEXT -> OutlinedTextField(
                        value = textValue, onValueChange = { textValue = it },
                        label = { Text("这次的内容") }, minLines = 2, modifier = Modifier.fillMaxWidth()
                    )
                    CheckinKind.TAGS -> {
                        options.chunked(3).forEach { row ->
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
                            ) {
                                row.forEach { o ->
                                    FilterChip(
                                        colors = filterChipColors(),
                                        selected = o in picked,
                                        onClick = {
                                            picked = if (o in picked) picked - o else picked + o
                                            haptics.perform(HapticEngine.Kind.Select)
                                        },
                                        label = { Text(o) }
                                    )
                                }
                            }
                        }
                        OutlinedTextField(
                            value = extraTag, onValueChange = { extraTag = it },
                            label = { Text("其他（逗号分隔，可留空）") },
                            singleLine = true, modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = valid,
                onClick = {
                    val rec = when (kind) {
                        CheckinKind.NUMBER, CheckinKind.COUNT ->
                            draft.copy(numValue = numText.trim().toDouble())
                        CheckinKind.TEXT -> draft.copy(textValue = textValue.trim())
                        CheckinKind.TAGS -> draft.copy(
                            tags = CheckinFormat.formatOptions(
                                (picked + CheckinFormat.parseOptions(extraTag)).toList()
                            )
                        )
                        else -> draft
                    }
                    onSave(rec)
                }
            ) { Text("保存") }
        },
        // 跳过 = 今天这次没有数据可记。不能当成取消打卡（打卡已经记下了），所以只是不写载荷
        dismissButton = { TextButton(onClick = onDismiss) { Text("跳过") } }
    )
}

// v64（2026-11-20）：板头与行间细线搬进了 ui/common/SplitBoard.kt
// （BoardHeader / BoardRowDivider）——六页主从页面共用一份材质，这里不再各留一份。
