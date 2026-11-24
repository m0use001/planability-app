package io.github.m0use001.planability.ai.embed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v51：自写 BERT WordPiece 分词的正确性。
 *
 * 词表用一小组手工数据（单测读不到 assets），但覆盖的都是会翻车的形态：
 * - **中文必须逐字切**：整句中文当一个词 → 词表查不到 → 全变 [UNK]，这是自写分词最常见的死法；
 * - ASCII 走 WordPiece 贪心最长匹配，续接块带 `##`；
 * - 标点单列、大小写归一、超长截断。
 */
class BertTokenizerTest {

    private val vocab = listOf(
        "[PAD]", "[UNK]", "[CLS]", "[SEP]",
        "线", "性", "代", "数", "中", "文", "题",
        "matrix", "##s", "##es", "vec", "##tor",
        "a", "b", "c", ","
    )
    private val t = BertTokenizer(vocab)

    @Test
    fun chineseSplitsPerCharacter() {
        assertEquals(listOf("线", "性", "代", "数"), t.tokens("线性代数"))
    }

    @Test
    fun encodeWrapsWithClsAndSep() {
        val ids = t.encode("线性代数").toList()
        assertEquals(t.clsId, ids.first())
        assertEquals(t.sepId, ids.last())
        assertEquals(6, ids.size)
    }

    @Test
    fun englishUsesWordPiece() {
        assertEquals(listOf("matrix", "##es"), t.tokens("matrixes"))
        assertEquals(listOf("vec", "##tor"), t.tokens("vector"))
    }

    @Test
    fun unknownWordBecomesUnk() {
        assertEquals(listOf("[UNK]"), t.tokens("zzz"))
    }

    @Test
    fun caseIsLowered() {
        assertEquals(listOf("matrix"), t.tokens("MATRIX"))
    }

    @Test
    fun punctuationIsSeparated() {
        assertEquals(listOf("a", ",", "b"), t.tokens("a,b"))
    }

    @Test
    fun mixedCjkAndAscii() {
        // 「中文题 MATRIX」→ 逐字 + 空格切 + 小写
        assertEquals(listOf("中", "文", "题", "matrix"), t.tokens("中文题 MATRIX"))
    }

    @Test
    fun truncationKeepsClsAndSep() {
        val long = "线".repeat(50)
        val ids = t.encode(long, maxLen = 10)
        assertEquals(10, ids.size)
        assertEquals(t.clsId, ids.first())
        assertEquals(t.sepId, ids.last())
    }

    @Test
    fun overlongTokenIsUnk() {
        assertTrue(t.tokens("x".repeat(101)) == listOf("[UNK]"))
    }
}
