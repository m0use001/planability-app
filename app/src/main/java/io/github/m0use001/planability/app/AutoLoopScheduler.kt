package io.github.m0use001.planability.app

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.Worker
import androidx.work.WorkManager
import io.github.m0use001.planability.ai.auto.AutoLoop
import kotlinx.coroutines.runBlocking
import java.util.concurrent.TimeUnit

/**
 * v43：常驻自主循环的调度器（用户定向「杀后台仍常驻」）。
 *
 * ## 双通道（为什么不是只用 WorkManager）
 * - **WorkManager 周期 15 分钟**：系统级持久化，进程被杀后由系统重排；缺点是 OEM 会推迟。
 * - **精确闹钟 30 分钟**：`setExactAndAllowWhileIdle` 在国产 ROM 上更可靠（Doze 也放行）；
 *   每次触发都重排下一次，形成自续链。
 * 两条通道都可能在对方没有上下文时触发（闹钟广播 / 周期任务），所以都走同一把锁：
 * 只 enqueue 一个 `auto_loop_oneshot`（REPLACE 去重）或直接由 worker 跑 tick。
 *
 * ## 开机/升级
 * 由 BootReceiver（已存在）调用 [ensure] 重建两条链——**这要求 manifest 有
 * RECEIVE_BOOT_COMPLETED**（v43 补上：此前缺失，v14 的"重启重挂闹钟"实际没生效）。
 */
object AutoLoopScheduler {

    private const val PERIODIC = "auto_loop_periodic"
    private const val ONESHOT = "auto_loop_oneshot"
    private const val ACTION_TICK = "io.github.m0use001.planability.AUTO_TICK"

    /** 闹钟兜底间隔（分钟） */
    private const val ALARM_MINUTES = 30L

    fun ensure(context: Context) {
        runCatching {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                PERIODIC,
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<AutoTickWorker>(15, TimeUnit.MINUTES)
                    .setConstraints(Constraints.NONE)
                    .build()
            )
        }
        scheduleAlarm(context)
    }

    /** 排下一次精确闹钟（每次触发后自续） */
    fun scheduleAlarm(context: Context) {
        runCatching {
            val am = context.getSystemService(AlarmManager::class.java) ?: return
            val pi = PendingIntent.getBroadcast(
                context, 9911,
                Intent(context, AutoTickReceiver::class.java).setAction(ACTION_TICK),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            am.setExactAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + ALARM_MINUTES * 60_000L,
                pi
            )
        }
    }
}

/** 周期/一次性任务体：跑一次 tick 并续上闹钟。Worker 在后台线程，runBlocking 安全 */
class AutoTickWorker(ctx: Context, params: androidx.work.WorkerParameters) : Worker(ctx, params) {
    override fun doWork(): Result {
        runCatching { runBlocking { AutoLoop.tick() } }
        AutoLoopScheduler.scheduleAlarm(applicationContext)
        return Result.success()
    }
}

/** 精确闹钟的接收器：重排下一次 + 让 WorkManager 跑一次 tick（REPLACE 去重） */
class AutoTickReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        AutoLoopScheduler.scheduleAlarm(context)
        runCatching {
            WorkManager.getInstance(context).enqueueUniqueWork(
                "auto_loop_oneshot",
                ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<AutoTickWorker>().build()
            )
        }
    }
}
