package io.github.m0use001.planability.ai.ptl

/**
 * **M2 桥接**：真实现已迁入 `:shared`（jvmShared 源集）。
 *
 * 消费方（ToolExecImpls / LifeTools）继续用 `PendingFilter` 这个名字，零改动。
 */
internal typealias PendingFilter = io.github.m0use001.planability.shared.PendingFilter
