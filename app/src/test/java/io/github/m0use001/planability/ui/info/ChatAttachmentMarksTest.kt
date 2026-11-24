package io.github.m0use001.planability.ui.info

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 用户气泡附件标记的剥离（2026-10-31 用户「附件更优雅」）。
 *
 * 为什么要钉：发送时为了喂模型，正文尾部会拼
 * `[附件已附: attachments/xxx.png 请用 file_read_image 读取]`。渲染侧靠这个正则把它剥出来
 * 单独显示成 chip。正则一旦和发送侧格式漂移：要么气泡里重新冒出英文路径（用户看到的一坨），
 * 要么整段正文被误删（更糟，用户的话就没了）。两种都要在单测里挡住。
 */
class ChatAttachmentMarksTest {

    private val marker = Regex("""\[附件已附: .+? 请用 .+? 读取]""")

    @Test
    fun `正文 + 两个附件标记：正文保留、附件单独列出`() {
        val content = "帮我看看这张课程表截图 " +
            "[附件已附: attachments/file_1_course_table.png 请用 file_read_image 读取] " +
            "[附件已附: attachments/file_2_notes.txt 请用 file_read 读取]"
        val (text, paths) = splitAttachmentMarks(content)
        assertEquals("帮我看看这张课程表截图", text)
        assertEquals(
            listOf("attachments/file_1_course_table.png", "attachments/file_2_notes.txt"),
            paths
        )
        assertTrue("剥离后不应残留协议文字", !text.contains("附件已附"))
    }

    @Test
    fun `没有标记时原样返回且不产生附件`() {
        val content = "普通消息，没有附件"
        val (text, paths) = splitAttachmentMarks(content)
        assertEquals(content, text)
        assertTrue(paths.isEmpty())
    }

    @Test
    fun `只有附件没有正文时正文为空`() {
        val content = "[附件已附: attachments/file_9_a.png 请用 file_read_image 读取]"
        val (text, paths) = splitAttachmentMarks(content)
        assertEquals("", text)
        assertEquals(listOf("attachments/file_9_a.png"), paths)
    }

    @Test
    fun `文件名带空格也能完整剥出`() {
        // safeName 只替换了 / 和 \，所以中文名里的空格会原样留在存储名里
        val content = "看这个 [附件已附: attachments/file_3_实验 记录 第二版.docx 请用 file_read 读取]"
        val (text, paths) = splitAttachmentMarks(content)
        assertEquals("看这个", text)
        assertEquals(listOf("attachments/file_3_实验 记录 第二版.docx"), paths)
    }

    @Test
    fun `正文里的方括号与换行不被误伤`() {
        val content = "第一行 [重要] 保留\n第二行 " +
            "[附件已附: attachments/file_4_x.pdf 请用 file_read 读取]"
        val (text, paths) = splitAttachmentMarks(content)
        assertTrue("正文里的 [重要] 不能被当成标记", text.contains("[重要]"))
        assertTrue("正文换行要保留", text.contains("\n"))
        assertEquals(listOf("attachments/file_4_x.pdf"), paths)
    }

    @Test
    fun `标记格式与发送侧一致（漂移守卫）`() {
        // 发送侧的拼接格式（ChatScreen sendCurrent）："[附件已附: " + relPath + " 请用 " + tool + " 读取]"
        val relPath = "attachments/file_5_course_table.png"
        val tool = "file_read_image"
        val sent = "提问 [附件已附: $relPath 请用 $tool 读取]"
        assertTrue("发送侧格式必须能被 Strip 正则命中", marker.containsMatchIn(sent))
        val (text, paths) = splitAttachmentMarks(sent)
        assertEquals("提问", text)
        assertEquals(listOf(relPath), paths)
    }
}
