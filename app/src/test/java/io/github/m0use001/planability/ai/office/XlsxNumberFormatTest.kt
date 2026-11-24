package io.github.m0use001.planability.ai.office

import io.github.m0use001.planability.ai.ptl.OfficeWriter
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * 数字格式与"规则选区跟随删行"（用户 2026-10-20「继续」补齐的两个尾巴）。
 *
 * 这两件事的共同点是：**文件没坏、但意思错了**。
 * 52% 显示成 0.52 用户还能自己换算；条件格式套在错行上，用户根本不会发现——
 * 只看到"颜色标错了一行"。所以宁可多写几个断言。
 */
class XlsxNumberFormatTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun make(): File {
        val f = File(tmp.root, "报表.xlsx")
        OfficeWriter.writeXlsx(
            f,
            "项目\t金额\t占比\t日期\n" +
                "工程数学\t1,234.50\t52%\t2026-10-15\n" +
                "英语\t98\t8.50%\t2026/11/1"
        )
        return f
    }

    @Test
    fun percentIsStoredAsFractionAndShownAsPercent() {
        val g = XlsxEditor.readGrid(make()).second
        assertEquals("52%", g[1][2])
        assertEquals("8.5%", g[2][2])
    }

    @Test
    fun dateIsStoredAsSerialAndShownAsDate() {
        val g = XlsxEditor.readGrid(make()).second
        assertEquals("2026-10-15", g[1][3])
        assertEquals("2026-11-01", g[2][3])
    }

    @Test
    fun amountKeepsThousandsSeparatorInPreview() {
        val g = XlsxEditor.readGrid(make()).second
        assertEquals("1,234.50", g[1][1])
    }

    @Test
    fun stylesCarryNumberFormats() {
        ZipFile(make()).use { z ->
            val sx = z.getInputStream(z.getEntry("xl/styles.xml")).readBytes().toString(Charsets.UTF_8)
            assertTrue("应有千分位格式", sx.contains("#,##0.00"))
            assertTrue("应有百分比格式", sx.contains("0.0%"))
            assertTrue("应有日期格式", sx.contains("yyyy-mm-dd"))
            val sheet = z.getInputStream(z.getEntry("xl/worksheets/sheet1.xml"))
                .readBytes().toString(Charsets.UTF_8)
            assertTrue("百分比单元格应套样式 4", sheet.contains("s=\"4\""))
            assertTrue("日期单元格应套样式 5", sheet.contains("s=\"5\""))
        }
    }

    @Test
    fun deleteRowAlsoShiftsConditionalFormattingAndValidation() {
        val src = File(tmp.root, "rules.xlsx")
        writeBook(
            src,
            "<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><sheetData>" +
                "<row r=\"1\"><c r=\"A1\" t=\"inlineStr\"><is><t>h</t></is></c></row>" +
                "<row r=\"2\"><c r=\"A2\"><v>1</v></c></row>" +
                "<row r=\"3\"><c r=\"A3\"><v>2</v></c></row>" +
                "</sheetData>" +
                "<conditionalFormatting sqref=\"A2:A3\">" +
                "<cfRule type=\"cellIs\" operator=\"greaterThan\" priority=\"1\"><formula>A3</formula></cfRule>" +
                "</conditionalFormatting>" +
                "<dataValidations count=\"1\"><dataValidation type=\"list\" sqref=\"B2:B3\">" +
                "<formula1>\"是,否\"</formula1></dataValidation></dataValidations>" +
                "</worksheet>"
        )
        val out = File(tmp.root, "rules_out.xlsx")
        XlsxEditor.apply(src, JSONArray("""[{"op":"delRow","row":2}]"""), out)
        val xml = ZipFile(out).use { z ->
            z.getInputStream(z.getEntry("xl/worksheets/sheet1.xml")).readBytes().toString(Charsets.UTF_8)
        }
        assertTrue("条件格式选区应收缩为 A2:A2：$xml", xml.contains("sqref=\"A2:A2\""))
        assertTrue("数据验证选区也应上移：$xml", xml.contains("sqref=\"B2:B2\""))
        assertTrue("规则公式里的引用也要改（A3→A2）：$xml", xml.contains("<formula>A2</formula>"))
    }

    @Test
    fun writesSampleIntoBuildDirForManualCheck() {
        // 落一份到 build/（gitignore 内），方便 adb push 到手机用表格应用看真实格式效果
        val f = File("build/xlsx-sample.xlsx")
        f.parentFile?.mkdirs()
        OfficeWriter.writeXlsx(
            f,
            "项目\t金额\t占比\t日期\n" +
                "工程数学\t1,234.50\t52%\t2026-10-15\n" +
                "英语\t98.00\t8.5%\t2026-11-01\n" +
                "Python\t12,000.00\t100%\t2026-12-31"
        )
        assertTrue("样例文件没写出来", f.exists() && f.length() > 0)
    }

    // ---------- 手写一个带规则的最小工作簿 ----------

    private fun writeBook(f: File, sheet: String) {
        ZipOutputStream(FileOutputStream(f)).use { z ->
            fun put(name: String, s: String) {
                z.putNextEntry(ZipEntry(name))
                z.write(s.toByteArray(Charsets.UTF_8))
                z.closeEntry()
            }
            put(
                "[Content_Types].xml",
                "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
                    "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">" +
                    "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>" +
                    "<Default Extension=\"xml\" ContentType=\"application/xml\"/>" +
                    "<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>" +
                    "<Override PartName=\"/xl/worksheets/sheet1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>" +
                    "</Types>"
            )
            put(
                "_rels/.rels",
                "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
                    "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
                    "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/>" +
                    "</Relationships>"
            )
            put(
                "xl/workbook.xml",
                "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
                    "<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" " +
                    "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\">" +
                    "<sheets><sheet name=\"Sheet1\" sheetId=\"1\" r:id=\"rId1\"/></sheets></workbook>"
            )
            put(
                "xl/_rels/workbook.xml.rels",
                "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
                    "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
                    "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet1.xml\"/>" +
                    "</Relationships>"
            )
            put("xl/worksheets/sheet1.xml", sheet)
        }
    }
}
