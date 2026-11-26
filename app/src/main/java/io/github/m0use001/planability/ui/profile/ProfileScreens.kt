package io.github.m0use001.planability.ui.profile

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.List
import androidx.compose.material.icons.rounded.Archive
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CropFree
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.EmojiEvents
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material.icons.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.Sensors
import androidx.compose.material.icons.rounded.ShowChart
import androidx.compose.material.icons.rounded.SmartToy
import androidx.compose.material.icons.rounded.SwapVert
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.Upload
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.m0use001.planability.app.PredictiveBackOverlay
import io.github.m0use001.planability.app.Router
import io.github.m0use001.planability.app.ShellNav
import io.github.m0use001.planability.app.tabSpecs
import io.github.m0use001.planability.core.AppTime
import io.github.m0use001.planability.core.HapticEngine
import io.github.m0use001.planability.core.HapticTicks
import io.github.m0use001.planability.core.LocalHaptics
import io.github.m0use001.planability.core.reachedEndHaptic
import io.github.m0use001.planability.core.formatMonthDay
import io.github.m0use001.planability.core.toLocalDate
import kotlin.math.roundToInt
import io.github.m0use001.planability.data.AchievementDefs
import io.github.m0use001.planability.data.Graph
import io.github.m0use001.planability.data.NoteEntity
import io.github.m0use001.planability.data.Schedule
import io.github.m0use001.planability.data.CheckinSeries
import io.github.m0use001.planability.data.SupervisionOverview
import io.github.m0use001.planability.data.Todo
import io.github.m0use001.planability.data.XpCenter
import io.github.m0use001.planability.data.unlockBackupAchievement
import io.github.m0use001.planability.ui.common.AppButtonGroup
import io.github.m0use001.planability.ui.common.MotionTabIndicator
import io.github.m0use001.planability.ui.common.adaptiveColumn
import io.github.m0use001.planability.ui.common.BoardDivider
import io.github.m0use001.planability.ui.common.BoardHeader
import io.github.m0use001.planability.ui.common.BoardRowDivider
import io.github.m0use001.planability.ui.common.BoardSectionTitle
import io.github.m0use001.planability.ui.common.BoardSubHeader
import io.github.m0use001.planability.ui.common.ConfirmDialog
import io.github.m0use001.planability.ui.common.BoardDrill
import io.github.m0use001.planability.ui.common.clickableRounded
import io.github.m0use001.planability.ui.theme.FIXED_STYLE_LABELS
import io.github.m0use001.planability.ui.theme.fixedStyleTriad
import io.github.m0use001.planability.ui.common.EmptyHint
import io.github.m0use001.planability.ui.common.HapticBackButton
import io.github.m0use001.planability.ui.common.formatMinutes
import io.github.m0use001.planability.ui.common.pressScale
import io.github.m0use001.planability.ui.info.MemoryScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.ui.graphics.vector.ImageVector
import java.util.Locale

@Composable
fun ProfileScreen() {
    // v68（2026-11-21「纸条导航」）：原 24 个 showXxx 布尔收敛成单一 [ProfileSub] 状态。
    // 两个分支共用同一份状态：窄屏/手机渲染整屏预测返回层（外壳已页内化，视觉不变），
    // 宽屏两板把它交给 BoardDrill 的细节瓦片（纸条三向滑动：概览右出屏、本列表滑进
    // 副框自动改排版、细分页滑进主框；返回集体左移）。跨切页不再收层——页面记忆。
    // （watcher 已删：原来收层是因为子页是独立 Dialog 窗口会浮在新 tab 上，v68 页内化。）
    var subPage by remember { mutableStateOf<ProfileSub?>(null) }
    // 打卡面板是唯一保留的独立层：它是从数据中心里再打开的**第二层**（嵌套），
    // 不占 subPage 槽位——数据中心开着时打卡面板浮在其上，返回先关面板。
    var showCheckinBoard by remember { mutableStateOf(false) }
    var boardFocus by remember { mutableStateOf<Long?>(null) }
    // 就地对话框（非页面层）：账号昵称编辑、图表全局默认设置
    var showAccountDialog by remember { mutableStateOf(false) }
    var showChartDefaults by remember { mutableStateOf(false) }
    // 「自动化任务 / 导入」共用 AutomationScreen 的段位参数
    var automationTab by remember { mutableIntStateOf(0) }
    // 两板判定由 BoxWithConstraints 实测后提升到函数域（根层的子页分发块也要读它：
    // 宽屏只有不可钻入的 A 线属地页走整屏模态，窄屏全部走整屏层）
    var profileTwoPane by remember { mutableStateOf(false) }
    val settings by Graph.settings.snapshot.collectAsState()

    val haptics = LocalHaptics.current
    // 主滚动容器：滑到顶/底各给一次触感（顶轻底重），否则这一屏内容很长却完全没有边界反馈
    val scroll = rememberScrollState()

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        // 2026-11-20 v65：宽屏改走**共享的两板骨架**（SplitBoards）——
        // 左板=基础设置 + Agent 能力，右板=账号概览 + 数据与统计 + 版本信息，
        // 两块 24dp 圆角板 + 12dp 白缝，与计划页/首页/专注页同一套材质。
        // 原来这里是三张**浮起的圆角卡**并排（SettingsGroup 的 Card 外壳）——
        // 板间 12dp 空隙 + 栏内浮卡 = 两种材质叠在一起，正是「内容块割裂」。
        //
        // 先量**内容区实测宽**再决定形态。为什么不用 rememberWindowSizeClass()：
        // 宽屏左侧有 80dp Rail，它读的 screenWidthDp 会把窗宽多算 80dp，
        // 临界值提前一档 —— FocusLayout 同款实测结论。
        androidx.compose.foundation.layout.BoxWithConstraints(
            Modifier.fillMaxSize(),
            contentAlignment = Alignment.TopCenter
        ) {
        val twoPane = profileIsTwoPane(maxWidth.value.toInt())
        profileTwoPane = twoPane
        val profileScrollLeft = rememberScrollState()
        val profileScrollRight = rememberScrollState()

        // ---- 账号卡：中性容器 + 小面积主色容器头像（原生设置页的卡片/图标都是中性，
        // 主色只做焦点强调；此前整卡 primaryContainer + 主色头像 = 两整块绿）----
        // 窄屏摆位（顶部浮起卡），宽屏摆位在**右板**（板内扁平行，不是浮卡——
        // 板本身已经是圆角矩形 + 容器底，板里再套一张卡就是两种材质）。
        // 头像/昵称/锁状态的内容由 AccountIdentity 一处提供，两处摆位共用。
        val accountCard: @Composable () -> Unit = {
            Card(
                onClick = {
                    haptics.perform(io.github.m0use001.planability.core.HapticEngine.Kind.Tap)
                    showAccountDialog = true
                },
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                // v57 C 类补漏：卡片按压 squish（「我的」页此前 0 处，全页点按没有按压反馈）。
                // pressScale 在 clickable 之前挂——只包住卡片自身的绘制（摆放铁律）。
                // 2026-11-20：点卡片改就地改昵称（对话框），不再开一整屏「账号」页
                //——那一页只有一个输入框加两行链接，正是用户点名的「太浪费」。
                // 用 Card(onClick=) 重载：水波画在卡片**形状内部**，不会在四个圆角处戳出方块。
                modifier = Modifier.fillMaxWidth().pressScale()
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(18.dp)) {
                    AccountIdentity(settings.accountName, settings.lockEnabled, Modifier.weight(1f))
                    Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
                }
            }
        }

        // ---- 三组设置行清单：宽窄两种摆位共用同一份 ----
        // （复制出来的那份下次改必漏，本仓教训是五工具漏路由）
        // 这里把每组抽成**条目列表**：窄屏（SettingsGroup 的 Card 竖排）与宽屏
        // （板内扁平行 + 行间细线）都从同一份列表渲染 —— 内容只有一处，改一处两边同时变。
        // v77：三份清单改收一个 open 回调——宽屏走 NavHost push（钻入），窄屏走 subPage 覆盖层。
        val basicEntries: ((ProfileSub) -> Unit) -> List<SettingEntry> = { open ->
            listOf(
                SettingEntry(Icons.Rounded.Palette, "外观") { open(ProfileSub.Appearance) },
                SettingEntry(Icons.Rounded.Public, "时区") { open(ProfileSub.TimeZone) },
                // 2026-11-20 用户「1 个页面全显示一项设置也太浪费了」：原「功能开关」
                // （4 个开关）、「计划设置」（2 个开关）、「目标设定」（2 个输入框）、「底栏排序」
                // （7 行拖动）各占一整页——一页只有一点点内容，跳进去还要跳出来。
                // 四张合成一页「更多设置」（计划 / 开关 / 目标 / 底栏排序），本列表只留这一个入口。
                SettingEntry(Icons.Rounded.Tune, "更多设置") { open(ProfileSub.MoreSettings) },
                // 2026-11-20：原「账号」页只有昵称一个输入框 + 这两行链接——太浪费。
                // 昵称改就地对话框（点头像卡弹出），两行链接升级成主列表的入口行。
                SettingEntry(Icons.Rounded.Lock, if (settings.lockEnabled) "应用锁（已开启）" else "应用锁") { open(ProfileSub.AppLock) },
                SettingEntry(Icons.Rounded.Save, "备份与恢复") { open(ProfileSub.Backup) },
                // v19.51：系统权限一屏览。之前这些授权散落在
                // 悬浮窗开关、信息获取、手机操控区域、各功能首次使用时才弹，
                // 用户想"一次看全/一次补全"没有入口。
                SettingEntry(Icons.Rounded.Security, "系统权限") { open(ProfileSub.Permissions) },
                SettingEntry(Icons.Rounded.Save, "使用指引") { open(ProfileSub.Guide) }
            )
        }
        val agentEntries: ((ProfileSub) -> Unit) -> List<SettingEntry> = { open ->
            listOf(
                // v50.5（用户 2026-11-05 拍板「两行：本体 + 扩展」）：与工作台抽屉
                // （ui/info/WorkbenchDrawer.kt）彻底对齐——那边也是这两行、同名同图标。
                //   模型与智能体 = AI 自己是谁：模型 / 人格 / 模型显示（第三项从本页收进 Hub）
                //   工具与扩展   = AI 能调动什么：能力与插件 / MCP / 命令
                // 之前本页有 6 行、抽屉 5 行且「模型显示管理」只在一边有，两边清单会各自漂移。
                SettingEntry(Icons.Rounded.Settings, "模型与智能体") { open(ProfileSub.Model) },
                SettingEntry(Icons.Rounded.Extension, "工具与扩展") { open(ProfileSub.ToolsHub) },
                // 用户指定：紧贴「能力设置」正下方 —— 抽屉那边也是这个位置，
                // 两个入口的顺序、标签、图标完全一致。
                SettingEntry(Icons.Rounded.BarChart, "图表默认设置") { showChartDefaults = true },
                // phone-a 在工作台抽屉里的同一入口（ui/info/WorkbenchDrawer.kt）：
                // DrawerActionRow(CropFree, "手机操控区域")。**标签、图标、紧随「能力设置」的
                // 位置都与那边保持一致**，否则同一个功能两个入口读起来不一样，用户会当成两件事。
                // 页面本体在 ui/info/PhoneUseRegionScreen（phone-a 线），故走 Router 过去。
                SettingEntry(Icons.Rounded.CropFree, "手机操控区域") {
                    // 跨 tab 跳转：先记来源页 —— 子页关闭时回「我的」，而不是落在
                    // 信息页根（否则「我的 → 手机操控区域 → 返回」把「我的」这一层跳过了）
                    ShellNav.returnTab = "profile"
                    Router.openPhoneUseRegion()
                },
                // v50.5：MCP / 命令已收进「工具与扩展」Hub；「自动化任务」不是 agent 配置
                // （是定时任务列表），保留独立行。导入与文件库同理留在本页。
                SettingEntry(Icons.Rounded.Sensors, "自动化任务") { automationTab = 0; open(ProfileSub.Automation) },
                SettingEntry(Icons.Rounded.Upload, "导入") { automationTab = 2; open(ProfileSub.Automation) },
                SettingEntry(Icons.Rounded.InsertDriveFile, "文件库") { open(ProfileSub.FileLibrary) },
                SettingEntry(Icons.Rounded.SmartToy, "记忆库") { open(ProfileSub.Memory) }
            )
        }
        val statsEntries: ((ProfileSub) -> Unit) -> List<SettingEntry> = { open ->
            listOf(
                SettingEntry(Icons.Rounded.ShowChart, "使用统计") { open(ProfileSub.Usage) },
                SettingEntry(Icons.Rounded.BarChart, "数据中心") { open(ProfileSub.DataCenter) },
                SettingEntry(Icons.Rounded.EmojiEvents, "成就墙") { open(ProfileSub.Achieve) },
                SettingEntry(Icons.Rounded.Timer, "周小结") { open(ProfileSub.Weekly) },
                SettingEntry(Icons.Rounded.Archive, "回收站") { open(ProfileSub.Trash) }
            )
        }

        if (twoPane) {
            // v68（2026-11-21「纸条导航」需求 2）：SplitBoards → BoardDrill——
            // 点基础设置/数据与统计里的条目，纸条三向滑动：概览右板划出屏幕右缘、
            // 本列表滑出主框滑进副框（宽 380dp 档连续改排版）、细分页从左缘滑进主框；
            // 返回集体左移 + 系统预测返回跟手拖纸条。根层几何与 SplitBoards 逐像素一致。
            // v77：宽屏根改走全局 NavHost 层级栈（SplitBoards/BoardDrill → NavHost ≈ 同一几何）。
            // 可钻入（embeddable）条目 push 进栈 = 钻入主板、上一页缩副框；尚未拆级的
            // A 线 Hub（模型/工具/记忆库/自动化）暂仍走下方 subPage 覆盖层（逐 Hub 拆）。
            io.github.m0use001.planability.ui.common.NavHost(
                rootMain = {
                    val nav = io.github.m0use001.planability.app.LocalNav.current
                    val openWide: (ProfileSub) -> Unit = { sp ->
                        if (sp.embeddable && nav != null) {
                            nav.push(io.github.m0use001.planability.app.NavPane(sp) {
                                ProfileSubScreen(
                                    sp, deferBack = true,
                                    onDone = { nav.pop() },
                                    onOpenBoard = { id -> boardFocus = id; showCheckinBoard = true },
                                    automationTab = automationTab
                                )
                            })
                        } else {
                            subPage = sp
                        }
                    }
                    Column(
                        Modifier
                            .fillMaxSize()
                            .verticalScroll(profileScrollLeft)
                            .reachedEndHaptic(profileScrollLeft)
                    ) {
                        BoardHeader(title = "我的")
                        BoardSectionTitle("基础设置")
                        BoardEntryList(basicEntries(openWide))
                        BoardDivider()
                        BoardSectionTitle("Agent 能力")
                        BoardEntryList(agentEntries(openWide))
                        // 2026-11-21 用户拍板：数据与统计的入口 + 关于（版本/本机数据）
                        // 归左板（右板只留账号 + 数据可视化，不混导航入口）
                        BoardDivider()
                        BoardSectionTitle("数据与统计")
                        BoardEntryList(statsEntries(openWide))
                        BoardDivider()
                        ProfileAboutSection()
                        Spacer(Modifier.height(16.dp))
                    }
                },
                rootSecondary = {
                    Column(
                        Modifier
                            .fillMaxSize()
                            .verticalScroll(profileScrollRight)
                            .reachedEndHaptic(profileScrollRight)
                    ) {
                        ProfileOverviewBoard(
                            name = settings.accountName,
                            lockEnabled = settings.lockEnabled,
                            onAccountClick = {
                                haptics.perform(io.github.m0use001.planability.core.HapticEngine.Kind.Tap)
                                showAccountDialog = true
                            }
                        )
                    }
                }
            )
        } else {
        Column(
            Modifier
                .fillMaxHeight()
                // 窄屏沿用旧的整列限宽（840dp）——widthIn(max) 在手机上是天然 no-op，
                // 手机路径与改版前逐像素一致
                .adaptiveColumn(PROFILE_ONE_COLUMN_MAX_WIDTH_DP.dp)
                .statusBarsPadding()
                .verticalScroll(scroll)
                .reachedEndHaptic(scroll)
                .padding(horizontal = 20.dp)
        ) {
            io.github.m0use001.planability.ui.common.PageHeader("我的", horizontalPadding = 0.dp)

            // 账号卡 / 三组行清单的条目定义在**上面**（两板分支之前）——
            // 宽屏两板与窄屏竖排共用同一份内容，不复制第二套。
            accountCard()
            Spacer(Modifier.height(14.dp))

            // 窄屏摆位（内容宽不够两块板）：与改版前逐像素一致的竖排 + 14dp 组缝。
            // 宽屏的两板摆位在上面 SplitBoards 分支里（基础/Agent 在左板，
            // 数据与统计 + 账号概览在右板）——行清单始终是上面同一份条目列表。
            SettingsGroup("基础设置") { EntryList(basicEntries({ subPage = it })) }
            Spacer(Modifier.height(14.dp))
            SettingsGroup("Agent 能力") { EntryList(agentEntries({ subPage = it })) }
            Spacer(Modifier.height(14.dp))
            SettingsGroup("数据与统计") { EntryList(statsEntries({ subPage = it })) }
            Spacer(Modifier.height(io.github.m0use001.planability.app.navAvoidPadding().calculateBottomPadding()))
        }
        }
    }
    }

    // v68：子页分发收敛成 [ProfileSubScreen]（一份 when，两个渲染位置共用）。
    //  · 窄屏/手机：全部走整屏预测返回层（外壳 v68 起页内化，视觉与改版前一致）。
    //  · 宽屏两板：可钻入的（embeddable = 已拆级、按 deferBack 分流瓦片/整屏）由上面
    //    NavHost 栈的瓦片承载，这里只渲染**不可钻入**的 A 线属地页（尚未拆级的
    //    ui/info Hub）——它们保持整屏模态卡，锁住当前 tab、Rail 照旧隐藏（与改版前
    //    一致），跨切页记忆照常成立（页内层不再浮到别的 tab 上）。
    when (val sp = subPage?.takeIf { !profileTwoPane || !it.embeddable }) {
        null -> {}
        else -> ProfileSubScreen(
            sp,
            deferBack = false,
            onDone = { subPage = null },
            onOpenBoard = { id -> boardFocus = id; showCheckinBoard = true },
            automationTab = automationTab
        )
    }
    // 打卡面板：数据中心里再打开的**第二层**（独立于 subPage 槽位；数据中心开着时
    // 浮在其上，返回先关面板）。两档形态都整屏浮在最上，与改版前一致。
    if (showCheckinBoard) {
        io.github.m0use001.planability.ui.plan.CheckinBoardScreen(
            initialTodoId = boardFocus,
            onDone = { showCheckinBoard = false }
        )
    }
    // 图表全局默认（与信息页右滑菜单同一份对话框）。上面「Agent 能力」组里的
    // 「图表默认设置」入口一直在置 showChartDefaults = true，但这一屏缺了对应的 if ——
    // 点了毫无反应（死入口）。照 InfoCenterScreen 的同名入口补上。
    if (showChartDefaults) io.github.m0use001.planability.ui.notes.ChartDefaultsDialog(
        onDismiss = { showChartDefaults = false },
        // 只写库：各处图表的默认值派生自 settings snapshot，写完自动刷新
        onDefaultsChanged = {}
    )

    // 2026-11-20：昵称就地编辑（对话框）——原「账号」整页只有一个输入框 + 两行链接，
    // 正是用户点名的「一整个屏幕全显示这一项设置，太浪费」。应用锁 / 备份与恢复
    // 升级成主列表的入口行（各自指向内容充实的页面）。
    if (showAccountDialog) {
        var draft by remember { mutableStateOf(settings.accountName) }
        AlertDialog(
            onDismissRequest = { showAccountDialog = false },
            title = { Text("昵称", fontWeight = FontWeight.SemiBold) },
            text = {
                Column {
                    OutlinedTextField(
                        value = draft,
                        onValueChange = { draft = it.take(12) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Planability 是纯本地应用：所有数据保存在本机 Room 数据库，不连接任何账号服务器。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    haptics.perform(io.github.m0use001.planability.core.HapticEngine.Kind.Tap)
                    val v = draft.trim()
                    if (v.isNotEmpty()) Graph.settings.update { it.copy(accountName = v) }
                    showAccountDialog = false
                }) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = { showAccountDialog = false }) { Text("取消") }
            }
        )
    }
}

