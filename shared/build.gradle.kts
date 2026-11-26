// M1 共享底座：KMP 模块（android + jvm 双 target）。
// 放 AI/工具内核 + 契约/DTO + 数据模型的单一真源——UI 不在 shared、桌面专属能力不进 shared。
// 配方实测见 ◆/planability-kmp-agp93-check.md（agentD 验证 AGP 9.3.0 + KGP 2.2.10 可用）。
//
// M5：Room 2.7.2 KMP——@Database + 49 实体 + 44 DAO + 56 迁移的真源收进 commonMain，
// KSP 在本模块为双 target 生成 Room 实现（Android 走框架 SQLite，JVM 走 sqlite-bundled）。
// 详见 ◆/planability-phone-architecture-roadmap.md M5。
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
}

kotlin {
    androidLibrary {
        namespace = "io.github.m0use001.planability.shared"
        compileSdk = 37
        minSdk = 36
        // 启用 android host 单测，避免 commonTest 源集在 android target 上缺 hostTest 配置
        // 导致 KMP 测试编译在依赖解析期抛空指针（"path is null"）。
        withHostTest {}
    }
    // 显式钉 JVM 目标，避免 jvm target 在无 toolchain 时被推断成 JDK25（"Kotlin does not yet
    // support 25 JDK target, falling back to JVM_24" → 测试编译期依赖解析抛 "path is null" NPE）。
    // 与 app 的 compileOptions(Java 11) 对齐。
    jvm {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
        }
    }

    sourceSets {
        commonMain.get().dependencies {
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
        }
        // jvmShared 中间源集：android 与 jvm 共用的 JVM 级代码。
        // 放「用了 java.*/JVM API 但无 Android 依赖」的工具文件（java.time/java.io/
        // java.net/System.currentTimeMillis…）；commonMain 仍保持纯 KMP（结构守卫不放宽）。
        // M5：数据层 Room 真源也在这里——实体/DAO 里有 java.time（PlanEntities.Countdown 等），
        // commonMain 用不了 java.*，所以 schema 真源落 jvmShared（android+jvm 都编译进来，
        // KSP 按各自 target 的编译单元处理同一批源码并分别生成 Room 实现）。
        val jvmShared by creating {
            dependsOn(commonMain.get())
            dependencies {
                // Room 2.7.2 KMP：实体/DAO 只用 room-runtime 的注解（@Entity/@Dao/@Query…）。
                // room-ktx 非必需（协程支持已在 runtime），且可能与 jvm test classpath 冲突，故不加。
                implementation(libs.androidx.room.runtime)
            }
        }
        androidMain.get().dependsOn(jvmShared)
        jvmMain.get().dependsOn(jvmShared)
        // M2 批3：org.json 纯逻辑（PermPolicy / DeepSeekSources）。
        // **只能加在 jvm 侧**：Android 平台的 org.json 由 android.jar 提供，若在 jvmShared
        // 里引 artifact 会重复打包（且 shadow 平台实现），实测会让 :app:kspDebugKotlin 直接崩。
        // jvmShared 源码本身可以用 org.json——android target 走 android.jar 解析、jvm target 走此 artifact。
        jvmMain.get().dependencies {
            implementation("org.json:json:20260814")
        }
        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
            }
        }
    }
}

// M5 说明：**不在 shared 跑 Room KSP**——实测 KSP 不支持 AGP 9 的
// `com.android.kotlin.multiplatform.library` android compilation
// （KotlinMultiplatformAndroidCompilationImpl cannot be cast to KotlinJvmAndroidCompilation）。
// 因此 adopted 架构：实体/DAO/纯逻辑作为「纯模型」迁入 shared（Room 注解仅编译期需要，
// room-runtime KMP artifact 已提供）；`@Database` + KSP + 56 迁移留 app（标准 Room 多模块：
// app 的 KSP 从 classpath 解析 shared 里的 @Entity/@Dao 并生成 AppDatabase_Impl）。

// 结构守卫：shared/commonMain 不得 import android.* —— 抽错层自动红灯。
// M5 后 commonMain 合法持有 androidx.room.* / androidx.sqlite.*（Room 2.7 KMP 注解的 commonMain 变体），
// 白名单放行这两族；其余 androidx.* 与所有 android.* 仍禁。
// 这条门会红（M1/M5 验收时植入假 import 实测过）。
tasks.register("checkNoAndroidImports") {
    group = "verification"
    description = "shared/commonMain 禁止 import android.* / androidx.*（白名单：androidx.room.* / androidx.sqlite.*）"
    // 路径在配置期解析（layout 可序列化，兼容 configuration cache；doLast 里不能调 Project.file()）
    val commonMainDir = layout.projectDirectory.dir("src/commonMain/kotlin")
    doLast {
        val dir = commonMainDir.asFile
        if (!dir.exists()) return@doLast
        val violations = mutableListOf<String>()
        dir.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .forEach { f ->
                f.useLines { lines ->
                    lines.forEach { line ->
                        val trimmed = line.trim()
                        if (trimmed.startsWith("import android.")) {
                            violations += "${f.relativeTo(dir)}: $trimmed"
                        } else if (trimmed.startsWith("import androidx.") &&
                            !trimmed.startsWith("import androidx.room.") &&
                            !trimmed.startsWith("import androidx.sqlite.")
                        ) {
                            violations += "${f.relativeTo(dir)}: $trimmed"
                        }
                    }
                }
            }
        if (violations.isNotEmpty()) {
            throw GradleException(
                "shared/commonMain 禁止 import android.* / 非白名单 androidx.*（KMP 抽错层红线）。\n" +
                    "白名单：androidx.room.* / androidx.sqlite.*（Room 2.7 KMP 注解）。\n" +
                    "违规行：\n" + violations.joinToString("\n")
            )
        }
    }
}
// 所有测试任务执行前先过结构守卫
tasks.matching { it.name.endsWith("Test") }.configureEach { dependsOn("checkNoAndroidImports") }
