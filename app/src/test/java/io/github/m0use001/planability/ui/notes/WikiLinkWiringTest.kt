package io.github.m0use001.planability.ui.notes

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * v53.6 链接可走的**接线**断言（源码级）。
 *
 * 逻辑层（切片/锚点/提及）已有单测；这里钉的是「接线没被后来的重构剪断」——
 * 这类断线的表现是「功能代码都在、界面上就是不出现」，单测全绿也发现不了：
 * - `MdInlineText` 没读 [LocalWikiLinks] → 胶囊永远不渲染；
 * - 表格单元格改回 `Text(inlineMd(…))` → 表格里的 `[[ ]]` 静默退化成纯文本；
 * - 编辑器预览没套 `CompositionLocalProvider` → 上下文是 null，整页退回旧行为。
 */
class WikiLinkWiringTest {

    private fun code(path: String): String {
        val f = File("src/main/java/io/github/m0use001/planability/ui/notes/$path")
        assertTrue("找不到 $path（测试工作目录不对？）", f.exists())
        return f.readText().lines().filterNot { it.trimStart().startsWith("//") }.joinToString("\n")
    }

    @Test
    fun inlineTextHonorsTheWikiContext() {
        val c = code("MarkdownMath.kt")
        assertTrue(
            "MdInlineText 没读 LocalWikiLinks —— 正文 [[X]] 永远不会变成胶囊",
            c.contains("LocalWikiLinks.current")
        )
        assertTrue("MdInlineText 没有走片段渲染（FlowRow/切片）", c.contains("splitWikiSegments"))
    }

    @Test
    fun tableCellsStillGoThroughInlineText() {
        val c = code("NotesScreen.kt")
        val table = c.substringAfter("private fun MdTable(")
        assertTrue("没找到 MdTable（改名了？更新锚点）", table.isNotEmpty())
        assertTrue(
            "表格单元格改回了 Text(inlineMd(…)) —— 表格里的 [[ ]] 会静默变纯文本、" +
                "行内公式也会退化（2026-10-27 修过一次）",
            table.contains("MdInlineText(")
        )
    }

    @Test
    fun editorPreviewProvidesTheContext() {
        val c = code("NotesScreen.kt")
        assertTrue(
            "预览里的 MarkdownView 没套 CompositionLocalProvider(LocalWikiLinks …) —— " +
                "上下文为 null，整页链接退回原文",
            c.contains("LocalWikiLinks provides WikiLinkCtx(")
        )
        // 悬空链接必须能落到「创建」：要么内联确认，要么有 dialog
        assertTrue("悬空链接点了没有落点（createLinkedTitle 未接）", c.contains("createLinkedTitle"))
    }

    @Test
    fun chatSideIsNotAffected() {
        // 聊天页/其它调用点不提供上下文，链接应保持原文——所以 WikiChip 自己必须判空返回
        val c = code("WikiLinkRender.kt")
        assertTrue(
            "WikiChip 没判 LocalWikiLinks == null 就直接画 —— 没有上下文的界面会崩或误渲染",
            c.contains("LocalWikiLinks.current ?: return")
        )
    }
}
