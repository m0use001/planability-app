package io.github.m0use001.planability.ui.notes

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.drawscope.DrawScope
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin

/**
 * 封面款式 6–10 的绘制。坐标一律用设计空间 1000×1120，色值全部来自 [CoverPal]。
 */

// ---- 6 蓝图 / 工程图：爆炸图 + 正视 + 剖面线 + 尺寸箭头 + 标题栏 ----
internal fun DrawScope.drawCoverBlueprint(p: CoverPal) {
    fillRect(p["bg"], 0f, 0f, 1000f, 1120f)
    for (i in 0..1000 step 40) {
        val heavy = i % 200 == 0
        strokeLine(if (heavy) p["auxStrong"] else p["aux"], i.toFloat(), 0f, i.toFloat(), 1120f,
            if (heavy) 4f else 2f)
    }
    for (i in 0..1120 step 40) {
        val heavy = i % 200 == 0
        strokeLine(if (heavy) p["auxStrong"] else p["aux"], 0f, i.toFloat(), 1000f, i.toFloat(),
            if (heavy) 4f else 2f)
    }
    // 爆炸分解
    dash(150f, 250f, 150f, 760f, p["aux"], 3f)
    for ((k, dy) in listOf(0f, 150f, 300f).withIndex()) {
        val y0 = 250f + dy
        strokePoly(p["line"], px(80f, y0, 250f, y0, 250f, y0 + 90f, 80f, y0 + 90f), 6f)
        strokePoly(p["line"], px(98f, y0 + 20f, 232f, y0 + 20f, 232f, y0 + 70f, 98f, y0 + 70f), 4f)
        strokeCircle(p["line"], 110f, y0 + 45f, 11f, 4f)
        strokeCircle(p["line"], 220f, y0 + 45f, 11f, 4f)
        if (k < 2) {
            strokeLine(p["accent"], 80f, y0 + 100f, 90f, y0 + 130f, 4f)
            strokeLine(p["accent"], 250f, y0 + 100f, 240f, y0 + 130f, 4f)
        }
    }
    // 中央正视
    val mx0 = 400f; val my0 = 230f; val mx1 = 780f; val my1 = 760f
    strokePoly(p["line"], px(mx0, my0, mx1, my0, mx1, my1, mx0, my1), 8f)
    for (i in 0 until 4) {
        val yy = my0 + 90f + i * 105f
        strokePoly(p["line"], px(mx0 + 30f, yy, mx1 - 30f, yy, mx1 - 30f, yy + 66f, mx0 + 30f, yy + 66f), 5f)
        if (i == 2) for (k in 0 until 9) {
            val hx0 = mx0 + 30f + k * 26f
            strokeLine(p["aux"], hx0, yy + 66f, hx0 + 40f, yy + 26f, 3f)
        }
    }
    val midY = (my0 + my1) / 2f
    val midX = (mx0 + mx1) / 2f
    dash(mx0 - 40f, midY, mx1 + 40f, midY, p["aux"], 3f)
    dash(midX, my0 - 40f, midX, my1 + 40f, p["aux"], 3f)
    // 螺栓孔节圆
    strokeCircle(p["aux"], midX, my0 + 45f, 34f, 3f)
    for (i in 0 until 6) {
        val a = i * 60f * Math.PI.toFloat() / 180f
        strokeCircle(p["line"], midX + 34f * cos(a), my0 + 45f + 34f * sin(a), 9f, 3f)
    }
    // 尺寸箭头
    strokeLine(p["aux"], mx0, my1, mx0, my1 + 88f, 3f)
    strokeLine(p["aux"], mx1, my1, mx1, my1 + 88f, 3f)
    strokeLine(p["accent"], mx0, my1 + 70f, mx1, my1 + 70f, 5f)
    strokeLine(p["accent"], mx0 - 12f, my1 + 58f, mx0 + 12f, my1 + 82f, 5f)
    strokeLine(p["accent"], mx1 - 12f, my1 + 58f, mx1 + 12f, my1 + 82f, 5f)
    strokeLine(p["aux"], mx1 + 70f, my0, mx1 + 100f, my0, 3f)
    strokeLine(p["aux"], mx1 + 70f, my1, mx1 + 100f, my1, 3f)
    strokeLine(p["accent"], mx1 + 85f, my0, mx1 + 85f, my1, 5f)
    strokeLine(p["accent"], mx1 + 73f, my0 - 12f, mx1 + 97f, my0 + 12f, 5f)
    strokeLine(p["accent"], mx1 + 73f, my1 - 12f, mx1 + 97f, my1 + 12f, 5f)
    // 细节圆
    fillCircle(p["bg"], 860f, 300f, 105f)
    strokeCircle(p["line"], 860f, 300f, 105f, 6f)
    strokeLine(p["line"], mx1 - 20f, 560f, 800f, 380f, 4f)
    strokeCircle(p["line"], 860f, 300f, 62f, 5f)
    dash(790f, 300f, 930f, 300f, p["aux"], 3f)
    dash(860f, 230f, 860f, 370f, p["aux"], 3f)
    strokeCircle(p["accent"], 860f, 300f, 22f, 4f)
    // 标题栏
    strokePoly(p["line"], px(540f, 950f, 960f, 950f, 960f, 1090f, 540f, 1090f), 7f)
    strokeLine(p["line"], 540f, 994f, 960f, 994f, 4f)
    strokeLine(p["line"], 730f, 950f, 730f, 1090f, 4f)
    strokeLine(p["line"], 540f, 1046f, 960f, 1046f, 3f)
    strokeLine(p["auxStrong"], 554f, 972f, 690f, 972f, 7f)
    // 图框
    strokePoly(p["line"], px(36f, 36f, 964f, 36f, 964f, 1084f, 36f, 1084f), 9f)
}

