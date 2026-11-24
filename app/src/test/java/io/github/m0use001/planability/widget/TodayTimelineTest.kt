package io.github.m0use001.planability.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「今日」**时间轴改版**的回归闸（2026-11-02）。
 *
 * 用户定案四项：强制 4×3（宽 4，不可拉伸）/ 只展示过去 1 小时到未来 1 小时 /
 * 取消加号按钮 / 顶部日期、底部小数据行保留。
 *
 * 选源码断言（与 [WidgetRangeDensityTest] 同理由）：要抓的是「锁被解开/FAB 加回来/
 * 窗口被放宽」这个行为本身——XML、渲染常量或数据层过滤一改这里就红。
 * 纯函数（窗口命中、并行分列）直接喂样本测语义。
 */
class TodayTimelineTest {

    private fun read(rel: String): String {
        val roots = listOf("app/src/main/", "src/main/")
        roots.forEach { r ->
            val f = java.io.File(r + rel)
            if (f.isFile) return f.readText()
        }
        return java.io.File("app/src/main/" + rel).readText()
    }

    @Test
    fun `组件声明锁死 4x3 且不可拉伸`() {
        val xml = read("res/xml/widget_today.xml")
        assertTrue("targetCellWidth 应为 4（宽 4）", xml.contains("android:targetCellWidth=\"4\""))
        assertTrue("targetCellHeight 应为 3", xml.contains("android:targetCellHeight=\"3\""))
        assertTrue(
            "resizeMode 应为 none（强制 4×3，不给拉伸手柄）",
            xml.contains("android:resizeMode=\"none\"")
        )
        assertTrue("minHeight 应为 3 格口径 190dp", xml.contains("android:minHeight=\"190dp\""))
    }

    @Test
    fun `加号按钮已删除`() {
        val art = read("java/io/github/m0use001/planability/widget/WidgetArt.kt")
        assertFalse("renderToday 不应再有 FAB 绘制（用户 2026-11-02 取消加号）",
            art.contains("fabSize"))
        assertFalse("不应再画 + 号线段", art.contains("canvas.drawLine(pcx"))
    }

    @Test
    fun `时间轴窗口是正负 1 小时且渲染与数据层同源`() {
        val art = read("java/io/github/m0use001/planability/widget/WidgetArt.kt")
        assertTrue("窗口半径应为 3600_000（1 小时）",
            art.contains("TIMELINE_WINDOW_MS: Long = 3600_000L"))
        assertTrue("渲染侧 now 线/刻度应来自 nowMs±窗口（数据层已过滤，渲染再钳一次）",
            art.contains("maxOf(ev.startMs, winStart)") && art.contains("minOf(ev.endMs, winEnd)"))
        val widget = read("java/io/github/m0use001/planability/widget/TodayWidget.kt")
        assertTrue("数据层应用 inTimelineWindow 过滤",
            widget.contains("inTimelineWindow(it.start, it.end, now)"))
        // v51（2026-11-13 用户「没把当前待办与今天的未设置事件待办分开」）：
        // 有时刻的待办（due>0）进时间轴、无时刻的才挂轴尾——按 due 分两路
        assertTrue("待办应按 due>0/due==0L 分成时间轴/不限时两路",
            widget.contains("filter { it.due > 0 }") && widget.contains("filter { it.due == 0L }"))
        assertTrue("有时刻待办应以 TODO_PENDING 进时间轴事件",
            widget.contains("EventKind.TODO_PENDING, t.title"))
        assertTrue("不限时圆点区只能吃 due==0 那一路（shownTodos 源自 untimedTodos）",
            widget.contains("untimedTodos.take(3)"))
        assertTrue("待办块底色应与日程块区分（第二色容器 vs 第三色容器）",
            art.contains("colors.secondaryContainer else colors.tertiaryContainer"))
        // v51.1（真库实锤）：重复待办的 due 停在旧锚点（date=11-13 但 due=11-12 20:30）——
        // 时刻只用 due 的 time-of-day、日子取 date 当天
        assertTrue("有时刻待办应按 date 当天 + due 时刻换算（occStart）",
            widget.contains("fun occStart("))
        assertTrue("窗外有时刻待办应在圆点区保留「下一项」行",
            art.contains("data.nextTodoText.isNotBlank()"))
    }

    @Test
    fun `顶部日期与底部统计行保留`() {
        val art = read("java/io/github/m0use001/planability/widget/WidgetArt.kt")
        assertTrue("日期行（月/日 + 星期）应保留", art.contains("datePart.draw"))
        assertTrue("底部统计行应保留且居中",
            art.contains("data.stats") && art.contains("drawCentered(canvas, w / 2f, h * 0.925f)"))
    }

    // ── 纯函数语义 ──

