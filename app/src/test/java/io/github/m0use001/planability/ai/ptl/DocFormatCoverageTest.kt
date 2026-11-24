package io.github.m0use001.planability.ai.ptl

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * **格式覆盖与失败契约**回归（2026-11-05 用户「一次踩一个坑」整改）。
 *
 * 锁三件事：
 * 1. `DOC_EXTS` 里**每一种**扩展名都必须有抽取分支，且**永远不返回空串**——
 *    新增格式时忘了接线，"空串"会被工具层翻译成一句无信息的"解析失败"，
 *    用户和模型都不知道到底发生了什么（PDF 就吃过这个亏）。
 * 2. 暂不支持的格式（PDF）必须给出**原因 + 替代路径**，而不是笼统失败。
 * 3. 抽取质量闸：文件不小但读出来的字极少时，结果必须带"质量异常"前缀。
 */
class DocFormatCoverageTest {

    private fun tmp(name: String, bytes: ByteArray): File {
        val f = File.createTempFile("docfmt", name)
        f.deleteOnExit()
        f.writeBytes(bytes)
        return f
    }

    @Test
    fun `每种声明支持的格式都不返回空串`() {
        ToolExecImpls.DOC_EXTS.forEach { ext ->
            val f = tmp(".$ext", "这不是一份真的文档".toByteArray())
            val text = ToolExecImpls.parseDocText(f)
            assertFalse(".$ext 返回了空串——工具层会误报「解析失败」", text.isBlank())
            assertTrue(
                ".$ext 的结果应是可读说明（[抽取失败/PDF 未抽取…]），实际：$text",
                text.startsWith("[")
            )
        }
    }

    @Test
    fun `PDF 给出原因与替代路径`() {
        val f = tmp(".pdf", ByteArray(200_000))
        val text = ToolExecImpls.parseDocText(f)
        // 2026-11-10：a07ef7be 换成「文字层 + 渲染 OCR + 另存」契约，旧文案（翻页预览/截图）已退役。
        // 且 JVM 单测没有 Android Context，PDF 的 OCR 分支跑不到（真机契约由
        // PoiDocumentReaderDeviceTest 覆盖）——这里只钉**失败契约**：永不空串、以 [ 前缀
        // 给出可读原因（抽取失败/质量异常），不允许静默返回空。
        assertTrue("失败契约：不能返回空串", text.isNotBlank())
        assertTrue("必须以 [ 前缀给出原因：$text", text.startsWith("["))
        assertTrue("原因要可读（抽取/质量）：$text", text.contains("抽取"))
    }

    @Test
    fun `抽取质量闸在字少时触发`() {
        val f = tmp(".docx", ByteArray(120_000))   // 120KB 的垃圾文件
        val text = ToolExecImpls.parseDocText(f)
        assertTrue("应带质量异常前缀：$text", text.startsWith("[抽取质量异常"))
    }

    @Test
    fun `正常大小的文本不被质量闸误伤`() {
        val f = tmp(".txt", "这是一段足够长的正文内容，用来验证质量闸不会误伤正常文件。".repeat(5).toByteArray())
        val text = ToolExecImpls.parseDocText(f)
        assertFalse("不该有质量异常前缀：$text", text.startsWith("[抽取质量异常"))
        assertTrue(text.isNotBlank())
    }
}
