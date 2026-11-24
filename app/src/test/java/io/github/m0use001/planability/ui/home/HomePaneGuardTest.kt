package io.github.m0use001.planability.ui.home

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * v72 首页副框图形化守卫（2026-11-21 用户「文字太多了，仅靠排版难以区分，
 * 应该加入圆角矩形来区分」+ 拍板「环+大数字卡」）。
 *
 * HomeTodayPane 曾整板裸文本：完成率只有一根 8dp 细条、待办空态一行灰字、
 * 专注/日历入口没有容器。修复形态 = ui/common.PaneBlocks 词汇表：
 * 完成率环卡（PaneStatRing）/ 节块（PaneSectionBlock）/ 空态占位块（PaneEmptyBlock）/
 * 入口行块（PaneActionBlock）。板内禁浮起 Card 的约定不变——块是扁平色调，不是卡。
 *
 * 断言读源码、与编译无关；CRLF 一律归一化后再匹配（仓库既有约定）。
 */
class HomePaneGuardTest {

    private fun source(rel: String): String {
        val f = File(rel)
        assertTrue("源码不在了（cwd 应为 app 模块目录）：$rel", f.isFile)
        return f.readText(Charsets.UTF_8).replace("\r\n", "\n")
    }

    private fun paneSegment(): String {
        val src = source("src/main/java/io/github/m0use001/planability/ui/home/HomeCards.kt")
        return src.substringAfter("fun HomeTodayPane(")
            .substringBefore("private const val PANE_TODO_LIMIT")
    }

    @Test
    fun `完成率必须是环加数字卡而不是一根细条`() {
        assertTrue(
            "完成率没有用 PaneStatRing（环+大数字卡）——用户拍板的形状",
            paneSegment().contains("PaneStatRing(")
        )
    }

    @Test
    fun `节与入口与空态都必须有圆角块信封`() {
        val seg = paneSegment()
        assertTrue("日程/待办节没有 PaneSectionBlock", seg.contains("PaneSectionBlock("))
        // v72.1：块内空态改「居中一行图标+文案」（嵌套占位块同色会隐形，用户实拍反馈）
        assertTrue("待办空态缺失或没给信封内占位（一行灰字=用户点名的杂乱）", seg.contains("这一天没有待办"))
        // v72.2：两个入口并进同一枚举（标题+一个矩形），行用 PaneActionRow
        assertTrue("专注/日历入口没有 PaneActionRow（一个矩形里多行）", seg.contains("PaneActionRow("))
    }

    @Test
    fun `板内不许回到浮起 Card`() {
        // 注意排除 TodayStatsCard( 这类自有组件名的子串：只钉独立的 Card( 调用
        assertFalse(
            "HomeTodayPane 又用了 Card(——板内嵌浮起卡=第二种材质（SplitBoard/PlanWidePaneGuard 同一条纪律）",
            Regex("(?<![A-Za-z])Card\\(").containsMatchIn(paneSegment())
        )
    }
}
