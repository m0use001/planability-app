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
import io.github.m0use001.planability.core.AppTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import kotlin.math.exp
import kotlin.math.ln

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
    @Query(
        "SELECT * FROM memories WHERE isDeleted = 0 AND (inIndex = 1 OR type = 'handoff') " +
            "AND (invalidAt = 0 OR invalidAt > :now) AND supersededBy = 0 " +
            "ORDER BY importance DESC, updatedAt DESC LIMIT :limit"
    )
    suspend fun workingLayer(limit: Int, now: Long): List<MemoryEntity>

    @Query(
        "SELECT COUNT(*) FROM memories WHERE isDeleted = 0 AND (inIndex = 1 OR type = 'handoff') " +
            "AND (invalidAt = 0 OR invalidAt > :now) AND supersededBy = 0"
    )
    suspend fun countWorking(now: Long): Int

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
     * 2026-11-12：显式作废（真机会话实锤）。区别于 merge 墓碑 [markSuperseded]——
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

/**
 * 各类型记忆的衰减半衰期（天）。
 *
 * 2026-10-29 吸纳 kaijibot 的「TypedInsight 类别衰减半衰期」与 TencentDB L1 类型分层：
 * 对所有类型统一用 30 天，会让一条踩过的教训和一次闲聊以同样速度淡出——
 * 而长期学习助手真正要保住的是**纠错与经验**，不是上下文。
 *
 * 只改时间项；匹配序/重要度/访问频次三项权重不动（打分公式其余部分保持原样）。
 * handoff 走工作层常驻（inIndex），不在召回主路径上，取默认值即可。
 */
/**
 * 重要度项：priority 0..100 归一；**-1 = 死命令，取满分**。
 *
 * 为什么 -1 要单独处理：它是 TencentDB 里 instruction 的“永远遵守”档，
 * 如果按字面 -1/100 参与排序，用户明确要求“以后都这样”的规则会排到最后——
 * 正好与语义相反。
 */
internal fun memoryPriorityScore(priority: Int): Double =
    if (priority < 0) 1.0 else (priority.coerceIn(0, 100) / 100.0)

internal fun memoryHalfLifeDays(type: String): Double = when (type) {
    "feedback" -> 90.0   // 纠正过的做法、踩过的坑：一个季度内都不该被忘
    "skill" -> 120.0     // 可复用技能/流程：最稳定
    "reference" -> 60.0  // 参考资料：中等
    else -> 30.0         // project / handoff：项目上下文时效性强
}

/** 每轮常驻进 system 的条目上限（handoff 永远算在最前面） */
private const val WORKING_LIMIT = 12
/** 注入块的总字符上限：超了就按条数截，并明说"还有 N 条未展示" */
private const val INJECT_MAX_CHARS = 3200
/** 单条正文最多注入多少字 */
private const val INJECT_BODY_CHARS = 900

/**
 * v57 召回项目作用域判据（纯函数，可单测）。
 *
 * scope<=0 = 全库（后台整理这类无会话上下文的调用）；scope>0 = 全局(0) + 本项目 +
 * 常驻索引(inIndex=1，刻意全局可见的工作层) + handoff（交接本来就要跨项目接力）。
 * 2026-11-21 串味修复的另一半：`memory_search` 从「不传 scope 全库召回」改成传会话项目，
 * 别的项目的普通条目从此搜不出来（det 出现在小说会话那种事不再发生）。
 */
internal fun recallableInScope(m: MemoryEntity, projectScope: Long): Boolean =
    projectScope <= 0L || m.projectId == 0L || m.projectId == projectScope ||
        m.inIndex || m.type == "handoff"

class MemoryRepo(private val version: MutableVersion) {
    private val dao get() = Graph.db.memoryDao()

    suspend fun all(): List<MemoryEntity> = dao.all()
    suspend fun count(): Int = dao.count()
    suspend fun byId(id: Long): MemoryEntity? = dao.byId(id)
    suspend fun workingLayer(
        limit: Int = WORKING_LIMIT,
        now: Long = AppTime.nowMillis()
    ): List<MemoryEntity> = dao.workingLayer(limit, now)

