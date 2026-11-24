package io.github.m0use001.planability.ai.embed

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import java.nio.LongBuffer
import kotlin.math.sqrt

/**
 * v52 知识库语义检索：端侧向量化（ONNX Runtime CPU，两档模型）。
 *
 * - 轻量 bge-small-zh：512 维，**CLS 池化**，词表在 assets；
 * - 重型 Qwen3-Embedding-0.6B：1024 维，**last-token 池化**，BPE 词表随模型下载，
 *   查询侧加官方 instruction 前缀（文档侧不加）。
 *
 * 两个模型各自建会话，但**只保留当前模型**：重型模型权重 590MB，同时留两个会话
 * 会把内存顶到 1GB 以上（手机上会被 LMK 杀）。切换时显式关旧会话。
 */
object Embedder {

    private interface Tokenizer {
        fun encode(text: String, maxLen: Int): LongArray
    }

    private class Loaded(
        val session: OrtSession,
        val tokenizer: Tokenizer,
        val dim: Int,
        val pooling: EmbeddingModel.Pooling
    )

    @Volatile private var env: OrtEnvironment? = null
    @Volatile private var loadedModelId: String? = null
    @Volatile private var loaded: Loaded? = null
    @Volatile private var loadedBpe: BpeTokenizer? = null

    /** 最近一次失败原因（面板/测试用来解释"为什么没启用"） */
    @Volatile var lastError: String? = null
        private set

    fun isReady(context: Context, model: EmbeddingModel = ModelStore.selected(context)): Boolean =
        ModelStore.isReady(context, model)

    /** 释放当前会话（切换模型/删除模型时调） */
    fun close() {
        runCatching { loaded?.session?.close() }
        loaded = null
        loadedModelId = null
        loadedBpe = null
    }

    private fun ensure(context: Context, model: EmbeddingModel): Loaded {
        loaded?.let { if (loadedModelId == model.id) return it }
        synchronized(this) {
            loaded?.let { if (loadedModelId == model.id) return it }
            close()
            val e = env ?: OrtEnvironment.getEnvironment().also { env = it }
            val opts = OrtSession.SessionOptions()
            // 重型模型单条就吃满 CPU 还慢；2 线程是"不卡前台"与"别太慢"的折中
            opts.setIntraOpNumThreads(2)
            val session = e.createSession(ModelStore.modelFile(context, model).absolutePath, opts)
            val tokenizer: Tokenizer = when (model.tokenizer) {
                EmbeddingModel.TokenizerKind.WORDPIECE_ASSET -> {
                    // v53：词表按模型指定（zh=en bge_vocab.txt / en=bge_vocab_en.txt，词表不同不可混用）
                    val t = BertTokenizer.fromAssets(context, model.vocabAsset)
                    object : Tokenizer {
                        override fun encode(text: String, maxLen: Int): LongArray =
                            t.encode(text, maxLen).map { it.toLong() }.toLongArray()
                    }
                }
                EmbeddingModel.TokenizerKind.BPE_FILES -> {
                    val bpe = loadBpe(context, model)
                    object : Tokenizer {
                        override fun encode(text: String, maxLen: Int): LongArray = bpe.encode(text, maxLen)
                    }
                }
            }
            val l = Loaded(session, tokenizer, model.dim, model.pooling)
            loaded = l
            loadedModelId = model.id
            return l
        }
    }

    private fun loadBpe(context: Context, model: EmbeddingModel): BpeTokenizer {
        loadedBpe?.let { return it }
        val dir = ModelStore.dir(context, model)
        val vocab = java.io.File(dir, "vocab.json").readText()
        val merges = java.io.File(dir, "merges.txt").readText()
        return BpeTokenizer.fromJson(vocab, merges).also { loadedBpe = it }
    }

