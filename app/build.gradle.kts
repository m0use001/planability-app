import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

// 高德 key 走 local.properties（已 .gitignore），不进仓库、不进版本历史。
// 没配就编成空串：AmapApi 会在调用处直接返回"未配置 key"的可读错误，而不是崩。
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "io.github.m0use001.planability"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "io.github.m0use001.planability"
        minSdk = 36
        targetSdk = 37
        versionCode = 1
        versionName = "1.0.0"

        ndk {
            // 本应用只跑在真机 arm64：砍掉 x86/x86_64/armeabi-v7a 的 mlkit、vosk 死重（约 58MB）
            //
            // 例外：在 x86_64 模拟器上采 macrobenchmark 基线时传 -PperfAbi=x86_64（AGENTS §4 禁
            // 日用机跑 benchmark，本机没有第二台 arm64 设备）。缺省仍是 arm64-v8a，
            // debug/release 日常产物不变；与 :macrobenchmark 必须传同一个值，否则测试包装不上被测包。
            abiFilters += (findProperty("perfAbi") as String?)?.takeIf { it.isNotBlank() } ?: "arm64-v8a"
        }

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        debug {
            // R8 压缩：debug 包直接分发，未压缩 dex 占 ~90MB
            //
            // **不要为了「小组件永远加载中」去关它。**（2026-10-17 教训）
            // 我一开始只知道「R8 关 -> 小组件正常、R8 开 -> 永远停在
            // initialLayout」，就把这里的 enable 改成了 false —— 确实能修好，
            // 但代价是 APK 83.4MB -> 123.1MB（+39.7MB）。
            //
            // 真正的原因很窄：WorkManager 靠反射造对象，R8 裁掉了
            // `androidx.work.OverwritingInputMerger` 的无参构造，worker 就不跑了；
            // 而 **Glance 的小组件 session 正是跑在 WorkManager 的 SessionWorker 里**，
            // 于是 `provideGlance` 永远不被调用。修法是
            // `app/proguard-rules.pro` 里那几条 androidx.work 的 keep
            // （已经加了，见那里的长注释），**不需要关 R8**。
            //
            // 换句话说：以后看到「小组件不渲染 + 没有任何异常日志」，
            // 先去看 WorkManager 有没有 `InstantiationException`，别急着关 R8。
            optimization {
                enable = true
            }
            proguardFiles("proguard-rules.pro")
            // androidTest APK 也会过 R8：androidx.test 的 Tracer 引用 errorprone 注解
            // （仅编译期存在），缺 -dontwarn 会直接构建失败（2026-10-30 跑位图渲染
            // instrumented 验收时踩到）。单独给测试包一份规则，不动主包。
            testProguardFile("proguard-androidtest.pro")
        }
        release {
            // v19.47（自用包）：R8 与 debug 保持一致，enable = true。
            //
            // 为什么 release 不关 R8（这里原来是 false）：
            //  · 关掉 APK 123.1MB，开着 68.2MB（省 55MB）；
            //  · 更关键的是——我们日常真机验收的 debug 包**本来就是 R8 开的产物**
            //    （图标封面反射、6 个 Glance 组件、桌面快捷方式，全是在这条路径上验过的）。
            //    release 关 R8 等于换一条从没验收过的路径，白白多 55MB。
            //
            // 2026-10-17 的教训在 app/proguard-rules.pro：R8 只裁"直接可达"的东西，
            // 反射入口（androidx.work 的 InputMerger，Glance 的 session 跑在它上面）
            // 要靠 keep 规则兜，**不是靠关 R8**。
            optimization {
                enable = true
            }
            // 签名复用 debug key：自用装机靠 `adb install -r` 覆盖安装，
            // 与现有 app 同签名才能**不卸载、不丢数据**（笔记/自动化/权限全在）。
            //
            // **这个 key 永不能用于商店上传**——本项目自用，刻意如此。
            // DSL：AGP 9.3 的 signingConfig 在 ApplicationVariantDimension 上，
            // ApplicationBuildType 继承它，所以 release{} 里可以直接这么赋值（javap 核实）。
            signingConfig = signingConfigs.getByName("debug")
        }

        // perf 阶段 0：性能度量专用变体（不是产品变体，日常装机仍是 debug）。
        //
        // 为什么必须新建一个变体、不能拿 debug 包测：**可调试包（isDebuggable=true）
        // 跑出来的 benchmark 数据没有意义**。来源: AnkiDroid/build.gradle.kts:236-240
        // —— 真实项目的 benchmark 变体就是 `initWith(release)` + `isDebuggable = false`。
        //
        // 继承 release 的三个理由：① 与线上同一条 R8 路径；② 我们 release 本来就复用
        // debug key 签名，装上去能覆盖安装、不丢数据；③ 压缩/优化规则只维护一处。
        create("benchmark") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            isDebuggable = false
            matchingFallbacks += listOf("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        // BuildConfig.AMAP_KEY：高德 Web服务 key（POI 搜索/逆地理编码）
        buildConfig = true
    }
    buildTypes.all {
        buildConfigField(
            "String", "AMAP_KEY",
            "\"${localProps.getProperty("amap_key", "")}\""
        )
        // v19.51：DeepSeek / NVIDIA NIM 两个 AI 供应商的 key。
        // 与 amap_key 同一套路：只在 local.properties（已 .gitignore），不进仓库、不进版本历史。
        // 没配就空串 → 播种时跳过该供应商（不报错、不留半条记录）。
        buildConfigField(
            "String", "DEEPSEEK_KEY",
            "\"${localProps.getProperty("deepseek_key", "")}\""
        )
        buildConfigField(
            "String", "NVIDIA_KEY",
            "\"${localProps.getProperty("nvidia_key", "")}\""
        )
        // 2026-11-02：AMD Radeon Cloud（MiMo-V2.6-Flash 等）——Routability 的主通道之一，
        // 同款机制：key 只在 local.properties（已 .gitignore），没配就跳过该供应商。
        buildConfigField(
            "String", "AMD_KEY",
            "\"${localProps.getProperty("amd_key", "")}\""
        )
        // 2026-11-02：Routability 虚拟供应商的其余上游 key（完整复刻网关 routes，
        // 只收 1M 上下文模型；codebuddy/zen 按用户要求不收录，它们需要伪装官方客户端指纹）。
        // 全部只在 local.properties，空串=该账号跳过，池里少一个候选而已。
        buildConfigField("String", "NVIDIA_KEY2", "\"${localProps.getProperty("nvidia_key2", "")}\"")
        buildConfigField("String", "MODELSCOPE_KEY", "\"${localProps.getProperty("modelscope_key", "")}\"")
        buildConfigField("String", "MODELSCOPE_KEY2", "\"${localProps.getProperty("modelscope_key2", "")}\"")
        buildConfigField("String", "OPENROUTER_KEY", "\"${localProps.getProperty("openrouter_key", "")}\"")
        buildConfigField("String", "CLOUDFLARE_KEY", "\"${localProps.getProperty("cloudflare_key", "")}\"")
        buildConfigField("String", "HUAWEI_KEY", "\"${localProps.getProperty("huawei_key", "")}\"")
        buildConfigField("String", "HUAWEI_KEY2", "\"${localProps.getProperty("huawei_key2", "")}\"")
        // v14.2：地点检索多源合并。其余三个源全走同一个机制——key 只在 local.properties，
        // 没配就留空串，源码里对应的源**自动不参与**（不报错、不空转），见 core/PlaceSearch.kt
        buildConfigField("String", "TENCENT_KEY", "\"${localProps.getProperty("tencent_key", "")}\"")
        buildConfigField("String", "BAIDU_KEY", "\"${localProps.getProperty("baidu_key", "")}\"")
        buildConfigField("String", "TIANDITU_KEY", "\"${localProps.getProperty("tianditu_key", "")}\"")
    }
    packaging {
        jniLibs {
            // Syncthing Go 鍐呮牳涓嶆槸鏍囧噯 NDK 浜х墿锛岃烦杩?strip锛?            // 闇€瑕佽В鍘嬪埌 nativeLibraryDir 浠ヤ究 exec 鍚姩鍐呮牳
            useLegacyPackaging = true
            keepDebugSymbols += "**/libsyncthingnative.so"
            // 2026-11-10 优化体检 P0-2（真机 /proc/<pid>/maps 实证）：GGUF-NPU 腿
            // （libggml-hexagon + libcdsprpc 直连 Hexagon RPC）全程零 libQnn*.so 映射，
            // 下列 QNN 调试/工具件运行时无用，纯占约 20MB：
            //  · *ProfilingReader（Optrace/Json/Chrometrace/Htp）= 离线分析 profiling 的工具件
            //  · QnnNetRunDirect*（Skel/Stub/Extensions）= qnn-netrun CLI 的设备端配套
            //  · libQnnHta* + libhta_hexagon_runtime_* = HTA（8 Gen1 时代 Hexagon 架构），SM8850 不用
            // 保留：QNN 运行时核心（Htp/HtpPrepare/HtpV79+V81/Skel/System/Ir/Dlc/Saver/
            // Calculator 系列）与 ggml/llama 全家——QAIRT 腿（geniex_plugin_qairt）目前在
            // 真机加载即崩（另案），修好前核心一律不剔，等 maps 复核后再收。
            excludes += setOf(
                "lib/arm64-v8a/libQnnHtpOptraceProfilingReader.so",
                "lib/arm64-v8a/libQnnJsonProfilingReader.so",
                "lib/arm64-v8a/libQnnChrometraceProfilingReader.so",
                "lib/arm64-v8a/libQnnHtpProfilingReader.so",
                "lib/arm64-v8a/libQnnNetRunDirectV81Skel.so",
                "lib/arm64-v8a/libQnnNetRunDirectV79Skel.so",
                "lib/arm64-v8a/libQnnNetRunDirectV81Stub.so",
                "lib/arm64-v8a/libQnnNetRunDirectV79Stub.so",
                "lib/arm64-v8a/libQnnHtpNetRunExtensions.so",
                "lib/arm64-v8a/libQnnHtaNetRunExtensions.so",
                "lib/arm64-v8a/libQnnHta.so",
                "lib/arm64-v8a/libhta_hexagon_runtime_snpe.so",
                "lib/arm64-v8a/libhta_hexagon_runtime_qnn.so",
                "lib/arm64-v8a/libCalculator_skel.so",
            )
        }
        resources {
            // POI（含 xmlbeans/commons）带一堆许可证/签名元数据，打进 APK 会和别的库撞名；
            // 这些文件运行期无用，按需要剔除。
            excludes += setOf(
                "META-INF/{AL2.0,LGPL2.1}",
                "META-INF/LICENSE*", "META-INF/NOTICE*", "META-INF/DEPENDENCIES",
                "META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA",
                "META-INF/versions/9/module-info.class"
            )
        }
    }
}

