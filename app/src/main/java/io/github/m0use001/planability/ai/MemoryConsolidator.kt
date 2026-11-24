package io.github.m0use001.planability.ai

import io.github.m0use001.planability.core.AppTime
import io.github.m0use001.planability.data.Graph
import io.github.m0use001.planability.data.MemoryEntity
import io.github.m0use001.planability.data.MemoryLayers
import io.github.m0use001.planability.data.MemoryRepo

/**
 * v36 B 项第三步：**记忆整理器本体**（rikkahub `MemoryConsolidator` 的等价物）。
 *
 * 输入是若干个会话里"上次整理之后新增的用户轮次"，输出是最多 [MEMORY_MAX_OPS] 条写入。
 * 由 [MemoryConsolidationScheduler] 的 execute() 调，也被 MemoryScreen 的"立即整理"直接调。
 *
 * ## 与原来每轮抽取的区别
 * - 原来：每轮一次 LLM 调用，失败静默丢弃；
 * - 现在：攒够轮次或空闲/切后台/重启才跑，**失败重试一次**再记 EXTRACT_FAIL，
 *   模型返回 [] 记 EXTRACT_EMPTY（"判定没值得记"和"链路断了"必须能区分）。
 * - 删除只在两种情况下发生：用户显式要求忘记、或手动"完整整理"；自动整理默认只增改。
 *   来源: rikkahub/data/ai/MemoryConsolidator.kt:160-163。
 */
object MemoryConsolidator {

    data class RunResult(
        val sessions: Int = 0,
        val turns: Int = 0,
        val added: Int = 0,
        val updated: Int = 0,
        val deleted: Int = 0,
        val blocked: Int = 0,
        /** 模型主动判定"与已有记忆重复、本次不写"的候选数（不是失败，也不是拦截）。 */
        val skipped: Int = 0,
        val empty: Boolean = false,
        val error: String? = null,
    ) {
        val hasChanges: Boolean get() = added + updated + deleted > 0
    }

    /** 手动整理时把整个记忆库也清理一遍（自动整理不做，见类注释）。 */
    suspend fun run(sessionIds: List<Long>, fullReview: Boolean = false): RunResult {
        if (sessionIds.isEmpty()) return RunResult(empty = true)
        var result = RunResult(sessions = sessionIds.size)
        sessionIds.forEach { id ->
            val outcome = consolidateOne(id, fullReview)
            result = result.copy(
                turns = result.turns + outcome.turns,
                added = result.added + outcome.added,
                updated = result.updated + outcome.updated,
                deleted = result.deleted + outcome.deleted,
                blocked = result.blocked + outcome.blocked,
                skipped = result.skipped + outcome.skipped,
                empty = result.empty && outcome.empty,
                error = outcome.error ?: result.error,
            )
        }
        val lastId = sessionIds.last()
        if (result.hasChanges || fullReview) rewriteHandoff(lastId)
        // P3：画像按次数触发（默认每 50 次整理重建一次；失败不阻塞整理）
        runCatching { PersonaBuilder.onConsolidated() }
        return result
    }

    private data class Outcome(
        val turns: Int = 0,
        val added: Int = 0,
        val updated: Int = 0,
        val deleted: Int = 0,
        val blocked: Int = 0,
        val skipped: Int = 0,
        /** 本批里**被写入记忆引用过**的最晚用户轮次；null = 无法归因（按整批推进）。 */
        val coveredThrough: Long? = null,
        val empty: Boolean = true,
        val error: String? = null,
    )

