package io.github.m0use001.planability.ui.notes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 2026-11-12 用户截图（积分表第三行 `\ln\lvert x\rvert+C` 整格裸显）+「表格加自适应
 * 左右滑动，适应长公式」两条回归守卫。
 *
 * 竖线族实锤链：设备同行其余公式全渲染、唯独含 \lvert/\rvert 的格子走「解析失败→
 * 原文裸显」回退；jlatexmath-android 0.2.0 的 AAR（TeXSymbols.xml）只登记
 * \vert/\Vert/\mid，jar 全文无 lvert/rvert（早先 grep 到的 "lvert" 是 \lvertneqq）。
 * 修复=normalizeLatex 进解析器前改写为语义等价的 \vert/\Vert。
 *
 * 表格横滑修=MdTable 按列内容估算自然宽，装不下容器才开 horizontalScroll（手势登记
 * hScrollZone 与代码块同口径）；装得下维持 weight 铺满。本文件刻意独立于
 * MarkdownMathTest.kt（该文件有 phone-b 的拆行 WIP 在途，不同文件并发）。
 */
class MarkdownVertTableGuardTest {

    private fun read(rel: String): String {
        val roots = listOf("app/src/main/java/io/github/m0use001/planability/", "src/main/java/io/github/m0use001/planability/")
        roots.forEach { r ->
            val f = java.io.File(r + rel)
            if (f.isFile) return f.readText()
        }
        return java.io.File("app/src/main/java/io/github/m0use001/planability/" + rel).readText()
    }

    private fun mathLatexOf(text: String): String =
        splitMathSegments(text).filterIsInstance<MathSeg.Math>().single().latex

    /** 积分表第三行第二格（用户截图原样）：改写后 jlatexmath 才可能解析成功 */
    @Test
    fun `lvert rvert 改写为 vert`() {
        assertEquals(
            "\\ln\\vert x\\vert+C",
            mathLatexOf("$\\ln\\lvert x\\rvert+C$")
        )
    }

    @Test
    fun `lVert rVert 改写为 Vert`() {
        assertEquals("\\Vert x\\Vert", mathLatexOf("$\\lVert x\\rVert$"))
    }

    /** 已是 \vert 的公式原样透传（不重复改写、不误删） */
    @Test
    fun `vert 原样保留`() {
        assertEquals("\\ln\\vert x\\vert+C", mathLatexOf("$\\ln\\vert x\\vert+C$"))
    }

    /** 负向断言：\lvertneqq 是 TeXSymbols.xml 里真登记的命令，不能被前缀误伤 */
    @Test
    fun `lvertneqq 不被误伤`() {
        val latex = mathLatexOf("\$x\\lvertneqq y$")
        assertTrue("lvertneqq 被前缀改写误伤：$latex", latex.contains("\\lvertneqq"))
        assertFalse("lvertneqq 不该被改成 vert：$latex", latex.contains("\\vertneqq y") && !latex.contains("\\lvertneqq"))
    }

    /** 列宽估算：\command 折 1 字符、括号/上下标符不计，CJK 记 2 */
    @Test
    fun `estLen 公式源码折算渲染宽`() {
        // 原始源码 22 字符；渲染出来约 10 个字符宽（\frac 记 1、{}^ 不计）
        assertEquals(10, estLen("\$\\frac{x^{r+1}}{r+1}+C\$"))
        assertEquals(4, estLen("步骤"))            // CJK ×2
        assertEquals(3, estLen("\$\\sin x\$"))           // \sin→x：x 空格 x
    }

    /** 表格横滑：overflow 开档 + hScrollZone 手势登记 + fill 模式（weight）保留 */
    @Test
    fun `MdTable 自适应横滑结构`() {
        val src = read("ui/notes/NotesScreen.kt")
        val block = src
            .substringAfter("private fun MdTable")
            .substringBefore("2026-11-12 表格列宽估算")
        fun tblBlockContains(needle: String) = block.contains(needle)
        assertTrue("MdTable 没登记 hScrollZone（聊天页右滑抽屉会抢表格横滑手势）",
            block.contains(".hScrollZone()"))
        assertTrue("MdTable 没接 horizontalScroll（长公式表格无从滑动）",
            tblBlockContains(".horizontalScroll(tblHs)"))
        // 2026-11-21 瞬移根治②：翻支判定改 effContainerPx（首帧屏宽兜底）+ 数学表预判横滑，
        // 冷进场不再「先 fit 测完再翻」——锚点条目高度骤变 = fling 中途瞬移实锤
        // （追踪器读数：突跳 1618px/11ms[手0程0]，滚动重锚定）。
        assertTrue("没有 overflow 开档判断（每张表都滑动=窄表格行为退化）",
            block.contains("val overflow = effContainerPx > 0"))
        assertTrue("首帧容器宽没量到时必须用屏宽兜底（否则每次进场先走错分支再翻一次）",
            block.contains("screenWidthDp.dp.toPx()"))
        assertTrue("数学表必须首帧直进横滑分支（挤压信号只有数学格会发，等实测再翻=瞬移）",
            block.contains("hasMathCell && estRatio > 0.85f"))
        assertTrue("装得下容器的表必须维持 weight 铺满（fill 分支丢了）",
            block.contains("weight(weights[c])"))
        // 两种模式都要量容器宽（scroll 分支量外层 Row、fill 分支量 Column），否则来回抖
        assertEquals("onSizeChanged 应恰好两处（两种模式各量一次容器宽）",
            2, Regex("\\.onSizeChanged \\{ containerPx").findAll(block).count())
    }

