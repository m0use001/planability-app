package io.github.m0use001.planability.data

import io.github.m0use001.planability.core.AppTime
import io.github.m0use001.planability.core.toLocalDate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

/** 一次日程出现（重复展开后） */
data class ScheduleOcc(
    val schedule: Schedule,
    val start: Long,
    val end: Long
)

/** 打卡监督统计 */
data class SupervisionStat(
    val checkedToday: Boolean,
    val streak: Int,
    val total: Int,
    val checkedDays: Set<Long>
)

/** 打卡监督总览行（数据中心 + 计划页完成度） */
data class SupervisionOverview(
    val todoId: Long,
    val title: String,
    val checkedToday: Boolean,
    val streak: Int,
    val totalChecked: Int,
    val expectedDays: Int,
    /** v23：按「迟一天扣 25%」加权后的完成率，不是「打过几天 / 应几天」 */
    val rate: Int,
    /** 未完成天数（已过期判定用） */
    val missedDays: Int = 0,
    /** 补交天数 */
    val lateDays: Int = 0
)

/**
 * v23：一条打卡数据趋势（数据中心画折线用）。
 *
 * [points] 是 (dayKey, 数值) 且按日期升序。time 型的「数值」是当天分钟数（见 plottableValue）。
 */
data class CheckinSeries(
    val todoId: Long,
    val title: String,
    val label: String,
    val kind: String,
    val unit: String,
    val points: List<Pair<Long, Double>>
)

/** 首页某日数据包 */
data class DayBundle(    val date: LocalDate,
    val schedules: List<ScheduleOcc>,
    val todos: List<Todo>,
    val hourTasks: List<HourTask>,
    val events: List<TimelineEvent>,
    val checks: List<SupervisionCheck>,
    val doneCount: Int
) {
    fun checkedOf(todoId: Long, dayKey: Long): Boolean =
        checks.any { it.todoId == todoId && it.date == dayKey }
}

/** 监督账本反推「应完成日」的最大回溯天数（防止老待办一口气造出几百天欠账） */
private const val MAX_LEDGER_LOOKBACK_DAYS = 90L

/**
 * 「漏勾即消失」的补口（2026-11-03）：单行滚期模型的 date 只在勾选时前进，某天没勾
 * 之后的每天都从今日视图消失。本函数把一条**过期未完成**的重复待办换算成 [day] 视图
 * 的显示副本（date/due/endAt 挪到那天、done=false），仅当——
 * 它不在当日列表（id 不在 [presentIds]）、那天没被 excludedDates 例外、
 * 且按重复规则**那天命中相位**（Recurrence.parseTodoRule + occurrences 统一判）。
 * 勾这个副本走 completeTodo 的普通滚期路径（副本 date=那天），单行模型不变。
 * 返回 null = 那天不显示。
 */
internal fun overdueDayView(
    t: Todo,
    day: LocalDate,
    dayKey: Long,
    presentIds: Set<Long>,
    excludedDates: Set<Long>
): Todo? {
    if (t.id in presentIds) return null
    if (dayKey in excludedDates) return null
    if (t.done || t.trashed || t.date <= 0 || t.date >= dayKey) return null
    if (!t.permanent && t.repeatEnd in 1 until dayKey) return null
    val rec = Recurrence.parseTodoRule(t)
    if (!rec.repeats) return null
    val anchor = t.date.toLocalDate()
    if (rec.occurrences(anchor, day, day).isEmpty()) return null
    // due/endAt 只搬「一天内的时刻」，日期换成本视图那天（dayView 同法）
    val timeOf = { ms: Long ->
        java.time.Instant.ofEpochMilli(ms).atZone(AppTime.zone).toLocalTime()
    }
    val dur = if (t.endAt > 0 && t.due > 0) t.endAt - t.due else 0L
    val newDue = t.due.takeIf { it > 0 }
        ?.let { day.atTime(timeOf(it)).atZone(AppTime.zone).toInstant().toEpochMilli() } ?: 0L
    return t.copy(
        date = dayKey,
        due = newDue,
        endAt = if (newDue > 0 && dur > 0) newDue + dur else 0L
    )
}

/**
 * 一条完成流水还原成「那一天」的**已勾选显示副本**：日期/时段挪到流水当天，done=true。
 *
 * 2026-11-07 用户「背单词待办无法删除」根因就在这：删除只是给行打 trashed=1，
 * 完成流水仍在；三个还原点（loadDay / todosOfDay / doneOccurrences）原来不看 trashed，
 * 已删除的待办照样被流水还原成副本 → 它「删了又回来」，而长按删除删的还是那条
 * 已删的同一行 = 纯空操作。所以**已删除的行一律不还原**（此函数对 trashed 直接返回 null）。
 *
 * 调用方仍然要用 `?.let {}`：这里的 null 就是「这条流水不该出现在视图里」。
 */
internal fun completedDayView(live: Todo, log: TodoDoneLog): Todo? {
    if (live.trashed) return null
    val day = log.date.toLocalDate()
    val dur = if (live.endAt > 0 && live.due > 0) live.endAt - live.due else 0L
    val newDue = if (live.due > 0) {
        val time = java.time.Instant.ofEpochMilli(live.due).atZone(AppTime.zone).toLocalTime()
        day.atTime(time).atZone(AppTime.zone).toInstant().toEpochMilli()
    } else 0L
    return live.copy(
        date = log.date,
        done = true,
        doneAt = log.at,
        due = newDue,
        endAt = if (newDue > 0 && dur > 0) newDue + dur else 0L
    )
}

/**
 * 完成一份实例后的**滚期目标日**（2026-11-04 用户「切到昨天点循环待办，出现一模一样的待办」）。
 *
 * 旧行为 `nextAfter(anchor, today)` 有一个洞：补**过去某天**的欠账副本时（completedOn < today），
 * 滚期从「今天之后」起算 → 直接跳到明天，**把今天的实例吞掉**；且完成流水无幂等，重复点按
 * 会在同一天堆叠 N 条一模一样的流水（真机取证：背单词 date 滚到 11-05、11-03 有 4 条
 * 一模一样的 doneLog，视图上就是一排复制体）。
 *
 * 现在按「被完成的那份实例」起算（completedOn），再夹一刀**永不早于今天**：
 *  · 补昨天的账 → 今天（今天的实例仍然待办）；
 *  · 完成今天的 → 明天（原语义不变）；
 *  · 补三天前的账（中间的欠账按单行模型折叠）→ 今天；
 *  · 例外表跳过，但跳完仍夹 ≥今天（今天若被例外掉则顺延到下一个非例外日）。
 * 周内例外：夹到今天可能落在规则允许的星期之外（如「每周一」夹到周四）——单行滚期模型
 * 下这是有意的：行必须落在可行动的日子，下次完成会再滚回下一个合法日。
 *
 * 2026-11-04 第二次修：**锚点不得晚于完成日**。此前锚点用行日期，而重复待办的行在每次
 * 勾选后已经滚到下一期；补昨天的账时拿"下一期的行"当锚点 → `nextAfter` 从未来起算，
 * 又跳一期 → 行直接进入未来（背单词 date 一上午被推到 11-06）。未来日期既不满足
 * `byDate(今天)` 也不满足 `overdueRepeating(date<今天)`，首页/计划页/未设置时间胶囊
 * 全部看不到，而且自己永远滚不回来。锚点被推前时按完成日对齐（相位仍交给 [Recurrence]）。
 * 返回 null = 超出重复期限。
 */
internal fun nextRollTarget(
    rec: Recurrence,
    anchor: LocalDate,
    completedOn: LocalDate,
    today: LocalDate,
    excludedDayKeys: Set<Long>
): LocalDate? {
    // 系列已结束（repeatEnd < today 且非永久）：直接 null，别让「夹到今天」把过期系列复活
    val until = rec.untilDayKey.takeIf { it > 0 }?.let { java.time.Instant.ofEpochMilli(it).atZone(AppTime.zone).toLocalDate() }
    if (until != null && today.isAfter(until)) return null
    // 锚点（行日期）晚于被完成的那天 = 行已被滚过头，不能从未来起算（否则再跳一期）
    val phaseAnchor = if (anchor.isAfter(completedOn)) completedOn else anchor
    var candidate = rec.nextAfter(phaseAnchor, completedOn)
    var guard = 0
    while (candidate != null && guard < 60) {
        if (candidate.isBefore(today)) candidate = today
        if (AppTime.dayKey(candidate) !in excludedDayKeys) return candidate
        candidate = rec.nextAfter(phaseAnchor, candidate)
        guard++
    }
    return null
}

