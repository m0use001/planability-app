package io.github.m0use001.planability.ai.ptl

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 课表精确查询闸（2026-11-12 用户：「助理中枢那个会话，围绕课程表读取又出了问题，
 * 而且 AI 说没有直接查日程待办的精确工具」）。
 *
 * ◆ 实录（消息 6519-6544）：用户问「我平时的课程设置」，模型拿
 * `data_history(schedule)`（按**主记录**计数：16 条主记录=一门每周重复的课只算 1 条）
 * 答课表 → 断言「没有一条课程是按每周重复建的」；被用户纠正后才试出 `timeline_query`
 * 逐日展开是对的，又连打 6 轮工具一天一天铺。三处根修：
 * ① timeline_query 加 days 参数（一次铺一段，上限 31）；
 * ② 三个工具的 whenToUse 互相指路（timeline_query=精确查，data_history 只数主记录）；
 * ③ ContextEngine 硬规则钉死「课表/某天安排必须用 timeline_query」。
 */
class HubTimetableQueryTest {

    private fun source(rel: String): String {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null && !File(dir, "app/src/main/java/io/github/m0use001/planability").isDirectory) {
            dir = dir.parentFile
        }
        val f = File(requireNotNull(dir) { "找不到仓库根" }, rel)
        assertTrue("源码不在了：$rel", f.isFile)
        return f.readText(Charsets.UTF_8).replace("\r\n", "\n")
    }

    @Test
    fun `timeline_query 有 days 区间参数，实现封顶 31 天`() {
        val defs = source("shared/src/jvmShared/kotlin/io/github/m0use001/planability/shared/ToolDefs.kt")
        val def = defs.substringAfter("name = \"timeline_query\"").take(1200)
        assertTrue("timeline_query 没加 days 参数", def.contains("ToolParam(\"days\""))
        val impl = source("app/src/main/java/io/github/m0use001/planability/ai/ptl/PlanTools.kt")
            .substringAfter("private suspend fun timelineQuery(")
        assertTrue("实现没读 days 参数", impl.contains("optInt(\"days\""))
        assertTrue("days 没封顶（防一次刷几千行）", impl.contains("coerceAtMost(31)"))
    }

    @Test
    fun `三个工具 whenToUse 互相指路，不许再拿 data_history 答课表`() {
        val defs = source("shared/src/jvmShared/kotlin/io/github/m0use001/planability/shared/ToolDefs.kt")
        fun block(name: String): String = defs.substring(defs.indexOf("name = \"$name\"")).substringBefore("ToolDef(")
        val timeline = block("timeline_query")
        val history = block("data_history")
        val schedQ = block("schedule_query")
        assertTrue("timeline_query 没写课表场景", timeline.contains("课表"))
        assertTrue("timeline_query 没警告 data_history 的口径", timeline.contains("主记录"))
        assertTrue("data_history 没写主记录计数口径", history.contains("主记录"))
        assertTrue("schedule_query 没指路 timeline_query", schedQ.contains("timeline_query"))
    }

    @Test
    fun `ContextEngine 硬规则钉死课表用 timeline_query`() {
        val ctx = source("app/src/main/java/io/github/m0use001/planability/ai/ContextEngine.kt")
        val idx = ctx.indexOf("查「某天/某周/课表/课程安排」")
        assertTrue("硬规则没加", idx >= 0)
        val rule = ctx.substring(idx, idx + 400)
        assertTrue("规则没提 days 铺一周", rule.contains("days"))
        assertTrue("规则没禁 data_history 答课表", rule.contains("不许用它回答课表"))
    }
}
