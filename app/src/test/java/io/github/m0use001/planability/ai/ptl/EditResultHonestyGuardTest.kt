package io.github.m0use001.planability.ai.ptl

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * P0-2 守卫：模型正文撒谎——声称改了 A、实际改了 B（2026-11-09 审计动态实录）。
 *
 * 事故：`todo_edit {"id":2,"date":"明天"}` 实际改的是「审计-重要紧急」（编号 2），
 * 但模型正文写「✅ 待办日期已更新 · 待办标题：背诵单词」——真标题只藏在工具行尾部
 * 小字 display 里。用户只看正文就被骗。
 *
 * 两道闸：
 * ① 工具 ok 文案（payload，模型逐字看到的那份）必须带「旧标题 → 新标题」，模型想撒谎
 *    就得先无视眼前的目标名；
 * ② 系统提示要求模型作答时复述被改对象的标题。
 * 另：改到带重复规则的待办而没动 repeat 时，文案必须警告（模型把「新建」误做成「改」
 * 时，重复规则会被静默继承——审计场景②就是这条）。
 */
class EditResultHonestyGuardTest {

    private fun source(rel: String): String {
        val f = File(rel)
        assertTrue("源码不在了（cwd 应为 app 模块目录）：$rel", f.isFile)
        return f.readText(Charsets.UTF_8)
    }

    @Test
    fun `todo_edit 的 ok 文案必须带旧标题到新标题`() {
        val plan = source("src/main/java/io/github/m0use001/planability/ai/ptl/PlanTools.kt")
        val seg = plan.substringAfter("private suspend fun todoEdit(").substringBefore("private suspend fun todoDelete(")
        // 只认 payload 那一行：必须出现「（旧标题 → 新标题）」形状，注释里的箭头不算
        assertTrue(
            "todoEdit 的 ok payload 必须写「旧标题 → 新标题」——现在只有新标题，模型正文可以随便写改的是谁",
            Regex("old\\.title\\s*\\+\\s*\" → \"\\s*\\+\\s*nt\\.title").containsMatchIn(seg),
        )
    }

    @Test
    fun `改到带重复的待办且未动 repeat 时必须警告`() {
        val plan = source("src/main/java/io/github/m0use001/planability/ai/ptl/PlanTools.kt")
        val seg = plan.substringAfter("private suspend fun todoEdit(").substringBefore("private suspend fun todoDelete(")
        assertTrue(
            "原待办带重复规则而本次 repeat 未传时必须显式警告（场景②把新建误做成编辑，weekly/1,3,5 被静默继承）",
            seg.contains("带重复规则") && seg.contains("repeat:none"),
        )
    }

    @Test
    fun `系统提示要求复述被改对象标题`() {
        val ctx = source("src/main/java/io/github/m0use001/planability/ai/ContextEngine.kt")
        assertTrue(
            "系统提示必须要求模型复述被改对象的标题（以工具返回的标题为准）",
            ctx.contains("复述") && ctx.contains("工具返回"),
        )
    }
}
