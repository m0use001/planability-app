package io.github.m0use001.planability.ai.rag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v53.7 第 2 期地基：块层切分 + 块级 CAS 编辑。
 *
 * 这块的 bug 形态都是「AI/人同时改同一篇」时才暴露的，所以每条断言都对着一个
 * 真实的并发/边界输入：
 * - 指纹不符还硬写 → 把用户刚改的一段覆盖掉；
 * - 区间交叠两次写 → 内容错位、越改越乱；
 * - 删块后下标整体前移 → 后续编辑打到别的块上；
 * - 未命中的行被「重新拼装」→ 用户原有的空行/缩进被洗掉。
 */
class NoteBlocksTest {

    private val doc = """
        # 实验报告

        第一段讲背景，
        第二行还是背景。

        - 步骤一
        - 步骤二

        ## 结果

        | 项目 | 值 |
        |---|---|
        | 误差 | 0.3 |

        ```
        code line
        ```
    """.trimIndent()

    @Test
    fun splitsByRendererSemantics() {
        val kinds = NoteBlocks.split(doc).map { it.kind }
        assertEquals(
            listOf("heading", "para", "item", "item", "heading", "table", "code"),
            kinds
        )
    }

    @Test
    fun indexesAreStableAndSequential() {
        val blocks = NoteBlocks.split(doc)
        assertEquals(blocks.indices.toList(), blocks.map { it.index })
    }

    @Test
    fun blockTextIsRawSource() {
        val blocks = NoteBlocks.split(doc)
        assertEquals("## 结果", blocks[4].text)
        assertEquals("- 步骤二", blocks[3].text)
        assertEquals("第一段讲背景，\n第二行还是背景。", blocks[1].text)
    }

    @Test
    fun sameContentSameSha_differentKindDifferentSha() {
        assertEquals(NoteBlocks.sha("para", "abc"), NoteBlocks.sha("para", "abc"))
        assertFalse(NoteBlocks.sha("para", "abc") == NoteBlocks.sha("heading", "abc"))
        assertEquals(12, NoteBlocks.sha("para", "abc").length)
    }

    @Test
    fun appliesSingleEditAndKeepsEverythingElse() {
        val blocks = NoteBlocks.split(doc)
        val res = NoteBlocks.apply(doc, listOf(NoteBlocks.Edit(2, blocks[2].sha, "- 步骤一（改）")))
        assertTrue(res.ok)
        assertEquals(1, res.applied)
        assertTrue(res.content.contains("- 步骤一（改）"))
        assertTrue(res.content.contains("- 步骤二"))
        assertTrue(res.content.contains("| 误差 | 0.3 |"))
        // 未被编辑的行逐字保留（空行、缩进都在）
        assertTrue(res.content.contains("第一段讲背景，\n第二行还是背景。\n\n- 步骤一（改）"))
    }

    @Test
    fun staleShaIsAConflictAndDoesNotOverwrite() {
        val blocks = NoteBlocks.split(doc)
        val res = NoteBlocks.apply(doc, listOf(NoteBlocks.Edit(1, "deadbeefdead", "覆盖掉用户的改动")))
        assertEquals(listOf(1), res.conflicts)
        assertEquals(0, res.applied)
        assertEquals(doc, res.content)   // 一个字都不许动
    }

    @Test
    fun outOfRangeIndexIsAConflict() {
        val res = NoteBlocks.apply(doc, listOf(NoteBlocks.Edit(99, "x", "y")))
        assertEquals(listOf(99), res.conflicts)
        assertEquals(doc, res.content)
    }

    @Test
    fun overlappingEditsConflictOnSecond() {
        val blocks = NoteBlocks.split(doc)
        // 同一块改两次：第二次必须判冲突，不能叠加
        val res = NoteBlocks.apply(
            doc,
            listOf(
                NoteBlocks.Edit(1, blocks[1].sha, "第一次"),
                NoteBlocks.Edit(1, blocks[1].sha, "第二次")
            )
        )
        assertEquals(1, res.applied)
        assertEquals(listOf(1), res.conflicts)
        assertTrue(res.content.contains("第一次"))
        assertFalse(res.content.contains("第二次"))
    }

    @Test
    fun emptyNewTextDeletesTheBlock() {
        val blocks = NoteBlocks.split(doc)
        val res = NoteBlocks.apply(doc, listOf(NoteBlocks.Edit(2, blocks[2].sha, "")))
        assertTrue(res.ok)
        assertFalse(res.content.contains("- 步骤一"))
        assertTrue(res.content.contains("- 步骤二"))
    }

    @Test
    fun multiLineReplacementKeepsFollowingBlocksIntact() {
        val blocks = NoteBlocks.split(doc)
        val res = NoteBlocks.apply(doc, listOf(NoteBlocks.Edit(1, blocks[1].sha, "新第一行\n新第二行")))
        assertTrue(res.ok)
        assertTrue(res.content.contains("新第一行\n新第二行\n\n- 步骤一"))
        assertEquals("code", NoteBlocks.split(res.content).last().kind)
    }

    @Test
    fun noEditsIsIdentity() {
        val res = NoteBlocks.apply(doc, emptyList())
        assertEquals(doc, res.content)
        assertTrue(res.ok)
    }

    @Test
    fun outlineIsCompactAndCarriesSha() {
        val out = NoteBlocks.outline(doc)
        assertTrue(out.contains("#0 heading sha="))
        assertTrue(out.contains("「## 结果」"))
        // 多行块压成一行，避免 AI 读到的清单被换行撑爆
        assertTrue(out.contains("第一段讲背景， ⏎ 第二行还是背景。"))
    }
}
