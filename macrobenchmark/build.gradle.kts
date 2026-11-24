// perf 阶段 0：性能度量模块（不是产品模块，assembleDebug 不受影响）。
//
// 形状来自官方样例 perf-infra/performance-samples/MacrobenchmarkSample：
//   · com.android.test 模块 + targetProjectPath = ":app"
//   · experimentalProperties["android.experimental.self-instrumenting"] = true
//     —— benchmark 跑在独立进程里
//   · 依赖 benchmark-macro-junit4 + uiautomator
// 来源: MacrobenchmarkSample/macrobenchmark/build.gradle.kts:40,56-58,73-83

plugins {
    // 不能写版本：AGP 已由 :app 带上 classpath，再声明版本会报
    // "plugin is already on the classpath with an unknown version"（实测踩到）。
    id("com.android.test")
}

// 被测 ABI。缺省 arm64-v8a（真机）；在 x86_64 模拟器上采基线时传 -PperfAbi=x86_64。
// 与 :app 的 defaultConfig 必须传同一个值，否则测试包装不上被测包（abi 不匹配直接安装失败）。
val perfAbi = (project.findProperty("perfAbi") as String?)?.takeIf { it.isNotBlank() } ?: "arm64-v8a"

android {
    namespace = "io.github.m0use001.planability.macrobenchmark"
    compileSdk {
        version = release(37)
    }

    // 这个模块只有 benchmark 一个变体：它必须和被测 app 的 `benchmark` 变体配对，
    // 所以两边都叫 benchmark，且 matchingFallbacks 指到 release。
    buildTypes {
        create("benchmark") {
            // 测试 APK 自己可调试没关系；**被测包**才必须不可调试
            //（app/build.gradle.kts 里那个 create("benchmark") 才是 isDebuggable = false）
            isDebuggable = true
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
        }
    }

    defaultConfig {
        // Baseline profile 与 macrobenchmark 要求 API 28+（与本项目 minSdk = 36 无冲突，
        // 这里显式写死 28 是因为它是这个模块自己的下限，不跟着 app 走）
        minSdk = 28
        targetSdk = 37
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            // 被测 ABI 由上面的 -PperfAbi 决定（缺省 arm64-v8a 真机；模拟器度量传 x86_64）。
            //
            // 为什么这里必须显式写：不写的话测试 APK 会打**4 个 ABI** —— 实测 48.6MB 里
            // libtracing_perfetto.so ×4 占 13.8MB，且第一次 connectedBenchmarkAndroidTest
            // 直接**安装超时失败**（ddmlib ShellCommandUnresponsiveException @ installCommit）。
            // 第一次跑就把这个坑踩了，记在 ref-perf-study.md §9.2。
            // （2026-10-21 再踩一次的变体：这里曾写死 arm64-v8a，perfAbi 属性加了却没接上，
            // 测试 APK 在 x86_64 模拟器上 INSTALL_FAILED_NO_MATCHING_ABIS —— 属性必须真的用起来。）
            abiFilters += perfAbi
        }
    }

    packaging {
        resources {
            // 试过在这里 exclude assets（`assets/trace_processor_shell_x86` 等），**实测无效**：
            // 那些文件是 AAR 带进来的 asset，不走 resources 这条过滤器。改用下面 androidResources
            // 的 aapt2 忽略模式（`!` 前缀 = 排除），实测有效。
            excludes += listOf("/META-INF/LICENSE-junit.txt")
        }
    }

    // androidx.benchmark 把 trace 处理工具按架构命名塞进 assets，同样不必带 4 份。
    // AAR 里实际名字（unzip -l 核实）：trace_processor_shell_{aarch64,arm,x86,x86_64} / tracebox_*。
    // 按被测 ABI 只留对应那份 —— 这一项是**必须**的：不排除的话测试 APK 带 4 份（~25MB），
    // 第一次 connectedBenchmarkAndroidTest 因测试 APK 太大安装超时（§9.2）。
    androidResources {
        ignoreAssetsPatterns += if (perfAbi == "arm64-v8a") listOf(
            "!trace_processor_shell_arm",
            "!trace_processor_shell_x86",
            "!trace_processor_shell_x86_64",
            "!tracebox_arm",
            "!tracebox_x86",
            "!tracebox_x86_64",
        ) else listOf(
            "!trace_processor_shell_aarch64",
            "!trace_processor_shell_arm",
            "!trace_processor_shell_x86",
            "!tracebox_aarch64",
            "!tracebox_arm",
            "!tracebox_x86",
        )
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    // 被测模块。AGP 会在跑 connectedBenchmarkAndroidTest 时自动装 app 的 benchmark 变体。
    targetProjectPath = ":app"

    // benchmark 与 app 分属两个进程，测试进程要能自己插桩
    experimentalProperties["android.experimental.self-instrumenting"] = true
}

dependencies {
    implementation(libs.androidx.benchmark.macro.junit4)
    implementation(libs.androidx.junit)
    implementation(libs.androidx.test.uiautomator)
    // benchmark 把 runner 当 runtime 依赖，annotation（androidx.test.filters.LargeTest）编不过，
    // 所以要显式提到 compile 作用域。版本与 benchmark 的 POM 要求一致（1.5.2），不升级别人。
    implementation(libs.androidx.test.runner)
}
