package io.github.m0use001.planability.app

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.automirrored.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.MenuBook
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.m0use001.planability.core.AppTime
import io.github.m0use001.planability.core.HapticEngine
import io.github.m0use001.planability.core.LocalHaptics
import io.github.m0use001.planability.core.LockShellChrome
import io.github.m0use001.planability.core.ShellChrome
import io.github.m0use001.planability.core.WindowSizeClass
import io.github.m0use001.planability.core.rememberShellRailForm
import io.github.m0use001.planability.core.rememberWindowSizeClass
import io.github.m0use001.planability.data.DEFAULT_TABS
import io.github.m0use001.planability.data.Graph
import io.github.m0use001.planability.ui.common.boardFrameViewport
import io.github.m0use001.planability.ui.focus.FocusScreen
import io.github.m0use001.planability.ui.home.HomeScreen
import io.github.m0use001.planability.ui.info.InfoCenterScreen
import io.github.m0use001.planability.ui.notes.NotesScreen
import io.github.m0use001.planability.ui.plan.PlanScreen
import io.github.m0use001.planability.ui.profile.ProfileScreen
import kotlinx.coroutines.launch
import kotlin.math.abs

fun tabSpecs(): Map<String, TabSpec> = mapOf(
    "home" to TabSpec("home", "首页", Icons.Rounded.Home),
    "plan" to TabSpec("plan", "计划", Icons.Rounded.CalendarMonth),
    "focus" to TabSpec("focus", "专注", Icons.Rounded.Timer),
    "notes" to TabSpec("notes", "笔记", Icons.Rounded.MenuBook),
    "info" to TabSpec("info", "信息", Icons.Rounded.Info),
    "profile" to TabSpec("profile", "我的", Icons.Rounded.Person)
)

/**
 * 壳层导航的跨文件共享状态 —— 只放「页面自己看不见、但必须知道」的两件事。
 *
 * 背景：NavShell 用 `beyondViewportPageCount = tabs.size` 让 7 个 tab 页**常驻组合**，
 * 切 tab 只是把 pager 翻过去，离开的页并不销毁。于是：
 *  ① 页内子页（首页日历 / 倒数日管理 / 评分详情）在别的 tab 上仍然挂着：lockCount
 *     不归零（新 tab 上底栏不出现），它的 BackHandler 也还活着 —— 按返回先被这个
 *     看不见的层白吃一次，用户以为没反应；
 *  ② `Router.openXxx()` 的形状是「先切目标 tab，再由目标页开子页」，子页返回后落在
 *     目标 tab 根，发起跳转的那一层（我的 / 首页）被整层跳过。
 * 这两件事页面自己都判断不了，只能由壳层告诉它。
 */
object ShellNav {
    /**
     * 当前停在哪个一级页（tab id）。
     *
     * 只在**切页入口**写（Router 深链 / 底栏点按与拖动吸附 / 全局返回兜底），
     * 刻意不读 pagerState.currentPage —— 翻页动画期间 currentPage 会一路跳过中间页，
     * 拿过路值当"当前"会让留在原页的子页在动画途中被误关（日历刚被小组件打开就被关掉）。
     */
    var currentTab: String
        get() = tabState.value
        set(value) {
            tabState.value = value
            // 后台冻结：切页即记（UiFreeze.save 内部判开关；appScope 保证落库不随组合取消）
            if (value.isNotEmpty()) UiFreeze.save(UiFreeze.KEY_TAB, value)
            // v69b：框显隐按 tab 记账后，切页不再需要全局复位（首页多形态只写 home 自己的账）。
        }

    /** [currentTab] 的快照态本体；set 走上面的自定义 setter 顺带写冻结 KV */
    private val tabState = mutableStateOf("")

    /**
     * 跨 tab 打开子页时记下的**来源 tab**（返回不跳级用）。
     * 发起方在调 Router 之前置入，目标页在子页 onDone 里调 [returnToSourceTab] 消费；
     * 底栏手动切页时作废 —— 人已经自己换了页，"从哪来"不再有意义，留着只会误导后续返回。
     */
    var returnTab: String? = null

    /**
     * 子页关闭时调用：返回**先回来源 tab**，不落在目标 tab 根。
     * 没记过来源、或来源就是自己这一层时什么都不做。
     *
     * @param self 子页所在的 tab id（信息页子页传 "info"）
     */
    fun returnToSourceTab(self: String) {
        val src = returnTab ?: return
        returnTab = null
        if (src != self) Router.targetTab = src
    }
}

