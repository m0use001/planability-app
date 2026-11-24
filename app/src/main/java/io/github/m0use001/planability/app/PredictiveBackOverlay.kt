package io.github.m0use001.planability.app

import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import io.github.m0use001.planability.core.LocalTabActive
import io.github.m0use001.planability.core.LockShellChrome
import io.github.m0use001.planability.ui.common.Motion
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** 当前全屏子页的程序化关闭（带收缩动画）。不在子页内时为空实现。 */
val LocalOverlayClose = compositionLocalOf { {} }

/**
 * 全屏子页统一外壳。
 *
 * v68（2026-11-21「纸条导航」）：从**独立 Dialog 窗口**改为**页内整页层**。
 *
 * 为什么必须搬进页面里：壳层 pager 七页常驻组合，子页若是独立窗口就会浮在
 * **所有** tab 之上——这正是每个一级页都要写一段「切走本 tab 就收掉全部子页」
 * watcher 的根源（记忆被主动抹掉，用户在外面点了外观设置、切去专注再回来，
 * 外观已经没了）。页内层让子页成为所属 tab 纸条的一部分：切走随纸条滑走、
 * 切回原样还在（页面记忆），底栏/Rail 的显隐也只作用于自己那一页
 * （[LockShellChrome] 经 [io.github.m0use001.planability.core.LocalTabId] 按 tab 记账）。
 *
 * 调用点必须处于**页面根 Box 的顶层**（与页面主体平级、排在其后）——层只盖住
 * 自己的页面矩形，盖不住别的 tab，也不再跨越窗口边界。
 *
 * 返回手势/按键：PredictiveBackHandler 跟手缩放内容，完成/取消经 close()/弹回；
 * 门控在 `tabActive && !closing`——常驻组合下别的 tab 上的本层不得响应系统返回。
 * 程序化关闭（顶栏返回、保存后关闭）经 LocalOverlayClose 走 [Motion].standard()（180ms）收缩动画后 onBack。
 */
/** 全屏子页的进场方向：Bottom=短上滑（默认，普通子页）；Forward=前进模式（层级导航，
 *  规范 Transition patterns "Forward and backward"：水平部分滑动+淡入，屏幕不用滑满宽） */
enum class OverlayEnter { Bottom, Forward }

