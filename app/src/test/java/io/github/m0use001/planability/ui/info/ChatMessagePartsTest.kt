package io.github.m0use001.planability.ui.info

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 2026-11-21 巨型消息切条（用户拍板「切，但保住跨块选词」）。
 *
 * 判据只有三条，但每条都必须能红：
 * 1. **逐字节还原**——切块拼回去必须与原文完全相同，否则同一份 markdown 会渲染成两种样子；
 * 2. **不切坏结构**——代码围栏不横跨两块、表格不被切在中间（表格切一半会变成两张残缺表）；
 * 3. **该切才切**——短消息保持单块（不制造无谓的条目和间距）。
 */
class ChatMessagePartsTest {

    private val longBody = buildString {
        repeat(6) { i ->
            appendLine("## 第 $i 段结论")
            appendLine()
            repeat(6) { appendLine("这一段是正文，说明第 $i 段的推理过程，长度足够触发切块逻辑。") }
            appendLine()
        }
    }

    @Test
    fun `短消息不切`() {
        val md = "只有两行\n很短"
        assertEquals(1, markdownChunks(md, maxChars = 1100).size)
    }

    @Test
    fun `切块拼回去与原文逐字节相同`() {
        val chunks = markdownChunks(longBody, maxChars = 400)
        assertTrue("应当切成多块", chunks.size > 1)
        assertEquals(longBody, chunks.joinToString("\n"))
    }

    @Test
    fun `代码围栏不横跨两块`() {
        val code = buildString {
            appendLine("# 标题")
            appendLine()
            appendLine("下面是代码：")
            appendLine("```kotlin")
            repeat(80) { appendLine("    val x$it = compute($it) // 长代码行，占位占位占位") }
            appendLine("```")
            appendLine()
            appendLine("代码结束，后面还有正文。后面还有正文。后面还有正文。")
        }
        val chunks = markdownChunks(code, maxChars = 500)
        // 围栏成对出现在**同一个**块里：任意块内 ``` 计数必须是偶数
        chunks.forEach { c ->
            val ticks = c.lines().sumOf { l ->
                var n = 0
                var i = 0
                val t = l.trimStart()
                while (i + 2 < t.length) {
                    if (t[i] == '`' && t[i + 1] == '`' && t[i + 2] == '`') { n++; i += 3 } else i++
                }
                n
            }
            assertEquals("块内围栏必须成对：\n$c", 0, ticks % 2)
        }
        assertEquals(code, chunks.joinToString("\n"))
    }

    @Test
    fun `表格不被切在中间`() {
        val md = buildString {
            appendLine("开头正文。".repeat(40))
            appendLine()
            appendLine("| 名称 | 说明 | 数量 |")
            appendLine("| --- | --- | --- |")
            repeat(6) { i -> appendLine("| 行$i | 说明文字 | ${i * 3} |") }
            appendLine()
            appendLine("结尾正文。".repeat(40))
        }
        val chunks = markdownChunks(md, maxChars = 300)
        // 表头 + 全部分隔行必须在同一块里连续出现
        val tableBlock = "| 名称 | 说明 | 数量 |\n| --- | --- | --- |"
        assertTrue(
            "表头与分隔行不能分家：${chunks.size} 块",
            chunks.any { it.contains(tableBlock) }
        )
        assertEquals(md, chunks.joinToString("\n"))
    }

    @Test
    fun `长列表也能切_序号保持原文字面量`() {
        // 一整段没有空行的有序列表（AI 回答最常见的形状）：必须能在列表项之间切，
        // 且序号是原文字面量，切开后不会重新编号（MdTextLines 直接画 `1.`）。
        val md = buildString {
            repeat(30) { i -> appendLine("${i + 1}. 第 ${i + 1} 条说明文字，写长一点好触发切块。") }
        }
        val chunks = markdownChunks(md, maxChars = 200)
        assertTrue("长列表应切成多块，实际 ${chunks.size}", chunks.size > 1)
        assertEquals(md, chunks.joinToString("\n"))
        // 每块的第一行序号连续：切点落在列表项边界，没有把某一条劈成两半
        chunks.forEach { c ->
            val first = c.lineSequence().first { it.isNotBlank() }
            assertTrue("块首应是完整列表项：$first", Regex("^\\d+[.)]\\s").containsMatchIn(first))
        }
    }

    @Test
    fun `超长消息切成多片_短消息仍为单片`() {
        val parts = splitMessageParts(listOf(ChatSegment.Md(longBody)))
        assertTrue("长消息应切成多片", parts.size > 1)
        // 每片拼接后再拼回整体，仍等于原文
        val joined = parts.flatMap { p -> p.map { (it as ChatSegment.Md).text } }.joinToString("\n")
        assertEquals(longBody, joined)

        val short = splitMessageParts(listOf(ChatSegment.Md("短")))
        assertEquals(1, short.size)
    }

    /**
     * 2026-11-23 v78：片数改为「纯扫描计数」（countMessageParts），不再 parse 全历史
     * （◆ 微积分进场 ANR/闪退根因）。计数必须与 markdownChunks **同口径**，
     * 否则条目数对不上 → 有片永远不渲染或越界。形状覆盖：短/边界/围栏/表格/长列表。
     */
    @Test
    fun `片数计数与切块结果永远一致`() {
        val cases = listOf(
            "短消息",
            "x".repeat(399),
            "y".repeat(400),
            "z".repeat(801),
            buildString {
                appendLine("开头说明"); appendLine()
                appendLine("```kotlin")
                repeat(80) { appendLine("val x$it = $it  // 这一行足够长以触发切块逻辑") }
                appendLine("```"); appendLine(); appendLine("结尾说明")
            },
            buildString {
                appendLine("| 列A | 列B |"); appendLine("| --- | --- |")
                repeat(60) { appendLine("| 数据$it | 数值$it |") }
            },
            buildString { repeat(80) { i -> appendLine("${i + 1}. 第 ${i + 1} 条说明，足够长以触发切块逻辑。") } },
            longBody
        )
        for ((i, md) in cases.withIndex()) {
            val expected = if (md.length < MSG_PART_MIN_CHARS) 1 else markdownChunks(md).size
            assertEquals("第 $i 个样例片数口径不一致", expected, countMessageParts(md))
            if (expected > 1) {
                val joined = (0 until expected).joinToString("\n") { messageChunkAt(md, it) }
                assertEquals("第 $i 个样例逐片取文拼回不等原文", md, joined)
            }
        }
    }
}