@Composable
fun AppShell() {
    val settings by Graph.settings.snapshot.collectAsState()
    val visible = settings.visibleTabs.ifEmpty { DEFAULT_TABS }
    val specs = tabSpecs()
    // 文件页签撤除（2026-10-24 用户拍板）：文件功能并入信息页文件库（InfoCenter 的
    // showFileLibrary），同步中心入口移信息首页胶囊；tabSpecs 已无 "files"，存量设置里的
    // files 由 mapNotNull 自然丢弃，排序屏宇宙同步收窄。FilesScreen 源码暂留未删（B 线属地）。
    // 笔记若被排序屏隐藏仍常驻 pager：深链/小组件的 "notes" 目标不死。
    val tabs = visible.mapNotNull { specs[it] } +
        listOf("notes").filter { it !in visible }.mapNotNull { specs[it] }
    val barTabs = visible.mapNotNull { specs[it] }
    val scope = rememberCoroutineScope()
    val pagerState = rememberPagerState(pageCount = { tabs.size })
    var baseTab by remember { mutableStateOf(0) }
    // v16.2 底栏滑切吸附式跟手：记录拖动中已翻到的页与其滑动动画，跨按钮边界时带动画翻页
    var dragPage by remember { mutableIntStateOf(-1) }
    var dragAnimJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }

    // 切页动效现状（2026-11-21 v68「纸条导航」用户拍板，覆盖 2026-10-29 的横滑拍板）：
    // - **底栏/Rail 点按 = 纵向滑动跨越中间页**（slideSwitchTo）——壳层是「相框」，
    //   七个一级页是压在框下的长纸条，切页 = 抽纸条上下动：Rail 上方的 tab 内容向下让位，
    //   下方的向上让位（VerticalPager 的语义天然如此）；
    // - **深链 / 返回兜底 / 内容里的跨 tab 跳转 = top-level quick fade**（fadeSwitchTo）：
    //   这些不是用户手动抽纸条，纵滑会让"跳转"看起来像翻书；
    // - **「按住拖动切页」手势**（用户 2026-10-23 定制）保留，路径仍走 pager 跟手 + 吸附
    //   （v68 起拖动方向随 pager 变为纵向）。
    // pager 本体不动（7 页常驻的状态保持机制不变），fade 只作用在包裹层 alpha 上。
    val pageAlpha = remember { Animatable(1f) }
    var fadeJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    fun fadeSwitchTo(pagerIdx: Int) {
        fadeJob?.cancel()
        fadeJob = scope.launch {
            pageAlpha.animateTo(0f, io.github.m0use001.planability.ui.common.Motion.quickEffects())
            pagerState.scrollToPage(pagerIdx)
            pageAlpha.animateTo(1f, io.github.m0use001.planability.ui.common.Motion.quickEffects())
        }
    }

    /**
     * 底栏/Rail 点按切页 = **纵向滑动跨越中间页**（v68「纸条导航」，2026-11-21 用户拍板，
     * 覆盖 2026-10-29 的横滑要求）："框不动、内容动"——壳层是相框，tab 页是压在框下的
     * 长纸条，切页抽纸条上下动；跨过的中间页真实渲染（七页常驻），长距离抽动不空白。
     *
     * 实现就是 pager 自己的 `animateScrollToPage` —— 7 页常驻组合（beyondViewportPageCount），
     * 途经的每一页都真实渲染，手指看到的确实是中间界面而不是空白。
     *
     * ## 非线性（2026-10-29 用户第二条：「切换动画改成非线性动画」；2026-11-08 拍板 tween→弹簧）
     * 最早一版是"每跨一页固定 +180ms"：时长与距离成正比 = 全程**匀速**，像被平推过去。
     * 二版改 tween+emphasized 曲线（起手快收尾长减速）+ 分段加时。**三版（现状）= 弹簧**
     * （[Motion.pageSlide]，临界阻尼零过冲）：
     * - 弹簧按物理积分，天然满足"长距离靠初速快解决、不靠拉长时间"——分段加时公式退役；
     * - 刚度与 standard 档同源（380），短距离沉降 ≈200ms、与 tween 版量级一致；
     * - **临界阻尼（零过冲）是页级专属**：过冲意味着先滑过头、露出隔壁页边缘再弹回来，
     *   整屏切换会显得不稳——与元素级（胶囊/行）的轻过冲刻意不同。
     *
     * 与 fadeSwitchTo 并存：深链/返回兜底仍是 quick fade（那两处不是用户手动横移，
     * 且深链常从冷启动来，横滑会让"跳转"看起来像翻书）。
     */
    var slideJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    fun slideSwitchTo(pagerIdx: Int) {
        slideJob?.cancel()
        fadeJob?.cancel()
        slideJob = scope.launch {
            // 上一次 fade 若停在半透明，先归位，别让滑动过程整层发灰
            if (pageAlpha.value < 1f) pageAlpha.animateTo(1f, io.github.m0use001.planability.ui.common.Motion.quickEffects())
            // v71（2026-11-21 用户「跨大幅度页面跳转也要做好动画，中间所有跳过的界面都要画出来」）：
            // 相邻页 = 弹簧（pageSlide，落位手感不变）；**跨多页 = emphasized tween 按距离加时**——
            // 弹簧的初速主导让长距离与短距离耗时几乎一样，中间页一闪而过等于没画；
            // tween 的匀速段让纸条**逐页掠过**每一块中间板（7 页常驻都在组合，途经页逐帧真实渲染），
            // 收尾 emphasized 减速落位。时长 = 340ms + 每多跨一页 +120ms，封顶 940ms。
            val dist = kotlin.math.abs(pagerIdx - pagerState.currentPage)
            val spec: androidx.compose.animation.core.FiniteAnimationSpec<Float> =
                if (dist <= 1) io.github.m0use001.planability.ui.common.Motion.pageSlide()
                else androidx.compose.animation.core.tween(
                    durationMillis = (340 + 120 * (dist - 1)).coerceAtMost(940),
                    easing = androidx.compose.animation.core.CubicBezierEasing(0.2f, 0f, 0f, 1f)
                )
            pagerState.animateScrollToPage(pagerIdx, animationSpec = spec)
        }
    }

    // v57 平板适配：宽屏走左侧 NavigationRail，窄屏走药丸底栏。
    // v64 根修（2026-11-20）：宽度改**实测**（LocalWindowInfo.containerSize）。
    // 原来读 `LocalConfiguration.screenWidthDp`——分屏 / 自由窗口 / 虚拟屏里它报的是
    // **显示器**宽，一个 452dp 的窗口被当成 1600dp 宽屏，Rail + 340dp 侧栏挤得内容
    // 只剩一条缝。并且宽屏形态只在实测 ≥840dp 出现（用户拍板）：600–840dp 的
    // 小平板 / 分屏退回手机形态（药丸底栏 + 右划抽屉），屏上不再同时有两套导航。
    val wide = rememberShellRailForm()

    /**
     * 切页的统一入口（底栏点按与 Rail 点按共用）。再点当前页 = 回顶（iOS/微信手感）。
     * 抽出来是为了底栏与 Rail 不各写一份——onDragProgress（拖动吸附）仍是底栏专属。
     */
    fun selectTab(barIdx: Int) {
        baseTab = barIdx
        dragPage = -1
        // 手动切页：来源记录作废（人已经自己换了页，"从哪来"不再有意义）
        ShellNav.returnTab = null
        val tabId = barTabs.getOrNull(barIdx)?.id
        val pagerIdx = tabs.indexOfFirst { it.id == tabId }.coerceAtLeast(0)
        val currentId =
            tabs.getOrNull(pagerState.settledPage.coerceIn(0, tabs.size - 1))?.id
        if (tabId != null && tabId == currentId) {
            TopScroll.request(tabId)
        }
        ShellNav.currentTab = tabId ?: ShellNav.currentTab
        dragAnimJob?.cancel()
        slideSwitchTo(pagerIdx)
    }

    // 首帧记一次当前页；之后只在切页入口更新（理由见 ShellNav.currentTab 的注释）。
    // 必须排在下面 Router 收集器**之前**：AppShell 组合前 targetTab 可能已被
    // MainActivity 的深链置上，先启动的这个先把 currentTab 定成当前页，
    // 再由收集器改成目标页；顺序反过来会被这里覆盖回旧页。
    //
    // 后台冻结（2026-10-30 用户需求）：装填开关 + 冷启动恢复上次页签。恢复必须
    // 在 Router 收集器之前、且深链在场时让位（小组件点进来应该去它点的页，
    // 不是回到"上次"）；scrollToPage 是瞬时定位，冷启动第一帧仍显示首页，
    // 但开屏动画盖住了这一帧。pagerState 自带的 saveable 只在"带实例状态重建"
    // （最近任务）路径生效，启动器冷启动（ColorOS 杀后台后的重进）拿不到。
    LaunchedEffect(Unit) {
        UiFreeze.load()
        if (UiFreeze.enabled && Router.targetTab == null) {
            val frozen = Graph.settings.kvGet(UiFreeze.KEY_TAB)
            val idx = tabs.indexOfFirst { it.id == frozen }
            if (frozen != null && idx >= 0 && idx != pagerState.currentPage) {
                pagerState.scrollToPage(idx)
            }
        }
        ShellNav.currentTab = tabs.getOrNull(pagerState.currentPage)?.id ?: ShellNav.currentTab
    }

    // Router 请求切换 tab
    LaunchedEffect(Unit) {
        snapshotFlow { Router.targetTab }.collect { target ->
            if (target != null) {
                val idx = tabs.indexOfFirst { it.id == target }
                if (idx >= 0) {
                    // 先记目标页再翻页：留在原页的子页要立刻知道外壳已经切走了
                    //（它据此收掉自己的覆盖层，见 ui/home/HomeScreen.kt）
                    ShellNav.currentTab = target
                    // v70f（2026-11-21 用户「跨页跳转按钮也要接入上下切换的页面动画」）：
                    // 动效按跳转来源分——UI 里的跨页跳转按钮（含二级界面里的，如
                    // 我的→手机操控区域）= 纵滑抽纸条，与 Rail/底栏点按同款
                    //（Router.pendingJumpSlide 默认 true）；系统深链（小组件/分享/通知）
                    // 置 false 仍走 quick fade（v68 拍板保留）。consumeTab 读后归位。
                    val slide = Router.pendingJumpSlide
                    if (slide) slideSwitchTo(idx) else fadeSwitchTo(idx)
                }
                Router.consumeTab()
            }
        }
    }

    // v12：全局返回兜底——非首页一级界面右滑/返回回首页，杜绝未覆盖状态手势直接退出 app
    // 修「跳级」：首页索引不能写死 0 —— 底栏顺序与显隐是可配的（我的 → 底栏排序），
    // home 可以被挪到别的位置、也可以被隐藏，第 0 页未必是首页，写死 0 会把返回送到错的页。
    // home 被隐藏时退回第一个可见页：仍不越过一级页直接退 app，只是回"最前面那一页"。
    val homeIndex = tabs.indexOfFirst { it.id == "home" }.coerceAtLeast(0)
    androidx.activity.compose.BackHandler(
        // v68：锁按 tab 记账——别的 tab 的页内子页不得挡住全局返回兜底。
        enabled = pagerState.currentPage != homeIndex &&
            ShellChrome.effectiveLock(ShellNav.currentTab) == 0
    ) {
        ShellNav.currentTab = tabs.getOrNull(homeIndex)?.id ?: ShellNav.currentTab
        fadeSwitchTo(homeIndex)
    }

    // v12 批F：全局搜索改信息页同款右滑抽屉（壳包裹全部一级页）；
    // 信息页让位给页内工作台抽屉（两套缘边手势在同一 Initial pass 同阈值，会一次右滑双开）
    // v19.7：再加两条闸门——① lockCount>0（页内二级视图如笔记详情/文件子目录）不响应，
    // 全局搜索只在「一级界面」生效；② 日历模式（首页第二形态）同样让位给月历的横向滑动
    val pageId = tabs[pagerState.currentPage.coerceIn(0, tabs.size - 1)].id
    // 当前页在底栏/Rail 宇宙里的高亮下标（笔记/文件从抽屉进入时 = -1，如实表达「不在任何目的地」）
    val currentBarIdx = barTabs.indexOfFirst {
        it.id == tabs.getOrNull(pagerState.currentPage.coerceIn(0, tabs.size - 1))?.id
    }
    // 一级界面本体（pager + 底栏）：底栏只在 Compact 出现，宽屏走 Rail（见下方 Row 分叉）。
    // v67 起搜索抽屉宿主统一包在 fork 外层（整窗级），Rail 在抽屉遮罩之下不再中途退场。
    // v67（2026-11-21 用户三条拍板）：搜索抽屉**上提到整窗层**（见下方 fork 处统一包），
    // 宽屏 sheet 从真正屏幕左缘滑出、遮罩罩住 Rail——不再因 LockShellChrome 收 Rail
    // 造成「容器中途变宽」的弹出缝隙。这里 shellBody 只剩页面本体。
    // 抽屉开着时不再让边缘开手势重复触发（原来靠 LockShellChrome 抬 lockCount 间接挡）。
    val searchEdgeEnabled = settings.searchEdgeEnabled && pageId != "info" &&
        ShellChrome.effectiveLock(pageId) == 0 && ShellChrome.homeImmersive.not() &&
        ShellChrome.searchOpen.not()
    val shellBody: @Composable () -> Unit = {
        Box(Modifier.fillMaxSize()) {
            // v69「框不动」（2026-11-21 用户修正：v68 的整页滚动把两块板的框边也带动了，
            // 切页中途框在跳）：宽屏时两块圆角板的**相框钉在壳层**（恒定不动），页面内容
            // 作为「纸条」画在框上、在框区裁剪盒里纵向滑动。框只在两板形态显示
            // （首页日历的 3 天/周/年视图是单列 → ShellChrome.boardFrames=false 时框与
            // 裁剪盒都退回整页区）。框的几何直接复用 SplitBoards 空内容版本——与页面
            // 透明板逐像素同源，永远不会错位。
            // v69b：框显隐**按 tab 记账**——首页日历单列形态只影响 home，不污染其余 tab
            val homeBoardFrames = ShellChrome.boardFramesFor(ShellNav.currentTab)
            if (wide && homeBoardFrames) {
                io.github.m0use001.planability.ui.common.SplitBoards(twoPane = true, primary = {}, secondary = {})
            }
            // 内容裁剪盒：框显 = 框区（外距/inset 公式与框同源，见 boardFrameViewport），
            // 纸条滑出框缘即被裁——「相框压住纸条」的物理含义。
            Box(
                Modifier
                    .fillMaxSize()
                    .then(
                        if (wide && homeBoardFrames) {
                            Modifier.boardFrameViewport()
                        } else {
                            Modifier
                        }
                    )
            ) {
                // v68：HorizontalPager → VerticalPager（纸条上下抽）。pager API 轴无关：
                // slideSwitchTo/fadeSwitchTo/底栏拖动吸附/beyondViewportPageCount 七页常驻全不动，
                // 只有方向变了——「点 Rail 上方的 tab，内容向下让位」由 VerticalPager 天然给出。
                // v70j（2026-11-21 用户「手机模式下页面切换应该保留左右切换设定」）：
                // 纸条导航的纵向抽只属于宽屏「框不动」物理（相框 + 上下墙）；手机没有相框，
                // 保留 10-29 拍板的**左右横切**。pagerState 轴无关——slideSwitchTo/fade/
                // 底栏拖动吸附/七页常驻全不动，只有抽动轴向按形态分（宽=Vertical 纸条上下抽，
                // 手机=Horizontal 左右翻页）。
                val pageContent: @Composable androidx.compose.foundation.pager.PagerScope.(Int) -> Unit = { page: Int ->
                    // v68「纸条导航」需求 3：给每页提供自己的 tab 身份与「是否当前页」。
                    // 页内层（PredictiveBackOverlay / LockShellChrome）靠 LocalTabId 把锁记到
                    // 自己那一页名下（「我的」开着子页切去「专注」，专注页的底栏/Rail 照常工作）；
                    // 页内 BackHandler / 预测返回靠 LocalTabActive 门控（七页常驻组合，别的 tab
                    // 上的返回层不得替当前页吃系统返回）。
                    val pageTabId = tabs[page].id
                    androidx.compose.runtime.CompositionLocalProvider(
                        io.github.m0use001.planability.core.LocalTabId provides pageTabId,
                        io.github.m0use001.planability.core.LocalTabActive provides (ShellNav.currentTab == pageTabId)
                    ) {
                    // v11：裁剪页内越界绘制——M3 抽屉 sheet 收起时停在相邻页（笔记）的绘制区，会"盖住"笔记页
                    Box(Modifier.fillMaxSize().clipToBounds()) {
                        when (tabs[page].id) {
                            "home" -> HomeScreen(
                                onSelectTab = { id ->
                                    val idx = tabs.indexOfFirst { it.id == id }.coerceAtLeast(0)
                                    ShellNav.currentTab = tabs.getOrNull(idx)?.id ?: ShellNav.currentTab
                                    // v71：首页内容里的跨 tab 跳转（今日概览→专注等）也走**抽纸条**——
                                    // 原来这里是 quick fade（跳切），与 Rail/底栏同一条动线不一致。
                                    slideSwitchTo(idx)
                                }
                            )
                            "plan" -> PlanScreen()
                            "focus" -> FocusScreen()
                            "notes" -> NotesScreen()
                            "info" -> InfoCenterScreen()
                            "profile" -> ProfileScreen()
                        }
                    }
                    }
                }
                // P1-1(1a)：top-level quick fade 的包裹层——点按/深链切页时整层先淡出再淡入；
                // 抽纸条时 alpha 恒为 1（fadeJob 不在跑），pager 滑动不受影响。
                // v69：fade 只作用在框内内容——相框不参与淡出。
                // v70j：轴向按形态分（见上）。
                val pagerFadeModifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = pageAlpha.value }
                if (wide) {
                    VerticalPager(
                        state = pagerState,
                        modifier = pagerFadeModifier,
                        userScrollEnabled = false, // 十三期：彻底删除一级界面滑动切换，只走底栏/路由切页
                        beyondViewportPageCount = tabs.size,
                        pageContent = pageContent
                    )
                } else {
                    HorizontalPager(
                        state = pagerState,
                        modifier = pagerFadeModifier,
                        userScrollEnabled = false,
                        beyondViewportPageCount = tabs.size,
                        pageContent = pageContent
                    )
                }
            }
            // 宽屏走 Rail，底栏不组合（连 AnimatedVisibility 都不进，省一层）。
                if (!wide) {
                    // 2026-10-24 M3E 审计：裸默认 enter/exit（fade+expand 双轴、非 expressive 弹簧）→
                    // 单轴竖直滑入/滑出（规范：底栏显隐按一个轴动），弹簧走 standardSpatial/quick 档
                    AnimatedVisibility(
                        // v68：锁按 tab 记账——本页（当前 tab）有页内子页开着才藏底栏；
                        // 别的 tab 的子页锁不影响这里（纸条切页从任何深度都可用）。
                        visible = ShellChrome.effectiveLock(ShellNav.currentTab) == 0,
                        enter = slideInVertically(io.github.m0use001.planability.ui.common.Motion.standardSpatial()) { it },
                        exit = slideOutVertically(io.github.m0use001.planability.ui.common.Motion.standardSpatial()) { it },
                        modifier = Modifier.align(Alignment.BottomCenter)
                    ) {
                        PillNavBar(
                            tabs = barTabs,
                            current = currentBarIdx,
                            onSelect = { barIdx -> selectTab(barIdx) },
                            onDragProgress = { fraction ->
                                // v16.2 吸附式跟手+滑动动画：手指跨过按钮边界的那一刻，候选框跳到候选页、
                                // 页面带左右滑动动画滑到该页停整（不会停留在页间叠加）；快速连续跨页时
                                // 取消上一个动画直接改道新目标。下标换算走底栏宇宙（B1 收敛后 ≠ pager 下标）
                                val barPage = (fraction * barTabs.size).toInt().coerceIn(0, barTabs.size - 1)
                                if (barPage != dragPage) {
                                    dragPage = barPage
                                    val pagerIdx = tabs
                                        .indexOfFirst { it.id == barTabs.getOrNull(barPage)?.id }
                                        .coerceAtLeast(0)
                                    dragAnimJob?.cancel()
                                    // 2026-10-24 M3E 审计：吸附落页挂 expressive 弹簧（原为 androidx 裸默认弹簧）
                                    dragAnimJob = scope.launch {
                                        pagerState.animateScrollToPage(
                                            pagerIdx,
                                            animationSpec = io.github.m0use001.planability.ui.common.Motion.standardSpatial<Float>()
                                        )
                                    }
                                }
                            }
                        )
                    }
                }
            }
            // v70g（2026-11-21 用户「认真检查所有地方…又出现了从框上方飞出的问题」）：
            // 墙带遮罩——纵向切页动画进行中，给框顶上方/框底下方的两条墙盖回底色。
            // 纸条带着两块板的圆角从墙区穿过时被盖住，看起来从相框**后面**穿行
            // （BoardDrill 缝带遮片同一物理语义的纵向版）。只在滑动瞬间显形：静止时
            // 页面与框逐像素对位、墙区本来就是背景，遮罩恒不可见；宽屏页内整页层
            // （PredictiveBackOverlay 的居中 modal 卡/全屏层）静止时铺满整页区，
            // 动画瞬间被墙带压住上缘属过渡态，落定即恢复。
            if (wide &&
                ShellChrome.boardFramesFor(ShellNav.currentTab) &&
                pagerState.isScrollInProgress
            ) {
                val wallColor = MaterialTheme.colorScheme.background
                val frameTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() +
                    io.github.m0use001.planability.ui.common.BoardLayout.Gap - 4.dp
                val frameBottom = io.github.m0use001.planability.app.navAvoidPadding().calculateBottomPadding()
                Column(Modifier.fillMaxSize()) {
                    Spacer(Modifier.fillMaxWidth().height(frameTop).background(wallColor))
                    Spacer(Modifier.weight(1f))
                    Spacer(Modifier.fillMaxWidth().height(frameBottom).background(wallColor))
                }
            }
    }
    // v67：搜索抽屉上提到整窗层——宽屏 sheet 从真正屏幕左缘滑出、遮罩罩住 Rail，
    // 弹出动画不再出现「Rail 中途退场 → 容器变宽」的缝隙；窄屏本来就整窗，行为不变。
    SearchDrawerHost(edgeSwipeEnabled = searchEdgeEnabled) {
        if (wide) {
        // v57 宽屏：左侧 Rail + 右侧一级界面本体。Rail 随 lockCount 显隐。
        //
        // v59 修正（2026-11-18 用户「工作台右滑菜单的位置不对，应该是从屏幕左边出来」）：
        // 原来 Rail 用 AnimatedVisibility 带**退场动画**，锁壳时它虽然隐形但**宽度还占着**，
        // 页面于是仍从 x=80dp 开始 —— 而工作台抽屉/全局搜索抽屉都是「页面内的
        // ModalNavigationDrawer」，被裁在页面矩形里，只能从 Rail 右缘滑出（用户截图实锤）。
        // 现在锁壳时 Rail **立刻退出布局**（exit = None），页面拿到整屏宽度，
        // 抽屉就从真正的屏幕左缘出来；进场仍保留滑入动画。
        Row(Modifier.fillMaxSize()) {
            AnimatedVisibility(
                // v68：锁按 tab 记账（底栏同款）——只有当前 tab 自己的页内锁会收 Rail。
                visible = ShellChrome.effectiveLock(ShellNav.currentTab) == 0,
                enter = androidx.compose.animation.slideInHorizontally(
                    io.github.m0use001.planability.ui.common.Motion.standardSpatial()
                ) { -it },
                // 退场不做动画：动画期间宽度继续占位，抽屉就还是从 Rail 右缘出来。
                // 锁壳那一刻页面已被抽屉遮罩/子页盖住，这个瞬变看不见。
                exit = androidx.compose.animation.ExitTransition.None
            ) {
                NavRailPane(
                    tabs = barTabs,
                    current = currentBarIdx,
                    onSelect = { barIdx -> selectTab(barIdx) },
                )
            }
            Box(Modifier.weight(1f).fillMaxHeight()) {
                shellBody()
            }
        }
        } else {
            shellBody()
        }
    }
}

