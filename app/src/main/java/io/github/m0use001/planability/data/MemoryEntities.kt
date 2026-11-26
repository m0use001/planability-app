package io.github.m0use001.planability.data

import io.github.m0use001.planability.core.AppTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import kotlin.math.exp
import kotlin.math.ln

// M5 KMP 化：MemoryEntity/MemoryEvidence/MemoryScene/MemoryPersona/MemoryFts/MemoryLink/MemoryHistory
// 实体与 MemoryDao/MemoryEvidenceDao/MemorySceneDao/MemoryPersonaDao 已迁 `:shared`（jvmShared，
// Room 真源），同包跨模块直解。本文件只留纯评分函数与 MemoryRepo（Graph 单例/镜像落盘/Keystore 无关但
// 依赖 MemoryFiles/Graph，Phase 2 再拆）。

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
 * 2026-11-26 收紧（小说会话事故）：原版 `scope<=0 = 全库` + `handoff 跨项目可见`，
 * 结果是小说(9)会话的 memory_search / 每轮注入都能看到别的项目的 handoff（军训、线代、
 * 体测…），模型把它们列进「要删的」清单。现行规则：
 * - `scope < 0` = [MEMORY_SCOPE_ALL]：只有助理中枢这类**跨项目汇总**的会话用；
 * - `scope == 0`：无项目会话 → 只看得见全局(0) + 常驻索引(inIndex=1)，不再全库；
 * - `scope > 0`：本项目 + 全局(0) + 常驻索引(inIndex=1)；**handoff 只在本项目内可见**。
 *
 * 「可见」不等于「可删」——删除/作废另走 ToolExecImpls 的严格归属检查。
 */
internal const val MEMORY_SCOPE_ALL: Long = -1L

internal fun recallableInScope(m: MemoryEntity, projectScope: Long): Boolean =
    projectScope < 0L || m.projectId == 0L || m.projectId == projectScope || m.inIndex

/**
 * 一条记忆能否当作**现行事实**使用：未删、未过期、未作废、未被取代，且在会话作用域内。
 *
 * 2026-11-26 二次收紧：原来这套条件只写在 [recall] 的过滤里，**一跳扩展**
 * （[expandOneHop]，wikilink 邻居）完全没过滤——召回命中的新条目如果链到旧设定，
 * 旧设定的全文会绕过作废直接进上下文（小说会话里「已被取代的旧人设反复出现」的另一条路）。
 * 现在召回/注入/一跳扩展共用这一个判据。
 */
internal fun memoryUsable(m: MemoryEntity, now: Long, projectScope: Long): Boolean =
    !m.isDeleted &&
        (m.expiration == 0L || m.expiration > now) &&
        (m.invalidAt == 0L || m.invalidAt > now) &&
        m.supersededBy == 0L &&
        recallableInScope(m, projectScope)

class MemoryRepo(private val version: MutableVersion) {
    private val dao get() = Graph.db.memoryDao()

    suspend fun all(): List<MemoryEntity> = dao.all()
    suspend fun count(): Int = dao.count()
    suspend fun byId(id: Long): MemoryEntity? = dao.byId(id)
    suspend fun workingLayer(
        limit: Int = WORKING_LIMIT,
        now: Long = AppTime.nowMillis(),
        projectScope: Long = MEMORY_SCOPE_ALL
    ): List<MemoryEntity> = dao.workingLayer(limit, now, projectScope)

    suspend fun countWorking(
        now: Long = AppTime.nowMillis(),
        projectScope: Long = MEMORY_SCOPE_ALL
    ): Int = dao.countWorking(now, projectScope)

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
        // wikilink 解析：[[name]] → 同名记忆（2026-11-26：按项目可见范围解析，
        // 原来 dao.byName 是全局 LIMIT 1，链接可能指到别的项目的同名条目）
        dao.clearLinks(id)
        Regex("\\[\\[([^\\[\\]]+)]]").findAll(m.body + " " + m.description).forEach { mr ->
            val target = dao.byNameVisible(mr.groupValues[1].trim(), m.projectId)
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
     * 常驻注入，作废设定反复复提（◆：三轮「我不是早说了…作废了吗？？」）。
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
            .filter { (m, _) -> memoryUsable(m, now, projectScope) }
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

    /**
     * 检索命中的一跳扩展（wikilink 邻居）。
     *
     * 2026-11-26：必须与召回同一套有效性 + 项目作用域过滤——`linkedFrom` 的 SQL 只滤
     * isDeleted，原来会顺着链接把**别的项目**或**已作废**的旧设定全文拉进上下文。
     */
    suspend fun expandOneHop(
        mems: List<MemoryEntity>,
        projectScope: Long = MEMORY_SCOPE_ALL,
        now: Long = AppTime.nowMillis()
    ): List<MemoryEntity> {
        val extra = mutableListOf<MemoryEntity>()
        val seen = mems.map { it.id }.toMutableSet()
        mems.forEach { m ->
            dao.linkedFrom(m.id)
                .filter { n -> memoryUsable(n, now, projectScope) }
                .forEach { n -> if (seen.add(n.id)) extra += n }
        }
        return extra
    }

    /**
     * 注入块：工作层索引行 + 检索命中全文（一跳扩展）；projectScope 限定项目记忆召回范围。
     * 2026-11-26：工作层索引行也按同一作用域过滤（别的项目的 handoff 不再每轮注入）。
     */
    suspend fun injectionBlock(recallQuery: String, projectScope: Long = MEMORY_SCOPE_ALL): String {
        val working = workingLayer(projectScope = projectScope)
        val totalWorking = runCatching { countWorking(projectScope = projectScope) }.getOrDefault(working.size)

        // I2：索引行单条截断（description 已经 ≤120 字，但 body 兜底那 40 字可能很长）
        val idx = working.joinToString("\n") {
            val line = "- [${it.type}] ${it.name}：${it.description.ifBlank { it.body.take(40) }}"
            if (line.length > 140) line.take(140) + "…" else line
        }

        val recalled = if (recallQuery.isBlank()) emptyList() else recall(recallQuery, 3, projectScope)
        val expanded = expandOneHop(recalled, projectScope).filter { e -> working.none { it.id == e.id } }
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
