package io.github.m0use001.planability.data

import io.github.m0use001.planability.core.AppTime
import java.time.LocalDate

/**
 * 分 App 的当日屏幕使用时间（「屏幕使用时间」小组件的数据源）。
 *
 * 算法与 `ui/home/HomeCards.loadScreenMinutes`（v19 终版）同一条链：逐事件按包配对成
 * 前台区间 → 与亮屏区间求交 → 区间并集。区别只在**按包保留**：
 *  - 每个 App 的分钟数 = 该包前台区间 ∩ 亮屏 的并集；
 *  - 当日总量 = 全部包并集后再并一次（= 全局并集，与 loadScreenMinutes 的总数一致，
 *    所以组件大字与评分详情页的「屏幕 X小时」口径相同）。
 * 不能用 queryUsageStats 求和（桶陷阱）、孤儿暂停直接忽略——两条教训都在
 * HomeCards 的长注释里，这里不复述，但改这段的人必须先读它。
 */
object ScreenTimeStats {

    data class AppUsage(val packageName: String, val label: String, val minutes: Int)

    /** 手动填写优先（与评分页同口径）；无权限或无数据返回 null。 */
    suspend fun today(date: LocalDate = AppTime.today()): Today? {
        val dayKey = AppTime.dayKey(date)
        val manual = Graph.settings.getManualScreenTime(dayKey)
        val context = Graph.appContext
        if (!hasUsageStatsPermission(context)) {
            return if (manual > 0) Today(manual, emptyList()) else null
        }
        val perPackage = runCatching { perPackageMinutes(context, date) }.getOrNull()
            ?: return if (manual > 0) Today(manual, emptyList()) else null
        val rows = perPackage.first
            .filter { it.value > 0 && !isLauncher(it.key) }
            .map { (pkg, min) -> AppUsage(pkg, appLabel(context, pkg), min) }
            .sortedByDescending { it.minutes }
        // 总量 = 非 launcher 包的前台区间做**全局并集**——不能把各包分钟直接相加：
        // 分屏/画中画/前后台切换瞬间同一段时间会出现在两个包里，相加就重复计时
        //（用户实测：sum=10h31m vs 谷歌原件同屏 8h57m）。launcher 不进总量（同原件）。
        val total = manual.takeIf { it > 0 } ?: perPackage.second
        return Today(total, rows)
    }

    data class Today(val totalMinutes: Int, val apps: List<AppUsage>)

    private fun appLabel(context: android.content.Context, pkg: String): String = runCatching {
        context.packageManager.getApplicationLabel(
            context.packageManager.getApplicationInfo(pkg, 0)
        ).toString()
    }.getOrDefault(pkg)

    /** 桌面/启动器不计入分 App 行（在桌面上前台的是 launcher，列出来全是噪音），总量照算。 */
    private fun isLauncher(pkg: String): Boolean = pkg.contains("launcher")

    private fun hasUsageStatsPermission(context: android.content.Context): Boolean = runCatching {
        val appOps = context.getSystemService(android.content.Context.APP_OPS_SERVICE) as android.app.AppOpsManager
        appOps.unsafeCheckOpNoThrow(
            "android:get_usage_stats", android.os.Process.myUid(), context.packageName
        ) == android.app.AppOpsManager.MODE_ALLOWED
    }.getOrDefault(false)

