package io.github.m0use001.planability.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 「其他颜色」8 套预设的**角色表契约**（2026-10-26 用户给 8 张角色图，逐值照抄）。
 *
 * 这层用源文件锚点而不是跑 [colorSchemeFromPreset]：色板生成走 android.graphics.Color，
 * 纯 JVM 单测拿不到真实 HSV 结果；而这里要防的是**表被改错值/改错序**——那才是会
 * 直接反映到界面上、又不会抛异常的错误。深色值是按 M3 音阶生成的，同理锚住档位。
 */
class PresetSchemeTest {

    private fun themeSource(): String {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null && !File(dir, "app/src/main/java/io/github/m0use001/planability").isDirectory) {
            dir = dir.parentFile
        }
        return File(
            requireNotNull(dir) { "找不到仓库根" },
            "app/src/main/java/io/github/m0use001/planability/ui/theme/Theme.kt"
        ).readText()
    }

    /** 8 套 × 7 角色，顺序 = primary / primaryContainer / secondaryContainer /
     *  tertiaryContainer / surfaceContainer / surfaceContainerHigh / inverseSurface */
    private val expected = listOf(
        listOf("6750A4", "E9DDFF", "E9DDFD", "FDDAE1", "EFEDF2", "E9E7ED", "313034"),
        listOf("3757BA", "E0E0FC", "E0E0FE", "FFD8E7", "EDEDF3", "E8E8ED", "303034"),
        listOf("256B42", "ABF2C1", "D1E8D7", "B6ECF4", "EAEFEB", "E4EAE5", "2D312E"),
        listOf("984061", "FED9E4", "FCDAE3", "FFDBC6", "F4ECEE", "EEE6E8", "352F30"),
        listOf("8B5000", "FDDDC1", "FBDDC4", "D2EABA", "F2EDE8", "EDE7E3", "34302C"),
        listOf("0C696E", "77F5FD", "C6E9EB", "D1E4FC", "E7EFF0", "E1EAEA", "2B3232"),
        listOf("AC3327", "FCDBD4", "FEDBD3", "F1E2A9", "F4ECEA", "EEE6E5", "352F2E"),
        listOf("635590", "E7DEFD", "E6DFF6", "FED9E2", "EFEDF3", "E9E7ED", "313034")
    )

    @Test
    fun `8 套预设的角色值与设计图逐值一致`() {
        val src = themeSource()
        val block = Regex("PRESET_LIGHTS: List<PresetLight> = listOf\\((.*?)\\n\\)", RegexOption.DOT_MATCHES_ALL)
            .find(src)?.groupValues?.get(1) ?: error("Theme.kt 里找不到 PRESET_LIGHTS")
        // 先去掉 .toInt()：它自带右括号，会让非贪婪的条目正则提前收尾（只剩 1 个 hex）
        val rows = Regex("PresetLight\\((.*?)\\)", RegexOption.DOT_MATCHES_ALL)
            .findAll(block.replace(".toInt()", ""))
            .map { m -> Regex("0x(?:FF)?([0-9A-Fa-f]{6})").findAll(m.groupValues[1]).map { it.groupValues[1].uppercase() }.toList() }
            .toList()
        assertEquals("预设必须是 8 套", 8, rows.size)
        rows.forEachIndexed { i, roles ->
            assertEquals("第 ${i + 1} 套必须正好 7 个角色", 7, roles.size)
            assertEquals("第 ${i + 1} 套角色值与设计图不一致", expected[i], roles)
        }
    }

    @Test
    fun `浅色直接用图值，深色按 M3 音阶派生`() {
        val src = themeSource()
        // 浅色：7 个角色全部取自 p.*（而不是再走 HSL 推导）
        listOf(
            "primary = primary,",
            "primaryContainer = primaryContainer,",
            "secondaryContainer = secondaryContainer,",
            "tertiaryContainer = tertiaryContainer,",
            "surfaceContainer = surfaceContainer,",
            "surfaceContainerHigh = surfaceContainerHigh,",
            "inverseSurface = inverseSurface,"
        ).forEach { line ->
            assertTrue("浅色分支缺「$line」（图值必须直接用）", src.contains(line))
        }
        // 深色：primary tone-80 / 容器 tone-30 / onContainer tone-90
        assertTrue("深色 primary 未取 tone-80 档", src.contains("0.78f)"))
        assertTrue("深色 primaryContainer 未取 tone-30 档", src.contains("), 0.30f)"))
        assertTrue("深色 onPrimaryContainer 未取 tone-90 档", src.contains("), 0.90f)"))
        assertTrue("深色分支没有从图值取色相", src.contains("darkSchemeFromPreset"))
    }

    @Test
    fun `COLOR_SEEDS 由角色表派生，顺序即启动器图标档位`() {
        val src = themeSource()
        assertTrue(
            "COLOR_SEEDS 必须从 PRESET_LIGHTS 派生（顺序决定 palette 4..11，抄两份会错档）",
            src.contains("val COLOR_SEEDS: List<Color> = PRESET_LIGHTS.map { Color(it.primary) }")
        )
    }
}
