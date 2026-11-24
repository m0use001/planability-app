package io.github.m0use001.planability.ui.common

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 标签芯片的手势接线（源码级断言）。
 *
 * 为什么要有这个测试：`LabelEditorRow` 曾经用
 * `Box(Modifier.pointerInput { detectTapGestures(onLongPress = …) })` 把 `FilterChip` 包起来想加长按改色，
 * 结果 `detectTapGestures` 把 down/up 消费掉了，芯片自己的 clickable 再也收不到 ——
 * 真机表现是「标签点不动、长按也不弹层」，即整个标签功能全废，而且**单测全绿**（UI 行为没有覆盖）。
 * 这里把两条硬约束钉在源码上：长按探测器不得消费事件、点击必须仍然切换标签。
 */
class LabelChipGestureTest {

    private val src: String by lazy {
        val f = File("src/main/java/io/github/m0use001/planability/ui/common/Ui.kt")
        assertTrue("找不到 Ui.kt（测试的工作目录不对？）", f.exists())
        f.readText()
    }

    private fun labelEditorRow(): String {
        val start = src.indexOf("fun LabelEditorRow(")
        assertTrue("Ui.kt 里没有 LabelEditorRow", start >= 0)
        val end = src.indexOf("\n@Composable", start + 1).let { if (it < 0) src.length else it }
        // 剥掉行注释：本文件里到处都写着「不要用 detectTapGestures」这类警告，
        // 不剥的话断言会被注释自己绊倒（第一版就踩了这个）
        return src.substring(start, end)
            .lines()
            .filterNot { it.trimStart().startsWith("//") }
            .joinToString("\n")
    }

    @Test
    fun `长按探测器不能用 detectTapGestures`() {
        val block = labelEditorRow()
        assertFalse(
            "LabelEditorRow 里又出现了 detectTapGestures —— 它会消费 down/up，" +
                "把 FilterChip 的 clickable 顶死（真机：标签点不动、长按不弹层）",
            block.contains("detectTapGestures")
        )
    }

    @Test
    fun `长按探测必须不消费事件且用 longPressTimeout`() {
        val block = labelEditorRow()
        assertTrue(
            "长按探测要先 awaitFirstDown(requireUnconsumed = false) 才能只观察不消费",
            block.contains("awaitFirstDown(requireUnconsumed = false)")
        )
        assertTrue(
            "长按判定要用 viewConfiguration.longPressTimeoutMillis，别写死毫秒数",
            block.contains("viewConfiguration.longPressTimeoutMillis")
        )
        assertTrue(
            "长按判定要排除拖动：位移超过 touchSlop 就不算长按（否则滑动列表会误弹改色）",
            block.contains("viewConfiguration.touchSlop")
        )
    }

    @Test
    fun `长按后必须屏蔽一次点击`() {
        val block = labelEditorRow()
        assertTrue(
            "芯片自己的 clickable 在长按后的抬手还会补一次 onClick，必须用 suppressClick 互斥",
            block.contains("suppressClick")
        )
        assertTrue(
            "suppressClick 没在对话框关闭时清理 —— 长按后拖走的那一次会白吞掉下一次点击",
            block.contains("suppressClick = null")
        )
    }

    @Test
    fun `点击仍然切换标签`() {
        val block = labelEditorRow()
        assertTrue(
            "FilterChip 的 onClick 必须保留勾选/取消逻辑（onChange(labelsCsv(...))）",
            block.contains("onChange(labelsCsv(")
        )
        assertTrue(
            "标签区的长按提示文案丢了 —— 用户不知道能长按",
            // v19.47：Ui.kt 的文案从「长按标签可改颜色」改成了「长按标签可改名字与颜色」
            // （长按不止能改色，也能改名）。断言要守的是**提示必须存在**，不是钉死某版措辞，
            // 所以跟着改成现文案；Ui.kt 那份仍是唯一来源。
            block.contains("长按标签可改名字与颜色")
        )
    }
}