    private suspend fun consolidateOne(sessionId: Long, fullReview: Boolean): Outcome {
        val session = Graph.chat.session(sessionId) ?: return Outcome(error = "会话不存在")
        // v54：助理中枢（私人助理会话）是用户画像的第一描绘人——用户在这里聊出的偏好/
        // 习惯/教训必须落**全局**（projectId=0），而不是锁进「助理中枢」项目作用域；
        // 否则别的项目召回不到、画像重建也看不见。写作用域与同名匹配范围都按全局算。
        val memProjectId = runCatching {
            val isHub = session.assistantId > 0 &&
                Graph.assistants.byId(session.assistantId)?.key == io.github.m0use001.planability.data.AssistantRepo.KEY_HUB
            if (isHub) 0L else session.projectId
        }.getOrDefault(session.projectId)
        val msgs = Graph.chat.messagesOf(sessionId).filter {
            it.id > session.clearBefore && it.role in listOf("user", "assistant") && it.content.isNotBlank()
        }
        if (msgs.isEmpty()) return Outcome()
        val lite = msgs.map { MemoryConsolidationScheduler.MsgLite(it.id, it.role, it.createdAt, it.content) }
        val consolidatedAt = Graph.memoryProgress.of(sessionId)
            ?: MemoryConsolidationScheduler.initialProgress(lite)
        val pending = MemoryConsolidationScheduler.pendingTurnsOf(lite, consolidatedAt, session.updatedAt)
        if (pending.turns.isEmpty()) return Outcome()

        // 转录预算：用户消息 8000 字符、助手回复 2000（rikkahub 同款，:57-58）。
        // 2026-11-10 真机（手机持续发烫）：只有单条上限、没有总量上限——采集提纯写出的
        // 「信息提取·」会话动辄几百轮，转录轻松破万字符，端侧 4096 ctx 必然
        // "Input prompt too long"；而失败路径不推进进度 → 会话永远待整理 →
        // 每次切后台/启动都重跑（每 3 秒一次失败 ×2 并发，localai CPU 262%）。
        // 这里加总量封顶：保留最近的轮次，超预算的旧轮次丢弃。
        val rawTurns = pending.turns.joinToString("\n\n") { turn ->
            turn.messages.joinToString("\n") { m ->
                val cap = if (m.role == "user") 8000 else 2000
                val text = if (m.content.length > cap) m.content.take(cap) + "…" else m.content
                "[${if (m.role == "user") "用户" else "助手"} #${m.id}] $text"
            }
        }
        val budget = inputBudget()
        val transcript = if (rawTurns.length > budget) {
            // 从最旧的方向砍：保留最近的轮次（最新上下文价值最高）
            val kept = StringBuilder()
            for (turn in pending.turns.reversed()) {
                val block = turn.messages.joinToString("\n") { m ->
                    val cap = if (m.role == "user") 8000 else 2000
                    val text = if (m.content.length > cap) m.content.take(cap) + "…" else m.content
                    "[${if (m.role == "user") "用户" else "助手"} #${m.id}] $text"
                }
                if (kept.isNotEmpty() && kept.length + block.length + 2 > budget) break
                if (kept.isNotEmpty()) kept.append("\n\n")
                kept.append(block)
            }
            MemoryPipeline.note(sessionId, "EXTRACT_TRUNCATED", "转录超预算，截到最近 $budget 字符")
            kept.toString()
        } else rawTurns
        val existing = Graph.memories.all().filter { it.type != "handoff" }
        val listing = memoryListing(existing)
        val now = AppTime.now()
        val input = buildConsolidationInput(
            listing = listing,
            turnsText = transcript,
            todayHint = "${now.year}-${now.monthValue}-${now.dayOfMonth}",
        )

        var lastChatError: String? = null
        val raw = runCatching { AiClient.chatWith(AiClient.usableWorkerConfig(), buildMemorySystemPrompt(), input, 0.2) }
            .onFailure {
                lastChatError = it.message
                android.util.Log.e("MemoryConsolidator", "chatWith failed s=$sessionId", it)
                MemoryPipeline.note(sessionId, "EXTRACT_FAIL", "chatWith: " + (it.message ?: it.javaClass.simpleName))
            }
            .getOrNull()
        // 失败重试一次：瞬时网络/限流就让它再试一回，仍失败才留证据离开
        val retried = raw ?: runCatching {
            AiClient.chatWith(AiClient.usableWorkerConfig(), buildMemorySystemPrompt(), input, 0.2)
        }.onFailure {
            MemoryPipeline.note(sessionId, "EXTRACT_FAIL", "重试仍失败: " + (it.message ?: it.javaClass.simpleName))
        }.getOrNull()
        if (retried == null) {
            // 2026-11-10 真机（localai 262% CPU 烧穿）：「Input prompt too long」是**确定性失败**
            // （端侧 4096 ctx 装不下），重试一百次也一样。原失败路径不推进进度 → 会话永远
            // 待整理 → 每次切后台/启动都重新跑。处理：识别后推进进度跳过本会话（证据记
            // memory_history），调度器不因此卡死；其他失败（网络/忙）维持原样不推进。
            val why = (lastChatError ?: "").lowercase()
            if ("too long" in why || "prompt" in why) {
                advance(sessionId, pending.consolidatedThrough ?: session.updatedAt)
                MemoryPipeline.note(sessionId, "EXTRACT_SKIP_LONG", "输入超端侧上下文，已跳过本会话（进度已推进）")
                return Outcome(turns = pending.totalTurns)
            }
            return Outcome(turns = pending.totalTurns, error = "模型调用失败")
        }

        val ops = runCatching { AiClient.parseJsonArray(retried) }
            .onFailure {
                MemoryPipeline.note(sessionId, "EXTRACT_FAIL", "parse: " + retried.take(160))
            }
            .getOrNull()
        if (ops == null) return Outcome(turns = pending.totalTurns, error = "解析失败")
        if (ops.length() == 0) {
            MemoryPipeline.note(sessionId, "EXTRACT_EMPTY", "模型返回空数组（判定没有值得记的）")
            // 没有可写内容也要推进进度，否则这个会话每次都被重新读一遍
            advance(sessionId, pending.consolidatedThrough ?: session.updatedAt)
            return Outcome(turns = pending.totalTurns)
        }
        // A 方案：抽取调用只出候选（一律按 STORE 进入下一阶段），冲突判定在第二次调用
        val candidates = parseMemoryOps(ops).map { it.copy(op = "STORE") }
        if (candidates.isEmpty()) {
            MemoryPipeline.note(sessionId, "EXTRACT_EMPTY", "候选解析为空")
            advance(sessionId, pending.consolidatedThrough ?: session.updatedAt)
            return Outcome(turns = pending.totalTurns)
        }

        val out = applyOps(sessionId, memProjectId, candidates, existing, pending, fullReview)
        // v45.1：进度只推进到「被写入记忆引用过的最后一个轮次」。
        // 没被引用的轮次留给下一轮——否则 MAX_OPS 没写到的轮次会被永久跳过
        // （真机实证：8 轮只写 2 条，考试时间/考场随进度推进丢失）。
        advance(sessionId, out.coveredThrough ?: (pending.consolidatedThrough ?: session.updatedAt))
        return out
    }

