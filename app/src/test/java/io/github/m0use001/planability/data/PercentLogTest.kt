package io.github.m0use001.planability.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

/**
 * v48「待办百分比进度流水」的纯函数防回归钉：
 * - PercentLogFormat 的日期/百分比渲染（UI 与 AI 共用同一份措辞）；
 * - 列表按新→旧拼接。
 */
class PercentLogTest {

    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")

    @Test
    fun `单行格式_日期与百分比`() {
        // 2026-11-03 00:00 +08
        val day = java.time.Instant.parse("2026-11-02T16:00:00Z").toEpochMilli()
        assertEquals("11/3 · 40%", PercentLogFormat.line(day, 40, zone))
    }

    @Test
    fun `日期为0时不带前缀`() {
        assertEquals("55%", PercentLogFormat.line(0, 55, zone))
    }

    @Test
    fun `recent_新到旧拼接且截断`() {
        val d1 = java.time.Instant.parse("2026-10-30T16:00:00Z").toEpochMilli() // 10/31
        val d2 = java.time.Instant.parse("2026-10-31T16:00:00Z").toEpochMilli() // 11/1
        val d3 = java.time.Instant.parse("2026-11-01T16:00:00Z").toEpochMilli() // 11/2
        val logs = listOf(
            TodoPercentLog(todoId = 1, date = d3, percent = 80),
            TodoPercentLog(todoId = 1, date = d2, percent = 60),
            TodoPercentLog(todoId = 1, date = d1, percent = 40)
        )
        assertEquals("11/2 · 80% → 11/1 · 60% → 10/31 · 40%", PercentLogFormat.recent(logs, zone))
        // limit 生效
        assertEquals("11/2 · 80% → 11/1 · 60%", PercentLogFormat.recent(logs, zone, limit = 2))
        // 空列表
        assertTrue(PercentLogFormat.recent(emptyList(), zone).isEmpty())
    }
}
