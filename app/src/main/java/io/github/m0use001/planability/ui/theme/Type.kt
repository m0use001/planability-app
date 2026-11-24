package io.github.m0use001.planability.ui.theme

import android.content.Context
import android.graphics.Typeface
import android.graphics.fonts.Font as PlatformFont
import android.graphics.fonts.FontFamily as PlatformFontFamily
import androidx.compose.material3.Typography
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.AndroidFont
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontLoadingStrategy
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import io.github.m0use001.planability.R
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** 可变字重字体（res/font，TTF 变量字体由系统按 wght 实例化） */
object AppFonts {
    val notoSc = FontFamily(Font(R.font.notosanssc))

    /**
     * Google Sans Flex —— M3 Expressive 的官方展示字体（google/fonts `ofl/googlesansflex`，SIL OFL）。
     * 子集化见 tools/fonts/subset_googlesansflex.py：只留拉丁+UI 符号，只保 wght/ROND/opsz 三轴
     * （3.73MB → 484KB）。中文逐字回退系统 CJK，与 Poppins 等拉丁字体同一行为。
     *
     * 可变轴设在 **Font 条目**上（TextStyle 没有 variationSettings 参数）：
     *  - 标题族 [googleSansFlexHeading]：ROND=100（终端全圆，M3E 的表情）+ opsz=28（大字收紧字怀）
     *  - 正文族 [googleSansFlexText]：ROND=0 + opsz=16（小字保持易读，不玩具化）
     * 每个族按 300..800 六档显式声明 wght（官方推荐的可变字体写法；字重偏移设置因此按百档吸附）。
     */
    private fun gsFlex(roundness: Int, opticalSize: Int): FontFamily = FontFamily(
        listOf(300, 400, 500, 600, 700, 800).map { w ->
            Font(
                R.font.googlesansflex,
                FontWeight(w),
                variationSettings = FontVariation.Settings(
                    FontVariation.Setting("wght", w.toFloat()),
                    FontVariation.Setting("ROND", roundness.toFloat()),
                    FontVariation.Setting("opsz", opticalSize.toFloat())
                )
            )
        }
    )
    val googleSansFlexHeading: FontFamily = gsFlex(roundness = 100, opticalSize = 28)
    val googleSansFlexText: FontFamily = gsFlex(roundness = 0, opticalSize = 16)

    // ---------- M3E 圆体中文字体：只接管中文，拉丁/符号仍走 Google Sans Flex ----------
    //
    // Compose 的 FontFamily 缺字时只回退**系统**字体，不会回退到另一个自定义族；所以「拉丁用
    // Google Sans、中文用圆体」不能靠两个族拼，而是要造一个自带回退链的 Typeface：
    // 主字体 = Google Sans Flex（无 CJK 字形），自定义回退 = 中文圆体。
    // Typeface.CustomFallbackBuilder（API 29+）：先按主字体取字，缺字才落到圆体，
    // 因此中文=圆体、拉丁/数字/符号=Google Sans，生僻字再落到系统 CJK。
    private const val GS_FLEX_RES = R.font.googlesansflex

    /**
     * 已构建过的字体对象（key = 资源 + 变体轴串）。Compose 侧按 Font 条目还会再缓存一层，
     * 这里缓存是为了同一字重被多个样式复用时不再读盘/重建。
     */
    private val fileCache = ConcurrentHashMap<Int, File>()
    private val fontCache = ConcurrentHashMap<String, PlatformFont>()

    /** res/font 在 APK 里是 DEFLATED，openRawResourceFd 不可用；先落到 cacheDir 再用 File 构建。
     *  多个字重会在不同线程并行加载，所以写临时文件再原子改名，避免读到写了一半的字体。 */
    private fun fontFile(ctx: Context, resId: Int): File = fileCache.getOrPut(resId) {
        val file = File(ctx.cacheDir, "pf_$resId.font")
        if (!file.exists() || file.length() == 0L) {
            val tmp = File(ctx.cacheDir, "pf_$resId.tmp")
            ctx.resources.openRawResource(resId).use { input ->
                tmp.outputStream().use { output -> input.copyTo(output) }
            }
            if (!tmp.renameTo(file)) {
                tmp.copyTo(file, overwrite = true)
                tmp.delete()
            }
        }
        file
    }

    /** 平台 Font（CustomFallbackBuilder 与它要的 FontFamily 都只吃平台 Font） */
    private fun platformFont(ctx: Context, resId: Int, variations: String?): PlatformFont =
        fontCache.getOrPut("$resId|$variations") {
            PlatformFont.Builder(fontFile(ctx, resId)).apply {
                if (variations != null) setFontVariationSettings(variations)
            }.build()
        }

