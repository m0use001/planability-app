package io.github.m0use001.planability.ui.info

import io.github.m0use001.planability.data.ChatMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * v53 工作流胶囊：从 AI 回复里解析计划勾选清单。
 *
 * 钉的口径：
 * - 只认 assistant 消息里的 `- [ ] / - [x]`（允许 `*` 与缩进）；
 * - **≥2 个勾选项才算计划**——正文里孤零零一个 checkbox 是举例，不是计划；
 * - 多条计划按时间排序，「最新一条」是胶囊显示的口径（AI 重发计划即整体覆盖）。
 */
class ChatPlanChecklistTest {

    private fun msg(id: Long, at: Long, content: String, role: String = "assistant") =
        ChatMessage(id = id, sessionId = 1, role = role, content = content, createdAt = at)

    @Test
    fun parsesCheckedAndUnchecked() {
        val plans = parsePlanChecklists(
            listOf(
                msg(1, 100, "计划如下：\n- [x] 读文件\n- [ ] 写笔记\n- [X] 大写也认")
            )
        )
        assertEquals(1, plans.size)
        assertEquals(3, plans[0].totalCount)
        assertEquals(2, plans[0].doneCount)
        assertEquals("写笔记", plans[0].items[1].text)
    }

    @Test
    fun requiresAtLeastTwoItems() {
        assertNull(latestPlan(listOf(msg(1, 100, "比如 `- [ ]` 这样写"))))
        assertNull(latestPlan(listOf(msg(1, 100, "- [x] 只有一个"))))
    }

    @Test
    fun ignoresUserMessages() {
        assertNull(
            latestPlan(
                listOf(msg(1, 100, "- [ ] 我自己写的清单", role = "user"), msg(2, 200, "- [ ] 另一条", role = "user"))
            )
        )
    }

    @Test
    fun starBulletAndIndentWork() {
        val plans = parsePlanChecklists(
            listOf(msg(1, 100, "* [ ] 甲\n  * [x] 乙"))
        )
        assertEquals(1, plans.size)
        assertEquals(2, plans[0].totalCount)
        assertTrue(plans[0].items[1].done)
    }

    @Test
    fun multiplePlansSortedByTime() {
        val plans = parsePlanChecklists(
            listOf(
                msg(1, 200, "- [x] 新\n- [x] 新"),
                msg(2, 100, "- [ ] 旧\n- [ ] 旧")
            )
        )
        assertEquals(listOf(2L, 1L), plans.map { it.messageId })
    }

    @Test
    fun latestPlanPicksNewest() {
        val msgs = listOf(
            msg(1, 100, "- [x] 旧一\n- [x] 旧二"),
            msg(2, 300, "- [ ] 新一\n- [x] 新二")
        )
        val latest = latestPlan(msgs)!!
        assertEquals(2L, latest.messageId)
        assertEquals(1, latest.doneCount)
    }

    /**
     * v53.5 防回归钉（源码级断言，本条 bug 单测全绿也测不到）：
     * v53 胶囊只在 `planNow != null` 时渲染——真机全库 1900+ 条 assistant 消息里
     * `- [ ]` 清单出现 0 次，胶囊 0 次显示，用户以为功能没做。
     * 钉死：①胶囊 Text 必须无条件渲染（三元选「计划 m/n」或「工作流」）；
     * ②面板挂载不得再卡 currentId>0（草稿会话点胶囊不许没反应）。
     */
    @Test
    fun 胶囊常驻_不随计划有无消失() {
        val c = File("src/main/java/io/github/m0use001/planability/ui/info/ChatScreen.kt")
            .readText()
            .lines()
            .filterNot { it.trimStart().startsWith("//") }
            .joinToString("\n")
        val pill = c.substringAfter("val planNow = remember(state.messages)", "")
            .substringBefore("val ctxWindow = io.github.m0use001.planability.data.contextWindowOf")
        assertTrue("没找到工作流胶囊块（挪位置了？更新定位锚点）", pill.isNotEmpty())
        assertFalse(
            "胶囊又在 if (planNow != null) 里了——没有 AI 勾选清单的会话（=绝大多数）会看不到入口",
            Regex("""if\s*\(\s*planNow\s*!=\s*null\s*\)\s*\{""").containsMatchIn(pill)
        )
        assertTrue(
            "胶囊没有「工作流」兜底文案——无计划会话的入口不见了",
            pill.contains("?: \"工作流\"")
        )
        assertTrue(
            "工作流面板挂载又卡 currentId>0 —— 草稿会话里点胶囊没反应",
            !c.contains("if (showWorkflow && currentId > 0L)")
        )
    }

