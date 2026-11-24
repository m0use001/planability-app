package io.github.m0use001.planability.ui.notes

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.drawscope.DrawScope
import kotlin.math.sin

/**
 * 封面款式 0–5 的绘制。全部写在设计空间 1000×1120，坐标换算见 CoverProtocol。
 * 色板一律从 [CoverPal] 取，不写死 —— 换配色时这一层代码一行都不用动。
 */

// ---- 0 包豪斯几何抽象：红黄蓝 + 粗黑线 + 大面积留白 ----
internal fun DrawScope.drawCoverBauhaus(p: CoverPal) {
    fillRect(p["bg"], 0f, 0f, 1000f, 1120f)
    strokeLine(p["ink"], -40f, 900f, 1040f, 240f, 16f)
    fillRect(p["blue"], 0f, 690f, 250f, 1120f)
    strokePoly(p["ink"], px(0f, 690f, 250f, 690f, 250f, 1120f, 0f, 1120f), 14f)
    fillRect(p["red"], 640f, 120f, 1000f, 300f)
    strokePoly(p["ink"], px(640f, 120f, 1000f, 120f, 1000f, 300f, 640f, 300f), 14f)
    fillRect(p["yellow"], 560f, 0f, 640f, 1120f)
    pieSlice(p["yellow"], 500f, 620f, 250f, 180f, 360f)
    arcStroke(p["ink"], 500f, 620f, 250f, 180f, 360f, 14f)
    strokeCircle(p["ink"], 500f, 620f, 250f, 14f)
    fillCircle(p["red"], 500f, 620f, 120f)
    strokeCircle(p["ink"], 500f, 620f, 120f, 14f)
    fillPoly(p["ink"], px(760f, 700f, 1000f, 700f, 1000f, 980f))
    fillPoly(p["ink"], px(150f, 250f, 330f, 250f, 150f, 430f))
    for (r in listOf(60f, 110f, 160f)) arcStroke(p["ink"], 250f, 1120f, r, 270f, 360f, 12f)
    strokePoly(p["ink"], px(0f, 0f, 1000f, 0f, 1000f, 1120f, 0f, 1120f), 10f)
}

// ---- 1 瑞士网格海报：网格 + 超大标题条 + 一块图像区 + 正文细线 ----
internal fun DrawScope.drawCoverSwiss(p: CoverPal) {
    fillRect(p["bg"], 0f, 0f, 1000f, 1120f)
    val cols = 6
    val m = 96f
    val cw = (1000f - 2 * m) / cols
    fun cxOf(i: Int) = m + i * cw
    for (i in 0..cols) strokeLine(p["grid"], cxOf(i), 40f, cxOf(i), 1080f, 2f)
    for (yy in listOf(0.10f, 0.235f, 0.40f, 0.76f, 0.94f)) {
        strokeLine(p["grid"], m, 1120f * yy, 1000f - m, 1120f * yy, 2f)
    }
    // 超大标题：三道粗黑横线
    for (i in 0 until 3) {
        val y = 1120f * 0.115f + i * 58f
        fillRect(p["black"], m, y, 1000f - m, y + 34f)
    }
    fillRect(p["accent"], cxOf(0), 1120f * 0.40f, cxOf(2), 1120f * 0.76f)
    strokePoly(p["black"], px(cxOf(0), 1120f * 0.40f, cxOf(2), 1120f * 0.40f,
        cxOf(2), 1120f * 0.76f, cxOf(0), 1120f * 0.76f), 5f)
    // 正文：左对齐、右端不齐
    val gut = cw * 0.34f
    val runs = listOf(3.4f, 2.6f, 3.0f)
    for (i in 0 until 9) {
        val y = 1120f * 0.42f + i * 34f
        strokeLine(p["body"], cxOf(2) + gut, y, cxOf(2) + gut + cw * runs[i % 3], y, 7f)
    }
    strokeLine(p["black"], m, 1120f * 0.94f, 1000f - m, 1120f * 0.94f, 4f)
    strokeLine(p["black"], m, 1120f * 0.965f, m + 96f, 1120f * 0.965f, 8f)
    for (i in 0 until 3) {
        strokeLine(p["meta"], cxOf(3) + i * 60f, 1120f * 0.965f,
            cxOf(3) + 34f + i * 60f, 1120f * 0.965f, 5f)
    }
}

