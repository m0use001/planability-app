package io.github.m0use001.planability.ai.ptl

import io.github.m0use001.planability.data.AssistantRepo
import io.github.m0use001.planability.data.PendingItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v53 助理中枢契约：内置私人助理、四个新工具在 DOMAINS 可达、PendingItem 新字段默认值
 * （存量行迁移后 source=capture / confidence=-1，恢复路径 BackupManager 同口径）。
 */
class AssistantHubContractTest {

    @Test
    fun hubAssistantSeeded() {
        val hub = AssistantRepo.builtinAssistants().firstOrNull { it.key == AssistantRepo.KEY_HUB }
        assertNotNull("内置助手必须有 key=hub（私人助理）", hub)
        assertTrue("hub 人格提示词不能为空", hub!!.systemPrompt.isNotBlank())
        assertTrue("hub 必须是 builtin（缺种子自动补回）", hub.builtin)
        // 提示词要教 AI 用入库工具，否则排完期不会写时间轴
        assertTrue("提示词要提到 pending_commit", hub.systemPrompt.contains("pending_commit"))
    }

    @Test
    fun newToolsReachableInDomains() {
        val info = ToolRegistry.DOMAINS["info"].orEmpty()
        listOf("pending_commit", "pending_ignore", "pending_suggest").forEach {
            assertTrue("info 域必须登记 $it（否则模型看不到，调用必报未知工具）", it in info)
        }
        assertTrue("schedule 域必须登记 timeline_query", "timeline_query" in ToolRegistry.DOMAINS["schedule"].orEmpty())
    }

    @Test
    fun toolDefsHaveIds() {
        val names = ToolRegistry.ALL.map { it.name }
        listOf("pending_commit", "pending_ignore", "pending_suggest", "timeline_query").forEach {
            assertTrue("ToolDefs.ALL 缺 $it", it in names)
        }
        // pending_commit 必须带 CONFIRM 闸门（入库是写操作，走确认卡）
        val commit = ToolRegistry.ALL.first { it.name == "pending_commit" }
        assertEquals(Risk.CONFIRM, commit.risk)
        // id 是引用【待处理清单】#n 的关键参数
        assertNotNull(commit.params.firstOrNull { it.name == "id" })
    }

    @Test
    fun pendingItemDefaultsMatchMigration() {
        // MIGRATION_52_53 的 DEFAULT 与这里必须同口径：存量行都是信息提取来的草稿
        val item = PendingItem(title = "测试")
        assertEquals("capture", item.source)
        assertEquals(0L, item.originProjectId)
        assertEquals(0L, item.originSessionId)
        assertEquals(0L, item.suggestedStart)
        assertEquals(0L, item.suggestedEnd)
        assertEquals(-1f, item.confidence)
        // v54：payload 默认空（备份/恢复与 MIGRATION_53_54 同口径）
        assertEquals("", item.payload)
        assertEquals("pending", item.status)
    }

    /**
     * v54 统一审核契约：普通会话创建待办/日程的**唯一产物是中枢草稿**。
     * PlanTools 的两个创建函数必须写 pending_items（带 payload + 来源标记），
     * 不许再直接 saveTodo/saveSchedule——直接进时间轴的唯一入口是 pending_commit。
     * 用源码断言（与 ExtractProjectContractTest 同风格）：这是行为红线，防回退。
     */
    @Test
    fun creationToolsRouteThroughHub() {
        val planSrc = sourceOf("app/src/main/java/io/github/m0use001/planability/ai/ptl/PlanTools.kt")
        assertTrue("todoCreate 必须产出中枢草稿（带 payload）",
            planSrc.contains("payload = a.toString()"))
        assertTrue("创建工具要向用户交代草稿去向（统一审核话术）",
            planSrc.contains("还没进时间轴，用户在助理中枢确认后才生效"))
        // 入库通道唯一：PendingImporter 存在且 pending_commit 走它
        val importer = sourceOf("app/src/main/java/io/github/m0use001/planability/ai/ptl/PendingImporter.kt")
        assertTrue("PendingImporter 必须存在（统一入库通道）", importer.contains("suspend fun commit"))
        val lifeSrc = sourceOf("app/src/main/java/io/github/m0use001/planability/ai/ptl/LifeTools.kt")
        assertTrue("pending_commit 必须走 PendingImporter", lifeSrc.contains("PendingImporter.commit(item"))
    }

