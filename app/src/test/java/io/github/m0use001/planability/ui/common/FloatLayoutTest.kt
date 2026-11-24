package io.github.m0use001.planability.ui.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [FloatLayout] 的单测。与 PC 端 `desktop/test/float.test.cjs` 覆盖同一批语义。
 *
 * 这个文件的价值不在「覆盖率」，在于**每条铁律都有一个测试钉住它**：
 * 铁律被违反时（有人为了省事改回无条件钳制、或把边名按移动窗命名），
 * 必须表现为某个具体测试变红，而不是等用户在第二块屏上拖出一片抖动。
 */
class FloatLayoutTest {

    private val screen = FloatDisplay(0, FloatRect(0f, 0f, 1080f, 2400f), 3.5f)

    private fun state(x: Float, y: Float, w: Float, h: Float, linked: Boolean = true) =
        PaneState(FloatRect(x, y, w, h), linked)

    private fun snapOf(
        input: PaneState,
        menu: PaneState,
        output: PaneState,
        anchor: PaneRole = PaneRole.INPUT,
    ) = LayoutSnapshot(mapOf(PaneRole.INPUT to input, PaneRole.MENU to menu, PaneRole.OUTPUT to output), anchor)

    // ---- 契约常量 ----

    @Test
    fun `默认 gap 为 0 即上下相连`() {
        assertEquals(0f, FloatLayoutDefaults.GAP, 0f)
        assertEquals(12f, FloatLayoutDefaults.SNAP_THRESHOLD, 0f)
        assertEquals(400f, FloatLayoutDefaults.MAX_DRIFT, 0f)
    }

    @Test
    fun `栈序是 input menu output`() {
        assertEquals(listOf(PaneRole.INPUT, PaneRole.MENU, PaneRole.OUTPUT), PANE_ORDER)
    }

    // ---- stackY ----

    @Test
    fun `stackY 在 gap 为 0 时各段边缘相接`() {
        val heights = mapOf(PaneRole.INPUT to 120f, PaneRole.MENU to 44f, PaneRole.OUTPUT to 300f)
        assertEquals(500f, stackY(500f, heights, 0, 0f), 0f)
        assertEquals(620f, stackY(500f, heights, 1, 0f), 0f)   // 500+120
        assertEquals(664f, stackY(500f, heights, 2, 0f), 0f)   // 500+120+44
    }

    @Test
    fun `stackY 在 gap 非 0 时每段之间留等距空隙`() {
        val heights = mapOf(PaneRole.INPUT to 100f, PaneRole.MENU to 50f, PaneRole.OUTPUT to 200f)
        // 每个前置段贡献 自身高度 + gap，所以第 1 段是 100+10 而不是 100
        assertEquals(110f, stackY(0f, heights, 1, 10f), 0f)
        assertEquals(170f, stackY(0f, heights, 2, 10f), 0f)   // 110 + (50+10)
    }

    // ---- defaultLayout / relayout ----

    @Test
    fun `defaultLayout 生成三段相连且等宽的整组`() {
        val s = defaultLayout(FloatRect(100f, 2000f, 900f, 120f), menuH = 44f, outputH = 300f)
        val i = s.panes[PaneRole.INPUT]!!.rect
        val m = s.panes[PaneRole.MENU]!!.rect
        val o = s.panes[PaneRole.OUTPUT]!!.rect
        assertEquals(2120f, m.y, 0f)          // input 底边，无空隙
        assertEquals(2164f, o.y, 0f)          // menu 底边，无空隙
        assertEquals(i.width, m.width, 0f)
        assertEquals(i.width, o.width, 0f)
        assertTrue(allLinked(s))
    }

    @Test
    fun `relayout 只重算 linked 的段`() {
        val s = snapOf(
            input = state(0f, 0f, 800f, 120f),
            menu = state(999f, 999f, 100f, 44f, linked = false),
            output = state(0f, 164f, 800f, 300f),
        )
        val r = relayout(s)
        assertEquals(999f, r.panes[PaneRole.MENU]!!.rect.x, 0f)   // detached 原样返回
        assertEquals(999f, r.panes[PaneRole.MENU]!!.rect.y, 0f)
        assertEquals(164f, r.panes[PaneRole.OUTPUT]!!.rect.y, 0f)
    }

