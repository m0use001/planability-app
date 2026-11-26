package io.github.m0use001.planability.ai

import io.github.m0use001.planability.data.AUTH_API_KEY
import io.github.m0use001.planability.data.ModelConfig
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/** OpenAI 兼容消息（支持工具调用往返） */
data class ChatApiMessage(
    val role: String,
    val content: String = "",
    /** assistant 消息的工具调用 JSON：[{"id","type","function":{"name","arguments"}}] */
    val toolCallsJson: String = "",
    /** role=tool 消息对应的调用 id */
    val toolCallId: String = "",
    /**
     * assistant 的思考内容（DeepSeek 等思考模型的 `reasoning_content`）。
     *
     * **思考模型 + 工具调用时必须原样回传**：DeepSeek 在工具轮之后会校验上一轮
     * assistant 是否带回了 reasoning_content，不带直接 400：
     * "The `reasoning_content` in the thinking mode must be passed back to the API"。
     * 非思考模型这条为空，序列化时会整段省略。
     */
    val reasoning: String = ""
)

/**
 * 2026-11-10 图片直通：user 消息里引用的附件图片 → 多模态 part。
 * 旧附件标记里的 OCR 指令在直通时改写（图已直接附上，别再逼模型走一遍 OCR 工具）；
 * 剥图降级时保留原指令，模型仍可调 file_read_image 走 OCR。
 */
private val IMAGE_TOOL_HINT = Regex("""\s*请用\s+file_read_image\s+读取""")
private const val IMAGE_ATTACHED_NOTE =
    "（图片已随消息直接附上，可直接查看；如需提取文字可调 file_read_image）"

internal fun toApiMessages(
    messages: List<ChatApiMessage>,
    /** DeepSeek 系（见 [echoEmptyReasoning]）：tool 轮缺 reasoning 时补空串而不是省略字段 */
    echoEmptyReasoning: Boolean = false,
    /** 附件路径 → data URL（注入以便单测；真实实现走 ImageAttach） */
    imageLoader: (String) -> String? = { ImageAttach.dataUrl(it) },
    /** 降级链：端点拒绝图片时剥图重发用 */
    includeImages: Boolean = true
): JSONArray {
    val arr = JSONArray()
    messages.forEach { m ->
        val o = JSONObject().put("role", m.role)
        when {
            m.role == "assistant" && m.toolCallsJson.isNotBlank() -> {
                if (m.content.isNotBlank()) o.put("content", m.content) else o.put("content", JSONObject.NULL)
                o.put("tool_calls", JSONArray(m.toolCallsJson))
                // 思考模型的要求：带工具调用的 assistant 必须把 reasoning 一起发回。
                // 2026-10-31 真机又复现一次（会话 287）：模型这一轮**发起了工具调用但没产出
                // reasoning_content**（库里 reasoning 长度 0），我们按"blank 就省略"发出去 →
                // 下一轮 400 "The `reasoning_content` in the thinking mode must be passed back"。
                // 实测 DeepSeek 对 `reasoning_content: ""` 是接受的（省略会 400、空串 200），
                // 所以对 DeepSeek 系补空串；其它厂商保持省略（不塞未知字段）。
                if (m.reasoning.isNotBlank()) o.put("reasoning_content", m.reasoning)
                else if (echoEmptyReasoning) o.put("reasoning_content", "")
            }
            m.role == "tool" -> {
                // 2026-11-11 工具结果图片直通：file_read_image 读到的（PPT 每页快照/截图）如果
                // 结果文本里引用了 attachments/exports 图片路径，把图一并喂给视觉模型——
                // 「读 PPT 拿到每页整体截屏」的最后一步：OCR 兜底之外，视觉模型直接看页。
                // 只取最近一条 tool 消息的引用（历史 tool 轮的图重发只会撑爆请求体），且上限 2 张。
                val isLatestTool = includeImages && m === messages.lastOrNull { it.role == "tool" }
                val refs = if (isLatestTool) ImageAttach.imageRefsIn(m.content).take(2) else emptyList()
                val dataUrls = refs.mapNotNull { imageLoader(it) }
                if (dataUrls.isNotEmpty()) {
                    val parts = JSONArray().put(JSONObject().put("type", "text").put("text", m.content))
                    dataUrls.forEach { url ->
                        parts.put(
                            JSONObject().put("type", "image_url")
                                .put("image_url", JSONObject().put("url", url))
                        )
                    }
                    o.put("content", parts)
                } else {
                    o.put("content", m.content)
                }
                o.put("tool_call_id", m.toolCallId)
            }
            else -> {
                // 2026-11-10 图片直通：user 消息正文引用的 attachments 图片 → image_url part
                val refs = if (m.role == "user" && includeImages) ImageAttach.imageRefsIn(m.content) else emptyList()
                val dataUrls = refs.mapNotNull { imageLoader(it) }
                if (dataUrls.isNotEmpty()) {
                    val text = IMAGE_TOOL_HINT.replace(m.content, IMAGE_ATTACHED_NOTE)
                    val parts = JSONArray().put(JSONObject().put("type", "text").put("text", text))
                    dataUrls.forEach { url ->
                        parts.put(
                            JSONObject().put("type", "image_url")
                                .put("image_url", JSONObject().put("url", url))
                        )
                    }
                    o.put("content", parts)
                } else {
                    o.put("content", m.content)
                }
                // 思考模式下**普通 assistant 消息也要回传 reasoning_content**。
                // 旧代码只给"带工具调用"的分支加了这一行，于是：第一轮带思考的回答
                // → 第二轮请求里它变成一条没有 reasoning_content 的 assistant 历史
                // → 服务端直接 400 "The `reasoning_content` in the thinking mode must be
                // passed back to the API"（真机 2026-10-20 用 glm-5.3-flash 复现）。
                // 只在这个字段非空时写：非思考模型它本来就是空，不会多出未知字段。
                if (m.role == "assistant" && m.reasoning.isNotBlank()) {
                    o.put("reasoning_content", m.reasoning)
                }
            }
        }
        arr.put(o)
    }
    return arr
}