    private object CjkOverlayLoader : AndroidFont.TypefaceLoader {
        override fun loadBlocking(context: Context, font: AndroidFont): Typeface? =
            (font as? CjkOverlayFont)?.buildTypeface(context)

        override suspend fun awaitLoad(context: Context, font: AndroidFont): Typeface? =
            loadBlocking(context, font)
    }

    /**
     * 一带中文回退的字体条目：[cjkRes] 由字重决定（静态字重字体按档挑文件），
     * [cjkRoundness] 非空时给中文圆体加 ROND 轴（资源圆体：标题 100 / 正文 0）。
     */
    @OptIn(ExperimentalTextApi::class)
    private class CjkOverlayFont(
        override val weight: FontWeight,
        private val heading: Boolean,
        private val cjkRes: (Int) -> Int,
        private val cjkVariable: Boolean = true,
        private val cjkRoundness: Int? = null,
    ) : AndroidFont(FontLoadingStrategy.Blocking, CjkOverlayLoader, FontVariation.Settings()) {

        override val style: FontStyle = FontStyle.Normal

        fun buildTypeface(ctx: Context): Typeface? {
            val w = weight.weight
            return try {
                val gsVariations = "'wght' $w, 'ROND' ${if (heading) 100 else 0}, 'opsz' ${if (heading) 28 else 16}"
                val primary = platformFont(ctx, GS_FLEX_RES, gsVariations)
                val cjkVariations = if (!cjkVariable) null else buildString {
                    append("'wght' ${w.coerceIn(300, 800)}")
                    if (cjkRoundness != null) append(", 'ROND' $cjkRoundness")
                }
                val fallback = platformFont(ctx, cjkRes(w), cjkVariations)
                Typeface.CustomFallbackBuilder(PlatformFontFamily.Builder(primary).build())
                    .addCustomFallback(PlatformFontFamily.Builder(fallback).build())
                    .build()
            } catch (t: Throwable) {
                android.util.Log.e("FontProbe", "composite failed w=$w heading=$heading cjk=${cjkRes(w)} var=$cjkVariable: $t", t)
                null
            }
        }
    }

    private fun cjkOverlay(
        heading: Boolean,
        cjkVariable: Boolean = true,
        cjkRoundness: Int? = null,
        cjkRes: (Int) -> Int,
    ): FontFamily = FontFamily(
        listOf(300, 400, 500, 600, 700, 800).map { w ->
            CjkOverlayFont(FontWeight(w), heading, cjkRes, cjkVariable, cjkRoundness)
        }
    )

    /** 文渊圆体 SC：glyf 可变字体 wght 100-900，GB2312 子集 9.7MB */
    private fun wenYuan(heading: Boolean) = cjkOverlay(heading) { R.font.wenyuanrounded }
    /** 975 圆体 SC：静态 400/700 两档，按字重挑文件，GB2312 子集共 5.9MB */
    private fun yuan975(heading: Boolean) = cjkOverlay(
        heading, cjkVariable = false
    ) { w -> if (w >= 600) R.font.yuan975_700 else R.font.yuan975_400 }
    /** 资源圆体：CFF2 可变字体，轴 wght 200-900 + ROND 0-100，子集 14MB */
    private fun rhr(heading: Boolean) = cjkOverlay(
        heading, cjkRoundness = if (heading) 100 else 0
    ) { R.font.rhr }

    private val families: Map<String, Pair<FontFamily, FontFamily>> = mapOf(
        "wen_yuan_rounded" to (wenYuan(heading = true) to wenYuan(heading = false)),
        "yuan_975" to (yuan975(heading = true) to yuan975(heading = false)),
        "rhr" to (rhr(heading = true) to rhr(heading = false)),
    )

    fun resolve(key: String): FontFamily? = when (key) {
        "google_sans_flex" -> googleSansFlexText
        "noto_sc" -> notoSc
        "wen_yuan_rounded" -> families[key]?.second
        "yuan_975" -> families[key]?.second
        "rhr" -> families[key]?.second
        else -> null
    }

    /** 标题层族（圆度开满）；没有专门标题族的字体返回 null，调用方沿用正文族 */
    fun heading(key: String): FontFamily? = families[key]?.first

