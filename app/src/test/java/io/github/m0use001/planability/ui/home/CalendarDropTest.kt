package io.github.m0use001.planability.ui.home

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * v73 日历 3 天/周视图拖块改时间：[computeDropMillis] 纯函数几何钉。
 * 用户拍板：不改时长（只平移）、15 分钟吸附、支持跨天、越界钳在当天内。
 */
class CalendarDropTest {

    private val zone = ZoneId.of("Asia/Shanghai")

    /** 固定基准：2026-11-21 10:00 → 11:00（60 分钟时长，40dp/小时口径） */
    private fun base(): Pair<Long, Long> {
        val s = LocalDateTime.of(2026, 11, 21, 10, 0).atZone(zone).toInstant().toEpochMilli()
        return s to s + 60L * 60000
    }

    private fun LocalDateTime.ms(): Long = atZone(zone).toInstant().toEpochMilli()

    @Test
    fun `同列下移40dp等于下移1小时 时长不变`() {
        val (s, e) = base()
        val (ns, ne) = computeDropMillis(s, e, 0f, 40f, 140f, 40f, zone)
        assertEquals(LocalDateTime.of(2026, 11, 21, 11, 0).ms(), ns)
        assertEquals(LocalDateTime.of(2026, 11, 21, 12, 0).ms(), ne)
        assertEquals(e - s, ne - ns) // 时长铁律
    }

    @Test
    fun `15分钟吸附_33dp约49分钟吸附到45分`() {
        val (s, e) = base()
        // 33dp = 49.5 分钟 → /15 四舍五入 = 3 格 = 45 分钟
        val (ns, _) = computeDropMillis(s, e, 0f, 33f, 140f, 40f, zone)
        assertEquals(LocalDateTime.of(2026, 11, 21, 10, 45).ms(), ns)
    }

    @Test
    fun `跨天右移一列 日期加一天 时间不变`() {
        val (s, e) = base()
        val (ns, ne) = computeDropMillis(s, e, 140f, 0f, 140f, 40f, zone)
        assertEquals(LocalDateTime.of(2026, 11, 22, 10, 0).ms(), ns)
        assertEquals(LocalDateTime.of(2026, 11, 22, 11, 0).ms(), ne)
    }

    @Test
    fun `上移越界钳在当天0点 时长仍不变`() {
        val (s, e) = base()
        // 10:00 上移 -20 小时（-800dp）→ 钳到 0:00，end=1:00
        val (ns, ne) = computeDropMillis(s, e, 0f, -800f, 140f, 40f, zone)
        assertEquals(LocalDateTime.of(2026, 11, 21, 0, 0).ms(), ns)
        assertEquals(LocalDateTime.of(2026, 11, 21, 1, 0).ms(), ne)
    }

    @Test
    fun `下移越界钳在当天结束前 时长仍不变`() {
        val (s, e) = base()
        // 10:00 下移 +20 小时 → start 钳到 23:00（end=24:00），不跨到次日
        val (ns, ne) = computeDropMillis(s, e, 0f, 800f, 140f, 40f, zone)
        assertEquals(LocalDateTime.of(2026, 11, 21, 23, 0).ms(), ns)
        assertEquals(LocalDateTime.of(2026, 11, 22, 0, 0).ms(), ne)
    }
}
