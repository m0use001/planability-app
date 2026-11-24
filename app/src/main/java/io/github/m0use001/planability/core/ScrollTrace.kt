package io.github.m0use001.planability.core

import android.util.Log
import android.view.Choreographer
import io.github.m0use001.planability.BuildConfig

/**
 * 滚动轨迹追踪器（诊断用，**debug 专属**）。
 *
 * 背景（2026-11-20 用户）：「加一个追踪器，盯着屏幕上某个点相较于整体会话的位置
 * （例如从下往上看 20% 处），我滚动几下，你看看位置变化是不是平滑曲线」。
 *
 * 采样（每帧，滚动进行中）：
 *  · 视口里一个**固定屏幕点**（距底 20%）落在哪条消息、消息内第几像素
 *    → `pos = ptIndex + (pointY - ptOffset) / ptSize` 即该点对应的「会话内容坐标」，
 *      内容向前滚动时 pos 单调增。
 *  · `firstVisibleItemIndex/ScrollOffset` → 同一 firstIndex 下 offset 的逐帧差 = 像素位移
 *    （`v`，正值 = 朝更晚内容）。
 *
 * 滚动停稳时一次性算好 [Result]（含判据 + 降采样到 ≤48 点的 T/POS/V 序列），
 * 由 UI 层以 debug 浮层画出曲线——**不逐帧打日志**，避免 IO 本身引入卡顿污染测量。
 * MCP 的 logcat 通道输出会被截断到几百字节，不可靠，所以观测走屏幕浮层 + 截图。
 *
 * 只读诊断，不参与任何滚动决策；`enabled` 在 release 恒 false（[BuildConfig.DEBUG]）。
 */
object ScrollTrace {
    private const val CAP = 6000

    private data class S(
        val t: Long, val fi: Int, val fo: Int,
        val y: Float, val pi: Int, val po: Int, val ps: Int,
        val dragged: Boolean, val prog: Boolean, val cf: Boolean,
        /** 锚点条目（= firstVisibleItemIndex 那一条）当前高度。它若突变，说明该条目被重新
         *  测量/换高——LazyColumn 会因此重锚，屏幕上就是一次「抽搐」。 */
        val asz: Int,
    )

