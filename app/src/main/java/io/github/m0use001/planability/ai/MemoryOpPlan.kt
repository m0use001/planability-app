package io.github.m0use001.planability.ai

import io.github.m0use001.planability.data.MemoryEntity
import io.github.m0use001.planability.data.MemoryRepo
import org.json.JSONArray

/**
 * v45 P2：记忆写入的**四态决策**（TencentDB L1 conflict detection 的落地方案）。
 *
 * 为什么拆成「纯函数规划器 + 薄执行层」：
 * 四态判定（STORE/SKIP/UPDATE/MERGE）全是业务规则，不难但容易写错；如果直接长在
 * `applyOps` 里，它必须连 Room 才能测，而本项目 androidTest 被 debug R8 卡着。
 * 拆出来之后这段逻辑能在纯 JVM 上跑全量用例，执行层只剩下「按计划插/改/立墓碑」。
 *
 * 关键语义（照 TencentDB `l1-dedup.ts:34-47` 与 Graphiti 的失效不删）：
 * - MERGE 不是删除：幸存者更新为一整条，其余被合并者只写 `supersededBy/invalidAt/expiredAt`，
 *   行保留、可追溯；
 * - 名称冲突一律按「已有记忆」处理，绝不因为模型换了个名字就多长一条；
 * - 模型给了不存在的 target → 降级为 STORE（宁可多记，不丢）；
 * - LLM 输出非法/候选为空 → 全部 STORE（调用方保证）。
 */
internal data class MemoryOp(
    val op: String,
    val name: String,
    val type: String,
    val kind: String,
    val description: String,
    val body: String,
    /** null = 模型没给，更新时保留旧值 */
    val priority: Int?,
    /** null/空 = 模型没给，更新时保留旧值 */
    val scene: String?,
    val sourceIds: List<Long>,
    /** 目标已有记忆名（UPDATE/DELETE 用第一个，MERGE 用全部） */
    val targets: List<String>,
)

internal data class PlannedWrite(
    val entity: MemoryEntity,
    /** 这条记忆的证据消息 id（落库后写 memory_evidence） */
    val evidence: List<Long>,
)

/**
 * 内容指纹：body SHA-256 的前 6 位 hex。
 *
 * 为什么用它当引用句柄而不是 id/名字：id 是模型容易幻觉编造的数字；
 * 名字是语义标签会抄错行；**指纹绑定正文**——模型只能从清单里抄，抄错一位就解析不到，
 * 解析不到就降级 STORE（无损）。B 方案「内容绑定引用」的核心。
 */
