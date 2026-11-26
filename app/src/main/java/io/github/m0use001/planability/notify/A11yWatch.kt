package io.github.m0use001.planability.notify

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import io.github.m0use001.planability.data.Graph

/**
 * 无障碍服务存活自检：ColorOS 会在**重启 / 长时间休眠**后静默关掉本应用的读屏采集服务
 * （见 ◆/planability-gotchas.md：本期发生两次，症状 = 采集时报「读屏采集未开启」，
 * 且事后很难发现——采集失败只在执行记录里留一行，用户多半过了很久才注意到没采到东西）。
 *
 * ## 能做与不能做（先说边界，别抱幻想）
 *  - **不能静默重开**：写 `Settings.Secure.enabled_accessibility_services` 需要
 *    WRITE_SECURE_CONFIG（signature|privileged），第三方应用拿不到。adb 能写是因为 shell 有权。
 *  - **能做的**：尽早发现 + 一条可点的通知直达无障碍设置页 + 文案教用户点「保持开启」。
 *    ColorOS 的「保持开启（3 秒倒数）」弹窗就是系统官方的「别再自动关」机制，
 *    手动重新打开时点它，之后被静默关闭的概率会低很多——文案必须把这句写上，
 *    否则用户重开完下次照样被关，会以为 App 没修好。
 *
 * ## 检测时机（两处，见调用点）
 *  - 15 分钟巡检（[AutomationSweep.run]）：后台保底，≤15 分钟发现一次；
 *  - 解锁/开机广播（[AutomationSignalReceiver]）：系统就是在重启/长休眠后关的，
 *    解锁是「刚被关掉」的最早可见时机，这一路能把发现时间压到秒级。
 *
 * ## 节流
 * 同一次事故只通知一条：KV `a11y.watch.notified` 置位；服务恢复（自检发现已开启）即清除，
 * 下次再被关会重新通知。不做「每 N 小时再提醒」——用户要么去点、要么暂时不用采集功能，
 * 反复弹只会变成狼来了。
 */
object A11yWatch {

    /** 专属通知 id：不与提醒/自动化共用，避免互相覆盖 */
    private const val NOTIF_ID = 4_200_000
    private const val KV_NOTIFIED = "a11y.watch.notified"

    /**
     * 被自检的服务（与 AndroidManifest 里声明的一致；测试也钉这一个）。
     * 2026-11-26 开源根治：类名改由设备包经 DeviceFeatures.a11y 提供——服务类本体
     * 在 可选设备源集，公开版恒为空串（无服务可自检，功能自然静默）。
     */
    val SERVICE_CLS: String get() = io.github.m0use001.planability.core.DeviceFeatures.a11y.serviceClassName

    /**
     * 纯判定：`Settings.Secure.enabled_accessibility_services` 的扁平串里有没有本服务。
     *
     * 两种写法都认：系统正常写的是 flattenToString 格式（`pkg/全限定类名`），
     * 但部分 ROM/工具写的是短类名（`pkg/.类名`）——只认一种会在某些机器上永远报「已关」。
     *
     * 刻意不用 `ComponentName.flattenToString()` 拼期望值：单测跑在纯 JVM 上，
     * android.jar 的桩方法全是 "not mocked"，字符串拼接才能让这条判定可被直接测试。
     */
    fun enabled(flat: String?, pkg: String, cls: String): Boolean {
        if (flat.isNullOrBlank()) return false
        return flat.contains("$pkg/$cls") || flat.contains("$pkg/.$cls")
    }

    /**
     * 服务是否**真的**在工作：总开关（`accessibility_enabled`）与服务清单**缺一不可**。
     *
     * 真机实证（2026-10-22 install -r 后）：总开关被重置为 0、但
     * `enabled_accessibility_services` 里本服务的名字**还在**——只查清单会把这种
     * 「关了一半」的状态误判成正常，自检从此永远静默。ColorOS 重启/休眠后
     * 两种形态都出现过，必须双查。
     */
    fun serviceOn(masterOn: Boolean, flat: String?, pkg: String, cls: String): Boolean =
        masterOn && enabled(flat, pkg, cls)

    /** 自检 + 按需发通知。调用方已包 runCatching；本函数内部也不允许抛（见 [post]） */
    suspend fun checkAndNotify(context: Context) {
        val cr = context.contentResolver
        val flat = runCatching {
            Settings.Secure.getString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        }.getOrNull()
        val masterOn = runCatching {
            Settings.Secure.getInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 0) == 1
        }.getOrDefault(false)
        val on = serviceOn(masterOn, flat, context.packageName, SERVICE_CLS)
        val notified = runCatching { Graph.settings.kvGet(KV_NOTIFIED) }.getOrNull()
        // 诊断日志：这功能的失败模式全是「安静地没生效」，logcat 必须能回答
        // 「查了没、判成什么、为什么没通知」三件事（2026-10-22 首装后通知未落地，靠它排查）
        android.util.Log.d(
            "A11yWatch",
            "check masterOn=$masterOn flatHasSvc=${flat?.contains(SERVICE_CLS) == true} " +
                "on=$on notified=$notified"
        )
        if (on) {
            // 恢复了：清掉置位，让下一次事故能重新通知
            if (notified != null) runCatching { Graph.settings.kvDelete(KV_NOTIFIED) }
            return
        }
        if (notified == "1") return
        post(context)
        runCatching { Graph.settings.kvPut(KV_NOTIFIED, "1") }
    }

    /** 发「已被系统关闭」的通知；点击直达无障碍设置。任何失败都只吞不抛 */
    private fun post(context: Context) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) return
        // 深链用**通用无障碍总页**，不能用 ACCESSIBILITY_DETAILS_SETTINGS：
        // 后者（API 34 起）要求 android.permission.OPEN_ACCESSIBILITY_DETAILS_SETTINGS
        // （signature|privileged），第三方应用点通知时会被 SecurityException 拦掉、什么也不弹
        // ——2026-10-22 真机实测（shell 直启同样被拒）。总页无需权限，且文案里已写清服务名。
        val deep = Intent("android.settings.ACCESSIBILITY_SETTINGS")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val pi = runCatching {
            PendingIntent.getActivity(
                context, 42, deep,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }.getOrNull()
        val body = "ColorOS 会在重启/休眠后自动关掉无障碍服务。" +
            "点这里重新打开「读屏采集（仅手动触发）」；" +
            "如果弹出「保持开启」确认框，请点「保持开启」，系统就不会再自动关它。"
        val n = NotificationCompat.Builder(context, "reminders")
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("读屏采集权限已被系统关闭")
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
        if (pi != null) n.setContentIntent(pi)
        runCatching { NotificationManagerCompat.from(context).notify(NOTIF_ID, n.build()) }
    }
}
