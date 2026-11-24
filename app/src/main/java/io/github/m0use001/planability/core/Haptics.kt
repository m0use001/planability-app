package io.github.m0use001.planability.core

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import kotlin.math.roundToInt

/**
 * 语义化触感反馈引擎，经 LocalHaptics 在主题入口注入。
 * intensityProvider 返回 0–150%（100% = 默认手感；0 = 静音；>0 时原语档位交给系统强度设置缩放）。
 *
 * ───────────────────────── 本项目 HIG 映射表 ─────────────────────────
 * 对齐 Apple HIG *Feedback* / playing-haptics 的口径：**同一种语义在全 app 只有一种手感**，
 * 用户摸一两下就能反推"这是选中 / 这是不可逆 / 这是出错了"。
 *
 * | HIG 概念                      | 我们的 Kind    | 硬件原语（2026-10-25 真机调档） | 用在哪（代表调用点） |
 * |-------------------------------|----------------|-------------------------------|--------------------|
 * | selection（选择）             | `Select`       | EFFECT_TICK（最轻）           | chip、二级 tab、switch、分段按钮、滑块刻度 |
 * | impact · impactLight（轻冲击）| `ImpactLight`  | EFFECT_CLICK（中）            | 弹层出现、开始专注；列表**到顶** |
 * | impact · impactMedium（中冲击）| `Rigid`       | EFFECT_CLICK（中）            | 底部导航切换等"有分量"的点击 |
 * | impact · impactHeavy（重冲击）| `ImpactHeavy`  | EFFECT_HEAVY_CLICK（最重）    | 破坏性确认（删除）；列表**到底** |
 * | notify · success（成功）      | `Success`      | EFFECT_DOUBLE_CLICK（结构）   | 保存/导出/打卡成功 |
 * | notify · error（错误）        | `Error`        | 双段波形（急促平直，保留结构）| 失败、放弃、中断 |
 * | notify · warning（警告）      | 暂复用 `Error` | （暂无独立手感，需要时再拆）  | — |
 * | —（HIG 无对应，本项目自定）   | `Tap`          | EFFECT_TICK（与 Select 同轻档）| 顶栏返回、展开/收起这类中性点击 |
 *
 * ───────────── 2026-10-25 真机调档：为什么从自定义波形换成硬件原语 ─────────────
 * OPPO PLK110（ColorOS 16）的 VibratorControlService 会把第三方应用的自定义
 * oneshot/waveform **归一成它自己的预置原语**（dumpsys vibrator_manager 实证）：
 * Select(7ms) → effect68/30ms，其余全部（Tap 10ms / ImpactLight 12ms / Rigid 18ms /
 * ImpactHeavy 28ms）→ effect1/45ms 同一段——五档波形在这台日用机上**塌成两档**，
 * 我们精心拉开的时长/振幅（相邻档 ≥1.4×）完全被吞掉。
 * 换 `VibrationEffect.createPredefined`（硬件原语）后同一台机器实测三档分明：
 * TICK=35ms@1600 < CLICK=48ms 强 < HEAVY_CLICK=98ms 强（DOUBLE_CLICK 也保留结构）。
 * 代价与原则：物理上只有 3+1 档，七个 Kind 按"感知重量"就近合并——
 * Select/Tap→TICK、ImpactLight/Rigid→CLICK、ImpactHeavy→HEAVY、Success→DOUBLE、
 * Error 保留波形结构（原语没有"急促双段"，失败场景宁可塌到中档也不占用 DOUBLE）；
 * 不支持原语的设备由系统自动回退合成波形；`tap/impactLight/impactMedium` 两两同档
 * 是**接受的取舍**（有区分的两档 > 名义上五档实际两档）。语义唯一性不变：同一种
 * 语义在全 app 只有一种手感，调用点不需要知道这些。
 * `Success`/`Error` 更靠**波形结构**（段数、间隔、是否上扬）区分，不靠"更重"。
 *
 * ───────────── 与 M3E 弹簧动效的搭配（2026-10-23，动效迁移见 ui/common/Motion.kt）─────────────
 * 触感给在**触发时刻**（点击/手势阈值）而不是动画落位——这条在弹簧体系下**不变**，
 * 双击感最讨嫌。唯一新增的落位触感是**展开**：内容被弹簧推出来之后给一发最轻的 `Select`
 * （AnimatedVisibility 的 `onExpanded`，调用点 ChatScreen 两处 / PlanScreen 逐日明细）；
 * 收起 = 回到原状，不配。档位↔触感对照：quick→Tap 或无；standard→Select；emphasized→ImpactLight（弹层出现）。
 * ────────────────────────────────────────────────────────────────
 */