/**
 * `finish_reason` → 给用户看的错误文案（null = 正常结束，不用报）。
 *
 * 这个函数存在的唯一理由是**可测**：以前整条链路根本没看 `finish_reason`，
 * 模型被 `max_tokens` 截断时消息仍被写成 `status=done`（真机 2026-10-20：
 * 流程图一个字都没出来，库里那条 assistant 27 个字、fenceCount=0、status=done）。
 * 截断必须是**用户可见的失败**，不能是静默的成功。
 */
internal fun finishReasonError(reason: String?): String? = when (reason) {
    "length", "max_tokens" ->
        "输出被截断：模型到达输出上限（finish_reason=$reason）。" +
            "这次生成不完整，可点「重新生成」，或把要求拆小。"
    "content_filter" -> "输出被安全策略截断（finish_reason=content_filter）。"
    else -> null
}

/** Anthropic 端点：Base URL 带 /v1 则直接拼 /messages，否则补 /v1 */internal fun anthropicUrl(baseUrl: String): String {
    val b = baseUrl.trimEnd('/')
    return if (b.endsWith("/v1")) "$b/messages" else "$b/v1/messages"
}

/** OpenAI tools 定义 → Anthropic tools（name/description/input_schema） */
internal fun toAnthropicTools(tools: JSONArray): JSONArray {
    val out = JSONArray()
    for (i in 0 until tools.length()) {
        val f = tools.optJSONObject(i)?.optJSONObject("function") ?: continue
        out.put(
            JSONObject()
                .put("name", f.optString("name"))
                .put("description", f.optString("description"))
                .put("input_schema", f.optJSONObject("parameters") ?: JSONObject())
        )
    }
    return out
}

/**
 * OpenAI 消息序列 → (system, Anthropic messages)。
 * system 提到顶层；assistant.tool_calls → tool_use 块（arguments 宽松解析，坏 JSON 落空对象）；
 * role=tool → user 消息里的 tool_result 块；相邻同角色消息合并（Anthropic 不允许连续同角色）。
 */
