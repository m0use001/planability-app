package io.github.m0use001.planability.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.m0use001.planability.core.AppTime
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * v53：**循环待办跨天流程**的真库验收（用户反复报的那条背单词）。
 *
 * 用户原话：「不仅今天看不到，切到昨天填完完成率自动填进今天当打卡了」。
 * 这组测试把他的话翻成可执行的断言，把四个环节一次钉死：
 *
 * 1. 补昨天的账 → 行滚到**今天**（不吞今天的实例），昨天的百分比不带到今天；
 * 2. 今天这份**未完成**（不能因为昨天打了卡就今天也算打卡）；
 * 3. 完成今天 → 行滚到明天，但**今天仍能查到已完成的副本**（不再"消失"）；
 * 4. 对今天的副本撤销 → 行拉回今天、完成流水撤掉（撤销真的生效）。
 *
 * 走真库（Graph），自建自清。
 */
@RunWith(AndroidJUnit4::class)
class RepeatingTodoFlowTest {

    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext
    private var todoId = 0L

    private val today get() = AppTime.today()
    private val yesterday get() = today.minusDays(1)
    private val todayKey get() = AppTime.dayKey(today)
    private val yesterdayKey get() = AppTime.dayKey(yesterday)

    @Before
    fun setUp() {
        Graph.init(ctx)
    }

    @After
    fun tearDown() {
        runBlocking { if (todoId > 0) runCatching { Graph.plan.purgeTodo(todoId) } }
    }

    /** 建一条「每天」待办，行日期在昨天（模拟"昨天没勾、今天来补"的场景） */
    private suspend fun createDaily(percent: Int = 30): Todo {
        todoId = Graph.plan.saveTodo(
            Todo(
                title = "背单词（回归测试）",
                date = yesterdayKey,
                repeatType = "daily",
                percentDone = percent,
                supervisionEnabled = true,
                dataRecordEnabled = true,
                dataRecordKind = "text",
                dataRecordLabel = "背了多少"
            )
        )
        return Graph.plan.todoById(todoId)!!
    }

    @Test
    fun backfillYesterdayThenTodayStillPending() = runBlocking {
        val t = createDaily()
        // 视图在昨天：先把百分比落到昨天，再补打卡
        Graph.plan.setPercentDone(t.id, 100, yesterdayKey)
        val instance = Graph.plan.todosOfDay(yesterdayKey).first { it.id == t.id }
        Graph.plan.completeTodo(instance, yesterday)

        val row = Graph.plan.todoById(t.id)!!
        assertEquals("补昨天的账后，行应滚到今天（不能跳到明天）", todayKey, row.date)
        assertFalse("今天这份不能被算成已打卡", row.done)
        assertEquals("昨天的 100% 不能带到今天这一期", 0, row.percentDone)

        val todayView = Graph.plan.todosOfDay(todayKey).firstOrNull { it.id == t.id }
        assertNotNull("今天应该能看到这条循环待办", todayView)
        assertFalse("今天这份是未完成的", todayView!!.done)
    }

    @Test
    fun completeTodayKeepsDoneCopyVisibleAndUndoWorks() = runBlocking {
        val t = createDaily()
        // 先把行拉到今天（直接建一条今天的实例）
        Graph.plan.saveTodo(t.copy(date = todayKey))
        val todayInstance = Graph.plan.todosOfDay(todayKey).first { it.id == t.id }
        assertFalse(todayInstance.done)

        Graph.plan.completeTodo(todayInstance, today)
        val after = Graph.plan.todoById(t.id)!!
        assertEquals("完成今天后行滚到明天", AppTime.dayKey(today.plusDays(1)), after.date)

        val copy = Graph.plan.todosOfDay(todayKey).firstOrNull { it.id == t.id }
        assertNotNull("今天的已完成副本必须还能查到（用户『今天看不到』的根因）", copy)
        assertTrue("副本是已完成状态", copy!!.done)
        assertEquals(todayKey, copy.date)
        // 列表口径：今天的循环副本算「进行中」，勾了也不会从今天列表消失
        assertTrue(
            "计划页『进行中』今天范围应包含它",
            TodoListFilters.inActiveList(copy, todayKey, "today")
        )

        // 撤销：拿今天的副本去撤销（仓储要求传显示副本）
        Graph.plan.completeTodo(copy, today)
        val undone = Graph.plan.todoById(t.id)!!
        assertEquals("撤销后行回到今天", todayKey, undone.date)
        assertFalse("撤销后是未完成", undone.done)
        assertTrue(
            "撤销后今天的完成流水应删掉",
            Graph.plan.todosOfDay(todayKey).first { it.id == t.id }.done.not()
        )
    }
}
