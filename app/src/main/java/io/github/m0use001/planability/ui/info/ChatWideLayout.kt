package io.github.m0use001.planability.ui.info

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 平板（宽屏）会话页的**形态决策机**（2026-11-19）。
 *
 * 背景：平板上的信息页原来是「会话列表 + 对话」两块**各自独立**的渲染——
 *   · 左栏 `ChatSessionsBody`（AI 会话 + 每行 ⋮）
 *   · 右滑抽屉 `WorkbenchDrawerContent`（工作台 → 项目与会话树，每个会话叶也带 ⋮）
 * 同一批会话被渲染两遍（左栏那份还是简化版，项目树/搜索/统计只在抽屉里），
 * 而且两者被装进一个 `fullscreen = true` 的 Dialog overlay，整块盖住 NavRail 与 App 外壳，
 * 与计划页刚做完的「页内两板 + 白缝」是两套材质两套网格——用户看到的正是「内容块割裂」。
 *
 * 这里把「窗口宽不宽 + 当前在看哪一块」收敛成一个纯函数：
 *   · 宽屏 = 侧栏（常驻的工作台树）+ 内容区（首页 / 会话列表 / 对话）
 *   · 窄屏 = 原路径（抽屉 + 全屏 Dialog 对话），一行不改
 *
 * 纪律（照 `ui/plan/PlanWideLayout.kt`）：
 *   1. 宽度判断只此一处，页面里不许再散落 `if (wide)` 决定形态；
 *   2. 会话内容只有**一个**限宽来源（[ContentMaxWidth]），正文/工具行/思考块/输入框共用，
 *      否则每块各限一次就会长出三四条基线（用户「会话内容不整齐」）；
 *   3. 窄屏路径恒定不变——手机是主战场，平板适配不许拿手机换观感。
 */
enum class ChatPane {
    /** 窄屏 / 宽屏首页：内容区显示问候语 + 输入卡 + 今日安排 + 最近会话。 */
    Home,

    /** 宽屏「管理全部会话」：内容区显示会话列表。 */
    Sessions,

    /** 宽屏对话中：内容区显示聊天。 */
    Conversation,
}

object ChatWideLayout {
    /**
     * 常驻侧栏（工作台项目与会话树）宽度。
     * 2026-11-21 用户拍板「框的大小要对齐其他界面」：从 340dp 改成与
     * [io.github.m0use001.planability.ui.common.BoardLayout.DetailWidth]（= WideSecondaryWidth 380dp）
     * 同值——首页/计划/专注/日历的定宽详情板都是这个口径。
     */
    val SidebarWidth: Dp = 380.dp

    /**
     * 会话正文列的最大宽度——**全 app 会话区唯一的限宽来源**。
     * 760dp（原来的助手正文限宽）在 1600dp 平板上偏窄；880dp 兼顾行长与铺满。
     */
    val ContentMaxWidth: Dp = 880.dp

    /**
     * 形态决策：宽窄与「看哪一块」两个维度各自独立。
     * 窄屏恒 [ChatPane.Home]——手机路径由各自的 Dialog / 抽屉承载，不走这个决策。
     */
    fun paneOf(isWide: Boolean, showChat: Boolean, showSessions: Boolean): ChatPane = when {
        !isWide -> ChatPane.Home
        showChat -> ChatPane.Conversation
        showSessions -> ChatPane.Sessions
        else -> ChatPane.Home
    }
}
