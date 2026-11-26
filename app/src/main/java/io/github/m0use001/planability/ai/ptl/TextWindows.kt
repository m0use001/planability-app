package io.github.m0use001.planability.ai.ptl

/**
 * **M1 桥接**：原 `internal object TextWindows` 的真实现已迁入 `:shared` 模块
 * （`io.github.m0use001.planability.shared.TextWindows`），本文件改为 typealias 指过去。
 *
 * 消费方（FsShellTools / BrowserTools / ToolExecImpls / TextWindowsTest）继续用
 * `io.github.m0use001.planability.ai.ptl.TextWindows`，零改动——typealias 透明转发。
 * M2 逐文件搬迁时，按文件改 import 指向 shared 包名，最后删掉这个 typealias。
 */
internal typealias TextWindows = io.github.m0use001.planability.shared.TextWindows
