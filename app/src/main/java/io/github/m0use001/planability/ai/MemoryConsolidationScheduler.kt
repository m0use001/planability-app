package io.github.m0use001.planability.ai

import io.github.m0use001.planability.core.AppTime
import io.github.m0use001.planability.data.Graph
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.job
import kotlinx.coroutines.launch

/**
 * v36 B 项第二步：**记忆后台整理调度器**（rikkahub `MemoryConsolidationScheduler` 的等价物）。
 *
 * 职责只有一件：决定"什么时候该整理"，并把"该整理"这个状态在进程内外都留下痕迹。
 * **它不做整理本身**（那是第三步的整理器），本文件里没有任何 LLM 调用。
 *
 * ## 触发条件（与 rikkahub 对齐）
 * - 一轮对话结束 → 空闲 [IDLE_DELAY_MS] 后整理；待整理轮数 ≥ [MAX_PENDING_TURNS] 时不再等
 * - 用户离开会话 → 立刻整理（不会马上再有新消息）
 * - 应用切到后台 → 有待整理就现在开始（空闲计时在后台走不完，ColorOS 上这条最关键）
 * - 进程重启 → 启动时补跑进程被杀前没来得及整理的
 *  来源: rikkahub/data/ai/MemoryConsolidationScheduler.kt:113-159、:84-97。
 *
 * ## 三条硬规则
 * 1. **失败不自动重试**：整理失败就打住，等下一轮对话结束或用户手动触发；否则模型一直报错时，
 *    每次切后台/离开会话都会再打一次。来源: 同文件 :195-198。
 * 2. **不抢前台服务保活**：拿不到就不整理，进度由 memory_progress 表兜着，下次启动照样补跑。
 *    来源: rikkahub/service/MemoryDreamForegroundService.kt:36-48、:86-91（它自己也写"整理不依赖前台服务"）。
 * 3. **进度按"用户消息的时间戳"推进，不按下标**：重新生成回复不会重复整理。
 *    来源: rikkahub/data/ai/MemoryConsolidator.kt:355-392。
 *
 * ## 与本项目的简化（单机单用户）
 * rikkahub 按 `memoryId`（每个助手一份记忆库）分锁、分定时器；我们只有一个记忆库，
 * 所以是**一把同步锁 + 每个会话一个空闲定时器 + 一个 failed 标记**，不为用不到的多库机制付复杂度。
 */
object MemoryConsolidationScheduler {

    /** 空闲多久后整理。2 分钟：够用户连着追问几句，又不至于把"等下再说"拖成"明天再说"。 */
    const val IDLE_DELAY_MS = 2 * 60 * 1000L

    /** 待整理轮数攒到这个数就不再等空闲，立刻整理。 */
    const val MAX_PENDING_TURNS = 6

    /** 单次最多带几个会话。 */
    const val MAX_SESSIONS_PER_RUN = 8

    /** 单次交给整理器的转录总长上限（字符）。 */
    const val MAX_TRANSCRIPT_CHARS = 60_000

    /** 整理 agent 的单次步数上限。 */
    const val MAX_STEPS = 32

    /** 只补最近 7 天有更新的会话；更早的不再补（刚开记忆/导入旧对话时不该把整段历史交出去）。 */
    const val PENDING_WINDOW_MS = 7L * 24 * 60 * 60 * 1000

    /** 找候选时最多读这么多个会话（每个都要读消息，刚开记忆时候选可能有几百个）。 */
    const val MAX_SCANNED_SESSIONS = 50

    /** 首次跟踪一个会话时最多回看这么多个用户轮次（决定整理起点，不是每批批大小）。 */
    const val MAX_TURNS_PER_SESSION = 12

    /**
     * 一批最多交给模型多少个轮次。
     *
     * **与单次写入上限绑定**：整理器一次最多写 [MEMORY_MAX_OPS] 条记忆，而进度会推进到
     * 本批最后一条用户消息——多给的轮次就等于**永久跳过**。
     * 2026-10-30 真模型验证实测：8 轮一次交出去只写出 2 条，其余 6 轮（考试时间、考场、
     * 作息更正、学习偏好）全部丢失。所以批大小压到「最多能写出的条数 × 2」，
     * 剩下的留给下一轮（调度器会在下一次空闲/切后台时接着跑）。
     */
    const val MAX_TURNS_PER_RUN = MEMORY_MAX_OPS * 2

