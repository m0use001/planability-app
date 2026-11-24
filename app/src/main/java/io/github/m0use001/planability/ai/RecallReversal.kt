package io.github.m0use001.planability.ai

import io.github.m0use001.planability.core.AppTime
import io.github.m0use001.planability.data.ChatMessage
import io.github.m0use001.planability.data.Graph
import org.json.JSONArray
import org.json.JSONObject

/**
 * v56 会话撤回的记忆回收。用户撤回一轮对话（重新生成 / 长按删除）再改口重发时，
 * 被撤流量里 AI 用 memory_add 写下的记忆还活着——旧设定照常被召回与常驻注入，
 * AI 在新旧两版内容之间混乱（2026-11-14 用户实录）。
 *
 * 修复口径：删除消息前扫描被删流量，把其中 memory_add **新建**的记忆
 * markInvalid（标失效不删行，历史记原因「来源消息已撤回」，镜像下次导出带 invalid）。
 *
 * 识别双层（取并集）：
 * ① assistant 消息 toolCallsJson（`[{"id","name","args"}]`）里 name=="memory_add"
 *    的调用 → args.name（内部名）；
 * ② tool 结果行文本里的「（内部名称 xxx）」（ToolExecImpls memory_add 的结果格式，
 *    防 toolCallsJson 缺损时漏召回）。
 *
 * 只回收**本次撤回流量期间新建**的记忆：originSession == 本会话 且
 * createdAt ≥ 被删消息的最早 createdAt。同名**更新**老条目（createdAt 更早）不动
 * ——没有旧正文快照，盲回滚会把老记忆整个丢掉；这类交给 undo_last_write 或用户手动 memory_invalidate。
 */
object RecallReversal {

    /** 从被删消息里收集 memory_add 的内部名（纯函数，可 JVM 单测）。 */
    internal fun memoryAddInternalNames(msgs: List<ChatMessage>): List<String> {
        val out = LinkedHashSet<String>()
        for (m in msgs) {
            if (m.role == "assistant" && m.toolCallsJson.isNotBlank()) {
                runCatching {
                    val arr = JSONArray(m.toolCallsJson)
                    for (i in 0 until arr.length()) {
                        val call = arr.optJSONObject(i) ?: continue
                        if (call.optString("name") != "memory_add") continue
                        val args = runCatching { JSONObject(call.optString("args")) }.getOrNull() ?: continue
                        val n = args.optString("name").trim()
                        if (n.isNotBlank()) out.add(n)
                    }
                }
            }
            if (m.role == "tool" && m.content.contains("（内部名称 ")) {
                Regex("（内部名称 (.+?)）").findAll(m.content).forEach {
                    out.add(it.groupValues[1].trim())
                }
            }
        }
        return out.filter { it.isNotBlank() }
    }

    /**
     * 撤回 [removed] 这批消息时回收记忆。返回已作废的内部名列表（空 = 无需告知）。
     * 调用方在 `deleteFrom` 之前传被删消息，删除动作由调用方自己执行。
     */
    suspend fun revokeFor(sessionId: Long, removed: List<ChatMessage>): List<String> {
        if (removed.isEmpty()) return emptyList()
        val names = memoryAddInternalNames(removed)
        if (names.isEmpty()) return emptyList()
        val minCreated = removed.minOf { it.createdAt }
        val revoked = mutableListOf<String>()
        for (n in names) {
            val mem = runCatching { Graph.db.memoryDao().byName(n) }.getOrNull() ?: continue
            if (mem.isDeleted || mem.invalidAt != 0L) continue
            // 只撤本会话写的、且在被撤流量期间新建的条目
            if (mem.originSession != sessionId || mem.createdAt < minCreated) continue
            runCatching {
                Graph.memories.markInvalid(mem.id, AppTime.nowMillis(), "来源消息已撤回（撤回流量里 AI 写入的记忆）")
            }
            revoked.add(n)
        }
        return revoked
    }
}
