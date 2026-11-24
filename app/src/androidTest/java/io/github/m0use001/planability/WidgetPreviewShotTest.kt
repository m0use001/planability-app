package io.github.m0use001.planability

import android.content.Context
import android.content.res.Configuration
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import io.github.m0use001.planability.ui.theme.colorSchemeFromFixedStyle
import io.github.m0use001.planability.widget.BlobEntry
import io.github.m0use001.planability.widget.ClockArt
import io.github.m0use001.planability.widget.ClockData
import io.github.m0use001.planability.widget.WidgetArt
import io.github.m0use001.planability.widget.WidgetPalette
import io.github.m0use001.planability.widget.countdownCoinBitmap
import org.junit.Test
import java.io.File
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

/**
 * 预览图（previewImage PNG）重生成器——v55 预览审查（2026-11-07），非断言测试。
 *
 * 背景：res/drawable(-night)-nodpi/widget_preview_*.png 停在 10-26/10-28 的旧设计
 * （todaystats 还是「白条药丸+托盘」、today 还是「大日号+FAB+事件条」、range 月历
 * 今天圈在 24 日），而运行时渲染在 11-02/11-03/11-06 经历了整卡时间轴/四胶囊/视觉
 * 验收四轮收敛；且各图示例数据互相矛盾（时钟 10/24、today 10/26、range 圈 10/24）。
 *
 * 本测试与 [WidgetShotTest] 同一原则：**直接调 WidgetArt/ClockArt/TodayStatsArt 等
 * 与桌面同一份渲染管线**，按各组件默认摆位（provider targetCell）的真实 dp 出图，
 * 保证预览图比例、版式、字号分支与真机一致。
 *
 * 统一示例场景（全部预览共享，锚点 2026-11-07 周六）：
 *   日程：高等数学 09:00–10:30（已过）、项目组会 14:30–15:30（进行中）、线性代数 15:50–17:20
 *   待办：5 条完成 2；可见未完成 交微积分作业 60% / 背单词 50 个 / 复习线代第三章 20%
 *   统计：待办 2/5 · 日程 3 场 · 专注 45 分；今日数据同源（2/5、睡眠 6h58m、专注 45m、屏幕 3h20m）
 *   屏幕时间：总 3h 20m = Planability 1h26m + Google 1h5m + Contacts 49m（各行相加自洽）
 *   倒数日：期末考试 2027.1.1 → 55 天；时钟 11/7 15:00 · 今日完成率 40%（=2/5）
 *
 * 跑法（模拟器，绝不连日用机——AGENTS.md §4.2）：
 * ```
 * ANDROID_SERIAL=emulator-5554 ./gradlew :app:connectedDebugAndroidTest \
 *   -Pandroid.testInstrumentationRunnerArguments.class=io.github.m0use001.planability.WidgetPreviewShotTest
 * ```
 * 产物 pull 自 `Android/data/<pkg>/files/widget-shots/preview/`，覆盖
 * `res/drawable-nodpi`（浅色）与 `res/drawable-night-nodpi`（深色）同名资源。
 */
class WidgetPreviewShotTest {

    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext

    /** 统一锚点：2026-11-07 周六，now=15:00（±1h 窗口内含项目组会+线性代数两场） */
    private val anchor: LocalDate = LocalDate.of(2026, 11, 7)
    private val zone: ZoneId = ZoneId.systemDefault()

    private fun ms(hour: Int, minute: Int): Long =
        anchor.atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()

    private fun hhmm(hour: Int, minute: Int) = "%02d:%02d".format(hour, minute)

    /**
     * 默认摆位真实 dp（provider targetCellWidth/Height，格子 ≈76×69dp，
     * 与 WidgetShotTest 的 4x2=304×138 同一口径）：
     * clock 4×2 / clock_stacked 2×3 / today 4×3 / range 4×4 / todaystats 4×2 /
     * screentime 2×1 / countdown 2×2。
     */
    private val sizes = mapOf(
        "clock" to DpSize(304.dp, 138.dp),
        "clock_stacked" to DpSize(152.dp, 207.dp),
        "today" to DpSize(304.dp, 207.dp),
        "range" to DpSize(304.dp, 276.dp),
        "todaystats" to DpSize(304.dp, 138.dp),
        "screentime" to DpSize(152.dp, 69.dp),
        "countdown" to DpSize(152.dp, 138.dp),
    )

