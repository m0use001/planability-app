package io.github.m0use001.planability.ai.ptl

import androidx.test.platform.app.InstrumentationRegistry
import io.github.m0use001.planability.data.Graph
import io.github.m0use001.planability.data.ProjectDirs
import io.github.m0use001.planability.data.ProjectFileRepo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 文档路径解析的**端到端（模拟器真盘）验收**（2026-11-03 用户「英语课项目里 AI 读不到文件」）。
 *
 * 复现真机链路：项目文件目录里放一个真实 docx（最小合法 zip），然后按真机模型试过的
 * **五种写法**分别解析——改造前全部「路径不合法」，改造后应全部命中同一个文件。
 */
class DocPathResolveE2ETest {

    /** 最小合法 docx：只需 word/document.xml（OfficeText.fromDocx 读它） */
    private fun writeMinimalDocx(target: File, text: String) {
        target.parentFile?.mkdirs()
        ZipOutputStream(target.outputStream()).use { z ->
            z.putNextEntry(ZipEntry("[Content_Types].xml"))
            z.write(
                ("<?xml version=\"1.0\" encoding=\"UTF-8\"?><Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">" +
                    "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>" +
                    "<Default Extension=\"xml\" ContentType=\"application/xml\"/>" +
                    "<Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/>" +
                    "</Types>").toByteArray()
            )
            z.closeEntry()
            z.putNextEntry(ZipEntry("word/document.xml"))
            z.write(
                ("<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                    + "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body>"
                    + "<w:p><w:r><w:t>$text</w:t></w:r></w:p>"
                    + "</w:body></w:document>").toByteArray()
            )
            z.closeEntry()
        }
    }

    @Test
    fun resolveFivePathFormsFromRealDeviceReport() = kotlinx.coroutines.runBlocking {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val projName = "E2E文档路径"
        Graph.projects.all().firstOrNull { it.name == projName }?.let { Graph.projects.delete(it.id) }
        val pid = Graph.projects.create(projName)
        assertTrue("项目应创建", pid > 0L)

        val filesRoot = File(ProjectFileRepo.dirOf(pid), ProjectDirs.FILES)
        val name = "1 2026 FALL AETC-International campus-Syllabus.docx"
        val real = File(filesRoot, name)
        writeMinimalDocx(real, "英语课测试文档正文")
        assertTrue("docx 应落盘", real.isFile)

        // 造一个挂在该项目下的会话（项目名/相对路径解析依赖会话项目）
        val sid = Graph.chat.createSession(title = "E2E 读文档", model = "", projectId = pid).id

        // ~ 展开成外部存储根：真机上模型试的是「~/大学文件/…/文件名」
        val storageRoot = runCatching {
            android.os.Environment.getExternalStorageDirectory().canonicalPath
        }.getOrDefault("/storage/emulated/0")
        val tildeForm = "~" + real.canonicalPath.removePrefix(storageRoot)

        val forms = listOf(
            real.absolutePath,                                   // ①绝对路径（fs_search 给出的那种）
            tildeForm,                                           // ②~/ 前缀（真机试过的写法）
            "$projName/${ProjectDirs.FILES}/$name",              // ③项目名/项目文件/文件名
            "${ProjectDirs.FILES}/$name",                        // ④项目文件/文件名
            name                                                 // ⑤裸文件名（项目内按名匹配）
        )
        forms.forEach { p ->
            val (f, err) = ToolExecImpls.resolveReadableFile(p, sid)
            assertTrue("写法「$p」应解析成功，实际错误：$err", f != null)
            assertEquals("写法「$p」应命中同一文件", real.canonicalPath, f!!.canonicalPath)
        }

        // 解析出的文档能被读成文本（不是空串——真机那次就是「读成功但空」）
        val text = ToolExecImpls.parseDocText(real)
        assertTrue("docx 应解析出正文，实际：$text", text.contains("英语课测试文档正文"))
    }
}
