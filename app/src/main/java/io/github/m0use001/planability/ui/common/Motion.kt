package io.github.m0use001.planability.ui.common

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import android.provider.Settings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.material3.MotionScheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp

/**
 * 全局动效规范 —— **动效改造的唯一真相源**，现在是 **M3 Expressive 弹簧体系**。
 *
 * 为什么要有这个对象：任何动画都从这里取参数，任何调用点都不许再写字面毫秒
 * （`MotionConventionTest` 钉住这条）。
 *
 * ───────────────── M3 Expressive 迁移（2026-10-23，phone-c） ─────────────────
 *
 * 三档现在**直接从 `MotionScheme.expressive()` 取官方弹簧**（material3 1.5.0-beta02 起公开，
 * 见 `gradle/libs.versions.toml` 注 + `Theme.kt`）——不是我们拍脑袋选的物理参数：
 *
 * | 档 | 官方规格 | dampingRatio / stiffness | 用在哪 |
 * |---|---|---|---|
 * | quick | fastSpatial | 0.6 / 800 | 收起/取消/看门狗回弹——起步最猛、轻微过冲，"立刻回位还带一下弹性" |
 * | standard | defaultSpatial | 0.8 / 380 | 二级 tab/分段内容、展开、全屏子页程序化关闭——标准落位 |
 * | emphasized | slowSpatial | 0.8 / 200 | 弹层进入（motionEnter）、大块位移——慢、有分量、收尾轻过冲 |
 *
 * 透明度/颜色这类**非空间**动画走 effects 规格（critical damping，1.0 / 3800·1600·800）——
 * 官方口径就是"位移可以过冲、透明度不许闪"，fadeIn/fadeOut 用它们。
 *
 * ───────────────── 三档时长的新角色 ─────────────────
 *
 * [quickMs]/[standardMs]/[emphasizedMs] 不再是动画的驱动参数（弹簧按物理积分推进，没有固定毫秒），
 * 降级为该档**自然沉降的参考时长**——保留它们是为了：
 * 1. `MotionConventionTest` 的档位间距断言仍然可查（120/180/300，相邻 ≥1.3×）；
 * 2. 调参/排障时知道"这一档大概多少毫秒落定"（spring 的 90% 沉降近似值）。
 * 弹簧参数变了要同步校准参考毫秒，别让它俩差出一个量级。
 *
 * ───────────────── 触感搭配 ─────────────────
 *
 * 全 app 的触感都在**触发时刻**给（点击/手势阈值），不在动画落位给——这是既有约定
 * （Haptics.kt 表格），迁移弹簧后**不改**。唯一的例外是**展开落位**：
 * 内容被弹簧推出来、下方元素被"推"开之后，补一发最轻的 `Select` 表示"多了的东西到了"
 * （AnimatedVisibility 没有 onExpanded 回调——监听 scope 的 `transition`：`currentState` 到 Visible
 * 且 `isRunning` 归 false = 落定，见三处调用点）；收起不配（回到原状不该有存在感）。
 * 档位↔触感对照：quick→无或 Tap；standard→Select（内容切换）；emphasized→ImpactLight（弹层出现）。
 *
 * ───────────────── 为什么弹簧天然适配高刷 ─────────────────
 *
 * 所有动画都按**时间/物理积分**逐帧推进，没有一处用 `delay()` 轮询驱动 UI，
 * 60Hz/120Hz 走的是同一条曲线、只是采样点更密；会改变布局的动画必须配
 * `animateContentSize`/`expandVertically`，让尺寸也逐帧过渡。
 */
object Motion {

    /** 官方 expressive 弹簧的唯一来源；档位映射错了，`MotionSpringSpecTest` 会红。 */
    private val expressive = MotionScheme.expressive()

    /** 微反馈档（≈120ms 沉降）—— 收起、取消、看门狗回弹：用户已知道结果，越干脆越好。fastSpatial：0.6/800，起步猛、轻过冲。 */
    const val quickMs = 120

    /** 常规档（≈180ms 沉降）—— 二级 tab/分段切换、展开、全屏子页程序化关闭。defaultSpatial：0.8/380，标准落位。 */
    const val standardMs = 180

    /** 强调档（≈300ms 沉降）—— 弹层进入等大块位移：位移大要看得清来路。slowSpatial：0.8/200，慢而沉。 */
    const val emphasizedMs = 300

    /**
     * wobble 档（≈200ms 沉降）—— 状态翻转的一次性「抖一下」。注意它是**过冲强度档**，
     * 不是节奏档：quick/standard/emphasized 区分的是"多快落定"，wobble 区分的是
     * "过冲多明显"（MediumBouncy 级 0.5/380，冲过头、回弹一次再落定）——
     * 沉降时长恰好落在 standard 与 emphasized 之间只是巧合，不做 1.3× 间距要求。
     * 只配"状态翻转值得被看见"的时刻（置顶/勾选完成/打卡成功），见 [wobbleOn] 的红线。
     */
    const val wobbleMs = 200

