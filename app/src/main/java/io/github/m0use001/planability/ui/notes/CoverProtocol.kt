package io.github.m0use001.planability.ui.notes

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import io.github.m0use001.planability.data.COVER_SOLID

// ==================== 封面编号 / 配色编码 ====================
//
// 这一层是**契约**，同一包里所有封面代码共用（矢量款与位图款都要用）。
// 绘制辅助在 CoverDraw.kt 里，两条线各自独立。

/**
 * 封面编号里同时编码了「款式」和「配色」两个维度。
 *
 * [COVER_PALETTE_STRIDE] = 100，款式编号 0 起、配色 0 起，于是
 *   code = base + palette * 100
 * 例：款式 8（剪纸层叠）第 2 套配色 → 108。
 *
 * 例外只有素色款：它没有 base 位，改用负数码 `-(palette + 1)`（见 [encodeSolid]）。
 *
 * ## 为什么不加一列 coverPalette
 * 加列要动 Room 迁移（30→31）、BackupManager 备份版本、PhoneDump 导出契约三处，
 * 而这三个都是跨端契约；把两个维度压进同一个 Int，这三处一行都不用改，
 * PC 侧拿到的仍然是一个数字。代价只是解码函数，这个文件里三个小函数就够。
 */
const val COVER_PALETTE_STRIDE = 100

/** 自定义文字平铺款（老编号）。带配色：90 / 190 / 290 … */
const val COVER_TEXT = 90

/**
 * 图标封面（单色矢量图标）。编号 **16 必须保持不变**——
 * `NotebookEntity.coverStyle` 里已经存着这个值，改号会让存量笔记本的封面静默变样。
 *
 * 15 号原本是位图（方块/物品贴图）封面，该款已随素材一起删除；
 * 对应存量行由 Room 迁移归一到素色（见 `MIGRATION_57_58`）。
 */
const val COVER_ICON = 16

/**
 * `NotebookEntity.coverHue` 在 v19.34 之后**不再参与渲染**——配色一律走 [CoverPal]。
 * 列本身保留（Room schema、备份 JSON、PhoneDump 导出里都还有它，删列要动迁移 + 备份版本）。
 * 这里只留一个常量，写库时填它，老数据读出来也还是这个值，不做无意义改写。
 */
const val DEFAULT_COVER_HUE = 210f

/**
 * 素色款（COVER_SOLID = -1）用**负数区间**编码配色序号：
 * `-1` = 素色 + 第 0 套，`-2` = 第 1 套，以此类推。
 *
 * ## 为什么不给它也用 `base + palette * 100`
 * 素色没有 base 位可用（它是 -1，不是 0 起编号的款式），而 -1 又是
 * `NotebookEntity.coverStyle` 的默认值、Room 迁移 29→30 写入的目标值、
 * AI 工具建默认笔记本时用的值——也就是说真机上**绝大多数素色行就写着 -1**。
 * 如果把素色挪成正编号、或让 `-1 + palette*100` 走通用公式，这些行要么渲染错、
 * 要么得写一次全表迁移。负数区间编码让 `-1` 原封不动仍然是"素色 + 第 0 套"，零迁移。
 */
private fun encodeSolid(palette: Int) = -(palette + 1).coerceAtLeast(1)

/**
 * ## 这里踩过的坑（两个方向都错过一次）
 * 款式 0 的第 2 套配色编出来是 **100**，正好等于 STRIDE：
 *  - 若用 `code >= STRIDE` 当"特殊款"的判据 → 100 被当成特殊款原样返回，
 *    coverBaseStyle(100) 给出 100 而不是 0 → **换了配色凭空多出一款封面**；
 *  - 若改成先对 base 位判 `>= STRIDE` → 也不行，因为 base 永远小于 STRIDE，判据等于没有。
 * 所以解码只按**符号**分流（负数 / 非负数），判据里不写任何会随款式编号漂移的区间；
 * 编码才对 base 补一道防御性上界，挡住"把乱码当款式号"这种外部输入。
 */
