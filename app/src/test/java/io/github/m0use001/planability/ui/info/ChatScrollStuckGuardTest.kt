package io.github.m0use001.planability.ui.info

import io.github.m0use001.planability.core.ProgrammaticScroll
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 2026-11-17 用户实报：
 *
 * > 「点『最新』有时不会自动到底部，常常中途卡住，
 * >   会话与自动书签列表也常常中途卡住然后震动」
 *
 * 三条根因（全部在源码里钉住）：
 * 1. 「最新」胶囊与会话进入贴底是**两套策略**（3 轮×60ms / while+1.5s），且都没告诉
 *    `autoFollow`「这是我在滚」——贴底一轮落在半路就被当成「用户主动离开底部」，
 *    跟随永久关闭、胶囊也不消失 = 「卡住」。
 * 2. 流式跟随只在「视口末项恰好是列表末项」时动，落到半路后**再也不追**。
 * 3. 程序滚动撞到列表端点会触发 `reachedEndHaptic` 的**重震**（抽屉折叠/跳底、「最新」
 *    贴底都没压掉）= 「然后震动」。
 *
 * 2026-11-20 钳制模型重写后：贴底 = requestScrollToItem + scrollBy(MAX) 两步幂等，
 * 两个入口仍共用同一个函数；根因 2 由 BottomClamp 纯函数接管（见 core/BottomClampTest）。
 */
class ChatScrollStuckGuardTest {

    private fun source(rel: String): String {
        val cands = listOf(File(rel), File("app", rel))
        val f = cands.firstOrNull { it.isFile }
        assertTrue("源码不在了：$rel（候选：${cands.map { it.path }}）", f != null)
        return f!!.readText(Charsets.UTF_8).replace("\r\n", "\n")
    }

    private fun chatSource(sub: String): String =
        source("src/main/java/io/github/m0use001/planability/$sub")

    @Test
    fun 程序滚动标记是计数器_嵌套时不会被内层提前清掉() {
        ProgrammaticScroll.begin()
        ProgrammaticScroll.begin()
        ProgrammaticScroll.end()
        assertTrue("还有一层程序滚动在跑，不该被清掉", ProgrammaticScroll.active())
        ProgrammaticScroll.end()
        // 留了一小段收尾宽限（Lazy 的 settle 可能比返回晚）
        Thread.sleep(520)
        assertFalse("两层都结束了，宽限过后应恢复为 false", ProgrammaticScroll.active())
    }

    @Test
    fun 贴底只有一个实现_两个入口共用() {
        val s = chatSource("ui/info/ChatScreen.kt")
        assertTrue("没有统一的贴底函数", s.contains("val snapToBottom: suspend () -> Unit"))
        val fnIdx = s.indexOf("val snapToBottom: suspend () -> Unit")
        // 窗口 2000 字符：贴底函数头部的口径注释会变长，判据必须仍在函数体前段
        val fn = s.substring(fnIdx, (fnIdx + 2000).coerceAtMost(s.length))
        // 2026-11-23 v78：贴底改为按索引跳到末项（settleToLatest）。大 scrollBy 在超大
        // 会话上会逐项组合全部中间条目（ANR/闪退根因），requestScrollToItem 越界会蹦顶。
        assertTrue(
            "贴底必须走 settleToLatest（按索引定位末项）",
            fn.contains("settleToLatest(")
        )
        assertFalse("贴底又用回 scrollBy(10_000_000f)（超大会话→ANR）", fn.contains("scrollBy(10_000_000f)"))
        assertFalse(
            // 用 `listState.` 前缀判定：函数头的口径注释里会出现 requestScrollToItem(末项…) 的字样
            "贴底又用回 requestScrollToItem（锚点 offset 越界会把列表重置到顶部=蹦顶）",
            fn.contains("listState.requestScrollToItem(")
        )
        assertFalse(
            "贴底又用回 Float.MAX_VALUE（Float→像素 Int 溢出成负数，滚回顶部）",
            fn.contains("scrollBy(Float.MAX_VALUE)")
        )
        assertTrue("贴底没打程序滚动标记", fn.contains("ProgrammaticScroll.begin()"))
        assertTrue("贴底没压端点触感", fn.contains("ScrollHapticSuppress.suppress("))
        // 旧的三轮重试必须消失（两套策略就是卡住的来源之一）
        assertFalse("还留着旧的 repeat(3) 贴底", s.contains("repeat(3) {"))
        assertFalse("还留着旧的 1600ms 兜底循环", fn.contains("withTimeoutOrNull(1600"))
        // 两个入口都走同一个函数
        val pill = s.indexOf("点了就是\"我要回到最新\"")
        assertTrue("找不到「最新」胶囊点击处理", pill >= 0)
        assertTrue(
            "「最新」胶囊没有改用统一下底函数",
            s.substring(pill, (pill + 1500).coerceAtMost(s.length)).contains("runCatching { snapToBottom() }")
        )
        val enter = s.indexOf("if (!restored) {")
        assertTrue("找不到进会话贴底", enter >= 0)
        assertTrue(
            "进会话贴底没有改用统一函数",
            s.substring(enter, (enter + 400).coerceAtMost(s.length)).contains("snapToBottom()")
        )
    }

