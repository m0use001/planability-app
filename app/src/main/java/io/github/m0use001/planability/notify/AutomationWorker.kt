package io.github.m0use001.planability.notify

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.m0use001.planability.core.AppTime
import io.github.m0use001.planability.data.Automation
import io.github.m0use001.planability.data.AutomationRepo
import io.github.m0use001.planability.data.AutomationRun
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import io.github.m0use001.planability.LauncherNightIcon

/**
 * v11：自动化任务调度（零新依赖：AlarmManager 15 分钟不精确重复巡检）。
 * 巡检规则：间隔型按 lastRun 判定；每日时刻型按今天触发点是否已过且未跑过判定。
 * 触发即用对应智能体执行 prompt（ChatEngine.runAction，动作会话留档），结果发系统通知。
 */
object AutomationScheduler {
    private const val ACTION = "io.github.m0use001.planability.AUTOMATION_SWEEP"
    const val NOTIF_BASE = 3_000_000

    fun ensure(context: Context) {
        val am = context.getSystemService(AlarmManager::class.java)
        am.setInexactRepeating(
            AlarmManager.RTC_WAKEUP,
            System.currentTimeMillis() + 60_000L,
            15 * 60_000L,
            PendingIntent.getBroadcast(
                context, 0,
                Intent(context, AutomationReceiver::class.java).setAction(ACTION)
                    .setData(android.net.Uri.parse("automation://sweep")),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        )
    }
}

class AutomationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                AutomationSweep.run(context)
            } finally {
                pending.finish()
            }
        }
    }
}

object AutomationSweep {
    suspend fun run(context: Context) {
        // v19.49：每次巡检顺手纠正一次启动器图标。
        // 这是「app 进程已死」时唯一还会跑的路（15 分钟一次），
        // 否则系统切到暗色后图标会一直停在浅色那套（用户 10-18 反馈的现象）。
        // 幂等：状态没变就是一次 PackageManager 读，不做实际切换。
        runCatching { LauncherNightIcon.apply(context) }
        val now = LocalDateTime.now(AppTime.zone)
        val nowMillis = AppTime.nowMillis()
        // v19.42：`hasEventTrigger` 的**排除**是这里最要紧的一行。
        // 巡检只管「到点看一眼」；事件触发由 AutomationSignal 精确投递。
        // 少了这个排除，事件型任务会**每 15 分钟跑一遍**（下面的 else 分支还会拿
        // dailyHour=-1 去算触发时刻），而事件触发又没有 lastRun 那种天然护栏——
        // 症状是「我只插了一次充电，它跑了一晚上」，且很难想到是巡检干的。
        AutomationRepo.all()
            .filter { it.enabled && it.hasTrigger && !it.hasEventTrigger }
            .forEach { a ->
            val due = when {
                // 位置触发由 GeoWatch 单独送；这里必须显式判 dailyHour 合法，
                // 否则 millisOf(today, -1, -1) 会拿一个不存在的时刻去比「今天已过没」
                a.intervalMinutes > 0 ->
                    a.lastRun == 0L || nowMillis - a.lastRun >= a.intervalMinutes * 60_000L
                a.dailyHour in 0..23 && a.dailyMinute in 0..59 -> {
                    // 每日时刻：今天已过触发点且 lastRun 早于今天的触发点
                    val triggerMillis = AppTime.millisOf(now.toLocalDate(), a.dailyHour, a.dailyMinute)
                    nowMillis >= triggerMillis && a.lastRun < triggerMillis
                }
                else -> false
            }
            if (!due) return@forEach
            AutomationRepo.touchLastRun(a.id)
            execute(context, a)
        }
        // 顺手把「打开应用时」的观察器拉起来（幂等）：它是后台常驻的，
        // 而巡检每 15 分钟一定会醒一次，是最省事、又不占别人文件的启动点。
        AutomationSignal.ensureAppObserver()
        // 顺手查一次无障碍服务有没有被 ColorOS 静默关掉（内部自带节流：一次事故只通知一条，
        // 恢复后自动复位）。巡检是后台唯一保证每 15 分钟醒一次的路——放在它这里，
        // 就算用户不解锁、不装任何触发器，最多 15 分钟内也会被提醒。
        runCatching { A11yWatch.checkAndNotify(context) }
    }

