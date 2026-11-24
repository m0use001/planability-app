package io.github.m0use001.planability.ai

import io.github.m0use001.planability.data.Graph
import io.github.m0use001.planability.data.AUTH_API_KEY
import io.github.m0use001.planability.data.ModelConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

class AiException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * OpenAI 兼容 chat/completions 客户端（云端，用户自填地址/Key/模型名）。
 * 周小结、信息提纯、日程/待办抽取、AI 会话自动命名共用。
 * 双槽：main = 对话/工具，worker = 摘要/命名等轻任务（AiStream/SseTransport 走流式）。
 */
object AiClient {

    /** v7：主模型 = 供应商表 active 模型；active 未配置时先回退首个启用模型，再回退旧 settings.model */
    val config: ModelConfig
        get() = Graph.aiProviders.activeConfig() ?: Graph.aiProviders.fallbackConfig() ?: Graph.settings.snapshot.value.model

    /** 轻任务/智能体模型：workerModel 指定，未设置回退主模型 */
    val workerConfig: ModelConfig
        get() = Graph.aiProviders.workerConfig() ?: config

    val configured: Boolean get() = config.enabled && config.baseUrl.isNotBlank() && config.modelName.isNotBlank()

    fun configFor(slot: String): ModelConfig =
        if (slot == "worker") workerConfig else config

    /** worker 优先（已含回退逻辑）。
     *  ⚠️ 这是**配置视角**：只保证"有配置"，不保证"端点活着"。后台类轻任务请用
     *  [usableWorkerConfig]（本地端点没起来时会回退主模型并打日志）。 */
    fun namingConfig(): ModelConfig = workerConfig

    /**
     * v45：worker 槽的**可用性**兜底（修"提纯结果全空"）。
     *
     * ## 问题
     * worker 槽指向本机端点（`http://127.0.0.1:18181/v1` 的端侧 NPU）时，只要引擎没拉起，
     * 每个请求都是 TCP 直接拒绝；而 provider 行仍是 `enabled`，[workerConfig] 只看配置，
     * 于是判定"能用"→ 后台提纯每次都失败：占位消息留空、`chat_digests.digest` 仍是空串，
     * 用户看到的是"提纯过了但什么都没出来"（2026-10-24 实测 269 条全空、242 个空会话）。
     *
     * ## 做法
     * 只对 **loopback** 地址做一次轻量 TCP 连接探测（250ms 超时；结果缓存 30s，不碰 HTTP 层），
     * 探不到就回退主模型并打一条 W 日志。远程地址不探测：多一次往返不划算，远端异常本来就会在
     * 请求里报错。
     */
    suspend fun usableWorkerConfig(): ModelConfig {
        val w = workerConfig
        if (!w.enabled || w.baseUrl.isBlank()) return config
        if (localEndpointAlive(w.baseUrl)) {
            // 2026-11-05 真机：本机引擎升级后模型改名（库里 worker 还写着 unsloth/Qwen3.5-2B-GGUF，
            // GenieX 实际只服务 ai-hub-models/Qwen3-4B）→ 服务端回的是**误导性的**
            // `LLM generate failed: Input prompt too long`（哪怕输入只有几百字），
            // 表现为「提纯全空」。这里对齐一次 /v1/models，名字不一致就用服务端的名字。
            val served = servedModelName(w.baseUrl)
            if (served != null && served.isNotBlank() && served != w.modelName) {
                android.util.Log.w(
                    "AiClient",
                    "worker 模型名 ${w.modelName} 不在本机端点服务列表（$served）→ 改用 $served"
                )
                return w.copy(modelName = served)
            }
            return w
        }
        android.util.Log.w(
            "AiClient",
            "worker 端点不可达（${w.baseUrl}，模型 ${w.modelName}）→ 本次改用主模型 ${config.modelName}"
        )
        return config
    }

