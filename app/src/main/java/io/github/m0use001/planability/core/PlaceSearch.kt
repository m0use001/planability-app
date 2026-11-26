package io.github.m0use001.planability.core

import io.github.m0use001.planability.BuildConfig
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * v14.2：地点检索的公共家什——一个 OkHttp 客户端 + 两种取法（GET / JSON POST）。
 *
 * 单独立文件是因为现在有 5 个源要用同一套超时/UA/错误口径，各写一遍的结果一定是
 * 「有的源 12秒超时、有的 3 秒、错误文案三种写法」。**失败原因必须能原样带给 UI**——
 * 用户要知道自己搜的是哪几个源、哪个源为什么没出结果（没配 key？配额超了？超时？）。
 */
internal object PlaceHttp {
    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .build()
    }

    /** GET 取 JSON。腾讯/百度/高德/天地图的搜索接口都是 GET。 */
    fun getJson(url: String): JSONObject {
        val req = Request.Builder().url(url)
            .header("User-Agent", "Planability/1.0 (Android)")
            .get()
            .build()
        return client.newCall(req).execute().use { resp ->
            val body = resp.body?.string()
            if (!resp.isSuccessful) {
                throw IllegalStateException("HTTP ${resp.code}${body?.let { " · " + it.take(120) }.orEmpty()}")
            }
            JSONObject(body ?: throw IllegalStateException("空响应"))
        }
    }

    /** POST 一段 JSON 取 JSON。天地图逆地理的 `postStr` 是这么调的。 */
    fun postJson(url: String, jsonBody: String): JSONObject {
        val req = Request.Builder().url(url)
            .header("User-Agent", "Planability/1.0 (Android)")
            .post(jsonBody.toRequestBody("application/json".toMediaType()))
            .build()
        return client.newCall(req).execute().use { resp ->
            val body = resp.body?.string()
            if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}")
            JSONObject(body ?: throw IllegalStateException("空响应"))
        }
    }

    fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    /** 三家的 key 都在 local.properties，读 BuildConfig；空串= 没配。 */
    fun tencentKey(): String? = BuildConfig.TENCENT_KEY.trim().ifBlank { null }
    fun baiduKey(): String? = BuildConfig.BAIDU_KEY.trim().ifBlank { null }
    fun tiandituKey(): String? = BuildConfig.TIANDITU_KEY.trim().ifBlank { null }

    /**
     * Nominatim 的使用政策要求「每请求必须带可识别 UA」且 ≤1 req/s。
     * 我们只在别的源都没出结果时才用它（见 [PlaceSearch]），天然满足频率要求。
     */
    fun nominatimJson(path: String): String {
        val req = Request.Builder().url("https://nominatim.openstreetmap.org$path")
            .header("User-Agent", "Planability/1.0 (Android)")
            .get()
            .build()
        return client.newCall(req).execute().use { resp ->
            val body = resp.body?.string()
            if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}")
            body ?: throw IllegalStateException("空响应")
        }
    }
}

/**
 * v14.2：**多源地点检索**——一次搜索并发打所有可用源，合并去重，每条标来源。
 *
 * ## 为什么要合并而不是"选一个源"
 *
 * 用户反馈"有些地方还是搜不到"（高德覆盖有缺口：新园区、校园内部、小区里的楼栋）。
 * 逐个试源等于让用户自己记住哪个源有货；合并后一次搜索把所有源的货一起摆出来，
 * 同一条地点在多源都命中时合成一条并标 `高德+腾讯`——**这也顺手说明"两家都认为它在这儿"**。
 *
 * ## 源的有无是数据决定的，不是编译期决定的
 *
 * 每个源是否参与只看 key 有没有配（[Source.active]）。没配的源**根本不发请求**，
 * 也不报"未配置"当错误——用户没申请腾讯 key 不该看到一片红色报错，只该看到
 * `来源：高德`（而不是"另外三个源挂了"）。
 *
 * ## 单个源失败不拖累其他
 *
 * 每个源独立超时（[search] 的 `timeoutMs`），谁慢谁出局，出局原因进 [Outcome.problems]
 * 原样显示。合并成功的路径永远优先于报错。
 *
 * ## 坐标
 *
 * 各源出参分别是 GCJ-02（高德/腾讯）、BD-09（百度）、CGCS2000（天地图，≈WGS84）。
 * 每个 Api 文件内部就转好了，**门面只出 WGS84**。
 */
object PlaceSearch {

    /** 检索源。cn 是 UI 上显示的名字，也是 problems 里的名字。 */
    enum class Source(val cn: String, val keyName: String?) {
        AMAP("高德", "amap_key"),
        TENCENT("腾讯", "tencent_key"),
        BAIDU("百度", "baidu_key"),
        TIANDITU("天地图", "tianditu_key"),
        OSM("OSM", null);

