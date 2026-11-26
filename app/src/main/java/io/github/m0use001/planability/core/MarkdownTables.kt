package io.github.m0use001.planability.core

/**
 * **M2 桥接**：真实现已迁入 `:shared`（commonMain）。
 * 保留同签名顶层函数转发，NotesScreen / NotePreview / OfficeWriter(原调用点) 零改动。
 * 搬迁彻底完成后删掉本文件，调用方改 import。
 */
fun splitTableRow(row: String): List<String> =
    io.github.m0use001.planability.shared.splitTableRow(row)
