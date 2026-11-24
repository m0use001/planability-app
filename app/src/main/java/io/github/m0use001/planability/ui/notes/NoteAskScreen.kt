package io.github.m0use001.planability.ui.notes

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.m0use001.planability.ai.rag.KbAsk

/**
 * v56「问笔记」的证据 UI 组件（判定横幅 / 来源徽章证据卡）。
 *
 * 原为独立整屏 `NoteAskScreen`；2026-11-07 用户拍板「笔记右上角仅保留一个搜索按钮」
 * 后并入 [GlobalNoteSearchScreen] 的「问笔记」模式——那里管查询与导航，这里只管
 * 证据怎么摆。语义模型管理也同时收拢进「模型设置」（ModelDisplayScreen 本地模型区）。
 *
 * 边界（用户拍板）：笔记侧 AI 只做**本机检索**——[KbAsk] 的双路 FTS + 分块 BM25 +
 * 本地向量（语义第三路/语义门/分块重排，全在端侧小模型上），不接对话、不接 LLM 生成；
 * 「组织成答案」是对话工作台（AI 会话里的 kb_ask 工具）的活，这里只交证据。
 */

/** 纯噪声判定：门未过且覆盖率干净为零——向量路对无意义查询也会给 top-K，纯噪声不配上桌 */
internal fun isNoise(r: KbAsk.Result): Boolean = !r.inDomain && r.coverage <= 0.0

/**
 * 判定横幅（诚实边界，与工具层 kb_ask 同一口径的三态）：
 * 有证据且词面/语义达标 → 「有相关记录」；有证据但不达标 → 分两档：
 * 覆盖率非零才列「间接相关」（覆盖率干净为零 = 检索噪声，当「没查到」）；
 * 无证据 → 「没有查到」。横幅只陈述，不编答案。
 */
@Composable
internal fun VerdictBanner(r: KbAsk.Result, semanticReady: Boolean, onRequestModel: () -> Unit) {
    val noise = isNoise(r)
    val (container, content) = when {
        r.evidence.isEmpty() || noise ->
            MaterialTheme.colorScheme.surfaceContainer to MaterialTheme.colorScheme.onSurfaceVariant
        r.inDomain ->
            MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.onSecondaryContainer
        else ->
            MaterialTheme.colorScheme.tertiaryContainer to MaterialTheme.colorScheme.onTertiaryContainer
    }
    Surface(
        color = container,
        contentColor = content,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Text(
                when {
                    r.evidence.isEmpty() || noise -> "笔记里没有查到相关内容——可能还没记录"
                    r.inDomain -> "笔记里有相关记录"
                    else -> "覆盖度偏低（${(r.coverage * 100).toInt()}%）——以下是间接相关的内容，请自行核对"
                },
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold
            )
            if (!semanticReady) {
                Text(
                    "想连「意思相近」的内容一起查？点此下载语义模型",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .padding(top = 3.dp)
                        .clickable(onClick = onRequestModel)
                )
            }
        }
    }
}

@Composable
internal fun EvidenceCard(ev: KbAsk.Evidence, needles: List<String>, bookName: String, onClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                NotePreview.stripLine(ev.title).ifBlank { "无标题" },
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false)
            )
            Spacer(Modifier.size(6.dp))
            // 来源徽章：标题 / 正文 / 标题+正文 / 分块 N / 语义 / 链接——命中怎么来的说在明处
            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(
                    ev.source,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }
            if (bookName.isNotBlank()) {
                Text(
                    "  $bookName",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            }
        }
        Spacer(Modifier.height(3.dp))
        HighlightedText(
            text = NotePreview.flatten(ev.snippet, ev.title, 110),
            needles = needles
        )
    }
}
