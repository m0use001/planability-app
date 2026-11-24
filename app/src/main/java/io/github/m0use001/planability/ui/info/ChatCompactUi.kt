package io.github.m0use001.planability.ui.info

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.m0use001.planability.ai.ChatRound
import io.github.m0use001.planability.ai.chatRounds
import io.github.m0use001.planability.ai.effectiveTranscript
import io.github.m0use001.planability.core.LocalHaptics
import io.github.m0use001.planability.data.ChatMessage
import io.github.m0use001.planability.data.CompactMarkerEntity
import io.github.m0use001.planability.data.Graph

/**
 * v50：**已压缩区间的摘要卡**（会话界面渲染）。
 *
 * 用户视角（2026-11-04 拍板，v47 交互作废）：
 * - 会话里**默认显示完整摘要文段**（不再 maxLines 截断、不再长按切换）
 * - 顶部一行：左=「压缩」标题 + 小字（压缩轮数 · 压缩幅度），右=「查看原文」按钮
 * - 点「查看原文」**跳独立界面**（[CompactOriginalScreen]），不再在会话里展开原文框
 *
 * 与模型的关系（不变，见 ContextEngine.assemble）：被压的原文不发给模型，
 * **摘要本身作为一条 assistant 消息插在原位发给模型**——所以压缩不丢上下文。
 */
@Composable
internal fun CompactSummaryCard(
    marker: CompactMarkerEntity,
    /** 区间内的原文消息（算压缩幅度用） */
    originals: List<ChatMessage>,
    onOpenOriginal: () -> Unit
) {
    val cs = MaterialTheme.colorScheme
    val haptics = LocalHaptics.current
    // 轮数：区间内的消息按「1 轮 = 1 条 user + AI 对它的全部回答」分（与详细编辑屏同口径）
    val rounds = remember(marker.id, originals) { chatRounds(originals) }
    // 压缩幅度：原文正文字符量 → 摘要字符量（工具调用 JSON 不算——用户读到的是正文）
    val subline = remember(marker.id, originals) { compactionSubline(marker, originals, rounds) }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            // 摘要卡 = 中性容器色（与消息气泡区分开，表示"这是替代品不是原话"）
            .background(cs.surfaceContainerHigh)
            .border(1.dp, cs.outlineVariant, RoundedCornerShape(14.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    "压缩",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = cs.onSurface
                )
                Text(
                    subline,
                    style = MaterialTheme.typography.labelSmall,
                    color = cs.onSurfaceVariant
                )
            }
            TextButton(onClick = {
                haptics.perform(io.github.m0use001.planability.core.HapticEngine.Kind.Tap)
                onOpenOriginal()
            }) {
                Text("查看原文", style = MaterialTheme.typography.labelMedium)
            }
        }
        Spacer(Modifier.height(4.dp))
        // 完整摘要文段——它就是要给模型看的内容，界面上也不藏
        Text(
            marker.summary,
            style = MaterialTheme.typography.bodyMedium,
            color = cs.onSurface
        )
    }
}

/**
 * 2026-11-15 用户要求：压缩摘要顶部要显示「从多少 tok 压缩到多少 tok + 压缩率」。
 * 口径 = token（与详细上下文编辑屏同一口径 [ContextEngine.estTokens]），
 * 原文量按原始消息正文累加；工具调用行正文常空但调用骨架也是原文量，记 40。
 */
internal data class CompactionStats(val origTok: Int, val sumTok: Int, val percent: Int)

internal fun compactionStats(originals: List<ChatMessage>, summary: String): CompactionStats? {
    if (originals.isEmpty() || summary.isBlank()) return null
    val origTok = originals.sumOf { m ->
        if (m.content.isBlank() && m.toolCallsJson.isNotBlank()) 40
        else io.github.m0use001.planability.ai.ContextEngine.estTokens(m.content)
    }
    val sumTok = io.github.m0use001.planability.ai.ContextEngine.estTokens(summary)
    if (origTok <= 0 || sumTok <= 0) return null
    val ratio = 1.0 - sumTok.toDouble() / origTok.toDouble()
    return CompactionStats(origTok, sumTok, (ratio * 100).toInt())
}