    /**
     * v54 画像职责契约：助理中枢（私人助理）整理出的记忆必须落**全局**作用域。
     * MemoryConsolidator 对 hub 会话要把 projectId 归零——否则用户在私人助理这里
     * 说出的偏好/教训被锁进「助理中枢」项目，别的项目看不见、画像重建也吃不到。
     */
    @Test
    fun hubMemoriesGoGlobal() {
        val src = sourceOf("app/src/main/java/io/github/m0use001/planability/ai/MemoryConsolidator.kt")
        assertTrue("中枢记忆全局化：consolidateOne 必须按 hub 身份归零 projectId",
            src.contains("KEY_HUB") && src.contains("memProjectId"))
        assertTrue("applyOps 必须用归零后的作用域", src.contains("applyOps(sessionId, memProjectId"))
    }

    /** 与 MemoryFormatTest.sourceOf 同法：从测试工作目录向上找仓库根再读源码 */
    private fun sourceOf(rel: String): String {
        val dir = java.io.File(System.getProperty("user.dir") ?: ".").absoluteFile
        val root = generateSequence(dir) { it.parentFile }
            .firstOrNull { java.io.File(it, "app/src/main/java/io/github/m0use001/planability").isDirectory }
        org.junit.Assert.assertNotNull("找不到仓库根（从 ${System.getProperty("user.dir")} 向上）", root)
        val f = java.io.File(root!!, rel)
        org.junit.Assert.assertTrue("源码不在了：$rel", f.isFile)
        return f.readText(Charsets.UTF_8)
    }

    /**
     * 2026-11-06 真机实锤的 bug 防线：老库里助手表**非空**时，
     * 后来新增的内置助手也必须补种。
     *
     * 原实现只在「表空 或 通用助手缺失」时整批 insertAll，于是 KEY_HUB 在存量库里
     * 永远进不去——AssistantHubScreen 拿不到 hub 助手 → 发送键永远禁用（点了没反应）。
     * 这里用假 DAO 模拟「已有 general/planner，没有 hub」的库，断言 ensureSeeded 补上。
     */
    @Test
    fun ensureSeededBackfillsNewBuiltinOnExistingDb() = kotlinx.coroutines.runBlocking {
        val existing = AssistantRepo.builtinAssistants()
            .filter { it.key != AssistantRepo.KEY_HUB }   // 老库：hub 还不存在
            .mapIndexed { i, a -> a.copy(id = (i + 1).toLong()) }
        val dao = FakeAssistantDao(existing)
        AssistantRepo(dao).ensureSeeded()
        assertNotNull("存量库必须补种缺失的 hub 助手", dao.byKey(AssistantRepo.KEY_HUB))
        // 已有行不许被改动（用户可能改过人格）
        assertEquals(existing.size + 1, dao.all().size)
    }

    /** 最小假 DAO：只实现 ensureSeeded 用到的 all/byKey/count/insertAll */
    private class FakeAssistantDao(initial: List<io.github.m0use001.planability.data.Assistant>) :
        io.github.m0use001.planability.data.AssistantDao {
        private val rows = initial.toMutableList()
        private var nextId = (initial.maxOfOrNull { it.id } ?: 0L) + 1
        override suspend fun all() = rows.toList()
        override suspend fun byId(id: Long) = rows.firstOrNull { it.id == id }
        override suspend fun byKey(key: String) = rows.firstOrNull { it.key == key }
        override suspend fun count() = rows.size
        override suspend fun insertAll(list: List<io.github.m0use001.planability.data.Assistant>) {
            list.forEach { a ->
                if (rows.none { it.key == a.key }) rows += a.copy(id = nextId++)
            }
        }
        override suspend fun upsert(a: io.github.m0use001.planability.data.Assistant): Long {
            val i = rows.indexOfFirst { it.id == a.id || it.key == a.key }
            if (i >= 0) rows[i] = a else rows += a
            return a.id
        }
        override suspend fun deleteCustom(id: Long) {
            rows.removeAll { it.id == id && !it.builtin }
        }
    }
}
