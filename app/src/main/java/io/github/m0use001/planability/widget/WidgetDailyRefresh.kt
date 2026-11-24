package io.github.m0use001.planability.widget

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import io.github.m0use001.planability.core.AppTime
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * 跨零点自动刷新。
 *
 * ## 为什么需要它
 * 小组件里大量内容带「今天」语义：`今日 · 10月17日`、`待办 2/5`、
 * `今日日程`、`近 7 天`的柱状图。provider XML 里的 `updatePeriodMillis`
 * 是 30 分钟一次，但因为**周期更新起点不确定**，过了零点后最多会有半小时
 * 挂着「昨天的日期」——早上第一眼看桌面的人最容易撞上。
 * 这里在每天 00:02 补一次定点刷新，把窗口压到 2 分钟。
 *
 * ## 为什么不用 setExact 硬上
 * 精确闹钟要 `SCHEDULE_EXACT_ALARM`（本项目已声明），但该权限在部分 ROM 上
 * 默认被关。沿用 `notify/Notify.kt` 的做法：能精确就精确，
 * 不能就退成 `setWindow` 给 5 分钟窗口，**绝不因为权限没给就让定时器静默消失**。
 *
 * ## 为什么每次触发都重新排
 * `setRepeating` 在 Doze 下会被大幅推迟且无法带窗口；一次性闹钟 + 每次重排
 * 才是「每天准点、且 Doze 也能唤醒」的组合。
 */
object WidgetDailyRefresh {

    private const val REQUEST_CODE = 0x5744      // 'W','D'
    private const val ACTION = "io.github.m0use001.planability.widget.DAILY_REFRESH"

    /** 目标时刻：次日 00:02（留 2 分钟给「日期已经变了」这件事先发生） */
    private fun nextFireAt(now: Long = AppTime.nowMillis()): Long {
        val zone = AppTime.zone
        val today = java.time.Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        var at = LocalDateTime.of(today.plusDays(1), java.time.LocalTime.of(0, 2))
        // 兜底：万一算出来的时间已经过去（时钟被改过），顺延一天
        var millis = at.atZone(zone).toInstant().toEpochMilli()
        if (millis <= now) {
            at = LocalDateTime.of(today.plusDays(2), java.time.LocalTime.of(0, 2))
            millis = at.atZone(zone).toInstant().toEpochMilli()
        }
        return millis
    }

    private fun pending(context: Context): PendingIntent {
        val intent = Intent(context, WidgetDailyRefreshReceiver::class.java).setAction(ACTION)
        return PendingIntent.getBroadcast(
            context, REQUEST_CODE, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    fun schedule(context: Context) {
        val app = context.applicationContext
        val am = app.getSystemService(AlarmManager::class.java) ?: return
        val at = nextFireAt()
        val p = pending(app)
        runCatching {
            val canExact = Build.VERSION.SDK_INT < 31 ||
                runCatching { am.canScheduleExactAlarms() }.getOrDefault(false)
            if (canExact) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, p)
            } else {
                am.setWindow(AlarmManager.RTC_WAKEUP, at, 5 * 60_000L, p)
            }
        }.onFailure {
            // 排不上也不影响功能：还有 provider 的 30 分钟周期更新兜底
            runCatching { am.setWindow(AlarmManager.RTC_WAKEUP, at, 15 * 60_000L, p) }
        }
    }
}

/**
 * 每日定点刷新的接收器。刷完**立刻排下一次**——
 * 一次性闹钟不重排的话，第二天就再也不响了（这也是这类定时器最常见的写错方式）。
 */
class WidgetDailyRefreshReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        WidgetRefresh.all(context)
        WidgetDailyRefresh.schedule(context)
    }
}