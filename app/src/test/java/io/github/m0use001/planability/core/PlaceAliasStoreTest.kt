package io.github.m0use001.planability.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 地点本地别名的纯几何部分（JSON IO 依赖 Graph/Context，不进 JVM 单测）。
 *
 * 用例取自真实事故：◆国际校区 ◆（23.01104,113.40831）与 ◆ 屋顶点击点
 * （23.01379,113.40344 WGS 换算后相距约 300m）——不同楼必须不能互相命中。
 */
class PlaceAliasStoreTest {

    @Test
    fun distanceMatchesLatitudeRuleOfThumb() {
        // 纬度每 0.001° ≈ 111.2m（赤道附近实测口径）
        val d = PlaceAliasStore.distanceM(23.0, 113.4, 23.001, 113.4)
        assertEquals(111.2, d, 1.5)
    }

    @Test
    fun matchPicksNearestInsideRadius() {
        val a = PlaceAliasStore.Alias("◆", 23.0110, 113.4083, 1)
        val b = PlaceAliasStore.Alias("◆", 23.0120, 113.4090, 2)
        // 点位离 a ~5m、离 b ~130m：命中 a
        val hit = PlaceAliasStore.matchAt(listOf(a, b), 23.01105, 113.40835, 60.0)
        assertEquals("◆", hit?.name)
    }

    @Test
    fun matchIgnoresBeyondRadius() {
        // 点位离唯一别名约 300m（◆↔◆ 的真实距离量级）：不同楼，绝不能命中
        val a = PlaceAliasStore.Alias("F3a座", 23.0110, 113.4083, 1)
        assertNull(PlaceAliasStore.matchAt(listOf(a), 23.0137, 113.4034, 60.0))
    }

    @Test
    fun matchEmptyListIsNull() {
        assertNull(PlaceAliasStore.matchAt(emptyList(), 23.0, 113.4, 60.0))
    }
}
