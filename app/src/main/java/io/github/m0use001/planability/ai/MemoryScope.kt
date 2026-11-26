package io.github.m0use001.planability.ai

import io.github.m0use001.planability.data.AssistantRepo
import io.github.m0use001.planability.data.ChatSession
import io.github.m0use001.planability.data.Graph
import io.github.m0use001.planability.data.MEMORY_SCOPE_ALL

/**
 * 会话 → 记忆/文件作用域（2026-11-26 小说会话事故后统一入口）。
 *
 * - **助理中枢**（assistantKey = hub）：[MEMORY_SCOPE_ALL]——私人助理本来就要汇总所有项目；
 * - **有项目的会话**：项目 id（只看本项目 + 全局 + 常驻索引）；
 * - **无项目会话**：0（只看全局 + 常驻索引，不再等于「全库」）。
 *
 * 纯函数 [memoryScopeFor] 单独拆出来是为了能被单测钉住——「0 曾经被当成全库」
 * 正是这次串味回归的判据漏洞。
 */
fun memoryScopeFor(session: ChatSession, isHubAssistant: Boolean): Long =
    if (isHubAssistant) MEMORY_SCOPE_ALL else session.projectId

suspend fun memoryScopeOf(sessionId: Long): Long {
    val s = Graph.chat.session(sessionId) ?: return 0L
    val hub = s.assistantId > 0L && runCatching {
        Graph.assistants.byId(s.assistantId)?.key == AssistantRepo.KEY_HUB
    }.getOrDefault(false)
    return memoryScopeFor(s, hub)
}
