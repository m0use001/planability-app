package io.github.m0use001.planability.ui.info

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 助理中枢主界面的**计数唯一性**锁（2026-11-08 用户：「中枢界面怎么有 2 个待处理，
 * 这会让用户感到疑惑」）。
 *
 * 当时的样子：顶栏一排「待审 N / 会话 N」胶囊，下面第一屏入口卡又写
 * 「待处理审核 · N 条待审（待办 X · 日程 Y）」「会话 · N 个会话」——同一个数字同屏两遍，
 * 用户不知道是不是两个东西。
 *
 * 规则：**主界面同一个计数只允许出现一次**，落在功能入口卡上（那里能给待办/日程拆分）；
 * 顶栏不再重复。二级页（HubReviewScreen 等）里的计数是另一个界面的局部状态，不受此限。
 */
class AssistantHubUiTest {

    @Test
    fun `主界面待审与会话计数只出现在入口卡`() {
        val code = ChatSourceGuard.readSourceCode(
            "app/src/main/java/io/github/m0use001/planability/ui/info/AssistantHubScreen.kt"
        )
        assertFalse(
            "顶栏又加了「待审 N」胶囊——和入口卡的计数同屏重复，用户会当成两个东西",
            code.contains("HubPill(\"待审 \${'$'}pendingTotal\")")
        )
        assertFalse(
            "顶栏又加了「会话 N」胶囊——和入口卡的计数同屏重复",
            code.contains("HubPill(\"会话 \${'$'}sessionCount\")")
        )
        assertTrue(
            "入口卡的待审计数（含待办/日程拆分）丢了——入口退化不消失，数字要有人报",
            code.contains("条待审（待办")
        )
    }

    @Test
    fun `审核与历史是上下级 不再是两个并列页面`() {
        // 2026-11-08 用户「待处理审核与待处理，2 个界面但功能相似」：
        // 现在分工 = 审核页（待确认：✓/✕/编辑/跟助理说） + 它的下级「审核历史」（已入库/已忽略/删除）。
        val hub = ChatSourceGuard.readSourceCode(
            "app/src/main/java/io/github/m0use001/planability/ui/info/AssistantHubScreen.kt"
        )
        assertTrue("审核页必须带历史入口（onOpenHistory）", hub.contains("onOpenHistory"))
        assertTrue("编辑能力必须留在审核页（原待确认 tab 收敛后不能丢）", hub.contains("onEdit"))
        // 2026-11-26：采集三页已抽到可选源集，源码面 = 主屏 + 采集页 两文件合读
        // （公开版无采集页文件，只读主屏——审核/历史结构断言在主屏部分）。
        // 附加源集目录按扫描发现（app/src 下除约定目录外的都算），不在源码里点名内部目录名。
        val info = ChatSourceGuard.readSourceCode(
            "app/src/main/java/io/github/m0use001/planability/ui/info/InfoCenterScreen.kt"
        ) + java.io.File("app/src").listFiles { d -> d.isDirectory }
            .orEmpty()
            .filter { it.name !in setOf("main", "test", "androidTest", "benchmark") }
            .map { java.io.File(it, "kotlin/io/github/m0use001/planability/info/InfoCaptureScreens.kt") }
            .firstOrNull { it.isFile }
            ?.readText(Charsets.UTF_8) ?: ""
        assertFalse("旧的并列「待处理」页不许回潮（PendingScreen）", info.contains("fun PendingScreen("))
        assertTrue("历史页应叫 ReviewHistoryScreen", info.contains("fun ReviewHistoryScreen("))
        assertFalse("中枢主界面不能再挂第二个待处理入口（onOpenList）", info.contains("onOpenList = {"))
    }

    @Test
    fun `中枢入口直达会话 低频项收进更多`() {
        val hub = ChatSourceGuard.readSourceCode(
            "app/src/main/java/io/github/m0use001/planability/ui/info/AssistantHubScreen.kt"
        )
        // 中枢功能页不再自带输入条（只剩审核页那一个 HubComposer）——"发一条消息才进会话"的路已拆
        // 只数调用点（定义那一行 "private fun HubComposer(" 不算）
        val composers = hub.lines().count { it.contains("HubComposer(") && !it.contains("fun HubComposer(") }
        assertEquals("中枢功能页不应再有输入条（应直达会话）", 1, composers)
        assertTrue("项目文件/项目记忆应收进「更多」", hub.contains("\"更多\"") && hub.contains("MoreHoriz"))
        // 2026-11-26：采集三页已抽到可选源集，源码面 = 主屏 + 采集页 两文件合读
        // （公开版无采集页文件，只读主屏——审核/历史结构断言在主屏部分）。
        // 附加源集目录按扫描发现（app/src 下除约定目录外的都算），不在源码里点名内部目录名。
        val info = ChatSourceGuard.readSourceCode(
            "app/src/main/java/io/github/m0use001/planability/ui/info/InfoCenterScreen.kt"
        ) + java.io.File("app/src").listFiles { d -> d.isDirectory }
            .orEmpty()
            .filter { it.name !in setOf("main", "test", "androidTest", "benchmark") }
            .map { java.io.File(it, "kotlin/io/github/m0use001/planability/info/InfoCaptureScreens.kt") }
            .firstOrNull { it.isFile }
            ?.readText(Charsets.UTF_8) ?: ""
        assertTrue(
            "抽屉中枢入口必须直达主会话（onPending 里 ensureMainSession）",
            info.contains("HubProject.ensureMainSession(aid, pid)")
        )
        val chat = ChatSourceGuard.readSourceCode(
            "app/src/main/java/io/github/m0use001/planability/ui/info/ChatScreen.kt"
        )
        assertTrue("会话 ⋮ 菜单要有中枢功能入口", chat.contains("onOpenHub") && chat.contains("\"中枢功能\""))
    }
}