    /**
     * 真正跑一条自动化：会话锚定 → AI 执行 → 系统通知。
     *
     * v24 从 `run()` 里抽出来，因为地理围栏触发要复用同一条执行路径——
     * 「定时跑」和「到地方了跑」除了触发来源以外没有任何区别，各写一份迟早分叉。
     */
    suspend fun execute(context: Context, a: Automation) {
        // v26：步骤流水线走确定性执行器，不经过模型；AI 路径保持原样。
        // 定时巡检和地理围栏两个触发入口都收口在这里，分支只写这一处。
        val startedAt = System.currentTimeMillis()
        // 采集类自动化（AI 的 phone_* 工具 / 流水线的 app_open、a11y_* 步骤）会把前台
        // 带到微信/QQ 等别的应用，跑完停在那里会让用户以为手机失控。开跑前记住前台，
        // 跑完（无论成败）由 AutoReturn 把前台还给用户——判定表见 AutoReturn.decide。
        val prevFg = AutoReturn.currentFg()
        try {
            if (a.kind == "flow") {
                var recorded = false
                runCatching {
                    val r = io.github.m0use001.planability.core.FlowRunner.run(context, a.id, a.name, a.stepsJson)
                    // 先落记录再发通知：通知是「别人看得到」的那一步，排在最后，
                    // 通知万一失败时执行结果也不会跟着一起消失。
                    recorded = record(a, "flow", true, r.summary, r.log.joinToString("\n"), startedAt)
                    // FlowRunner 必出结果（含每步明细，失败带 ❌ 前缀），成功失败都通知，
                    // 免得"到点跑没跑"又变成一个看不见的问题
                    Notify.remind(
                        context, AutomationScheduler.NOTIF_BASE + (a.id % 100_000).toInt(),
                        "自动化：" + a.name,
                        r.summary.take(180),
                        Notify.ROUTE_AUTOMATION
                    )
                }.onFailure {
                    android.util.Log.e("AutomationSweep", "流水线执行失败 id=" + a.id, it)
                    if (!recorded) {
                        record(a, "flow", false, it.message ?: it.toString(), it.stackTraceToString().take(2000), startedAt)
                    }
                }
                return
            }
            var recorded = false
            runCatching {
                // v18.1：会话锚定——每次执行追加到同一个对话（AI 创建的自动化=生成它的对话；
                // 用户自建的=首个「自动化·任务名」会话收编为锚点），不再每次新开同名会话
                val title = "自动化·" + a.name
                val kvKey = "auto.session:" + a.id
                val sessions = io.github.m0use001.planability.data.Graph.chat.sessions()
                var anchor = io.github.m0use001.planability.data.Graph.settings.kvGet(kvKey)?.toLongOrNull() ?: 0L
                if (anchor != 0L && sessions.none { it.id == anchor }) anchor = 0L
                if (anchor == 0L) {
                    anchor = sessions.firstOrNull { it.title == title }?.id ?: 0L
                }
                val (sid, answer) = io.github.m0use001.planability.ai.ChatEngine.runAction(
                    a.assistantKey, title, a.prompt, sessionIdIn = anchor
                )
                if (anchor == 0L || anchor != sid) {
                    io.github.m0use001.planability.data.Graph.settings.kvPut(kvKey, sid.toString())
                }
                recorded = record(a, "ai", true, answer, "", startedAt)
                Notify.remind(
                    context, AutomationScheduler.NOTIF_BASE + (a.id % 100_000).toInt(),
                    "自动化：" + a.name,
                    answer.take(180),
                    Notify.ROUTE_AUTOMATION
                )
            }.onFailure {
                android.util.Log.e("AutomationSweep", "自动化执行失败 id=" + a.id, it)
                // 失败也要留痕：这正是没有日志时的盲区——
                // 通知不发、会话可能都没落下，界面上什么都查不到。
                if (!recorded) {
                    record(a, "ai", false, it.message ?: it.toString(), it.stackTraceToString().take(2000), startedAt)
                }
            }
        } finally {
            // 放 finally：中途异常/取消也要把前台还给用户（停在微信里是最常见的"它怎么了"来源）
            AutoReturn.restore(prevFg)
        }
    }

    /**
     * 落一条执行记录。**任何情况下都不许把异常抛回执行路径**——
     * "写日志失败把刚跑完的自动化带崩"比没有日志更糟，所以整体再包一层 runCatching。
     *
     * 返回是否写成功：调用方靠它避免「成功那次已写、通知失败又补一条 fail」的重复记录。
     */
    private suspend fun record(
        a: Automation,
        kind: String,
        ok: Boolean,
        summary: String,
        detail: String,
        startedAt: Long
    ): Boolean =
        runCatching {
            AutomationRepo.logRun(
                AutomationRun(
                    automationId = a.id,
                    name = a.name,
                    kind = kind,
                    at = startedAt,
                    ok = ok,
                    summary = summary.take(4000),
                    detail = detail.take(8000),
                    durationMs = System.currentTimeMillis() - startedAt
                )
            )
            true
        }.getOrElse {
            android.util.Log.e("AutomationSweep", "写执行日志失败 id=" + a.id, it)
            false
        }
}
