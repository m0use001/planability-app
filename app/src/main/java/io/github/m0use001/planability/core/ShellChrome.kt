package io.github.m0use001.planability.core

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp

/**
 * 一级页外壳锁定：底栏是一级界面专属。
 * 任何全屏子页/编辑器/覆盖层/抽屉调用 [lock] 计数加一，销毁时减一（可嵌套）。
 * 底栏可见性由 [effectiveLock] 驱动；十三期起 Pager 已彻底禁用滑动切换，
 * lock 不再控制 Pager（消费者：NavShell 底栏/Rail 显隐、左缘搜索手势带、全局返回兜底）。
 *
 * v68（2026-11-21「纸条导航」）锁分两层：壳层 pager 七页**常驻组合**，页内子页
 * （v68 起 PredictiveBackOverlay 页内化）随所属 tab 的纸条一起滑走、跨切页保留——
 * 它们锁的只能是自己那一页：「我的」页开着外观设置切到「专注」，专注页的底栏/Rail/
 * 返回兜底必须照常工作。所以页内层经 [LockShellChrome] 记到 [LocalTabId] 名下
 * （[tabLocks]），壳级组合（搜索抽屉等无 tab 上下文的层）仍记全局 [lockCount]。
 * NavShell 的消费者一律读 [effectiveLock]（当前 tab 的页内锁 + 壳级锁）。
 * [lockCount] 保留原名与语义，既有调用方（含并行会话的 v67 抽屉层）零改动。
 */
object ShellChrome {
    /** 壳级锁：无 tab 上下文的层（搜索抽屉宿主等）。 */
    var lockCount by mutableIntStateOf(0)
        private set

    /** 每个 tab 名下的页内锁计数（[LockShellChrome] 在 pager 内容里自动按 [LocalTabId] 记账）。 */
    private val tabLocks = androidx.compose.runtime.mutableStateMapOf<String, Int>()

    /** [currentTab] 当前生效的锁数 = 壳级 + 它自己名下的页内锁。 */
    fun effectiveLock(currentTab: String): Int = lockCount + (tabLocks[currentTab] ?: 0)

    var searchOpen by mutableStateOf(false)

    /**
     * v69「框不动」：每个一级 tab 的壳层相框是否显示（**按 tab 记账**，未登记 = true）。
     *
     * 为什么必须分 tab：首页日历的 3 天/周/年视图是**单列**形态要藏框，而七页常驻组合——
     * 全局单值会被首页（常驻重组）的 SideEffect 持续写回 false，污染其他 tab
     * （2026-11-21 用户截图实锤：日历 3 天视图看过之后，我的页点外观整页无框、
     * 内容行按整页宽当视口）。按 tab 记账后首页怎么写都只影响 home。
     * 壳层框层与内容裁剪盒都读 [boardFramesFor]。
     */
    private val boardFrameStates = androidx.compose.runtime.mutableStateMapOf<String, Boolean>()

    fun boardFramesFor(tab: String): Boolean = boardFrameStates[tab] ?: true

    fun setBoardFrames(tab: String, show: Boolean) { boardFrameStates[tab] = show }

    /** v19.7：首页进入日历模式（月历有横向滑动带）时置 true，让右滑抽屉手势让位 */
    var homeImmersive by mutableStateOf(false)

    /**
     * v19.7：横向滚动条登记（坐标引用，不按帧重算矩形）。
     * 右滑抽屉是整屏手势，会抢走 chips / 模型选择条这类横向滑动的首次手势；
     * 由横向滚动条自己登记，抽屉手势**按下时**才做命中测试。
     *
     * 2026-11-10 性能：旧实现用 onGloballyPositioned 每帧重算 Rect 并对
     * mutableStateListOf 增删——滚动时每个代码块每帧都分配+改列表，长会话掉帧。
     * 改存可变节点：布局回调只赋坐标引用（零分配），命中测试延迟到手势按下。
     * 注意节点必须**可变**：DisposableEffect 跑在 onGloballyPositioned 之前，
     * 登记时坐标还没算出来，只能等布局回调回填。
     */
    class HScrollNode(val padXPx: Float, val padYPx: Float) {
        @Volatile var coords: androidx.compose.ui.layout.LayoutCoordinates? = null
    }
    val hScrollNodes = mutableListOf<HScrollNode>()

