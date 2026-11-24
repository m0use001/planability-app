package io.github.m0use001.planability.ai.embed

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * v51：**真模型**端侧向量化验收（ORT + bge-small-zh-v1.5 int8）。
 *
 * 验的是自写链路的四个可能翻车的点：
 * 1. `assets/bge_vocab.txt` 能被解析、中文逐字切；
 * 2. ONNX Runtime 能在设备上建会话（仿真器走 ARM 转译，能跑就说明真机更没问题）；
 * 3. 模型输入名对得上（input_ids/attention_mask/token_type_ids，不硬编码假设）；
 * 4. **语义是对的**：同义句相似度必须高于无关句——不然向量化等于白跑。
 *
 * 模型 24MB 不入库：测试前把文件推到 app 私有目录 `files/embed/bge-small-zh-v1.5-int8.onnx`，
 * 没有文件时跳过（模拟器/真机手工准备，和 PptReadInstrumentedTest 同一套做法）。
 */
@RunWith(AndroidJUnit4::class)
class EmbedInstrumentedTest {

    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun embedsChineseSemantically() {
        val model = ModelStore.selected(ctx)
        val f = ModelStore.modelFile(ctx, model)
        if (!f.exists() || f.length() < 20_000_000L) {
            println("EMBED_SKIP：模型未推送（" + f.absolutePath + "）")
            return
        }
        println("EMBED_MODEL=" + model.id + " DIM=" + model.dim)

        val t0 = System.currentTimeMillis()
        val a = Embedder.embed(ctx, "线性代数里矩阵的秩怎么求", model, false)
        val t1 = System.currentTimeMillis()
        val b = Embedder.embed(ctx, "矩阵的秩的计算方法", model, false)
        val c = Embedder.embed(ctx, "今天晚上吃什么比较好", model, false)
        val t2 = System.currentTimeMillis()

        assertTrue("向量维度应等于模型声明（" + model.dim + "）", a != null && a.size == model.dim)
        assertTrue(b != null && c != null)
        val simRelated = Embedder.cosine(a!!, b!!)
        val simUnrelated = Embedder.cosine(a, c!!)
        println("EMBED_MS_FIRST=" + (t1 - t0) + " EMBED_MS_SECOND=" + (t2 - t1))
        println("EMBED_SIM_RELATED=" + simRelated + " EMBED_SIM_UNRELATED=" + simUnrelated)
        assertTrue("同义句相似度应明显高于无关句（related=$simRelated unrelated=$simUnrelated）",
            simRelated > simUnrelated + 0.1f)
    }

    @Test
    fun tokenizerReadsAssetVocab() {
        val tok = BertTokenizer.fromAssets(ctx)
        assertTrue("词表应有 2 万条以上", tok.vocabSize > 20000)
        // 参数写全：生产代码只用两参形态，一参的合成方法会被 R8 裁掉
        val ids = tok.encode("线性代数", BertTokenizer.MAX_TOKENS)
        assertTrue("编码应含 [CLS]/[SEP]", ids.first() == tok.clsId && ids.last() == tok.sepId)
        assertTrue("中文应逐字切出 4 个字元（+CLS+SEP）", ids.size == 6)
    }
}
