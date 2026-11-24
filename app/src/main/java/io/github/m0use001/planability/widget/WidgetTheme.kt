package io.github.m0use001.planability.widget

import android.content.Context
import android.content.res.Configuration
import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceModifier
import androidx.glance.background
import androidx.glance.appwidget.cornerRadius
import androidx.glance.text.FontWeight
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import io.github.m0use001.planability.data.Graph
import io.github.m0use001.planability.data.ThemeConfig
import io.github.m0use001.planability.ui.theme.SeedColors
import io.github.m0use001.planability.ui.theme.colorSchemeFromPreset

/**
 * 小组件取色层：**运行时**从用户的主题种子色推色板，不写死任何资源色。
 *
 * ## 为什么单独一层
 * 旧写法（`WidgetCommon.WidgetColors`）把 `#17171C / #AAB6FF` 直接钉死在代码里，
 * 于是用户在「外观 → 主题色」里换了色，应用内立刻变、桌面小组件纹丝不动——
 * 两处看起来像同一个应用，实际是两套配色。
 * 这里改为读 `Graph.settings.snapshot` 的 `ThemeConfig.seed`，与 `PlanabilityTheme`
 * 走**同一个** `colorSchemeFromPreset`（浅色=8 套预设的精确角色值，深色暂走推导），
 * 保证应用内和桌面是同一套色板。
 *
 * ## 明暗
 * 复用 `ThemeConfig.mode` 的 0 跟随系统 / 1 浅 / 2 深，不自己另发明一套。
 *
 * ## 圆角：用 Glance 自带的 `cornerRadius`
 * 这里有过一次**被真机打脸**的弯路，留档给后人：
 * 一开始判断「Glance 1.1 没有圆角能力」，于是用
 * `.background(ImageProvider(圆角shape)).background(颜色)` 两步叠，
 * 指望 API 31+ 的 `setBackgroundColor` 是「给已有 drawable 上色」。
 * 结果子元素（统计块、指令行、倒数日圆盘）**全都渲染成直角方块**——
 * 那层染色把圆角 shape 吃掉了，只有启动器自己裁的外层圆角是真的。
 * 其实 `androidx.glance.appwidget.cornerRadius(Dp)` 一直都在
 * （`ApplyModifiersKt.applyModifiers` 逐节点消费 `CornerRadiusModifier`）。
 * 现在统一 `background(色) + cornerRadius(半径)`，圆角才真正生效。
 *
 * **任何直角块放在圆角卡片里都很突兀**，所以宁可多做一层形状：
 * 统计块 18dp、外壳 28dp、chip 与按钮 999dp（夹成胶囊/正圆）。
 */
object WidgetTheme {

    /** 种子色读取失败时的兜底，与 `ThemeConfig` 默认值同源 */
    private val fallback = ThemeConfig()

