package io.github.m0use001.planability.ui.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **跨端一致性回放测试** —— 把 PC 端 `desktop/src/main/float/layout.ts` 的真实实现当作
 * 黄金标准（oracle），用它导出的向量驱动本文件的 Kotlin 实现。
 *
 * ## 为什么必须有这个
 *
 * 两端没有共享代码（AGENTS.md 第 0 节），Kotlin 与 TypeScript 是**两份手工维护的副本**。
 * 而这个引擎的 bug 全是算术错误：不报错、不崩溃，只在用户把窗拖到边缘时才显形，表现为
 * 「整组抖动」或「吸附看起来坏了」。手写单测只能证明「我理解了我读过的代码」，
 * 证明不了「我的行为和他们的行为一致」。
 *
 * PC 的 `desktop/src/main/float/conformance.ts` 管不了这件事：它是 TS 内部的**编译期类型
 * 断言**，保证 renderer 契约与主进程声明同形，产物直接丢弃、不产生任何运行时数据。
 * 跨语言那一半没人管 —— 就是这里。
 *
 * 这个测试已经抓到过一次真实漂移：手机端最初把 `displays` 做成扁平矩形，而 PC 是
 * `{ id, bounds, scaleFactor? }`。形状不同这件事，读代码时极易看漏，只有真机对跑才暴露。
 *
 * ## 向量怎么更新
 *
 * [FloatLayoutVectors] 由 phone-b 用**只读**方式编译 PC 的 layout.ts 并逐函数驱动生成
 * （tsc 产物落 TEMP，**不写进 desktop 的 .test 目录**，避免与 PC 端并发测试互踩）。
 * 顺带记一个 Kotlin 的坑：**块注释可嵌套**。所以在 KDoc 里写下形如「目录斜杠 + 星号」的
 * 字面量（本文原写成 desktop 的 .test 目录后跟两个星号收尾）会在注释内部再开一个注释，
 * 后面的星号斜杠只闭合内层，报错还落在文件末行，与真正出问题的行号毫无关系 ——
 * 我第一次写这段说明时自己又踩了一遍。
 * 其 `SOURCE_COMMIT` 记录生成时的来源。
 *
 * 更新流程：PC 改了 layout.ts -> 重新生成向量 -> `SOURCE_COMMIT` 变了就**必须**重跑本测试，
 * 并同时核对 PC 自己的 `npm run test:float`。只改一边，几何就漂了。
 *
 * 这正是 `docs/lessons.md` F1「副本漂移」的解法：把「靠人读代码保持一致」换成「靠机器回放保持一致」。
 */
class FloatLayoutConformanceTest {

    private fun assertRectEq(expected: FloatRect, actual: FloatRect, what: String) {
        // 非有限值不能靠等号比（NaN != NaN），按位判定。
        fun same(a: Float, b: Float) = if (a.isNaN() || b.isNaN()) a.isNaN() && b.isNaN() else a == b
        assertTrue("$what x 不一致：PC=$expected 本端=$actual", same(expected.x, actual.x))
        assertTrue("$what y 不一致：PC=$expected 本端=$actual", same(expected.y, actual.y))
        assertTrue("$what width 不一致：PC=$expected 本端=$actual", same(expected.width, actual.width))
        assertTrue("$what height 不一致：PC=$expected 本端=$actual", same(expected.height, actual.height))
    }

    private fun assertSnapshotEq(expected: LayoutSnapshot, actual: LayoutSnapshot, what: String) {
        assertEquals("$what anchor 不一致", expected.anchor, actual.anchor)
        for (role in PANE_ORDER) {
            val e = expected.panes[role] ?: error("$what PC 侧缺 $role")
            val a = actual.panes[role] ?: error("$what 本端缺 $role")
            assertRectEq(e.rect, a.rect, "$what/$role")
            assertEquals("$what/$role linked 不一致", e.linked, a.linked)
        }
    }

    @Test
    fun `stackY 与 PC 逐值一致`() {
        for (c in FloatLayoutVectors.STACK_Y) {
            assertEquals("stackY index=${c.index} gap=${c.gap}", c.expect, stackY(c.anchorY, c.heights, c.index, c.gap))
        }
    }

    @Test
    fun `clampToDisplay 与 PC 逐值一致`() {
        for (c in FloatLayoutVectors.CLAMP) {
            assertRectEq(c.expect, clampToDisplay(c.rect, c.displays), "clamp[${c.rect} displays=${c.displays.size}]")
        }
    }

    @Test
    fun `snapRect 与 PC 逐值一致`() {
        for (c in FloatLayoutVectors.SNAP) {
            val got = snapRect(c.rect, c.target, c.threshold)
            if (c.expect == null) {
                assertTrue("PC 判定不吸，本端却吸了：$c", got == null)
            } else {
                assertTrue("PC 吸了，本端没吸：$c", got != null)
                assertEquals("snapRect edge 不一致（注意边名指 target 侧，不是移动窗侧）", c.expect.edge, got!!.edge)
                assertEquals("snapRect x 不一致", c.expect.x, got.x)
                assertEquals("snapRect y 不一致", c.expect.y, got.y)
            }
        }
    }

