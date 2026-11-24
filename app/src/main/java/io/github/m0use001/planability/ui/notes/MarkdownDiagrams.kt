package io.github.m0use001.planability.ui.notes

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import io.github.m0use001.planability.core.hScrollZone
import io.github.m0use001.planability.ui.common.hScrollHint
import io.github.m0use001.planability.ui.theme.SeedColors
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ==================== 十三期：思维导图 / 鱼骨图 块渲染 ====================
// 协议：
// ```mindmap          ```fishbone
// 中心主题            问题：为什么效率低
// - 分支              主因A
// -- 子节点           - 子因1
// - 分支2             - 子因2
//                      主因B
// 深度用 "-" 个数（或 2 空格缩进）；鱼骨图首行=问题，之后 主因行 + "- 子因" 行

internal data class MindNode(val depth: Int, val text: String)

/**
 * 解析思维导图。
 *
 * 模型两种缩进风格都会用，而且是**混用**的：
 *   A 纯横杠  "- 前期准备" / "-- 子节点"
 *   B 空格缩进 "  - 需求评审"（前面是空格，后面才到横杠）
 * 原来只用 `takeWhile { it == '-' }` 数横杠，遇到 B 的首字符是空格 → 数到 0 →
 * 走 else 分支，**横杠留在文本里没被剥掉**（真机上就看到节点显示成"- 需求评审"），
 * 深度也只按缩进算、把横杠那一级忽略了。这里先剥缩进再数横杠，两者取更深的那个。
 */
internal fun parseMindmap(lines: List<String>): List<MindNode> {
    val out = mutableListOf<MindNode>()
    lines.forEach { raw ->
        val l = raw.trimEnd()
        if (l.isBlank()) return@forEach
        val indent = l.length - l.trimStart().length
        val body = l.trimStart()
        val dashes = body.takeWhile { it == '-' }.length
        val text = if (dashes > 0) body.drop(dashes).trim() else body.trim()
        if (text.isBlank()) return@forEach
        val byIndent = if (out.isEmpty()) 0 else indent / 2 + 1
        val depth = (if (dashes > 0) maxOf(dashes, byIndent) else byIndent).coerceIn(0, 5)
        out += MindNode(depth, text)
    }
    return out
}

internal data class BoneCause(val text: String, val subs: List<String>)

internal fun parseFishbone(lines: List<String>): Pair<String, List<BoneCause>> {
    val nonBlank = lines.map { it.trim() }.filter { it.isNotBlank() }
    if (nonBlank.isEmpty()) return "" to emptyList()
    val title = nonBlank.first()
        .removePrefix("问题：").removePrefix("问题:")
        .removePrefix("主题：").removePrefix("主题:").trim()
        .ifBlank { nonBlank.first() }
    val causes = mutableListOf<BoneCause>()
    nonBlank.drop(1).forEach { l ->
        if (l.startsWith("-")) {
            val t = l.dropWhile { it == '-' }.trim()
            if (t.isNotBlank() && causes.isNotEmpty()) {
                causes[causes.size - 1] = causes.last().copy(subs = causes.last().subs + t)
            }
        } else {
            causes += BoneCause(l, emptyList())
        }
    }
    return title to causes
}

/**
 * 思维导图：根在左，分支横向展开，贝塞尔连线。
 *
 * 2026-11-06 用户「生成的图要利用满屏幕，不要挤到一边」：原来深度间距固定 26dp、
 * 盒子按文字自然宽，短标签的导图全部挤在屏幕左侧一小条，右边大片留白。
 * 改两遍布局：先量每个盒子的自然宽，按深度归并出「列宽」（该深度最宽盒 + 12dp 列距），
 * 再把各列**等比拉伸**铺满可用宽度——根贴左、最深层贴右，整幅图吃满屏宽。
 * 列的自然总宽超出可用宽（超长标签）时不再把盒子截窄（旧代码 coerceAtMost 截宽
 * 会让文字溢出圆角框），改为整图横向滑动，与代码块横滑同款交互。
 */
@Composable
fun MindmapBlock(raw: String) {
    val nodes = remember(raw) { parseMindmap(raw.lines()) }
    if (nodes.isEmpty()) {
        Text("（思维导图内容为空）", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
        return
    }
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    // 颜色全部在组合期解析（Canvas 内禁读主题）
    val rootBg = MaterialTheme.colorScheme.primaryContainer
    val rootFg = MaterialTheme.colorScheme.onPrimaryContainer
    val l1Bg = MaterialTheme.colorScheme.secondaryContainer
    val l1Fg = MaterialTheme.colorScheme.onSecondaryContainer
    val deepBg = MaterialTheme.colorScheme.surfaceVariant
    val deepFg = MaterialTheme.colorScheme.onSurfaceVariant
    val lineColor = MaterialTheme.colorScheme.outlineVariant
    val rootStyle = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Bold, color = rootFg)
    val l1Style = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium, color = l1Fg)
    val deepStyle = TextStyle(fontSize = 10.sp, color = deepFg)

    val rowH = 32f
    val heightDp = (nodes.size * rowH + 14).dp

    // 第一遍（组合期）：自然盒宽 → 每个深度的列宽（该深度最宽盒 + 12dp 列距）
    val maxDepth = nodes.maxOf { it.depth }
    val colMin = remember(nodes, density) {
        with(density) {
            val gap = 12.dp.toPx()
            FloatArray(maxDepth + 1).also { arr ->
                nodes.forEach { n ->
                    val style = when {
                        n.depth == 0 -> rootStyle
                        n.depth == 1 -> l1Style
                        else -> deepStyle
                    }
                    val w = measurer.measure(AnnotatedString(n.text), style).size.width + 18.dp.toPx()
                    arr[n.depth] = maxOf(arr[n.depth], w + gap)
                }
            }
        }
    }
    val natWidthDp = with(density) { (12.dp.toPx() + colMin.sum()).toDp() }

    BoxWithConstraints(Modifier.fillMaxWidth()) {
        // 绘制体两分支共用：可用宽 ≥ 列自然总宽时等比拉伸铺满，否则保持自然宽（外层横向滑）
        val drawMind: DrawScope.() -> Unit = {
            val rowHp = rowH.dp.toPx()
            val pad = 6.dp.toPx()
            fun nodeY(i: Int) = pad + i * rowHp + rowHp / 2

            // 列起点：colMin 已含列距；余量按列宽比例摊进去
            val avail = size.width - 2 * pad
            val colSum = colMin.sum()
            val colX = FloatArray(maxDepth + 1)
            var acc = pad
            colMin.forEachIndexed { d, cw ->
                colX[d] = acc
                if (colSum < avail) acc += cw + (avail - colSum) * (cw / colSum) else acc += cw
            }
            // 只有根节点一个盒子时居中，别贴左
            if (maxDepth == 0 && nodes.size == 1) colX[0] = (size.width - (colMin[0] - 12.dp.toPx())) / 2

            // 先量文本，算每节点盒子（宽=文字自然宽，不再截窄）
            data class Box(val x: Float, val y: Float, val w: Float, val h: Float, val text: String, val depth: Int, val idx: Int)
            val boxes = nodes.mapIndexed { idx, n ->
                val style = when {
                    n.depth == 0 -> rootStyle
                    n.depth == 1 -> l1Style
                    else -> deepStyle
                }
                val tw = measurer.measure(AnnotatedString(n.text), style).size.width
                Box(colX[n.depth], nodeY(idx), tw + 18.dp.toPx(), rowHp - 8.dp.toPx(), n.text, n.depth, idx)
            }

            // 连线：栈记录各深度最近节点；控制点用父子盒沿的实际水平间隙（拉伸后间隙变大）
            val lastAtDepth = IntArray(6) { -1 }
            boxes.forEach { b ->
                if (b.depth > 0) {
                    var pd = b.depth - 1
                    while (pd > 0 && lastAtDepth.getOrNull(pd) == -1) pd--
                    val pi = lastAtDepth.getOrNull(pd) ?: -1
                    if (pi >= 0) {
                        val p = boxes[pi]
                        val g = (b.x - (p.x + p.w)).coerceAtLeast(1f)
                        val path = Path()
                        path.moveTo(p.x + p.w, p.y)
                        path.cubicTo(
                            p.x + p.w + g / 2, p.y,
                            b.x - g / 2, b.y,
                            b.x, b.y
                        )
                        drawPath(path, color = lineColor, style = Stroke(1.5.dp.toPx(), cap = StrokeCap.Round))
                    }
                }
                if (b.depth < 6) lastAtDepth[b.depth] = b.idx
            }

            // 盒子与文本
            boxes.forEach { b ->
                val (bg, _) = when {
                    b.depth == 0 -> rootBg to rootFg
                    b.depth == 1 -> l1Bg to l1Fg
                    else -> deepBg to deepFg
                }
                drawRoundRect(
                    color = bg,
                    topLeft = Offset(b.x, b.y - b.h / 2),
                    size = androidx.compose.ui.geometry.Size(b.w, b.h),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(8.dp.toPx())
                )
                val style = when {
                    b.depth == 0 -> rootStyle
                    b.depth == 1 -> l1Style
                    else -> deepStyle
                }
                drawText(
                    measurer,
                    AnnotatedString(b.text),
                    style = style,
                    topLeft = Offset(b.x + 9.dp.toPx(), b.y - measurer.measure(AnnotatedString(b.text), style).size.height / 2)
                )
            }
        }
        if (natWidthDp > maxWidth) {
            // 超长标签放不下：整图横向滑，盒子保持自然宽不截字。
            // 2026-11-12 用户「组件/图的横滑跟右滑菜单手势冲突」：这里没登记 hScrollZone，
            // 外壳抽屉手势（Initial 通道先看事件、按下点不在横滑区就整段消费）会把图的
            // 右滑抢成开菜单——与 2026-11-09 代码块同款病灶，登记后抽屉在图上让位。
            // 2026-11-13 用户「滑动条要可见」：挂 hScrollHint 指示条。
            val diagramHs = rememberScrollState()
            Box(
                Modifier
                    .fillMaxWidth()
                    .hScrollZone()
                    .hScrollHint(diagramHs)
                    .horizontalScroll(diagramHs)
            ) {
                Canvas(Modifier.width(natWidthDp).height(heightDp).padding(vertical = 4.dp), drawMind)
            }
        } else {
            Canvas(Modifier.fillMaxWidth().height(heightDp).padding(vertical = 4.dp), drawMind)
        }
    }
    Spacer(Modifier.height(10.dp))
}

