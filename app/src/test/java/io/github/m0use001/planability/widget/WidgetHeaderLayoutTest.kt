package io.github.m0use001.planability.widget

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 2026-11-15 用户两条小组件报障：
 *
 * ① 今日款：「怎么今天时间轴显示明天日程，下面 7 点的未来计划页不明显
 *    （如果是不在时间轴范围内但当天接下来要经历的计划要单独标出，不要跟时间轴混在一起）」
 *    ——截图里 17:00–18:00 轴段中画着「下一场 · 明天 14:00 身体素质」，而当天 19:00
 *    真正接下来的计划只占一条不显眼的普通行。
 *
 * ② 多天总览：「这个组件顶部排的乱七八糟的」——标题「11/9–11/15」与右侧范围胶囊抢位。
 *
 * 都是**位图绘制**的版面问题（真机上肉眼可见，但单测跑不到渲染），按仓库惯例
 * 用源码断言钉住结构（与 [TodayTimelineTest] 同理由）。
 */
class WidgetHeaderLayoutTest {

    private fun read(rel: String) = java.io.File("src/main/$rel").readText()

    private fun art() = read("java/io/github/m0use001/planability/widget/WidgetArt.kt")
    private fun today() = read("java/io/github/m0use001/planability/widget/TodayWidget.kt")

    // ── ① 今日款：窗外当天安排单独一块，不进时间轴 ──

    @Test
    fun 今日款_窗外当天场次单独成块不进时间轴() {
        val art = art()
        assertTrue(
            "TodayData 必须有 upcomingToday 字段（今天窗外的场次单独列出）",
            art.contains("val upcomingToday: List<TodayEvent> = emptyList()")
        )
        assertTrue(
            "TodayData 必须有 nextOtherDayText 字段（跨天的下一场，不进轴）",
            art.contains("val nextOtherDayText: String = \"\"")
        )
        assertTrue(
            "「接下来」块必须有分隔线把它与时间轴切开",
            art.contains("接下来") && art.contains("val sepY = schedBottom + h * 0.012f")
        )
    }

    @Test
    fun 今日款_空态不再把下一场画在轴中部() {
        val art = art()
        val emptyBlock = art.substringAfter("if (data.events.isEmpty()) {").substringBefore("} else {")
        assertFalse(
            "空态不能画 nextText（那是跨天的场次，用户报「今天时间轴显示明天日程」）",
            emptyBlock.contains("data.nextText")
        )
        assertTrue("空态应只说窗内确实没安排", emptyBlock.contains("data.emptyText"))
    }

    @Test
    fun 今日款_数据层把当天窗外与跨天分开() {
        val w = today()
        assertTrue(
            "数据层要按「同一天且窗外」筛出 upcomingToday",
            w.contains("st.toLocalDate() == today && occ.start > now")
        )
        assertTrue(
            "数据层要单独产出 nextOtherDayText（别的日子）",
            w.contains("nextOtherDayText") && w.contains("toLocalDate() != today")
        )
        assertFalse(
            "旧的 nextText 补位逻辑必须退役（它把明天塞进今天的轴）",
            w.contains("val nextText =")
        )
    }

    // ── ② 多天总览：标题与胶囊不抢位 ──

    @Test
    fun 多天总览_标题按可用宽缩字且与胶囊垂直居中() {
        val art = art()
        assertTrue(
            "标题宽度要受胶囊左侧可用宽约束（否则长标题压到胶囊上）",
            art.contains("val titleMaxW = zoneLeft - padX - w * 0.03f")
        )
        assertTrue(
            "标题超宽要等比缩字",
            art.contains("titleInk *= (titleMaxW / mw)")
        )
        assertTrue(
            "标题要与胶囊同带垂直居中（draw 的第二参是墨迹顶，要用实际墨迹高算）",
            art.contains("chipTop + (chipH - titleSized.inkHeight) / 2f")
        )
    }
}
