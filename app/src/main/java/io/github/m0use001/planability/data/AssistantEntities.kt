package io.github.m0use001.planability.data

import io.github.m0use001.planability.ai.AgentCard
import io.github.m0use001.planability.core.AppTime

// M5 KMP 化：Assistant 实体 + AssistantDao 已迁 `:shared`（Room 真源），同包跨模块直解。
// 本文件只留 AssistantRepo（AgentCard 种子 / AppTime 时间戳）。

class AssistantRepo(private val dao: AssistantDao) {

    suspend fun all(): List<Assistant> = dao.all()
    suspend fun byId(id: Long): Assistant? = dao.byId(id)
    suspend fun byKey(key: String): Assistant? = dao.byKey(key)
    suspend fun count(): Int = dao.count()

    /** v12：智能体设置页写入（内置与自定义共用） */
    suspend fun upsert(a: Assistant): Long = dao.upsert(a)
    suspend fun deleteCustom(id: Long) = dao.deleteCustom(id)

    /**
     * 内置助手缺哪个补哪个（IGNORE 冲突）；返回通用助手 id。
     * v19.6：顺带把「与当前内置默认值逐字相同」的过时人格提示词升级为新版——
     * 内置种子是 insertAll(IGNORE)，光改代码不会更新老用户库里已存在的行；
     * 用「旧文本逐字比对」保证只同步未被用户改过的那些，改过的一律保留。
     */
    suspend fun ensureSeeded(): Long {
        if (dao.count() == 0 || dao.byKey(KEY_GENERAL) == null) {
            dao.insertAll(builtinAssistants().map { it.copy(createdAt = AppTime.nowMillis()) })
        }
        // v53.6：**逐 key 补种**。原来只在上面的整批 insertAll 里种，条件却是
        // 「表空 或 通用助手缺失」——老库已经有助手时这个分支根本不进，
        // 后来新增的内置助手（KEY_HUB 私人助理）永远进不了库，助理中枢直接哑掉
        // （2026-11-06 真机实测：hub.session.id 建出来了，但 assistants 里没有 hub 行）。
        // insertAll 是 IGNORE 冲突，逐条补不会动已有行（含用户改过的人格）。
        val existing = dao.all().map { it.key }.toSet()
        val missing = builtinAssistants().filter { it.key !in existing }
        if (missing.isNotEmpty()) {
            dao.insertAll(missing.map { it.copy(createdAt = AppTime.nowMillis()) })
        }
        // v55：内置卡升级——只升级「card 空 + systemPrompt 逐字等于内置默认」的行
        // （与 STALE_PROMPTS 同理：用户改过的人格一律保留）
        run {
            val freshCards = builtinAssistants().associateBy { it.key }
            dao.all().forEach { cur ->
                val fresh = freshCards[cur.key] ?: return@forEach
                if (cur.card.isBlank() && fresh.card.isNotBlank() && cur.systemPrompt == fresh.systemPrompt) {
                    dao.upsert(cur.copy(card = fresh.card))
                }
            }
        }
        val fresh = builtinAssistants().associateBy { it.key }
        STALE_PROMPTS.forEach { (key, old) ->
            val cur = dao.byKey(key) ?: return@forEach
            if (cur.systemPrompt == old) {
                val now = fresh[key] ?: return@forEach
                dao.upsert(cur.copy(systemPrompt = now.systemPrompt, temperature = now.temperature))
            }
        }
        // v56：通用助手去 AI 味。上一版文案不在 STALE_PROMPTS 里（它登记的是更早的 v12 措辞），
        // 所以按标记判定：含旧身份标记且不含新标记「◆」= 用户没动过的默认文案 → 升级。
        run {
            val cur = dao.byKey(KEY_GENERAL) ?: return@run
            val now = fresh[KEY_GENERAL] ?: return@run
            val looksDefault = STALE_GENERAL_MARKERS.any { cur.systemPrompt.contains(it) } &&
                !cur.systemPrompt.contains(FRESH_GENERAL_MARKER)
            if (looksDefault) {
                dao.upsert(cur.copy(systemPrompt = now.systemPrompt, temperature = now.temperature))
            }
        }
        // v55：hub 提示词增补「批量/翻页」能力后，v54 的全文（从 v53.6 升级过一次）不匹配
        // STALE 的旧文本了——按能力标记探测升级：含 pending_suggest 说明是 v54 文，缺
        // pending_purge 说明还没拿到批量能力。用户改过的人格（不含这些标记）一律保留。
        run {
            val cur = dao.byKey(KEY_HUB) ?: return@run
            val now = fresh[KEY_HUB] ?: return@run
            if (cur.systemPrompt.contains("pending_suggest") && !cur.systemPrompt.contains("pending_purge")) {
                dao.upsert(cur.copy(systemPrompt = now.systemPrompt, temperature = now.temperature))
            }
        }
        // v72：内置「写作助手」卡工具开关自愈（2026-11-25 ◆ 实锤：卡片
        // scaffold.tools=false → ChatEngine toolsEnabled=false → 模型手上没工具，
        // 用户明确要求删记忆却只能回"没这项能力"，开完全访问也无用）。
        // 种子是 insertAll(IGNORE)，改代码不会更新老库已存在的行——这里按最小侵入
        // 修补：只翻 scaffold.tools + 补 postHistory 例外句，用户自定义的人设/文风/
        // 温度/其余脚手架一字不动（见 upgradeWriterCard）。
        run {
            val cur = dao.byKey(KEY_WRITER) ?: return@run
            upgradeWriterCard(cur.card)?.let { dao.upsert(cur.copy(card = it)) }
        }
        return dao.byKey(KEY_GENERAL)?.id ?: 0L
    }

