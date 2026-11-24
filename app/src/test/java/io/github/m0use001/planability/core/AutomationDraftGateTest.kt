package io.github.m0use001.planability.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 自动化动作的「中枢草稿闸」（2026-11-12 用户「AI 生成的所有日程待办全都要先进助理中枢」）。
 *
 * 审计实锤的绕过通道：`ActionRegistry` 的 `todo_add` / `schedule_add` 原来**直接**
 * `saveTodo`/`saveSchedule` 进时间轴——AI 在普通会话里 `automation_create` 建一条带这俩
 * 动作的流水线再 `automation_run`，产出就绕过助理中枢审核落库了。v54 已把会话侧
 * todo_create/schedule_create/pending_add 全部收口到草稿，这条是最后一个洞。
 *
 * 修法：动作改产 PendingItem 草稿（source="automation"），入库仍只有 PendingImporter
 * 一条通道（中枢卡片 ✓ / pending_commit）。试运行（automationId=0）什么都不写。
 */
class AutomationDraftGateTest {

    private fun source(rel: String): String {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null && !File(dir, "app/src/main/java/io/github/m0use001/planability").isDirectory) {
            dir = dir.parentFile
        }
        val f = File(requireNotNull(dir) { "找不到仓库根" }, rel)
        assertTrue("源码不在了：$rel", f.isFile)
        // Windows checkout 是 CRLF：结构化切块（"\n            }\n"）不归一化就恒命中不到，
        // 切出来是空串——断言全红而且看起来像功能没改（2026-11-12 实测踩中）
        return f.readText(Charsets.UTF_8).replace("\r\n", "\n")
    }

    private val registry by lazy {
        source("app/src/main/java/io/github/m0use001/planability/core/ActionRegistry.kt")
    }

    private fun actionBlock(key: String): String {
        val idx = registry.indexOf("\"$key\" -> {")
        assertTrue("ActionRegistry 里找不到 $key 分支（user.dir=" + System.getProperty("user.dir") + "）", idx >= 0)
        return registry.substring(idx, registry.indexOf("\n            }\n", idx).coerceAtLeast(idx))
    }

    @Test
    fun `todo_add 落中枢草稿，不直写时间轴`() {
        val block = actionBlock("todo_add")
        assertTrue("todo_add 没走草稿通道（addPendingItems）", block.contains("addPendingItems"))
        assertTrue("todo_add 还在直写时间轴", !block.contains("Graph.plan.saveTodo"))
        assertTrue("草稿来源没标 automation", block.contains("source = \"automation\""))
        assertTrue("payload 没带原始参数（入库时 PendingImporter 靠它忠实重建）", block.contains("payload"))
    }

    @Test
    fun `schedule_add 落中枢草稿，不直写时间轴`() {
        val block = actionBlock("schedule_add")
        assertTrue("schedule_add 没走草稿通道（addPendingItems）", block.contains("addPendingItems"))
        assertTrue("schedule_add 还在直写时间轴", !block.contains("Graph.plan.saveSchedule"))
        assertTrue("草稿来源没标 automation", block.contains("source = \"automation\""))
        // 时间走 suggestedStart/suggestedEnd 兜底字段（毫秒），不塞格式化文本让入库时再猜
        assertTrue("日程时间没放 suggestedStart", block.contains("suggestedStart = start"))
    }

    @Test
    fun `timeText 带完整日期，不同天的自动化草稿不被判重吞掉`() {
        // PendingDedup 靠 timeText 里的日期区分「不同天的同类事件」：
        // DATE_ISO 只认 yyyy-M-d 四位年份——timeText 用 "M-d" 的话每日自动化
        // 隔天再跑会被当成同一条，隔天那份静默丢失
        val todoBlock = actionBlock("todo_add")
        val schedBlock = actionBlock("schedule_add")
        assertTrue("todo_add timeText 缺 yyyy-M-d 完整日期", todoBlock.contains("yyyy-M-d"))
        assertTrue("schedule_add timeText 缺 yyyy-M-d 完整日期", schedBlock.contains("yyyy-M-d"))
        // 自证判据：真的用 PendingDedup 验证「M-d 写法会被判重、yyyy-M-d 不会」
        fun item(timeText: String) = io.github.m0use001.planability.data.PendingItem(
            title = "每日站会", kind = "schedule", timeText = timeText
        )
        val badOld = item("11-12 09:00-09:30")
        val badNew = item("11-13 09:00-09:30")
        val goodOld = item("2026-11-12 09:00-09:30")
        val goodNew = item("2026-11-13 09:00-09:30")
        assertFalse("反例失效：M-d 居然区分出日期了，本测试失去意义",
            io.github.m0use001.planability.data.PendingDedup.monthDaySet(badOld.timeText).isNotEmpty())
        assertTrue("反例失效：M-d 两条居然不算重复",
            io.github.m0use001.planability.data.PendingDedup.isDuplicate(badOld, badNew))
        assertTrue("yyyy-M-d 两条不同天被判成重复（会吞掉隔天草稿）",
            !io.github.m0use001.planability.data.PendingDedup.isDuplicate(goodOld, goodNew))
    }

    @Test
    fun `试运行不落任何写`() {
        // automationId=0 是编辑器试运行——原来一步就建真待办，用户测个流水线库里多一条
        assertTrue("todo_add 缺试运行闸", actionBlock("todo_add").contains("automationId == 0L"))
        assertTrue("schedule_add 缺试运行闸", actionBlock("schedule_add").contains("automationId == 0L"))
    }

    @Test
    fun `动作说明改口为草稿语义`() {
        assertTrue(
            "todo_add 的 ActionDef 说明还在说「计划页可见」",
            registry.contains("\"todo_add\", \"新建待办\", \"把一条待办放进助理中枢草稿")
        )
        assertTrue(
            "schedule_add 的 ActionDef 说明还在说「往计划页写」",
            registry.contains("\"schedule_add\", \"新建日程\",\n            \"把一条日程放进助理中枢草稿")
        )
    }

    @Test
    fun `三处来源标签认识 automation，不再误标成提取`() {
        val hub = source("app/src/main/java/io/github/m0use001/planability/ui/info/AssistantHubScreen.kt")
        val ctx = source("app/src/main/java/io/github/m0use001/planability/ai/ContextEngine.kt")
        val exec = source("app/src/main/java/io/github/m0use001/planability/ai/ptl/ToolExecImpls.kt")
        assertTrue("中枢卡片来源标签缺 automation", hub.contains("\"automation\" -> \"自动化\""))
        assertTrue("ContextEngine 草稿清单标签缺 automation", ctx.contains("\"automation\" -> \"自动化\""))
        assertTrue("pending_query 输出标签缺 automation", exec.contains("\"automation\" -> \"自动化\""))
        assertTrue("pending_query 的 source 过滤不认 automation", exec.contains("\"session\", \"capture\", \"manual\", \"automation\""))
    }
}
