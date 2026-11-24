package io.github.m0use001.planability.ai.rag

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.m0use001.planability.ai.embed.Embedder
import io.github.m0use001.planability.ai.embed.EmbeddingModels
import io.github.m0use001.planability.ai.embed.ModelStore
import io.github.m0use001.planability.ai.embed.NoteVectors
import io.github.m0use001.planability.data.COVER_SOLID
import io.github.m0use001.planability.data.Graph
import io.github.m0use001.planability.data.NoteEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * v53 端侧重排第一步的**语义门 A/B 回归**（真库真模型，直调 [KbAsk.ask]，不经 LLM）。
 *
 * 回答的问题（可行性研究 §7 第一步验收）：**零成本版能不能救回「词面不重叠的真命中」**。
 * 用例：库里只有一篇**英文**梯度下降笔记（查询是中文，词面重叠=0，FTS/BM25 天生瞎）——
 * - OFF（模型未下载）：召回为空 → 证据空 → 拒答（旧行为，真命中被漏）；
 * - ON（模型已就位）：笔记级向量路召回英文笔记 + 语义门（≥[KbAsk.SEMANTIC_GATE]）放行 → 出证据。
 * 余弦实测值全量打印进失败消息：门 0.45 定得对不对、跨语言实际落多少，都以它为准。
 *
 * 模型文件（不入库）：
 * ```
 * adb push model_int8.onnx /data/local/tmp/bge.onnx
 * adb shell run-as io.github.m0use001.planability mkdir -p files/embed/bge-small-zh
 * adb shell run-as io.github.m0use001.planability cp /data/local/tmp/bge.onnx files/embed/bge-small-zh/model.onnx
 * adb shell am instrument -w -e class io.github.m0use001.planability.ai.rag.KbGateInstrumentedTest \
 *   io.github.m0use001.planability.test/androidx.test.runner.AndroidJUnitRunner
 * ```
 * 没有模型时 ON 侧跳过（OFF 侧照跑）；有模型时 OFF 侧跳过——两次运行拼成完整 A/B。
 */
@RunWith(AndroidJUnit4::class)
class KbGateInstrumentedTest {

    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext
    private var notebookId = 0L
    private var enNoteId = 0L
    private val noteIds = mutableListOf<Long>()

    @Before
    fun setUp() {
        runBlocking {
            Graph.init(ctx)
            notebookId = Graph.notes.createNotebook("语义门A-B", COVER_SOLID, 210f)
            enNoteId = note(
                "Gradient descent",
                "Gradient descent is an iterative optimization algorithm. " +
                    "It repeatedly steps against the slope of the loss surface to minimize " +
                    "the error, adjusting parameters by the learning rate."
            )
            note(
                "Renaissance painting",
                "Renaissance painters developed linear perspective, layering glazes and " +
                    "chiaroscuro to give flat panels an illusion of depth and volume."
            )
            note(
                "图书馆开放时间",
                "图书馆开放时间与借阅规则：周一至周五 8:00-22:00，借书上限 10 册，期末周延长开放。"
            )
        }
    }

    @After
    fun tearDown() {
        runBlocking {
            runCatching {
                Graph.db.noteEmbeddingDao().clear()
                noteIds.forEach { Graph.notes.purgeNote(it) }
            }
            runCatching { Graph.db.notebookDao().delete(notebookId) }
        }
    }

    private suspend fun note(title: String, content: String): Long {
        val id = Graph.notes.createNote(notebookId, title, "md")
        Graph.notes.saveNote(NoteEntity(id = id, notebookId = notebookId, title = title, content = content))
        noteIds += id
        return id
    }

    private fun modelReady() = ModelStore.isReady(ctx, EmbeddingModels.byId("bge-small-zh"))

    /** 中文查询查英文笔记：词面零重叠 */
    private val crossQuery = "梯度下降是如何工作的"

    @Test
    fun `OFF_无模型_词面零重叠只能拒答_词法对照仍放行`() = runBlocking {
        assumeTrue("有模型时 OFF 侧跳过（A/B 的 OFF 半边要在无模型状态跑）", !modelReady())
        val r = KbAsk.ask(crossQuery, topK = 6, tag = "")
        assertTrue(
            "OFF 侧：召回应为空（FTS/BM25 对零词面重叠天生失效）→ 证据空 → 拒答；实测 inDomain=" +
                r.inDomain + " evidence=" + r.evidence.size + " coverage=" + r.coverage,
            !r.inDomain && r.evidence.isEmpty()
        )
        val control = KbAsk.ask("图书馆 借书", topK = 6, tag = "")
        assertTrue(
            "OFF 侧：词法对照查询应正常放行（拒答门不误伤正常检索）；实测 inDomain=" +
                control.inDomain + " evidence=" + control.evidence.size,
            control.inDomain
        )
    }

    @Test
    fun `ON_有模型_语义门救回跨语言真命中`() = runBlocking {
        assumeTrue("无模型时 ON 侧跳过（先推模型文件再跑）", modelReady())
        // R8 会把伴生属性访问器内联掉（NoSuchMethodError 之坑），测试侧必须走生产保留的 byId
        val model = EmbeddingModels.byId("bge-small-zh")
        NoteVectors.ensureIndexed(ctx, model, limit = 10)
        // 负例对照：无关笔记（英文 Renaissance + 中文图书馆）的余弦——
        // 门限必须把「相关跨语言」与「无关」分开才有意义：间隔大而门拦下=重标定门（零成本），
        // 两者接近=信号本身不存在，才上 265MB cross-encoder。
        suspend fun cosineOf(id: Long): Float {
            val q = Embedder.embed(ctx, crossQuery, model, isQuery = true) ?: return -1f
            val doc = Graph.db.noteEmbeddingDao().all(model.id).firstOrNull { it.noteId == id }
                ?: return -1f
            return Embedder.cosine(q, Embedder.fromBytes(doc.vec))
        }
        val cosRelated = cosineOf(enNoteId)
        val cosUnrelated = noteIds.filter { it != enNoteId }.maxOf { cosineOf(it) }
        val r = KbAsk.ask(crossQuery, topK = 6, tag = "")
        println("KbGateAB related=" + cosRelated + " unrelated=" + cosUnrelated +
            " margin=" + (cosRelated - cosUnrelated) + " gate=" + KbAsk.SEMANTIC_GATE +
            " inDomain=" + r.inDomain + " coverage=" + r.coverage +
            " evidence=" + r.evidence.joinToString("／") { it.title + "(" + it.source + ")" })
        assertTrue(
            "跨语言 A/B 实测：相关英文笔记余弦=" + cosRelated +
                "，无关笔记最高余弦=" + cosUnrelated +
                "，间隔=" + (cosRelated - cosUnrelated) +
                "，门=" + KbAsk.SEMANTIC_GATE + "；inDomain=" + r.inDomain +
                " coverage=" + r.coverage + " evidence=" +
                r.evidence.joinToString("／") { it.title + "(" + it.source + ")" } +
                "。判定：间隔大而门拦下 → 门限按跨语言负例重标定（仍零成本）；" +
                "间隔小 → 才上 265MB cross-encoder。",
            cosRelated > cosUnrelated && r.evidence.any { it.noteId == enNoteId }
        )
        val control = KbAsk.ask("图书馆 借书", topK = 6, tag = "")
        assertTrue(
            "ON 侧：词法对照查询应照常放行（向量路不压过精确命中）；实测 inDomain=" +
                control.inDomain + " evidence=" + control.evidence.size,
            control.inDomain
        )
    }
}
