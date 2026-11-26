package io.github.m0use001.planability.ai.ptl

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 记忆批量删除的突发上限（纯函数，无 DB）——2026-11-26 小说会话事故的硬闸。
 *
 * 事故里模型一次「删」之后连续执行 24 次 memory_forget，确认卡一张张弹出、
 * 用户快速连点「允许」即全部落库。确认卡管不住连点，这里钉死窗口额度：
 * 5 分钟内最多 [MEMORY_MUTATE_BURST_MAX] 条，超出直接拒绝。
 */
class MemoryBurstGuardTest {

    private val t0 = 1_800_000_000_000L

    @Test
    fun `窗口内额度用满后拒绝`() {
        var used = 0
        repeat(MEMORY_MUTATE_BURST_MAX) {
            assertTrue("第 ${used + 1} 条应放行", memoryBurstAllowed(t0, used, t0 + 1_000L))
            used++
        }
        assertFalse(
            "第 ${MEMORY_MUTATE_BURST_MAX + 1} 条必须被拦（连续点允许也拦）",
            memoryBurstAllowed(t0, used, t0 + 2_000L)
        )
    }

    @Test
    fun `批量作废按整批预占额度`() {
        assertTrue("一次作废 5 条（正好额度）应放行", memoryBurstAllowed(t0, 0, t0 + 1_000L, want = 5))
        assertFalse("一次作废 6 条必须被拦", memoryBurstAllowed(t0, 0, t0 + 1_000L, want = 6))
        assertFalse("已用 3 条再作废 3 条必须被拦", memoryBurstAllowed(t0, 3, t0 + 1_000L, want = 3))
    }

    @Test
    fun `窗口过期后重新计算`() {
        assertTrue(
            "过了 5 分钟窗口应重新有额度",
            memoryBurstAllowed(t0, MEMORY_MUTATE_BURST_MAX, t0 + MEMORY_MUTATE_BURST_WINDOW_MS)
        )
        assertFalse(
            "窗口内（差 1ms）仍然拦",
            memoryBurstAllowed(t0, MEMORY_MUTATE_BURST_MAX, t0 + MEMORY_MUTATE_BURST_WINDOW_MS - 1)
        )
    }
}
