// M3E LoadingIndicator 由 ExperimentalMaterial3ExpressiveApi 门控（beta02 实查注解）
@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class
)

package io.github.m0use001.planability.ui.home

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.FiberManualRecord
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material3.AlertDialog
import io.github.m0use001.planability.ui.common.motionEnter
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.m0use001.planability.core.AppTime
import io.github.m0use001.planability.core.HapticEngine
import io.github.m0use001.planability.core.LocalHaptics
import io.github.m0use001.planability.core.formatCn
import io.github.m0use001.planability.core.formatShort
import io.github.m0use001.planability.data.DayBundle
import io.github.m0use001.planability.data.Graph
import io.github.m0use001.planability.data.Todo
import io.github.m0use001.planability.ui.common.AppIcons
import io.github.m0use001.planability.ui.theme.ShapeTokens
import java.time.LocalDate
import kotlin.math.roundToInt

/** 评分拆解（首页标题环 / 状态卡入口 / 评分详情页共用一份计算） */
data class ScoreBreakdown(
    val total: Int,
    /** v46：加权完成数（Float）——完成=1、未完成且启用百分比=percent/100、其余=0 */
    val done: Float,
    val rate: Float,
    val score: Int,
    val penalty: Int,
    val sleepPenalty: Int,
    /** v46：启用百分比完成且未完成的项数（>0 时评分详情页注明加权口径） */
    val percentCount: Int = 0
)

fun scoreBreakdown(
    bundle: DayBundle?,
    screenMinutes: Int,
    sleep: io.github.m0use001.planability.data.SleepRecord?,
    screenLimitMin: Int,
    screenPenaltyPer30: Int,
    sleepGoalMin: Int,
    sleepPenaltyPer30: Int
): ScoreBreakdown {
    val total = bundle?.let { it.todos.size + it.hourTasks.size } ?: 0
    // 2026-10-29 起：滚期完成的历史实例由 loadDay 还原成 done=true 的显示副本并入 todos，
    // 所以 `count{done}` 已含它们；doneCount 只剩「待办已被删除、还原不出副本」的流水补位。
    // 旧口径曾把全部流水再加一遍 → 完成率 200%（真机复现）。
    // v49：百分比权重走 TodoProgress.contribution（唯一裁决处）。
    // 这段公式此前在 HomeCards 与 TodayStatsWidget 各抄一份（B 类病因活标本：
    // 「同一事实多处定义」），改一处忘一处就是又一个「完成率 200%」。
    // 现在两处都调纯函数，SingleSourceTest 会挡住第三份复制。
    // 注：Kotlin 的 sumOf 没有 Float 溢出（只有 Int/Long/Double/UInt），用 Double 累加
    val done = bundle?.let { b ->
        (b.todos.sumOf { io.github.m0use001.planability.data.TodoProgress.contribution(it.done, it.percentDone) } +
            b.hourTasks.count { it.done } + b.doneCount).toFloat()
    } ?: 0f
    val rate = if (total > 0) (done / total).coerceAtMost(1f) else 0f
    // 综合评分 = 完成率×100 − 屏幕时间超时扣分 − 睡眠不足扣分
    val overMin = if (screenMinutes > screenLimitMin) screenMinutes - screenLimitMin else 0
    val penalty = (overMin / 30) * screenPenaltyPer30
    val sleepShort = sleep?.let { (sleepGoalMin - it.durationMin).coerceAtLeast(0) } ?: 0
    val sleepPenalty = (sleepShort / 30) * sleepPenaltyPer30
    val score = ((rate * 100).roundToInt() - penalty - sleepPenalty).coerceIn(0, 100)
    return ScoreBreakdown(
        total, done, rate, score, penalty, sleepPenalty,
        percentCount = bundle?.todos?.count {
            io.github.m0use001.planability.data.TodoProgress.inProgress(it.done, it.percentDone)
        } ?: 0
    )
}

/**
 * 首页标题栏内的评分胶囊（v19.5 取代原正文状态卡，v19.6 合并完成率，v19.7 放大 + 改称「评分」）：
 * 「评分 N │ ◔ N%」——高 40dp、字号升一级，点击进评分详情页。
 */
