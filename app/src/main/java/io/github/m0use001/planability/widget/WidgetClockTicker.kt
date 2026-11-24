package io.github.m0use001.planability.widget

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.appwidget.AppWidgetManager
import android.os.Build
import android.os.PowerManager
import io.github.m0use001.planability.core.AppTime
import io.github.m0use001.planability.data.Graph
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * 两款大时钟的**分钟级心跳**。
 *
 * ## 为什么需要它
 * 大时钟显示「时：分」，但 provider 的 `updatePeriodMillis` 最小 30 分钟，
 * 且周期更新起点不确定——用户实测时钟会挂着十几分钟前的旧时间。
 * 这里每次更新后往「下一个整分钟」排一发一次性闹钟，触发时只重推两款时钟
 * 并立刻排下一分钟，形成心跳链；没有任何时钟实例在桌面上时链自然断掉（省电）。
 *
 * ## 电池
 * - 熄屏（`isInteractive=false`）时**跳过重绘只排下一跳**——桌面看不见，画了也白画；
 * - 位图很小、只推两款组件，成本可忽略；Pixel/原生时钟组件同样是分钟级闹钟的思路。
 *
 * ## 精确度
 * 沿用 [WidgetDailyRefresh] 的策略：能精确就 `setExactAndAllowWhileIdle`，
 * 权限没给就退 `setWindow` 1 分钟窗口，**绝不静默消失**（时钟晚一分钟比不响更糟）。
 */
object WidgetClockTicker {

    private const val REQUEST_CODE = 0x5743      // 'W','C'
    private const val ACTION = "io.github.m0use001.planability.widget.CLOCK_TICK"

    private fun pending(context: Context): PendingIntent {
        val intent = Intent(context, WidgetClockTickerReceiver::class.java).setAction(ACTION)
        return PendingIntent.getBroadcast(
            context, REQUEST_CODE, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /** 桌面上有没有任一款大时钟实例——没有就不排，心跳链自然断掉 */
    internal fun clockPlaced(context: Context): Boolean {
        val am = AppWidgetManager.getInstance(context) ?: return false
        fun anyOf(cls: Class<*>): Boolean =
            runCatching { am.getAppWidgetIds(ComponentName(context, cls)).isNotEmpty() }
                .getOrDefault(false)
        return anyOf(ClockWidgetReceiver::class.java) ||
            anyOf(ClockStackedWidgetReceiver::class.java)
    }

    fun schedule(context: Context) {
        val app = context.applicationContext
        if (!clockPlaced(app)) return
        val am = app.getSystemService(AlarmManager::class.java) ?: return
        val zone = AppTime.zone
        val next = Instant.ofEpochMilli(AppTime.nowMillis()).atZone(zone).toInstant()
            .truncatedTo(ChronoUnit.MINUTES).plus(1, ChronoUnit.MINUTES).toEpochMilli()
        val p = pending(app)
        runCatching {
            val canExact = Build.VERSION.SDK_INT < 31 ||
                runCatching { am.canScheduleExactAlarms() }.getOrDefault(false)
            if (canExact) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, p)
            } else {
                am.setWindow(AlarmManager.RTC_WAKEUP, next, 60_000L, p)
            }
        }.onFailure {
            runCatching { am.setWindow(AlarmManager.RTC_WAKEUP, next, 120_000L, p) }
        }
    }
}

/**
 * 每分钟的心跳接收器：亮屏才重绘两款时钟，然后立刻排下一跳。
 * 链路会由三个入口续上：本接收器、[WidgetRefresh.all]（数据变更/巡检/开机）、
 * 两个时钟 receiver 的 onUpdate（放置/调整尺寸时）。
 *
 * **goAsync 必须用**：闹钟唤醒的多半是被缓存冻结的进程，onReceive 直接 return 的
 * 话进程可能在协程跑完前又被冻住——闹钟在响、重绘却一次次被吞，桌面上就是
 * 「还是有时间延迟」（用户实测）。持住广播窗口等推完再 finish。
 */
class WidgetClockTickerReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pm = context.getSystemService(PowerManager::class.java)
        val pending = goAsync()
        val app = context.applicationContext
        Graph.appScope.launch {
            try {
                if (pm?.isInteractive != false) {
                    WidgetRefresh.clocksNow(app)
                }
            } finally {
                WidgetClockTicker.schedule(app)
                pending.finish()
            }
        }
    }
}