// ---- 7 等高线地形图：三个互不相交的等高线群 ----
internal fun DrawScope.drawCoverContour(p: CoverPal) {
    fillRect(p["paper"], 0f, 0f, 1000f, 1120f)
    for (i in 1 until 8) {
        strokeLine(p["grid"], i * 125f, 0f, i * 125f, 1120f, 2f)
        strokeLine(p["grid"], 0f, i * 140f, 1000f, i * 140f, 2f)
    }
    val bands = listOf(p["b0"], p["b1"], p["b2"], p["b3"], p["b4"])
    val peaks = listOf(Triple(290f, 370f, 0.80f), Triple(770f, 545f, 0.58f), Triple(120f, 880f, 0.38f))
    for ((px0, py0, scale) in peaks) {
        val rings = 12
        for (k in rings downTo 1) {
            val t = k / rings.toFloat()
            val rx = 330f * scale * t
            val ry = 290f * scale * t
            val pts = (0 until 97).map { i ->
                val a = i / 96f * 2f * Math.PI.toFloat()
                val wob = 1f + 0.10f * sin(a * 2 + px0 * 0.01f) +
                    0.06f * sin(a * 3 + py0 * 0.013f) + 0.035f * sin(a * 5 + 1.7f)
                O(px0 + rx * wob * cos(a), py0 + ry * wob * sin(a))
            }
            fillPoly(bands[((1 - t) * bands.size).toInt().coerceAtMost(bands.size - 1)], pts)
            strokePath(p["ink"], pts, if (k % 4 == 0) 4f else 2f)
        }
        // 山头十字
        strokeLine(p["ink"], px0 - 16f, py0, px0 + 16f, py0, 4f)
        strokeLine(p["ink"], px0, py0 - 16f, px0, py0 + 16f, 4f)
    }
    // 陡崖短线
    for (i in 0 until 16) {
        val a = (20f + i * 4.5f) * Math.PI.toFloat() / 180f
        val x = 290f + 264f * cos(a)
        val y = 370f + 232f * sin(a)
        strokeLine(p["ink"], x, y, x + 14f, y + 24f, 3f)
    }
    // 比例尺
    for (i in 0 until 4) {
        fillRect(if (i % 2 == 0) p["ink"] else p["paper"],
            70f + i * 56f, 1032f, 70f + (i + 1) * 56f, 1058f)
        strokePoly(p["ink"], px(70f + i * 56f, 1032f, 70f + (i + 1) * 56f, 1032f,
            70f + (i + 1) * 56f, 1058f, 70f + i * 56f, 1058f), 3f)
    }
    strokeLine(p["ink"], 70f, 1080f, 294f, 1080f, 3f)
    // 指北针
    strokeCircle(p["ink"], 880f, 150f, 32f, 4f)
    fillPoly(p["ink"], px(880f, 108f, 906f, 196f, 880f, 172f, 854f, 196f))
}

