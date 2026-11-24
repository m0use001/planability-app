package io.github.m0use001.planability.ai.embed

import android.content.Context

/**
 * v51 知识库语义检索：BERT WordPiece 分词（bge-small-zh-v1.5 用）。
 *
 * 为什么自己写而不是引 tokenizer 库：
 * - 端侧只需要 BERT 这一种词表格式，`vocab.txt` 是 110KB 的纯文本；
 * - 引 `ai.djl.huggingface:tokenizers` 要带 native 库，APK 又多几 MB，收益为零。
 *
 * 规则严格照 BERT 的 `BasicTokenizer` + `WordpieceTokenizer`：
 * 1. 清洗（去控制符 / 全角转半角 / 小写）；
 * 2. 按空白 + 标点切分，**CJK 字符逐字切开**；
 * 3. 对每个词做贪心最长匹配，匹配不到用 `[UNK]`。
 *
 * 中文句子里 CJK 本来就是逐字的，所以第 2 步是关键——不然整句中文会变成一个词、
 * 词表里查不到、全变 [UNK]（这是自写 tokenizer 最常见的翻车点）。
 */
class BertTokenizer(vocab: List<String>) {

    private val ids: Map<String, Int> = vocab.withIndex().associate { (i, w) -> w to i }

    val vocabSize: Int get() = ids.size
    val padId: Int get() = ids["[PAD]"] ?: 0
    val unkId: Int get() = ids["[UNK]"] ?: 100
    val clsId: Int get() = ids["[CLS]"] ?: 101
    val sepId: Int get() = ids["[SEP]"] ?: 102

    /** 编码为模型输入：[CLS] + tokens + [SEP]，超长截断到 [maxLen] */
    fun encode(text: String, maxLen: Int = MAX_TOKENS): IntArray {
        val pieces = mutableListOf<Int>()
        pieces += clsId
        tokens(text).forEach { tok ->
            if (pieces.size >= maxLen - 1) return@forEach
            pieces += (ids[tok] ?: unkId)
        }
        pieces += sepId
        return pieces.toIntArray()
    }

    /** 切词（不查 id），单测直接验这一步 */
    fun tokens(text: String): List<String> {
        val out = mutableListOf<String>()
        basicTokens(text).forEach { w ->
            wordPiece(w).forEach { out += it }
        }
        return out
    }

    // ---------- BasicTokenizer ----------

    private fun basicTokens(text: String): List<String> {
        val cleaned = StringBuilder(text.length)
        text.forEach { c ->
            when {
                c == '\u0000' || c == '\uFFFD' -> {}
                c.isWhitespace() -> cleaned.append(' ')
                isControl(c) -> {}
                else -> cleaned.append(c)
            }
        }
        val lower = cleaned.toString().lowercase()
        val out = mutableListOf<String>()
        val cur = StringBuilder()
        fun flush() {
            if (cur.isNotEmpty()) {
                out += cur.toString()
                cur.setLength(0)
            }
        }
        lower.forEach { ch ->
            when {
                ch == ' ' -> flush()
                isCjk(ch) -> { flush(); out += ch.toString() }
                isPunctuation(ch) -> { flush(); out += ch.toString() }
                else -> cur.append(ch)
            }
        }
        flush()
        return out
    }

    private fun isCjk(c: Char): Boolean {
        val cp = c.code
        return (cp in 0x4E00..0x9FFF) ||      // CJK 统一表意
            (cp in 0x3400..0x4DBF) ||          // 扩展 A
            (cp in 0xF900..0xFAFF) ||          // 兼容表意
            (cp in 0x3040..0x30FF) ||          // 日文假名
            (cp in 0xAC00..0xD7AF)             // 韩文
    }

    private fun isPunctuation(c: Char): Boolean {
        if (c.isLetterOrDigit()) return false
        val type = Character.getType(c)
        return type == Character.CONNECTOR_PUNCTUATION.toInt() ||
            type == Character.DASH_PUNCTUATION.toInt() ||
            type == Character.START_PUNCTUATION.toInt() ||
            type == Character.END_PUNCTUATION.toInt() ||
            type == Character.INITIAL_QUOTE_PUNCTUATION.toInt() ||
            type == Character.FINAL_QUOTE_PUNCTUATION.toInt() ||
            type == Character.OTHER_PUNCTUATION.toInt() ||
            type == Character.MATH_SYMBOL.toInt() ||
            type == Character.CURRENCY_SYMBOL.toInt() ||
            type == Character.MODIFIER_SYMBOL.toInt() ||
            type == Character.OTHER_SYMBOL.toInt()
    }

    private fun isControl(c: Char): Boolean {
        if (c == '\t' || c == '\n' || c == '\r') return false
        val type = Character.getType(c)
        return type == Character.CONTROL.toInt() || type == Character.FORMAT.toInt()
    }

    // ---------- WordpieceTokenizer ----------

    private fun wordPiece(word: String): List<String> {
        if (word.length > 100) return listOf("[UNK]")
        val out = mutableListOf<String>()
        var start = 0
        while (start < word.length) {
            var end = word.length
            var piece: String? = null
            while (start < end) {
                val sub = if (start > 0) "##" + word.substring(start, end) else word.substring(start, end)
                if (ids.containsKey(sub)) {
                    piece = sub
                    break
                }
                end--
            }
            if (piece == null) return listOf("[UNK]")   // 整词只要有一段落不了地就退 UNK
            out += piece
            start = end
        }
        return out
    }

    companion object {
        /** 语义检索只喂前 256 个字元：够表达一篇笔记的主题，也把单条推理钉在 20ms 内 */
        const val MAX_TOKENS = 256

        /** 从 assets 读词表（110KB，按文件名缓存） */
        // v53：按词表文件名缓存——zh/en 两套词表（21128/30522 条），不同模型各用各的
        private val cached = HashMap<String, BertTokenizer>()

        fun fromAssets(context: Context, assetName: String = "bge_vocab.txt"): BertTokenizer =
            cached[assetName] ?: synchronized(this) {
                cached[assetName] ?: run {
                    val vocab = context.assets.open(assetName).bufferedReader().use { it.readLines() }
                    val t = BertTokenizer(vocab)
                    // 词表读残（0 行/占位）就当成不可用：宁可退回词法检索，也别把整句编码成 [UNK]
                    require(t.vocabSize > 1000) { "$assetName 不完整（${t.vocabSize} 条）" }
                    t.also { cached[assetName] = it }
                }
            }
    }
}
