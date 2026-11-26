package io.github.m0use001.planability.ui.info

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.LinkOff
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import io.github.m0use001.planability.ai.ChatEngine
import io.github.m0use001.planability.data.Graph
import io.github.m0use001.planability.ui.common.FloatDisplay
import io.github.m0use001.planability.ui.common.FloatLayoutConfig
import io.github.m0use001.planability.ui.common.FloatRect
import io.github.m0use001.planability.ui.common.LayoutSnapshot
import io.github.m0use001.planability.ui.common.PANE_ORDER
import io.github.m0use001.planability.ui.common.PaneRole
import io.github.m0use001.planability.ui.common.allLinked
import io.github.m0use001.planability.ui.common.clampToDisplay
import io.github.m0use001.planability.ui.common.defaultLayout
import io.github.m0use001.planability.ui.common.isStillLinked
import io.github.m0use001.planability.ui.common.movePane
import io.github.m0use001.planability.ui.common.relayout
import io.github.m0use001.planability.ui.common.setAllLinked
import io.github.m0use001.planability.ui.common.setLinked
import io.github.m0use001.planability.ui.common.snapRect
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import io.github.m0use001.planability.ui.theme.PlanabilityTheme
import android.widget.Toast
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner

/**
 * v14：手机端**悬浮窗输入框**（phone-b 早前在 `phone-b.md` assign 的 P3/P4/P5，对应 PC 的 float）。
 *
 * ## 为什么是半透明 Activity，而不是 WindowManager 悬浮窗
 *
 * PC 端是普通桌面窗口，浮窗就是浮窗；手机端不一样：`TYPE_APPLICATION_OVERLAY` 要额外权限、
 * 要自己处理 `BadTokenException`、还要自己解决焦点和输入法（`FloatBall` 三样都踩过）。
 * 而「输入框能浮出来打字」这件事**本质上要求这块区域能拿到键盘输入**，那恰恰是普通窗口
 * 最不费劲的。所以这里用一个全透明、不压暗、并且带 `FLAG_NOT_TOUCH_MODAL` 的 Activity：
 * 面板以外的地方点击直接穿到下面的 App，输入框则像平时一样弹键盘。
 *
 * ## 几何仍然走 `FloatLayout`
 *
 * 三段窗的排布、吸附、拖拽、连成一组全部调用 `ui/common/FloatLayout.kt` 里的纯函数——
 * 那套与 PC `layout.ts` 逐值对齐，两端算的是同一批矩形。本文件只负责「把矩形画成界面」
 * 和「把手指变成矩形」，不自己写任何几何。
 */
/**
 * 后台悬浮服务：把 [FloatChatRoot] 搬到 TYPE_APPLICATION_OVERLAY 窗口上。
 *
 * ## 为什么需要它
 *
 * Activity 形态有个绕不开的限制：**退到桌面 / 切到别的 App 后，Activity 必然被盖住**
 * （桌面和别的 App 的 z-order 都在它之上）。要让浮窗盖在别的 App 上面，
 * 只能走 WindowManager 的 overlay 窗口 —— 就是项目里 focus/FloatBall 那条路。
 *
 * ## 窗口配置的三个坑
 *
 * - 只用 `FLAG_NOT_TOUCH_MODAL` 不够：它只对「窗口边界之外」的触摸放行，
 *   而这个窗口是全屏的，「边界之外」是空集，触摸依然全被吃掉。
 * - `FLAG_NOT_FOCUSABLE` 会毁掉输入框：加了它窗口就拿不到焦点，键盘弹不出来
 *   （FloatBall 能用它，是因为那只是个不可输入的悬浮球）。
 * - 所以：窗口保持 `FLAG_NOT_TOUCH_MODAL` 且**不加** `FLAG_NOT_FOCUSABLE`，
 *   窗外放行由我们自己在 [dispatchTouchEvent] 里做 hit-test。
 */
private const val FLOG = "FloatChatOverlay"

