package io.github.m0use001.planability.ai.ptl

import android.content.Context
import android.net.Uri
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * DeepSeek 应用能力云通道（2026-11-14，方案 ◆/planability-ai-cloud-plan.md，
 * 用户拍板：Tavily 优先云兜底；语音不上云；护栏日预算默认 5 元）。
 *
 * 三个独立 REST 接口（/guides/search + /guides/ocr 文档实测）：
 * - Search：POST https://api.deepseek.com/v1/tools/search  `{query, count(1-50), need_content}`
 *   → `results[]{title,url,snippet,summary,content,site_name,date,…}`（5 元/千次，仅成功计费）
 * - Fetch：POST https://api.deepseek.com/v1/tools/fetch  `{url}` → `{title, content}`（Markdown 正文）
 * - OCR：POST https://api.deepseek.com/v1/tools/ocr  multipart(file, purpose) → `{pages[]{index,text,…}}`
 *   （1.5 元/千页）
 *
 * 触发语义（全部**兜底**，不做主源）：
 * - Search：searchWithSource 的 Tavily→端侧 都空手后；
 * - Fetch：web_fetch 静态抓+无头渲染都空手后；
 * - OCR：端侧 ML Kit 结果过短（识别失败/近乎空白）时。
 *
 * 失败契约：网络错误/非 2xx/JSON 坏 → 空手/null，**绝不抛异常**（与 fetchTavily 同款），
 * 调用方继续走自己的原始路径。
 *
 * 安全约束：fetch 的目标 URL 必须先过 [io.github.m0use001.planability.core.SafeUrl.isAllowed]
 * （仅 http/https、拒绝本机/私有/保留地址）——把用户给的不可信 URL 转发给云端抓取前
 * 必须先在本地拦一道；Search/OCR 的目标 host 固定为 api.deepseek.com，无此问题。
 *
 * key 复用 DeepSeek 供应商的 apiKey（SecretBox 密文，AiProviderRepo 取明文），
 * 不新增密钥录入。护栏：`cloud.used.<yyyyMMdd>` 累计当日费用（0.1 分粒度），
 * 超过 `cloudDailyBudgetFen`（分）拒绝并让调用方回退降级链。
 */
object DeepSeekCloud {

    private const val BASE = "https://api.deepseek.com"
    private val JSON = "application/json; charset=utf-8".toMediaType()
    private val client by lazy {
        okhttp3.OkHttpClient.Builder()
            .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(120, java.util.concurrent.TimeUnit.SECONDS)
            .build()
    }

    // ---------------------------------------------------------------- 开关与 key

    /** 开关快照读取（非阻塞）：三开关各自独立，默认全关 = 行为与上云前完全一致 */
    internal fun searchEnabled(): Boolean = runCatching {
        io.github.m0use001.planability.data.Graph.settings.snapshot.value.cloudSearchEnabled
    }.getOrDefault(false)

    internal fun fetchEnabled(): Boolean = runCatching {
        io.github.m0use001.planability.data.Graph.settings.snapshot.value.cloudFetchEnabled
    }.getOrDefault(false)

    /** 云 OCR 模式（off / fallback / prefer）；读不到设置按兜底处理（保守）。 */
    internal fun ocrMode(): String = runCatching {
        io.github.m0use001.planability.data.Graph.settings.snapshot.value.cloudOcrMode
    }.getOrDefault(io.github.m0use001.planability.data.CLOUD_OCR_FALLBACK)

    /**
     * 「优先」模式（2026-11-14 用户：AI 读文件优先走云端）。
     * 与兜底的区别只有一处：不设端侧长度门槛——先上云，云端失败才回端侧。
     */
    internal fun preferOcr(): Boolean = ocrMode() == io.github.m0use001.planability.data.CLOUD_OCR_PREFER

    internal fun ocrEnabled(): Boolean = ocrMode() != io.github.m0use001.planability.data.CLOUD_OCR_OFF

