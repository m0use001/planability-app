package io.github.m0use001.planability.ui.notes

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.m0use001.planability.data.COVER_SOLID
import io.github.m0use001.planability.data.Graph

/** 右栏一行：笔记 id / 标题 / 类型 / 所属笔记本（含封面风格，供缩略卡）/ 最近更新 / 字数 */
private data class RecentNoteRow(
    val id: Long,
    val title: String,
    val kind: String,
    val bookId: Long,
    val bookName: String,
    val bookStyle: Int,
    val updatedAt: Long,
    val size: Int,
)

/**
 * v60 宽屏笔记页的常驻右栏（2026-11-18）；v69d（2026-11-21 用户「不要全文字，加点图形」）
 * 重做为**封面色缩略卡**：每行左侧一枚所属笔记本的迷你封面（[NotebookCover] 真渲染器按
 * 40dp 缩渲，与笔记本网格同一种视觉语言），右侧标题 + 元信息行（笔记本名 · 相对时间）
 * + 右缘字数/类型胶囊。数据取自既有仓储（[Graph.notes].allNotes，DAO 已按 updatedAt 倒序），
 * 不新增任何存储或口径。
 */
@Composable
fun NotesRecentPane(
    limit: Int = 10,
    onOpenNote: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val version by Graph.dataVersion.flow.collectAsState()
    var rows by remember { mutableStateOf<List<RecentNoteRow>>(emptyList()) }
    LaunchedEffect(version) {
        val books = Graph.notes.notebooks().associateBy { it.id }
        rows = Graph.notes.allNotes().take(limit).map { n ->
            val book = books[n.notebookId]
            RecentNoteRow(
                id = n.id,
                title = n.title,
                kind = n.kind,
                bookId = n.notebookId,
                bookName = book?.name ?: "",
                bookStyle = book?.coverStyle ?: COVER_SOLID,
                updatedAt = n.updatedAt,
                size = if (n.kind == "md") n.content.length else 0
            )
        }
    }
    // 2026-11-20 v65 两板推广：本面板现在是笔记页**右板的全部内容**——
    // 板内不套浮起 Card（板本身是圆角矩形 + 容器底，板里嵌卡 = 第二种材质）；
    // 空库**不整块消失**（「入口无内容应退化不消失」），给占位文案。
    Column(modifier.fillMaxWidth()) {
        Text(
            "最近笔记",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
        )
        if (rows.isEmpty()) {
            // v72：空态升级占位块（与其它右板同一词汇表）
            io.github.m0use001.planability.ui.common.PaneEmptyBlock(
                "还没有笔记\n在左板新建笔记本后写第一条，它会出现在这里",
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            )
            return
        }
        rows.forEachIndexed { i, row ->
            // v69e（2026-11-21 用户参考卡片）：与笔记列表同一版式——标题在左、右上角叠放
            // 缩略卡、日期沉底左。380dp 副框里缩到紧凑档（缩略卡 32dp、日期 labelSmall）。
            Row(
                verticalAlignment = Alignment.Top,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 3.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.55f))
                    .clickable { onOpenNote(row.id) }
                    .padding(horizontal = 12.dp, vertical = 10.dp)
            ) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            row.title.ifBlank { "无标题" },
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                    }
                    Text(
                        listOfNotNull(
                            row.bookName.ifBlank { null },
                            NotePreview.relativeTime(row.updatedAt)
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        modifier = Modifier.padding(top = 3.dp)
                    )
                    // 底行：日期（第三档明暗）+ 类型/字数胶囊，与列表卡同一条基线
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(top = 10.dp).fillMaxWidth()
                    ) {
                        Text(
                            java.text.SimpleDateFormat("yyyy/M/d HH:mm", java.util.Locale.getDefault())
                                .format(java.util.Date(row.updatedAt)) + " 更新",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f),
                            maxLines = 1
                        )
                        Spacer(Modifier.weight(1f))
                        Text(
                            when (row.kind) {
                                "pdf" -> "PDF"
                                "freeform" -> "画布"
                                else -> "${row.size} 字"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .background(MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.6f))
                                .padding(horizontal = 8.dp, vertical = 3.dp)
                        )
                    }
                }
                // 右上角叠放缩略卡（紧凑档）：副卡斜放 + 主卡压上，白描边
                Box(Modifier.padding(start = 10.dp, top = 1.dp)) {
                    Box(
                        Modifier
                            .padding(top = 5.dp)
                            .size(width = 26.dp, height = 26.dp / 0.78f)
                            .graphicsLayer { rotationZ = -8f }
                            .clip(RoundedCornerShape(5.dp))
                            .background(MaterialTheme.colorScheme.surface)
                            .padding(1.dp)
                    ) {
                        NotebookCover(row.bookStyle, modifier = Modifier.fillMaxSize(), text = "", layout = 0, title = "")
                    }
                    Box(
                        Modifier
                            .padding(start = 11.dp)
                            .size(width = 30.dp, height = 30.dp / 0.78f)
                            .clip(RoundedCornerShape(6.dp))
                            .background(MaterialTheme.colorScheme.surface)
                            .padding(1.dp)
                    ) {
                        // v70b（用户拍板「这里也要改」）：主卡同笔记行——画内容预览（compact 30dp 档），
                        // 不再一律用笔记本封面；副卡（后面斜放那张）继续画封面保留身份。
                        NoteContentThumb(row.kind, compact = true)
                    }
                }
            }
            if (i < rows.lastIndex) Spacer(Modifier.height(6.dp))
        }
        Spacer(Modifier.height(8.dp))
    }
}
