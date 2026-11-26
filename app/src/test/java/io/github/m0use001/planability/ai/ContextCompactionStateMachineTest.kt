package io.github.m0use001.planability.ai

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v37 压缩状态机的**决策层**断言（`ai/Compaction.kt`）。
 *
 * 这一层是刻意做成纯函数的：没有 Room、没有网络、没有 Context，
 * 所以规则可以逐条钉死，不用 Robolectric。真正会和 provider 打交道的执行层在 `ChatEngine`，
 * 那里只保留"读 DB / 调 AI / 写回"。
 *
 * 每条规则后面都标了来源（OpenMinis 测试名 / rikkahub 的做法），见
 * `◆/ref-runtime-study.md` 第一节 B 组与 5.5 节。
 */
class ContextCompactionStateMachineTest {

    // ---------- decide：该不该压 ----------

    @Test
    fun `默认关闭自动压缩，到阈值只提示不自动动手`() {
        // 到 0.85 线：自动关着 → 照发 + 给提示，绝不自动改用户历史
        val d = Compaction.decide(
            estimatedTokens = 8_500,
            windowTokens = 10_000,
            autoEnabled = Compaction.AUTO_DEFAULT
        )
        assertEquals(Compaction.CompactionAction.PROCEED, d.action)
        assertTrue("关着的时候必须告诉用户'可以手动压缩'", d.hint.contains("/compact"))
        assertFalse(Compaction.AUTO_DEFAULT)
    }

    @Test
    fun `自动开时才真的压`() {
        val d = Compaction.decide(estimatedTokens = 8_500, windowTokens = 10_000, autoEnabled = true)
        assertEquals(Compaction.CompactionAction.COMPACT, d.action)
    }

    @Test
    fun `手动 compact 跳过阈值`() {
        // 只 5% 用量也允许手动压（用户明确要裁历史）
        val d = Compaction.decide(estimatedTokens = 500, windowTokens = 10_000, force = true)
        assertEquals(Compaction.CompactionAction.COMPACT, d.action)
    }

    @Test
    fun `有未决工具调用时不许压（哪怕手动）`() {
        // rikkahub compressConversation 三道检查的第三条：没回来的结果被压掉就再也不会执行，
        // 而且请求会变成非法序列
        for (force in listOf(true, false)) {
            val d = Compaction.decide(
                estimatedTokens = 99_000,
                windowTokens = 10_000,
                autoEnabled = true,
                force = force,
                pendingToolCalls = 1
            )
            assertEquals(
                "force=$force 时未决工具也该拦住",
                Compaction.CompactionAction.SKIP,
                d.action
            )
            assertTrue(d.reason.contains("工具调用"))
        }
    }

    @Test
    fun `窗口未知时永不掐死请求`() {
        // OpenMinis CompactionDecisionTest：不能因为不知道窗口就停
        val d = Compaction.decide(estimatedTokens = 999_999, windowTokens = 0, autoEnabled = true)
        assertEquals(Compaction.CompactionAction.PROCEED, d.action)
        assertTrue(d.reason.contains("未知"))
    }

    @Test
    fun `压了但没变小就停手，不无限重试`() {
        val d = Compaction.decide(
            estimatedTokens = 9_000,
            windowTokens = 10_000,
            autoEnabled = true,
            lastCompactionShrunk = false
        )
        assertEquals(Compaction.CompactionAction.EXHAUSTED, d.action)
    }

    @Test
    fun `同一轮试太多次也停手`() {
        val d = Compaction.decide(
            estimatedTokens = 9_000,
            windowTokens = 10_000,
            autoEnabled = true,
            attempts = Compaction.MAX_ATTEMPTS
        )
        assertEquals(Compaction.CompactionAction.EXHAUSTED, d.action)
    }

    @Test
    fun `预算内什么都不做`() {
        val d = Compaction.decide(estimatedTokens = 1_000, windowTokens = 10_000, autoEnabled = true)
        assertEquals(Compaction.CompactionAction.PROCEED, d.action)
    }

    @Test
    fun `到窗口上限比到压缩线更严重，提示也不同`() {
        val atLine = Compaction.decide(8_500, 10_000)
        val over = Compaction.decide(10_000, 10_000)
        assertTrue(atLine.reason.contains("压缩线"))
        assertTrue(over.reason.contains("上限"))
    }

    // ---------- preflight：这次压缩允不允许发生 ----------

    @Test
    fun `前置检查拦下流式中与空范围`() {
        assertEquals(Compaction.BlockReason.STREAMING, Compaction.preflight(streaming = true, 5))
        assertEquals(Compaction.BlockReason.EMPTY_RANGE, Compaction.preflight(streaming = false, 0))
        assertEquals(Compaction.BlockReason.NONE, Compaction.preflight(streaming = false, 5))
    }

