package io.github.m0use001.planability.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** 群聊提纯记录 */
@Entity(tableName = "chat_digests", indices = [Index("date"), Index("sourceGroup")])
data class ChatDigest(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sourceGroup: String,
    /** dayKey */
    val date: Long,
    val rawText: String,
    /** AI 提纯要点 */
    val digest: String,
    /** 当日总结（一键总结生成后回填） */
    val summary: String = "",
    val createdAt: Long = 0
)

/** 目标群聊（一键总结的采集对象；DB v19） */
@Entity(tableName = "chat_targets", indices = [Index("enabled"), Index("name")])
data class GroupTarget(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** 群名，用于搜索进群与提纯记录归档 */
    val name: String,
    /** 0 微信 / 1 QQ */
    val platform: Int = 0,
    /** 是否参与一键总结 */
    val enabled: Boolean = true,
    val createdAt: Long = 0
)

/** AI 提取的日程/待办草稿（待处理暂存区）
 *  v53：加 source/originProjectId/originSessionId/suggestedStart/suggestedEnd/confidence，
 *  让 AI 助理中枢能按来源汇总、追溯、并显示 AI 建议时段。 */
@Entity(tableName = "pending_items", indices = [Index("status"), Index("source")])
data class PendingItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    /** schedule / todo */
    val kind: String = "todo",
    /** AI 抽到的原始时间描述，如「周三晚上」「明天 14:00」 */
    val timeText: String = "",
    val place: String = "",
    val extra: String = "",
    /** pending / imported / ignored */
    val status: String = "pending",
    val createdAt: Long = 0,
    /** v53：来源 — capture(信息提取) / session(项目会话) / manual(手动) / automation(流水线动作；
     *  2026-11-12 起 todo_add/schedule_add 也只落草稿，不再直写时间轴) */
    val source: String = "capture",
    /** v53：来源项目（0=全局/信息提取） */
    val originProjectId: Long = 0,
    /** v53：来源会话（0=非会话产出） */
    val originSessionId: Long = 0,
    /** v53：AI 建议起始（epoch millis，0=未建议） */
    val suggestedStart: Long = 0,
    /** v53：AI 建议结束（epoch millis，0=未建议） */
    val suggestedEnd: Long = 0,
    /** v53：AI 抽取置信度（0..1，-1=手动） */
    val confidence: Float = -1f,
    /** v54：入库用的完整参数（JSON，通常是创建工具的原始 args）。
     *  普通会话的 todo_create/schedule_create 现在产出草稿，确认入库时按 payload
     *  忠实重建（重复/优先级/标签全保留），而不是只靠 timeText 猜。 */
    val payload: String = ""
)

/** 周小结生成记录 */
@Entity(tableName = "weekly_records", indices = [Index("weekStart")])
data class WeeklyRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val weekStart: Long,
    val text: String,
    /** llm / template */
    val source: String = "template",
    val createdAt: Long = 0
)

/** settings KV 表 */
@Entity(tableName = "settings")
data class SettingKV(
    @PrimaryKey val key: String,
    val value: String
)

/** 成就解锁记录 */
@Entity(tableName = "achievements")
data class AchievementRec(
    @PrimaryKey val key: String,
    val unlockedAt: Long
)

/** v18.1：每日睡眠记录（OPPO 健康节点采集）。一天一条（dateEpochDay 唯一）。 */
@Entity(tableName = "sleep_records", indices = [Index(value = ["dateEpochDay"], unique = true)])
data class SleepRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** 归属日期（醒来那天的 epochDay） */
    val dateEpochDay: Long,
    /** 睡眠总时长（分钟） */
    val durationMin: Int,
    /** 入睡时刻（分钟数 0-1439，凌晨侧） */
    val fallMin: Int,
    /** 醒来时刻（分钟数） */
    val wakeMin: Int,
    /** 原文日期（如 "10月02日，周五"） */
    val dateText: String = "",
    val createdAt: Long = 0
) {
    /** "7 小时 8 分钟" 形态 */
    val durationText: String
        get() = "${durationMin / 60} 小时 ${durationMin % 60} 分钟"

    /** 是否达到 7 小时基线 */
    val atGoal: Boolean get() = durationMin >= 420
}