class HapticEngine(
    context: Context,
    private val intensityProvider: () -> Float = { 1f }
) {

    enum class Kind {
        /** 轻触：顶栏返回、展开/收起这类中性点击 —— EFFECT_TICK，与 Select 同轻档（HIG 无对应）。 */
        Tap,
        /** 选择：chip / 二级 tab / switch / 分段按钮 / 滑块刻度 —— EFFECT_TICK 全场最轻，选中是高频动作，重了两下就烦。 */
        Select,
        /** 硬冲击：底部导航等"有分量"的点击 —— EFFECT_CLICK，对应 HIG impactMedium。 */
        Rigid,
        /** 轻冲击：弹层出现、列表到顶 —— EFFECT_CLICK（与 Rigid 同中档），摸起来是"到了"而不是"撞上了"（HIG impactLight）。 */
        ImpactLight,
        /** 重冲击：破坏性确认、列表到底 —— EFFECT_HEAVY_CLICK 最实的一下，"没得再去了 / 这一步不可逆"（HIG impactHeavy）。 */
        ImpactHeavy,
        /** 错误：失败/放弃/中断 —— 等重双段波形 30ms + 40ms 短间隔，急促平直像报警，结构上与成功截然不同。 */
        Error,
        /** 成功：保存/导出/打卡成功 —— EFFECT_DOUBLE_CLICK，"叮—咚"双击结构，与 Error 的参数/结构都不同。 */
        Success
    }

    private val vibrator: Vibrator? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }

    /**
     * 轻/中两档的物理原语，按厂牌校准（2026-10-25 OPPO PLK110 真机 dumpsys 实证）：
     * ColorOS（Oplus 系）把 EFFECT_CLICK 映到它自家的轻段（effect0/35ms/强度1600），
     * 把 EFFECT_TICK 映到更重的 TICK(STRONG)/44ms —— 与 AOSP 的轻重大 противополож。
     * 所以 Oplus 上轻档用 CLICK、中档用 TICK；原生系（TICK<CLICK<HEAVY）维持标准映射。
     */
    private val lightPrimitive: Int
    private val mediumPrimitive: Int

    init {
        val oplus = Build.MANUFACTURER.let { m ->
            m.contains("OPPO", true) || m.contains("OnePlus", true) || m.contains("realme", true)
        }
        lightPrimitive = if (oplus) VibrationEffect.EFFECT_CLICK else VibrationEffect.EFFECT_TICK
        mediumPrimitive = if (oplus) VibrationEffect.EFFECT_TICK else VibrationEffect.EFFECT_CLICK
    }

    fun perform(kind: Kind) {
        val v = vibrator ?: return
        val i = intensityProvider().coerceIn(0f, 1.5f)
        if (i <= 0f) return

        when (kind) {
            // 硬件原语（2026-10-25 真机调档，理由见类注释与 lightPrimitive 校准注释）：
            // 直接请求原语才能保住轻/中/重三档的区分度；不支持原语的设备由
            // createPredefined 自动回退合成波形。
            Kind.Select, Kind.Tap ->
                v.vibrate(VibrationEffect.createPredefined(lightPrimitive))
            Kind.ImpactLight, Kind.Rigid ->
                v.vibrate(VibrationEffect.createPredefined(mediumPrimitive))
            Kind.ImpactHeavy ->
                v.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_HEAVY_CLICK))
            Kind.Success ->
                v.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_DOUBLE_CLICK))
            // Error 保留双段波形（等重 30ms×2、间隔 40ms 的急促报警结构）：
            // 原语里没有"急促双段"，宁可让 ColorOS 塌到中档，也不占用 Success 的 DOUBLE 结构
            Kind.Error -> v.vibrate(
                VibrationEffect.createWaveform(
                    longArrayOf(0, 30, 40, 30),
                    intArrayOf(0, 255, 0, 255), -1
                )
            )
        }
    }
}

/**
 * 滑块的「刻度」触感：数值每跨过一格振一次 [HapticEngine.Kind.Select]。
 *
 * 为什么按格振、而不是每次 onValueChange 都振：一次拖动能产生上百个回调，
 * 每次都振只会连成一片麻木感（像手机在抖），信息量反而是零；
 * 只有"跳了一档"才值得反馈，也才和视觉上的刻度对得上。
 * 为什么用全场最轻的 Select：滑块是持续调节，反馈必须比点击更轻，否则拖两下就嫌吵。
 *
 * 用法：`val ticks = remember(haptics) { HapticTicks(haptics, 10f) }`，
 * 在 `onValueChange` 里先 `ticks.onValue(v)` 再更新状态。
 */
