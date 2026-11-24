package io.github.m0use001.planability.ui.notes

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 2026-11-17 用户第二批报障（两张截图）：
 *
 * ① 列表项里的长行内公式 —— `- **$f'>0$ ⇒ …**：任取 $x_1<x_2$，
 *    $f(x_2)-f(x_1)=f'(c)(x_2-x_1)>0$。（3.1 里"导数正判单调"这条，证明就在这里。）`
 *    真机：公式被按关系符拆成两行位图塞在段落里，后面的文字贴到第一行右侧继续排，
 *    行距又被整体撑大 → 「文字被挤出、中间一个空洞」。
 *    修：拆行公式**独占一行**（段落切成若干流式片段 + LatexBlock）。
 *
 * ② 引用块里的 `\big` —— `$s'=e^{某因子}\times\big[$结论里的式子$\big]$`
 *    真机：`\times\big[结论里的式子]` 原样裸显（jlatexmath 没有 `\big`），
 *    上标组的中文抽走后还留下孤零零的 `}`。
 */
class InlineLatexLayoutTest {

    private fun source(rel: String): String {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null && !File(dir, "app/src/main/java/io/github/m0use001/planability").isDirectory) {
            dir = dir.parentFile
        }
        val f = File(requireNotNull(dir) { "找不到仓库根" }, "app/src/main/java/io/github/m0use001/planability/$rel")
        assertTrue("$rel 不在了", f.isFile)
        return f.readText(Charsets.UTF_8)
    }

    private val math by lazy { source("ui/notes/MarkdownMath.kt") }

    @Test
    fun 大号括号命令要剥掉_不许整条公式回退裸显() {
        // 真机消息的原样（简化成一段）
        val segs = splitMathSegments("""使 ${'$'}s'=e^{某因子}\times\big[${'$'}结论${'$'}\big]${'$'}。""")
        val latex = segs.filterIsInstance<MathSeg.Math>().joinToString("|") { it.latex }
        assertFalse("`\\big` 没剥掉，整段会渲染失败回退源码：$latex", latex.contains("\\big"))
        assertFalse("`\\Big` 系列也要剥：$latex", latex.contains("\\Big"))
    }

    @Test
    fun 中文独占上标组时连壳掏空_不许留孤零零的花括号() {
        val segs = splitMathSegments("""使 ${'$'}s'=e^{某因子}\times[a]${'$'} 成立""")
        for (s in segs.filterIsInstance<MathSeg.Math>()) {
            assertFalse("上标开壳残留：${s.latex}", s.latex.trimEnd().endsWith("^{"))
            assertFalse("上标开壳残留：${s.latex}", s.latex.trimEnd().endsWith("_{"))
            assertFalse("闭壳残留（会渲成字面量 }）：${s.latex}", s.latex.trimStart().startsWith("}"))
        }
    }

    @Test
    fun 拆行公式必须独占一行() {
        val s = math
        assertTrue(
            "MdInlineText 没有「拆行公式独占一行」的分支（会塞在段落里把文字挤到旁边）",
            s.contains("val blockIdx = segs.indices.filter")
        )
        assertTrue("没有用 Column 切片段", s.contains("Column(modifier.then(capCapture))"))
        assertTrue("独占一行的公式没走 LatexBlock", s.contains("LatexBlock(gatheredOf("))
        // 片段回拼时，标签链（空公式）要回成纯文字，不能丢
        assertTrue("片段回拼丢了标签链文字", s.contains("if (s.latex.isBlank()) append(s.labels)"))
    }

    @Test
    fun 行内语境里空公式的标签链不许丢字() {
        assertTrue(
            "MathSeg.Math 空 latex 时没把 labels 当文字续写（整段中文会消失）",
            math.contains("if (s.latex.isBlank()) {") && math.contains("appendMarkAware(s.labels)")
        )
    }
}
