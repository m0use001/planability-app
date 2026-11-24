package io.github.m0use001.planability.ui.focus

import android.content.Intent
import android.provider.Settings
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.PictureInPictureAlt
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import io.github.m0use001.planability.ui.common.motionEnter
import io.github.m0use001.planability.ui.common.SelectDropdownChip
import io.github.m0use001.planability.ui.common.pressScale
import io.github.m0use001.planability.ui.common.wobbleOn
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.m0use001.planability.app.PredictiveBackOverlay
import io.github.m0use001.planability.app.Router
import io.github.m0use001.planability.core.AppTime
import io.github.m0use001.planability.core.HapticEngine
import io.github.m0use001.planability.core.hScrollZone
import io.github.m0use001.planability.core.LocalHaptics
import io.github.m0use001.planability.core.formatMonthDay
import io.github.m0use001.planability.core.toLocalDate
import io.github.m0use001.planability.data.Graph
import io.github.m0use001.planability.focus.FocusTimerService
import io.github.m0use001.planability.focus.FocusTimerState
import io.github.m0use001.planability.focus.FloatBall
import io.github.m0use001.planability.ui.common.BoardLayout
import io.github.m0use001.planability.ui.common.DatePickerModal
import io.github.m0use001.planability.ui.common.EmptyHint
import io.github.m0use001.planability.ui.common.Motion
import io.github.m0use001.planability.ui.common.adaptiveColumn
import io.github.m0use001.planability.ui.common.filterChipColors
import io.github.m0use001.planability.ui.common.formatMinutes
import java.time.LocalDate