class FloatChatOverlayService :
    androidx.lifecycle.LifecycleService(),
    androidx.savedstate.SavedStateRegistryOwner {

    /**
     * LifecycleService 只解决 Lifecycle（内部 ServiceLifecycleDispatcher），**不含 SavedState**，
     * 所以 SavedStateRegistryOwner 这半边还得自己实现。
     *
     * 关键点：`SavedStateRegistryController.create(owner)` 会把 controller 注册成 owner 的
     * lifecycle observer，**owner 一到 CREATED 它就自己 performAttach 了**。
     * 所以这里只需要在 super.onCreate() 之后调 `performRestore`，
     * **绝不能再手动 performAttach**（会撞「was already attached」），
     * 也不能把 attach/restore 整个包进 runCatching —— 那正是前几轮「一片空白且不报错」的由来。
     */
    private val savedStateController =
        androidx.savedstate.SavedStateRegistryController.create(this)

    override val savedStateRegistry: androidx.savedstate.SavedStateRegistry
        get() = savedStateController.savedStateRegistry

    private var windowManager: WindowManager? = null
    private var overlayView: android.view.View? = null

    /**
     * 浮窗当前并集边界，由 FloatChatRoot.onBounds 上报。
     *
     * **单位是 dp**（FloatChatRoot 内部按 dp 画窗），而 MotionEvent 的 x/y 是 px。
     * 记下来是因为这个坑同时踩了两次：先是拿 ev.x 直接和它比（坐标系原点都不同），
     * 改成加窗口原点偏移后又忘了换算单位 —— 两次的表现一模一样：
     * 「浮窗外点得动、浮窗内点不动」，看起来像 z-order 有问题，其实是量纲不对。
     */
    private var paneBounds = androidx.compose.ui.geometry.Rect.Zero

    /**
     * 窗口自身左上角的屏幕坐标（px）。
     *
     * 命中测试要用它换算：MotionEvent 的 x/y 是**窗口内**坐标（从 0 开始），
     * 而 paneBounds 是**屏幕**坐标，两者不差这一段就永远判不中。
     */
    @Volatile private var winX = 0
    @Volatile private var winY = 0

    /**
     * 窗口**真实**的屏幕原点（px），由布局回调持续同步。
     *
     * 为什么不用我们 updateViewLayout 时请求的那个 y：键盘弹起时系统会按 IME inset
     * 再平移一次窗口，那个平移量只有 WindowManager 知道。结果就是「请求 y」和「实际 y」
     * 差了一截，而内容是按请求值画的 offset —— 差的这一截正好把顶栏（把手条 + ×）
     * 推出窗口外裁掉，现场是「输入框能用、光标在、但关闭键不见了」。
     * 所以两边都以真实原点为准：内容偏移用它，命中测试也用它。
     */
    private val originOnScreen = androidx.compose.runtime.mutableStateOf(intArrayOf(0, 0))

    /**
     * 键盘高度（px，0 = 没弹）。
     *
     * 这个窗口默认既不响应也不上报 IME（实测 fitTypes 只有 STATUS_BARS / NAVIGATION_BARS，
     * 没有 SOFT_INPUT），键盘一弹浮窗就被压在底下只剩一条边。设了
     * SOFT_INPUT_ADJUST_NOTHING 之后这份 inset 才拿得到，让位逻辑改由我们自己算。
     */
    private val imeBottomPx = androidx.compose.runtime.mutableStateOf(0)

    /*
     * Lifecycle 与 SavedStateRegistry 全部交给 LifecycleService。
    //
    // 这一段原先手搓了三轮都绕不过去，记下来免得下一个人再踩：
    // 现象从头到尾是「addView 成功、浮窗一片空白」，因为异常发生在 attach 之后、组合之前，
    // 而且最初被 runCatching 静默吞了。三轮错法依次是——
    //   ① 自己 LifecycleRegistry，先拉 RESUMED 再 attach/restore：状态机顺序错；
    //   ② attach/restore 包在 runCatching 里：异常被吃，registry 永远没恢复，
    //      于是 ComposeView.onAttachedToWindow 里的 consumeRestoredStateForKey FATAL；
    //   ③ SavedStateRegistryController.create(this)：它内部自建 LifecycleRegistry 并与 owner
    //      解耦，Service 的 lifecycle 到 CREATED 它看不见 → performRestore 抛
    //      「cannot be called when owner is not CREATED」。
    // 另外试过：裸 SavedStateRegistry（构造函数 internal）、SavedStateRegistryProvider
    // （savedstate 1.4.0 里根本没这个类型）、无参 SavedStateRegistryController（构造函数 private）。
     * LifecycleService 直接给你一套配好的 Controller + Lifecycle 时序，这才是一行的事。
     */

    /**
     * 目标会话 id。
     *
     * 刻意用 State 而不是 onCreate 里一次性取值：`startForegroundService` 的执行顺序是
     * onCreate → onStartCommand，onCreate 那一刻拿不到启动 intent（Service 的 getIntent()
     * 在这个时机也不可靠）。所以先以 0 渲染（FloatChatRoot 见到 sessionId<=0 会自己建会话），
     * 等 onStartCommand 把真实 id 写进来，Compose 自然重组。
     */
    private val sessionIdState = androidx.compose.runtime.mutableStateOf(0L)

    override fun onStartCommand(
        intent: android.content.Intent?,
        flags: Int,
        startId: Int
    ): Int {
        intent?.getLongExtra(FloatChatActivity.EXTRA_SESSION_ID, 0L)?.let { sessionIdState.value = it }
        return super.onStartCommand(intent, flags, startId)
    }

    override fun onCreate() {
        super.onCreate()
        // super 之后 lifecycle 已是 CREATED，controller 已自动 attach；这里只补 restore。
        // 传空 Bundle：悬浮窗不需要恢复上次状态，但 registry 必须标记为「已恢复」，
        // 否则 ComposeView 里的 consumeRestoredStateForKey 会直接抛。
        runCatching { savedStateController.performRestore(Bundle()) }
            .onFailure { Log.e(FLOG, "performRestore failed", it) }
        Log.i(FLOG, "overlay service onCreate")
        // 直接进 RESUMED：Service 活着就意味着这个浮窗该是活的，
        // 停在 CREATED 会让所有 lifecycle-aware 的 effect 挂起（内容看着有、但不刷新）。
        startForegroundNotification()
        windowManager = getSystemService(WindowManager::class.java)
        // 外层 View 负责「窗外点放行」
        val root = object : android.widget.FrameLayout(this) {
            override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
                if (!isInsidePane(ev)) {
                    // 放行时留个记录：这个窗口是按浮窗矩形收缩的，正常情况下不会有
                    // DOWN 落在外面。真出现了，说明 paneBounds 与窗口对不上（量纲或原点）。
                    if (ev.actionMasked == android.view.MotionEvent.ACTION_DOWN) {
                        Log.w(
                            FLOG,
                            "DOWN outside window: screen=%.0f,%.0f bounds=%.1f,%.1f %.1fx%.1f"
                                .format(ev.x + winX, ev.y + winY,
                                    paneBounds.left, paneBounds.top,
                                    paneBounds.width, paneBounds.height)
                        )
                    }
                    return false
                }
                return super.dispatchTouchEvent(ev)
            }

            /**
             * 只有 DOWN 需要判断；后续 MOVE/UP 跟着这次 down 的目标走 ——
             * 否则拖动中手指滑出浮窗就会被下层窗口抢走。
             */
            private fun isInsidePane(ev: android.view.MotionEvent): Boolean {
                if (ev.actionMasked != android.view.MotionEvent.ACTION_DOWN) return true
                val r = paneBounds
                if (r == androidx.compose.ui.geometry.Rect.Zero) return true // 尚未上报，先全收
                // 两步换算，一步都不能少：
                //   ① 加回窗口原点 —— ev.x/ev.y 从 0 起算，paneBounds 是屏幕坐标；
                //   ② 乘 density   —— paneBounds 是 dp，ev 是 px。
                val d = resources.displayMetrics.density
                val sx = ev.x + winX
                val sy = ev.y + winY
                return sx >= r.left * d && sx <= r.right * d &&
                    sy >= r.top * d && sy <= r.bottom * d
            }
        }.apply {
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            // 跟踪真实屏幕原点：系统替我们让开键盘时，这里会跟着变
            setOnApplyWindowInsetsListener { v, insets ->
                imeBottomPx.value =
                    if (android.os.Build.VERSION.SDK_INT >= 30) {
                        insets.getInsets(android.view.WindowInsets.Type.ime()).bottom
                    } else 0
                insets
            }
            addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
                val loc = IntArray(2)
                v.getLocationOnScreen(loc)
                val prev = originOnScreen.value
                if (prev[0] != loc[0] || prev[1] != loc[1]) {
                    originOnScreen.value = loc
                    winX = loc[0]
                    winY = loc[1]
                }
            }
            // 关键一行：把自造的 LifecycleOwner 挂上去，ComposeView 才会开始组合。
            // setViewTreeLifecycleOwner 是 View 的扩展函数，得 import 后用扩展语法调。
            setViewTreeLifecycleOwner(this@FloatChatOverlayService)
            // 第二个 owner，和上面那个一样是 ComposeView 组合的硬性前提
            setViewTreeSavedStateRegistryOwner(this@FloatChatOverlayService)
        }

        // 窗口要**跟随浮窗矩形**，而不是铺满全屏。
        //
        // 这是「浮窗在别的 App 上还点得动下面」的唯一可靠做法（真机踩过）：全屏 overlay 窗口
        // 里 FLAG_NOT_TOUCH_MODAL 形同虚设——它只对「窗口边界之外」的触摸放行，而全屏窗口的
        // 「之外」是空集，于是自己写的 dispatchTouchEvent hit-test 也不管用，**整个桌面会被
        // 挡死**（实测点桌面图标毫无反应）。
        //
        // 所以：窗口 = 浮窗矩形；内容按 (屏幕坐标 − 窗口原点) 平移，让 FloatChatRoot 继续
        // 用屏幕坐标系算几何（面板之间那些对齐/吸附逻辑一个字都不用改）。
        val densityPx = resources.displayMetrics.density
        val metrics = resources.displayMetrics
        val screenW = metrics.widthPixels / densityPx
        val screenH = metrics.heightPixels / densityPx

        // 首帧还没有边界，先按默认位置给一个合理尺寸，随后被 onBounds 纠正
        val params = WindowManager.LayoutParams(
            (330 * densityPx).toInt(), (316 * densityPx).toInt(),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // NOT_TOUCH_MODAL：窗口外（也就是浮窗外）不拦截，触摸直接落到下面的 App；
            // 刻意不加 NOT_FOCUSABLE —— 加了输入框就拿不到焦点、键盘弹不出来。
            //
            // 也刻意**不加** FLAG_LAYOUT_NO_LIMITS：试过用它把窗口位置完全收归自己、
            // 手工按 IME inset 上移，结果它连 IME inset 一起挡掉了（imeBottom 恒为 0），
            // 浮窗直接被键盘压在底下看不见。键盘让位这事交给系统，我们只负责**跟着它走**。
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            android.graphics.PixelFormat.TRANSLUCENT
        ).apply {
            gravity = android.view.Gravity.TOP or android.view.Gravity.START
            dimAmount = 0f
            // 键盘让位交给系统：ADJUST_PAN 会把窗口整体推到键盘上方（不压缩内容）。
            // 自己按 ime inset 算位移试了三轮，要么浮窗被压在键盘底下只剩一条边，
            // 要么推过头整个飞出屏幕——因为内容是以屏幕坐标画的，任何"多推一点少推一点"
            // 都会立刻暴露成错位，不如让系统来。
            // 系统平移后的真实位置由下面的 addOnLayoutChangeListener 跟踪，
            // 命中测试（winX/winY）跟着它走就行。
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN
            // Android 15(API 35) 起 IME 归 fitInsetsTypes 管，softInputMode 单独设了没用。
            // 实测这台机(SDK 36)：只设 softInputMode 时 dumpsys 里 fitTypes 仍然只有
            // STATUS_BARS/NAVIGATION_BAR/CAPTION_BAR，键盘弹起窗口纹丝不动，
            // 浮窗直接被压在键盘底下只剩一条边。加上 ime() 才真的生效。
            if (android.os.Build.VERSION.SDK_INT >= 35) {
                fitInsetsTypes = android.view.WindowInsets.Type.ime() or
                    android.view.WindowInsets.Type.systemBars() or
                    android.view.WindowInsets.Type.displayCutout()
            }
            x = (12 * densityPx).toInt()
            y = (screenH * 0.60f * densityPx).toInt()
        }

        winX = params.x
        winY = params.y

        // 初值用 params 里的 x/y（addView 之前就已经是真实值），不能留 (0,0)
        originOnScreen.value = intArrayOf(params.x, params.y)
        val boundsState = androidx.compose.runtime.mutableStateOf(
            androidx.compose.ui.geometry.Rect(12f, screenH * 0.60f, 12f + 330f, screenH * 0.60f + 316f)
        )

        val compose = androidx.compose.ui.platform.ComposeView(this).apply {
            setContent {
                val settings by Graph.settings.snapshot.collectAsState()
                PlanabilityTheme(config = settings.theme) {
                    // 内容按窗口原点平移：FloatChatRoot 仍以屏幕坐标作画（默认锚点 0.60 等
                    // 全都保持一致），这里只把它挪进我们给的小窗口里。
                    val b = boundsState.value
                    val org = originOnScreen.value
                    // 浮窗底边超出键盘多少（px）。上限卡在 b.top：最多把浮窗顶到屏幕顶，
                    // 再多就该整体压缩了，那是另一套逻辑，不在这里偷偷做。
                    // 注意用**全屏**高度算键盘顶边，displayMetrics.heightPixels 不含状态栏。
                    // 让位由系统做，这里只跟着它走：键盘弹起时窗口真实原点会变，
                    // 布局回调会更新 originOnScreen / winX / winY（命中测试要靠它们）。
                    val imeShiftPx = 0f
                    Box(
                        Modifier
                            // 内容以**屏幕坐标**画（FloatChatRoot 的几何契约不能动），
                            // 面板落在 Box 内的位置就是 b.top，所以偏移恒为 -b.top：
                            // 这样它永远贴在窗口左上角，跟窗口被系统挪到哪都没关系。
                            // （绕这一圈是因为中途误把「窗口原点」也算进去了，
                            //  两次都把内容推出窗口外裁掉，现场是浮窗凭空消失。）
                            .offset(x = -b.left.dp, y = -b.top.dp)
                            .size(width = screenW.dp, height = screenH.dp)
                    ) {
                        FloatChatRoot(
                            sessionId = sessionIdState.value,
                            onExit = { stopSelf() },
                            onBounds = { r ->
                                paneBounds = r
                                boundsState.value = r
                                runCatching {
                                    // 先算出新的窗口原点并记下来，命中测试要靠它换算
                                    val nx = (r.left * densityPx).toInt()
                                    val ny = (r.top * densityPx - imeShiftPx).toInt()
                                    winX = nx
                                    winY = ny
                                    originOnScreen.value = intArrayOf(nx, ny)
                                    windowManager?.updateViewLayout(
                                        root,
                                        WindowManager.LayoutParams().apply {
                                            // copyFrom 必须在**最前面**：它把源 params 的所有字段
                                            // 覆写过来，写在后面等于把下面刚算好的
                                            // width/height/x/y 又抹回初始值 —— 症状就是
                                            // 「浮窗能显示，但怎么拖都不动」。
                                            copyFrom(params)
                                            width = (r.width * densityPx).toInt()
                                            height = (r.height * densityPx).toInt()
                                            x = nx
                                            y = ny
                                        }
                                    )
                                }.onFailure { Log.e(FLOG, "updateViewLayout failed", it) }
                            },
                            screenOverride = screenW to screenH,
                        )
                    }

                    // 键盘弹出/收起不会触发 onBounds（几何没变），这里单独把窗口挪一次。
                    // 内容偏移恒为 -b.top，窗口一动内容自然跟着走，两边不用各算一遍。
                    val winBounds = boundsState.value
                    LaunchedEffect(imeShiftPx) {
                        val nx = (winBounds.left * densityPx).toInt()
                        val ny = (winBounds.top * densityPx - imeShiftPx).toInt()
                        winX = nx
                        winY = ny
                        originOnScreen.value = intArrayOf(nx, ny)
                        runCatching {
                            windowManager?.updateViewLayout(
                                root,
                                WindowManager.LayoutParams().apply {
                                    copyFrom(params)
                                    width = (winBounds.width * densityPx).toInt()
                                    height = (winBounds.height * densityPx).toInt()
                                    x = nx
                                    y = ny
                                }
                            )
                        }.onFailure { Log.e(FLOG, "updateViewLayout failed", it) }
                    }
                }
            }
        }
        root.addView(
            compose,
            android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        runCatching {
            windowManager?.addView(root, params)
            overlayView = root
            Log.i(FLOG, "overlay view added, screen=${screenW}x$screenH")
        }.onFailure {
            Log.e(FLOG, "addView failed", it)
            // 没给「显示在其他应用上层」授权时 addView 会抛 SecurityException/BadToken，
            // 捕获掉并退回 Activity 形态，别让 App 默默崩掉。
            stopSelf()
        }
    }

    /** 前台通知：保住进程不被回收，也让用户看得见「有个浮窗在跑」。 */
    private fun startForegroundNotification() {
        val channelId = "float_chat_overlay"
        runCatching {
            val mgr = getSystemService(android.app.NotificationManager::class.java)
            if (android.os.Build.VERSION.SDK_INT >= 26 &&
                mgr?.getNotificationChannel(channelId) == null
            ) {
                mgr.createNotificationChannel(
                    android.app.NotificationChannel(
                        channelId, "悬浮窗", android.app.NotificationManager.IMPORTANCE_LOW
                    )
                )
            }
            val n = android.app.Notification.Builder(this, channelId)
                .setContentTitle("悬浮窗运行中")
                .setContentText("返回桌面后聊天窗仍会浮在最上层")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .build()
            startForeground(8701, n)
        }.onFailure { stopSelf() }
    }

    override fun onDestroy() {
        overlayView?.let { runCatching { windowManager?.removeView(it) } }
        overlayView = null
        super.onDestroy()
    }
}