    @Test
    fun `relayout 永不移动 anchor 本身`() {
        val s = snapOf(state(50f, 60f, 800f, 120f), state(0f, 0f, 10f, 44f), state(0f, 0f, 10f, 300f))
        val r = relayout(s)
        assertEquals(50f, r.panes[PaneRole.INPUT]!!.rect.x, 0f)
        assertEquals(60f, r.panes[PaneRole.INPUT]!!.rect.y, 0f)
    }

    @Test
    fun `relayout 跟随者取 anchor 的 x 与宽度但保留自己的高度`() {
        val s = snapOf(state(50f, 60f, 800f, 120f), state(0f, 0f, 10f, 44f), state(0f, 0f, 10f, 777f))
        val r = relayout(s)
        assertEquals(50f, r.panes[PaneRole.MENU]!!.rect.x, 0f)
        assertEquals(800f, r.panes[PaneRole.MENU]!!.rect.width, 0f)
        assertEquals(44f, r.panes[PaneRole.MENU]!!.rect.height, 0f)
        assertEquals(777f, r.panes[PaneRole.OUTPUT]!!.rect.height, 0f)  // 高度是自己的，不被锚点改写
    }

    @Test
    fun `缺段时 relayout 不崩`() {
        val s = LayoutSnapshot(mapOf(PaneRole.INPUT to state(0f, 0f, 800f, 120f)), PaneRole.INPUT)
        val r = relayout(s)
        assertEquals(120f, r.panes[PaneRole.INPUT]!!.rect.height, 0f)
        assertNull(r.panes[PaneRole.MENU])
    }

    // ---- movePane ----

    @Test
    fun `移动 anchor 会带动整组`() {
        val s = defaultLayout(FloatRect(0f, 0f, 800f, 120f), 44f, 300f)
        val r = movePane(s, PaneRole.INPUT, FloatRect(300f, 500f, 800f, 120f))
        assertEquals(300f, r.panes[PaneRole.MENU]!!.rect.x, 0f)
        assertEquals(620f, r.panes[PaneRole.MENU]!!.rect.y, 0f)
        assertEquals(664f, r.panes[PaneRole.OUTPUT]!!.rect.y, 0f)
    }

    @Test
    fun `移动 detached 的段它自己走 整组不动`() {
        val s = defaultLayout(FloatRect(0f, 0f, 800f, 120f), 44f, 300f)
            .let { setLinked(it, PaneRole.MENU, false) }
        val r = movePane(s, PaneRole.MENU, FloatRect(500f, 900f, 100f, 44f))
        assertEquals(500f, r.panes[PaneRole.MENU]!!.rect.x, 0f)
        assertEquals(900f, r.panes[PaneRole.MENU]!!.rect.y, 0f)
        assertEquals(0f, r.panes[PaneRole.INPUT]!!.rect.x, 0f)
        assertEquals(164f, r.panes[PaneRole.OUTPUT]!!.rect.y, 0f)
    }

    @Test
    fun `移动 detached 的段不得牵连仍相连的段 anchor 偏离原点时`() {
        // 这条是跨端向量回放抓出来的真实 bug：曾把「detached 就 return moved」写成
        // 「统一 relayout」。原测试里 anchor 在 (0,0)，而 relayout 在 anchor 位于原点时
        // **是幂等的**，重算结果与原值相同，于是错误被完全掩盖。必须让 anchor 偏离原点。
        val s = snapOf(
            input = state(50f, 60f, 800f, 120f),                       // anchor 故意不在原点
            menu = state(999f, 999f, 100f, 44f, linked = false),        // 已拆开
            output = state(0f, 164f, 800f, 300f),                      // 仍相连，且偏离正确堆叠位
        )
        val r = movePane(s, PaneRole.MENU, FloatRect(500f, 900f, 100f, 44f))
        assertEquals(500f, r.panes[PaneRole.MENU]!!.rect.x, 0f)
        assertEquals(900f, r.panes[PaneRole.MENU]!!.rect.y, 0f)
        // output 仍相连且不是被拖动的那段，它必须**原封不动**
        assertEquals(0f, r.panes[PaneRole.OUTPUT]!!.rect.x, 0f)
        assertEquals(164f, r.panes[PaneRole.OUTPUT]!!.rect.y, 0f)
        // anchor 也一样不动
        assertEquals(50f, r.panes[PaneRole.INPUT]!!.rect.x, 0f)
        assertEquals(60f, r.panes[PaneRole.INPUT]!!.rect.y, 0f)
    }