// ---- 8 剪纸层叠：框 + 五层递进，每层留一道厚度高光 ----
internal fun DrawScope.drawCoverPaperCut(p: CoverPal) {
    fillRect(p["frame"], 0f, 0f, 1000f, 1120f)
    // 圆角内框
    drawRoundRectCompat(p["inner"], 70f, 90f, 930f, 1030f, 60f)
    fillRect(p["sky"], 70f, 90f, 930f, 330f)
    val layers = listOf(p["b0"], p["b1"], p["b2"], p["b3"], p["b4"])
    for (i in 0 until 5) {
        val y = 430f + i * 118f
        val col = layers[i]
        val ridge = ridgeLine(1000f, y, 70f + i * 16f, 2 + i, 11 + i, 40)
        // 统一的投影方向（朝右下）
        // v19.46：填到 **1030**（内框下沿）而不是 960。原来层只到 960，而内框到 1030、
        // 画布到 1120，于是在底部留了一条 **160 单位的近白色带**（[森林绿] 配色的
        // frame=#EAF4EA / inner=#F4F8F1 都是近白），比上边的 90、左右的 70 厚一倍 ——
        // 用户看到的是「底下有一条白边」，而纸边的意图本来是**四边等宽的框**。
        fillPoly(lerp(col, Color.Black, 0.16f), shiftDown(ridge, R(16f)) +
            listOf(O(1000f, 1030f), O(70f, 1030f)))
        fillPoly(col, ridge + listOf(O(1000f, 1030f), O(70f, 1030f)))
        strokeSmoothPoly(lerp(col, Color.White, 0.38f), ridge, 7f)
        if (i >= 2) for (k in 0 until 6) {
            val x = 100f + k * 150f + (i * 37) % 60
            val yy = y - 30f - (k % 3) * 22f
            fillPoly(lerp(col, Color.Black, 0.22f), px(x, yy - 60f, x + 26f, yy, x - 26f, yy))
        }
    }
    strokeRoundRectCompat(p["frameLine"], 70f, 90f, 930f, 1030f, 60f, 6f)
}

// ---- 9 Art Deco：中轴对称 + 金色日出放射 + 阶梯冠 ----
internal fun DrawScope.drawCoverArtDeco(p: CoverPal) {
    fillRect(p["bg"], 0f, 0f, 1000f, 1120f)
    val cx = 500f
    for (i in -7..7) strokeLine(p["gold"], cx, 300f, cx + i * 120f, 90f, 5f)
    pieSlice(p["goldLight"], cx, 300f, 210f, 180f, 360f)
    var r = 60f
    while (r < 210f) { arcStroke(p["bg"], cx, 300f, r, 180f, 360f, 12f); r += 42f }
    for (i in 0 until 5) {
        val w = 520f - i * 74f
        val y = 300f + i * 44f
        strokePoly(p["gold"], px(cx - w / 2f, y, cx + w / 2f, y, cx + w / 2f, y + 44f, cx - w / 2f, y + 44f), 5f)
    }
    for (sgn in intArrayOf(-1, 1)) {
        for (i in 0 until 6) {
            strokeLine(p["gold"], cx + sgn * (300f + i * 34f), 560f,
                cx + sgn * (300f + i * 34f), 900f, 4f)
        }
        if (sgn > 0) pieSliceStroke(p["gold"], cx + 330f, 730f, 110f, 0f, 180f, 6f)
        else pieSliceStroke(p["gold"], cx - 330f, 730f, 110f, 180f, 360f, 6f)
    }
    fillRect(p["bg"], 230f, 610f, 770f, 830f)
    strokePoly(p["gold"], px(230f, 610f, 770f, 610f, 770f, 830f, 230f, 830f), 6f)
    strokePoly(p["goldLight"], px(252f, 632f, 748f, 632f, 748f, 808f, 252f, 808f), 3f)
    for (i in 0 until 5) {
        val x = cx - 320f + i * 160f
        fillPoly(if (i % 2 == 0) p["gold"] else p["goldLight"],
            px(x, 960f, x + 40f, 1000f, x, 1040f, x - 40f, 1000f))
    }
    strokePoly(p["gold"], px(40f, 40f, 960f, 40f, 960f, 1080f, 40f, 1080f), 8f)
    strokePoly(p["goldLight"], px(62f, 62f, 938f, 62f, 938f, 1058f, 62f, 1058f), 3f)
}