@Composable
fun ScorePill(
    bd: ScoreBreakdown,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val haptics = LocalHaptics.current
    val pct = (bd.rate * 100).roundToInt()
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .height(40.dp)
            // 药丸用百分比半径（50% = 任意高度都全圆）。注意：Compose 的 RoundedCornerShape(Int)
            // 重载本来就是 percent，审查报告按 50px 记是误读，这里显式写 percent = 只是明确意图
            .clip(RoundedCornerShape(percent = 50))
            // 颜色分工：评分胶囊是**状态展示**（非操作），用第三色容器；
            // 操作类（按钮/选中态/FAB）统一主色——这样三色都在场但不互相打架。
            .background(MaterialTheme.colorScheme.tertiaryContainer)
            // 评分胶囊：进入详情的中性点击 → Tap
            .clickable { haptics.perform(HapticEngine.Kind.Tap); onClick() }
            .padding(horizontal = 12.dp)
            .semantics { contentDescription = "评分 ${bd.score}，完成率 $pct%，查看评分详情" }
    ) {
        // 2026-10-25 用户「评分与完成率胶囊没对齐」：原来是 CenterVertically + 12sp/16sp
        // 混排，中心对齐但**基线**一高一低。现在四个文本按基线对齐（分隔线仍按行高居中），
        // 整组在 40dp 胶囊里垂直居中。
        Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(
                "评分",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.75f),
                modifier = Modifier.alignByBaseline()
            )
            // v47：数字滚动过渡（打卡/取消/百分比变化时数字平滑走过去，不再跳变）。
            // animateIntAsState 要 Int 谱，Motion 谱是 Float —— 用 Float 动画 + roundToInt。
            val scoreF by androidx.compose.animation.core.animateFloatAsState(
                targetValue = bd.score.toFloat(),
                animationSpec = io.github.m0use001.planability.ui.common.Motion.quick()
            )
            Text(
                "${scoreF.roundToInt()}",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
                modifier = Modifier.alignByBaseline()
            )
            // 分隔竖线：评分 | 完成
            Box(
                Modifier
                    .align(Alignment.CenterVertically)
                    .width(1.dp)
                    .height(16.dp)
                    .background(MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.35f))
            )
            Text(
                "完成",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.75f),
                modifier = Modifier.alignByBaseline()
            )
            val pctF by androidx.compose.animation.core.animateFloatAsState(
                targetValue = pct.toFloat(),
                animationSpec = io.github.m0use001.planability.ui.common.Motion.quick()
            )
            Text(
                "${pctF.roundToInt()}%",
                // 2026-10-25 用户「字体不整齐」：评分数字是 titleMedium/Bold，完成率这一段
                // 原来是 labelMedium/常规 → 数字与 % 看着比评分轻一档。两侧数字统一字号与字重，
                // 「评分/完成」两枚文字标签保持 labelMedium，层级仍是"标签轻、数值重"。
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
                modifier = Modifier.alignByBaseline()
            )
        }
    }
}

/** 胶囊内的完成率迷你进度环（v19.7 已改为「完成 N%」文字，此函数仅备用） */
@Composable
private fun MiniRateArc(frac: Float) {
    val primary = MaterialTheme.colorScheme.primary
    val track = MaterialTheme.colorScheme.surfaceVariant
    val arcSize = 16.dp
    Canvas(Modifier.size(arcSize)) {
        val stroke = 2.5.dp.toPx()
        val d = arcSize.toPx()
        val r = (d - stroke) / 2
        drawCircle(color = track, radius = r, style = androidx.compose.ui.graphics.drawscope.Stroke(stroke))
        if (frac > 0.005f) {
            drawArc(
                color = primary,
                startAngle = -90f,
                sweepAngle = frac * 360f,
                useCenter = false,
                style = androidx.compose.ui.graphics.drawscope.Stroke(
                    stroke, cap = androidx.compose.ui.graphics.StrokeCap.Round
                ),
                topLeft = androidx.compose.ui.geometry.Offset(stroke / 2, stroke / 2),
                size = androidx.compose.ui.geometry.Size(d - stroke, d - stroke)
            )
        }
    }
}

/**
 * 今日评分详情页（v19.2 从首页迁入）：计算过程（完成率得分/加分项/扣分项/合计）
 * + 输入项（屏幕使用、睡眠——含入睡/醒来/达标等完整详情）。
 */
