package io.github.m0use001.planability.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 根背景必须与左侧 NavigationRail **同一个色**（2026-11-20 用户指令：
 * 「背景就不要渐变了，改成跟左边菜单栏同一个颜色」）。
 *
 * 背景事实：暗色原先铺的是 Theme.DarkAppBackground —— ColorOS 同款垂直渐变
 * （#2B2C31→#151518），而左侧 Rail 用的是 `colorScheme.surface`（暗色明度恒 0.115，
 * 中性方案实测 #1D1D1D）。两者不是同一个色 → 宽屏（平板 / 折叠屏内屏 / 大屏窗口）
 * 上 Rail 与内容区之间出现一道明显的色阶接缝。
 *
 * 本测试把三条链路钉住，防止将来又被改回渐变或改回 `background`：
 * 1. Theme 层：不再有 DarkAppBackground 这个渐变画刷；
 * 2. 根层（MainActivity）与开屏浮层（LaunchAnim）：一律 SolidColor(surface)；
 * 3. 冷启动窗口底色：实色，且与根层取同一个色值档（不许再出现 gradient 标签）。
 */
class AppBackgroundGuardTest {

    private fun repoRoot(): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null && !File(dir, "app/src/main/java/io/github/m0use001/planability").isDirectory) {
            dir = dir.parentFile
        }
        return requireNotNull(dir) { "找不到仓库根" }
    }

    private fun read(rel: String): String = File(repoRoot(), rel).readText(Charsets.UTF_8)

    @Test
    fun 主题层不许再有渐变画刷() {
        val theme = read("app/src/main/java/io/github/m0use001/planability/ui/theme/Theme.kt")
        assertFalse(
            "Theme 又出现了 DarkAppBackground 渐变画刷（根背景必须与 Rail 同色）",
            theme.contains("val DarkAppBackground")
        )
        assertFalse(
            "Theme 里不许再为根背景定义 verticalGradient",
            theme.contains("Brush.verticalGradient")
        )
        // 只查**代码里真正构造的色值**，注释里留着历史说明（#2B2C31 是什么）是允许的
        assertFalse(
            "Theme 里又在代码里构造了旧的渐变色 #2B2C31",
            theme.contains("Color(0xFF2B2C31)")
        )
        assertFalse(
            "Theme 里又在代码里构造了旧的渐变色 #151518",
            theme.contains("Color(0xFF151518)")
        )
    }

    @Test
    fun 全仓不许再引用渐变画刷() {
        val offenders = mutableListOf<String>()
        val root = File(repoRoot(), "app/src/main/java/io/github/m0use001/planability")
        root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .forEach { f ->
                val t = f.readText(Charsets.UTF_8)
                if (t.contains("DarkAppBackground")) {
                    offenders += f.relativeTo(repoRoot()).path
                }
            }
        assertTrue("这些文件还在引用 DarkAppBackground：$offenders", offenders.isEmpty())
    }

    @Test
    fun 根背景必须铺surface实色() {
        val main = read("app/src/main/java/io/github/m0use001/planability/MainActivity.kt")
        assertTrue(
            "根背景没有铺 colorScheme.surface（宽屏会与左侧 Rail 出现色阶接缝）",
            main.contains("SolidColor(MaterialTheme.colorScheme.surface)")
        )
        assertFalse(
            "根背景不许退回 colorScheme.background（与 Rail 的 surface 不是同一个色）",
            main.contains("SolidColor(MaterialTheme.colorScheme.background)")
        )
    }

    @Test
    fun 开屏浮层必须与根背景同色() {
        val anim = read("app/src/main/java/io/github/m0use001/planability/ui/common/LaunchAnim.kt")
        assertTrue(
            "开屏浮层没有铺 colorScheme.surface（冷启动会出现渐变→实色的跳变）",
            anim.contains("SolidColor(MaterialTheme.colorScheme.surface)")
        )
        assertFalse(
            "开屏浮层不许再用 colorScheme.background",
            anim.contains("SolidColor(MaterialTheme.colorScheme.background)")
        )
    }

    @Test
    fun 色板里background必须与surface同值() {
        val theme = read("app/src/main/java/io/github/m0use001/planability/ui/theme/Theme.kt")
        // 页面级填充用 colorScheme.background、左侧 Rail 用 colorScheme.surface：
        // 两者不同值时，宽屏上两栏之间仍有一道色阶接缝。
        // 逐个分支核对：每个 background= 后面必须跟着与 surface= 相同的明度档。
        val bg = Regex("background = C\\.hsl\\([^\\n]*?, ([0-9.]+)f\\)")
            .findAll(theme).map { it.groupValues[1] }.toList()
        val sf = Regex("surface = C\\.hsl\\([^\\n]*?, ([0-9.]+)f\\)")
            .findAll(theme).map { it.groupValues[1] }.toList()
        assertTrue("没找到 background/surface 的色板定义", bg.isNotEmpty() && sf.isNotEmpty())
        assertEquals("background 与 surface 的明度档必须一一相同：bg=$bg surface=$sf", sf, bg)
    }

    @Test
    fun 冷启动窗口底色必须是实色() {
        val drawable = read("app/src/main/res/drawable/launch_bg_night.xml")
        assertFalse(
            "夜间 windowBackground 又出现 gradient（首帧会闪一条渐变带）",
            drawable.contains("<gradient")
        )
        assertTrue(
            "夜间 windowBackground 没有实色",
            drawable.contains("<solid")
        )
        val colors = read("app/src/main/res/values/colors_splash.xml")
        assertTrue(
            "splash_bg_night 不见了",
            colors.contains("splash_bg_night")
        )
        // 只查 <color> 标签里的实际取值，注释里的历史说明不算
        assertFalse(
            "splash 颜色里还留着旧的渐变顶色 #2B2C31",
            Regex("<color[^>]*>\\s*#(?:FF)?2B2C31\\s*</color>").containsMatchIn(colors)
        )
        assertFalse(
            "splash 颜色里还留着旧的渐变底色 #151518",
            Regex("<color[^>]*>\\s*#(?:FF)?151518\\s*</color>").containsMatchIn(colors)
        )
    }
}
