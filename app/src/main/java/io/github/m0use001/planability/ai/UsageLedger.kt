package io.github.m0use001.planability.ai

import io.github.m0use001.planability.data.Graph
import java.io.File

/**
 * 用量账本的读取与预算判定（v40，成本优化阶段 3，ref-cost-study §2.1/2.2）。
 *
 * ## 为什么单独一个文件
 * `usage.jsonl` 是 ContextEngine.appendUsage 追加的**机器写入**的扁平 JSON，字段名固定。
 * 聚合与预算判定抽成纯函数（不碰 org.json——JVM 单测里它是桩），解析用手写的宽容提取器：
 * - 缺字段 = null（**"unknown ≠ 0"**，老账本行没有 cachedTokens 就该是"没上报"，不是 0）；
 * - 坏行返回 null，聚合跳过——账本宁可缺一行也不崩。
 *
 * ## 归因槽位（appendUsage 在写）
 * main（主对话）/ summary（滚动摘要）/ naming（自动起名）/ rescue（超长救援）。
 */
object UsageLedger {

    data class UsageEntry(
        val ts: Long,
        val session: Long,
        val slot: String,
        val model: String,
        val tokensIn: Int?,
        val tokensOut: Int?,
        val cachedTokens: Int?,
        /** 墙钟耗时（ms）；老行/主对话流式路径没有 → null（不冒充 0） */
        val durationMs: Long? = null
    ) {
        /** 输入+输出合计；没上报的部分不计（不让"没上报"冒充 0 摊薄均值） */
        val totalTokens: Long get() = (tokensIn ?: 0).toLong() + (tokensOut ?: 0).toLong()
    }

    /** 单行账本 → 条目。解析失败的行返回 null（聚合时跳过）。 */
    fun parseLine(line: String): UsageEntry? {
        val trimmed = line.trim()
        if (!trimmed.startsWith("{")) return null
        fun num(key: String): Long? = Regex("\"$key\":(-?\\d+)").find(trimmed)?.groupValues?.get(1)?.toLongOrNull()
        fun str(key: String): String = Regex("\"$key\":\"([^\"]*)\"").find(trimmed)?.groupValues?.get(1) ?: ""
        val ts = num("ts") ?: return null
        val session = num("session") ?: return null
        return UsageEntry(
            ts = ts,
            session = session,
            slot = str("slot").ifBlank { "main" },
            model = str("model"),
            tokensIn = num("tokensIn")?.toInt(),
            tokensOut = num("tokensOut")?.toInt(),
            cachedTokens = num("cachedTokens")?.toInt(),
            durationMs = num("ms")?.takeIf { it > 0 }
        )
    }

    /** 读全量账本（运行时；单测直接喂 parseLine 的产物） */
    suspend fun loadEntries(): List<UsageEntry> {
        val f = File(Graph.appContext.filesDir, "ai_usage/usage.jsonl")
        if (!f.exists()) return emptyList()
        return f.readLines().mapNotNull { parseLine(it) }
    }

    /** 一个会话的累计 token（输入+输出，含摘要/起名/救援——那也是这个会话花的钱） */
    suspend fun sessionSpent(sessionId: Long): Long =
        loadEntries().filter { it.session == sessionId }.sumOf { it.totalTokens }

    /**
     * 按任意 key 聚合（槽位/模型/日期由调用方给 keyOf；统计页 UI 归 B 线，这里只供数）。
     * cachedTokens 单独累计：缓存命中是"没花或半价花的钱"，混进 tokensIn 会算错账。
     */
    data class Bucket(
        val key: String,
        val calls: Int,
        val tokensIn: Long,
        val tokensOut: Long,
        val cachedTokens: Long,
        /** 有耗时的行才有（ms 求和）；全都没测时为 0 */
        val durationMs: Long = 0
    ) {
        /** 输出速度（tok/s）。没有有效耗时返回 null —— 不编数。 */
        fun tokPerSec(): Double? = if (durationMs > 0 && tokensOut > 0) tokensOut * 1000.0 / durationMs else null
    }

    fun summarize(entries: List<UsageEntry>, keyOf: (UsageEntry) -> String): List<Bucket> {
        val order = mutableListOf<String>()
        val map = LinkedHashMap<String, LongArray>()
        entries.forEach { e ->
            val k = keyOf(e)
            val arr = map.getOrPut(k) { order += k; LongArray(5) }
            arr[0] += 1
            arr[1] += (e.tokensIn ?: 0).toLong()
            arr[2] += (e.tokensOut ?: 0).toLong()
            arr[3] += (e.cachedTokens ?: 0).toLong()
            arr[4] += e.durationMs ?: 0L
        }
        return order.map { k ->
            val a = map.getValue(k)
            Bucket(k, a[0].toInt(), a[1], a[2], a[3], a[4])
        }
    }

    // ---------- 会话级预算（学 PURE OrchestrationBudget：派发前拒绝，不是事后报警） ----------

    const val KEY_SOFT = "ctx.sessionBudgetSoft"
    const val KEY_HARD = "ctx.sessionBudgetHard"

    /**
     * 四态：NO_BUDGET（未配置，**默认状态**——这些护栏默认关闭，ref-cost-study §2 "Everything
     * here is off by default"）/ Ok / Warn（过软线，模型可自查后收尾）/ OverHard（过硬线，发送被拒）。
     */
    sealed class BudgetCheck {
        object NoBudget : BudgetCheck()
        data class Ok(val spent: Long, val soft: Int?, val hard: Int?) : BudgetCheck()
        data class Warn(val spent: Long, val soft: Int, val hard: Int?) : BudgetCheck()
        data class OverHard(val spent: Long, val hard: Int, val reason: String) : BudgetCheck()
    }

    fun budgetStatus(spent: Long, soft: Int?, hard: Int?): BudgetCheck = when {
        soft == null && hard == null -> BudgetCheck.NoBudget
        hard != null && spent >= hard -> BudgetCheck.OverHard(spent, hard, refusalReason(spent, soft, hard))
        soft != null && spent >= soft -> BudgetCheck.Warn(spent, soft, hard)
        else -> BudgetCheck.Ok(spent, soft, hard)
    }

    /** 拒绝话术：用户与模型都能读懂（"为什么被拒 + 怎么办"），不写内部黑话 */
    fun refusalReason(spent: Long, soft: Int?, hard: Int): String =
        "本会话已累计 " + spent + " tokens（软上限 " + (soft ?: "未设") + "，硬上限 " + hard + "），" +
            "已达硬预算，本次发送被拦截。可调大设置项 " + KEY_HARD + "，或开启新会话继续。"

    suspend fun checkBudget(sessionId: Long): BudgetCheck {
        val soft = Graph.settings.kvGet(KEY_SOFT)?.trim()?.toIntOrNull()?.takeIf { it > 0 }
        val hard = Graph.settings.kvGet(KEY_HARD)?.trim()?.toIntOrNull()?.takeIf { it > 0 }
        if (soft == null && hard == null) return BudgetCheck.NoBudget
        return budgetStatus(sessionSpent(sessionId), soft, hard)
    }
}
