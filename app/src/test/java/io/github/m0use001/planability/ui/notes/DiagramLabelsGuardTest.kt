package io.github.m0use001.planability.ui.notes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 全部图种的标签长文本守卫（2026-11-17 用户「其他图也要检查」）。
 *
 * 鱼骨图那类压字的病根（**布局假设标签很短**）在别的图里也存在：
 * ①流程图：节点盒宽已被 46% 画布宽截断，但文字按自然宽居中画 → 溢出盒子压邻居；
 * ②树形图：节点文字按自然宽画在 colW 里 → 长中文直接压到相邻列；
 * ③热力图：列头超宽压邻居列；
 * ④雷达图：维度名只夹了左边界，右侧顶点标签画到画布外。
 * 修法统一：**先按可用宽换行/截断（wrapByWidth），再逐行居中画**。
 */
class DiagramLabelsGuardTest {

    private fun source(moduleRel: String): String {
        val cands = listOf(File(moduleRel), File("app", moduleRel))
        val f = cands.firstOrNull { it.isFile }
        assertTrue("源码不在了：$moduleRel（候选：${cands.map { it.path }}）", f != null)
        return f!!.readText(Charsets.UTF_8).replace("\r\n", "\n")
    }

    private val diagrams: String
        get() = source("src/main/java/io/github/m0use001/planability/ui/notes/MarkdownDiagrams.kt")

    // ---------- 纯行为：wrapByWidth ----------

    private fun width(t: String): Float = t.length * 10f

    @Test
    fun `装得下就不换行`() {
        assertEquals(listOf("短标签"), wrapByWidth("短标签", 100f, ::width, 3))
    }

    @Test
    fun `超宽按字断行`() {
        val out = wrapByWidth("一二三四五六七八九十", 40f, ::width, 5)
        assertTrue("应断成多行：$out", out.size >= 2)
        out.forEach { assertTrue("单行超宽：$it", width(it) <= 40f) }
        assertEquals("一二三四五六七八九十", out.joinToString(""))
    }

    @Test
    fun `超过行数上限末行加省略号`() {
        val out = wrapByWidth("一二三四五六七八九十十一十二", 30f, ::width, 2)
        assertEquals(2, out.size)
        assertTrue("末行应带省略号：$out", out.last().endsWith("…"))
        out.forEach { assertTrue("单行超宽：$it", width(it) <= 30f) }
    }

    @Test
    fun `空文本与零行返回空`() {
        assertTrue(wrapByWidth("   ", 40f, ::width, 2).isEmpty())
        assertTrue(wrapByWidth("有内容", 40f, ::width, 0).isEmpty())
    }

    // ---------- 结构守卫：每个图都必须先换行再画 ----------

    @Test
    fun `流程图节点文字按盒宽换行`() {
        val seg = diagrams.substringAfter("fun FlowBlock(").substringBefore("/** 树形图")
        assertTrue("流程图节点文字必须走 wrapByWidth 换行", seg.contains("wrapByWidth(b.text, innerFlow,"))
        assertTrue("换行后必须逐行居中画（不能再用自然宽居中单行）",
            seg.contains("flowLines.forEachIndexed"))
    }

    @Test
    fun `树形图节点文字按列宽换行`() {
        val seg = diagrams.substringAfter("fun TreeBlock(").substringBefore("private fun fmtNum")
        assertTrue("树形图节点文字必须走 wrapByWidth 换行", seg.contains("wrapByWidth(n.text, innerTree,"))
        assertTrue("换行后必须逐行居中画", seg.contains("treeLines.forEachIndexed"))
    }

    @Test
    fun `热力图列头按列宽截断`() {
        val seg = diagrams.substringAfter("fun HeatmapBlock(").substringBefore("// ==================== 鱼骨图 v2")
        assertTrue("热力图列头必须按列宽截断（wrapByWidth）", seg.contains("wrapByWidth(name, colInner,"))
    }

    @Test
    fun `雷达图维度名两侧都要夹取`() {
        val seg = diagrams.substringAfter("fun RadarBlock(").substringBefore("fun HeatmapBlock(")
        assertTrue("雷达图维度名必须夹在画布内（右侧也要夹）",
            seg.contains("lx.coerceIn(0f, (w - lt.size.width)"))
    }
}
