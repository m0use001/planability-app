package io.github.m0use001.planability.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import androidx.compose.ui.unit.DpSize
import io.github.m0use001.planability.ui.theme.AppFonts
import java.time.LocalDate
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 「今日」（合并款）与「多天总览」两款组件的位图画布，与 [ClockArt] 同一套路：
 * Glance 的 Text 吃不到应用字体（详见 ClockArt 的注释），所以整块画成图，
 * 字体/字重/字号跟随外观页设置，版面按卡片尺寸归一。
 *
 * 与时钟不同：这两款的卡面用 **surfaceContainer**（中性面）——内容是时间轴/月历这类
 * 多行文字，彩色大面会喧宾夺主；时钟是「一块面 + 一行字」，才用第二色实心面。
 */
internal object WidgetArt {

    /** 一套原始色（调用方从 WidgetPalette 取好 ARGB） */
    /** CJK 判断（字号参考字形与换行都靠它） */
    private fun isCjk(ch: Char?): Boolean =
        ch != null && (ch in '一'..'鿿' || ch in '　'..'〿')

    class Colors(
        val card: Int,
        val onCard: Int,
        val sub: Int,
        val primary: Int,
        val onPrimary: Int,
        val primaryContainer: Int,
        val onPrimaryContainer: Int,
        val secondaryContainer: Int,
        val onSecondaryContainer: Int,
        val tertiaryContainer: Int,
        val onTertiaryContainer: Int,
        /** 第二色家族彩面（WidgetPalette.clockCardColor，大时钟/倒数日卡的底）。
         *  带默认值：旧调用点不传也不受影响，只有需要彩面的位图组件取它。 */
        val clockFace: Int = 0,
        /** 主色强调数字（WidgetPalette.heroNumberColor）：本轮统一的「组件里那个主色大数字」。
         *  0 = 未提供，调用方退回 primary。带默认值是为了让旧布局缓存反序列化不炸。 */
        val heroNumber: Int = 0
    ) {
        val tertiary: Int = tertiaryContainer   // 圆点/点缀沿用第三色容器族
    }

    /** fg 以 [alpha] 叠在 bg 上（用于中性胶囊这类临时派生色） */
    private fun blend(fg: Int, bg: Int, alpha: Float): Int {
        val a = (alpha * 255).roundToInt()
        fun ch(v: Int, shift: Int) = (v shr shift) and 0xFF
        fun mix(shift: Int) = (ch(fg, shift) * a + ch(bg, shift) * (255 - a)) / 255
        return (0xFF shl 24) or (mix(16) shl 16) or (mix(8) shl 8) or mix(0)
    }

    private fun luma(argb: Int): Float {
        val r = (argb shr 16 and 0xFF) / 255f
        val g = (argb shr 8 and 0xFF) / 255f
        val b = (argb and 0xFF) / 255f
        return 0.299f * r + 0.587f * g + 0.114f * b
    }

    /**
     * 事件块底色对比度闸（v51，2026-11-13 用户「内容颜色与背景混在一起」）：
     * 暗色下 tertiaryContainer（V≈0.3 的深紫蓝）压在 surfaceContainer 卡底上，
     * 亮度差实测只有 ~0.03（真机截图像素 47,46,98 vs 43,45,43）——块只剩色相可辨。
     * 这里按卡面明暗把容器色抬到 Δluma ≥ [minDelta]（暗色向白提、浅色向黑压），
     * 色相不变，只补亮度层。块上文字仍用 onCard（近白/近黑），两侧对比都还在。
     */
    internal fun blockBg(container: Int, card: Int, minDelta: Float = 0.14f): Int {
        val dark = luma(card) < 0.5f
        val l = luma(container)
        val target = if (dark) luma(card) + minDelta else luma(card) - minDelta
        val enough = if (dark) l >= target else l <= target
        if (enough) return container
        val k = ((if (dark) target - l else l - target) /
            (if (dark) 1f - l else l).coerceAtLeast(0.01f)).coerceIn(0f, 1f)
        // 注意 WidgetArt.blend(fg, bg, alpha) 的语义：**fg 是叠加层**（fg*α + bg*(1-α)）。
        // 参数写反就会变成「容器×(1-k) + 白×k」的近白板（12:39 真机首验实锤，
        // TodayStatsWidget.kt 里同款坑有前科）——底=容器、叠层=白/黑。
        return blend(if (dark) 0xFFFFFFFF.toInt() else 0xFF000000.toInt(), container, k)
    }

    class TextPainter(ctx: Context, fontKey: String, weight: Int) {
        val base: Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = AppFonts.typeface(ctx, fontKey, weight)
        }