// ---- 10 星座图 / 星图：星场 + 银河带 + 北斗式星座 + 黄道环 ----
internal fun DrawScope.drawCoverStarChart(p: CoverPal) {
    fillGradVStops(listOf(0f to p["skyTop"], 0.55f to p["skyTop"], 1f to p["skyBot"]),
        0f, 0f, 1000f, 1120f)
    val rnd = Rng(7)
    val cx = 500f; val cy = 545f; val r = 372f
    // 球外的星场
    for (i in 0 until 260) {
        val x = rnd.range(0f, 1000f)
        val y = rnd.range(0f, 1120f)
        if ((x - cx) * (x - cx) + (y - cy) * (y - cy) < r * r) continue
        fillCircle(p["star"].copy(alpha = rnd.range(0.35f, 0.9f)), x, y, rnd.range(0.8f, 2.2f))
    }
    // 银河带
    var bx = -40f
    while (bx < 1040f) {
        val y = 300f + 210f * sin((bx / 1000f) * Math.PI.toFloat() * 1.15f - 0.4f)
        strokeLine(p["milky"].copy(alpha = 0.13f), bx, y, bx + 16f, y, 34f)
        bx += 16f
    }
    // 天球
    fillCircle(p["sphere"].copy(alpha = 0.55f), cx, cy, r)
    strokeCircle(p["line"], cx, cy, r, 4f)
    strokeCircle(p["line"].copy(alpha = 0.4f), cx, cy, r * 0.62f, 2f)
    strokeCircle(p["line"].copy(alpha = 0.35f), cx, cy, r * 0.30f, 2f)
    dash(cx - r, cy, cx + r, cy, p["line"].copy(alpha = 0.45f), 2f, on = 18f, off = 14f)
    dash(cx, cy - r, cx, cy + r, p["line"].copy(alpha = 0.45f), 2f, on = 18f, off = 14f)
    // 球内星点
    for (i in 0 until 170) {
        val a = rnd.range(0f, 6.2832f)
        val rr = r * Math.sqrt(rnd.range(0f, 1f).toDouble()).toFloat()
        val x = cx + rr * cos(a)
        val y = cy + rr * sin(a)
        val sz = rnd.range(1.4f, 4.2f)
        fillCircle(p["star"], x, y, sz)
        if (sz > 3.7f) {
            strokeLine(p["star"].copy(alpha = 0.5f), x - sz * 2.6f, y, x + sz * 2.6f, y, 2f)
            strokeLine(p["star"].copy(alpha = 0.5f), x, y - sz * 2.6f, x, y + sz * 2.6f, 2f)
        }
    }
    // 北斗式星座（固定图形，不是随机折线）
    // 用 Triple 而不是嵌套的 Pair：a to b to c 会解析成 ((a to b) to c)，
    // 再按 (x, y, sz) 解构就要求 component3()，编译不过
    val dipper = listOf(
        Triple(300f, 330f, 6f), Triple(400f, 300f, 5f), Triple(500f, 300f, 5f),
        Triple(600f, 315f, 5f), Triple(660f, 400f, 4f), Triple(640f, 500f, 4f),
        Triple(620f, 610f, 4f))
    for (i in 0 until dipper.size - 1) {
        strokeLine(p["line"].copy(alpha = 0.85f), dipper[i].first, dipper[i].second,
            dipper[i + 1].first, dipper[i + 1].second, 3f)
    }
    strokeLine(p["line"].copy(alpha = 0.4f), dipper[3].first, dipper[3].second,
        dipper[6].first, dipper[6].second, 2f)
    for ((x, y, sz) in dipper) {
        fillCircle(Color.White, x, y, sz)
        strokeCircle(p["star"].copy(alpha = 0.9f), x, y, sz, 2f)
        strokeLine(p["star"].copy(alpha = 0.45f), x - sz * 2.6f, y, x + sz * 2.6f, y, 2f)
        strokeLine(p["star"].copy(alpha = 0.45f), x, y - sz * 2.6f, x, y + sz * 2.6f, 2f)
    }
    // 黄道刻度环
    strokeCircle(p["line"], cx, cy, r + 60f, 3f)
    for (i in 0 until 72) {
        val a = i / 72f * 6.2832f
        val long = (i % 6 == 0)
        val r0 = r + 60f - (if (long) 28f else 13f)
        strokeLine(p["line"].copy(alpha = 0.9f),
            cx + r0 * cos(a), cy + r0 * sin(a),
            cx + (r + 60f) * cos(a), cy + (r + 60f) * sin(a), if (long) 4f else 2f)
    }
    // 月相徽章
    fillCircle(p["star"], 790f, 900f, 66f)
    fillCircle(p["moon"], 822f, 880f, 62f)
}

// 本文件的绘制辅助已全部移入 CoverDraw（见 CoverDraw.kt）：
// 点划线 / 山脊线 / 圆角矩形 等在那边是 CoverDraw 的成员，
// 这里用顶层转发调用，15 款矢量封面的代码保持简短。
