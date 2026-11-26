package io.github.m0use001.planability.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import io.github.m0use001.planability.data.ThemeConfig
import kotlin.math.pow

/**
 * 内置四套方案：颜色**逐像素照抄**系统原生取色器的四个圆（2026-10-24 用户提供浅/深
 * 两张截图，数值为截图采样，见下方注释里的 RGB）。
 *
 * 为什么不再从壁纸取色：壁纸 hint 的色相/饱和度因 ROM 差异太大，取出来的方案
 * 时灰时接近同一色相（用户实测多轮反馈「还是同一个颜色」「整页灰」）。现在
 * 「拼色」= 直接挑一套内置配色，稳定可预期；「其他颜色」tab 的自选种子仍按
 * [colorSchemeFromSeed] 派生。
 *
 * 每套 = [primary, secondary, tertiary]，浅/深各一组（第二三色在深色下是
 * 深底瓣、浅色下是浅底瓣，与原生截图一致）。
 */
private data class FixedStyle(val light: List<Int>, val dark: List<Int>)

private val FIXED_STYLES: List<FixedStyle> = listOf(
    // 0 经典绿（原生选中圆）：顶绿 / 左下粉 / 右下薰衣草
    FixedStyle(
        light = listOf(0xFF2D6A3B.toInt(), 0xFFFEC5D4.toInt(), 0xFFA7A6DD.toInt()),
        dark = listOf(0xFF95D69B.toInt(), 0xFF704653.toInt(), 0xFFA7A6DD.toInt())
    ),
    // 1 黑白：顶黑(深:白) / 左下浅灰(深:深灰) / 右下中灰
    FixedStyle(
        light = listOf(0xFF000000.toInt(), 0xFFD5D4D4.toInt(), 0xFFABABAB.toInt()),
        dark = listOf(0xFFFCFCFC.toInt(), 0xFF525252.toInt(), 0xFFABABAB.toInt())
    ),
    // 2 中性灰：顶冷灰 / 左下浅灰紫(深:深灰蓝) / 右下灰紫
    FixedStyle(
        light = listOf(0xFF5A5E6C.toInt(), 0xFFD3D4DE.toInt(), 0xFFA5AAC1.toInt()),
        dark = listOf(0xFFC3C6D6.toInt(), 0xFF50525B.toInt(), 0xFFA5AAC1.toInt())
    ),
    // 3 亮蓝：顶亮蓝(深:淡蓝) / 左下浅蓝(深:深蓝紫) / 右下薰衣草
    FixedStyle(
        light = listOf(0xFF0055D5.toInt(), 0xFFD2D1F9.toInt(), 0xFFB4A2D9.toInt()),
        dark = listOf(0xFFB2C5FF.toInt(), 0xFF4F4F71.toInt(), 0xFFB4A2D9.toInt())
    )
)

/** 内置方案的数量（外观页画圆、测试断言都用它，别写死 4）。 */
const val FIXED_STYLE_COUNT = 4

/** 方案名（顺序即 variant 序号）。 */
val FIXED_STYLE_LABELS = listOf("经典绿", "黑白", "中性", "亮蓝")

/**
 * 一套「其他颜色」预设的**浅色角色值**（2026-10-26 用户给的 8 张角色表，逐值照抄）。
 *
 * 图里只标了 7 个角色：primary / primaryContainer / secondaryContainer /
 * tertiaryContainer / surfaceContainer / surfaceContainerHigh / inverseSurface——
 * 这 7 个直接取图值；secondary/tertiary 前景与各 on* 仍按 M3 tone 档派生
 * （见 [colorSchemeFromPreset]）。深色值用户稍后单独提供，暂沿用 [colorSchemeFromSeed]。
 */
private data class PresetLight(
    val primary: Int,
    val primaryContainer: Int,
    val secondaryContainer: Int,
    val tertiaryContainer: Int,
    val surfaceContainer: Int,
    val surfaceContainerHigh: Int,
    val inverseSurface: Int
)

/**
 * 8 套预设（顺序即 palette 序号 4..11）：紫(基线) / 蓝 / 绿 / 胭脂 / 棕 / 青 / 红 / 紫。
 *
 * 为什么不再用「种子 + HSL 推导整板」：图里给的是 Material 官方按音阶取出来的
 * 容器与表面值（例如青绿那套 primaryContainer 是 #77F5FD），HSL 近似永远对不上；
 * 现在浅色直接用这些值，只有深色还走推导（等深色 8 套到了再换成同构的表）。
 */
