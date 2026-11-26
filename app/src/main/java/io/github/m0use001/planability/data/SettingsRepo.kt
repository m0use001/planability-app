package io.github.m0use001.planability.data

import android.graphics.Color
import io.github.m0use001.planability.core.SecretBox
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

// 云 OCR 模式取值（2026-11-14 用户：「AI 读文件改成优先走云端」——针对在用的 DeepSeek 系列模型）。
// 三档而不是布尔：兜底（旧语义）与优先（新默认）是两种不同工作流，用户要能切回去。
const val CLOUD_OCR_OFF = "off"
const val CLOUD_OCR_FALLBACK = "fallback"
const val CLOUD_OCR_PREFER = "prefer"

/** 主题配置（持久化到 settings 表） */
data class ThemeConfig(
    /** 0 跟随系统 / 1 浅色 / 2 深色 */
    val mode: Int = 0,
    /** 配色来源：0=拼色（壁纸颜色 hint 生成 4 方案） / 1=自选种子色。
     *  默认 0（C2-A 终态；38466586 过渡期曾为 1）：装上即与系统观感一致。
     *  theme.seed 原值永不改写——「其他颜色」tab 选回原种子即恢复旧观感。 */
    val colorSource: Int = 0,
    /** 拼色方案序号 0..3（主色/辅色/点缀色/互补色），仅 colorSource=0 时生效 */
    val wallpaperVariant: Int = 0,
    /** 种子色 ARGB */
    val seed: Int = Color.rgb(88, 110, 245),
    val fontScale: Float = 1.0f,
    /** default / google_sans_flex / noto_sc /
     *  wen_yuan_rounded / yuan_975 / rhr（后三个只接管中文，拉丁/符号走 Google Sans Flex） */
    val fontFamily: String = "default",
    /** 全局字重偏移（在每款字体的常规字重上叠加）300–800 */
    val fontWeight: Int = 0,
    /** 触感强度 0–150%（100% = 系统默认手感） */
    val hapticIntensity: Int = 100,
    /** 代码块配色方案：vscode=完全复刻 VS Code 默认主题 / theme=按 VS Code 色相关系从主题色生成 */
    val codeHlStyle: String = "vscode"
)

/** GLM（智谱 BigModel）默认端点 */
const val GLM_BASE_URL = "https://open.bigmodel.cn/api/paas/v4"

/** MiMo（小米）默认端点：执行者槽，抽摘要/命名等轻任务 */
const val MIMO_BASE_URL = "https://api.xiaomimimo.com/v1"

/** 鉴权风格：bearer（Authorization: Bearer）/ api-key（自定义头）
 *  M5：AUTH_BEARER 已随 AiProvider 实体迁 `:shared`（同包跨模块直解），这里只留 AUTH_API_KEY。 */
const val AUTH_API_KEY = "api-key"

/** GLM 常用模型（label 展示 + 模型 ID） */
val GLM_MODELS = listOf(
    "glm-4-flash" to "GLM-4-Flash · 免费",
    "glm-4.5-flash" to "GLM-4.5-Flash · 免费",
    "glm-4.6" to "GLM-4.6 · 旗舰",
    "glm-5.3-flash" to "GLM-5.3-Flash · 最新"
)

/** AI 模型配置（默认 GLM，兼容任意 OpenAI 兼容端点） */
data class ModelConfig(
    /** glm / mimo / custom */
    val provider: String = "glm",
    val baseUrl: String = GLM_BASE_URL,
    val apiKey: String = "",
    val modelName: String = "glm-4-flash",
    val enabled: Boolean = false,
    /** 槽位：main（对话/工具）/ worker（摘要/命名等轻任务） */
    val slot: String = "main",
    /** 鉴权头风格：bearer / api-key（MiMo 用 api-key） */
    val authStyle: String = AUTH_BEARER,
    /** API 协议：openai（/chat/completions）/ anthropic（/messages，支持流式与工具调用） */
    val apiFormat: String = "openai"
)

/**
 * v19.10：模型上下文窗口估算（此前 UI 硬编码 32k，导致 mimo v2.6 flash（1M）显示 46% 实际只占约 1.5%）。
 * 2026-10-28 全量核对（用户「把各个模型上下文数量查清」）：
 * - DeepSeek 官方 pricing 页：deepseek-flash / deepseek-v4-pro 均 **1M 上下文 / 最大输出 384k**；
 *   旧版/第三方托管的 deepseek（r1 等）= 128k，单列一档；
 * - OpenAI：gpt-4.1 = 1M、gpt-5 = 400k、o3 = 200k（原表把三者并为 1M，gpt-5/o3 虚高）；
 * - Claude = 200k；GLM-4-Flash = 128k；MiMo v2.6 = 1M（此前已实测核对）。
 * NVIDIA NIM 的模型名来自用户扫描，无法穷举，未命中走 32k 兜底（保守值，宁可高估占用）。
 */
