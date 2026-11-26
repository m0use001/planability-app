package io.github.m0use001.planability

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.fragment.app.FragmentActivity
import io.github.m0use001.planability.app.AppShell
import io.github.m0use001.planability.app.Router
import io.github.m0use001.planability.core.AppTime
import io.github.m0use001.planability.core.HapticEngine
import io.github.m0use001.planability.core.LocalHaptics
import io.github.m0use001.planability.data.Graph
import io.github.m0use001.planability.ui.profile.AppLockGate
import io.github.m0use001.planability.ui.theme.PlanabilityTheme

/** FragmentActivity：BiometricPrompt 需要 */
class MainActivity : FragmentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 平板适配（2026-11-16）：manifest 已不再锁竖屏（那是全设备生效的，没法按屏幕尺寸分叉），
        // 改为这里给手机补回竖屏锁。
        //
        // 2026-11-18 用户：「在一些小平板上又会被识别成手机然后强制竖屏」——
        // 阈值原来取 sw<600，但 7~8 吋小平板的 smallestScreenWidthDp 常见 533~599
        // （800×1280 @1.5x = 533dp），正好掉进这条线里被当成手机锁死竖屏。
        // 手机的短边基本都 ≤480dp（主流 360/393/412/430），480 以上那是「大手机」，
        // 让它自由旋转、布局按**窗口宽度**自适应（core/WindowSize.kt 逐窗口读 Configuration），
        // 比按设备短边一刀切更贴近事实。
        // 折叠屏外屏 sw≈320 → 仍按手机锁竖屏 ✓；展开后 sw≥600 → 自由 ✓。
        if (resources.configuration.smallestScreenWidthDp < PHONE_MAX_SHORT_SIDE_DP) {
            requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
        enableEdgeToEdge()
        // 2026-10-29 用户「去除 ColorOS 套的开屏」：系统开屏视图在首帧就绪后立即移除，
        // 不播默认的图标缩放/淡出退场——配合透明图标位（values-v31 themes），
        // 观感是「点开 → 我们自己的 LaunchOverlay」，中间没有系统大图标那一下
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            splashScreen.setOnExitAnimationListener { it.remove() }
        }
        // 关闭平台级触感（系统强度、无法缩放），应用内触感统一由 HapticEngine 按全局强度产生
        window.decorView.isHapticFeedbackEnabled = false
        // 高刷屏适配：给本窗口设刷新率偏好，120Hz 屏上才有 120fps 的 VSYNC 可用。
        // 为什么这么写、以及"设备没开高刷会不会更糟"的退化分析见 preferHighRefreshRate()
        preferHighRefreshRate()
        handleShortcut(intent)
        // v43 调试钩子（仅 debug）：`am start -a io.github.m0use001.planability.DEBUG_TICK` 触发一次自主循环。
        // 用途：验证「任务落库 → tick → 回收」链路，不必等 15 分钟周期任务。release 包里不生效。
        if (BuildConfig.DEBUG && intent?.action == "io.github.m0use001.planability.DEBUG_TICK") {
            android.util.Log.i("AutoLoop", "DEBUG_TICK(onCreate) 触发")
            lifecycleScope.launch {
                runCatching { io.github.m0use001.planability.ai.auto.AutoLoop.tick() }
                    .onFailure { android.util.Log.e("AutoLoop", "DEBUG_TICK(onCreate) tick 失败", it) }
            }
        }
        // v45 调试钩子（仅 debug）：`am start -n pkg/.MainActivity -a io.github.m0use001.planability.DEBUG_MEMORY`
        // 同步跑一轮记忆整理并打一行结果。验证四态/场景/画像链路时不需要等真假聊天。
        if (BuildConfig.DEBUG && intent?.action == "io.github.m0use001.planability.DEBUG_MEMORY") {
            lifecycleScope.launch {
                val r = runCatching { io.github.m0use001.planability.ai.MemoryConsolidationScheduler.debugRunOnce() }
                android.util.Log.i("MemoryDebug", "结果：${r.getOrNull()} / ${r.exceptionOrNull()?.message}")
            }
        }
        // v45 调试钩子（仅 debug）：`-a io.github.m0use001.planability.DEBUG_PERSONA` 强制重建一次画像。
        // 画像默认每 50 次整理才触发；验证生成链路（场景+高优先级记忆 → 四段式画像）用这个。
        if (BuildConfig.DEBUG && intent?.action == "io.github.m0use001.planability.DEBUG_PERSONA") {
            lifecycleScope.launch {
                val r = runCatching { io.github.m0use001.planability.ai.PersonaBuilder.rebuild() }
                android.util.Log.i("MemoryDebug", "画像：$r / ${r.exceptionOrNull()?.message}")
            }
        }
        // v45 调试钩子（仅 debug）：`am start -a io.github.m0use001.planability.DEBUG_PURIFY` 跑一遍群提纯。
        // 用途：提纯的唯一入口要先录屏 + 跳微信滚屏，想单独验证"提纯链路"（worker 回退/空结果/会话清理）时用。
        if (BuildConfig.DEBUG && intent?.action == "io.github.m0use001.planability.DEBUG_PURIFY") {
            lifecycleScope.launch {
                // 采集引擎在设备功能包里，经调试钩子槽位调（公开版 null=静默）
                val r = runCatching { io.github.m0use001.planability.core.DeviceFeatures.debugHooks.purifyOnce?.invoke() }
                android.util.Log.i("PurifyDebug", "结果：${r.getOrNull()} / ${r.exceptionOrNull()?.message}")
            }
        }
        // 2026-11-04 调试钩子（仅 debug）：`am start -a io.github.m0use001.planability.DEBUG_STREAM`
        // 免模型跑一段假流式（与真实流式同一 UI 通路），用于验证聊天滚动跟随/冻结语义。
        // 创建完直接 Router.openSession 打开会话（免 UI 导航）。
        if (BuildConfig.DEBUG && intent?.action == "io.github.m0use001.planability.DEBUG_STREAM") {
            lifecycleScope.launch {
                val sid = runCatching { io.github.m0use001.planability.ai.ChatEngine.debugFakeStream() }
                android.util.Log.i("StreamDebug", "session=${sid.getOrNull()} / ${sid.exceptionOrNull()?.message}")
                // v70f：调试钩子也是系统拉起，同 handleShortcut 降级 quick fade
                io.github.m0use001.planability.app.Router.pendingJumpSlide = false
                sid.getOrNull()?.let { io.github.m0use001.planability.app.Router.openSession(it) }
            }
        }
        // 2026-11-09 调试钩子（仅 debug）：`am start -a io.github.m0use001.planability.DEBUG_RESET_CAPTURE`
        // 完整重置信息提取（采集原文/提纯会话/KV/草稿/落盘目录）——免 UI 导航做重置与对账。
        if (BuildConfig.DEBUG && intent?.action == "io.github.m0use001.planability.DEBUG_RESET_CAPTURE") {
            lifecycleScope.launch {
                val r = runCatching { io.github.m0use001.planability.core.DeviceFeatures.debugHooks.resetCapture?.invoke() }
                android.util.Log.i("CaptureDebug", "重置：${r.getOrNull()} / ${r.exceptionOrNull()?.message}")
            }
        }
        // v24：把旧 project_files 表里的内容搬成真实文件（一次性，KV 标记保证不重复）。
        // 必须在 UI 起来之前做：文件列表读的是磁盘，表还没搬完会先显示"空"。
        lifecycleScope.launch {
            io.github.m0use001.planability.data.ProjectFilesMigrator.runOnce()
            runCatching {
                io.github.m0use001.planability.data.ProjectFiles.warmUp()
            }
            // 2026-10-29：旧落点（对话/、<标题__id>/、项目文件/、根上的工作进程）搬进「项目数据」。
            // 放 warmUp 之后：迁移要用项目名缓存解析绑定目录。只搬能对上库的 app 产物，
            // 用户的文件一律不碰（规则见 ◆/planability-project-file-storage.md）。
            runCatching {
                io.github.m0use001.planability.data.ProjectDataMigration.runIfNeeded()
            }
            // 记忆的磁盘镜像补跑：KV 里已有的记忆也要在 项目记忆/ 里现出原形（幂等）
            runCatching {
                // 先清「记忆清零、场景还挂着」的孤儿场景（幂等迁移），再导出——
                // 否则场景.md 会继续输出已失效项目的场景（2026-11-05 用户实报）
                io.github.m0use001.planability.data.Graph.memories.sweepOrphanScenes()
                io.github.m0use001.planability.data.MemoryFiles.exportAll()
            }
            // 四个子目录恒齐全：Syncthing 电脑端与文件库都能立刻看到结构
            runCatching {
                io.github.m0use001.planability.data.ProjectDirs.ensureAll()
            }
        }
        // v36 B 项：进程启动后补跑"被杀前没来得及整理的记忆"。
        // 只在首次创建时跑（旋转屏幕不重复触发）；调度器幂等，重复调也只是多读一次库。
        if (savedInstanceState == null) {
            lifecycleScope.launch {
                io.github.m0use001.planability.ai.MemoryConsolidationScheduler.resumePending()
            }
        }
        setContent {
            val settings by Graph.settings.snapshot.collectAsState()
            // 时区覆盖接线
            AppTime.zoneOverride = settings.zoneOverride
            PlanabilityTheme(config = settings.theme) {
                val haptics = remember {
                    HapticEngine(this) { Graph.settings.snapshot.value.theme.hapticIntensity / 100f }
                }
                // 根背景 = 与左侧 NavigationRail **同一个色**（colorScheme.surface）。
                // 2026-11-20 用户指令：「背景不要再渐变了，改成跟左边菜单栏同一个颜色」——
                // 暗色原先是 ColorOS 同款垂直渐变（#2B2C31→#151518），与 Rail 的 surface
                // 不是同一个色，宽屏上 Rail 与内容区之间出现色阶接缝。
                // 明暗都统一走 surface：浅色下 background 与 surface 只差 1% 明度，
                // 但既然要求「同一个颜色」，就别留这条差。
                // Surface 用透明色 + 显式 contentColor，避免 contentColorFor(透明) 得到 Unspecified
                androidx.compose.material3.Surface(
                    color = androidx.compose.ui.graphics.Color.Transparent,
                    contentColor = MaterialTheme.colorScheme.onBackground
                ) {
                    androidx.compose.foundation.layout.Box(
                        Modifier
                            .fillMaxSize()
                            .background(androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.surface))
                    ) {
                    androidx.compose.runtime.CompositionLocalProvider(
                        LocalHaptics provides haptics,
                        androidx.compose.ui.platform.LocalHapticFeedback provides
                            io.github.m0use001.planability.core.HapticEngineFeedback(haptics)
                    ) {
                        var unlocked by remember { mutableStateOf(!settings.lockEnabled) }
                        androidx.compose.foundation.layout.Box {
                            if (!unlocked) {
                                AppLockGate(
                                    pin = settings.lockPin,
                                    biometric = settings.lockBiometric,
                                    onUnlocked = { unlocked = true }
                                )
                            } else {
                                AppShell()
                            }
                            // 自定义开屏动画（launch.anim 设置，点按可跳过）：
                            // 盖在锁屏/主内容之上的最上层——冷启动组合时播一次，
                            // 热启动不重建 Activity 天然不重播
                            io.github.m0use001.planability.ui.common.LaunchOverlay()
                        }
                    }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShortcut(intent)
        // 2026-11-04：DEBUG_STREAM 前台也能补触发（见 onCreate 同名分支）
        if (BuildConfig.DEBUG && intent.action == "io.github.m0use001.planability.DEBUG_STREAM") {
            lifecycleScope.launch {
                val sid = runCatching { io.github.m0use001.planability.ai.ChatEngine.debugFakeStream() }
                android.util.Log.i("StreamDebug", "session=${sid.getOrNull()} / ${sid.exceptionOrNull()?.message}")
                // v70f：调试钩子也是系统拉起，同 handleShortcut 降级 quick fade
                io.github.m0use001.planability.app.Router.pendingJumpSlide = false
                sid.getOrNull()?.let { io.github.m0use001.planability.app.Router.openSession(it) }
            }
        }
        // v43 调试钩子（仅 debug）：应用已在前台时也能重复触发自主循环
        if (BuildConfig.DEBUG && intent.action == "io.github.m0use001.planability.DEBUG_TICK") {
            android.util.Log.i("AutoLoop", "DEBUG_TICK(onNewIntent) 触发")
            lifecycleScope.launch {
                runCatching { io.github.m0use001.planability.ai.auto.AutoLoop.tick() }
                    .onFailure { android.util.Log.e("AutoLoop", "DEBUG_TICK(onNewIntent) tick 失败", it) }
            }
        }
    }

    /**
     * v36 B 项：应用切到后台就告诉记忆整理调度器"可以整理了"。
     * ColorOS 上这条最关键 —— 空闲计时在后台不一定走得完，进程还可能被回收；
     * 进度由 memory_progress 表兜着，下次启动照样补跑。
     *
     * 2026-10-20：切后台同时做一次**整库快照**到公共目录（AutoBackup，30 分钟节流）。
     * 防的是"卸载重装/装错变体把 App 数据清空"——filesDir 会随卸载消失，
     * 而 Download/Planability/ 不会（恢复方式见 tools/device-guard）。
     */
    override fun onStop() {
        super.onStop()
        io.github.m0use001.planability.ai.MemoryConsolidationScheduler.onAppHidden()
        Graph.appScope.launch { io.github.m0use001.planability.data.AutoBackup.snapshot() }
    }

    private fun handleShortcut(intent: Intent?) {
        // v70f：系统深链（快捷方式/小组件/自动化图标/分享/通知）统一降级为 quick fade——
        // 不是用户在本 app 里点跳转按钮（Router.pendingJumpSlide 默认 true=纵滑抽纸条），
        // 冷启动/后台拉起横滑会像翻书（v68 拍板保留）。NavShell 消费后自动归位。
        Router.pendingJumpSlide = false
        when (intent?.action) {
            ACTION_NEW_TODO -> Router.openTodo("plan", 0L)
            ACTION_NEW_COUNTDOWN -> Router.openCountdownManager()
            ACTION_START_FOCUS -> Router.startFocus()
            // 小组件分区深链：widget/WidgetCommon.clickOpenApp 会把目标 tab 塞进 extra。
            // 纯新增分支，不影响上面几个既有入口（不带 extra 时根本不会走到这里）。
            Router.ACTION_WIDGET_TAB -> {
                val tab = intent.getStringExtra(Router.EXTRA_TAB)
                val sub = intent.getStringExtra(Router.EXTRA_PLAN_SUB)
                // 2026-11-01：页内覆盖层直达（倒数日管理 / 月历）。
                // 覆盖层标志在 HomeScreen 消费，消费 effect 依赖 currentTab 已是 "home"，
                // 所以这里单独把 targetTab 定到 home（不等 tab 分支）。
                val open = intent.getStringExtra(Router.EXTRA_WIDGET_OPEN)
                when (tab) {
                    "plan" -> Router.openPlan(sub)
                    null -> Unit
                    else -> Router.targetTab = tab
                }
                when (open) {
                    Router.WIDGET_OPEN_COUNTDOWN -> {
                        Router.targetTab = "home"
                        Router.pendingWidgetCountdown = true
                    }
                    Router.WIDGET_OPEN_CALENDAR -> {
                        Router.targetTab = "home"
                        Router.pendingWidgetCalendar = true
                    }
                }
            }
            // v19.43：桌面图标点进来 → 直接跑那一条指令。
            // 走 AutomationShortcuts.runById 是为了让它和 UI 的「运行」按钮、
            // AI 的 automation_run 共用同一个执行入口（执行记录与通知因此一致）。
            io.github.m0use001.planability.app.AutomationShortcuts.ACTION_RUN -> {
                val id = intent.getLongExtra(
                    io.github.m0use001.planability.app.AutomationShortcuts.EXTRA_AUTOMATION_ID, 0L
                )
                // 先切到自动化页：跑的是哪一条、执行成没成，用户要能看见
                Router.openAutomations()
                lifecycleScope.launch {
                    io.github.m0use001.planability.app.AutomationShortcuts
                        .runById(this@MainActivity, id)
                }
            }
            Intent.ACTION_SEND -> {
                if (intent.type?.startsWith("text/") == true) {
                    val text = intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty().trim()
                    if (text.isNotBlank()) Router.openSharedToChat(text)
                }
            }
            // 系统通知点击 → 跳到对应界面（目标值与 notify/Notify.kt 的 ROUTE_* 一一对应）
            ACTION_NOTIFICATION -> {
                val route = intent.getStringExtra(EXTRA_ROUTE) ?: return
                val targetId = intent.getLongExtra(EXTRA_TARGET_ID, 0L)
                when (route) {
                    "todo" -> Router.openTodo("plan", targetId)
                    "schedule" -> Router.openSchedule("plan", targetId)
                    "focus" -> Router.targetTab = "focus"
                    "chat" -> Router.targetTab = "info"
                    "automation" -> Router.openAutomations()
                    "digest" -> Router.openGroupDigest()
                }
            }
        }
    }

    /**
     * 请求让本窗口跑在高刷新率上（120Hz 屏 → 120fps 的 VSYNC，Compose 动画才不会被 VSYNC 卡在 60）。
     * 在 onCreate 里设一次即可：这个偏好挂在 Window 的 LayoutParams 上，窗口活着就一直生效，
     * onResume 不会被系统清掉，没必要反复设。
     *
     * 为什么用 window.attributes.preferredRefreshRate：
     *  - 它是"整窗的刷新率偏好"，系统按窗口合并决策，设一次对整窗生效、零依赖；
     *  - 官方文档明确：只想要更高刷新率时应优先用它而非 preferredDisplayModeId——后者把
     *    分辨率等其它 mode 属性一起锁死，为了 120Hz 把渲染分辨率也切掉会更糊、更耗电；
     *  - 文档也说明它等价于 Surface.setFrameRate(rate, DEFAULT, CHANGE_FRAME_RATE_ONLY_IF_SEAMLESS)，
     *    即"只在能无缝切换时才提"；Surface.setFrameRate 要逐 Surface 声明，View/Compose 体系
     *    没有统一挂点，窗口级这一处就够了。
     *
     * 兼容性：preferredRefreshRate 自 API 1 就有，Display.getMode()/getSupportedModes() 是 API 23，
     * Context.getDisplay() 是 API 30——本项目 minSdk = 36（app/build.gradle.kts），不需要版本分支。
     *
     * 设备没开高刷时会不会更糟？不会，这里给的只是"偏好"，平台可以不采纳，最坏是维持原样：
     *  - 屏幕最高档本来就是 60：want <= 当前值，直接 return，行为与不设完全一致；
     *  - 用户在系统里把刷新率上限压到 60（AOSP 的 peak_refresh_rate 设置，如「标准刷新率」）：
     *    读到 60 就把 want 压回 60、同样不设——尊重省电选择，不因我们多耗电；
     *  - 当前已经跑在高档（自适应屏正处 120）：也不设，避免任何降档可能；
     *  - 省电模式把刷新率压住：平台的强制策略优先于 App 偏好，压住就是维持 60，同样不会更糟；
     *  - 读不到 peak_refresh_rate（OEM 没走 AOSP 这个 key）：按屏幕最高档请求，是否采纳由
     *    平台/OEM 的按 App 限帧策略决定，被无视就是照旧，不会崩也不会变卡。
     * 采纳后的唯一代价是前台更耗电，那是"跑满刷新率"本身要付的钱，不是退化。
     *
     * 边界：FloatChatActivity 是另一个 Window，这里管不到；它没设自己的偏好，跟随平台默认
     * 策略（主窗口把整屏刷新率拉起来时它一并跑在高档）。
     */
    private fun preferHighRefreshRate() {
        // display 是 Context.getDisplay()（API 30）：当前窗口所在显示屏，折叠屏内外屏各算各的
        val disp = display ?: return
        val now = disp.mode.refreshRate
        val max = disp.supportedModes.maxOfOrNull { it.refreshRate } ?: return
        // 用户设的刷新率上限（AOSP key 是字符串常量，读不到就当"没有上限"）
        val peak = Settings.System.getString(contentResolver, "peak_refresh_rate")
            ?.toFloatOrNull()
            ?.takeIf { it > 0f }
        val want = minOf(max, peak ?: max)
        if (want <= now) return // 已经够高，或用户要求更低：不主动往低设
        window.attributes = window.attributes.apply { preferredRefreshRate = want }
    }

    companion object {
        const val ACTION_NEW_TODO = "io.github.m0use001.planability.action.NEW_TODO"
        const val ACTION_NEW_COUNTDOWN = "io.github.m0use001.planability.action.NEW_COUNTDOWN"
        const val ACTION_START_FOCUS = "io.github.m0use001.planability.action.START_FOCUS"

        /**
         * 判定「这是手机」的**短边上限**（dp）。
         *
         * 为什么不是 600：600 是 sw600dp（平板资源限定符）的门槛，但 7~8 吋小平板的
         * smallestScreenWidthDp 常见 533~599（800×1280 @1.5x = 533dp），拿 600 当线
         * 会把小平板判成手机 → 强制竖屏（用户 2026-11-18 实机反馈）。
         * 手机短边主流是 320~430dp，480 是「超大手机」的上限，
         * 取 480：真手机仍锁竖屏（零行为变化），小平板自由旋转。
         */
        const val PHONE_MAX_SHORT_SIDE_DP = 480

        /**
         * 系统通知点击专用。**必须与快捷方式那几个 action 分开**：
         * 落地页走 EXTRA_ROUTE/EXTRA_TARGET_ID 两个 extra，而快捷方式是无参的。
         */
        const val ACTION_NOTIFICATION = "io.github.m0use001.planability.action.NOTIFICATION"
        const val EXTRA_ROUTE = "planability.route"
        const val EXTRA_TARGET_ID = "planability.routeTargetId"
    }
}