    /**
     * 模型名是否 DeepSeek 系列（用户给的上云范围：「对于我现在在用的 deepseek 系列模型」）。
     * 覆盖 `deepseek-chat` / `deepseek-reasoner` / `deepseek-v3.2` / `deepseek-ai/...` 等写法。
     */
    internal fun isDeepSeekSeries(name: String): Boolean =
        name.contains("deepseek", ignoreCase = true)

    /**
     * 会话当前模型是不是 DeepSeek 系列。查不到会话/模型 → false（等于退回旧兜底语义，
     * 不因识别失败就把文件传上云）。供应商 baseUrl 也算一条证据（聚合站里模型名可能被改写）。
     */
    internal suspend fun sessionUsesDeepSeek(sessionId: Long): Boolean = runCatching {
        val session = sessionId.takeIf { it > 0L }
            ?.let { io.github.m0use001.planability.data.Graph.chat.session(it) }
        val name = session?.model?.takeIf { it.isNotBlank() }
            ?: io.github.m0use001.planability.data.Graph.aiProviders.activeConfig()?.modelName.orEmpty()
        if (isDeepSeekSeries(name)) return@runCatching true
        if (name.isBlank()) return@runCatching false
        io.github.m0use001.planability.data.Graph.aiProviders.configByName(name)
            ?.baseUrl?.contains("deepseek", ignoreCase = true) == true
    }.getOrDefault(false)

    /**
     * 这个会话是否走「优先」：优先模式 + 当前模型是 DeepSeek 系列。
     * sessionId=0（无会话上下文，如自动化/文件库直读）按显式开关执行。
     */
    internal suspend fun preferForSession(sessionId: Long): Boolean =
        preferOcr() && (sessionId <= 0L || sessionUsesDeepSeek(sessionId))

    /**
     * 预算预检（**发请求前**）：当前用量 + [costTenths] 是否仍在日预算内。
     * 说明：OCR/文件解析按页计费，页数只有响应里才知道，所以这里按最小页数先拦一道，
     * 响应后再按实际页数记账（极端情况最多超支一份文件，之后的调用都会被 charge 拒绝）。
     */
    internal fun budgetAllows(costTenths: Int): Boolean {
        val budget = runCatching {
            io.github.m0use001.planability.data.Graph.settings.snapshot.value.cloudDailyBudgetFen
        }.getOrDefault(500)
        if (budget <= 0) return false
        val today = java.time.LocalDate.now().toString().replace("-", "")
        return readUsage(usedFile(), today).total + costTenths <= budget * 10
    }

    /**
     * 服务端工具类型清单（对话透传用，AiStream 塞进 tools 数组）：type-only 形态
     * `{"type":"deepseek_web_search"}`，执行在 DeepSeek 服务端，引用随响应下发。
     * 语义：云搜索开 = web_search（思考模式下即「深度思考搜索」）；云抓取开 = read_url。
     * 全关时返回空表 = 请求体一个字节都不多传。
     */
    internal fun serverToolTypes(): List<String> = buildList {
        if (searchEnabled()) add("deepseek_web_search")
        if (fetchEnabled()) add("deepseek_read_url")
    }

    /**
     * DeepSeek 供应商的明文 key（复用 provider 配置，不新增录入）。
     * 找 baseUrl 含 "deepseek" 且配了 key 的供应商（内置播种名「DeepSeek」，用户改名也不影响）；
     * 没有返回 null = 云能力整体不可用（调用方空手回退）。
     */
    fun apiKey(): String? = runCatching {
        io.github.m0use001.planability.data.Graph.aiProviders
            .configByBaseUrlHint("deepseek")?.apiKey?.takeIf { it.isNotBlank() }
    }.getOrNull()

    // ---------------------------------------------------------------- 护栏

