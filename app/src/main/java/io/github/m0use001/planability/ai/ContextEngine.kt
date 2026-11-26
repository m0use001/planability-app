package io.github.m0use001.planability.ai

import io.github.m0use001.planability.core.AppTime
import io.github.m0use001.planability.core.toLocalDate
import io.github.m0use001.planability.data.CheckinFormat
import io.github.m0use001.planability.data.Graph
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** 上下文清单中的一项（token 可审计） */
data class CtxItem(
    val type: String,
    val label: String,
    val tokens: Int,
    val enabled: Boolean,
    /** 该项注入的内容（enabled=false 时为空串） */
    val content: String
)

/** 组装结果：payload 消息 + 清单 */
data class ContextPlan(
    val items: List<CtxItem>,
    val totalTokens: Int,
    /** 最终发给模型的消息 */
    val messages: List<ChatApiMessage>,
    /**
     * **压缩前**整个有效历史的条数（`effectiveTranscript` 原样长度，摘要游标之前、capHistory 之前）。
     *
     * 上线校验的分母必须是它，不能是"发出去的条数"、也不能是"摘要锚点之后的条数"：
     * 压缩就是推进摘要锚点丢掉**前缀**，这段条数除了这里没有别的来源
     * （history label 只记 capHistory 的截断）。用错的话，压缩成功时分子分母相等
     * → 每轮都误报"压缩没生效"（phone-b 2026-10-19 真机抓到的误报）。
     */
    val preCompactCount: Int = 0
)

/**
 * M2 上下文引擎：
 * - 个人上下文 chips（日程/待办/提纯/历史摘要）按开关注入 system，带来源标注
 * - PromptGuard 清洗外部采集文本（防注入/零宽字符）
 * - token 估算（CJK≈1/字，其余≈4 字符/token）
 * - token manifest 落盘（每会话一份，可审计）
 * - usage 账本（jsonl 追加，成本可见）
 */
object ContextEngine {

    /**
     * 画像注入的使用纪律（2026-11-11 线代会话 288 事故后新增）。
     *
     * 画像正文（PersonaBuilder 生成）只有「是什么」，没有「怎么用」——裸注入时模型会：
     * ① 把兴趣图谱「在学某语言」泛化成把任何话题都落到代码上演示（讲线代附整段 Python）；
     * ② 把画像条目当成对话里聊过的事实（不标依据就引用）；
     * ③ 把「交互协议」某一条外推成全局风格（「代码讲解」偏好被套到数学讲解上）。
     * 三条硬规则分别堵这三个口子。放在稳定段（persona 正文之前），不破前缀缓存。
     */
    const val PERSONA_USAGE_RULES: String =
        "（使用纪律：画像是长期背景，不是本轮请求。① 学科讲解保持学科语言——" +
            "数学等非编程学科的解答用定义、式子、推导本身，不主动附代码示例；" +
            "「正在学某语言」不构成把当前话题转成代码演示的理由，只有用户本轮明确要代码" +
            "或问题本身就是编程问题时才写代码。② 画像条目不是本轮对话里聊过的事实，" +
            "不要说成「我们聊过/你说过」；因画像做出的个性化选择（举例、语言、深度）" +
            "要一句话点明依据，如「你画像里记着…」。③ 画像里「交互协议」各条按其字面主题适用" +
            "（如「代码讲解」只在用户问代码时生效），不要把某一条泛化成所有话题的全局风格。）"

    // ---------- token 估算 ----------

    fun estTokens(text: String): Int {
        if (text.isEmpty()) return 0
        // v40（成本优化 2.2，Agora CountMemo 的简化版，ref-cost-study §1.3）：
        // assemble 每轮对全历史逐条估算，绝大多数文本与上一轮完全相同 → 按文本做 key 命中即精确。
        // 只记 ≥128 字的条目（短的直接算比建 key 便宜）；存的是引用不复制内容；并发读写用 CHM。
        if (text.length >= 128) {
            estMemo[text]?.let { return it }
        }
        var cjk = 0
        var other = 0
        for (ch in text) {
            if (ch.code >= 0x2E80) cjk++ else other++
        }
        val n = (cjk + other / 3.5).toInt().coerceAtLeast(1)
        if (text.length >= 128) {
            if (estMemo.size >= EST_MEMO_MAX) estMemo.clear()   // 粗暴但正确：全清比真 LRU 便宜，竞态最坏多算一次
            estMemo[text] = n
        }
        return n
    }

    private val estMemo = java.util.concurrent.ConcurrentHashMap<String, Int>()
    private const val EST_MEMO_MAX = 4096

    // ---------- PromptGuard：外部文本进上下文前清洗 ----------

    private val zeroWidth = Regex("[\\u200B-\\u200F\\u202A-\\u202E\\u2060\\uFEFF\\u2066-\\u2069]")

    /** 去零宽字符/控制字符、压平过空行、限长。外部采集文本（群聊/OCR）必须先过这里 */
    fun sanitize(text: String, maxChars: Int = 4000): String {
        val cleaned = zeroWidth.replace(text, "")
            .replace(Regex("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F]"), "")
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()
        return if (cleaned.length > maxChars) cleaned.take(maxChars) + "…（已截断）" else cleaned
    }

    /** 分钟数 → "HH:MM"（睡眠 chip 用） */
    private fun fmtHM(min: Int): String = "%02d:%02d".format((min / 60) % 24, min % 60)

    // ---------- chips 开关 ----------

    // 注意这里**没有** "summary"：摘要不是"可选注入的一块数据"。
    // lastSummarizeIndex 一旦推进，那段历史就只剩摘要这一份表示——
    // 允许关掉它 = 允许静默丢掉上下文（而且在界面上看不出来）。
    val CHIP_TYPES = listOf("schedule", "todos", "sleep", "digests", "memory", "scenes", "checkins")

    fun chipEnabled(type: String): Boolean =
        Graph.settings.snapshot.value.ctxChips[type] != false

    fun setChipEnabled(type: String, enabled: Boolean) {
        Graph.settings.update {
            it.copy(ctxChips = it.ctxChips + (type to enabled))
        }
    }

    // ---------- 组装 ----------

