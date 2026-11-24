package io.github.m0use001.planability.data

import io.github.m0use001.planability.core.AppTime

/**
 * v45 P3：L2 场景与 L3 画像的读写。
 *
 * 分工：
 * - 场景是**确定性派生**的——整理器每写一条带 scene 的记忆，场景的 heat +1、
 *   summary 追加一句；不额外调用模型（省一次 LLM，也避免模型每轮造新场景名）。
 * - 画像是**生成物**——`ai/PersonaBuilder` 定期用场景 + 高优先级记忆重写，
 *   这里只负责存取与上限。
 *
 * 为什么场景不做 LLM 摘要：ReMe/TencentDB 的做法都要额外一次调用，而单用户场景下
 * 「最多 15 个场景、每个几百字」用确定性拼接已经够用；等真跑出问题再升级。
 */
object MemoryLayers {

    const val MAX_SCENES = 15
    const val SCENE_SUMMARY_MAX = 300
    const val SCENE_LINE_MAX = 200
    const val PERSONA_MAX = 2000

    private const val KV_PERSONA_RUNS = "memory.persona.runs"

    /** 场景合并：追加新描述、去重、按总长截断（保留最新在后，截掉最旧的前缀）。 */
    internal fun mergeSceneSummary(existing: String, addition: String, max: Int = SCENE_SUMMARY_MAX): String {
        val add = addition.trim()
        if (add.isBlank()) return existing.trim()
        val old = existing.trim()
        if (old.isBlank()) return add.take(max)
        if (old.contains(add) || add.contains(old)) return if (old.length >= add.length) old.take(max) else add.take(max)
        val joined = "$old；$add"
        if (joined.length <= max) return joined
        // 超长：先丢最早的，再从尾部保留 max 长度（新的内容更重要）
        return joined.takeLast(max)
    }

    /** 注入行：名字 + 摘要（摘要截到 max，带省略号）。 */
    internal fun formatSceneLine(name: String, summary: String, max: Int = SCENE_LINE_MAX): String {
        val s = summary.trim()
        val body = if (s.length > max) s.take(max) + "…" else s
        return if (body.isBlank()) "- $name" else "- $name：$body"
    }

    /** 是否到了重建画像的时机。 */
    internal fun shouldRebuildPersona(runsSinceLast: Int, every: Int = 50): Boolean =
        every > 0 && runsSinceLast >= every

    /**
     * 整理器每写一条带场景的记忆就喊一次。
     *
     * v57（2026-11-21 串味审计）：查找从全局 `byName` 收紧为**本项目的** `byNameInProject`。
     * 旧逻辑下任何项目写同名场景都灌进同一行（场景唯一索引也因此放宽为 (name, projectId)），
     * 线代的 det/体测就这样进了小说项目的「设定卡迭代」场景。
     */
    suspend fun touchScene(name: String, projectId: Long, description: String, now: Long): MemoryScene? {
        val scene = name.trim().take(60)
        if (scene.isBlank()) return null
        return runCatching {
            val dao = Graph.db.memorySceneDao()
            val existing = dao.byNameInProject(scene, projectId)
            val merged = MemoryScene(
                id = existing?.id ?: 0,
                name = scene,
                summary = mergeSceneSummary(existing?.summary.orEmpty(), description),
                heat = (existing?.heat ?: 0) + 1,
                projectId = if (existing != null) existing.projectId else projectId,
                validAt = existing?.validAt?.takeIf { it > 0 } ?: now,
                invalidAt = 0,
                createdAt = existing?.createdAt?.takeIf { it > 0 } ?: now,
                updatedAt = now,
            )
            val id = dao.upsert(merged)
            capScenes(projectId, now)
            merged.copy(id = id)
        }.getOrNull()
    }

    /** 超过上限时把 heat 最低的标成失效（不删行，历史仍可查）。v57：按项目各自封顶。 */
    private suspend fun capScenes(projectId: Long, now: Long) {
        val dao = Graph.db.memorySceneDao()
        val active = dao.activeOwnedBy(projectId, MAX_SCENES + 50, now)
        if (active.size <= MAX_SCENES) return
        active.drop(MAX_SCENES).forEach { dao.invalidate(it.id, now) }
    }

    /** 场景索引块（注入用）：按 heat 排序，只给名字 + 摘要。 */
    suspend fun sceneIndexBlock(projectScope: Long = 0L, limit: Int = 5): String {
        if (limit <= 0) return ""
        val now = AppTime.nowMillis()
        val scenes = runCatching {
            Graph.db.memorySceneDao().activeForProject(projectScope, limit, now)
        }.getOrDefault(emptyList())
        if (scenes.isEmpty()) return ""
        return scenes.joinToString("\n") { formatSceneLine(it.name, it.summary) }
    }

    suspend fun persona(): String =
        runCatching { Graph.db.memoryPersonaDao().get()?.body.orEmpty() }.getOrDefault("").trim()

    suspend fun savePersona(text: String, now: Long = AppTime.nowMillis()): Boolean {
        val clean = text.trim().take(PERSONA_MAX)
        if (clean.isBlank()) return false
        return runCatching {
            val old = Graph.db.memoryPersonaDao().get()
            Graph.db.memoryPersonaDao().upsert(
                MemoryPersona(
                    id = 1, body = clean,
                    version = (old?.version ?: 0) + 1, updatedAt = now
                )
            )
            true
        }.getOrDefault(false)
    }

    suspend fun personaRuns(): Int =
        runCatching { Graph.settings.kvGet(KV_PERSONA_RUNS)?.toIntOrNull() ?: 0 }.getOrDefault(0)

    suspend fun setPersonaRuns(n: Int) {
        runCatching { Graph.settings.kvPut(KV_PERSONA_RUNS, n.coerceAtLeast(0).toString()) }
    }
}
