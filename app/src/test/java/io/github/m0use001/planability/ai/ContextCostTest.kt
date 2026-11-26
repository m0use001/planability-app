package io.github.m0use001.planability.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 成本优化阶段 1（ref-cost-study.md §7）的接线断言。
 *
 * 本仓没有 Robolectric，`assemble`/`toToolsJson` 都要 Graph 或 org.json（JVM 单测里是桩），
 * 所以这一批照 `ContextCompactionBoundaryTest` 的路子钉**源码级约定**：
 * 钉不住行为就钉住结构——比什么都不钉好。
 */
class ContextCostTest {

    private fun source(rel: String): String {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null && !File(dir, "app/src/main/java/io/github/m0use001/planability").isDirectory) {
            dir = dir.parentFile
        }
        val f = File(requireNotNull(dir) { "找不到仓库根" }, rel)
        assertTrue("源码不在了：$rel", f.isFile)
        return f.readText(Charsets.UTF_8)
    }

    // ---------- 1.1 system 前缀稳定化 ----------

    @Test
    fun `分钟级时间戳与 chips 在易变尾部，stable 前缀里不再出现时间`() {
        // Agora/PURE/Coomi 三家的共同前提：provider 前缀缓存吃的是"逐字节稳定的前缀"。
        // 时间戳坐在 persona 后面时，每个请求的 system 都不同 → 缓存命中率≈0。
        val ctx = source("app/src/main/java/io/github/m0use001/planability/ai/ContextEngine.kt")
        val personaIdx = ctx.indexOf(".append(persona)")
        val tsIdx = ctx.indexOf("当前时间：")
        val typesetIdx = ctx.indexOf("【回答排版】")
        val chipsIdx = ctx.indexOf("chips.forEach { c ->")
        assertTrue("定位失败：persona/$personaIdx ts/$tsIdx 排版/$typesetIdx chips/$chipsIdx",
            personaIdx >= 0 && tsIdx >= 0 && typesetIdx >= 0 && chipsIdx >= 0)
        assertTrue(
            "时间戳还在稳定段里（应挪到排版说明之后）——每轮都变的前缀让 provider 缓存全部失效",
            tsIdx > typesetIdx
        )
        assertTrue(
            "时间戳应与 chips 相邻（同属易变尾部，时间在 chips 注入之前）",
            tsIdx < chipsIdx
        )
        assertTrue(
            "persona 后面不能直接跟时间戳（老位置回归）",
            !Regex("""\.append\(persona\)\s*\n\s*\.append\("\\n当前时间""").containsMatchIn(ctx)
        )
        assertTrue(
            "易变尾部要有'为什么'的注释（后人容易把时间戳挪回去）",
            ctx.contains("易变尾部")
        )
    }

    // ---------- 1.2 工具 schema memo ----------

    @Test
    fun `工具 schema 有 (domains, plugins) 维度的 memo，且每次返回新的外层数组`() {
        val defs = source("shared/src/jvmShared/kotlin/io/github/m0use001/planability/shared/ToolDefs.kt")
        assertTrue("没有 memo 字段 —— 72 个工具的 schema 仍在每轮重序列化", defs.contains("schemaMemo"))
        assertTrue(
            "memo 的 key 必须含 enabledDomains 与 enabledPlugins（设置变更自然产生新 key）",
            defs.contains("it.first == enabledDomains && it.second == enabledPlugins")
        )
        assertTrue(
            "返回必须套新 JSONArray —— McpBridge.appendTools 会往传入数组追加 MCP 工具，" +
                "共享外层数组会把上一轮的追加残留进下一轮",
            Regex("""return JSONArray\(\)\.apply""").containsMatchIn(defs)
        )
        assertTrue(
            "缓存里存的是每工具 JSONObject 的只读列表（内层共享安全，外层必须新建）",
            defs.contains("List<JSONObject>")
        )
    }

    // ---------- 1.3/1.4 账本与缓存命中 ----------

    @Test
    fun `usage 账本：unknown 不写 0，cachedTokens 可缺省`() {
        // rikkahub-agent-pure 的账本纪律："unknown ≠ 0" —— provider 没上报的字段保持缺失，
        // 绝不记成 0，否则"没上报"会污染缓存命中率与按用途聚合的成本。
        val ctx = source("app/src/main/java/io/github/m0use001/planability/ai/ContextEngine.kt")
        assertTrue("appendUsage 的 token 参数应为可空（没上报 = 缺字段）", ctx.contains("tokensIn: Int?,"))
        assertTrue("缺 cachedTokens 参数", ctx.contains("cachedTokens: Int? = null"))
        assertTrue(
            "没上报的字段必须整体缺省，不能写 0",
            ctx.contains("if (tokensIn != null) line.put(\"tokensIn\", tokensIn)") &&
                ctx.contains("if (cachedTokens != null) line.put(\"cachedTokens\", cachedTokens)")
        )
    }

    @Test
    fun `cached_tokens 两种上报形态都收，Anthropic 收 cache_read，worker 调用能带 usage 回来`() {
        val stream = source("app/src/main/java/io/github/m0use001/planability/ai/AiStream.kt")
        assertTrue("OpenAI 标准形态没收", stream.contains("prompt_tokens_details"))
        assertTrue("DeepSeek 专用字段没收", stream.contains("prompt_cache_hit_tokens"))
        assertTrue("Anthropic 的 cache_read_input_tokens 没收", stream.contains("cache_read_input_tokens"))
        val client = source("app/src/main/java/io/github/m0use001/planability/ai/AiClient.kt")
        assertTrue(
            "worker 槽位（非流式）调用要能带 usage 回来 —— 否则命名/摘要/救援永远没账",
            client.contains("chatWithCounted") && client.contains("cache_read_input_tokens")
        )
    }

    @Test
    fun `worker 槽位三笔调用进账本：summary、naming、rescue`() {
        // 原来只有 slot="main"：命名/摘要/救援这些 AI 调用完全没记账（ref-cost-study §0-B4）。
        val chat = source("app/src/main/java/io/github/m0use001/planability/ai/ChatEngine.kt")
        assertTrue("摘要调用没进账本", chat.contains("\"summary\""))
        assertTrue("起名调用没进账本", chat.contains("\"naming\""))
        val comp = source("app/src/main/java/io/github/m0use001/planability/ai/Compaction.kt")
        assertTrue("救援调用没进账本", comp.contains("\"rescue\""))
    }

    // ---------- 阶段 2：估算诚实化（ref-cost-study §7 阶段 2） ----------

    @Test
    fun `工具结果线上预算：超预算保留头尾与省略标记，未超原样返回`() {
        // 错误码/结论在尾部——"只留头"的老sanitize恰好把最有价值的部分丢了（PURE 上游 bug 记录）
        val big = "头".repeat(4000) + "中".repeat(20000) + "尾。错误码 E-42 收尾".repeat(30)
        val out = truncateToolResultWire(big, maxTokens = 6000)
        assertTrue("应判定截断", out.truncated)
        assertTrue("尾部必须保留（错误码在尾巴）", out.text.contains("E-42"))
        assertTrue("头部必须保留", out.text.startsWith("头"))
        assertTrue("要有省略标记", out.text.contains("中段省略约"))
        val under = truncateToolResultWire("短结果", maxTokens = 6000)
        assertFalse("未超预算不该动", under.truncated)
        assertEquals("短结果", under.text)
    }

    @Test
    fun `预算极小时只留头，标记不能丢（PURE 同款取舍）`() {
        val out = truncateToolResultWire("字".repeat(5000), maxTokens = 30)
        assertTrue(out.truncated)
        assertTrue("标记是元数据：丢了它 = 模型看到的数据静默缺失", out.text.contains("省略"))
    }

    @Test
    fun `base64 内联清洗：长段替换、短段保留`() {
        val blob = "QUJD".repeat(2000)   // 8000 字符连续 base64 体
        val (elided, hits) = elideInlineBase64("前缀 " + blob + " 后缀")
        assertEquals(1, hits)
        assertTrue(elided.contains("base64 内联数据省略"))
        assertFalse("内联二进制不该出现在 wire 上", elided.contains("QUJD"))
        val (keep, hits2) = elideInlineBase64("短 base64：QUJDRA==")
        assertEquals(0, hits2)
        assertEquals("短 base64：QUJDRA==", keep)
    }

    @Test
    fun `estTokens 走 memo 结果不变（同一文本两次一致）`() {
        val text = "标".repeat(500) + "abc".repeat(100)
        val first = ContextEngine.estTokens(text)
        val second = ContextEngine.estTokens(text)   // 第二次命中 memo
        assertEquals(first, second)
        assertEquals(first, ContextEngine.estTokens("标".repeat(500) + "abc".repeat(100)))
        assertTrue("≥128 字的 CJK 文本 1 字 1 token", first >= 500)
    }

    // ---------- 阶段 3：账本聚合与会话预算（ref-cost-study §7 阶段 3） ----------

    @Test
    fun `账本行解析：缺字段是 null 不是 0（unknown ≠ 0），坏行返回 null`() {
        val newRow = UsageLedger.parseLine(
            """{"ts":100,"session":14,"slot":"main","model":"m","tokensIn":100,"tokensOut":7,"cachedTokens":88}"""
        )
        assertEquals(100, newRow!!.tokensIn)
        assertEquals(88, newRow.cachedTokens)
        // 老格式：没有 cachedTokens 字段 → null（不能读成 0 污染命中率）
        val oldRow = UsageLedger.parseLine(
            """{"ts":100,"session":11,"slot":"main","model":"m","tokensIn":10355,"tokensOut":279}"""
        )
        assertEquals(10355, oldRow!!.tokensIn)
        assertNull(oldRow.cachedTokens)
        // usage 没上报的行：totalTokens 不冒充有数
        val bare = UsageLedger.parseLine("""{"ts":100,"session":1,"slot":"naming","model":"m"}""")
        assertNull(bare!!.tokensIn)
        assertNull(bare.tokensOut)
        assertEquals(0L, bare.totalTokens)
        assertNull("坏行要被跳过而不是崩", UsageLedger.parseLine("not json"))
        assertNull(UsageLedger.parseLine("{}"))
    }

    @Test
    fun `summarize 按任意 key 聚合：调用数与三段 token 分列`() {
        val e1 = UsageLedger.UsageEntry(1, 1, "main", "m1", 100, 10, 60)
        val e2 = UsageLedger.UsageEntry(2, 1, "summary", "m2", 50, 5, null)
        val e3 = UsageLedger.UsageEntry(3, 1, "main", "m1", 200, 20, null)
        val bySlot = UsageLedger.summarize(listOf(e1, e2, e3)) { it.slot }
        assertEquals(listOf("main", "summary"), bySlot.map { it.key })
        val main = bySlot.first { it.key == "main" }
        assertEquals(2, main.calls)
        assertEquals(300L, main.tokensIn)
        assertEquals(30L, main.tokensOut)
        assertEquals(60L, main.cachedTokens)   // 缓存命中单列，不混进 tokensIn
        val byModel = UsageLedger.summarize(listOf(e1, e2, e3)) { it.model }
        assertEquals(2, byModel.first { it.key == "m1" }.calls)
    }

    @Test
    fun `预算四态：默认无上限，软线预警，硬线拒绝且话术含数字`() {
        assertEquals(UsageLedger.BudgetCheck.NoBudget, UsageLedger.budgetStatus(999L, null, null))
        assertTrue(UsageLedger.budgetStatus(10L, 100, 200) is UsageLedger.BudgetCheck.Ok)
        val warn = UsageLedger.budgetStatus(120L, 100, 200)
        assertTrue(warn is UsageLedger.BudgetCheck.Warn)
        assertEquals(120L, (warn as UsageLedger.BudgetCheck.Warn).spent)
        val over = UsageLedger.budgetStatus(250L, 100, 200) as UsageLedger.BudgetCheck.OverHard
        assertEquals(200, over.hard)
        assertTrue(
            "拒绝话术要给用户和模型都能读懂的原因与出路",
            over.reason.contains("250") && over.reason.contains("200") && over.reason.contains(UsageLedger.KEY_HARD)
        )
    }

    // ---------- 阶段 4：压缩槽 + 耗时/tok-s ----------

    @Test
    fun `账本行解析耗时，聚合给出 tok 每秒；没测耗时的桶不编数`() {
        val timed = UsageLedger.parseLine(
            """{"ts":1,"session":1,"slot":"summary","model":"qwen-local","tokensIn":100,"tokensOut":50,"ms":2000}"""
        )!!
        assertEquals(2000L, timed.durationMs)
        val untimed = UsageLedger.parseLine("""{"ts":2,"session":1,"slot":"main","model":"m","tokensIn":9,"tokensOut":9}""")!!
        assertNull("老行没有 ms → null（不冒充 0）", untimed.durationMs)
        val b = UsageLedger.summarize(listOf(timed)) { it.slot }.first()
        assertEquals(25.0, b.tokPerSec()!!, 0.001)   // 50 tok / 2s
        val noSpeed = UsageLedger.summarize(listOf(untimed)) { it.slot }.first()
        assertNull("没有有效耗时就返回 null，不编 tok/s", noSpeed.tokPerSec())
    }

    @Test
    fun `压缩槽：KV 指向的模型解析不到时回退 worker，摘要与救援都走压缩槽并带回退`() {
        val client = source("app/src/main/java/io/github/m0use001/planability/ai/AiClient.kt")
        assertTrue("缺少压缩槽配置项", client.contains("ctx.compactModel"))
        // 2026-11-15：解析失败仍必须回退 worker——只是从「裸 workerConfig」改成
        // 「usableWorkerConfig()」（本机端点死了回云端主模型、活着对齐服务端模型名）。
        // 摘要绝不能丢这一条不变。
        assertTrue(
            "解析失败必须回退 worker（不能把摘要弄丢）",
            client.contains("return usableWorkerConfig()") || client.contains("return workerConfig")
        )
        assertTrue(
            "用 modelName 校验'真的解析到了'（configByName 找不到会回退 active）",
            client.contains("cfg.modelName == name")
        )
        val chat = source("app/src/main/java/io/github/m0use001/planability/ai/ChatEngine.kt")
        assertTrue("摘要没走压缩槽", chat.contains("AiClient.compactConfig()"))
        // v45：回退槽改用"可用性"版本——本地端点（127.0.0.1:18181）没起来时，
        // 只按配置取的 namingConfig() 会再撞一次连接拒绝，摘要照样丢（2026-10-24 实测）
        // 2026-11-15：备槽由 summaryFallback(primary) 决定——本机主槽直接给云端主模型，
        // 其余情况仍走 usableWorkerConfig()；三级链在 ChatEngine 里 primary→fallback→云端。
        assertTrue(
            "摘要没有回退路径",
            chat.contains("val fallback = AiClient.summaryFallback(primary)") ||
                chat.contains("val fallback = AiClient.usableWorkerConfig()")
        )
        val comp = source("app/src/main/java/io/github/m0use001/planability/ai/Compaction.kt")
        assertTrue("救援没走压缩槽", comp.contains("AiClient.compactConfig()"))
        assertTrue("耗时没进账本", comp.contains("reply.durationMs"))
    }
}
