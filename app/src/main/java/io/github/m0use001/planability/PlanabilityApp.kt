package io.github.m0use001.planability

import android.app.Activity
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.WallpaperManager
import android.content.ComponentCallbacks2
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Bundle
import androidx.compose.ui.graphics.Color
import io.github.m0use001.planability.data.Graph
import io.github.m0use001.planability.data.ThemeConfig
import io.github.m0use001.planability.ui.theme.COLOR_SEEDS
import io.github.m0use001.planability.ui.theme.FIXED_STYLE_COUNT
import io.github.m0use001.planability.ui.theme.SeedColors
import io.github.m0use001.planability.widget.WidgetDailyRefresh
import io.github.m0use001.planability.widget.WidgetRefresh
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class PlanabilityApp : Application() {

    /** 当前前台 Activity（onPause 即清，不持过期引用；壁纸变化 recreate 用，见 onCreate） */
    private var resumedActivity: Activity? = null

    override fun onCreate() {
        super.onCreate()
        Graph.init(this)
        // 公式位图磁盘缓存（2026-11-21）：渲过一次就落盘，重进会话/滚回原处不再重算。
        // 只给目录，读写在渲染线程内部完成（组合期不碰磁盘）。
        io.github.m0use001.planability.ui.notes.LatexRender.initDisk(cacheDir)
        // PDFBox-Android 首次使用前要加载内置资源（字体/字形表），否则抽 PDF 文字层时
        // 可能抛 "PDFBoxResourceLoader not initialized"。只抽文字也用得到，这里是唯一初始化点。
        runCatching { com.tom_roush.pdfbox.android.PDFBoxResourceLoader.init(this) }
        val nm = getSystemService(NotificationManager::class.java)
        // 提醒渠道不使用系统默认震动（强度不可控）；由 ReminderReceiver 按全局强度显式震动
        nm.deleteNotificationChannel("reminders")
        nm.createNotificationChannel(
            NotificationChannel("reminders", "日程与待办提醒", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "日程/待办到点提醒"
                enableVibration(false)
                setVibrationPattern(null)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel("reminders", "日程与待办提醒", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "日程/待办到点提醒"
            }
        )
        // 前台服务渠道。StreamKeepService（AI 流式保活）与 CaptureProjectionService（录屏采集）
        // 都用 "focus" 这个 id，但此前**只建了 reminders** —— 于是 API 34+ 上
        // startForeground() 拿到一个不存在的渠道，直接抛
        // CannotPostForegroundServiceNotificationException: Bad notification for startForeground，
        // 进程当场被杀。表现就是「一发消息就闪退、assistant 消息永远停在 streaming」
        // （2026-10-19 真机抓到，logcat -b crash 里连续两条）。
        // 用 IMPORTANCE_MIN：前台服务只需要"活着"，不该弹出来打扰。
        if (nm.getNotificationChannel("focus") == null) {
            nm.createNotificationChannel(
                NotificationChannel("focus", "前台保活", NotificationManager.IMPORTANCE_MIN).apply {
                    description = "AI 生成/录屏期间保持进程存活，不打扰"
                    setShowBadge(false)
                }
            )
        }
        // 一次性迁移（2026-10-21 事故后）：日程/待办与项目解耦。
        // 事故复盘：课表导入把 13 条课程挂在了一个测试项目下，用户删项目 → 整张课表消失。
        // 日历数据属于用户，不属于任何容器；这里把历史数据里的 projectId 统一清零。
        Graph.appScope.launch {
            runCatching {
                if (Graph.settings.raw("mig.plan.global") != "1") {
                    Graph.db.scheduleDao().detachAllProjects()
                    Graph.db.todoDao().detachAllProjects()
                    Graph.settings.putRawValue("mig.plan.global", "1")
                    Graph.dataVersion.bump()
                }
                // 存量地点补登记：编辑器里选过的地点要出现在「分类管理 → 地点」
                if (Graph.settings.raw("mig.plan.places") != "1") {
                    Graph.plan.backfillPlaceCategories()
                    Graph.settings.putRawValue("mig.plan.places", "1")
                }
                if (Graph.settings.raw("mig.plan.places.clean") != "1") {
                    Graph.plan.dropPlaceholderPlaces()
                    Graph.settings.putRawValue("mig.plan.places.clean", "1")
                }
                // v41：老数据的绑定名回填（地图选过的地名本来就在 place 里）
                if (Graph.settings.raw("mig.plan.placepoi") != "1") {
                    Graph.db.scheduleDao().backfillPlacePoi()
                    Graph.db.todoDao().backfillPlacePoi()
                    Graph.settings.putRawValue("mig.plan.placepoi", "1")
                    Graph.dataVersion.bump()
                }
                // 2026-10-31：handoff 泄漏修数据——历史 rewriteHandoff 没带 projectId，
                // 各项目的「当前状态+下一步」都写成了同一条全局记忆（用户实锤：Python 编程
                // 的交接出现在全局记忆里）。来源会话仍在且属于某项目的，归回那个项目；
                // 来源会话已删的保持原样（无从判断归属，宁可不动）。
                if (Graph.settings.raw("mig.memory.handoff.project") != "1") {
                    val adopted = Graph.db.memoryDao().adoptOrphanHandoffs()
                    Graph.settings.putRawValue("mig.memory.handoff.project", "1")
                    if (adopted > 0) Graph.dataVersion.bump()
                }
            }
        }
        // 数据变更 → 刷新桌面小组件
        Graph.appScope.launch {
            Graph.dataVersion.flow.collectLatest { refreshWidgets() }
        }
        // 外观变更 → 也要刷新桌面小组件。
        // 为什么单列一条：`SettingsRepo.update` 只写库、**不 bump `dataVersion`**，
        // 所以用户在「外观 → 主题色」换色时，应用内立刻变、桌面组件却纹丝不动，
        // 要等到下次数据变更或次日 00:02 才换色（真机确认过）。
        // 只盯 colorSource/seed/mode/wallpaperVariant 四个字段：其余设置变化与组件无关，
        // 不该白刷六次。（colorSource/wallpaperVariant 是拼色配色的新增维度：
        // 0=拼色+方案序号，1=自选种子；任一变化都要重取色板。）
        Graph.appScope.launch {
            var last: List<Int>? = null
            Graph.settings.snapshot
                .map { listOf(it.theme.colorSource, it.theme.seed, it.theme.mode, it.theme.wallpaperVariant) }
                .distinctUntilChanged()
                .collectLatest { cur ->
                    // StateFlow 会先发一次初值，那一次不是"变化"，跳过
                    if (last != null && last != cur) {
                        refreshWidgets()
                        // 配色变了 → 桌面图标也要跟着换那一档（同一份配色矩阵）
                        runCatching { LauncherNightIcon.apply(this@PlanabilityApp) }
                    }
                    last = cur
                }
        }
        // 壁纸取色跟随（theme.colorSource=0，配色提案 C4）：壁纸一变，六张组件与
        // 应用内色板都要跟。壁纸动画可能连发多次回调 → 500ms 防抖合并；回调在
        // binder 线程，动作抛回 appScope（IO），recreate 再 post 到主线程。
        runCatching {
            var lastAt = 0L
            val listener = WallpaperManager.OnColorsChangedListener { _, _ ->
                Graph.appScope.launch {
                    val now = System.currentTimeMillis()
                    if (now - lastAt < 500) return@launch
                    lastAt = now
                    // 种子模式（colorSource=1）不看壁纸，直接短路
                    if (Graph.settings.snapshot.value.theme.colorSource != 0) return@launch
                    refreshWidgets()
                    // 应用内 scheme 只在组合时解析系统色资源，重组不会自发发生 →
                    // 前台 Activity recreate 一次（后台时不必，下次启动自然取新色）
                    resumedActivity?.let { a ->
                        android.os.Handler(android.os.Looper.getMainLooper()).post { a.recreate() }
                    }
                }
            }
            // javap 实查（android-37.0）：监听 API 是 addOnColorsChangedListener；
            // Handler 参数在本 SDK 非空平台类型，传主线程 Handler（回调里再抛回 appScope）
            WallpaperManager.getInstance(this)
                .addOnColorsChangedListener(listener, android.os.Handler(android.os.Looper.getMainLooper()))
        }.onFailure { android.util.Log.w("PlanabilityApp", "壁纸取色监听注册失败", it) }
        // 前台 Activity 追踪：目前唯一用途是上面壁纸变化时的 recreate。
        // ActivityLifecycleCallbacks 在本 compileSdk 下仍是全抽象接口，七个方法都要实现。
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityCreated(a: Activity, s: Bundle?) {}
            override fun onActivityStarted(a: Activity) {}
            override fun onActivityResumed(a: Activity) { resumedActivity = a }
            override fun onActivityPaused(a: Activity) { if (resumedActivity === a) resumedActivity = null }
            override fun onActivityStopped(a: Activity) {}
            override fun onActivitySaveInstanceState(a: Activity, s: Bundle) {}
            override fun onActivityDestroyed(a: Activity) {}
        })
        // 启动补挂：未来 48h 内日程/待办的精确提醒
        Graph.appScope.launch {
            runCatching { io.github.m0use001.planability.notify.ReminderReArm.rearm(this@PlanabilityApp) }
        }
        // v19.14：跨端 dump v1——写到第一个 Syncthing 同步文件夹，PC 端按mtime 拉取
        Graph.appScope.launch {
            delay(1500)
            runCatching { io.github.m0use001.planability.data.PhoneDump.write(this@PlanabilityApp) }
        }
        // v19.61：同步内核随应用启动（pc-a 10-18 assign：此前每次重开 app 内核都不在，
        // 用户得进同步页手动点「启动」，在那之前手机端与电脑端是断的）。
        // 开关存旁路 KV sync.autostart，缺键=开；同步页「此设备」卡可关。
        // start 幂等（isRunning 短路）；延迟 1s 避开冷启动尖峰（与 dump 导出同款做法）。
        Graph.appScope.launch {
            runCatching {
                if (Graph.settings.raw("sync.autostart") != "0") {
                    delay(1000)
                    io.github.m0use001.planability.syncthing.SyncthingEngine.start(this@PlanabilityApp)
                }
            }
        }
        // 端侧小模型（GenieX NPU，方案 设计文档）：
        // KV local.engine=1 才开（缺键=关，与 sync.autostart 相反——端侧引擎吃电，须显式开启）。
        // 独立进程 :localai 起 OpenAI 兼容 server；模型未下载不阻塞，首个请求 503。
        Graph.appScope.launch {
            runCatching {
                if (Graph.settings.raw("local.engine") == "1") {
                    delay(1500)
                    startService(android.content.Intent(this@PlanabilityApp, io.github.m0use001.planability.ai.local.LocalLlmService::class.java))
                }
            }
        }
        // v11：自动化任务巡检闹钟
        io.github.m0use001.planability.notify.AutomationScheduler.ensure(this)
        // v43：常驻自主循环（WorkManager 15 分钟 + 精确闹钟 30 分钟双通道；杀后台不丢任务）
        io.github.m0use001.planability.app.AutoLoopScheduler.ensure(this)
        // 跨零点：每天 00:02 把 6 个小组件重推一次，避免早上看到「昨天的日期」
        WidgetDailyRefresh.schedule(this)
        // v24：地理围栏。没有带位置触发的自动化时这条是空转——不注册监听、不问权限。
        io.github.m0use001.planability.notify.GeoWatch.sync(this)
        Graph.appScope.launch {
            Graph.settings.load()
            // v19.13：恢复专注悬浮胶囊（设置里开着就补挂一次）
            if (Graph.settings.snapshot.value.floatBallEnabled) {
                io.github.m0use001.planability.focus.FloatBall.show(this@PlanabilityApp)
            }
        }
        // 启动时同步一次图标变体（配色 + 系统明暗）。
        // 必须在 load() 之后：那之前 snapshot 还是默认值，会把「经典绿」当成用户的配色。
        Graph.appScope.launch {
            runCatching { LauncherNightIcon.apply(this@PlanabilityApp) }
        }
        registerComponentCallbacks(object : ComponentCallbacks2 {
            override fun onConfigurationChanged(newConfig: Configuration) {
                // 明暗变了：用当前配色重算 alias（读 snapshot，不重新 load）
                val night = LauncherNightIcon.isNightFromCfg(newConfig)
                Graph.appScope.launch {
                    runCatching {
                        LauncherNightIcon.apply(
                            this@PlanabilityApp,
                            Graph.settings.snapshot.value.theme,
                            night
                        )
                    }
                }
                // 系统明暗切换也会改变小组件取色（WidgetTheme 跟随 ThemeConfig.mode=0），
                // 所以配置变化要顺带刷一次桌面，否则组件会一直停在旧模式下
                refreshWidgets()
            }

            override fun onLowMemory() {}

            override fun onTrimMemory(level: Int) {}
        })
    }

    /**
     * 刷新全部桌面小组件。
     *
     * 两处坑都在这一个函数里收口：
     * 1. 各组件的刷新统一走 WidgetRefresh.all，别在别处再私挂一份 updateAll 列表
     *    （历史上 `ShortcutsWidget`（2026-10-29 已删）就因漏在列表外卡过旧卡片）。
     * 2. **只挂数据变更不够**：取色现在跟随 `theme.seed` 与系统明暗，
     *    这两种变化不会 bump `dataVersion`，所以要由配置变化也调一次。
     */
    private fun refreshWidgets() {
        WidgetRefresh.all(this)
    }
}

