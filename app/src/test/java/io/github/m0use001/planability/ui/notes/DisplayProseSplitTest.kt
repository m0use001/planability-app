package io.github.m0use001.planability.ui.notes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** v56.2 块级公式句级连接词拆分（用户截图「其中」被挤出公式 → 数学/正文/数学 顺序块）。 */
class DisplayProseSplitTest {

    @Test
    fun 用户实录_连接词拆成三段顺序块() {
        val src = """D(x)=f'(c)+\alpha(x)，其中 \lim_{x\to c}\alpha(x)=0"""
        val parts = splitDisplayProse(src)!!
        assertEquals(3, parts.size)
        assertTrue(parts[0].second)
        assertTrue(parts[0].first.startsWith("D(x)"))
        assertFalse2(parts[1])
        assertEquals("，其中", parts[1].first)
        assertTrue(parts[2].second)
        assertTrue(parts[2].first.contains("\\lim_{x\\to c}"))
    }

    @Test
    fun 顶层text组_内容含CJK也算候选() {
        val src = """D(x)=f'(c)+\alpha(x), \text{其中 } \lim_{x\to c}\alpha(x)=0"""
        val parts = splitDisplayProse(src)!!
        assertEquals(3, parts.size)
        assertEquals("其中", parts[1].first)
        assertTrue(parts[2].first.contains("\\lim"))
    }

    @Test
    fun 括注桥接型不拆_走标签老路() {
        // 2026-11-13 拍板的合并形态：括注开头/结尾的 prose 是桥接标签
        assertNull(splitDisplayProse("""\text{（决定第一列}p, r\text{）}"""))
    }

    @Test
    fun 结构内text不候选_underbrace标签老路() {
        assertNull(splitDisplayProse("""x \underbrace{=}_{\text{可导}} y"""))
    }

    @Test
    fun 尾注释不拆_无后继数学走标签老路() {
        assertNull(splitDisplayProse("""x=1 \text{（解）}"""))
    }

    @Test
    fun 多连接词_单段拆分函数返回null_但整句混排接住() {
        // v56.2 时这里整行走标签（多 run 会碎片化）；v56.4 起交给 splitDisplaySentence
        // 做**行内整句混排**（不切块就不会碎），splitDisplayProse 本身仍只管单 run。
        assertNull(splitDisplayProse("a=1，其中 b=2 且 c=3"))
        val sent = splitDisplaySentence("a=1，其中 b=2 且 c=3")!!
        assertEquals(
            listOf("a=1", "，其中", "b=2", "且", "c=3"),
            sent.map { it.first }
        )
        assertEquals(listOf(true, false, true, false, true), sent.map { it.second })
    }

    /**
     * 用户截图（2026-11-14）：「出现中文被挤出」——块级公式里一句话夹着几段公式时，
     * 中文以前全被抽到公式下方成「说的是 · 趋近 · 趋近」，公式那头只剩
     * `lim…=L  x  c  f(x)  L` 一堆碎片。整句混排必须按源码顺序交替切。
     */
    @Test
    fun 用户截图_夹中文的整句按源码顺序交替切() {
        val src = """\lim_{x\to c}f(x)=L \quad\text{说的是}\quad x \text{ 趋近 } c,\quad f(x) \text{ 趋近 } L"""
        val sent = splitDisplaySentence(src)!!
        val kinds = sent.map { it.second }
        assertEquals("必须是 数学/中文 交替：$sent", listOf(true, false, true, false, true, false, true), kinds)
        assertTrue(sent[0].first.contains("\\lim"))
        assertEquals("说的是", sent[1].first)
        assertEquals("x", sent[2].first)
        assertEquals("趋近", sent[3].first)
        assertTrue("c 段应带上后面的 f(x)：${sent[4].first}", sent[4].first.startsWith("c"))
        assertEquals("趋近", sent[5].first)
        assertEquals("L", sent[6].first)
        // 渲染层：一整句走行内（display=false），中文与公式同段
        val segs = splitMathSegments("$$$src$$")
        assertTrue("混排数学段必须 display=false", segs.filterIsInstance<MathSeg.Math>().none { it.display })
        val joined = segs.joinToString("") { if (it is MathSeg.Math) it.latex else (it as MathSeg.Txt).text }
        assertTrue("中文不在原位：$joined", joined.contains("说的是"))
        assertTrue("中文被抽成标签了：${segs.filterIsInstance<MathSeg.Math>().map { it.labels }}",
            segs.filterIsInstance<MathSeg.Math>().all { it.labels.isBlank() })
    }

    @Test
    fun 推导链仍是标签老路_整句混排也不接() {
        // 909b74f5 拍板：箭头串起来的推论标签在公式下方连读
        assertNull(
            splitDisplaySentence("""1 \neq -1 \Rightarrow \text{双侧极限不存在} \Rightarrow \text{不可导}""")
        )
    }

    @Test
    fun 无CJK返回null() {
        assertNull(splitDisplayProse("""E=mc^{2}"""))
    }

    @Test
    fun 推导链整行走标签老路_箭头在场不拆() {
        // 909b74f5 拍板形态：推导链的中文是箭头串起来的推论标签（公式下方连读）
        assertNull(
            splitDisplayProse(
                """1 \neq -1 \Rightarrow \text{双侧极限不存在}，其中 \lim_{x\to c}\alpha(x)=0"""
            )
        )
    }

    private fun assertFalse2(p: Pair<String, Boolean>) = org.junit.Assert.assertFalse(p.second)
}
