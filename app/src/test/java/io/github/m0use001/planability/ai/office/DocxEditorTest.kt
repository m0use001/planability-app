package io.github.m0use001.planability.ai.office

import io.github.m0use001.planability.ai.ptl.OfficeWriter
import java.io.File
import java.util.zip.ZipFile
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [DocxEditor] 的改稿闸门。
 *
 * 盯的不是"改出来好不好看"，而是**会不会改错地方**——docx 改稿最容易出的两类事故：
 * ① 段落序号数到了表格单元格里（用户说"改第 5 段"，改的是表格里一个格子）；
 * ② 改文字顺手把标题降级成正文（pPr 被丢掉）。两条都用断言钉住。
 */
class DocxEditorTest {

    private fun tmp(name: String) =
        File(System.getProperty("java.io.tmpdir"), "docx-edit-$name").also { it.parentFile?.mkdirs() }

    /**
     * 自造一份**合法**的带表格 docx：表格单元格里的段落**不能**被算进"第 N 段"。
     *
     * "合法"是要紧的：早先这个夹具只塞了 `[Content_Types].xml` + `word/document.xml`，
     * 结果 python-docx 连**输入文件**都打不开，门禁脚本报的全是"夹具坏了"，
     * 差点被误读成改稿功能有问题。夹具必须先自己能被 Word 解析器打开。
     */
    private fun buildWithTable(): File {
        val f = tmp("src.docx")
        val doc = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body>
            <w:p><w:pPr><w:pStyle w:val="Heading1"/></w:pPr><w:r><w:rPr><w:b/></w:rPr><w:t>季度总结</w:t></w:r></w:p>
            <w:p><w:r><w:t>第一段正文</w:t></w:r></w:p>
            <w:tbl><w:tr><w:tc><w:p><w:r><w:t>表格里的一格</w:t></w:r></w:p></w:tc></w:tr></w:tbl>
            <w:p><w:r><w:t>第二段正文</w:t></w:r></w:p>
            </w:body></w:document>
        """.trimIndent()
        zip(
            f,
            mapOf(
                "[Content_Types].xml" to
                    """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                       <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
                       <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
                       <Default Extension="xml" ContentType="application/xml"/>
                       <Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>
                       <Override PartName="/word/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml"/>
                       </Types>""",
                "_rels/.rels" to
                    """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                       <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                       <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/>
                       </Relationships>""",
                "word/_rels/document.xml.rels" to
                    """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                       <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                       <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>
                       </Relationships>""",
                "word/styles.xml" to
                    """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                       <w:styles xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
                       <w:style w:type="paragraph" w:default="1" w:styleId="Normal"><w:name w:val="Normal"/></w:style>
                       <w:style w:type="paragraph" w:styleId="Heading1"><w:name w:val="heading 1"/><w:rPr><w:b/></w:rPr></w:style>
                       </w:styles>""",
                "word/document.xml" to doc
            )
        )
        return f
    }

    @Test
    fun tableCellParagraphsAreNotCountedAsBodyParagraphs() {
        val ps = DocxEditor.read(buildWithTable())
        assertEquals("表格单元格被算进段落了：$ps", 3, ps.size)
        assertEquals("季度总结", ps[0].text)
        assertEquals("第一段正文", ps[1].text)
        assertEquals("第二段正文", ps[2].text)
        assertTrue("没读到标题样式", ps[0].style.contains("Heading"))
    }

    @Test
    fun editingParagraphKeepsHeadingStyle() {
        val src = buildWithTable()
        val out = tmp("out.docx")
        DocxEditor.apply(src, listOf(DocxEditor.Edit(0, "季度总结（修订）")), out)
        val xml = documentOf(out)
        // 标题级别必须还在——改文字不该把标题降成正文
        assertTrue("pPr（标题样式）被丢掉了", xml.contains("Heading1"))
        assertTrue("新文字没写进去", xml.contains("季度总结（修订）"))
        assertTrue("旧文字还在", !xml.contains("<w:t>季度总结</w:t>"))
    }

    @Test
    fun untouchedParagraphsStayByteIdentical() {
        val src = buildWithTable()
        val out = tmp("out2.docx")
        DocxEditor.apply(src, listOf(DocxEditor.Edit(1, "第一段正文（已改）")), out)
        val before = documentOf(src)
        val after = documentOf(out)

        // 按结构定位比对，而不是按文字切：被改的那段现在会多出 xml:space 属性，
        // 按文字切出来的"头部"必然不等，那是测试写法的问题而不是产品的问题。
        val headP = before.substringAfter("<w:body>").substringBefore("</w:p>") + "</w:p>"
        assertTrue("标题段不该变", after.contains(headP))

        val tbl = "<w:tbl>" + before.substringAfter("<w:tbl>").substringBefore("</w:tbl>") + "</w:tbl>"
        assertTrue("表格（含单元格里的段落）不该变", after.contains(tbl))

        val tailP = before.substringAfter("</w:tbl>").substringBefore("</w:body>")
        assertTrue("表格之后的段落不该变", after.contains(tailP))

        assertTrue("新文字没写进去", after.contains("第一段正文（已改）"))
        assertTrue("旧文字还在", !after.contains("<w:t>第一段正文</w:t>"))
    }

    @Test
    fun editingMultipleParagraphsDoesNotShiftLaterOnes() {
        // 替换是变长的（0→长），如果按"删掉再插入"的写法顺序处理，后面段落会全部错位
        val src = buildWithTable()
        val out = tmp("out3.docx")
        DocxEditor.apply(
            src,
            listOf(
                DocxEditor.Edit(0, "很长的新标题内容用来把长度撑开很多很多很多很多"),
                DocxEditor.Edit(2, "第二段正文也改了")
            ),
            out
        )
        val after = documentOf(out)
        assertTrue("第 1 段没改成", after.contains("把长度撑开很多很多很多很多"))
        assertTrue("第 3 段没改成", after.contains("第二段正文也改了"))
        assertTrue("表格被误改了", after.contains("表格里的一格"))
    }

    @Test
    fun outOfRangeIndexFailsLoudly() {
        val src = buildWithTable()
        val res = DocxEditor.apply(src, listOf(DocxEditor.Edit(99, "不存在")), tmp("out4.docx"))
        assertEquals("不该报告成功", 0, res.changed)
        assertTrue("应说明越界", res.failed.any { it.contains("100") || it.contains("第 100") })
    }

    @Test
    fun nonDocxIsRejected() {
        val f = tmp("fake.docx")
        f.writeText("我只是一个 txt")
        var msg = ""
        try {
            DocxEditor.read(f)
        } catch (e: Exception) {
            msg = e.message ?: ""
        }
        assertTrue("读不懂必须报错，实际：$msg", msg.isNotBlank())
    }

    @Test
    fun worksOnFilesOurOwnWriterProduced() {
        // 用户自己的 docx 大多是从别处导出的，但也可能是本 App 生成的（file_export_document）
        val f = tmp("own.docx")
        OfficeWriter.writeDocx(f, "第一行\n第二行\n第三行")
        val ps = DocxEditor.read(f)
        assertEquals("段落数不对：$ps", 3, ps.size)
        assertEquals("第一行", ps[0].text)
    }

    @Test
    fun specialCharactersSurviveRoundTrip() {
        val src = buildWithTable()
        val out = tmp("out5.docx")
        DocxEditor.apply(src, listOf(DocxEditor.Edit(1, "A&B <tag> 的 100% & \"引号\"")), out)
        val after = documentOf(out)
        assertTrue("特殊字符没被转义保存", after.contains("A&amp;B &lt;tag&gt;"))
        // 再读回来必须是原样，而不是 &amp; 这些实体
        val back = DocxEditor.read(out)
        assertEquals(
            "读回来变了", "A&B <tag> 的 100% & \"引号\"", back.first { it.text.startsWith("A&B") }.text
        )
    }

    @Test
    fun clearingAParagraphKeepsDocumentValid() {
        // 整段删空是常见需求（"把这段删掉"）；写成 <w:p></w:p> 可以，但别写成残缺标签
        val src = buildWithTable()
        val out = tmp("out6.docx")
        DocxEditor.apply(src, listOf(DocxEditor.Edit(1, "")), out)
        val after = documentOf(out)
        assertTrue("段落标签残缺", after.contains("<w:p>") && after.contains("</w:p>"))
        assertEquals("空段落后段落数应少一条文字", 2, DocxEditor.read(out).count { it.text.isNotBlank() })
    }

    private fun documentOf(f: File): String =
        ZipFile(f).use { z ->
            z.getInputStream(z.getEntry("word/document.xml")!!).readBytes().toString(Charsets.UTF_8)
        }

    private fun zip(out: File, entries: Map<String, String>) {
        java.util.zip.ZipOutputStream(java.io.FileOutputStream(out)).use { z ->
            entries.forEach { (k, v) ->
                z.putNextEntry(java.util.zip.ZipEntry(k))
                z.write(v.toByteArray(Charsets.UTF_8))
                z.closeEntry()
            }
        }
    }
}
