package io.github.m0use001.planability.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 全局路由器：全局搜索 / 信息中心确认入库 / 小组件等入口
 * 跨页打开编辑器时，先切到目标 Tab，再由目标页消费 pending id。
 */
object Router {
    /** 待打开的待办编辑器 id（null 清除；0 = 新建） */
    var pendingTodoId by mutableStateOf<Long?>(null)
    var pendingScheduleId by mutableStateOf<Long?>(null)
    var pendingNoteId by mutableStateOf<Long?>(null)
    var pendingCountdownOpen by mutableStateOf(false)
    var pendingFocusStart by mutableStateOf(false)

    /** v12：从时间轴自动化块打开自动化管理页 */
    var pendingAutomationOpen by mutableStateOf(false)

    /** v16 批：跳转信息获取专属界面（群聊提纯） */
    var pendingGroupDigestOpen by mutableStateOf(false)

    fun openGroupDigest() {
        targetTab = "info"; pendingGroupDigestOpen = true
    }

    /** 系统分享进来的文本（ACTION_SEND → 新笔记） */
    var pendingSharedText by mutableStateOf<String?>(null)

    /** 系统分享进来的文本 → AI 对话预填输入（M5 分享直达对话） */
    var pendingSharedToChat by mutableStateOf<String?>(null)

    /** 需要切到的 tab */
    var targetTab by mutableStateOf<String?>(null)

    /**
     * v70f（2026-11-21 用户「跨页跳转按钮也要接入上下切换的页面动画」）：本次跨页跳转
     * 用哪种切页动效。**true（默认）= 纵滑抽纸条**（NavShell 的 slideSwitchTo，与
     * Rail/底栏点按同款）——UI 里的跳转按钮（专注页跳计划、我的→手机操控区域、
     * 搜索抽屉行、时间轴块、聊天 /automation……）对用户来说和亲手点 tab 没有本质
     * 区别，全量默认接入；**false = quick fade**——只给系统深链（小组件/快捷方式/
     * 分享/通知/调试钩子：MainActivity.handleShortcut 统一置 false；v68 拍板保留，
     * 冷启动/后台拉起不是用户在本 app 里抽纸条）。
     * 消费方（NavShell 的 Router 收集器）读后经 [consumeTab] 归位回 true。
     */
    var pendingJumpSlide by mutableStateOf(true)

    /**
     * 小组件深链：进计划页后要切到哪个子页（"todo" / "schedule"，null = 保持原样）。
     *
     * 为什么需要单独一个字段：`targetTab` 只能表达「哪个 Tab」，
     * 而计划页内部的「日程/待办」是 `PlanScreen` 的局部 state，外面碰不到。
     * 不加这个字段的话，「点小组件里的待办区」只能把用户丢到计划页首屏，
     * 还得自己再点一下——那就不能叫深链了。
     */
    var pendingPlanSubTab by mutableStateOf<String?>(null)

    /** 小组件点进来的 action 与 extra 键（与 `widget/WidgetCommon.kt` 共用） */
    const val ACTION_WIDGET_TAB = "io.github.m0use001.planability.action.WIDGET_TAB"
    const val EXTRA_TAB = "planability.tab"
    const val EXTRA_PLAN_SUB = "planability.planSub"

    /**
     * 2026-11-01：小组件点击的**直达目标**（tab 之外还要开哪一层）。
     *
     * 为什么加这个：`tab` + `planSub` 只能表达「去哪个一级页 / 计划页哪个分段」，
     * 而倒数日管理、月历这些是**页内覆盖层**（HomeScreen 的 showCountdowns / showCalendar），
     * 外面碰不到。此前倒数日组件只能 `clickOpenApp("plan")` —— 点它落到计划页，
     * 而不是倒数日管理（用户实锤「点倒数日小组件就该跳转倒数日」）。
     */
    const val EXTRA_WIDGET_OPEN = "planability.widgetOpen"
    const val WIDGET_OPEN_COUNTDOWN = "countdown"
    const val WIDGET_OPEN_CALENDAR = "calendar"

    /**
     * 页内覆盖层直达标志：MainActivity 置上，HomeScreen 消费后置回 false。
     * 与 [pendingCountdownOpen]（快捷方式用）分开是因为语义不同：
     * 那个是「新建倒数日」入口，这个是「小组件点了倒数日，给我打开它」。
     */
    var pendingWidgetCountdown by mutableStateOf(false)
    var pendingWidgetCalendar by mutableStateOf(false)

    fun openPlan(sub: String? = null) {
        targetTab = "plan"; pendingPlanSubTab = sub
    }

    fun openTodo(tab: String, id: Long) {
        targetTab = tab; pendingTodoId = id
    }

    fun openSchedule(tab: String, id: Long) {
        targetTab = tab; pendingScheduleId = id
    }

    fun openNote(id: Long) {
        targetTab = "notes"; pendingNoteId = id
    }

    fun openCountdownManager() {
        targetTab = "home"; pendingCountdownOpen = true
    }

    fun startFocus() {
        targetTab = "focus"; pendingFocusStart = true
    }

    /** v12：打开自动化与扩展管理页（信息页内） */
    fun openAutomations() {
        targetTab = "info"; pendingAutomationOpen = true
    }

    /**
     * v24：打开指定会话（导入分享对话后跳过去看）。
     * 形状同 [openAutomations]：切 tab + 一个 pending id，由 InfoCenterScreen 消费。
     * 那边已有 `chatSessionId` 局部 state，消费只需一行（并把我提交的 change 记在
     * phone-b 日志里）。不加这个通道的话，导入完只能靠用户自己去抽屉里找。
     */
    var pendingSessionId by mutableStateOf(0L)

    fun openSession(id: Long) {
        targetTab = "info"; pendingSessionId = id
    }

    /**
     * 打开「手机操控区域」页（信息页内；页面本体在设备功能包里，公开版无此页）。
     *
     * 为什么要走 Router 而不是各页自己管 state：这个页面目前**只挂在信息页的工作台抽屉**，
     * 那是 InfoCenterScreen 的局部 state，别的屏碰不到。现在「我的 → 设置」也要能进（phone-b 加的），
     * 于是需要一个跨屏通道 —— 形状照 [openAutomations]：切到目标 tab + 一个 pending 标志，
     * 由目标页消费。消费点在 InfoCenterScreen（属 phone-a 线），所以那边要有一行配合。
     */
    var pendingPhoneUseRegionOpen by mutableStateOf(false)

    fun openPhoneUseRegion() {
        targetTab = "info"; pendingPhoneUseRegionOpen = true
    }

    /** v19.7：日历按钮移入首页右滑抽屉——切回首页并打开月历视图 */
    var pendingHomeCalendar by mutableStateOf(false)

    fun openHomeCalendar() {
        targetTab = "home"; pendingHomeCalendar = true
    }

    fun openSharedText(text: String) {
        targetTab = "notes"; pendingSharedText = text
    }

    /** M5 分享直达对话：切到信息页并预填 AI 对话输入 */
    fun openSharedToChat(text: String) {
        targetTab = "info"; pendingSharedToChat = text
    }

    fun consumeTab(): String? {
        val t = targetTab
        targetTab = null
        // v70f：动效旗标读走了就归位（下一次 UI 跳转默认重新纵滑；系统深链自己置 false）
        pendingJumpSlide = true
        return t
    }
}
