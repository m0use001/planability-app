package io.github.m0use001.planability.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * v49 知识库：真实 Room + 真实 SQLite 的 FTS4 / 链接图验证。
 *
 * 单测只能验纯函数——FTS4 的 MATCH 语法、外部内容虚表、COLLATE NOCASE 这些
 * **只有真 SQLite 才认**，写错了在 JVM 测试里完全看不出来（当年记忆 FTS 就是这么验的）。
 *
 * 用内存库（不碰真实数据），按 AGENTS §4.2 只对模拟器跑：
 * ```
 * ANDROID_SERIAL=emulator-5554 ./gradlew :app:connectedDebugAndroidTest \
 *   -Pandroid.testInstrumentationRunnerArguments.class=io.github.m0use001.planability.data.NoteKbInstrumentedTest
 * ```
 */
@RunWith(AndroidJUnit4::class)
class NoteKbInstrumentedTest {

    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun ftsDualRouteAndLikeFallback() = runBlocking {
        val nb = db.notebookDao().upsert(NotebookEntity(name = "测试本"))
        val a = db.noteDao().upsert(NoteEntity(notebookId = nb, title = "线性代数复习", content = "矩阵与向量空间的基础"))
        db.noteDao().upsert(NoteEntity(notebookId = nb, title = "杂记", content = "今天天气不错"))

        // 标题路：FTS4 把整个 CJK 串当一个 token，所以必须用前缀语法（应用分词给的就是前缀）
        val byTitle = db.noteKbDao().ftsSearch("title:线性*")
        assertTrue("标题路应命中", byTitle.any { it.id == a })

        // 正文路
        val byBody = db.noteKbDao().ftsSearch("content:矩阵*")
        assertTrue("正文路应命中", byBody.any { it.id == a })

        // 干扰项不该被召回
        assertTrue("无关笔记不许命中", byBody.none { it.title == "杂记" })

        // 中文退化路径
        val like = db.noteKbDao().likeSearch("向量")
        assertTrue("LIKE 兜底应命中", like.any { it.id == a })

        // 回收站里的笔记不出现在检索结果
        db.noteDao().trash(a, 123L)
        assertTrue("已删除的笔记不应被检出", db.noteKbDao().ftsSearch("title:线性*").isEmpty())
    }

    @Test
    fun linkGraphResolveAndReverse() = runBlocking {
        val nb = db.notebookDao().upsert(NotebookEntity(name = "测试本"))
        val a = db.noteDao().upsert(NoteEntity(notebookId = nb, title = "目标笔记", content = ""))
        val b = db.noteDao().upsert(NoteEntity(notebookId = nb, title = "引用者", content = "看 [[目标笔记]]"))

        // 悬空引用 → 目标出现后回填
        db.noteKbDao().insertLinks(listOf(NoteLink(fromId = b, toId = 0, target = "目标笔记", at = 1)))
        val resolved = db.noteKbDao().resolveLinks("目标笔记", a)
        assertEquals(1, resolved)
        assertEquals(a, db.noteKbDao().inLinks(a).single().toId)
        assertTrue("回填后不再是悬空", db.noteKbDao().danglingTargets().isEmpty())

        // 按标题查（大小写不敏感 / COLLATE NOCASE）
        assertEquals(a, db.noteKbDao().byTitle("目标笔记")?.id)
        val upper = db.noteDao().upsert(NoteEntity(notebookId = nb, title = "Kotlin", content = ""))
        assertEquals(upper, db.noteKbDao().byTitle("kotlin")?.id)
        db.noteDao().delete(upper)

        // 反链 / 出链
        assertEquals(1, db.noteKbDao().outLinks(b).size)
        assertEquals(1, db.noteKbDao().inLinks(a).size)
    }
}
