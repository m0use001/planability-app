package io.github.m0use001.planability.ai.ptl

/**
 * **M2 桥接**：真实现已迁入 `:shared`（jvmShared 源集，org.json 纯逻辑）。
 *
 * 调用方（ToolExecutor / ChatPermMenu / ChatEngine / PermCoverageTest 等）继续用
 * `PermPolicy` 这个名字，零改动。
 */
internal typealias PermPolicy = io.github.m0use001.planability.shared.PermPolicy
