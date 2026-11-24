package io.github.m0use001.planability.data

import io.github.m0use001.planability.core.AppTime

/**
 * v54：助理中枢 = **特殊项目**——有专属界面（不进抽屉项目树、会话管理页也不显示），
 * 但项目能力一个不少（会话/文件/记忆/草稿审核都在中枢界面里）。
 *
 * 项目 id 存 KV（hub.project.id）：抽屉/会话页按 id 过滤，比按名字稳
 * （用户把别的项目改名成「助理中枢」不会被误伤）。
 */
object HubProject {
    const val NAME = "助理中枢"
    private const val KV_ID = "hub.project.id"

    /** 查/建「助理中枢」项目（幂等）；中枢界面与 hub 会话的挂靠目标 */
    suspend fun ensureId(): Long {
        Graph.settings.kvGet(KV_ID)?.toLongOrNull()?.let { id ->
            if (Graph.projects.byId(id) != null) return id
        }
        val id = Graph.projects.all().firstOrNull { it.name == NAME }?.id
            ?: runCatching { Graph.projects.create(NAME) }.getOrElse {
                Graph.projects.all().firstOrNull { it.name == NAME }?.id ?: 0L
            }
        if (id > 0L) Graph.settings.kvPut(KV_ID, id.toString())
        return id
    }

    /** 只读查（不建）——抽屉/会话页过滤用；null = 中枢项目还没建过，无需过滤 */
    suspend fun idOrNull(): Long? {
        val id = Graph.settings.kvGet(KV_ID)?.toLongOrNull() ?: return null
        return if (Graph.projects.byId(id) != null) id else null
    }

    /** 中枢会话 id 的 KV 键（私人助理主会话，跨打开复用） */
    const val SESSION_KV = "hub.session.id"

    /** 查/建私人助理主会话（幂等；挂中枢项目、KEY_HUB 助手）
     *  2026-11-11 用户「不能全显示私人助理」：KV 失效时先复用中枢项目下现存的同名会话，
     *  别再新建一个重名的（历史上 KV 被清就堆出多个「私人助理」） */
    suspend fun ensureMainSession(assistantId: Long, projectId: Long): Long {
        var sid = Graph.settings.kvGet(SESSION_KV)?.toLongOrNull()
        var existing = sid?.let { runCatching { Graph.chat.session(it) }.getOrNull() }
        if (existing == null) {
            existing = Graph.chat.sessions().firstOrNull {
                it.projectId == projectId && !it.archived && it.assistantId == assistantId && it.title == "私人助理"
            }
            existing?.let {
                sid = it.id
                Graph.settings.kvPut(SESSION_KV, it.id.toString())
            }
        }
        if (existing == null) {
            sid = Graph.chat.createSession(title = "私人助理", assistantId = assistantId, projectId = projectId).id
            Graph.settings.kvPut(SESSION_KV, sid.toString())
        }
        return sid ?: 0L
    }

    /** 建中枢项目下的普通会话（用户在中枢界面「新建会话」；通用助手起步，会话内可换） */
    suspend fun newSession(projectId: Long): Long =
        Graph.chat.createSession(title = "新对话", assistantId = 0, projectId = projectId).id

    /** 中枢项目下的会话（进行中，未归档），新在前 */
    suspend fun sessionsOf(projectId: Long): List<ChatSession> =
        Graph.chat.sessions().filter { it.projectId == projectId && !it.archived }
            .sortedByDescending { it.updatedAt }

    fun nowMillis(): Long = AppTime.nowMillis()
}
