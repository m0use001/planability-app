package io.github.m0use001.planability.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 地点本地别名的纯几何部分（JSON IO 依赖 Graph/Context，不进 JVM 单测）。
 *
 * 用例源自一次真实事故（坐标已脱敏为虚构值）：某大学国际校区 楼A（30.01104,120.40831）与 楼B 屋顶点击点
 * （30.01379,120.40344，相距数百米）——不同楼必须不能互相命中。
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
        val a = PlaceAliasStore.Alias("楼B", 30.0110, 120.4083, 1)
        val b = PlaceAliasStore.Alias("楼C", 30.0120, 120.4090, 2)
        // 点位离 a ~7m、离 b ~120m：命中 a
        val hit = PlaceAliasStore.matchAt(listOf(a, b), 30.01105, 120.40835, 60.0)
        assertEquals("楼B", hit?.name)
    }

    @Test
    fun matchIgnoresBeyondRadius() {
        // 点位离唯一别名数百米（楼A↔楼B 的距离量级）：不同楼，绝不能命中
        val a = PlaceAliasStore.Alias("楼A座", 30.0110, 120.4083, 1)
        assertNull(PlaceAliasStore.matchAt(listOf(a), 30.0137, 120.4034, 60.0))
    }

    @Test
    fun matchEmptyListIsNull() {
        assertNull(PlaceAliasStore.matchAt(emptyList(), 23.0, 113.4, 60.0))
    }
}