@Composable
private fun SettingsGroup(title: String, content: @Composable () -> Unit) {
    // 分组标题 = 主色（原生 Gboard 的分组标题就是主色；拼色规则 R1/R6：
    // 彩色短词只上主色，第二色留给容器面与导航指示）
    Text(title, style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
    Spacer(Modifier.height(6.dp))
    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    ) {
        Column(Modifier.padding(vertical = 4.dp)) { content() }
    }
}

/**
 * 一条设置入口（图标 + 标题 + 点击）。
 *
 * 抽成数据类而不是直接写 Composable 调用，是为了**内容只有一处**：
 * 窄屏（Card 竖排）与宽屏（板内扁平行 + 细线）渲染的是同一份列表，
 * 不会出现「宽屏少一行 / 窄屏多一行」的漂移（本仓教训：五工具漏路由）。
 */
private class SettingEntry(
    val icon: ImageVector,
    val label: String,
    val onClick: () -> Unit,
)

/** 窄屏用：Card 里的行清单（无行间细线，保持手机路径逐像素一致）。 */
@Composable
private fun EntryList(entries: List<SettingEntry>) {
    entries.forEach { SettingRow(it.icon, it.label, it.onClick) }
}

/**
 * 「我的」全部二级页的路由表（v68 单状态收敛）。
 *
 * `embeddable` = 宽屏两板下可以 push 进 NavHost 栈的瓦片——条件是它能在瓦片形态
 * （透明单板 + BoardSubHeader）与整屏覆盖层间按 `deferBack` 分流。B 线属地页已全部
 * embeddable；A 线属地页逐个拆级中（v77 S1b：文件库已可钻入），未拆的保持整屏模态卡
 * （embeddable = false），返回外壳自己管理。
 */
private enum class ProfileSub(val embeddable: Boolean) {
    Appearance(true), TimeZone(true), MoreSettings(true), AppLock(true), Backup(true),
    Permissions(true), Guide(true), DataCenter(true), Achieve(true), Weekly(true),
    Trash(true), Usage(true),
    Model(true), Memory(true), ToolsHub(true), FileLibrary(true), Commands(true), Automation(true)
}

/**
 * [ProfileSub] → 子页渲染的分发口：全仓唯一一份（宽屏瓦片与整屏层两个渲染位置共用，
 * 改一处两边同时变——本仓教训「复制出来的那份下次改必漏」）。
 *
 * [onOpenBoard]：数据中心 → 打卡面板的跨层跳转（面板是独立于 subPage 的第二层）。
 * [automationTab]：自动化任务 / 导入共用一张页，段位由入口决定（状态在 [ProfileScreen]）。
 */
