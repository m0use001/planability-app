package io.github.m0use001.planability.data

// M5 KMP 化：MemoryProgressEntity 实体 + MemoryProgressDao 已迁 `:shared`（Room 真源），
// 同包跨模块直解。本文件只留 MemoryProgressRepo（薄封装）。

/**
 * 薄封装：只做「读-改-写」这几次数据库往返，**不含策略**。
 * 什么时候整理、整理多少，是第二步调度器的事，不放这里。
 */
class MemoryProgressRepo(private val dao: MemoryProgressDao) {

    suspend fun of(sessionId: Long): Long? = dao.of(sessionId)

    suspend fun count(): Int = dao.count()

    /** 把该会话整理到 [at]（毫秒时间戳）。只在整理成功之后调。 */
    suspend fun advanceTo(sessionId: Long, at: Long) {
        dao.upsert(MemoryProgressEntity(sessionId = sessionId, consolidatedAt = at))
    }

    suspend fun pendingSessionIds(since: Long, limit: Int): List<Long> =
        dao.pendingSessionIds(since, limit)

    suspend fun findPending(limit: Int): List<Long> = dao.findPending(limit)

    /** @return 清掉的孤儿行数 */
    suspend fun deleteOrphans(): Int = dao.deleteOrphans()
}
