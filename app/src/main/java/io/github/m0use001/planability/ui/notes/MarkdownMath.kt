package io.github.m0use001.planability.ui.notes

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.unit.takeOrElse
import androidx.compose.ui.unit.sp
import androidx.collection.LruCache
import io.github.m0use001.planability.core.hScrollZone
import io.github.m0use001.planability.ui.common.hScrollHint
import org.scilab.forge.jlatexmath.TeXFormula
import ru.noties.jlatexmath.JLatexMathDrawable
import kotlinx.coroutines.launch

/** 文本/公式分段：Markdown 行内与块级 LaTeX 统一处理 */
sealed class MathSeg {
    data class Txt(val text: String) : MathSeg()
    /**
     * [labels]：块级公式里被剥离的中文标注（`\text{ 解}` 这类）。
     * jlatexmath 画不了 CJK，2026-11-02 起对 display 块做「剥 CJK 保留数学 + 中文下挂一行」：
     * 此前整块被 hasCJK 拒绝成裸文本，线性代数会话两段解集判据全裸显。
     */
    data class Math(val latex: String, val display: Boolean, val labels: String = "") : MathSeg()
}

private fun isCjkChar(c: Char): Boolean {
    val code = c.code
    return code in 0x2E80..0x9FFF || code in 0xF900..0xFAFF || code in 0xFF00..0xFFEF
}

/** v12：内容含 CJK 一律不算公式——价格 "$2/M、缓存 $0.10/M" 会被误判成 $...$，且 jlatexmath 本就渲染不了中文 */
private fun hasCJK(s: String): Boolean = s.any {
    val c = it.code
    c in 0x2E80..0x9FFF || c in 0xF900..0xFAFF || c in 0xFF00..0xFFEF
}

/**
 * 2026-11-10：`\[ … \]` / `\( … \)` 定界符归一成 `$$` / `$`。
 *
 * 之前只把**开**定界符换成 `$$`、收的 `\]` 没换，结果整段找不到闭合 `$$`，
 * 公式直接被当正文裸显（真机：模型用 \[…\] 写的公式一个都没渲染）。
 * 只处理**单反斜杠**：`\\[4pt]` 是双反斜杠（换行+间距），不能被误伤。
 */
private fun normalizeTexDelimiters(s: String): String {
    if (s.indexOf('\\') < 0) return s
    val out = StringBuilder(s.length)
    var i = 0
    while (i < s.length) {
        val c = s[i]
        val prevIsBackslash = i > 0 && s[i - 1] == '\\'
        if (c == '\\' && !prevIsBackslash && i + 1 < s.length) {
            when (s[i + 1]) {
                '[' -> { out.append("$$"); i += 2; continue }
                ']' -> { out.append("$$"); i += 2; continue }
                '(' -> { out.append("$"); i += 2; continue }
                ')' -> { out.append("$"); i += 2; continue }
            }
        }
        out.append(c)
        i++
    }
    return out.toString()
}

/**
 * 2026-11-10：把块级公式内容按行拆成「数学行 / 说明行」。
 *
 * 模型常把解释文字直接写进 `$$…$$`（"其中 x 表示人数"），以前这些文字被
 * [stripCjkLabels] 当标签抽走 → 用户看到两段公式之间的正常文字变成居中暗色小字。
 * 判据：**有 2 个以上汉字且不含反斜杠**的行 = 说明行（含 `\text{…}` 的行仍按数学处理，
 * 那是公式内标注）。
 */
private fun splitProseAndMath(content: String): List<Pair<Boolean, String>> {
    val out = mutableListOf<Pair<Boolean, String>>()
    val mathBuf = StringBuilder()
    val proseBuf = StringBuilder()
    fun flushMath() {
        if (mathBuf.isNotEmpty()) { out += true to mathBuf.toString(); mathBuf.clear() }
    }
    fun flushProse() {
        if (proseBuf.isNotEmpty()) { out += false to proseBuf.toString(); proseBuf.clear() }
    }
    for (ln in content.split('\n')) {
        val t = ln.trim()
        val isProse = t.count(::isCjkChar) >= 2 && !t.contains('\\')
        if (isProse) {
            flushMath()
            proseBuf.append(t).append('\n')
        } else {
            flushProse()
            mathBuf.append(ln).append('\n')
        }
    }
    flushMath()
    flushProse()
    return out
}

/**
 * 把文本切分为 普通文本 与 数学公式 段：
 * 支持 $$…$$（可跨行）、$…$、\(…\)、\[…\]；不处理代码围栏内容（调用方保证）。
 * v12 防误判：行内 $…$ 必须同行闭合且内容不含 CJK（美元金额/普通文本不受影响）。
 *
 * 2026-11-10（用户笔记本报障）三处修正：
 * ① `\[ … \]` 之前只把开定界符换成 `$$`、收定界符没换 → 整段识别失败当正文裸显；
 *    现在先做一遍**单反斜杠**定界符归一（`\\[4pt]` 是双反斜杠，不会被误伤）。
 * ② 块级公式里混入的说明文字（模型常把解释写进 $$…$$）不再被整段抽成"标签"，
 *    而是按行拆出来当正文（用户看到的现象：两公式之间的普通文字变成居中暗色小字）。
 * ③ `\\[4pt]` 这类换行间距、以及裸换行，统一在 normalizeLatex 里处理（见下）。
 */
/**
 * 2026-11-14 快速滚动卡顿二段修：`remember` 只在**同一个组合实例**内有效——LazyColumn
 * item 滑出即销毁，往复滑动（用户最高频的动作）每次回场都要全量重跑本函数与
 * [splitFenceSegments]（正则密集热点）。这里改为**跨组合的模块级 LruCache**：
 * key=原文、值不可变（MathSeg 全是 immutable data class），回场零解析成本。
 * 256 条 ≈ 一整个重型会话的段落集合。
 */
private val mathSegCache = LruCache<String, List<MathSeg>>(256)

/** 块级公式「拆行重渲」结论缓存（2026-11-21 滚动单帧瞬移根治②）：gathered 决策原来只活在
 *  remember 里——LazyColumn item 滑出即销毁，回场从原始 latex 重新开始：先按单行渲、
 *  SideEffect 再换 gathered 版重渲，高度一帧内大变（停在底部时表现为内容自己弹一下，
 *  追踪器实锤 突跳350px/49ms[手0程0底1]）。结论按 latex|屏宽 跨组合缓存，
 *  回场首帧直接用正确布局，不再翻转。 */
private val gatheredCache = LruCache<String, String>(128)

fun splitMathSegments(text: String): List<MathSeg> {
    mathSegCache.get(text)?.let { return it }
    val parsed = splitMathSegmentsUncached(text)
    mathSegCache.put(text, parsed)
    return parsed
}

private fun splitMathSegmentsUncached(text: String): List<MathSeg> {
    val normalized = normalizeTexDelimiters(text)
    val segs = mutableListOf<MathSeg>()
    val buf = StringBuilder()
    var i = 0
    val n = normalized.length
    fun flush() {
        if (buf.isNotEmpty()) { segs += MathSeg.Txt(buf.toString()); buf.clear() }
    }
    while (i < n) {
        val c = normalized[i]
        if (c == '$') {
            val display = i + 1 < n && normalized[i + 1] == '$'
            val openLen = if (display) 2 else 1
            val close = normalized.indexOf(if (display) "$$" else "$", i + openLen)
            // 行内 $：不允许跨行闭合
            val limit = if (display) n else normalized.indexOf('\n', i + openLen).let { if (it == -1) n else it }
            val content = if (close in (i + openLen) until limit)
                normalized.substring(i + openLen, close).trim() else null
            if (!content.isNullOrBlank()) {
                // 2026-11-18 真机（◆ 消息 7719，用户「又出现乱码」）：整块是一个
                // `\begin{aligned}` 环境（`&&` 双列 + 行内 \text{CJK}）时，jlatexmath
                // 画不了整块 → 回退裸文本，屏幕上满是 `\begin{aligned}` 与 `&`。
                // 只有这种「整块对齐环境」才拆行（每行一条独立公式 + 行注解）。
                if (display) {
                    val alignedRows = splitAlignedRows(content)
                    if (alignedRows != null) {
                        flush()
                        for ((f, ann) in alignedRows) {
                            if (f.isNotEmpty()) {
                                // 2026-11-19 用户「文字被挤出」再犯（真机截图：对齐环境里
                                // 每行公式前面都挂着一对**空括号「( )」**，中文注解整段不见了）。
                                // 病根：这条路径（2026-11-18 拆行修）把每一行**直接**塞进
                                // MathSeg.Math，**没走 [stripCjkLabels]** —— 于是 `\text{定义}`
                                // 原样进了 jlatexmath：它画不了 CJK，渲染成空字形，
                                // 屏幕上就剩一对空括号，中文被「挤」没了。
                                // 这里与其它块级路径同口径：CJK 剥成标签，挂在公式下方。
                                val (cleanRow, rowLabels) = stripCjkLabels(f)
                                val mergedLabels = listOf(ann, rowLabels)
                                    .filter { it.isNotBlank() }
                                    .joinToString(" · ")
                                segs += MathSeg.Math(normalizeLatex(cleanRow, true), true, mergedLabels)
                            } else if (ann.isNotEmpty()) {
                                // 纯注解行（「中间：常数倍直接提」）回正文
                                segs += MathSeg.Txt(ann)
                            }
                        }
                        i = close + openLen
                        continue
                    }
                }
                if (!hasCJK(content)) {
                    flush()
                    segs += MathSeg.Math(normalizeLatex(content, display), display)
                    i = close + openLen
                    continue
                }
                if (display && isSingleMathEnv(content)) {
                    // 2026-11-24（消息 8097，用户截图「三种取法 cases」）：整块就是一个
                    // 多行环境（cases/array/matrix…）时**绝不按行拆散文**——旧路径把
                    // \begin{cases}/每行/ \end{cases} 拆成多段，每段都解析失败→裸 LaTeX。
                    // jlatexmath 原生支持 cases（探针实测）：只剥 CJK 标签、保留环境整体渲染。
                    flush()
                    val (cleanEnv, envLabels) = stripCjkLabels(content)
                    if (cleanEnv.any { !it.isWhitespace() }) {
                        segs += MathSeg.Math(normalizeLatex(cleanEnv, true), true, envLabels)
                    } else if (envLabels.isNotBlank()) {
                        segs += MathSeg.Math("", true, envLabels)
                    }
                    i = close + openLen
                    continue
                }
                if (display) {
                    // 2026-11-10 修正：模型常把**解释文字整段写进 $$…$$**，以前这些文字被当成
                    // 标签抽走、渲染成居中暗色小字。现在按行拆：说明行回正文，数学行照渲。
                    val chunks = splitProseAndMath(content)
                    // 2026-11-17：整块都是**标签链**时（公式本体被吃光、只剩标签）也算
                    // 「有可渲染内容」——旧判据只看公式本体，会把整块当成没内容、
                    // 掉进下面的行内分支，把 `$$` 定界符泄成字面量（用户截图里
                    // 「MVT」孤零零 + 下方一行散字就是这么来的）。
                    val hasRenderable = chunks.any { (isMath, body) ->
                        if (!isMath) {
                            body.isNotBlank()
                        } else {
                            val (c2, l2) = stripCjkLabels(body)
                            c2.any { !it.isWhitespace() } || l2.isNotBlank()
                        }
                    }
                    if (hasRenderable) {
                        flush()
                        for ((isMath, body) in chunks) {
                            if (isMath) {
                                // v56.2：行内的句级中文连接词（「其中」「若」）不再是公式下方
                                // 的标签——顶层 CJK 连接词把一行拆成 数学/正文/数学 顺序块
                                //（正文行回两段公式之间，阅读顺序不再断裂）。
                                // 括注型与结构内 \text 仍走 stripCjkLabels 老路。
                                val parts = splitDisplayProse(body)
                                if (parts != null) {
                                    for ((b, isM) in parts) {
                                        if (isM) {
                                            val (clean, labels) = stripCjkLabels(b)
                                            if (clean.any { !it.isWhitespace() }) {
                                                segs += MathSeg.Math(normalizeLatex(clean, true), true, labels)
                                            } else if (labels.isNotBlank()) {
                                                // 2026-11-17：整块都是标签链（公式本体被吃光）→
                                                // 只渲染居中的一行文字（空公式 + 标签）
                                                segs += MathSeg.Math("", true, labels)
                                            }
                                        } else if (b.isNotBlank()) {
                                            segs += MathSeg.Txt(b)
                                        }
                                    }
                                } else {
                                    // v56.4 多段中文的整句（`lim…=L 说的是 x 趋近 c …`）：
                                    // 按源码顺序切成 数学/正文 交替段，**行内**流式渲染——
                                    // 中文不再被抽到公式下方（用户截图「中文被挤出」），
                                    // 也不会像切块那样把「当/时，有」碎成几段。
                                    val sent = splitDisplaySentence(body)
                                    if (sent != null) {
                                        for ((b, isM) in sent) {
                                            if (isM) {
                                                val (clean, labels) = stripCjkLabels(b)
                                                if (clean.any { !it.isWhitespace() }) {
                                                    // display=false → 回缓冲拼进行内文本，与中文同段
                                                    segs += MathSeg.Math(normalizeLatex(clean, false), false)
                                                }
                                                // 行内段没有"公式下方"可挂标签：剥出的中文按位置续写
                                                if (labels.isNotBlank()) segs += MathSeg.Txt(labels)
                                            } else if (b.isNotBlank()) {
                                                segs += MathSeg.Txt(b)
                                            }
                                        }
                                    } else {
                                        // 2026-11-17 用户（图 1，第三次报「中文被挤出」）：
                                        // `$$\boxed{\det A=…a_{kk}^{(\text{主元})}}$$` 剥完公式里
                                        // 留一对空括号、中文孤零零挂到公式下方。用户拍板
                                        // 「中文回到公式行内」→ 这块改走行内混排（中文与公式同段）。
                                        val (clean, labels) = stripCjkLabels(body)
                                        val inlinePieces =
                                            if (hasStrippedAnnotationScript(clean)) splitDisplayCjkInline(body)
                                            else null
                                        if (inlinePieces != null) {
                                            for ((b, isM) in inlinePieces) {
                                                if (isM) {
                                                    if (b.isNotBlank()) {
                                                        segs += MathSeg.Math(normalizeLatex(b, false), false)
                                                    }
                                                } else if (b.isNotBlank()) {
                                                    segs += MathSeg.Txt(b)
                                                }
                                            }
                                        } else if (clean.any { !it.isWhitespace() }) {
                                            segs += MathSeg.Math(normalizeLatex(clean, true), true, labels)
                                        } else if (labels.isNotBlank()) {
                                            // 2026-11-17 用户「中文被挤出」真源（消息 7634）：
                                            // `\text{费马引理…} ⇒ \text{Rolle…} ⇒ \text{MVT}
                                            // ⇒ \text{定理 B}` 整块是一条**标签链**——公式本体
                                            // （MVT）也进了链，剩下的是一行居中文字。
                                            segs += MathSeg.Math("", true, labels)
                                        }
                                    }
                                }
                            } else if (body.isNotBlank()) {
                                segs += MathSeg.Txt(body.trim())
                            }
                        }
                        i = close + openLen
                        continue
                    }
                } else {
                    // 2026-11-14 用户「中文混排」（◆ 测试消息里 AI 标注的
                    // 「中文入公式最容易崩，重点看这条」）：行内 `$…$` 含 CJK 以前**整段
                    // 当正文**、连 `$` 一起裸显（`$\text{体积}=\frac{4}{3}\pi r^{3}$` 原样）。
                    // 修：把 CJK 片段（`\text{…}` 内或裸中文 run）抽成正文，其余 LaTeX 照常
                    // 渲染，流式拼成「体积 =4/3πr³」（jlatexmath 画不了 CJK，混排只能这样分件）。
                    val pieces = splitInlineCjk(content)
                    if (pieces.any { it.second }) {   // 至少有一段可渲的数学
                        flush()
                        for ((body, isMath) in pieces) {
                            if (isMath) {
                                if (body.isNotBlank()) segs += MathSeg.Math(normalizeLatex(body, false), false)
                            } else {
                                segs += MathSeg.Txt(body)
                            }
                        }
                        i = close + openLen
                        continue
                    }
                }
            }
            buf.append(c)
            i++
            continue
        }
        buf.append(c)
        i++
    }
    flush()
    return segs
}

/**
 * 推导箭头命令 → Unicode 文本符号（**全名**匹配——`\top` 不得误当 `\to`）。
 * 中文被抽出公式流时，紧邻的推导箭头跟着转成文本符号随说明走，不在数学流里悬空。
 */
private val arrowSymbols: Map<String, String> = mapOf(
    "Longrightarrow" to "⟹", "longrightarrow" to "⟶",
    "Rightarrow" to "⇒", "rightarrow" to "→",
    "Longleftarrow" to "⟸", "longleftarrow" to "⟵",
    "Leftarrow" to "⇐", "leftarrow" to "←",
    "Longleftrightarrow" to "⟺", "longleftrightarrow" to "⟺",
    "Leftrightarrow" to "⇔", "leftrightarrow" to "↔",
    "implies" to "⟹", "impliedby" to "⟸", "iff" to "⟺",
    "to" to "→", "gets" to "←", "mapsto" to "↦", "leadsto" to "⇝",
    "Uparrow" to "⇑", "Downarrow" to "⇓", "uparrow" to "↑", "downarrow" to "↓",
    "updownarrow" to "↕", "nearrow" to "↗", "searrow" to "↘",
    "swarrow" to "↙", "nwarrow" to "↖"
)

/**
 * 从 s 尾部剥「间距 + 推导箭头」（连续多个一起剥），返回 (剥剩文本, 箭头串·Unicode·原序)。
 * 纯函数，JVM 可测。
 */
internal fun stripTrailingArrows(s: String): Pair<String, String> {
    var e = s.length
    val sb = StringBuilder()
    while (true) {
        while (e > 0) {
            val c = s[e - 1]
            if (c.isWhitespace()) { e--; continue }
            val tail = s.substring(0, e)
            val sp = listOf("\\,", "\\;", "\\:", "\\!", "\\ ").firstOrNull { tail.endsWith(it) }
            if (sp != null) { e -= 2; continue }
            if (tail.endsWith("\\qquad")) { e -= 6; continue }
            if (tail.endsWith("\\quad")) { e -= 5; continue }
            break
        }
        val m = Regex("\\\\([A-Za-z]+)$").find(s.substring(0, e)) ?: break
        val sym = arrowSymbols[m.groupValues[1]] ?: break
        sb.insert(0, sym)
        e = m.range.first
    }
    return s.substring(0, e).trimEnd() to sb.toString()
}

/** 从 latex 的 from 位置起剥「间距 + 推导箭头」，返回 (新位置, 箭头串·Unicode·原序) */
internal fun stripLeadingArrows(latex: String, from: Int): Pair<Int, String> {
    var i = from
    val sb = StringBuilder()
    while (true) {
        while (i < latex.length) {
            val c = latex[i]
            if (c.isWhitespace()) { i++; continue }
            if (c == '\\') {
                if (i + 1 < latex.length && latex[i + 1] in ",;:! ") { i += 2; continue }
                if (latex.startsWith("\\qquad", i)) { i += 6; continue }
                if (latex.startsWith("\\quad", i)) { i += 5; continue }
            }
            break
        }
        val m = Regex("\\\\([A-Za-z]+)").find(latex, i) ?: break
        if (m.range.first != i) break
        val sym = arrowSymbols[m.groupValues[1]] ?: break
        sb.append(sym)
        i = m.range.last + 1
    }
    return i to sb.toString()
}

