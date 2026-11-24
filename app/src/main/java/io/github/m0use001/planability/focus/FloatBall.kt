package io.github.m0use001.planability.focus

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import io.github.m0use001.planability.MainActivity
import io.github.m0use001.planability.core.HapticEngine
import io.github.m0use001.planability.data.Graph

/**
 * 专注悬浮胶囊（v19.13：原圆球 → 胶囊）：系统悬浮窗显示番茄阶段 + 剩余时间 + 进度环。
 * 单击暂停/继续，长按结束专注，拖动移位（松手自动吸附到左右边缘）。
 * 震感走 HapticEngine（全局强度设置）。
 *
 * **只有处于专注状态才悬浮**（`phase != Idle`），这条规则在两处把：
 * [show] 入口拒绝空闲时挂载 —— 否则 App 启动补挂（`PlanabilityApp`）与设置里点「开启」
 * （`FocusScreen`）都会把「番茄钟 / 点按开始」常驻到桌面上；
 * [BallView.tick] 每 500ms 复查一次，状态回到 Idle 就自己撤下 —— 兜住「专注结束但没人调 hide」。
 * 暂停（Paused）算专注状态，胶囊要留着让用户点继续。
 */
object FloatBall {
    private var view: BallView? = null

    fun canDraw(context: Context): Boolean = Settings.canDrawOverlays(context)

    fun show(context: Context) {
        if (FocusTimerState.phase.value == FocusTimerState.Phase.Idle) return
        if (!canDraw(context)) return
        val app = context.applicationContext
        // v19.13：addView 必须在主线程；且**只有真的挂上**才记 view，
        // 否则一次失败后 view 非空，之后永远 early-return（悬浮窗再也不出现）
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            if (view != null) return@post
            val wm = app.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val v = BallView(app)
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = 40
                y = 600
            }
            runCatching { wm.addView(v, params) }
                .onSuccess { view = v }
                .onFailure { android.util.Log.e("FloatBall", "addView 失败", it) }
        }
    }

    fun hide(context: Context) {
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            val v = view ?: return@post
            val wm = context.applicationContext
                .getSystemService(Context.WINDOW_SERVICE) as WindowManager
            runCatching { wm.removeView(v) }
            view = null
        }
    }
}

@SuppressLint("ClickableViewAccessibility")
private class BallView(context: Context) : View(context) {

