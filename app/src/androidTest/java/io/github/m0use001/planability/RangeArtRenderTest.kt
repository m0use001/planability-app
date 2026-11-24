package io.github.m0use001.planability

import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import io.github.m0use001.planability.widget.WidgetArt
import io.github.m0use001.planability.widget.artColors
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.LocalDate

/**
 * 「多天总览」位图渲染的**落盘验收**（2026-11-05 v2 时间轴改版）：
 * 3 天/周/月三档按真实管线（artColors + renderRange）各渲一张 PNG；
 * 3 天/周档同时落盘 manifest.json（列头/日期/每事件开始时间）——
 * 视觉验收评审 agent 按样本数据核对时间轴定位，不读源码。
 *
 * 样本数据（与规格卡 tmp/visual/2026-11-05-range/spec.md 一致）：
 * 今天 7 场（含 14:00/14:30 重叠对），其余列 0~2 场。
 * 输出走 getExternalFilesDir（应用沙箱外可 pull）。
 */
class RangeArtRenderTest {

    @Test
    fun renderAllThreeRanges() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val outDir = File(ctx.getExternalFilesDir(null), "range-art").apply { mkdirs() }
        outDir.listFiles()?.forEach { it.delete() }   // 旧产物清掉，避免评审拿到陈旧帧
        val size = androidx.compose.ui.unit.DpSize(250.dp, 250.dp)
        val colors = artColors(ctx, size)
        val today = LocalDate.now()

        fun monthData(): WidgetArt.RangeData {
            val first = today.withDayOfMonth(1)
            val gridStart = first.minusDays((first.dayOfWeek.value + 6) % 7L)
            val byDay = mapOf(
                today to 5,
                today.plusDays(1) to 1,
                today.plusDays(2) to 2,
                today.minusDays(4) to 1
            )
            val cells = (0 until 42).map { off ->
                val d = gridStart.plusDays(off.toLong())
                WidgetArt.MonthCell(
                    label = d.dayOfMonth.toString(),
                    inMonth = d.month == today.month,
                    isToday = d == today,
                    eventKinds = List(byDay[d] ?: 0) { it % 3 }
                )
            }
            return WidgetArt.RangeData(
                range = WidgetArt.Range.Month,
                monthTitle = "${today.year}年${today.monthValue}月",
                monthCells = cells,
                columns = null,
                // v50.7：场数统计取消（与生产 buildData 同步），footer 恒空
                footer = ""
            )
        }

        // 今天 7 场（含重叠对 14:00/14:30）；其余天按固定模式给 0~2 场
        val todayTimes = listOf("08:00", "09:30", "11:00", "14:00", "14:30", "18:00", "21:30")
        val otherTimes = mapOf(1 to listOf("10:00", "15:00"), 2 to listOf("09:00", "19:00"),
            3 to listOf<String>(), 4 to listOf("13:00"), 5 to listOf<String>(), 6 to listOf("11:00"))

        fun columnData(range: WidgetArt.Range): WidgetArt.RangeData {
            val days = if (range == WidgetArt.Range.Week) 7 else 3
            val start = if (range == WidgetArt.Range.Week)
                today.minusDays(((today.dayOfWeek.value + 6) % 7).toLong()) else today
            val heads = listOf("一", "二", "三", "四", "五", "六", "日")
            val manifestCols = mutableListOf<Map<String, Any?>>()
            val endLabel = start.plusDays((days - 1).toLong())
            val cols = (0 until days).map { off ->
                val d = start.plusDays(off.toLong())
                val todayOff = ((today.dayOfWeek.value + 6) % 7).toInt()
                val times: List<String> = if (d == today) todayTimes
                else otherTimes[(off - todayOff + days * 7) % days] ?: emptyList()
                val evs = times.mapIndexed { ti, t ->
                    // 标题按时间顺序编号（事项1..N），与规格卡样本表一致
                    WidgetArt.TodayEvent(
                        WidgetArt.EventKind.TIMED, "事项" + (ti + 1), t
                    )
                }
                manifestCols.add(mapOf(
                    "date" to d.toString(),
                    "isToday" to (d == today),
                    "head" to (if (range == WidgetArt.Range.Week) heads[d.dayOfWeek.value - 1]
                    else "周" + heads[d.dayOfWeek.value - 1]),
                    "events" to times
                ))
                WidgetArt.DayColumn(
                    head = if (range == WidgetArt.Range.Week) heads[d.dayOfWeek.value - 1]
                    else "周" + heads[d.dayOfWeek.value - 1],
                    dateLabel = d.dayOfMonth.toString(),
                    events = evs,
                    isToday = d == today
                )
            }
            // manifest：评审的样本数据真源（评审不读源码，按它核对时间轴定位）
            val manifest = mapOf(
                "range" to if (range == WidgetArt.Range.Week) "week" else "threedays",
                "renderedAt" to java.time.LocalDateTime.now().toString(),
                "axisNote" to "事件条 y = 开始分钟线性映射；今天列含 14:00/14:30 重叠对（lane 分离）",
                "columns" to manifestCols
            )
            File(outDir, "manifest-${if (range == WidgetArt.Range.Week) "week" else "threedays"}.json")
                .writeText(org.json.JSONObject(manifest).toString(2), Charsets.UTF_8)
            val rangeLabel = start.monthValue.toString() + "/" + start.dayOfMonth +
                "–" + endLabel.monthValue + "/" + endLabel.dayOfMonth
            // v50.7：场数统计取消（与生产 buildData 同步），footer 恒空
            return WidgetArt.RangeData(range, rangeLabel, null, cols, "")
        }

        listOf(
            "month" to monthData(),
            "week" to columnData(WidgetArt.Range.Week),
            "threedays" to columnData(WidgetArt.Range.ThreeDays)
        ).forEach { (name, data) ->
            val bmp = WidgetArt.renderRange(ctx, size, colors, 400, "default", 1.0f, data)
            File(outDir, "$name.png").outputStream().use { o ->
                bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, o)
            }
        }
        assertTrue("PNG 落盘目录：${outDir.absolutePath}", outDir.listFiles()!!.size >= 5)
    }
}