    /**
     * 单条文本 → 归一化向量。
     * [isQuery] 为 true 时给重型模型加 instruction 前缀（Qwen3-Embedding 官方口径）。
     */
    fun embed(
        context: Context,
        text: String,
        model: EmbeddingModel = ModelStore.selected(context),
        isQuery: Boolean = false
    ): FloatArray? {
        if (!ModelStore.isReady(context, model)) return null
        val body = text.trim()
        if (body.isEmpty()) return null
        val input = if (isQuery && model.queryPrefix.isNotEmpty()) model.queryPrefix + body else body
        return runCatching {
            val l = ensure(context, model)
            val e = env ?: error("ORT 环境未初始化")
            val ids = l.tokenizer.encode(input, BpeTokenizer.MAX_TOKENS)
            if (ids.isEmpty()) return null
            val shape = longArrayOf(1, ids.size.toLong())
            val mask = LongArray(ids.size) { 1L }
            val types = LongArray(ids.size) { 0L }
            // Qwen3 的 ONNX 导出要求显式 position_ids（0..n-1）；BERT 系没有这个输入，
            // 所以按 inputNames 决定喂不喂，多出来的张量白建一个也无妨。
            val pos = LongArray(ids.size) { it.toLong() }
            OnnxTensor.createTensor(e, LongBuffer.wrap(ids), shape).use { idsT ->
                OnnxTensor.createTensor(e, LongBuffer.wrap(mask), shape).use { maskT ->
                    OnnxTensor.createTensor(e, LongBuffer.wrap(types), shape).use { typeT ->
                        OnnxTensor.createTensor(e, LongBuffer.wrap(pos), shape).use { posT ->
                            val feeds = HashMap<String, OnnxTensor>(64)
                            val past = mutableListOf<OnnxTensor>()
                            l.session.inputInfo.forEach { (name, info) ->
                                when {
                                    name.contains("input_ids") -> feeds[name] = idsT
                                    name.contains("attention_mask") -> feeds[name] = maskT
                                    name.contains("position_ids") -> feeds[name] = posT
                                    name.contains("token_type_ids") -> feeds[name] = typeT
                                    // Qwen3 的导出带 KV 缓存输入（28 层 × key/value，形状 [B,8,S,128]）。
                                    // 做向量化时缓存为空：喂一个 seq=0 的空张量即可（B=1, S=0）。
                                    name.startsWith("past_key_values") -> {
                                        val declared = (info.info as? ai.onnxruntime.TensorInfo)?.shape
                                            ?: longArrayOf(1, 8, 0, 128)
                                        val dims = LongArray(declared.size) { i ->
                                            if (declared[i] > 0) declared[i] else if (i == 0) 1L else 0L
                                        }
                                        val count = dims.fold(1L) { a, b -> a * b }.toInt()
                                        val t = OnnxTensor.createTensor(
                                            e, java.nio.FloatBuffer.allocate(count), dims
                                        )
                                        past += t
                                        feeds[name] = t
                                    }
                                }
                            }
                            try {
                                val want = l.session.outputNames.firstOrNull { it.contains("last_hidden") }
                                    ?: l.session.outputNames.first()
                                l.session.run(feeds, setOf(want)).use { out ->
                                    val first = out.get(0)
                                    val tensor: OnnxTensor = when (first) {
                                        is OnnxTensor -> first
                                        is Map<*, *> -> first.values.first() as OnnxTensor
                                        else -> error("未知输出类型 " + first.javaClass.name)
                                    }
                                    @Suppress("UNCHECKED_CAST")
                                    val value = tensor.value as Array<Array<FloatArray>>   // [1, seq, dim]
                                    val seq = value[0]
                                    val vec = when (l.pooling) {
                                        EmbeddingModel.Pooling.CLS -> seq[0]
                                        // 无 padding（batch=1），最后一个 token 就是语义位
                                        EmbeddingModel.Pooling.LAST_TOKEN -> seq[seq.size - 1]
                                    }
                                    normalize(vec.copyOf(l.dim))
                                }
                            } finally {
                                past.forEach { runCatching { it.close() } }
                            }
                        }
                    }
                }
            }
        }.onFailure {
            lastError = it.javaClass.simpleName + ": " + (it.message ?: "")
            if (it is Error) close()
        }.getOrNull()
    }

    /** 当前会话的输入/输出摘要（面板出错时显示，便于判断模型导出形态对不对） */
    fun describe(context: Context, model: EmbeddingModel = ModelStore.selected(context)): String = runCatching {
        val l = ensure(context, model)
        val ins = l.session.inputInfo.entries.joinToString(", ") { (k, v) ->
            val shape = (v.info as? ai.onnxruntime.TensorInfo)?.shape?.joinToString("x") ?: "?"
            k + "[" + shape + "]"
        }
        val outs = l.session.outputNames.joinToString(", ")
        "输入: " + ins + "；输出: " + outs
    }.getOrElse { (it.message ?: "无法描述模型") }

    fun embedAll(
        context: Context,
        texts: List<String>,
        model: EmbeddingModel = ModelStore.selected(context)
    ): List<FloatArray> = texts.mapNotNull { embed(context, it, model) }

    internal fun normalize(v: FloatArray): FloatArray {
        var sum = 0.0
        v.forEach { sum += it.toDouble() * it }
        val norm = sqrt(sum).toFloat()
        if (norm > 1e-6f) for (i in v.indices) v[i] /= norm
        return v
    }

    internal fun cosine(a: FloatArray, b: FloatArray): Float {
        val n = minOf(a.size, b.size)
        var dot = 0.0
        var na = 0.0
        var nb = 0.0
        for (i in 0 until n) {
            dot += a[i].toDouble() * b[i]
            na += a[i].toDouble() * a[i]
            nb += b[i].toDouble() * b[i]
        }
        if (na < 1e-9 || nb < 1e-9) return 0f
        return (dot / (sqrt(na) * sqrt(nb))).toFloat()
    }

    fun toBytes(v: FloatArray): ByteArray {
        val bb = java.nio.ByteBuffer.allocate(v.size * 4).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        v.forEach { bb.putFloat(it) }
        return bb.array()
    }

    fun fromBytes(b: ByteArray): FloatArray {
        val bb = java.nio.ByteBuffer.wrap(b).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        val out = FloatArray(b.size / 4)
        for (i in out.indices) out[i] = bb.float
        return out
    }
}