/** 摘要卡顶部小字：「12 轮 · 3720 tok → 480 tok · 压缩 87%」（原文/摘要为空时退化成纯轮数） */
internal fun compactionSubline(
    marker: CompactMarkerEntity,
    originals: List<ChatMessage>,
    rounds: List<ChatRound>
): String {
    val head = "${rounds.size} 轮"
    val s = compactionStats(originals, marker.summary) ?: return head
    // 从多少 tok 压缩到多少 tok + 压缩率（用户 2026-11-15 明确要的三段信息）
    return if (s.percent > 0) "$head · ${s.origTok} tok → ${s.sumTok} tok · 压缩 ${s.percent}%"
    else "$head · ${s.origTok} tok → ${s.sumTok} tok"
}

/**
 * v50：**压缩原文独立查看页**（点摘要卡「查看原文」进入）。
 *
 * v47 原来把原文展开塞在会话流里（长按切换），改为跳独立界面——
 * 会话流保持"只显示摘要"的干净形态，原文整页铺开（不截断）。
 * 框的颜色语言保留：tertiaryContainer 底 + 描边，一眼知道"这段已不进模型上下文"。
 */
@Composable
internal fun CompactOriginalScreen(
    sessionId: Long,
    marker: CompactMarkerEntity,
    onDismiss: () -> Unit
) {
    val haptics = LocalHaptics.current
    var originals by remember { mutableStateOf<List<ChatMessage>>(emptyList()) }
    LaunchedEffect(sessionId, marker.id) {
        val s = Graph.chat.session(sessionId) ?: return@LaunchedEffect
        val msgs = effectiveTranscript(Graph.chat.messagesOf(sessionId), s.clearBefore)
        originals = msgs.filter {
            it.id >= marker.firstCompactedMessageId && it.id <= marker.lastCompactedMessageId
        }
    }
    // 返回=直接调 onDismiss。**不能**走 LocalOverlayClose：本屏由 ChatScreen 直接挂载
    // （不在任何父 overlay 里），组合期捕获到的是空实现——返回键与手势都会变成空操作
    // （与 ContextEditorScreen 同一个坑，见那边的注释）。
    io.github.m0use001.planability.app.PredictiveBackOverlay(onBack = {
        haptics.perform(io.github.m0use001.planability.core.HapticEngine.Kind.Tap)
        onDismiss()
    }) {
        Column(
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surface)
                .statusBarsPadding()
                .navigationBarsPadding()
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp)
            ) {
                IconButton(onClick = {
                    haptics.perform(io.github.m0use001.planability.core.HapticEngine.Kind.Tap)
                    onDismiss()
                }) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "返回")
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        "压缩的原文",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        "这段不再发给模型；会话里的摘要才是模型看到的",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Column(
                Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 14.dp)
                    .padding(bottom = 14.dp)
            ) {
                CompactedOriginalFrame {
                    originals.forEach { m ->
                        val who = when (m.role) {
                            "user" -> "用户"
                            "tool" -> "工具结果"
                            else -> "助手"
                        }
                        Column {
                            Text(
                                who,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.tertiary
                            )
                            Text(
                                m.content.ifBlank {
                                    if (m.toolCallsJson.isNotBlank()) "（工具调用）" else "（空）"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 被压缩原文的**外框**：不同背景色 + 边框，把这段圈出来（用户明确要求）。 */
@Composable
internal fun CompactedOriginalFrame(content: @Composable () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            // 压缩区背景：tertiaryContainer 低透明（与普通气泡的 surfaceContainer 明显不同）
            .background(cs.tertiaryContainer.copy(alpha = 0.35f))
            .border(1.dp, cs.tertiary.copy(alpha = 0.5f), RoundedCornerShape(14.dp))
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(cs.tertiary)
            )
            Spacer(Modifier.width(6.dp))
            Text(
                "以下为已压缩的原文（不进模型上下文）",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = cs.tertiary
            )
        }
        content()
    }
}