    /**
     * 计费换算（**唯一口径**，纯函数可单测；调用点不许再写数字字面量）：
     * 官方定价（/quick_start/pricing）：搜索 5 元/千次、网页抓取 5 元/千次、OCR 1.5 元/千页。
     * 记账单位 tenths = 0.1 分 = 0.001 元。
     * - 一次搜索/抓取 = 0.005 元 = 5 tenths
     * - 一页 OCR = 0.0015 元 = 1.5 tenths（整数粒度：单页记 2，多页按 1.5/页累计取整）
     */
    internal fun costTenthsSearch(): Int = 5
    internal fun costTenthsFetch(): Int = 5
    internal fun costTenthsOcrPages(pages: Int): Int = if (pages <= 1) 2 else pages * 3 / 2
    internal fun tenthsToYuan(tenths: Int): Double = tenths / 1000.0

    /**
     * 预算检查并记账。[costTenths] = 本次费用（0.1 分粒度）：搜索/抓取一次 0.5 元 = 50，
     * OCR 每页 1.5 分 = 15。累计超过 `cloudDailyBudgetFen`（分，默认 500=5 元）返回 false，
     * 调用方回退降级链并在审计标注「云预算尽」。
     *
     * 记账放**本地文件**（cacheDir，一行 date|tenths）而不是 settings KV——KV 读写是
     * suspend，而整条搜索/抓取链是同步阻塞风格（fetchTavily 同款），不能为记账破坏它。
     * 文件在缓存目录：清缓存=当日计数重置，保守方向可接受。
     */
    private fun usedFile(): File =
        File(runCatching { io.github.m0use001.planability.data.Graph.appContext.cacheDir }.getOrNull() ?: File("/data/local/tmp"), "cloud_used.txt")

    private fun readUsedTenths(today: String): Int = usageToday().total

    fun budgetOkAndCharge(costTenths: Int): Boolean = charge("", costTenths)

    // ---------------------------------------------------------------- 用量账本（面板细化）

    /**
     * 当日用量明细。[tenths] = 0.001 元粒度。
     *
     * 存储：cacheDir/cloud_used.txt 一行 `date|total|search|fetch|ocr`（旧格式 `date|total`
     * 也能读——向后兼容，缺的段按 0）。同时**每日镜像**一份到 filesDir/cloud_usage/month-<date>
     * 供「本月累计」（cacheDir 会被系统清，filesDir 不会；写文件失败只丢统计不丢功能）。
     */
    data class DayUsage(val total: Int = 0, val search: Int = 0, val fetch: Int = 0, val ocr: Int = 0)

    fun usageToday(): DayUsage {
        val today = java.time.LocalDate.now().toString().replace("-", "")
        return readUsage(usedFile(), today)
    }

    /**
     * 分类记账：[kind] = search / fetch / ocr（""=只记总账，用于兼容旧调用）。
     * 超预算返回 false（调用方回退降级链）。
     */
    fun charge(kind: String, costTenths: Int): Boolean {
        val budget = runCatching {
            io.github.m0use001.planability.data.Graph.settings.snapshot.value.cloudDailyBudgetFen
        }.getOrDefault(500)
        if (budget <= 0) return false
        val today = java.time.LocalDate.now().toString().replace("-", "")
        return runCatching {
            val u = readUsage(usedFile(), today)
            val total = u.total + costTenths
            if (total > budget * 10) return false
            val next = DayUsage(
                total = total,
                search = u.search + if (kind == "search") costTenths else 0,
                fetch = u.fetch + if (kind == "fetch") costTenths else 0,
                ocr = u.ocr + if (kind == "ocr") costTenths else 0
            )
            val line = "$today|${next.total}|${next.search}|${next.fetch}|${next.ocr}"
            usedFile().writeText(line)
            // 每日镜像（本月累计读它；失败不影响总量）
            runCatching { monthFile(today).writeText(line) }
            true
        }.getOrDefault(false)
    }

