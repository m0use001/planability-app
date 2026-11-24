package io.github.m0use001.planability.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** v45 P3：场景/画像的纯函数规则（不碰 Room）。 */
class MemoryLayersTest {

    @Test
    fun `场景摘要首次直接取内容`() {
        assertEquals("C++ 课程复习", MemoryLayers.mergeSceneSummary("", "C++ 课程复习"))
        assertEquals("", MemoryLayers.mergeSceneSummary("", "   "))
    }

    @Test
    fun `场景摘要追加时不重复`() {
        val one = MemoryLayers.mergeSceneSummary("在复习 C++", "用户偏好先看例子")
        assertTrue(one.contains("在复习 C++") && one.contains("用户偏好先看例子"))
        // 新内容是旧内容的子串 → 保留更长的那份，不再追加
        assertEquals(one, MemoryLayers.mergeSceneSummary(one, "在复习 C++"))
        assertEquals(one, MemoryLayers.mergeSceneSummary("在复习 C++", one))
    }

    @Test
    fun `场景摘要超长时保留最新尾部`() {
        val old = "A".repeat(200)
        val add = "B".repeat(200)
        val merged = MemoryLayers.mergeSceneSummary(old, add, max = 300)
        assertEquals(300, merged.length)
        assertTrue("截断后必须保住新内容", merged.endsWith("B"))
    }

    @Test
    fun `场景注入行带省略号且可退化`() {
        assertEquals("- 场景", MemoryLayers.formatSceneLine("场景", ""))
        val line = MemoryLayers.formatSceneLine("场景", "X".repeat(300), max = 100)
        assertTrue(line.endsWith("…"))
        assertEquals(100 + 1 + "- 场景：".length, line.length)
    }

    @Test
    fun `画像重建按次数触发`() {
        assertFalse(MemoryLayers.shouldRebuildPersona(49, 50))
        assertTrue(MemoryLayers.shouldRebuildPersona(50, 50))
        assertTrue(MemoryLayers.shouldRebuildPersona(51, 50))
        assertFalse("every<=0 视为关闭", MemoryLayers.shouldRebuildPersona(999, 0))
    }
}
