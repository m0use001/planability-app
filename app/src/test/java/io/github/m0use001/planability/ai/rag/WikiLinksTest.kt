package io.github.m0use001.planability.ai.rag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v49 知识库：`[[wikilink]]` 解析与删除的边界。
 *
 * 这些断言对应解析器里每一条「看起来多余」的规则——每一条都对应一个会真实发生的输入：
 * 代码块里的示例、别名、未闭合的方括号、跨行、超长标题。
 */
class WikiLinksTest {

    @Test
    fun basicLinksAndAliases() {
        val refs = WikiLinks.parse("看 [[线性代数]] 和 [[概率论|那门课]]")
        assertEquals(2, refs.size)
        assertEquals("线性代数", refs[0].title)
        assertEquals("", refs[0].alias)
        assertEquals("概率论", refs[1].title)
        assertEquals("那门课", refs[1].alias)
    }

    @Test
    fun dedupeIsCaseInsensitive() {
        val refs = WikiLinks.parse("[[Kotlin]] [[kotlin]] [[KOTLIN]]")
        assertEquals(1, refs.size)
    }

    @Test
    fun fenceBlocksAreIgnored() {
        val content = "正文 [[真链接]]\n```\n[[假链接]]\n```\n结尾 [[另一个]]"
        val titles = WikiLinks.parse(content).map { it.title }
        assertEquals(listOf("真链接", "另一个"), titles)
    }

    @Test
    fun unclosedFenceSwallowsRest() {
        // 未闭合的围栏：后面内容都在代码块里，不应产生链接
        val content = "```\n[[假的]]\n"
        assertTrue(WikiLinks.parse(content).isEmpty())
    }

    @Test
    fun malformedBracketsAreIgnored() {
        assertTrue(WikiLinks.parse("[[]]").isEmpty())
        assertTrue(WikiLinks.parse("[[  ]]").isEmpty())
        assertTrue(WikiLinks.parse("[[a\nb]]").isEmpty())      // 不跨行
        assertTrue(WikiLinks.parse("[[a|]]").isNotEmpty())     // 空别名仍算链接
        assertTrue(WikiLinks.parse("普通 [单括号] 文本").isEmpty())
    }

    @Test
    fun embedSyntaxCountsAsLink() {
        val refs = WikiLinks.parse("![[图片笔记]]")
        assertEquals(1, refs.size)
        assertEquals("图片笔记", refs[0].title)
    }

    @Test
    fun normalizationCollapsesWhitespace() {
        assertEquals("线性 代数", WikiLinks.normalize("  线性   代数 "))
        val refs = WikiLinks.parse("[[  线性   代数 ]]")
        assertEquals("线性 代数", refs[0].title)
    }

    @Test
    fun overlongTitleIsNotALink() {
        val long = "长".repeat(121)
        assertTrue(WikiLinks.parse("[[$long]]").isEmpty())
    }

    @Test
    fun removeDeletesOnlyMatchingLink() {
        val content = "A [[目标]] B [[目标|别名]] C [[别的]]"
        val out = WikiLinks.remove(content, "目标")
        assertTrue(out.contains("A  B  C"))
        assertTrue(out.contains("[[别的]]"))
        assertTrue(!out.contains("[[目标]]"))
    }

    @Test
    fun removeIsCaseInsensitiveAndKeepsOthers() {
        val out = WikiLinks.remove("[[Kotlin]] 与 [[Java]]", "kotlin")
        assertTrue(!out.contains("Kotlin"))
        assertTrue(out.contains("[[Java]]"))
    }

    /**
     * v53.6 链接可走：`#锚点` 语法（Obsidian/Logseq 写法）。
     * 锚点只进 Ref.anchor，**不进链接图的边**——同一篇的 `[[X#a]]`/`[[X#b]]` 仍是一条边。
     */
    @Test
    fun anchorIsParsedButDoesNotChangeEdgeIdentity() {
        val refs = WikiLinks.parse("[[微积分#第二章]] 与 [[微积分#第三章|范围]] 与 [[微积分]]")
        assertEquals(1, refs.size)
        assertEquals("微积分", refs[0].title)
        assertEquals("第二章", refs[0].anchor)   // 首次出现为准
    }

    @Test
    fun anchorWithAliasSplitsCorrectly() {
        val refs = WikiLinks.parse("[[线代#行列式|那个公式]]")
        assertEquals(1, refs.size)
        assertEquals("线代", refs[0].title)
        assertEquals("行列式", refs[0].anchor)
        assertEquals("那个公式", refs[0].alias)
    }

    @Test
    fun emptyAnchorStillCountsAndBareHashIsNotATitle() {
        assertEquals("目标", WikiLinks.parse("[[目标#]]")[0].title)
        assertTrue(WikiLinks.parse("[[#只有锚点]]").isEmpty())
    }

    @Test
    fun removeAlsoStripsAnchoredVariants() {
        val out = WikiLinks.remove("A [[目标#小节]] B [[目标|别名]] C", "目标")
        assertTrue(!out.contains("目标"))
        assertTrue(out.contains("A  B  C"))
    }

    /**
     * v53.6 第 1 期 b：提及但未链接。
     * 每一条都对应一个会让面板变成噪音的输入。
     */
    @Test
    fun mentionsPlainText() {
        assertTrue(WikiLinks.mentions("今天复习了微积分第二章", "微积分"))
        assertTrue(WikiLinks.mentions("Review the Kotlin notes", "kotlin"))
    }

    @Test
    fun alreadyLinkedTextIsNotAMention() {
        // 已经 [[微积分]] 的，不该再出现在"提及但未链接"里——否则面板就是反链的复读机
        assertTrue(!WikiLinks.mentions("看 [[微积分]]", "微积分"))
        assertTrue(!WikiLinks.mentions("看 [[微积分|那门课]] 和 [[微积分]]", "微积分"))
    }

    @Test
    fun mentionAndLinkCoexist() {
        // 正文里先提到一次、后面才链接：仍算提及（纯文本段里有）
        assertTrue(WikiLinks.mentions("微积分很难，详见 [[微积分]]", "微积分"))
    }

    @Test
    fun codeFencesDoNotCount() {
        assertTrue(!WikiLinks.mentions("```\n[[微积分]] / 微积分\n```", "微积分"))
    }

    @Test
    fun tooShortTitlesNeverMatch() {
        // 单字标题会命中满篇，只制造噪音
        assertTrue(!WikiLinks.mentions("我 我 我", "我"))
    }
}
