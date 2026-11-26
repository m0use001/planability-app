package io.github.m0use001.planability

import io.github.m0use001.planability.data.DayBundle
import io.github.m0use001.planability.data.HourTask
import io.github.m0use001.planability.data.Todo
import io.github.m0use001.planability.ui.home.scoreBreakdown
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.LocalDate

/**
 * 完成率不能超 100% 的回归测试（真机出现过 200%）。
 *
 * 根因是**双重计数**：completeTodo 每次都写一条完成流水，非重复待办完成后 done=true 还留在当日列表——
 * 评分的 `todos.count{done}` 已经数过它，把流水总数 `doneCount` 原样再加一遍就是数两遍。
 * 修复在生产端：DayBundle.doneCount 改用 DoneLogDao.countRolledOffOn（只数已滚出当日列表的重复待办），
 * 消费端 scoreBreakdown 再补一道 rate 封顶兜底。
 */
class CompletionRateTest {

    private fun repoRoot(): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            if (File(dir, "app/src/main/java/io/github/m0use001/planability").isDirectory) return dir
            dir = dir.parentFile
        }
        throw AssertionError("找不到仓库根——这条测试要读源码，工作目录不对别当'约定没被破坏'")
    }

    // M5：data/ 实体/DAO 已迁 shared，读取时 app 路径不存在则回落到 shared 同包路径。
    private fun src(rel: String): String {
        val app = File(repoRoot(), "app/src/main/java/io/github/m0use001/planability/$rel")
        val f = if (app.isFile) app else File(repoRoot(), "shared/src/jvmShared/kotlin/io/github/m0use001/planability/$rel")
        return f.readText(Charsets.UTF_8)
    }

    private fun bundle(todos: List<Todo>, doneCount: Int, hours: List<HourTask> = emptyList()) =
        DayBundle(
            date = LocalDate.of(2026, 10, 23),
            schedules = emptyList(),
            todos = todos,
            hourTasks = hours,
            events = emptyList(),
            checks = emptyList(),
            doneCount = doneCount
        )

    @Test
    fun `完成率封顶 100 即使入参被翻倍污染`() {
        val bd = scoreBreakdown(
            bundle(listOf(Todo(id = 1, title = "a", done = true)), doneCount = 1),
            screenMinutes = 0, sleep = null, screenLimitMin = 300,
            screenPenaltyPer30 = 0, sleepGoalMin = 0, sleepPenaltyPer30 = 0
        )
        assertTrue("rate=${bd.rate} 超了 1——封顶没生效", bd.rate <= 1f)
        assertEquals(100, (bd.rate * 100).toInt())
    }

    @Test
    fun `doneCount 用滚出口径 不再数全部流水`() {
        val repo = src("data/PlanRepo.kt")
        // 9101b90e 把口径从「countRolledOffOn 一条 SQL」换成逐行版
        // `rolled.size - rolledViews.size`（滚期完成的历史实例已还原成显示副本时不能再数一遍，
        // 只有待办已被删除的那些才靠 doneCount 补位）。两者都满足"不数全部流水"这条要求，
        // 所以两种写法都接受，但绝不能退回 countOn。
        val rolledFormula = Regex("""doneCount\s*=\s*rolled\.size\s*-\s*rolledViews\.size""")
        val legacyFormula = Regex("""doneCount\s*=\s*doneLogDao\.countRolledOffOn""")
        assertTrue(
            "loadDay 的 doneCount 既不是滚出口径、也不是逐行版滚出口径——会重新引入 200% 完成率",
            rolledFormula.containsMatchIn(repo) || legacyFormula.containsMatchIn(repo)
        )
        assertTrue(
            "doneCount 又回到 countOn（数全部流水）——会重新引入 200% 完成率",
            !Regex("""doneCount\s*=\s*doneLogDao\.countOn""").containsMatchIn(repo)
        )
        val dao = src("data/PlanDaos.kt")
        assertTrue("DoneLogDao 少了 countRolledOffOn 查询", dao.contains("countRolledOffOn"))
    }
}
