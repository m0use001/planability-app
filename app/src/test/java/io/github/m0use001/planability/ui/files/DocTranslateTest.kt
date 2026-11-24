package io.github.m0use001.planability.ui.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 文档中文对照阅读的**段落切分与批量协议**回归（2026-11-05）。
 *
 * 锁三件事：
 * 1. 抽取器的页标记（`[第 N 页]` / `[S0.0]`）必须被正确剥离并挂到段落上——
 *    页标记错位会让译文插到别的页下面，比不翻译更误导；
 * 2. 不值得翻的内容（纯数字、纯符号、已是中文、太短）必须跳过，别把 token 烧在页码上；
 * 3. 批量协议双向可解析：构造的 `序号|原文` 能被模型理解、模型回的 `序号|译文` 能按序装上，
 *    漏条要留 null（触发逐段补发），不能错位。
 */
class DocTranslateTest {

    @Test
    fun `pptx 页标记被剥离并挂到段落`() {
        val lines = listOf(
            "[第 1 页]",
            "[S0.0] Chapter 8",
            "[S0.1] Indeterminate Forms",
            "[第 2 页]",
            "[S1.0] Calculus 1"
        )
        val paras = DocTranslate.splitParas(lines)
        assertEquals(3, paras.size)
        assertEquals("第 1 页", paras[0].page)
        assertEquals("Chapter 8", paras[0].text)
        assertEquals("第 1 页", paras[1].page)
        assertEquals("第 2 页", paras[2].page)
        assertEquals("Calculus 1", paras[2].text)
    }

    @Test
    fun `docx 段落标记被剥离`() {
        val paras = DocTranslate.splitParas(listOf("[P1] First paragraph", "[P2] Second paragraph"))
        assertEquals(2, paras.size)
        assertEquals("First paragraph", paras[0].text)
        assertTrue(paras[0].page.isBlank())
    }

    @Test
    fun `页码数字与符号行被跳过`() {
        val lines = listOf(
            "12", "——", "1/25", "[第 1 页]", "[S0.0] Real sentence here"
        )
        val paras = DocTranslate.splitParas(lines)
        assertEquals(1, paras.size)
        assertEquals("Real sentence here", paras[0].text)
    }

    @Test
    fun `中文为主的段落不翻`() {
        assertEquals(false, DocTranslate.worthTranslating("这是已经翻译好的中文段落，无需再译"))
        assertEquals(true, DocTranslate.worthTranslating("Indeterminate Forms of Type 0/0"))
        // 英文 + 少量中文（术语）→ 仍要翻
        assertEquals(true, DocTranslate.worthTranslating("求极限的 indeterminate form 例子"))
    }

    @Test
    fun `太短的内容不翻`() {
        assertEquals(false, DocTranslate.worthTranslating("a"))
        assertEquals(false, DocTranslate.worthTranslating("图 1"))
    }

    @Test
    fun `批量协议可往返`() {
        val texts = listOf("First line one", "Second line two")
        val block = DocTranslate.buildUserBlock(texts)
        assertTrue(block.contains("1|First line one"))
        assertTrue(block.contains("2|Second line two"))

        val reply = "1|第一行\n2|第二行"
        val parsed = DocTranslate.parseBatch(reply, 2)
        assertEquals("第一行", parsed[0])
        assertEquals("第二行", parsed[1])
    }

    @Test
    fun `模型漏条时留null而不是错位`() {
        val parsed = DocTranslate.parseBatch("2|只有第二条", 2)
        assertNull(parsed[0])
        assertEquals("只有第二条", parsed[1])
    }

    @Test
    fun `模型加了多余说明也不崩`() {
        val raw = """
            好的，以下是翻译：
            1|第一段译文
            2|第二段译文
            希望有帮助！
        """.trimIndent()
        val parsed = DocTranslate.parseBatch(raw, 2)
        assertEquals("第一段译文", parsed[0])
        assertEquals("第二段译文", parsed[1])
    }

    @Test
    fun `总段落数有上限`() {
        val many = (1..400).map { "[P$it] A long enough English sentence number $it here" }
        val paras = DocTranslate.splitParas(many)
        assertTrue("应封顶在 ${DocTranslate.MAX_PARAS}", paras.size <= DocTranslate.MAX_PARAS)
    }

    @Test
    fun `空输入得到空段落表`() {
        assertTrue(DocTranslate.splitParas(emptyList()).isEmpty())
    }
}
