package io.github.m0use001.planability.data

/**
 * v53：待办列表的「进行中 / 已归档」判定（计划页状态筛选）。
 *
 * 为什么单独提出来：2026-11-05 用户「循环的背单词待办，今天看不到」——勾完之后行滚到
 * 明天，今天只剩完成流水还原的**已完成副本**，而「进行中」一律滤掉 done，
 * 于是今天列表里它整个消失（首页未设置时间胶囊也只显示「已完成」）。
 * 对**每天/每周都要做**的习惯，这等于"做完就找不到、想撤销也没入口"。
 *
 * 口径：在**今天**这个范围里，循环待办今天那一份即使已完成也算「进行中」——
 * 它是今天的事，勾了也还属于今天；一次性待办不受影响（勾完就归档，符合预期）。
 */
object TodoListFilters {

    /**
     * @param range 计划页范围："today" / "week" / "month" / "all"
     */
    fun inActiveList(todo: Todo, todayKey: Long, range: String): Boolean {
        if (!todo.done) return true
        // 只有「今天」范围保留当天已完成的循环副本：其他范围里原行/副本按日期自会可见，
        // 再放宽会让「进行中」里堆满历史完成项。
        return range == "today" &&
            todo.repeatType != "none" &&
            todo.date == todayKey
    }
}