        /** 这个源现在能不能用（key 配了 / 本身免 key）。 */
        val active: Boolean get() = when (this) {
            AMAP -> AmapApi.configured()
            TENCENT -> PlaceHttp.tencentKey() != null
            BAIDU -> PlaceHttp.baiduKey() != null
            TIANDITU -> PlaceHttp.tiandituKey() != null
            OSM -> true
        }
    }

    /** 一条合并后的地点。坐标恒为 **WGS84**。 */
    data class Hit(
        val name: String,
        val address: String,
        val lat: Double,
        val lng: Double,
        /** 命中它的源，按 [Source] 枚举序（稳定、去重后） */
        val sources: List<Source>
    )

    /**
     * 逆地理结果：[best] 是猜的最近名，[candidates] 是贴邻候选（含 best 本身，按距离序）。
     *
     * 候选存在的原因：◆/◆ 这类贴邻楼栋，POI 库坐标差几十米"最近"就会排错，
     * 单回一个名字猜错了用户没法救；把前几个一起带回，选点面板摆成候选 chips，
     * 点一下纠正，不用重新选点。
     */
    data class ReverseHit(
        val best: String,
        val candidates: List<String>,
        val source: Source
    )

    /** 搜索总结果。hits 为空但 problems 也为空 = 源都没问题，就是真没这地方。 */
    data class Outcome(
        val hits: List<Hit>,
        /** 形如「腾讯：HTTP 402配额超限」，一条一个源 */
        val problems: List<String>,
        /** 实际发过请求的源（未配 key 的不算） */
        val asked: List<Source>
    )

    /** 当前真正能用的源。UI 可以在搜索框旁提示"来源：…"。 */
    fun activeSources(): List<Source> = Source.values().filter { it.active }

    /**
     * 关键词搜索：所有可用源并发，结果合并去重。
     *
     * @param nearLat/nearLng **WGS84** 参考点（通常是当前定位）。给了就按距离排序，
     *   并让支持"附近检索"的源（腾讯/百度）走 nearby 模式，结果更贴近用户当前位置。
     * @param timeoutMs 单源超时。宁可少一个源，也不要一个死源把整次搜索拖成十几秒。
     */
    suspend fun search(
        keyword: String,
        nearLat: Double? = null,
        nearLng: Double? = null,
        perSource: Int = 10,
        timeoutMs: Int = 7000
    ): Outcome = withContext(Dispatchers.IO) {
        val kw = keyword.trim()
        if (kw.isEmpty()) return@withContext Outcome(emptyList(), emptyList(), emptyList())
        val near = if (nearLat != null && nearLng != null && (nearLat != 0.0 || nearLng != 0.0)) {
            nearLat to nearLng
        } else null

        val keyed = listOf(Source.AMAP, Source.TENCENT, Source.BAIDU, Source.TIANDITU).filter { it.active }
        val raw = ArrayList<Pair<Source, List<Hit>>>()
        val problems = ArrayList<String>()
        val asked = ArrayList<Source>()

        // ---- 配了 key 的源：全并发 ----
        keyed.forEach { src ->
            asked.add(src)
            val r = runCatching {
                withTimeoutOrNull(timeoutMs.toLong()) { runSource(src, kw, near, perSource) }
            }
            val got: List<Hit>? = r.getOrNull()
            if (r.isFailure) problems += "${src.cn}：${friendly(r.exceptionOrNull())}"
            if (got != null) raw += src to got
        }

        // ---- OSM 兜底：只在上面一个都没搜出东西时才发 ----
        // 不每次都打有三个原因：① Nominatim 政策要求 ≤1 req/s 且必须带可识别 UA，
        // 我们不该为一个覆盖最弱的源消耗政策额度；② 中文 POI 覆盖最弱；
        // ③ 它是英文/拼音地名，混进中文结果里反而像噪声。真搜不到时兜底才是它的价值所在。
        if (raw.all { it.second.isEmpty() }) {
            // NominatimApi 自己是 Result（要单独判失败原因），这里再包一层取它的值
            val r = runCatching {
                withTimeoutOrNull(timeoutMs.toLong()) { NominatimApi.search(kw, near, perSource).getOrThrow() }
            }
            val got: List<Hit>? = r.getOrNull()
            if (r.isFailure) problems += "${Source.OSM.cn}：${friendly(r.exceptionOrNull())}"
            else if (!got.isNullOrEmpty()) {
                asked.add(Source.OSM)
                raw += Source.OSM to got
            }
        }

        val merged = merge(raw.flatMap { it.second }, near)
        Outcome(merged, problems, asked)
    }

