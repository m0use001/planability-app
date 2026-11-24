package io.github.m0use001.planability.macrobenchmark

import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.ExperimentalMacrobenchmarkApi
import androidx.benchmark.macro.ExperimentalMetricApi
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * perf 阶段 0：冷启动 / 热启动基线。
 *
 * ## 为什么是两个 compilationMode 而不是一个
 *
 * 它们回答的是**两个不同的问题**：
 *
 * - [CompilationMode.None]（不做 AOT）—— "代码本身有多重"。排除了 AOT 的干扰，
 *   改代码 / 减依赖带来的变化更容易被放大出来。**优化前后对比用这个。**
 * - [CompilationMode.Ignore] —— "用户实际感受到多慢"。它不去改设备上的编译状态，
 *   所以 baseline profile 的收益只在这个模式里体现。**验收 baseline profile 用这个。**
 *
 * ⚠️ 记一个实测踩到的坑：**benchmark 1.5.0 里没有 `CompilationMode.Default`**。
 * 我一开始照老资料写了 `CompilationMode.Default()`，编译报 `Unresolved reference 'Default'`；
 * 扒开 AAR 里的 classes.jar 看到真实清单是
 * `None / Ignore / Interpreted / Partial / Full`（没有 Default）。
 * 于是"用户实际感受"这个位置改用 [CompilationMode.Ignore]。
 * 这个选择是**按类名与语义推断的，不是官方文档核实的**，第一次跑出数时会顺手确认一次。
 *
 * ## 为什么 iterations = 10
 *
 * 官方样例里 `DEFAULT_ITERATIONS = 1`
 *（performance-samples .../benchmark/util/Utils.kt:20），那是为了让样例跑得快；
 * 用户给的资料明确要求 **iterations ≥ 10**，单次跑出来的数不可信。
 * 阶段 0 的验收也写了：同一 build 跑两次差 < 10% 才算基线可用。
 */
private const val TARGET_PACKAGE = "io.github.m0use001.planability"
private const val ITERATIONS = 10

@LargeTest
@RunWith(AndroidJUnit4::class)
// CompilationMode.Ignore 在 1.5.0 里标了实验性，必须 opt-in（实测报错：
// "This Macrobenchmark API is experimental."）。两个注解一起挂上，反正多余的那个只是未使用警告。
@OptIn(ExperimentalMacrobenchmarkApi::class, ExperimentalMetricApi::class)
class StartupBenchmark {

    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    /** 冷启动，不做 AOT：用于"代码改动前后"的对比。 */
    @Test
    fun coldStartupNoAot() = benchmarkRule.measureRepeated(
        packageName = TARGET_PACKAGE,
        metrics = listOf(StartupTimingMetric()),
        compilationMode = CompilationMode.None(),
        startupMode = StartupMode.COLD,
        iterations = ITERATIONS,
        setupBlock = { pressHome() },
    ) {
        startActivityAndWait()
    }

    /** 冷启动，不动设备上的编译状态：用于"用户实际感受"的对比（baseline profile 只在这体现）。 */
    @Test
    fun coldStartupAsInstalled() = benchmarkRule.measureRepeated(
        packageName = TARGET_PACKAGE,
        metrics = listOf(StartupTimingMetric()),
        compilationMode = CompilationMode.Ignore(),
        startupMode = StartupMode.COLD,
        iterations = ITERATIONS,
        setupBlock = { pressHome() },
    ) {
        startActivityAndWait()
    }

    /**
     * 热启动（进程还在）。
     *
     * 对自用 app 尤其重要：日常用法是从其它 app 切回来，不是每次都冷启。
     */
    @Test
    fun warmStartupNoAot() = benchmarkRule.measureRepeated(
        packageName = TARGET_PACKAGE,
        metrics = listOf(StartupTimingMetric()),
        compilationMode = CompilationMode.None(),
        startupMode = StartupMode.WARM,
        iterations = ITERATIONS,
        setupBlock = { pressHome() },
    ) {
        startActivityAndWait()
    }
}