/**
 * 启动器图标：按「软件内配色 + 系统明暗」启用对应的 activity-alias。
 *
 * ## 为什么是 alias 矩阵
 * 启动器图标是**编译期资源**，运行期没有任何 API 能改一个 app 的图标颜色
 * （`IconCompat` 只对快捷方式有效）。所以做法是：把 12 档配色 × 明暗共 24 份
 * 图标全部预先编译进 APK（由 tools 下的生成脚本从 Theme.kt 的调色板生成），
 * 运行期只 `setComponentEnabledSetting` 打开其中一份、关掉其余。
 *
 * 12 档 = 拼色四套（palette 0..3，与 [io.github.m0use001.planability.ui.theme.FIXED_STYLES] 同序）
 * + 其他颜色八个预设（palette 4..11，与 COLOR_SEEDS 同序）。
 * 预设是有限枚举（外观页只给这八颗），所以矩阵能覆盖全部可选项；
 * 若将来放开成任意取色，就得换方案（快捷方式动态图标），不能只加数字。
 *
 * ## 为什么不留在 Application 里
 * 原来只有 `Application.onCreate` 和 `onConfigurationChanged` 会调它，
 * 等于**只有 app 进程活着时才换**。ColorOS 会在后台杀进程，
 * 杀完之后用户把系统切成暗色，图标就停在旧那套，直到下次手动打开 app。
 *
 * ## 幂等 / 少写
 * 进程级记 `lastComponent`；第一次调用先从 PackageManager 读回「现在开着哪个」，
 * 之后状态没变就一次写都不做。切档时**先启用新的、再禁用旧的**，中间不会出现零图标。
 */
