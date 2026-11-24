package io.github.m0use001.planability.ui.notes

import io.github.m0use001.planability.data.NoteEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v54.3 笔记导出 `.md`：文件名与文件内容。
 *
 * 导出是"资产可携带"的唯一出口，错了就是用户拿着一个打不开/对不上号的文件：
 * - 文件名含 `/` `:` `*` 会让文件系统拒绝或建出诡异目录；
 * - front matter 的 YAML 没转义（标题里有冒号）会让别的编辑器解析失败；
 * - 内容截断/少换行会让原文和文件不一致。
 */
class NoteExportTest {

    private fun note(
        title: String = "实验报告",
        content: String = "# 实验报告\n\n正文内容",
        tags: String = "",
        updatedAt: Long = 1_700_000_000_000
    ) = NoteEntity(
        id = 7, notebookId = 1, title = title, kind = "md",
        content = content, tags = tags, updatedAt = updatedAt
    )

    @Test
    fun fileNameScrubsIllegalCharacters() {
        assertEquals("a_b_c_d.md", NoteExport.fileName(note(title = "a/b:c*d")))
        // 引号与竖线也属于非法字符
        assertEquals("q_w_e.md", NoteExport.fileName(note(title = "q\"w|e")))
    }

    @Test
    fun fileNameStripsMarkdownFromTitle() {
        // 标题取自首行原文，可能带 `#` 与 `[[ ]]`
        assertEquals("已归档.md", NoteExport.fileName(note(title = "#已归档")))
        assertEquals("微积分.md", NoteExport.fileName(note(title = "[[微积分]]")))
    }

    @Test
    fun blankTitleFallsBack() {
        assertEquals("无标题.md", NoteExport.fileName(note(title = "")))
        assertEquals("无标题.md", NoteExport.fileName(note(title = "   ")))
    }

    @Test
    fun longTitleIsCapped() {
        val name = NoteExport.fileName(note(title = "长".repeat(200)))
        assertTrue("文件名不该无限长: ${name.length}", name.length <= 63)
        assertTrue(name.endsWith(".md"))
    }

    @Test
    fun documentHasFrontMatterAndBody() {
        val doc = NoteExport.document(note(tags = "微积分, 期末"), exportedAt = 1_700_000_100_000)
        assertTrue("缺 front matter 起始", doc.startsWith("---\n"))
        assertTrue(doc.contains("title: 实验报告"))
        assertTrue(doc.contains("tags: 微积分, 期末"))
        // 正文必须在 front matter 之后原样出现
        val body = doc.substringAfter("---\n\n")
        assertTrue(body.startsWith("# 实验报告"))
        assertTrue(doc.endsWith("\n"))
    }

    @Test
    fun tagsLineOmittedWhenNoTags() {
        val doc = NoteExport.document(note(tags = " , "))
        assertFalse("没有标签时不该留空 tags 行", doc.contains("tags:"))
    }

    @Test
    fun asciiColonTitleIsQuoted() {
        // YAML 只对 ASCII 冒号敏感：`key: value` 里值含 ": " 会被截断
        val doc = NoteExport.document(note(title = "实验: 误差分析"))
        assertTrue("含 ASCII 冒号的标题必须加引号，否则 YAML 解析失败", doc.contains("title: \"实验: 误差分析\""))
    }

    @Test
    fun fullWidthColonIsNotQuoted() {
        // 中文标题里的「：」是常见写法、对 YAML 无意义，加引号只是噪音
        val doc = NoteExport.document(note(title = "实验：误差分析"))
        assertTrue(doc.contains("title: 实验：误差分析"))
        assertFalse(doc.contains("title: \"实验："))
    }

    @Test
    fun contentIsNotAltered() {
        val content = "第一行\n\n- 条目\n\n```\ncode\n```"
        val doc = NoteExport.document(note(content = content))
        assertTrue(doc.contains(content))
    }
}