@Composable
fun PredictiveBackOverlay(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    enter: OverlayEnter = OverlayEnter.Bottom,
    /**
     * v58 平板适配（iPad HIG "centered modal"）：宽屏（≥600dp）上把二级页收成
     * **居中限宽（1000dp）的模态卡**——否则表单/列表类二级页在 1280dp 宽下整宽拉稀
     * （用户实测截图点名「文件库」）。手机（Compact）恒为满屏，零影响。
     *
     * 需要整屏的页面必须传 `fullscreen = true` 显式退出：
     * 双栏宿主（InfoCenter 聊天 / NoteListPage / 文件管理器内嵌文件库的场合由 Hub 决定）、
     * 画布编辑器（Freeform / PdfNote / 全屏文本展开）。
     */
    fullscreen: Boolean = false,
    /**
     * v68 钻入板条（BoardDrill 的细节瓦片）里传 true：系统返回让给宿主的纸条
     * （BoardDrill 自己装 PredictiveBackHandler 跟手拖瓦片），本层不再装自己的
     * 预测返回——否则瓦片里更深的这层会抢到手势，返回变成"缩放"而不是"纸条左移"。
     * 程序化关闭（LocalOverlayClose）与进场动画不受影响。
     */
    deferBack: Boolean = false,
    /**
     * v75：「页内层但仍属于钻入板内」的页面（如计划编辑器里再开的「选择时间」瓦片）传 false——
     * 保持壳层 Rail/底栏可见（钻入板内不是整屏接管），只借用本层的返回手势/关闭动画。
     * 默认 true：整屏子页照旧锁壳（藏 Rail/底栏）。与 [deferBack] 解耦——deferBack 决定
     * 「是否铺底/是否自装返回」，lockShell 只决定「壳层是否让位」。
     */
    lockShell: Boolean = true,
    content: @Composable () -> Unit
) {
    // v68：钻入瓦片（deferBack = true）里的层是**页面的一部分**（BoardDrill 细节瓦片上的
    // 钻入内容），不是浮起的模态——不锁壳。锁了会把 Rail 藏掉，「开着外观设置点专注
    // 切页」（需求 3）就走不通了。整屏层（deferBack = false）照旧锁。
    // v75：lockShell=false 的钻入瓦片（选择时间器）也不锁——与编辑器瓦片同款，Rail 常驻。
    if (!deferBack && lockShell) LockShellChrome()
    // 常驻组合的门控：本层随所属 tab 的纸条滑走后（tab 不再是当前页），
    // 预测返回必须失效——否则藏在别的 tab 底下的这一层会替当前页吃掉系统返回。
    val tabActive = LocalTabActive.current
    var fraction by remember { mutableFloatStateOf(0f) }
    var closing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // 进场动画（P1-2 整改，2026-10-24 第二轮审查）：**容器（含不透明底）首帧即完全遮住宿主**，
    // 进场动效只作用在内容层——默认短上滑（32dp，defaultSpatial 轻过冲落位）+ 快速淡入；
    // 层级导航（如 笔记本网格→笔记列表）传 [OverlayEnter.Forward] 走水平 1/4 滑+淡。
    // 旧写法把 alpha/scale/圆角都打在整页容器上：宿主标题与新页标题同位同时半透明
    // （违反 "Fully fade out content before fading new content in" 与 "Don't slowly fade
    // components on top of other content"），整页 scale 也违反 "Scale and z-axis motion is avoided"。
    // 为什么必须有进场：没有它时全屏子页是"啪"一下硬切出现，而关闭却有 180ms 收缩——不对称。
    val enterAnim = remember { Animatable(0f) }
    LaunchedEffect(Unit) { enterAnim.animateTo(1f, Motion.standard()) }

    val close: () -> Unit = remember(onBack) {
        {
            if (!closing) {
                closing = true
                scope.launch {
                    // 关闭 = 常规档（Motion.standard / 180ms）：全 app 所有"程序化关闭子页"共用这一个时长，
                    // 顶栏返回、保存后关闭、手势完成关掉，摸起来是同一件事
                    Animatable(fraction).animateTo(1f, Motion.standard()) { fraction = this.value }
                    onBack()
                    // 2026-11-01 根修「卡死在返回时的下陷特效」：分层返回的覆盖层（文件库：
                    // 先关预览 → 再退目录层级 → 最后才关库）onBack 之后**并不离开组合**——
                    // 不复位的话 closing 永远 true、fraction 永远 1，整页永久停在收缩收场态
                    // （缩 8% + 变暗 30% + 切圆角），预测手势被禁用，系统返回也因 if(!closing)
                    // 变成空操作（用户复报多次，2026-10-31 的嵌套窗口修的只是诱因之一）。
                    // 若 onBack 真把覆盖层关了，协程作用域随组合销毁，这两行写的是已丢弃的状态，无害。
                    closing = false
                    fraction = 0f
                }
            }
        }
    }

    // 关闭动画进行中禁用预测式手势（progress 会与收缩动画打架）；手势取消已复位，
    // 但取消可能不经过 catch（系统直接停发 progress）——看门狗把冻结的半途进度弹回 0
    LaunchedEffect(closing) {
        while (!closing) {
            kotlinx.coroutines.delay(700)
            val f = fraction
            if (f > 0.01f) {
                kotlinx.coroutines.delay(250)
                if (!closing && fraction <= f + 0.001f && fraction >= f - 0.001f) {
                    // 卡住的半途进度弹回 = 回到原状，用 quick（120ms，比原来的 160ms 更干脆）；
                    // 它不是用户主动操作，越快归位越不抢戏
                    Animatable(fraction).animateTo(0f, Motion.quick()) { fraction = this.value }
                }
            }
        }
    }

    // 页内层的预测式返回：跟手缩放，取消时弹回，松手完成走收缩动画。
    // tabActive 门控见函数 KDoc；deferBack（钻入瓦片）时让给宿主的纸条手势。
    PredictiveBackHandler(enabled = !deferBack && tabActive && !closing) { progress ->
        try {
            progress.collect { p ->
                fraction = p.progress.coerceIn(0f, 1f)
            }
            close()
        } catch (e: CancellationException) {
            fraction = 0f
            throw e
        }
    }

    androidx.compose.runtime.CompositionLocalProvider(LocalOverlayClose provides close) {
        // 显式铺**不透明背景色**（原来是 Dialog 窗口 decor 的底色，v68 起由本层自己铺）。
        //
        // 为什么必须做：下面 graphicsLayer 有 `alpha = 1f - 0.3f * f` 与 `clip = f > 0.01f`。
        // 只要 fraction 短暂大于 0（PredictiveBackHandler 跟手，或看门狗复位过渡期），
        // 整层内容就变成 70% 透明——层自身的不透明底保证收缩期盖得住下面的页面主体，
        // 不会出现「宿主标题与新页标题两层半透明叠字」。
        val bg = MaterialTheme.colorScheme.background
        // v69「框不动」：钻入瓦片（deferBack）**不铺不透明底**——瓦片是壳层固定框上的
        // 透明纸条内容，铺底会把壳层的板底盖出一片色差。页内化后「两层半透明叠字」的
        // 原始问题已不存在（收缩期透出的是壳层板底，不是宿主内容）。
        Box(
            Modifier
                .fillMaxSize()
                .then(if (deferBack) Modifier else Modifier.background(bg))
                // 2026-11-02 键盘筛查：原来 Dialog 窗口 decorFitsSystemWindows=false 不吃
                // Manifest 的 adjustResize；页内层回到 activity 窗口，imePadding 继续负责
                // 把键盘弹出时的中下部输入框（模型管理 Base URL/API Key 等）顶上来。
                .imePadding(),
            contentAlignment = Alignment.TopCenter
        ) {
            // v58：宽屏 + 非满屏页 = **居中限宽模态卡**（iPad HIG centered modal）。
            // 外层满页铺不透明底（关闭收缩期遮住宿主），内层卡片在宽屏收到 1000dp 居中——
            // 40+ 个表单/列表类二级页一次性从「整宽拉稀」变成居中模态，手机路径不变。
            val modalWide = !fullscreen &&
                io.github.m0use001.planability.core.rememberWindowSizeClass().isWide
            Box(
                modifier = modifier
                    .then(
                        if (modalWide) {
                            Modifier.widthIn(max = 1000.dp).fillMaxHeight()
                        } else {
                            Modifier.fillMaxSize()
                        }
                    )
                    .graphicsLayer {
                        // 容器层只跟**手势/关闭收缩**（f）；进场不再动容器——
                        // 容器一透明宿主就会透出来（正是 P1-2 抓到的脏帧来源）。
                        // 本层的不透明底色（外层 Box 的 background）保证收缩期也遮得住。
                        val f = fraction
                        val s = 1f - 0.08f * f
                        scaleX = s
                        scaleY = s
                        alpha = 1f - 0.3f * f
                        shape = RoundedCornerShape((16f * f).coerceAtLeast(0f).dp)
                        clip = f > 0.01f
                    }
            ) {
            // 内容层承接进场：Bottom=短上滑+快淡入；Forward=前进模式（层级导航），
            // 水平 1/4 宽滑入+快淡入（规范："Android uses a fade as screens slide …
            // don't have to slide the full width"，与 PlanScreen 分段切换同一比例）。
            // 位移走 spatial 弹簧（允许轻过冲落位），alpha 同源。
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        val e = enterAnim.value.coerceIn(0f, 1f)
                        alpha = e
                        when (enter) {
                            OverlayEnter.Bottom -> translationY = 32.dp.toPx() * (1f - e)
                            OverlayEnter.Forward -> translationX = size.width * 0.25f * (1f - e)
                        }
                    }
            ) {
                content()
            }
            }
        }
    }
}