    suspend fun countWorking(now: Long = AppTime.nowMillis()): Int = dao.countWorking(now)

    /**
     * 新增/覆盖式更新（**同项目**同名覆盖，解析 [[wikilink]]），返回 id。
     *
     * v57（2026-11-21 串味审计）：查找从全局 `byName` 收紧为 [MemoryDao.byNameInProject]——
     * 旧逻辑下 A 项目写记忆会静默覆盖 B 项目的同名行（id 被复用、正文被换，纯数据丢失）；
     * 现在跨项目同名是两条独立行（唯一索引已放宽为 (name, projectId)），互不覆盖。
     * 想更新全局(0)那份：从全局会话写，或显式走 byId 路径。
     */
    suspend fun upsert(m: MemoryEntity): Long = withContext(Dispatchers.IO) {
        val now = AppTime.nowMillis()
        val existing = dao.byNameInProject(m.name, m.projectId)
        val toSave = m.copy(
            id = existing?.id ?: 0,
            createdAt = existing?.createdAt ?: now,
            updatedAt = now,
            originSession = if (m.originSession != 0L) m.originSession else existing?.originSession ?: 0
        )
        val id = dao.upsert(toSave)
        // wikilink 解析：[[name]] → 同名记忆
        dao.clearLinks(id)
        Regex("\\[\\[([^\\[\\]]+)]]").findAll(m.body + " " + m.description).forEach { mr ->
            val target = dao.byName(mr.groupValues[1].trim())
            if (target != null && target.id != id) dao.insertLink(MemoryLink(fromId = id, toId = target.id))
        }
        dao.insertHistory(
            MemoryHistory(
                memoryId = id,
                action = if (existing == null) "ADD" else "UPDATE",
                // v45.2 C：UPDATE 必须留旧正文——md 镜像会被下次导出覆盖，审计行是
                // 错误改写唯一的回滚来源（真机实证：作息写进「语言偏好」后原内容无处可寻）
                detail = if (existing == null) "新增"
                else "覆盖更新；旧正文：" + existing.body.take(800),
                at = now
            )
        )
        version.bump()
        // 单向落盘镜像（项目记忆/ 目录）；防抖合并同一轮的多条写入
        MemoryFiles.schedule(toSave.projectId)
        id
    }

    /** v45：把一条旧记忆标成「被 [by] 取代」（merge 墓碑），不删行。 */
    suspend fun markSuperseded(id: Long, by: Long, at: Long) = withContext(Dispatchers.IO) {
        val old = dao.byId(id)
        dao.markSuperseded(id, by, at)
        // C：墓碑也留旧正文——合并出错时同样可回滚
        dao.insertHistory(
            MemoryHistory(
                memoryId = id, action = "MERGE",
                detail = "被 #$by 取代；旧正文：" + (old?.body?.take(800) ?: ""),
                at = at
            )
        )
        version.bump()
        MemoryFiles.schedule(old?.projectId ?: 0L)
    }

    /**
     * 2026-11-12：显式作废。之前模型唯一的写通道是 memory_add——用户宣布某设定作废时
     * 它只能堆「作废声明」记忆，承载旧说法的条目 invalidAt 一直是 0、照常被召回与
     * 常驻注入，作废设定反复复提（真机会话：三轮追问）。
     * 与 [markSuperseded] 同风格：标失效不删行，历史记原因（含旧正文可回滚），
     * 镜像下次导出会带 status: invalid。
     */
    suspend fun markInvalid(id: Long, at: Long, reason: String) = withContext(Dispatchers.IO) {
        val old = dao.byId(id)
        dao.markInvalid(id, at)
        dao.insertHistory(
            MemoryHistory(
                memoryId = id, action = "INVALIDATE",
                detail = "作废：" + reason.ifBlank { "未写明" } + "；正文：" + (old?.body?.take(800) ?: ""),
                at = at
            )
        )
        version.bump()
        MemoryFiles.schedule(old?.projectId ?: 0L)
    }