    /** 常规缓动 = Material 标准曲线（FastOutSlowIn）。弹簧体系下不再驱动动画，留作对照与非弹簧场合备用。 */
    val standardEasing: Easing = FastOutSlowInEasing

    /** 强调缓动 = Material emphasized 曲线 (0.2, 0, 0, 1)。同上：对照/备用，弹层的实际曲线由 slowSpatial 弹簧决定。 */
    val emphasizedEasing: Easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)

    /** quick 档弹簧（fastSpatial）—— 收起/回弹/纯位移类微反馈。 */
    fun quick(): FiniteAnimationSpec<Float> = expressive.fastSpatialSpec()

    /** standard 档弹簧（defaultSpatial）—— 二级 tab、分段、展开、全屏子页关闭。 */
    fun standard(): FiniteAnimationSpec<Float> = expressive.defaultSpatialSpec()

    /** emphasized 档弹簧（slowSpatial）—— 弹层进入等大面积动效：慢、沉、收尾轻过冲。 */
    fun emphasized(): FiniteAnimationSpec<Float> = expressive.slowSpatialSpec()

    /**
     * wobble 档弹簧 —— 状态翻转的一次性「抖一下」：MediumBouncy 级过冲（0.5）+
     * defaultSpatial 刚度（380），明显冲过头、回弹一次再落定。与三档节奏弹簧是两个维度：
     * 那 三档管"多快落定"，这根管"抖得多明显"。唯一调用入口是 [Modifier.wobbleOn]，
     * 别拿它驱动常规进出场（那会回到 expressive 审查前的花哨态）。
     */
    fun wobble(): FiniteAnimationSpec<Float> = spring(dampingRatio = 0.5f, stiffness = 380f)

    /**
     * 页级横滑弹簧（NavShell 底栏页签切换专用，2026-11-08 用户拍板 tween→弹簧）——
     * **临界阻尼（damping 1.0，零过冲）** + defaultSpatial 同款刚度（380）。
     *
     * 为什么页级偏偏不过冲、而元素级（胶囊/行）允许轻过冲：这是**整屏页面**的位移，
     * 过冲意味着先滑过头、露出隔壁页的边缘再弹回来——整屏切换会显得"不稳"。
     * 刚度与 standard 同源（380），短距离沉降 ≈200ms、与被替换的 tween 版量级一致；
     * 弹簧按物理积分，天然满足"长距离靠初速快解决、不靠拉长时间"（10-29 拍板），
     * 那个分段加时公式随之退役。唯一调用点 NavShell.slideSwitchTo。
     */
    fun pageSlide(): FiniteAnimationSpec<Float> = spring(dampingRatio = 1f, stiffness = 380f)

    /**
     * standard 档，按**任意值类型**取弹簧——`slideInHorizontally`/`expandVertically` 这类
     * 接口要的是 `FiniteAnimationSpec<IntOffset>` / `<IntSize>`，用不了上面三个 Float 版。
     * 参数与 [standard] 同源（同一个 defaultSpatialSpec），只是把类型参数让给调用点。
     * 2026-10-23 加：PlanScreen 分段切换从 Crossfade 改 AnimatedContent 时补的入口。
     */
    fun <T> standardSpatial(): FiniteAnimationSpec<T> = expressive.defaultSpatialSpec()

    /** quick 档，按**任意值类型**取弹簧——Dp 版给 PillNavBar 内边距这类非 Float 尺寸（参数与 [quick] 同源）。 */
    fun <T> quickSpatial(): FiniteAnimationSpec<T> = expressive.fastSpatialSpec()

    /**
     * 容器尺寸弹簧（泛型）—— `SizeTransform` / `animateContentSize` 的标准接法（v57 批 0 收口）：
     * 「同一容器里两组内容互换 / 行数变化」时容器宽度高度按 defaultSpatial 收放（轻微过冲=胶囊"坐下去"）。
     * 与 [standardSpatial] 同一根官方弹簧，只是语义分工：spatial 给进出场交接，sizeSpring 给容器尺寸收放。
     * 全仓首例 = 笔记列表岛的模式切换（NotesScreen SizeTransform）——推广见动效推广计划 A 类。
     */
    fun <T> sizeSpring(): FiniteAnimationSpec<T> = expressive.defaultSpatialSpec()

    /** quick 档 effects（fastEffects 1.0/3800）——透明度类"快淡出/快淡入"，不过冲。二级页 fade-through、内容快速淡入用它。 */
    fun quickEffects(): FiniteAnimationSpec<Float> = expressive.fastEffectsSpec()

    /** standard 档 effects（defaultEffects 1.0/1600）——透明度类标准淡入淡出。 */
    fun standardEffects(): FiniteAnimationSpec<Float> = expressive.defaultEffectsSpec()

    /**
     * effects 档，按**任意值类型**取弹簧——`animateColorAsState` 这类接口要
     * `AnimationSpec<Color>`，Float 版喂不进去（AppButtonGroup 内容色随选中态过渡）。
     * 参数与 [standardEffects] 同源（同一个 defaultEffectsSpec，1.0/1600，不过冲）。
     */
    fun <T> effects(): FiniteAnimationSpec<T> = expressive.defaultEffectsSpec()

    /**
     * 展开进场 = 淡入（defaultEffects，不过冲）+ 纵向展开（defaultSpatial，带弹性落位）。
     * 为什么不能只做淡入：只淡入的话，内容在第一帧就占满高度、下方元素被瞬间顶下去，
     * 看起来像"闪现"；`expandVertically` 让高度也逐帧过渡，下面的卡片是被**推**下去的。
     */
    val expandIn: EnterTransition =
        fadeIn(expressive.defaultEffectsSpec()) +
            expandVertically(expressive.defaultSpatialSpec())

    /**
     * 收起出场 = 纵向收缩（fastSpatial）+ 淡出（fastEffects）。
     * 收起比展开快是故意的：收起 = 回到原状，用户已经看过内容，"干脆消失"比"对称的慢"更利索。
     */
    val collapseOut: ExitTransition =
        shrinkVertically(expressive.fastSpatialSpec()) +
            fadeOut(expressive.fastEffectsSpec())

    /**
     * 内容尺寸变化（`Modifier.animateContentSize`）用 spring。
     * 为什么：尺寸变化的**终点取决于内容**（几行文本、几个步骤），tween 是定长曲线，
     * 内容多时动画没走到位就被截断、内容少时又拖尾；spring 按"离目标还差多少"推进，
     * 大小两种情况都在各自合适的节奏里收住。参数与官方 defaultSpatial 对齐
     * （dampingRatio 0.8 / stiffness 380，轻微过冲=expressive 的"东西坐下去"）。
     */
    val contentSize: FiniteAnimationSpec<IntSize> = sizeSpring()
}

