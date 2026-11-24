package io.github.m0use001.planability.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v57 召回项目作用域判据的真值表（纯函数，无 DB）。
 *
 * 规则（[recallableInScope]）：scope<=0 全库；scope>0 = 全局(0) + 本项目 +
 * 常驻索引(inIndex) + handoff。这是 `memory_search` 不再把别的项目条目
 * 端进当前会话的唯一守门判据——回归即红。
 */
class MemoryScopePureTest {

    private fun mem(projectId: Long, inIndex: Boolean = false, type: String = "project") =
        MemoryEntity(name = "t", projectId = projectId, inIndex = inIndex, type = type)

    @Test
    fun `scope 为 0 时全库可见`() {
        for (p in listOf(0L, 9L, 11L, 17L)) {
            assertTrue("scope=0 应可见 projectId=$p", recallableInScope(mem(p), 0L))
        }
    }

    @Test
    fun `本项目与全局永远可见`() {
        assertTrue(recallableInScope(mem(9), 9L))
        assertTrue(recallableInScope(mem(0), 9L))
    }

    @Test
    fun `别的项目普通条目不可见——串味根修的判据`() {
        assertFalse("线代(11)的普通条目不许出现在小说(9)会话", recallableInScope(mem(11), 9L))
        assertFalse(recallableInScope(mem(9), 11L))
        assertFalse(recallableInScope(mem(17), 9L))
    }

    @Test
    fun `常驻索引与 handoff 跨项目可见（工作层语义）`() {
        assertTrue("inIndex=1 是刻意全局可见", recallableInScope(mem(11, inIndex = true), 9L))
        assertTrue("handoff 本来就要跨项目接力", recallableInScope(mem(11, type = "handoff"), 9L))
    }
}