    /** v45：写证据边。重复 (memoryId,messageId) 由唯一索引忽略，不抛。 */
    suspend fun addEvidence(memoryId: Long, messageIds: List<Long>, now: Long = AppTime.nowMillis()) =
        withContext(Dispatchers.IO) {
            if (memoryId <= 0L || messageIds.isEmpty()) return@withContext
            messageIds.forEach { mid ->
                if (mid <= 0L) return@forEach
                runCatching {
                    Graph.db.memoryEvidenceDao().insert(
                        MemoryEvidence(memoryId = memoryId, messageId = mid, kind = "support", createdAt = now)
                    )
                }
            }
        }

    suspend fun softDelete(id: Long) {
        val pid = dao.byId(id)?.projectId ?: 0L
        dao.softDelete(id, AppTime.nowMillis())
        dao.insertHistory(MemoryHistory(memoryId = id, action = "DELETE", detail = "软删除", at = AppTime.nowMillis()))
        version.bump()
        invalidateScenesIfEmpty(pid)
        MemoryFiles.schedule(pid)
    }

    /**
     * 2026-11-05：**该作用域的记忆清零时，把它的 L2 场景一并失效**。
     *
     * 为什么：场景是记忆/会话沉淀出的热区，会被注入 AI 上下文、也导出成
     * `项目记忆/场景.md`。用户在记忆库把某项目（或全局）的记忆全删光，本意就是
     * 「忘掉这摊事」——但场景不在 memories 表里、UI 里也没有任何入口可删，
     * 结果就是「记忆库显示已删除，文件管理器里的 场景.md 还在、AI 也还记得」
     * （2026-11-05 用户实报）。失效走 invalidAt（与 P3 四态一致，留墓碑可审计）。
     * 只失效**本作用域**的场景（全局记忆清零清全局场景，项目记忆清零清项目场景）。
     */
    private suspend fun invalidateScenesIfEmpty(projectId: Long) {
        val remain = runCatching {
            Graph.db.memoryDao().all().count { it.projectId == projectId }
        }.getOrDefault(-1)
        if (remain != 0) return
        val now = AppTime.nowMillis()
        runCatching {
            Graph.db.memorySceneDao().all()
                .filter { it.projectId == projectId && (it.invalidAt == 0L || it.invalidAt > now) }
                .forEach { Graph.db.memorySceneDao().invalidate(it.id, now) }
        }
    }

    /**
     * 启动维护（幂等）：历史上「记忆已清零、场景还挂着」的项目补一次失效——
     * 修复前删光记忆的项目不会再有删除动作，只能靠启动补跑清掉残留场景
     * （2026-11-05 数据迁移：项目 9 删光记忆后 场景.md 仍残留）。
     */
    suspend fun sweepOrphanScenes() = withContext(Dispatchers.IO) {
        runCatching {
            val activePids = Graph.db.memoryDao().all().map { it.projectId }.toSet()
            // 「曾有记忆、但现在清零」才算孤儿；从未有过记忆的项目（场景由会话直接沉淀）
            // 不能被误清——那是活跃数据，只是记忆条目还没攒出来
            val emptiedPids = Graph.db.memoryDao().allIncludingDeleted()
                .map { it.projectId }.toSet() - activePids
            if (emptiedPids.isEmpty()) return@withContext
            val now = AppTime.nowMillis()
            Graph.db.memorySceneDao().all()
                .filter { (it.invalidAt == 0L || it.invalidAt > now) && it.projectId in emptiedPids }
                .forEach { Graph.db.memorySceneDao().invalidate(it.id, now) }
        }
    }

    suspend fun searchByText(q: String): List<MemoryEntity> = withContext(Dispatchers.IO) {
        val term = q.trim()
        if (term.isEmpty()) return@withContext all()
        val fts = runCatching { dao.ftsSearch(term.replace("\"", "")) }.getOrDefault(emptyList())
        if (fts.isNotEmpty()) fts else dao.likeSearch(term)
    }