    /** 2026-11-12 用户「组件/图的横滑跟右滑菜单手势冲突」：屏缘豁免 + 图表块登记 */
    @Test
    fun `横滑与右滑菜单共存`() {
        val nav = read("app/NavShell.kt")
        assertTrue("抽屉手势缺屏缘豁免（edgeSwipeDrawerOpen + swipeRightToOpen 两处都要）",
            Regex("down\\.position\\.x > edgeExemptPx").findAll(nav).count() >= 2)
        assertTrue("豁免带宽没定义", nav.contains("28.dp.toPx()"))
        val diagram = read("ui/notes/MarkdownDiagrams.kt")
        // 图表块的 horizontalScroll 分支前必须登记 hScrollZone（否则抽屉抢图）
        val scrollBranch = diagram.substringAfter("natWidthDp > maxWidth")
        assertTrue("图表块横滑没登记 hScrollZone（11-09 代码块同款病灶复发）",
            scrollBranch.contains(".hScrollZone()"))
    }

    /** 2026-11-13 用户「所有长内容的横向滑动条要可见，不然不知道能滑」：
     *  5 处长内容容器（代码块/表格/块级公式/图示/CSV 查看器）都必须挂 hScrollHint，
     *  且 **hScrollHint 必须在 horizontalScroll 之前**（外层=视口层）：挂在后面量到
     *  的是内容宽，thumb 被视口裁成满屏灰线、也不随滚动移动（logcat w=2930 实锤）。 */
    @Test
    fun `长内容容器都要有可见滑动指示条`() {
        fun notesBlock(marker: String): String {
            val s = read("ui/notes/NotesScreen.kt")
            val i = s.indexOf(marker)
            assertTrue("找不到锚点 $marker", i >= 0)
            return s.substring(i, (i + 3000).coerceAtMost(s.length))
        }
        fun assertHintBeforeScroll(where: String, block: String, hs: String) {
            val h = block.indexOf(".hScrollHint($hs)")
            val sc = block.indexOf(".horizontalScroll($hs)")
            assertTrue("$where 没挂 hScrollHint($hs)", h >= 0)
            assertTrue("$where 没接 horizontalScroll($hs)", sc >= 0)
            assertTrue("$where 的 hScrollHint 必须在 horizontalScroll 之前（内容层=灰线 bug 回归）", h < sc)
        }
        assertHintBeforeScroll("代码块", notesBlock("val codeHs = rememberScrollState()"), "codeHs")
        assertHintBeforeScroll("表格", read("ui/notes/NotesScreen.kt").substringAfter("private fun MdTable"), "tblHs")
        assertHintBeforeScroll("块级公式", read("ui/notes/MarkdownMath.kt").substringAfter("fun LatexBlock"), "hState")
        assertHintBeforeScroll("图示", read("ui/notes/MarkdownDiagrams.kt").substringAfter("natWidthDp > maxWidth"), "diagramHs")
        assertHintBeforeScroll("CSV 查看器", read("ui/files/TextDocScreens.kt").substringAfter("val cellWidth = 132.dp"), "csvHs")
        // 指示条必须自适应：装得下（maxValue==0）不画；M3E 形态（用户三校：读得出滑到哪、
        // 不要 Windows 桌面样式）=「surfaceContainerHighest 细轨 + onSurfaceVariant 厚滑块
        // 骑轨」，滑块封顶 50% 视口、保底 40dp；中性色（R11 装饰件不上 primary）
        val hint = read("ui/common/HScrollHint.kt")
        assertTrue("hScrollHint 不再自适应（装得下也画=视觉噪音）",
            hint.contains("range <= 0") || hint.contains("maxValue <= 0"))
        // 2026-11-19 更新字面量：phone-b 的 d2e72be8 把几何提成纯函数 thumbWidthPx(viewportPx,…)
        // 后，封顶写作 `viewportPx * 0.5f`、保底写作 `minOf(minPx, maxW)`，旧断言钉的
        // `w * 0.5f` 只活在测试文件里了 —— 语义（封顶 50% 视口 + 保底 40dp）一条没少，
        // 所以改钉当前形态；顺带把**空区间收敛**也钉上（那才是 11-18 真机闪退的根因）。
        // 行为侧已有 HScrollHintGeometryTest 穷举 thumbWidthPx，这里是源码结构的第二道。
        assertTrue("滑块没封顶（内容略超宽时会画出横贯灰线而不是滑块）",
            hint.contains("viewportPx * 0.5f") && hint.contains("THUMB_MIN"))
        assertTrue("保底没按封顶收敛 —— 视口窄于 2×保底时 coerceIn 空区间会抛（2026-11-18 真机首帧必崩）",
            hint.contains("minOf(minPx, maxW)"))
        assertTrue("不是 M3 中性配色（轨道/滑块同基色透明度阶梯 10%/45%，跨主题保证对比）",
            hint.contains("onSurface.copy(alpha = 0.10f)") && hint.contains("onSurface.copy(alpha = 0.45f)"))
        assertTrue("滑块与轨道必须同画（双层骑轨=位置感）",
            Regex("drawRoundRect\\([\\s\\S]*?trackColor[\\s\\S]*?drawRoundRect\\([\\s\\S]*?thumbColor").containsMatchIn(hint))
        assertTrue("装饰件不许用 primary 强调色（R11）",
            !hint.contains("colorScheme.primary"))
    }

