package io.github.m0use001.planability.ai.ptl

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

/**
 * v12：联网搜索（国内可直连，无需 API Key）。
 * 主通道 Bing 中国网页版 HTML 解析；质量兜底链（2026-11-04 两次真机案例驱动）：
 * ①「不背单词 会员」→ Bing 把单字「不」的百度百科顶到第一（三条不同写法返回逐字相同结果）；
 * ②「背单词软件 永久会员 排行榜」→ Bing 返回曼恩卡车（标题与 query **零词元重叠**）。
 * 兜底顺序：中文 query 兜底**360 搜索**（www.so.com，桌面 UA 静态页，中文排序明显更稳；
 * 实测 okhttp 可直连不触发风控——百度对 okhttp 的 TLS 指纹直接回「安全验证页」，不可用；
 * 360 拿不到就保留 Bing 原始结果，绝不落 ensearch：实测 ensearch 对中文 query 劣化成
 * 英文站，比跑偏还糟）；英文 query 兜底 Bing ensearch=1（国际通道）。ensearch 不做默认。
 *
 * ## 2026-11-14 起：Tavily 成为主源，上面这整条端侧链降级为兜底
 *
 * 产品决策：不依赖任何自建基础设施（否决了自建 SearXNG），也不想继续维护
 * 「Bing/360 改版 → 我们改 selector」这条路。Tavily 的免费档（1000 credits/月、
 * 不要信用卡）在四类真实 query（中文学习/中文资讯/英文技术/中文时效）上实测
 * 全部命中，且它是正规 API 而非爬页面——失败是显式的 HTTP 错误，不是「解析出零条」。
 *
 * 语义（顺序不许调换）：
 * 1. 配了 key → 先打 Tavily；结果过**同一道** isGoodEnough（API 稳≠不过滤跑题）
 * 2. Tavily 空手/质量不过 → 走原端侧链（Bing→360→学术库），一端有结果就用它
 * 3. 端侧也空手 → 退回 Tavily 的原始结果（有总比没有强）
 * 4. 没配 key → 行为与 11-14 之前**完全一致**（纯端侧链）
 * 来源随结果一起返回（searchWithSource），工具审计里能看出这次是谁答的。
 */
object WebSearch {
    data class Hit(val title: String, val url: String, val snippet: String)

    /** 搜索来源：tavily = 商业 API 主源；scrape = 端侧抓页面兜底链 */
    data class Outcome(val hits: List<Hit>, val source: String)

    private const val TAVILY_URL = "https://api.tavily.com/search"

    private val client by lazy {
        okhttp3.OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .build()
    }

    private val UA =
        "Mozilla/5.0 (Linux; Android 16; PLK110) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Mobile Safari/537.36"

    /** 桌面 UA：360/百度一类中文引擎只给桌面 UA 出经典静态结果页 */
    private const val DESKTOP_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Safari/537.36"

    /**
     * 设置页写入的 Tavily key（库里存密文，snapshot 里已是明文）。
     *
     * 2026-11-14 支持**多 key 轮换**（同日配置了两个 key）：免费档按账号计 1000 次/月，
     * 一个号额度用尽会返回错误体 → 解析出来是空表 → 自动打下一个号。设置里用换行或
     * 逗号分隔即可；提取只认 `tvly-…` 字面量（粘贴整段 JSON/带说明也不怕）。
     */
    internal fun tavilyKeys(): List<String> {
        val raw = runCatching {
            io.github.m0use001.planability.data.Graph.settings.snapshot.value.searchTavilyKey
        }.getOrDefault("")
        return parseKeys(raw)
    }

    /** 纯函数（给单测）：从一段输入里提取 tvly-… key，按出现顺序去重 */
    internal fun parseKeys(rawIn: String): List<String> {
        val raw = rawIn.trim()
        if (raw.isEmpty()) return emptyList()
        val found = Regex("tvly-[A-Za-z0-9_-]+").findAll(raw).map { it.value }.distinct().toList()
        return found.ifEmpty { listOf(raw) }
    }