@Composable
fun ScoreDetailContent(
    bd: ScoreBreakdown,
    screenMinutes: Int,
    sleep: io.github.m0use001.planability.data.SleepRecord?,
    sleepBusy: Boolean,
    onEditScreenTime: (Int) -> Unit,
) {
    val haptics = LocalHaptics.current
    // P2-2：CompositionLocal 只能在组合期读（onClick lambda 里读会编译错），先取出来
    val overlayClose = io.github.m0use001.planability.app.LocalOverlayClose.current
    val goals = Graph.settings.snapshot.collectAsState().value
    val limit = goals.screenLimitMin
    val base = (bd.rate * 100).roundToInt()
    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
    ) {
        // 头部：返回 + 标题（与其他覆盖层一致）
        Row(verticalAlignment = Alignment.CenterVertically) {
            // 顶栏返回 = 中性点击 → Tap。
            // P2-2（第二轮审查）：必须走 LocalOverlayClose（带 Motion.standard 收缩）——
            // 旧写法直接调 onClose 绕过了 PredictiveBackOverlay 的关闭动画，
            // 同一个壳上编辑器们都有退场动画、唯独本页瞬间消失（不一致）。
            IconButton(onClick = {
                haptics.perform(HapticEngine.Kind.Tap)
                overlayClose()
            }) {
                Icon(
                    AppIcons.Nav.Back, "返回",
                    // R11：返回是导航图标，颜色不表态（原生顶栏返回也是中性灰）
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.width(4.dp))
            Text("今日评分", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(4.dp))

        // 大数字（完成度并入计算卡首行、合计=本数字，均不再重复展示）
        Text(
            "${bd.score}",
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.height(14.dp))

        // ---- 计算框：台账式两列（左=项目，右=加/扣分；完成率基础分计入加分侧） ----
        Card(
            shape = ShapeTokens.card,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                // v19.4：无加分来源时不再写「加分项：暂无」（用户要求）；加分侧只展示真实存在的项
                ScoreLedgerRow("完成率基础分", "+$base 分", MaterialTheme.colorScheme.primary)
                if (bd.percentCount > 0) {
                    // v46：有未完成但开了百分比的待办时，说明完成率不是整数计数
                    Text(
                        "含 ${bd.percentCount} 项按完成度百分比加权计入",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (bd.penalty > 0) {
                    Spacer(Modifier.height(8.dp))
                    ScoreLedgerRow("屏幕超时", "−${bd.penalty} 分", MaterialTheme.colorScheme.error)
                }
                if (bd.sleepPenalty > 0) {
                    Spacer(Modifier.height(8.dp))
                    ScoreLedgerRow("睡眠不足", "−${bd.sleepPenalty} 分", MaterialTheme.colorScheme.error)
                }
            }
        }
        Spacer(Modifier.height(12.dp))

        // ---- 输入项：屏幕 + 睡眠合一卡（每项一行：文本 + 状态图标 + 图标钮） ----
        Card(
            shape = ShapeTokens.card,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)
                ) {
                    Text(
                        if (screenMinutes >= 0)
                            "屏幕 ${screenMinutes / 60}小时${screenMinutes % 60}分 · 上限 ${limit / 60}小时"
                        else "屏幕 未统计（可手动填写）",
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    ScreenTimeEdit(screenMinutes, onEditScreenTime)
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)
                ) {
                    Text(
                        if (sleep != null)
                            "睡眠 ${sleep.durationMin / 60}小时${sleep.durationMin % 60}分 · ${fmtMin(sleep.fallMin)}→${fmtMin(sleep.wakeMin)}"
                        else "睡眠 未采集（OPPO 健康 · 点按更新）",
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        color = if (sleep == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f)
                    )
                    if (sleep != null) {
                        Icon(
                            if (sleep.atGoal) AppIcons.State.Check else AppIcons.State.Warn,
                            if (sleep.atGoal) "达标" else "不足",
                            Modifier.size(14.dp).padding(end = 2.dp),
                            tint = if (sleep.atGoal) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                        )
                    }
                    if (sleepBusy) {
                        // M3E：短等待（同步握手 <5s）用 LoadingIndicator（形状轮换），不再用老式转圈
                        androidx.compose.material3.LoadingIndicator(Modifier.size(24.dp))
                    }
                }
            }
        }
        Spacer(Modifier.height(40.dp))
    }
}

/** 计算框台账行：左=项目，右=加/扣分分值 */
@Composable
private fun ScoreLedgerRow(
    label: String,
    value: String,
    valueColor: androidx.compose.ui.graphics.Color
) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = valueColor
        )
    }
}

