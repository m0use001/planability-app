package io.github.m0use001.planability.ai.embed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * v51：向量数学与序列化（纯函数，不依赖模型）。
 *
 * 归一化之后余弦=点积，是向量检索能"一条算子跑一库"的前提，所以单独钉住。
 */
class EmbedderMathTest {

    @Test
    fun normalizeMakesUnitLength() {
        val v = floatArrayOf(3f, 4f)
        Embedder.normalize(v)
        assertEquals(0.6f, v[0], 1e-6f)
        assertEquals(0.8f, v[1], 1e-6f)
    }

    @Test
    fun normalizeHandlesZeroVector() {
        val v = floatArrayOf(0f, 0f, 0f)
        Embedder.normalize(v)   // 不许除零崩
        assertTrue(v.all { it == 0f })
    }

    @Test
    fun cosineOfIdenticalIsOne() {
        val a = floatArrayOf(1f, 2f, 3f)
        assertEquals(1f, Embedder.cosine(a, a.copyOf()), 1e-6f)
    }

    @Test
    fun cosineOfOrthogonalIsZero() {
        assertEquals(0f, Embedder.cosine(floatArrayOf(1f, 0f), floatArrayOf(0f, 1f)), 1e-6f)
    }

    @Test
    fun cosineOfOppositeIsMinusOne() {
        assertEquals(-1f, Embedder.cosine(floatArrayOf(1f, 0f), floatArrayOf(-1f, 0f)), 1e-6f)
    }

    @Test
    fun cosineWithZeroVectorIsZero() {
        assertEquals(0f, Embedder.cosine(floatArrayOf(0f, 0f), floatArrayOf(1f, 1f)), 1e-6f)
    }

    @Test
    fun bytesRoundTrip() {
        val v = floatArrayOf(0.5f, -0.25f, 1e-3f, 512f)
        val back = Embedder.fromBytes(Embedder.toBytes(v))
        assertEquals(v.size, back.size)
        for (i in v.indices) assertTrue(abs(v[i] - back[i]) < 1e-6f)
    }

    @Test
    fun storedVectorSizeMatchesDim() {
        // 512 维 float32 = 2048 字节（DB 里按 BLOB 存）
        val v = FloatArray(512) { it * 0.001f }
        assertEquals(2048, Embedder.toBytes(v).size)
    }
}