object LauncherNightIcon {

    /** palette 序号：0..3 = 拼色四套，4..11 = 其他颜色八个预设 */
    fun paletteIndexOf(theme: ThemeConfig): Int =
        if (theme.colorSource == 0) {
            theme.wallpaperVariant.coerceIn(0, FIXED_STYLE_COUNT - 1)
        } else {
            FIXED_STYLE_COUNT + nearestSeedIndex(theme.seed)
        }

    /**
     * 老版本的 `theme.seed` 可能是任意色（早期取色器留下的）。外观页现在只给六颗，
     * 所以按**色相最近**归档——至少不会在蓝主题下冒出绿色的桌面图标。
     */
    private fun nearestSeedIndex(seed: Int): Int {
        val h = SeedColors.hueOf(Color(seed))
        var best = 0
        var bestD = -1f
        COLOR_SEEDS.forEachIndexed { i, c ->
            val raw = kotlin.math.abs(h - SeedColors.hueOf(c))
            val d = if (raw > 180f) 360f - raw else raw
            if (bestD < 0f || d < bestD) {
                bestD = d
                best = i
            }
        }
        return best
    }

    private val PALETTE_COUNT get() = FIXED_STYLE_COUNT + COLOR_SEEDS.size

    private fun componentName(palette: Int, night: Boolean): String =
        "io.github.m0use001.planability.LauncherPal$palette${if (night) "Night" else "Day"}"

