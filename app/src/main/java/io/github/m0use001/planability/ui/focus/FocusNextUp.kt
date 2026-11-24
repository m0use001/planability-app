package io.github.m0use001.planability.ui.focus

import io.github.m0use001.planability.core.AppTime
import io.github.m0use001.planability.data.DayBundle
import io.github.m0use001.planability.data.Todo
import io.github.m0use001.planability.data.TodoProgress

/**
 * 专注页侧栏「接下来要做」的一行（2026-11-19 平板「别空」批）。
 *
 * [badge] 是左侧那一小列文字（时刻 / 「待办」/ 百分比），[startMillis] 只用于排序与判空
 * （0 = 无时刻），[isSchedule] 决定点击后走日程深链还是待办深链。
 */
data class NextUpItem(
    val id: Long,
    val title: String,
    val startMillis: Long,
    val badge: String,
    val isSchedule: Boolean,
)

/**
 * 从今日 [DayBundle] 里挑「接下来要做」的条目（纯函数，[FocusNextUpTest] 直接断言）。
 *
 * 口径（不新立查询，全部来自 `Graph.plan.loadDay(今天)`）：
 * - 待办：未完成；排序与首页今日侧栏**同一套**（有时刻优先 → 时刻 → 紧急 → 重要），
 *   所以逾期的会自然排在最前——「拖着的先做」；
 * - 日程：只取还没结束的（`end > now`），正在进行的那场也算；
 * - 合并：有能力定时刻的（带 due 的待办 + 剩余日程）按时间升序先排，无时刻的待办殿后。
 *
 * 为什么不做「勾选完成」：专注页只读展示 + 跳转，勾选仍然只走首页/计划页那一份写路径，
 * 避免在这里长出第二条改库通道。
 */
fun nextUpItems(bundle: DayBundle, nowMillis: Long, limit: Int = 3): List<NextUpItem> {
    if (limit <= 0) return emptyList()
    val pending = bundle.todos
        .filter { !it.done }
        .sortedWith(
            compareBy<Todo>(
                { if (it.due > 0) 0 else 1 },
                { if (it.due > 0) it.due else 0L },
                { -(it.urgency.coerceAtLeast(-1)) },
                { -(it.importance.coerceAtLeast(-1)) }
            )
        )
        .map { t ->
            NextUpItem(
                id = t.id,
                title = t.title,
                startMillis = t.due,
                badge = when {
                    t.due > 0 -> AppTime.millisToTime(t.due)
                    // 启用哨兵（-1）的判据只许活在 TodoProgress —— 自己在这里写哨兵比较，
                    // 换哨兵值时就会漏改本处（SingleSourceTest 专门盯这件事，本文件曾是违规对象）。
                    TodoProgress.isEnabled(t.percentDone) -> "${t.percentDone}%"
                    else -> "待办"
                },
                isSchedule = false
            )
        }
    val schedules = bundle.schedules
        .filter { it.end > nowMillis }
        .sortedBy { it.start }
        .map { occ ->
            NextUpItem(
                id = occ.schedule.id,
                title = occ.schedule.title,
                startMillis = occ.start,
                badge = AppTime.millisToTime(occ.start),
                isSchedule = true
            )
        }
    val timed = (pending.filter { it.startMillis > 0 } + schedules).sortedBy { it.startMillis }
    val untimed = pending.filter { it.startMillis <= 0 }
    return (timed + untimed).take(limit)
}
