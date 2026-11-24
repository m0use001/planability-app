package io.github.m0use001.planability.ui.info

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.m0use001.planability.data.Graph
import io.github.m0use001.planability.ui.common.Motion
import io.github.m0use001.planability.ui.common.pressScale
import kotlinx.coroutines.launch

/**
 * 「自动书签」侧栏（2026-11-02 用户立项，参考 minimap 形态）：
 * 长会话找某个回复不用上下狂滚——**每条用户消息自动成为一个书签**，
 * 点条目直接跳到那条对话。
 *
 * 2026-11-02 二轮（用户实机反馈三条）：
 * ①入口从右缘把手**移进标题栏**（智能体胶囊右边，见 ChatScreen 的 subtitleContent），
 *   收起态不再占消息区；②当前阅读位置的条目高亮改**胶囊**底；
 * ③跳转目标要落在**标题栏下方**（视口顶）而不是输入栏上方——reverseLayout 列表的
 *   scrollToItem 只会把目标锚到视口底，[jumpChatBookmark] 两步法先锚底再按 layoutInfo
 *   差值反向滚，把目标顶边精确顶到视口顶（受列表头 clamp：跳最新长消息时到不了顶）。
 *
 * - 摘要 = 消息首行截 18 字；徽标 = 第 N 条用户消息 / 共 M 条（用户拍板默认）
 * - 展开状态记 settings KV（全局），状态本体由调用方 remember（[rememberChatBookmarksExpanded]），
 *   顶栏按钮与本面板共享同一 state
 */

/** 一个书签：正序显示节点序列里的一条用户消息 */
internal data class ChatBookmark(
    val nodeIndex: Int,   // 在 buildChatNodes 产物（正序）中的下标
    val label: String,    // 摘要
    val ordinal: Int,     // 第几个用户消息（1-based）
    val total: Int,       // 用户消息总数
    /** 2026-11-15 用户：被压缩的那一条在书签里换**淡紫色底**（与详细编辑屏同一套紫） */
    val compacted: Boolean = false
)

/** 从显示节点抽取书签（每条用户消息一个，正序编号）；v47：压缩摘要卡也出一个书签（自动书签跟着变） */
internal fun buildChatBookmarks(nodes: List<ChatNode>): List<ChatBookmark> {
    val total = nodes.count {
        (it is ChatNode.Single && it.msg.role == "user") || it is ChatNode.Compact
    }
    var n = 0
    return nodes.mapIndexedNotNull { idx, node ->
        when {
            node is ChatNode.Single && node.msg.role == "user" -> {
                n++
                ChatBookmark(idx, chatBookmarkSummary(node.msg.content), n, total)
            }
            // v47：被压缩的轮在这里只显示摘要（原文气泡不在节点序列里）
            // 2026-11-15 用户：文字上只留「[已压缩]」+ 压缩概要——摘要自带的时间/编号块头
            // （`[11/15 #7262-7285] `）要剥掉，否则一句里两截都像元数据，用户看不出重点。
            node is ChatNode.Compact -> {
                n++
                ChatBookmark(
                    idx,
                    "[已压缩] " + chatBookmarkSummary(stripSummaryHeader(node.marker.summary)),
                    n, total, compacted = true
                )
            }
            else -> null
        }
    }
}

/** 压缩摘要自带的块头 `[11/15 #7262-7285] `——书签里不显示，只留正文概要 */
internal fun stripSummaryHeader(summary: String): String =
    summary.replaceFirst(Regex("""^\s*\[[^\]]*]\s*"""), "").trim()

/** 摘要：首行截 40 字（约 2 行容量上限，超出交给 Text 的 maxLines=2 省略）；
 *  空消息有占位，不产生空条目 */
internal fun chatBookmarkSummary(content: String): String {
    val line = content.trim().lineSequence().firstOrNull().orEmpty().trim()
    if (line.isEmpty()) return "（无文字内容）"
    return if (line.length <= 40) line else line.take(40) + "…"
}

/**
 * 书签的正序节点下标 → LazyColumn item 下标。
 *
 * 2026-11-05 八修：列表改成正序排列（`items(nodes)`，不再 asReversed+reverseLayout），
 * 两者现在是**恒等映射**——保留这个函数是为了让调用点语义不变（以后再加前置 item
 * 时只需改这里一处）。
 */
internal fun chatLazyIndex(nodeIndex: Int, nodeCount: Int): Int = nodeIndex