fun contextWindowOf(modelName: String): Int {
    // 2026-11-12 用户实录（切模型上下文 33%→100%）：Routability 的精确模型名（含 auto）
    // 优先走 RoutabilityCatalog.MODEL_CONTEXT（1M 表，单一真源）。原来的字符串 contains
    // 猜测对 "auto" 全不命中 → 掉 32k 兜底 → 切到 auto 的瞬间同一份历史直接爆表。
    // （data 层引 ai.pool 已有先例：AiProviderEntities 装表处同款引用。）
    io.github.m0use001.planability.ai.pool.RoutabilityCatalog.MODEL_CONTEXT[modelName]
        ?.let { return it.toInt() }
    val m = modelName.lowercase()
    return when {
        m.contains("mimo") -> 1_000_000
        m.contains("deepseek-flash") || m.contains("deepseek-v4") -> 1_000_000
        m.contains("deepseek") -> 128_000
        m.contains("gpt-4.1") -> 1_000_000
        m.contains("gpt-5") -> 400_000
        m.contains("o3") -> 200_000
        m.contains("flash") && m.contains("glm") -> 128_000
        m.contains("claude") -> 200_000
        else -> 32_000
    }
}

/** 自定义封面预设（v15：文字 + 排列方式，存 KV JSON 数组） */
data class CustomCover(val id: Long, val text: String, val layout: Int)

/** 执行者槽（worker）默认配置：小米 MiMo */
fun defaultWorkerConfig() = ModelConfig(
    provider = "mimo",
    baseUrl = MIMO_BASE_URL,
    modelName = "mimo-v2.6-flash",
    enabled = false,
    slot = "worker",
    authStyle = AUTH_API_KEY
)

/**
 * 一级页默认顺序。无 "files"（2026-10-24 用户拍板：文件页签撤除，功能并入信息页文件库）；
 * 存量 nav.order/visibleTabs 里的 files 由 NavShell 的 mapNotNull 自然丢弃。
 */
val DEFAULT_TABS = listOf("home", "plan", "focus", "notes", "info", "profile")

