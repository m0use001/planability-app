package io.github.m0use001.planability.ai.ptl

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 文档/图片工具**路径面**的回归闸（2026-11-03 用户「英语课项目里 AI 读不到文件」）。
 *
 * 真机取证（某次会话）：模型把五种写法全试了一遍，全部「路径不合法或文件不存在」——
 * 因为 `file_read_document` 只认 `attachments/` 与 `exports/`，而用户资料在**项目文件**里。
 * 而 `file_library_read` 对二进制的 docx 返回「[文件名] + 空正文」（库里没有二进制内容），
 * 模型误以为读成功、拿到空字符串。
 *
 * 本闸钉两条：①文档/图片读取必须走统一解析（含绝对路径与项目文件相对路径）；
 * ②文件库读二进制文档时不得静默返回空正文。
 */
class DocPathResolveTest {

    private fun read(rel: String): String {
        val roots = listOf("app/src/main/java/io/github/m0use001/planability/", "src/main/java/io/github/m0use001/planability/")
        roots.forEach { r ->
            val f = java.io.File(r + rel)
            if (f.isFile) return f.readText()
        }
        return java.io.File("app/src/main/java/io/github/m0use001/planability/" + rel).readText()
    }

    @Test
    fun `文档与图片读取走统一解析_不再只认 attachments`() {
        val src = read("ai/ptl/ToolExecImpls.kt")
        assertTrue("resolveReadableFile 应存在", src.contains("suspend fun resolveReadableFile"))
        // 2026-11-11 修正锚点：readDocumentTool/readImageTool 已从 execute() 拆出
        // （ceae050b，execute 曾超 64KB），统一解析断言必须跟着函数体走——
        // 旧断言截"两个 dispatch 行之间"，拆分后永远漏（HEAD 上先红，stash 实测）。
        val doc = src.substringAfter("private suspend fun readDocumentTool")
            .substringBefore("private suspend fun readImageTool")
        assertTrue("file_read_document 必须用统一解析", doc.contains("resolveUnified("))
        val img = src.substringAfter("private suspend fun readImageTool")
            .substringBefore("fun pptSlideNotes")
        assertTrue("file_read_image 必须用统一解析", img.contains("resolveUnified("))
        // 相对路径必须支持「项目文件」根（英语课那次的目录名）
        assertTrue("应支持项目文件根", src.contains("ProjectDirs.FILES"))
        // 绝对路径必须走 FsGuard（安全守卫不绕过）
        assertTrue("绝对路径走 FsGuard", src.contains("FsGuard.resolve(expanded)"))
    }

    @Test
    fun `文件库读二进制文档不得静默返回空正文`() {
        val src = read("ai/ptl/ToolExecImpls.kt")
        val block = src.substringAfter("\"file_library_read\" ->").substringBefore("\"file_library_write\" ->")
        assertTrue("二进制文档应解析真实文件", block.contains("parseDocText("))
        assertTrue("解析不出要给模型可执行的提示", block.contains("file_read_document"))
    }
}
