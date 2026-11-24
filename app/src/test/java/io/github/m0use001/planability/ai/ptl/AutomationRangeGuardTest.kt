package io.github.m0use001.planability.ai.ptl

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * P1-5 守卫：automation 定时越界 → **永不触发的空壳**（2026-11-09 审计静态实锤）。
 *
 * 事故形状：`automation_create {"dailyHour":25}`（或用户说「每天 24:00」被模型转成 24）
 * 落库后 `hasTrigger` 判定 dailyHour>=0 通过 → 存下一条永远不会响的任务，没有任何报错。
 * 用户以为配好了，到点什么都不发生——这类「存下来但不跑」的静默空壳比报错难查得多。
 *
 * 期望：hour 只收 -1（未设）或 0..23；minute 只收 -1 或 0..59；interval 不收负数。
 */
class AutomationRangeGuardTest {

    private fun source(rel: String): String {
        val f = File(rel)
        assertTrue("源码不在了（cwd 应为 app 模块目录）：$rel", f.isFile)
        return f.readText(Charsets.UTF_8)
    }

    @Test
    fun `automation_create 校验 hour 与 minute 范围`() {
        val exec = source("src/main/java/io/github/m0use001/planability/ai/ptl/ToolExecImpls.kt")
        val seg = exec.substringAfter("\"automation_create\" -> {").substringBefore("\"automation_edit\" -> {")
        assertTrue(
            "automation_create 必须对 dailyHour/dailyMinute 做 0-23 / 0-59 校验（越界=永不触发的空壳）",
            seg.contains("dHour !in 0..23") && seg.contains("dMinute !in 0..59"),
        )
    }

    @Test
    fun `automation_edit 同样校验`() {
        val exec = source("src/main/java/io/github/m0use001/planability/ai/ptl/ToolExecImpls.kt")
        val seg = exec.substringAfter("\"automation_edit\" -> {").substringBefore("\"automation_delete\" -> {")
        assertTrue(
            "automation_edit 的 interval/hour/minute 也必须做范围校验（编辑路径同样能造空壳）",
            seg.contains("0..23") && seg.contains("0..59") && seg.contains("intervalMinutes"),
        )
    }
}
