package io.github.m0use001.planability.ui.notes

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * v49 知识库：编辑器里的 `[[` 笔记链接补全。
 *
 * 只做「光标前刚敲了 `[[` 或 `[[某某` 时，列出匹配标题」——不做全量链接语法高亮，
 * 也不改写已有正文（补全只在用户点选时插入 `[[标题]]`，一次落定）。
 */

/** 光标处的未完成链接：start = `[[` 起始下标，query = 已输入的目标前缀 */
data class LinkQuery(val start: Int, val query: String)

/**
 * 光标位置是否处在一个未闭合的 `[[` 里。
 *
 * 规则：
 * - 只看光标前最近的一对 `[[`，且中间不能再出现 `]]`；
 * - 同一行内（跨行的 `[[` 不是链接）；
 * - 中间不能有 `[`（避免 `[[a[[b` 误判）；
 * - 允许 query 为空（刚敲完 `[[` 就弹全量候选）。
 */
fun linkQueryAt(text: String, cursor: Int): LinkQuery? {
    if (cursor <= 1 || cursor > text.length) return null
    val before = text.substring(0, cursor)
    val open = before.lastIndexOf("[[")
    if (open < 0) return null
    val inner = before.substring(open + 2)
    if (inner.contains("]]") || inner.contains('[') || inner.contains('\n')) return null
    if (inner.contains('|')) return null // 已经写到别名段，不再补全目标
    return LinkQuery(open, inner.trim())
}

/** 候选条：横向一排 chip，点了就把 `[[` 补成 `[[标题]]` */
@Composable
fun LinkSuggestBar(
    items: List<String>,
    modifier: Modifier = Modifier,
    onPick: (String) -> Unit
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 3.dp
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                "链接到",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 10.dp)
            )
            items.take(4).forEach { title ->
                SuggestionChip(
                    onClick = { onPick(title) },
                    label = { Text(title, maxLines = 1) }
                )
            }
        }
    }
}