internal fun toAnthropicMessages(
    messages: List<ChatApiMessage>,
    imageLoader: (String) -> String? = { ImageAttach.dataUrl(it) },
    includeImages: Boolean = true
): Pair<String, JSONArray> {
    val system = StringBuilder()
    val out = ArrayList<JSONObject>()

    fun blocksOf(role: String, blocks: JSONArray) {
        val last = out.lastOrNull()
        if (last != null && last.optString("role") == role) {
            val arr = last.getJSONArray("content")
            for (i in 0 until blocks.length()) arr.put(blocks.get(i))
        } else {
            out.add(JSONObject().put("role", role).put("content", blocks))
        }
    }
    fun textBlock(s: String) = JSONObject().put("type", "text").put("text", s)

    messages.forEach { m ->
        when (m.role) {
            "system" -> {
                if (m.content.isNotBlank()) {
                    if (system.isNotEmpty()) system.append("\n\n")
                    system.append(m.content)
                }
            }
            "assistant" -> {
                val blocks = JSONArray()
                if (m.content.isNotBlank()) blocks.put(textBlock(m.content))
                if (m.toolCallsJson.isNotBlank()) {
                    val calls = runCatching { JSONArray(m.toolCallsJson) }.getOrNull()
                    if (calls != null) {
                        for (i in 0 until calls.length()) {
                            val c = calls.optJSONObject(i) ?: continue
                            val fn = c.optJSONObject("function") ?: continue
                            // 2026-11-05：arguments 走 JsonRepair（坏 JSON 不再静默落空对象）
                            val input = JsonRepair.parseObject(fn.optString("arguments", "{}"))
                                ?: JSONObject()
                            blocks.put(
                                JSONObject().put("type", "tool_use")
                                    .put("id", c.optString("id", "call_$i"))
                                    .put("name", fn.optString("name"))
                                    .put("input", input)
                            )
                        }
                    }
                }
                if (blocks.length() > 0) blocksOf("assistant", blocks)
            }
            "tool" -> {
                val block = JSONObject().put("type", "tool_result").put("tool_use_id", m.toolCallId)
                if (m.content.isNotBlank()) block.put("content", m.content)
                blocksOf("user", JSONArray().put(block))
            }
            else -> {
                // 2026-11-10 图片直通：attachments 图片 → Anthropic image 块
                val refs = if (m.role == "user" && includeImages) ImageAttach.imageRefsIn(m.content) else emptyList()
                if (refs.isNotEmpty() || m.content.isNotBlank()) {
                    val blocks = JSONArray()
                    val text =
                        if (refs.any { imageLoader(it) != null }) IMAGE_TOOL_HINT.replace(m.content, IMAGE_ATTACHED_NOTE)
                        else m.content
                    if (text.isNotBlank()) blocks.put(textBlock(text))
                    refs.forEach { ref ->
                        val url = imageLoader(ref) ?: return@forEach
                        val (mime, b64) = ImageAttach.splitDataUrl(url) ?: return@forEach
                        blocks.put(
                            JSONObject().put("type", "image")
                                .put(
                                    "source",
                                    JSONObject().put("type", "base64")
                                        .put("media_type", mime).put("data", b64)
                                )
                        )
                    }
                    if (blocks.length() > 0) blocksOf("user", blocks)
                }
            }
        }
    }
    val arr = JSONArray()
    out.forEach { arr.put(it) }
    return system.toString() to arr
}

/** 流式增量事件 */
sealed interface StreamEvent {
    /** 正文增量 */
    data class Content(val text: String) : StreamEvent

    /** 思考过程增量（reasoning_content） */
    data class Reasoning(val text: String) : StreamEvent

    /** 工具调用增量（按 index 分片拼接） */
    data class ToolCallDelta(
        val index: Int,
        val id: String?,
        val name: String?,
        val argsFragment: String?
    ) : StreamEvent

    /** 服务端 usage（部分端点在收尾 chunk 下发；缺失时保持 0）。
     *  v40（成本优化 1.4）：cachedTokens = 前缀缓存命中的输入 token。
     *  **"unknown ≠ 0"**（学 rikkahub-agent-pure 账本）：端点没上报就是 null，
     *  绝不能记成 0 —— 否则"没上报"会污染缓存命中率的统计。 */
    data class Usage(
        val tokensIn: Int,
        val tokensOut: Int,
        val cachedTokens: Int? = null
    ) : StreamEvent

    /** 流正常结束 */
    data object Completed : StreamEvent

