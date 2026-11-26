package io.github.m0use001.planability.ui.notes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 2026-11-17 用户实报（第二次报「中文文字被挤出的问题还是没有修好」）+
 * 真机聊天库消息 7634 的**真源**：
 *
 * ```
 * $$ \text{费马引理（最值点处 }f'=0\text{）}\Rightarrow \text{Rolle（需三条件）}
 *     \Rightarrow \text{MVT}\Rightarrow \text{定理 B} $$
 * ```
 *
 * 旧行为（用户截图）：公式行只剩孤零零一个 **MVT**，下面一行是被拆散的中文
 * 「费马引理（最值点处 f'=0） ⇒ Rolle（需三条件） ⇒ · ⇒ 定理 B」——
 * 链从中间被撕开、箭头散成「⇒ · ⇒」，中文被"挤出"公式。
 *
 * 根因：`stripCjkLabels` 只把**含 CJK** 的 `\text{…}` 抽成标签，链中间的 ASCII 组
 * （`\text{MVT}`）留在公式里 → 链断成「公式 + 标签行」两截；且整块因此被判成
 * 「没有数学部分」，掉进行内分支，把 `$$` 定界符泄成字面量。
 */
class LabelChainSplitTest {

    /** 真机消息 7634 的原样（空格/换行按原文） */
    private val realChain =
        """$$ \text{费马引理（最值点处 }f'=0\text{）}\Rightarrow \text{Rolle（需三条件）}\Rightarrow \text{MVT}\Rightarrow \text{定理 B} $$"""

    @Test
    fun 用户实录_整条推导链并成一行_不再被撕开() {
        val segs = splitMathSegments(realChain)
        val maths = segs.filterIsInstance<MathSeg.Math>()
        assertEquals("应合成一条：$segs", 1, maths.size)
        val m = maths[0]
        assertTrue("必须是块级", m.display)
        assertEquals(
            "标签链顺序/内容不对：${m.labels}",
            "费马引理（最值点处 f'=0） ⇒ Rolle（需三条件） ⇒ MVT ⇒ 定理 B",
            m.labels
        )
        assertFalse("链被「 · 」切开了：${m.labels}", m.labels.contains("·"))
        assertEquals("公式本体应被标签链吃光（不再有悬空的 MVT）：${m.latex}", "", m.latex)
    }

    @Test
    fun 用户实录_不能泄出美元定界符() {
        val segs = splitMathSegments(realChain)
        val txt = segs.filterIsInstance<MathSeg.Txt>().joinToString("") { it.text }
        assertFalse("`$$` 定界符泄成了字面量：$txt", txt.contains("$"))
    }

    @Test
    fun 链上顺序照源码_四个节点都在() {
        val labels = splitMathSegments(realChain)
            .filterIsInstance<MathSeg.Math>()
            .map { it.labels }
            .joinToString("")
        val i1 = labels.indexOf("费马引理")
        val i2 = labels.indexOf("Rolle")
        val i3 = labels.indexOf("MVT")
        val i4 = labels.indexOf("定理 B")
        assertTrue("节点缺失：$labels", listOf(i1, i2, i3, i4).all { it >= 0 })
        assertTrue("顺序不对：$labels", i1 < i2 && i2 < i3 && i3 < i4)
    }

    @Test
    fun 纯文字说明块不泄出美元定界符() {
        // 2026-11-17 顺带修的旧洞：`$$ 其中 x 表示人数 $$` 以前掉进行内分支，
        // 把 `$$` 当字面量吐出来。
        val segs = splitMathSegments("$$ 其中 x 表示人数 $$")
        val txt = segs.filterIsInstance<MathSeg.Txt>().joinToString("") { it.text }
        assertFalse("`$$` 定界符泄成了字面量：$txt", txt.contains("$"))
        assertTrue("说明文字丢了：$txt", txt.contains("其中"))
    }

    @Test
    fun ascii链成员判据() {
        val latex = """\text{MVT}\Rightarrow \text{定理 B}"""
        val after = latex.indexOf("}") + 1
        assertTrue("MVT 后面紧跟箭头→CJK 组，应算链成员", followedByCjkText(latex, after))
        // 不是链成员的：箭头后面是数学，不是 CJK 组
        val latex2 = """\text{if } x>0"""
        val after2 = latex2.indexOf("}") + 1
        assertFalse("无箭头相连，不该算链成员", followedByCjkText(latex2, after2))
    }

    @Test
    fun 非链成员的ascii组仍留在公式里() {
        // `\text{if }` 后面没有 CJK 组 → 留在公式本体，不进标签
        val m = splitMathSegments("""$$\text{if } x>0$$""")
            .filterIsInstance<MathSeg.Math>()
            .single()
        assertTrue("ASCII 组被误抽成标签：${m.labels}", m.labels.isBlank())
        assertTrue("公式本体丢了 ASCII 组：${m.latex}", m.latex.contains("if"))
    }

    @Test
    fun 既有拍板不变_推导链仍走标签老路() {
        // 909b74f5：公式 + 推导链标签（公式本体是真数学时不动）
        val m = splitMathSegments(
            """$$1 \neq -1 \Rightarrow \text{双侧极限不存在} \Rightarrow \text{不可导}$$"""
        ).filterIsInstance<MathSeg.Math>().single()
        assertEquals("⇒ 双侧极限不存在 ⇒ 不可导", m.labels)
        assertTrue("公式本体不该被吃光：${m.latex}", m.latex.contains("neq"))
    }

    @Test
    fun 空公式段渲染层不许崩() {
        // MathSeg.Math("", true, labels) → LatexBlock 必须对空 latex 直接返回
        val src = File(System.getProperty("user.dir") ?: ".").absoluteFile
            .let { dir ->
                var d: File? = dir
                while (d != null && !File(d, "app/src/main/java/io/github/m0use001/planability").isDirectory) {
                    d = d.parentFile
                }
                File(requireNotNull(d) { "找不到仓库根" }, "app/src/main/java/io/github/m0use001/planability/ui/notes/MarkdownMath.kt")
            }
        val text = src.readText(Charsets.UTF_8)
        assertTrue(
            "LatexBlock 没处理空 latex（整块标签链会走进 jlatexmath）",
            text.contains("if (latex.isBlank())")
        )
    }
}