    private val wm get() = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val handler = Handler(Looper.getMainLooper())
    private val density = resources.displayMetrics.density
    // v19.13：圆球 68dp → 胶囊 132×48dp
    private val capW = (132 * density).toInt()
    private val capH = (48 * density).toInt()
    private val ringR = 13 * density
    private val ringCx = 12 * density + ringR
    private val ringCy = capH / 2f
    private val textX = ringCx + ringR + 9 * density
    private val haptics = HapticEngine(context) {
        runCatching { Graph.settings.snapshot.value.theme.hapticIntensity / 100f }.getOrDefault(1f)
    }

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(235, 40, 44, 62)
    }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3.4f * density
        strokeCap = Paint.Cap.ROUND
        color = Color.rgb(255, 138, 101)
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 15 * density
        textAlign = Paint.Align.LEFT
        isFakeBoldText = true
    }
    private val stagePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(190, 255, 255, 255)
        textSize = 10 * density
        textAlign = Paint.Align.LEFT
    }

    private val tick = object : Runnable {
        override fun run() {
            // 专注状态才悬浮：空闲了就自己撤下（show() 只把入口，这里兜持续条件）
            if (FocusTimerState.phase.value == FocusTimerState.Phase.Idle) {
                FloatBall.hide(context)
                return
            }
            invalidate()
            handler.postDelayed(this, 500)
        }
    }

    private var downX = 0f
    private var downY = 0f
    private var paramsX = 0
    private var paramsY = 0
    private var pressedAt = 0L
    private var moved = false
    private var longPressFired = false

    private val longPressAction = Runnable {
        if (!moved && FocusTimerState.phase.value != FocusTimerState.Phase.Idle) {
            longPressFired = true
            haptics.perform(HapticEngine.Kind.ImpactHeavy)
            FocusTimerService.stop(context)
        }
    }

    init {
        handler.post(tick)
        setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY
                    paramsX = (layoutParams as WindowManager.LayoutParams).x
                    paramsY = (layoutParams as WindowManager.LayoutParams).y
                    pressedAt = System.currentTimeMillis()
                    moved = false
                    longPressFired = false
                    handler.postDelayed(longPressAction, 600)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (!moved && dx * dx + dy * dy > (12 * density) * (12 * density)) {
                        moved = true
                        handler.removeCallbacks(longPressAction)
                    }
                    if (moved) {
                        val lp = layoutParams as WindowManager.LayoutParams
                        val maxX = resources.displayMetrics.widthPixels - capW
                        val maxY = resources.displayMetrics.heightPixels - capH
                        lp.x = (paramsX + dx.toInt()).coerceIn(0, maxX)
                        lp.y = (paramsY + dy.toInt()).coerceIn(0, maxY)
                        runCatching { wm.updateViewLayout(this, lp) }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    handler.removeCallbacks(longPressAction)
                    // 松手**永远吸附到更近的一侧**（2026-11-03 用户「自适应吸附到屏幕两侧」）：
                    // 旧版留了中段 1/3 死区（x 在 1/3..2/3 间松手就停在原地），胶囊会悬在屏幕中间
                    val snapThreshold = resources.displayMetrics.widthPixels / 2f
                    if (moved) {
                        runCatching {
                            val lp = layoutParams as WindowManager.LayoutParams
                            val screenW = resources.displayMetrics.widthPixels
                            val margin = (8 * density).toInt()
                            val maxX = (screenW - capW).coerceAtLeast(0)
                            lp.x = (if (lp.x + capW / 2f < snapThreshold) margin
                            else screenW - capW - margin).coerceIn(0, maxX)
                            wm.updateViewLayout(this, lp)
                        }
                    }
                    if (!moved && !longPressFired && System.currentTimeMillis() - pressedAt < 600) {
                        // 单击：暂停/继续（空闲时打开应用）
                        when (FocusTimerState.phase.value) {
                            FocusTimerState.Phase.Running -> {
                                haptics.perform(HapticEngine.Kind.Tap)
                                FocusTimerService.pause(context)
                            }
                            FocusTimerState.Phase.Paused -> {
                                haptics.perform(HapticEngine.Kind.Tap)
                                FocusTimerService.resume(context)
                            }
                            else -> context.startActivity(
                                android.content.Intent(context, MainActivity::class.java)
                                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        }
                        performClick()
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    handler.removeCallbacks(longPressAction)
                    true
                }
                else -> false
            }
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(capW, capH)
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()

        val phase = FocusTimerState.phase.value
        val stage = FocusTimerState.stage.value
        val countUp = FocusTimerState.countUp
        val total = FocusTimerState.totalSec.value
        val remain = FocusTimerState.remainSec.value
        val idle = phase == FocusTimerState.Phase.Idle || phase == FocusTimerState.Phase.Finished

        // 胶囊底：全圆角
        canvas.drawRoundRect(0f, 0f, w, h, h / 2f, h / 2f, bgPaint)

        // 阶段色：专注橙 / 短休绿 / 长休蓝；暂停灰
        val accent = when {
            phase == FocusTimerState.Phase.Paused -> Color.argb(255, 150, 150, 158)
            stage == FocusTimerState.Stage.ShortBreak -> Color.rgb(129, 199, 132)
            stage == FocusTimerState.Stage.LongBreak -> Color.rgb(100, 181, 246)
            else -> Color.rgb(255, 138, 101)
        }
        val sweep = when {
            idle -> 0f
            // 正计时没有总时长，画满环没有语义（2026-11-03 与专注页同修：
            // remain 是已用秒数、total 是 25 分占位）→ 只留静态环
            stage == FocusTimerState.Stage.Work && countUp -> 0f
            total > 0 -> 360f * remain / total
            else -> 0f
        }
        // 左侧小环 + 进度
        ringPaint.color = accent
        if (idle) {
            dotPaint.color = accent
            canvas.drawCircle(ringCx, ringCy, ringR * 0.34f, dotPaint)
        } else {
            canvas.drawCircle(ringCx, ringCy, ringR, ringPaint)
            if (sweep > 0f) {
                canvas.drawArc(
                    ringCx - ringR, ringCy - ringR, ringCx + ringR, ringCy + ringR,
                    -90f, sweep, false, ringPaint
                )
            }
        }

        // 文案：主时间 + 副阶段
        val clock = "${remain / 60}:${"%02d".format(remain % 60)}"
        val main = if (idle) "番茄钟" else clock
        val sub = when {
            idle -> if (phase == FocusTimerState.Phase.Finished) "已完成" else "点按开始"
            phase == FocusTimerState.Phase.Paused -> "已暂停 · 点按继续"
            stage == FocusTimerState.Stage.ShortBreak -> "短休 · 点按暂停"
            stage == FocusTimerState.Stage.LongBreak -> "长休 · 点按暂停"
            countUp -> "正计时 · 第${FocusTimerState.round.value}轮"
            else -> "专注 · 第${FocusTimerState.round.value}轮"
        }
        val mainBase = ringCy - 1 * density
        val subBase = ringCy + 12 * density
        canvas.drawText(main, textX, mainBase, textPaint)
        canvas.drawText(sub, textX, subBase, stagePaint)
    }

    override fun onDetachedFromWindow() {
        handler.removeCallbacks(tick)
        handler.removeCallbacks(longPressAction)
        super.onDetachedFromWindow()
    }
}