    /**
     * 2026-11-14：服务端联网搜索的来源（DeepSeek 全家桶应用 API）。
     *
     * 与本地工具轮的 ToolCallDelta 是**两套东西**：服务端工具在 DeepSeek 那边执行，
     * 客户端只收到 `server_tool_calls`/`annotations` 增量（模拟器真探针实录形状见
     * DeepSeekSources 文件头）。这里把合并去重后的来源推给上层，用于：
     * ①消息末尾附来源列表（用户能看到 AI 引了哪几篇）；②「深度思考搜索」轨迹展示。
     * 全部开关关时不发（请求里也没有那两个 tool），行为与上云前完全一致。
     */
    data class Sources(
        val list: List<io.github.m0use001.planability.shared.DeepSeekSources.Source>,
        val traces: List<io.github.m0use001.planability.shared.DeepSeekSources.Trace> = emptyList()
    ) : StreamEvent

    /** 失败（HTTP 错误/网络错误/解析中断） */
    data class Failed(val message: String) : StreamEvent
}

/**
 * v54 思考档位（五档）：off 关 / '' 自适应 / low·high·max 思考力度。
 *
 * 2026-11-06 对 DeepSeek 官方 API 真探针实测：`GET /models` 声明
 * `effort.supported_levels = [low, high, max]`（默认 high），`reasoning_effort` 三档在
 * 非流式与流式下均被接受且行为不同；`thinking:{"type":"disabled"/"enabled"}` 也被
 * 接受（disabled → reasoning_content 为空、completion_tokens=1）。
 * 力度档只对支持 effort 的端点下发 `reasoning_effort`；其它 openai 兼容端点
 * **退化为 thinking enabled**（等于旧「开」）——入口常驻不隐藏，语义不丢。
 * 旧值 "on"（v8 三档的「开」）继续兼容：仍发 thinking enabled。
 */
object ThinkingLevels {
    /** 滑条/芯片顺序：关 → 自适应 → 力度三档 */
    val ALL = listOf("off", "", "low", "high", "max")

    /** 展示名；low/high/max 直接用 DeepSeek 的档名原文（与 API 术语一致） */
    fun label(level: String): String = when (level) {
        "off" -> "关"
        "on" -> "开" // 旧三档残留值，只在展示层兼容
        "" -> "自适应"
        else -> level
    }

    /** 是否对该端点下发 reasoning_effort（2026-11-06 只实测过 DeepSeek 官方，其余先退化） */
    fun supportsEffort(baseUrl: String): Boolean = baseUrl.contains("deepseek", ignoreCase = true)

    /** 滑条落位；旧 "on" 语义=默认力度（DeepSeek 默认 effort=high）→ 落在 high */
    fun sliderIndex(level: String): Int =
        ALL.indexOf(if (level == "on") "high" else level).coerceAtLeast(0)
}

/** 把思考档位翻译进 openai 兼容请求体（档位→参数契约钉在这，可单测） */
internal fun applyOpenAiThinking(b: JSONObject, thinking: String, baseUrl: String) {
    // v54 思考档位五档：off 关 / '' 自适应（不发参数）/ low·high·max 力度。
    // 力度档只对支持 effort 的端点（DeepSeek 官方）发 reasoning_effort，
    // 其它 openai 兼容端点退化为 thinking enabled（=旧「开」，GLM 认识；MiMo 忽略未知字段）。
    when (thinking) {
        "off" -> b.put("thinking", JSONObject().put("type", "disabled"))
        "on" -> b.put("thinking", JSONObject().put("type", "enabled"))
        "low", "high", "max" ->
            if (ThinkingLevels.supportsEffort(baseUrl)) b.put("reasoning_effort", thinking)
            else b.put("thinking", JSONObject().put("type", "enabled"))
    }
}

/** Anthropic 侧：扩展思考只在显式要思考时开（力度档退化，Anthropic 无 effort 概念）；自适应/关=不发（默认不思考） */
internal fun applyAnthropicThinking(b: JSONObject, thinking: String) {
    if (thinking != "" && thinking != "off")
        b.put("thinking", JSONObject().put("type", "extended").put("budget_tokens", 3072))
}

/**
 * OpenAI 兼容 SSE transport：POST /chat/completions stream=true，
 * 逐行读 `data: {chunk}` / `data: [DONE]`，映射为 [StreamEvent]（含 tool_calls 增量）。
 * 取消 = collector 取消 → okhttp call.cancel()（awaitClose）。
 */
