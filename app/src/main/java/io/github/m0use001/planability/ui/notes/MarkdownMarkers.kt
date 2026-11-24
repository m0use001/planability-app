package io.github.m0use001.planability.ui.notes

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation

/**
 * v54.2 编辑态：把 Markdown 的**结构标记**弱化着色。
 *
 * 编辑区仍然是纯文本（不做所见即所得——那是第 3 期的活），但一眼能看出
 * 「哪些字符是语法、哪些是我的字」：`#`、`-`、`>`、`**`、`` ` `` 这些标记压成弱色，
 * 正文保持正常对比度。视觉上立刻从"代码编辑器"变成"在写东西"。
 *
 * 关键约束：**只着色、不改字**。所以 [OffsetMapping.Identity]——光标位置、
 * 选区、`TextRange` 全部照旧，不会出现「点哪里光标跳别处」这类经典 VisualTransformation 事故。
 */
object MarkdownMarkers {

    private val HEADING = Regex("""^#{1,6}""")
    private val QUOTE = Regex("""^>""")
    private val BULLET = Regex("""^[-*+]""")
    private val ORDERED = Regex("""^\d+[.)]""")
    private val TASK_AFTER_BULLET = Regex("""^[-*+]\s+\[[ xX]]""")
    private val TASK = Regex("""^\[[ xX]]""")
    private val FENCE = Regex("""^```[\w-]*""")

    /**
     * 返回需要弱化的字符区间（含首含尾）。
     *
     * 实现是「**先按位置打点、再合并成段**」而不是逐条收集区间——因为同一次点击
     * 会被多个规则命中（`**粗**` 会同时被"列表符"和"行内 **"标到；``` 会同时被
     * "围栏"和"行内反引号"标到）。直接收集会得到重叠区间，渲染时重复上色、
     * 断言也永远对不齐（这两种情况都是单测抓到的）。
     */
    fun ranges(text: String): List<IntRange> {
        if (text.isEmpty()) return emptyList()
        val mask = BooleanArray(text.length)
        fun mark(from: Int, to: Int) {
            for (i in from..to) if (i in mask.indices) mask[i] = true
        }

        var lineStart = 0
        for (line in text.split('\n')) {
            val t = line.trimStart()
            val base = lineStart + (line.length - t.length)
            listOf(HEADING, QUOTE, BULLET, ORDERED, FENCE).forEach { re ->
                val m = re.find(t) ?: return@forEach
                mark(base + m.range.first, base + m.range.last)
            }
            // 任务框：`- [ ] x` 与裸 `[ ] x` 两种写法都要认
            val task = TASK_AFTER_BULLET.find(t) ?: TASK.find(t)
            if (task != null) {
                val idx = t.lastIndexOf('[', task.range.last)
                if (idx >= 0) mark(base + idx, base + minOf(idx + 2, t.length - 1))
            }
            lineStart += line.length + 1
        }
        // 行内标记：逐处打点（不成对也无害——只影响颜色）
        listOf("**", "__", "~~", "`", "[[", "]]").forEach { tok ->
            var i = text.indexOf(tok)
            while (i >= 0) {
                mark(i, i + tok.length - 1)
                i = text.indexOf(tok, i + tok.length)
            }
        }
        // 合并连续打点
        val out = ArrayList<IntRange>()
        var start = -1
        for (i in mask.indices) {
            if (mask[i]) { if (start < 0) start = i }
            else if (start >= 0) { out += start..(i - 1); start = -1 }
        }
        if (start >= 0) out += start..(mask.size - 1)
        return out
    }
}

/**
 * 把 [MarkdownMarkers.ranges] 换成弱色。
 * @param markerColor 标记色（一般是 `onSurfaceVariant` 的淡化版）
 */
class MarkdownMarkerTransform(private val markerColor: Color) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val ranges = MarkdownMarkers.ranges(text.text)
        if (ranges.isEmpty()) return TransformedText(text, OffsetMapping.Identity)
        val styled = buildAnnotatedString {
            append(text)
            ranges.forEach { r ->
                addStyle(
                    SpanStyle(color = markerColor, fontWeight = FontWeight.Normal),
                    r.first,
                    r.last + 1
                )
            }
        }
        return TransformedText(styled, OffsetMapping.Identity)
    }
}