    @Test
    fun 程序化贴底期间不许把跟随关掉() {
        val s = chatSource("ui/info/ChatScreen.kt")
        assertTrue(
            "autoFollow 的关闭判据没有排除程序滚动（贴底半路 = 跟随被永久关闭）",
            s.contains("!io.github.m0use001.planability.core.ProgrammaticScroll.active()")
        )
    }

    @Test
    fun 流式跟随走纯函数_不许再用整段追赶往回拽() {
        val s = chatSource("ui/info/ChatScreen.kt")
        assertTrue(
            "跟随 effect 必须走纯决策 BottomClamp.decide——「该不该钳」必须收进可穷举单测的纯函数",
            s.contains("io.github.m0use001.planability.core.BottomClamp.decide(")
        )
        // 2026-11-18 用户「滑动末期屏幕突然被反向拽一下」的根因之一就是这个分支：
        // 只要末项不在视口里就整段 scrollToItem 回底部，用户刚滑上去的那一帧也会被执行。
        // 钳制模型的对应闸 = lastVisibleIsLast（见 BottomClampTest 时序回归）。
        assertFalse(
            "跟随又出现了「落后就整段追赶」分支（if (last.index < count - 1) …）",
            s.contains("if (last.index < count - 1) {")
        )
    }

    @Test
    fun 抽屉的程序滚动要压掉端点重震() {
        val s = chatSource("ui/info/WorkbenchDrawer.kt")
        for (anchor in listOf("drawerScroll.scrollToItem(headerIndex, collapseTopPadPx)", "drawerScroll.animateScrollToItem(")) {
            val i = s.indexOf(anchor)
            assertTrue("找不到程序滚动点：$anchor", i >= 0)
            val before = s.substring((i - 400).coerceAtLeast(0), i)
            assertTrue(
                "程序滚动前没压端点触感（会放一声重震）：$anchor",
                before.contains("ScrollHapticSuppress.suppress(")
            )
        }
    }

    @Test
    fun 书签面板不许在用户滚动时被拽回去() {
        val s = chatSource("ui/info/ChatBookmarks.kt")
        assertTrue(
            "书签面板跟随没检查「用户正在滚面板」（主列表一动就把面板拽回当前条 = 卡住）",
            s.contains("if (panelListState.isScrollInProgress) return@LaunchedEffect")
        )
    }

    @Test
    fun 会话列表不做逐项落位动画() {
        val s = chatSource("ui/info/ChatScreen.kt")
        val i = s.indexOf("items(items.filter { !it.archived }")
        assertTrue("找不到会话列表 items", i >= 0)
        val block = s.substring(i, (i + 300).coerceAtMost(s.length))
        assertFalse(
            "会话列表又用上了 animateItem（dataVersion 整体重装时会抖动/卡顿）",
            block.contains("animateItem()")
        )
    }
}