data class SettingsSnapshot(
    val theme: ThemeConfig = ThemeConfig(),
    val model: ModelConfig = ModelConfig(),
    /** 执行者槽：摘要/命名/抽取等轻任务（MiMo） */
    val worker: ModelConfig = defaultWorkerConfig(),
    /** 一级页顺序（tab id） */
    val navOrder: List<String> = DEFAULT_TABS,
    /** 隐藏的一级页 */
    val hiddenTabs: Set<String> = emptySet(),
    val searchEdgeEnabled: Boolean = true,
    /**
     * 2026-11-14：联网搜索的主源换成 Tavily（用户拍板：不想远程依赖自家电脑，
     * 也不想再维护端侧爬 Bing/360 的 selector 链）。这里存的是**明文**，
     * 落库走 SecretBox.seal（键 search.tavilyKey），与 provider key 同口径。
     * 空串 = 没配，web_search 直接走原来的端侧兜底链，行为与之前完全一致。
     */
    val searchTavilyKey: String = "",
    /**
     * 2026-11-14：DeepSeek 应用能力云开关（方案 ◆/planability-ai-cloud-plan.md，
     * 拍板：Tavily 优先云兜底；语音不上云）。KV 直存（非密钥，明文布尔/数字），
     * key 复用 DeepSeek 供应商配置不新增录入。默认全关 = 行为与上云前完全一致。
     */
    val cloudSearchEnabled: Boolean = false,
    val cloudFetchEnabled: Boolean = false,
    /**
     * 云 OCR 模式（2026-11-14 用户：「AI 读文件改成优先走云端」，只在 DeepSeek 系列模型下生效）：
     * - [CLOUD_OCR_OFF]：关闭（一次都不上云）；
     * - [CLOUD_OCR_FALLBACK]：兜底（旧语义——端侧 ML Kit 识别不出才上云）；
     * - [CLOUD_OCR_PREFER]（默认）：优先——图片/扫描件/PDF/doc/docx/ppt/pptx 先交 DeepSeek
     *   文件解析（支持这五种文档格式，按页返回文本），云端不可用/失败/超预算才回端侧。
     *   判断与降级全在 [io.github.m0use001.planability.ai.ptl.DeepSeekCloud]。
     */
    val cloudOcrMode: String = CLOUD_OCR_PREFER,
    /** 云 OCR 是否可用（[CLOUD_OCR_OFF] 之外都可用）。旧字段名保留，读侧不用改。 */
    val cloudOcrEnabled: Boolean = true,
    /** 云能力日预算（分）；0 = 云整体禁用 */
    val cloudDailyBudgetFen: Int = 500,
    val clipboardEnabled: Boolean = true,
    val floatBallEnabled: Boolean = false,
    /** 开屏动画：0 关 / 1 淡入浮现 / 2 缩放浮现 / 3 上滑浮现（2026-10-28 用户要的自定义开屏） */
    val launchAnim: Int = 1,
    val zoneOverride: String? = null,
    val focusGoalMin: Int = 120,
    val todoGoal: Int = 5,
    val screenLimitMin: Int = 480,
    val screenPenaltyPer30: Int = 2,
    /** v18.1：睡眠目标时长（分钟）与不足扣分（每 30 分钟） */
    val sleepGoalMin: Int = 420,
    val sleepPenaltyPer30: Int = 2,
    /** 番茄：专注/短休/长休时长（分钟）与长休前轮数 */
    val pomoWorkMin: Int = 25,
    val pomoShortMin: Int = 5,
    val pomoLongMin: Int = 15,
    val pomoRounds: Int = 4,
    /** 正计时模式：专注阶段不设时长，手动结束 */
    val focusCountUp: Boolean = false,
    val accountName: String = "我",
    val lockEnabled: Boolean = false,
    val lockPin: String = "",
    val lockBiometric: Boolean = false,
    val captureFloatEnabled: Boolean = false,
    /** AI 工作台上下文 chips 开关（缺省=true）：schedule/todos/digests/summary */
    val ctxChips: Map<String, Boolean> = emptyMap(),
    // ---------- phone use：AI 可视与可操作区域 ----------
    /** 总开关。false = 不限制（既有行为，AI 能看全屏、能在任意点操作）；true = 下面两块生效 */
    val aiRegionLimited: Boolean = false,
    /** AI 可视区域，归一化 "x,y,w,h"（0..1），空串 = 全屏。作用于 phone_read / phone_screenshot */
    val aiVisibleRegion: String = "",
    /** AI 可操作区域，归一化 "x,y,w,h"（0..1），空串 = 全屏。作用于 ◆ / phone_swipe / ◆ */
    val aiOperableRegion: String = "",
    // ---------- 图表全局默认呈现（单图可在图表右上角三点里覆盖） ----------
    /** 柱顶/点上标数值 */
    val chartShowValues: Boolean = false,
    /** 折线/面积图叠加趋势线 */
    val chartShowTrend: Boolean = false,
    /** 图例（饼图/雷达多系列等） */
    val chartShowLegend: Boolean = true,
    /** 网格线 */
    val chartShowGrid: Boolean = true,
    /** X 轴类目名 */
    val chartShowLabels: Boolean = true,
    /** 调色板序号 */
    val chartPalette: Int = 0,
    /** 图表高度 dp，0 = 用类型默认高度 */
    val chartHeightDp: Int = 0,
    // ---------- 计划页着色（v28） ----------
    /** false（默认）= 行首色条取第一个自由标签的颜色、无标签=白色；
     *  true = 按紧急性+重要性合成（色相随重要性 220°→0°、饱和度随紧急性 0.35→0.95） */
    val planColorByUrgencyImportance: Boolean = false,
    /** 首页时间轴是否在日程/待办块里显示备注（默认关：备注往往很长，块里放不下） */
    val timelineShowNote: Boolean = false
) {
    val visibleTabs: List<String> get() = navOrder.filter { it !in hiddenTabs }
}

class SettingsRepo(private val dao: SettingsDao) {

    private val _snapshot = MutableStateFlow(SettingsSnapshot())
    val snapshot: StateFlow<SettingsSnapshot> = _snapshot

    private suspend fun putRaw(key: String, value: String) {
        dao.put(SettingKV(key, value))
    }

    // ---------- 旁路小配置的通用 KV 入口（phone-b 新增，纯追加） ----------
    // 给「标签颜色的饱和度/明度」这类**不属于 SettingsSnapshot 正式字段**的小配置用。
    // 好处：不用动 label_colors 表结构、不用写 Room 迁移；缺键就返回 null，
    // 调用方自己退回默认值，老数据行为不变。

    /** 读一个原始设置项；没有这个键就返回 null。 */
    suspend fun raw(key: String): String? = dao.get(key)

