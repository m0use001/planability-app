package io.github.m0use001.planability.ai.embed

/**
 * v52：Qwen3-Embedding 的 BPE 分词（byte-level BPE，与 GPT-2/cl100k 同构）。
 *
 * 词表来自模型仓库的 `vocab.json`（token → id）与 `merges.txt`（合并优先级），
 * 随重型模型一起下载——Qwen 的词表有 15 万条，塞进 APK 不划算。
 *
 * 与 BERT WordPiece 的本质区别（这决定了不能复用 [BertTokenizer]）：
 * - 先把文本按正则**预切分**（字母串 / 单个数字 / 标点串 / 空白串）；
 * - 每个片段转成 byte-level 的"可见字符"，再做 BPE 合并；
 * - 合并表是**有序对**，按优先级反复合并，不是最长匹配；
 * - 没有 `##` 前缀，也没有 [CLS]/[SEP]。
 */
class BpeTokenizer(vocab: Map<String, Int>, merges: List<Pair<String, String>>) {

    private val ids: Map<String, Int> = vocab
    private val ranks: Map<Pair<String, String>, Int> =
        merges.withIndex().associate { (i, p) -> p to i }
    private val cache = HashMap<String, List<String>>()

    val vocabSize: Int get() = ids.size
    val unkId: Int = ids["<|endoftext|>"] ?: 0

    /** 编码为 id 序列（不加任何特殊 token；Qwen3-Embedding 的 ONNX 输入就是裸文本） */
    fun encode(text: String, maxLen: Int = MAX_TOKENS): LongArray {
        val out = ArrayList<Long>(minOf(text.length, maxLen))
        for (piece in pretokenize(text)) {
            for (tok in bpe(piece)) {
                if (out.size >= maxLen) return out.toLongArray()
                out += (ids[tok] ?: unkId).toLong()
            }
        }
        return out.toLongArray()
    }

    /** 切词（不查 id），单测直接验这一步 */
    fun tokens(text: String): List<String> = pretokenize(text).flatMap { bpe(it) }

    // ---------- 预切分 ----------

    private fun pretokenize(text: String): List<String> {
        val out = mutableListOf<String>()
        for (m in PRE.findAll(text)) out += m.value
        return out
    }

    // ---------- BPE ----------

    private fun bpe(word: String): List<String> {
        cache[word]?.let { return it }
        // **必须按 UTF-8 字节起步**：byte-level BPE 的单位是字节，不是字符。
        // 按字符映射的话中文（3 字节/字）永远查不到词表，全部退化成同一个 [UNK]，
        // 结果就是"所有文本的向量一模一样"（实测踩过）。
        val encoded = word.toByteArray(Charsets.UTF_8).map { b ->
            BYTE_ENCODER[(b.toInt() and 0xFF).toChar()] ?: (b.toInt() and 0xFF).toChar().toString()
        }
        var parts = encoded
        if (parts.size > 1) {
            while (true) {
                var bestRank = Int.MAX_VALUE
                var bestIdx = -1
                for (i in 0 until parts.size - 1) {
                    val r = ranks[parts[i] to parts[i + 1]] ?: continue
                    if (r < bestRank) {
                        bestRank = r
                        bestIdx = i
                    }
                }
                if (bestIdx < 0) break
                val merged = parts[bestIdx] + parts[bestIdx + 1]
                val next = ArrayList<String>(parts.size - 1)
                var i = 0
                while (i < parts.size) {
                    if (i == bestIdx) {
                        next += merged
                        i += 2
                    } else {
                        next += parts[i]
                        i += 1
                    }
                }
                parts = next
                if (parts.size == 1) break
            }
        }
        if (cache.size < 50_000) cache[word] = parts
        return parts
    }

    companion object {
        const val MAX_TOKENS = 256

        /**
         * Qwen2/Qwen3 官方预切分正则（GPT-4 cl100k 变体）：
         * 英文缩写 / 可选前导空格+字母串 / 单个数字 / 标点串 / 换行 / 空白串。
         */
        private val PRE = Regex(
            """(?i:'s|'t|'re|'ve|'m|'ll|'d)|[^\r\n\p{L}\p{N}]?\p{L}+|\p{N}| ?[^\s\p{L}\p{N}]+[\r\n]*|\s*[\r\n]+|\s+(?!\S)|\s+"""
        )

        /** GPT-2 的 byte ↔ unicode 映射（让任意字节都能出现在词表里） */
        private val BYTE_ENCODER: Map<Char, String> by lazy {
            val bs = mutableListOf<Int>()
            (33..126).forEach { bs += it }                 // 可见 ASCII
            (161..172).forEach { bs += it }
            (174..255).forEach { bs += it }
            val cs = bs.toMutableList()
            var n = 0
            for (b in 0..255) {
                if (b !in bs) {
                    bs += b
                    cs += 256 + n
                    n++
                }
            }
            bs.mapIndexed { i, b -> b.toChar() to cs[i].toChar().toString() }.toMap()
        }

        /** 从 vocab.json 文本解析（JSON 对象 token→id） */
        fun fromJson(vocabJson: String, mergesTxt: String): BpeTokenizer {
            val vocab = HashMap<String, Int>(200_000)
            val obj = org.json.JSONObject(vocabJson)
            obj.keys().forEach { k -> vocab[k] = obj.getInt(k) }
            val merges = ArrayList<Pair<String, String>>(160_000)
            mergesTxt.lineSequence().forEach { line ->
                val t = line.trim()
                if (t.isEmpty() || t.startsWith("#")) return@forEach   // 首行 "#version: ..."
                val sp = t.indexOf(' ')
                if (sp > 0) merges += t.substring(0, sp) to t.substring(sp + 1)
            }
            return BpeTokenizer(vocab, merges)
        }
    }
}
