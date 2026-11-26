package io.github.m0use001.planability.ai.ptl

/**
 * **M2 桥接**：真实现已迁入 `:shared`（jvmShared 源集，org.json 纯逻辑）。
 *
 * 调用方（DeepSeekCloud）继续用 `DeepSeekSources` 这个名字，零改动。
 * 嵌套类型（Source/Trace/Result）的使用方只有 ai/AiStream.kt 与 ai/ChatEngine.kt 两处，
 * 已改为直接引 shared 的全限定名——因为 typealias 不参与全限定名解析，
 * 而在 object 内放嵌套 typealias 会让 KSP 崩（实测 `UtilKt.declarations` IllegalStateException）。
 */
internal typealias DeepSeekSources = io.github.m0use001.planability.shared.DeepSeekSources
