package io.github.m0use001.planability.ui.notes

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.lerp
import kotlin.math.cos
import kotlin.math.sin

// ==================== COVER_ICON：单色矢量图标封面 ====================

/**
 * 图标封面的款式编号。
 *
 * 换色只驱动「底」这一个维度：底色取 [CoverPal] 的 `"base"`，图标本身不着色——
 * 它是单色矢量，前景按底色亮度自动取黑白（见 [coverIcon]），
 * 深底浅底都能读。想让图标从底上浮起来靠的是底色本身的渐变，不加投影。
 *
 * ## 摆放方式共 12 种（[CoverLayouts]），且**允许伸出裁切范围**
 * 不套用自适应图标那套「缩到安全区内」的规矩：设计上就要能探出边缘。
 * 实现上**故意不做任何 clamp**，让绘制直接超出封面边界，由 `NotebookCover` 外层的
 * `clipPath(coverShape)` 统一裁掉——所以每一处越界都是**有意设计的出血**，不是溢出 bug。
 * 改这块时别「顺手修」那些看起来跑出边界的坐标。
 *
 * ## 坐标一律走设计空间 1000×1120，换算只在 [coverIcon] 内部做一次
 * 踩过的坑：曾经把已换算成像素的 Offset 又传进来再换算一次，
 * 等于把像素值再乘一遍 `size.width/1000`，图标整体被推出格子外。
 * 这里不要引入第二处换算。
 */

/**
 * 一个图标下标给人看的名字（选择器与「各位置图标」chip 用）。
 *
 * 下标越界时退回 `entryOf` 的收敛结果——库里可能存在被手改过、或旧版本写下的值，
 * 这里宁可显示第一个图标的名字，也不要抛异常把整页编辑器打掉。
 */
internal fun iconLabel(icon: Int, @Suppress("UNUSED_PARAMETER") baseStyle: Int): String =
    MaterialIconSet.NAMES.getOrElse(MaterialIconSet.entryOf(icon)) { "?" }

/**
 * 把图标下标收敛到合法的绘图区间。
 *
 * 负数与越界值都退回第一个图标：库里可能有被手改过的行，或早期版本写下的编号。
 * 保留这个函数而不是就地 `coerceIn`，是因为它同时是「打开编辑器时归一存量数据」的
 * 唯一入口——所见（chip 名字）与所画（封面）必须出自同一次归一。
 */
internal fun coerceIconToStyle(icon: Int, @Suppress("UNUSED_PARAMETER") baseStyle: Int): Int {
    val n = MaterialIconSet.COUNT
    if (n <= 0) return 0
    return icon.coerceIn(0, n - 1)
}

/**
 * 邻近取图：把 [base] 按 [delta] 在图标表里循环展开。
 *
 * 摆放方式里很多地方要放「几张不同的图」而不是同一张重复，用固定偏移量在
 * 图标表里循环取，既保证确定性（同一本笔记本每次打开排版一致，不会在重组时乱跳），
 * 又保证相邻取到的不是同一张。
 */
internal fun nearby(base: Int, delta: Int): Int {
    val off = MaterialIconSet.offset
    val n = MaterialIconSet.COUNT
    if (n <= 0) return off
    val e = (((base - off) % n + delta) % n + n) % n
    return off + e
}

/**
 * 解析 `NotebookEntity.coverIcons`（CSV）。空串/脏数据一律当作「一个都没指定」。
 */
private fun parseIcons(s: String): List<Int> =
    if (s.isBlank()) emptyList() else s.split(',').mapNotNull { it.trim().toIntOrNull() }

/**
 * 取第 [i] 个槽位的图标：CSV 里存了非负值就用它，否则（没存、或存的是 -1 占位）退回 [nearby]。
 *
 * CSV 空串 = 用户一个位置都没指定，于是每个槽位都走 nearby，结果与逐位指定前相同。
 */
private fun slotOf(csv: List<Int>, i: Int, base: Int, delta: Int): Int =
    if (i < csv.size && csv[i] >= 0) csv[i] else nearby(base, delta)

/**
 * 把第 [i] 个槽位写成 [value]，返回新的 CSV。
 *
 * 长度不够时用 -1 补齐——**不能补 0**：0 是合法下标（第一个图标），
 * 补 0 会把「未指定」悄悄变成「指定了第一个图标」。渲染侧把负数当作未指定。
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

// ==================== 摆放方式 ====================

/**
 * 12 种摆放方式。
 *
 * 序号写进 `NotebookEntity.coverLayout`，**只追加不改含义**——改动会让存量笔记本的
 * 排版静默变样。删某个排版时把新序号补在末尾，别复用中间的空位。
 */