    private fun defaultComponentName(): String = componentName(0, false)

    private val DAY_ICONS = intArrayOf(
        R.mipmap.ic_launcher_pal0_day, R.mipmap.ic_launcher_pal1_day,
        R.mipmap.ic_launcher_pal2_day, R.mipmap.ic_launcher_pal3_day,
        R.mipmap.ic_launcher_pal4_day, R.mipmap.ic_launcher_pal5_day,
        R.mipmap.ic_launcher_pal6_day, R.mipmap.ic_launcher_pal7_day,
        R.mipmap.ic_launcher_pal8_day, R.mipmap.ic_launcher_pal9_day
    )
    private val NIGHT_ICONS = intArrayOf(
        R.mipmap.ic_launcher_pal0_night, R.mipmap.ic_launcher_pal1_night,
        R.mipmap.ic_launcher_pal2_night, R.mipmap.ic_launcher_pal3_night,
        R.mipmap.ic_launcher_pal4_night, R.mipmap.ic_launcher_pal5_night,
        R.mipmap.ic_launcher_pal6_night, R.mipmap.ic_launcher_pal7_night,
        R.mipmap.ic_launcher_pal8_night, R.mipmap.ic_launcher_pal9_night
    )

    private val DAY_FGS = intArrayOf(
        R.drawable.ic_launcher_fg_pal0_day, R.drawable.ic_launcher_fg_pal1_day,
        R.drawable.ic_launcher_fg_pal2_day, R.drawable.ic_launcher_fg_pal3_day,
        R.drawable.ic_launcher_fg_pal4_day, R.drawable.ic_launcher_fg_pal5_day,
        R.drawable.ic_launcher_fg_pal6_day, R.drawable.ic_launcher_fg_pal7_day,
        R.drawable.ic_launcher_fg_pal8_day, R.drawable.ic_launcher_fg_pal9_day
    )
    private val NIGHT_FGS = intArrayOf(
        R.drawable.ic_launcher_fg_pal0_night, R.drawable.ic_launcher_fg_pal1_night,
        R.drawable.ic_launcher_fg_pal2_night, R.drawable.ic_launcher_fg_pal3_night,
        R.drawable.ic_launcher_fg_pal4_night, R.drawable.ic_launcher_fg_pal5_night,
        R.drawable.ic_launcher_fg_pal6_night, R.drawable.ic_launcher_fg_pal7_night,
        R.drawable.ic_launcher_fg_pal8_night, R.drawable.ic_launcher_fg_pal9_night
    )

