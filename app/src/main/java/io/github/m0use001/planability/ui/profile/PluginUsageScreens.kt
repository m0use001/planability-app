package io.github.m0use001.planability.ui.profile

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material.icons.rounded.Event
import androidx.compose.material.icons.rounded.FactCheck
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Sensors
import androidx.compose.material.icons.rounded.SmartToy
import androidx.compose.material.icons.rounded.Topic
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.m0use001.planability.app.PredictiveBackOverlay
import io.github.m0use001.planability.ai.ptl.ToolRegistry
import io.github.m0use001.planability.core.HapticEngine
import io.github.m0use001.planability.core.LocalHaptics
import io.github.m0use001.planability.core.formatMonthDay
import io.github.m0use001.planability.data.Graph
import io.github.m0use001.planability.data.SettingKV
import io.github.m0use001.planability.ui.common.EmptyHint
import kotlinx.coroutines.launch
import io.github.m0use001.planability.ui.theme.ShapeTokens
import org.json.JSONObject
import java.io.File

// ==================== v7 能力与插件（工具域开关 + 能力包） ====================

private data class DomainMeta(val key: String, val name: String, val desc: String, val icon: androidx.compose.ui.graphics.vector.ImageVector)

private val DOMAIN_META = listOf(
    DomainMeta("schedule", "日程域", "查询/创建/修改日程与例外日", Icons.Rounded.Schedule),
    DomainMeta("todo", "待办域", "查询/创建/修改/删除待办、重复规则、打卡数据、分类", Icons.Rounded.FactCheck),
    DomainMeta("note", "笔记域", "RAG 全库检索与笔记读写、笔记本管理", Icons.Rounded.Topic),
    DomainMeta("focus", "专注域", "补记与查询专注会话", Icons.Rounded.Timer),
    DomainMeta("info", "信息域", "读屏采集/提纯查询/待确认查询", Icons.Rounded.Sensors),
    DomainMeta("memory", "记忆域", "长期记忆检索/新增/遗忘", Icons.Rounded.Memory),
    DomainMeta("file", "文件基础域", "列出与读取应用内文本文件", Icons.Rounded.Folder)
)

private val PLUGIN_META = listOf(
    Triple("office", "办公三件套包", "读取 docx/xlsx/pptx/pdf，导出 Word/Excel/CSV 文件"),
    Triple("web", "网页阅读包", "抓取用户给出的网页链接并提取正文给模型阅读"),
    Triple("ics", "日历交换包", "把日程导出为 .ics 文件，可导入系统日历或分享"),
    Triple("phone", "手机操控包（Phone Use）", "模型读屏、点按、滑动、打开应用、填字——像 computer use 一样操作手机；操作类动作按权限模式弹确认卡")
)