// ==================== 十三期二批：流程图 / 树形图 / 散点图 / 雷达图 / 热力图 ====================
// 协议：
// ```flow 流程图：每行 "A -> B -> C"（→ 亦可），目标节点可带 "(标签)" 作为边上文字；单行无箭头 = 独立节点
// ```tree 树形图：语法同 mindmap（- 个数或 2 空格缩进），但根在上、子向下
// ```scatter 散点图：首行标题，之后每行 "x,y" 数值对
// ```radar 雷达图：首行标题，之后每行 "维度:数值"，可用 "满分:N" 指定量程（缺省取最大值）
// ```heatmap 热力图：首行标题，第二行 ",列1,列2,...", 之后每行 "行名,v1,v2,..."
// 柱状/折线/饼图沿用 ```chart，围栏语言槽直接写 bar/line/pie 或 柱状图/折线图/饼图 亦可

internal data class FlowEdge(val from: String, val to: String, val label: String)

internal fun parseFlow(lines: List<String>): Pair<List<String>, List<FlowEdge>> {
    val nodes = linkedSetOf<String>()
    val edges = mutableListOf<FlowEdge>()
    val labelRe = Regex("""^(.*)[（(]([^)）]{1,12})[)）]\s*$""")
    lines.map { it.trim() }.filter { it.isNotBlank() }.forEach { l ->
        if (l.contains("->") || l.contains("→")) {
            val segs = l.split("->", "→").map { it.trim() }.filter { it.isNotBlank() }
            segs.forEachIndexed { i, rawSeg ->
                val m = labelRe.find(rawSeg)
                val node = (m?.groupValues?.get(1) ?: rawSeg).trim()
                nodes += node
                if (i > 0) {
                    val prevRaw = segs[i - 1]
                    val prev = (labelRe.find(prevRaw)?.groupValues?.get(1) ?: prevRaw).trim()
                    edges += FlowEdge(prev, node, m?.groupValues?.get(2)?.orEmpty() ?: "")
                }
            }
        } else {
            nodes += l
        }
    }
    return nodes.toList() to edges
}