/** 分钟数 → "HH:MM" */
private fun fmtMin(min: Int): String =
    "%02d:%02d".format((min / 60) % 24, min % 60)

@Composable
private fun ScreenTimeEdit(current: Int, onManual: (Int) -> Unit) {
    var editing by remember { mutableStateOf(false) }
    var text by remember { mutableStateOf("") }
    // v19.1：图标钮化（状态卡输入层单行化），点击行为与弹窗不变
    IconButton(
        onClick = { text = if (current > 0) current.toString() else ""; editing = true },
        modifier = Modifier.size(32.dp)
    ) {
        Icon(
            AppIcons.Act.Edit,
            if (current >= 0) "修改屏幕时间" else "手动填写屏幕时间",
            Modifier.size(17.dp),
            // R11：卡头快捷动作 → 中性
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
    if (editing) {
    AlertDialog(
        modifier = Modifier.motionEnter(),
        onDismissRequest = { editing = false },
            title = { Text("屏幕使用时间（分钟）") },
            text = {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it.filter { c -> c.isDigit() }.take(4) },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onManual(text.toIntOrNull() ?: 0)
                    editing = false
                }) { Text("确定") }
            },
            dismissButton = { TextButton(onClick = { editing = false }) { Text("取消") } }
        )
    }
}

// ---------- 屏幕使用时间（UsageStats / 手动） ----------

