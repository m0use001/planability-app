package io.github.m0use001.planability.ai.ptl

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 电脑端输出的"空壳"检测（[ToolExecImpls.pcOutputLooksHollow]）。
 *
 * 这层守卫存在的原因见那个函数的注释：2026-10-11 桥把 pptx 入参读错一层，
 * 返回 0 页空文件，而上层"存在且非 0 字节"就收下，顶掉手机端兜底还报"高保真版式"。
 * **文件存在 ≠ 文件有内容**，这正是"失败装成功"那一类 bug 的入口。
 */
class PcOutputGuardTest {

    private fun zipWith(vararg entries: String): File {
        val f = File.createTempFile("pcout", ".zip")
        f.deleteOnExit()
        ZipOutputStream(f.outputStream().buffered()).use { z ->
            for (e in entries) {
                z.putNextEntry(ZipEntry(e))
                z.write("<x/>".toByteArray())
                z.closeEntry()
            }
        }
        return f
    }

    @Test
    fun `pptx 只有母版没有 slide 判为空壳`() {
        val f = zipWith(
            "[Content_Types].xml", "ppt/presentation.xml",
            "ppt/slideMasters/slideMaster1.xml", "ppt/slideLayouts/slideLayout1.xml"
        )
        assertTrue(
            "没有 ppt/slides/slideN.xml 就是 0 页——正是 2026-10-11 真机上那份 45627 字节的空 pptx",
            ToolExecImpls.pcOutputLooksHollow(f, "pptx")
        )
    }

    @Test
    fun `pptx 有 slide 就不是空壳`() {
        val f = zipWith("ppt/presentation.xml", "ppt/slides/slide1.xml", "ppt/slides/slide2.xml")
        assertFalse(ToolExecImpls.pcOutputLooksHollow(f, "pptx"))
    }

    @Test
    fun `docx 认 word document xml`() {
        assertFalse(ToolExecImpls.pcOutputLooksHollow(zipWith("word/document.xml"), "docx"))
        assertTrue(ToolExecImpls.pcOutputLooksHollow(zipWith("[Content_Types].xml"), "docx"))
    }

    @Test
    fun `xlsx 认任意 worksheet`() {
        assertFalse(ToolExecImpls.pcOutputLooksHollow(zipWith("xl/worksheets/sheet1.xml"), "xlsx"))
        assertTrue(ToolExecImpls.pcOutputLooksHollow(zipWith("xl/workbook.xml"), "xlsx"))
    }

    @Test
    fun `不是 zip 或不存在一律当空壳`() {
        val notZip = File.createTempFile("notzip", ".pptx")
        notZip.deleteOnExit()
        notZip.writeText("这不是一个 zip")
        assertTrue(ToolExecImpls.pcOutputLooksHollow(notZip, "pptx"))
        assertTrue(ToolExecImpls.pcOutputLooksHollow(File(notZip.parentFile, "根本不存在.pptx"), "pptx"))
        val empty = File.createTempFile("empty", ".pptx")
        empty.deleteOnExit()
        assertTrue(ToolExecImpls.pcOutputLooksHollow(empty, "pptx"))
    }

    /**
     * 跨端断言：手机端发的形状与桥读的形状必须对得上。
     *
     * 两端各自都"对"：桥的门禁脚本直接拿 deck 当 payload 测（绿），
     * 手机端 `PcBridge.render` 发 `{deck:...}`（它自己也以为对）——
     * 只有把两边的源码放一起比才看得出错位，而它的后果是**静默空文件**。
     * 所以钉在源码层：桥改了取值方式、或手机端改了包法，这条就红。
     */
    @Test
    fun `桥读 pptx 入参要兼容 payload deck 这层包装`() {
        val root = generateSequence(File(System.getProperty("user.dir") ?: ".").absoluteFile) { it.parentFile }
            .firstOrNull { File(it, "app/src/main/java/io/github/m0use001/planability").isDirectory }
        assertTrue(
            "找不到仓库根（从 ${System.getProperty("user.dir")} 向上）——" +
                "这条要读桥的源码，找不到不能当通过",
            root != null
        )
        val bridge = File(root!!, "desktop/scripts/office-bridge.mjs")
        assertTrue("桥的源码不在了？$bridge", bridge.isFile)
        val src = bridge.readText(Charsets.UTF_8)
        assertTrue(
            "office-bridge.mjs 不再是 `renderPptx(payload.deck || payload)` —— " +
                "手机端 PcBridge.render 发的是 {deck:{...}}，只读 payload.slides 会静默产出 0 页空 pptx。" +
                "改形状请两端一起改，并更新这条断言",
            src.contains("renderPptx(payload.deck || payload)")
        )
    }
}
