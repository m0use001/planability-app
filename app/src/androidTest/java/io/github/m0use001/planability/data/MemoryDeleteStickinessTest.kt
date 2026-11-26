package io.github.m0use001.planability.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * **记忆删除粘性门（instrumented，模拟器实测）**——2026-11-26 小说会话事故的根修回归。
 *
 * 事故链路：AI 连续 `memory_forget` 24 条 → 每条 `softDelete` 都会 `MemoryFiles.schedule(pid)`，
 * 800ms 后防抖导出先跑 `adoptStray`；原来跳过集用 `all()`（isDeleted=0），软删行的镜像
 * `.md` 被当孤儿重新收养 → `@Insert(REPLACE)` 在 (name, projectId) 唯一索引上把墓碑
 * 整个替换成一条新 active 行（id 还变了）。真机现场：11:03:0x 删 24 条，新行 createdAt
 * 全是 11:03:09，memory_history 里 23 条 DELETE 全成了孤儿记录。
 *
 * 本门走真实路径（真 Room + 真目录 + 真 adoptStray），两条断言：
 * 1. 软删行的镜像文件**不许**被收养（删除必须粘住）；
 * 2. 真正陌生的孤儿 `.md` **仍然**要被收养（别修过头把「AI 写文件进镜像目录」弄丢）。
 */
@RunWith(AndroidJUnit4::class)
class MemoryDeleteStickinessTest {

    private val probePid = 999_991L
    private val stamp = System.currentTimeMillis()
    private val deletedName = "stickiness-deleted-$stamp"
    private val strayName = "stickiness-stray-$stamp"
    private lateinit var dir: File

    @Before
    fun setUp() {
        Graph.init(InstrumentationRegistry.getInstrumentation().targetContext)
        dir = File(
            InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
            "memstick-$stamp"
        ).apply { mkdirs() }
    }

    @After
    fun tearDown() {
        runCatching { dir.deleteRecursively() }
        // 自建自清：直接 SQL 清掉探针行（含墓碑）
        runCatching {
            Graph.db.openHelper.writableDatabase.execSQL(
                "DELETE FROM memories WHERE name LIKE 'stickiness-%$stamp'"
            )
        }
    }

    @Test
    fun `软删条目不会被镜像文件复活 陌生孤儿仍会被收养`() = runBlocking {
        // ── 1. 造一条记忆 + 它的镜像文件，然后软删 ──
        val id = Graph.db.memoryDao().upsert(
            MemoryEntity(
                id = 0, name = deletedName, type = "project", description = "删除粘性探针",
                body = "这条被删后不允许被镜像复活", importance = 1, accessCount = 0,
                lastAccessAt = 0, expiration = 0, inIndex = false, isDeleted = false,
                originSession = 0, createdAt = stamp, updatedAt = stamp, projectId = probePid,
                priority = 50, scene = "", validAt = 0, invalidAt = 0, expiredAt = 0,
                version = 1, supersededBy = 0
            )
        )
        // 导出格式的镜像（frontmatter 里有 name，正文非空）
        File(dir, "$deletedName.md").writeText(
            "---\nname: $deletedName\ndescription: 删除粘性探针\ntype: project\n" +
                "importance: 1\nstatus: active\n---\n\n这条被删后不允许被镜像复活\n",
            Charsets.UTF_8
        )
        Graph.db.memoryDao().softDelete(id, System.currentTimeMillis())

        // ── 2. 再造一个真正陌生的孤儿文件（应被收养）──
        File(dir, "$strayName.md").writeText(
            "用户手写的记忆文件，应该被收养进记忆库。", Charsets.UTF_8
        )

        val adopted = MemoryFiles.adoptStray(probePid, dir)

        // 断言 1：软删行还在墓碑态，没有被替换成新 active 行
        val row = Graph.db.memoryDao().byId(id)
        assertNotNull("软删行的墓碑被 REPLACE 物理删掉了（删除不粘）", row)
        assertTrue("软删行被镜像复活成 active 了（删除不粘）", row!!.isDeleted)
        assertEquals(
            "同一个名字出现了 active 副本（镜像收养绕过墓碑）",
            0,
            Graph.db.memoryDao().all().count { it.name == deletedName }
        )

        // 断言 2：陌生孤儿照常收养
        assertEquals("陌生孤儿文件应被收养 1 条", 1, adopted)
        val stray = Graph.db.memoryDao().all().firstOrNull { it.name == strayName }
        assertNotNull("陌生孤儿没有被收养（修过头了）", stray)
        assertTrue("收养后的正文不对", stray!!.body.contains("用户手写的记忆文件"))
    }
}
