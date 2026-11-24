package io.github.m0use001.planability.notify

import android.Manifest
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import io.github.m0use001.planability.MainActivity
import io.github.m0use001.planability.core.AppTime
import io.github.m0use001.planability.core.HapticEngine
import io.github.m0use001.planability.data.Graph
import io.github.m0use001.planability.LauncherNightIcon

object Notify {
    /**
     * 通知点击后的落地目标。**与 `MainActivity.handleShortcut` 里的 when 分支一一对应**，
     * 新增一种必须两边同步改，否则点了没反应。
     */
    const val ROUTE_TODO = "todo"
    const val ROUTE_SCHEDULE = "schedule"
    const val ROUTE_FOCUS = "focus"
    const val ROUTE_CHAT = "chat"
    const val ROUTE_AUTOMATION = "automation"
    const val ROUTE_DIGEST = "digest"

    /**
     * 通知 → 打开 App 并跳到对应界面（MainActivity 是 singleTop，热路径走 onNewIntent）。
     *
     * **requestCode 要按 (route, targetId) 组合、不能按通知 id**：不同目标（两个不同待办的提醒）
     * 必须拿到不同 PendingIntent 实例，否则 FLAG_UPDATE_CURRENT 会把前一个的 extra 覆盖掉，
     * 结果点第二个待办的通知跳到第一个待办。data URI 里也带上 route/targetId ——
     * PendingIntent 的相等性看 action/data/type 而**不看 extra**，光靠 requestCode 不够稳。
     */
    fun openIntent(context: Context, route: String, targetId: Long = 0L): PendingIntent =
        PendingIntent.getActivity(
            context,
            31 * route.hashCode() + targetId.toInt(),
            Intent(context, MainActivity::class.java)
                .setAction(MainActivity.ACTION_NOTIFICATION)
                .setData(android.net.Uri.parse("planability://notification/$route/$targetId"))
                .putExtra(MainActivity.EXTRA_ROUTE, route)
                .putExtra(MainActivity.EXTRA_TARGET_ID, targetId)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    fun remind(
        context: Context, id: Int, title: String, text: String,
        route: String = ROUTE_CHAT, targetId: Long = 0L,
    ) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val n = NotificationCompat.Builder(context, "reminders")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            // 点通知跳对应界面（以前根本没设 contentIntent，点开什么都不会发生）
            .setContentIntent(openIntent(context, route, targetId))
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(id, n) }
    }

    /**
     * 专注常驻通知。**2026-11-03 起不再每秒重发**：计时交给系统 Chronometer
     * （[android.app.Notification.setUsesChronometer]），服务端只在阶段/轮次/暂停态
     * 变化时重发一次。此前 `updateNotification()` 每秒 `nm.notify` 一次，状态栏图标
     * 每秒被按时间重排，与系统「planability 正在其他软件上方显示」的图标不断换位
     * （用户真机实拍）。
     *
     * [chronoBaseElapsed]：非 0 时启用计时器——倒计时传 `elapsedRealtime()+remain*1000`
     * （countDown=true，系统自动显示剩余 mm:ss），正计时传 `elapsedRealtime()-elapsed*1000`；
     * 0 = 不启用（暂停态用静态文本）。
     */
    fun focus(
        context: Context, text: String,
        chronoBaseElapsed: Long = 0L, countDown: Boolean = false,
    ): android.app.Notification =
        NotificationCompat.Builder(context, "focus")
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("专注进行中")
            .setContentText(text)
            // 内容 Intent 必须自带：通知可能被重发/重建，漏了就点不开
            .setContentIntent(openIntent(context, ROUTE_FOCUS))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(chronoBaseElapsed != 0L)
            .setUsesChronometer(chronoBaseElapsed != 0L)
            .setWhen(chronoBaseElapsed)
            .setChronometerCountDown(countDown)
            .build()
}

/**
 * 日程/待办提醒：优先 setExactAndAllowWhileIdle（_doze 放行），
 * 无精确闹钟权限（ColorOS 默认拒）或抛 SecurityException 时回退 setWindow。
 * id 编码：日程 1_000_000+id、待办 2_000_000+id，避免互相覆盖。
 */
object ReminderScheduler {
    private const val ACTION = "io.github.m0use001.planability.REMIND"
    const val SCHEDULE_BASE = 1_000_000
    const val TODO_BASE = 2_000_000

    /**
     * alarm 的 PendingIntent。route/targetId 走 extra 携带：nid 是 `BASE + (原 id % BASE)`
     * 的**有损编码**（BASE 是 1_000_000），光靠 nid 没法还原原始实体 id，深链接必须另带一份。
     * extra 不参与 PendingIntent 相等性，所以 cancel() 那边照样能用同一个 id 撤掉。
     */
    private fun pi(
        context: Context, id: Int, title: String?, text: String?,
        route: String = "", targetId: Long = 0L,
    ): PendingIntent =
        PendingIntent.getBroadcast(
            context, id,
            Intent(context, ReminderReceiver::class.java).setAction(ACTION)
                .putExtra("nid", id)
                .putExtra("route", route)
                .putExtra("targetId", targetId)
                .putExtra("title", title ?: "")
                .putExtra("text", text ?: "")
                .setData(android.net.Uri.parse("reminder://$id")),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    fun schedule(
        context: Context, id: Int, at: Long, title: String, text: String,
        route: String = Notify.ROUTE_TODO, targetId: Long = 0L,
    ) {
        if (at <= System.currentTimeMillis()) return
        val am = context.getSystemService(AlarmManager::class.java)
        val pending = pi(context, id, title, text, route, targetId)
        val canExact = runCatching { am.canScheduleExactAlarms() }.getOrDefault(false)
        try {
            if (canExact) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
            } else {
                am.setWindow(AlarmManager.RTC_WAKEUP, at, 60_000L, pending)
            }
        } catch (e: SecurityException) {
            am.setWindow(AlarmManager.RTC_WAKEUP, at, 60_000L, pending)
        }
    }

