package io.github.m0use001.planability.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query

/**
 * v37：**压缩标记**（`compact_markers`）。一条 = 一次"把某个会话的一段旧历史换成摘要"。
 *
 * ## 为什么单独建表
 * 摘要正文已经在 `chat_sessions.memoryPrompt` 里（滚动摘要在用），但那只说明"有一份摘要"，
 * 不说"这段摘要覆盖到哪儿、覆盖时是什么状态"。压缩状态机需要一条**可回放、可审计**的记录：
 * - 分割线画在哪（`lastCompactedMessageId` / `firstKeptMessageId`）；
 * - 这次压掉了多少条（`compactedCount`）；
 * - 是手动 / 自动 / 兜底触发的（`source`）；
 * - 失败后能不能只回滚这一条（见 [CompactMarkerDao] 的 delete）。
 * 参考 OpenMinis `CompactMarkerEntity`（anchors 模型：`lastCompactedMessageId` 是**消息 id**，
 * 不是计数下标）与 rikkahub 的"检查点是消息节点、历史不物理删除"。
 *
 * ## 锚点用消息 id，不用计数
 * `chat_sessions.lastSummarizeIndex`（v39 起是兼容列）是**计数**，删过消息（长按删除/重生成）
 * 之后它指向别处——物理上从未摘要的消息会被当成已摘要丢掉（见 ref-runtime-study.md 7.2-M5）。
 * v39 给 chat_sessions 加了同语义的 `lastSummarizedMessageId`（M6 根治），
 * 本表的锚点也一律是消息 id：消息被删就让锚点一并失效，不会悄悄错位。
 *
 * ## sessionId 用 ForeignKey(CASCADE)
 * 用户（phone-a）在 memory_progress 上刻意不加外键，理由是"会话每保存一次就被整行重写"。
 * 本表**可以**加，因为实测本仓的写路径里会话更新走 `@Update`（`ChatDao.updateSession` /
 * `updateSessionRaw`），只有新建走 `@Insert(REPLACE)`，REPLACE 只会命中新 id；
 * 会话删除（`deleteSession`）时 marker 本来就没有意义，CASCADE 顺手清掉更干净。
 * **注意**：将来谁要是把会话保存改成 INSERT OR REPLACE，这条外键会把该会话的全部压缩标记
 * 一起清掉——那种改动必须连带回来读这段注释。
 */
@Entity(
    tableName = "compact_markers",
    foreignKeys = [
        ForeignKey(
            entity = ChatSession::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("sessionId"), Index("lastCompactedMessageId")]
)
data class CompactMarkerEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    /** 摘要正文：替代"锚点之前的全部历史"。 */
    val summary: String,
    /** 锚点（消息 id）：本标记覆盖到此 id 为止（含）。锚点之后照发原文。 */
    val lastCompactedMessageId: Long,
    /** 锚点之后的第一条消息 id（画分割线用；0 = 未知，回退 lastCompactedMessageId + 1）。 */
    val firstKeptMessageId: Long = 0,
    /** 本标记压掉的消息条数。 */
    val compactedCount: Int = 0,
    /**
     * v47：**区间起点**（消息 id，含）。0 = 旧语义「从会话最开头一路压到 lastCompactedMessageId」。
     *
     * 为什么加这一列：v37 的压缩只有"整历史滚动"一种形状——一条摘要替代锚点之前的**全部**历史。
     * 2026-11-03 用户要求「选中几轮连续对话进行选择性压缩」，于是同表要能表达**一段区间**：
     * 起点 firstCompactedMessageId、终点 lastCompactedMessageId。0 保持旧行语义，读取侧
     * （[io.github.m0use001.planability.ai.ContextEngine] 的组装 / 会话界面渲染）按 0 走老路径。
     */
    val firstCompactedMessageId: Long = 0,
    /** 触发来源：manual（/compact）/ auto（阈值）/ rescue（兜底）/ manual_range（v47 区间压缩）。 */
    val source: String = "manual",
    val createdAt: Long = 0,
    /** 标记形状版本（1 = 当前字段），将来的形状演进靠它分辨，别复用旧行。 */
    val version: Int = 1
)

@Dao
interface CompactMarkerDao {

