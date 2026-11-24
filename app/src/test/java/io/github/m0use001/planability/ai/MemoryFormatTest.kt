package io.github.m0use001.planability.ai

import io.github.m0use001.planability.data.memoryHalfLifeDays
import io.github.m0use001.planability.data.memoryPriorityScore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v36 B 项第三步：记忆格式与整理器的**纯逻辑**单测。
 *
 * 钉的都是"错一步就静默出事"的地方：
 * 1. 五道闸条款必须在 prompt 里（少一条，模型就开始记推断/记一次性琐事）；
 * 2. 敏感信息必须有代码兜底（提示词说"永不写入"不算数，模型会忘）；
 * 3. kind→type 的归一是零迁移的关键（加错一次就得动表）；
 * 4. 整理输入必须带"今天日期"和清单（否则模型把上个月的事当成今天）。
 */
class MemoryFormatTest {

    @Test
    fun `五道闸与敏感条款都在规则里`() {
        for (key in listOf(
            "第 1 道：[陈述] 测试",
            "第 2 道：[陈旧性] 测试",
            "第 3 道：[校准]",
            "第 4 道：[候选去重]",
            "第 5 道：[体量]",
            "敏感信息",
            "永不写入：身份证号、银行卡号",
        )) {
            assertTrue("规则里少了：$key", MEMORY_FORMAT_RULES.contains(key))
        }
    }

    @Test
    fun `handoff prompt 明令禁止写长期事实`() {
        val p = buildHandoffPrompt("用户：在刷高数")
        assertTrue("没禁止写长期事实 —— handoff 会越长越大，最后和长期记忆混在一起", p.contains("不许写长期事实"))
    }

    @Test
    fun `整理输入带今天日期与清单、并要求只输出 JSON`() {
        val input = buildConsolidationInput(
            listing = "- plan-a｜project｜四年规划",
            turnsText = "[用户 #12] 我周四下午没课",
            todayHint = "2026-10-19",
        )
        assertTrue(input.contains("2026-10-19"))
        assertTrue(input.contains("plan-a"))
        assertTrue(input.contains("[用户 #12] 我周四下午没课"))
        assertTrue(
            "没要求只输出 JSON —— 模型会带解释，解析必失败",
            input.contains("只输出 JSON 数组") || input.contains("JSON")
        )
        assertTrue(
            "空清单要显式告知，否则模型以为记忆库是空的随便写",
            input.contains("<memory_listing>")
        )
    }

    @Test
    fun `身份证与银行卡被代码拦下，普通长数字不误伤`() {
        // 这两个样本都是**校验位正确**的真格式（末位 X / 通过 Luhn）：
        // 拿"形状像但校验位不对"的号码来测，测的是运气不是拦截逻辑。
        assertNotNull("身份证没拦住", looksSensitive("我的身份证是 11010519900307881X"))
        assertNotNull("银行卡没拦住", looksSensitive("卡号 4111111111111111"))
        assertNull("普通手机号不该被当敏感信息", looksSensitive("电话 13800138000"))
        assertNull("普通学号不该被当敏感信息", looksSensitive("学号 20211234"))
    }

    @Test
    fun `身份证校验位错的号码不拦（避免误伤）`() {
        // 形状对但末位校验不通过 → 不是真身份证号，不该拦
        assertNull(looksSensitive("身份证 110105199003078881"))
    }

    @Test
    fun `kind 归一到已有 type，不动表`() {
        // fact → project；episode/draft → reference；procedure → feedback
        val m = MemoryConsolidator.normalizeTypeForTest("", "fact")
        assertEquals("project", m)
        assertEquals("reference", MemoryConsolidator.normalizeTypeForTest("", "episode"))
        assertEquals("reference", MemoryConsolidator.normalizeTypeForTest("", "draft"))
        assertEquals("feedback", MemoryConsolidator.normalizeTypeForTest("", "procedure"))
        // 显式给了合法 type 就以它为准（兼容老调用方）
        assertEquals("skill", MemoryConsolidator.normalizeTypeForTest("skill", "procedure"))
        // 未知 kind 兜底 project，不抛异常
        assertEquals("project", MemoryConsolidator.normalizeTypeForTest("", "不知道是啥"))
    }