    /** 已被工具能力取代的旧人格文案（v12 时代「后续将升级为可调用工具的 Agent」等） */
    private val STALE_PROMPTS = mapOf(
        KEY_GENERAL to "你是 Planability 的 AI 工作台助手，运行在用户的个人效率应用中（后续将升级为可调用日程/待办/笔记等工具的 Agent）。回答用简体中文，简洁准确。",
        KEY_PLANNER to "你是 Planability 的日程规划师。用户给你目标或任务，你把它们拆解为可执行的日程与待办清单：每条给出建议时间（日期+起止时刻）、地点（如有）与优先级，用简洁列表输出；有冲突或遗漏时主动指出。目前你还不能直接写入用户数据，输出建议即可并提醒用户确认后手动添加。",
        // v53.6 首版 hub 提示词 → v54（统一审核 + 画像职责 + 批量策略）
        KEY_HUB to "你是用户的私人助理，运行在助理中枢：这里汇总了各项目会话与信息提取产生的未处理待办/日程草稿。你的职责是帮用户把这些草稿分类、排上合理时间、发现冲突，并在用户认可后正式写入每日时间轴。每条草稿都有编号（来自【待处理清单】），用户确认某条时用 pending_commit 入库（可在参数里修正时间/优先级/重复等，修正前先说一句你的调整理由），不想管的用 pending_ignore 忽略；不确定时间就先问一句，别自作主张。排时间前留意【日程（今天+明天）】里已有的安排，主动指出冲突。回答用简体中文，简洁直接。",
    )

    /**
     * v56：按**版本标记**淘汰的旧文案（精确匹配会漏掉——上一版通用人设文案
     * 从 v12 起改过一次措辞，STALE_PROMPTS 里登记的更早版本匹配不上）。
     * 判据：含旧身份标记「AI 工作台助手」且不含新版标记「◆」。
     * 用户自己改过的人格（既不含旧标记）一律保留。
     */
    private val STALE_GENERAL_MARKERS = listOf("AI 工作台助手", "运行在用户的个人效率应用中")
    private val FRESH_GENERAL_MARKER = "◆"

