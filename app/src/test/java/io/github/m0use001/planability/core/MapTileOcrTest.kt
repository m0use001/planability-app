package io.github.m0use001.planability.core

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 瓦片注记 OCR 的纯几何部分（下载/OCR 依赖网络与 Context，不进 JVM 单测）。
 *
 * 断言值来自 2026-10-22 的真实探测：
 * - 黄船小学校验瓦片网格 0 偏移（GCJ 反查 tile 落在目击格）；
 * - ◆ 点位（GCJ 23.011247,113.408957）反查 z18 = (213653, 113846)，
 *   该格 OCR 读出 F3b座/F3a座/F2a座。
 */
class MapTileOcrTest {

    @Test
    fun knownPoisMapToExpectedTiles() {
        // 黄船小学（目击吻合的锚点）
        val (x1, y1) = MapTileOcr.latLngToTile(23.077786, 113.422387, 18)
        assertEquals(213663, x1.toInt())
        assertEquals(113793, y1.toInt())
        // ◆ 点位
        val (x2, y2) = MapTileOcr.latLngToTile(23.011247, 113.408957, 18)
        assertEquals(213653, x2.toInt())
        assertEquals(113846, y2.toInt())
    }

    @Test
    fun tileRoundTripPreservesCoordinates() {
        val (tx, ty) = MapTileOcr.latLngToTile(23.011247, 113.408957, 18)
        val (lat, lng) = MapTileOcr.tileToLatLng(tx, ty, 18)
        assertEquals(23.011247, lat, 1e-6)
        assertEquals(113.408957, lng, 1e-6)
    }

    @Test
    fun sheetPixelMapsIntoNeighborTileOfCenter() {
        // 拼图左上角（0,0）应落在中心格的西-北邻格左上角
        val (tx, ty) = MapTileOcr.tileOfSheet(0.0, 0.0, 213653, 113846)
        assertEquals(213652.0, tx, 1e-9)
        assertEquals(113845.0, ty, 1e-9)
        // 拼图中心（384,384）= 中心格正中。注意瓦片 y 向**南**递增，
        // 画布顶 dy=-1 是北邻格（cy-1），中心格是 dy=0（cy），故中心点=cy+0.5。
        val (cx, cy) = MapTileOcr.tileOfSheet(384.0, 384.0, 213653, 113846)
        assertEquals(213653.5, cx, 1e-9)
        assertEquals(113846.5, cy, 1e-9)
    }

    @Test
    fun distanceMatchesLatitudeRuleOfThumb() {
        assertEquals(111.2, MapTileOcr.distM(23.0, 113.4, 23.001, 113.4), 1.5)
    }
}
