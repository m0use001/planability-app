package io.github.m0use001.planability.ai.office

import io.github.m0use001.planability.ai.ptl.OfficeWriter
import io.github.m0use001.planability.ai.ptl.PptxWriter
import io.github.m0use001.planability.shared.DocModel
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [OfficeText] 的抽取测试。
 *
 * ## 为什么要测这个
 *
 * 它是一堆正则，看一眼觉得对，跑一遍真实文件才发现问题的情况非常多：
 * 页码排序错、共享字符串没解析、段落切分把文字粘成一坨。这些错误**不会报错**，
 * 只会安静地给出一份残缺的预览——而残缺的预览比报错更糟，因为它看起来像成功了。
 *
 * 三个来源各测一遍：
 * - 本项目自己写的 docx/xlsx（inlineStr 那种）
 * - 本项目 PptxWriter 写的 pptx
 * - **手搓的 sharedStrings 版 xlsx**——真正的 Excel 都是这种，我们自己的写器不产出，
 *   所以只能手工构造。这条最容易写错，也最值得钉住。
 */
class OfficeTextTest {

    private fun tmp(name: String): File =
        File(System.getProperty("java.io.tmpdir"), "office-text-$name").also { it.parentFile?.mkdirs() }

    @Test
    fun docxParagraphsComeBack() {
        val f = tmp("a.docx")
        OfficeWriter.writeDocx(f, "# 标题\n\n正文第一行\n正文第二行")
        val text = OfficeText.read(f)
        listOf("标题", "正文第一行", "正文第二行").forEach {
            assertTrue("docx 预览里读不到「$it」，实际：\n$text", text.contains(it))
        }
        // 段落要分行：粘成一坨的话预览就没法看了
        assertTrue("docx 段落没有分行，实际：\n$text", text.lines().size >= 3)
    }

    /**
     * 读取的编号与编辑工具的 index 必须同一套基准。
     *
     * 这条是补真机事故：读取时打的是 `[P0]`、`[P1]`…（0 基），而 `file_edit_document`
     * 声明"index 从 1 开始"。真机让人改第 3 段，模型读到 `[P2]` 后按 1 基传了 index=2，
     * 结果**改掉的是第 2 段**——工具报成功、文件能打开，只是改错了地方。
     * 这种错没有任何单测覆盖过，因为两边各自都"符合自己的文档"。
     */
    @Test
    fun docxIndexLabelsAreOneBasedToMatchTheEditTool() {
        val f = tmp("idx.docx")
        OfficeWriter.writeDocx(f, "甲\n乙\n丙")
        val text = OfficeText.fromDocx(f, withIndex = true)
        val labels = Regex("""\[(P\d+)]""").findAll(text).map { it.groupValues[1] }.toList()
        assertEquals("编号应从 P1 开始且连续，实际：$labels", listOf("P1", "P2", "P3"), labels)
        assertTrue("不该出现 0 基的 [P0]（会让模型把 index 理解成第 N-1 段）：\n$text",
            !text.contains("[P0]"))
        // 编号必须与段落一一对应，不能错位（[P2] 后面跟的应是第 2 段内容「乙」）
        assertTrue("[P1] 后面跟的不是甲：\n$text", text.contains("[P1] 甲"))
        assertTrue("[P2] 后面跟的不是乙：\n$text", text.contains("[P2] 乙"))
        assertTrue("[P3] 后面跟的不是丙：\n$text", text.contains("[P3] 丙"))
    }

    @Test
    fun xlsxInlineStringsComeBack() {
        val f = tmp("a.xlsx")
        OfficeWriter.writeXlsx(f, "渠道\t新增\t留存\n自然量\t12043\t45%\n投放\t3210\t31%")
        val text = OfficeText.read(f)
        listOf("渠道", "新增", "留存", "自然量", "12043", "45%").forEach {
            assertTrue("xlsx 预览里读不到「$it」，实际：\n$text", text.contains(it))
        }
        assertTrue("xlsx 表格没分行，实际：\n$text", text.lines().size >= 3)
    }

