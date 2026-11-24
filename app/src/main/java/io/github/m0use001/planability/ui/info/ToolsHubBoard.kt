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
import io.github.m0use001.planability.ui.profile.PluginMarketScreen

/**
 * v77 S1b-4（工具与扩展 Hub 拆级，2026-11-23「都做」）：「工具与扩展」的**板内钻入**形态——
 * 「我的」宽屏把它作为 NavHost 栈里的一块透明瓦片（Rail 常驻、两框不动）。三个 tab：
 * 能力与插件（PluginMarketScreen）/ MCP（AutomationScreen hideTabs）/ 命令（MemoryScreen
 * 技能筛选 hideTopBar），各自以 `embedded = true` **单板**形态渲染（透明底 + 板内页头 +
 * 强单板），不再整页全屏覆盖层。与 ModelHubSubBoard 同一套拆级法。
 */
@Composable
fun ToolsHubSubBoard(onDone: () -> Unit) {
    var tab by remember { mutableStateOf(0) }
    var restored by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        Graph.settings.kvGet("shell.freeze.hub.tools")?.toIntOrNull()?.takeIf { it in 0..2 }?.let { tab = it }
        restored = true
    }
    LaunchedEffect(tab, restored) {
        if (restored) Graph.settings.kvPut("shell.freeze.hub.tools", tab.toString())
    }
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
                options = listOf("能力与插件", "MCP", "命令"),
                selectedIndex = tab,
                onSelect = { tab = it },
                animatedIndex = tabSlide.value,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }

    PredictiveBackOverlay(onBack = onDone, deferBack = true) {
        when (tab) {
            0 -> PluginMarketScreen(
                onDone = onDone, mode = 0, topSwitcher = switcher, insideOverlay = true, embedded = true
            )
            1 -> AutomationScreen(
                onDone = onDone, initialTab = 1, topSwitcher = switcher,
                insideOverlay = true, hideTabs = true, embedded = true
            )
            else -> MemoryScreen(
                onDone = onDone, initialType = "skill", topSwitcher = switcher,
                insideOverlay = true, hideTopBar = true, embedded = true
            )
        }
    }
}
