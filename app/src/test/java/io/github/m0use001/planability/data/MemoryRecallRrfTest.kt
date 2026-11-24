package io.github.m0use001.planability.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v45 P4：双路 FTS 的 RRF 融合（纯函数）。
 *
 * 钉住三点：两路命中 > 单路命中；标题路权重 > 正文路（k=60、w=0.7）；
 * 单路空时退化为另一路原序。
 */
class MemoryRecallRrfTest {

    private fun mem(id: Long, name: String) = MemoryEntity(id = id, name = name, body = "b")

    @Test
    fun `两路都命中的排最前`() {
        val a = mem(1, "A")
        val b = mem(2, "B")
        val c = mem(3, "C")
        val fused = MemoryRepo.fuseRrf(listOf(a, b), listOf(b, c))
        // A: 0.7/61；B: 0.7/62 + 0.3/61（两路相加反超）；C: 0.3/62
        assertEquals(listOf(b, a, c), fused)
    }

    @Test
    fun `标题路权重高于正文路`() {
        val title = mem(1, "T")
        val body = mem(2, "B")
        val fused = MemoryRepo.fuseRrf(listOf(title), listOf(body))
        assertEquals(title, fused[0])
    }

    @Test
    fun `单路空时退化为另一路原序`() {
        val t1 = mem(1, "T1")
        val t2 = mem(2, "T2")
        assertEquals(listOf(t1, t2), MemoryRepo.fuseRrf(listOf(t1, t2), emptyList()))
        val b1 = mem(3, "B1")
        val b2 = mem(4, "B2")
        assertEquals(listOf(b1, b2), MemoryRepo.fuseRrf(emptyList(), listOf(b1, b2)))
        assertTrue(MemoryRepo.fuseRrf(emptyList(), emptyList()).isEmpty())
    }

    @Test
    fun `名次差被 k 抑制（k=60）`() {
        val a = mem(1, "A")
        val b = mem(2, "B")
        // 标题路第 1 名 vs 正文路第 1 名：0.7/61 > 0.3/61，但差距远小于朴素排名差
        val fused = MemoryRepo.fuseRrf(listOf(a), listOf(b))
        assertEquals(listOf(a, b), fused)
    }
}
