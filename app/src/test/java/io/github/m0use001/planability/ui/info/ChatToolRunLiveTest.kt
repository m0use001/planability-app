package io.github.m0use001.planability.ui.info

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 工具运行期「看起来跟卡了一样」根修守卫（2026-11-12 用户：「AI 调用工具/工具运行时
 * 还是缺运行中动画与计时，看起来跟卡了一样」）。
 *
 * 诊断：用户盯着看的是过程链块（ProcessChainBlock，折叠成「已工作 N 秒」一行）。
 * 两条冻结缺陷：
 * ① 链头「已工作 N 秒」的 N 是**已落库消息之间的跨度**（remember(msgs) 缓存），
 *   工具执行期间数字不跳；② 链头图标静态（Schedule 图标），无运行动画。
 * 而带动画+计时的 LiveToolRow 挂在列表**最底部**，用户往上翻时根本看不见；
 * 浮窗会话此前完全没有执行期活行。
 *
 * 修法：链头活跃轮（streamingId 指着链内消息）→ LoadingIndicator + 从链首到当前时刻
 * 的实时秒数（每秒跳）；浮窗复用同一份 LiveToolRow 挂在 reverseLayout 首个 item
 * （= 视觉底部），挂载条件与主窗一致。
 */
class ChatToolRunLiveTest {

