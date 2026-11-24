package io.github.m0use001.planability.ui.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * 「纸条」钻入几何的纯函数钉（v68，2026-11-21 用户三条拍板之二）。
 *
 * [drillTiles] 是 BoardDrill 唯一的几何真源：起点必须与 SplitBoards 的两板布局
 * 逐像素对齐（否则「点外观」的第一帧会跳），终点必须满足用户原话的三条——
 * 概览完全出屏右缘、主板落在副板位、细分页落在主框位。
 */
class DrillGeometryTest {

    // 与 SplitBoards 的现实口径一致：视口 1200dp，详情板 380dp，缝 12dp
    private val windowW = 1200f
    private val secW = 380f
    private val gap = 12f
    private val mainW = windowW - secW - gap // 808

    @Test
    fun `起点与 SplitBoards 两板几何逐像素对齐`() {
        val t = drillTiles(0f, windowW, secW, gap)
        assertEquals(0f, t.mainX, 1e-4f)
        assertEquals(mainW, t.mainW, 1e-4f)
        assertEquals(mainW + gap, t.secX, 1e-4f)
        assertEquals(secW, t.secW, 1e-4f)
        // 细分页整块停在视口左缘之外
        assertEquals(-mainW, t.detailX, 1e-4f)
        assertEquals(mainW, t.detailW, 1e-4f)
    }

    @Test
    fun `终点满足用户三条_概览出屏_主板进副板_细分页进主框`() {
        val t = drillTiles(1f, windowW, secW, gap)
        assertEquals("细分页应落在主框位（x=0）", 0f, t.detailX, 1e-4f)
        assertEquals(mainW + gap, t.mainX, 1e-4f)
        assertEquals("主板滑进副板位后宽度应是副板宽（自动改排版的终点）", secW, t.mainW, 1e-4f)
        assertTrue(
            "概览必须完全划出屏幕右缘（左缘 ≥ 视口右缘）",
            t.secX >= windowW - 1e-4f
        )
    }

    @Test
    fun `中途主板宽度连续插值（自动改排版）`() {
        val t = drillTiles(0.5f, windowW, secW, gap)
        val expectedMid = mainW + (secW - mainW) * 0.5f
        assertEquals(expectedMid, t.mainW, 1e-4f)
    }

    @Test
    fun `全程瓦片不重叠且不出相框左右界`() {
        var p = 0f
        while (p <= 1f) {
            val t = drillTiles(p, windowW, secW, gap)
            val detailRight = t.detailX + t.detailW
            val mainRight = t.mainX + t.mainW
            assertTrue("p=$p 细分页与主板重叠", detailRight <= t.mainX + 1e-3f)
            assertTrue("p=$p 主板与副板重叠", mainRight <= t.secX + 1e-3f)
            assertTrue("p=$p 副板没有一直延到/超出视口右缘（出屏途中被截断）", t.secX + t.secW >= windowW - 1e-3f)
            assertTrue("p=$p 主板左缘越出视口左缘", t.mainX >= -1e-3f)
            assertTrue("p=$p 细分页右缘越出视口", detailRight <= windowW + 1e-3f)
            p += 0.01f
        }
    }

    @Test
    fun `视口不足两板宽时主板宽度收敛不为负`() {
        // 600dp 视口装不下 380dp 副板 + 缝：mainW 收敛到 0，几何不炸
        val t0 = drillTiles(0f, 300f, secW, gap)
        assertTrue("主板宽度必须非负", t0.mainW >= 0f)
        val t1 = drillTiles(1f, 300f, secW, gap)
        assertEquals(0f, t1.detailX, 1e-4f)
    }

    @Test
    fun `进度超界被夹取`() {
        val t = drillTiles(1.7f, windowW, secW, gap)
        val t1 = drillTiles(1f, windowW, secW, gap)
        assertEquals(t1.detailX, t.detailX, 1e-4f)
        assertTrue(abs(t.mainW - t1.mainW) < 1e-4f)
    }
}
