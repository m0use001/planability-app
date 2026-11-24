package io.github.m0use001.planability.core

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 瓦片注记 OCR 的纯几何部分（下载/OCR 依赖网络与 Context，不进 JVM 单测）。
 *
 * 断言值按标准 slippy 公式对虚构示例坐标推导（原真实坐标已脱敏）：
 * - 示例小学校验瓦片网格 0 偏移（GCJ 反查 tile 落在目击格）；
 * - 楼B 点位（GCJ 30.011247,120.408957）反查 z18 = (218751, 108144)，
 *   该格 OCR 读出 楼B座/楼A座/楼G座。
 */
class MapTileOcrTest {

    @Test
    fun knownPoisMapToExpectedTiles() {
        // 示例锚点（虚构坐标）
        val (x1, y1) = MapTileOcr.latLngToTile(30.077786, 120.422387, 18)
        assertEquals(218760, x1.toInt())
        assertEquals(108088, y1.toInt())
        // 楼B 点位
        val (x2, y2) = MapTileOcr.latLngToTile(30.011247, 120.408957, 18)
        assertEquals(218751, x2.toInt())
        assertEquals(108144, y2.toInt())
    }

    @Test
    fun tileRoundTripPreservesCoordinates() {
        val (tx, ty) = MapTileOcr.latLngToTile(30.011247, 120.408957, 18)
        val (lat, lng) = MapTileOcr.tileToLatLng(tx, ty, 18)
        assertEquals(30.011247, lat, 1e-6)
        assertEquals(120.408957, lng, 1e-6)
    }

    @Test
    fun sheetPixelMapsIntoNeighborTileOfCenter() {
        // 拼图左上角（0,0）应落在中心格的西-北邻格左上角
        val (tx, ty) = MapTileOcr.tileOfSheet(0.0, 0.0, 218751, 108144)
        assertEquals(218750.0, tx, 1e-9)
        assertEquals(108143.0, ty, 1e-9)
        // 拼图中心（384,384）= 中心格正中。注意瓦片 y 向**南**递增，
        // 画布顶 dy=-1 是北邻格（cy-1），中心格是 dy=0（cy），故中心点=cy+0.5。
        val (cx, cy) = MapTileOcr.tileOfSheet(384.0, 384.0, 218751, 108144)
        assertEquals(218751.5, cx, 1e-9)
        assertEquals(108144.5, cy, 1e-9)
    }

    @Test
    fun distanceMatchesLatitudeRuleOfThumb() {
        assertEquals(111.2, MapTileOcr.distM(23.0, 113.4, 23.001, 113.4), 1.5)
    }
}
