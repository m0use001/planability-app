package io.github.m0use001.planability.ui.plan

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.m0use001.planability.app.PredictiveBackOverlay
import io.github.m0use001.planability.app.navAvoidPadding
import io.github.m0use001.planability.core.AppTime
import io.github.m0use001.planability.core.HapticEngine
import io.github.m0use001.planability.core.LocalHaptics
import io.github.m0use001.planability.core.formatMonthDay
import io.github.m0use001.planability.core.toLocalDate
import io.github.m0use001.planability.data.CheckinDay
import io.github.m0use001.planability.data.CheckinFormat
import io.github.m0use001.planability.data.CheckinKind
import io.github.m0use001.planability.data.CheckinLedger
import io.github.m0use001.planability.data.CheckinRecord
import io.github.m0use001.planability.data.CheckinSeries
import io.github.m0use001.planability.data.Graph
import io.github.m0use001.planability.data.Todo
import io.github.m0use001.planability.ui.common.BackfillCheckinDialog
import io.github.m0use001.planability.ui.common.HapticBackButton
import io.github.m0use001.planability.ui.common.filterChipColors
import io.github.m0use001.planability.ui.common.pressScale
import io.github.m0use001.planability.ui.common.wobbleOn
import io.github.m0use001.planability.ui.profile.CheckinTrendBlock
import java.time.LocalDate
import java.time.YearMonth
import kotlinx.coroutines.launch

/**
 * v54.4 打卡面板：把散在四处的打卡事实（监督账本 / 数据记录 / 完成率 / 连击）
 * 收成一屏两层的统一查看面。
 *
 * 为什么要有它（2026-11-07 审查结论）：数据四张表都在，但全文记录只有编辑器里 7 条、
 * 监督日历埋在"已过期"和编辑器两处死角、文本/多选型打卡零统计、热力图只做了专注——
 * 「打卡情况与打卡数据记录查看难」的病灶全在展示层。
 *
 * 两层：
 * - 面板（列表）= 顶部「今天」chip 行（点 chip 就地打卡，零跳转）+ 每个打卡待办一行
 *   （连击 / 30 天密度条 / 完成率），点行进详情；
 * - 详情 = 月历热力（点天 = 补交/改那天）+ 连击与账目 + 趋势（时间/数值/计数折线、
 *   多选标签频次）+ 全部数据记录（不再只有 7 条）。
 *
 * 语义红线（与计划页/首页同一条链，绝不另立口径）：
 * - 监督待办的今日打卡走 [io.github.m0use001.planability.data.PlanRepo.completeTodo]
 *   （滚期 + 完成流水 + 监督卡 + 零输入自动落库，与计划页胶囊完全一致）；
 * - 补交 / 改某天 / 撤销某天走 BackfillCheckinDialog → backfillCheck（监督）或
 *   addCheckin（纯数据记录）——与计划页「已过期」展开同法；
 * - 纯数据记录（未开监督）的待办没有「应打日」，有记录即完成，不造欠账。
 *
 * 候选范围：开了监督的待办 + 有过数据记录的待办；一条记录都没有且没开监督的
 * 待办不进面板（没有任何可看的东西，进来看空）。
 */
