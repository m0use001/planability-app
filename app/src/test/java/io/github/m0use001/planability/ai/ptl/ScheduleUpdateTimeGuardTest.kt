package io.github.m0use001.planability.ai.ptl

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * P1-6 守卫：schedule_update 静默吞掉不可解析的 start/end（2026-11-09 审计静态实锤）。
 *
 * 事故形状：`schedule_update {"id":3,"start":"下周三下午"}` —— parseDateTime 返回 null 被
 * `?.let{}` 吞掉，日程一点没动，工具却回「已修改日程」。用户/模型都以为改了。
 * 同文件里 todo_edit 对 date 是显式 fail 的——两条路径口径必须一致。
 */
class ScheduleUpdateTimeGuardTest {

    private fun source(rel: String): String {
        val f = File(rel)
        assertTrue("源码不在了（cwd 应为 app 模块目录）：$rel", f.isFile)
        return f.readText(Charsets.UTF_8)
    }

    @Test
    fun `schedule_update 的 start 解析失败必须显式失败`() {
        val plan = source("src/main/java/io/github/m0use001/planability/ai/ptl/PlanTools.kt")
        val seg = plan.substringAfter("private suspend fun scheduleUpdate(").substringBefore("private suspend fun scheduleSkipDay(")
        assertTrue(
            "start 解析不出来时必须 return ToolExecResult.fail（不能 ?.let 吞掉后回「已修改」）",
            seg.contains("开始时间看不懂") || seg.contains("开始时间无法解析"),
        )
        assertTrue(
            "解析失败的 fail 必须真的 return（startsWith「return ToolExecResult.fail」形状）",
            seg.contains("return ToolExecResult.fail(\"开始时间"),
        )
    }

    @Test
    fun `schedule_update 的 end 解析失败必须显式失败`() {
        val plan = source("src/main/java/io/github/m0use001/planability/ai/ptl/PlanTools.kt")
        val seg = plan.substringAfter("private suspend fun scheduleUpdate(").substringBefore("private suspend fun scheduleSkipDay(")
        assertTrue(
            "end 解析不出来时必须 return fail（与 todo_edit 对 date 的口径一致）",
            seg.contains("return ToolExecResult.fail(\"结束时间"),
        )
    }
}