    /** 本月累计（1 号到今天）：按 filesDir/cloud_usage/month-YYYYMMDD 汇总 */
    fun usageThisMonth(): DayUsage {
        val dir = runCatching {
            File(io.github.m0use001.planability.data.Graph.appContext.filesDir, "cloud_usage")
        }.getOrNull() ?: return DayUsage()
        val monthPrefix = java.time.LocalDate.now().toString().replace("-", "").take(6)
        return runCatching {
            var acc = DayUsage()
            dir.listFiles()?.forEach { f ->
                val day = f.name.removePrefix("month-")
                if (!f.name.startsWith("month-") || !day.startsWith(monthPrefix)) return@forEach
                val u = readUsage(f, day)
                acc = DayUsage(
                    acc.total + u.total, acc.search + u.search,
                    acc.fetch + u.fetch, acc.ocr + u.ocr
                )
            }
            acc
        }.getOrDefault(DayUsage())
    }

    private fun monthFile(today: String): File =
        File(io.github.m0use001.planability.data.Graph.appContext.filesDir, "cloud_usage").also { it.mkdirs() }
            .let { File(it, "month-$today") }

    /** 读一行账本；日期不匹配（跨天/旧文件）返回零。旧格式 `date|total` 兼容。 */
    private fun readUsage(file: File, today: String): DayUsage = runCatching {
        if (!file.isFile) return DayUsage()
        val parts = file.readText().trim().split('|')
        if (parts.size < 2 || parts[0] != today) return DayUsage()
        val total = parts[1].toIntOrNull() ?: 0
        DayUsage(
            total = total,
            search = parts.getOrNull(2)?.toIntOrNull() ?: 0,
            fetch = parts.getOrNull(3)?.toIntOrNull() ?: 0,
            ocr = parts.getOrNull(4)?.toIntOrNull() ?: 0
        )
    }.getOrDefault(DayUsage())

    /** 当日已用（元字符串，设置卡用量展示用）。tenths = 0.1 分 = 0.001 元 → 除 1000 */
    fun usedTodayYuan(): String =
        String.format(java.util.Locale.ROOT, "%.3f", tenthsToYuan(usageToday().total))

    // ---------------------------------------------------------------- Search（云搜索兜底）

