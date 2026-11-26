package io.github.m0use001.planability.ai

import io.github.m0use001.planability.core.AppTime
import io.github.m0use001.planability.core.DeviceFeatures
import io.github.m0use001.planability.data.ChatMessage
import io.github.m0use001.planability.data.Graph
import io.github.m0use001.planability.data.ModelConfig
import io.github.m0use001.planability.ai.ptl.PtlApprovalBridge
import io.github.m0use001.planability.ai.ptl.Risk
import io.github.m0use001.planability.ai.ptl.ToolCallReq
import io.github.m0use001.planability.ai.ptl.ToolDef
import io.github.m0use001.planability.ai.ptl.ToolExecResult
import io.github.m0use001.planability.ai.ptl.ToolRouter
import io.github.m0use001.planability.ai.ptl.ToolExecutor
import io.github.m0use001.planability.ai.ptl.ToolRegistry
import io.github.m0use001.planability.ai.ptl.enabledDomains
import io.github.m0use001.planability.ai.ptl.enabledPlugins
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import kotlin.coroutines.resume

/** 会话页实时状态：DB 历史 + 流式过程中的增量都汇到这里 */
data class ChatUiState(
    val sessionId: Long = 0,
    val messages: List<ChatMessage> = emptyList(),
    /** 正在流式生成的 assistant 消息 id */
    val streamingId: Long? = null,
    /** 持久化错误提示（发送失败等） */
    val notice: String? = null,
    /** v8：本轮上下文预计 tokens（上次 assemble 结果，输入栏用量 chip 显示） */
    val ctxTokens: Int = 0,
    /**
     * v53：上下文压缩进行中（滚动摘要 / 手动 /compact 的模型调用阶段）。
     * 用户实报「压缩没有进度条」——压缩要跑几十秒的模型调用，期间界面毫无信号，
     * 看起来像卡死/没生效。UI 据此显示压缩中指示。
     */
    val compacting: Boolean = false,
    /**
     * 2026-11-11：**工具调用参数的流式生成期**已出现已知工具名（首个 ToolCallDelta.name）。
     * 本地模型吐几千字 JSON 参数要一两分钟，这期间消息行 content 还是空的、
     * 工具也还没开始执行（LiveToolRow 不显示）——界面只剩「思考中」，用户以为卡死
     * （session 1293 三轮 file_library_write 每轮间隔 2 分钟实录）。
     * UI 据此把「思考中」升级为「正在调用 <工具>」。执行开始/轮结束/出错时清空。
     */
    val callingTool: String = ""
) {
    val streaming: Boolean get() = streamingId != null
}

/** v8 @勾连引用：kind = memory/skill/schedule/todo/note/assistant/domain/plugin（domain/plugin 只启用能力不注入内容） */
data class LinkedRef(val kind: String, val id: Long, val label: String)

/**
 * AI 会话引擎（M1）：SSE 流式对话编排。
 * - 发送：插 user 行 + assistant 占位行（streaming）→ 收集增量（内存状态实时更新，DB 节流落盘）
 * - 停止：协程 cancel → status=stopped
 * - 重生成：删最后一条 assistant 及其后 → 以最后 user 重跑
 * - 自动命名：首轮完成且默认标题 → worker（未启用回退主槽）起 4-5 字主题
 */
object ChatEngine {

    private val states = mutableMapOf<Long, MutableStateFlow<ChatUiState>>()

    /**
     * v53：最近一次压缩被拒绝的原因（null = 没跑过/成功了）。
     * 用户实报「模型压缩文本失败」但界面只有一句笼统话——把拒绝原因原样带给 UI，
     * 用户能立刻分清「本来就没有可压内容」和「模型调用失败」。
     */
    @Volatile var lastCompactBlockedReason: String? = null
        private set
    private val jobs = mutableMapOf<Long, Job>()
    private val emptyFlow = MutableStateFlow(ChatUiState())

    /** 订阅某会话状态（id=0 返回共享空态） */
    fun stateOf(sessionId: Long): StateFlow<ChatUiState> = flowOf(sessionId)

    private fun flowOf(sessionId: Long): MutableStateFlow<ChatUiState> =
        if (sessionId <= 0L) emptyFlow else states.getOrPut(sessionId) { MutableStateFlow(ChatUiState(sessionId)) }

    /** 打开会话时拉历史 */
    suspend fun load(sessionId: Long) {
        if (sessionId <= 0L) return
        val msgs = Graph.chat.messagesOf(sessionId)
        val f = states.getOrPut(sessionId) { MutableStateFlow(ChatUiState(sessionId)) }
        val streamingMsg = msgs.firstOrNull { it.status == "streaming" }
        // 上次异常退出残留的 streaming 行 → 标记 stopped；
        // 本进程正在流式生成的行（jobs 里有活动任务）不能动，否则误标 stopped
        if (streamingMsg != null && jobs[sessionId] == null) {
            val fixed = streamingMsg.copy(status = "stopped")
            Graph.chat.updateMessage(fixed)
            f.value = f.value.copy(
                sessionId = sessionId,
                messages = msgs.map { if (it.id == streamingMsg.id) fixed else it }
            )
        } else {
            f.value = f.value.copy(sessionId = sessionId, messages = msgs)
        }
        // v10：进会话即显示上下文用量估算（按 clearCursor 后的有效消息）
        //
        // 2026-11-17 用户报障「上下文百分比一直跳来跳去」（一会 37% 一会又变成百分之几）：
        // 这里原来写的是**裸历史**估算（清游标后逐条估 token，不受发送预算裁剪），
        // 而发送 / 压缩 / 重算写的是 assemble() 的**实际载荷**（历史按
        // MAX_HISTORY_TOKENS=12000 裁剪后）——两个口径抢同一个数字：
        // 进会话显示 37%（92 万字会话 ≈ 37 万 token ÷ 1M 窗口），发一轮又变 1%，
        // 于是来回跳。统一到 assemble 口径（唯一真源）：数字稳定，含义明确
        // ＝「这一轮实际占用多少上下文窗口」。
        recomputeCtxTokens(sessionId)
    }

    /** 会话模型解析：会话行 model 名 → 供应商表启用模型；未命中回退全局 active（v7） */
    private suspend fun resolveSessionCfg(sessionId: Long): ModelConfig {
        val name = Graph.chat.session(sessionId)?.model.orEmpty()
        return Graph.aiProviders.configByName(name) ?: AiClient.config
    }

    /** 插入 system 痕迹行（如"已切换模型：X"），仅 UI 展示，transcript 不发送 */
    suspend fun insertSystemNote(sessionId: Long, text: String) {
        val m = ChatMessage(sessionId = sessionId, role = "system", content = text, status = "done", createdAt = AppTime.nowMillis())
        val id = Graph.chat.insertMessage(m)
        val f = flowOf(sessionId)
        f.value = f.value.copy(messages = f.value.messages + m.copy(id = id))
    }

    /**
     * v56：新会话落角色卡开场白（酒馆 `first_mes`）。
     *
     * 只在**该会话还没有任何消息**时插，重复调用幂等。没有卡或卡没开场白时什么都不做。
     * 开场白同样过宏展开（`{{char}}`/`{{user}}`）——卡里的开场白十有八九带占位符。
     */
    private suspend fun seedGreeting(sessionId: Long, assistantId: Long) {
        val aid = assistantId.takeIf { it > 0L } ?: return
        val assistant = runCatching { Graph.assistants.byId(aid) }.getOrNull() ?: return
        val card = AgentCard.parse(assistant.card, assistant.systemPrompt)
        if (card.greeting.isBlank()) return
        val existing = runCatching { Graph.chat.messagesOf(sessionId) }.getOrDefault(emptyList())
        if (existing.isNotEmpty()) return
        val userName = runCatching { Graph.settings.snapshot.value.accountName }
            .getOrDefault(io.github.m0use001.planability.ai.card.CardMacros.DEFAULT_USER)
        val text = io.github.m0use001.planability.ai.card.CardMacros
            .expand(card.greeting, assistant.name, userName)
            .trim()
        if (text.isBlank()) return
        Graph.chat.insertMessage(
            ChatMessage(
                sessionId = sessionId, role = "assistant", content = text,
                status = "done", model = "card-greeting", createdAt = AppTime.nowMillis(),
            )
        )
    }

    /**
     * 发送用户消息并开始流式生成。返回会话 id（新会话在此创建，挂载 assistantId 与 projectId）。
     * modelOverride 非空时以此作为会话模型（对话页切换模型）。
     */
    suspend fun send(
        sessionIdIn: Long,
        text: String,
        assistantId: Long = 0L,
        projectId: Long = 0L,
        modelOverride: String = "",
        linked: List<LinkedRef> = emptyList()
    ): Long {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return sessionIdIn
        var sessionId = sessionIdIn
        if (sessionId <= 0L) {
            val modelName = modelOverride.ifBlank { AiClient.config.modelName }
            val s = Graph.chat.createSession(model = modelName, assistantId = assistantId, projectId = projectId)
            sessionId = s.id
            // v56：角色卡开场白（酒馆 first_mes）——新会话首条落一条 assistant 消息。
            // 为什么放在这里而不是 UI：所有新会话入口（对话页/悬浮窗/中枢）都走 send，
            // 在 UI 里挂会漏；而且开场白本来就该进历史，模型下一轮才看得到「自己说过这句」。
            runCatching { seedGreeting(sessionId, assistantId) }
        } else if (modelOverride.isNotBlank()) {
            Graph.chat.setSessionModel(sessionId, modelOverride)
        }
        val f = flowOf(sessionId)
        if (f.value.streaming) return sessionId

        val now = AppTime.nowMillis()
        val userMsg = ChatMessage(
            sessionId = sessionId, role = "user", content = trimmed, status = "done", createdAt = now
        )
        val userId = Graph.chat.insertMessage(userMsg)
        val cfg = resolveSessionCfg(sessionId) ?: AiClient.config
        // v40（成本优化 3.x，学 PURE OrchestrationBudget，ref-cost-study §2.2）：会话级 token 预算
        // 在**派发前**检查——硬上限超了就拒绝调用模型（不是事后报警）。默认无上限（护栏默认关）；
        // 设置 KV：ctx.sessionBudgetSoft / ctx.sessionBudgetHard。用户消息已落库，不吞输入。
        val check = UsageLedger.checkBudget(sessionId)
        if (check is UsageLedger.BudgetCheck.OverHard) {
            f.value = f.value.copy(
                sessionId = sessionId,
                messages = f.value.messages + userMsg.copy(id = userId),
                notice = check.reason
            )
            Graph.chat.touchSession(sessionId)
            return sessionId
        }
        val assistantMsg = ChatMessage(
            sessionId = sessionId, role = "assistant", content = "",
            model = cfg.modelName, status = "streaming", createdAt = AppTime.nowMillis()
        )
        val assistantId = Graph.chat.insertMessage(assistantMsg)
        f.value = f.value.copy(
            sessionId = sessionId,
            messages = f.value.messages + userMsg.copy(id = userId) + assistantMsg.copy(id = assistantId),
            streamingId = assistantId,
            callingTool = "",
            notice = null
        )
        Graph.chat.touchSession(sessionId)

        if (!cfg.enabled || cfg.baseUrl.isBlank()) {
            failMessage(sessionId, assistantId, "尚未配置模型（我的 → 设置 → 模型管理）")
            return sessionId
        }
        // routeQuery=trimmed：工具路由（v55 Step 2）按本轮提问选 top-K 工具子集
        startStream(sessionId, assistantId, cfg, temperature = 0.7, linked = linked, routeQuery = trimmed)
        return sessionId
    }

