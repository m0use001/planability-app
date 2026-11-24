package io.github.m0use001.planability.ui.info

/**
 * 「在底部」的**容差**判定（2026-11-20）。
 *
 * 病根：流式期间最后那条气泡一直在长。严格用 `canScrollForward` 判「在底」的话，
 * 每长出几个像素就判成「离底了」，于是：
 *  · 流式跟随的「上一帧在底才准贴底」闸被**一次**误判永久关死——贴底贴了没贴上
 *    （读数比真值差几像素，长出来的尾巴还没来得及贴平）就把 wasAtBottom 打成
 *    false，之后再也不贴，视口被 growing 的气泡甩在后面
 *    （用户报的「点『最新』跳一半卡住，之后不再跟」）。
 *  · 跟随死了之后胶囊常驻、点它也追不上，观感就是「『最新』按钮跳转又失效」。
 *
 * 判据：**最后一个可见 item 的底边**与**视口底边**的差 ≤ 容差即算「在底」。
 * 真贴底时这个差是 -底部内容边距（负数，实测 = -10dp 的 padding）；尾巴长一点
 * 仍在容差内。纯函数，可单测。
 *
 * 注意：容差只能救「跟得上」的场景。流式一帧长出一大截（新段落/工具卡进场）
 * 仍会超出容差——那一段由「胶囊在流式跟随活着时压住」兜住（见 ChatScreen
 * 的 showLatestPill），两层配合才完整。
 */
internal const val BOTTOM_SLACK_DP = 64f

/**
 * @param itemBottom 最后一个可见 item 的底边（视口坐标，Float）
 * @param viewportEnd 视口底边（视口坐标，Float）
 * @param slackPx 容差（像素）
 * @return true = 认为在底部
 */
internal fun isAtBottomWithSlack(
    itemBottom: Float,
    viewportEnd: Float,
    slackPx: Float,
): Boolean = itemBottom <= viewportEnd + slackPx
