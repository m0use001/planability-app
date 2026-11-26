package io.github.m0use001.planability.ui.common

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 2026-11-15 用户（第三次报同一个坑）：「文字又被全面屏横杠安全区遮住了……这个问题出现好几次了」。
 *
 * 机制（v19.14 就踩过，◆ 有记录）：**`navigationBarsPadding()` 在
 * `ModalBottomSheet` 的内容里是空操作**——M3 已经把窗口 inset 消费掉，padding 返回 0，
 * 于是面板最后一两行文字正好压在全面屏手势横杠下面。唯一可靠写法是
 * `contentWindowInsets = { WindowInsets.navigationBars }`，或滚动内容尾部放
 * `Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))`。
 *
 * 这条守卫把「每个 ModalBottomSheet 调用点都必须二选一」钉死，避免再靠人记。
 */
class SheetBottomInsetTest {

    @Test
    fun 每个底部面板都必须处理导航栏安全区() {
        val root = File("src/main/java/io/github/m0use001/planability")
        val offenders = mutableListOf<String>()
        var sites = 0
        root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" && "/.mimosa/" !in it.path }
            .forEach { f ->
                val lines = f.readLines()
                lines.forEachIndexed { i, l ->
                    val t = l.trim()
                    if (!t.contains("ModalBottomSheet(") || t.startsWith("*") || t.startsWith("//")) return@forEachIndexed
                    sites++
                    // 往后看 120 行（到本面板内容结束）找覆盖方式
                    val window = lines.drop(i).take(120).joinToString("\n")
                    val params = window.substringBefore(") {")
                    val ok = params.contains("contentWindowInsets") ||
                        window.contains("windowInsetsBottomHeight")
                    if (!ok) offenders += "${f.name}:${i + 1}"
                }
            }
        assertTrue("没扫到 ModalBottomSheet（定位锚点失效？）", sites > 0)
        assertTrue(
            "这些底部面板既没有 contentWindowInsets 也没有尾部 windowInsetsBottomHeight Spacer——" +
                "navigationBarsPadding() 在里面是空操作，末行文字会被全面屏横杠盖住：$offenders",
            offenders.isEmpty()
        )
    }

    @Test
    fun 不允许只靠navigationBarsPadding兜底() {
        // 反向确认：如果某个面板只有 navigationBarsPadding 而没有上面两种写法，上面的用例已经会红。
        // 这里额外钉住「不要在新增面板里把 navigationBarsPadding 当唯一手段」的注释约定。
        val root = File("src/main/java/io/github/m0use001/planability")
        var withNavPadOnly = 0
        root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" && "/.mimosa/" !in it.path }
            .forEach { f ->
                val lines = f.readLines()
                lines.forEachIndexed { i, l ->
                    val t = l.trim()
                    if (!t.contains("ModalBottomSheet(") || t.startsWith("*") || t.startsWith("//")) return@forEachIndexed
                    val window = lines.drop(i).take(120).joinToString("\n")
                    val params = window.substringBefore(") {")
                    val hasReal = params.contains("contentWindowInsets") ||
                        window.contains("windowInsetsBottomHeight")
                    if (!hasReal && window.contains("navigationBarsPadding()")) withNavPadOnly++
                }
            }
        assertTrue("仍有面板把空操作的 navigationBarsPadding() 当唯一兜底：$withNavPadOnly 处", withNavPadOnly == 0)
    }
}