class FloatChatActivity : ComponentActivity() {

    /**
     * 用户主动离开（Home / 切到别的 App）时，把浮窗交给后台服务承载。
     *
     * 用 [onUserLeaveHint] 而不是 [onStop]：点「×」finish 时同样会走 onStop，
     * 那种情况不该再把服务拉起来（否则窗关了服务还在后台挂通知）。
     */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        val allowed = android.provider.Settings.canDrawOverlays(this)
        Log.i(FLOG, "onUserLeaveHint allowed=$allowed")
        if (!allowed) return
        val sessionId = intent?.getLongExtra(EXTRA_SESSION_ID, 0L) ?: 0L
        // 不要 runCatching 静默吞掉：上一版就是这里把异常吃干净了，
        // 结果「Service 没起来」和「Service 起来了但崩了」两种情况完全分不开。
        runCatching {
            startForegroundService(
                Intent(this, FloatChatOverlayService::class.java)
                    .putExtra(EXTRA_SESSION_ID, sessionId)
            )
        }.onFailure { Log.e(FLOG, "startForegroundService failed", it) }
    }

    /** 回到前台时把后台服务收掉，避免两个浮窗（Activity + Overlay）同时画。 */
    override fun onStart() {
        super.onStart()
        runCatching { stopService(Intent(this, FloatChatOverlayService::class.java)) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 面板之外不吃触摸：点下面那个 App 就真的点到下面去了
        window.addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL)
        window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val sessionId = intent?.getLongExtra(EXTRA_SESSION_ID, 0L) ?: 0L
        // 需要「显示在其他应用上层」才能后台悬浮。没授权不影响当前 Activity 形态，
        // 所以不在 onCreate 里打断；留给 onUserLeaveHint 在真正需要时引导。
        if (!android.provider.Settings.canDrawOverlays(this)) {
            val asked = getSharedPreferences("float_chat", MODE_PRIVATE)
                .getBoolean("overlay_asked", false)
            if (!asked) {
                getSharedPreferences("float_chat", MODE_PRIVATE)
                    .edit().putBoolean("overlay_asked", true).apply()
                startActivity(
                    android.content.Intent(
                        android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        android.net.Uri.parse("package:$packageName")
                    )
                )
                Toast.makeText(this, "开启「显示在其他应用上层」，聊天窗才能在退回桌面后继续悬浮", Toast.LENGTH_LONG).show()
                finish()
                return
            }
        }
        setContent {
            // 必须套 PlanabilityTheme，不能用裸 MaterialTheme。
            // 裸 MaterialTheme{} 不传 colorScheme 也不传 typography，走的是 Material3 的
            // **默认浅色 + 默认字体**；而主界面（MainActivity）是用 PlanabilityTheme(settings.theme)
            // 渲染的，深浅模式、种子色、字体/字重/字号全来自用户的「外观」设置。
            // 结果真机上用户在应用里选了深色、选了自定义字体，浮窗却永远是白底黑字、
            // 字体也不跟随（截图里「输 入 消 息」字距异常就是这么来的）——两处一起修。
            val settings by Graph.settings.snapshot.collectAsState()
            PlanabilityTheme(config = settings.theme) {
                FloatChatRoot(sessionId = sessionId, onExit = { finish() })
            }
        }
    }

    companion object {
        const val EXTRA_SESSION_ID = "float_session_id"
    }
}