/**
 * 行内公式的 CJK 拆件（2026-11-14 用户「中文混排」）：
 * `\text{体积}=\frac{4}{3}\pi r^{3}` → [("体积", 正文), ("=\frac{4}{3}\pi r^{3}", 数学)]；
 * `\text{极限}\lim_{x\to 0}` → [("极限", 正文), ("\lim_{x\to 0}", 数学)]；
 * 裸中文（模型常不写 `\text` 直接塞）同样按 run 抽出。返回 (片段, 是否数学) 顺序保持原文。
 * 纯函数，JVM 可测。
 *
 * 2026-11-14 二轮（用户截图「1≠-1 ⇒ ⇒ / 双侧极限不存在·不可导」）：抽出中文时**紧邻的
 * 推导箭头一并剥出**转 Unicode 随中文走——`1\neq-1 \Rightarrow \text{双侧极限不存在·不可导}`
 * 旧版剥完数学剩 `1\neq-1 \Rightarrow`（悬空箭头）、中文掉出公式行。箭头是推导链的连接，
 * 该跟着它指向的说明走：前侧箭头挂中文段**头部**（"⇒ 双侧极限…"），后侧箭头独立成小段
 * 拼在中文与下一段之间（读序 = 原文）。
 */
internal fun splitInlineCjk(content: String): List<Pair<String, Boolean>> {
    val out = mutableListOf<Pair<String, Boolean>>()
    val math = StringBuilder()
    fun flushMath() {
        val t = math.toString()
        if (t.isNotBlank()) out += t to true
        math.clear()
    }
    /** 抽出一段中文：前剥数学尾箭头（挂中文头）、入正文段、后剥紧跟箭头（独立小段）；返回新位置 */
    fun emitProse(inner: String, from: Int): Int {
        val kept = stripTrailingArrows(math.toString())
        math.setLength(0)
        var rest = kept.first
        var nextPos = from
        // 2026-11-17 用户截图（消息 7628）：`$s'=e^{某因子}\times\big[ …$` 里中文是 `^{…}`
        // 上标组的**全部内容**——中文抽走后数学剩 `s'=e^{` 和后面一段开头的 `}`，
        // 两头都成了孤零零的定界符（真机渲染成字面量 "}"）。规则：CJK run 独占一个
        // `^{…}`/`_{…}` 组时，**整组连壳掏空**（开壳从数学尾剥掉、闭壳跳过）。
        val shell = Regex("""[\^_]\s*\{$""").find(rest)
        if (shell != null && nextPos < content.length && content[nextPos] == '}') {
            rest = rest.substring(0, shell.range.first)
            nextPos++
        }
        math.append(rest)
        flushMath()
        out.add((if (kept.second.isNotEmpty()) kept.second + " " + inner else inner) to false)
        val (next, trail) = stripLeadingArrows(content, nextPos)
        if (trail.isNotEmpty()) out.add(trail to false)
        return next
    }
    var i = 0
    while (i < content.length) {
        if (content.startsWith("\\text{", i)) {
            val close = content.indexOf('}', i + 6)
            if (close > 0) {
                val inner = content.substring(i + 6, close)
                if (inner.any(::isCjkChar)) {
                    i = emitProse(inner, close + 1)
                    continue
                }
            }
        }
        val c = content[i]
        if (isCjkChar(c)) {
            val start = i
            while (i < content.length && isCjkChar(content[i])) i++
            i = emitProse(content.substring(start, i), i)
            continue
        }
        math.append(c)
        i++
    }
    flushMath()
    return out
}

/**
 * v56.2 块级公式的**句级中文连接词**拆分（2026-11-14 用户截图：
 * `D(x)=f'(c)+\alpha(x)，其中 \lim_{x\to c}\alpha(x)=0` 的「其中」被 stripCjkLabels
 * 抽成公式下方的标签、剥除处留 `\,` 大空档——连接词是句子的中段，抽走后阅读顺序断裂）。
 *
 * 把一行拆成 数学/正文/数学 的**顺序块**（正文行回到两段公式之间，与 2026-11-10 的
 * 「说明行回正文」同语义，只是粒度从行细化到句）。返回 null = 没有合格连接词，
 * 调用方走 [stripCjkLabels] 标签老路。拆出的数学段各自仍过 stripCjkLabels
 * （underbrace 等结构内标签不丢）。
 *
 * 识别纪律（防止把 2026-11-13 拍板的括注合并与推导链标签拆碎）：
 * - **任一候选紧邻推导箭头 → 整行 null**（推导链的中文是箭头串起来的推论标签，
 *   909b74f5 拍板形态「⇒ 双侧极限不存在 ⇒ 不可导」在公式下方连读；不能用「行里有 \to
 *   就退」——`\lim_{x\to c}` 里的 \to 是真数学下标，会误杀正例）；
 * - 只看**顶层**（花括号深度 0 且**圆括号深度 0**——`\quad(\text{…})` 尾注整段在括号里）
 *   的裸 CJK 串或顶层 `\text{CJK}` 整组——`\underbrace{…}_{\text{…}}` 里深度 ≥1，不候选；
 * - 候选必须有**前后两段非空数学**（尾注释 `x=1 \text{（解）}` 仍走标签老路）；
 * - 括注型不拆：prose 以 （/( 开头或以 ）/) 结尾的是桥接标签
 *   （`\text{（决定第一列}p, r\text{）}` 两段候选都被这条挡下）。
 */
internal fun splitDisplayProse(latex: String): List<Pair<String, Boolean>>? {
    val runs = topLevelCjkRuns(latex)
    if (runs.size != 1) return null
    val r = runs[0]
    val beforeStr = latex.substring(0, r.start)
    val afterStr = latex.substring(r.end)
    if (beforeStr.trim().isEmpty() || afterStr.trim().isEmpty()) return null   // 尾注释/头注释
    if (beforeStr.any(::isCjkChar) || afterStr.any(::isCjkChar)) return null   // 另一侧还有别的 CJK run
    if (tailArrow(beforeStr) || headArrow(afterStr)) return null               // 推导链老路
    val out = mutableListOf<Pair<String, Boolean>>()
    out.add(beforeStr.trim() to true)
    out.add(r.text.trim() to false)
    out.add(afterStr.trim() to true)
    return out
}

/**
 * v56.4 块级公式的**整句混排**（2026-11-14 用户截图：
 * `\lim_{x\to c}f(x)=L \quad\text{说的是}\quad x \text{ 趋近 } c \ldots` 渲染成
 * 公式行只剩 `lim…=L   x   c   f(x)   L`、中文被抽到下方一行「说的是 · 趋近 · 趋近」）。
 *
 * 多段中文时**不再抽标签**，而是按源码顺序切成交替的 数学/正文 段，整句按**行内**流式
 * 渲染（调用方给 display=false）：`lim_{x→c} f(x)=L 说的是 x 趋近 c …` 一句读下来，
 * 阅读顺序与源码一致。这也顺带解决 v56.2 担心的「当/时，有」碎片化——碎片化来自
 * "切块后各自成段"，行内流式不会。
 *
 * 返回 null 的情况（继续走 [stripCjkLabels] 标签老路）：
 * - 候选 <2 段（单段连接词仍是 [splitDisplayProse] 的 数学/正文/数学 顺序块）；
 * - 任一候选**紧贴推导箭头**（909b74f5 拍板：推导链的推论标签在公式下方连读）；
 * - 中文段或数学段少于两段（整块其实就是一条公式 + 一个尾注）。
 */
internal fun splitDisplaySentence(latex: String): List<Pair<String, Boolean>>? {
    val runs = topLevelCjkRuns(latex)
    if (runs.size < 2) return null
    for (r in runs) {
        if (tailArrow(latex.substring(0, r.start)) || headArrow(latex.substring(r.end))) return null
    }
    val out = mutableListOf<Pair<String, Boolean>>()
    var cursor = 0
    for (r in runs) {
        val math = trimSpacing(latex.substring(cursor, r.start))
        if (math.isNotEmpty()) out.add(math to true)
        val prose = r.text.trim()
        if (prose.isNotEmpty()) out.add(prose to false)
        cursor = r.end
    }
    val tail = trimSpacing(latex.substring(cursor))
    if (tail.isNotEmpty()) out.add(tail to true)
    if (out.count { it.second } < 2) return null   // 数学段不足（整块没几句公式）
    if (out.count { !it.second } < 2) return null  // 中文段不足（其实只有一处注释）
    return out
}

/** [splitDisplayProse]/[splitDisplaySentence] 共用的顶层 CJK 候选段（已滤掉括注型） */
private data class CjkRunCandidate(val start: Int, val end: Int, val text: String)

/**
 * 扫出**顶层**（花括号深度 0 且圆括号深度 0——`\quad(\text{…})` 尾注整段在括号里）的
 * CJK 候选：顶层 `\text{CJK}` 整组，或裸 CJK 串。`\underbrace{…}_{\text{…}}` 里深度 ≥1，
 * 不算候选。括注型（以 （/( 开头或以 ）/) 结尾）是桥接标签，直接排除。
 */
private fun topLevelCjkRuns(latex: String): List<CjkRunCandidate> {
    val runs = mutableListOf<CjkRunCandidate>()
    var depth = 0
    var paren = 0
    var i = 0
    while (i < latex.length) {
        if (latex.startsWith("\\text{", i) && depth == 0 && paren == 0) {
            // 顶层 \text{...}：内容含 CJK → 整组算一个 prose 候选（跨到配对 }）
            var d = 1
            var j = i + 6
            while (j < latex.length && d > 0) {
                when (latex[j]) {
                    '{' -> d++
                    '}' -> d--
                }
                j++
            }
            val inner = latex.substring(i + 6, j - 1)
            if (inner.any(::isCjkChar)) {
                runs.add(CjkRunCandidate(i, j, inner))
                i = j
                continue
            }
            i += 6
            continue
        }
        val c = latex[i]
        if (depth == 0 && paren == 0 && isCjkChar(c)) {
            val start = i
            while (i < latex.length && isCjkChar(latex[i])) i++
            runs.add(CjkRunCandidate(start, i, latex.substring(start, i)))
            continue
        }
        when (c) {
            '{' -> depth++
            '}' -> if (depth > 0) depth--
            '(' -> paren++
            ')' -> if (paren > 0) paren--
        }
        i++
    }
    return runs.filter { r ->
        val t = r.text.trim()
        t.isNotEmpty() && !(t.first() in "（(「【" || t.last() in "）)」】")
    }
}

/** 推导箭头判定：紧跟在该段**末尾**的箭头命令（`\to`/`\Rightarrow`…）或 Unicode 箭头 */
private fun tailArrow(s: String): Boolean {
    val t = s.trimEnd()
    Regex("""\\([a-zA-Z]+)\s*$""").find(t)?.let { return it.groupValues[1] in arrowSymbols.keys }
    return t.isNotEmpty() && t.last() in "→⇒⇐↔↦⇝"
}

/** 推导箭头判定：紧贴在该段**开头**的箭头命令或 Unicode 箭头 */
private fun headArrow(s: String): Boolean {
    val t = s.trimStart()
    Regex("""^\\([a-zA-Z]+)""").find(t)?.let { return it.groupValues[1] in arrowSymbols.keys }
    return t.isNotEmpty() && t.first() in "→⇒⇐↔↦⇝"
}

/**
 * 去掉数学段首尾的排版间距（`\quad`/`\qquad`/`\,`/`\;`/`\!`/`~` 与空白）。
 * 整句混排时这些间距是给独立显示公式排版的，行内留着会变成莫名其妙的空隙。
 */
private fun trimSpacing(s: String): String {
    val lead = Regex("""^(\s|~|\\quad|\\qquad|\\,|\\;|\\:|\\!)+""")
    val trail = Regex("""(\s|~|\\quad|\\qquad|\\,|\\;|\\:|\\!)+$""")
    var t = s
    while (true) {
        val n = t.replaceFirst(lead, "").replaceFirst(trail, "")
        if (n == t) return t
        t = n
    }
}

/**
 * 块级公式剥 CJK：`\text{ 解}` 类整组的中文抽出来当标签（渲染在公式下方用普通文字），
 * 其余散落 CJK 直接去掉；数学部分原样保留。多标签用「 · 」连接。
 *
 * 2026-11-13 用户七截图（标签「的 n可逆 · 对任意…」乱序、`\text{（决定第一列}p, r\text{）}`
 * 被拆散）：旧实现是三段正则**分阶段全串替换**（先整段括注、再 varBridge 预扫、再逐个
 * \text），varBridge 预扫阶段就把靠后的桥接标签先 addLabel → 标签顺序 ≠ 源码顺序。
 * 改成**单遍从左到右扫描**：括注 / \text+桥接续写 / 散落 \text 都按出现顺序入列。
 * 两个 \text{CJK} 组之间只夹标识符或逗号列表（`（决定第一列}p, r\text{）}`）时并成**一条**
 * 标签（同一句话，不再被「 · 」切断）；剥除处留 `\,` 间距——`b\text{对任意}b` 剥完是
 * `b\,b` 而不是粘连的 `bb`（math 里裸空格会被 TeX 忽略，必须是真间距命令）。
 */
/**
 * `\underbrace{…}` / `\overbrace{…}` 及其紧随的上下标组（`_{…}`/`^{…}`）的**整段区间**。
 *
 * 2026-11-22 用户截图「文字飞了」：`\underbrace{…}_{\text{二阶小量}}` 的标注被 ② 当普通
 * `\text{CJK}` 抽成公式下方的一行、花括号下标变空壳——括号标注（909b74f5 拍板「挂在
 * 花括号下方连读」）本来就该在括号正下方。以前做不到是因为 jlatexmath 画不了 CJK；
 * 现在 [LatexRender.cjkFontRegistered] 注册了外部字体，结构内标注可以真正渲在括号下：
 * 剥 CJK 时整段保护、原样保留（下标里的散落 CJK 也不丢）。纯函数，JVM 可测。
 */
internal fun braceScriptRanges(latex: String): List<IntRange> {
    val out = mutableListOf<IntRange>()
    var i = 0
    while (i < latex.length) {
        val cmdLen = when {
            latex.startsWith("\\underbrace", i) -> 11
            latex.startsWith("\\overbrace", i) -> 10
            else -> 0
        }
        if (cmdLen > 0) {
            var j = i + cmdLen
            if (j < latex.length && latex[j] == '{') {
                var d = 1; j++
                while (j < latex.length && d > 0) {
                    when (latex[j]) { '{' -> d++; '}' -> d-- }
                    j++
                }
                var k = j
                while (k < latex.length && latex[k].isWhitespace()) k++
                if (k < latex.length && (latex[k] == '_' || latex[k] == '^') &&
                    k + 1 < latex.length && latex[k + 1] == '{'
                ) {
                    var d2 = 1; var m = k + 2
                    while (m < latex.length && d2 > 0) {
                        when (latex[m]) { '{' -> d2++; '}' -> d2-- }
                        m++
                    }
                    out.add(i until m)
                }
                i = j
                continue
            }
        }
        i++
    }
    return out
}

