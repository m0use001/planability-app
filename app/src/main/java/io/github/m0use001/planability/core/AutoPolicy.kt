package io.github.m0use001.planability.core

import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * 常驻自主循环的纯策略（v43：用户定向「杀后台仍常驻的自主循环」）。
 *
 * 全部是纯函数，单测直接覆盖——因为这套逻辑的失败模式是"静默"的：
 * 任务没跑、跑一半被杀、重试风暴，用户都看不到，只能靠代码正确性保证。
 */
object AutoPolicy {

    /** 心跳超时：running 超过这个时间没更新 = 进程在执行中死了，下次 tick 回收重试 */
    const val STALE_MS = 10 * 60_000L

    /** 最大尝试次数；超过转 failed（不无限重试，避免耗电） */
    const val MAX_ATTEMPTS = 3

    /** 失败退避：5 分钟 → 15 分钟 → 1 小时 */
    fun retryDelayMs(attempts: Int): Long = when {
        attempts <= 1 -> 5 * 60_000L
        attempts == 2 -> 15 * 60_000L
        else -> 60 * 60_000L
    }

    fun shouldFail(attempts: Int): Boolean = attempts >= MAX_ATTEMPTS

    /** 陈旧 running 判定（进程被杀/被系统冻结后的回收依据） */
    fun isStale(status: String, updatedAt: Long, now: Long): Boolean =
        status == "running" && now - updatedAt > STALE_MS

    /**
     * 下一个每日时刻（本地时区）。用于内置的 daily_maintenance 排期。
     * 纯函数（显式传入时区与当前时刻），单测固定值断言。
     */
    fun nextDailyAt(now: Instant, zone: ZoneId, hour: Int, minute: Int): Long {
        val local = now.atZone(zone)
        var next = local.withHour(hour).withMinute(minute).withSecond(0).withNano(0)
        if (!next.isAfter(local)) next = next.plusDays(1)
        return next.toInstant().toEpochMilli()
    }

    /** 下一次 recurring 的时间 */
    fun nextRunFor(recurring: Boolean, intervalMin: Int, now: Long): Long =
        if (!recurring) 0L else now + intervalMin.coerceAtLeast(60) * 60_000L
}
