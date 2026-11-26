package io.github.m0use001.planability.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/** v11：自动化任务——定时让 AI 执行 prompt 并通知结果 */
@Entity(tableName = "automations")
data class Automation(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    /** 执行时发给 AI 的指令 */
    val prompt: String,
    /** 助手稳定键（general/planner/digest/tutor/writer） */
    val assistantKey: String = "general",
    /** 周期间隔分钟；0 = 不按间隔 */
    val intervalMinutes: Int = 0,
    /** 每日执行时刻（24 小时制）；-1 = 不按每日时刻 */
    val dailyHour: Int = -1,
    val dailyMinute: Int = -1,
    /**
     * v24：地理围栏触发。
     *
     * 坐标存 **WGS84**（和日程/待办的 lat/lng 同一个口径），判定时和系统给的位置直接算距离，
     * 不做任何 GCJ 换算——**定位 API 给的本来就是 WGS84**，这里若去换反而会引入几百米误差。
     *
     * [geoTrigger] 取值：`none`（不按位置，默认）/ `arrive`（进入）/ `leave`（离开）。
     * 用字符串而不是 enum，是为了让 Room 存起来与 AI 工具的参数口径一致（AI 传的就是字符串）。
     */
    val geoTrigger: String = "none",
    val geoLat: Double = 0.0,
    val geoLng: Double = 0.0,
    /** 围栏半径（米），下限 50 */
    val geoRadiusM: Int = 0,
    /**
     * v19.42：**事件触发**类型。空串 = 不按事件。
     *
     * 取值见 `notify/AutomationSignal.kt` 的 KINDS：boot / power / unlock / app_open。
     * 用字符串而不是 enum：跟 [geoTrigger] 同一个理由——AI 工具与数据库共用一套字面量。
     *
     * **为什么不拿现成字段凑**：按间隔/每日时刻/位置三种触发各有各的字段，
     * 再往里塞「充电时」就得给 interval 一堆特殊值，而那些值在别处都会被当成分钟数用。
     * 事件触发单独两列，读的时候不用查表。
     */
    val triggerKind: String = "",
    /**
     * 事件触发的参数，含义随 [triggerKind]：
     * power = `on`（插上）/ `off`（拔掉）；boot / unlock 不用；app_open = 目标包名。
     */
    val triggerArg: String = "",
    /**
     * 迟滞状态：当前在围栏内还是外。**必须有它**，否则人站在围栏边上抖动时，
     * arrive/leave 会交替触发、把通知刷成spam。判据是 `geoRadiusM` 加上/减去一个余量。
     */
    val geoInside: Boolean = false,
    val enabled: Boolean = true,
    val lastRun: Long = 0,
    val createdAt: Long = 0,
    /**
     * v26：执行方式。`ai` = 触发时把 [prompt] 交给模型执行（旧行为，默认）；
     * `flow` = 确定性步骤流水线（步骤存 [stepsJson]，见 `core/ActionRegistry.kt`），
     * 不调模型不烧 token，遇错即停并出通知。
     * 用字符串而不是 enum 与 [geoTrigger] 同理：存储与 AI 工具传参口径一致。
     */
    val kind: String = "ai",
    /** kind=flow 时的步骤序列 JSON（`AutomationStep.encodeList`）；kind=ai 时为 "[]" 不参与 */
    val stepsJson: String = "[]"
) {
    /** 是否配置了有效触发 */
    val hasTrigger: Boolean
        get() = intervalMinutes > 0 || (dailyHour in 0..23 && dailyMinute in 0..59) ||
            hasGeoTrigger || hasEventTrigger

    /**
     * v19.42：是否配了**事件触发**。
     *
     * 单独一个判据不是为了好看——它决定了 [io.github.m0use001.planability.notify.AutomationSweep]
     * 会不会把这条当定时任务跑：巡检的过滤是 `enabled && hasTrigger`，
     * 一旦事件触发被算进 hasTrigger 而巡检那边不排掉，事件型任务就会**每 15 分钟跑一遍**
     * （`else` 分支还会拿 dailyHour=-1 去算触发时刻）。
     * 症状是「我明明只插了一次充电，它跑了一晚上」——所以这条判据必须与巡检的过滤成对出现。
     */
    val hasEventTrigger: Boolean
        get() = triggerKind.isNotBlank()

    /** 是否配了位置触发 */
    val hasGeoTrigger: Boolean
        get() = geoTrigger != "none" && geoRadiusM > 0 && (geoLat != 0.0 || geoLng != 0.0)
}

@Dao
interface AutomationDao {
    @Query("SELECT * FROM automations ORDER BY createdAt DESC")
    suspend fun all(): List<Automation>

