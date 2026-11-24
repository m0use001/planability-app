package io.github.m0use001.planability.widget

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 2026-11-15 用户（截图）：「在详细编辑界面设置完成百分比后，时间轴对应代表出现胶囊
 * 按照百分比填充，但整体没有变暗」。
 *
 * 根因：时间轴块的 `isDim` 只认勾选完成（`b.done`），`percentDone` 只驱动标题左边的
 * 状态胶囊填充，**完全不参与整块变暗** —— 于是 30% 进度的块和 0% 的块视觉上一模一样。
 *
 * 修法：完成度 progress = done ? 1 : percentDone/100，**底色 alpha、标题色、副文字
 * 全部按 progress 连续插值**（不是二值跳变）。
 *
 * 这是 Compose 层的视觉断言跑不到的东西，按仓库惯例用源码结构钉（与
 * [WidgetHeaderLayoutTest] 同理由）。
 */
class TimelineBlockDimTest {

    private fun grid() = java.io.File(
        "src/main/java/io/github/m0use001/planability/ui/home/TimelineGrid.kt"
    ).readText()

    @Test
    fun 完成度要由百分比推导() {
        val g = grid()
        assertTrue(
            "必须由 percentDone 推导 progress（否则百分比只影响胶囊、不影响整块）",
            g.contains("b.percentDone in 0..100 -> b.percentDone / 100f")
        )
        assertTrue("done 应视为满进度", g.contains("b.done -> 1f"))
    }

    @Test
    fun 底色按完成度连续变暗() {
        val g = grid()
        assertTrue(
            "底色 alpha 必须按 progress 插值（0.9→0.28），不能二值",
            g.contains("val bgAlpha = 0.9f - (0.9f - 0.28f) * progress")
        )
        assertFalse(
            "旧的二值 isDim 分支不能回潮",
            g.contains("if (isDim) 0.28f else 0.9f")
        )
    }

    @Test
    fun 文字也随完成度变暗() {
        val g = grid()
        assertTrue(
            "标题色要按 progress 向 dim 色插值（底块暗了字还全亮=看起来仍像没开始）",
            g.contains("lerp(") && g.contains("(progress * 0.85f).coerceIn(0f, 1f)")
        )
        assertTrue(
            "副文字透明度也要跟着压（subAlpha 由 progress 推导）",
            g.contains("val subAlpha = 0.85f - (0.85f - 0.35f) * progress")
        )
    }
}