fun encodeCoverStyle(base: Int, palette: Int): Int = when {
    base == COVER_SOLID -> encodeSolid(palette)
    base < 0 || base >= COVER_PALETTE_STRIDE -> base
    else -> base + palette * COVER_PALETTE_STRIDE
}

/** 拆出款式编号（负数一律是素色） */
fun coverBaseStyle(code: Int): Int =
    if (code < 0) COVER_SOLID else code % COVER_PALETTE_STRIDE

/** 拆出配色序号（素色从负数码里还原） */
fun coverPaletteIndex(code: Int): Int =
    if (code < 0) (-code - 1).coerceAtLeast(0) else code / COVER_PALETTE_STRIDE

/**
 * 一套配色。
 *
 * 用具名 map 而不是 data class 的具名字段，是因为不同款式需要的颜色角色差别很大
 * （包豪斯要红黄蓝，莫兰迪要七个灰阶，等高线要五档色阶，位图款要 base/accent 两个角色），
 * 强行统一字段名只会得到一堆"对某些款式毫无意义"的字段。
 *
 * 取不到的颜色返回 [MISSING_PALETTE]（品红）——宁可显眼地画错，也不要静默变成
 * 透明或黑色，那种在真机上要靠肉眼找很久才发现。
 */
class CoverPal(val label: String, private val map: Map<String, Color>) {
    operator fun get(key: String): Color = map[key] ?: MISSING_PALETTE

    fun mix(key: String, toward: Color, t: Float): Color = lerp(this[key], toward, t)

    fun shade(key: String, amount: Float): Color =
        lerp(this[key], Color.Black, amount.coerceIn(0f, 1f))

    fun tint(key: String, amount: Float): Color =
        lerp(this[key], Color.White, amount.coerceIn(0f, 1f))
}

val MISSING_PALETTE = Color(0xFFFF00FF)

/**
 * 每款封面自己的配色方案。
 *
 * 用户 2026-10-14 的要求：
 *  1. **每款按自己的艺术风格单独配色，不共用一套通用配色**——所以下面各有各的颜色角色；
 *  2. **黑金只给 Art Deco**，别的款式不出现黑金；
 *  3. 未点名的款式保持单配色。
 *
 * 每个列表的第 0 项永远是风格说明书里的原色，所以「原色 vs 变体」永远可对照。
 */
private fun C(hex: Long) = Color(hex)

private fun pal(label: String, vararg pairs: Pair<String, Color>) =
    CoverPal(label, pairs.toMap())

object CoverPalettes {

    /**
     * 素色款的配色。只有 `base` 一个角色——上下渐变由它推出来
     * （上端 lerp 白 10%、下端 lerp 黑 10%），所以选色 = 选整张封面。
     *
     * 以前这里没有配色表，素色靠一个「换色」按钮每次 `hue += 47°` 去撞
     * [io.github.m0use001.planability.ui.theme.SeedColors.artPalette] 里的一整条色环，
     * 每按一次换一档、且换不了具体想要的那一档。现在改成具名色板，
     * 和其余款式一样在「配色方案」那一排里选，同一个界面、同一套交互。
     *
     * 第 0 项沿用原来的靛蓝（#5464C7 的近似），保证老笔记本看起来没变。
     */
    private val solidTones = listOf(
        pal("靛蓝", "base" to C(0xFF5464C7)),
        pal("暖阳", "base" to C(0xFFD08A3C)),
        pal("松墨", "base" to C(0xFF3E7A5E)),
        pal("藕荷", "base" to C(0xFFA8648F))
    )

