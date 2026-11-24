package io.github.m0use001.planability.ui.info

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 平板（宽屏）会话页的**形态决策机**真值表（2026-11-19）。
 *
 * 背景：平板信息页原来是「会话列表 + 对话」两块各画各的，左栏（AI 会话）与右滑抽屉
 * （工作台 → 项目与会话树）画的是同一批会话 = 用户报的「重复」；而且它们被装进一个
 * `fullscreen = true` 的 Dialog overlay，整块盖住 NavRail 与 App 外壳 = 用户报的「割裂」。
 *
 * 这里把「窗口宽不宽 + 当前看哪一块」收敛成纯函数 `ChatWideLayout.paneOf`，
 * 用穷举真值表钉死它：宽屏三态、窄屏恒一态。
 *
 * 纪律（照 PlanWideLayoutTest）：每条断言都做过**植入故障验证**——
 * 把 `showChat` 分支写反、把 `!isWide` 短路删掉，分别让对应用例转红后还原。
 */
class ChatWideLayoutTest {

    @Test
    fun `窄屏恒为首页态 手机路径由各自的 Dialog 承载`() {
        assertEquals(
            "窄屏不该进任何宽屏分支——手机路径是抽屉 + 全屏 Dialog 对话",
            ChatPane.Home,
            ChatWideLayout.paneOf(isWide = false, showChat = false, showSessions = false)
        )
        assertEquals(
            "窄屏即使会话状态为真也不该走宽屏形态",
            ChatPane.Home,
            ChatWideLayout.paneOf(isWide = false, showChat = true, showSessions = false)
        )
        assertEquals(
            "窄屏即使会话列表为真也不该走宽屏形态",
            ChatPane.Home,
            ChatWideLayout.paneOf(isWide = false, showChat = false, showSessions = true)
        )
    }

    @Test
    fun `宽屏三态 各自对应首页 会话列表 对话`() {
        assertEquals(
            "宽屏默认是首页态",
            ChatPane.Home,
            ChatWideLayout.paneOf(isWide = true, showChat = false, showSessions = false)
        )
        assertEquals(
            "宽屏打开会话列表 = 会话列表态",
            ChatPane.Sessions,
            ChatWideLayout.paneOf(isWide = true, showChat = false, showSessions = true)
        )
        assertEquals(
            "宽屏打开对话 = 对话态",
            ChatPane.Conversation,
            ChatWideLayout.paneOf(isWide = true, showChat = true, showSessions = false)
        )
    }

    @Test
    fun `对话优先于会话列表 两个状态同时为真时不能两处都渲染`() {
        assertEquals(
            "会话与列表同时为真时必须只显示一处（对话优先），否则右区画两份内容",
            ChatPane.Conversation,
            ChatWideLayout.paneOf(isWide = true, showChat = true, showSessions = true)
        )
    }

    @Test
    fun `侧栏宽度与会话正文列宽各有唯一出处`() {
        // 2026-11-20 v66（eb47ca46）：树板 340→380dp，对齐共享两板骨架 BoardLayout.DetailWidth
        assertEquals("常驻侧栏宽度应固定 380dp（对齐 BoardLayout.DetailWidth）", 380f, ChatWideLayout.SidebarWidth.value, 0.01f)
        assertEquals(
            "会话正文列上限应是 880dp（全 app 会话区唯一限宽来源）",
            880f,
            ChatWideLayout.ContentMaxWidth.value,
            0.01f
        )
        assertNotEquals(
            "正文列不能宽到把侧栏挤没（侧栏 340 + 正文 880 > 常见平板内容区）",
            ChatWideLayout.ContentMaxWidth.value + ChatWideLayout.SidebarWidth.value,
            0f
        )
        assertTrue(
            "正文列必须比侧栏宽，否则平板上正文被压成窄条",
            ChatWideLayout.ContentMaxWidth.value > ChatWideLayout.SidebarWidth.value
        )
    }

    @Test
    fun `形态只由宽窄与两个状态决定 不读别的`() {
        // 同一组输入必须永远给同一结果（纯函数，不能有隐藏状态）
        val a = ChatWideLayout.paneOf(isWide = true, showChat = true, showSessions = false)
        val b = ChatWideLayout.paneOf(isWide = true, showChat = true, showSessions = false)
        assertEquals("决策机必须是纯函数", a, b)
    }
}
