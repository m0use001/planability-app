package io.github.m0use001.planability.ui.info

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.m0use001.planability.core.AppTime
import io.github.m0use001.planability.data.ChatMessage
import io.github.m0use001.planability.data.Graph
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * v53 造数脚本（instrumented）：在模拟器上建一条**带勾选清单计划的会话**，
 * 供人工/截图验收顶栏「计划 m/n」胶囊与工作流面板。
 *
 * 它不做断言——这是给 UI 验收准备环境用的（真机上现在没有这类会话，胶囊按设计不出现）。
 * 跑法：
 * ```
 * adb -s emulator-5556 shell am instrument -w \
 *   -e class io.github.m0use001.planability.ui.info.SeedPlanSessionTest \
 *   io.github.m0use001.planability.test/androidx.test.runner.AndroidJUnitRunner
 * ```
 */
@RunWith(AndroidJUnit4::class)
class SeedPlanSessionTest {

    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun setUp() {
        Graph.init(ctx)
    }

    @Test
    fun seedSessionWithChecklist() = runBlocking {
        val now = AppTime.nowMillis()
        val session = Graph.chat.createSession(title = "工作流胶囊验收")
        Graph.chat.insertMessage(
            ChatMessage(
                sessionId = session.id, role = "user",
                content = "帮我排一下周四交的实验报告", createdAt = now
            )
        )
        Graph.chat.insertMessage(
            ChatMessage(
                sessionId = session.id, role = "assistant",
                content = "好的，按四步推进：\n" +
                    "- [x] 收集上周的实验数据\n" +
                    "- [x] 画误差曲线\n" +
                    "- [ ] 写讨论部分\n" +
                    "- [ ] 通读一遍并导出 PDF",
                createdAt = now + 1000
            )
        )
        println("SEED_SESSION_ID=" + session.id)
    }
}
