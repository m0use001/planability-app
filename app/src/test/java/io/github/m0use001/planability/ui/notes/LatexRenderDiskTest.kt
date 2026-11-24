package io.github.m0use001.planability.ui.notes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 公式位图磁盘缓存的名字规则（2026-11-21 用户「公式为什么还要反复渲染」）。
 *
 * 名字决定「同一条公式只渲一次」能不能成立，所以只钉两件事：
 * 1. **稳定**：同一 key 永远得到同一个文件名（否则落盘白落、每次都是新文件）；
 * 2. **可区分**：颜色/字号/latex 任一不同就必须是不同的文件（否则深浅色主题会
 *    互相覆盖、大小号公式串图）。
 */
class LatexRenderDiskTest {

    private fun name(color: Int, sizeBits: Float, latex: String) =
        LatexRender.diskName("$color|$sizeBits|$latex")

    @Test
    fun `同一个 key 文件名稳定`() {
        val k = "-16777216|1.07E7|\\frac{d y}{d x}"
        assertEquals(LatexRender.diskName(k), LatexRender.diskName(k))
    }

    @Test
    fun `颜色 字号 latex 任一不同文件名都不同`() {
        val base = name(-1, 48f, "x^2")
        assertNotEquals(base, name(-16777216, 48f, "x^2"))
        assertNotEquals(base, name(-1, 72f, "x^2"))
        assertNotEquals(base, name(-1, 48f, "x^3"))
    }

    @Test
    fun `文件名是 40 位小写十六进制`() {
        val n = name(-1, 48f, "\\sum_{i=1}^n i")
        assertEquals(40, n.length)
        assertTrue("只允许 [0-9a-f]：$n", n.all { it in '0'..'9' || it in 'a'..'f' })
    }
}
