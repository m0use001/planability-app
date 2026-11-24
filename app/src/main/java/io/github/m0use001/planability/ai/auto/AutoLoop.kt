package io.github.m0use001.planability.ai.auto

import io.github.m0use001.planability.core.AppTime
import io.github.m0use001.planability.core.AutoPolicy
import io.github.m0use001.planability.data.AutoTask
import io.github.m0use001.planability.data.Graph
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.time.Instant
import java.time.ZoneId

/**
 * v43：常驻自主循环的 runner（用户定向「杀后台仍常驻的自主循环」）。
 *
 * ## 一次 tick 做三件事
 * 1. **回收**：`running` 且心跳超时（进程被杀/被冻结）→ 改回 queued，下次重试；
 * 2. **播种**：任务表为空时排一个每日维护（证明循环活着，也顺手做库维护）；
 * 3. **执行**：到期任务逐条跑，成功→done / 周期→排下一次；失败→退避重试，3 次后 failed。
 *
 * ## 为什么"任务落库 + 心跳"就够扛杀后台
 * 被杀时内存里的一切都没了，但**库里的状态还在**：queued 的任务下个 tick 照跑；
 * running 的任务因为心跳不再更新，会在下次 tick 被回收成 queued 重跑。
 * 调度本身由 WorkManager（15 分钟周期，系统负责在杀进程后重排）+ 精确闹钟（30 分钟兜底）撑着。
 *
 * ## 内置任务（无状态、幂等，适合无人值守）
 * - `selfcheck`：验证循环链条（写日志）；
 * - `daily_maintenance`：清理 7 天前的历史任务 + `PRAGMA optimize`。
 * 写类/网络类任务（shell、网页操作）**不内置**：无人值守只跑低风险任务，
 * 需要写数据/点网页的任务必须走会话权限档与确认卡，后续按 kind 注册。
 */
object AutoLoop {

    private const val TAG = "AutoLoop"
    private const val TASK_TIMEOUT_MS = 120_000L

    private val handlers: Map<String, suspend (AutoTask) -> String> = mapOf(
        "selfcheck" to { t -> "ok #" + t.id + " at " + AppTime.nowMillis() },
        "daily_maintenance" to { _ -> maintenance() }
    )

    /** 跑一次巡检；返回本次执行的任务数（0 = 只是空转/回收） */
    suspend fun tick(now: Long = AppTime.nowMillis(), limit: Int = 3): Int = withContext(Dispatchers.IO) {
        val dao = Graph.db.autoTaskDao()
        if (dao.count() == 0) {
            dao.insert(
                AutoTask(
                    kind = "daily_maintenance",
                    title = "每日维护（库优化 + 清理历史任务）",
                    recurring = true,
                    intervalMin = 24 * 60,
                    nextRunAt = AutoPolicy.nextDailyAt(
                        Instant.ofEpochMilli(now), ZoneId.systemDefault(), 3, 10
                    ),
                    createdAt = now, updatedAt = now
                )
            )
            android.util.Log.i(TAG, "首次运行：已排每日维护（03:10）")
        }
        val recovered = dao.recoverStale(now - AutoPolicy.STALE_MS, now)
        if (recovered > 0) android.util.Log.i(TAG, "回收陈旧任务 $recovered 个（执行中进程被杀）")

        var ran = 0
        for (t in dao.due(now, limit)) {
            dao.setStatus(t.id, "running", now, "")
            var ok = false
            var note = ""
            try {
                val h = handlers[t.kind] ?: throw IllegalStateException("未知任务类型：${t.kind}")
                note = withTimeout(TASK_TIMEOUT_MS) { h(t) }
                ok = true
            } catch (e: Throwable) {
                note = e.message ?: e.javaClass.simpleName
            }
            val attempts = t.attempts + (if (ok) 0 else 1)
            val status = when {
                !ok && AutoPolicy.shouldFail(attempts) -> "failed"
                ok && !t.recurring -> "done"
                else -> "queued"
            }
            val next = if (!ok) now + AutoPolicy.retryDelayMs(attempts)
            else AutoPolicy.nextRunFor(t.recurring, t.intervalMin, now)
            dao.reschedule(t.id, attempts, status, next, AppTime.nowMillis(), if (ok) "" else note, t.checkpoint)
            android.util.Log.i(
                TAG,
                "任务 ${t.kind}#${t.id} → $status" + (if (ok) "（$note）" else "（失败：${note.take(120)}）")
            )
            ran++
        }
        ran
    }

    /** 每日维护：清理 7 天前已完成/失败任务 + 30 天前审计 + SQLite 优化 */
    private suspend fun maintenance(): String = withContext(Dispatchers.IO) {
        val dao = Graph.db.autoTaskDao()
        val pruned = dao.prune(AppTime.nowMillis() - 7L * 24 * 3600_000)
        // v44：审计只留 30 天。无上限的表会随工具面扩大（fs_*/shell/browser）持续变胖。
        val auditPruned = runCatching {
            Graph.db.toolAuditDao().prune(AppTime.nowMillis() - 30L * 24 * 3600_000)
        }.getOrDefault(0)
        runCatching { Graph.db.openHelper.writableDatabase.query("PRAGMA optimize").close() }
        "pruned=$pruned audit=$auditPruned"
    }
}
