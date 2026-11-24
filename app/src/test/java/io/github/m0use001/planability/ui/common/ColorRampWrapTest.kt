package io.github.m0use001.planability.ui.common

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 色阶色相的 wrap（源码级断言）。
 *
 * ## 为什么值得钉
 * 2026-10-17 真机 **两次 FATAL EXCEPTION** 都是这一行：
 * `java.lang.IllegalArgumentException: HSV (387.6087, 0.9, 0.70000005) must be in range (0..360, 0..1, 0..1)`
 * 崩在 `levelRamp` → `importanceColor` → 计划页 TodoRow，**整个计划页打不开**。
 *
 * 起因：色阶改成"由主题色相派生"（急 = 主题色 ±25°，重 = 补色区 +155~205°）。
 * 主题色相接近 360 时（例如 346°），+205 就是 551°，直接超出 `Color.hsv` 的合法区间。
 *
 * ## 为什么必须是 wrap 而不是 clamp
 * 色相是**环**：387.6° 和 27.6° 是同一个颜色。
 * clamp 会把 551° 压成 360°（红），而它本该是 191°（青）——**不崩，但颜色静默变错**，
 * 比崩溃更难发现。所以这里钉"取模两次"（兼容负数）。
 *
 * ## 为什么用源码断言而不是直接调
 * `levelRamp` 是 private，单测拿不到；而它调用的 `Color.hsv` 是 Android 框架类，
 * JVM 单测里是 stub 实现，**不会真抛**（所以运行时断言在这里是假的、测不出来）。
 * 这条约定又必须拦，只能读源码。
 */
class ColorRampWrapTest {

    private fun src(): String {
        var dir = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            val f = File(dir, "app/src/main/java/io/github/m0use001/planability/ui/common/Ui.kt")
            if (f.isFile) return f.readText(Charsets.UTF_8)
            dir = dir.parentFile
        }
        throw AssertionError("找不到 Ui.kt（工作目录不对？）")
    }

    @Test
    fun `levelRamp 的色相必须 wrap 回 360 度环上`() {
        val body = src().substringAfter("private fun levelRamp", "")
            .substringBefore("\n}", "")
        assertTrue("levelRamp 不见了（改名/被删了？）", body.isNotEmpty())

        assertTrue(
            "Color.hsv 的色相没有 wrap——主题色相加 ±25/+155~205 后可能超 360，" +
                "真机 10-17 两次 IllegalArgumentException 崩了整个计划页",
            Regex("""%\s*360f""").containsMatchIn(body)
        )
        // 两次取模是给负数留的（主题色相 -25 可能变负）；只写一次 `% 360` 对负值仍是负的
        assertTrue(
            "色相 wrap 只做了一次取模——主题色相减 25 可能得负值，" +
                "负色相同样会让 Color.hsv 抛",
            Regex("""%\s*360f\s*\+\s*360f\)\s*%\s*360f""").containsMatchIn(body)
        )
        // 不许用 clamp 代替 wrap：clamp 会让 551° 静默变成 360°(红) 而不是 191°(青)
        assertTrue(
            "色相被 clamp 了——色相是环，越界该 wrap 不该 clamp，" +
                "clamp 会把颜色静默改错（不崩，更难发现）",
            !Regex("""(coerceIn|coerceAtMost)\s*\(?\s*0f?\s*,\s*360""").containsMatchIn(body)
        )
    }
}