@Composable
fun PluginMarketScreen(
    onDone: () -> Unit,
    mode: Int = 0,
    /** 合并屏（ui/info/ToolsHub.kt）的顶部切换器；null=本屏自己顶栏 */
    topSwitcher: (@Composable () -> Unit)? = null,
    /** 合并屏已在外层套 PredictiveBackOverlay → 本屏只画内容（防双 Dialog 闪帧） */
    insideOverlay: Boolean = false,
    /** v77：板内钻入瓦片——透明底 + 板内页头 + 0 inset + 强制单板（无框套框） */
    embedded: Boolean = false
) {
    // v12：mode 0=完整（能力+插件）/ 1=仅能力域 / 2=仅插件包
    var domains by remember { mutableStateOf<Set<String>>(ToolRegistry.DOMAINS.keys) }
    var plugins by remember { mutableStateOf<Set<String>>(ToolRegistry.PLUGINS.keys) }
    val haptics = LocalHaptics.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    LaunchedEffect(androidx.compose.ui.platform.LocalContext.current) {
        domains = ToolRegistry.enabledDomains() ?: ToolRegistry.DOMAINS.keys
        plugins = ToolRegistry.enabledPlugins() ?: ToolRegistry.PLUGINS.keys
    }

    fun saveDomains(next: Set<String>) {
        domains = next
        scope.launch {
            Graph.db.settingsDao().put(SettingKV("tool.domains", next.joinToString(",")))
        }
    }

    fun savePlugins(next: Set<String>) {
        plugins = next
        scope.launch {
            Graph.db.settingsDao().put(SettingKV("plugin.enabled", next.joinToString(",")))
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    val shell: @Composable () -> Unit = {
        Scaffold(
            containerColor = if (embedded) androidx.compose.ui.graphics.Color.Transparent
            else MaterialTheme.colorScheme.background,
            contentWindowInsets = if (embedded)
                androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0)
            else androidx.compose.material3.ScaffoldDefaults.contentWindowInsets,
            topBar = {
                Column {
                    if (embedded) {
                        io.github.m0use001.planability.ui.common.BoardSubHeader(
                            title = when (mode) { 1 -> "能力设置"; 2 -> "插件设置"; else -> "能力与插件" },
                            onBack = onDone
                        )
                    } else {
                    TopAppBar(
                        title = { Text(when (mode) { 1 -> "能力设置"; 2 -> "插件设置"; else -> "能力与插件" }, fontWeight = FontWeight.Bold) },
                        navigationIcon = {
                            // 顶栏返回 = 中性点击 → Tap
                            val overlayClose = io.github.m0use001.planability.app.LocalOverlayClose.current
                            IconButton(onClick = { haptics.perform(HapticEngine.Kind.Tap); overlayClose() }) {
                                Icon(Icons.AutoMirrored.Rounded.ArrowBack, "返回")
                            }
                        }
                    )
                    }
                    if (topSwitcher != null) {
                        Column(Modifier.fillMaxWidth()) { topSwitcher() }
                    }
                }
            }
        ) { pad ->
            // 2026-11-20 v65 两板推广：宽屏完整模式（mode 0）拆成**两板**——
            // 左板=基础能力（工具域）清单（行多，吃弹性宽），右板=插件能力包（定宽 380dp）。
            // 原来是整窗一条 16dp 内缩的单列——1600dp 宽的窗口里右侧全是死区。
            // mode 1/2 只有一组开关，保持单列。
            val wideBoards = !embedded && io.github.m0use001.planability.core.rememberWindowSizeClass().isWide && mode == 0
            if (wideBoards) {
                io.github.m0use001.planability.ui.common.SplitBoards(
                    twoPane = true,
                    // 必须把 Scaffold 的 pad（顶栏+系统栏）垫在壳的 modifier 上——
                    // 垫在板内容上=板从窗口顶画起，右板首行被顶栏盖住（实拍抓到）
                    modifier = Modifier.padding(pad),
                    detailWidth = 380.dp,
                    // Scaffold 的 pad 已含系统栏与顶栏，壳再垫一遍就是双倍
                    applyTopInset = false,
                    applyBottomInset = false,
                    primary = {
                        Column(
                            Modifier
                                .fillMaxSize()
                                .verticalScroll(newScroll())
                                .padding(top = 8.dp, bottom = 16.dp)
                        ) {
                            Text(
                                "基础能力（工具域）", style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                            )
                            Text(
                                "关闭的域不再注入模型（模型将看不到该域工具）。「系统域」（时间/撤销/深度规划/智能体）常开。",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 16.dp)
                            )
                            Spacer(Modifier.height(6.dp))
                            DOMAIN_META.forEach { d ->
                                CapabilityRow(
                                    icon = d.icon, name = d.name, desc = d.desc,
                                    checked = d.key in domains, flat = true,
                                    onToggle = { on -> saveDomains(if (on) domains + d.key else domains - d.key) }
                                )
                            }
                        }
                    },
                    secondary = {
                        Column(
                            Modifier
                                .fillMaxSize()
                                .verticalScroll(newScroll())
                                .padding(top = 8.dp, bottom = 16.dp)
                        ) {
                            Text(
                                "插件能力包", style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                            )
                            PLUGIN_META.forEach { (key, name, desc) ->
                                CapabilityRow(
                                    icon = when (key) {
                                        "office" -> Icons.Rounded.Info
                                        "web" -> Icons.Rounded.Public
                                        "phone" -> Icons.Rounded.PhoneAndroid
                                        else -> Icons.Rounded.Event
                                    },
                                    name = name, desc = desc,
                                    checked = key in plugins, flat = true,
                                    onToggle = { on -> savePlugins(if (on) plugins + key else plugins - key) }
                                )
                            }
                            Text(
                                "说明：插件包即装即用，卸载后模型不再收到对应工具；已产生的文件与数据不受影响。",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                            )
                        }
                    }
                )
            } else
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(pad)
                    .verticalScroll(newScroll())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (mode != 2) {
                    Text(
                        "基础能力（工具域）", style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        "关闭的域不再注入模型（模型将看不到该域工具）。「系统域」（时间/撤销/深度规划/智能体）常开。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    DOMAIN_META.forEach { d ->
                        CapabilityRow(
                            icon = d.icon, name = d.name, desc = d.desc,
                            checked = d.key in domains,
                            onToggle = { on -> saveDomains(if (on) domains + d.key else domains - d.key) }
                        )
                    }
                }
                if (mode != 1) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "插件能力包", style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold
                    )
                    PLUGIN_META.forEach { (key, name, desc) ->
                        CapabilityRow(
                            icon = when (key) {
                                "office" -> Icons.Rounded.Info
                                "web" -> Icons.Rounded.Public
                                "phone" -> Icons.Rounded.PhoneAndroid
                                else -> Icons.Rounded.Event
                            },
                            name = name, desc = desc,
                            checked = key in plugins,
                            onToggle = { on -> savePlugins(if (on) plugins + key else plugins - key) }
                        )
                    }
                    Text(
                        "说明：插件包即装即用，卸载后模型不再收到对应工具；已产生的文件与数据不受影响。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.height(20.dp))
            }
        }
    }
    if (insideOverlay) shell() else PredictiveBackOverlay(onBack = onDone) { shell() }
}

