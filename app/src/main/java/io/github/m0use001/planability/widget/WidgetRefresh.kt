package io.github.m0use001.planability.widget

import android.content.Context
import androidx.glance.appwidget.updateAll
import io.github.m0use001.planability.data.Graph
import kotlinx.coroutines.launch

/**
 * 小组件的**统一刷新入口**（2026-10-29 起七个：快捷指令/完成趋势/即将到来三席
 * 按用户指令删除）。
 *
 * 之所以单独抽一个对象而不是继续留在 `PlanabilityApp` 里：
 * 现在有**三条路**要刷新组件（数据变更、系统明暗变化、跨零点定时），
 * 三份 `updateAll` 列表迟早会写漏一个。
 *
 * 大时钟拆成数字/堆叠两个 provider 后是第 7、8 席：两者共用同一份数据，
 * 但 `updateAll` 按 provider 分别推送，漏一个就会出现「一个变了另一个没变」。
 * 「屏幕使用时间」是第 9 席（数据在 30 分钟 updatePeriod + 刷新热区之外，
 * 数据变更/明暗/跨零点也跟着全量刷，桌面才会跟应用内一致）；
 * 「今日数据」是第 10 席（四维今日进度，含屏幕时间口径，同上）。
 */
object WidgetRefresh {

    fun all(context: Context) {
        val app = context.applicationContext
        Graph.appScope.launch {
            // 单个组件失败不该拖累其它组件，所以逐个 runCatching 包一层；
            // 外层再包一层兜住 updateAll 自身抛异（例如 widget 已被删）。
            runCatching { TodayWidget().updateAll(app) }
            runCatching { CountdownWidget().updateAll(app) }
            runCatching { RangeWidget().updateAll(app) }
            runCatching { ClockWidget().updateAll(app) }
            runCatching { ClockStackedWidget().updateAll(app) }
            runCatching { ScreenTimeWidget().updateAll(app) }
            runCatching { TodayStatsWidget().updateAll(app) }
            // 时钟的分钟级心跳跟着所有刷新路径续上：数据变更/明暗/巡检都会重排下一跳
            WidgetClockTicker.schedule(app)
        }
    }

    /** 只重推两款大时钟（分钟级心跳专用，不惊动其它组件） */
    fun clocks(context: Context) {
        val app = context.applicationContext
        Graph.appScope.launch { clocksNow(app) }
    }

    /**
     * 只推倒数日组件（2026-10-26 用户「倒数日新建后更新延迟」）：
     * 原先只有启动/跨零点/30 分钟周期会刷新，增删改后桌面要等半天。
     * 倒数日的增删改入口都在 `CountdownManageScreen`，那边调这个即时推。
     */
    fun countdownsNow(context: Context) {
        val app = context.applicationContext
        Graph.appScope.launch {
            runCatching { CountdownWidget().updateAll(app) }
        }
    }

    /** 同上但**挂起直到推完**：心跳 receiver 用 goAsync 持住广播窗口等它，
     *  避免进程在协程完成前又被缓存冻结、重绘被吞（时间延迟的根因之一） */
    suspend fun clocksNow(context: Context) {
        val app = context.applicationContext
        runCatching { ClockWidget().updateAll(app) }
        runCatching { ClockStackedWidget().updateAll(app) }
    }
}