    fun cancel(context: Context, id: Int) {
        val am = context.getSystemService(AlarmManager::class.java)
        am.cancel(pi(context, id, null, null))
    }
}

/** App 启动补挂：为未来 48h 内的日程（含重复展开）与有截止时刻的待办重挂提醒 */
object ReminderReArm {
    suspend fun rearm(context: Context) {
        val now = AppTime.nowMillis()
        val today = AppTime.today()
        Graph.plan.scheduleOccurrences(today, today.plusDays(2)).forEach { occ ->
            val lead = occ.schedule.remindMin
            if (lead < 0) return@forEach
            val at = occ.start - lead * 60_000L
            if (occ.start > now) {
                ReminderScheduler.schedule(
                    context, ReminderScheduler.SCHEDULE_BASE + (occ.schedule.id % ReminderScheduler.SCHEDULE_BASE).toInt(),
                    at, "日程：${occ.schedule.title}", formatAt(occ.start),
                    Notify.ROUTE_SCHEDULE, occ.schedule.id
                )
            }
        }
        listOf(today, today.plusDays(1)).forEach { day ->
            Graph.plan.todosOfDay(AppTime.dayKey(day)).forEach { t ->
                if (t.remindMin < 0 || t.due <= now || t.done) return@forEach
                val at = t.due - t.remindMin * 60_000L
                ReminderScheduler.schedule(
                    context, ReminderScheduler.TODO_BASE + (t.id % ReminderScheduler.TODO_BASE).toInt(),
                    at, "待办：${t.title}", formatAt(t.due),
                    Notify.ROUTE_TODO, t.id
                )
            }
        }
    }

    private fun formatAt(millis: Long): String {
        val dt = java.time.Instant.ofEpochMilli(millis).atZone(AppTime.zone).toLocalDateTime()
        return "${dt.monthValue}月${dt.dayOfMonth}日 %02d:%02d".format(dt.hour, dt.minute)
    }
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val title = intent.getStringExtra("title") ?: return
        val text = intent.getStringExtra("text") ?: ""
        val nid = intent.getIntExtra("nid", title.hashCode())
        // 渠道已关闭系统震动：按全局强度显式产生提醒触感
        runCatching {
            HapticEngine(context) {
                runCatching { Graph.settings.snapshot.value.theme.hapticIntensity / 100f }.getOrDefault(1f)
            }.perform(HapticEngine.Kind.ImpactHeavy)
        }
        // 优先用调度时带的 route；改版前挂上的老 alarm 没有这个 extra，就按 nid 落到的区间反推种类
        // —— SCHEDULE_BASE=1_000_000 / TODO_BASE=2_000_000 两个区间不重叠，反推是安全的
        val route = intent.getStringExtra("route")?.takeIf { it.isNotEmpty() }
            ?: if (nid >= ReminderScheduler.TODO_BASE) Notify.ROUTE_TODO
            else Notify.ROUTE_SCHEDULE
        val targetId = intent.getLongExtra("targetId", 0L)
        Notify.remind(context, nid, title, text, route, targetId)
    }
}

/**
 * v14：开机后把两条提醒链路重新挂上。
 *
 * ## 之前为什么提醒会丢
 *
 * `AlarmManager` 里的一切闹钟都是**进程内存之外**的调度，但这个 App 从来没有
 * `BOOT_COMPLETED` 的receiver。于是每次重启手机之后：
 * 自动化巡检闹钟没了、日程/待办的精确闹钟也没了，
 * 而用户视角就是「重启之后提醒全不响」，直到他手动点开一次 App 触发 `PlanabilityApp.onCreate`
 * 里的补挂。手机一天重启好几次，这个「必须手动开一次」的条件太苛刻了。
 *
 * ## 做法
 *
 * 沿用既有两条链路各自的入口，不新增机制：自动化走 `AutomationScheduler.ensure`，
 * 日程/待办走 `ReminderReArm.rearm`——和 `PlanabilityApp.onCreate` 里调的是同两个函数，
 * 所以不会出现「开机补的和冷启动补的规则不一样」。
 *
 * 用 `goAsync` + IO 线程：`rearm` 要读库算 48h 内的发生时刻，在主线程里做会踩 ANR。
 * `ACTION_MY_PACKAGE_REPLACED` 也一并收：应用更新同样会清空闹钟。
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) return
        val pending = goAsync()
        val app = context.applicationContext
        Thread {
            try {
                // v19.49：开机/更新后纠正一次启动器图标
                // （Application.onCreate 也会做，但进程若已被系统先拉起过一次，
                //   这里兜住「图标停留在上一套」的情况）。
                // 现在是「配色 × 明暗」矩阵，要先读设置，故用 runBlocking 收口（本就在后台线程）。
                runCatching {
                    kotlinx.coroutines.runBlocking { LauncherNightIcon.apply(app) }
                }
                runCatching { AutomationScheduler.ensure(app) }
                // v43：常驻自主循环的调度链（WorkManager 周期 + 精确闹钟）也要在开机后重建
                runCatching { io.github.m0use001.planability.app.AutoLoopScheduler.ensure(app) }
                // v24：开机后同样要把地理围栏接回来（进程死了监听就没了）
                runCatching { GeoWatch.sync(app) }
                runCatching {
                    kotlinx.coroutines.runBlocking {
                        runCatching { ReminderReArm.rearm(app) }
                    }
                }
            } finally {
                pending.finish()
            }
        }.start()
    }
}
