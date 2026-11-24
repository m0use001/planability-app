package io.github.m0use001.planability.notify

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import io.github.m0use001.planability.data.Automation
import io.github.m0use001.planability.data.AutomationRepo
import io.github.m0use001.planability.data.Graph
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * v24：**地理围栏触发**——进/出某个地方时自动跑一条自动化。
 *
 * ## 「没设位置绑定的提醒就不空扫描」怎么保证的
 *
 * 这是本文件存在的第一原则，也是用户明确提的要求。做法是三道闸，任何一道不过就**完全不启动**：
 *
 * 1. **不注册就不存在**。`LocationManager.requestLocationUpdates` 一旦注册，系统就会
 *    按 minTime/minDistance 持续唤醒进程、上报 GPS——哪怕回调里第一行就 return。
 *    所以「空跑」不是靠回调里判断，是靠**压根不注册**。
 * 2. **判据是一条 SQL**。[AutomationRepo.enabledGeo] 只返回 `enabled=1 且
 *    geoTrigger!='none' 且 geoRadiusM>0 且坐标非零` 的行。它返回空列表时，
 *    [sync] 直接 `stop()` 后 return——不申请权限、不取缓存位置、不做任何地理计算。
 * 3. **权限只在真的要听的时候才问**。没有后台定位权限时也只是记一条状态、
 *    在 UI 上提示，不弹窗、不反复骚扰；用户自己开了带位置的自动化才走申请流程。
 *
 * 另外 [stop] 幂等：重复调用不会抛，也不会因为「上一个监听已经注销过」而出错。
 *
 * ## 迟滞（hysteresis）
 *
 * 围栏边界上 GPS 会抖。没有迟滞的话人站在边上会 `arrive → leave → arrive` 交替触发，
 * 通知刷成 spam。判据是**进**用 `radius`，**出**用 `radius + OUT_MARGIN_M`：
 * 两个边界之间留一条 60m 的死区，只有真的走出去很远才算「离开」。
 */
object GeoWatch {

    private const val TAG = "GeoWatch"

    /** 死区宽度（米）：小于它就算还「在里面」。 */
    private const val OUT_MARGIN_M = 60f

    /** 多久取一次位置（毫秒）。围栏本来就是粗粒度，不要更勤。 */
    private const val MIN_TIME_MS = 30_000L

    /** 至少移动这么多米才上报（米）。 */
    private const val MIN_DIST_M = 50f

    private var listener: LocationListener? = null
    private var watching = false

    /** 当前是否真的在监听。UI 用它显示「位置触发：已开启/未开启」。 */
    val isWatching: Boolean get() = watching

    /**
     * 是否存在带位置的自动化（最近一次 [sync] 的结果）。
     *
     * 用缓存而不是每次查库：[statusText] 是普通函数、跑在主线程，不能挂起查 Room。
     * 这个值的语义就是「上次同步时有没有」，对 UI 提示足够了。
     */
    @Volatile
    var hasFences: Boolean = false
        private set

    private fun lm(context: Context): LocationManager? =
        context.applicationContext.getSystemService(Context.LOCATION_SERVICE) as? LocationManager

    private fun granted(context: Context): Boolean =
        perms.any {
            context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
        }

    private val perms = arrayOf(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION
    )

