package io.github.m0use001.planability.ai.embed

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.m0use001.planability.ai.rag.NoteSearch
import io.github.m0use001.planability.data.COVER_SOLID
import io.github.m0use001.planability.data.Graph
import io.github.m0use001.planability.data.NoteEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * v52：**重型模型 Qwen3-Embedding-0.6B（int8 613MB）**的端侧验收。
 *
 * 与轻量模型的验收口径一致（维度 / 同义 vs 无关 / 纯语义召回），额外钉住两件重型特有的事：
 * 1. **last-token 池化**（Qwen3-Embedding 不是 CLS）——池化取错时同义相似度会明显塌陷；
 * 2. **单条耗时**——600M 参数在 CPU 上跑，索引策略（每批 4 篇、后台分批）就靠这个数字定。
 *
 * 模型不入库：测试前推到 `files/embed/qwen3-embedding-0.6b/`（model.onnx + vocab.json + merges.txt）。
 * 没有模型时跳过。
 */
@RunWith(AndroidJUnit4::class)
class HeavyModelInstrumentedTest {

    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext
    // 通过 ALL 取（生产代码遍历 ALL；直接引用 HEAVY 的 getter 会被 R8 裁掉）
    private val heavy = EmbeddingModels.ALL.first { it.dim >= 1024 }
    private var notebookId = 0L
    private val noteIds = mutableListOf<Long>()

    @Before
    fun setUp() {
        Graph.init(ctx)
    }

    @After
    fun tearDown() {
        runBlocking {
            noteIds.forEach { Graph.notes.purgeNote(it) }
            if (notebookId > 0) runCatching { Graph.db.notebookDao().delete(notebookId) }
            ModelStore.select(ctx, EmbeddingModels.ALL.first { it.dim < 1024 })
            Embedder.close()
        }
    }

    @Test
    fun heavyModelEmbedsAndRetrieves() = runBlocking {
        if (!ModelStore.isReady(ctx, heavy)) {
            println("HEAVY_SKIP：模型未推送（" + ModelStore.modelFile(ctx, heavy).absolutePath + "）")
            return@runBlocking
        }
        ModelStore.select(ctx, heavy)
        Embedder.close()
        println("HEAVY_MODEL_DESC=" + Embedder.describe(ctx, heavy))
        runCatching {
            val dir = ModelStore.dir(ctx, heavy)
            val bpe = BpeTokenizer.fromJson(
                java.io.File(dir, "vocab.json").readText(),
                java.io.File(dir, "merges.txt").readText()
            )
            val t1 = bpe.tokens("线性代数里矩阵的秩怎么求")
            val t2 = bpe.tokens("今天晚上吃什么比较好")
            println("HEAVY_TOKENS1=" + t1.size + " " + t1.take(10))
            println("HEAVY_TOKENS2=" + t2.size + " " + t2.take(10))
        }.onFailure { println("HEAVY_TOKEN_ERR=" + it.message) }

        val t0 = System.currentTimeMillis()
        val a = Embedder.embed(ctx, "线性代数里矩阵的秩怎么求", heavy, false)
        val t1 = System.currentTimeMillis()
        val b = Embedder.embed(ctx, "矩阵的秩的计算方法", heavy, false)
        val c = Embedder.embed(ctx, "今天晚上吃什么比较好", heavy, false)
        val t2 = System.currentTimeMillis()
        assertTrue("向量应为 1024 维（实际 " + (a?.size ?: "null") + "，错误：" + Embedder.lastError + "）",
            a != null && a.size == 1024 && b != null && c != null)

        // 查询侧走 instruction 前缀（与索引侧不对称，这是 Qwen3-Embedding 的官方口径）
        val q = Embedder.embed(ctx, "怎么算一个矩阵的秩", heavy, true)
        assertTrue(q != null)
        val va = a!!
        val vc = c!!
        val vq = q!!
        val simDoc = Embedder.cosine(vq, va)
        val simOther = Embedder.cosine(vq, vc)
        println("HEAVY_MS_FIRST=" + (t1 - t0) + " HEAVY_MS_SECOND=" + (t2 - t1))
        println("HEAVY_SIM_RELATED=" + Embedder.cosine(va, b!!) + " HEAVY_SIM_QUERY_DOC=" + simDoc +
            " HEAVY_SIM_QUERY_OTHER=" + simOther)
        assertTrue("同义句应明显高于无关句", Embedder.cosine(va, b) > Embedder.cosine(va, vc) + 0.1f)
        assertTrue("查询-文档相似度应高于查询-无关文档", simDoc > simOther + 0.1f)

        // 真库链路：建索引用重型模型，纯语义问法能找回
        notebookId = Graph.notes.createNotebook("重型向量测试", COVER_SOLID, 210f)
        val math = createNote("线性代数复习", "矩阵的秩怎么求？先做行变换化成阶梯形，非零行数就是秩。")
        createNote("晚饭记录", "今天吃了番茄炒蛋和米饭，味道不错。")
        val ti = System.currentTimeMillis()
        val n = NoteVectors.ensureIndexed(ctx, heavy, 10)
        println("HEAVY_INDEX_MS=" + (System.currentTimeMillis() - ti) + " COUNT=" + n)
        assertTrue("两篇都该算出来", NoteVectors.indexedCountFor(ctx, heavy) >= 2)

        val hits = NoteSearch.search("高斯消元处理方程组", 5, "")
        println("HEAVY_HITS=" + hits.joinToString { "《" + it.note.title + "》:" + it.matchedBy })
        assertTrue("纯语义问法应命中数学笔记", hits.any { it.note.id == math })
    }

    private suspend fun createNote(title: String, content: String): Long {
        val id = Graph.notes.createNote(notebookId, title, "md")
        Graph.notes.saveNote(NoteEntity(id = id, notebookId = notebookId, title = title, content = content))
        noteIds += id
        return id
    }
}