/** 三段窗的固定尺寸（dp）。 */
private val INPUT_W = 330.dp

/**
 * 窗体**外壳**高度 = 顶部拖动把手条 26dp + 底部 resize 把手 20dp。
 *
 * 这两块是 Pane 自己画的，**不在** INPUT_H / OUTPUT_H 里。之前给 defaultLayout 只传了
 * INPUT_H+OUTPUT_H(270dp)，漏掉外壳，实际要占 316dp，多出的 46dp 让整列被父约束 coerce，
 * 表现就是「输入框顶到把手条上、按钮被挤没」（真机截图确认过）。算总高必须含外壳。
 */
private val PANE_CHROME = 46.dp
// 一体窗**下方输入区**的固定高度。
//
// 原来独立输入窗是 134dp，合并成一体窗后输入区只占窗的一部分，110dp 够显示
// 三行内的输入（TextField maxLines=3），上面剩下的全部归输出区。
private val INPUT_H = 110.dp
// 菜单窗高度：0 —— 中间那扇窗**不画了**（反馈：两个框之间那条空的把手指要删掉）。
//
// 刻意**不**把 PaneRole.MENU 从 enum 里删掉，而是把它压成 0 高度、渲染时跳过：
//   · PaneRole / PANE_ORDER 是**跨端契约**，PC 端 layout.ts 里也是
//     `PaneRole = 'input' | 'menu' | 'output'`，三窗栈序对齐靠它；
//   · 而且 allLinked() 是 `PANE_ORDER.every(r => snap.panes[r]?.linked)` ——
//     一旦 snap.panes 里没有 MENU，这一项恒为 null，`every` 永远返回 false，
//     「三窗连成组」会直接失灵。
// 所以保留它在快照里（占 0 高），只跳过绘制：几何与并组逻辑一行不动，风险最小。
// 注意这是**手机端先行**的状态，PC 端仍画着菜单窗；要两端一致需同步改
// PaneRole/PANE_ORDER 并 bump STACK_ORDER_VERSION（layout.ts 里已有这个机制）。
private val MENU_H = 0.dp