    @Test
    fun `移动 linked 的非 anchor 段会被立刻重新堆叠`() {
        val s = defaultLayout(FloatRect(0f, 0f, 800f, 120f), 44f, 300f)
        val r = movePane(s, PaneRole.MENU, FloatRect(500f, 900f, 100f, 44f))
        assertEquals(0f, r.panes[PaneRole.MENU]!!.rect.x, 0f)     // 被拉回 anchor 的 x
        assertEquals(120f, r.panes[PaneRole.MENU]!!.rect.y, 0f)
        assertEquals(800f, r.panes[PaneRole.MENU]!!.rect.width, 0f)
    }

    @Test
    fun `移动不存在的段返回原快照`() {
        val s = LayoutSnapshot(emptyMap(), PaneRole.INPUT)
        assertEquals(s, movePane(s, PaneRole.INPUT, FloatRect(0f, 0f, 1f, 1f)))
    }

    // ---- clamp 与 relayout 的组合顺序（PC F1 抓到的第三个真 bug）----
    //
    // 陷阱：`clamp` 之后 `relayout` 会**悄悄撤销 clamp**。linked 段是从 anchor 推导的，
    // 推导会覆盖掉钳制结果，输出框又被推出屏外。而「先 clamp 再 relayout」恰恰是最自然的
    // 写法，所以 PC 把它留成了显式 regression guard。手机端这两个函数是独立的纯函数、
    // 组合顺序在调用方（A 线接线），于是有**同样的风险**，此处以同样的方式钉住。
    //
    // 场景：用户把整组拖到下沿，anchor 底边已出屏 20px。
    private fun groupHangingOffBottom() = snapOf(
        input = state(0f, 2300f, 800f, 120f),
        menu = state(0f, 2420f, 800f, 44f),
        output = state(0f, 2464f, 800f, 300f),
    )

    /** 一段窗是否还够得着（至少留 KEEP 可见）。 */
    private fun reachable(p: FloatRect) =
        (minOf(p.bottom, screen.bounds.bottom) - maxOf(p.y, screen.bounds.y)) >= minOf(80f, p.height)

    @Test
    fun `错误顺序 clamp 再 relayout 会把窗重新推出屏外`() {
        val s = groupHangingOffBottom()
        // 错误顺序：先逐窗钳制，看起来都在屏内了…
        val clampedFirst = s.panes.mapValues { (_, p) -> p.copy(rect = clampToDisplay(p.rect, listOf(screen))) }
        // …然后 relayout 把钳制撤销了
        val after = relayout(LayoutSnapshot(clampedFirst, PaneRole.INPUT))
        // 这就是 bug：menu 与 output 双双被推出屏外，重新够不着
        assertFalse("menu 不该被 relayout 推出屏外", reachable(after.panes[PaneRole.MENU]!!.rect))
        assertFalse("output 不该被 relayout 推出屏外", reachable(after.panes[PaneRole.OUTPUT]!!.rect))
    }

    @Test
    fun `正确顺序 relayout 后逐窗钳制且不再 relayout 保证每段都够得着`() {
        val s = relayout(groupHangingOffBottom())
        val clamped = LayoutSnapshot(
            s.panes.mapValues { (_, p) -> p.copy(rect = clampToDisplay(p.rect, listOf(screen))) },
            s.anchor,
        )
        for (role in PANE_ORDER) {
            assertTrue("$role 应该够得着，实际 ${clamped.panes[role]!!.rect}", reachable(clamped.panes[role]!!.rect))
        }
        // 逐窗钳制保证的是「够得着」，**不是**「不重叠」也不是「保持栈序」——
        // 整组挂在下沿时这三者会互相打架。这是 PC 那条规则的真实代价，见我在
        // phone-b.md 里向 PC 提的问题，不要在这里偷偷改成「整组一起移」。
        assertEquals(2320f, clamped.panes[PaneRole.OUTPUT]!!.rect.y, 0f)
    }

