package io.github.m0use001.planability.ui.notes

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * v53.6 第 1 期「链接可走」：把正文里的 `[[标题]]` 渲染成**可点胶囊**。
 *
 * 借鉴来源（已读源码）：
 * - Logseq `<open-page-ref`：正文前向链可点；命中去目标页，**悬空则弹「创建」**。
 * - Obsidian / Noteriv：`#锚点`、`|别名` 语法。
 *
 * 为什么用 CompositionLocal 而不是加参数：渲染链是
 * MarkdownView → MdTextLines → MdInlineText，而 MdInlineText 在 MdTextLines 里有十来个调用点；
 * 逐个加参既吵又容易漏。CompositionLocal 让「这个界面知道链接该往哪走」成为一个上下文，
 * 聊天页等**不知道链接归属**的调用点自动保持旧行为（原样文本），零改动。
 *
 * 渲染层只负责画与点击；链接图的边仍由 NotesRepo.rebuildLinks 在保存时重建，这里不写库。
 */
data class WikiLinkCtx(
    /** 归一化小写标题 → 笔记 id；查不到 = 悬空（点击走创建） */
    val byTitle: Map<String, Long>,
    /** 命中：先落盘当前笔记，再打开目标。第二参是 `#锚点`（空串 = 从顶部打开） */
    val onOpen: (Long, String) -> Unit,
    /** 悬空：交给调用方弹「创建《标题》」确认 */
    val onCreate: (String) -> Unit
)

val LocalWikiLinks = compositionLocalOf<WikiLinkCtx?> { null }

/** 一行文本按 `[[…]]` 切出来的片段。 */
sealed class WikiSeg {
    data class Txt(val text: String) : WikiSeg()
    data class Link(val label: String, val target: String, val anchor: String) : WikiSeg()
}

private val WS = Regex("\\s+")

/**
 * 把一行切成「普通文本 / 链接」片段。
 *
 * 与 [io.github.m0use001.planability.ai.rag.WikiLinks.parse] 的区别：那个是**去重建图**用的
 * （一篇里同一标题只留一条边），这里是**逐处渲染**用的（同一标题出现三次就画三个胶囊）。
 * 语法口径保持一致，但不复用它的输出。
 *
 * 行内码（成对反引号）里的 `[[x]]` 不渲染成链接——写代码/示例时不该冒出可点胶囊。
 */
internal fun splitWikiSegments(text: String): List<WikiSeg> {
    if (!text.contains("[[")) return listOf(WikiSeg.Txt(text))
    val out = ArrayList<WikiSeg>()
    var i = 0
    var txtStart = 0
    while (true) {
        val open = text.indexOf("[[", i)
        if (open < 0) break
        if (insideInlineCode(text, open)) { i = open + 2; continue }
        val close = text.indexOf("]]", open + 2)
        if (close < 0) break
        val inner = text.substring(open + 2, close)
        if (inner.contains('\n')) { i = close + 2; continue }
        val head = inner.substringBefore('|')
        val title = head.substringBefore('#').trim().replace(WS, " ")
        val anchor = head.substringAfter('#', "").trim().replace(WS, " ")
        val alias = inner.substringAfter('|', "").trim()
        // 超长标题视为普通文本（与 WikiLinks.parse 的 120 字上限一致，防病态匹配）
        if (title.isEmpty() || title.length > 120) { i = close + 2; continue }
        if (txtStart < open) out += WikiSeg.Txt(text.substring(txtStart, open))
        out += WikiSeg.Link(label = alias.ifBlank { title }, target = title, anchor = anchor)
        i = close + 2
        txtStart = i
    }
    if (txtStart < text.length) out += WikiSeg.Txt(text.substring(txtStart))
    return if (out.isEmpty()) listOf(WikiSeg.Txt(text)) else out
}

/** 位置 idx 是否落在行内码（成对反引号）内部。 */
private fun insideInlineCode(text: String, idx: Int): Boolean {
    var ticks = 0
    for (k in 0 until idx) if (text[k] == '`') ticks++
    return ticks % 2 == 1
}

/** 链接胶囊：命中 → 主色淡底；悬空 → 弱化文字（一眼看出「这篇还没写」），点了走创建。 */
@Composable
fun WikiChip(label: String, target: String, anchor: String = "") {
    val ctx = LocalWikiLinks.current ?: return
    val id = ctx.byTitle[target.lowercase()]
    val dangling = id == null
    val scheme = MaterialTheme.colorScheme
    Text(
        text = if (anchor.isBlank()) label else "$label › $anchor",
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = FontWeight.Medium,
        color = if (dangling) scheme.onSurfaceVariant else scheme.primary,
        modifier = Modifier
            .clip(RoundedCornerShape(percent = 50))
            .background(if (dangling) scheme.surfaceContainerHigh else scheme.primary.copy(alpha = 0.12f))
            .clickable { if (id != null) ctx.onOpen(id, anchor) else ctx.onCreate(target) }
            .padding(horizontal = 6.dp, vertical = 1.dp)
    )
}
