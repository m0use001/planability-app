package io.github.m0use001.planability.ui.notes

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4

/**
 * 2026-11-12 用户截图（积分表第三行 `\ln\lvert x\rvert+C` 整格裸显）设备端探针。
 *
 * 证据链（三次模拟器实跑）：jlatexmath-android 0.2.0 对 `\lvert` 抛
 * `ParseException: Unknown symbol or command or predefined TeXFormula: 'lvert'`
 * （TeXSymbols.xml / TeXFormulaSettings.xml 均未登记）→ LatexRender 返回 null →
 * 渲染端 fallbackText 把原文当等宽文本裸显——正是用户截图那格的病。
 * （首跑曾出现过一次「\lvert 成功」的反常结果，两跑后无法复现且 logcat 实锤异常，
 * 判定无效；本文件以可复现的失败为准。）
 *
 * 修复=normalizeLatex 进解析器前改写为语义等价的 `\vert/\Vert`（1d3e93f4）。
 * 本探针钉死两件事：改写目标能解析；原始形式解析失败（若未来库升级让它变红，
 * 说明库已支持，可摘掉 normalizeLatex 里的改写）。
 *
 * 真机禁跑（仪器化测试一律模拟器，见 AGENTS §4.2）。
 */
@RunWith(AndroidJUnit4::class)
class LatexVertProbeDeviceTest {

    private val color = 0xFF000000.toInt()

    @Test
    fun rewrittenVertFormsParse() {
        assertNotNull(LatexRender.bitmap("\\ln\\vert x\\vert+C", 40f, color))
        assertNotNull(LatexRender.bitmap("\\Vert x\\Vert", 40f, color))
        assertNotNull(LatexRender.bitmap("a\\mid b", 40f, color))
    }

    @Test
    fun rawVertFormsQuirkyButCovered() {
        // 库的登记不对称（三次实跑实证）：\lvert 稳定抛 ParseException（=用户截图那格
        // 裸显的根因，normalizeLatex 的改写就是为它）；\lVert 反而能走库内未登记路径渲出。
        // 哪条变了都说明库在变——红了就对一遍 normalizeLatex 的改写是否仍兜住。
        assertNull(
            "\\lvert 竟然能解析了（jlatexmath 升级了？可摘掉 normalizeLatex 的改写）",
            LatexRender.bitmap("\\ln\\lvert x\\rvert+C", 40f, color)
        )
        assertNotNull(LatexRender.bitmap("\\lVert x\\rVert", 40f, color))
    }

    @Test
    fun fullPipelineCellRenders() {
        // 用户截图那格的全链路：splitMathSegments（JVM 守卫已钉改写）→ 渲染
        val latex = splitMathSegments("$\\ln\\lvert x\\rvert+C$")
            .filterIsInstance<MathSeg.Math>().single().latex
        assertTrue(
            "全链路产出的 latex 应能渲染（管线又坏了）",
            LatexRender.bitmap(latex, 40f, color) != null
        )
    }

    /** 2026-11-14 用户截图（微积分会话 6732）：「2.2 导数」那条的
     *  `$$f'(x)=\lim_{h\to0}\frac{f(x+h)-f(x)}{h}$$` 整段回退裸显。全链路复现，
     *  失败时 LatexRender 的 Log.w 会打出具体命令。 */
    @Test
    fun derivativeLimitFormulaParses() {
        val latex = splitMathSegments("\$\$f'(x)=\\lim_{h\\to0}\\frac{f(x+h)-f(x)}{h}\$\$")
            .filterIsInstance<MathSeg.Math>().single().latex
        assertTrue(
            "导数定义公式解析失败（latex=$latex，查 logcat LatexRender）",
            LatexRender.bitmap(latex, 40f, color) != null
        )
    }
}
