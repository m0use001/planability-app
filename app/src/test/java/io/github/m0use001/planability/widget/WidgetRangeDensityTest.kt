package io.github.m0use001.planability.widget

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「多天总览」**固定 4×4 + 密度版式**的回归闸（2026-11-01）。
 *
 * 用户：「锁死 4×4 体积，同时增加信息密度（现在这组件几乎提供不了任何有效信息）」。
 * 密度三项：月档每格 3 条彩条（原 2）、列档每列 6 行（原 4）、周档列头带当日场数；
 * 标题从「本周/近 3 天」换成真实日期区间（选中档位由右上胶囊高亮表达，标题位不该浪费）。
 *
 * 选源码断言（与 [WidgetColorTokenTest] 同理由）：要抓的是"锁被解开/行数改回去"
 * 这个行为本身——XML 或渲染常量一改这里就红。
 */
class WidgetRangeDensityTest {

    private fun read(rel: String): String {
        val roots = listOf("app/src/main/", "src/main/")
        roots.forEach { r ->
            val f = java.io.File(r + rel)
            if (f.isFile) return f.readText()
        }
        return java.io.File("app/src/main/" + rel).readText()
    }

    @Test
    fun `组件声明锁死 4x4 且不可拉伸`() {
        val xml = read("res/xml/widget_range.xml")
        assertTrue("targetCellWidth 应为 4", xml.contains("android:targetCellWidth=\"4\""))
        assertTrue("targetCellHeight 应为 4", xml.contains("android:targetCellHeight=\"4\""))
        assertTrue(
            "resizeMode 应为 none（锁死体积，不给拉伸手柄）",
            xml.contains("android:resizeMode=\"none\"")
        )
        assertTrue(
            "minHeight 应为 4 格口径 250dp（原 180dp 是 4×3 时代的）",
            xml.contains("android:minHeight=\"250dp\"")
        )
    }

    @Test
    fun `月档每格画 3 条彩条且加号阈值随动`() {
        val art = read("java/io/github/m0use001/planability/widget/WidgetArt.kt")
        assertTrue("月历格应 take(3) 条彩条（原 2 条太稀）", art.contains("cell.eventKinds.take(3)"))
        assertTrue("超出 3 条才显示「+n」", art.contains("cell.eventKinds.size > 3"))
        assertTrue("数据层 kinds 上限应提到 4（3 条 + 加号进位）",
            read("java/io/github/m0use001/planability/widget/RangeWidget.kt").contains("coerceAtMost(4)"))
    }

    @Test
    fun `列档为共享垂直时间轴（v2 终态钉版）`() {
        // 2026-11-05 更新：edd22416 已把列档从「行网格」重写成**共享垂直时间轴**
        // （事件条 y=开始分钟线性映射、lane 分离重叠、30min 刻度）——原断言钉的
        // busiest/take(rows)/listTop+lineH 代码已不存在，测试却没跟上（旧账）。
        // 这里按当前已验收终态重新钉版。
        val art = read("java/io/github/m0use001/planability/widget/WidgetArt.kt")
        assertTrue(
            "事件条 y=开始分钟线性映射（30min 吸附轴界）",
            art.contains("fun yOf(min: Int)") &&
                art.contains("(min - axisStart).coerceIn(0, span)")
        )
        assertTrue(
            "并行事件 lane 分离（前条 end > 后条 start 排进下一 lane）",
            art.contains("sortedBy { it.first }")
        )
        assertTrue("主体应直落到 0.95h", art.contains("val bodyBottom = h * 0.95f"))
        assertTrue(
            "汇总行画在卡底（0.958h），不得再画进标题行右端",
            art.contains(".draw(canvas, padX, h * 0.958f)") &&
                !art.contains("drawRight(canvas, zoneLeft - w * 0.015f")
        )
    }

    @Test
    fun `周档列头只留星期 场数已取消`() {
        // 2026-11-05 用户拍板「取消场数统计；周视图只用一个汉字表示星期几」：
        // 列头恢复纯 col.head（周=单字「六」/3 天=「周六」），「·N」后缀撤除。
        val art = read("java/io/github/m0use001/planability/widget/WidgetArt.kt")
        assertTrue(
            "列头不得再拼场数「·N」",
            !art.contains("·${'$'}{col.events.size}")
        )
        assertTrue(
            "列头直接用 col.head",
            art.contains("tp.sized(col.head, ink(0.044f), colors.sub)")
        )
    }

    @Test
    fun `场数统计已取消 footer 恒空`() {
        // 2026-11-05 用户拍板：月/周/3 天档底部「共 N 场」汇总行撤除。
        val rw = read("java/io/github/m0use001/planability/widget/RangeWidget.kt")
        assertTrue(
            "三个档位的 footer 都应是空串",
            rw.contains("footer = \"\"")
        )
        assertTrue(
            "不得再有任何「N 场」文案",
            !rw.contains("场日程 ·") && !rw.contains("共 ${'$'}total 场")
        )
    }

    @Test
    fun `标题位给真实日期区间而不是零信息量占位`() {
        val art = read("java/io/github/m0use001/planability/widget/WidgetArt.kt")
        assertTrue(
            "主标题应优先 monthTitle（空串才回退 range.title）",
            art.contains("data.monthTitle.ifBlank { data.range.title }")
        )
        val rw = read("java/io/github/m0use001/planability/widget/RangeWidget.kt")
        assertTrue(
            "3 天/周档应填日期区间（如 10/31–11/2）",
            rw.contains("\"–${'$'}{end.monthValue}/${'$'}{end.dayOfMonth}\"")
        )
    }

    @Test
    fun `LocalSize 0x0 不得进入渲染与布局持久化`() {
        // 2026-11-01 真机实锤：ColorOS 重加组件后首次组合拿到空 options → LocalSize 0×0，
        // 热区（按比例算）宽高=0 → 切档点击全死；且 0×0 曾把持久化布局冲掉。三层闸：
        val rw = read("java/io/github/m0use001/planability/widget/RangeWidget.kt")
        assertTrue(
            "组合期应走 effectiveSize 防御",
            rw.contains("effectiveSize(LocalSize.current, ctx)")
        )
        assertTrue(
            "obtain 应拒绝 0×0（回退 lastGoodSize）",
            rw.contains("if (size.width.value <= 0f || size.height.value <= 0f)")
        )
        assertTrue("persistLayout 不落盘 0×0", rw.contains("if (size.width.value <= 0f || size.height.value <= 0f) return"))
        assertTrue(
            "loadLayout 应把 0×0 残留当无效",
            rw.contains("""if (o.getDouble("w") <= 0.0 || o.getDouble("h") <= 0.0) return null""")
        )
        assertTrue(
            "渲染尺寸应优先 launcher 真实占位（LocalSize 报的是可伸展最大区域，" +
                "4×4 报 412dp>屏幕 360dp——位图 8.3MB 且热区错位，用户实测点胶囊映射错档）",
            rw.contains("OPTION_APPWIDGET_MIN_WIDTH")
        )
        assertTrue(
            "渲染位图应封顶 1080px（ColorOS 虚报 412dp→1443px/8.3MB 一张，三档预热顶爆 LRU）",
            rw.contains("cap = 1080f") || read("java/io/github/m0use001/planability/widget/WidgetArt.kt").contains("cap = 1080f")
        )
        assertTrue(
            "热区必须全比例 weight 网格（绝对 dp 会被 ColorOS 谎报尺寸打歪，" +
                "用户实测：点画着的「3天」命中「周」）",
            rw.contains("private fun RangeSegmentHotSpots()") &&
                !rw.contains("(size.width.value * 0.40f).dp")
        )
    }
}
