package io.github.m0use001.planability.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.m0use001.planability.core.DeviceFeatures
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

    // ---- 「打开应用时」：复用无障碍服务已经在跑的窗口监听 ----

    @Volatile
    private var appObserverStarted = false

    /**
     * 进程级作用域：观察器要活过单次调用，挂在某个 Activity 的 scope 上会在页面销毁时被取消，
     * 而「打开应用时」这条触发恰恰要在用户**没打开 App** 时也能响。
     */
    private val scope = CoroutineScope(Dispatchers.Default)

    /**
     * 拉起 app_open 的观察器。**幂等**，三个地方都调它（谁先到谁拉起）：
     * 15 分钟巡检（保证后台也会醒）、自动化列表页（用户进来看见它已就绪）。
     *
     * 为什么不写在 `Application.onCreate` 里：那是 app 入口、所有人的文件，
     * 按 AGENTS.md 我不该先斩后奏。代价写在这里：进程被系统杀掉后，
     * 最坏要等下一次巡检（≤15 分钟）或用户打开自动化页，这条触发才恢复。
     */
    fun ensureAppObserver() {
        if (appObserverStarted) return
        appObserverStarted = true
        scope.launch {
            var lastPkg = ""
            while (isActive) {
                // 2026-11-26 开源根治：读屏通道经 DeviceFeatures.a11y（公开版 NoOp=一直等）
                val a11y = DeviceFeatures.a11y
                if (!a11y.isBound) {
                    // 无障碍还没开（或已关）：等着。开着之后自动接上，不需要用户再操作一次。
                    delay(5_000)
                    continue
                }
                a11y.windowEvents.collect { pkg ->
                    // 同一应用里弹对话框也会发窗口切换，按包名去重比按事件去重准
                    if (pkg.isNotBlank() && pkg != lastPkg) {
                        lastPkg = pkg
                        // **必须是 collect 而不是 collectLatest**：后者一到新事件就取消上一段，
                        // 于是「切得快一点」会把正在跑的那条自动化掐在半路——
                        // 而它的表现是执行记录里一条 ok=false，理由看起来毫不相干。
                        // 这里的代价是事件会排队（缓冲 16，满则丢弃，不阻塞无障碍线程），
                        // 对「打开某应用时」这种低频触发完全够用。
                        runCatching { fire(Graph.appContext, KIND_APP_OPEN, pkg) }
                    }
                }
            }
        }
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
                // 解锁/开机是 ColorOS 静默关无障碍后的最早可见时机（它就是在重启/长休眠后关的），
                // 顺路查一次自检——内部自带节流，一次事故只通知一条。放在 fire 之前：
                // 就算这条广播没有匹配任何自动化，自检也必须跑。
                if (hit.first == AutomationSignal.KIND_BOOT || hit.first == AutomationSignal.KIND_UNLOCK) {
                    runCatching { A11yWatch.checkAndNotify(context) }
                }
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