/**
 * 弹层（AlertDialog 等独立窗口）的统一进入：快速淡入 + 轻微上移落位（[Motion.standard] =
 * defaultSpatial/defaultEffects 档，≈180ms）。
 *
 * 档位依据（第二轮审查 P2-3，2026-10-24）：官方速度表把"部分遮挡屏幕的组件"（弹窗、bottom
 * sheet）归 **Default** 档，slow 档是全屏页——此前用 emphasized(slowSpatial) 是档位用错。
 * 同时**去掉了原来的 94%→100% 缩放**：规范明文 "Scale and z-axis motion is avoided since they
 * imply elevation change"，位移与淡入就够了。位移走 spatial（轻微过冲的弹性落位），
 * alpha 走同一根弹簧、越界由 graphicsLayer 钳到 1。
 *
 * 为什么必须是 Modifier 而不是 `AnimatedVisibility`：AlertDialog 开在**独立窗口**里，
 * 把宿主组合的 AnimatedVisibility 包在外面时，那层 alpha/位移作用在宿主窗口的空节点上，
 * 屏幕上什么都不会发生；只有作用到弹窗窗口**内部的 Surface**（= AlertDialog 的 modifier 参数）才看得见。
 *
 * 为什么只做进场、不做出场：出场要拦住 `onDismiss` 等动画跑完，会把"点外面 = 立刻关"变成"点外面还要等动画"，
 * 属于改变交互语义，宁可不做（动画不改语义是底线）。
 *
 * 为什么用 Animatable 而不是状态切换：alpha/位移在 `graphicsLayer {}` 里逐帧读同一个 Animatable，
 * 每帧只刷图层不重排 —— 120Hz 屏上进场是"一帧一帧淡进去"而不是靠重组硬怼。
 */
fun Modifier.motionEnter(): Modifier = composed {
    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) { progress.animateTo(1f, Motion.standard()) }
    graphicsLayer {
        val p = progress.value.coerceIn(0f, 1f)
        alpha = p
        translationY = 8.dp.toPx() * (1f - p)
    }
}