private val PRESET_LIGHTS: List<PresetLight> = listOf(
    // 1 紫（M3 基线）
    PresetLight(
        0xFF6750A4.toInt(), 0xFFE9DDFF.toInt(), 0xFFE9DDFD.toInt(),
        0xFFFDDAE1.toInt(), 0xFFEFEDF2.toInt(), 0xFFE9E7ED.toInt(), 0xFF313034.toInt()
    ),
    // 2 蓝
    PresetLight(
        0xFF3757BA.toInt(), 0xFFE0E0FC.toInt(), 0xFFE0E0FE.toInt(),
        0xFFFFD8E7.toInt(), 0xFFEDEDF3.toInt(), 0xFFE8E8ED.toInt(), 0xFF303034.toInt()
    ),
    // 3 绿
    PresetLight(
        0xFF256B42.toInt(), 0xFFABF2C1.toInt(), 0xFFD1E8D7.toInt(),
        0xFFB6ECF4.toInt(), 0xFFEAEFEB.toInt(), 0xFFE4EAE5.toInt(), 0xFF2D312E.toInt()
    ),
    // 4 胭脂（酒红）
    PresetLight(
        0xFF984061.toInt(), 0xFFFED9E4.toInt(), 0xFFFCDAE3.toInt(),
        0xFFFFDBC6.toInt(), 0xFFF4ECEE.toInt(), 0xFFEEE6E8.toInt(), 0xFF352F30.toInt()
    ),
    // 5 棕
    PresetLight(
        0xFF8B5000.toInt(), 0xFFFDDDC1.toInt(), 0xFFFBDDC4.toInt(),
        0xFFD2EABA.toInt(), 0xFFF2EDE8.toInt(), 0xFFEDE7E3.toInt(), 0xFF34302C.toInt()
    ),
    // 6 青
    PresetLight(
        0xFF0C696E.toInt(), 0xFF77F5FD.toInt(), 0xFFC6E9EB.toInt(),
        0xFFD1E4FC.toInt(), 0xFFE7EFF0.toInt(), 0xFFE1EAEA.toInt(), 0xFF2B3232.toInt()
    ),
    // 7 红
    PresetLight(
        0xFFAC3327.toInt(), 0xFFFCDBD4.toInt(), 0xFFFEDBD3.toInt(),
        0xFFF1E2A9.toInt(), 0xFFF4ECEA.toInt(), 0xFFEEE6E5.toInt(), 0xFF352F2E.toInt()
    ),
    // 8 紫（灰紫）
    PresetLight(
        0xFF635590.toInt(), 0xFFE7DEFD.toInt(), 0xFFE6DFF6.toInt(),
        0xFFFED9E2.toInt(), 0xFFEFEDF3.toInt(), 0xFFE9E7ED.toInt(), 0xFF313034.toInt()
    )
)

/**
 * 「其他颜色」tab 的 8 颗预设种子（顺序即 palette 序号 4..11，与 [PRESET_LIGHTS] 同序）。
 *
 * 种子值 = 各套的 primary（图里的主色）。外观页只给这 8 颗、启动器图标按同一份顺序
 * 生成配色矩阵（LauncherNightIcon.paletteIndexOf），顺序一变图标就会错档，别随手调整。
 */
val COLOR_SEEDS: List<Color> = PRESET_LIGHTS.map { Color(it.primary) }

/** 某套内置方案在某明暗下的三色（供外观页预览圆直接使用，不经过整包色板）。 */
fun fixedStyleTriad(style: Int, dark: Boolean): List<Color> {
    val s = FIXED_STYLES[style.coerceIn(0, FIXED_STYLES.lastIndex)]
    return (if (dark) s.dark else s.light).map { Color(it) }
}

/**
 * 时间轴「现在线」专用色（2026-11-25 用户三连定稿）：**色相 = primary 旋转 +150°**、
 * 饱和/明度钉鲜亮档。为什么不是 secondary：某些「其他颜色」预设里 secondary 的色相
 * 与 primary 几乎同域（实测蓝系预设 secondary 是绿灰，跟无标签绿块二次撞色，用户
 * 截图「看不清」）；+150° 保证与 primary **恒定色相差**——随主题派生又永不撞无标签块。
 * 明度鲜亮档 + 调用方的 surface 底衬勾边，浅背景与彩色块上都跳得出来。
 */
@Composable
fun nowLineTone(): Color {
    val scheme = MaterialTheme.colorScheme
    val dark = SeedColors.lightnessOf(scheme.onSurface) > 0.5f
    val hue = (SeedColors.hueOf(scheme.primary) + 150f) % 360f
    return SeedColors.hsl(
        hue,
        0.55f,
        if (dark) 0.84f else 0.5f
    )
}

/**
 * 第二色的「可读文字调」：内置方案里 secondary 常是浅容器色（浅粉/浅薰衣草），
 * 直接当标题文字在浅色模式下看不清。这里只保留色相，按当前明暗把明度钉到可读档
 * （深色用亮调、浅色用暗调），用来给**小节标题**这类非操作文案上第二色。
 */
