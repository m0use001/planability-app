package io.github.m0use001.planability.ai

import io.github.m0use001.planability.core.AppTime
import io.github.m0use001.planability.data.Graph
import io.github.m0use001.planability.data.MemoryEntity
import io.github.m0use001.planability.data.MemoryHistory
import kotlinx.coroutines.launch

/**
 * M2.5 记忆写入管线（mem0 V3 简化版，worker=MiMo，回退主槽）：
 * 1. 抽取：最近 20 条对话 + 已有记忆索引 → 最多 3 条 ADD/UPDATE/DELETE 操作（Rich Not Atomic / 15-80 字 / No Echo）
 * 2. 去重：内容 SHA-256 指纹相同跳过；同名走覆盖更新
 * 3. 合并：矛盾即删（DELETE → 软删 + 审计）
 * 4. handoff：每轮完成后覆盖重写唯一一份「当前状态+下一步」（inIndex 常驻）
 */
object MemoryPipeline {

    private const val MAX_OPS = 3

    /** 整理器（第三步）复用这个常量与 note(): 记"抽取阶段发生了什么"到 memory_history。 */
    internal val HANDOFF_NAME = "handoff-current"

    /**
     * 轮次完成后调用（后台，不阻塞）。
     *
     * v36 B 项第二步起，这里**只记 pending、不再当轮抽取**（0 次 LLM 调用）：
     * 什么时候整理、整理多少，交给 [MemoryConsolidationScheduler]。
     * 用户可感知的变化是「这轮说的话，下一次会话/空闲几分钟后才进记忆」——
     * 这是设计目标（宁可慢一点，也不要每轮都为 3 条记忆打两次模型、还互相覆盖）。
     *
     * 原来的每轮抽取/重写 handoff 逻辑仍在下面，第三步的整理器会复用它们。
     */
    fun afterTurn(sessionId: Long) {
        android.util.Log.i("MemoryPipeline", "afterTurn session=" + sessionId + " → 只记 pending")
        Graph.appScope.launch {
            runCatching { MemoryConsolidationScheduler.onTurnFinished(sessionId) }
                .onFailure { android.util.Log.e("MemoryPipeline", "afterTurn → scheduler failed", it) }
        }
    }

    private suspend fun cfg() = AiClient.usableWorkerConfig()

    /**
     * 记一条"抽取阶段发生了什么"到 memory_history。
     *
     * 为什么用 memoryId = 0：这类事件不属于任何一条记忆（抽取失败/判定为空），
     * 但必须留下证据，否则"每轮 0 条写入"永远分不清是**判定没东西**还是**根本没跑/解析失败**。
     * action 用 EXTRACT_* 前缀，和记忆自身的 ADD/UPDATE/DELETE/RECALL 区分开。
     */
    internal suspend fun note(sessionId: Long, action: String, detail: String) {
        runCatching {
            Graph.db.memoryDao().insertHistory(
                MemoryHistory(
                    memoryId = 0,
                    action = action,
                    detail = ("s=" + sessionId + " " + detail).take(200),
                    at = AppTime.nowMillis()
                )
            )
        }
    }

