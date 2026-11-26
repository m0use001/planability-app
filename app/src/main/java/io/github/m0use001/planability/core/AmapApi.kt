package io.github.m0use001.planability.core

import io.github.m0use001.planability.BuildConfig
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * 高德 Web 服务客户端：POI 关键词搜索（place/text）+ 逆地理编码（geocode/regeo）。
 *
 * ## 为什么要有这一层
 *
 * 地图选点面板（`ui/edit/MapPicker.kt`）此前只能「点地图取坐标」，用户要找一个具体
 * 地点（某栋教学楼、某家店）只能自己在地图上瞎划——底图上没有 POI 注记，更没法按名字找。
 * 这里接高德 Web服务补上这两件事。
 *
 * ## 坐标系是本文件最容易出错的地方
 *
 * 高德接口**收发的都是 GCJ-02（火星坐标）**，而应用存储口径一直是 WGS84（`GeoCodec`
 * 就是为此存在的）。所以本文件：
 * - 入参 [search] 的 `nearLat/nearLng` 是 **WGS84**，内部转 GCJ-02 再发给高德；
 * - 出参 [Poi.lat]/[Poi.lng] 与 [reverseGeocode] 的坐标是 **WGS84**（内部转过了）。
 *
 * 这样调用方（地图选点、地理围栏、分类地点）全都只碰 WGS84，转换只有这一处。
 *
 * ## key 与配额
 *
 * key 来自 `local.properties` 的 `amap_key` → `BuildConfig.AMAP_KEY`（个人 key，
 * 日配额约 3000 次）。**没配 key 时 [search] 返回带原因的错误而不是抛异常** ——
 * 选点是可选功能，不能因为没配 key 就让整个面板崩掉。
 */
object AmapApi {

    /** 一个 POI 搜索结果。坐标是 **WGS84**（已从高德的 GCJ-02 转好）。 */
    data class Poi(
        val name: String,
        /** 行政区 + 街道门牌（高德 address 字段，空时回退为行政区） */
        val address: String,
        val lat: Double,
        val lng: Double
    )

    private val client by lazy {
        okhttp3.OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
            .build()
    }

    private fun key(): String? = BuildConfig.AMAP_KEY.ifBlank { null }

    /** v14.2：key 有没有配。给 [PlaceSearch] 判断这个源参不参与（没配就压根不发请求）。 */
    fun configured(): Boolean = key() != null

