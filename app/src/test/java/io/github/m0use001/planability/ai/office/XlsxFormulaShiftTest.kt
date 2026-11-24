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
 * 删行后的**引用修正**（用户 2026-10-20：「2 点都做」的第二点）。
 *
 * 删行只把行挪上去是不够的：原来写着 `SUM(B2:B4)` 的合计行会指向错数据，
 * 而用户看到的是"表还在、数不对"——比直接报错更难发现。
 * 这里钉住 Excel 的三条规则：下面的引用上移、指向被删行的引用变 #REF!、区间收缩一行。
 */
class XlsxFormulaShiftTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun book(sheet: String): File {
        val f = File(tmp.root, "book.xlsx")
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
                    "<sheets><sheet name=\"明细\" sheetId=\"1\" r:id=\"rId1\"/></sheets></workbook>"
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
        return f
    }

    private fun sheetOf(f: File): String = ZipFile(f).use { z ->
        z.getInputStream(z.getEntry("xl/worksheets/sheet1.xml")).readBytes().toString(Charsets.UTF_8)
    }

    private fun deleteRowOf(src: File, row: Int): String {
        val out = File(tmp.root, "out.xlsx")
        XlsxEditor.apply(src, JSONArray("""[{"op":"delRow","row":$row}]"""), out)
        return sheetOf(out)
    }

    @Test
    fun rangeShrinksWhenDeletedRowInsideIt() {
        val sheet = "<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><sheetData>" +
            "<row r=\"1\"><c r=\"A1\" t=\"inlineStr\"><is><t>合计</t></is></c><c r=\"B1\"><f>SUM(B2:B4)</f><v>0</v></c></row>" +
            "<row r=\"2\"><c r=\"A2\" t=\"inlineStr\"><is><t>x</t></is></c></row>" +
            "<row r=\"3\"><c r=\"A3\" t=\"inlineStr\"><is><t>y</t></is></c></row>" +
            "<row r=\"4\"><c r=\"A4\" t=\"inlineStr\"><is><t>z</t></is></c></row>" +
            "</sheetData></worksheet>"
        val xml = deleteRowOf(book(sheet), 3)
        assertTrue("区间应收缩成 B2:B3：$xml", xml.contains("SUM(B2:B3)"))
    }

    @Test
    fun referenceToDeletedRowBecomesRefError() {
        val sheet = "<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><sheetData>" +
            "<row r=\"1\"><c r=\"A1\" t=\"inlineStr\"><is><t>h</t></is></c></row>" +
            "<row r=\"2\"><c r=\"B2\"><v>10</v></c></row>" +
            "<row r=\"3\"><c r=\"C3\"><f>B2*2</f><v>0</v></c></row>" +
            "</sheetData></worksheet>"
        val xml = deleteRowOf(book(sheet), 2)
        assertTrue("指向被删行应 #REF!：$xml", xml.contains("#REF!"))
    }

    @Test
    fun referenceBelowDeletionShiftsUp() {
        val sheet = "<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><sheetData>" +
            "<row r=\"1\"><c r=\"A1\"><f>B5+1</f><v>0</v></c></row>" +
            "<row r=\"2\"><c r=\"B2\"><v>1</v></c></row>" +
            "<row r=\"5\"><c r=\"B5\"><v>2</v></c></row>" +
            "</sheetData></worksheet>"
        val xml = deleteRowOf(book(sheet), 2)
        assertTrue("引用应从 B5 变 B4：$xml", xml.contains("B4+1"))
    }

    @Test
    fun referenceAboveDeletionUntouched() {
        val sheet = "<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><sheetData>" +
            "<row r=\"1\"><c r=\"A1\"><f>B1*3</f><v>0</v></c></row>" +
            "<row r=\"2\"><c r=\"B1\"><v>1</v></c></row>" +
            "<row r=\"3\"><c r=\"C3\"><v>2</v></c></row>" +
            "</sheetData></worksheet>"
        val xml = deleteRowOf(book(sheet), 3)
        assertTrue("删除行不影响上方引用：$xml", xml.contains("B1*3"))
    }

    @Test
    fun mergeCellShiftsWithRows() {
        val sheet = "<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><sheetData>" +
            "<row r=\"1\"><c r=\"A1\" t=\"inlineStr\"><is><t>标题</t></is></c></row>" +
            "<row r=\"2\"><c r=\"A2\" t=\"inlineStr\"><is><t>x</t></is></c></row>" +
            "<row r=\"3\"><c r=\"A3\" t=\"inlineStr\"><is><t>y</t></is></c></row>" +
            "</sheetData><mergeCells count=\"1\"><mergeCell ref=\"A1:A3\"/></mergeCells></worksheet>"
        val xml = deleteRowOf(book(sheet), 2)
        assertTrue("合并区域应收缩为 A1:A2：$xml", xml.contains("A1:A2"))
    }
}