    private suspend fun extractAndMerge(sessionId: Long) {
        val session = Graph.chat.session(sessionId) ?: return
        val transcript = Graph.chat.messagesOf(sessionId).filter {
            it.id > session.clearBefore && it.role in listOf("user", "assistant") && it.content.isNotBlank()
        }
        if (transcript.size < 2) {
            android.util.Log.i("MemoryPipeline", "extract skip: transcript=" + transcript.size)
            return
        }
        android.util.Log.i("MemoryPipeline", "extract session=" + sessionId + " transcript=" + transcript.size)
        val recent = transcript.takeLast(20)
        val existing = Graph.memories.all().filter { it.type != "handoff" }

        val indexLines = existing.take(30).joinToString("\n") {
            "- ${it.name}（${it.type}）：${it.description.ifBlank { it.body.take(40) }}"
        }
        val transcriptText = recent.joinToString("\n") {
            "${if (it.role == "user") "用户" else "助手"}：${it.content.take(300)}"
        }
        val now = AppTime.now()
        val prompt = buildString {
            append("你是记忆抽取引擎。从对话中提取值得长期记住的用户事实/偏好/项目背景/反馈教训（不含本次闲聊内容本身）。\n")
            append("规则：每条 15~80 字、具体细节不泛化；不要复述对话；最多 $MAX_OPS 条；没有值得记的输出 []。\n")
            append("观察日期锚点：${now.year}-${now.monthValue}-${now.dayOfMonth}。\n")
            if (indexLines.isNotBlank()) append("已有记忆（name：描述）：\n$indexLines\n")
            append("\n只输出 JSON 数组，元素格式：")
            append("""{"op":"ADD|UPDATE|DELETE","name":"简短唯一名","type":"project|feedback|reference","description":"一句话","body":"完整内容","reason":"仅 DELETE 需要"}""")
            append("\n对话：\n$transcriptText")
        }
        val raw = runCatching {
            AiClient.chatWith(cfg(), "你是记忆抽取引擎，只输出 JSON 数组。", prompt, 0.2)
        }.onFailure {
            android.util.Log.e("MemoryPipeline", "extract chatWith failed", it)
            note(sessionId, "EXTRACT_FAIL", "chatWith: " + (it.message ?: it.javaClass.simpleName))
        }.getOrNull() ?: return
        val ops = runCatching { AiClient.parseJsonArray(raw) }
            .onFailure {
                android.util.Log.e("MemoryPipeline", "extract parse failed: ${raw.take(200)}", it)
                note(sessionId, "EXTRACT_FAIL", "parse: " + raw.take(160))
            }
            .getOrNull() ?: return
        if (ops.length() == 0) {
            // 这是"模型判定本轮没有值得记的东西"——**正常结果**，但要留证据，
            // 否则用户问"你不是记住了吗"时，没有任何办法证明它当时跑过。
            android.util.Log.i("MemoryPipeline", "extract empty (model returned [])")
            note(sessionId, "EXTRACT_EMPTY", "模型返回空数组")
            return
        }

        // 与 MemoryConsolidator.applyOps 同口径：写入作用域 = 本项目 ∪ 全局（extractAndMerge
        // 目前已无调用者，但只要复活就必须带着项目作用域，否则跨项目同名记忆会被误改）
        val scoped = MemoryConsolidator.scopedMemories(existing, session.projectId)
        val hashSeen = scoped.associate { it.id to Graph.memories.bodyHash(it.body) }
        var applied = 0
        for (i in 0 until ops.length()) {
            if (applied >= MAX_OPS) break
            val o = ops.optJSONObject(i) ?: continue
            val op = o.optString("op", "ADD").uppercase()
            val name = o.optString("name", "").trim().take(40)
            if (name.isBlank()) continue
            val type = o.optString("type", "project").takeIf { it in listOf("project", "feedback", "reference") } ?: "project"
            val description = o.optString("description", "").trim().take(120)
            val body = o.optString("body", "").trim().take(800)

            when (op) {
                "DELETE" -> {
                    val target = scoped.firstOrNull { it.name == name } ?: continue
                    Graph.memories.softDelete(target.id)
                    applied++
                }
                "UPDATE" -> {
                    val target = scoped.firstOrNull { it.name == name } ?: continue
                    val merged = target.copy(
                        description = description.ifBlank { target.description },
                        body = body.ifBlank { target.body },
                        type = if (o.has("type")) type else target.type
                    )
                    Graph.memories.upsert(merged)
                    applied++
                }
                else -> {
                    if (body.isBlank()) continue
                    val hash = Graph.memories.bodyHash(body)
                    if (hashSeen.containsValue(hash)) continue // 完全重复
                    val sameName = scoped.firstOrNull { it.name == name }
                    if (sameName != null) {
                        Graph.memories.upsert(sameName.copy(description = description.ifBlank { sameName.description }, body = body))
                    } else {
                        Graph.memories.upsert(
                            MemoryEntity(
                                name = name, type = type, description = description, body = body,
                                inIndex = false, importance = 1, originSession = sessionId,
                                projectId = session.projectId
                            )
                        )
                    }
                    applied++
                }
            }
        }
        android.util.Log.i(
            "MemoryPipeline",
            "extract done session=" + sessionId + " ops=" + ops.length() + " applied=" + applied
        )
        if (applied == 0) {
            // 有候选但一条都没落地（全部重复/名字空/正文空）——和"模型返回 []"是两件事，
            // 分开记，调参时才知道该改 prompt 还是改去重。
            note(sessionId, "EXTRACT_EMPTY", "候选 " + ops.length() + " 条全部跳过")
        }
    }

    /** handoff 强制机制：唯一一份「当前状态+下一步」，覆盖式更新，inIndex 常驻 */
    private suspend fun rewriteHandoff(sessionId: Long) {
        // 用户关掉了交接记忆（删掉那一条时自动关闭）：不再回写，
        // 否则"删了又出现"就是永远删不掉（2026-10-21）。
        if (runCatching { Graph.settings.raw("memory.handoff.off") }.getOrNull() == "1") return
        val session = Graph.chat.session(sessionId) ?: return
        val transcript = Graph.chat.messagesOf(sessionId).filter {
            it.id > session.clearBefore && it.role in listOf("user", "assistant") && it.content.isNotBlank()
        }
        if (transcript.isEmpty()) return
        val recent = transcript.takeLast(10).joinToString("\n") {
            "${if (it.role == "user") "用户" else "助手"}：${it.content.take(200)}"
        }
        val text = runCatching {
            AiClient.chatWith(
                cfg(),
                "你是状态交接助手。根据最近对话输出一行不超过 60 字的「正在做什么 + 下一步」，中文，直接输出正文不要解释。",
                recent, 0.2
            )
        }.onFailure { android.util.Log.e("MemoryPipeline", "handoff chatWith failed", it) }
            .getOrNull()?.trim().takeUnless { it.isNullOrBlank() } ?: return

        // 2026-10-31 修：handoff 必须按**会话所属项目**落库。旧写法没带 projectId，
        // 默认 0 = 全局——任何项目的「当前状态+下一步」都变成全局记忆，再注入到
        // 所有项目的对话里（用户实锤：Python 编程的交接出现在全局记忆）。查找也要按
        // 项目：全局查询会跨项目命中别家的 handoff，覆盖掉别人的。
        val existing = Graph.db.memoryDao().byNameInProject(HANDOFF_NAME, session.projectId)
        Graph.memories.upsert(
            MemoryEntity(
                name = HANDOFF_NAME,
                type = "handoff",
                description = text.take(40),
                body = text,
                importance = 3,
                inIndex = true,
                originSession = sessionId,
                projectId = session.projectId,
                id = existing?.id ?: 0,
                createdAt = existing?.createdAt ?: 0
            )
        )
    }
}