    @Test
    fun `relayout 与 PC 逐值一致`() {
        for (c in FloatLayoutVectors.RELAYOUT) {
            assertSnapshotEq(c.expect, relayout(c.snap, FloatLayoutConfig(gap = c.gap)), "relayout[gap=${c.gap}]")
        }
    }

    @Test
    fun `movePane 与 PC 逐值一致`() {
        for (c in FloatLayoutVectors.MOVE_PANE) {
            assertSnapshotEq(
                c.expect,
                movePane(c.snap, c.role, c.next, FloatLayoutConfig(gap = c.gap)),
                "movePane[${c.role}]",
            )
        }
    }

    @Test
    fun `defaultLayout 与 PC 逐值一致`() {
        for (c in FloatLayoutVectors.DEFAULT_LAYOUT) {
            assertSnapshotEq(
                c.expect,
                defaultLayout(c.input, c.menuH, c.outputH, FloatLayoutConfig(gap = c.gap)),
                "defaultLayout[gap=${c.gap}]",
            )
        }
    }

    @Test
    fun `isStillLinked 与 PC 逐值一致`() {
        for (c in FloatLayoutVectors.DRIFT) {
            val cfg = c.maxDrift?.let { FloatLayoutConfig(maxDrift = it) } ?: FloatLayoutConfig()
            assertEquals("isStillLinked[${c.role} maxDrift=${c.maxDrift}]", c.expect, isStillLinked(c.snap, c.role, cfg))
        }
    }

    @Test
    fun `linked 标志与 PC 逐值一致`() {
        for (c in FloatLayoutVectors.FLAGS) {
            // 向量已把三种状态都算好了，这里只验证本端能复现同样的判定
            assertEquals("base allLinked", c.baseAll, allLinked(c.base))
            assertEquals("base anyDetached", c.baseAny, anyDetached(c.base))
            assertEquals("unlinked allLinked", c.unlinkedAll, allLinked(c.unlinked))
            assertEquals("unlinked anyDetached", c.unlinkedAny, anyDetached(c.unlinked))
            assertEquals("allOff allLinked", c.allOffAll, allLinked(c.allOff))
            assertEquals("allOff anyDetached", c.allOffAny, anyDetached(c.allOff))
            // 并且本端自己推导出的状态必须与 PC 一致（而不只是自己跟自己一致）
            assertSnapshotEq(c.unlinked, setLinked(c.base, PaneRole.OUTPUT, false), "setLinked(OUTPUT,false)")
            assertSnapshotEq(c.allOff, setAllLinked(c.base, false), "setAllLinked(false)")
        }
    }

    // ---- 向量集自身的自检：覆盖出现空洞时回放测试就白写了 ----

    @Test
    fun `向量集覆盖到每个函数且不为空`() {
        assertTrue(FloatLayoutVectors.STACK_Y.isNotEmpty())
        assertTrue(FloatLayoutVectors.CLAMP.isNotEmpty())
        assertTrue(FloatLayoutVectors.SNAP.isNotEmpty())
        assertTrue(FloatLayoutVectors.RELAYOUT.isNotEmpty())
        assertTrue(FloatLayoutVectors.MOVE_PANE.isNotEmpty())
        assertTrue(FloatLayoutVectors.DRIFT.isNotEmpty())
        assertTrue(FloatLayoutVectors.FLAGS.isNotEmpty())
        assertTrue(FloatLayoutVectors.DEFAULT_LAYOUT.isNotEmpty())
    }

    @Test
    fun `阈值边界与同距稳定性都有向量钉住`() {
        // PC 把这两条写进了注释（「d<=12 吸」「同距按声明顺序取 right」），
        // 一旦哪天实现改了，回放必须立刻红。
        val atThreshold = FloatLayoutVectors.SNAP.count { it.threshold == 12f }
        assertTrue("阈值 12 附近的向量不足", atThreshold >= 6)
        val edges = FloatLayoutVectors.SNAP.mapNotNull { it.expect?.edge }.toSet()
        assertEquals("四条边必须都有向量覆盖，否则有一侧的边名语义无人校验", 4, edges.size)
    }

    @Test
    fun `多屏与 inset 场景都有向量`() {
        assertTrue("没有多屏向量，「取最大重叠而非第一块屏」无人校验", FloatLayoutVectors.CLAMP.any { it.displays.size > 1 })
        // 1080x2400 减去上下 inset 后的可用区，即手机端真实场景
        assertTrue("没有扣掉状态栏/导航栏的 inset 场景", FloatLayoutVectors.CLAMP.any { c ->
            c.displays.any { it.bounds.height < 2400f }
        })
    }

    @Test
    fun `向量记录了来源 commit 便于发现过期`() {
        assertTrue(
            "向量没记 SOURCE_COMMIT，日后无法判断是否已过期",
            FloatLayoutVectors.SOURCE_COMMIT.contains("459e05b"),
        )
    }
}