object SseTransport {

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(300, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    /** 发起流式对话。tools 非空时携带 function calling 定义；thinking: off/on/''/low/high/max（见 [ThinkingLevels]，v8 起，v54 扩力度档）
     *  exportDir：本会话导出目录（ImageAttach.dataUrl 解析 exports/ 虚拟前缀用，见该函数注释） */
    fun streamChat(
        cfg: ModelConfig,
        messages: List<ChatApiMessage>,
        temperature: Double = 0.7,
        tools: JSONArray? = null,
        thinking: String = "",
        exportDir: java.io.File? = null,
        /**
         * 2026-11-14 会话级「联网」开关（四象限：思考档 × 联网）。
         * null = 沿用全局云开关（cloudSearchEnabled / cloudFetchEnabled）；
         * true = 本次会话强制带服务端联网工具；false = 本次会话不带。
         * 只有 DeepSeek 端点且最终为 true 时才真的往 tools 里塞。
         */
        webSearch: Boolean? = null
    ): Flow<StreamEvent> = callbackFlow {
        val c = cfg
        // DeepSeek 系的思考模式对 tool 轮校验最严（见 toApiMessages 注释）：缺 reasoning 就 400
        val echoEmptyReasoning = c.baseUrl.contains("deepseek", ignoreCase = true)
        if (!c.enabled || c.baseUrl.isBlank() || c.modelName.isBlank()) {
            trySend(StreamEvent.Failed("模型未配置（我的 → 设置 → 模型管理）"))
            close()
            return@callbackFlow
        }
        val anthropic = c.apiFormat == "anthropic"
        val url = if (anthropic) anthropicUrl(c.baseUrl)
        else c.baseUrl.trimEnd('/') + "/chat/completions"
        // 诊断（2026-11-05）：端侧引擎报 `Input prompt too long` 时，先看这里——
        // 实际发出的消息条数与字符数，能立刻分出"输入真超长"还是"服务端模型名/参数不对"。
        android.util.Log.d(
            "AiStream",
            "→ $url model=${c.modelName} msgs=${messages.size} chars=${messages.sumOf { it.content.length }}"
        )
        // 2026-11-10 图片直通：user 消息里带 attachments 图片引用 = 这次请求含图
        val hadImages = messages.any { it.role == "user" && ImageAttach.imageRefsIn(it.content).isNotEmpty() }

        fun buildBody(stripImages: Boolean): JSONObject {
            // 2026-11-11：exports/ 虚拟前缀要在有会话目录的调用侧解析（见 ImageAttach.dataUrl）
            val imgLoader: (String) -> String? =
                if (exportDir != null) ({ rel -> ImageAttach.dataUrl(rel, exportDir) })
                else ({ ImageAttach.dataUrl(it) })
            return if (anthropic) {
                val b = JSONObject()
                    .put("model", c.modelName)
                    .put("stream", true)
                    .put("max_tokens", 8192)
                    .put("temperature", temperature)
                val (sysText, msgs) = toAnthropicMessages(messages, imageLoader = imgLoader, includeImages = !stripImages)
                if (sysText.isNotBlank()) b.put("system", sysText)
                b.put("messages", msgs)
                if (tools != null && tools.length() > 0) b.put("tools", toAnthropicTools(tools))
                applyAnthropicThinking(b, thinking)
                b
            } else {
                val b = JSONObject()
                    .put("model", c.modelName)
                    .put("stream", true)
                    .put("temperature", temperature)
                    .put("stream_options", JSONObject().put("include_usage", true))
                    .put(
                        "messages",
                        toApiMessages(messages, echoEmptyReasoning = echoEmptyReasoning,
                            imageLoader = imgLoader, includeImages = !stripImages)
                    )
                if (tools != null && tools.length() > 0) b.put("tools", tools)
                // 2026-11-14：DeepSeek 服务端工具透传（全家桶应用 API）——云能力开且
                // 当前就是 DeepSeek 端点时，把 deepseek_web_search / deepseek_read_url 塞进
                // tools 数组（type-only）。执行发生在 DeepSeek 服务端（引用随响应下发），
                // 不进本地 24 轮工具循环；开关关时一个字节都不多传。
                //
                // 会话级开关优先（webSearch != null）：用户在会话里显式选了「联网」/「不联网」，
                // 就不能被全局默认悄悄覆盖——这是「四象限」可预测性的基础。
                if (echoEmptyReasoning) {
                    val serverTools =
                        if (webSearch == false) emptyList()
                        else if (webSearch == true) {
                            // 显式联网：搜索工具必定带；读链接跟随它的全局开关（用户可能只想要搜索）
                            buildList {
                                add("deepseek_web_search")
                                if (io.github.m0use001.planability.ai.ptl.DeepSeekCloud.fetchEnabled()) {
                                    add("deepseek_read_url")
                                }
                            }
                        } else io.github.m0use001.planability.ai.ptl.DeepSeekCloud.serverToolTypes()
                    if (serverTools.isNotEmpty()) {
                        val arr = b.optJSONArray("tools") ?: org.json.JSONArray().also { b.put("tools", it) }
                        for (t in serverTools) arr.put(org.json.JSONObject().put("type", t))
                    }
                }
                applyOpenAiThinking(b, thinking, c.baseUrl)
                b
            }
        }

        fun buildRequest(body: JSONObject): Request {
            val builder = Request.Builder().url(url)
                .header("Accept", "text/event-stream")
                .post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            if (c.apiKey.isNotBlank()) {
                if (anthropic) {
                    builder.header("x-api-key", c.apiKey)
                    builder.header("anthropic-version", "2023-06-01")
                } else if (c.authStyle == AUTH_API_KEY) builder.header("api-key", c.apiKey)
                else builder.header("Authorization", "Bearer ${c.apiKey}")
            }
            return builder.build()
        }

        // 降级链（2026-11-10 图片直通配套）：端点拒绝带图请求（HTTP 4xx，或 SSE error 帧）
        // 且还没吐过任何输出 → 剥掉图片重发一次；剥图后正文里的「请用 file_read_image 读取」
        // 指令原样保留，模型自己走 OCR 兜底。纯文本请求行为不变（hadImages=false 不重试）。
        var gotOutput = false
        var strippedRetryUsed = false
        val currentCall = java.util.concurrent.atomic.AtomicReference<Call?>(null)

        // 2026-11-09 真机事故「已工作939秒」：用户重试后，工具 16:27:07 失败 → 模型流
        // 16:42:42 才出第一个字，中间空 15 分钟。okhttp readTimeout=300s 是**字节间**超时，
        // SSE 服务器发 keepalive（`: ping` 这类心跳行）就能让 readUtf8Line 一直不超时；
        // 模型真卡住时连接「活着」却没内容，引擎干等到天荒地老。
        // 看门狗：只认**真实 data 帧**为进展（心跳/空行不算），idle > 阈值就 cancel + 报错。
        val lastProgress = java.util.concurrent.atomic.AtomicLong(System.currentTimeMillis())
        val watchdog = launch {
            val thresholdMs = 120_000L
            while (true) {
                delay(5_000)
                val idle = System.currentTimeMillis() - lastProgress.get()
                if (idle > thresholdMs) {
                    currentCall.get()?.cancel()
                    this@callbackFlow.trySend(StreamEvent.Failed(
                        "已 ${(idle / 1000)} 秒没有任何模型输出（服务器无响应），已自动中断——请重试一次"
                    ))
                    this@callbackFlow.close()
                    break
                }
            }
        }

        fun fire(body: JSONObject) {
            val call = client.newCall(buildRequest(body))
            currentCall.set(call)
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (!call.isCanceled()) {
                        trySend(StreamEvent.Failed("网络错误：${e.message ?: "连接失败"}"))
                    }
                    close()
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use { resp ->
                        if (!resp.isSuccessful) {
                            val errText = runCatching { resp.body?.string() }.getOrNull().orEmpty()
                            if (hadImages && !strippedRetryUsed && !gotOutput && resp.code in 400..499) {
                                strippedRetryUsed = true
                                android.util.Log.w("AiStream", "端点拒绝带图请求(${resp.code})，剥图重发")
                                fire(buildBody(stripImages = true))
                                return
                            }
                            trySend(StreamEvent.Failed("模型接口错误 ${resp.code}：${errText.take(300)}"))
                            close()
                            return
                        }
                    val source = resp.body?.source()
                    if (source == null) {
                        trySend(StreamEvent.Failed("响应为空"))
                        close()
                        return
                    }
                    try {
                        var done = false
                        var anthropicInputTok = 0
                        // 2026-11-14 服务端搜索来源累积（收尾统一推一次 Sources）
                        var serverSources = io.github.m0use001.planability.shared.DeepSeekSources.Result()
                        while (!done) {
                            val line = source.readUtf8Line() ?: break
                            if (call.isCanceled()) break
                            val trimmed = line.trim()
                            if (trimmed.isEmpty() || trimmed.startsWith(":")) continue
                            if (!trimmed.startsWith("data:")) continue
                            lastProgress.set(System.currentTimeMillis())   // 真实帧=进展（心跳行已 continue 跳过）
                            val payload = trimmed.removePrefix("data:").trim()
                            if (payload == "[DONE]") {
                                done = true
                                break
                            }
                            val chunk = JSONObject(payload)
                            if (anthropic) {
                                when (chunk.optString("type")) {
                                    "message_start" -> anthropicInputTok =
                                        chunk.optJSONObject("message")?.optJSONObject("usage")?.optInt("input_tokens", 0) ?: 0
                                    "content_block_start" -> {
                                        val blk = chunk.optJSONObject("content_block")
                                        if (blk != null && blk.optString("type") == "tool_use") {
                                            trySend(
                                                StreamEvent.ToolCallDelta(
                                                    index = chunk.optInt("index", 0),
                                                    id = blk.optString("id").takeIf { it.isNotEmpty() },
                                                    name = blk.optString("name").takeIf { it.isNotEmpty() },
                                                    argsFragment = null
                                                )
                                            )
                                        }
                                    }
                                    "content_block_delta" -> {
                                        val d = chunk.optJSONObject("delta")
                                        val idx = chunk.optInt("index", 0)
                                        when (d?.optString("type")) {
                                            "text_delta" -> d.optString("text").takeIf { it.isNotEmpty() }
                                                ?.let { gotOutput = true; trySend(StreamEvent.Content(it)) }
                                            "thinking_delta" -> d.optString("thinking").takeIf { it.isNotEmpty() }
                                                ?.let { gotOutput = true; trySend(StreamEvent.Reasoning(it)) }
                                            "input_json_delta" -> d.optString("partial_json").takeIf { it.isNotEmpty() }
                                                ?.let { gotOutput = true; trySend(StreamEvent.ToolCallDelta(idx, null, null, it)) }
                                        }
                                    }
                                    "message_delta" -> {
                                        val u = chunk.optJSONObject("usage")
                                        trySend(
                                            StreamEvent.Usage(
                                                anthropicInputTok,
                                                u?.optInt("output_tokens", 0) ?: 0,
                                                // Anthropic：cache_read_input_tokens = 前缀缓存命中的输入 token
                                                u?.optInt("cache_read_input_tokens", -1)?.takeIf { it >= 0 }
                                            )
                                        )
                                    }
                                    "error" -> trySend(
                                        StreamEvent.Failed(
                                            "模型接口错误：" + (chunk.optJSONObject("error")?.optString("message") ?: "未知错误")
                                        )
                                    )
                                }
                                continue
                            }
                            // OpenAI 兼容系也可能在 SSE 里夹带 error 对象（真机实测：端侧 GenieX/Qwen3
                            // 输入超上下文时回 `data: {"error":{"message":"LLM generate failed: Input
                            // prompt too long"}}` 再 `[DONE]`）。旧实现只读 usage/choices，这种帧被整块
                            // 忽略 → 流"正常结束"、正文空串、消息状态 done——提纯结果静默为空。
                            chunk.optJSONObject("error")?.let { err ->
                                val msg = "模型接口错误：" + (err.optString("message").ifBlank { "未知错误" })
                                if (hadImages && !strippedRetryUsed && !gotOutput) {
                                    strippedRetryUsed = true
                                    android.util.Log.w("AiStream", "端点报错且尚无输出（可能拒图），剥图重发：$msg")
                                    fire(buildBody(stripImages = true))
                                    return
                                }
                                trySend(StreamEvent.Failed(msg))
                                done = true
                                return@let
                            }
                            chunk.optJSONObject("usage")?.let { usage ->
                                val tin = usage.optInt("prompt_tokens", 0)
                                val tout = usage.optInt("completion_tokens", 0)
                                // v40（成本优化 1.4）：OpenAI 兼容系两种上报形态——
                                // 标准：prompt_tokens_details.cached_tokens；DeepSeek：prompt_cache_hit_tokens。
                                // 没上报保持 null（unknown ≠ 0）。
                                val cached = usage.optJSONObject("prompt_tokens_details")
                                    ?.optInt("cached_tokens", -1)?.takeIf { it >= 0 }
                                    ?: usage.optInt("prompt_cache_hit_tokens", -1).takeIf { it >= 0 }
                                if (tin > 0 || tout > 0) trySend(StreamEvent.Usage(tin, tout, cached))
                            }
                            val choices = chunk.optJSONArray("choices")
                            if (choices != null && choices.length() > 0) {
                                val choice = choices.getJSONObject(0)
                                val delta = choice.optJSONObject("delta")
                                if (delta != null) {
                                    // org.json 的 optString 会把 JSON null 读成 "null" 字面量（MiMo 会发 content:null）
                                    fun optText(key: String): String? {
                                        val v = delta.opt(key) ?: return null
                                        if (v === JSONObject.NULL) return null
                                        val s = v.toString()
                                        return s.takeIf { it.isNotEmpty() }
                                    }
                                    optText("reasoning_content")?.let { gotOutput = true; trySend(StreamEvent.Reasoning(it)) }
                                    optText("content")?.let { gotOutput = true; trySend(StreamEvent.Content(it)) }
                                    // 2026-11-14：服务端联网搜索的来源/轨迹（DeepSeek 全家桶应用 API）。
                                    // 形状见 DeepSeekSources 文件头（模拟器真探针实录）；坏 JSON 不抛，
                                    // 来源是辅助信息，解析失败不能把整条回复带崩。
                                    runCatching {
                                        io.github.m0use001.planability.shared.DeepSeekSources.parseDelta(delta)
                                    }.getOrNull()?.let { r ->
                                        gotOutput = true
                                        serverSources = io.github.m0use001.planability.shared.DeepSeekSources
                                            .merge(serverSources, r)
                                    }
                                    delta.optJSONArray("tool_calls")?.let { calls ->
                                        gotOutput = true
                                        // org.json 的 optString 同样会把 JSON null 读成 "null" 字面量
                                        // （mimo 工具调用增量里 name/arguments 常为 null，曾拼出 "data_historynullnull"）
                                        fun safeText(obj: JSONObject?, key: String): String? {
                                            val v = obj?.opt(key) ?: return null
                                            if (v === JSONObject.NULL) return null
                                            return v.toString().takeIf { it.isNotEmpty() }
                                        }
                                        for (i in 0 until calls.length()) {
                                            val tc = calls.optJSONObject(i) ?: continue
                                            val fn = tc.optJSONObject("function")
                                            trySend(
                                                StreamEvent.ToolCallDelta(
                                                    index = tc.optInt("index", 0),
                                                    id = safeText(tc, "id"),
                                                    name = safeText(fn, "name"),
                                                    argsFragment = safeText(fn, "arguments")
                                                )
                                            )
                                        }
                                    }
                                }
                                // finish_reason 以前**完全没被处理**：模型被 max_tokens 截断时
                                // 我们照样把消息写成 status=done，用户只看到"话说到一半没了"，
                                // 没有任何错误提示。真机事故 2026-10-20：流程图一个字都没出来，
                                // 库里那条消息 27 个字 + fenceCount=0 + status=done（查备份库确认）。
                                val finish = choice.opt("finish_reason")
                                if (finish != null && finish !== JSONObject.NULL) {
                                    finishReasonError(finish.toString())?.let {
                                        trySend(StreamEvent.Failed(it))
                                    }
                                }
                            }
                        }
                        // 2026-11-14：服务端搜索来源在收尾统一推一次（流式期间只累积不打扰，
                        // 上层收到后附到消息末尾）。空结果不发 = 没开云搜索时零变化。
                        if (!serverSources.isEmpty) {
                            trySend(StreamEvent.Sources(serverSources.sources, serverSources.traces))
                        }
                        trySend(StreamEvent.Completed)
                    } catch (e: IOException) {
                        if (!call.isCanceled()) {
                            trySend(StreamEvent.Failed("连接中断：${e.message ?: "读取失败"}"))
                        }
                    } catch (e: Exception) {
                        trySend(StreamEvent.Failed("解析失败：${e.message ?: "未知错误"}"))
                    } finally {
                        close()
                    }
                }
            }
        })
        } // fun fire

        fire(buildBody(stripImages = false))

        awaitClose { currentCall.get()?.cancel() }
    }.buffer(Channel.UNLIMITED)
}
