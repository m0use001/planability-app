package io.github.m0use001.planability.ui.notes

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.m0use001.planability.ui.theme.SeedColors
import kotlin.math.abs
import kotlin.math.max

/**
 * 树状图 / 旭日图共用的分层数据。
 *
 * 输入是缩进树（和思维导图同一种写法）：
 * ```
 * 总收入          ← depth 0
 * - 产品          ← depth 1
 *   - 订阅 40     ← depth 2
 *   - 广告 35     ← depth 2
 * ```
 * **每个节点的数值 = 自己直接挂着的子节点之和**（叶子用自己写的数）。
 * 这与树形图（TreeBlock）不同 —— 树形图是"结构图"，不编码大小；树状图是"占比图"。
 */
internal data class HierNode(
    val name: String,
    val value: Float,
    val depth: Int,
    val children: List<HierNode>
)

internal fun parseHierarchy(lines: List<String>): HierNode? {
    val raw = ArrayList<Pair<Int, Pair<String, Float>>>()
    lines.forEach { line ->
        val l = line.trimEnd()
        if (l.isBlank()) return@forEach
        val indent = l.length - l.trimStart().length
        val body = l.trimStart()
        val dashes = body.takeWhile { it == '-' }.length
        val rest = if (dashes > 0) body.drop(dashes).trim() else body.trim()
        if (rest.isBlank()) return@forEach
        val name = rest.substringBefore(":").trim()
        val explicit = rest.substringAfter(":", "").trim().toFloatOrNull()
        val depth: Int = if (dashes > 0) maxOf(dashes, indent / 2 + 1) else (if (raw.isEmpty()) 0 else indent / 2 + 1)
            .coerceIn(0, 8)
        raw += depth to (name to (explicit ?: 0f))
    }
    if (raw.isEmpty()) return null

    // 合成父节点数值：叶子用自己的值，非叶子 = 自己的值 + 所有直接子节点之和。
    //
    // 关键：**必须自底向上**（按深度从大到小），否则父节点会在收到子节点的值之前
    // 就被处理掉。原来的循环是自顶向下，实测踩了：
    //   项目成本分布            (无值=0)
    //   - 开发:5000             (depth1)
    //     - 前端:2500           (depth2)
    //   - 设计:1500             (depth1)
    // 自顶向下时，"前端"把 2500 加给了"开发"（正确），但"开发"这一轮已经过去了，
    // 于是 **2500 永远加不到根节点**；最后根节点只收到"设计+运维"=2300，
    // 而"开发"虚增成 7500。树状图按根值切面积，根值错了整张图就错。
    val nodeVals: MutableList<Float> = raw.map { it.second.second }.toMutableList()
    // 记录每个节点的直接父下标
    val parentOf = IntArray(raw.size) { -1 }
    val lastAtDepth = IntArray(16) { -1 }
    for (i in raw.indices) {
        val d = raw[i].first
        for (p in d - 1 downTo 0) {
            if (lastAtDepth[p] >= 0) { parentOf[i] = lastAtDepth[p]; break }
        }
        if (d < 16) lastAtDepth[d] = i
    }
    // 按深度从大到小：先让深层把自己的值交给父，再让父继续往上交
    val maxDepth = raw.maxOf { it.first }
    for (d in maxDepth downTo 1) {
        for (i in raw.indices) {
            if (raw[i].first == d) {
                val par = parentOf[i]
                if (par >= 0) nodeVals[par] += nodeVals[i]
            }
        }
    }

    // 建树
    class B(val name: String, val depth: Int, var value: Float) {
        val kids = mutableListOf<B>()
    }
    val roots = mutableListOf<B>()
    val stack = mutableListOf<B>()
    raw.forEachIndexed { i, item ->
        val d = item.first
        val b = B(item.second.first, d, nodeVals[i])
        while (stack.size > d) stack.removeAt(stack.size - 1)
        if (stack.isEmpty()) roots += b else stack.last().kids += b
        stack += b
    }
    fun conv(b: B): HierNode = HierNode(b.name, b.value, b.depth, b.kids.map { conv(it) })

    // 多根时包一层虚拟根，保证树状图/旭日图有单一父节点可算面积
    if (roots.size == 1) return conv(roots[0])
    return HierNode(
        "总计", roots.sumOf { it.value.toDouble() }.toFloat(), 0, roots.map { conv(it) }
    )
}

/** 树状图：嵌套矩形面积编码大小（右上角三点 → 详细编辑） */
@Composable
fun TreemapBlock(raw: String) {
    var t by remember { mutableStateOf("treemap") }
    ChartFrame(
        chartType = t,
        title = "",
        switchableTypes = listOf("treemap", "sunburst"),
        onTypeChange = { t = it }
    ) { _ ->
        // 同一份缩进树数据，面积编码 ↔ 角度编码，可直接互换
        if (t == "sunburst") SunburstBlockBody(raw) else TreemapBlockBody(raw)
    }
}