    @Test
    fun `整理默认只增不删，用户手动才允许删`() {
        // 这条是策略断言：自动整理出现 DELETE 时必须被拦下并计数
        val autoAllowed = MemoryConsolidator.deleteAllowedForTest(fullReview = false)
        val manualAllowed = MemoryConsolidator.deleteAllowedForTest(fullReview = true)
        assertFalse("自动整理不该能删记忆", autoAllowed)
        assertTrue("用户手动整理应该能删", manualAllowed)
    }

    @Test
    fun `抽取不判冲突，四态在冲突 prompt 里（A 方案两段式）`() {
        // v45.2：抽取调用只出候选，四态决策移到第二次调用（TencentDB l1-extraction/l1-dedup 同构拆分）
        val input = buildConsolidationInput(listing = "", turnsText = "", todayHint = "2026-10-29")
        assertTrue("抽取输出不该再带 op/target", !input.contains("target_names"))
        assertTrue("抽取输出不该再带 op", !input.contains("\"op\""))
        val sys = buildConflictSystemPrompt()
        assertTrue("冲突 prompt 没开放四态", sys.contains("store|skip|update|merge"))
        assertTrue("冲突规则没写 MERGE 不是删除", CONFLICT_RULES.contains("合并不是删除"))
        assertTrue("冲突规则没写主题对不上绝不改写", CONFLICT_RULES.contains("主题对不上就绝不改写"))
    }

    @Test
    fun `冲突 prompt 展示候选池全文并标注关联候选`() {
        val pool = listOf(
            PoolView("#a3f2c1", 1, "语言偏好", "feedback", 90, "对话", "10-30 11:00", "以后回答都用中文"),
        )
        val cands = listOf(
            CandidateView("N1", "最近改成六点半起床", "reference", 70, "作息", listOf("#a3f2c1")),
            CandidateView("N2", "新的独立事实", "project", 60, "学习", emptyList()),
        )
        val p = buildConflictUserPrompt(pool, cands)
        assertTrue("候选池正文要进 prompt（模型必须看得见要改写什么）", p.contains("以后回答都用中文"))
        assertTrue("池条目要带指纹", p.contains("#a3f2c1"))
        assertTrue("新候选要带 cid 与关联候选", p.contains("### N1") && p.contains("#a3f2c1"))
        assertTrue("无关联的候选要标明倾向 store", p.contains("倾向 store"))
    }

    @Test
    fun `类型衰减半衰期：教训比项目上下文活得久`() {
        // 2026-10-29 吸纳 kaijibot「类别衰减半衰期」：
        // 统一 30 天会让踩过的坑和一次闲聊同速淡出，长期学习助手最该保住的是纠错。
        assertTrue(memoryHalfLifeDays("feedback") > memoryHalfLifeDays("project"))
        assertTrue(memoryHalfLifeDays("skill") >= memoryHalfLifeDays("feedback"))
        assertTrue(memoryHalfLifeDays("reference") > memoryHalfLifeDays("project"))
        assertEquals(30.0, memoryHalfLifeDays("project"), 0.0)
        assertEquals(30.0, memoryHalfLifeDays("没见过的类型"), 0.0)
    }

    @Test
    fun `优先级打分：死命令满分、0到100归一`() {
        // v45：priority 取代 importance 参与召回打分。死命令（-1）必须是满分，
        // 否则用户说“以后都这样”的规则会排到最后，语义正好反了。
        assertEquals(1.0, memoryPriorityScore(-1), 0.0)
        assertEquals(0.85, memoryPriorityScore(85), 0.0001)
        assertEquals(0.0, memoryPriorityScore(0), 0.0)
        assertEquals(1.0, memoryPriorityScore(150), 0.0)
    }

    @Test
    fun `召回与工作层都过滤失效与合并墓碑`() {
        // 源码级断言：这两处过滤一旦被删，墓碑行会重新被注入（用户会看到被纠正过的旧说法），
        // 而编译与其它测试都发现不了。
        val src = sourceOf("app/src/main/java/io/github/m0use001/planability/data/MemoryEntities.kt")
        assertTrue("工作层没过滤失效/墓碑", src.contains("invalidAt = 0 OR invalidAt > :now"))
        assertTrue("召回没过滤墓碑", src.contains("m.supersededBy == 0L"))
    }

    @Test
    fun `单次最多四条、单条最多 800 字`() {
        // 2→4（2026-10-30）：名额压力会诱发打错目标的 UPDATE，且批处理修复后
        // 4 轮/批只写 2 条仍会丢事实。上限变更时 MAX_TURNS_PER_RUN 会自动跟着变。
        assertEquals(4, MEMORY_MAX_OPS)
        assertEquals(800, MEMORY_BODY_MAX)
    }