    // ---- clampToDisplay ----

    @Test
    fun `放得下的窗不会被顶上去 这条钉住 PC 的 KEEP 回归`() {
        // 44px 的 menu 窗紧贴底边：keepY 取 min(80, 44)=44，可见 44px 已够，原地不动。
        // 若把 KEEP 无条件套成 80，它会被顶到 y=2320，而 input 窗(120px)反而压住它 ——
        // 钳制亲手制造了它本该防的重叠。
        val menu = clampToDisplay(FloatRect(0f, 2330f, 800f, 44f), listOf(screen))
        assertEquals(2330f, menu.y, 0f)
    }

    @Test
    fun `放不下时至少留 KEEP 可见`() {
        // 整窗在屏下沿之外 50px，可见量 -50 < keepY=80 -> 拉到 y=2400-80=2320。
        val r = clampToDisplay(FloatRect(0f, 2450f, 800f, 120f), listOf(screen))
        assertEquals(2320f, r.y, 0f)
        assertEquals(2440f, r.bottom, 0f)
        // 校验语义：被拉回后至少有一端在屏内可够着，而不是「完全包含」
        assertTrue(r.bottom > screen.bounds.bottom)   // 底边仍探出屏外 40px，这是允许的
    }

    @Test
    fun `目标是够得着而不是完全包含 挂出去一点就留着`() {
        // 200px 宽的窗挂出左缘 50px，仍有 150px 可见 >= 80，用户放哪就放哪。
        val r = clampToDisplay(FloatRect(-50f, 100f, 200f, 200f), listOf(screen))
        assertEquals(-50f, r.x, 0f)
        assertEquals(100f, r.y, 0f)
    }

    @Test
    fun `完全拖出屏外会被拉回到 KEEP 范围内`() {
        val r = clampToDisplay(FloatRect(-500f, 100f, 200f, 200f), listOf(screen))
        assertEquals(0f, r.x, 0f)            // 右边够不着，左边留 80
    }

    @Test
    fun `选取重叠面积最大的那块屏而不是第一块`() {
        val second = FloatDisplay(2, FloatRect(1080f, 0f, 1080f, 2400f), 1f)
        // 完全落在第二块屏上：第一块重叠面积为 0，不会被选中。
        val r = clampToDisplay(FloatRect(2000f, 1000f, 200f, 200f), listOf(screen, second))
        assertEquals(2000f, r.x, 0f)
        // 跨边界但**第二块屏重叠更大**：这里第二块可见 70px、第一块 30px，故选第二块；
        // 又因 70 < keepX=80 触发钳制，被拉到第二块左缘 1080。
        val r2 = clampToDisplay(FloatRect(1050f, 1000f, 100f, 200f), listOf(screen, second))
        assertEquals(1080f, r2.x, 0f)
        // 对照：同样跨第二块屏，但可见 150px >= 80 已够得着 -> 一个像素都不动
        // （铁律：钳制的目标是「够得着」，不是「完全包含」）
        val r3 = clampToDisplay(FloatRect(1030f, 1000f, 200f, 200f), listOf(screen, second))
        assertEquals(1030f, r3.x, 0f)
    }

    @Test
    fun `非有限值与空屏列表原样返回`() {
        val nan = FloatRect(Float.NaN, 0f, 10f, 10f)
        assertEquals(nan, clampToDisplay(nan, listOf(screen)))
        val r = FloatRect(0f, 0f, 10f, 10f)
        assertEquals(r, clampToDisplay(r, emptyList()))
    }

    // ---- snapRect ----

    private val target = FloatRect(100f, 100f, 200f, 200f)   // right=300 bottom=300

    @Test
    fun `边名指 target 侧 不是移动窗侧`() {
        // 移动窗左边贴到 target 的右边 -> edge=RIGHT，x 落在 target.right
        val r = snapRect(FloatRect(296f, 150f, 100f, 50f), target)!!
        assertEquals(SnapEdge.RIGHT, r.edge)
        assertEquals(300f, r.x, 0f)
        assertEquals(150f, r.y, 0f)   // 只动命中的那个轴

        // 移动窗右边贴到 target 的左边 -> edge=LEFT，x 落在 target.x - width
        val l = snapRect(FloatRect(8f, 150f, 100f, 50f), target)!!
        assertEquals(SnapEdge.LEFT, l.edge)
        assertEquals(0f, l.x, 0f)
    }