/** 流程图：自上而下分层（BFS 深度），判断节点（?结尾）画菱形，开始/结束画胶囊 */
@Composable
fun FlowBlock(raw: String) {
    val (nodeList, edges) = remember(raw) { parseFlow(raw.lines()) }
    if (nodeList.isEmpty()) {
        Text("（流程图内容为空）", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
        return
    }
    val measurer = rememberTextMeasurer()
    val boxBg = MaterialTheme.colorScheme.primaryContainer
    val boxFg = MaterialTheme.colorScheme.onPrimaryContainer
    val diamondBg = MaterialTheme.colorScheme.tertiaryContainer
    val diamondFg = MaterialTheme.colorScheme.onTertiaryContainer
    val stadiumBg = MaterialTheme.colorScheme.secondaryContainer
    val stadiumFg = MaterialTheme.colorScheme.onSecondaryContainer
    val arrowColor = MaterialTheme.colorScheme.onSurfaceVariant
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val labelBg = MaterialTheme.colorScheme.surface
    val nodeStyle = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium, color = boxFg)
    val diamondStyle = TextStyle(fontSize = 10.sp, fontWeight = FontWeight.Medium, color = diamondFg)
    val stadiumStyle = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = stadiumFg)
    val labelStyle = TextStyle(fontSize = 9.sp, color = labelColor)

    // BFS 分层
    val levelOf = remember(nodeList, edges) {
        val level = mutableMapOf<String, Int>()
        val incoming = edges.groupBy { it.to }
        val roots = nodeList.filter { incoming[it].isNullOrEmpty() }.ifEmpty { listOf(nodeList.first()) }
        val queue = ArrayDeque<Pair<String, Int>>()
        roots.forEach { level[it] = 0; queue.add(it to 0) }
        while (queue.isNotEmpty()) {
            val (n, l) = queue.removeFirst()
            edges.filter { it.from == n }.forEach { e ->
                val cur = level[e.to]
                if (cur == null || l + 1 < cur) { level[e.to] = l + 1; queue.add(e.to to l + 1) }
            }
        }
        nodeList.mapNotNull { n -> level[n]?.let { n to it } }
    }
    val maxLevel = levelOf.maxOfOrNull { it.second } ?: 0
    val rowH = 64f
    val nodeH = 34f
    val heightDp = ((maxLevel + 1) * rowH + 16).dp

    BoxWithConstraints(Modifier.fillMaxWidth()) {
        Canvas(Modifier.fillMaxWidth().height(heightDp).padding(vertical = 4.dp)) {
            val w = size.width
            val nodeHp = nodeH.dp.toPx()
            val rowHp = rowH.dp.toPx()
            val pad = 10.dp.toPx()
            data class FBox(val cx: Float, val cy: Float, val w: Float, val h: Float, val text: String, val kind: String)
            // 逐层均匀铺开
            val byLevel = levelOf.groupBy({ it.second }, { it.first })
            val rowIdx = byLevel.keys.sorted().withIndex().associate { it.value to it.index }
            val boxes = mutableMapOf<String, FBox>()
            byLevel.toSortedMap().forEach { (lv, names) ->
                val cy = pad + nodeHp / 2 + (rowIdx[lv] ?: 0) * rowHp
                names.forEachIndexed { i, name ->
                    val isJudge = name.endsWith("?") || name.endsWith("？")
                    val isCap = name.equals("开始", true) || name.equals("结束", true) ||
                        name.equals("start", true) || name.equals("end", true)
                    val style = when {
                        isJudge -> diamondStyle
                        isCap -> stadiumStyle
                        else -> nodeStyle
                    }
                    val tw = measurer.measure(AnnotatedString(name), style).size.width
                    val bw = (tw + 22.dp.toPx()).coerceAtMost(w * 0.46f)
                    val cx = if (names.size == 1) w / 2
                    else pad + bw / 2 + i * (w - 2 * pad - bw) / (names.size - 1).coerceAtLeast(1)
                    boxes[name] = FBox(cx, cy, bw, nodeHp, name, if (isJudge) "diamond" else if (isCap) "stadium" else "box")
                }
            }

            // 边（直线+箭头+标签）
            val labelCache = edges.map { e ->
                // 2026-11-12 用户「标签乱七八糟的，而且箭头的头飞了」：drawText 直接喂
                // AnnotatedString 会按**画布剩余宽度**自动折行——靠右的「标签:可延后」被劈成
                // 「标签:可延 / 后」，孤字正好压在下一个节点的箭头上（看着像箭头飞了）。
                // 这里预布局成**单行**（超宽省略），绘制时再在画布内夹取位置。
                e.label to (if (e.label.isBlank()) null else measurer.measure(
                    AnnotatedString(e.label), labelStyle,
                    maxLines = 1, softWrap = false,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                ))
            }
            edges.forEachIndexed { ei, e ->
                val s = boxes[e.from] ?: return@forEachIndexed
                val t = boxes[e.to] ?: return@forEachIndexed
                val x1 = s.cx; val y1 = s.cy + s.h / 2
                val x2 = t.cx; val y2 = t.cy - s.h / 2
                drawLine(arrowColor, Offset(x1, y1), Offset(x2, y2 - 5.dp.toPx()), strokeWidth = 1.5.dp.toPx(), cap = StrokeCap.Round)
                // 箭头三角
                val ang = kotlin.math.atan2(y2 - y1, x2 - x1)
                val ah = 5.dp.toPx()
                val tip = Offset(x2, y2)
                val l = Offset(tip.x - ah * kotlin.math.cos(ang - 0.5f), tip.y - ah * kotlin.math.sin(ang - 0.5f))
                val r = Offset(tip.x - ah * kotlin.math.cos(ang + 0.5f), tip.y - ah * kotlin.math.sin(ang + 0.5f))
                drawPath(Path().apply {
                    moveTo(tip.x, tip.y); lineTo(l.x, l.y); lineTo(r.x, r.y); close()
                }, arrowColor)
                // 边标签：单行 + 画布内夹取 + 底色胶囊（压线也可读，孤字不再压箭头）
                labelCache.getOrNull(ei)?.second?.let { lm ->
                    val lw = lm.size.width.toFloat()
                    val lh = lm.size.height.toFloat()
                    val mx = (x1 + x2) / 2
                    val my = (y1 + y2) / 2
                    val lx = (mx - lw / 2).coerceIn(2.dp.toPx(), (w - lw - 2.dp.toPx()).coerceAtLeast(2.dp.toPx()))
                    val ly = (my - lh / 2).coerceIn(0f, (size.height - lh).coerceAtLeast(0f))
                    drawRoundRect(
                        color = labelBg,
                        topLeft = Offset(lx - 4.dp.toPx(), ly - 2.dp.toPx()),
                        size = androidx.compose.ui.geometry.Size(lw + 8.dp.toPx(), lh + 4.dp.toPx()),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(6.dp.toPx())
                    )
                    drawText(lm, topLeft = Offset(lx, ly))
                }
            }

            // 节点
            boxes.forEach { (_, b) ->
                val st = when (b.kind) {
                    "diamond" -> diamondStyle
                    "stadium" -> stadiumStyle
                    else -> nodeStyle
                }
                when (b.kind) {
                    "diamond" -> {
                        drawPath(
                            Path().apply {
                                moveTo(b.cx, b.cy - b.h / 2 - 4.dp.toPx())
                                lineTo(b.cx + b.w / 2 + 8.dp.toPx(), b.cy)
                                lineTo(b.cx, b.cy + b.h / 2 + 4.dp.toPx())
                                lineTo(b.cx - b.w / 2 - 8.dp.toPx(), b.cy)
                                close()
                            },
                            color = diamondBg
                        )
                    }
                    "stadium" -> {
                        drawRoundRect(stadiumBg, Offset(b.cx - b.w / 2, b.cy - b.h / 2), androidx.compose.ui.geometry.Size(b.w, b.h), androidx.compose.ui.geometry.CornerRadius(b.h))
                    }
                    else -> {
                        drawRoundRect(boxBg, Offset(b.cx - b.w / 2, b.cy - b.h / 2), androidx.compose.ui.geometry.Size(b.w, b.h), androidx.compose.ui.geometry.CornerRadius(9.dp.toPx()))
                    }
                }
                // 2026-11-17 用户「其他图也要检查」：节点文字按**盒子宽**换行（最多 2 行、超出省略），
                // 再逐行居中——原来按自然宽居中，文字比盒子宽（盒子已被 46% 宽截断）就直接
                // 溢出盒子、盖到相邻节点上。
                val innerFlow = (b.w - 14.dp.toPx()).coerceAtLeast(16.dp.toPx())
                val flowLines = wrapByWidth(b.text, innerFlow, { measurer.measure(AnnotatedString(it), st).size.width.toFloat() }, 2)
                val flowLineH = measurer.measure(AnnotatedString("测"), st).size.height
                flowLines.forEachIndexed { li, ln ->
                    val lw = measurer.measure(AnnotatedString(ln), st).size.width
                    drawText(
                        measurer, AnnotatedString(ln), style = st,
                        topLeft = Offset(b.cx - lw / 2f, b.cy - flowLines.size * flowLineH / 2f + li * flowLineH)
                    )
                }
            }
        }
    }
    Spacer(Modifier.height(10.dp))
}

/** 树形图：根在上，子节点向下（语法同 mindmap），叶序均分横向位置，肘形连线 */
@Composable
fun TreeBlock(raw: String) {
    val nodes = remember(raw) { parseMindmap(raw.lines()) }
    if (nodes.isEmpty()) {
        Text("（树形图内容为空）", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
        return
    }
    val measurer = rememberTextMeasurer()
    val rootBg = MaterialTheme.colorScheme.primaryContainer
    val rootFg = MaterialTheme.colorScheme.onPrimaryContainer
    val nodeBg = MaterialTheme.colorScheme.surfaceVariant
    val nodeFg = MaterialTheme.colorScheme.onSurfaceVariant
    val lineColor = MaterialTheme.colorScheme.outlineVariant
    val rootStyle = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Bold, color = rootFg)
    val nodeStyle = TextStyle(fontSize = 10.sp, color = nodeFg)

    // 父子关系（栈找最近同深度上层）
    val children = remember(nodes) {
        val map = mutableMapOf<Int, MutableList<Int>>()
        val stack = IntArray(6) { -1 }
        nodes.forEachIndexed { idx, n ->
            if (n.depth > 0) {
                var pd = n.depth - 1
                while (pd > 0 && stack[pd] == -1) pd--
                val p = stack[pd]
                if (p >= 0) map.getOrPut(p) { mutableListOf() }.add(idx)
            }
            if (n.depth < 6) stack[n.depth] = idx
        }
        map
    }
    val xPos = remember(nodes, children) {
        val x = FloatArray(nodes.size)
        var leaf = 0f
        fun assign(i: Int): Float {
            val ch = children[i]
            val v = if (ch.isNullOrEmpty()) leaf++ else ch.map { assign(it) }.average().toFloat()
            x[i] = v
            return v
        }
        assign(0)
        x
    }
    val maxDepth = nodes.maxOf { it.depth }
    val rowH = 54f
    val heightDp = ((maxDepth + 1) * rowH + 14).dp

    BoxWithConstraints(Modifier.fillMaxWidth()) {
        Canvas(Modifier.fillMaxWidth().height(heightDp).padding(vertical = 4.dp)) {
            val w = size.width
            val rowHp = rowH.dp.toPx()
            val pad = 8.dp.toPx()
            val leafCount = xPos.maxOrNull()?.plus(1f)?.coerceAtLeast(1f) ?: 1f
            val colW = (w - 2 * pad) / leafCount
            fun cx(i: Int) = pad + xPos[i] * colW + colW / 2
            fun cy(i: Int) = pad + nodes[i].depth * rowHp + rowHp / 2

            // 肘形连线
            children.forEach { (p, kids) ->
                kids.forEach { c ->
                    val path = Path()
                    path.moveTo(cx(p), cy(p) + rowHp / 2 - 6.dp.toPx())
                    path.lineTo(cx(p), (cy(p) + cy(c)) / 2)
                    path.lineTo(cx(c), (cy(p) + cy(c)) / 2)
                    path.lineTo(cx(c), cy(c) - rowHp / 2 + 6.dp.toPx())
                    drawPath(path, lineColor, style = Stroke(1.5.dp.toPx(), cap = StrokeCap.Round))
                }
            }

            nodes.forEachIndexed { i, n ->
                val style = if (n.depth == 0) rootStyle else nodeStyle
                val st = measurer.measure(AnnotatedString(n.text), style)
                val bw = (st.size.width + 16.dp.toPx()).coerceAtMost(colW * 0.94f)
                val bh = rowHp - 16.dp.toPx()
                val left = cx(i) - bw / 2
                val top = cy(i) - bh / 2
                if (n.depth == 0) {
                    drawRoundRect(rootBg, Offset(left, top), androidx.compose.ui.geometry.Size(bw, bh), androidx.compose.ui.geometry.CornerRadius(10.dp.toPx()))
                } else {
                    drawRoundRect(nodeBg, Offset(left, top), androidx.compose.ui.geometry.Size(bw, bh), androidx.compose.ui.geometry.CornerRadius(7.dp.toPx()))
                }
                // 2026-11-17 用户「其他图也要检查」（鱼骨图同款病根）：长中文节点名按盒子宽
                // 换行（最多 2 行、超出省略），再逐行居中画。原来按**自然宽**居中，
                // 文字比列宽宽就直接压到邻居列的节点上。
                val innerTree = (bw - 10.dp.toPx()).coerceAtLeast(12.dp.toPx())
                val treeLines = wrapByWidth(n.text, innerTree, { measurer.measure(AnnotatedString(it), style).size.width.toFloat() }, 2)
                val treeLineH = st.size.height
                treeLines.forEachIndexed { li, ln ->
                    val lw = measurer.measure(AnnotatedString(ln), style).size.width
                    drawText(
                        measurer, AnnotatedString(ln), style = style,
                        topLeft = Offset(cx(i) - lw / 2f, cy(i) - treeLines.size * treeLineH / 2f + li * treeLineH)
                    )
                }
            }
        }
    }
    Spacer(Modifier.height(10.dp))
}

