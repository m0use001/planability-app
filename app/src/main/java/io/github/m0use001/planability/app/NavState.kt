package io.github.m0use001.planability.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.snapshots.SnapshotStateList

/**
 * 层级栈里的一级 = 一块**单板**瓦片（透明，画在壳层固定相框上）。
 *
 * @param key 稳定标识（相等时写回不触发失效）。
 * @param content 该级内容（宽度会随钻入在 `mainW ↔ secW` 间连续改排版，须自适应）。
 */
class NavPane(val key: Any, val content: @Composable () -> Unit)

/**
 * v77 全局层级栈（2026-11-22 用户拍板「选项3：全局层级栈 + 钻进板，逐级单板分解」）。
 *
 * **深度唯一真源**：每个一级 tab 一条 `SnapshotStateList<NavPane>`。此前深度散落在各页
 * 的扁平布尔 + 各自的 `BoardDrill`/`BoardDeck`/`PredictiveBackOverlay` 里，互不相通；
 * 两板 Hub（记忆库/自动化/模型/助理/文件库）因此只能整页覆盖（框套框）。现在收敛到这一条栈，
 * 由 [io.github.m0use001.planability.ui.common.NavHost] 渲染成横向纸条，钻入 = 主板让位、上一页缩副板。
 *
 * 状态挂在 object 上（进程级、跨重组存活），按 tab 分桶；A 线跨 tab 的深链由 Router 决定切到哪个 tab 后 push。
 */
object NavState {
    private val stacks = mutableStateMapOf<String, SnapshotStateList<NavPane>>()

    /** 取（必要时创建）某 tab 的栈。读它的大小/元素在组合期可观察。 */
    fun stack(tab: String): SnapshotStateList<NavPane> =
        stacks.getOrPut(tab) { mutableStateListOf() }

    fun depth(tab: String): Int = stacks[tab]?.size ?: 0
}

/**
 * 绑定到某个 tab 的导航句柄（经 [LocalNav] 下发）。页面里 push/pop 都走这里。
 *
 * 返回动画由 [io.github.m0use001.planability.ui.common.NavHost] 统一负责：NavHost 会把自己的
 * 「带动画 pop」挂到 [backHandler] 上，因此这里的 [pop] 也走同一条动画路径（不是瞬删）。
 */
class NavController(val tab: String) {
    /** NavHost 注入的「带动画返回」；未挂时 [pop] 退化为瞬删。 */
    var backHandler: (() -> Unit)? = null

    val stack: SnapshotStateList<NavPane> get() = NavState.stack(tab)
    val depth: Int get() = stack.size

    fun push(pane: NavPane) {
        stack.add(pane)
    }

    /** 带动画返回一级（NavHost 未挂时瞬删）。 */
    fun pop() {
        val h = backHandler
        if (h != null) h() else popImmediate()
    }

    /** 瞬删一级（NavHost 在动画结束后调用；或弹窗等无动画场景）。 */
    fun popImmediate() {
        if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex)
    }

    /** 回到根（清空整条栈，瞬删——用于切 tab / 深链重置）。 */
    fun popToRoot() {
        stack.clear()
    }

    /**
     * 瞬删到指定栈深（[depth] = 要保留的层数；v77 S1b 文件库面包屑跳层用）。
     *
     * 面包屑点前面的层级 = 直接跳回去，不需要逐层播动画（与旧 `path = path.take(n)` 同语义）；
     * 走 [popImmediate] 而不是 [pop]，避免连续多次「动画 pop」互相打架。
     */
    fun popTo(depth: Int) {
        while (stack.size > depth) popImmediate()
    }
}

/** 当前 tab 的导航句柄；不在 NavHost 内时为空。 */
val LocalNav = compositionLocalOf<NavController?> { null }