    /** 给 UI/日志看的状态。 */
    data class Status(
        val pendingTurns: Int = 0,
        val running: Boolean = false,
        val lastRunAt: Long = 0,
        /** 人类可读的一句话：上次整理结果，或当前在等什么 */
        val lastRunNote: String = "",
        /** 上次整理失败且之后没有新轮次 —— 为 true 时不自动重试 */
        val stalled: Boolean = false,
    )

    private val lock = Any()
    private val timers = mutableMapOf<Long, Job>()
    /** 已知有待整理轮次的会话。启动补跑认出来的候选也在这儿，所以 onAppHidden 不能只盯计时器。 */
    private val pendingSessions = mutableSetOf<Long>()
    private var failed = false
    private val _status = MutableStateFlow(Status())

    val status: StateFlow<Status> get() = _status

    private fun say(msg: String) {
        _status.value = _status.value.copy(lastRunNote = msg)
    }

    // ---------- 入口 ----------

    /**
     * 一轮对话真正结束。由 [MemoryPipeline.afterTurn] 调用（它自己被 ChatEngine 调）。
     *
     * 三件事，全不碰模型：①首次见到这个会话就把整理起点定下 ②记一次待整理轮数 ③决定马上跑还是等空闲。
     */
    fun onTurnFinished(sessionId: Long) {
        Graph.appScope.launch {
            runCatching {
                val item = pendingOf(sessionId)
                if (item == null) {
                    // 没有新的轮次（例如只是重新生成了回复）：也把起点推进一下，
                    // 免得下次扫描还把它当候选读一遍。来源: MemoryConsolidator.kt:229-231。
                    rememberSeen(sessionId)
                    return@launch
                }
                synchronized(lock) { failed = false; pendingSessions += sessionId }
                countPending(item.turns)
                android.util.Log.i(
                    "MemoryConsolidationScheduler",
                    "onTurnFinished s=$sessionId 轮=${item.turns} → " +
                        if (item.turns >= MAX_PENDING_TURNS) "攒够 $MAX_PENDING_TURNS 轮，立刻整理"
                        else "等空闲 ${IDLE_DELAY_MS / 60000} 分钟"
                )
                if (item.turns >= MAX_PENDING_TURNS) runNow() else restartTimer(sessionId)
            }.onFailure {
                android.util.Log.e("MemoryConsolidationScheduler", "onTurnFinished s=$sessionId", it)
            }
        }
    }

    /** 用户离开会话（不会马上再有新消息，不用等空闲）。 */
    fun onConversationLeft(sessionId: Long) {
        Graph.appScope.launch {
            runCatching {
                val item = pendingOf(sessionId) ?: return@launch
                countPending(item.turns)
                runNow()
            }.onFailure { android.util.Log.e("MemoryConsolidationScheduler", "onConversationLeft", it) }
        }
    }

    /**
     * 应用切到后台。有空闲计时在跑的、或有待整理会话的，现在就整理。
     *
     * 不能只盯空闲计时：启动补跑（resumePending）是从库里认出候选的，那种情况根本没有计时器，
     * 只看 timers 会漏掉——真机上就漏过：HOME 下去一声不响，回来 pending 还原样躺着。
     */
    fun onAppHidden() {
        Graph.appScope.launch {
            runCatching {
                val waiting = synchronized(lock) { timers.isNotEmpty() || pendingSessions.isNotEmpty() }
                if (!waiting) return@launch
                val n = synchronized(lock) { pendingSessions.size }
                android.util.Log.i("MemoryConsolidationScheduler", "onAppHidden: $n 个会话待整理")
                runNow()
            }.onFailure { android.util.Log.e("MemoryConsolidationScheduler", "onAppHidden", it) }
        }
    }

    /** 进程启动时补跑：进程被杀前没来得及整理的会话。幂等，可反复调。 */
    suspend fun resumePending() {
        runCatching {
            val since = AppTime.nowMillis() - PENDING_WINDOW_MS
            val coarse = Graph.memoryProgress.pendingSessionIds(since, MAX_SCANNED_SESSIONS)
            if (coarse.isEmpty()) {
                say("启动补跑：没有待整理会话")
                return@runCatching
            }
            var total = 0
            coarse.forEach { id -> pendingOf(id)?.let { total += it.turns } }
            synchronized(lock) { pendingSessions += coarse }
            countPending(total)
            say("启动补跑：$total 轮待整理")
            if (total > 0) runNow()
        }.onFailure { android.util.Log.e("MemoryConsolidationScheduler", "resumePending", it) }
    }