/**
 * 一体窗的**默认总高** = [INPUT_H] + [OUTPUT_H]。
 *
 * 反馈要求「输入框输出框做成一体」，所以不再有独立的输出窗：
 * defaultLayout 那边 outputH 传 0，两段的总高并到 INPUT 这一扇上，渲染时只画 INPUT
 * （见 FloatChatRoot 里 `if (role != PaneRole.INPUT) return@forEach`）。
 * 几何上仍是「两扇」的形状，跨端 PaneRole/PANE_ORDER 一个字没动。
 *
 * 默认 160dp，之后**由底部把手拖动**调整，范围 [INPUT_H]+90dp ~ 屏高*0.88。
 *
 * 160 而不是更大的原因（真机踩过）：总高 = 把手条26 + 输出 + 输入110 + resize把手20，
 * 再叠上默认锚点 0.60（≈475dp），200dp 那版算出 831dp > 屏高 792dp，窗底被裁掉一截，
 * 相机/发送键只露出一半。160dp 时 475+316=791，正好塞得下。
 */
private val OUTPUT_H = 160.dp

/**
 * 三段窗的默认锚点：屏幕**右下**。
 *
 * 为什么放右下而不是居中：真机首验（v14）把锚点放在 `screenH*0.28` 时，三扇窗全挤在左上角，
 * 而此时主屏内容（聊天正文、输入栏）恰好在下方被挡掉——最该看见的东西反而看不见了。
 * 右下角是手机上「浮窗」的默认心智位置，也和系统小窗一致。
 */
private const val ANCHOR_X_RATIO = 0.06f
private const val ANCHOR_Y_RATIO = 0.60f

/** 发送需要的会话上下文，跟着窗口一起带进来。 */
private data class FloatSession(
    val assistantId: Long = 0L,
    val projectId: Long = 0L,
    val modelName: String = "",
)