@Composable
fun secondaryTextTone(): Color {
    val scheme = MaterialTheme.colorScheme
    val dark = SeedColors.lightnessOf(scheme.onSurface) > 0.5f
    val base = scheme.secondary
    return SeedColors.hsl(
        SeedColors.hueOf(base),
        (SeedColors.satOf(base) * 0.8f).coerceIn(0.08f, 0.6f),
        if (dark) 0.78f else 0.35f
    )
}

/**
 * 把颜色搬到目标明度档，同时按「色度守恒」调饱和——拼色不再退化成单色的关键。
 *
 * 旧写法把取色圆里采到的**单个十六进制**直接当 secondary/tertiary 用：
 * 浅色那支是 V≈1.0 的淡粉（当文字看不见）、深色那支是 V≈0.44 的深梅（当容器糊底），
 * 两边都只剩一半可用，于是全 App 只有 primary 能上（实测 secondary 使用 0 处）。
 *
 * 官方做法是每个 key color 都是一条 13 阶调色板：前景取 40/80 档（对应 V≈0.49/0.94），
 * 容器取 90/30 档。换档时饱和不能照搬——向下压明度要提饱和、向上提明度要降饱和，
 * 这里用 (v0/v) 近似保色度，再乘经验系数（浅 0.8 / 深 1.2）把经典绿校准到系统
 * overlay 的实测值（#7E525F / #F0B8C6）。
 */
private fun readableTone(c: Color, light: Boolean, v: Float): Color {
    val h = SeedColors.hueOf(c)
    val s = SeedColors.satOf(c)
    val v0 = SeedColors.lightnessOf(c)
    // 黑白方案（s≈0）没有色度可保，直接给灰阶，别凭空长出红/蓝色相
    if (v0 < 0.02f || s < 0.02f) return SeedColors.hsl(h, 0f, v)
    val s2 = (s * (v0 / v) * (if (light) 0.80f else 1.20f)).coerceIn(0f, 0.60f)
    return SeedColors.hsl(h, s2, v)
}

/**
 * 容器色：色相不变，浅色留源色约一半色度、深色放大到源色之上。
 *
 * 旧公式两头都乘 0.40 并把深色明度钉在 0.26，深色下第二色相直接退化成暖灰
 * （#42383B，S 0.15）——系统同一支是 #643B47（S 0.41）。深色必须保/提饱和，
 * 因为它要在一片近黑的底上被看见。
 */
private fun containerTone(c: Color, light: Boolean): Color {
    val s = SeedColors.satOf(c)
    val v0 = SeedColors.lightnessOf(c)
    val v = if (light) 0.95f else 0.40f
    // 浅色：源色本来就是亮调，压一半色度正好落在系统 tone-90 的档位；
    // 深色：要把亮调的源色压到 0.40，饱和必须按 (v0/v) 提上去才保得住色度
    // （经典绿 secondary 0.37×0.44/0.40≈0.41，系统实测 0.41）。
    val s2 = if (light) (s * 0.55f).coerceIn(0.06f, 0.30f)
    else (s * (v0 / v)).coerceIn(0.12f, 0.55f)
    return SeedColors.hsl(SeedColors.hueOf(c), s2, v)
}

/** 容器上的前景：同色相、留更多色度；浅色压暗到可读，深色提到 0.90（系统 on_*_container 同档）。 */
private fun onContainerTone(c: Color, light: Boolean): Color {
    val s = SeedColors.satOf(c)
    val v0 = SeedColors.lightnessOf(c)
    val v = if (light) 0.24f else 0.90f
    val s2 = if (light) (s * 0.90f).coerceIn(0.05f, 0.60f)
    else (s * (v0 / v)).coerceIn(0.05f, 0.45f)
    return SeedColors.hsl(SeedColors.hueOf(c), s2, v)
}

// ─────────────── WCAG 对比度工具（2026-11-08 审计后新增，tmp/contrast_audit.py 同口径） ───────────────

/**
 * WCAG 相对亮度。为什么不用 HSV 的 V：**V 是"通道最大值"，不是亮度**——
 * `#0055D5` 的 V=0.84（看着"亮"），真实相对亮度只有 0.113。整条派生链里
 * 凡是拿 V 当亮度的判断都是错的（曾致亮蓝·浅主按钮文字只有 2.89:1）。
 * 另注意 `SeedColors.hsl` 实际是 HSVToColor，第三参是 V 不是 L。
 */
internal fun wcagLuminance(c: Color): Float {
    fun lin(v: Float): Float =
        if (v <= 0.04045f) v / 12.92f else ((v + 0.055f) / 1.055f).pow(2.4f)
    return 0.2126f * lin(c.red) + 0.7152f * lin(c.green) + 0.0722f * lin(c.blue)
}