// ---- 2 孟菲斯设计：满版密铺 + 一个主色块 ----
internal fun DrawScope.drawCoverMemphis(p: CoverPal) {
    fillRect(p["bg"], 0f, 0f, 1000f, 1120f)
    fillRect(p["orange"], 90f, 470f, 620f, 800f)
    strokePoly(p["ink"], px(90f, 470f, 620f, 470f, 620f, 800f, 90f, 800f), 10f)
    fillCircle(p["teal"], 300f, 300f, 120f)
    pieSlice(p["bg"], 300f, 300f, 120f, 0f, 180f)
    strokeCircle(p["ink"], 300f, 300f, 120f, 10f)
    for (c in listOf(760f to 250f to 90f, 880f to 520f to 60f, 700f to 880f to 80f)) {
        fillCircle(p["yellow"], c.first.first, c.first.second, c.second)
        strokeCircle(p["ink"], c.first.first, c.first.second, c.second, 9f)
    }
    // 波点阵
    for (i in 0 until 5) for (j in 0 until 4) {
        if (0.62f < i / 4f && i / 4f < 0.9f && 0.1f < j / 3f && j / 3f < 0.4f) continue
        fillCircle(p["pink"], 700f + i * 62f, 620f + j * 62f, 16f)
        strokeCircle(p["ink"], 700f + i * 62f, 620f + j * 62f, 16f, 5f)
    }
    // 锯齿
    val zig = (0 until 15).map { i ->
        val xx = 80f + i * 60f
        val dip = if (i % 2 == 0) 60f else 0f   // if 表达式不能直接放进算术里
        O(xx, 980f - dip)
    }
    strokePath(p["purple"], zig, 22f, closed = false)
    // 斜条纹
    for (i in 0 until 5) strokeLine(p["ink"], 840f + i * 40f, 1120f, 1000f + i * 40f, 960f, 14f)
    // 棋盘格
    for (i in 0 until 3) for (j in 0 until 3) {
        if ((i + j) % 2 == 0) fillRect(p["teal"], 90f + i * 34f, 120f + j * 34f,
            90f + (i + 1) * 34f, 120f + (j + 1) * 34f)
    }
    // 阶梯色块
    for (i in 0 until 4) {
        val x = 660f + i * 46f
        fillRect(p["pink"], x, 1080f - i * 46f, x + 46f, 1080f)
        strokePoly(p["ink"], px(x, 1080f - i * 46f, x + 46f, 1080f - i * 46f,
            x + 46f, 1080f, x, 1080f), 6f)
    }
    // 三角旗
    for (i in 0 until 3) {
        val x = 620f + i * 90f
        val c = if (i % 2 == 0) p["yellow"] else p["teal"]
        fillPoly(c, px(x, 140f, x + 60f, 140f, x + 30f, 60f))
        strokePoly(p["ink"], px(x, 140f, x + 60f, 140f, x + 30f, 60f), 6f)
    }
}

// ---- 3 构成主义 / 至上主义：一条主斜轴压住画面 ----
internal fun DrawScope.drawCoverConstructivism(p: CoverPal) {
    fillRect(p["paper"], 0f, 0f, 1000f, 1120f)
    fillPoly(p["red"], px(-60f, 300f, 1060f, 120f, 1060f, 300f, -60f, 480f))
    fillPoly(p["ink"], px(-60f, 300f, 1060f, 120f, 1060f, 190f, -60f, 370f))
    fillPoly(p["ink"], px(120f, 1120f, 420f, 1120f, 150f, 640f))
    fillCircle(p["red"], 700f, 470f, 190f)
    strokeCircle(p["ink"], 700f, 470f, 190f, 12f)
    fillCircle(p["paper"], 700f, 470f, 95f)
    fillPoly(p["blue"], px(820f, 1120f, 1060f, 1120f, 1060f, 700f))
    fillPoly(p["yellow"], px(250f, 800f, 900f, 620f, 900f, 700f, 250f, 880f))
    strokeLine(p["ink"], -60f, 980f, 1060f, 560f, 10f)
    strokeLine(p["ink"], 0f, 60f, 0f, 1060f, 4f)
    strokeLine(p["ink"], 980f, 0f, 980f, 1120f, 4f)
}

