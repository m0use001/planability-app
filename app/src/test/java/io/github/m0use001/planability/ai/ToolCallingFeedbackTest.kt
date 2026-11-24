package io.github.m0use001.planability.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 工具调用「参数生成期」的活反馈（2026-11-11 用户：「工具运行时也要有反馈，不然以为卡死」）。
 *
 * 实录（真机会话，tools_audit）：三轮 file_library_write+memory_add，每轮之间模型
 * 流式吐数千字 JSON 参数耗时 1.5–2.5 分钟——这期间消息行 content 为空、工具尚未执行
 * （LiveToolRow 不显示），界面只剩「思考中」，用户以为卡死。
 *
 * 修法：ChatUiState.callingTool 记录首个 ToolCallDelta 的工具名；UI 把空消息行的
 * 「思考中」升级为「正在调用 X · N 秒」。本测试钉住：
 * ① 引擎在参数期置位、在轮终/执行交接/出错/停止各点清零（不留陈值）；
 * ② UI 两个入口（主会话 + 悬浮窗）都把状态传进 MessageBubble；
 * ③ ThinkingRow 有工具名时渲染「正在调用 …」而不是「思考中」。
 */
class ToolCallingFeedbackTest {

    private fun source(rel: String): String {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null && !File(dir, "app/src/main/java/io/github/m0use001/planability").isDirectory) {
            dir = dir.parentFile
        }
        val f = File(requireNotNull(dir) { "找不到仓库根" }, rel)
        assertTrue("源码不在了：$rel", f.isFile)
        return f.readText(Charsets.UTF_8)
    }

    // ---------- 行为：状态字段 ----------

    @Test
    fun `callingTool 默认空且可置值（数据类行为）`() {
        assertEquals("", ChatUiState().callingTool)
        assertEquals("file_library_write", ChatUiState(callingTool = "file_library_write").callingTool)
        // streaming 判定不受新字段影响
        assertTrue(ChatUiState(streamingId = 7L).streaming)
    }

    // ---------- 结构：引擎置位与清零 ----------

    @Test
    fun `引擎在首个工具名到达时置位 callingTool`() {
        val engine = source("app/src/main/java/io/github/m0use001/planability/ai/ChatEngine.kt")
        // 锚定主循环的累积器（文件里还有另一条不带工具累积的简单流路径 ToolCallDelta -> Unit）
        val accIdx = engine.indexOf("toolAcc.getOrPut(ev.index)")
        assertTrue("主循环找不到工具调用累积器", accIdx > 0)
        val handler = engine.substring(accIdx, engine.indexOf("is StreamEvent.Usage ->", accIdx))
        assertTrue(
            "ToolCallDelta 分支没有置位 callingTool——参数生成期仍是纯「思考中」",
            handler.contains("acc.name.append(it)") &&
                handler.contains("callingTool.isBlank()") &&
                handler.contains("callingTool = it")
        )
    }

    @Test
    fun `引擎在两个发送入口与三个终态都清零 callingTool`() {
        val engine = source("app/src/main/java/io/github/m0use001/planability/ai/ChatEngine.kt")
        // 两个发送入口（send 与 regenerate）建 streaming 行时重置，防上一轮残留
        val sendSites = Regex("""streamingId = assistantId,\s*\n\s*callingTool = "",?""")
            .findAll(engine).count()
        assertTrue("发送入口没重置 callingTool（找到 $sendSites 处，需 2 处）", sendSites == 2)
        // 三个终态：stopped / error / 正常结束
        val stopSite = engine.contains("f.value = f.value.copy(streamingId = null, callingTool = \"\")")
        val errorSite = engine.contains("f.value = f.value.copy(streamingId = null, callingTool = \"\", notice = errorMsg)")
        val endSite = engine.contains("f.value = f.value.copy(streamingId = null, callingTool = \"\")")
        assertTrue("终态（stopped）没清零 callingTool", stopSite)
        assertTrue("终态（error）没清零 callingTool", errorSite)
        // 轮内交接：参数期结束 → 执行期（LiveToolRow 接棒）与最终回答两条路径都要清
        val finalizeWithTools = engine.contains(
            "// 参数生成期结束：callingTool 交给 LiveToolRow（执行期）接棒"
        )
        val finalAnswer = engine.contains(
            "if (f.value.callingTool.isNotBlank()) f.value = f.value.copy(callingTool = \"\")"
        )
        assertTrue("工具轮交接处没清 callingTool", finalizeWithTools)
        assertTrue("最终回答路径没清 callingTool", finalAnswer)
    }

    // ---------- 结构：UI 接线 ----------

    @Test
    fun `主会话与悬浮窗都把 callingTool 传进 MessageBubble`() {
        val screen = source("app/src/main/java/io/github/m0use001/planability/ui/info/ChatScreen.kt")
        assertTrue(
            "主会话 MessageBubble 没接 callingTool",
            screen.contains("callingTool = state.callingTool")
        )
        val float = source("app/src/main/java/io/github/m0use001/planability/ui/info/FloatChatActivity.kt")
        assertTrue(
            "悬浮窗 MessageBubble 没接 callingTool（悬浮窗里工具参数期同样静止）",
            float.contains("callingTool = state.callingTool")
        )
    }

    @Test
    fun `ThinkingRow 有工具名时显示正在调用与秒数，无工具名维持思考中`() {
        val screen = source("app/src/main/java/io/github/m0use001/planability/ui/info/ChatScreen.kt")
        val row = screen.substringAfter("private fun ThinkingRow(")
            .substringBefore("\n}\n\n/**")
        assertTrue("ThinkingRow 没有 callingTool 参数", row.contains("callingTool: String"))
        assertTrue("没渲染「正在调用」文案", row.contains("正在调用 "))
        assertTrue("没配秒数", row.contains("秒") && row.contains("System.currentTimeMillis()"))
        assertTrue("没接中文工具名映射", row.contains("toolLabel("))
        assertTrue("无工具名时要维持「思考中」", row.contains("\"思考中\""))
        // 空消息行调用点要带上行 createdAt 做秒数锚
        assertTrue(
            "ThinkingRow 调用点没传 startedAt 锚",
            screen.contains("ThinkingRow(callingTool, msg.createdAt)")
        )
    }

    @Test
    fun `计时的 tick 必须在组合期被读取（只写不读的 State 不触发重组，秒数会冻死）`() {
        val screen = source("app/src/main/java/io/github/m0use001/planability/ui/info/ChatScreen.kt")
        // 2026-11-11 真机实报「计时被卡死」：tick 只自增不被读，LaunchedEffect 每秒的
        // 自增无法让任何 recompose scope 失效 → 秒数停在第 0 帧。ThinkingRow /
        // LiveToolRow / LiveAgentRow 都必须在 secs 计算里读一次 tick 订阅重组。
        // 2026-11-12：LiveToolRow 改 internal 供浮窗复用；新增第 4 处=过程链活跃轮的
        // 「正在工作 N 秒」（ProcessChainBlock），同病同修，一并钉住。
        val funs = listOf(
            "private fun ThinkingRow(", "internal fun LiveToolRow(",
            "private fun LiveAgentRow(", "private fun ProcessChainBlock(",
        )
        for (f in funs) {
            val body = screen.substringAfter(f).substringBefore("\n}\n")
            assertTrue(
                "$f 的秒数计算没有读 tick（秒数会冻死）",
                Regex("""secs = (run \{)?\s*\n?\s*tick""").containsMatchIn(body) || body.contains("tick ")
            )
        }
    }
}