    /** 一轮滚动（两个滚动事件之间）的轨迹结果。 */
    class Result(
        /** 降采样后的时间（ms，从本轮开始算）。 */
        val tMs: List<Int>,
        /** 固定屏幕点（距底 frac）对应的内容坐标序列，与 [tMs] 等长。 */
        val pos: List<Float>,
        /** 像素位移序列（同 firstIndex 内 offset 差；跨 item 边界为 NaN），与 [tMs] 等长。 */
        val v: List<Float>,
        /** 本轮主导方向：+1 内容前进 / -1 内容后退 / 0 原地抖（无法定方向）。 */
        val dir: Int,
        /** 逆主导方向的帧数（真回退/抖动，一次流畅单向下滑应为 0）。 */
        val flips: Int,
        /** 像素级最大逆向位移（px，真正「反向拽」的幅度）。 */
        val backPx: Int,
        /** 内容坐标最大逆向位移。 */
        val backPos: Float,
        /** 相邻帧像素速度最大突跳（px/帧）。 */
        val jumpPx: Int,
        /** 该突跳跨过的时间（ms）——判断它是「真跳变」还是「采样间隔太稀」。 */
        val jumpDtMs: Int,
        /** 该突跳发生的时间点（ms，从本轮开始）。 */
        val jumpAtMs: Int,
        /** 突跳那一刻的现场：手指是否按在列表上。 */
        val jumpDragged: Boolean,
        /** 突跳那一刻程序化滚动标记是否激活（贴底钳制/胶囊跳转在动滚动）。 */
        val jumpProg: Boolean,
        /** 突跳那一刻是否还能向后滚（false=已在底部）。 */
        val jumpCf: Boolean,
        /** 突跳前/后的像素速度（px/帧）：从 0 跳到 1384 = 冻住后补跳；A≈B 反向 = 真回退。 */
        val jumpV0: Float,
        val jumpV1: Float,
        /** 逐帧统计：本轮总帧数。 */
        val frames: Int,
        /** 帧间隔 >20ms（60Hz 掉帧）的次数。 */
        val jank20: Int,
        /** 帧间隔 >34ms（掉 2 帧以上）的次数。 */
        val jank40: Int,
        /** 最差帧间隔（ms）。 */
        val worstDtMs: Int,
        /** 最差帧发生时刻（ms，从本轮开始）。 */
        val worstAtMs: Int,
        /** 最差帧时刻视口顶部的条目序号（= 正在进场组合的候选），-1 表示未知。 */
        val stallFi: Int,
        /** 本轮最重的一次 Markdown 组合（>25ms 才记）：耗时 ms，-1 = 无。 */
        val composeMs: Int,
        /** 该条消息长度（字符）。 */
        val composeLen: Int,
        /** 该条消息的表格行数。 */
        val composeTables: Int,
        /** 该条消息的 display 公式数。 */
        val composeMath: Int,
        /** 该组合完成时刻（ms，从本轮开始）。 */
        val composeAtMs: Int,
        /** 本轮最重的 Markdown 组合的各段分解（如「表=92 式=31」），空串=无。 */
        val composeParts: String,
        /** 本轮单条消息 measure 的最大耗时（ms），-1=未采到。 */
        val measureMaxMs: Int,
        /** 本轮单条消息**绘制录制**（主线程记录 display list）的最大耗时（ms），-1=未采到。 */
        val drawMaxMs: Int,
        /** 本轮阻塞式 GC 次数（art.gc.blocking-gc-count 增量）。 */
        val blockingGcCount: Int,
        /** 本轮阻塞式 GC 总时长（ms）。 */
        val blockingGcMs: Int,
        /** 本轮全部 GC 次数与总时长（含后台 GC；后台 GC 也会抢 CPU 让渲染掉帧）。 */
        val gcCount: Int,
        val gcMs: Int,
        /** 本轮渲染完成的公式位图张数 / 最慢一张的耗时与时刻。 */
        val latexCount: Int,
        val latexMaxMs: Int,
        val latexAtMs: Int,
        /** 本轮有多少条公式渲染请求**因为列表在滚而被推迟**（滚停才渲）。 */
        val latexDeferred: Int,
        /** 最差那一帧内：各段组合耗时合计 / measure 合计（含条目数）/ 绘制录制合计。
         *  「单段 <10ms、单条 measure 27ms，却有 99ms 卡帧」——证明是**同一帧里堆了好几条**
         *  的合计，而不是某一处特别慢。 */
        val worstFrameComposeMs: Int,
        val worstFrameMeasureMs: Int,
        val worstFrameDrawMs: Int,
        val worstFrameItems: Int,
        /** 原始采样转储（每帧一行）：`t fi fo pos 点高 锚高 手 程 底` + 帧间隔与打点。
         *  debug 浮层只给结论；「1085px 跳位」这种细节必须看原始数据才能定性。 */
        val raw: String,
        val durMs: Int,
        val n: Int,
        val frac: Float,
    )

    private val lock = Any()
    private val data = ArrayList<S>(CAP)
    private val frameTimes = ArrayList<Long>(4096)
    private var frameCb: Choreographer.FrameCallback? = null
    private var recording = false

    /** GC 计数基线（本轮开始时的累计值，收尾做差）。取不到（模拟器/旧 ROM）一律 0。 */
    private var gcBlock0 = 0
    private var gcBlockMs0 = 0
    private var gcAll0 = 0
    private var gcAllMs0 = 0

    private fun gcStat(name: String): Int =
        runCatching { android.os.Debug.getRuntimeStat(name)?.trim()?.toIntOrNull() ?: 0 }
            .getOrDefault(0)

    /** 带时间戳的耗时点（kind: 0=组合段 1=measure 2=绘制录制）。
     *  用途：把「最差那一帧」窗口内的各项耗时**加起来**——单段都不大但同帧堆叠才是真凶时，
     *  只有求和能看出来。 */
    private data class Mark(val atNanos: Long, val kind: Int, val v: Int)
    private val marks = ArrayList<Mark>(4096)

    private fun mark(kind: Int, v: Int) {
        synchronized(lock) {
            if (!recording) return
            if (marks.size < 40000) marks.add(Mark(System.nanoTime(), kind, v))
        }
    }