class HapticTicks(private val engine: HapticEngine, private val step: Float) {
    private var bucket = Int.MIN_VALUE

    fun onValue(value: Float) {
        val b = if (step > 0f) (value / step).roundToInt() else 0
        if (b == bucket) return
        bucket = b
        // 首次跨格也振：onValueChange 只由用户拖动触发，起手给一下是对操作的即时确认
        engine.perform(HapticEngine.Kind.Select)
    }
}

val LocalHaptics = staticCompositionLocalOf<HapticEngine> {
    error("HapticEngine not provided")
}

/**
 * 列表滑到尽头（顶部/底部）各触发一次触感，**顶和底用不同手感**：
 * 顶 = [HapticEngine.Kind.ImpactLight]（轻，"回到开头了"）、
 * 底 = [HapticEngine.Kind.ImpactHeavy]（实，"内容到底、没得再去了"）——
 * 两端同一档的话用户摸不出自己在哪一端，筛选结果是"少了一条"还是"多了一条"全靠猜。
 * 只有**滚动手势在场**时撞到尽头才振（见 [edgeHapticPair] 内注释）：
 * 筛选切换、增删条目这类内容变化把列表顶到尽头不算，19 个调用点共用这一份实现，行为天然一致。
 */
fun Modifier.reachedEndHaptic(state: androidx.compose.foundation.lazy.LazyListState): Modifier =
    composed {
        edgeHapticPair(
            atStart = { !state.canScrollBackward },
            atEnd = { !state.canScrollForward },
            isScrolling = { state.isScrollInProgress }
        )
        this
    }

/** 网格（LazyVerticalGrid）版：与列表同语义。 */
fun Modifier.reachedEndHaptic(state: androidx.compose.foundation.lazy.grid.LazyGridState): Modifier =
    composed {
        edgeHapticPair(
            atStart = { !state.canScrollBackward },
            atEnd = { !state.canScrollForward },
            isScrolling = { state.isScrollInProgress }
        )
        this
    }

/** 普通 verticalScroll（Column）版：抽屉会话树、首页时间轴等非懒加载滚动面。 */
fun Modifier.reachedEndHaptic(state: androidx.compose.foundation.ScrollState): Modifier =
    composed {
        edgeHapticPair(
            atStart = { !state.canScrollBackward },
            atEnd = { !state.canScrollForward },
            isScrolling = { state.isScrollInProgress }
        )
        this
    }

@Composable
private fun edgeHapticPair(
    atStart: () -> Boolean,
    atEnd: () -> Boolean,
    isScrolling: () -> Boolean
) {
    val haptics = LocalHaptics.current
    // 2026-10-30 重写触发条件：尽头触感只认**滚**出来的尽头，不认内容变化顶出来的。
    // 旧版用 armed 旗（"离开过尽头再回来就振"），但筛选切换、勾掉待办这类**内容变化**
    // 同样会让 canScrollForward/Backward 翻转——列表换成短的瞬间两头"到边"，旗子齐响：
    // 计划页切「今天⇄已归档」真机 dumpsys 实证每切一次多出 HEAVY_CLICK+CLICK 两发，
    // 而且 HEAVY_CLICK 与 Select 同帧起振时还会把它 cancelled_superseded 顶掉，
    // 用户本该摸到的最轻选中触感整个被吃掉。
    //
    // 但只看触发瞬间的 isScrollInProgress 也不够：快甩正好停在尽头上时，惯性在
    // 尽头翻转的同一帧（或前一帧）结束，等 effect 跑起来滚动已经"不在场"——
    // 真机实测三次快甩回顶都没振（撞到底的那次反而振了，因为惯性还有余量在夹持）。
    // 所以补 150ms 宽限：刚停下的滚动仍算"滚出来的尽头"。scrollSeen 只在真的
    // 观察到过滚动后才记停点，初帧（从没滚过）不会被误记——首帧防误振比旧版更严。
    val scrolling by remember { derivedStateOf { isScrolling() } }
    var scrollSeen by remember { mutableStateOf(false) }
    var lastScrollEndMs by remember { mutableStateOf(0L) }
    // 2026-11-03 竞争根修（用户「我的页滑到底不震」，模拟器 EdgeHaptic 日志铁证）：
    // 旧版 end/scrolling 各挂一个 LaunchedEffect——快照批量应用时「end 翻转」与
    // 「scrolling 停止」同帧到达，end 的 effect 先执行时 150ms 宽限时间戳还没被
    // scrolling 的 effect 记下（仍是极旧的 0L），条件不满足且 end 不再变化=永远错过。
    // 收进**单一事件流**：记账与判定在同一 collect 里顺序执行，同帧合并也不再竞争。
    // 触发语义不变：只在 end/start **翻转**时振 + 150ms 宽限 + 抑制窗口。
    var prevEnd by remember { mutableStateOf(false) }
    var prevStart by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        snapshotFlow { Triple(atEnd(), atStart(), isScrolling()) }.collect { (e, s, sc) ->
            val now = System.currentTimeMillis()
            if (sc) {
                scrollSeen = true
            } else if (scrollSeen) {
                lastScrollEndMs = now
                scrollSeen = false
            }
            val grace = sc || now - lastScrollEndMs <= 150
            val suppressed = ScrollHapticSuppress.active()
            if (e && !prevEnd && grace && !suppressed) {
                // 到底：撞到"墙"了 —— 用最实的重档（HEAVY_CLICK）
                android.util.Log.i("EdgeHaptic", "fired END (scrolling=$sc)")
                haptics.perform(HapticEngine.Kind.ImpactHeavy)
            }
            if (s && !prevStart && grace && !suppressed) {
                // 到顶：只是回到开头 —— 用中档（CLICK），明显比到底轻一档
                android.util.Log.i("EdgeHaptic", "fired START (scrolling=$sc)")
                haptics.perform(HapticEngine.Kind.ImpactLight)
            }
            prevEnd = e
            prevStart = s
        }
    }
}