    // ---------- 内部 ----------

    private data class Item(val sessionId: Long, val turns: Int)

    /** 这个会话里还没整理的**用户轮数**；没有新的轮次返回 null。 */
    private suspend fun pendingOf(sessionId: Long): Item? {
        val session = Graph.chat.session(sessionId) ?: return null
        val msgs = Graph.chat.messagesOf(sessionId).filter {
            it.id > session.clearBefore && it.role in listOf("user", "assistant") && it.content.isNotBlank()
        }
        if (msgs.isEmpty()) return null
        val lite = msgs.map { MsgLite(it.id, it.role, it.createdAt, it.content) }
        val consolidatedAt = Graph.memoryProgress.of(sessionId) ?: initialProgress(lite)
        val pending = pendingTurnsOf(lite, consolidatedAt, session.updatedAt)
        if (pending.turns.isEmpty()) return null
        return Item(sessionId, pending.totalTurns)
    }

    /** 首次见到这个会话：把起点定在"最近 N 个有文字的用户轮次"之前，之后的内容才会被整理。 */
    private suspend fun rememberSeen(sessionId: Long) {
        val session = Graph.chat.session(sessionId) ?: return
        val users = Graph.chat.messagesOf(sessionId).filter {
            it.id > session.clearBefore && it.role == "user" && it.content.isNotBlank()
        }
        if (users.isEmpty()) return
        Graph.memoryProgress.advanceTo(sessionId, initialProgress(users.map { MsgLite(it.id, it.role, it.createdAt, it.content) }))
    }

    private fun countPending(n: Int) {
        if (n > 0) _status.value = _status.value.copy(pendingTurns = _status.value.pendingTurns + n)
    }

    private fun restartTimer(sessionId: Long) {
        val job = Graph.appScope.launch {
            delay(IDLE_DELAY_MS)
            // 整理开始时会取消还在等的定时器，先把自己摘出来，免得被自己取消
            // （rikkahub 同款写法：MemoryConsolidationScheduler.kt:239-250）
            val self = currentCoroutineContext().job
            synchronized(lock) { if (timers[sessionId] === self) timers.remove(sessionId) }
            runNow()
        }
        synchronized(lock) { timers.put(sessionId, job)?.cancel() }
    }