/** WCAG 对比度，(L亮+0.05)/(L暗+0.05)。 */
internal fun wcagContrast(a: Color, b: Color): Float {
    val la = wcagLuminance(a)
    val lb = wcagLuminance(b)
    val hi = maxOf(la, lb)
    val lo = minOf(la, lb)
    return (hi + 0.05f) / (lo + 0.05f)
}

/** 近黑前景（M3 on* 的暗档）。 */
internal val DarkInk = Color(0xFF141218)

/**
 * 在 [c] 底上选黑白前景：**算两个候选的对比度、取高的**，不再按 V 拍脑袋。
 * 黑白/中性这类中灰底（V=0.49、黑白前景都在 4:1 边缘）尤其依赖这个——
 * 按亮度选会选错边（实测 白/黑 各 4.09 vs 4.56）。
 */
internal fun readableOnColor(c: Color): Color =
    if (wcagContrast(c, Color.White) >= wcagContrast(c, DarkInk)) Color.White else DarkInk

/**
 * 由内置方案生成整套 M3 色板：主色直接用采样值（与系统一致），
 * 第二/三色按官方 tone 档取**可读前景**，容器各自按自身色相派生。
 * 表面/错误色仍由 primary 走 [colorSchemeFromSeed]。
 */
fun colorSchemeFromFixedStyle(style: Int, dark: Boolean): ColorScheme {
    val triad = fixedStyleTriad(style, dark)
    val primary = triad[0]
    val light = !dark
    // 前景取 40 档（浅）/ 80 档（深），不再照搬取色圆里的淡彩/深彩
    val secondary = readableTone(triad[1], light, if (light) 0.49f else 0.94f)
    val tertiary = readableTone(triad[2], light, if (light) 0.55f else 0.97f)
    val base = colorSchemeFromSeed(primary, dark)

    // 2026-11-08 改对比度选黑白：旧写法拿 HSV 的 V>0.55 当"亮底"，但 V 是通道最大值不是亮度
    // （#0055D5 的 V=0.84、相对亮度只有 0.113），亮蓝·浅的主按钮文字因此只有 2.89:1。
    fun onColor(c: Color): Color = readableOnColor(c)

    fun containerOf(c: Color): Color = containerTone(c, light)

    fun onContainerOf(c: Color): Color = onContainerTone(c, light)

    return base.copy(
        primary = primary,
        onPrimary = onColor(primary),
        primaryContainer = containerOf(primary),
        onPrimaryContainer = onContainerOf(primary),
        secondary = secondary,
        onSecondary = onColor(secondary),
        secondaryContainer = containerOf(secondary),
        onSecondaryContainer = onContainerOf(secondary),
        tertiary = tertiary,
        onTertiary = onColor(tertiary),
        tertiaryContainer = containerOf(tertiary),
        onTertiaryContainer = onContainerOf(tertiary)
    )
}

/**
 * 「其他颜色」整套色板。
 *
 * - **浅色 + 命中 8 套预设之一**：图里的 7 个角色直接用（primary / *_Container /
 *   surfaceContainer / surfaceContainerHigh / inverseSurface），其余角色按 M3 tone 档
 *   从这些值派生——secondary/tertiary 前景取对应容器的同相 tone-40/55，
 *   on*Container 取同相 tone-24，保证浅底上可读；
 * - **深色**：设计软件没有出深色表（用户 2026-10-26 确认），按 M3 规范从图值生成——
 *   primary 取 tone-80、容器取 tone-30、on*Container 取 tone-90、表面阶梯沿用
 *   现有深色档距（0.175 / 0.215 / 0.255…），色相与色度全部继承图值；
 * - 不在预设里的旧自定义种子：仍走 [colorSchemeFromSeed]（与历史观感一致）。
 */
fun colorSchemeFromPreset(seedArgb: Int, dark: Boolean): ColorScheme {
    val p = PRESET_LIGHTS.firstOrNull { it.primary == seedArgb }
        ?: return colorSchemeFromSeed(Color(seedArgb), dark)
    return if (dark) darkSchemeFromPreset(p) else lightSchemeFromPreset(p)
}