private fun stripCjkLabels(latex: String): Pair<String, String> {
    val labels = StringBuilder()
    // underbrace/overbrace 标注整段保护（见 [braceScriptRanges]）：命中区间逐字节照抄，
    // ②的 \text 抽取与 ③的散落 CJK 丢弃都跳过。
    val protect = braceScriptRanges(latex)
    fun inProtect(p: Int) = protect.any { p >= it.first && p <= it.last }
    fun addLabel(text: String) {
        val l = text.trim()
        if (l.isNotEmpty()) {
            val prev = labels.lastOrNull()
            // 2026-11-11 用户截图：`第 i 行、第 j 列的元素` 被拆成 "第/行、第/列的元素" 再用
            // " · " 连接 →「第 · 行、第 · 列」。上一段以变量字母/数字结尾（"第 i"）时说明
            // 是同句续写，直接粘上不留分隔符；否则维持 " · "。
            // 2026-11-12 用户截图：`\underbrace{...}_{\text{...}}` 链的多个标签被粘成
            // 「方程下界递减收敛…」——旧判据 isLetterOrDigit() 对 CJK 同样为 true。
            // 只有 ASCII 字母/数字结尾才视为同句续写（"第 i" + "行"），其余一律 " · "。
            val asciiAlnum = prev != null && (prev in 'a'..'z' || prev in 'A'..'Z' || prev in '0'..'9')
            if (labels.isNotEmpty()) labels.append(if (asciiAlnum) "" else " · ")
            labels.append(l)
        }
    }
    /** \text 内文 → 标签文本：保留 CJK/字母/数字/空格/中英标点，压缩空白 */
    fun keepFilter(s: String): String = s
        .filter { isCjkChar(it) || it.isLetterOrDigit() || it == ' ' || "，。；：！？、,".contains(it) }
        .replace(Regex("\\s+"), " ")
        // 2026-11-10：A 与全角逗号之间会冒出一个空格（"右边还有 A ，只能解方程"）——
        // 中文标点前不留空格。
        .replace(Regex("\\s+([，。；：！？、])"), "$1")
        .trim()
    /** 桥接续写后追加下一段：中文标点/右括号前不留空格（"p, r）"而非"p, r ）"） */
    fun appendNext(buf: StringBuilder, next: String) {
        if (next.isEmpty()) return
        if (!"，。；：！？、）】》”’」".contains(next.first())) buf.append(' ')
        buf.append(next)
    }
    // 整段括注（\quad…(...)+CJK \text）连壳抽走；\text 中文组（可带桥接续写）按序入标签；
    // 其余字符照抄，散落 CJK 直接去掉（旧版末尾统一 filter 的语义）。
    val parenRe = Regex("\\\\(?:quad|qquad|,|;|!|\\s)*\\(([^()]*)\\)")
    val textRe = Regex("\\\\text\\{([^{}]*)\\}")
    // 桥接：两个 \text{CJK} 之间只夹「标识符列表或简单数学桥」= 同一句的续写。
    // v56.3（用户截图「被 · 控制」）：`_{\text{被 }f'(c)\text{ 控制}}` 的桥是 f'(c)——
    // 旧桥接只认 [A-Za-z0-9_] 标识符列表，含引号/括号/减号/比较符的句子桥匹配不上 → 标签碎
    // （「被 · 控制」「当 · x>1 · 时」）。放宽为：字母数字/引号/括号/减号/比较符/空格组成、
    // 且至少一个非空字符；**带反斜杠命令的桥（\mathbf{b}、\in）不算**——那类是公式本体，
    // 留在公式里（\mathbf{b}\text{对任意}\mathbf{b}\text{有唯一解} 的既有拍板形态不变）。
    val bridgeRe = Regex(
        "([A-Za-z0-9_'()\\-\\s,<>:=≤≥≠≈]*[A-Za-z0-9_'(),<>:=≤≥≠≈][A-Za-z0-9_'()\\-\\s,<>:=≤≥≠≈]*)\\\\text\\{([^{}]*)\\}"
    )
    val out = StringBuilder(latex.length)
    var i = 0
    while (i < latex.length) {
        // ⓪ underbrace/overbrace 标注整段保护：逐字节照抄（含内部 \text 与散落 CJK）
        if (inProtect(i)) {
            out.append(latex[i])
            i++
            continue
        }
        // ① 整段括注（含 \text{CJK}）连壳抽走——2026-11-10「()内注释被挤出」的修法，
        //    顺序并入单遍：括注前已出现的 \text 标签排在它前面（旧版括注恒先入列）。
        val pm = parenRe.find(latex, i)
        if (pm != null && pm.range.first == i) {
            val inner = pm.groupValues[1]
            val hasCjkText = Regex("\\\\text\\{[^{}]*\\}").findAll(inner).any { it.value.any(::isCjkChar) }
            if (hasCjkText) {
                // 2026-11-14 二轮：括注前侧的悬空推导箭头一并转 Unicode 挂标签头
                val lead = stripTrailingArrows(out.toString())
                if (lead.second.isNotEmpty()) {
                    out.setLength(0)
                    out.append(lead.first)
                }
                val body = keepFilter(
                    inner.replace(Regex("\\\\text\\{([^{}]*)\\}")) { g -> " ${g.groupValues[1]} " }
                )
                addLabel(if (lead.second.isNotEmpty()) lead.second + " " + body else body)
                out.append("\\,")
                i = pm.range.last + 1
                continue
            }
            // 纯数学括号（`g(n)`）不整段跳过——落到③逐字节照抄，括号内 \text 由②处理
        }
        // ①b **纯括注**（2026-11-19 用户「文字被挤出」再犯，真机截图：对齐环境里每行
        //     公式前面挂着一对**空括号「( )」**，中文注解整段不见了）。
        //     病根：① 的括注正则要求括号**前面有反斜杠命令**（`\quad(…)`、`\,(…)`），
        //     纯括号 `(\text{定义})` 不认 → 落到②把 \text 剥走 → 括号留在公式里成空壳，
        //     中文被「挤」没了（jlatexmath 画不了 CJK，渲染出来就是空字形）。
        //     这里把「括号里只有一条 \text」的括注整组抽走，中文当标签挂到公式下方。
        //     只认「内容仅一条 \text」——括号里还夹着别的数学（如 `(\text{其中} f'(x))`）
        //     时不许整组抽走，那会把公式本体一起吃掉。
        val pureTextRe = Regex("""\(\s*(\\text\{[^{}]*\})\s*\)""")
        val ptm = pureTextRe.find(latex, i)
        if (ptm != null && ptm.range.first == i) {
            val innerText = ptm.groupValues[1]
            if (innerText.any(::isCjkChar)) {
                val body = innerText.removePrefix("\\text{").removeSuffix("}")
                addLabel(keepFilter(body))
                out.append("\\,")
                i = ptm.range.last + 1
                continue
            }
        }
        // ② \text{…}：CJK → 标签（并桥接续写到下一个 CJK \text）；ASCII → 原样留公式
        //    2026-11-17 用户「中文被挤出」（真机消息 7634 的真源）：推导链上的 **ASCII
        //    \text 组**也是链的一节（`\text{费马引理…} ⇒ \text{Rolle…} ⇒ \text{MVT}
        //    ⇒ \text{定理 B}`）。旧实现把 ASCII 组留在公式里、把两侧中文抽到公式下方
        //    → 链从中间被撕开（MVT 悬在上方、箭头散成「⇒ · ⇒」）——用户看到的正是
        //    「MVT」孤零零一个公式 + 下方一行被拆散的中文。
        //    判据：其后（跳过推导箭头与间距）紧跟一个含 CJK 的 \text 组 = 链成员，
        //    跟两侧中文标签并进**同一条**标签链，顺序照源码。
        val tm = textRe.find(latex, i)
        if (tm != null && tm.range.first == i) {
            val inner = tm.groupValues[1]
            val groupEnd = tm.range.last + 1
            val isCjk = inner.any(::isCjkChar)
            if (isCjk || followedByCjkText(latex, groupEnd)) {
                // 2026-11-14 二轮（用户截图「1≠-1 ⇒ ⇒ / 双侧极限不存在·不可导」）：
                // 剥除点**前侧**的悬空推导箭头转 Unicode 挂标签头——`$$1\neq-1 \Rightarrow
                // \text{双侧极限不存在}$$` 旧版剥完公式行剩 `1\neq-1 \Rightarrow \,`（悬空箭头）
                val lead = stripTrailingArrows(out.toString())
                if (lead.second.isNotEmpty()) {
                    out.setLength(0)
                    out.append(lead.first)
                }
                val buf = StringBuilder(if (isCjk) keepFilter(inner) else inner.trim())
                if (lead.second.isNotEmpty()) buf.insert(0, lead.second + " ")
                var end = tm.range.last + 1
                // 2026-11-14 用户「中间的"个"哪来的」：`\underbrace{…}_{n\ \text{个}}` 的 n
                // 是标注的一部分（n 个），旧版只抽「个」→ 数学里剩个空下标、标签孤零零一个
                // 「个」。规则：紧邻的 `_{单变量}` 组**整组被掏空**时并入标签前缀
                //（组里还有别的数学成分就不动；掏空后把紧随的 `}` 一并吃掉防悬空大括号）。
                val subTail = Regex("_\\{\\s*([A-Za-z0-9]+)\\s*(?:\\\\[,;:! ]|\\\\quad|\\\\qquad|\\s)*$")
                val sm = subTail.find(out)
                if (sm != null) {
                    buf.insert(0, sm.groupValues[1] + " ")
                    out.setLength(sm.range.first)
                    if (latex.getOrNull(end) == '}') end++
                }
                while (true) {
                    var advanced = false
                    val bm = bridgeRe.find(latex, end)
                    if (bm != null && bm.range.first == end && bm.groupValues[2].any(::isCjkChar)) {
                        buf.append(' ').append(bm.groupValues[1].trim())
                        appendNext(buf, keepFilter(bm.groupValues[2]))
                        end = bm.range.last + 1
                        advanced = true
                    }
                    // 2026-11-14 二轮：剥除点**后侧**的推导箭头——后随中文 \text 则链式并入
                    // 同一标签（「双侧极限不存在 ⇒ 不可导」是一条推论，不该被「 · 」切开）；
                    // 后随其他（公式/结尾）则挂本标签尾（箭头指向后续内容）。
                    val (e2, trail) = stripLeadingArrows(latex, end)
                    if (trail.isNotEmpty()) {
                        val tm2 = textRe.find(latex, e2)
                        if (tm2 != null && tm2.range.first == e2 && tm2.groupValues[1].any(::isCjkChar)) {
                            appendNext(buf, trail + " " + keepFilter(tm2.groupValues[1]))
                            end = tm2.range.last + 1
                            advanced = true
                        } else if (tm2 != null && tm2.range.first == e2 &&
                            followedByCjkText(latex, tm2.range.last + 1)
                        ) {
                            // 2026-11-17：链上的 ASCII \text 组（如 `\text{MVT}`）也进链——
                            // 旧实现把它留在公式里，链被从中间撕开。
                            buf.append(' ').append(trail).append(' ').append(tm2.groupValues[1].trim())
                            end = tm2.range.last + 1
                            advanced = true
                        } else {
                            buf.append(' ').append(trail)
                            end = e2
                            advanced = true
                        }
                    }
                    if (!advanced) break
                }
                addLabel(buf.toString())
                out.append("\\,")
                i = end
                continue
            }
            out.append(tm.value)
            i = tm.range.last + 1
            continue
        }
        // ③ 普通字符：散落 CJK 丢弃（旧版语义），其余照抄
        val c = latex[i]
        if (isCjkChar(c)) {
            // 2026-11-14 二轮：散落 CJK 丢弃（既有拍板）时，紧贴的推导箭头一并吞掉——
            // 否则中文丢完两侧箭头悬空连排（截图「⇒ ⇒」的另一形态）
            val kept = stripTrailingArrows(out.toString())
            out.setLength(0)
            out.append(kept.first)
            while (i < latex.length && isCjkChar(latex[i])) i++
            i = stripLeadingArrows(latex, i).first
            continue
        }
        out.append(c)
        i++
    }
    return (if (isBlankLatex(out.toString())) "" else out.toString()) to labels.toString()
}

/**
 * [from] 处往后（跳过推导箭头与排版间距）是否**紧跟**一个含 CJK 的 `\text{…}` 组。
 *
 * 2026-11-17 用户「中文被挤出」：推导链 `\text{费马引理…} ⇒ \text{Rolle…} ⇒ \text{MVT}
 * ⇒ \text{定理 B}` 里，链中间的 `\text{MVT}` 是 ASCII——旧实现把它留在公式里、两侧中文
 * 抽到公式下方，链被从中间撕开。判据：ASCII 组后面紧跟（经箭头相连）CJK 组 → 它也是
 * 链的一节。纯函数，JVM 可测。
 */
internal fun followedByCjkText(latex: String, from: Int): Boolean {
    val (pos, trail) = stripLeadingArrows(latex, from)
    if (trail.isEmpty()) return false           // 没有箭头相连 → 不是链成员
    val tm = Regex("\\\\text\\{([^{}]*)\\}").find(latex, pos) ?: return false
    return tm.range.first == pos && tm.groupValues[1].any(::isCjkChar)
}

/** 数学部分只剩排版间距（`\,` `\quad` …）→ 视作空（整块都是标签链） */
internal fun isBlankLatex(s: String): Boolean =
    s.replace(Regex("""\\(,|;|:|!|quad|qquad)"""), "").trim().isEmpty()

/**
 * 「标注被抽走后只剩空壳的上下标组」——`a_{kk}^{(\text{主元})}` 剥完是 `a_{kk}^{(\,)}`，
 * `x^{\text{主元}}` 剥完是 `x^{\,}`。
 *
 * 2026-11-17 用户（第三轮，图 1）：这种块级公式的中文被抽成公式下方孤零零一行、
 * 公式里留一对空括号 `^{()}`，用户拍板「中文回到公式行内」。
 *
 * **排除** `\underbrace{…}_{\text{可导}}` / `\overbrace{…}^{\text{…}}`：那种空壳是
 * 花括号的结构位（剥完是 `_{\,}`），标签本来就该挂在花括号下方连读（909b74f5 拍板、
 * `CjkFormulaBatteryTest` 钉住），不能被这条误伤。
 */
private val EMPTY_SCRIPT_RE = Regex("""[\^_]\{(?:[\\,;:!\s]*|\([\\,;:!\s]*\))\}""")

internal fun hasStrippedAnnotationScript(latex: String): Boolean {
    for (m in EMPTY_SCRIPT_RE.findAll(latex)) {
        if (!precededByBraceCommand(latex, m.range.first)) return true
    }
    return false
}

/** 位置 [p]（`^` 或 `_`）前面是不是 `\underbrace{…}` / `\overbrace{…}` 的收尾大括号 */
private fun precededByBraceCommand(latex: String, p: Int): Boolean {
    var i = p - 1
    while (i >= 0 && latex[i].isWhitespace()) i--
    if (i < 0 || latex[i] != '}') return false
    var depth = 0
    var j = i
    while (j >= 0) {
        when (latex[j]) {
            '}' -> depth++
            '{' -> {
                depth--
                if (depth == 0) break
            }
        }
        j--
    }
    if (j < 0) return false
    val head = latex.substring(0, j)
    return head.endsWith("\\underbrace") || head.endsWith("\\overbrace")
}

/** `\boxed{…}` 去掉外框（中文要回到公式行内时必须拆开，外框没法跟着走） */
private fun unwrapBoxed(latex: String): String {
    val t = latex.trim()
    if (!t.startsWith("\\boxed{")) return latex
    if (!t.endsWith("}")) return latex
    var depth = 0
    for (k in "\\boxed".length until t.length) {
        when (t[k]) {
            '{' -> depth++
            '}' -> {
                depth--
                if (depth == 0 && k != t.length - 1) return latex   // 最外层不是包住整串
            }
        }
    }
    if (depth != 0) return latex
    return t.substring("\\boxed".length + 1, t.length - 1)
}

/**
 * 展示块整块就是一个**对齐环境**时，按行拆成「独立公式 + 该行标注」（2026-11-18 真机：
 * ◆ 消息 7719，用户「又出现乱码」）。
 *
 * 原文：`$$\begin{aligned} … \\ … &&\text{左边：链式…} \\ … \end{aligned}$$`。
 * jlatexmath 画不了整块的结构（`&&` 双列分隔 + 行内 `\text{CJK}`），整块解析失败 →
 * 回退裸文本，用户看到的就是满屏 `\begin{aligned}` 和 `&`。
 *
 * 拆行后每行都是一条**能独立渲的公式**：`&`（对齐点）去掉，`&&` 之后是这一行的注解，
 * `\text{}` 连壳解成普通文字挂在公式下方（沿用既有「块级公式标签」形态）。
 * 只有注解、没有公式的行（`&&&\text{中间：常数倍直接提}`）返回空公式 + 注解。
 *
 * 返回 null = 不是「整块一个对齐环境」的形态（调用方保持原路径）。
 */
internal fun splitAlignedRows(content: String): List<Pair<String, String>>? {
    val t = content.trim()
    // 只在**已知渲不动**的形态上接管：`&&`（多列+行注解）或 `\text{}`（jlatexmath 画不了
    // CJK）。简单的 `a&=1\\b&=2` 一直能正常渲染，别动它（MarkdownMathTest 钉着）。
    if (!t.contains("&&") && !t.contains("\\text{")) return null
    val head = Regex("""^\\begin\{((?:aligned|align|alignedat|alignat|gathered|gather|split)\*?)\}""")
        .find(t) ?: return null
    val env = head.groupValues[1]
    val endTag = "\\end{$env}"
    val endIdx = t.lastIndexOf(endTag)
    if (endIdx < 0) return null
    // 对齐环境必须占满整块（收尾后只允许空白），否则是别的结构的一部分
    if (t.substring(endIdx + endTag.length).isNotBlank()) return null
    var inner = t.substring(head.range.last + 1, endIdx)
    // alignedat/alignat 带列数参数（{2}）
    if (env.startsWith("alignat")) inner = inner.replaceFirst(Regex("""^\s*\{\d+\}"""), "")
    val rows = inner.split("\\\\")
    if (rows.size < 2) return null
    val out = mutableListOf<Pair<String, String>>()
    for (raw in rows) {
        // `\\[4pt]` 的间距参数会落在下一行开头
        var row = raw.replaceFirst(Regex("""^\s*\[[^\]]{0,12}\]"""), "").trim()
        if (row.isEmpty()) continue
        val annIdx = row.indexOf("&&")
        val formula = (if (annIdx >= 0) row.substring(0, annIdx) else row)
            .replace("&", "").trim()
        val annotation = if (annIdx >= 0)
            unwrapRowAnnotation(row.substring(annIdx + 2).trimStart('&', ' ')) else ""
        if (formula.isEmpty() && annotation.isEmpty()) continue
        out += formula to annotation
    }
    return out.ifEmpty { null }
}

/** 拆行注解里的 `\text{…}` 连壳解成普通文字（jlatexmath 画不了 CJK，注解走文字层） */
private fun unwrapRowAnnotation(s: String): String {
    val textRe = Regex("""\\text\{([^{}]*)\}""")
    var t = s
    while (true) {
        val n = textRe.replace(t) { it.groupValues[1] }
        if (n == t) break
        t = n
    }
    return t
        .replace("\\,", " ").replace("\\;", " ").replace("\\ ", " ")
        .replace("\\%", "%").replace("\\_", "_")
        .replace("\\{", "{").replace("\\}", "}")
        .replace(Regex("\\s+"), " ")
        .trim()
}

/**
 * 块级公式的**中文行内混排**（2026-11-17 用户拍板）：把 `\text{CJK}` 抽成正文、其余留在
 * 数学段，并做**括号再平衡**，保证每段都是可渲的合法 latex：
 *
 * `\boxed{\det A=(-1)^{s}\prod_{k=1}^{n}a_{kk}^{(\text{主元})}}`
 * → [数学 `\det A=(-1)^{s}\prod_{k=1}^{n}a_{kk}`] [正文 `(主元)`]
 *
 * 渲染成一行「det A=(−1)ˢ ∏ a_{kk} (主元)」——中文回到公式行内，不再被甩到下方一行。
 * 返回 null = 没有可抽的中文（调用方保持原行为）。
 */
internal fun splitDisplayCjkInline(latex: String): List<Pair<String, Boolean>>? {
    val core = unwrapBoxed(latex)
    val raw = splitInlineCjk(core).toMutableList()
    if (raw.none { !it.second && it.first.isNotBlank() }) return null
    val out = mutableListOf<Pair<String, Boolean>>()
    var i = 0
    while (i < raw.size) {
        var txt = raw[i].first
        val isMath = raw[i].second
        if (!isMath) {
            if (txt.isNotBlank()) out += txt to false
            i++
            continue
        }
        // 数学段尾部的「被掏空的上下标开壳」：把括号搬到紧随其后的中文里去
        val openParen = Regex("""[\^_]\{\s*\(\s*$""").find(txt)
        val openPlain = Regex("""[\^_]\{\s*$""").find(txt)
        val opener = openParen ?: openPlain
        if (opener != null && i + 1 < raw.size && !raw[i + 1].second) {
            val withParen = openParen != null
            txt = txt.substring(0, opener.range.first)
            var prose = raw[i + 1].first.trim()
            if (withParen) prose = "(" + prose
            if (i + 2 < raw.size && raw[i + 2].second) {
                val closer = if (withParen) Regex("""^\s*\)\s*\}\s*""") else Regex("""^\s*\}\s*""")
                val m2 = closer.find(raw[i + 2].first)
                if (m2 != null) {
                    if (withParen) prose += ")"
                    raw[i + 2] = raw[i + 2].first.substring(m2.range.last + 1) to true
                }
            }
            if (txt.isNotBlank()) out += txt to true
            if (prose.isNotBlank()) out += prose to false
            i += 2
            continue
        }
        if (txt.isNotBlank()) out += txt to true
        i++
    }
    if (out.none { !it.second }) return null
    return out
}

/**
 * jlatexmath-android 0.2.0 的内置宏表在真机上缺 `\operatorname`——线性代数会话里
 * `$\operatorname{Col}A$` 等整段抛异常回退裸文本（同屏 `$\mathbf b$` 都正常，2026-11-02
 * 实锤）。语义上 `\operatorname{X}` 对这些用法（rank/Nul/Col/nullity/span）与
 * `\mathrm{X}` 等价（直立罗马体、无上下限差异），切段时统一改写。
 * 嵌套大括号的参数（`\operatorname{arg{..}}`）不在此处理，交给 jlatexmath 原生报错回退。
 */
