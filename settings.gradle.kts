pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "Planability"
include(":app")
// M1 共享底座：AI/工具内核 + 契约/DTO + 数据模型的单一真源（android+jvm 双 target，
// 禁 UI、禁 android.*/androidx.* import——结构守卫会红）。见 ◆/planability-phone-architecture-roadmap.md
include(":shared")
// 阶段 0（度量）：性能 benchmark 模块。不是产品代码，assembleDebug 不受影响。
include(":macrobenchmark")
 