    /** 560dpi = 日用机 3.5x 同密度，绝对 dp 的布局决策与真机一致且出图够清晰 */
    private fun themed(night: Boolean): Context {
        val cfg = Configuration(ctx.resources.configuration)
        cfg.uiMode = (cfg.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
            (if (night) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO)
        cfg.densityDpi = 560
        return ctx.createConfigurationContext(cfg)
    }

    /**
     * 与 `TodayWidget.artColors` 同一套映射，但**绕开 Graph.settings**直接用默认
     * 拼色方案（经典绿 0 号）——预览资产必须不随模拟器/设备的主题设置漂移。
     * artColors 若改映射，这里要跟（以它为真源）。
     */
    private fun colors(dark: Boolean): WidgetArt.Colors {
        val s = colorSchemeFromFixedStyle(0, dark)
        val p = WidgetPalette(s, dark, 1f)
        return WidgetArt.Colors(
            card = p.artCard.toArgb(),
            onCard = p.artOnCard.toArgb(),
            sub = p.subColor.toArgb(),
            primary = p.accentColor.toArgb(),
            onPrimary = s.onPrimary.toArgb(),
            primaryContainer = p.artPrimaryContainer.toArgb(),
            onPrimaryContainer = p.artOnPrimaryContainer.toArgb(),
            secondaryContainer = s.secondaryContainer.toArgb(),
            onSecondaryContainer = s.onSecondaryContainer.toArgb(),
            tertiaryContainer = s.tertiaryContainer.toArgb(),
            onTertiaryContainer = s.onTertiaryContainer.toArgb(),
            clockFace = p.clockCardColor.toArgb(),
            heroNumber = p.heroNumberColor.toArgb()
        )
    }

    private fun save(name: String, bmp: android.graphics.Bitmap) {
        val dir = File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "widget-shots/preview")
            .apply { mkdirs() }
        File(dir, "$name.png").outputStream().use {
            bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    // —— 统一示例数据（各组件一份，字段语义见各 Data 类） ——

    private fun todayData() = WidgetArt.TodayData(
        dayNumber = "7",
        weekday = "周六",
        month = "11月",
        stats = "待办 2/5 · 日程 3 场 · 专注 45 分",
        events = listOf(
            WidgetArt.TodayEvent(
                WidgetArt.EventKind.TIMED, "项目组会",
                "${hhmm(14, 30)}–${hhmm(15, 30)}", startMs = ms(14, 30), endMs = ms(15, 30)
            ),
            WidgetArt.TodayEvent(
                WidgetArt.EventKind.TIMED, "线性代数",
                "${hhmm(15, 50)}–${hhmm(17, 20)}", startMs = ms(15, 50), endMs = ms(17, 20)
            ),
        ),
        emptyText = "未来 1 小时没有安排",
        nowMs = ms(15, 0),
        nextText = "",
        todos = listOf(
            WidgetArt.TodoLine("交微积分作业", 60),
            WidgetArt.TodoLine("背单词 50 个", -1),
            WidgetArt.TodoLine("复习线代第三章", 20)
        ),
        todoMore = 0
    )

    private fun statsData() = io.github.m0use001.planability.widget.TodayStatsArt.Data(
        listOf(
            io.github.m0use001.planability.widget.TodayStatsArt.Row("待办", "2/5", 0.40f),
            io.github.m0use001.planability.widget.TodayStatsArt.Row("睡眠", "6h58m", 1.0f),
            io.github.m0use001.planability.widget.TodayStatsArt.Row("专注", "45m", 0.50f),
            io.github.m0use001.planability.widget.TodayStatsArt.Row("屏幕", "3h20m", 0.83f)
        )
    )

    private fun screenTimeData() = io.github.m0use001.planability.widget.ScreenTimeArt.Data(
        total = "3h 20m",
        // 短名（2 字 CJK）：默认 2×1 很窄，长名（如 Planability）会被截成「Planab…」，
        // 预览要展示干净的真实形态；总时长与「今日数据」的屏幕行同源（3h20m）
        rows = listOf(
            io.github.m0use001.planability.widget.ScreenTimeArt.Row("1h26m", "微信", 86),
            io.github.m0use001.planability.widget.ScreenTimeArt.Row("1h5m", "相机", 65),
            io.github.m0use001.planability.widget.ScreenTimeArt.Row("49m", "电话", 49)
        ),
        emptyText = "今天还没有使用记录"
    )

    /** 月档 42 格（周一首）：从 2026-11-01 所在周的周一开始 */
    private fun monthCells(): List<WidgetArt.MonthCell> {
        val first = YearMonth.of(2026, 11).atDay(1)
            .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        // 事件分布与 today 的故事同源：今天（7 日）日程+待办都在
        val withEvents = setOf(3, 5, 7, 10, 12, 13, 17, 19, 20, 24, 26)
        val twoBars = setOf(3, 7, 13, 20)
        return (0 until 42).map { i ->
            val d = first.plusDays(i.toLong())
            val inMonth = d.monthValue == 11
            val kinds = when {
                d == anchor -> listOf(0, 2)                       // 日程 + 待办
                d.dayOfMonth in twoBars && inMonth -> listOf(0, 2)
                d.dayOfMonth in withEvents && inMonth -> listOf(2)
                else -> emptyList()
            }
            WidgetArt.MonthCell(d.dayOfMonth.toString(), inMonth, d == anchor, kinds)
        }
    }

    private fun rangeData() = WidgetArt.RangeData(
        range = WidgetArt.Range.Month,
        monthTitle = "2026年11月",
        monthCells = monthCells(),
        columns = null,
        // v50.7（用户 2026-11-05「取消场数统计」）：底部汇总行撤除——预览与在途方向一致
        footer = ""
    )

    private fun clockData() = ClockData(
        timeText = "15:00",
        hh = "15",
        mm = "00",
        dateText = "11/7",
        rateText = "今日完成率 40%"
    )

    @Test
    fun shootAllPreviews() {
        for (night in listOf(false, true)) {
            val suffix = if (night) "-night" else ""
            val c = themed(night)
            val colors = colors(night)
            // 位图组件走 artColors 同源色板需要 ctx 带主题——这里统一用自建 colors，
            // 时钟色板直接取自 WidgetPalette（clockPaintColors 与 artColors 同一来源）
            val p = WidgetPalette(colorSchemeFromFixedStyle(0, night), night, 1f)
            val density = c.resources.displayMetrics.density

            save("clock$suffix", ClockArt.render(
                c, sizes.getValue("clock"), p.clockPaintColors, 400, "default", 1.0f,
                clockData(), ClockArt.Style.Digital
            ))
            save("clock_stacked$suffix", ClockArt.render(
                c, sizes.getValue("clock_stacked"), p.clockPaintColors, 400, "default", 1.0f,
                clockData(), ClockArt.Style.Stacked
            ))
            save("today$suffix", WidgetArt.renderToday(
                c, sizes.getValue("today"), colors, 400, "default", 1.0f, todayData()
            ))
            save("range$suffix", WidgetArt.renderRange(
                c, sizes.getValue("range"), colors, 400, "default", 1.0f, rangeData()
            ))
            save("todaystats$suffix", io.github.m0use001.planability.widget.TodayStatsArt.render(
                c, sizes.getValue("todaystats"), colors, 400, "default", 1.0f, statsData()
            ))
            save("screentime$suffix", io.github.m0use001.planability.widget.ScreenTimeArt.render(
                c, sizes.getValue("screentime"), colors, 400, "default", 1.0f, screenTimeData()
            ))
            // 倒数日：币形位图参数与 CountdownWidget.provideGlance 调用点同源
            val cd = sizes.getValue("countdown")
            save("countdown$suffix", countdownCoinBitmap(
                (cd.width.value * density).toInt(), (cd.height.value * density).toInt(), 1f,
                c, "default", 400,
                p.cardColor.toArgb(), p.heroNumberColor.toArgb(),
                p.artOnCard.toArgb(), p.subColor.toArgb(),
                BlobEntry("期末考试", 20270101, 55L, "")
            ))
        }
    }
}