@Composable
fun FocusScreen() {
    val haptics = LocalHaptics.current
    val context = LocalContext.current
    val phase by FocusTimerState.phase.collectAsState()
    val remain by FocusTimerState.remainSec.collectAsState()
    // 总时长不再直接参与环（环的语义判定收在 FocusTimerState.ringFraction()）
    val stage by FocusTimerState.stage.collectAsState()
    val round by FocusTimerState.round.collectAsState()
    var showStats by remember { mutableStateOf(false) }
    var showFloatDialog by remember { mutableStateOf(false) }
    var showPomoDialog by remember { mutableStateOf(false) }
    /** 自定义专注时长（1–240 分钟，2026-10-24 用户要求） */
    var showCustomDialog by remember { mutableStateOf(false) }
    /** 放弃专注前的二次确认：计时是沉没成本，误触直接清零太亏 */
    var confirmGiveUp by remember { mutableStateOf(false) }
    val settings by Graph.settings.snapshot.collectAsState()
    val goalMin = settings.focusGoalMin
    // 初始值跟库里的 pomoWorkMin（原来写死 25：改过时长后重进页面显示 25:00、
    // 实际开始却用库值——显示与行为不一致）。键带 pomoWorkMin，设置变时同步。
    var selectedMin by remember(settings.pomoWorkMin) { mutableIntStateOf(settings.pomoWorkMin) }

    // 快捷方式/瓷贴：直达开始专注
    // 键用标志本身：各页常驻组合（beyondViewportPageCount = tabs.size），Unit 版早跑完、翻转不重跑
    LaunchedEffect(Router.pendingFocusStart) {
        if (Router.pendingFocusStart) {
            Router.pendingFocusStart = false
            if (phase == FocusTimerState.Phase.Idle || phase == FocusTimerState.Phase.Finished) {
                FocusTimerService.start(context, selectedMin)
            }
        }
    }

    // v58 平板 bug 根修：这里原来漏了 contentAlignment = TopCenter —— adaptiveColumn()
    // 把列限到 840dp 后，Box 默认 TopStart 对齐，整列被钉在屏幕**左侧**，
    // 圆环偏左、右边 400+dp 全空（正是用户说的「大而空」的另一种形态）。
    // 与 PlanScreen / ProfileScreens / InfoCenter 同一写法。
    // v76：宽窄判定提到 Box 外——末尾窄屏覆盖层兜底要门控（宽屏时专注统计已由 BoardDrill 钻入承载）。
    var wideTwoPane by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        // 2026-11-20 v65：宽窄判定要在 BoxWithConstraints **外面**也能读到——
        // 浮起标题栏（窄屏才画）挂在这一层。判定本身仍只用内容区实测宽
        // （BoxWithConstraints 里的 maxWidth），口径不变。
        BoxWithConstraints(Modifier.fillMaxSize()) {
            // v62 平板「大而空」根修（2026-11-19 用户 1600×1000 截图）：
            // ① 分档一律用**内容区实测宽高**（maxWidth/maxHeight）——宽屏左侧有 80dp Rail，
            //    `rememberWindowSizeClass()` 读的 screenWidthDp 会把窗宽多算 80dp，临界值提前一档；
            // ② 原来整列被 adaptiveColumn 的 840dp 上限框住，1600dp 窗口里两侧各空 340dp；
            //    2026-11-20 v65 进一步统一成**两块板**（与计划页/首页同一套 SplitBoards 材质）。
            // ③ 每列用 `verticalScroll + heightIn(min = 面板高)`——滚动容器按无限高测量，
            //    直接放 `verticalArrangement = Center` 是空操作（v60 的「垂直居中」其实从未生效），
            //    把内容最小高度钉成视口高之后 Center / SpaceBetween 才真的会分配空白。
            val usableWidthDp = maxWidth.value.toInt()
            val topSpacerDp =
                WindowInsets.statusBars.asPaddingValues().calculateTopPadding() +
                    io.github.m0use001.planability.ui.common.M3ETitleBarPinnedH + 12.dp
            val paneHeightDp = (maxHeight - topSpacerDp).coerceAtLeast(320.dp)
            // 2026-11-20 v65：宽窄判定只此一处（focusIsTwoPane）。
            // 原来这里是三档（900 两栏 / 1200 三栏），现在统一成两块板。
            val wide = focusIsTwoPane(usableWidthDp)
            wideTwoPane = wide
            // 环按**左板实宽**算（不是整页宽）：两板形态下左板 = 内容宽 − 416，
            // 900dp 内容宽时左板只有 484dp，按整页 900dp 算出来的 522dp 环比还宽。
            val leftPaneWidthDp = focusLeftPaneWidthDp(usableWidthDp)
            val ringSize = focusRingSizeDp(leftPaneWidthDp, paneHeightDp.value.toInt(), wide).dp
            // 沿用原名：这个变量就是「一栏可用的高度」，旧注释与守卫测试都按这个名字找它。
            val widePaneHeight = paneHeightDp

            // v59：各区块收成 lambda —— 宽屏要把「环+时长+控制」和「今日专注」分成左右两栏，
            // 窄屏仍按原顺序竖向排（一份代码两种摆位，避免复制粘贴出第二套逻辑）。
            val focusRing: @Composable () -> Unit = {
            // ---------- 计时环 ----------
            // v57 批 2（B 类）：**一个番茄跑完**（phase 进 Finished）整环抖一下——这是全页
            // 最值得被看见的状态翻转。不挂 phase 整体（暂停/继续太频繁，每次点都抖就成噪声），
            // 只挂「是否处于完成态」这个布尔：完成抖一次，点下一轮时归 false 也抖一次
            // （= 新一轮开始的手感，可接受且符合"状态翻转"语义）。
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(ringSize)
                    .wobbleOn(trigger = phase == FocusTimerState.Phase.Finished, scaleTo = 1.03f)
            ) {
                // 圆环进度走 standard 弹簧：放弃/完成后状态归位时圆弧是**扫着收回去**的，
                // 不再瞬跳（审查差距 12）；每秒 tick 的 1° 增量也顺带被抹平成连续扫动。
                //
                // **2026-11-03 用户「正计时还显示一个环」**：正计时（Work 阶段）没有总时长——
                // remain 是**已用秒数**、total 是 25 分钟占位，remain/total 会从 1 瞬间掉到 ~0
                // 再花 25 分钟慢慢爬满，环在语义上是错的。判定收在 FocusTimerState.ringFraction()
                // （null = 不画弧），这里只负责画；倒计时照常按 remain/total 走。
                val ringFrac = FocusTimerState.ringFraction()
                val showArc = ringFrac != null
                val targetProgress = ringFrac ?: 0f
                val progress by animateFloatAsState(
                    targetValue = targetProgress,
                    animationSpec = Motion.standard(),
                    label = "focus-ring"
                )
                val baseColor = MaterialTheme.colorScheme.surfaceVariant
                val ringColor = when (phase) {
                    FocusTimerState.Phase.Idle -> baseColor
                    FocusTimerState.Phase.Paused -> MaterialTheme.colorScheme.tertiary
                    FocusTimerState.Phase.Finished -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.primary
                }
                Canvas(Modifier.fillMaxSize()) {
                    val stroke = 18.dp.toPx()
                    val inset = stroke / 2 + 4.dp.toPx()
                    drawCircle(
                        color = baseColor,
                        radius = size.minDimension / 2 - inset,
                        center = Offset(size.width / 2, size.height / 2),
                        style = Stroke(stroke)
                    )
                    // 正计时/Idle：只有轨道，不画进度弧（见上方 showArc 的语义说明）
                    if (showArc && progress > 0f) {
                        drawArc(
                            color = ringColor,
                            startAngle = -90f,
                            sweepAngle = 360f * progress,
                            useCenter = false,
                            topLeft = Offset(inset, inset),
                            size = androidx.compose.ui.geometry.Size(
                                size.minDimension - inset * 2,
                                size.minDimension - inset * 2
                            ),
                            style = Stroke(stroke, cap = StrokeCap.Round)
                        )
                    }
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        when {
                            phase == FocusTimerState.Phase.Idle ->
                                if (settings.focusCountUp) "0:00" else "$selectedMin:00"
                            else -> "${remain / 60}:${"%02d".format(remain % 60)}"
                        },
                        // v62：环放大到 480dp 后 45sp 的字在环里只占一条缝——环 ≥400dp 时升一档
                        style = if (ringSize >= 400.dp) MaterialTheme.typography.displayLarge
                        else MaterialTheme.typography.displayMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        when {
                            phase == FocusTimerState.Phase.Idle ->
                                if (settings.focusCountUp) "正计时模式 · 点开始" else "选择时长开始专注"
                            phase == FocusTimerState.Phase.Running -> when (stage) {
                                FocusTimerState.Stage.Work ->
                                    if (FocusTimerState.countUp) "正计时中…"
                                    else "专注中… · 第 $round / ${settings.pomoRounds} 轮"
                                FocusTimerState.Stage.ShortBreak -> "短休中 ☕"
                                FocusTimerState.Stage.LongBreak -> "长休中 🌿"
                            }
                            phase == FocusTimerState.Phase.Paused -> "已暂停 · " + when (stage) {
                                FocusTimerState.Stage.Work -> "专注"
                                FocusTimerState.Stage.ShortBreak -> "短休"
                                else -> "长休"
                            }
                            else -> "完成！休息一下 🍅"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            }

            // v12：悬浮窗显示计时（退出应用仍可见；需悬浮窗权限）
            val focusOverlaySwitch: @Composable () -> Unit = {
            if (phase == FocusTimerState.Phase.Running || phase == FocusTimerState.Phase.Paused) {
                val canOverlay = remember {
                    android.provider.Settings.canDrawOverlays(context)
                }
                var overlayOn by remember {
                    mutableStateOf(
                        context.getSharedPreferences("planability", android.content.Context.MODE_PRIVATE)
                            .getBoolean("focus.overlay", true)
                    )
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        Icons.Rounded.PictureInPictureAlt, null,
                        Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "悬浮窗显示计时",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f)
                    )
                    if (!canOverlay) {
                        TextButton(onClick = {
                            runCatching {
                                context.startActivity(
                                    android.content.Intent(
                                        android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                        android.net.Uri.parse("package:" + context.packageName)
                                    )
                                )
                            }
                        }) { Text("去授权") }
                    } else {
                        Switch(
                            checked = overlayOn,
                            onCheckedChange = { on ->
                                // 开/关都算"选择"：最轻的 Select（HIG selection → 见 core/Haptics.kt 映射表）
                                haptics.perform(HapticEngine.Kind.Select)
                                overlayOn = on
                                context.getSharedPreferences("planability", android.content.Context.MODE_PRIVATE)
                                    .edit().putBoolean("focus.overlay", on).apply()
                                io.github.m0use001.planability.focus.FocusTimerService.overlay(context, on)
                            }
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
            }

            // ---------- 时长选择 ----------
            val focusDurations: @Composable () -> Unit = {
            // 正计时模式没有"时长"这回事（开始按钮固定按 25 分占位起算、结束后按实际分钟记录），
            // 摆一排选了不生效的 chip 是误导（2026-11-03 用户「正计时还有很多 bug」）——
            // 该模式下换成一行说明，时长选择留给倒计时模式。
            if (settings.focusCountUp) {
                Text(
                    "正计时模式：不设时长，结束后按实际专注分钟记录",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                // 2026-11-22 用户「可滑动项不要碰到框/圆角矩形边缘」：时长预设横滑胶囊行
                // 挂在板内会滑进圆角——改单个下拉胶囊（选中值即摘要）。
                val presets = listOf(15, 25, 45, 60)
                val isCustom = settings.pomoWorkMin !in presets
                Row(
                    // v62：宽屏左栏比手机宽得多，chip 贴左会与居中的环错轴——宽屏居中，手机不动
                    horizontalArrangement =
                        Arrangement.spacedBy(8.dp, if (wide) Alignment.CenterHorizontally else Alignment.Start)
                ) {
                    SelectDropdownChip(
                        summary = if (isCustom) "自定义 ${settings.pomoWorkMin} 分" else "$selectedMin 分钟",
                        options = presets.map { "$it 分钟" } + "自定义…",
                        selectedIndex = presets.indexOf(selectedMin),
                        enabled = phase == FocusTimerState.Phase.Idle || phase == FocusTimerState.Phase.Finished,
                        onSelect = { idx ->
                            haptics.perform(HapticEngine.Kind.Select)
                            if (idx < presets.size) {
                                selectedMin = presets[idx]
                                Graph.settings.update { it.copy(pomoWorkMin = presets[idx]) }
                            } else {
                                // 自定义时长（2026-10-24 用户要求）：选中态=库值不在预设里
                                showCustomDialog = true
                            }
                        }
                    )
                }
            }
            }

            // ---------- 控制按钮 ----------
            val focusControls: @Composable () -> Unit = {
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                when (phase) {
                    FocusTimerState.Phase.Idle, FocusTimerState.Phase.Finished -> {
                        Button(
                            onClick = {
                                haptics.perform(HapticEngine.Kind.ImpactLight)
                                selectedMin = settings.pomoWorkMin
                                FocusTimerService.start(context, settings.pomoWorkMin)
                            },
                            // v62：宽屏主按钮跟着环一起长（环径 ×0.55、高 56dp），
                            // 否则 480dp 的环下面挂一枚 150dp 的小按钮，像没做完
                            modifier = Modifier
                                .width(if (wide) (ringSize * 0.55f).coerceAtLeast(150.dp) else 150.dp)
                                .height(if (wide) 56.dp else 52.dp)
                                .pressScale()
                        ) {
                            Icon(Icons.Rounded.PlayArrow, null)
                            Text(if (settings.focusCountUp) " 开始正计时" else " 开始专注")
                        }
                    }
                    else -> {
                        if (phase == FocusTimerState.Phase.Running) {
                            OutlinedButton(
                                onClick = { haptics.perform(HapticEngine.Kind.Tap); FocusTimerService.pause(context) },
                                modifier = Modifier.width(120.dp).height(52.dp).pressScale()
                            ) {
                                Icon(Icons.Rounded.Pause, null); Text(" 暂停")
                            }
                        } else {
                            Button(
                                onClick = { haptics.perform(HapticEngine.Kind.Tap); FocusTimerService.resume(context) },
                                modifier = Modifier.width(120.dp).height(52.dp).pressScale()
                            ) {
                                Icon(Icons.Rounded.PlayArrow, null); Text(" 继续")
                            }
                        }
                        OutlinedButton(
                            onClick = {
                                // 先问一句再放弃（审查差距 12）：这个动作会清空本轮计时
                                haptics.perform(HapticEngine.Kind.Tap)
                                confirmGiveUp = true
                            },
                            modifier = Modifier.width(120.dp).height(52.dp).pressScale()
                        ) {
                            Icon(Icons.Rounded.Stop, null); Text(" 放弃")
                        }
                    }
                }
            }
            }

            // ---------- 本轮节奏（v62 宽屏）：把「第 x / y 轮」图形化 ----------
            // 只在倒计时模式有意义（正计时不分轮次），所以由调用点在 !focusCountUp 时才排出来。
            // 不新增数据：就是原来的 round / pomoRounds 换成点阵，一眼看出「还剩几个番茄」。
            val focusRoundDots: @Composable () -> Unit = {
                val total = settings.pomoRounds.coerceIn(1, 8)
                val currentIdx = round.coerceIn(1, total) - 1
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    repeat(total) { i ->
                        val done = i < currentIdx ||
                            (i == currentIdx && phase == FocusTimerState.Phase.Finished)
                        Box(
                            Modifier
                                .size(if (i == currentIdx) 12.dp else 9.dp)
                                .clip(CircleShape)
                                .background(
                                    when {
                                        done -> MaterialTheme.colorScheme.primary
                                        i == currentIdx ->
                                            MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)
                                        else -> MaterialTheme.colorScheme.surfaceVariant
                                    }
                                )
                        )
                    }
                    Spacer(Modifier.width(4.dp))
                    Text(
                        "第 ${currentIdx + 1} / $total 轮",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // ---------- 单列（手机 / 700dp 级窄窗）：原顺序一字不动 ----------
            if (!wide) {
                Column(
                    Modifier
                        .fillMaxHeight()
                        .adaptiveColumn() // 非宽屏时天然 no-op，留着防「宽窗口但可用宽不足」的极端情形
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // 2026-10-25 用户「专注界面也补上标题栏」：与计划/笔记同款固定态圆角标题栏，
                    // 内容从圆角下钻过——顶部垫出状态栏+固定标题栏高，PageHeader 退场。
                    Spacer(Modifier.height(topSpacerDp))
                    focusRing()
                    Spacer(Modifier.height(20.dp))
                    focusOverlaySwitch()
                    focusDurations()
                    Spacer(Modifier.height(24.dp))
                    focusControls()
                    Spacer(Modifier.height(28.dp))
                    TodayFocusSummary(goalMin)
                    // v10：悬浮底栏避让
                    Spacer(
                        Modifier.height(
                            io.github.m0use001.planability.app.navAvoidPadding().calculateBottomPadding()
                        )
                    )
                }
            } else {
                // ---------- 宽屏：两块板（计时舞台 | 概览） ----------
                // 2026-11-20 v65：原来是三块**直角列**（计时 | 统计 | 待办），栏与栏之间
                // 只有 24dp 空隙、每栏内容直接铺在页面底色上，栏内又浮一串 18dp 圆角卡——
                // 两种材质 + 三种网格。现在统一成与计划页/首页同一套 SplitBoards：
                // 两块 24dp 圆角板 + 12dp 白缝，板头长在板面上。
                //
                // `heightIn(min = 面板高 - 24dp)` 仍然是**居中能不能生效**的关键：
                // 滚动容器按无限高测量子列，只写 arrangement 会被直接忽略（v60 的实际状态）。
                val paneMinHeight = (widePaneHeight - 24.dp).coerceAtLeast(0.dp)
                // v76（2026-11-22「二级+页面全量纳入主副框钻入」批次2）：宽屏两板根由
                // SplitBoards 换成 BoardDrill——专注统计从「居中模态卡」改为钻入主板
                // （上一页缩进副框、Rail 常驻、返回集体左移），与计划/我的同款钻入动画。
                // level=0 时几何与 SplitBoards 逐像素一致（DrillGeometryTest 钉）。
                // v77（全局层级栈）：BoardDrill → NavHost（专注统计 push 进栈 = 钻入主板、Rail 常驻）。
                io.github.m0use001.planability.ui.common.NavHost(
                    detailWidth = io.github.m0use001.planability.ui.common.BoardLayout.DetailWidth,
                    rootMain = {
                        val nav = io.github.m0use001.planability.app.LocalNav.current
                        // ---- 左板：板头 + 计时舞台（环 / 轮次 / 时长 / 控制）垂直居中 ----
                        Column(
                            Modifier
                                .fillMaxSize()
                                .verticalScroll(rememberScrollState())
                                .heightIn(min = paneMinHeight)
                        ) {
                            // 板头长在板面上（不再压一层浮起圆角栏）
                            io.github.m0use001.planability.ui.common.BoardHeader(
                                title = "专注",
                                actions = {
                                    IconButton(onClick = { showFloatDialog = true }) {
                                        Icon(
                                            Icons.Rounded.PlayArrow, contentDescription = "悬浮球",
                                            tint = if (settings.floatBallEnabled) MaterialTheme.colorScheme.primary
                                            else MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    IconButton(onClick = { showPomoDialog = true }) {
                                        Icon(Icons.Rounded.Tune, contentDescription = "番茄设置")
                                    }
                                    IconButton(onClick = {
                                        nav?.let { n ->
                                            n.push(io.github.m0use001.planability.app.NavPane("focus-stats") {
                                                FocusStatsScreen(onDone = { n.pop() }, deferBack = true)
                                            })
                                        }
                                    }) {
                                        Icon(Icons.Rounded.BarChart, contentDescription = "专注统计")
                                    }
                                }
                            )
                            // 内容列限宽 760dp 居中（与计划页左板同一尺度）：
                            // 左板是弹性的，不限宽的话环会孤零零漂在大板中央。
                            Column(
                                Modifier
                                    .fillMaxWidth()
                                    .widthIn(max = io.github.m0use001.planability.ui.focus.FOCUS_STAGE_MAX_WIDTH_DP.dp)
                                    .heightIn(min = paneMinHeight),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                focusRing()
                                if (!settings.focusCountUp) {
                                    Spacer(Modifier.height(16.dp))
                                    focusRoundDots()
                                }
                                Spacer(Modifier.height(24.dp))
                                focusDurations()
                                Spacer(Modifier.height(28.dp))
                                focusControls()
                            }
                        }
                    },
                    rootSecondary = {
                        // ---- 右板：概览（今日专注 / 近 7 天 / 本周概览 / 接下来要做 / 开关）----
                        // v69（2026-11-21 用户「还是太挤、标题没有对齐」）：与左板**完全同构**——
                        // 板头钉在列顶部，各节收进独立的居中内容列（heightIn(min=paneMinHeight)）。
                        // 原来板头和内容作为一个整体垂直居中，内容长度一变板头就上下漂，
                        // 两块板的板头不在同一水平线（用户截图实锤）。
                        // v70（2026-11-21 用户拍板）：概览板**不滚动**——去掉 verticalScroll
                        // （内容按视口一屏收纳，删掉「最近专注」后正好放得下），整组在
                        // heightIn(min=paneMinHeight) 的内容列里垂直居中。
                        Column(Modifier.fillMaxSize()) {
                            io.github.m0use001.planability.ui.common.BoardHeader(title = "概览")
                            Column(
                                Modifier
                                    .fillMaxSize()
                                    .padding(horizontal = BoardLayout.Padding)
                                    .heightIn(min = paneMinHeight),
                                // 内容短时整组垂直居中（与左板舞台同一条轴）
                                verticalArrangement = Arrangement.Center
                            ) {
                                // v72：节间距 24→18——两个节装了色调块（自带纵向内距），总量补偿
                                TodayFocusSummary(goalMin, inBoard = true)
                                Spacer(Modifier.height(18.dp))
                                FocusWeekBars()
                                Spacer(Modifier.height(18.dp))
                                FocusWeekStats()
                                Spacer(Modifier.height(18.dp))
                                FocusNextUpPane()
                                Spacer(Modifier.height(18.dp))
                                focusOverlaySwitch()
                            }
                        }
                    }
                )
            }
        }
        // 固定态标题栏压在内容上层（最后声明 = 最上层），动作区收编原 PageHeader 的三个入口。
        // 2026-11-20 v65：**窄屏才用**这根浮起栏。宽屏时它会横跨两板把右板顶部盖掉，
        // 而且「浮起圆角栏」压在「圆角板」上就是第二种材质——宽屏改用板面固定板头
        // （BoardHeader，见上面 SplitBoards 的 primary）。
        if (!wideTwoPane) {
            io.github.m0use001.planability.ui.common.M3ETitleBar(
                title = "专注",
                collapsedFraction = 1f,
                expandable = false,
                modifier = Modifier.align(Alignment.TopCenter),
                actions = {
                    IconButton(onClick = { showFloatDialog = true }) {
                        Icon(
                            Icons.Rounded.PlayArrow, contentDescription = "悬浮球",
                            tint = if (settings.floatBallEnabled) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(onClick = { showPomoDialog = true }) {
                        Icon(Icons.Rounded.Tune, contentDescription = "番茄设置")
                    }
                    IconButton(onClick = { showStats = true }) {
                        Icon(Icons.Rounded.BarChart, contentDescription = "专注统计")
                    }
                }
            )
        }
    }

    // v76：宽屏已由上面 BoardDrill 的 detail 承载专注统计；此处只兜窄屏/浮层路径，避免双渲染。
    if (showStats && !wideTwoPane) {
        FocusStatsScreen(onDone = { showStats = false })
    }

    // 放弃确认（M3 AlertDialog，低干扰）：确认才真正 stop；取消/点外面什么都不发生
    if (confirmGiveUp) {
        AlertDialog(
            modifier = Modifier.motionEnter(),
            onDismissRequest = { confirmGiveUp = false },
            title = { Text("放弃本次专注？") },
            text = {
                // 正计时不满 1 分钟不记流水（记录粒度是分钟）——这里说清楚，别让人以为记漏了
                val partial = FocusTimerState.countUp && FocusTimerState.elapsedSec in 1..59
                Text(
                    if (partial)
                        "已专注 ${FocusTimerState.elapsedSec} 秒，不足 1 分钟不会记入统计。本轮计时会清零。"
                    else "本轮计时会清零，不会记入专注统计。"
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    haptics.perform(HapticEngine.Kind.Error)
                    confirmGiveUp = false
                    FocusTimerService.stop(context)
                }) { Text("放弃") }
            },
            dismissButton = {
                TextButton(onClick = { confirmGiveUp = false }) { Text("继续专注") }
            }
        )
    }

    if (showCustomDialog) {
        CustomFocusDurationDialog(
            current = settings.pomoWorkMin,
            onDismiss = { showCustomDialog = false },
            onConfirm = { v ->
                Graph.settings.update { it.copy(pomoWorkMin = v) }
                selectedMin = v
                haptics.perform(HapticEngine.Kind.Success)
                showCustomDialog = false
            }
        )
    }

    if (showPomoDialog) {
        AlertDialog(
            modifier = Modifier.motionEnter(),
            onDismissRequest = { showPomoDialog = false },
            title = { Text("番茄设置") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    PomoChipRow("专注时长", listOf(15, 25, 45, 60), settings.pomoWorkMin) { v ->
                        Graph.settings.update { it.copy(pomoWorkMin = v) }
                    }
                    PomoChipRow("短休", listOf(5, 10, 15), settings.pomoShortMin) { v ->
                        Graph.settings.update { it.copy(pomoShortMin = v) }
                    }
                    PomoChipRow("长休", listOf(15, 20, 30), settings.pomoLongMin) { v ->
                        Graph.settings.update { it.copy(pomoLongMin = v) }
                    }
                    PomoChipRow("长休前轮数", listOf(2, 3, 4, 6), settings.pomoRounds) { v ->
                        Graph.settings.update { it.copy(pomoRounds = v) }
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("正计时模式", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "不设时长，手动结束记录实际分钟",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = settings.focusCountUp,
                            onCheckedChange = {
                                haptics.perform(HapticEngine.Kind.Select)
                                Graph.settings.update { it.copy(focusCountUp = !it.focusCountUp) }
                            }
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showPomoDialog = false }) { Text("完成") }
            }
        )
    }

    if (showFloatDialog) {
        val canDraw = FloatBall.canDraw(context)
        AlertDialog(
            modifier = Modifier.motionEnter(),
            onDismissRequest = { showFloatDialog = false },
            title = { Text("专注悬浮胶囊") },
            text = {
                Text(
                    if (canDraw) "专注进行中时胶囊悬浮显示剩余时间，可拖动（松手自动吸附到左右边缘），单击暂停/继续；空闲时不出现。"
                    else "需要「显示在其他应用上层」权限。请在系统设置中授权后返回。"
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val enable = !settings.floatBallEnabled
                    Graph.settings.update { it.copy(floatBallEnabled = enable) }
                    if (enable) {
                        if (canDraw) FloatBall.show(context)
                        else context.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION))
                    } else {
                        FloatBall.hide(context)
                    }
                    showFloatDialog = false
                }) {
                    Text(if (settings.floatBallEnabled) "关闭" else "开启")
                }
            },
            dismissButton = { TextButton(onClick = { showFloatDialog = false }) { Text("取消") } }
        )
    }
}

@Composable
private fun TodayFocusSummary(goalMin: Int, inBoard: Boolean = false) {
    val version by Graph.dataVersion.flow.collectAsState()
    var todayMin by remember { mutableStateOf(0) }
    var doneCount by remember { mutableStateOf(0) }
    LaunchedEffect(version) {
        val sessions = Graph.focus.sessionsBetween(AppTime.today(), AppTime.today())
        todayMin = sessions.filter { it.completed }.sumOf { (it.end - it.start) / 60000 }.toInt()
        doneCount = sessions.count { it.completed }
    }
    if (inBoard) {
        // 板内分节（2026-11-20 v65）：板里不再嵌浮起 Card。
        // v72（2026-11-21 用户「应该加入圆角矩形来区分」）：内容装进色调块（PaneContentBlock），
        // 标题仍走 FocusBoardSection（与右板其它节同构）。
        FocusBoardSection("今日专注") {
            io.github.m0use001.planability.ui.common.PaneContentBlock {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "完成 $doneCount 个番茄",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    formatMinutes(todayMin),
                    style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(8.dp))
                FocusGoalBar(goalMin, todayMin)
            }
        }
        return
    }
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier.fillMaxWidth()
    ) {        Column(Modifier.padding(16.dp)) {
            // 换行修复（2026-10-24 用户真机截图）：原来「完成 N 个番茄」与大字分钟同一行、
            // 靠 weight 空隙挤在右侧——3.5x 密度+字体放大时只剩「茄」字折到第二行。
            // 改成标题行内右侧（标题只有 4 个字、weight 让位，右侧永不折行），大字分钟独占一行。
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "今日专注",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    "完成 $doneCount 个番茄",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                formatMinutes(todayMin),
                style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(10.dp))
            FocusGoalBar(goalMin, todayMin)
        }
    }
}

/**
 * 今日专注的目标进度条（2026-11-20 v65 抽出）。
 *
 * 窄屏（浮起卡内）与宽屏（板内分节）共用同一份实现——两份画法迟早会漂移。
 */
@Composable
private fun FocusGoalBar(goalMin: Int, todayMin: Int) {
    val frac = (todayMin.toFloat() / goalMin.coerceAtLeast(1)).coerceIn(0f, 1f)
    Box(
        Modifier
            .fillMaxWidth()
            .height(8.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Box(
            Modifier
                .fillMaxWidth(frac)
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(MaterialTheme.colorScheme.primary)
        )
    }
    Text(
        "目标 $goalMin 分钟 · 已完成 ${(frac * 100).toInt()}%",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 6.dp)
    )
}

/**
 * 自定义专注时长（2026-10-24 用户要求）：预设 15/25/45/60 之外任选 1–240 分钟。
 * 只改 pomoWorkMin（开始按钮与番茄钟都从库值取），不动短休/长休——那些在「番茄设置」里。
 */
@Composable
private fun CustomFocusDurationDialog(
    current: Int,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit
) {
    var text by remember { mutableStateOf(current.toString()) }
    val parsed = text.trim().toIntOrNull()
    val ok = parsed != null && parsed in 1..240
    AlertDialog(
        modifier = Modifier.motionEnter(),
        onDismissRequest = onDismiss,
        title = { Text("自定义专注时长") },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = { t -> if (t.length <= 3 && t.all { it.isDigit() }) text = t },
                    label = { Text("分钟（1 – 240）") },
                    singleLine = true,
                    isError = text.isNotEmpty() && !ok,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Number,
                        imeAction = ImeAction.Done
                    )
                )
                Text(
                    "预设档之外的时长；确定后「开始专注」与番茄钟都用它。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }
        },
        confirmButton = {
            TextButton(enabled = ok, onClick = { onConfirm(parsed ?: return@TextButton) }) { Text("确定") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

// ==================== 专注统计 ====================

@Composable
fun FocusStatsScreen(onDone: () -> Unit, deferBack: Boolean = false) {
    val haptics = LocalHaptics.current
    val version by Graph.dataVersion.flow.collectAsState()
    var days by remember { mutableStateOf<List<Pair<LocalDate, Int>>>(emptyList()) }
    var totalMin by remember { mutableStateOf(0) }
    var count by remember { mutableStateOf(0) }
    // 范围：14 / 30 天；0 = 自定义起止
    var range by remember { mutableStateOf(14) }
    var customFrom by remember { mutableStateOf(AppTime.today().minusDays(29)) }
    var customTo by remember { mutableStateOf(AppTime.today()) }
    var showFromPicker by remember { mutableStateOf(false) }
    var showToPicker by remember { mutableStateOf(false) }

    val effFrom = if (range == 0) customFrom else AppTime.today().minusDays((range - 1).toLong())
    val effTo = if (range == 0) customTo else AppTime.today()

    LaunchedEffect(version, effFrom, effTo) {
        val sessions = Graph.focus.sessionsBetween(effFrom, effTo)
        val dayCount = java.time.temporal.ChronoUnit.DAYS.between(effFrom, effTo).toInt() + 1
        days = (0 until dayCount).map { i ->
            val d = effFrom.plusDays(i.toLong())
            d to sessions.filter { it.completed && it.startDay == AppTime.dayKey(d) }
                .sumOf { (it.end - it.start) / 60000 }.toInt()
        }
        totalMin = Graph.focus.totalMinutes()
        count = Graph.focus.completedCount()
    }

    // v76：deferBack=true 时作为 BoardDrill 钻入瓦片渲染（透明、不自装返回、返回归 BoardDrill）。
    PredictiveBackOverlay(onBack = onDone, deferBack = deferBack) {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(20.dp)
        ) {
            // v19.1：补返回按钮（此前只能靠手势返回，用户反馈缺返回入口）
            Row(verticalAlignment = Alignment.CenterVertically) {
                // 返回 = 中性轻触（Tap），见 core/Haptics.kt 的 HIG 映射表
                IconButton(onClick = { haptics.perform(HapticEngine.Kind.Tap); onDone() }) {
                    Icon(
                        Icons.AutoMirrored.Rounded.ArrowBack,
                        "返回",
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                }
                Spacer(Modifier.width(4.dp))
                Text("专注统计", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatCard("累计专注", formatMinutes(totalMin), Modifier.weight(1f))
                StatCard("完成番茄", "$count 个", Modifier.weight(1f))
            }
            Spacer(Modifier.height(20.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
            ) {
                // 时间范围 chip = 选择，和同屏的时长 chip 同一档（Select）——
                // 同一屏里"有的振有的不振"最伤手感，这里按同一规则补齐
                FilterChip(range == 14, { range = 14; haptics.perform(HapticEngine.Kind.Select) }, label = { Text("近14天") }, colors = filterChipColors())
                FilterChip(range == 30, { range = 30; haptics.perform(HapticEngine.Kind.Select) }, label = { Text("近30天") }, colors = filterChipColors())
                FilterChip(range == 0, { range = 0; haptics.perform(HapticEngine.Kind.Select) }, label = { Text("自定义") }, colors = filterChipColors())
            }
            if (range == 0) {
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { showFromPicker = true }, modifier = Modifier.weight(1f)) {
                        Text("起 ${customFrom.formatMonthDay()}")
                    }
                    OutlinedButton(onClick = { showToPicker = true }, modifier = Modifier.weight(1f)) {
                        Text("止 ${customTo.formatMonthDay()}")
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            Text("专注趋势（分钟）", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(12.dp))
            FocusTrendChart(days, effFrom, effTo)
            Spacer(Modifier.height(20.dp))
            Text("近 6 周热力图", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(10.dp))
            HeatMap()
            Spacer(Modifier.height(40.dp))
        }
    }

    if (showFromPicker) {
        DatePickerModal(customFrom, { showFromPicker = false }, {
            if (!it.isAfter(customTo)) customFrom = it
            showFromPicker = false
        })
    }
    if (showToPicker) {
        DatePickerModal(customTo, { showToPicker = false }, {
            if (!it.isBefore(customFrom)) customTo = it
            showToPicker = false
        })
    }
}

/** 平滑折线趋势图：网格 + 填充 + 圆点（颜色在组合期取好，Canvas 内不读主题） */
@Composable
private fun FocusTrendChart(data: List<Pair<LocalDate, Int>>, from: LocalDate, to: LocalDate) {
    if (data.isEmpty()) return
    val maxV = data.maxOf { it.second }.coerceAtLeast(30)
    val lineColor = MaterialTheme.colorScheme.primary
    val gridColor = MaterialTheme.colorScheme.surfaceVariant
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    ) {
        Column(Modifier.fillMaxWidth().padding(14.dp)) {
            Canvas(Modifier.fillMaxWidth().height(170.dp)) {
                val w = size.width
                val top = 10.dp.toPx()
                val bottom = size.height - 6.dp.toPx()
                val n = data.size
                fun xPos(i: Int): Float = if (n <= 1) w / 2 else i * w / (n - 1)
                fun yPos(v: Int): Float = bottom - (v.toFloat() / maxV) * (bottom - top)
                repeat(4) { g ->
                    val gy = top + (bottom - top) * g / 3
                    drawLine(gridColor, Offset(0f, gy), Offset(w, gy), strokeWidth = 1.dp.toPx())
                }
                val points = data.mapIndexed { i, (_, v) -> Offset(xPos(i), yPos(v)) }
                val path = androidx.compose.ui.graphics.Path()
                points.forEachIndexed { i, p ->
                    if (i == 0) path.moveTo(p.x, p.y)
                    else {
                        val prev = points[i - 1]
                        val midX = (prev.x + p.x) / 2
                        path.cubicTo(midX, prev.y, midX, p.y, p.x, p.y)
                    }
                }
                val fill = androidx.compose.ui.graphics.Path()
                fill.addPath(path)
                fill.lineTo(points.last().x, bottom)
                fill.lineTo(points.first().x, bottom)
                fill.close()
                drawPath(fill, lineColor.copy(alpha = 0.15f))
                drawPath(path, lineColor, style = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round))
                val dotEvery = if (n <= 32) 1 else n / 16
                points.forEachIndexed { i, p ->
                    if (i % dotEvery == 0 || i == n - 1) {
                        drawCircle(lineColor, radius = 2.5.dp.toPx(), center = p)
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth()) {
                Text(from.formatMonthDay(), style = MaterialTheme.typography.labelSmall, color = labelColor)
                Spacer(Modifier.weight(1f))
                Text(to.formatMonthDay(), style = MaterialTheme.typography.labelSmall, color = labelColor)
            }
        }
    }
}

@Composable
private fun StatCard(label: String, value: String, modifier: Modifier = Modifier) {
    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = modifier
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        }
    }
}

// ============ v62 宽屏专注页：接下来要做 ============

/**
 * 「接下来要做」——专注页侧栏的待办 / 日程（2026-11-19 平板「大而空」批）。
 *
 * 为什么放在这：专注页原来只有计时 + 统计，统计卡再堆也填不满 1000dp 高的侧栏；
 * 而用户打开专注页时最自然的下一句是「那我先做哪件」——这里直接把答案摆在旁边。
 *
 * 口径（全部只读，不新增查询、不写库）：
 * - 数据来自 `Graph.plan.loadDay(今天)`，与首页今日侧栏同一份 DayBundle；
 * - 挑条规则见 [nextUpItems]（纯函数，单测覆盖）；
 * - 点一条走**现有深链**：待办 → `Router.pendingTodoId`、日程 → `Router.pendingScheduleId`，
 *   再切到计划页（`PlanScreen.kt` 的 LaunchedEffect 会打开对应编辑器）；
 * - 勾选完成不在这里做：写路径只保留首页 / 计划页那一份，专注页绝不长第二条。
 */
/**
 * 板内分节容器（2026-11-20 v65）。
 *
 * 原来这些块都是 `Card(18~20dp, surfaceContainer)`——**板内再嵌浮起圆角卡**，
 * 那是第二种材质：板本身已经是「圆角矩形 + 容器底」，块与块之间靠卡片边界切分，
 * 读起来是一串互不相干的浮块（用户报的「割裂」）。
 *
 * 现在板内的分节靠**标题 + 内容 + 细线**表达层次，卡片外壳去掉。
 */
@Composable
private fun FocusBoardSection(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit
) {
    Column(modifier.fillMaxWidth()) {
        Text(
            title,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.height(12.dp))
        content()
    }
}

@Composable
private fun FocusNextUpPane() {
    val haptics = LocalHaptics.current
    val version by Graph.dataVersion.flow.collectAsState()
    var items by remember { mutableStateOf<List<NextUpItem>>(emptyList()) }
    LaunchedEffect(version) {
        val bundle = runCatching { Graph.plan.loadDay(AppTime.today()) }.getOrNull()
        items = if (bundle == null) emptyList()
        else nextUpItems(bundle, System.currentTimeMillis(), NEXT_UP_LIMIT)
    }
    FocusBoardSection("接下来要做") {
            // v72：行装进色调块；空态升级 PaneEmptyBlock（占位块而非一行灰字）
            io.github.m0use001.planability.ui.common.PaneContentBlock {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "今日",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(8.dp))
            if (items.isEmpty()) {
                // 入口常驻纪律（v53 教训）：没有内容就退化展示 + 给去处，不许整卡消失
                io.github.m0use001.planability.ui.common.PaneEmptyBlock("今天没有待办或日程")
            } else {
                items.forEach { item ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .clickable {
                                haptics.perform(HapticEngine.Kind.Tap)
                                if (item.isSchedule) Router.pendingScheduleId = item.id
                                else Router.pendingTodoId = item.id
                                Router.targetTab = "plan"
                            }
                            .padding(vertical = 10.dp)
                    ) {
                        Text(
                            item.badge,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            modifier = Modifier.width(52.dp)
                        )
                        Text(
                            item.title,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
            TextButton(
                onClick = {
                    haptics.perform(HapticEngine.Kind.Tap)
                    Router.targetTab = "plan"
                },
                modifier = Modifier.align(Alignment.End)
            ) { Text("去计划页 ›", style = MaterialTheme.typography.labelMedium) }
            }
    }
}

/** 侧栏只摆最紧要的三条：再多就变成计划页的重复副本了。 */
private const val NEXT_UP_LIMIT = 3

// ============ v59 宽屏专注页右栏：近 7 天柱状 + 最近记录 ============

/**
 * 近 7 天专注柱状。
 *
 * 数据来自 [io.github.m0use001.planability.data.Graph.focus] 的既有查询（sessionsBetween），
 * 口径与「专注统计」页**同一份**（按 startDay 归日、只算 completed），不另立一套算法。
 * 宽屏右栏只有一张「今日专注」卡时下面 400dp 全空——这里用真实数据把它填上。
 */
@Composable
private fun FocusWeekBars() {
    val version by Graph.dataVersion.flow.collectAsState()
    var days by remember { mutableStateOf<List<Pair<LocalDate, Int>>>(emptyList()) }
    LaunchedEffect(version) {
        val today = AppTime.today()
        val from = today.minusDays(6)
        val sessions = Graph.focus.sessionsBetween(from, today)
        days = (0..6).map { i ->
            val d = from.plusDays(i.toLong())
            d to sessions.filter { it.completed && it.startDay == AppTime.dayKey(d) }
                .sumOf { (it.end - it.start) / 60000 }.toInt()
        }
    }
    val maxMin = days.maxOfOrNull { it.second } ?: 0
    FocusBoardSection("近 7 天") {
        // v72.2：柱状图从裸画改「标题 + 一枚矩形」（用户「一行标题+一个圆角矩形」）
        io.github.m0use001.planability.ui.common.PaneContentBlock {
            Row(
                // v62：宽屏侧栏有 ~800dp 高，64dp 的柱子像一条没长开的线——放到 96dp；
                // 今日那根加深、其余降一档透明度，一眼看出「今天还没开始」。
                Modifier.fillMaxWidth().height(96.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.Bottom
            ) {
                days.forEach { (d, min) ->
                    val frac = if (maxMin <= 0) 0f else (min.toFloat() / maxMin).coerceIn(0f, 1f)
                    val isToday = d == AppTime.today()
                    Column(
                        Modifier.weight(1f),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Bottom
                    ) {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height((6 + 76 * frac).dp)
                                .clip(RoundedCornerShape(4.dp))
                                .background(
                                    when {
                                        min <= 0 -> MaterialTheme.colorScheme.surfaceVariant
                                        isToday -> MaterialTheme.colorScheme.primary
                                        else -> MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)
                                    }
                                )
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "${d.dayOfMonth}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            val total = days.sumOf { it.second }
            Spacer(Modifier.height(8.dp))
            Text(
                // 全新用户七根柱全是零，卡片里只剩日期数字，看着像没加载——给一句实话
                if (total == 0) "近 7 天还没有专注记录" else "合计 ${formatMinutes(total)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * v60：本周概览——近 7 天的四个口径（合计 / 日均 / 番茄数 / 连续天数）。
 *
 * 为什么有它：宽屏右栏「近 7 天柱状 + 最近记录」两块之后仍然到屏幕中线就结束，
 * 下面还是空的。这四个数都是同一批 [FocusSession] 现算的，不新增查询口径。
 */
@Composable
private fun FocusWeekStats() {
    val version by Graph.dataVersion.flow.collectAsState()
    var totalMin by remember { mutableStateOf(0) }
    var pomoCount by remember { mutableStateOf(0) }
    var activeDays by remember { mutableStateOf(0) }
    var streak by remember { mutableStateOf(0) }
    LaunchedEffect(version) {
        val today = AppTime.today()
        val from = today.minusDays(6)
        val sessions = Graph.focus.sessionsBetween(from, today).filter { it.completed }
        totalMin = sessions.sumOf { (it.end - it.start) / 60000 }.toInt()
        pomoCount = sessions.size
        val perDay = (0..6).map { i ->
            val d = from.plusDays(i.toLong())
            sessions.filter { it.startDay == AppTime.dayKey(d) }.sumOf { (it.end - it.start) / 60000 }.toInt()
        }
        activeDays = perDay.count { it > 0 }
        // 连续天数：从今天往回数有专注的日子（今天没专注就从昨天起算）
        var s = 0
        var idx = 6
        if (perDay.getOrNull(idx) == 0) idx--
        while (idx >= 0 && perDay[idx] > 0) { s++; idx-- }
        streak = s
    }
    FocusBoardSection("本周概览") {
            // v72.2：四格独立小矩形 → 并进同一枚矩形（细竖线分隔）
            io.github.m0use001.planability.ui.common.PaneStatRow(
                listOf(
                    formatMinutes(totalMin) to "合计",
                    "${totalMin / 7} 分" to "日均",
                    "$pomoCount 个" to "番茄",
                    "$streak 天" to "连续"
                )
            )
    }
}

// v72.2：FocusStatCell 退役（四格并进一枚 PaneStatRow）。

@Composable
private fun PomoChipRow(label: String, options: List<Int>, current: Int, onPick: (Int) -> Unit) {
    val haptics = LocalHaptics.current
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            options.forEach { v ->
                FilterChip(
                    colors = filterChipColors(),
                    selected = current == v,
                    // 番茄设置里的时长/轮数也是"选择"，与主页时长 chip 同档（Select）
                    onClick = { onPick(v); haptics.perform(HapticEngine.Kind.Select) },
                    label = { Text(if (label == "长休前轮数") "$v 轮" else "$v 分") }
                )
            }
        }
    }
}

@Composable
private fun HeatMap() {
    val version by Graph.dataVersion.flow.collectAsState()
    var minutes by remember { mutableStateOf<Map<Long, Int>>(emptyMap()) }
    LaunchedEffect(version) {
        val from = AppTime.today().minusDays(41)
        val sessions = Graph.focus.sessionsBetween(from, AppTime.today())
        minutes = sessions.filter { it.completed }
            .groupBy { it.startDay }
            .mapValues { (_, v) -> v.sumOf { (it.end - it.start) / 60000 }.toInt() }
    }
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        repeat(6) { week ->
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                repeat(7) { dow ->
                    val back = (5 - week) * 7 + (6 - dow)
                    val d = AppTime.today().minusDays(back.toLong())
                    val m = minutes[AppTime.dayKey(d)] ?: 0
                    val intensity = when {
                        m == 0 -> 0f
                        m < 30 -> 0.35f
                        m < 60 -> 0.6f
                        m < 120 -> 0.8f
                        else -> 1f
                    }
                    val color = if (intensity == 0f) MaterialTheme.colorScheme.surfaceVariant
                    else MaterialTheme.colorScheme.primary.copy(alpha = intensity)
                    Box(
                        Modifier
                            .size(34.dp)
                            .clip(RoundedCornerShape(7.dp))
                            .background(color)
                            .clickable { }
                    )
                }
            }
        }
    }
}
