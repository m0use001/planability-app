package io.github.m0use001.planability.ai

import org.json.JSONObject

/**
 * v55 智能体卡：把「一句话人设」升级为结构化定义。
 *
 * 字段与注入位置（ContextEngine.assemble 消费）：
 * - [persona]      → system 头部（原 systemPrompt 的位置）
 * - [styleExamples] → 紧随人设的「文风示例」块（预算紧时先砍）
 * - [postHistory]  → 聊天记录之后的一条 system 消息（酒馆 post_history 槽，权重最高）
 * - [depthText]    → 钉在倒数第 [depth] 条消息后（角色 [depthRole]，酒馆 depth_prompt 同款）
 * - [scaffold*]    → 全局脚手架（排版/组件/图表/工具循环）按卡裁剪
 * - [temperature]  → 覆盖 Assistant.temperature（null = 用表字段）
 *
 * 兼容：card 为空时 parse() 用旧 systemPrompt 生成等价卡（零行为变化）。
 */
data class AgentCard(
    val schema: Int = 1,
    /** 人设正文（多行分节纯文本） */
    val persona: String = "",
    /** 文风示例（示例段落/对照稿，教文风比形容词清单有效） */
    val styleExamples: String = "",
    /** 收尾禁令（注入在聊天记录之后，贴生成点） */
    val postHistory: String = "",
    /** 深度注正文（卡级方向舵） */
    val depthText: String = "",
    /** 深度注位置：倒数第几条消息后 */
    val depth: Int = 4,
    /** 深度注角色：system / user / assistant */
    val depthRole: String = "system",
    /** 脚手架：回答排版（## 标题/列表/粗体） */
    val scaffoldMarkdown: Boolean = true,
    /** 脚手架：内嵌组件卡（plan/qa/checklist…） */
    val scaffoldComponents: Boolean = true,
    /** 脚手架：图表块 */
    val scaffoldCharts: Boolean = true,
    /** 脚手架：工具循环（todo/日程/记忆/搜索…） */
    val scaffoldTools: Boolean = true,
    /** 生成温度覆盖（null = 用 Assistant.temperature） */
    val temperature: Double? = null,
    /** 开场白（v1 预留，未接线） */
    val greeting: String = "",
) {

    fun toJson(): String = JSONObject().apply {
        put("schema", schema)
        put("persona", persona)
        put("styleExamples", styleExamples)
        put("postHistory", postHistory)
        put("depthPrompt", JSONObject().apply {
            put("text", depthText)
            put("depth", depth)
            put("role", depthRole)
        })
        put("scaffold", JSONObject().apply {
            put("markdown", scaffoldMarkdown)
            put("components", scaffoldComponents)
            put("charts", scaffoldCharts)
            put("tools", scaffoldTools)
        })
        temperature?.let { put("temperature", it) }
        put("greeting", greeting)
    }.toString()

    /** 创作预设：排版/组件/图表关（工作台味归零），**工具保留**——见下 */
    fun creative(): AgentCard = copy(
        scaffoldMarkdown = false,
        scaffoldComponents = false,
        scaffoldCharts = false,
        // 2026-11-25：工具不再随创作预设一并关掉。关掉工具=ChatEngine 的
        // toolsEnabled=false，模型手上根本没有工具清单，用户明确要求"删记忆/查资料"
        // 时只能回"没这项能力"（◆ 复发实锤）。排版/组件/图表关掉是为了
        // 正文干净，工具与"正文是否带 Markdown"无关——放开工具不影响创作手感，
        // 且真正要不要调由人设的"未经明确要求不主动调"约束。
        scaffoldTools = true,
    )

    /**
     * v56：展开文本字段里的角色卡宏（`{{char}}` / `{{user}}` / `<START>`）。
     *
     * 只展开**进提示词的那几段**（人设/文风示例/收尾禁令/深度注/开场白），不动脚手架开关。
     * 不做的话导入卡会在正文里吐出字面 `{{char}}`——详见 [io.github.m0use001.planability.ai.card.CardMacros]。
     */
    fun rendered(charName: String, userName: String = "我"): AgentCard = copy(
        persona = io.github.m0use001.planability.ai.card.CardMacros.expand(persona, charName, userName),
        styleExamples = io.github.m0use001.planability.ai.card.CardMacros.expand(styleExamples, charName, userName),
        postHistory = io.github.m0use001.planability.ai.card.CardMacros.expand(postHistory, charName, userName),
        depthText = io.github.m0use001.planability.ai.card.CardMacros.expand(depthText, charName, userName),
        greeting = io.github.m0use001.planability.ai.card.CardMacros.expand(greeting, charName, userName),
    )

    companion object {
        /**
         * 没人设时的兜底人格。
         *
         * v56：原来写「你是 Planability 的 AI 工作台助手……简洁准确」——开头自称 AI 就在
         * 激活模型的助手人格，客服腔从第一句就定调了；「简洁准确」是要求不是方法。
         * 这里**刻意保持极短且中性**：真正的完整人设只有一份真相，在
         * `AssistantEntities.builtinAssistants()`（KEY_GENERAL），复制过来会漂移。
         * 本常量只在「assistant 记录丢了 / card 与 systemPrompt 都空」时兜底。
         */
        const val LEGACY_GENERAL_PROMPT =
            "直接回答用户的问题，不要客服套话，不要自称 AI。"

        /**
         * v56：角色扮演卡的**默认收尾禁令**（贴生成点，权重最高的槽位）。
         *
         * 只在「导入角色卡 + 选了角色扮演」且**卡自带的 post_history_instructions 为空**时填入
         * ——卡作者写了就尊重作者的，不覆盖。
         *
         * 三条都来自调研（AstrBot `astrbot_plugin_private_companion` +
         * `StarfallMark/persona-prompt-designer`，后者 CC BY-NC-SA 4.0），
         * 且都**不破坏文采**：
         * ①模型专属口癖黑名单——各家模型被反驳时的出厂语言习惯（DeepSeek 的
         *   「这波我认」「这部分我没考虑到」等）最容易让人物瞬间变回助手；
         * ②被动触发锁 + 就事论事原则——治「设定推销员病」（随便问句无关的话就迫不及待
         *   提自己写的爱好）；把非核心背景交给世界书按需触发，而不是每轮主动推销；
         * ③不自称 AI / 不总结 / 不用 Markdown。
         *
         * **刻意不放字数硬锁**：那套（句数+逗号数）只适合「微信碎片短句流」人格，
         * 默认加会把有文风示例的卡压成电报体。需要的用户在编辑页自己加。
         */
        const val ROLEPLAY_POST_HISTORY =
            "始终以角色的身份回话，不要自称 AI / 助手 / 模型，不谈自己是程序，" +
                "不写「以下是」「总结一下」这类解说，不在结尾复述或总结对话。" +
                "不要使用 Markdown 结构（无 ## 标题、列表、粗体、表格），" +
                "也不输出 plan / qa / checklist 一类组件块。" +
                "不要使用这些模型口癖（它们会让人物瞬间变回助手）：" +
                "「这波我认」「我认」「这部分我没考虑到」「我没想到」「好的，明白了」" +
                "「有什么需要我做的吗」「希望这对你有帮助」。" +
                "只回答对方真正问的事；对方没问到的设定不要主动推销" +
                "（不主动提自己的爱好、经历或背景，除非对方问到或话题自然相关）。" +
                "不确定的事按角色性格含糊带过，不要给出精确的事实或数据。"

        /**
         * 解析卡 JSON。
         * - raw 空 → 以 [fallbackSystemPrompt] 为人设的等价卡；
         * - 坏 JSON → 同上（绝不因一张坏卡让会话哑掉）；
         * - persona 空白 → 回落到 fallbackSystemPrompt。
         */
        fun parse(raw: String?, fallbackSystemPrompt: String = ""): AgentCard {
            if (raw.isNullOrBlank()) return AgentCard(persona = fallbackSystemPrompt)
            return runCatching {
                val o = JSONObject(raw)
                val d = o.optJSONObject("depthPrompt")
                val s = o.optJSONObject("scaffold")
                AgentCard(
                    schema = o.optInt("schema", 1),
                    persona = o.optString("persona", "").ifBlank { fallbackSystemPrompt },
                    styleExamples = o.optString("styleExamples", ""),
                    postHistory = o.optString("postHistory", ""),
                    depthText = d?.optString("text", "") ?: "",
                    depth = (d?.optInt("depth", 4) ?: 4).coerceIn(0, 200),
                    depthRole = d?.optString("role", "system") ?: "system",
                    scaffoldMarkdown = s?.optBoolean("markdown", true) ?: true,
                    scaffoldComponents = s?.optBoolean("components", true) ?: true,
                    scaffoldCharts = s?.optBoolean("charts", true) ?: true,
                    scaffoldTools = s?.optBoolean("tools", true) ?: true,
                    temperature = if (o.has("temperature") && !o.isNull("temperature")) {
                        o.optDouble("temperature", 0.7)
                    } else null,
                    greeting = o.optString("greeting", ""),
                )
            }.getOrDefault(AgentCard(persona = fallbackSystemPrompt))
        }
    }
}