private fun normalizeLatex(s: String, display: Boolean = false): String {
    val withOps = s.replace(Regex("\\\\operatorname\\*?\\{([^{}]*)\\}")) { m -> "\\mathrm{${m.groupValues[1]}}" }
    // 2026-11-17 用户截图（消息 7628 引用块）：`$\big]$` 整段渲染失败、回退成源码裸显，
    // 真机看到的是「\times\big[结论里的式子]」——jlatexmath-android 0.2.0 的宏表里
    // **没有 `\big`/`\Big`/`\bigg`/`\Bigg`**（含 `l`/`r`/`m` 变体）。这类命令只是把括号
    // 放大一号，纯排版微调；剥掉后 `[ ]` 语义完全一致，整条公式就能正常渲。
    val withSizes = withOps.replace(Regex("""\\(?:bigg|Bigg|big|Big)[lrm]?"""), "")
    // 2026-11-02 用户「矩阵要用[]」：pmatrix（圆括号）统一改 bmatrix（方括号），
    // 与国内线代教材的记号一致；jlatexmath 两种环境都支持
    var out = withSizes
        .replace("\\begin{pmatrix*}", "\\begin{bmatrix*}")
        .replace("\\end{pmatrix*}", "\\end{bmatrix*}")
        .replace("\\begin{pmatrix}", "\\begin{bmatrix}")
        .replace("\\end{pmatrix}", "\\end{bmatrix}")

    // 2026-11-12 用户截图（积分表第三行 `\ln\lvert x\rvert+C` 整格裸显、同行其余公式全正常）：
    // amsmath 的 \lvert/\rvert/\lVert/\rVert 在 jlatexmath-android 0.2.0 **没有定义**
    // （AAR 的 TeXSymbols.xml 只登记了 \vert/\Vert/\mid；jar 全文无 lvert/rvert——
    // 早先 grep 到的 "lvert" 其实是 \lvertneqq，不相干）→ 整格解析失败、回退原文裸显。
    // 语义完全等价（\lvert=\rvert=\vert=|，\lVert=\rVert=\Vert=‖），进解析器前统一改写；
    // 负向断言防误伤 \lvertneqq 这类以同前缀开头的真命令。
    out = Regex("\\\\[lr](Vert|vert)(?![A-Za-z])").replace(out) { m -> "\\" + m.groupValues[1] }

    // 2026-11-18 真机（◆ 消息 7721，用户「又出现乱码」）：模型按《数学分析》中文
    // 教材惯例写双曲简写 `\sh`/`\ch`/`\th`（=sinh/cosh/tanh）与反双曲 `\arsh`/`\arch`/`\arth`
    // （=arsinh/arcosh/artanh），外加 `\tfrac`——jlatexmath-android 0.2.0 宏表里**都没有**
    // → 整块/整格解析失败回退裸文本（表格里整列 `\sh x=\frac{…}` 原样可见）。
    // 语义逐个等价改写；`\sharp` 这类同前缀真命令用负向断言保护。
    val hyper = mapOf("sh" to "sinh", "ch" to "cosh", "th" to "tanh")
    out = Regex("""\\ar(sh|ch|th)(?![A-Za-z])""").replace(out) { m ->
        "\\mathrm{ar" + hyper.getValue(m.groupValues[1]) + "}"
    }
    out = Regex("""\\(sh|ch|th)(?![A-Za-z])""").replace(out) { m ->
        "\\" + hyper.getValue(m.groupValues[1])
    }
    out = Regex("""\\tfrac(?![A-Za-z])""").replace(out) { "\\frac " }

    // 2026-11-11 夜撞（真机消息 5572 实锤）：mathtools 的 \xRightarrow/\xrightarrow/\xleftarrow
    // jlatexmath 不支持——`$$…\xRightarrow{\text{平方}}…$$` 整块解析失败回退裸文本，
    // `\text{平方}` 被剥标签后剩 `\xRightarrow{}` 原样可见、"平方"成孤儿标签行。
    // 语义=带标注的箭头 → \overset{标注}{箭头}（jlatexmath 支持 overset；CJK 标注已被
    // 上游剥标签，落到这里的只剩 ASCII/空）。空标注退化为纯箭头。允许标注含一层嵌套
    // 大括号（\xrightarrow{\text{step}}）。必须在 gathered 合并**之前**做，续行判定才认得。
    out = Regex("\\\\x(Rightarrow|rightarrow|Leftarrow|leftarrow)\\{((?:[^{}]|\\{[^{}]*\\})*)\\}")
        .replace(out) { m ->
            // 2026-11-24 真机（◆ 消息 8089，用户截图「}→」）：
            // 原实现只 replace("\\,","")——`\xrightarrow{\ \text{切}\ }` 剥掉 \text 后
            // 标签剩 `\ \,`，清掉 `\,` 留下**结尾悬空反斜杠**，拼进 `\overset{\ \}{…}`
            // 时 `\` 紧贴 `}` 被 jlatexmath 读成**转义右花括号 `\}`** → 屏上渲成 `}→`，
            // 每个箭头前面多一个 `}`。改为把整串间距命令（`\,`/`\;`/`\:`/`\!`/`\ `）
            // 归一成空格、再掐掉结尾残留反斜杠；清空则退化为纯箭头。
            val label = sanitizeArrowLabel(m.groupValues[2])
            val arrow = "\\" + m.groupValues[1]
            if (label.isEmpty()) arrow else "\\overset{$label}{$arrow}"
        }

    // 2026-11-11 用户截图：模型写 `…\quadA=1`（间距命令后紧贴字母，TeX 会读成
    // 未知命令 `\quadA`）→ 整块解析失败回退裸文本。修：空白类命令后紧跟字母时补空格。
    out = Regex("""\\(?:quad|qquad)(?=[A-Za-z])""").replace(out) { it.value + " " }

    // 2026-11-10 真机：`\\[4pt]` 是「换行+4pt 间距」。jlatexmath-android 不支持这个可选参数，
    // 整块解析失败 → 回退裸文本，用户看到的就是「公式压成一行 + ［4pt］被画出来」。
    // 先把「换行+间距」还原成普通换行，再把单独的间距命令删掉。
    val spacing = "\\[\\s*[0-9.]{1,6}\\s*(?:pt|em|ex|mm|cm|px)?\\s*\\]"
    // String.replace(Regex, …) 走 Matcher 语义：替换串里的反斜杠会被再解释一层，
    // 想得到 LaTeX 的换行 `\\` 必须写成 "\\\\\\\\"（否则只剩一个 `\`，jlatexmath 直接报错）。
    out = Regex(Regex.escape("\\\\") + spacing).replace(out, "\\\\\\\\")
    out = Regex(Regex.escape("\\") + spacing).replace(out, "")

    // 2026-11-10：块级公式里的裸换行 → gathered 多行排版（jlatexmath 对 gather/aligned
    // 系列环境支持良好）。已有 \begin{} 环境的不再包一层，避免双重换行/报错。
    // 2026-11-10 二轮（用户截图「一个长等式被强制换行了」）：模型常把**一条**长等式的
    // 源码按排版习惯写成多行（续行以 = / \overset / \xrightarrow 开头、或上一行括号没闭合），
    // 一律按行硬分会把一条等式拆成 N 行叠罗汉。现在先做续行合并：只有「上一行表达式完整
    // 且下一行不以关系符开头」的换行才算真分行。
    if (display && out.contains('\n') && !out.contains("\\begin{")) {
        val merged = mergeDisplayContinuationLines(out)
        val lines = merged.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.size > 1) {
            out = "\\begin{gathered}" + lines.joinToString(" \\\\ ") + "\\end{gathered}"
        } else if (lines.size == 1) {
            out = lines[0]
        }
    }
    return out
}

/**
 * 箭头标注清洗（2026-11-24，真机消息 8089）：`\xrightarrow{\ \text{切}\ }` 的标注被
 * stripCjkLabels 剥成 `\ \,` 后，残留的**结尾悬空反斜杠**会与 `}` 组成转义右括号 `\}`，
 * 渲染成 `}→`。这里把间距命令（`\,`/`\;`/`\:`/`\!`/`\ `）归一成空格、掐掉结尾反斜杠，
 * 全空则返回空串（调用方退化为纯箭头）。纯函数，JVM 可测。
 */
internal fun sanitizeArrowLabel(raw: String): String {
    var s = raw
    s = Regex("""\\[,;:!]""").replace(s, " ")   // \, \; \: \!
    s = s.replace("\\ ", " ")                     // 反斜杠+空格（TeX 控制空格）
    s = Regex("""\s+""").replace(s, " ").trim()
    s = Regex("""\\+$""").replace(s, "")          // 结尾悬空反斜杠（会被吃成 \}）
    return s.trim()
}

/**
 * 整块是否**恰好是一个 LaTeX 多行环境**（`\begin{env}…\end{env}`，如 cases/array/matrix）。
 *
 * 2026-11-24 真机（◆ 消息 8097，用户截图三种取法）：`\begin{cases}` 里带
 * `\text{CJK}` 时，旧的「块级按行拆散文」把它拆成 n 段——`\begin{cases}` 单独一段、
 * 每行 `f(x_{i-1}) & …` 各一段、`\end{cases}` 又一段，jlatexmath 每段都解析失败 →
 * 整屏裸 LaTeX。jlatexmath 原生支持 cases（探针实测），正确做法是**整块不拆**：
 * 只剥 CJK 标签、保留环境结构整体渲染。纯函数，JVM 可测。
 */
internal fun isSingleMathEnv(s: String): Boolean {
    val t = s.trim()
    // 允许「纯数学前缀」（y= / f(\bar x_i)= 这类），但整块必须以同一环境的 \end 收尾，
    // 且该环境在块内只开一次（不含嵌套同环境）。多行环境一旦被按行拆散必然整块解析失败。
    val begins = Regex("""\\begin\{([^{}]+)\}""").findAll(t).toList()
    if (begins.isEmpty()) return false
    val env = begins.last().groupValues[1]
    if (!t.endsWith("\\end{$env}")) return false
    if (t.split("\\begin{$env}").size != 2) return false
    return true
}

/** 花括号配平（跳过转义符）；≠0 说明表达式写到一半，下一行必然是续行 */
private fun braceBalance(s: String): Int {
    var d = 0
    var i = 0
    while (i < s.length) {
        when (s[i]) {
            '\\' -> i++ // 跳过转义（\{ \} 不计）
            '{' -> d++
            '}' -> d--
        }
        i++
    }
    return d
}

/** 行尾以关系/箭头命令收尾（\xrightarrow{…}、\overset{…}{=}），下一行是它的右操作数 */
private fun endsWithRelationCmd(line: String): Boolean =
    line.contains(Regex("\\\\(xrightarrow|xleftarrow|overset|stackrel|underbrace|overbrace)")) &&
        braceBalance(line) == 0 && line.endsWith("}")

/** 下一行以关系符/运算符/对齐符开头 → 是上一行的续行 */
private val startsWithRelation = Regex(
    "^(&|=|\\+|-|\\\\(overset|stackrel|xrightarrow|xleftarrow|cdot|times|ne|neq|approx|equiv" +
        "|to|rightarrow|leftarrow|Rightarrow|implies|iff|le|ge|pm|mp)\\b|\\{)"
)

/**
 * 把「一条等式被源码换行拆开」的续行合并回一行：
 * ① 上一行以 \\\\ 结尾 = 显式换行，不合并；
 * ② 上一行括号不闭合 / 行尾是运算符 / 行尾是关系命令，或下一行以关系符开头 → 合并；
 * ③ 其余换行视为真的分行（多行方程组），保留为 gathered 的行。
 */
private fun mergeDisplayContinuationLines(src: String): String {
    val lines = src.lines().map { it.trim() }.filter { it.isNotEmpty() }
    if (lines.size <= 1) return src
    val rows = mutableListOf<String>()
    var cur = lines[0]
    for (k in 1 until lines.size) {
        val nxt = lines[k]
        val explicitBreak = cur.endsWith("\\\\")
        val join = !explicitBreak && (
            nxt.startsWith("&") ||
                braceBalance(cur) != 0 ||
                cur.endsWith("=") || cur.endsWith("+") || cur.endsWith("-") ||
                cur.endsWith(",") || cur.endsWith("(") ||
                endsWithRelationCmd(cur) ||
                startsWithRelation.containsMatchIn(nxt)
            )
        if (join) {
            cur = (cur + " " + nxt.removePrefix("&")).trim()
        } else {
            rows += cur
            cur = nxt
        }
    }
    rows += cur
    return rows.joinToString("\n")
}

/**
 * 解析失败后的**一次修复重试**（2026-11-13 用户七截图，模拟器 LatexParseProbeTest 实探）：
 * ① `\tag{n}` 在 0.2.0 三张注册表里都没有（实测 ParseException）——转成行尾 `\qquad(n)`
 *    （编号语义保留），`\notag`/`\nonumber` 直接删；
 * ② 命令粘连：模型常把命令与后词连写（`A\iffI` 实测 ParseException——TeX 最大吞并把
 *    `iffI` 读成未知命令）。把每个 `\`+字母串切成「最长已知前缀 + 空格 + 余下字面」，
 *    已知表在 [JLATEX_COMMANDS]（AAR 注册表并集）。整词已知 / 单字母（无 ≥2 前缀）不动。
 *
 * **只在 jlatexmath 解析失败后调用**（[LatexRender.bitmap] 重试路径）——能解析的公式
 * 一个字符都不会被改写；修复后仍失败则照旧回退裸文本。
 */
internal fun repairTex(latex: String): String? {
    var out = latex
    // ① \tag{…} → \qquad(…)；\notag/\nonumber 删除（后面紧跟字母时不动，交给②）
    out = Regex("""\\tag\{([^{}]*)\}""").replace(out) { "\\qquad(${it.groupValues[1]})" }
    out = Regex("""\\(?:notag|nonumber)(?![A-Za-z])""").replace(out, "")
    // ② 粘连拆分
    val sb = StringBuilder(out.length + 8)
    var i = 0
    while (i < out.length) {
        val c = out[i]
        if (c == '\\' && i + 1 < out.length && out[i + 1].isLetter()) {
            var j = i + 1
            while (j < out.length && out[j].isLetter()) j++
            val name = out.substring(i + 1, j)
            if (name in JLATEX_COMMANDS) {
                sb.append('\\').append(name)
                i = j
                continue
            }
            var cut = 0
            for (k in (name.length - 1) downTo 2) {   // ≥2：单字母前缀（\b 这类）不切
                if (name.substring(0, k) in JLATEX_COMMANDS) { cut = k; break }
            }
            if (cut > 0) {
                sb.append('\\').append(name, 0, cut).append(' ').append(name, cut, name.length)
                i = j
                continue
            }
        }
        sb.append(c)
        i++
    }
    val fixed = sb.toString()
    return if (fixed != latex) fixed else null
}

/** LaTeX → Bitmap 渲染缓存（渲染方决定线程；个人规模公式毫秒级；失败返回 null 由调用方回退原文） */
object LatexRender {
    // 2026-11-22 用户截图「underbrace 标注飞出公式」根治第一块拼图：jlatexmath 自带字体
    // 不含 CJK → 公式里的中文渲染成空字形，整类「中文进公式」只能靠「剥出来下挂」绕。
    // TeXFormula.registerExternalFont(UnicodeBlock, 字体名) 是上游官方扩展点（希腊/
    // 西里尔同路），Android 端 "sans-serif" 经 Typeface.create 解析、覆盖 CJK。
    // 进程级注册一次；失败静默——注册不上就维持「剥标签」老路，不至于更坏。
    val cjkFontRegistered: Boolean = runCatching {
        listOf(
            Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS,
            Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A,
            Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS,
            Character.UnicodeBlock.CJK_SYMBOLS_AND_PUNCTUATION,
            Character.UnicodeBlock.HALFWIDTH_AND_FULLWIDTH_FORMS,
            Character.UnicodeBlock.GENERAL_PUNCTUATION
        ).forEach { TeXFormula.registerExternalFont(it, "sans-serif") }
        true
    }.getOrDefault(false)

    // 2026-11-11 掉帧修：96→256。公式重的长会话来回翻动时 96 容易整轮逐出——
    // 逐出后回场要在 Default 线程重渲染并先裸显 LaTeX 源码（可感知的闪烁+CPU 抖动）。
    // 2026-11-14 快速滚动二段修：**按字节限量**（32MB）——行内小图几 KB、块级大图
    // 数百 KB，256 条按条数算最坏可堆到 100MB+（位图在 native 堆，直接顶内存压力
    // 触发 kswapd/LMK，是「上下滑动还是卡」的隐性来源之一）。典型重会话 ~10-15MB，
    // 32MB 内全程保温；真堆到上限才逐出（方向正确：宁可重渲，不可顶爆内存）。
    private val cache = object : LruCache<String, Bitmap>(32 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount
    }

    private fun key(latex: String, textSizePx: Float, colorArgb: Int) =
        colorArgb.toString() + "|" + textSizePx.toRawBits() + "|" + latex

    /**
     * **磁盘缓存**（2026-11-21 用户「公式为什么还要反复渲染，我又不会自己修改」）。
     *
     * 原先渲染结果只活在 32MB 内存 LRU 里：一条公式重的会话几十上百张位图，滚过一大段
     * 就会被逐出，回头再看要重新 jlatexmath 解析 + 描一遍（后台单队列，几 ms 到几十 ms
     * 一张），而且**杀进程重进全部重来**。内容和颜色不变就该只算一次。
     *
     * 这里把渲好的位图按 `SHA-1(色|字号|latex)` 落 PNG 到 `cacheDir/formula/`：
     * · 只在渲染线程读/写（组合期的 [peek] 永远不碰磁盘，避免主线程解码）；
     * · 命中解码后回填内存 LRU，之后又是纯内存路径；
     * · 按最后访问时间淘汰，锁 192MB / 4000 张（cacheDir 本身也受系统清理管辖）。
     */
    private var diskDir: java.io.File? = null
    private var saveCount = 0

    /**
     * 磁盘上**已有**的键（文件名集合）。用途：判断「这张图要不要真渲」。
     *
     * 2026-11-21 用户「为什么还是滑动中不渲染、停手才卡一下」：上一版把**所有**未命中
     * 内存缓存的请求都推迟到滚停，于是滚动全程是裸 LaTeX 文字，松手瞬间十几条一起排队
     * 渲 + 十来次整段文本重排 —— 就是那一下「卡」。磁盘里已有的公式只要解码（1~2ms），
     * 滚动中给出来完全没问题，不该跟「真渲」同等待遇。这里是纯内存集合查询，
     * 组合期用它判断不用碰文件系统。
     */
    private val diskKeys = java.util.Collections.synchronizedSet(HashSet<String>())

    /** 这张图磁盘里已经有了吗（纯内存查询，可在组合期调用）。 */
    fun hasDisk(latex: String, textSizePx: Float, colorArgb: Int): Boolean =
        diskKeys.contains(diskName(key(latex, textSizePx, colorArgb)))

    /**
     * **位图尺寸索引**（2026-11-21 用户「有些公式都渲染好了，停手时突然又变化，如同压缩毛巾」）。
     *
     * 病根：组合期只能查内存 LRU（[peek]），冷进场时内存里没有 → 行高与占位按**估算**排，
     * 位图到货后再按真实宽高重排一次 = 那一下「压缩毛巾泡开」。内存 LRU 装不下整条会话
     * （4000 张位图远超 32MB），所以每次进场都会重演。
     *
     * 这里把「渲好时就知道的宽高」旁路存一份（key 的 SHA-1 → w,h）：
     * · 渲染落盘时写 `index.txt`（一行 `hex w h`）；
     * · 启动时后台读回内存；
     * · 组合期 [peekSize] 纯内存查，拿到真实宽高就能**一次排对**，不必等位图。
     */
    private val sizes = java.util.concurrent.ConcurrentHashMap<String, IntArray>()
    @Volatile
    private var indexFile: java.io.File? = null

    /** 组合期用：这张公式的位图真实像素宽高（内存索引；未渲过返回 null）。 */
    fun peekSize(latex: String, textSizePx: Float, colorArgb: Int): IntArray? =
        sizes[diskName(key(latex, textSizePx, colorArgb))]

    private fun recordSize(k: String, w: Int, h: Int) {
        if (w <= 0 || h <= 0) return
        val name = diskName(k)
        if (sizes.put(name, intArrayOf(w, h)) == null) {
            val f = indexFile
            if (f != null) {
                runCatching {
                    synchronized(sizes) {
                        java.io.FileOutputStream(f, true).use { out ->
                            out.write("$name $w $h\n".toByteArray())
                        }
                    }
                }
            }
        }
    }

    // 2026-11-21 实测：一个线代会话全量预热就 4059 张 / 49MB（平均 12KB/张）。
    // 所以上限按「字节」为主（384MB），条数只用来兜住 inode/目录扫描成本。
    private const val DISK_MAX_BYTES = 384L * 1024 * 1024
    private const val DISK_MAX_FILES = 20000
    private const val DISK_PRUNE_EVERY = 64