internal fun bodyFingerprint(body: String): String =
    java.security.MessageDigest.getInstance("SHA-256")
        .digest(body.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
        .take(6)

internal data class MemoryApplyPlan(
    val stores: List<PlannedWrite> = emptyList(),
    val updates: List<PlannedWrite> = emptyList(),
    /** (被取代的旧记忆 id, 取代它的记忆 id) */
    val superseded: List<Pair<Long, Long>> = emptyList(),
    val deletes: List<Long> = emptyList(),
    val skipped: Int = 0,
    val blocked: Int = 0,
    /** 主题错配被降级为 STORE 的 UPDATE/MERGE 数（审计用） */
    val diverted: Int = 0,
) {
    val writes: Int get() = stores.size + updates.size

    /** 所有写入引用的证据消息 id 并集——进度推进粒度依赖它（只消费被写到的轮次）。 */
    val evidenceIds: List<Long>
        get() = (stores + updates).flatMap { it.evidence }.distinct()
}

/** 模型没给 priority 时按 kind 给一个合理默认（0..100）。 */
internal fun defaultPriorityForKind(kind: String): Int = when (kind) {
    "fact" -> 70
    "episode" -> 60
    "procedure" -> 75
    "draft" -> 40
    else -> 50
}

/**
 * 解析模型输出的 JSON 数组。任何单条非法只跳过那一条，不让整批失败——
 * 一批里一条脏数据导致整轮记忆丢失，比少记一条糟得多。
 */
internal fun parseMemoryOps(arr: JSONArray): List<MemoryOp> {
    val out = ArrayList<MemoryOp>(arr.length())
    for (i in 0 until arr.length()) {
        val o = arr.optJSONObject(i) ?: continue
        val name = o.optString("name", "").trim().take(40)
        if (name.isBlank()) continue
        val kind = o.optString("kind", "").trim().lowercase()
        val type = MemoryConsolidator.normalizeType(o.optString("type", ""), kind)
        val rawOp = o.optString("op", "STORE").trim().uppercase()
        val op = when (rawOp) {
            "ADD" -> "STORE"
            "STORE", "SKIP", "UPDATE", "MERGE", "DELETE" -> rawOp
            else -> "STORE"
        }
        val sourceIds = buildList {
            o.optJSONArray("source_message_ids")?.let { a ->
                for (j in 0 until a.length()) {
                    val id = a.optLong(j, 0L)
                    if (id > 0L) add(id)
                }
            }
        }.distinct().take(20)
        val targets = buildList {
            o.optJSONArray("target_names")?.let { a ->
                for (j in 0 until a.length()) {
                    val t = a.optString(j, "").trim()
                    if (t.isNotBlank()) add(t)
                }
            }
            // UPDATE 单目标时模型可能直接写 target_name
            o.optString("target_name", "").trim().takeIf { it.isNotBlank() }?.let { add(it) }
        }.distinct()
        out += MemoryOp(
            op = op, name = name, type = type, kind = kind,
            description = o.optString("description", "").trim().take(120),
            body = o.optString("body", "").trim().take(MEMORY_BODY_MAX),
            priority = if (o.has("priority")) o.optInt("priority", defaultPriorityForKind(kind))
                .coerceIn(-1, 100) else null,
            scene = o.optString("scene", "").trim().take(60).ifBlank { null },
            sourceIds = sourceIds,
            targets = targets,
        )
    }
    return out
}

/**
 * 把一批 MemoryOp 变成可执行计划。纯函数：不碰 IO、不读时间（now 由调用方传）。
 *
 * [scoped] 必须已经按项目作用域过滤过（全局 ∪ 本项目），否则会误改别的项目的同名记忆。
 */
internal fun planMemoryOps(
    ops: List<MemoryOp>,
    scoped: List<MemoryEntity>,
    projectId: Long,
    sessionId: Long,
    now: Long,
    fullReview: Boolean,
): MemoryApplyPlan {
    val stores = ArrayList<PlannedWrite>()
    val updates = ArrayList<PlannedWrite>()
    val superseded = ArrayList<Pair<Long, Long>>()
    val deletes = ArrayList<Long>()
    var skipped = 0
    var blocked = 0
    var diverted = 0
    var applied = 0

    // 同作用域内同名记忆：STORE 命中它 = 覆盖（沿用旧行为，避免模型换个名字就重复一条）
    val byName = scoped.associateBy { it.name }
    val hashSeen = scoped.map { it.body }.toMutableSet()
    // v45.2 B 方案：引用解析顺序 数字id → #指纹 → 名字。
    // 指纹绑定正文（抄错一位就解析不到 → 降级 STORE，无损），比语义名字可靠。
    val byIdNum = scoped.associateBy { it.id }
    val byFingerprint = scoped.associateBy { bodyFingerprint(it.body) }

    fun resolveTargets(op: MemoryOp): List<MemoryEntity> =
        op.targets.mapNotNull { t ->
            val key = t.trim().removePrefix("#")
            byIdNum[key.toLongOrNull()] ?: byFingerprint[key] ?: byName[t.trim()]
        }.distinctBy { it.id }

    fun guardSensitive(body: String): Boolean {
        val hit = looksSensitive(body)
        if (hit != null) {
            blocked++
            return false
        }
        return true
    }

    for (op in ops) {
        if (applied >= MEMORY_MAX_OPS) break
        val targets = resolveTargets(op)
        when (op.op) {
            "SKIP" -> skipped++
            "DELETE" -> {
                val t = targets.firstOrNull()
                // 自动整理不许删：只有用户显式要求或手动完整整理才删（rikkahub 同款）
                if (t == null || !fullReview || t.type == "handoff") {
                    blocked++
                } else {
                    deletes += t.id
                    applied++
                }
            }
            "UPDATE", "MERGE" -> {
                val survivor = targets.firstOrNull()
                if (survivor == null) {
                    // 模型指了不存在的目标：退化为 STORE，不丢内容
                    val stored = storeOrUpdate(
                        op, byName, projectId, sessionId, now, hashSeen,
                        ::guardSensitive
                    )
                    if (stored != null) {
                        if (stored.second) updates += stored.first else stores += stored.first
                        applied++
                    }
                } else {
                    val body = op.body.ifBlank { survivor.body }
                    if (!guardSensitive(body)) continue
                    if (body.isBlank()) continue
                    // v45.1 主题错配防护（真机实证：模型把作息内容 UPDATE 进了「语言偏好」，
                    // 硬规则被毁）。新内容与目标正文**连一个词都不重叠**时，大概率是点错了行。
                    // 改写不可逆地毁掉旧记忆，而降级为 STORE 是无损的，所以宁降不误。
                    if (looksUnrelated(survivor.body, body)) {
                        diverted++
                        val filtered = byName - targets.map { it.name }.toSet()
                        val stored = storeOrUpdate(
                            op, filtered, projectId, sessionId, now, hashSeen,
                            ::guardSensitive
                        )
                        if (stored != null) {
                            if (stored.second) updates += stored.first else stores += stored.first
                            applied++
                        }
                        continue
                    }
                    // 合并时取所有目标里最早的 validAt（事实开始为真的时间），保持时间线
                    val validAt = (targets.map { it.validAt }.filter { it > 0 }.minOrNull()
                        ?: survivor.validAt)
                    val merged = survivor.copy(
                        description = op.description.ifBlank { survivor.description },
                        body = body,
                        type = op.type,
                        priority = op.priority ?: survivor.priority,
                        scene = op.scene ?: survivor.scene,
                        version = survivor.version + 1,
                        validAt = validAt,
                        invalidAt = 0,
                        expiredAt = 0,
                        supersededBy = 0,
                        updatedAt = now,
                    )
                    updates += PlannedWrite(merged, op.sourceIds)
                    if (op.op == "MERGE") {
                        targets.drop(1).forEach { superseded += it.id to survivor.id }
                    }
                    applied++
                }
            }
            else -> { // STORE
                val stored = storeOrUpdate(
                    op, byName, projectId, sessionId, now, hashSeen,
                    ::guardSensitive
                )
                if (stored != null) {
                    if (stored.second) updates += stored.first else stores += stored.first
                    applied++
                }
            }
        }
    }
    return MemoryApplyPlan(
        stores, updates, superseded, deletes, skipped, blocked, diverted
    )
}

/**
 * 主题错配判定：两段正文分词后**没有任何交集**。
 *
 * 用 [MemoryRepo.tokenize]（ASCII 词 + CJK 二元组）——它是检索同款分词，天然对中文友好。
 * 只有双方都分出词才判「无关」，空串/纯标点不触发（避免误伤短正文）。
 * 误判的代价是「本该 UPDATE 却 STORE 了一条新记忆」——无损；漏判的代价是毁掉一条旧记忆。
 */
private fun looksUnrelated(existingBody: String, newBody: String): Boolean {
    val a = MemoryRepo.tokenize(existingBody, 100).toSet()
    val b = MemoryRepo.tokenize(newBody, 100).toSet()
    if (a.isEmpty() || b.isEmpty()) return false
    return a.intersect(b).isEmpty()
}

/**
 * STORE 的统一出口：同名 → 覆盖更新；否则新建。
 * 返回 (写入, 是否更新已有)；返回 null 表示被敏感信息或去重拦下。
 */
private fun storeOrUpdate(
    op: MemoryOp,
    byName: Map<String, MemoryEntity>,
    projectId: Long,
    sessionId: Long,
    now: Long,
    hashSeen: MutableSet<String>,
    guardSensitive: (String) -> Boolean,
): Pair<PlannedWrite, Boolean>? {
    if (op.body.isBlank()) return null
    if (!guardSensitive(op.body)) return null
    if (!hashSeen.add(op.body)) return null // 完全重复
    val sameName = byName[op.name]
    if (sameName != null) {
        val merged = sameName.copy(
            description = op.description.ifBlank { sameName.description },
            body = op.body,
            type = op.type,
            priority = op.priority ?: sameName.priority,
            scene = op.scene ?: sameName.scene,
            version = sameName.version + 1,
            updatedAt = now,
        )
        return PlannedWrite(merged, op.sourceIds) to true
    }
    val entity = MemoryEntity(
        name = op.name,
        type = op.type,
        description = op.description,
        body = op.body,
        inIndex = false,
        importance = if (op.kind == "fact") 2 else 1,
        priority = op.priority ?: defaultPriorityForKind(op.kind),
        scene = op.scene.orEmpty(),
        validAt = now,
        version = 1,
        originSession = sessionId,
        projectId = projectId,
        createdAt = now,
        updatedAt = now,
    )
    return PlannedWrite(entity, op.sourceIds) to false
}

// ───────────────── v45.2 A 方案：两段式冲突检测 ─────────────────
//
// 抽取调用只出候选（不带冲突判断），冲突调用把**候选池全文**喂给模型再判四态
// （TencentDB l1-dedup 同构：模型看得见要改写的正文，是它家 PersonaMem 48%→76% 的工程前提）。
// 本文件只放纯函数（候选池构造/解析/映射），LLM 调用在 MemoryConsolidator。

/** 候选池条目的模型视图：#指纹 + 正文（截断后），模型引用 #指纹 指向它。 */
internal data class PoolView(
    val fingerprint: String,
    val id: Long,
    val name: String,
    val type: String,
    val priority: Int,
    val scene: String,
    /** 已格式化的更新时间（由调用方格式化，纯函数不碰时钟） */
    val updatedText: String,
    val body: String,
)

/** 新候选的模型视图。 */
internal data class CandidateView(
    val cid: String,
    val content: String,
    val type: String,
    val priority: Int,
    val scene: String,
    val related: List<String>,
)

/** 候选池构造结果：统一池 + 每条新候选各自的关联候选指纹（topK，按融合序）。 */
internal data class CandidatePoolResult(
    val pool: List<PoolView>,
    /** 与 candidates 一一对应；每项是该候选检索到的近邻 #指纹 */
    val related: List<List<String>>,
)

/**
 * 构造候选池：[retrieved] 是**调用方已取回**的每候选检索结果（与 candidates 一一对应；
 * 检索本身要碰 Room，留在 Consolidator），这里只做纯组装——过滤到 [scoped] 作用域与有效行，
 * 跨候选去重成统一池、截到 [maxPool] 条。相关列表里指向被截条目的指纹一并过滤，保证引用都可解析。
 */
internal fun buildCandidatePool(
    candidates: List<MemoryOp>,
    scoped: List<MemoryEntity>,
    retrieved: List<List<MemoryEntity>>,
    updatedTextOf: (Long) -> String,
    maxPool: Int = 12,
): CandidatePoolResult {
    if (candidates.isEmpty()) return CandidatePoolResult(emptyList(), emptyList())
    val scopedIds = scoped.map { it.id }.toSet()
    val unified = LinkedHashMap<Long, MemoryEntity>()
    val related = ArrayList<List<String>>(candidates.size)
    for (i in candidates.indices) {
        val hits = retrieved.getOrNull(i).orEmpty()
            .filter { it.id in scopedIds && (it.invalidAt == 0L) && it.supersededBy == 0L }
        related += hits.map { bodyFingerprint(it.body) }
        hits.forEach { m -> unified.putIfAbsent(m.id, m) }
    }
    val pool = unified.values.take(maxPool).map { m ->
        PoolView(
            fingerprint = bodyFingerprint(m.body),
            id = m.id, name = m.name, type = m.type,
            priority = m.priority, scene = m.scene,
            updatedText = updatedTextOf(m.updatedAt),
            body = m.body.take(400),
        )
    }
    // 统一池截断后，相关列表里可能指向被截掉的条目 → 过滤，保证引用都可解析
    val poolFps = pool.map { it.fingerprint }.toSet()
    return CandidatePoolResult(
        pool,
        related.map { ids -> ids.filter { fp -> poolFps.contains(fp) } },
    )
}

/** 冲突调用的一条决策。 */
internal data class ConflictDecision(
    val cid: String,
    val action: String,
    val targetIds: List<String>,
    val content: String,
    val type: String?,
    val priority: Int?,
    val scene: String?,
)

/**
 * 解析冲突调用输出。cid 对不上候选、action 非法 → 该条忽略（映射阶段按 store 兜底）。
 */
internal fun parseConflictOps(arr: JSONArray): List<ConflictDecision> {
    val out = ArrayList<ConflictDecision>(arr.length())
    for (i in 0 until arr.length()) {
        val o = arr.optJSONObject(i) ?: continue
        val cid = o.optString("cid", "").trim()
        if (cid.isBlank()) continue
        val action = o.optString("action", "store").trim().lowercase()
        val targets = buildList {
            o.optJSONArray("target_ids")?.let { a ->
                for (j in 0 until a.length()) {
                    val t = a.optString(j, "").trim()
                    if (t.isNotBlank()) add(t)
                }
            }
        }.distinct()
        out += ConflictDecision(
            cid = cid,
            action = action,
            targetIds = targets,
            content = o.optString("content", "").trim().take(MEMORY_BODY_MAX),
            type = o.optString("type", "").trim().takeIf { it.isNotBlank() },
            priority = if (o.has("priority") && !o.isNull("priority"))
                o.optInt("priority", 50).coerceIn(-1, 100) else null,
            scene = o.optString("scene", "").trim().takeIf { it.isNotBlank() },
        )
    }
    return out
}

/**
 * 决策 → MemoryOp。未知 cid / 非法 action 一律按 store 兜底（宁多记，不丢）。
 * B+ 子集判定在这里做：decision 是 store、但新正文分词集 ⊆ 某个**关联候选**正文 →
 * 无增量，降级 SKIP（只对检索到的近邻比，不对全库比——Honcho richness 的无嵌入近似，
 * 避免跨主题误杀）。
 */
internal fun mapConflictToOps(
    decisions: List<ConflictDecision>,
    candidates: List<MemoryOp>,
    pool: List<PoolView>,
): List<MemoryOp> {
    if (decisions.isEmpty()) return candidates
    val byCid = candidates.associateBy { "N${candidates.indexOf(it) + 1}" }
    val poolByFp = pool.associateBy { it.fingerprint }
    val out = ArrayList<MemoryOp>(candidates.size)
    val decided = mutableSetOf<String>()
    for (d in decisions) {
        val cand = byCid[d.cid] ?: continue
        decided += d.cid
        val action = when (d.action) {
            "store", "skip", "update", "merge" -> d.action
            else -> "store"
        }
        when (action) {
            "skip" -> out += cand.copy(op = "SKIP", targets = d.targetIds)
            "update", "merge" -> {
                val resolved = d.targetIds.mapNotNull { fp -> poolByFp[fp.removePrefix("#")]?.id }
                if (resolved.isEmpty()) {
                    out += cand.copy(op = "STORE")   // 指不到目标：降级 STORE，无损
                } else {
                    out += cand.copy(
                        op = action.uppercase(),
                        body = d.content.ifBlank { cand.body },
                        type = d.type ?: cand.type,
                        priority = d.priority ?: cand.priority,
                        scene = d.scene ?: cand.scene,
                        targets = resolved.map { it.toString() },
                    )
                }
            }
            else -> { // store
                val tokens = MemoryRepo.tokenize(d.content.ifBlank { cand.body }, 200).toSet()
                val subsumed = tokens.isNotEmpty() && d.targetIds.any { fp ->
                    val p = poolByFp[fp.removePrefix("#")]
                    p != null && MemoryRepo.tokenize(p.body, 200).toSet().containsAll(tokens)
                }
                out += cand.copy(op = if (subsumed) "SKIP" else "STORE", targets = d.targetIds)
            }
        }
    }
    // 决策没覆盖到的候选（模型漏答）→ store 兜底
    candidates.forEachIndexed { i, c ->
        val cid = "N${i + 1}"
        if (cid !in decided) out += c.copy(op = "STORE")
    }
    return out
}
