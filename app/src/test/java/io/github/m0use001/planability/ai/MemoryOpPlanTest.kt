package io.github.m0use001.planability.ai

import io.github.m0use001.planability.data.MemoryEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v45 P2 四态决策的纯函数单测（不碰 Room）。
 *
 * 钉的都是「写错就静默污染记忆库」的地方：
 * 1. 模型给了不存在的 target → 降级为 STORE，不丢内容；
 * 2. MERGE 必须立墓碑（superseded）而不是删行；
 * 3. 自动整理不许 DELETE，手动整理才允许；
 * 4. 敏感信息在计划阶段就拦下；
 * 5. 单次写入上限（MAX_OPS）只数真正落库的写操作，SKIP 不占名额。
 */
class MemoryOpPlanTest {

    private fun mem(
        id: Long,
        name: String,
        body: String = "body-$name",
        type: String = "project",
        priority: Int = 50,
        validAt: Long = 100,
        version: Int = 1,
    ) = MemoryEntity(
        id = id, name = name, type = type, body = body, priority = priority,
        validAt = validAt, version = version, projectId = 7
    )

    private fun op(
        action: String,
        name: String,
        body: String = "新内容",
        kind: String = "fact",
        type: String = "project",
        priority: Int? = null,
        scene: String? = null,
        evidence: List<Long> = emptyList(),
        targets: List<String> = emptyList(),
    ) = MemoryOp(action, name, type, kind, "描述", body, priority, scene, evidence, targets)

    private fun plan(
        ops: List<MemoryOp>,
        scoped: List<MemoryEntity> = emptyList(),
        fullReview: Boolean = false,
    ) = planMemoryOps(ops, scoped, projectId = 7, sessionId = 9, now = 1_000L, fullReview = fullReview)

    @Test
    fun `STORE 新记忆带优先级与证据`() {
        val p = plan(listOf(op("STORE", "n1", priority = 85, evidence = listOf(11, 12))))
        assertEquals(1, p.stores.size)
        assertEquals(85, p.stores[0].entity.priority)
        assertEquals(listOf(11L, 12L), p.stores[0].evidence)
        assertEquals(7L, p.stores[0].entity.projectId)
        assertEquals(1, p.stores[0].entity.version)
    }

    @Test
    fun `STORE 同名走覆盖，版本加一`() {
        val existing = mem(1, "n1", body = "旧", version = 3)
        val p = plan(listOf(op("STORE", "n1", body = "新")), listOf(existing))
        assertEquals(0, p.stores.size)
        assertEquals(1, p.updates.size)
        assertEquals("新", p.updates[0].entity.body)
        assertEquals(4, p.updates[0].entity.version)
        assertEquals(1L, p.updates[0].entity.id)
    }

    @Test
    fun `SKIP 不写库也不占写入名额`() {
        val p = plan(
            listOf(
                op("SKIP", "a", targets = listOf("a")),
                op("STORE", "b", body = "b 的内容"),
                op("STORE", "c", body = "c 的内容"),
            ),
            listOf(mem(1, "a"))
        )
        assertEquals(1, p.skipped)
        // MAX_OPS=2：SKIP 不占名额，所以 b、c 都能写进去
        assertEquals(2, p.stores.size)
    }

    @Test
    fun `UPDATE 指向不存在的目标时降级为 STORE`() {
        val p = plan(listOf(op("UPDATE", "x", targets = listOf("不存在"))))
        assertEquals(1, p.stores.size)
        assertEquals(0, p.updates.size)
    }

    @Test
    fun `MERGE 合并多条并立墓碑，validAt 取最早`() {
        val a = mem(1, "a", body = "A", validAt = 100)
        val b = mem(2, "b", body = "B", validAt = 50)
        val p = plan(listOf(op("MERGE", "merged", body = "A+B", targets = listOf("a", "b"))), listOf(a, b))
        assertEquals(0, p.stores.size)
        assertEquals(1, p.updates.size)
        val survivor = p.updates[0].entity
        assertEquals(1L, survivor.id)
        assertEquals("A+B", survivor.body)
        assertEquals(2, survivor.version)
        assertEquals("合并后 validAt 应取最早的事实起点", 50L, survivor.validAt)
        assertEquals(listOf(2L to 1L), p.superseded)
    }

