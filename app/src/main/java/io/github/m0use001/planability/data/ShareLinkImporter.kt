package io.github.m0use001.planability.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * org.json 的 [JSONArray.keys()] 返回的是裸 [Iterator]，不能直接 `forEach`。
 * 而项目里有个 json 的 stub 实现（单元测试用），签名可能更窄，
 * 所以这里自己遍历，不依赖 forEach 扩展。
 */
private inline fun <T> Iterator<T>.forEachIndexedByMe(action: (T) -> Unit) {
    for (v in this) action(v)
}

/**
 * v24：导入其他 AI 平台的**分享链接**对话。
 *
 * ## 为什么要单独立一个文件（而不是塞进 ToolExec）
 * 这是"解析别人家私有网页结构"的活，特点是：
 * - 对方随时会改版，今天能用三个月后可能失效 —— 所以**每种平台一个独立解析器**，
 *   坏掉只坏一个，其余照常；
 * - 失败必须可解释，不能静默失败。解析不出来时返回带原因的结果，
 *   UI 会把抓到的原文/报错一起显示给用户，而不是"导入失败"四个字。
 *
 * ## 平台现状（2026-10-08 实测口径）
 * - ChatGPT `chatgpt.com/share/<id>`：HTML 里内嵌 `__NEXT_DATA__` 或
 *   `<script type="application/json">`，含完整对话树。**优先读 JSON，失败再退回正则抠正文**。
 * - Claude `claude.ai/share/<id>`：内嵌 `__NEXT_DATA__` 同构。
 * - 其它平台（Gemini / 豆包 / DeepSeek 等）：HTML 结构不保证，
 *   统一走"正文抠取"兜底，并在结果里标注 `degraded = true` 让 UI 提示"按纯文本导入"。
 *
 * **共同点**：这些分享页几乎都是 JS 渲染 + 有反爬，纯 HTTP 抓取能拿到的
 * 常常只有骨架。所以本解析器的设计前提就是"大概率需要退回降级路径"，
 * 而不是假设能一次拿到完美 JSON。
 */
object ShareLinkImporter {

    /** 解析结果 */
    data class Result(
        val title: String,
        /** 已按顺序排好的消息 */
        val messages: List<ParsedMessage>,
        val source: String,
        /** true = 没能拿到结构化数据，是按纯文本抠出来的 */
        val degraded: Boolean,
        /** degraded 时的原因（给用户看） */
        val note: String? = null
    )

    data class ParsedMessage(val role: String, val text: String)

    /** 判断是不是我们支持的分享链接（决定 UI 要不要提示） */
    fun platformOf(url: String): String? {
        val u = url.lowercase().trim()
        return when {
            u.contains("chatgpt.com/share") || u.contains("chat.openai.com/share") -> "ChatGPT"
            u.contains("claude.ai/share") -> "Claude"
            u.contains("gemini.google.com/share") -> "Gemini"
            u.contains("chat.deepseek.com/share") || u.contains("chat.deepseek.com/a/chat/s") -> "DeepSeek"
            else -> null
        }
    }

    /**
     * 入口：把 HTML 原文解析成对话。
     * [url] 只用于标注来源与判断平台，不参与解析（可能带追踪参数）。
     */
    fun parse(url: String, html: String): Result {
        val platform = platformOf(url)
        // 顺序：结构化 JSON（准） → 内嵌状态对象（半准） → 正文抠取（降级）
        nextJsonLd(html)?.let { return it }
        nextData(html)?.let { return it }
        val fallback = scrapeText(html)
        return if (fallback.isEmpty()) {
            Result(
                title = platform?.let { "$it 导入" } ?: "导入的对话",
                messages = emptyList(), source = platform ?: "未知平台",
                degraded = true,
                note = "这个页面没抓到对话内容。可能是需要登录、对方改了页面结构，" +
                    "或者分享链接已失效。可以试试用浏览器打开后复制正文文本。"
            )
        } else {
            Result(
                title = platform?.let { "$it 对话" } ?: "导入的对话",
                messages = fallback.map { ParsedMessage("assistant", it) },
                source = platform ?: "未知平台",
                degraded = true,
                note = "没能拿到结构化对话，已按纯文本导入（每条视作一条消息）。"
            )
        }
    }

    // ---------- 结构化解析 ----------

    /** ChatGPT/Claude 常见：<script type="application/json" id="__NEXT_DATA__">…</script> */
    private fun nextData(html: String): Result? {
        val m = Regex(
            """<script[^>]*id="__NEXT_DATA__"[^>]*>(.*?)</script>""",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
        ).find(html) ?: return null
        val raw = m.groupValues[1]
        if (raw.isBlank()) return null
        return fromNextData(raw)
    }

    /** 部分页面用 ld+json 装正文 */
    private fun nextJsonLd(html: String): Result? {
        val m = Regex(
            """<script[^>]*type="application/ld\+json"[^>]*>(.*?)</script>""",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
        ).find(html) ?: return null
        val raw = m.groupValues[1]
        if (raw.isBlank()) return null
        return runCatching {
            val o = JSONObject(raw)
            val msgs = collectMessages(o)
            if (msgs.isEmpty()) null
            else Result(
                title = o.optString("headline", "导入的对话").take(80),
                messages = msgs, source = platformOf(m.value) ?: "未知平台",
                degraded = false
            )
        }.getOrNull()
    }