    /**
     * 文字款（自定义文字平铺）的配色。文字是白色半透明压在底色上，
     * 所以这里的 `base` 一律比素色**压深**——底色太亮的话 0.55 alpha 的平铺字直接糊掉。
     */
    private val textTones = listOf(
        pal("藏蓝", "base" to C(0xFF2F3E6B)),
        pal("赭墨", "base" to C(0xFF7A4A2E)),
        pal("墨绿", "base" to C(0xFF2C4F3F)),
        pal("绛紫", "base" to C(0xFF5E2F52))
    )

    /**
     * 图标款（[COVER_ICON]）的配色。只有 `base` 一个角色——
     * 前景色由 [coverIcon] 按底色亮度自动选（深底白图标、浅底深色图标），
     * 所以深浅底都能用。底色只驱动"底"，图标本身不着色。
     */
    private val iconTones = listOf(
        pal("云白", "base" to C(0xFFF1EFE9)),
        pal("雾蓝", "base" to C(0xFFDCE4F5)),
        pal("苔绿", "base" to C(0xFF3E7A5E)),
        pal("墨蓝", "base" to C(0xFF2A3B63))
    )

    private val bauhaus = listOf(
        pal("原色",
            "bg" to C(0xFFF5F2EA), "ink" to C(0xFF0A0A0A),
            "red" to C(0xFFE63946), "yellow" to C(0xFFF4C20D), "blue" to C(0xFF1D4ED8)),
        pal("粉彩包豪斯",
            "bg" to C(0xFFF6F1E9), "ink" to C(0xFF22223B),
            "red" to C(0xFFF28482), "yellow" to C(0xFFFFCE67), "blue" to C(0xFF2A9D8F)),
        pal("夜蓝包豪斯",
            "bg" to C(0xFF1B263B), "ink" to C(0xFFF0EDE4),
            "red" to C(0xFFFF595E), "yellow" to C(0xFFFFCA3A), "blue" to C(0xFF1982C4))
    )

    private val swiss = listOf(
        pal("原版",
            "bg" to C(0xFFFFFFFF), "black" to C(0xFF111111), "accent" to C(0xFF0057B8),
            "grid" to C(0xFFE4E4E4), "body" to C(0xFFC4C4C4), "meta" to C(0xFF8A8A8A)),
        pal("红强调",
            "bg" to C(0xFFFFFFFF), "black" to C(0xFF111111), "accent" to C(0xFFE30613),
            "grid" to C(0xFFE4E4E4), "body" to C(0xFFC4C4C4), "meta" to C(0xFF8A8A8A)),
        pal("夜间反白",
            "bg" to C(0xFF0E0E0E), "black" to C(0xFFF2F2F2), "accent" to C(0xFF00A3FF),
            "grid" to C(0xFF242424), "body" to C(0xFF4A4A4A), "meta" to C(0xFF707070))
    )

    private val memphis = listOf(
        pal("原版",
            "bg" to C(0xFFFFF8F0), "ink" to C(0xFF101010),
            "pink" to C(0xFFFF5FA2), "teal" to C(0xFF00D3C7), "yellow" to C(0xFFFFD447),
            "purple" to C(0xFF7B5CFF), "orange" to C(0xFFFF6B35)),
        pal("糖果孟菲斯",
            "bg" to C(0xFFFFF4F8), "ink" to C(0xFF2B2B2B),
            "pink" to C(0xFFFF8FB1), "teal" to C(0xFF7DE0D3), "yellow" to C(0xFFFFE066),
            "purple" to C(0xFFA98BFF), "orange" to C(0xFFFF8A5B)),
        pal("夜光孟菲斯",
            "bg" to C(0xFF12122B), "ink" to C(0xFFEFEFFA),
            "pink" to C(0xFFFF4F9A), "teal" to C(0xFF00E5C0), "yellow" to C(0xFFFFE14D),
            "purple" to C(0xFF8A5CFF), "orange" to C(0xFFFF7A3D))
    )