    fun search(query: String, count: Int = 5): List<Hit> = searchWithSource(query, count).hits

    /**
     * 带来源的搜索（工具层用它标注「本次结果由谁给出」）。顺序语义见文件头注释，
     * **不许把 Tavily 挪到端侧链后面**——那条链每多跑一次就多一次被风控/改版的机会。
     *
     * 2026-11-14 云兜底（决策「Tavily 优先，云兜底」）：Tavily 与端侧链都空手后
     * 试 DeepSeek Search API（开关 cloudSearchEnabled + key + 日预算护栏），
     * source="deepseek"。开关关时行为与之前完全一致。
     */
    fun searchWithSource(query: String, count: Int = 5): Outcome {
        val keys = tavilyKeys()
        if (keys.isEmpty()) {
            val legacy = legacySearch(query, count)
            if (legacy.isNotEmpty()) return Outcome(legacy, "scrape")
            val cloud = DeepSeekCloud.searchIfEnabled(query, count)
            if (cloud.isNotEmpty()) return Outcome(cloud, "deepseek")
            return Outcome(legacy, "scrape")
        }
        // 逐 key 试（额度用尽/失效的号返回空表，直接跳过）
        var lastTav: List<Hit> = emptyList()
        for (k in keys) {
            val tav = fetchTavily(query, count, k)
            if (tav.isEmpty()) continue
            lastTav = tav
            if (isGoodEnough(tav, query)) return Outcome(tav, "tavily")
        }
        val legacy = legacySearch(query, count)
        if (legacy.isNotEmpty()) return Outcome(legacy, "scrape")
        // 端侧也空手：云兜底 → 至少把 Tavily 的原始结果交给模型（可能少于 2 条，但比空手强）
        val cloud = DeepSeekCloud.searchIfEnabled(query, count)
        if (cloud.isNotEmpty()) return Outcome(cloud, "deepseek")
        return Outcome(lastTav, "tavily")
    }

    /** 原端侧链（Bing→360→学术库），由 searchWithSource 在 Tavily 不可用时调用 */
    private fun legacySearch(query: String, count: Int): List<Hit> {
        val hits = fetchBing(query, count, ensearch = false)
        // 两道**互相独立**的退化判据（2026-11-08 第三次报障后加第二道）：
        // ① 词面判据 isGoodEnough：结果讲不讲这件事（会被"同词共字"骗过——见 isRelevant 注释）；
        // ② 结构判据 looksLikeSiteCards：有没有一条内容页。它不看措辞，只看到底返了站名还是正文——
        //    引擎把 query 降级成站名匹配时，返回的必然全是站点根页/官网首页。
        // 两道都不许单独当"没问题"的证明，所以这里用 AND：任一报警就换源。
        val siteCards = contentTokens(query).size >= 2 && looksLikeSiteCards(hits)
        if (isGoodEnough(hits, query) && !siteCards) return hits
        // 质量兜底：跑偏/退化才触发，正常结果零开销。
        // 2026-11-08 新增第三类退化「只匹配首词」：Bing 中国版对多词中文 query 会返回
        // 只认第一个词的站点卡（星轨3… → 云海幻境官网全家桶），见 isRelevant 的判据说明。
        // 中文 query 只兜底 360；ensearch 已实测对中文 query 劣化成英文站
        // （2026-11-04 端到端验证：「背单词软件…」百度被限流时 ensearch 返回 YouTube），
        // 宁可保留 Bing 原始（可能跑偏）结果也不换成英文垃圾。
        val retry = if (containsCjk(query)) fetch360(query, count)
        else fetchBing(query, count, ensearch = true)
        if (retry.isNotEmpty() && isGoodEnough(retry, query)) return retry
        // 学术库兜底（2026-11-08 真机实证）：英文学术长查询在 Bing/360 上已彻底失效——
        // 「Parry 2023 Meta-Analysis "Brain Drain Effect"…」返回 Cisco 论坛、「Ward…working
        // memory」返回影视站，两路都是零相关。而同一台手机上 AI 自己抓
        // api.semanticscholar.org / api.crossref.org 全部成功（工具审计 21:34/22:24 实录）。
        // 这类 JSON API 没有风控，直接查比刮 HTML 稳得多。
        if (looksAcademic(query)) {
            val scholarly = fetchScholarly(query, count)
            if (scholarly.isNotEmpty()) return scholarly
        }
        return retry.ifEmpty { hits }
    }