    private fun get(url: String): JSONObject {
        val req = okhttp3.Request.Builder()
            .url(url)
            .header("User-Agent", "Planability/1.0 (Android)")
            .get()
            .build()
        return client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IllegalStateException("高德返回 HTTP ${resp.code}")
            val body = resp.body?.string() ?: throw IllegalStateException("高德返回空响应")
            JSONObject(body)
        }
    }

    /**
     * POI 关键词搜索。
     *
     * @param nearLat/nearLng **WGS84** 的参考点，给了就按距离排序（搜「星巴克」时附近的结果排前面）。
     * @return 失败时是 [Result.failure]，消息里带高德的 info（配额超限/未授权等），供 UI 直接显示。
     */
    suspend fun search(keywords: String, nearLat: Double? = null, nearLng: Double? = null, limit: Int = 10): Result<List<Poi>> =
        withContext(Dispatchers.IO) {
            val k = key() ?: return@withContext Result.failure(IllegalStateException("未配置 amap_key，POI 搜索不可用"))
            val q = keywords.trim()
            if (q.isEmpty()) return@withContext Result.success(emptyList())
            runCatching {
                val sb = StringBuilder("https://restapi.amap.com/v3/place/text?key=")
                    .append(k)
                    .append("&keywords=").append(URLEncoder.encode(q, "UTF-8"))
                    .append("&offset=").append(limit)
                if (nearLat != null && nearLng != null && (nearLat != 0.0 || nearLng != 0.0)) {
                    // 高德 location 参数是 GCJ-02，且必须经纬度对调成 lng,lat
                    val (gLat, gLng) = GeoCodec.wgsToGcj(nearLat, nearLng)
                    sb.append("&location=").append(gLng).append(",").append(gLat)
                        .append("&sortrule=distance")
                }
                val json = get(sb.toString())
                if (json.optString("status") != "1") {
                    throw IllegalStateException("高德 POI 搜索失败：" + json.optString("info").ifBlank { "未知错误" })
                }
                val arr = json.optJSONArray("pois") ?: return@runCatching emptyList()
                (0 until arr.length()).mapNotNull { i ->
                    val o = arr.optJSONObject(i) ?: return@mapNotNull null
                    val loc = o.optString("location")
                    if (loc.isBlank()) return@mapNotNull null
                    val parts = loc.split(",")
                    if (parts.size != 2) return@mapNotNull null
                    val gLat = parts[1].toDoubleOrNull() ?: return@mapNotNull null
                    val gLng = parts[0].toDoubleOrNull() ?: return@mapNotNull null
                    // 高德回的是 GCJ-02，出口统一成 WGS84
                    val (wLat, wLng) = GeoCodec.gcjToWgs(gLat, gLng)
                    Poi(
                        name = o.optString("name"),
                        address = listOf(o.optString("pname"), o.optString("cityname"), o.optString("adname"), o.optString("address"))
                            .map { it.trim() }.filter { it.isNotEmpty() }
                            .joinToString("").ifBlank { o.optString("adname") },
                        lat = wLat,
                        lng = wLng
                    )
                }
            }
        }

    /**
     * 逆地理编码：坐标 → 中文地址（GCJ-02 入，**WGS84 出**）。
     *
     * 用于「点地图取点」后告诉用户这是哪儿——没有名字的坐标用户没法确认选对了没有。
     *
     * 取名优先级（2026-10-20 改）：**离点最近的 POI 名（≤500m）> formatted_address > 省市区拼接**。
     * 之前只取 `formatted_address`，在园区/校园里它常常只是「◆大学」这种大地名，
     * 而地图注记上明明写着「F3c栋」——用户点过去却拿到大地名（真机反馈）。
     * `extensions=all` 才会带 `pois[]`；注意 **pois 数组顺序不按距离**，
     * 大 AOI 恒在第一位，必须按自带 distance 字段排序后取最近。
     *
     * 2026-10-21：返回值从单个名字改成「最近名 + 候选列表」。
     * ◆/◆ 这种贴邻楼栋，POI 库里坐标差几十米最近的就是另一个，只回一个名字
     * 猜错了用户没法救；把 500m 内的前几个一起带回，选点面板摆成候选 chips，
     * 点一下就纠正，不用重新选点。
     */
    suspend fun reverseGeocode(lat: Double, lng: Double): Result<Pair<String, List<String>>> =
        withContext(Dispatchers.IO) {
            val k = key() ?: return@withContext Result.failure(IllegalStateException("未配置 amap_key，地址解析不可用"))
            runCatching {
                // 入参是 WGS84（应用存储口径），高德要 GCJ-02
                val (gLat, gLng) = GeoCodec.wgsToGcj(lat, lng)
                val json = get(
                    "https://restapi.amap.com/v3/geocode/regeo?key=" + k +
                        "&location=" + gLng + "," + gLat + "&extensions=all"
                )
                if (json.optString("status") != "1") {
                    throw IllegalStateException("高德逆地理失败：" + json.optString("info").ifBlank { "未知错误" })
                }
                val rc = json.optJSONObject("regeocode")
                // POI 名优先，但**必须按离点的实际距离排序后取最近的一个**：
                // 高德返回的数组顺序里，大 AOI（整个校区/园区）恒在第一位，
                // 按数组顺序取永远是「◆大学」这种大地名（用户 2026-10-20 反馈）。
                // 每条 POI 自带 distance 字段（离请求点的米数），缺了就用经纬度现算。
                val named = mutableListOf<Pair<Double, String>>()
                rc?.optJSONArray("pois")?.let { arr ->
                    for (i in 0 until arr.length()) {
                        val o = arr.optJSONObject(i) ?: continue
                        val name = o.optString("name").trim()
                        if (name.isEmpty()) continue
                        val d = o.optString("distance").toDoubleOrNull() ?: run {
                            val ll = o.optString("location").split(",")
                            if (ll.size != 2) continue
                            val pla = ll[1].toDoubleOrNull() ?: continue
                            val plo = ll[0].toDoubleOrNull() ?: continue
                            val p1 = Math.toRadians(gLat)
                            val p2 = Math.toRadians(pla)
                            val dp = p2 - p1
                            val dl = Math.toRadians(plo - gLng)
                            val a = kotlin.math.sin(dp / 2) * kotlin.math.sin(dp / 2) +
                                kotlin.math.cos(p1) * kotlin.math.cos(p2) *
                                kotlin.math.sin(dl / 2) * kotlin.math.sin(dl / 2)
                            6371000.0 * 2 * kotlin.math.asin(kotlin.math.sqrt(a))
                        }
                        named += d to name
                    }
                }
                named.sortBy { it.first }
                // 500m 内的都算"脚下"候选：第一个是猜测值，其余给用户当纠正入口。
                // distinct 防止高德同一条 POI 出现两次（实测偶发）。
                val near = named.filter { it.first <= 500.0 }.map { it.second }.distinct()
                if (near.isNotEmpty()) {
                    return@runCatching near.first() to near.take(6)
                }
                val full = rc?.optString("formatted_address")?.trim().orEmpty()
                if (full.isNotEmpty()) return@runCatching full to emptyList<String>()
                val ac = rc?.optJSONObject("addressComponent")
                val joined = listOf("province", "city", "district", "streetNumber")
                    .mapNotNull { ac?.optString(it)?.takeIf { s -> s.isNotBlank() && s != "[]" } }
                    .joinToString("")
                (joined.ifBlank { throw IllegalStateException("这个点没有可用地址") }) to emptyList<String>()
            }
        }
}