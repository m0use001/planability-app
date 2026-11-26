package io.github.m0use001.planability.ai.ptl

/**
 * **M2 桥接**：真实现已迁入 `:shared`（jvmShared 源集；表格切分改指同模块 splitTableRow）。
 *
 * 消费方（ToolExecImpls / PptxWriter）继续用 `OfficeWriter` 这个名字，零改动。
 */
internal typealias OfficeWriter = io.github.m0use001.planability.shared.OfficeWriter
