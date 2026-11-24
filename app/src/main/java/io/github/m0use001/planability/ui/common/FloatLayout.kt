package io.github.m0use001.planability.ui.common

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 悬浮窗三段布局的**纯几何引擎**。
 *
 * 与 PC 端 `desktop/src/main/float/layout.ts`（提交 `459e05b`，F1）**逐值对齐**：
 * 同名常量、同样的语义、同样的边界处理。两端没有共享代码，靠这份对齐 + 双份单测保持一致，
 * 所以改任何一端都必须改另一端，改完跑两边单测。这是有意的（AGENTS.md 第 0 节）。
 *
 * **为什么单独一个文件、且不含任何 Android 依赖**：吸附系统里所有有意思的 bug 都是算术错误，
 * 而算术错误在用户把窗拖到第二个显示器之前都不会显形。这个文件只进出矩形，于是整台引擎
 * 可以在无屏环境单测。手机端同理 —— 悬浮窗交互归 A 线接线，几何归这里。
 *
 * **下面标「铁律」的六条是被坑出来的，不是设计洁癖，改动前先读完**：
 * 1. 单锚点、单方向。menu 和 output 只跟随 anchor，绝不互相跟随。三窗互相重定位会振荡，
 *    用户看到的是整组抖动。
 * 2. detached 是**存储标志**，不是推断出来的。早期设计「拖远了就脱组」，用户没法把整组找回来，
 *    除非知道一个魔法手势。改成显式、可逆的 link 开关（Q3=A）。
 * 3. 钳制用 **anchor 所在**显示屏，不是主屏。
 * 4. 默认 stack：gap=0（用户要的「默认上下相连」），宽度一致。gap 是用户偏好，不是常量。
 * 5. 吸附**只认最近的边**。否则小窗贴近大窗会同时匹配 top 和 left，结果取决于迭代顺序。
 * 6. 边名指 **target 的哪一侧**，不是移动窗的那一侧。「left」= 贴到 target 的左边缘。
 *    第一版按移动窗命名，读起来正好反过来 —— 这就是那种「系统没坏但感觉坏了」的歧义。
 */

private const val KEEP = 80f

/** 三段窗。`MENU` 即用户所说的「截屏选项 / 截屏按钮」那一段。 */
enum class PaneRole { INPUT, MENU, OUTPUT }

/** 栈序 = 铁律 4：上下相连的默认顺序，input 在最上。 */
val PANE_ORDER: List<PaneRole> = listOf(PaneRole.INPUT, PaneRole.MENU, PaneRole.OUTPUT)

data class FloatRect(
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
) {
    fun isFinite(): Boolean =
        !x.isNaN() && !x.isInfinite() &&
            !y.isNaN() && !y.isInfinite() &&
            !width.isNaN() && !width.isInfinite() &&
            !height.isNaN() && !height.isInfinite()

    val right: Float get() = x + width
    val bottom: Float get() = y + height
}

/**
 * 契约常量。单位 dp（DIP 与 dp 一一对应，数值可跨端直接移植）。
 *
 * [gap] 0 = 相连（边缘相接），这正是「默认上下相连」。
 * [snapThreshold] 边要靠多近才被吸过去。12dp 是刻意的：再大就发黏、和用户抢手，
 * 再小几乎不触发 —— 窗会吸到整数位置，指针落在两个整数之间。
 * [maxDrift] linked 的 pane 允许偏多远仍算组内。刻意宽松：连接要能扛住随手一拖，而不是惩罚它。
 */
object FloatLayoutDefaults {
    const val GAP = 0f
    const val SNAP_THRESHOLD = 12f
    const val MAX_DRIFT = 400f
}

data class FloatLayoutConfig(
    val gap: Float = FloatLayoutDefaults.GAP,
    val snapThreshold: Float = FloatLayoutDefaults.SNAP_THRESHOLD,
    val maxDrift: Float = FloatLayoutDefaults.MAX_DRIFT,
)

/** 一个 pane 的位置 + 是否还在组内。 */
data class PaneState(val rect: FloatRect, val linked: Boolean)

/** 整组的瞬时状态。[anchor] 是整组跟随的那一个，只有它能带动别人。 */
data class LayoutSnapshot(val panes: Map<PaneRole, PaneState>, val anchor: PaneRole)

enum class SnapEdge { LEFT, RIGHT, TOP, BOTTOM }

data class SnapResult(val x: Float, val y: Float, val edge: SnapEdge)

/**
 * 一块屏。与 PC 端 `DisplayLike` 同形：`{ id, bounds, scaleFactor? }`。
 *
 * 手机端目前只有一块屏，但**形状刻意不简化**：`id` 对应 `Display.getDisplayId()`，
 * `scaleFactor` 对应本机 density 3.5x。形状一致才叫契约一致，否则将来补多窗口
 * 就会变成「两处形状不同」的第二笔账要还。
 */