/** v12 批F：全局搜索右滑抽屉壳——信息页工作台同款 ModalNavigationDrawer 手势（左缘拖动跟随） */
@Composable
fun SearchDrawerHost(
    edgeSwipeEnabled: Boolean,
    content: @Composable () -> Unit
) {
    val scope = rememberCoroutineScope()
    val drawerState = androidx.compose.material3.rememberDrawerState(
        androidx.compose.material3.DrawerValue.Closed
    )
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    // ShellChrome.searchOpen（图标/路由入口）→ 抽屉开合
    LaunchedEffect(ShellChrome.searchOpen) {
        if (ShellChrome.searchOpen) drawerState.open() else drawerState.close()
    }
    // 手势开合 → 标志位（结果行点击与关闭逻辑都吃这个标志）
    LaunchedEffect(Unit) {
        snapshotFlow { drawerState.currentValue }.collect { v ->
            val open = v == androidx.compose.material3.DrawerValue.Open
            if (ShellChrome.searchOpen != open) ShellChrome.searchOpen = open
        }
    }
    // v67（2026-11-21 用户拍板「取消右划菜单弹出时自动收起左侧菜单栏」）：宽屏 Rail 不再
    // 因搜索抽屉收起——抽屉已上提到整窗层，遮罩罩住 Rail 即可（v59 的「抽屉从屏幕左缘
    // 出」由上提实现，不再靠收 Rail）。窄屏保留药丸底栏随抽屉隐去的原行为
    // （lockCount 同时挡着底栏显隐）。
    val wideShell = io.github.m0use001.planability.core.rememberShellRailForm()
    // v68 修（2026-11-21，v67 引入的窄屏回归，装机实拍抓到）：这里写成了
    // `if (!wideShell) LockShellChrome()` —— 窄屏**无条件**锁壳，lockCount 恒 ≥1，
    // 药丸底栏（visible = effectiveLock == 0）永远隐藏，手机档失去唯一导航。
    // v67 的意图是「窄屏保留底栏随抽屉隐去的原行为」——**开抽屉才锁**（v66 原条件）；
    // 宽屏 Rail 在遮罩之下不再收起（v67 拍板），不锁。
    if (!wideShell && (drawerState.isOpen ||
            (drawerState.isAnimationRunning &&
                drawerState.targetValue == androidx.compose.material3.DrawerValue.Open))
    ) {
        LockShellChrome()
    }
    // back 键关抽屉（信息页工作台抽屉同款兜底）
    androidx.activity.compose.BackHandler(enabled = drawerState.isOpen) {
        scope.launch { drawerState.close() }
    }
    // 2026-11-19 用户「全局搜索右划菜单不要自动弹键盘」：抽屉一开就把焦点从搜索框摘掉、
    // 把 IME 收起来——搜索框是抽屉里第一个可聚焦控件，某些 ROM/Compose 组合会顺手给它焦点，
    // 结果右划唤出菜单的同时键盘就弹上来了（用户没打算搜索，键盘先占半屏）。
    // 关闭时同样收一遍：不然关掉抽屉后焦点仍留在搜索框上，下次右划唤出键盘又自动弹。
    val drawerOpenish = drawerState.isOpen ||
        (drawerState.isAnimationRunning && drawerState.targetValue == androidx.compose.material3.DrawerValue.Open)
    LaunchedEffect(drawerOpenish) {
        focusManager.clearFocus()
        keyboard?.hide()
    }
    androidx.compose.material3.ModalNavigationDrawer(
        drawerState = drawerState,
        // v67：开着的抽屉放开 M3 内置手势——遮罩**点按**关闭 + 遮罩区**拖拽**收回走原生实现。
        // 旧约束「gesturesEnabled 必须恒关」防的是开侧抢页面横向滚动（拖拽期间持有
        // anchoredDrag 互斥锁会饿死程序化 close()）；只放开「开着」这一态，抽屉关着时
        // 页面滚动照旧。sheet 上的 drawerSwipeToClose 仍在，双保险。
        gesturesEnabled = drawerOpenish,
        drawerContent = {
            androidx.compose.material3.ModalDrawerSheet {
                Box(Modifier.drawerSwipeToClose(drawerState)) {
                    // 2026-11-19 用户「点日历有几率右划菜单不收回」：原来 onClose 只把
                    // ShellChrome.searchOpen 置 false，等 shell 里那条 LaunchedEffect 去关抽屉；
                    // 而**右划打开**时抽屉的 currentValue 要等开合动画结束才变成 Open，
                    // 此刻 searchOpen 还是 false —— 用户在动画没走完时点「日历」，
                    // onClose 把 false 又写成 false，状态没变化 → 那条 effect 不触发 → 抽屉不收。
                    // 现在 onClose **直接关抽屉**（顺带同步标志位），与标志位解耦，race 消失。
                    GlobalSearchContent(
                        // v70j：抽屉瘦身只属于宽屏（副框只存在宽屏）；手机没有副框，
                        // 统计框 + 日历入口留在抽屉里（见 GlobalSearchContent 的 !wide 分支）。
                        wide = wideShell,
                        onClose = {
                            ShellChrome.searchOpen = false
                            scope.launch { drawerState.close() }
                        }
                    )
                }
            }
        }
    ) {
        Box(Modifier.edgeSwipeDrawerOpen(drawerState, edgeSwipeEnabled)) {
            content()
        }
    }
}

