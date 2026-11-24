package io.github.m0use001.planability.ai.lore

import io.github.m0use001.planability.data.LoreEntry
import kotlin.random.Random

/**
 * 世界书（设定卡）匹配引擎——纯 Kotlin，零依赖，可 JVM 单测。
 *
 * 算法移植自 LoreCraft（MIT，272 行 worldbook.js），语义用 SillyTavern
 * world-info.js:4766-5160 与 RisuAI lorebook.svelte.ts:99-670 交叉校正
 * （仅行为参考，不复制 AGPL/GPL 代码）。相同输入 → 相同输出（概率项除外，
 * 可用注入的 [random] 固定种子）。
 *
 * 一轮激活流程：
 * 1. 过滤 disabled / 空内容；
 * 2. constant 条目无条件入选（不扫键、不掷概率）；
 * 3. 其余条目扫最近 scanDepth 条消息（0 = 全局默认），主键任一命中才活；
 *    副键按 logic 四态（AND_ANY / NOT_ALL / NOT_ANY / AND_ALL）裁决；
 * 4. 命中后按 probability 放行；
 * 5. priority 降序做预算选拔：整条进/整条出（不截断），预算内贪心；
 * 6. 入选者按 order 降序排位（同槽位内的先后）。
 *
 * 明确不做：递归触发、向量条目、sticky/cooldown/delay（RisuAI 已整体砍掉）。
 */
object LoreEngine {

    const val POS_DESC_AFTER = "desc_after"
    const val POS_DESC_BEFORE = "desc_before"
    const val POS_DEPTH = "depth"

    const val LOGIC_AND_ANY = "AND_ANY"
    const val LOGIC_NOT_ALL = "NOT_ALL"
    const val LOGIC_NOT_ANY = "NOT_ANY"
    const val LOGIC_AND_ALL = "AND_ALL"

    /** 一条被激活的世界书条目（ContextEngine 按 position/role/depth 分桶注入）。 */
    data class LoreHit(
        val id: Long,
        val name: String,
        val content: String,
        val position: String,
        val depth: Int,
        val role: String,
        val order: Int,
    )

    /**
     * @param entries 该智能体名下全部条目（未过滤）
     * @param recentTexts 最近消息文本，**新→旧**（下标 0 = 最新一条）
     * @param maxTokens 世界书预算上限（整条计数）
     * @param defaultScanDepth 条目未覆写 scanDepth 时的全局扫描深度
     * @param tokenCost 单条内容的 token 估算（默认 2 字符 = 1 token，近似）
     */
    fun activate(
        entries: List<LoreEntry>,
        recentTexts: List<String>,
        maxTokens: Int,
        defaultScanDepth: Int = 2,
        tokenCost: (String) -> Int = { it.length / 2 + 1 },
        random: Random = Random.Default,
    ): List<LoreHit> {
        val live = entries.filter { !it.disabled && it.content.isNotBlank() }
        if (live.isEmpty() || maxTokens <= 0) return emptyList()

        val constants = live.filter { it.constant }
        val selective = live.filter { !it.constant }.filter { e ->
            val roll = if (e.probability >= 100) 0 else random.nextInt(100)
            (e.probability >= 100 || roll < e.probability) &&
                matches(e, recentTexts, defaultScanDepth)
        }

        // 选拔：常驻优先，其次 priority 降序（同 priority 按 order 降序，再按 id 稳定）
        val candidates = (constants + selective)
            .distinctBy { it.id }
            .sortedWith(
                compareByDescending<LoreEntry> { it.constant }
                    .thenByDescending { it.priority }
                    .thenByDescending { it.order }
                    .thenBy { it.id }
            )

        // 预算：整条进/整条出；超预算的条目跳过，继续尝试后面更小的（RisuAI 贪心语义）
        var used = 0
        val admitted = mutableListOf<LoreEntry>()
        for (e in candidates) {
            val cost = tokenCost(e.content).coerceAtLeast(1)
            if (used + cost > maxTokens) continue
            used += cost
            admitted += e
        }

        // 排位：同槽位内 order 降序（ST sortFn 同口径）
        return admitted
            .sortedWith(compareByDescending<LoreEntry> { it.order }.thenBy { it.id })
            .map {
                LoreHit(
                    id = it.id,
                    name = it.name,
                    content = it.content,
                    position = it.position,
                    depth = if (it.depth <= 0) 4 else it.depth,
                    role = it.role.ifBlank { "system" },
                    order = it.order,
                )
            }
    }

    /** 键匹配：主键 OR；副键按 logic 四态。 */
    private fun matches(e: LoreEntry, recentTexts: List<String>, defaultScanDepth: Int): Boolean {
        val keys = e.keyList()
        if (keys.isEmpty()) return false
        val depth = if (e.scanDepth > 0) e.scanDepth else defaultScanDepth
        val window = recentTexts.take(depth.coerceAtLeast(1)).map { it.lowercase() }
        if (window.isEmpty()) return false

        fun hit(key: String): Boolean {
            val k = key.lowercase()
            return window.any { it.contains(k) }
        }

        if (keys.none { hit(it) }) return false
        val second = e.secondKeyList()
        if (second.isEmpty()) return true
        val hits = second.map { hit(it) }
        return when (e.logic) {
            LOGIC_NOT_ALL -> !hits.all { it }
            LOGIC_NOT_ANY -> hits.none { it }
            LOGIC_AND_ALL -> hits.all { it }
            else -> hits.any { it } // AND_ANY
        }
    }
}
