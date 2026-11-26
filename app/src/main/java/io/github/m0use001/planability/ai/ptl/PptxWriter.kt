package io.github.m0use001.planability.ai.ptl

/**
 * **M2 桥接**：真实现已迁入 `:shared`（jvmShared 源集，java.io/net/zip 纯逻辑）。
 *
 * 消费方（ToolExecImpls / PptxEditor / OfficeText / 单测）继续用 `PptxWriter` 这个名字，零改动。
 */
internal typealias PptxWriter = io.github.m0use001.planability.shared.PptxWriter
