package io.github.m0use001.planability.ai

import io.github.m0use001.planability.data.Graph

/**
 * 压缩状态机的**决策层**（C 组）。纯函数 + 一处 KV 开关，不碰 Room、不碰网络，
 * 所以它整块都能被 JVM 单测直接断言（见 `ContextCompactionStateMachineTest`）。
 *
 * ## 分层
 * - **决策**在这里：[decide] 只回答"现在该不该压"；[shouldSplitOnError] 只回答"这个错误降半有没有救"；
 *   [preflight] 只回答"这次压缩允不允许发生"；[validateOnTheWire] 只回答"摘要是不是真的上了线"。
 * - **执行**在 `ChatEngine`（读 DB、调 AI、写回）；唯一的例外是 [rescueOversizedSend]
 *   （超长求援的 SPLIT_MERGE，只改本次请求的内存副本，不碰 DB，所以留在这里跟决策挨着）；
 *   **兜底**在 `ContextEngine.capHistory`（按 token 预算从尾部收，
 *   与这里的"锚点之后"是两层，互不覆盖：capHistory 永远是最后一道保险丝）。
 *
 * ## 默认**关闭**自动压缩
 * 学 OpenMinis `AutoCompactPrefs`：关 = 到阈值只提示，绝不自动改用户的历史。
 * 压缩是不可逆动作，而"哪个错误算上下文超长"在真实世界里分不清（OpenMinis 用一整份
 * `ExhaustedCompactionAbsenceTest` 钉的就是这条：候选组全失败时不许自动压缩）。
 * 手动 `/compact` 不受这个开关限制。
 *
 * 来源：`◆/ref-runtime-study.md` 第一/二/六节（OpenMinis ContextPolicy /
 * CompactionDecisionTest / ExhaustedCompactionAbsenceTest、rikkahub compressConversation 三道前置检查）。
 */
object Compaction {

    // ---------- 开关：默认关 ----------

    /** 走 SettingsRepo 的旁路 KV，不占正式字段、不动 Room schema（见 SettingsRepo 的 kvGet/kvPut）。 */
    const val KEY_AUTO = "compaction.auto"

    /** 默认关：到阈值只提示，不自动改历史。 */
    const val AUTO_DEFAULT = false

    /**
     * 自动压缩开关。**这是给压缩状态机的 in-loop 压缩用的**，与既有的
     * 「滚动摘要」（`ChatEngine.maybeScheduleSummary`，每轮结束后自己按阈值跑）是两件事：
     * 后者已经跑了很久、用户也在用，不在这个开关的管辖范围内。
     * 状态机的执行层接上之前，这个开关只被单测引用——**不要**因为看到"默认关"
     * 就以为滚动摘要也停了。
     */
    suspend fun autoEnabled(): Boolean =
        Graph.settings.kvGet(KEY_AUTO)?.toBooleanStrictOrNull() ?: AUTO_DEFAULT

    suspend fun setAutoEnabled(on: Boolean) {
        Graph.settings.kvPut(KEY_AUTO, if (on) "true" else "false")
    }

    // ---------- 预算常量 ----------

    /** 单次压缩最多调几次模型（第一次 + 一次救援；OpenMinis MAX_COMPACT_LLM_CALLS=6，我们更省） */
    const val MAX_COMPACT_LLM_CALLS = 3

    /** split 的深度上限：1+2+4+8 = 15 次是上限，所以深度 3 就到顶 */
    const val MAX_SPLIT_DEPTH = 3

    /** compactThreshold = window × 0.85（ContextPolicy.forUserCap 的口径） */
    const val COMPACT_RATIO = 0.85

    /** 同一次运行里最多几次压缩尝试，超过就 EXHAUSTED（防止"压了但没变小"的自旋） */
    const val MAX_ATTEMPTS = 3

    // ---------- 决策 ----------

    enum class CompactionAction { PROCEED, COMPACT, SKIP, EXHAUSTED }