@Composable
private fun ProfileSubScreen(
    sub: ProfileSub,
    deferBack: Boolean,
    onDone: () -> Unit,
    onOpenBoard: (Long?) -> Unit = {},
    automationTab: Int = 0
) {
    when (sub) {
        ProfileSub.Appearance -> AppearanceScreen(onDone = onDone, deferBack = deferBack)
        ProfileSub.TimeZone -> TimeZoneScreen(onDone = onDone, deferBack = deferBack)
        ProfileSub.MoreSettings -> MoreSettingsScreen(onDone = onDone, deferBack = deferBack)
        ProfileSub.AppLock -> AppLockSettingsScreen(onDone = onDone, deferBack = deferBack)
        ProfileSub.Backup -> ExportImportScreen(onDone = onDone, deferBack = deferBack)
        ProfileSub.Permissions -> SystemPermissionsScreen(onDone = onDone, deferBack = deferBack)
        ProfileSub.Guide -> GuideScreen(onDone = onDone, deferBack = deferBack)
        ProfileSub.DataCenter -> DataCenterScreen(onDone = onDone, deferBack = deferBack, onOpenBoard = onOpenBoard)
        ProfileSub.Achieve -> AchievementWallScreen(onDone = onDone, deferBack = deferBack)
        ProfileSub.Weekly -> WeeklySummaryScreen(onDone = onDone, deferBack = deferBack)
        ProfileSub.Trash -> TrashScreen(onDone = onDone, deferBack = deferBack)
        ProfileSub.Usage -> UsageStatsScreen(onDone = onDone, deferBack = deferBack)
        // —— A 线属地页（ui/info）：逐个拆级中；已拆的按 deferBack 分流（瓦片 vs 整屏）——
        // v77 S1b：模型与智能体 Hub 拆级——宽屏钻入栈走 ModelHubSubBoard（三 tab 单板嵌入）；
        // 窄屏与未拆级入口照旧整屏覆盖层。
        ProfileSub.Model ->
            if (deferBack) io.github.m0use001.planability.ui.info.ModelHubSubBoard(onDone = onDone)
            else io.github.m0use001.planability.ui.info.ModelSettingsHub(onDone = onDone)
        // v77 S1b：记忆库/命令设置拆级——宽屏钻入栈走 MemorySubBoard（列表→表单逐级）；
        // 窄屏与未拆级入口照旧整屏覆盖层。
        ProfileSub.Memory ->
            if (deferBack) io.github.m0use001.planability.ui.info.MemorySubBoard(initialType = null, onDone = onDone)
            else MemoryScreen(onDone = onDone)
        // v77 S1b：工具与扩展 Hub 拆级——宽屏钻入栈走 ToolsHubSubBoard（三 tab 单板嵌入）
        ProfileSub.ToolsHub ->
            if (deferBack) io.github.m0use001.planability.ui.info.ToolsHubSubBoard(onDone = onDone)
            else io.github.m0use001.planability.ui.info.ToolsHub(onDone = onDone)
        // v77 S1b：文件库拆级——宽屏钻入栈走 FileLibrarySubBoard（逐级单板：目录/预览/沙箱/
        // 本机存储各推一层）；窄屏与未拆级入口照旧整屏覆盖层。
        ProfileSub.FileLibrary ->
            if (deferBack) io.github.m0use001.planability.ui.info.FileLibrarySubBoard(onDone = onDone)
            else io.github.m0use001.planability.ui.info.FileLibraryScreen(onDone = onDone)
        ProfileSub.Commands ->
            if (deferBack) io.github.m0use001.planability.ui.info.MemorySubBoard(initialType = "skill", onDone = onDone)
            else MemoryScreen(onDone = onDone, initialType = "skill")
        ProfileSub.Automation -> io.github.m0use001.planability.ui.info.AutomationScreen(
            onDone = onDone,
            initialTab = automationTab,
            embedded = deferBack
        )
    }
}

/**
 * 宽屏板内用：**扁平行 + 行间细线**，不套浮起 Card。
 *
 * 板本身已经是「圆角矩形 + 容器底」，板里再嵌一张同材质的卡就是两种材质叠在一起 ——
 * 那正是宽屏「内容块割裂」的来源。层次靠「分组标题 + 扁平行 + 细线」表达。
 */
@Composable
private fun BoardEntryList(entries: List<SettingEntry>) {
    Column(Modifier.fillMaxWidth()) {
        entries.forEachIndexed { i, e ->
            if (i > 0) BoardRowDivider()
            SettingRow(e.icon, e.label, e.onClick)
        }
    }
}

/**
 * 账号标识：头像 + 昵称 + 应用锁状态。
 *
 * 窄屏在浮卡里、宽屏在板内扁平行里，**内容由这一处提供**（两处摆位不复制文案）。
 */
@Composable
private fun AccountIdentity(name: String, lockEnabled: Boolean, modifier: Modifier = Modifier) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = modifier) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(52.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape)
        ) {
            Text(name.take(1), color = MaterialTheme.colorScheme.onPrimaryContainer,
                style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
            Text(
                if (lockEnabled) "应用锁已开启" else "本地账号 · 纯离线",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * 「我的」页的**右侧概览板**（宽屏两板形态的右板）。
 *
 * 右板不许空（未选中时也必须有真内容），所以这里放三段：
 *  1. 账号：头像 / 昵称 / 应用锁状态（板内扁平行，点它改昵称）；
 *  2. 数据与统计：使用统计 / 数据中心 / 成就墙 / 周小结 / 回收站；
 *  3. 关于：版本号 + 本机数据库占用（真数据，不是占位文案）。
 *
 * 板内**不再嵌浮起 Card** —— 板本身就是圆角矩形 + 容器底，板里再套一张卡
 * 就是第二种材质；层次靠「分组标题 + 扁平行 + 细线」。
 */
@Composable
private fun ProfileOverviewBoard(
    name: String,
    lockEnabled: Boolean,
    onAccountClick: () -> Unit,
) {
    // ---- 账号数据可视化：全部走既有仓储真数据，不新增表、不新增口径 ----
    val version by Graph.dataVersion.flow.collectAsState()
    var doneTodos by remember { mutableIntStateOf(0) }
    var schedules by remember { mutableIntStateOf(0) }
    var notes by remember { mutableIntStateOf(0) }
    var focusMin by remember { mutableIntStateOf(0) }
    var week by remember { mutableStateOf<List<Pair<java.time.LocalDate, Int>>>(emptyList()) }
    LaunchedEffect(version) {
        runCatching {
            doneTodos = Graph.plan.todoDoneCount()
            schedules = Graph.plan.scheduleCount()
            notes = Graph.notes.noteCount()
            focusMin = Graph.focus.totalMinutes()
            val today = AppTime.today()
            val sessions = Graph.focus.sessionsBetween(today.minusDays(6), today)
            week = (0..6).map { i ->
                val d = today.minusDays((6 - i).toLong())
                d to sessions.filter { it.completed && it.startDay == AppTime.dayKey(d) }
                    .sumOf { (it.end - it.start) / 60000 }.toInt()
            }
        }
    }

    Column(Modifier.fillMaxWidth()) {
        BoardHeader(title = "概览", subtitle = "账号与本机数据")

        // 账号：板内扁平行（点它改昵称），不是浮卡
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .pressScale()
                .clickableRounded(onClick = onAccountClick)
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            AccountIdentity(name, lockEnabled, Modifier.weight(1f))
            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        BoardDivider()
        // v76：标题与圆角矩形**内缩 BoardLayout.Padding**——原来这列没有水平内边距，
        // PaneTitleRow/PaneStatRow/PaneContentBlock 又是 fillMaxWidth，直接贴到板框圆角边缘
        // （用户「标题与圆角矩形都太靠框边缘了」）。与专注页概览板同款：板头自己带内边距，
        // 正文节再统一套一层 horizontal=Padding。
        Column(Modifier.fillMaxWidth().padding(horizontal = io.github.m0use001.planability.ui.common.BoardLayout.Padding)) {
        // v72.2（用户「严格改成一行标题+一个圆角矩形」「太挤」）：标题在矩形外，
        // 四格统计并进**同一个**圆角矩形（格间细竖线），不再四块各占一个矩形。
        io.github.m0use001.planability.ui.common.PaneTitleRow("数据一览")
        io.github.m0use001.planability.ui.common.PaneStatRow(
            listOf(
                "$doneTodos" to "待办完成",
                "$schedules" to "日程",
                "$notes" to "笔记",
                io.github.m0use001.planability.ui.common.formatMinutes(focusMin) to "专注"
            )
        )
        Spacer(Modifier.height(16.dp))

        io.github.m0use001.planability.ui.common.PaneTitleRow("近 7 天专注")
        io.github.m0use001.planability.ui.common.PaneContentBlock {
            ProfileWeekBars(week)
        }
        Spacer(Modifier.height(16.dp))
        }
    }
}

/**
 * 「关于」段（版本号 + 本机数据占用）——2026-11-21 从右板挪到左板（用户拍板
 * 右板只留账号与数据可视化）。版本取安装包真值；库大小后台线程读，不在重组里做文件 IO。
 */
@Composable
private fun ProfileAboutSection() {
    val ctx = LocalContext.current
    val versionName = remember(ctx) {
        runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }
            .getOrNull()?.takeIf { it.isNotBlank() } ?: "—"
    }
    var dbBytes by remember { mutableLongStateOf(0L) }
    LaunchedEffect(ctx) {
        dbBytes = withContext(Dispatchers.IO) {
            runCatching { ctx.getDatabasePath("planability.db").length() }.getOrDefault(0L)
        }
    }
    BoardSectionTitle("关于")
    InfoRow("版本", versionName)
    BoardRowDivider()
    InfoRow("本机数据", formatBytes(dbBytes))
}

/**
 * 「关于」段（版本号 + 本机数据占用）——2026-11-21 从右板挪到左板（用户拍板
 * 右板只留账号与数据可视化）。版本取安装包真值；库大小后台线程读，不在重组里做文件 IO。
 */
@Composable
private fun ProfileWeekBars(week: List<Pair<java.time.LocalDate, Int>>) {
    val barColor = MaterialTheme.colorScheme.primary
    val trackColor = MaterialTheme.colorScheme.surfaceVariant
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val maxV = week.maxOfOrNull { it.second }?.coerceAtLeast(1) ?: 1
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(
            Modifier.fillMaxWidth().height(72.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            week.forEach { (_, v) ->
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    // 底轨（让 0 值的天也有存在感，不整列消失）
                    Box(
                        Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(trackColor)
                    )
                    if (v > 0) {
                        val frac = v.toFloat() / maxV
                        Box(
                            Modifier
                                .align(Alignment.BottomCenter)
                                .fillMaxWidth()
                                .fillMaxHeight(frac.coerceIn(0.06f, 1f))
                                .clip(RoundedCornerShape(4.dp))
                                .background(barColor)
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            week.forEach { (d, _) ->
                Text(
                    "${d.monthValue}/${d.dayOfMonth}",
                    style = MaterialTheme.typography.labelSmall,
                    color = labelColor,
                    modifier = Modifier.weight(1f),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            }
        }
    }
}

/** 板内的「标签 — 值」信息行（不可点）。 */
@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** 字节数 → 人类可读。0 / 读不到时给「—」（不留「0 KB」这种看起来像坏了的字样）。 */
private fun formatBytes(bytes: Long): String = when {
    bytes <= 0L -> "—"
    bytes < 1024L * 1024L -> "${bytes / 1024L} KB"
    else -> String.format(Locale.US, "%.1f MB", bytes / 1024.0 / 1024.0)
}

/**
 * 设置行。整个「我的」体系里所有二级页的入口都是它，所以震动加在这里等于一次覆盖全页。
 *
 * 用 [io.github.m0use001.planability.core.HapticEngine.Kind.Tap]：进入二级页是中性导航动作，
 * 对应 HIG 表里 Tap 那一档（顶栏返回、展开/收起），不该用 Select（那是"选中/改变值"）
 * 也不该用 Rigid（那是底部导航那种"有分量"的切换）。
 */
@Composable
private fun SettingRow(icon: ImageVector, label: String, onClick: () -> Unit) {
    val haptics = LocalHaptics.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            // v57 C 类补漏：全页二级入口统一按压 squish——「震动加在这里等于一次覆盖全页」
            // 的同款杠杆（见上方 KDoc），视觉反馈也一次覆盖全页。
            .pressScale()
            // 2026-11-20：圆角水波（平板上「突兀的正方形」的同族病）。
            // 裸 Row 没有自己的圆角底，clickable 的 indication 会画成整块直角矩形；
            // clickableRounded 先 clip 再 clickable，水波按 16dp 圆角裁剪。
            .clickableRounded {
                haptics.perform(io.github.m0use001.planability.core.HapticEngine.Kind.Tap)
                onClick()
            }
            .padding(horizontal = 16.dp, vertical = 13.dp)
    ) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// ==================== 账号（应用锁 + 备份） ====================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppLockSettingsScreen(onDone: () -> Unit, deferBack: Boolean = false) {
    val settings by Graph.settings.snapshot.collectAsState()
    val haptics = LocalHaptics.current
    var pin by remember { mutableStateOf("") }
    PredictiveBackOverlay(onBack = onDone, deferBack = deferBack) {
        Scaffold(
            containerColor = if (deferBack) androidx.compose.ui.graphics.Color.Transparent else androidx.compose.material3.MaterialTheme.colorScheme.background,topBar = { if (deferBack) BoardSubHeader("应用锁", onDone) else TopAppBar(navigationIcon = { HapticBackButton(onDone) }, title = { Text("应用锁", fontWeight = FontWeight.SemiBold) }) }) { pad ->
            Column(Modifier.fillMaxSize().padding(pad).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("启用应用锁（启动时输入 PIN）", modifier = Modifier.weight(1f))
                    Switch(
                        checked = settings.lockEnabled,
                        onCheckedChange = { on ->
                            if (on && pin.length < 4) {
                                // 需要先设置 PIN（状态没变，所以不给触感：没发生的"选中"不该有反馈）
                            } else {
                                // 开/关都是"选择"，用最轻的 Select（HIG selection）
                                haptics.perform(HapticEngine.Kind.Select)
                                Graph.settings.update { it.copy(lockEnabled = on) }
                            }
                        }
                    )
                }
                OutlinedTextField(
                    value = pin,
                    onValueChange = { pin = it.filter { c -> c.isDigit() }.take(6) },
                    label = { Text(if (settings.lockPin.isEmpty()) "设置 PIN（4-6 位数字）" else "修改 PIN") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    modifier = Modifier.fillMaxWidth()
                )
                TextButton(onClick = {
                    if (pin.length >= 4) {
                        haptics.perform(io.github.m0use001.planability.core.HapticEngine.Kind.Success)
                        Graph.settings.update { it.copy(lockPin = pin, lockEnabled = true) }
                        pin = ""
                    } else {
                        // 没发生的保存不给「成功」触感
                        haptics.perform(io.github.m0use001.planability.core.HapticEngine.Kind.Error)
                    }
                }) { Text("保存 PIN 并开启") }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("生物识别解锁")
                        Text(
                            "优先使用指纹/面容，失败或取消后回退 PIN",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = settings.lockBiometric,
                        onCheckedChange = {
                            haptics.perform(HapticEngine.Kind.Select)
                            Graph.settings.update { it.copy(lockBiometric = !it.lockBiometric) }
                        }
                    )
                }
            }
        }
    }
}

/** 启动门：优先生物识别（可用且开启），PIN 兜底 */
@Composable
fun AppLockGate(pin: String, biometric: Boolean, onUnlocked: () -> Unit) {
    val haptics = LocalHaptics.current
    val context = androidx.compose.ui.platform.LocalContext.current
    val activity = context as? androidx.fragment.app.FragmentActivity
    val canBio = remember {
        androidx.biometric.BiometricManager.from(context).canAuthenticate(
            androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
        ) == androidx.biometric.BiometricManager.BIOMETRIC_SUCCESS
    }
    var useBio by remember { mutableStateOf(canBio && biometric) }
    var input by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }

    if (useBio && activity != null) {
        androidx.compose.runtime.LaunchedEffect(Unit) {
            val prompt = androidx.biometric.BiometricPrompt(
                activity,
                androidx.core.content.ContextCompat.getMainExecutor(activity),
                object : androidx.biometric.BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: androidx.biometric.BiometricPrompt.AuthenticationResult) {
                        onUnlocked()
                    }

                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                        // 用户取消/点了"使用 PIN" → 回退 PIN 输入
                        useBio = false
                    }
                }
            )
            val info = androidx.biometric.BiometricPrompt.PromptInfo.Builder()
                .setTitle("解锁 Planability")
                .setNegativeButtonText("使用 PIN")
                .setAllowedAuthenticators(androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK)
                .build()
            runCatching { prompt.authenticate(info) }.onFailure { useBio = false }
        }
    }

    Box(
        // 2026-11-02 键盘筛查：主窗口 edge-to-edge 下 Manifest 的 adjustResize 失效，
        // 居中布局的 PIN 框会被键盘上沿盖住——imePadding 让整块内容在键盘弹出时上移
        Modifier.fillMaxSize().imePadding().background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Rounded.Lock, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(44.dp))
            Text("Planability 已锁定", style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold, modifier = Modifier.padding(vertical = 12.dp))
            OutlinedTextField(
                value = input,
                onValueChange = {
                    input = it.filter { c -> c.isDigit() }.take(6)
                    error = false
                    if (input == pin) onUnlocked()
                },
                label = { Text(if (useBio) "生物识别已调起 · 也可输入 PIN" else "输入 PIN") },
                isError = error,
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                modifier = Modifier.width(200.dp)
            )
            if (error) Text("PIN 错误", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
            if (canBio && biometric) {
                TextButton(onClick = {
                    haptics.perform(io.github.m0use001.planability.core.HapticEngine.Kind.Tap)
                    useBio = true
                }) { Text("使用指纹/面容解锁") }
            }
        }
    }
}