    // ---------- split / merge ----------

    @Test
    fun `split 到深度上限或不足两条就不再切`() {
        val list = (1..7).toList()
        val parts = Compaction.split(list, depth = 0)
        assertEquals(listOf(listOf(1, 2, 3, 4), listOf(5, 6, 7)), parts)

        // 深度到顶：空 = 别再切了（调用方据此收手）
        assertTrue(Compaction.split(list, depth = Compaction.MAX_SPLIT_DEPTH).isEmpty())
        // 不足 2 条：空
        assertTrue(Compaction.split(listOf(1), depth = 0).isEmpty())
    }

    @Test
    fun `merge 严格按原顺序接回`() {
        val a = listOf(1, 2, 3)
        val b = listOf(4, 5, 6)
        assertEquals(a + b, Compaction.merge(listOf(a, b)))
        // 切完再合必须还原
        val original = (1..9).toList()
        assertEquals(original, Compaction.merge(Compaction.split(original, depth = 0)))
    }

    // ---------- shouldSplitOnError：这个错误降半有没有救 ----------

    @Test
    fun `与大小无关的错误不许降半`() {
        // 429 / 网络 / 取消 / 参数类 400：降半只会把一次失败放大成很多次慢调用
        for (msg in listOf(
            "HTTP 429 Too Many Requests",
            "rate limit exceeded for model",
            "insufficient_quota",
            "java.net.SocketTimeoutException: timeout",
            "failed to connect to api.example.com",
            "cancelled by user",
            "HTTP 400 invalid_request_error: Unsupported parameter: 'temperature' is not supported",
            "HTTP 401 Unauthorized: invalid api key",
            "reasoning.effort: 'none' is not supported with this model"
        )) {
            assertFalse("不该降半：$msg", Compaction.shouldSplitOnError(msg))
        }
    }

    @Test
    fun `明确是太长的错误必须降半`() {
        for (msg in listOf(
            "context_length_exceeded",
            "This model's maximum context length is 128000 tokens",
            "Request too large for model",
            "your input exceeds the context window of this model",
            "tokens exceeds the model's limit, please reduce the length of the messages"
        )) {
            assertTrue("该降半：$msg", Compaction.shouldSplitOnError(msg))
        }
    }

    @Test
    fun `裸数字状态码必须按词边界，别被 token 数误伤`() {
        // 超长报文里带数字是常态：40192 / 14296 / 128000…用 includes 匹配 "401"/"429"
        // 会把明显的"你太长了"判成配额/参数类错误 → 不降半 → 压缩整段失败。
        for (msg in listOf(
            "This model's maximum context length is 40192 tokens",
            "input length 14296 exceeds limit",
            "context length is 4294967296 characters",
            "HTTP 400: your input exceeds the context window of this model"
        )) {
            assertTrue("该降半却被判成大小无关：$msg", Compaction.shouldSplitOnError(msg))
        }
        // 真的配额/参数类仍然不降半
        assertFalse(Compaction.shouldSplitOnError("HTTP 429 Too Many Requests"))
        assertFalse(Compaction.shouldSplitOnError("HTTP 401 Unauthorized"))
    }

    @Test
    fun `认不出来的错误默认降半（举证责任在反方）`() {
        // OpenMinis#133：枚举所有 provider 的"你太长了"是不可能的，漏一个就等于压缩整段失败。
        // 所以默认重试：半截摘要胜过没有摘要。
        // null 要显式给类型，否则两个重载（String? / Throwable?）歧义
        val noMessage: String? = null
        assertTrue(Compaction.shouldSplitOnError(noMessage))
        assertTrue(Compaction.shouldSplitOnError(""))
        assertTrue(Compaction.shouldSplitOnError("HTTP 502 上游打了个嗝"))
        assertTrue(Compaction.shouldSplitOnError(RuntimeException("某种我们没见过的错")))
    }

    @Test
    fun `空消息与前后空格不影响判断`() {
        // 空 = 没有任何信息 → 回到默认（降半），与 null 同规则：举证责任在"不该重试"那一边
        assertTrue(Compaction.shouldSplitOnError("   "))
        // 大小写不敏感、前后空格不影响
        assertTrue(Compaction.shouldSplitOnError("  CONTEXT_LENGTH_EXCEEDED  "))
        assertFalse(Compaction.shouldSplitOnError("  HTTP 429 Rate Limit  "))
    }

    // ---------- validateOnTheWire：摘要必须真的进了下一次请求 ----------

