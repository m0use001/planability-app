package io.github.m0use001.planability.data

import kotlin.math.roundToInt

/**
 * 待办「进度 / 完成」语义的**唯一裁决处**（v49，2026-11-03）。
 *
 * ## 为什么要有这个文件
 *
 * 2026-11-02/03 一晚连修 8 个 bug，归类后只有 4 类病因，其中两类是：
 *
 * **B 类「同一事实多处定义」**——「完成」这个概念当时有 3 个来源（`done` / `percentDone>=100` /
 * 监督打卡），每条链路（时间轴胶囊、计划页胶囊、完成率、小组件、AI 工具）各自解释一遍。
 * 于是：改 100% 没反应、打卡后锁死 100%、撤销打卡失效、完成率两头不一致——全是从这里长出来的。
 *
 * **D 类「几何写死」**——胶囊填充比例被拿固定 padding 去减，50% 渲染成三成。
 *
 * 两类都无法被当时的测试网住，因为判定**写在 composable 里**，JVM 测试够不到。
 * 本文件把这些判定搬成**纯函数**（无 Compose、无 Android、无 IO），于是可以穷举断言
 * （见 `TodoProgressTest`），并且全仓只允许存在这一份实现（见 `SingleSourceTest`）。
 *
 * 放在 `data/` 而不是 ui：消费方包括仓储层（PlanRepo 决定要不要写百分比流水）
 * 与小组件（widget 取数后自己算权重），两者都不该反向依赖 ui 包。
 *
 * ## 唯一的语义（用户多轮拍板后的终态）
 *
 * 1. **百分比只改进度、永不碰勾选**。`percentDone` 是独立的进度事实，`done` 是独立的打卡事实。
 *    没有「设到 100% 自动打卡」这种第二条完成通道（编辑器保存时的满态自动打卡走
 *    completeTodo 正规链，见 Editors.saveTodo——不是 percentDone 直接改写 done）。
 * 2. **胶囊里两个信号，各画各的**（2026-11-14 三次用户实测后的终态）：
 *    - **填充比例只由 percent 决定**：启用百分比时 `1..99` 永远按比例填充，
 *      哪怕已经打卡——80% 打卡就该看到八成的电池，不能伪装成满格。
 *    - **勾只属于「满」**（[showsCheck]，用户原话「100% 才能打勾」）：启用百分比时
 *      勾 ⟺ `percentDone >= 100`；没开百分比时才用 `done` 当勾。
 *      80% 打卡 = 八成填充、不打勾（已打卡在浮层文案 / 时间轴暗色 / 已归档里可见）。
 *    - 只有「没有可画的进度比例」时才退回整颗实心 + 挖孔勾（[showsSolidChecked]）：
 *      未启用百分比、或百分比为 0。
 *    - 历史（三次都是把一个概念压成另一个）：11-03 定「打卡了也不画勾」→ 用户投诉
 *      打卡后胶囊毫无痕迹；11-14 早改成「打卡一律满格」→ 用户投诉 80% 打卡显示满格；
 *      11-14 定稿：**填充管进度、勾管满**，谁也不再覆盖谁。
 * 3. 未启用百分比（-1）退回旧语义：`done` → 实心带勾，否则空胶囊。
 */
object TodoProgress {

    /** 未启用百分比。与 [Todo.percentDone] 的哨兵值一致（Room 默认值） */
    const val DISABLED = -1

    /** 胶囊为 <100% 的填充留出的比例系数：50%→48%、99%→95%，保证「满」只属于 100% */
    private const val SHORT_OF_FULL_SCALE = 0.96f

    fun isEnabled(percentDone: Int): Boolean = percentDone >= 0

    fun clamp(percentDone: Int): Int = percentDone.coerceIn(0, 100)

    /**
     * 撤销打卡后**行上**百分比的归宿（2026-11-14 用户定稿：**清空**）。
     *
     * 撤销 = 「这一期不算数了」——百分比与胶囊一起清空（归 0，胶囊回到空轮廓），
     * 不留任何"撤销了但还停在 80%"的中间态：那种状态下时间轴暗色没了、胶囊却还填着八成，
     * 用户分不清是撤销成功还是没生效。
     * 原先「半途（0..99）保留」的子规则已废弃（用户实测后明确要求一起清）。
     * 未启用原样返回（负哨兵不是进度值，没有"清空"这回事）。
     */
    fun undoRollbackPercent(percentDone: Int): Int =
        if (isEnabled(percentDone)) 0 else percentDone

    // ---------------------------------------------------------------- 胶囊形态

    /**
     * 胶囊要不要画**勾**。
     *
     * **用户定稿（2026-11-14 第三次报障）：「100% 才能打勾」**——
     * 启用百分比时勾 ⟺ `percentDone >= 100`，打卡（`done`）本身不给勾；
     * 只有没开百分比、没有进度这个概念时，才用 `done` 当勾。
     *
     * 于是同一个待办的两个事实各归各的：
     * - **填充** = 进度（[fillFraction]，只看 percent，打卡不改它）；
     * - **勾** = 「满」（100%），80% 打卡就只显示八成电池、不打勾——
     *   已打卡这件事在浮层文案（「今天已打卡」）、时间轴块的暗色、已归档分组里都有。
     *
     * 编辑器「满态自动打卡」也用这一条（未启用百分比时它恒为 false，不会误触发）。
     */
    fun showsCheck(done: Boolean, percentDone: Int): Boolean =
        if (isEnabled(percentDone)) percentDone >= 100 else done