    /** 一次读出全部原始设置项（前缀筛选用，避免 N 次查询）。 */
    suspend fun rawAll(): Map<String, String> = dao.all().associate { it.key to it.value }

    /** 写一个原始设置项。 */
    suspend fun putRawValue(key: String, value: String) = putRaw(key, value)

    /** 删掉一个原始设置项（恢复默认用）。 */
    suspend fun removeRaw(key: String) = dao.delete(key)

    suspend fun load() {
        val all = dao.all().associate { it.key to it.value }
        // 云 OCR 模式：新键优先；旧布尔键兼容映射（"1"=兜底、显式 "0"=关）；都没写 = 优先。
        // 两字段从同一个值派生，避免「模式=优先但 enabled=false」这种自相矛盾快照。
        val ocrMode = all["cloud.ocrMode"]
            ?.takeIf { it == CLOUD_OCR_OFF || it == CLOUD_OCR_FALLBACK || it == CLOUD_OCR_PREFER }
            ?: when (all["cloud.ocr"]) {
                "1" -> CLOUD_OCR_FALLBACK
                "0" -> CLOUD_OCR_OFF // 旧版显式关过：尊重；要优先在设置里切
                else -> CLOUD_OCR_PREFER
            }
        val s = SettingsSnapshot(
            theme = ThemeConfig(
                mode = all["theme.mode"]?.toIntOrNull() ?: 0,
                colorSource = all["theme.colorSource"]?.toIntOrNull() ?: 0,
                wallpaperVariant = all["theme.wallpaperVariant"]?.toIntOrNull() ?: 0,
                seed = all["theme.seed"]?.toIntOrNull() ?: Color.rgb(88, 110, 245),
                fontScale = all["theme.fontScale"]?.toFloatOrNull() ?: 1.0f,
                fontFamily = all["theme.fontFamily"] ?: "default",
                fontWeight = all["theme.fontWeight"]?.toIntOrNull() ?: 0,
                hapticIntensity = all["theme.hapticIntensity"]?.toIntOrNull() ?: 100,
                codeHlStyle = all["theme.codeHlStyle"] ?: "vscode"
            ),
            model = ModelConfig(
                provider = all["model.provider"] ?: "glm",
                baseUrl = all["model.baseUrl"] ?: GLM_BASE_URL,
                // v44：库里的 key 是密文（SecretBox），对外仍是明文
                apiKey = SecretBox.open(all["model.key"] ?: ""),
                modelName = all["model.name"] ?: "glm-4-flash",
                enabled = all["model.enabled"] == "1",
                slot = "main",
                authStyle = all["model.authStyle"] ?: AUTH_BEARER
            ),
            worker = ModelConfig(
                provider = all["modelw.provider"] ?: "mimo",
                baseUrl = all["modelw.baseUrl"] ?: MIMO_BASE_URL,
                apiKey = SecretBox.open(all["modelw.key"] ?: ""),
                modelName = all["modelw.name"] ?: "mimo-v2.6-flash",
                enabled = all["modelw.enabled"] == "1",
                slot = "worker",
                authStyle = all["modelw.authStyle"] ?: AUTH_API_KEY
            ),
            navOrder = all["nav.order"]?.let { parseStringList(it) } ?: DEFAULT_TABS,
            hiddenTabs = all["nav.hidden"]?.let { parseStringList(it).toSet() } ?: emptySet(),
            searchEdgeEnabled = all["toggle.searchEdge"] != "0",
            searchTavilyKey = SecretBox.open(all["search.tavilyKey"] ?: ""),
            // 2026-11-14 云能力开关（DeepSeek 应用 API）
            cloudSearchEnabled = all["cloud.search"] == "1",
            cloudFetchEnabled = all["cloud.fetch"] == "1",
            // OCR 模式（上面的 ocrMode 派生值，两字段同源）
            cloudOcrMode = ocrMode,
            cloudOcrEnabled = ocrMode != CLOUD_OCR_OFF,
            cloudDailyBudgetFen = all["cloud.dailyBudget"]?.toIntOrNull() ?: 500,
            clipboardEnabled = all["toggle.clipboard"] != "0",
            floatBallEnabled = all["toggle.floatBall"] == "1",
            launchAnim = all["launch.anim"]?.toIntOrNull() ?: 1,
            zoneOverride = all["time.zone"],
            focusGoalMin = all["goal.focusMin"]?.toIntOrNull() ?: 120,
            todoGoal = all["goal.todos"]?.toIntOrNull() ?: 5,
            screenLimitMin = all["goal.screenLimit"]?.toIntOrNull() ?: 480,
            screenPenaltyPer30 = all["goal.screenPenalty"]?.toIntOrNull() ?: 2,
            sleepGoalMin = all["goal.sleepMin"]?.toIntOrNull() ?: 420,
            sleepPenaltyPer30 = all["goal.sleepPenalty"]?.toIntOrNull() ?: 2,
            pomoWorkMin = all["pomo.workMin"]?.toIntOrNull() ?: 25,
            pomoShortMin = all["pomo.shortMin"]?.toIntOrNull() ?: 5,
            pomoLongMin = all["pomo.longMin"]?.toIntOrNull() ?: 15,
            pomoRounds = all["pomo.rounds"]?.toIntOrNull() ?: 4,
            focusCountUp = all["toggle.focusCountUp"] == "1",
            chartShowValues = all["chart.values"] == "1",
            chartShowTrend = all["chart.trend"] == "1",
            chartShowLegend = all["chart.legend"] != "0",
            chartShowGrid = all["chart.grid"] != "0",
            chartShowLabels = all["chart.labels"] != "0",
            chartPalette = all["chart.palette"]?.toIntOrNull() ?: 0,
            chartHeightDp = all["chart.height"]?.toIntOrNull() ?: 0,
            planColorByUrgencyImportance = all["plan.colorByUrgency"] == "1",
            timelineShowNote = all["timeline.showNote"] == "1",
            accountName = all["account.name"] ?: "我",
            lockEnabled = all["lock.enabled"] == "1",
            lockPin = all["lock.pin"] ?: "",
            lockBiometric = all["lock.biometric"] == "1",
            captureFloatEnabled = all["capture.float"] == "1",
            ctxChips = all["ctx.chips"]?.let { parseChipMap(it) } ?: emptyMap(),
            aiRegionLimited = all["ai.region.limited"] == "1",
            aiVisibleRegion = all["ai.region.visible"] ?: "",
            aiOperableRegion = all["ai.region.operable"] ?: ""
        )
        _snapshot.value = s
        loaded = true
    }

