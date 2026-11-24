package io.github.m0use001.planability

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 自动化执行日志（v35）的源码级断言。
 *
 * ## 为什么这些得钉在源码层
 * 这套东西的每一处**漏掉都不会编译失败、也不会单测红**，只会在真机上表现成
 * 「日志里少了某些条」——而"少"恰恰是最难被发现的那种故障：
 *
 *  - 只记成功不记失败 → 界面上看起来一切正常，只有失败的任务永远查不到原因；
 *  - 少了 prune → 表无限长，15 分钟一轮自动写，跑一晚上几千行；
 *  - 工具忘了进域清单 → 模型看不到它，界面上有、对话里叫不动；
 *  - 工具进错档 → 一个只读查询也开始弹确认卡。
 *
 * 运行期断言跨不过去（要真跑一条自动化 + 一个真库），所以照本仓库既有的做法
 * （`CoverStyleCoverageTest` / `MigrationShapeTest`）钉在源码文本上。
 */
class AutomationRunLogTest {

    private fun repoFile(rel: String): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            val f = File(dir, rel)
            if (f.isFile) return f
            dir = dir.parentFile
        }
        throw AssertionError("找不到 $rel（工作目录 ${System.getProperty("user.dir")}）")
    }

    private val worker by lazy {
        repoFile("app/src/main/java/io/github/m0use001/planability/notify/AutomationWorker.kt")
            .readText(Charsets.UTF_8)
    }
    private val repo by lazy {
        repoFile("app/src/main/java/io/github/m0use001/planability/data/AutomationEntity.kt")
            .readText(Charsets.UTF_8)
    }
    private val toolDefs by lazy {
        repoFile("app/src/main/java/io/github/m0use001/planability/ai/ptl/ToolDefs.kt")
            .readText(Charsets.UTF_8)
    }
    private val toolExec by lazy {
        repoFile("app/src/main/java/io/github/m0use001/planability/ai/ptl/ToolExecImpls.kt")
            .readText(Charsets.UTF_8)
    }
    private val permMenu by lazy {
        repoFile("app/src/main/java/io/github/m0use001/planability/ui/info/ChatPermMenu.kt")
            .readText(Charsets.UTF_8)
    }

    @Test
    fun `两条执行路径的成功与失败都要落记录`() {
        // 四个分支一个都不能少。最容易漏的是「失败」两条——
        // 而失败恰恰是唯一值得回溯的那些次。
        for (branch in listOf("flow", "ai")) {
            for (ok in listOf("true", "false")) {
                val needle = "record(a, \"$branch\", $ok,"
                assertTrue(
                    "AutomationWorker 里没有 $needle —— " +
                        if (ok == "false") "失败不落记录 = 日志里查不到「为什么没跑成」，等于没做这个功能"
                        else "成功不落记录 = 日志永远是空的",
                    worker.contains(needle)
                )
            }
        }
    }

    @Test
    fun `写日志本身失败时不能把执行路径带崩`() {
        // record 必须整体包在 runCatching 里：日志写失败而炸掉刚执行完的自动化，
        // 比没有日志更糟。断言它是个返回 Boolean 的私有函数 + 内层 runCatching。
        assertTrue("record 不见了", worker.contains("private suspend fun record("))
        assertTrue(
            "record 没包 runCatching —— 建表/写库失败会把执行路径一起抛出去",
            worker.contains("}getOrElse {") || worker.contains("}getOrElse{") ||
                worker.contains("}.getOrElse {")
        )
        assertTrue(
            "record 要把日志失败吞掉并返回 false（调用方靠它避免重复记录）",
            worker.contains("false") && worker.contains("写执行日志失败")
        )
    }

    @Test
    fun `执行日志表会被封顶`() {
        assertTrue("AutomationRepo.logRun 缺失", repo.contains("fun logRun("))
        assertTrue(
            "logRun 没调 prune —— 表会无限长（15 分钟一轮自动写）",
            repo.contains("runDao().prune(")
        )
        assertTrue(
            "prune 的 SQL 要按时间倒序留最新的：不是 WHERE id NOT IN (... ORDER BY at DESC ...)",
            repo.contains("ORDER BY at DESC, id DESC LIMIT")
        )
    }

    @Test
    fun `automation_log 工具声明在案且进了 automation 域`() {
        assertTrue(
            "ToolDefs 里没有 automation_log 声明",
            toolDefs.contains("name = \"automation_log\"")
        )
        assertTrue(
            "automation_log 没进 automation 域清单 —— 域开关关掉时它会被整组摘掉，" +
                "但开着的时候模型也必须看得见它",
            toolDefs.contains("\"automation_log\"")
        )
        // 只在**它自己那个 ToolDef 块内**判 risk：整段正则往后扫会扫到下一个工具（plugin_toggle
        // 的 CONFIRM）的 risk 上，那种断言看着对、其实判的是别人。
        val logDef = toolDefs
            .substringAfter("name = \"automation_log\"")
            .substringBefore("ToolDef(")
        assertTrue(
            "automation_log 应是只读的（不写 risk = 默认 AUTO）——设成 CONFIRM 会让「查日志」弹确认卡",
            !logDef.contains("risk = Risk")
        )
        assertTrue(
            "ToolExecImpls 里没有 automation_log 的实现分支",
            toolExec.contains("\"automation_log\" ->")
        )
        assertTrue(
            "automation_log 是只读的，不该出现在 ChatPermMenu 的 WRITE_TOOLS 里",
            !permMenu.contains("\"automation_log\"")
        )
    }
}
