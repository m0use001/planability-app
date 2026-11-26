package io.github.m0use001.planability.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Update

/**
 * 长期记忆（zcode 式索引/正文分离）：
 * - type: project / feedback / reference / handoff
 * - inIndex=true 的工作层条目（含 handoff）每轮常驻注入；其余按打分召回归档层
 * - body 内 [[wikilink]] 解析进 memory_links，召回一跳扩展
 */
@Entity(
    tableName = "memories",
    indices = [
        // v57：唯一性从「全局同名」改成「同项目同名」——跨项目同名是常态（线代和小说
        // 都会有「我在和用户做X」），全局唯一只会逼出串味与静默覆盖（2026-11-21 串味审计）。
        Index(value = ["name", "projectId"], unique = true),
        Index("type"),
        Index("inIndex")
    ]
)
data class MemoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** 简短唯一名（slug，也是召回/合并的键） */
    val name: String,
    val type: String = "project",
    /** 一句话描述（索引行展示用） */
    val description: String = "",
    /** 正文（全文，召回命中才注入） */
    val body: String = "",
    /** 1 普通 / 2 重要 / 3 关键 */
    val importance: Int = 1,
    val accessCount: Int = 0,
    val lastAccessAt: Long = 0,
    /** 过期时间 millis；0 = 永久 */
    val expiration: Long = 0,
    /** 工作层常驻 */
    val inIndex: Boolean = false,
    val isDeleted: Boolean = false,
    val originSession: Long = 0,
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
    /** 所属项目（0 = 未分类，v5 项目大体系） */
    val projectId: Long = 0,
    // ── v45（2026-10-30）：冲突四态 / 时间有效性 / 分层 / 证据 ──
    /** 0..100；-1 = 用户明确要求 AI 永远遵守的死命令（TencentDB instruction 分档） */
    val priority: Int = 50,
    /** L2 场景名（“我在和用户做 C++ 课程复习”）；空 = 未归类 */
    val scene: String = "",
    /** 世界时间起点（这条事实何时开始为真）；0 = 沿用 createdAt */
    val validAt: Long = 0,
    /** 世界时间终点（何时停止为真）；0 = 仍有效。矛盾更新只写它，不删行 */
    val invalidAt: Long = 0,
    /** 系统判定其失效的墙钟时间（区别于 invalidAt 的世界时间） */
    val expiredAt: Long = 0,
    /** 每次 update/merge +1 */
    val version: Int = 1,
    /** merge 墓碑：被哪条记忆取代；0 = 未被取代 */
    val supersededBy: Long = 0
)

/**
 * v45：记忆 → 原始消息的证据边（Honcho `source_indices` 的落库形态）。
 *
 * 为什么单独一张表而不是塞进 body：用户问「你凭什么这么认为」时要能直接跳到原话；
 * 而正文是给模型读的，掺进 `[#123]` 只会污染召回。kind=context 表示「用来解释的那句」，
 * 不是事实本身的出处。
 */
@Entity(
    tableName = "memory_evidence",
    indices = [
        Index("memoryId"),
        Index(value = ["memoryId", "messageId"], unique = true)
    ]
)
data class MemoryEvidence(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val memoryId: Long,
    val messageId: Long,
    val kind: String = "support",
    val createdAt: Long = 0
)

/** v45：L2 场景（TencentDB scene / heat）。上限与合并策略在 ai 层控制。 */
@Entity(
    tableName = "memory_scenes",
    // v57：与 memories 同理——场景按 (name, projectId) 唯一，跨项目同名场景各自独立
    //（旧的全局唯一是场景串味的机制根源：任何项目写同名场景都灌进同一行）。
    indices = [Index(value = ["name", "projectId"], unique = true)]
)
data class MemoryScene(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val summary: String = "",
    /** 新建=1、每次更新+1、合并=参与方之和 */
    val heat: Int = 1,
    val projectId: Long = 0,
    val validAt: Long = 0,
    val invalidAt: Long = 0,
    val createdAt: Long = 0,
    val updatedAt: Long = 0
)

/**
 * v45：L3 长期画像（单行，id 恒为 1）。
 *
 * 与 memories 分开的原因：画像是**被生成物**，每次重建整体覆盖；
 * 混进 memories 会被整理器当成普通记忆反复改写，也会占掉工作层名额。
 */
