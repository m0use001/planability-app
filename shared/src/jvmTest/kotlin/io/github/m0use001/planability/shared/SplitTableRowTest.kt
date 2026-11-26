package io.github.m0use001.planability.shared

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 表格行切分（M2 迁移时补：原 app 侧没有直接单测，只有渲染层的间接覆盖）。
 * 重点钉住 2026-11-12 的根因——`$…$` 内的竖线不是列分隔符。
 */
class SplitTableRowTest {

    @Test
    fun `普通表格三列`() {
        assertEquals(listOf("a", "b", "c"), splitTableRow("| a | b | c |"))
    }

    @Test
    fun `公式里的竖线不当分隔符`() {
        val row = "| 5–6 | 用 \$|b_n-1|<\\frac1{100}\$ 逐步逼近 |"
        val cells = splitTableRow(row)
        assertEquals(2, cells.size)
        assertEquals("用 \$|b_n-1|<\\frac1{100}\$ 逐步逼近", cells[1])
    }

    @Test
    fun `转义竖线保留反斜杠`() {
        val cells = splitTableRow("| a | x\\|y |")
        assertEquals(2, cells.size)
        assertEquals("x\\|y", cells[1])
    }

    @Test
    fun `无首尾管道的行`() {
        assertEquals(listOf("a", "b"), splitTableRow("a | b"))
    }
}
