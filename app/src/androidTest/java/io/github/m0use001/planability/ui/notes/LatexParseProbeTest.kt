package io.github.m0use001.planability.ui.notes

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test

/**
 * jlatexmath-android 0.2.0 解析支持**真机矩阵**（2026-11-13 七截图报障排查）。
 *
 * JVM 单测探不了：TeXFormula 静态初始化要 JLatexMathAndroid.init(Context)。
 * 只对模拟器跑（日用机禁 connected 测试，见 AGENTS.md §4）。
 *
 * 两段：
 * ① 原始命令支持矩阵——钉住「\tag 不支持、\iffI 粘连不支持」这些结论的原点；
 * ② repairTex 端到端：解析失败 → 修复 → 再解析必须成功（LatexRender.bitmap 的重试链）。
 */
class LatexParseProbeTest {

    private fun parses(latex: String): Boolean =
        runCatching { org.scilab.forge.jlatexmath.TeXFormula(latex) }.isSuccess

    @Test
    fun probeParseMatrix() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        ru.noties.jlatexmath.JLatexMathAndroid.init(ctx)
        val sb = StringBuilder("[latex-parse-probe]\n")

        // ① 命令支持矩阵（2026-11-13 实探结论：ne/neq/iff/det/implies 全 OK；tag/粘连 FAIL）
        val matrix = mapOf(
            "ne-bare" to "\\ne",
            "ne-in-expr" to "a\\ne0",
            "iff-expr" to "A\\iff B",
            "iff-glued" to "A\\iffI",          // 模型常见粘连：无空格 → 必须 FAIL
            "det-matrix" to "\\det\\begin{bmatrix}e&f\\\\h&i\\end{bmatrix}",
            "tag" to "ax_1+bx_2=y_1\\tag{1}",   // 未注册 → 必须 FAIL
            "checkmark" to "(2,1,0)\\cdot(5,-2,1)^{\\mathsf T}=8\\ \\checkmark",
            "escaped-space" to "A\\ \\text{可逆}",
        )
        for ((k, v) in matrix) {
            sb.append(if (parses(v)) "OK    " else "FAIL  ").append(k)
                .append("  |  ").append(v).append('\n')
        }

        // ② repairTex 端到端：失败原样 → 修复后必须能解析
        val broken = listOf(
            "A\\iffI",
            "ax_1+bx_2=y_1\\tag{1}",
            "\\det A\\ne0\\iff A\\iffI",
            "x=1\\notag",
            "a\\neqX b",
        )
        sb.append("--- repairTex ---\n")
        for (v in broken) {
            val before = parses(v)
            val fixed = repairTex(v)
            val after = fixed != null && parses(fixed)
            // 走真实渲染管道（含 LatexRender.bitmap 的失败重试 + draw），不止解析
            val bmp = LatexRender.bitmap(v, 56f, 0xFF000000.toInt())
            sb.append(if (after || before) "OK    " else "FAIL  ")
                .append(v).append("  =>  ").append(fixed ?: "(unchanged)")
                .append("  before=").append(before).append(" after=").append(after)
                .append(" bmp=").append(bmp?.let { "${it.width}x${it.height}" } ?: "null")
                .append('\n')
        }
        val out = java.io.File(ctx.getExternalFilesDir(null), "latex-probe.txt")
        out.writeText(sb.toString())
        println(sb.toString())

        // ③ 真实源码端到端（2026-11-13 七截图）：用户某次会话的原始行 → 真实管线
        //    （splitMathSegments 的 display 分支：stripCjkLabels + normalizeLatex →
        //    LatexRender.bitmap）→ 位图落盘 PNG，拉回人工目检。
        //    写公共 Download/（connected 测试结束会卸应用连 getExternalFilesDir 一起删）
        @Suppress("DEPRECATION")
        val dir = java.io.File(
            android.os.Environment.getExternalStoragePublicDirectory(
                android.os.Environment.DIRECTORY_DOWNLOADS
            ),
            "latex-render"
        )
        dir.mkdirs()
        dir.listFiles()?.forEach { it.delete() }
        val real = mapOf(
            // 6901 行 27/29：cases + 全角括号 caption（图2/图3）
            "img2_cases_col1" to "\$\$\\begin{cases}ap+br=1\\\\cp+dr=0\\end{cases}\\qquad\\text{（决定第一列 }p,r\\text{）}\$\$",
            "img3_cases_col2" to "\$\$\\begin{cases}aq+bs=0\\\\cq+ds=1\\end{cases}\\qquad\\text{（决定第二列 }q,s\\text{）}\$\$",
            // 6907 行 77：行内长推导链（图4）
            "img4_bullet_b1" to "- \$B_1\$ 的系数：\$-gae+gbd+adh-dbg=adh-gae=a(dh-eg)\$",
            // 图1/图6 形态：\tag 与命令粘连
            "img6_tag" to "\$\$ax_1+bx_2=y_1\\tag{1}\$\$",
            "img1_iffI" to "\$\$\\det A\\ne0\\iff A\\iff I\$\$",
        )
        val sb2 = StringBuilder("[latex-real-render]\n")
        for ((name, src) in real) {
            val segs = splitMathSegments(src)
            val maths = segs.filterIsInstance<MathSeg.Math>()
            val lines = mutableListOf<String>()
            var n = 0
            for (m in maths) {
                n++
                val bmp = LatexRender.bitmap(m.latex, 84f, 0xFFE6E6E6.toInt())
                lines.add("seg$n latex=[${m.latex}] labels=[${m.labels}] bmp=${bmp?.width}x${bmp?.height}")
                bmp?.let {
                    java.io.File(dir, "${name}_$n.png").outputStream().use { os ->
                        it.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, os)
                    }
                }
            }
            val txt = segs.filterIsInstance<MathSeg.Txt>().joinToString("|") { it.text }
            sb2.append(name).append(": ").append(lines.joinToString(" ;; "))
                .append("  TXT=[").append(txt).append("]\n")
        }
        java.io.File(dir, "report.txt").writeText(sb2.toString())
        println(sb2.toString())
    }
}
