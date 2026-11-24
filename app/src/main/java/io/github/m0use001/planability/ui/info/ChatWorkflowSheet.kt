package io.github.m0use001.planability.ui.info

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import io.github.m0use001.planability.ui.notes.MdInlineText
import androidx.compose.ui.unit.dp
import io.github.m0use001.planability.ui.common.motionEnter

/**
 * v53 工作流面板（用户笔记本「ai会话界面顶部加上工作流胶囊，点击展开工作流，可展示：
 * 那些每次与ai会话时都要看的东西、ai给出的计划的具体推进进度等」）。
 *
 * 两块：
 * 1. **会话速览**——每次都要确认的那几样：智能体 / 模型 / 思考档 / 权限档 / 上下文占用。
 *    这些原来散在顶栏胶囊和「发送设置」里，要点两三次才看全。
 * 2. **计划推进**——AI 在回复里写的勾选清单（`- [ ]` / `- [x]`），显示完成度、
 *    逐条状态，以及「本轮计划第几次出现」（AI 重发计划即整体覆盖，只显示最新一份）。
 *
 * 只读展示：勾选状态来自 AI 自己的文本，用户改任务走计划页/笔记，不在这里伪造进度。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkflowPanelSheet(
    plans: List<PlanChecklist>,
    assistantLabel: String,
    modelName: String,
    thinkingLevel: String,
    permLabel: String,
    ctxTokens: Int,
    ctxWindow: Int,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val latest = plans.lastOrNull()
    ModalBottomSheet(dragHandle = { io.github.m0use001.planability.ui.common.SheetDragHandleOnly() }, onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier
                .fillMaxWidth()
                // 2026-11-12 两次修复：navigationBarsPadding 前置/后置在 ModalBottomSheet
                // 里都**实测无效**（inset 被消费 → padding 返回 0，v19.14 同款教训，见
                // ChatScreen InputBar 注释）。最终方案=滚动内容尾部放显式量导航栏高度的
                // Spacer（windowInsetsBottomHeight 直读根窗口 inset），见 Column 末尾。
                .verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text("工作流", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                "会话速览 + AI 计划的推进进度",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 10.dp)
            )

            Text(
                "会话速览",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary
            )
            OverviewRow("智能体", assistantLabel.ifBlank { "通用助手" })
            OverviewRow("模型", modelName.ifBlank { "—" })
            OverviewRow("思考", io.github.m0use001.planability.ai.ThinkingLevels.label(thinkingLevel))
            OverviewRow("权限", permLabel)
            OverviewRow(
                "上下文",
                if (ctxWindow > 0) "${(ctxTokens * 100L / ctxWindow).toInt()}%（$ctxTokens / $ctxWindow）"
                else "$ctxTokens tokens"
            )

            Spacer(Modifier.height(12.dp))
            Text(
                "计划推进",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary
            )
            if (latest == null) {
                Text(
                    "本会话里 AI 还没有给出勾选清单形式的计划。\n" +
                        "（要它排计划时让它写 `- [ ] 步骤` 清单，这里就会显示推进进度）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp, bottom = 4.dp)
                )
            } else {
                val frac = if (latest.totalCount > 0) latest.doneCount.toFloat() / latest.totalCount else 0f
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "${latest.doneCount} / ${latest.totalCount} 已完成",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.weight(1f))
                    if (plans.size > 1) {
                        Text(
                            "第 ${plans.size} 版计划",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                LinearProgressIndicator(
                    progress = { frac },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp)
                        .clip(RoundedCornerShape(percent = 50))
                )
                Spacer(Modifier.height(10.dp))
                latest.items.forEach { item ->
                    Row(
                        verticalAlignment = Alignment.Top,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)
                    ) {
                        Icon(
                            if (item.done) Icons.Rounded.CheckCircle
                            else Icons.Rounded.RadioButtonUnchecked,
                            null,
                            Modifier.size(15.dp).padding(top = 1.dp),
                            tint = if (item.done) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.size(8.dp))
                        // 2026-11-11 用户截图：勾选清单里「**归纳法**」加粗裸露、`$a_n>2$`
                        // 公式漏原文——计划项走和正文同一条行内富文本链路（inlineMd+公式）。
                        MdInlineText(
                            item.text,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (item.done) MaterialTheme.colorScheme.onSurfaceVariant
                            else Color.Unspecified
                        )
                    }
                }
                if (latest.doneCount < latest.totalCount) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "进度来自 AI 自己写的勾选清单，不会自动打勾——" +
                            "想让它更新就说「更新计划勾选」，它会重发一份带 [x] 的清单。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            // 尾部手势条垫高（与「模型与思考」sheet 同款，2026-11-12）：navigationBarsPadding
            // 在 ModalBottomSheet 里被 inset 消费返回 0，显式量导航栏高度的 Spacer 才可靠
            Spacer(Modifier.height(16.dp))
            Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
        }
    }
}

@Composable
private fun OverviewRow(label: String, value: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .motionEnter()
            .padding(vertical = 3.dp)
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(width = 52.dp, height = 20.dp)
        )
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