    /**
     * 同步监听状态。**这是唯一的入口**，在四个时机调用：
     * 应用启动、自动化增删改、开机、应用更新。
     *
     * 幂等：已经在听就刷新围栏表并 return；没有带位置的自动化就停。
     */
    fun sync(context: Context) {
        val app = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            val fences = runCatching { AutomationRepo.enabledGeo() }.getOrDefault(emptyList())
            // 先把缓存写上：UI 的状态文案靠它，且「有没有在用位置」与实际动作永远一致
            hasFences = fences.isNotEmpty()
            android.util.Log.d(TAG, "sync: fences=${fences.size} granted=${granted(app)} wasWatching=$watching")
            if (fences.isEmpty()) {
                stop(app)
                return@launch
            }
            if (!granted(app)) {
                // 没有定位权限就不注册——注册了也只会一直收到 null/异常
                stop(app)
                return@launch
            }
            start(app, fences)
        }
    }

    @SuppressLint("MissingPermission") // 上面 granted() 已经把住了
    private fun start(context: Context, fences: List<Automation>) {
        val manager = lm(context) ?: return
        if (listener != null) {
            runCatching { manager.removeUpdates(listener!!) }
            listener = null
        }
        val l = object : LocationListener {
            override fun onLocationChanged(loc: Location) = evaluate(context, fences, loc)
            // 老 API 的四个回调：空实现，但**不能删**——删了在部分 ROM 上会 AbstractMethodError
            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {}
            @Deprecated("系统回调，保留以兼容旧 API")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        }
        val provider = when {
            runCatching { manager.isProviderEnabled(LocationManager.GPS_PROVIDER) }.getOrDefault(false) ->
                LocationManager.GPS_PROVIDER
            runCatching { manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) }.getOrDefault(false) ->
                LocationManager.NETWORK_PROVIDER
            else -> return
        }
        val ok = runCatching {
            manager.requestLocationUpdates(
                provider, MIN_TIME_MS, MIN_DIST_M, l, Looper.getMainLooper()
            )
        }.isSuccess
        listener = if (ok) l else null
        watching = ok
        android.util.Log.d(TAG, "start: provider=$provider ok=$ok fences=${fences.size}")

        if (ok) {
            // 先用一次**缓存位置**把迟滞状态对齐，免得用户一开就收到一条「你已在围栏内」
            getLastKnown(manager)?.let { loc ->
                CoroutineScope(Dispatchers.IO).launch { evaluate(context, fences, loc, fire = false) }
            }
        }
    }

    fun stop(context: Context) {
        val l = listener ?: run { if (watching) android.util.Log.d(TAG, "stop: nothing registered"); watching = false; return }
        runCatching { lm(context)?.removeUpdates(l) }
        listener = null
        watching = false
        android.util.Log.d(TAG, "stop: removed listener")
    }

    private fun getLastKnown(manager: LocationManager): Location? =
        runCatching {
            manager.allProviders
                .mapNotNull { p -> runCatching { manager.getLastKnownLocation(p) }.getOrNull() }
                .maxByOrNull { it.time }
        }.getOrNull()

    /**
     * 判定一条围栏。`fire=false` 时只对齐迟滞状态不触发（用于初始化）。
     *
     * 坐标直接和系统给的位置算距离：**不做 GCJ 换算**——定位 API 出的就是 WGS84，
     * 数据库里存的也是 WGS84，两边同口径。这里若"好心"去换算反而会引入几百米偏差。
     */
    private fun evaluate(context: Context, fences: List<Automation>, loc: Location, fire: Boolean = true) {
        CoroutineScope(Dispatchers.IO).launch {
            for (f in fences) {
                val d = distanceMeters(loc.latitude, loc.longitude, f.geoLat, f.geoLng)
                // 迟滞：进去用 radius，出来要用 radius + OUT_MARGIN，中间是死区。
                // 「当前在不在里面」必须按上一次状态算，否则边界抖动时两个条件会同时为真。
                val entered = !f.geoInside && d <= f.geoRadiusM
                val exited = f.geoInside && d > f.geoRadiusM + OUT_MARGIN_M
                val inside = if (f.geoInside) !exited else entered
                if (inside != f.geoInside) AutomationRepo.setGeoInside(f.id, inside)

                val shouldFire = when (f.geoTrigger) {
                    "arrive" -> entered
                    "leave" -> exited
                    else -> false
                }
                if (!fire || !shouldFire) continue

                // 防抖：同一位置抖动反复横跳时，lastRun 也要挡一道
                val now = System.currentTimeMillis()
                if (now - f.lastRun < 60_000L) continue
                AutomationRepo.touchLastRun(f.id)
                AutomationSweep.execute(context, f)
            }
        }
    }

    /** 两点球面距离（米）。Haversine，地球半径取 6371km。 */
    fun distanceMeters(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Float {
        val r = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
            Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
            Math.sin(dLng / 2) * Math.sin(dLng / 2)
        return (2 * r * Math.asin(Math.sqrt(a.coerceAtMost(1.0)))).toFloat()
    }

    /** 自动化增删改后调一次，让「有没有在监听」立刻跟上。 */
    fun onAutomationsChanged(context: Context) = sync(context)

    /** 给 UI 用的简短状态。普通函数（非挂起），所以判据取 [hasFences] 缓存。 */
    fun statusText(context: Context): String {
        val app = context.applicationContext
        return when {
            !hasFences -> "位置触发：未使用"
            !granted(app) -> "位置触发：需要定位权限"
            watching -> "位置触发：已开启"
            else -> "位置触发：未启动"
        }
    }

    init {
        // 进程被杀后重启：没有带位置的自动化就什么都不做
        if (Graph.appContext != null) sync(Graph.appContext)
    }
}