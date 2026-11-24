package io.github.m0use001.planability

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 启动器图标随「软件内配色 × 系统深浅色」切换（源码级断言）。
 *
 * ## 为什么要有
 * 用户 10-18 反馈：**暗色模式下进软件出现普通模式的图标**——切换逻辑只在
 * `Application.onCreate` / `onConfigurationChanged` 里，ColorOS 收掉进程后没人纠正。
 * v19.49 修法：抽成不依赖 Application 存活的 `LauncherNightIcon.apply(context)`，
 * 挂到 BootReceiver（开机/更新）与 AutomationSweep（15 分钟巡检）两条既有路径上。
 *
 * 2026-10-25 图标升级为**配色矩阵**：12 档配色（拼色四套 palette 0..3 + 其他颜色
 * 八个预设 palette 4..11）× 明暗 = 24 个 activity-alias（`.LauncherPal{0..11}{Day,Night}`），
 * 运行期只启用一个。本测试改为钉住矩阵的行为：
 * 启停顺序（先启用新的再禁用旧的）、配色索引映射、清单 alias 数量与默认档。
 *
 * ## 为什么用源码断言
 * 这条约定全靠 PackageManager 的组件启停 + 系统 uiMode，JVM 单测里全都没有，
 * 只能读源码。而它失效时的症状（图标颜色不对）**不崩、不报错、不写日志**，
 * 只有这类断言能拦。
 */
class LauncherNightIconTest {

    private fun src(rel: String): String {
        var dir = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            val f = File(dir, "app/src/main/java/io/github/m0use001/planability/$rel")
            if (f.isFile) return f.readText(Charsets.UTF_8)
            dir = dir.parentFile
        }
        throw AssertionError("找不到 $rel")
    }

    private fun res(rel: String): String {
        var dir = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            val f = File(dir, "app/src/main/$rel")
            if (f.isFile) return f.readText(Charsets.UTF_8)
            dir = dir.parentFile
        }
        throw AssertionError("找不到 $rel")
    }

    @Test
    fun `切换逻辑必须抽成不依赖 Application 的对象`() {
        val app = src("PlanabilityApp.kt")
        assertTrue(
            "LauncherNightIcon 对象不见了——切换逻辑又被塞回 Application 里了，" +
                "那样只有 app 进程活着时才换图标（用户 10-18 反馈的现象）",
            app.contains("object LauncherNightIcon")
        )
        assertTrue(
            "apply() 不在 LauncherNightIcon 里",
            app.contains("fun apply(context: Context")
        )
        // 幂等：配色与明暗都没变时不再写 PackageManager（进程级 lastComponent 缓存）
        assertTrue(
            "apply() 没有幂等保护（lastComponent）——多路径每 15 分钟调一次会不停切组件",
            app.contains("if (current == target)")
        )
    }

    @Test
    fun `进程不活时也必须有人纠正图标`() {
        val boot = src("notify/Notify.kt")
        assertTrue(
            "BootReceiver 没调 LauncherNightIcon.apply——开机后图标可能是上一套",
            boot.contains("LauncherNightIcon.apply(app)")
        )
        val sweep = src("notify/AutomationWorker.kt")
        assertTrue(
            "15 分钟巡检没调 LauncherNightIcon.apply——ColorOS 杀掉进程后，" +
                "系统切暗色也不会纠正图标（这是用户反馈的主路径）",
            sweep.contains("LauncherNightIcon.apply(context)")
        )
    }

    @Test
    fun `启停顺序必须先启用新档再禁用旧档`() {
        val app = src("PlanabilityApp.kt")
        val body = app
            .substringAfter("fun apply(context: Context, theme: ThemeConfig")
            .substringBefore("/** 从 PackageManager 读回")
        assertTrue(
            "apply() 里没有启用目标档的调用",
            body.contains("COMPONENT_ENABLED_STATE_ENABLED")
        )
        assertTrue(
            "apply() 里没有禁用旧档的调用",
            body.contains("COMPONENT_ENABLED_STATE_DISABLED")
        )
        assertTrue(
            "启停顺序写反了——必须先启用新的再禁用旧的，中间不能出现零图标",
            body.indexOf("COMPONENT_ENABLED_STATE_ENABLED") <
                body.indexOf("COMPONENT_ENABLED_STATE_DISABLED")
        )
    }

    @Test
    fun `配色索引映射必须是拼色四档在前自选种子在后`() {
        val app = src("PlanabilityApp.kt")
        assertTrue(
            "拼色档没有收敛到 0..FIXED_STYLE_COUNT-1",
            app.contains("theme.wallpaperVariant.coerceIn(0, FIXED_STYLE_COUNT - 1)")
        )
        assertTrue(
            "自选种子档没有接在拼色四档之后（palette 4..11）",
            app.contains("FIXED_STYLE_COUNT + nearestSeedIndex(theme.seed)")
        )
    }

    @Test
    fun `清单里是 24 档配色 alias 且默认只启用 pal0 日间`() {
        val manifest = res("AndroidManifest.xml")
        val names = Regex("android:name=\"\\.LauncherPal\\d+(?:Day|Night)\"")
            .findAll(manifest).map { it.value }.toList()
        assertEquals("配色矩阵必须是 12 档 × 明暗 = 24 个 alias", 24, names.size)
        assertEquals("alias 命名必须成对成套（Day/Night 各 12）", 12, names.count { it.endsWith("Day\"") })
        val enabledAliases = Regex(
            "<activity-alias[\\s\\S]*?android:enabled=\"true\"[\\s\\S]*?</activity-alias>"
        ).findAll(manifest).count { it.value.contains(".LauncherPal") }
        assertEquals(
            "清单默认必须恰好启用一个 alias（pal0_day）——刚装好、程序还没跑过时也要有图标",
            1,
            enabledAliases
        )
        assertTrue(
            "默认启用的应当是 pal0_day（与默认配色 拼色0 经典绿 一致）",
            Regex("android:name=\"\\.LauncherPal0Day\"[\\s\\S]*?android:enabled=\"true\"")
                .containsMatchIn(manifest)
        )
    }
}