    @Query("SELECT * FROM automations WHERE enabled = 1")
    fun observeEnabled(): Flow<List<Automation>>

    @Query("SELECT * FROM automations WHERE id = :id")
    suspend fun byId(id: Long): Automation?

    @Insert
    suspend fun insert(a: Automation): Long

    @Update
    suspend fun update(a: Automation)

    @Query("UPDATE automations SET lastRun = :at WHERE id = :id")
    suspend fun touchLastRun(id: Long, at: Long)

    /**
     * v24：只取**启用了**且**确实带位置**的自动化。
     *
     * 这条查询就是「不空扫描」的全部保证：`GeoWatch` 只看这个返回值，
     * 空列表 = 立刻注销监听、退出、连权限都不问。反过来写（取全部再在内存里过滤）
     * 的话，每次数据变更都要把所有自动化捞出来，已经不算扫描但也不是零成本。
     */
    @Query(
        "SELECT * FROM automations WHERE enabled = 1 AND geoTrigger != 'none' " +
            "AND geoRadiusM > 0 AND (geoLat != 0.0 OR geoLng != 0.0)"
    )
    suspend fun enabledGeo(): List<Automation>

    @Query("DELETE FROM automations WHERE id = :id")
    suspend fun delete(id: Long)
}

/**
 * v35：一次自动化执行的记录——**"到点跑没跑"和"跑成什么样"的回溯面**。
 *
 * ## 为什么非有不可
 * 改这条之前，手机端执行完只留两样东西：一条系统通知和会话里的那轮对话。
 * 通知划掉就没了，AI 路径失败时**连会话都不一定落得下**（`runAction` 抛异常），
 * 于是"任务失败了"这件事在界面上没有任何回溯点。
 * PC 端早有 TaskLog（`automation-task.ts`，带 runId、可回放），手机端一直缺——
 * 这是两端能力差异里最实在的一条，见 `◆/planability-cross-end-feature-gap.md` §3.10。
 *
 * ## 几个设计取舍
 *  - **冗余存 [name]**：任务被删掉后记录还得读得懂（PC 也是这个取向）。
 *    代价是任务改名后旧记录还显示旧名字——那反而是对的，记录是历史。
 *  - **不进备份**：`automations` 本身就不在 `BackupManager.exportJson` 的表清单里，
 *    运行日志更不该进（大、易变、PC 侧无对应物）。所以**不用改 BackupManager**，
 *    这也意味着它是纯手机本地的表，不构成跨端契约。
 *  - **不级联删除**：删任务时保留记录，靠 [AutomationRunDao.prune] 兜底封顶。
 *  否则"我删掉的那个任务是不是一直失败"这个问题永远查不到。
 *  - [summary] 和 [detail] 分开：摘要给列表行（和通知同一句），明细给展开看
 *    （flow 的每步结果 / ai 的错误串）。塞一起的话列表会被第一步的长文本挤爆。
 */
@Entity(
    tableName = "automations_run",
    indices = [Index("automationId", "at")]
)
data class AutomationRun(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val automationId: Long,
    /** 冗余的任务名，见类注释 */
    val name: String,
    /** `ai` / `flow`，与 [Automation.kind] 同口径 */
    val kind: String,
    /** 执行**开始**时刻（不是结束），排序与「上次执行」都按它 */
    val at: Long = 0,
    val ok: Boolean = false,
    /** 摘要：与推给用户的那条通知同一句 */
    val summary: String = "",
    /** 明细：flow 的每步一行 / ai 失败时的异常串 */
    val detail: String = "",
    val durationMs: Long = 0
)

@Dao
interface AutomationRunDao {
    @Insert
    suspend fun insert(r: AutomationRun): Long

    @Query("SELECT * FROM automations_run WHERE automationId = :id ORDER BY at DESC LIMIT :limit")
    suspend fun recent(id: Long, limit: Int): List<AutomationRun>

    @Query("SELECT * FROM automations_run ORDER BY at DESC LIMIT :limit")
    suspend fun recentAll(limit: Int): List<AutomationRun>

    /**
     * 封顶：只留最新 [keep] 条，其余全删。
     *
     * 不按"每任务 keep N"是因为那要相关子查询，SQLite 里既难写又难读；
     * 这张表 15 分钟一轮巡检，按 500 条封顶算，即使任务一天跑几十次也够翻很久。
     */
    @Query(
        "DELETE FROM automations_run WHERE id NOT IN " +
            "(SELECT id FROM automations_run ORDER BY at DESC, id DESC LIMIT :keep)"
    )
    suspend fun prune(keep: Int)
}