@Entity(tableName = "memory_persona")
data class MemoryPersona(
    @PrimaryKey val id: Long = 1,
    val body: String = "",
    val version: Int = 1,
    val updatedAt: Long = 0
)

/** FTS 外部内容虚表：name/description/body 全文检索 */
@Fts4(contentEntity = MemoryEntity::class)
@Entity(tableName = "memories_fts")
data class MemoryFts(
    val name: String,
    val description: String,
    val body: String
)

/** wikilink 图：召回一跳扩展 */
@Entity(tableName = "memory_links", indices = [Index("fromId"), Index("toId")])
data class MemoryLink(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val fromId: Long,
    val toId: Long
)

/** 记忆审计（ADD/UPDATE/DELETE/RECALL） */
@Entity(tableName = "memory_history", indices = [Index("memoryId")])
data class MemoryHistory(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val memoryId: Long,
    val action: String,
    val detail: String = "",
    val at: Long = 0
)

@Dao
interface MemoryDao {
    @Query("SELECT * FROM memories WHERE isDeleted = 0 ORDER BY inIndex DESC, updatedAt DESC")
    suspend fun all(): List<MemoryEntity>

    @Query("SELECT COUNT(*) FROM memories WHERE isDeleted = 0")
    suspend fun count(): Int

    // I1：加 LIMIT。原来没有上限 —— 用户在记忆页每 pin 一条，每轮就多注入一行，
    // 2000 条记忆 + 手滑 pin 几十条时，光索引行就能吃掉小半个窗口。
    // v45：工作层也要过滤「已失效」与「已被合并取代」——墓碑行仍在库里（可追溯），
    // 但绝不能继续每轮注入，否则用户会看到被纠正过的旧说法。
    //
    // 2026-11-26（小说会话事故）：工作层原来**不分项目**——别的项目的 handoff 每轮
    // 都注入到当前会话，模型于是把「军训/线代/体测」这些别的项目的进度当成可删条目
    // 列进清单。现在按会话作用域过滤：scope<0（助理中枢）全库；否则只留
    // 本项目 + 全局(0) + 常驻索引(inIndex=1)。handoff 也只在**本项目**内可见。
    @Query(
        "SELECT * FROM memories WHERE isDeleted = 0 AND (inIndex = 1 OR type = 'handoff') " +
            "AND (invalidAt = 0 OR invalidAt > :now) AND supersededBy = 0 " +
            "AND (:scope < 0 OR projectId = :scope OR projectId = 0 OR inIndex = 1) " +
            "ORDER BY importance DESC, updatedAt DESC LIMIT :limit"
    )
    suspend fun workingLayer(limit: Int, now: Long, scope: Long): List<MemoryEntity>

    @Query(
        "SELECT COUNT(*) FROM memories WHERE isDeleted = 0 AND (inIndex = 1 OR type = 'handoff') " +
            "AND (invalidAt = 0 OR invalidAt > :now) AND supersededBy = 0 " +
            "AND (:scope < 0 OR projectId = :scope OR projectId = 0 OR inIndex = 1)"
    )
    suspend fun countWorking(now: Long, scope: Long): Int

    @Query("SELECT * FROM memories WHERE name = :name LIMIT 1")
    suspend fun byName(name: String): MemoryEntity?

    /** 同项目内按名字查（handoff 这类"每项目一份"的记忆用）。projectId=0 = 全局那份。 */
    @Query("SELECT * FROM memories WHERE name = :name AND projectId = :projectId LIMIT 1")
    suspend fun byNameInProject(name: String, projectId: Long): MemoryEntity?

    /**
     * v57 项目作用域内按名字读（不写！写路径一律 [byNameInProject] 严格同项目）：
     * 可见序 = 本项目 → 全局(0) → 常驻索引(inIndex=1，与 recall 的跨项目可见规则一致)。
     * 别的项目的普通条目一律查不到——这是 2026-11-21 串味修复的一半：
     * AI 在小说会话里按名字翻记忆/作废记忆，翻不到也作废不了别的项目的行。
     * 不滤 isDeleted：软删行要能被 memory_add 原地复活（undo/重放语义）。
     */
    @Query(
        "SELECT * FROM memories WHERE name = :name AND " +
            "(projectId = :projectId OR projectId = 0 OR inIndex = 1) " +
            "ORDER BY CASE WHEN projectId = :projectId THEN 0 WHEN projectId = 0 THEN 1 ELSE 2 END " +
            "LIMIT 1"
    )
    suspend fun byNameVisible(name: String, projectId: Long): MemoryEntity?