    /**
     * [load] 是否至少完成过一次。
     *
     * 启动器图标要在「当前配色」上做决定，而 Application.onCreate 里的 load() 是异步的：
     * 开机广播 / 巡检可能先跑到，此时 snapshot 还是默认值（拼色 0 经典绿）。
     * 有这面旗就能判断「该不该先补读一次」，而不是把默认配色当成用户的选择写进桌面图标。
     */
    @Volatile
    var loaded: Boolean = false
        private set

    fun update(transform: (SettingsSnapshot) -> SettingsSnapshot) {
        val old = _snapshot.value
        val new = transform(old)
        _snapshot.value = new
        Graph.appScope.launch {
            persistDiff(old, new)
        }
    }

    private suspend fun persistDiff(old: SettingsSnapshot, new: SettingsSnapshot) {
        if (old.theme != new.theme) {
            putRaw("theme.mode", new.theme.mode.toString())
            putRaw("theme.colorSource", new.theme.colorSource.toString())
            putRaw("theme.wallpaperVariant", new.theme.wallpaperVariant.toString())
            putRaw("theme.seed", new.theme.seed.toString())
            putRaw("theme.fontScale", new.theme.fontScale.toString())
            putRaw("theme.fontFamily", new.theme.fontFamily)
            putRaw("theme.fontWeight", new.theme.fontWeight.toString())
            putRaw("theme.hapticIntensity", new.theme.hapticIntensity.toString())
            putRaw("theme.codeHlStyle", new.theme.codeHlStyle)
        }
        if (old.model != new.model) {
            putRaw("model.provider", new.model.provider)
            putRaw("model.baseUrl", new.model.baseUrl)
            putRaw("model.key", SecretBox.seal(new.model.apiKey))
            putRaw("model.name", new.model.modelName)
            putRaw("model.enabled", if (new.model.enabled) "1" else "0")
            putRaw("model.authStyle", new.model.authStyle)
        }
        if (old.worker != new.worker) {
            putRaw("modelw.provider", new.worker.provider)
            putRaw("modelw.baseUrl", new.worker.baseUrl)
            putRaw("modelw.key", SecretBox.seal(new.worker.apiKey))
            putRaw("modelw.name", new.worker.modelName)
            putRaw("modelw.enabled", if (new.worker.enabled) "1" else "0")
            putRaw("modelw.authStyle", new.worker.authStyle)
        }
        if (old.navOrder != new.navOrder) putRaw("nav.order", jsonStringList(new.navOrder))
        if (old.hiddenTabs != new.hiddenTabs) putRaw("nav.hidden", jsonStringList(new.hiddenTabs.toList()))
        if (old.searchEdgeEnabled != new.searchEdgeEnabled) putRaw("toggle.searchEdge", if (new.searchEdgeEnabled) "1" else "0")
        // 2026-11-14：Tavily key 与 provider key 同口径——库里存 Seal 后的密文（v44 起备份
        // 加密依赖这一点：明文 key 进 .enc 备份等于把 key 一起拷走）。
        if (old.searchTavilyKey != new.searchTavilyKey)
            putRaw("search.tavilyKey", SecretBox.seal(new.searchTavilyKey))
        if (old.cloudSearchEnabled != new.cloudSearchEnabled)
            putRaw("cloud.search", if (new.cloudSearchEnabled) "1" else "0")
        if (old.cloudFetchEnabled != new.cloudFetchEnabled)
            putRaw("cloud.fetch", if (new.cloudFetchEnabled) "1" else "0")
        if (old.cloudOcrMode != new.cloudOcrMode) {
            putRaw("cloud.ocrMode", new.cloudOcrMode)
            // 旧键镜像：老版本/别处只认布尔的读侧不至于把「优先」看成关
            putRaw("cloud.ocr", if (new.cloudOcrMode != CLOUD_OCR_OFF) "1" else "0")
        } else if (old.cloudOcrEnabled != new.cloudOcrEnabled) {
            // 只改布尔的旧调用方：映射成 兜底/关闭（不猜「优先」——那是显式选择）
            val mode = if (new.cloudOcrEnabled) CLOUD_OCR_FALLBACK else CLOUD_OCR_OFF
            putRaw("cloud.ocrMode", mode)
            putRaw("cloud.ocr", if (new.cloudOcrEnabled) "1" else "0")
        }
        if (old.cloudDailyBudgetFen != new.cloudDailyBudgetFen)
            putRaw("cloud.dailyBudget", new.cloudDailyBudgetFen.toString())
        if (old.clipboardEnabled != new.clipboardEnabled) putRaw("toggle.clipboard", if (new.clipboardEnabled) "1" else "0")
        if (old.floatBallEnabled != new.floatBallEnabled) putRaw("toggle.floatBall", if (new.floatBallEnabled) "1" else "0")
        if (old.launchAnim != new.launchAnim) putRaw("launch.anim", new.launchAnim.toString())
        if (old.zoneOverride != new.zoneOverride) putRaw("time.zone", new.zoneOverride ?: "")
        if (old.focusGoalMin != new.focusGoalMin) putRaw("goal.focusMin", new.focusGoalMin.toString())
        if (old.todoGoal != new.todoGoal) putRaw("goal.todos", new.todoGoal.toString())
        if (old.screenLimitMin != new.screenLimitMin) putRaw("goal.screenLimit", new.screenLimitMin.toString())
        if (old.screenPenaltyPer30 != new.screenPenaltyPer30) putRaw("goal.screenPenalty", new.screenPenaltyPer30.toString())
        if (old.sleepGoalMin != new.sleepGoalMin) putRaw("goal.sleepMin", new.sleepGoalMin.toString())
        if (old.sleepPenaltyPer30 != new.sleepPenaltyPer30) putRaw("goal.sleepPenalty", new.sleepPenaltyPer30.toString())
        if (old.pomoWorkMin != new.pomoWorkMin) putRaw("pomo.workMin", new.pomoWorkMin.toString())
        if (old.pomoShortMin != new.pomoShortMin) putRaw("pomo.shortMin", new.pomoShortMin.toString())
        if (old.pomoLongMin != new.pomoLongMin) putRaw("pomo.longMin", new.pomoLongMin.toString())
        if (old.pomoRounds != new.pomoRounds) putRaw("pomo.rounds", new.pomoRounds.toString())
        if (old.focusCountUp != new.focusCountUp) putRaw("toggle.focusCountUp", if (new.focusCountUp) "1" else "0")
        if (old.chartShowValues != new.chartShowValues) putRaw("chart.values", if (new.chartShowValues) "1" else "0")
        if (old.chartShowTrend != new.chartShowTrend) putRaw("chart.trend", if (new.chartShowTrend) "1" else "0")
        if (old.chartShowLegend != new.chartShowLegend) putRaw("chart.legend", if (new.chartShowLegend) "1" else "0")
        if (old.chartShowGrid != new.chartShowGrid) putRaw("chart.grid", if (new.chartShowGrid) "1" else "0")
        if (old.chartShowLabels != new.chartShowLabels) putRaw("chart.labels", if (new.chartShowLabels) "1" else "0")
        if (old.chartPalette != new.chartPalette) putRaw("chart.palette", new.chartPalette.toString())
        if (old.chartHeightDp != new.chartHeightDp) putRaw("chart.height", new.chartHeightDp.toString())
        if (old.planColorByUrgencyImportance != new.planColorByUrgencyImportance) {
            putRaw("plan.colorByUrgency", if (new.planColorByUrgencyImportance) "1" else "0")
        }
        if (old.timelineShowNote != new.timelineShowNote) {
            putRaw("timeline.showNote", if (new.timelineShowNote) "1" else "0")
        }
        if (old.accountName != new.accountName) putRaw("account.name", new.accountName)
        if (old.lockEnabled != new.lockEnabled) putRaw("lock.enabled", if (new.lockEnabled) "1" else "0")
        if (old.lockPin != new.lockPin) putRaw("lock.pin", new.lockPin)
        if (old.lockBiometric != new.lockBiometric) putRaw("lock.biometric", if (new.lockBiometric) "1" else "0")
        if (old.captureFloatEnabled != new.captureFloatEnabled) putRaw("capture.float", if (new.captureFloatEnabled) "1" else "0")
        if (old.ctxChips != new.ctxChips) putRaw("ctx.chips", jsonStringChipMap(new.ctxChips))
        if (old.aiRegionLimited != new.aiRegionLimited) putRaw("ai.region.limited", if (new.aiRegionLimited) "1" else "0")
        if (old.aiVisibleRegion != new.aiVisibleRegion) putRaw("ai.region.visible", new.aiVisibleRegion)
        if (old.aiOperableRegion != new.aiOperableRegion) putRaw("ai.region.operable", new.aiOperableRegion)
    }