    // ==================== 2026-11-12 计划组件接入工作流菜单 ====================

    /** 用户拍板：AI 用计划组件（```plan JSON）排的计划也要进工作流二级菜单 */
    @Test
    fun 计划组件步骤进菜单_全部未完成() {
        val content = "方案如下：\n```plan\n" +
            "{\"title\":\"迁移\",\"goal\":\"换库\",\"steps\":[{\"name\":\"1 备份\",\"detail\":\"全量\"},{\"name\":\"2 切流\",\"detail\":\"\"}]}\n" +
            "```"
        val plans = parsePlanChecklists(listOf(msg(1, 100, content)))
        assertEquals(1, plans.size)
        assertEquals(2, plans[0].totalCount)
        assertEquals(0, plans[0].doneCount)
        // 组件自带的序号前缀剥掉（渲染端会自己编 1. 2. 3.）
        assertEquals("备份", plans[0].items[0].text)
        assertEquals("切流", plans[0].items[1].text)
    }

    /** 单步计划不算（孤步不是计划，与勾选清单同口径） */
    @Test
    fun 计划组件单步不算() {
        val content = "```plan\n{\"steps\":[{\"name\":\"只有一步\"}]}\n```"
        assertNull(latestPlan(listOf(msg(1, 100, content))))
    }

    /** 同一条消息里勾选清单与组件并存：≥2 项的勾选清单优先（组件是它的可视化） */
    @Test
    fun 勾选清单与组件并存时清单优先() {
        val content = "- [x] 读文件\n- [ ] 写笔记\n" +
            "```plan\n{\"steps\":[{\"name\":\"备份\"},{\"name\":\"切流\"}]}\n```"
        val plans = parsePlanChecklists(listOf(msg(1, 100, content)))
        assertEquals(1, plans.size)
        assertEquals("读文件", plans[0].items[0].text)
        assertEquals(1, plans[0].doneCount)
    }

    /** 非法 JSON 的 plan 围栏回落为不算（与渲染端「未闭合回落文本」同口径） */
    @Test
    fun 非法JSON不算计划() {
        val content = "```plan\n这不是JSON\n```"
        assertNull(latestPlan(listOf(msg(1, 100, content))))
    }

    /**
     * 2026-11-15 用户「模型调用了计划组件，但工作流中的计划没有同步更新」：
     * 模型用 DSML 标签写 plan 卡时，正文渲染端有 normalizeComponentMarkup 翻译成
     * ```plan 围栏所以聊天里看得见；工作流面板原来拿正则扫**原文**，看不见 DSML → 不同步。
     * 钉死：DSML 形态的 plan 卡必须也能进工作流。
     */
    @Test
    fun DSML形态的plan卡也进工作流() {
        val content = "<｜DSML｜ plan>\n" +
            "{\"title\":\"迁移\",\"steps\":[{\"name\":\"1 备份\"},{\"name\":\"2 切流\"}]}\n" +
            "</｜DSML｜>"
        val plans = parsePlanChecklists(listOf(msg(1, 100, content)))
        assertEquals("DSML 形态的 plan 卡没进工作流（正文渲染有、工作流没有=用户实报的不同步）", 1, plans.size)
        assertEquals(2, plans[0].totalCount)
    }

    /** 单行写法 ```plan {...}``` 也要能进工作流（渲染端支持，解析端不能漏） */
    @Test
    fun 单行plan围栏也进工作流() {
        val content = "```plan {\"steps\":[{\"name\":\"甲\"},{\"name\":\"乙\"}]}```"
        val plans = parsePlanChecklists(listOf(msg(1, 100, content)))
        assertEquals(1, plans.size)
        assertEquals(2, plans[0].totalCount)
    }
}
