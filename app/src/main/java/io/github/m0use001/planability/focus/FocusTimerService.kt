package io.github.m0use001.planability.focus

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import io.github.m0use001.planability.MainActivity
import io.github.m0use001.planability.data.Graph
import io.github.m0use001.planability.notify.Notify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 番茄钟前台服务：通知栏倒计时，进程被杀后由状态恢复。
 * 阶段流转：专注 →（短休 ×N-1 / 长休 ×1）→ 专注 …；正计时模式专注阶段手动结束。
 */
class FocusTimerService : Service() {

    private val scope = CoroutineScope(Dispatchers.Default + Job())
    private var tickJob: Job? = null
    // v12：悬浮计时窗（退出应用仍显示剩余时间）
    // v19.13：改用 FloatBall 胶囊，不再持有自己的 View
    private var overlayAdded = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val s = Graph.settings.snapshot.value
                val min = intent.getIntExtra(EXTRA_MIN, s.pomoWorkMin)
                FocusTimerState.startFocus(min, s.focusCountUp, s.pomoRounds)
                startLoop()
                // 立刻把带 Chronometer 的正式通知顶上来（否则前台兜底通知要晾 1 秒）
                updateNotification()
            }
            ACTION_PAUSE -> FocusTimerState.pause()
            ACTION_RESUME -> FocusTimerState.resume()
            ACTION_OVERLAY -> {
                if (intent.getBooleanExtra(EXTRA_SHOW, true)) addOverlayIfNeeded() else removeOverlay()
            }
            ACTION_STOP -> {
                stopAndRecord()
                FocusTimerState.reset()
                removeOverlay()
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                // 服务被重建：若状态仍在计时则继续循环
                if (FocusTimerState.phase.value == FocusTimerState.Phase.Running) startLoop()
                else stopSelf()
            }
        }
        startForegroundCompat()
        if (FocusTimerState.phase.value == FocusTimerState.Phase.Running ||
            FocusTimerState.phase.value == FocusTimerState.Phase.Paused
        ) addOverlayIfNeeded()
        return START_STICKY
    }

    /** 手动结束：Work 阶段记流水（正计时记实际分钟、满 1 分钟算完成），休息阶段不记 */
    private fun stopAndRecord() {
        val st = FocusTimerState.stage.value
        if (st == FocusTimerState.Stage.Work && FocusTimerState.phase.value != FocusTimerState.Phase.Idle) {
            if (FocusTimerState.countUp) {
                val mins = FocusTimerState.stopCountUpMinutes()
                if (mins >= 1) {
                    Graph.appScope.launch { Graph.focus.recordSession(mins, completed = true) }
                }
            } else {
                Graph.appScope.launch {
                    Graph.focus.recordSession(FocusTimerState.plannedMin, completed = false)
                }
            }
        }
    }

    private fun startLoop() {
        tickJob?.cancel()
        tickJob = scope.launch {
            while (isActive && FocusTimerState.phase.value != FocusTimerState.Phase.Idle) {
                delay(1000)
                val doneStage = FocusTimerState.tick()
                updateNotification()
                if (doneStage != null) handleStageFinished(doneStage)
            }
        }
    }

    /**
     * 上次发出的通知语义键。**2026-11-03 起通知不再每秒重发**：每秒 `nm.notify` 会让
     * 状态栏图标按时间重排，与系统「planability 正在其他软件上方显示」的图标不断换位
     * （用户真机实拍）。秒级计时改由系统 Chronometer 在通知里自己走
     * （[Notify.focus] 的 chronoBaseElapsed），这里只在阶段/轮次/暂停态变化时重发。
     */
    private var lastNotifKey: String? = null

    private fun updateNotification() {
        val nm = getSystemService(NotificationManager::class.java)
        val text = notifText()
        val (base, countDown) = chronoBase()
        // key 只含**语义**（文案+计时方向），不含 base——倒计时的 base 每秒都在变，
        // 把它放进 key 等于退回每秒重发。Chronometer 的基点在每次真正重发时取当下，
        // 连续运行期间系统自己走，不需要我们续。
        val key = "$text|$countDown"
        if (key == lastNotifKey) return
        lastNotifKey = key
        runCatching { nm.notify(NOTIF_ID, Notify.focus(this, text, base, countDown)) }
            .onSuccess { android.util.Log.i("FocusNotif", "posted: $key") }
            .onFailure { android.util.Log.e("FocusNotif", "notify failed", it) }
    }

    /**
     * 计时器基点：倒计时 = 未来时刻（系统倒数显示剩余），正计时 = 已流逝起点；
     * 暂停态返回 0 = 不启用计时器（静态文本里带剩余时间，Chronometer 暂停不了）。
     */
    private fun chronoBase(): Pair<Long, Boolean> {
        val now = android.os.SystemClock.elapsedRealtime()
        val s = FocusTimerState
        return when {
            s.phase.value == FocusTimerState.Phase.Paused -> 0L to false
            s.countUp && s.stage.value == FocusTimerState.Stage.Work ->
                (now - s.elapsedSec * 1000L) to false
            else -> (now + s.remainSec.value * 1000L) to true
        }
    }

    /** 通知文案：阶段 + 轮次（时间交给 Chronometer；暂停时静态带上剩余） */
    private fun notifText(): String {
        val st = FocusTimerState.stage.value
        val stageLabel = when (st) {
            FocusTimerState.Stage.Work ->
                if (FocusTimerState.countUp) "正计时" else "专注 · 第${FocusTimerState.round.value}轮"
            FocusTimerState.Stage.ShortBreak -> "短休"
            FocusTimerState.Stage.LongBreak -> "长休"
        }
        val r = FocusTimerState.remainSec.value
        return if (FocusTimerState.phase.value == FocusTimerState.Phase.Paused)
            "$stageLabel（已暂停 ${r / 60}:${"%02d".format(r % 60)}）" else stageLabel
    }

    private fun handleStageFinished(stage: FocusTimerState.Stage) {
        val s = Graph.settings.snapshot.value
        when (stage) {
            FocusTimerState.Stage.Work -> {
                Graph.appScope.launch {
                    Graph.focus.recordSession(FocusTimerState.plannedMin, completed = true)
                }
                val next = FocusTimerState.nextAfterWork()
                val breakMin = if (next == FocusTimerState.Stage.LongBreak) s.pomoLongMin else s.pomoShortMin
                Notify.remind(
                    this, 9001, "专注完成 🍅",
                    "第 ${FocusTimerState.round.value} 轮完成，休息 $breakMin 分钟",
                    Notify.ROUTE_FOCUS
                )
                FocusTimerState.enterStage(next, breakMin)
            }
            FocusTimerState.Stage.ShortBreak -> {
                val next = FocusTimerState.nextAfterBreak()
                if (next == null) {
                    finishCycle(); return
                }
                Notify.remind(this, 9002, "休息结束", "开始第 ${FocusTimerState.round.value} 轮专注", Notify.ROUTE_FOCUS)
                FocusTimerState.enterStage(next, s.pomoWorkMin)
            }
            FocusTimerState.Stage.LongBreak -> finishCycle()
        }
    }

    private fun finishCycle() {
        Notify.remind(
            this, 9003, "番茄完成 🎉",
            "已完成 ${FocusTimerState.roundsTotal} 轮番茄，好好休息",
            Notify.ROUTE_FOCUS
        )
        FocusTimerState.reset()
        stopSelf()
    }

    private fun startForegroundCompat() {
        // 点常驻通知 → 专注页（原来只指向 MainActivity，开 App 但停在别的 tab）
        val pi = Notify.openIntent(this, Notify.ROUTE_FOCUS)
        val builder = NotificationCompat.Builder(this, "focus")
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
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

    // ---------- v12：悬浮计时窗 ----------

    private fun overlayPrefEnabled(): Boolean =
        getSharedPreferences("planability", MODE_PRIVATE).getBoolean("focus.overlay", true)

    // v19.13：悬浮窗统一走 FloatBall 胶囊（可拖动 + 进度环 + 阶段文案），
    // 不再单独维护一个不可拖动的纯文字 TextView
    private fun addOverlayIfNeeded() {
        if (overlayAdded || !overlayPrefEnabled()) return
        if (!android.provider.Settings.canDrawOverlays(this)) return
        runCatching {
            FloatBall.show(this)
            overlayAdded = true
        }
    }

    private fun removeOverlay() {
        runCatching { FloatBall.hide(this) }
        overlayAdded = false
    }

    override fun onDestroy() {
        tickJob?.cancel()
        removeOverlay()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "start"
        const val ACTION_PAUSE = "pause"
        const val ACTION_RESUME = "resume"
        const val ACTION_STOP = "stop"
        const val ACTION_OVERLAY = "overlay"
        const val EXTRA_MIN = "min"
        const val EXTRA_SHOW = "show"
        const val NOTIF_ID = 1001

        fun start(ctx: Context, min: Int) =
            ctx.startForegroundService(intent(ctx, ACTION_START).putExtra(EXTRA_MIN, min))

        fun pause(ctx: Context) = ctx.startService(intent(ctx, ACTION_PAUSE))
        fun resume(ctx: Context) = ctx.startService(intent(ctx, ACTION_RESUME))
        fun stop(ctx: Context) = ctx.startService(intent(ctx, ACTION_STOP))
        fun overlay(ctx: Context, show: Boolean) =
            ctx.startService(intent(ctx, ACTION_OVERLAY).putExtra(EXTRA_SHOW, show))

        private fun intent(ctx: Context, action: String) =
            Intent(ctx, FocusTimerService::class.java).setAction(action)
    }
}