    private val constructivism = listOf(
        pal("构成主义红黑",
            "paper" to C(0xFFF2EEE3), "ink" to C(0xFF0C0C0C),
            "red" to C(0xFFD2232A), "yellow" to C(0xFFFFC800), "blue" to C(0xFF1F4FA8)),
        pal("至上主义蓝黄",
            "paper" to C(0xFFE8E4D9), "ink" to C(0xFF141414),
            "red" to C(0xFF1F4FA8), "yellow" to C(0xFFFFC800), "blue" to C(0xFFFFC800)),
        pal("暗底红",
            "paper" to C(0xFF16161A), "ink" to C(0xFFF2F2F2),
            "red" to C(0xFFFF3_B30), "yellow" to C(0xFFFFC800), "blue" to C(0xFF0A84FF))
    )

    private val lowpoly = listOf(
        pal("黄昏",
            "skyTop" to C(0xFFFFC98A), "skyHi" to C(0xFFFFB86B), "skyMid" to C(0xFFE86A5E),
            "skyLow" to C(0xFF6C4E8F), "skyBot" to C(0xFF2E2A55),
            "sun" to C(0xFFFFE0A8), "mtnLit" to C(0xFF8E76C0), "mtnBase" to C(0xFF6E5A9E),
            "mtnDark" to C(0xFF3E3268), "mtnDeep" to C(0xFF52408A), "mtnWarm" to C(0xFFC98FA8),
            "water" to C(0xFF22345C), "waterHi" to C(0xFF2B4676), "waterMid" to C(0xFF1A2547),
            "waterLo" to C(0xFF141C33), "refl" to C(0xFFFFB86B), "reflHi" to C(0xFFFFE0A8)),
        pal("自然青绿",
            "skyTop" to C(0xFFCFEBD2), "skyHi" to C(0xFFA8D8C0), "skyMid" to C(0xFF6FB3A8),
            "skyLow" to C(0xFF3E6B8C), "skyBot" to C(0xFF1E3A52),
            "sun" to C(0xFFFFF3_CC), "mtnLit" to C(0xFF6FA8B8), "mtnBase" to C(0xFF4E7A94),
            "mtnDark" to C(0xFF2E4E68), "mtnDeep" to C(0xFF3E6B8C), "mtnWarm" to C(0xFF8FC0B0),
            "water" to C(0xFF1E3A52), "waterHi" to C(0xFF2A5470), "waterMid" to C(0xFF16303F),
            "waterLo" to C(0xFF10222E), "refl" to C(0xFFA8D8C0), "reflHi" to C(0xFFFFF3_CC)),
        pal("蓝调夜山",
            "skyTop" to C(0xFF3E5C8A), "skyHi" to C(0xFF4A6FA5), "skyMid" to C(0xFF35507D),
            "skyLow" to C(0xFF1E2B4A), "skyBot" to C(0xFF0D1424),
            "sun" to C(0xFFC8D8F0), "mtnLit" to C(0xFF40598C), "mtnBase" to C(0xFF2F4470),
            "mtnDark" to C(0xFF1A2542), "mtnDeep" to C(0xFF22335C), "mtnWarm" to C(0xFF5A78A8),
            "water" to C(0xFF16233C), "waterHi" to C(0xFF1D3054), "waterMid" to C(0xFF131E33),
            "waterLo" to C(0xFF0C1322), "refl" to C(0xFF4A6FA5), "reflHi" to C(0xFFC8D8F0))
    )

    private val contour = listOf(
        pal("墨绿",
            "paper" to C(0xFFF7F5F0), "ink" to C(0xFF1A1A1A), "grid" to C(0xFFDDE4D8),
            "b0" to C(0xFFE8F1E4), "b1" to C(0xFFC3D9BB), "b2" to C(0xFFA8C8A0),
            "b3" to C(0xFF5E8C6A), "b4" to C(0xFF2F5245)),
        pal("单色蓝阶",
            "paper" to C(0xFFF7F5F0), "ink" to C(0xFF1A1A1A), "grid" to C(0xFFDCE4EE),
            "b0" to C(0xFFE4ECF5), "b1" to C(0xFFC0D2E8), "b2" to C(0xFF8FB0D4),
            "b3" to C(0xFF4A6FA5), "b4" to C(0xFF1F4E79)),
        pal("沙漠赭石",
            "paper" to C(0xFFF7F5F0), "ink" to C(0xFF1A1A1A), "grid" to C(0xFFEADCC8),
            "b0" to C(0xFFF7EBDA), "b1" to C(0xFFEBD3B0), "b2" to C(0xFFD6A870),
            "b3" to C(0xFFA8703C), "b4" to C(0xFF6E4222))
    )

