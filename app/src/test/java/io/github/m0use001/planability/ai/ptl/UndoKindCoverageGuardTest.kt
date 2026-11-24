package io.github.m0use001.planability.ai.ptl

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * P1-7 守卫：持久化撤销的三类写操作**没有 applyUndo 分支**（2026-11-09 审计静态实锤）。
 *
 * 事故形状：`registerUndoPersisted` 登记了 `pending_add` / `note_delete` /
 * `file_library_write` 三种 kind，但 `applyUndo` 的 when 里没有它们 → 走 else → false →
 * `undoLast` 返回 null → 用户看到「没有可撤销的写操作」。进程重启后（内存栈清空）
 * 这三类写操作永远撤不回来，界面却给过「可撤销」的承诺。
 */
class UndoKindCoverageGuardTest {

    private fun source(rel: String): String {
        val f = File(rel)
        assertTrue("源码不在了（cwd 应为 app 模块目录）：$rel", f.isFile)
        return f.readText(Charsets.UTF_8)
    }

    private fun undoBranches(): String {
        val exec = source("src/main/java/io/github/m0use001/planability/ai/ptl/ToolExecutor.kt")
        return exec.substringAfter("private suspend fun applyUndo(").substringBefore("// ---------- \"总是允许\"")
    }

    @Test
    fun `applyUndo 必须覆盖 pending_add`() {
        val seg = undoBranches()
        assertTrue(
            "applyUndo 缺 \"pending_add\" 分支：草稿类写操作跨进程撤不回（registerUndoPersisted 登记了它）",
            seg.contains("\"pending_add\" ->"),
        )
    }

    @Test
    fun `applyUndo 必须覆盖 note_delete`() {
        val seg = undoBranches()
        assertTrue(
            "applyUndo 缺 \"note_delete\" 分支：删笔记跨进程撤不回（承诺过「可撤销」）",
            seg.contains("\"note_delete\" ->"),
        )
    }

    @Test
    fun `applyUndo 必须覆盖 file_library_write`() {
        val seg = undoBranches()
        assertTrue(
            "applyUndo 缺 \"file_library_write\" 分支：文件库写入跨进程撤不回",
            seg.contains("\"file_library_write\" ->"),
        )
    }
}
