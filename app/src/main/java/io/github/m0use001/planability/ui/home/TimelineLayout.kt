package io.github.m0use001.planability.ui.home

/**
 * v59：时间轴的**纵向布局**（2026-11-17）。
 *
 * ## 为什么要有它
 *
 * 旧的时间轴是「24 小时 × 恒定 64dp」= 1536dp 高，一天里大部分是空行。
 * 16:10 平板上这一列有 680dp 宽、800dp 高，空行一格一格排下来，看上去就是
 * 用户说的「大而空」——不是横向拉稀了，纵向还是一整天没人。
 *
 * 新规则（**只作用于宽屏 ≥600dp**，手机口径一字不动）：
 * - **有安排的小时**保持原高度（64dp）——事件块的高度、比例、可读性都不变；
 * - **空闲的小时**压扁到 22dp——一行还认得出是哪个钟点，但不再撑出一大片空白。
 *
 * 关键约束：**位置仍按真实分钟换算**。压扁只改「视觉间距」，不改「几点在哪儿」——
 * 事件块落在哪个钟点、现在线在哪、点击命中哪一小时，都由 [minuteToDp] 统一换算，
 * 布局和命中测试共用同一份映射（v56 之前的「同一事实两处定义」那类病在这里最容易复发）。
 */
class TimelineLayout private constructor(
    /** 每个小时占多高（dp）：有事 64dp、空闲 22dp。 */
    val hourHeightsDp: IntArray,
    /** 每个小时的顶边（dp），长度 25（最后一个是整条轴的底）。 */
    val hourTopsDp: FloatArray
) {
    /** 整条时间轴的高度（dp）。 */
    val totalHeightDp: Float get() = hourTopsDp[24]

    /**
     * 真实分钟 → 轴上的 y（dp）。
     * 有事的小时按 64dp 线性铺开，空闲的小时按 22dp 铺开——
     * 同一个小时内是线性的，所以「事件在钟点里的相对位置」永远正确。
     */
    fun minuteToDp(minute: Int): Float {
        val m = minute.coerceIn(0, 1440)
        if (m >= 1440) return totalHeightDp
        val h = m / 60
        val frac = (m % 60) / 60f
        return hourTopsDp[h] + frac * hourHeightsDp[h]
    }

    /** 某小时的顶边（dp）。 */
    fun hourTopDp(hour: Int): Float = hourTopsDp[hour.coerceIn(0, 24)]

    /** 该小时是否有安排（决定标签字号与网格线强弱）。 */
    fun isContentHour(hour: Int): Boolean = hourHeightsDp[hour.coerceIn(0, 23)] > IDLE_HOUR_HEIGHT_DP

    companion object {
        /** 空闲小时压扁后的高度（dp）。 */
        const val IDLE_HOUR_HEIGHT_DP = 22

        /**
         * 按「哪些小时有事」算纵向布局。
         * @param contentHours 有事件的小时（0..23）
         * @param contentHourHeightDp 有事的小时高度（默认沿用 [HOUR_HEIGHT_DP]）
         * @param idleHourHeightDp 空闲小时高度（[IDLE_HOUR_HEIGHT_DP]）
         * @param fillHeightDp 可视高度（dp）：时间轴比屏幕矮时，把空闲小时**回弹**撑满，
         *   但不超过自然高度（64dp）。传 0 = 不撑（内容多时自然更不撑）。
         */
        fun build(
            contentHours: Set<Int>,
            contentHourHeightDp: Int = HOUR_HEIGHT_DP,
            idleHourHeightDp: Int = IDLE_HOUR_HEIGHT_DP,
            fillHeightDp: Float = 0f
        ): TimelineLayout {
            val heights = IntArray(24)
            val tops = FloatArray(25)
            var y = 0f
            for (h in 0 until 24) {
                val hgt = if (h in contentHours) contentHourHeightDp else idleHourHeightDp
                heights[h] = hgt
                tops[h] = y
                y += hgt
            }
            tops[24] = y
            // v59：比可视高度矮 → 空闲小时回弹（上限 = 自然高度 64dp）。
            // 为什么要有这一步：压缩之后一天只剩 ~570dp，页面下半截会空一大块——
            // 「空」只是从时间轴内部挪到了页面底部。让它**撑满**才算真的不空，
            // 而内容多的时候（总高 > 可视高度）自然不触发，滚动行为不变。
            if (fillHeightDp > y && heights.any { it == idleHourHeightDp }) {
                val idleCount = heights.count { it == idleHourHeightDp }
                val per = ((fillHeightDp - y) / idleCount).toInt()
                if (per > 0) {
                    var y2 = 0f
                    for (h in 0 until 24) {
                        if (heights[h] == idleHourHeightDp) {
                            heights[h] = (heights[h] + per).coerceAtMost(contentHourHeightDp)
                        }
                        tops[h] = y2
                        y2 += heights[h]
                    }
                    tops[24] = y2
                }
            }
            return TimelineLayout(heights, tops)
        }
    }
}

/**
 * 一组事件占用了哪些小时（起点所在小时到终点所在小时，含两端）。
 * 跨整点的事件会把中间的小时也算成「有事」——那个小时本就该占满高度。
 */
internal fun contentHoursOf(blocks: List<TimelineBlock>): Set<Int> {
    val out = HashSet<Int>()
    for (b in blocks) {
        val startH = (b.startMin / 60).coerceIn(0, 23)
        val endH = ((b.endMin - 1).coerceAtLeast(b.startMin) / 60).coerceIn(0, 23)
        for (h in startH..endH) out.add(h)
    }
    return out
}
