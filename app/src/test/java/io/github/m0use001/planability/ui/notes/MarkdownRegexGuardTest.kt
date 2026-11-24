package io.github.m0use001.planability.ui.notes

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 2026-11-17 滚动流畅性 P1 守卫：
 *
 * markdown 渲染链路里多个 Regex 原来在**函数体/每格内联 new**，每次重组都重新编译。
 * 翻回旧消息重组合时一屏几十行 = 几十次 Regex 编译，滚动掉帧。
 *
 * 修复：表格分隔行 `^:?-{2,}:?$`、裸 LaTeX 命令、折行命令前缀、有序列表头
 * 全部提为模块顶层常量；表格分支解析走 `parseMdTable`（模块级 LruCache）。
 *
 * 这条守卫钉住两件事：
 * ① 五个常量必须在源码顶层（不能被删回内联）；
 * ② `bareLatexLine` / `prejoinSplitMathLines` / MdTextLines 表格分支的函数体里
 *    不得再出现裸 `Regex(`（= 回退到内联编译）。
 */
class MarkdownRegexGuardTest {

    private fun source(moduleRel: String): String {
        val cands = listOf(File(moduleRel), File("app", moduleRel))
        val f = cands.firstOrNull { it.isFile }
        assertTrue("源码不在了：$moduleRel（候选：${cands.map { it.path }}）", f != null)
        return f!!.readText(Charsets.UTF_8).replace("\r\n", "\n")
    }

    private val notes: String
        get() = source("src/main/java/io/github/m0use001/planability/ui/notes/NotesScreen.kt")

    // ---------- 顶层常量必须存在 ----------

    @Test
    fun `表格分隔行 Regex 必须是顶层常量`() {
        assertTrue("缺 TABLE_SEP_REGEX 常量", notes.contains("private val TABLE_SEP_REGEX"))
    }

    @Test
    fun `裸 LaTeX 命令 Regex 必须是顶层常量`() {
        assertTrue("缺 BARE_LATEX_CMD_REGEX", notes.contains("private val BARE_LATEX_CMD_REGEX"))
        assertTrue("缺 BARE_LATEX_KNOWN_REGEX", notes.contains("private val BARE_LATEX_KNOWN_REGEX"))
    }

    @Test
    fun `折行命令前缀 Regex 必须是顶层常量`() {
        assertTrue("缺 BROKEN_TAIL_CMD_REGEX", notes.contains("private val BROKEN_TAIL_CMD_REGEX"))
    }

    @Test
    fun `有序列表头 Regex 必须是顶层常量`() {
        assertTrue("缺 ORDERED_LIST_START_REGEX", notes.contains("private val ORDERED_LIST_START_REGEX"))
    }

    // ---------- 函数体不得含内联 Regex( ----------

    /** 截取 `fun name` 到下一个顶层 `fun `/`@Composable` 之间的函数体 */
    private fun funBody(src: String, name: String): String {
        val pat = "fun $name"
        val start = src.indexOf(pat)
        assertTrue("找不到函数 $name", start >= 0)
        // 找下一个顶层 fun 或 @Composable（缩进更浅）
        val rest = src.substring(start + pat.length)
        val nextFun = rest.indexOf("\nfun ")
        val nextComp = rest.indexOf("\n@Composable")
        val endCandidates = listOf(nextFun, nextComp).filter { it >= 0 }
        val end = if (endCandidates.isEmpty()) rest.length else endCandidates.min()
        return rest.substring(0, end)
    }

    @Test
    fun `bareLatexLine 函数体不得含内联 Regex`() {
        val body = funBody(notes, "bareLatexLine")
        assertFalse("bareLatexLine 还在函数体内 new Regex（应走顶层常量）\n$body",
            body.contains("Regex("))
    }

    @Test
    fun `prejoinSplitMathLines 函数体不得含内联 Regex`() {
        val body = funBody(notes, "prejoinSplitMathLines")
        assertFalse("prejoinSplitMathLines 还在函数体内 new Regex（应走顶层常量）\n$body",
            body.contains("Regex("))
    }

    @Test
    fun `MdTextLines 表格分支不得含内联 Regex`() {
        val body = funBody(notes, "MdTextLines")
        assertFalse("MdTextLines 还在函数体内 new Regex（表格分隔行应走 TABLE_SEP_REGEX）\n$body",
            body.contains("Regex("))
    }

    // ---------- 表格解析缓存 ----------

    @Test
    fun `parseMdTable 必须存在并使用 LruCache`() {
        assertTrue("缺 parseMdTable", notes.contains("fun parseMdTable("))
        assertTrue("缺 mdTableCache", notes.contains("mdTableCache"))
        // MdTextLines 表格分支必须调 parseMdTable
        val body = funBody(notes, "MdTextLines")
        assertTrue("MdTextLines 表格分支必须调 parseMdTable(tbl)\n$body", body.contains("parseMdTable(tbl)"))
    }
}