    /** 本轮最重的 Markdown 组合：[dtMs, len, tableRows, displayMath]（经 noteSlowCompose 上报）。 */
    private var worstCompose: IntArray? = null

    /** 本轮各段（MdTextLines/MdTable/LatexBlock）累计；见 noteComposePart。 */
    private var measureMax = -1

    /** 本轮单条消息 measure 耗时上报（ChatScreen 的条目测量探针）。 */
    fun noteMeasure(ms: Int) {
        if (!enabled) return
        postMark(1, ms)
        synchronized(lock) {
            if (!recording) return
            if (ms > measureMax) measureMax = ms
        }
        mark(1, ms)
    }

    /** 本轮绘制录制峰值（drawWithContent 探针）——组合/测量都便宜时的第三嫌疑人。 */
    private var drawMax = -1

    fun noteDraw(ms: Int) {
        if (!enabled || ms < 0) return
        postMark(2, ms)
        synchronized(lock) {
            if (!recording) return
            if (ms > drawMax) drawMax = ms
        }
        mark(2, ms)
    }

    /** 公式位图渲染完成（worker 侧上报）：张数 + 最慢一张。渲染与 UI 抢 CPU/堆，是卡顿第四嫌疑人。 */
    private var latexCount = 0
    private var latexMax = -1
    private var latexMaxAt = -1

    fun noteLatexRender(ms: Int) {
        if (!enabled) return
        postMark(4, ms)
        synchronized(lock) {
            if (!recording) return
            latexCount++
            if (ms > latexMax) {
                latexMax = ms
                latexMaxAt = frameTimes.firstOrNull()?.let {
                    ((System.nanoTime() - it) / 1_000_000).toInt()
                } ?: -1
            }
        }
    }

    /** 因「列表正在滚动」而推迟的公式渲染请求数（这些本该是白渲）。 */
    private var latexDeferred = 0

    fun noteLatexDeferred() {
        if (!enabled) return
        synchronized(lock) {
            if (!recording) return
            latexDeferred++
        }
    }

    /**
     * 聊天列表是否正在滚动（由 [io.github.m0use001.planability.ui.info.MessageList] 的采样器维护；
     * 其他页面不置位）。
     *
     * 用途：**滚动中不渲公式位图**。快速滑动时条目飞过去就 dispose，渲染是白干——
     * 白干不光浪费，还在 UI 线程抢 CPU、在堆上反复分配/回收位图（32MB 缓存附近的
     * 逐出抖动），正是「组合<10ms、测量 22ms，却仍有 80ms 卡帧」的候选来源。
     */
    @Volatile
    var listScrolling: Boolean = false
        private set

    @Volatile
    private var lastScrollSignalAt = 0L

    fun setListScrolling(scrolling: Boolean) {
        listScrolling = scrolling
        lastScrollSignalAt = android.os.SystemClock.elapsedRealtime()
    }

    /**
     * 等到列表停手再返回（最多 [maxWaitMs]）。
     * 只在 debug 包生效；[listScrolling] 若被异常遗留在 true（采样器被取消等），
     * 超过 400ms 没有新信号就当作已停手，公式不会被永久卡住。
     */
    suspend fun awaitScrollIdle(maxWaitMs: Long = 1200) {
        if (!enabled) return
        val deadline = android.os.SystemClock.elapsedRealtime() + maxWaitMs
        while (true) {
            val now = android.os.SystemClock.elapsedRealtime()
            if (!listScrolling) return
            if (now - lastScrollSignalAt > 400) return
            if (now > deadline) return
            kotlinx.coroutines.delay(40)
        }
    }

    // ===== 停手后帧监视（2026-11-21「停手时还是会顿一下」）=====
    private val postFrames = ArrayList<Long>(256)
    private val postMarks = ArrayList<Mark>(256)
    private var postCb: Choreographer.FrameCallback? = null
    private var postStopAt = 0L

    /** 停手窗口内的耗时打点（组合/测量/绘制），用来点名「那 24ms 是谁」 */
    private fun postMark(kind: Int, v: Int) {
        synchronized(lock) {
            val t0 = postStopAt
            if (t0 == 0L) return
            val at = System.nanoTime()
            if ((at - t0) / 1_000_000 >= 900) return
            if (postMarks.size < 4000) postMarks.add(Mark(at, kind, v))
        }
    }

