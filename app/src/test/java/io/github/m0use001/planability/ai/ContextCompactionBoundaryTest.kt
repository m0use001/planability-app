package io.github.m0use001.planability.ai

import io.github.m0use001.planability.data.ChatMessage
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 滚动摘要 / 历史裁剪的**边界用例**，逐条对应 `◆/ref-runtime-study.md` 第七节。
 *
 * ## 为什么需要这个文件
 *
 * `app/src/test/` 里原来 38 个测试，**没有一个摸过 `ChatEngine` / `ContextEngine` 的运行期行为**
 * （全是纯函数、形状约定、源码级断言）。结果是：压缩这条链上"会编译、会绿"，但有几个洞：
 * 下标漂移（摘要输入与请求载荷不是同一份过滤）、`dropLast(8)` 拆 tool 对、保留窗口无 token 上限、
 * 摘要 chip 关掉后历史被静默丢弃。本文件就是把这四条 + 文档 7.2 的 M1~M8 钉成断言。
 *
 * ## 两档测试，为什么不能都写成行为断言
 *
 * 本项目**没有 Robolectric、没有 MockK**（`app/build.gradle.kts` 只有 `testImplementation(libs.junit)`），
 * 而 `ContextEngine.assemble` / `ChatEngine.maybeScheduleSummary` 都要 `Graph`（Context + Room + Settings）。
 * 所以：
 * - 能用纯函数说话的一律行为断言（`capHistory`、`summarizeStartIndex`/`nextSummarizeAnchor` 是特意放开可见性的）；
 * - 够不到的（读 DB、调 AI、UI）就用**源码级断言**——同 `ExportStoreScanConventionTest`、
 *   `MigrationShapeTest` 与 OpenMinis `ClearChatTouchesSessionTest` 的路子：钉不住行为，就钉住约定，
 *   比什么都不钉好。源码级断言一律**用函数名/特征串定位，不用行号**（行号一改就瞎）。
 *
 * ## @Ignore 清零（2026-10-19 晚）
 *
 * 文档 7.1/7.2 曾有四条生产代码没修的用例挂着 `@Ignore`（M5 删除/清游标不维护索引、
 * M7 压缩后不刷新用量、M8 备份不含 chat_sessions、shouldSplitOnError 没接进主循环）。
 * 本轮全部修完并转成真断言——**@Ignore 不跑等于没写**，解除时每条都按新写的审视过：
 * M5/M7 当天早些时候修，M8 与 shouldSplitOnError 在 v39（摘要锚点 id 化）之后修。
 * 今后若再挂 @Ignore，照旧规矩：注明"修哪一行之后去掉 @Ignore"，明知会红的测试不进主干。
 */
class ContextCompactionBoundaryTest {

    // ---------- 源码读取（同 ExportStoreScanConventionTest） ----------

