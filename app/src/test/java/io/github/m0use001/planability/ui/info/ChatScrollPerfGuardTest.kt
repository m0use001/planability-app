package io.github.m0use001.planability.ui.info

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 2026-11-17 滚动流畅性 P0+P2 守卫：
 *
 * 症状：长会话（数千条消息）流式输出与滚动掉帧。
 * 根因与修复（全部钉在源码里）：
 * 1. **流式逐 token 全量 state 拷贝无节流**：ChatEngine 每个 SSE 分片都
 *    `updateStateRow` → `messages.map` 整列表拷贝，下游 nodes/预热/跟随全被
 *    拖成每 token 重建。修：55ms 节流（`flushStateRowThrottled`），结束路径
 *    仍走即时 `updateStateRow`。
 * 2. **预热 `LaunchedEffect(nodes)` 每 token 重启**：nodes 每 55ms 重建一次，
 *    key 用了 nodes 就每 55ms 取消重启 Default 协程。修：key 改结构签名
 *    （size/lastId/streaming），流式期间不重启。
 * 3. **streamKey `remember(state.streamingId, nodes)` 每 token 重建**：
 *    跟随 `LaunchedEffect(state.streaming, streamKey)` 每 token 重订阅。
 *    修：key 去掉 nodes，改 `streamingId + size + lastId`。
 * 4. **MermaidBlock WebView onDispose 空**：不 destroy、JS 不停，长 mermaid
 *    会话多次进出累积实例。修：onDispose 里 stopLoading/removeJS/destroy。
 */
class ChatScrollPerfGuardTest {

    private fun source(moduleRel: String): String {
        val cands = listOf(File(moduleRel), File("app", moduleRel))
        val f = cands.firstOrNull { it.isFile }
        assertTrue("源码不在了：$moduleRel（候选：${cands.map { it.path }}）", f != null)
        return f!!.readText(Charsets.UTF_8).replace("\r\n", "\n")
    }

    private val chatEngine: String
        get() = source("src/main/java/io/github/m0use001/planability/ai/ChatEngine.kt")

    private val chatScreen: String
        get() = source("src/main/java/io/github/m0use001/planability/ui/info/ChatScreen.kt")

    private val diagrams: String
        get() = source("src/main/java/io/github/m0use001/planability/ui/notes/MarkdownDiagrams.kt")

    // ---------- P0a: ChatEngine 流式 state 节流 ----------

    @Test
    fun `ChatEngine 必须有流式 state 节流器`() {
        assertTrue("缺 flushStateRowThrottled", chatEngine.contains("flushStateRowThrottled"))
        assertTrue("缺 lastStateFlush 节流计时", chatEngine.contains("lastStateFlush"))
        assertTrue("节流窗口必须是 55ms", chatEngine.contains("now - lastStateFlush < 55"))
    }

    @Test
    fun `Content 分片必须走节流版不是即时 updateStateRow`() {
        // 主 SSE 路径那处（roundContent 累加处）；文件里还有另一处压缩/摘要路径的
        // 一行式 when 分支，不在本次节流范围。
        val idx = chatEngine.indexOf("roundContent += ev.text")
        assertTrue("找不到主 Content 分支", idx >= 0)
        val branch = chatEngine.substring(idx, (idx + 400).coerceAtMost(chatEngine.length))
        assertTrue("Content 分支必须调 flushStateRowThrottled，不能裸调 updateStateRow\n$branch",
            branch.contains("flushStateRowThrottled"))
        assertFalse("Content 分支还在裸调 updateStateRow（节流失效）\n$branch",
            branch.contains("updateStateRow(currentRowId) { it.copy(content = roundContent) }"))
    }

    @Test
    fun `Reasoning 分片必须走节流版`() {
        val idx = chatEngine.indexOf("roundReasoning += ev.text")
        assertTrue("找不到主 Reasoning 分支", idx >= 0)
        val branch = chatEngine.substring(idx, (idx + 400).coerceAtMost(chatEngine.length))
        assertTrue("Reasoning 分支必须调 flushStateRowThrottled\n$branch",
            branch.contains("flushStateRowThrottled"))
        assertFalse("Reasoning 分支还在裸调 updateStateRow（节流失效）\n$branch",
            branch.contains("updateStateRow(currentRowId) { it.copy(reasoning = roundReasoning) }"))
    }