    private val papercut = listOf(
        pal("森林绿",
            "b0" to C(0xFFE3F0E1), "b1" to C(0xFFA9D3B0), "b2" to C(0xFF6BAF8D),
            "b3" to C(0xFF3E8E76), "b4" to C(0xFF27614F),
            "frame" to C(0xFFEAF4EA), "frameLine" to C(0xFFB9CFB7),
            "sky" to C(0xFFDCEBDB), "inner" to C(0xFFF4F8F1)),
        pal("暖阳层叠",
            "b0" to C(0xFFFFF3E0), "b1" to C(0xFFFFD9A0), "b2" to C(0xFFF5A85C),
            "b3" to C(0xFFD2703A), "b4" to C(0xFFA8512A),
            "frame" to C(0xFFFFF9F0), "frameLine" to C(0xFFE8C89E),
            "sky" to C(0xFFFBEBD6), "inner" to C(0xFFFFFDF8)),
        pal("雾霭层叠",
            "b0" to C(0xFFEAEEF3), "b1" to C(0xFFC2CEDD), "b2" to C(0xFF94A9BF),
            "b3" to C(0xFF6B8199), "b4" to C(0xFF4A5B70),
            "frame" to C(0xFFF2F5F8), "frameLine" to C(0xFFAEBECE),
            "sky" to C(0xFFDCE4EC), "inner" to C(0xFFF8FAFC))
    )

    private val artdeco = listOf(
        pal("深蓝金",
            "bg" to C(0xFF0B2545), "gold" to C(0xFFC9A227), "goldLight" to C(0xFFE5C158),
            "ivory" to C(0xFFF4EFE4), "ink" to C(0xFF0A0A0A)),
        pal("黑金",
            "bg" to C(0xFF0A0A0A), "gold" to C(0xFFD4AF37), "goldLight" to C(0xFFF0D98C),
            "ivory" to C(0xFFE8E2D4), "ink" to C(0xFF0A0A0A))
    )

    private val morandi = listOf(
        pal("莫兰迪原色",
            "c0" to C(0xFFD3C7B6), "c1" to C(0xFFC9BFB5), "c2" to C(0xFFB2A5A0),
            "c3" to C(0xFFA8A39D), "c4" to C(0xFF8C93A8), "c5" to C(0xFF6E6A63),
            "c6" to C(0xFF4A4A44), "sun" to C(0xFFE4DACC)),
        pal("冷灰晨雾",
            "c0" to C(0xFFD8DCE0), "c1" to C(0xFFC2C8CE), "c2" to C(0xFFAFB8C2),
            "c3" to C(0xFFA3ADB8), "c4" to C(0xFF8693A6), "c5" to C(0xFF646A73),
            "c6" to C(0xFF44474D), "sun" to C(0xFFE9EDF2)),
        pal("暖砂陶土",
            "c0" to C(0xFFE8DCC8), "c1" to C(0xFFDCCBB2), "c2" to C(0xFFC9AE9C),
            "c3" to C(0xFFB9A594), "c4" to C(0xFF9C8A7C), "c5" to C(0xFF6E5F52),
            "c6" to C(0xFF4A4038), "sun" to C(0xFFF5EBD8))
    )