    private fun repoRoot(): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null && !File(dir, "app/src/main/java/io/github/m0use001/planability").isDirectory) {
            dir = dir.parentFile
        }
        return requireNotNull(dir) {
            "找不到仓库根（从 ${System.getProperty("user.dir")} 向上都没见到 " +
                "app/src/main/java/io/github/m0use001/planability）——源码级断言找不到文件，不能当成'约定没被破坏'"
        }
    }

    private fun source(rel: String): String {
        val f = File(repoRoot(), rel)
        assertTrue("源码不在了：$rel", f.isFile)
        return f.readText(Charsets.UTF_8)
    }

    // ---------- 构造消息（不用 org.json：android.jar 的桩在 JVM 单测里会抛 Stub!） ----------

    private val CALLS_A = """[{"id":"c1","type":"function","function":{"name":"note_read","arguments":"{}"}}]"""
    private val CALLS_AB = """[{"id":"c1","type":"function","function":{"name":"note_read","arguments":"{}"}},
        {"id":"c2","type":"function","function":{"name":"note_write","arguments":"{}"}}]"""

    /** 每段消息都带一个可读标记，方便断言"整组在还是整组不在"；`cjk` 决定它占多少 token */
    private fun user(marker: String, cjk: Int = 0) = ChatApiMessage("user", marker + "标".repeat(cjk))
    private fun assistant(marker: String, cjk: Int = 0, calls: String = "") =
        ChatApiMessage("assistant", marker + "标".repeat(cjk), calls)
    private fun toolResult(marker: String, callId: String, cjk: Int = 0) =
        ChatApiMessage("tool", marker + "标".repeat(cjk), "", callId)

    private val callIdRegex = Regex("\"id\"\\s*:\\s*\"([^\"]+)\"")

    /** tool_calls 的 id 集合声明在这条 assistant 消息里 */
    private fun declaredIds(m: ChatApiMessage): Set<String> =
        callIdRegex.findAll(m.toolCallsJson).map { it.groupValues[1] }.toSet()

    /**
     * 断言：结果里**不允许**出现"前面没有声明它的 tool_calls"的 tool 结果。
     * 这是所有 OpenAI 兼容端点的硬约束，出现就是 400（或模型开始编造不存在的结果）。
     */
    private fun assertNoOrphanToolResult(out: List<ChatApiMessage>) {
        val declared = mutableSetOf<String>()
        for (m in out) {
            if (m.role == "assistant" && m.toolCallsJson.isNotBlank()) declared += declaredIds(m)
            if (m.role == "tool") {
                assertTrue(
                    "出现孤立的 tool 结果（id=${m.toolCallId}，前面没有声明它的 tool_calls）",
                    m.toolCallId in declared
                )
            }
        }
    }

    // ---------- 1) tool 对不可拆（文档 7.1 缺口 2 / T2） ----------

    @Test
    fun `带 toolCalls 的 assistant 与它的 tool 结果同进同出`() {
        // 这一组自己就超过整个预算，且是**最新**的一组 → 按"至少留一组"它必须整组留下
        val toolGroup = listOf(
            assistant("G1-调用", cjk = 10, calls = CALLS_AB),
            toolResult("G1-结果一", "c1", ContextEngine.MAX_HISTORY_TOKENS),
            toolResult("G1-结果二", "c2", ContextEngine.MAX_HISTORY_TOKENS)
        )
        val history = listOf(user("G0-老问题", 200), assistant("G0-老回答", 200)) + toolGroup

        val out = ContextEngine.capHistory(history)

        assertNoOrphanToolResult(out)
        val kept = out.count { it.content.startsWith("G1-") }
        assertEquals("tool 组必须整组留下（当前只留了 $kept 条，3 才对）", 3, kept)
    }

    @Test
    fun `超预算时最旧的 tool 组成组消失，不留下半个调用`() {
        val history = listOf(
            assistant("G1-调用", cjk = 10, calls = CALLS_AB),
            toolResult("G1-结果一", "c1", ContextEngine.MAX_HISTORY_TOKENS / 2),
            toolResult("G1-结果二", "c2", ContextEngine.MAX_HISTORY_TOKENS / 2),
            user("G2-问题", 10),
            assistant("G2-回答", 10),
            user("G3-问题", 10)
        )

        val out = ContextEngine.capHistory(history)

        assertNoOrphanToolResult(out)
        assertTrue("整个 G1 组应该被丢掉，实际留下了 ${out.count { it.content.startsWith("G1-") }} 条",
            out.none { it.content.startsWith("G1-") })
        assertTrue("最近的用户消息必须保住", out.any { it.content.startsWith("G3-") })
    }

    @Test
    fun `两组工具轮夹着普通消息时不会被粘成一组`() {
        // G1 组 ≈ 整个预算；它被丢掉时，中间的 G3 组必须完整保留
        val history = listOf(
            assistant("G1-调用", cjk = 10, calls = CALLS_AB),
            toolResult("G1-结果一", "c1", ContextEngine.MAX_HISTORY_TOKENS / 2),
            toolResult("G1-结果二", "c2", ContextEngine.MAX_HISTORY_TOKENS / 2),
            user("G2-问题", 10),
            assistant("G3-调用", cjk = 10, calls = CALLS_AB),
            toolResult("G3-结果", "c1", 10),
            user("G4-问题", 10)
        )

        val out = ContextEngine.capHistory(history)

        assertNoOrphanToolResult(out)
        assertTrue("G1 组应该整组消失", out.none { it.content.startsWith("G1-") })
        assertTrue("G3 组的调用丢了", out.any { it.content.startsWith("G3-调用") })
        assertTrue("G3 组的结果丢了", out.any { it.content.startsWith("G3-结果") })
    }

    // ---------- 2) 孤立 tool 必须丢（文档 7.1 缺口 2） ----------

    @Test
    fun `历史以 tool 开头时，孤立 tool 必须丢`() {
        // 场景：上一轮的调用已被摘要/切点丢掉，只剩结果尾巴。带着它发出去是非法序列。
        val history = listOf(
            toolResult("孤儿结果", "gone", ContextEngine.MAX_HISTORY_TOKENS / 2 + 100),
            user("Q-问题", ContextEngine.MAX_HISTORY_TOKENS / 2 + 100),
            assistant("A-回答", 10)
        )

        val out = ContextEngine.capHistory(history)

        assertTrue(
            "孤立 tool 结果被留下了（首条是 ${out.first().role}:${out.first().content.take(12)}）",
            out.none { it.content.startsWith("孤儿") }
        )
        assertTrue("首条不应该是 tool，实际是 ${out.first().role}", out.first().role != "tool")
        assertTrue("正常对话应该保住", out.first().content.startsWith("Q-问题"))
    }

    // ---------- 3) 预算边界（文档 7.1 缺口 3） ----------

    @Test
    fun `预算边界：超预算丢最旧的整组，最近一条用户消息保住`() {
        val half = ContextEngine.MAX_HISTORY_TOKENS / 2 + 200
        val history = listOf(
            user("G1-老问题", half),
            assistant("G2-老回答", half),
            user("G3-最新问题", 10)
        )
        assertTrue(
            "构造的输入应该确实超预算",
            ContextEngine.estTokens(history.joinToString("") { it.content }) > ContextEngine.MAX_HISTORY_TOKENS
        )

        val out = ContextEngine.capHistory(history)

        assertTrue("最旧的一组没被丢掉", out.none { it.content.startsWith("G1-") })
        assertTrue("中间那组应该保住", out.any { it.content.startsWith("G2-") })
        assertTrue("最新的用户消息必须保住", out.any { it.content.startsWith("G3-") })
        assertEquals(
            "最后一条必须是最新的用户消息（顺序被打乱）",
            "G3-最新问题" + "标".repeat(10),
            out.last().content
        )
    }

    @Test
    fun `单组就超预算时也保住最新一组，而不是发一个空历史`() {
        val history = listOf(
            user("G1-老问题", 100),
            assistant("G2-老回答", 100),
            user("G3-最新大块", ContextEngine.MAX_HISTORY_TOKENS * 2)
        )

        val out = ContextEngine.capHistory(history)

        assertEquals("应该只留最新那一组，实际 ${out.size} 条", 1, out.size)
        assertTrue("留下的不是最新那组", out[0].content.startsWith("G3-"))
    }

    @Test
    fun `不超预算时原样返回`() {
        val history = listOf(
            user("u", 10),
            assistant("a", 10),
            assistant("调用", 10, CALLS_AB),
            toolResult("结果", "c1", 10)
        )

        val out = ContextEngine.capHistory(history)

        assertEquals(history, out)
    }

    @Test
    fun `已知限制：预算内 capHistory 直接原样返回，所以预算内的孤立 tool 它管不着`() {
        // 这条是**钉住现状**的：capHistory 的第一行是"不超预算就 return all"。
        // 因此预算内出现的孤立 tool（切点落在 call 与 result 之间）会被原样发出去 →
        // 这正是 [T2] 必须单独做"切点吸附"的原因：capHistory 只是最后一道保险丝。
        val history = listOf(toolResult("预算内孤儿", "gone", 10), user("Q", 10))

        val out = ContextEngine.capHistory(history)

        assertEquals("capHistory 的早退分支变了", history, out)
    }

    @Test
    fun `预算必须把 toolCallsJson 算进去，否则大调用会被估成 0`() {
        // B 项（phone-b 2026-10-19 报）：capHistory 原来只 sumOf(estTokens(content))。
        // 一条正文很短、toolCallsJson 很大的 assistant 会被估成 0 → 总预算判成"没超"，
        // 原样发出去；而摘要侧用 estMessageTokens（含 toolCalls），两边口径不一致。
        val hugeCalls = """[{"id":"c1","type":"function","function":{"name":"note_write","arguments":"""" +
            "标".repeat(ContextEngine.MAX_HISTORY_TOKENS) + """"}}]"""
        val history = listOf(
            user("G1-老问题", 10),
            assistant("G1-大调用", cjk = 10, calls = hugeCalls),
            toolResult("G1-结果", "c1", 10),
            user("G2-最新问题", 10)
        )
        assertTrue(
            "构造的 toolCalls 应该确实超过预算",
            ContextEngine.estTokens(hugeCalls) > ContextEngine.MAX_HISTORY_TOKENS
        )

        val out = ContextEngine.capHistory(history)

        assertNoOrphanToolResult(out)
        assertTrue(
            "toolCallsJson 没进预算：超大的调用组被原样留下了（${out.size} 条）",
            out.none { it.content.startsWith("G1-") }
        )
        assertTrue("最新一条用户消息必须保住", out.any { it.content.startsWith("G2-") })
    }

    // ---------- 4) 摘要 chip 不可关（文档 7.1 缺口 4 / 3d） ----------

    @Test
    fun `摘要 chip 已从 CHIP_TYPES 移除，用户关不掉`() {
        val ctx = source("app/src/main/java/io/github/m0use001/planability/ai/ContextEngine.kt")
        val line = ctx.lineSequence().first { it.contains("val CHIP_TYPES") }
        assertFalse(
            "CHIP_TYPES 里又出现 summary 了 —— 用户关掉它，history 会被静默丢" +
                "（drop(lastSummarizeIndex) 照常执行，而摘要 chip 不注入）",
            line.contains("\"summary\"")
        )
    }

    @Test
    fun `摘要 chip 在 assemble 里被强制启用`() {
        val ctx = source("app/src/main/java/io/github/m0use001/planability/ai/ContextEngine.kt")
        assertTrue(
            "找不到强制启用的摘要 chip（之前那条 CtxItem 是 enabled = true 的）",
            ctx.contains("此前对话摘要（不可关闭）") && ctx.contains("enabled = true")
        )
        assertFalse(
            "摘要 chip 又走 chipItem() 了 —— 那条路会把 enabled 设成 chipEnabled(\"summary\")",
            ctx.contains("chipItem(\"summary\"")
        )
    }

    @Test
    fun `assemble 仍然调用 capHistory，作为最后一道保险`() {
        val ctx = source("app/src/main/java/io/github/m0use001/planability/ai/ContextEngine.kt")
        assertTrue(
            "assemble 里没有 capHistory 了 —— 只剩条数截断的话，单个大工具结果就能占满窗口",
            ctx.contains("val history = capHistory(fullHistory, historyBudget())")
        )
    }

    // ---------- 5) M1：take → takeLast ----------

    @Test
    fun `M1 摘要合并用 takeLast，超限时砍掉最旧的那一段`() {
        val chat = source("app/src/main/java/io/github/m0use001/planability/ai/ChatEngine.kt")
        assertTrue(
            "喂给摘要模型的旧摘要不是 takeLast(2000) —— take 取的是开头（最旧的），" +
                "而真正需要接续的是最近发生的事",
            chat.contains("session.memoryPrompt.takeLast(2000)")
        )
        // 合并/封顶走 mergeSummary（那里是 takeLast，cap=6000）
        assertTrue(
            "合并没走 mergeSummary —— 又回到各写一份，两处规则会漂移",
            chat.contains("mergeSummary(")
        )
        val ctx = source("app/src/main/java/io/github/m0use001/planability/ai/ChatContext.kt")
        assertTrue("mergeSummary 不是 takeLast", ctx.contains("(base + addition).takeLast(cap)"))
        assertFalse(
            "ChatContext 里又出现 take(cap)（会把最新的摘要静默丢掉）",
            ctx.contains("(base + addition).take(cap)")
        )
    }

    // ---------- 6) M2：并发摘要不倒退（纯函数，可直接断言；v39 起锚点是消息 id） ----------

    @Test
    fun `M2 并发摘要不会把锚点写倒退`() {
        // 场景：用户点了 /compact 的那条先算完，锚点推到 id=30 的消息；
        // 轮尾那条后算完，手里还是旧快照（只覆盖到 id=12）。
        // 旧实现按旧快照回写 → 游标倒退 → 已摘要的内容重新进 older（重复摘要）。
        assertEquals("force 那次应把锚点推到 30", 30L, nextSummarizeAnchor(0L, 30L))
        assertEquals(
            "锚点被写回去了（12 ≠ 30），已摘要的内容会被重复摘要",
            30L, nextSummarizeAnchor(30L, 12L)
        )
    }

    @Test
    fun `M2 锚点正常推进仍要继续，旧快照覆盖更晚也不倒退`() {
        assertEquals("正常推进失效了，滚动摘要会卡住", 32L, nextSummarizeAnchor(12L, 32L))
        assertEquals("相同锚点重复写无害", 32L, nextSummarizeAnchor(32L, 32L))
    }

    @Test
    fun `M2 回写前重读、基于 fresh 合并（源码级）`() {
        val chat = source("app/src/main/java/io/github/m0use001/planability/ai/ChatEngine.kt")
        assertTrue(
            "回写前没有重读会话 —— 并发时后写的那条会用旧 memoryPrompt 覆盖掉另一条刚写的摘要",
            chat.contains("val fresh = Graph.chat.session(sessionId)")
        )
        assertTrue(
            "合并没有基于 fresh.memoryPrompt（还在用调用前的旧值）",
            chat.contains("mergeSummary(fresh.memoryPrompt")
        )
        assertTrue(
            "锚点没走 nextSummarizeAnchor（后退保护被绕过了）",
            chat.contains("lastSummarizedMessageId = nextSummarizeAnchor(")
        )
    }

    // ---------- 已修好转真断言的（M3/M4，见各自注释） ----------

    @Test
    fun `M3 摘要输入必须与请求载荷用同一个过滤函数（含 role=tool）`() {
        // 修好于 2026-10-19：摘要输入与 assemble 都走 ChatContext.effectiveTranscript。
        // 注：与 T1 同源，保留两条是因为一个从"过滤口径"角度、一个从"tool 内容别丢"角度钉。
        val chat = source("app/src/main/java/io/github/m0use001/planability/ai/ChatEngine.kt")
        val ctx = source("app/src/main/java/io/github/m0use001/planability/ai/ChatContext.kt")
        assertTrue(
            "ChatEngine 的摘要输入仍自成一套过滤（应调用 assemble 用的那个函数）",
            chat.contains("effectiveTranscript(")
        )
        assertTrue("共用的过滤函数没抽出来", ctx.contains("fun effectiveTranscript("))
    }

    @Test
    fun `M4 空范围压缩不得报告成功`() {
        val chat = source("app/src/main/java/io/github/m0use001/planability/ai/ChatEngine.kt")
        // 修好于 2026-10-19：maybeScheduleSummary 返回 Boolean，调用方按返回值决定提不提示
        assertTrue(
            "maybeScheduleSummary 仍返回 Unit —— 调用方无从知道这次到底压没压",
            chat.contains("): Boolean")
        )
        val screen = source("app/src/main/java/io/github/m0use001/planability/ui/info/ChatScreen.kt")
        assertTrue(
            "ChatScreen 的 /compact 没有按返回值分支 —— 又回到无条件弹『已压缩』了",
            screen.contains("val ok = ChatEngine.maybeScheduleSummary(currentId, force = true)")
        )
        assertTrue(
            "压缩失败/空范围时没有告诉用户真实结果（静默）",
            // v53：拒绝原因由 ChatEngine.lastCompactBlockedReason 带出（区分「没到阈值/
            // 流式中/模型调用失败」），UI 原样展示——不再是一句笼统的固定文案。
            screen.contains("ChatEngine.lastCompactBlockedReason") &&
                chat.contains("lastCompactBlockedReason")
        )
    }

    // ---------- M5/M6：摘要锚点是消息 id（v39 根治，删消息自愈） ----------

    @Test
    fun `M6 锚点换算：锚点之前的全裁，锚点被删则剩下的全是已摘要`() {
        val msgs = (1L..10L).map { ChatMessage(id = it, sessionId = 1, role = "user", content = "m$it") }
        assertEquals("没摘要过 → 从头发", 0, summarizeStartIndex(msgs, 0L))
        assertEquals("锚点覆盖到 7 → 从第 8 条开始", 7, summarizeStartIndex(msgs, 7L))
        assertEquals(
            "锚点 id 比现存所有消息都大（它自己和之后被删过）→ 剩下的都是更早的已摘要，全裁",
            10, summarizeStartIndex(msgs, 99L)
        )
        assertEquals(0, summarizeStartIndex(msgs, -1L))
    }

    @Test
    fun `M5 清游标必须连锚点与摘要一起清，删消息不再需要维护计数`() {
        val chat = source("app/src/main/java/io/github/m0use001/planability/ai/ChatEngine.kt")
        assertTrue(
            "clearCursor 没有重置摘要锚点 —— 清游标后新消息会被当成已摘要裁掉（静默丢历史）",
            chat.contains("lastSummarizedMessageId = 0")
        )
        val body = chat.substringAfter("suspend fun deleteFromId(")
            .substringBefore("/** 工具调用增量累积器")
        assertFalse(
            "deleteFromId 还在维护计数游标 —— v39 起锚点是消息 id，删消息自愈，别再写回",
            body.contains("lastSummarize")
        )
    }

    @Test
    fun `M7 压缩成功必须刷新上下文用量显示`() {
        val chat = source("app/src/main/java/io/github/m0use001/planability/ai/ChatEngine.kt")
        val body = chat.substringAfter("private suspend fun summarizeNow(")
            .substringBefore("// ---------- 自动命名")
        assertTrue(
            "摘要成功路径没有更新 ctxTokens —— 面板会一直显示压缩前的大数字",
            body.contains("ctxTokens")
        )
        assertTrue(
            "刷新口径不是 assemble 的结果 —— 面板数与下一轮实际请求会对不上",
            body.contains("ContextEngine.assemble(")
        )
    }

    @Test
    fun `M8 备份必须覆盖会话、消息与压缩标记，且导入有清库与回填`() {
        // 修好于 2026-10-19：exportJson 补 chat_sessions/chat_messages/compact_markers，version 5→6
        val backup = source("app/src/main/java/io/github/m0use001/planability/data/BackupManager.kt")
        for (t in listOf("chat_sessions", "chat_messages", "compact_markers")) {
            assertTrue(
                "exportJson 里没有 $t —— 从 JSON 备份恢复会丢会话与四年尺度的摘要",
                backup.contains("tables.put(\"$t\"")
            )
        }
        val imp = backup.substringAfter("suspend fun importJson(")
            .substringBefore("Graph.settings.load()")
        assertTrue(
            "importJson 没有清 chat_sessions —— 备份是全量替换，残留旧会话会和备份混在一起",
            imp.contains("backup.clearChatSessions()")
        )
        assertTrue("importJson 没有回填 chat_messages", imp.contains("backup.insertChatMessages("))
        assertTrue(
            "importJson 没有回填 compact_markers —— 摘要的审计记录一起走才对得上账",
            imp.contains("backup.insertCompactMarkers(")
        )
        // 清库/插入的顺序：markers（外键指向 sessions）先清、sessions 先插
        assertTrue("清库顺序不对：markers 必须先于 sessions 清（外键）",
            imp.indexOf("clearCompactMarkers()") < imp.indexOf("clearChatSessions()"))
        assertTrue("插入顺序不对：sessions 必须先于 markers 插（外键）",
            imp.indexOf("insertChatSessions(") < imp.indexOf("insertCompactMarkers("))
    }

    // ---------- shouldSplitOnError 接主循环后的切点行为（超长救援） ----------

    @Test
    fun `救援切点不拆工具对，正常消息处按 desired 切`() {
        val msgs = listOf(
            user("q1", 10),
            assistant("a1-调用", cjk = 10, calls = CALLS_AB),
            toolResult("a1-结果一", "c1", 10),
            toolResult("a1-结果二", "c2", 10),
            user("q2", 10)
        )
        // desired=2/3 都落在"调用与结果之间"→ 必须退回到 q1 之后（1），把整组留在保留侧
        assertEquals(1, splitSafeIndex(msgs, 2))
        assertEquals(1, splitSafeIndex(msgs, 3))
        // desired=4 是组边界（q2 前）→ 照切；前缀里是完整的一组
        assertEquals(4, splitSafeIndex(msgs, 4))
        // 没有工具对的普通对话：对半就是切点
        val plain = listOf(user("a", 10), assistant("b", 10), user("c", 10), assistant("d", 10))
        assertEquals(2, splitSafeIndex(plain, 2))
        // 兜底：desired 越界/过小都不越界返回
        assertEquals(1, splitSafeIndex(plain, 0))
        assertEquals(4, splitSafeIndex(plain, 99))
    }

    @Test
    fun `T2 摘要切点必须吸附到工具轮边界`() {
        // 修好于 2026-10-19（phone-b 抽 snapCutToToolSafe，ChatEngine 已接上）
        val chat = source("app/src/main/java/io/github/m0use001/planability/ai/ChatEngine.kt")
        assertTrue(
            "摘要侧切点计算没有走 snapCutToToolSafe —— 预算内切点落在 call 与 result 之间时，\n" +
                "保留段会以 role=tool 开头 → 400；capHistory 只在超预算时才兜",
            chat.contains("snapCutToToolSafe(")
        )
        val ctx = source("app/src/main/java/io/github/m0use001/planability/ai/ChatContext.kt")
        assertTrue(
            "snapCutToToolSafe 只往后推（往前推会让索引倒退，重复摘要）",
            ctx.contains("while (c < msgs.size && msgs[c].role == \"tool\") c++")
        )
    }

    @Test
    fun `T3 token 估算必须计入 toolCallsJson`() {
        // 修好于 2026-10-19（estMessageTokens = content + toolCallsJson + 4）
        val ctx = source("app/src/main/java/io/github/m0use001/planability/ai/ChatContext.kt")
        assertTrue(
            "没有 estMessageTokens —— 阈值与预算都还在用 estTokens(content)",
            ctx.contains("fun estMessageTokens(")
        )
        assertTrue(
            "阈值侧没有用 estMessageTokens",
            source("app/src/main/java/io/github/m0use001/planability/ai/ChatEngine.kt")
                .contains("estMessageTokens(")
        )
    }

    @Test
    fun `T1 摘要侧与读侧必须是同一个过滤口径`() {
        // 修好于 2026-10-19（effectiveTranscript，两处都走它）。
        // 原来全仓 4 份不同过滤（F1 摘要 / F2 读侧 / F3 显示 / F4 检索）→ 下标漂移。
        val chat = source("app/src/main/java/io/github/m0use001/planability/ai/ChatEngine.kt")
        assertTrue(
            "摘要侧没有用 effectiveTranscript —— 又回到自成一套的过滤，下标会再次漂移",
            chat.contains("effectiveTranscript(")
        )
        assertFalse(
            "摘要侧还留着 role in listOf(\"user\", \"assistant\") 的老过滤",
            chat.contains("it.role in listOf(\"user\", \"assistant\")")
        )
    }

    // ---------- v45 A2 门牌：摘要必须带得回原文的指针 ----------

    @Test
    fun `A2 摘要块头带消息 id 范围门牌，转录行带 id 前缀`() {
        // 2026-10-27（planability-dsh-optimization-plan.md §A2）：
        // "摘要每条都对，但都不带出处"是思考退化的根因——
        // 块头范围指针是"保证"（compact_markers 锚点之外摘要自身也能定位原文），
        // 逐条标注是"尽力"（模型按转录行里的 [#id] 前缀引用）。
        val chat = source("app/src/main/java/io/github/m0use001/planability/ai/ChatEngine.kt")
        assertTrue(
            "摘要块头没有范围门牌 —— 摘要又变回无处可查的孤立结论",
            chat.contains("\${older.first().id}-\${older.last().id}] \$summary")
        )
        assertTrue(
            "喂给摘要模型的转录行没有 [#id] 前缀 —— 模型无从标注来源",
            chat.contains("\"[#" + "$" + "{it.id}] \"")
        )
        assertTrue(
            "摘要提示词没有要求逐条标注来源编号",
            chat.contains("来源消息编号")
        )
    }
}
