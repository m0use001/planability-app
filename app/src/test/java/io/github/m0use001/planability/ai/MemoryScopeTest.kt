package io.github.m0use001.planability.ai

import io.github.m0use001.planability.data.ChatSession
import io.github.m0use001.planability.data.MEMORY_SCOPE_ALL
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 会话 → 记忆作用域（2026-11-26 小说会话事故的入口判据）。
 *
 * 事故背景：小说(9)会话属于项目 9，但记忆读取路径把「无项目」当全库、
 * 又给 handoff 开了跨项目豁免，导致别的项目的进度条目进了上下文并被列进删除清单。
 * 这里钉死三档：hub=全库哨兵、普通会话=项目 id、无项目=0（只读全局+常驻）。
 */
class MemoryScopeTest {

    @Test
    fun `普通项目会话的作用域就是项目 id`() {
        assertEquals(9L, memoryScopeFor(ChatSession(projectId = 9L), isHubAssistant = false))
        assertEquals(17L, memoryScopeFor(ChatSession(projectId = 17L), isHubAssistant = false))
    }

    @Test
    fun `助理中枢才拿全库哨兵`() {
        assertEquals(MEMORY_SCOPE_ALL, memoryScopeFor(ChatSession(projectId = 0L), isHubAssistant = true))
        assertEquals(
            MEMORY_SCOPE_ALL,
            memoryScopeFor(ChatSession(projectId = 9L), isHubAssistant = true)
        )
    }

    @Test
    fun `无项目且非中枢的会话不再是全库`() {
        assertEquals(0L, memoryScopeFor(ChatSession(projectId = 0L), isHubAssistant = false))
        assertEquals(0L, memoryScopeFor(ChatSession(), isHubAssistant = false))
    }
}