// ==================== 备份与恢复 ====================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExportImportScreen(onDone: () -> Unit, deferBack: Boolean = false) {
    val haptics = LocalHaptics.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<String?>(null) }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) {
            scope.launch {
                status = "正在导出…"
                runCatching {
                    val json = Graph.backup.exportJson()
                    context.contentResolver.openOutputStream(uri)?.use {
                        it.write(json.toByteArray(Charsets.UTF_8))
                    }
                }.onSuccess {
                    status = "导出完成 ✓"
                    Graph.appScope.launch { unlockBackupAchievement() }
                }.onFailure { status = "导出失败：${it.message}" }
            }
        }
    }
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                status = "正在导入…"
                runCatching {
                    val text = context.contentResolver.openInputStream(uri)?.use {
                        it.readBytes().toString(Charsets.UTF_8)
                    } ?: ""
                    if (!Graph.backup.importJson(text)) throw Exception("文件格式不正确")
                }.onSuccess { status = "导入完成 ✓（已覆盖本地数据）" }
                    .onFailure { status = "导入失败：${it.message}" }
            }
        }
    }
    val zipExportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        if (uri != null) {
            scope.launch {
                status = "正在打包备份…"
                runCatching {
                    context.contentResolver.openOutputStream(uri)?.use {
                        Graph.backup.exportZip(context, it)
                    } ?: throw Exception("无法写入目标文件")
                }.onSuccess {
                    status = "备份包导出完成 ✓"
                    Graph.appScope.launch { unlockBackupAchievement() }
                }.onFailure { status = "导出失败：${it.message}" }
            }
        }
    }
    val zipImportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                status = "正在导入备份包…"
                runCatching {
                    val ok = context.contentResolver.openInputStream(uri)?.use {
                        Graph.backup.importZip(context, it)
                    } ?: false
                    if (!ok) throw Exception("备份包格式不正确（缺 data.json）")
                }.onSuccess { status = "备份包导入完成 ✓（已覆盖本地数据）" }
                    .onFailure { status = "导入失败：${it.message}" }
            }
        }
    }

    PredictiveBackOverlay(onBack = onDone, deferBack = deferBack) {
        Scaffold(
            containerColor = if (deferBack) androidx.compose.ui.graphics.Color.Transparent else androidx.compose.material3.MaterialTheme.colorScheme.background,topBar = { if (deferBack) BoardSubHeader("备份与恢复", onDone) else TopAppBar(navigationIcon = { HapticBackButton(onDone) }, title = { Text("备份与恢复", fontWeight = FontWeight.SemiBold) }) }) { pad ->
            Column(
                Modifier.fillMaxSize().padding(pad).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    "JSON 备份包含全部数据库内容；zip 备份包额外打包 PDF 笔记、笔记录音与附件等文件。导入会覆盖现有数据。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedButton(onClick = {
                    haptics.perform(io.github.m0use001.planability.core.HapticEngine.Kind.Tap)
                    zipExportLauncher.launch("planability-backup-${AppTime.today()}.zip")
                }, modifier = Modifier.fillMaxWidth()) { Text("导出完整备份包（zip · 含附件文件）") }
                OutlinedButton(onClick = {
                    haptics.perform(io.github.m0use001.planability.core.HapticEngine.Kind.Tap)
                    zipImportLauncher.launch(arrayOf("application/zip"))
                }, modifier = Modifier.fillMaxWidth()) { Text("从备份包导入（覆盖现有数据）") }
                OutlinedButton(onClick = {
                    haptics.perform(io.github.m0use001.planability.core.HapticEngine.Kind.Tap)
                    exportLauncher.launch("planability-backup-${AppTime.today()}.json")
                }, modifier = Modifier.fillMaxWidth()) { Text("仅导出数据（json）") }
                OutlinedButton(onClick = {
                    haptics.perform(io.github.m0use001.planability.core.HapticEngine.Kind.Tap)
                    importLauncher.launch(arrayOf("application/json"))
                }, modifier = Modifier.fillMaxWidth()) { Text("从文件导入（覆盖现有数据）") }
                status?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
            }
        }
    }
}

