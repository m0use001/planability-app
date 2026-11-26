package io.github.m0use001.planability.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.m0use001.planability.core.AppTime
import io.github.m0use001.planability.core.HapticEngine
import io.github.m0use001.planability.core.LocalHaptics
import io.github.m0use001.planability.core.ShellChrome
import io.github.m0use001.planability.core.formatCn
import io.github.m0use001.planability.core.formatShort
import io.github.m0use001.planability.data.DayBundle
import io.github.m0use001.planability.data.Graph
import io.github.m0use001.planability.app.Router
import io.github.m0use001.planability.ui.common.AppIcons
import io.github.m0use001.planability.ui.common.pressScale
import io.github.m0use001.planability.ui.edit.ScheduleEditScreen
import io.github.m0use001.planability.ui.edit.TimelineEventEditor
import io.github.m0use001.planability.ui.edit.TodoEditScreen
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.time.LocalDate

@Composable
fun HomeScreen(onSelectTab: (String) -> Unit) {
    val haptics = LocalHaptics.current
    var selectedDate by remember { mutableStateOf(AppTime.today()) }
    // v70k（2026-11-22 用户「首页时间轴要在 0 点时自动切换日期」）：App 开着跨零点时
    // selectedDate 还停在昨天——时间轴/右栏/完成率全对不上新的一天。挂一个到下一零点的
    // 定时器，到点刷新 todayNow；只有当用户**正看着今天**（selectedDate==旧的今天）才跟过去，
    // 翻着历史日期时不打扰。首页在后台冻结时定时器暂停（LaunchedEffect 跟组合走），
    // 回前台重组时 todayNow 立即按当前日重算（remember 初值 + 下面 LaunchedEffect(Unit) 兜底）。
    var todayNow by remember { mutableStateOf(AppTime.today()) }
    var prevToday by remember { mutableStateOf(AppTime.today()) }
    LaunchedEffect(Unit) {
        todayNow = AppTime.today()
        prevToday = todayNow
        while (true) {
            val now = java.time.LocalDateTime.now()
            val nextMidnight = now.toLocalDate().plusDays(1).atStartOfDay()
            delay(java.time.Duration.between(now, nextMidnight).toMillis() + 1_500)
            todayNow = AppTime.today()
        }
    }
    LaunchedEffect(todayNow) {
        if (todayNow != prevToday) {
            if (selectedDate == prevToday) selectedDate = todayNow
            prevToday = todayNow
        }
    }
    var bundle by remember { mutableStateOf<DayBundle?>(null) }
    var screenMinutes by remember { mutableStateOf(-1) }
    // 编辑器用各自的 id 当开关（id 非空即打开），照 PlanScreen 的写法 ——
    // 原来 todo/schedule 共用一个 showEditor，关掉其中一个会把另一个也一起灭掉，
    // 表现就是「一次返回两层一起消失」
    var editingTodo by remember { mutableStateOf<Long?>(null) }
    var editingSchedule by remember { mutableStateOf<Long?>(null) }
    var editingEventId by remember { mutableStateOf<Long?>(null) }
    // 2026-11-22 用户：从日历点开待办/日程编辑器，退出后应回到日历（原实现先 closeCalendar 再开
    // 编辑器，退出只剩首页）。此标志记住「本次编辑是从日历进入的」，编辑器全部关闭后自动重开日历。
    var calendarReturnAfterEditor by remember { mutableStateOf(false) }
    // v54.5：待办编辑器点「查看打卡记录」→ 打卡面板详情（与计划页同一套）
    var boardFocus by remember { mutableStateOf<Long?>(null) }
    // 退出面板后回到编辑器（与计划页同口径）
    var boardReturnToEditor by remember { mutableStateOf<Long?>(null) }
    // 2026-11-03 用户：①「未设置时间计划」胶囊点开二级菜单（就地勾选/开进度滑条）；
    // ②启用了百分比（v46 percentDone 0..100）的待办轻点/菜单点按弹百分比悬浮小菜单
    var showUntimedSheet by remember { mutableStateOf(false) }
    // 2026-11-14：只存 **id**，渲染时从 bundle 现取——原来存打开瞬间的快照对象，
    // 期间打卡/改百分比后浮层文案与按钮仍按旧状态显示（实测「卡死在不显示已打卡，
    // 但时间轴上是暗色，点进去又显示已打卡」的帮凶之一）。bundle 随 dataVersion 刷新。
    var percentTodoId by remember { mutableStateOf<Long?>(null) }
    // v48.1：首页打卡后的数据记录录入（与计划页同一套 PendingCheckin/CheckinCaptureDialog）。
    // 此前数据录入只在计划页触发，首页勾完/记录完百分比一直没地方填数据（用户实锤）。
    var pendingCheckin by remember {
        mutableStateOf<io.github.m0use001.planability.ui.plan.PendingCheckin?>(null)
    }
    // 后台冻结（2026-10-30 用户实锤：编辑器这一层也要回来）：时间轴长按打开的编辑弹窗
    // 记到 shell.freeze.editor.home，冷启动按冻结页签恢复。只还原"编辑既有对象"（id>0），
    // 新建表单不还原；首页与计划页各记各的键——两页都常驻组合、弹窗是独立窗口，
    // 合记一个键会让"停在计划页却飘着首页的编辑弹窗"。对象已删则安静不恢复。
    var homeEditorsRestored by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        io.github.m0use001.planability.app.UiFreeze.awaitLoaded()
        if (io.github.m0use001.planability.app.UiFreeze.enabled &&
            Graph.settings.kvGet("shell.freeze.tab") == "home"
        ) {
            val editor = Graph.settings.kvGet("shell.freeze.editor.home").orEmpty()
            val kind = editor.substringBefore(':')
            val id = editor.substringAfter(':', "").toLongOrNull() ?: 0L
            if (id > 0L) {
                when (kind) {
                    "todo" -> if (Graph.plan.todoById(id) != null) editingTodo = id
                    "schedule" -> if (Graph.plan.scheduleById(id) != null) editingSchedule = id
                    "event" -> if (Graph.db.timelineEventDao().byId(id) != null) editingEventId = id
                }
            }
        }
        homeEditorsRestored = true
    }
    LaunchedEffect(homeEditorsRestored, editingTodo, editingSchedule, editingEventId) {
        if (!homeEditorsRestored || !io.github.m0use001.planability.app.UiFreeze.enabled) return@LaunchedEffect
        val v = when {
            (editingTodo ?: 0L) > 0L -> "todo:$editingTodo"
            (editingSchedule ?: 0L) > 0L -> "schedule:$editingSchedule"
            (editingEventId ?: 0L) > 0L -> "event:$editingEventId"
            else -> ""
        }
        Graph.settings.kvPut("shell.freeze.editor.home", v)
    }
    // v23 的「首页点长期待办 → 补交那天」弹窗已去掉：用户 2026-10-25 要求点击一律进编辑器；
    // 补交仍从计划页「已过期」列表进入。
    var showCalendar by remember { mutableStateOf(false) }
    var showCountdowns by remember { mutableStateOf(false) }
    // v19.2：今日评分详情页
    var showScore by remember { mutableStateOf(false) }
    val version by Graph.dataVersion.flow.collectAsState()
    val scope = rememberCoroutineScope()
    // v59 平板：宽屏（≥600dp）首页走「时间轴 + 今日侧栏」双栏——16:10 平板上单列时间轴
    // 会把事件块拉成上千 dp 的横条、右半屏全空（用户截图点名）。窄屏路径一个字不动。
    val wide = io.github.m0use001.planability.core.rememberWindowSizeClass().isWide
    // 时间轴的纵向布局（宽屏上空闲小时被压扁）——**红线定位与滚动回正必须用同一份映射**，
    // 否则「滚到当前时刻」和画出来的现在线会错位（各算一套的老坑）。
    var timelineLayout by remember { mutableStateOf<TimelineLayout?>(null) }
    // v59：自动定位只做一次（切日期才重置）——后台数据刷新不该把人从当前位置拽回去
    var homeAutoScrolled by remember(selectedDate) { mutableStateOf(false) }

    /**
     * v48.1：首页的统一打卡入口——打卡 +（需要手填的数据记录）弹录入框。
     * 打卡前重读一次待办：用户在百分比浮层里刚拖完滑条（onSet 已落库），
     * 用旧的快照打卡会把**旧百分比**写进当天的百分比流水。
     *
     * 2026-11-04 修「切到昨天打卡，待办被滚进未来、今天彻底看不到」：
     * **只借 fresh 的百分比/数据记录字段，实例仍用视图项 t**。重复待办的行在勾选后
     * 已经滚到下一期（date 可能是今天/下周），把整行当"要打卡的那一份"会让滚期从
     * 行的日期再起算一期 → 越过今天（背单词一上午被推到 11-06）。
     */
    val checkInTodo: (io.github.m0use001.planability.data.Todo) -> Unit = { t ->
        scope.launch {
            val fresh = Graph.plan.todoById(t.id) ?: t
            val instance = t.copy(percentDone = fresh.percentDone)
            val wasDone = instance.done
            Graph.plan.completeTodo(instance, selectedDate)
            if (!wasDone && fresh.dataRecordEnabled &&
                io.github.m0use001.planability.data.CheckinKind.needsInput(fresh.dataRecordKind)
            ) {
                pendingCheckin = io.github.m0use001.planability.ui.plan.PendingCheckin(
                    fresh,
                    io.github.m0use001.planability.data.CheckinFormat.blank(
                        fresh, AppTime.dayKey(selectedDate), AppTime.nowMillis()
                    )
                )
            }
        }
    }

    // v59：宽屏侧栏里的待办轻点 = 与时间轴**同一套语义**（百分比待办弹浮层，其余直接打卡）。
    // 不在侧栏另造一条交互路径——同一件事两套手感就是下一批 bug 的温床。
    val paneTodoTap: (io.github.m0use001.planability.data.Todo) -> Unit = { t ->
        if (percentEnabled(t)) {
            haptics.perform(HapticEngine.Kind.Tap)
            percentTodoId = t.id
        } else {
            haptics.perform(HapticEngine.Kind.Select)
            checkInTodo(t)
        }
    }

    // v71（2026-11-21 用户拍板「日历界面改成右移入场覆盖原有界面」）：日历不再 fade-through
    // 整层淡出淡入，改**右移入场覆盖层**——进入时整层从右缘滑入盖住首页，关闭向右滑出。
    // 首页内容常驻底层（不再 if/else 互斥），滑出动画走完才摘掉日历层（[calendarMounted]）。
    val calendarSlide = remember { androidx.compose.animation.core.Animatable(1f) } // 1 = 屏外右缘
    var calendarMounted by remember { mutableStateOf(false) }
    fun setCalendar(to: Boolean) {
        if (to) {
            calendarMounted = true
            showCalendar = true
        } else {
            showCalendar = false
        }
    }
    androidx.compose.runtime.LaunchedEffect(showCalendar) {
        if (showCalendar) {
            calendarSlide.animateTo(0f, io.github.m0use001.planability.ui.common.Motion.pageSlide())
        } else if (calendarMounted) {
            calendarSlide.animateTo(1f, io.github.m0use001.planability.ui.common.Motion.pageSlide())
            calendarMounted = false
        }
    }
    // 2026-11-22 用户：从日历进待办/日程编辑器 → 退出应回到日历。编辑器（窄屏覆盖层 / 宽屏
    // BoardDrill）全部关闭后，若本次编辑来自日历，则重开日历。boardFocus（打卡板）非空时不算
    // 收尾，避免编辑→打卡→返回的中间态误触发。
    androidx.compose.runtime.LaunchedEffect(
        calendarReturnAfterEditor, editingTodo, editingSchedule, editingEventId, boardFocus
    ) {
        if (calendarReturnAfterEditor && editingTodo == null && editingSchedule == null &&
            editingEventId == null && boardFocus == null
        ) {
            calendarReturnAfterEditor = false
            setCalendar(true)
        }
    }
    val categories = remember { mutableStateOf(emptyList<io.github.m0use001.planability.data.CategoryEntity>()) }
    // v12：自动化任务（同步至时间轴）
    var automations by remember { mutableStateOf(emptyList<io.github.m0use001.planability.data.Automation>()) }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        Graph.db.automationDao().observeEnabled().collect { automations = it }
    }

    androidx.compose.runtime.LaunchedEffect(selectedDate, version) {
        bundle = Graph.plan.loadDay(selectedDate)
        categories.value = Graph.plan.allCategories()
    }
    androidx.compose.runtime.LaunchedEffect(selectedDate, version) {
        screenMinutes = loadScreenMinutes(selectedDate)
    }
    // v18.1：最新睡眠记录（首页卡片 + 综合评分）
    var sleepRecord by remember { mutableStateOf<io.github.m0use001.planability.data.SleepRecord?>(null) }
    var sleepBusy by remember { mutableStateOf(false) }
    // v19.2：评分拆解——标题环 / 状态卡入口 / 详情页共用一份计算
    val goalsNow = Graph.settings.snapshot.collectAsState().value
    val bd = remember(bundle, screenMinutes, sleepRecord, goalsNow) {
        scoreBreakdown(
            bundle, screenMinutes, sleepRecord,
            goalsNow.screenLimitMin, goalsNow.screenPenaltyPer30,
            goalsNow.sleepGoalMin, goalsNow.sleepPenaltyPer30
        )
    }
    androidx.compose.runtime.LaunchedEffect(version, selectedDate) {
        // v54.4-P2：睡眠按**选中日**取（dateEpochDay=醒来那天）。历史日不再混入"最近一晚"；
        // 今天仍取最新一条兜底——当天早晨的记录可能还没从健康节点同步过来。
        sleepRecord = if (selectedDate == AppTime.today()) Graph.sleep.latest()
        else Graph.sleep.byDay(selectedDate.toEpochDay())
    }
    // v10：日历面板非一级界面，隐藏底栏
    if (showCalendar) {
        io.github.m0use001.planability.core.LockShellChrome()
    }
    // v19.7：抽屉里的「日历」入口 → 打开月历视图
    androidx.compose.runtime.LaunchedEffect(io.github.m0use001.planability.app.Router.pendingHomeCalendar) {
        if (io.github.m0use001.planability.app.Router.pendingHomeCalendar) {
            io.github.m0use001.planability.app.Router.pendingHomeCalendar = false
            setCalendar(true)
        }
    }
    val navAvoid = io.github.m0use001.planability.app.navAvoidPadding().calculateBottomPadding()

    // v68（2026-11-21「纸条导航」）：上面的「切走本 tab 就收掉覆盖层」watcher 删除——
    // 覆盖层 v68 起是页内层（PredictiveBackOverlay 不再是独立窗口），随本页的纸条一起
    // 滑走、跨切页原样保留（页面记忆）；锁经 LockShellChrome 按 tab 记账、返回手势
    // 由 LocalTabActive 门控，别的 tab 上既不会被锁住也不会被吃返回。
    // （原来收层是为了对付「Dialog 浮在所有 tab 上」：锁不归零藏别人的底栏、
    // BackHandler 替别人吃返回——两个问题都已在壳层根治。）

    // v12：日历面板/倒数日管理是页内覆盖层，右滑返回应关闭它们而不是退出 app。
    // 两个 enabled 显式互斥：倒数日管理是从日历里开的（永远是更内层），先关它；
    // 原来两个 handler 各自只判自己的开关、靠注册顺序定优先级，顺序一改优先级就翻了。
    // v68：两处都加 LocalTabActive 门——本页常驻组合，别的 tab 上不得吃返回。
    val homeTabActive = io.github.m0use001.planability.core.LocalTabActive.current
    androidx.activity.compose.BackHandler(enabled = showCountdowns && homeTabActive) { showCountdowns = false }
    // 日历的三个关闭出口都走 closeCalendar()：跨 tab 打开日历（全局搜索抽屉的「日历」入口）
    // 时会记下来源页，关闭要回来源而不是停在首页 —— 否则就是一层跳过（返回逻辑统一收口）
    fun closeCalendar() {
        setCalendar(false)
        io.github.m0use001.planability.app.ShellNav.returnToSourceTab("home")
    }
    androidx.activity.compose.BackHandler(enabled = !showCountdowns && showCalendar && homeTabActive) { closeCalendar() }

    // M3E 差距 4 试点（2026-10-24 用户精修拍板 → 自研 M3ETitleBar）：大标题随滚动折叠成小标题、
    // 回滚展开。库 LargeFlexibleTopAppBar 是"两枚标题交叉淡化"（中段两份半透明同屏，用户点名
    // 不行）；自研版 = 一枚标题连续缩放（原生 Settings 5x 慢放同款语义）+ 底部圆角浮层分割。
    val homeDensity = androidx.compose.ui.platform.LocalDensity.current
    val homeScroll = rememberScrollState()
    // 折叠分数 = **滚动位置的直接推导**（2026-10-27 结构性重写）：
    // 之前是"nestedScroll 消费拖拽 + 独立 CollapseState + 停稳吸附动画"三套机制共写一个
    // fraction：反复快滑时吸附动画与下一次拖拽互相抢写、判定（canExpand 12dp 闸门）又与
    // 甩动残余速度打架，出现"标题没展开/滑到某处卡住"的一类 bug。
    // 现在只保留**一个真相** scrollState.value：滚到顶部 72dp 行程内标题按比例张开，
    // 其余区间恒折叠。没有独立动画、没有闸门、没有第二个写入者——滑到顶自然放大，
    // 也会随手指连续跟手（原生 TopAppBar 语义）。
    val homeCollapseExtraPx = with(homeDensity) {
        io.github.m0use001.planability.ui.common.M3ETitleBarExtra.toPx()
    }
    val homeCollapseFraction by androidx.compose.runtime.remember(homeCollapseExtraPx) {
        androidx.compose.runtime.derivedStateOf {
            if (homeCollapseExtraPx <= 0f) 1f
            else (homeScroll.value / homeCollapseExtraPx).coerceIn(0f, 1f)
        }
    }

    Box(Modifier.fillMaxSize()) {
        // 状态栏内边距由 M3ETitleBar 自己吃（内部 statusBars）；日历层没有顶栏，由它自己补
        // v71：日历 = **右移入场覆盖层**——首页内容常驻底层（不再淡出/互斥），
        // 日历整层从右缘滑入盖住它，关闭向右滑出，动画走完才离开组合。
        ShellChrome.homeImmersive = showCalendar
        Column(Modifier.fillMaxSize()) {
                val scrollState = homeScroll
                val density = androidx.compose.ui.platform.LocalDensity.current
                // 2026-10-24 浮层化：时间轴铺满全屏、顶部垫出**展开态标题栏**的高度，
                // 滚动时内容从标题栏的圆角下钻过（原生同款"浮在内容上"），而不是被矩形截断
                val headerClear = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() +
                    io.github.m0use001.planability.ui.common.M3ETitleBarPinnedH +
                    io.github.m0use001.planability.ui.common.M3ETitleBarExtra
                // 点底栏「首页」（重复点当前页或从别页切进来）→ 时间线**滑**到聚焦当前时刻：
                // 红线滚到视口垂直中线（红线 y = 时刻比例 × 小时高）。
                // 2026-10-27 用户「点击底部菜单栏回正改成自动滑正，而不是直接刷新到默认位置」：
                // 原 scrollTo 瞬跳读起来像整页重刷；改 animateScrollTo 平滑滑正，
                // 标题折叠分数同步用 standard 弹簧过渡（否则滑完才"啪"地折叠一下）。
                io.github.m0use001.planability.app.OnHomeTabSelect {
                    if (selectedDate == AppTime.today()) {
                        // v59：按时间轴实际布局换算红线位置（宽屏上空闲小时被压扁，
                        // 旧公式 hour*64dp 会把红线指到错误的地方）
                        val now = AppTime.now()
                        val redDp = timelineLayout?.minuteToDp(now.hour * 60 + now.minute) ?: 0f
                        val redPx = with(density) { redDp.dp.toPx() }
                        val target = (redPx - scrollState.viewportSize / 2f)
                            .toInt()
                            .coerceIn(0, scrollState.maxValue)
                        // 折叠分数由 scrollState 推导（见 homeCollapseFraction），滑动过程自带
                        // 折叠/展开过渡，不需要再并行动画一个 fraction。
                        scrollState.animateScrollTo(target)
                    } else {
                        // 翻到别的日期时，红线不在这一天的坐标系里——滑回顶部（旧行为兜底）
                        scrollState.animateScrollTo(0)
                    }
                }
                // 时间轴边缘触感：只在**停稳后**按最终位置结算（2026-10-27）
                // 旧版在滚动过程中按位置判定，甩动过冲帧会命中边界 → 没真正滑到位就振。
                // 现在回到零点附近（≤12dp，与标题完全展开同一判定）或内容真底停稳才振，
                // 同一处停稳只振一次（lastEdge 去重）；标题开合已由 homeCollapseFraction
                // 随滚动位置推导，这里不再有任何动画/状态写入。
                LaunchedEffect(scrollState) {
                    val navAvoidPx = with(density) { navAvoid.toPx() }
                    val nearTopPx = with(density) { 12.dp.toPx() }
                    // -1=还没结算过；0=顶；1=底；2=都不是
                    var lastEdge = -1
                    androidx.compose.runtime.snapshotFlow { scrollState.isScrollInProgress }
                        .collectLatest { inProgress ->
                            if (inProgress) return@collectLatest
                            val v = scrollState.value
                            val max = scrollState.maxValue
                            val atTop = v <= nearTopPx
                            val atBottom = max > 0 && v >= (max - navAvoidPx)
                            val edge = when {
                                atTop -> 0
                                atBottom -> 1
                                else -> 2
                            }
                            // 停稳在边界且与上次停稳位置不同才振（顶轻底重）
                            if (lastEdge != -1 && edge != 2 && edge != lastEdge) {
                                haptics.perform(
                                    if (edge == 0) HapticEngine.Kind.ImpactLight
                                    else HapticEngine.Kind.ImpactHeavy
                                )
                            }
                            lastEdge = edge
                        }
                }
                // 半开吸附（2026-10-27 用户截图）：推导式折叠让"停在半开"成为合法状态——
                // 滚到顶部 72dp 行程中间松手，标题就停在一半、副标题被裁掉半行。
                // 停稳后若落在半开区间，按**最近一端**把滚动位置滑到位（全开或全折）：
                // 动的是 scrollState 本身（唯一真相），不是另一个独立动画，不会再有抢写。
                // 用 collect（不是 collectLatest）：吸附动画自身会把 isScrollInProgress 置真，
                // collectLatest 会在动画途中把执行它的协程取消掉。
                LaunchedEffect(scrollState, homeCollapseExtraPx) {
                    androidx.compose.runtime.snapshotFlow { scrollState.isScrollInProgress }
                        .collect { inProgress ->
                            if (inProgress) return@collect
                            val v = scrollState.value
                            if (homeCollapseExtraPx > 0f && v > 0 && v < homeCollapseExtraPx) {
                                val home = if (v < homeCollapseExtraPx / 2f) 0
                                else homeCollapseExtraPx.toInt()
                                scrollState.animateScrollTo(home)
                            }
                        }
                }
                // 今天时自动滚到当前时段（留 1 小时上文）
                // v59：等时间轴把布局回传上来再定位（布局随数据/压缩规则变化，
                // 拿固定 64dp 公式算会指错位置）。autoScrolled 保证只自动定位一次——
                // 之后用户自己滚到哪儿就停在哪儿，后台数据刷新不该把人拽回当前时刻。
                // 2026-11-19（用户「要自动滚到当前时刻」）：原来首页经常停在 00:00，根因是
                // **首帧 scrollState.viewportSize 还是 0 就把 homeAutoScrolled 定成 true 了**——
                // 那一次 timelineLayout 拿到的 fillHeightDp 也是 0、滚动上限还没算出来，
                // scrollTo 的目标被钳到 0，之后就再也不重试。现在视口没量出来就先不定案。
                LaunchedEffect(selectedDate, timelineLayout, scrollState.viewportSize) {
                    val tl = timelineLayout ?: return@LaunchedEffect
                    if (homeAutoScrolled) return@LaunchedEffect
                    if (scrollState.viewportSize == 0) return@LaunchedEffect
                    homeAutoScrolled = true
                    if (selectedDate == AppTime.today()) {
                        val now = AppTime.now()
                        // 按**分钟**换算（与红线/时间轴同一份映射），不再用整点近似
                        val nowMin = now.hour * 60 + now.minute
                        val targetPx = with(density) {
                            tl.minuteToDp((nowMin - 60).coerceAtLeast(0)).dp.toPx()
                        }
                        scrollState.scrollTo(targetPx.toInt())
                        // 折叠态由 scroll value 推导：滚到当前时段（远离零点）自然就是折叠态
                    } else {
                        scrollState.scrollTo(0)
                    }
                }
                Box(Modifier.weight(1f)) {
                // ---- 时间轴列（窄屏=整窗；宽屏=左板）----
                // topSpacer：窄屏要垫出**浮起标题栏**的高度（内容从栏底圆角下钻过）；
                // 宽屏标题栏换成了板面上的固定行（BoardHeader），它自己占位，
                // 时间轴就在它下面滚，顶部不能再垫一次（否则凭空多一条空白）。
                val timelineColumn: @Composable (Dp) -> Unit = { topSpacer ->
                    Column(
                        Modifier
                            .fillMaxSize()
                            .verticalScroll(scrollState)
                            // 不挂 reachedEndHaptic：时间轴边缘触感在下面按"停稳位置"结算。
                            // 2026-10-27 结构性重写后**不再需要 translationY 补偿**：
                            // 折叠分数直接由滚动位移推导（f = v/extra），标题栏收缩量与内容
                            // 上移量恰好相等 —— 内容恒贴在标题栏下沿。旧版的 -extra·fraction
                            // 是为"消费式折叠"（fraction 与 v 解耦）做的补偿，保留会叠成 2x 位移。
                ) {
                    Spacer(Modifier.height(topSpacer))
                    // ---------- 时间轴（按时间绝对定位；今日评分入口已迁至标题栏胶囊） ----------
                    TimelineGrid(
                        date = selectedDate,
                        bundle = bundle,
                        categories = categories.value,
                        automations = automations,
                        // v59：把纵向布局回传，红线定位/滚动回正与画出来的位置用同一份映射
                        onLayout = { timelineLayout = it },
                        // v59：可视高度传进去——压缩后若比屏幕矮，空闲小时回弹撑满，不留死白
                        viewportHeightDp = with(density) { scrollState.viewportSize.toDp() },
                        onBlockClick = { block ->
                            // 2026-10-29 用户：「轻点待办打卡/取消打卡，长按待办/日程跳转详细编辑」
                            // → 待办轻点改语义为勾选（完成/撤销），导航全部让给长按。
                            when (block.kind) {
                                "todo" -> {
                                    val t = bundle?.todos?.firstOrNull { it.id == block.refId }
                                    if (t != null) {
                                        // 2026-11-03 用户：启用了百分比的待办轻点不再直接勾选——
                                        // 弹百分比悬浮小菜单（滑条+可输入）；其余照旧勾选/撤销
                                        if (percentEnabled(t)) {
                                            haptics.perform(HapticEngine.Kind.Tap)
                                            percentTodoId = t.id
                                        } else {
                                            // 打卡是状态变更 → Select；已完成的重复待办副本 = 撤销那次打卡
                                            haptics.perform(HapticEngine.Kind.Select)
                                            checkInTodo(t)
                                        }
                                    }
                                }
                                "schedule" -> {
                                    haptics.perform(HapticEngine.Kind.Tap)
                                    editingSchedule = block.refId
                                }
                                "event" -> {
                                    haptics.perform(HapticEngine.Kind.Tap)
                                    editingEventId = block.refId
                                }
                                "automation" -> {
                                    haptics.perform(HapticEngine.Kind.Tap)
                                    // 跨 tab 跳转：先记来源 —— 自动化页关闭时要回首页，
                                    // 否则落在信息页根，首页这一层被整层跳过（返回跳级）
                                    io.github.m0use001.planability.app.ShellNav.returnTab = "home"
                                    io.github.m0use001.planability.app.Router.openAutomations()
                                }
                            }
                        },
                        // 2026-10-29：长按 = 跳详细编辑（待办/日程/事件都进编辑器）；
                        // 原来待办长按是完成，已按用户要求与轻点互换。
                        onBlockLongClick = { block ->
                            when (block.kind) {
                                "todo" -> {
                                    haptics.perform(HapticEngine.Kind.Tap)
                                    editingTodo = block.refId
                                }
                                "schedule" -> {
                                    haptics.perform(HapticEngine.Kind.Tap)
                                    editingSchedule = block.refId
                                }
                                "event" -> {
                                    haptics.perform(HapticEngine.Kind.Tap)
                                    editingEventId = block.refId
                                }
                            }
                        }
                    )
                    // v10：悬浮底栏避让（内容延伸到栏后，尾部留白可滚出）
                    Spacer(Modifier.height(navAvoid))
                }
                }
                // 标题栏压在时间轴上层（最后声明 = 最上层），内容滚动时从圆角下钻过。
                // 2026-11-20 v65：宽屏已改走板面固定板头（BoardHeader，见下面的 SplitBoards 分支），
                // 这根浮起栏**只剩窄屏用**——它带滚动折叠（homeCollapseFraction）与展开区，
                // 那是手机路径的既有体验。
                val homeTitleBar: @Composable (Modifier) -> Unit = { barModifier ->
                io.github.m0use001.planability.ui.common.M3ETitleBar(
                    title = if (selectedDate == AppTime.today()) "今天" else selectedDate.formatShort(),
                    subtitle = selectedDate.formatCn(),
                    // 折叠态在「今天」右侧显示「10/25」（用户 2026-10-25：折叠后也要看得到日期）
                    collapsedSuffix = "${selectedDate.monthValue}/${selectedDate.dayOfMonth}",
                    // 展开态第三行：当天日程/待办数量（用户 2026-10-25 要求）。
                    // 数值用主色加粗、名词用中性小字，扫一眼就能读出"今天有几件事"。
                    expandedMeta = {
                        Row {
                            Text(
                                "日程 ",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                "${bundle?.schedules?.size ?: 0}",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                " · 待办 ",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                "${bundle?.todos?.size ?: 0}",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    },
                    collapsedFraction = homeCollapseFraction,
                    modifier = barModifier,
                    navigationIcon = {
                        IconButton(
                            onClick = { haptics.perform(HapticEngine.Kind.Tap); ShellChrome.searchOpen = true },
                            modifier = Modifier.pressScale()
                        ) {
                            // R11：搜索是"入口/导航"图标，不表态 → 中性
                            Icon(AppIcons.Act.Search, contentDescription = "搜索", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    },
                    // v19.6：今日评分 + 完成率合并为一个胶囊；v19.7 放大到 40dp 并改称「评分」
                    // 2026-10-25：从 64dp 工具行右侧挪到底部右侧插槽；当日展开态加了第三行
                    // 「日程/待办数量」后胶囊贴底会骑在日期与数量两行之间（用户「标题栏不整齐」），
                    // 故上移 34dp 让胶囊中心与大标题「今天」那一行同轴。
                    trailing = { ScorePill(bd, onClick = { showScore = true }) },
                    trailingLift = 34.dp,
                    // 点顶栏进日历（原 PageHeader 的整行点击语义保留）；P2-1 走 fade-through
                    onClick = { haptics.perform(HapticEngine.Kind.Tap); setCalendar(true) }
                )
                }
                if (wide) {
                    // 2026-11-20 v65：宽屏首页改走**共享的两板骨架**（SplitBoards）——
                    // 左板=时间轴、右板=今日概览，两块 24dp 圆角板 + 12dp 白缝，
                    // 与计划页/文件页/记忆库同一套材质。原来的 WideSplitPane 是
                    // 「竖线 + 直角栏」，是全 app 最后一批旧网格。
                    // 板头也从浮起 M3ETitleBar 换成 BoardHeader（长在板面上）——
                    // 浮起栏压在板上是第二种材质，正是宽屏「内容块割裂」的来源。
                    // v69「框不动」：根层是两板形态 → 壳层相框显示。
                    // v71 卡顿根修：首页内容 v71 起常驻组合（日历是覆盖层叠在上面），
                    // 这个 SideEffect 与 CalendarView 里的 setBoardFrames(false) 会**每次重组
                    // 都互相翻转**——切视图每次都触发双方重组，壳层框显隐/裁剪盒跟着每帧抖，
                    // 用户看到的就是「每切一次视图卡一下」。现在**单一来源**：home 按
                    // showCalendar 统一写（日历开着=false，关着=按形态），CalendarView 不再写。
                    androidx.compose.runtime.SideEffect {
                        io.github.m0use001.planability.core.ShellChrome.setBoardFrames("home", !showCalendar)
                    }
                    // v76（「二级+全量纳入钻入」批次3）：宽屏根 SplitBoards → BoardDrill。
                    // 首页的二级页（评分 / 待办编辑器 / 日程编辑器 / 自由事件 / 打卡面板 / 倒数日）
                    // 从「整页覆盖层」改为**钻入主板**：上一页（时间轴）缩进副框、今日概览常驻右副框、
                    // Rail 常驻、返回逐级（checkin→回编辑器由 onBack 特判还原）。根层 level=0 几何
                    // 与 SplitBoards 逐像素一致（DrillGeometryTest）。窄屏仍走原覆盖层（下面 !wide）。
                    val homeSubKey: Pair<String, Long>? = when {
                        showScore -> "score" to 0L
                        editingTodo != null -> "todo" to editingTodo!!
                        editingSchedule != null -> "schedule" to editingSchedule!!
                        editingEventId != null -> "event" to editingEventId!!
                        boardFocus != null -> "checkin" to boardFocus!!
                        showCountdowns -> "countdown" to 0L
                        else -> null
                    }
                    io.github.m0use001.planability.ui.common.BoardDrill(
                        detailKey = homeSubKey,
                        onBack = {
                            when {
                                boardFocus != null -> {
                                    val back = boardReturnToEditor
                                    boardFocus = null; boardReturnToEditor = null
                                    if (back != null) editingTodo = back
                                }
                                editingTodo != null -> editingTodo = null
                                editingSchedule != null -> editingSchedule = null
                                editingEventId != null -> editingEventId = null
                                showScore -> showScore = false
                                showCountdowns -> showCountdowns = false
                            }
                        },
                        detailWidth = io.github.m0use001.planability.ui.common.BoardLayout.DetailWidth,
                        detail = { k ->
                            when (k.first) {
                                "score" -> androidx.compose.runtime.CompositionLocalProvider(
                                    // ScoreDetailContent 的返回按钮走 LocalOverlayClose（默认空实现）——瓦片里补上
                                    io.github.m0use001.planability.app.LocalOverlayClose provides { showScore = false }
                                ) {
                                    ScoreDetailContent(
                                        bd = bd, screenMinutes = screenMinutes, sleep = sleepRecord, sleepBusy = sleepBusy,
                                        onEditScreenTime = { m ->
                                            screenMinutes = m
                                            scope.launch { Graph.settings.setManualScreenTime(AppTime.dayKey(selectedDate), m) }
                                        },
                                        onCaptureSleep = {
                                            sleepBusy = true
                                            scope.launch {
                                                val captured = runCatching {
                                                    io.github.m0use001.planability.core.DeviceFeatures.screens.captureSleep?.invoke()
                                                }
                                                sleepRecord = Graph.sleep.latest()
                                                sleepBusy = false
                                                if (captured.getOrNull() == null) {
                                                    android.widget.Toast.makeText(
                                                        io.github.m0use001.planability.data.Graph.appContext,
                                                        captured.exceptionOrNull()?.message
                                                            ?: io.github.m0use001.planability.core.DeviceFeatures.screens.sleepFailureText?.invoke()
                                                            ?: "没抓到睡眠数据",
                                                        android.widget.Toast.LENGTH_LONG
                                                    ).show()
                                                }
                                            }
                                        }
                                    )
                                }
                                "todo" -> TodoEditScreen(
                                    todoId = k.second, presetDate = selectedDate,
                                    onDone = { editingTodo = null },
                                    onOpenCheckin = { tid -> editingTodo = null; boardReturnToEditor = tid; boardFocus = tid },
                                    embedded = true
                                )
                                "schedule" -> ScheduleEditScreen(
                                    scheduleId = k.second, presetDate = selectedDate,
                                    onDone = { editingSchedule = null }, embedded = true
                                )
                                "event" -> TimelineEventEditor(
                                    eventId = k.second, date = selectedDate,
                                    onDone = { editingEventId = null }, embedded = true
                                )
                                "checkin" -> io.github.m0use001.planability.ui.plan.CheckinBoardScreen(
                                    initialTodoId = k.second,
                                    onDone = {
                                        val back = boardReturnToEditor
                                        boardFocus = null; boardReturnToEditor = null
                                        if (back != null) editingTodo = back
                                    },
                                    deferBack = true
                                )
                                "countdown" -> io.github.m0use001.planability.ui.plan.CountdownManageScreen(
                                    onDone = { showCountdowns = false }, deferBack = true
                                )
                            }
                        },
                        main = {
                            Column(Modifier.fillMaxSize()) {
                                io.github.m0use001.planability.ui.common.BoardHeader(
                                    title = if (selectedDate == AppTime.today()) "今天"
                                            else selectedDate.formatShort(),
                                    subtitle = selectedDate.formatCn(),
                                    leading = {
                                        IconButton(
                                            onClick = {
                                                haptics.perform(HapticEngine.Kind.Tap)
                                                ShellChrome.searchOpen = true
                                            },
                                            modifier = Modifier.pressScale()
                                        ) {
                                            // R11：搜索是"入口/导航"图标，不表态 → 中性
                                            Icon(
                                                AppIcons.Act.Search,
                                                contentDescription = "搜索",
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    },
                                    actions = {
                                        // 评分 + 完成率胶囊（原 M3ETitleBar 的 trailing 槽）
                                        ScorePill(bd, onClick = { showScore = true })
                                    },
                                    // 点板头进日历（原「点顶栏进日历」的整行点击语义保留）
                                    onClick = {
                                        haptics.perform(HapticEngine.Kind.Tap)
                                        setCalendar(true)
                                    }
                                )
                                // 板头之下是时间轴本体。板壳已经把状态栏垫掉了，
                                // 这里只需要给**板头自己的高度**让位（时间轴不再从
                                // 浮起标题栏底下钻过去——板头是板面上的固定行）。
                                Box(Modifier.fillMaxSize()) {
                                    // 宽屏：板头是板面上的固定行（BoardHeader），
                                    // 时间轴就在它下面滚 → 顶部不垫任何空间。
                                    timelineColumn(0.dp)
                                }
                            }
                        },
                        secondary = {
                            HomeTodayPane(
                                date = selectedDate,
                                bundle = bundle,
                                bd = bd,
                                onToggleTodo = paneTodoTap,
                                onOpenTodo = { editingTodo = it },
                                onOpenSchedule = { editingSchedule = it },
                                onOpenUntimed = { showUntimedSheet = true },
                                onFocus = { onSelectTab("focus") },
                                // v70i：右划菜单收纳——日历入口迁到副框（原抽屉里的日历行）
                                onOpenCalendar = { setCalendar(true) },
                                // 注意：HomeTodayPane 内部**已经**是 verticalScroll，
                                // 这里不要再套一层（嵌套滚动 = 无限高约束，首帧必崩，2026-11-18 实测）。
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    )
                } else {
                    Box(Modifier.fillMaxSize()) {
                        // 窄屏：浮起标题栏压在时间轴上，内容从栏底圆角下钻过
                        // → 时间轴顶部要垫出整条标题栏的高度（含状态栏与展开区）。
                        timelineColumn(headerClear)
                        homeTitleBar(Modifier.align(Alignment.TopCenter))
                    }
                }
            }
        }

        // v71：日历覆盖层（右移入场）。translationX 由 calendarSlide 驱动：1=屏外右缘、0=盖住首页。
        // 只在 calendarMounted 期间组合（滑出动画结束即摘），首页内容全程在底层不动。
        if (calendarMounted) {
            Column(
                Modifier
                    .fillMaxSize()
                    // 覆盖层必须自铺不透明底：它是叠在常驻首页之上的层，不铺底就会
                    // 「两层内容互相透」——首页标题栏「今天」/评分胶囊/右栏会从日历缝里钻出来
                    // （首版实拍就是这个：日历没有底，首页的顶栏和侧栏都浮在上面）。
                    .background(MaterialTheme.colorScheme.background)
                    .graphicsLayer { translationX = size.width * calendarSlide.value }
                    .statusBarsPadding()
            ) {
                CalendarView(
                    selected = selectedDate,
                    onPick = {
                        selectedDate = it
                        closeCalendar()
                    },
                    onCountdowns = { showCountdowns = true },
                    onBack = { closeCalendar() },
                    onEditTodo = { id ->
                        calendarReturnAfterEditor = true
                        closeCalendar()
                        editingTodo = id
                    },
                    onEditSchedule = { id ->
                        calendarReturnAfterEditor = true
                        closeCalendar()
                        editingSchedule = id
                    }
                )
            }
        }

        // v12 批F：首页/日历不再显示 + 号按钮（新建走时间轴点击或计划页）
        // 2026-11-02 用户：无时间待办在时间轴上排不了位（TimelineGrid 只画 due>0），
        // 等于"建了但首页看不见"——底栏上方加「未设置时间计划：N」胶囊汇总，
        // 与剪贴板条同列堆叠（胶囊在上），点胶囊去计划页待办区补时间。
        // v59：宽屏下这两枚改挂到右栏底部（由 HomeTodayPane 渲染），
        // 否则会浮在「时间轴 / 侧栏」分栏缝的正中间。
        if (!wide) Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = navAvoid + 14.dp)
        ) {
            val untimed = bundle?.todos?.let { untimedPendingCount(it) } ?: 0
            // v48.4（真机「填完数据又改不了百分比」）：胶囊此前只在**未完成数>0**时出现——
            // 打卡后唯一入口消失，没设时间的百分比待办在首页彻底打不开浮层。
            // 现在只要有没设时间的待办（含已打卡）就显示胶囊；计数仍只数未完成，
            // 而已打卡的百分比待办会在二级菜单里以「已完成」出现，点它可以直接调进度。
            val hasUntimed = bundle?.todos?.any { it.due <= 0 } ?: false
            androidx.compose.animation.AnimatedVisibility(
                visible = hasUntimed,
                enter = io.github.m0use001.planability.ui.common.Motion.expandIn,
                exit = io.github.m0use001.planability.ui.common.Motion.collapseOut
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    UntimedPlanCapsule(
                        count = untimed,
                        onClick = {
                            haptics.perform(HapticEngine.Kind.Tap)
                            showUntimedSheet = true
                        }
                    )
                    Spacer(Modifier.height(8.dp))
                }
            }
            ClipboardBar(
                enabled = Graph.settings.snapshot.value.clipboardEnabled,
                onImport = { editingTodo = 0L }
            )
        }
    }

    // v19.2：今日评分详情页（计算过程 + 扣分/加分项 + 睡眠 + 屏幕）。
    // v74（2026-11-22 用户平板截图「评分页和时间轴叠在一起」）：本层原来组合在页面主体
    // Box **之前**——v19.2 时代它是独立 Dialog 窗口，代码顺序与层级无关；v68 页内化后
    // Compose「后组合者画在上」，主体的时间轴日程块、「今日概览」右栏整层盖在评分页上
    // （计算框的「完成率基础分」从日程块缝里透出来）。PredictiveBackOverlay 的 KDoc 本就
    // 要求「与页面主体平级、排在其后」——移到主体 Box 之后、编辑器之前（评分页开着时
    // 主体被盖住、编辑器无同开路径，顺序只影响极端情况的叠层方向）。
    if (showScore && !wide) {
        io.github.m0use001.planability.app.PredictiveBackOverlay(onBack = { showScore = false }) {
            ScoreDetailContent(
                bd = bd,
                screenMinutes = screenMinutes,
                sleep = sleepRecord,
                sleepBusy = sleepBusy,
                onEditScreenTime = { m ->
                    screenMinutes = m
                    scope.launch { Graph.settings.setManualScreenTime(AppTime.dayKey(selectedDate), m) }
                },
                onCaptureSleep = {
                    sleepBusy = true
                    scope.launch {
                        val captured = runCatching {
                            io.github.m0use001.planability.core.DeviceFeatures.screens.captureSleep?.invoke()
                        }
                        sleepRecord = Graph.sleep.latest()
                        sleepBusy = false
                        // 失败必须出声：采集失败曾只写 logcat，刷新钮转一下就停，用户视角=「点了没反应」
                        // （2026-10-29 报告就是它：装新包把无障碍服务打死后的静默 null）
                        if (captured.getOrNull() == null) {
                            android.widget.Toast.makeText(
                                io.github.m0use001.planability.data.Graph.appContext,
                                captured.exceptionOrNull()?.message
                                    ?: io.github.m0use001.planability.core.DeviceFeatures.screens.sleepFailureText?.invoke()
                                    ?: "没抓到睡眠数据",
                                android.widget.Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                }
            )
        }
    }

    // ---------- 编辑器 ----------
    // 各开各的：id 非空即打开，关的时候只清自己那个 id —— 一次返回只退一层
    editingTodo?.takeIf { !wide }?.let { id ->
        TodoEditScreen(
            todoId = id, presetDate = selectedDate,
            onDone = { editingTodo = null },
            onOpenCheckin = { tid ->
                editingTodo = null
                boardReturnToEditor = tid
                boardFocus = tid
            }
        )
    }
    boardFocus?.takeIf { !wide }?.let { tid ->
        io.github.m0use001.planability.ui.plan.CheckinBoardScreen(
            initialTodoId = tid,
            onDone = {
                val back = boardReturnToEditor
                boardFocus = null
                boardReturnToEditor = null
                if (back != null) editingTodo = back
            }
        )
    }
    // 2026-11-03：未设置时间计划二级菜单 + 百分比悬浮小菜单（见 HomeQuickSheets.kt）
    if (showUntimedSheet && bundle != null) {
        UntimedPlanSheet(
            todos = bundle!!.todos,
            onDismiss = { showUntimedSheet = false },
            onToggleDone = { t ->
                haptics.perform(HapticEngine.Kind.Select)
                // v48.1：走统一入口——需要手填的数据记录会弹录入框
                checkInTodo(t)
            },
            // 二级菜单里点百分比行：收起菜单再弹浮层（Dialog 可叠 sheet，顺序无碍，
            // 但收起让浮层成为唯一前景更干净）
            onOpenPercent = { t ->
                showUntimedSheet = false
                percentTodoId = t.id
            }
        )
    }
    // 2026-11-05 用户「同时开百分比与数据记录时出现两个保存按钮」：
    // 打卡记录弹窗一起来的时候不再渲染百分比浮层——两个都是 Dialog，
    // 同时在组合里时底下的「确定」和上面的「保存」会一起出现在屏幕上。
    // 用互斥而不是靠时序（percentTodoId = null 后紧接着开新弹窗，慢机型上仍会叠一帧甚至更久）。
    if (pendingCheckin == null) percentTodoId?.let { pid ->
        // 每次重组从 bundle 现取（bundle 随 dataVersion 刷新）——浮层文案/按钮永远跟随最新状态
        bundle?.todos?.firstOrNull { it.id == pid }?.let { t ->
            PercentQuickMenu(
                todo = t,
                onDismiss = { percentTodoId = null },
                onSet = { v ->
                    haptics.perform(HapticEngine.Kind.Select)
                    // 2026-11-04：进度按**所看那天**落账（原来缺省写今天——切到昨天调滑条，
                    // 今天凭空多出一条打卡记录）。行的 percentDone 只在今天才改。
                    scope.launch { Graph.plan.setPercentDone(t.id, v, AppTime.dayKey(selectedDate)) }
                },
                // v48.1：浮层里直接打卡。2026-11-14：滑条值随回调进来，**挂起串行**——
                // 先落进度再打卡；原来 onSet/check 两条协程并发，打卡时可能把旧百分比
                // 写进当天的百分比流水。
                onCheckIn = { v ->
                    haptics.perform(HapticEngine.Kind.Select)
                    percentTodoId = null
                    scope.launch {
                        Graph.plan.setPercentDone(t.id, v, AppTime.dayKey(selectedDate))
                        checkInTodo(t)
                    }
                },
            // v48.3：已打卡后「改记录数据」——预填当天已有记录，保存走同日覆盖
            // （浮层侧已加门：没开数据记录的待办不显示这个入口）
            onEditData = {
                haptics.perform(HapticEngine.Kind.Tap)
                percentTodoId = null
                scope.launch {
                    val fresh = Graph.plan.todoById(t.id) ?: t
                    val day = selectedDate
                    val existing = runCatching { Graph.plan.checkinOn(fresh.id, day) }.getOrNull()
                    pendingCheckin = io.github.m0use001.planability.ui.plan.PendingCheckin(
                        fresh,
                        existing ?: io.github.m0use001.planability.data.CheckinFormat.blank(
                            fresh, AppTime.dayKey(day), AppTime.nowMillis()
                        )
                    )
                }
            },
            // v48.3：已打卡后「撤销打卡」（仓储层会一并删掉当天的数据记录与百分比流水）
            onUndoCheckIn = {
                haptics.perform(HapticEngine.Kind.Select)
                percentTodoId = null
                scope.launch {
                    // v48.6 修「撤销打卡没反应」：**不能**重读原行！重复待办打卡后原行已滚到
                    // 下一期（done=false、date=下周），今天时间轴上的是显示副本（done=true、
                    // date=今天）。拿原行去 completeTodo 会被当成"再次打卡"，撤销永远不生效。
                    // completeTodo 的撤销分支本来就要求传「那一天的显示副本」（见仓储注释）。
                    Graph.plan.completeTodo(t, selectedDate)
                }
            }
            )
        }
    }
    // v48.1：打卡后的数据记录录入（与计划页同一套组件）
    pendingCheckin?.let { p ->
        io.github.m0use001.planability.ui.plan.CheckinCaptureDialog(
            todo = p.todo,
            draft = p.draft,
            onDismiss = {
                pendingCheckin = null
                percentTodoId = null   // 关掉录入框别把百分比浮层"放回来"
            },
            onSave = { rec ->
                scope.launch { Graph.plan.addCheckin(rec) }
                pendingCheckin = null
                percentTodoId = null
            }
        )
    }
    editingSchedule?.takeIf { !wide }?.let { id ->
        ScheduleEditScreen(scheduleId = id, presetDate = selectedDate, onDone = { editingSchedule = null })
    }
    editingEventId?.takeIf { !wide }?.let { id ->
        TimelineEventEditor(eventId = id, date = selectedDate, onDone = { editingEventId = null })
    }

    if (showCountdowns && !wide) {
        io.github.m0use001.planability.ui.plan.CountdownManageScreen(onDone = { showCountdowns = false })
    }

    // Router：小组件请求打开倒数日管理
    // 键必须是标志本身（不是 Unit）：NavShell 用 beyondViewportPageCount = tabs.size 让 7 个 tab 页
    // 常驻组合，本页早在首帧就组合过、LaunchedEffect(Unit) 已经跑完，之后标志翻转不会重跑 ——
    // 小组件/快捷方式点过来只会切 tab、弹窗永远不开（同 InfoCenterScreen 的 pendingPhoneUseRegionOpen，
    // phone-a 已在那边修过一处，这里是他没覆盖到的另一条路径）。
    LaunchedEffect(Router.pendingCountdownOpen) {
        if (Router.pendingCountdownOpen) {
            Router.pendingCountdownOpen = false
            showCountdowns = true
        }
    }

    // 2026-11-01：小组件直达页内覆盖层（倒数日组件 / 多天总览组件）。
    // 与上面 pendingCountdownOpen 分开：那条是「新建倒数日」快捷方式，语义是"去管理"；
    // 这两条是"用户点了桌面上的这个组件，把他带到组件对应的界面"。
    // 注意顺序：**先开页面、再清标志**。反过来会有个隐蔽死锁——
    // 清标志改的是本 effect 的 key，state 一变 effect 立刻重启并 cancel 掉当前协程，
    // 后面的 delay/开页永远跑不到（2026-11-01 实测：冷启动能开、热切换开不了）。
    LaunchedEffect(Router.pendingWidgetCountdown) {
        if (Router.pendingWidgetCountdown) {
            showCountdowns = true
            Router.pendingWidgetCountdown = false
        }
    }
    LaunchedEffect(Router.pendingWidgetCalendar) {
        if (Router.pendingWidgetCalendar) {
            setCalendar(true)
            Router.pendingWidgetCalendar = false
        }
    }
}