@Composable
private fun TreemapBlockBody(raw: String) {

    val root = remember(raw) { parseHierarchy(raw.lines().filter { it.isNotBlank() }) }
    if (root == null || root.children.isEmpty()) {
        Text(
            "（树状图内容为空，格式：根节点 + \"- 子节点:数值\" 缩进排列）",
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error
        )
        return
    }
    val measurer = rememberTextMeasurer()
    val base = SeedColors.chartPalette(isSystemInDarkTheme())
    val border = MaterialTheme.colorScheme.surface
    val textOnFill = Color.White
    val heightDp = ChartOptions.defaultHeightFor("treemap")

    Canvas(Modifier.fillMaxWidth().height(heightDp.dp).padding(vertical = 4.dp)) {
        val w = size.width
        val h = size.height
        if (root.value <= 0f) return@Canvas

        // 收集叶子（面积只由叶子承担，父节点作为标题画在子区顶部）
        val leaves = mutableListOf<HierNode>()
        fun collect(n: HierNode) {
            if (n.children.isEmpty()) leaves += n else n.children.forEach { collect(it) }
        }
        collect(root)

        fun drawArea(node: HierNode, x: Float, y: Float, aw: Float, ah: Float, depth: Int) {
            if (aw < 1f || ah < 1f || node.value <= 0f) return
            // 父节点：先铺一块深色底当标题条
            if (node.children.isNotEmpty()) {
                val headH = (18f).coerceAtMost(ah * 0.28f)
                drawRect(
                    color = base[depth % base.size].copy(alpha = 0.55f),
                    topLeft = Offset(x, y), size = Size(aw, ah)
                )
                val m = measurer.measure(
                    AnnotatedString(node.name), TextStyle(fontSize = 10.sp, color = textOnFill)
                )
                if (m.size.width + 8 <= aw && headH >= 12f) {
                    drawText(
                        measurer, AnnotatedString(node.name),
                        topLeft = Offset(x + 4f, y + (headH - m.size.height) / 2f),
                        style = TextStyle(fontSize = 10.sp, color = textOnFill)
                    )
                }
                val inner = HierNode(node.name, node.value, depth, node.children)
                // 子节点**横向铺列**、每列内部按比例纵向切 —— 这才是树状图的经典形态。
                //
                // 原来写成"全部横向铺满、纵向堆叠"，于是 餐饮/交通/购物 各占满整幅宽度、
                // 它们的子项又叠在同一竖列里，看上去就是一层薄薄的横条，读不出层级
                // （真机截图里 3 个子节点各占满整宽，只剩一条缝）。
                val total = inner.children.sumOf { it.value.toDouble() }.toFloat().coerceAtLeast(0.001f)
                var colX = x
                var colRemain = aw
                inner.children.forEachIndexed { i, ch ->
                    val share = (ch.value / total).coerceIn(0f, 1f)
                    val isLast = i == inner.children.lastIndex
                    val colW = if (isLast) colRemain else (aw * share).coerceAtLeast(24f)
                    // 列头写父级自己的名字，让每一列属于谁一眼可见
                    val colHeadH = (16f).coerceAtMost((ah - headH) * 0.22f)
                    drawRect(
                        color = base[depth % base.size].copy(alpha = 0.75f),
                        topLeft = Offset(colX, y + headH), size = Size(colW, colHeadH)
                    )
                    val st = TextStyle(fontSize = 10.sp, color = textOnFill)
                    val m = measurer.measure(AnnotatedString(node.name), st)
                    if (m.size.width + 8 <= colW && colHeadH >= 12f) {
                        drawText(
                            measurer, AnnotatedString(node.name),
                            topLeft = Offset(colX + 4f, y + headH + (colHeadH - m.size.height) / 2f),
                            style = st
                        )
                    }
                    val innerTop = y + headH + colHeadH
                    val innerH = ah - headH - colHeadH
                    var cy = innerTop
                    var rem = innerH
                    ch.children.forEachIndexed { j, gc ->
                        val gShare = (gc.value / ch.value.coerceAtLeast(0.001f)).coerceIn(0f, 1f)
                        val gLast = j == ch.children.lastIndex
                        val gH = if (gLast) rem else (innerH * gShare).coerceAtLeast(18f)
                        drawArea(gc, colX, cy, colW, gH, depth + 2)
                        cy += gH
                        rem -= gH
                    }
                    drawRect(
                        color = border.copy(alpha = 0.7f),
                        topLeft = Offset(colX, y + headH), size = Size(colW, ah - headH),
                        style = Stroke(width = 1.5f)
                    )
                    colX += colW
                    colRemain -= colW
                }
                drawRect(
                    color = border.copy(alpha = 0.6f),
                    topLeft = Offset(x, y), size = Size(aw, ah),
                    style = Stroke(width = 1.5f)
                )
            } else {
                // 叶子：整块归它，颜色按深度交替
                val c = base[(depth + 1) % base.size]
                drawRect(color = c, topLeft = Offset(x, y), size = Size(aw, ah))
                val style = TextStyle(fontSize = 10.sp, color = textOnFill)
                val m = measurer.measure(AnnotatedString(node.name), style)
                if (m.size.width + 8 <= aw && ah >= m.size.height + 6f) {
                    drawText(measurer, AnnotatedString(node.name), topLeft = Offset(x + 4f, y + (ah - m.size.height) / 2f), style = style)
                }
                drawRect(
                    color = border.copy(alpha = 0.6f),
                    topLeft = Offset(x, y), size = Size(aw, ah),
                    style = Stroke(width = 1.5f)
                )
            }
        }
        drawArea(root, 0f, 0f, w, h, 0)
    }
    Spacer(Modifier.height(8.dp))
}

