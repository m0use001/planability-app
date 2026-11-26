package io.github.m0use001.planability.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
// M5：原为 io.github.m0use001.planability.core.toLocalDate（app 侧桥），迁 shared 后指向真源。
import io.github.m0use001.planability.shared.toLocalDate

/** 日程（无打卡功能） */
@Entity(tableName = "schedules", indices = [Index("start"), Index("trashed")])
data class Schedule(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    /** epoch millis */
    val start: Long,
    val end: Long,
    val tagId: Long = 0,
    val place: String = "",
    val note: String = "",
    /** "", daily, weekly, biweekly, monthly, yearly */
    val repeatRule: String = "",
    /** 重复日程的"仅今天删除/修改"例外日（逗号分隔 dayKey） */
    val excludedDates: String = "",
    /** 提前提醒分钟数；-1 = 不提醒 */
    val remindMin: Int = 15,
    val trashed: Boolean = false,
    val trashedAt: Long = 0,
    /** 所属项目（0 = 未分类，v5 项目大体系） */
    val projectId: Long = 0,
    /** v11：0 高 / 1 中 / 2 低（未显式设置=1，渲染回退分类色） */
    val priority: Int = 1,
    /** v28：紧急性 0-4（4 最紧急）。**-1 = 未设置（默认）**，用户 2026-10-20 要求可空 */
    val urgency: Int = -1,
    /** v28：重要性 0-4（4 最重要）。**-1 = 未设置（默认）**。priority 由它派生（见 priorityOf/importanceOf） */
    val importance: Int = -1,
    /** v11：地图选点坐标（0 = 未选点） */
    val lat: Double = 0.0,
    val lng: Double = 0.0,
    /**
     * v41（2026-10-21）：**绑定地名** = 地图选中的 POI 名（如"◆大学◆未来技术学院"）。
     *
     * 与 [place]（显示地名，用户可自由写"未来技术学院 B3-302"）分开：
     * 只存一个字段时，要么自由输入把分类管理塞满脏地名，
     * 要么强制显示 POI 名把楼层房间号丢掉。现在绑定名用于"地图上是哪",
     * 显示名用于"用户想怎么称呼它"。
     */
    val placePoi: String = "",
    /** v14：多体系分类标签（逗号分隔，如"工作,重要"） */
    val labels: String = "",
    // v21：真实创建/修改时刻。v19.14 之前日程没有这两列，PhoneDump 只能用可推导的稳定时间占位，
    // 结果 PC 侧的改动会在手机下次导出时被覆盖（mtime + updatedAt 合并必败）。
    val createdAt: Long = 0,
    val updatedAt: Long = 0
)

/** 待办：支持重复频率、常驻、打卡监督 */
@Entity(tableName = "todos", indices = [Index("date"), Index("trashed"), Index("supervisionEnabled")])
data class Todo(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    /** 归属日 dayKey；0 = 未安排日期（只在计划页出现） */
    val date: Long = 0,
    /** 截止/开始时刻 epoch millis（与 endAt 构成时间段） */
    val due: Long = 0,
    /** 结束时刻 epoch millis；0 = 旧数据（视为 due+30 分钟） */
    val endAt: Long = 0,
    /** 0 高 / 1 中 / 2 低 */
    val priority: Int = 1,
    /** v28：紧急性 0-4（4 最紧急）。**-1 = 未设置（默认）**，见 Schedule.urgency */
    val urgency: Int = -1,
    /** v28：重要性 0-4（4 最重要）。**-1 = 未设置（默认）**。priority 由它派生 */
    val importance: Int = -1,
    val tagId: Long = 0,
    val folderId: Long = 0,
    val done: Boolean = false,
    val doneAt: Long = 0,
    /** none / daily / weekly / interval */
    val repeatType: String = "none",
    /** weekly 时启用的星期（1=周一…7=周日），逗号分隔 */
    val repeatDays: String = "",
    /** interval 时的间隔天数 */
    val repeatInterval: Int = 0,
    /** 重复截止日 dayKey；0 = 永久 */
    val repeatEnd: Long = 0,
    /** 常驻：一直存在，不设截止 */
    val permanent: Boolean = false,
    val supervisionEnabled: Boolean = false,
    /** v46：百分比完成。**-1 = 未启用（默认）**；0..99 = 启用且未完成；打卡完成 = 100。
     *  编辑器按 >=0 显示开关态；完成率计算里未完成且启用按 percent/100 加权（见 scoreBreakdown） */
    val percentDone: Int = -1,
    // ---------- v22：打卡数据记录 ----------
    /** 开了打卡监督后，是否记录每次打卡的载荷（否则只记日期，见 SupervisionCheck） */
    val dataRecordEnabled: Boolean = false,
    /** 记录格式，见 [CheckinKind]："" / time / number / count / text / tags */
    val dataRecordKind: String = "",
    /** 字段名/提示语，如「早餐时间」「体重」 */
    val dataRecordLabel: String = "",
    /** 数值型单位，如 kg / ml */
    val dataRecordUnit: String = "",
    /** 多选型的预设选项（逗号分隔） */
    val dataRecordOptions: String = "",
    /** 重复待办的例外日（逗号分隔 dayKey） */
    val excludedDates: String = "",
    val note: String = "",
    /** 提前提醒分钟数；-1 = 不提醒（0 = 准时） */
    val remindMin: Int = 15,
    val trashed: Boolean = false,
    val trashedAt: Long = 0,
    /** 所属项目（0 = 未分类，v5 项目大体系） */
    val projectId: Long = 0,
    /** v11：地点（可经地图选点） */
    val place: String = "",
    /** v11：地图选点坐标（0 = 未选点） */
    val lat: Double = 0.0,
    val lng: Double = 0.0,
    /** v41：绑定地名（地图 POI 名），与 [place] 显示地名分开，见 Schedule.placePoi */
    val placePoi: String = "",
    /** v14：多体系分类标签（逗号分隔，如"工作,重要"） */
    val labels: String = "",
    // v21：同 Schedule，真实创建/修改时刻，理由见上
    val createdAt: Long = 0,
    val updatedAt: Long = 0
)

