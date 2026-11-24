package io.github.m0use001.planability.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v44：SecretBox 的格式/幂等语义。
 *
 * 真正的 AES 走 Android Keystore，JVM 单测里没有（也不该有），所以这里只验两件事：
 * - 前缀/魔数判定（决定"这到底是不是密文"）；
 * - **不含密文时绝不返回别的值**——历史明文原样返回，损坏密文返回空串
 *   （绝不能把密文当 API key 发出去）。
 */
class SecretBoxTest {

    @Test
    fun `plain legacy value passes through untouched`() {
        assertEquals("sk-plain", SecretBox.open("sk-plain"))
        assertFalse(SecretBox.isSealed("sk-plain"))
    }

    @Test
    fun `blank stays blank`() {
        assertEquals("", SecretBox.open(""))
        assertEquals("", SecretBox.seal(""))
    }

    @Test
    fun `already sealed value is not double sealed`() {
        val sealed = SecretBox.PREFIX + "AAAA"
        assertEquals(sealed, SecretBox.seal(sealed))
        assertTrue(SecretBox.isSealed(sealed))
    }

    @Test
    fun `broken sealed value yields empty instead of ciphertext`() {
        // 太短（没有 IV）
        assertEquals("", SecretBox.open(SecretBox.PREFIX + "AAAA"))
        // 合法 base64 但解不开（没有 Android Keystore / 密钥换了）：也不能回吐密文
        val junk = SecretBox.PREFIX + java.util.Base64.getEncoder()
            .encodeToString(ByteArray(40) { 7 })
        assertEquals("", SecretBox.open(junk))
        // 非法 base64
        assertEquals("", SecretBox.open(SecretBox.PREFIX + "!!!not-base64!!!"))
    }

    @Test
    fun `backup file magic detection`() {
        val notSealed = ByteArray(64) { 0 }
        assertFalse(SecretBox.looksSealed(notSealed))
        // 顶部 sqlite header 也不能被误判
        val sqlite = "SQLite format 3\u0000".toByteArray() + ByteArray(40)
        assertFalse(SecretBox.looksSealed(sqlite))
        // 伪造一个正确的头
        val sealed = ByteArray(64).also {
            it[0] = 0x50; it[1] = 0x42; it[2] = 0x4B; it[3] = 0x31
        }
        assertTrue(SecretBox.looksSealed(sealed))
    }

    @Test
    fun `openBytes rejects non sealed input`() {
        assertEquals(null, SecretBox.openBytes(ByteArray(100) { 1 }))
    }
}