/**
 * 当前阅读位置对应的书签（无书签在可视范围内时退最近一条）。
 *
 * 2026-11-05 八修正序化：firstVisibleItemIndex 是**视口顶部**那一项，越往下翻越大。
 * 取「视口顶及之前（更旧方向）的最后一条书签」——用户读到哪、就亮哪条提问；
 * 一条都还没过顶（停在最上方）时退第一条。
 */
internal fun currentChatBookmark(
    bookmarks: List<ChatBookmark>,
    firstVisibleItemIndex: Int,
    nodeCount: Int,
    /** 节点序号 → 列表条目序号（2026-11-21 超长消息切条后不再一一对应），缺省恒等。 */
    itemIndexOfNode: (Int) -> Int = { chatLazyIndex(it, nodeCount) },
): ChatBookmark? {
    if (bookmarks.isEmpty()) return null
    val before = bookmarks.filter { itemIndexOfNode(chatLazyIndex(it.nodeIndex, nodeCount)) <= firstVisibleItemIndex }
    return before.maxByOrNull { itemIndexOfNode(chatLazyIndex(it.nodeIndex, nodeCount)) } ?: bookmarks.first()
}

private const val KEY_EXPANDED = "chat.bookmarks.expanded"

// v50.2 的「会话级读到哪」记忆（KEY_POS_PREFIX + save/loadChatReadingPos）已随
// 2026-11-08 用户「每次进入 AI 会话从最新开始」撤除——恢复逻辑与它成对删除，
// 进会话一律贴底；手动跳转仍走书签面板。

/** 展开状态（顶栏按钮与面板共用）：remember 一次于会话页层，落 settings KV */
@Composable
internal fun rememberChatBookmarksExpanded(): androidx.compose.runtime.MutableState<Boolean> {
    val inner = remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        inner.value = runCatching { Graph.settings.kvGet(KEY_EXPANDED) == "1" }.getOrDefault(false)
    }
    return remember {
        object : androidx.compose.runtime.MutableState<Boolean> {
            override var value: Boolean
                get() = inner.value
                set(v) {
                    inner.value = v
                    Graph.appScope.launch {
                        runCatching { Graph.settings.kvPut(KEY_EXPANDED, if (v) "1" else "0") }
                    }
                }
            override fun component1(): Boolean = inner.value
            override fun component2(): (Boolean) -> Unit = { value = it }
        }
    }
}

/**
 * 跳转并把目标**顶到视口顶（标题栏下方）**。
 *
 * 2026-11-05 八修正序化后简化：正序布局的 `scrollToItem(index, 0)` 天然把该项
 * **顶边贴到视口顶**，不再需要「先锚底再按差值反向 scrollBy」的两步法
 * （那是 reverseLayout 独有的补偿）。仍保留 ScrollHapticSuppress——
 * 程序滚动撞到列表端点会触发「滚到尽头」触感，用户摸到莫名一震。
 * v50.2 起供会话重进的阅读位置恢复共用（ChatScreen），故 internal。
 */
internal suspend fun jumpChatBookmark(listState: LazyListState, index: Int) {
    io.github.m0use001.planability.core.ScrollHapticSuppress.suppress(1200L)
    runCatching { listState.scrollToItem(index, 0) }
}