    /**
     * 给**拿不到 Compose FontFamily** 的场景（桌面小组件用 Canvas 画字、预览图生成）
     * 构一个平台 Typeface，规则与 [typography] 完全同源：同一个字体资源、同一组可变轴
     * （wght / ROND / opsz）、同一条「拉丁走 Google Sans Flex、中文走圆体」的回退链。
     *
     * 为什么需要它：Glance 1.1.1 的 `TextStyle.fontFamily` 在 appwidget 翻译层**没有被消费**
     * （1.2.0 才用 `TypefaceSpan(String)`，且只认系统字体族），所以组件里的 Text 永远是
     * 系统字体；要让桌面组件吃到应用字体，只能自己画。
     *
     * 任何失败都回落 `Typeface.DEFAULT`，绝不因为字体问题让组件空白。
     */
    fun typeface(ctx: Context, familyKey: String, weight: Int, heading: Boolean = false): Typeface {
        val w = weight.coerceIn(300, 800)
        return runCatching {
            fun tf(f: PlatformFont): Typeface =
                Typeface.CustomFallbackBuilder(PlatformFontFamily.Builder(f).build()).build()

            fun tf(primary: PlatformFont, fallback: PlatformFont): Typeface =
                Typeface.CustomFallbackBuilder(PlatformFontFamily.Builder(primary).build())
                    .addCustomFallback(PlatformFontFamily.Builder(fallback).build())
                    .build()

            val gs = platformFont(
                ctx, GS_FLEX_RES,
                "'wght' $w, 'ROND' ${if (heading) 100 else 0}, 'opsz' ${if (heading) 28 else 16}"
            )
            when (familyKey) {
                "noto_sc" -> tf(platformFont(ctx, R.font.notosanssc, null))
                "wen_yuan_rounded" -> tf(gs, platformFont(ctx, R.font.wenyuanrounded, "'wght' $w"))
                "yuan_975" -> tf(
                    gs, platformFont(ctx, if (w >= 600) R.font.yuan975_700 else R.font.yuan975_400, null)
                )
                "rhr" -> tf(
                    gs, platformFont(ctx, R.font.rhr, "'wght' $w, 'ROND' ${if (heading) 100 else 0}")
                )
                "google_sans_flex" -> tf(gs)
                else -> Typeface.DEFAULT
            }
        }.getOrElse {
            android.util.Log.w("AppFonts", "typeface(key=$familyKey,w=$weight) 失败，回落系统字体: $it")
            Typeface.DEFAULT
        }
    }
}

/** 字号缩放 + 字重偏移：所有样式统一处理 */
fun typography(fontScale: Float, fontFamilyKey: String = "default", weightOffset: Int = 0): Typography {
    val family = AppFonts.resolve(fontFamilyKey) ?: FontFamily.Default
    // M3E 的字体表情靠可变轴（见 AppFonts.gsFlex / cjkOverlay）——选带圆度轴的字体时，标题层与
    // 正文层用**两个族**：标题圆度开满（圆终端、紧字怀），正文圆度归零（小字保持易读）。
    // 其它字体两族都等于同一个 family，行为完全不变。
    val bodyFamily = if (fontFamilyKey == "google_sans_flex") AppFonts.googleSansFlexText else family
    val headingFamily = AppFonts.heading(fontFamilyKey)
        ?: if (fontFamilyKey == "google_sans_flex") AppFonts.googleSansFlexHeading else bodyFamily
    fun style(
        size: Int,
        weight: FontWeight = FontWeight.Normal,
        line: Int = size + 6,
        spacing: Double = 0.0,
        heading: Boolean = false
    ) = TextStyle(
        fontFamily = if (heading) headingFamily else bodyFamily,
        fontWeight = shiftWeight(weight, weightOffset),
        fontSize = (size * fontScale).sp,
        lineHeight = (line * fontScale).sp,
        letterSpacing = (spacing * fontScale).sp
    )
    return Typography(
        displayLarge = style(52, FontWeight.Bold, heading = true),
        displayMedium = style(42, FontWeight.Bold, heading = true),
        displaySmall = style(34, FontWeight.Bold, heading = true),
        headlineLarge = style(30, FontWeight.Bold, heading = true),
        headlineMedium = style(26, FontWeight.Bold, heading = true),
        headlineSmall = style(22, FontWeight.Bold, heading = true),
        titleLarge = style(20, FontWeight.SemiBold, heading = true),
        titleMedium = style(16, FontWeight.SemiBold, 22, 0.1, heading = true),
        titleSmall = style(14, FontWeight.SemiBold, 20, 0.1, heading = true),
        bodyLarge = style(16, FontWeight.Normal, 24, 0.3),
        bodyMedium = style(14, FontWeight.Normal, 20, 0.25),
        bodySmall = style(12, FontWeight.Normal, 16, 0.2),
        labelLarge = style(14, FontWeight.Medium, 20, 0.1),
        labelMedium = style(12, FontWeight.Medium, 16, 0.4),
        labelSmall = style(11, FontWeight.Medium, 16, 0.4)
    )
}

/** 在基础字重上叠加偏移，钳到合法区间 */
fun shiftWeight(base: FontWeight, offset: Int): FontWeight {
    if (offset == 0) return base
    val target = (base.weight + offset).coerceIn(100, 900)
    return FontWeight(target)
}