/** 浅色：图值直接用，缺的角色从容器同相派生 */
private fun lightSchemeFromPreset(p: PresetLight): ColorScheme {
    val primary = Color(p.primary)
    val primaryContainer = Color(p.primaryContainer)
    val secondaryContainer = Color(p.secondaryContainer)
    val tertiaryContainer = Color(p.tertiaryContainer)
    // 前景 = 容器同相的可读调（M3 里 secondary 就是同一调色板的 tone-40）
    val secondary = readableTone(secondaryContainer, true, 0.49f)
    val tertiary = readableTone(tertiaryContainer, true, 0.55f)
    val surfaceContainer = Color(p.surfaceContainer)
    val surfaceContainerHigh = Color(p.surfaceContainerHigh)
    val inverseSurface = Color(p.inverseSurface)
    val base = colorSchemeFromSeed(primary, false)
    return base.copy(
        primary = primary,
        onPrimary = onColorFor(primary),
        primaryContainer = primaryContainer,
        onPrimaryContainer = onContainerTone(primaryContainer, true),
        secondary = secondary,
        onSecondary = onColorFor(secondary),
        secondaryContainer = secondaryContainer,
        onSecondaryContainer = onContainerTone(secondaryContainer, true),
        tertiary = tertiary,
        onTertiary = onColorFor(tertiary),
        tertiaryContainer = tertiaryContainer,
        onTertiaryContainer = onContainerTone(tertiaryContainer, true),
        // 图只给 container / High 两档：Low 夹在 surface 与 container 之间、
        // Highest 按 High 再压一档，档距与现有浅色阶梯一致
        surfaceContainerLow = lerp(base.surface, surfaceContainer, 0.55f),
        surfaceContainer = surfaceContainer,
        surfaceContainerHigh = surfaceContainerHigh,
        surfaceContainerHighest = lerp(surfaceContainerHigh, Color.Black, 0.05f),
        inverseSurface = inverseSurface,
        inverseOnSurface = Color.White,
        inversePrimary = SeedColors.hsl(
            SeedColors.hueOf(primary), SeedColors.satOf(primary) * 0.9f, 0.80f
        )
    )
}

/**
 * 深色：按 M3 音阶从图值生成（用户确认设计软件没有深色表）。
 *
 * 关键换档：primary tone40→80、容器 tone90→30、on*Container →90；
 * 色相全部保留，饱和度按「深底要提色度」的经验系数放大
 * （与 [colorSchemeFromSeed] 深色分支同一套档位，浅深观感连贯）。
 */
private fun darkSchemeFromPreset(p: PresetLight): ColorScheme {
    val primary = Color(p.primary)
    val secContainer = Color(p.secondaryContainer)
    val terContainer = Color(p.tertiaryContainer)
    val surf = Color(p.surfaceContainer)
    val C = SeedColors
    val h = C.hueOf(primary)
    val s = C.satOf(primary)
    val h2 = C.hueOf(secContainer)
    val s2 = C.satOf(secContainer)
    val h3 = C.hueOf(terContainer)
    val s3 = C.satOf(terContainer)
    val hs = C.hueOf(surf)
    val ss = C.satOf(surf)
    return darkColorScheme(
        primary = C.hsl(h, (s * 0.9f).coerceIn(0.10f, 0.62f), 0.78f),
        onPrimary = C.hsl(h, s * 0.9f, 0.14f),
        primaryContainer = C.hsl(h, (s * 0.55f).coerceIn(0.10f, 0.40f), 0.30f),
        onPrimaryContainer = C.hsl(h, (s * 0.60f).coerceIn(0.08f, 0.45f), 0.90f),
        secondary = readableTone(secContainer, false, 0.90f),
        onSecondary = C.hsl(h2, s2 * 0.5f, 0.14f),
        secondaryContainer = C.hsl(h2, (s2 * 0.90f).coerceIn(0.10f, 0.40f), 0.30f),
        onSecondaryContainer = C.hsl(h2, (s2 * 0.60f).coerceIn(0.08f, 0.45f), 0.90f),
        tertiary = readableTone(terContainer, false, 0.92f),
        onTertiary = C.hsl(h3, s3 * 0.5f, 0.14f),
        tertiaryContainer = C.hsl(h3, (s3 * 0.90f).coerceIn(0.10f, 0.40f), 0.32f),
        onTertiaryContainer = C.hsl(h3, (s3 * 0.60f).coerceIn(0.08f, 0.45f), 0.90f),
        // 与 colorSchemeFromSeed 深色分支同源：M3 官方暗色 error 基线（见那边注释）
        error = Color(0xFFFFB4AB),
        onError = Color(0xFF601410),
        errorContainer = Color(0xFF93000A),
        onErrorContainer = Color(0xFFFFDAD6),
        // background 与 surface 同值（2026-11-20 用户指令：背景跟左侧菜单栏同一个颜色）。
        // 原本 background 比 surface 暗一档（暗 0.09 vs 0.115），页面级填充用 background、
        // 左侧 Rail 用 surface → 宽屏上两栏之间仍有一道色阶接缝。
        background = C.hsl(hs, ss * 0.35f, 0.115f),
        onBackground = C.hsl(hs, ss * 0.20f, 0.91f),
        surface = C.hsl(hs, ss * 0.35f, 0.115f),
        onSurface = C.hsl(hs, ss * 0.20f, 0.91f),
        surfaceVariant = C.hsl(hs, ss * 0.40f, 0.19f),
        onSurfaceVariant = C.hsl(hs, ss * 0.20f, 0.74f),
        outline = C.hsl(hs, ss * 0.25f, 0.56f),
        outlineVariant = C.hsl(hs, ss * 0.25f, 0.30f),
        surfaceContainer = C.hsl(hs, ss * 0.40f, 0.175f),
        surfaceContainerHigh = C.hsl(hs, ss * 0.40f, 0.215f),
        surfaceContainerHighest = C.hsl(hs, ss * 0.40f, 0.255f),
        surfaceContainerLow = C.hsl(hs, ss * 0.35f, 0.150f),
        surfaceContainerLowest = C.hsl(hs, ss * 0.30f, 0.07f),
        inverseSurface = C.hsl(hs, ss * 0.20f, 0.91f),
        inverseOnSurface = C.hsl(hs, ss * 0.30f, 0.18f),
        inversePrimary = C.hsl(h, (s * 0.80f).coerceIn(0.10f, 0.55f), 0.80f)
    )
}