    /** 手动记录的屏幕使用时间（分钟），key: st:<dayKey> */
    suspend fun getManualScreenTime(dayKey: Long): Int =
        dao.get("st:$dayKey")?.toIntOrNull() ?: 0

    suspend fun setManualScreenTime(dayKey: Long, minutes: Int) {
        putRaw("st:$dayKey", minutes.toString())
    }

    /**
     * PTL：confirm 级"总是允许"的工具名单（M4）。
     *
     * 2026-10-29：改为**按会话**存（key `ptl.always:<sessionId>`）。原先是一条全局 KV，
     * 在任一对话里点过"总是允许"之后，所有会话都不再弹确认 —— 包括切到「计划模式」的会话，
     * 于是"只读档不改动任何数据"能被历史放行绕过去。
     */
    suspend fun ptlAlways(sessionId: Long): Set<String> =
        runCatching { dao.get("ptl.always:$sessionId")?.let { parseStringList(it).toSet() } }
            .getOrNull() ?: emptySet()

    suspend fun setPtlAlways(sessionId: Long, names: Set<String>) {
        putRaw("ptl.always:$sessionId", jsonStringList(names.toList()))
    }

    /** PTL：持久化撤销栈（每个元素是一条 undo 指令 JSON；跨进程存活） */
    suspend fun ptlUndo(): List<String> =
        runCatching { dao.get("ptl.undo")?.let { parseStringList(it) } }.getOrNull() ?: emptyList()

