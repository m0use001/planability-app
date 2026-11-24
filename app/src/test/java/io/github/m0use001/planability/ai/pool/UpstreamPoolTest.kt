package io.github.m0use001.planability.ai.pool

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 多账号上游池的算法钉子（移植自 Routability 网关 `route.py` 的 Upstream/router）。
 *
 * 这些规则在网关里是靠真机 429 反复调出来的，端内重写必须逐条对齐——
 * 错一条的表现都是"免费额度被刷进惩罚态、一挂一整天"，比功能缺失更难查。
 */
class UpstreamPoolTest {

    private fun up(
        id: String,
        priority: Int = 100,
        startRpm: Double = 6.0,
        maxRpm: Double = 12.0,
        dailyLimit: Int = 0
    ) = UpstreamState(
        id = id, name = id, priority = priority,
        startRpm = startRpm, rpmMin = 2.0, rpmMax = maxRpm,
        cooldownBaseSec = 60.0, cooldownMaxSec = 3600.0, dailyLimit = dailyLimit
    )

    @Test
    fun `连续 429 时冷却指数增长且 RPM 减半`() {
        val u = up("a", startRpm = 8.0)
        val t0 = 1000.0
        u.on429(t0)
        assertEquals("RPM 应减半", 4.0, u.rpm, 0.001)
        assertEquals("首次冷却 = base", 60.0, u.cooldownRemaining(t0), 0.001)

        val t1 = t0 + 61
        u.on429(t1)
        assertEquals("再减半", 2.0, u.rpm, 0.001)
        assertEquals("第二次冷却翻倍 = 120s", 120.0, u.cooldownRemaining(t1), 0.001)

        val t2 = t1 + 121
        u.on429(t2)
        assertEquals("RPM 触底不再降", 2.0, u.rpm, 0.001)
        assertEquals("第三次 240s", 240.0, u.cooldownRemaining(t2), 0.001)
    }

    @Test
    fun `单次 5xx 只降速不冷却_连击两次才冷却`() {
        val u = up("a", startRpm = 10.0)
        val t0 = 1000.0
        u.on5xx(t0, 500)
        assertEquals("RPM ×0.7", 7.0, u.rpm, 0.001)
        assertEquals("单次不冷却", 0.0, u.cooldownRemaining(t0), 0.001)
        assertTrue("但要软降级", u.isDemoted(t0))

        u.on5xx(t0 + 1, 502)
        assertTrue("连击 2 次进冷却", u.cooldownRemaining(t0 + 1) > 0)
    }

    @Test
    fun `成功会清空连续计数并缓慢提速`() {
        val u = up("a", startRpm = 5.0, maxRpm = 12.0)
        val t = 1000.0
        u.on429(t)
        assertEquals(2.5, u.rpm, 0.001)
        // 冷却期内不会被选
        assertTrue(u.cooldownRemaining(t) > 0)
        // 连续 20 次成功后提速 1.1 倍（每分钟最多一次）
        val later = t + 61
        repeat(20) { u.onSuccess(later) }
        assertEquals("2.5 × 1.1", 2.75, u.rpm, 0.001)
    }

    @Test
    fun `滑动窗口限制每分钟请求数`() {
        val u = up("a", startRpm = 3.0)
        val t = 1000.0
        // 连发 3 次（RPM=3 的容量）
        repeat(3) { i -> u.markSent(t + i * 21.0) }
        // 第 4 次必须等到第一个滑出 60s 窗
        val wait = u.waitSec(t + 50)
        assertTrue("窗口满时应等待（实际 $wait）", wait > 0)
        // 62 秒后第一个已滑出，可以发
        assertEquals(0.0, u.waitSec(t + 62.0), 0.5)
    }

    @Test
    fun `按优先级选上游_同优先级用最久未用`() {
        val a = up("nvidia", priority = 10)
        val b = up("amd", priority = 50)
        val pool = UpstreamPool("mimo", listOf(b, a))
        assertEquals("优先级小的先选", "nvidia", pool.pick(1000.0)?.id)

        // a 刚用过 → 同为最低优先级时轮到更久未用的
        a.markSent(1000.0)
        val c1 = up("x", priority = 10)
        val c2 = up("y", priority = 10)
        c1.markSent(1000.0)
        val pool2 = UpstreamPool("m", listOf(c1, c2))
        assertEquals("最久未用的优先（y 从没用过）", "y", pool2.pick(1001.0)?.id)
    }

    @Test
    fun `冷却中的上游让位给健康候选`() {
        val a = up("a", priority = 10)
        val b = up("b", priority = 50)
        a.on429(1000.0)   // a 进冷却
        val pool = UpstreamPool("m", listOf(a, b))
        assertEquals("a 冷却 → 选 b", "b", pool.pick(1000.0)?.id)
    }

    @Test
    fun `全部冷却时返回最短等待的上游`() {
        val a = up("a", priority = 10)
        val b = up("b", priority = 50)
        a.on429(1000.0)   // 60s
        b.on429(1000.0)
        val pool = UpstreamPool("m", listOf(a, b))
        assertNotNull("全冷却也要返回一个（调用方等它）", pool.pick(1000.0))
        assertTrue(pool.allBlocked(1000.0))
        assertTrue("最短等待应 > 0", pool.shortestWait(1000.0) > 0)
    }

    @Test
    fun `试过的上游不再被选`() {
        val a = up("a")
        val b = up("b")
        val pool = UpstreamPool("m", listOf(a, b))
        assertEquals("a", pool.pick(1000.0, setOf())?.id)
        assertEquals("跳过试过的 a", "b", pool.pick(1000.0, setOf("a"))?.id)
        assertNull("全试过 → null", pool.pick(1000.0, setOf("a", "b")))
    }

    @Test
    fun `每日额度用尽要等到明天`() {
        val u = up("a", dailyLimit = 2)
        val t = 1000.0
        u.markSent(t)
        u.markSent(t + 1)
        val cd = u.cooldownRemaining(t + 2)
        assertTrue("额度用尽应冷却到次日（实际 ${cd}s）", cd > 0)
    }
}