private suspend fun loadCheckinItem(todo: Todo, seriesBy: Map<Long, CheckinSeries>): CheckinBoardItem {
    val supervision = todo.supervisionEnabled
    val records = Graph.plan.checkinsOf(todo.id)
    var ledger = Graph.plan.checkinLedger(todo)
    if (ledger.isEmpty() && !supervision && records.isNotEmpty()) {
        // 纯数据记录：由记录直接合成账本（有记录=完成，迟交按提交时刻算）
        ledger = records.map { r ->
            val delay = CheckinLedger.delayDays(r.date, r.at, AppTime.zone)
            CheckinDay(
                date = r.date, dayNo = 0,
                state = if (delay > 0) CheckinDay.LATE else CheckinDay.DONE,
                delayDays = delay, credit = CheckinLedger.credit(delay),
                record = r
            )
        }
    }
    val todayKey = AppTime.dayKey(AppTime.today())
    // 只认 DONE/LATE；PENDING（今天还没打）的 checked 也是 true，不能拿来判"今天已打"
    val checkedToday = if (supervision) ledger.any {
        it.date == todayKey && (it.state == CheckinDay.DONE || it.state == CheckinDay.LATE)
    } else records.any { it.date == todayKey }

    // 连击：从最新往回数连续完成；PENDING（今天还没打）跳过不打断，MISSED 才断
    fun runAt(days: List<CheckinDay>, from: Int): Int {
        var n = 0
        var i = from
        while (i < days.size) {
            val d = days[i]
            if (d.state == CheckinDay.PENDING) {
                i++
                continue
            }
            if (!d.checked) break
            n++
            i++
        }
        return n
    }
    val streak = runAt(ledger, 0)
    var best = 0
    for (i in ledger.indices) {
        if (ledger[i].state == CheckinDay.PENDING || !ledger[i].checked) continue
        best = maxOf(best, runAt(ledger, i))
    }
    return CheckinBoardItem(
        todo = todo, supervision = supervision, ledger = ledger, records = records,
        series = seriesBy[todo.id],
        checkedToday = checkedToday, streak = streak, best = best,
        rate = CheckinLedger.rate(ledger),
        missed = CheckinLedger.missedCount(ledger),
        late = CheckinLedger.lateCount(ledger)
    )
}

private suspend fun loadBoardCandidates(): List<Todo> {
    val sup = Graph.plan.supervisionTodos()
    val withRecords = Graph.plan.checkinPairs(1).map { it.first }
    return (sup + withRecords).distinctBy { it.id }.filter { !it.trashed }
}