    /**
     * 跑一次整理。同一个记忆库同一时刻只跑一次（runNow 重入直接返回）。
     *
     * 第二步只到"把该整理的状态算清楚"为止：见 [execute]。
     */
    private suspend fun runNow() {
        if (_status.value.running) return
        _status.value = _status.value.copy(running = true, lastRunAt = AppTime.nowMillis())
        try {
            val plan = buildPlan()
            execute(plan)
            _status.value = _status.value.copy(pendingTurns = 0)
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) throw t
            // 失败不自动重试：只标记，等下一轮或用户手动
            synchronized(lock) { failed = true }
            _status.value = _status.value.copy(stalled = true, lastRunNote = "整理失败：${t.message}")
            android.util.Log.e("MemoryConsolidationScheduler", "run failed", t)
        } finally {
            _status.value = _status.value.copy(running = false)
        }
    }

    /**
     * 仅调试（DEBUG_MEMORY 钩子）：同步跑一轮并把结果写成一行可读文本。
     *
     * 为什么需要它：启动补跑走的是 `savedInstanceState == null`，真机/模拟器上从最近任务
     * 恢复时不会触发，验证记忆链路就没有稳定入口。这个函数不做任何「额外」的事——
     * 与自动整理同一个 buildPlan + MemoryConsolidator.run，只是把结果返回给调用方打日志。
     */
    suspend fun debugRunOnce(): String {
        // 与 runNow 同一道重入门：否则 DEBUG_MEMORY 会和启动补跑（resumePending）并发
        // 跑同一个会话（2026-10-30 实测：两次 LLM 调用互相踩，浪费一次且结果不可预期）。
        if (_status.value.running) return "正在整理中（上一次还没跑完）"
        _status.value = _status.value.copy(running = true, lastRunAt = AppTime.nowMillis())
        try {
            return debugRunLocked()
        } finally {
            _status.value = _status.value.copy(running = false)
        }
    }

    private suspend fun debugRunLocked(): String {
        val now = AppTime.nowMillis()
        val since = now - PENDING_WINDOW_MS
        val sessions = runCatching { Graph.chat.sessions() }.getOrDefault(emptyList())
        val coarse = Graph.memoryProgress.pendingSessionIds(since, MAX_SCANNED_SESSIONS)
        if (coarse.isEmpty()) {
            return "没有候选会话（now=$now since=$since sessions=${sessions.size} " +
                "newest=${sessions.maxOfOrNull { it.updatedAt } ?: 0}）"
        }
        synchronized(lock) { pendingSessions += coarse }
        val plan = buildPlan()
        if (plan.sessionIds.isEmpty()) return "候选 ${coarse.size} 个，但没有可整理轮次"
        val result = MemoryConsolidator.run(plan.sessionIds, fullReview = false)
        return "sessions=${plan.sessionIds.size} turns=${plan.turns} added=${result.added} " +
            "updated=${result.updated} deleted=${result.deleted} blocked=${result.blocked} " +
            "skipped=${result.skipped} err=${result.error}"
    }

    /**
     * 用户手动「立即整理」。与自动整理的区别只有两点：
     * ① 允许删（fullReview=true，见 MemoryConsolidator 类注释）；② 不看 failed 标记，失败过也能再点。
     */
    fun organizeNow() {
        if (_status.value.running) {
            say("正在整理，等它跑完")
            return
        }
        Graph.appScope.launch {
            runCatching {
                val since = AppTime.nowMillis() - PENDING_WINDOW_MS
                val coarse = Graph.memoryProgress.pendingSessionIds(since, MAX_SCANNED_SESSIONS)
                // 这条分支也要留证据：用户点了"立即整理"却什么都没发生，
                // 没日志就只能靠猜（是没候选、还是按钮没生效、还是查询写错了）。
                if (coarse.isEmpty()) {
                    say("没有待整理的会话")
                    android.util.Log.i("MemoryConsolidationScheduler", "organizeNow: 没有候选会话")
                    return@launch
                }
                synchronized(lock) { pendingSessions += coarse }
                _status.value = _status.value.copy(running = true, lastRunAt = AppTime.nowMillis())
                try {
                    val result = MemoryConsolidator.run(coarse, fullReview = true)
                    synchronized(lock) { failed = false }
                    val text = if (result.error != null) {
                        _status.value = _status.value.copy(stalled = true)
                        "整理失败：" + result.error
                    } else if (result.hasChanges) {
                        "整理完成：新增 " + result.added + "、更新 " + result.updated +
                            "、删除 " + result.deleted + "、拦截 " + result.blocked
                    } else {
                        "整理完成：没有值得记的（" + result.turns + " 轮）"
                    }
                    say(text)
                    android.util.Log.i("MemoryConsolidationScheduler", "organizeNow: $text")
                } finally {
                    _status.value = _status.value.copy(running = false, pendingTurns = 0)
                }
            }.onFailure { android.util.Log.e("MemoryConsolidationScheduler", "organizeNow failed", it) }
        }
    }

    /** 一次整理的候选与预算。 */
    private data class Plan(
        val sessionIds: List<Long>,
        val turns: Int,
        val transcriptChars: Int,
    )

    private suspend fun buildPlan(): Plan {
        val since = AppTime.nowMillis() - PENDING_WINDOW_MS
        val coarse = Graph.memoryProgress.pendingSessionIds(since, MAX_SCANNED_SESSIONS)
        val picked = mutableListOf<Long>()
        var turns = 0
        var chars = 0
        for (id in coarse) {
            if (picked.size >= MAX_SESSIONS_PER_RUN) break
            val session = Graph.chat.session(id) ?: continue
            val msgs = Graph.chat.messagesOf(id).filter {
                it.id > session.clearBefore && it.role in listOf("user", "assistant") && it.content.isNotBlank()
            }
            if (msgs.isEmpty()) continue
            val lite = msgs.map { MsgLite(it.id, it.role, it.createdAt, it.content) }
            val consolidatedAt = Graph.memoryProgress.of(id) ?: initialProgress(lite)
            val pending = pendingTurnsOf(lite, consolidatedAt, session.updatedAt)
            if (pending.turns.isEmpty()) continue
            // transcript 预算：至少带上一个，否则第一个之后谁都轮不到
            if (picked.isNotEmpty() && chars + pending.chars > MAX_TRANSCRIPT_CHARS) continue
            picked += id
            turns += pending.totalTurns
            chars += pending.chars
        }
        return Plan(picked, turns, chars)
    }

    /**
     * 第三步起，这里接真整理器（[MemoryConsolidator]）。
     *
     * 规则：失败不自动重试（由调度器标记 stalled，等下一轮或用户手动）；
     * 成功才把进度推进交给整理器自己（它按"本批最后一条用户消息"推进）。
     */
    private suspend fun execute(plan: Plan) {
        if (plan.sessionIds.isEmpty()) {
            say("没有待整理的会话")
            android.util.Log.i("MemoryConsolidationScheduler", "execute: 无事可做")
            return
        }
        say("正在整理 ${plan.turns} 轮 / ${plan.sessionIds.size} 个会话…")
        val result = MemoryConsolidator.run(plan.sessionIds, fullReview = false)
        if (result.error != null) {
            synchronized(lock) { failed = true }
            _status.value = _status.value.copy(stalled = true, lastRunNote = "整理失败：${result.error}")
            android.util.Log.e("MemoryConsolidationScheduler", "execute failed: ${result.error}")
            return
        }
        synchronized(lock) { failed = false }
        val note = if (result.hasChanges) {
            "整理完成：新增 ${result.added}、更新 ${result.updated}、拦截 ${result.blocked}"
        } else {
            "整理完成：没有值得记的（${result.turns} 轮）"
        }
        say(note)
        android.util.Log.i("MemoryConsolidationScheduler", "execute: $note")
    }

    // ---------- 纯逻辑（可单测，不碰数据库） ----------

    internal data class MsgLite(val id: Long, val role: String, val createdAt: Long, val content: String)

    /** 一个用户轮次：一条用户消息 + 直到下一条用户消息之前的回复。 */
    internal data class Turn(val messages: List<MsgLite>) {
        val userMessage: MsgLite get() = messages.first()
    }

    internal data class Pending(
        val turns: List<Turn>,
        val totalTurns: Int,
        /** 这批轮次的字符量（粗略的转录预算） */
        val chars: Int,
        /** 整理完这批后进度应推到的时间戳；没有可推进的内容时为 null */
        val consolidatedThrough: Long?,
    )

    /** 按用户消息切成轮。 */
    internal fun turnsOf(messages: List<MsgLite>): List<Turn> {
        val starts = messages.indices.filter { messages[it].role == "user" }
        return starts.mapIndexed { i, s ->
            val end = starts.getOrElse(i + 1) { messages.size }
            Turn(messages.subList(s, end))
        }
    }

    /** 首次跟踪：只回看最近 [MAX_TURNS_PER_SESSION] 个**有文字**的用户轮次，起点定在它前面 1ms。 */
    internal fun initialProgress(messages: List<MsgLite>): Long {
        val first = turnsOf(messages)
            .filter { it.userMessage.content.isNotBlank() }
            .takeLast(MAX_TURNS_PER_SESSION)
            .firstOrNull() ?: return 0L
        return first.userMessage.createdAt - 1
    }

    /**
     * [consolidatedAt] 之后新增的用户轮次。
     *
     * 按**用户消息创建时间**判断而不是按下标：重新生成回复不改用户消息的时间，不会重复整理；
     * 编辑用户消息会产生新消息，算新的一轮。积压超过一次能处理的量时只整理最早的一批，
     * 进度只推进到下一批之前 —— 直接推到末尾会让没交给模型的轮次再也不会被整理。
     * 来源: rikkahub/data/ai/MemoryConsolidator.kt:355-392。
     */
    internal fun pendingTurnsOf(
        messages: List<MsgLite>,
        consolidatedAt: Long,
        updatedAt: Long,
    ): Pending {
        val turns = turnsOf(messages)
        var pending = turns.filter { it.userMessage.createdAt > consolidatedAt }
        var through = maxOf(updatedAt, turns.lastOrNull()?.messages?.last()?.createdAt ?: 0L)
        val batch = pending.take(minOf(MAX_TURNS_PER_SESSION, MAX_TURNS_PER_RUN))
        val chars = batch.sumOf { t -> t.messages.sumOf { it.content.length } }
        if (batch.isNotEmpty()) {
            val batchEnd = batch.last().userMessage.createdAt
            pending.drop(batch.size).firstOrNull { it.userMessage.createdAt > batchEnd }?.let { next ->
                through = next.userMessage.createdAt - 1
            }
            through = maxOf(through, batchEnd)
        }
        return Pending(
            turns = batch,
            totalTurns = pending.size,
            chars = chars,
            consolidatedThrough = through.takeIf { it > consolidatedAt },
        )
    }
}