// ==================== 外观（字体/字重/震动 全量版） ====================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppearanceScreen(onDone: () -> Unit, deferBack: Boolean = false) {
    val settings by Graph.settings.snapshot.collectAsState()
    val haptics = LocalHaptics.current
    val theme = settings.theme

    fun upd(t: io.github.m0use001.planability.data.ThemeConfig) {
        Graph.settings.update { it.copy(theme = t) }
    }

    // 三个滑块都按"跨过一格振一次"，不逐像素振（连续振连成一片只有麻木感，
    // 详见 core/Haptics.kt 的 HapticTicks 与其注释）
    val weightTicks = remember(haptics) { HapticTicks(haptics, 25f) }    // 字重 0–500：每 25 一格
    val scaleTicks = remember(haptics) { HapticTicks(haptics, 0.05f) }   // 字号 0.8–1.3：每 5% 一格
    val intensityTicks = remember(haptics) { HapticTicks(haptics, 5f) }  // 触感强度 0–150：每 5% 一格

    // 滑动条配色必须自己给：Material3 新版 SliderDefaults 的**未激活轨道**用的是
    // `secondaryContainer`，而本项目色板里 secondary 是 primary 偏移 +28° 色相
    // （紫 → 粉紫）。浅色模式下那就是一条明显的**粉色**轨道，和蓝色主色完全不搭
    // （用户反馈「滑动条颜色有问题」）。改成中性色阶，强调只用主色。
    val sliderColors = androidx.compose.material3.SliderDefaults.colors(
        thumbColor = MaterialTheme.colorScheme.primary,
        activeTrackColor = MaterialTheme.colorScheme.primary,
        inactiveTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        activeTickColor = MaterialTheme.colorScheme.onPrimary,
        inactiveTickColor = MaterialTheme.colorScheme.outlineVariant
    )

    PredictiveBackOverlay(onBack = onDone, deferBack = deferBack) {
        Scaffold(
            containerColor = if (deferBack) androidx.compose.ui.graphics.Color.Transparent else androidx.compose.material3.MaterialTheme.colorScheme.background,topBar = { if (deferBack) BoardSubHeader("外观", onDone) else TopAppBar(navigationIcon = { HapticBackButton(onDone) }, title = { Text("外观", fontWeight = FontWeight.SemiBold) }) }) { pad ->
            val lookScroll = rememberScrollState()
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(pad)
                    .verticalScroll(lookScroll)
                    .reachedEndHaptic(lookScroll)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // ---------- 主题模式 ----------
                Text("主题模式", style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(0 to "跟随系统", 1 to "浅色", 2 to "深色").forEach { (m, label) ->
                        val selected = theme.mode == m
                        OutlinedButton(
                            onClick = {
                                // 分段选择 = HIG selection
                                haptics.perform(io.github.m0use001.planability.core.HapticEngine.Kind.Select)
                                upd(theme.copy(mode = m))
                            },
                            colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(
                                containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer
                                else MaterialTheme.colorScheme.surface
                            )
                        ) {
                            Text(
                                label,
                                color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
                                else MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }

                // ---------- 配色（拼色 / 其他颜色） ----------
                Text("配色", style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                var colorTab by remember(theme.colorSource) {
                    mutableStateOf(if (theme.colorSource == 0) 0 else 1)
                }
                AppButtonGroup(
                    options = listOf("拼色", "其他颜色"),
                    selectedIndex = colorTab,
                    onSelect = { colorTab = it },
                    modifier = Modifier.width(250.dp)
                )
                if (colorTab == 0) {
                    // 内置四套方案：颜色照抄系统原生取色器（浅/深各一套），不取壁纸。
                    // 之前从壁纸 hint 取色，因 ROM/壁纸差异常出灰或同色，用户多轮反馈后
                    // 改为固定配色（2026-10-24）。
                    Text(
                        "内置四套方案（照抄系统原生的配色）",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                    // 明暗口径与 PlanabilityTheme 同源（mode 0/1/2）
                    val dark = when (theme.mode) { 1 -> false; 2 -> true; else -> isSystemInDarkTheme() }
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.padding(top = 8.dp)
                    ) {
                        for (i in FIXED_STYLE_LABELS.indices) {
                            val triad = fixedStyleTriad(i, dark)
                            val selected = theme.colorSource == 0 && theme.wallpaperVariant == i
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Canvas(
                                    Modifier
                                        .size(56.dp)
                                        .clip(CircleShape)
                                        .clickable {
                                            haptics.perform(io.github.m0use001.planability.core.HapticEngine.Kind.Select)
                                            upd(theme.copy(colorSource = 0, wallpaperVariant = i))
                                        }
                                ) {
                                    val half = size.minDimension / 2f
                                    // 上半=primary、左下=secondary、右下=tertiary（与原生预览圆一致）
                                    drawArc(triad[0], startAngle = 180f, sweepAngle = 180f, useCenter = true)
                                    drawArc(triad[1], startAngle = 90f, sweepAngle = 90f, useCenter = true)
                                    drawArc(triad[2], startAngle = 0f, sweepAngle = 90f, useCenter = true)
                                    if (selected) drawCircle(
                                        color = triad[0],
                                        radius = half - 3f,
                                        style = Stroke(width = 5f)
                                    )
                                }
                                // 每个圆下面标名字：不加标签时「黑白」容易被当成"没上色"
                                Text(
                                    FIXED_STYLE_LABELS[i],
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (selected) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(top = 6.dp)
                                )
                            }
                        }
                    }
                } else {
                    // 8 颗预设（palette 4..11）：FlowRow 自动换行，窄屏也不会挤出卡片
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        // 预设种子集中在 Theme.kt 的 PRESET_LIGHTS / COLOR_SEEDS：
                        // 启动器图标要按同一份顺序生成配色矩阵（palette 4..11），
                        // 这里再抄一份就会悄悄错档。
                        io.github.m0use001.planability.ui.theme.COLOR_SEEDS.forEach { seedColor ->
                            val seed = seedColor.toArgb()
                            val selected = theme.colorSource == 1 && theme.seed == seed
                            Box(
                                Modifier
                                    .size(38.dp)
                                    .clip(CircleShape)
                                    .background(androidx.compose.ui.graphics.Color(seed))
                                    .border(
                                        width = if (selected) 3.dp else 0.dp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        shape = CircleShape
                                    )
                                    .clickable {
                                        haptics.perform(io.github.m0use001.planability.core.HapticEngine.Kind.Select)
                                        upd(theme.copy(seed = seed, colorSource = 1))
                                    }
                            )
                        }
                    }
                }

                // ---------- 代码配色（2026-10-30 用户：代码块自动涂色 + 两套方案可选） ----------
                Text("代码配色", style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(
                        // 完全复刻：与 VS Code 默认主题（Dark+/Light+）逐色一致，不随 app 配色变化
                        "vscode" to "VS Code 复刻",
                        // 派生：保留 VS Code 的色相关系，色相整体旋转到当前主题主色
                        "theme" to "跟随主题色"
                    ).forEach { (key, label) ->
                        val selected = theme.codeHlStyle == key
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = if (selected) MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.surfaceContainer,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    haptics.perform(io.github.m0use001.planability.core.HapticEngine.Kind.Select)
                                    upd(theme.copy(codeHlStyle = key))
                                }
                        ) {
                            Row(
                                Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    label,
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
                                    else MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.weight(1f)
                                )
                                // 小色条预览：VS Code 复刻=官方色；跟随主题=按当前主题色派生
                                val previewStyle = io.github.m0use001.planability.ui.common.CodeHighlight.Style.of(key)
                                val pv = io.github.m0use001.planability.ui.common.CodeHighlight
                                    .paletteFor(MaterialTheme.colorScheme, previewStyle)
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    listOf(
                                        pv.comment, pv.string, pv.number, pv.keywordControl,
                                        pv.keywordDecl, pv.type, pv.function, pv.variable
                                    ).forEach { c ->
                                        Box(
                                            Modifier
                                                .size(10.dp)
                                                .clip(CircleShape)
                                                .background(c)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // ---------- 字体 ----------
                Text("字体", style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(
                        "default" to "系统默认",
                        // M3 Expressive 拉丁字体（OFL，附带 ROND 圆角轴，标题层已启用）——中文自动回退系统 CJK
                        "google_sans_flex" to "Google Sans Flex",
                        "noto_sc" to "思源黑体",
                        // M3E 圆体中文字体：**只接管中文**，拉丁/数字/符号仍走 Google Sans Flex
                        "wen_yuan_rounded" to "文渊圆体",
                        "yuan_975" to "975 圆体",
                        "rhr" to "资源圆体（圆度轴）"
                    ).forEach { (key, label) ->
                        val selected = theme.fontFamily == key
                        Card(
                            shape = RoundedCornerShape(12.dp),
                            colors = androidx.compose.material3.CardDefaults.cardColors(
                                containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer
                                else MaterialTheme.colorScheme.surfaceContainer
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    haptics.perform(io.github.m0use001.planability.core.HapticEngine.Kind.Select)
                                    upd(theme.copy(fontFamily = key))
                                }
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
                            ) {
                                Text(
                                    label,
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
                                    else MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.weight(1f)
                                )
                                if (selected) {
                                    Text("✓", color = MaterialTheme.colorScheme.primary,
                                        fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }

                // ---------- 字重 ----------
                Text("字重偏移：${theme.fontWeight}", style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                androidx.compose.material3.Slider(
                    value = theme.fontWeight.toFloat(),
                    onValueChange = { v -> weightTicks.onValue(v); upd(theme.copy(fontWeight = v.roundToInt())) },
                    valueRange = 0f..500f,
                    colors = sliderColors
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Aa", style = MaterialTheme.typography.titleLarge.copy(
                        fontWeight = FontWeight((400 + theme.fontWeight).coerceIn(100, 900))
                    ))
                    Text("永", style = MaterialTheme.typography.titleLarge.copy(
                        fontWeight = FontWeight((400 + theme.fontWeight).coerceIn(100, 900))
                    ))
                    Text("日", style = MaterialTheme.typography.titleLarge.copy(
                        fontWeight = FontWeight((400 + theme.fontWeight).coerceIn(100, 900))
                    ))
                    Text("月", style = MaterialTheme.typography.titleLarge.copy(
                        fontWeight = FontWeight((400 + theme.fontWeight).coerceIn(100, 900))
                    ))
                    TextButton(onClick = {
                        haptics.perform(io.github.m0use001.planability.core.HapticEngine.Kind.Tap)
                        upd(theme.copy(fontWeight = 0))
                    }) { Text("复位") }
                }

                // ---------- 字号 ----------
                Text("字号缩放：${(theme.fontScale * 100).toInt()}%", style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                androidx.compose.material3.Slider(
                    value = theme.fontScale,
                    onValueChange = { v -> scaleTicks.onValue(v); upd(theme.copy(fontScale = v)) },
                    valueRange = 0.8f..1.3f,
                    colors = sliderColors
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(0.85f, 1.0f, 1.15f).forEach { s ->
                        OutlinedButton(onClick = {
                            haptics.perform(io.github.m0use001.planability.core.HapticEngine.Kind.Select)
                            upd(theme.copy(fontScale = s))
                        }) {
                            Text("${(s * 100).toInt()}%")
                        }
                    }
                }

                // ---------- 开屏动画（2026-10-28 用户：自定义开屏） ----------
                // 冷启动盖在最上层播一次，点按跳过；"关"连一帧都不播（见 ui/common/LaunchAnim.kt）
                Text(
                    "开屏动画",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold
                )
                AppButtonGroup(
                    options = listOf("关", "淡入", "缩放", "上滑"),
                    selectedIndex = settings.launchAnim,
                    onSelect = { idx ->
                        haptics.perform(io.github.m0use001.planability.core.HapticEngine.Kind.Select)
                        Graph.settings.update { it.copy(launchAnim = idx) }
                    }
                )

                // ---------- 触感强度 ----------
                Text("触感强度：${theme.hapticIntensity}%", style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                androidx.compose.material3.Slider(
                    value = theme.hapticIntensity.toFloat(),
                    onValueChange = { v -> intensityTicks.onValue(v); upd(theme.copy(hapticIntensity = v.roundToInt())) },
                    valueRange = 0f..150f,
                    colors = sliderColors
                )
                // 五个试听按钮原来挤在一个 Row 里，屏幕上根本放不下——最后一个「错误」
                // 会被压成一条缝（用户反馈「错误文字按钮显示异常」）。
                // 换成 FlowRow：放不下就换行，每个按钮保持自己的尺寸。
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf("轻" to io.github.m0use001.planability.core.HapticEngine.Kind.Tap,
                        "中" to io.github.m0use001.planability.core.HapticEngine.Kind.Rigid,
                        "重" to io.github.m0use001.planability.core.HapticEngine.Kind.ImpactHeavy,
                        "成功" to io.github.m0use001.planability.core.HapticEngine.Kind.Success,
                        "错误" to io.github.m0use001.planability.core.HapticEngine.Kind.Error).forEach { (label, kind) ->
                        OutlinedButton(onClick = { haptics.perform(kind) }) { Text(label) }
                    }
                }
                Text(
                    "超过 100% 时振幅拉满并延长时长，手感明显强于系统默认。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                // ---------- 应用图标预览（明/暗） ----------
                Text("应用图标", style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                // 预览必须跟桌面同一个来源：palette 序号 + 明暗 → 那套前景，
                // 不能拿固定资源画——否则用户换了配色，这里还显示上一套，看着像没生效。
                val iconPalette = io.github.m0use001.planability.LauncherNightIcon.paletteIndexOf(theme)
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    listOf(
                        Triple("浅色", androidx.compose.ui.graphics.Color(0xFFF4F4F8),
                            io.github.m0use001.planability.LauncherNightIcon.fgRes(iconPalette, false)),
                        Triple("深色", androidx.compose.ui.graphics.Color(0xFF17171C),
                            io.github.m0use001.planability.LauncherNightIcon.fgRes(iconPalette, true))
                    ).forEach { (label, bg, fgRes) ->
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Box(
                                Modifier
                                    .size(64.dp)
                                    .clip(androidx.compose.foundation.shape.CircleShape)
                                    .background(bg)
                            ) {
                                // adaptive-icon 的 108 单位画布里只有中间 72 是"可见框"，
                                // 桌面按 mask 裁切后剩下的就是这个框；前景矢量套 108/72 = 1.5 倍，
                                // 预览里的比例才和桌面一致（直接用 adaptive-icon 画不了：
                                // 它不是 VectorDrawable，painterResource 会抛
                                // "Only VectorDrawables and rasterized asset types are supported"）。
                                androidx.compose.foundation.Image(
                                    androidx.compose.ui.res.painterResource(fgRes),
                                    contentDescription = null,
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .scale(1.5f)
                                )
                            }
                            Text(label, style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                Text(
                    "桌面图标跟随上面选的配色（拼色四套 / 其他颜色八个预设），并随系统深浅色自动切换明暗那套；Android 13 主题单色图标另用剪影层。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(Modifier.height(20.dp))
            }
        }
    }
}

// ==================== 时区 ====================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimeZoneScreen(onDone: () -> Unit, deferBack: Boolean = false) {
    val settings by Graph.settings.snapshot.collectAsState()
    var q by remember { mutableStateOf("") }
    val override = settings.zoneOverride
    // v19.14：「跟随系统」那行显示**系统时区**，不是生效后的时区（否则选完覆盖后这行会跟着变，误导）
    val systemZoneId = remember { java.util.TimeZone.getDefault().id }
    // v19.14：原来要用户手填 IANA ID —— 改成可搜城市名的列表，点一下就生效
    val allIds = remember {
        java.util.TimeZone.getAvailableIDs()
            .filter { it.contains('/') && !it.startsWith("SystemV/") && !it.startsWith("Etc/") }
            .sortedWith(compareBy({ ZONE_CN[it] == null }, { ZONE_CN[it] ?: it }))
    }
    val shown = remember(q) {
        val k = q.trim()
        if (k.isBlank()) allIds
        else allIds.filter {
            it.contains(k, ignoreCase = true) ||
                it.substringAfterLast('/').replace('_', ' ').contains(k, ignoreCase = true) ||
                (ZONE_CN[it]?.contains(k) == true) ||
                ZONE_CN.entries.any { (_, cn) -> cn.contains(k) && k.length >= 2 && it.startsWith(k, true) }
        }
    }
    fun pick(id: String?) {
        Graph.settings.update { it.copy(zoneOverride = id) }
    }

    PredictiveBackOverlay(onBack = onDone, deferBack = deferBack) {
        Scaffold(
            containerColor = if (deferBack) androidx.compose.ui.graphics.Color.Transparent else androidx.compose.material3.MaterialTheme.colorScheme.background,
            topBar = {
                if (deferBack) BoardSubHeader("时区", onDone) else TopAppBar(
                    navigationIcon = { HapticBackButton(onDone) },
                    title = { Text("时区", fontWeight = FontWeight.SemiBold) }
                )
            }
        ) { pad ->
            Column(Modifier.fillMaxSize().padding(pad)) {
                OutlinedTextField(
                    value = q, onValueChange = { q = it },
                    placeholder = { Text("搜城市或时区：上海 / Tokyo / Asia") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
                )
                val haptics = LocalHaptics.current
                val zoneList = rememberLazyListState()
                LazyColumn(
                    Modifier.fillMaxSize()
                        .padding(horizontal = 8.dp)
                        .reachedEndHaptic(zoneList)
                ) {
                    item {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .clickable {
                                    haptics.perform(io.github.m0use001.planability.core.HapticEngine.Kind.Select)
                                    pick(null)
                                }
                                .padding(horizontal = 12.dp, vertical = 12.dp)
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text("跟随系统", style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    systemZoneId,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            if (override == null) {
                                Icon(Icons.Rounded.Check, "当前使用", tint = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                    if (shown.isEmpty()) {
                        item {
                            Text(
                                "没找到「$q」。也可以试英文城市名或时区 ID（如 Tokyo、Europe/Paris）。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(16.dp)
                            )
                        }
                    }
                    items(shown) { id ->
                        val cn = ZONE_CN[id]
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .clickable {
                                    haptics.perform(io.github.m0use001.planability.core.HapticEngine.Kind.Select)
                                    pick(id)
                                }
                                .padding(horizontal = 12.dp, vertical = 12.dp)
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    cn ?: id.substringAfterLast('/').replace('_', ' '),
                                    style = MaterialTheme.typography.bodyLarge
                                )
                                Text(
                                    id,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            if (override == id) {
                                Icon(Icons.Rounded.Check, "当前使用", tint = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                }
            }
        }
    }
}

/** v19.14：常用时区中文名（列表里显示城市名，ID 只作副行） */
private val ZONE_CN: Map<String, String> = mapOf(
    "Asia/Shanghai" to "上海", "Asia/Beijing" to "北京", "Asia/Chongqing" to "重庆",
    "Asia/Hong_Kong" to "香港", "Asia/Macau" to "澳门", "Asia/Taipei" to "台北",
    "Asia/Tokyo" to "东京", "Asia/Seoul" to "首尔", "Asia/Singapore" to "新加坡",
    "Asia/Kuala_Lumpur" to "吉隆坡", "Asia/Bangkok" to "曼谷", "Asia/Jakarta" to "雅加达",
    "Asia/Manila" to "马尼拉", "Asia/Ho_Chi_Minh" to "胡志明市", "Asia/Kolkata" to "加尔各答",
    "Asia/Dubai" to "迪拜", "Asia/Jerusalem" to "耶路撒冷", "Asia/Karachi" to "卡拉奇",
    "Asia/Istanbul" to "伊斯坦布尔(旧别名)",
    "Asia/Almaty" to "阿拉木图", "Asia/Tashkent" to "塔什干",
    "Asia/Yekaterinburg" to "叶卡捷琳堡", "Asia/Novosibirsk" to "新西伯利亚", "Asia/Vladivostok" to "海参崴",
    "Australia/Sydney" to "悉尼", "Australia/Melbourne" to "墨尔本", "Australia/Brisbane" to "布里斯班",
    "Australia/Perth" to "珀斯", "Australia/Adelaide" to "阿德莱德", "Pacific/Auckland" to "奥克兰",
    "Pacific/Honolulu" to "檀香山", "Pacific/Fiji" to "斐济",
    "Europe/London" to "伦敦", "Europe/Dublin" to "都柏林", "Europe/Lisbon" to "里斯本",
    "Europe/Madrid" to "马德里", "Europe/Paris" to "巴黎", "Europe/Brussels" to "布鲁塞尔",
    "Europe/Amsterdam" to "阿姆斯特丹", "Europe/Berlin" to "柏林", "Europe/Zurich" to "苏黎世",
    "Europe/Vienna" to "维也纳", "Europe/Prague" to "布拉格", "Europe/Rome" to "罗马",
    "Europe/Copenhagen" to "哥本哈根", "Europe/Oslo" to "奥斯陆", "Europe/Stockholm" to "斯德哥尔摩",
    "Europe/Helsinki" to "赫尔辛基", "Europe/Warsaw" to "华沙", "Europe/Budapest" to "布达佩斯",
    "Europe/Athens" to "雅典", "Europe/Kiev" to "基辅", "Europe/Moscow" to "莫斯科",
    "Europe/Istanbul" to "伊斯坦布尔",
    "America/New_York" to "纽约", "America/Chicago" to "芝加哥", "America/Denver" to "丹佛",
    "America/Los_Angeles" to "洛杉矶", "America/Anchorage" to "安克雷奇", "America/Toronto" to "多伦多",
    "America/Vancouver" to "温哥华", "America/Mexico_City" to "墨西哥城", "America/Bogota" to "波哥大",
    "America/Sao_Paulo" to "圣保罗", "America/Argentina/Buenos_Aires" to "布宜诺斯艾利斯",
    "America/Santiago" to "圣地亚哥", "Africa/Cairo" to "开罗", "Africa/Lagos" to "拉各斯",
    "Africa/Nairobi" to "内罗毕", "Africa/Johannesburg" to "约翰内斯堡", "Atlantic/Reykjavik" to "雷克雅未克"
)

// ==================== 底栏排序（并入「更多设置」） ====================

/**
 * 底栏排序的**行清单**（原独立二级页，2026-11-20 并进 [MoreSettingsScreen] 的
 * 「底栏排序」段——一页只有这一点内容，不值得占一整屏）。
 */
@Composable
private fun NavOrderSection() {
    val haptics = LocalHaptics.current
    val settings by Graph.settings.snapshot.collectAsState()
    val specs = tabSpecs()
    // 底栏还原 7 格后，可排序/可隐藏宇宙回到全部页签（B1-A 的 5 格收窄已随「移回来」撤销）。
    // 存量 navOrder 里的缺项由调用方兜底（pager 常驻全部页，深链不受影响）。
    val order = settings.navOrder.filter { specs.containsKey(it) }
    Text("调整一级页顺序与显隐（隐藏的页不出现在底栏）",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.height(8.dp))
    order.forEachIndexed { idx, id ->
        val spec = specs[id] ?: return@forEachIndexed
        val hidden = id in settings.hiddenTabs
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)
        ) {
            Icon(spec.icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(10.dp))
            Text(spec.label, modifier = Modifier.weight(1f))
            Checkbox(checked = !hidden, onCheckedChange = { show ->
                haptics.perform(io.github.m0use001.planability.core.HapticEngine.Kind.Select)
                Graph.settings.update {
                    it.copy(hiddenTabs = if (show) it.hiddenTabs - id else it.hiddenTabs + id)
                }
            })
            IconButton(enabled = idx > 0, onClick = {
                haptics.perform(io.github.m0use001.planability.core.HapticEngine.Kind.Tap)
                Graph.settings.update {
                    val o = it.navOrder.toMutableList()
                    o[idx] = o[idx - 1].also { o[idx - 1] = o[idx] }
                    it.copy(navOrder = o)
                }
            }) { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowLeft, "上移") }
            IconButton(enabled = idx < order.size - 1, onClick = {
                haptics.perform(io.github.m0use001.planability.core.HapticEngine.Kind.Tap)
                Graph.settings.update {
                    val o = it.navOrder.toMutableList()
                    o[idx] = o[idx + 1].also { o[idx + 1] = o[idx] }
                    it.copy(navOrder = o)
                }
            }) { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, "下移") }
        }
    }
}

// ==================== 更多设置（计划 / 开关 / 目标） ====================

/**
 * 2026-11-20 用户「1 个页面全显示一项设置也太浪费了」：原「功能开关」（4 个开关）、
 * 「计划设置」（2 个开关）、「目标设定」（2 个输入框）、「底栏排序」（7 行拖动）
 * **各占一整页**——一页只有一点点内容，跳进去还要跳出来。四张合成一页，
 * 按段分组（计划 / 开关 / 目标 / 底栏排序）。
 *
 * 各原二级页的行、文案、写入字段**逐字保留**（只挪位置不改语义）；
 * 「我的」主列表只留一个「更多设置」入口。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MoreSettingsScreen(onDone: () -> Unit, deferBack: Boolean = false) {
    val settings by Graph.settings.snapshot.collectAsState()
    val haptics = LocalHaptics.current
    // 后台冻结：状态不在 SettingsSnapshot（纯旁路 KV，见 app/UiFreeze.kt），
    // 所以本行自己读内存镜像 + 异步切换；其余行读 snapshot。
    val freezeScope = rememberCoroutineScope()
    var freezeOn by remember { mutableStateOf(io.github.m0use001.planability.app.UiFreeze.enabled) }
    LaunchedEffect(io.github.m0use001.planability.app.UiFreeze.enabled) {
        freezeOn = io.github.m0use001.planability.app.UiFreeze.enabled
    }
    // 目标草稿：remember(settings.x) 让在别处改过的值也能跟上来
    var focusMin by remember(settings.focusGoalMin) { mutableStateOf(settings.focusGoalMin.toString()) }
    var todoGoal by remember(settings.todoGoal) { mutableStateOf(settings.todoGoal.toString()) }
    val sectionLabel: @Composable (String) -> Unit = { title ->
        Text(title, style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
    }
    val sectionCard: @Composable (@Composable () -> Unit) -> Unit = { content ->
        Card(
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
        ) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) { content() }
        }
        Spacer(Modifier.height(14.dp))
    }
    PredictiveBackOverlay(onBack = onDone, deferBack = deferBack) {
        Scaffold(
            containerColor = if (deferBack) androidx.compose.ui.graphics.Color.Transparent else androidx.compose.material3.MaterialTheme.colorScheme.background,topBar = { if (deferBack) BoardSubHeader("更多设置", onDone) else TopAppBar(navigationIcon = { HapticBackButton(onDone) }, title = { Text("更多设置", fontWeight = FontWeight.SemiBold) }) }) { pad ->
            Column(
                Modifier.fillMaxSize().padding(pad).padding(16.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                sectionLabel("计划")
                sectionCard {
                    ToggleSetting(
                        "计划页显示紧急·重要胶囊",
                        "开＝每行额外显示「紧急」「重要」两颗胶囊，颜色按各自档位（和编辑器双滑块同源）",
                        settings.planColorByUrgencyImportance
                    ) {
                        Graph.settings.update {
                            it.copy(planColorByUrgencyImportance = !it.planColorByUrgencyImportance)
                        }
                    }
                    // 2026-10-21（用户要求）：备注常常很长，默认不往时间轴上塞；
                    // 想要的自己开，且只在块够高（≥88dp）时才画，避免把标题挤没。
                    ToggleSetting(
                        "首页时间轴显示备注",
                        "开＝日程/待办块里追加显示备注（一般两行，很长的块三行）。块不到一小时仍不显示，优先保证标题、地点与时间",
                        settings.timelineShowNote
                    ) {
                        Graph.settings.update {
                            it.copy(timelineShowNote = !it.timelineShowNote)
                        }
                    }
                }
                sectionLabel("功能开关")
                sectionCard {
                    ToggleSetting("左缘搜索抽屉", "一级页左缘右滑唤出全局搜索", settings.searchEdgeEnabled) {
                        Graph.settings.update { it.copy(searchEdgeEnabled = !it.searchEdgeEnabled) }
                    }
                    ToggleSetting("剪贴板导入浮条", "检测到复制的文本时提示一键建待办", settings.clipboardEnabled) {
                        Graph.settings.update { it.copy(clipboardEnabled = !it.clipboardEnabled) }
                    }
                    ToggleSetting("专注悬浮胶囊", "专注进行中时系统悬浮窗显示剩余时间，可拖动；空闲不出现", settings.floatBallEnabled) {
                        Graph.settings.update { it.copy(floatBallEnabled = !it.floatBallEnabled) }
                    }
                    ToggleSetting(
                        "后台冻结",
                        "杀掉应用后重新打开时，自动回到最后一次停留的界面与筛选状态（不是阻止系统回收，是重进直接回去）",
                        freezeOn
                    ) {
                        freezeOn = !freezeOn
                        freezeScope.launch {
                            io.github.m0use001.planability.app.UiFreeze.setEnabled(freezeOn)
                        }
                    }
                }
                sectionLabel("目标")
                sectionCard {
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedTextField(
                            value = focusMin,
                            onValueChange = { focusMin = it.filter { c -> c.isDigit() }.take(4) },
                            label = { Text("每日专注目标（分钟）") }, singleLine = true, modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = todoGoal,
                            onValueChange = { todoGoal = it.filter { c -> c.isDigit() }.take(3) },
                            label = { Text("每日待办完成目标（个）") }, singleLine = true, modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedButton(onClick = {
                            haptics.perform(io.github.m0use001.planability.core.HapticEngine.Kind.Success)
                            Graph.settings.update {
                                it.copy(focusGoalMin = focusMin.toIntOrNull() ?: 120, todoGoal = todoGoal.toIntOrNull() ?: 5)
                            }
                        }) { Text("保存目标") }
                    }
                }
                sectionLabel("底栏排序")
                NavOrderSection()
                Spacer(Modifier.height(io.github.m0use001.planability.app.navAvoidPadding().calculateBottomPadding()))
            }
        }
    }
}

@Composable
private fun ToggleSetting(label: String, desc: String, checked: Boolean, onToggle: () -> Unit) {
    val haptics = LocalHaptics.current
    Row(verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Text(desc, style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        // 「功能开关 / 计划设置 / 目标设定」整批开关都走这里，一处补 = 一批都有手感
        Switch(checked = checked, onCheckedChange = {
            haptics.perform(HapticEngine.Kind.Select)
            onToggle()
        })
    }
}

// ==================== 使用指引 ====================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GuideScreen(onDone: () -> Unit, deferBack: Boolean = false) {
    PredictiveBackOverlay(onBack = onDone, deferBack = deferBack) {
        Scaffold(
            containerColor = if (deferBack) androidx.compose.ui.graphics.Color.Transparent else androidx.compose.material3.MaterialTheme.colorScheme.background,topBar = { if (deferBack) BoardSubHeader("使用指引", onDone) else TopAppBar(navigationIcon = { HapticBackButton(onDone) }, title = { Text("使用指引", fontWeight = FontWeight.SemiBold) }) }) { pad ->
            val guideScroll = rememberScrollState()
            Column(
                Modifier.fillMaxSize().padding(pad)
                    .verticalScroll(guideScroll).reachedEndHaptic(guideScroll).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                guideItem("首页 · 整点时间轴", "按小时规划一天：点任意小时格新建整点任务；顶部「待办」区勾选打卡；点日期打开月历/年历。")
                guideItem("计划", "管理日程、待办与倒数日。待办支持每天/每周几/间隔重复、常驻显示与打卡监督（连续天数统计）。")
                guideItem("专注", "只做三件事：番茄钟计时（锁屏也走通知栏倒计时）、悬浮球（需悬浮窗权限）、专注统计。")
                guideItem("笔记", "笔记本内可新建 Markdown 笔记（编辑/预览）或无边记（单指作画、双指平移缩放）。左上回收站可恢复删除的笔记。")
                guideItem("信息", "粘贴群聊文本 → AI 提纯要点 → 一键总结；AI 可抽取日程/待办进入「待处理」，确认后写入计划页。需先在「模型管理」配置 API。")
                guideItem("我的", "应用锁、外观、时区、底栏排序、功能开关、目标、模型管理、数据中心、成就墙、周小结、回收站与全量备份都在这里。")
                Spacer(Modifier.height(30.dp))
            }
        }
    }
}

@Composable
private fun guideItem(title: String, body: String) {
    Card(shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.padding(14.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text(body, style = MaterialTheme.typography.bodySmall)
        }
    }
}

// ==================== 数据中心 ====================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
// v54.4：onOpenBoard = 跳打卡面板（参数 = 聚焦的待办 id，null 只开面板）。
// 数据中心这两块卡保留摘要，但"看全部/补交/改记录"统一去面板——不再各自长出全量视图。
fun DataCenterScreen(
    onDone: () -> Unit,
    deferBack: Boolean = false,
    onOpenBoard: (Long?) -> Unit = {}
) {
    val version by Graph.dataVersion.flow.collectAsState()
    var data by remember { mutableStateOf(mapOf<String, String>()) }
    var xp by remember { mutableStateOf(0L) }
    var daily by remember { mutableStateOf(mapOf<Long, Int>()) }
    var gridStart by remember { mutableStateOf(AppTime.today()) }
    var supervision by remember { mutableStateOf<List<SupervisionOverview>>(emptyList()) }
    // v23：打卡数据趋势
    var checkinSeries by remember { mutableStateOf<List<CheckinSeries>>(emptyList()) }

    LaunchedEffect(version) {
        supervision = Graph.plan.supervisionOverview()
        val today = AppTime.today()
        val monday = today.minusDays((today.dayOfWeek.value - 1).toLong())
        val monthStart = today.withDayOfMonth(1)
        val from = monday.minusWeeks(27)
        gridStart = from
        val sessions = Graph.focus.sessionsBetween(from, AppTime.today()).filter { it.completed }
        daily = sessions.groupBy { it.startDay }
            .mapValues { (_, v) -> v.sumOf { (it.end - it.start) / 60000 }.toInt() }
        val weekSessions = sessions.filter { it.startDay >= AppTime.dayKey(monday) }
        val monthSessions = sessions.filter { it.startDay >= AppTime.dayKey(monthStart) }
        val focus7 = sessions.filter { it.startDay > AppTime.dayKey(today.minusDays(7)) }
            .sumOf { (it.end - it.start) / 60000 }.toInt()
        val done7 = Graph.plan.doneLogs(today.minusDays(6), AppTime.today()).size
        xp = XpCenter.total()
        checkinSeries = Graph.plan.checkinSeries()
        val sleep7 = runCatching { Graph.sleep.last(7) }.getOrDefault(emptyList())
        val sleepAvg = if (sleep7.isNotEmpty()) sleep7.sumOf { it.durationMin } / sleep7.size else 0
        data = mapOf(
            "本周专注" to "${formatMinutes(weekSessions.sumOf { (it.end - it.start) / 60000 }.toInt())} · ${weekSessions.size} 次",
            "本月专注" to "${formatMinutes(monthSessions.sumOf { (it.end - it.start) / 60000 }.toInt())} · ${monthSessions.size} 次",
            "近7天完成" to "$done7 条",
            "近7天专注" to formatMinutes(focus7),
            "近7天睡眠" to if (sleep7.isNotEmpty()) "${sleepAvg / 60}小时${sleepAvg % 60}分 · ${sleep7.size}天" else "未采集",
            "睡眠记录" to (runCatching { Graph.sleep.count() }.getOrDefault(0)).toString(),
            "日程总数" to Graph.plan.scheduleCount().toString(),
            "待办总数" to Graph.plan.todoCount().toString(),
            "待办完成" to Graph.plan.todoDoneCount().toString(),
            "累计专注" to formatMinutes(Graph.focus.totalMinutes()),
            "笔记总数" to Graph.notes.noteCount().toString(),
            "提纯记录" to Graph.info.digestCount().toString(),
            "已入库信息" to Graph.info.importedCount().toString(),
            "倒数日" to Graph.plan.countdowns().size.toString()
        )
    }

    PredictiveBackOverlay(onBack = onDone, deferBack = deferBack) {
        Scaffold(
            containerColor = if (deferBack) androidx.compose.ui.graphics.Color.Transparent else androidx.compose.material3.MaterialTheme.colorScheme.background,topBar = { if (deferBack) BoardSubHeader("数据中心", onDone) else TopAppBar(navigationIcon = { HapticBackButton(onDone) }, title = { Text("数据中心", fontWeight = FontWeight.SemiBold) }) }) { pad ->
            val dataScroll = rememberScrollState()
            Column(
                Modifier.fillMaxSize().padding(pad)
                    .verticalScroll(dataScroll).reachedEndHaptic(dataScroll).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                LevelCard(xp)
                if (supervision.isNotEmpty()) {
                    Card(
                        shape = RoundedCornerShape(18.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
                    ) {
                        Column(Modifier.fillMaxWidth().padding(14.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("打卡监督追踪", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                                Spacer(Modifier.weight(1f))
                                Text(
                                    "面板 ›",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.clickable { onOpenBoard(null) }
                                )
                            }
                            Spacer(Modifier.height(8.dp))
                            supervision.forEach { o ->
                                Column(
                                    Modifier.fillMaxWidth().padding(vertical = 6.dp)
                                        .clickable { onOpenBoard(o.todoId) }
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            o.title, style = MaterialTheme.typography.bodyMedium,
                                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f)
                                        )
                                        Text(
                                            if (o.checkedToday) "今日已打卡" else "今日未打卡",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = if (o.checkedToday) MaterialTheme.colorScheme.primary
                                            else MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    Spacer(Modifier.height(4.dp))
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Box(
                                            Modifier.weight(1f).height(6.dp)
                                                .clip(RoundedCornerShape(3.dp))
                                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                        ) {
                                            Box(
                                                Modifier.fillMaxWidth(o.rate / 100f).height(6.dp)
                                                    .clip(RoundedCornerShape(3.dp))
                                                    .background(MaterialTheme.colorScheme.primary)
                                            )
                                        }
                                        Spacer(Modifier.width(8.dp))
                                        Text(
                                            "${o.rate}%",
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                    }
                                    Text(
                                        "连续 ${o.streak} 天 · 累计 ${o.totalChecked}/${o.expectedDays} 天",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
                Card(
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
                ) {
                    Column(Modifier.fillMaxWidth().padding(14.dp)) {
                        Text("近 28 周专注热力图", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(10.dp))
                        HeatMap28(daily, gridStart)
                    }
                }
                // v23：打卡数据趋势（折线 + 打卡密度热力）。用户挑的组合：既要数值走势，也一眼看哪天漏了
                if (checkinSeries.isNotEmpty()) {
                    Card(
                        shape = RoundedCornerShape(18.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
                    ) {
                        Column(Modifier.fillMaxWidth().padding(14.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    "打卡数据趋势",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Spacer(Modifier.weight(1f))
                                Text(
                                    "${checkinSeries.size} 项 · 面板 ›",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.clickable { onOpenBoard(null) }
                                )
                            }
                            Spacer(Modifier.height(8.dp))
                            checkinSeries.forEach { s ->
                                CheckinTrendBlock(
                                    title = s.title,
                                    label = s.label,
                                    kind = s.kind,
                                    unit = s.unit,
                                    points = s.points
                                )
                                Spacer(Modifier.height(12.dp))
                            }
                        }
                    }
                }
                data.entries.chunked(2).forEach { pair ->
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        pair.forEach { (k, v) ->
                            Card(
                                shape = RoundedCornerShape(18.dp),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                                modifier = Modifier.weight(1f)
                            ) {
                                Column(Modifier.padding(14.dp)) {
                                    Text(k, style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text(v, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                        if (pair.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

/** XP 等级卡 */
@Composable
private fun LevelCard(xp: Long) {
    val level = XpCenter.levelOf(xp)
    val (cur, need) = XpCenter.progressOf(xp)
    val frac = if (need <= 0) 1f else (cur.toFloat() / need).coerceIn(0f, 1f)
    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
    ) {
        Column(Modifier.fillMaxWidth().padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Lv.$level", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer)
                Spacer(Modifier.width(10.dp))
                Text(
                    "$xp XP",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.weight(1f)
                )
            }
            Spacer(Modifier.height(8.dp))
            Box(
                Modifier.fillMaxWidth().height(8.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.15f))
            ) {
                Box(
                    Modifier.fillMaxWidth(frac).height(8.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(MaterialTheme.colorScheme.primary)
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "距下一级还差 ${need - cur} XP · 完成日程 / 待办 / 专注 / 提纯可得 XP",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
        }
    }
}

/** 28 周 × 7 天专注热力图（列 = 周，行 = 周一…周日） */
@Composable
private fun HeatMap28(daily: Map<Long, Int>, startMonday: java.time.LocalDate) {
    val today = AppTime.today()
    val labels = listOf("一", "二", "三", "四", "五", "六", "日")
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        labels.forEachIndexed { row, label ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(3.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    label, style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(14.dp)
                )
                repeat(28) { col ->
                    val d = startMonday.plusWeeks(col.toLong()).plusDays(row.toLong())
                    val future = d.isAfter(today)
                    val m = daily[AppTime.dayKey(d)] ?: 0
                    val intensity = when {
                        m == 0 -> 0f
                        m < 30 -> 0.35f
                        m < 60 -> 0.6f
                        m < 120 -> 0.8f
                        else -> 1f
                    }
                    val color = when {
                        future -> androidx.compose.ui.graphics.Color.Transparent
                        intensity == 0f -> MaterialTheme.colorScheme.surfaceVariant
                        else -> MaterialTheme.colorScheme.primary.copy(alpha = intensity)
                    }
                    Box(
                        Modifier
                            .weight(1f)
                            .height(10.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(color)
                    )
                }
            }
        }
    }
}

// ==================== v23 打卡数据趋势 ====================

/**
 * 一个待办的打卡趋势块：折线 + 打卡密度小格子。
 *
 * 手绘而不是引图表库：这个项目零图表依赖（连热力图都是自己画的），加一个库要动
 * 依赖树和混淆规则；而且这里只需要"一条线 + 几个点"，Canvas 十几行就够。
 *
 * time 型纵轴是当天分钟数（0..1440），要画成 06:00 / 12:00 / 18:00 才认得出；
 * 数值/计数型直接用 min..max，纵轴两端各留 10% 余量，线才不贴边。
 */
@Composable
// v54.4：改 internal——打卡面板详情页复用同一块折线（趋势视觉只此一份，防两处漂移）
internal fun CheckinTrendBlock(
    title: String,
    label: String,
    kind: String,
    unit: String,
    points: List<Pair<Long, Double>>
) {
    val zone = AppTime.zone
    val isTime = kind == io.github.m0use001.planability.data.CheckinKind.TIME
    // v54.5：数值读数带单位（计数型「次」、数值型用待办配的单位），不再给裸数字
    val unitText = when {
        isTime -> ""
        kind == io.github.m0use001.planability.data.CheckinKind.COUNT -> " 次"
        unit.isNotBlank() -> " $unit"
        else -> ""
    }
    val primary = MaterialTheme.colorScheme.primary
    val tertiary = MaterialTheme.colorScheme.tertiary
    val grid = MaterialTheme.colorScheme.outlineVariant
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant

    // x 轴按真实日距，而不是按等距的序号 —— 隔三天的两个点不该挨在一起
    val first = points.first().first
    val last = points.last().first
    val span = (last - first).coerceAtLeast(1L).toFloat()
    val vs = points.map { it.second }
    val rawMin = vs.min()
    val rawMax = vs.max()
    // 全天同一时刻（rawMax==rawMin）会除零：给一个人工窗口，看起来像一条平线
    val flat = rawMax - rawMin < 1e-6
    val lo = if (flat) rawMin - 1.0 else rawMin - (rawMax - rawMin) * 0.1
    val hi = if (flat) rawMax + 1.0 else rawMax + (rawMax - rawMin) * 0.1

    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            if (label.isNotBlank() && label != title) {
                Text(
                    " · $label",
                    style = MaterialTheme.typography.labelSmall,
                    color = labelColor
                )
            }
            Spacer(Modifier.weight(1f))
            val latest = points.last().second
            Text(
                if (isTime) {
                    val m = latest.toInt().coerceIn(0, 1439)
                    "%02d:%02d".format(m / 60, m % 60)
                } else {
                    io.github.m0use001.planability.data.CheckinFormat.trimNum(latest) + unitText
                },
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                color = primary
            )
        }
        Spacer(Modifier.height(6.dp))
        // ---- 折线 ----
        androidx.compose.foundation.Canvas(Modifier.fillMaxWidth().height(96.dp)) {
            if (points.size < 2) return@Canvas
            val w = size.width
            val h = size.height
            val padV = 6f
            fun yOf(v: Double): Float =
                padV + (1f - ((v - lo) / (hi - lo)).toFloat()).coerceIn(0f, 1f) * (h - padV * 2)
            fun xOf(dayKey: Long): Float = (dayKey - first).toFloat() / span * w
            // 三条参考线（0% / 50% / 100%）
            listOf(0.25f, 0.5f, 0.75f).forEach { f ->
                drawLine(
                    grid, androidx.compose.ui.geometry.Offset(0f, padV + f * (h - padV * 2)),
                    androidx.compose.ui.geometry.Offset(w, padV + f * (h - padV * 2)),
                    strokeWidth = 1f
                )
            }
            val xy = points.map { androidx.compose.ui.geometry.Offset(xOf(it.first), yOf(it.second)) }
            // 逐段画而不是 drawPath：点少（几十个），逐段能顺手在线下打一层浅色填充
            for (i in 0 until xy.size - 1) {
                drawLine(primary, xy[i], xy[i + 1], strokeWidth = 3f)
            }
            xy.forEachIndexed { i, p ->
                // 单点（只有两条记录但跨度极大）也要能看见
                drawCircle(if (i == xy.size - 1) tertiary else primary, radius = 3.5f, center = p)
            }
        }
        // ---- 纵轴读数 + 记录数 + x 轴起止日期（v54.5 用户「折线图令人看不懂」）----
        // 原来挤成两行、读数是裸数字（24.3 没有单位/没有「最低最高」的含义），
        // 现在一行讲清「最低/最高多少、共几个点」，第二行给时间跨度。
        val loText = if (isTime) "%02d:00".format((lo.toInt().coerceIn(0, 1439)) / 60)
        else io.github.m0use001.planability.data.CheckinFormat.trimNum(lo) + unitText
        val hiText = if (isTime) "%02d:00".format((hi.toInt().coerceIn(0, 1439)) / 60)
        else io.github.m0use001.planability.data.CheckinFormat.trimNum(hi) + unitText
        Spacer(Modifier.height(4.dp))
        // 时间型是「最早/最晚」，数值型才是「最低/最高」——措辞跟着语义走
        val (loLabel, hiLabel) = if (isTime) "最早" to "最晚" else "最低" to "最高"
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("$loLabel $loText", style = MaterialTheme.typography.labelSmall, color = labelColor)
            Spacer(Modifier.weight(1f))
            Text(
                "$hiLabel $hiText · ${points.size} 次",
                style = MaterialTheme.typography.labelSmall,
                color = labelColor
            )
        }
        Spacer(Modifier.height(2.dp))
        Text(
            java.time.Instant.ofEpochMilli(first).atZone(zone).toLocalDate().formatMonthDay() +
                " – " +
                java.time.Instant.ofEpochMilli(last).atZone(zone).toLocalDate().formatMonthDay(),
            style = MaterialTheme.typography.labelSmall,
            color = labelColor
        )
    }
}

// ==================== 回收站 ====================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrashScreen(onDone: () -> Unit, deferBack: Boolean = false) {
    val scope = rememberCoroutineScope()
    val haptics = LocalHaptics.current
    var tab by remember { mutableIntStateOf(0) }
    var schedules by remember { mutableStateOf<List<Schedule>>(emptyList()) }
    var todos by remember { mutableStateOf<List<Todo>>(emptyList()) }
    var notes by remember { mutableStateOf<List<NoteEntity>>(emptyList()) }
    var confirmEmpty by remember { mutableStateOf(false) }
    val version by Graph.dataVersion.flow.collectAsState()

    LaunchedEffect(version, tab) {
        schedules = Graph.plan.trashedSchedules()
        todos = Graph.plan.trashedTodos()
        notes = Graph.notes.trashedNotes()
    }

    PredictiveBackOverlay(onBack = onDone, deferBack = deferBack) {
        Scaffold(
            containerColor = if (deferBack) androidx.compose.ui.graphics.Color.Transparent else androidx.compose.material3.MaterialTheme.colorScheme.background,
            topBar = {
                if (deferBack) BoardSubHeader("回收站", onDone, actions = {
                    TextButton(onClick = {
                        // 清空回收站 = 不可逆，用 HIG 的重冲击（破坏性确认那一档）
                        haptics.perform(io.github.m0use001.planability.core.HapticEngine.Kind.ImpactHeavy)
                        confirmEmpty = true
                    }) { Text("清空", color = MaterialTheme.colorScheme.error) }
                }) else TopAppBar(navigationIcon = { HapticBackButton(onDone) },
                    title = { Text("回收站", fontWeight = FontWeight.SemiBold) },
                    actions = {
                        TextButton(onClick = {
                            // 清空回收站 = 不可逆，用 HIG 的重冲击（破坏性确认那一档）
                            haptics.perform(io.github.m0use001.planability.core.HapticEngine.Kind.ImpactHeavy)
                            confirmEmpty = true
                        }) { Text("清空", color = MaterialTheme.colorScheme.error) }
                    })
            }
        ) { pad ->
            Column(Modifier.fillMaxSize().padding(pad)) {
                // 指示条换 M3E 弹簧滑动（MotionTabIndicator）：默认是定长 tween 硬曲线，不在弹簧体系
                TabRow(selectedTabIndex = tab, indicator = { pos -> MotionTabIndicator(pos, tab) }) {
                    listOf("日程", "待办", "笔记").forEachIndexed { i, label ->
                        // 切二级 tab = 选择（HIG selection → 最轻的 Select），与 chip/开关同一档
                        Tab(selected = tab == i, onClick = { tab = i; haptics.perform(HapticEngine.Kind.Select) }, text = { Text(label) })
                    }
                }
                val binState = androidx.compose.foundation.lazy.rememberLazyListState()
                // key(tab)：日程/待办/笔记三套回收站换一棵列表，避免旧行 animateItem 退场淡化
                // 与新 tab 的行同屏叠一瞬（用户 2026-10-25「横切界面切换时重叠」）。
                androidx.compose.runtime.key(tab) {
                LazyColumn(
                    Modifier.fillMaxSize().padding(horizontal = 16.dp).reachedEndHaptic(binState),
                    state = binState
                ) {
                    val items: List<Pair<Long, String>> = when (tab) {
                        0 -> schedules.map { it.id to it.title }
                        1 -> todos.map { it.id to it.title }
                        else -> notes.map { it.id to it.title }
                    }
                    if (items.isEmpty()) item { EmptyHint("回收站是空的") }
                    items(items, key = { "${tab}-${it.first}" }) { (id, title) ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.animateItem().fillMaxWidth().padding(vertical = 6.dp)
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            TextButton(onClick = {
                                // 恢复是可撤销的正面动作，Success（不是删除那档 ImpactHeavy）
                                haptics.perform(io.github.m0use001.planability.core.HapticEngine.Kind.Success)
                                scope.launch {
                                    when (tab) {
                                        0 -> Graph.plan.restoreSchedule(id)
                                        1 -> Graph.plan.restoreTodo(id)
                                        else -> Graph.notes.restoreNote(id)
                                    }
                                }
                            }) { Text("恢复") }
                            IconButton(onClick = {
                                // 彻底删除不可逆 = 与 ConfirmDialog 的破坏性确认同一档（ImpactHeavy）：
                                // 少了确认弹窗，这一下重手感就是唯一在说"这事没法撤销"
                                haptics.perform(HapticEngine.Kind.ImpactHeavy)
                                scope.launch {
                                    when (tab) {
                                        0 -> Graph.plan.purgeSchedule(id)
                                        1 -> Graph.plan.purgeTodo(id)
                                        else -> Graph.notes.purgeNote(id)
                                    }
                                }
                            }) { Icon(Icons.Rounded.Delete, "彻底删除", tint = MaterialTheme.colorScheme.error) }
                        }
                    }
                    item { Spacer(Modifier.height(40.dp)) }
                }
                }
            }
        }
    }

    if (confirmEmpty) {
        ConfirmDialog(
            title = "清空回收站",
            text = "将彻底删除回收站中所有项目，不可恢复。",
            onConfirm = {
                scope.launch {
                    Graph.plan.emptyScheduleTrash()
                    Graph.plan.emptyTodoTrash()
                    Graph.notes.emptyTrash()
                }
            },
            onDismiss = { confirmEmpty = false },
            confirmText = "清空"
        )
    }
}
