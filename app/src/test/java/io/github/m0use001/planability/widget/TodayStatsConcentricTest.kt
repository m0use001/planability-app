package io.github.m0use001.planability.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「今日数据」**2×2 四胶囊组**版式的几何回归闸（2026-11-02 晚）。
 *
 * 历史：2026-11-01 胶囊行条+托盘同心多轮收敛 → 2026-11-02 白天四环版式（环浮在托盘上）
 * → 同日晚上用户「锁死 4×2（宽 4）并拆分成 4 个胶囊组」：组边界改由胶囊承担，
 * 每个胶囊内嵌小环 + 名称/数值两行。
 *
 * 本测试锁定四个不变量（与 TodayStatsWidget/TodayStatsArt.render 对应）：
 *
 *  1. **锁 4×2**：XML targetCell 4×2 且 resizeMode=none（不给拉伸手柄）；
 *  2. 托盘半轴 = 固定 26dp × density，不随卡片尺寸缩放（「有的大有的小」的老病根）；
 *  3. 胶囊 2×2 等分：两列/两行 + 内距 + 缝严格填满卡面（不多一像素、不少一像素）；
 *  4. 环几何自洽：环径同时受胶囊高（减内距）与胶囊宽约束、描边 13%、
 *     环心百分比（"100%" 四字符）必然画得进内孔。
 */
class TodayStatsConcentricTest {

    private data class Geo(
        val capW: Float, val capH: Float,
        val ringD: Float, val stroke: Float, val pctInk: Float,
    )

    /** 与 TodayStatsArt.render 对应的纯几何部分 */
    private fun geo(w: Float, h: Float, density: Float): Geo {
        val marginX = w * 0.035f
        val marginY = h * 0.075f
        val gapX = w * 0.028f
        val gapY = h * 0.075f
        val capW = (w - marginX * 2f - gapX) / 2f
        val capH = (h - marginY * 2f - gapY) / 2f
        val pad = capH * 0.09f
        val ringD = minOf(capH - pad * 2f, capW * 0.46f)
            .coerceAtLeast(capH * 0.34f)
        return Geo(capW, capH, ringD, ringD * 0.13f, ringD * 0.30f)
    }

    @Test
    fun `组件声明锁死 4x2 且不可拉伸`() {
        val xml = java.io.File("app/src/main/res/xml/widget_todaystats.xml").let {
            if (it.isFile) it.readText() else java.io.File("src/main/res/xml/widget_todaystats.xml").readText()
        }
        assertTrue("targetCellWidth 应为 4（宽 4）", xml.contains("android:targetCellWidth=\"4\""))
        assertTrue("targetCellHeight 应为 2", xml.contains("android:targetCellHeight=\"2\""))
        assertTrue(
            "resizeMode 应为 none（锁死 4×2，不给拉伸手柄——旧值 horizontal|vertical）",
            xml.contains("android:resizeMode=\"none\"")
        )
    }

    @Test
    fun `整卡托盘已删除（2026-11-03 用户「圆角矩形背景删掉」）`() {
        val src = java.io.File("app/src/main/java/io/github/m0use001/planability/widget/TodayStatsWidget.kt").let {
            if (it.isFile) it.readText()
            else java.io.File("src/main/java/io/github/m0use001/planability/widget/TodayStatsWidget.kt").readText()
        }
        assertTrue("不应再画整卡托盘路径（Squircle.path 满卡）", !src.contains("Squircle.path("))
        assertTrue("不应再有托盘半轴变量", !src.contains("trayA"))
    }

    @Test
    fun `胶囊 2x2 等分恰好填满卡面`() {
        for ((w, h) in listOf(1064f to 484f, 700f to 300f, 437f to 654f)) {
            val g = geo(w, h, 3.5f)
            val mx = w * 0.035f
            val gx = w * 0.028f
            val my = h * 0.075f
            val gy = h * 0.075f
            // 两列：左内距 + 两胶囊 + 中缝 + 右内距 == 卡宽（浮点容差 0.5px）
            assertEquals(w, mx * 2f + g.capW * 2f + gx, 0.5f)
            assertEquals(h, my * 2f + g.capH * 2f + gy, 0.5f)
            assertTrue("胶囊必须有正尺寸", g.capW > 0f && g.capH > 0f)
        }
    }

    @Test
    fun `环径双约束与环心百分比可画`() {
        val g = geo(w = 1064f, h = 484f, density = 3.5f)
        assertTrue("环径不得超出胶囊高（含内距）", g.ringD <= g.capH - g.capH * 0.09f * 2f + 0.5f)
        assertTrue("环径不得超出胶囊宽", g.ringD <= g.capW * 0.46f + 0.5f)
        assertTrue("环应占胶囊高 ≥80%（2026-11-03 用户「环大一点」）", g.ringD >= g.capH * 0.78f)
        assertEquals("描边 = 13% 环径（下限 2px 见实现）", g.ringD * 0.13f, g.stroke, 0.5f)
        // 与实现同式（chord 判定 + 按弦长自动缩字号）：「100%」四字符在 Google Sans Flex
        // 下 ≈ 2.3×字号；实现会缩到弦长放得下为止，下限 = 环径 16%（再小也读不清）
        val innerR = g.ringD / 2f - g.stroke / 2f
        val floorInk = g.ringD * 0.16f
        val pctW = floorInk * 2.3f
        val pctH = floorInk * 1.2f
        val maxW = 2f * kotlin.math.sqrt(
            (innerR * innerR - (pctH / 2f) * (pctH / 2f)).coerceAtLeast(0f)
        )
        assertTrue("下限字号下 pctW=$pctW 必须不超过内孔弦长 $maxW", pctW <= maxW)
    }

    @Test
    fun `极端尺寸下几何不退化`() {
        for ((w, h, d) in listOf(
            Triple(946f, 942f, 2.625f),  // 模拟器 4×4 兜底
            Triple(700f, 300f, 2f),      // 极端扁条
            Triple(300f, 700f, 2f),      // 极端竖条
        )) {
            val g = geo(w, h, d)
            assertTrue("环径必须为正", g.ringD > 0f)
            assertTrue("环径不低于胶囊高 34%（窄卡时不把环缩没）",
                g.ringD >= g.capH * 0.34f - 0.5f)
            assertTrue("环 + 描边必须小于胶囊高", g.ringD + g.stroke < g.capH + 0.5f)
        }
    }
}