    /**
     * 一次决策：[reason] 是给人看的（进系统提示行/log），[hint] 是自动压缩关着时给用户的那句
     * "可以手动压缩"——到阈值只提示，不自动动手。
     */
    data class CompactionDecision(
        val action: CompactionAction,
        val reason: String = "",
        val hint: String = ""
    )

    /**
     * 该不该压缩。
     *
     * 顺序是有意的（每一层都可能短路）：
     * 1. **有未决工具调用 → SKIP**：结果还没回来，此时压缩会把请求切成非法序列
     *    （assistant 有 tool_calls 却没有配对的 tool 结果），而且压过的再也不会执行。
     *    —— rikkahub `ChatService.compressConversation` 三道检查的第三条。
     * 2. **force → COMPACT**：`/compact` 是用户明确手动触发，跳过阈值。
     * 3. **没进展 → EXHAUSTED**：上一次压缩后请求没变小（`lastCompactionShrunk=false`）
     *    或尝试次数到顶 → 停止并如实告知，不无限重试（B14）。
     * 4. **窗口未知 → PROCEED**：不能因为不知道窗口就掐死请求（B10）。
     * 5. **到线**（≥ window 或 ≥ 0.85×window）：自动开 → COMPACT；自动关 → PROCEED + 提示。
     */
    fun decide(
        estimatedTokens: Int,
        windowTokens: Int,
        autoEnabled: Boolean = AUTO_DEFAULT,
        force: Boolean = false,
        pendingToolCalls: Int = 0,
        lastCompactionShrunk: Boolean = true,
        attempts: Int = 0
    ): CompactionDecision {
        if (pendingToolCalls > 0) {
            return CompactionDecision(
                CompactionAction.SKIP,
                "还有 $pendingToolCalls 个工具调用的结果没回来，现在压缩会发出非法序列"
            )
        }
        if (force) {
            return CompactionDecision(CompactionAction.COMPACT, "手动 /compact")
        }
        if (!lastCompactionShrunk) {
            return CompactionDecision(
                CompactionAction.EXHAUSTED,
                "上次压缩后请求没有变小，再压也不会更小"
            )
        }
        if (attempts >= MAX_ATTEMPTS) {
            return CompactionDecision(
                CompactionAction.EXHAUSTED,
                "本轮已经试过 $attempts 次压缩，停手免得自旋"
            )
        }
        if (windowTokens <= 0) {
            return CompactionDecision(
                CompactionAction.PROCEED,
                "窗口大小未知，不因为不知道上限就掐死请求"
            )
        }
        val over = estimatedTokens >= windowTokens
        val atLine = estimatedTokens >= (windowTokens * COMPACT_RATIO).toInt()
        if (over || atLine) {
            if (autoEnabled) return CompactionDecision(CompactionAction.COMPACT, "超过压缩线")
            return CompactionDecision(
                CompactionAction.PROCEED,
                if (over) "已到窗口上限" else "已到压缩线（0.85×窗口）",
                "上下文偏大，可以发 /compact 手动压缩"
            )
        }
        return CompactionDecision(CompactionAction.PROCEED, "预算内")
    }

    // ---------- 前置检查：这次压缩允不允许发生 ----------

    enum class BlockReason { NONE, STREAMING, EMPTY_RANGE }

    /**
     * 手动压缩的三道门前两道（第三道"未决工具"由 [decide] 负责，因为在决策链里也要拦）。
     *
     * 这道是给 M4 的：原来 `/compact` 无论有没有可压内容都弹"已压缩当前上下文…"——
     * 用户看到假的成功反馈，会以为历史已经裁过。
     */
    fun preflight(streaming: Boolean, compactableCount: Int): BlockReason = when {
        streaming -> BlockReason.STREAMING       // 生成中的回复压缩不到，检查点也不能插到它前面
        compactableCount <= 0 -> BlockReason.EMPTY_RANGE
        else -> BlockReason.NONE
    }

    // ---------- split / merge ----------

