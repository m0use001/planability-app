package io.github.m0use001.planability.data

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * v28：五档紧急性/重要性与旧三档 `priority` 的映射。
 *
 * 这套映射是**跨端契约的护栏**：`priority` 还在被 AI 工具参数、`PhoneDump`（导出给 PC）、
 * 备份文件读写，所以它不能删，只能变成 importance 的投影。投影错一位，
 * PC 侧显示的高中低就会和手机上滑块的位置对不上，而且两边各自"都对"——
 * 这正是 2026-10-11 docx 编号 0 基/1 基那类 bug 的形状，先在这儿钉死。
 */
class PriorityMapTest {

    @Test
    fun `三档 priority 与五档 importance 双向映射可逆`() {
        // 每一档 priority 都要能 round-trip 回自己
        for (p in listOf(0, 1, 2)) {
            assertEquals("priority=$p 往返后必须不变", p, PriorityMap.priorityOf(PriorityMap.importanceOf(p)))
        }
        // 三个锚点档：高→4、中→2、低→0（反过来 4→0、2→1、0→2）
        assertEquals(4, PriorityMap.importanceOf(0))
        assertEquals(2, PriorityMap.importanceOf(1))
        assertEquals(0, PriorityMap.importanceOf(2))
        assertEquals(0, PriorityMap.priorityOf(4))
        assertEquals(1, PriorityMap.priorityOf(2))
        assertEquals(2, PriorityMap.priorityOf(0))
    }

    @Test
    fun `存量行默认值迁移后优先级视图逐行等价`() {
        // 迁移给两列的 DEFAULT 是 2（中）。老数据 priority 默认 1（中），
        // 所以 importance=2 必须折回 priority=1 —— 否则迁移一次就把所有旧条目"提级/降级"了。
        assertEquals(1, PriorityMap.priorityOf(2))
        // 中间档的归类也要固定：3 归高、1 归低（不是"就近取整到 2"）
        assertEquals(0, PriorityMap.priorityOf(3))
        assertEquals(2, PriorityMap.priorityOf(1))
    }

    @Test
    fun `档位文案五档各不相同且越界被收敛`() {
        val labels = (0..4).map { PriorityMap.labelOf(it) }
        assertEquals("五档文案必须互不相同，否则滑块拉到底和拉到顶长得一样", 5, labels.toSet().size)
        assertEquals("低", PriorityMap.labelOf(0))
        assertEquals("高", PriorityMap.labelOf(4))
        // 滑块理论上给不出越界值，但别让越界值把 UI 打崩
        assertEquals("低", PriorityMap.labelOf(-3))
        assertEquals("高", PriorityMap.labelOf(99))
    }
}
