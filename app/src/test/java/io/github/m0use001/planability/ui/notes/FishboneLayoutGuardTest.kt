package io.github.m0use001.planability.ui.notes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 鱼骨图长标签压字守卫（2026-11-17 用户报障）。
 *
 * 报障形态：鱼骨图里长中文标签（"训练总量是否到位（热量不低、蛋白质密度极低）"这类）
 * 互相压成一团、绿色骨线直接穿过文字。
 *
 * 判据放在**纯布局函数** [layoutFishbone] 上：布局算完给出每个标签的矩形，
 * 断言「任意两个标签矩形不相交」+「都在画布内」+「长标签确实换行」。
 * 这样"改坏实现"会立刻红，而不是靠人眼看图。
 */
class FishboneLayoutGuardTest {

    // kind: 0=主因 1=子因 2=鱼头；中文按整字宽近似（像素）
    private val charW = mapOf(0 to 11f, 1 to 9f, 2 to 12f)

    private fun measure(text: String, kind: Int): Float = text.length * (charW[kind] ?: 10f)

    private fun layout(
        causes: List<BoneCause>,
        title: String = "体测 64.2，爆发力两项合计只拿 2 分",
        avail: Float = 1080f
    ) = layoutFishbone(
        causes = causes,
        title = title,
        availWidth = avail,
        measure = ::measure,
        causeLineH = 15f,
        subLineH = 12f,
        headLineH = 16f,
        pad = 6f,
        gap = 5f,
        minBandW = 104f,
        maxBandW = 210f,
        slant = 16f,
        tickLen = 18f,
        minBoneLen = 62f
    )

    /** 用户截图同款：长主因 + 长子因 + 超长鱼头（句子长度按截图里的真实长句量级） */
    private val userCase = listOf(
        BoneCause(
            "最差不是基础动作质量而是食物替代真实进食的问题被长期忽略掉了",
            listOf(
                "学校根本没有提供沙拉也没有可用的高蛋白加餐时段",
                "训练餐里的蛋白质摄入量远远低于肌肉合成的阈值"
            )
        ),
        BoneCause(
            "训练总量是否到位（热量不低、蛋白质密度极低、且三餐分布不均）",
            listOf("极难吃补剂，训练量与恢复时间跟训练计划完全不匹配")
        ),
        BoneCause(
            "训练强度问题被数据掩盖了其实强度一直不够",
            listOf("这两句只是拿证据陈的观念锁死了其他所有可能的原因")
        ),
        BoneCause(
            "体测 64.2，爆发力两项合计只拿 2 分说明身体已经很久没有系统训练",
            listOf("训练强度问题")
        )
    )

    @Test
    fun `长中文标签互不重叠`() {
        val lay = layout(userCase)
        val ls = lay.labels
        for (i in ls.indices) {
            for (j in i + 1 until ls.size) {
                assertTrue(
                    "标签压字：[$i]「${ls[i].lines.joinToString("/")}」与 [$j]「${ls[j].lines.joinToString("/")}」相交",
                    !ls[i].hits(ls[j])
                )
            }
        }
        // 断言不是"因为没画标签才没重叠"
        assertTrue("标签数量异常少：${ls.size}", ls.size >= userCase.size + userCase.sumOf { it.subs.size })
    }

    @Test
    fun `所有标签都在画布内`() {
        val lay = layout(userCase)
        lay.labels.forEach {
            assertTrue("标签越界左：${it.lines} x=${it.x}", it.x >= -0.5f)
            assertTrue("标签越界上：${it.lines} y=${it.y}", it.y >= -0.5f)
            assertTrue("标签越界右：${it.lines} right=${it.right} width=${lay.width}", it.right <= lay.width + 0.5f)
            assertTrue("标签越界下：${it.lines} bottom=${it.bottom} height=${lay.height}", it.bottom <= lay.height + 0.5f)
        }
        assertTrue("鱼头越界右：right=${lay.head.right} width=${lay.width}", lay.head.right <= lay.width + 0.5f)
    }

    @Test
    fun `长主因必须换行而不是撑破band`() {
        // 40 字主因：单行 ≈ 440px > 允许宽（2×(band-2×斜角)），必须换行
        val lay = layout(listOf(BoneCause("训练总量是否到位（热量不低、蛋白质密度极低，而且蛋白质的摄入时间点也没有对齐）", emptyList())))
        val cause = lay.labels.first { it.kind == 0 }
        assertTrue("长主因没有换行：${cause.lines}", cause.lines.size >= 2)
        cause.lines.forEach {
            assertTrue("单行超宽：$it", measure(it, 0) <= cause.w + 0.5f)
        }
    }

    @Test
    fun `超长子因截断加省略号且不越band`() {
        // 60 字子因：两行也装不下，必须截断加省略号
        val longSub = "这两句只是拿证据凑的观念锁死了其他所有可能的原因所以完全不应该这么看而且还要考虑作息与恢复"
        val lay = layout(listOf(BoneCause("短主因", listOf(longSub))))
        val sub = lay.labels.first { it.kind == 1 }
        assertTrue("子因没有截断：${sub.lines}", sub.lines.last().endsWith("…"))
        assertTrue("子因行数超限：${sub.lines}", sub.lines.size <= 2)
    }

    @Test
    fun `子因越多越高_骨长按内容算`() {
        val one = layout(listOf(BoneCause("主因", listOf("子一"))))
        val many = layout(listOf(BoneCause("主因", listOf("子一", "子二", "子三", "子四"))))
        assertTrue("子因变多高度没长：${one.height} -> ${many.height}", many.height > one.height)
    }

    @Test
    fun `超长鱼头换行且宽度封顶`() {
        val lay = layout(userCase, title = "体测 64.2，爆发力两项合计只拿 2 分，顺便再补一句更长的结论尾巴")
        assertTrue("鱼头没换行：${lay.head.lines}", lay.head.lines.size >= 2)
        assertTrue("鱼头太宽：${lay.head.w}", lay.head.w <= 1080f * 0.34f + 12f)
    }

    @Test
    fun `band放不下时给出自然宽供外层横滑`() {
        val many = (1..8).map { BoneCause("主因$it", listOf("子一", "子二")) }.toList()
        val lay = layout(many)
        assertTrue("应给自然宽（>可用宽）触发横滑：${lay.width}", lay.width > 1080f)
    }
}
