package io.github.m0use001.planability.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 2026-11-19 用户两条报障（全局搜索右划抽屉）：
 *
 * 1. **「右划菜单不要自动弹键盘」**：抽屉里搜索框是第一个可聚焦控件，框自己拿到焦点
 *    （ROM/Compose 自动给、或上一次会话残留）就把 IME 顶上来。修法=
 *    抽屉开/关时 `clearFocus()` + `keyboard.hide()`，并且**没有用户点击而获得的焦点一律撤销**
 *    （`onFocusChanged` + `userTappedField` 只在手指真落在框上时置位）。
 * 2. **「点日历有几率右划菜单不收回」**：`onClose` 原来只把 `ShellChrome.searchOpen` 置 false，
 *    靠外壳那条 `LaunchedEffect` 去关抽屉；而右划打开时抽屉 `currentValue` 要等开合动画
 *    结束才变成 Open，此刻标志还是 false —— 在动画没走完时点「日历」，
 *    `onClose` 把 false 又写成 false（状态没变化）→ effect 不触发 → 抽屉不收。
 *    修法=`onClose` **直接关抽屉**（`drawerState.close()`），与标志位解耦。
 */
class SearchDrawerBehaviorGuardTest {

    private val navShell: String by lazy {
        val cands = listOf(
            File("src/main/java/io/github/m0use001/planability/app/NavShell.kt"),
            File("app/src/main/java/io/github/m0use001/planability/app/NavShell.kt")
        )
        val f = cands.firstOrNull { it.isFile }
        assertTrue("找不到 NavShell.kt", f != null)
        f!!.readText(Charsets.UTF_8).replace("\r\n", "\n")
    }

    private fun codeOnly(src: String): String = src.lines().map { line ->
        val i = line.indexOf("//")
        if (line.trimStart().startsWith("//")) "" else if (i >= 0) line.substring(0, i) else line
    }.joinToString("\n")

    @Test
    fun `抽屉开合必须收键盘摘焦点`() {
        val code = codeOnly(navShell)
        val host = code.substringAfter("fun SearchDrawerHost(", "")
        assertTrue("找不到 SearchDrawerHost", host.isNotEmpty())
        val body = host.substringBefore("\n/**", host)
        assertTrue("抽屉壳没有收键盘（右划唤出会弹 IME）", body.contains("keyboard?.hide()"))
        assertTrue("抽屉壳没有 clearFocus", body.contains("clearFocus()"))
    }

    @Test
    fun `没有用户点击而获得的焦点必须撤销`() {
        val code = codeOnly(navShell)
        assertTrue("搜索框没有 onFocusChanged 钩子", code.contains("onFocusChanged {"))
        assertTrue("没有 userTappedField 区分「用户点的」与「自动拿到的」", code.contains("userTappedField"))
        assertTrue(
            "没有撤销自动焦点：autoFocus 时应当 clearFocus + 收键盘",
            Regex("""if\s*\(\s*fieldFocused\s*&&\s*!\s*userTappedField\s*\)""").containsMatchIn(code)
        )
        assertTrue("搜索框没挂 fieldExtras（钩子没接上）", code.contains(".then(fieldExtras)"))
    }

    @Test
    fun `onClose 必须直接关抽屉而不是只改标志位`() {
        val code = codeOnly(navShell)
        val idx = code.indexOf("GlobalSearchContent(")
        assertTrue("找不到 GlobalSearchContent 调用", idx >= 0)
        val call = code.substring(idx, (idx + 500).coerceAtMost(code.length))
        assertTrue(
            "onClose 只改了 ShellChrome.searchOpen，没直接关抽屉——点日历会几率性不收回\n$call",
            call.contains("drawerState.close()")
        )
        assertFalse(
            "onClose 还在把「只改标志位」当成唯一手段",
            Regex("""onClose\s*=\s*\{\s*ShellChrome\.searchOpen\s*=\s*false\s*\}""").containsMatchIn(call)
        )
    }
}