    /** 重新生成：删除最后一条 assistant 及其后，再对最后一条 user 重新生成 */
    suspend fun regenerate(sessionId: Long) {
        val f = flowOf(sessionId)
        if (f.value.streaming) return
        val msgs = Graph.chat.messagesOf(sessionId)
        val lastAssistant = msgs.lastOrNull { it.role == "assistant" } ?: return
        // v56 撤回记忆回收：被删流量里 memory_add 写入的记忆一并作废（标失效不删行），
        // 否则用户改口重发后 AI 还在被撤回的旧设定上打转（2026-11-14 用户实录）
        val revoked = runCatching {
            RecallReversal.revokeFor(sessionId, msgs.filter { it.id >= lastAssistant.id })
        }.getOrDefault(emptyList())
        Graph.chat.deleteFrom(sessionId, lastAssistant.id)
        if (revoked.isNotEmpty()) {
            insertSystemNote(sessionId, "已随撤回作废记忆：" + revoked.joinToString("、") + "；改口重发即可")
        }
        val kept = Graph.chat.messagesOf(sessionId)
        val cfg = resolveSessionCfg(sessionId) ?: AiClient.config
        val assistantMsg = ChatMessage(
            sessionId = sessionId, role = "assistant", content = "",
            model = cfg.modelName, status = "streaming", createdAt = AppTime.nowMillis()
        )
        val assistantId = Graph.chat.insertMessage(assistantMsg)
        f.value = f.value.copy(
            messages = kept + assistantMsg.copy(id = assistantId),
            streamingId = assistantId,
            callingTool = "",
            notice = null
        )
        if (!cfg.enabled || cfg.baseUrl.isBlank()) {
            failMessage(sessionId, assistantId, "尚未配置模型（我的 → 设置 → 模型管理）")
            return
        }
        startStream(sessionId, assistantId, cfg, temperature = 0.7)
    }

    /** 停止当前生成 */
    fun stop(sessionId: Long) {
        jobs[sessionId]?.cancel()
    }

    /** 清除页面级错误通知 */
    fun clearNotice(sessionId: Long) {
        flowOf(sessionId).value = flowOf(sessionId).value.copy(notice = null)
    }

    /**
     * 仅调试（`am start -a io.github.m0use001.planability.DEBUG_STREAM` 触发，BuildConfig.DEBUG 闸）：
     * 免模型模拟流式输出——走与真实流式完全相同的 UI 通路（ChatUiState.streamingId + 内容
     * 逐块增长），用于滚动跟随/冻结语义的实测。挂在第一个真实项目下（会话列表按真实项目渲染）。
     */
    suspend fun debugFakeStream(): Long {
        val projectId = runCatching { Graph.projects.all().firstOrNull()?.id ?: 0L }.getOrDefault(0L)
        val sessionId = Graph.chat.createSession(title = "滚动实测", model = "debug-stream", projectId = projectId).id
        val f = flowOf(sessionId)
        val now = AppTime.nowMillis()
        val userMsg = ChatMessage(sessionId = sessionId, role = "user", content = "scroll test", status = "done", createdAt = now)
        val userId = Graph.chat.insertMessage(userMsg)
        val assistantMsg = ChatMessage(sessionId = sessionId, role = "assistant", content = "", model = "debug", status = "streaming", createdAt = now)
        val assistantId = Graph.chat.insertMessage(assistantMsg)
        f.value = f.value.copy(
            sessionId = sessionId,
            messages = f.value.messages + userMsg.copy(id = userId) + assistantMsg.copy(id = assistantId),
            streamingId = assistantId
        )
        jobs[sessionId] = Graph.appScope.launch {
            val line = "这是一段**用于滚动**语义实测的长文本，没有任何*真实*含义，只需要足够长，让气泡在生成期间不断长高，从而复现**跟随**与**冻结**两种视口状态。"
            try {
                kotlinx.coroutines.delay(2500)   // 留时间打开会话
                val sb = StringBuilder()
                // 2026-11-05 用户两次校准：①逐字从尾部出现（不是每隔一阵冒出一坨）；
                // ②参照 deepseek v4.1 flash ≈ 200 token/s → 每 16ms（帧对齐）追加 4 字符
                // ≈ 240 字/s。1200 tick ≈ 19s 总时长，每 30 tick（0.5s）出一个段头共 40 段。
                var p = 0
                // 2026-11-17 滚动流畅性：这里原先**每 16ms 就写一次 state + 一次 DB**，
                // 与真实 SSE 路径（state 55ms 节流 / DB 600ms 节流）不同口径——
                // 调试钩子既然自称「走与真实流式完全相同的 UI 通路」，就该同口径，
                // 否则它测的是一条真实路径已不存在的更差路径。改后：
                //  · state 更新 55ms 节流（与 flushStateRowThrottled 同一窗口）
                //  · DB 落盘 600ms 节流（与 flushRow 同一窗口），收尾强制写
                var lastStateFlush = 0L
                var lastDbFlush = 0L
                repeat(1200) { tick ->
                    if (tick % 30 == 0) sb.append("\n\n#### 第 ").append(tick / 30 + 1).append(" 段\n")
                    val take = minOf(4, line.length - p)
                    sb.append(line, p, p + take)
                    p += take
                    if (p >= line.length) p = 0
                    val content = sb.toString()
                    val nowMs = System.currentTimeMillis()
                    if (nowMs - lastStateFlush >= 55) {
                        lastStateFlush = nowMs
                        f.value = f.value.copy(
                            messages = f.value.messages.map { if (it.id == assistantId) it.copy(content = content) else it }
                        )
                    }
                    if (nowMs - lastDbFlush >= 600) {
                        lastDbFlush = nowMs
                        Graph.chat.messageById(assistantId)?.let { Graph.chat.updateMessage(it.copy(content = content)) }
                    }
                    kotlinx.coroutines.delay(16)
                }
                val finalContent = sb.toString()
                f.value = f.value.copy(
                    messages = f.value.messages.map {
                        if (it.id == assistantId) it.copy(content = finalContent, status = "done") else it
                    },
                    streamingId = null
                )
                Graph.chat.messageById(assistantId)?.let { Graph.chat.updateMessage(it.copy(content = finalContent, status = "done")) }
            } catch (t: Throwable) {
                if (t !is CancellationException) {
                    f.value = f.value.copy(streamingId = null)
                    Graph.chat.messageById(assistantId)?.let { Graph.chat.updateMessage(it.copy(status = "stopped")) }
                }
            }
        }
        return sessionId
    }

    /** 工具结果行被 UI 撤销后追加标记（同步 DB 与实时状态） */
    suspend fun markToolRowRevoked(sessionId: Long, rowId: Long, describe: String) {
        Graph.chat.messageById(rowId)?.let { m ->
            val updated = m.copy(content = m.content + "\n[已撤销：" + describe + "]")
            Graph.chat.updateMessage(updated)
            val f = flowOf(sessionId)
            f.value = f.value.copy(messages = f.value.messages.map { if (it.id == rowId) updated else it })
        }
    }

    /** 组件留痕：向 assistant 消息尾部追加一行结果标记（qa 选择/日程终稿/清单勾选），重进会话后卡片可还原 */
    /**
     * 卡片留痕标记只应由 App 自己写入（卡片点「发送回答」→ onTrace → appendToMessage）。
     * 但模型会把上下文里见过的标记**仿写**进自己的回复末尾——2026-11-08 真机◆：
     * AI 在 ```qa 卡后面自己加了一行 `[qa-result] 0=0`，于是卡片一出现就显示
     * 「已回复 / 回答已发送 / 选项已选中」，用户根本没点过。
     * 所以模型流式输出落库前，把这些标记行剥掉；App 真正的留痕是之后 append 的，不受影响。
     */
    private val MODEL_ECHO_MARKERS =
        listOf("[qa-result]", "[qa-custom]", "[sched-sent]", "[check-done]")

    private fun stripModelEchoMarkers(s: String): String {
        if (s.isEmpty() || MODEL_ECHO_MARKERS.none { s.contains(it) }) return s
        return s.lines().filterNot { line ->
            val t = line.trim()
            MODEL_ECHO_MARKERS.any { t.startsWith(it) }
        }.joinToString("\n")
    }

    suspend fun appendToMessage(sessionId: Long, rowId: Long, line: String) {
        Graph.chat.messageById(rowId)?.let { m ->
            val updated = m.copy(content = m.content + "\n" + line)
            Graph.chat.updateMessage(updated)
            val f = flowOf(sessionId)
            f.value = f.value.copy(messages = f.value.messages.map { if (it.id == rowId) updated else it })
        }
    }

    // ---------- M4 审批桥（confirm/danger 确认卡） ----------

    data class ApprovalRequest(
        val sessionId: Long,
        val toolName: String,
        val risk: String,
        val argsJson: String,
        val canAlways: Boolean
    )

    private var approvalCont: kotlinx.coroutines.CancellableContinuation<Boolean>? = null
    private val _approval = MutableStateFlow<ApprovalRequest?>(null)