/**
 * 按压缩放反馈（M3 Expressive 的 "squish"）：按住缩到 [pressedScale]，松手回弹。
 *
 * 为什么自包含指针检测、不走 InteractionSource：material3 1.5.0-beta02 的
 * Button/IconButton/FAB/Chip/Switch 全部在内部**直接调自己的 `ripple()`**
 * （SurfaceKt 字节码实证，不经 `LocalIndication`）——想全局接管按压指示就必须改
 * 每个调用点的 interactionSource 传参（全 app 700+ 处，不现实）。改成自己看按下/抬起：
 * 调用点只要在原有 modifier 链上多挂一个 `.pressScale()`，零传参。
 *
 * 手感 = [Motion.quick]（fastSpatial 弹簧，起步猛、轻过冲）：按下"陷进去"、
 * 松手"弹回来"都是同一条曲线；透明度类动画不过冲的规范管不到缩放——位移类允许过冲。
 *
 * 不加触感：触感在**触发时刻**由调用点按 core/Haptics.kt 的语义表给（点击成功那一下），
 * 这里只管按压期间的视觉形变，两者各司其职。
 *
 * 与底下的 ripple 共存：缩放走 graphicsLayer（不动布局、不裁剪），水波仍按原 bounds 画，
 * 按下瞬间缩放 4% 的差异在水波之上几乎不可感，两层反馈叠加正是 expressive 的层次。
 *
 * **摆放铁律**：graphicsLayer 只包住链上比它靠里的绘制。挂在裸卡片链
 * （`clip().background().clickable()` 那种）时必须放在 `.clip()` **之前**——
 * 放后面只缩得到文字、缩不到底块（模拟器 bbox 实测踩过）。传给 Button/IconButton/
 * FilterChip 这类组件的 modifier 参数则天然正确（组件内部绘制都在它里面）。
 */
fun Modifier.pressScale(pressedScale: Float = 0.96f): Modifier = composed {
    var pressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (pressed) pressedScale else 1f,
        animationSpec = Motion.quick(),
        label = "pressScale"
    )
    // 不消费任何事件，不影响原点击语义；waitForUpOrCancellation 在滑出边界或
    // 被滚动抢走事件时返回 null（无参版本：任何事件被别的手势消费即视为取消），
    // 同样收手回弹。
    pointerInput(Unit) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false)
            pressed = true
            waitForUpOrCancellation()
            pressed = false
        }
    }.graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
}

/**
 * 一次性「抖一下」（v57 批 0）：[trigger] 变化时 scale 沿 [Motion.wobble] 弹簧做一次
 * 1 → [scaleTo] → 1 的往返——两段都带过冲：胀出去轻微超调、缩回来轻微回陷，
 * 即用户说的「非线性动画（例如"抖"一下）」（2026-11-07 拍板推广，全仓第一个调用点
 * 是笔记行的置顶翻转）。
 *
 * 用在哪：**状态翻转值得被看见**的时刻（置顶/取消、勾选完成、打卡成功、模式切换）。
 * 红线（设计文档 §4 D 类，10 条）：
 * - 一个触发时刻只配一次 wobble，同屏两个以上 wobble 源 = 滥用；
 * - 只碰 scale/位移/尺寸，alpha/颜色不过冲（官方口径）；
 * - 页级 tab/分段内容切换仍然硬切，wobble 不给页级交接；
 * - 进度/加载类不用（读数要恒速可预期）；连续变化的状态不用（会永远在抖）；
 * - 触感不改：触感在触发时刻由调用点给，wobble 落位不补。
 *
 * 首次组合不抖：trigger 的初值只是初始状态、不是「翻转」（seen 门闩跳过第一次执行）。
 * 减弱动画环境（开发者选项 ANIMATOR_DURATION_SCALE = 0）静默跳过——抖是修饰不是信息。
 *
 * [scaleTo] 合理范围 1.03~1.10：再大就从「抖」变成「弹跳表演」。
 *
 * **摆放铁律**同 [pressScale]：graphicsLayer 只包住链上比它靠里的绘制，
 * 挂裸卡片链时必须放在 `.clip()` **之前**（放后面只缩得到文字缩不到底块）。
 */
fun Modifier.wobbleOn(trigger: Any?, scaleTo: Float = 1.06f): Modifier = composed {
    val context = LocalContext.current
    val scale = remember { Animatable(1f) }
    val seen = remember { mutableStateOf(false) }
    LaunchedEffect(trigger) {
        if (!seen.value) {
            seen.value = true
            return@LaunchedEffect
        }
        val animatorScale = Settings.Global.getFloat(
            context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f
        )
        if (animatorScale == 0f) return@LaunchedEffect
        scale.snapTo(1f)
        scale.animateTo(scaleTo, Motion.wobble())
        scale.animateTo(1f, Motion.wobble())
    }
    graphicsLayer {
        scaleX = scale.value
        scaleY = scale.value
    }
}