    @Test
    fun `MERGE 没有目标时降级为 STORE`() {
        val p = plan(listOf(op("MERGE", "n", targets = listOf("不存在"))))
        assertEquals(1, p.stores.size)
        assertEquals(0, p.superseded.size)
    }

    @Test
    fun `自动整理不许删除，手动整理才允许`() {
        val existing = mem(1, "a")
        val auto = plan(listOf(op("DELETE", "a", targets = listOf("a"))), listOf(existing), fullReview = false)
        assertEquals(1, auto.blocked)
        assertEquals(0, auto.deletes.size)

        val manual = plan(listOf(op("DELETE", "a", targets = listOf("a"))), listOf(existing), fullReview = true)
        assertEquals(0, manual.blocked)
        assertEquals(listOf(1L), manual.deletes)
    }

    @Test
    fun `敏感信息在计划阶段拦下`() {
        val p = plan(listOf(op("STORE", "id", body = "我的身份证是 11010519900307881X")))
        assertEquals(0, p.stores.size)
        assertEquals(1, p.blocked)
    }

    @Test
    fun `单次最多四条写操作`() {
        val p = plan(
            listOf(
                op("STORE", "a", body = "a 的内容"),
                op("STORE", "b", body = "b 的内容"),
                op("STORE", "c", body = "c 的内容"),
                op("STORE", "d", body = "d 的内容"),
                op("STORE", "e", body = "e 的内容"),
                op("STORE", "f", body = "f 的内容"),
            )
        )
        assertEquals(MEMORY_MAX_OPS, p.stores.size)
    }

    @Test
    fun `主题错配的 UPDATE 降级为 STORE，目标不被改写`() {
        // 真机实证：模型把作息内容 UPDATE 进「语言偏好」，硬规则被毁。
        // 两段正文分词零交集 → 判定点错行，降级为 STORE（无损）。
        val target = mem(1, "语言偏好", body = "以后回答都用中文，不要用英文")
        val p = plan(
            listOf(op("UPDATE", "作息", body = "最近改成早上六点半起床，因为要背单词", targets = listOf("语言偏好"))),
            listOf(target)
        )
        assertEquals(1, p.diverted)
        assertEquals(0, p.updates.size)
        assertEquals(1, p.stores.size)
        assertEquals("作息", p.stores[0].entity.name)
    }

    @Test
    fun `同主题的 UPDATE 正常覆盖`() {
        val target = mem(1, "语言偏好", body = "以后回答都用中文", version = 1)
        val p = plan(
            listOf(op("UPDATE", "语言偏好", body = "回答一律用中文，术语保留英文", targets = listOf("语言偏好"))),
            listOf(target)
        )
        assertEquals(0, p.diverted)
        assertEquals(1, p.updates.size)
        assertEquals(2, p.updates[0].entity.version)
        assertEquals(1L, p.updates[0].entity.id)
    }

    @Test
    fun `解析 ADD 等同 STORE，非法 op 兜底 STORE`() {
        val arr = org.json.JSONArray(
            """[{"op":"ADD","name":"a","body":"x"},{"op":"WHAT","name":"b","body":"y"}]"""
        )
        val parsed = parseMemoryOps(arr)
        assertEquals(listOf("STORE", "STORE"), parsed.map { it.op })
        assertTrue("没给 priority 时要按 kind 给默认值", parsed.all { it.priority == null })
    }

    @Test
    fun `解析 target_name 单数写法`() {
        val arr = org.json.JSONArray("""[{"op":"UPDATE","name":"a","target_name":"旧名","body":"x"}]""")
        assertEquals(listOf("旧名"), parseMemoryOps(arr).first().targets)
    }

    // ───────────────── v45.2 B/A 方案 ─────────────────

