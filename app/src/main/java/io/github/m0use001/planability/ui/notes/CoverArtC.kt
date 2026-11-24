package io.github.m0use001.planability.ui.notes

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.drawscope.DrawScope
import kotlin.math.cos
import kotlin.math.sin

/**
 * 封面款式 11–14 的绘制。坐标一律用设计空间 1000×1120，色值全部来自 [CoverPal]。
 */

// ---- 11 莫兰迪色块 / 抽象地形：横向分层，最浅的天空 + 渐深的山峦 ----
internal fun DrawScope.drawCoverMorandi(p: CoverPal) {
    fillRect(p["c0"], 0f, 0f, 1000f, 1120f)
    fillCircle(p["sun"], 720f, 250f, 120f)
    val cols = listOf(p["c0"], p["c1"], p["c2"], p["c3"], p["c4"], p["c5"], p["c6"])
    val ys = listOf(430f, 560f, 690f, 820f, 950f, 1070f)
    for (i in ys.indices) {
        val ridge = ridgeLine(1000f, ys[i], 60f + i * 14f, 2 + i, 21 + i, 48)
        fillPoly(cols[(i + 1).coerceAtMost(cols.size - 1)],
            ridge + listOf(O(1000f, 1120f), O(0f, 1120f)))
    }
    // 细颗粒噪点：让色块不像纯色填充
    val rnd = Rng(3)
    for (i in 0 until 1600) {
        val x = rnd.range(0f, 1000f)
        val y = rnd.range(0f, 1120f)
        fillRect(if (i % 2 == 0) Color.White.copy(alpha = 0.05f) else Color.Black.copy(alpha = 0.05f),
            x, y, x + 2f, y + 2f)
    }
}

// ---- 12 故障艺术：主体 + RGB 重影 + 集中的撕裂条 + 扫描线 ----
internal fun DrawScope.drawCoverGlitch(p: CoverPal) {
    fillRect(p["bg"], 0f, 0f, 1000f, 1120f)
    val subj = listOf(
        floatArrayOf(230f, 300f, 770f, 380f),
        floatArrayOf(230f, 430f, 700f, 510f),
        floatArrayOf(230f, 560f, 770f, 640f))
    for ((dx, col) in listOf(-22f to p["red"], 22f to p["cyan"], 10f to p["blue"])) {
        for (s in subj) fillRect(col.copy(alpha = 0.85f), s[0] + dx, s[1], s[2] + dx, s[3])
    }
    for (s in subj) fillRect(p["white"].copy(alpha = 0.92f), s[0], s[1], s[2], s[3])
    val rnd = Rng(23)
    // 撕裂条集中在上/中/下三段，不均匀分布
    // 用 Pair 而不是 ClosedFloatingPointRange：FloatRange 的 first/last 在本项目
    // 用的 Kotlin 版本上解析不到
    val zones = listOf(240f to 420f, 560f to 700f, 760f to 1000f)
    repeat(16) {
        val z = zones[rnd.int(0, 3)]
        val y = rnd.range(z.first, z.second)
        val h = rnd.range(6f, 26f)
        val dx = (if (rnd.next() > 0.5f) -1f else 1f) * rnd.range(12f, 90f)
        val c = listOf(p["red"], p["cyan"], p["blue"], p["white"], p["grey"])[rnd.int(0, 5)]
        if (dx < 0) fillRect(c.copy(alpha = rnd.range(0.5f, 0.95f)), 0f, y, 1000f, y + h)
        else fillRect(c.copy(alpha = rnd.range(0.5f, 0.95f)), dx, y, 1000f, y + h)
    }
    repeat(26) {
        val x = rnd.range(0f, 880f)
        val y = rnd.range(0f, 1050f)
        val c = listOf(p["red"], p["cyan"], p["blue"], p["grey"])[rnd.int(0, 4)]
        fillRect(c.copy(alpha = rnd.range(0.4f, 0.9f)), x, y, x + rnd.range(40f, 130f), y + rnd.range(20f, 70f))
    }
    var y = 0f
    while (y < 1120f) { fillRect(Color.Black.copy(alpha = 0.35f), 0f, y, 1000f, y + 2f); y += 6f }
}

