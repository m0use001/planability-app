package io.github.m0use001.planability.ui.info

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 2026-11-20 用户「对话流又密又乱」降噪批的源码级守卫。
 *
 * 口径：对话流里的过程信息（思考 / 工具调用 / 工具回执）与过程链 ChainStepRow 统一成
 * 同一款「安静行」——无底色、14dp 中性图标 + labelSmall 灰字、右侧 ⌄/› 箭头；
 * 「详情/收起」字样退场；展开详情统一走 ChainDetail 左细竖线旁注。正文才是视觉主体。
 *
 * 为什么钉源码字面量：两处底色横条（工具块 secondaryContainer@0.45、思考块
 * surfaceVariant@0.6 compositeOver）是这次用户抱怨的直接视觉源，一旦有人改回去，
 * 这里必须红。
 */
class ChatProcessDeclutterGuardTest {

    private val src: String by lazy {
        val f = File("src/main/java/io/github/m0use001/planability/ui/info/ChatScreen.kt")
        assertTrue("找不到 ChatScreen.kt（测试的工作目录不对？）", f.exists())
        f.readText()
    }

    /** 去掉行注释，免得断言被注释里提到的词绊倒 */
    private fun stripComments(s: String) =
        s.lines().filterNot { it.trimStart().startsWith("//") }.joinToString("\n")

    /** 从标记函数起，到下一个函数/标记为止的函数体（剥行注释） */
    private fun bodyOf(startMarker: String, endMarker: String): String = stripComments(
        src.substringAfter(startMarker).substringBefore(endMarker)
    )

    // ---------- 底色退场 ----------

    @Test
    fun `两处过程横条底色退场`() {
        val code = stripComments(src)
        assertFalse(
            "工具调用块的 secondaryContainer 底色横条又回来了（2026-11-20 用户「又密又乱」的直接视觉源）",
            code.contains("secondaryContainer.copy(alpha = 0.45f)")
        )
        assertFalse(
            "思考块的 surfaceVariant 底色横条又回来了",
            code.contains("surfaceVariant.copy(alpha = 0.6f")
        )
    }

    // ---------- ProcessStepRow：与 ChainStepRow 同款几何 ----------

    @Test
    fun `ProcessStepRow 是无底色安静行`() {
        val b = bodyOf("private fun ProcessStepRow(", "private fun ToolCallsBlock(")
        assertTrue(
            "ProcessStepRow 没对齐 ChainStepRow 的行距（vertical = 5.dp）",
            b.contains("padding(vertical = 5.dp)")
        )
        assertTrue("ProcessStepRow 没用 ⌄/› 箭头 affordance", b.contains("if (open) \"⌄\" else \"›\""))
        assertTrue("ProcessStepRow 文字没走 labelSmall", b.contains("MaterialTheme.typography.labelSmall"))
        assertFalse("ProcessStepRow 不许再带底色", b.contains(".background("))
    }

    // ---------- 思考块 / 工具块 ----------

    @Test
    fun `思考块走 ProcessStepRow 且详情字样退场`() {
        val b = bodyOf("private fun ReasoningBlock(", "private fun ErrorNoticeBar(")
        assertTrue("思考块收起态没走 ProcessStepRow", b.contains("ProcessStepRow("))
        assertTrue("思考块展开体没包 ChainDetail（与过程链同款旁注）", b.contains("ChainDetail {"))
        assertFalse("思考块里的「详情」字样又回来了", b.contains("\"详情\""))
        assertFalse("思考块里的「收起」字样又回来了", b.contains("\"收起\""))
        // 三态文案保持（完成字数 / 流式 ▾ / 流式计时秒数）
        assertTrue("思考块三态文案丢了", b.contains("深度思考 · ") && b.contains("深度思考 ▾"))
    }

    @Test
    fun `工具块走 ProcessStepRow 且详情字样退场`() {
        val b = bodyOf("private fun ToolCallsBlock(", "internal fun toolWrittenPath(")
        assertTrue("工具块收起态没走 ProcessStepRow", b.contains("ProcessStepRow("))
        assertTrue("工具块展开体没包 ChainDetail", b.contains("ChainDetail {"))
        assertFalse("工具块里的「详情」字样又回来了", b.contains("\"详情\""))
        assertFalse("工具块里的「收起」字样又回来了", b.contains("\"收起\""))
        // 与正文拉开一档（尾 Spacer 8dp；正文段落自带 6dp 尾距）
        assertTrue("工具块尾 Spacer 应为 8dp（与正文拉开）", b.contains("Spacer(Modifier.height(8.dp))"))
    }

    // ---------- 工具回执行 ----------

    @Test
    fun `回执行箭头化且成功态转中性`() {
        val b = bodyOf("private fun ToolResultRow(", "internal fun ApprovalCard(")
        assertFalse("回执行的「详情」字样又回来了", b.contains("\"详情\""))
        assertFalse("回执行的「收起」字样又回来了", b.contains("\"收起\""))
        assertTrue("回执行没换 ⌄/› 箭头", b.contains("if (expanded) \"⌄\" else \"›\""))
        assertTrue(
            "成功回执图标又亮回 primary 了（降噪口径：常规回执中性，失败才红）",
            b.contains("ok -> Icons.Rounded.CheckCircle to MaterialTheme.colorScheme.onSurfaceVariant")
        )
        assertTrue("回执行展开体没包 ChainDetail", b.contains("ChainDetail {"))
        // MarkdownView 链路不许退化（ToolResultMarkdownTest 的姊妹断言）
        assertTrue("回执行展开体没走 MarkdownView", b.contains("MarkdownView(body)"))
    }
}
