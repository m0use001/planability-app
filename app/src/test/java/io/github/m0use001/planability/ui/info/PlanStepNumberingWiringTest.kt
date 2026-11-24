package io.github.m0use001.planability.ui.info

import io.github.m0use001.planability.data.ChatMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 2026-11-19 用户实报：「计划推进组件的自动编号把模型输出吃了，
 * 实际上这里应该是 3.8 反导数，3 被吞了」。
 *
 * 这一批钉两件事：
 * 1. **聊天里的计划卡**必须走 [io.github.m0use001.planability.ui.common.planStepLabel]，
 *    并且自动序号只在「模型没自带章节号」时才渲染（源码级守卫：老的
 *    `Regex("""^\s*\d+\s*[.、．)）]?\s*""")` 一旦复活就会把「3.8」剥成「8」）；
 * 2. **工作流菜单**与计划卡共用同一口径（不能一个显示 3.8、另一个显示 8）。
 */
class PlanStepNumberingWiringTest {

    private fun source(rel: String): String {
        val cands = listOf(File(rel), File("app", rel))
        val f = cands.firstOrNull { it.isFile }
        assertTrue("源码不在了：$rel", f != null)
        return f!!.readText(Charsets.UTF_8).replace("\r\n", "\n")
    }

    private val chatComponents: String
        get() = source("src/main/java/io/github/m0use001/planability/ui/info/ChatComponents.kt")

    private val checklist: String
        get() = source("src/main/java/io/github/m0use001/planability/ui/info/ChatPlanChecklist.kt")

    @Test
    fun `计划卡必须走统一的序号口径`() {
        assertTrue(
            "ChatComponents 的计划卡没用 planStepLabel（老正则会把「3.8」剥成「8」）",
            chatComponents.contains("planStepLabel(")
        )
        assertTrue(
            "老的剥序号正则还在计划卡里",
            !chatComponents.contains("""^\s*\d+\s*[.、．)）]?\s*""")
        )
    }

    @Test
    fun `自动序号必须按 showIndex 条件渲染`() {
        assertTrue(
            "计划卡的序号必须受 showIndex 控制（模型自带章节号时不再套 1.）",
            chatComponents.contains("label.showIndex")
        )
    }

    @Test
    fun `工作流菜单与计划卡同口径`() {
        assertTrue(
            "工作流菜单没用 planStepLabel（聊天里显示 3.8、菜单里显示 8 = 两副面孔）",
            checklist.contains("planStepLabel(")
        )
        assertTrue(
            "工作流菜单里旧的剥序号正则还在",
            !checklist.contains("""^\s*\d+\s*[.、．)）]?\s*""")
        )
    }

    /** 端到端：计划组件里名为「3.8 反导数」的步骤，进工作流菜单后不能变成「8 反导数」。 */
    @Test
    fun `计划组件步骤名带章节号时工作流菜单保留原文`() {
        val content = "```plan\n" +
            "{\"title\":\"微积分\",\"steps\":[" +
            "{\"name\":\"3.8 反导数：定义与公式表\",\"detail\":\"\"}," +
            "{\"name\":\"2 练习 20 题\",\"detail\":\"\"}]}\n```"
        val plans = parsePlanChecklists(
            listOf(ChatMessage(id = 1, sessionId = 1, role = "assistant", content = content, createdAt = 100))
        )
        assertEquals(1, plans.size)
        assertEquals("3.8 反导数：定义与公式表", plans[0].items[0].text)
        assertEquals("练习 20 题", plans[0].items[1].text)
    }
}
