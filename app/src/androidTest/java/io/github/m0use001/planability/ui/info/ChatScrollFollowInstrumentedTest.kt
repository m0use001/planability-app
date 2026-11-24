package io.github.m0use001.planability.ui.info

import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.m0use001.planability.core.BottomClamp
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Ignore
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 仓库第一个 instrumented 滚动测试（2026-11-20 钳制模型重写的验收门）。
 *
 * 此前的滚动测试全是 JVM 源码文本守卫 + 纯函数单测，没有任何「真实 LazyColumn →
 * 真实布局 → 真实滚动」的集成覆盖；重写执行层时这块是裸奔的，先补上。
 *
 * 复刻 MessageList 的**同款几何**（正序 LazyColumn + contentPadding + spacedBy +
 * 容差 atBottom + BottomClamp 决策 + scrollBy(MAX) 钳底执行），用合成内容模拟
 * 流式「最后一条每帧长高」。断言走真实布局快照，不经任何 mock。
 *
 * **只对模拟器跑**（AGENTS §4.2 硬闸：绝不连日用机——Gradle connected 测试跑完
 * 会连主包一起卸载）。
 *
 * **2026-11-20 基建现状**：标 [Ignore] 暂挂——Compose ui-test（BOM 2026.02.01）
 * 的 `kotlinx-coroutines-test` 在 `TestScopeImpl.enter` 调 `ensurePlatformExceptionHandlerLoaded`，
 * 而 app 打包进 dex 的 `CoroutineExceptionHandlerImplKt`（classes3.dex）缺该方法
 * （依赖树与 META-INF 版本文件都标 1.10.2，但 dex 层未合并进来——是仓库从未跑过
 * instrumented compose 测试、coroutines 去重/dex 合并基建未对齐的更深问题，独立于本线）。
 * 测试逻辑本身已审，等 coroutines 基建对齐后去掉 [Ignore] 即可运行。
 */
@Ignore("coroutines-test vs 打包 core 的 dex 合并基建未对齐（仓库首个 instrumented compose 测试，待基建修）")
@RunWith(AndroidJUnit4::class)
class ChatScrollFollowInstrumentedTest {

    @get:Rule
    val rule = createComposeRule()

    private fun lastVisibleIsLastOf(state: androidx.compose.foundation.lazy.LazyListState): Boolean {
        val info = state.layoutInfo
        return info.visibleItemsInfo.lastOrNull()?.index == info.totalItemsCount - 1
    }

    /** 装上 MessageList 同款钳制模型：streaming 期间 BottomClamp.decide → scrollBy(MAX)。 */
    @Composable
    private fun Harness(
        messages: MutableList<String>,
        streaming: Boolean,
        autoFollow: Boolean,
        onState: (androidx.compose.foundation.lazy.LazyListState) -> Unit,
        onScope: (kotlinx.coroutines.CoroutineScope) -> Unit,
    ) {
        val listState = rememberLazyListState()
        onState(listState)
        val scope = rememberCoroutineScope()
        onScope(scope)
        val listDragged by listState.interactionSource.collectIsDraggedAsState()
        Surface(Modifier.fillMaxSize()) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(messages, key = { it.hashCode() }) { msg ->
                    Box(Modifier.fillMaxWidth().height(60.dp)) {
                        Text(msg, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
        LaunchedEffect(streaming) {
            snapshotFlow {
                BottomClamp.Input(
                    streaming = streaming,
                    pinned = autoFollow,
                    dragged = listDragged,
                    lastVisibleIsLast = lastVisibleIsLastOf(listState),
                    canScrollForward = listState.canScrollForward,
                )
            }.collect { input ->
                if (!BottomClamp.decide(input)) return@collect
                runCatching { listState.scrollBy(Float.MAX_VALUE) }
            }
        }
    }

    @Test
    fun 流式增长时钳底_贴平后自然停() {
        val msgs = mutableStateListOf<String>()
        var streaming by mutableIntStateOf(0) // 0=false 1=true
        var autoFollow = true
        lateinit var state: androidx.compose.foundation.lazy.LazyListState
        lateinit var scope: kotlinx.coroutines.CoroutineScope
        rule.setContent {
            Harness(msgs, streaming == 1, autoFollow, { state = it }, { scope = it })
        }
        rule.runOnIdle {
            repeat(60) { msgs += "msg-$it" }
            streaming = 1
        }
        // 流式：最后一条内容增长（模拟逐 token 追加）
        repeat(5) { round ->
            rule.runOnIdle { msgs[msgs.size - 1] = "growing-$round-" + "x".repeat(200) }
            rule.waitForIdle()
        }
        // 真贴底判据：列表自己说了算（!canScrollForward）
        rule.runOnIdle {
            assertTrue("流式增长后应被钳在底部", !state.canScrollForward)
        }
        // 钳平后决策必须收敛为 false（无重发、无循环）
        rule.runOnIdle {
            assertFalse(
                "贴平后 BottomClamp 不许再放行钳底",
                BottomClamp.decide(
                    BottomClamp.Input(
                        streaming = true, pinned = true, dragged = false,
                        lastVisibleIsLast = lastVisibleIsLastOf(state),
                        canScrollForward = state.canScrollForward,
                    )
                )
            )
        }
    }

    @Test
    fun 上滑看历史时流式增长不许把人拽回底部() {
        val msgs = mutableStateListOf<String>()
        var streaming by mutableIntStateOf(0)
        var autoFollow = false // 用户上滑后 autoFollow 被关（生产里由停稳探测器置 false）
        lateinit var state: androidx.compose.foundation.lazy.LazyListState
        lateinit var scope: kotlinx.coroutines.CoroutineScope
        rule.setContent {
            Harness(msgs, streaming == 1, autoFollow, { state = it }, { scope = it })
        }
        rule.runOnIdle {
            repeat(120) { msgs += "msg-$it" }
        }
        rule.waitForIdle()
        // 先钉在底部
        rule.runOnIdle { scope.launch { state.scrollToItem(msgs.size - 1, Int.MAX_VALUE) } }
        rule.waitForIdle()
        // 用户上滑（直接程序滚到半路，模拟停稳在历史位）
        rule.runOnIdle { scope.launch { state.scrollToItem(30) } }
        rule.waitForIdle()
        // 流式开始且内容增长
        rule.runOnIdle { streaming = 1 }
        repeat(4) { round ->
            rule.runOnIdle { msgs[msgs.size - 1] = "growing-$round-" + "x".repeat(300) }
            rule.waitForIdle()
        }
        rule.runOnIdle {
            assertEquals(
                "用户在看历史，流式增长后视口必须分毫不动",
                30, state.firstVisibleItemIndex,
            )
        }
    }
}
