package io.github.m0use001.planability.ai.ptl

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 2026-11-12 用户「AI又反映有问题」（◆，四级备考时段安排）回归守卫。
 *
 * 真机实锤：用户「你给四级备考那几个待办安排每天具体实际时间段」→ 模型整轮没调
 * `todo_query`（tools_audit 该会话 0 次），只调了 data_history（给 8 条样本、无编号）
 * 和 kb_ask，然后两轮对用户断言「我没有查待办明细的工具」「没法给已有待办加时刻」。
 *
 * 两条断言都是幻觉：todo_query 实现齐全（每条带「编号 N」+重复规则+总数），
 * todo_edit 自带 due 参数且支持重复待办（P2 2026-11-10 就加了）。
 * 真正的问题是**模型看不见**：todo_query 不在召回地板，这轮被挤出 top-K。
 * 与 2026-11-08 网页组、2026-11-11 文件读取组同一病灶（丢工具 = 模型断言没能力）。
 */
class TodoQueryVisibilityGuardTest {

    private val allNames = ToolRegistry.ALL.map { it.name }

    private fun def(name: String) = ToolRegistry.ALL.first { it.name == name }

    @Test
    fun `实况复现 - 排时段轮必须还能看见 todo_query`() {
        // 1316 用户原句（12:09）：词法上没有「查/列表」字样，只有「待办/安排/时段」
        val keep = requireNotNull(
            ToolRouter.select(allNames, "你给四级备考那几个待办安排每天具体实际时间段", emptySet())
        )
        assertTrue("todo_query 被路由挤掉（模型就会断言没有查待办的工具）：$keep", "todo_query" in keep)
    }

    @Test
    fun `todo_query 与 schedule_query 必须是召回地板`() {
        // 查询类工具是兜底自救的口子：任何轮次都可能突然需要「拿到编号再改」
        assertTrue("todo_query 必须在召回地板", "todo_query" in ToolRouter.FLOOR)
        assertTrue("schedule_query 必须在召回地板", "schedule_query" in ToolRouter.FLOOR)
    }

    @Test
    fun `todo_query 描述要写明返回编号与改前先查的场景`() {
        val d = def("todo_query")
        assertTrue("cnDescription 要写明带编号（模型靠它决定能不能改）", d.cnDescription.contains("编号"))
        assertTrue("whenToUse 要覆盖「排时段/加时刻前先查编号」场景",
            d.whenToUse.contains("时段") || d.whenToUse.contains("时刻"))
    }

    @Test
    fun `todo_edit 描述要写明可加时刻`() {
        val d = def("todo_edit")
        assertTrue("todo_edit whenToUse 要点名 due/时刻（否则模型不知道能加时间）",
            d.whenToUse.contains("时刻") || d.whenToUse.contains("时间"))
    }

    @Test
    fun `data_history 待办与日程明细必须自带编号`() {
        val src = java.io.File("src/main/java/io/github/m0use001/planability/ai/ptl/ToolExecImpls.kt")
            .takeIf { it.isFile }?.readText()
            ?: java.io.File("app/src/main/java/io/github/m0use001/planability/ai/ptl/ToolExecImpls.kt").readText()
        // 2026-11-12 ◆ 第二次复发：todo_query 已在召回地板，deepseek-flash 仍只肯调
        // data_history 并断言「todo_query 不在工具列表」→ 模型信任的那份数据必须自带编号，
        // 否则「看到标题没有编号 → 断言没有改的工具」永远重演。
        assertTrue("data_history 待办明细没带编号", Regex("（编号 \" \\+ t\\.id").containsMatchIn(src))
        assertTrue("data_history 日程明细没带编号", src.contains("（编号 \" + s.id"))
        assertTrue("待办超 30 条要指路 todo_query", src.contains("更全用 todo_query 分页查"))
    }
}
