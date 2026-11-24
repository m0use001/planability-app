package io.github.m0use001.planability.ai.ptl

import io.github.m0use001.planability.core.AppTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * 2026-11-14 真机会话实录：`pending_update {"time":"11月19日 16:00"}` 被拒
 * （「时间看不懂」），而错误提示自己拿「11月13日 15:00」当合法例子——提示撒谎，
 * 「X月X日/号 [HH:MM]」这一档在 parseDateTime 里根本不存在。修复后钉住。
 */
class ParseDateTimeCjkDateTest {

    private fun parts(millis: Long): Triple<Int, Int, Int> {
        val d = Instant.ofEpochMilli(millis).atZone(AppTime.zone).toLocalDateTime()
        return Triple(d.monthValue, d.dayOfMonth, d.hour)
    }

    @Test
    fun `X月X日 加时刻能解析`() {
        val t = ToolExecImpls.parseDateTime("11月19日 16:00")
        assertNotNull("11月19日 16:00 必须能解析（某次会话用户实录被拒）", t)
        val (month, day, hour) = parts(t!!)
        assertEquals(11, month)
        assertEquals(19, day)
        assertEquals(16, hour)
    }

    @Test
    fun `X月X号 变体与无时刻档`() {
        val noTime = ToolExecImpls.parseDateTime("11月19号")
        assertNotNull(noTime)
        val (m, d, _) = parts(noTime!!)
        assertEquals(11, m)
        assertEquals(19, d)
        // 无时刻=当天日键（0 点附近）
        val dayMillis = AppTime.dayKey(Instant.ofEpochMilli(noTime).atZone(AppTime.zone).toLocalDate())
        assertEquals(dayMillis, noTime)
    }

    @Test
    fun `非法日期显式失败`() {
        assertTrue(ToolExecImpls.parseDateTime("13月40日 16:00") == null)
    }

    @Test
    fun `旧格式不回归`() {
        assertNotNull(ToolExecImpls.parseDateTime("16:00"))
        assertNotNull(ToolExecImpls.parseDateTime("周四 16:00"))
        assertNotNull(ToolExecImpls.parseDateTime("2026-11-19 16:00"))
        assertNotNull(ToolExecImpls.parseDateTime("11-19 16:00"))
        assertNotNull(ToolExecImpls.parseDateTime("明天 16:00"))
    }

    /** plan_category_create 必须三处登记（def / 域清单 / 执行器路由）——工具六处登记纪律 */
    @Test
    fun `plan_category_create 三处登记`() {
        fun src(rel: String) = java.io.File(rel).readText(Charsets.UTF_8)
        val defs = src("src/main/java/io/github/m0use001/planability/ai/ptl/ToolDefs.kt")
        assertTrue("ToolDefs 缺 def", defs.contains("\"plan_category_create\""))
        assertTrue("域清单没登记（模型看不到工具）", defs.contains("plan_category_list\", \"plan_category_create"))
        val exec = src("src/main/java/io/github/m0use001/planability/ai/ptl/ToolExecImpls.kt")
        assertTrue("执行器路由没挂（会回「未知工具」——1316 会话的老坑）",
            exec.contains("\"plan_category_list\", \"plan_category_create\""))
        val plan = src("src/main/java/io/github/m0use001/planability/ai/ptl/PlanTools.kt")
        assertTrue("PlanTools 缺处理器", plan.contains("\"plan_category_create\" -> categoryCreate(a)"))
    }
}