    /**
     * 每个包的当日亮屏前台分钟数 + 非 launcher 包的全局并集分钟数。
     * 全局并集用于 total：各包分钟直接相加会把跨包重叠段（分屏/画中画）重复计时。
     */
    private fun perPackageMinutes(
        context: android.content.Context,
        date: LocalDate
    ): Pair<Map<String, Int>, Int> {
        val usm = context.getSystemService(android.content.Context.USAGE_STATS_SERVICE)
            as android.app.usage.UsageStatsManager
        val start = date.atStartOfDay(AppTime.zone).toInstant().toEpochMilli()
        val end = date.plusDays(1).atStartOfDay(AppTime.zone).toInstant().toEpochMilli()
        val endNow = System.currentTimeMillis().coerceAtMost(end)

        val events = usm.queryEvents(start, end)
        val ev = android.app.usage.UsageEvents.Event()
        val openResume = HashMap<String, Long>()
        // pkg → 前台区间（配对成功的）
        val fg = HashMap<String, MutableList<LongArray>>()
        val screenOn = ArrayList<LongArray>()
        var screenState: Boolean? = null
        var onStart = 0L
        var firstScreenTyp = 0        // 0=全天无屏幕事件 1=首个是亮屏 -1=首个是灭屏
        var firstScreenT = 0L

        while (events.hasNextEvent()) {
            events.getNextEvent(ev)
            when (ev.eventType) {
                android.app.usage.UsageEvents.Event.ACTIVITY_RESUMED -> {
                    if (openResume[ev.packageName] == null) openResume[ev.packageName] = ev.timeStamp
                }
                android.app.usage.UsageEvents.Event.ACTIVITY_PAUSED -> {
                    val r = openResume.remove(ev.packageName)
                    if (r != null) {
                        val from = r.coerceAtLeast(start)
                        val to = ev.timeStamp.coerceAtMost(end)
                        if (to > from) fg.getOrPut(ev.packageName) { ArrayList() }
                            .add(longArrayOf(from, to))
                    }
                }
                android.app.usage.UsageEvents.Event.SCREEN_INTERACTIVE,
                android.app.usage.UsageEvents.Event.SCREEN_NON_INTERACTIVE -> {
                    val on = ev.eventType == android.app.usage.UsageEvents.Event.SCREEN_INTERACTIVE
                    if (firstScreenTyp == 0) {
                        firstScreenTyp = if (on) 1 else -1
                        firstScreenT = ev.timeStamp
                    }
                    if (on) {
                        if (screenState != true) onStart = ev.timeStamp
                        screenState = true
                    } else {
                        if (screenState == true && onStart > 0) {
                            val to = ev.timeStamp.coerceAtMost(end)
                            if (to > onStart) screenOn.add(longArrayOf(onStart, to))
                        }
                        screenState = false
                        onStart = 0L
                    }
                }
            }
        }
        // 还在前台的包：补到 now（孤儿暂停忽略，与主口径一致）
        openResume.forEach { (pkg, r) ->
            val from = r.coerceAtLeast(start)
            if (endNow > from) fg.getOrPut(pkg) { ArrayList() }.add(longArrayOf(from, endNow))
        }
        // 首个事件是灭屏 → 它之前是亮屏；全天无屏幕事件 = 全天亮屏（同主口径）
        if (firstScreenTyp == 0) screenOn.add(longArrayOf(start, end))
        else {
            if (firstScreenTyp == -1) screenOn.add(longArrayOf(start, firstScreenT))
            if (screenState == true && onStart > 0) screenOn.add(longArrayOf(onStart, endNow))
        }

        fun unionMinutes(intervals: List<LongArray>): Int {
            val sorted = intervals.map { iv ->
                // ∩ 亮屏段（可能切出多段）
                screenOn.mapNotNull { s ->
                    val a = maxOf(iv[0], s[0]); val b = minOf(iv[1], s[1])
                    if (b > a) longArrayOf(a, b) else null
                }
            }.flatten().sortedBy { it[0] }
            var total = 0L
            var curS = -1L
            var curE = -1L
            for (iv in sorted) {
                if (curE < 0) { curS = iv[0]; curE = iv[1] }
                else if (iv[0] <= curE) { if (iv[1] > curE) curE = iv[1] }
                else { total += curE - curS; curS = iv[0]; curE = iv[1] }
            }
            if (curE >= 0) total += curE - curS
            return (total / 60000L).toInt()
        }

        val perPackage = fg.mapValues { (_, ivs) -> unionMinutes(ivs) }
        // 全局并集（不含 launcher）：区间先按包拆开再合到一起并，跨包重叠不重复计
        val globalUnion = unionMinutes(
            fg.filter { !isLauncher(it.key) }.values.flatten()
        )
        return perPackage to globalUnion
    }
}