        /**
         * 按**墨迹高度**定字号。注意用**参考字形**（中文「国」/ 拉丁数字「0」）来换算，
         * 不是待画的那串自己：一/二/十 这类字形墨迹只有一条横线，用自己的墨迹算会把字号
         * 放大十几倍（月历周首行曾因此画出一条横贯好几列的白杠）。
         */
        fun sized(text: String, targetInk: Float, color: Int): Sized {
            val paint = Paint(base)
            paint.color = color
            paint.textSize = 100f
            val ref = if (isCjk(text.firstOrNull())) "国" else "0"
            val probe = Rect()
            paint.getTextBounds(ref, 0, ref.length, probe)
            paint.textSize = 100f * targetInk / probe.height().toFloat().coerceAtLeast(1f)
            val bounds = Rect()
            paint.getTextBounds(text, 0, text.length, bounds)
            return Sized(text, paint, bounds)
        }
    }

    class Sized(val text: String, val paint: Paint, val bounds: Rect) {
        val inkHeight: Float get() = bounds.height().toFloat()

        /**
         * 绘制。混排（CJK+拉丁/数字）时按 run 拆开、**墨迹底边对齐**后逐 run 画——
         * 单次 drawText 时 CJK 回退字形的墨迹会沉到拉丁基线以下（用户报「字符漂移」）：
         * 数字/字母贴基线，汉字却往下坠一截。纯单种文字走原路径，输出与旧版完全一致。
         * 放在 draw() 本体里，所有调用方（今日/多天总览/大时钟/屏幕使用时间）自动继承。
         */
        fun draw(canvas: Canvas, x: Float, inkTop: Float) {
            val runs = splitRuns(text)
            if (runs.size <= 1) {
                canvas.drawText(text, x, inkTop - bounds.top, paint)
                return
            }
            val commonBottom = (inkTop - bounds.top) + bounds.bottom
            var cx = x
            val rb = Rect()
            for ((_, seg) in runs) {
                paint.getTextBounds(seg, 0, seg.length, rb)
                canvas.drawText(seg, cx, commonBottom - rb.bottom, paint)
                cx += paint.measureText(seg)
            }
        }

        /** 兼容别名：对齐逻辑已下沉到 [draw] */
        fun drawAligned(canvas: Canvas, x: Float, inkTop: Float) = draw(canvas, x, inkTop)

        /** 水平居中于 [cx]（按墨迹，不是 advance），混排走 [drawAligned] */
        fun drawCentered(canvas: Canvas, cx: Float, inkTop: Float) {
            drawAligned(canvas, cx - (bounds.left + bounds.right) / 2f, inkTop)
        }

        /** 右对齐于 [right]（按墨迹右沿） */
        fun drawRight(canvas: Canvas, right: Float, inkTop: Float) {
            drawAligned(canvas, right - bounds.right, inkTop)
        }

        fun ellipsize(maxWidth: Float): Sized {
            if (paint.measureText(text) <= maxWidth) return this
            var t = text
            while (t.length > 1 && paint.measureText(t + "…") > maxWidth) t = t.dropLast(1)
            val bounds = Rect()
            paint.getTextBounds(t + "…", 0, t.length + 1, bounds)
            return Sized(t + "…", paint, bounds)
        }
    }

    /** CJK/非 CJK 连续段拆分（drawAligned 用） */
    private fun splitRuns(text: String): List<Pair<Boolean, String>> {
        val out = ArrayList<Pair<Boolean, String>>()
        for (ch in text) {
            val cjk = isCjk(ch)
            if (out.isNotEmpty() && out.last().first == cjk) {
                out[out.lastIndex] = cjk to (out.last().second + ch)
            } else {
                out.add(cjk to ch.toString())
            }
        }
        return out
    }

    private fun bitmapOf(w: Int, h: Int): Pair<Bitmap, Canvas> {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        return bmp to Canvas(bmp)
    }

    private fun px(size: DpSize, ctx: Context): Pair<Int, Int> {
        val density = ctx.resources.displayMetrics.density
        val w = (size.width.value * density).roundToInt().coerceIn(2, 4096)
        val h = (size.height.value * density).roundToInt().coerceIn(2, 4096)
        return w to h
    }

    /** 给同包的其它画布对象（ScreenTimeArt）用的薄封装：保持 px/bitmapOf 私有命名不动 */
    internal fun sizeOf(size: DpSize, ctx: Context): Pair<Int, Int> = px(size, ctx)
    internal fun newBitmap(w: Int, h: Int): Pair<Bitmap, Canvas> = bitmapOf(w, h)

    // ════════════════════════ 今日（合并款） ════════════════════════

    /** 事件分类 → 彩条颜色（参考原生 Calendar schedule：绿=待办/勾过、蓝=定时日程、绿=全天。
     *  我们的三色族映射：待办=主色容器、定时=第三色容器、全天=第二色容器） */
    enum class EventKind { TIMED, TODO_DONE, TODO_PENDING, ALLDAY }

    /**
     * [startMs]/[endMs]：事件绝对时刻（epoch ms）。今日款时间轴按它定位（2026-11-02 改版）；
     * 多天总览的列表条不定位，仍按 [timeText] 显示——默认 0 保旧调用点兼容。
     * [next] 已不被今日款使用（时间轴上「进行中/下一场」由几何位置与 now 线自然表达），
     * 字段保留给多天总览。
     */
    class TodayEvent(
        val kind: EventKind, val title: String, val timeText: String, val next: Boolean = false,
        val startMs: Long = 0L, val endMs: Long = 0L
    )

    /** 「今日待办」一行：未完成待办的标题 + 进度（0..100；负数=没有百分比，不画） */
    class TodoLine(val title: String, val percent: Int = -1)

    /** 跨 now 线块的标题补画登记（遮罩要用所在块的**同款**提亮底色，v51） */
    private class OverLineText(val title: String, val x: Float, val top: Float, val bg: Int)

    class TodayData(
        val dayNumber: String,      // "24"
        val weekday: String,        // "周六"
        val month: String,          // "10月"
        val stats: String,          // "待办 2/5 · 日程 3 场 · 专注 32 分"
        /** 今日款语义（2026-11-02 时间轴改版）：**只含 [nowMs-1h, nowMs+1h] 窗口内的
         *  定时日程（TIMED）与有时刻的待办（TODO_PENDING，v51 起）**；
         *  多天总览语义不变（当日列表）。无时刻待办走 [todos] 圆点区。 */
        val events: List<TodayEvent>,
        val emptyText: String,      // 没有日程时的占位
        /** 时间轴锚点（epoch ms）。0 = 未提供，今日款调用方必传。 */
        val nowMs: Long = 0L,
        /** 2026-11-02 晚用户报「没能显示当前日程、待办」后的补位：
         *  窗口内没有日程时改显示「下一场」（今天晚些时候或明天），永不空转。 */
        val nextText: String = "",
        /** 今日未完成待办（最多渲染 3 行，多出的走 [todoMore] 计数） */
        val todos: List<TodoLine> = emptyList(),
        val todoMore: Int = 0,
        /** v51.1：下一条还没到点的**有时刻**待办（「15:45 标题」）。±1h 窗外的
         *  有时刻待办靠这行保持可见；已进窗成块的不重复出。空串=没有。 */
        val nextTodoText: String = "",
        /**
         * 2026-11-15 用户（截图）：「今天时间轴显示明天日程」「不在时间轴范围内但当天
         * 接下来要经历的计划要单独标出，不要跟时间轴混在一起」。
         *
         * 今天**尚未开始、且落在 ±1h 窗口之外**的日程——按时间定位画进轴里是错的
         * （轴上每个 y 都有"离现在多久"的含义，窗外事件没有这个含义）。改为**轴下方
         * 单独一块**，用分隔线与时间轴切开。
         */
        val upcomingToday: List<TodayEvent> = emptyList(),
        /**
         * 今天已无后续安排、下一场落在**别的日子**时的提示行（「下一场 · 明天 14:00 身体素质」）。
         * 同样画在轴下方的独立块里——原来是居中画在轴中部，看上去就像它是轴上的事件
         * （用户 2026-11-15 截图里的那个绿点 + 「明天 14:00」）。
         */
        val nextOtherDayText: String = ""
    )

    /** 时间轴窗口半径：只展示过去 1 小时到未来 1 小时（2026-11-02 用户定案） */
    internal val TIMELINE_WINDOW_MS: Long = 3600_000L

    /** 窗口命中：事件与 [now]±[windowMs] 有交集（紧邻不算重叠，end==窗口起点仍入窗） */
    internal fun inTimelineWindow(start: Long, end: Long, now: Long, windowMs: Long = TIMELINE_WINDOW_MS): Boolean =
        end > now - windowMs && start < now + windowMs

    /**
     * 时间轴事件的重叠分列（贪心）：按开始时刻排序，每块放进「上一块已结束」的第一个列，
     * 都不空则开新列。返回与输入同序的列号数组。渲染按列数等分宽度，
     * 并行日程（如同时段两场）各占一列不再互相糊盖。
     */
    internal fun assignTimelineColumns(spans: List<Pair<Long, Long>>): IntArray {
        val result = IntArray(spans.size)
        val colEnds = mutableListOf<Long>()
        spans.withIndex().sortedBy { it.value.first }.forEach { (idx, span) ->
            val col = colEnds.indexOfFirst { it <= span.first }
            if (col < 0) {
                colEnds.add(span.second)
                result[idx] = colEnds.size - 1
            } else {
                colEnds[col] = span.second
                result[idx] = col
            }
        }
        return result
    }

    /**
     * 2026-11-02 时间轴改版（用户定案：强制 4×3 / 只看过去 1 小时到未来 1 小时 / 去加号 /
     * 顶部日期、底部小数据行保留）。同日晚上用户报「没能显示当前日程、待办」，补两处：
     *  - 窗口内没有日程时，日程区改显示「下一场」（[TodayData.nextText]，今天晚些时候或明天）；
     *  - 新增**今日待办列表**（3 行定高槽位，未完成空心圈 + 标题 + 百分比，超出收口「还有 n 项」）。
     * 版面：日期行 → 日程区（时间轴/下一场）→ 分隔线+待办 → 底部统计行。
     */
    fun renderToday(
        ctx: Context,
        size: DpSize,
        colors: Colors,
        weight: Int,
        fontKey: String,
        fontScale: Float,
        data: TodayData
    ): Bitmap {
        val (w, h) = px(size, ctx)
        val (bmp, canvas) = bitmapOf(w, h)
        val fs = fontScale.coerceIn(0.85f, 1.25f)
        val tp = TextPainter(ctx, fontKey, weight)
        fun ink(frac: Float) = frac * h * fs

        // 卡面圆角：**ColorOS 连续曲率**（超椭圆 n=3.25，见 Squircle.kt），
        // 半轴 = 固定 26dp × density ——**不按短边比例**，否则不同尺寸的组件
        // 圆角一大一小（2026-11-01 用户「有的大有的小」）。
        canvas.drawPath(
            Squircle.cardPath(w.toFloat(), h.toFloat(), ctx.resources.displayMetrics.density),
            Paint(Paint.ANTI_ALIAS_FLAG).apply { color = colors.card }
        )

        // 2026-11-06 验收返工（A9/A8）：窄卡（tall）上 padX 只有 5.5dp、轴刻度标签
        // 「04:00」左缘距卡边 1.1dp。padX 抬到 ≥8dp 口径；轴位再给刻度文字让位。
        val padX = maxOf(w * 0.055f, h * 0.058f)

        // ── 日期一行（用户 2026-10-26：合成一行）："10/26" 大 + "周一" 小，底对齐。
        //    FAB 已删（2026-11-02），日期行放宽到整卡宽，窄摆位仍整体缩字。──
        val monthNum = data.month.removeSuffix("月")
        val dateMaxW = w - padX * 2f
        var dateInk = ink(0.085f)
        var weekInk = ink(0.056f)
        val gap = w * 0.028f
        run {
            val d0 = tp.sized("$monthNum/${data.dayNumber}", dateInk, colors.onCard)
            val w0 = tp.sized(data.weekday, weekInk, colors.onCard)
            val total = d0.paint.measureText("$monthNum/${data.dayNumber}") + gap +
                w0.paint.measureText(data.weekday)
            if (total > dateMaxW) {
                val k = dateMaxW / total
                dateInk *= k; weekInk *= k
            }
        }
        val datePart = tp.sized("$monthNum/${data.dayNumber}", dateInk, colors.onCard)
        val weekPart = tp.sized(data.weekday, weekInk, colors.onCard)
        datePart.draw(canvas, padX, h * 0.055f)
        weekPart.draw(canvas, padX + datePart.paint.measureText("$monthNum/${data.dayNumber}") + gap,
            h * 0.055f + (dateInk - weekInk))

        // ── 主体：**整卡一条时间轴**（2026-11-03 用户「做成时间轴样式」）──
        //  改造前是「上半 ±1h 时刻轴 + 下半 3 行待办列表 + 底部统计」三块拼接，
        //  时间轴只占半张卡、还要挤三档刻度（22:49/23:49/00:49 这类噪声）。
        //  现在统一成一条轴：
        //   · 轴左=整点刻度（-1h/now/+1h 落在整点格上时显示真实时刻，其余用 now 标线表达）
        //   · 轴右=定时日程块（按起止时刻定位、并行贪心分列、跨 now 线=m 进行中）
        //   · 轴尾=「不限时」区：无时刻待办作为圆点行挂在轴末端，仍是时间轴的一段
        //     （不会说话的两块版式被合掉，待办不再"另开一块"）
        //   · now 主色线 + 轴上圆点贯穿全轴
        val top = h * 0.185f
        val bottom = h * 0.855f
        val tickProbe = tp.sized("00:00", ink(0.028f), colors.sub)
        val axisX = padX + maxOf(
            w * 0.105f,
            tickProbe.paint.measureText("00:00") + w * 0.030f
        )
        val evLeft = axisX + w * 0.032f
        val evRight = w - padX
        val now = if (data.nowMs > 0) data.nowMs else System.currentTimeMillis()
        val winStart = now - TIMELINE_WINDOW_MS
        val winSpan = TIMELINE_WINDOW_MS * 2f

        // 轴尾给无时刻待办留槽：最多 3 行槽位（超出时第 3 槽变「还有 n 项」），槽高随条数走，日程区相应上收。
        // v51.1：下一条约好时刻的待办（nextTodoText）也占一行——窗外的有时刻待办
        // 不能因为不进 ±1h 窗就从组件里消失。
        val overflow = data.todoMore > 0 || data.todos.size > 3
        val untimedShown = if (overflow) data.todos.take(2) else data.todos.take(3)
        val untimedMore = data.todoMore + (data.todos.size - untimedShown.size).coerceAtLeast(0)
        val untimedInk = ink(0.036f)
        val untimedRowH = untimedInk * 1.95f
        val untimedRows = if (untimedShown.isEmpty() && data.nextTodoText.isBlank()) 0
            else untimedShown.size + (if (untimedMore > 0) 1 else 0) + (if (data.nextTodoText.isNotBlank()) 1 else 0)
        val untimedH = if (untimedRows == 0) 0f else untimedRowH * untimedRows + h * 0.022f

        // ── 轴下「接下来」块（2026-11-15 用户：不在 ±1h 时间轴内、但当天接下来要经历的
        //    安排要**单独标出**，不要跟时间轴混在一起）──
        //  轴内每个 y 都隐含"离现在多久"，窗外事件放进去会被读成"轴上的某一刻"
        //  （用户截图里「明天 14:00 身体素质」正是这么被误读的）。这里另起一块：
        //    · 今天窗外的场次（upcomingToday，最多 2 行，时刻主色 + 标题）
        //    · 今天已无安排时退化为「下一场 · 明天 …」（nextOtherDayText）
        //  块高从日程区里扣除，与时间轴用分隔线切开。
        val leadLines = buildList {
            data.upcomingToday.take(2).forEach { ev ->
                val st = java.time.Instant.ofEpochMilli(ev.startMs)
                    .atZone(io.github.m0use001.planability.core.AppTime.zone)
                add("%02d:%02d".format(st.hour, st.minute) to ev.title)
            }
            if (isEmpty() && data.nextOtherDayText.isNotBlank()) {
                add("" to data.nextOtherDayText)
            }
        }
        val leadInk = ink(0.036f)
        val leadRowH = leadInk * 1.95f
        val leadH = if (leadLines.isEmpty()) 0f else leadRowH * leadLines.size + h * 0.030f

        val schedBottom = bottom - untimedH - leadH

        // t(epoch ms) → y(px)：窗口顶端=now-1h、底端=now+1h（只在日程区）
        fun yOf(t: Long) = top + (t - winStart) / winSpan * (schedBottom - top)

        fun fmt(t: Long): String {
            val dt = java.time.Instant.ofEpochMilli(t).atZone(io.github.m0use001.planability.core.AppTime.zone)
            return "%02d:%02d".format(dt.hour, dt.minute)
        }

        // 轴竖线贯穿全高（含不限时区）——一块连续的轴，不是两段
        canvas.drawLine(axisX, top, axisX, bottom,
            Paint(Paint.ANTI_ALIAS_FLAG).apply { color = colors.sub; alpha = 55; strokeWidth = w * 0.003f })

        // 左侧刻度：只画**整点**（窗口内的每个整点），比三档 dead tick 干净。
        // now 那格主色，其余 sub。整点处同时画一条横贯日程区的淡引导线——
        // 时间轴的可读性靠"横向等距节拍"，只给文字没有线，块的位置感仍然飘。
        run {
            val tickInk = ink(0.028f)
            val zone = io.github.m0use001.planability.core.AppTime.zone
            val startHour = java.time.Instant.ofEpochMilli(winStart).atZone(zone)
                .withMinute(0).withSecond(0).withNano(0)
            val nowHour = java.time.Instant.ofEpochMilli(now).atZone(zone).hour
            var t = startHour.toInstant().toEpochMilli()
            val guide = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = colors.sub; alpha = 30; strokeWidth = w * 0.0022f
            }
            val dash = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                strokeCap = Paint.Cap.ROUND
                strokeWidth = w * 0.0028f
            }
            while (t <= now + TIMELINE_WINDOW_MS) {
                if (t >= winStart) {
                    val isNowHour = java.time.Instant.ofEpochMilli(t).atZone(zone).hour == nowHour
                    val y = yOf(t)
                    if (y in (top + tickInk)..(schedBottom - tickInk)) {
                        canvas.drawLine(evLeft, y, evRight, y, guide)
                        dash.color = if (isNowHour) colors.primary else colors.sub
                        dash.alpha = if (isNowHour) 200 else 110
                        canvas.drawLine(axisX - w * 0.010f, y, axisX + w * 0.010f, y, dash)
                        tp.sized(fmt(t), tickInk, if (isNowHour) colors.primary else colors.sub)
                            .drawRight(canvas, axisX - w * 0.020f, y - tickInk / 2f)
                    }
                }
                t += 3600_000L
            }
        }

        if (data.events.isEmpty()) {
            // 2026-11-15 用户：「今天时间轴显示明天日程」——空态原来把 nextText（可能是
            // **明天**的场次）直接画在轴中部，读者会以为它就是轴上那一刻的事件。
            // 现在轴内只说"这一小时确实空着"；「接下来」另有一块（轴下方）表达。
            val dotInk = ink(0.040f)
            val dotR = dotInk * 0.22f
            val dotX = evLeft + dotR
            val cy = (top + schedBottom) / 2f
            canvas.drawCircle(dotX, cy + dotInk * 0.36f, dotR,
                Paint(Paint.ANTI_ALIAS_FLAG).apply { color = colors.sub; alpha = 120 })
            tp.sized(data.emptyText, dotInk, colors.sub)
                .ellipsize(evRight - (dotX + dotR) - w * 0.03f)
                .draw(canvas, dotX + dotR + w * 0.03f, cy)
        } else {

            // 窗口内事件（数据层已过滤，这里再钳一次防越界），clamp 后贪心分列
            val winEnd = now + TIMELINE_WINDOW_MS
            val clamped = data.events.map { ev ->
                Triple(ev, maxOf(ev.startMs, winStart), minOf(ev.endMs, winEnd))
            }
            val cols = assignTimelineColumns(clamped.map { it.second to it.third })
            val colN = (cols.maxOrNull() ?: 0) + 1
            val colW = (evRight - evLeft) / colN
            val titleInk = ink(0.032f)
            val timeInk = ink(0.026f)
            val nowY = yOf(now)
            // 跨 now 线的块：标题先记下，画完 now 线再补一层「遮罩 + 标题」——
            // 直接压线会把标题切一半（2026-11-02 晚实拍「英语角」正被绿线穿过）
            val overLineTexts = mutableListOf<OverLineText>()
            clamped.forEachIndexed { i, (ev, s, e) ->
                val col = cols[i]
                val left = evLeft + colW * col
                val right = left + colW * 0.94f
                val yTop = yOf(s)
                val yBot = yOf(e)
                // 最小可读高度：短日程（15 分钟）也放得下一行标题
                val blockTop = minOf(yTop, (yTop + yBot) / 2f - titleInk * 0.8f)
                    .coerceIn(top, bottom - titleInk * 1.6f)
                val blockBot = maxOf(yBot, blockTop + titleInk * 1.6f)
                    .coerceAtMost(bottom)
                if (blockBot - blockTop < titleInk * 0.4f) return@forEachIndexed
                // v51：日程块=第三色容器、有时刻待办块=第二色容器（两类一眼可分），
                // 都过 blockBg 对比度闸（用户「内容颜色与背景混在一起」）
                val blockBgColor = if (ev.kind == EventKind.TODO_PENDING)
                    colors.secondaryContainer else colors.tertiaryContainer
                val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = blockBg(blockBgColor, colors.card)
                }
                canvas.drawRoundRect(
                    RectF(left, blockTop, right, blockBot),
                    titleInk * 0.5f, titleInk * 0.5f, bgPaint
                )
                val textLeft = left + colW * 0.055f
                // 块内文字用卡片前景（textOnCard 统一 token 的 ARGB 源）：拼色方案下
                // onTertiaryContainer 与容器底同调偏暗、对比不足（2026-11-02 真机实拍）；
                // 块底色本身已表达事件分类，文字可读性优先。
                val onBg = colors.onCard
                // now 线横穿块内时（进行中的日程）只画标题：时间行必然被线切一半
                //（2026-11-02 晚实拍「23:29–00:14」被 now 线压穿），进行中的语义由
                // 块跨线本身表达，不需要时间行再重复
                val straddlesNow = blockTop < nowY - titleInk * 0.15f && nowY < blockBot - titleInk * 0.15f
                // v50.6（视觉验收第 1 轮 FAIL）：两行阈值原来是 2.3×titleInk——但两行的
                // 实际排版需求只有 1.55×titleInk（时间行基线）+ 0.55×timeInk（下延+呼吸）
                // ≈31px。阈值比需求大 5px，导致 35.7px 高的块（窗口内可见 40 分钟的日程，
                // 如「写周报」）差 0.4px 被判单行、时间行随机丢失。改按**实际需求**判：
                // 放得下就显示两行。
                val twoLineNeed = titleInk * 1.55f + timeInk * 0.55f
                if (blockBot - blockTop >= twoLineNeed && !straddlesNow) {
                    // 高块两行：标题 + 起止时间
                    // 2026-11-06 视觉验收返工（评审实测标题与时间整段重叠：重叠矩形内
                    // 标题 84px + 时间 74px 像素互压 → C2/A8 FAIL）：原来时间行写死在
                    // `titleInk * 1.55f` 的**名义字号**上，而实际字形高度（含 ascent/descent）
                    // 比 titleInk 大，时间行就顶穿标题下沿。改成按**实测字形高度**往下排，
                    // 并且放不下时宁可不画时间行，也不压字。
                    val titleS = tp.sized(ev.title, titleInk, onBg)
                        .ellipsize(right - textLeft - w * 0.02f)
                    titleS.draw(canvas, textLeft, blockTop + titleInk * 0.42f)
                    val titleGlyphH = titleS.bounds.height().toFloat().coerceAtLeast(titleInk)
                    val timeS = tp.sized(ev.timeText, timeInk, onBg)
                        .ellipsize(right - textLeft - w * 0.02f)
                    timeS.paint.alpha = 185   // 时间行比标题退一档，层级更清晰
                    val timeTop = blockTop + titleInk * 0.42f + titleGlyphH + timeInk * 0.18f
                    if (timeTop + timeInk <= blockBot) timeS.draw(canvas, textLeft, timeTop)
                } else {
                    // 跨 now 线的块：标题顶对齐，并登记到 overLineTexts（线画完再压一次）
                    val titleTop = if (straddlesNow) blockTop + titleInk * 0.22f
                    else (blockTop + blockBot - titleInk) / 2f
                    val titleTp = tp.sized(ev.title, titleInk, onBg)
                        .ellipsize(right - textLeft - w * 0.02f)
                    if (straddlesNow) {
                        overLineTexts.add(OverLineText(titleTp.text, textLeft, titleTop,
                            blockBg(blockBgColor, colors.card)))
                    }
                    titleTp.draw(canvas, textLeft, titleTop)
                }
            }

            // now 线：画在事件块**之后**（压块上）——先画会被进行中的块盖住，
            // 「当前时刻」在块内不可见（2026-11-02 模拟器渲染实拍）
            canvas.drawLine(axisX, nowY, evRight, nowY,
                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = colors.primary; strokeWidth = w * 0.006f; strokeCap = Paint.Cap.ROUND
                })
            canvas.drawCircle(axisX, nowY, w * 0.010f,
                Paint(Paint.ANTI_ALIAS_FLAG).apply { color = colors.primary })

            // 跨线块标题补遮罩：now 线在文字处断开（线从字后过），标题不再被切成两半。
            // v51：遮罩色用所在块的**同款提亮底色**（原来写死 tertiaryContainer 原值，
            // 块底过闸后遮罩反而比块暗，跨线标题四周出现一圈更深的补丁）
            overLineTexts.forEach { olt ->
                val tt = tp.sized(olt.title, titleInk, colors.onCard)
                val textW = tt.paint.measureText(olt.title)
                canvas.drawRoundRect(
                    RectF(olt.x - w * 0.012f, olt.top - titleInk * 0.18f,
                        olt.x + textW + w * 0.012f, olt.top + titleInk * 1.02f),
                    titleInk * 0.28f, titleInk * 0.28f,
                    Paint(Paint.ANTI_ALIAS_FLAG).apply { color = olt.bg }
                )
                tt.draw(canvas, olt.x, olt.top)
            }
        }

        // ── 轴下「接下来」块：与时间轴用一条分隔线切开，明确"这里不是轴上的时刻" ──
        if (leadLines.isNotEmpty()) {
            val sepY = schedBottom + h * 0.012f
            canvas.drawLine(
                evLeft, sepY, evRight, sepY,
                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = colors.sub; alpha = 70; strokeWidth = w * 0.0022f
                }
            )
            // 小标签：说明这块是什么（避免又被读成时间轴的一部分）
            tp.sized("接下来", ink(0.026f), colors.sub).draw(canvas, evLeft, sepY + h * 0.008f)
            val rowsTop = sepY + h * 0.008f + ink(0.026f) * 1.6f
            leadLines.forEachIndexed { i, (timePart, titlePart) ->
                val cy = rowsTop + leadRowH * i + leadRowH / 2f
                // 空心圈（与待办区同形但中性色）：表示"计划中、未开始"
                val r = leadInk * 0.26f
                canvas.drawCircle(
                    evLeft + r, cy, r,
                    Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = colors.sub; style = Paint.Style.STROKE; strokeWidth = r * 0.55f
                    }
                )
                val textX = evLeft + r * 2f + w * 0.022f
                if (timePart.isNotBlank()) {
                    // 有具体时刻：时刻主色 + 标题卡片前景（与轴内块的时间/标题同色语义）
                    val tS = tp.sized(timePart, leadInk, colors.primary)
                    tS.draw(canvas, textX, cy - leadInk / 2f)
                    val tx = textX + tS.paint.measureText(timePart) + w * 0.022f
                    tp.sized(titlePart, leadInk, colors.onCard)
                        .ellipsize(evRight - tx)
                        .draw(canvas, tx, cy - leadInk / 2f)
                } else {
                    // 「下一场 · 明天 14:00 身体素质」这类整句：中性色，不抢轴内语义
                    tp.sized(titlePart, leadInk, colors.sub)
                        .ellipsize(evRight - textX)
                        .draw(canvas, textX, cy - leadInk / 2f)
                }
            }
        }

        // ── 轴尾「不限时」区：无时刻待办挂轴末（圆点行，与日程块同语言但不着色）──
        //  无时刻待办无法按 t 定位，挂轴尾表达「今天还要做、顺序不限」。
        //  超出 3 条在第 3 行收口「还有 n 项」；末行是下一条**有时刻**待办
        //  （v51.1：时刻主色——窗外的有时刻待办不能因为不进 ±1h 窗就消失）。
        if (untimedRows > 0) {
            val zoneTop = schedBottom + leadH + h * 0.018f
            // 分隔刻度：轴上一个小方块示意「不限时段」起点
            canvas.drawCircle(axisX, zoneTop - h * 0.014f, w * 0.005f,
                Paint(Paint.ANTI_ALIAS_FLAG).apply { color = colors.sub })
            untimedShown.forEachIndexed { i, todo ->
                val cy = zoneTop + untimedRowH * i + untimedRowH / 2f
                val r = untimedInk * 0.30f
                // 空心小圆圈 = 未完成（勾选框的静态表达；位图上不能交互，语义留在形状里）
                canvas.drawCircle(evLeft + r, cy, r,
                    Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = colors.primary; style = Paint.Style.STROKE
                        strokeWidth = r * 0.55f
                    })
                val textX = evLeft + r * 2f + w * 0.022f
                val pctText = if (todo.percent in 1..99) "${todo.percent}%" else ""
                val pctW = if (pctText.isNotBlank())
                    tp.sized(pctText, ink(0.030f), colors.sub).paint.measureText(pctText) + w * 0.018f
                else 0f
                tp.sized(todo.title, untimedInk, colors.onCard)
                    .ellipsize(evRight - textX - pctW)
                    .draw(canvas, textX, cy - untimedInk / 2f)
                if (pctText.isNotBlank()) {
                    tp.sized(pctText, ink(0.030f), colors.sub)
                        .drawRight(canvas, evRight, cy - ink(0.030f) / 2f)
                }
            }
            val more = untimedMore
            if (more > 0) {
                val cy = zoneTop + untimedRowH * untimedShown.size + untimedRowH / 2f
                tp.sized("还有 $more 项待办", untimedInk * 0.92f, colors.sub)
                    .ellipsize(evRight - evLeft)
                    .draw(canvas, evLeft + untimedInk * 0.60f + w * 0.022f,
                        cy - untimedInk * 0.92f / 2f)
            }
            // 下一条约好时刻的待办：时刻用主色（区别于不限时行的空心圈）
            if (data.nextTodoText.isNotBlank()) {
                val rowIdx = untimedShown.size + if (more > 0) 1 else 0
                val cy = zoneTop + untimedRowH * rowIdx + untimedRowH / 2f
                val timeLen = data.nextTodoText.indexOf(' ').coerceAtLeast(1)
                val timePart = data.nextTodoText.take(timeLen)
                val titlePart = data.nextTodoText.drop(timeLen + 1)
                val timeS = tp.sized(timePart, untimedInk, colors.primary)
                timeS.draw(canvas, evLeft, cy - untimedInk / 2f)
                val tx = evLeft + timeS.paint.measureText(timePart) + w * 0.022f
                tp.sized(titlePart, untimedInk, colors.onCard)
                    .ellipsize(evRight - tx)
                    .draw(canvas, tx, cy - untimedInk / 2f)
            }
        }

        // ── 底部：统计一行，**水平居中**（用户 2026-10-26）──
        // ink_top 只到 0.925：fontScale 放大后墨迹会往下长，再低就压到卡底圆角上（实拍教训）
        tp.sized(data.stats, ink(0.036f), colors.sub)
            .ellipsize(w - padX * 2f)
            .drawCentered(canvas, w / 2f, h * 0.925f)
        return bmp
    }

    // ════════════════════════ 多天总览（3 天 / 1 周 / 1 月） ════════════════════════

    /** 范围档位：下标即持久化的值（0/1/2），点右上角胶囊循环切换 */
    enum class Range(val label: String, val title: String) {
        ThreeDays("3 天", "近 3 天"),
        Week("周", "本周"),
        Month("月", "")
    }

    /** 月视图的一格：[eventKinds] 是该日事件的分类（按 [EventKind] 序号，最多 3 条，超出略） */
    class MonthCell(
        val label: String,
        val inMonth: Boolean,
        val isToday: Boolean,
        val eventKinds: List<Int>
    )

    /** 3 天 / 1 周视图的一列 */
    class DayColumn(
        val head: String,           // 星期："六"（周款）/ "周六"（3 天款）
        val dateLabel: String,      // 日号："24"
        val events: List<TodayEvent>,
        val isToday: Boolean
    )

    class RangeData(
        val range: Range,
        val monthTitle: String,     // 卡面主标题：月档="2026年11月"；3 天/周档="10/31–11/2"（空串回退 range.title）
        val monthCells: List<MonthCell>?,   // 月视图：42 格（含周首）
        val columns: List<DayColumn>?,      // 3 天 / 1 周
        val footer: String
    )

    fun renderRange(
        ctx: Context,
        size: DpSize,
        colors: Colors,
        weight: Int,
        fontKey: String,
        fontScale: Float,
        data: RangeData
    ): Bitmap {
        val (w0, h0) = px(size, ctx)
        // 渲染尺寸封顶（2026-11-01）：ColorOS 对 4×4 报 412dp（1443px @3.5x），
        // 比实际占位（~276dp）虚大 ~50%，位图被桌面 FillBounds 压回显示——按虚报尺寸
        // 渲是 8.3MB/张，预热三档直接顶爆 24MB 位图 LRU，切档永远缓存未命中重画。
        // 内容全部按 w/h 比例绘制，封顶后压回真实占位视觉无损。
        val cap = 1080f
        val scale = minOf(cap / w0, cap / h0, 1f)
        val w = (w0 * scale).roundToInt().coerceAtLeast(2)
        val h = (h0 * scale).roundToInt().coerceAtLeast(2)
        val (bmp, canvas) = bitmapOf(w, h)
        val fs = fontScale.coerceIn(0.85f, 1.25f)
        val tp = TextPainter(ctx, fontKey, weight)
        fun ink(frac: Float) = frac * h * fs

        // 卡面圆角：ColorOS 连续曲率（同 renderToday，固定 26dp，见 Squircle.kt）
        canvas.drawPath(
            Squircle.cardPath(w.toFloat(), h.toFloat(), ctx.resources.displayMetrics.density),
            Paint(Paint.ANTI_ALIAS_FLAG).apply { color = colors.card }
        )

        val padX = w * 0.05f
        // 胶囊几何 **必须与 RangeSegmentHotSpots 的比例网格严格同式**（2026-11-01）：
        // 热区是 RemoteViews 里的 weight 网格（相对真实视图），位图是 FillBounds 压进
        // 同一视图——两边都按「卡宽/高的分数」定义才能对齐。ColorOS 谎报组件尺寸
        // （4×4 报 412dp>屏宽 360dp），任何绝对 dp 都会对不上。
        // 横向：胶囊区 = 右 1/3（x: 0.667w..1.0w），三段各 0.1111w；
        // 纵向：热区带 = 高的 6.7%..20%（见热区注释），胶囊画在 0.08h..0.16h 居其中。
        val zoneLeft = w * 0.6667f

        // ── 头：标题（左）+ 范围胶囊（右，点击切换）──
        // 2026-11-04 视觉验收第 1 轮（独立评审 agentB）B6(a)：计数行原来画在标题行右端
        // （11-02「撤底部行、计数并入标题行」），但月档标题「2026年11月」很长，右侧净宽
        // 只剩 ~120px，计数被截成「7⋯」直接叠在「月」字上。计数改回**底部一行**
        // （见主体之后），标题行只留标题+胶囊，互不抢位。
        // 2026-11-15 用户（截图）：「这个组件顶部排的乱七八糟的」——标题「11/9–11/15」
        // 与右侧范围胶囊在同一行上**互相抢位**：标题按 0.062h 定尺后宽度不受约束，
        // 长标题（月档「2026年11月」、周档「11/9–11/15」）直接压到胶囊下方/左侧；
        // 加上胶囊起点 0.6667w 硬切，视觉上就是"两坨东西叠着"。
        // 修：①先量标题宽度，超可用宽（到胶囊左侧再留 8% 空隙）就等比缩字；
        //     ②标题与胶囊**垂直居中对齐**在同一水平带宽内，不再各画各的基线。
        val title = data.monthTitle.ifBlank { data.range.title }
        var titleInk = ink(0.062f)
        val titleMaxW = zoneLeft - padX - w * 0.03f
        run {
            val probe = tp.sized(title, titleInk, colors.onCard)
            val mw = probe.paint.measureText(title)
            if (mw > titleMaxW && mw > 0f) titleInk *= (titleMaxW / mw)
        }

        val chipH = h * 0.08f
        val segW = (w - zoneLeft) / Range.entries.size
        val chipLeft = zoneLeft + w * 0.007f
        val chipRight = w - w * 0.007f
        val chipTop = h * 0.08f
        // 标题与胶囊同带垂直居中（胶囊中线 = 视觉基准线）。
        // draw() 的第二参是**墨迹顶**（不是基线、也不是中心），所以要用实际墨迹高算：
        // inkTop = 胶囊顶 + (胶囊高 - 标题墨迹高)/2。
        val titleSized = tp.sized(title, titleInk, colors.onCard)
        titleSized.draw(canvas, padX, chipTop + (chipH - titleSized.inkHeight) / 2f)
        val chip = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = colors.primaryContainer }
        canvas.drawRoundRect(
            RectF(chipLeft, chipTop, chipRight, chipTop + chipH),
            chipH / 2f, chipH / 2f, chip
        )
        // 标签按 chipH 定尺（不是卡高）：方形/竖长摆位下卡高相对卡宽变大，
        // 按卡高定尺会让「3天」比选中药丸还宽、深色字伸出药丸外（实拍教训）
        Range.entries.forEachIndexed { i, r ->
            val label = tp.sized(r.label, chipH * 0.40f, 0)
            val cx = zoneLeft + segW * (i + 0.5f)
            val cy = chipTop + chipH / 2f
            if (i == data.range.ordinal) {
                val sel = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = colors.primary }
                val selW = segW * 0.86f
                canvas.drawRoundRect(
                    RectF(cx - selW / 2f, chipTop + chipH * 0.10f, cx + selW / 2f, chipTop + chipH * 0.90f),
                    chipH * 0.40f, chipH * 0.40f, sel
                )
                label.paint.color = colors.card
            } else {
                label.paint.color = colors.onPrimaryContainer
            }
            label.drawCentered(canvas, cx, cy - label.inkHeight / 2f)
        }

        // ── 主体（页脚已并入标题行 → 主体直落到卡底，给日程腾出行高）──
        val bodyTop = h * 0.165f
        val bodyBottom = h * 0.95f
        when (data.range) {
            Range.Month -> drawMonth(canvas, tp, data, colors, w, h, padX, bodyTop, bodyBottom) { ink(it) }
            else -> drawColumns(canvas, tp, data, colors, w, h, padX, bodyTop, bodyBottom) { ink(it) }
        }
        // 底部汇总行（2026-11-04 验收第 1 轮 agentB B5：4×4 底部 152px 全空、
        // 汇总信息缺失）。主体到 0.95h，计数画在其下贴卡底，月/周/3 天共用。
        if (data.footer.isNotBlank()) {
            val countInk = ink(0.030f)
            tp.sized(data.footer, countInk, colors.sub)
                .draw(canvas, padX, h * 0.958f)
        }
        return bmp
    }

    /**
     * 月视图（参考原生 Calendar month view）：细线表格，日号在格子**左上**，
     * 今天=主色圆环（不是圆底），格内靠上是该日事件的**小彩条**（颜色按分类轮换，
     * 原生是多日历多色；我们按条序轮换三容器色）。超出 2 条加「+n」。
     */
    private fun drawMonth(
        canvas: Canvas,
        tp: TextPainter,
        data: RangeData,
        colors: Colors,
        w: Int, h: Int,
        padX: Float,
        top: Float, bottom: Float,
        ink: (Float) -> Float
    ) {
        val cells = data.monthCells ?: return
        val weekdays = listOf("一", "二", "三", "四", "五", "六", "日")
        val colW = (w - 2 * padX) / 7f
        weekdays.forEachIndexed { i, d ->
            tp.sized(d, ink(0.040f), colors.sub)
                .drawCentered(canvas, padX + colW * (i + 0.5f), top)
        }
        val gridTop = top + h * 0.058f
        val rows = ((cells.size + 6) / 7).coerceAtLeast(1)
        val rowH = (bottom - gridTop) / rows

        // 2026-10-30 用户（参考原生月历组件）：**去掉表格线**——格与格靠留白分隔，
        // 画了细线反而像表格，月历要的是"漂浮的日期"。原横 7 竖 8 条线删除。

        val barH = min(rowH * 0.16f, h * 0.018f)
        val barColors = listOf(colors.primaryContainer, colors.tertiaryContainer,
            colors.secondaryContainer)
        cells.forEachIndexed { i, cell ->
            val col = i % 7
            val row = i / 7
            val cellL = padX + colW * col
            val cellT = gridTop + rowH * row
            // 日号：格子左上（原生同位）。今天=**主色实心圆**，数字画在圆心里
            // （2026-10-30 用户参考原生月历组件：实心圆形、内部显示文字；原为圆环）
            val num = tp.sized(cell.label, ink(0.040f), colors.onCard)
            val numCx = cellL + w * 0.014f + num.paint.measureText(cell.label) / 2f
            val numCy = cellT + h * 0.012f + num.inkHeight / 2f
            val discR = num.inkHeight * 0.95f
            if (cell.isToday) {
                canvas.drawCircle(
                    numCx, numCy, discR,
                    Paint(Paint.ANTI_ALIAS_FLAG).apply { color = colors.primary }
                )
                num.paint.color = colors.onPrimary
            } else if (!cell.inMonth) {
                num.paint.alpha = 90
            }
            num.draw(canvas, cellL + w * 0.014f, cellT + h * 0.012f)
            num.paint.alpha = 255
            // 事件彩条：最多 3 条（2026-11-01 锁 4×4 后格高富余，从 2 条提到 3 条）；
            // 超出把「+n」放在日号行右端。
            // 2026-11-04 验收第 1 轮 agentB B6(b)：今天格的彩条顶(0.048h)高于今日圆底，
            // ≈80% 圆冠被条盖住——今天格的条区从圆底之下起。
            val barTop0 = if (cell.isToday) {
                maxOf(cellT + h * 0.048f, numCy + discR + h * 0.004f)
            } else {
                cellT + h * 0.048f
            }
            cell.eventKinds.take(3).forEachIndexed { j, kind ->
                val barTop = barTop0 + j * (barH + h * 0.004f)
                canvas.drawRoundRect(
                    RectF(cellL + w * 0.008f, barTop, cellL + colW - w * 0.008f, barTop + barH),
                    barH / 2f, barH / 2f,
                    Paint(Paint.ANTI_ALIAS_FLAG).apply { color = barColors[kind % 3] }
                )
            }
            if (cell.eventKinds.size > 3) {
                tp.sized("+${cell.eventKinds.size - 3}", ink(0.026f), colors.sub)
                    .drawRight(canvas, cellL + colW - w * 0.010f, cellT + h * 0.018f)
            }
        }
    }

    /**
     * 3 天 / 1 周视图 v2（2026-11-05 视觉验收改版，用户方向 1+2）：
     * 主体从「行槽位」改为**共享垂直时间轴**——每列事件条 y 按开始时间线性映射
     * （早在上晚在下），左缘时刻刻度等距（HH:MM），时间重叠的事件按 lane 等分列宽
     * 错开不叠盖；今天列画「现在」参考线；头部与轴区之间加分隔线明确区域。
     */
    private fun drawColumns(
        canvas: Canvas,
        tp: TextPainter,
        data: RangeData,
        colors: Colors,
        w: Int, h: Int,
        padX: Float,
        top: Float, bottom: Float,
        ink: (Float) -> Float = { it }
    ) {
        val cols = data.columns ?: return
        val n = cols.size.coerceAtLeast(1)
        val barColors = listOf(colors.primaryContainer to colors.onPrimaryContainer,
            colors.tertiaryContainer to colors.onTertiaryContainer,
            colors.secondaryContainer to colors.onSecondaryContainer)

        // ── 列头（星期 + 日号，今天=主色圆盘）──
        val headTop = top + h * 0.005f
        val headBottom = top + h * 0.128f
        // 区域分隔线（方向 1）：画在**标题行+胶囊（控制区）与列头（数据区）之间**。
        // v50.7（用户「标题栏、时间轴要分割出来」）加粗到 w×0.004、α110 后暴露旧位置错误：
        // 原 y=0.148h **横穿右侧胶囊**（胶囊占 0.08..0.16h，线在其内）——旧 α62/1px 时
        // 看不出来而已。胶囊底 0.16h、列头顶 0.17h，净空带中线 = 0.1635h。
        val sepY = h * 0.1635f
        val sep = Paint().apply { color = colors.sub; alpha = 110; strokeWidth = w * 0.004f }
        canvas.drawLine(padX, sepY, w - padX, sepY, sep)

        // ── 时间轴几何：左缘留刻度 gutter，事件条 y = 开始分钟线性映射 ──
        val gutter = w * 0.088f
        val colsLeft = padX + gutter
        val axisTop = headBottom + h * 0.022f
        val axisBottom = bottom - h * 0.035f
        val startMins = cols.flatMap { c -> c.events.mapNotNull { parseHHMM(it.timeText) } }
        val axisStart = (((startMins.minOrNull() ?: 480) - 40).coerceAtLeast(0) / 30) * 30
        val axisEnd = (((startMins.maxOrNull() ?: 1200) + 60).coerceAtMost(1439) / 30) * 30
        val span = (axisEnd - axisStart).coerceAtLeast(480)
        fun yOf(min: Int): Float =
            axisTop + (min - axisStart).coerceIn(0, span).toFloat() * (axisBottom - axisTop) / span

        // 刻度：轴内取整 30min 点（span/4 步长，至少 30min），相邻等距；淡横线全宽
        val tickStep = (((span / 4) / 30).coerceAtLeast(1)) * 30
        val firstTick = ((axisStart / tickStep) + 1) * tickStep
        val tickTextInk = min(ink(0.030f), gutter * 0.62f)
        var tick = firstTick
        while (tick <= axisEnd) {
            val y = yOf(tick)
            val lineP = Paint().apply { color = colors.sub; alpha = 38; strokeWidth = 1f }
            canvas.drawLine(colsLeft, y, w - padX, y, lineP)
            tp.sized("%02d:%02d".format(tick / 60, tick % 60), tickTextInk, colors.sub)
                .drawRight(canvas, padX + gutter * 0.86f, y - tickTextInk / 2f)
            tick += tickStep
        }

        val colW = (w - padX - colsLeft) / n
        val barDur = 60          // 事件条视觉时长（分钟）；无结束时间数据，统一 1h
        val now = java.time.LocalTime.now()
        val nowMin = now.hour * 60 + now.minute
        cols.forEachIndexed { i, col ->
            val left = colsLeft + colW * i
            val cx = left + colW / 2f
            // v50.7（用户）：列头只留星期（周视图=单字「六」），场数「·N」撤除
            val headTp = tp.sized(col.head, ink(0.044f), colors.sub)
            val date = tp.sized(col.dateLabel, ink(0.056f), colors.onCard)
            headTp.drawCentered(canvas, cx, headTop)
            val dateTop = headTop + h * 0.055f
            if (col.isToday) {
                canvas.drawCircle(
                    cx, dateTop + date.inkHeight / 2f, date.inkHeight * 0.95f,
                    Paint(Paint.ANTI_ALIAS_FLAG).apply { color = colors.primary }
                )
                date.paint.color = colors.onPrimary
            }
            date.drawCentered(canvas, cx, dateTop)

            if (col.events.isEmpty()) {
                // 空白天：淡胶囊居轴区上半（语义=该日暂无日程）
                val dotH = h * 0.030f
                val dotY = axisTop + (axisBottom - axisTop) * 0.30f
                canvas.drawRoundRect(
                    RectF(left + colW * 0.10f, dotY - dotH / 2f,
                        left + colW * 0.90f, dotY + dotH / 2f),
                    dotH / 2f, dotH / 2f,
                    Paint(Paint.ANTI_ALIAS_FLAG).apply { color = colors.sub; alpha = 55 }
                )
            }

            // lane 分配：时间重叠（前条 end > 后条 start）的排进下一 lane，
            // lane 数决定条宽（同列条左缘 = left + pad + laneIdx*laneW，等分对齐）
            val sorted = col.events.mapNotNull { ev ->
                parseHHMM(ev.timeText)?.let { Triple(it, it + barDur, ev) }
            }.sortedBy { it.first }
            val lanes = mutableListOf<MutableList<Triple<Int, Int, TodayEvent>>>()
            sorted.forEach { e ->
                val lane = lanes.firstOrNull { l -> l.last().second <= e.first }
                    ?: mutableListOf<Triple<Int, Int, TodayEvent>>().also { lanes.add(it) }
                lane.add(e)
            }
            if (lanes.isNotEmpty()) {
                val laneW = colW / lanes.size
                val barH = (yOf(axisStart + barDur) - yOf(axisStart))
                    .coerceAtMost((axisBottom - axisTop) * 0.20f)
                    .coerceAtLeast(h * 0.024f)
                val textInk = min(ink(0.032f), barH * 0.52f)
                val timeInk = min(ink(0.028f), colW * 0.20f)
                lanes.forEachIndexed { li, lane ->
                    lane.forEach { (_, _, ev) ->
                        val yTop = yOf(parseHHMM(ev.timeText) ?: 0)
                        val (bg, onBg) = barColors[ev.kind.ordinal % 3]
                        val l = left + colW * 0.02f + laneW * li
                        val r = l + laneW * 0.88f
                        if (n <= 3) {
                            // 3 天款：条上标题（左）+ 开始时间（右）；lane 太窄只写标题
                            canvas.drawRoundRect(
                                RectF(l, yTop, r, yTop + barH),
                                barH * 0.22f, barH * 0.22f,
                                Paint(Paint.ANTI_ALIAS_FLAG).apply { color = bg }
                            )
                            // lane 分离的窄条（laneW < 55% 列宽）条内放不下"标题+时间"，
                            // 时间改画**条下**（与周款同构）；单 lane 宽条保持条内右侧
                            val showTimeInline = laneW >= colW * 0.55f && ev.timeText.isNotBlank()
                            val titleW = (r - l) * (if (showTimeInline) 0.62f else 0.86f)
                            tp.sized(ev.title, textInk, onBg)
                                .ellipsize(titleW)
                                .draw(canvas, l + (r - l) * 0.06f, yTop + (barH - textInk) / 2f)
                            if (showTimeInline) {
                                tp.sized(ev.timeText, textInk * 0.92f, onBg)
                                    .drawRight(canvas, r - (r - l) * 0.05f,
                                        yTop + (barH - textInk * 0.92f) / 2f)
                            } else if (ev.timeText.isNotBlank()) {
                                tp.sized(ev.timeText, textInk * 0.88f, colors.sub)
                                    .drawCentered(canvas, (l + r) / 2f, yTop + barH + textInk * 0.10f)
                            }
                        } else {
                            // 周款：纯色小条 + 条下开始时间（列宽放不下字）
                            canvas.drawRoundRect(
                                RectF(l, yTop, r, yTop + barH * 0.72f),
                                barH * 0.24f, barH * 0.24f,
                                Paint(Paint.ANTI_ALIAS_FLAG).apply { color = bg }
                            )
                            // 第 1 轮评审实锤：lane 分离后 laneW≈40px，ellipsize 把
                            // "08:00" 截成 "08…"（分钟不可读）。HH:MM 固定 5 字符不会过长，
                            // 直接完整画；但 lane≥2 时相邻 lane 的条下文字会水平互撞
                            // （中心距 22px < 文字宽 47px），lane≥2 省略条下时间——
                            // 轴刻度 + 条的位置本身已表达时间。
                            if (ev.timeText.isNotBlank() && lanes.size == 1) {
                                tp.sized(ev.timeText, timeInk, colors.sub)
                                    .drawCentered(canvas, (l + r) / 2f, yTop + barH * 0.78f)
                            }
                        }
                    }
                }
            }

            // 「现在」参考线：只贯穿今天列（primary，alpha 0.6）+ 左端小圆点
            if (col.isToday && nowMin in axisStart..axisEnd) {
                val y = yOf(nowMin)
                val nowP = Paint().apply { color = colors.primary; alpha = 153; strokeWidth = 1.6f }
                canvas.drawLine(left + colW * 0.02f, y, left + colW * 0.98f, y, nowP)
                canvas.drawCircle(left + colW * 0.02f, y, h * 0.0045f, nowP)
            }
        }
    }

    /** "HH:MM" → 当日分钟；解析失败返回 null（3 天/周档全是 TIMED，理论上必有） */
    private fun parseHHMM(text: String): Int? {
        val m = Regex("^(\\d{1,2}):(\\d{2})$").find(text.trim()) ?: return null
        val hh = m.groupValues[1].toIntOrNull() ?: return null
        val mm = m.groupValues[2].toIntOrNull() ?: return null
        if (hh !in 0..23 || mm !in 0..59) return null
        return hh * 60 + mm
    }
}