    /** 清单：工作层常驻 + 最近若干条，一行一条（带 #指纹 与正文预览——B 方案内容绑定引用）。 */
    private suspend fun memoryListing(existing: List<MemoryEntity>): String {
        val working = Graph.memories.workingLayer()
        val recent = existing.sortedByDescending { it.updatedAt }.take(20)
        // v45：清单带上 scene，模型才能沿用已有场景名而不是每轮造新名字
        val lines = (working + recent).distinctBy { it.id }.take(30).map {
            "- #${Graph.memories.bodyHash(it.body).take(6)} ${it.name}｜${it.type}｜" +
                "${it.scene.ifBlank { "未分类" }}｜" +
                "${it.description.ifBlank { it.body.take(40) }}｜${it.body.take(60)}"
        }
        return lines.joinToString("\n")
    }

    /**
     * 写入作用域：会话挂在项目下时，同名匹配与正文去重只看「本项目 ∪ 全局」，
     * 否则小说项目整理时会误改/误删全局或别的项目里的同名记忆（2026-10-21 用户踩）。
     * 全局（projectId=0）永远在范围内。
     */
    internal fun scopedMemories(existing: List<MemoryEntity>, projectId: Long): List<MemoryEntity> =
        if (projectId <= 0L) existing
        else existing.filter { it.projectId == 0L || it.projectId == projectId }

    /**
     * 候选池检索：双路 FTS（标题/正文）+ RRF，限定 [scoped] 作用域、只取有效行。
     * 与注入召回同一套融合参数——「冲突阶段看到的近邻」和「召回会看到的」保持一致。
     */
    private suspend fun retrievePoolCandidates(query: String, scoped: List<MemoryEntity>): List<MemoryEntity> {
        val scopedIds = scoped.map { it.id }.toSet()
        val title = LinkedHashMap<Long, MemoryEntity>()
        val body = LinkedHashMap<Long, MemoryEntity>()
        MemoryRepo.tokenize(query, 12).forEach { t ->
            runCatching { Graph.db.memoryDao().ftsSearch("name:$t* OR description:$t*") }
                .getOrDefault(emptyList())
                .filter { it.id in scopedIds }
                .forEach { m -> title.putIfAbsent(m.id, m) }
            runCatching { Graph.db.memoryDao().ftsSearch("body:$t*") }
                .getOrDefault(emptyList())
                .filter { it.id in scopedIds }
                .forEach { m -> body.putIfAbsent(m.id, m) }
        }
        return MemoryRepo.fuseRrf(title.values.toList(), body.values.toList())
    }

