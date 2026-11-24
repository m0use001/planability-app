package io.github.m0use001.planability.ai

import io.github.m0use001.planability.core.AppTime
import io.github.m0use001.planability.data.Graph
import io.github.m0use001.planability.data.MemoryEntity
import io.github.m0use001.planability.data.MemoryLayers
import io.github.m0use001.planability.data.MemoryScene

/**
 * v45 P3：L3 长期画像的生成（TencentDB `persona-generation.ts` 的单用户简化版）。
 *
 * 触发：每 [TRIGGER_EVERY] 次整理跑一次，或手动 [rebuild]。计数放在设置 KV，
 * 重启不丢。为什么不是「每轮」：画像变化慢，每轮重写既费钱又会让它抖动。
 *
 * 输入只取「场景摘要 + 高优先级记忆」，不喂全库——全库既超预算又会把画像写成流水账。
 *
 * 注意：与记忆不同，画像是**生成物**：每次重建整体覆盖，旧版本只留在
 * `memory_persona.version` 里（不保留历史正文）。真正的历史在记忆本身，画像只是索引。
 */
object PersonaBuilder {

    /** 每多少次整理重建一次画像。 */
    const val TRIGGER_EVERY = 50

    /** 输入记忆上限（按 priority/更新时间取前 N 条）。 */
    private const val MAX_INPUT_MEMORIES = 60

    /** 整理完成后调用；`force = true` 表示用户手动重建。 */
    suspend fun onConsolidated(force: Boolean = false): Boolean {
        val runs = MemoryLayers.personaRuns() + 1
        if (!force && !MemoryLayers.shouldRebuildPersona(runs, TRIGGER_EVERY)) {
            MemoryLayers.setPersonaRuns(runs)
            return false
        }
        val ok = runCatching { rebuild() }.getOrDefault(false)
        MemoryLayers.setPersonaRuns(if (ok) 0 else runs)
        return ok
    }

    suspend fun rebuild(): Boolean {
        val now = AppTime.nowMillis()
        val scenes = runCatching {
            Graph.db.memorySceneDao().activeForProject(0, MemoryLayers.MAX_SCENES, now)
        }.getOrDefault(emptyList())
        val memories = runCatching { Graph.memories.all() }.getOrDefault(emptyList())
            .filter {
                it.type != "handoff" &&
                    (it.invalidAt == 0L || it.invalidAt > now) &&
                    it.supersededBy == 0L &&
                    it.body.isNotBlank()
            }
            .sortedWith(
                compareByDescending<MemoryEntity> { it.priority }
                    .thenByDescending { it.updatedAt }
            )
            .take(MAX_INPUT_MEMORIES)
        if (scenes.isEmpty() && memories.isEmpty()) return false

        val raw = runCatching {
            AiClient.chatWith(
                AiClient.usableWorkerConfig(),
                buildPersonaSystemPrompt(),
                buildPersonaUserPrompt(scenes, memories),
                0.3
            )
        }.getOrNull()?.trim().orEmpty()
        if (raw.isBlank()) return false
        return MemoryLayers.savePersona(raw, now)
    }

    internal fun buildPersonaSystemPrompt(): String = buildString {
        appendLine("你是用户画像整理员。根据给定的场景摘要与长期记忆，写一份**稳定**的用户画像。")
        appendLine("固定四段，每段用「## 标题」开头，顺序不变：")
        appendLine("## 基础锚点 —— 身份、专业/年级、长期项目、居住与作息等不易变化的事实；")
        appendLine("## 兴趣图谱 —— 长期兴趣、学习方向、常用工具与方法偏好；")
        appendLine("## 交互协议 —— 用户对 AI 的长期要求：语言、格式、语气、禁忌、必须遵守的规则；")
        appendLine("## 认知内核 —— 稳定的价值观、决策风格、反复出现的模式（必须有证据，不写性格测评）。")
        appendLine("规则：总长不超过 ${MemoryLayers.PERSONA_MAX} 字；只写材料里有的，不推断、不补全、不编造；")
        appendLine("材料里没有的段落写「（暂无）」，不要用常识填充；不写具体某天的琐事；")
        appendLine("宁可短，也不要为了凑满四段而扩写。直接输出正文，不要解释。")
    }

    internal fun buildPersonaUserPrompt(scenes: List<MemoryScene>, memories: List<MemoryEntity>): String =
        buildString {
            appendLine("<scenes>")
            if (scenes.isEmpty()) appendLine("（无）")
            else scenes.forEach { appendLine("- ${it.name}：${it.summary.take(200)}") }
            appendLine("</scenes>")
            appendLine()
            appendLine("<memories>")
            if (memories.isEmpty()) appendLine("（无）")
            else memories.forEach {
                val pri = if (it.priority < 0) "死命令" else "p${it.priority}"
                appendLine("- [$pri][${it.type}] ${it.name}：${it.body.take(120)}")
            }
            appendLine("</memories>")
        }
}