@Composable
private fun newScroll() = androidx.compose.foundation.rememberScrollState()

@Composable
private fun CapabilityRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    name: String,
    desc: String,
    checked: Boolean,
    onToggle: (Boolean) -> Unit,
    /**
     * 2026-11-20 v65 两板推广：板内**扁平行**形态——不套浮起 Card（板本身已是
     * 圆角矩形 + 容器底，板里再嵌卡 = 第二种材质）。窄屏单列保持 Card 逐像素不变。
     */
    flat: Boolean = false
) {
    val haptics = LocalHaptics.current
    val body: @Composable () -> Unit = {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(34.dp)
                    .clip(RoundedCornerShape(10.dp))
                    // R11：能力图标是装饰性入口，不占主色面 → 中性底 + 中性图标
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            ) {
                Icon(icon, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                Text(
                    desc, style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2, overflow = TextOverflow.Ellipsis
                )
            }
            Switch(checked = checked, onCheckedChange = {
                // 开关 = 选择 → Select（能力域/插件包共用本行，一处覆盖所有开关）
                haptics.perform(HapticEngine.Kind.Select)
                onToggle(it)
            })
        }
    }
    if (flat) body() else {
        Card(
            shape = ShapeTokens.card,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
        ) { body() }
    }
}

// ==================== v7 使用统计（token 用量 + 工具调用） ====================

private data class UsageRow(val ts: Long, val session: Long, val tokensIn: Int, val tokensOut: Int)