/**
 * 旭日图：同心圆环，每圈一层，首层是根，圆心留空。
 *
 * 与树状图同一份 [parseHierarchy] 数据，只是映射方式不同 ——
 * 角度编码占比、半径编码深度，比树状图省地方，更适合层级 ≤4 层。
 */
@Composable
fun SunburstBlock(raw: String) {
    var t by remember { mutableStateOf("sunburst") }
    ChartFrame(
        chartType = t,
        title = "",
        switchableTypes = listOf("sunburst", "treemap"),
        onTypeChange = { t = it }
    ) { _ ->
        if (t == "treemap") TreemapBlockBody(raw) else SunburstBlockBody(raw)
    }
}

@Composable
private fun SunburstBlockBody(raw: String) {

    val root = remember(raw) { parseHierarchy(raw.lines().filter { it.isNotBlank() }) }
    if (root == null || root.children.isEmpty()) {
        Text(
            "（旭日图内容为空，格式：根节点 + \"- 子节点:数值\" 缩进排列）",
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error
        )
        return
    }
    val measurer = rememberTextMeasurer()
    val base = SeedColors.chartPalette(isSystemInDarkTheme())
    val heightDp = ChartOptions.defaultHeightFor("sunburst")
    var title = root.name

    Box(Modifier.fillMaxWidth()) {
        Canvas(Modifier.fillMaxWidth().height(heightDp.dp).padding(vertical = 4.dp)) {
            val w = size.width
            val cx = w / 2f
            val cy = size.height / 2f
            val outerR = minOf(w, size.height) / 2f - 4f
            val innerR = outerR * 0.30f
            val ringW = (outerR - innerR) / 4f

            fun ring(depth: Int, spanFrom: Float, spanTo: Float, node: HierNode) {
                if (node.children.isEmpty() || depth > 3) return
                val total = node.value.coerceAtLeast(0.001f)
                var a = spanFrom
                node.children.forEachIndexed { i, ch ->
                    val share = (ch.value / total).coerceIn(0f, 1f)
                    val a2 = if (i == node.children.lastIndex) spanTo else a + (spanTo - spanFrom) * share
                    val rOut = innerR + ringW * (depth + 1)
                    val sweep = a2 - a
                    drawArc(
                        color = base[(depth + 1) % base.size],
                        startAngle = a - 90f, sweepAngle = sweep, useCenter = false,
                        topLeft = Offset(cx - rOut, cy - rOut),
                        size = Size(rOut * 2, rOut * 2),
                        style = Stroke(width = ringW * 0.82f)
                    )
                    // 只有扇形够大才标名字，否则一圈全是碎字
                    if (sweep > 0.14f) {
                        val mid = (a + a2) / 2f
                        val rad = (a - 90f) * (Math.PI / 180.0)
                        val rr = (innerR + ringW * (depth + 0.5f)).toDouble()
                        val lx = (cx + rr * kotlin.math.cos(rad)).toFloat()
                        val ly = (cy + rr * kotlin.math.sin(rad)).toFloat()
                        val st = TextStyle(fontSize = 9.sp, color = Color.White)
                        val m = measurer.measure(AnnotatedString(ch.name), st)
                        if (m.size.width < ringW * 1.6f) {
                            drawText(
                                measurer, AnnotatedString(ch.name),
                                topLeft = Offset(lx - m.size.width / 2f, ly - m.size.height / 2f),
                                style = st
                            )
                        }
                    }
                    ring(depth + 1, a, a2, ch)
                    a = a2
                }
            }
            // 内圈（深度 0）
            drawArc(
                color = base[0],
                startAngle = -90f, sweepAngle = 360f, useCenter = false,
                topLeft = Offset(cx - innerR, cy - innerR),
                size = Size(innerR * 2, innerR * 2),
                style = Stroke(width = ringW * 0.82f)
            )
            ring(0, 0f, 360f, root)
        }
        if (title.isNotBlank()) {
            Text(
                title, style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.Center)
            )
        }
    }
    Spacer(Modifier.height(8.dp))
}