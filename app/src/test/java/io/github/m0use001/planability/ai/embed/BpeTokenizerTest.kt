package io.github.m0use001.planability.ai.embed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v52：Qwen3-Embedding 用的 byte-level BPE 分词。
 *
 * 词表用玩具数据（真表 15 万条、4.4MB，随模型下载）。钉的是**算法**：
 * 预切分边界、按优先级合并、未知片段退 unk、截断。真词表上的表现由设备仪器化测试覆盖。
 */
class BpeTokenizerTest {

    // tokens 用 GPT-2 的 byte-level 写法：ASCII 原样
    private val vocab = mapOf(
        "a" to 0, "b" to 1, "c" to 2, "d" to 3,
        "ab" to 4, "abc" to 5,
        "Ġ" to 6, "Hello" to 7, "," to 8,
        "<|endoftext|>" to 9, "world" to 10
    )
    private val merges = listOf("a" to "b", "ab" to "c")
    private val t = BpeTokenizer(vocab, merges)

    @Test
    fun mergesByPriority() {
        assertEquals(listOf("ab"), t.tokens("ab"))
        assertEquals(listOf("abc"), t.tokens("abc"))
    }

    @Test
    fun partialMergeFallsBackToPieces() {
        // "abcd"：ab 合并成 ab，再和 c 合成 abc，d 留单
        assertEquals(listOf("abc", "d"), t.tokens("abcd"))
    }

    @Test
    fun pretokenizesOnSpaceAndPunctuation() {
        // byte-level BPE **从单字节起步**，没有合并规则时就是逐字符；
        // 空格记为 "Ġ"（U+0120）——这一条正好证明预切分把空格切成了独立片段。
        assertEquals(
            listOf("H", "e", "l", "l", "o", ",", "Ġ", "w", "o", "r", "l", "d"),
            t.tokens("Hello, world")
        )
    }

    @Test
    fun encodeMapsKnownAndUnknown() {
        val ids = t.encode("abc").toList()
        assertEquals(listOf(5L), ids)
        // "z" 不在词表 → unk
        assertEquals(listOf(9L), t.encode("z").toList())
    }

    @Test
    fun encodeTruncates() {
        val ids = t.encode("aaaaaa", maxLen = 3)
        assertEquals(3, ids.size)
    }

    @Test
    fun emptyTextIsEmpty() {
        assertTrue(t.encode("").isEmpty())
    }

    @Test
    fun cjkStillProducesTokens() {
        // 玩具词表没有 CJK 字节 token，但分词不应崩、也不该返回空
        val toks = t.tokens("中文")
        assertTrue("CJK 至少要切出片段", toks.isNotEmpty())
    }

    @Test
    fun fromJsonParsesRealFormat() {
        val json = """{"a":0,"b":1,"ab":2,"<|endoftext|>":3}"""
        val mergesTxt = "#version: 0.2\na b\n"
        val parsed = BpeTokenizer.fromJson(json, mergesTxt)
        assertEquals(4, parsed.vocabSize)
        assertEquals(listOf("ab"), parsed.tokens("ab"))
    }
}