/**
 * 抽屉收回手势（叠在抽屉 sheet 内容上）：左滑（相对屏幕）累计超过 48dp 即带动画收抽屉并消费后续事件。
 * M3 内置拖拽的回收阈值是「半屏距离或 400dp/s 甩动」，轻扫常两条都不达 → 弹回原位，体感"要滑两次"；
 * 抽屉关着时内置手势仍恒关（防抢页面横向滚动），sheet 上的本手势是轻扫收回主通道
 * （抽屉开着时内置手势已放开，遮罩区另有原生点按/拖拽收回，双保险）。不消费 down：不影响 sheet 内点击与滚动。
 */
fun Modifier.drawerSwipeToClose(
    drawerState: androidx.compose.material3.DrawerState
): Modifier = composed {
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val haptics = LocalHaptics.current
    remember(drawerState) {
        Modifier.pointerInput(drawerState) {
            val trigger = with(density) { 48.dp.toPx() }
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Main)
                var startX = down.position.x
                var startY = down.position.y
                var triggered = false
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Main)
                    val change = event.changes.firstOrNull() ?: break
                    // v70i 修（用户「手机模式下右划菜单无法左划返回」）：按**手指净位移**判触发，
                    // 不再减 sheetShift（sheet 自身跟手移动的量）。原来两者相减：M3 内置拖拽
                    // （gesturesEnabled=drawerOpenish 开抽屉态放开）跟手拖 sheet 时
                    // sheetShift≈dxLocal，dx 被抵成 ~0，48dp 阈值永远到不了——轻划左移
                    // 半途被 M3 弹回，体感就是「左划没反应」。宽屏一直能用是靠遮罩点按兜底。
                    val dx = change.position.x - startX
                    val dy = change.position.y - startY
                    if (!triggered && dx < -trigger && abs(dx) > abs(dy) * 1.2f) {
                        triggered = true
                        android.util.Log.d("DrawerClose", "trigger dx=$dx dy=$dy")
                        haptics.perform(HapticEngine.Kind.Tap)
                        // 消费后续事件（含 up），由 close() 独占 anchoredDrag 锁做收回动画
                        event.changes.forEach { it.consume() }
                        scope.launch { drawerState.close() }
                    }
                    if (triggered) event.changes.forEach { it.consume() }
                    if (!change.pressed) break
                }
            }
        }
    }
}

