package io.github.m0use001.planability.ai.ptl

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * DeepSeek 搜索链路的**结构守卫**（M2 批 3：行为测试已迁入 `shared` 的 DeepSeekSourcesTest，
 * 这里只留「读 app 源码断言接线仍在」的部分——它们测的是 app 内部接线，不适合搬进 shared）。
 */
class DeepSeekSourcesTest {

    /**
     * 四象限可预测性：会话级「关」必须压过全局开——否则用户明确选了不联网，
     * 全局开关却偷偷给他联网（花钱 + 行为不可预测）。
     */
    @Test
    fun `会话级联网开关优先级高于全局`() {
        val s = read("ai/AiStream.kt")
        val block = s.substringAfter("if (echoEmptyReasoning)")
        assertTrue("会话级关没压过全局", block.contains("if (webSearch == false) emptyList()"))
        assertTrue("会话级开没生效", block.contains("else if (webSearch == true)"))
        assertTrue("跟随全局的分支丢了", block.contains("else io.github.m0use001.planability.ai.ptl.DeepSeekCloud.serverToolTypes()"))
        // 引擎侧传值：'' 跟随 / '1' 开 / '0' 关
        val e = read("ai/ChatEngine.kt")
        assertTrue("引擎没把会话档传下去", e.contains("webSearch = when (session.webSearch)"))
    }

    /** 来源必须附在正文末尾（否则用户看不到引用），且没来源时一个字都不加 */
    @Test
    fun `来源附正文且无来源零变化`() {
        val e = read("ai/ChatEngine.kt")
        assertTrue("来源没附到正文", e.contains("roundContent + sourcesBlock"))
        assertTrue("无来源时应为空串", e.contains("if (serverSources.isNotEmpty())"))
        val s = read("ai/AiStream.kt")
        assertTrue("空来源不该发事件", s.contains("if (!serverSources.isEmpty)"))
    }

    /** 迁移齐全：v56 字段 + 迁移注册（缺一个 = 升级即崩）。v57 起尾钉升级（2026-11-21） */
    @Test
    fun `v56 迁移三件套齐全`() {
        val db = read("data/AppDatabase.kt")
        assertTrue("迁移体丢了", db.contains("private val MIGRATION_55_56"))
        assertTrue("字段没加", db.contains("ADD COLUMN webSearch TEXT NOT NULL DEFAULT ''"))
        val ent = read("data/ChatEntities.kt")
        assertTrue("实体没字段", ent.contains("val webSearch: String = \"\""))
        assertTrue("DAO 没更新方法", ent.contains("setSessionWebSearch"))
    }

    /** v57（2026-11-21 记忆项目隔离）：版本钉 57 + 新迁移登记 + 索引替换齐活 */
    @Test
    fun `v57 迁移三件套齐全`() {
        val db = read("data/AppDatabase.kt")
        assertTrue("版本没升到 57", db.contains("version = 57,"))
        assertTrue("迁移体丢了", db.contains("private val MIGRATION_56_57"))
        assertTrue(
            "迁移没登记进 addMigrations",
            db.contains("MIGRATION_55_56, MIGRATION_56_57)")
        )
        assertTrue(
            "v57 必须把 memories/memory_scenes 唯一索引换成 (name, projectId)",
            db.contains("`index_memories_name_projectId`") && db.contains("`index_memory_scenes_name_projectId`")
        )
    }

    private fun read(rel: String): String {
        val roots = listOf(
            // M5：data/ 实体/DAO 已迁 shared；cwd 为 app 模块，shared 在 ../shared。
            "../shared/src/jvmShared/kotlin/io/github/m0use001/planability/",
            "app/src/main/java/io/github/m0use001/planability/",
            "src/main/java/io/github/m0use001/planability/",
        )
        val found = roots.map { java.io.File(it + rel) }.filter { it.isFile }
        if (found.isNotEmpty()) return found.joinToString("\n") { it.readText() }
        return java.io.File("app/src/main/java/io/github/m0use001/planability/" + rel).readText()
    }
}