    /**
     * 本机端点的实际可服务模型名（5 分钟缓存；只对 loopback 发请求，非本机返回 null）。
     * 取 `/v1/models` 的第一条 id——端侧只有一个已加载模型。
     */
    suspend fun servedModelName(baseUrl: String): String? = withContext(Dispatchers.IO) {
        val url = runCatching { URL(baseUrl) }.getOrNull() ?: return@withContext null
        val host = url.host ?: return@withContext null
        if (host != "127.0.0.1" && host != "localhost" && host != "::1") return@withContext null
        val key = url.toString()
        val now = System.currentTimeMillis()
        synchronized(servedModelCache) {
            servedModelCache[key]?.let { (at, name) -> if (now - at < 300_000L) return@withContext name }
        }
        val name = runCatching {
            val conn = (URL(baseUrl.trimEnd('/') + "/models").openConnection() as java.net.HttpURLConnection).apply {
                connectTimeout = 800
                readTimeout = 1200
                requestMethod = "GET"
            }
            try {
                if (conn.responseCode !in 200..299) return@runCatching null
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                org.json.JSONObject(body).optJSONArray("data")
                    ?.takeIf { it.length() > 0 }
                    ?.getJSONObject(0)?.optString("id")?.takeIf { it.isNotBlank() }
            } finally {
                conn.disconnect()
            }
        }.getOrNull()
        synchronized(servedModelCache) { servedModelCache[key] = now to name }
        name
    }

    private val servedModelCache = HashMap<String, Pair<Long, String?>>()

    /** baseUrl 是否指向本机（loopback）端点 */
    fun isLoopback(baseUrl: String): Boolean {
        val host = runCatching { URL(baseUrl).host }.getOrNull() ?: return false
        return host == "127.0.0.1" || host == "localhost" || host == "::1"
    }

    /** loopback 端点探测（30s 缓存）。非 loopback 地址一律视为可用。 */
    suspend fun localEndpointAlive(baseUrl: String): Boolean = withContext(Dispatchers.IO) {
        val url = runCatching { URL(baseUrl) }.getOrNull() ?: return@withContext true
        val host = url.host ?: return@withContext true
        if (!isLoopback(baseUrl)) return@withContext true
        val port = if (url.port > 0) url.port else if (url.protocol == "https") 443 else 80
        val key = "$host:$port"
        val now = System.currentTimeMillis()
        synchronized(probeCache) {
            probeCache[key]?.let { (at, ok) -> if (now - at < 30_000L) return@withContext ok }
        }
        val ok = runCatching {
            java.net.Socket().use { it.connect(java.net.InetSocketAddress(host, port), 250) }
        }.isSuccess
        synchronized(probeCache) { probeCache[key] = now to ok }
        ok
    }

    /** loopback 端点最近一次探测结果（`host:port` → 时间戳/是否活着），30s 内复用 */
    private val probeCache = HashMap<String, Pair<Long, Boolean>>()

    /** v40（成本优化阶段 4）：压缩槽的模型名设置项。值 = 模型表里已启用的模型名；
     *  指向本机 llama.cpp 服务（OpenAI 兼容端点）的模型即可让摘要/救援改走本地、token 成本归零。 */
    const val KEY_COMPACT_MODEL = "ctx.compactModel"

    /**
     * 压缩槽（摘要 / 超长救援）。未配置或名字解析不到 → **回退 worker**（与阶段 3 之前完全一致）。
     *
     * 2026-11-15 用户「本地模型总是压缩失败」：原来这里直接返回 `workerConfig`——
     * 只看配置不看端点可用性，也不对齐服务端实际模型名。本机端点活着但模型名不符
     * （升级后改名）或输入超长时，调用必失败且没有云端兜底。改成：解析到本机端点时走
     * [alignLocal]（活着→对齐模型名；死了→回云端主模型）。
     */
    suspend fun compactConfig(): ModelConfig {
        val name = runCatching { Graph.settings.kvGet(KEY_COMPACT_MODEL) }.getOrNull()?.trim().orEmpty()
        if (name.isNotBlank()) {
            val cfg = runCatching { Graph.aiProviders.configByName(name) }.getOrNull()
            // configByName 找不到时会回退 active；用 modelName 是否等于请求名来识别"真的解析到了"
            if (cfg != null && cfg.enabled && cfg.modelName == name) {
                return if (isLoopback(cfg.baseUrl)) alignLocal(cfg) else cfg
            }
        }
        return usableWorkerConfig()
    }

