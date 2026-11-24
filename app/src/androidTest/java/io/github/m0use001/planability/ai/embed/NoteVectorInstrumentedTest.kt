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
 * v51：**语义检索接到真库真模型**的端到端断言。
 *
 * 单测只能验融合公式；这里验的是「笔记写进库 → 算出向量 → 用一个**不含任何原词**的
 * 近义问法仍能命中」，也就是用户真正能感知到的那个能力。
 *
 * 需要模型文件在 `files/embed/bge-small-zh-v1.5-int8.onnx`（24MB，不入库）：
 * ```
 * adb push model.onnx /data/local/tmp/bge.onnx
 * adb shell run-as io.github.m0use001.planability cp /data/local/tmp/bge.onnx files/embed/bge-small-zh-v1.5-int8.onnx
 * adb shell am instrument -w -e class io.github.m0use001.planability.ai.embed.NoteVectorInstrumentedTest \
 *   io.github.m0use001.planability.test/androidx.test.runner.AndroidJUnitRunner
 * ```
 * 没有模型时整个测试跳过（不 red）。
 */
@RunWith(AndroidJUnit4::class)
class NoteVectorInstrumentedTest {

    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext
    private var notebookId = 0L
    private val noteIds = mutableListOf<Long>()

    @Before
    fun setUp() {
        runBlocking {
            Graph.init(ctx)
            notebookId = Graph.notes.createNotebook("向量测试", COVER_SOLID, 210f)
        }
    }

    @After
    fun tearDown() {
        runBlocking {
            noteIds.forEach { Graph.notes.purgeNote(it) }
            runCatching { Graph.db.notebookDao().delete(notebookId) }
        }
    }

    private suspend fun note(title: String, content: String): Long {
        val id = Graph.notes.createNote(notebookId, title, "md")
        Graph.notes.saveNote(NoteEntity(id = id, notebookId = notebookId, title = title, content = content))
        noteIds += id
        return id
    }

    @Test
    fun semanticSearchFindsNoteWithoutSharedWords() = runBlocking {
        if (!ModelStore.isReady(ctx, ModelStore.selected(ctx))) {
            println("VEC_SKIP：模型未推送")
            return@runBlocking
        }
        val math = note("线性代数复习", "矩阵的秩怎么求？先做行变换化成阶梯形，非零行数就是秩。")
        val life = note("晚饭记录", "今天吃了番茄炒蛋和米饭，味道不错。")

        val model = ModelStore.selected(ctx)
        val indexed = NoteVectors.ensureIndexed(ctx, model, 50)
        println("VEC_MODEL=" + model.id + " DIM=" + model.dim + " INDEXED=" + indexed + " COUNT=" + NoteVectors.indexedCountFor(ctx, model))
        assertTrue("两篇笔记都应算出向量", NoteVectors.indexedCountFor(ctx, model) >= 2)

        // ①向量路单独验：换句几乎没有共同字面的说法，只有语义能连上
        val vecHits = NoteVectors.search(ctx, "高斯消元处理方程组", model, 5)
        println("VEC_ONLY=" + vecHits.joinToString { it.first.toString() + ":" + "%.3f".format(it.second) })
        assertTrue("向量路应命中数学笔记（原文里没有「高斯/消元」这些字）", vecHits.any { it.first == math })

        // ②融合路：正常问法要能检索到数学笔记
        // 三个参数全传：debug 也开 R8，只用默认参数的调用形态可能被裁掉（$default 合成方法）。
        val hits = NoteSearch.search("怎么算一个矩阵的秩", 5, "")
        println("VEC_HITS=" + hits.joinToString { "《" + it.note.title + "》:" + it.matchedBy })
        assertTrue("应检索到数学笔记", hits.any { it.note.id == math })

        // 无关笔记不该被顶到第一
        assertTrue("无关笔记不应排第一", hits.firstOrNull()?.note?.id != life)
    }
}
