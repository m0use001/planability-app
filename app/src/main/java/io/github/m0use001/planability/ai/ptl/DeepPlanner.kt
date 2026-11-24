package io.github.m0use001.planability.ai.ptl

import io.github.m0use001.planability.ai.AiClient
import io.github.m0use001.planability.ai.AiException
import io.github.m0use001.planability.data.Graph
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull

/**
 * M4.5 规划器-工人（DevWit 协议简化版）：
 * 1. 规划器（主槽）：把复杂任务分解为 ≤4 个子任务 JSON
 * 2. 工人（执行者槽，并发 3，单个 120s 超时）：独立完成子任务输出精炼结果
 * 3. 汇总（主槽）：合并子结果为结构化答案
 * 工人不带工具（纯 LLM）；任一工人失败以占位结果继续汇总。
 */
object DeepPlanner {

    private const val MAX_SUBTASKS = 4
    private const val CONCURRENCY = 3
    private const val WORKER_TIMEOUT_MS = 120_000L

    /** 执行进度（聊天页在流式期间显示） */
    private val _progress = MutableStateFlow<String?>(null)
    fun progressFlow(): StateFlow<String?> = _progress

    suspend fun run(sessionId: Long, task: String): String {
        try {
            return runInternal(sessionId, task)
        } finally {
            _progress.value = null
        }
    }

    private suspend fun runInternal(sessionId: Long, task: String): String {
        if (task.isBlank()) throw AiException("任务描述为空")
        val mainCfg = AiClient.config
        if (!mainCfg.enabled || mainCfg.baseUrl.isBlank()) throw AiException("尚未配置模型（我的 → 设置 → 模型管理）")
        val workerCfg = AiClient.usableWorkerConfig()

        // 近期对话作为任务背景（不重复注入 chips，避免成本）
        val session = Graph.chat.session(sessionId)
        val recent = if (session == null) "" else {
            Graph.chat.messagesOf(sessionId)
                .filter { m -> m.id > session.clearBefore && m.role == "user" && m.content.isNotBlank() }
                .takeLast(3).joinToString("\n") { m -> "用户：" + m.content.take(200) }
        }

        // ---------- 1. 分解 ----------
        _progress.value = "规划器分解任务中…"
        val planSystem = "你是任务规划器。把用户的复杂任务分解为不超过 $MAX_SUBTASKS 个可独立完成的子任务。" +
            "每个子任务给出 goal（要做什么）和 context（工人需要的背景/约束/期望产出格式）。" +
            "只输出 JSON 数组，格式：[{\"goal\":\"...\",\"context\":\"...\"}]，不要任何解释。"
        val planUser = buildString {
            if (recent.isNotBlank()) append("近期对话背景：\n").append(recent).append("\n\n")
            append("任务：").append(task)
        }
        val planRaw = withTimeoutOrNull(90_000L) {
            runCatching { AiClient.chatWith(mainCfg, planSystem, planUser, 0.3) }.getOrNull()
        } ?: throw AiException("任务分解失败（超时或网络错误）")
        val subtasks = parseSubtasks(planRaw)
        if (subtasks.isEmpty()) {
            // 规划器判断无需分解：直接返回其回答
            return planRaw.trim()
        }

        // ---------- 2. 工人并发执行 ----------
        // v19.9：注册运行态，让「跑到第几步」在运行面板可见（分解已完成 → 各子任务 → 汇总）
        val runId = SubAgentRuns.start(
            sessionId, "deep_plan", "深度规划 · " + task.trim().take(24),
            listOf("分解任务") + subtasks.map { "子任务：" + it.first.take(18) } + listOf("汇总结果")
        )
        SubAgentRuns.step(runId, 0, "done")
        subtasks.forEachIndexed { i, _ -> SubAgentRuns.setState(runId, i + 1, "running") }
        _progress.value = "工人并行执行中（0/" + subtasks.size + "）"
        val workerResults: List<String> = coroutineScope {
            val semaphore = Semaphore(CONCURRENCY)
            subtasks.mapIndexed { index, sub ->
                async {
                    semaphore.withPermit {
                        val output = withTimeoutOrNull(WORKER_TIMEOUT_MS) {
                            runCatching {
                                AiClient.chatWith(
                                    workerCfg,
                                    "你是执行工人，独立完成分配给你的子任务。输出精炼、可直接使用的结果（≤200 字），不要客套，不要复述任务。若信息不足，给出基于合理假设的结果并标注假设。",
                                    "总任务：" + task + "\n\n你的子任务：" + sub.first + "\n背景与要求：" + sub.second,
                                    0.4
                                )
                            }.getOrNull()
                        } ?: "（子任务执行失败：超时或错误）"
                        SubAgentRuns.step(runId, index + 1, "done")
                        _progress.value = "工人并行执行中（" + (index + 1) + "/" + subtasks.size + "）"
                        "子任务" + (index + 1) + "【" + sub.first + "】\n" + output
                    }
                }
            }.awaitAll()
        }

        // ---------- 3. 汇总 ----------
        _progress.value = "汇总结果中…"
        subtasks.indices.forEach { SubAgentRuns.step(runId, it + 1, "done") }
        val combined = workerResults.joinToString("\n\n")
        val summary = withTimeoutOrNull(90_000L) {
            runCatching {
                AiClient.chatWith(
                    mainCfg,
                    "你是汇总者。把多个工人的子任务结果合并为一份面向用户的最终答案：结构清晰（分节/列表）、去掉重复与失败占位、给出可执行的下一步建议。中文输出。",
                    "总任务：" + task + "\n\n各工人结果：\n" + combined,
                    0.4
                )
            }.getOrNull()
        }
        // 汇总失败时回退为拼接原始结果
        val out = summary?.trim()?.takeIf { it.isNotBlank() } ?: combined
        SubAgentRuns.step(runId, subtasks.size + 1, if (summary.isNullOrBlank()) "failed" else "done")
        SubAgentRuns.finish(runId, "done")
        return out
    }

    /** 容错解析子任务 JSON（剥围栏/截取数组边界/上限裁剪） */
    private fun parseSubtasks(raw: String): List<Pair<String, String>> {
        var t = raw.trim().replace("```json", "").replace("```", "").trim()
        val s = t.indexOf('[')
        val e = t.lastIndexOf(']')
        if (s < 0 || e <= s) return emptyList()
        t = t.substring(s, e + 1)
        val arr = runCatching { org.json.JSONArray(t) }.getOrNull() ?: return emptyList()
        val out = mutableListOf<Pair<String, String>>()
        for (i in 0 until arr.length()) {
            if (out.size >= MAX_SUBTASKS) break
            val o = arr.optJSONObject(i) ?: continue
            val goal = o.optString("goal", "").trim().ifBlank { o.optString("task", "").trim() }
            val context = o.optString("context", "").trim()
            if (goal.isNotBlank()) out += goal.take(200) to context.take(500)
        }
        return out
    }
}
