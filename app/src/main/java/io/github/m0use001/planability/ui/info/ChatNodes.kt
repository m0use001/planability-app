package io.github.m0use001.planability.ui.info

import io.github.m0use001.planability.ai.compactedIdSet
import io.github.m0use001.planability.data.ChatMessage
import io.github.m0use001.planability.data.CompactMarkerEntity

/**
 * 会话列表的显示节点（2026-10-31「过程链」）。
 *
 * 消息表里，"思考 + 工具调用 + 工具结果" 是**多条独立消息**（assistant 带 toolCallsJson、
 * 若干 role=tool 的回执），但用户眼里它们是**一次过程**。以前三条各渲染各的块
 * （思考块 / 工具块 / 结果行），于是同一个动作出现三个「详情」入口、三套折叠状态。
 *
 * 这里在数据层先把它们合成一个 [Chain]，列表只渲染一个容器：
 * 折叠时一行摘要（多久思考、几个工具、几条结果），展开时按发生顺序铺开。
 * 纯函数，便于单测覆盖边界（绝不吞正文、绝不跨 user 消息合并）。
 */
internal sealed class ChatNode(open val key: Any) {
    /** 单条消息（user / system / 正文型 assistant） */
    data class Single(val msg: ChatMessage) : ChatNode(msg.id)

    /** 一次过程：assistant(思考+工具调用) + 其后连续的 tool 回执 */
    data class Chain(val msgs: List<ChatMessage>) : ChatNode("chain_" + msgs.first().id)

    /**
     * v47：被手动压缩的区间——渲染为摘要卡（原文不显示，长按可切原文/摘要）。
     * 出现在区间最后一条消息的位置（时间序）；markers 为空时永不产生。
     */
    data class Compact(val marker: CompactMarkerEntity) : ChatNode("compact_" + marker.id)
}

/**
 * 把消息序列切成显示节点。
 *
 * 规则（刻意保守）：
 * 1. 只有「assistant 带 toolCallsJson 且正文为空」或「role=tool」才开启过程链；
 *    带正文的 assistant 永远单独成节点——正文是回答本身，合并会把它藏进折叠区。
 * 2. 链只吃连续的过程消息；遇到 user / system / 正文型 assistant 立即截断。
 * 3. 单条孤立的过程消息（例如只有工具调用没有回执、或孤立的 tool 行）仍单独渲染，
 *    保持与旧行为一致，不制造"空链"。
 */
internal fun buildChatNodes(messages: List<ChatMessage>): List<ChatNode> =
    buildChatNodes(messages, emptyList())

/** v47：带压缩标记的版本——被区间覆盖的消息不进节点，区间终点处放一颗摘要卡节点。 */
internal fun buildChatNodes(messages: List<ChatMessage>, markers: List<CompactMarkerEntity>): List<ChatNode> {
    val rangeMarkers = markers.filter { it.firstCompactedMessageId > 0 }
    if (rangeMarkers.isEmpty()) return buildChatNodesPlain(messages)
    val covered = compactedIdSet(rangeMarkers)
    val markerByLast = rangeMarkers.associateBy { it.lastCompactedMessageId }
    val visible = ArrayList<ChatMessage>(messages.size)
    val out = ArrayList<ChatNode>(messages.size)
    for (m in messages) {
        if (m.id !in covered) {
            visible += m
            continue
        }
        // 区间内：渲染时以区间终点处的摘要卡代表（时间序只挂一次）
        markerByLast[m.id]?.let { out += ChatNode.Compact(it) }
    }
    // visible 里是原文照排，但 Compact 卡的**位置**要在正确的时间序上——
    // 上面把 Compact 插在了 out 的区间终点处，而 visible 的节点还没放。
    // 简化处理：先铺 visible 的常规节点，再把 Compact 按其区间最后一条消息在 visible
    // 中的前驱位置插回去（按消息 id 大小比较即可，id 单调递增）。
    val plain = buildChatNodesPlain(visible)
    if (out.isEmpty()) return plain
    // 每颗 Compact 卡的插入位置：plain 中「id 大于 marker.lastCompactedMessageId 的第一个节点」之前
    val merged = ArrayList<ChatNode>(plain.size + out.size)
    var mi = 0
    val sorted = out.sortedBy { (it as ChatNode.Compact).marker.lastCompactedMessageId }
    fun firstMsgId(n: ChatNode): Long = when (n) {
        is ChatNode.Single -> n.msg.id
        is ChatNode.Chain -> n.msgs.first().id
        is ChatNode.Compact -> n.marker.lastCompactedMessageId + 1
    }
    for (node in plain) {
        while (mi < sorted.size && (sorted[mi] as ChatNode.Compact).marker.lastCompactedMessageId < firstMsgId(node)) {
            merged += sorted[mi]; mi++
        }
        merged += node
    }
    while (mi < sorted.size) { merged += sorted[mi]; mi++ }
    return merged
}

private fun buildChatNodesPlain(messages: List<ChatMessage>): List<ChatNode> {
    val out = ArrayList<ChatNode>(messages.size)
    var i = 0
    while (i < messages.size) {
        val m = messages[i]
        if (!isProcessMessage(m)) {
            out += ChatNode.Single(m)
            i++
            continue
        }
        var j = i
        while (j < messages.size && isProcessMessage(messages[j])) j++
        val run = messages.subList(i, j)
        // 有 tool 回执才算"一次过程"；孤立的一条就按单条渲染
        out += if (run.size > 1) ChatNode.Chain(run.toList()) else ChatNode.Single(run[0])
        i = j
    }
    return out
}

/** 属于"过程"的消息：工具回执，或「带工具调用且正文为空」的 assistant */
internal fun isProcessMessage(m: ChatMessage): Boolean = when (m.role) {
    "tool" -> true
    "assistant" -> m.toolCallsJson.isNotBlank() && m.content.isBlank()
    else -> false
}
