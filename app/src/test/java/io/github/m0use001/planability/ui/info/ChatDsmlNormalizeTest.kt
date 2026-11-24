package io.github.m0use001.planability.ui.info

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 组件标记归一化的回归闸（2026-11-04 用户「AI 调用会话组件失败了」）。
 *
 * 真机样本（某次会话末尾，deepseek-flash）：正文按协议写完了，但问答组件用的是
 * DSML 标记而不是围栏——
 * ```
 * <｜DSML｜ qa>
 * {"questions":[{"q":"先做哪块？","options":["A","B"]}]}
 * </｜DSML｜>
 * ```
 * 解析器只认 ` ```qa ` 围栏，于是整块回落成纯文本，用户看到一串生标记、卡片没出来。
 * 归一化后应与「模型正确写围栏」完全等价。
 */
class ChatDsmlNormalizeTest {

    private val realSample = """
        ## 一、英语课
        正文若干……

        你想让我先动哪块？

        <｜DSML｜ qa>
        {"questions":[{"q":"先做哪块？","options":["校内 essay","四级听力"]}]}
        </｜DSML｜>
    """.trimIndent()

    @Test
    fun `DSML 问答块归一成围栏_与模型正确写法等价`() {
        val out = normalizeComponentMarkup(realSample)
        assertFalse("不应残留开标记", out.contains("<｜DSML｜"))
        assertFalse("不应残留闭标记", out.contains("</｜DSML｜"))
        assertTrue("应转成 qa 围栏", out.contains("```qa"))
        // JSON 行原样保留在围栏内（解析器下一步吃它）
        assertTrue(out.contains("\"questions\""))
        // 正文不受影响
        assertTrue(out.contains("## 一、英语课"))
        assertTrue(out.contains("你想让我先动哪块？"))
    }

    @Test
    fun `孤立半截 DSML 行整行剥掉`() {
        val src = "解释一下\n  <｜DSML｜ calls>\n继续正文"
        val out = normalizeComponentMarkup(src)
        assertFalse("半截标记不应留下", out.contains("DSML"))
        assertTrue("正文保留", out.contains("解释一下") && out.contains("继续正文"))
    }

    @Test
    fun `不认识的 DSML 开标记直接丢弃_不污染正文`() {
        val out = normalizeComponentMarkup("<｜DSML｜ unknown>abc</｜DSML｜>")
        assertFalse(out.contains("DSML"))
        assertTrue("块内文字按普通正文留下", out.contains("abc"))
    }

    @Test
    fun `普通正文与既有围栏不受影响`() {
        val plain = "纯文本 **粗体** | 表格 |\n\n```qa {\"questions\":[]}```"
        assertEquals("没有 DSML 时应原样返回", plain, normalizeComponentMarkup(plain))
    }
}
