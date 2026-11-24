package io.github.m0use001.planability.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.m0use001.planability.data.Automation
import io.github.m0use001.planability.data.AutomationRepo
import io.github.m0use001.planability.data.Graph
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * v19.42：**事件触发**（充电 / 解锁 / 开机 / 打开某个应用）。
 *
 * ## 为什么单独一个文件，而不是塞进 [AutomationWorker]
 * 定时触发是「到点看一眼」，事件触发是「**别人告诉我们要做什么**」——两者的入口、
 * 生命周期、去重规则都不一样。混进 `AutomationWorker` 会让那个文件的 `run()`
 * 同时管两套语义，而它已经有一段必须小心读的巡检过滤（见 [AutomationSweep.run]）。
 *
 * ## 与定时触发的分工（这条是整个 v19.42 的地基）
 * - **定时**：15 分钟巡检，按 `intervalMinutes` / 每日时刻判定；
 * - **事件**：[AutomationSignalReceiver] 收系统广播 → [fire] 按 `triggerKind` 精确匹配。
 * 两条路都收口到 [AutomationSweep.execute]，所以执行记录、通知、AI 会话锚定全都只有一份。
 *
 * **配了事件触发的任务不会被巡检捡走**——判据是 `Automation.hasEventTrigger`，
 * 由 [AutomationSweep.run] 显式排掉。少这一步的现场症状是「我只插了一次充电，
 * 它一晚上跑了七八回」，而且查起来会以为是 bug 在触发逻辑里。
 */
object AutomationSignal {

    // ---- 事件类型（与 Automation.triggerKind 同一套字面量，AI 工具也用它们）----

    /** 开机后 / 应用升级后跑一次 */
    const val KIND_BOOT = "boot"

    /** 充电状态变化；arg = `on`（插上）/ `off`（拔掉） */
    const val KIND_POWER = "power"

    /** 解锁后跑一次（对应系统的 ACTION_USER_PRESENT） */
    const val KIND_UNLOCK = "unlock"

    /** 打开某个应用时跑；arg = 目标包名。依赖无障碍已开启 */
    const val KIND_APP_OPEN = "app_open"

    val KINDS = listOf(KIND_BOOT, KIND_POWER, KIND_UNLOCK, KIND_APP_OPEN)

    /**
     * 系统广播 → (事件类型, 参数)。
     *
     * **只列 manifest 里能收到的那些。** Android 8 起隐式广播有白名单，
     * 白名单外的事件在 manifest 里声明了也收不到（SCREEN_ON/SCREEN_OFF/HEADSET_PLUG
     * 都在名单外，必须动态注册，而动态注册要求进程活着——那就不是「自动」了）。
     * 所以解锁用 `ACTION_USER_PRESENT`（在白名单里），而不是 SCREEN_ON。
     */
    fun matchBroadcast(action: String): Pair<String, String>? = when (action) {
        Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> KIND_BOOT to ""
        Intent.ACTION_POWER_CONNECTED -> KIND_POWER to "on"
        Intent.ACTION_POWER_DISCONNECTED -> KIND_POWER to "off"
        Intent.ACTION_USER_PRESENT -> KIND_UNLOCK to ""
        else -> null
    }

    /** 列表卡片与编辑器上显示的触发描述 */
    fun describe(kind: String, arg: String): String = when (kind) {
        KIND_BOOT -> "开机后 / 应用升级后"
        KIND_POWER -> if (arg == "off") "拔掉充电器时" else "插上充电器时"
        KIND_UNLOCK -> "解锁后"
        KIND_APP_OPEN -> if (arg.isBlank()) "打开应用时" else "打开「$arg」时"
        else -> "未配置触发"
    }

    /** 事件参数是否匹配这条自动化。空参数 = 匹配该类型的任意参数 */
    private fun argMatches(want: String, got: String): Boolean = want.isBlank() || want == got

    /**
     * 去重窗口。系统广播可能**同一次事件送达两遍**（进程被杀后重投、
     * 或 manifest 与动态注册同时收到），而事件触发没有 `lastRun` 那种天然护栏——
     * 不去重就是「插一次充电跑两遍」。2 分钟足够挡住重投，又不会误伤用户手动重复操作。
     */
    private const val DEDUP_MS = 120_000L
    private val lastFired = HashMap<Long, Long>()

    /**
     * 触发一批任务。返回真正跑了几个。
     *
     * 逐条串行而不是并发：并发时它们会抢同一份通知/数据库写入，
     * 而事件触发的语义本来就该是「挨着做完」。
     */
    suspend fun fire(context: Context, kind: String, arg: String): Int {
        val list = AutomationRepo.all().filter {
            it.enabled && it.triggerKind == kind && argMatches(it.triggerArg, arg)
        }
        if (list.isEmpty()) return 0
        val now = System.currentTimeMillis()
        var n = 0
        list.forEach { a ->
            synchronized(lastFired) {
                val last = lastFired[a.id] ?: 0L
                if (now - last < DEDUP_MS) return@forEach
                lastFired[a.id] = now
            }
            runCatching { AutomationSweep.execute(context, a) }
            n++
        }
        return n
    }

    // ---- 「打开应用时」触发：依赖无障碍窗口监听，已随设备控制栈移除 ----

    /**
     * 保留空实现：巡检与自动化列表页仍会调用它，但不再注册无障碍窗口观察器。
     * 依赖无障碍的 app_open 触发已随设备控制栈一并移除（源码公开版不含无障碍服务）。
     */
    fun ensureAppObserver() {
        // no-op（无障碍采集/设备控制已从公开源码剔除）
    }
}

/**
 * 事件触发的入口。
 *
 * 走 `goAsync()` + IO 协程：执行链路里有数据库与（可能的）模型调用，
 * 在 `onReceive` 里同步做必然被 ANR 杀掉——这个坑项目的 `AdbShellClient` 已经踩过一次
 * （`Future.get` 跑在主线程被 `OplusAnrThrowable` 强杀）。
 */
class AutomationSignalReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val hit = AutomationSignal.matchBroadcast(intent.action ?: "") ?: return
        // **goAsync() 一次广播只能调一次**：第二次调用返回 null，
        // 于是 pending.finish() 抛 NPE（真机 FATAL：每次开机/解锁广播都把进程打死，
        // 2026-10-22 实测三次）。所以自检与 fire 共用同一个 pending。
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                AutomationSignal.fire(context, hit.first, hit.second)
                // 开机后重发桌面快捷方式清单：动态快捷方式在设备重启后不会自动恢复
                // （只有「钉到桌面」的那种由启动器自己留着）。
                // 用这个 receiver 而不是 BootReceiver：那边是别人的文件，我不改。
                if (hit.first == AutomationSignal.KIND_BOOT) {
                    runCatching {
                        io.github.m0use001.planability.app.AutomationShortcuts.publish(context)
                    }
                }
            } finally {
                pending.finish()
            }
        }
    }
}