    suspend fun setPtlUndo(entries: List<String>) {
        putRaw("ptl.undo", jsonStringList(entries))
    }

    /** 文件页默认打开路径（空 = 根目录） */
    suspend fun filesDefaultPath(): String = runCatching { dao.get("files.defaultPath") ?: "" }.getOrNull() ?: ""

    suspend fun setFilesDefaultPath(path: String) {
        putRaw("files.defaultPath", path)
    }

    // ---------- 群聊采集/反检测设置（v16 批） ----------

    /** 手势随机化总开关 */
    suspend fun captureRandomize(): Boolean =
        runCatching { dao.get("capture.randomize") != "0" }.getOrNull() ?: true

    suspend fun setCaptureRandomize(on: Boolean) {
        putRaw("capture.randomize", if (on) "1" else "0")
    }

    /** 随机档位 1-3（幅度/时长/停顿递增） */
    suspend fun captureLevel(): Int =
        runCatching { dao.get("capture.level")?.toIntOrNull()?.coerceIn(1, 3) }.getOrNull() ?: 2

    suspend fun setCaptureLevel(level: Int) {
        putRaw("capture.level", level.coerceIn(1, 3).toString())
    }

    /**
     * 把某张图当前的呈现选项存为**全局默认**（Office 的"改模板"层）。
     *
     * 单图菜单里改的只是这张图；这里写进 KV 后，之后新出现的图表会用它当初值。
     */
    suspend fun saveChartDefaults(o: io.github.m0use001.planability.ui.notes.ChartOptions) {
        putRaw("chart.values", if (o.showValues) "1" else "0")
        putRaw("chart.trend", if (o.showTrendLine) "1" else "0")
        putRaw("chart.legend", if (o.showLegend) "1" else "0")
        putRaw("chart.grid", if (o.showGrid) "1" else "0")
        putRaw("chart.labels", if (o.showDataLabels) "1" else "0")
        putRaw("chart.palette", o.paletteIdx.toString())
        putRaw("chart.height", o.heightDp.toString())
        load()   // 立即生效，否则要等下次进页面
    }