    /** 从停手那一刻开始记 900ms 的帧间隔（与覆盖层挂载/Toast/落盘同一条时间轴）。 */
    private fun startPostStopWatch() {
        synchronized(lock) {
            postFrames.clear()
            postMarks.clear()
            postStopAt = System.nanoTime()
        }
        postCb?.let { Choreographer.getInstance().removeFrameCallback(it) }
        val cb = object : Choreographer.FrameCallback {
            override fun doFrame(frameTimeNanos: Long) {
                val more: Boolean
                synchronized(lock) {
                    postFrames.add(frameTimeNanos)
                    more = (frameTimeNanos - postStopAt) / 1_000_000 < 900
                }
                if (more) Choreographer.getInstance().postFrameCallback(this)
            }
        }
        postCb = cb
        Choreographer.getInstance().postFrameCallback(cb)
    }

    /** 停手后帧统计（供采样文件落盘时附加）：`t=相对停手的毫秒 dt=帧间隔`。 */
    fun postStopText(): String {
        val list: List<Long>
        val marksCopy: List<Mark>
        val t0: Long
        synchronized(lock) {
            list = ArrayList(postFrames)
            marksCopy = ArrayList(postMarks)
            t0 = postStopAt
        }
        if (list.size < 3) return "(无停手后帧数据)\n"
        val sb = StringBuilder(2048)
        var worst = 0.0
        var worstAt = 0
        for (i in 1 until list.size) {
            val dt = (list[i] - list[i - 1]) / 1_000_000.0
            val at = ((list[i] - t0) / 1_000_000).toInt()
            if (dt > worst) { worst = dt; worstAt = at }
            if (dt > 20) sb.append("post t=").append(at).append(" dt=")
                .append(String.format("%.1f", dt)).append('\n')
        }
        // 停手窗口内谁在占主线程
        for (m in marksCopy) {
            val at = ((m.atNanos - t0) / 1_000_000).toInt()
            sb.append("  mark t=").append(at).append(' ')
                .append(when (m.kind) {
                    0 -> "part"; 1 -> "measure"; 2 -> "draw"; 4 -> "latex"; else -> "compose"
                })
                .append(' ').append(m.v).append("ms\n")
        }
        sb.insert(0, "-- post-stop frames=" + list.size + " worst=" +
            String.format("%.1f", worst) + "ms@" + worstAt + "ms --\n")
        return sb.toString()
    }


    /** 组合耗时按段累加（文/表/式…），只记 >10ms 的段，用于定位重型消息的大头。 */
    private val composeParts = HashMap<String, Int>()

    /** Markdown 组合内部各段（MdTextLines/MdTable/LatexBlock）各自耗时上报。 */
    fun noteComposePart(name: String, dtMs: Int) {
        if (!enabled || dtMs <= 10) return
        synchronized(lock) {
            if (!recording) return
            composeParts[name] = (composeParts[name] ?: 0) + dtMs
        }
        mark(0, dtMs)
    }

    /** release 恒 false；debug 包默认 true。 */
    var enabled: Boolean = BuildConfig.DEBUG

    /**
     * 是否显示屏幕浮层（默认关）。
     *
     * 2026-11-21 实测：停手后 0~100ms 没有长帧，长帧在 t≈168/351ms——正是浮层挂载/绘制
     * 那两帧（各 ~25ms）。调试面板不该冒充产品缺陷，所以默认不挂；读数照旧写
     * `files/scrolltrace.txt`（含停手后帧统计）。
     */
    var showOverlay: Boolean = false

    /** 追踪点：距视口底部向上的比例（0.20 = 从下往上看 20% 处）。 */
    var anchoredFractionFromBottom: Float = 0.20f

