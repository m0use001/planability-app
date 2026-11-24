package io.github.m0use001.planability.ui.info

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「qa 组件末尾出现了一个代码组件」回归闸（2026-11-08 真机英语课会话）。
 *
 * 真实消息（3594/3596/1141 同构）的组件块长这样：
 * ```
 * <｜DSML｜ qa>
 * {"questions":[…]}        ← JSON 一行收尾（以 } 结束）
 * </｜DSML｜>
 * [qa-result] 0=0
 * ```
 * normalizeComponentMarkup 把闭标记翻译成 ```，但 ChatComp.parse 的「未闭合围栏止损」
 * （jsonOk break，2026-10-12 引入）在 JSON 已能解析时就 break——此时 i 停在闭围栏行上，
 * 外层 continue 后没人消费它，`​```​` 单独落进 Md 段 → markdown 渲染成**一个空的
 * 代码块挂在 qa 卡末尾**。模型规范写闭合围栏的（非 DSML）同样中招。
 *
 * 钉死：JSON 解析成功后，紧随其后的闭围栏行必须被消费掉；止损行为（漏写围栏时
 * 后续正文不被吞）保持不变。
 */
class ChatCompParseTrailingFenceTest {

    /** 真机样本（真机消息结尾）：DSML + 单行 JSON + 留痕行 */
    private val dsmlSample = """
        ## 我的排序建议

        **首选 A**：论点明确，文献一篇就够。

        <｜DSML｜ qa>
        {"questions":[{"q":"三个候选里定哪个？","options":["A 手机在视野内就损耗专注（最稳）","B 课间刷短视频不算休息（中等）","C 反驳“屏幕毁掉这一代”（最尖锐）"]}]}
        </｜DSML｜>
        [qa-result] 0=0
    """.trimIndent()

    @Test
    fun `DSML_单行JSON_闭标记_末尾不再悬空代码块`() {
        val out = ChatComp.parse(dsmlSample)
        // 卡片要出得来
        val card = out.filterIsInstance<ChatSegment.Card>().single()
        assertEquals("qa", card.type)
        assertTrue(card.json.getJSONArray("questions").length() == 1)
        // 正文段保留
        assertTrue(out.filterIsInstance<ChatSegment.Md>().any { it.text.contains("首选 A") })
        // 核心断言：任何 Md 段都不应包含裸 ```（那是渲染成空代码块的直接来源）
        out.filterIsInstance<ChatSegment.Md>().forEach {
            assertFalse("Md 段不应残留裸围栏：[${it.text.take(40)}]", it.text.contains("```"))
        }
    }

    @Test
    fun `规范写法_闭合围栏_同样不悬空代码块`() {
        val fenced = """
            先说结论。

            ```qa
            {"questions":[{"q":"选哪个？","options":["A","B"]}]}
            ```
            [qa-result] 0=1
        """.trimIndent()
        val out = ChatComp.parse(fenced)
        assertEquals(1, out.filterIsInstance<ChatSegment.Card>().size)
        out.filterIsInstance<ChatSegment.Md>().forEach {
            assertFalse("Md 段不应残留裸围栏：[${it.text.take(40)}]", it.text.contains("```"))
        }
    }

    @Test
    fun `止损行为不变_模型漏写围栏时后续正文不被吞`() {
        val unclosed = """
            ```qa
            {"questions":[{"q":"选哪个？","options":["A","B"]}]}
            这句是正文，不是组件的一部分。
        """.trimIndent()
        val out = ChatComp.parse(unclosed)
        assertEquals(1, out.filterIsInstance<ChatSegment.Card>().size)
        val md = out.filterIsInstance<ChatSegment.Md>().joinToString("\n") { it.text }
        assertTrue("漏写围栏时正文应保留", md.contains("这句是正文"))
        assertFalse(md.contains("```qa"))
    }

    @Test
    fun `内联单行写法不受影响`() {
        val inline = "看这里：\n```qa {\"questions\":[{\"q\":\"选哪个？\",\"options\":[\"A\",\"B\"]}]}```\n完事。"
        val out = ChatComp.parse(inline)
        assertEquals(1, out.filterIsInstance<ChatSegment.Card>().size)
        val md = out.filterIsInstance<ChatSegment.Md>().joinToString("\n") { it.text }
        assertTrue(md.contains("完事。"))
        assertFalse(md.contains("```"))
    }
}
