package io.github.m0use001.planability.ui.info

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 消息操作行可见性（2026-11-08 用户：「中途突然显示了一下『复制 重新生成』，即使没输出完」）。
 *
 * 根因在多工具轮的接缝：一轮内容结束后引擎先把该行 finalize 成 `done`，再插入新的
 * `streaming` 行。这个空档里 `msg.status == "streaming"` 已经是 false，若只看"这一行"
 * 是否在流式，操作行就会闪一下。
 *
 * 所以判定必须用**整轮**状态（`state.streaming`），不能用单行 status。这条钉住它。
 *
 * 读源码的路径处理：从 user.dir 往上找仓库根，然后**规范化 + 确认落在根内**再读
 * （路径是字面量、无外部输入，但把"读工作区内的固定文件"这件事写死成可校验的形式，
 * 免得以后有人把它改成拼接变量）。
 */
class MessageActionsVisibilityTest {

    private fun repoRoot(): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        var hops = 0
        while (dir != null && hops < 12) {
            if (File(dir, "app/src/main/java/io/github/m0use001/planability").isDirectory) return dir
            dir = dir.parentFile
            hops++
        }
        throw AssertionError("找不到仓库根（user.dir=${System.getProperty("user.dir")}）")
    }

    /** 只允许读仓库根之下的文件：规范化后必须真在根内，且不允许出现 `..` 段 */
    private fun readUnderRoot(root: File, relative: String): String {
        assertTrue("相对路径不许含 ..（当前：$relative）", !relative.split('/', '\\').contains(".."))
        val rootCanon = root.canonicalFile
        val target = File(rootCanon, relative).canonicalFile
        assertTrue(
            "目标文件跑出了仓库根：${target.path}",
            target.path.startsWith(rootCanon.path + File.separator)
        )
        assertTrue("源码文件不在了：${target.path}", target.isFile)
        return target.readText(Charsets.UTF_8)
    }

    @Test
    fun `复制与重新生成只在整轮结束后出现`() {
        val code = readUnderRoot(repoRoot(), "app/src/main/java/io/github/m0use001/planability/ui/info/ChatScreen.kt")
            .lines().filterNot { it.trimStart().startsWith("//") }.joinToString("\n")
        assertTrue(
            "操作行条件丢了 turnRunning——多工具轮接缝处会闪一下「复制/重新生成」",
            code.contains("!streaming && !turnRunning && isLastMsg")
        )
        assertTrue(
            "调用点没把整轮状态传进去（state.streaming）",
            code.contains("turnRunning = state.streaming")
        )
    }
}