    /** Application 启动时注入（`cacheDir`）；没注入就退化成纯内存缓存。 */
    fun initDisk(cacheDir: java.io.File) {
        runCatching {
            val d = java.io.File(cacheDir, "formula")
            d.mkdirs()
            diskDir = d
            indexFile = java.io.File(d, "index.txt")
            // 启动时把已有文件名读进集合（一次 listFiles，之后全是内存查询）
            d.listFiles { f -> f.isFile && f.name.endsWith(".png") }?.forEach { f ->
                diskKeys.add(f.name.substringBeforeLast('.'))
            }
            // 尺寸索引（后台）：先读 index.txt，再对没有索引条的 PNG 补读文件头宽高。
            // 只有读文件头（inJustDecodeBounds），不解码整张图。
            Thread {
                runCatching {
                    indexFile?.takeIf { it.isFile }?.forEachLine { line ->
                        val p = line.split(' ')
                        if (p.size == 3) {
                            val w = p[1].toIntOrNull() ?: return@forEachLine
                            val h = p[2].toIntOrNull() ?: return@forEachLine
                            if (w > 0 && h > 0) sizes[p[0]] = intArrayOf(w, h)
                        }
                    }
                    val files = d.listFiles { f -> f.isFile && f.name.endsWith(".png") } ?: return@runCatching
                    val missing = files.filter { sizes[it.name.substringBeforeLast('.')] == null }
                    if (missing.isNotEmpty()) {
                        val opt = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        val sb = StringBuilder(missing.size * 32)
                        for (f in missing) {
                            android.graphics.BitmapFactory.decodeFile(f.absolutePath, opt)
                            if (opt.outWidth > 0 && opt.outHeight > 0) {
                                val name = f.name.substringBeforeLast('.')
                                sizes[name] = intArrayOf(opt.outWidth, opt.outHeight)
                                sb.append(name).append(' ').append(opt.outWidth)
                                    .append(' ').append(opt.outHeight).append('\n')
                            }
                            opt.outWidth = 0
                            opt.outHeight = 0
                        }
                        indexFile?.let { idx ->
                            java.io.FileOutputStream(idx, true).use { it.write(sb.toString().toByteArray()) }
                        }
                    }
                }
            }.apply { isDaemon = true }.start()
        }
    }

    /** 文件名（纯函数，单测钉死稳定性）：SHA-1(`色|字号位模式|latex`) → hex。 */
    internal fun diskName(key: String): String {
        val md = java.security.MessageDigest.getInstance("SHA-1")
        val bytes = md.digest(key.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            if (v < 0x10) sb.append('0')
            sb.append(Integer.toHexString(v))
        }
        return sb.toString()
    }

    private fun diskFile(k: String): java.io.File? {
        val d = diskDir ?: return null
        return java.io.File(d, diskName(k) + ".png")
    }

    private fun loadDisk(k: String): Bitmap? {
        val f = diskFile(k) ?: return null
        if (!f.isFile) return null
        return runCatching {
            // 刷新访问时间：淘汰按「最近使用」而不是「最早生成」
            f.setLastModified(System.currentTimeMillis())
            android.graphics.BitmapFactory.decodeFile(f.absolutePath)
        }.getOrNull()
    }

    private fun saveDisk(k: String, bmp: Bitmap) {
        val f = diskFile(k) ?: return
        runCatching {
            val tmp = java.io.File(f.absolutePath + ".tmp")
            java.io.FileOutputStream(tmp).use { out ->
                bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
            }
            if (!tmp.renameTo(f)) {
                tmp.copyTo(f, overwrite = true)
                tmp.delete()
            }
            diskKeys.add(f.name.substringBeforeLast('.'))
            if (++saveCount % DISK_PRUNE_EVERY == 0) pruneDisk()
        }
    }

    /** 超出上限就按最后修改时间从旧到新删，直到回到上限内。 */
    private fun pruneDisk() {
        val d = diskDir ?: return
        runCatching {
            val files = d.listFiles { f -> f.isFile && f.name.endsWith(".png") } ?: return
            var total = files.sumOf { it.length() }
            var count = files.size
            if (total <= DISK_MAX_BYTES && count <= DISK_MAX_FILES) return
            files.sortBy { it.lastModified() }
            for (f in files) {
                if (total <= DISK_MAX_BYTES && count <= DISK_MAX_FILES) break
                val len = f.length()
                if (f.delete()) {
                    total -= len
                    count--
                    diskKeys.remove(f.name.substringBeforeLast('.'))
                }
            }
        }
    }

    /** 只查缓存不渲染：组合期先同步吃掉命中项，未命中的交给后台线程 */
    fun peek(latex: String, textSizePx: Float, colorArgb: Int): Bitmap? =
        cache.get(key(latex, textSizePx, colorArgb))

    private fun renderOnce(latex: String, textSizePx: Float, colorArgb: Int): Result<Bitmap> =
        runCatching {
            val d = JLatexMathDrawable.builder(latex)
                .textSize(textSizePx)
                .color(colorArgb)
                .fitCanvas(true)
                .build()
            val w = d.intrinsicWidth.coerceAtLeast(1)
            val h = d.intrinsicHeight.coerceAtLeast(1)
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            // 2026-11-12 用户「部分大数学公式渲染出来变空心描边」（\boxed{4} 的 4 空心）：
            // 字节码级根因——jlatexmath-android 0.2.0 的 AndroidGraphics2D 把 AWT 的
            // fill/draw 两族调用挤进**同一个 android.graphics.Paint**：FramedBox.draw
            // （\boxed/\fbox 路径）先 setStroke 画框（draw(rect) 内部置 Style.STROKE，
            // 恢复的只是 Stroke 对象不是 Style），随后 base.draw 的文字走 drawChars，
            // 而它**不设置 Style**→文字继承 STROKE，整个渲染成轮廓。上游纯 AWT 无此
            // 问题（drawString 与 draw 互相独立），是移植层缺陷。
            // 修法：换一个把文字绘制强制回 FILL 的 Canvas，不动二进制库。
            // 只覆盖 drawText(char[],…)——字节码实证 AndroidGraphics2D 的文字出口
            // 只有 Canvas.drawText([CIIFF Paint)（drawChars 直转）；其余重载桩类型
            // 各 SDK 不一，没实证的不猜。
            val canvas = object : Canvas(bmp) {
                override fun drawText(chars: CharArray, index: Int, count: Int, x: Float, y: Float, paint: Paint) {
                    paint.style = Paint.Style.FILL   // 库里没有合法的空心文字用例
                    super.drawText(chars, index, count, x, y, paint)
                }
            }
            d.setBounds(0, 0, w, h)
            d.draw(canvas)
            bmp
        }

    fun bitmap(latex: String, textSizePx: Float, colorArgb: Int): Bitmap? {
        val k = key(latex, textSizePx, colorArgb)
        cache.get(k)?.let { return it }
        // 磁盘命中：解码（渲染线程）后回填内存 LRU，之后回到纯内存路径
        loadDisk(k)?.let { bmp ->
            cache.put(k, bmp)
            recordSize(k, bmp.width, bmp.height)
            return bmp
        }
        val direct = renderOnce(latex, textSizePx, colorArgb)
        if (direct.isSuccess) {
            return direct.getOrNull()?.also {
                cache.put(k, it); recordSize(k, it.width, it.height); saveDisk(k, it)
            }
        }
        // 2026-11-13 七截图：解析失败不再直接躺平回退——先试 repairTex（\tag / 命令粘连）
        // 修复一次再渲；修复后仍失败才回退裸文本（原样，key 不变，下次直接走这条路）。
        val repaired = repairTex(latex)
        if (repaired != null) {
            val retry = renderOnce(repaired, textSizePx, colorArgb)
            if (retry.isSuccess) {
                android.util.Log.w("LatexRender", "修复后解析成功: $repaired")
                return retry.getOrNull()?.also {
                    cache.put(k, it); recordSize(k, it.width, it.height); saveDisk(k, it)
                }
            }
            retry.exceptionOrNull()?.let {
                android.util.Log.w("LatexRender", "修复后仍失败: $repaired", it)
            }
        }
        // 2026-11-02：线性代数会话整表公式回退裸文本，此处却静默吞异常——
        // 加日志拿真凶（哪条命令/哪条公式不被 jlatexmath-android 0.2.0 支持）
        android.util.Log.w("LatexRender", "jlatexmath 解析失败: $latex", direct.exceptionOrNull())
        return null
    }
}

/**
 * 渲染派发：**交互优先的双档单队列**（同一时刻只渲一条）。
 *
 * 2026-11-14 用户「多数学公式会话快速滚动卡顿」：未命中缓存原来直接丢
 * Dispatchers.Default **无界并发**——快速滑动一屏组合出十几条公式 = 十几个
 * jlatexmath 重解析同时抢满所有核，UI/渲染线程被挤压掉帧。收敛为单队列后仍有
 * 「首滑换高」问题（用户回访「还是卡」）：滑动到未渲染区域时 fallback→位图逐条
 * 到货，正文反复重排，视觉上内容在指下跳动。三段修引入 [warmUpFormulas]：
 * 会话打开即按 **background 档**预热全部公式，滑动全程 peek 命中。
 * 两档排队规则：
 *  · interactive = 可见 item 的渲染请求，永远排在 background 前面
 *    （kotlinx select 的 onReceive 按书写顺序尝试，天然优先）；
 *  · 同一时刻只渲一条，CPU 让给 UI 线程；
 *  · item 滑出组合 → produceState 协程取消 → 还在排队没开跑的任务在 worker
 *    侧检查 [RenderTask.result] 已取消直接跳过（白跑免了），正在跑的跑完即止。
 */
private class RenderTask(
    val latex: String,
    val sizePx: Float,
    val colorArgb: Int,
    /** 挂等待者；等待者取消（item 滑出）= 任务作废 */
    val result: kotlinx.coroutines.CompletableDeferred<Bitmap?> = kotlinx.coroutines.CompletableDeferred(),
)

private val interactiveRenderQ = kotlinx.coroutines.channels.Channel<RenderTask>(kotlinx.coroutines.channels.Channel.UNLIMITED)
private val backgroundRenderQ = kotlinx.coroutines.channels.Channel<RenderTask>(kotlinx.coroutines.channels.Channel.UNLIMITED)

private val renderWorker = kotlinx.coroutines.CoroutineScope(
    kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default.limitedParallelism(1)
).launch {
    while (true) {
        val task = kotlinx.coroutines.selects.select<RenderTask> {
            interactiveRenderQ.onReceive { it }   // 书写在前 = 优先
            backgroundRenderQ.onReceive { it }
        }
        if (!task.result.isActive) continue       // 等待者已取消：作废，不白跑
        val tt0 = android.os.SystemClock.elapsedRealtime()
        val bmp = runCatching { LatexRender.bitmap(task.latex, task.sizePx, task.colorArgb) }
            .onFailure { android.util.Log.w("LatexRender", "渲染任务异常: ${task.latex.take(40)}", it) }
            .getOrNull()
        // 2026-11-21 探针：渲染耗时上报（与滚动帧时间同轴），用于判断「80ms 卡帧」是否
        // 与公式渲染完成同帧——渲染在 Default 线程，但位图分配/GC 与首帧纹理上传会打到主线程。
        io.github.m0use001.planability.core.ScrollTrace.noteLatexRender(
            (android.os.SystemClock.elapsedRealtime() - tt0).toInt()
        )
        task.result.complete(bmp)
    }
}

/** 交互档渲染：可见 item 的公式（用户正盯着的地方，绝不能排在预热后面） */
private suspend fun renderInteractive(latex: String, sizePx: Float, colorArgb: Int): Bitmap? {
    LatexRender.peek(latex, sizePx, colorArgb)?.let { return it }
    // 2026-11-21 快速滑动「抽搐」第四修：**要真渲的请求才等滚停**。
    // 条目被甩过去随即 dispose，produceState 协程随之取消——这些渲染本来就是白干；
    // 白干还会在 Default 线程上反复分配位图（缓存边缘逐出抖动 → 堆压力）。
    //
    // 2026-11-21 补（用户「为什么还是滑动中不渲染、停手才卡一下」）：上一版把**磁盘里
    // 已有**的图也一起推迟了。滚动全程裸 LaTeX、松手瞬间十几条一起排队解码 + 十几次
    // 整段文本重排 = 那一下卡。磁盘命中只是解码（1~2ms），滚动中给出来毫无压力，
    // 所以只对「磁盘也没有、必须 jlatexmath 重算」的请求推迟。
    val onDisk = LatexRender.hasDisk(latex, sizePx, colorArgb)
    if (!onDisk && io.github.m0use001.planability.core.ScrollTrace.listScrolling) {
        io.github.m0use001.planability.core.ScrollTrace.noteLatexDeferred()
        io.github.m0use001.planability.core.ScrollTrace.awaitScrollIdle()
        // 等待期间可能已被预热渲好（background 档不会被推迟）
        LatexRender.peek(latex, sizePx, colorArgb)?.let { return it }
    }
    val task = RenderTask(latex, sizePx, colorArgb)
    interactiveRenderQ.send(task)
    return task.awaitResult()
}

private suspend fun RenderTask.awaitResult(): Bitmap? =
    try {
        result.await()
    } catch (e: kotlinx.coroutines.CancellationException) {
        // 等待者死了（item 滑出 / 预热被重启）→ 任务作废，worker 跳过不白跑
        result.cancel()
        throw e
    }

/**
 * 会话/笔记打开时的**后台预热**（2026-11-14 用户「上下滑动还是卡」三段修）：
 * 把内容里的全部公式按渲染时同款字号渲进缓存，之后滑动全程 peek 命中——
 * 首滑不再有 fallback→位图换高跳变，也不再跟 item 组合抢队列。
 * 排 background 档：用户当前看着的公式（interactive）永远先渲。
 * [fontPx] 必须与渲染调用点同源（正文 16sp.toPx()；行内 ×1.25、块级 ×1.5），
 * 否则 cache key（含字号位模式）对不上白渲。[colorArgb] 同理取 onSurface。
 * [maxFormulas] 封顶：2026-11-21 用户「把存量公式一起全渲染了」起**默认不限**
 * （原来 240 条，滚到后面的公式仍要现算）。磁盘里已有的直接跳过（`hasDisk`），
 * 所以重进会话时这一步几乎零成本，只有真正没见过的公式才会渲。
 */
suspend fun warmUpFormulas(
    markdowns: List<String>,
    fontPx: Float,
    colorArgb: Int,
    maxFormulas: Int = Int.MAX_VALUE,
    /** 容器宽（px）；>0 时顺带预热「宽公式的拆行变体 + 块级口径」——
     *  这两样是运行期一定会用到的（行内提升成块、块级拆行），预热漏了就等于把
     *  「压缩毛巾」留在那几张上。 */
    screenPx: Float = 0f,
) {
    var enqueued = 0

    suspend fun warmOne(latex: String, sizePx: Float) {
        if (LatexRender.peek(latex, sizePx, colorArgb) != null) return
        if (LatexRender.hasDisk(latex, sizePx, colorArgb)) return
        if (enqueued >= maxFormulas) return
        enqueued++
        // 2026-11-21 用户「我把所有已经产生的公式都渲染了」：**不再因滚动暂停预热**。
        // 队列本身是单线程且交互档优先，正在看的公式永远先渲；暂停的代价是「滚到的那一条
        // 恰好还没渲」——就是用户看到的裸 LaTeX。
        val task = RenderTask(latex, sizePx, colorArgb)
        backgroundRenderQ.send(task)
        task.awaitResult()
    }

    for (md in markdowns) {
        // splitMathSegments 本身也顺带焐热（模块级解析缓存的回填走这里）
        for (seg in splitMathSegments(md)) {
            if (seg !is MathSeg.Math) continue
            // 2026-11-16：行内口径改成 max(行字号, 正文字号)×1.25 后，这里的 bodyPx×1.25
            // 恰好等于表格/卡片（行字号更小）与正文（相等）的渲染字号——三类调用点键统一，
            // 预热不再白渲。块级仍是正文×1.5。
            val sizePx = if (seg.display) fontPx * 1.5f else mathRenderSizePx(fontPx, fontPx)
            warmOne(seg.latex, sizePx)
            // 2026-11-21「压缩毛巾」：宽公式运行期会被 gatherIfNeeded 拆行（行内提升成块，
            // 或块级 Scroll 档拆行），那些变体也得先渲好——否则它们到货时同样换高。
            if (screenPx > 0f && !seg.display) {
                val wh = LatexRender.peekSize(seg.latex, sizePx, colorArgb)
                    ?: LatexRender.peek(seg.latex, sizePx, colorArgb)
                        ?.let { intArrayOf(it.width, it.height) }
                val maxWem = screenPx / fontPx
                val natEm = wh?.let { it[0].toFloat() / fontPx }
                if (natEm != null && natEm > maxWem) {
                    val g = gatherIfNeeded(seg.latex, natEm, maxWem)
                    if (g != seg.latex) {
                        warmOne(g, sizePx)            // 行内口径（尚未提升时的占位）
                        warmOne(g, fontPx * 1.5f)     // 块级口径（提升成 LatexBlock 后）
                    }
                }
            }
        }
    }
}

/** pending（渲染中）与「渲完但失败」必须区分：pending 要按估算**预留高度**（流式贴底
 *  滚动不被 fallback→位图的换高顶得抽搐），失败只回退裸文本、不占位。 */
internal sealed interface LatexSlot {
    data object Pending : LatexSlot
    data class Done(val bmp: Bitmap?) : LatexSlot
}

@Composable
private fun latexSlot(latex: String, sizePx: Float): LatexSlot {
    val colorArgb = MaterialTheme.colorScheme.onSurface.toArgb()
    val cached = remember(latex, sizePx, colorArgb) { LatexRender.peek(latex, sizePx, colorArgb) }
    if (cached != null) return LatexSlot.Done(cached)
    val rendered by androidx.compose.runtime.produceState<LatexSlot>(LatexSlot.Pending, latex, sizePx, colorArgb) {
        value = LatexSlot.Done(renderInteractive(latex, sizePx, colorArgb))
    }
    return rendered
}

/**
 * 行内/块级通用渲染位图获取（文本色在组合期解析，避免 Canvas 主题坑）。
 *
 * 2026-10-27 用户（对话里生成 HTML 实例时"渲染 LaTeX 卡了很久"）：
 * 渲染原来在**主线程组合期同步**做，一条消息十来个公式就在一帧里全画完，
 * jlatexmath 首次还要初始化字体资源，整屏冻结数秒；授权弹窗等一切 UI 都跟着卡。
 * 改成：缓存命中同步返回（重组合零成本），未命中在后台单队列渲染、
 * 完成前回退原文（等宽 latex 文本），渲染完自动换成位图——主线程永不等公式。
 */
@Composable
private fun latexBitmap(latex: String, sizePx: Float): Bitmap? =
    when (val slot = latexSlot(latex, sizePx)) {
        is LatexSlot.Done -> slot.bmp
        LatexSlot.Pending -> null
    }

/**
 * 行内公式的"格子内容"（2026-11-21「压缩毛巾」）。
 *
 * 位图状态在**这里**读（InlineTextContent 的内容 lambda 是独立的重组作用域），
 * 于是位图到货只重组这一格，不会重建整段 AnnotatedString、也就不会整段文本重排。
 * 占位尺寸由调用方用 [LatexRender.peekSize] 的尺寸索引预先算好——首帧即真实大小。
 */
@Composable
private fun InlineLatexImage(latex: String, sizePx: Float) {
    val bmp = latexBitmap(latex, sizePx)
    if (bmp != null) {
        Image(bitmap = bmp.asImageBitmap(), contentDescription = null, modifier = Modifier.fillMaxSize())
    }
}