    /**
     * 真正的 Excel（和大多数第三方库）把文字放进 sharedStrings，sheet 里只存下标。
     * 只扫 sheet 的 `<t>` 会得到几乎空白的预览——这是本测试存在的首要理由。
     */
    @Test
    fun xlsxSharedStringsAreResolvedByIndex() {
        val f = tmp("shared.xlsx")
        val shared = listOf("渠道", "新增", "留存", "自然量", "投放")
        val sheet = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData>
            <row r="1"><c r="A1" t="s"><v>0</v></c><c r="B1" t="s"><v>1</v></c><c r="C1" t="s"><v>2</v></c></row>
            <row r="2"><c r="A2" t="s"><v>3</v></c><c r="B2"><v>12043</v></c><c r="C2"><v>0.45</v></c></row>
            <row r="3"><c r="A3" t="s"><v>4</v></c><c r="B3"><v>3210</v></c><c r="C3"><v>0.31</v></c></row>
            </sheetData></worksheet>
        """.trimIndent()
        val sharedXml =
            "<sst xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">" +
                shared.joinToString("") { "<si><t>$it</t></si>" } + "</sst>"
        zipOf(
            f,
            mapOf(
                "xl/workbook.xml" to "<workbook/>",
                "xl/sharedStrings.xml" to sharedXml,
                "xl/worksheets/sheet1.xml" to sheet
            )
        )
        val text = OfficeText.read(f)
        listOf("渠道", "自然量", "投放", "12043", "0.45").forEach {
            assertTrue("共享字符串没按下标还原出「$it」，实际：\n$text", text.contains(it))
        }
        // 下标不该漏到预览里
        assertTrue("预览里漏出了单元格下标，实际：\n$text", !Regex("<v>\\d+</v>").containsMatchIn(text))
    }

    @Test
    fun pptxSlidesAreOrderedAndComplete() {
        val f = tmp("a.pptx")
        PptxWriter.write(
            f,
            DocModel.Deck(
                title = "T",
                slides = (1..12).map {
                    DocModel.Slide(title = "第${it}页", blocks = listOf(DocModel.Block.Bullets(listOf("要点$it"))))
                }
            )
        )
        val text = OfficeText.read(f)
        listOf("第1页", "第6页", "第12页", "要点12").forEach {
            assertTrue("pptx 预览里读不到「$it」，实际：\n$text", text.contains(it))
        }
        // 页码必须按数字排：slide10 若排在 slide2 前面，翻页预览就全乱了
        val i1 = text.indexOf("第1页")
        val i2 = text.indexOf("第2页")
        val i10 = text.indexOf("第10页")
        assertTrue("pptx 页序不对：1页@$i1 2页@$i2 10页@$i10", i1 in 0 until i2 && i2 < i10)
    }

    @Test
    fun pptxTableCellsAreReadable() {
        val f = tmp("t.pptx")
        PptxWriter.write(
            f,
            DocModel.Deck(
                title = "T",
                slides = listOf(
                    DocModel.Slide(
                        title = "明细",
                        blocks = listOf(
                            DocModel.Block.Table(listOf(listOf("渠道", "新增"), listOf("自然量", "12043")))
                        )
                    )
                )
            )
        )
        val text = OfficeText.read(f)
        listOf("明细", "渠道", "新增", "自然量", "12043").forEach {
            assertTrue("pptx 表格读不到「$it」，实际：\n$text", text.contains(it))
        }
    }

    @Test
    fun xmlEntitiesAreDecoded() {
        // 文字里本来就有 & < > 时，预览里要显示成原样，而不是 &amp; 这种
        val f = tmp("esc.docx")
        OfficeWriter.writeDocx(f, "A&B 的 <tag> 与 \"引号\"")
        val text = OfficeText.read(f)
        assertTrue("实体没解码，实际：\n$text", text.contains("A&B 的 <tag>"))
        assertTrue("不该残留 &amp;，实际：\n$text", !text.contains("&amp;"))
    }

    @Test
    fun plainTextPassesThrough() {
        val f = tmp("a.md")
        f.writeText("# 标题\n正文")
        assertTrue(OfficeText.read(f).contains("正文"))
    }

    /**
     * Word 常把一句话拆成多个 run，空格长在 run 首尾（`Many students ` + `p` + `ut the` +
     * `ir` + ` phone` + `s`）。逐 run `.trim()` 会把空格吃掉 → "Many studentsput theirphones"
     * （2026-11-09 真机：英语课附件全文单词粘连）。手搓一份 Word 风格的 document.xml 钉死。
     */
    @Test
    fun docxRunSplitsKeepSpaces() {
        val f = tmp("runs.docx")
        zipOf(f, mapOf(
            "word/document.xml" to
                "<?xml version=\"1.0\"?>" +
                "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\">" +
                "<w:body><w:p>" +
                "<w:r><w:t>Many students </w:t></w:r>" +
                "<w:r><w:t>p</w:t></w:r>" +
                "<w:r><w:t>ut the</w:t></w:r>" +
                "<w:r><w:t>ir</w:t></w:r>" +
                "<w:r><w:t xml:space=\"preserve\"> phone</w:t></w:r>" +
                "<w:r><w:t>s</w:t></w:r>" +
                "</w:p></w:body></w:document>"
        ))
        val text = OfficeText.fromDocx(f)
        assertTrue("run 拆分处的空格被 trim 吃掉了，实际：\n$text",
            text.contains("Many students put their phones"))
    }

    private fun zipOf(out: File, entries: Map<String, String>) {
        ZipOutputStream(FileOutputStream(out)).use { z ->
            entries.forEach { (k, v) ->
                z.putNextEntry(ZipEntry(k))
                z.write(v.toByteArray(Charsets.UTF_8))
                z.closeEntry()
            }
        }
    }
}