/**
 * 程序滚动的端点触感抑制窗口（2026-11-03 用户「自动书签上下滑动时突然出现震动」）：
 * 书签跳转的 scrollToItem/scrollBy 是**程序滚动**——isScrollInProgress 同样为 true、
 * 撞到列表端点时「滚出来的尽头」条件全满足，用户就摸到一声莫名其妙的震动。
 * 跳转入口把窗口拨到 now+时长，端点触感在窗口内静默；手势滚动不受影响。
 */
object ScrollHapticSuppress {
    @Volatile internal var untilMs: Long = 0L
    fun suppress(durationMs: Long = 800L) {
        untilMs = System.currentTimeMillis() + durationMs
    }
    internal fun active(): Boolean = System.currentTimeMillis() < untilMs
}

/**
 * 程序化滚动的**语义**标记（2026-11-17 用户「点『最新』有时不会自动到底部，常常中途卡住」）。
 *
 * 与 [ScrollHapticSuppress] 只管「别震」不同，这个标记告诉上层**这次滚动不是用户干的**：
 * 聊天列表的 `autoFollow` 判据是「滚动刚结束、又没停在底部 → 用户主动离开了底部 → 停跟随」，
 * 而「贴底」本身就是程序滚动——只要这一轮因为消息还在异步进场而停在半路，跟随就被误关，
 * 之后既没人继续追、胶囊也不会自己消失，观感正是「点了没到底、也不跟了」。
 *
 * 程序滚动入口先 [begin] 拨一个时间窗，窗口内「停在半路」不算用户离开底部。
 */
object ProgrammaticScroll {
    private val depth = java.util.concurrent.atomic.AtomicInteger(0)
    @Volatile private var graceUntilMs: Long = 0L

    fun begin() {
        depth.incrementAndGet()
    }

    fun end() {
        if (depth.decrementAndGet() < 0) depth.set(0)
        // 只留一小段收尾宽限：Lazy 的 settle 可能比返回稍晚。窗口太长会把用户
        // 紧接着的手动上滑也当成"程序滚动中"，从而把跟随错误地保持开着。
        graceUntilMs = System.currentTimeMillis() + 400
    }

    fun active(): Boolean =
        depth.get() > 0 || System.currentTimeMillis() < graceUntilMs
}

/**
 * Compose 自带触感（LocalHapticFeedback）→ HapticEngine 桥：
 * 让 Compose 组件内部触感与全局强度设置一致。
 */
class HapticEngineFeedback(private val engine: HapticEngine) : androidx.compose.ui.hapticfeedback.HapticFeedback {
    override fun performHapticFeedback(hapticFeedbackType: androidx.compose.ui.hapticfeedback.HapticFeedbackType) {
        when (hapticFeedbackType) {
            androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress ->
                engine.perform(HapticEngine.Kind.ImpactLight)
            androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove ->
                engine.perform(HapticEngine.Kind.Tap)
            else -> engine.perform(HapticEngine.Kind.Tap)
        }
    }
}
