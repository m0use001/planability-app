package io.github.m0use001.planability.ai.ptl

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.m0use001.planability.core.toLocalDate
import io.github.m0use001.planability.data.Graph
import io.github.m0use001.planability.data.Todo
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 计划域工具的**端到端**断言（instrumented，模拟器跑）：参数 JSON → PlanTools →
 * 真 Room → 读回来。2026-10-31 用户实锤「让 AI 写待办，它说连重复待办都建不了」，
 * 这组测试钉的是整条链——JVM 单测（TodoRepeatToolContractTest）锁语法往返，
 * 这里锁「工具真的把规则写进了库、query 真的读得回来」。
 *
 * 走真库（Graph.init 指向模拟器上的 planability.db），自建自清，不碰既有数据。
 */
@RunWith(AndroidJUnit4::class)
class PlanToolsE2ETest {

    private val sessionId = 987_654_321L

    @Before
    fun setUp() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        Graph.init(ctx)
    }

    private suspend fun exec(name: String, args: JSONObject): ToolExecResult =
        ToolExecImpls.execute(sessionId, name, args)

    @Test
    fun `todo_create 重复参数落库，query 读得回来`() = runBlocking {
        val r = exec(
            "todo_create",
            JSONObject().put("title", "测_重复待办E2E").put("date", "2026-11-02")
                .put("repeat", "weekly:1,3,5").put("labels", "工作,测试")
        )
        assertTrue("todo_create 失败：" + r.payload, r.ok)
        val t = Graph.plan.activeTodos().first { it.title == "测_重复待办E2E" }
        assertEquals("weekly", t.repeatType)
        assertEquals("1,3,5", t.repeatDays)
        assertTrue("缺省应永久", t.permanent)
        assertEquals("工作,测试", t.labels)

        val q = exec("todo_query", JSONObject())
        assertTrue("query 没把重复规则说出来：" + q.payload, q.payload.contains("重复:"))

        // 清理
        Graph.plan.purgeTodo(t.id)
    }

    @Test
    fun `todo_edit 改重复规则与日期`() = runBlocking {
        exec(
            "todo_create",
            JSONObject().put("title", "测_编辑待办E2E").put("date", "2026-11-02").put("repeat", "weekly:1")
        )
        val t = Graph.plan.activeTodos().first { it.title == "测_编辑待办E2E" }
        val r = exec(
            "todo_edit",
            JSONObject().put("id", t.id).put("repeat", "interval:14").put("date", "2026-11-05")
                .put("title", "测_编辑待办E2E改")
        )
        assertTrue("todo_edit 失败：" + r.payload, r.ok)
        val nt = Graph.plan.todoById(t.id)!!
        assertEquals("interval", nt.repeatType)
        assertEquals(14, nt.repeatInterval)
        assertEquals("2026-11-05 应落成新锚点", 5, nt.date.toLocalDate().dayOfMonth)
        Graph.plan.purgeTodo(t.id)
    }

    @Test
    fun `todo_delete 进回收站且可撤销`() = runBlocking {
        exec("todo_create", JSONObject().put("title", "测_删除待办E2E"))
        val t = Graph.plan.activeTodos().first { it.title == "测_删除待办E2E" }
        val r = exec("todo_delete", JSONObject().put("id", t.id))
        assertTrue(r.ok)
        assertTrue("删完应进回收站", Graph.plan.todoById(t.id)!!.trashed)
        // 撤销（内存栈精确快照路径）
        val undone = ToolExecutor.undoLast(sessionId)
        assertNotNull("撤销应命中", undone)
        assertFalse("撤销后应回来", Graph.plan.todoById(t.id)!!.trashed)
        Graph.plan.purgeTodo(t.id)
    }

    @Test
    fun `checkin_add 记一笔数值，checkin_query 读得回来`() = runBlocking {
        // 数据记录待办先经 UI 口径建好（AI 不负责开 dataRecord，编辑器开）
        val tid = Graph.plan.saveTodo(
            Todo(title = "测_打卡待办E2E", dataRecordEnabled = true, dataRecordKind = "number", dataRecordUnit = "kg")
        )
        val r = exec(
            "checkin_add",
            JSONObject().put("todo_id", tid).put("value", 62.5)
        )
        assertTrue("checkin_add 失败：" + r.payload, r.ok)
        val q = exec("checkin_query", JSONObject().put("todo_id", tid))
        assertTrue("query 应含数值：" + q.payload, q.payload.contains("62.5"))
        Graph.plan.purgeTodo(tid)
    }

    @Test
    fun `schedule_create 带重复与截止日，schedule_delete 进回收站`() = runBlocking {
        val r = exec(
            "schedule_create",
            JSONObject().put("title", "测_重复日程E2E").put("start", "2026-11-02 09:00")
                .put("repeat", "weekly:1").put("repeat_until", "2026-11-20")
        )
        assertTrue("schedule_create 失败：" + r.payload, r.ok)
        assertTrue("回执应带重复描述：" + r.payload, r.payload.contains("每周"))
        val s = Graph.db.scheduleDao().all().first { it.title == "测_重复日程E2E" }
        assertTrue("repeatRule 应含 weekly:1：" + s.repeatRule, s.repeatRule.startsWith("weekly:1"))
        assertTrue("repeatRule 应含 until：" + s.repeatRule, s.repeatRule.contains("until:"))
        val q = exec("schedule_query", JSONObject().put("start", "2026-11-02").put("end", "2026-11-08"))
        assertTrue("query 没把重复规则说出来：" + q.payload, q.payload.contains("每周"))

        val d = exec("schedule_delete", JSONObject().put("id", s.id))
        assertTrue(d.ok)
        assertTrue("删完应进回收站", Graph.db.scheduleDao().byId(s.id)!!.trashed)
        val undone = ToolExecutor.undoLast(sessionId)
        assertNotNull(undone)
        assertFalse("撤销后应回来", Graph.db.scheduleDao().byId(s.id)!!.trashed)
        Graph.plan.purgeSchedule(s.id)
    }

    @Test
    fun `schedule_delete_today 支持任意日期`() = runBlocking {
        exec(
            "schedule_create",
            JSONObject().put("title", "测_例外日程E2E").put("start", "2026-11-06 10:00").put("repeat", "weekly:5")
        )
        val s = Graph.db.scheduleDao().all().first { it.title == "测_例外日程E2E" }
        val before = s.excludedDates
        val r = exec(
            "schedule_delete_today",
            JSONObject().put("id", s.id).put("date", "2026-11-13")
        )
        assertTrue(r.ok)
        val after = Graph.db.scheduleDao().byId(s.id)!!.excludedDates
        assertTrue("例外日应记进 excludedDates：$before → $after", after.length > before.length)
        Graph.plan.purgeSchedule(s.id)
    }

    @Test
    fun `plan_category_list 与 screen_time_query 不崩且给话`() = runBlocking {
        val c = exec("plan_category_list", JSONObject())
        assertTrue(c.ok)
        val st = exec("screen_time_query", JSONObject())
        // 模拟器多半没有「使用情况访问」权限——允许失败，但必须是**友好失败**（带指引），不能异常
        assertTrue("要么成功要么给权限指引：" + st.payload, st.ok || st.payload.contains("使用情况访问"))
    }

    @Test
    fun `tag 参数找不到时报错并列出现有标签`() = runBlocking {
        val r = exec(
            "todo_create",
            JSONObject().put("title", "测_标签待办E2E").put("tag", "绝不存在的标签XYZ")
        )
        assertFalse("不存在的标签必须报错，不能静默建成无标签", r.ok)
        assertTrue("错误要教模型怎么查：" + r.payload, r.payload.contains("plan_category_list"))
    }
}