/**
 * 行内文本渲染：数学 $…$ 以 InlineTextContent 嵌入文本流，其余走 inlineMd（粗体/斜体/行内码/删除线）。
 *
 * v53.6：正文 `[[标题]]` 走 [LocalWikiLinks] 上下文画成可点胶囊（[WikiChip]）。
 * 上下文没提供（聊天页等）时**完全保持旧行为**——原样文本，不误把 `[[x]]` 变成可点目标。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MdInlineText(
    text: String,
    style: TextStyle = MaterialTheme.typography.bodyLarge,
    color: Color = Color.Unspecified,
    modifier: Modifier = Modifier,
    /** 表头等需要加粗的调用点用；null = 跟随 style */
    fontWeight: FontWeight? = null,
    /** 2026-11-13 表格实测开滑：数学内容**绘制宽**（em，相对本行字号）超过可用宽（maxWem）
     *  = 内容被挤压，上报自然宽给调用方（MdTable 据此开横滑并把列宽适配到内容）。
     *  null（正文/标题等一切旧调用点）= 零行为变化。 */
    onClamped: ((Float) -> Unit)? = null,
    /**
     * 可用宽（px）提示（2026-11-21「压缩毛巾」）。
     *
     * 拆行（gatherIfNeeded）决策依赖可用宽，而可用宽以前只能靠 `Modifier.layout` **布局后**
     * 回写——首帧只能按屏宽赌一把，赌错就要在第二帧翻一次（高度跳变）。
     * 表格这类**宽度已知**的调用点把格宽传进来，首帧就能判对；不传则退回屏宽。
     */
    widthHintPx: Float? = null,
    /**
     * 2026-11-24 表格紧凑模式（用户截图「超级空的表格」）：单元格里的公式**按本行字号**
     * 渲染（不再抬到正文 bodyLarge 口径）、**不做拆行提升**、占位/行高上限不乘字号放大
     * 系数。此前表格格里公式按正文口径放大 + 窄格触发 gathered（\begin{gathered}，上限
     * 6em）→ 行高被撑到正文的 4~6 倍、公式却缩在中间一小团。表格行要紧凑：字小一点、
     * 行矮下去；被挤压时既有 onClamped→列加宽/横滑机制仍兜底可读性。
     */
    compactMath: Boolean = false,
) {
    // v53.6 链接可走：有链接上下文且这行确实含 [[]] 时，按片段流式排（文本片段照旧走数学/富文本链路）
    val wiki = LocalWikiLinks.current
    if (wiki != null && text.contains("[[")) {
        val segs = remember(text) { splitWikiSegments(text) }
        if (segs.any { it is WikiSeg.Link }) {
            FlowRow(
                modifier = modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
                itemVerticalAlignment = Alignment.CenterVertically
            ) {
                segs.forEach { s ->
                    when (s) {
                        is WikiSeg.Txt ->
                            if (s.text.isNotBlank()) {
                                // 片段内可能还有 $公式$/粗体：递归一次即可收敛（片段已不含 [[]]）
                                MdInlineText(s.text, style, color, Modifier, fontWeight, onClamped)
                            }
                        is WikiSeg.Link -> WikiChip(s.label, s.target, s.anchor)
                    }
                }
            }
            return
        }
    }
    val segs = remember(text) { splitMathSegments(text) }
    val hasMath = segs.any { it is MathSeg.Math }
    // v50 全局换色：inlineMd 非 composable，链接色从这里（composable）传 scheme.primary 进去
    val linkColor = MaterialTheme.colorScheme.primary
    if (!hasMath) {
        Text(inlineMd(text, linkColor), style = style, color = color, modifier = modifier, fontWeight = fontWeight)
        return
    }
    val density = LocalDensity.current
    val fs: TextUnit = if (style.fontSize.isSpecified) style.fontSize else 16.sp
    val fontPx = with(density) { fs.toPx() }
    // 2026-11-16 用户截图「字体还是太小」（表格里三行 \lim 公式）：
    // 公式渲染字号 = **本行字号** × 1.25。而表格格（MdTable）与卡片（ChatComponents）
    // 的 style 是 bodySmall(12sp) → 同一道公式渲出来只有 15sp，比正文的 20sp 小一圈；
    // 公式本来就是这些格子的主要内容，不该跟着「辅助文字」的字号一起缩。
    // 改成基准**不低于正文**：行字号更小时按 bodyLarge 走，行字号更大时照旧跟随
    //（用户调大字号/标题里的公式仍然变大）。
    // 附带修好预热键：warmUpFormulas 按正文 ×1.25 建键，此前表格/卡片公式的键对不上，
    // 渲了也用不到（与 11-14「fontScale 对齐」是同一类白渲）。
    val bodyPx = with(density) {
        MaterialTheme.typography.bodyLarge.fontSize.takeOrElse { 16.sp }.toPx()
    }
    // 2026-11-24 compactMath：表格格按本行字号（12sp×1.25）渲公式，不再抬到正文口径。
    val mathSizePx = if (compactMath) fontPx * 1.25f else mathRenderSizePx(fontPx, bodyPx)

    val contentMap = mutableMapOf<String, InlineTextContent>()
    // 2026-11-11 夜撞（表格压极小，真机消息 5574）：capPx/maxWem 提到预量**之前**——
    // 拆行决策（gatherIfNeeded）在预量与正式渲染两处都要用。
    // 2026-11-21「膨胀」：capPx 原来首帧是 MAX_VALUE → maxWem 无穷 → 拆行判据直接失灵，
    // 首帧永远按「不拆」排、布局回写宽度后再翻一次。先用屏幕宽兜底（正文列就是它减边距），
    // 真实容器宽由下面的 layout 捕获纠正——首帧就已经落在正确的一侧。
    val screenPxFallback = with(density) {
        androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp.dp.toPx()
    }
    var capPx by remember(text) { mutableStateOf(widthHintPx ?: screenPxFallback) }
    val maxWem = (if (capPx <= 0f || capPx.isInfinite()) Float.MAX_VALUE else capPx / fontPx)
        .coerceAtMost(14f)
    val capCapture = Modifier.layout { m, c ->
        if (c.hasBoundedWidth && c.maxWidth.toFloat() != capPx) capPx = c.maxWidth.toFloat()
        val p = m.measure(c)
        layout(p.width, p.height) { p.place(0, 0) }
    }
    // 2026-11-10：行内高公式（分数/极限）会顶穿上下的行——Compose 的 Text 行高按字算，
    // 不会为行内图片自动让位。这里量一遍所有公式的占位高度，把**行距**同步放大。
    // 性能关键：预量只用 **peek**（纯缓存查询，不触发渲染）——此前这里也走 latexBitmap
    // （composable，含 produceState），与下方正式渲染各起一份异步任务，滚动长公式会话时
    // 双份渲染 = 掉帧。未命中缓存的按启发式估高（含 \frac/\lim/\sum/\int/\sqrt 即按高算）。
    // 2026-11-11：预量对「将拆行」的公式用 gathered 版位图量高（hCap 3.4em），
    // 否则行高按单行版算会把拆行版顶穿上下行。
    // 2026-11-16：公式基准被抬到正文口径后，位图**相对本行字号**变大了（表格格里
    // 从 1.25em 变成 ~1.67em）。占位高度上限原本按「公式=行字号×1.25」定标，不放大会
    // 把抬过的字号又压回去——等于白改。这里算出放大倍数，所有上限同步乘上。
    val mathEmScale = if (compactMath) 1f else mathSizePx / (fontPx * 1.25f)
    val colorArgbNow = MaterialTheme.colorScheme.onSurface.toArgb()
    // ── 2026-11-17 用户截图（真机消息 7624 列表项）：「• f'>0 ⇒ f 严格递增：任取 x₁<x₂，
    // f(x₂)-f(x₁)=f'(c)(x₂-x₁)>0。（3.1 里"导数正判单调"这条…）」
    // 拆行（gather）后的两行位图原来被塞在**段落流**里：文字贴到位图第一行右侧继续排，
    // 行距又被 maxMathEm 整体撑大 → 读起来就是「文字被挤出、中间一个空洞」。
    // 现在：需要拆行的公式**独占一行**——段落按它切成若干流式片段，各自独立排（行距各算各的），
    // 公式自身走 LatexBlock（居中 + 装不下先缩放）。
    val gatheredOf: (MathSeg.Math) -> String? = { m ->
        // 2026-11-24 compactMath：表格格内**绝不**提升为块级/拆行（那会撑爆行高）。
        if (compactMath) null else {
        // 2026-11-21「膨胀」：内存 LRU 未命中时用**尺寸索引**——否则首帧判不出「该拆行」，
        // 等位图到货才把这条公式从行内提升成独占一行的块（高度、位置全变）。
        val wh = if (m.latex.isBlank()) null else
            LatexRender.peek(m.latex, mathSizePx, colorArgbNow)?.let { intArrayOf(it.width, it.height) }
                ?: LatexRender.peekSize(m.latex, mathSizePx, colorArgbNow)
        if (wh == null || !maxWem.isFinite()) {
            null
        } else {
            // 自然宽高（高度不封顶）——块级提升判定必须用未压缩的自然值
            val nat = inlinePlaceholderEm(wh[0], wh[1], fontPx, Float.MAX_VALUE, hCap = Float.MAX_VALUE)
            val gathered = gatherIfNeeded(m.latex, nat.first, maxWem).takeIf { it != m.latex }
            when {
                gathered != null -> gathered
                // 2026-11-22 用户截图「公式过小」：∑/∫/分式这类自然高 3em+ 的行内公式
                // 被占位上限（普通行内 2.2em，见 [mathCapEm]）等比压到 <0.8 倍，符号比
                // 正文矮一圈。与「拆行优先、缩放兜底」同一哲学：压缩超过 20% 就不硬缩，
                // 提升为独占一行的块级渲染（[LatexBlock] 全高、只按宽适配）。
                tallInlineSqueezed(
                    nat.second,
                    inlinePlaceholderEm(wh[0], wh[1], fontPx).second
                ) -> m.latex
                else -> null
            }
        }
        }
    }
    val blockIdx = segs.indices.filter { i ->
        (segs[i] as? MathSeg.Math)?.let { gatheredOf(it) != null } == true
    }
    /** 片段回拼成 markdown（行内公式回 `$…$`，标签链回纯文字），交回本函数递归渲染 */
    fun rebuild(sub: List<MathSeg>): String = buildString {
        for (s in sub) when (s) {
            is MathSeg.Txt -> append(s.text)
            is MathSeg.Math ->
                if (s.latex.isBlank()) append(s.labels)
                else append('$').append(s.latex).append('$')
        }
    }
    if (blockIdx.isNotEmpty()) {
        Column(modifier.then(capCapture)) {
            var start = 0
            for (i in blockIdx) {
                val pre = rebuild(segs.subList(start, i))
                if (pre.isNotBlank()) {
                    MdInlineText(pre, style, color, Modifier.fillMaxWidth(), fontWeight)
                }
                LatexBlock(gatheredOf(segs[i] as MathSeg.Math)!!)
                start = i + 1
            }
            val rest = rebuild(segs.subList(start, segs.size))
            if (rest.isNotBlank()) {
                MdInlineText(rest, style, color, Modifier.fillMaxWidth(), fontWeight)
            }
        }
        return
    }
    android.os.Trace.beginSection("cell.lineHeight")
    val maxMathEm = segs.filterIsInstance<MathSeg.Math>().maxOfOrNull { s ->
        val peeked = LatexRender.peek(s.latex, mathSizePx, colorArgbNow)
        // 2026-11-21「压缩毛巾」：内存 LRU 里没有时查**尺寸索引**，让行高首帧就用真实值
        // （否则先按估算排、位图到货再涨开）。
        val wh = peeked?.let { intArrayOf(it.width, it.height) }
            ?: LatexRender.peekSize(s.latex, mathSizePx, colorArgbNow)
        val natural = wh?.let { inlinePlaceholderEm(it[0], it[1], fontPx, Float.MAX_VALUE) }
        // 先判「会不会拆行」——行高预留与占位必须用**同一档上限**（见 [mathCapEm]）。
        // 2026-11-16 修：上一版这里写死 gathered=false，普通公式量到 2.2em，而下方正式
        // 渲染若真的拆了行会占 5.2em → 行距预留不足，拆行版顶穿上下行。
        val gatheredLatex = natural?.let { n ->
            // 2026-11-24 compactMath：表格格不做拆行提升（撑爆行高）
            if (compactMath) null
            else gatherIfNeeded(s.latex, n.first, maxWem).takeIf { it != s.latex }
        }
        val eff = gatheredLatex?.let { g ->
            LatexRender.peek(g, mathSizePx, colorArgbNow)?.let { intArrayOf(it.width, it.height) }
                ?: LatexRender.peekSize(g, mathSizePx, colorArgbNow)
        } ?: wh
        val willGather = gatheredLatex != null
        val baseCap = mathCapEm(s.latex, willGather)
        eff?.let {
            inlinePlaceholderEm(it[0], it[1], fontPx, Float.MAX_VALUE, hCap = mathHeightCapEm(baseCap, mathEmScale)).second
        }
        // 尺寸也不知道（首次见到、且未渲完）：按同一档上限给**保守估计**
            ?: mathHeightCapEm(
                if (compactMath) 2.2f
                else if (s.latex.contains("\\begin{")) 6f
                else if (isTallLatex(s.latex)) 2.2f
                else 1.4f,
                mathEmScale
            )
    } ?: 1.4f
    android.os.Trace.endSection()
    val textStyle = if (maxMathEm > 1.55f) style.copy(lineHeight = (maxMathEm + 0.25f).em) else style
    // 2026-11-10 用户截图「公式串行了」（聊天表格里公式压过邻格文字）：占位宽上限写死
    // 14em，远超窄容器（4 列表格单元格 ~8em）——Image 溢出占位盒（Box/Row 不裁剪）画到
    // 相邻单元格上，邻格自己的文字后画、盖在公式上层，看着像「文字压公式」。
    // 这里量出本段实际可用宽，把占位宽夹到容器以内（高按比例缩，公式不再越界）。
    // 2026-11-10 二次修正（真机 crash-loop 22:09 两次 FATAL，我上一版引入）：量宽**不能**
    // 用 BoxWithConstraints——它是 SubcomposeLayout，任何 IntrinsicSize 父布局查内禀尺寸
    // 直接 IllegalStateException（引用块/表格这类 Row.height(IntrinsicSize.Min) 场景，
    // App 自动恢复会话即崩循环）。改成 Modifier.layout 捕获**容器 maxWidth** 回写状态：
    // 首帧按 14em 上限渲染（与旧行为一致），下一帧把超宽公式夹进容器；等值回写不触发
    // 重组，无循环；Modifier.layout 不拦 intrinsic（透传给 Text，Paragraph 支持内禀测量）。
    // 2026-11-13 表格实测开滑：本行内被挤压的数学内容最大自然宽（em）；0=没挤压
    var clampedEm = 0f
    android.os.Trace.beginSection("cell.build")
    val annotated = buildAnnotatedString {
        // 2026-11-11 用户截图：`~~"因为 $a_n\to\sqrt5$…"~~（…）` 的删除线段跨过了公式段，
        // 旧实现各段独立调 inlineMd → `~~` 两侧都不配对，标记裸露。这里按段流式跟踪。
        // 2026-11-20 降噪批扩展：单 * 斜体同病（用户截图 `*（$\Delta x$ 是负的…）*`
        // 星号裸显）——2026-11-02 只补过 ** 的悬空粗体。[InlineMarkTracker] 统一做
        // ~~ 与单 * 的跨段配对；单 * 全段计数为奇数（裸乘号/误写）时不配斜体。
        val markTracker = InlineMarkTracker(segs.filterIsInstance<MathSeg.Txt>().map { it.text })
        fun appendMarkAware(t: String) {
            for (span in markTracker.spansOf(t)) {
                val a = inlineMd(span.text, linkColor)
                val style = when {
                    span.strike && span.ital -> SpanStyle(
                        textDecoration = androidx.compose.ui.text.style.TextDecoration.LineThrough,
                        fontStyle = androidx.compose.ui.text.font.FontStyle.Italic
                    )
                    span.strike -> SpanStyle(textDecoration = androidx.compose.ui.text.style.TextDecoration.LineThrough)
                    span.ital -> SpanStyle(fontStyle = androidx.compose.ui.text.font.FontStyle.Italic)
                    else -> null
                }
                if (style != null) withStyle(style) { append(a) } else append(a)
            }
        }
        segs.forEach { s ->
            when (s) {
                is MathSeg.Txt -> appendMarkAware(s.text)
                is MathSeg.Math -> {
                    // 2026-11-17：整块标签链（公式本体被吃光）在行内语境下当纯文字续写，
                    // 否则 latexBitmap("") 拿不到位图、回退成空串 → 文字整段丢失。
                    if (s.latex.isBlank()) {
                        appendMarkAware(s.labels)
                        return@forEach
                    }
                    // 2026-11-21「压缩毛巾」：**先拿尺寸索引**把占位排对，不依赖位图是否已到货。
                    // 组合期 latexBitmap 只查内存 LRU；冷进场必然未命中，以前会按估算排一次、
                    // 位图到货再按真实宽高重排——用户看到的就是「渲好的公式停手时又涨开」。
                    // 尺寸索引（渲好时落盘的 w/h）让首帧就用真实尺寸，位图晚一两帧到也不重排。
                    val memoryBmp = latexBitmap(s.latex, mathSizePx)
                    val indexed = LatexRender.peekSize(s.latex, mathSizePx, colorArgbNow)
                    var wh = memoryBmp?.let { intArrayOf(it.width, it.height) } ?: indexed
                    var bmpLatex = s.latex
                    var usedGathered = false
                    if (wh != null && maxWem.isFinite()) {
                        // 2026-11-11 夜撞：夹紧要缩掉 25% 以上时改**按顶层关系符拆行**
                        //（gathered 多行），可读性远好于硬缩；拆不出多行才回退缩放。
                        val natural = inlinePlaceholderEm(wh[0], wh[1], fontPx, Float.MAX_VALUE)
                        // 2026-11-24 compactMath：表格格不做拆行提升（撑爆行高）。
                        val gathered = if (compactMath) s.latex else gatherIfNeeded(s.latex, natural.first, maxWem)
                        if (gathered != s.latex) {
                            val gWh = LatexRender.peekSize(gathered, mathSizePx, colorArgbNow)
                                ?: LatexRender.peek(gathered, mathSizePx, colorArgbNow)
                                    ?.let { intArrayOf(it.width, it.height) }
                            if (gWh != null) {
                                wh = gWh
                                bmpLatex = gathered
                                usedGathered = true
                            }
                        }
                    }
                    if (wh == null) {
                        // 尺寸也未知（首次见到的公式、且还没渲完）：回退裸 LaTeX 文本。
                        // 清洗间距/换行命令，别让 `\ ` `\!` 原样可见（消息 5572 教训）
                        withStyle(SpanStyle(fontFamily = FontFamily.Monospace)) { append(fallbackText(s.latex)) }
                    } else {
                        val id = "latex" + contentMap.size
                        // 2026-11-10 用户截图：行内分数/极限"小得看不清"——根因是这里把占位
                        // 高度写死 1.4em，高公式位图被**按 1.4em 压扁**（InlineTextContent 会把
                        // 图片缩放到占位尺寸）。改成按位图像素高度换算 em：高公式自然撑高行高，
                        // 分数不再缩水；宽度上限由 maxWem（容器宽）夹紧；拆行版允许高到 3.4em
                        //（行距已由上方 maxMathEm 同步放大）。
                        // 2026-11-11 用户截图：表格里的 4×4 矩阵被压成"极小"——矩阵天然
                        // 4~5em 高，2.2/3.4 的行高上限对它就是把整个公式等比缩小。矩阵环境
                        // （\begin{bmatrix} 等）放宽到 6em：行高会跟着长，但矩阵必须能读。
                        // 2026-11-16：isMatrix 已并入 [mathCapEm]（矩阵/拆行/普通三档在
                        // 那里唯一表述），本处不再重复判定。
                        // 2026-11-13 表格实测开滑：**绘制宽**超过可用宽=被挤压，记下自然宽
                        //（最终是拆行版就量拆行版）
                        if (onClamped != null && maxWem.isFinite()) {
                            val natWem = wh[0].toFloat() / fontPx
                            if (natWem > maxWem + 0.05f && natWem > clampedEm) clampedEm = natWem
                        }
                        val (wEm, hEm) = inlinePlaceholderEm(
                            wh[0], wh[1], fontPx, maxWem,
                            // 2026-11-14 用户「被压缩成一行的超小公式」（模拟器真源复现）：
                            // gathered 3 行位图自然高 ~4.5em，压在 3.4em 上限里整摞缩到 ~76%，
                            // 行内公式比正文还小一圈。行高预留（maxMathEm）对 \begin{} 已按
                            // 6em 放大过，占位上限提到 5.2：2 行不变，3 行不再缩水。
                            // 2026-11-16：上限与行高预量**统一走 [mathCapEm]**（此前两处各写
                            // 一套：预量 3.4/6，占位 2.2/5.2/6 —— 占位比预量大时公式顶穿上下行，
                            // 小时白白缩水），再乘字号放大倍数。
                            hCap = mathCapEm(s.latex, usedGathered) * mathEmScale
                        )
                        appendInlineContent(id, "[tex]")
                        // 2026-11-15 用户截图（微积分会话「好了，现在开2.3」那轮的例 2）：
                        // `- 乘积法则：$f'=2x(…)=…=…$` 这种**被拆成多行**的行内公式，占位如果
                        // TextCenter，整摞垂直居中 → 前面那个「乘积法则：」文字落在**第 2 行**旁边，
                        // 第一行（光秃秃一个 `f'`）飘在文字上方，看着像公式被切断了。
                        // 多行（gathered）改用 Top 对齐：标签与第一行同高，后续等号行自然往下排。
                        val vAlign = if (usedGathered) PlaceholderVerticalAlign.Top
                        else PlaceholderVerticalAlign.TextCenter
                        contentMap[id] = InlineTextContent(
                            Placeholder(wEm.em, hEm.em, vAlign)
                        ) {
                            // 位图到货只重组**这一格**（状态在这里读），不再牵动整段文本重排
                            InlineLatexImage(bmpLatex, mathSizePx)
                        }
                    }
                }
            }
        }
    }
    android.os.Trace.endSection()
    // 上报放在 SideEffect（组合期写外部状态会触发重组风暴；MdTable 里单向棘轮去重）
    val cb by androidx.compose.runtime.rememberUpdatedState(onClamped)
    androidx.compose.runtime.SideEffect {
        val r = cb
        if (r != null && clampedEm > 0f) r(clampedEm)
    }
    Text(annotated, style = textStyle, color = color, inlineContent = contentMap, modifier = modifier.then(capCapture), fontWeight = fontWeight)
}