/** 打卡监督记录（待办 id + 日期 + 实际打卡时刻） */
@Entity(tableName = "supervision_checks", indices = [Index("todoId"), Index("date")])
data class SupervisionCheck(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val todoId: Long,
    /** dayKey */
    val date: Long,
    /**
     * v23：实际打卡时刻 epoch millis。**at 的日期晚于 [date] 即「补交」**，
     * 延迟天数 = at 日期 − date，完成率按它扣分。
     * 老数据是 0（只知道哪天打了、不知道几点），一律当按时处理，不冤枉人。
     */
    val at: Long = 0
)

/**
 * v22：打卡载荷记录 —— 每次打卡一行，kind 决定其余字段的含义。
 *
 * 为什么不把载荷塞进 [SupervisionCheck]：那张表已经在线上，且唯一语义是「这天打了卡」
 * （连续天数/完成率都从它算）。载荷是可选的、格式因待办而异，混进去会让所有统计查询都要面对
 * 一堆用不上的列，老数据的默认值也会变得含义不明。分开之后，没开「数据记录」的待办在
 * checkin_records 里零行，行为与现在完全一致。
 *
 * 各 kind 的取值：
 * - time   只用 [at]（打卡时刻，如「早餐时间 08:12」）
 * - number [numValue] + [unit]
 * - count  [numValue]
 * - text   [textValue]
 * - tags   [tags]（逗号分隔，如「豆浆,油条」）
 *
 * 读历史时**以记录自带的 kind 为准**，不跟随待办当前的设置 —— 用户改了格式，
 * 老记录不能被新格式重新解释成另一种含义。
 */
@Entity(tableName = "checkin_records", indices = [Index("todoId"), Index("date")])
data class CheckinRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val todoId: Long,
    /** dayKey，与 SupervisionCheck.date 同口径，便于两边对齐 */
    val date: Long,
    /** 打卡时刻 epoch millis */
    val at: Long = 0,
    val kind: String = CheckinKind.TIME,
    val numValue: Double = 0.0,
    val unit: String = "",
    val textValue: String = "",
    /** 逗号分隔 */
    val tags: String = ""
)

/** 待办完成流水（完成趋势统计用） */
@Entity(tableName = "todo_done_logs", indices = [Index("date")])
data class TodoDoneLog(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val todoId: Long,
    val title: String,
    /** dayKey */
    val date: Long,
    val at: Long
)

/** 整点任务：绑定某日某小时 */
@Entity(tableName = "hour_tasks", indices = [Index("date")])
data class HourTask(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** dayKey */
    val date: Long,
    /** 0-23 */
    val hour: Int,
    val title: String,
    val done: Boolean = false
)

/** 自由时间轴事件：不落整点格的当日事件 */
@Entity(tableName = "timeline_events", indices = [Index("date")])
data class TimelineEvent(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** dayKey */
    val date: Long,
    /** 当日第几分钟 */
    val startMin: Int,
    val durMin: Int,
    val title: String,
    /** 色相角度，走 SeedTones 派生 */
    val hue: Float = 210f
)

