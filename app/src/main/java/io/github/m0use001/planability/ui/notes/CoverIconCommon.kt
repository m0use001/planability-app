package io.github.m0use001.planability.ui.notes

import android.graphics.BitmapFactory
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import io.github.m0use001.planability.data.Graph
import kotlin.math.cos
import kotlin.math.sin

internal fun nearby(base: Int, delta: Int): Int {
    // Material 图标是**独立的一套**（下标 >= MaterialIconSet.offset），陪衬图也必须留在它这一套里。
    // 否则在 Material 封面选个复杂排列，陪衬位就直接从 MC 素材表里取图 ——
    // 用户看到的就是「◆素材混进了图标封面」。
    if (MaterialIconSet.isMaterial(base)) {
        val off = MaterialIconSet.offset
        val n = MaterialIconSet.COUNT
        if (n <= 0) return off
        val e = (((base - off) % n + delta) % n + n) % n
        return off + e
    }
    // MC 池语义在封面素材包（可选素材包）；公开版没有 MC 池，非 Material 下标原样返回
    return io.github.m0use001.planability.core.CoverArtPack.nearbyMc?.invoke(base, delta) ?: base
}

/**
 * 解析 [NotebookEntity.coverIcons]（CSV）。空串/脏数据一律当作「一个都没指定」。
 */
internal fun parseIcons(s: String): List<Int> =
    if (s.isBlank()) emptyList() else s.split(',').mapNotNull { it.trim().toIntOrNull() }

/**
 * 取第 [i] 个槽位的图标：CSV 里存了非负值就用它，否则（没存、或存的是 -1 占位）退回 [nearby]。
 *
 * CSV 空串 = 用户一个位置都没指定，于是每个槽位都走 nearby，结果与改造前逐位相同。
 */
internal fun slotOf(csv: List<Int>, i: Int, base: Int, delta: Int): Int =
    if (i < csv.size && csv[i] >= 0) csv[i] else nearby(base, delta)

/**
 * 把第 [i] 个槽位写成 [value]，返回新的 CSV。
 *
 * 长度不够时用 -1 补齐——**不能补 0**：0 是合法下标（第一个图标），
 * 补 0 会把「未指定」悄悄变成「指定了 Abc」。渲染侧把负数当作未指定。
 */

fun writeSlot(csv: String, i: Int, value: Int): String {
    val list = csv.split(',').mapNotNull { it.trim().toIntOrNull() }.toMutableList()
    while (list.size <= i) list.add(-1)
    list[i] = value
    return list.joinToString(",")
}

/**
 * 读第 [i] 个槽位的图标（UI 预览用）。未指定返回 -1，调用方自行决定显示什么。
 */

fun readSlot(csv: String, i: Int): Int =
    csv.split(',').mapNotNull { it.trim().toIntOrNull() }.getOrElse(i) { -1 }

/**
 * 一个图标下标给人看的名字：Material 用 PascalCase 原名，◆ 用原版条目名。
 *
 * MC 条目名可能带 mod 前缀（`create/asurine_natural_3`），展示时只留最后一段。
 */

internal fun iconLabel(icon: Int, baseStyle: Int): String = when {
    MaterialIconSet.isMaterial(icon) ->
        MaterialIconSet.NAMES.getOrElse(MaterialIconSet.entryOf(icon)) { "?" }

    else ->
        // MC 池条目名来自封面素材包（可选素材包）；公开版返回 "?"（该下标不该出现）
        io.github.m0use001.planability.core.CoverArtPack.iconNameOf(icon).ifBlank { "?" }
}

/**
 * v19.44：把图标下标迁到 [baseStyle] 对应的那个素材池。
 *
 * 两套素材**共用 `coverIcon` 一个 Int**，区间不同：MC 在 `[0, McIcons.COUNT*2)`、
 * Material 在 `[offset, offset+COUNT)`（见 [MaterialIconSet] 的区间注释）。
 * 所以换款式必须迁移，否则：
 *  - ICON → MC：拿着一个 Material 下标，绘制时被折回 MC 区间 —— 用户看到「图标自己变了」；
 *  - MC → ICON：`isMaterial` 为 false，绘制回退到 `MaterialIconSet.offset`（封面看着像默认值），
 *    而**选择器锁定在 Material** 却拿着一个 MC 下标 ——> 选完存下去又被绘制回退，
 *    表现就是**「选了半天没反应」**（用户 10-16 报的就是这一条）。
 *
 * 迁移策略是「取该池的第一张」而不是按比例换算：两个池的条目毫无对应关系
 * （一边是 ◆ 贴图、一边是 Material 矢量图标），换算只会得到一个随机图标，
 * 那比「回到第一张」更让人困惑。
 */

internal fun coerceIconToStyle(icon: Int, baseStyle: Int): Int {
    val isMat = MaterialIconSet.isMaterial(icon)
    return when {
        baseStyle == COVER_ICON && !isMat -> MaterialIconSet.offset
        baseStyle != COVER_ICON && isMat -> 0
        else -> icon
    }
}

object McLayouts {
    /** 居中单图：完整不裁，靠尺寸撑存在感。最保守的一档。 */
    const val CENTER_ONE = 0