@Composable
private fun FloatChatRoot(
    sessionId: Long,
    onExit: () -> Unit,
    /**
     * 上报「当前所有窗的并集矩形」（dp）。Activity 形态用不到，**悬浮窗形态必须用** ——
     * 后台服务那个窗口是全屏的，要让点在浮窗之外的触摸落到下面的 App 上，
     * 就得知道浮窗在哪一块（见 FloatChatOverlayService.isInsidePane）。
     */
    onBounds: (androidx.compose.ui.geometry.Rect) -> Unit = {},
    /**
     * 真实屏幕尺寸（dp）。后台悬浮窗形态下窗口只有浮窗那么大，BoxWithConstraints 量到的
     * maxWidth/maxHeight 是**窗口**不是屏幕，几何全靠它算，所以必须由外部告知。
     */
    screenOverride: Pair<Float, Float>? = null,
) {
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()

    // 会话不在了（新会话还没落库、或被删）就退出，别留一个空壳浮窗在桌面上
    var ctx by remember { mutableStateOf(FloatSession()) }
    var alive by remember { mutableStateOf(true) }
    // realId：这条浮窗**真正绑定**的会话 id。
    //
    // InputPaneBody / OutputPaneBody 都拿它查草稿、读流式状态、发消息，所以必须是
    // 数据库里真实存在的 id。信息页首页此时**还没有会话**（sessionId<=0），原先
    // `Graph.chat.session(0)` 返回 null → alive=false → 浮窗一闪就退出，等于按钮没用。
    // 现在：sessionId<=0 时先建一个空会话，再用它的 id 继续 —— 首页点「转悬浮」
    // 得到的是「刚建好的新会话的浮窗」，和主窗共享同一份草稿。
    var realId by remember { mutableStateOf(sessionId) }
    LaunchedEffect(sessionId) {
        var id = sessionId
        if (id <= 0L) {
            id = runCatching {
                withContext(Dispatchers.IO) { Graph.chat.createSession() }
            }.getOrNull()?.id ?: 0L
        }
        if (id <= 0L) {
            alive = false
            return@LaunchedEffect
        }
        Log.i(FLOG, "FloatChatRoot resolving session, input=$sessionId resolved=$id")
        val s = runCatching { withContext(Dispatchers.IO) { Graph.chat.session(id) } }.getOrNull()
        if (s == null) {
            Log.w(FLOG, "FloatChatRoot session $id not found -> alive=false")
            alive = false
        } else {
            Log.i(FLOG, "FloatChatRoot session ready id=$id")
            realId = id
            ctx = FloatSession(s.assistantId, s.projectId, s.model)
        }
    }
    if (!alive) {
        LaunchedEffect(Unit) { onExit() }
        return
    }
    // 等 realId 就绪再渲染 Pane：否则首页无会话时第一帧会拿 0 去查草稿与流状态
    // （不会崩，但会闪一屏空壳）。
    if (realId <= 0L) {
        LaunchedEffect(Unit) { Log.i(FLOG, "FloatChatRoot realId not ready yet") }
        return
    }

    // 这里**不能**用 imePadding()。
    //
    // imePadding 会把容器的可用高度压成「屏幕高 − IME 高」，而 screenH 就是从这个容器量出来的，
    // 于是锚点 y = screenH * 0.60 跟着变小、窗组整体上移；但三扇窗的**固定高度没变**，
    // 窗组底边就伸出了「屏幕高 − IME 高」这块可用区。Compose 里超出父约束的
    // Modifier.height 会被 coerce，于是键盘一弹，整组窗被压扁成只剩顶部把手条
    // （真机：输入框和发送键全部消失）。
    //
    // 改成：容器保持整屏、几何按整屏算，再按键盘顶边位置把**整组矩形往上平移**。
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val screenW = screenOverride?.first ?: maxWidth.value
        val screenH = screenOverride?.second ?: maxHeight.value
        val cfg = remember { FloatLayoutConfig() }

        // 默认整组：输入窗在上、菜单居中、输出窗在下（= FloatLayout 的 PANE_ORDER）。
        // 刻意不做成「输入窗在最下」：relayout 的栈序是「anchor 在顶、跟随者向下」这条铁律，
        // 倒过来要么改引擎（跨端分叉）要么在 UI 里私造一份反序排布（又一份会走样的几何）。
        // 所以保留引擎顺序，默认摆偏上；键盘弹起时整组跟着 imePadding 上移。
        // 想换位置直接拖，拖动/吸附/连组都走同一套引擎函数。
        var snap by remember {
            mutableStateOf(
                defaultLayout(
                    // ⚠ 单位：FloatLayout 的矩形是 **px**，不是 dp。首验时我直接把 dp 塞进去，
                    // 结果 116dp 被当成 116px、渲染前再除以 density(3.5) → 每扇窗只剩 33dp 高，
                    // 三扇窗几乎叠在一起。引擎那边一个字没错，是这里少乘了一次。
                    input = FloatRect(
                        (screenW - INPUT_W.value - screenW * ANCHOR_X_RATIO) * density.density,
                        screenH * ANCHOR_Y_RATIO * density.density,
                        INPUT_W.value * density.density,
                        // 一体窗：输出区总高并进 INPUT 这一扇，OUTPUT 传 0。
                        // defaultLayout/relayout/并组三套逻辑因此一行都不用改。
                        (INPUT_H.value + OUTPUT_H.value + PANE_CHROME.value) * density.density
                    ),
                    menuH = MENU_H.value * density.density,
                    outputH = 0f,
                    cfg = cfg,
                )
            )
        }

        PANE_ORDER.forEach { role ->
            val st = snap.panes[role] ?: return@forEach
            // 引擎算的是 px，这里换回 dp 再交给 Compose 布局；钳制同样在引擎里做
            // 可用高度要把导航栏扣掉，否则窗底会压在系统导航栏上（真机：窗底正好盖住
            // 首页/计划那排 tab，遮住它们只露半个字）。clampToDisplay 才钳得住。
            val visibleH = screenH - WindowInsets.navigationBars
                .asPaddingValues().calculateBottomPadding().value
            val displayDp = remember(screenW, screenH, density.density) {
                listOf(FloatDisplay(0, FloatRect(0f, 0f, screenW, visibleH), density.density))
            }
            // 键盘弹出时整组上移，让窗组底边贴住键盘顶边（见上面为什么不能用 imePadding）。
            val imePx = WindowInsets.ime.getBottom(density).toFloat()
            val groupBottomPx = snap.panes.values.maxOfOrNull { it.rect.y + it.rect.height } ?: 0f
            val imeTopPx = screenH * density.density - imePx
            val shiftPx = (groupBottomPx - imeTopPx).coerceAtLeast(0f)
            val rectDp = clampToDisplay(
                FloatRect(
                    st.rect.x / density.density,
                    (st.rect.y - shiftPx) / density.density,
                    st.rect.width / density.density,
                    st.rect.height / density.density
                ),
                displayDp,
            )
            // 只画一体窗。MENU / OUTPUT 都**保留在快照里**（高度 0），
            // 因为 allLinked() 是 PANE_ORDER.every(r -> panes[r]?.linked) ——
            // 从快照里摘掉任何一个，every 就恒为 false，并组会失灵（见 MENU_H 注释）。
            if (role != PaneRole.INPUT) return@forEach
            Pane(
                role = role,
                rectDp = rectDp,
                linked = st.linked,
                sessionId = realId,
                ctx = ctx,
                scope = scope,
                onDragDelta = { dxPx, dyPx ->
                    // 手势增量是 px，引擎矩形也是 px，这里**不换算**
                    val cur = snap.panes[role]?.rect ?: return@Pane
                    snap = movePane(
                        snap, role,
                        cur.copy(x = cur.x + dxPx, y = cur.y + dyPx),
                        cfg
                    )
                },
                onDragEnd = { snap = settleAfterDrag(snap, role, cfg) },
                onResize = { dyPx ->
                    val cur = snap.panes[role]?.rect ?: return@Pane
                    // 下限：留得出输入区 + 一条消息；上限：别挡住整个屏
                    val minH = (INPUT_H.value + 90f + PANE_CHROME.value) * density.density
                    val maxH = screenH * density.density * 0.88f
                    val newH = (cur.height + dyPx).coerceIn(minH, maxH)
                    // 矩形交给引擎改，不在 UI 层自己写几何
                    snap = movePane(snap, role, cur.copy(height = newH), cfg)
                },
                onExit = onExit,
            )
        }

        // 上报并集边界，供后台悬浮窗做「窗外触摸穿透」。
        // snap 是 mutableStateOf，每次拖动/缩放都会变，正好驱动重报。
        LaunchedEffect(snap) {
            val rects = PANE_ORDER.mapNotNull { role -> snap.panes[role]?.rect }
            if (rects.isNotEmpty()) {
                onBounds(
                    androidx.compose.ui.geometry.Rect(
                        rects.minOf { it.x } / density.density,
                        rects.minOf { it.y } / density.density,
                        rects.maxOf { it.x + it.width } / density.density,
                        rects.maxOf { it.y + it.height } / density.density
                    )
                )
            }
        }
    }
}

/**
 * 松手结算：先试吸附到别的窗的边；吸上了且没跑远就算组内，否则自动脱组
 * ——「脱组是存储标志而不是推断出来的」这条铁律在这里体现为：明确决定，不让用户猜。
 */