    /**
     * 胶囊要不要画成**整颗实心 + 挖孔勾**（没有按比例的部分可画时）。
     *
     * 只有「有勾、但没有进度比例」才走这个形态：未启用百分比、或百分比为 0
     * （0% 本来就不画进度轨，见 [showsProgressFill]）。其余情况一律「进度填充 + 勾」，
     * 否则打卡会把 80% 撑成满格（用户 2026-11-14 实测报障）。
     */
    fun showsSolidChecked(done: Boolean, percentDone: Int): Boolean =
        showsCheck(done, percentDone) && !showsProgressFill(percentDone)

    /**
     * 胶囊要不要画「进度填充」（浅色底轨 + 实色）。
     *
     * 0% 不画——用户的定义是「没任何完成就是空胶囊」，0% 属于「没任何完成」，
     * 此时与未启用百分比的空胶囊同形（这是他明确接受的表现）。
     */
    fun showsProgressFill(percentDone: Int): Boolean = isEnabled(percentDone) && percentDone >= 1

    /**
     * 实色填充占胶囊内宽的**比例**（0f..1f）。
     *
     * 铁律：返回值 `== 1f` **当且仅当** 百分比达到 100。
     * 不变量由此保证——任何 <100 的值都乘了 [SHORT_OF_FULL_SCALE]，且 `0.96 < 1`。
     * 这条断言是「胶囊必须在 100% 时显示满、其他任何时候都不能」的可执行形式。
     *
     * 历史教训（v48.7→v48.9）：最初用 `padding(end = 2.5.dp)` 制造右缘空隙，
     * 于是在 20dp 的胶囊里 50% 的宽度又被减掉 2.5dp ≈ 12.5%，渲染出来只有三成。
     * 空隙必须**按比例**留，不能拿绝对值去减。
     */
    fun fillFraction(percentDone: Int): Float {
        val p = clamp(percentDone)
        if (p >= 100) return 1f
        // 0% 不在这里出非零值：进度轨的有无由 [showsProgressFill] 决定（0% 不画）。
        return (p / 100f) * SHORT_OF_FULL_SCALE
    }

    // ---------------------------------------------------------------- 完成率权重

    /**
     * 一条待办对「今日完成率」的贡献（0.0..1.0）。
     *
     * - 已打卡 → 1.0（打卡是权威的「完成了」）
     * - 启用百分比未满 → percent/100
     * - 未启用 / 未开始 → 0.0
     *
     * 注意与 [fillFraction] 的区别：**这是语义权重，不做几何缩短**。
     * 99% 进度的权重就是 0.99，不是 0.9504。混用会让评分与胶囊两个数字对不上
     * （今晚「完成率两头不一致」正是这种混用）。
     */
    fun contribution(done: Boolean, percentDone: Int): Double = when {
        done -> 1.0
        isEnabled(percentDone) -> clamp(percentDone) / 100.0
        else -> 0.0
    }

    /** 「进行中的百分比待办」：启用、未打卡、未到 100（评分详情里数颗数用） */
    fun inProgress(done: Boolean, percentDone: Int): Boolean =
        !done && isEnabled(percentDone) && percentDone < 100

    /**
     * 完整完成率的分子（百分比加权后），已是「件数当量」。
     * 抽出来是为了让首页与小组件共用：此前 `HomeCards.kt` 与 `TodayStatsWidget.kt`
     * 各抄了一份 `if (it.done) 1.0 else if (it.percentDone in 0..99) ... else 0.0`，
     * 正是 B 类病因的活标本（今天没炸，改一处忘一处就炸）。
     */
    fun weightedDone(todos: List<TodoProgressItem>): Double =
        todos.sumOf { contribution(it.done, it.percentDone) }

    /**
     * 完成率，封顶 1.0。`total` 由调用方给（含 hourTasks / 滚期补位），
     * 这里只负责「分子怎么算、怎么封顶」这一个规则。
     */
    fun rate(todos: List<TodoProgressItem>, extraDone: Double, total: Int): Float {
        if (total <= 0) return 0f
        val done = weightedDone(todos) + extraDone
        return (done / total).coerceIn(0.0, 1.0).toFloat()
    }

    /** 评分里的百分比小数显示：`(rate*100)` 四舍五入 */
    fun ratePercent(rate: Float): Int = (rate * 100f).roundToInt().coerceIn(0, 100)
}

/**
 * [TodoProgress] 纯函数接受的**最小投影**。
 *
 * 不直接吃 `data.Todo` 的其余字段：小组件那边拿到的是 `WidgetArt` 自己的数据形状，
 * 跟着 Todo 走会被迫造无用对象。需要 Todo 的调用方一行 `todo.toProgressItem()` 即可。
 */
data class TodoProgressItem(
    val done: Boolean,
    val percentDone: Int = TodoProgress.DISABLED
)

fun Todo.toProgressItem() = TodoProgressItem(done = done, percentDone = percentDone)
