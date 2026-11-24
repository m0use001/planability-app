package io.github.m0use001.planability.core

/**
 * 腾讯位置服务（apis.map.qq.com）地点搜索 + 逆地理。
 *
 * ## 为什么要加这个源
 *
 * 用户反馈"有些地方还是搜不到"——高德 POI 覆盖有缺口（新建园区、校园内部、小区里的楼栋）。
 * 腾讯的 POI 库与高德不同源，两边互补；实测里腾讯常常能捞到高德没有的**小区/园区内部点位**。
 *
 * ## key
 *
 * 腾讯位置服务控制台建 **WebServiceAPI** 类型的 key（个人可申请，有免费日配额），
 * 填进 `local.properties` 的 `tencent_key`。没配 = 这个源不参与（见 [PlaceSearch]），
 * 不是错误——用户没申请腾讯 key 是正常状态。
 *
 * ## 坐标
 *
 * 腾讯接口**收发都是 GCJ-02**，与高德同一基准，所以复用 [GeoCodec] 的 wgsToGcj/gcjToWgs 即可。
 * 近/远参数 `location` 是 `lat,lng`（**纬,经**），与高德的 `lng,lat` 相反——写反了会搜到地球另一面。
 */
object TencentApi {

    private const val BASE = "https://apis.map.qq.com/ws"

    /** 一条腾讯 POI。坐标已转成 WGS84。 */
    data class Poi(val name: String, val address: String, val lat: Double, val lng: Double)

    fun configured(): Boolean = PlaceHttp.tencentKey() != null

    /**
     * 关键词搜索。
     *
     * @param near WGS84 参考点；给了就用 `boundary=nearby(lat,lng,半径)` + 按距离排序。
     * @return 失败时是 [Result.failure]，消息带腾讯的 message（"此key未开启此功能"/"配额超限"等）。
     */
    suspend fun search(
        keyword: String,
        near: Pair<Double, Double>? = null,
        limit: Int = 10
    ): Result<List<Poi>> = runCatching {
        val key = PlaceHttp.tencentKey() ?: error("未配置 tencent_key")
        val q = keyword.trim()
        if (q.isEmpty()) return@runCatching emptyList()
        val sb = StringBuilder(BASE).append("/place/v1/search?key=").append(key)
            .append("&keyword=").append(PlaceHttp.enc(q))
            .append("&page_size=").append(limit.coerceIn(1, 20))
        // ⚠ boundary 是**必填**的（2026-10-09 实测：不带直接 status 348「参数错误」，
        // 带错格式也 348，只有 region()/nearby() 认）。所以"用户还没定位成功"那条路
        // 也必须给 boundary——用全国范围，否则没定位的人永远搜不到任何东西。
        if (near != null) {
            val (gLat, gLng) = GeoCodec.wgsToGcj(near.first, near.second)
            // 全国范围 + 按距离排序，而不是 nearby(半径1km)：搜"异地地名"时后者直接搜不到，
            // 前者照样能出结果、只是最近的那条排第一。
            sb.append("&boundary=").append(PlaceHttp.enc("region(全国,0)"))
            sb.append("&location=").append(gLat).append(",").append(gLng)
            sb.append("&orderby=_distance")
        } else {
            sb.append("&boundary=").append(PlaceHttp.enc("region(全国,0)"))
            sb.append("&orderby=_weight")
        }
        val json = PlaceHttp.getJson(sb.toString())
        val status = json.optInt("status", -1)
        if (status != 0) {
            error("腾讯 POI 搜索失败：" + (json.optString("message").ifBlank { "status=$status" }))
        }
        val arr = json.optJSONArray("data") ?: return@runCatching emptyList()
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val loc = o.optJSONObject("location") ?: return@mapNotNull null
            val gLat = loc.optDouble("lat", 0.0)
            val gLng = loc.optDouble("lng", 0.0)
            if (gLat == 0.0 && gLng == 0.0) return@mapNotNull null
            val (wLat, wLng) = GeoCodec.gcjToWgs(gLat, gLng)
            Poi(
                name = o.optString("title").ifBlank { o.optString("name") },
                address = o.optString("address"),
                lat = wLat,
                lng = wLng
            )
        }
    }

    /** 逆地理：WGS84 入参 → 中文地址。 */
    suspend fun reverseGeocode(lat: Double, lng: Double): String {
        val key = PlaceHttp.tencentKey() ?: throw IllegalStateException("未配置 tencent_key")
        val (gLat, gLng) = GeoCodec.wgsToGcj(lat, lng)
        val json = PlaceHttp.getJson("$BASE/geocoder/v1/?key=$key&location=$gLat,$gLng")
        if (json.optInt("status", -1) != 0) {
            throw IllegalStateException("腾讯逆地理失败：" + json.optString("message"))
        }
        val res = json.optJSONObject("result") ?: throw IllegalStateException("腾讯逆地理无结果")
        val full = res.optString("address").trim()
        if (full.isNotEmpty()) return full
        val ac = res.optJSONObject("address_component")
        val parts = ArrayList<String>()
        for (k in listOf("province", "city", "district", "street")) {
            val v = ac?.optString(k) ?: ""
            if (v.isNotBlank()) parts.add(v)
        }
        val joined = parts.joinToString("")
        if (joined.isBlank()) throw IllegalStateException("这个点没有可用地址")
        return joined
    }
}