    private fun source(rel: String): String {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null && !File(dir, "app/src/main/java/io/github/m0use001/planability").isDirectory) {
            dir = dir.parentFile
        }
        val f = File(requireNotNull(dir) { "找不到仓库根" }, rel)
        assertTrue("源码不在了：$rel", f.isFile)
        // Windows checkout 是 CRLF，结构化断言先归一化（历次踩坑）
        return f.readText(Charsets.UTF_8).replace("\r\n", "\n")
    }

    // ---------- 过程链活跃态 ----------

    @Test
    fun `ProcessChainBlock 接收 active 标志且由 streamingId 在链内判定`() {
        val src = source("app/src/main/java/io/github/m0use001/planability/ui/info/ChatScreen.kt")
        assertTrue("ProcessChainBlock 必须接收 active 参数",
            src.contains("private fun ProcessChainBlock(\n    msgs: List<ChatMessage>,\n    onOpenFile: (String) -> Unit,\n    active: Boolean = false"))
        // 调用点必须用「streamingId 在链内任一条消息」判活跃
        val callSite = Regex("""ProcessChainBlock\(\s*\n\s*node\.msgs""").find(src)?.range?.first
            ?: error("ProcessChainBlock 调用点不见了（活跃判定丢失）")
        val window = src.substring(callSite, callSite + 500)
        assertTrue("活跃判定必须用 streamingId 与链内消息 id 比对",
            window.contains("state.streamingId != null") &&
                window.contains("node.msgs.any { it.id == state.streamingId }"))
    }

    @Test
    fun `链头活跃轮挂 LoadingIndicator 且秒数从链首到当前时刻实时跳`() {
        val src = source("app/src/main/java/io/github/m0use001/planability/ui/info/ChatScreen.kt")
        val body = src.substringAfter("val liveLabel = if (active) {").substringBefore("} else null")
        assertTrue("活跃轮秒数必须读 tick（只写不读的 State 不触发重组 → 冻死）",
            body.contains("tick"))
        assertTrue("活跃轮秒数必须从链首 createdAt 到「当前时刻」算（不是已存消息跨度）",
            body.contains("System.currentTimeMillis() - chainStart"))
        assertTrue("活跃轮标签必须是「正在工作 … 秒」（不是冻结的「已工作 … 秒」）",
            body.contains("正在工作"))
        // 链头活跃轮必须挂 LoadingIndicator（leading 槽），非活跃才用静态 workLabel
        assertTrue("链头必须按 liveLabel 切 LoadingIndicator leading",
            src.contains("leading = if (liveLabel != null)"))
        assertTrue("leading 里必须是 LoadingIndicator",
            src.contains("LoadingIndicator(Modifier.size(14.dp), color = MaterialTheme.colorScheme.primary)"))
    }

    @Test
    fun `链结束后仍用冻结跨度_不再每秒重组`() {
        val src = source("app/src/main/java/io/github/m0use001/planability/ui/info/ChatScreen.kt")
        // 冻结跨度（remember(msgs) 缓存）必须仍在：链结束后给静态「已工作 N 秒」
        assertTrue("冻结跨度 spanMs 必须保留（remember(msgs) 缓存）", src.contains("remember(msgs) {\n        (msgs.maxOfOrNull"))
        assertTrue("链头标签在非活跃轮必须回退到冻结 workLabel",
            src.contains("label = liveLabel ?: workLabel"))
        // tick 只在活跃时跑，结束后停（LaunchedEffect(active) 里 if (active)）
        val eff = src.substringAfter("LaunchedEffect(active) {")
        assertTrue("tick 必须受 active 守护（链结束停跑、不再每秒重组）",
            eff.take(120).contains("if (active) while (true)"))
    }

    @Test
    fun `ChainStepRow 加了 leading 槽_静态 Icon 走 else`() {
        val src = source("app/src/main/java/io/github/m0use001/planability/ui/info/ChatScreen.kt")
        val body = src.substringAfter("private fun ChainStepRow(").substringBefore("private fun ChainDetail")
        assertTrue("ChainStepRow 必须有 leading 槽（@Composable 回调，默认 null）",
            body.contains("leading: (@Composable () -> Unit)? = null"))
        assertTrue("leading 非空时画 leading、否则画静态 Icon",
            body.contains("if (leading != null)") && body.contains("else {\n            Icon("))
    }

    // ---------- 浮窗执行期活行 ----------

    @Test
    fun `浮窗会话挂同款 LiveToolRow_挂载条件与主窗一致`() {
        val src = source("app/src/main/java/io/github/m0use001/planability/ui/info/FloatChatActivity.kt")
        val idx = src.indexOf("LiveToolRow(streamRow, state.messages, callingTool = state.callingTool)")
        assertTrue("浮窗 OutputPaneBody 没挂 LiveToolRow（执行期整窗静止）", idx > 0)
        val window = src.substring(maxOf(0, idx - 700), idx + 60)
        assertTrue("浮窗挂载条件必须与主窗一致：streamingId 非空 + 行带 toolCallsJson",
            window.contains("state.streamingId != null") &&
                window.contains("streamRow.toolCallsJson.isNotBlank()"))
        // reverseLayout：首个 item 在视觉底部——live-tools 必须在 items(state.messages) 之前声明
        val liveItem = src.indexOf("item(key = \"live-tools\")")
        val msgsItem = src.indexOf("items(state.messages.asReversed()")
        assertTrue("LiveToolRow 必须声明在 messages items 之前（reverseLayout 首个 = 视觉底部）",
            liveItem in 0 until msgsItem)
    }

    // ---------- 参数生成期活行（2026-11-14 用户「AI 写个文件以为卡死了」） ----------

    /**
     * 用户截图：行内正文流完之后只剩一个光标——模型正在把**上千字文件正文当工具参数**
     * 吐出来（一两分钟），此时 toolCallsJson 还没落库（引擎在轮末才写），
     * ThinkingRow 又只在「正文为空」时显示 → 界面完全静止。
     * 修法：`callingTool`（首个工具名到达即置位）也算挂载条件，文案「正在准备 X」。
     */
    @Test
    fun `参数生成期也必须挂活行_不能等 toolCallsJson 落库`() {
        val main = source("app/src/main/java/io/github/m0use001/planability/ui/info/ChatScreen.kt")
        assertTrue(
            "主窗活行条件里没有 callingTool——参数生成期（写文件最长的时段）界面又只剩光标",
            main.contains("streamRow.toolCallsJson.isBlank() && state.callingTool.isNotBlank()")
        )
        assertTrue(
            "主窗必须把 callingTool 传给活行（否则标签是空的）",
            main.contains("LiveToolRow(streamRow, state.messages, callingTool = state.callingTool)")
        )
        // LiveToolRow 本身：参数生成期文案必须说清「正在准备」
        val body = main.substringAfter("internal fun LiveToolRow(").substringBefore("\n/**")
        assertTrue("LiveToolRow 没有 callingTool 参数", body.contains("callingTool: String = \"\""))
        assertTrue("参数生成期没走独立的文案（应显示「正在准备 X」）", body.contains("正在准备"))
        assertTrue("参数生成期判据丢了", body.contains("calls.isEmpty() && callingTool.isNotBlank()"))
        // 浮窗同款
        val float = source("app/src/main/java/io/github/m0use001/planability/ui/info/FloatChatActivity.kt")
        assertTrue(
            "浮窗活行条件没有 callingTool——浮窗里写文件同样看着像卡死",
            float.contains("streamRow.toolCallsJson.isBlank() && state.callingTool.isNotBlank()")
        )
    }

    @Test
    fun `LiveToolRow 与依赖已对同模块开放_internal_供浮窗复用`() {
        val src = source("app/src/main/java/io/github/m0use001/planability/ui/info/ChatScreen.kt")
        assertTrue("LiveToolRow 必须 internal（浮窗同包复用）", src.contains("internal fun LiveToolRow("))
        assertTrue("parseChainCalls 必须 internal（internal 函数不能返回私有类型）",
            src.contains("internal fun parseChainCalls("))
        assertTrue("ChainCall 必须 internal", src.contains("internal data class ChainCall("))
        assertTrue("toolLabel 必须 internal（LiveToolRow 依赖它）", src.contains("internal fun toolLabel("))
    }
}