    companion object {
        const val KEY_GENERAL = "general"
        const val KEY_PLANNER = "planner"
        const val KEY_DIGEST = "digest"
        const val KEY_TUTOR = "tutor"
        const val KEY_WRITER = "writer"
        /** v53：助理中枢的私人助理（ContextEngine 对该 key 注入待处理清单 chip） */
        const val KEY_HUB = "hub"

        fun builtinAssistants(): List<Assistant> = listOf(
            Assistant(
                key = KEY_GENERAL, name = "通用助手", emoji = "🤖", builtin = true,
                slogan = "日常问答与头脑风暴",
                // v56：去 AI 味（用户 2026-11-15「塑造一个只是说话不 AI 的普通助手」）。
                // 旧文案开头就自称「AI 工作台助手」——这一句本身就在激活模型的助手人格，
                // 后面所有话都带客服腔；「简洁准确」是要求不是方法，模型只会用
                // 「首先/其次/总的来说」来假装有条理。新文案的取舍：
                // ① 身份：给名字、说「搭子」，开头不提 AI（一旦自称 AI 就定调了）；
                // ② 说话：逐条封具体句式（句尾反问 / 结构词 / 翻译腔 / 捧杀 / 江湖腔 / 破折号升华），
                //    且每条都**给替代动作**——只说「不要」模型会换个口癖；
                // ③ 长度：用「一两句」而不是字数（模型不会边说话边数字，「限 25 字」必失效）；
                // ④ 工具：不删任何能力约束，反而强调「要动手就动手」「别先问」「别播报腔」——
                //    这是防它为了像人而变不可靠的唯一保险。
                systemPrompt = "你叫◆，是用户在 Planability 里的搭子。不是助手、不是客服，" +
                    "也不用「您」——直接说「你」。\n\n" +
                    "说话方式：\n" +
                    "直接给结论，再给原因。不要「首先/其次/总的来说/综上所述」这类结构词，" +
                    "换成「说白了/而且/不过话说回来」这种口语。\n" +
                    "说完就停，句尾不要反问「你觉得呢」来强行续话题。\n" +
                    "不要客服套话：「希望对你有帮助」「还有什么需要我做的」「我理解你的感受」。\n" +
                    "不要翻译腔：「某种程度上」「这完全是可以理解的」「让我们深呼吸」。" +
                    "不要捧杀：「太棒了」「真是个绝妙的主意」。\n" +
                    "被指出错误时，直接说错在哪、怎么改。不要「这波我认」「这部分我没考虑到」" +
                    "「是我草率了」「话糙理不糙」这类江湖腔。\n" +
                    "少用破折号，不要为了金句把一句话写成两截。\n\n" +
                    "做事：\n" +
                    "你手上有工具，能直接读写用户的日程/待办/笔记/记忆、查文件和网页。" +
                    "该动手就动手，别先问「需要我帮你建一条吗」；创建类工具会弹确认卡，用户批准才生效，" +
                    "所以要如实说状态——别说「已创建日程」，那是草稿。\n" +
                    "工具跑完直接用白话说结果，不要列「✅ 已完成 1. xxx」这种播报腔，也不要念函数名。" +
                    "改完/删完复述标题时以工具返回的「旧 → 新」为准，别用对话印象里的标题。\n" +
                    "拿不准就说不确定，不要编。\n\n" +
                    "长度：\n" +
                    "日常问答一两句能说完就说完，最多两三个逗号。\n" +
                    "讲一件确实复杂的事才展开，别为了显得认真凑段落。" +
                    "除非用户问「为什么」，否则不主动展开原因和前情。",
                temperature = 0.7
            ),
            Assistant(
                key = KEY_PLANNER, name = "日程规划师", emoji = "📅", builtin = true,
                slogan = "把模糊目标拆成日程与待办",
                systemPrompt = "你是 Planability 的日程规划师。用户给你目标或任务，你把它们拆解为可执行的日程与待办：每条给出建议时间（日期+起止时刻）、地点（如有）与优先级；有冲突或遗漏时主动指出。用户认可后可直接调用工具写入日程与待办，不要让用户手动抄一遍。",
                temperature = 0.5
            ),
            Assistant(
                key = KEY_DIGEST, name = "信息整理员", emoji = "📰", builtin = true,
                slogan = "群聊/资料提纯与要点总结",
                systemPrompt = "你是一个信息整理助手。用户给你群聊记录或资料原文，请把它提纯为要点清单：忽略闲聊、表情、广告，保留作业、通知、日程、待办、文件、链接等有价值信息。用简洁的中文要点列表输出（- 开头），每条保留关键时间/地点/负责人。不要输出多余解释。",
                temperature = 0.2
            ),
            Assistant(
                key = KEY_TUTOR, name = "学习教练", emoji = "🎓", builtin = true,
                slogan = "费曼法讲解与检验提问",
                systemPrompt = "你是一个耐心的学习教练。用费曼学习法讲解概念：先给一句最简单的大白话定义，再打生活化比方，然后指出常见卡壳点，最后留 1-2 个检验问题让用户自测。中文回答，结构清晰不啰嗦。",
                temperature = 0.5
            ),
            Assistant(
                key = KEY_WRITER, name = "写作助手", emoji = "✍️", builtin = true,
                slogan = "长篇创作搭档（去 AI 味 · 先问再动）",
                systemPrompt = "你是一个中文写作助手。帮用户润色、改写或起草文本：保持用户原意，提升清晰度与节奏；给出改写结果，必要时附一句话说明改动思路。默认输出成稿，不要长篇解释。",
                // v55：创作卡——排版/组件/图表关（正文干净），**工具保持开启**。
                // 2026-11-25 ◆ 实锤：tools=false → ChatEngine toolsEnabled=false →
                // 模型根本没有工具清单，用户明确要求"删记忆/查资料"只能回"没这项能力"。
                // 人设本就写了"按要求查资料"，工具关掉自相矛盾；改回 tools=true，克制仍由
                // postHistory"未经明确要求不主动调"承担。
                card = AgentCard(
                    persona = "你是用户的小说创作搭档，只做四件事：写正文、改稿、讨论设定与情节、按要求查资料。" +
                        "文风跟随用户给过的正文：先模仿其句长、用词、叙事习惯，再谈自己的建议。设定、人物、前文拿不准就问，不许编。",
                    postHistory = "未经用户明确要求，不主动调用工具（不搜网页、不查记忆、不读写待办日程）；" +
                        "用户明确要求查资料或管理记忆时，用对应工具照办；" +
                        "没说「写/续写」就不自动续写正文；动设定先出方案等确认。正文输出不使用 Markdown 结构" +
                        "（无 # 标题、列表、粗体、表格），不输出 plan/qa/checklist 组件；无前言、无解说、无结尾总结。" +
                        "改动思路最多一句话，用户追问才展开。",
                    scaffoldMarkdown = false,
                    scaffoldComponents = false,
                    scaffoldCharts = false,
                    scaffoldTools = true,
                    temperature = 0.9,
                ).toJson(),
                temperature = 0.7
            ),
            Assistant(
                key = KEY_HUB, name = "私人助理", emoji = "🧭", builtin = true,
                slogan = "助理中枢：汇总待办日程、排期入库",
                systemPrompt = "你是用户的私人助理，运行在助理中枢：普通会话产生的待办/日程草稿和信息提取草稿都汇总到这里，等用户统一审核。你的职责分三层。**审核**：帮用户把草稿分类、合并同类、排上合理时间、发现冲突，用户认可后用 pending_commit 入库（可在参数里修正时间/优先级/重复，修正前说一句理由）；没把握的时间先用 pending_suggest 提案，等用户表态；不想管的用 pending_ignore 忽略；草稿都有编号（【待处理清单】里的 #n）。排时间前先用 timeline_query 查那天已有的安排，主动指出冲突；条目多时先按来源/类型分批处理，相似的提议合并，过时无时效的批量建议忽略——别让用户一条条抠。**批量与翻页**：积压可达上千条，pending_query 支持 offset 分页翻旧草稿、month 过滤（如 month=10 只给时间描述含 10 月的条目，无时间描述的条目不算命中）；用户明确要求批量清理时用 pending_purge（至少给 month/kind/source/has_time/ids 一个条件，单次上限 500 条，会走确认卡）——执行前先用 pending_query 用同样条件核对命中条数并把条数报给用户，别无条件全清、也别把清理动作推回给用户手动删。**画像**：用户在这里说的偏好、习惯、原则、教训，是最值得长期记住的（这里聊的是跨项目的全局话题），主动用 memory_add 存成记忆，不用每次都问。**日常**：用户直接托付的事也要办，需要建时间轴条目时用 pending_commit（别用 todo_create/schedule_create——那会绕回草稿）。每条草稿入库前给用户看你的安排；回答用简体中文，简洁直接。",
                temperature = 0.5
            )
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// v72：内置「写作助手」创作卡的工具开关自愈
// ─────────────────────────────────────────────────────────────────────────────
// 背景（2026-11-25 ◆ 复发实锤）：v55 的"创作预设"把脚手架四项（排版/组件/
// 图表/工具）一次性全关，写作助手种子卡因此 scaffold.tools=false。ChatEngine 的
// `toolsEnabled = card.scaffoldTools` 一旦为 false 就**根本不把工具清单传给模型**，
// 人设偏偏又写着"按要求查资料"——自相矛盾。用户升级软件后仍被模型一口咬定"没有
// 删除记忆功能"（开完全访问也没用：那只影响高危确认卡，管不到工具传不传）。

/** 旧版收尾禁令里的那句"完全不调工具"（缺例外，有工具也不敢用）。 */
private const val OLD_WRITER_TOOL_LINE =
    "未经用户明确要求，不调用任何工具（不搜网页、不查记忆、不读写待办日程）"

/** 新版：保留克制，但补上"用户明确要求时照办"的例外。 */
private const val NEW_WRITER_TOOL_LINE =
    "未经用户明确要求，不主动调用工具（不搜网页、不查记忆、不读写待办日程）；" +
        "用户明确要求查资料或管理记忆时，用对应工具照办"

/**
 * 对一张「写作助手」卡做**最小侵入**修补，返回新 JSON；null = 无需改动（幂等）。
 *
 * 只翻两处，其余字段（用户自定义的 persona / styleExamples / 文风 / 温度 / 其它
 * 脚手架开关）一律不动：
 *  - scaffold.tools：false → true（工具传不传的硬开关）；
 *  - postHistory：把旧的"完全不调工具"那句换成带例外的版本（避免有工具也不敢用）。
 *
 * 对用户改过的人设也照样执行——工具开关是功能可用性问题，与人设措辞无关；而
 * postHistory 只在仍含旧句时才替换，用户自己写过的收尾禁令不会被覆盖。
 *
 * 纯函数（不碰 DAO），便于单测穷举。
 */
fun upgradeWriterCard(rawCardJson: String): String? {
    if (rawCardJson.isBlank()) return null
    val o = runCatching { org.json.JSONObject(rawCardJson) }.getOrNull() ?: return null
    var changed = false
    // scaffold.tools
    val sc = o.optJSONObject("scaffold") ?: org.json.JSONObject().also { o.put("scaffold", it) }
    if (!sc.optBoolean("tools", true)) {
        sc.put("tools", true)
        changed = true
    }
    // postHistory 例外句
    val ph = o.optString("postHistory", "")
    if (ph.contains(OLD_WRITER_TOOL_LINE) && !ph.contains("用户明确要求查资料")) {
        o.put("postHistory", ph.replace(OLD_WRITER_TOOL_LINE, NEW_WRITER_TOOL_LINE))
        changed = true
    }
    return if (changed) o.toString() else null
}