    @Test
    fun `窗口命中判定_边界紧邻不算重叠`() {
        val now = 1_000_000L
        val w = WidgetArt.TIMELINE_WINDOW_MS
        assertTrue("完全在窗口内", WidgetArt.inTimelineWindow(now - 60_000, now + 60_000, now))
        assertTrue("进行中（起点在过去终点在未来）", WidgetArt.inTimelineWindow(now - 7200_000, now + 1800_000, now))
        assertTrue("起点在窗口内但终点在窗外（被 clamp 而非丢弃）",
            WidgetArt.inTimelineWindow(now + 1800_000, now + 7200_000, now))
        assertFalse("end 恰为窗口起点=紧邻在外（零块无信息量，丢弃无损）",
            WidgetArt.inTimelineWindow(now - 7200_000, now - w, now))
        assertFalse("终点在窗口起点之外 1ms 不入窗",
            WidgetArt.inTimelineWindow(now - 7200_000, now - w - 1, now))
        assertFalse("整个在窗口之前", WidgetArt.inTimelineWindow(now - 7200_000, now - 3601_000, now))
        assertFalse("整个在窗口之后", WidgetArt.inTimelineWindow(now + 3601_000, now + 7200_000, now))
        assertEquals("窗口半径恒为 1 小时", 3600_000L, w)
    }

    @Test
    fun `并行日程贪心分列_串行共列`() {
        fun cols(vararg spans: Pair<Long, Long>) = WidgetArt.assignTimelineColumns(spans.toList())
        // 串行（前一块结束==后一块开始）：同一列
        val serial = cols(0L to 100L, 100L to 200L, 200L to 300L)
        assertTrue("串行事件应共列", serial.all { it == 0 })
        // 两场完全并行：分两列
        val parallel = cols(0L to 100L, 0L to 100L)
        assertTrue("并行事件应分列", parallel.toSet().size == 2)
        // A(0-100) B(50-150) C(100-200)：A 与 C 可共列（B 结束才释放？否——B 50-150 与 C 100-200 重叠，C 不能入 B 列；C 可入 A 列）
        val mix = cols(0L to 100L, 50L to 150L, 100L to 200L)
        assertEquals("A 与 C 串行可共列", mix[0], mix[2])
        assertTrue("B 与 A/C 均重叠，独占一列", mix[1] != mix[0] && mix[1] != mix[2])
        // 输出与输入同序（渲染按下标配块）
        val unordered = cols(300L to 400L, 0L to 100L)
        assertEquals("列号数组与输入同序", 2, unordered.size)
    }

    // ── v51：事件块底色对比度闸（用户「内容颜色与背景混在一起」）──

    private fun lumaOf(argb: Int): Float {
        val r = (argb shr 16 and 0xFF) / 255f
        val g = (argb shr 8 and 0xFF) / 255f
        val b = (argb and 0xFF) / 255f
        return 0.299f * r + 0.587f * g + 0.114f * b
    }

    @Test
    fun `块底对比度闸_暗色卡上把深容器提亮`() {
        // 真机实锤形状：卡 43,45,43（surfaceContainer 暗档）、块 47,46,98（tertiaryContainer）。
        // JVM 单测没有 mock android.graphics（见 PresetSchemeTest），用字面 ARGB。
        val card = 0xFF2B2D2B.toInt()
        val block = 0xFF2F2E62.toInt()
        val lifted = WidgetArt.blockBg(block, card)
        assertTrue("提亮后与卡的亮度差应 ≥0.12",
            lumaOf(lifted) - lumaOf(card) >= 0.12f)
        assertTrue("色相应保持（只补亮度：蓝通道仍是三通道最大）",
            (lifted shr 0 and 0xFF) > (lifted shr 16 and 0xFF))
        // 钉死 blend 参数序（fg=叠加层）：只允许「抬到目标档」，不许冲成近白板
        //（12:39 真机首验：参数反了 → 容器×13%+白×87% ≈ 白板，文字全灭）。
        val targetL = lumaOf(card) + 0.14f
        assertTrue(
            "结果应贴着目标亮度（|Δ|≤0.03），而不是一冲到顶",
            kotlin.math.abs(lumaOf(lifted) - targetL) <= 0.03f
        )
    }

    @Test
    fun `块底对比度闸_浅色卡上把浅容器压暗_对比够则原样放行`() {
        val lightCard = 0xFFE8EAE6.toInt()
        val lightBlock = 0xFFE2E4FF.toInt() // 浅色 tertiaryContainer 形状
        val pressed = WidgetArt.blockBg(lightBlock, lightCard)
        assertTrue("压暗后块应比卡深（Δluma ≥0.12）",
            lumaOf(lightCard) - lumaOf(pressed) >= 0.12f)
        // 本来就够对比的容器：原样放行
        val midBlock = 0xFF1E2878.toInt()
        assertEquals(midBlock, WidgetArt.blockBg(midBlock, lightCard))
    }
}
