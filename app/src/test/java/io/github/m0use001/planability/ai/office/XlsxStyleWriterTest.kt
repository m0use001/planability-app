package io.github.m0use001.planability.ai.office

import io.github.m0use001.planability.ai.ptl.OfficeWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipFile

/**
 * xlsx 生成的**样式**（用户 2026-10-20：「2 点都做」的第一点）。
 *
 * 以前导出的是裸数据：打开一屏白格子，分不清表头和数据行。
 * 现在要求：表头加粗+底色+居中、数据行细边框、列宽按内容估、冻结首行。
 * 这些都在 `xl/styles.xml` 与 `sheet1.xml` 的 `s=` 属性里，所以直接查包内容。
 */
class XlsxStyleWriterTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun make(): File {
        val f = File(tmp.root, "表.xlsx")
        OfficeWriter.writeXlsx(f, "科目\t成绩\t备注\n工程数学\t88\t通过\n英语\t95\t优秀")
        return f
    }

    @Test
    fun hasStylesPart() {
        ZipFile(make()).use { z ->
            assertTrue("缺 xl/styles.xml：Excel 会按无样式渲染", z.getEntry("xl/styles.xml") != null)
            val rels = z.getInputStream(z.getEntry("xl/_rels/workbook.xml.rels"))
                .readBytes().toString(Charsets.UTF_8)
            assertTrue("styles.xml 没有被 workbook 引用", rels.contains("styles.xml"))
            val types = z.getInputStream(z.getEntry("[Content_Types].xml"))
                .readBytes().toString(Charsets.UTF_8)
            assertTrue("Content_Types 里缺 styles 声明", types.contains("spreadsheetml.styles+xml"))
        }
    }

    @Test
    fun headerRowUsesHeaderStyle() {
        ZipFile(make()).use { z ->
            val xml = z.getInputStream(z.getEntry("xl/worksheets/sheet1.xml"))
                .readBytes().toString(Charsets.UTF_8)
            // 表头单元格 s="1"，数据单元格 s="2"
            assertTrue("表头应套样式 1：$xml", xml.contains("""<c r="A1" s="1""""))
            assertTrue("数据应套样式 2：$xml", xml.contains("""<c r="A2" s="2""""))
        }
    }

    @Test
    fun writesColumnWidthsAndFreezePane() {
        ZipFile(make()).use { z ->
            val xml = z.getInputStream(z.getEntry("xl/worksheets/sheet1.xml"))
                .readBytes().toString(Charsets.UTF_8)
            assertTrue("应有列宽定义", xml.contains("<cols>") && xml.contains("customWidth=\"1\""))
            assertTrue("首行应冻结", xml.contains("state=\"frozen\""))
        }
    }

    @Test
    fun numbersStayNumbers() {
        ZipFile(make()).use { z ->
            val xml = z.getInputStream(z.getEntry("xl/worksheets/sheet1.xml"))
                .readBytes().toString(Charsets.UTF_8)
            assertTrue("成绩应写为数字而不是文本", xml.contains("<v>88</v>"))
        }
    }

    @Test
    fun readGridStillWorksAfterStyling() {
        val g = XlsxEditor.readGrid(make()).second
        assertEquals("科目", g[0][0])
        assertEquals("88", g[1][1])
        assertEquals("优秀", g[2][2])
    }
}
