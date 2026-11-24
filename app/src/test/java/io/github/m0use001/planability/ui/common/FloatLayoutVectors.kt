package io.github.m0use001.planability.ui.common

/**
 * **跨端一致性测试向量 —— 由 PC 端 `desktop/src/main/float/layout.ts` 的真实实现生成，勿手改。**
 *
 * 生成器：phone-b 的只读脚本（tsc 产物落 TEMP，不写进 desktop/.test/，避免与 PC 并发测试互踩）。
 * 来源 commit：SOURCE_COMMIT —— **它变了就必须重新生成并重跑 FloatLayoutConformanceTest**，
 * 且要同时核对 PC 侧自己的 `npm run test:float`。只改一边，几何就漂了。
 *
 * 为什么需要这个：两端无共享代码，本文件与那份 TS 是手工维护的两份副本；而这个引擎的 bug
 * 全是算术错误 —— 不报错、不崩溃，只在用户把窗拖到边缘时才显形。手写单测只能证明
 * 「我理解了我读过的代码」，证明不了「我的行为和他们的行为一致」。
 * PC 的 conformance.ts 只做 TS 内部编译期类型断言，管不到跨语言这一半。
 *
 * 为什么是 Kotlin 而不是 JSON：Android 单元测试里的 org.json 是 stub（调用即抛），
 * 用它就得改 app/build.gradle.kts 加依赖，那是共享文件，不值得为一份 fixture 去动。
 */
internal object FloatLayoutVectors {
    /** 生成时 PC 端 layout.ts 的 commit，便于发现向量已过期。 */
    const val SOURCE_COMMIT = "7bea809b1fed1258ef1b4eac5e842d49fa494513 (layout.ts 459e05b)"

    data class StackYCase(val anchorY: Float, val heights: Map<PaneRole, Float>, val index: Int, val gap: Float, val expect: Float)
    data class ClampCase(val rect: FloatRect, val displays: List<FloatDisplay>, val expect: FloatRect)
    data class SnapCase(val rect: FloatRect, val target: FloatRect, val threshold: Float, val expect: SnapResult?)
    data class RelayoutCase(val snap: LayoutSnapshot, val gap: Float, val expect: LayoutSnapshot)
    data class MovePaneCase(val snap: LayoutSnapshot, val role: PaneRole, val next: FloatRect, val gap: Float, val expect: LayoutSnapshot)
    data class DriftCase(val snap: LayoutSnapshot, val role: PaneRole, val maxDrift: Float?, val expect: Boolean)
    data class FlagsCase(
        val base: LayoutSnapshot, val baseAll: Boolean, val baseAny: Boolean,
        val unlinked: LayoutSnapshot, val unlinkedAll: Boolean, val unlinkedAny: Boolean,
        val allOff: LayoutSnapshot, val allOffAll: Boolean, val allOffAny: Boolean,
    )
    data class DefaultLayoutCase(val input: FloatRect, val menuH: Float, val outputH: Float, val gap: Float, val expect: LayoutSnapshot)