data class FloatDisplay(
    val id: Int,
    val bounds: FloatRect,
    val scaleFactor: Float = 1f,
)

/**
 * 铁律 3：把窗留在**它自己所在的那块屏**上。窗不能被摆到屏幕外，否则用户就再也拖不回来。
 *
 * 手机端当前只有一块屏，`displays` 通常长度为 1；但保留列表形参是为了
 * 「最近重叠最大者」的判定逻辑与 PC 端一致，且留了分屏/多窗口的口子。
 *
 * @param displays 每项的 [FloatDisplay.bounds] 应是**已扣掉状态栏/导航栏/刘海**的可用区。
 *
 * 调用顺序见 [relayout] 的说明：**先 relayout 再逐窗 clamp，之后不要再 relayout**，
 * 否则钳制会被推导覆盖掉。
 */
fun clampToDisplay(rect: FloatRect, displays: List<FloatDisplay>): FloatRect {
    if (!rect.isFinite() || displays.isEmpty()) return rect
    // getDisplayMatching 语义：与 rect 重叠面积最大的那块屏。
    var best = displays[0].bounds
    var bestArea = -1f
    for (d in displays) {
        val b = d.bounds
        val ox = max(0f, min(rect.right, b.right) - max(rect.x, b.x))
        val oy = max(0f, min(rect.bottom, b.bottom) - max(rect.y, b.y))
        val area = ox * oy
        if (area > bestArea) {
            bestArea = area
            best = b
        }
    }
    // 每个轴：窗**能整个放进**这块屏就整个放进去 —— 那才是用户说「我拖到边上去了」的意思。
    // 只有放不下时才退回「至少留 KEEP px 可见」。第一版无条件套 KEEP，结果 44px 的 menu 窗
    // 被顶到离底边 80px、120px 的 input 窗反而压住它 —— 钳制亲手制造了它本该防的重叠。
    val keepX = min(KEEP, rect.width)
    val keepY = min(KEEP, rect.height)
    // 只在「将会留下的可见量不足」时才动窗。目标不是「完全包含」，是「够得着」：
    // 挂出去 50px 的窗，用户想放哪就放哪，不动它。
    val visX = min(rect.right, best.right) - max(rect.x, best.x)
    val visY = min(rect.bottom, best.bottom) - max(rect.y, best.y)
    val x = if (visX >= keepX) rect.x else min(max(rect.x, best.x), best.right - keepX).roundHalfUp()
    val y = if (visY >= keepY) rect.y else min(max(rect.y, best.y), best.bottom - keepY).roundHalfUp()
    return rect.copy(x = x, y = y)
}

/**
 * 给定 anchor 顶边，第 [index] 段窗（高度取 [heights]）的顶边该在哪。
 * 纯函数：整个引擎就是这一个表达式。
 */
fun stackY(anchorY: Float, heights: Map<PaneRole, Float>, index: Int, gap: Float): Float {
    var y = anchorY
    for (i in 0 until index) {
        y += (heights[PANE_ORDER[i]] ?: 0f) + gap
    }
    return y
}

/**
 * 从 anchor 重算**每一个 linked 的** pane。detached 的原样返回 —— 这就是那个标志的全部意义。
 *
 * ## ⚠ 与 [clampToDisplay] 的组合顺序：先 relayout，再逐窗 clamp，**然后不要再 relayout**
 *
 * 「clamp 之后再 relayout」会**悄悄撤销 clamp**：linked 段是从 anchor 推导出来的，
 * 推导直接覆盖掉钳制结果，输出框又被推出屏外。而「先 clamp 再 relayout」恰恰是最自然的
 * 写法，所以 PC 侧把它列为 F1 抓到的第三个真 bug，并留了显式 regression guard
 * （手机端见 `FloatLayoutTest` 的「错误顺序」「正确顺序」两条）。
 *
 * 正确顺序的代价也要知道：逐窗钳制只保证「每段都够得着」，**不保证不重叠、不保证保持栈序**
 * —— 整组挂在屏幕下沿时这三者互相打架。不要为此偷偷改成「整组一起移」，
 * 那会引入 PC 侧不存在的规则，造成新的跨端形状分叉。
 */
