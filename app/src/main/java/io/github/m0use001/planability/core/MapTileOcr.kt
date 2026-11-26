package io.github.m0use001.planability.core

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sinh
import kotlin.math.sqrt

/**
 * 瓦片注记 OCR：把地图上「肉眼可见的楼号」（如 F3b座）取出来给选点用。
 *
 * 背景（2026-10-22 实测）：◆国际校区这类新楼群，**高德 Web POI 库没有 ◆/◆/◆**
 * （around 不带关键词 radius200 都是 0 条），逆地理只能抓到贴邻的 ◆ —— 但**注记层瓦片
 * （wprd style=8，与 MapPicker 的 SAT_LABEL 同源）里明明画着 F3b座/F3c座/F3d座**：
 * 那是烧进 PNG 的装饰文字，不是可查询的数据。于是把「看得见」变成「取得到」：
 *
 * 下载点击点附近 3×3 注记瓦片 → 拼成一张（黑底合成，注记是透明底白字）→
 * 用 app 已有的 mlkit 中文 OCR 读文字 → 文本块 bbox 中心换回地理坐标 →
 * 按离点击点距离排序返回。调用方摆成候选 chips（**不自动改名**——OCR 有误读可能）。
 *
 * 可行性已实证（PC 侧 RapidOCR 对 ◆ 点位的中心格读出 F3b座/F3a座/F2a座、
 * 北邻格 F3d座/F3c座，b/d 无混淆）；瓦片网格与标准 slippy 公式 0 偏移
 * （黄船小学校验：GCJ 反查的 tile 落在目击格）。
 */
object MapTileOcr {

    /** 高德注记瓦片（%d=子域 1-4，与 MapPicker 注记层同源；免费、无 key） */
    private const val TILE_URL =
        "https://wprd0%d.is.autonavi.com/appmaptile?lang=zh_cn&size=1&scale=1&style=8&x=%d&y=%d&z=%d"

    private const val TILE_PX = 256

    /** 一个瓦片注记文本：名称 + 其中心点（GCJ）+ 距查询点的米数 */
    data class Hit(val name: String, val lat: Double, val lng: Double, val distanceM: Double)

    private val client by lazy {
        OkHttpClient.Builder().connectTimeout(6, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS).build()
    }

    /** 经纬度 → 连续瓦片坐标（标准 slippy 公式）。纯函数，单测直接测 */
    fun latLngToTile(lat: Double, lng: Double, zoom: Int): Pair<Double, Double> {
        val n = (1 shl zoom).toDouble()
        val x = (lng + 180.0) / 360.0 * n
        val r = Math.toRadians(lat)
        val y = (1.0 - ln(sin(r) / cos(r) + 1.0 / cos(r)) / Math.PI) / 2.0 * n
        return x to y
    }

    /** 连续瓦片坐标 → 经纬度。纯函数 */
    fun tileToLatLng(tx: Double, ty: Double, zoom: Int): Pair<Double, Double> {
        val n = (1 shl zoom).toDouble()
        val lng = tx / n * 360.0 - 180.0
        val lat = Math.toDegrees(atan(sinh(Math.PI * (1.0 - 2.0 * ty / n))))
        return lat to lng
    }

    /** 拼图内像素 → 连续瓦片坐标（拼图 3×3 格，中心格 = 查询点所在格）。纯函数 */
    fun tileOfSheet(px: Double, py: Double, centerTx: Int, centerTy: Int): Pair<Double, Double> =
        (centerTx - 1 + px / TILE_PX) to (centerTy - 1 + py / TILE_PX)

    /** 两点球面距离（米）。纯函数 */
    fun distM(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val r = 6371000.0
        val p1 = Math.toRadians(lat1)
        val p2 = Math.toRadians(lat2)
        val dp = p2 - p1
        val dl = Math.toRadians(lng2 - lng1)
        val a = sin(dp / 2) * sin(dp / 2) + cos(p1) * cos(p2) * sin(dl / 2) * sin(dl / 2)
        return 2 * r * asin(sqrt(a))
    }

    /**
     * 点击点（GCJ）附近的瓦片注记文本，按距离升序，最多 8 条。
     * 任何一步失败（无网/OCR 异常）都安静返回空——注记是锦上添花，不能拖垮选点。
     *
     * @param zoom 当前地图层级；楼号注记在 z16+ 才齐全（低 zoom 被注记策略稀释）。
     */
    suspend fun nearby(gcjLat: Double, gcjLng: Double, zoom: Int): List<Hit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val z = zoom.coerceIn(14, 18)
                val (tx, ty) = latLngToTile(gcjLat, gcjLng, z)
                val cx = floor(tx).toInt()
                val cy = floor(ty).toInt()
                android.util.Log.d("PlanMapOCR", "nearby z=$z tile=($cx,$cy)")
                // 3×3 并发下载，拼成一张后单次 OCR（9 次独立 OCR 太慢）
                val parts = (-1..1).flatMap { dx -> (-1..1).map { dy -> dx to dy } }
                    .map { (dx, dy) -> async { fetchTile(cx + dx, cy + dy, z) to (dx to dy) } }
                    .awaitAll()
                android.util.Log.d("PlanMapOCR", "tiles ok=${parts.count { it.first != null }}/9")
                val sheet = Bitmap.createBitmap(TILE_PX * 3, TILE_PX * 3, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(sheet)
                // 注记是透明底白字：黑底合成才看得见（白底合成 = 白字融进白底，OCR 全瞎）
                canvas.drawColor(Color.BLACK)
                for (part in parts) {
                    val bytes = part.first
                    val dx = part.second.first
                    val dy = part.second.second
                    if (bytes == null) continue
                    val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: continue
                    canvas.drawBitmap(bmp, ((dx + 1) * TILE_PX).toFloat(), ((dy + 1) * TILE_PX).toFloat(), null)
                    bmp.recycle()
                }
                val lines = OcrHelper.recognizeLines(sheet)
                sheet.recycle()
                android.util.Log.d("PlanMapOCR", "ocr lines=${lines.map { it.text }}")
                lines.mapNotNull { line ->
                    val name = line.text.replace(" ", "").trim()
                    if (name.isEmpty()) return@mapNotNull null
                    val (pxT, pyT) = tileOfSheet(line.centerX.toDouble(), line.centerY.toDouble(), cx, cy)
                    val (nLat, nLng) = tileToLatLng(pxT, pyT, z)
                    Hit(name, nLat, nLng, distM(gcjLat, gcjLng, nLat, nLng))
                }.sortedBy { it.distanceM }.take(8)
            }.onFailure { e ->
                android.util.Log.w("PlanMapOCR", "nearby failed", e)
            }.getOrDefault(emptyList())
        }

    private fun fetchTile(x: Int, y: Int, z: Int): ByteArray? = runCatching {
        // 子域按坐标轮转（wprd01-04），避免单域限流
        val sub = 1 + Math.floorMod(x + y, 4)
        val req = Request.Builder()
            .url(String.format(TILE_URL, sub, x, y, z))
            .header("User-Agent", "Planability/1.0 (Android)")
            .get()
            .build()
        client.newCall(req).execute().use { r -> if (r.isSuccessful) r.body?.bytes() else null }
    }.getOrNull()

}
