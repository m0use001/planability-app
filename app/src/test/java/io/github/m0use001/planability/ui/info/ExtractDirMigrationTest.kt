package io.github.m0use001.planability.ui.info

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 2026-11-15 用户：抽屉里出现两个「信息提取」入口（📁 真项目 / 🔽 磁盘产物树）。
 * 修法之一是把磁盘产物树整体下移一层到 `信息提取/聊天原文/<群>/…`。
 *
 * 用户手机上已经有 10 个群目录与历史日期目录 —— 迁移必须**一个不丢、可重入、不误伤新结构**。
 * 这条钉住迁移函数的四条规则（见 [migrateExtractRawIntoSubdir] KDoc）。
 */
class ExtractDirMigrationTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun root() = tmp.newFolder("信息提取")

    @Test
    fun 群目录整体下移到聊天原文下_文件与日期层级不动() {
        val r = root()
        File(r, "班级群/2026-11-14/记录.md").apply { parentFile!!.mkdirs(); writeText("正文") }
        File(r, "家人群/2026-11-15/记录.md").apply { parentFile!!.mkdirs(); writeText("正文2") }

        val res = migrateExtractRawIntoSubdir(r)

        assertEquals(listOf("家人群", "班级群").sorted(), res.moved.sorted())
        val raw = File(r, "聊天原文")
        assertTrue("聊天原文/班级群/2026-11-14/记录.md 应该存在", File(raw, "班级群/2026-11-14/记录.md").isFile)
        assertTrue("聊天原文/家人群/2026-11-15/记录.md 应该存在", File(raw, "家人群/2026-11-15/记录.md").isFile)
        assertEquals("正文", File(raw, "班级群/2026-11-14/记录.md").readText())
        assertTrue("原位置不能再留群目录", !File(r, "班级群").exists())
    }

    @Test
    fun 幂等_重复调用不再搬动() {
        val r = root()
        File(r, "班级群/2026-11-14/记录.md").apply { parentFile!!.mkdirs(); writeText("x") }

        assertEquals(1, migrateExtractRawIntoSubdir(r).moved.size)
        assertEquals("第二次调用必须什么都不做", 0, migrateExtractRawIntoSubdir(r).moved.size)
        assertEquals("第三次同理", 0, migrateExtractRawIntoSubdir(r).moved.size)
        assertTrue(File(r, "聊天原文/班级群/2026-11-14/记录.md").isFile)
    }

    @Test
    fun 新结构目录不被当成群搬走() {
        val r = root()
        File(r, "聊天原文/班级群/2026-11-14/记录.md").apply { parentFile!!.mkdirs(); writeText("x") }
        File(r, "聊天提纯").mkdirs()

        val res = migrateExtractRawIntoSubdir(r)
        assertEquals("「聊天原文」「聊天提纯」是结构自身，不能搬", 0, res.moved.size)
        assertTrue("不该有跳过/失败项", res.skippedExisting.isEmpty() && res.failed.isEmpty())
        assertTrue(File(r, "聊天原文/班级群/2026-11-14/记录.md").isFile)
        assertTrue(File(r, "聊天提纯").isDirectory)
        assertTrue("绝不能出现 聊天原文/聊天原文 套娃", !File(r, "聊天原文/聊天原文").exists())
    }

    @Test
    fun 目标已存在同名群_跳过不覆盖也不合并() {
        val r = root()
        File(r, "班级群/2026-11-14/old.md").apply { parentFile!!.mkdirs(); writeText("旧") }
        File(r, "聊天原文/班级群/2026-11-15/new.md").apply { parentFile!!.mkdirs(); writeText("新") }

        val res = migrateExtractRawIntoSubdir(r)

        assertEquals("撞名必须跳过，不算搬成功", 0, res.moved.size)
        assertEquals("撞名要落在 skippedExisting 里", listOf("班级群"), res.skippedExisting)
        assertTrue("旧内容原地保留，不丢", File(r, "班级群/2026-11-14/old.md").isFile)
        assertTrue("新结构里的内容不被覆盖", File(r, "聊天原文/班级群/2026-11-15/new.md").isFile)
        assertEquals("新", File(r, "聊天原文/班级群/2026-11-15/new.md").readText())
    }

    @Test
    fun 根目录不存在时安全返回零() {
        val res = migrateExtractRawIntoSubdir(File(tmp.root, "不存在的目录"))
        assertEquals(0, res.moved.size)
        assertTrue(!res.cannotCreateRoot)
    }

    @Test
    fun 根下散落文件不动() {
        val r = root()
        File(r, "说明.txt").writeText("不该被搬")
        File(r, "班级群/2026-11-14/记录.md").apply { parentFile!!.mkdirs(); writeText("x") }

        assertEquals(1, migrateExtractRawIntoSubdir(r).moved.size)
        assertTrue("根下散落文件不属于任何群，原地保留", File(r, "说明.txt").isFile)
    }
}
