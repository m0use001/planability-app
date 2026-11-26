package io.github.m0use001.planability.data

// M5 KMP 化：CompactMarkerEntity 实体 + CompactMarkerDao 已迁 `:shared`（Room 真源），同包跨模块直解。
// 本文件只留 CompactRepo（薄封装，依赖 AppDatabase）。

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
