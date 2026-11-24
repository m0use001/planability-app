package io.github.m0use001.planability.ui.info

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 2026-11-20「『最新』按钮跳转又失效」的纯函数判据。
 *
 * 病根：流式期间最后那条气泡一直在长，「在底部」若用严格判（canScrollForward），
 * 长出几个像素就被判成「离底」——跟随 effect 的「上一帧在底才准贴」闸被一次
 * 误判永久关死（视口被甩在后面，「之后不再跟」）。
 *
 * 这里钉死容差判据的边界：**容差之内算在底，容差之外不算**。
 */
class ChatScrollBottomSlackTest {

    @Test
    fun `真贴底时底边在视口底边之上_算在底`() {
        // 真贴底：item 底边比视口底边高出底部内容边距（负差）
        assertTrue(isAtBottomWithSlack(itemBottom = 1000f, viewportEnd = 1010f, slackPx = 64f))
    }

    @Test
    fun `尾巴长出一点仍在容差内_算在底`() {
        // 气泡又长了 30px：底边到了视口底边之下 30px < 容差 64px
        assertTrue(isAtBottomWithSlack(itemBottom = 1040f, viewportEnd = 1010f, slackPx = 64f))
    }

    @Test
    fun `超出容差_不算在底`() {
        // 差 200px > 容差 64px：真的离底了
        assertFalse(isAtBottomWithSlack(itemBottom = 1210f, viewportEnd = 1010f, slackPx = 64f))
    }

    @Test
    fun `容差为零时退化成严格判`() {
        assertTrue(isAtBottomWithSlack(itemBottom = 1010f, viewportEnd = 1010f, slackPx = 0f))
        assertFalse(isAtBottomWithSlack(itemBottom = 1011f, viewportEnd = 1010f, slackPx = 0f))
    }

    @Test
    fun `容差必须是正数_否则容差判毫无意义`() {
        assertTrue(BOTTOM_SLACK_DP > 0f)
    }
}