    /** DeadMansSwitch：确认卡无响应超时（毫秒） */
    private const val APPROVAL_TIMEOUT_MS = 120_000L

    /** 当前待审批请求（全局同一时刻至多一个） */
    fun pendingApproval(): StateFlow<ApprovalRequest?> = _approval

    init {
        ToolExecutor.approvalBridge = object : PtlApprovalBridge {
            override suspend fun requestApproval(
                sessionId: Long,
                tool: ToolDef,
                argsJson: String
            ): Boolean = awaitApproval(sessionId, tool, argsJson)
        }
    }

    private suspend fun awaitApproval(sessionId: Long, tool: ToolDef, argsJson: String): Boolean {
        // DeadMansSwitch：确认卡 120 秒无响应自动拒绝，防止挂死执行链
        return kotlinx.coroutines.withTimeoutOrNull(APPROVAL_TIMEOUT_MS) {
            suspendCancellableCoroutine { cont ->
                approvalCont = cont
                _approval.value = ApprovalRequest(
                    sessionId = sessionId,
                    toolName = tool.name,
                    risk = tool.risk.name.lowercase(),
                    argsJson = argsJson,
                    canAlways = tool.risk == Risk.CONFIRM
                )
                cont.invokeOnCancellation {
                    _approval.value = null
                    approvalCont = null
                }
            }
        } ?: false
    }

    /** UI 确认卡回调：granted=放行；always=总是允许（仅 confirm 级） */
    fun resolveApproval(granted: Boolean, always: Boolean) {
        val cont = approvalCont ?: return
        val req = _approval.value
        approvalCont = null
        _approval.value = null
        if (granted && always && req?.canAlways == true) {
            Graph.appScope.launch {
                // 按会话存：只在当前会话里免确认，换到别的会话（尤其计划模式）不能继承
                runCatching {
                    Graph.settings.setPtlAlways(
                        req.sessionId, Graph.settings.ptlAlways(req.sessionId).plus(req.toolName)
                    )
                }
            }
        }
        cont.resume(granted)
    }

    /**
     * M3 动作会话：提纯/抽取等一次性任务也走会话留档（流式写库，可回看可审计）。
     * - sessionIdIn=0 时按 assistantKey+title 新建会话；否则在既有会话追加一轮
     * - systemOverride 非空时替换助手 system（时间仍附加），用于抽取等专用提示词
     * 返回 (sessionId, 最终文本)。调用方协程被取消 → 落盘 stopped 后向上抛。
     */
    suspend fun runAction(
        assistantKey: String,
        title: String,
        userText: String,
        systemOverride: String? = null,
        sessionIdIn: Long = 0L,
        useWorker: Boolean = false,
        /** 无状态动作：只发 systemOverride+userText，不带会话历史/上下文组件。
         *  提纯/抽取这类"每次输入自包含"的一次性任务必须开——否则会话历史逐轮累积，
         *  后面的轮次把之前所有天的原文全部重发一遍，token 成本随天数线性膨胀。 */
        stateless: Boolean = false,
        /** 2026-11-03 信息提取项目化：**发送与存储分离**——消息流里存占位（原文已归档），
         *  模型本轮仍收到完整 userText。提纯轮用：原文只存在于当轮请求与 chat_digests.rawText，
         *  历史里只留占位，下一轮上下文只含此前提纯总结（用户拍板的「提纯后不再塞原文」）。 */
        storeUserText: String? = null
    ): Pair<Long, String> {
        // v45：worker 槽走"可用性"解析——本地端点没起来时回退主模型，
        // 否则后台提纯每次都白跑（占位消息留空 = 用户看到"提纯了但没结果"）
        val cfg = if (useWorker) AiClient.usableWorkerConfig() else AiClient.config
        if (!cfg.enabled || cfg.baseUrl.isBlank()) throw AiException("尚未配置模型（我的 → 设置 → 模型管理）")
        val sessionId = if (sessionIdIn > 0L) sessionIdIn else {
            val assistant = Graph.assistants.byKey(assistantKey)
            Graph.chat.createSession(
                // 空标题按"新对话"建，完成后由 scheduleNaming 按内容自动起名（提纯/重提纯等动作会话）
                title = title.ifBlank { "新对话" },
                model = cfg.modelName,
                assistantId = assistant?.id ?: 0L
            ).id
        }
        val session = Graph.chat.session(sessionId) ?: throw AiException("动作会话不存在")
        val assistant = session.assistantId.takeIf { it > 0L }
            .let { if (it != null) runCatching { Graph.assistants.byId(it) }.getOrNull() else null }

        val now = AppTime.nowMillis()
        Graph.chat.insertMessage(
            ChatMessage(
                sessionId = sessionId, role = "user",
                // storeUserText != null：库里存占位（原文已归档），模型本轮收原文（payload 分支替换）
                content = storeUserText ?: userText, status = "done", createdAt = now
            )
        )
        val placeholderId = Graph.chat.insertMessage(
            ChatMessage(sessionId = sessionId, role = "assistant", content = "", model = cfg.modelName, status = "streaming", createdAt = AppTime.nowMillis())
        )
        Graph.chat.touchSession(sessionId)

        val payload = if (stateless && systemOverride != null) {
            // 无状态动作：不带会话历史与上下文组件，只有 systemOverride+本轮输入
            val t = AppTime.now()
            val system = systemOverride + "\n当前时间：${t.year}年${t.monthValue}月${t.dayOfMonth}日。"
            listOf(ChatApiMessage("system", system), ChatApiMessage("user", userText))
        } else {
            val plan = ContextEngine.assemble(sessionId, session)
            val msgs = if (systemOverride != null) {
                val t = AppTime.now()
                val system = systemOverride + "\n当前时间：${t.year}年${t.monthValue}月${t.dayOfMonth}日。"
                listOf(ChatApiMessage("system", system)) + plan.messages.drop(1)
            } else plan.messages
            // storeUserText：assemble 从消息表读回的是**占位**——末尾本轮 user 换回完整原文，
            // 保证模型收到原文，而历史里的既有轮次仍是占位（原文只进当轮）
            if (storeUserText != null && msgs.lastOrNull()?.role == "user") {
                msgs.dropLast(1) + ChatApiMessage("user", userText)
            } else msgs
        }

        // 注册调用方 Job：让 load() 的残留修复与 stop() 语义一致
        val callerJob = kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]
        if (callerJob != null) jobs[sessionId] = callerJob

        var content = ""
        var reasoning = ""
        var tokensIn = 0
        var tokensOut = 0
        var lastFlush = 0L
        var endStatus = "done"
        var errorMsg = ""

        suspend fun flush(force: Boolean) {
            val t = System.currentTimeMillis()
            if (force || t - lastFlush > 600) {
                lastFlush = t
                Graph.chat.messageById(placeholderId)?.let {
                    Graph.chat.updateMessage(it.copy(content = content, reasoning = reasoning, tokensIn = tokensIn, tokensOut = tokensOut))
                }
            }
        }