    /** 某档配色在某明暗下的 adaptive-icon 资源（应用内预览与桌面用同一份）。 */
    fun iconRes(palette: Int, night: Boolean): Int {
        val icons = if (night) NIGHT_ICONS else DAY_ICONS
        return icons[palette.coerceIn(0, icons.lastIndex)]
    }

    /**
     * 只要**前景**那一层（矢量），给应用内预览用。
     *
     * 预览不能用 [iconRes]：adaptive-icon 不是 VectorDrawable，`painterResource` 会抛
     * "Only VectorDrawables and rasterized asset types are supported"；而把它画成位图时，
     * AdaptiveIconDrawable 内部会按 mask 再放大一层，比例很难和桌面一致。
     * 前景矢量直接画、外面套 108/72 = 1.5 倍，才是桌面看到的比例。
     */
    fun fgRes(palette: Int, night: Boolean): Int {
        val icons = if (night) NIGHT_FGS else DAY_FGS
        return icons[palette.coerceIn(0, icons.lastIndex)]
    }

    /** 判断当前是否系统深色模式（配置里只有 uiMode 一个来源） */
    fun isNightFromCfg(config: Configuration): Boolean =
        (config.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    fun isNight(context: Context): Boolean = isNightFromCfg(context.resources.configuration)

    private var lastComponent: String? = null

    /** 读当前设置里的配色，跟系统明暗一起算；开机广播 / 巡检走这条（会补读一次设置）。 */
    suspend fun apply(context: Context) {
        if (!Graph.settings.loaded) runCatching { Graph.settings.load() }
        apply(context, Graph.settings.snapshot.value.theme, isNight(context))
    }

    fun apply(context: Context, theme: ThemeConfig, night: Boolean = isNight(context)) {
        val pal = paletteIndexOf(theme)
        val target = componentName(pal, night)
        val pm = context.packageManager
        val current = lastComponent ?: enabledComponent(pm, context)
        if (current == target) {
            lastComponent = target
            return
        }
        runCatching {
            pm.setComponentEnabledSetting(
                ComponentName(context, target),
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP
            )
            pm.setComponentEnabledSetting(
                ComponentName(context, current),
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP
            )
        }
        lastComponent = target
    }

    /** 从 PackageManager 读回现在生效的是哪个 alias（清单默认 pal0_day）。 */
    private fun enabledComponent(pm: PackageManager, context: Context): String {
        for (p in 0 until PALETTE_COUNT) {
            for (night in booleanArrayOf(false, true)) {
                val name = componentName(p, night)
                val st = pm.getComponentEnabledSetting(ComponentName(context, name))
                if (st == PackageManager.COMPONENT_ENABLED_STATE_ENABLED) return name
            }
        }
        // 没被显式改过 → 跟随清单默认（pal0_day 是唯一 enabled=true 的）
        return defaultComponentName()
    }
}
