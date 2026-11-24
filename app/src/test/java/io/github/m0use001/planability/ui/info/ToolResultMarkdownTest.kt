package io.github.m0use001.planability.ui.info

import androidx.compose.ui.text.font.FontWeight
import io.github.m0use001.planability.ui.notes.inlineMd
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 用户报障（2026-11-14）：「表格内（和其他一些地方）md 格式没渲染」。
 *
 * 截图上工具回执报告里的表格是一串原文：`**D_x(k)=0**`、`$\ln(1+\alpha)\sim\alpha$`。
 * 病根不在表格、也不在行内解析——**工具回执的展开正文整段是一行 `Text(body)`**：
 * 回执里本来就带完整 markdown 报告（表格/粗体/公式），纯文本渲染自然全是源码。
 *
 * 修法：ToolResultRow 与过程链的「返回详情」都改走 MarkdownView（同助手正文一套链路）。
 * 这份守卫钉「回执正文不许再退回纯 Text」，并确认 inlineMd 本身对粗体是好的
 * （排除"是不是 inlineMd 坏了"这种误判）。
 */
class ToolResultMarkdownTest {

    private fun source(rel: String): String {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null && !File(dir, "app/src/main/java/io/github/m0use001/planability").isDirectory) {
            dir = dir.parentFile
        }
        val f = File(requireNotNull(dir) { "找不到仓库根" }, rel)
        assertTrue("源码不在了：$rel", f.isFile)
        return f.readText(Charsets.UTF_8).replace("\r\n", "\n")
    }

    @Test
    fun `工具回执展开正文走 MarkdownView 不许纯 Text`() {
        val src = source("app/src/main/java/io/github/m0use001/planability/ui/info/ChatScreen.kt")
        val row = src.substringAfter("private fun ToolResultRow(").substringBefore("expanded") 
        assertTrue("ToolResultRow 里没有 MarkdownView", src.contains("MarkdownView(body)"))
        assertFalse(
            "工具回执正文又退回纯 Text 了（表格/粗体/公式会原样裸显示）",
            row.contains("Text(\n                            body") || row.contains("Text(body,")
        )
    }

    @Test
    fun `过程链的返回详情也走 MarkdownView`() {
        val src = source("app/src/main/java/io/github/m0use001/planability/ui/info/ChatScreen.kt")
        val seg = src.substringAfter("expandedResult,").substringBefore("}", missingDelimiterValue = "")
        assertTrue(
            "过程链「返回详情」还在用纯 Text 渲染回执",
            seg.contains("MarkdownView(")
        )
        assertFalse(
            "过程链「返回详情」还是 Text(result.content…",
            seg.contains("Text(\n")
        )
    }

    @Test
    fun `行内解析本身没问题_粗体与行内码都要出样式`() {
        // 排除误判：inlineMd 对 `**粗体**` / `` `code` `` 是好的，问题只在调用点。
        val bold = inlineMd("**D_x(k)=0**")
        assertEquals("粗体文本内容", "D_x(k)=0", bold.text)
        assertTrue(
            "粗体没生效——inlineMd 本身坏了？",
            bold.spanStyles.any { it.item.fontWeight == FontWeight.Bold }
        )
        val mono = inlineMd("前 `code` 后")
        assertTrue("行内码没生效", mono.spanStyles.isNotEmpty())
    }
}