/**
 * 把行日期收回系列范围内（2026-11-19 用户「早已结束的待办突然出现在今天」）。
 *
 * 单行滚期模型里 `date` 代表**当前这一期**。系列有截止（`repeatEnd`）时，`date` 晚于
 * 截止就是非法状态：它不是任何一期，而 `overdueRepeating` / `overdueDayView` 又都以
 * 「系列已结束」为理由把它判死 —— 结果是一条 11/7 就该结束的待办被钉在今天，
 * 既不会滚走也不会消失，天天占着今日视图（用户实测）。
 *
 * 三种写法都能造出这种行：编辑器保存时按「今天」落了 date、长按改期挪到今天、
 * 撤销完成时把「完成流水那天」的显示副本原样写回行。与其在每处各堵一遍（迟早漏一处），
 * 在这里统一收口：`date` 压回 `repeatEnd`，`due`/`endAt` 按同一位移平移
 * （时刻与时长不变，日粒度位移不会引入跨天误差），其余列一个不动。
 *
 * 永久（`permanent`）与无截止（`repeatEnd<=0`）的待办不受影响 —— 没有"系列之外"这回事。
 */
internal fun clampIntoSeries(t: Todo): Todo {
    val end = t.repeatEnd
    if (t.permanent || end <= 0 || t.date <= 0 || t.date <= end) return t
    val shift = end - t.date
    return t.copy(
        date = end,
        due = if (t.due > 0) t.due + shift else 0L,
        endAt = if (t.endAt > 0) t.endAt + shift else 0L
    )
}