    /** 居中大图出血：图标大到四边都探出封面，只剩中间一截。出血最直白的形态。 */
    const val CENTER_BLEED = 1

    /** 四角各一：四张图各越过一条边。 */
    const val CORNER = 2

    /** 底部一排：沿底边排开，靠近两端的两张探出下/侧边。 */
    const val BOTTOM_ROW = 3

    /** 对角散落：逐个不同尺寸、带轻微旋转，交替越过左上/右下。有「随手贴」的生气。 */
    const val DIAGONAL = 4

    /** 对角线重复：同一条斜带上重复同一张图标，斜切出界。 */
    const val DIAG_TILE = 5

    /** 错位网格：每行左右错半格，行首行尾必然出血。 */
    const val STAGGER_GRID = 6

    /** 环绕一圈：贴边排一圈，中心留空。 */
    const val RING = 7

    /** 左下角·探出：图标中心落在角外，左/下两边都有一部分在封面之外。 */
    const val BOTTOM_LEFT_BLEED = 8

    /** 右下角·探出：同上，改往右下探。 */
    const val BOTTOM_RIGHT_BLEED = 9

    /**
     * 左下角，完整：图标**完整在封面内**，且与**左边缘和下边缘相切**。
     * 与出血档的区别只在这一点——出血档会有一部分在封面外面。
     */
    const val BOTTOM_LEFT_FIT = 10

    /** 右下角，完整：完整显示并与右边缘、下边缘相切。 */
    const val BOTTOM_RIGHT_FIT = 11

    const val COUNT = 12

    /**
     * 选择器里**实际展示**的排列下标。
     *
     * 「底部一排」(3)、「对角平铺」(5)、「环绕」(7) 按用户要求下架 ——
     * 但**只从选择器移除，不改常量、不复用序号**：`coverLayout` 是存进数据库的，
     * 存量笔记本里可能正是 3/5/7，`drawCoverMc` 的分支必须继续能画，
     * 否则那些本子的排版会静默变成别的样式。
     */
    val VISIBLE: IntArray = intArrayOf(
        CENTER_ONE, CENTER_BLEED, CORNER, DIAGONAL, STAGGER_GRID,
        BOTTOM_LEFT_BLEED, BOTTOM_RIGHT_BLEED, BOTTOM_LEFT_FIT, BOTTOM_RIGHT_FIT
    )

    /**
     * 每种排列有几个「可单独指定图标」的位置。
     *
     * 单图排列（居中/满铺/四个角部档）都是 1 个位置——那一个位置就是 `coverIcon`，
     * 管理界面上不必再列一排格子。多图标的才需要逐个挑。
     */
    fun slotCount(layout: Int): Int = when (layout) {
        CORNER -> 4
        BOTTOM_ROW -> 5
        DIAGONAL -> 4
        STAGGER_GRID -> 12
        RING -> 7
        // 对角平铺是「同一条斜带重复同一张」，只占一个位置
        else -> 1
    }

    /**
     * 每个槽位在封面上的**方位名**，下标与绘制顺序一一对应。
     *
     * 单说「位置 2」用户不知道指哪个角——四角档直接给「左上/右上/左下/右下」，
     * 对角散落按视觉落点给名，错位网格按行列给，环绕按顺时针方位给。
     * 单图档只有一个位置，叫「主体」。
     */
    fun slotNames(layout: Int): List<String> = when (layout) {
        CORNER -> listOf("左上", "右上", "左下", "右下")
        // 对角散落：从左上那张大的开始，沿对角线到右下那张大的
        DIAGONAL -> listOf("左上", "中部", "左下", "右下")
        // 底部一排：从左到右
        BOTTOM_ROW -> listOf("左1", "左2", "左3", "左4", "左5")
        // 错位网格：3 列 × 4 行，奇数行整体右移半格
        STAGGER_GRID -> List(12) { k -> "第${k / 3 + 1}行${listOf("左", "中", "右")[k % 3]}" }
        // 环绕：从正上方开始顺时针
        RING -> listOf("顶部", "右上", "右下", "底部", "左下", "左上", "中左")
        else -> listOf("主体")
    }

    /** 选择器/文案用的中文名（下标与常量一一对应，含已下架的三项） */
    val LABELS = arrayOf(
        "居中", "满铺", "四角", "底部一排",
        "对角散落", "对角平铺", "错位网格", "环绕",
        // 两种角部摆放，各自有「探出封面」与「完整贴边」两档。
        // 「出血」是渲染术语，读起来不像人话，这里一律用「探出 / 贴边」。
        "左下角·探出", "右下角·探出", "左下角·贴边", "右下角·贴边",
    )
}

