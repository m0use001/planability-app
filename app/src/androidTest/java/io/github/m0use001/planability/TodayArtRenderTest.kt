package io.github.m0use001.planability

import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import io.github.m0use001.planability.widget.WidgetArt
import io.github.m0use001.planability.widget.artColors
import org.junit.Test
import java.io.File
import java.time.Instant

/**
 * 「今日」时间轴款位图渲染的**落盘验收**（与 [RangeArtRenderTest] 同一路）：
 * 按 4×3 实际占位口径（真机 4 列 ≈249dp × 3 行 ≈187dp，@3.5x）渲三张 PNG，
 * 覆盖：常规窗口（进行中+即将开始）、两场并行（分列）、空窗口。
 * 验收人 pull 下来人工核对样式——getExternalFilesDir 可写，/data/local/tmp 不可写（老坑）。
 */
class TodayArtRenderTest {

    @Test
    fun renderTimelineVariants() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val outDir = File(ctx.getExternalFilesDir(null), "today-art").apply { mkdirs() }
        val size = androidx.compose.ui.unit.DpSize(249.dp, 187.dp)
        val colors = artColors(ctx, size)
        val now = System.currentTimeMillis()
        fun ms(offsetMin: Long) = now + offsetMin * 60_000
        fun hm(t: Long): String {
            val dt = Instant.ofEpochMilli(t).atZone(java.time.ZoneId.systemDefault())
            return "%02d:%02d".format(dt.hour, dt.minute)
        }
        fun ev(title: String, s: Long, e: Long) = WidgetArt.TodayEvent(
            WidgetArt.EventKind.TIMED, title, "${hm(s)}–${hm(e)}", startMs = s, endMs = e
        )

        val variants = listOf(
            // 常规：一场进行中（跨 now 线）+ 一场 40 分钟后开始
            "today-normal" to WidgetArt.TodayData(
                dayNumber = "2", weekday = "周日", month = "11月",
                stats = "待办 1/4 · 日程 2 场 · 专注 32 分",
                events = listOf(
                    ev("进行中的周会", ms(-20), ms(25)),
                    ev("写周报", ms(40), ms(75))
                ),
                emptyText = "未来 1 小时没有安排", nowMs = now,
                todos = listOf(
                    WidgetArt.TodoLine("补 Python 笔记", 60),
                    WidgetArt.TodoLine("去驿站退货", -1),
                    WidgetArt.TodoLine("四级词汇 30 分钟", -1),
                )
            ),
            // 并行两场：同时段分两列不互盖
            "today-parallel" to WidgetArt.TodayData(
                dayNumber = "2", weekday = "周日", month = "11月",
                stats = "待办 0/2 · 日程 3 场",
                events = listOf(
                    ev("英语角", ms(-10), ms(50)),
                    ev("项目评审", ms(-10), ms(50)),
                    ev("刚结束的晨跑", ms(-55), ms(-15))
                ),
                emptyText = "未来 1 小时没有安排", nowMs = now
            ),
            // 空窗口 + 下一场 + 待办溢出（2026-11-02 晚用户报「没能显示当前日程、待办」的形态：
            // 深夜 23:35，窗口 [22:35,00:35] 内今天已无日程）
            "today-next-todos" to WidgetArt.TodayData(
                dayNumber = "2", weekday = "周日", month = "11月",
                stats = "待办 2/9 · 日程 10 场 · 专注 25 分",
                events = emptyList(),
                emptyText = "未来 1 小时没有安排", nowMs = now,
                nextText = "下一场 · 明天 08:50 学术英语与国际交流",
                todos = listOf(
                    WidgetArt.TodoLine("补 Python 笔记", 60),
                    WidgetArt.TodoLine("去驿站退货", -1),
                    WidgetArt.TodoLine("四级词汇 30 分钟", -1),
                ),
                todoMore = 4
            ),
            // 2026-11-15 用户报障形态：窗内空 + **当天 19:00 窗外场次** + 跨天的下一场。
            // 「当天窗外」必须单独成块（时刻主色），「跨天」才退化成整句提示，
            // 两者都**不进时间轴**。
            "today-upcoming" to WidgetArt.TodayData(
                dayNumber = "15", weekday = "周日", month = "11月",
                stats = "待办 0/7 · 日程 0 场",
                events = emptyList(),
                emptyText = "未来 1 小时没有安排", nowMs = now,
                upcomingToday = listOf(
                    ev("四级：整卷模拟第1套", ms(75), ms(105)),
                    ev("晚间复盘", ms(180), ms(210))
                ),
                todos = listOf(
                    WidgetArt.TodoLine("补 Python 笔记", 60),
                )
            ),
            // 跨天的下一场：今天确实没有后续了 → 只给一句「下一场 · 明天 …」
            "today-other-day" to WidgetArt.TodayData(
                dayNumber = "15", weekday = "周日", month = "11月",
                stats = "待办 0/7 · 日程 0 场",
                events = emptyList(),
                emptyText = "未来 1 小时没有安排", nowMs = now,
                nextOtherDayText = "下一场 · 明天 14:00 身体素质"
            ),
            // 空窗口 + 没有下一场（今天日程已结束、次日也没有）
            "today-empty" to WidgetArt.TodayData(
                dayNumber = "2", weekday = "周日", month = "11月",
                stats = "待办 2/5 · 日程 0 场",
                events = emptyList(),
                emptyText = "未来 1 小时没有安排", nowMs = now
            )
        )
        variants.forEach { (name, data) ->
            val bmp = WidgetArt.renderToday(
                ctx, size, colors, 400, "default", 1.0f, data
            )
            File(outDir, "$name.png").outputStream().use { bmp.compress(
                android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
}