    /**
     * 云搜索兜底入口（开关 + key + 护栏全过才发请求）。失败/关闭/超预算一律空表。
     * **同步阻塞**（与 fetchTavily 同风格——searchWithSource 整条链是同步的）。
     * 摘要优先 summary（DeepSeek 服务端已抽好），退 snippet/content。
     */
    fun searchIfEnabled(query: String, count: Int): List<WebSearch.Hit> {
        if (!searchEnabled()) return emptyList()
        val key = apiKey() ?: return emptyList()
        if (!charge("search", costTenthsSearch())) return emptyList()
        return runCatching {
            val payload = org.json.JSONObject()
                .put("query", query.take(128))
                .put("count", count.coerceIn(1, 50))
                .put("need_content", false)
                .toString()
            val req = okhttp3.Request.Builder()
                .url("$BASE/v1/tools/search")
                .header("Authorization", "Bearer " + key)
                .header("Accept", "application/json")
                .post(payload.toRequestBody(JSON))
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return emptyList()
                val body = resp.body?.string() ?: return emptyList()
                parseSearch(body, count)
            }
        }.getOrDefault(emptyList())
    }

    /** Search 响应解析（单测喂真实形状）：results[]{title,url,snippet,summary,content} */
    internal fun parseSearch(json: String, count: Int): List<WebSearch.Hit> = runCatching {
        val arr = org.json.JSONObject(json).optJSONArray("results") ?: return emptyList()
        val out = mutableListOf<WebSearch.Hit>()
        for (i in 0 until arr.length()) {
            if (out.size >= count) break
            val o = arr.optJSONObject(i) ?: continue
            val title = o.optString("title").trim()
            val url = o.optString("url").trim()
            if (title.isBlank() || url.isBlank()) continue
            if (!url.startsWith("http://") && !url.startsWith("https://")) continue
            val snippet = (o.optString("summary").ifBlank { o.optString("snippet").ifBlank { o.optString("content") } })
                .replace(Regex("\\s+"), " ")
                .take(240)
            out.add(WebSearch.Hit(title, url, snippet))
        }
        out
    }.getOrDefault(emptyList())

    // ---------------------------------------------------------------- Fetch（云抓取兜底）

    /**
     * 云抓取兜底：web_fetch 的静态抓+无头渲染都空手后调用。返回 (title, Markdown 正文)，
     * 关闭/超预算/失败/URL 非法一律 null。**调用方必须已过 SafeUrl**——这里再拦一道双保险。
     */
    suspend fun fetchIfEnabled(url: String): Pair<String, String>? {
        if (!fetchEnabled()) return null
        if (!io.github.m0use001.planability.core.SafeUrl.isAllowed(url)) return null
        val key = apiKey() ?: return null
        if (!charge("fetch", costTenthsFetch())) return null
        return runCatching {
            val payload = org.json.JSONObject().put("url", url).toString()
            val req = okhttp3.Request.Builder()
                .url("$BASE/v1/tools/fetch")
                .header("Authorization", "Bearer " + key)
                .header("Accept", "application/json")
                .post(payload.toRequestBody(JSON))
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val body = resp.body?.string() ?: return null
                val o = org.json.JSONObject(body)
                val content = o.optString("content").trim()
                if (content.isBlank()) return null
                o.optString("title").trim() to content
            }
        }.getOrNull()
    }

    // ------------------------------------------------ OCR（云端识别：兜底 / 优先两档）

    /**
     * 云 OCR（图片）：把原图上传 `POST /v1/tools/ocr`（purpose=text_recognition）取整页文本。
     * - 兜底模式：端侧结果过短（[localText] 近乎空）才调用；
     * - 优先模式：调用方传 [force]=true（2026-11-14 用户：读文件优先走云端），不设长度门槛。
     * 发请求前先做预算预检；响应按 page_count 记账（1.5 元/千页）。失败/关闭/超预算 null。
     */
    fun ocrIfEnabled(context: Context, uri: Uri, localText: String, force: Boolean = false): String? {
        if (!ocrEnabled()) return null
        if (!force && localText.length >= 8) return null   // 兜底模式：端侧有实质内容就不打扰云端
        val key = apiKey() ?: return null
        if (!budgetAllows(costTenthsOcrPages(1))) return null
        val file = copyToCache(context, uri) ?: return null
        try {
            return runCatching {
                val raw = postOcr(file, key) ?: return null
                val text = parseOcr(raw) ?: return null
                // 记账按页：1.5 元/千页 = 0.0015 元/页 = 0.15 分 = 1.5 tenths/页；
                // tenths 是整数粒度 → 单页按 2 tenths（0.2 分，宁多勿少=护栏偏保守），
                // 多页按 1.5/页累计后取整。
                charge("ocr", costTenthsOcrPages(pageCountOf(raw)))
                text
            }.getOrNull()
        } finally {
            file.delete()
        }
    }

    /** 文件解析结果（[pages] = 云端返回页数，用于记账与提示）。 */
    data class DocParse(val text: String, val pages: Int)

    /** 云端文件解析支持的扩展名（DeepSeek OCR/Files 接口：PDF/Word/PPT + 图片；**不含 xlsx**）。 */
    internal val CLOUD_DOC_EXTS = setOf("pdf", "doc", "docx", "ppt", "pptx")

    /**
     * 云端优先读文件（2026-11-14 用户：「AI 读文件改成优先走云端」）。
     *
     * 满足全部条件才发请求，否则 null（调用方直接走端侧解析，行为与上云前一致）：
     * 优先模式 + 当前会话模型是 DeepSeek 系列 + 格式在 [CLOUD_DOC_EXTS] 内 + 有 key + 预算够。
     * 云端返回逐页文本，多页时带 `[第N页]` 页标（改稿要能按页定位）；
     * 失败/非 2xx/空文本/坏 JSON 一律 null——绝不把「云端没读到」当成「文件是空的」。
     */
    suspend fun parseDocIfEnabled(context: Context, f: File, sessionId: Long = 0L): DocParse? {
        if (!preferForSession(sessionId)) return null
        val ext = f.extension.lowercase()
        if (ext !in CLOUD_DOC_EXTS) return null
        if (!f.isFile || f.length() <= 0L) return null
        // 超大件不整份上传（会等很久也未必成功）：直接端侧解析，失败原因在端侧链里有说明
        if (f.length() > 40L * 1024 * 1024) return null
        // 2026-11-15 用户「为什么余额烧得这么快」：AI 一轮任务常把同一份文件读好几遍
        // （重读、分段、24 轮工具循环），每次整份上云都按页计费。按 (路径, 大小, mtime)
        // 落盘缓存：同一份文件重读**零计费**；文件改过（size/mtime 变）才重新上云。
        val cacheKey = docCacheKeyOf(f.absolutePath, f.length(), f.lastModified())
        docCacheRead(context, cacheKey)?.let { return it }
        val key = apiKey() ?: return null
        if (!budgetAllows(costTenthsOcrPages(1))) return null
        return withContext(Dispatchers.IO) {
            runCatching {
                val raw = postOcr(f, key) ?: return@runCatching null
                val pages = parseOcrPages(raw) ?: return@runCatching null
                charge("ocr", costTenthsOcrPages(pageCountOf(raw, pages.size)))
                val parsed = DocParse(
                    text = "[云端解析（DeepSeek）· 共 " + pages.size + " 页]\n" + numberPages(pages),
                    pages = pages.size
                )
                docCacheWrite(context, cacheKey, parsed)
                parsed
            }.getOrNull()
        }
    }

    // ---------------------------------------------------------------- 文档解析缓存（重读不计费）

    /** 缓存键：路径 + 大小 + mtime 的 SHA-1（内容没变就不重新计费；文件改了就是新条目）。 */
    internal fun docCacheKeyOf(path: String, length: Long, lastModified: Long): String {
        val digest = java.security.MessageDigest.getInstance("SHA-1")
            .digest("$path|$length|$lastModified".toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    /** 缓存序列化/解析（纯函数，单测用）：`{"text":…,"pages":N}`；空文本视为无效。 */
    internal fun encodeDocCache(parsed: DocParse): String =
        org.json.JSONObject().put("text", parsed.text).put("pages", parsed.pages).toString()

    internal fun decodeDocCache(json: String): DocParse? = runCatching {
        val o = org.json.JSONObject(json)
        val text = o.optString("text")
        if (text.isBlank()) return null
        DocParse(text, o.optInt("pages", 1).coerceAtLeast(1))
    }.getOrNull()

    /** 缓存最多留这么多份（PDF 全文不小，别无限长） */
    private val DOC_CACHE_MAX = 120

    private fun docCacheRead(context: Context, cacheKey: String): DocParse? = runCatching {
        val f = java.io.File(java.io.File(context.filesDir, "cloud_doc_cache"), "$cacheKey.json")
        if (!f.isFile) return null
        decodeDocCache(f.readText())
    }.getOrNull()

    private fun docCacheWrite(context: Context, cacheKey: String, parsed: DocParse) {
        runCatching {
            val dir = java.io.File(context.filesDir, "cloud_doc_cache")
            dir.mkdirs()
            java.io.File(dir, "$cacheKey.json").writeText(encodeDocCache(parsed))
            val all = dir.listFiles()?.sortedByDescending { it.lastModified() } ?: return
            all.drop(DOC_CACHE_MAX).forEach { it.delete() }
        }
    }

    /** 上传一份文件给 `/v1/tools/ocr`，返回原始 JSON；非 2xx/空 body → null。 */
    private fun postOcr(file: File, key: String): String? = runCatching {
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart(
                "file", file.name,
                file.asRequestBody("application/octet-stream".toMediaType())
            )
            .addFormDataPart("purpose", "text_recognition")
            .build()
        val req = okhttp3.Request.Builder()
            .url("$BASE/v1/tools/ocr")
            .header("Authorization", "Bearer " + key)
            .post(body)
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return null
            resp.body?.string()?.takeIf { it.isNotBlank() }
        }
    }.getOrNull()

    /** 响应里的计费页数（`page_count`，缺省用实际返回页数）。 */
    private fun pageCountOf(raw: String, fallback: Int = 1): Int = runCatching {
        org.json.JSONObject(raw).optInt("page_count", fallback)
    }.getOrDefault(fallback)

    /** OCR 响应解析（单测）：pages[].text 逐页；空结果 null */
    internal fun parseOcrPages(json: String): List<String>? = runCatching {
        val pages = org.json.JSONObject(json).optJSONArray("pages") ?: return null
        val texts = (0 until pages.length()).mapNotNull { i ->
            pages.optJSONObject(i)?.optString("text")?.trim()?.takeIf { it.isNotEmpty() }
        }
        if (texts.isEmpty()) null else texts
    }.getOrDefault(null)

    internal fun parseOcr(json: String): String? = parseOcrPages(json)?.joinToString("\n")

    /**
     * 逐页文本 → 带页标的正文（单页不加页标；多页 `[第N页]` 前缀，与端侧 PDF OCR 同形）。
     * 页标让模型能说「第 3 页的公式」而不是笼统「文档里」。
     */
    internal fun numberPages(pages: List<String>): String =
        if (pages.size <= 1) pages.joinToString("\n")
        else pages.mapIndexed { i, t -> "[第" + (i + 1) + "页]\n" + t }.joinToString("\n\n")

    // ------------------------------------------- 统一 OCR 入口（图片 / 内嵌图 / 扫描页）

    /**
     * file_read_image / 文档内嵌图 / PDF 扫描件的统一 OCR 入口。
     *
     * 两档语义（2026-11-14 用户拍板：DeepSeek 系列模型优先走云端）：
     * - 优先（[preferForSession]）：**先云后端侧**——云端解析质量对扫描件/公式/手写明显更好，
     *   云端不可用（无 key/超预算/网络失败/空结果）才回端侧 ML Kit；
     * - 兜底/关闭：端侧 ML Kit 第一手，云端只在端侧 <8 字时补（旧行为）。
     */
    suspend fun recognizeEnhanced(context: Context, uri: Uri, sessionId: Long = 0L): String {
        val local = runCatching {
            io.github.m0use001.planability.core.OcrHelper.recognize(context, uri)
        }.getOrDefault("")
        if (preferForSession(sessionId)) {
            val cloudFirst = ocrIfEnabled(context, uri, "", force = true)
            if (!cloudFirst.isNullOrBlank())
                return cloudFirst + "\n\n[本页文字由云端 OCR 识别]"
        }
        val cloud = ocrIfEnabled(context, uri, local)
        return if (cloud != null && cloud.length > local.length)
            cloud + "\n\n[本页文字由云端 OCR 识别]"
        else local
    }

    // ---------------------------------------------------------------- 内部

    private fun copyToCache(context: Context, uri: Uri): File? = runCatching {
        val f = File(context.cacheDir, "cloud_ocr_" + System.currentTimeMillis() + ".bin")
        context.contentResolver.openInputStream(uri)?.use { input ->
            f.outputStream().use { input.copyTo(it) }
        } ?: return null
        if (f.length() == 0L) {
            f.delete()
            return null
        }
        f
    }.getOrNull()
}
