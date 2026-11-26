package io.github.m0use001.planability.shared

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * v19.9 智能体运行态：让「运行中的智能体跑到第几步」可见。
 * M2 从 app/ai/ptl/SubAgentRuns.kt 迁入（jvmShared：kotlinx-coroutines + System.currentTimeMillis，
 * 无 Android 依赖）。
 *
 * 之前运行面板只能显示**已完成**的工具调用审计（tools_audit 是执行完才落库的），
 * 看不到进行中的进度。这里为每次智能体运行维护一份实时步骤表：
 * ask_expert = 取人格 → 调用模型 → 回收答案；deep_plan = 分解 → 各子任务 → 汇总。
 * 进程内内存态（运行中必然在进程内），结束后把快照留在列表里供回看。
 */

data class SubAgentStep(
    val label: String,
    /** pending / running / done / failed / skipped */
    val state: String = "pending"
)

data class SubAgentRun(
    val id: String,
    val sessionId: Long,
    /** ask_expert / deep_plan */
    val kind: String,
    val title: String,
    val steps: List<SubAgentStep>,
    /** running / done / failed */
    val status: String = "running",
    val startedAt: Long = System.currentTimeMillis(),
    val endedAt: Long = 0L
) {
    val total: Int get() = steps.size
    val finishedCount: Int get() = steps.count { it.state == "done" || it.state == "failed" || it.state == "skipped" }
    /** 「第 k/n 步」里的 k：正在跑的那一步（1 起）；全完成时等于 n */
    val stepNo: Int
        get() {
            val idx = steps.indexOfFirst { it.state == "running" }
            return when {
                idx >= 0 -> idx + 1
                status == "running" -> (finishedCount + 1).coerceAtMost(total.coerceAtLeast(1))
                else -> total
            }
        }
    val currentLabel: String?
        get() = steps.firstOrNull { it.state == "running" }?.label
            ?: steps.getOrNull(stepNo - 1)?.label
}

object SubAgentRuns {

    private val _runs = MutableStateFlow<List<SubAgentRun>>(emptyList())

    /** 全量（按开始时间倒序）；面板按 sessionId 过滤 */
    val runs: StateFlow<List<SubAgentRun>> = _runs.asStateFlow()

    fun of(sessionId: Long): List<SubAgentRun> =
        _runs.value.filter { it.sessionId == sessionId }.sortedByDescending { it.startedAt }

    fun byId(id: String): SubAgentRun? = _runs.value.firstOrNull { it.id == id }

    /** 开始一次智能体运行，返回 runId（步骤初始全 pending，第一步自动置 running） */
    fun start(sessionId: Long, kind: String, title: String, steps: List<String>): String {
        val id = kind + "_" + System.currentTimeMillis()
        val run = SubAgentRun(
            id = id,
            sessionId = sessionId,
            kind = kind,
            title = title,
            steps = steps.map { SubAgentStep(it, if (it == steps.firstOrNull()) "running" else "pending") }
        )
        _runs.update { (listOf(run) + it).take(30) }
        return id
    }

    /** 把某一步置为完成（并自动把下一步推进 running）；索引越界安全忽略 */
    fun step(runId: String, index: Int, state: String = "done") {
        _runs.update { list ->
            list.map { r ->
                if (r.id != runId || index !in r.steps.indices) return@map r
                val next = r.steps.toMutableList()
                next[index] = next[index].copy(state = state)
                // 顺带把后面第一个仍 pending 的步骤推上 running
                val nxt = next.indexOfFirst { it.state == "pending" }
                if (state == "done" && nxt >= 0) next[nxt] = next[nxt].copy(state = "running")
                r.copy(steps = next)
            }
        }
    }

    fun setState(runId: String, index: Int, state: String) = step(runId, index, state)

    /** 结束运行：未处理的步骤记为 skipped（不假装完成） */
    fun finish(runId: String, status: String = "done") {
        _runs.update { list ->
            list.map { r ->
                if (r.id != runId) return@map r
                val steps = r.steps.map { s ->
                    when (s.state) {
                        "done", "failed" -> s
                        "running" -> s.copy(state = if (status == "failed") "failed" else "done")
                        else -> s.copy(state = "skipped")
                    }
                }
                r.copy(steps = steps, status = status, endedAt = System.currentTimeMillis())
            }
        }
    }

    /** 进程内清理（会话删除时调用，避免陈旧运行态长期占位） */
    fun dropSession(sessionId: Long) {
        _runs.update { list -> list.filterNot { it.sessionId == sessionId } }
    }
}