    private suspend fun applyOps(
        sessionId: Long,
        projectId: Long,
        candidates: List<MemoryOp>,
        existing: List<MemoryEntity>,
        pending: MemoryConsolidationScheduler.Pending,
        fullReview: Boolean,
    ): Outcome {
        val turns = pending.totalTurns
        val scoped = scopedMemories(existing, projectId)
        val now = AppTime.nowMillis()

        // ── A 方案第二段：候选池全文 + 四态判定（第二次 LLM 调用）──
        // 检索先做（要碰 Room），纯组装在 buildCandidatePool
        val retrieved = candidates.map { c -> retrievePoolCandidates((c.name + " " + c.body).take(400), scoped) }
        val poolResult = buildCandidatePool(
            candidates, scoped, retrieved,
            updatedTextOf = { ms ->
                java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.CHINA).format(java.util.Date(ms))
            },
        )
        val candViews = candidates.mapIndexed { i, c ->
            CandidateView(
                cid = "N${i + 1}",
                content = c.body,
                type = c.type,
                priority = c.priority ?: defaultPriorityForKind(c.kind),
                scene = c.scene.orEmpty(),
                related = poolResult.related.getOrNull(i) ?: emptyList(),
            )
        }
        val conflictRaw = runCatching {
            AiClient.chatWith(AiClient.usableWorkerConfig(), buildConflictSystemPrompt(), buildConflictUserPrompt(poolResult.pool, candViews), 0.2)
        }.onFailure {
            MemoryPipeline.note(sessionId, "CONFLICT_FAIL", "chatWith: " + (it.message ?: it.javaClass.simpleName))
        }.getOrNull()
        val conflictRetried = conflictRaw ?: runCatching {
            AiClient.chatWith(AiClient.usableWorkerConfig(), buildConflictSystemPrompt(), buildConflictUserPrompt(poolResult.pool, candViews), 0.2)
        }.onFailure {
            MemoryPipeline.note(sessionId, "CONFLICT_FAIL", "重试仍失败: " + (it.message ?: it.javaClass.simpleName))
        }.getOrNull()
        val decisions = conflictRetried?.let { raw ->
            runCatching { parseConflictOps(AiClient.parseJsonArray(raw)) }
                .onFailure {
                    MemoryPipeline.note(sessionId, "CONFLICT_FAIL", "parse: " + raw.take(160))
                }
                .getOrNull()
        }
        // 冲突阶段失败 → 全部 store（TencentDB 同款降级：宁多记，不丢）
        val ops = if (decisions == null) {
            MemoryPipeline.note(sessionId, "CONFLICT_FAIL", "判定不可用 → 全部 store（${candidates.size} 条）")
            candidates
        } else {
            mapConflictToOps(decisions, candidates, poolResult.pool)
        }

        // 四态判定在纯函数里做（ai/MemoryOpPlan.kt），这里只负责按计划落库。
        val plan = planMemoryOps(ops, scoped, projectId, sessionId, now, fullReview)

        plan.stores.forEach { w ->
            val id = Graph.memories.upsert(w.entity)
            Graph.memories.addEvidence(id, w.evidence, now)
        }
        plan.updates.forEach { w ->
            Graph.memories.upsert(w.entity)
            Graph.memories.addEvidence(w.entity.id, w.evidence, now)
        }
        plan.superseded.forEach { (oldId, survivorId) ->
            Graph.memories.markSuperseded(oldId, survivorId, now)
        }
        plan.deletes.forEach { Graph.memories.softDelete(it) }

        // P3：把带场景的记忆汇总到 L2（heat+1、摘要追加），不额外调用模型
        (plan.stores.map { it.entity } + plan.updates.map { it.entity }).forEach { e ->
            if (e.scene.isNotBlank()) {
                MemoryLayers.touchScene(
                    e.scene, projectId, e.description.ifBlank { e.body.take(80) }, now
                )
            }
        }

        val added = plan.stores.size
        val updated = plan.updates.size
        val deleted = plan.deletes.size

