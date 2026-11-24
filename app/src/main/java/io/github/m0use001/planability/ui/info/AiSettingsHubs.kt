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
import io.github.m0use001.planability.ui.profile.ModelDisplayScreen
import io.github.m0use001.planability.ui.profile.PluginMarketScreen

/**
 * v50.5：**AI 设置的两个总汇页**（用户 2026-11-05 拍板「两行：本体 + 扩展」）。
 *
 * 抽屉底部原本 6 行 AI 设置（模型与智能体 / AI能力开关 / 图表默认设置 / 手机操控区域 /
 * MCP / 命令），其中 4 行属 AI 域、且「我的」页还有一份不完全相同的重复清单
 * （多一项「模型显示管理」、抽屉里却没有）。合并规则按**语义边界**分两组：
 *
 * - 「模型与智能体」= AI **自己是谁**：模型 / 智能体（人格）/ 模型显示
 * - 「工具与扩展」  = AI **能调动什么**：能力与插件 / MCP / 命令
 *
 * 不合并成一行 6 tab 的原因（用户明确反馈）：一行 6 段切换器在手机上必然超宽，
 * 而且"本体"和"扩展"是两种完全不同的心智，混在一起找不到东西。
 *
 * 图表默认设置、手机操控区域留在抽屉里独立成行——前者是呈现偏好、后者是系统能力，
 * 都不是 agent 配置，塞进来会让这两个 Hub 变成"杂项设置"。
 *
 * ## 嵌入契约（防"闪一下"）
 *
 * 被嵌入的五个屏各自原来都套着 PredictiveBackOverlay（全屏 Dialog = 独立窗口）。
 * 若每个 tab 各套一层，切 tab 就是「两个 Dialog 接连关+开」，宿主窗口透出一帧——
 * 用户看到页面闪一下（SettingsHub 的 ModelManagerScreen 注释里有这条完整踩坑史）。
 * 所以统一：**Hub 在最外层只套一层 overlay**，各屏收 insideOverlay=true 只画内容。
 */

private const val KEY_MODEL_TAB = "shell.freeze.hub.model"
private const val KEY_TOOLS_TAB = "shell.freeze.hub.tools"

/**
 * 「模型与智能体」总汇：3 tab（模型设置 / 智能体设置 / 模型显示）。
 *
 * 前两个 tab 沿用原 [ModelSettingsHub] 的既有实现不动；这里扩展为三 tab 并把
 * 「模型显示管理」从「我的」页收进来——它改的 `AiModel.enabled` 与供应商卡里的开关
 * 是**同一个字段**，本来就该和模型设置放在一起看。
 */
@Composable
fun ModelSettingsHub(onDone: () -> Unit) {
    var tab by remember { mutableStateOf(0) }
    var restored by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        Graph.settings.kvGet(KEY_MODEL_TAB)?.toIntOrNull()?.takeIf { it in 0..2 }?.let { tab = it }
        restored = true
    }
    LaunchedEffect(tab, restored) {
        if (restored) Graph.settings.kvPut(KEY_MODEL_TAB, tab.toString())
    }

    // 2026-11-22 用户「这个横向选择列表也要加上切换动画」：切换器作为 topSwitcher
    // 画在**每个 tab 各自的整页**里，切 tab 时整页组合被换掉、切换器随之销毁重建，
    // AppButtonGroup 内部弹簧以新索引为初值直接落位 = 永远看不到滑动。
    // 组件注释（2026-10-29 计划页同坑）预留的解法：Animatable 提到重建范围之外，
    // 以 animatedIndex 传进去——滑动进度在 Hub 层活下来，胶囊就能滑过去。
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
                // 滑动进度从换页范围外传进来，重建不打断滑动（同 PlanScreen segmentSlide）
                animatedIndex = tabSlide.value,
                // v53.2：**不写死宽度**。原来钉 360dp，在 560dpi 机型（可用宽约 308dp）上
                // 被父布局压回可用宽后每段只剩 ~98dp，「模型设置」+ 选中勾裁成「模型设」。
                // 现在交给父布局定宽（16dp 边距 + 填满），配合 AppButtonGroup 三段省勾。
                modifier = Modifier.fillMaxWidth()
            )
        }
    }

    // v64：宽屏整屏承载三 tab 的主从两板（不再收成居中 1000dp 模态卡）——
    // 1000dp 卡里两板虽放得下，但整屏才是与计划页/会话页同一套「页内分栏」体验。
    PredictiveBackOverlay(
        onBack = onDone,
        fullscreen = io.github.m0use001.planability.core.rememberWindowSizeClass().isWide
    ) {
        when (tab) {
            0 -> io.github.m0use001.planability.ui.profile.ModelManagerScreen(
                onDone = onDone, topSwitcher = switcher, insideOverlay = true
            )
            1 -> AssistantsScreen(onDone = onDone, topSwitcher = switcher, insideOverlay = true)
            else -> ModelDisplayScreen(
                onDone = onDone, topSwitcher = switcher, insideOverlay = true
            )
        }
    }
}

/**
 * 「工具与扩展」总汇：3 tab（能力与插件 / MCP 设置 / 命令设置）。
 *
 * 三页各自都是完整页面，这里只负责切换：
 * - 能力与插件 = [PluginMarketScreen] 的 mode=0（能力域 + 插件包开关同一屏）
 * - MCP 设置   = [AutomationScreen] 的 MCP 子页（hideTabs 藏掉它自己的四段 TabRow，
 *                否则和本 Hub 的切换器叠成两层 tab）
 * - 命令设置   = [MemoryScreen] 的技能筛选（hideTopBar 藏掉它自己的标题栏同理）
 */
@Composable
fun ToolsHub(onDone: () -> Unit) {
    var tab by remember { mutableStateOf(0) }
    var restored by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        Graph.settings.kvGet(KEY_TOOLS_TAB)?.toIntOrNull()?.takeIf { it in 0..2 }?.let { tab = it }
        restored = true
    }
    LaunchedEffect(tab, restored) {
        if (restored) Graph.settings.kvPut(KEY_TOOLS_TAB, tab.toString())
    }

    // 同 ModelSettingsHub：Animatable 提到换页范围外，胶囊滑动不被切换器重建打断
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
                // 同上：不写死 340dp（可压回 ~308dp 导致「能力与插件」裁成「能力与」）。
                modifier = Modifier.fillMaxWidth()
            )
        }
    }

    PredictiveBackOverlay(onBack = onDone) {
        when (tab) {
            0 -> PluginMarketScreen(
                onDone = onDone, mode = 0, topSwitcher = switcher, insideOverlay = true
            )
            1 -> AutomationScreen(
                onDone = onDone,
                initialTab = 1,          // 1=MCP，见 AutomationScreen 的 tab 表
                topSwitcher = switcher,
                insideOverlay = true,
                hideTabs = true          // 别叠两层 tab
            )
            else -> MemoryScreen(
                onDone = onDone,
                initialType = "skill",   // 技能 = 斜杠命令/技能存储
                topSwitcher = switcher,
                insideOverlay = true,
                hideTopBar = true        // 标题重复，去掉本屏顶栏
            )
        }
    }
}
