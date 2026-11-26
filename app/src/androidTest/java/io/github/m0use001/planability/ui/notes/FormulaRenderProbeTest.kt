package io.github.m0use001.planability.ui.notes

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 2026-11-14 用户「我专门叫 AI 测渲染公式」——小说助理◆「公式渲染测试」
 * 消息 6923（4510 字，按编号铺开所有公式可能性：行内/块级/上下标/分式根式/求和积分/
 * 矩阵行列式/分段对齐/…）。本探针把那条消息的**原文**（assets/formula-probe.txt，
 * 一字不差）灌进真实管线：splitMathSegments → normalizeLatex → LatexRender，
 * 逐条渲染、失败逐条打日志（LatexRender Log.w 带具体异常），最后汇总断言。
 *
 * 失败即代表用户会在那条消息里看到哪一类「原文裸显」——修 normalizeLatex/渲染端。
 * 真机禁跑（仪器化测试一律模拟器，见 AGENTS §4.2）。
 */
@RunWith(AndroidJUnit4::class)
class FormulaRenderProbeTest {

    @Test
    fun allMathSegmentsInProbeMessageRender() {
        val ctx = InstrumentationRegistry.getInstrumentation().context   // 测试包的 assets
        val src = ctx.assets.open("formula-probe.txt")
            .bufferedReader(Charsets.UTF_8).use { it.readText() }
        // 镜像真实管线（MarkdownView 先按 ``` 围栏分段，围栏内容不走公式切分）：
        // 消息末尾的 ```plan/qa/checklist/schedule 是 JSON 组件，LaTeX 在其中是 JSON
        // 双写转义（`\\pi`），永远不该喂给公式渲染器——纯文本段才是用户看到的公式面。
        val textOnly = Regex("(?s)```.*?```").replace(src, "")
        val segs = splitMathSegments(textOnly)
        val math = segs.filterIsInstance<MathSeg.Math>()
        assertTrue("兜底：probe 文本里应有大量公式段", math.size >= 30)
        val fails = mutableListOf<String>()
        math.forEachIndexed { i, s ->
            val bmp = LatexRender.bitmap(s.latex, 40f, 0xFF000000.toInt())
            if (bmp == null) {
                fails += "#$i display=${s.display} ${s.latex.take(120)}"
                Log.w("FormulaProbe", "FAIL #$i display=${s.display}: ${s.latex}")
            } else {
                Log.d("FormulaProbe", "OK   #$i display=${s.display} ${bmp.width}x${bmp.height}")
            }
        }
        assertTrue(
            "共 ${math.size} 段，失败 ${fails.size} 段：\n" + fails.joinToString("\n"),
            fails.isEmpty()
        )
    }
}