internal data class CheckinBoardItem(
    val todo: Todo,
    val supervision: Boolean,
    /** 逐日账本，倒序（CheckinLedger 口径）；纯数据记录由记录合成 */
    val ledger: List<CheckinDay>,
    /** 全量数据记录，倒序 */
    val records: List<CheckinRecord>,
    val series: CheckinSeries?,
    val checkedToday: Boolean,
    val streak: Int,
    val best: Int,
    val rate: Int,
    val missed: Int,
    val late: Int
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CheckinBoardScreen(initialTodoId: Long? = null, onDone: () -> Unit, deferBack: Boolean = false) {
    val scope = rememberCoroutineScope()
    val haptics = LocalHaptics.current
    val version by Graph.dataVersion.flow.collectAsState()
    var items by remember { mutableStateOf<List<CheckinBoardItem>?>(null) }
    var detailId by remember { mutableStateOf(initialTodoId) }
    // 防连点双滚：completeTodo 带滚期，同一快照打两次会把行滚两期
    var busy by remember { mutableStateOf(false) }

    LaunchedEffect(version) {
        val cands = loadBoardCandidates()
        val seriesBy = Graph.plan.checkinSeries().associateBy { it.todoId }
        items = cands.map { loadCheckinItem(it, seriesBy) }
    }

    // 今日/某天的录入弹层（面板与详情共用）
    var capture by remember { mutableStateOf<PendingCheckin?>(null) }
    var dayEdit by remember { mutableStateOf<Pair<Todo, LocalDate>?>(null) }

    // 详情是面板的层级态：同一覆盖层内先退详情再退面板（文件库分层返回同法）。
    // 2026-11-08 用户「点击待办打卡监督数据界面返回会强制先跳转一个打卡界面」：
    // 从编辑器/数据中心**带着某个待办**进来时（initialTodoId 非空），详情就是根层——
    // 返回直接退出，不再先掉到面板列表白闪一页。只有从面板列表点行进详情
    // （initialTodoId 为空）才保留「先退详情再退列表」。
    val detailIsRoot = initialTodoId != null
    PredictiveBackOverlay(onBack = {
        if (detailId != null && !detailIsRoot) detailId = null else onDone()
    }, deferBack = deferBack) {
        val detail = detailId?.let { id -> items?.firstOrNull { it.todo.id == id } }
        if (detail == null) {
            // ---------- 面板 ----------
            Scaffold(
                containerColor = if (deferBack) androidx.compose.ui.graphics.Color.Transparent else androidx.compose.material3.MaterialTheme.colorScheme.background,
                topBar = {
                    TopAppBar(navigationIcon = { HapticBackButton(onDone) }, title = { Text("打卡", fontWeight = FontWeight.SemiBold) })
                }
            ) { pad ->
                val list = items
                when {
                    list == null -> Box(Modifier.fillMaxSize().padding(pad), contentAlignment = Alignment.Center) {
                        Text("载入中…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    list.isEmpty() -> Box(Modifier.fillMaxSize().padding(pad), contentAlignment = Alignment.Center) {
                        Text(
                            "还没有打卡待办。\n给待办开启「打卡监督」或「数据记录」后，会在这里汇总。",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    else -> LazyColumn(
                        Modifier.fillMaxSize().padding(pad),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp, 8.dp, 16.dp, 16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        val doneToday = list.count { it.checkedToday }
                        item {
                            Column {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("今天", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                                    Spacer(Modifier.weight(1f))
                                    Text(
                                        "$doneToday/${list.size} 已打",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Spacer(Modifier.height(8.dp))
                                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    items(list, key = { "chip" + it.todo.id }) { it ->
                                        TodayChip(item = it, enabled = !busy, onTap = {
                                            if (busy) return@TodayChip
                                            haptics.perform(HapticEngine.Kind.Tap)
                                            // 2026-11-19 用户「早已结束的待办突然出现在今天」：
                                            // 「今天」chip 原来对**每一条**监督待办都给打卡入口，不看
                                            // 今天是不是这条规则的应打日。给一条「每周二 · 至 11/7」
                                            // 的待办在这里打今天的卡，滚期必然判「没有下一期」，行就会被
                                            // 钉在今天（系列之外），此后谁也滚不动它。
                                            // 两种「今天不该打」都拦下，落到详情去补交历史那天：
                                            //  ①系列已结束（repeatEnd < 今天）—— 今天压根不属于这个系列；
                                            //  ②规则重复但今天不是应打日（账本里没有今天）。
                                            // 不重复/无日期的监督待办不受影响（原来怎么点还怎么点）。
                                            val gateTodayKey = AppTime.dayKey(AppTime.today())
                                            val seriesEnded = it.todo.repeatEnd in 1 until gateTodayKey && !it.todo.permanent
                                            val offPhase = it.todo.repeatType != "none" &&
                                                it.ledger.none { d -> d.date == gateTodayKey }
                                            if (it.supervision && (seriesEnded || offPhase)) {
                                                detailId = it.todo.id
                                                return@TodayChip
                                            }
                                            busy = true
                                            scope.launch {
                                                try {
                                                    val today = AppTime.today()
                                                    if (it.supervision) {
                                                        if (!it.checkedToday) {
                                                            // 与计划页胶囊同一条链：滚期 + 流水 + 监督卡
                                                            Graph.plan.todoById(it.todo.id)?.let { fresh ->
                                                                Graph.plan.completeTodo(fresh, today)
                                                            }
                                                            if (it.todo.dataRecordEnabled &&
                                                                CheckinKind.needsInput(it.todo.dataRecordKind)
                                                            ) {
                                                                capture = PendingCheckin(
                                                                    it.todo,
                                                                    CheckinFormat.blank(it.todo, AppTime.dayKey(today), AppTime.nowMillis())
                                                                )
                                                            }
                                                        } else {
                                                            dayEdit = it.todo to today
                                                        }
                                                    } else {
                                                        val todayKey = AppTime.dayKey(today)
                                                        val hasToday = it.records.any { r -> r.date == todayKey }
                                                        if (hasToday) dayEdit = it.todo to today
                                                        else capture = PendingCheckin(
                                                            it.todo, CheckinFormat.blank(it.todo, todayKey, AppTime.nowMillis())
                                                        )
                                                    }
                                                } finally {
                                                    busy = false
                                                }
                                            }
                                        })
                                    }
                                }
                            }
                        }
                        items(list, key = { it.todo.id }) { it ->
                            BoardRow(item = it, onClick = {
                                haptics.perform(HapticEngine.Kind.Select)
                                detailId = it.todo.id
                            })
                        }
                        item { Spacer(Modifier.height(navAvoidPadding().calculateBottomPadding())) }
                    }
                }
            }
        } else {
            // ---------- 详情 ----------
            CheckinDetail(item = detail, onBack = {
                // 顶栏 ← 与系统返回同口径：带目标进来时直接退出
                if (detailIsRoot) onDone() else detailId = null
            }, onPickDay = { day ->
                haptics.perform(HapticEngine.Kind.Tap)
                dayEdit = detail.todo to day
            })
        }
    }

    capture?.let { p ->
        CheckinCaptureDialog(
            todo = p.todo,
            draft = p.draft,
            onDismiss = { capture = null },
            onSave = { rec ->
                scope.launch { Graph.plan.addCheckin(rec) }
                capture = null
            }
        )
    }
    dayEdit?.let { (t, d) ->
        // 预填已有记录（与计划页补交同法：先异步取，再进弹窗）
        val existing = remember(t.id, d) { mutableStateOf<CheckinRecord?>(null) }
        var loaded by remember(t.id, d) { mutableStateOf(false) }
        LaunchedEffect(t.id, d) {
            existing.value = Graph.plan.checkinOn(t.id, d)
            loaded = true
        }
        if (loaded) {
            BackfillCheckinDialog(
                todo = t,
                day = d,
                existing = existing.value,
                onDismiss = { dayEdit = null },
                onSave = { rec ->
                    scope.launch {
                        if (rec == null) {
                            // 2026-11-14：撤销统一走 undoCheckinDay（原 removeCheckin+toggleCheck
                            // 会把「撤销」变成加卡，且不撤 done/完成流水——时间轴仍暗着）
                            Graph.plan.undoCheckinDay(t.id, d)
                        } else if (t.supervisionEnabled) {
                            Graph.plan.backfillCheck(t.id, d, rec)
                        } else {
                            Graph.plan.addCheckin(rec)
                        }
                    }
                    dayEdit = null
                }
            )
        }
    }
}

/** 「今天」chip：未打=描边（可点直接打），已打=实心（点开改/撤那天） */
@Composable
private fun TodayChip(item: CheckinBoardItem, enabled: Boolean, onTap: () -> Unit) {
    FilterChip(
        selected = item.checkedToday,
        enabled = enabled,
        onClick = onTap,
        // v57 批 2c（B 类）：**今天打卡成功/撤销** chip 抖一下——打卡是全 app 语义最强的
        // 状态翻转（一眼要看见"打上了"）。只挂 checkedToday，不挂 enabled（busy 变化满屏
        // chip 一起动，那是加载态不该抖）。撤销也抖：语义对称（同笔记置顶）。
        modifier = Modifier.wobbleOn(trigger = item.checkedToday, scaleTo = 1.08f),
        colors = filterChipColors(),
        leadingIcon = if (item.checkedToday) {
            { Icon(Icons.Rounded.Check, contentDescription = null, Modifier.width(16.dp)) }
        } else null,
        label = {
            Text(
                item.todo.title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.width(88.dp)
            )
        }
    )
}

/** 面板行：标题 + 连击/完成率 + 30 天密度条 */
@Composable
private fun BoardRow(item: CheckinBoardItem, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.fillMaxWidth().padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    item.todo.title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    if (item.supervision) "连续 ${item.streak} 天"
                    else "${item.records.size} 条记录",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    "${item.rate}%",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Spacer(Modifier.height(8.dp))
            DensityStrip(item.ledger)
        }
    }
}

/** 最近 30 天密度条：一格一天，按时=主色 · 补交=第三色 · 未完成=错误容器 · 今天未打=描边灰 */
@Composable
private fun DensityStrip(days: List<CheckinDay>) {
    val byDate = remember(days) { days.associateBy { it.date } }
    Row(Modifier.fillMaxWidth().height(16.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        repeat(30) { i ->
            val key = AppTime.dayKey(AppTime.today().minusDays((29 - i).toLong()))
            val d = byDate[key]
            val color = when (d?.state) {
                CheckinDay.DONE -> MaterialTheme.colorScheme.primary
                CheckinDay.LATE -> MaterialTheme.colorScheme.tertiary
                CheckinDay.MISSED -> MaterialTheme.colorScheme.errorContainer
                CheckinDay.PENDING -> MaterialTheme.colorScheme.surfaceVariant
                else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
            }
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(2.dp))
                    .background(color)
            )
        }
    }
}

// ==================== 详情 ====================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CheckinDetail(item: CheckinBoardItem, onBack: () -> Unit, onPickDay: (LocalDate) -> Unit) {
    var month by remember { mutableStateOf(YearMonth.now()) }
    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { HapticBackButton(onBack) },
                title = {
                    Text(item.todo.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                }
            )
        }
    ) { pad ->
        LazyColumn(
            Modifier.fillMaxSize().padding(pad),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp, 8.dp, 16.dp, 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Card(
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
                ) {
                    Column(Modifier.fillMaxWidth().padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { month = month.minusMonths(1) }, modifier = Modifier.width(36.dp)) {
                                Icon(Icons.AutoMirrored.Rounded.KeyboardArrowLeft, contentDescription = "上个月")
                            }
                            Text(
                                "%d年%d月".format(month.year, month.monthValue),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(onClick = { month = month.plusMonths(1) }, modifier = Modifier.width(36.dp)) {
                                Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = "下个月")
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                        MonthGrid(item = item, month = month, onPickDay = onPickDay)
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            LegendDot(MaterialTheme.colorScheme.primary, "按时")
                            LegendDot(MaterialTheme.colorScheme.tertiary, "补交")
                            LegendDot(MaterialTheme.colorScheme.errorContainer, "未完成")
                            LegendDot(MaterialTheme.colorScheme.surfaceVariant, "今天")
                        }
                    }
                }
            }
            item {
                // 账目：监督待办给连击/账目；纯记录给条数与起始
                if (item.supervision) {
                    Column {
                        // v54.5（用户 2026-11-07「两行数据重复」）：月历的颜色+图例已经把
                        // 按时/补交/未完成画出来了，小字再抄一遍纯属重复——只留主指标胶囊。
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            StatPill("连续 ${item.streak} 天")
                            StatPill("最佳 ${item.best}")
                            StatPill("完成率 ${item.rate}%")
                        }
                    }
                } else {
                    val first = item.records.lastOrNull()?.date ?: 0
                    Text(
                        if (first > 0) "共 ${item.records.size} 条记录 · 自 ${first.toLocalDate().formatMonthDay()} 起"
                        else "共 ${item.records.size} 条记录",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (item.series != null) {
                item {
                    Card(
                        shape = RoundedCornerShape(18.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
                    ) {
                        Column(Modifier.fillMaxWidth().padding(14.dp)) {
                            CheckinTrendBlock(
                                title = item.todo.dataRecordLabel.ifBlank { "趋势" },
                                label = "",
                                kind = item.series.kind,
                                unit = item.series.unit,
                                points = item.series.points
                            )
                        }
                    }
                }
            }
            // 多选型：标签频次（文本型的语义在记录原文里，不硬造统计）
            if (item.records.isNotEmpty() && item.records.first().kind == CheckinKind.TAGS) {
                item {
                    TagFrequencyCard(item.records)
                }
            }
            item {
                Text(
                    "全部记录 · ${item.records.size} 条（点一条可改/撤）",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
            }
            if (item.records.isEmpty()) {
                item {
                    Text(
                        "还没有数据记录。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            items(item.records, key = { it.id }) { r ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .clickable { onPickDay(r.date.toLocalDate()) }
                        .padding(horizontal = 6.dp, vertical = 7.dp)
                ) {
                    Text(
                        CheckinFormat.dayLabel(r.date, AppTime.zone),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.width(46.dp)
                    )
                    Text(
                        CheckinFormat.value(r, AppTime.zone),
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    if (r.kind != CheckinKind.TIME) {
                        Text(
                            CheckinFormat.time(r.at, AppTime.zone),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            item { Spacer(Modifier.height(navAvoidPadding().calculateBottomPadding())) }
        }
    }
}

@Composable
private fun LegendDot(color: androidx.compose.ui.graphics.Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(10.dp).height(10.dp).clip(RoundedCornerShape(2.dp)).background(color))
        Spacer(Modifier.width(4.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun StatPill(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f))
            .padding(horizontal = 8.dp, vertical = 4.dp)
    )
}

/**
 * 月历热力：有账本的天可点（补交/改那天），无账本的天只是日期数字。
 *
 * 2026-11-07 用户「日期框要跟日历界面一个样式」：几何与排版整体对齐
 * `ui/home/CalendarView.kt` 的 MonthGrid —— 42dp 方格、SpaceEvenly 行、
 * bodyMedium 数字、pressScale + Select 触感、圆形底；这里唯一的不同是
 * **圆底颜色承载打卡状态**（按时=主色 / 补交=第三色 / 未完成=错误容器 / 今天=主色容器），
 * 相当于把日历里「完成度环」的位置换成状态色。
 */
@Composable
private fun MonthGrid(item: CheckinBoardItem, month: YearMonth, onPickDay: (LocalDate) -> Unit) {
    val haptics = LocalHaptics.current
    val byDate = remember(item.ledger) { item.ledger.associateBy { it.date } }
    val today = AppTime.today()
    val first = month.atDay(1)
    // 与 CalendarView 同口径：lead = 周一开头时首日前面要空几格
    val lead = (first.dayOfWeek.value + 6) % 7
    val days = month.lengthOfMonth()
    val rows = (lead + days + 6) / 7
    Column(Modifier.fillMaxWidth()) {
        // 表头格子与日期格同为 42dp，星期字与日期列严格对齐（CalendarView v13 同法）
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            listOf("一", "二", "三", "四", "五", "六", "日").forEach {
                Box(Modifier.size(42.dp), contentAlignment = Alignment.Center) {
                    Text(it, style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        repeat(rows) { r ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                repeat(7) { c ->
                    val idx = r * 7 + c - lead
                    if (idx in 0 until days) {
                        val day = month.atDay(idx + 1)
                        val d = byDate[AppTime.dayKey(day)]
                        val isToday = day == today
                        val (cellBg, numColor) = when (d?.state) {
                            CheckinDay.DONE -> MaterialTheme.colorScheme.primary to MaterialTheme.colorScheme.onPrimary
                            CheckinDay.LATE -> MaterialTheme.colorScheme.tertiary to MaterialTheme.colorScheme.onTertiary
                            CheckinDay.MISSED -> MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
                            CheckinDay.PENDING -> MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
                            else -> if (isToday) MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
                            else androidx.compose.ui.graphics.Color.Transparent to MaterialTheme.colorScheme.onSurface
                        }
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(42.dp)
                                .pressScale()
                                .clip(CircleShape)
                                .background(cellBg)
                                .clickable {
                                    haptics.perform(HapticEngine.Kind.Select)
                                    onPickDay(day)
                                }
                        ) {
                            Text(
                                "${idx + 1}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = numColor,
                                fontWeight = if (isToday) FontWeight.SemiBold else FontWeight.Normal
                            )
                        }
                    } else {
                        Spacer(Modifier.size(42.dp))
                    }
                }
            }
        }
    }
}

/** 多选型打卡的标签频次（全部记录里每个标签出现的天数） */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TagFrequencyCard(records: List<CheckinRecord>) {
    val counts = remember(records) {
        val m = LinkedHashMap<String, Int>()
        records.forEach { r ->
            r.tags.split(',').map { it.trim() }.filter { it.isNotEmpty() }.forEach { t ->
                m[t] = (m[t] ?: 0) + 1
            }
        }
        m.entries.sortedByDescending { it.value }.take(12)
    }
    if (counts.isEmpty()) return
    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    ) {
        Column(Modifier.fillMaxWidth().padding(14.dp)) {
            Text("标签频次", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                counts.forEach { (tag, n) ->
                    Text(
                        "$tag ×$n",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f))
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }
        }
    }
}