    /**
     * 一次性修数据（2026-10-31）：把**落在全局(projectId=0)但来源会话属于某项目**的
     * handoff 归回那个项目。历史 bug：rewriteHandoff 没带 projectId，任何人任何项目的
     * 「当前状态+下一步」都写进同一条全局记忆，再被注入到所有项目的对话里
     * （用户实锤：Python 编程的交接出现在全局记忆）。
     * 只动 handoff 且来源会话仍在、项目>0 的行；来源会话已删的保持原样（无从判断归属）。
     */
    @Query(
        "UPDATE memories SET projectId = (" +
            "SELECT projectId FROM chat_sessions WHERE chat_sessions.id = memories.originSession) " +
            "WHERE type = 'handoff' AND projectId = 0 AND originSession > 0 AND EXISTS (" +
            "SELECT 1 FROM chat_sessions WHERE chat_sessions.id = memories.originSession " +
            "AND chat_sessions.projectId > 0)"
    )
    suspend fun adoptOrphanHandoffs(): Int

    @Query("SELECT * FROM memories WHERE id = :id")
    suspend fun byId(id: Long): MemoryEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(m: MemoryEntity): Long

    @Query("UPDATE memories SET isDeleted = 1, updatedAt = :now WHERE id = :id")
    suspend fun softDelete(id: Long, now: Long)

    @Query("UPDATE memories SET accessCount = accessCount + 1, lastAccessAt = :now WHERE id IN (:ids)")
    suspend fun bumpAccess(ids: List<Long>, now: Long)

    // FTS（外部内容表）；中文退化为 LIKE
    @Query(
        "SELECT memories.* FROM memories JOIN memories_fts ON memories.rowid = memories_fts.rowid " +
            "WHERE memories_fts MATCH :match AND memories.isDeleted = 0 LIMIT 12"
    )
    suspend fun ftsSearch(match: String): List<MemoryEntity>

    @Query(
        "SELECT * FROM memories WHERE isDeleted = 0 AND " +
            "(name LIKE '%'||:t||'%' OR description LIKE '%'||:t||'%' OR body LIKE '%'||:t||'%') LIMIT 12"
    )
    suspend fun likeSearch(t: String): List<MemoryEntity>

    @Query("SELECT * FROM memories WHERE id IN (:ids) AND isDeleted = 0")
    suspend fun byIds(ids: List<Long>): List<MemoryEntity>

    @Insert
    suspend fun insertLink(l: MemoryLink): Long

    @Query("DELETE FROM memory_links WHERE fromId = :fromId")
    suspend fun clearLinks(fromId: Long)

    @Query("SELECT * FROM memories WHERE id IN (SELECT toId FROM memory_links WHERE fromId = :fromId) AND isDeleted = 0")
    suspend fun linkedFrom(fromId: Long): List<MemoryEntity>

    @Insert
    suspend fun insertHistory(h: MemoryHistory): Long

    @Query("SELECT * FROM memory_history WHERE memoryId = :memoryId ORDER BY at DESC LIMIT 20")
    suspend fun historyOf(memoryId: Long): List<MemoryHistory>

    // ── v45 备份用（BackupManager 全量导出/导入；插入保留原 id，顺序由调用方保证）──
    /**
     * v45：merge 墓碑。旧记忆不删，只标记「被谁取代」+ 失效时间。
     * `invalidAt` 用世界时间（新事实开始为真的时刻），`expiredAt` 用系统时间（现在）。
     */
    @Query(
        "UPDATE memories SET supersededBy = :by, invalidAt = :at, expiredAt = :at, updatedAt = :at " +
            "WHERE id = :id"
    )
    suspend fun markSuperseded(id: Long, by: Long, at: Long)

    /**
     * 2026-11-12：显式作废（◆ 实锤）。区别于 merge 墓碑 [markSuperseded]——
     * 没有取代者，用户只是宣布「这条不再算数」。invalidAt 用世界时间=现在，
     * 行保留（证据链/审计），recall 与工作层查询本来就按 invalidAt 过滤。
     */
    @Query("UPDATE memories SET invalidAt = :at, expiredAt = :at, updatedAt = :at WHERE id = :id")
    suspend fun markInvalid(id: Long, at: Long)

