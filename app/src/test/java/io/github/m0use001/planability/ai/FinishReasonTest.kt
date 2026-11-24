package io.github.m0use001.planability.ai

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `finish_reason` 的处理：**截断必须是可见的失败，不能是静默的成功**。
 *
 * 2026-10-20 真机事故：用户让它画流程图，界面停在"好的，给你一张……"。
 * 查备份库才看清 —— 那条 assistant 消息只有 27 个字、`fenceCount=0`、`status=done`。
 * 也就是说：模型被输出上限截断了，而客户端**完全没看 `finish_reason`**，
 * 照样把消息写成"成功"。用户看到的是"图生成到一半没了"，且没有任何提示。
 */
class FinishReasonTest {

    @Test
    fun `length 是失败，不是成功`() {
        val msg = finishReasonError("length")
        assertNotNull("被输出上限截断却当成成功 —— 用户会以为图就是没画出来", msg)
        assertTrue("要告诉用户这次不完整", msg!!.contains("截断"))
        assertTrue("要给用户下一步动作", msg.contains("重新生成"))
    }

    @Test
    fun `max_tokens 同样算截断`() {
        assertNotNull(finishReasonError("max_tokens"))
    }

    @Test
    fun `content_filter 要单独说清楚`() {
        val msg = finishReasonError("content_filter")
        assertNotNull(msg)
        assertTrue(msg!!.contains("安全策略"))
    }

    @Test
    fun `正常结束不报错`() {
        assertNull("stop 是正常结束", finishReasonError("stop"))
        assertNull("tool_calls 是正常结束（后面还有工具轮）", finishReasonError("tool_calls"))
        assertNull("没有这个字段时不能瞎报", finishReasonError(null))
        assertNull("空串不能瞎报", finishReasonError(""))
    }
}
