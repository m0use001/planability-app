package io.github.m0use001.planability.data

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.concurrent.withLock

/**
 * v19.17 **沙箱访问**（统一入口，UI 只认这个类）。
 *
 * 底层是 [AdbShellClient]——本 App 自己当 ADB 主机连手机的 `adbd`，
 * 不依赖任何第三方提权 App。协议细节见 [AdbShellClient]。
 *
 * ## 为什么状态读取**永不阻塞**
 *
 * 第一次接的时候在文件页图标里直接调 `shell()`，结果整个 App 崩在
 * `NetworkOnMainThreadException`——socket 连接是**实打实的网络 IO**，
 * 绝不能在主线程做。
 *
 * 所以这里分成两个入口：
 * - [state] 只读缓存，**立即返回**，可以随便在重组里调
 * - [refresh] 才真正去连，由 UI 在 `LaunchedEffect` 里触发
 *
 * ## 三态而不是四态
 *
 * 第三方提权方案里最让人困惑的是「服务在跑但没授权」和「服务没跑」
 * 要去两个不同地方处理。现在只剩两件事、都只做一次：开端口、点确认。
 */
object SandboxAccess {

    private const val TAG = "SandboxAccess"

    enum class State {
        /** adbd 没开在 TCP 端口上，连都连不上 */
        NOT_ENABLED,

        /** 端口通了但手机不认这个连接，等「允许调试」确认 */
        NOT_AUTHORIZED,

        /** 可用 */
        READY
    }

    @Volatile
    private var cached: State = State.NOT_ENABLED

    @Volatile
    private var lastCheckedAt: Long = 0L

    /**
     * 首次授权的等待上限。
     *
     * 只有「手机上正在等用户点允许」那一次需要这么久；授权之后连接复用，
     * 一次 `id -u` 是毫秒级的。
     */
    /**
     * 握手 + 授权的总等待上限。
     *
     * **为什么是 180 秒而不是 90 秒**（实测出来的，不是拍脑袋）：
     * 批准「允许 USB 调试吗？」之后，adbd **不是立刻认**的。三次独立实测
     * 从「批准」到「收到 CNXN」分别是 **60s / 66s / ~60s**（最后一次差一点、
     * 90 秒窗口里只剩 45 秒，直接 HANDSHAKE_TIMEOUT）。看规律像是 ColorOS 按
     * **60 秒周期**把 framework 那份已授权名单同步给 adbd。
     *
     * 于是总时间 = 用户点弹框的时间（0~60s）+ 同步周期（~60s）+ 余量，
     * 取 180 秒比较稳。等的这阵子 UI 只是显示上一次的状态，不会卡住。
     */
    private const val AUTH_WAIT_MS = 180_000

    /** 状态多久算过期（毫秒）。太短会频繁握手，太长会反应迟钝。 */
    private const val STALE_MS = 5_000L

    /** 只读缓存，不阻塞。可以在重组里直接调。 */
    fun state(): State = cached

    fun isReady(): Boolean = cached == State.READY

    /** 列目录。null = 读不到（不是「空目录」，UI 要分开显示）。 */
    fun listDir(path: String): List<String>? = AdbShellClient.listDir(path)

    fun readText(path: String, maxBytes: Long = 2L * 1024 * 1024): String? =
        AdbShellClient.readText(path, maxBytes)

    /**
     * **给后台/自动化用的特权通道**（v19.44，回应 phone-c 的 assign）。
     *
     * 为什么必须从这里走、而不是直接调 `AdbShellClient.shell()`：
     *
     * 1. **串行化**。`AdbShellClient` 内部是单线程池，但**握手（refresh）和命令走的是
     *    同一条连接**：后台跑一条命令时如果正好撞上用户在文件页触发的 refresh，
     *    两边会互相把对方的连接搞坏 —— 而且 adbd 那边的代价是「再来一次
     *    授权弹窗 + 等 30~60 秒」。这里用一把闸门把两者排成队。
     * 2. **一次只跑一条**。adbd 只服务单客户端，并发没有收益、只有副作用。
     *
     * 想清楚失败代价再调用：**命令失败（超时）会关掉整条共享连接**
     * （`AdbShellClient.closeQuietly()`，因为超时后流的游标已经花了），
     * 下一次用要重新握手。所以自动化这边只该跑**瞬时完成**的命令
     * （`svc wifi enable` 这种），别拿它跑长任务。
     *
     * @return stdout；null = 没连上 / 没授权 / 超时（与 `AdbShellClient.shell` 一致）
     */
    suspend fun shell(command: String, timeoutMs: Int = 20_000): String? =
        withContext(Dispatchers.IO) {
            cmdGate.withLock { AdbShellClient.shell(command, timeoutMs) }
        }

    /**
     * 真连一次 adbd 来确认状态。**必须在协程里调**（内部会切到 IO 线程）。
     *
     * 超时给得很长（[AUTH_WAIT_MS]）是有原因的：首次连接时手机会弹
     * 「允许 USB 调试吗？」，**要等用户点**。我原来只等 6 秒，结果弹窗还在
     * 屏幕上 App 就断开了，用户点「允许」时早已无效——授权不落盘，
     * 下次连接照样重来。等待期间 UI 显示的是上一次的状态，不会卡住。
     */
    /** 正在进行中的 refresh；第二个进来直接返回，避免自己跟自己抢连接。 */
    private val refreshing = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * 特权通道的**命令闸门**：refresh 和后台命令共用一条连接，必须排队。
     * 见 [shell] 的说明。
     */
    private val cmdGate = java.util.concurrent.locks.ReentrantLock()

