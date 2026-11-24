package io.github.m0use001.planability.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert

/**
 * v36：记忆后台整理的**进度表**（B 项要的 memory_progress）。
 *
 * 一条 = 一个会话「整理到哪儿了」。为什么单独建表、不挂在 `chat_sessions` 上：
 * 会话每保存一次就被整行重写（ChatRepo 走 REPLACE），进度挂上去会被冲掉。
 * rikkahub 为此专门建了 `memory_consolidation`，理由一字不差（它的类注释就这么写的）。
 * 来源: rikkahub/data/db/entity/MemoryConsolidationEntity.kt:8-28。
 *
 * **刻意不加 ForeignKey**：本仓库全库没有任何外键声明（`chat_messages` 也只有 Index），
 * 会话删除后由 [MemoryProgressDao.deleteOrphans] 顺手清，不把 CASCADE 引进来。
 *
 * `sessionId` 用 INTEGER 而不是 TEXT：`chat_sessions.id` 是 Long，
 * 存 TEXT 会让每次查询都做 Long↔String 转换，而且 TEXT 列的类型亲和会把数字转成文本，
 * 跟 `chat_messages.sessionId` 的用法也对不上。
 */
@Entity(tableName = "memory_progress")
data class MemoryProgressEntity(
    @PrimaryKey val sessionId: Long,
    val consolidatedAt: Long = 0L,
)

@Dao
interface MemoryProgressDao {
    @Upsert
    suspend fun upsert(p: MemoryProgressEntity)

    @Query("SELECT consolidatedAt FROM memory_progress WHERE sessionId = :sessionId")
    suspend fun of(sessionId: Long): Long?

    @Query("SELECT COUNT(*) FROM memory_progress")
    suspend fun count(): Int

    /**
     * 「上次整理之后又有更新」的会话，最近更新的在前。
     *
     * 与 rikkahub 的 `getUpdatedConversationIds` 同构（LEFT JOIN + COALESCE，
     * 用 `updatedAt > COALESCE(consolidatedAt, 0)` 粗筛）。来源:
     * rikkahub/data/db/dao/MemoryConsolidationDAO.kt:17-25。
     *
     * 注意这只是**粗筛**：会话被重新生成回复、或最后一轮没结束，都会让 updatedAt 变大，
     * 真正有没有"新的用户轮次"要读消息才能判定（第二步的整理器做这件事）。
     */
    @Query(
        "SELECT c.id FROM chat_sessions AS c " +
            "LEFT JOIN memory_progress AS m ON m.sessionId = c.id " +
            "WHERE c.updatedAt >= :since AND c.updatedAt > COALESCE(m.consolidatedAt, 0) " +
            "ORDER BY c.updatedAt DESC LIMIT :limit"
    )
    suspend fun pendingSessionIds(since: Long, limit: Int): List<Long>

    /** 不给时间窗的版本，给「一次整理到底有哪些候选」用。 */
    @Query(
        "SELECT c.id FROM chat_sessions AS c " +
            "LEFT JOIN memory_progress AS m ON m.sessionId = c.id " +
            "WHERE c.updatedAt > COALESCE(m.consolidatedAt, 0) " +
            "ORDER BY c.updatedAt DESC LIMIT :limit"
    )
    suspend fun findPending(limit: Int): List<Long>

    /** 会话被删掉后清孤儿行（本库无外键，靠这个兜；启动时顺手跑一次）。 */
    @Query("DELETE FROM memory_progress WHERE sessionId NOT IN (SELECT id FROM chat_sessions)")
    suspend fun deleteOrphans(): Int
}

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