    /**
     * 无嵌入检索打分（mem0/plan v3 公式）：
     * score = 0.5·sig(匹配序) + 0.25·exp(-days/30) + 0.15·importance + 0.1·log(1+access) + 0.1·inIndex
     * v8 项目作用域：projectScope>0 时仅召回 全局(projectId=0)+工作层+该项目的记忆；项目命中加权 +0.15
     */
    suspend fun recall(query: String, topK: Int = 3, projectScope: Long = 0): List<MemoryEntity> = withContext(Dispatchers.IO) {
        val terms = tokenize(query)
        if (terms.isEmpty()) return@withContext emptyList()
        // v45 P4：双路 FTS + RRF 融合——标题路（name/description，更能说明「关于什么」）
        // 与正文路（body）分开召回再融合，单一路径会把「标题沾边」和「正文相关」混在一个序里。
        // 两路都空才退 LIKE（中文退化路径）。
        val titleList = LinkedHashMap<Long, MemoryEntity>()
        val bodyList = LinkedHashMap<Long, MemoryEntity>()
        terms.forEach { t ->
            val tHits = runCatching { dao.ftsSearch("name:$t* OR description:$t*") }.getOrDefault(emptyList())
            val bHits = runCatching { dao.ftsSearch("body:$t*") }.getOrDefault(emptyList())
            if (tHits.isEmpty() && bHits.isEmpty()) {
                dao.likeSearch(t).forEach { m -> titleList.putIfAbsent(m.id, m) }
            } else {
                tHits.forEach { m -> titleList.putIfAbsent(m.id, m) }
                bHits.forEach { m -> bodyList.putIfAbsent(m.id, m) }
            }
        }
        val fused = fuseRrf(titleList.values.toList(), bodyList.values.toList())
        val ranked = fused.mapIndexed { i, m -> m to i } // 融合序：越靠前越相关
        val now = AppTime.nowMillis()
        ranked
            .filter { (m, _) ->
                (m.expiration == 0L || m.expiration > now) &&
                    // v45：世界时间已失效 / 已被 merge 取代的，不再召回（行仍在库里）
                    (m.invalidAt == 0L || m.invalidAt > now) &&
                    m.supersededBy == 0L &&
                    recallableInScope(m, projectScope)
            }
            .sortedByDescending { (m, r) ->
                val days = (now - m.updatedAt) / 86_400_000.0
                0.5 * (1.0 / (1 + r)) +
                    0.25 * exp(-days / memoryHalfLifeDays(m.type)) +
                    // v45：priority（0..100，-1=死命令）取代 importance(1..3) 作为重要度项
                    0.15 * memoryPriorityScore(m.priority) +
                    0.1 * ln(1.0 + m.accessCount) +
                    0.1 * (if (m.inIndex) 1.0 else 0.0) +
                    (if (projectScope > 0L && m.projectId == projectScope) 0.15 else 0.0)
            }
            .take(topK)
            .map { it.first }
            .also { hits ->
                if (hits.isNotEmpty()) {
                    dao.bumpAccess(hits.map { it.id }, now)
                    hits.forEach { dao.insertHistory(MemoryHistory(memoryId = it.id, action = "RECALL", detail = query.take(80), at = now)) }
                }
            }
    }

    /** 检索命中的一跳扩展（wikilink 邻居） */
    suspend fun expandOneHop(mems: List<MemoryEntity>): List<MemoryEntity> {
        val extra = mutableListOf<MemoryEntity>()
        val seen = mems.map { it.id }.toMutableSet()
        mems.forEach { m ->
            dao.linkedFrom(m.id).forEach { n -> if (seen.add(n.id)) extra += n }
        }
        return extra
    }

