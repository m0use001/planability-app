package io.github.m0use001.planability.shared

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * TextWindows 行为对照测试（从 app/ai/ptl/TextWindowsTest.kt 迁入，断言不变）。
 * 在 JVM 与 Android 两个 target 上都跑（commonTest），验证 shared 纯逻辑两端一致。
 */
class TextWindowsTest {

    @Test
    fun `basic page within limit`() {
        val text = "a".repeat(1000)
        val p = TextWindows.page(text, offset = 0, limit = 8000)
        assertEquals(0, p.start)
        assertEquals(1000, p.end)
        assertEquals(1000, p.total)
        assertFalse(p.hasMore)
    }

    @Test
    fun `paginates across multiple pages`() {
        val text = "b".repeat(3000)
        val p1 = TextWindows.page(text, 0, 8000)
        assertEquals(0, p1.start)
        assertEquals(3000, p1.end)
        assertFalse(p1.hasMore)
        // 小 limit 验证翻页
        val p2 = TextWindows.page(text, 0, 200)
        assertEquals(0, p2.start)
        assertEquals(200, p2.end)
        assertTrue(p2.hasMore)
        val p3 = TextWindows.page(text, p2.end, 200)
        assertEquals(200, p3.start)
        assertEquals(400, p3.end)
    }

    @Test
    fun `default limit applied`() {
        val text = "c".repeat(2000)
        val p = TextWindows.page(text, offset = 0)
        assertEquals(0, p.start)
        assertEquals(2000, p.end)
        assertEquals(2000, p.total)
    }

    @Test
    fun `offset beyond total returns empty page`() {
        val text = "d".repeat(500)
        val p = TextWindows.page(text, offset = 999)
        assertEquals(500, p.start)
        assertEquals(500, p.end)
        assertEquals(500, p.total)
        assertFalse(p.hasMore)
        assertEquals("", p.text)
    }

    @Test
    fun `negative offset clamped to zero`() {
        val p = TextWindows.page("abcdef", offset = -5, limit = 200)
        assertEquals(0, p.start)
        assertEquals(6, p.end)
    }

    @Test
    fun `limit clamped to minimum 200`() {
        val text = "e".repeat(1000)
        assertEquals(200, TextWindows.page(text, 0, 1).end)
    }

    @Test
    fun `limit clamped to maximum 200_000`() {
        val text = "f".repeat(1000)
        assertEquals(1000, TextWindows.page(text, 0, 999_999).end)
    }

    @Test
    fun `empty text returns empty page with header`() {
        val p = TextWindows.page("", 0)
        assertEquals(0, p.start)
        assertEquals(0, p.end)
        assertEquals(0, p.total)
        assertFalse(p.hasMore)
        assertTrue(p.header("empty.txt").contains("文件为空"))
    }

    @Test
    fun `chinese text paginates correctly`() {
        val text = "你好世界".repeat(250) // 1000 chars
        val p = TextWindows.page(text, 0, 200)
        assertEquals(0, p.start)
        assertEquals(200, p.end)
        assertEquals(1000, p.total)
        assertTrue(p.hasMore)
        assertEquals(200, p.text.length)
    }

    @Test
    fun `header contains offset instruction when hasMore`() {
        val text = "g".repeat(1000)
        val p = TextWindows.page(text, 0, 200)
        val h = p.header("novel.md")
        assertTrue(h.contains("offset=200"))
        assertTrue(h.contains("novel.md"))
    }

    @Test
    fun `mid-file offset`() {
        val text = "h".repeat(6000)
        val p = TextWindows.page(text, 5_000, 500)
        assertEquals(5000, p.start)
        assertEquals(5500, p.end)
        assertEquals(6000, p.total)
        assertTrue(p.hasMore)
    }
}