    /**
     * 递归找对话数组。
     *
     * 不写死路径是有原因的：ChatGPT 和 Claude 的 `__NEXT_DATA__` 结构完全不同，
     * 而且都在改版。**广度优先找到"像对话的数组"** 比顺着固定路径钻更耐改版。
     */
    private fun fromNextData(raw: String): Result? = runCatching {
        val root = JSONObject(raw)
        val msgs = collectMessages(root)
        if (msgs.isEmpty()) return@runCatching null
        Result(
            title = findTitle(root) ?: "导入的对话",
            messages = msgs,
            source = "分享链接",
            degraded = false
        )
    }.getOrNull()

    private fun collectMessages(root: Any?): List<ParsedMessage> {
        val out = ArrayList<ParsedMessage>()
        val seen = HashSet<String>()   // 分享页常把同一段对话重复嵌在多处，去重
        fun walk(node: Any?, depth: Int) {
            if (node == null || depth > 12 || out.size > 400) return
            when (node) {
                is JSONArray -> for (i in 0 until node.length()) walk(node.opt(i), depth + 1)
                is JSONObject -> {
                    // 单条消息：{role|author, content|text}
                    val role = optStringAny(node, "role", "author", "sender")
                    val text = extractText(node.opt("content") ?: node.opt("text"))
                    if (role != null && text.isNotBlank()) {
                        val k = role + "|" + text.take(80)
                        if (seen.add(k)) out += ParsedMessage(role, text)
                    }
                    node.keys().forEachIndexedByMe { walk(node.opt(it), depth + 1) }
                }
            }
        }
        walk(root, 0)
        // 保持原顺序（递归是 DFS，顺序大体正确；这里只做稳定性排序避免同深度乱序）
        return out.map { it.trim() }
    }

    private fun findTitle(root: Any?): String? {
        var best: String? = null
        fun walk(node: Any?, depth: Int) {
            if (node == null || depth > 8) return
            when (node) {
                is JSONArray -> for (i in 0 until node.length()) walk(node.opt(i), depth + 1)
                is JSONObject -> {
                    for (k in listOf("title", "name", "chatName")) {
                        val v = node.opt(k)
                        if (v is String && v.length in 2..80 && best == null) best = v
                    }
                    node.keys().forEachIndexedByMe { walk(node.opt(it), depth + 1) }
                }
            }
        }
        walk(root, 0)
        return best
    }

    /** content 可能是字符串，也可能是 [{type:"text",text:{text:"…"}}] 这种块数组 */
    private fun extractText(node: Any?): String {
        val sb = StringBuilder()
        fun walk(n: Any?, depth: Int) {
            if (n == null || depth > 6) return
            when (n) {
                is String -> sb.append(n)
                is JSONArray -> for (i in 0 until n.length()) walk(n.opt(i), depth + 1)
                is JSONObject -> {
                    // 跳过明显的非正文（URL、附件 ID 等）
                    val t = optStringAny(n, "text", "content", "value")
                    if (t != null) sb.append(t) else n.keys().forEachIndexedByMe { walk(n.opt(it), depth + 1) }
                }
            }
        }
        walk(node, 0)
        return sb.toString().trim()
    }

    private fun optStringAny(o: JSONObject, vararg keys: String): String? {
        for (k in keys) {
            val v = o.opt(k)
            if (v is String && v.isNotBlank()) return v
        }
        return null
    }

    // ---------- 降级：正文抠取 ----------

    /**
     * 抓不到结构化时的兜底：把 HTML 标签剥掉，取看起来像正文的段落。
     * 明确是**降级**（结果里 degraded=true），UI 会照实告诉用户。
     */
    private fun scrapeText(html: String): List<String> {
        val noScript = html.replace(
            Regex("(?s)<(script|style)[^>]*>.*?</\\1>"), ""
        )
        val paras = Regex("(?is)<p[^>]*>(.*?)</p>")
            .findAll(noScript)
            .map { stripTags(it.groupValues[1]) }
            .map { it.trim() }
            .filter { it.length >= 12 }
            .toList()
        if (paras.isNotEmpty()) return paras.take(200)
        // 没有 <p>（有的站点用 div）→ 整页剥标签后按空行切
        val text = stripTags(noScript)
        return text.split("\n")
            .map { it.trim() }
            .filter { it.length >= 20 }
            .take(200)
    }

    private fun stripTags(s: String): String =
        s.replace(Regex("<[^>]+>"), " ")
            .replace("&nbsp;", " ").replace("&amp;", "&")
            .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
            .replace(Regex("\\s+"), " ")
            .trim()

    private fun ParsedMessage.trim(): ParsedMessage =
        copy(text = text.trim(), role = role.trim().lowercase())

    /** 归一化角色到本项目口径 */
    fun normalizeRole(raw: String): String = when (raw.lowercase()) {
        "user", "human", "提问", "问" -> "user"
        "assistant", "ai", "bot", "gpt", "claude", "gemini", "回答" -> "assistant"
        "system" -> "system"
        else -> "assistant"
    }

    /** 拼出请求 URL（key 不参与，公开页面不需要鉴权） */
    fun fetchUrl(url: String): String = url.trim()
}