fun relayout(snap: LayoutSnapshot, cfg: FloatLayoutConfig = FloatLayoutConfig()): LayoutSnapshot {
    val anchorPane = snap.panes[snap.anchor] ?: return snap
    // 高度取自**重算前**的快照，与 PC 端同序：跟随者只改 x/y/width，自己的 height 保持不变。
    val heights = PANE_ORDER.associateWith { snap.panes[it]?.rect?.height ?: 0f }
    val out = HashMap<PaneRole, PaneState>(PANE_ORDER.size)
    PANE_ORDER.forEachIndexed { i, role ->
        val pane = snap.panes[role]
        if (pane == null) return@forEachIndexed
        if (!pane.linked || role == snap.anchor) {
            out[role] = pane
            return@forEachIndexed
        }
        out[role] = PaneState(
            rect = FloatRect(
                x = anchorPane.rect.x,
                y = stackY(anchorPane.rect.y, heights, i, cfg.gap),
                width = anchorPane.rect.width,
                height = pane.rect.height,
            ),
            linked = true,
        )
    }
    return LayoutSnapshot(out, snap.anchor)
}

/**
 * 把 [rect] 放这儿会不会吸到 [target] 的某条边？返回调整后的 x/y，或 null（不吸）。
 *
 * 邻近度按**两条边之间的空隙**算，且**只有最近的边胜出**（铁律 5）。
 * 吸竖边要求两窗纵向区间确实重叠，吸横边要求横向重叠 —— 否则一个高的 input 窗
 * 会隔着屏幕横着吸到矮 output 窗上。
 */
fun snapRect(
    rect: FloatRect,
    target: FloatRect,
    threshold: Float = FloatLayoutDefaults.SNAP_THRESHOLD,
): SnapResult? {
    val gapX = listOf(
        // 移动窗左边 ≈ target 右边 -> 贴到 target 的右边
        SnapEdge.RIGHT to abs(rect.x - target.right),
        // 移动窗右边 ≈ target 左边 -> 贴到 target 的左边
        SnapEdge.LEFT to abs(rect.right - target.x),
    )
    val gapY = listOf(
        SnapEdge.BOTTOM to abs(rect.y - target.bottom),
        SnapEdge.TOP to abs(rect.bottom - target.y),
    )
    val overlapsY = rect.y < target.bottom && target.y < rect.bottom
    val overlapsX = rect.x < target.right && target.x < rect.right
    val cands = (if (overlapsY) gapX else emptyList()) + (if (overlapsX) gapY else emptyList())
    val near = cands.filter { it.second <= threshold }
    if (near.isEmpty()) return null
    // 稳定排序：同距时保持声明顺序（right, left, bottom, top），与 PC 端 sort 一致。
    val win = near.sortedBy { it.second }.first()
    return when (win.first) {
        SnapEdge.RIGHT -> SnapResult(target.right, rect.y, win.first)
        SnapEdge.LEFT -> SnapResult(target.x - rect.width, rect.y, win.first)
        SnapEdge.BOTTOM -> SnapResult(rect.x, target.bottom, win.first)
        SnapEdge.TOP -> SnapResult(rect.x, target.y - rect.height, win.first)
    }
}

/**
 * 移动一个 pane。三条路径，顺序有意义：
 * - 它是 anchor -> 整组跟着走（[relayout]）；
 * - 否则它已 detached -> **只动它自己，不重排整组**；
 * - 否则 linked -> 立刻重新堆叠 —— 这就是「组感觉硬」而不是「软塌塌」的原因。
 *
 * 第二条容易写错成「统一 relayout」，那样拖一个已拆开的段会把仍相连的段一起拽走。
 * 注意 [relayout] 在 anchor 位于 `(0,0)` 时是**幂等**的，所以这种错误在原点布局的
 * 测试里完全看不出来 —— 必须让 anchor 偏离原点才暴露。跨端向量回放就是靠这种用例抓到的。
 */
fun movePane(
    snap: LayoutSnapshot,
    role: PaneRole,
    next: FloatRect,
    cfg: FloatLayoutConfig = FloatLayoutConfig(),
): LayoutSnapshot {
    val pane = snap.panes[role] ?: return snap
    val moved = LayoutSnapshot(snap.panes.toMutableMap().apply { this[role] = pane.copy(rect = next) }, snap.anchor)
    if (role == snap.anchor) return relayout(moved, cfg)
    if (!pane.linked) return moved
    return relayout(moved, cfg)
}

/** linked 的 pane 是否还离组够近（松手时用）。 */
fun isStillLinked(
    snap: LayoutSnapshot,
    role: PaneRole,
    cfg: FloatLayoutConfig = FloatLayoutConfig(),
): Boolean {
    val pane = snap.panes[role] ?: return false
    if (!pane.linked) return false
    val a = snap.panes[snap.anchor]?.rect ?: return false
    val b = pane.rect
    return abs(a.x - b.x) <= cfg.maxDrift && abs(a.y - b.y) <= cfg.maxDrift
}

