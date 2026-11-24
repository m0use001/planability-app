package io.github.m0use001.planability.widget

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 小组件**配色口径唯一性**的回归闸（2026-11-01）。
 *
 * 用户：「是否出现都是首要文字但小组件间没真正统一颜色（例如胶囊时钟的时间文字
 * 与倒数日的剩余天数文字的颜色）」——查证属实且不止一处。深色（经典绿方案）实测：
 *
 * | 角色 | 旧实现 | 颜色 |
 * |---|---|---|
 * | 时钟时间 | hsl(primary, 0.95) | #A9F2AF |
 * | 倒数日剩余天数 | scheme.primary 原值 | #95D69B |
 * | 今日数据四项数值 | hsl(primary, 0.92) | #A3EBAA |
 *
 * 三者是同一个角色（"组件里那个主色大数字"），桌面上并排是三个不同的绿。
 * 正文同理：时钟的日期用 hsl(onSurface, 0.96)（#F5EFF4）而其它组件用 onSurface
 * 原值（#E6E1E5）。现在各收敛成一个 token：
 *  - [WidgetPalette.heroNumberColor]   主色强调数字
 *  - [WidgetPalette.textOnCardColor]   卡片首要文字
 *
 * 本测试用**源码断言**守这两条：谁再在组件里自己算 hsl(...) 或直接用 scheme.primary
 * 当数字色，这里就会红。选源码断言而不是渲染断言，是因为要抓的是"绕过 token 自己配"
 * 这个行为本身，而不是某一次渲染结果。
 */
class WidgetColorTokenTest {

    private fun source(rel: String): String {
        val roots = listOf("app/src/main/java/", "src/main/java/")
        roots.forEach { r ->
            val f = java.io.File(r + rel)
            if (f.isFile) return f.readText()
        }
        // 单测工作目录可能是模块目录（app/），也兼容仓库根
        val f = java.io.File("app/src/main/java/" + rel)
        assertTrue("源码不在了：$rel（cwd=${java.io.File(".").absolutePath}）", f.isFile)
        return f.readText()
    }

    @Test
    fun `两个 token 存在且按明暗分档`() {
        val wt = source("io/github/m0use001/planability/widget/WidgetTheme.kt")
        assertTrue("缺主色强调数字 token", wt.contains("val heroNumberColor"))
        assertTrue("缺卡片首要文字 token", wt.contains("val textOnCardColor"))
        // 深色抬亮、浅色用原角色值——档位写死在 token 里，组件不该再各算一套
        assertTrue("heroNumberColor 应在深色抬到 0.95", wt.contains("0.95f"))
        assertTrue("textOnCardColor 应在深色抬到 0.96", wt.contains("0.96f"))
    }

    @Test
    fun `时钟的时间与日期都走 token`() {
        val wt = source("io/github/m0use001/planability/widget/WidgetTheme.kt")
        assertTrue(
            "时钟时间色应等于 heroNumberColor（原来自算 hsl(primary,0.95)）",
            wt.contains("clockTimeColor: Color get() = heroNumberColor")
        )
        assertTrue(
            "时钟日期/完成率色应等于 textOnCardColor（原来自算 hsl(onSurface,0.96)）",
            wt.contains("clockTextColor: Color get() = textOnCardColor")
        )
    }

    @Test
    fun `倒数日天数用 hero token 而不是 primary 原值`() {
        val cd = source("io/github/m0use001/planability/widget/CountdownWidget.kt")
        assertTrue("倒数日数字应走 heroNumberColor", cd.contains("heroNumberColor"))
        assertTrue(
            "倒数日数字不该再用 accentColor（= scheme.primary 原值，深色下比时钟暗一档）",
            !cd.contains("p.accentColor.toArgb(),                // 数字")
        )
    }

    @Test
    fun `今日数据四项数值用 hero token`() {
        val ts = source("io/github/m0use001/planability/widget/TodayStatsWidget.kt")
        assertTrue(
            "数值色应取 colors.heroNumber",
            ts.contains("colors.heroNumber")
        )
        assertTrue(
            "不该再自算 hsl(primary, 0.92)（那是第三套绿）",
            !ts.contains("hsv[2] = 0.92f")
        )
    }

    @Test
    fun `位图组件的首要文字统一走 textOnCardColor`() {
        val wt = source("io/github/m0use001/planability/widget/WidgetTheme.kt")
        assertTrue(
            "artOnCard 应等于 textOnCardColor（今日/多天/屏幕使用/今日数据的正文都取它）",
            wt.contains("val artOnCard: Color get() = textOnCardColor")
        )
    }

    @Test
    fun `heroNumber 一路能传到位图组件`() {
        // Colors 构造参数 → artColors 填充 → 今日数据消费
        assertTrue(
            "WidgetArt.Colors 应带 heroNumber 字段",
            source("io/github/m0use001/planability/widget/WidgetArt.kt").contains("val heroNumber: Int = 0")
        )
        assertTrue(
            "artColors 应把 heroNumberColor 塞进 Colors",
            source("io/github/m0use001/planability/widget/TodayWidget.kt").contains("heroNumber = p.heroNumberColor.toArgb()")
        )
    }

    @Test
    fun `多天总览的布局缓存不因新字段崩`() {
        // RangeWidget 把 Colors 序列化进 SharedPreferences；加字段必须用带默认值的读法，
        // 否则老缓存（12 项）反序列化直接抛异常 → 组件白屏
        val rw = source("io/github/m0use001/planability/widget/RangeWidget.kt")
        assertTrue("heroNumber 写入缓存", rw.contains("colors.heroNumber"))
        assertTrue("旧缓存用 optInt 兜底（不能裸 getInt）", rw.contains("a.optInt(12, 0)"))
    }
}