    @Test
    fun `上下两个方向也按 target 侧命名`() {
        val b = snapRect(FloatRect(150f, 296f, 50f, 100f), target)!!
        assertEquals(SnapEdge.BOTTOM, b.edge)
        assertEquals(300f, b.y, 0f)
        assertEquals(150f, b.x, 0f)

        // 贴上边意味着纵向**不重叠**，重叠保护因此会关掉 gapX（横边候选）。
        // 吸上边靠的是 gapY，它要求**横向**重叠 —— 这里满足（rect x 150..200 落在 target x 100..300 内）。
        val t = snapRect(FloatRect(150f, 0f, 50f, 100f), target)!!
        assertEquals(SnapEdge.TOP, t.edge)
        assertEquals(0f, t.y, 0f)    // target.y - height = 100 - 100
    }

    @Test
    fun `只有最近的边胜出 同距时按声明顺序稳定取 right`() {
        // 同时距 RIGHT 与 BOTTOM 各 5px：right 先声明，故取 RIGHT。
        val r = snapRect(FloatRect(295f, 295f, 30f, 30f), target)!!
        assertEquals(SnapEdge.RIGHT, r.edge)
        assertEquals(300f, r.x, 0f)
        assertEquals(295f, r.y, 0f)
    }

    @Test
    fun `纵向不重叠时不吸横边 高窗不会横吸到矮窗上`() {
        // 8px 处贴 target 左边，若无重叠保护会被误吸
        val r = snapRect(FloatRect(8f, 400f, 100f, 500f), target)
        assertNull(r)
    }

    @Test
    fun `横向不重叠时不吸竖边`() {
        // 竖着贴 target 底边但横向完全错开
        val r = snapRect(FloatRect(400f, 296f, 100f, 50f), target)
        assertNull(r)
    }

    @Test
    fun `阈值边界取闭区间`() {
        assertEquals(300f, snapRect(FloatRect(288f, 150f, 60f, 40f), target)!!.x, 0f)  // d=12 吸
        assertNull(snapRect(FloatRect(287f, 150f, 60f, 40f), target))                   // d=13 不吸
    }

    @Test
    fun `自定义阈值生效`() {
        assertNull(snapRect(FloatRect(270f, 150f, 60f, 40f), target))                        // d=30
        assertEquals(300f, snapRect(FloatRect(270f, 150f, 60f, 40f), target, 40f)!!.x, 0f)
    }

    // ---- linked 标志 ----

    @Test
    fun `isStillLinked 用 maxDrift 判定且对 detached 返回 false`() {
        val s = defaultLayout(FloatRect(0f, 0f, 800f, 120f), 44f, 300f)
        assertTrue(isStillLinked(s, PaneRole.MENU))
        val far = s.copy(panes = s.panes.toMutableMap().apply {
            this[PaneRole.MENU] = PaneState(FloatRect(5000f, 0f, 800f, 44f), true)
        })
        assertFalse(isStillLinked(far, PaneRole.MENU))
        val det = setLinked(s, PaneRole.MENU, false)
        assertFalse(isStillLinked(det, PaneRole.MENU))
    }

    @Test
    fun `setLinked 可逆 setAllLinked 一次切全部`() {
        val s = defaultLayout(FloatRect(0f, 0f, 800f, 120f), 44f, 300f)
        val one = setLinked(s, PaneRole.OUTPUT, false)
        assertTrue(one.panes[PaneRole.OUTPUT]!!.linked.not())
        assertTrue(anyDetached(one))
        assertFalse(allLinked(one))
        assertTrue(setLinked(one, PaneRole.OUTPUT, true).panes[PaneRole.OUTPUT]!!.linked)  // 可逆
        val all = setAllLinked(one, true)
        assertTrue(allLinked(all))
        assertFalse(anyDetached(all))
    }

    @Test
    fun `对不存在的段切 linked 是安全的`() {
        val s = LayoutSnapshot(mapOf(PaneRole.INPUT to state(0f, 0f, 800f, 120f)), PaneRole.INPUT)
        assertEquals(s, setLinked(s, PaneRole.MENU, false))
    }