        try {
            // 2026-11-02 多账号池：同名模型多账号时 429/5xx 自动冷却换号（无账号池时零开销直发）
            io.github.m0use001.planability.ai.pool.RoutedStream.streamChatPooled(
                cfg.modelName, cfg, assistant?.temperature ?: 0.3, null, "",
                send = { c -> SseTransport.streamChat(c, payload, assistant?.temperature ?: 0.3) }
            ).collect { ev ->
                when (ev) {
                    is StreamEvent.Content -> { content += ev.text; flush(false) }
                    is StreamEvent.Reasoning -> { reasoning += ev.text; flush(false) }
                    is StreamEvent.ToolCallDelta -> Unit
                    is StreamEvent.Usage -> { tokensIn = ev.tokensIn; tokensOut = ev.tokensOut }
                    // 这条是「非主循环」的一次性调用（payload 直发、无工具）；
                    // 服务端搜索来源不会出现在这里（没传 tools），按空处理。
                    is StreamEvent.Sources -> Unit
                    is StreamEvent.Completed -> Unit
                    is StreamEvent.Failed -> { endStatus = "error"; errorMsg = ev.message; throw StreamFailure(ev.message) }
                }
            }
        } catch (e: StreamFailure) {
            // 结束态统一落盘
        } catch (e: CancellationException) {
            endStatus = "stopped"
            withContext(NonCancellable) {
                Graph.chat.messageById(placeholderId)?.let {
                    Graph.chat.updateMessage(it.copy(content = stripModelEchoMarkers(content), reasoning = reasoning, status = "stopped", tokensIn = tokensIn, tokensOut = tokensOut))
                }
                Graph.chat.touchSession(sessionId)
            }
            jobs.remove(sessionId)
            throw e
        }
        Graph.chat.messageById(placeholderId)?.let {
            Graph.chat.updateMessage(
                it.copy(content = stripModelEchoMarkers(content), reasoning = reasoning, tokensIn = tokensIn, tokensOut = tokensOut, status = endStatus, error = errorMsg)
            )
        }
        Graph.chat.touchSession(sessionId)
        jobs.remove(sessionId)
        if (endStatus == "done") {
            ContextEngine.appendUsage(sessionId, "main", cfg.modelName, tokensIn, tokensOut)
            // 调用方没给标题（新建的"新对话"）→ 按首轮内容自动起名，动作会话不留无标题档
            if (title.isBlank()) scheduleNaming(sessionId)
        } else {
            throw AiException(errorMsg.ifBlank { "生成失败" })
        }
        return sessionId to content
    }

    /**
     * 删除 fromId 及其后所有消息（长按菜单的删除/重试用）。
     * 生成中拦截；user 消息删除后其文本可回填输入框实现"重试"。
     */
    suspend fun deleteFromId(sessionId: Long, fromId: Long) {
        val f = flowOf(sessionId)
        if (f.value.streaming) return
        // v56 撤回记忆回收（同 regenerate）：被删流量里 memory_add 新建的记忆一并作废
        val removed = Graph.chat.messagesOf(sessionId).filter { it.id >= fromId }
        val revoked = runCatching { RecallReversal.revokeFor(sessionId, removed) }.getOrDefault(emptyList())
        Graph.chat.deleteFrom(sessionId, fromId)
        // M5(a)：锚点是消息 id，删消息**天然自愈**——锚点被删时 summarizeStartIndex 会把
        // 剩下的（更早的、本就已摘要的）全部裁掉，新消息 id 只会更大、照常出现在历史里。
        // 旧计数游标在这类操作后会指向别处，这正是 v39 换锚点的原因。
        f.value = f.value.copy(messages = f.value.messages.filter { it.id < fromId }, notice = null)
        if (revoked.isNotEmpty()) {
            insertSystemNote(sessionId, "已随撤回作废记忆：" + revoked.joinToString("、") + "；改口重发即可")
        }
    }

    /** 工具调用增量累积器（SSE 分片 → 完整调用） */
    private class CallAcc {
        var id: String? = null
        val name = StringBuilder()
        val args = StringBuilder()
    }

    private fun startStream(
        sessionId: Long,
        assistantId: Long,
        cfg: io.github.m0use001.planability.data.ModelConfig,
        temperature: Double,
        linked: List<LinkedRef> = emptyList(),
        // v55 工具路由（Step 2）：本轮用户提问文本，用于按相关性选 top-K 工具子集。
        // null=重试/无新信号→路由不窄化、原样返回全量（弱信号不冒险）。
        routeQuery: String? = null
    ) {
        val f = flowOf(sessionId)
        jobs[sessionId]?.cancel()
        jobs[sessionId] = Graph.appScope.launch {
            // v12：流式期间前台保活（ColorOS 后台冻结防护）
            io.github.m0use001.planability.app.StreamKeepService.acquire(Graph.appContext)
            // M2 上下文引擎：chips 注入 + 游标/摘要裁剪 + manifest 落盘
            val session = Graph.chat.session(sessionId)
            if (session == null) {
                io.github.m0use001.planability.app.StreamKeepService.release(Graph.appContext)
                jobs.remove(sessionId)
                return@launch
            }
            // v8 @勾连：解析为本轮注入的上下文项 + 强制启用的域/插件
            val linkedItems = runCatching { ContextEngine.resolveLinked(linked) }.getOrDefault(emptyList())
            val forcedDomains = linked.filter { it.kind == "domain" }.map { it.label }.toSet()
            val forcedPlugins = linked.filter { it.kind == "plugin" }.map { it.label }.toSet()
            // v7 能力包：按启用域/插件过滤注入的工具集；v8 @勾连可对本轮强制启用
            // v12 修复：enabledDomains/enabledPlugins 的 null 语义是"全部启用"，
            // 之前 `?: emptySet()` 会把默认全开状态解析成空集 → 插件工具（web_search 等）全被过滤
            val enabledDomains = (ToolRegistry.enabledDomains() ?: ToolRegistry.DOMAINS.keys) + forcedDomains
            val enabledPlugins = (ToolRegistry.enabledPlugins() ?: ToolRegistry.PLUGINS.keys) + forcedPlugins
            // v11：注入 MCP 动态工具（启用服务器的 tools/list，带缓存）——每轮发送追加一次
            // v40（成本优化 2.1）：schema 先建、先追加 MCP，再进 assemble——工具 schema 的 token
            // 必须进上下文账本（面板/manifest 里多一个"工具 schema"项），原来完全不在估算里。
            // v55 工具路由（Step 2）：按本轮提问选 top-K 子集（弱信号原样返回全量）。
            // 在 ◆ 过滤与 MCP 追加之前——MCP 工具是动态的、不在打分池里，
            // 在路由之后追加；◆ 按可用性裁。routeQuery 为 null（重试）或
            // 弱信号时 route() 原样返回 full，零行为变化。
            // v55 智能体卡：卡策略决定脚手架——创作卡关掉工具循环（不构建 schema、不跑路由）
            val assistant = session.assistantId.takeIf { it > 0L }
                .let { if (it != null) runCatching { Graph.assistants.byId(it) }.getOrNull() else null }
            val card = AgentCard.parse(assistant?.card, assistant?.systemPrompt ?: "")
            val toolsEnabled = card.scaffoldTools
            val toolsJson = if (!toolsEnabled) JSONArray()
            else ToolRouter.route(ToolRegistry.toToolsJson(enabledDomains, enabledPlugins), routeQuery)
            // v43：◆ 只在 ◆ 可用时注入——没有执行通道就别让模型看见，
            // 否则它会把"环境不可用"当成工具坏了反复重试。isReadyQuiet 不写日志、不触发 binder 请求。
            // 2026-11-26 开源根治：◆ 状态经 DeviceFeatures.tools 查询（设备包的门控）；
            // 公开版 tools=null → 不进此分支（那里本来也没有 ◆）。
            if (toolsEnabled && DeviceFeatures.tools?.shellBridgeReady() != true) {
                for (i in toolsJson.length() - 1 downTo 0) {
                    val fn = toolsJson.optJSONObject(i)?.optJSONObject("function") ?: continue
                    if (fn.optString("name") == "◆") toolsJson.remove(i)
                }
            }
            if (toolsEnabled) {
                runCatching { io.github.m0use001.planability.ai.mcp.McpBridge.appendTools(toolsJson) }
            }
            val plan = ContextEngine.assemble(
                sessionId, session, toolsEnabled = toolsEnabled,
                linked = linkedItems, toolsJson = toolsJson.toString()
            )
            ContextEngine.writeManifest(sessionId, plan)
            f.value = f.value.copy(ctxTokens = plan.totalTokens)
            val effectiveTemperature = card.temperature ?: assistant?.temperature ?: temperature

            var tokensIn = 0
            var tokensOut = 0
            // v40（成本优化 1.4）：前缀缓存命中的输入 token（端点没上报保持 null，不写 0）
            var cachedIn: Int? = null
            var lastFlush = 0L
            // 2026-11-17 滚动流畅性 P0：SSE 分片逐 token 进来，Content/Reasoning 原来
            // 每个分片都 updateStateRow → messages.map 整列表拷贝一次 state。长会话
            // （数千条）里这就是每 token 一次 O(n) 拷贝，下游 nodes/buildChatNodes、
            // 公式预热、跟随 snapshotFlow 全被拖成每 token 重建——流式期间滚动必掉帧。
            // 改 55ms 节流（≈3 帧窗口）：累积量 roundContent/roundReasoning 本来就是
            // 全量真值，晚两帧写 state 不丢字；结束路径（done/stopped/error/工具轮收尾）
            // 都直接走 updateStateRow 即时写，最终态不丢。
            var lastStateFlush = 0L
            var currentRowId = assistantId
            var lastContent = ""
            var lastReasoning = ""
            var endStatus = "done"
            var errorMsg = ""

            suspend fun flushRow(content: String, reasoning: String, force: Boolean) {
                val now = System.currentTimeMillis()
                if (force || now - lastFlush > 600) {
                    lastFlush = now
                    Graph.chat.messageById(currentRowId)?.let {
                        Graph.chat.updateMessage(it.copy(content = content, reasoning = reasoning))
                    }
                }
            }

            fun updateStateRow(rowId: Long, transform: (ChatMessage) -> ChatMessage) {
                f.value = f.value.copy(messages = f.value.messages.map { if (it.id == rowId) transform(it) else it })
            }

            /** 节流版：只在距上次写 state ≥55ms 时才 commit。累积量 content/reasoning
             * 本就是全量真值，晚两帧不丢字；结束路径走 updateStateRow（即时）兜底最终态。 */
            fun flushStateRowThrottled(rowId: Long, content: String, reasoning: String) {
                val now = System.currentTimeMillis()
                if (now - lastStateFlush < 55) return
                lastStateFlush = now
                updateStateRow(rowId) { it.copy(content = content, reasoning = reasoning) }
            }

            suspend fun finalizeRow(
                rowId: Long, content: String, reasoning: String,
                status: String, error: String, withTokens: Boolean, toolCallsJson: String? = null
            ) {
                Graph.chat.messageById(rowId)?.let {
                    Graph.chat.updateMessage(
                        it.copy(
                            content = stripModelEchoMarkers(content), reasoning = reasoning,
                            tokensIn = if (withTokens) tokensIn else it.tokensIn,
                            tokensOut = if (withTokens) tokensOut else it.tokensOut,
                            status = status, error = error,
                            toolCallsJson = toolCallsJson ?: it.toolCallsJson
                        )
                    )
                }
                Graph.chat.touchSession(sessionId)
            }

            try {
                // v8 会话级工具权限 {"mode":..., "allow":[],"deny":[]}；2026-10-29 加 mode。
                // allow 一律按档位收窄：老会话（没有 mode）由 PermPolicy 反推档位后取交集，
                // 于是旧的「自动」语义（当时含 fs_*/phone_*/系统配置）自动收紧成新语义，
                // 不必等用户重新选一次档位。
                val permMode = io.github.m0use001.planability.shared.PermPolicy.modeForPermJson(session.permJson)
                val sessionPerm = runCatching { org.json.JSONObject(session.permJson) }.getOrNull()
                val rawAllow = sessionPerm?.optJSONArray("allow")
                    ?.let { a -> (0 until a.length()).map { a.optString(it) }.toSet() } ?: emptySet()
                val permAllow = if (session.permJson.isBlank()) emptySet()
                else rawAllow intersect io.github.m0use001.planability.shared.PermPolicy.allowedSet(permMode)
                val permDeny = sessionPerm?.optJSONArray("deny")
                    ?.let { a -> (0 until a.length()).map { a.optString(it) }.toSet() } ?: emptySet()
                val history = plan.messages.toMutableList()
                val callHistory = mutableListOf<String>()
                var guardTrips = 0
                var rescueUsed = false
                // 2026-11-01：上限 6→12。用户「让 AI 移动文件，弹一堆申请全点完对话就卡住了」：
                // 「线性代数项目文件整理」正好 6 个工具轮（探测 4 + fs_move×6 里的一轮 6 连发），
                // 预算耗尽时最后的总结轮根本没跑。多文件/多步操作 6 轮不够；防死循环有
                // LoopDetector 熔断（同参 3 连 → 熔断，两次熔断终止）兜底，放宽预算是安全的。
                // 2026-11-08 用户「工具调用次数上限锁死」：12 轮对"抓完整份章节表"这类任务
                // 根本不够（每页一次 web_fetch + 分页续读，动辄 20+ 轮），一到顶就断在半路。
                // 放到 24；防死循环不靠轮数，靠 LoopDetector（同参 3 连熔断、两次熔断终止）。
                rounds@ for (round in 0 until 24) {
                    var roundContent = ""
                    var roundReasoning = ""
                    // 2026-11-14：本轮服务端联网搜索的来源与轨迹（DeepSeek 云端工具）；
                    // 收尾时附到正文末尾，见 StreamEvent.Sources 分支。
                    var serverSources: List<io.github.m0use001.planability.shared.DeepSeekSources.Source> = emptyList()
                    var serverTraces: List<io.github.m0use001.planability.shared.DeepSeekSources.Trace> = emptyList()
                    val toolAcc = LinkedHashMap<Int, CallAcc>()
                    try {
                        // 2026-11-11 全链撞测 bug#3：exports/ 是虚拟前缀，ImageAttach.dataUrl
                        // 只查 filesDir/<rel> → 工具结果图片直通（PPT 每页快照）永远 miss。
                        // 引擎侧把本会话导出目录解析好传下去（dirFor 挂起，此处有协程上下文）。
                        val imageExportDir = runCatching {
                            io.github.m0use001.planability.ai.office.ExportStore.existingDirFor(
                                io.github.m0use001.planability.data.Graph.appContext, sessionId
                            )
                        }.getOrNull()
                        // 2026-11-02 多账号池（同 todoCreate 口径；工具轮的 toolsJson 原样透传）
                        io.github.m0use001.planability.ai.pool.RoutedStream.streamChatPooled(
                            cfg.modelName, cfg, effectiveTemperature, toolsJson,
                            thinking = session.thinking,
                            send = { c ->
                                SseTransport.streamChat(c, history, effectiveTemperature, toolsJson,
                                    thinking = session.thinking, exportDir = imageExportDir,
                                    // 2026-11-14 四象限：会话级联网开关（'' 跟随全局 / '1' 强制 / '0' 强制关）
                                    webSearch = when (session.webSearch) {
                                        "1" -> true
                                        "0" -> false
                                        else -> null
                                    })
                            }
                        ).collect { ev ->
                            when (ev) {
                                is StreamEvent.Content -> {
                                    roundContent += ev.text
                                    lastContent = roundContent
                                    flushStateRowThrottled(currentRowId, roundContent, roundReasoning)
                                    flushRow(roundContent, roundReasoning, false)
                                }
                                is StreamEvent.Reasoning -> {
                                    roundReasoning += ev.text
                                    lastReasoning = roundReasoning
                                    flushStateRowThrottled(currentRowId, roundContent, roundReasoning)
                                }
                                is StreamEvent.ToolCallDelta -> {
                                    val acc = toolAcc.getOrPut(ev.index) { CallAcc() }
                                    ev.id?.let { if (acc.id == null) acc.id = it }
                                    ev.name?.let {
                                        acc.name.append(it)
                                        // 2026-11-11 工具调用参数生成期活反馈：本地模型吐数千字
                                        // JSON 参数要一两分钟，此前 UI 只剩「思考中」（用户以为卡死）。
                                        // 首个工具名一到就把状态升上去，UI 把占位行文案换成「正在调用 X」。
                                        if (f.value.callingTool.isBlank()) {
                                            f.value = f.value.copy(callingTool = it.trim())
                                        }
                                    }
                                    ev.argsFragment?.let { acc.args.append(it) }
                                }
                                is StreamEvent.Usage -> {
                                    tokensIn = ev.tokensIn; tokensOut = ev.tokensOut; cachedIn = ev.cachedTokens
                                }
                                is StreamEvent.Sources -> {
                                    // 2026-11-14：服务端联网搜索的来源（DeepSeek 全家桶应用 API）。
                                    // 累积到本轮；收尾时附在正文末尾（用户能看到 AI 引了哪几篇，
                                    // AI 下一次回放历史时也能引用这些 URL）。空列表不发（见 AiStream）。
                                    serverSources = ev.list
                                    serverTraces = ev.traces
                                }
                                is StreamEvent.Completed -> Unit
                                is StreamEvent.Failed -> {
                                    endStatus = "error"
                                    errorMsg = ev.message
                                    throw StreamFailure(ev.message)
                                }
                            }
                        }
                    } catch (e: StreamFailure) {
                        // shouldSplitOnError 接进主循环（执行层落地）：收到的是 StreamEvent.Failed 的
                        // **原文**，不二次包装——主循环与压缩对同一错误的判断必须同源（B15；
                        // ref-runtime-study B1/B3：配额/参数/网络类错误降半没有救，这里本来就会 false）。
                        // 救援只改本次请求的内存副本（不碰 DB），一次发送最多救一次；
                        // 救不了就把原错误**原样**抛给外层收尾。
                        if (!rescueUsed && Compaction.shouldSplitOnError(e.msg)) {
                            val rescued = runCatching {
                                Compaction.rescueOversizedSend(sessionId, cfg, history.first().content, history.drop(1))
                            }.getOrNull()
                            if (rescued != null) {
                                rescueUsed = true
                                history.clear()
                                history += ChatApiMessage("system", rescued.first)
                                history += rescued.second
                                continue@rounds
                            }
                        }
                        throw e
                    }
                    if (toolAcc.isEmpty()) {
                        // 无工具调用：本轮即最终回答。守卫（2026-11-13 ◆ 消息 6859 实锤，
                        // phone-b 取证指派）：模型有极低概率**整轮零内容返回**（读完 场景.md 之后、
                        // 文件读取之前）——finalize 成 done+空内容就是静默空气泡，用户以为 AI 死了。
                        // 置 error 走统一错误收尾（红条+可重新生成），别把「没话说」装成「说完了」。
                        if (roundContent.isBlank() && roundReasoning.isBlank()) {
                            endStatus = "error"
                            errorMsg = "模型这一轮没有返回任何内容。点重新生成再试一次；连续出现就换模型（模型面板里切）。"
                            break@rounds
                        }
                        // 2026-11-14：服务端联网搜索的来源附在正文末尾（用户看到引了哪几篇；
                        // AI 回放历史时也能直接引用这些 URL）。没来源 = 完全无变化。
                        val sourcesBlock = if (serverSources.isNotEmpty())
                            "\n\n" + io.github.m0use001.planability.shared.DeepSeekSources
                                .render(serverSources, serverTraces)
                        else ""
                        finalizeRow(currentRowId, roundContent + sourcesBlock, roundReasoning, "done", "", withTokens = true)
                        updateStateRow(currentRowId) {
                            it.copy(
                                content = roundContent + sourcesBlock, reasoning = roundReasoning,
                                tokensIn = tokensIn, tokensOut = tokensOut, status = "done", error = ""
                            )
                        }
                        if (f.value.callingTool.isNotBlank()) f.value = f.value.copy(callingTool = "")
                        break@rounds
                    }
                    // 工具调用轮：落库 assistant(tool_calls) → 执行 → tool 结果行 → 下一轮
                    // callId 统一生成（编码工具名），callsJson 与 tool 行共用
                    val calls = toolAcc.values.map { acc ->
                        val callName = acc.name.toString().trim()
                        Triple(
                            callName.ifBlank { "tool" } + "#" + (acc.id ?: ("call_" + round + "_" + System.nanoTime())),
                            callName,
                            acc.args.toString()
                        )
                    }
                    val callsJson = JSONArray().apply {
                        calls.forEach { (cid, cname, cargs) ->
                            put(
                                JSONObject()
                                    .put("id", cid)
                                    .put("type", "function")
                                    .put("function", JSONObject().put("name", cname).put("arguments", cargs))
                            )
                        }
                    }.toString()
                    finalizeRow(currentRowId, roundContent, roundReasoning, "done", "", withTokens = false, toolCallsJson = callsJson)
                    updateStateRow(currentRowId) { it.copy(content = roundContent, reasoning = roundReasoning, status = "done", toolCallsJson = callsJson) }
                    // 参数生成期结束：callingTool 交给 LiveToolRow（执行期）接棒
                    if (f.value.callingTool.isNotBlank()) f.value = f.value.copy(callingTool = "")
                    history.add(
                        ChatApiMessage(
                            "assistant", roundContent,
                            toolCallsJson = callsJson,
                            // 思考模型必须回传，否则下一轮 400（真机 09:07 抓到）
                            reasoning = roundReasoning
                        )
                    )

                    for ((callId, callName, callArgs) in calls) {
                        // LoopDetector：同一工具+同参数连续 3 次 → 熔断（不再执行，回灌护栏提示）
                        val sig = callName + "|" + callArgs.trim()
                        callHistory.add(sig)
                        val forcedGuard = callHistory.size >= 3 && callHistory.takeLast(3).all { it == sig }
                        val result = if (forcedGuard && guardTrips < 2) {
                            guardTrips++
                            ToolExecResult(
                                false,
                                "[FAIL] 护栏：同一工具与参数已连续重复调用 3 次。停止重试，直接基于已获得的信息回答用户，或明确说明做不到。",
                                "护栏熔断 " + callName
                            )
                        } else {
                            ToolExecutor.run(sessionId, ToolCallReq(callId, callName, callArgs), permAllow, permDeny, permMode)
                        }
                        val toolRow = ChatMessage(
                            sessionId = sessionId, role = "tool", content = result.payload,
                            toolCallId = callId, status = "done", createdAt = AppTime.nowMillis()
                        )
                        val toolRowId = Graph.chat.insertMessage(toolRow)
                        history.add(ChatApiMessage("tool", result.payload, toolCallId = callId))
                        f.value = f.value.copy(messages = f.value.messages + toolRow.copy(id = toolRowId))
                    }
                    if (guardTrips >= 2) {
                        // 二次熔断：彻底终止执行链
                        finalizeRow(currentRowId, lastContent, lastReasoning, "done", "", withTokens = true)
                        updateStateRow(currentRowId) { it.copy(content = lastContent, reasoning = lastReasoning, status = "done") }
                        break@rounds
                    }
                    // 继续轮：插入新的 streaming assistant 行
                    val newRow = ChatMessage(
                        sessionId = sessionId, role = "assistant", content = "", model = cfg.modelName,
                        status = "streaming", createdAt = AppTime.nowMillis()
                    )
                    val newRowId = Graph.chat.insertMessage(newRow)
                    f.value = f.value.copy(messages = f.value.messages + newRow.copy(id = newRowId), streamingId = newRowId)
                    currentRowId = newRowId
                    lastContent = ""
                    lastReasoning = ""
                }
                // 轮数用尽：收尾最后一行。不能静默落空——预算耗尽时最后一行是空的 streaming
                // 占位（前一轮工具行之后新建的），finalize 成 done+空内容在界面上就是
                // 「审批全点完了却没下文」（2026-11-01 用户实测）。最后一行仍为空（只有
                // 预算耗尽这条路径会这样；两条 break 路径的 finalize 已带内容）时，
                // 落一句可见说明，把"为什么停了"讲出来。
                val finalContent =
                    if (lastContent.isBlank() && lastReasoning.isBlank())
                        "（本轮工具调用已达 24 轮上限，先停在这里——已完成的步骤都在上面的工具结果里。" +
                            "发一句「继续」我就接着往下做；任务太长时也可以让我分段做（比如先列前 10 章）。）"
                    else lastContent
                finalizeRow(currentRowId, finalContent, lastReasoning, "done", "", withTokens = true)
                updateStateRow(currentRowId) { it.copy(content = finalContent, reasoning = lastReasoning, status = "done") }
            } catch (e: StreamFailure) {
                // 结束态统一落盘
            } catch (e: CancellationException) {
                endStatus = "stopped"
                withContext(NonCancellable) {
                    finalizeRow(currentRowId, lastContent, lastReasoning, "stopped", "", withTokens = true)
                    updateStateRow(currentRowId) { it.copy(content = lastContent, reasoning = lastReasoning, status = "stopped") }
                    f.value = f.value.copy(streamingId = null, callingTool = "")
                }
                jobs.remove(sessionId)
                io.github.m0use001.planability.app.StreamKeepService.release(Graph.appContext)
                throw e
            }
            if (endStatus == "error") {
                finalizeRow(currentRowId, lastContent, lastReasoning, "error", errorMsg, withTokens = true)
                updateStateRow(currentRowId) { it.copy(content = lastContent, reasoning = lastReasoning, status = "error", error = errorMsg) }
                f.value = f.value.copy(streamingId = null, callingTool = "", notice = errorMsg)
            } else {
                f.value = f.value.copy(streamingId = null, callingTool = "")
            }
            jobs.remove(sessionId)
            io.github.m0use001.planability.app.StreamKeepService.release(Graph.appContext)

            // 2026-11-08 用户「抓完网页弹出的浏览器不会自己关」：回合结束时按最后动作决定——
            // open/extract/back 只是"看一眼网页"，关掉；click/fill 多半在等用户登录/填表，留着别打断。
            runCatching {
                val bs = io.github.m0use001.planability.browser.BrowserSession
                if (bs.hostAlive()) {
                    when (bs.lastAction) {
                        "open", "extract", "back", "close", "" -> bs.close()
                    }
                }
            }

            // v42：会话镜像写穿（仅绑定真实文件夹的项目生效；done/error/stopped 都镜像——
            // 镜像要反映库里现状，失败与中止的内容也如实落盘，见 data/SessionMirror）
            io.github.m0use001.planability.data.SessionMirror.mirror(sessionId)

            if (endStatus == "done") {
                scheduleNaming(sessionId)
                scheduleSummaryAfterTurn(sessionId)
                ContextEngine.appendUsage(sessionId, "main", cfg.modelName, tokensIn, tokensOut, cachedIn)
                MemoryPipeline.afterTurn(sessionId)
            }
        }
    }

    private class StreamFailure(val msg: String) : Exception(msg)

    private suspend fun finalizeMessage(
        sessionId: Long, assistantId: Long, content: String, reasoning: String,
        tokensIn: Int, tokensOut: Int, status: String, error: String
    ) {
        val m = Graph.chat.messageById(assistantId) ?: return
        Graph.chat.updateMessage(
            m.copy(
                content = content, reasoning = reasoning,
                tokensIn = tokensIn, tokensOut = tokensOut,
                status = status, error = error
            )
        )
        Graph.chat.touchSession(sessionId)
    }

    private suspend fun failMessage(sessionId: Long, assistantId: Long, message: String) {
        val f = states[sessionId] ?: return
        finalizeMessage(sessionId, assistantId, "", "", 0, 0, "error", message)
        f.value = f.value.copy(
            messages = f.value.messages.map { if (it.id == assistantId) it.copy(status = "error", error = message) else it },
            streamingId = null,
            notice = message
        )
        // v42：绑定项目的对话镜像（这条早退路径不经过流结束收尾，单独喊一次）
        io.github.m0use001.planability.data.SessionMirror.mirror(sessionId)
    }

    // ---------- 游标与滚动摘要 ----------

    /**
     * 压缩模型调用的硬超时（P1-3，2026-11-10）：压缩是可跳过的辅助操作，
     * 超时=跳过本次、历史原样保留，绝不能让会话界面因模型卡住而永久冻结。
     */
    private const val COMPACT_TIMEOUT_MS = 180_000L
    private const val SUMMARY_THRESHOLD_TOKENS = 2800
    private const val SUMMARY_CAP_CHARS = 6000
    /** 条数兜底阈值：还不到 token 线，但已经攒了这么多条也压一次 */
    private const val SUMMARY_MIN_MESSAGES = 12

    /**
     * 清除之前上下文：游标推到最后一条消息，下一轮请求只带其后的增量。
     *
     * M5(b)：摘要锚点必须一起归 0。锚点还指着清除之前的某条消息时，`summarizeStartIndex`
     * 会把**清除之后新说的话**当成已摘要裁掉 —— 用户看不到任何报错，历史静默消失。
     *
     * 同时清 `memoryPrompt`：`/clear` 的语义是"更早的消息不再发送"，旧摘要正是那些消息的
     * 唯一代表；留着它等于没清（ref-runtime-study §7.2-M5 的"按产品意图决定"选这一边）。
     * `compact_markers` 是审计记录，不删。
     */
    suspend fun clearCursor(sessionId: Long): Boolean {
        val f = flowOf(sessionId)
        if (f.value.streaming) return false
        val last = Graph.chat.messagesOf(sessionId).lastOrNull()?.id ?: return false
        val s = Graph.chat.session(sessionId) ?: return false
        Graph.chat.updateSessionRaw(s.copy(clearBefore = last, lastSummarizedMessageId = 0, memoryPrompt = ""))
        return true
    }

    /**
     * 轮尾触发：仍走 appScope —— 新建轮次的 stop() 会取消上一轮的 job，
     * 顺手取消掉"正在写摘要"没有任何好处。
     */
    private fun scheduleSummaryAfterTurn(sessionId: Long) {
        Graph.appScope.launch { maybeScheduleSummary(sessionId) }
    }

    /**
     * 滚动摘要（轮次完成后 / 用户点 /compact）：除最近 `KEEP_RECENT` 条外，
     * 未摘要部分超阈值时用 worker 压缩 ≤200 字增量并入 memoryPrompt，
     * 写一条 compact marker，游标前推。
     *
     * @return **真的压成功了才 true**。被前置检查拦下、没有可压内容、AI 失败一律 false
     *   ——调用方据此决定要不要弹"已压缩"：没压成功就报成功会让用户以为历史已经裁过（M4）。
     */
    suspend fun maybeScheduleSummary(sessionId: Long, force: Boolean = false): Boolean {
        // 整个函数体都在 try 里：跑它的作用域是 Graph.appScope（SupervisorJob + 无异常处理器），
        // 任何逃出去的异常会直接杀进程。改成 suspend 时差点把原来那层 runCatching 弄丢——
        // 那时候 Room 的三个调用（session / messagesOf / updateSessionRaw）是裸的，
        // 一次 SQLiteException 就能在"每轮结束"这条路上把应用带走。
        //
        // v53：压缩中状态**只包模型调用那一段**（summarizeNow 内部），不在这里置位——
        // 轮尾自动摘要每轮都可能被调用，进来就亮指示器会让它闪一下（大多数时候直接 return false）。
        // 置位点在 summarizeNow 里真正准备发请求之前。
        return try {
            summarizeNow(sessionId, force)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.w("ChatEngine", "maybeScheduleSummary failed", e)
            false
        } finally {
            // 兜底清：任何异常路径都不能让"压缩中"永久挂着（否则输入区一直转圈）
            setCompacting(sessionId, false)
        }
    }

    /** 压缩中指示（v53）。UI 读 ChatUiState.compacting。 */
    private fun setCompacting(sessionId: Long, on: Boolean) {
        runCatching {
            val f = flowOf(sessionId)
            if (f.value.compacting != on) f.value = f.value.copy(compacting = on)
        }
    }

    private suspend fun summarizeNow(sessionId: Long, force: Boolean): Boolean {
        val session = Graph.chat.session(sessionId) ?: return false
        // 会话内容的**唯一**过滤口径（effectiveTranscript）。
        // 改之前这里是自己的 `role in [user, assistant] && content 非空`，与读侧 assemble 的口径
        // 不一致 → 同一个 lastSummarizeIndex 在两边数是不同的集合（下标漂移，见 ref-runtime-study §7.0）。
        // v47：再排除**手动区间压缩**已覆盖的消息——区间摘要在原位自管（assemble 注入），
        // 自动摘要再吃一遍就是同一片原文两份摘要（memoryPrompt 冗余）。
        val transcript = effectiveTranscript(
            Graph.chat.messagesOf(sessionId), session.clearBefore
        ).let { msgs ->
            val rangeIds = manualRangeCoveredIds(sessionId)
            if (rangeIds.isEmpty()) msgs else msgs.filter { it.id !in rangeIds }
        }
        // 切点也走唯一口径：summarizeCut 算"留最近 N 条"，snapCutToToolSafe 把落在 tool 消息上的
        // 切点往后推（否则切出来的历史以"没有对应调用的工具结果"开头 → 400 / 模型编造）。
        val cut = snapCutToToolSafe(transcript, summarizeCut(transcript.size))
        // v39/M6：起点由 **id 锚点**换算（summarizeStartIndex），与读侧 assemble 用同一个函数——
        // 旧的计数下标在删消息后会指向别处（M5 的根），锚点删不删消息都稳。
        val fromIdx = summarizeStartIndex(transcript, session.lastSummarizedMessageId).coerceAtMost(cut)
        // v53：这些 return false 是「压缩没发生」的全部原因。用户实报「模型压缩文本失败」
        // 但界面只说一句笼统话——把每一次拒绝的原因都记下来（lastCompactBlockedReason），
        // 手动 /compact 由 UI 原样展示，用户能立刻分清「本来就没有可压内容」和「模型调用失败」。
        if (cut <= fromIdx) {
            lastCompactBlockedReason = "没有新的可压缩内容（最近的消息要保留）"
            return false
        }
        val older = transcript.subList(fromIdx, cut)
        // 前置检查（C 组 preflight）：流式中 / 没有可压内容直接回 false。
        // 生成中的回复压缩不到，检查点也不能插到它前面（rikkahub compressConversation 第一条）。
        val streaming = flowOf(sessionId).value.streaming
        when (Compaction.preflight(streaming, older.size)) {
            Compaction.BlockReason.STREAMING -> {
                lastCompactBlockedReason = "正在生成回复，压缩要等它结束"
                return false
            }
            Compaction.BlockReason.EMPTY_RANGE -> {
                lastCompactBlockedReason = "没有可压缩的消息"
                return false
            }
            else -> {}
        }
        // token 必须按**整条**算：只算 content 会把"空 content 但带一大坨 toolCallsJson 的 assistant"
        // 估成 0，既骗过阈值也骗过 capHistory。
        val olderTokens = older.sumOf { estMessageTokens(it) }
        if (!force && olderTokens < SUMMARY_THRESHOLD_TOKENS && older.size < SUMMARY_MIN_MESSAGES) {
            lastCompactBlockedReason = "还没到压缩阈值（当前约 $olderTokens tokens）"
            return false
        }
        lastCompactBlockedReason = null

        // M3：effectiveTranscript 现在连 role=tool 一起进摘要输入了（那才是文件内容/搜索结果/OCR），
        // 所以这里要给 tool 单独一个标签，并且**不要**只留 400 字就扔——
        // 只喂 400 字的话，把最有价值的内容纳进来也白纳。
        // v45（A2 门牌）：每行带 [#消息id] 前缀，摘要要点才能逐条标注来源——
        // "摘要每条都对，但都不带出处"是思考退化的根因（◆/planability-dsh-optimization-plan.md §A2）。
        val transcriptText = older.joinToString("\n") {
            val tag = "[#${it.id}] "
            when (it.role) {
                "user" -> "${tag}用户：${it.content.take(600)}"
                "tool" -> "${tag}工具结果：${it.content.take(600)}"
                else -> "${tag}助手：${it.content.take(600)}"
            }
        }
        // M1：take 取的是**开头**（最旧的摘要）。摘要越长，喂给模型的越可能是几个月前的内容，
        // 而真正需要接续的是最近发生的事 —— 改成 takeLast。
        val prev = session.memoryPrompt.takeLast(2000)
        // v40（成本优化 1.3）：摘要调用进账本（slot="summary"）。
        // 阶段 4：压缩槽优先（可指向本机 llama.cpp 的 OpenAI 兼容端点，token 成本归零），
        // 失败回退 worker 一次——本地模型不可用不能把摘要整条弄丢（ref-cost-study §7 阶段 4 硬要求）。
        val primary = AiClient.compactConfig()
        // 2026-11-15 用户「本地模型总是压缩失败」：primary 是本机端点时，它活着不代表调用能成功
        // （模型名不符/输入超长/空回复都会失败），usableWorkerConfig 只探测 TCP 活不活、活就返回
        // 本地配置 → sameCfg → 不重试 → 必失败。改成三级链：primary → summaryFallback → 云端主模型，
        // 各自按 baseUrl+modelName 去重，前一个失败就换下一个。
        val fallback = AiClient.summaryFallback(primary)
        val cloudMain = AiClient.config
        val sysPrompt = "你是对话摘要助手。把对话历史压缩为不超过 200 字的中文要点摘要：保留关键事实、用户偏好、已做的决定、未完成事项与日期。" +
            "每条要点末尾标注它依据的来源消息编号，格式如（#123）或（#123-#127），只标确实依据了的编号，没有依据的不标。" +
            "直接输出摘要正文，不要解释。"
        val userPrompt = (if (prev.isNotBlank()) "已有摘要：\n$prev\n\n" else "") + "新增对话：\n$transcriptText"
        var usedModel = primary.modelName
        // v53：压缩中指示——**只在模型调用这一段亮**（这是真正耗时的部分，几十秒级）。
        // 前置守卫（没内容/流式中）不亮，避免每轮闪一下。
        setCompacting(sessionId, true)
        // P1-3（2026-11-10 审计动态实录）：本地/弱模型卡住时 chatWithCounted 永不返回，
        // compacting 一直挂着 → 整个会话界面「正在压缩上下文…」冻结 30+ 分钟，
        // 撤销 chips 点击全被忙态吞掉。压缩是**可跳过**的辅助操作，必须给硬超时：
        // 超时=跳过本次压缩（历史原样保留），并把原因写进 lastCompactBlockedReason。
        val reply = try {
            withTimeoutOrNull(COMPACT_TIMEOUT_MS) {
                // 三级链：primary → fallback → cloudMain，按 baseUrl+modelName 去重
                val chain = LinkedHashMap<String, ModelConfig>()
                fun key(c: ModelConfig) = c.baseUrl + "|" + c.modelName
                chain[key(primary)] = primary
                chain[key(fallback)] = fallback
                chain[key(cloudMain)] = cloudMain
                var result: AiClient.ChatReply? = null
                for (cfg in chain.values) {
                    usedModel = cfg.modelName
                    result = runCatching {
                        AiClient.chatWithCounted(cfg, sysPrompt, userPrompt, 0.2)
                    }.getOrNull()
                    if (result != null) break
                }
                result
            } ?: run {
                // 超时：不动任何数据，跳过本次压缩（下一轮/用户重试都可以再来）
                lastCompactBlockedReason = "压缩超时（超过 " + (COMPACT_TIMEOUT_MS / 1000) +
                    " 秒没有产出），已跳过本次压缩；历史保持不动，可稍后再试或在设置里换压缩模型"
                return false   // finally 会清掉压缩中指示
            }
        } finally {
            setCompacting(sessionId, false)
        }
        if (reply == null) {
            // 模型调用失败：三级链全部不可用。
            lastCompactBlockedReason = "压缩模型调用失败（压缩槽/备槽/云端主模型都不可用）"
            return false
        }
        ContextEngine.appendUsage(
            sessionId, "summary", usedModel,
            reply.tokensIn, reply.tokensOut, reply.cachedTokens, reply.durationMs
        )
        val summary = reply.text.trim()
        if (summary.isBlank()) {
            lastCompactBlockedReason = "压缩模型返回了空摘要（" + usedModel + "）"
            return false
        }

        val now = AppTime.now()
        // v45（A2 门牌）：块头带保底范围指针 #首-#尾——模型逐条标注是"尽力"，这一行是"保证"：
        // 任何时候都能由 compact_markers 的锚点回查这段摘要盖住了哪些原文。
        val appended = "[${now.monthValue}/${now.dayOfMonth} #${older.first().id}-${older.last().id}] $summary"
        // M2：调用 AI 是秒级的，这段时间里可能有**另一条**摘要路径也在跑
        // （轮尾这一条 vs 用户点 /compact 的 force 那一条）。原实现用调用前的
        // `prev` 和调用前的 `transcript.size` 回写，会出现两种事故：
        //   ① 后写的那次把索引写小 → 已摘要过的内容重新进 older（重复摘要）
        //   ② 用旧 prev 覆盖 → 并发期间新追加的那条摘要被冲掉
        // 修法：回写前重读，摘要基于 fresh.memoryPrompt 合并，索引取 max。
        val fresh = Graph.chat.session(sessionId) ?: return false
        Graph.chat.updateSessionRaw(
            fresh.copy(
                // M1 ②：超 6000 时砍掉的应该是**最旧**的部分，而不是刚写进去的这条（mergeSummary = takeLast）
                memoryPrompt = mergeSummary(fresh.memoryPrompt, appended, SUMMARY_CAP_CHARS),
                // M2：锚点只前进不后退（两条路径并发时，先算完的那条不会被后来者覆盖回去）。
                // v39/M6：从计数下标换成消息 id 锚点——本次覆盖到的最后一条就是 older.last()
                lastSummarizedMessageId = nextSummarizeAnchor(fresh.lastSummarizedMessageId, older.last().id)
            )
        )
        // M7：压缩把请求变小了，但上下文用量 `ctxTokens` 只在进会话 / 发送时算一次。
        // 不重算的话，用户点完 /compact（或轮尾自动摘要跑完）看到的还是压缩前的大数字，
        // 会以为压缩没生效。这里按"下一轮真会发出去的载荷"重算一遍（与 assemble 同一口径），
        // 包 runCatching：面板刷新失败不能把"摘要已成功"改判成 false。
        runCatching {
            val after = Graph.chat.session(sessionId) ?: fresh
            // v40（成本优化 2.1）：工具 schema 也要进面板估算（与 startStream 同口径；
            // @勾连强制启用的域/插件不在本路径，此处近似——面板是估算不是计费）
            val a2 = after.assistantId.takeIf { it > 0L }
                ?.let { runCatching { Graph.assistants.byId(it) }.getOrNull() }
            val card2 = AgentCard.parse(a2?.card, a2?.systemPrompt ?: "")
            val tools = if (!card2.scaffoldTools) "" else runCatching {
                ToolRegistry.toToolsJson(ToolRegistry.enabledDomains(), ToolRegistry.enabledPlugins()).toString()
            }.getOrDefault("")
            val p = ContextEngine.assemble(sessionId, after, toolsEnabled = card2.scaffoldTools, toolsJson = tools)
            val f = flowOf(sessionId)
            f.value = f.value.copy(ctxTokens = p.totalTokens)
        }
        // v37 压缩标记：锚点 = 本次覆盖到的最后一条消息 **id**（不是计数下标——计数在删消息后会错位）。
        // 失败不回滚到这里：这只是记录，写不写都不影响"摘要是否上线"，写不进去下次压缩照样来。
        runCatching {
            Graph.compactMarkers.markCompact(
                sessionId = sessionId,
                summary = appended,
                anchorMessageId = older.last().id,
                firstKeptMessageId = if (cut < transcript.size) transcript[cut].id else 0,
                compactedCount = older.size,
                source = if (force) "manual" else "auto",
                now = AppTime.nowMillis()
            )
        }
        return true
    }

    // ---------- v47：按轮选择性压缩（manual_range） ----------

    /**
     * 用户在「详细上下文编辑」里选中**连续的几轮**（1 轮 = 1 条 user + AI 对它的全部回答），
     * 手动把这一段压成一条独立摘要。与 [summarizeNow] 的整历史滚动是两条并行的轨道：
     * - 区间摘要**不并入** memoryPrompt（那是"全部历史"的摘要），独立写一行
     *   [CompactMarkerEntity]（source=manual_range，firstCompactedMessageId=区间起点）；
     * - 组装上下文时（ContextEngine.assemble）跳过被区间覆盖的消息、在原位注入摘要；
     * - 删掉这行 marker 即恢复原文进上下文（天然可反悔）。
     *
     * @return null = 成功；否则为用户可读的失败原因。
     */
    suspend fun manualCompactRange(
        sessionId: Long, firstId: Long, lastId: Long,
        /** 2026-11-04：压缩用模型名（详细编辑器可选；null=跟随 compact 槽→worker 链） */
        modelOverride: String? = null,
    ): String? {
        val session = Graph.chat.session(sessionId) ?: return "会话不存在"
        val ui = flowOf(sessionId).value
        if (ui.streaming) return "生成中不能压缩，等这轮回答完再试"
        val transcript = effectiveTranscript(Graph.chat.messagesOf(sessionId), session.clearBefore)
        val fi = transcript.indexOfFirst { it.id == firstId }
        val li = transcript.indexOfFirst { it.id == lastId }
        if (fi < 0 || li < 0 || li < fi) return "压缩区间无效（消息可能已被删除）"
        // 区间必须从 user 消息开始（轮边界）——切在 assistant/tool 中间会把
        // tool_call 与它的 tool_result 拆进两边，模型侧 400 或编造（snapCutToToolSafe 同理）。
        if (transcript[fi].role != "user") return "压缩区间必须从一条用户消息开始"
        val older = transcript.subList(fi, li + 1)
        if (older.any { it.id == ui.streamingId }) return "区间里有还没生成完的回复"
        if (older.none { it.role == "assistant" }) return "选中的轮里还没有 AI 的回答"

        val transcriptText = older.joinToString("\n") {
            val tag = "[#${it.id}] "
            when (it.role) {
                "user" -> "${tag}用户：${it.content.take(600)}"
                "tool" -> "${tag}工具结果：${it.content.take(600)}"
                else -> "${tag}助手：${it.content.take(600)}"
            }
        }
        val asked = askSummaryModel(sessionId, transcriptText, modelOverride)
            ?: return "摘要生成失败（模型不可用或返回为空）"
        val (summary, _) = asked
        val now = AppTime.now()
        val appended = "[${now.monthValue}/${now.dayOfMonth} #${older.first().id}-${older.last().id}] $summary"
        // 重压同一段：先清掉相交的旧区间行，避免"同一片原文两份摘要"的双重语义
        Graph.compactMarkers.deleteOverlappingRanges(sessionId, firstId, lastId)
        Graph.compactMarkers.markCompact(
            sessionId = sessionId,
            summary = appended,
            anchorMessageId = lastId,
            firstKeptMessageId = if (li + 1 < transcript.size) transcript[li + 1].id else 0,
            compactedCount = older.size,
            source = "manual_range",
            now = AppTime.nowMillis(),
            firstCompactedMessageId = firstId
        )
        recomputeCtxTokens(sessionId)
        return null
    }

    /** v47：取消一次区间压缩——删 marker 行即恢复原文进上下文。 */
    suspend fun manualUncompact(sessionId: Long, markerId: Long) {
        Graph.compactMarkers.delete(markerId)
        recomputeCtxTokens(sessionId)
    }

    /** v47：本会话被手动区间压缩覆盖的全部消息 id（过滤/渲染共用，口径在 ChatContext.compactedIdSet）。 */
    suspend fun manualRangeCoveredIds(sessionId: Long): Set<Long> = runCatching {
        compactedIdSet(Graph.compactMarkers.rangesOf(sessionId))
    }.getOrDefault(emptySet())

    /**
     * v47：摘要模型调用（compact 槽优先→worker 兜底），返回 (摘要, 所用模型)；都失败 null。
     * 2026-11-04：[modelOverride] 非空 = 详细编辑器里指定了「压缩用模型」（configByName 解析，
     * 解析不到/未启用回退上面默认链）。
     */
    private suspend fun askSummaryModel(
        sessionId: Long, transcriptText: String, modelOverride: String? = null
    ): Pair<String, String>? {
        val primary = modelOverride?.trim()?.takeIf { it.isNotBlank() }?.let { name ->
            runCatching { Graph.aiProviders.configByName(name) }.getOrNull()
                ?.takeIf { it.enabled && it.modelName == name }   // 同 compactConfig 的"真解析到了"判据
                ?.let { if (AiClient.isLoopback(it.baseUrl)) AiClient.alignLocal(it) else it }
        } ?: AiClient.compactConfig()
        // 2026-11-15：三级链（同 summarizeNow），本地失败→云端兜底
        val fallback = AiClient.summaryFallback(primary)
        val cloudMain = AiClient.config
        val sysPrompt = "你是对话摘要助手。把对话历史压缩为不超过 200 字的中文要点摘要：保留关键事实、用户偏好、已做的决定、未完成事项与日期。" +
            "每条要点末尾标注它依据的来源消息编号，格式如（#123）或（#123-#127），只标确实依据了的编号，没有依据的不标。" +
            "直接输出摘要正文，不要解释。"
        var usedModel = primary.modelName
        val chain = LinkedHashMap<String, ModelConfig>()
        fun key(c: ModelConfig) = c.baseUrl + "|" + c.modelName
        chain[key(primary)] = primary
        chain[key(fallback)] = fallback
        chain[key(cloudMain)] = cloudMain
        var reply: AiClient.ChatReply? = null
        for (cfg in chain.values) {
            usedModel = cfg.modelName
            reply = runCatching {
                AiClient.chatWithCounted(cfg, sysPrompt, transcriptText, 0.2)
            }.getOrNull()
            if (reply != null) break
        }
        if (reply == null) return null
        ContextEngine.appendUsage(
            sessionId, "summary", usedModel,
            reply.tokensIn, reply.tokensOut, reply.cachedTokens, reply.durationMs
        )
        val summary = reply.text.trim()
        if (summary.isBlank()) return null
        return summary to usedModel
    }

    /** v47：按"下一轮真会发出去的载荷"重算 ctxTokens（原 M7 块，区间压缩也要刷面板数字）。 */
    private suspend fun recomputeCtxTokens(sessionId: Long) {
        // 2026-11-17：长会话（几百条消息 / 近百万字符）逐条估 token 是纯 CPU，
        // 在主线程算会掉帧——挪到 Default 线程（DB 读本身是 suspend 的 Room 调用，安全）。
        withContext(Dispatchers.Default) {
            runCatching {
                val after = Graph.chat.session(sessionId) ?: return@runCatching
                val a2 = after.assistantId.takeIf { it > 0L }
                    ?.let { runCatching { Graph.assistants.byId(it) }.getOrNull() }
                val card2 = AgentCard.parse(a2?.card, a2?.systemPrompt ?: "")
                val tools = if (!card2.scaffoldTools) "" else runCatching {
                    ToolRegistry.toToolsJson(ToolRegistry.enabledDomains(), ToolRegistry.enabledPlugins()).toString()
                }.getOrDefault("")
                val p = ContextEngine.assemble(sessionId, after, toolsEnabled = card2.scaffoldTools, toolsJson = tools)
                val f = flowOf(sessionId)
                f.value = f.value.copy(ctxTokens = p.totalTokens)
            }
        }
    }

    // ---------- 自动命名 ----------

    /** 历史遗留的无标题动作会话补起名（进入会话列表时清扫一次）；中枢会话默认名「私人助理」同样清扫
     *  （2026-11-11 用户：中枢会话列表不能全是「私人助理」，要有各自标题） */
    fun healUntitledSessions() {
        Graph.appScope.launch {
            runCatching {
                Graph.chat.sessions()
                    .filter { it.title.isBlank() || it.title == "私人助理" }
                    .forEach { scheduleNaming(it.id) }
            }
        }
    }

    /** 首轮完成后：默认标题且内容量足够 → worker 起名 */
    private fun scheduleNaming(sessionId: Long) {
        Graph.appScope.launch {
            runCatching {
                val session = Graph.chat.session(sessionId) ?: return@launch
                // 只起名"无主"会话：默认"新对话"/中枢默认名"私人助理"/历史遗留的空标题；
                // 已有语义标题（如"采集提纯·10/1"）不动
                if (session.title.isNotBlank() && session.title != "新对话" && session.title != "私人助理") return@launch
                val msgs = Graph.chat.messagesOf(sessionId)
                val firstUser = msgs.firstOrNull { it.role == "user" }?.content ?: return@launch
                val firstAssistant = msgs.firstOrNull { it.role == "assistant" && it.status == "done" }?.content ?: return@launch
                // ≈50 token（中文 1 token≈1-2 字）
                if (firstUser.length + firstAssistant.length < 60) return@launch

                val cfg = AiClient.usableWorkerConfig()
                val prompt = "用户：${firstUser.take(300)}\n\n助手：${firstAssistant.take(300)}"
                // v40（成本优化 1.3）：起名调用进账本（slot="naming"）
                val reply = runCatching {
                    AiClient.chatWithCounted(
                        cfg,
                        "你是对话命名助手。根据对话内容起一个 4~5 个汉字的简短主题标题，概括话题即可。直接输出标题本身，不要引号、句号、标点和任何解释。",
                        prompt, 0.2
                    )
                }.getOrNull()
                reply?.let {
                    ContextEngine.appendUsage(sessionId, "naming", cfg.modelName, it.tokensIn, it.tokensOut, it.cachedTokens)
                }
                val title = reply?.text?.trim()?.trim('"', '「', '」', '。', ' ', '\n')

                val finalTitle = if (!title.isNullOrBlank() && title.length <= 20) title else firstUser.lineSequence().firstOrNull()?.take(10) ?: "新对话"
                if (finalTitle.isNotBlank() && finalTitle != "新对话") {
                    Graph.chat.renameSession(sessionId, finalTitle)
                    // v42：标题进了镜像文件名，改名后重写并清掉旧标题的镜像
                    io.github.m0use001.planability.data.SessionMirror.mirror(sessionId)
                }
            }
        }
    }
}
