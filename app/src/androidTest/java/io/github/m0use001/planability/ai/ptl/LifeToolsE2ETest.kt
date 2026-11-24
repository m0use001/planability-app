package io.github.m0use001.planability.ai.ptl

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.m0use001.planability.data.Graph
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 「生活数据」第二批工具的端到端断言（instrumented，模拟器真库）：
 * 专注补记/查询、周小结、成就经验、草稿确认流、回收站、笔记本 + 笔记编辑删除。
 * 自建自清，不碰既有数据。
 */
@RunWith(AndroidJUnit4::class)
class LifeToolsE2ETest {

    private val sessionId = 987_654_322L

    @Before
    fun setUp() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        Graph.init(ctx)
    }

    private suspend fun exec(name: String, args: JSONObject): ToolExecResult =
        ToolExecImpls.execute(sessionId, name, args)

    @Test
    fun `focus_add 补记并 focus_query 读得回来`(): Unit = runBlocking {
        val r = exec("focus_add", JSONObject().put("planned_min", 25))
        assertTrue("focus_add 失败：" + r.payload, r.ok)
        val q = exec("focus_query", JSONObject().put("days", 1))
        assertTrue("query 应含分钟数：" + q.payload, q.payload.contains("25"))
        // 清理：删掉刚补的这条（按最近一条删）
        val s = Graph.db.focusSessionDao().all().maxByOrNull { it.start }
        s?.let { Graph.db.focusSessionDao().deleteById(it.id) }
    }

    @Test
    fun `weekly_query 与 achievement_query 给话不崩`(): Unit = runBlocking {
        assertTrue(exec("weekly_query", JSONObject()).ok)
        val a = exec("achievement_query", JSONObject())
        assertTrue(a.ok)
        assertTrue("应含等级：" + a.payload, a.payload.contains("等级"))
    }

    @Test
    fun `pending_add 进待确认列表且可撤销`(): Unit = runBlocking {
        val r = exec(
            "pending_add",
            JSONObject().put("title", "测_草稿E2E").put("kind", "todo").put("timeText", "周三晚上")
        )
        assertTrue("pending_add 失败：" + r.payload, r.ok)
        val item = Graph.info.pendingItems().firstOrNull { it.title == "测_草稿E2E" }
        assertNotNull("草稿应落库待确认", item)
        val undone = ToolExecutor.undoLast(sessionId)
        assertNotNull(undone)
        assertTrue("撤销后草稿应消失", Graph.info.pendingItems().none { it.title == "测_草稿E2E" })
    }

    @Test
    fun `trash 查询与恢复待办`(): Unit = runBlocking {
        exec("todo_create", JSONObject().put("title", "测_回收待办E2E"))
        val t = Graph.plan.activeTodos().first { it.title == "测_回收待办E2E" }
        exec("todo_delete", JSONObject().put("id", t.id))
        val q = exec("trash_query", JSONObject())
        assertTrue("回收站应列出它：" + q.payload, q.payload.contains("测_回收待办E2E"))
        val r = exec("trash_restore", JSONObject().put("kind", "todo").put("id", t.id))
        assertTrue("trash_restore 失败：" + r.payload, r.ok)
        assertFalse("恢复后应活着", Graph.plan.todoById(t.id)!!.trashed)
        Graph.plan.purgeTodo(t.id)
    }

    @Test
    fun `notebook_create 后 note_create 指名写入，note_edit 移动`(): Unit = runBlocking {
        val nb = exec("notebook_create", JSONObject().put("name", "测_笔记本E2E"))
        assertTrue(nb.ok)
        val r = exec(
            "note_create",
            JSONObject().put("title", "测_笔记E2E").put("content", "内容")
                .put("notebook", "测_笔记本E2E")
        )
        assertTrue("note_create 指名失败：" + r.payload, r.ok)
        val note = Graph.notes.allNotes().first { it.title == "测_笔记E2E" }
        val target = Graph.notes.notebooks().first { it.name == "测_笔记本E2E" }
        assertEquals_nb(note.notebookId, target.id)

        val e = exec(
            "note_edit",
            JSONObject().put("id", note.id).put("title", "测_笔记E2E改")
        )
        assertTrue(e.ok)
        assertEquals_nb(Graph.notes.noteById(note.id)!!.title, "测_笔记E2E改")

        val d = exec("note_delete", JSONObject().put("id", note.id))
        assertTrue(d.ok)
        assertTrue(Graph.notes.noteById(note.id)!!.trashed)
        Graph.notes.purgeNote(note.id)
        Graph.notes.deleteNotebook(target.id)
    }

    private fun assertEquals_nb(x: Any, y: Any) {
        assertTrue("断言失败：$x != $y", x == y)
    }
}