    /**
     * Tavily 搜索（2026-11-14 新增，主源）。
     *
     * 请求契约（实测）：POST https://api.tavily.com/search，`Authorization: Bearer tvly-…`，
     * body `{"query":…,"max_results":N,"search_depth":"basic"}`；响应 `results[]` 每项
     * `{title,url,content}`，content 直接是可用的摘要（不用再抓页面）。
     *
     * 失败契约：网络错误/非 2xx/JSON 坏 → 空手返回，让调用方走端侧链；**不抛异常**
     * （工具层只有在两条链都空手时才会告诉用户「搜索无结果」）。
     */
    internal fun fetchTavily(query: String, count: Int, key: String): List<Hit> {
        val payload = runCatching {
            org.json.JSONObject()
                .put("query", query)
                .put("max_results", count.coerceIn(1, 8))
                .put("search_depth", "basic")
                .toString()
        }.getOrNull() ?: return emptyList()
        val req = okhttp3.Request.Builder()
            .url(TAVILY_URL)
            .header("Authorization", "Bearer " + key)
            .header("Accept", "application/json")
            .post(
                payload.toRequestBody(
                    "application/json; charset=utf-8".toMediaType()
                )
            )
            .build()
        val body = runCatching {
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) null else resp.body?.string()
            }
        }.getOrNull() ?: return emptyList()
        return parseTavily(body, count)
    }

    /** Tavily 响应解析（单独抽出来给单测喂真实响应样本） */
    internal fun parseTavily(json: String, count: Int): List<Hit> = runCatching {
        val arr = org.json.JSONObject(json).optJSONArray("results") ?: return emptyList()
        val out = mutableListOf<Hit>()
        for (i in 0 until arr.length()) {
            if (out.size >= count) break
            val o = arr.optJSONObject(i) ?: continue
            val title = o.optString("title").trim()
            val url = o.optString("url").trim()
            if (title.isBlank() || url.isBlank()) continue
            if (!url.startsWith("http://") && !url.startsWith("https://")) continue
            val snippet = o.optString("content")
                .replace(Regex("\\s+"), " ")
                .trim()
                .take(240)
            out.add(Hit(title, url, snippet))
        }
        out
    }.getOrDefault(emptyList())

    /** 够好 = 有结果、不退化、且与 query 相关（三者都过才不触发兜底） */
    internal fun isGoodEnough(hits: List<Hit>, query: String): Boolean =
        hits.size >= 2 && !isDegenerate(hits) && isRelevant(hits, query)

    /**
     * 学术型查询判定：拉丁文为主（无中日韩字符）+ 至少命中一条——带 19xx/20xx 年份、
     * 带引号短语、或词元数 ≥ 6。这类长查询在 Bing/360 上的召回实测已不可用（见 search()）。
     */
    internal fun looksAcademic(query: String): Boolean {
        if (containsCjk(query)) return false
        val words = query.split(Regex("[^A-Za-z0-9\u00C0-\u024F]+")).filter { it.isNotBlank() }
        if (words.size < 2) return false
        val hasYear = Regex("\\b(19|20)\\d{2}\\b").containsMatchIn(query)
        val hasQuote = query.contains('"')
        return hasYear || hasQuote || words.size >= 6
    }

    /**
     * 学术库检索：OpenAlex → Semantic Scholar（都是公开 JSON API，无需 Key）。
     * 返回空表示两路都没结果，由调用方保留原结果。
     */
    internal fun fetchScholarly(query: String, count: Int): List<Hit> {
        // Crossref 优先：引文式长查询（作者+年份+标题）命中率最高，2026-11-08 PC/真机实测
        // 「Parry 2023 Meta-Analysis Brain Drain」第一条就是目标论文本体。
        fetchCrossref(query, count).takeIf { it.isNotEmpty() }?.let { return it }
        fetchOpenAlex(query, count).takeIf { it.isNotEmpty() }?.let { return it }
        return fetchSemanticScholar(query, count)
    }

    private fun fetchCrossref(query: String, count: Int): List<Hit> {
        val url = "https://api.crossref.org/works?query.bibliographic=" +
            URLEncoder.encode(query, "UTF-8") + "&rows=" + count.coerceIn(1, 10) +
            "&select=title,DOI,issued,container-title,abstract"
        val body = getJson(url) ?: return emptyList()
        return runCatching {
            val items = org.json.JSONObject(body).optJSONObject("message")
                ?.optJSONArray("items") ?: return emptyList()
            (0 until items.length()).mapNotNull { i ->
                val o = items.optJSONObject(i) ?: return@mapNotNull null
                val title = o.optJSONArray("title")?.optString(0).orEmpty()
                if (title.isBlank()) return@mapNotNull null
                val doi = o.optString("DOI")
                if (doi.isBlank()) return@mapNotNull null
                val year = o.optJSONObject("issued")?.optJSONArray("date-parts")
                    ?.optJSONArray(0)?.optInt(0, 0) ?: 0
                val venue = o.optJSONArray("container-title")?.optString(0).orEmpty()
                // Crossref 摘要是 JATS 片段（<jats:p>…</jats:p>），剥标签后截断
                val abs = o.optString("abstract")
                    .replace(Regex("<[^>]+>"), " ")
                    .replace(Regex("\\s+"), " ")
                    .trim().take(220)
                Hit(
                    title = title,
                    url = "https://doi.org/" + doi,
                    snippet = listOfNotNull(
                        year.takeIf { it > 0 }?.toString(),
                        venue.takeIf { it.isNotBlank() },
                        abs.takeIf { it.isNotBlank() }
                    ).joinToString(" · ")
                )
            }
        }.getOrDefault(emptyList())
    }

    private fun fetchOpenAlex(query: String, count: Int): List<Hit> {
        val url = "https://api.openalex.org/works?search=" +
            URLEncoder.encode(query, "UTF-8") + "&per-page=" + count.coerceIn(1, 10) +
            "&mailto=planability@app.local"
        val body = getJson(url) ?: return emptyList()
        return runCatching {
            val arr = org.json.JSONObject(body).optJSONArray("results") ?: return emptyList()
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val title = o.optString("title").ifBlank { return@mapNotNull null }
                val doi = o.optString("doi").removePrefix("https://doi.org/")
                val year = o.optInt("publicationYear", 0)
                val venue = o.optJSONObject("primary_location")
                    ?.optJSONObject("source")?.optString("display_name").orEmpty()
                val cited = o.optInt("cited_by_count", 0)
                val link = if (doi.isNotBlank()) "https://doi.org/" + doi
                           else o.optString("id").ifBlank { "" }
                if (link.isBlank()) return@mapNotNull null
                Hit(
                    title = title,
                    url = link,
                    snippet = listOfNotNull(
                        year.takeIf { it > 0 }?.toString(),
                        venue.takeIf { it.isNotBlank() },
                        if (cited > 0) "被引 " + cited + " 次" else null
                    ).joinToString(" · ")
                )
            }
        }.getOrDefault(emptyList())
    }

    private fun fetchSemanticScholar(query: String, count: Int): List<Hit> {
        val url = "https://api.semanticscholar.org/graph/v1/paper/search?query=" +
            URLEncoder.encode(query, "UTF-8") + "&limit=" + count.coerceIn(1, 10) +
            "&fields=title,abstract,year,venue,externalIds,url"
        val body = getJson(url) ?: return emptyList()
        return runCatching {
            val arr = org.json.JSONObject(body).optJSONArray("data") ?: return emptyList()
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val title = o.optString("title").ifBlank { return@mapNotNull null }
                val doi = o.optJSONObject("externalIds")?.optString("DOI").orEmpty()
                val link = if (doi.isNotBlank()) "https://doi.org/" + doi
                           else o.optString("url").ifBlank { "" }
                if (link.isBlank()) return@mapNotNull null
                val year = o.optInt("year", 0)
                val venue = o.optString("venue")
                val abs = o.optString("abstract").replace(Regex("\\s+"), " ").take(220)
                Hit(
                    title = title,
                    url = link,
                    snippet = listOfNotNull(
                        year.takeIf { it > 0 }?.toString(),
                        venue.takeIf { it.isNotBlank() },
                        abs.takeIf { it.isNotBlank() }
                    ).joinToString(" · ")
                )
            }
        }.getOrDefault(emptyList())
    }

    /** JSON GET（学术库共用；失败返回 null，不抛） */
    private fun getJson(url: String): String? = runCatching {
        val req = okhttp3.Request.Builder()
            .url(url)
            .header("User-Agent", UA)
            .header("Accept", "application/json")
            .get()
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) null else resp.body?.string()
        }
    }.getOrNull()

    private fun fetchBing(query: String, count: Int, ensearch: Boolean): List<Hit> {
        val url = "https://cn.bing.com/search?q=" +
            URLEncoder.encode(query, "UTF-8") + "&mkt=zh-CN&setlang=zh-hans" +
            if (ensearch) "&ensearch=1" else ""
        val req = okhttp3.Request.Builder()
            .url(url)
            .header("User-Agent", UA)
            .header("Accept-Language", "zh-CN,zh;q=0.9")
            .get()
            .build()
        val html = client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return@use null
            resp.body?.string()
        } ?: return emptyList()
        return parseBing(html, count)
    }

    /**
     * 退化判定（命中任一即算）：① 首条结果是百度百科**单字**条目
     * （路径解码后只有一个字符，如「不」——Bing 中国版把 query 按字查字典了）；
     * ② 前两条结果的标题+链接完全相同（真实搜索不会这样）。
     */
    internal fun isDegenerate(hits: List<Hit>): Boolean {
        if (hits.size < 2) return false
        val first = hits.first()
        // 百科单字条目的路径形如 /item/不/64725（末段是数字 ID，单字在中间段）
        if (first.url.contains("baike.baidu.com/item/") &&
            (java.net.URI(first.url).rawPath ?: "").split('/')
                .any { seg ->
                    seg.isNotBlank() && runCatching {
                        java.net.URLDecoder.decode(seg, "UTF-8")
                    }.getOrDefault("").let { it.length == 1 && it.first().code > 0x2E00 }
                }
        ) return true
        val a = hits[0]; val b = hits[1]
        return a.title == b.title && a.url == b.url
    }

    /**
     * 相关性判定：**按 query 的词元**判，不是按整句的汉字集合判。
     *
     * 2026-11-08 用户第三次报「搜索还是不行」，真机取证（小说项目会话）：query
     * `星轨3 主线剧情 章节 完整顺序` 与 `星轨3 后星轨书 剧情 解析` 返回**逐字相同的 6 条**，
     * 全是云海幻境官网/「崩」字百科/ExampleGames 首页——Bing 中国版自己退化成"首词站点匹配"。
     * 这是已知的搜索引擎毛病，不是我们传参出错（返回页 title 里就是完整 query）。
     *
     * 但**兜底没触发是我们的 bug**：旧判据只数"标题里出现 query 的不同汉字 ≥2 个"，
     * 而 `《星轨：云海幻境》官方网站` 里同时有「星」「轨」两个——两个字来自**同一个词**
     * 就算相关，等于给"只匹配首词"的发一条免死金牌。而 360 对这话第一个结果就是
     * 「星轨3剧情顺序(主线精简版)」，白白不用。
     *
     * 新判据：query 先切成词（空白/中英标点），覆盖 **min(2, 词数)** 个不同的词才算相关；
     * **多词 query 额外要求至少命中共一个"首词以外"的词**——否则 `星轨3 后星轨书 剧情 解析`
     * 会被 `《星轨3》官方网站` 蒙过。判断每个词时还会**扣除前面词已经用掉的汉字**：
     * 「后星轨书」的独有部分是「后」「书」，光有「星轨」不算命中。等于把"只认首词"
     * 从词级堵到字级。
     * - 单句长词（「四级听力精听方法」）词数=1 → 只要该词命中 2 个汉字即可，
     *   保留原来"同义改写/顿号改写"的宽容（CET 站标题写「四、六级」仍算命中）；
     * - 多词 query（「星轨3 主线剧情 章节」）→ 首词 + 至少一个后续词，只吃首词的站点卡直接判不相关；
     * - 英文/数字词元按整词匹配（长度 ≥2），仍照旧。
     * query 没有可判词元（纯符号）时不拦，避免误杀。
     */
    internal fun isRelevant(hits: List<Hit>, query: String): Boolean {
        val tokens = contentTokens(query)
        if (tokens.isEmpty()) return true
        val need = minOf(2, tokens.size)
        // 只看标题：正文/摘要是模型自己该判的，这里要的是"这条结果是不是在讲这件事"
        return hits.any { h ->
            var used = emptySet<Char>()
            var total = 0
            var tail = 0
            tokens.forEachIndexed { i, t ->
                if (tokenCovered(h.title, t, used)) {
                    total++
                    if (i > 0) tail++
                }
                used = used + t.filter { it.code in 0x4E00..0x9FFF }.toSet()
            }
            total >= need && (tokens.size < 2 || tail >= 1)
        }
    }

    /** query 的内容词元：按空白与中英标点切，丢掉纯符号片段 */
    internal fun contentTokens(q: String): List<String> =
        q.split(Regex("[\\s，。、；：？！,.;:!?/|\\\\()（）\\[\\]【】「」『』\"'“”‘’]+"))
            .map { it.trim() }
            .filter { t -> t.isNotEmpty() && (t.any { it.code in 0x4E00..0x9FFF } || asciiTokens(t).isNotEmpty()) }

    /**
     * 单个词元是否被这段文本覆盖：
     * - 词里有汉字 → 该词的**不同汉字**出现 ≥2 个（词只有 1 个汉字时就是出现 1 个）；
     *   [exclude] 是前面词已经用掉的汉字：词里剩下的独有部分仍要 ≥2 个才算命中
     *   （「后星轨书」扣掉「星轨」只剩「后」「书」，官网卡两个都没有 → 不算命中）；
     * - 纯英文/数字词 → 整词出现（不分大小写）。
     */
    internal fun tokenCovered(text: String, token: String, exclude: Set<Char> = emptySet()): Boolean {
        val cjk = token.filter { it.code in 0x4E00..0x9FFF }.toSet()
        if (cjk.isEmpty()) return asciiTokens(token).any { text.contains(it, ignoreCase = true) }
        val core = cjk - exclude
        val use = if (core.isNotEmpty()) core else cjk
        return use.count { text.contains(it) } >= minOf(2, use.size)
    }

    /** 英文/数字词元（小写，长度 ≥2） */
    internal fun asciiTokens(q: String): List<String> =
        Regex("[A-Za-z0-9]{2,}").findAll(q).map { it.value.lowercase() }.toList()

    /**
     * 站点卡退化（结构信号，2026-11-08 新增）：结果里 60% 以上是站点根页/官网首页/百科式入口，
     * 说明引擎匹配到的是**站名**而不是**内容**——这正是「首词站点匹配」退化的形态本身，
     * 而且它不依赖 query 与标题的字面重叠，字面判据再怎么被"同词共字"骗，这一道也照样报警。
     *
     * 只对多词 query 生效（单词品牌 query 返回官网是天经地义的，调用方已先判词数）。
     */
    internal fun looksLikeSiteCards(hits: List<Hit>): Boolean {
        if (hits.size < 3) return false
        val cards = hits.count { h ->
            isRootPage(h.url) || h.title.contains("官网") || h.title.contains("官方网站") ||
                h.title.contains("首页")
        }
        return cards * 10 >= hits.size * 6
    }

    /** 站点根页判定：路径为空、`/`、或只有一段且不带点的路径（如 `/zh-cn/`） */
    internal fun isRootPage(url: String): Boolean {
        val path = runCatching { java.net.URI(url).rawPath ?: "" }.getOrDefault("")
        val segs = path.split('/').filter { it.isNotBlank() }
        return segs.isEmpty() || (segs.size == 1 && !segs[0].contains('.'))
    }

    private fun containsCjk(q: String): Boolean = q.any { it.code in 0x4E00..0x9FFF }

    /** 360 兜底：桌面 UA 静态结果页；被风控（安全验证页/响应异常短）就空手回（保留 Bing 原始结果） */
    private fun fetch360(query: String, count: Int): List<Hit> {
        val url = "https://www.so.com/s?q=" + URLEncoder.encode(query, "UTF-8")
        val req = okhttp3.Request.Builder()
            .url(url)
            .header("User-Agent", DESKTOP_UA)
            .header("Accept-Language", "zh-CN,zh;q=0.9")
            .get()
            .build()
        val html = runCatching {
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                resp.body?.string()
            }
        }.getOrNull() ?: return emptyList()
        if (html.length < 4000 || html.contains("安全验证") || html.contains("wappass")) {
            return emptyList()
        }
        return parse360(html, count)
    }

    /**
     * 360 结果页解析：`<h3 class="res-title"><a href="…">标题</a>` 结构。
     * 链接优先取 a 的 **data-mdurl**（360 直接给出的真实 URL）；只有 so.com/link 跳转链
     * 而没有 data-mdurl 的是广告位/推广位，剔除。摘要在块后的 `<p class="res-desc">` 里
     * （`<span data-result-summary>` 包着正文摘要，前面可能还有一段灰色的日期 span）。
     *
     * 2026-11-08 两处修正（同一次真机报障）：
     * ① **`<h3` 与 `class` 之间可能有多个空格**（richsum 模板输出 `<h3   class="res-title">`）——
     *    旧正则写死一个空格，菜谱/富摘要类结果**整页解析成 0 条**，兜底白跑又退回 Bing 垃圾；
     * ② 摘要此前恒为空串——模型只拿得到标题，自然只能挑官网首页。现在按块尾部窗口取摘要，
     *    richsum 模板没有 res-desc 时退化为块内文本前 200 字，都没有就留空（不编）。
     */
    internal fun parse360(html: String, count: Int): List<Hit> {
        val out = mutableListOf<Hit>()
        val block = Pattern.compile("<h3\\s+class=\"res-title[^>]*>\\s*<a([^>]*)>([\\s\\S]*?)</a>")
        val m = block.matcher(html)
        while (m.find() && out.size < count) {
            val attrs = m.group(1) ?: continue
            val title = stripTags(m.group(2) ?: "")
            if (title.isBlank()) continue
            val md = Regex("data-mdurl=\"(https?://[^\"]+)\"").find(attrs)?.groupValues?.getOrNull(1)
            val href = Regex("href=\"(https?://[^\"]+)\"").find(attrs)?.groupValues?.getOrNull(1)
            val url = md ?: href ?: continue
            if (md == null && url.contains("so.com/link")) continue
            out.add(Hit(title, url, snippet360(html, m.end())))
        }
        return out
    }

    /** 结果块尾部的摘要：优先 `data-result-summary` 的 span，其次整个 res-desc 段，最后取块内文本 */
    private fun snippet360(html: String, from: Int): String {
        val tail = html.substring(from, minOf(html.length, from + 2000))
        val raw = Regex("<span data-result-summary[^>]*>([\\s\\S]*?)</span>").find(tail)?.groupValues?.getOrNull(1)
            ?: Regex("<p class=\"res-desc\"[^>]*>([\\s\\S]*?)</p>").find(tail)?.groupValues?.getOrNull(1)
        if (raw != null) return cleanSnippet(stripTags(raw))
        // richsum（菜谱/步骤/富摘要）：整块文本当摘要，截 200 字——总比空串让模型瞎猜强
        val blockEnd = tail.indexOf("<h3").let { if (it < 0) minOf(tail.length, 1200) else it }
        return cleanSnippet(stripTags(tail.substring(0, minOf(blockEnd, 1200))))
    }

    /** 摘要清洗：去掉残留的裸链接/半截标签（截断窗口可能切断 `<a`），压空白，截 200 字 */
    private fun cleanSnippet(s: String): String =
        s.replace(Regex("https?://\\S+"), " ")
            .substringBefore('<')
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(200)

    /** Bing 结果页解析：优先 b_algo 块内 <h2><a>（真正的新闻标题），跳过纯域名/裸链接式伪标题。
     *  2026-11-08：`<li` 与 `class` 之间允许任意空白（同 360 的坑，别写死一个空格）。 */
    internal fun parseBing(html: String, count: Int): List<Hit> {
        val out = mutableListOf<Hit>()
        val block = Pattern.compile("<li\\s+class=\"b_algo\"[\\s\\S]*?</li>")
        val h2anchor = Pattern.compile(
            "<h2[^>]*>\\s*<a[^>]+href=\"(https?://[^\"]+)\"[^>]*>([\\s\\S]*?)</a>",
            Pattern.CASE_INSENSITIVE
        )
        val anchor = Pattern.compile("<a[^>]+href=\"(https?://[^\"]+)\"[^>]*>([\\s\\S]*?)</a>", Pattern.CASE_INSENSITIVE)
        val para = Pattern.compile("<p[^>]*>([\\s\\S]*?)</p>", Pattern.CASE_INSENSITIVE)
        val domainLike = Regex("^[\\w.-]+\\.[a-z]{2,6}(/.*)?$")
        val m = block.matcher(html)
        while (m.find() && out.size < count) {
            val seg = m.group()
            var url: String? = null
            var title = ""
            val hm = h2anchor.matcher(seg)
            if (hm.find()) {
                url = hm.group(1)
                title = stripTags(hm.group(2) ?: "")
            }
            if (url == null || title.isBlank() || title.contains("http") || domainLike.matches(title)) {
                val am = anchor.matcher(seg)
                while (am.find()) {
                    val u = am.group(1) ?: continue
                    val t = stripTags(am.group(2) ?: "")
                    if (t.isBlank() || t.contains("http") || domainLike.matches(t)) continue
                    url = u; title = t; break
                }
            }
            val pm = para.matcher(seg)
            val snippet = if (pm.find()) stripTags(pm.group(1) ?: "") else ""
            if (url == null || title.isBlank()) continue
            out.add(Hit(title, url, snippet))
        }
        return out
    }

    private fun stripTags(s: String): String =
        s.replace(Regex("<[^>]+>"), "")
            .replace("&" + "nbsp;", " ")
            .replace("&" + "amp;", "&")
            .replace("&" + "quot;", String(charArrayOf(34.toChar())))
            .replace("&" + "#39;", "'")
            .replace("&" + "lt;", "<")
            .replace("&" + "gt;", ">")
            .replace(Regex("\\s+"), " ")
            .trim()
}
