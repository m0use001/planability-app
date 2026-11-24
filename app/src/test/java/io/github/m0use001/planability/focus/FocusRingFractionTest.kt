package io.github.m0use001.planability.focus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * 专注进度环的语义闸（2026-11-03）。
 *
 * 用户报「正计时还显示一个环」：正计时 Work 阶段 [FocusTimerState.remainSec] 是**已用秒数**、
 * [FocusTimerState.totalSec] 是 25 分钟占位，`remain/total` 会从 1 瞬间掉到 ≈0 再花 25 分钟
 * 爬满——环画出来就是错的。判定统一收在 [FocusTimerState.ringFraction]（null = 不画弧），
 * 这里把四种情形钉死：倒计时递减、正计时恒 null、空闲 null、休息阶段照常。
 *
 * 同时记录一条实测：倒计时环本身是**好的**（真机/模拟器 105 秒实测缺口 25°，理论 25.2°），
 * 之前看着"不动"是因为 25 分钟里前一两分钟只差几度、加上圆头描边把缺口补上了。
 */
class FocusRingFractionTest {

    @Before
    fun reset() {
        FocusTimerState.reset()
    }

    @Test
    fun `倒计时从满环按剩余比例递减`() {
        FocusTimerState.startFocus(25, countUpMode = false, rounds = 4)
        assertEquals("刚开始=满环", 1f, FocusTimerState.ringFraction()!!, 1e-6f)
        repeat(150) { FocusTimerState.tick() }      // 150s / 1500s
        assertEquals(0.9f, FocusTimerState.ringFraction()!!, 1e-6f)
        repeat(1350) { FocusTimerState.tick() }     // 走到 0
        val end = FocusTimerState.ringFraction()
        // 走完后 phase=Finished，环按 0 收口（不是 null：Finished 没有"不画"的语义）
        assertEquals(0f, end ?: 0f, 1e-6f)
    }

    @Test
    fun `正计时不参与进度环`() {
        FocusTimerState.startFocus(25, countUpMode = true, rounds = 4)
        assertNull("正计时一开始就不该画弧", FocusTimerState.ringFraction())
        assertEquals("正计时从 0 起算（不再借用 25 分占位，避免先闪 25:00）",
            0, FocusTimerState.remainSec.value)
        assertEquals("正计时没有总时长", 0, FocusTimerState.totalSec.value)
        repeat(10) { FocusTimerState.tick() }
        assertNull("正计时累计中仍不画弧", FocusTimerState.ringFraction())
        assertEquals("秒表本身在走（remain 当已用秒数）", 10, FocusTimerState.remainSec.value)
    }

    @Test
    fun `空闲态不画弧`() {
        assertNull(FocusTimerState.ringFraction())
    }

    @Test
    fun `休息阶段按剩余比例走`() {
        FocusTimerState.enterStage(FocusTimerState.Stage.ShortBreak, 5)
        assertEquals(1f, FocusTimerState.ringFraction()!!, 1e-6f)
        repeat(60) { FocusTimerState.tick() }
        assertEquals(4f / 5f, FocusTimerState.ringFraction()!!, 1e-6f)
    }
}
