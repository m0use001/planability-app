package io.github.m0use001.planability.app

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.ConcurrentHashMap

/**
 * 底栏「再点一次当前页签 = 回到顶部」的信号通道。
 *
 * ## 为什么要一个全局通道
 * 四个一级界面（首页/计划/笔记/文件）各有自己的滚动状态，而且**滚动状态就住在页面里**
 * （`rememberScrollState` / `rememberLazyListState` 都声明在页面内部的分支里）。
 * 底栏在 [NavShell] 里，拿不到这些 state；反过来给每个页面加参数又会把它们全部改一遍。
 * 所以这里只放一个"页签 → 计数器"的通道：底栏负责 +1，页面自己订阅并在变化时回顶。
 *
 * ## 为什么按页签分开计数
 * 只用一个全局计数器的话，在笔记页回顶会顺带让计划页（它常驻组合）也跳回顶部，
 * 用户切回去会发现"我刚才翻到的位置没了"。分页签各自一个计数，谁被点谁才动。
 *
 * ## 为什么第一次组合不回顶
 * 每个调用点都用 `remember { mutableIntStateOf(当前值) }` 记住"我看过的那个值"，
 * 只有值**变了**才滚。页面第一次组合时值相等 → 不动。否则从别的页签滑过来（各页
 * 一直常驻组合）就会被回顶，等于强制清掉用户的浏览位置。
 */
object TopScroll {

    private val ticks = ConcurrentHashMap<String, MutableStateFlow<Int>>()

    private fun tickOf(tab: String): MutableStateFlow<Int> =
        ticks.getOrPut(tab) { MutableStateFlow(0) }

    /** 页面订阅用：这个页签的"回顶请求"计数。 */
    fun stateOf(tab: String): StateFlow<Int> = tickOf(tab)

    /** 底栏调用：请求 [tab] 回到顶部。 */
    fun request(tab: String) {
        tickOf(tab).value += 1
    }
}

/**
 * 通用实现：订阅 [tab] 的回顶请求，计数变化时执行 [onTop]。
 *
 * `seen` 初值取组合期的当前计数，因此第一次组合不会触发（见 [TopScroll] 的说明）。
 */
@Composable
private fun OnReselect(tab: String, onTop: suspend () -> Unit) {
    val tick by TopScroll.stateOf(tab).collectAsState()
    var seen by remember(tab) { mutableIntStateOf(tick) }
    LaunchedEffect(tick) {
        if (tick != seen) {
            seen = tick
            onTop()
        }
    }
}

/** 列表型页面（首页日程以外的 LazyColumn / 笔记网格） */
@Composable
fun ScrollToTopOnReselect(tab: String, state: LazyListState) =
    OnReselect(tab) { state.animateScrollToItem(0) }

@Composable
fun ScrollToTopOnReselect(tab: String, state: LazyGridState) =
    OnReselect(tab) { state.animateScrollToItem(0) }

/** 整页 `verticalScroll` 的页面（首页时间轴、文件页的非列表分支） */
@Composable
fun ScrollToTopOnReselect(tab: String, state: ScrollState) =
    OnReselect(tab) { state.animateScrollTo(0) }

/**
 * 首页专用：点底栏「首页」（重复点当前页，或从别页切进来——NavShell 两种都会发信号）
 * → 执行 [block]。语义上首页的回顶请求是「聚焦当前时间线」（红线居中），不是回顶，
 * 所以单独开一个入口，别和 ScrollToTopOnReselect 混用。
 */
@Composable
fun OnHomeTabSelect(block: suspend () -> Unit) = OnReselect("home", block)
