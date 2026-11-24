package io.github.m0use001.planability.ui.notes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 2026-11-19 用户实报：「会话界面滚动还是卡顿」。
 *
 * 这一批钉公式渲染链路上的**主线程热点**（滚动时每个可见气泡每帧都要走）：
 * 1. **拆行决策必须走缓存**：[gatherIfNeeded] 是纯函数，却每条公式每帧被调两遍
 *    （算行高一遍、算占位一遍），每次都是逐字符扫描 + 三次尝试；
 * 2. **扫描不许有 O(n²) 分配**：原来命令扫描用 `latex.drop(i+1)`/`substring(i)`
 *    每个字符拷一次尾巴；
 * 3. **回退文本的 Regex 必须模块级预编译**：回退态在位图未到货前每帧都走。
 */
class MarkdownMathPerfGuardTest {

    private fun source(rel: String): String {
        val cands = listOf(File(rel), File("app", rel))
        val f = cands.firstOrNull { it.isFile }
        assertTrue("源码不在了：$rel", f != null)
        return f!!.readText(Charsets.UTF_8).replace("\r\n", "\n")
    }

    private val md: String
        get() = source("src/main/java/io/github/m0use001/planability/ui/notes/MarkdownMath.kt")

    private fun codeOnly(src: String): String = src.lines().map { line ->
        val i = line.indexOf("//")
        if (line.trimStart().startsWith("//")) "" else if (i >= 0) line.substring(0, i) else line
    }.joinToString("\n")

    @Test
    fun `拆行决策必须走缓存`() {
        val code = codeOnly(md)
        assertTrue("gatherIfNeeded 必须查缓存（GATHER_CACHE）", code.contains("GATHER_CACHE"))
        val idx = code.indexOf("internal fun gatherIfNeeded(")
        assertTrue("找不到 gatherIfNeeded", idx >= 0)
        val fn = code.substring(idx, (idx + 1800).coerceAtMost(code.length))
        assertTrue("gatherIfNeeded 里必须有缓存读取", fn.contains("GATHER_CACHE[key]?.let"))
        assertTrue("gatherIfNeeded 里必须有缓存回填", fn.contains("GATHER_CACHE[key] = out"))
    }

    @Test
    fun `缓存键必须带上两个宽度`() {
        val code = codeOnly(md)
        val idx = code.indexOf("internal fun gatherIfNeeded(")
        val fn = code.substring(idx, (idx + 1800).coerceAtMost(code.length))
        assertTrue("缓存键必须含自然宽", fn.contains("naturalWem"))
        assertTrue("缓存键必须含可用宽", fn.contains("maxWem"))
        assertTrue("缓存键必须走 gatherCacheKey（位模式）", fn.contains("gatherCacheKey("))
    }

    @Test
    fun `命令扫描不许用 drop substring 拷贝尾巴`() {
        val code = codeOnly(md)
        val idx = code.indexOf("internal fun splitAtTopRelations(")
        assertTrue("找不到 splitAtTopRelations", idx >= 0)
        val fn = code.substring(idx, (idx + 4000).coerceAtMost(code.length))
        assertTrue("扫描里还有 latex.drop(…)，O(n²) 分配", !fn.contains("latex.drop("))
        assertTrue("扫描里还有 latex.substring(i) 整段拷贝", !fn.contains("val rest = latex.substring(i)"))
        assertTrue("必须用 letterRunLength 扫命令名", fn.contains("letterRunLength("))
    }

    @Test
    fun `回退文本的 Regex 必须模块级预编译`() {
        val code = codeOnly(md)
        val idx = code.indexOf("internal fun fallbackText(")
        assertTrue("找不到 fallbackText", idx >= 0)
        val fn = code.substring(idx, (idx + 600).coerceAtMost(code.length))
        assertTrue("fallbackText 里还在现场编译 Regex", !fn.contains("Regex("))
        assertTrue("必须有模块级预编译的 Regex 常量", code.contains("FALLBACK_SPACING_RE"))
    }

    // ---------- 行为不变（缓存不得改口径） ----------

    @Test
    fun `缓存不得改拆行口径`() {
        val latex = "a=b+c=d"
        // 宽容器：装得下，不拆
        assertEquals(latex, gatherIfNeeded(latex, 5f, 20f))
        // 窄容器：拆行（顶层等号）
        val narrow = gatherIfNeeded(latex, 30f, 10f)
        assertNotEquals(latex, narrow)
        assertTrue(narrow.startsWith("\\begin{gathered}"))
        // 同样的输入再调一次（命中缓存）结果必须一模一样
        assertEquals(narrow, gatherIfNeeded(latex, 30f, 10f))
        // 宽度不同 → 决策不同（缓存键必须把宽度算进去）：
        // 自然宽 30em、可用宽 25em → 只超 20%（<25% 阈值）→ 不拆
        assertEquals(latex, gatherIfNeeded(latex, 30f, 25f))
    }
}
