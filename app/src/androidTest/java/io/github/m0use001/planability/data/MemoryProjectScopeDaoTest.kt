package io.github.m0use001.planability.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * v57 记忆项目隔离的真 schema 验收（2026-11-21 串味审计）。
 *
 * 用内存库直接建 v57 schema，钉死四件事：
 * 1. 跨项目同名记忆能共存（旧全局唯一索引下这一步直接抛约束冲突 = 红的故障证明）；
 * 2. 跨项目同名**场景**能共存（touchScene 的 per-project 查找靠它）；
 * 3. `byNameVisible` 可见矩阵：本项目 ✓ / 全局 ✓ / 别项目 inIndex ✓ / 别项目普通 ✗；
 * 4. `activeOwnedBy` 只数自己名下的场景（别的项目热度不占名额）。
 */
@RunWith(AndroidJUnit4::class)
class MemoryProjectScopeDaoTest {

    private var db: AppDatabase? = null

    @Before
    fun setUp() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        db?.close()
        db = null
    }

    @Test
    fun `跨项目同名记忆共存且按名各找各的`() = runBlocking {
        val dao = db!!.memoryDao()
        val a = dao.upsert(MemoryEntity(name = "我在和用户做X", projectId = 9, body = "小说版"))
        val b = dao.upsert(MemoryEntity(name = "我在和用户做X", projectId = 11, body = "线代版"))
        assertNotEquals("跨项目同名必须是两行（旧全局唯一索引在此直接抛异常）", a, b)
        assertEquals("小说(9)按名取到小说版", "小说版", dao.byNameInProject("我在和用户做X", 9)!!.body)
        assertEquals("线代(11)按名取到线代版", "线代版", dao.byNameInProject("我在和用户做X", 11)!!.body)
    }

    @Test
    fun `同项目重放同名仍是原地覆盖`() = runBlocking {
        val dao = db!!.memoryDao()
        dao.upsert(MemoryEntity(name = "dup", projectId = 9, body = "v1"))
        // 裸 DAO 的 REPLACE：唯一冲突时删旧行插新行（id 会变）——「复用 id」的语义在
        // MemoryRepo.upsert（先 byNameInProject 再带旧 id 写），由源码守卫钉住。
        // 这里钉的是 schema 层不变量：同项目重放后**仍然只有一行**、内容是新的。
        dao.upsert(MemoryEntity(name = "dup", projectId = 9, body = "v2"))
        val rows = dao.byNameInProject("dup", 9)
        assertEquals("v2", rows!!.body)
        assertEquals(1, db!!.memoryDao().all().count { it.name == "dup" && it.projectId == 9L })
    }

    @Test
    fun `byNameVisible 可见矩阵`() = runBlocking {
        val dao = db!!.memoryDao()
        dao.upsert(MemoryEntity(name = "mine", projectId = 9))
        dao.upsert(MemoryEntity(name = "global", projectId = 0))
        dao.upsert(MemoryEntity(name = "pinned", projectId = 11, inIndex = true))
        dao.upsert(MemoryEntity(name = "foreign", projectId = 11))
        assertEquals(9L, dao.byNameVisible("mine", 9)!!.projectId)
        assertEquals("全局(0)从任何项目都可见", 0L, dao.byNameVisible("global", 9)!!.projectId)
        assertEquals("别的项目的常驻索引可见（工作层语义）", 11L, dao.byNameVisible("pinned", 9)!!.projectId)
        assertNull("别的项目的普通条目不可见（串味根修）", dao.byNameVisible("foreign", 9))
        // 本项目命中优先于全局同名兜底
        dao.upsert(MemoryEntity(name = "both", projectId = 0, body = "全局版"))
        dao.upsert(MemoryEntity(name = "both", projectId = 9, body = "本项目版"))
        assertEquals("本项目版", dao.byNameVisible("both", 9)!!.body)
    }

    @Test
    fun `跨项目同名场景共存且按项目各自封顶`() = runBlocking {
        val dao = db!!.memorySceneDao()
        // 注意：debug 也开 R8，App 代码构造 MemoryScene 全走全参构造 → 合成默认参构造
        // 会被剥掉（NoSuchMethodError，2026-11-21 实测）。测试里必须全参构造。
        fun scene(name: String, pid: Long, summary: String = "", heat: Int = 1) = MemoryScene(
            id = 0, name = name, summary = summary, heat = heat, projectId = pid,
            validAt = 0, invalidAt = 0, createdAt = 0, updatedAt = 0
        )
        dao.upsert(scene("我在和用户做设定卡迭代", 9, "小说", 3))
        dao.upsert(scene("我在和用户做设定卡迭代", 11, "线代", 3))
        assertEquals("小说", dao.byNameInProject("我在和用户做设定卡迭代", 9)!!.summary)
        assertEquals("线代", dao.byNameInProject("我在和用户做设定卡迭代", 11)!!.summary)
        // 造 16 个项目 9 的场景 + 16 个项目 11 的场景：activeOwnedBy 只数自己名下
        repeat(16) { i ->
            dao.upsert(scene("s9-$i", 9, heat = i + 1))
            dao.upsert(scene("s11-$i", 11, heat = i + 1))
        }
        val cap = MemoryLayers.MAX_SCENES
        assertTrue("项目 9 名下应超上限", dao.activeOwnedBy(9, cap + 5, 0L).size > cap)
        assertTrue("项目 11 名下应超上限", dao.activeOwnedBy(11, cap + 5, 0L).size > cap)
        // 名单里不许混进对方项目的场景
        assertTrue(
            "activeOwnedBy(9) 不得混入项目 11 的场景",
            dao.activeOwnedBy(9, 100, 0L).none { it.projectId == 11L }
        )
    }
}
