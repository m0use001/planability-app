package io.github.m0use001.planability.ui.info

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 2026-11-20 钳制模型重写（用户拍板「壮士断腕」）的源码级守卫。
 *
 * 背景：命令式贴底（suspend scrollToItem 循环）的自指反馈环是 11-18、11-20 三轮
 * 大修的共同病根——自己的滚动把自己判成手势、两个滚动驱动互相取消、等帧读数 +
 * 容差被一帧长一大截击穿。重写后：
 *  · 执行 = `requestScrollToItem(末项, MAX)` + `scrollBy(Float.MAX_VALUE)` 幂等钳到底；
 *  · 「要不要钳」= 纯函数 `BottomClamp.decide`（真值表在 BottomClampTest 穷举）；
 *  · 删除 snapGuard / snappingToBottom / 1600ms 循环 / ScrollFollow 状态机。
 *
 * 本文件钉住新原语不许退化回命令式，每条断言都做过植入故障验证（改坏必须变红）。
 */
class ChatScrollUserGestureGuardTest {

    private fun source(rel: String): String {
        val cands = listOf(File(rel), File("app", rel))
        val f = cands.firstOrNull { it.isFile }
        assertTrue("源码不在了：$rel", f != null)
        return f!!.readText(Charsets.UTF_8).replace("\r\n", "\n")
    }

    private val chatScreen: String
        get() = source("src/main/java/io/github/m0use001/planability/ui/info/ChatScreen.kt")

    /** 去掉行内注释与整行注释，避免注释里提到老实现把守卫染红。 */
    private fun codeOnly(src: String): String = src.lines().map { line ->
        val i = line.indexOf("//")
        if (line.trimStart().startsWith("//")) "" else if (i >= 0) line.substring(0, i) else line
    }.joinToString("\n")

    /** snapToBottom 函数体（从声明起 1600 字符窗口，够覆盖两步动作与 finally）。 */
    private val snapFn: String
        get() {
            val code = codeOnly(chatScreen)
            val i = code.indexOf("val snapToBottom: suspend () -> Unit")
            assertTrue("找不到统一的贴底函数 snapToBottom", i >= 0)
            return code.substring(i, (i + 1600).coerceAtMost(code.length))
        }

    // ---------- 贴底原语：幂等钳制，不是命令循环 ----------

    @Test
    fun `贴底走按索引定位的 settleToLatest_不用大 scrollBy`() {
        val fn = snapFn
        // 2026-11-23 v78：大 scrollBy 在超大会话上逐项组合→ANR/闪退；改按索引跳末项。
        assertTrue(
            "贴底必须走 settleToLatest（按索引定位末项）",
            fn.contains("settleToLatest(")
        )
        assertFalse(
            "贴底又用回 scrollBy(10_000_000f)（超大会话→ANR）",
            fn.contains("scrollBy(10_000_000f)")
        )
        assertFalse(
            "贴底又用回 requestScrollToItem（锚点越界→蹦顶）",
            fn.contains("requestScrollToItem(")
        )
        assertFalse(
            "贴底又用回 scrollBy(Float.MAX_VALUE)（整数溢出→滚回顶部）",
            fn.contains("scrollBy(Float.MAX_VALUE)")
        )
    }

    @Test
    fun `贴底不许再回到命令式循环`() {
        val fn = snapFn
        assertFalse(
            "贴底又出现了反复发命令的循环（自指反馈环的病根）——只许 request 一次 + 钳一次",
            fn.contains("while (true)")
        )
        assertFalse(
            "贴底不许再带超时兜底循环（钳制是幂等的，一轮就收敛）",
            fn.contains("withTimeoutOrNull(1600")
        )
        assertFalse(
            "贴底不许再 delay 重发（旧 50ms 循环是「卡在半路」的来源）",
            fn.contains("kotlinx.coroutines.delay(50)")
        )
    }

    @Test
    fun `手势判据 snapGuard 已随命令式原语删除`() {
        val code = codeOnly(chatScreen)
        assertFalse(
            "snapGuard 又回来了（那是给「逐帧重发的命令」判手势用的，钳制原语不需要）",
            code.contains("snapGuard(")
        )
        assertFalse(
            "snappingToBottom 让路标志又回来了（两个「到底」互取消无后果，不需要让路）",
            code.contains("snappingToBottom")
        )
    }

    // ---------- 贴底必须仍是程序滚动（触感 + 跟随探测） ----------

