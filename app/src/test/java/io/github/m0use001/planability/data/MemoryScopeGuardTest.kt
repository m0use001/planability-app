package io.github.m0use001.planability.data

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * v57 记忆项目隔离守卫（2026-11-21 小说助理串味审计）。
 *
 * 三条病根，每条钉死在源码字面量上（回归即红）：
 * ①`memory_search` 曾用 `recall(q, 5)` 不传 scope → 全库召回，别的项目条目全进小说会话；
 * ②`touchScene`/`upsert` 曾按名字**全局**匹配 → 跨项目同名互相灌/互相覆盖（数据丢失）；
 * ③memories/memory_scenes 唯一索引曾是全局 (name) → 跨项目同名被迫挤一行。
 * 修复形态：唯一索引 (name, projectId) + MIGRATION_56_57 + 检索/按名读写全部项目化。
 *
 * 断言读源码、与编译无关；CRLF 一律归一化后再匹配（仓库既有约定）。
 */
class MemoryScopeGuardTest {

    private fun source(rel: String): String {
        val f = File(rel)
        assertTrue("源码不在了（cwd 应为 app 模块目录）：$rel", f.isFile)
        return f.readText(Charsets.UTF_8).replace("\r\n", "\n")
    }

    @Test
    fun `唯一索引必须是 name+projectId 两张表都要`() {
        val src = source("src/main/java/io/github/m0use001/planability/data/MemoryEntities.kt")
        val hits = "Index(value = [\"name\", \"projectId\"], unique = true)".let {
            Regex(Regex.escape(it)).findAll(src).count()
        }
        assertTrue("memories 与 memory_scenes 两张表的唯一索引都必须是 (name, projectId)，实际出现 $hits 次", hits >= 2)
        assertTrue("memories 表不许再留全局唯一 (name) 索引", !src.contains("Index(value = [\"name\"], unique = true)"))
    }

    @Test
    fun `迁移 56_57 必须存在并换掉两个旧索引`() {
        val db = source("src/main/java/io/github/m0use001/planability/data/AppDatabase.kt")
        // 2026-11-24 v58（图标素材剔除）之后版本号钉 58；v57 的迁移与登记必须仍在
        assertTrue("DB 版本必须升到 58", db.contains("version = 58"))
        assertTrue("必须有 MIGRATION_56_57 定义", db.contains("MIGRATION_56_57 = object"))
        assertTrue("迁移必须挂进 addMigrations", db.contains(", MIGRATION_56_57,"))
        for (idx in listOf("`index_memories_name_projectId`", "`index_memory_scenes_name_projectId`")) {
            assertTrue("迁移必须创建 $idx（与 Room 注解默认名一致，否则启动 schema 校验崩）", db.contains(idx))
        }
        assertTrue("迁移必须删掉旧的全局唯一索引 index_memories_name", db.contains("DROP INDEX IF EXISTS `index_memories_name`"))
        assertTrue("迁移必须删掉旧的全局唯一索引 index_memory_scenes_name", db.contains("DROP INDEX IF EXISTS `index_memory_scenes_name`"))
    }

    @Test
    fun `upsert 同名覆盖必须严格同项目`() {
        val src = source("src/main/java/io/github/m0use001/planability/data/MemoryEntities.kt")
        assertTrue(
            "MemoryRepo.upsert 必须用 byNameInProject(m.name, m.projectId)（跨项目同名不许复用 id）",
            src.contains("dao.byNameInProject(m.name, m.projectId)")
        )
        assertTrue(
            "upsert 不许再按全局 byName 找已有行（跨项目覆盖=静默数据丢失）",
            !src.contains("val existing = dao.byName(m.name)")
        )
    }

    @Test
    fun `touchScene 必须按项目内名字找场景`() {
        val src = source("src/main/java/io/github/m0use001/planability/data/MemoryLayers.kt")
        assertTrue(
            "touchScene 必须用 byNameInProject(scene, projectId)（全局 byName 是场景串味的机制根源）",
            src.contains("dao.byNameInProject(scene, projectId)")
        )
        assertTrue("touchScene 不许再按全局 byName 找场景", !src.contains("dao.byName(scene)"))
        assertTrue("场景封顶必须按项目各自算（activeOwnedBy）", src.contains("dao.activeOwnedBy(projectId, MAX_SCENES + 50, now)"))
    }

    @Test
    fun `recall 的项目作用域必须提纯为可单测判据`() {
        val src = source("src/main/java/io/github/m0use001/planability/data/MemoryEntities.kt")
        assertTrue(
            "必须有 internal fun recallableInScope(m: MemoryEntity, projectScope: Long): Boolean 纯函数",
            src.contains("internal fun recallableInScope(m: MemoryEntity, projectScope: Long): Boolean")
        )
        assertTrue("recall 过滤必须走 recallableInScope", src.contains("recallableInScope(m, projectScope)"))
    }

    @Test
    fun `memory_search 语义路必须传会话项目 scope`() {
        val exec = source("src/main/java/io/github/m0use001/planability/ai/ptl/ToolExecImpls.kt")
        assertTrue(
            "memory_search 必须传会话项目：recall(q, 5, sessionProject)——不传 scope=全库召回=串味",
            exec.contains("recall(q, 5, sessionProject)")
        )
        assertTrue("不许再有无 scope 的 recall(q, 5)", !exec.contains("recall(q, 5)"))
    }

    @Test
    fun `按名字读记忆必须走项目作用域`() {
        val exec = source("src/main/java/io/github/m0use001/planability/ai/ptl/ToolExecImpls.kt")
        assertTrue(
            "必须有 visibleMemory 作用域读助手（id 路也要复核可见性）",
            exec.contains("private suspend fun visibleMemory(key: String, sessionId: Long): MemoryEntity?")
        )
        assertTrue(
            "memory_search 精确命中必须走 byNameVisible",
            exec.contains("byNameVisible(q, sessionProject)")
        )
        assertTrue(
            "memory_invalidate 的循环与 replaced_by 都必须走 visibleMemory",
            exec.contains("val replaced = if (replacedRaw.isEmpty()) null else visibleMemory(replacedRaw, sessionId)") &&
                exec.contains("val m = visibleMemory(key, sessionId)")
        )
        assertTrue(
            "memory_forget 必须走 visibleMemory",
            exec.contains("val m = visibleMemory(key, sessionId)") || exec.contains("visibleMemory(key, sessionId)")
        )
        assertTrue(
            "ToolExecImpls 里不许再有任何 memoryDao().byName( 全局裸查（跨项目翻记忆的口子全关）",
            !exec.contains("memoryDao().byName(")
        )
    }
}