// ===== 日用机保护（2026-10-30 两次事故：connected 测试跑完连主包一起卸载、数据清空）=====
// Gradle 的 connected*AndroidTest 无论成败都会卸载它装上的 APK；日用真机绝不能
// 作为 connected 测试目标。规则（AGENTS §4.2）落到构建脚本，防"哪个会话
// 顺手跑一下"的误操作：任务开始前查目标序列号，命中日用机直接失败。
// 第一道：配置期就拦（配置必然发生在任何装机动作之前，不依赖任务动作顺序）。
// ① env 显式点名日用机 → 拒；② 没点名但日用机在线 → 拒（AGP 会对所有在线设备跑）。
val wantsConnectedTest = gradle.startParameter.taskNames.any {
    val n = it.lowercase()
    n.contains("connected") && n.contains("androidtest")
}
if (wantsConnectedTest) {
    val daily = System.getenv("PLANABILITY_DAILY_SERIAL") ?: ""
    val envSerial = System.getenv("ANDROID_SERIAL")?.takeIf { it.isNotBlank() }
    val dailyOnline = envSerial == null && runCatching {
        val proc = ProcessBuilder("adb", "devices").redirectErrorStream(true).start()
        val out = proc.inputStream.bufferedReader().readLines()
        proc.waitFor()
        out.any { line -> line.split("\t").firstOrNull()?.trim() == daily }
    }.getOrDefault(false)
    if (daily.isNotBlank() && (envSerial == daily || dailyOnline)) {
        throw GradleException(
            "拒绝在日用机 $daily 上跑 connected 测试：Gradle 跑完会把主包卸载、数据清空" +
                "（2026-10-30 已发生两次，靠守护快照恢复）。请对模拟器跑：" +
                "ANDROID_SERIAL=emulator-5554 ./gradlew …；确需真机只能手动 " +
                "adb install + am instrument（AGENTS §4.2）。"
        )
    }
}