    @Test
    fun `贴底要打程序滚动标记并压端点触感`() {
        val fn = snapFn
        assertTrue("贴底没打程序滚动标记（外层探测会误关跟随）", fn.contains("ProgrammaticScroll.begin()"))
        assertTrue("贴底没压端点触感（程序滚动撞底不该震）", fn.contains("ScrollHapticSuppress.suppress("))
        val endIdx = fn.lastIndexOf("ProgrammaticScroll.end()")
        assertTrue("贴底没收程序滚动标记", endIdx >= 0)
    }

    @Test
    fun `流式跟随必须走 BottomClamp 纯函数`() {
        val code = codeOnly(chatScreen)
        assertTrue(
            "跟随 effect 必须调 BottomClamp.decide（决策收进可穷举纯函数）",
            code.contains("io.github.m0use001.planability.core.BottomClamp.decide(")
        )
        assertTrue(
            "跟随的输入必须含 listDragged（手指按着时不许钳）",
            code.contains("dragged = listDragged")
        )
        assertTrue(
            "跟随的输入必须含末可见项是否为末项（用户在看历史时不许钳）",
            code.contains("lastVisibleIsLast =")
        )
    }

    @Test
    fun `跟随的钳底必须包在程序滚动标记里`() {
        val code = codeOnly(chatScreen)
        val glue = code.indexOf("BottomClamp.decide(")
        // 2026-11-23：钳底动作改为 settleToLatest（按索引贴底，大 scrollBy 会 ANR）
        val clamp = code.indexOf("settleToLatest(", glue)
        assertTrue("找不到跟随的钳底动作", clamp >= 0)
        val window = code.substring(glue, clamp)
        assertTrue(
            "跟随的钳底没有打 ProgrammaticScroll 标记——外层「滚动刚结束且不在底」探测器会把" +
                "我们自己的钳底当成用户离开底部，跟随永久死掉（11-20 老 bug）",
            window.contains("ProgrammaticScroll.begin()")
        )
    }

    @Test
    fun `流式收尾必须补一记贴底`() {
        val code = codeOnly(chatScreen)
        // 锚在跟随 effect 内的 BottomClamp.decide，向前回扫收尾分支（!streaming 早返回在它之前）
        val decide = code.indexOf("BottomClamp.decide(")
        assertTrue("找不到跟随 effect 的 BottomClamp.decide", decide >= 0)
        // 2026-11-24：钳制输入新增 userScrolling/cleanFollow 两行（死锁豁免），
        // 回扫窗口 600→1000——语义不变（缺收尾补贴底仍 FAIL，已做植入故障验证）。
        val window = code.substring((decide - 1000).coerceAtLeast(0), decide)
        assertTrue(
            "流式收尾没有补贴底——胶囊会在回复结束后因最后一帧的增长缝常驻",
            window.contains("if (autoFollow) snapToBottom()")
        )
    }

    // ---------- 决策层（pinned / 胶囊）不许退化 ----------

    @Test
    fun `在底判定必须带容差`() {
        val code = codeOnly(chatScreen)
        assertTrue(
            "「在底部」没有走容差判据 isAtBottomWithSlack——胶囊显示在惯性中要即时出现",
            code.contains("isAtBottomWithSlack(")
        )
        assertTrue(
            "容差值没接进 atBottom（bottomSlackPx 必须作为 remember 的 key）",
            code.contains("remember(bottomSlackPx)")
        )
    }

    @Test
    fun `流式跟随时胶囊必须压住`() {
        val code = codeOnly(chatScreen)
        val pill = code.indexOf("showLatestPill by remember")
        assertTrue("找不到 showLatestPill", pill >= 0)
        val window = code.substring(pill, (pill + 300).coerceAtMost(code.length))
        assertTrue(
            "胶囊显隐没有压住「流式跟随活着」的场景——气泡一帧长出一大截会击穿任何容差，" +
                "胶囊跟着闪现，用户点它时列表其实就在底（观感=跳转失效）",
            window.contains("!(state.streaming && autoFollow)")
        )
    }

    // ---------- 纯函数判据（老 snapGuard 单测位保留语义：手势优先） ----------

    @Test
    fun `钳制决策在手指按着时必须为否`() {
        // 直接钉纯函数语义（完整真值表在 core/BottomClampTest）
        assertEquals(
            false,
            io.github.m0use001.planability.core.BottomClamp.decide(
                io.github.m0use001.planability.core.BottomClamp.Input(
                    streaming = true, pinned = true, dragged = true,
                    lastVisibleIsLast = true, canScrollForward = true,
                )
            )
        )
    }
}