    @Test
    fun `结束路径仍走即时 updateStateRow 保证最终态不丢`() {
        // done/stopped/error 收尾都用即时 updateStateRow（不经过节流器）
        val doneIdx = chatEngine.indexOf("status = \"done\"")
        assertTrue("找不到 done 收尾", doneIdx >= 0)
        // 至少有一处 updateStateRow 出现在 flushStateRowThrottled 定义之后
        val throttleIdx = chatEngine.indexOf("flushStateRowThrottled")
        val tail = chatEngine.substring(throttleIdx)
        assertTrue("收尾路径必须仍调 updateStateRow（最终态即时写）", tail.contains("updateStateRow(currentRowId)"))
    }

    @Test
    fun `调试假流路径必须与真实 SSE 路径同口径节流`() {
        val idx = chatEngine.indexOf("suspend fun debugFakeStream()")
        assertTrue("找不到 debugFakeStream", idx >= 0)
        val fn = chatEngine.substring(idx, (idx + 3000).coerceAtMost(chatEngine.length))
        assertTrue("假流路径的 state 更新必须 55ms 节流\n$fn", fn.contains("lastStateFlush >= 55"))
        assertTrue("假流路径的 DB 落盘必须 600ms 节流\n$fn", fn.contains("lastDbFlush >= 600"))
    }

    // ---------- P0b: ChatScreen 预热/streamKey 稳定 key ----------

    @Test
    fun `预热 LaunchedEffect 的 key 不得是裸 nodes`() {
        // 找 warmUpFormulas( 的实际调用处（注释里也提到 warmUpFormulas，需带括号区分）
        val warmIdx = chatScreen.indexOf("warmUpFormulas(")
        assertTrue("找不到 warmUpFormulas( 调用", warmIdx >= 0)
        // 往前找 LaunchedEffect(，取 key 部分（到第一个 ") {"）
        val before = chatScreen.substring(0, warmIdx)
        val leIdx = before.lastIndexOf("LaunchedEffect(")
        assertTrue("找不到预热 LaunchedEffect", leIdx >= 0)
        val closeIdx = chatScreen.indexOf(") {", leIdx)
        assertTrue("找不到 LaunchedEffect key 结束", closeIdx >= 0)
        val key = chatScreen.substring(leIdx + "LaunchedEffect(".length, closeIdx)
        assertFalse("预热 LaunchedEffect 的 key 还含 nodes（会每 55ms 重启）\n$key",
            key.contains("nodes"))
        assertTrue("预热 key 必须含 state.messages.size（结构签名）\n$key",
            key.contains("state.messages.size"))
        assertTrue("预热 key 必须含 state.streaming（流式结束时补热定稿正文）\n$key",
            key.contains("state.streaming"))
    }

    // ---------- 2026-11-18：跟随不许再拽人（滑动末期反向拽 / 点最新跳一半） ----------

    /** 去掉 `//` 注释后的源码：注释里会引用老实现（viewportEndOffset / scrollBy(over)），
     *  那些引用不能把守卫染红。 */
    private fun codeOnly(src: String): String = src.lines().map { line ->
        val i = line.indexOf("//")
        if (line.trimStart().startsWith("//")) "" else if (i >= 0) line.substring(0, i) else line
    }.joinToString("\n")

    @Test
    fun `跟随决策必须走纯函数 BottomClamp_decide`() {
        val code = codeOnly(chatScreen)
        assertTrue("跟随 effect 必须调 BottomClamp.decide（决策抽成纯函数才能穷举单测）",
            code.contains("BottomClamp.decide("))
        // 2026-11-20 钳制模型重写：执行只剩 scrollBy(MAX) 幂等钳底，无状态机无 afterGlue
        assertFalse(
            "ScrollFollow 状态机又回来了（命令式贴底的自指反馈环是三轮大修的病根）",
            code.contains("ScrollFollow.step(")
        )
    }