    /** 注入块：工作层索引行 + 检索命中全文（一跳扩展）；projectScope 限定项目记忆召回范围 */
    suspend fun injectionBlock(recallQuery: String, projectScope: Long = 0): String {
        val working = workingLayer()
        val totalWorking = runCatching { countWorking() }.getOrDefault(working.size)

        // I2：索引行单条截断（description 已经 ≤120 字，但 body 兜底那 40 字可能很长）
        val idx = working.joinToString("\n") {
            val line = "- [${it.type}] ${it.name}：${it.description.ifBlank { it.body.take(40) }}"
            if (line.length > 140) line.take(140) + "…" else line
        }

        val recalled = if (recallQuery.isBlank()) emptyList() else recall(recallQuery, 3, projectScope)
        val expanded = expandOneHop(recalled).filter { e -> working.none { it.id == e.id } }
        val hits = (recalled + expanded).distinctBy { it.id }

        // I2：全文也要有上限。body 没有硬上限（工具/用户都能写很长），
        // 3 条命中 × 无上限正文足以挤爆窗口。
        val fullBuilder = StringBuilder()
        var used = 0
        var shown = 0
        for (m in hits) {
            val body = if (m.body.length > INJECT_BODY_CHARS) m.body.take(INJECT_BODY_CHARS) + "…" else m.body
            val piece = "【${m.name}】$body"
            if (used + piece.length > INJECT_MAX_CHARS && shown > 0) break
            if (fullBuilder.isNotEmpty()) fullBuilder.append("\n\n")
            fullBuilder.append(piece)
            used += piece.length
            shown++
        }
        val hiddenHits = hits.size - shown
        val hiddenWorking = (totalWorking - working.size).coerceAtLeast(0)

        if (idx.isBlank() && fullBuilder.isEmpty()) return ""
        return buildString {
            if (idx.isNotBlank()) {
                append("长期记忆索引（详细内容可询问）：\n").append(idx)
                if (hiddenWorking > 0) append("\n（还有 $hiddenWorking 条常驻记忆未展示）")
            }
            if (fullBuilder.isNotEmpty()) {
                if (isNotEmpty()) append("\n\n")
                append("检索命中的记忆全文：\n").append(fullBuilder)
                if (hiddenHits > 0) append("\n（另有 $hiddenHits 条命中未展开，可再次检索）")
            }
        }
    }

    /** 内容指纹（仅用于去重，非安全用途） */
    fun bodyHash(body: String): String =
        MessageDigest.getInstance("SHA-256").digest(body.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    companion object {
        /**
         * 双路 FTS 的 RRF 融合（ReMe `steps/index/search.py:55-86` / TencentDB
         * `store/search-utils.ts:18-62` 同款：k=60 抑制名次差，0.7 权重偏标题路——
         * 名字/描述命中比正文命中更能说明「这条记忆是关于什么的」）。
         * 只在一一路命中的条目照常参与排序（贡献单路分），不会被丢弃。
         */
        internal fun fuseRrf(
            title: List<MemoryEntity>,
            body: List<MemoryEntity>,
            k: Int = 60,
            titleWeight: Double = 0.7,
        ): List<MemoryEntity> {
            if (title.isEmpty()) return body
            if (body.isEmpty()) return title
            val score = HashMap<Long, Double>()
            val byId = HashMap<Long, MemoryEntity>()
            title.forEachIndexed { i, m ->
                score.merge(m.id, titleWeight / (k + i + 1), Double::plus)
                byId[m.id] = m
            }
            body.forEachIndexed { i, m ->
                score.merge(m.id, (1 - titleWeight) / (k + i + 1), Double::plus)
                byId.putIfAbsent(m.id, m)
            }
            return score.entries.sortedByDescending { it.value }.map { byId.getValue(it.key) }
        }

        /** 检索分词：ASCII 词 + CJK 连续串拆二元组（FTS4 对中文不分词） */
        fun tokenize(query: String, maxTerms: Int = 8): List<String> {
            val out = mutableListOf<String>()
            Regex("[A-Za-z0-9_]{2,}").findAll(query).forEach { out += it.value.lowercase() }
            Regex("[\\u2E80-\\u9FFF\\u3000-\\u303F]+").findAll(query).forEach { run ->
                val s = run.value
                if (s.length == 1) out += s
                else for (i in 0 until s.length - 1) out += s.substring(i, i + 2)
            }
            return out.distinct().take(maxTerms)
        }
    }
}
