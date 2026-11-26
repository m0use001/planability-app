package io.github.m0use001.planability.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 2026-11-20 钳制模型重写（用户拍板「壮士断腕」）的回归网。
 *
 * 前身 `ScrollFollowTest` 守的是「scrollToItem 命令式贴底」的 16 分支状态机；
 * 三轮大修（11-18 反向拽 / 11-19 卡住 / 11-20 跳转失效）证明那个原语本身就是病根，
 * 状态机连同它的「上一帧在底才准贴」闸、afterGlue 等帧读数一起删除。
 *
 * 新执行层只剩一个纯布尔判定 [BottomClamp.decide]：真值表穷举（128 组合）
 * + 时序回归（流式增长连钳、上滑落手不钳、历史位不钳、收尾补钳）。
 * 被守护的东西：**只在「流式 + 钉底 + 手不在列表上 + 没贴平 +（末项可见 或 跟随历史干净）」
 * 时钳底**，其余一切情况视口分毫不动。
 *
 * 2026-11-24 新增 cleanFollow/userScrolling 两输入（用户「流式跟一会就停」实测回归）：
 * 末项（底部矮活行）被上方气泡一帧长一大截**整体推出视口**时 lastVisibleIsLast 永久
 * 翻 false，旧判据死锁——视口冻住直到流式结束猛跳。死锁态与「用户上滑走了」只能靠
 * 历史区分：跟随以来没见过用户滚动（拖拽/惯性）= 内容顶出去的，照钳；见过 = 不许拽回。
 */
class BottomClampTest {

    private fun decide(
        streaming: Boolean = true,
        pinned: Boolean = true,
        dragged: Boolean = false,
        lastVisibleIsLast: Boolean = true,
        canScrollForward: Boolean = true,
        userScrolling: Boolean = false,
        cleanFollow: Boolean = false,
    ) = BottomClamp.decide(
        BottomClamp.Input(
            streaming, pinned, dragged, lastVisibleIsLast, canScrollForward,
            userScrolling, cleanFollow,
        )
    )

    // ---------- 真值表穷举：128 种输入组合 ----------

    @Test
    fun `全组合穷举_七输入只允许一种通过`() {
        for (streaming in listOf(false, true)) {
            for (pinned in listOf(false, true)) {
                for (dragged in listOf(false, true)) {
                    for (lastIsLast in listOf(false, true)) {
                        for (fwd in listOf(false, true)) {
                            for (userScrolling in listOf(false, true)) {
                                for (cleanFollow in listOf(false, true)) {
                                    val expected = streaming && pinned && !dragged && fwd &&
                                        (lastIsLast || (cleanFollow && !userScrolling))
                                    val got = decide(
                                        streaming, pinned, dragged, lastIsLast, fwd,
                                        userScrolling, cleanFollow,
                                    )
                                    assertEquals(
                                        "streaming=$streaming pinned=$pinned dragged=$dragged " +
                                            "lastIsLast=$lastIsLast fwd=$fwd " +
                                            "userScrolling=$userScrolling cleanFollow=$cleanFollow",
                                        expected, got
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // ---------- 逐条件语义钉死 ----------

    @Test
    fun `非流式绝不钳`() {
        // 流式结束（收尾补钳由执行侧的 !streaming 分支负责，决策机不管）
        assertFalse(decide(streaming = false))
    }

    @Test
    fun `没钉底绝不钳_用户在看历史内容长多少都不动视口`() {
        assertFalse(decide(pinned = false))
    }

    @Test
    fun `手指按在列表上绝不钳`() {
        assertFalse(decide(dragged = true))
    }

    @Test
    fun `视口不在列表末尾绝不钳_这就是不许把看历史的用户拽回去的那道闸`() {
        assertFalse(decide(lastVisibleIsLast = false))
    }

    @Test
    fun `已贴平绝不钳_幂等的另一半`() {
        assertFalse(decide(canScrollForward = false))
    }

    @Test
    fun `五个条件全满足才钳`() {
        assertTrue(decide())
    }

    // ---------- 2026-11-24 死锁回归（用户「流式跟一会就停」） ----------

    @Test
    fun `末项被流式增长整体推出视口_跟随历史干净_仍要钳`() {
        // 贴底跟随中（活行可见、末项就是末项）→ 钳
        assertTrue(decide())
        // 上一帧长一大截：矮活行整体被推出视口（lastVisibleIsLast=false），
        // 但跟随以来没见过用户滚动 → 死锁豁免，照钳拉回
        assertTrue(decide(lastVisibleIsLast = false, cleanFollow = true))
        // 钳完贴平 → 停
        assertFalse(decide(canScrollForward = false, cleanFollow = true))
    }

    @Test
    fun `见过用户滚动之后_末项不在视口_一次都不许钳`() {
        // 用户上滑（拖拽期）
        assertFalse(decide(dragged = true, lastVisibleIsLast = false, cleanFollow = false))
        // 惯性期（手指已离开，isScrollInProgress 仍 true）
        assertFalse(
            decide(lastVisibleIsLast = false, userScrolling = true, cleanFollow = false)
        )
        // 惯性落定，视口停在半路：一次都不许拽回
        repeat(30) {
            assertFalse(
                "上滑后内容增长必须完全不干预视口",
                decide(lastVisibleIsLast = false, cleanFollow = false)
            )
        }
    }

    // ---------- 时序回归 ----------

    @Test
    fun `流式增长连钳：每帧长高都钳_贴平后自然停`() {
        // 气泡长高 → 残余可滚 → 钳；钳完贴平 → 不再钳
        assertTrue(decide(canScrollForward = true))
        // 钳完（scrollBy(MAX) 落地）canScrollForward=false
        assertFalse(decide(canScrollForward = false))
        // 气泡又长高 → 再钳（幂等，无状态，无「上一帧」记忆）
        assertTrue(decide(canScrollForward = true))
    }

    @Test
    fun `上滑落手之后流式内容再增长也不会被拽回底部`() {
        // 手指拖动上滑：视口离开末尾 + 手还按着
        assertFalse(decide(dragged = true, lastVisibleIsLast = false))
        // 落手：手指离开，但视口仍停在半路（末项不可见）
        assertFalse(decide(dragged = false, lastVisibleIsLast = false))
        // 之后流式每帧增长：lastVisibleIsLast 恒 false，一次都不许钳
        repeat(30) {
            assertFalse("上滑后内容增长必须完全不干预视口", decide(lastVisibleIsLast = false))
        }
    }

    @Test
    fun `用户滑回底部后跟随重新生效`() {
        // 手指把列表拖回末尾（还按着：不钳）
        assertFalse(decide(dragged = true, lastVisibleIsLast = true))
        // 松手：末项可见 + 有残余 → 钳
        assertTrue(decide(dragged = false, lastVisibleIsLast = true))
        // 执行侧贴平后 cleanFollow 重新记 true（死锁豁免恢复武装）
        assertTrue(decide(lastVisibleIsLast = false, cleanFollow = true))
    }

    @Test
    fun `钉底时视口末项就是末项_流式增长直接钳`() {
        // 老模型的病根场景：钉在底部，气泡一帧长一大截击穿容差。
        // 钳制模型没有容差参与跟随判据——lastVisibleIsLast + canScrollForward 直说。
        assertTrue(decide())
    }
}