    /**
     * 本机端点配置对齐：活着→对齐服务端实际模型名（升级改名后旧配置照常能用）；
     * 死了→回退云端主模型。供压缩槽等「指名本机模型」的场景用。
     */
    suspend fun alignLocal(cfg: ModelConfig): ModelConfig {
        if (!localEndpointAlive(cfg.baseUrl)) {
            android.util.Log.w("AiClient", "本机端点不可达（${cfg.baseUrl}，模型 ${cfg.modelName}）→ 回退主模型 ${config.modelName}")
            return config
        }
        val served = servedModelName(cfg.baseUrl)
        if (served != null && served.isNotBlank() && served != cfg.modelName) {
            android.util.Log.w("AiClient", "本机模型名 ${cfg.modelName} 不在端点服务列表（$served）→ 改用 $served")
            return cfg.copy(modelName = served)
        }
        return cfg
    }

    /**
     * 摘要/压缩的**备槽**：主槽是本机端点时直接给云端主模型。
     * 原 [usableWorkerConfig] 只探测 TCP 活不活——本机端口活着但模型调用失败
     * （模型名不符/输入超长/空回复）时它仍返回本机配置 → 与主槽 sameCfg → 不重试 → 必失败。
     */
    suspend fun summaryFallback(primary: ModelConfig): ModelConfig =
        if (isLoopback(primary.baseUrl)) config else usableWorkerConfig()

    /** 用指定槽位配置做一次性对话（非流式） */
    /** 单条对话补全 + 服务端 usage。有就带、没有 null —— "unknown ≠ 0"：
     *  把没上报的字段记成 0 会污染按用途聚合的成本统计（学 rikkahub-agent-pure 账本）。
     *  v40（成本优化 1.3）：命名/摘要/救援这些 worker 槽位调用从此能进账本。 */
    data class ChatReply(
        val text: String,
        val tokensIn: Int?,
        val tokensOut: Int?,
        val cachedTokens: Int?,
        /** 本次调用墙钟耗时（ms）。阶段 4 补：账本要能算 tok/s（PURE 的统计页有，我们之前没有）。 */
        val durationMs: Long
    )

    suspend fun chatWith(cfg: ModelConfig, system: String, user: String, temperature: Double = 0.3): String =
        chatWithCounted(cfg, system, user, temperature).text