class PlanRepo(
    private val db: AppDatabase,
    private val version: MutableVersion,
    private val scope: CoroutineScope
) {
    private val scheduleDao = db.scheduleDao()
    private val todoDao = db.todoDao()
    private val supervisionDao = db.supervisionDao()
    private val checkinDao = db.checkinDao()
    private val percentDao = db.percentLogDao()
    private val doneLogDao = db.doneLogDao()
    private val hourTaskDao = db.hourTaskDao()
    private val eventDao = db.timelineEventDao()
    private val countdownDao = db.countdownDao()
    private val categoryDao = db.categoryDao()
    private val labelColorDao = db.labelColorDao()

    private fun changed() {
        version.bump()
        scope.launch { Graph.achievements.checkAll() }
    }

    // ---------- 日程 ----------

    /** v21：所有日程/待办写入都在这里盖时间戳。
     *  新建（id=0）：createdAt = updatedAt = now；
     *  已有：createdAt 保留原值（旧数据为 0 时补成 now），updatedAt = now。
     *  dump v1 的合并靠这两个字段判新旧，少盖一次就等于「这次改动不存在」。 */
    private fun Schedule.stamped(now: Long): Schedule =
        if (id == 0L) copy(createdAt = now, updatedAt = now)
        else copy(createdAt = if (createdAt > 0) createdAt else now, updatedAt = now)

    private fun Todo.stamped(now: Long): Todo =
        if (id == 0L) copy(createdAt = now, updatedAt = now)
        else copy(createdAt = if (createdAt > 0) createdAt else now, updatedAt = now)

    suspend fun saveSchedule(item: Schedule): Long {
        val isNew = item.id == 0L
        // 2026-10-21：日程**不再与项目挂钩**。事故复盘：课表导入把 13 条课程挂在了
        // 一个（测试用）项目下，用户删项目时整张课表跟着没了。
        // 日历数据是用户自己的，不该由项目容器决定生死——这里统一写 projectId = 0。
        val id = scheduleDao.upsert(item.copy(projectId = 0).stamped(AppTime.nowMillis()))
        // 绑定地名优先（placePoi = 地图 POI 名），没有才退回显示地名
        ensurePlaceCategory(item.placePoi.ifBlank { item.place }, item.lat, item.lng)
        if (isNew) runCatching { XpCenter.add(2) }
        changed()
        return id
    }

    /**
     * 把用到的地点登记进「分类管理 → 地点」（kind=1）。
     *
     * 2026-10-21 用户反馈：在编辑器里新选了「华工国际校区未来技术学院」，
     * 分类管理里却没有这个地点——因为地点只写进了日程的 place 字段，
     * 从没回写到 categories。这里保存日程/待办时顺手补登记，
     * 已有同名条目则只在它缺坐标时补坐标（不覆盖用户改过的名字/颜色）。
     */
    private suspend fun ensurePlaceCategory(place: String, lat: Double, lng: Double) {
        val name = place.trim()
        if (name.isEmpty()) return
        // 导入器/编辑器里的占位文案不是真地点，别登记成分类
        if (name in setOf("未排地点", "未设置地点", "未安排", "-", "—")) return
        runCatching {
            val existing = categoryDao.byKind(1).firstOrNull { it.name == name }
            when {
                existing == null ->
                    categoryDao.upsert(CategoryEntity(name = name, kind = 1, lat = lat, lng = lng))
                existing.lat == 0.0 && existing.lng == 0.0 && (lat != 0.0 || lng != 0.0) ->
                    categoryDao.upsert(existing.copy(lat = lat, lng = lng))
            }
        }
    }

    suspend fun deleteSchedule(id: Long) {
        scheduleDao.trash(id, AppTime.nowMillis())
        changed()
    }

    suspend fun scheduleById(id: Long): Schedule? = scheduleDao.byId(id)
    suspend fun trashedSchedules(): List<Schedule> = scheduleDao.trashed()
    suspend fun restoreSchedule(id: Long) {
        scheduleDao.restore(id, AppTime.nowMillis())
        changed()
    }

    suspend fun purgeSchedule(id: Long) {
        scheduleDao.delete(id)
        changed()
    }

    suspend fun emptyScheduleTrash() {
        scheduleDao.emptyTrash()
        changed()
    }

    /** 展开重复日程到日期区间（含端点）：统一 Recurrence 引擎 + 例外日跳过 */
    suspend fun scheduleOccurrences(from: LocalDate, to: LocalDate): List<ScheduleOcc> {
        val zone = AppTime.zone
        val rangeStart = from.atStartOfDay(zone).toInstant().toEpochMilli()
        val rangeEnd = to.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1
        val all = scheduleDao.activeUntil(rangeEnd)
        val result = mutableListOf<ScheduleOcc>()
        for (s in all) {
            val excluded = s.excludedDates.split(",").mapNotNull { it.trim().toLongOrNull() }.toSet()
            val startDt = java.time.Instant.ofEpochMilli(s.start).atZone(zone).toLocalDateTime()
            val dur = (s.end - s.start).coerceAtLeast(0)
            val anchor = startDt.toLocalDate()
            val rec = Recurrence.parseScheduleRule(s.repeatRule)
            val dates = if (rec.repeats) {
                rec.occurrences(anchor, from, to).filter { AppTime.dayKey(it) !in excluded }
            } else {
                listOf(anchor).filter {
                    val occStart = it.atTime(startDt.hour, startDt.minute).atZone(zone).toInstant().toEpochMilli()
                    occStart in rangeStart..rangeEnd
                }
            }
            dates.forEach { d ->
                val occ = d.atTime(startDt.hour, startDt.minute).atZone(zone).toInstant().toEpochMilli()
                if (occ >= s.start) result += ScheduleOcc(s, occ, occ + dur)
            }
        }
        return result.sortedBy { it.start }
    }

    /** 重复日程「仅修改今天」：例外日 + 生成一次性副本，返回副本 id */
    suspend fun excludeScheduleForToday(s: Schedule, date: LocalDate): Long {
        val dayKey = AppTime.dayKey(date)
        val now = AppTime.nowMillis()
        val marks = s.excludedDates.split(",").mapNotNull { it.trim().toLongOrNull() }.toMutableSet()
        marks.add(dayKey)
        scheduleDao.update(s.copy(excludedDates = marks.joinToString(",") { it.toString() }).stamped(now))
        val newId = scheduleDao.upsert(
            s.copy(id = 0, repeatRule = "", excludedDates = "", trashed = false, trashedAt = 0).stamped(now)
        )
        changed()
        return newId
    }

    /** 重复待办「仅修改今天」：例外日 + 一次性副本 */
    suspend fun excludeTodoForToday(t: Todo, date: LocalDate): Long {
        val dayKey = AppTime.dayKey(date)
        val now = AppTime.nowMillis()
        val marks = t.excludedDates.split(",").mapNotNull { it.trim().toLongOrNull() }.toMutableSet()
        marks.add(dayKey)
        todoDao.update(t.copy(excludedDates = marks.joinToString(",") { it.toString() }).stamped(now))
        val newId = todoDao.upsert(
            t.copy(id = 0, date = dayKey, repeatType = "none", excludedDates = "", done = false, doneAt = 0).stamped(now)
        )
        changed()
        return newId
    }

    // ---------- v54.4-P4：单次与系列分开（发生级操作） ----------

    /**
     * 把行从被挖的那天滚到下一个非例外日；系列没有下一天就保持原样。
     * 滚动口径与 [completeTodo] 一致（保时刻、endAt 随移、百分比归零），目标夹 ≥今天。
     */
    private fun rollTodoOffDay(t: Todo, marks: Set<Long>, date: LocalDate): Todo {
        if (t.date != AppTime.dayKey(date)) return t
        val rec = Recurrence.parseTodoRule(t)
        if (!rec.repeats) return t
        val next = nextRollTarget(rec, date, date, AppTime.today(), marks) ?: return t
        val newDue = if (t.due > 0) {
            val old = java.time.Instant.ofEpochMilli(t.due).atZone(AppTime.zone).toLocalDateTime()
            next.atTime(old.hour, old.minute).atZone(AppTime.zone).toInstant().toEpochMilli()
        } else 0L
        val newEnd = if (t.endAt > 0 && newDue > 0) newDue + (t.endAt - t.due) else 0L
        val rolledPercent = if (TodoProgress.isEnabled(t.percentDone)) 0 else t.percentDone
        return t.copy(date = AppTime.dayKey(next), due = newDue, endAt = newEnd, percentDone = rolledPercent)
    }

    /**
     * 重复待办「仅删这一份」：那天进例外日，行停在那天就滚到下一个非例外日。
     * 不生成副本——那天的这一份从此不存在，系列按自己的节奏继续。
     */
    suspend fun excludeTodoOccurrence(t: Todo, date: LocalDate) {
        val dayKey = AppTime.dayKey(date)
        val now = AppTime.nowMillis()
        val marks = t.excludedDates.split(",").mapNotNull { it.trim().toLongOrNull() }.toMutableSet()
        if (!marks.add(dayKey)) return
        val row = rollTodoOffDay(t, marks, date)
        todoDao.update(row.copy(excludedDates = marks.joinToString(",") { it.toString() }).stamped(now))
        changed()
    }

    /**
     * 重复待办「仅改/仅挪这一份」：例外日 + 行滚走 + 生成那天的**单次副本**（repeatType=none），
     * 返回副本 id——之后对副本的任何编辑都只影响这一份，系列继续自己的节奏。
     * （行不滚走会跟副本同天同屏，出现两行一模一样。）
     */
    suspend fun splitTodoOccurrence(t: Todo, date: LocalDate): Long {
        val dayKey = AppTime.dayKey(date)
        val now = AppTime.nowMillis()
        val marks = t.excludedDates.split(",").mapNotNull { it.trim().toLongOrNull() }.toMutableSet()
        marks.add(dayKey)
        val row = rollTodoOffDay(t, marks, date)
        todoDao.update(row.copy(excludedDates = marks.joinToString(",") { it.toString() }).stamped(now))
        val newId = todoDao.upsert(
            t.copy(id = 0, date = dayKey, repeatType = "none", excludedDates = "", done = false, doneAt = 0).stamped(now)
        )
        changed()
        return newId
    }

    /** 重复日程「仅删这一份」：那天进例外日（日程场次是规则生成的虚例，无需滚行）。 */
    suspend fun excludeScheduleOccurrence(s: Schedule, date: LocalDate) {
        val dayKey = AppTime.dayKey(date)
        val now = AppTime.nowMillis()
        val marks = s.excludedDates.split(",").mapNotNull { it.trim().toLongOrNull() }.toMutableSet()
        if (!marks.add(dayKey)) return
        scheduleDao.update(s.copy(excludedDates = marks.joinToString(",") { it.toString() }).stamped(now))
        changed()
    }

    /**
     * 日程系列「此条及以后都删」：规则截止改到那天之前一天。
     * 锚点已不早于那天 = 场次全在删除范围内，等价整个系列，直接回收；
     * 已过去的场次不受影响（历史保留在「已过去」视图里）。
     */
    suspend fun endScheduleFrom(s: Schedule, date: LocalDate) {
        val rec = Recurrence.parseScheduleRule(s.repeatRule)
        if (!rec.repeats) return
        val anchor = java.time.Instant.ofEpochMilli(s.start).atZone(AppTime.zone).toLocalDate()
        if (!anchor.isBefore(date)) {
            deleteSchedule(s.id)
            return
        }
        val untilKey = AppTime.dayKey(date) - 86_400_000L
        scheduleDao.update(
            s.copy(repeatRule = Recurrence.toScheduleRule(rec.copy(untilDayKey = untilKey))).stamped(AppTime.nowMillis())
        )
        changed()
    }

    // ---------- 待办 ----------

    suspend fun saveTodo(item: Todo): Long {
        // 同日程：待办也不与项目挂钩（2026-10-21 事故后统一解耦）
        // 2026-11-19：**所有保存的唯一闸口**——日期越过系列截止时一律收回截止日
        // （编辑器保存 / 长按改期 / AI 工具 / 待办导入四条路都从这里过，见 clampIntoSeries）。
        val safe = clampIntoSeries(item)
        val id = todoDao.upsert(safe.copy(projectId = 0).stamped(AppTime.nowMillis()))
        ensurePlaceCategory(item.placePoi.ifBlank { item.place }, item.lat, item.lng)
        changed()
        return id
    }

    suspend fun deleteTodo(id: Long) {
        todoDao.trash(id, AppTime.nowMillis())
        changed()
    }

    suspend fun todoById(id: Long): Todo? = todoDao.byId(id)

    /**
     * 百分比进度快捷设置（2026-11-03 用户：首页轻点启用了百分比的待办弹滑条菜单）。
     * 只动 percentDone（0..100），不碰 done——100% 表示「做完了但没打卡」，
     * 勾选/撤销仍走 completeTodo 的滚期与流水路径。changed() 让首页/组件/计划页刷新。
     *
     * 2026-11-04 用户「切到昨天填完完成率，自动填进今天当打卡了」：进度按**视图那天**落账。
     * [dayKey] 由调用方传所看那天的 dayKey；只有「今天」的进度才写回行的 percentDone
     * （行值代表当前期），看历史天调进度只写那天的流水，不许改写今天。
     */
    suspend fun setPercentDone(id: Long, value: Int, dayKey: Long = AppTime.dayKey(AppTime.today())) {
        val t = todoDao.byId(id) ?: return
        val v = value.coerceIn(0, 100)
        // v48.6：百分比**只改进度、永不碰勾选**（浮层文案本来就是「不改变勾选状态」）。
        // 之前把 100% 耦合到 done 上，造成三联事故：①重复待办打卡后原行已滚到下一期，
        // 在今天的显示副本上改 100% 会把**下一期**标成完成；②撤销打卡后 percent 停在 100，
        // 用户觉得"锁死"；③打卡/撤销的联动全走 completeTodo，百分比不该有第二条完成通道。
        val todayKey = AppTime.dayKey(AppTime.today())
        todoDao.update(
            t.copy(
                percentDone = if (dayKey == todayKey) v else t.percentDone,
                updatedAt = AppTime.nowMillis()
            )
        )
        // v48：每次设置百分比都进流水（同日覆盖）——列表/AI 的百分比曲线靠它
        recordPercent(id, v, dayKey)
        changed()
    }

    // ---------- v48：百分比进度流水 ----------

    /**
     * 记一笔百分比（同一天覆盖，dayKey 缺省=今天）。
     * 语义见 [TodoPercentLog]：循环/长期待办按每个打卡日一条，形成「百分比列表」。
     */
    suspend fun recordPercent(todoId: Long, percent: Int, dayKey: Long = AppTime.dayKey(AppTime.today())) {
        val v = percent.coerceIn(0, 100)
        // replace = 删+插同事务（并发写入时 delete/insert 交错会撞唯一索引，真机闪退过）。
        // 流水是辅助数据：写失败只记日志，不能把百分比保存/打卡带崩。
        runCatching {
            percentDao.replace(
                TodoPercentLog(todoId = todoId, date = dayKey, percent = v, at = AppTime.nowMillis())
            )
        }.onFailure { android.util.Log.w("PlanRepo", "percent log 写失败：${it.message}") }
        changed()
    }

    /** 某待办的百分比流水（新→旧） */
    suspend fun percentsOf(todoId: Long, limit: Int = 60): List<TodoPercentLog> =
        percentDao.ofTodo(todoId, limit)

    /** 撤销打卡时删掉那天的百分比流水（与勾选对称） */
    suspend fun dropPercentOfDay(todoId: Long, dayKey: Long) {
        percentDao.deleteOfDay(todoId, dayKey)
        changed()
    }

    suspend fun activeTodos(): List<Todo> = todoDao.active()
    suspend fun scheduleCount(): Int = scheduleDao.count()
    suspend fun todoCount(): Int = todoDao.count()
    suspend fun todoDoneCount(): Int = todoDao.doneCount()
    /**
     * 「某天」的待办视图（提醒排程 / 今日与多天总览组件 / 编辑器冲突检查用）。
     * 2026-11-03 与 [loadDay] 对齐为同一口径（此前只按 date 精确匹配，同为
     * 「漏勾即消失」的洞——小组件统计不含欠账、欠账的当天提醒不响）：
     * ①当日列表（excludedDates 例外剔除——此前 todosOfDay 不滤例外，例外日也会提醒）；
     * ②当日完成流水还原的显示副本（今天勾过的滚期件此前计入不了 done）；
     * ③过期未勾重复待办、今天命中相位的显示副本（见 [overdueDayView]）。
     */
    suspend fun todosOfDay(dayKey: Long): List<Todo> {
        val day = dayKey.toLocalDate()
        val excludedOf = { t: Todo -> t.excludedDates.split(",").mapNotNull { it.trim().toLongOrNull() }.toSet() }
        val direct = todoDao.byDate(dayKey).filter { dayKey !in excludedOf(it) }
        val presentIds = direct.map { it.id }.toSet().toMutableSet()
        val rolledViews = doneLogDao.rolledOffBetween(dayKey, dayKey).mapNotNull { log ->
            // 同 loadDay：已删除的行不还原（「背单词无法删除」的幽灵副本源头）
            todoDao.byId(log.todoId)?.let { completedDayView(it, log) }
        }
        rolledViews.forEach { presentIds.add(it.id) }
        val overdue = todoDao.overdueRepeating(dayKey).mapNotNull { t ->
            overdueDayView(t, day, dayKey, presentIds, excludedOf(t))
        }
        return withDayPercent(direct + rolledViews + overdue, dayKey)
    }
    suspend fun trashedTodos(): List<Todo> = todoDao.trashed()
    suspend fun restoreTodo(id: Long) {
        todoDao.restore(id, AppTime.nowMillis())
        changed()
    }

    suspend fun purgeTodo(id: Long) {
        supervisionDao.deleteOfTodo(id)
        checkinDao.deleteOfTodo(id)
        todoDao.delete(id)
        changed()
    }

    suspend fun emptyTodoTrash() {
        todoDao.emptyTrash()
        changed()
    }

    /**
     * 勾选完成：重复待办按周期滚动生成下一期并记录完成流水；
     * 开启打卡监督的同时记一笔打卡。
     */
    suspend fun completeTodo(todo: Todo, onDate: LocalDate): Todo? {
        val now = AppTime.nowMillis()
        val dayKey = AppTime.dayKey(onDate)
        if (todo.done) {
            // 取消勾选 = 撤销那次打卡：按打卡发生的那天删（不是按今天），
            // 否则昨天打的卡今天取消，昨天的连续天数/数据记录会赖着不走。
            val undoDay = if (todo.doneAt > 0)
                java.time.Instant.ofEpochMilli(todo.doneAt).atZone(AppTime.zone).toLocalDate()
            else onDate
            val undoKey = AppTime.dayKey(undoDay)
            if (todo.supervisionEnabled) {
                supervisionDao.deleteCheck(todo.id, undoKey)
            }
            // 2026-10-29：完成流水与勾选对称——不删的话撤销后「已完成」条目还挂着，
            // 再勾一次又插一条，同一天同一件事变成两条流水。
            doneLogDao.deleteOfDay(todo.id, undoKey)
            // 2026-11-14：数据记录与百分比流水**无条件**撤。原来挂在传入快照的
            // dataRecordEnabled / percentDone 上——开关后来被关掉、或快照是旧值时，
            // 那天的记录就赖着不走：浮层「改记录数据」与 AI 在没开数据记录的待办上
            // 仍看得到记录（用户实测「我没开数据记录，却让我记录数据」）。删除幂等。
            checkinDao.deleteOfDay(todo.id, undoKey)
            percentDao.deleteOfDay(todo.id, undoKey)
            // 滚期型待办撤销时，调用方传进来的是「那一天的显示副本」（date=那天、due 按那天算），
            // 这里整体写回 = 原行被拉回那一天并恢复未完成 —— 正是"撤销滚期"。
            // 2026-11-14：行上百分比一并**清空**（TodoProgress.undoRollbackPercent）——
            // 用户定稿「撤销打卡后百分比与胶囊都要清空」：撤销后留个 80% 的填充分不清
            // 是撤销成功还是没生效（时间轴暗色没了、胶囊却还填着）。未启用（-1）原样。
            todoDao.update(
                // 2026-11-19：写回前必须收回系列内 —— 撤销一条「系列之外那天」的完成
                // （今天的显示副本、已结束系列今天补的卡）会把行钉在今天，见 clampIntoSeries。
                clampIntoSeries(
                    todo.copy(
                        done = false, doneAt = 0,
                        percentDone = TodoProgress.undoRollbackPercent(todo.percentDone)
                    )
                ).stamped(now)
            )
            changed()
            return todoDao.byId(todo.id)
        }
        // 2026-11-04 幂等（用户「点一下出现一个一模一样的待办」）：同 (todoId, dayKey)
        // 只留一条完成流水——欠账副本（overdueDayView）完成时会再走一遍这条路径，
        // 重复点按/多端重放会在同一天堆叠 N 条一模一样的流水，视图上就是一排复制体。
        doneLogDao.deleteOfDay(todo.id, dayKey)
        doneLogDao.insert(TodoDoneLog(todoId = todo.id, title = todo.title, date = dayKey, at = now))
        // v48：**每个打卡日记录当天百分比**（循环/长期待办的核心诉求）——
        // 启用百分比的待办打卡时把当天值落流水（同一天覆盖）；未启用（-1）不记。
        // replace = 事务内删+插：与 setPercentDone 并发（浮层"打卡并记录"）也不会撞唯一索引。
        // 兜底 runCatching：流水是辅助数据，写失败也不能把打卡带崩（曾因此闪退）。
        if (TodoProgress.isEnabled(todo.percentDone)) {
            runCatching {
                percentDao.replace(TodoPercentLog(todoId = todo.id, date = dayKey, percent = todo.percentDone, at = now))
            }
        }
        runCatching { XpCenter.add(3) }
        if (todo.supervisionEnabled) {
            val exists = supervisionDao.ofTodo(todo.id).any { it.date == dayKey }
            if (!exists) supervisionDao.insert(SupervisionCheck(todoId = todo.id, date = dayKey, at = now))
            // v22：零输入格式（time）在这里直接落库，不等 UI 弹框；
            // 需手填的格式由 UI 录完调 addCheckin（判据 CheckinKind.needsInput）
            if (todo.dataRecordEnabled && !CheckinKind.needsInput(todo.dataRecordKind)) {
                checkinDao.deleteOfDay(todo.id, dayKey)
                checkinDao.insert(CheckinFormat.blank(todo, dayKey, now))
            }
        }
        val next: Todo? = if (todo.repeatType != "none") {
            val nextDate = nextOccurrence(todo, onDate) ?: run {
                // 超出重复期限：标记完成。
                // 2026-11-19：这里的 todo 可能是「今天的显示副本」（date=今天），而系列已结束
                // —— 原样写回就把截止 11/7 的行钉在 11/19，此后谁也滚不动它（见 clampIntoSeries）。
                todoDao.update(clampIntoSeries(todo.copy(done = true, doneAt = now)).stamped(now))
                null
            }
            if (nextDate != null) {
                val newDue = if (todo.due > 0) {
                    val oldDue = java.time.Instant.ofEpochMilli(todo.due).atZone(AppTime.zone).toLocalDateTime()
                    nextDate.atTime(oldDue.hour, oldDue.minute).atZone(AppTime.zone).toInstant().toEpochMilli()
                } else 0
                // 时间段滚动：保持时长不变（endAt = 新开始 + 原时长）
                val newEndAt = if (todo.endAt > 0 && newDue > 0) newDue + (todo.endAt - todo.due) else 0
                // 2026-11-04：新一期从 0% 起（启用百分比时）。此前把上一期的 65% 带进明天，
                // 明天的胶囊一睁眼就是「65%」——进度是每个打卡日各自的事实。
                // 启停状态（-1/≥0）保留，只把值归零。
                val rolledPercent = if (TodoProgress.isEnabled(todo.percentDone)) 0 else todo.percentDone
                val rolled = todo.copy(
                    date = AppTime.dayKey(nextDate), due = newDue, endAt = newEndAt,
                    done = false, doneAt = 0, percentDone = rolledPercent
                )
                todoDao.update(rolled.stamped(now))
                rolled
            } else null
        } else {
            todoDao.update(clampIntoSeries(todo.copy(done = true, doneAt = now)).stamped(now))
            todoDao.byId(todo.id)
        }
        changed()
        return next
    }

    /**
     * 下一次重复出现日（统一 Recurrence 引擎，跳过例外日）；null 表示已超出期限。
     *
     * [completedOn] = 被完成的那份**实例**的日期（补昨天的账就是昨天），不是行日期：
     * 行在每次勾选后已经滚到下一期，拿它当"从哪天之后"会再跳一期（见 [nextRollTarget]）。
     * 锚点（相位参照）仍取行日期，`nextRollTarget` 内部会把它夹到不晚于完成日。
     */
    private fun nextOccurrence(todo: Todo, completedOn: LocalDate): LocalDate? {
        val today = AppTime.today()
        val excluded = todo.excludedDates.split(",").mapNotNull { it.trim().toLongOrNull() }.toSet()
        val anchor = if (todo.date > 0) todo.date.toLocalDate() else completedOn
        val rec = Recurrence.parseTodoRule(todo)
        if (!rec.repeats) return null
        return nextRollTarget(rec, anchor, completedOn, today, excluded)
    }

    /** 打卡监督统计（连续天数等） */
    /** v54.4 打卡面板用：所有开监督的待办（DAO 本有此查询，补一个仓库包装） */
    suspend fun supervisionTodos(): List<Todo> = todoDao.supervisionTodos()

    suspend fun supervisionStat(todoId: Long): SupervisionStat {
        val checks = supervisionDao.ofTodo(todoId)
        val days = checks.map { it.date }.toSortedSet()
        val today = AppTime.dayKey(AppTime.today())
        val checkedToday = days.contains(today)
        var streak = 0
        var cursor = if (checkedToday) AppTime.today() else AppTime.today().minusDays(1)
        while (days.contains(AppTime.dayKey(cursor))) {
            streak++
            cursor = cursor.minusDays(1)
        }
        return SupervisionStat(checkedToday, streak, days.size, days.toSet())
    }

    /**
     * 「撤销某天」的唯一入口（2026-11-14 统一，原三处 UI 各自 removeCheckin + toggleCheck 已废）。
     *
     * 旧写法有三个洞（打卡面板 / 计划页已过期 / 编辑器补交弹窗同犯）：
     * ①`toggleCheck` 是**切换**——那天没有监督卡时「撤销」反而打出一张卡（撤销变打卡）；
     * ②计划页那处连监督开关都不判，没开监督的待办也会被写/删卡；
     * ③三处都不碰 done 与完成流水——那天真的勾过（completeTodo 打的卡），撤销后
     *   时间轴那块仍暗着、胶囊仍有勾、完成率仍算完成（用户视角「撤销没生效」）。
     *
     * 现在统一：该天有完成流水 → 还原那天的显示副本走 [completeTodo] 撤销分支
     * （done/完成流水/监督卡/数据记录/百分比流水一体撤）；纯补交卡（没勾过）→ 只撤
     * 监督卡与数据记录——百分比流水不是补交写的，不连带删。
     */
    suspend fun undoCheckinDay(todoId: Long, date: LocalDate) {
        val dayKey = AppTime.dayKey(date)
        val t = todoDao.byId(todoId) ?: return
        val log = doneLogDao.byDate(dayKey).firstOrNull { it.todoId == todoId }
        if (log != null && !t.trashed) {
            completedDayView(t, log)?.let { completeTodo(it, date) }
            return
        }
        supervisionDao.deleteCheck(todoId, dayKey)
        checkinDao.deleteOfDay(todoId, dayKey)
        changed()
    }

    // ---------- v23：账本（缺卡 / 补交 / 加权完成率） ----------

    /**
     * 统计区间：起点取「首次打卡日」与「待办开始日」的较早者，终点取今天与重复截止的较早者。
     *
     * 为什么终点要卡重复截止：已经到期的重复待办不该再往后累计缺卡，
     * 否则一个 7 月截止的待办在 10 月会显示"欠了 90 天"，全是废话。
     */
    private fun ledgerRange(todo: Todo, firstCheck: Long?): Pair<LocalDate, LocalDate> {
        val today = AppTime.today()
        val start = minOf(
            firstCheck ?: Long.MAX_VALUE,
            if (todo.date > 0) todo.date else Long.MAX_VALUE
        ).let { if (it == Long.MAX_VALUE) today else it.toLocalDate() }
        val end = if (todo.repeatEnd > 0 && !todo.permanent) {
            minOf(today, todo.repeatEnd.toLocalDate())
        } else today
        return start to maxOf(start, end)
    }

    /**
     * 逐日账本。常驻或每天重复的监督待办逐日列全；其他重复频率按**重复规则反推应完成日**
     * （2026-10-29 改：原来只列"有记录的那几天"，weekly 这类永远造不出 MISSED，
     * 「过期 N」对它们恒为 0 —— 用户实测就是"过期机制没用"）。
     *
     * 反推口径：`Recurrence.occurrencesSince` 相位对齐往回枚举，起点取
     * max(今天−90 天, 创建日, 首次打卡日) —— 不早于待办存在之前、也不无限往前造欠账；
     * 截止到 `repeatEnd`（规则自身 until）。今天没打且是应完成日 → PENDING 不算欠账
     * （与 [CheckinLedger.build] 同口径）。
     */
    suspend fun checkinLedger(todo: Todo): List<CheckinDay> {
        val checks = supervisionDao.ofTodo(todo.id)
        if (checks.isEmpty() && todo.date <= 0) return emptyList()
        val checkMap = checks.associate { it.date to it.at }
        val recMap = checkinDao.ofTodo(todo.id).associateBy { it.date }
        val (start, end) = ledgerRange(todo, checks.minOfOrNull { it.date })
        val daily = todo.permanent || todo.repeatType == "daily"
        if (daily) {
            return CheckinLedger.build(start, end, checkMap, recMap, AppTime.zone)
        }
        val expected = expectedDays(todo, checks)
        // 各行：应完成日 ∪ 实际有记录的日子
        val keys = (checkMap.keys + recMap.keys + expected).toSortedSet()
        val todayKey = AppTime.dayKey(AppTime.today())
        return keys.toList().asReversed().map { key ->
            val at = checkMap[key]
            val delay = at?.let { CheckinLedger.delayDays(key, it, AppTime.zone) } ?: 0
            CheckinDay(
                date = key,
                dayNo = java.time.temporal.ChronoUnit.DAYS.between(start, key.toLocalDate()).toInt() + 1,
                state = when {
                    // 今天还没过完，不算欠
                    at == null && key == todayKey -> CheckinDay.PENDING
                    at == null -> CheckinDay.MISSED
                    delay > 0 -> CheckinDay.LATE
                    else -> CheckinDay.DONE
                },
                delayDays = delay,
                credit = if (at == null) 0 else CheckinLedger.credit(delay),
                record = recMap[key]
            )
        }
    }

    /** 非逐日重复待办的应完成日集合 —— 口径在 [CheckinLedger.expectedDays]（纯函数、有单测） */
    private fun expectedDays(todo: Todo, checks: List<SupervisionCheck>): Set<Long> =
        CheckinLedger.expectedDays(todo, checks, AppTime.today(), MAX_LEDGER_LOOKBACK_DAYS)

    /** 批量取账本（已过期列表用），按缺卡数倒序 */
    suspend fun checkinLedgers(todos: List<Todo>): List<Pair<Todo, List<CheckinDay>>> =
        todos.mapNotNull { t -> checkinLedger(t).takeIf { it.isNotEmpty() }?.let { t to it } }

    /** 补交某一天：打卡 + 可选载荷。at 一律记实际提交时刻 → 延迟天数自动算出来 */
    suspend fun backfillCheck(
        todoId: Long,
        date: LocalDate,
        record: CheckinRecord? = null,
        at: Long = AppTime.nowMillis()
    ) {
        val dayKey = AppTime.dayKey(date)
        supervisionDao.deleteCheck(todoId, dayKey)
        supervisionDao.insert(SupervisionCheck(todoId = todoId, date = dayKey, at = at))
        record?.let {
            checkinDao.deleteOfDay(todoId, dayKey)
            checkinDao.insert(it.copy(todoId = todoId, date = dayKey, at = at))
        }
        changed()
    }

    /** 某天已有记录（补交预填用） */
    suspend fun checkinOn(todoId: Long, date: LocalDate): CheckinRecord? =
        checkinDao.ofTodo(todoId).firstOrNull { it.date == AppTime.dayKey(date) }

    /**
     * 数据中心趋势用：每个开了数据记录的待办 → 一串 (dayKey, 值)。
     *
     * 只给能画成折线的格式（时间/数值/计数）；短文本和多选没有"量纲"，
     * 强行折线只会画出一堆噪声，那两类仍在编辑器/已过期里看原文。
     */
    suspend fun checkinSeries(): List<CheckinSeries> {
        val ids = checkinDao.todoIds()
        if (ids.isEmpty()) return emptyList()
        return ids.mapNotNull { id ->
            val t = todoDao.byId(id) ?: return@mapNotNull null
            if (t.trashed || !t.dataRecordEnabled) return@mapNotNull null
            val rs = CheckinFormat.sortForDisplay(checkinDao.ofTodo(id))
            val kind = t.dataRecordKind.ifBlank { CheckinKind.TIME }
            if (!isPlottable(kind)) return@mapNotNull null
            val pts = rs.mapNotNull { r ->
                val v = plottableValue(r, kind) ?: return@mapNotNull null
                r.date to v
            }.sortedBy { it.first }
            if (pts.size < 2) return@mapNotNull null   // 一个点画不出趋势
            CheckinSeries(
                todoId = id,
                title = t.title,
                label = t.dataRecordLabel,
                kind = kind,
                unit = t.dataRecordUnit,
                points = pts
            )
        }.sortedBy { it.title }
    }

    /** 能画折线的格式 */
    private fun isPlottable(kind: String): Boolean =
        kind == CheckinKind.TIME || kind == CheckinKind.NUMBER || kind == CheckinKind.COUNT

    /**
     * 折线用的数值。
     * time 型换算成「当天分钟数」—— 否则 08:00 和 20:00 之间没有可比的轴，
     * 而且跨天后的小时数会自己往回跳（23:59 → 00:01），折线会画出一根尖刺。
     */
    private fun plottableValue(r: CheckinRecord, kind: String): Double? = when (kind) {
        CheckinKind.TIME -> {
            val at = r.at
            if (at <= 0) null else {
                val z = java.time.Instant.ofEpochMilli(at).atZone(AppTime.zone).toLocalTime()
                z.hour * 60 + z.minute + z.second / 60.0
            }
        }
        CheckinKind.NUMBER, CheckinKind.COUNT -> r.numValue
        else -> null
    }

    // ---------- v22：打卡数据记录 ----------

    suspend fun checkinsOf(todoId: Long): List<CheckinRecord> =
        CheckinFormat.sortForDisplay(checkinDao.ofTodo(todoId))

    /** 一天一条：先删同天再插。否则重复打卡（改完数据再保存一次）会叠出两行，统计就重了。 */
    suspend fun addCheckin(record: CheckinRecord) {
        checkinDao.deleteOfDay(record.todoId, record.date)
        checkinDao.insert(record)
        changed()
    }

    suspend fun removeCheckin(todoId: Long, dayKey: Long) {
        checkinDao.deleteOfDay(todoId, dayKey)
        changed()
    }

    /**
     * 开了数据记录且真有记录的待办 → (待办, 记录)，按记录条数倒序。
     * 给 AI 的 chip 与 data_history 共用同一份取数，避免两处口径漂移。
     */
    suspend fun checkinPairs(limitPerTodo: Int = 14): List<Pair<Todo, List<CheckinRecord>>> {
        val ids = checkinDao.todoIds()
        if (ids.isEmpty()) return emptyList()
        // 待办可能已被删（记录还在），或用户关了数据记录 —— 两种都不该出现在 AI 视野里
        val todos = ids.mapNotNull { todoDao.byId(it) }.filter { !it.trashed && it.dataRecordEnabled }
        if (todos.isEmpty()) return emptyList()
        val byTodo = checkinDao.ofTodos(todos.map { it.id }).groupBy { it.todoId }
        return todos.mapNotNull { t ->
            val rs = byTodo[t.id].orEmpty()
            if (rs.isEmpty()) null else t to CheckinFormat.sortForDisplay(rs.take(limitPerTodo))
        }.sortedByDescending { it.second.size }
    }

    /** 打卡监督总览：所有开启监督的待办的连续/累计/完成度（数据中心 + 计划页用） */
    suspend fun supervisionOverview(): List<SupervisionOverview> {
        val today = AppTime.today()
        return todoDao.supervisionTodos().map { t ->
            val days = supervisionDao.ofTodo(t.id).map { it.date }.toSortedSet()
            val streak = run {
                var s = 0
                var cursor = if (days.contains(AppTime.dayKey(today))) today else today.minusDays(1)
                while (days.contains(AppTime.dayKey(cursor))) {
                    s++
                    cursor = cursor.minusDays(1)
                }
                s
            }
            // 统计起点：首次打卡日 / 待办开始日 的较早者；终点：今天与截止的较早者
            val start = days.firstOrNull()?.toLocalDate()
                ?: (if (t.date > 0) t.date.toLocalDate() else today)
            val end = if (t.repeatEnd > 0 && !t.permanent) {
                minOf(today, t.repeatEnd.toLocalDate())
            } else today
            val expected = ChronoUnit.DAYS.between(start, end).toInt() + 1
            val total = days.size
            // v23：完成率改成「迟一天扣 25 分」的加权分，而不是「打过几天 / 应几天」。
            // 老数据 at=0 按 0 天延迟算，结果与旧口径一致 —— 只有真补交过的才会掉分。
            val ledger = CheckinLedger.build(
                start, end,
                supervisionDao.ofTodo(t.id).associate { it.date to it.at },
                emptyMap(), AppTime.zone
            )
            SupervisionOverview(
                todoId = t.id, title = t.title,
                checkedToday = days.contains(AppTime.dayKey(today)),
                streak = streak, totalChecked = total,
                expectedDays = expected.coerceAtLeast(1),
                rate = CheckinLedger.rate(ledger),
                missedDays = CheckinLedger.missedCount(ledger),
                lateDays = CheckinLedger.lateCount(ledger)
            )
        }.sortedByDescending { it.rate }
    }

    suspend fun doneLogs(from: LocalDate, to: LocalDate): List<TodoDoneLog> =
        doneLogDao.between(AppTime.dayKey(from), AppTime.dayKey(to))

    suspend fun doneCountOn(dayKey: Long): Int = doneLogDao.countOn(dayKey)

    // ---------- 整点任务 / 自由事件 ----------

    suspend fun hourTasksOf(dayKey: Long): List<HourTask> = hourTaskDao.byDate(dayKey)
    suspend fun saveHourTask(item: HourTask) {
        hourTaskDao.upsert(item)
        changed()
    }

    suspend fun toggleHourTask(item: HourTask) {
        hourTaskDao.upsert(item.copy(done = !item.done))
        changed()
    }

    suspend fun deleteHourTask(id: Long) {
        hourTaskDao.delete(id)
        changed()
    }

    suspend fun eventsOf(dayKey: Long): List<TimelineEvent> = eventDao.byDate(dayKey)
    suspend fun saveEvent(item: TimelineEvent) {
        eventDao.upsert(item)
        changed()
    }

    suspend fun deleteEvent(id: Long) {
        eventDao.delete(id)
        changed()
    }

    // ---------- 倒数日 ----------

    /** 按下一次目标日排序（每周几型取本/下周该日） */
    suspend fun countdowns(): List<Countdown> {
        val today = AppTime.today()
        return countdownDao.allOrdered().sortedBy { it.nextDate(today) }
    }
    suspend fun saveCountdown(item: Countdown) {
        countdownDao.upsert(item)
        changed()
    }

    suspend fun deleteCountdown(id: Long) {
        countdownDao.delete(id)
        changed()
    }

    /**
     * 把现有日程/待办里的地点一次性登记进「分类管理 → 地点」。
     *
     * 用于 2026-10-21 之前的存量数据：那时地点只存在 place 字段里，
     * 用户在编辑器里新选的地点不会出现在分类管理。
     */
    suspend fun backfillPlaceCategories() {
        val places = LinkedHashMap<String, Pair<Double, Double>>()
        runCatching {
            scheduleDao.all().filter { !it.trashed && (it.place.isNotBlank() || it.placePoi.isNotBlank()) }.forEach {
                val name = it.placePoi.ifBlank { it.place }.trim()
                if (name.isNotEmpty()) places.putIfAbsent(name, it.lat to it.lng)
            }
            todoDao.all().filter { !it.trashed && (it.place.isNotBlank() || it.placePoi.isNotBlank()) }.forEach {
                val name = it.placePoi.ifBlank { it.place }.trim()
                if (name.isNotEmpty()) places.putIfAbsent(name, it.lat to it.lng)
            }
        }
        places.forEach { (name, ll) -> ensurePlaceCategory(name, ll.first, ll.second) }
        if (places.isNotEmpty()) changed()
    }

    /** 清掉占位文案被误登记成地点分类的条目（"未排地点"这类） */
    suspend fun dropPlaceholderPlaces() {
        val bad = setOf("未排地点", "未设置地点", "未安排", "-", "—")
        runCatching {
            categoryDao.byKind(1).filter { it.name.trim() in bad }.forEach { categoryDao.delete(it.id) }
        }
        changed()
    }

    // ---------- 分类 ----------

    suspend fun categories(kind: Int): List<CategoryEntity> = categoryDao.byKind(kind)
    suspend fun allCategories(): List<CategoryEntity> = categoryDao.all()
    suspend fun saveCategory(item: CategoryEntity) {
        categoryDao.upsert(item)
        changed()
    }

    /**
     * 改某条地点分类的坐标，**只动 lat/lng 两列**（2026-10-29）。
     * 改坐标的入口（分类管理点行重选点 / 新建地点重名合并）一律走这里：
     * 整行 `saveCategory(旧快照.copy(lat, lng))` 会把面板打开期间新加的显示地名覆盖掉
     * （ensureDisplayName 直改 displayNames 列，快照里没有）。
     */
    suspend fun updatePlaceCoords(id: Long, lat: Double, lng: Double) {
        categoryDao.updateCoords(id, lat, lng)
        changed()
    }

    suspend fun deleteCategory(id: Long) {
        categoryDao.delete(id)
        changed()
    }

    // ---------- 地点两级模型（v44：绑定地名 → 多个显示地名） ----------

    /** 绑定地点下挂的显示地名（换行分隔，过滤空行） */
    fun displayNamesOf(c: CategoryEntity): List<String> =
        c.displayNames.split('\n').map { it.trim() }.filter { it.isNotEmpty() }

    /**
     * 把显示地名挂到绑定地名下（按绑定名匹配；绑定不存在就先建一条）。
     * 选点/新建显示名都走这里，保证「一个绑定名 → 多个显示名」只有一处写入口。
     */
    suspend fun ensureDisplayName(bound: String, display: String, lat: Double = 0.0, lng: Double = 0.0) {
        val b = bound.trim()
        val d = display.trim()
        if (b.isEmpty() || d.isEmpty()) return
        val cur = categories(1).firstOrNull { it.name == b }
        if (cur == null) {
            categoryDao.upsert(CategoryEntity(name = b, kind = 1, lat = lat, lng = lng, displayNames = d))
            changed()
            return
        }
        val list = displayNamesOf(cur)
        if (list.contains(d)) return
        categoryDao.upsert(cur.copy(displayNames = (list + d).joinToString("\n")))
        changed()
    }

    /** 从绑定地点下删掉一个显示地名 */
    suspend fun removeDisplayName(categoryId: Long, display: String) {
        val cur = categories(1).firstOrNull { it.id == categoryId } ?: return
        val list = displayNamesOf(cur).filter { it != display }
        categoryDao.upsert(cur.copy(displayNames = list.joinToString("\n")))
        changed()
    }

    /**
     * 显示地名改名（2026-10-25 用户要求）。原位替换保持挂载顺序；
     * 改成已存在的名字视为合并（distinct 去重），不会出现两条同名胶囊。
     */
    suspend fun renameDisplayName(categoryId: Long, from: String, to: String) {
        val old = from.trim()
        val new = to.trim()
        if (old.isEmpty() || new.isEmpty() || old == new) return
        val cur = categories(1).firstOrNull { it.id == categoryId } ?: return
        val list = displayNamesOf(cur).map { if (it == old) new else it }.distinct()
        categoryDao.upsert(cur.copy(displayNames = list.joinToString("\n")))
        changed()
    }

    // ---------- 自由标签颜色（v28） ----------

    /** name → hue。查不到的标签由调用方回退默认色（210f），与"没存过"语义一致 */
    suspend fun labelColors(): Map<String, Float> =
        labelColorDao.all().associate { it.name to it.hue }

    /** 设颜色；[hue] = null 表示恢复默认（删掉记录，而不是存一个"默认色"占位） */
    suspend fun setLabelColor(name: String, hue: Float?) {
        val key = name.trim()
        if (key.isEmpty()) return
        if (hue == null) labelColorDao.delete(key) else labelColorDao.upsert(LabelColor(key, hue))
        changed()
    }

    // ---------- 聚合 ----------

    suspend fun loadDay(date: LocalDate): DayBundle {
        val dayKey = AppTime.dayKey(date)
        val dbTodos = todoDao.byDate(dayKey).filter { t ->
            dayKey !in t.excludedDates.split(",").mapNotNull { it.trim().toLongOrNull() }.toSet()
        }
        // 2026-10-29：重复待办勾完就被滚到下一期，原行离开当日列表 —— 用户实测「今天打完卡
        // 就完全找不到了，连已归档都没有」。这里把当日完成流水里"todoId 已不在当日列表"的行
        // 还原成**显示副本**（done=true、date=那天、due/endAt 按那天的时刻重算）里，
        // 当日视图/已归档就能看见并可撤销（撤销走 completeTodo 的 done 分支写回原行）。
        val rolled = doneLogDao.rolledOffBetween(dayKey, dayKey)
        // 2026-11-07 用户「背单词无法删除」：删除只是给行打 trashed=1，完成流水还在——
        // 这里原来不看 trashed，已删的待办照样被流水还原成显示副本，于是它「删了又回来」，
        // 而且长按删除删的还是同一条已删行 = 纯空操作。只还原**未删除**的行。
        val rolledViews = rolled.mapNotNull { log ->
            todoDao.byId(log.todoId)?.let { completedDayView(it, log) }
        }
        // 2026-11-03 用户实测「四级背单词设了每天，今天首页没有、『未设置时间计划』也没有」：
        // 单行滚期模型的 date 只在**勾选时**前进——某天没勾，date 停在那一天，
        // 之后每天都从今日视图消失（直到 repeatEnd）。补一条漏口：date 已过期、未完成、
        // 仍在重复期内、未被今天 excludedDates 例外、且按重复规则**今天命中相位**的待办，
        // 以「今天的显示副本」（date/due/endAt 挪到今天，done=false）并入当日视图。
        // 勾这个副本与勾今天的正主走同一条 completeTodo 滚期路径（副本 date=今天），
        // 单行滚期模型不变；看历史天（loadDay 过去日期）时同样能看到当时的欠账。
        val dayIds = dbTodos.map { it.id }.toSet()
        val excludedOf = { t: Todo -> t.excludedDates.split(",").mapNotNull { it.trim().toLongOrNull() }.toSet() }
        val overdueViews = todoDao.overdueRepeating(dayKey).mapNotNull { t ->
            overdueDayView(t, date, dayKey, dayIds, excludedOf(t))
        }
        return DayBundle(
            date = date,
            schedules = scheduleOccurrences(date, date),
            todos = withDayPercent(dbTodos + rolledViews + overdueViews, dayKey),
            hourTasks = hourTaskDao.byDate(dayKey),
            events = eventDao.byDate(dayKey),
            checks = supervisionDao.byDate(dayKey),
            // 补位口径：流水里**没能还原成显示副本**的（待办已被删除）才靠 doneCount 计；
            // 已还原的那些已经进了 todos 的 done 计数，再加一遍就是 200%。
            doneCount = rolled.size - rolledViews.size
        )
    }
    /**
     * 已完成、但原行已滚到后一期（不在那些天列表里）的历史实例，按显示副本返回。
     * 计划页「今天/已归档」与首页当天共用；只读展示，不改库。
     */
    suspend fun doneOccurrences(fromDayKey: Long, toDayKey: Long): List<Todo> =
        doneLogDao.rolledOffBetween(fromDayKey, toDayKey).mapNotNull { log ->
            // 同 loadDay：已删除的行不还原（否则删掉的待办在历史视图里「无法删除」）
            todoDao.byId(log.todoId)?.let { completedDayView(it, log) }
        }

    // dayView 已提升为 internal 纯函数 completedDayView（含 trashed 过滤，见其文档）。

    /**
     * 视图里的百分比按**那一天**的流水显示（2026-11-04「切到昨天填完成率记到今天」）。
     *
     * 行上的 percentDone 只代表当前期（今天/下一次）；历史天若拿行值展示，看到的会是
     * 今天的 65%。所以：启用了百分比的待办，取那天流水；没有流水时——今天退回行值
     * （今天=当前期），历史天显示 0（那天什么都没记，不能借今天的值）。
     * 未启用（-1）原样返回，勾选语义不受影响。
     */
    private suspend fun withDayPercent(todos: List<Todo>, dayKey: Long): List<Todo> {
        if (todos.none { TodoProgress.isEnabled(it.percentDone) }) return todos
        val todayKey = AppTime.dayKey(AppTime.today())
        val logs = runCatching { percentDao.ofDay(dayKey).associate { it.todoId to it.percent } }
            .getOrDefault(emptyMap())
        return todos.map { t ->
            if (!TodoProgress.isEnabled(t.percentDone)) t
            else {
                val v = logs[t.id] ?: if (dayKey == todayKey) t.percentDone else 0
                if (v == t.percentDone) t else t.copy(percentDone = v)
            }
        }
    }

    /**
     * 单行滚期**自愈**（2026-11-04「背单词今天看不到」）：旧 bug 会把重复待办的行滚进未来，
     * 而未来日期在两个当日查询（`byDate(今天)` / `overdueRepeating(date<今天)`）里都不存在，
     * 于是这条待办对首页/计划页/组件彻底隐身，直到未来那天到来——自己永远滚不回来。
     *
     * 只修**每日重复**，因为它的合法状态是可判定的：今天没完成 → 行日期=今天；
     * 今天完成了 → 明天。比这更远一定是滚过头。（周/月/隔日重复的未来日期是正常相位，
     * 无法与"用户把系列改到未来开始"区分，这里不动，避免误伤。）
     * 从未完成过（无完成流水）的不动——那可能是用户故意设的未来开始日。
     */
    suspend fun repairRolledPast(today: LocalDate = AppTime.today()) {
        val todayKey = AppTime.dayKey(today)
        val now = AppTime.nowMillis()
        var fixed = 0
        for (t in todoDao.repeatAheadOf(todayKey)) {
            if (t.repeatType != "daily" || t.permanent) continue
            val last = doneLogDao.lastDoneUpTo(t.id, todayKey) ?: continue
            // 今天已完成 → 下一期是明天；否则今天那一期还欠着
            var target = if (last >= todayKey) today.plusDays(1) else today
            val excluded = t.excludedDates.split(",").mapNotNull { it.trim().toLongOrNull() }.toSet()
            var guard = 0
            while (AppTime.dayKey(target) in excluded && guard < 60) {
                target = target.plusDays(1)
                guard++
            }
            val targetKey = AppTime.dayKey(target)
            if (targetKey < t.date) {
                todoDao.update(t.copy(date = targetKey).stamped(now))
                fixed++
            }
        }
        if (fixed > 0) {
            android.util.Log.i("PlanRepo", "repairRolledPast: 拉回 $fixed 条被滚到未来的每日重复待办")
            changed()
        }
    }

    /**
     * 「系列之外的行」自愈（2026-11-19 用户「早已结束的待办突然出现在今天」）。
     *
     * `date > repeatEnd` 是模型外的状态（见 [clampIntoSeries]），存量库里可能已经有这种行
     * ——编辑器保存/改期/撤销完成三处都能造出来。启动时统一收回 `repeatEnd`，
     * 让这些行回到「系列已结束 → 不可见」的正常结局，不必等用户自己发现再手动改期。
     *
     * 只在真的存在这种行时才写库 + bump 版本（正常情况下一次读都没有额外开销）。
     */
    suspend fun repairDeadSeries() {
        val now = AppTime.nowMillis()
        var fixed = 0
        for (t in todoDao.active()) {
            val c = clampIntoSeries(t)
            if (c === t) continue
            todoDao.update(c.stamped(now))
            fixed++
        }
        if (fixed > 0) {
            android.util.Log.i("PlanRepo", "repairDeadSeries: 收回 $fixed 条越过系列截止的待办")
            changed()
        }
    }
}
