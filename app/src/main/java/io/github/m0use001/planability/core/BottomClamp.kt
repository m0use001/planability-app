package io.github.m0use001.planability.core

/**
 * 会话列表「贴底」的**决策**入口（2026-11-20 钳制模型重写）。
 *
 * 历史包袱：本文件的前身 `ScrollFollow` 是一台 16 分支状态机，负责「什么时候发
 * `scrollToItem` 命令」。11-18 / 11-20 三轮大修证明**命令式贴底这个原语本身是病根**——
 * 发出去的命令会把自己的 `isScrollInProgress` 按成 true（自指）、两个命令互相取消、
 * 落没落底要「等一帧再读」，每个补丁都在环上再挂一个判别器，判别器又成为新的误判源。
 *
 * 钳制模型把**执行**换成幂等的 `scrollBy(Float.MAX_VALUE)`（只滚残余像素、钳在末端，
 * 不管 contentPadding 的数学；两个「到底」动作谁先谁后结果一样，互取消不再有后果）。
 * 执行不再需要状态机——「要不要钳」只剩一个纯布尔判定：
 *
 *  · 只在**流式期间**钳（流式 = 内容在长，视口才会被顶出去）；
 *  · 只在**钉着底部**（pinned，即原 autoFollow）时钳——用户在看历史（!pinned）永远别动；
 *  · **手指按在列表上**（dragged）时不钳——方向盘在用户手里；
 *  · **末可见项就是列表末项**才钳——用户翻到别处看时末项不可见，钳了就是把人拽回去；
 *  · **还有残余可滚距离**（canScrollForward）才钳——已经贴平就不用白滚一次。
 *
 * 执行侧（ChatScreen）拿到 true 只做一件事：`scrollBy(Float.MAX_VALUE)`。
 * 纯函数，可穷举单测（32 种输入组合）。
 */
object BottomClamp {

    /** 一帧的输入（全部来自 Compose 的布局快照 / 手势状态）。 */
    data class Input(
        /** 流式进行中（内容正在增长）。 */
        val streaming: Boolean,
        /** 钉在底部（用户希望跟随；即原 autoFollow）。 */
        val pinned: Boolean,
        /** 用户手指正按在列表上拖。 */
        val dragged: Boolean,
    /** 末可见项就是列表末项（视口停在列表尾部）。 */
    val lastVisibleIsLast: Boolean,
    /** 还有向下的残余可滚距离（没贴平）。 */
    val canScrollForward: Boolean,
    /**
     * 2026-11-24 修复「流式跟一会就停」：用户/惯性滚动进行中（拖拽之外的另一半信号）。
     * 手指按着由 [dragged] 覆盖；**惯性 fling 与非程序滚动的滑行**只有这里能看见。
     */
    val userScrolling: Boolean = false,
    /**
     * 2026-11-24 修复「流式跟一会就停」：自最近一次「贴平在底」以来没见过用户滚动
     * （拖拽或 [userScrolling]）。末项被内容增长**整体推出视口**的那一帧
     * （末项是底部那行矮的「生成中」活行，上方气泡一帧长一大截），[lastVisibleIsLast]
     * 会永久翻 false——旧判据在这里死锁，视口冻住直到流式结束猛跳（用户实测）。
     * 死锁与「用户上滑走了」外部不可分，只能靠这段**历史**区分：跟随中从未见过用户
     * 滚动 → 是内容顶出去的 → 照钳；见过用户滚动 → 人在别处 → 不许拽回。
     */
    val cleanFollow: Boolean = false,
)

/** true = 该执行一次钳底（调用方负责 `scrollBy(Float.MAX_VALUE)`）。 */
fun decide(i: Input): Boolean =
    i.streaming && i.pinned && !i.dragged && i.canScrollForward &&
        (i.lastVisibleIsLast || (i.cleanFollow && !i.userScrolling))
}