suspend fun loadScreenMinutes(date: java.time.LocalDate): Int {
    val dayKey = AppTime.dayKey(date)
    val manual = Graph.settings.getManualScreenTime(dayKey)
    if (manual > 0) return manual
    val context = Graph.appContext
    if (!hasUsagePermission(context)) return -1
    // 2026-10-27 用户「首页时间轴还是有卡顿/有时候卡住」：UsageStats 的 queryEvents 是
    // binder 调用 + 下面逐事件遍历（ColorOS 事件流一整天可达数万条），原来这段跑在
    // **主线程**（suspend fun 不自动切线程，LaunchedEffect 默认 Main）——数据一变更
    // 就在界面上直接卡住。整段搬到 IO。（Room 的 suspend DAO 自己会切，不在此列。）
    return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
    runCatching {
        val usm = context.getSystemService(android.content.Context.USAGE_STATS_SERVICE) as android.app.usage.UsageStatsManager
        val start = date.atStartOfDay(AppTime.zone).toInstant().toEpochMilli()
        val end = date.plusDays(1).atStartOfDay(AppTime.zone).toInstant().toEpochMilli()
        // 不能用 queryUsageStats 求和：返回的桶与查询区间是"重叠即整桶返回"，
        // OEM 桶边界与本地零点不对齐时会返回两个整天桶相加 → 直接爆 24h。
        // 改逐事件累计：按包配对成段 → 与亮屏段求交 → 区间并集（详见下方 v19 注释）
        val events = usm.queryEvents(start, end)
        val ev = android.app.usage.UsageEvents.Event()
        // v19 终版：按包配对 + 全部区间取并集。
        // ColorOS 的事件流是分钟级交错簇（同分钟内多包 P 密集、单包 R/P 高频震荡），
        // 旧"全局单槽"配对大面积失败；失败的孤儿暂停曾从午夜起算——10-03 实测 launcher
        // 孤儿累计 6400 分钟、总和 7630 分钟顶穿 24h 上限（UI 恒显 24 小时）。
        // 终版：孤儿暂停（窗口内无配对 RESUME）直接忽略；同包 R→P 成对成段；跨包区间并集防重叠双计。
        val openResume = HashMap<String, Long>()
        val intervals = ArrayList<LongArray>()
        val screenOn = ArrayList<LongArray>()
        var screenState: Boolean? = null
        var onStart = 0L
        var firstScreenTyp = 0        // 0=全天无屏幕事件 1=首个是亮屏 -1=首个是灭屏
        var firstScreenT = 0L
        val endNow = System.currentTimeMillis().coerceAtMost(end)
        while (events.hasNextEvent()) {
            events.getNextEvent(ev)
            when (ev.eventType) {
                android.app.usage.UsageEvents.Event.ACTIVITY_RESUMED -> {
                    // 同包连续 RESUME 保留更早起点（Activity 重建不丢段）
                    if (openResume[ev.packageName] == null) openResume[ev.packageName] = ev.timeStamp
                }
                android.app.usage.UsageEvents.Event.ACTIVITY_PAUSED -> {
                    val r = openResume.remove(ev.packageName)
                    if (r != null) {
                        val from = r.coerceAtLeast(start)
                        val to = ev.timeStamp.coerceAtMost(end)
                        if (to > from) intervals.add(longArrayOf(from, to))
                    }
                }
                android.app.usage.UsageEvents.Event.SCREEN_INTERACTIVE,
                android.app.usage.UsageEvents.Event.SCREEN_NON_INTERACTIVE -> {
                    val on = ev.eventType == android.app.usage.UsageEvents.Event.SCREEN_INTERACTIVE
                    if (firstScreenTyp == 0) {
                        firstScreenTyp = if (on) 1 else -1
                        firstScreenT = ev.timeStamp
                    }
                    if (on) {
                        if (screenState != true) onStart = ev.timeStamp
                        screenState = true
                    } else {
                        if (screenState == true && onStart > 0) {
                            val to = ev.timeStamp.coerceAtMost(end)
                            if (to > onStart) screenOn.add(longArrayOf(onStart, to))
                        }
                        screenState = false
                        onStart = 0L
                    }
                }
            }
        }
        openResume.values.forEach { r ->
            val from = r.coerceAtLeast(start)
            if (endNow > from) intervals.add(longArrayOf(from, endNow))
        }
        when {
            firstScreenTyp == 0 -> screenOn.add(longArrayOf(start, end))
            else -> {
                // 首个事件是灭屏 → 它之前是亮屏
                if (firstScreenTyp == -1) screenOn.add(longArrayOf(start, firstScreenT))
                if (screenState == true && onStart > 0) screenOn.add(longArrayOf(onStart, endNow))
            }
        }
        // 屏幕使用 = 前台区间 ∩ 亮屏区间（v19：ColorOS 熄屏常不发 PAUSED，
        // 实测 planability 01:28 RESUME→08:33 PAUSE 横跨睡眠全被计入；亮屏段事件成对可靠）
        val onScreen = ArrayList<LongArray>()
        for (f in intervals) {
            for (s in screenOn) {
                val a = maxOf(f[0], s[0])
                val b = minOf(f[1], s[1])
                if (b > a) onScreen.add(longArrayOf(a, b))
            }
        }
        onScreen.sortBy { it[0] }
        var total = 0L
        var curS = -1L
        var curE = -1L
        for (iv in onScreen) {
            if (curE < 0) {
                curS = iv[0]; curE = iv[1]
            } else if (iv[0] <= curE) {
                if (iv[1] > curE) curE = iv[1]
            } else {
                total += curE - curS
                curS = iv[0]; curE = iv[1]
            }
        }
        if (curE >= 0) total += curE - curS
        android.util.Log.d("ScreenTime", "total=${total / 60000}m fg=${intervals.size}段 on=${screenOn.size}段 交集并集")
        (total / 60000L).toInt()
    }.getOrDefault(-1).let { if (it == 0) -1 else it }
    }
}

fun hasUsagePermission(context: android.content.Context): Boolean = runCatching {
    val appOps = context.getSystemService(android.content.Context.APP_OPS_SERVICE) as android.app.AppOpsManager
    val mode = appOps.unsafeCheckOpNoThrow(
        "android:get_usage_stats", android.os.Process.myUid(), context.packageName
    )
    mode == android.app.AppOpsManager.MODE_ALLOWED
}.getOrDefault(false)

// ---------- 未设置时间计划胶囊 ----------

/**
 * 2026-11-02 用户：「允许待办不设置时间（但日期要设置），无时间待办/日程就在首页
 * 底部栏上方显示一个胶囊，写着「未设置时间计划：」然后显示到底几个」。
 *
 * 无时间待办（due<=0）在时间轴上排不了位（TimelineGrid 只画 due>0 的块），
 * 以前就等于"建了但首页看不见"。这里在底栏上方汇总数量，点一下去计划页补时间。
 * 只数**今天、未完成**的那批：总数跨天会越滚越大，失去"提醒现在处理"的意义。
 */
internal fun untimedPendingCount(todos: List<io.github.m0use001.planability.data.Todo>): Int =
    todos.count { it.due <= 0 && !it.done }

