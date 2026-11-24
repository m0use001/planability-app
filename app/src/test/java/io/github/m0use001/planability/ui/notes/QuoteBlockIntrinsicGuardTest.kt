package io.github.m0use001.planability.ui.notes

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 引用块 intrinsic 测量守卫（2026-11-10 真机 crash-loop）。
 *
 * 现象：一进含「引用块 + 行内公式」的会话（微积分 id=5562）就 FATAL：
 * `IllegalStateException: Asking for intrinsic measurements of SubcomposeLayout layouts...`，
 * 栈里是 `IntrinsicSizeModifier.measure → calculateContentConstraints → minIntrinsicHeight`
 * 一路问到 `NoIntrinsicsMeasurePolicy`。App 会自动恢复上次会话 → **启动即崩循环**
 * （19:58 起 6 次 FATAL，LaunchedEffect scrollToItem 触发整屏 remeasure 时炸）。
 *
 * 根因链：
 * ①引用块（NotesScreen.mdTextLines 的 `>` 分支）用 `Row.height(IntrinsicSize.Min)` +
 *   子级 `Box.fillMaxHeight()` 画左侧竖线——IntrinsicSize 会对**整棵子树**做 intrinsic 测量；
 * ②MdInlineText 含公式时（980f9563 起）是 `BoxWithConstraints`= SubcomposeLayout，
 *   SubcomposeLayout **明确不支持** intrinsic 测量 → 抛异常；
 * ③于是「引用里带 `$公式$`」这一组合一渲染就崩，且因为崩在 measure 阶段，连滚动回底都触发。
 *
 * 守卫钉两条：
 * ①NotesScreen 不得再出现 IntrinsicSize（竖线改 drawBehind 画，不量 intrinsic）；
 * ②MdInlineText 仍是 BoxWithConstraints——这是①的前提；若哪天它不再用 SubcomposeLayout，
 *   这条守卫应被重新评估而不是删掉。
 */
class QuoteBlockIntrinsicGuardTest {

    private fun src(rel: String): String {
        val candidates = listOf(File(rel), File("app/$rel"))
        return candidates.firstOrNull { it.exists() }?.readText()
            ?: error("找不到 $rel（工作目录 ${File(".").absolutePath}）")
    }

    private val notesSrc by lazy {
        src("src/main/java/io/github/m0use001/planability/ui/notes/NotesScreen.kt")
    }
    private val mathSrc by lazy {
        src("src/main/java/io/github/m0use001/planability/ui/notes/MarkdownMath.kt")
    }

    /** 去掉注释行后的代码正文（注释里会写「不要再用 IntrinsicSize」这类说明，不能误判） */
    private val notesCode: String by lazy {
        notesSrc.lineSequence()
            .filterNot { l ->
                val t = l.trimStart()
                t.startsWith("//") || t.startsWith("/*") || t.startsWith("*")
            }
            .joinToString("\n")
    }

    @Test
    fun `引用块不得用 IntrinsicSize 量高`() {
        assertFalse(
            "NotesScreen.kt 代码里又出现 IntrinsicSize：引用块用它画竖线会对子树做 intrinsic 测量，" +
                "而 MdInlineText 含公式时是 BoxWithConstraints（SubcomposeLayout 不支持 intrinsic）→ " +
                "真机「引用+公式」必崩（2026-11-10 启动即崩循环）。竖线请用 drawBehind 画。",
            notesCode.contains("IntrinsicSize")
        )
    }

    @Test
    fun `MdInlineText 的数学分支不得是 SubcomposeLayout（BoxWithConstraints）`() {
        // 2026-11-10 二次定论：MdInlineText 会被任意父布局包（引用块/表格/列表项），
        // 一旦内部是 BoxWithConstraints（SubcomposeLayout），任何 IntrinsicSize 父布局
        // 查内禀尺寸就 IllegalStateException。真正的不变量是「量宽不得用 SubcomposeLayout」，
        // 而守卫①（引用块不量 intrinsic）是同一事故的另一半——两条都要钉住。
        // 正确写法：Modifier.layout 捕获容器 maxWidth 回写状态（透传内禀测量）。
        assertFalse(
            "MdInlineText 数学分支又用回 BoxWithConstraints：任何 IntrinsicSize 父布局" +
                "（引用块/表格）都会 FATAL（2026-11-10 真机启动即崩循环）。" +
                "量容器宽请用 Modifier.layout 捕获 maxWidth。",
            mathSrc.contains("BoxWithConstraints(")
        )
    }
}
