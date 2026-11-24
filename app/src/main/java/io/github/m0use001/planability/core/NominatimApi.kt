package io.github.m0use001.planability.core

import org.json.JSONArray
import org.json.JSONObject

/**
 * OpenStreetMap Nominatim：免 key 的兜底源。
 *
 * ## 定位要摆清楚
 *
 * - **只在前面的源都没搜出东西时才发请求**（见 [PlaceSearch.search]）。理由有三条：
 *   政策限制（Nominatim 使用政策要求 ≤1 req/s，且必须带可识别 UA）、中文 POI 覆盖最弱、
 *   结果多是英文/拼音地名，混进中文列表反而像噪声。真搜不到时它才是真有价值（境外、
 *   没有商业 POI 的野外点）。
 * - 坐标就是 WGS84，**不涉及任何偏移**。
 * - 覆盖弱是客观事实，不包装：搜不到时 UI 会照实说"已查 N 个来源都没有，换个说法或直接在地图上点"。
 */
object NominatimApi {

    fun search(
        keyword: String,
        near: Pair<Double, Double>? = null,
        limit: Int = 10
    ): Result<List<PlaceSearch.Hit>> = runCatching {
        val q = keyword.trim()
        if (q.isEmpty()) return@runCatching emptyList()
        val sb = StringBuilder("/search?format=jsonv2&addressdetails=1")
            .append("&q=").append(PlaceHttp.enc(q))
            .append("&limit=").append(limit.coerceIn(1, 20))
            .append("&accept-language=zh-CN")
        if (near != null) {
            // viewbox 是左,上,右,下（lng/lat），bounded=0 表示不丢弃框外结果
            val d = 0.02 // 约 2 公里，够覆盖"附近"
            sb.append("&viewbox=")
                .append(near.second - d).append(",").append(near.first + d)
                .append(",").append(near.second + d).append(",").append(near.first - d)
                .append("&bounded=0")
        }
        val arr = JSONArray(PlaceHttp.nominatimJson(sb.toString()))
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val lat = o.optString("lat").toDoubleOrNull() ?: return@mapNotNull null
            val lon = o.optString("lon").toDoubleOrNull() ?: return@mapNotNull null
            PlaceSearch.Hit(
                name = o.optString("name").ifBlank { o.optString("display_name").substringBefore(",") },
                address = o.optString("display_name"),
                lat = lat,
                lng = lon,
                sources = listOf(PlaceSearch.Source.OSM)
            )
        }
    }

    suspend fun reverseGeocode(lat: Double, lng: Double): String = runCatching {
        PlaceHttp.nominatimJson("/reverse?format=jsonv2&accept-language=zh-CN&lat=$lat&lon=$lng")
            .let { JSONObject(it) }
            .optString("display_name")
            .trim()
            .ifBlank { error("这个点没有可用地址") }
    }.getOrThrow()
}