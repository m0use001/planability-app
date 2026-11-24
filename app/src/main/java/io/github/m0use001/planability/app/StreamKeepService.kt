package io.github.m0use001.planability.app

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import io.github.m0use001.planability.MainActivity
import io.github.m0use001.planability.notify.Notify

/**
 * v12：AI 流式输出保活前台服务。
 * startStream 期间拉起（低优先"生成中"常驻通知），完成/中断即撤——防 ColorOS 后台冻结打断输出。
 */
class StreamKeepService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        startForegroundCompat()
        return START_NOT_STICKY
    }

    private fun startForegroundCompat() {
        // 点通知 → 信息页（回到 AI 对话看输出），不是笼统地开 App
        val pi = Notify.openIntent(this, Notify.ROUTE_CHAT)
        val builder = NotificationCompat.Builder(this, "focus")
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle("AI 正在生成…")
            .setContentText("后台保活中，回到应用查看输出")
            .setContentIntent(pi)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
        val n: Notification = builder.build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    companion object {
        const val ACTION_STOP = "stop"
        const val NOTIF_ID = 1002
        private var refs = 0

        /** 流开始（可重入：多会话并行时按计数加减） */
        fun acquire(ctx: Context) {
            refs++
            if (refs == 1) {
                runCatching {
                    ctx.startForegroundService(Intent(ctx, StreamKeepService::class.java))
                }
            }
        }

        /** 流结束 */
        fun release(ctx: Context) {
            refs = (refs - 1).coerceAtLeast(0)
            if (refs == 0) {
                runCatching { ctx.startService(Intent(ctx, StreamKeepService::class.java).setAction(ACTION_STOP)) }
            }
        }
    }
}
