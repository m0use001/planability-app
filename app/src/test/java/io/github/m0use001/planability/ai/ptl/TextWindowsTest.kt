package io.github.m0use001.planability.ai.ptl

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 读取工具分页回归闸（2026-11-05 ◆ 现场）。
 *
 * 用户让 AI 读 58 万字的小说稿，AI 只能拿到前 1.2 万字并告诉用户
 * 「工具每次只能从文件开头取」——读取类工具缺少 offset 是设计缺陷。
 * 这组测试钉住三件事：切页正确、头部元信息齐全、续读 offset 给对。
 */
class TextWindowsTest {

    @Test
    fun `首屏从0开始且头部写明总量与续读offset`() {
        val text = "a".repeat(20_000)
        val p = TextWindows.page(text, offset = 0, limit = 8000)
        assertEquals(0, p.start)
        assertEquals(8000, p.end)
        assertEquals(20_000, p.total)
        assertTrue(p.hasMore)
        val head = p.header("小说.md")
        assertTrue(head.contains("第 1–8000 字"))
        assertTrue(head.contains("共 20000 字"))
        assertTrue(head.contains("offset=8000"))
    }

    @Test
    fun `续读offset能接着上一页且不丢不重`() {
        val text = (1..30_000).joinToString("") { (it % 10).toString() }
        val p1 = TextWindows.page(text, 0, 8000)
        val p2 = TextWindows.page(text, p1.end, 8000)
        assertEquals(p1.end, p2.start)
        // 两页拼起来必须等于原文的对应片段（无重叠、无跳跃）
        assertEquals(text.substring(0, 16_000), p1.text + p2.text)
    }

    @Test
    fun `读到末尾时hasMore为false且不再给offset`() {
        val text = "短文本"
        val p = TextWindows.page(text, offset = 0)
        assertFalse(p.hasMore)
        val head = p.header()
        assertTrue(head.contains("已到文件末尾"))
        assertFalse(head.contains("offset="))
    }

    @Test
    fun `offset越界返回空页但不抛异常`() {
        val text = "abc"
        val p = TextWindows.page(text, offset = 999)
        assertEquals(3, p.start)
        assertEquals(3, p.end)
        assertEquals("", p.text)
        assertFalse(p.hasMore)
    }

    @Test
    fun `offset负数按0处理`() {
        // limit 有 200 的下限，这里用全文更短的内容观察 offset 夹取
        val p = TextWindows.page("abcdef", offset = -5, limit = 3)
        assertEquals(0, p.start)
        assertEquals("abcdef", p.text) // limit 被夹到 200 ≥ 全文长度 → 整篇
    }

    @Test
    fun `limit被夹在合理区间`() {
        val text = "x".repeat(1000)
        // 过小 → 下限 200；过大 → 上限 200000（不超过全文）
        assertEquals(200, TextWindows.page(text, 0, 1).end)
        assertEquals(1000, TextWindows.page(text, 0, 999_999).end)
    }

    @Test
    fun `空文件头写文件为空`() {
        val p = TextWindows.page("", 0)
        assertEquals(0, p.total)
        assertTrue(p.header().contains("文件为空"))
    }

    @Test
    fun `render包含头部与正文`() {
        val p = TextWindows.page("你好世界", 0)
        val out = p.render("笔记.md")
        assertTrue(out.startsWith("[笔记.md · 第 1–4 字"))
        assertTrue(out.endsWith("你好世界"))
    }

    @Test
    fun `中文按字符切不会截断半个字`() {
        val text = "汉".repeat(10_000)
        val p = TextWindows.page(text, 5_000, 500)
        assertEquals(500, p.text.length)
        assertTrue(p.text.all { it == '汉' })
    }
}
