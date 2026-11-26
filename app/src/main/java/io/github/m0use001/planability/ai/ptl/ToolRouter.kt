package io.github.m0use001.planability.ai.ptl

/**
 * **M4 契约真源桥接**：真实现已迁入 `:shared`（jvmShared，与 ToolRegistry 同包）。
 * 消费方（ChatEngine / ToolRouterTest / ProbeForgetRouteTest / FileVisionFallbackGuardTest）
 * 继续用 `ToolRouter` 这个名字，零改动。
 */
internal typealias ToolRouter = io.github.m0use001.planability.shared.ToolRouter