/** 倒数日 / 纪念日 */
@Entity(tableName = "countdowns")
data class Countdown(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    /** 目标日 dayKey（每周几型仅作占位，展示走 weekday 推算） */
    val target: Long,
    val repeatYearly: Boolean = false,
    val note: String = "",
    /** v16：每周几型（DayOfWeek 1..7）；-1 = 非每周型 */
    val weekday: Int = -1,
    /** v17：每月几号型（1..31，大月超出取月末）；-1 = 非每月型 */
    val dayOfMonth: Int = -1
) {
    /** 是否每周几型倒数日 */
    val weekly: Boolean get() = weekday in 1..7

    /** 是否每月几号型倒数日 */
    val monthly: Boolean get() = dayOfMonth in 1..31

    /** 距 today 最近的下一次目标日（固定日 = 自身，周年已推进，每周几/每月几号取下一次出现） */
    fun nextDate(today: java.time.LocalDate): java.time.LocalDate {
        val base = target.toLocalDate()
        return when {
            weekly -> today.plusDays(((weekday - today.dayOfWeek.value + 7) % 7).toLong())
            monthly -> {
                val cand = today.withDayOfMonth(minOf(dayOfMonth, today.lengthOfMonth()))
                if (!cand.isBefore(today)) cand
                else {
                    val nm = today.plusMonths(1)
                    nm.withDayOfMonth(minOf(dayOfMonth, nm.lengthOfMonth()))
                }
            }
            repeatYearly && base.isBefore(today) -> {
                var next = base.withYear(today.year)
                if (next.isBefore(today)) next = next.plusYears(1)
                next
            }
            else -> base
        }
    }

    /** 周几汉字（每周几型用） */
    fun weekdayLabel(): String? =
        if (weekly) listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")[weekday - 1] else null

    /** 类型角标文案：每周五 / 每月5号；固定日返回 null */
    fun kindLabel(): String? = when {
        weekly -> weekdayLabel()
        monthly -> "每月${dayOfMonth}号"
        else -> null
    }
}

/** 分类：标签 / 地点 / 虚拟文件夹 */
@Entity(tableName = "categories", indices = [Index("kind")])
data class CategoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    /** 0 标签 / 1 地点 / 2 文件夹 */
    val kind: Int,
    /** 色相角度（SeedTones 派生标识色） */
    val hue: Float = 210f,
    /**
     * v27：地点分类的经纬度（WGS84，与日程/待办的 lat/lng 同口径）。
     *
     * 只有 kind=1 的地点分类用得上；标签/文件夹恒为 0。给地点分类绑坐标后，
     * 建日程/待办时可以一键带上「在哪」，省得每次重新搜。
     * 默认 0.0 表示未绑坐标——与 schedules/todos 里 lat/lng 的判空口径一致。
     */
    val lat: Double = 0.0,
    val lng: Double = 0.0,
    /**
     * v44：挂在这个绑定地点下的**显示地名**（换行分隔；同一栋楼的不同房间/教室）。
     *
     * 2026-10-25 用户定的两级模型：绑定地名（本行的 name + 坐标，来自地图 POI）是"哪栋楼"，
     * 显示地名是用户自己的叫法（◆-302、3 楼会议室…）。一个绑定地名可挂多个显示地名。
     * 空串 = 还没配过显示名（编辑器里默认用绑定名兜底）。
     */
    val displayNames: String = ""
)

/**
 * v28：自由标签的颜色。
 *
 * 「标签」= Todo.labels / Schedule.labels 里那串逗号分隔的自由文本（`工作,重要`），
 * 它们没有实体表，所以颜色单独存一份 name → hue。
 * 分类（CategoryEntity）自己带 hue，且整套分类体系后续会让位给重要性-紧急性，另开一轮处理。
 *
 * name 用主键：同一个标签词只该有一种颜色，重复写入即覆盖（REPLACE）。
 */
@Entity(tableName = "label_colors")
data class LabelColor(
    @PrimaryKey val name: String,
    /** 色相角度 0..360，与 CategoryEntity.hue 同口径（SeedTones 派生标识色） */
    val hue: Float = 210f
)

/**
 * v28：五档「重要性」⇄ 旧三档 `priority` 的双向映射。
 *
 * `priority` 是**跨端契约**（AI 工具参数、`PhoneDump` 导出给 PC、备份文件都在读它），
 * 所以留着不让位，只把它变成 importance 的投影：
 * 旧数据 priority=1（中）↔ importance=2（中），迁移后逐行等价，不需要回填。
 */
object PriorityMap {
    /** importance(0..4) → priority（0 高 / 1 中 / 2 低） */
    fun priorityOf(importance: Int): Int = when {
        importance < 0 -> 1        // 未设置：按"中"参与旧三档契约，不能冒充"低"
        importance >= 3 -> 0
        importance == 2 -> 1
        else -> 2
    }

    /** priority（0 高 / 1 中 / 2 低）→ importance(0..4)。AI 工具/旧数据写入走这里 */
    fun importanceOf(priority: Int): Int = when (priority) {
        0 -> 4
        2 -> 0
        else -> 2
    }

    /** 档位标签（0..4 → 低/偏低/中/偏高/高），编辑器与筛选共用，别各写一份 */
    fun labelOf(level: Int): String = when (level.coerceIn(0, 4)) {
        0 -> "低"
        1 -> "偏低"
        2 -> "中"
        3 -> "偏高"
        else -> "高"
    }
}
