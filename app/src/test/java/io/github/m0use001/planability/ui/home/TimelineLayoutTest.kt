package io.github.m0use001.planability.ui.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v59：时间轴纵向压缩的**纯函数**单测（2026-11-17）。
 *
 * 为什么要有这份测试：时间轴的「点在哪儿」现在由一份映射（[TimelineLayout]）决定，
 * 而它同时被四件事用着——网格线、小时标签、事件块、现在线，外加首页的滚动定位。
 * 这正是本仓库吃过大亏的「同一事实多处定义」族（完成率 200%、时间轴和首页对不上）。
 * 所以：**映射只许有一份，且必须被钉死**。
 *
 * 纪律同 SingleSourceTest：每条断言都要能真的红。
 */
class TimelineLayoutTest {

    /** 全空的一天：24 个空闲小时各 22dp。 */
    @Test
    fun `全空的一天压成 24 个 22dp 的小时`() {
        val layout = TimelineLayout.build(contentHours = emptySet())
        assertEquals(24 * TimelineLayout.IDLE_HOUR_HEIGHT_DP, layout.totalHeightDp.toInt())
        assertEquals(0f, layout.minuteToDp(0), 0.01f)
        assertEquals(layout.totalHeightDp, layout.minuteToDp(1440), 0.01f)
    }

    /** 有事的小时保持原高度（64dp），空闲的小时压扁。 */
    @Test
    fun `有事的小时保持 64dp 空闲压成 22dp`() {
        val layout = TimelineLayout.build(contentHours = setOf(11))
        assertEquals(64, layout.hourHeightsDp[11])
        assertEquals(TimelineLayout.IDLE_HOUR_HEIGHT_DP, layout.hourHeightsDp[10])
        assertEquals(TimelineLayout.IDLE_HOUR_HEIGHT_DP, layout.hourHeightsDp[12])
        // 0–10 空闲（11×22=242）+ 11 有事（64）+ 12–23 空闲（12×22=264）= 570
        assertEquals(570f, layout.totalHeightDp, 0.01f)
    }

    /**
     * 位置仍按**真实分钟**换算：同一个小时内部是线性的，
     * 所以「11:30 在 11 点这一小时的正中间」这件事不能被压缩规则破坏。
     */
    @Test
    fun `小时内按分钟线性换算 位置不因压缩而失真`() {
        val layout = TimelineLayout.build(contentHours = setOf(11))
        val top11 = layout.hourTopDp(11)
        assertEquals(242f, top11, 0.01f)
        assertEquals(top11 + 32f, layout.minuteToDp(11 * 60 + 30), 0.01f)
        assertEquals(top11 + 64f, layout.minuteToDp(12 * 60), 0.01f)
    }

    /** 事件块跨整点：高度 = 两端映射之差（不按 64dp 粗算）。 */
    @Test
    fun `跨整点的事件块高度按映射差计算`() {
        val layout = TimelineLayout.build(contentHours = setOf(10, 11, 12))
        val h = layout.minuteToDp(12 * 60) - layout.minuteToDp(10 * 60)
        // 10、11、12 三小时都有事 → 每小时 64dp → 10:00–12:00 = 128dp
        assertEquals(128f, h, 0.01f)
    }

    /** 有事小时判定：isContentHour 必须与高度一致（渲染层靠它决定字号与线宽）。 */
    @Test
    fun `isContentHour 与小时高度一致`() {
        val layout = TimelineLayout.build(contentHours = setOf(9))
        assertTrue(layout.isContentHour(9))
        assertTrue(!layout.isContentHour(8))
        assertTrue(!layout.isContentHour(10))
    }

    /** contentHoursOf：跨整点的事件把中间小时也算成「有事」。 */
    @Test
    fun `跨整点事件占用的小时都被算成有事`() {
        val blocks = listOf(
            TimelineBlock(kind = "schedule", refId = 1, title = "课", startMin = 10 * 60 + 30, endMin = 12 * 60 + 15, color = androidx.compose.ui.graphics.Color.Red)
        )
        val hours = contentHoursOf(blocks)
        assertTrue(hours.contains(10))
        assertTrue(hours.contains(11))
        assertTrue(hours.contains(12))
        assertEquals(3, hours.size)
    }

    /** 分钟越界不炸：startMin 可能被 clamp 到 0，endMin 可能到 1440。 */
    @Test
    fun `分钟越界被夹住不崩`() {
        val layout = TimelineLayout.build(contentHours = setOf(0))
        assertEquals(0f, layout.minuteToDp(-30), 0.01f)
        assertEquals(layout.totalHeightDp, layout.minuteToDp(9999), 0.01f)
        assertEquals(0f, layout.hourTopDp(-5), 0.01f)
    }

    /**
     * v59：压缩后如果比可视高度矮，空闲小时**回弹**撑满——
     * 否则「空」只是从时间轴内部挪到了页面底部（下半屏一片死白）。
     * 回弹有上限（自然高度 64dp），内容多的时候不触发（总高本来就超过视口）。
     */
    @Test
    fun `比可视高度矮时空闲小时回弹撑满 但不超过自然高度`() {
        val base = TimelineLayout.build(contentHours = setOf(11))
        val filled = TimelineLayout.build(contentHours = setOf(11), fillHeightDp = 700f)
        assertTrue("回弹后应该更高", filled.totalHeightDp > base.totalHeightDp)
        assertTrue("回弹后不应该超过视口太多", filled.totalHeightDp <= 700f + 24f)
        // 有事的小时**不参与回弹**（事件块高度必须稳定）
        assertEquals(64, filled.hourHeightsDp[11])
        // 空闲的小时在 [22, 64] 之间
        for (h in 0 until 24) {
            if (h == 11) continue
            assertTrue("空闲小时高度越界：$h -> ${filled.hourHeightsDp[h]}", filled.hourHeightsDp[h] in 22..64)
        }
    }

    /** 内容比视口高时不回弹（滚动行为不变）。 */
    @Test
    fun `比可视高度高时不回弹`() {
        val layout = TimelineLayout.build(contentHours = (0..23).toSet(), fillHeightDp = 700f)
        assertEquals(24 * HOUR_HEIGHT_DP.toFloat(), layout.totalHeightDp, 0.01f)
    }
}
