package io.github.m0use001.planability.ui.info

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.m0use001.planability.app.PredictiveBackOverlay
import io.github.m0use001.planability.data.Graph
import io.github.m0use001.planability.ui.common.AppButtonGroup
import io.github.m0use001.planability.ui.common.Motion

/**
 * v77 S1b-3（模型与智能体 Hub 拆级，2026-11-23 用户「顶到了」「框呢」+「都做」）：
 * 「模型与智能体」的**板内钻入**形态——「我的」宽屏把它作为 NavHost 栈里的一块透明瓦片
 * （Rail 常驻、两框不动）。三个 tab（模型设置 / 智能体设置 / 模型显示）各自以自己的
 * `embedded = true` 单板形态渲染（透明底 + 板内页头 + 单列/单板），不再整页全屏覆盖层。
 *
 * 为什么不做「list→detail 两级真正钻入」而用 embedded 单板：模型供应商详情是 200+ 行、
 * 携带大量就地编辑状态（Base URL/Key 防抖、测连接、模型列表增删改、本地模型详情）的
 * 复杂表单——把它拆成独立 detail 板会把整片状态搬出去、风险极高。embedded 单板让三个 tab
 * 各自复用**既有的窄屏单列分支**（列表与详情同列滚动，与手机一致），既进板框架、又零状态
 * 搬动。等这版稳了若要给模型 tab 单独升级成真 drill，再专门一轮做。
 */
@Composable
fun ModelHubSubBoard(onDone: () -> Unit) {
    var tab by remember { mutableStateOf(0) }
    var restored by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        Graph.settings.kvGet("shell.freeze.hub.model")?.toIntOrNull()?.takeIf { it in 0..2 }?.let { tab = it }
        restored = true
    }
    LaunchedEffect(tab, restored) {
        if (restored) Graph.settings.kvPut("shell.freeze.hub.model", tab.toString())
    }
    // 胶囊滑动进度提到换页范围外（切 tab 整页重建也不打断滑动，同 PlanScreen segmentSlide）
    val tabSlide = remember { androidx.compose.animation.core.Animatable(tab.toFloat()) }
    LaunchedEffect(tab) {
        tabSlide.animateTo(
            tab.toFloat(),
            androidx.compose.animation.core.tween(
                durationMillis = Motion.emphasizedMs,
                easing = Motion.emphasizedEasing
            )
        )
    }

    val switcher: @Composable () -> Unit = {
        Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp)) {
            AppButtonGroup(
                options = listOf("模型设置", "智能体设置", "模型显示"),
                selectedIndex = tab,
                onSelect = { tab = it },
                animatedIndex = tabSlide.value,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }

    PredictiveBackOverlay(onBack = onDone, deferBack = true) {
        when (tab) {
            0 -> io.github.m0use001.planability.ui.profile.ModelManagerScreen(
                onDone = onDone, topSwitcher = switcher, insideOverlay = true, embedded = true
            )
            1 -> AssistantsScreen(
                onDone = onDone, topSwitcher = switcher, insideOverlay = true, embedded = true
            )
            else -> io.github.m0use001.planability.ui.profile.ModelDisplayScreen(
                onDone = onDone, topSwitcher = switcher, insideOverlay = true, embedded = true
            )
        }
    }
}