@Composable
fun UntimedPlanCapsule(
    count: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    // v48.5（真机「打卡后改不了百分比」终局根因）：原来 count<=0 直接 return ——
    // 没设时间的待办**全部打卡后**计数归零，胶囊整个不渲染，首页就再没有入口
    // 打开这些待办的百分比浮层（100% 也就"锁死"了）。现在只有负数（无效入参）才隐藏；
    // 显示与否交给调用方的 hasUntimed（有没有未设时间的待办）。
    if (count < 0) return
    // 2026-11-20：胶囊是 50% 圆角，但 clickable 挂在内层 Row 上、且没有 clip →
    // 按压水波是一个**直角矩形**，在胶囊里戳出四个角。把点击挂到 Surface 上（Surface 的
    // 形状裁剪在内容外侧，指示会跟着胶囊走），内层只留 padding。
    androidx.compose.material3.Surface(
        onClick = onClick,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(percent = 50),
        color = androidx.compose.material3.MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = modifier
    ) {
        Row(
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
        ) {
            Text(
                if (count > 0) "未设置时间计划：$count" else "未设置时间计划 · 已完成",
                style = androidx.compose.material3.MaterialTheme.typography.labelMedium,
                color = androidx.compose.material3.MaterialTheme.colorScheme.primary
            )
        }
    }
}

// ---------- 剪贴板浮条 ----------