// ---- 13 中式几何装饰：八角窗格 + 冰裂纹 + 回纹 + 印章 ----
internal fun DrawScope.drawCoverChinese(p: CoverPal) {
    fillRect(p["xuan"], 0f, 0f, 1000f, 1120f)
    val cx = 500f; val cy = 540f
    fillPoly(p["inner"], octa(cx, cy, 392f))
    strokePath(p["ink"], octa(cx, cy, 392f), 13f)
    strokePath(p["gold"], octa(cx, cy, 356f), 9f)
    strokePath(p["ink"], octa(cx, cy, 330f), 4f)
    // 冰裂纹：八角顶点两两连线（外层 + 内层细网）
    val v1 = octa(cx, cy, 330f)
    for (i in v1.indices) for (j in i + 1 until v1.size) {
        strokeLine(p["ink"].copy(alpha = 0.26f), v1[i].x, v1[i].y, v1[j].x, v1[j].y, 4f)
    }
    val v2 = octa(cx, cy, 196f)
    for (i in v2.indices) for (j in i + 1 until v2.size) {
        strokeLine(p["ink"].copy(alpha = 0.20f), v2[i].x, v2[i].y, v2[j].x, v2[j].y, 3f)
    }
    // 中央双重方胜
    for ((rr, col) in listOf(196f to p["ink"], 150f to p["red"])) {
        strokePath(col, px(cx, cy - rr, cx + rr, cy, cx, cy + rr, cx - rr, cy), 7f)
        strokePoly(col, px(cx - rr * 0.72f, cy - rr * 0.72f, cx + rr * 0.72f, cy - rr * 0.72f,
            cx + rr * 0.72f, cy + rr * 0.72f, cx - rr * 0.72f, cy + rr * 0.72f), 7f)
    }
    fillCircle(p["inner"], cx, cy, 92f)
    strokeCircle(p["ink"], cx, cy, 92f, 8f)
    strokeCircle(p["gold"], cx, cy, 92f, 3f)
    // 回纹边框（上下）
    for ((yy, flip) in listOf(112f to 1f, (1120f - 112f) to -1f)) {
        for (i in 0 until 11) {
            val x = 96f + i * 74f
            strokeLine(p["red"], x, yy, x, yy + 54f * flip, 9f)
            strokeLine(p["red"], x, yy + 54f * flip, x + 50f, yy + 54f * flip, 9f)
            strokeLine(p["red"], x + 50f, yy + 54f * flip, x + 50f, yy + 22f * flip, 9f)
            strokeLine(p["red"], x + 50f, yy + 22f * flip, x + 26f, yy + 22f * flip, 9f)
        }
    }
    strokePoly(p["ink"], px(84f, 84f, 916f, 84f, 916f, 1036f, 84f, 1036f), 11f)
    strokePoly(p["gold"], px(102f, 102f, 898f, 102f, 898f, 1018f, 102f, 1018f), 4f)
    // 四角如意云头
    for ((ccx, ccy) in listOf(168f to 236f, 832f to 236f, 168f to 884f, 832f to 884f)) {
        fillCircle(p["xuan"], ccx, ccy, 62f)
        strokeCircle(p["ink"], ccx, ccy, 62f, 6f)
        arcStroke(p["red"], ccx, ccy, 44f, 160f, 380f, 8f)
        arcStroke(p["red"], ccx - 26f, ccy + 6f, 26f, 200f, 340f, 7f)
        arcStroke(p["red"], ccx + 26f, ccy + 6f, 26f, 200f, 340f, 7f)
        strokePath(p["gold"], px(ccx, ccy - 30f, ccx + 30f, ccy, ccx, ccy + 30f, ccx - 30f, ccy), 6f)
    }
    // 印章式题匾
    drawRoundRectCompat(p["red"], 432f, 452f, 568f, 588f, 10f)
    strokeRoundRectCompat(p["xuan"], 444f, 464f, 556f, 576f, 6f, 4f)
    strokePoly(p["xuan"], px(466f, 486f, 534f, 486f, 534f, 516f, 466f, 516f), 8f)
    strokePoly(p["xuan"], px(466f, 528f, 534f, 528f, 534f, 558f, 466f, 558f), 8f)
}

// ---- 14 冷抽象 / 蒙德里安：不等距网格 + 靠边色块 + 贯穿黑条 ----
internal fun DrawScope.drawCoverMondrian(p: CoverPal) {
    fillRect(p["canvas"], 0f, 0f, 1000f, 1120f)
    val xs = floatArrayOf(0f, 290f, 452f, 690f, 1000f)
    val ys = floatArrayOf(0f, 300f, 470f, 760f, 1120f)
    fillRect(p["red"], xs[0], ys[0], xs[1], ys[1])
    fillRect(p["blue"], xs[3], ys[1], xs[4], ys[2])
    fillRect(p["grey"], xs[1], ys[3], xs[2], ys[4])
    fillRect(p["yellow"], xs[2], ys[2], xs[3], ys[3])
    // 贯穿黑条画在网格之上（否则会被网格线切断）
    fillRect(p["ink"], xs[0], ys[2], xs[4], ys[2] + 66f)
    for (x in xs) strokeLine(p["ink"], x, ys[0], x, ys[4], 18f)
    for (y in ys) strokeLine(p["ink"], xs[0], y, xs[4], y, 18f)
}

/** 供本文件内部使用的三角函数引用（八角计算） */
internal fun cosD(deg: Float) = cos(deg * Math.PI.toFloat() / 180f)
internal fun sinD(deg: Float) = sin(deg * Math.PI.toFloat() / 180f)