    /** 备份用：含软删行（墓碑也是数据，丢了就还原不出历史） */
    @Query("SELECT * FROM memories")
    suspend fun allIncludingDeleted(): List<MemoryEntity>

    @Query("DELETE FROM memories")
    suspend fun clearAll()

    @Insert
    suspend fun insertAll(list: List<MemoryEntity>)

    @Query("SELECT * FROM memory_links")
    suspend fun allLinks(): List<MemoryLink>

    @Query("DELETE FROM memory_links")
    suspend fun clearAllLinks()

    @Insert
    suspend fun insertAllLinks(list: List<MemoryLink>)

    @Query("SELECT * FROM memory_history")
    suspend fun allHistory(): List<MemoryHistory>

    @Query("DELETE FROM memory_history")
    suspend fun clearAllHistory()

    @Insert
    suspend fun insertAllHistory(list: List<MemoryHistory>)
}

/** v45：证据边 */
@Dao
interface MemoryEvidenceDao {
    @Query("SELECT * FROM memory_evidence WHERE memoryId = :memoryId ORDER BY id")
    suspend fun of(memoryId: Long): List<MemoryEvidence>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(e: MemoryEvidence): Long

    @Insert
    suspend fun insertAll(list: List<MemoryEvidence>)

    @Query("SELECT * FROM memory_evidence")
    suspend fun all(): List<MemoryEvidence>

    @Query("DELETE FROM memory_evidence")
    suspend fun clearAll()
}

/** v45：L2 场景 */
@Dao
interface MemorySceneDao {
    @Query("SELECT * FROM memory_scenes ORDER BY heat DESC, updatedAt DESC")
    suspend fun all(): List<MemoryScene>

    /** 注入用：有效的场景，按 heat 排序；scope>0 时只取全局 + 本项目。 */
    @Query(
        "SELECT * FROM memory_scenes WHERE (invalidAt = 0 OR invalidAt > :now) " +
            "AND (:scope <= 0 OR projectId = 0 OR projectId = :scope) " +
            "ORDER BY heat DESC, updatedAt DESC LIMIT :limit"
    )
    suspend fun activeForProject(scope: Long, limit: Int, now: Long): List<MemoryScene>

    /** 超过上限时把最冷的场景标失效（不删行）。 */
    @Query("UPDATE memory_scenes SET invalidAt = :at, updatedAt = :at WHERE id = :id")
    suspend fun invalidate(id: Long, at: Long)

    @Query("SELECT * FROM memory_scenes WHERE name = :name LIMIT 1")
    suspend fun byName(name: String): MemoryScene?

    /** v57：按项目内名字取场景——场景的归项目隔离就靠它（旧的 byName 全局匹配是串味根源）。 */
    @Query("SELECT * FROM memory_scenes WHERE name = :name AND projectId = :projectId LIMIT 1")
    suspend fun byNameInProject(name: String, projectId: Long): MemoryScene?

    /**
     * v57：**只**取 [projectId] 自己名下的有效场景（不含全局兜底）——场景上限按项目各自
     * 封顶（[MemoryLayers.MAX_SCENES]），别的项目的热度不该占本项目的名额。
     */
    @Query(
        "SELECT * FROM memory_scenes WHERE projectId = :projectId AND (invalidAt = 0 OR invalidAt > :now) " +
            "ORDER BY heat DESC, updatedAt DESC LIMIT :limit"
    )
    suspend fun activeOwnedBy(projectId: Long, limit: Int, now: Long): List<MemoryScene>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(s: MemoryScene): Long

    @Insert
    suspend fun insertAll(list: List<MemoryScene>)

    @Query("DELETE FROM memory_scenes")
    suspend fun clearAll()
}

/** v45：L3 画像（单行） */
@Dao
interface MemoryPersonaDao {
    @Query("SELECT * FROM memory_persona WHERE id = 1")
    suspend fun get(): MemoryPersona?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(p: MemoryPersona)

    @Insert
    suspend fun insertAll(list: List<MemoryPersona>)

    @Query("DELETE FROM memory_persona")
    suspend fun clearAll()
}