    /** 逆地理：坐标 → 中文地址。按源优先级取**第一个成功的**（不是合并，地址越短越没用）。 */
    suspend fun reverse(lat: Double, lng: Double): Result<ReverseHit> = withContext(Dispatchers.IO) {
        val order = Source.values().filter { it != Source.OSM && it.active }
        var last: Throwable? = null
        for (src in order) {
            val r = runCatching {
                when (src) {
                    // 高德带贴邻候选（pois[] 按距离排），其余源只有单个名字
                    Source.AMAP -> AmapApi.reverseGeocode(lat, lng).getOrThrow()
                        .let { (best, cands) -> ReverseHit(best, cands, src) }
                    Source.TENCENT -> ReverseHit(TencentApi.reverseGeocode(lat, lng), emptyList(), src)
                    Source.BAIDU -> ReverseHit(BaiduApi.reverseGeocode(lat, lng), emptyList(), src)
                    Source.TIANDITU -> ReverseHit(TiandituApi.reverseGeocode(lat, lng), emptyList(), src)
                    else -> null
                }
            }
            val hit = r.getOrNull()
            if (hit != null && hit.best.isNotBlank()) return@withContext Result.success(hit)
            last = r.exceptionOrNull() ?: IllegalStateException("没解析出地址")
        }
        if (order.isEmpty()) {
            Result.failure(IllegalStateException("没有可用的地址解析源（local.properties 里一个 key 都没配）"))
        } else {
            Result.failure(last ?: IllegalStateException("没解析出地址"))
        }
    }

    private suspend fun runSource(
        src: Source,
        kw: String,
        near: Pair<Double, Double>?,
        limit: Int
    ): List<Hit> = when (src) {
        // 每个源都把坐标转成 WGS84 了（见各 Api 文件），这里只做一层壳：补上来源标记
        Source.AMAP -> AmapApi.search(kw, near?.first, near?.second, limit).getOrThrow().map {
            Hit(it.name, it.address, it.lat, it.lng, listOf(src))
        }
        Source.TENCENT -> TencentApi.search(kw, near, limit).getOrThrow().map {
            Hit(it.name, it.address, it.lat, it.lng, listOf(src))
        }
        Source.BAIDU -> BaiduApi.search(kw, near, limit).getOrThrow().map {
            Hit(it.name, it.address, it.lat, it.lng, listOf(src))
        }
        Source.TIANDITU -> TiandituApi.search(kw, near, limit).getOrThrow().map {
            Hit(it.name, it.address, it.lat, it.lng, listOf(src))
        }
        Source.OSM -> NominatimApi.search(kw, near, limit).getOrThrow()
    }

    /**
     * 合并去重。
     *
     * 判定"同一条"用**名字 + 距离**双条件：名字标准化后相等或互相包含，且相距 <80 米。
     * 单看名字会把同名连锁店合并掉；单看距离会把相邻两栋楼合并掉。
     * 80 米这个门限取的是"同一个院/同一栋的误差上限"：百度/高德对同一 POI 的成对坐标
     * 实测能差 100~300 米（各家的纠正是近似公式，不是真值），而真·相邻两家门店通常 >50 米。
     */
    private fun merge(all: List<Hit>, near: Pair<Double, Double>?): List<Hit> {
        val out = ArrayList<Hit>()
        for (h in all) {
            val i = out.indexOfFirst { sameName(it.name, h.name) && distM(it.lat, it.lng, h.lat, h.lng) < 80.0 }
            if (i >= 0) {
                val old = out[i]
                out[i] = old.copy(
                    // 哪个源的地址更完整用哪个（短的会被长的替掉）
                    address = if (h.address.length > old.address.length) h.address else old.address,
                    sources = (old.sources + h.sources).distinct()
                )
            } else {
                out += h
            }
        }
        val list = if (near != null) out.sortedBy { distM(near.first, near.second, it.lat, it.lng) } else out
        return list.take(20)
    }

    /** 名字标准化后相等或短的是长的子串（去掉空格与常见标点，"XX大厦" vs "XX大厦A座" 算同一条候选）。 */
    private fun sameName(a: String, b: String): Boolean {
        val x = norm(a)
        val y = norm(b)
        if (x == y) return true
        val (s, l) = if (x.length <= y.length) x to y else y to x
        return s.length >= 2 && l.contains(s)
    }

    private fun norm(s: String): String =
        s.lowercase().filter { it.isLetterOrDigit() }

    /** 两点距离（米）。门面里自己算，不去依赖 notify 包里的 GeoWatch。 */
    private fun distM(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val r = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
            Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
            Math.sin(dLng / 2) * Math.sin(dLng / 2)
        return 2 * r * Math.asin(Math.sqrt(a.coerceAtMost(1.0)))
    }

    /** 把异常翻译成一句人话（技术栈不给用户看，但"超时/没配 key/配额"要说清）。 */
    private fun friendly(t: Throwable?): String {
        val msg = t?.message.orEmpty()
        return when {
            t is kotlinx.coroutines.TimeoutCancellationException || msg.contains("timeout", true) -> "超时"
            msg.contains("未配置") -> msg
            msg.contains("配额") || msg.contains("quota", true) || msg.contains("QPS", true) ||
                msg.contains("调用量") || msg.contains("限流") || msg.contains("上限") -> "配额/频率超限"
            msg.isBlank() -> t?.javaClass?.simpleName ?: "未知错误"
            else -> msg.take(60)
        }
    }
}