    fun beginScroll() {
        if (!enabled) return
        synchronized(lock) {
            data.clear()
            frameTimes.clear()
            worstCompose = null
            measureMax = -1
            drawMax = -1
            latexCount = 0
            latexMax = -1
            latexMaxAt = -1
            latexDeferred = 0
            marks.clear()
            gcBlock0 = gcStat("art.gc.blocking-gc-count")
            gcBlockMs0 = gcStat("art.gc.blocking-gc-time-millis")
            gcAll0 = gcStat("art.gc.gc-count")
            gcAllMs0 = gcStat("art.gc.gc-time-millis")
            composeParts.clear()
            recording = true
        }
        // 帧时间计：逐帧记录 Choreographer 时间，量化掉帧（与内容坐标同一条时间轴）
        val cb = object : Choreographer.FrameCallback {
            override fun doFrame(frameTimeNanos: Long) {
                val again: Boolean
                synchronized(lock) {
                    again = recording
                    if (again && frameTimes.size < 20000) frameTimes.add(frameTimeNanos)
                }
                if (again) Choreographer.getInstance().postFrameCallback(this)
            }
        }
        synchronized(lock) { frameCb = cb }
        Choreographer.getInstance().postFrameCallback(cb)
        setListScrolling(true)
    }

    /** MarkdownView 单条消息整段组合耗时上报（ui/notes/NotesScreen.kt 的 MdPerf 打点转接）。
     *  调用方现在每段都报（>2ms），>25ms 的才进 [worstCompose] 展示；同时打点进帧窗口求合计。 */
    fun noteSlowCompose(dtMs: Int, len: Int, tableRows: Int, displayMath: Int) {
        if (!enabled) return
        if (dtMs <= 2) return
        postMark(3, dtMs)
        mark(3, dtMs)
        if (dtMs <= 25) return
        synchronized(lock) {
            if (!recording) return
            val cur = worstCompose
            if (cur != null && dtMs <= cur[0]) return
            val t0 = frameTimes.firstOrNull() ?: return
            val atMs = ((System.nanoTime() - t0) / 1_000_000).toInt()
            worstCompose = intArrayOf(dtMs, len, tableRows, displayMath, atMs)
        }
    }

    fun sample(
        frameNanos: Long,
        firstIndex: Int,
        firstOffset: Int,
        pointY: Float,
        ptIndex: Int,
        ptOffset: Int,
        ptSize: Int,
        dragged: Boolean,
        programmatic: Boolean,
        canScrollForward: Boolean,
        anchorSize: Int = -1,
    ) {
        if (!enabled) return
        // 每帧刷新「正在滚」的时间戳：awaitScrollIdle 靠它判活。只在 beginScroll 刷一次的话，
        // 400ms 之后就被当成停手了——2026-11-21 实测正是这样，滚了 2.6s 却有 156 张公式
        // 在滚动中渲完，每张都触发一次整条消息重组合。
        setListScrolling(true)
        synchronized(lock) {
            if (!recording || data.size >= CAP) return
            data.add(
                S(frameNanos, firstIndex, firstOffset, pointY, ptIndex, ptOffset, ptSize,
                    dragged, programmatic, canScrollForward, anchorSize)
            )
        }
    }

