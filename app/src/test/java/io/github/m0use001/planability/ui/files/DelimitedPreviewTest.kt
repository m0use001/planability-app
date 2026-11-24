package io.github.m0use001.planability.ui.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CSV/TSV 预览的解析（用户 2026-10-20：「纯文本格式的预览呢？例如 html」）。
 *
 * 表格预览最容易在两类输入上翻车，而这两类在 AI 生成的内容里都很常见：
 * - 单元格里带逗号（"张,三"）——按逗号硬切会把一行拆成两列；
 * - 单元格里带换行（多行备注）——按行硬切会把一条记录拆成两行。
 */
class DelimitedPreviewTest {

    @Test
    fun parsesPlainCsv() {
        val rows = parseDelimited("科目,成绩\n工程数学,88\n英语,90", ',')
        assertEquals(3, rows.size)
        assertEquals(listOf("科目", "成绩"), rows[0])
        assertEquals(listOf("工程数学", "88"), rows[1])
    }

    @Test
    fun keepsCommasInsideQuotes() {
        val rows = parseDelimited("姓名,备注\n\"张,三\",正常", ',')
        assertEquals(listOf("张,三", "正常"), rows[1])
    }

    @Test
    fun handlesEscapedQuotes() {
        val rows = parseDelimited("\"a\"\"b\",c", ',')
        assertEquals(listOf("a\"b", "c"), rows[0])
    }

    @Test
    fun handlesNewlineInsideQuotes() {
        val rows = parseDelimited("a,\"第一行\n第二行\"\nb,c", ',')
        assertEquals(2, rows.size)
        assertEquals("第一行\n第二行", rows[0][1])
    }

    @Test
    fun parsesTsvWithTabs() {
        val rows = parseDelimited("a\tb\n1\t2", '\t')
        assertEquals(listOf("a", "b"), rows[0])
        assertEquals(listOf("1", "2"), rows[1])
    }

    @Test
    fun dropsBlankLines() {
        val rows = parseDelimited("a,b\n\n\n1,2\n", ',')
        assertEquals(2, rows.size)
        assertTrue(rows.none { it.all { c -> c.isBlank() } })
    }
}
