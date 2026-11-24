package io.github.m0use001.planability.ui.plan

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 2026-11-01 真机闪退（crash 缓冲 16:10，用户在时间段选择器点「确定」）防回归钉：
 * 时间轴底端 = 1440 分钟 = 24 点，旧代码 `LocalTime.of(endMin / 60, …)` 在 1440 时
 * 抛 `DateTimeException: Invalid value for HourOfDay (valid values 0 - 23): 24`，
 * 编辑器当场闪退。修复 = 「确定」统一走 minToLocalTime（1440 → 23:59）。
 * 这类崩溃全绿单测测不到——只有真实拖到时间轴最底再点确定才炸，所以钉源码。
 */
class TimeRangePickerClampTest {

    @Test
    fun `确定按钮不裸调 LocalTime_of_必须走1440收口`() {
        val root = generateSequence(File(System.getProperty("user.dir") ?: ".").absoluteFile) { it.parentFile }
            .firstOrNull { File(it, "app/src/main/java/io/github/m0use001/planability").isDirectory }
        assertTrue("找不到仓库根", root != null)
        val f = File(root, "app/src/main/java/io/github/m0use001/planability/ui/edit/TimeRangePicker.kt")
        assertTrue("TimeRangePicker.kt 不在了", f.isFile)
        val code = f.readText(Charsets.UTF_8)
            .lines().filterNot { it.trimStart().startsWith("//") }.joinToString("\n")
        // onPick 里不允许再出现裸的 LocalTime.of(startMin/60 / endMin/60)——那是 24 点闪退的写法
        assertTrue(
            "「确定」又回到了裸 LocalTime.of(分钟/60,…) —— 时间轴拖到底(1440)会再次闪退",
            !code.contains("onPick(LocalTime.of(")
        )
        assertTrue(
            "minToLocalTime 收口函数不在了 —— 1440 分钟没有别的防线",
            code.contains("fun minToLocalTime") && code.contains("LocalTime.of(23, 59)")
        )
    }
}