/**
 * 抽屉打开手势：除底部导航条一整行（含胶囊与屏缘空位）外任意位置起手，向右横滑触发。
 * 右滑意图在位移刚过 slop 且水平领先垂直时锁定——带纵漂的斜向右滑也能开（不必绝对水平），
 * 锁定后事件整段消费，纵向滚动不再插手；纯纵向滚动（dx 始终落后 dy）不受影响。
 * 抽屉关闭手势仍走抽屉自身（scrim/按钮/drawerSwipeToClose）。
 */
fun Modifier.edgeSwipeDrawerOpen(
    drawerState: androidx.compose.material3.DrawerState,
    enabled: Boolean
): Modifier = composed {
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val haptics = LocalHaptics.current
    remember(drawerState, enabled) {
        Modifier.pointerInput(drawerState, enabled) {
            if (!enabled) return@pointerInput
            // 触发区：除底部导航条一整行（含胶囊与屏缘空位）外的任意位置
            val stripPx = with(density) { 132.dp.toPx() }
            val openDistance = with(density) { 64.dp.toPx() }
            val slop = viewConfiguration.touchSlop
            // 2026-11-12 用户「组件/图的横滑跟右滑菜单手势冲突」：表格/公式/图登记横滑区后，
            // 铺满宽的组件让「开菜单」在屏上找不到落点（数学课满屏公式）。给屏缘留 28dp
            // 豁免带：起点贴左缘一律开菜单（标准抽屉交互），区内的中段照常滚组件。
            val edgeExemptPx = with(density) { 28.dp.toPx() }
            awaitEachGesture {
                // Initial 通道：父级先于子级滚动看到事件——否则滚动子级先消费，手势永远失效
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                if (down.position.y > size.height - stripPx) return@awaitEachGesture
                // v19.7：按在横向滚动条上（chips / 模型选择条 / 月历周带）时让位给它，不开抽屉；
                // v19.12：屏缘 28dp 例外——组件占满宽后菜单仍可从屏缘开出
                if (io.github.m0use001.planability.core.ShellChrome.inHScrollZone(down.position) &&
                    down.position.x > edgeExemptPx
                ) return@awaitEachGesture
                var opened = false
                var locked = false
                var startX = down.position.x
                var startY = down.position.y
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val change = event.changes.firstOrNull() ?: break
                    val dx = change.position.x - startX
                    val dy = change.position.y - startY
                    // 右滑意图锁定：水平位移刚过 slop 且领先垂直分量即判为右滑——此后纵漂/斜滑
                    // 不再影响判定（用户做不到绝对水平），事件整段消费，时间轴等纵向滚动不再插手；
                    // 纵向滚动的 dx 始终落后 dy，永远不会锁定
                    if (!locked && dx > slop && dx > abs(dy)) {
                        locked = true
                        haptics.perform(HapticEngine.Kind.ImpactLight)
                        event.changes.forEach { it.consume() }
                    }
                    if (!opened && locked && dx > openDistance) {
                        opened = true
                        // 达到展开距离：抽屉带动画展开
                        event.changes.forEach { it.consume() }
                        scope.launch { drawerState.open() }
                    }
                    if (locked) event.changes.forEach { it.consume() }
                    if (!change.pressed) break
                }
            }
        }
    }
}