tasks.configureEach {
    if (name.startsWith("connected") && name.endsWith("AndroidTest")) {
        doFirst {
            val daily = System.getenv("PLANABILITY_DAILY_SERIAL") ?: ""
            val envSerial = System.getenv("ANDROID_SERIAL")?.takeIf { it.isNotBlank() }
            val onlineSerials = runCatching {
                val proc = ProcessBuilder("adb", "devices").redirectErrorStream(true).start()
                val out = proc.inputStream.bufferedReader().readLines()
                proc.waitFor()
                out.mapNotNull { line ->
                    val parts = line.split("\t")
                    if (parts.size >= 2 && parts[1].trim() == "device") parts[0].trim() else null
                }
            }.getOrDefault(emptyList())
            // 显式指定优先；没指定时 AGP 会对**所有在线设备**跑（多设备同时在线时
            // 日用机也会被选中），所以"日用机在线且没显式指定别的目标"一律拒绝
            val dangerous = daily.isNotBlank() &&
                (envSerial == daily || (envSerial == null && onlineSerials.contains(daily)))
            if (dangerous) {
                throw GradleException(
                    "拒绝在日用机 $daily 上跑 connected 测试：Gradle 跑完会把主包卸载、数据清空" +
                        "（2026-10-30 已发生两次，靠守护快照恢复）。请对模拟器跑：" +
                        "ANDROID_SERIAL=emulator-5554 ./gradlew …；确需真机只能手动 " +
                        "adb install + am instrument（AGENTS §4.2）。"
                )
            }
        }
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    // Service 里要放 ComposeView：LifecycleService 负责 SavedStateRegistry / Lifecycle 的
    // 正确时序（自己 new 的话 performRestore 的 CREATED 检查怎么都绕不过去，见下）
    implementation("androidx.lifecycle:lifecycle-service:2.9.4")
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    implementation(libs.androidx.glance.appwidget)
    // v43：常驻自主循环用 WorkManager 做持久化调度（周期任务在杀进程后由系统重排）。
    // 此前只有 Glance 的传递依赖，compile 不可见；版本钉在缓存里的 2.7.1，与传递版本一致，不升级。
    // 用 ktx 包：OneTime/PeriodicWorkRequestBuilder 这些 Kotlin 扩展在 ktx 里（runtime 包里没有）。
    implementation("androidx.work:work-runtime-ktx:2.7.1")
    implementation(libs.androidx.biometric)
    implementation(libs.okhttp)
    implementation(libs.jlatexmath.android)
    implementation(libs.vosk.android)
    implementation(libs.mlkit.text.chinese)
    implementation("org.osmdroid:osmdroid-android:6.1.20")
    // v51 知识库语义检索：端侧向量化（bge-small-zh-v1.5 int8，按需下载，不进 APK）。
    // 只跑 CPU：24M 参数模型单条约 5-20ms，没必要碰 NPU（也避免和 GenieX 抢 QNN）。
    // 2026-11-05：1.20.0 的 arm64 .so 是 4KB 页对齐（p_align=4096）——Android 15+
    // 16KB 页设备上会被拒载。1.22.0 起官方改成 16KB 对齐（实测 AAR 内 p_align=16384）。
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.22.0")
    // 端侧小模型：高通官方 GenieX（NPU/HTP 推理运行时，方案见项目设计文档）。
    // Maven Central；arm64-v8a 预编译 so 免 NDK/CMake。
    // 0.3.1 实测不可用：其 JNI 桥调 HubSource.getValue() 而 AAR 无此方法 → NoSuchMethodError → 进程 SIGABRT（2026-10-22 真机）。
    // 0.8.0 曾是 central 最新且与官方 main 分支源码一致。
    // 0.10.0（2026-11-03 升级，端侧模型调研）：ggml-htp 从 v73-v81 扩到 v73-**v85**，
    // 新增 listChipsets/detectChipset/listHubModels/resolveAlias（bean 签名与 0.8.0 一致，
    // ModelPullInput/LlmCreateInput/ModelConfig 构造未变 → 调用点零改动）。
    implementation("com.qualcomm.qti:geniex-android:0.10.0")
    // 2026-11-09 文档解析叶子（第一刀，成熟库组合）：
    //  · POI：docx（XWPF，按 run 拼、不 trim → 不再吞空格）、**doc（HWPF，老二进制，此前完全不支持）**、
    //    xlsx（WorkbookFactory）；poi-ooxml 供 docx/xlsx，poi-scratchpad 供老 doc。
    //  · PDFBox-Android：PDF 文字层（此前只有“翻页预览”没有文字）。
    //    无文字层时由 OcrHelper 兜底（渲染成图再识别）。
    //  只用在解析叶子，不引入任何 agent 框架。
    implementation(libs.poi)
    implementation(libs.poi.ooxml)
    implementation(libs.poi.scratchpad)
    implementation(libs.pdfbox.android)
    ksp(libs.androidx.room.compiler)
    testImplementation(libs.junit)
    // 单测里的 org.json 是 android.jar 的空壳（"Method put in JSONObject not mocked"）。
    // 用真实实现替换，才能断言 reasoning_content 这类序列化行为。只影响单测 classpath。
    testImplementation(libs.org.json)
    androidTestImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