private fun settleAfterDrag(snap: LayoutSnapshot, role: PaneRole, cfg: FloatLayoutConfig): LayoutSnapshot {
    val me = snap.panes[role]?.rect ?: return snap
    var next = snap
    for (other in PANE_ORDER) {
        if (other == role) continue
        val target = snap.panes[other]?.rect ?: continue
        val hit = snapRect(me, target, cfg.snapThreshold) ?: continue
        next = movePane(next, role, me.copy(x = hit.x, y = hit.y), cfg)
        if (isStillLinked(next, role, cfg)) next = setLinked(next, role, true)
        return relayout(next, cfg)
    }
    if (next.panes[role]?.linked == true && !isStillLinked(next, role, cfg)) {
        next = setLinked(next, role, false)
    }
    return relayout(next, cfg)
}

@Composable
private fun Pane(
    role: PaneRole,
    rectDp: FloatRect,
    linked: Boolean,
    sessionId: Long,
    ctx: FloatSession,
    scope: CoroutineScope,
    onDragDelta: (Float, Float) -> Unit,
    onDragEnd: () -> Unit,
    /** 拖底部把手时的高度增量（px，正数向下变高）。null = 这扇不可调大小。 */
    onResize: ((Float) -> Unit)? = null,
    onExit: () -> Unit,
) {
    Column(
        Modifier
            .offset { IntOffset(rectDp.x.dp.roundToPx(), rectDp.y.dp.roundToPx()) }
            .width(rectDp.width.dp)
            .height(rectDp.height.dp)
            // 20dp 而不是 16dp：反馈「让框框圆滑点」，一体窗体量变大后 16dp 显得方
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surface)
    ) {
        // 每扇窗顶上一条把手：只有拖这里才移动窗身，免得和窗内的滚动/输入打架
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(26.dp)
                .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                .pointerInput(role) {
                    detectDragGestures(
                        onDrag = { change, amount ->
                            change.consume()
                            onDragDelta(amount.x, amount.y)
                        },
                        onDragEnd = { onDragEnd() },
                        onDragCancel = { onDragEnd() }
                    )
                }
        ) {
            Spacer(Modifier.width(6.dp))
            Box(
                Modifier
                    .size(width = 22.dp, height = 4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
            )
            Spacer(Modifier.weight(1f))
            // 只留一个并组按钮（反馈：两个锁定按钮不知道用哪个）。
            // 取舍说明：留下的是「整组连成/拆开」，因为手机端现在就两扇窗；
            // 去掉的是「只把这扇并进组/拆出来」，代价是连成组之后**不能单独挪某一扇窗**，
            // 要改单扇位置得先整组拆开。恢复单扇并组需要给它找入口（长按？菜单？），
            // 因为顶栏只有一个位置了。
            // 锁链按钮删掉了（反馈：一体窗只剩一扇，并组已无意义，留个按不动的按钮
            // 只会误导）。引擎侧的 linked / setAllLinked 保持原样——那是与 PC
            // layout.ts 对齐的跨端契约，PC 端仍有多窗，不能跟着手机端一起删。
            IconButton(onClick = onExit, modifier = Modifier.size(24.dp)) {
                Icon(
                    Icons.Rounded.Close, "收起悬浮窗",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(14.dp)
                )
            }
        }
        when (role) {
            PaneRole.INPUT -> UnifiedPaneBody(sessionId, ctx, scope)
            PaneRole.MENU -> MenuPaneBody()
            PaneRole.OUTPUT -> OutputPaneBody(sessionId)
        }
        // 底部 resize 把手：只有统一窗这扇可调。
        // 放在窗内最底下、而不是压在边框上：窗高变化时把手跟着内容一起移动，
        // 用户手底下始终是同一条带子，不会「拖一下把手飞走」。
        if (onResize != null) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(20.dp)
                    .pointerInput(role) {
                        detectDragGestures { change, amount ->
                            change.consume()
                            onResize(amount.y)
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                Box(
                    Modifier
                        .size(width = 44.dp, height = 3.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
                )
            }
        }
    }
}