/**
 * 对话页（独立 Dialog 窗口）里的右滑打开菜单手势。
 *
 * 为什么不能复用 [edgeSwipeDrawerOpen]：对话页是 `PredictiveBackOverlay` 的 Dialog 窗口，
 * 触摸事件到不了父窗口的 Box，父页那个手势永远收不到（2026-10-22 用户反馈"对话界面右滑菜单没反应"）。
 * 所以把同一套判定搬进对话窗口，目标从 DrawerState 换成回调——父页收到后关对话、再开抽屉。
 * 判定完全一致：右滑意图锁定（dx 过 slop 且领先 dy）、按在横向滚动条上让位、底部 132dp 不触发。
 */
fun Modifier.swipeRightToOpen(enabled: Boolean, onOpen: () -> Unit): Modifier = composed {
    val density = LocalDensity.current
    val haptics = LocalHaptics.current
    val currentOnOpen by androidx.compose.runtime.rememberUpdatedState(onOpen)
    remember(enabled) {
        Modifier.pointerInput(enabled) {
            if (!enabled) return@pointerInput
            val stripPx = with(density) { 132.dp.toPx() }
            val openDistance = with(density) { 64.dp.toPx() }
            val slop = viewConfiguration.touchSlop
            // 与 edgeSwipeDrawerOpen 同款屏缘豁免（2026-11-12 表格/公式/图横滑冲突）
            val edgeExemptPx = with(density) { 28.dp.toPx() }
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                if (down.position.y > size.height - stripPx) return@awaitEachGesture
                if (io.github.m0use001.planability.core.ShellChrome.inHScrollZone(down.position) &&
                    down.position.x > edgeExemptPx
                ) return@awaitEachGesture
                var opened = false
                var locked = false
                val startX = down.position.x
                val startY = down.position.y
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val change = event.changes.firstOrNull() ?: break
                    val dx = change.position.x - startX
                    val dy = change.position.y - startY
                    if (!locked && dx > slop && dx > abs(dy)) {
                        locked = true
                        haptics.perform(HapticEngine.Kind.ImpactLight)
                        event.changes.forEach { it.consume() }
                    }
                    if (!opened && locked && dx > openDistance) {
                        opened = true
                        event.changes.forEach { it.consume() }
                        currentOnOpen()
                    }
                    if (locked) event.changes.forEach { it.consume() }
                    if (!change.pressed) break
                }
            }
        }
    }
}

