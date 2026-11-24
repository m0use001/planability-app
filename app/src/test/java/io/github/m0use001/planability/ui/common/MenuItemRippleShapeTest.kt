package io.github.m0use001.planability.ui.common

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 2026-11-15 用户（真机截图）：「软件内所有可拉起二级菜单的把手，选中特效都是这个突兀的长方形」。
 *
 * 复现证据：按住 ⋮ 菜单里的「发送设置」时，M3 DropdownMenuItem 的点击高亮是**包满整行的
 * 直角矩形**——菜单容器是圆角，里面的高亮却是方块，所以显得突兀。
 *
 * 修法：给每个 DropdownMenuItem 传 `modifier = Modifier.clip(RoundedCornerShape(10.dp))`
 * （M3 会把调用方的 modifier 放在内部 clickable 之前，所以高亮跟着圆角一起裁掉）。
 *
 * 这条守卫钉住「不许回潮」：任何 DropdownMenuItem 调用点都必须带圆角 clip。
 */
class MenuItemRippleShapeTest {

    @Test
    fun 所有菜单项的高亮都必须圆角裁切() {
        val root = File("src/main/java/io/github/m0use001/planability")
        val offenders = mutableListOf<String>()
        var sites = 0
        root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" && "/.mimosa/" !in it.path }
            .forEach { f ->
                val lines = f.readLines()
                lines.forEachIndexed { i, l ->
                    val t = l.trim()
                    if (!t.contains("DropdownMenuItem(") || t.startsWith("//") || t.startsWith("*")) return@forEachIndexed
                    sites++
                    // 调用点后 6 行内必须出现 clip(RoundedCornerShape
                    val window = lines.drop(i).take(7).joinToString("\n")
                    if (!window.contains("clip(RoundedCornerShape")) {
                        offenders += "${f.name}:${i + 1}"
                    }
                }
            }
        assertTrue("没扫到任何 DropdownMenuItem（定位锚点失效？）", sites > 0)
        assertTrue(
            "这些菜单项没有圆角裁切高亮——点击时会出现整行直角方块（用户 2026-11-15 报障）：$offenders",
            offenders.isEmpty()
        )
    }
}
