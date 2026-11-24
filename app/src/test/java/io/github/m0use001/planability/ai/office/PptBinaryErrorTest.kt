package io.github.m0use001.planability.ai.office

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * `.ppt`（OLE2）读取的**失败路径**单测：坏文件必须给人话而不是崩溃。
 *
 * 成功路径（真实课件抽取）在开发期用真机课件验证过（8 页/20 页两档，文字与页序正确），
 * 但课件有版权、不进仓库——所以回归集只锁错误处理：
 * 工具层拿到的是"[读不了 .ppt：…]"这样的可读字符串，AI 能据此换策略（如找同目录 PDF）。
 */
class PptBinaryErrorTest {

    private fun tmp(name: String, bytes: ByteArray): File {
        val f = File.createTempFile("pptbin", name)
        f.deleteOnExit()
        f.writeBytes(bytes)
        return f
    }

    @Test
    fun `非 OLE 文件返回人话`() {
        val f = tmp(".ppt", "not a compound file".toByteArray())
        val text = OfficeText.fromPpt(f)
        assertTrue("应带[读不了]标记，实际：$text", text.startsWith("[读不了 .ppt："))
    }

    @Test
    fun `OLE 头但目录损坏也不崩`() {
        // 只有正确的 8 字节签名，其余全零 —— 目录流读不出来
        val b = ByteArray(1024)
        byteArrayOf(0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte(),
            0xA1.toByte(), 0xB1.toByte(), 0x1A, 0xE1.toByte()).copyInto(b)
        val f = tmp(".ppt", b)
        val text = OfficeText.fromPpt(f)
        assertTrue("应带[读不了]标记，实际：$text", text.startsWith("[读不了 .ppt："))
    }

    @Test
    fun `空文件不崩`() {
        val f = tmp(".ppt", ByteArray(0))
        val text = OfficeText.fromPpt(f)
        assertTrue("应带[读不了]标记，实际：$text", text.startsWith("[读不了 .ppt："))
    }
}
