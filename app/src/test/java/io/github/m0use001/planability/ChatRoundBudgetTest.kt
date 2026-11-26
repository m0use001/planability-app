package io.github.m0use001.planability

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 2026-11-01 用户：「让 AI 移动一下文件，弹出一堆申请，全点完对话就卡住了」的防回归钉。
 *
 * 根因（DB 取证：会话 288「线性代数项目文件整理」）：工具轮预算写死 6 轮，该会话正好
 * 6 个工具轮（探测 4 轮 + fs_move 6 连发 1 轮 + …），预算耗尽后最后的总结轮没跑，
 * 循环尾把**空的 streaming 占位行**静默 finalize 成 done+空内容——界面上就是"没下文"。
 * 修复 = ①预算 6→12（防死循环由 LoopDetector 熔断兜底）；②耗尽收尾时空行落可见说明。
 *
 * 2026-11-08 二次扩容 12→24：用户「工具调用次数上限锁死」——抓完整份章节表这类任务
 * 每页一次 web_fetch、一份表 20+ 轮，12 轮到顶就断在半路。防死循环仍靠 LoopDetector。
 * 引擎循环依赖 DB/网络，JVM 测不了，按本项目惯例钉源码。
 */
class ChatRoundBudgetTest {

    @Test
    fun `工具轮预算提到24且耗尽要落可见说明`() {
        val root = generateSequence(File(System.getProperty("user.dir") ?: ".").absoluteFile) { it.parentFile }
            .firstOrNull { File(it, "app/src/main/java/io/github/m0use001/planability").isDirectory }
        assertTrue("找不到仓库根", root != null)
        val f = File(root, "app/src/main/java/io/github/m0use001/planability/ai/ChatEngine.kt")
        assertTrue("ChatEngine.kt 不在了", f.isFile)
        val code = f.readText(Charsets.UTF_8)
            .lines().filterNot { it.trimStart().startsWith("//") }.joinToString("\n")
        assertTrue(
            "工具轮预算被压回 12 以下了——抓一页表要十几轮，会断在半路",
            code.contains("rounds@ for (round in 0 until 24)")
        )
        assertTrue(
            "轮数耗尽的可见说明没了——空占位行会被静默 finalize 成 done+空内容（界面=卡住）",
            code.contains("本轮工具调用已达 24 轮上限")
        )
    }
}