    // ────── 项目作用域（2026-10-25 补 [assign]：写入链路漏 projectId）──────

    private fun mem(name: String, projectId: Long, body: String = "b-$name") =
        io.github.m0use001.planability.data.MemoryEntity(
            id = name.hashCode().toLong(), name = name, type = "project",
            body = body, projectId = projectId
        )

    @Test
    fun `写入作用域是本项目加全局，跨项目同名不进来`() {
        val existing = listOf(
            mem("主角设定", projectId = 0L),      // 全局
            mem("主角设定", projectId = 2L),      // 别的项目，同名
            mem("写作偏好", projectId = 7L),      // 本项目
        )
        val scoped = MemoryConsolidator.scopedMemories(existing, projectId = 7L)
        // 同名两条里只能看到全局那条 —— UPDATE/DELETE/sameName 匹配都建在这份清单上，
        // 只要这里不含别的项目，跨项目同名就误改不了
        assertEquals(listOf(0L, 7L), scoped.map { it.projectId })
        assertEquals(1, scoped.count { it.name == "主角设定" })
        assertEquals(0L, scoped.first { it.name == "主角设定" }.projectId)
    }

    @Test
    fun `全局会话作用域退化为全库`() {
        // projectId=0（未挂项目的会话）：项目记忆不该被排除，否则老会话整理会漏改
        val existing = listOf(mem("a", 0L), mem("b", 3L))
        assertEquals(existing, MemoryConsolidator.scopedMemories(existing, projectId = 0L))
    }

    @Test
    fun `projectId 传递链被源码钉住`() {
        // applyOps 是 private 且直连 Graph（Room），纯 JVM 测试进不去；
        // 按 MemoryProgressTest 的惯例用源码断言钉住四条链：
        val src = sourceOf("app/src/main/java/io/github/m0use001/planability/ai/MemoryConsolidator.kt")
        // v54：普通会话直传 session.projectId；助理中枢（KEY_HUB）会话是**有意特例**——
        // 它整理出的记忆要落全局（projectId=0，画像第一描绘人），走 memProjectId 归零分支。
        // 两个形态必须二缺一不可：直接传原值，或带 hub 归零逻辑的 memProjectId。
        val direct = src.contains("applyOps(sessionId, session.projectId,")
        val hubGlobal = src.contains("applyOps(sessionId, memProjectId,") &&
            src.contains("KEY_HUB") && src.contains("if (isHub) 0L")
        assertTrue(
            "consolidateOne 没把 projectId 传给 applyOps（既不是直传 session.projectId，也不是 hub 归零特例）",
            direct || hubGlobal
        )
        // v45 P2：新建记忆的实体构造搬到了 MemoryOpPlan.storeOrUpdate，断言跟着搬
        val planner = sourceOf("app/src/main/java/io/github/m0use001/planability/ai/MemoryOpPlan.kt")
        assertTrue(
            "新建记忆没落 projectId/originSession —— 项目会话整理出的记忆会全部落全局",
            planner.contains("originSession = sessionId") && planner.contains("projectId = projectId")
        )
        assertTrue("新记忆没给 priority 默认值", planner.contains("defaultPriorityForKind(op.kind)"))
        assertTrue(
            "hashSeen/同名匹配没限定在作用域清单上",
            src.contains("val scoped = scopedMemories(existing, projectId)")
        )
        val tool = sourceOf("app/src/main/java/io/github/m0use001/planability/ai/ptl/ToolExecImpls.kt")
        assertTrue(
            "memory_add 没记 projectId/originSession",
            tool.contains("originSession = sessionId, projectId = sessionProject") &&
                tool.contains("Graph.chat.session(sessionId)?.projectId ?: 0L")
        )
    }

    private fun sourceOf(rel: String): String {
        val dir = java.io.File(System.getProperty("user.dir") ?: ".").absoluteFile
        val root = generateSequence(dir) { it.parentFile }
            .firstOrNull { java.io.File(it, "app/src/main/java/io/github/m0use001/planability").isDirectory }
        assertTrue("找不到仓库根（从 ${System.getProperty("user.dir")} 向上）", root != null)
        val f = java.io.File(root, rel)
        assertTrue("源码不在了：$rel", f.isFile)
        return f.readText(Charsets.UTF_8)
    }
}