/** 跨段标记配对产出：一段文字 + 它所处的删除线/斜体状态 */
internal data class InlineMarkSpan(val text: String, val strike: Boolean, val ital: Boolean)

/**
 * 跨公式段的行内标记流式配对（~~删除线 与 单 * 斜体）。
 *
 * 为什么存在：`$公式$` 会把正文切成多个 Txt 段，`*（$\Delta x$ 是负的…）*` 的开/闭
 * 星号落在两个段里，每段独立调 inlineMd（它只认段内配对）两侧都配不上 → 星号裸显。
 * 2026-11-02 曾只为 ** 补过「悬空粗体延续」；这里统一成流式状态机：~~ 与单 * 在
 * 段序列里开/闭配对，样式跨公式段延续。
 *
 * 单 * 的防护（2026-11-20）：**全段单 * 计数为奇数时不启用斜体配对**——奇数说明有
 * 裸乘号/误写（「2*3=6」），整段保持旧路径（inlineMd 自己段内配对，配不上就原样显示）。
 * ~~ 维持旧行为：无条件开关延续（2026-11-11 语义）。`**` 粗体不归这里管（inlineMd 优先档）。
 */
internal class InlineMarkTracker(txtParts: List<String>) {
    private var strike = false
    private var ital = false
    private val italEnabled = txtParts.sumOf { countSingleStars(it) } % 2 == 0

    /** 处理一段 Txt，返回 [(text, strike, ital)]；配对标记已剥掉，状态延续到下一段 */
    fun spansOf(part: String): List<InlineMarkSpan> {
        val out = mutableListOf<InlineMarkSpan>()
        val cur = StringBuilder()
        fun flush() {
            if (cur.isNotEmpty()) {
                out += InlineMarkSpan(cur.toString(), strike, ital)
                cur.clear()
            }
        }
        var i = 0
        while (i < part.length) {
            val sJ = part.indexOf("~~", i)
            var iJ = if (italEnabled) part.indexOf('*', i) else -1
            // ** 粗体标记跳过（inlineMd 自己处理），只配对单 *
            while (iJ >= 0 && iJ + 1 < part.length && part[iJ + 1] == '*') iJ = part.indexOf('*', iJ + 2)
            when {
                sJ < 0 && iJ < 0 -> { cur.append(part, i, part.length); i = part.length }
                iJ < 0 || (sJ >= 0 && sJ < iJ) -> {
                    cur.append(part, i, sJ); flush(); strike = !strike; i = sJ + 2
                }
                else -> { cur.append(part, i, iJ); flush(); ital = !ital; i = iJ + 1 }
            }
        }
        flush()
        return out
    }

    companion object {
        /** 不在 ** 里的单 * 个数（** 成对跳过） */
        internal fun countSingleStars(s: String): Int {
            var n = 0
            var i = 0
            while (i < s.length) {
                if (s[i] == '*') {
                    if (i + 1 < s.length && s[i + 1] == '*') i += 2 else { n++; i++ }
                } else i++
            }
            return n
        }
    }
}

/** 块级公式的显示策略（纯函数，JVM 可测） */
internal enum class LatexFitMode {
    /** 装得下：原尺寸居中（视觉不变） */
    Fits,
    /** 装不下但缩得动：等比缩到容器宽，不给滑动条 */
    Scaled,
    /** 缩到下限仍装不下：原尺寸 + 横向滑动 */
    Scroll
}

/**
 * 缩到多小就放弃缩放、改回横滑。0.62 = 公式仍有六成以上高度，字母不至于糊成蚂蚁；
 * 再窄的公式（超宽两倍以上）缩了也读不了，不如让用户横滑看原图。
 */
internal const val LATEX_MIN_SCALE = 0.62f

/**
 * 块级公式怎么摆进容器。
 *
 * @param naturalPx 位图自然宽（px）
 * @param containerPx 容器可用宽（px）
 */
internal fun latexFitMode(
    naturalPx: Float,
    containerPx: Float,
    minScale: Float = LATEX_MIN_SCALE
): LatexFitMode {
    if (containerPx <= 0f || naturalPx <= 0f) return LatexFitMode.Fits
    // 4% 余量：取整误差不算超宽（沿用 2026-11-13 的假滑条修复）
    if (naturalPx <= containerPx * 1.04f) return LatexFitMode.Fits
    val scale = containerPx / naturalPx
    return if (scale >= minScale) LatexFitMode.Scaled else LatexFitMode.Scroll
}

/** 块级公式：居中显示，解析失败回退等宽原文（2026-11-21 外包一层分解探针） */
@Composable
fun LatexBlock(latex: String) {
    // 2026-11-21 探针：组合内**就地**计时。SideEffect 要等整帧组合结束后才执行，会把本组件
    // 之后所有兄弟节点的耗时都算进来（各段互相重叠、集体虚高）。组合期调用返回即取差值。
    val t0 = android.os.SystemClock.elapsedRealtime()
    LatexBlockImpl(latex)
    io.github.m0use001.planability.core.ScrollTrace.noteComposePart(
        "式", (android.os.SystemClock.elapsedRealtime() - t0).toInt()
    )
}

@Composable
private fun LatexBlockImpl(latex: String) {
    // 2026-11-17：整块都是标签链时，公式本体为空（MathSeg.Math("", true, labels)）——
    // 不去渲染位图（空 latex 会让 jlatexmath 抛异常），只留一点上下间距。
    if (latex.isBlank()) {
        Spacer(Modifier.height(8.dp))
        return
    }
    val density = LocalDensity.current
    // 2026-11-14：16.sp 硬编码改取 bodyLarge 实际值（含用户 fontScale 缩放）——
    // 之前行内公式跟字号缩放走、块级不走，用户调大字号时块级反而显小；
    // 且预热键（warmUpFormulas 的 fontPx）必须与渲染调用点同源
    val fontPx = with(density) {
        MaterialTheme.typography.bodyLarge.fontSize.takeOrElse { 16.sp }.toPx()
    }
    // 2026-11-20 用户「复杂场景公式横滑条又密又乱」（截图两条横滑）：
    // 块级原阶梯只有 Fits/Scaled/Scroll（2026-11-12「块级保排版原貌」拍板），超宽 1.6 倍
    // 直接横滑——多步推导里每条公式都横滑就是截图的样子。行内路径 2026-11-12 起已有
    // 「拆行优先、缩放兜底」（gatherIfNeeded），块级并入同一阶梯：进 Scroll 档先按顶层
    // 关系符/公式组/等号拆行重渲（gathered 位图，每行一条关系式），拆得动就不横滑；
    // 拆不动（无断点/已是多行环境）维持横滑保底。
    // 2026-11-21 瞬移根治②：gathered 决策跨组合缓存（键含屏宽），回场首帧直接用
    // 正确布局——原来回场必先单行再翻转，高度跳一次。
    val screenPx = with(density) {
        LocalConfiguration.current.screenWidthDp.dp.toPx()
    }
    val gatheredKey = "$latex|${screenPx.toInt()}"
    var gatheredForDisplay by remember(latex) { mutableStateOf(gatheredCache.get(gatheredKey)) }
    val colorArgbNow = MaterialTheme.colorScheme.onSurface.toArgb()
    val mathPxBlk = fontPx * 1.5f
    // 2026-11-21「膨胀」：尺寸索引里已经有这张图时，**第一帧**就用它的真实自然宽决定
    // 要不要拆行。以前是「先按单行画一帧 → SideEffect 翻开 → 第二帧换成拆行」，
    // 高度跳那一下就是这个两帧翻转（用户说的压缩毛巾）。
    val indexedGathered = if (gatheredForDisplay != null) null else run {
        val natPx = LatexRender.peekSize(latex, mathPxBlk, colorArgbNow)?.get(0)
            ?: LatexRender.peek(latex, mathPxBlk, colorArgbNow)?.width
        if (natPx == null) null
        else if (latexFitMode(natPx.toFloat(), screenPx) == LatexFitMode.Scroll) {
            val g = gatherIfNeeded(latex, natPx / fontPx, screenPx / fontPx)
            if (g != latex) {
                gatheredCache.put(gatheredKey, g)
                g
            } else null
        } else null
    }
    val displayLatex = gatheredForDisplay ?: indexedGathered ?: latex
    when (val slot = latexSlot(displayLatex, mathPxBlk)) {
        LatexSlot.Pending -> {
            // 2026-11-14 用户「AI 生成带动屏幕滚动时屏幕抽搐」：渲染中先按估算**预留**位图
            // 高度（裸文本垫底）。流式贴底跟随每次换高都做视口补偿，「1 行 13sp 裸文本 →
            // 可达 3~6em 的位图」几十 px 的跳变就是抽搐源；预留把换高收敛到估算误差内。
            // 渲染完且失败（Done(null)）才真回退裸文本——不占位。
            // 2026-11-21：尺寸索引命中时直接按**真实高度**预留（估算误差也不留）。
            val exactH = LatexRender.peekSize(displayLatex, mathPxBlk, colorArgbNow)?.get(1)
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(
                        if (exactH != null) with(density) { exactH.toFloat().toDp() }
                        else with(density) { (estimateBlockEm(displayLatex) * fontPx).toDp() }
                    )
            ) {
                Text(
                    fallbackText(latex),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        is LatexSlot.Done -> if (slot.bmp == null) {
            Text(
                fallbackText(latex),
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth()
            )
        } else {
            val bmp = slot.bmp
        // 2026-11-12 用户「很多地方都需要自适应滑动条，特别长的公式也要」：
        // 原先 Image 固定自然宽，超过容器被父约束夹住等比缩小——长等式缩成蚂蚁字。
        // 改成量容器宽：装得下照旧居中（视觉不变）；装不下按自然尺寸渲 + 横向滑动
        // （手势登记 hScrollZone，与代码块/表格同口径）。与行内公式的「拆行优先」
        // 分工：块级本来就该保排版原貌，宽了给滑条而不是缩放或硬拆。
        // 2026-11-13 用户「滑动条要可见，不然不知道能滑」：over 分支挂 hScrollHint。
        // 2026-11-13 用户「刚好占满屏幕的不太长公式被塞了滑动条」：阈值加 4% 余量——
        // 超出不到 4%（约 12dp）视作渲染取整误差，照旧居中钳制（肉眼不可见的缩），
        // 不给假滑条；真超宽才进滑动模式。
        var containerPx by remember(latex) { mutableIntStateOf(0) }
        val hState = rememberScrollState()
        val naturalW = with(density) { bmp.width.toDp() }
        val naturalH = with(density) { bmp.height.toDp() }
        val naturalPx = with(density) { bmp.width.toDp().toPx() }
        // 2026-11-17 用户「文字被挤出 / 公式被切一半」：
        // ① 首帧 containerPx 还是 0 → over 永远 false → 固定自然宽的 Image 在
        //    Row(Center) 里居中溢出。Compose 的 Row **不裁剪**，位图会直接画到
        //    兄弟节点上——公式下面那行中文（\text{} 剥出来的标签）被盖住、两端溢出屏幕。
        //    首帧先用屏幕宽兜底判一次，容器宽到了再改用真实值。
        // ② 超宽公式原来只有「原尺寸 + 横向滑动」一种结局：用户看到的永远是左边被切一半
        //    的公式加一条滑动条（截图实锤）。现在改成**先等比缩到容器宽**，
        //    缩到 [LATEX_MIN_SCALE] 仍装不下才退回横滑——中等超宽直接看全，不再切头。
        // （2026-11-21 screenPx 上移到函数顶部：gathered 缓存键在首帧就要用它。）
        val effPx = if (containerPx > 0) containerPx.toFloat() else screenPx
        val mode = latexFitMode(naturalPx, effPx)
        // 2026-11-20：Scroll 档先试拆行（只对原始 latex 试一次；gathered 版含 \begin{，
        // gatherIfNeeded 直接原样返回，不会递归）。SideEffect 写状态 → 下一帧用 gathered
        // 重渲；转换帧按原样横滑渲染，与旧行为一致。
        if (mode == LatexFitMode.Scroll && gatheredForDisplay == null) {
            val g = gatherIfNeeded(latex, naturalPx / fontPx, effPx / fontPx)
            if (g != latex) {
                gatheredCache.put("$latex|${screenPx.toInt()}", g)
                androidx.compose.runtime.SideEffect { gatheredForDisplay = g }
            }
        }
        val scale = if (mode == LatexFitMode.Scaled) (effPx / naturalPx) else 1f
        Row(
            Modifier
                .fillMaxWidth()
                .onSizeChanged { containerPx = it.width }
                // 兜底：即便判定没赶上首帧，也不许位图画到 Row 边界外盖住相邻文字
                .clipToBounds()
                .then(
                    if (mode == LatexFitMode.Scroll)
                        Modifier.hScrollZone().hScrollHint(hState).horizontalScroll(hState)
                    else Modifier
                )
                .padding(vertical = 4.dp),
            horizontalArrangement = if (mode == LatexFitMode.Scroll) Arrangement.Start else Arrangement.Center
        ) {
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .width(naturalW * scale)
                    .height(naturalH * scale)
            )
        }
        }
    }
    Spacer(Modifier.height(8.dp))
}

/** 含这些命令的行内公式自然高度 >1.4em（分数/极限/求和/积分/根号/矩阵） */
internal fun isTallLatex(latex: String): Boolean = listOf(
    "\\frac", "\\lim", "\\sum", "\\int", "\\sqrt", "\\begin", "\\displaystyle", "\\binom"
).any { latex.contains(it) }

/**
 * 块级公式**渲染中**的预留高度估算（em，相对正文 16sp）。只求「方向对、量级近」：
 * 块级渲染字号是正文的 1.5 倍，实测单行公式位图 ~1.8em、分数/极限 ~3.5em、
 * 矩阵/cases/gathered 每行 ~1.8em。行分隔符按字面 `\\` 数估（嵌套环境会高估，
 * 方向可接受）；估算误差 ±0.3em 级——远小于无预留时「1 行 13sp 裸文本 → 位图」
 * 两三个 em 的跳变（2026-11-14 流式贴底滚动抽搐根修）。纯函数，JVM 可测。
 */
internal fun estimateBlockEm(latex: String): Float {
    val rows = latex.split("\\\\").dropLastWhile { it.isBlank() }.size.coerceAtLeast(1)
    return when {
        rows > 1 -> (rows * 1.8f).coerceAtMost(7f)
        isTallLatex(latex) -> 3.2f
        else -> 2.0f
    }
}

// ==================== 2026-11-11 夜撞：行内长公式「拆行优先、缩放兜底」 ====================

/**
 * 从 [from] 起连续英文字母的个数（命令名长度）。
 *
 * 2026-11-19 性能：原来用 `latex.drop(i + 1).takeWhile { it.isLetter() }.length`，
 * 每个字符都把尾巴整段拷一遍 → 长公式上 O(n²) 分配。改成索引扫描，零分配。
 */
private fun letterRunLength(s: String, from: Int): Int {
    var j = from
    while (j < s.length && s[j].isLetter()) j++
    return j - from
}

/**
 * 「拆行决策」缓存（2026-11-19 滚动卡顿批）。
 *
 * [gatherIfNeeded] 是**纯函数**（latex + 自然宽 + 可用宽 → 拆行后的 latex），
 * 但它每条公式每帧都要被调两遍（算行高一遍、算占位一遍），而拆行本身是逐字符扫描 +
 * 三次尝试（关系符 / 等号 / 二元运算符）。长会话里几十个可见气泡各带公式时，
 * 这些扫描全落在**主线程组合期**——用户看到的就是滑动时掉帧。
 *
 * 缓存键 = latex + 两个宽度的**位模式**（Float 用 toBits，避开 == 的 -0.0/NaN 坑）。
 * 命中即省掉整条扫描；条目上限 512，超了按 LRU 淘汰。
 */
private val GATHER_CACHE: MutableMap<GatherKey, String> =
    object : LinkedHashMap<GatherKey, String>(128, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<GatherKey, String>?): Boolean =
            size > 512
    }

/** 缓存键：latex + 两个宽度的**位模式**（Float 用 toRawBits，避开 == 的 -0.0 坑）。 */
private data class GatherKey(val latex: String, val naturalBits: Int, val maxBits: Int)