    suspend fun chatWithCounted(cfg: ModelConfig, system: String, user: String, temperature: Double = 0.3): ChatReply =
        withContext(Dispatchers.IO) {
            val startedAt = System.currentTimeMillis()
            if (!cfg.enabled || cfg.baseUrl.isBlank() || cfg.modelName.isBlank()) {
                throw AiException("尚未配置模型（我的 → 设置 → 模型管理）")
            }
            val anthropic = cfg.apiFormat == "anthropic"
            val url = if (anthropic) anthropicUrl(cfg.baseUrl)
            else cfg.baseUrl.trimEnd('/') + "/chat/completions"
            val body = if (anthropic) {
                val (sys, msgs) = toAnthropicMessages(
                    listOf(ChatApiMessage("system", system), ChatApiMessage("user", user))
                )
                val b = JSONObject()
                    .put("model", cfg.modelName)
                    .put("max_tokens", 8192)
                    .put("temperature", temperature)
                if (sys.isNotBlank()) b.put("system", sys)
                b.put("messages", msgs)
                b
            } else {
                JSONObject()
                    .put("model", cfg.modelName)
                    .put("temperature", temperature)
                    .put(
                        "messages", JSONArray()
                            .put(JSONObject().put("role", "system").put("content", system))
                            .put(JSONObject().put("role", "user").put("content", user))
                    )
            }
            var conn: HttpURLConnection? = null
            try {
                conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 20_000
                    readTimeout = 120_000
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    if (cfg.apiKey.isNotBlank()) {
                        if (anthropic) {
                            setRequestProperty("x-api-key", cfg.apiKey)
                            setRequestProperty("anthropic-version", "2023-06-01")
                        } else if (cfg.authStyle == AUTH_API_KEY) {
                            setRequestProperty("api-key", cfg.apiKey)
                        } else {
                            setRequestProperty("Authorization", "Bearer ${cfg.apiKey}")
                        }
                    }
                }
                conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                val code = conn.responseCode
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
                if (code !in 200..299) {
                    throw AiException("模型接口错误 $code：${text.take(300)}")
                }
                val root = JSONObject(text)
                val content = if (anthropic) {
                    val arr = root.getJSONArray("content")
                    buildString {
                        for (i in 0 until arr.length()) {
                            val blk = arr.getJSONObject(i)
                            if (blk.optString("type") == "text") append(blk.optString("text"))
                        }
                    }.trim()
                } else {
                    root.getJSONArray("choices")
                        .getJSONObject(0)
                        .getJSONObject("message")
                        .getString("content")
                        .trim()
                }
                // v40（成本优化 1.3/1.4）：usage 有就带、没有 null（"unknown ≠ 0"）。
                // Anthropic：input_tokens/output_tokens/cache_read_input_tokens；
                // OpenAI 兼容：prompt_tokens/completion_tokens + prompt_tokens_details.cached_tokens
                // 或 DeepSeek 的 prompt_cache_hit_tokens。
                val usage = root.optJSONObject("usage")
                val tokensIn = usage?.optInt("input_tokens", -1)?.takeIf { it >= 0 }
                    ?: usage?.optInt("prompt_tokens", -1)?.takeIf { it >= 0 }
                val tokensOut = usage?.optInt("output_tokens", -1)?.takeIf { it >= 0 }
                    ?: usage?.optInt("completion_tokens", -1)?.takeIf { it >= 0 }
                val cached = usage?.optInt("cache_read_input_tokens", -1)?.takeIf { it >= 0 }
                    ?: usage?.optJSONObject("prompt_tokens_details")?.optInt("cached_tokens", -1)?.takeIf { it >= 0 }
                    ?: usage?.optInt("prompt_cache_hit_tokens", -1)?.takeIf { it >= 0 }
                ChatReply(content, tokensIn, tokensOut, cached, System.currentTimeMillis() - startedAt)
            } catch (e: AiException) {
                throw e
            } catch (e: IOException) {
                throw AiException("网络错误：${e.message ?: "连接失败"}", e)
            } finally {
                conn?.disconnect()
            }
        }

    /**
     * 拉取供应商的模型目录（OpenAI 兼容的 GET {baseUrl}/models）。
     *
     * v19.51：给「扫描模型」按钮用——NVIDIA NIM 的 catalog 太大且常变，
     * 不可能在本机写死；DeepSeek 只有两款但走同一套代码，行为一致更好测。
     *
     * 失败时抛异常（由调用方转成可读提示）：网络不通 / key 无效 / 不是 OpenAI 兼容端点，
     * 这三种都得让用户看见原文，而不是静默返回空列表（那会让人以为"该供应商没有模型"）。
     */
    suspend fun fetchModels(cfg: ModelConfig): List<String> = withContext(Dispatchers.IO) {
        // **必须 withContext(Dispatchers.IO)**：调用方 ScanModelsDialog 是从 LaunchedEffect 里
        // 调这个方法的，而 LaunchedEffect 跑在**主线程**。不切 IO 就是
        // android.os.NetworkOnMainThreadException —— 真机表现是「扫描失败」，
        // 而该异常的 message 是 null，用户只看到"失败："两个字，什么都查不出来。
        // （chatWith 一直包着 withContext，只有这个方法漏了。）
        val url = cfg.baseUrl.trimEnd('/') + "/models"
        val conn = (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15000
            readTimeout = 20000
            // 与 chatWith 同一套鉴权：openai 格式走 Bearer；anthropic 格式 /models 不存在，
            // 直接给可读错误，别让用户看到 404 原文后以为是 key 错了
            if (cfg.apiFormat == "anthropic") {
                throw java.io.IOException("Anthropic 协议没有 /models 目录，无法扫描")
            }
            if (cfg.apiKey.isNotBlank()) setRequestProperty("Authorization", "Bearer " + cfg.apiKey)
            setRequestProperty("Content-Type", "application/json")
        }
        try {
            val code = conn.responseCode
            val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                throw java.io.IOException("HTTP $code：${body.take(200)}")
            }
            val arr = JSONObject(body).optJSONArray("data") ?: JSONArray()
            val out = LinkedHashSet<String>()
            for (i in 0 until arr.length()) {
                val id = arr.optJSONObject(i)?.optString("id")?.trim()
                if (!id.isNullOrEmpty()) out.add(id)
            }
            out.toList()
        } finally {
            conn.disconnect()
        }
    }

    suspend fun chat(system: String, user: String, temperature: Double = 0.3): String =
        chatWith(config, system, user, temperature)

    /** 让模型输出 JSON 数组并宽松解析（去掉 ```json 围栏、截取第一个 [ 到最后一个 ]） */
    fun parseJsonArray(text: String): JSONArray {
        val cleaned = text
            .replace("```json", "").replace("```", "")
            .trim()
        val start = cleaned.indexOf('[')
        val end = cleaned.lastIndexOf(']')
        // 2026-11-05 根治：模型输出的 JSON 常不严格（未转义引号/全角标点/尾逗号…），
        // 严格解析失败时走 JsonRepair 扫描式修复（组件卡侧同源），不再一坏全扔。
        if (start < 0 || end <= start) throw AiException("模型未返回有效 JSON")
        val slice = cleaned.substring(start, end + 1)
        return runCatching { JSONArray(slice) }
            .recoverCatching { JsonRepair.parseArray(slice) ?: throw it }
            .getOrThrow()
    }

    // ---------- 提示词 ----------

    const val DIGEST_SYSTEM = "你是一个信息整理助手。用户给你一段群聊记录原文，请把它提纯为要点清单：忽略闲聊、表情、广告，保留作业、通知、日程、待办、文件、链接等有价值信息。用简洁的中文要点列表输出（- 开头），每条保留关键时间/地点/负责人。不要输出多余解释。" +
        "\n补充（连续提纯模式）：历史消息里的「[原文已归档]」占位代表此前各天已提纯过的原始记录，你此前的回复即各天提纯要点——本轮上下文只保留提纯总结、不再含历史原文（设计如此）。本轮**只总结本轮原文里的内容**：旧要点已在你此前的回复里，不要再抄进本轮清单——每天清单都滚入历史要点的话，抽取轮会把旧事件按天反复重抽（2026-11-12 用户「要不就重复一堆」的根因）。某件事有新进展时正常输出它的新要点；连续两天都要做的事（每周组会之类）当天原文里有就抽。仍用 - 开头的清单格式。"

    const val SUMMARY_SYSTEM = "你是一个信息总结助手。把用户提供的多个群聊提纯要点汇总为一段当日简报，按重要程度排序，控制在 200 字以内，中文输出。"

    const val EXTRACT_SYSTEM = """你是一个信息抽取助手。从用户提供的群聊记录原文中提取日程和待办。只输出 JSON 数组，不要任何解释文字。每个元素格式：
{"kind":"schedule 或 todo","title":"标题","time":"时间描述，如 9月30日 14:00，可为空","place":"地点，可为空"}
只提取**需要用户行动**的事项：要参加的会议/活动、要交的作业/材料、要完成的任务、要去的地点。单纯的转述或信息分享（如「某人发送了文件」「某人提到了某事」「老师发了课件」）不是待办，不要输出。同一事项在记录里出现多次时只输出一条（取信息最全的一次）。"""

    const val WEEKLY_SYSTEM = "你是一个温和的自我管理教练。根据用户给的一周数据统计，写一段 120~200 字的中文周小结：先肯定亮点，再给一条具体建议，语气自然不说教。"
}