@Composable
fun ClipboardBar(
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onImport: (String) -> Unit
) {
    if (!enabled) return
    val context = androidx.compose.ui.platform.LocalContext.current
    var text by remember { mutableStateOf<String?>(null) }
    var dismissedHash by remember { mutableStateOf(0) }

    DisposableEffect(Unit) {
        val activity = context as? androidx.activity.ComponentActivity
        val observer = androidx.lifecycle.LifecycleEventObserver { _, e ->
            if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                val clip = cm.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString()?.trim()
                text = if (!clip.isNullOrBlank() && clip.length in 4..500 && clip.hashCode() != dismissedHash) clip else null
            }
        }
        activity?.lifecycle?.addObserver(observer)
        onDispose { activity?.lifecycle?.removeObserver(observer) }
    }

    text?.let { clip ->
        // 2026-10-29 用户：提示条固定 6 秒自动消失（挡在首页底部碍事）。
        // 自动关闭与手动关闭同语义（记 dismissedHash）——同一条剪贴板内容回前台不再弹，
        // 否则 ON_RESUME 的检测会立刻把它弹回来，等于永远关不掉。
        LaunchedEffect(clip) {
            kotlinx.coroutines.delay(6_000L)
            dismissedHash = clip.hashCode()
            text = null
        }
        androidx.compose.material3.Surface(
            shape = ShapeTokens.card,
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
            shadowElevation = 6.dp,
            modifier = modifier
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(start = 14.dp, end = 4.dp, top = 4.dp, bottom = 4.dp)
            ) {
                androidx.compose.material3.Icon(
                    AppIcons.Kind.Clipboard, null,
                    // R11：剪贴板候选条的行首类型图标 → 中性
                    tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp)
                )
                Text(
                    " 检测到可导入文本：${clip.take(18)}…",
                    style = MaterialTheme.typography.bodySmall, maxLines = 1
                )
                TextButton(onClick = { onImport(clip); dismissedHash = clip.hashCode(); text = null }) { Text("建待办") }
                androidx.compose.material3.IconButton(
                    onClick = { dismissedHash = clip.hashCode(); text = null },
                    modifier = Modifier.size(28.dp)
                ) {
                    androidx.compose.material3.Icon(
                        AppIcons.Nav.Close, null, tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
    }
}

// ==================== v59 宽屏「今日」侧栏 ====================

/**
 * 宽屏首页右栏（16:10 平板上「大而空」的直接解药，2026-11-17）。
 *
 * 为什么要有它：v57/v58 只把一级页限住了宽度，右侧仍然是一片空白——
 * 用户截图点名「仍有许多像图中这种大而空的界面」。按 iPad HIG 的 split view 思路，
 * 宽屏的空必须用**第二栏真内容**填掉，而不是靠留白。
 *
 * 这一栏放的全是既有事实、只是此前散在别处的东西：
 * ① 今日概览（完成率/评分/日程待办数）——与标题栏评分胶囊**同一份** [ScoreBreakdown]
 *    （由首页 scoreBreakdown 现算后传进来，这里只显示不重算公式，避免
 *    「同一事实两处定义」那类病再犯）；
 * ② 今日待办清单——勾选走首页同一条 checkInTodo（百分比待办弹同一个浮层）；
 * ③ 「未设置时间计划」入口——复用 [UntimedPlanCapsule]，点开原二级菜单；
 * ④ 专注入口——跳专注 tab（用了 HomeScreen 声明已久却一直未用的 onSelectTab）。
 *
 * 数字口径：全部来自调用方已算好的 bundle / bd / date，
 * 所以「切到别的日期看时间轴」时右栏跟着那天走，不会「左栏昨天、右栏今天」。
 */
@Composable
fun HomeTodayPane(
    date: LocalDate,
    bundle: DayBundle?,
    bd: ScoreBreakdown,
    onToggleTodo: (Todo) -> Unit,
    onOpenTodo: (Long) -> Unit,
    onOpenSchedule: (Long) -> Unit,
    onOpenUntimed: () -> Unit,
    onFocus: () -> Unit,
    // v70i（2026-11-21 用户「右划菜单仅保留搜索功能，剩下功能合并至首页副框」）：
    // 原全局搜索抽屉里的 日历入口 + 今日统计框 迁到这里，入口行点开走首页的日历覆盖层。
    onOpenCalendar: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHaptics.current
    val todos = bundle?.todos.orEmpty()
    // 未完成在前；有时刻的按时刻排，无时刻的殿后（同 due 再按紧急/重要）
    val pending = remember(bundle) {
        todos.filter { !it.done }
            .sortedWith(
                compareBy<Todo>(
                    { if (it.due > 0) 0 else 1 },
                    { if (it.due > 0) it.due else 0L },
                    { -(it.urgency.coerceAtLeast(-1)) },
                    { -(it.importance.coerceAtLeast(-1)) }
                )
            )
    }
    val hasUntimed = todos.any { it.due <= 0 }
    val untimedCount = untimedPendingCount(todos)

    Column(
        modifier
            .fillMaxHeight()
            // 2026-11-20 v65：底色与状态栏 inset 都由板壳（SplitBoards）给了——
            // 这里再自带 background/statusBarsPadding 就是「板里再画一层板」+
            // 双倍顶部空白。板内只留内容。
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp)
    ) {
        Spacer(Modifier.height(18.dp))
        // 宽屏下 M3E 顶栏只压在时间轴左栏，这一栏自己起一行小标题。
        // 不重复「今天 / 评分」——顶栏已经有了，同一句话两处并排会显得没做完。
        Text(
            "今日概览",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold
        )
        Text(
            date.formatCn(),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp)
        )
        Spacer(Modifier.height(14.dp))

        // ---- 今日概览 · 完成率（v72：环+大数字卡，用户拍板；原来只有一根 8dp 细条）----
        // v72.2：补一行标题在矩形外（用户「一行标题+一个圆角矩形」）
        io.github.m0use001.planability.ui.common.PaneTitleRow("完成率")
        io.github.m0use001.planability.ui.common.PaneStatRing(
            fraction = bd.rate,
            headline = if (bd.total > 0) "${(bd.rate * 100).roundToInt()}%" else "暂无任务",
            subline = if (bd.total > 0) {
                val d = if (bd.done % 1f == 0f) bd.done.toInt().toString()
                else String.format(java.util.Locale.US, "%.1f", bd.done)
                "完成 $d / ${bd.total} 项"
            } else null,
            chips = listOf(
                "日程 ${bundle?.schedules?.size ?: 0}",
                "待办 ${todos.size}"
            )
        )

        // ---- 今日日程（v59：侧栏原来只有待办——有课表时侧栏仍是空的）----
        val schedules = bundle?.schedules.orEmpty()
        if (schedules.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            // v72：节装进色调圆角块（与计划右板同款词汇）
            io.github.m0use001.planability.ui.common.PaneSectionBlock(
                "日程",
                titleTrailing = { io.github.m0use001.planability.ui.common.PaneChip("共 ${schedules.size}") }
            ) {
                schedules.forEach { occ ->
                    Row(
                        verticalAlignment = Alignment.Top,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .clickable { onOpenSchedule(occ.schedule.id) }
                            .padding(vertical = 7.dp, horizontal = 4.dp)
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
            }
        }

        // ---- 今日待办（v72：装色调块；空态升级占位块）----
        Spacer(Modifier.height(12.dp))
        io.github.m0use001.planability.ui.common.PaneSectionBlock(
            "待办",
            titleTrailing = { io.github.m0use001.planability.ui.common.PaneChip("${pending.size}/${todos.size}") }
        ) {
            if (pending.isEmpty()) {
                // v72.1：块内空态不再嵌套占位块（同色会隐形）——居中一行图标+文案
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 16.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Rounded.CheckCircle, null, Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "这一天没有待办",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                pending.take(PANE_TODO_LIMIT).forEach { t ->
                    HomePaneTodoRow(
                        todo = t,
                        onToggle = { onToggleTodo(t) },
                        onOpen = { onOpenTodo(t.id) }
                    )
                }
                if (pending.size > PANE_TODO_LIMIT) {
                    Text(
                        "还有 ${pending.size - PANE_TODO_LIMIT} 项，去计划页查看",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
        }

        if (hasUntimed) {
            Spacer(Modifier.height(10.dp))
            UntimedPlanCapsule(
                count = untimedCount,
                onClick = {
                    haptics.perform(HapticEngine.Kind.Tap)
                    onOpenUntimed()
                }
            )
        }

        // ---- 专注 + 日历入口（v72.2：两个入口并进**同一枚**圆角矩形，标题「快捷」在外，
        // 行间细线分隔——用户「严格改成一行标题+一个圆角矩形」，不再一块一个入口）----
        Spacer(Modifier.height(12.dp))
        io.github.m0use001.planability.ui.common.PaneSectionBlock("快捷") {
            io.github.m0use001.planability.ui.common.PaneActionRow(
                onClick = {
                    haptics.perform(HapticEngine.Kind.Select)
                    onFocus()
                },
                icon = Icons.Rounded.Timer,
                title = "专注",
                subtitle = "番茄钟 / 正计时",
                trailing = {
                    Text("开始", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                }
            )
            androidx.compose.material3.HorizontalDivider(
                modifier = Modifier.padding(vertical = 4.dp),
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
            )
            io.github.m0use001.planability.ui.common.PaneActionRow(
                onClick = {
                    haptics.perform(HapticEngine.Kind.Tap)
                    onOpenCalendar()
                },
                icon = Icons.Rounded.CalendarMonth,
                title = "日历",
                subtitle = "月视图 · 倒数日",
                trailing = {
                    Text("打开", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                }
            )
        }

        // ---- 今日统计（v70i：原全局搜索抽屉「搜索框下方」的数据框迁到这里——
        // 完成率 / 屏幕时间 / 睡眠三条进度条 + 综合评分，与首页评分同一份口径）----
        Spacer(Modifier.height(14.dp))
        io.github.m0use001.planability.ui.common.TodayStatsCard(Modifier.fillMaxWidth())

        // 底部留白：板壳（SplitBoards）已经按导航条/药丸算过 bottom inset，
        // 板内只补一点呼吸距，不再重复垫 navAvoid（双倍底部空白）。
        Spacer(Modifier.height(16.dp))
    }
}

private const val PANE_TODO_LIMIT = 10

/** 侧栏待办行：圈 = 勾选（走首页 checkInTodo 同一条路），文字 = 进编辑器。 */
@Composable
private fun HomePaneTodoRow(todo: Todo, onToggle: () -> Unit, onOpen: () -> Unit) {
    val haptics = LocalHaptics.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable { haptics.perform(HapticEngine.Kind.Tap); onOpen() }
            .padding(vertical = 7.dp, horizontal = 4.dp)
    ) {
        Icon(
            if (todo.done) Icons.Rounded.CheckCircle else Icons.Rounded.FiberManualRecord,
            contentDescription = if (todo.done) "已完成" else "未完成",
            tint = if (todo.done) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .size(18.dp)
                .clickable { onToggle() }
        )
        Spacer(Modifier.width(10.dp))
        Text(
            todo.title,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Spacer(Modifier.width(8.dp))
        val badge = when {
            todo.due > 0 -> AppTime.millisToTime(todo.due)
            percentEnabled(todo) -> "${todo.percentDone}%"
            else -> "未设时间"
        }
        Text(
            badge,
            style = MaterialTheme.typography.labelSmall,
            color = if (todo.due > 0) MaterialTheme.colorScheme.onSurfaceVariant
            else MaterialTheme.colorScheme.tertiary
        )
    }
}