    @Test
    fun `上线校验抓出摘要没进请求`() {
        val system = "你是助手…\n\n【用户上下文·此前对话摘要（不可关闭）】\n[10/19] 用户在装衣柜"
        // 摘要没出现
        val p1 = Compaction.validateOnTheWire(
            system = system, summary = "[10/19] 用户在研究压缩", fullCount = 20, sentCount = 8
        )
        assertTrue("摘要没进 system 居然没报", p1.any { it.contains("system") })
    }

    @Test
    fun `上线校验抓出历史没变短`() {
        val summary = "[10/19] 用户在装衣柜"
        val p2 = Compaction.validateOnTheWire(
            system = "…$summary…", summary = summary, fullCount = 20, sentCount = 20
        )
        assertTrue("历史一条没少居然没报", p2.any { it.contains("没有变短") })
    }

    @Test
    fun `上线校验通过时是空列表`() {
        val summary = "[10/19] 用户在装衣柜"
        assertTrue(
            Compaction.validateOnTheWire(
                system = "…\n$summary", summary = summary, fullCount = 20, sentCount = 8
            ).isEmpty()
        )
        // 没有摘要（没压缩过）时不该有无缘由的问题
        assertTrue(
            Compaction.validateOnTheWire(
                system = "只是普通 system", summary = "", fullCount = 0, sentCount = 0
            ).isEmpty()
        )
    }

    // ---------- 源码级：接线必须落在说话的地方 ----------

