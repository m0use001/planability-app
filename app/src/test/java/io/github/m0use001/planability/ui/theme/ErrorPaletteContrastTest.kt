package io.github.m0use001.planability.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 配色对比度守卫（2026-11-08 审计，tmp/contrast_audit.py 同口径）。
 *
 * 背景：`SeedColors.hsl` 实际是 HSV（第三参 V 不是 L），曾让三处按"高 L 就淡/亮"填的
 * 参数翻车——暗色 error 只有 2.0–2.4:1、亮蓝·浅主按钮文字 2.89:1。本测试钉两件事：
 *
 * 1. [readableOnColor] 的**行为**：黑白前景按 WCAG 对比度取高的，附上真实回归数字
 *    （#0055D5 配白 6.44:1 / 配黑 2.89:1，选白；#7D7D7D 配黑 4.56 / 配白 4.09，选黑）。
 *    它是纯 Kotlin 数学（不碰 android.graphics），JVM 可跑。
 * 2. 暗色 error 四件套 = M3 官方基线 tone80/20/30/90。整板生成走 android.graphics
 *    （同 [PresetSchemeTest] 的理由），这层用源文件锚点，防再被改回 hsl(4,.72,.70)。
 */
class ErrorPaletteContrastTest {

    // ---------- 1. readableOnColor 行为 ----------

    @Test
    fun `饱和中蓝配白不配黑——亮蓝·浅主按钮的回归用例`() {
        // #0055D5：HSV V=0.84（旧判据会选黑），相对亮度 0.113，白 6.44:1 黑 2.89:1
        assertEquals(Color.White, readableOnColor(Color(0xFF0055D5)))
    }

    @Test
    fun `中灰配黑不配白——黑白方案 secondary 的回归用例`() {
        // #7D7D7D：V=0.49（旧判据会选白，4.09:1 不达标），黑 4.56:1 / 白 4.09:1
        assertEquals(DarkInk, readableOnColor(Color(0xFF7D7D7D)))
    }

    @Test
    fun `M3 暗色 error 鲑红配近黑文字`() {
        // #FFB4AB：白 1.55:1 黑 10.4:1
        assertEquals(DarkInk, readableOnColor(Color(0xFFFFB4AB)))
    }

    @Test
    fun `对比度函数与手算值一致`() {
        // #0055D5 vs 白 = (1.05)/(0.1129+0.05) = 6.44
        assertTrue(wcagContrast(Color(0xFF0055D5), Color.White) > 6.2f)
        assertTrue(wcagContrast(Color(0xFF0055D5), DarkInk) < 3.0f)
        // 同色对比度恒为 1
        assertEquals(1f, wcagContrast(Color.White, Color.White), 0.0001f)
    }

    // ---------- 2. 暗色 error 四件套锚点 ----------

    private fun themeSource(): String {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null && !File(dir, "app/src/main/java/io/github/m0use001/planability").isDirectory) {
            dir = dir.parentFile
        }
        return File(
            requireNotNull(dir) { "找不到仓库根" },
            "app/src/main/java/io/github/m0use001/planability/ui/theme/Theme.kt"
        ).readText()
    }

    @Test
    fun `暗色 error 四件套在两条深色分支里都是 M3 基线`() {
        val src = themeSource()
        // seed 深色分支 + preset 深色分支，各出现一次 = 至少 2 次
        assertEquals(2, Regex("0xFFFFB4AB").findAll(src).count())   // tone80
        assertEquals(2, Regex("0xFF601410").findAll(src).count())   // tone20
        assertEquals(2, Regex("0xFF93000A").findAll(src).count())   // tone30
        assertEquals(2, Regex("0xFFFFDAD6").findAll(src).count())   // tone90
    }

    @Test
    fun `旧错误写法不得回流`() {
        val src = themeSource()
        // 旧 onColor：拿 HSV 的 V 当亮度判黑白
        assertTrue(!src.contains("lightnessOf(c) > 0.55f"))
        // 旧暗色 error：hsl(4,.72,.70) = 饱和中红 #B23B32，暗面上只有 ~2.3:1
        assertTrue(!src.contains("C.hsl(4f, 0.72f, 0.70f)"))
    }
}
