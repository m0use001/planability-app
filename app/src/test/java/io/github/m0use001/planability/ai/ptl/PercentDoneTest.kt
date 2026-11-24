package io.github.m0use001.planability.ai.ptl

import io.github.m0use001.planability.data.DayBundle
import io.github.m0use001.planability.data.Todo
import io.github.m0use001.planability.ui.home.scoreBreakdown
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * 2026-11-02「待办百分比完成」防回归钉。
 *
 * 口径：Todo.percentDone -1=未启用；0..99=启用未完成；打卡完成=100。
 * 完成率按加权计：完成=1、未完成且启用=percent/100、未启用=0——
 * 未启用的待办**不得**因为字段存在就被算成有进度。
 */
class PercentDoneTest {

    private fun bundle(todos: List<io.github.m0use001.planability.data.Todo>) = DayBundle(
        date = LocalDate.of(2026, 11, 2),
        schedules = emptyList(),
        todos = todos,
        hourTasks = emptyList(),
        events = emptyList(),
        checks = emptyList(),
        doneCount = 0
    )

    private fun bd(todos: List<io.github.m0use001.planability.data.Todo>) = io.github.m0use001.planability.ui.home.scoreBreakdown(
        bundle(todos), screenMinutes = 0, sleep = null,
        screenLimitMin = 0, screenPenaltyPer30 = 0, sleepGoalMin = 0, sleepPenaltyPer30 = 0
    )

    @Test
    fun `百分比加权计入完成率`() {
        // 1 条完成 + 1 条 50% → done=1.5/2=75%
        val r = bd(
            listOf(
                io.github.m0use001.planability.data.Todo(id = 1, title = "a", done = true),
                io.github.m0use001.planability.data.Todo(id = 2, title = "b", percentDone = 50)
            )
        )
        assertEquals(1.5f, r.done, 0.001f)
        assertEquals(0.75f, r.rate, 0.001f)
        assertEquals(1, r.percentCount)
    }

    @Test
    fun `未启用的待办按 0 算_不白送进度`() {
        val r = bd(
            listOf(
                io.github.m0use001.planability.data.Todo(id = 1, title = "a", percentDone = -1),
                io.github.m0use001.planability.data.Todo(id = 2, title = "b", percentDone = 0)
            )
        )
        // percentDone=-1 未启用算 0；0% 也算 0 —— 都是 0/2
        assertEquals(0f, r.done, 0.001f)
        // percentDone=0 也是启用（做了 0%），计入 percentCount；-1 未启用不计
        assertEquals(1, r.percentCount)
    }

    @Test
    fun `评分详情页口径注释只在有启用项时出现`() {
        val none = bd(listOf(io.github.m0use001.planability.data.Todo(id = 1, title = "a")))
        assertEquals(0, none.percentCount)
        val some = bd(listOf(io.github.m0use001.planability.data.Todo(id = 1, title = "a", percentDone = 30)))
        assertEquals(1, some.percentCount)
    }

    @Test
    fun `todo工具都有percent参数`() {
        for (name in listOf("todo_create", "todo_edit")) {
            val def = ToolRegistry.byName(name)
            assertNotNull("工具 $name 不存在了", def)
            assertTrue(
                "工具 $name 没有 percent 参数——百分比完成对 AI 又不可设了",
                def!!.params.any { it.name == "percent" }
            )
        }
        // todo_create 的 percent 描述要写明「100=打卡完成」，防模型拿它当打卡入口
        assertTrue(
            "todo_create 的 percent 描述丢了 100=打卡口径",
            ToolRegistry.byName("todo_create")!!.params.first { it.name == "percent" }
                .description.contains("100")
        )
    }
}