@Composable
internal fun ChatBookmarksPanel(
    bookmarks: List<ChatBookmark>,
    listState: LazyListState,
    nodeCount: Int,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    /**
     * 面板自己的列表状态，由调用方 remember 并抬到面板之外（2026-11-11 用户「自动书签
     * 要加记忆，不然每次都是从顶部 1 开始」）：AnimatedVisibility 收起会卸载内容，
     * 面板内 remember 的状态每收一次就丢一次；抬出去之后开合之间滚动位置得以保留。
     */
    panelListState: LazyListState = androidx.compose.foundation.lazy.rememberLazyListState(),
    /** 节点序号 → 列表条目序号（2026-11-21 超长消息切条后不再一一对应），缺省恒等。 */
    itemIndexOfNode: (Int) -> Int = { chatLazyIndex(it, nodeCount) },
) {
    if (bookmarks.size < 3) return   // 短会话没滚动的痛，不占视觉
    val scope = rememberCoroutineScope()
    // 当前阅读位置的书签（滚动时随 firstVisibleItemIndex 联动）
    val current by derivedStateOf {
        currentChatBookmark(bookmarks, listState.firstVisibleItemIndex, nodeCount, itemIndexOfNode)
    }
    // 展开时直接落到**当前读到的书签**：面板从顶部 1 开始等于每次都要重新找位置
    // 2026-11-17 用户「自动书签列表也常常中途卡住」：这个 effect 挂的是 `current?.ordinal`，
    // 而 current 跟着**主列表**每次滚动就变——用户正在用手指滚面板时，主列表一有滚动
    // （含惯性、流式贴底）就把面板拽回当前条，观感就是"滑到一半被卡住"。
    // 2026-11-19 复查：光判 `panelListState.isScrollInProgress` 不够——**用户手指一停**，
    // 主列表下一次滚动（流式贴底/惯性尾巴）又会把面板拽走，看着就是"滑着滑着被弹回去"。
    // 改成：**只在展开的那一刻落位一次**（边沿触发），之后面板归用户，不再追主列表。
    var landedOnOpen by remember { mutableStateOf(false) }
    LaunchedEffect(expanded) {
        if (!expanded) {
            landedOnOpen = false
            return@LaunchedEffect
        }
        if (landedOnOpen) return@LaunchedEffect
        // 用户此刻正在滚面板（面板状态被保留着）→ 不去拽它
        if (panelListState.isScrollInProgress) return@LaunchedEffect
        val target = current ?: return@LaunchedEffect
        landedOnOpen = true
        // items(bookmarks, key=ordinal) 的 item 下标 = ordinal-1（ordinal 从 1 起）
        runCatching { panelListState.scrollToItem((target.ordinal - 1).coerceAtLeast(0)) }
    }

    AnimatedVisibility(
        visible = expanded,
        // v57 批 4（A 类 + 违规收口）：原来 140/180/120/160 四处裸 tween 字面量——
        // 位移走 standardSpatial（弹簧轻过冲）、透明度走 quickEffects（不过冲，官方口径）。
        // 「进比出慢」的原有节奏保留：进入 standard 档 ≈180ms，退出 quick ≈120ms。
        enter = fadeIn(Motion.quickEffects()) + slideInHorizontally(Motion.standardSpatial()) { it / 4 },
        exit = fadeOut(Motion.quickEffects()) + slideOutHorizontally(Motion.quickSpatial()) { it / 4 },
        modifier = modifier
    ) {
        Surface(
            shape = RoundedCornerShape(topStart = 16.dp, bottomStart = 16.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.94f),
            border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant),
            shadowElevation = 3.dp,
            // 2026-11-11 用户「拉宽一点」：228→272dp，40 字摘要少折一行
            modifier = Modifier.width(272.dp).heightIn(max = 340.dp)
        ) {
            LazyColumn(
                state = panelListState,
                modifier = Modifier.padding(vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)   // 条目间自适应留缝（1/2 行都透气）
            ) {
                items(bookmarks, key = { it.ordinal }) { bm ->
                    val active = current?.ordinal == bm.ordinal
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            // v57 批 4（C 类）：条目按压 squish——挂在 clip 之前（摆放铁律，缩得到底块）
                            .pressScale()
                            // 2026-11-02 三轮（用户）：选中背景改回**圆角矩形**并横向对齐面板
                            // 外框（fillMaxWidth——此前 wrap 只包住文字，选中底色右侧参差）
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(
                                when {
                                    active -> MaterialTheme.colorScheme.surfaceContainerHighest
                                    // 2026-11-15 用户：压缩条目淡紫底（与详细编辑屏的紫同一色系）
                                    bm.compacted -> MaterialTheme.colorScheme.tertiary.copy(alpha = 0.16f)
                                    else -> Color.Transparent
                                }
                            )
                            .clickable {
                                // 跳过去就收起：读完直接读内容，列表不再挡字
                                onExpandedChange(false)
                                scope.launch {
                                    jumpChatBookmark(
                                        listState,
                                        itemIndexOfNode(chatLazyIndex(bm.nodeIndex, nodeCount))
                                    )
                                }
                            }
                            .padding(horizontal = 8.dp, vertical = 7.dp)
                    ) {
                        Text(
                            "${bm.ordinal}/${bm.total}",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.surfaceContainer)
                                .padding(horizontal = 5.dp, vertical = 1.dp)
                        )
                        Text(
                            bm.label,
                            style = MaterialTheme.typography.bodySmall,
                            // 2026-11-02 三轮（用户）：长输入最多 2 行（超出省略），行高收紧
                            maxLines = 2,
                            lineHeight = 16.sp,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(start = 7.dp)
                        )
                    }
                }
            }
        }
    }
}