        // 覆盖归因：写入引用过哪些轮次（evidence → 轮次的用户消息时间）
        val evidence = plan.evidenceIds.toHashSet()
        var covered: Long? = null
        for (t in pending.turns) {
            if (t.messages.any { it.id in evidence }) {
                val ts = t.userMessage.createdAt
                if (covered == null || ts > covered) covered = ts
            }
        }
        if (covered == null && plan.writes > 0) {
            MemoryPipeline.note(
                sessionId, "EXTRACT_NO_EVIDENCE",
                "写入 ${plan.writes} 条但模型没给 source_message_ids，进度按整批推进"
            )
        }

        MemoryPipeline.note(
            sessionId,
            "CONSOLIDATE",
            "新增$added 更新$updated 合并${plan.superseded.size} 删除$deleted " +
                "拦截${plan.blocked} 跳过${plan.skipped} 改道${plan.diverted}（整理 $turns 轮）"
        )
        return Outcome(
            turns = turns, added = added, updated = updated, deleted = deleted,
            blocked = plan.blocked, skipped = plan.skipped,
            coveredThrough = covered,
            empty = added + updated + deleted == 0
        )
    }

    /** kind 归一到我们已有的 type 体系：不新增列，零迁移。 */
    internal fun normalizeType(typeField: String, kind: String): String {
        val t = typeField.trim().lowercase()
        if (t in listOf("project", "feedback", "reference", "skill")) return t
        return when (kind) {
            "episode", "draft" -> "reference"
            "procedure" -> "feedback"
            else -> "project" // fact / 兜底
        }
    }

    /** 单测入口：归一规则不许悄悄改坏（一改就得动表迁移）。 */
    internal fun normalizeTypeForTest(typeField: String, kind: String): String =
        normalizeType(typeField, kind)

    /**
     * 单测入口：自动整理默认不许删，只有用户手动整理（fullReview）才允许。
     * 真判定在 [applyOps] 里，这里把它抽出来给测试钉住。
     */
    internal fun deleteAllowedForTest(fullReview: Boolean): Boolean = fullReview

    private suspend fun advance(sessionId: Long, at: Long) {
        Graph.memoryProgress.advanceTo(sessionId, at)
    }

    /**
     * handoff 每**次整理**只重写一次（原来每轮都写），且内容没变就不写。
     * 依据 §1.5.5：别每轮重写、加硬上限、禁止写长期事实。
     */
    private suspend fun rewriteHandoff(sessionId: Long) {
        val session = Graph.chat.session(sessionId) ?: return
        val recent = Graph.chat.messagesOf(session.id)
            .filter { it.role in listOf("user", "assistant") && it.content.isNotBlank() }
            .takeLast(8)
            .joinToString("\n") {
                "${if (it.role == "user") "用户" else "助手"}：${it.content.take(200)}"
            }
        if (recent.isBlank()) return
        val text = runCatching {
            AiClient.chatWith(AiClient.usableWorkerConfig(), buildHandoffPrompt(recent), "", 0.2)
        }.getOrNull()?.trim()?.take(120).orEmpty()
        if (text.isBlank()) return
        // 2026-10-31：同 MemoryPipeline.rewriteHandoff——handoff 必须按会话项目落库，
        // 否则会写成全局记忆并注入到所有项目（用户实锤的 Python 交接泄漏就是这条路径的孪生 bug）
        val existing = Graph.db.memoryDao().byNameInProject(MemoryPipeline.HANDOFF_NAME, session.projectId)
        if (existing != null && existing.body == text) return // 内容没变，不写
        Graph.memories.upsert(
            MemoryEntity(
                name = MemoryPipeline.HANDOFF_NAME,
                type = "handoff",
                description = text.take(40),
                body = text,
                importance = 3,
                inIndex = true,
                originSession = session.id,
                projectId = session.projectId,
                id = existing?.id ?: 0,
                createdAt = existing?.createdAt ?: 0
            )
        )
    }


}
/**
 * 整理输入的字符预算：本地 worker（127.0.0.1:18181）ctx=4096 token，中文 ≈1 token/字，
 * 还要留记忆索引+指令+输出的空间 → 6000；主模型上下文大，放宽到 30000。
 * 2026-11-10 真机事故（localai 262% CPU 持续发烫）的根治项之一。
 */
private suspend fun inputBudget(): Int = runCatching {
    val cfg = AiClient.usableWorkerConfig()
    if (cfg.baseUrl.contains("127.0.0.1")) 6000 else 30000
}.getOrDefault(6000)