package io.github.m0use001.planability.ui.info

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 2026-11-01 用户：「我在线代项目新建对话，怎么自动填充我当时在英语项目下新建对话的文字」。
 *
 * 根因：新会话（未创建，id=0）的草稿全项目共用一个 key 0——A 项目没发出去的字
 * 会自动填进 B 项目的新对话。修复 = 新会话按项目分桶（"p<projectId>"）。
 * 这条钉住桶键推导：已存会话仍按会话 id（悬浮窗/主窗共享同一份草稿的 v14 约定不能破）。
 */
class ChatDraftsKeyTest {

    @Test
    fun `已存会话按会话id分桶_新会话按项目分桶`() {
        assertEquals("288", ChatDrafts.key(288, 11))
        assertEquals("p11", ChatDrafts.key(0, 11))
        assertEquals("p0", ChatDrafts.key(0, 0))
    }
}
