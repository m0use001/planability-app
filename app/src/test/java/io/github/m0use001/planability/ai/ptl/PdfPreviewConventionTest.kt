package io.github.m0use001.planability.ai.ptl

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 跨组件约定：**生成侧写 PDF 的文件名** 必须和 **预览侧找 PDF 的文件名** 是同一套。
 *
 * 这条约定目前活在两个文件里：
 * - 写：`ToolExecImpls.pdfPreviewNote()` → `<nameWithoutExtension>.pdf`
 * - 读：`ui/info/FileLibraryPreview.OfficeFilePreview()` → `listOf("${f.nameWithoutExtension}.pdf", "preview.pdf")`
 *
 * 两边各自改都"对"，只有放一起比才看得出分叉 —— 分叉的后果是**静默降级**：
 * 预览页找不到 PDF 就退回自绘快照，不报错、不崩溃，用户只是再也看不到真·版式，
 * 而代码和测试全绿。docx/xlsx 的"翻 PDF"这条链是 2026-10-11 才接上的，
 * 当时单测只覆盖了"能转出来"，没有任何测试跨越"写下去"与"读回来"两端。
 *
 * 所以这里用**源码级断言**（运行时断言覆盖不到：一端在 `ai/ptl`、另一端在 Compose 私有函数里）：
 * 任一侧改了命名，这条红，逼着改的人同时看另一侧。
 */
class PdfPreviewConventionTest {

    private fun repoRoot(): File {
        // 单测的工作目录通常是模块目录（app/），但也可能被构建配置挪动，向上找总没错
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            val marker = File(dir, "app/src/main/java/io/github/m0use001/planability")
            if (marker.isDirectory) return dir
            dir = dir.parentFile
        }
        throw AssertionError(
            "找不到仓库根（从 ${System.getProperty("user.dir")} 一路向上都没见到 " +
                "app/src/main/java/io/github/m0use001/planability）——这条测试要读源码，" +
                "找不到就说明工作目录不对，别把它当成'约定没被破坏'"
        )
    }

    private fun source(rel: String): String =
        File(repoRoot(), rel).let {
            assertTrue("源码不见了：$rel（改名/挪包了？两端约定请一起改）", it.isFile)
            it.readText(Charsets.UTF_8)
        }

    @Test
    fun `写侧按 nameWithoutExtension 拼出 PDF 预览名`() {
        val src = source(
            "app/src/main/java/io/github/m0use001/planability/ai/ptl/ToolExecImpls.kt"
        )
        assertTrue(
            "ToolExecImpls 里找不到 `nameWithoutExtension + \".pdf\"` ——" +
                "写侧改了 PDF 命名？预览侧（ExportBrowserScreen.DeckPreview）要跟着改，" +
                "否则预览会静默退回自绘快照",
            src.contains("nameWithoutExtension + \".pdf\"")
        )
    }

    @Test
    fun `读侧按同一个名字找 PDF 预览`() {
        val src = source(
            "app/src/main/java/io/github/m0use001/planability/ui/info/FileLibraryPreview.kt"
        )
        assertTrue(
            "FileLibraryPreview.OfficeFilePreview 里找不到 `\"${'$'}{f.nameWithoutExtension}.pdf\"` ——" +
                "读侧改了 PDF 命名？写侧（ToolExecImpls.pdfPreviewNote）要跟着改，" +
                "否则真·预览会静默消失（不报错，只是永远显示自绘快照）",
            src.contains("\"\${f.nameWithoutExtension}.pdf\"")
        )
    }

    @Test
    fun `转不出 PDF 时必须删掉同名旧 PDF`() {
        val src = source(
            "app/src/main/java/io/github/m0use001/planability/ai/ptl/ToolExecImpls.kt"
        )
        val helper = src.substringAfter(
            "private suspend fun pdfPreviewNote",
            missingDelimiterValue = ""
        )
        assertTrue(
            "pdfPreviewNote 没了或是挪走了 —— 里面那句 `pdfFile.delete()` 是关键：\n" +
                "预览页只要有同名 .pdf 就翻 PDF 并标注「与文件实际版式一致」，\n" +
                "改稿/重生成后转失败却留着旧 PDF = 过期版式顶着一句笃定的承诺给用户看。\n" +
                "宁可退回（诚实的）自绘快照，也不要（撒谎的）精确。",
            helper.isNotEmpty() && helper.contains("pdfFile.delete()")
        )
    }
}
