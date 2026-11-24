package io.github.m0use001.planability.ui.info

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * v70h（2026-11-21 用户「会话界面由于自身设置，动画改成点击副框，只是主框内容左右移动
 * 变化；同时也要补齐框」）：信息页宽屏主框三态（首页/会话列表/对话）原来**硬切**。
 * 拍板口径：
 *  1. 信息页侧栏（副框）是**常驻工作台树**、没有层级语义——**不套**计划页那套
 *     BoardDrill 整条抽（会把侧栏也拖走）；只推主框内容（AnimatedContent 方向性横移）。
 *  2. 滑动全程裁在主框圆角瓦片内（SplitBoards 各板自带 clip）——缝与侧栏纹丝不动。
 */
class ChatWidePanePushGuardTest {

    private val info: String by lazy {
        val f = listOf(
            File("src/main/java/io/github/m0use001/planability/ui/info/InfoCenterScreen.kt"),
            File("app/src/main/java/io/github/m0use001/planability/ui/info/InfoCenterScreen.kt")
        ).firstOrNull { it.isFile }
        assertTrue("找不到 InfoCenterScreen.kt", f != null)
        f!!.readText(Charsets.UTF_8).replace("\r\n", "\n")
    }

    private fun codeOnly(src: String): String = src.lines().map { line ->
        val i = line.indexOf("//")
        if (line.trimStart().startsWith("//")) "" else if (i >= 0) line.substring(0, i) else line
    }.joinToString("\n")

    @Test
    fun `宽屏主框三态必须走方向性推挤 而不是硬切`() {
        val code = codeOnly(info)
        val wide = code.substringAfter("if (wideInfo) {", "")
        assertTrue("找不到宽屏分支", wide.isNotEmpty())
        val body = wide.substringBefore("\n            } else {", wide)
        assertTrue(
            "宽屏主框没有 AnimatedContent 推挤——点副框换会话仍是硬切（v70h 用户拍板）",
            body.contains("androidx.compose.animation.AnimatedContent(") &&
                body.contains("label = \"chat-pane-push\"")
        )
        // 方向性：进一级从右滑入；返回反向（两支 transitionSpec 都要在）
        assertTrue(
            "推挤缺「进一级」分支（新内容从右滑入）",
            body.contains("val forward = targetState.first.ordinal >= initialState.first.ordinal")
        )
        assertTrue(
            "推挤缺反向分支（返回时旧内容向右让出）",
            body.contains("slideInHorizontally(Motion.standardSpatial()) { -it / 3 }") &&
                body.contains("slideOutHorizontally(Motion.standardSpatial()) { it }")
        )
    }

    @Test
    fun `侧栏树不得被拖进推挤动画（BoardDrill 禁用于信息页）`() {
        val code = codeOnly(info)
        assertFalse(
            "信息页宽屏用了 BoardDrill——侧栏是常驻树没有层级语义，整条抽会把侧栏拖走（v70h 拍板）",
            code.contains("BoardDrill(")
        )
    }
}
