package io.github.m0use001.planability.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.m0use001.planability.core.AppTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 2026-11-14：打卡撤销**归一**的设备端活体探针（模拟器跑，禁真机——会写库）。
 *
 * 覆盖单元测试够不到的 Room 真库路径（PlanRepo.completeTodo 的 undo 分支）：
 *
 * 1. **满态清空**：percent=100 打卡再撤销 → 行值归 0（TodoProgress.undoRollbackPercent），
 *    两条流水（done_log / percent_log）清空——用户视角「撤销真的生效」，胶囊回空。
 * 2. **半途也清空**：percent=50 打卡再撤销 → 行值也归 0（用户 2026-11-14 定稿
 *    「撤销打卡后百分比与胶囊都要清空」，原「半途保留」子规则已废弃）。
 * 3. **残留清理**：没开数据记录（dataRecordEnabled=false）但 checkin_records 里有
 *    当天残留（历史版本/开关后来关掉造成）→ 撤销后**无条件**清掉——否则浮层
 *    「改记录数据」与 AI 在没开开关的待办上仍看得到记录
 *    （用户实测「我没开数据记录按钮，却让我记录数据」）。
 */
@RunWith(AndroidJUnit4::class)
class CheckinUndoProbeTest {

    private fun repo() = Graph.plan

    /** 建一条测试待办（标题带前缀，结束清场） */
    private suspend fun make(title: String, percent: Int, record: Boolean): Todo =
        repo().saveTodo(
            Todo(
                title = title,
                date = 0, due = 0, endAt = 0,
                percentDone = percent,
                supervisionEnabled = record,   // 数据记录挂在监督下（编辑器同口径）
                dataRecordEnabled = record
            )
        ).let { repo().todoById(it)!! }

    private fun cleanup(titlePrefix: String) {
        // 探针自清理，不污染模拟器库
        Graph.db.openHelper.writableDatabase.run {
            execSQL("DELETE FROM todo_done_logs WHERE todoId IN (SELECT id FROM todos WHERE title LIKE '$titlePrefix%')")
            execSQL("DELETE FROM todo_percent_logs WHERE todoId IN (SELECT id FROM todos WHERE title LIKE '$titlePrefix%')")
            execSQL("DELETE FROM checkin_records WHERE todoId IN (SELECT id FROM todos WHERE title LIKE '$titlePrefix%')")
            execSQL("DELETE FROM supervision_checks WHERE todoId IN (SELECT id FROM todos WHERE title LIKE '$titlePrefix%')")
            execSQL("DELETE FROM todos WHERE title LIKE '$titlePrefix%'")
        }
    }

    @Test
    fun fullPercentUndoResetsRowAndClearsLogs() {
        val pre = "QA_FULL_"
        cleanup(pre)
        kotlinx.coroutines.runBlocking {
            val t = make(pre + "满态", 100, record = false)
            val today = AppTime.today()
            // 打卡：done=true + 完成流水 + 百分比流水（正规链）
            repo().completeTodo(t, today)
            val afterCheck = repo().todoById(t.id)!!
            assertTrue("打卡后应 done=true", afterCheck.done)
            assertEquals(100, afterCheck.percentDone)
            assertTrue("完成流水没写（打卡面板/AI/已归档会看不到）",
                Graph.db.doneLogDao().byDate(AppTime.dayKey(today)).any { it.todoId == t.id })
            assertEquals("百分比流水没写", 100,
                Graph.db.percentLogDao().ofDay(AppTime.dayKey(today)).first { it.todoId == t.id }.percent)
            // 撤销（传显示副本）：行值归 0、两流水清
            val view = afterCheck.copy(done = true, doneAt = afterCheck.updatedAt)
            repo().completeTodo(view, today)
            val afterUndo = repo().todoById(t.id)!!
            assertFalse("撤销后仍 done", afterUndo.done)
            assertEquals("满态撤销后行值必须归 0（否则胶囊仍满勾、完成率仍 100%）",
                0, afterUndo.percentDone)
            assertTrue("完成流水撤销后残留",
                Graph.db.doneLogDao().byDate(AppTime.dayKey(today)).none { it.todoId == t.id })
            assertTrue("百分比流水撤销后残留",
                Graph.db.percentLogDao().ofDay(AppTime.dayKey(today)).none { it.todoId == t.id })
        }
        cleanup("QA_")
    }

    @Test
    fun halfPercentUndoAlsoClears() {
        kotlinx.coroutines.runBlocking {
            val t = make("QA_HALF_半途", 50, record = false)
            val today = AppTime.today()
            repo().completeTodo(t, today)
            val view = repo().todoById(t.id)!!.copy(done = true, doneAt = AppTime.nowMillis())
            repo().completeTodo(view, today)
            val after = repo().todoById(t.id)!!
            assertFalse(after.done)
            // 用户定稿「撤销打卡后百分比与胶囊都要清空」：半途也归 0，胶囊回空轮廓
            assertEquals("撤销后半途百分比必须清空（否则胶囊还填着五成，像没撤销）",
                0, after.percentDone)
            assertTrue("撤销后百分比流水残留",
                Graph.db.percentLogDao().ofDay(AppTime.dayKey(today)).none { it.todoId == t.id })
        }
        cleanup("QA_")
    }

    @Test
    fun undoClearsOrphanCheckinRecord() {
        kotlinx.coroutines.runBlocking {
            // 没开数据记录（record=false），但库里有当天残留记录（历史版本/开关后来关掉）
            val t = make("QA_ORPHAN_残留", -1, record = false)
            val today = AppTime.today()
            val todayKey = AppTime.dayKey(today)
            Graph.db.checkinDao().insert(CheckinFormat.blank(t, todayKey, AppTime.nowMillis()))
            assertTrue(Graph.db.checkinDao().ofTodo(t.id).any { it.date == todayKey })
            // 打卡→撤销：残留记录必须被无条件清掉（原实现挂在快照 dataRecordEnabled 上会赖着）
            repo().completeTodo(t, today)
            val view = repo().todoById(t.id)!!.copy(done = true, doneAt = AppTime.nowMillis())
            repo().completeTodo(view, today)
            // 注意：读断言走 ofTodo（主代码保活）——debug 构建开 R8，直调仅测试用的
            // DAO 方法会 NoSuchMethodError（仓库既有教训）
            assertEquals("撤销后数据记录残留——没开数据记录的待办仍能「改记录数据」",
                0, Graph.db.checkinDao().ofTodo(t.id).size)
        }
        cleanup("QA_")
    }
}