/** 按相对亮度选黑白前景：浅底（黄/浅青容器派生的前景）上白字会糊，必须按亮度判断 */
private fun onColorFor(c: Color): Color = readableOnColor(c)

/**
 * colorSchemeFromSeed：由种子色以 HSL 手工推导整套 M3 色板。
 *
 * @param variant 拼色风格（仅 colorSource=0 使用；自选种子固定 0），对齐原生取色器四个圆：
 *  0 壁纸色——壁纸主色 + 互补/邻近第二三色（多彩观感）；1 黑白——纯灰阶且明度拉开
 *（浅色 primary 近黑、深色 primary 近白，与「中性」的中间灰明显不同）；
 *  2 中性——近灰带极淡色相；3 亮蓝——固定应用默认蓝紫（与壁纸无关）。
 */
fun colorSchemeFromSeed(seed: Color, dark: Boolean, variant: Int = 0): ColorScheme {
    val baseH = SeedColors.hueOf(seed)
    val baseS = SeedColors.satOf(seed)
    // 四种风格对齐系统原生取色器的四个圆（用户拿截图对照：几个颜色差别挺大的）：
    //  0 壁纸色：壁纸主色 + 互补/邻近第二三色（原生选中圆的多彩观感）；
    //  1 黑白：纯灰阶（s=0），原生第一个圆；
    //  2 中性：近灰带极淡色相，原生第二个圆；
    //  3 亮蓝：固定用应用默认蓝（与壁纸无关，原生第三个圆永远是这个蓝紫）。
    val defaultBlueH = SeedColors.hueOf(Color(0xFF576FF5))
    val h = if (variant == 3) defaultBlueH else baseH
    val s = when (variant) {
        1 -> 0f
        2 -> 0.06f
        3 -> 0.62f
        else -> baseS.coerceIn(0.32f, 0.62f)
    }
    // 壁纸色风格把第二/三色拉开成互补与邻近色，圆里才有多彩感；其余风格沿用
    // 原来 +28°/+62° 的克制偏移（灰阶方案下饱和度已为 0，偏移不影响观感）。
    val secondaryOffset = if (variant == 0) 180f else 28f
    val tertiaryOffset = if (variant == 0) 120f else 62f
    // 饱和度上限 0.62（旧值 0.85）。
    //
    // 种子色里 0xFF00696E（青绿）与 0xFF7B2FBE（紫罗兰）的原始饱和度都接近 1.0，
    // 旧上限几乎等于原样放大——浅色模式下 primary 直接是荧光级别的青/紫，
    // 整屏刺眼（用户反馈「绿色、紫色主题色饱和度太高」）。
    // 压到 0.62 之后仍然是明确的彩色，但回到 M3 那种克制的浓度；
    // 下限留 0.32 防止低饱和种子被压成灰——但黑白/中性方案本身就是灰，要放过 0。
    val sClamped = s.coerceIn(0f, 0.62f)
    // 黑白方案把明度也拉开（原生第一个圆：浅色顶黑、底白/浅灰；深色顶白、底深灰）。
    // 若只把饱和度打到 0，四个角色仍是同一档中灰，会和「中性」圆混为一谈。
    // 壁纸色方案（0）的第二/三色是互补+邻近色，如果沿用克制的饱和/明度档，深色下
    // 发灰发白，预览圆下两瓣看着像"颜色没显示"（用户实测反馈）——给它更高饱和、
    // 更实的明度，下两瓣才有真正的颜色。
    val secSatFactor = if (variant == 0) 0.95f else 0.55f
    val terSatFactor = if (variant == 0) 0.88f else 0.50f
    val lightPrimaryL = if (variant == 1) 0.12f else 0.44f
    val lightSecondaryL = when (variant) { 1 -> 0.97f; 0 -> 0.62f; else -> 0.42f }
    val lightTertiaryL = when (variant) { 1 -> 0.85f; 0 -> 0.74f; else -> 0.40f }
    val darkSecondaryL = when (variant) { 1 -> 0.28f; 0 -> 0.60f; else -> 0.74f }
    val darkTertiaryL = when (variant) { 1 -> 0.55f; 0 -> 0.66f; else -> 0.76f }
    val C = SeedColors
    // 第二/三色同样按官方 tone 档重取前景与容器（与 colorSchemeFromFixedStyle 一致）：
    // 旧写法把 0.62/0.60 这类中间调当 secondary，深色下不到可读亮度；
    // 容器饱和度也压到 0.35 倍，第二色相被抹平。
    val secRaw = C.hsl(h + secondaryOffset, sClamped * secSatFactor,
        if (dark) darkSecondaryL else lightSecondaryL)
    val terRaw = C.hsl(h + tertiaryOffset, sClamped * terSatFactor,
        if (dark) darkTertiaryL else lightTertiaryL)
    val sec = readableTone(secRaw, !dark, if (dark) 0.94f else 0.49f)
    val ter = readableTone(terRaw, !dark, if (dark) 0.97f else 0.55f)
    val secContainer = containerTone(secRaw, !dark)
    val terContainer = containerTone(terRaw, !dark)
    return if (dark) {
        darkColorScheme(
            primary = C.hsl(h, sClamped * 0.9f, 0.78f),
            onPrimary = C.hsl(h, sClamped * 0.9f, 0.14f),
            primaryContainer = containerTone(C.hsl(h, sClamped, 0.78f), false),
            onPrimaryContainer = onContainerTone(C.hsl(h, sClamped, 0.78f), false),
            secondary = sec,
            onSecondary = C.hsl(h + secondaryOffset, sClamped * 0.55f, 0.14f),
            secondaryContainer = secContainer,
            onSecondaryContainer = onContainerTone(secRaw, false),
            tertiary = ter,
            onTertiary = C.hsl(h + tertiaryOffset, sClamped * 0.5f, 0.14f),
            tertiaryContainer = terContainer,
            onTertiaryContainer = onContainerTone(terRaw, false),
            // 2026-11-08 暗色 error 四件套对齐 M3 官方基线（tone80/20/30/90）。旧值
            // hsl(4,.72,.70) 在 HSV 里是"饱和中红 #B23B32"，相对亮度只有 0.12，
            // 压暗面文字只有 2.0–2.4:1（`color = colorScheme.error` 全仓 61 处使用）。
            // M3 的暗色 error 是"低饱和高亮"的鲑红——这里直接写 hex，杜绝再被 HSV/HSL 混淆。
            error = Color(0xFFFFB4AB),          // M3 dark error  = tone80 #FFB4AB
            onError = Color(0xFF601410),        // M3 dark onError = tone20 #601410
            errorContainer = Color(0xFF93000A), // M3 dark errorContainer = tone30 #93000A
            onErrorContainer = Color(0xFFFFDAD6), // M3 dark onErrorContainer = tone90 #FFDAD6
            // background 与 surface 同值（2026-11-20：背景跟左侧 Rail 同一个颜色）
            background = C.hsl(h, sClamped * 0.10f, 0.115f),
            onBackground = C.hsl(h, sClamped * 0.10f, 0.91f),
            surface = C.hsl(h, sClamped * 0.10f, 0.115f),
            onSurface = C.hsl(h, sClamped * 0.10f, 0.91f),
            surfaceVariant = C.hsl(h, sClamped * 0.14f, 0.19f),
            onSurfaceVariant = C.hsl(h, sClamped * 0.10f, 0.74f),
            outline = C.hsl(h, sClamped * 0.10f, 0.56f),
            outlineVariant = C.hsl(h, sClamped * 0.10f, 0.30f),
            // 暗色容器阶梯同步拉开（浅色段见下方注释，2026-10-25 用户）：Low 0.125 与
            // surface 0.115 几乎同色，折叠标题栏在暗色下完全融进正文。
            surfaceContainer = C.hsl(h, sClamped * 0.12f, 0.175f),
            surfaceContainerHigh = C.hsl(h, sClamped * 0.12f, 0.215f),
            surfaceContainerHighest = C.hsl(h, sClamped * 0.12f, 0.255f),
            surfaceContainerLow = C.hsl(h, sClamped * 0.11f, 0.150f),
            surfaceContainerLowest = C.hsl(h, sClamped * 0.10f, 0.07f)
        )
    } else {
        lightColorScheme(
            primary = C.hsl(h, sClamped, lightPrimaryL),
            onPrimary = Color.White,
            primaryContainer = containerTone(C.hsl(h, sClamped, lightPrimaryL), true),
            onPrimaryContainer = onContainerTone(C.hsl(h, sClamped, lightPrimaryL), true),
            secondary = sec,
            onSecondary = Color.White,
            secondaryContainer = secContainer,
            onSecondaryContainer = onContainerTone(secRaw, true),
            tertiary = ter,
            onTertiary = Color.White,
            tertiaryContainer = terContainer,
            onTertiaryContainer = onContainerTone(terRaw, true),
            error = C.hsl(4f, 0.78f, 0.48f),
            onError = Color.White,
            errorContainer = C.hsl(4f, 0.85f, 0.90f),
            onErrorContainer = C.hsl(4f, 0.9f, 0.14f),
            // 浅色背景的取色：饱和度压到很低，接近纯白。
            // 原来是 s*0.18（默认种子下约 11% 饱和），整屏明显偏紫/偏蓝，
            // 用户反馈「普通模式下背景要更偏纯白」。现在 layering 只靠明度差表达，
            // 容器档（surfaceContainer*）留一点点色偏，否则层次会糊成一片白。
            // background 与 surface 同值（2026-11-20：背景跟左侧 Rail 同一个颜色）
            background = C.hsl(h, sClamped * 0.05f, 0.985f),
            onBackground = C.hsl(h, sClamped * 0.15f, 0.12f),
            // 容器阶梯（2026-10-25 用户「标题栏与正文根本没做出颜色区分」）：原 Low 0.968 与
            // surface 0.985 只差 1.7%，折叠标题栏/卡片/块面全糊成一片。现在每档拉开 ≥2.5%
            // 明度，M3ETitleBar 折叠态（containerLow）与正文、块面与背景都能读出层次。
            surface = C.hsl(h, sClamped * 0.05f, 0.985f),
            onSurface = C.hsl(h, sClamped * 0.15f, 0.12f),
            surfaceVariant = C.hsl(h, sClamped * 0.10f, 0.92f),
            onSurfaceVariant = C.hsl(h, sClamped * 0.12f, 0.34f),
            outline = C.hsl(h, sClamped * 0.10f, 0.52f),
            outlineVariant = C.hsl(h, sClamped * 0.10f, 0.86f),
            surfaceContainer = C.hsl(h, sClamped * 0.07f, 0.940f),
            surfaceContainerHigh = C.hsl(h, sClamped * 0.07f, 0.922f),
            surfaceContainerHighest = C.hsl(h, sClamped * 0.08f, 0.900f),
            surfaceContainerLow = C.hsl(h, sClamped * 0.05f, 0.955f),
            surfaceContainerLowest = Color.White
        )
    }
}