@Composable
private fun InputPaneBody(sessionId: Long, ctx: FloatSession, scope: CoroutineScope) {
    // 与主窗共用同一份草稿：收起为悬浮窗不该丢字（PC 侧 R7 踩过这个坑）
    // LocalContext.current 只能在 composable 上下文取，不能在 onClick lambda 里取；
    // 参数名 ctx 已被 FloatSession 占用，这里叫 localCtx。
    val localCtx = LocalContext.current
    var draft by remember { mutableStateOf(ChatDrafts.get(ChatDrafts.key(sessionId, ctx.projectId))) }
    var attachments by remember { mutableStateOf(listOf<String>()) }
    var streaming by remember { mutableStateOf(false) }

    LaunchedEffect(sessionId) {
        ChatEngine.stateOf(sessionId).collect { s ->
            streaming = s.streamingId != null || s.messages.any { it.status == "streaming" }
        }
    }

    // 必须固定高度：一体窗里它在下面、上面是输出区，fillMaxSize 会把输出区挤没。
    Column(
        Modifier
            .fillMaxWidth()
            .height(INPUT_H)
            .padding(horizontal = 8.dp, vertical = 6.dp)
    ) {
        OutlinedTextField(
            value = draft,
            onValueChange = {
                draft = it
                ChatDrafts.set(ChatDrafts.key(sessionId, ctx.projectId), it)
            },
            modifier = Modifier.fillMaxWidth().weight(1f),
            // 显式给 style：Pane 的根是 Column+background 而不是 Surface，
            // 不会提供 LocalTextStyle，placeholder 不写 style 就退回 Compose 默认字体，
            // 与主界面 InputBar 用的 bodyLarge 不一致（真机上「输 入 消 息」字距发虚）。
            placeholder = {
                Text("输入消息…", style = MaterialTheme.typography.bodyLarge)
            },
            maxLines = 3,
            shape = RoundedCornerShape(12.dp)
        )
        if (attachments.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth().padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                attachments.forEach { p ->
                    Text(
                        File(p).name,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                            .clickable { attachments = attachments - p }
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(top = 2.dp)
        ) {
            IconButton(
                onClick = {
                    scope.launch {
                        if (!io.github.m0use001.planability.core.DeviceFeatures.a11y.isBound) {
                            // 服务真没开：直接告诉用户去哪开
                            Toast.makeText(
                                localCtx,
                                "截屏需要无障碍服务：请到 设置→无障碍 里开启「读屏采集（仅手动触发）」",
                                Toast.LENGTH_LONG
                            ).show()
                        } else {
                            val path = withContext(Dispatchers.IO) { grabScreenshot() }
                            if (path != null) {
                                attachments = attachments + path
                            } else {
                                // 以前失败是**静默**的：不弹错误、不留痕，附件区只是少一个 chip，
                                // 用户点了相机什么也没发生，只能理解为「这功能坏了」
                                // （真机反馈：截屏输入失败）。
                                // 服务在开但仍失败：多数是能力位没生效——ServiceInfo 在服务绑定时
                                // 定格，装了带 canTakeScreenshot 声明的新包后需重绑（关一次再开）。
                                Toast.makeText(
                                    localCtx,
                                    "截屏失败：请到 设置→无障碍 把「读屏采集」关一次再开，然后重试",
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        }
                    }
                },
                enabled = !streaming,
                modifier = Modifier.size(30.dp)
            ) {
                Icon(
                    Icons.Rounded.CameraAlt, "截屏附加",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(17.dp)
                )
            }
            Spacer(Modifier.weight(1f))
            IconButton(
                onClick = {
                    if (streaming) {
                        ChatEngine.stop(sessionId)
                        return@IconButton
                    }
                    val marks = attachments.joinToString(" ") {
                        "[附件已附: " + File(it).name + " 请用文件工具读取]"
                    }
                    val text = if (marks.isEmpty()) draft.trim() else (draft.trim() + " " + marks)
                    if (text.isBlank()) return@IconButton
                    draft = ""
                    attachments = emptyList()
                    ChatDrafts.set(ChatDrafts.key(sessionId, ctx.projectId), "")
                    scope.launch { ChatEngine.send(sessionId, text, ctx.assistantId, ctx.projectId) }
                },
                modifier = Modifier.size(30.dp)
            ) {
                Icon(
                    if (streaming) Icons.Rounded.Stop else Icons.AutoMirrored.Rounded.Send,
                    if (streaming) "停止" else "发送",
                    tint = if (streaming) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

/**
 * 菜单窗：以前**只放一行提示文字**，56dp 整扇窗就为这句话（真机反馈：很占位置），
 * 而顶栏那两个链条按钮到底什么意思也没人说得清（反馈：不知道如何使用）。
 *
 * 改成**图例行**：直接把这几个手势/按钮画出来配字，尺寸同时缩到 34dp。
 * 一个改动同时解决「看不懂」和「太占地方」两件事。
 */
/** 菜单窗已整体不渲染（见 MENU_H 的注释），这里保留占位以免调用点报错。 */
/**
 * 一体窗内容：上面是输出区（占满剩余），下面是固定高度的输入区。
 *
 * 要求是「输入框输出框做成一体」，这里**只重新堆叠、不另写一份渲染** ——
 * 沿用原来的 OutputPaneBody/InputPaneBody，否则跟主窗的两处画法会慢慢分叉。
 */
@Composable
private fun UnifiedPaneBody(sessionId: Long, ctx: FloatSession, scope: CoroutineScope) {
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxWidth().weight(1f)) {
            OutputPaneBody(sessionId)
        }
        InputPaneBody(sessionId, ctx, scope)
    }
}

@Composable
private fun MenuPaneBody() {
    Box(Modifier.fillMaxSize())
}

@Composable
private fun OutputPaneBody(sessionId: Long) {
    val state by remember(sessionId) { ChatEngine.stateOf(sessionId) }.collectAsState()
    val listState = rememberLazyListState()
    LaunchedEffect(state.messages.size) {
        if (state.messages.isNotEmpty()) {
            runCatching { listState.animateScrollToItem(0) }
        }
    }
    if (state.messages.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                "还没有消息",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        return
    }
        LazyColumn(
            state = listState,
            reverseLayout = true,
            modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            // 2026-11-12 用户实录（「工具运行时还是缺运行中动画与计时，看起来跟卡了一样」）：
            // 浮窗此前**只有参数生成期**的 ThinkingRow（随 MessageBubble 渲染），工具**执行**
            // 期间（streamingId 指向带 toolCallsJson 的行）整窗静止、无任何信号。与主窗
            // ChatScreen 同款 LiveToolRow（reverseLayout 首个 item = 视觉底部），挂载条件
            // 一致：streamingId 行带 toolCallsJson。复用主窗同一份画法（见 ChatScreen）。
            val streamRow = state.messages.firstOrNull { it.id == state.streamingId }
            // 2026-11-14：与主窗同步补上**参数生成期**（callingTool 已到、toolCallsJson 还没落）——
            // 模型写文件时把上千字正文当参数吐出来，这期间浮窗此前整窗静止（用户以为卡死）。
            val argsPhase = streamRow != null &&
                streamRow.toolCallsJson.isBlank() && state.callingTool.isNotBlank()
            if (state.streamingId != null && streamRow != null &&
                (streamRow.toolCallsJson.isNotBlank() || argsPhase)
            ) {
                item(key = "live-tools") {
                    LiveToolRow(streamRow, state.messages, callingTool = state.callingTool)
                }
            }
            items(state.messages.asReversed(), key = { it.id }) { msg ->
            // 复用主窗同一套气泡渲染，不另写简化版——否则同一条消息两处画法会慢慢分叉
            MessageBubble(
                msg = msg,
                isLastMsg = false,
                onLongPress = {},
                userTexts = emptyList(),
                onSendCard = {},
                onExportCode = { _, _ -> },
                onOpenFile = {},
                callingTool = state.callingTool
            )
        }
    }
}

/**
 * 截屏落盘并返回绝对路径。
 *
 * 复用读屏采集服务的 takeScreenBitmap()（设备功能包提供的无障碍截屏通道），
 * **不引入 MediaProjection**：那要用户额外授权一次弹窗，而这里要的只是「把当前屏幕存成附件」。
 * 服务没开/失败一律返回 null——调用方不弹错误，附件区就是少一个 chip，不打断正在输入的人。
 */
private fun grabScreenshot(): String? = runCatching {
    // 2026-11-26 开源根治：截屏通道经 DeviceFeatures.a11y（公开版 NoOp=null，附件区少一个 chip）
    val bmp = io.github.m0use001.planability.core.DeviceFeatures.a11y.takeScreenBitmap() ?: run {
        android.util.Log.w("FloatShot", "grabScreenshot: 无障碍截图通道不可用")
        return@runCatching null
    }
    val dir = File(Graph.appContext.filesDir, "float_shots").apply { mkdirs() }
    val f = File(dir, "shot_${System.currentTimeMillis()}.png")
    java.io.FileOutputStream(f).use { out -> bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out) }
    bmp.recycle()
    android.util.Log.w("FloatShot", "grabScreenshot: 已落盘 ${f.absolutePath} ${f.length()}B")
    f.absolutePath
}.getOrNull()