    /**
     * 降半：切成两半，前一半至少 1 条。**深度到顶或不足 2 条就返回空**
     * ——空就是"别再切了"，调用方据此收手（B8）。
     */
    fun <T> split(list: List<T>, depth: Int = 0, maxDepth: Int = MAX_SPLIT_DEPTH): List<List<T>> {
        if (list.size < 2 || depth >= maxDepth) return emptyList()
        val half = (list.size + 1) / 2
        return listOf(list.subList(0, half), list.subList(half, list.size))
    }

    /**
     * 把 split 出来的段按**原顺序**接回去。
     * 顺序不许变：摘要两半各自压完之后 join 错了，等于把对话顺序打乱给模型看。
     */
    fun <T> merge(parts: List<List<T>>): List<T> {
        val out = ArrayList<T>(parts.sumOf { it.size })
        parts.forEach { out.addAll(it) }
        return out
    }

    // ---------- 错误分类：这个错误降半有没有救 ----------

    /** 与请求大小无关的错误：降半只会把一次失败放大成很多次慢调用。 */
    private val SIZE_AGNOSTIC_TEXT = listOf(
        "cancel",                       // 用户/client 取消
        "sockettimeout", "timeout", "failed to connect", "connect", "unreachable",
        "offline", "network", "unknownhost",
        "rate limit", "too many requests", "quota", "insufficient_quota",
        "unauthorized", "invalid api key", "authentication",
        "unsupported parameter", "unknown field", "not supported", "invalid_request",
        "reasoning.effort", "temperature"
    )

    /**
     * 裸数字状态码**必须**按词边界匹配：`the maximum context length is 40192 tokens`
     * 里含 "401"、`input length 14296` 里含 "429"，用 includes 会把明显的超长误判成
     * 配额/参数类错误，然后不降半 —— 压缩整段失败。
     */
    private val SIZE_AGNOSTIC_CODES = listOf(400, 401, 403, 429)

    /** 明确是"你太长了"的信号。 */
    private val TOO_LARGE = listOf(
        "context_length_exceeded", "context length", "maximum context length",
        "too large", "request too large", "request entity too large",
        "tokens exceeds", "reduce the length", "input exceeds", "too many tokens"
    )

    /**
     * 这个错误该不该降半重试。
     *
     * 与 chat 主循环**同源**：传进来的就是主循环手里那条错误的原文（`StreamFailure`/`errorMsg`），
     * 不二次包装。将来主循环长出统一的错误分类，这里必须改成转发给那个分类器
     * （`ContextCompactionStateMachineTest` 有一条源码级断言钉住这件事）。
     *
     * 默认 **true**：枚举所有 provider 怎么写"你太长了"是不可能的（OpenMinis#133 里
     * `[context_length_exceeded] Your input exceeds the context window of this model`
     * 就漏过了好几个变体），所以举证责任在"不该重试"那一边：有摘要的半截胜过没有摘要。
     */
    fun shouldSplitOnError(message: String?): Boolean {
        if (message.isNullOrBlank()) return true
        val m = message.lowercase()
        if (TOO_LARGE.any { m.contains(it) }) return true
        if (SIZE_AGNOSTIC_TEXT.any { m.contains(it) }) return false
        if (SIZE_AGNOSTIC_CODES.any { code -> Regex("\\b$code\\b").containsMatchIn(m) }) return false
        return true
    }

    /** [shouldSplitOnError] 的 Throwable 版本：**用原始异常**，不要用再包一层的 message。 */
    fun shouldSplitOnError(error: Throwable?): Boolean = shouldSplitOnError(error?.message)

    // ---------- 上线校验：摘要必须真的进了下一次请求 ----------

