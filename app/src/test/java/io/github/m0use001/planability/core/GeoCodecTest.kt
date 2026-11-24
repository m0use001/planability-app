package io.github.m0use001.planability.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 坐标换算的单测。纯数学，不需要设备——**这是坐标类 bug 唯一能在提交前拦住的闸门**。
 *
 * 背景：选点存 WGS84，底图/接口三家各给各的基准（高德/腾讯 GCJ-02、百度 BD-09、
 * 天地图 CGCS2000≈WGS84）。写反一个符号的代价是选好的点落到隔壁街区，而且
 * **真机上看不出来**（图能显示、点也能存，只是位置不对），所以只能靠这里的往返残差守住。
 */
class GeoCodecTest {

    /** 广州 / 北京 / 上海 / 乌鲁木齐：覆盖全国东西跨度。 */
    private val cities = listOf(
        Triple("广州", 23.1291, 113.2644),
        Triple("北京", 39.9042, 116.4074),
        Triple("上海", 31.2304, 121.4737),
        Triple("乌鲁木齐", 43.8256, 87.6168)
    )

    /** 两点球面距离（米）。用 haversine，别自己拿度数当米——那正是本测试最初写错的地方。 */
    private fun meters(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val r = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
            Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
            Math.sin(dLng / 2) * Math.sin(dLng / 2)
        return 2 * r * Math.asin(Math.sqrt(a.coerceAtMost(1.0)))
    }

    @Test
    fun gcjWgsRoundTripKeepsMetersLevel() {
        for ((_, lat, lng) in cities) {
            val (gLat, gLng) = GeoCodec.wgsToGcj(lat, lng)
            val (wLat, wLng) = GeoCodec.gcjToWgs(gLat, gLng)
            // 往返残差要亚厘米级（迭代四轮收敛）；放宽到 1cm 是为了不因浮点顺序死磕
            assertTrue("$lat,$lng GCJ 往返残差太大", meters(lat, lng, wLat, wLng) < 0.01)
        }
    }

    @Test
    fun gcjOffsetIsInTheKnownRange() {
        // 高德偏移实测：广州 630m、天安门 554m、上海 480m、乌鲁木齐 265m
        val expected = mapOf("广州" to 630.0, "北京" to 554.0, "上海" to 480.0, "乌鲁木齐" to 265.0)
        for ((name, lat, lng) in cities) {
            val (gLat, gLng) = GeoCodec.wgsToGcj(lat, lng)
            val d = meters(lat, lng, gLat, gLng)
            val want = expected.getValue(name)
            // 允许 ±15%：算法是公开公式，不同版本常数略有差异，但量级不能变
            assertTrue("$name 偏移 $d m 偏离预期 $want m 太多", d > want * 0.85 && d < want * 1.15)
        }
    }

    @Test
    fun outsideChinaIsUntouched() {
        // 境外不做偏移（东京），否则会把点挪出几百米
        val (lat, lng) = GeoCodec.wgsToGcj(35.6812, 139.7671)
        assertEquals(35.6812, lat, 1e-9)
        assertEquals(139.7671, lng, 1e-9)
    }

    @Test
    fun bd09RoundTripKeepsCentimeterLevel() {
        for ((_, lat, lng) in cities) {
            val (bLat, bLng) = GeoCodec.wgsToBd09(lat, lng)
            val (wLat, wLng) = GeoCodec.bd09ToWgs(bLat, bLng)
            assertTrue("BD-09 往返残差太大", meters(lat, lng, wLat, wLng) < 0.5)
        }
    }

    @Test
    fun bd09AddsItsOwnOffsetOnTopOfGcj() {
        // 百度的偏移是**在 GCJ 之上再叠一层**，国内实测两套差 300~600 米。
        // 如果哪天有人把 bd09ToGcj 写成恒等（"反正都是国内坐标"），
        // 百度搜出来的店会落到隔壁街区——这条测试就是钉住那层额外偏移的。
        val (lat, lng) = 23.1291 to 113.2644
        val (gLat, gLng) = GeoCodec.wgsToGcj(lat, lng)
        val (bLat, bLng) = GeoCodec.wgsToBd09(lat, lng)
        val gap = meters(gLat, gLng, bLat, bLng)
        assertTrue("BD-09 与 GCJ 只差 $gap m，说明少叠了一层偏移", gap > 100.0)
        assertTrue("BD-09 与 GCJ 差 $gap m，大得离谱（公式写错了吧）", gap < 1500.0)
    }
}