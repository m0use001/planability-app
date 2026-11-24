package io.github.m0use001.planability.ai.ptl

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * P0-1 守卫：todo_edit/schedule_edit 的 **id 号簿混淆**（2026-11-09 审计两次动态复现）。
 *
 * 事故：模型把「助理中枢草稿 #N」的编号当成 todos/schedules 表 id 传给 todo_edit ——
 * 工具把无关待办整条改掉（连重复规则一起保留），全程 ok 无警示。真实案例：
 * `todo_edit {"id":1,"title":"Finish reading chapter 3 by Friday",...}` 把「审计-重复背单词」
 * （weekly/1,3,5/permanent）改名成英文待办；`todo_edit {"id":2,"date":"明天"}` 把
 * 「审计-重要紧急」日期改到明天。audit 全绿，用户两天后才发现。
 *
 * 三道闸缺一即红：
 * ① 工具描述钉死 id 来源（模型层）；② 系统提示同款规则（对话层）；
 * ③ 确认卡展示被改对象当前完整状态（用户层，ToolExecutor 注入 `_当前对象`）。
 */
class TodoEditIdGuardTest {

    private fun source(rel: String): String {
        val f = File(rel)
        assertTrue("源码不在了（cwd 应为 app 模块目录）：$rel", f.isFile)
        return f.readText(Charsets.UTF_8)
    }

    @Test
    fun `todo_edit 工具描述钉死 id 必须来自 todo_query`() {
        val defs = source("src/main/java/io/github/m0use001/planability/ai/ptl/ToolDefs.kt")
        val seg = defs.substringAfter("name = \"todo_edit\"").substringBefore("ToolDef(")
        assertTrue(
            "todo_edit 描述必须写明「id 来自 todo_query、草稿编号不是 id」——弱模型会把「草稿 #1」直接当 id 改坏无关待办",
            seg.contains("todo_query") && seg.contains("草稿编号") || seg.contains("草稿编号不是 id"),
        )
    }

    @Test
    fun `确认卡必须注入被改对象的当前状态`() {
        val exec = source("src/main/java/io/github/m0use001/planability/ai/ptl/ToolExecutor.kt")
        assertTrue(
            "ToolExecutor 必须在弹确认卡前把被改对象的现状（标题/日期/重复规则）注入 argsJson（_当前对象 键）——用户要能一眼看出改错了对象",
            exec.contains("_当前对象") && exec.contains("todoById") && exec.contains("scheduleById"),
        )
    }

    @Test
    fun `执行层 id 查不到时要点明是草稿编号`() {
        val plan = source("src/main/java/io/github/m0use001/planability/ai/ptl/PlanTools.kt")
        assertTrue(
            "PlanTools 必须有 missingIdMsg：id 查不到且撞上 pending 草稿编号时，报错要写明「这是草稿不是 id」",
            plan.contains("missingIdMsg") && plan.contains("是助理中枢的草稿") && plan.contains("pending_commit"),
        )
        assertTrue(
            "todoEdit 的存在性校验必须走 missingIdMsg（不能只回「不存在」——弱模型会原样重试同 id）",
            plan.contains("Graph.plan.todoById(id) ?: return ToolExecResult.fail(missingIdMsg(\"待办\", id))"),
        )
    }

    @Test
    fun `系统提示写明草稿编号不是 id`() {
        val ctx = source("src/main/java/io/github/m0use001/planability/ai/ContextEngine.kt")
        assertTrue(
            "系统提示必须对模型钉死：todo_edit/schedule_edit 的 id 来自 query 工具，待处理草稿编号不是 id",
            ctx.contains("草稿编号不是"),
        )
    }
}