    /** 最近一条标记；没有（没压缩过）返回 null。 */
    @Query("SELECT * FROM compact_markers WHERE sessionId = :sessionId ORDER BY createdAt DESC, id DESC LIMIT 1")
    suspend fun latest(sessionId: Long): CompactMarkerEntity?

    /** 一个会话的全部标记（新→旧），用于分割线渲染与回放。 */
    @Query("SELECT * FROM compact_markers WHERE sessionId = :sessionId ORDER BY createdAt DESC, id DESC")
    suspend fun allOf(sessionId: Long): List<CompactMarkerEntity>

    @Query("SELECT COUNT(*) FROM compact_markers")
    suspend fun count(): Int

    @Insert
    suspend fun insert(marker: CompactMarkerEntity): Long

    /**
     * 只改摘要侧字段。锚点不动：它是"压到哪儿"的事实记录，
     * 改锚点等于改历史，那是另一次压缩该做的事。
     */
    @Query(
        "UPDATE compact_markers SET summary = :summary, compactedCount = :count, " +
            "firstKeptMessageId = :firstKept, createdAt = :at WHERE id = :id"
    )
    suspend fun updateSummary(id: Long, summary: String, count: Int, firstKept: Long, at: Long)

    @Query("DELETE FROM compact_markers WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM compact_markers WHERE sessionId = :sessionId")
    suspend fun deleteOfSession(sessionId: Long)

    /** v47：区间压缩——覆盖 [first,last]（含）的全部标记（手动区间可能与旧行重叠时由调用方裁决）。 */
    @Query(
        "SELECT * FROM compact_markers WHERE sessionId = :sessionId AND firstCompactedMessageId > 0 " +
            "ORDER BY firstCompactedMessageId ASC"
    )
    suspend fun rangesOf(sessionId: Long): List<CompactMarkerEntity>

    /** v47：删掉与 [first,last] 有交集的区间行（重新压缩同一段时先清旧行，避免双重语义）。 */
    @Query(
        "DELETE FROM compact_markers WHERE sessionId = :sessionId AND firstCompactedMessageId > 0 " +
            "AND firstCompactedMessageId <= :last AND lastCompactedMessageId >= :first"
    )
    suspend fun deleteOverlappingRanges(sessionId: Long, first: Long, last: Long)
}

/** 压缩标记的仓库。会话行删除交给外键 CASCADE，这里不再写 orphan 清扫。 */
class CompactRepo(private val db: AppDatabase) {
    private val dao get() = db.compactMarkerDao()

    suspend fun latest(sessionId: Long): CompactMarkerEntity? = dao.latest(sessionId)
    suspend fun allOf(sessionId: Long): List<CompactMarkerEntity> = dao.allOf(sessionId)
    suspend fun count(): Int = dao.count()
    suspend fun insert(marker: CompactMarkerEntity): Long = dao.insert(marker)
    suspend fun updateSummary(id: Long, summary: String, count: Int, firstKept: Long, at: Long) =
        dao.updateSummary(id, summary, count, firstKept, at)

    suspend fun delete(id: Long) = dao.delete(id)
    suspend fun deleteOfSession(sessionId: Long) = dao.deleteOfSession(sessionId)

    /** v47：区间压缩的全部区间行（按起点升序），组装与会话渲染共用。 */
    suspend fun rangesOf(sessionId: Long): List<CompactMarkerEntity> = dao.rangesOf(sessionId)

    /** v47：重新压缩同一段前先清掉与其相交的旧区间行。 */
    suspend fun deleteOverlappingRanges(sessionId: Long, first: Long, last: Long) =
        dao.deleteOverlappingRanges(sessionId, first, last)

    /** 记一次成功压缩：锚点 = 本次覆盖到的最后一条消息 id。 */
    suspend fun markCompact(
        sessionId: Long,
        summary: String,
        anchorMessageId: Long,
        firstKeptMessageId: Long,
        compactedCount: Int,
        source: String,
        now: Long,
        /** v47：区间压缩的起点（消息 id）；0 = 旧语义整历史滚动。 */
        firstCompactedMessageId: Long = 0
    ): Long = dao.insert(
        CompactMarkerEntity(
            sessionId = sessionId,
            summary = summary,
            lastCompactedMessageId = anchorMessageId,
            firstKeptMessageId = firstKeptMessageId,
            compactedCount = compactedCount,
            source = source,
            createdAt = now,
            firstCompactedMessageId = firstCompactedMessageId
        )
    )
}
