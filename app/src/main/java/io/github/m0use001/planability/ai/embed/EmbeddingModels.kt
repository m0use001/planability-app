package io.github.m0use001.planability.ai.embed

/**
 * v52 知识库语义检索：可选的本地向量模型登记表。
 *
 * 两档，用户自己在面板里选：
 * - **轻量 bge-small-zh**（24MB，512 维，CLS 池化）：索引快（实测 5ms/条），日常够用；
 * - **重型 Qwen3-Embedding-0.6B**（int8 613MB，1024 维，last-token 池化 + 查询指令前缀）：
 *   中文语义与跨语言更强，但下载大、单条约 0.3–1s，全库索引要几分钟。
 *
 * 两个模型**不通用向量**：维度与空间都不同，切换必须清空重算（`note_embeddings.modelId` 隔离）。
 *
 * 下载源统一 hf-mirror（国内可达）；[ModelStore] 只允许 https + 白名单 host，
 * 拒绝 localhost / 环回 / 私网 / 保留地址。
 */
data class EmbeddingModel(
    val id: String,
    val label: String,
    val desc: String,
    val dim: Int,
    val approxMb: Int,
    /** 主模型文件名（固定 model.onnx） */
    val modelUrl: String,
    /** 分词器文件：url → 本地文件名 */
    val tokenizerFiles: List<Pair<String, String>>,
    val tokenizer: TokenizerKind,
    val pooling: Pooling,
    /** 查询侧前缀（Qwen3-Embedding 官方建议；文档侧不加） */
    val queryPrefix: String = "",
    /** WordPiece 词表在 assets 的文件名（zh/en 各一份，词表不同不可混用） */
    val vocabAsset: String = "bge_vocab.txt"
) {
    enum class TokenizerKind { WORDPIECE_ASSET, BPE_FILES }
    enum class Pooling { CLS, LAST_TOKEN }
}

object EmbeddingModels {

    val LIGHT = EmbeddingModel(
        id = "bge-small-zh",
        label = "轻量中文 · bge-small-zh（24MB）",
        desc = "索引快（每条几毫秒），中文同义/近义够用。适合日常笔记量。",
        dim = 512,
        approxMb = 24,
        modelUrl = "https://hf-mirror.com/Xenova/bge-small-zh-v1.5/resolve/main/onnx/model_int8.onnx",
        tokenizerFiles = emptyList(),   // 词表随 APK（assets/bge_vocab.txt，110KB）
        tokenizer = EmbeddingModel.TokenizerKind.WORDPIECE_ASSET,
        pooling = EmbeddingModel.Pooling.CLS
    )

    /**
     * v53 英文档（用户 2026-11-05「向量模型也加上适配英文的」）：
     * bge-small-en-v1.5（33MB，384 维，BERT 英文词表 30522）——与中文档同架构同分词器类型，
     * 接入零新机制。检索场景加官方查询前缀（bge-en 系口径，文档侧不加）。
     * 英文笔记为主 / 中英混排时选它；纯中文内容仍建议中文档（词表覆盖更全）。
     * 跨语言（中文查询查英文笔记）走重型 Qwen3-Embedding（多语）。
     */
    val EN = EmbeddingModel(
        id = "bge-small-en",
        label = "轻量英文 · bge-small-en（33MB）",
        desc = "英文笔记/中英混排的语义检索（每条几毫秒）。纯中文内容建议选中文档。",
        dim = 384,
        approxMb = 33,
        modelUrl = "https://hf-mirror.com/Xenova/bge-small-en-v1.5/resolve/main/onnx/model_int8.onnx",
        tokenizerFiles = emptyList(),   // 词表随 APK（assets/bge_vocab_en.txt，30522 条）
        tokenizer = EmbeddingModel.TokenizerKind.WORDPIECE_ASSET,
        pooling = EmbeddingModel.Pooling.CLS,
        queryPrefix = "Represent this sentence for searching relevant passages: ",
        vocabAsset = "bge_vocab_en.txt"
    )

    val HEAVY = EmbeddingModel(
        id = "qwen3-embedding-0.6b",
        label = "重型 · Qwen3-Embedding-0.6B（约 590MB）",
        desc = "理解力更强（跨语言、长句、抽象问法），但下载大、每条 0.3–1 秒，" +
            "全库索引要几分钟。建议在 WiFi 下下载。",
        dim = 1024,
        approxMb = 590,
        modelUrl = "https://hf-mirror.com/onnx-community/Qwen3-Embedding-0.6B-ONNX/resolve/main/onnx/model_quantized.onnx",
        tokenizerFiles = listOf(
            "https://hf-mirror.com/onnx-community/Qwen3-Embedding-0.6B-ONNX/resolve/main/vocab.json" to "vocab.json",
            "https://hf-mirror.com/onnx-community/Qwen3-Embedding-0.6B-ONNX/resolve/main/merges.txt" to "merges.txt"
        ),
        tokenizer = EmbeddingModel.TokenizerKind.BPE_FILES,
        pooling = EmbeddingModel.Pooling.LAST_TOKEN,
        queryPrefix = "Instruct: Given a note or question, retrieve the notes that answer it\nQuery: "
    )

    val ALL = listOf(LIGHT, EN, HEAVY)

    fun byId(id: String?): EmbeddingModel = ALL.firstOrNull { it.id == id } ?: LIGHT

    val DEFAULT_ID = LIGHT.id
}