@Composable
fun UsageStatsScreen(onDone: () -> Unit, deferBack: Boolean = false) {
    val haptics = LocalHaptics.current
    var usage by remember { mutableStateOf<List<UsageRow>>(emptyList()) }
    var toolCalls by remember { mutableStateOf<Int>(0) }
    var sessionTitles by remember { mutableStateOf<Map<Long, String>>(emptyMap()) }

    LaunchedEffect(Unit) {
        val f = File(Graph.appContext.filesDir, "ai_usage/usage.jsonl")
        val rows = mutableListOf<UsageRow>()
        if (f.exists()) {
            f.readLines().forEach { line ->
                runCatching {
                    val o = JSONObject(line)
                    rows += UsageRow(
                        ts = o.optLong("ts"), session = o.optLong("session"),
                        tokensIn = o.optInt("tokensIn"), tokensOut = o.optInt("tokensOut")
                    )
                }
            }
        }
        usage = rows
        toolCalls = Graph.db.toolAuditDao().count()
        sessionTitles = rows.groupingBy { it.session }.eachCount().keys.associateWith { sid ->
            Graph.chat.session(sid)?.title ?: "会话 #$sid"
        }
    }

    val totalIn = usage.sumOf { it.tokensIn }
    val totalOut = usage.sumOf { it.tokensOut }
    val dayKey = { ts: Long ->
        java.time.Instant.ofEpochMilli(ts).atZone(java.time.ZoneId.systemDefault()).toLocalDate()
    }
    val dailyMap = usage.groupBy { dayKey(it.ts) }
        .mapValues { (_, rows) -> rows.sumOf { it.tokensIn + it.tokensOut }.toFloat() }
    // 近 7 天 = 含今天在内的 7 个自然日（v19.6 修：原来 takeLast(7) 取的是「最近 7 个有记录的日子」，
    // 断档的日子被悄悄跳过，柱状图与横轴标签都骗人）
    val today = io.github.m0use001.planability.core.AppTime.today()
    val last7 = (6 downTo 0).map { back ->
        val d = today.minusDays(back.toLong())
        d to (dailyMap[d] ?: 0f)
    }
    val topSessions = usage.groupBy { it.session }
        .map { (sid, rows) -> Triple(sid, rows.sumOf { it.tokensIn + it.tokensOut }, rows.size) }
        .sortedByDescending { it.second }
        .take(5)

    @OptIn(ExperimentalMaterial3Api::class)
    PredictiveBackOverlay(onBack = onDone, deferBack = deferBack) {
        Scaffold(
            containerColor = if (deferBack) androidx.compose.ui.graphics.Color.Transparent else androidx.compose.material3.MaterialTheme.colorScheme.background,
            topBar = {
                if (deferBack) io.github.m0use001.planability.ui.common.BoardSubHeader("使用统计", onDone) else
                TopAppBar(
                    title = { Text("使用统计", fontWeight = FontWeight.Bold) },
                    navigationIcon = {
                        // 顶栏返回 = 中性点击 → Tap
                        val overlayClose = io.github.m0use001.planability.app.LocalOverlayClose.current
                        IconButton(onClick = { haptics.perform(HapticEngine.Kind.Tap); overlayClose() }) {
                            Icon(Icons.AutoMirrored.Rounded.ArrowBack, "返回")
                        }
                    }
                )
            }
        ) { pad ->
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(pad)
                    .verticalScroll(newScroll())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Card(shape = ShapeTokens.card, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                    Column(Modifier.padding(16.dp)) {
                        Text("AI 用量合计", style = MaterialTheme.typography.labelMedium)
                        Text(
                            "${totalIn + totalOut} tokens",
                            style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold
                        )
                        Text(
                            "输入 $totalIn · 输出 $totalOut · 共 ${usage.size} 次生成 · 工具调用 $toolCalls 次",
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }

                Text("近 7 天", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                if (usage.isEmpty()) EmptyHint("还没有用量记录；对话与工具调用后自动记账")
                else {
                    val peak = last7.maxByOrNull { it.second }
                    if (peak != null && peak.second > 0f) {
                        Text(
                            "峰值 ${peak.first.monthValue}/${peak.first.dayOfMonth} · ${fmtTok(peak.second.toLong())}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    SevenDayBars(last7)
                }

                Text("会话 Top5", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                if (topSessions.isEmpty()) EmptyHint("暂无数据")
                topSessions.forEach { (sid, tokens, calls) ->
                    Card(shape = ShapeTokens.card, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                        Row(Modifier.padding(horizontal = 12.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(sessionTitles[sid] ?: "会话 #$sid", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text("$calls 次生成", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Text("$tokens tok", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
                Spacer(Modifier.height(20.dp))
            }
        }
    }
}

/** token 数格式化：过万折成「x.x 万」 */
private fun fmtTok(v: Long): String =
    if (v >= 10000) String.format("%.1f 万", v / 10000.0) else v.toString()

/**
 * 近 7 天柱状图（7 个自然日）。
 * v19.6 修：①「近 7 天」原来取的是最近 7 个*有记录*的日子，断档日被跳过；②柱高按整行高度算，
 * 峰值那根柱子会把日期标签挤出/盖住——现在柱区固定 92dp、柱高按比例 fillMaxHeight，标签永不重叠。
 */
@Composable
private fun SevenDayBars(data: List<Pair<java.time.LocalDate, Float>>) {
    val maxV = data.maxOfOrNull { it.second }?.coerceAtLeast(1f) ?: 1f
    val today = io.github.m0use001.planability.core.AppTime.today()
    androidx.compose.foundation.layout.Row(
        Modifier
            .fillMaxWidth()
            .height(134.dp)
            .clip(ShapeTokens.card)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        data.forEach { (d, v) ->
            val frac = (v / maxV).coerceIn(0f, 1f)
            val isToday = d == today
            androidx.compose.foundation.layout.Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.weight(1f)
            ) {
                Box(
                    Modifier.fillMaxWidth().height(92.dp),
                    contentAlignment = Alignment.BottomCenter
                ) {
                    if (v > 0f) {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .fillMaxHeight(frac)
                                .clip(RoundedCornerShape(6.dp))
                                .background(MaterialTheme.colorScheme.primary)
                        )
                    } else {
                        // 零用量日：留 3dp 浅色底桩，保证 7 根柱子与横轴一一对应
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(3.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.25f))
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    "${d.monthValue}/${d.dayOfMonth}",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isToday) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = if (isToday) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1
                )
            }
        }
    }
}