/** v10：悬浮底栏的底部避让 padding（栏高 88dp + 下边距 8dp + 间隙 10dp，叠加导航条 inset）。
 *
 * v57 平板适配：宽屏走左侧 Rail、没有悬浮底栏，底部避让只剩导航条 inset（+16dp 内容余量）；
 * 药丸底栏的 106dp 只在 Compact 形态下生效。这是全 app 各页 `contentPadding` 的单点开关。 */
@Composable
fun navAvoidPadding(): PaddingValues {
    val navInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    // v64：跟外壳同一个实测口径——宽屏形态（≥840dp）只留导航条 + 16dp 余量；
    // 600–840dp 退回手机形态，药丸底栏的 106dp 重新生效（原来被 screenWidthDp
    // 谎报成宽屏，106dp 被提前收掉，内容被底栏盖住）。
    val wide = io.github.m0use001.planability.core.rememberShellRailForm()
    return if (wide) {
        PaddingValues(bottom = navInset + 16.dp)
    } else {
        PaddingValues(bottom = navInset + 106.dp)
    }
}

private data class SearchRow(val type: String, val id: Long, val title: String, val sub: String)

@Composable
private fun GlobalSearchContent(
    onClose: () -> Unit,
    // v70j：宽屏=true 抽屉只剩搜索（其余已并入首页副框）；手机=false 保留统计框+日历
    //（手机没有副框，「合并至首页副框」对手机不成立——2026-11-22 用户指出误清）。
    wide: Boolean,
) {
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<SearchRow>>(emptyList()) }
    val version by Graph.dataVersion.flow.collectAsState()
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    // 抽屉内条目/入口点击的中性反馈（与抽屉其它行同一档）
    val haptics = LocalHaptics.current
    // 2026-11-19 用户「右划菜单不要自动弹键盘」：抽屉内容组合时（= 右划唤出的那一刻）
    // 搜索框可能自动拿到焦点 → IME 直接弹上来。这里在内容组合的第一时间把焦点摘掉、
    // 键盘收掉；用户**主动点**搜索框仍然正常弹键盘（那之后不再有副作用）。
    LaunchedEffect(Unit) {
        focusManager.clearFocus()
        keyboard?.hide()
    }
    // 「没有用户点击就获得的焦点」一律撤销：抽屉里搜索框是第一个可聚焦控件，
    // 框自己拿到焦点（ROM/Compose 自动给、或上一次会话残留）就会把键盘顶上来。
    // userTappedField 只在**手指真实落在搜索框上**时置位（Initial 通道只看不消费，
    // 不影响输入框自己的点击/光标/选区），所以用户主动点搜索不受影响。
    var userTappedField by remember { mutableStateOf(false) }
    var fieldFocused by remember { mutableStateOf(false) }
    LaunchedEffect(fieldFocused, userTappedField) {
        if (fieldFocused && !userTappedField) {
            focusManager.clearFocus()
            keyboard?.hide()
        }
    }
    val fieldExtras = Modifier
        .onFocusChanged {
            fieldFocused = it.isFocused
            // 失焦即复位：抽屉关掉时宿主会 clearFocus，下次右划唤出重新从「没点过」算起
            if (!it.isFocused) userTappedField = false
        }
        .pointerInput(Unit) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                userTappedField = true
            }
        }

    LaunchedEffect(query, version) {
        if (query.isBlank()) {
            results = emptyList()
        } else {
            val q = query.trim()
            val schedules = Graph.plan.scheduleOccurrences(AppTime.today().minusDays(30), AppTime.today().plusDays(60))
                .asSequence()
                .filter { it.schedule.title.contains(q) || it.schedule.place.contains(q) }
                .distinctBy { it.schedule.id }
                .map { SearchRow("schedule", it.schedule.id, it.schedule.title, AppTime.millisToTime(it.start)) }
                .toList()
            val todos = Graph.plan.activeTodos()
                .filter { it.title.contains(q) }
                .map { SearchRow("todo", it.id, it.title, if (it.done) "已完成" else "待完成") }
            // 2026-11-07 P0-1：笔记路改 NoteSearch（双路 FTS + RRF 排序 + LIKE 兜底）——
            // 原来每键 allNotes() 全量读正文再 contains，规模一上来主线程就卡
            val notes = runCatching {
                io.github.m0use001.planability.ai.rag.NoteSearch.search(q, topK = 20)
            }.getOrDefault(emptyList()).map { SearchRow("note", it.note.id, it.note.title, "笔记") }
            results = (schedules + todos + notes).take(50)
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(16.dp)
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("全局搜索", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                // 关闭抽屉 = 顶栏返回一类的中性点击 → Tap
                IconButton(onClick = { haptics.perform(HapticEngine.Kind.Tap); onClose() }) {
                    Icon(Icons.Rounded.Close, contentDescription = "关闭")
                }
            }
            OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth().then(fieldExtras),
                    placeholder = { Text("搜索日程 / 待办 / 笔记") },
                    leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search)
                )
                // v70j（2026-11-22 用户「手机模式下右划菜单怎么也清的只剩搜索了」）：v70i 的
                // 「仅保留搜索」只该作用于宽屏——副框只存在宽屏，手机没有承接处。窄屏抽屉
                // 恢复原有两件：今日统计框 + 日历入口（宽屏走首页副框的 HomeTodayPane）。
                if (!wide) {
                    // 今日数据可视化框（2026-10-24 用户要求：搜索框下面），与工作台抽屉/首页同源
                    Spacer(Modifier.height(10.dp))
                    io.github.m0use001.planability.ui.common.TodayStatsCard(Modifier.fillMaxWidth())
                    // v19.7：日历按钮从首页标题栏移到这里（跨 tab 打开 + 记来源页）
                    Spacer(Modifier.height(8.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .clickable {
                                haptics.perform(HapticEngine.Kind.Tap)
                                focusManager.clearFocus()
                                onClose()
                                // 记来源页：从别的一级页打开日历，关闭时要回来源（否则跳过原页）
                                if (ShellNav.currentTab != "home") ShellNav.returnTab = ShellNav.currentTab
                                Router.openHomeCalendar()
                            }
                            .padding(horizontal = 10.dp, vertical = 10.dp)
                    ) {
                        Icon(
                            Icons.Rounded.CalendarMonth, contentDescription = null,
                            // R11：抽屉/搜索里的入口行 = 装饰性图标 → 中性
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(Modifier.width(10.dp))
                        Text("日历", style = MaterialTheme.typography.bodyLarge)
                    }
                }
            Spacer(Modifier.height(12.dp))
            LazyColumn {
                if (results.isEmpty() && query.isNotBlank()) {
                    item {
                        Text("没有匹配结果", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                items(results, key = { it.type + it.id }) { row ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .clickable {
                                // 打开一条搜索结果 = 命令类中性点击 → Tap（与抽屉其它行同档）
                                haptics.perform(HapticEngine.Kind.Tap)
                                focusManager.clearFocus()
                                onClose()
                                when (row.type) {
                                    "schedule" -> Router.openSchedule("plan", row.id)
                                    "todo" -> Router.openTodo("plan", row.id)
                                    "note" -> Router.openNote(row.id)
                                }
                            }
                            .padding(10.dp)
                    ) {
                        Icon(
                            imageVector = when (row.type) {
                                "schedule" -> Icons.Rounded.CalendarMonth
                                "todo" -> Icons.Rounded.CheckCircle
                                else -> Icons.Rounded.MenuBook
                            },
                            contentDescription = null,
                            // R11：搜索结果类型图标 → 中性
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(row.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
                            Text(
                                row.sub, style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}