    /**
     * 摘要写进 DB 不算数，**上了线才算数**（OpenMinis `CompactSummaryOnTheWireTest` 的教训：
     * 旧五个压缩测试全过而 bug 活了四个月，因为它们只断言"生成了摘要"、不断言请求体）。
     *
     * 返回问题列表，空 = 通过。调用方把它写进 manifest / log，人在真机与账本里都看得见。
     */
    fun validateOnTheWire(
        system: String,
        summary: String,
        /** 压缩前的历史条数（含工具往返） */
        fullCount: Int,
        /** 实际发出去的历史条数 */
        sentCount: Int
    ): List<String> {
        val problems = mutableListOf<String>()
        if (summary.isBlank()) return problems
        if (!system.contains(summary)) {
            problems += "摘要没有出现在请求的 system 里（可能被 chip 开关/清洗吃掉了）"
        }
        if (fullCount > 0 && sentCount >= fullCount) {
            problems += "请求里的历史没有变短（$sentCount/$fullCount），压缩没生效"
        }
        return problems
    }

    // ---------- 执行层：超长求援（SPLIT_MERGE 的落地；Compaction 里唯一碰网络的一个） ----------

    /**
     * 发送超长时的**一次性**救援：把 in-memory 请求历史对半拆开（切点走 [splitSafeIndex]，
     * 不拆工具对），前一半喂给摘要模型，摘要并进 system 后让主循环重发。
     *
     * 只改**这次请求**的内存副本，不碰 DB —— B13 的教训是"模糊信号下绝不自动改用户历史"，
     * 这里连改都不改：救援失败也只是白打一次摘要调用，历史与游标原封不动。
     * 返回 null = 拆不动 / 摘要失败，调用方把原错误**原样**抛出去（不许二次包装，B15）。
     */
    suspend fun rescueOversizedSend(
        sessionId: Long,
        cfg: io.github.m0use001.planability.data.ModelConfig,
        system: String,
        msgs: List<ChatApiMessage>
    ): Pair<String, List<ChatApiMessage>>? {
        if (msgs.size < 4) return null
        val cut = splitSafeIndex(msgs, msgs.size / 2)
        val older = msgs.subList(0, cut)
        val kept = msgs.subList(cut, msgs.size)
        if (older.isEmpty() || kept.isEmpty()) return null
        // 与滚动摘要同一套转录文本形状（role 标签 + 每条 600 字），救援产物与正式摘要可读性一致
        val text = older.joinToString("\n") { m ->
            when (m.role) {
                "user" -> "用户：${m.content.take(600)}"
                "tool" -> "工具结果：${m.content.take(600)}"
                else -> "助手：${m.content.take(600)}"
            }
        }
        // v40（成本优化 1.3/阶段 4）：救援调用进账本（slot="rescue"）——它恰恰是最贵的隐性调用之一。
        // 压缩槽优先（可指本机模型），失败回退调用方给的 cfg（会话主模型）。
        val primary = runCatching { AiClient.compactConfig() }.getOrDefault(cfg)
        val sysPrompt = "你是对话摘要助手。把对话历史压缩为不超过 200 字的中文要点摘要：保留关键事实、用户偏好、已做的决定、未完成事项与日期。直接输出摘要正文，不要解释。"
        val userPrompt = "新增对话：\n$text"
        var usedModel = primary.modelName
        val reply = runCatching {
            AiClient.chatWithCounted(primary, sysPrompt, userPrompt, 0.2)
        }.getOrNull() ?: runCatching {
            val sameCfg = primary.modelName == cfg.modelName && primary.baseUrl == cfg.baseUrl
            if (sameCfg) null else {
                usedModel = cfg.modelName
                AiClient.chatWithCounted(cfg, sysPrompt, userPrompt, 0.2)
            }
        }.getOrNull() ?: return null
        ContextEngine.appendUsage(
            sessionId, "rescue", usedModel,
            reply.tokensIn, reply.tokensOut, reply.cachedTokens, reply.durationMs
        )
        val summary = reply.text.trim()
        if (summary.isBlank()) return null
        // 注进 system 的形状与 ContextEngine 的 summary chip 一致（同样不可关闭——
        // 它替代了被裁掉的那段历史，关掉它 = 静默丢上下文）
        val newSystem = system + "\n\n【用户上下文·此前对话摘要（不可关闭）】\n[超长救援] " + summary.take(2000)
        return newSystem to kept
    }
}
