package io.github.m0use001.planability.ai.office

import java.io.File
import java.io.FileOutputStream
import org.apache.poi.xwpf.usermodel.XWPFDocument
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [PoiDocumentReader] 的解析锁（2026-11-09 第一刀）。
 *
 * 关键回归：Word 把一句话拆成多个 run、空格长在 run 首尾。自研正则在 run 级 `.trim()`
 * 吞掉了这些空格 → "Many studentsput theirphones"（真机：英语课附件全文粘连）。
 * POI 的 XWPF 取文本不额外 trim，这里用**真实形状**（多 run、首尾空格、xml 空白）钉死。
 *
 * 这些用例都喂"能红的形状"：如果哪天有人在 reader 里加了 trim/规范化，这里立刻红。
 */
class PoiDocumentReaderTest {

    private fun tmp(name: String): File =
        File(System.getProperty("java.io.tmpdir"), "poi-reader-$name").also { it.parentFile?.mkdirs() }

    private fun writeSplitRunDocx(f: File) {
        XWPFDocument().use { docx ->
            val p = docx.createParagraph()
            // 模拟 Word 的真实拆分：空格在 run 尾部/头部，最后一段没有分隔符
            p.createRun().setText("Many students ")
            p.createRun().setText("put their ")
            p.createRun().setText("phones ")
            p.createRun().setText("upside down")
            val p2 = docx.createParagraph()
            p2.createRun().setText("The Phone You Don't Use Still Costs You")
            FileOutputStream(f).use { docx.write(it) }
        }
    }

    @Test
    fun `docx 多 run 拆分处空格不丢`() {
        val f = tmp("split.docx")
        writeSplitRunDocx(f)
        val text = PoiDocumentReader.readWord(f)
        assertNotNull("docx 应能读出内容", text)
        assertTrue(
            "run 拆分处的空格被吞了（这是 2026-11-09 真机粘连 bug 的形状）：\n$text",
            text!!.contains("Many students put their phones upside down")
        )
        assertTrue("第二段应保留：\n$text", text.contains("The Phone You Don't Use Still Costs You"))
        assertTrue("不该把整段粘成一坨：\n$text", !text.contains("studentsput"))
    }

    @Test
    fun `docx 连续空行压缩但行内空格保留`() {
        val f = tmp("blank.docx")
        XWPFDocument().use { docx ->
            docx.createParagraph().createRun().setText("A  B")
            repeat(4) { docx.createParagraph() }
            docx.createParagraph().createRun().setText("C")
            FileOutputStream(f).use { docx.write(it) }
        }
        val text = PoiDocumentReader.readWord(f)
        assertNotNull(text)
        assertTrue("行内两个空格应保留：\n$text", text!!.contains("A  B"))
        // 4 个空段落压到不超过 2 个空行
        assertTrue("空行没有压缩：\n${text.replace("\n", "\\n")}",
            !text.contains("\n\n\n\n"))
    }

    @Test
    fun `xlsx 单元格按格式化文本读出`() {
        val f = tmp("cells.xlsx")
        XSSFWorkbook().use { wb ->
            val s = wb.createSheet("成绩")
            val r = s.createRow(0)
            r.createCell(0).setCellValue("姓名")
            r.createCell(1).setCellValue("分数")
            val r2 = s.createRow(1)
            r2.createCell(0).setCellValue("张三")
            r2.createCell(1).setCellValue(92.5)
            FileOutputStream(f).use { wb.write(it) }
        }
        val text = PoiDocumentReader.readSheet(f)
        assertNotNull(text)
        assertTrue("表头/数据应读出：\n$text", text!!.contains("姓名") && text.contains("张三"))
        assertTrue("数字单元格应按文本读出：\n$text", text.contains("92.5"))
    }

    @Test
    fun `非 word 扩展名返回 null 而不是乱码`() {
        val f = tmp("x.bin")
        f.writeBytes(byteArrayOf(0x00, 0x01, 0x02, 0x03))
        assertNull("二进制非 word 文件应返回 null（由调用方决定降级），不能吐乱码",
            PoiDocumentReader.readWord(f))
    }

    /** 写-读往返：我们自己的写器产出，必须能被解析器读回来（防"导出成功但是空壳"） */
    @Test
    fun `docx 写器产物可回读`() {
        val f = tmp("roundtrip.docx")
        io.github.m0use001.planability.ai.ptl.OfficeWriter.writeDocx(f, "# 标题\n\n正文第一段\n正文第二段")
        val text = PoiDocumentReader.readWord(f)
        assertNotNull("写出的 docx 回读为空", text)
        listOf("标题", "正文第一段", "正文第二段").forEach {
            assertTrue("回读缺「$it」：\n$text", text!!.contains(it))
        }
        assertNull("正常文档不该被回读闸判失败", PoiDocumentReader.verifyWritten(f, 20))
    }

    /** 回读闸必须能红：造一个只有空段落的 docx，必须判失败 */
    @Test
    fun `空壳 docx 必须被回读闸判失败`() {
        val f = tmp("hollow.docx")
        XWPFDocument().use { d ->
            d.createParagraph()   // 只有一个空段落
            FileOutputStream(f).use { d.write(it) }
        }
        assertNotNull("空壳 docx 应被判失败，实际放行了",
            PoiDocumentReader.verifyWritten(f, 1000))
    }

    /** xlsx 读出来要带工作表清单与 1 基行号（模型才能引用 B3 / 第 N 行） */
    @Test
    fun `xlsx 带工作表清单与行号`() {
        val f = tmp("sheetmeta.xlsx")
        XSSFWorkbook().use { wb ->
            val s = wb.createSheet("成绩")
            wb.createSheet("空表")
            val r = s.createRow(0)
            r.createCell(0).setCellValue("姓名")
            r.createCell(1).setCellValue("分数")
            val r2 = s.createRow(1)
            r2.createCell(0).setCellValue("张三")
            r2.createCell(1).setCellValue(92.5)
            FileOutputStream(f).use { wb.write(it) }
        }
        val text = PoiDocumentReader.readSheet(f)
        assertNotNull(text)
        assertTrue("缺工作表清单：\n$text", text!!.contains("[工作表 1：成绩（2 行）]"))
        assertTrue("缺第二张表清单：\n$text", text.contains("[工作表 2：空表"))
        assertTrue("行号应为 1 基：\n$text", text.contains("1\t姓名\t分数"))
        assertTrue("第二行缺行号 2：\n$text", text.contains("2\t张三\t92.5"))
    }
}
