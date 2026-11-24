package io.github.m0use001.planability.ui.notes

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.m0use001.planability.data.NoteRow
import io.github.m0use001.planability.ui.common.BoardDivider
import io.github.m0use001.planability.ui.common.BoardHeader
import io.github.m0use001.planability.ui.common.BoardLayout

/**
 * 笔记页右板（宽屏、还没选具体笔记时）。
 *
 * 以前这里只有一句「从左侧选择一篇笔记」——占了半屏的空白（「别空」批的规矩）。
 * 现在给出这本笔记本的规模与最近改动的几篇，点一下就在右板里打开。
 */
@Composable
internal fun NotesOverviewPane(
    notebookName: String,
    notes: List<NoteRow>,
    onOpen: (Long) -> Unit
) {
    val pinned = notes.count { it.pinned }
    val totalChars = notes.sumOf { it.size }
    val recent = notes.sortedByDescending { it.updatedAt }.take(8)
    Column(Modifier.fillMaxSize()) {
        BoardHeader(
            title = notebookName,
            subtitle = "点左侧笔记在此打开"
        )
        BoardDivider()
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = BoardLayout.Padding, vertical = 12.dp)
        ) {
            // v72（2026-11-21 用户「应该加入圆角矩形来区分」）：裸统计行 → 三枚数值格，
            // 「最近修改」列表装进色调块，空态升级占位块（正身都是 ui/common.PaneBlocks）。
            io.github.m0use001.planability.ui.common.PaneStatRow(
                listOf(
                    "${notes.size} 篇" to "笔记",
                    "$pinned 篇" to "置顶",
                    totalChars.abbrChars() to "总字数"
                )
            )
            Spacer(Modifier.height(16.dp))
            if (recent.isEmpty()) {
                io.github.m0use001.planability.ui.common.PaneEmptyBlock(
                    "这本笔记本还是空的：点右下角新建，或从别处移进来"
                )
            } else {
                io.github.m0use001.planability.ui.common.PaneSectionBlock("最近修改") {
                    recent.forEach { n ->
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .clickable { onOpen(n.id) }
                                .padding(horizontal = 4.dp, vertical = 8.dp)
                        ) {
                            Row {
                                Text(
                                    n.title.ifBlank { "无标题" },
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                                Text(
                                    "${n.size} 字",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            if (n.preview.isNotBlank()) {
                                Text(
                                    n.preview,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** 大数缩写（v72）：超过 1 万字按 x.x 万 缩，统计格里放不下全数位。 */
private fun Int.abbrChars(): String = when {
    this >= 10000 -> String.format(java.util.Locale.US, "%.1f 万", this / 10000f)
    else -> toString()
}
