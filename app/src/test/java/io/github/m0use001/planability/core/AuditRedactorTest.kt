package io.github.m0use001.planability.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** v44：审计参数脱敏。重点验证"路径/命令留、内容/凭据隐"。 */
class AuditRedactorTest {

    @Test
    fun `path and command are kept for reconstruction`() {
        val out = AuditRedactor.redact("""{"path":"/sdcard/notes/a.md","content":"秘密正文"}""")
        assertTrue("path 丢了：$out", out.contains("/sdcard/notes/a.md"))
        assertTrue("content 未脱敏：$out", out.contains("（隐去 4 字）"))
        assertFalse("content 内容泄露：$out", out.contains("秘密正文"))
    }

    @Test
    fun `shell command stays readable`() {
        val out = AuditRedactor.redact("""{"command":"ls -l /sdcard","cwd":"/sdcard"}""")
        assertTrue(out.contains("ls -l /sdcard"))
        assertTrue(out.contains("/sdcard"))
    }

    @Test
    fun `browser fill text is hidden but selector kept`() {
        val out = AuditRedactor.redact("""{"selector":"#pwd","text":"hunter2"}""")
        assertTrue(out.contains("#pwd"))
        assertFalse(out.contains("hunter2"))
    }

    @Test
    fun `case insensitive secret keys`() {
        val out = AuditRedactor.redact("""{"apiKey":"sk-123","Token":"abc"}""")
        assertFalse(out.contains("sk-123"))
        assertFalse(out.contains("abc"))
    }

    @Test
    fun `nested object inside content-like key is walked`() {
        val out = AuditRedactor.redact("""{"payload":{"text":"深层秘密","path":"/a"}}""")
        assertFalse(out.contains("深层秘密"))
        assertTrue(out.contains("/a"))
    }

    @Test
    fun `unparsable args are fully hidden but length reported`() {
        val out = AuditRedactor.redact("not json but has sk-secret-1234567890")
        assertFalse(out.contains("sk-secret"))
        assertTrue(out.contains("已隐去"))
    }

    @Test
    fun `blank stays blank`() {
        assertEquals("", AuditRedactor.redact("  "))
    }

    @Test
    fun `long args are truncated`() {
        val big = "x".repeat(5000)
        val out = AuditRedactor.redact("""{"query":"$big"}""")
        assertTrue(out.length <= 2000)
    }
}
