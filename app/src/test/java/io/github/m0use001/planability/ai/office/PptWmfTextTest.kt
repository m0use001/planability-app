package io.github.m0use001.planability.ai.office

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.Deflater

/**
 * 2026-11-12：老版 .ppt「只能读到标题」根修的守卫。
 *
 * 真机课件取证（8 页数学课件）：文本原子只有页眉标题，正文（定理/例题公式）是
 * Pictures 流里的 WMF 矢量图（0xF01B BLIP，deflate 压缩）。WMF 的 EXTTEXTOUT
 * 记录带真实字符，按 (y, x) 排序可还原成行。这里用**合成 WMF**钉住三件事：
 * 1) EXTTEXTOUT 与 TEXTOUT 两种记录都收；
 * 2) 按坐标还原顺序（先 y 后 x），同一行用空格连接，换行按 y 跳变；
 * 3) 字形下标（ETO_GLYPH_INDEX）的 run 必须丢弃——解出来是乱码。
 * 另钉 BLIP 包装：deflate 压缩的 WMF 要先解开再解析。
 *
 * 为什么不用真实课件当夹具：涉及用户讲义内容，不入库；合成 WMF 结构等价。
 */
class PptWmfTextTest {

    /** 造一条 WMF 记录：size 以「字」计（含 6 字节头）。 */
    private fun rec(func: Int, params: ByteArray): ByteArray {
        val words = (6 + params.size + 1) / 2 + (if ((6 + params.size) % 2 != 0) 1 else 0)
        val out = ByteArray(words * 2)
        out[0] = (words and 0xFF).toByte()
        out[1] = ((words shr 8) and 0xFF).toByte()
        out[2] = 0; out[3] = 0
        out[4] = (func and 0xFF).toByte()
        out[5] = ((func shr 8) and 0xFF).toByte()
        params.copyInto(out, 6)
        return out
    }

    private fun extText(x: Int, y: Int, text: String, opt: Int = 0): ByteArray {
        val b = text.toByteArray(Charsets.ISO_8859_1)
        val p = ByteArray(8 + b.size)
        fun put16(o: Int, v: Int) { p[o] = (v and 0xFF).toByte(); p[o + 1] = ((v shr 8) and 0xFF).toByte() }
        put16(0, y); put16(2, x); put16(4, b.size); put16(6, opt)
        b.copyInto(p, 8)
        return rec(0x0A32, p)
    }

    private fun wmf(vararg recs: ByteArray): ByteArray {
        val head = ByteArray(18)
        head[0] = 1; head[1] = 0          // type = 1（内存/文件 WMF）
        head[2] = 9; head[3] = 0          // headerWords = 9
        val out = ByteArrayOutputStream()
        out.write(head)
        recs.forEach { out.write(it) }
        return out.toByteArray()
    }

    @Test
    fun `EXTTEXTOUT 按坐标还原成行`() {
        // 乱序写入：先第二行右侧，再第一行右侧，再第一行左侧
        val d = wmf(
            extText(x = 900, y = 120, text = "lim()0."),
            extText(x = 900, y = 40, text = "0/0"),
            extText(x = 40, y = 40, text = "Theorem A"),
            extText(x = 40, y = 120, text = "Suppose that")
        )
        val t = PptBinary.wmfText(d)
        assertEquals("Theorem A 0/0\nSuppose that lim()0.", t)
    }

    @Test
    fun `TEXTOUT 老记录也收`() {
        // META_TEXTOUT: nChars, string, y, x
        val b = "Cauchy".toByteArray(Charsets.ISO_8859_1)
        val p = ByteArray(4 + b.size + 4)
        p[0] = b.size.toByte(); p[1] = 0
        b.copyInto(p, 2)
        p[2 + b.size] = 40; p[3 + b.size] = 0        // y
        p[4 + b.size] = 40; p[5 + b.size] = 0        // x
        val d = wmf(rec(0x0521, p))
        assertEquals("Cauchy", PptBinary.wmfText(d))
    }

    @Test
    fun `字形下标 run 必须丢弃 - 解出来是乱码`() {
        // ETO_GLYPH_INDEX(0x10)：字节是字形编号不是字符
        val d = wmf(
            extText(x = 40, y = 40, text = "Real text"),
            extText(x = 40, y = 80, text = "\u0001\u0002\u0003", opt = 0x10)
        )
        val t = PptBinary.wmfText(d)
        assertEquals("Real text", t)
    }

    @Test
    fun `deflate 压缩的 BLIP 先解压再解析`() {
        val w = wmf(extText(x = 40, y = 40, text = "L Hopital"))
        val def = Deflater()
        def.setInput(w); def.finish()
        val buf = ByteArrayOutputStream()
        val tmp = ByteArray(4096)
        while (!def.finished()) {
            val n = def.deflate(tmp)
            if (n <= 0) break
            buf.write(tmp, 0, n)
        }
        def.end()
        val z = buf.toByteArray()
        // BLIP：16B UID + 头部若干字节 + zlib 流
        val blip = ByteArray(16 + 34 + z.size)
        z.copyInto(blip, 16 + 34)
        val t = PptBinary.blipText(blip, 0, blip.size)
        assertEquals("L Hopital", t)
    }

    @Test
    fun `不是 WMF 的载荷返回 null 而不是乱码`() {
        assertNull(PptBinary.wmfText(ByteArray(64)))
        assertTrue(PptBinary.pictureTexts(java.io.File("不存在.ppt")).isEmpty())
    }
}
