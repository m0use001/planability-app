package io.github.m0use001.planability.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

/**
 * 常驻自主循环的纯策略（v43）。
 *
 * 这里的失败都是静默的（任务没跑/重试风暴/每日任务排到昨天），所以用固定值钉住。
 */
class AutoPolicyTest {

    private val zone = ZoneId.of("Asia/Shanghai")

    @Test
    fun retryBackoffGrows() {
        assertEquals(5 * 60_000L, AutoPolicy.retryDelayMs(1))
        assertEquals(15 * 60_000L, AutoPolicy.retryDelayMs(2))
        assertEquals(60 * 60_000L, AutoPolicy.retryDelayMs(3))
    }

    @Test
    fun failsAfterMaxAttempts() {
        assertFalse(AutoPolicy.shouldFail(1))
        assertFalse(AutoPolicy.shouldFail(2))
        assertTrue(AutoPolicy.shouldFail(AutoPolicy.MAX_ATTEMPTS))
    }

    @Test
    fun staleOnlyForRunningBeyondThreshold() {
        val now = 10_000_000L
        // running 且心跳超时 → 可回收
        assertTrue(AutoPolicy.isStale("running", now - AutoPolicy.STALE_MS - 1, now))
        // running 但心跳还新 → 不动
        assertFalse(AutoPolicy.isStale("running", now - 1000, now))
        // 非 running 状态永远不算陈旧
        assertFalse(AutoPolicy.isStale("queued", now - AutoPolicy.STALE_MS - 1, now))
    }

    @Test
    fun nextDailyAtIsTodayOrTomorrow() {
        // 2026-10-22 01:00 上海：03:10 还没到 → 今天
        val early = Instant.parse("2026-10-21T17:00:00Z") // = 10-22 01:00 +08
        val today = Instant.ofEpochMilli(AutoPolicy.nextDailyAt(early, zone, 3, 10))
            .atZone(zone)
        assertEquals(22, today.dayOfMonth)
        assertEquals(3, today.hour)
        assertEquals(10, today.minute)

        // 2026-10-22 04:00 上海：今天 03:10 已过 → 明天
        val late = Instant.parse("2026-10-21T20:00:00Z") // = 10-22 04:00 +08
        val next = Instant.ofEpochMilli(AutoPolicy.nextDailyAt(late, zone, 3, 10))
            .atZone(zone)
        assertEquals(23, next.dayOfMonth)
        assertEquals(3, next.hour)
    }

    @Test
    fun recurringNextRunHonoursMinimumInterval() {
        val now = 1_000L
        assertEquals(0L, AutoPolicy.nextRunFor(false, 1440, now))
        // 小于 60 分钟按 60 分钟算（防止 AI 排出一个 1 分钟的高频任务耗电）
        assertEquals(now + 60 * 60_000L, AutoPolicy.nextRunFor(true, 1, now))
        assertEquals(now + 24 * 60 * 60_000L, AutoPolicy.nextRunFor(true, 1440, now))
    }
}