    @Test
    fun `跟随 effect 的 key 只能是 state_streaming`() {
        val code = codeOnly(chatScreen)
        // 老 bug：key 里带流式节点 key（streamKey），每进一条新消息就重启 effect，
        // 决策机状态被重置成「在底部」→ 把刚上滑的用户又拽回去一次。
        assertFalse("跟随 effect 不该再有 streamKey（重启会重置「是否在底部」）",
            code.contains("streamKey"))
        val idx = code.indexOf("BottomClamp.decide(")
        assertTrue("找不到跟随 effect", idx >= 0)
        val le = code.lastIndexOf("LaunchedEffect(", idx)
        assertTrue("跟随 effect 外面没有 LaunchedEffect", le >= 0)
        val close = code.indexOf(") {", le)
        assertTrue("找不到 LaunchedEffect key 结束", close >= 0)
        val key = code.substring(le + "LaunchedEffect(".length, close).trim()
        assertEquals("跟随 effect 的 key 只能是 state.streaming（重启次数要可预期）",
            "state.streaming", key)
    }

    @Test
    fun `跟随不得再用视口像素推算贴底距离`() {
        val code = codeOnly(chatScreen)
        // 老 bug：over = (last.offset + last.size) - viewportEndOffset 把 contentPadding
        // 也算成「还差多少」，真贴底时是个负值，每帧反向滚一截 = 抽搐。
        // （2026-11-20 收窄：viewportEndOffset 本身不再是禁词——「在底部」的容差判据
        //  isAtBottomWithSlack 用它量最后一个可见 item 的底边与视口底边的差；
        //  禁的是拿它推算「还差多少」再 scrollBy 的老病根。）
        assertFalse("跟随不得再用「还差多少」像素推算贴底", code.contains("- viewportEndOffset"))
        assertFalse("跟随不得对 viewportEndOffset 做减法推算", code.contains("viewportEndOffset -"))
        assertFalse("跟随代码里不得再 scrollBy(over…)", code.contains("scrollBy(over"))
        // 2026-11-23 v78：贴底改为按索引跳到末项（settleToLatest）——scrollBy(巨大值) 在
        // 超大会话上会逐项组合中间条目导致 ANR/闪退（某次会话实测），禁用。
        assertTrue("贴底必须走 settleToLatest（按索引定位，不逐项组合）",
            code.contains("settleToLatest("))
        assertFalse("贴底不得再用 scrollBy(10_000_000f)（超大会话逐项组合→ANR）",
            code.contains("scrollBy(10_000_000f)"))
        assertFalse("贴底不得再用 requestScrollToItem(n - 1 …)（锚点越界→蹦顶）",
            code.contains("requestScrollToItem(n - 1"))
    }

    @Test
    fun `跟随不得再有远距离追赶分支`() {
        val code = codeOnly(chatScreen)
        // 老 bug：末项不在视口就 scrollToItem(count-1) —— 用户上滑刚落手那一帧
        // autoFollow 还没翻 false，整段瞬移回底部（= 反向拽）。
        // 钳制模型的对应闸：BottomClamp.Input.lastVisibleIsLast（false 时不钳）。
        assertFalse("跟随里不得出现「末项不在视口」的远距离追赶判据",
            code.contains("last.index < count - 1"))
        assertTrue(
            "跟随的钳制输入必须含 lastVisibleIsLast 闸（用户在看历史时不许钳）",
            code.contains("lastVisibleIsLast =")
        )
    }

    // ---------- P2: MermaidBlock WebView 销毁 ----------

    @Test
    fun `MermaidBlock 的 onDispose 必须 destroy WebView`() {
        val idx = diagrams.indexOf("fun MermaidBlock(")
        assertTrue("找不到 MermaidBlock", idx >= 0)
        val fn = diagrams.substring(idx, (idx + 1400).coerceAtMost(diagrams.length))
        // 找实际 onDispose 块（注释里也提到 onDispose，用 lastIndexOf 拿到真正的调用）
        val disposeIdx = fn.lastIndexOf("onDispose")
        assertTrue("MermaidBlock 没有 onDispose 块", disposeIdx >= 0)
        val dispose = fn.substring(disposeIdx, (disposeIdx + 250).coerceAtMost(fn.length))
        assertTrue("onDispose 必须 stopLoading\n$dispose", dispose.contains("stopLoading"))
        assertTrue("onDispose 必须 removeJavascriptInterface\n$dispose", dispose.contains("removeJavascriptInterface"))
        assertTrue("onDispose 必须 destroy WebView\n$dispose", dispose.contains("destroy"))
    }
}
