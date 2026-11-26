package io.github.m0use001.planability.shared

/**
 * 工具风险档（M2 批 3 从 app/ai/ptl/ToolDefs.kt 顶部取出）。
 *
 * 只取了枚举本身——ToolDefs 里 1300+ 行的工具 schema 归 M4「契约单一真源」，
 * 这里迁移的是 PermPolicy 需要的类型依赖。
 */
enum class Risk { AUTO, CONFIRM, DANGER }