    /** 明暗判定：0 跟随系统 / 1 浅 / 2 深 */
    fun isNight(context: Context, mode: Int): Boolean = when (mode) {
        1 -> false
        2 -> true
        else -> (context.resources.configuration.uiMode and
            Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    }

    /** 取当前色板。任何取色失败都退回默认种子色，绝不让组件崩在 provideGlance 里。 */
    fun palette(context: Context): WidgetPalette = palette(context, null)

    /**
     * @param size 组件当前的真实尺寸（`LocalSize.current`）。
     *
     * **字号要跟着尺寸走**：同一个 3×2 上合适，用户拉到 4×3 之后字就显得很小、
     * 缩到 2×2 又会溢出。Glance 没有「自适应字号」，所以自己按高度换算一个缩放因子，
     * 基准是 3×2 的约 170dp。上下夹住 0.88–1.32：再小就看不清，再大必然撑破。
     */
    fun palette(context: Context, size: DpSize?): WidgetPalette {
        val cfg = runCatching { Graph.settings.snapshot.value.theme }.getOrNull() ?: fallback
        val dark = isNight(context, cfg.mode)
        // 配色来源 0=拼色：与应用内共用 ui.theme.colorSchemeFromFixedStyle
        // （内置四套照抄原生的方案，不取壁纸），选中的方案序号来自
        // ThemeConfig.wallpaperVariant——应用内与桌面组件逐角色一致。
        val scheme = runCatching {
            if (cfg.colorSource == 0) {
                io.github.m0use001.planability.ui.theme.colorSchemeFromFixedStyle(cfg.wallpaperVariant, dark)
            } else {
                colorSchemeFromPreset(cfg.seed, dark)
            }
        }.getOrElse { colorSchemeFromPreset(fallback.seed, dark) }
        // 字号 = 尺寸因子 × 用户字号偏好（外观页 fontScale 0.8–1.3；此前组件不乘它，
        // App 里调大字号桌面却纹丝不动）。整体夹在 0.88–1.6：上限防「4×3 大格 + 1.3 字号」
        // 时文字撑破容器，下限保证小格仍可读。
        val sizeScale = size?.let { (it.height.value / 170f).coerceIn(0.88f, 1.32f) } ?: 1f
        val scale = (sizeScale * cfg.fontScale).coerceIn(0.88f, 1.6f)
        return WidgetPalette(scheme, dark, scale)
    }
}

/**
 * 一份色板 + 一套字号。字号按 M3 Expressive 的字阶思路重新分档：
 * 组件里真正需要「一眼看到」的数字才是 hero，其余数字一律降级，
 * 避免整版组件全是同权重大字、层级糊掉。
 *
 * @param scale 尺寸因子 × 用户字号偏好（随格子自适应）
 */
class WidgetPalette(
    /** 原始 ColorScheme：位图渲染（WidgetArt）的容器/on 色从这里取 */
    val scheme: ColorScheme,
    val dark: Boolean,
    val scale: Float = 1f
) {

    /** 字号缩放：所有 sp 都过我这一道，保证「一处改、全体跟」 */
    private fun fs(base: Float) = (base * scale).sp

    /**
     * 少数几个**原始 Color 值**。
     * `ColorProvider` 只能喂给 Glance 组件；而趋势图是拿 Android `Canvas` 自己画的，
     * 那边要的是 ARGB int。两套表示放在同一个对象里，避免画图和渲染各拿一份色板导致色差。
     */
    val accentColor = scheme.primary
    val subColor = scheme.onSurfaceVariant

    /**
     * **主色强调数字**（2026-11-01 统一）：胶囊时钟的时间、倒数日的剩余天数、
     * 今日数据的四项数值，三者是同一个角色（"组件里那个主色大数字"），必须同色。
     *
     * 此前是三套算式各算各的，深色下三个不同的绿：
     *  - 时钟时间  hsl(primary, 0.95)  → #A9F2AF
     *  - 倒数日天数 scheme.primary 原值 → #95D69B
     *  - 今日数据  hsl(primary, 0.92)  → #A3EBAA
     * 并排放在桌面上，同一角色的绿肉眼可分。现在只此一份：
     * 深色抬到 V0.95（原生大时钟实测 #B0F2B6 的明度档），浅色直接用 primary
     * （浅色 primary 本身已过对比度，抬亮反而变淡）。
     */
    val heroNumberColor: Color = if (dark) SeedColors.hsl(
        SeedColors.hueOf(scheme.primary), SeedColors.satOf(scheme.primary), 0.95f
    ) else scheme.primary

    /**
     * **卡片上的首要文字**（2026-11-01 统一）：时钟的日期/完成率、倒数日的名字、
     * 今日总览的日期、多天总览的日期、屏幕使用时间的标题与总时长、今日数据的行标签。
     *
     * 此前时钟用 hsl(onSurface, 0.96)（原生辅助行的近白实测）而其它组件用 onSurface 原值
     * （深色 V0.91），两块卡并排能看出"同样是正文，一个更白"。
     * 现在统一到抬亮档：深色 0.96（原生实测 #F3EFFA 量级），浅色 onSurface。
     */
    val textOnCardColor: Color = if (dark) SeedColors.hsl(
        SeedColors.hueOf(scheme.onSurface), SeedColors.satOf(scheme.onSurface), 0.96f
    ) else scheme.onSurface

    /**
     * 「卡片上的首要文字」的 ColorProvider 形态（Glance 组件用）。
     * 值与 [textOnCardColor] 同源 —— 一处改、两套表示一起跟。
     */

    // chip 文字 11sp 属小字（要 4.5:1）：onPrimaryContainer(L0.90) 压 primaryContainer(L0.30)
    // 实算 3.99 不达标，明度抬到 0.93；浅色远超，不动。
    private val chipTextColor = if (dark) SeedColors.hsl(
        SeedColors.hueOf(scheme.onPrimaryContainer),
        SeedColors.satOf(scheme.onPrimaryContainer) * 0.7f, 0.93f
    ) else scheme.onPrimaryContainer

    /**
     * 外壳底色。**故意用半透明**：桌面启动器自己已经给小组件画了一层带圆角的容器，
     * 旧代码用不透明 `#17171C` 直接铺满，等于把启动器的圆角盖成一个硬邦邦的直角矩形——
     * 这是小组件「不好看」最主要的一条。半透明让它透出来，同时保证壁纸上的可读性。
     */
    val shell = ColorProvider(scheme.surface.withAlpha(if (dark) 0.82f else 0.92f))

    /** 统计块/内容块的底：**第二色容器**（原生小组件的暗红块面——拼色规则 R4：
     *  第二色用于"面"，不用于按钮/文字）。ala 两档深浅：block 是标准档，
     *  blockAlt 是加透明度后更暗的一档（图表底用）。 */
    val block = ColorProvider(scheme.secondaryContainer)
    val blockAlt = ColorProvider(scheme.secondaryContainer.copy(alpha = 0.72f))

    /** 胶囊 chip（标题旁的计数徽标、标签）——第三角色：桌面组件是
     *  第二/三色少数能露脸的表面，拼色的"圆底两瓣"在桌面上才看得到。 */
    val chip = ColorProvider(scheme.tertiaryContainer)
    val onChip = ColorProvider(chipTextColor)

    /** 可点条目的面：**主色容器**（R1 交互控件只用主色族）。
     *  此前快捷指令行用的是 blockAlt（第二色面），与同桌面「范围胶囊」的主色族形态不一致。 */
    val primaryPill = ColorProvider(scheme.primaryContainer)

    /** 主色（强调数字、圆盘软底） */
    val accent = ColorProvider(scheme.primary)
    val accentSoft = ColorProvider(scheme.primary.copy(alpha = 0.22f))

    val text = ColorProvider(scheme.onSurface)
    val sub = ColorProvider(scheme.onSurfaceVariant)

    // —— 字阶（全部经 [fs] 缩放，随组件尺寸自适应；收敛到 M3 刻度 11/12/14/16/24/36）——
    /** 组件标题（比正文大两档：此前 14 vs 13 只差 1sp，层级拉不开） */
    val title = TextStyle(fontSize = fs(16f), fontWeight = FontWeight.Bold, color = text)

    /** 次要说明、日期 */
    val label = TextStyle(fontSize = fs(11f), fontWeight = FontWeight.Medium, color = sub)

    /** 大数字后面的单位（「还有 12 **天**」）——压在数字右下不抢戏 */
    val unit = TextStyle(fontSize = fs(12f), fontWeight = FontWeight.Bold, color = sub)

    /** 胶囊 chip 上的文字 */
    val labelOnChip = TextStyle(fontSize = fs(11f), fontWeight = FontWeight.Medium, color = onChip)

    /** 正文行 */
    val body = TextStyle(fontSize = fs(14f), color = text)

    /** 主色容器上的正文行（可点条目，如快捷指令行）：颜色与 chip 文字同一套对比度抬升 */
    val bodyOnPrimary = TextStyle(fontSize = fs(14f), color = onChip)

    /** 三格统计里最大的那一个数字用主色（唯一允许的彩色数字） */
    val statLead = TextStyle(fontSize = fs(24f), fontWeight = FontWeight.Bold, color = ColorProvider(heroNumberColor))
    val stat = TextStyle(fontSize = fs(16f), fontWeight = FontWeight.Bold, color = text)

    /** 倒数日这类整版只有一个主角的组件 */
    val hero = TextStyle(fontSize = fs(36f), fontWeight = FontWeight.Bold, color = ColorProvider(heroNumberColor))

    /** 倒数日花形组件的圆盘数字/单位（2026-10-25 用户草图：天数装进小圆） */
    val discNum = TextStyle(fontSize = fs(18f), fontWeight = FontWeight.Bold, color = ColorProvider(heroNumberColor))
    val discUnit = TextStyle(fontSize = fs(10f), fontWeight = FontWeight.Medium, color = sub)

    /** 趋势图底部星期标签（今天一档，其余压暗一档；居中对齐各自的柱子） */
    fun chartDay(isToday: Boolean) = TextStyle(
        fontSize = fs(11f),
        fontWeight = FontWeight.Medium,
        color = if (isToday) text else sub,
        textAlign = androidx.glance.text.TextAlign.Center
    )

    // —— 大时钟（巨型胶囊）：位图渲染，色板这里只提供颜色 ——
    //
    // 为什么没有 TextStyle：组件的字是 `ClockArt` 用 Canvas 画的。Glance 1.1.1 的
    // `TextStyle.fontFamily` 在 appwidget 翻译层**没有被消费**（1.2.0 才用
    // `TypefaceSpan(String)`，且只认系统字体族），Text 永远渲染成系统字体——和应用里选的
    // Google Sans Flex / 中文圆体对不上。改用 Canvas 后字体、字重、字距、墨迹位置都可控，
    // 也顺带绕开了 Text 的 font padding（它会把三行内容挤爆）。
    //
    // 下面四个颜色的算式来自**真机同屏逐像素实测**（原生 Solid 大时钟：卡片 #4A2531、
    // 时间 #B0F2B6、日期 #F3EFFA），完整对照表见
    // `设计文档`。

    /**
     * 卡片面：不透明的「第二色家族」实心块。
     * 原生实测色相=secondary(341°) ✓、明度 V0.29、**饱和度 0.50**；我们的 secondary
     * 饱和度只有 0.375，直接搬会偏灰（#4D313A），所以深色按 ×1.35 提到 0.50；浅色取
     * 同色高明度变体（V0.93、饱和 ×0.7）。纯灰方案（黑白）s≈0 时保持 0，不会变红。
     */
    val clockCardColor = run {
        val s = SeedColors.satOf(scheme.secondary)
        val sat = if (s < 0.06f) s else if (dark) {
            (s * 1.35f).coerceIn(0.18f, 0.55f)
        } else {
            (s * 0.70f).coerceIn(0.10f, 0.35f)
        }
        // V：暗色 0.29→0.24、浅色 0.93→0.90（用户反馈「背景有点浅」，双档同压）
        SeedColors.hsl(SeedColors.hueOf(scheme.secondary), sat, if (dark) 0.24f else 0.90f)
    }

    /** 大卡面（倒数日等整版位图组件）：与时钟卡同一套取色，保证桌面同族组件一个调子 */
    val cardColor: Color get() = clockCardColor

    /** 时间：统一走 [heroNumberColor]（与倒数日天数、今日数据数值同色，见那里的长注释） */
    private val clockTimeColor: Color get() = heroNumberColor

    /** 日期/完成率：统一走 [textOnCardColor]（与其它组件的首要文字同色） */
    private val clockTextColor: Color get() = textOnCardColor

    /** 堆叠款小时：第三色可读调（卡片已占第二色，再拿第二色写字会糊成一块） */
    private val clockHoursColor = run {
        val tertiary = scheme.tertiary
        val s = SeedColors.satOf(tertiary)
        val sat = if (s < 0.06f) s else if (dark) {
            (s * 0.85f).coerceIn(0.15f, 0.50f)
        } else {
            (s * 1.5f).coerceIn(0.22f, 0.55f)
        }
        SeedColors.hsl(SeedColors.hueOf(tertiary), sat, if (dark) 0.86f else 0.46f)
    }

    // —— 位图组件（今日合并款 / 多天总览款）用的原始色（ARGB 由调用方 toArgb）——
    /** 卡面：surfaceContainer（不透明）。这两款内容行数多，用中性面比时钟的彩色面稳 */
    val artCard = scheme.surfaceContainer
    /** 今天/选中高亮的底（主色容器）与其上文字 */
    val artPrimaryContainer = scheme.primaryContainer
    val artOnPrimaryContainer = scheme.onPrimaryContainer
    /** 事件圆点/时间列的点缀色（第三色，与时钟小时同族） */
    val artTertiary = scheme.tertiary
    /**
     * 正文色（首位文字的统一口径）。2026-11-01：此前是 onSurface 原值（深色 V0.91），
     * 而时钟的日期/完成率用 V0.96 的近白 —— 两块卡并排能看出「同样是正文，一个更白」。
     * 现在统一走 [textOnCardColor]（深色 0.96 / 浅色 onSurface）。
     */
    val artOnCard: Color get() = textOnCardColor

    /** 位图渲染要 ARGB，不是 ColorProvider */
    internal val clockPaintColors = ClockArt.Colors(
        card = clockCardColor.toArgb(),
        time = clockTimeColor.toArgb(),
        hours = clockHoursColor.toArgb(),
        text = clockTextColor.toArgb()
    )
}

/** 保留 alpha 的取色（ColorScheme 里的 surface 都是不透明的，直接用会盖掉壁纸） */
private fun Color.withAlpha(a: Float): Color = Color(red, green, blue, a)

/**
 * 圆角：**全部方形组件的外轮廓都走 `Squircle`（ColorOS 超椭圆 n=3.25）**。
 *
 * 2026-10-31 用户要求「完全对齐 ColorOS 曲率且连续平滑」——查询结果：
 * Glance 的 `cornerRadius(Dp)` 只能给**圆弧**（它底层就是 `setCornerRadius` / clip 到
 * round rect），做不出超椭圆。所以方形组件的卡面**不再用 Glance 圆角**，一律整幅
 * Canvas 位图（[ClockArt] / [WidgetArt] / [TodayStatsWidget] / [ScreenTimeWidget] 都是这条路），
 * 外轮廓由 [Squircle.cardPath] 绘制。
 *
 * 下面这几个 Glance modifier 只剩历史意义：它们的调用点（[WidgetShell]）已无组件使用
 * （七个组件全部改为整幅位图渲染），保留是为了不让老分支编译失败；
 * **新组件不要再走 Glance 圆角**，否则又是圆弧、又和系统对不上。
 */

/** @deprecated 走 [Squircle.cardPath] 位图渲染；Glance 的弧线圆角对不上 ColorOS */
@Deprecated("Glance cornerRadius 只能画圆弧，对不上 ColorOS 连续曲率；用 Squircle.cardPath + 位图")
fun GlanceModifier.widgetCard(p: WidgetPalette): GlanceModifier = this
    .background(p.shell)
    .cornerRadius(28.dp)

/** @deprecated 同上：内部块也应走 [Squircle.path] 位图路线 */
@Deprecated("Glance cornerRadius 只能画圆弧；用 Squircle.path + 位图")
fun GlanceModifier.widgetBlock(p: WidgetPalette, alt: Boolean = false): GlanceModifier = this
    .background(if (alt) p.blockAlt else p.block)
    .cornerRadius(16.dp)

/** 胶囊（全圆角） */
fun GlanceModifier.widgetPill(p: WidgetPalette): GlanceModifier = this
    .background(p.chip)
    .cornerRadius(999.dp)

/** 正圆：方块 + 超大圆角，Android 会把半径夹到短边一半，正方形即成正圆 */
fun GlanceModifier.widgetDisc(p: WidgetPalette): GlanceModifier = this
    .background(p.accentSoft)
    .cornerRadius(999.dp)

/** 主色族胶囊：**可点条目**的底（R1 交互控件只用主色族）。
 *  与多天总览的范围胶囊同族，文字配 [WidgetPalette.bodyOnPrimary] / [WidgetPalette.labelOnChip]。 */
fun GlanceModifier.widgetPillPrimary(p: WidgetPalette): GlanceModifier = this
    .background(p.primaryPill)
    .cornerRadius(999.dp)