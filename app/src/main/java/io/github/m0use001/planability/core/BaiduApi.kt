package io.github.m0use001.planability.core

/**
 * 百度地图开放平台 地点检索 + 逆地理。
 *
 * ## 坐标是这里最容易翻车的地方
 *
 * 百度出参是 **BD-09**，它不是 GCJ-02，而是在 GCJ 之上**再叠一层**自己的旋转平移。
 * 三套坐标在国内实测两两差 300~600 米：把 BD-09 当 WGS 存，选好的点会落到隔壁街区。
 * 所以出参一律走 [GeoCodec.bd09ToWgs]（BD-09 → GCJ-02 → WGS84）两步回到应用口径。
 *
 * ## key
 *
 * 百度开放平台建应用 → 「服务端 key」或「Android key」，填进 `local.properties` 的 `baidu_key`。
 * 服务端 key 需在控制台配 IP 白名单（本机开发填本机出口 IP 即可）；没配 key 则该源不参与。
 *
 * ## region 是必填
 *
 * 百度 `place/v2/search` 的 `region` **不能省**，省了返回 401。有附近点时走 `nearby`
 * （纯坐标检索，不需要 region），这也比"全国"更准。
 */
object BaiduApi {

    private const val BASE = "https://api.map.baidu.com"

    data class Poi(val name: String, val address: String, val lat: Double, val lng: Double)

    fun configured(): Boolean = PlaceHttp.baiduKey() != null

    /**
     * 关键词检索。
     *
     * ## 两处实测踩坑（2026-10-09）
     *
     * ① 附近检索**不能**用 `place/v2/nearby`：那个路径现在一律 404（一整个 HTML 错误页），
     * 带不带 radius_limit、location 顺序、是否带 tag 全试过都是 404；同一 key 的
     * `place/v2/search` 正常。
     * ② 也**不能**在 search 上加 `location`+`radius` 做距离加权：加上之后结果直接
     * 清零（total=0，`radius_limit=false` 也一样），「银行」这种全国有几千条的大词
     * 同样清零。所以百度这一路就老老实实只传 `region=全国`。
     *
     * 距离排序不靠百度：门面（[PlaceSearch]）拿到各源结果后会按参考点重排，
     * 不依赖单个源的私有参数——这也是为什么这里传参越少越稳。
     */
    suspend fun search(
        keyword: String,
        near: Pair<Double, Double>? = null,
        limit: Int = 10
    ): Result<List<Poi>> = runCatching {
        val key = PlaceHttp.baiduKey() ?: error("未配置 baidu_key")
        val q = keyword.trim()
        if (q.isEmpty()) return@runCatching emptyList()
        val size = limit.coerceIn(1, 20)
        // region 是必填，省了直接 401；"全国"是百度认可的写法
        val json = PlaceHttp.getJson(
            "$BASE/place/v2/search?query=${PlaceHttp.enc(q)}&region=${PlaceHttp.enc("全国")}" +
                "&scope=2&output=json&page_size=$size&ak=$key"
        )
        // 百度 status：0 成功，非 0 是参数/配额/key 问题，message 里会说
        if (json.optInt("status", -1) != 0) {
            error("百度 POI 搜索失败：" + json.optString("message").ifBlank { "status=${json.optInt("status")}" })
        }
        // 实测（2026-10-09）`results` 在**顶层**，与 status/total 同级；
        // 而 nearby / detail 接口是嵌在 `result` 里的。两种都认——
        // 只认一种的症状都是「配了 key 但永远搜不到东西」（静默空列表，最难查的一类）。
        val items = json.optJSONArray("results")
            ?: json.optJSONObject("result")?.optJSONArray("results")
        if (items == null) return@runCatching emptyList()
        (0 until items.length()).mapNotNull { i ->
            val o = items.optJSONObject(i) ?: return@mapNotNull null
            val loc = o.optJSONObject("location") ?: return@mapNotNull null
            val bLat = loc.optDouble("lat", 0.0)
            val bLng = loc.optDouble("lng", 0.0)
            if (bLat == 0.0 && bLng == 0.0) return@mapNotNull null
            val (wLat, wLng) = GeoCodec.bd09ToWgs(bLat, bLng)
            Poi(
                name = o.optString("name"),
                address = o.optString("address"),
                lat = wLat,
                lng = wLng
            )
        }
    }

    /**
     * 逆地理：WGS84 入参 → 中文地址。
     *
     * 用 `coordtype=gcj02` 把 WGS 转成 GCJ 再问百度，这样百度不需要把地址反算回 BD-09，
     * 地址文字（省市区+门牌）不受两套偏移影响。
     */
    suspend fun reverseGeocode(lat: Double, lng: Double): String {
        val key = PlaceHttp.baiduKey() ?: throw IllegalStateException("未配置 baidu_key")
        val (gLat, gLng) = GeoCodec.wgsToGcj(lat, lng)
        val json = PlaceHttp.getJson(
            "$BASE/reverse_geocoding/v3/?output=json&coordtype=gcj02&location=$gLng,$gLat&ak=$key"
        )
        if (json.optInt("status", -1) != 0) {
            throw IllegalStateException("百度逆地理失败：" + json.optString("message"))
        }
        val res = json.optJSONObject("result") ?: throw IllegalStateException("百度逆地理无结果")
        val full = res.optString("formatted_address").trim()
        if (full.isNotEmpty()) return full
        val ac = res.optJSONObject("addressComponent")
        val parts = ArrayList<String>()
        for (k in listOf("province", "city", "district", "street")) {
            val v = ac?.optString(k) ?: ""
            if (v.isNotBlank() && v != "[]") parts.add(v)
        }
        val joined = parts.joinToString("")
        if (joined.isBlank()) throw IllegalStateException("这个点没有可用地址")
        return joined
    }
}