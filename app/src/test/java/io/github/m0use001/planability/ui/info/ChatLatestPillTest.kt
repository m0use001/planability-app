package io.github.m0use001.planability.ui.info

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「最新」胶囊的行为锁（2026-11-08 用户：「显示延迟且点击后卡顿」）。
 *
 * - 显示：纯**位置**判定（离了底就显示，惯性滚动中也即时出现）；
 *   不能跟 `autoFollow` 走——那个状态要等滚动彻底停止才翻转，惯性期间胶囊不出现 = "延迟"。
 * - 点击：瞬时贴底。`animateScrollToItem` 会把中间几百个重型 markdown item 逐个组合
 *   出来，列表越长越卡（卡顿来源）。2026-11-20 钳制模型重写后走统一 snapToBottom
 *   （requestScrollToItem 瞬时定位 + scrollBy(MAX) 钳残余）。
 *
 * 源码读取走 [ChatSourceGuard]（路径校验集中在那一处）。
 */
class ChatLatestPillTest {

    @Test
    fun `胶囊按位置显示 点击走统一贴底`() {
        val code = ChatSourceGuard.readSourceCode(
            "app/src/main/java/io/github/m0use001/planability/ui/info/ChatScreen.kt"
        )
        assertTrue(
            "胶囊显示条件没挂位置判定（showLatestPill）——惯性滚动期间会延迟出现",
            code.contains("if (showLatestPill)")
        )
        assertTrue(
            "点击没有走统一贴底函数 snapToBottom（requestScrollToItem 瞬时定位，不逐个组合中间 item）",
            code.contains("runCatching { snapToBottom() }")
        )
        assertFalse(
            "点击又改回 animateScrollToItem 了（长列表点击必卡）",
            code.contains("animateScrollToItem(n - 1")
        )
    }
}
