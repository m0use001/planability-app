package io.github.m0use001.planability.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 召回项目作用域真值表（纯函数，无 DB）——**2026-11-26 小说会话事故后的收紧版**。
 *
 * 旧规则（已废）：`scope<=0 全库` + `handoff 跨项目可见`。事故实测：小说(9)会话里
 * 模型能看到并列出 7 个别的项目的 handoff（军训/线代/体测…）当「要删的」。
 *
 * 新规则（[recallableInScope]）：
 * - `scope < 0`（[MEMORY_SCOPE_ALL]，仅助理中枢）= 全库；
 * - `scope == 0`（无项目会话）= 全局(0) + 常驻索引(inIndex=1)；
 * - `scope > 0` = 本项目 + 全局(0) + 常驻索引(inIndex=1)；handoff **只在**本项目内可见。
 */
class MemoryScopePureTest {

    private fun mem(projectId: Long, inIndex: Boolean = false, type: String = "project") =
        MemoryEntity(name = "t", projectId = projectId, inIndex = inIndex, type = type)

    @Test
    fun `只有显式的全库哨兵才跨项目（助理中枢）`() {
        for (p in listOf(0L, 9L, 11L, 17L)) {
            assertTrue("scope=MEMORY_SCOPE_ALL 应可见 projectId=$p", recallableInScope(mem(p), MEMORY_SCOPE_ALL))
        }
    }

    @Test
    fun `无项目会话只看全局与常驻——0 不再等于全库`() {
        assertTrue(recallableInScope(mem(0), 0L))
        assertTrue(recallableInScope(mem(11, inIndex = true), 0L))
        assertFalse("无项目会话不许看到别的项目的普通条目", recallableInScope(mem(11), 0L))
        assertFalse("无项目会话不许看到别的项目的 handoff", recallableInScope(mem(11, type = "handoff"), 0L))
    }

    @Test
    fun `本项目与全局永远可见`() {
        assertTrue(recallableInScope(mem(9), 9L))
        assertTrue(recallableInScope(mem(0), 9L))
        assertTrue("本项目自己的 handoff 要可见", recallableInScope(mem(9, type = "handoff"), 9L))
    }

    @Test
    fun `别的项目普通条目不可见——串味根修的判据`() {
        assertFalse("线代(11)的普通条目不许出现在小说(9)会话", recallableInScope(mem(11), 9L))
        assertFalse(recallableInScope(mem(9), 11L))
        assertFalse(recallableInScope(mem(17), 9L))
    }

    @Test
    fun `别的项目的 handoff 也不再跨项目可见——事故回归点`() {
        assertFalse(
            "军训/线代/体测等别的项目的 handoff 不许进小说会话（2026-11-26 事故根因）",
            recallableInScope(mem(14, type = "handoff"), 9L)
        )
        assertFalse(recallableInScope(mem(11, type = "handoff"), 17L))
    }

    @Test
    fun `常驻索引仍然跨项目可见（用户主动 pin 的工作层）`() {
        assertTrue("inIndex=1 是用户刻意全局可见", recallableInScope(mem(11, inIndex = true), 9L))
    }
}