    /** 单群采集屏数 3-12 */
    suspend fun captureSteps(): Int =
        runCatching { dao.get("capture.steps")?.toIntOrNull()?.coerceIn(3, 12) }.getOrNull() ?: 6

    suspend fun setCaptureSteps(steps: Int) {
        putRaw("capture.steps", steps.coerceIn(3, 12).toString())
    }

    /** 群聊全量采集标记：某群从未全量采过（滚到聊天记录顶部）则为 false，一键总结时首次=全量 */
    suspend fun captureFullDone(group: String): Boolean =
        runCatching { dao.get("capture.full:$group") == "1" }.getOrNull() ?: false

    suspend fun setCaptureFullDone(group: String) {
        putRaw("capture.full:$group", "1")
    }

    /**
     * 重置单群全量采集标记（2026-11-08 审计补口）：下次一键总结该群重新从最新滚到顶补历史。
     * 与「清空全部提纯记录」不同——**不删任何已采原文/提纯结果**，只是重新翻一遍历史；
     * mergeCapture 按行级去重合并，重采不会产生重复内容，只会补上以前漏掉的更早消息。
     */
    suspend fun clearCaptureFull(group: String) {
        runCatching { dao.delete("capture.full:$group") }
    }

    /** 通用 KV（信息提取会话映射等） */
    suspend fun kvGet(key: String): String? = runCatching { dao.get(key) }.getOrNull()

    suspend fun kvPut(key: String, value: String) {
        putRaw(key, value)
    }

    suspend fun kvDelete(key: String) {
        runCatching { dao.delete(key) }
    }

    /** AI 提纯开关（采集后自动总结）：默认关闭，测试期用 */
    suspend fun extractPurifyEnabled(): Boolean =
        runCatching { dao.get("extract.purify") == "1" }.getOrNull() ?: false

    suspend fun setExtractPurifyEnabled(on: Boolean) {
        putRaw("extract.purify", if (on) "1" else "0")
    }

    /** 提纯用哪个模型：false=端侧 worker（免费、上下文小），true=主模型（远程、按量计费）。
     *  2026-11-05：端侧 QAIRT/Qwen3-4B 上下文仅 4096 且历史会累计，长记录提纯容易反复失败。 */
    suspend fun extractPurifyUseMain(): Boolean =
        runCatching { dao.get("extract.purify.model") == "main" }.getOrNull() ?: false

    suspend fun setExtractPurifyUseMain(main: Boolean) {
        putRaw("extract.purify.model", if (main) "main" else "local")
    }

    /** 自定义封面预设库（v15） */
    suspend fun customCovers(): List<CustomCover> = runCatching {
        val arr = JSONArray(dao.get("custom.covers") ?: "[]")
        (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            CustomCover(o.optLong("id"), o.optString("text"), o.optInt("layout"))
        }
    }.getOrNull() ?: emptyList()

    suspend fun setCustomCovers(list: List<CustomCover>) {
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject().put("id", it.id).put("text", it.text).put("layout", it.layout)) }
        putRaw("custom.covers", arr.toString())
    }

    companion object {
        fun parseStringList(json: String): List<String> = runCatching {
            val arr = JSONArray(json)
            (0 until arr.length()).map { arr.getString(it) }
        }.getOrNull() ?: emptyList()

        fun jsonStringList(list: List<String>): String {
            val arr = JSONArray()
            list.forEach { arr.put(it) }
            return arr.toString()
        }

        fun parseChipMap(json: String): Map<String, Boolean> = runCatching {
            val obj = JSONObject(json)
            val names = obj.names() ?: JSONArray()
            (0 until names.length()).associate { i ->
                val k = names.getString(i)
                k to obj.getBoolean(k)
            }
        }.getOrNull() ?: emptyMap()

        fun jsonStringChipMap(map: Map<String, Boolean>): String {
            val obj = JSONObject()
            map.forEach { (k, v) -> obj.put(k, v) }
            return obj.toString()
        }
    }
}
