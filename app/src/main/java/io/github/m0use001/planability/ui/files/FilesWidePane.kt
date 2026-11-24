package io.github.m0use001.planability.ui.files

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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.m0use001.planability.ui.common.BoardDivider
import io.github.m0use001.planability.ui.common.BoardHeader
import io.github.m0use001.planability.ui.common.BoardLayout
import java.io.File

/**
 * 文件库右板（宽屏两栏、还没选具体文件时）。
 *
 * 「未选中的右板不许空着」是这一系列主从页面的统一规矩：这里给出当前目录的规模与构成，
 * 并把最近改动的几个文件列出来——点一下就在**同一块右板**里打开预览（不弹窗、不换页）。
 */
@Composable
internal fun FilesOverviewPane(
    dir: File,
    items: List<File>,
    onPick: (File) -> Unit
) {
    val files = items.filter { !it.isDirectory }
    val dirCount = items.count { it.isDirectory }
    val totalSize = files.sumOf { it.length() }
    val recent = files.sortedByDescending { it.lastModified() }.take(6)
    Column(Modifier.fillMaxSize()) {
        BoardHeader(
            title = "预览",
            subtitle = "点左侧文件在此打开"
        )
        BoardDivider()
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = BoardLayout.Padding, vertical = 12.dp)
        ) {
            // v72（2026-11-21 用户「应该加入圆角矩形来区分」）：与笔记右板同款——
            // 裸统计行 → 三枚数值格；「最近修改」装色调块；空态升级占位块。
            Text(
                dir.name.ifBlank { "文件" },
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(10.dp))
            io.github.m0use001.planability.ui.common.PaneStatRow(
                listOf(
                    "$dirCount" to "文件夹",
                    "${files.size}" to "文件",
                    formatSize(totalSize) to "总大小"
                )
            )
            Spacer(Modifier.height(16.dp))
            if (recent.isEmpty()) {
                io.github.m0use001.planability.ui.common.PaneEmptyBlock("这里还没有可预览的文件")
            } else {
                io.github.m0use001.planability.ui.common.PaneSectionBlock("最近修改") {
                    recent.forEach { f ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .clickable { onPick(f) }
                                .padding(horizontal = 4.dp, vertical = 8.dp)
                        ) {
                            Text(
                                f.name,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                formatSize(f.length()),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}
