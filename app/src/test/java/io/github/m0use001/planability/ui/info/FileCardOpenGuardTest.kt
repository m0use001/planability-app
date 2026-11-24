package io.github.m0use001.planability.ui.info

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 2026-11-17 用户实报（截图）：AI 回复里的文件卡
 * 「赶进度计划__1116更新.md / exports/赶进度计划__1116更新.md」点「打开」→
 * 底部弹「文件不存在或路径不支持」。
 *
 * 根因分两层：
 * ① 卡片里的 path 是**模型自由文本**（`ChatComp.parse` 原样取 json.path），
 *    模型常把文件写进「项目文件/分类/」，却在卡里写 exports/xxx.md；
 *    甚至压根没调用导出工具，只是嘴上说"已改成 v3"。
 * ② `ExportStore.resolveExisting` 只在**会话目录**几个候选点精确找文件名，
 *    项目文件目录压根不在候选里 → 精确路径必然 miss。
 *
 * 修：resolveExisting 加「按同名在项目文件目录（含一级子目录）里再搜一遍」的兜底；
 * 打开失败时区分「路径不支持」与「文件不存在（可能没真生成）」两种话术。
 */
class FileCardOpenGuardTest {

    private fun source(rel: String) =
        java.io.File("src/main/java/io/github/m0use001/planability/$rel").readText()

    private val exportStore by lazy { source("ai/office/ExportStore.kt") }
    private val chatScreen by lazy { source("ui/info/ChatScreen.kt") }

    @Test
    fun `exports路径找不到时要按同名在项目文件目录兜底搜`() {
        val body = exportStore.substringAfter("suspend fun resolveExisting(")
            .substringBefore("\n    /**")
        assertTrue(
            "resolveExisting 没有按文件名兜底搜索（AI 把文件写进项目文件/分类/ 时点开必然失败）",
            body.contains("substringAfterLast('/')")
        )
        assertTrue(
            "兜底搜索没有覆盖项目文件目录",
            body.contains("ProjectDirs.filesDir(")
        )
        assertTrue(
            "兜底搜索没有进一级子目录（项目文件/<分类>/xxx.md 这种）",
            body.contains("if (!k.isDirectory) continue")
        )
    }

    @Test
    fun `打开失败要区分路径不支持与文件不存在`() {
        assertTrue(
            "还在用原来那句「文件不存在或路径不支持」（两种失败混成一句，用户以为是 App 坏了）",
            !chatScreen.contains("\"文件不存在或路径不支持\"")
        )
        assertTrue(
            "没有「AI 可能只改了内容，没有真的导出这个文件」这条可诊断提示",
            chatScreen.contains("没有真的导出这个文件")
        )
        assertTrue(
            "没有「路径不支持」的独立话术",
            chatScreen.contains("只支持 attachments/ 与 exports/")
        )
    }
}
