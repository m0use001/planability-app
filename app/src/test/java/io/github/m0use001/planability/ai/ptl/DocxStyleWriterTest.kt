package io.github.m0use001.planability.ai.ptl

import io.github.m0use001.planability.ai.office.OfficeText
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipFile

/**
 * 手机端 docx 生成器的排版能力（用户 2026-10-20：「能不能用手机自带文档软件、不借助电脑」）。
 *
 * 结论是：查看可以交给手机文档应用，但"好看"只能由生成器决定——
 * 文档应用只显示文件里已有的样式。所以这里把生成器补成真排版，
 * 并钉住三件事：
 * 1. 包结构完整（styles.xml + document.xml.rels，缺一个 Word 就退回"无样式"）；
 * 2. 结构化标记真的变成标题/表格/加粗，而不是原样留在文字里；
 * 3. 标记符号本身不能漏进正文（`**`、`---` 留在文档里比没样式更难看）。
 */
class DocxStyleWriterTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val markdown = """
        # 季度复盘
        ## 一、结论
        本期**完成率 92%**，比上期高 4 个点。
        - 工程数学：全部通过
        - 学术英语：差一次展示
        1. 先补展示
        2. 再写总结
        | 科目 | 成绩 | 备注 |
        | --- | --- | --- |
        | 工程数学 | 88 | 通过 |
        ---
        > 备注：数据来自 10 月统计
    """.trimIndent()

    @Test
    fun writesStyledPackage() {
        val f = File(tmp.root, "报告.docx")
        OfficeWriter.writeDocx(f, markdown)
        ZipFile(f).use { z ->
            assertNotNull("缺 word/styles.xml：Word 会按无样式渲染", z.getEntry("word/styles.xml"))
            assertNotNull("缺 document.xml.rels：styles.xml 不会被引用", z.getEntry("word/_rels/document.xml.rels"))
            val xml = z.getInputStream(z.getEntry("word/document.xml")).readBytes().toString(Charsets.UTF_8)
            assertTrue("一级标题应套 Heading1 样式", xml.contains("w:val=\"Heading1\""))
            assertTrue("二级标题应套 Heading2 样式", xml.contains("w:val=\"Heading2\""))
            assertTrue("Markdown 表格应变成真表格", xml.contains("<w:tbl>"))
            assertTrue("表头行应加粗", xml.contains("<w:b/>"))
            assertTrue("应启用 A4 页面与页边距", xml.contains("w:w=\"11906\""))
        }
    }

    @Test
    fun textSurvivesRoundTripWithoutMarkers() {
        val f = File(tmp.root, "报告.docx")
        OfficeWriter.writeDocx(f, markdown)
        val text = OfficeText.fromDocx(f)
        assertTrue("标题文字丢了", text.contains("季度复盘"))
        assertTrue("表格前的正文丢了", text.contains("完成率 92%"))
        assertFalse("** 标记漏进了正文", text.contains("**"))
        assertFalse("分隔线标记漏进了正文", text.contains("---"))
        assertTrue("表格单元格文字丢了", text.contains("工程数学") && text.contains("88"))
        assertTrue("引用块文字丢了", text.contains("数据来自 10 月统计"))
    }

    @Test
    fun plainTextStillWorks() {
        // 老用法（纯文本、无 Markdown）不能被新排版搞坏：这是绝大多数既有调用的样子
        val f = File(tmp.root, "plain.docx")
        OfficeWriter.writeDocx(f, "第一行\n第二行\n\n第三行")
        val text = OfficeText.fromDocx(f)
        assertTrue(text.contains("第一行"))
        assertTrue(text.contains("第三行"))
    }

    @Test
    fun writesSampleIntoBuildDirForManualCheck() {
        // 落一份到 build/（gitignore 内），方便 adb push 到手机、用自带文档应用看真实排版。
        // 不是断言测试，是"手动验收的素材"，失败也不影响 CI 结论。
        val f = File("build/docx-sample.docx")
        f.parentFile?.mkdirs()
        OfficeWriter.writeDocx(f, markdown)
        assertTrue("样例文件没写出来", f.exists() && f.length() > 0)
    }
}