/**
 * 暗色模式应用背景：**实色**，与左侧 NavigationRail 的底色完全一致
 * （2026-11-20 用户指令：「背景不要再渐变了，改成跟左边菜单栏同一个颜色」）。
 *
 * 原来这里是 ColorOS 同款垂直渐变（#2B2C31→#151518），与 Rail 的
 * `colorScheme.surface` 不是同一个色 → 宽屏上 Rail 与内容区之间出现明显色阶接缝。
 * 现改为根层直接铺 `MaterialTheme.colorScheme.surface`（见 MainActivity / LaunchAnim），
 * Rail 与内容区同色、色阶只在卡片层（surfaceContainer*）上出现。
 *
 * 冷启动窗口底色（res/drawable/launch_bg_night.xml、@color/splash_bg_night）
 * 同步改成同档实色，避免首帧渐变→实色的跳变。
 */
@Composable
fun PlanabilityTheme(
    config: ThemeConfig = ThemeConfig(),
    content: @Composable () -> Unit
) {
    val dark = when (config.mode) {
        1 -> false
        2 -> true
        else -> isSystemInDarkTheme()
    }
    val scheme = if (config.colorSource == 0) {
        // 拼色 = 内置四套方案（照抄系统原生取色器的颜色，不取壁纸/种子）；
        // wallpaperVariant 是所选方案序号。
        colorSchemeFromFixedStyle(config.wallpaperVariant, dark)
    } else {
        // 其他颜色 = 8 颗预设（浅色直接用图里的角色值，深色暂走同相推导）
        colorSchemeFromPreset(config.seed, dark)
    }
    MaterialTheme(
        colorScheme = scheme,
        typography = typography(config.fontScale, config.fontFamily, config.fontWeight),
        // M3 Expressive 弹簧动效：库内已采纳 motionScheme 的组件（进度、弹层、chips 等）统一走
        // expressive 弹簧曲线（beta02 起公开，见 gradle/libs.versions.toml 的 material3 注）。
        // 注意它不接管 ui/common/Motion.kt 的自研三档 tween——那是应用自己编排的转场。
        motionScheme = MotionScheme.expressive(),
        content = content
    )
}
