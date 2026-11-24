package io.github.m0use001.planability.ui.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [deckSlot] 几何钉（2026-11-21「笔记三级全做成滑动」）。
 * 口径与 [DrillGeometryTest] 一致：windowW=1200, secW=380, gap=12 → mainW=808。
 */
class DeckGeometryTest {
    private val windowW = 1200f
    private val secW = 380f
    private val gap = 12f
    private val mainW = windowW - secW - gap // 808

    /** 整数层：index=level 在主框、index=level-1 在副框，其余出屏。 */
    @Test
    fun settledLevel_mainAndSecondaryAndOffscreen() {
        val level = 2
        // 主框那块
        val main = deckSlot(2, level.toFloat(), windowW, secW, gap)
        assertEquals(0f, main.x, 0.01f)
        assertEquals(mainW, main.w, 0.01f)
        // 副框那块（上一级主板滑进副框位）
        val sec = deckSlot(1, level.toFloat(), windowW, secW, gap)
        assertEquals(mainW + gap, sec.x, 0.01f)
        assertEquals(secW, sec.w, 0.01f)
        // 更浅一级：完全滑出右缘
        val older = deckSlot(0, level.toFloat(), windowW, secW, gap)
        assertTrue(older.x >= windowW - 0.01f)
        // 更深一级：完全在左缘外
        val deeper = deckSlot(3, level.toFloat(), windowW, secW, gap)
        assertTrue(deeper.x + deeper.w <= 0.01f)
    }

    /** level=1（根层）：网格主框 + 最近笔记副框，笔记列表/编辑器停屏外左。 */
    @Test
    fun rootLevel_gridMain_recentSecondary() {
        val grid = deckSlot(1, 1f, windowW, secW, gap)
        assertEquals(0f, grid.x, 0.01f)
        assertEquals(mainW, grid.w, 0.01f)
        val recent = deckSlot(0, 1f, windowW, secW, gap)
        assertEquals(mainW + gap, recent.x, 0.01f)
        assertEquals(secW, recent.w, 0.01f)
        val list = deckSlot(2, 1f, windowW, secW, gap)
        assertTrue(list.x + list.w <= 0.01f)
    }

    /** 中点：主板从 mainW 线性收窄到 secW（逐帧改排版的来源），两块活动板不重叠。 */
    @Test
    fun midpoint_linearInterpolation_noOverlap() {
        val p = 1.5f
        val grid = deckSlot(1, p, windowW, secW, gap)   // 主→副途中
        val list = deckSlot(2, p, windowW, secW, gap)   // 左→主途中
        assertEquals(mainW + (secW - mainW) * 0.5f, grid.w, 0.01f)
        assertEquals(-mainW * 0.5f, list.x, 0.01f)
        assertEquals(mainW, list.w, 0.01f)
        // 网格板右缘与列表板左缘之间至少留缝
        assertTrue(grid.x + grid.w + gap <= list.x + 0.01f || list.x + list.w <= grid.x + 0.01f)
        // 最近笔记正在滑出右缘
        val recent = deckSlot(0, p, windowW, secW, gap)
        assertEquals(mainW + gap + secW * 0.5f, recent.x, 0.01f)
    }

    /** 全程扫描：任何进度下可见板两两不重叠、不出视口外正方向。 */
    @Test
    fun sweep_noOverlap_withinViewport() {
        var p = 1f
        while (p <= 3f + 0.001f) {
            val slots = (0..3).map { deckSlot(it, p, windowW, secW, gap) }
            val visible = slots.filter { it.x < windowW && it.x + it.w > 0f }
            for (i in visible.indices) for (j in i + 1 until visible.size) {
                val a = visible[i]; val b = visible[j]
                val overlap = a.x < b.x + b.w && b.x < a.x + a.w
                assertTrue("p=$p 板 $i/$j 重叠", !overlap)
            }
            visible.forEach { s ->
                // 板从左侧滑入时 x 本就为负（纸条在框外），只约束不越过**右缘起点**
                assertTrue("p=$p 板越出右缘", s.x <= windowW + 0.01f)
                assertTrue("p=$p 板宽>0", s.w > 0f)
            }
            p += 0.1f
        }
    }

    /** 越界 clamp：progress 小数再多也收敛，mainW 永不为负（窄视口保护）。 */
    @Test
    fun narrowViewport_mainWNonNegative() {
        val slots = deckSlot(1, 99f, 400f, secW, gap)
        assertTrue(slots.w >= 0f)
    }
}