// ---- 4 欧普艺术：黑白放射楔形 + 同心环 + 角部强调色 ----
internal fun DrawScope.drawCoverOpArt(p: CoverPal) {
    fillRect(p["white"], 0f, 0f, 1000f, 1120f)
    val cx = 500f; val cy = 560f
    for (i in 0 until 36) pieSlice(p["black"], cx, cy, 720f, i * 10f, i * 10f + 5f)
    for (r in listOf(150f, 300f, 450f)) strokeCircle(p["black"], cx, cy, r, 26f)
    fillCircle(p["white"], cx, cy, 90f)
    strokeCircle(p["black"], cx, cy, 90f, 22f)
    fillRect(p["accent"], 0f, 980f, 300f, 1120f)
    val wave = (0..1000 step 12).map { xi ->
        val x = xi.toFloat()
        val t = (x / 60f) % 6.28318f
        O(x, 1010f + 46f * ((t - 3.14159f) / 3.14159f) * 0.5f)
    }
    strokePath(p["black"], wave, 14f, closed = false)
}

// ---- 5 低多边形：一座山体 + 大面片水面 + 夕阳长方形倒影 ----
internal fun DrawScope.drawCoverLowPoly(p: CoverPal) {
    fillGradVStops(listOf(
        0f to p["skyTop"], 0.22f to p["skyHi"], 0.46f to p["skyMid"],
        0.70f to p["skyLow"], 1f to p["skyBot"]), 0f, 0f, 1000f, 1120f)
    // 低面数太阳（正六边形，圆会跑偏风格）
    fillPoly(p["sun"], px(760f, 150f, 820f, 196f, 820f, 268f, 760f, 314f, 700f, 268f, 700f, 196f))
    val WTOP = 690f
    // 一座连贯山体：每段脊线拆成两个明度不同的三角面
    val ridge = listOf(
        0f to 585f, 110f to 505f, 215f to 548f, 330f to 392f,
        455f to 500f, 600f to 330f, 720f to 452f, 840f to 386f, 1000f to 505f)
    for (i in 0 until ridge.size - 1) {
        val (ax, ay) = ridge[i]
        val (bx, by) = ridge[i + 1]
        val lit = 0.5f + 0.5f * (i.toFloat() / (ridge.size - 2))
        fillPoly(p.mix("mtnBase", p["mtnDark"], lit), px(ax, ay, bx, by, ax, WTOP))
        fillPoly(p.mix("mtnLit", p["mtnDeep"], lit), px(ax, ay, bx, by, bx, WTOP))
    }
    // 主峰左侧压暗、右侧加暖边光
    fillPoly(p.shade("mtnDark", 0.45f), px(600f, 330f, 600f, WTOP, 760f, WTOP))
    fillPoly(p.tint("mtnBase", 0.22f), px(600f, 330f, 720f, 452f, 840f, 386f, 1000f, 505f, 1000f, WTOP, 900f, WTOP))
    // 面片接缝
    strokeLine(p["mtnDark"].copy(alpha = 0.35f), 330f, 392f, 360f, WTOP, 3f)
    strokeLine(p["mtnDark"].copy(alpha = 0.35f), 600f, 330f, 644f, WTOP, 3f)
    // 水面：几片大三角面
    fillRect(p["water"], 0f, WTOP, 1000f, 1120f)
    val water = listOf(0f to 700f, 260f to 748f, 540f to 704f, 800f to 756f, 1000f to 716f)
    for (i in 0 until water.size - 1) {
        val (ax, ay) = water[i]
        val (bx, by) = water[i + 1]
        val sh = lerp(p["waterHi"], p["waterLo"], i.toFloat() / (water.size - 2))
        fillPoly(sh, px(ax, ay, bx, by, (ax + bx) / 2f - 70f, 1120f))
        fillPoly(lerp(sh, Color.Black, 0.30f), px(ax, ay, bx, by, (ax + bx) / 2f + 140f, 1120f))
    }
    strokeLine(lerp(p["mtnBase"], p["refl"], 0.45f), 0f, WTOP, 1000f, WTOP, 5f)
    // 夕阳倒影：长方形，自上而下逐级加宽
    for (i in 0 until 5) {
        val y0 = WTOP + 30f + i * 86f
        val wd = 84f + i * 52f
        fillRect(lerp(p["refl"], p["reflHi"], 0.14f + i * 0.16f), 760f - wd / 2f, y0, 760f + wd / 2f, y0 + 40f)
    }
}

/** 供 sin 引用保持 import 被用（欧普那条波浪在别处也可能用到） */
internal fun waveY(x: Float) = 1010f + 46f * (((x / 60f) % 6.28318f) - 3.14159f) / 3.14159f * 0.5f