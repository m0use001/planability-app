package io.github.m0use001.planability

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import io.github.m0use001.planability.widget.WidgetArt
import io.github.m0use001.planability.widget.artColors
import org.junit.Test
import java.io.File

/**
 * 小组件「多形态渲染快照」——给**视觉验收**当证据源（不是断言测试）。
 *
 * 为什么需要它：Glance 小组件的真实观感取决于桌面网格给的 DpSize（4×2 / 2×2 / 竖长…），
 * 而桌面摆位在模拟器上很难稳定复现（要拖拽、要改 launcher DB）。这里直接调
 * `WidgetArt.render*`（**与桌面同一份渲染管线**），按多种真实摆位各出一张 PNG。
 *
 * 跑完从 `Android/data/<pkg>/files/widget-shots/` pull 下来，连同规格卡交给**独立评审**
 * （它只看图与像素数字，不看源码——这是本流程能收敛的关键）。
 *
 * 跑法：
 * ```
 * ANDROID_SERIAL=emulator-5556 ./gradlew :app:connectedDebugAndroidTest \
 *   -Pandroid.testInstrumentationRunnerArguments.class=io.github.m0use001.planability.WidgetShotTest
 * ```
 */
class WidgetShotTest {

    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext

    /** 真实摆位：4×2 横卡 / 2×2 方卡 / 竖长（用户实拍过的问题形态） */
    private val sizes = listOf(
        "4x2" to DpSize(304.dp, 138.dp),
        "2x2" to DpSize(140.dp, 138.dp),
        "tall" to DpSize(125.dp, 187.dp)
    )

    private fun save(name: String, bmp: android.graphics.Bitmap) {
        val dir = File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "widget-shots").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use {
            bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    @Test
    fun shootTodayWidget() {
        val now = 1_791_000_000_000L   // 固定锚点，时间轴刻度可复现
        // 采样数据必须**自洽**：timeText 由 startMs 推出。第 1 轮评审的 A1/A3 就是我手写
        // timeText="10:00" 却把 startMs 放在窗口内——评审按图判「窗外日程也显示了」与
        // 「now 不在中线」，是数据自相矛盾造成的假阳性，不是渲染 bug。
        fun hhmm(ms: Long): String = java.time.Instant.ofEpochMilli(ms)
            .atZone(java.time.ZoneId.systemDefault())
            .format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))
        val ev1Start = now - 20 * 60_000L
        val ev1End = now + 20 * 60_000L
        val ev2Start = now + 40 * 60_000L
        val ev2End = now + 80 * 60_000L
        val data = WidgetArt.TodayData(
            dayNumber = "5",
            weekday = "周三",
            month = "11月",
            stats = "待办 2/5 · 日程 2 场 · 专注 32 分",
            events = listOf(
                WidgetArt.TodayEvent(
                    WidgetArt.EventKind.TIMED, "高等数学", "${hhmm(ev1Start)}–${hhmm(ev1End)}",
                    startMs = ev1Start, endMs = ev1End
                ),
                WidgetArt.TodayEvent(
                    WidgetArt.EventKind.TIMED, "线性代数", "${hhmm(ev2Start)}–${hhmm(ev2End)}",
                    startMs = ev2Start, endMs = ev2End
                )
            ),
            emptyText = "窗口内没有日程",
            nowMs = now,
            nextText = "下一场：线性代数 " + hhmm(ev2Start),
            todos = listOf(
                WidgetArt.TodoLine("交微积分作业", 60),
                WidgetArt.TodoLine("背单词 50 个", -1),
                WidgetArt.TodoLine("复习线代第三章", 20)
            ),
            todoMore = 2
        )
        sizes.forEach { (name, size) ->
            save(
                "today-$name",
                WidgetArt.renderToday(ctx, size, artColors(ctx, size), 400, "default", 1.0f, data)
            )
        }
    }

    @Test
    fun shootStatsWidget() {
        val data = io.github.m0use001.planability.widget.TodayStatsArt.Data(
            listOf(
                io.github.m0use001.planability.widget.TodayStatsArt.Row("待办", "3/11", 0.27f),
                io.github.m0use001.planability.widget.TodayStatsArt.Row("睡眠", "6h58m", 1.0f),
                io.github.m0use001.planability.widget.TodayStatsArt.Row("专注", "25m", 0.52f),
                io.github.m0use001.planability.widget.TodayStatsArt.Row("屏幕", "6h20m", 0.79f)
            )
        )
        sizes.forEach { (name, size) ->
            save(
                "stats-$name",
                io.github.m0use001.planability.widget.TodayStatsArt.render(
                    ctx, size, artColors(ctx, size), 400, "default", 1.0f, data
                )
            )
        }
    }
}