    private val mondrian = listOf(
        pal("三原",
            "canvas" to C(0xFFF4F4F0), "ink" to C(0xFF0A0A0A),
            "red" to C(0xFFD40920), "yellow" to C(0xFFF7D842),
            "blue" to C(0xFF1356A2), "grey" to C(0xFFBFBFB8)),
        pal("蓝黄双原",
            "canvas" to C(0xFFF4F4F0), "ink" to C(0xFF0A0A0A),
            "red" to C(0xFF1356A2), "yellow" to C(0xFFF7D842),
            "blue" to C(0xFFF7D842), "grey" to C(0xFFBFBFB8)),
        pal("大地色系",
            "canvas" to C(0xFFF2EDE4), "ink" to C(0xFF0A0A0A),
            "red" to C(0xFFB4472C), "yellow" to C(0xFFD9A441),
            "blue" to C(0xFF3F6B5C), "grey" to C(0xFFC4B8A4))
    )

    /** 款式编号 -> 配色方案列表（单配色款式返回一项） */
    private val byStyle: Map<Int, List<CoverPal>> = mapOf(
        // 素色 / 文字款都不画图案（走 NotebookCover 的 else / COVER_TEXT 分支），
        // 但仍需要一套合法配色，因为 NotebookCover 会先无条件取一份 pal 再进 when
        COVER_SOLID to solidTones,
        COVER_TEXT to textTones,
        // v19.47：图标款（16）原先**漏了这一行**——byStyle 查不到 16，
        // `of(16)` 返回空表。后果不是画错（NotebookCover 的 getOrElse 会兜底成
        // 素色第 0 套的底色，所以看起来"能用"），而是**图标款根本没有配色方案**：
        // 编辑器里那一排配色芯片是空的，用户没法换底色（CoverStyleCoverageTest /
        // CoverPaletteCodingTest 各有一条一直因此红着）。
        COVER_ICON to iconTones,
        0 to bauhaus, 1 to swiss, 2 to memphis, 3 to constructivism,
        5 to lowpoly, 7 to contour, 8 to papercut, 9 to artdeco,
        11 to morandi, 14 to mondrian,
        // 单配色款式（用户 2026-10-14 点名之外的保留原样）
        4 to listOf(pal("原版",
            "black" to C(0xFF000000), "white" to C(0xFFFFFFFF), "accent" to C(0xFFFF2D2D))),
        6 to listOf(pal("原版",
            "bg" to C(0xFF0B3C75), "line" to C(0xFFEAF4FF), "aux" to C(0xFF7FB6E8),
            "auxStrong" to C(0xFFA8D0F0), "accent" to C(0xFFFFB400))),
        10 to listOf(pal("原版",
            "skyTop" to C(0xFF1B2F6E), "skyBot" to C(0xFF0A1030), "sphere" to C(0xFF0B1533),
            "line" to C(0xFF7FB3FF), "star" to C(0xFFF5E9C8), "milky" to C(0xFF9FC0FF),
            "moon" to C(0xFF101B44))),
        12 to listOf(pal("原版",
            "bg" to C(0xFF0D0D12), "red" to C(0xFFFF0033), "cyan" to C(0xFF00FFF0),
            "blue" to C(0xFF0033FF), "white" to C(0xFFFFFFFF), "grey" to C(0xFF8A8A94))),
        13 to listOf(pal("原版",
            "xuan" to C(0xFFF5EFE0), "ink" to C(0xFF1C1C1C), "red" to C(0xFFC8161D),
            "gold" to C(0xFFD4AF37), "inner" to C(0xFFEFE7D6)))
    )

    fun of(style: Int): List<CoverPal> = byStyle[style].orEmpty()

    fun countOf(style: Int): Int = maxOf(1, of(style).size)

    /** 配色序号越界时收敛到合法范围（库里可能有被手改过的编号） */
    fun clampPalette(style: Int, index: Int): Int =
        if (index in of(style).indices) index else 0

    fun labelOf(style: Int, index: Int): String = of(style).getOrNull(index)?.label ?: ""
}