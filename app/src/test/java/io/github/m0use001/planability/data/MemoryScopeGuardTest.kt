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
        // M5：data/ 实体/DAO 已迁 shared（cwd=app 模块，shared 在 ../shared）。拆分文件两半合并。
        val s = File(rel.replace("src/main/java/", "../shared/src/jvmShared/kotlin/"))
        val f = File(rel)
        val parts = listOfNotNull(s.takeIf { it.isFile }, f.takeIf { it.isFile })
        assertTrue("源码不在了（cwd 应为 app 模块目录）：$rel", parts.isNotEmpty())
        return parts.joinToString("\n") { it.readText(Charsets.UTF_8) }.replace("\r\n", "\n")
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
        assertTrue("DB 版本必须升到 57", db.contains("version = 57"))
        assertTrue("必须有 MIGRATION_56_57 定义", db.contains("MIGRATION_56_57 = object"))
        assertTrue("迁移必须挂进 addMigrations", db.contains(", MIGRATION_56_57)"))
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
        assertTrue("visibleMemory 必须走 memoryScopeOf", exec.contains("val scope = memoryScopeOf(sessionId)"))
        assertTrue("visibleMemory 必须用 recallableInScope 复核", exec.contains("recallableInScope(m, scope)"))
        assertTrue(
            "memory_search 精确命中必须走 byNameVisible",
            exec.contains("byNameVisible(q, sessionProject)")
        )
        // 2026-11-26：删除/作废改走严格归属（ownedMemory），不再用「可见」范围
        assertTrue(
            "memory_invalidate 的循环与 replaced_by 都必须走 ownedMemory（可见≠可删）",
            exec.contains("val replaced = if (replacedRaw.isEmpty()) null else ownedMemory(replacedRaw, sessionId)") &&
                exec.contains("val m = ownedMemory(key, sessionId)")
        )
        assertTrue(
            "memory_forget 必须走 ownedMemory",
            exec.contains("val m = ownedMemory(key, sessionId)")
        )
        // 全局 byName 只允许出现在 hub（scope<0）分支里
        assertTrue(
            "全局裸查 byName 只允许在 scope<0 的 hub 分支",
            exec.contains("if (scope < 0L) Graph.db.memoryDao().byName(key)")
        )
    }

    // ---------- 2026-11-26 小说会话事故的回归门 ----------

    @Test
    fun `删除必须严格归属本项目`() {
        val exec = source("src/main/java/io/github/m0use001/planability/ai/ptl/ToolExecImpls.kt")
        assertTrue(
            "必须有 ownedMemory 严格归属判据（按名只查本项目）",
            exec.contains("private suspend fun ownedMemory(key: String, sessionId: Long): MemoryEntity?") &&
                exec.contains("byNameInProject(key, pid)")
        )
        assertTrue(
            "ownedMemory 必须复核 projectId == pid（别的项目/inIndex 一律不可删）",
            exec.contains("return if (m.projectId == pid) m else null")
        )
    }

    @Test
    fun `handoff 不许再跨项目可见`() {
        val src = source("src/main/java/io/github/m0use001/planability/data/MemoryEntities.kt")
        assertTrue(
            "recallableInScope 不许再有 handoff 跨项目豁免（事故根因：别的项目的 handoff 每轮注入）",
            !src.contains("m.type == \"handoff\"")
        )
        assertTrue(
            "scope=0 必须是「全局+常驻」，不能再等于全库（MEMORY_SCOPE_ALL 才是全库）",
            src.contains("internal const val MEMORY_SCOPE_ALL: Long = -1L") &&
                src.contains("projectScope < 0L || m.projectId == 0L || m.projectId == projectScope || m.inIndex")
        )
    }

    @Test
    fun `工作层注入必须按会话作用域过滤`() {
        val dao = source("src/main/java/io/github/m0use001/planability/data/MemoryEntities.kt")
        assertTrue(
            "workingLayer/countWorking 的 SQL 必须带 :scope 过滤（本项目+全局+常驻）",
            dao.contains(":scope < 0 OR projectId = :scope OR projectId = 0 OR inIndex = 1")
        )
        assertTrue(
            "injectionBlock 必须把 projectScope 传给 workingLayer/countWorking",
            dao.contains("workingLayer(projectScope = projectScope)") &&
                dao.contains("countWorking(projectScope = projectScope)")
        )
    }

    @Test
    fun `skill_list 与文件库缺省必须按作用域收口`() {
        val exec = source("src/main/java/io/github/m0use001/planability/ai/ptl/ToolExecImpls.kt")
        assertTrue(
            "skill_list 必须按作用域过滤（原来全库列技能，别的项目 skill 也端出来）",
            exec.contains("it.type == \"skill\" && recallableInScope(it, scope)")
        )
        assertTrue(
            "file_library_list 缺省不许再列所有项目（只列本项目+全局；hub 才全览）",
            exec.contains("scope > 0L -> projects.firstOrNull { it.id == scope }")
        )
        assertTrue(
            "file_library_read 缺省候选必须只取本项目+全局",
            exec.contains("homeProjects.flatMap { io.github.m0use001.planability.data.ProjectFileRepo.tree(it.id) }")
        )
    }

    @Test
    fun `批量删除必须有与确认卡无关的硬上限`() {
        val exec = source("src/main/java/io/github/m0use001/planability/ai/ptl/ToolExecImpls.kt")
        assertTrue(
            "必须有 MEMORY_MUTATE_BURST_MAX 常量",
            exec.contains("internal const val MEMORY_MUTATE_BURST_MAX = 5")
        )
        assertTrue(
            "memory_forget 必须过 memoryBurstHasRoom",
            exec.contains("if (!memoryBurstHasRoom(sessionId, 1, now))")
        )
        assertTrue(
            "memory_invalidate 必须整批过 memoryBurstHasRoom",
            exec.contains("if (undoneRows.isNotEmpty() && !memoryBurstHasRoom(sessionId, undoneRows.size, now))")
        )
    }

    // ---------- 2026-11-27 二轮审计：作废旧设定不许经「一跳扩展」复活 ----------

    @Test
    fun `一跳扩展与 wikilink 必须按有效性和作用域过滤`() {
        val src = source("src/main/java/io/github/m0use001/planability/data/MemoryEntities.kt")
        assertTrue(
            "必须有 memoryUsable 纯判据（未删/未过期/未作废/未取代/在作用域内）",
            src.contains("internal fun memoryUsable(m: MemoryEntity, now: Long, projectScope: Long): Boolean")
        )
        assertTrue(
            "recall 必须走 memoryUsable",
            src.contains("filter { (m, _) -> memoryUsable(m, now, projectScope) }")
        )
        assertTrue(
            "expandOneHop 必须逐邻居过滤 memoryUsable（否则链接能绕开作废）",
            src.contains(".filter { n -> memoryUsable(n, now, projectScope) }")
        )
        assertTrue(
            "injectionBlock 必须把 scope 传给 expandOneHop",
            src.contains("expandOneHop(recalled, projectScope)")
        )
        assertTrue(
            "wikilink 解析必须按项目可见范围（全局 byName 会链到别的项目）",
            src.contains("dao.byNameVisible(mr.groupValues[1].trim(), m.projectId)")
        )
        assertTrue(
            "wikilink 不许再用全局 byName",
            !src.contains("val target = dao.byName(")
        )
    }

    @Test
    fun `镜像索引的作废标记必须在行首并写明勿用`() {
        val files = source("src/main/java/io/github/m0use001/planability/data/MemoryFiles.kt")
        assertTrue("必须用【已失效·勿用】/【已被取代·勿用】", files.contains("【已失效·勿用】") && files.contains("【已被取代·勿用】"))
        assertTrue(
            "标记必须在条目行首（原来挂行尾，模型当成正文自注）",
            files.contains("appendLine(\"- \$mark[")
        )
        assertTrue(
            "单文件正文也要有状态警示行",
            files.contains("本条已作废/被取代")
        )
    }
}
