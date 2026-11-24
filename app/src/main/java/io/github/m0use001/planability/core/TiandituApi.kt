package io.github.m0use001.planability.core

import org.json.JSONObject

/**
 * 天地图（api.tianditu.gov.cn）POI 搜索 + 逆地理。
 *
 * ## 为什么值得加
 *
 * ① **中文注记新**——高德路网瓦片用户嫌"过期没参考价值"，天地图是国家级的现状数据；
 * ② **坐标不用换算**——天地图对外是 CGCS2000，与 WGS84 在米级以下重合（我国境内差异 cm 级），
 * 本文件**不做任何偏移转换**，省掉一整类误差；
 * ③ 免费日配额对个人项目足够；④ 同一个 tk 还能开底图瓦片（见 MapPicker 的 WMTS 图层）。
 *
 * ## 参数名是 `tk` 不是 `key`（2026-10-09 实测踩到）
 *
 * 天地图所有接口的参数名统一是 `tk`。写成 `key=` 会被网关拒成
 * `{"code":308010,"message":"请求参数错误或参数缺失"}`，看起来像"key 无效"，
 * 实际是**参数名错了**——很容易误判成申请失败。
 *
 * ## 路径也和我最初写的不一样
 *
 * - 逆地理：`/geocoder`（**不是** `/api/v2/geocoder`，那个路径 404）
 * - POI 搜索：`/v2/search`，参数装在 `postStr`（JSON 字符串）里
 *
 * ## key
 *
 * 天地图开发者账号（个人实名）申请 API Key，填进 `local.properties` 的 `tianditu_key`。
 * 控制台里要把「搜索服务 v2.0」勾上（图3 那种全选即可）；**没勾时的症状很有迷惑性**：
 * `/v2/search` 恒返回 `{"msg":"请求异常","status":400}`，看起来像参数写错，其实是权限。
 */
object TiandituApi {

    private const val HOST = "https://api.tianditu.gov.cn"

    data class Poi(val name: String, val address: String, val lat: Double, val lng: Double)

    fun configured(): Boolean = PlaceHttp.tiandituKey() != null

    /**
     * POI 搜索。
     *
     * 天地图 V2 搜索**必须**带两个我最初漏掉的参数（2026-10-09 实测：全漏就是
     * `{"msg":"【搜索请求异常】参数格式错误","status":400}`，看不出是缺参数）：
     * - URL 上的 **`type=query`**（漏了直接 400）
     * - postStr 里的 **`queryType`**：1 普通 / 2 视野内 / 3 周边 / 12 行政区划 / 13 多边形 / 14 统计
     *
     * 有参考点（当前定位）时用 **queryType=3 周边搜索**（要 queryRadius + pointLonlat），
     * 没有就用 **queryType=1 普通搜索**（要 level + mapBound，mapBound 合法值域 -180,-90 至 180,90）。
     *
     * 出参注意：POI 坐标字段叫 **`lonlat`**（不是 `location`），格式是 `经,纬`。
     */
    suspend fun search(
        keyword: String,
        near: Pair<Double, Double>? = null,
        limit: Int = 10
    ): Result<List<Poi>> = runCatching {
        val tk = PlaceHttp.tiandituKey() ?: throw IllegalStateException("未配置 tianditu_key")
        val q = keyword.trim()
        if (q.isEmpty()) return@runCatching emptyList()
        val post = JSONObject()
            .put("keyWord", q)
            .put("start", 0)
            .put("count", limit.coerceIn(1, 20))
        if (near != null) {
            post.put("queryType", 3)
            post.put("queryRadius", 5000)
            // 天地图坐标就是 CGCS2000（≈WGS84），**不需要任何偏移换算**
            post.put("pointLonlat", "${near.second},${near.first}")
        } else {
            post.put("queryType", 1)
            post.put("level", 12)
            post.put("mapBound", "-180,-90,180,90")
        }
        val json = PlaceHttp.getJson(
            "$HOST/v2/search?postStr=" + PlaceHttp.enc(post.toString()) + "&type=query&tk=$tk"
        )
        // 出错时错误信息在 status.cndesc。**infocode=1000 是「服务正常」**（不是 0！），
        // 2001 参数错 / 2002 格式错 / 2003 缺参数 / 301013 权限类型错。
        // 我最初按「非 0 即失败」判，结果成功响应反被当成失败抛出去（真机才暴露）。
        val st = json.optJSONObject("status")
        if (st != null && st.optInt("infocode", 1000) != 1000) {
            throw IllegalStateException(
                "天地图 POI 搜索失败：" + st.optString("cndesc").ifBlank { "infocode=" + st.optInt("infocode") }
            )
        }
        val pois = json.optJSONArray("pois")
        if (pois == null) return@runCatching emptyList()
        (0 until pois.length()).mapNotNull { i ->
            val o = pois.optJSONObject(i) ?: return@mapNotNull null
            val parts = o.optString("lonlat").split(",")
            if (parts.size != 2) return@mapNotNull null
            val lon = parts[0].toDoubleOrNull() ?: return@mapNotNull null
            val lat = parts[1].toDoubleOrNull() ?: return@mapNotNull null
            val distM = o.optString("distance")
            val distPart = if (distM.isNotBlank()) "· 约 $distM" else ""
            Poi(
                name = o.optString("name"),
                address = o.optString("address") + distPart,
                lat = lat,
                lng = lon
            )
        }
    }

    /** 逆地理：坐标 → 中文地址。入参/出参都是 CGCS2000，本函数不做换算。 */
    suspend fun reverseGeocode(lat: Double, lng: Double): String {
        val tk = PlaceHttp.tiandituKey() ?: throw IllegalStateException("未配置 tianditu_key")
        // postStr 里是 lon/lat，写反不会报错只会静默给错地址——所以这里显式按 key 名组装
        val body = JSONObject()
            .put("lon", lng)
            .put("lat", lat)
            .put("ver", 1)
            .toString()
        val json = PlaceHttp.getJson(
            "$HOST/geocoder?tk=$tk&postStr=" + PlaceHttp.enc(body)
        )
        val msg = json.optString("msg")
        if (msg.isNotBlank() && msg != "ok") {
            throw IllegalStateException("天地图逆地理失败：$msg")
        }
        val res = json.optJSONObject("result") ?: throw IllegalStateException("天地图逆地理无结果")
        // 实测返回的是 formatted_address（不是 address）；拿不到再退回省市区拼接
        val full = res.optString("formatted_address").trim()
        if (full.isNotEmpty()) return full
        val ac = res.optJSONObject("addressComponent")
        val parts = ArrayList<String>()
        for (k in listOf("province", "city", "county", "address")) {
            val v = ac?.optString(k) ?: ""
            if (v.isNotBlank()) parts.add(v)
        }
        val joined = parts.joinToString("")
        if (joined.isBlank()) throw IllegalStateException("这个点没有可用地址")
        return joined
    }
}