/** 切换某一段是否在组内。显式且可逆 —— Q3=A 的全部要点。 */
fun setLinked(snap: LayoutSnapshot, role: PaneRole, linked: Boolean): LayoutSnapshot =
    snap.panes[role]?.let {
        LayoutSnapshot(snap.panes.toMutableMap().apply { this[role] = it.copy(linked = linked) }, snap.anchor)
    } ?: snap

/** 一键全连 / 全断（UI 上的链条按钮）。 */
fun setAllLinked(snap: LayoutSnapshot, linked: Boolean): LayoutSnapshot {
    val panes = HashMap<PaneRole, PaneState>(PANE_ORDER.size)
    PANE_ORDER.forEach { panes[it] = snap.panes[it]?.copy(linked = linked) ?: return@forEach }
    return LayoutSnapshot(panes, snap.anchor)
}

/** 全都在组内（UI 据此把开关画成「开」）。 */
fun allLinked(snap: LayoutSnapshot): Boolean = PANE_ORDER.all { snap.panes[it]?.linked == true }

/** 存在已拆开的段（UI 据此提示「可以吸附回去」）。 */
fun anyDetached(snap: LayoutSnapshot): Boolean = PANE_ORDER.any { snap.panes[it]?.linked == false }

/** 持久化形态。手机端落盘走 `data/`（A 线），这里只定义形状。 */
data class StoredPane(val rect: FloatRect, val linked: Boolean)

data class StoredLayout(val panes: Map<PaneRole, StoredPane>, val anchor: PaneRole, val gap: Float? = null)

fun saveLayout(snap: LayoutSnapshot, gap: Float? = null): StoredLayout {
    val panes = HashMap<PaneRole, StoredPane>(PANE_ORDER.size)
    PANE_ORDER.forEach { role -> snap.panes[role]?.let { panes[role] = StoredPane(it.rect, it.linked) } }
    return StoredLayout(panes, snap.anchor, gap)
}

/**
 * 还原持久化的布局。**逐字段校验**：这份数据来自磁盘，可能由更老的版本写入，
 * 这里一个 NaN 就能让整组拖不动，且毫无线索。
 *
 * [stored] 允许是任意形状（调用方从 JSON / Bundle 解析出来的原始 Map 也行），
 * 任何不认识的字段一律回落到 [fallback]。
 */
fun loadLayout(stored: Any?, fallback: LayoutSnapshot): LayoutSnapshot {
    val s = stored as? Map<*, *> ?: return fallback
    val rawPanes = s["panes"] as? Map<*, *> ?: return fallback
    val panes = HashMap<PaneRole, PaneState>(PANE_ORDER.size)
    PANE_ORDER.forEach { role ->
        val p = rawPanes[role.name] as? Map<*, *>
        val r = FloatRect(
            x = (p?.get("x") as? Number)?.toFloat() ?: Float.NaN,
            y = (p?.get("y") as? Number)?.toFloat() ?: Float.NaN,
            width = (p?.get("width") as? Number)?.toFloat() ?: Float.NaN,
            height = (p?.get("height") as? Number)?.toFloat() ?: Float.NaN,
        )
        val fb = fallback.panes[role]
        panes[role] = if (r.isFinite()) PaneState(r, p?.get("linked") == true) else fb ?: return@forEach
    }
    val anchorName = s["anchor"] as? String
    val anchor = PANE_ORDER.firstOrNull { it.name == anchorName } ?: fallback.anchor
    return LayoutSnapshot(panes, anchor)
}

/** 由一个矩形生成默认的「上下相连」整组。 */
fun defaultLayout(
    input: FloatRect,
    menuH: Float,
    outputH: Float,
    cfg: FloatLayoutConfig = FloatLayoutConfig(),
): LayoutSnapshot = relayout(
    LayoutSnapshot(
        anchor = PaneRole.INPUT,
        panes = mapOf(
            PaneRole.INPUT to PaneState(input, linked = true),
            PaneRole.MENU to PaneState(
                FloatRect(input.x, input.y + input.height + cfg.gap, input.width, menuH), linked = true,
            ),
            PaneRole.OUTPUT to PaneState(
                FloatRect(input.x, input.y + input.height + cfg.gap + menuH + cfg.gap, input.width, outputH),
                linked = true,
            ),
        ),
    ),
    cfg,
)

/**
 * Math.round 语义（.5 往正方向取整），显式写出来以免被 Kotlin 的 `roundToInt`
 * 在负数上的差异悄悄改变跨端行为。
 */
private fun Float.roundHalfUp(): Float = kotlin.math.floor(this + 0.5f).toFloat()