private fun gatherCacheKey(latex: String, naturalWem: Float, maxWem: Float): GatherKey =
    GatherKey(latex, naturalWem.toRawBits(), maxWem.toRawBits())


/** unicode 链式推理关系符（2026-11-20 截图：模型直出 ⟹ 而非 \implies，命令表认不出）。单字符集合。 */
private const val UNICODE_REL_CHARS = "⟹⟸⟺⇒⇐⇔→←↔⟶⟵"

/**
 * 按**顶层**关系符把一行长公式拆成多行（返回 null = 拆不了，调用方回退等比缩放）。
 *
 * 背景（真机消息 5574 实锤）：表格列宽 ~8em，`$\sqrt{x+6}\le x\iff x^2-x-6\ge0\iff x\ge3$`
 * 一行自然 ~16em，被 maxWem 等比缩到 50% 极小不可读。拆成 gathered 多行（每行一个
 * 关系式）比缩放可读得多。拆分只认**顶层**：花括号深度 0、且不在 \left…\right 组内、
 * 且不在方括号组内（2026-11-14 用户截图：`=a\bigl[(dh-eg)B_1+(bg-ah)B_2+…\bigr]` 被
 * 二元档拆成 3 行竖摞——`[` 还开着、`]` 落在第三行末尾，句子中间竖着一块读不成行；
 * 方括号和圆括号同样是分组，组内不拆，整行回退缩放）、且不是更长命令的一部分；
 * 拆出的行以关系符开头（数学排版惯例）。
 * 关系符取「链式推理」家族（iff/Rightarrow/implies/leftrightarrow/to/le/ge…），
 * 刻意**不含 =**（等式链拆开反而难读）。纯函数，JVM 可测。
 */
internal fun splitAtTopRelations(latex: String, allowBinaryOps: Boolean = false): List<String>? {
    val rels = listOf(
        "\\iff", "\\Leftrightarrow", "\\leftrightarrow", "\\implies",
        "\\Longrightarrow", "\\longrightarrow", "\\Rightarrow", "\\to"
    )
    // 2026-11-12 用户「表格内数学公式被压缩的太小」：纯加/乘链（+ − · ×）没有顶层
    // 关系符，旧逻辑拆不动只能硬缩到 0.4x 极小。加法/乘法链在运算符处断行是标准
    // 数学排版（行首悬挂运算符），所以提供第二档拆分点。括号深度防护：(-x)^2
    // 这类括号内的一元负号不是拆分点。
    val ops = if (allowBinaryOps) listOf(
        "\\pm", "\\mp", "\\cdot", "\\times", "\\oplus", "\\otimes", "\\cup", "\\cap"
    ) else emptyList()
    // 2026-11-19 性能：关系符表在循环里每次 `(rels + ops)` 拼一个新 List（每字符一次），
    // 提到循环外。
    val allRels = if (ops.isEmpty()) rels else rels + ops
    // 刻意不含 \le/\ge/\approx/\equiv：链里的每个不等号都断行会把公式碎成渣，
    // 只在「链式推理」连接处断（⇔/⇒/→）；等号同理不拆。
    val parts = mutableListOf<String>()
    val cur = StringBuilder()
    var i = 0
    var braceDepth = 0
    var leftRight = 0
    var parenDepth = 0
    var bracketDepth = 0
    while (i < latex.length) {
        val c = latex[i]
        if (c == '\\') {
            // 命令：识别是否顶层关系符（要求后面不是字母，防 \leq 误匹配 \le）
            // 2026-11-19 性能：原来这里 `latex.substring(i)` 每个字符都拷一遍尾巴
            // （O(n²) 分配）→ 改成 startsWith(i) 原地比对。
            val rel = allRels.firstOrNull {
                latex.startsWith(it, i) && latex.getOrNull(i + it.length)?.isLetter() != true
            }
            if (rel != null && braceDepth == 0 && leftRight == 0 && parenDepth == 0 && bracketDepth == 0) {
                if (cur.isNotBlank()) parts.add(cur.toString().trim())
                cur.clear()
                cur.append(rel)
                i += rel.length
                continue
            }
            // 普通命令：整段抄（\{ \} 不配平）
            val cmdLen = 1 + letterRunLength(latex, i + 1)
            cur.append(latex, i, i + cmdLen)
            if (latex.startsWith("\\left", i)) leftRight++
            if (latex.startsWith("\\right", i) && leftRight > 0) leftRight--
            i += cmdLen
            continue
        }
        // unicode 关系符（单字符）：顶层才拆，行首悬挂同命令档（深度守卫同上）
        if (c in UNICODE_REL_CHARS && braceDepth == 0 && leftRight == 0 && parenDepth == 0 && bracketDepth == 0) {
            if (cur.isNotBlank()) parts.add(cur.toString().trim())
            cur.clear()
            cur.append(c)
            i++
            continue
        }
        when (c) {
            '{' -> braceDepth++
            '}' -> braceDepth--
            '(' -> parenDepth++
            ')' -> if (parenDepth > 0) parenDepth--
            '[' -> bracketDepth++
            ']' -> if (bracketDepth > 0) bracketDepth--
            '&' -> if (braceDepth == 0 && leftRight == 0) { // 对齐符也是合法拆分点
                if (cur.isNotBlank()) parts.add(cur.toString().trim())
                cur.clear()
                i++
                continue
            }
            '+', '-', '−' -> if (allowBinaryOps && braceDepth == 0 && leftRight == 0 && parenDepth == 0 && bracketDepth == 0) {
                // 二元 +/−：前一位是运算对象（数字/字母/括号/命令尾）才算二元；
                // 行首的一元负号（前面是命令或开头）不拆
                val prev = cur.trim().lastOrNull()
                val binary = prev != null &&
                    (prev.isLetterOrDigit() || prev == ')' || prev == '}' || prev == ']')
                if (binary) {
                    if (cur.isNotBlank()) parts.add(cur.toString().trim())
                    cur.clear()
                    cur.append(c)
                    i++
                    continue
                }
            }
        }
        cur.append(c)
        i++
    }
    if (cur.isNotBlank()) parts.add(cur.toString().trim())
    // 拆不出 ≥2 段（比如整个表达式只有一个关系符且左侧为空）→ 回退缩放
    if (parts.size < 2) return null
    return parts
}

/**
 * 按**顶层等号**拆等式链（行首悬挂 `=`，数学排版惯例）。
 *
 * 2026-11-13 用户截图（`$B_1$ 的系数：$-gae+gbd+adh-dbg=adh-gae=a(dh-eg)$`）：
 * 这类推导链没有链式关系符（⇔/⇒），落到二元运算符档会被 `+/-` 切成 **5 个碎片**
 * 竖摞在文字行里，碎片互相粘着完全读不出推导；等号是它天然的断行点
 * （3 行「…=…=…」vs 5 行碎片）。2~4 段才可用——更多等号说明是长链，
 * 硬拆也不好读，回退缩放由调用方兜底。
 * 括号/大括号/方括号/`\left…\right` 组内的 `=`（`\frac{a=b}{c}`、`f(x)=1` 的定义式嵌套）不是拆分点。
 */
internal fun splitAtTopEquals(latex: String, maxParts: Int = 4): List<String>? {
    val parts = mutableListOf<String>()
    val cur = StringBuilder()
    var braceDepth = 0
    var leftRight = 0
    var parenDepth = 0
    var bracketDepth = 0
    var i = 0
    while (i < latex.length) {
        val c = latex[i]
        if (c == '\\') {
            // 2026-11-19 性能：同 splitAtTopRelations——去掉每字符一次的 substring/drop 分配
            val cmdLen = 1 + letterRunLength(latex, i + 1)
            cur.append(latex, i, i + cmdLen)
            if (latex.startsWith("\\left", i)) leftRight++
            if (latex.startsWith("\\right", i) && leftRight > 0) leftRight--
            i += cmdLen
            continue
        }
        when (c) {
            '{' -> braceDepth++
            '}' -> braceDepth--
            '(' -> parenDepth++
            ')' -> if (parenDepth > 0) parenDepth--
            '[' -> bracketDepth++
            ']' -> if (bracketDepth > 0) bracketDepth--
            '=' -> if (braceDepth == 0 && leftRight == 0 && parenDepth == 0 && bracketDepth == 0) {
                if (cur.isNotBlank()) parts.add(cur.toString().trim())
                cur.clear()
                cur.append('=')
                i++
                continue
            }
        }
        cur.append(c)
        i++
    }
    if (cur.isNotBlank()) parts.add(cur.toString().trim())
    if (parts.size < 2 || parts.size > maxParts) return null
    return parts
}

/**
 * 按**顶层逗号 + 后随空距**拆公式组（2026-11-20 用户截图复杂场景横滑条批）：
 * `f(x) = x^{1/10}, \qquad x_0 = 1024,` 两条等式用 \qquad 并排（多列 aligned 的
 * 两列单元剥掉 & 后也是这个形态）——等号档会切成 `f(x) / = x^{1/10}, \qquad x_0 /
 * = 1024,` 三条碎片，等式的头尾互相粘连读不出。逗号+空距是公式组的天然断点，
 * 拆成两条整式：`f(x) = x^{1/10},` / `x_0 = 1024,`。
 * 只认后随 \quad/\qquad/\,/\;/\:/\ 空距的顶层逗号（深度守卫同 [splitAtTopEquals]）；
 * 集合记号 `a, b \in S` 这类裸逗号不拆。逗号留在上一行行尾（数学排版惯例）。
 */
internal fun splitAtTopCommaGroups(latex: String): List<String>? {
    val parts = mutableListOf<String>()
    val cur = StringBuilder()
    var braceDepth = 0
    var leftRight = 0
    var parenDepth = 0
    var bracketDepth = 0
    var i = 0
    while (i < latex.length) {
        val c = latex[i]
        if (c == '\\') {
            val cmdLen = 1 + letterRunLength(latex, i + 1)
            cur.append(latex, i, i + cmdLen)
            if (latex.startsWith("\\left", i)) leftRight++
            if (latex.startsWith("\\right", i) && leftRight > 0) leftRight--
            i += cmdLen
            continue
        }
        when (c) {
            '{' -> braceDepth++
            '}' -> braceDepth--
            '(' -> parenDepth++
            ')' -> if (parenDepth > 0) parenDepth--
            '[' -> bracketDepth++
            ']' -> if (bracketDepth > 0) bracketDepth--
            ',' -> if (braceDepth == 0 && leftRight == 0 && parenDepth == 0 && bracketDepth == 0) {
                // 只认后随空距命令的顶层逗号；裸逗号（集合记号/普通列举）不拆
                var j = i + 1
                while (j < latex.length && latex[j] == ' ') j++
                val spacing = listOf("\\quad", "\\qquad", "\\;", "\\,", "\\:", "\\ ")
                    .firstOrNull { latex.startsWith(it, j) }
                if (spacing != null) {
                    if (cur.isNotBlank()) parts.add(cur.toString().trim() + ",")
                    cur.clear()
                    i = j + spacing.length
                    continue
                }
            }
        }
        cur.append(c)
        i++
    }
    if (cur.isNotBlank()) parts.add(cur.toString().trim())
    if (parts.size < 2) return null
    return parts
}

/**
 * 行内公式「拆行优先」决策（纯函数，JVM 可测）：
 * 自然宽 [naturalWem] 超过容器 [maxWem] 且缩水比 <0.8（超过 25%）时，尝试按顶层
 * 关系符拆行包成 gathered；拆不出多行就原样返回（调用方走等比缩放兜底）。
 * 拆行后仍超宽的（无关系符的巨型单项）不继续处理。
 */
internal fun gatherIfNeeded(latex: String, naturalWem: Float, maxWem: Float): String {
    if (!maxWem.isFinite() || maxWem <= 0f || naturalWem <= maxWem) return latex
    if (naturalWem < maxWem * 1.25f) return latex // 轻微溢出：缩放即可，不拆
    if (latex.contains("\\begin{")) return latex  // 已是多行环境
    // 2026-11-19 性能：拆行决策是纯函数 → 走缓存，别让每次重组都重扫一遍长公式
    val key = gatherCacheKey(latex, naturalWem, maxWem)
    synchronized(GATHER_CACHE) {
        GATHER_CACHE[key]?.let { return it }
    }
    // 2026-11-12：先按关系符拆（⇔/⇒/→）；没有顶层关系符时改按**顶层等号**拆
    // （2026-11-13 图4/图5 推导链——`+/-` 碎片档读不出推导，等号档 2~4 行）；
    // 等号也没有（纯加/乘链）才按二元运算符拆（+ − · ×，行首悬挂运算符），不硬缩到 0.4x
    val parts = splitAtTopRelations(latex)
        ?: splitAtTopCommaGroups(latex)
        ?: splitAtTopEquals(latex)
        ?: splitAtTopRelations(latex, allowBinaryOps = true)
        ?: return latex
    val out = "\\begin{gathered}" + parts.joinToString(" \\\\ ") + "\\end{gathered}"
    synchronized(GATHER_CACHE) { GATHER_CACHE[key] = out }
    return out
}

/**
 * 渲染失败回退裸文本前的清理（真机消息 5572 教训：`$$…\xRightarrow{\text{平方}}…$$`
 * 整块失败时 `\ ` `\!` 这类间距命令原样可见）。把换行/间距命令还原成可读空格，
 * `\left`/`\right` 这类纯装饰命令剥掉；其余命令保留（裸文本仍可读出语义）。
 */
/**
 * 渲染失败回退裸文本前的清理（真机消息 5572 教训：`$$…\xRightarrow{\text{平方}}…$$`
 * 整块失败时 `\ ` `\!` 这类间距命令原样可见）。把换行/间距命令还原成可读空格，
 * `\left`/`\right` 这类纯装饰命令剥掉；其余命令保留（裸文本仍可读出语义）。
 *
 * 2026-11-19 性能：三个 Regex 原来**每次调用现场编译**——回退态在位图未到货前
 * 每帧每气泡都走这里，长会话里编译开销可观。提到模块级常量。
 */
private val FALLBACK_SPACING_RE = Regex("\\\\\\\\|\\\\[,;:! ]")
private val FALLBACK_DELIM_RE = Regex("\\\\left|\\\\right")
private val FALLBACK_WS_RE = Regex("\\s+")

internal fun fallbackText(latex: String): String = latex
    .replace(FALLBACK_SPACING_RE, " ")
    .replace(FALLBACK_DELIM_RE, "")
    .replace(FALLBACK_WS_RE, " ")
    .trim()

/**
 * 行内公式占位/行高上限（em）——**唯一定义**，预量与占位两处共用。
 *
 * 2026-11-16 用户截图「字体还是太小」：同一个量原先在两处各写一套（行高预量 3.4/6、
 * 占位 2.2/5.2/6）。占位给得比预量小 = 公式在已经让出空间的行里被等比缩水（用户看到的
 * 「太小」）；给得比预量大 = 顶穿上下行。合并成一个函数后不会再漂。
 *
 * - 矩阵/多行环境（`\begin{…}`）：自然高 4~6em，给小了整块变成蚂蚁字 → 6em
 * - 拆行版（gathered）：3 行自然高 ~4.5em → 5.2em
 * - 普通行内（含 \frac/\lim/\sum 这类高公式）：2.2em 足够容纳分子分母与下限
 */
internal fun mathCapEm(latex: String, gathered: Boolean): Float = when {
    latex.contains("\\begin{") -> 6f
    gathered -> 5.2f
    else -> 2.2f
}

/**
 * 上限按字号放大倍数同步放大——公式基准抬到正文口径后（[mathRenderSizePx]），
 * 表格/卡片里位图相对本行字号从 1.25em 变成 ~1.67em；上限不跟着放会把抬过的字号
 * 又压回去（等于白改）。倍数 = 1.0 时行为与旧版完全一致。
 */
internal fun mathHeightCapEm(baseCapEm: Float, scale: Float): Float =
    baseCapEm * scale.coerceAtLeast(1f)

/**
 * 公式渲染字号（px）：**行字号与正文字号取大**再乘系数。
 *
 * 2026-11-16 用户截图「字体还是太小」（会话里一张表格，三行 `\lim` 极限公式）：
 * 公式字号一直按**所在行**的字号 ×1.25 算，而表格格（[MdTable]）与聊天卡片
 * （ChatComponents）传的 style 是 `bodySmall`(12sp) → 同一道公式渲出来只有 15sp，
 * 放进正文（`bodyLarge` 16sp）却有 20sp。公式在这些格里是**主要内容**，不该跟着
 * 「辅助文字」的字号一起缩——数学符号本来就比汉字窄小，缩一档就明显糊。
 *
 * 取 max 而不是固定值：用户调大字号（fontScale）或在标题里写公式时，公式仍然跟着
 * 所在行变大；只有「行字号小于正文」的场景被抬到正文口径。
 *
 * 顺带修好预热键：`warmUpFormulas` 按正文 ×1.25 建键，此前表格/卡片里的公式键
 * 对不上，渲完也用不到（与 11-14「fontScale 对齐」同一类白渲）。纯函数，JVM 可测。
 */
internal fun mathRenderSizePx(rowFontPx: Float, bodyFontPx: Float): Float =
    maxOf(rowFontPx, bodyFontPx) * 1.25f

/**
 * 行内公式占位尺寸（em）——按位图**自然比例**换算，不再固定 1.4em。
 *
 * 2026-11-10 用户截图：行内的分数/极限符号小得看不清。根因不是渲染字号，而是占位高度
 * 写死 1.4em：`InlineTextContent` 会把位图缩放进占位框，高公式（\frac、\lim）自然高度
 * 是 2~3em，被压到 1.4em 就整体缩水一圈。这里按 height/fontPx 求出真实 em，上限 4.5em
 * 防个别公式把行高撑爆。
 */
internal fun inlinePlaceholderEm(
    bmpW: Int,
    bmpH: Int,
    fontPx: Float,
    maxWEm: Float = 14f,
    hCap: Float = 2.2f
): Pair<Float, Float> {    if (bmpH <= 0 || bmpW <= 0 || fontPx <= 0f) return 1.4f to 1.4f
    // 2026-11-10 二轮：完全按自然高度（最高 4.5em）会把行顶穿——Compose 的行高不会
    // 自动容纳这么大的行内内容，上下行文字压在公式上。改成**封顶 2.2em**：
    // 比旧的 1.4em 大一圈（分数不再缩水），又不至于越界；行距由调用方同步放大。
    // 2026-11-11：拆行（gathered）版允许更高（hCap=3.4f）——它就是靠「变高不变窄」
    // 换可读性的，高度仍由调用方的 maxMathEm 行距放大兜底。
    var hEm = (bmpH / fontPx).coerceIn(1.0f, hCap)
    var wEm = (bmpW.toFloat() / bmpH) * hEm
    // 超宽行内公式（长分式/矩阵）横向也会溢出 → 等比缩到容器宽以内
    //（maxWEm 由调用方按实际容器宽传入，默认 14em；2026-11-10 表格压字事故）
    val maxW = maxWEm.coerceAtLeast(2f)
    if (wEm > maxW) {
        val k = maxW / wEm
        wEm = maxW
        hEm *= k
    }
    return wEm to hEm
}

/**
 * 行内公式「高被压小」判定（纯函数，JVM 可测）：自然高 [naturalHEm] 被占位上限
 * [cappedHEm] 压掉超过 20%（capped < natural×0.8）时，不再硬缩——调用方把这条公式
 * 提升为独占一行的块级渲染。见 [inlinePlaceholderEm] 处 2026-11-22「公式过小」注释。
 */
internal fun tallInlineSqueezed(naturalHEm: Float, cappedHEm: Float): Boolean =
    naturalHEm > 0f && cappedHEm < naturalHEm * 0.8f
