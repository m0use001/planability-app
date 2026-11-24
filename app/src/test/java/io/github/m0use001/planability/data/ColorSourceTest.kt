package io.github.m0use001.planability.data

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 配色来源契约（设计文档；2026-10-24 下午改「拼色」）：
 * colorSource=0 时应用内与桌面组件都从 wallpaperSeed（壁纸主色）
 * 取种子、由 theme.wallpaperVariant 选方案，走同一条 colorSchemeFromSeed——三端逐角色一致。
 */
class ColorSourceTest {

    private fun source(rel: String): String {
        // 与 ContextCostTest 同款：从 user.dir 向上找仓库根（worktree 也能命中自己的根）
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null && !File(dir, "app/src/main/java/io/github/m0use001/planability").isDirectory) {
            dir = dir.parentFile
        }
        return File(
            requireNotNull(dir) { "找不到仓库根" },
            "app/src/main/java/io/github/m0use001/planability/$rel"
        ).readText()
    }

    @Test
    fun `ThemeConfig 有 colorSource 且 settings KV 读写覆盖`() {
        val repo = source("data/SettingsRepo.kt")
        assertTrue("ThemeConfig 缺 colorSource 字段", repo.contains("val colorSource: Int"))
        assertTrue("缺 theme.colorSource 解析", repo.contains("all[\"theme.colorSource\"]"))
        assertTrue("缺 theme.colorSource 落库", repo.contains("putRaw(\"theme.colorSource\""))
    }

    @Test
    fun `默认 0（拼色）且 Theme_kt 与 WidgetTheme 走同一内置方案源`() {
        // 拼色终态：colorSource=0 时应用内（Theme.kt）与桌面组件（WidgetTheme）都必须
        // 经 colorSchemeFromFixedStyle 取内置方案（照抄原生，不取壁纸）+ variant 选方案。
        val repo = source("data/SettingsRepo.kt")
        assertTrue("默认必须是 0（拼色）", repo.contains("val colorSource: Int = 0,"))
        assertTrue("拼色方案序号 wallpaperVariant 缺字段", repo.contains("val wallpaperVariant: Int = 0,"))
        assertTrue("缺 theme.wallpaperVariant 落库", repo.contains("putRaw(\"theme.wallpaperVariant\""))
        val theme = source("ui/theme/Theme.kt")
        assertTrue("Theme.kt 缺内置方案生成器", theme.contains("fun colorSchemeFromFixedStyle"))
        assertTrue("内置方案数量必须是 4（与外观页圆数一致）", theme.contains("const val FIXED_STYLE_COUNT = 4"))
        assertTrue("Theme.kt 拼色分支没按 variant 选方案", theme.contains("config.wallpaperVariant"))
        assertTrue(
            "WidgetTheme 没走同一内置方案源",
            source("widget/WidgetTheme.kt").contains("colorSchemeFromFixedStyle(cfg.wallpaperVariant")
        )
    }

    @Test
    fun `种子模式走 colorSchemeFromPreset（8 套预设浅色用图值），壁纸监听按 colorSource 短路`() {
        val wt = source("widget/WidgetTheme.kt")
        assertTrue("WidgetTheme 没按 colorSource 分流", wt.contains("cfg.colorSource == 0"))
        assertTrue("种子模式必须走 colorSchemeFromPreset", wt.contains("colorSchemeFromPreset(cfg.seed, dark)"))
        val app = source("PlanabilityApp.kt")
        assertTrue("缺壁纸变化监听（javap 实查 API=addOnColorsChangedListener）", app.contains("addOnColorsChangedListener"))
        assertTrue("壁纸回调必须按 colorSource 短路（种子模式不折腾）", app.contains("theme.colorSource != 0"))
    }
}
