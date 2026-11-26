package io.github.m0use001.planability.core

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 2026-11-12「数学显示严重问题」根修守卫。
 *
 * 用户截图里表格错列、`$` 裸奔、公式整段不渲染。取证（◆ 原文）发现 AI 的
 * markdown 本身是对的，坏在渲染端**整行 split('|')** 把行内公式里的竖线当列分隔符：
 * `$|b_n-1|<\frac1{100}$`、`(|q|<1)` 全被劈开。
 *
 * 这里用消息里的原始行做夹具（一字不改），钉住：
 * 1) `$…$` 内的竖线不切；2) 表格结构照常（首尾 `|` 剥掉、空壳尾格丢弃）；
 * 3) `\|` 转义不被误当分隔符；4) 纯文本行零行为变化。
 */
class MarkdownTableSplitTest {

    @Test
    fun `行内公式里的竖线不是列分隔符 - 真实消息行`() {
        val row = "| 5–6 | 用 \$|b_n-1|<\\frac1{100},\\frac1{1000},\\frac1{10000}\$ 逐步逼近 |"
        val cells = splitTableRow(row)
        assertEquals(2, cells.size)
        assertEquals("5–6", cells[0])
        assertEquals("用 \$|b_n-1|<\\frac1{100},\\frac1{1000},\\frac1{10000}\$ 逐步逼近", cells[1])
    }

    @Test
    fun `例2 行 - 括号竖线不再把公式劈三段`() {
        val row = "| 9 | 例2：\$\\lim q^n=0\\ (|q|<1)\$ |"
        val cells = splitTableRow(row)
        assertEquals(2, cells.size)
        assertEquals("9", cells[0])
        assertEquals("例2：\$\\lim q^n=0\\ (|q|<1)\$", cells[1])
    }

    @Test
    fun `转义竖线保留字面 - 不当分隔符`() {
        val cells = splitTableRow("| a | \\|x\\| |")
        assertEquals(2, cells.size)
        assertEquals("a", cells[0])
        assertEquals("\\|x\\|", cells[1])
    }

    @Test
    fun `无公式的普通表格零行为变化`() {
        assertEquals(listOf("步", "内容", "性质"), splitTableRow("| 步 | 内容 | 性质 |"))
        assertEquals(listOf("1", "解不动点方程"), splitTableRow("|1|解不动点方程|"))
    }

    @Test
    fun `公式跨多格的整行 - 竖线成对出现也稳`() {
        val row = "| 8 | EX1：\$\\lim\\left(1+\\dfrac{(-1)^n}{n}\\right)=1\$ |"
        val cells = splitTableRow(row)
        assertEquals(2, cells.size)
        assertEquals("8", cells[0])
        assertEquals("EX1：\$\\lim\\left(1+\\dfrac{(-1)^n}{n}\\right)=1\$", cells[1])
    }
}
