package io.github.m0use001.planability.ai.ptl

/**
 * **M2 桥接**：真实现已迁入 `:shared`（jvmShared 源集）。
 *
 * 消费方（DeepPlanner / ToolExecImpls / ChatScreen）继续用
 * `io.github.m0use001.planability.ai.ptl.SubAgentRuns` 等名字，零改动——typealias 透明转发。
 * 搬迁彻底完成后删掉这个桥接文件，改 import 指向 shared 包名。
 */
internal typealias SubAgentRuns = io.github.m0use001.planability.shared.SubAgentRuns
internal typealias SubAgentRun = io.github.m0use001.planability.shared.SubAgentRun
internal typealias SubAgentStep = io.github.m0use001.planability.shared.SubAgentStep
