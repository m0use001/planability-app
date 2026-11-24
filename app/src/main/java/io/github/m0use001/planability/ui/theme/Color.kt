package io.github.m0use001.planability.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * 种子色 → M3 风格色板：以 HSL 手工推导整套色板，不引入 material-color-utilities。
 * 三层色彩纪律：
 *  1. 标识色（分类/图表序列）→ SeedTones 主色色相旋转；
 *  2. 语义色（优先级/评级）→ 保留标准色相，只随主题调饱和度明度；
 *  3. 美术内容色（笔记封面/便签）→ 固定色，有意不随主题。
 */
object SeedColors {

    fun hsl(h: Float, s: Float, l: Float, alpha: Float = 1f): Color {
        val hsv = floatArrayOf((h % 360f + 360f) % 360f, s.coerceIn(0f, 1f), l.coerceIn(0f, 1f))
        val argb = android.graphics.Color.HSVToColor((alpha * 255).toInt(), hsv)
        return Color(argb)
    }

    private fun toHsv(color: Color): FloatArray {
        val argb = (color.alpha * 255).toInt() shl 24 or
            ((color.red * 255).toInt() shl 16) or
            ((color.green * 255).toInt() shl 8) or
            (color.blue * 255).toInt()
        return FloatArray(3).also { android.graphics.Color.colorToHSV(argb, it) }
    }

    fun hueOf(color: Color): Float = toHsv(color)[0]
    fun satOf(color: Color): Float = toHsv(color)[1]
    fun lightnessOf(color: Color): Float = toHsv(color)[2]

    /** 标识色：色相旋转派生（分类、图表序列），随主题调明度 */
    fun tone(hue: Float, dark: Boolean): Color =
        hsl(hue, if (dark) 0.58f else 0.60f, if (dark) 0.68f else 0.52f)

    fun toneContainer(hue: Float, dark: Boolean): Color =
        hsl(hue, if (dark) 0.30f else 0.85f, if (dark) 0.24f else 0.91f)

    // ---------- 语义色：保留标准色相 ----------

    fun priorityHigh(dark: Boolean) = hsl(4f, if (dark) 0.72f else 0.78f, if (dark) 0.64f else 0.52f)
    fun priorityMid(dark: Boolean) = hsl(38f, if (dark) 0.80f else 0.90f, if (dark) 0.62f else 0.48f)
    fun priorityLow(dark: Boolean) = hsl(215f, if (dark) 0.66f else 0.72f, if (dark) 0.66f else 0.50f)

    fun success(dark: Boolean) = hsl(145f, 0.62f, if (dark) 0.60f else 0.42f)

    // ---------- 美术内容色：固定不随主题 ----------

    val artPalette = listOf(
        hsl(212f, 0.55f, 0.60f), hsl(152f, 0.48f, 0.55f), hsl(28f, 0.72f, 0.58f),
        hsl(348f, 0.62f, 0.62f), hsl(262f, 0.48f, 0.62f), hsl(188f, 0.52f, 0.55f)
    )

    val inkPalette = listOf(
        Color(0xFF1B1B1F), Color(0xFF3E5AA8), Color(0xFFB03A3A),
        Color(0xFF1F7A4D), Color(0xFFB07A1F), Color(0xFF6B4FA1)
    )

    /**
     * 图表专用色板，**不能直接用 [inkPalette]**。
     *
     * inkPalette 的第一格是 Color(0xFF1B1B1F) —— 近黑墨水，那是给笔记荧光笔/笔迹用的，
     * 在浅色纸上正是我们要的效果。图表拿它当色板，暗色模式下第一个系列就和背景
     * 糊成一片：真机上表现为散点图/雷达图第一个系列看不见、饼图像少了一块、
     * 柱状图第一根几乎透明。所以这里按主题各给一套。
     */
    fun chartPalette(dark: Boolean): List<Color> =
        if (dark) listOf(
            hsl(212f, 0.62f, 0.70f), hsl(348f, 0.68f, 0.68f), hsl(152f, 0.54f, 0.60f),
            hsl(28f, 0.78f, 0.64f), hsl(262f, 0.58f, 0.74f), hsl(188f, 0.60f, 0.60f)
        ) else listOf(
            Color(0xFF3E5AA8), Color(0xFFB03A3A), Color(0xFF1F7A4D),
            Color(0xFFB07A1F), Color(0xFF6B4FA1), Color(0xFF2E7D8F)
        )
}
