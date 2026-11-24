package io.github.m0use001.planability.core

import io.github.m0use001.planability.data.Graph
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 地点本地别名（修「点 楼B 却显示 楼A」的正解）。
 *
 * 背景：某大学国际校区这类新楼群，**高德 POI 库没有 楼B/楼C/楼D**
 * （2026-10-22 实测：周边 500m 搜 楼B 为空；卫星图上的白字是注记层、
 * 烧在瓦片图片里，不是数据），而 MapPicker 的「最近具名 POI」规则会稳稳
 * 抓到贴邻的 楼A（70m）——按设计运行，但张冠李戴。
 *
 * 这里把用户的手改变成持久记忆：确认选点时按「名称 + 坐标」记一条别名，
 * 下次点到同楼（[MATCH_RADIUS_M] 内）直接用用户自己的名字，不再问高德。
 * 只存坐标与名字：JSON 文件（filesDir/place-aliases.json），无网络、无 DB 迁移。
 */
object PlaceAliasStore {

    /** 命中半径：一栋教学楼约 40~80m 宽，点两次都在楼顶时 <50m；取 60m 防手抖 */
    const val MATCH_RADIUS_M = 60.0

    /** 学习半径：确认选点的名字记到该点 30m 范围（同一楼只留最近一次的名字） */
    private const val LEARN_RADIUS_M = 30.0

    private const val MAX_ALIASES = 200

    data class Alias(val name: String, val lat: Double, val lng: Double, val at: Long)

    /** 两点球面距离（米）。纯函数，单测直接测 */
    fun distanceM(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val r = 6371000.0
        val p1 = Math.toRadians(lat1)
        val p2 = Math.toRadians(lat2)
        val dp = Math.toRadians(lat2 - lat1)
        val dl = Math.toRadians(lng2 - lng1)
        val a = sin(dp / 2) * sin(dp / 2) + cos(p1) * cos(p2) * sin(dl / 2) * sin(dl / 2)
        return 2 * r * asin(sqrt(a))
    }

    /** 别名表里 [radiusM] 内最近的一条；纯函数，单测直接测 */
    fun matchAt(aliases: List<Alias>, lat: Double, lng: Double, radiusM: Double): Alias? =
        aliases.mapNotNull { a ->
            val d = distanceM(lat, lng, a.lat, a.lng)
            if (d <= radiusM) d to a else null
        }.minByOrNull { it.first }?.second

    @Volatile
    private var cache: List<Alias>? = null

    private fun file(): File = File(Graph.appContext.filesDir, "place-aliases.json")

    private fun load(): List<Alias> {
        cache?.let { return it }
        val list = runCatching {
            val root = JSONObject(file().readText(Charsets.UTF_8))
            val arr = root.optJSONArray("aliases") ?: JSONArray()
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val n = o.optString("name").trim()
                if (n.isEmpty()) return@mapNotNull null
                Alias(n, o.optDouble("lat", Double.NaN), o.optDouble("lng", Double.NaN), o.optLong("at", 0))
            }.filter { !it.lat.isNaN() && !it.lng.isNaN() }
        }.getOrDefault(emptyList())
        cache = list
        return list
    }

    private fun save(list: List<Alias>) {
        runCatching {
            val arr = JSONArray()
            list.forEach { a ->
                arr.put(JSONObject().put("name", a.name).put("lat", a.lat).put("lng", a.lng).put("at", a.at))
            }
            file().writeText(JSONObject().put("aliases", arr).toString(), Charsets.UTF_8)
        }
        cache = list
    }

    /** [lat]/[lng] 附近的用户别名；没有返回 null */
    suspend fun nearest(lat: Double, lng: Double, radiusM: Double = MATCH_RADIUS_M): Alias? =
        withContext(Dispatchers.IO) { matchAt(load(), lat, lng, radiusM) }

    /**
     * 删掉 [radiusM] 内的别名。自愈用：某条别名与 pin 正下方的瓦片楼号**对不上**，
     * 说明它是别的楼留下的旧记忆（例如 楼F 被记在了 楼E 附近），留着只会继续劫持落点。
     */
    suspend fun forgetNear(lat: Double, lng: Double, radiusM: Double = LEARN_RADIUS_M) =
        withContext(Dispatchers.IO) {
            val cur = load()
            val kept = cur.filter { distanceM(lat, lng, it.lat, it.lng) > radiusM }
            if (kept.size != cur.size) save(kept)
        }

    /**
     * 用户确认选点时学习一条：同楼（30m 内）旧别名整体替换为这次的名字——
     * 用户把 楼A 改成「楼B」后，旧的 楼A 记忆必须让位，同一格只留最新语义。
     */
    suspend fun remember(name: String, lat: Double, lng: Double) = withContext(Dispatchers.IO) {
        val clean = name.trim()
        if (clean.isEmpty()) return@withContext
        val now = AppTime.nowMillis()
        val cur = load().toMutableList()
        cur.removeAll { distanceM(lat, lng, it.lat, it.lng) <= LEARN_RADIUS_M }
        cur.add(Alias(clean, lat, lng, now))
        save(cur.sortedByDescending { it.at }.take(MAX_ALIASES))
    }
}