    // ---- 持久化 ----

    @Test
    fun `save 与 load 往返一致`() {
        val s = setLinked(defaultLayout(FloatRect(10f, 20f, 800f, 120f), 44f, 300f), PaneRole.MENU, false)
        val stored = saveLayout(s, gap = 8f)
        // 模拟真实落盘形态：键是 role 的**字符串名**（JSON 不认 enum），
        // 数值统一转成 Double（JSON 数字反序列化出来的就是 Double，不是 Float）。
        val raw = mapOf(
            "anchor" to stored.anchor.name,
            "gap" to stored.gap,
            "panes" to stored.panes.entries.associate { (role, p) ->
                role.name to mapOf(
                    "x" to p.rect.x.toDouble(), "y" to p.rect.y.toDouble(),
                    "width" to p.rect.width.toDouble(), "height" to p.rect.height.toDouble(),
                    "linked" to p.linked,
                )
            },
        )
        val back = loadLayout(raw, defaultLayout(FloatRect(0f, 0f, 800f, 120f), 44f, 300f))
        assertEquals(stored.panes[PaneRole.INPUT]!!.rect, back.panes[PaneRole.INPUT]!!.rect)
        assertEquals(stored.panes[PaneRole.MENU]!!.rect, back.panes[PaneRole.MENU]!!.rect)
        assertFalse(back.panes[PaneRole.MENU]!!.linked)
        assertEquals(PaneRole.INPUT, back.anchor)
    }

    @Test
    fun `落盘数据损坏时逐字段回落而不是整组报废`() {
        val fb = defaultLayout(FloatRect(0f, 0f, 800f, 120f), 44f, 300f)
        val bad = mapOf(
            "anchor" to "不存在的角色",
            "panes" to mapOf(
                // x 是 NaN（老版本写坏的）-> 整段回落
                PaneRole.INPUT.name to mapOf("x" to Double.NaN, "y" to 5.0, "width" to 9.0, "height" to 9.0),
                // 类型不对 -> 整段回落
                PaneRole.MENU.name to "这不是对象",
                // 正常 -> 采用
                PaneRole.OUTPUT.name to mapOf("x" to 7.0, "y" to 8.0, "width" to 9.0, "height" to 10.0),
            ),
        )
        val r = loadLayout(bad, fb)
        assertEquals(fb.panes[PaneRole.INPUT]!!.rect, r.panes[PaneRole.INPUT]!!.rect)
        assertEquals(fb.panes[PaneRole.MENU]!!.rect, r.panes[PaneRole.MENU]!!.rect)
        assertEquals(FloatRect(7f, 8f, 9f, 10f), r.panes[PaneRole.OUTPUT]!!.rect)
        assertEquals(PaneRole.INPUT, r.anchor)   // anchor 认不出就回落
    }

    @Test
    fun `非对象输入直接返回回落布局`() {
        val fb = defaultLayout(FloatRect(0f, 0f, 800f, 120f), 44f, 300f)
        assertEquals(fb, loadLayout(null, fb))
        assertEquals(fb, loadLayout("垃圾", fb))
        assertEquals(fb, loadLayout(emptyMap<String, Any>(), fb))
    }

    @Test
    fun `linked 字段缺失按 false 处理`() {
        val fb = defaultLayout(FloatRect(0f, 0f, 800f, 120f), 44f, 300f)
        val r = loadLayout(
            mapOf(
                "anchor" to "OUTPUT",
                "panes" to mapOf(
                    PaneRole.INPUT.name to mapOf("x" to 0.0, "y" to 0.0, "width" to 8.0, "height" to 1.0),
                    PaneRole.MENU.name to mapOf("x" to 0.0, "y" to 0.0, "width" to 8.0, "height" to 1.0),
                    PaneRole.OUTPUT.name to mapOf("x" to 0.0, "y" to 0.0, "width" to 8.0, "height" to 1.0),
                ),
            ),
            fb,
        )
        assertFalse(r.panes[PaneRole.INPUT]!!.linked)
        assertEquals(PaneRole.OUTPUT, r.anchor)
    }
}