    private fun source(rel: String): String {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null && !File(dir, "app/src/main/java/io/github/m0use001/planability").isDirectory) {
            dir = dir.parentFile
        }
        requireNotNull(dir) { "找不到仓库根" }
        val f = File(dir, rel)
        assertTrue("源码不在了：$rel", f.isFile)
        return f.readText(Charsets.UTF_8)
    }

    @Test
    fun `压缩标记表已注册且版本至少是 37`() {
        // 只要求"至少 37"：37 已被 compact_markers 占，38 又被长期计划占（phone-c 自己的下一条线），
        // 钉死 37 会被别人后续升级碰红 —— A 在 MemoryProgressTest 上踩过一次，这里照抄那个教训。
        val db = source("app/src/main/java/io/github/m0use001/planability/data/AppDatabase.kt")
        assertTrue("实体没注册进 @Database —— Room 建不出表，写 marker 就崩", db.contains("CompactMarkerEntity::class"))
        assertTrue("DAO 没暴露", db.contains("abstract fun compactMarkerDao()"))
        val v = Regex("version = (\\d+)").find(db)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        assertTrue("读不到 @Database 的 version", v != null)
        assertTrue("version=$v，不到 37 —— compact_markers 是 36→37 进来的", v >= 37)
    }

    @Test
    fun `迁移 36 到 37 建表且挂进了 addMigrations`() {
        val db = source("app/src/main/java/io/github/m0use001/planability/data/AppDatabase.kt")
        val block = db.substringAfter("MIGRATION_36_37", missingDelimiterValue = "")
        assertTrue("MIGRATION_36_37 不见了", block.isNotEmpty())
        assertTrue(
            "迁移里没建 compact_markers —— 老用户升级后写 marker 直接崩",
            block.contains("CREATE TABLE IF NOT EXISTS `compact_markers`")
        )
        // 本库第一个外键：必须连 CASCADE 一起落，否则删会话留一堆孤儿标记
        assertTrue("外键没进迁移", block.contains("FOREIGN KEY(`sessionId`) REFERENCES `chat_sessions`(`id`)"))
        assertTrue("没有 ON DELETE CASCADE", block.contains("ON DELETE CASCADE"))
        assertTrue(
            "迁移没挂进 addMigrations —— 写了不注册等于没迁移",
            db.contains("MIGRATION_35_36, MIGRATION_36_37")
        )
        // 顺序必须是升序（2026-10-05 实测教训：插到前面则整条注册链失效）
        val idx35 = db.indexOf("MIGRATION_34_35, MIGRATION_35_36, MIGRATION_36_37")
        assertTrue("36→37 没有按升序接在 34_35, 35_36 后面", idx35 > 0)
    }

    @Test
    fun `压缩成功后真的写了 marker（锚点是消息 id）`() {
        val chat = source("app/src/main/java/io/github/m0use001/planability/ai/ChatEngine.kt")
        assertTrue(
            "压缩成功路径没有写 marker —— 新表建了却不用，等于白迁移",
            chat.contains("Graph.compactMarkers.markCompact(")
        )
        assertTrue(
            "marker 的锚点用的是计数而不是消息 id（删消息后会错位）",
            chat.contains("anchorMessageId = older.last().id")
        )
    }

    @Test
    fun `shouldSplitOnError 接进主循环，且与主循环的错误同源（不许二次包装）`() {
        // 规定：Compaction.shouldSplitOnError 收到的是主循环手里那条错误的原文。
        // 主循环目前没有统一分类器（错误以 errorMsg 文本往下传），所以这里钉住
        // "不许在 Compaction 里另起一套判断"：将来主循环长出分类器，这条测试要跟着改成
        // 断言转发，而不是让两套规则各走各的。
        val chat = source("app/src/main/java/io/github/m0use001/planability/ai/ChatEngine.kt")
        val failBlock = chat.substringAfter("} catch (e: StreamFailure) {")
            .substringBefore("if (endStatus == \"error\")")
        assertTrue(
            "ChatEngine 主循环的失败路径没有用 shouldSplitOnError —— 谓词写好了却没人调，" +
                "主循环与压缩对同一错误的判断会不一致",
            failBlock.contains("shouldSplitOnError")
        )
        assertTrue(
            "救援收到的不是原始错误（二次包装了）—— 两套规则各走各的比没有更糟",
            failBlock.contains("shouldSplitOnError(e.msg)")
        )
        assertTrue(
            "救援没有限一次 —— 每次失败都压会把一次失败放大成很多次慢调用（B1 的教训）",
            failBlock.contains("rescueUsed")
        )
        assertTrue(
            "救援走了 rescueOversizedSend —— 只改本次请求的内存副本，不碰 DB",
            failBlock.contains("Compaction.rescueOversizedSend")
        )
    }

    @Test
    fun `上线校验的分母必须是压缩前（游标之前）的条数`() {
        // 两个曾经踩过的坑：
        // ① 分母写成"发出去的 + capHistory 截掉的" → 等价于"capHistory 没裁过"，压缩成功时误报；
        // ② 分母写成"摘要游标之后"的 fullHistory.size → 压缩成功时它恰好等于 sentCount，照样误报
        //    （phone-b 2026-10-19 真机抓到的就是这一种）。
        val ctx = source("app/src/main/java/io/github/m0use001/planability/ai/ContextEngine.kt")
        assertTrue(
            "上线校验没有用 plan.preCompactCount（effectiveTranscript 原样长度）",
            ctx.contains("fullCount = plan.preCompactCount")
        )
        assertFalse(
            "分母又变成 sentCount + 已截断了 —— capHistory 是另一码事，与摘要无关",
            Regex("fullCount = sentCount \\+").containsMatchIn(ctx)
        )
        assertFalse(
            "分母又退回摘要游标之后（压缩成功时等于 sentCount，会误报）",
            Regex("fullCount = plan\\.historyFullCount").containsMatchIn(ctx)
        )
        assertTrue("ContextPlan 少了 preCompactCount 字段", ctx.contains("val preCompactCount: Int = 0"))
        assertTrue(
            "assemble 没有把整个 transcript 的条数填进去",
            ctx.contains("preCompactCount = transcript.size")
        )
    }

    @Test
    fun `maybeScheduleSummary 整个函数体必须有兜底异常处理`() {
        // 它跑在 Graph.appScope（SupervisorJob + 无异常处理器）上：逃出任何异常都会杀进程。
        // 改成 suspend 时曾把原来的 runCatching 弄丢，Room 的三个调用当时是裸的。
        val chat = source("app/src/main/java/io/github/m0use001/planability/ai/ChatEngine.kt")
        val body = chat.substringAfter("suspend fun maybeScheduleSummary(")
            .substringBefore("private suspend fun summarizeNow(")
        assertTrue(
            "maybeScheduleSummary 没有把调用包进 try —— 一次 SQLiteException 就能在"
 +
                "『每轮结束』这条路上把应用带走",
            body.contains("return try {")
        )
        assertTrue(
            "CancellationException 必须原样抛（那是我/用户主动取消，不是错误）",
            body.contains("catch (e: CancellationException)")
        )
        assertTrue("失败要记日志，不许静默", body.contains("android.util.Log.w("))
    }

    @Test
    fun `自动压缩默认关，开关走旁路 KV 不动 schema`() {
        val c = source("app/src/main/java/io/github/m0use001/planability/ai/Compaction.kt")
        assertTrue("AUTO_DEFAULT 不是 false", c.contains("const val AUTO_DEFAULT = false"))
        assertTrue(
            "开关写进了 SettingsSnapshot 正式字段 —— 那会连带 persistDiff 的坑" +
                "（开关点了没反应、重启即丢），应该走旁路 KV",
            !c.contains("ctxChips")
        )
        assertTrue("开关走旁路 KV", c.contains("Graph.settings.kvGet(KEY_AUTO)"))
    }
}
