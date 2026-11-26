package io.github.m0use001.planability.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「可当现行事实使用」判据的真值表（纯函数，无 DB）——2026-11-27 小说会话二轮审计。
 *
 * 现场：AI 说「10 条旧设定没被 memory_invalidate 标记、检索时仍按有效内容召回」。
 * 查库：这 10 条的 invalidAt/supersededBy **都已置位**（MEMORY.md 也有标记），但代码里
 * 确实存在一条绕过作废的路——**一跳扩展**（wikilink 邻居）只滤 isDeleted，不滤失效，
 * 顺着链接能把旧设定全文拉进上下文；链接解析还是全局 `byName`（可指到别的项目）。
 * 本测试钉死判据：召回/注入/一跳扩展共用 [memoryUsable]。
 */
class MemoryUsableTest {

    private val now = 1_800_000_000_000L

    private fun mem(
        projectId: Long = 9L,
        invalidAt: Long = 0,
        supersededBy: Long = 0,
        expiration: Long = 0,
        isDeleted: Boolean = false,
        inIndex: Boolean = false
    ) = MemoryEntity(
        id = 1, name = "t", type = "project", description = "", body = "",
        importance = 1, accessCount = 0, lastAccessAt = 0, expiration = expiration,
        inIndex = inIndex, isDeleted = isDeleted, originSession = 0,
        createdAt = 0, updatedAt = 0, projectId = projectId,
        priority = 50, scene = "", validAt = 0, invalidAt = invalidAt, expiredAt = 0,
        version = 1, supersededBy = supersededBy
    )

    @Test
    fun `正常条目可用`() {
        assertTrue(memoryUsable(mem(), now, 9L))
        assertTrue(memoryUsable(mem(projectId = 0L), now, 9L))
    }

    @Test
    fun `软删 已失效 已被取代 已过期 一律不可用`() {
        assertFalse("软删", memoryUsable(mem(isDeleted = true), now, 9L))
        assertFalse("已失效", memoryUsable(mem(invalidAt = now - 1), now, 9L))
        assertFalse("已被取代", memoryUsable(mem(supersededBy = 2L), now, 9L))
        assertFalse("已过期", memoryUsable(mem(expiration = now - 1), now, 9L))
    }

    @Test
    fun `未来才失效的仍可用`() {
        assertTrue(memoryUsable(mem(invalidAt = now + 1), now, 9L))
        assertTrue(memoryUsable(mem(expiration = now + 1), now, 9L))
    }

    @Test
    fun `作用域外的条目不可用——一跳扩展不许跨项目`() {
        assertFalse("线代(11)的条目不许被小说(9)的 wikilink 扩进来", memoryUsable(mem(projectId = 11L), now, 9L))
        assertTrue("本项目", memoryUsable(mem(projectId = 9L), now, 9L))
        assertTrue("常驻索引仍全局", memoryUsable(mem(projectId = 11L, inIndex = true), now, 9L))
        assertTrue("hub 全库", memoryUsable(mem(projectId = 11L), now, MEMORY_SCOPE_ALL))
    }
}