    /**
     * 失败后的冷却截止时刻。
     *
     * **弹窗刷屏的根因就在这里。** 每次连接尝试都会在手机上留下一个
     * 「允许 USB 调试吗？」；而 adbd 只服务单 host，**每开一条新 TCP 连接
     * 就是一次全新挑战**。原来进文件页就自动 refresh()、失败还重试一次，
     * 两者相乘就是弹窗一个接一个——用户反馈「严重干扰开发」。
     *
     * 所以失败后进入冷却，冷却期内一律不再重连；只有用户在设置页明确
     * 按「重新检测」（force=true）才再试。
     */
    @Volatile
    private var cooldownUntil: Long = 0L

    /** 冷却期长度。3 分钟足够用户看完结果、决定要不要手动重试。 */
    private const val COOLDOWN_MS = 180_000L

    /** 冷却还剩多少毫秒（0 = 不在冷却）。设置页用来提示用户。 */
    fun cooldownLeftMs(): Long =
        (cooldownUntil - System.currentTimeMillis()).coerceAtLeast(0L)

    /**
     * **只在「从来没判过一次状态」时**真连一次。
     *
     * 状态初值是 [State.NOT_ENABLED]，如果永远不主动连一次，用户打开设置页
     * 看到的永远是「尚未开启调试端口」，按���也会跳开发者选项，没法重试。
     *
     * 但也不能每次进页面都连：一次连接就可能换来一个授权弹窗。这里取折中——
     * **首次**进页面连一次（最多一个弹窗），之后一律靠按钮由用户发起。
     */
    suspend fun refreshIfFirstTime() {
        if (lastCheckedAt == 0L) refresh()
    }

    /**
     * @param force 用户显式点了「重新检测」——绕过冷却。
     */
    suspend fun refresh(force: Boolean = false) {
        val left = cooldownLeftMs()
        if (!force && left > 0) {
            Log.i(TAG, "冷却中（还剩 ${left / 1000}s），不重连，避免弹窗刷屏")
            return
        }
        // 单飞：refresh 自己是 90s 的长操作，同时跑两个等于抢同一条 adb 连接。
        if (!refreshing.compareAndSet(false, true)) return
        try {
            doRefresh()
        } finally {
            refreshing.set(false)
        }
    }

    private suspend fun doRefresh() {
        // **必须 withContext(IO)**：LaunchedEffect 默认跑在主线程上，
        // 而 AdbShellClient.shell() 内部是 Future.get() ——会**阻塞调用线程**。
        // 我漏了这一层，结果文件页一进来就 ANR：
        //   OplusAnrThrowable: FutureTask.get → AdbShellClient.shell:143
        //   ActivityTaskManager: Force finishing activity .LauncherDark
        // 「AdbShellClient 内部已经切线程了」是不够的——切的是干活的线程，
        // 等结果的还是调用方。
        // **只试一次，不重试。** 重试 = 新 TCP 连接 = 手机上新弹窗，
        // 「点了无数次还是等待手机确认」多半就是这么来的：
        // 前一次授权还没落盘，后一次连接又来催一遍。
        val r = withContext(Dispatchers.IO) {
            // 与后台命令**同一把闸门**：refresh 最长要等 180 秒（等用户点授权），
            // 期间绝不能让自动化的命令挤进同一条连接 —— 那会把正在等的握手作废，
            // 代价是用户再点一次弹窗、再等一分钟。
            cmdGate.withLock { AdbShellClient.shell("id -u", AUTH_WAIT_MS)?.trim() }
        }
        Log.i(TAG, "refresh(): id -u 原始结果=${r ?: "null"} " +
            "（失败归因=${AdbShellClient.lastFailure()}）")
        // 判定用**失败当场记下的原因**，不另开连接去探测。
        // 旧写法 `if (canConnect()) ...` 会另建一个 socket，而 adbd 只服务单 host，
        // 第二个必被拒——于是「端口开着、只是在等授权」被误报成「端口没开」，
        // 用户看到的一直是「尚未开启调试端口」，尽管 /proc/net/tcp6 里连接好好的。
        val s = when {
            r == "2000" -> State.READY
            r == null -> when (AdbShellClient.lastFailure()) {
                AdbShellClient.Failure.PORT_CLOSED -> State.NOT_ENABLED
                else -> State.NOT_AUTHORIZED
            }
            else -> State.NOT_AUTHORIZED
        }
        if (s != cached) {
            Log.i(TAG, "状态 $cached -> $s（id -u = ${r ?: "null"}）")
            cached = s
        }
        // 没就绪就进冷却：3 分钟内不再自动重连，弹窗不会刷屏。
        cooldownUntil = if (s == State.READY) 0L
        else System.currentTimeMillis() + COOLDOWN_MS
        lastCheckedAt = System.currentTimeMillis()
    }


    /**
     * 无法用 intent 直接跳到无线调试那一页——实测
     * `android.settings.WIFI_DEBUGGING_SETTINGS` 在本机**不可解析**
     * （`am start` 报 unable to resolve），它不是公开 action。
     */
    fun openDeveloperOptions() {
        try {
            Graph.appContext.startActivity(
                android.content.Intent(
                    android.provider.Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS
                ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (_: Throwable) {
        }
    }

    // canConnect() 已删除：它另开一个 socket 探测，adbd 只服务单 host、
    // 第二个被拒，导致把「没授权」误报成「没开端口」。
    // 现在改由 AdbShellClient.lastFailure() 提供**失败当场**的原因。
}