fun DrawScope.drawCoverIcon(p: CoverPal, icon: Int, layout: Int, icons: String = "") {
    // 多图标排列里每个位置各自的图标；空 = 没指定，逐位退回 nearby()
    val csv = parseIcons(icons)
    val base = p["base"]
    // 影子色由底色压暗得到（同 mcIcon 口径）：换色器只驱动底色这一个维度
    val shadow = androidx.compose.ui.graphics.lerp(base, androidx.compose.ui.graphics.Color.Black, 0.38f)

    // 底：与纯色款同一条三段竖向渐变
    fillGradVStops(
        listOf(
            0.0f to androidx.compose.ui.graphics.lerp(base, androidx.compose.ui.graphics.Color.White, 0.10f),
            0.5f to base,
            1.0f to androidx.compose.ui.graphics.lerp(base, androidx.compose.ui.graphics.Color.Black, 0.10f),
        ),
        0f, 0f, 1000f, 1120f
    )

    /** 单个图标：Material 下标走本地矢量；MC 下标走素材包槽位（未注册=不画）。 */
    fun one(
        index: Int, cell: Float, cx: Float, cy: Float,
        sdx: Float = 0f, sdy: Float = 0f, alpha: Float = 1f, rot: Float = 0f,
    ) {
        if (MaterialIconSet.isMaterial(index)) {
            // 前景色按底色亮度自动选黑白（同 mcIcon 的 Material 分支）
            val lum = base.red * 0.299f + base.green * 0.587f + base.blue * 0.114f
            val fg = if (lum < 0.55f) androidx.compose.ui.graphics.Color.White
            else androidx.compose.ui.graphics.Color(0xFF141416)
            MaterialIconSet.run {
                drawMaterialIcon(index, cell, cx, cy, tint = fg, alpha = alpha)
            }
            return
        }
        if (rot != 0f) {
            rotate(rot) {
                io.github.m0use001.planability.core.CoverArtPack.drawMcIcon
                    ?.invoke(this@drawCoverIcon, index, cell, cx, cy, base, shadow, sdx, sdy, alpha)
            }
        } else {
            io.github.m0use001.planability.core.CoverArtPack.drawMcIcon
                ?.invoke(this@drawCoverIcon, index, cell, cx, cy, base, shadow, sdx, sdy, alpha)
        }
    }

    when (layout) {
        McLayouts.CENTER_ONE -> one(icon, 420f, 500f, 545f, 18f, 18f)
        McLayouts.CENTER_BLEED -> one(icon, 1150f, 500f, 545f, 34f, 34f)
        McLayouts.CORNER -> {
            one(slotOf(csv, 0, icon, 0), 300f, 150f, 210f, 14f, 14f)
            one(slotOf(csv, 1, icon, 137), 300f, 850f, 210f, 14f, 14f)
            one(slotOf(csv, 2, icon, 419), 300f, 150f, 880f, 14f, 14f)
            one(slotOf(csv, 3, icon, 733), 300f, 850f, 880f, 14f, 14f)
        }
        McLayouts.BOTTOM_ROW -> {
            val n = 5
            for (i in 0 until n) {
                val cx = 500f + (i - (n - 1) / 2f) * 300f
                one(slotOf(csv, i, icon, i * 211), 260f, cx, 1010f, 12f, 12f)
            }
        }
        McLayouts.DIAGONAL -> {
            one(slotOf(csv, 0, icon, 0), 340f, 230f, 260f, 14f, 14f, 1f, -12f)
            one(slotOf(csv, 1, icon, 271), 260f, 560f, 480f, 10f, 10f, 0.92f, 9f)
            one(slotOf(csv, 2, icon, 617), 300f, 300f, 730f, 12f, 12f, 0.96f, -7f)
            one(slotOf(csv, 3, icon, 881), 380f, 760f, 930f, 16f, 16f, 1f, 14f)
        }
        McLayouts.DIAG_TILE -> {
            for (i in -1..4) {
                val d = -100f + i * 300f
                one(slotOf(csv, 0, icon, 0), 320f, d, d * 1.12f, 14f, 14f, 0.95f)
            }
        }
        McLayouts.STAGGER_GRID -> {
            val cols = 3
            val rows = 4
            for (r in 0 until rows) {
                val off = if (r % 2 == 0) 0f else 165f
                for (c in 0 until cols) {
                    val k = r * cols + c
                    one(slotOf(csv, k, icon, k), 250f, -60f + off + c * 330f, 170f + r * 280f, 10f, 10f, 0.90f)
                }
            }
        }
        McLayouts.RING -> {
            val n = 7
            for (i in 0 until n) {
                val a = (i.toFloat() / n) * 2f * 3.1415927f - 1.5708f
                one(slotOf(csv, i, icon, i * 149), 230f, 500f + 380f * kotlin.math.cos(a), 560f + 430f * kotlin.math.sin(a), 10f, 10f, 0.95f)
            }
        }
        McLayouts.BOTTOM_LEFT_BLEED -> {
            val c = 780f
            one(icon, c, c / 2f, 1120f - c / 2f, 24f, 24f)
        }
        McLayouts.BOTTOM_LEFT_FIT -> {
            val c = 460f
            one(icon, c, c / 2f, 1120f - c / 2f, 18f, 18f)
        }
        McLayouts.BOTTOM_RIGHT_FIT -> {
            val c = 460f
            one(icon, c, 1000f - c / 2f, 1120f - c / 2f, 18f, 18f)
        }
        // 未知排版退回最保守的一档，别画成空白
        else -> one(icon, 420f, 500f, 545f, 18f, 18f)
    }
}