    fun inHScrollZone(pos: Offset): Boolean = hScrollNodes.any { n ->
        runCatching {
            val c = n.coords ?: return@runCatching false
            val b = c.boundsInRoot()
            pos.x >= b.left - n.padXPx && pos.x <= b.right + n.padXPx &&
                pos.y >= b.top - n.padYPx && pos.y <= b.bottom + n.padYPx
        }.getOrDefault(false)
    }

    fun lock(tab: String?) {
        if (tab == null) lockCount++
        else tabLocks[tab] = (tabLocks[tab] ?: 0) + 1
    }

    fun unlock(tab: String?) {
        if (tab == null) {
            lockCount = (lockCount - 1).coerceAtLeast(0)
        } else {
            val rest = (tabLocks[tab] ?: 0) - 1
            if (rest <= 0) tabLocks.remove(tab) else tabLocks[tab] = rest
        }
    }
}

/**
 * 当前组合所属的一级 tab id（NavShell 的 pager 页内容里提供；壳级组合为 null → 锁记全局）。
 * 页内层靠它把锁记到自己那一页名下（[ShellChrome.tabLocks]）。
 */
val LocalTabId = androidx.compose.runtime.compositionLocalOf<String?> { null }

/**
 * 本组合所在的一级页是否是**当前停留页**。
 *
 * 为什么必须有：pager 七页常驻组合（beyondViewportPageCount），别的 tab 上页面仍然
 * 组合着——它们的返回手势（BackHandler / PredictiveBackHandler）与覆盖层若不按
 * 「我是不是当前页」门控，就会替别的页白吃系统返回（看起来像"按了没反应"）。
 * NavShell 在 pager 内容里随 [ShellNav] 的当前页实时提供；壳级组合默认 true
 * （无 tab 上下文的层不受此门控约束）。
 */
val LocalTabActive = androidx.compose.runtime.compositionLocalOf { true }

/**
 * 标记「这是一个横向滚动条」：把自身在根坐标里的矩形登记到 [ShellChrome.hScrollZones]，
 * 右滑抽屉手势在按下点落于该区内时放弃开抽屉。卸载时自动注销。
 */
@Composable
fun Modifier.hScrollZone(enabled: Boolean = true): Modifier {
    // v19.9：判定区外扩——chips 条只有 ~48dp 高，手势起点稍微偏一点就落到区外被抽屉抢走；
    // 纵向给足余量（±20dp）；横向只留 ±4dp——横向不能再宽，否则会把同一行右侧的
    // 发送键/权限键一起圈进「横滑区」，点它们时抽屉手势被吞（v19.10 修）。
    val padY = with(LocalDensity.current) { 20.dp.toPx() }
    val padX = with(LocalDensity.current) { 4.dp.toPx() }
    val node = remember(padX, padY) { ShellChrome.HScrollNode(padXPx = padX, padYPx = padY) }
    // 登记/注销只在 effect 里做（组合生命周期一次）；坐标由下方布局回调回填。
    // 旧写法在 effect 里读 slot[0]——effect 跑在布局回调之前，首组合时是 null，
    // 横滑区永远登记不上（抽屉又会抢手势）。
    DisposableEffect(enabled) {
        if (enabled) ShellChrome.hScrollNodes.add(node)
        onDispose { ShellChrome.hScrollNodes.remove(node) }
    }
    return this.onGloballyPositioned { coords -> node.coords = coords }
}

/**
 * 在组合期间锁定一级页外壳：pager 页内容里（[LocalTabId] 非空）记到该 tab 名下，
 * 壳级组合记全局——见 [ShellChrome] 的分层说明。
 */
@Composable
fun LockShellChrome() {
    val tab = LocalTabId.current
    DisposableEffect(tab) {
        ShellChrome.lock(tab)
        onDispose { ShellChrome.unlock(tab) }
    }
}