    /** 2026-11-14 用户「去看中文混排那项」（某次会话 AI 测试消息原样，AI 自己标注
     *  「中文入公式最容易崩，重点看这条」）：行内公式里的 CJK 必须拆成正文+数学渲染，
     *  不许整段连 `$` 一起裸显。 */
    @Test
    fun `中文混排行内公式拆分渲染`() {
        // `$\text{体积}=\frac{4}{3}\pi r^{3}$` → Txt("体积") + Math("=\frac…")
        run {
            val segs = splitMathSegments("$\\text{体积}=\\frac{4}{3}\\pi r^{3}$")
            assertTrue("整段裸显回归（没拆出正文）", segs.any { it is MathSeg.Txt && (it as MathSeg.Txt).text.contains("体积") })
            assertTrue("没拆出可渲染的数学段", segs.any { it is MathSeg.Math })
        }
        // `$\text{极限}\lim_{x\to 0}$`
        run {
            val segs = splitMathSegments("$\\text{极限}\\lim_{x\\to 0}$")
            val txt = segs.filterIsInstance<MathSeg.Txt>().joinToString("") { it.text }
            assertTrue("「极限」没落成正文：$txt", txt.contains("极限"))
            assertTrue("\\lim 没落成数学段", segs.filterIsInstance<MathSeg.Math>().any { it.latex.contains("\\lim") })
        }
        // `$\text{当且仅当 }x=0$`（\text 内 ASCII+中文混合）
        run {
            val segs = splitMathSegments("$\\text{当且仅当 }x=0$")
            val txt = segs.filterIsInstance<MathSeg.Txt>().joinToString("") { it.text }
            assertTrue("「当且仅当」没落成正文：$txt", txt.contains("当且仅当"))
            assertTrue("x=0 没落成数学段", segs.filterIsInstance<MathSeg.Math>().any { it.latex.contains("x=0") })
        }
        // 纯函数直测：裸中文（模型常不写 \text）
        val pieces = splitInlineCjk("速度=\\frac{s}{t}")
        assertTrue("裸中文没拆出正文", pieces.any { !it.second && it.first.contains("速度") })
        assertTrue("数学段丢了", pieces.any { it.second && it.first.contains("\\frac") })
    }

    /** 2026-11-13 用户两连校准（长表格没条/刚好占满有条）：
     *  开滑判定必须含**实测挤压**信号（MdInlineText 上报自然宽），不许只靠源码估算；
     *  块级公式阈值必须有余量（1.04f），差几像素不给假滑条。 */
    @Test
    fun `开滑判定用实测挤压且块级公式有余量阈值`() {
        val tbl = read("ui/notes/NotesScreen.kt").substringAfter("private fun MdTable")
        assertTrue("表格丢了实测挤压信号（clampedEm 状态表）",
            tbl.contains("mutableStateMapOf<Int, Float>()"))
        assertTrue("表格没有 reportClamped 棘轮（重复上报会抖）",
            tbl.contains("fun reportClamped(c: Int, em: Float)"))
        assertTrue("开滑判定只剩估算、没有实测挤压（长表格没条的根因回来了）",
            tbl.contains("totalDp.toPx()") && tbl.contains("|| squeezed"))
        assertTrue("滑动模式列宽没按实测自然宽适配（格内公式仍会被压）",
            tbl.contains("colDpFit"))
        val latex = read("ui/notes/MarkdownMath.kt")
        assertTrue("MdInlineText 没有挤压上报通道（onClamped）",
            latex.contains("onClamped: ((Float) -> Unit)? = null"))
        assertTrue("上报必须走 SideEffect（组合期写状态=重组风暴）",
            latex.contains("SideEffect") && latex.contains("clampedEm > 0f"))
        assertTrue("块级公式没有 4% 余量阈值（刚好占满的公式会被塞假滑条）",
            latex.contains("containerPx * 1.04f"))
    }
}