    /** 滚动停稳时调用；样本不足（瞬时程序化滚动）返回 null。 */
    fun endScroll(): Result? {
        setListScrolling(false)
        if (!enabled) return null
        // 2026-11-21 用户「停手时还是会顿一下」：停手后的 900ms 单独记帧，
        // 用来分辨到底是「真渲/重排」还是「调试浮层挂载 / Toast / 采样落盘」。
        startPostStopWatch()
        val list: List<S>
        val framesList: List<Long>
        synchronized(lock) {
            recording = false
            frameCb?.let { Choreographer.getInstance().removeFrameCallback(it) }
            frameCb = null
            if (data.size < 3) { data.clear(); frameTimes.clear(); return null }
            list = ArrayList(data)
            data.clear()
            framesList = ArrayList(frameTimes)
            frameTimes.clear()
        }
        val n = list.size
        val t0 = list[0].t
        val tm = DoubleArray(n) { (list[it].t - t0) / 1_000_000.0 }
        val pos = DoubleArray(n) {
            val s = list[it]
            s.pi + (s.y - s.po) / s.ps.toDouble()
        }
        val dv = DoubleArray(n) { Double.NaN }
        for (i in 1 until n) {
            if (list[i].fi == list[i - 1].fi) {
                dv[i] = (list[i].fo - list[i - 1].fo).toDouble()
            }
        }
        // 主导方向 = 多数帧的位移方向；逆主导方向的帧数/幅度才是「回退」
        var upCount = 0
        var downCount = 0
        for (i in 1 until n) {
            val d = pos[i] - pos[i - 1]
            if (d > 1e-4) upCount++ else if (d < -1e-4) downCount++
        }
        val dir = when {
            upCount > downCount -> 1
            downCount > upCount -> -1
            else -> 0
        }
        var flips = 0
        var backPos = 0.0
        if (dir != 0) {
            for (i in 1 until n) {
                val d = pos[i] - pos[i - 1]
                val s = if (d > 1e-4) 1 else if (d < -1e-4) -1 else 0
                if (s != 0 && s != dir) {
                    flips++
                    if (kotlin.math.abs(d) > backPos) backPos = kotlin.math.abs(d)
                }
            }
        }
        var backPx = 0.0
        if (dir != 0) {
            for (i in 1 until n) {
                val v = dv[i]
                if (v.isNaN()) continue
                val s = if (v > 0) 1 else if (v < 0) -1 else 0
                if (s != 0 && s != dir && kotlin.math.abs(v) > backPx) backPx = kotlin.math.abs(v)
            }
        }
        var jump = 0.0
        var jumpDtMs = 0
        var jumpAtMs = 0
        var jumpIdx = -1
        var jumpPrevIdx = -1
        var prevIdx = -1
        for (i in 1 until n) {
            val v = dv[i]
            if (v.isNaN()) continue
            if (prevIdx >= 0) {
                val j = kotlin.math.abs(v - dv[prevIdx])
                if (j > jump) {
                    jump = j
                    jumpDtMs = (tm[i] - tm[prevIdx]).toInt()
                    jumpAtMs = tm[i].toInt()
                    jumpIdx = i
                    jumpPrevIdx = prevIdx
                }
            }
            prevIdx = i
        }
        val jumpS = if (jumpIdx in 1 until n) list[jumpIdx] else null
        val jumpV0 = if (jumpPrevIdx in 0 until n) dv[jumpPrevIdx].toFloat() else Float.NaN
        val jumpV1 = if (jumpIdx in 0 until n) dv[jumpIdx].toFloat() else Float.NaN
        // 帧时间统计：掉帧次数与最差帧（与内容坐标同一条时间轴，可对齐到突跳时刻）
        var jank20 = 0
        var jank40 = 0
        var worstDt = 0.0
        var worstAt = 0
        if (framesList.size >= 3) {
            val f0 = framesList[0]
            for (i in 1 until framesList.size) {
                val dtMs = (framesList[i] - framesList[i - 1]) / 1_000_000.0
                if (dtMs > worstDt) {
                    worstDt = dtMs
                    worstAt = ((framesList[i] - f0) / 1_000_000).toInt()
                }
                if (dtMs > 20) jank20++
                if (dtMs > 34) jank40++
            }
        }
        // 卡顿时刻视口顶部条目（新进场组合的候选）
        var stallFi = -1
        if (worstDt > 0 && n > 0) {
            var best = 0
            var bestD = Double.MAX_VALUE
            for (i in 0 until n) {
                val d = kotlin.math.abs(tm[i] - worstAt)
                if (d < bestD) { bestD = d; best = i }
            }
            stallFi = list[best].fi
        }
        val m = if (n <= 48) n else 48
        val pick = IntArray(m) { if (m == 1) 0 else (it.toDouble() * (n - 1) / (m - 1)).toInt() }
        // 最差帧窗口内的耗时**合计**：窗口 = (worstAt - worstDt, worstAt]
        var wCompose = 0
        var wMeasure = 0
        var wDraw = 0
        var wItems = 0
        if (framesList.size >= 3 && worstDt > 0) {
            val f0n = framesList[0]
            val wEnd = f0n + worstAt * 1_000_000L
            val wStart = (wEnd - (worstDt * 1_000_000.0).toLong()) - 2_000_000L
            for (mk in synchronized(lock) { ArrayList(marks) }) {
                if (mk.atNanos <= wStart || mk.atNanos > wEnd) continue
                when (mk.kind) {
                    3 -> wCompose += mk.v      // 单条消息整段组合
                    1 -> { wMeasure += mk.v; wItems++ }
                    2 -> wDraw += mk.v
                }
            }
        }
        val wc = synchronized(lock) { worstCompose }
        // 原始采样文本：t / fi / fo / 内容坐标 / 点高 / 锚高 / 手程底 + 该时刻的帧间隔
        val rawSb = StringBuilder(4096 * 8)
        rawSb.append("n=$n dur=${tm[n - 1].toInt()}ms fi0=${list[0].fi}fo0=${list[0].fo}\n")
        run {
            val frameGaps = HashMap<Long, Double>()
            if (framesList.size >= 3) {
                for (i in 1 until framesList.size) {
                    frameGaps[framesList[i]] = (framesList[i] - framesList[i - 1]) / 1_000_000.0
                }
            }
            val limit = if (n <= 1200) n else 1200
            for (i in 0 until limit) {
                val s = list[i]
                val gap = frameGaps[s.t]
                rawSb.append(i)
                    .append(' ').append(tm[i].toInt())
                    .append(" fi=").append(s.fi)
                    .append(" fo=").append(s.fo)
                    .append(" pos=").append(String.format("%.2f", pos[i]))
                    .append(" ps=").append(s.ps)
                    .append(" as=").append(s.asz)
                    .append(" f=").append(if (s.dragged) 1 else 0)
                    .append(if (s.prog) 1 else 0)
                    .append(if (s.cf) 1 else 0)
                    .append(if (gap != null) " dt=${String.format("%.1f", gap)}" else "")
                    .append('\n')
            }
        }
        val mk = synchronized(lock) { ArrayList(marks) }
        if (mk.isNotEmpty() && framesList.isNotEmpty()) {
            val f0n = framesList[0]
            rawSb.append("-- marks --\n")
            for (m in mk) {
                rawSb.append((m.atNanos - f0n) / 1_000_000).append(' ')
                    .append(when (m.kind) { 0 -> "part" 1 -> "measure" 2 -> "draw" else -> "compose" })
                    .append(' ').append(m.v).append('\n')
            }
        }
        val partsStr = synchronized(lock) {
            if (composeParts.isEmpty()) "" else
                composeParts.entries.sortedByDescending { it.value }
                    .joinToString(" ") { "${it.key}=${it.value}" }
        }
        return Result(
            tMs = pick.map { tm[it].toInt() },
            pos = pick.map { pos[it].toFloat() },
            v = pick.map { dv[it].toFloat() },
            dir = dir,
            flips = flips,
            backPx = backPx.toInt(),
            backPos = backPos.toFloat(),
            jumpPx = jump.toInt(),
            jumpDtMs = jumpDtMs,
            jumpAtMs = jumpAtMs,
            jumpDragged = jumpS?.dragged ?: false,
            jumpProg = jumpS?.prog ?: false,
            jumpCf = jumpS?.cf ?: false,
            jumpV0 = jumpV0,
            jumpV1 = jumpV1,
            frames = framesList.size,
            jank20 = jank20,
            jank40 = jank40,
            worstDtMs = worstDt.toInt(),
            worstAtMs = worstAt,
            stallFi = stallFi,
            composeMs = wc?.get(0) ?: -1,
            composeLen = wc?.get(1) ?: 0,
            composeTables = wc?.get(2) ?: 0,
            composeMath = wc?.get(3) ?: 0,
            composeAtMs = wc?.get(4) ?: -1,
            composeParts = partsStr,
            measureMaxMs = synchronized(lock) { measureMax },
            drawMaxMs = synchronized(lock) { drawMax },
            blockingGcCount = (gcStat("art.gc.blocking-gc-count") - gcBlock0).coerceAtLeast(0),
            blockingGcMs = (gcStat("art.gc.blocking-gc-time-millis") - gcBlockMs0).coerceAtLeast(0),
            gcCount = (gcStat("art.gc.gc-count") - gcAll0).coerceAtLeast(0),
            gcMs = (gcStat("art.gc.gc-time-millis") - gcAllMs0).coerceAtLeast(0),
            latexCount = synchronized(lock) { latexCount },
            latexMaxMs = synchronized(lock) { latexMax },
            latexAtMs = synchronized(lock) { latexMaxAt },
            latexDeferred = synchronized(lock) { latexDeferred },
            worstFrameComposeMs = wCompose,
            worstFrameMeasureMs = wMeasure,
            worstFrameDrawMs = wDraw,
            worstFrameItems = wItems,
            raw = rawSb.toString(),
            durMs = tm[n - 1].toInt(),
            n = n,
            frac = anchoredFractionFromBottom,
        )
    }
}
