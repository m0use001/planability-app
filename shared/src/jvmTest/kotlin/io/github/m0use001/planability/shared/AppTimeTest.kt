package io.github.m0use001.planability.shared

import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * AppTime 行为对照（M2 迁移时补：迁移前 app 侧无直接单测）。
 * 钉住三条核心语义：zoneOverride 生效 / 覆盖变更后缓存失效 / dayKey 往返一致。
 */
class AppTimeTest {

    @AfterTest
    fun resetZone() {
        AppTime.zoneOverride = null
    }

    @Test
    fun `zoneOverride 生效且缓存失效`() {
        AppTime.zoneOverride = null
        val systemZone = ZoneId.systemDefault()
        assertEquals(systemZone, AppTime.zone)

        AppTime.zoneOverride = "America/New_York"
        assertEquals(ZoneId.of("America/New_York"), AppTime.zone, "覆盖后 zone 立即切换（缓存已失效）")

        AppTime.zoneOverride = "Asia/Shanghai"
        assertEquals(ZoneId.of("Asia/Shanghai"), AppTime.zone, "二次覆盖同样立即生效")

        AppTime.zoneOverride = "Not/AZone"
        assertEquals(ZoneId.systemDefault(), AppTime.zone, "非法覆盖回落系统时区")
    }

    @Test
    fun `dayKey 往返一致`() {
        AppTime.zoneOverride = "Asia/Shanghai"
        val d = LocalDate.of(2026, 11, 25)
        val key = AppTime.dayKey(d)
        assertEquals(key, AppTime.dayKeyOf(key), "dayKeyOf(dayKey) 恒等")
        assertEquals(d, key.toLocalDate(), "key 经顶层 Long.toLocalDate 还原为同一天")
        assertEquals(key, d.toDayKey(), "LocalDate.toDayKey 与 dayKey 同口径")
    }

    @Test
    fun `millisOf 与 millisToTime 同区往返`() {
        AppTime.zoneOverride = "Asia/Shanghai"
        val d = LocalDate.of(2026, 11, 25)
        val millis = AppTime.millisOf(d, 14, 30)
        assertEquals("14:30", AppTime.millisToTime(millis))
        assertTrue(AppTime.millisToFull(millis).contains("2026年11月25日"))
    }
}