    val STACK_Y = listOf(
        StackYCase(0f, mapOf(PaneRole.INPUT to 120f, PaneRole.MENU to 44f, PaneRole.OUTPUT to 300f), 0, 0f, 0f),
        StackYCase(0f, mapOf(PaneRole.INPUT to 120f, PaneRole.MENU to 44f, PaneRole.OUTPUT to 300f), 1, 0f, 120f),
        StackYCase(0f, mapOf(PaneRole.INPUT to 120f, PaneRole.MENU to 44f, PaneRole.OUTPUT to 300f), 2, 0f, 164f),
        StackYCase(0f, mapOf(PaneRole.INPUT to 120f, PaneRole.MENU to 44f, PaneRole.OUTPUT to 300f), 0, 10f, 0f),
        StackYCase(0f, mapOf(PaneRole.INPUT to 120f, PaneRole.MENU to 44f, PaneRole.OUTPUT to 300f), 1, 10f, 130f),
        StackYCase(0f, mapOf(PaneRole.INPUT to 120f, PaneRole.MENU to 44f, PaneRole.OUTPUT to 300f), 2, 10f, 184f),
        StackYCase(0f, mapOf(PaneRole.INPUT to 120f, PaneRole.MENU to 44f, PaneRole.OUTPUT to 300f), 0, -4f, 0f),
        StackYCase(0f, mapOf(PaneRole.INPUT to 120f, PaneRole.MENU to 44f, PaneRole.OUTPUT to 300f), 1, -4f, 116f),
        StackYCase(0f, mapOf(PaneRole.INPUT to 120f, PaneRole.MENU to 44f, PaneRole.OUTPUT to 300f), 2, -4f, 156f),
        StackYCase(0f, mapOf(PaneRole.INPUT to 0f, PaneRole.MENU to 0f, PaneRole.OUTPUT to 50f), 2, 0f, 0f),
    )
    val CLAMP = listOf(
        ClampCase(FloatRect(0f, 2330f, 800f, 44f), listOf(FloatDisplay(0, FloatRect(0f, 0f, 1080f, 2400f), 3.5f)), FloatRect(0f, 2330f, 800f, 44f)),
        ClampCase(FloatRect(0f, 2450f, 800f, 120f), listOf(FloatDisplay(0, FloatRect(0f, 0f, 1080f, 2400f), 3.5f)), FloatRect(0f, 2320f, 800f, 120f)),
        ClampCase(FloatRect(-50f, 100f, 200f, 200f), listOf(FloatDisplay(0, FloatRect(0f, 0f, 1080f, 2400f), 3.5f)), FloatRect(-50f, 100f, 200f, 200f)),
        ClampCase(FloatRect(-500f, 100f, 200f, 200f), listOf(FloatDisplay(0, FloatRect(0f, 0f, 1080f, 2400f), 3.5f)), FloatRect(0f, 100f, 200f, 200f)),
        ClampCase(FloatRect(2000f, 1000f, 200f, 200f), listOf(FloatDisplay(0, FloatRect(0f, 0f, 1080f, 2400f), 3.5f), FloatDisplay(2, FloatRect(1080f, 0f, 1080f, 2400f), 1f)), FloatRect(2000f, 1000f, 200f, 200f)),
        ClampCase(FloatRect(1050f, 1000f, 100f, 200f), listOf(FloatDisplay(0, FloatRect(0f, 0f, 1080f, 2400f), 3.5f), FloatDisplay(2, FloatRect(1080f, 0f, 1080f, 2400f), 1f)), FloatRect(1080f, 1000f, 100f, 200f)),
        ClampCase(FloatRect(1030f, 1000f, 200f, 200f), listOf(FloatDisplay(0, FloatRect(0f, 0f, 1080f, 2400f), 3.5f), FloatDisplay(2, FloatRect(1080f, 0f, 1080f, 2400f), 1f)), FloatRect(1030f, 1000f, 200f, 200f)),
        ClampCase(FloatRect(900f, 1000f, 200f, 200f), listOf(FloatDisplay(0, FloatRect(0f, 0f, 1080f, 2400f), 3.5f), FloatDisplay(2, FloatRect(1080f, 0f, 1080f, 2400f), 1f)), FloatRect(900f, 1000f, 200f, 200f)),
        ClampCase(FloatRect(0f, 100f, 400f, 300f), listOf(FloatDisplay(0, FloatRect(0f, 96f, 1080f, 2160f), 3.5f)), FloatRect(0f, 100f, 400f, 300f)),
        ClampCase(FloatRect(-40f, -40f, 120f, 120f), listOf(FloatDisplay(0, FloatRect(0f, 96f, 1080f, 2160f), 3.5f)), FloatRect(-40f, 96f, 120f, 120f)),
        ClampCase(FloatRect(10f, 10f, 5f, 5f), listOf(FloatDisplay(0, FloatRect(0f, 0f, 1080f, 2400f), 3.5f)), FloatRect(10f, 10f, 5f, 5f)),
        ClampCase(FloatRect(Float.NaN, 0f, 10f, 10f), listOf(FloatDisplay(0, FloatRect(0f, 0f, 1080f, 2400f), 3.5f)), FloatRect(Float.NaN, 0f, 10f, 10f)),
        ClampCase(FloatRect(0f, 0f, Float.POSITIVE_INFINITY, 10f), listOf(FloatDisplay(0, FloatRect(0f, 0f, 1080f, 2400f), 3.5f)), FloatRect(0f, 0f, Float.POSITIVE_INFINITY, 10f)),
        ClampCase(FloatRect(0f, 0f, 10f, 10f), listOf(), FloatRect(0f, 0f, 10f, 10f)),
    )
    val SNAP = listOf(
        SnapCase(FloatRect(296f, 150f, 100f, 50f), FloatRect(100f, 100f, 200f, 200f), 12f, SnapResult(300f, 150f, SnapEdge.RIGHT)),
        SnapCase(FloatRect(8f, 150f, 100f, 50f), FloatRect(100f, 100f, 200f, 200f), 12f, SnapResult(0f, 150f, SnapEdge.LEFT)),
        SnapCase(FloatRect(150f, 296f, 50f, 100f), FloatRect(100f, 100f, 200f, 200f), 12f, SnapResult(150f, 300f, SnapEdge.BOTTOM)),
        SnapCase(FloatRect(150f, 0f, 50f, 100f), FloatRect(100f, 100f, 200f, 200f), 12f, SnapResult(150f, 0f, SnapEdge.TOP)),
        SnapCase(FloatRect(295f, 295f, 30f, 30f), FloatRect(100f, 100f, 200f, 200f), 12f, SnapResult(300f, 295f, SnapEdge.RIGHT)),
        SnapCase(FloatRect(288f, 150f, 60f, 40f), FloatRect(100f, 100f, 200f, 200f), 12f, SnapResult(300f, 150f, SnapEdge.RIGHT)),
        SnapCase(FloatRect(287f, 150f, 60f, 40f), FloatRect(100f, 100f, 200f, 200f), 12f, null),
        SnapCase(FloatRect(270f, 150f, 60f, 40f), FloatRect(100f, 100f, 200f, 200f), 12f, null),
        SnapCase(FloatRect(270f, 150f, 60f, 40f), FloatRect(100f, 100f, 200f, 200f), 40f, SnapResult(300f, 150f, SnapEdge.RIGHT)),
        SnapCase(FloatRect(8f, 400f, 100f, 500f), FloatRect(100f, 100f, 200f, 200f), 12f, null),
        SnapCase(FloatRect(400f, 296f, 100f, 50f), FloatRect(100f, 100f, 200f, 200f), 12f, null),
        SnapCase(FloatRect(0f, 0f, 300f, 300f), FloatRect(100f, 100f, 200f, 200f), 0f, null),
        SnapCase(FloatRect(-300f, -300f, 100f, 100f), FloatRect(100f, 100f, 200f, 200f), 12f, null),
        SnapCase(FloatRect(96f, 96f, 10f, 10f), FloatRect(100f, 100f, 200f, 200f), 12f, SnapResult(90f, 96f, SnapEdge.LEFT)),
        SnapCase(FloatRect(300f, 300f, 10f, 10f), FloatRect(100f, 100f, 200f, 200f), 0f, null),
    )
    val RELAYOUT = listOf(
        RelayoutCase(LayoutSnapshot(
mapOf(
            PaneRole.INPUT to PaneState(FloatRect(0f, 0f, 800f, 120f), true),
            PaneRole.MENU to PaneState(FloatRect(0f, 120f, 800f, 44f), true),
            PaneRole.OUTPUT to PaneState(FloatRect(0f, 164f, 800f, 300f), true),
        ),
            PaneRole.INPUT,
        ), 0f, LayoutSnapshot(
mapOf(
            PaneRole.INPUT to PaneState(FloatRect(0f, 0f, 800f, 120f), true),
            PaneRole.MENU to PaneState(FloatRect(0f, 120f, 800f, 44f), true),
            PaneRole.OUTPUT to PaneState(FloatRect(0f, 164f, 800f, 300f), true),
        ),
            PaneRole.INPUT,
        )),
        RelayoutCase(LayoutSnapshot(
mapOf(
            PaneRole.INPUT to PaneState(FloatRect(0f, 0f, 800f, 120f), true),
            PaneRole.MENU to PaneState(FloatRect(0f, 120f, 800f, 44f), true),
            PaneRole.OUTPUT to PaneState(FloatRect(0f, 164f, 800f, 300f), true),
        ),
            PaneRole.INPUT,
        ), 8f, LayoutSnapshot(
mapOf(
            PaneRole.INPUT to PaneState(FloatRect(0f, 0f, 800f, 120f), true),
            PaneRole.MENU to PaneState(FloatRect(0f, 128f, 800f, 44f), true),
            PaneRole.OUTPUT to PaneState(FloatRect(0f, 180f, 800f, 300f), true),
        ),
            PaneRole.INPUT,
        )),
        RelayoutCase(LayoutSnapshot(
mapOf(
            PaneRole.INPUT to PaneState(FloatRect(50f, 60f, 800f, 120f), true),
            PaneRole.MENU to PaneState(FloatRect(999f, 999f, 100f, 44f), false),
            PaneRole.OUTPUT to PaneState(FloatRect(0f, 164f, 800f, 300f), true),
        ),
            PaneRole.INPUT,
        ), 0f, LayoutSnapshot(
mapOf(
            PaneRole.INPUT to PaneState(FloatRect(50f, 60f, 800f, 120f), true),
            PaneRole.MENU to PaneState(FloatRect(999f, 999f, 100f, 44f), false),
            PaneRole.OUTPUT to PaneState(FloatRect(50f, 224f, 800f, 300f), true),
        ),
            PaneRole.INPUT,
        )),
        RelayoutCase(LayoutSnapshot(
mapOf(
            PaneRole.INPUT to PaneState(FloatRect(50f, 60f, 800f, 120f), true),
            PaneRole.MENU to PaneState(FloatRect(999f, 999f, 100f, 44f), false),
            PaneRole.OUTPUT to PaneState(FloatRect(0f, 164f, 800f, 300f), true),
        ),
            PaneRole.INPUT,
        ), 8f, LayoutSnapshot(
mapOf(
            PaneRole.INPUT to PaneState(FloatRect(50f, 60f, 800f, 120f), true),
            PaneRole.MENU to PaneState(FloatRect(999f, 999f, 100f, 44f), false),
            PaneRole.OUTPUT to PaneState(FloatRect(50f, 240f, 800f, 300f), true),
        ),
            PaneRole.INPUT,
        )),
        RelayoutCase(LayoutSnapshot(
mapOf(
            PaneRole.INPUT to PaneState(FloatRect(50f, 60f, 800f, 120f), true),
            PaneRole.MENU to PaneState(FloatRect(50f, 180f, 800f, 44f), true),
            PaneRole.OUTPUT to PaneState(FloatRect(50f, 224f, 800f, 777f), false),
        ),
            PaneRole.OUTPUT,
        ), 0f, LayoutSnapshot(
mapOf(
            PaneRole.INPUT to PaneState(FloatRect(50f, 224f, 800f, 120f), true),
            PaneRole.MENU to PaneState(FloatRect(50f, 344f, 800f, 44f), true),
            PaneRole.OUTPUT to PaneState(FloatRect(50f, 224f, 800f, 777f), false),
        ),
            PaneRole.OUTPUT,
        )),
        RelayoutCase(LayoutSnapshot(
mapOf(
            PaneRole.INPUT to PaneState(FloatRect(50f, 60f, 800f, 120f), true),
            PaneRole.MENU to PaneState(FloatRect(50f, 180f, 800f, 44f), true),
            PaneRole.OUTPUT to PaneState(FloatRect(50f, 224f, 800f, 777f), false),
        ),
            PaneRole.OUTPUT,
        ), 8f, LayoutSnapshot(
mapOf(
            PaneRole.INPUT to PaneState(FloatRect(50f, 224f, 800f, 120f), true),
            PaneRole.MENU to PaneState(FloatRect(50f, 352f, 800f, 44f), true),
            PaneRole.OUTPUT to PaneState(FloatRect(50f, 224f, 800f, 777f), false),
        ),
            PaneRole.OUTPUT,
        )),
        RelayoutCase(LayoutSnapshot(
mapOf(
            PaneRole.INPUT to PaneState(FloatRect(10f, 20f, 400f, 60f), false),
            PaneRole.MENU to PaneState(FloatRect(500f, 900f, 90f, 40f), true),
            PaneRole.OUTPUT to PaneState(FloatRect(0f, 0f, 10f, 10f), true),
        ),
            PaneRole.INPUT,
        ), 0f, LayoutSnapshot(
mapOf(
            PaneRole.INPUT to PaneState(FloatRect(10f, 20f, 400f, 60f), false),
            PaneRole.MENU to PaneState(FloatRect(10f, 80f, 400f, 40f), true),
            PaneRole.OUTPUT to PaneState(FloatRect(10f, 120f, 400f, 10f), true),
        ),
            PaneRole.INPUT,
        )),
        RelayoutCase(LayoutSnapshot(
mapOf(
            PaneRole.INPUT to PaneState(FloatRect(10f, 20f, 400f, 60f), false),
            PaneRole.MENU to PaneState(FloatRect(500f, 900f, 90f, 40f), true),
            PaneRole.OUTPUT to PaneState(FloatRect(0f, 0f, 10f, 10f), true),
        ),
            PaneRole.INPUT,
        ), 8f, LayoutSnapshot(
mapOf(
            PaneRole.INPUT to PaneState(FloatRect(10f, 20f, 400f, 60f), false),
            PaneRole.MENU to PaneState(FloatRect(10f, 88f, 400f, 40f), true),
            PaneRole.OUTPUT to PaneState(FloatRect(10f, 136f, 400f, 10f), true),
        ),
            PaneRole.INPUT,
        )),
    )
    val MOVE_PANE = listOf(
        MovePaneCase(LayoutSnapshot(
mapOf(
            PaneRole.INPUT to PaneState(FloatRect(0f, 0f, 800f, 120f), true),
            PaneRole.MENU to PaneState(FloatRect(0f, 120f, 800f, 44f), true),
            PaneRole.OUTPUT to PaneState(FloatRect(0f, 164f, 800f, 300f), true),
        ),
            PaneRole.INPUT,
        ), PaneRole.INPUT, FloatRect(300f, 500f, 800f, 120f), 0f, LayoutSnapshot(
mapOf(
            PaneRole.INPUT to PaneState(FloatRect(300f, 500f, 800f, 120f), true),
            PaneRole.MENU to PaneState(FloatRect(300f, 620f, 800f, 44f), true),
            PaneRole.OUTPUT to PaneState(FloatRect(300f, 664f, 800f, 300f), true),
        ),
            PaneRole.INPUT,
        )),
        MovePaneCase(LayoutSnapshot(
mapOf(
            PaneRole.INPUT to PaneState(FloatRect(0f, 0f, 800f, 120f), true),
            PaneRole.MENU to PaneState(FloatRect(0f, 120f, 800f, 44f), true),
            PaneRole.OUTPUT to PaneState(FloatRect(0f, 164f, 800f, 300f), true),
        ),
            PaneRole.INPUT,
        ), PaneRole.MENU, FloatRect(500f, 900f, 100f, 44f), 0f, LayoutSnapshot(
mapOf(
            PaneRole.INPUT to PaneState(FloatRect(0f, 0f, 800f, 120f), true),
            PaneRole.MENU to PaneState(FloatRect(0f, 120f, 800f, 44f), true),
            PaneRole.OUTPUT to PaneState(FloatRect(0f, 164f, 800f, 300f), true),
        ),
            PaneRole.INPUT,
        )),
        MovePaneCase(LayoutSnapshot(
mapOf(
            PaneRole.INPUT to PaneState(FloatRect(50f, 60f, 800f, 120f), true),
            PaneRole.MENU to PaneState(FloatRect(999f, 999f, 100f, 44f), false),
            PaneRole.OUTPUT to PaneState(FloatRect(0f, 164f, 800f, 300f), true),
        ),
            PaneRole.INPUT,
        ), PaneRole.MENU, FloatRect(500f, 900f, 100f, 44f), 0f, LayoutSnapshot(
mapOf(
            PaneRole.INPUT to PaneState(FloatRect(50f, 60f, 800f, 120f), true),
            PaneRole.MENU to PaneState(FloatRect(500f, 900f, 100f, 44f), false),
            PaneRole.OUTPUT to PaneState(FloatRect(0f, 164f, 800f, 300f), true),
        ),
            PaneRole.INPUT,
        )),
        MovePaneCase(LayoutSnapshot(
mapOf(
            PaneRole.INPUT to PaneState(FloatRect(50f, 60f, 800f, 120f), true),
            PaneRole.MENU to PaneState(FloatRect(999f, 999f, 100f, 44f), false),
            PaneRole.OUTPUT to PaneState(FloatRect(0f, 164f, 800f, 300f), true),
        ),
            PaneRole.INPUT,
        ), PaneRole.OUTPUT, FloatRect(10f, 10f, 50f, 50f), 0f, LayoutSnapshot(
mapOf(
            PaneRole.INPUT to PaneState(FloatRect(50f, 60f, 800f, 120f), true),
            PaneRole.MENU to PaneState(FloatRect(999f, 999f, 100f, 44f), false),
            PaneRole.OUTPUT to PaneState(FloatRect(50f, 224f, 800f, 50f), true),
        ),
            PaneRole.INPUT,
        )),
        MovePaneCase(LayoutSnapshot(
mapOf(
            PaneRole.INPUT to PaneState(FloatRect(50f, 60f, 800f, 120f), true),
            PaneRole.MENU to PaneState(FloatRect(50f, 180f, 800f, 44f), true),
            PaneRole.OUTPUT to PaneState(FloatRect(50f, 224f, 800f, 777f), false),
        ),
            PaneRole.OUTPUT,
        ), PaneRole.MENU, FloatRect(500f, 900f, 100f, 44f), 0f, LayoutSnapshot(
mapOf(
            PaneRole.INPUT to PaneState(FloatRect(50f, 224f, 800f, 120f), true),
            PaneRole.MENU to PaneState(FloatRect(50f, 344f, 800f, 44f), true),
            PaneRole.OUTPUT to PaneState(FloatRect(50f, 224f, 800f, 777f), false),
        ),
            PaneRole.OUTPUT,
        )),
        MovePaneCase(LayoutSnapshot(
mapOf(
            PaneRole.INPUT to PaneState(FloatRect(50f, 60f, 800f, 120f), true),
            PaneRole.MENU to PaneState(FloatRect(50f, 180f, 800f, 44f), true),
            PaneRole.OUTPUT to PaneState(FloatRect(50f, 224f, 800f, 777f), false),
        ),
            PaneRole.OUTPUT,
        ), PaneRole.OUTPUT, FloatRect(-5f, -5f, 700f, 100f), 0f, LayoutSnapshot(
mapOf(
            PaneRole.INPUT to PaneState(FloatRect(-5f, -5f, 700f, 120f), true),
            PaneRole.MENU to PaneState(FloatRect(-5f, 115f, 700f, 44f), true),
            PaneRole.OUTPUT to PaneState(FloatRect(-5f, -5f, 700f, 100f), false),
        ),
            PaneRole.OUTPUT,
        )),
        MovePaneCase(LayoutSnapshot(
mapOf(
            PaneRole.INPUT to PaneState(FloatRect(10f, 20f, 400f, 60f), false),
            PaneRole.MENU to PaneState(FloatRect(500f, 900f, 90f, 40f), true),
            PaneRole.OUTPUT to PaneState(FloatRect(0f, 0f, 10f, 10f), true),
        ),
            PaneRole.INPUT,
        ), PaneRole.INPUT, FloatRect(700f, 1200f, 300f, 100f), 0f, LayoutSnapshot(
mapOf(
            PaneRole.INPUT to PaneState(FloatRect(700f, 1200f, 300f, 100f), false),
            PaneRole.MENU to PaneState(FloatRect(700f, 1300f, 300f, 40f), true),
            PaneRole.OUTPUT to PaneState(FloatRect(700f, 1340f, 300f, 10f), true),
        ),
            PaneRole.INPUT,
        )),
        MovePaneCase(LayoutSnapshot(
mapOf(
            PaneRole.INPUT to PaneState(FloatRect(10f, 20f, 400f, 60f), false),
            PaneRole.MENU to PaneState(FloatRect(500f, 900f, 90f, 40f), true),
            PaneRole.OUTPUT to PaneState(FloatRect(0f, 0f, 10f, 10f), true),
        ),
            PaneRole.INPUT,
        ), PaneRole.OUTPUT, FloatRect(0f, 0f, 50f, 50f), 0f, LayoutSnapshot(
mapOf(
            PaneRole.INPUT to PaneState(FloatRect(10f, 20f, 400f, 60f), false),
            PaneRole.MENU to PaneState(FloatRect(10f, 80f, 400f, 40f), true),
            PaneRole.OUTPUT to PaneState(FloatRect(10f, 120f, 400f, 50f), true),
        ),
            PaneRole.INPUT,
        )),
    )
    val DRIFT = listOf(
        DriftCase(LayoutSnapshot(
mapOf(
            PaneRole.INPUT to PaneState(FloatRect(0f, 0f, 800f, 120f), true),
            PaneRole.MENU to PaneState(FloatRect(0f, 120f, 800f, 44f), true),
            PaneRole.OUTPUT to PaneState(FloatRect(0f, 164f, 800f, 300f), true),
        ),
            PaneRole.INPUT,
        ), PaneRole.INPUT, null, true),
        DriftCase(LayoutSnapshot(
mapOf(
            PaneRole.INPUT to PaneState(FloatRect(0f, 0f, 800f, 120f), true),
            PaneRole.MENU to PaneState(FloatRect(0f, 120f, 800f, 44f), true),
            PaneRole.OUTPUT to PaneState(FloatRect(0f, 164f, 800f, 300f), true),
        ),
            PaneRole.INPUT,
        ), PaneRole.INPUT, 0f, true),
        DriftCase(LayoutSnapshot(
mapOf(
            PaneRole.INPUT to PaneState(FloatRect(0f, 0f, 800f, 120f), true),
            PaneRole.MENU to PaneState(FloatRect(0f, 120f, 800f, 44f), true),
            PaneRole.OUTPUT to PaneState(FloatRect(0f, 164f, 800f, 300f), true),
        ),
            PaneRole.INPUT,
        ), PaneRole.INPUT, 400f, true),
        DriftCase(LayoutSnapshot(
mapOf(
            PaneRole.INPUT to PaneState(FloatRect(0f, 0f, 800f, 120f), true),
            PaneRole.MENU to PaneState(FloatRect(0f, 120f, 800f, 44f), true),
            PaneRole.OUTPUT to PaneState(FloatRect(0f, 164f, 800f, 300f), true),
        ),
            PaneRole.INPUT,
        ), PaneRole.INPUT, 100000f, true),
        DriftCase(LayoutSnapshot(
mapOf(
            PaneRole.INPUT to PaneState(FloatRect(0f, 0f, 800f, 120f), true),
            PaneRole.MENU to PaneState(FloatRect(0f, 120f, 800f, 44f), true),
            PaneRole.OUTPUT to PaneState(FloatRect(0f, 164f, 800f, 300f), true),
        ),
            PaneRole.INPUT,
        ), PaneRole.MENU, null, true),
        DriftCase(LayoutSnapshot(
mapOf(
            PaneRole.INPUT to PaneState(FloatRect(0f, 0f, 800f, 120f), true),
            PaneRole.MENU to PaneState(FloatRect(0f, 120f, 800f, 44f), true),
            PaneRole.OUTPUT to PaneState(FloatRect(0f, 164f, 800f, 300f), true),
        ),
            PaneRole.INPUT,
        ), PaneRole.MENU, 0f, false),
        DriftCase(LayoutSnapshot(
mapOf(
            PaneRole.INPUT to PaneState(FloatRect(0f, 0f, 800f, 120f), true),
            PaneRole.MENU to PaneState(FloatRect(0f, 120f, 800f, 44f), true),
            PaneRole.OUTPUT to PaneState(FloatRect(0f, 164f, 800f, 300f), true),
        ),
            PaneRole.INPUT,
        ), PaneRole.MENU, 400f, true),
        DriftCase(LayoutSnapshot(
mapOf(
            PaneRole.INPUT to PaneState(FloatRect(0f, 0f, 800f, 120f), true),
            PaneRole.MENU to PaneState(FloatRect(0f, 120f, 800f, 44f), true),
            PaneRole.OUTPUT to PaneState(FloatRect(0f, 164f, 800f, 300f), true),
        ),
            PaneRole.INPUT,
        ), PaneRole.MENU, 100000f, true),
        DriftCase(LayoutSnapshot(
mapOf(
            PaneRole.INPUT to PaneState(FloatRect(0f, 0f, 800f, 120f), true),
            PaneRole.MENU to PaneState(FloatRect(0f, 120f, 800f, 44f), true),
            PaneRole.OUTPUT to PaneState(FloatRect(0f, 164f, 800f, 300f), true),
        ),
            PaneRole.INPUT,
        ), PaneRole.OUTPUT, null, true),
        DriftCase(LayoutSnapshot(
mapOf(
            PaneRole.INPUT to PaneState(FloatRect(0f, 0f, 800f, 120f), true),
            PaneRole.MENU to PaneState(FloatRect(0f, 120f, 800f, 44f), true),
            PaneRole.OUTPUT to PaneState(FloatRect(0f, 164f, 800f, 300f), true),
        ),
            PaneRole.INPUT,
        ), PaneRole.OUTPUT, 0f, false),
        DriftCase(LayoutSnapshot(
mapOf(
            PaneRole.INPUT to PaneState(FloatRect(0f, 0f, 800f, 120f), true),
            PaneRole.MENU to PaneState(FloatRect(0f, 120f, 800f, 44f), true),
            PaneRole.OUTPUT to PaneState(FloatRect(0f, 164f, 800f, 300f), true),
        ),
            PaneRole.INPUT,
        ), PaneRole.OUTPUT, 400f, true),
        DriftCase(LayoutSnapshot(
mapOf(
            PaneRole.INPUT to PaneState(FloatRect(0f, 0f, 800f, 120f), true),
            PaneRole.MENU to PaneState(FloatRect(0f, 120f, 800f, 44f), true),
            PaneRole.OUTPUT to PaneState(FloatRect(0f, 164f, 800f, 300f), true),
        ),
            PaneRole.INPUT,
        ), PaneRole.OUTPUT, 100000f, true),
        DriftCase(LayoutSnapshot(
mapOf(
            PaneRole.INPUT to PaneState(FloatRect(50f, 60f, 800f, 120f), true),
            PaneRole.MENU to PaneState(FloatRect(999f, 999f, 100f, 44f), false),
            PaneRole.OUTPUT to PaneState(FloatRect(0f, 164f, 800f, 300f), true),
        ),
            PaneRole.INPUT,
        ), PaneRole.INPUT, null, true),
        DriftCase(LayoutSnapshot(
mapOf(
            PaneRole.INPUT to PaneState(FloatRect(50f, 60f, 800f, 120f), true),
            PaneRole.MENU to PaneState(FloatRect(999f, 999f, 100f, 44f), false),
            PaneRole.OUTPUT to PaneState(FloatRect(0f, 164f, 800f, 300f), true),
        ),
            PaneRole.INPUT,
        ), PaneRole.INPUT, 0f, true),
        DriftCase(LayoutSnapshot(
mapOf(
            PaneRole.INPUT to PaneState(FloatRect(50f, 60f, 800f, 120f), true),
            PaneRole.MENU to PaneState(FloatRect(999f, 999f, 100f, 44f), false),
            PaneRole.OUTPUT to PaneState(FloatRect(0f, 164f, 800f, 300f), true),
        ),
            PaneRole.INPUT,
        ), PaneRole.INPUT, 400f, true),
        DriftCase(LayoutSnapshot(
mapOf(
            PaneRole.INPUT to PaneState(FloatRect(50f, 60f, 800f, 120f), true),
            PaneRole.MENU to PaneState(FloatRect(999f, 999f, 100f, 44f), false),
            PaneRole.OUTPUT to PaneState(FloatRect(0f, 164f, 800f, 300f), true),
        ),
            PaneRole.INPUT,
        ), PaneRole.INPUT, 100000f, true),
        DriftCase(LayoutSnapshot(
mapOf(
            PaneRole.INPUT to PaneState(FloatRect(50f, 60f, 800f, 120f), true),
            PaneRole.MENU to PaneState(FloatRect(999f, 999f, 100f, 44f), false),
            PaneRole.OUTPUT to PaneState(FloatRect(0f, 164f, 800f, 300f), true),
        ),
            PaneRole.INPUT,
        ), PaneRole.MENU, null, false),
        DriftCase(LayoutSnapshot(
mapOf(
            PaneRole.INPUT to PaneState(FloatRect(50f, 60f, 800f, 120f), true),
            PaneRole.MENU to PaneState(FloatRect(999f, 999f, 100f, 44f), false),
            PaneRole.OUTPUT to PaneState(FloatRect(0f, 164f, 800f, 300f), true),
        ),
            PaneRole.INPUT,
        ), PaneRole.MENU, 0f, false),
        DriftCase(LayoutSnapshot(
mapOf(
            PaneRole.INPUT to PaneState(FloatRect(50f, 60f, 800f, 120f), true),
            PaneRole.MENU to PaneState(FloatRect(999f, 999f, 100f, 44f), false),
            PaneRole.OUTPUT to PaneState(FloatRect(0f, 164f, 800f, 300f), true),
        ),
            PaneRole.INPUT,
        ), PaneRole.MENU, 400f, false),
        DriftCase(LayoutSnapshot(
mapOf(
            PaneRole.INPUT to PaneState(FloatRect(50f, 60f, 800f, 120f), true),
            PaneRole.MENU to PaneState(FloatRect(999f, 999f, 100f, 44f), false),
            PaneRole.OUTPUT to PaneState(FloatRect(0f, 164f, 800f, 300f), true),
        ),
            PaneRole.INPUT,
        ), PaneRole.MENU, 100000f, false),
        DriftCase(LayoutSnapshot(
mapOf(
            PaneRole.INPUT to PaneState(FloatRect(50f, 60f, 800f, 120f), true),
            PaneRole.MENU to PaneState(FloatRect(999f, 999f, 100f, 44f), false),
            PaneRole.OUTPUT to PaneState(FloatRect(0f, 164f, 800f, 300f), true),
        ),
            PaneRole.INPUT,
        ), PaneRole.OUTPUT, null, true),
        DriftCase(LayoutSnapshot(
mapOf(
            PaneRole.INPUT to PaneState(FloatRect(50f, 60f, 800f, 120f), true),
            PaneRole.MENU to PaneState(FloatRect(999f, 999f, 100f, 44f), false),
            PaneRole.OUTPUT to PaneState(FloatRect(0f, 164f, 800f, 300f), true),
        ),
            PaneRole.INPUT,
        ), PaneRole.OUTPUT, 0f, false),
        DriftCase(LayoutSnapshot(
mapOf(
            PaneRole.INPUT to PaneState(FloatRect(50f, 60f, 800f, 120f), true),
            PaneRole.MENU to PaneState(FloatRect(999f, 999f, 100f, 44f), false),
            PaneRole.OUTPUT to PaneState(FloatRect(0f, 164f, 800f, 300f), true),
        ),
            PaneRole.INPUT,
        ), PaneRole.OUTPUT, 400f, true),
        DriftCase(LayoutSnapshot(
mapOf(
            PaneRole.INPUT to PaneState(FloatRect(50f, 60f, 800f, 120f), true),
            PaneRole.MENU to PaneState(FloatRect(999f, 999f, 100f, 44f), false),
            PaneRole.OUTPUT to PaneState(FloatRect(0f, 164f, 800f, 300f), true),
        ),
            PaneRole.INPUT,
        ), PaneRole.OUTPUT, 100000f, true),
        DriftCase(LayoutSnapshot(
mapOf(
            PaneRole.INPUT to PaneState(FloatRect(50f, 60f, 800f, 120f), true),
            PaneRole.MENU to PaneState(FloatRect(50f, 180f, 800f, 44f), true),
            PaneRole.OUTPUT to PaneState(FloatRect(50f, 224f, 800f, 777f), false),
        ),
            PaneRole.OUTPUT,
        ), PaneRole.INPUT, null, true),
        DriftCase(LayoutSnapshot(
mapOf(
            PaneRole.INPUT to PaneState(FloatRect(50f, 60f, 800f, 120f), true),
            PaneRole.MENU to PaneState(FloatRect(50f, 180f, 800f, 44f), true),
            PaneRole.OUTPUT to PaneState(FloatRect(50f, 224f, 800f, 777f), false),
        ),
            PaneRole.OUTPUT,
        ), PaneRole.INPUT, 0f, false),
        DriftCase(LayoutSnapshot(
mapOf(
            PaneRole.INPUT to PaneState(FloatRect(50f, 60f, 800f, 120f), true),
            PaneRole.MENU to PaneState(FloatRect(50f, 180f, 800f, 44f), true),
            PaneRole.OUTPUT to PaneState(FloatRect(50f, 224f, 800f, 777f), false),
        ),
            PaneRole.OUTPUT,
        ), PaneRole.INPUT, 400f, true),
        DriftCase(LayoutSnapshot(
mapOf(
            PaneRole.INPUT to PaneState(FloatRect(50f, 60f, 800f, 120f), true),
            PaneRole.MENU to PaneState(FloatRect(50f, 180f, 800f, 44f), true),
            PaneRole.OUTPUT to PaneState(FloatRect(50f, 224f, 800f, 777f), false),
        ),
            PaneRole.OUTPUT,
        ), PaneRole.INPUT, 100000f, true),
        DriftCase(LayoutSnapshot(
mapOf(
            PaneRole.INPUT to PaneState(FloatRect(50f, 60f, 800f, 120f), true),
            PaneRole.MENU to PaneState(FloatRect(50f, 180f, 800f, 44f), true),
            PaneRole.OUTPUT to PaneState(FloatRect(50f, 224f, 800f, 777f), false),
        ),
            PaneRole.OUTPUT,
        ), PaneRole.MENU, null, true),
        DriftCase(LayoutSnapshot(
mapOf(
            PaneRole.INPUT to PaneState(FloatRect(50f, 60f, 800f, 120f), true),
            PaneRole.MENU to PaneState(FloatRect(50f, 180f, 800f, 44f), true),
            PaneRole.OUTPUT to PaneState(FloatRect(50f, 224f, 800f, 777f), false),
        ),
            PaneRole.OUTPUT,
        ), PaneRole.MENU, 0f, false),
        DriftCase(LayoutSnapshot(
mapOf(
            PaneRole.INPUT to PaneState(FloatRect(50f, 60f, 800f, 120f), true),
            PaneRole.MENU to PaneState(FloatRect(50f, 180f, 800f, 44f), true),
            PaneRole.OUTPUT to PaneState(FloatRect(50f, 224f, 800f, 777f), false),
        ),
            PaneRole.OUTPUT,
        ), PaneRole.MENU, 400f, true),
        DriftCase(LayoutSnapshot(
mapOf(
            PaneRole.INPUT to PaneState(FloatRect(50f, 60f, 800f, 120f), true),
            PaneRole.MENU to PaneState(FloatRect(50f, 180f, 800f, 44f), true),
            PaneRole.OUTPUT to PaneState(FloatRect(50f, 224f, 800f, 777f), false),
        ),
            PaneRole.OUTPUT,
        ), PaneRole.MENU, 100000f, true),
        DriftCase(LayoutSnapshot(
mapOf(
            PaneRole.INPUT to PaneState(FloatRect(50f, 60f, 800f, 120f), true),
            PaneRole.MENU to PaneState(FloatRect(50f, 180f, 800f, 44f), true),
            PaneRole.OUTPUT to PaneState(FloatRect(50f, 224f, 800f, 777f), false),
        ),
            PaneRole.OUTPUT,
        ), PaneRole.OUTPUT, null, false),
        DriftCase(LayoutSnapshot(
mapOf(
            PaneRole.INPUT to PaneState(FloatRect(50f, 60f, 800f, 120f), true),
            PaneRole.MENU to PaneState(FloatRect(50f, 180f, 800f, 44f), true),
            PaneRole.OUTPUT to PaneState(FloatRect(50f, 224f, 800f, 777f), false),
        ),
            PaneRole.OUTPUT,
        ), PaneRole.OUTPUT, 0f, false),
        DriftCase(LayoutSnapshot(
mapOf(
            PaneRole.INPUT to PaneState(FloatRect(50f, 60f, 800f, 120f), true),
            PaneRole.MENU to PaneState(FloatRect(50f, 180f, 800f, 44f), true),
            PaneRole.OUTPUT to PaneState(FloatRect(50f, 224f, 800f, 777f), false),
        ),
            PaneRole.OUTPUT,
        ), PaneRole.OUTPUT, 400f, false),
        DriftCase(LayoutSnapshot(
mapOf(
            PaneRole.INPUT to PaneState(FloatRect(50f, 60f, 800f, 120f), true),
            PaneRole.MENU to PaneState(FloatRect(50f, 180f, 800f, 44f), true),
            PaneRole.OUTPUT to PaneState(FloatRect(50f, 224f, 800f, 777f), false),
        ),
            PaneRole.OUTPUT,
        ), PaneRole.OUTPUT, 100000f, false),
        DriftCase(LayoutSnapshot(
mapOf(
            PaneRole.INPUT to PaneState(FloatRect(10f, 20f, 400f, 60f), false),
            PaneRole.MENU to PaneState(FloatRect(500f, 900f, 90f, 40f), true),
            PaneRole.OUTPUT to PaneState(FloatRect(0f, 0f, 10f, 10f), true),
        ),
            PaneRole.INPUT,
        ), PaneRole.INPUT, null, false),
        DriftCase(LayoutSnapshot(
mapOf(
            PaneRole.INPUT to PaneState(FloatRect(10f, 20f, 400f, 60f), false),
            PaneRole.MENU to PaneState(FloatRect(500f, 900f, 90f, 40f), true),
            PaneRole.OUTPUT to PaneState(FloatRect(0f, 0f, 10f, 10f), true),
        ),
            PaneRole.INPUT,
        ), PaneRole.INPUT, 0f, false),
        DriftCase(LayoutSnapshot(
mapOf(
            PaneRole.INPUT to PaneState(FloatRect(10f, 20f, 400f, 60f), false),
            PaneRole.MENU to PaneState(FloatRect(500f, 900f, 90f, 40f), true),
            PaneRole.OUTPUT to PaneState(FloatRect(0f, 0f, 10f, 10f), true),
        ),
            PaneRole.INPUT,
        ), PaneRole.INPUT, 400f, false),
        DriftCase(LayoutSnapshot(
mapOf(
            PaneRole.INPUT to PaneState(FloatRect(10f, 20f, 400f, 60f), false),
            PaneRole.MENU to PaneState(FloatRect(500f, 900f, 90f, 40f), true),
            PaneRole.OUTPUT to PaneState(FloatRect(0f, 0f, 10f, 10f), true),
        ),
            PaneRole.INPUT,
        ), PaneRole.INPUT, 100000f, false),
        DriftCase(LayoutSnapshot(
mapOf(
            PaneRole.INPUT to PaneState(FloatRect(10f, 20f, 400f, 60f), false),
            PaneRole.MENU to PaneState(FloatRect(500f, 900f, 90f, 40f), true),
            PaneRole.OUTPUT to PaneState(FloatRect(0f, 0f, 10f, 10f), true),
        ),
            PaneRole.INPUT,
        ), PaneRole.MENU, null, false),
        DriftCase(LayoutSnapshot(
mapOf(
            PaneRole.INPUT to PaneState(FloatRect(10f, 20f, 400f, 60f), false),
            PaneRole.MENU to PaneState(FloatRect(500f, 900f, 90f, 40f), true),
            PaneRole.OUTPUT to PaneState(FloatRect(0f, 0f, 10f, 10f), true),
        ),
            PaneRole.INPUT,
        ), PaneRole.MENU, 0f, false),
        DriftCase(LayoutSnapshot(
mapOf(
            PaneRole.INPUT to PaneState(FloatRect(10f, 20f, 400f, 60f), false),
            PaneRole.MENU to PaneState(FloatRect(500f, 900f, 90f, 40f), true),
            PaneRole.OUTPUT to PaneState(FloatRect(0f, 0f, 10f, 10f), true),
        ),
            PaneRole.INPUT,
        ), PaneRole.MENU, 400f, false),
        DriftCase(LayoutSnapshot(
mapOf(
            PaneRole.INPUT to PaneState(FloatRect(10f, 20f, 400f, 60f), false),
            PaneRole.MENU to PaneState(FloatRect(500f, 900f, 90f, 40f), true),
            PaneRole.OUTPUT to PaneState(FloatRect(0f, 0f, 10f, 10f), true),
        ),
            PaneRole.INPUT,
        ), PaneRole.MENU, 100000f, true),
        DriftCase(LayoutSnapshot(
mapOf(
            PaneRole.INPUT to PaneState(FloatRect(10f, 20f, 400f, 60f), false),
            PaneRole.MENU to PaneState(FloatRect(500f, 900f, 90f, 40f), true),
            PaneRole.OUTPUT to PaneState(FloatRect(0f, 0f, 10f, 10f), true),
        ),
            PaneRole.INPUT,
        ), PaneRole.OUTPUT, null, true),
        DriftCase(LayoutSnapshot(
mapOf(
            PaneRole.INPUT to PaneState(FloatRect(10f, 20f, 400f, 60f), false),
            PaneRole.MENU to PaneState(FloatRect(500f, 900f, 90f, 40f), true),
            PaneRole.OUTPUT to PaneState(FloatRect(0f, 0f, 10f, 10f), true),
        ),
            PaneRole.INPUT,
        ), PaneRole.OUTPUT, 0f, false),
        DriftCase(LayoutSnapshot(
mapOf(
            PaneRole.INPUT to PaneState(FloatRect(10f, 20f, 400f, 60f), false),
            PaneRole.MENU to PaneState(FloatRect(500f, 900f, 90f, 40f), true),
            PaneRole.OUTPUT to PaneState(FloatRect(0f, 0f, 10f, 10f), true),
        ),
            PaneRole.INPUT,
        ), PaneRole.OUTPUT, 400f, true),
        DriftCase(LayoutSnapshot(
mapOf(
            PaneRole.INPUT to PaneState(FloatRect(10f, 20f, 400f, 60f), false),
            PaneRole.MENU to PaneState(FloatRect(500f, 900f, 90f, 40f), true),
            PaneRole.OUTPUT to PaneState(FloatRect(0f, 0f, 10f, 10f), true),
        ),
            PaneRole.INPUT,
        ), PaneRole.OUTPUT, 100000f, true),
    )
    val FLAGS = listOf(
        FlagsCase(LayoutSnapshot(
mapOf(
            PaneRole.INPUT to PaneState(FloatRect(0f, 0f, 800f, 120f), true),
            PaneRole.MENU to PaneState(FloatRect(0f, 120f, 800f, 44f), true),
            PaneRole.OUTPUT to PaneState(FloatRect(0f, 164f, 800f, 300f), true),
        ),
            PaneRole.INPUT,
        ), true, false, LayoutSnapshot(
mapOf(
            PaneRole.INPUT to PaneState(FloatRect(0f, 0f, 800f, 120f), true),
            PaneRole.MENU to PaneState(FloatRect(0f, 120f, 800f, 44f), true),
            PaneRole.OUTPUT to PaneState(FloatRect(0f, 164f, 800f, 300f), false),
        ),
            PaneRole.INPUT,
        ), false, true, LayoutSnapshot(
mapOf(
            PaneRole.INPUT to PaneState(FloatRect(0f, 0f, 800f, 120f), false),
            PaneRole.MENU to PaneState(FloatRect(0f, 120f, 800f, 44f), false),
            PaneRole.OUTPUT to PaneState(FloatRect(0f, 164f, 800f, 300f), false),
        ),
            PaneRole.INPUT,
        ), false, true),
        FlagsCase(LayoutSnapshot(
mapOf(
            PaneRole.INPUT to PaneState(FloatRect(50f, 60f, 800f, 120f), true),
            PaneRole.MENU to PaneState(FloatRect(999f, 999f, 100f, 44f), false),
            PaneRole.OUTPUT to PaneState(FloatRect(0f, 164f, 800f, 300f), true),
        ),
            PaneRole.INPUT,
        ), false, true, LayoutSnapshot(
mapOf(
            PaneRole.INPUT to PaneState(FloatRect(50f, 60f, 800f, 120f), true),
            PaneRole.MENU to PaneState(FloatRect(999f, 999f, 100f, 44f), false),
            PaneRole.OUTPUT to PaneState(FloatRect(0f, 164f, 800f, 300f), false),
        ),
            PaneRole.INPUT,
        ), false, true, LayoutSnapshot(
mapOf(
            PaneRole.INPUT to PaneState(FloatRect(50f, 60f, 800f, 120f), false),
            PaneRole.MENU to PaneState(FloatRect(999f, 999f, 100f, 44f), false),
            PaneRole.OUTPUT to PaneState(FloatRect(0f, 164f, 800f, 300f), false),
        ),
            PaneRole.INPUT,
        ), false, true),
        FlagsCase(LayoutSnapshot(
mapOf(
            PaneRole.INPUT to PaneState(FloatRect(50f, 60f, 800f, 120f), true),
            PaneRole.MENU to PaneState(FloatRect(50f, 180f, 800f, 44f), true),
            PaneRole.OUTPUT to PaneState(FloatRect(50f, 224f, 800f, 777f), false),
        ),
            PaneRole.OUTPUT,
        ), false, true, LayoutSnapshot(
mapOf(
            PaneRole.INPUT to PaneState(FloatRect(50f, 60f, 800f, 120f), true),
            PaneRole.MENU to PaneState(FloatRect(50f, 180f, 800f, 44f), true),
            PaneRole.OUTPUT to PaneState(FloatRect(50f, 224f, 800f, 777f), false),
        ),
            PaneRole.OUTPUT,
        ), false, true, LayoutSnapshot(
mapOf(
            PaneRole.INPUT to PaneState(FloatRect(50f, 60f, 800f, 120f), false),
            PaneRole.MENU to PaneState(FloatRect(50f, 180f, 800f, 44f), false),
            PaneRole.OUTPUT to PaneState(FloatRect(50f, 224f, 800f, 777f), false),
        ),
            PaneRole.OUTPUT,
        ), false, true),
        FlagsCase(LayoutSnapshot(
mapOf(
            PaneRole.INPUT to PaneState(FloatRect(10f, 20f, 400f, 60f), false),
            PaneRole.MENU to PaneState(FloatRect(500f, 900f, 90f, 40f), true),
            PaneRole.OUTPUT to PaneState(FloatRect(0f, 0f, 10f, 10f), true),
        ),
            PaneRole.INPUT,
        ), false, true, LayoutSnapshot(
mapOf(
            PaneRole.INPUT to PaneState(FloatRect(10f, 20f, 400f, 60f), false),
            PaneRole.MENU to PaneState(FloatRect(500f, 900f, 90f, 40f), true),
            PaneRole.OUTPUT to PaneState(FloatRect(0f, 0f, 10f, 10f), false),
        ),
            PaneRole.INPUT,
        ), false, true, LayoutSnapshot(
mapOf(
            PaneRole.INPUT to PaneState(FloatRect(10f, 20f, 400f, 60f), false),
            PaneRole.MENU to PaneState(FloatRect(500f, 900f, 90f, 40f), false),
            PaneRole.OUTPUT to PaneState(FloatRect(0f, 0f, 10f, 10f), false),
        ),
            PaneRole.INPUT,
        ), false, true),
    )
    val DEFAULT_LAYOUT = listOf(
        DefaultLayoutCase(FloatRect(100f, 2000f, 900f, 120f), 44f, 300f, 0f, LayoutSnapshot(
mapOf(
            PaneRole.INPUT to PaneState(FloatRect(100f, 2000f, 900f, 120f), true),
            PaneRole.MENU to PaneState(FloatRect(100f, 2120f, 900f, 44f), true),
            PaneRole.OUTPUT to PaneState(FloatRect(100f, 2164f, 900f, 300f), true),
        ),
            PaneRole.INPUT,
        )),
        DefaultLayoutCase(FloatRect(100f, 2000f, 320f, 80f), 40f, 200f, 0f, LayoutSnapshot(
mapOf(
            PaneRole.INPUT to PaneState(FloatRect(100f, 2000f, 320f, 80f), true),
            PaneRole.MENU to PaneState(FloatRect(100f, 2080f, 320f, 40f), true),
            PaneRole.OUTPUT to PaneState(FloatRect(100f, 2120f, 320f, 200f), true),
        ),
            PaneRole.INPUT,
        )),
        DefaultLayoutCase(FloatRect(100f, 2000f, 1000f, 200f), 60f, 500f, 0f, LayoutSnapshot(
mapOf(
            PaneRole.INPUT to PaneState(FloatRect(100f, 2000f, 1000f, 200f), true),
            PaneRole.MENU to PaneState(FloatRect(100f, 2200f, 1000f, 60f), true),
            PaneRole.OUTPUT to PaneState(FloatRect(100f, 2260f, 1000f, 500f), true),
        ),
            PaneRole.INPUT,
        )),
        DefaultLayoutCase(FloatRect(100f, 2000f, 900f, 120f), 44f, 300f, 12f, LayoutSnapshot(
mapOf(
            PaneRole.INPUT to PaneState(FloatRect(100f, 2000f, 900f, 120f), true),
            PaneRole.MENU to PaneState(FloatRect(100f, 2132f, 900f, 44f), true),
            PaneRole.OUTPUT to PaneState(FloatRect(100f, 2188f, 900f, 300f), true),
        ),
            PaneRole.INPUT,
        )),
        DefaultLayoutCase(FloatRect(100f, 2000f, 320f, 80f), 40f, 200f, 12f, LayoutSnapshot(
mapOf(
            PaneRole.INPUT to PaneState(FloatRect(100f, 2000f, 320f, 80f), true),
            PaneRole.MENU to PaneState(FloatRect(100f, 2092f, 320f, 40f), true),
            PaneRole.OUTPUT to PaneState(FloatRect(100f, 2144f, 320f, 200f), true),
        ),
            PaneRole.INPUT,
        )),
        DefaultLayoutCase(FloatRect(100f, 2000f, 1000f, 200f), 60f, 500f, 12f, LayoutSnapshot(
mapOf(
            PaneRole.INPUT to PaneState(FloatRect(100f, 2000f, 1000f, 200f), true),
            PaneRole.MENU to PaneState(FloatRect(100f, 2212f, 1000f, 60f), true),
            PaneRole.OUTPUT to PaneState(FloatRect(100f, 2284f, 1000f, 500f), true),
        ),
            PaneRole.INPUT,
        )),
    )
}