    /**
     * v8 @勾连解析：LinkedRef → 当轮注入的 CtxItem。
     * domain/plugin 类型不产生内容（只在 ChatEngine 里合并进本轮工具集）。
     */
    suspend fun resolveLinked(refs: List<io.github.m0use001.planability.ai.LinkedRef>): List<CtxItem> =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val out = mutableListOf<CtxItem>()
            refs.forEach { r ->
                runCatching {
                    when (r.kind) {
                        "memory", "skill" -> {
                            val m = if (r.id > 0) Graph.memories.byId(r.id)
                            else Graph.memories.all().firstOrNull { it.name == r.label }
                            if (m != null) out += CtxItem(
                                "linked", (if (m.type == "skill") "技能·" else "记忆·") + m.name,
                                estTokens(m.body), true, "【" + (if (m.type == "skill") "技能" else "记忆") + "·" + m.name + "】" + m.body
                            )
                        }
                        "schedule" -> {
                            val s = Graph.plan.scheduleById(r.id)
                            if (s != null) {
                                val txt = "【日程·" + s.title + "】" + AppTime.millisToTime(s.start) + "-" +
                                    AppTime.millisToTime(s.end) + (if (s.place.isNotBlank()) " 地点:" + s.place else "")
                                out += CtxItem("linked", "日程·" + s.title, estTokens(txt), true, txt)
                            }
                        }
                        "todo" -> {
                            val t = Graph.plan.todoById(r.id)
                            if (t != null) {
                                val txt = "【待办·" + t.title + "】" + (if (t.priority == 0) "（高优先）" else "")
                                out += CtxItem("linked", "待办·" + t.title, estTokens(txt), true, txt)
                            }
                        }
                        "note" -> {
                            val n = Graph.notes.noteById(r.id)
                            if (n != null) {
                                val body = n.content.take(1500)
                                val txt = "【笔记·" + n.title + "】\n" + body +
                                    (if (n.content.length > 1500) "…（已截断，全文用 note_read 分段读）" else "")
                                out += CtxItem("linked", "笔记·" + n.title, estTokens(txt), true, txt)
                            }
                        }
                        "assistant" -> {
                            val a = Graph.assistants.byId(r.id)
                            if (a != null) {
                                val body = a.systemPrompt.take(800)
                                val txt = "【智能体·" + a.name + "】" + a.slogan + "\n" + body +
                                    (if (a.systemPrompt.length > 800) "…（已截断）" else "")
                                out += CtxItem("linked", "智能体·" + a.name, estTokens(txt), true, txt)
                            }
                        }
                    }
                }
            }
            out.distinctBy { it.label }
        }

    /**
     * 组装本轮请求：system = 人设 + 时间 + chips（日程/待办/提纯/历史摘要）；
     * messages = system + transcript（clearBefore 游标 + 摘要锚点之后的增量）。
     */
    suspend fun assemble(
        sessionId: Long,
        session: io.github.m0use001.planability.data.ChatSession,
        toolsEnabled: Boolean = false,
        /** v8 @勾连：用户显式勾连的内容项，本轮强制注入 */
        linked: List<CtxItem> = emptyList(),
        /** v40（成本优化 2.1）：本轮工具 schema 的序列化文本，进上下文账本（原来完全不在估算里） */
        toolsJson: String = ""
    ): ContextPlan {
        val chips = mutableListOf<CtxItem>()

        // v45 P3：L3 画像（稳定段，放易变尾部之前）——每轮读一次 DB，字符串很小
        val personaText = runCatching { io.github.m0use001.planability.data.MemoryLayers.persona() }
            .getOrDefault("").takeIf { it.isNotBlank() }

        // 日程（今天 + 明天）
        runCatching {
            val today = AppTime.today()
            val todayKey = AppTime.dayKey(today)
            val occ = Graph.plan.scheduleOccurrences(today, today.plusDays(1))
            if (occ.isNotEmpty()) {
                val lines = occ.take(24).map { o ->
                    val day = if (AppTime.dayKeyOf(o.start) == todayKey) "今天" else "明天"
                    "$day ${AppTime.millisToTime(o.start)}-${AppTime.millisToTime(o.end)} ${o.schedule.title}" +
                        (if (o.schedule.place.isNotBlank()) " @${o.schedule.place}" else "")
                }
                chips += chipItem("schedule", "日程（今天+明天）", lines.joinToString("\n"))
            }
        }
        // 待办（未完成，按日期近排序）
        runCatching {
            val todos = Graph.plan.activeTodos()
                .filter { it.date > 0 }
                .sortedBy { it.date }
            if (todos.isNotEmpty()) {
                val lines = todos.take(20).map { t ->
                    val d = t.date.toLocalDate().let { "${it.monthValue}/${it.dayOfMonth}" }
                    "$d ${t.title}" + if (t.priority == 0) "（高优先）" else ""
                }
                chips += chipItem("todos", "待办（未完成）", lines.joinToString("\n"))
            }
        }
        // v22：打卡数据记录 —— 用户在待办里记的每次打卡载荷（早餐时间、体重、次数…）。
        // 必须默认可见：这是用户主动记下来、期望 AI 知道的东西，不该藏在工具里等 AI 想起来去查。
        runCatching {
            val pairs = Graph.plan.checkinPairs(limitPerTodo = 10)
            if (pairs.isNotEmpty()) {
                val lines = pairs.take(6).mapNotNull { (t, rs) ->
                    CheckinFormat.summary(t, rs, AppTime.zone).ifBlank { null }
                }
                if (lines.isNotEmpty()) {
                    chips += chipItem("checkins", "打卡记录", lines.joinToString("\n"))
                }
            }
        }
        // 今日提纯（外部采集信息，过 PromptGuard）
        runCatching {
            val digests = Graph.info.digestsOf(AppTime.dayKey(AppTime.today()))
                .filter { it.digest.isNotBlank() }
            if (digests.isNotEmpty()) {
                val body = digests.take(6).joinToString("\n\n") { d ->
                    "【${d.sourceGroup}】\n" + sanitize(d.digest, 600)
                }
                chips += chipItem("digests", "今日信息提纯", body)
            }
        }
        // v53 助理中枢：私人助理（key=hub）的会话注入待处理清单——含编号，供 pending_commit/pending_ignore 引用。
        // 只对 hub 会话注入，普通项目会话的行为不变。
        // v54：v54 起普通会话的 todo_create/schedule_create 也产草稿（统一审核），中枢是全量审核入口；
        // chip 带分类统计与「已建议」标记，最多列 30 条、其余提示用 pending_query 翻（支持过滤）。
        runCatching {
            val isHub = session.assistantId > 0 &&
                Graph.assistants.byId(session.assistantId)?.key == io.github.m0use001.planability.data.AssistantRepo.KEY_HUB
            if (isHub) {
                val pend = Graph.info.pendingItems()
                if (pend.isNotEmpty()) {
                    val projNames = runCatching { Graph.projects.all().associate { it.id to it.name } }
                        .getOrDefault(emptyMap())
                    val todoN = pend.count { it.kind != "schedule" }
                    val srcN = pend.groupingBy { it.source }.eachCount()
                    val head = "共 " + pend.size + " 条待审：待办 $todoN / 日程 " + (pend.size - todoN) +
                        "；会话产生 " + (srcN["session"] ?: 0) + "、信息提取 " + (srcN["capture"] ?: 0) +
                        "、手动 " + (srcN["manual"] ?: 0)
                    val body = head + "\n" + pend.take(30).joinToString("\n") { p ->
                        val kindCn = if (p.kind == "schedule") "日程" else "待办"
                        val srcCn = when (p.source) {
                            "session" -> "会话"
                            "manual" -> "手动"
                            "automation" -> "自动化"   // 2026-11-12：流水线 todo_add/schedule_add 落草稿
                            else -> "提取"
                        }
                        val proj = if (p.source == "session" && p.originProjectId > 0)
                            "·" + (projNames[p.originProjectId] ?: "项目" + p.originProjectId) else ""
                        val time = p.timeText.ifBlank { "" }
                        val sug = if (p.suggestedStart > 0)
                            " 已建议 ${AppTime.millisToTime(p.suggestedStart)}-${AppTime.millisToTime(p.suggestedEnd)}" else ""
                        "#${p.id} [$kindCn·$srcCn$proj] ${p.title}" +
                            (if (time.isNotBlank()) " @$time" else "") + sug +
                            (if (p.place.isNotBlank()) " @${p.place}" else "")
                    } + if (pend.size > 30)
                        "\n（只列最近 30 条；翻更早的用 pending_query，支持 kind/source/limit 过滤）" else ""
                    chips += CtxItem(
                        type = "pending", label = "待处理清单（不可关闭）",
                        tokens = estTokens(body), enabled = true, content = body
                    )
                }
            }
        }
        // 睡眠（v18.1：OPPO 健康采集，最近一次 + 近7天均值）
        runCatching {
            val latest = Graph.sleep.latest()
            val week = Graph.sleep.last(7)
            if (latest != null) {
                val avgMin = if (week.isEmpty()) 0 else week.sumOf { it.durationMin } / week.size
                val body = buildString {
                    append("最近一晚（${latest.dateText.ifBlank { latest.dateEpochDay.toString() }}）：")
                    append(latest.durationText)
                    append("，入睡 ${fmtHM(latest.fallMin)}，醒来 ${fmtHM(latest.wakeMin)}")
                    if (week.size >= 2) {
                        append("\n近${week.size}天平均：${avgMin / 60} 小时 ${avgMin % 60} 分钟")
                        append("，平均入睡 ${fmtHM(week.map { it.fallMin }.average().toInt())}")
                    }
                }
                chips += chipItem("sleep", "睡眠数据", body)
            }
        }
        // 历史摘要（滚动摘要写回）
        if (session.memoryPrompt.isNotBlank()) {
            // 强制启用：见 CHIP_TYPES 上的说明。这里不能走 chipItem()
            // （那条路会把 enabled 设成 chipEnabled("summary")）。
            chips += CtxItem(
                type = "summary",
                label = "此前对话摘要（不可关闭）",
                tokens = estTokens(session.memoryPrompt),
                enabled = true,
                content = session.memoryPrompt
            )
        }
        // 记忆（M2.5）：工作层索引 + 按最近用户消息检索命中
        runCatching {
            val transcriptForRecall = Graph.chat.messagesOf(session.id).filter {
                it.id > session.clearBefore && it.role == "user" && it.content.isNotBlank()
            }
            val recallQuery = transcriptForRecall.takeLast(3).joinToString(" ") { it.content }.take(300)
            // v8 项目记忆：全局记忆 + 本项目根的记忆（子文件夹不持有记忆）
            // 2026-11-26：作用域走 memoryScopeOf（hub=跨项目；普通会话=本项目，别的项目 handoff 不再注入）
            val memBody = Graph.memories.injectionBlock(recallQuery, memoryScopeOf(session.id))
            if (memBody.isNotBlank()) chips += chipItem("memory", "记忆（全局+项目+检索）", memBody)
        }
        // v45 P3：L2 场景索引（只给名字+摘要；具体记忆仍走 L1 召回）
        runCatching {
            val sceneBody = io.github.m0use001.planability.data.MemoryLayers.sceneIndexBlock(session.projectId)
            if (sceneBody.isNotBlank()) chips += chipItem("scenes", "场景索引", sceneBody)
        }
        // v8 @勾连：用户显式勾连的内容（点选产生，恒启用）
        linked.forEach { item ->
            chips += chipItem("linked", item.label, item.content)
        }

        val sb = StringBuilder()
        val now = AppTime.now()
        val week = when (now.dayOfWeek.value) {
            1 -> "周一"; 2 -> "周二"; 3 -> "周三"; 4 -> "周四"; 5 -> "周五"; 6 -> "周六"; else -> "周日"
        }
        // v55 智能体卡：card 为空时回落到旧 systemPrompt（零行为变化）；坏卡不哑会话
        val assistant = runCatching {
            session.assistantId.takeIf { it > 0 }?.let { Graph.assistants.byId(it) }
        }.getOrNull()
        val card = runCatching {
            AgentCard.parse(assistant?.card, assistant?.systemPrompt ?: "")
        }.getOrDefault(AgentCard(persona = assistant?.systemPrompt ?: ""))
            .let { if (it.persona.isBlank()) it.copy(persona = AgentCard.LEGACY_GENERAL_PROMPT) else it }
            // v56：导入卡（酒馆 community cards）里的 {{char}}/{{user}}/<START> 必须展开，
            // 否则模型把占位符当字面文本模仿，回复里直接吐「{{char}}: …」。
            // 用户名复用 accountName（个人页可改，默认「我」）。
            .rendered(
                charName = assistant?.name.orEmpty(),
                userName = runCatching {
                    Graph.settings.snapshot.value.accountName
                }.getOrDefault(io.github.m0use001.planability.ai.card.CardMacros.DEFAULT_USER),
            )
            // 角色卡是**外部不可信内容**（社区下载），与人设同样过 PromptGuard：
            // 清零宽字符与控制符——这些是提示词注入的常见载体，进 system 段尤其危险。
            .let { c ->
                c.copy(
                    persona = sanitize(c.persona, 20000),
                    styleExamples = sanitize(c.styleExamples, 12000),
                    postHistory = sanitize(c.postHistory, 8000),
                    depthText = sanitize(c.depthText, 4000),
                )
            }
        val persona = card.persona
        sb.append(persona)
            // v40（成本优化 1.1，ref-cost-study §7-1.1）：分钟级时间戳从 system 头部挪到
            // **易变尾部**（见下方“易变尾部”注释处）。它原来坐在 persona 后面，
            // 每轮都变 → provider 的前缀缓存（OpenAI 自动缓存 / Anthropic cache_control）
            // 从第一个请求起就全部失效，这是每轮重复支付的最大单点成本。
            .append("用户上下文来自其本地数据，仅供个性化参考，不要编造其中没有的信息。")
        if (card.styleExamples.isNotBlank()) {
            sb.append("\n\n【文风示例】\n").append(card.styleExamples)
        }
        // 工具段 = 全局能力开关 × 卡策略（创作卡关工具循环）
        val toolsEnabledByCard = toolsEnabled && card.scaffoldTools
        if (toolsEnabledByCard) {
            sb.append("\n你可以调用工具直接读写用户的日程/待办/笔记/记忆与查询文件。")
                .append("用户明确要创建/修改/完成时立即调用对应工具；创建类工具会弹出确认卡，由用户批准后生效。")
                // v54 统一审核：创建类工具产出中枢草稿，模型必须交代去向
                .append("注意：todo_create/schedule_create 产出的是**助理中枢草稿**（用户在中枢审核后才进时间轴），工具返回会带草稿编号——要如实向用户说明状态，别说「已创建日程」。")
                // P0-1（2026-11-10 审计两次动态复现）：弱模型把「草稿 #N」当 todos 表 id 调 todo_edit，
                // 把无关待办整条改掉。这里与 ToolDefs 描述双保险钉死 id 来源。
                .append("改/删待办或日程时，id 必须来自 todo_query/schedule_query 返回的「编号 N」（data_history 列出的编号同样可用）——草稿编号不是 id，草稿要用 pending_commit 入库后才有真实 id。")
                // 2026-11-12（◆ 实录）：模型拿 data_history（数主记录条数）答「我的课表」，
                // 得出「没有每周重复课程」的错误结论还向用户断言没有精确查询工具——其实
                // timeline_query 就是（重复日程展开成当天实例）。这里钉死精确查询的口径。
                .append("查「某天/某周/课表/课程安排」这类精确安排必须用 timeline_query（重复日程会展开成当天实例，可传 days 一次铺一周）；data_history 是趋势统计、按主记录计数（每周重复的课只算 1 条），不许用它回答课表或某天安排。")
                // P0-2（2026-11-10 审计动态实录）：模型正文声称改「背诵单词」、实际改的是
                // 「审计-重要紧急」。复述要求把「以工具返回标题为准」变成硬规则。
                .append("改完/删完以后，向用户复述的标题必须以工具返回里写的标题为准（工具返回带「旧 → 新」），不要用对话印象里的标题。")
                .append("调用工具前先用一句话说明你要做什么；工具返回 [OK]/[FAIL]/[DENIED]，[FAIL] 时根据报错调整参数重试（最多两次）。")
                // 2026-11-07 检索触发策略（端侧微调必要性审查后的落地，见
                // ◆/planability-ondevice-finetune-review.md）：kb_ask 原先只靠工具
                // 描述里的"适用场景"引导——模型想用才用。现在升级为硬规则：涉及用户自己的
                // 记录必须先取证再回答，没证据就明说没有，不许凭对话印象编。
                // 位置约束：必须在 toolsEnabled 段内、易变尾部（当前时间）之前——
                // 保住 provider 前缀缓存（ContextCostTest 钉过这段的稳定性）。
                .append("检索策略：用户问到自己的笔记/资料/记录/记忆（「我记过…」「我的笔记里有没有…」「之前说过…」「根据我的情况…」），先用 kb_ask 检索证据再回答，不要只凭对话印象作答；")
                // 2026-11-11 小说会话实录（session 1293）：用户早让 AI 查过崩3剧情并落了盘
                // （◆/#135 + 项目文件/◆三世界观设定与时间线.md），问细节时 kb_ask
                // 只搜笔记 → 模型报「本地笔记里没有」→ 转头去搜网页（用户：「AI 会查错地方」）。
                // kb_ask 现已三库齐扫（笔记+记忆+项目文件），规则同步改口：没命中才是真的没有，
                // 且剧情/设定类问题也必须先走 kb_ask。
                .append("kb_ask 会一并检索**笔记、记忆、项目文件**三处（用户的剧情/设定/世界观等长期项目沉淀常在记忆与项目文件里，不在笔记）；它没命中才是真的本地没有。")
                .append("证据包没覆盖的部分必须明说「笔记里没有相关记录」，不许编造；本地没命中要转外部资料时，先向用户说明「本地记录里没有，我查一下网络资料」。")
                .append("要找整篇笔记用 note_search，要读整篇项目文件用 file_library_read，找长文/模糊语义用 library_search。")
                .append("\n内置智能体（对用户不可见）：planner=日程规划、digest=信息提纯、tutor=教学讲解、writer=写作润色。")
                .append("需要专项视角产出时用 ask_expert 委托，拿到回答后由你整合输出，不要原样转述「智能体说」。")
                // 2026-11-08 网页正文硬规则：小说◆ 里模型搜到 bwiki 章节页后说
                // 「我没有抓取网页的能力」——工具明明在（web_fetch/browser_* 均已实现且成功用过），
                // 一部分是路由把它筛掉了（已修 ToolRouter，见 ToolVisibilityTest），另一部分是
                // 模型把"搜索摘要"当成了能力上限。这条规则把话堵死：有工具就别说没有。
                .append("网页正文：web_search 只给标题与摘要，**不等于读到了原文**；需要正文、章节、表格、完整名单时，用 web_fetch 抓取该链接（返回带「第 X–Y 字」可续读）。")
                .append("web_fetch 内部已经包含**无头渲染**（前端渲染站也能取到正文，不弹窗），所以读网页一律先 web_fetch；")
                .append("[FETCH_EMPTY] 说明真的取不到（登录墙/验证码）——**这时才**用 browser_open 打开同一链接，并且要**明确请用户在这个窗口里登录/验证**，登录后 browser_extract 取正文，取完 browser_close 关窗。")
                .append("**任何情况下都不要声称「我没有抓取网页的能力」**——工具一直都在，先试再说。")
                // 2026-11-08 用户实报「AI 把记忆写到项目记忆文件夹，文件管理器有、记忆库没有」：
                // 那个目录是记忆库的导出镜像，写文件不算记忆。这条把通道说死。
                .append("记忆写入：要长期记住一件事，只能用 memory_add（带 project 参数），它会进记忆库并同步导出到「项目记忆」文件夹；")
                .append("**「项目记忆」文件夹是导出镜像，不要往里写文件**（写了不进记忆库，还会被下次导出清掉）；要存长文档放「项目文件」。")
                // 2026-11-12 ◆ 三轮实锤（用户「我不是早说了之前那个意大利人设作废了吗？？」）：
                // ①AI 只会 memory_add 新条目/作废声明，承载旧说法的条目没人作废 → 照常被召回、
                //   常驻注入、混进摘要，作废设定反复复提；②设定卡.md 一直在项目文件里没人读
                //   （AI 自诊「我压根没打开那份已经存在的文件」，只从记忆库捞零散旧条目）。
                // 记忆卫生两条硬规则，配套工具 memory_invalidate（标失效不删行）：
                .append("记忆卫生（作废/更改设定时）：用户表示某设定、说法或人设**作废、更换、不再算数**时，必须先用 memory_search 找到承载旧说法的记忆条目，用 memory_invalidate 把它们逐条作废（写明原因），再记录新说法；只新增一条「作废声明」而不作废旧条目等于没办——旧设定还会被反复召回。")
                // 2026-11-26 小说会话事故（AI 列出 8 条别的项目的进度条目问「要不要删」，
                // 又在一次「删」之后连续执行 24 次 memory_forget）：
                .append("**记忆作用域与删除边界（硬规则）**：你只能检索、作废、删除**当前会话所属项目**的记忆条目；别的项目的条目即使出现在上下文（常驻/交接）里，也不得列进删除清单、不得建议删除——要清理别的项目，请让用户切到那个项目的会话。")
                .append("删除记忆一次只处理用户**逐条点名**的条目；**不要一次性列出大清单让用户回一个「删」**。若用户确实要批量清理，明确告诉他去「记忆库」里逐条确认，而不是由你连续调用 memory_forget。作废旧设定优先用 memory_invalidate（不删行、可追溯），只有用户明确说「删掉」才用 memory_forget。")
                .append("长期设定类沉淀（设定卡/世界观/时间线/纲要）以项目文件里的权威文件为准：涉及设定的回答先 file_library_list 看有哪些卡、用 file_library_read 读卡再答；卡与记忆冲突时以卡为准，并把冲突的记忆条目作废或更新；写完设定卡或重大改版后，用 memory_add 登记一条指针（文件路径+一句话内容），防止「卡躺在项目文件里、AI 忘了它存在」。")
        }
        // v55 智能体卡：创作卡可关掉组件协议（plan/qa/checklist 是「工作台味」的另一半）
        if (card.scaffoldComponents) {
        sb.append("\n\n【内嵌组件协议】回答适合结构化展示时，输出独立的完整围栏块（语言槽写组件名，正文为合法 JSON，围栏前后各留一个空行）：")
                // v19.11：实测模型把 qa 当成工具名去调用（报「未知工具 qa」），这里明确「写在正文里、不是工具」
                .append("\n注意：以下是【正文里的文本块】，不是工具调用——不要用 plan/qa/schedule/checklist/file 当作工具名发起函数调用。")
            .append("\n```plan {\"title\":\"标题\",\"goal\":\"目标\",\"steps\":[{\"name\":\"步骤名\",\"detail\":\"说明\"}],\"note\":\"备注可空\"}``` —— 多步计划/路线图/排期用（**这就是用户在工作流面板里看到的那份计划**）")
            .append("\n```qa {\"questions\":[{\"q\":\"问题\",\"options\":[\"选项A\",\"选项B\"]}]}``` —— 需要用户在明确选项中做决策时用，最多 3 题、每题 2-4 个选项")
            .append("\n```schedule {\"items\":[{\"title\":\"事件\",\"start\":\"明天 15:00\",\"end\":\"16:00\",\"location\":\"地点可空\"}]}``` —— 提出日程方案供用户一键转创建请求；应用会自动检测与既有日程的时间冲突并提示用户修改")
            .append("\n```checklist {\"title\":\"标题\",\"items\":[\"条目1\",\"条目2\"]}``` —— 可勾选的执行清单，用户可一键转待办")
            .append("\n```file {\"path\":\"exports/hello.md\",\"name\":\"显示名\"}``` —— 向用户【钓出】某个文件（路径必须**逐字复制** file_export/file_list 回执里的路径，限 attachments/ 或 exports/ 相对路径），用户可点开预览")
            // 2026-11-17 用户「怎么打开不了」实报：卡片里的 path 是模型自由文本，
            // 它常自己编一个 exports/xxx.md，而文件其实写在「项目文件/分类/」下，
            // 或者压根没调用导出工具 → 用户点开只得到"文件不存在"。
            .append("\n**没真正调用 file_export/file_list 写出这个文件，就不要输出 file 卡**——用户点了打不开，比不钓更糟。")
            .append("\n围栏必须闭合且 JSON 合法；JSON 字符串值内的英文双引号必须写成 \\\" 或改用「」——实测有模型吐出『要\\\"好看\\\"』这种未转义内层引号，直接导致卡片解析失败。")
            .append("\nplan 的 steps[].name 只写步骤名本身，不要自带「1 」「1. 」等序号——界面会自动编号，自带序号会出现两组数字。")
            // 2026-11-04 用户「AI 调用会话组件失败了」：deepseek-flash 会自作主张改用内部
            // DSML 标记（`<｜DSML｜ qa>…</｜DSML｜>`），用户看到一串生标记、卡片没出来。
            // 明文禁止；渲染侧另有归一化兜底（ChatComponents.normalizeComponentMarkup）。
            .append("\n禁止使用 <｜DSML｜> 这类 XML 标签写法，也不要自造组件标签——组件只能用上面 ``` 围栏的形式输出。")
        }
        // 【回答排版】改成**无条件**下发（2026-10-11）。
        //
        // 这段能力原先挂在 if (toolsEnabled) 里：用户在「AI能力开关」里关掉工具之后，
        // 模型就完全收不到任何 Markdown 格式提示，回答退回纯文本 —— 可「把回答排清楚」
        // 跟有没有工具没有关系。
        //
        // 同时补上**渲染层早就认、却从没教给模型的粗体**：ui/notes 的 inlineMd
        // 一直支持 **粗体** / *斜体* / ~~删除线~~ / 行内码 / 链接，但提示词里只列了
        // 「标题/列表/任务清单/引用/表格/行内码/删除线」——唯独没有粗体，所以模型
        // 基本不会主动加粗，用户看到的就是一段没有强调的纯文本。
        // v55 智能体卡：创作卡可关掉 Markdown 排版（正文里的 ## 标题/列表/粗体正是「AI 味」来源之一）
        if (card.scaffoldMarkdown) {
        sb.append("\n\n【回答排版】用 Markdown 组织回答：小节用 ## 标题，要点/步骤用列表，横向对比用表格，公式用 LaTeX（行内 $…$、块级 $$…$$）。")
            .append("**用 **粗体** 标出最需要用户注意的内容** —— 结论、金额、截止时间、下一步行动项。")
            .append("次要补充可用 *斜体*，已作废或不再适用的内容用 ~~删除线~~。")
            .append("一段回答里粗体控制在 1~3 处，不要整段加粗、也不要给每个词都加粗 —— 满屏加粗等于没加粗。")
            .append("plan/qa/schedule/checklist/file 是给结构化信息用的内嵌组件，普通叙述和闲聊不要套它们。")
        }

        // 2026-11-17 用户实报（连续多次）：「每次我让 AI 生成/修改计划，AI 都以为是搞 .md 计划文件，
        // 都忘记了工作流里面的那个计划，我要的是后者。」
        //
        // 根因：App 里「计划」有三种含义，用户指的是第 2 种，而提示词只教了组件的 JSON 格式，
        // 从没说过「plan 卡 = 工作流面板里那份计划」；反倒是工具描述里反复出现
        // 「项目文件/计划/步骤.md」这类示例路径，模型自然把「计划」落到 md 文件上。
        //
        // 这段【计划】规则与【回答排版】一样**无条件**下发：创作卡关了组件协议时，
        // 用户照样会说「给我排个计划」——那时至少要给出 `- [ ]` 勾选清单（工作流面板也认）。
        sb.append("\n\n【计划：用户说的是「工作流面板里那份计划」】")
            .append("本应用里用户说的「计划」= **工作流面板**（会话顶栏的工作流胶囊）里显示的那份计划，" +
                "有面板上的步骤清单和推进进度；它**不是**某个 .md 文件，也不是日程/待办数据库。")
            .append("这份计划只有两种载体：① ```plan 组件卡（多步计划/路线图/排期）② 正文里的 `- [ ]` 勾选清单（步骤清单）。" +
                "两者都会被工作流面板自动收集，**最新一条覆盖旧的一条**。")
            .append("用户说「生成计划 / 改计划 / 更新计划 / 把计划调一下 / 这个计划怎么改」时，" +
                "一律**重发一份完整的最新版**（plan 卡或勾选清单），让面板立刻显示新版本；" +
                "要基于旧版改就先读对话里上一版计划的内容再改，不要凭印象重写。")
            .append("**严禁**把计划写进 .md 文件：不要 file_library_write「项目文件/计划/xxx.md」、" +
                "不要新建「计划.md」「赶进度计划.md」这类文件，也不要用 file_export 导出一份计划文档 —— " +
                "写了用户在工作流面板里看不到，只会在文件库里多一个没人要的 md。")
            .append("只有用户**明确说**「把这份计划导出/归档成文档/存成文件」时才落盘。")
            .append("计划里的每一步要可执行：写清动作和产出（读哪份材料、做什么题、改哪一段），" +
                "不要写「复习基础知识」「继续推进」这种没有落点的空话；步骤之间有先后依赖就写进 detail。")

        if (toolsEnabledByCard && card.scaffoldCharts) {
            sb.append("\n\n【数据历史与可视化】日程与待办支持重要程度（high/normal/low，影响颜色）与地点坐标（lat/lng 可来自地图选点），创建/修改时建议带上。")
                .append("用户想看某类数据的整体情况、历史变化或趋势时，调用 data_history 工具（type=todo/schedule/note/digest/usage/sleep/checkin，days 可选 30/90/365）；用户问昨晚/最近的睡眠情况时调用 sleep_query 工具。")
                .append("拿到数据后用图块可视化（围栏语言槽写类型名，中英文均可）：")
                .append("\n```chart：次行写 line|bar|pie，再次行标题，之后每行「类别:数值」——趋势/对比/占比（也可直接用 ```line / ```bar / ```pie / ```柱状图 / ```折线图 / ```饼图，数据行直接跟在围栏后）")
                .append("\n其它类型（语言槽直接写类型名，标题可省略）：hbar 条形图（类目名长时用，横置可读）/ area 面积图（强调随时间变化的幅度或累积量）/ donut 圆环图（饼图加内圈，中心显示总量）/ bubble 气泡图（数据行 x,y,大小，半径编码第三变量）")
                .append("\n树状图/旭日图用缩进树写：首行=根节点，之后每行「- 子节点:数值」，靠缩进定层级；treemap 用面积编码大小，sunburst 用角度编码占比")
                .append("\n直方图写成 bar 且数据行是连续的数值区间（柱间无间隙的观感）；股价图/曲面图暂不支持，别输出这两种语言槽")
                .append("\n```scatter：每行「x,y」数值对——两个变量的相关性分布")
                .append("\n```radar：每行「维度:数值」，可用「满分:N」指定量程——多维能力/状态对比（≥3 维）")
                .append("\n```heatmap：首行标题，次行「,列1,列2,…」，之后每行「行名,v1,v2,…」——按行列展示密集程度/日程分布")
                .append("\n```mindmap：首行=中心主题，每行行首 - 的个数=层级——结构/知识梳理")
                .append("\n```tree：语法同 mindmap，但纵向自上而下——层级组织结构")
                .append("\n```flow：每行「A -> B -> C」（→ 亦可），目标节点可带「(标签)」作边上文字；以 ?/？ 结尾的节点画成判断菱形——流程/决策链")
                .append("\n```fishbone：首行=问题，每行一个主因，主因下 - 行=子因——因果分析")
                .append("\n选用：趋势→chart line、占比→pie、相关→scatter、多维对比→radar、因果→fishbone、结构→mindmap/tree、流程决策→flow、密度分布→heatmap。数据要来自工具真实结果，不要编造。")
        }
        // v45 P3：画像放稳定段（易变尾部之前），避免它把 provider 前缀缓存打穿
        // 2026-11-11 线代会话（288）实录：画像裸注入时，模型把兴趣图谱「在学 Python/C++」
        // 泛化成「什么都往代码上演示」——纯数学问题附了整段求行列式的 Python 实现，
        // 还把画像内容说成对话里已有的背景（未标依据）。注入时带上使用纪律，
        // 纪律是静态文本，位于稳定段，不影响 provider 前缀缓存。
        if (personaText != null) {
            sb.append("\n\n【用户画像（长期）】\n").append(PERSONA_USAGE_RULES).append(personaText)
        }
        // —— 易变尾部（每轮都可能变，放在 system 最后：保证它之前的前缀逐字节稳定，
        // provider 前缀缓存能吃到 persona + 规则 + 组件协议 + 排版说明这一大段）——
        // 当前时间对模型有用（日程类工具的“今天/明天”解析、提醒措辞），但不能用缓存换；
        // 精度仍到分钟：时间在尾部，变了也只废掉 chips 之后这一小段。
        sb.append("\n\n当前时间：${now.year}年${now.monthValue}月${now.dayOfMonth}日 $week ${now.hour}:${now.minute.toString().padStart(2, '0')}。")
        chips.forEach { c ->
            if (c.enabled && c.content.isNotBlank()) {
                sb.append("\n\n【用户上下文·${c.label}】\n").append(c.content)
            }
        }
        val system = sb.toString()

        // transcript：游标之后的有效消息（含工具调用往返，保持顺序）
        // 与摘要侧共用同一个过滤口径（ChatContext.effectiveTranscript）。
        // 摘要裁剪走 id 锚点（v39/M6）：锚点之前（含）的都是已摘要部分。
        val transcript = effectiveTranscript(Graph.chat.messagesOf(sessionId), session.clearBefore)
        // v47：**手动区间压缩**——用户选中某几轮压成摘要后，那几条消息不再进上下文，
        // 摘要作为一条 assistant 消息**插在原位置**（保持时间序；堆头部会让模型把
        // 很早的事当成"刚发生"）。区间之外的消息照原样发。
        // 取 rangesOf 而不是 allOf：旧版整历史 mark（firstCompactedMessageId=0）走老路径不受影响。
        val rangeMarks = runCatching { Graph.compactMarkers.rangesOf(sessionId) }.getOrDefault(emptyList())
        val inRange = compactedIdSet(rangeMarks)
        val rangeByLastId = rangeMarks.associateBy { it.lastCompactedMessageId }
        val emittedRangeIds = mutableSetOf<Long>()
        val afterAnchor = transcript.drop(summarizeStartIndex(transcript, session.lastSummarizedMessageId))
        val fullHistory = buildList {
            for (it in afterAnchor) {
                if (it.id in inRange) {
                    // 区间内：只在遇到该区间**最后一条**消息时注入一次摘要（原位）
                    val mark = rangeByLastId[it.id]
                    if (mark != null && emittedRangeIds.add(mark.id)) {
                        add(
                            ChatApiMessage(
                                "assistant",
                                "【以下为已压缩的 ${mark.compactedCount} 条对话的摘要，原文仍在会话记录里】\n${mark.summary}",
                                "", "", ""
                            )
                        )
                    }
                    continue
                }
                val m = ChatApiMessage(it.role, it.content, it.toolCallsJson, it.toolCallId, it.reasoning)
                // v40（成本优化 2.3/2.4）：工具结果的 **wire 副本**做 base64 清洗 + "头+尾"预算；
                // DB 与 UI 看到的仍是全文（可回溯），只裁发出去的那份。
                if (m.role == "tool") {
                    val copy = truncateToolResultWire(m.content)
                    add(m.copy(content = copy.text))
                } else add(m)
            }
        }
        // 3c：只按"条数"截是不够的 —— 一次大文件读取/OCR 的**单个**工具结果就能占满窗口。
        // 这里按 token 预算从尾部收，并保证 tool_call 与它的 tool_result 永远同进同出。
        // v40（2.5）：预算参数化（设置 KV "ctx.historyBudget"），不再写死。
        val history = capHistory(fullHistory, historyBudget())
        // 与 capHistory / 摘要侧共用同一个单条估算（content + toolCallsJson + 框架开销），
        // 否则清单上的 token 与"按预算裁到哪一条"会是两套数。
        val historyTokens = history.sumOf { estApiMessageTokens(it) }
        val droppedMsgs = fullHistory.size - history.size
        val systemTokens = estTokens(system)

        // v55 智能体卡：世界书（设定卡）——键触发推式注入；拉式 kb_ask 保持兜底。
        // 位置语义：desc_before/desc_after 都作为「主 system 之后、历史之前」的一条 system
        // （紧贴 persona 会把每轮可变内容插进前缀缓存中段，反而打穿缓存；易变靠后原则不变）。
        val loreEntries = runCatching {
            session.assistantId.takeIf { it > 0L }?.let { Graph.lore.forAssistant(it) } ?: emptyList()
        }.getOrDefault(emptyList())
        val loreHits = runCatching {
            io.github.m0use001.planability.ai.lore.LoreEngine.activate(
                entries = loreEntries,
                recentTexts = history.reversed().map { it.content },
                maxTokens = LORE_BUDGET_TOKENS,
                defaultScanDepth = LORE_SCAN_DEPTH,
            )
        }.getOrDefault(emptyList())
        // v56：世界书正文同样是外部卡带来的，宏要展开、零宽字符要清（与人设同口径）
        val loreChar = assistant?.name.orEmpty()
        val loreUser = runCatching {
            Graph.settings.snapshot.value.accountName
        }.getOrDefault(io.github.m0use001.planability.ai.card.CardMacros.DEFAULT_USER)
        fun loreBody(content: String): String = sanitize(
            io.github.m0use001.planability.ai.card.CardMacros.expand(content, loreChar, loreUser),
            8000,
        )
        val loreSurface = loreHits
            .filter { it.position != io.github.m0use001.planability.ai.lore.LoreEngine.POS_DEPTH }
        val loreSurfaceText = loreSurface
            .joinToString("\n\n") { "【设定·${it.name}】\n${loreBody(it.content)}" }
        val loreDepth = loreHits
            .filter { it.position == io.github.m0use001.planability.ai.lore.LoreEngine.POS_DEPTH }
        var outHistory = history
        loreDepth.forEach { h ->
            outHistory = injectAtDepth(outHistory, h.depth, h.role, "【设定·${h.name}】\n${loreBody(h.content)}")
        }
        // 深度注三层顺序：世界书 depth → 卡级深度注 → 会话作者注（同深度时作者注在最前）
        outHistory = injectAtDepth(outHistory, card.depth, card.depthRole, card.depthText)
        outHistory = injectAtDepth(outHistory, session.authorNoteDepth, "system", session.authorNote)

        val items = chips +
            (if (personaText != null)
                listOf(CtxItem("persona", "用户画像（长期）", estTokens(personaText), true, personaText))
            else emptyList()) +
            CtxItem("system", "系统提示+时间", systemTokens, true, system) +
            // v40（成本优化 2.1）：工具 schema 是每请求几千到上万 token 的固定块，
            // 原来完全不在账上（ref-cost-study §0-B6）。显式列为一个 item，面板/manifest 都可见。
            (if (toolsEnabledByCard && toolsJson.isNotBlank())
                listOf(CtxItem("tools", "工具 schema", estTokens(toolsJson), true, ""))
            else emptyList()) +
            (if (loreHits.isNotEmpty())
                listOf(
                    CtxItem(
                        "lore", "世界书（命中 ${loreHits.size} 条）",
                        estTokens(loreSurfaceText) + loreDepth.sumOf { estTokens(it.content) }, true, ""
                    )
                )
            else emptyList()) +
            CtxItem(
                "history",
                if (droppedMsgs > 0) "对话历史（${history.size} 条，已截断 $droppedMsgs 条）"
                else "对话历史（${history.size} 条）",
                historyTokens, true, ""
            )
        val messages = buildList {
            add(ChatApiMessage("system", system))
            if (loreSurfaceText.isNotBlank()) add(ChatApiMessage("system", loreSurfaceText))
            addAll(outHistory)
            // 收尾禁令：历史之后、生成之前（酒馆 post_history 槽；全场权重最高的一条）
            if (card.postHistory.isNotBlank()) add(ChatApiMessage("system", card.postHistory))
        }
        return ContextPlan(
            items = items,
            totalTokens = items.sumOf { it.tokens },
            messages = messages,
            // **压缩前**的整个有效历史条数（不是 fullHistory.size——那是游标之后，
            // 压缩成功时它恰好等于发出去的条数，会掩盖"到底有没有压缩"）。
            preCompactCount = transcript.size
        )
    }

    /** v55 世界书注入预算（token 估算口径）。v1 固定；后续接设置页。 */
    private const val LORE_BUDGET_TOKENS = 3000

    /** v55 世界书默认扫描深度（最近 N 条消息；条目可覆写）。 */
    private const val LORE_SCAN_DEPTH = 2

    /**
     * v55 把一条深度注插到「倒数第 [depth] 条消息之前」。
     * 不拆 tool_call/tool_result 对：落点在 tool 结果上时前移到该串第一条 tool；
     * 若正好落在 assistant(tool_calls) 与其结果之间，再前移到 assistant 之前。
     */
    private fun injectAtDepth(
        history: List<ChatApiMessage>,
        depth: Int,
        role: String,
        text: String,
    ): List<ChatApiMessage> {
        if (text.isBlank()) return history
        var idx = (history.size - depth.coerceIn(0, history.size)).coerceIn(0, history.size)
        while (idx > 0 && idx < history.size && history[idx].role == "tool") idx--
        if (idx > 0 && history[idx - 1].role == "assistant" && history[idx - 1].toolCallsJson.isNotBlank()) idx--
        return history.toMutableList().also { it.add(idx, ChatApiMessage(role, text)) }
    }

    /** 历史预算（token）**默认值**。可被设置 KV `ctx.historyBudget` 覆盖（见 [historyBudget]）。 */
    // 对单测可见：`ContextCompactionBoundaryTest` 要用它构造"刚好超预算/刚好不超"的输入。
    // 值改了测试会跟着改，不用在测试里硬编码 12000。
    internal const val MAX_HISTORY_TOKENS = 12000

    /**
     * v40（成本优化 2.5，ref-cost-study §1.4）：预算参数化。
     * 原来 12000 写死、与模型窗口无关——小窗口模型会被 capHistory 兜底裁到远超窗口，
     * 大窗口模型又白白多裁。设置 KV `ctx.historyBudget`（缺省 = 默认值，夹在 2000..200000）。
     * 设置面板 UI 属 B 线（已在 phone-c.md 记 assign），先通 KV。
     */
    internal suspend fun historyBudget(): Int =
        (Graph.settings.kvGet("ctx.historyBudget")?.trim()?.toIntOrNull() ?: MAX_HISTORY_TOKENS)
            .coerceIn(2000, 200_000)

    /**
     * 把历史裁到默认预算内（测试与旧调用的重载）。
     *
     * 规则（对应 C 的边界清单 A 组）：
     * 1. **不拆 tool 对**：带 toolCalls 的 assistant + 紧随其后的 tool 消息算一组，
     *    整组留或整组丢。模型看到"没有对应调用"的工具结果会报 400 或开始编造。
     * 2. **孤立的 tool 消息直接丢**（前面没有带 toolCalls 的 assistant）——
     *    它同样会造成上述非法序列。
     * 3. 从**尾部**往前装，所以最近的对话一定保住；至少保留一组，哪怕它自己就超预算
     *    （总比发一个空历史好）。
     */
    // 对单测可见：这是唯一一道"拆 tool 对"的闸门，行为要能被直接断言
    // (ContextCompactionBoundaryTest)。逻辑本身一行没动。
    internal fun capHistory(all: List<ChatApiMessage>): List<ChatApiMessage> = capHistory(all, MAX_HISTORY_TOKENS)

    internal fun capHistory(all: List<ChatApiMessage>, budget: Int): List<ChatApiMessage> {
        if (all.isEmpty()) return all
        // 与 estMessageTokens 同口径：只算 content 会把"空正文但带一大坨 toolCallsJson 的 assistant"
        // 估成 0，既骗过预算也骗过"要不要裁"的早退分支（phone-b 2026-10-19 报的 B 项）。
        if (all.sumOf { estApiMessageTokens(it) } <= budget) return all

        val groups = ArrayList<MutableList<ChatApiMessage>>()
        var pendingToolGroup: MutableList<ChatApiMessage>? = null
        for (m in all) {
            if (m.role == "tool") {
                // 只认"上一条是带 toolCalls 的 assistant"或"同一组的后续 tool"
                val g = pendingToolGroup
                if (g != null && g.first().toolCallsJson.isNotBlank()) g += m
                else pendingToolGroup = null   // 孤立 tool：丢弃
            } else {
                val g = mutableListOf(m)
                groups += g
                pendingToolGroup = if (m.role == "assistant" && m.toolCallsJson.isNotBlank()) g else null
            }
        }

        val kept = ArrayDeque<List<ChatApiMessage>>()
        var tokens = 0
        for (g in groups.asReversed()) {
            val t = g.sumOf { estApiMessageTokens(it) }
            if (kept.isNotEmpty() && tokens + t > budget) break
            kept.addFirst(g)
            tokens += t
        }
        return kept.flatten()
    }

    private fun chipItem(type: String, label: String, content: String): CtxItem =
        CtxItem(type, label, estTokens(content), chipEnabled(type), content)

    // ---------- manifest 落盘（可审计） ----------

    fun writeManifest(sessionId: Long, plan: ContextPlan) {
        runCatching {
            val dir = File(Graph.appContext.filesDir, "ai_context").apply { mkdirs() }
            val json = JSONObject()
                .put("ts", System.currentTimeMillis())
                .put("sessionId", sessionId)
                .put("totalTokens", plan.totalTokens)
                .put(
                    "items", JSONArray().apply {
                        plan.items.forEach {
                            put(
                                JSONObject()
                                    .put("type", it.type)
                                    .put("label", it.label)
                                    .put("tokens", it.tokens)
                                    .put("enabled", it.enabled)
                            )
                        }
                    }
                )
            // v37 上线校验：摘要写进 DB 不算数，**真的出现在这次请求里才算数**。
            // 问题（空数组 = 通过）落到 manifest，真机与账本里都看得见——这是
            // `CompactSummaryOnTheWireTest` 那条教训的落地版（只断言"生成了摘要"不够）。
            val summaryChip = plan.items.firstOrNull { it.type == "summary" && it.enabled }
            if (summaryChip != null) {
                val label = plan.items.firstOrNull { it.type == "history" }?.label.orEmpty()
                // label 形如「对话历史（8 条）」或「对话历史（8 条，已截断 12 条）」
                val sentCount = Regex("""（(\d+)\s*条""").find(label)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                // 关键：分母用**压缩前**的整个有效历史条数（plan.preCompactCount）。
                // 不能用 sentCount+已截断（那等价于"capHistory 没裁过"），
                // 也不能用摘要游标之后的条数（压缩成功时它等于 sentCount，会误报）。
                val problems = Compaction.validateOnTheWire(
                    system = plan.messages.firstOrNull { it.role == "system" }?.content ?: "",
                    summary = summaryChip.content,
                    fullCount = plan.preCompactCount,
                    sentCount = sentCount
                )
                json.put("compactionOnWire", JSONArray().apply { problems.forEach { p -> put(p) } })
            }
            File(dir, "manifest_$sessionId.json").writeText(json.toString())
        }
    }

    // ---------- usage 账本 ----------

    /**
     * 追加一行用量。v40（成本优化 1.3/1.4，ref-cost-study §2.1）：
     * - `slot` 是**用途归因**：main（主对话）/ summary（滚动摘要）/ naming（自动起名）/ rescue（超长救援）。
     *   原来只有 main，命名与摘要的 AI 调用完全没记账——不知道钱花在哪，就谈不上省。
     * - `tokensIn/tokensOut/cachedTokens` 可空：**"unknown ≠ 0"**（学 rikkahub-agent-pure 的账本），
     *   端点没上报就不写这个字段，绝不写 0 —— "没上报"会污染缓存命中率与按用途的成本聚合。
     *   旧读取方（usage 工具、用量页）用 optInt 读，缺字段回 0，行为兼容。
     */
    suspend fun appendUsage(
        sessionId: Long,
        slot: String,
        model: String,
        tokensIn: Int?,
        tokensOut: Int?,
        cachedTokens: Int? = null,
        /** 墙钟耗时（ms）。阶段 4：统计页要算 tok/s（PURE 有，我们之前没有）。主对话流式路径不测，缺省 null。 */
        durationMs: Long? = null
    ) =
        withContext(Dispatchers.IO) {
            runCatching {
                val dir = File(Graph.appContext.filesDir, "ai_usage").apply { mkdirs() }
                val line = JSONObject()
                    .put("ts", System.currentTimeMillis())
                    .put("session", sessionId)
                    .put("slot", slot)
                    .put("model", model)
                if (tokensIn != null) line.put("tokensIn", tokensIn)
                if (tokensOut != null) line.put("tokensOut", tokensOut)
                if (cachedTokens != null) line.put("cachedTokens", cachedTokens)
                if (durationMs != null && durationMs > 0) line.put("ms", durationMs)
                File(dir, "usage.jsonl").appendText(line.toString() + "\n")
            }
        }
}