object CoverLayouts {
    /** 居中单图：完整不裁，靠尺寸撑存在感。最保守的一档。 */
    const val CENTER_ONE = 0

    /** 居中大图出血：图标大到四边都探出封面，只剩中间一截。出血最直白的形态。 */
    const val CENTER_BLEED = 1

    /** 四角各一：四张图各越过一条边。 */
    const val CORNER = 2

    /** 底部一排：沿底边排开，靠近两端的两张探出下/侧边。 */
    const val BOTTOM_ROW = 3

    /** 对角散落：逐个不同尺寸，交替越过左上/右下。有「随手贴」的生气。 */
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
     * 存量笔记本里可能正是 3/5/7，[drawCoverIcon] 的分支必须继续能画，
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

// ==================== 绘制 ====================

/**
 * 画一个图标。
 *
 * 前景色**按底色亮度自动选黑白**：图标是单色的，画在任何换色方案的底上
 * 都要有对比，写死白色会在浅底上糊成一片。
 *
 * @param cellDesign 图标边长（设计空间）。
 * @param cx,cy      图标中心（**设计空间**，换算由 [MaterialIconSet.drawMaterialIcon] 做）。
 */
private fun DrawScope.coverIcon(
    index: Int,
    cellDesign: Float,
    cx: Float,
    cy: Float,
    base: Color,
    alpha: Float = 1f
) {
    val lum = base.red * 0.299f + base.green * 0.587f + base.blue * 0.114f
    val fg = if (lum < 0.55f) Color.White else Color(0xFF141416)
    MaterialIconSet.run {
        drawMaterialIcon(index, cellDesign, cx, cy, tint = fg, alpha = alpha)
    }
}

/**
 * 在中心 `(cx, cy)`、边长约 [cell] 的范围内画**一个**图标（图标选择器用）。
 *
 * ## 和 [drawCoverIcon] 的三点区别，都是选择器特有的需求
 * 1. **不画底色**——选择器把图标摆在自己的中性格子里，底色是它自己的事；
 *    这里多画一层渐变反而会把格子糊住、看不出选中态。
 * 2. **尺寸用 [cell] 而非比例**——选择器格子是正方形且很小（几十 dp），
 *    按封面尺寸算比例塞进小格子会溢出，这里统一缩到格内。
 * 3. **[tint] 由调用方给**——脱离封面底色后没有「按亮度选黑白」的依据。
 */
fun DrawScope.coverIconPreview(
    icon: Int,
    cell: Float,
    cx: Float,
    cy: Float,
    alpha: Float = 1f,
    tint: Color? = null
) {
    MaterialIconSet.run {
        // 这三个参数都是**画布像素**，而 drawMaterialIcon 内部还会再做一次 X()/Y() 换算。
        // 之前只换算了 cell、把 cx/cy 裸传——图标中心被算成 cx*width/1000，
        // 整体跑到格子左上角，于是「选中框（画在 Box 上）在正中、图标却在外面」。
        drawMaterialIcon(
            icon,
            cellDesign = cell * 1000f / size.width,
            cx = cx * 1000f / size.width,
            cy = cy * 1120f / size.height,
            tint = tint, alpha = alpha
        )
    }
}

/**
 * 画图标封面。
 *
 * @param p      配色。底色 `"base"`；取不到返回品红（[MISSING_PALETTE]），
 *               宁可显眼地画错也不要静默透明。
 * @param icon   主图标下标，见 [MaterialIconSet]。
 * @param layout 摆放方式，见 [CoverLayouts]。
 * @param icons  多图标排列里每个位置各自的图标（CSV，空 = 逐位退回 [nearby]）。
 */
fun DrawScope.drawCoverIcon(p: CoverPal, icon: Int, layout: Int, icons: String = "") {
    // 多图标排列里每个位置各自的图标；空 = 没指定，逐位退回 nearby()
    val csv = parseIcons(icons)
    val base = p["base"]

    // 底：与纯色款同一条三段竖向渐变（上下各压一档，图标才浮得出来）
    fillGradVStops(
        listOf(
            0.0f to lerp(base, Color.White, 0.10f),
            0.5f to base,
            1.0f to lerp(base, Color.Black, 0.10f),
        ),
        0f, 0f, 1000f, 1120f
    )

    when (layout) {
        CoverLayouts.CENTER_ONE ->
            coverIcon(icon, 420f, 500f, 545f, base)

        // 1150 > 设计空间宽 1000，所以左右必然探出；上下同理（1120 高）
        CoverLayouts.CENTER_BLEED ->
            coverIcon(icon, 1150f, 500f, 545f, base)

        CoverLayouts.CORNER -> {
            coverIcon(slotOf(csv, 0, icon, 0), 300f, 150f, 210f, base)
            coverIcon(slotOf(csv, 1, icon, 137), 300f, 850f, 210f, base)
            coverIcon(slotOf(csv, 2, icon, 419), 300f, 150f, 880f, base)
            coverIcon(slotOf(csv, 3, icon, 733), 300f, 850f, 880f, base)
        }

        CoverLayouts.BOTTOM_ROW -> {
            val n = 5
            for (i in 0 until n) {
                val cx = 500f + (i - (n - 1) / 2f) * 300f
                coverIcon(slotOf(csv, i, icon, i * 211), 260f, cx, 1010f, base)
            }
        }

        CoverLayouts.DIAGONAL -> {
            coverIcon(slotOf(csv, 0, icon, 0), 340f, 230f, 260f, base)
            coverIcon(slotOf(csv, 1, icon, 271), 260f, 560f, 480f, base, 0.92f)
            coverIcon(slotOf(csv, 2, icon, 617), 300f, 300f, 730f, base, 0.96f)
            coverIcon(slotOf(csv, 3, icon, 881), 380f, 760f, 930f, base)
        }

        CoverLayouts.DIAG_TILE -> {
            // 沿左上→右下的对角铺一串。起点 -100（连同 cell/2 在左上角外）、
            // 步长 300，末点 1100（右下角外），两端各有一张被切掉一部分——这就是「斜切出界」。
            // y 用 d*1.12 跟 x 同比，否则对角线在 1000×1120 的矩形里会被拉歪。
            // 对角平铺整条带用同一个图标（它本来就是「重复同一张」），只占一个槽位
            for (i in -1..4) {
                val d = -100f + i * 300f
                coverIcon(slotOf(csv, 0, icon, 0), 320f, d, d * 1.12f, base, 0.95f)
            }
        }

        CoverLayouts.STAGGER_GRID -> {
            val cols = 3
            val rows = 4
            for (r in 0 until rows) {
                val off = if (r % 2 == 0) 0f else 165f
                for (c in 0 until cols) {
                    val k = r * cols + c
                    coverIcon(
                        slotOf(csv, k, icon, k), 250f,
                        -60f + off + c * 330f, 170f + r * 280f, base, 0.90f
                    )
                }
            }
        }

        CoverLayouts.RING -> {
            val n = 7
            for (i in 0 until n) {
                val a = (i.toFloat() / n) * 2f * 3.1415927f - 1.5708f
                coverIcon(
                    slotOf(csv, i, icon, i * 149), 230f,
                    500f + 380f * cos(a), 560f + 430f * sin(a), base, 0.95f
                )
            }
        }

        // ---------- 角部摆放 ----------
        // 设计空间 1000×1120，左下角 = (0, 1120)、右下角 = (1000, 1120)。
        // 出血档把中心推到角外、尺寸放大，让图标的一部分落在封面之外；
        // 完整档则让图标正好贴住左/右边缘与下边缘，四边都在封面内。
        CoverLayouts.BOTTOM_LEFT_BLEED -> {
            // 贴边档是 460 且中心严格落在角上——「探出」必须比它更大、也更靠中央，
            // 否则素材整体缩在封面角上、只露出一个边角，读不出是什么（用户反馈）。
            // 这里 780 + 中心往右上收，左下两条边各探出约 1/4 边长，主体仍在封面内。
            val c = 780f
            coverIcon(icon, c, c / 2f, 1120f - c / 2f, base)
        }

        CoverLayouts.BOTTOM_RIGHT_BLEED -> {
            val c = 780f
            coverIcon(icon, c, 1000f - c / 2f, 1120f - c / 2f, base)
        }

        CoverLayouts.BOTTOM_LEFT_FIT -> {
            // 相切：左下角点落在 (0, 1120)，于是中心 = (cell/2, 1120 - cell/2)
            val c = 460f
            coverIcon(icon, c, c / 2f, 1120f - c / 2f, base)
        }

        CoverLayouts.BOTTOM_RIGHT_FIT -> {
            val c = 460f
            coverIcon(icon, c, 1000f - c / 2f, 1120f - c / 2f, base)
        }

        // 未知排版（存量数据里出现新版本没有的值）退回最保守的一档，别画成空白
        else -> coverIcon(icon, 420f, 500f, 545f, base)
    }
}