    @Test
    fun `引用按 数字id 指纹 名字 三级解析`() {
        val a = mem(1, "a", body = "以后回答都用中文")
        val fp = bodyFingerprint(a.body)
        val byFp = plan(
            listOf(op("UPDATE", "x", body = "回答一律用中文，术语可留英文", targets = listOf("#$fp"))),
            listOf(a)
        )
        assertEquals("指纹命中 → UPDATE", 1, byFp.updates.size)
        val byId = plan(
            listOf(op("UPDATE", "x", body = "回答一律用中文，术语可留英文", targets = listOf("1"))),
            listOf(a)
        )
        assertEquals("数字 id 命中 → UPDATE", 1, byId.updates.size)
        val byName = plan(
            listOf(op("UPDATE", "x", body = "回答一律用中文，术语可留英文", targets = listOf("a"))),
            listOf(a)
        )
        assertEquals("名字命中 → UPDATE", 1, byName.updates.size)
    }

    @Test
    fun `幻觉指纹降级为 STORE，目标不动`() {
        val a = mem(1, "a", body = "以后回答都用中文")
        val p = plan(
            listOf(op("UPDATE", "x", body = "完全无关的新事实内容", targets = listOf("#beef00"))),
            listOf(a)
        )
        assertEquals("指不到目标 → STORE", 1, p.stores.size)
        assertEquals(0, p.updates.size)
    }

    @Test
    fun `候选池跨候选去重且按上限截断，相关列表过滤被截条目`() {
        val scoped = (1L..5L).map { mem(it, "m$it", body = "正文$it") }
        val cands = listOf(op("STORE", "n1", body = "新内容一"), op("STORE", "n2", body = "新内容二"))
        // 两个候选的检索结果都返回全部 5 条（模拟 topK 放行）
        val res = buildCandidatePool(cands, scoped, retrieved = listOf(scoped, scoped), updatedTextOf = { "t" })
        assertEquals(5, res.pool.size)
        assertEquals(2, res.related.size)
        assertTrue(res.related.all { it.size == 5 })
        // maxPool=3：池截断到 3，相关列表里指向第 4、5 条的指纹必须被过滤掉
        val res3 = buildCandidatePool(cands, scoped, listOf(scoped, scoped), { "t" }, maxPool = 3)
        assertEquals(3, res3.pool.size)
        assertTrue(res3.related.all { rel -> rel.all { fp -> res3.pool.any { it.fingerprint == fp } } })
    }

    @Test
    fun `冲突决策映射四态与兜底`() {
        val scoped = listOf(mem(1, "语言偏好", body = "以后回答都用中文"))
        val cands = listOf(
            op("STORE", "作息", body = "最近改成六点半起床", kind = "episode"),
            op("STORE", "中文补充", body = "回答一律用中文，术语留英文", kind = "feedback"),
            op("STORE", "漏答的", body = "没人理我"),
        )
        val pool = listOf(PoolView(bodyFingerprint(scoped[0].body), 1, "语言偏好", "feedback", 90, "", "t", scoped[0].body))
        val decisions = parseConflictOps(
            // 目标指向候选池里的「语言偏好」；这里只验**映射**——内容是否错配由规划器 looksUnrelated 兜底
            org.json.JSONArray(
                """[{"cid":"N1","action":"update","target_ids":["#${bodyFingerprint(scoped[0].body)}"]}]"""
            )
        )
        val ops = mapConflictToOps(decisions, cands, pool)
        assertEquals("N1 映射为 UPDATE", "UPDATE", ops[0].op)
        assertEquals("N2 未被决策 → store 兜底", "STORE", ops[2].op)
        // store + 新正文分词集 ⊆ 关联候选正文 → 子集判定降级 SKIP（无增量）；
        // 用「都用中文」：其二元组（都用/用中/中文）全部落在池正文「以后回答都用中文」的二元组里
        val subset = parseConflictOps(
            org.json.JSONArray("""[{"cid":"N2","action":"store","content":"都用中文","target_ids":["#${bodyFingerprint(scoped[0].body)}"]}]""")
        )
        val ops2 = mapConflictToOps(subset, cands, pool)
        assertEquals("新正文是候选池正文的子集 → SKIP", "SKIP", ops2.first { it.name == "中文补充" }.op)
    }

    @Test
    fun `冲突解析失败时映射退化为全部 STORE`() {
        val cands = listOf(op("STORE", "a", body = "内容a"), op("STORE", "b", body = "内容b"))
        val ops = mapConflictToOps(emptyList(), cands, emptyList())
        assertEquals(2, ops.size)
        assertTrue(ops.all { it.op == "STORE" })
    }
}