private fun fmtNum(v: Float): String =
    if (kotlin.math.abs(v) >= 100 || v == v.toInt().toFloat()) v.toInt().toString() else String.format("%.1f", v)

/**
 * 气泡图：散点图 + 半径编码第三个变量。
 *
 * 数据 `x,y,大小`（逗号分隔）。大小列可省略，省略时所有气泡等大。
 * 用平方根缩放半径 —— 直接拿数值当半径会让大值气泡大得离谱（面积随半径平方增长）。
 */
@Composable
fun BubbleBlock(raw: String) {
    var t by remember { mutableStateOf("bubble") }
    ChartFrame(
        chartType = t,
        title = "",
        switchableTypes = listOf("bubble", "scatter"),
        onTypeChange = { t = it }
    ) { _ ->
        // 两者数据格式相同（x,y[,大小]），只是画法不同，可直接互换
        if (t == "scatter") ScatterBlockBody(raw) else BubbleBlockBody(raw)
    }
}

@Composable
private fun BubbleBlockBody(raw: String) {

    val lines = raw.lines().map { it.trim() }.filter { it.isNotBlank() }
    val title = lines.firstOrNull()?.takeIf { !it.contains(",") && !it.contains("，") } ?: ""
    val pts = lines.drop(if (title.isBlank()) 0 else 1).mapNotNull { l ->
        val parts = l.split(",", "，").map { it.trim() }
        val x = parts.getOrNull(0)?.toFloatOrNull() ?: return@mapNotNull null
        val y = parts.getOrNull(1)?.toFloatOrNull() ?: return@mapNotNull null
        val sz = parts.getOrNull(2)?.toFloatOrNull() ?: 1f
        Triple(x, y, sz)
    }
    if (pts.size < 2) {
        Text(
            "（气泡图至少需要 2 个点，格式 x,y,大小）",
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error
        )
        return
    }
    val measurer = rememberTextMeasurer()
    val axisColor = MaterialTheme.colorScheme.outline
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val tickColor = MaterialTheme.colorScheme.onSurfaceVariant
    val palette = SeedColors.chartPalette(isSystemInDarkTheme())
    val tickStyle = TextStyle(fontSize = 9.sp, color = tickColor)
    Canvas(Modifier.fillMaxWidth().height(220.dp).padding(vertical = 4.dp)) {
        val w = size.width; val h = size.height
        val padL = 34.dp.toPx(); val padB = 22.dp.toPx(); val padT = 10.dp.toPx(); val padR = 10.dp.toPx()
        val xs = pts.map { it.first }; val ys = pts.map { it.second }
        val x0 = xs.min(); val x1 = xs.max(); val y0 = ys.min(); val y1 = ys.max()
        fun sx(v: Float) = padL + (v - x0) / ((x1 - x0).takeIf { it != 0f } ?: 1f) * (w - padL - padR)
        fun sy(v: Float) = h - padB - (v - y0) / ((y1 - y0).takeIf { it != 0f } ?: 1f) * (h - padT - padB)
        val maxSz = pts.maxOf { it.third }.coerceAtLeast(0.001f)
        repeat(5) { i ->
            val f = i / 4f
            val xv = x0 + (x1 - x0) * f
            val yv = y0 + (y1 - y0) * f
            drawLine(gridColor, Offset(sx(xv), padT), Offset(sx(xv), h - padB), strokeWidth = 1f)
            drawLine(gridColor, Offset(padL, sy(yv)), Offset(w - padR, sy(yv)), strokeWidth = 1f)
            val xt = measurer.measure(AnnotatedString(fmtNum(xv)), tickStyle)
            drawText(measurer, AnnotatedString(fmtNum(xv)), topLeft = Offset(sx(xv) - xt.size.width / 2, h - padB + 4.dp.toPx()), style = tickStyle)
            val yt = measurer.measure(AnnotatedString(fmtNum(yv)), tickStyle)
            drawText(measurer, AnnotatedString(fmtNum(yv)), topLeft = Offset(2.dp.toPx(), sy(yv) - yt.size.height / 2), style = tickStyle)
        }
        drawLine(axisColor, Offset(padL, padT), Offset(padL, h - padB), strokeWidth = 1.5f)
        drawLine(axisColor, Offset(padL, h - padB), Offset(w - padR, h - padB), strokeWidth = 1.5f)
        pts.forEachIndexed { i, (x, y, sz) ->
            // 面积正比于大小 → 半径取平方根，否则大气泡会盖住整张图
            val r = (4.dp.toPx() + 16.dp.toPx() * kotlin.math.sqrt(sz / maxSz))
            drawCircle(palette[i % palette.size].copy(alpha = 0.55f), radius = r, center = Offset(sx(x), sy(y)))
            drawCircle(palette[i % palette.size], radius = r, center = Offset(sx(x), sy(y)), style = Stroke(1.5f))
        }
    }
    if (title.isNotBlank()) {
        androidx.compose.material3.Text(
            title, style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
    }
    Spacer(Modifier.height(10.dp))
}

/** 散点图：数值 x-y 对，带坐标轴与刻度 */
@Composable
fun ScatterBlock(raw: String) {
    var t by remember { mutableStateOf("scatter") }
    ChartFrame(
        chartType = t,
        title = "",
        switchableTypes = listOf("scatter", "bubble"),
        onTypeChange = { t = it }
    ) { _ ->
        // 两者数据格式相同（x,y[,大小]），只是画法不同，可直接互换
        if (t == "bubble") BubbleBlockBody(raw) else ScatterBlockBody(raw)
    }
}

@Composable
private fun ScatterBlockBody(raw: String) {
    val lines = raw.lines().map { it.trim() }.filter { it.isNotBlank() }
    val title = lines.firstOrNull()?.takeIf { parsePair(it) == null } ?: ""
    val pts = (if (title.isBlank()) lines else lines.drop(1)).mapNotNull { parsePair(it) }
    if (pts.isEmpty()) {
        Text("（散点图为空，每行格式 x,y）", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
        return
    }
    val measurer = rememberTextMeasurer()
    val axisColor = MaterialTheme.colorScheme.outline
    val tickColor = MaterialTheme.colorScheme.onSurfaceVariant
    val pointColor = MaterialTheme.colorScheme.primary
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val tickStyle = TextStyle(fontSize = 9.sp, color = tickColor)
    Canvas(Modifier.fillMaxWidth().height(220.dp).padding(vertical = 4.dp)) {
        val w = size.width; val h = size.height
        val padL = 34.dp.toPx(); val padB = 22.dp.toPx(); val padT = 10.dp.toPx(); val padR = 10.dp.toPx()
        val xs = pts.map { it.first }; val ys = pts.map { it.second }
        val x0 = xs.min(); val x1 = xs.max(); val y0 = ys.min(); val y1 = ys.max()
        fun sx(v: Float) = padL + (v - x0) / ((x1 - x0).takeIf { it != 0f } ?: 1f) * (w - padL - padR)
        fun sy(v: Float) = h - padB - (v - y0) / ((y1 - y0).takeIf { it != 0f } ?: 1f) * (h - padT - padB)
        // 网格与刻度（4 段）
        repeat(5) { i ->
            val f = i / 4f
            val xv = x0 + (x1 - x0) * f
            val yv = y0 + (y1 - y0) * f
            drawLine(gridColor, Offset(sx(xv), padT), Offset(sx(xv), h - padB), strokeWidth = 1f)
            drawLine(gridColor, Offset(padL, sy(yv)), Offset(w - padR, sy(yv)), strokeWidth = 1f)
            val xt = measurer.measure(AnnotatedString(fmtNum(xv)), tickStyle)
            drawText(measurer, AnnotatedString(fmtNum(xv)), topLeft = Offset(sx(xv) - xt.size.width / 2, h - padB + 4.dp.toPx()), style = tickStyle)
            val yt = measurer.measure(AnnotatedString(fmtNum(yv)), tickStyle)
            drawText(measurer, AnnotatedString(fmtNum(yv)), topLeft = Offset(2.dp.toPx(), sy(yv) - yt.size.height / 2), style = tickStyle)
        }
        drawLine(axisColor, Offset(padL, padT), Offset(padL, h - padB), strokeWidth = 1.5f)
        drawLine(axisColor, Offset(padL, h - padB), Offset(w - padR, h - padB), strokeWidth = 1.5f)
        pts.forEach { (x, y) ->
            drawCircle(pointColor, radius = 4.dp.toPx(), center = Offset(sx(x), sy(y)))
        }
    }
    if (title.isNotBlank()) {
        androidx.compose.material3.Text(
            title, style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
    }
    Spacer(Modifier.height(10.dp))
}

private fun parsePair(line: String): Pair<Float, Float>? {
    val parts = line.split(",", "，", ":", "：").map { it.trim() }
    if (parts.size < 2) return null
    val x = parts[0].toFloatOrNull() ?: return null
    val y = parts[1].toFloatOrNull() ?: return null
    return x to y
}

/** 雷达图：n≥3 维，同心网格 + 填充数据多边形 */
@Composable
fun RadarBlock(raw: String) {
    val lines = raw.lines().map { it.trim() }.filter { it.isNotBlank() }
    val title = lines.firstOrNull()?.takeIf { !it.contains(":") && !it.contains("：") } ?: ""
    val scale = lines.firstOrNull { it.startsWith("满分") }?.substringAfter(":")?.substringAfter("：")?.trim()?.toFloatOrNull()
    // 维度 → 各系列的值。模型经常写「编程:85,70」这种**多系列**对比，
    // 原来只取 toFloatOrNull()，"85,70" 解析失败被整条丢掉，最后报「至少需要 3 个维度」。
    val rows = lines.drop(if (title.isBlank()) 0 else 1)
        .filter { !it.startsWith("满分") }
        .mapNotNull { l ->
            val kv = l.split(":", "：", limit = 2)
            if (kv.size != 2 || kv[0].isBlank()) return@mapNotNull null
            // 支持 "维度:值"（系列名另起一行给，如「系列A:甲」）或 "维度:值1,值2"
            val vals = kv[1].split(",", "，").mapNotNull { it.trim().toFloatOrNull() }
            if (vals.isEmpty()) null else kv[0].trim() to vals
        }
    if (rows.size < 3) {
        Text(
            "（雷达图至少需要 3 个维度，格式 维度:数值 或 维度:系列1值,系列2值）",
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error
        )
        return
    }
    val seriesCount = rows.maxOf { it.second.size }
    if (seriesCount < 1) {
        Text("（雷达图数据为空）", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
        return
    }
    val measurer = rememberTextMeasurer()
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val axisColor = MaterialTheme.colorScheme.outline
    val fillColor = MaterialTheme.colorScheme.primary
    val palette = SeedColors.chartPalette(isSystemInDarkTheme())
    val labelColor = MaterialTheme.colorScheme.onSurface
    val labelStyle = TextStyle(fontSize = 10.sp, color = labelColor)
    Canvas(Modifier.fillMaxWidth().height(240.dp).padding(vertical = 4.dp)) {
        val w = size.width; val h = size.height
        val labelPad = 44.dp.toPx()
        val cx = w / 2; val cy = h / 2
        val radius = (minOf(w, h) / 2 - labelPad).coerceAtLeast(20.dp.toPx())
        val maxV = (scale ?: rows.maxOf { r -> r.second.max() }).coerceAtLeast(0.001f)
        val n = rows.size
        fun pt(i: Int, frac: Float): Offset {
            val ang = (-90.0 + 360.0 * i / n) * kotlin.math.PI / 180.0
            return Offset(
                cx + (radius * frac * kotlin.math.cos(ang)).toFloat(),
                cy + (radius * frac * kotlin.math.sin(ang)).toFloat()
            )
        }
        repeat(4) { ring ->
            val f = (ring + 1) / 4f
            val path = Path()
            repeat(n) { i ->
                val p = pt(i, f)
                if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
            }
            path.close()
            drawPath(path, gridColor, style = Stroke(1f))
        }
        rows.forEachIndexed { i, (name, _) ->
            val p = pt(i, 1f)
            drawLine(axisColor, Offset(cx, cy), p, strokeWidth = 1f)
            val lt = measurer.measure(AnnotatedString(name), labelStyle)
            val lx = if (p.x < cx - 1) p.x - lt.size.width else if (p.x > cx + 1) p.x else p.x - lt.size.width / 2
            val ly = (p.y - lt.size.height / 2).coerceIn(0f, h - lt.size.height)
            // 2026-11-17 用户「其他图也要检查」：维度名过长时**右侧也要夹取**，
            // 原来只夹左边 → 右侧顶点标签直接画到画布外/压住相邻顶点。
            val lxClamped = lx.coerceIn(0f, (w - lt.size.width).coerceAtLeast(0f))
            drawText(measurer, AnnotatedString(name), topLeft = Offset(lxClamped, ly), style = labelStyle)
        }
        // 每个系列一个多边形。
        //
        // 多系列必须用**互不相邻**的调色板下标（0,2,4…），不能直接取 si：
        // inkPalette 的相邻色（如蓝/红/绿）在细线轮廓下区分度不够，真机上
        // 两个系列画出来几乎完全重叠，看不出是"两人对比"。
        val seriesColors = if (seriesCount == 1) listOf(fillColor)
        else List(seriesCount) { palette[(it * 2) % palette.size] }
        repeat(seriesCount) { si ->
            val c = seriesColors[si]
            val path = Path()
            rows.forEachIndexed { i, (_, vals) ->
                val v = vals.getOrElse(si) { vals.last() }
                val p = pt(i, (v / maxV).coerceIn(0f, 1f))
                if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
            }
            path.close()
            drawPath(path, c.copy(alpha = if (seriesCount == 1) 0.22f else 0.14f))
            drawPath(
                path, c,
                style = Stroke((if (seriesCount == 1) 2.dp else 2.5.dp).toPx(), cap = StrokeCap.Round)
            )
            rows.forEachIndexed { i, (_, vals) ->
                val v = vals.getOrElse(si) { vals.last() }
                drawCircle(c, radius = 3.dp.toPx(), center = pt(i, (v / maxV).coerceIn(0f, 1f)))
            }
        }
    }
    // 多系列时必须给图例，否则两条多边形谁是谁完全看不出来
    if (seriesCount > 1) {
        Row(
            Modifier.fillMaxWidth().padding(top = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp, androidx.compose.ui.Alignment.CenterHorizontally)
        ) {
            val seriesColors = if (seriesCount == 1) listOf(fillColor)
            else List(seriesCount) { palette[(it * 2) % palette.size] }
            repeat(seriesCount) { si ->
                val c = seriesColors[si]
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(9.dp).clip(RoundedCornerShape(2.dp)).background(c))
                    Spacer(Modifier.width(5.dp))
                    Text(
                        "系列${si + 1}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
    if (title.isNotBlank()) {
        androidx.compose.material3.Text(
            title, style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
    }
    Spacer(Modifier.height(10.dp))
}

/** 热力图：行×列数值网格，色深表示数值大小 */
@Composable
fun HeatmapBlock(raw: String) {
    val lines = raw.lines().map { it.trim() }.filter { it.isNotBlank() }
    if (lines.size < 2) {
        Text("（热力图格式：首行标题，第二行 ,列1,列2,...，之后 行名,v1,v2,...）", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
        return
    }
    val title = lines.first()
    val headerCells = lines.getOrNull(1)?.split(",", "，")?.map { it.trim() } ?: emptyList()
    val cols = headerCells.drop(1)
    val rows = lines.drop(2).map { l ->
        val cells = l.split(",", "，").map { it.trim() }
        (cells.getOrNull(0) ?: "") to cells.drop(1).map { it.toFloatOrNull() }
    }.filter { it.first.isNotBlank() }
    if (cols.isEmpty() || rows.isEmpty()) {
        Text("（热力图为空）", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
        return
    }
    val measurer = rememberTextMeasurer()
    val maxV = rows.flatMap { it.second }.filterNotNull().maxOrNull() ?: 1f
    val minV = rows.flatMap { it.second }.filterNotNull().minOrNull() ?: 0f
    val cLow = MaterialTheme.colorScheme.surfaceVariant
    val cHigh = MaterialTheme.colorScheme.primary
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val headColor = MaterialTheme.colorScheme.onSurface
    val cellDark = TextStyle(fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurface)
    val cellLight = TextStyle(fontSize = 9.sp, color = androidx.compose.ui.graphics.Color.White)
    val rowStyle = TextStyle(fontSize = 10.sp, color = labelColor)
    val colStyle = TextStyle(fontSize = 10.sp, fontWeight = FontWeight.Medium, color = headColor)
    val lo = cLow.toArgb()
    val hi = cHigh.toArgb()

    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        if (title.isNotBlank() && !title.startsWith(",") && !title.startsWith("，")) {
            androidx.compose.material3.Text(
                title, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(bottom = 4.dp)
            )
        }
        Canvas(Modifier.fillMaxWidth().height((28 + rows.size * 34).dp)) {
            val labelW = 52.dp.toPx()
            val cw = (size.width - labelW) / cols.size
            val ch = 30.dp.toPx()
            // 列头
            cols.forEachIndexed { c, name ->
                // 2026-11-17 用户「其他图也要检查」：列头过长会压到邻居列 → 按列宽单行截断加省略号
                val colInner = (cw - 6.dp.toPx()).coerceAtLeast(10.dp.toPx())
                val colLines = wrapByWidth(name, colInner, { measurer.measure(AnnotatedString(it), colStyle).size.width.toFloat() }, 1)
                val colText = colLines.firstOrNull() ?: name
                val colW = measurer.measure(AnnotatedString(colText), colStyle).size.width
                drawText(measurer, AnnotatedString(colText), topLeft = Offset(labelW + c * cw + (cw - colW) / 2, 2.dp.toPx()), style = colStyle)
            }
            rows.forEachIndexed { r, (name, vals) ->
                val y = (r + 1) * ch
                drawText(measurer, AnnotatedString(name.take(4)), topLeft = Offset(2.dp.toPx(), y + (ch - measurer.measure(AnnotatedString(name.take(4)), rowStyle).size.height) / 2), style = rowStyle)
                vals.forEachIndexed { c, v ->
                    val x = labelW + c * cw
                    if (v != null) {
                        val f = ((v - minV) / ((maxV - minV).takeIf { it != 0f } ?: 1f)).coerceIn(0f, 1f)
                        val bg = android.graphics.Color.rgb(
                            (android.graphics.Color.red(lo) + (android.graphics.Color.red(hi) - android.graphics.Color.red(lo)) * f).toInt(),
                            (android.graphics.Color.green(lo) + (android.graphics.Color.green(hi) - android.graphics.Color.green(lo)) * f).toInt(),
                            (android.graphics.Color.blue(lo) + (android.graphics.Color.blue(hi) - android.graphics.Color.blue(lo)) * f).toInt()
                        )
                        drawRoundRect(
                            androidx.compose.ui.graphics.Color(bg),
                            Offset(x + 2.dp.toPx(), y + 2.dp.toPx()),
                            androidx.compose.ui.geometry.Size(cw - 4.dp.toPx(), ch - 4.dp.toPx()),
                            androidx.compose.ui.geometry.CornerRadius(5.dp.toPx())
                        )
                        val vs = fmtNum(v)
                        val cellStyle = if (f < 0.55f) cellDark else cellLight
                        val vt = measurer.measure(AnnotatedString(vs), cellStyle)
                        drawText(measurer, AnnotatedString(vs), topLeft = Offset(x + (cw - vt.size.width) / 2, y + (ch - vt.size.height) / 2), style = cellStyle)
                    } else {
                        drawRoundRect(
                            cLow.copy(alpha = 0.4f),
                            Offset(x + 2.dp.toPx(), y + 2.dp.toPx()),
                            androidx.compose.ui.geometry.Size(cw - 4.dp.toPx(), ch - 4.dp.toPx()),
                            androidx.compose.ui.geometry.CornerRadius(5.dp.toPx())
                        )
                    }
                }
            }
        }
    }
    Spacer(Modifier.height(10.dp))
}

// ==================== 鱼骨图 v2：长中文标签不再互相压字（2026-11-17 用户报障） ====================

/**
 * 2026-11-17 用户截图报障：鱼骨图里长中文标签（"训练总量是否到位（热量不低、蛋白质密度极低）"这类）
 * 互相压成一团，绿色骨线直接穿过文字。
 *
 * 病根不是字体也不是主题，是**布局假设标签很短**：
 * ①主因文字按自然宽画在骨端（`ex - width/2`），相邻主因的文字必然重叠；
 * ②子因文字从刻度向左无限伸展，会盖住左边主因的文字与骨线；
 * ③鱼头标题单行不限宽（"体测 64.2，爆发力两项合计只拿 2 分"≈250dp），
 *   在 360dp 手机上把主刺挤得只剩几十 dp；
 * ④画布高度按"子因行数"估，长标签换行后高度不够，文字直接压到下一行。
 *
 * v2 做法（与思维导图同款思路）：**先算布局再画**，且布局是可单测的纯函数——
 * ①每个主因占一条横向 band，标签一律换行到 band 内（超行数截断加省略号）；
 * ②主因文字贴在骨端外侧、居中于骨端，宽度上限 = 2×(band-2×斜角)，
 *   保证同侧相邻主因（隔一条 band）永不相交；
 * ③子因文字向左只伸到本 band 左边界，绝不越界进邻居 band；
 * ④骨长与画布高度按**实际文字高度**算（不是猜的行数）；
 * ⑤鱼头标题也换行，宽度封顶 34% 可用宽；
 * ⑥band 放不下时整图走自然宽 + 横向滑动（同思维导图/代码块）。
 */

/** 标签盒（纯布局结果，坐标单位 px） */
internal data class FbLabel(
    val x: Float, val y: Float, val w: Float, val h: Float,
    val lines: List<String>, val kind: Int
) {
    val right: Float get() = x + w
    val bottom: Float get() = y + h
    /** 两个标签矩形是否真相交（留 0.5px 容差，避开浮点擦边误判） */
    fun hits(o: FbLabel): Boolean =
        x < o.right - 0.5f && o.x < right - 0.5f && y < o.bottom - 0.5f && o.y < bottom - 0.5f
}

/** 小骨刻度：起点(px,py)、终点(endX,py) */
internal data class FbTick(val px: Float, val py: Float, val endX: Float)

/** 大骨：主刺上的基点(bx,by) → 骨端(ex,ey)，外加小骨刻度 */
internal data class FbBone(
    val bx: Float, val by: Float, val ex: Float, val ey: Float,
    val ticks: List<FbTick>
)

/** 鱼骨图完整布局 */
internal data class FbLayout(
    val width: Float, val height: Float, val spineY: Float,
    val spineStart: Float, val spineEnd: Float,
    val head: FbLabel, val bones: List<FbBone>, val labels: List<FbLabel>
)

/**
 * 按像素宽换行（中文按字断行；超出 maxLines 时末行截断加省略号）。
 * [measure] 注入文字宽度测量（生产用 TextMeasurer，单测用固定字宽）。
 */
internal fun wrapByWidth(
    text: String,
    maxWidth: Float,
    measure: (String) -> Float,
    maxLines: Int
): List<String> {
    val src = text.trim()
    if (src.isEmpty()) return emptyList()
    if (maxLines <= 0) return emptyList()
    if (measure(src) <= maxWidth) return listOf(src)
    val all = mutableListOf<String>()
    var cur = StringBuilder()
    for (ch in src) {
        val next = cur.toString() + ch
        if (cur.isNotEmpty() && measure(next) > maxWidth) {
            all += cur.toString()
            cur = StringBuilder()
        }
        cur.append(ch)
    }
    if (cur.isNotEmpty()) all += cur.toString()
    if (all.size <= maxLines) return all
    val kept = all.take(maxLines).toMutableList()
    var last = kept.last()
    while (last.isNotEmpty() && measure(last + "…") > maxWidth) last = last.dropLast(1)
    kept[kept.lastIndex] = last + "…"
    return kept
}

/**
 * 鱼骨图布局（纯函数，单测可跑）。
 *
 * @param causes 主因与子因
 * @param title 鱼头标题
 * @param availWidth 可用宽（px）
 * @param measure 文字宽度测量：(文本, kind) -> px；kind 0=主因 1=子因 2=鱼头
 */
internal fun layoutFishbone(
    causes: List<BoneCause>,
    title: String,
    availWidth: Float,
    measure: (String, Int) -> Float,
    causeLineH: Float,
    subLineH: Float,
    headLineH: Float,
    pad: Float,
    gap: Float,
    minBandW: Float,
    maxBandW: Float,
    slant: Float,
    tickLen: Float,
    minBoneLen: Float
): FbLayout {
    val n = causes.size.coerceAtLeast(1)

    // ---- 鱼头：标题换行，宽度封顶 34% 可用宽 ----
    val headMaxTextW = (availWidth * 0.34f - 2 * pad).coerceAtLeast(24f)
    val headLines = wrapByWidth(title, headMaxTextW, { measure(it, 2) }, 3)
    val headTextW = headLines.maxOfOrNull { measure(it, 2) } ?: 0f
    val headW = headTextW + 2 * pad
    val headH = headLines.size * headLineH + pad

    // ---- 主刺与 band：放不下就按自然宽（外层横滑） ----
    val fixed = pad + headW + gap + pad
    val naturalW = fixed + n * minBandW
    val bandW: Float
    val width: Float
    if (naturalW > availWidth) {
        bandW = minBandW
        width = naturalW
    } else {
        bandW = ((availWidth - fixed) / n).coerceIn(minBandW, maxBandW)
        width = availWidth
    }
    val usedW = fixed + n * bandW
    val offsetX = if (usedW < width) (width - usedW) / 2f else 0f
    val spineStart = offsetX + pad
    val spineEnd = spineStart + n * bandW

    // ---- 第一遍：量文字块（宽度只依赖 bandW，与骨长无关） ----
    class CauseBlock(
        val causeLines: List<String>, val causeW: Float, val causeH: Float,
        val subs: List<Triple<List<String>, Float, Float>> // lines, w, h
    )

    val blocks = causes.mapIndexed { i, cause ->
        val bandStart = spineStart + i * bandW
        val bandEnd = bandStart + bandW
        val ex = bandEnd - 2 * slant
        // 主因文字：居中于骨端，宽度上限保证同侧邻居（隔一条 band）不相交
        val causeMaxW = (2 * (bandW - 2 * slant)).coerceAtLeast(24f)
        val cl = wrapByWidth(cause.text, causeMaxW, { measure(it, 0) }, 3)
        val cw = cl.maxOfOrNull { measure(it, 0) } ?: 0f
        // 子因文字：向左只伸到本 band 左边界
        val subBlocks = cause.subs.mapIndexed { j, sub ->
            val f = (j + 1f) / (cause.subs.size + 1f)
            val px = bandEnd - slant - slant * f
            val tickEnd = px - tickLen
            val avail = (tickEnd - pad - bandStart).coerceAtLeast(18f)
            val sl = wrapByWidth(sub, avail, { measure(it, 1) }, 2)
            val sw = sl.maxOfOrNull { measure(it, 1) } ?: 0f
            Triple(sl, sw, sl.size * subLineH)
        }
        CauseBlock(cl, cw, cl.size * causeLineH, subBlocks)
    }

    // ---- 第二遍：骨长按实际文字高度定（上下半区各算各的） ----
    // 子因落在骨上 f=(j+1)/(n+1) 处 → 相邻子因**中心距** = boneLen/(n+1)。
    // 要不压字，必须中心距 ≥ 最高子因块高 + 间距 ⇒ boneLen ≥ (n+1)×(maxSubH+gap)。
    // （早期版本按「Σ(块高+间距)」算，中心距只有 boneLen/(n+1)，两块 24px 的子因
    //   在 21px 间距上直接叠在一起——守卫 FishboneLayoutGuardTest 抓到的真碰撞。）
    fun sideNeed(indices: List<Int>): Float =
        indices.maxOfOrNull { i ->
            val b = blocks[i]
            val n = b.subs.size
            val maxSubH = b.subs.maxOfOrNull { it.third } ?: 0f
            maxOf(minBoneLen, (n + 1) * (maxSubH + gap))
        } ?: minBoneLen

    val upIdx = causes.indices.filter { it % 2 == 0 }
    val downIdx = causes.indices.filter { it % 2 == 1 }
    val boneUp = sideNeed(upIdx)
    val boneDown = sideNeed(downIdx)
    val causeLabelUp = upIdx.maxOfOrNull { blocks[it].causeH } ?: 0f
    val causeLabelDown = downIdx.maxOfOrNull { blocks[it].causeH } ?: 0f
    val upRegion = boneUp + causeLabelUp + 2 * gap
    val downRegion = boneDown + causeLabelDown + 2 * gap
    val height = upRegion + downRegion + 2 * pad
    val spineY = pad + upRegion

    // ---- 第三遍：摆位 ----
    val bones = mutableListOf<FbBone>()
    val labels = mutableListOf<FbLabel>()
    causes.forEachIndexed { i, cause ->
        val b = blocks[i]
        val bandStart = spineStart + i * bandW
        val bandEnd = bandStart + bandW
        val up = i % 2 == 0
        val dir = if (up) -1f else 1f
        val boneLen = if (up) boneUp else boneDown
        val bx = bandEnd - slant
        val ex = bx - slant
        val ey = spineY + dir * boneLen
        val ticks = mutableListOf<FbTick>()
        cause.subs.forEachIndexed { j, sub ->
            val f = (j + 1f) / (cause.subs.size + 1f)
            val px = bx + (ex - bx) * f
            val py = spineY + (ey - spineY) * f
            ticks += FbTick(px, py, px - tickLen)
            val sb = b.subs[j]
            val sx = (px - tickLen - pad - sb.second).coerceAtLeast(bandStart)
            labels += FbLabel(sx, py - sb.third / 2f, sb.second, sb.third, sb.first, 1)
        }
        bones += FbBone(bx, spineY, ex, ey, ticks)
        // 主因文字：骨端外侧（上下交替），居中于骨端 x
        val cx = (ex - b.causeW / 2f).coerceAtLeast(pad)
        val cy = if (up) ey - gap - b.causeH else ey + gap
        labels += FbLabel(cx, cy, b.causeW, b.causeH, b.causeLines, 0)
    }

    val head = FbLabel(spineEnd + gap, spineY - headH / 2f, headW, headH, headLines, 2)
    return FbLayout(width, height, spineY, spineStart, spineEnd, head, bones, labels)
}

/** 鱼骨图：主刺水平线 + 上下交替大骨 + 水平小骨 */
@Composable
fun FishboneBlock(raw: String) {
    val (title, causes) = remember(raw) { parseFishbone(raw.lines()) }
    if (title.isBlank() || causes.isEmpty()) {
        Text("（鱼骨图内容为空）", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
        return
    }
    val measurer = rememberTextMeasurer()
    val spineColor = MaterialTheme.colorScheme.onSurface
    val boneColor = MaterialTheme.colorScheme.primary
    val subColor = MaterialTheme.colorScheme.outline
    val headBg = MaterialTheme.colorScheme.errorContainer
    val headFg = MaterialTheme.colorScheme.onErrorContainer
    val causeStyle = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
    val subStyle = TextStyle(fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    val headStyle = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Bold, color = headFg)
    val density = LocalDensity.current

    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val padPx = with(density) { 6.dp.toPx() }
        val gapPx = with(density) { 5.dp.toPx() }
        val slantPx = with(density) { 16.dp.toPx() }
        val tickPx = with(density) { 18.dp.toPx() }
        val minBandPx = with(density) { 104.dp.toPx() }
        val maxBandPx = with(density) { 210.dp.toPx() }
        val minBonePx = with(density) { 62.dp.toPx() }
        val causeLineHPx = with(density) { 15.dp.toPx() }
        val subLineHPx = with(density) { 12.dp.toPx() }
        val headLineHPx = with(density) { 16.dp.toPx() }

        fun measureText(t: String, kind: Int): Float {
            val style = when (kind) {
                0 -> causeStyle
                1 -> subStyle
                else -> headStyle
            }
            return measurer.measure(AnnotatedString(t), style).size.width.toFloat()
        }

        val availPx = with(density) { maxWidth.toPx() }
        val layout = layoutFishbone(
            causes = causes,
            title = title,
            availWidth = availPx,
            measure = ::measureText,
            causeLineH = causeLineHPx,
            subLineH = subLineHPx,
            headLineH = headLineHPx,
            pad = padPx,
            gap = gapPx,
            minBandW = minBandPx,
            maxBandW = maxBandPx,
            slant = slantPx,
            tickLen = tickPx,
            minBoneLen = minBonePx
        )

        val drawFish: DrawScope.() -> Unit = {
            // 主刺
            drawLine(
                spineColor,
                Offset(layout.spineStart, layout.spineY),
                Offset(layout.spineEnd, layout.spineY),
                strokeWidth = 2.5.dp.toPx(), cap = StrokeCap.Round
            )
            // 鱼头
            drawRoundRect(
                color = headBg,
                topLeft = Offset(layout.head.x, layout.head.y),
                size = androidx.compose.ui.geometry.Size(layout.head.w, layout.head.h),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(10.dp.toPx())
            )
            layout.head.lines.forEachIndexed { i, line ->
                val lw = measureText(line, 2)
                val lh = measurer.measure(AnnotatedString(line), headStyle).size.height
                drawText(
                    measurer, AnnotatedString(line), style = headStyle,
                    topLeft = Offset(
                        layout.head.x + (layout.head.w - lw) / 2f,
                        layout.head.y + (layout.head.h - layout.head.lines.size * headLineHPx) / 2f + i * headLineHPx + (headLineHPx - lh) / 2f
                    )
                )
            }
            // 骨线（先线后字，字压线在上层但不糊）
            layout.bones.forEach { bone ->
                drawLine(
                    boneColor, Offset(bone.bx, bone.by), Offset(bone.ex, bone.ey),
                    strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round
                )
                bone.ticks.forEach { t ->
                    drawLine(
                        subColor, Offset(t.px, t.py), Offset(t.endX, t.py),
                        strokeWidth = 1.2.dp.toPx(), cap = StrokeCap.Round
                    )
                }
            }
            // 文字：主因 / 子因（鱼头已画）
            layout.labels.forEach { lb ->
                val style = if (lb.kind == 0) causeStyle else subStyle
                val lineH = if (lb.kind == 0) causeLineHPx else subLineHPx
                lb.lines.forEachIndexed { i, line ->
                    drawText(
                        measurer, AnnotatedString(line), style = style,
                        topLeft = Offset(lb.x, lb.y + i * lineH)
                    )
                }
            }
        }

        if (layout.width > availPx) {
            // band 放不下：整图横向滑（与思维导图/代码块同款），标签保持自然宽不截字
            val diagramHs = rememberScrollState()
            Box(
                Modifier
                    .fillMaxWidth()
                    .hScrollZone()
                    .hScrollHint(diagramHs)
                    .horizontalScroll(diagramHs)
            ) {
                Canvas(
                    Modifier
                        .width(with(density) { layout.width.toDp() })
                        .height(with(density) { layout.height.toDp() })
                        .padding(vertical = 4.dp),
                    drawFish
                )
            }
        } else {
            Canvas(
                Modifier
                    .fillMaxWidth()
                    .height(with(density) { layout.height.toDp() })
                    .padding(vertical = 4.dp),
                drawFish
            )
        }
    }
    Spacer(Modifier.height(10.dp))
}

// ==================== v10：mermaid 图（WebView + 本地 mermaid.min.js，离线） ====================

/** mermaid 代码 → HTML 包装（转义 + 主题 + 高度回传） */
private fun mermaidHtml(code: String, dark: Boolean): String {
    val escaped = code.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;")
    val theme = if (dark) "dark" else "default"
    return """<!DOCTYPE html><html><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<style>body{margin:0;padding:4px;background:transparent;overflow-x:hidden}svg{max-width:100% !important;height:auto !important}</style>
<script src="mermaid.min.js"></script></head><body>
<pre class="mermaid" id="c">$escaped</pre>
<script>
mermaid.initialize({startOnLoad:false,theme:"$theme",securityLevel:"loose"});
mermaid.run({querySelector:".mermaid"}).then(function(){
  var b=document.body.getBoundingClientRect();
  Android.onHeight(Math.ceil(b.height+8));
}).catch(function(e){ Android.onHeight(120); });
</script></body></html>"""
}

/** mermaid 图块：WebView 渲染，高度由 JS 回传自适应 */
@Composable
fun MermaidBlock(code: String) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val dark = androidx.compose.foundation.isSystemInDarkTheme()
    var heightDp by remember { mutableIntStateOf(220) }
    val wv = remember(code, dark) {
        android.webkit.WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.allowFileAccess = true
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            addJavascriptInterface(object : Any() {
                @android.webkit.JavascriptInterface
                fun onHeight(h: Int) {
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        heightDp = h.coerceIn(60, 2000)
                    }
                }
            }, "Android")
        }
    }
    androidx.compose.runtime.DisposableEffect(code, dark) {
        wv.loadDataWithBaseURL("file:///android_asset/", mermaidHtml(code, dark), "text/html", "base64", null)
        // 2026-11-17 滚动流畅性 P2：原来 onDispose 空——LazyColumn item 滑出销毁时
        // WebView 不 destroy、JS 不停、渲染进程不回收，长 mermaid 会话多次进出累积实例
        // → 内存与渲染进程压力，滚动掉帧。code/dark 变化时旧实例也走这条 onDispose。
        onDispose {
            runCatching { wv.stopLoading() }
            runCatching { wv.removeJavascriptInterface("Android") }
            runCatching { wv.destroy() }
        }
    }
    androidx.compose.ui.viewinterop.AndroidView(
        factory = { wv },
        modifier = Modifier.fillMaxWidth().height(heightDp.dp)
    )
    Spacer(Modifier.height(10.dp))
}
