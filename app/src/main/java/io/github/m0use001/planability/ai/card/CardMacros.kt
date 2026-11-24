package io.github.m0use001.planability.ai.card

/**
 * v56：角色卡宏展开（SillyTavern 同款占位符）。
 *
 * 为什么必须做：从酒馆社区导入的卡，`mes_example` / `first_mes` / 世界书正文里到处是
 * `{{char}}` / `{{user}}` / `<START>`。不展开的话模型会把占位符当字面文本模仿——
 * 实测表现是回复里直接吐出 `{{char}}: …`，或把「{{user}}」当成某个角色的名字。
 * 这一条不修，任何导入卡都只发挥一半效用（2026-11-15 用户：「我想要 AI 按照某个
 * 二次元人物的人设说话」）。
 *
 * 只做**纯文本替换**：不解析 `{{random}}`、`{{roll}}`、`{{getvar}}` 这类需要求值或
 * 状态的动作宏（SillyTavern 那套是模板引擎，本 App 不做脚本化提示词）。
 * 未识别的 `{{...}}` 原样保留，便于用户看出卡里有什么是没生效的。
 */
object CardMacros {

    /** 角色名缺失时的兜底（空名字会让「{{char}}：」变成「：」）。 */
    const val DEFAULT_CHAR = "助手"

    /** 用户名缺失时的兜底——与 [io.github.m0use001.planability.data.SettingsSnapshot.accountName] 默认值一致。 */
    const val DEFAULT_USER = "我"

    /**
     * `<START>` 是酒馆的示例对话分隔符（不是标签），展开成一条可见分隔线。
     * 保留它在文本里能让模型看出「以下是若干组独立的对话范例」。
     */
    const val START_MARK = "---"

    private val CHAR_RE = Regex("""\{\{\s*char\s*\}\}""", RegexOption.IGNORE_CASE)
    private val USER_RE = Regex("""\{\{\s*user\s*\}\}""", RegexOption.IGNORE_CASE)
    private val START_RE = Regex("""<START>""", RegexOption.IGNORE_CASE)

    /**
     * 展开文本里的宏。
     *
     * @param text 原始卡文本（可为空）
     * @param charName 角色名（空 → [DEFAULT_CHAR]）
     * @param userName 用户称呼（空 → [DEFAULT_USER]）
     */
    fun expand(
        text: String,
        charName: String,
        userName: String = DEFAULT_USER,
    ): String {
        if (text.isEmpty()) return text
        // 没有任何宏就直接返回，避免无谓的字符串重建（每次组装上下文都会走一遍）
        if (!text.contains("{{") && !text.contains("<START>", ignoreCase = true)) return text
        val c = charName.trim().ifBlank { DEFAULT_CHAR }
        val u = userName.trim().ifBlank { DEFAULT_USER }
        return text
            .replace(CHAR_RE, c)
            .replace(USER_RE, u)
            .replace(START_RE, START_MARK)
    }

    /**
     * 是否含**动作类**宏（`{{random::a::b}}` / `{{roll:1d6}}` / `{{getvar::x}}` …）。
     *
     * 我们不做求值，但要在卡编辑页提示用户「这条不生效」，否则用户会以为是坏了。
     */
    fun hasActionMacros(text: String): Boolean {
        if (!text.contains("{{")) return false
        return ACTION_RE.containsMatchIn(text)
    }

    private val ACTION_RE = Regex(
        """\{\{\s*(random|roll|pick|getvar|setvar|getglobalvar|setglobalvar|incvar|decvar|""" +
            """addvar|time|date|idle_duration|input|newline|trim|reverse)\b""",
        RegexOption.IGNORE_CASE
    )

    /** 从卡文本里抽出全部宏名（去重、小写），供编辑页提示「这些宏未生效」。 */
    fun macroNames(text: String): List<String> {
        if (!text.contains("{{")) return emptyList()
        return NAME_RE.findAll(text)
            .map { it.groupValues[1].lowercase() }
            .distinct()
            .toList()
    }

    private val NAME_RE = Regex("""\{\{\s*([a-zA-Z_][a-zA-Z0-9_]*)""")
}
