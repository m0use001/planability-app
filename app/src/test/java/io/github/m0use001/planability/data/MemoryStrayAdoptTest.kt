package io.github.m0use001.planability.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 记忆镜像「孤儿文件收养」的解析锁（2026-11-08 用户实报）。
 *
 * 现场：让 AI 把新记忆写到 `<项目数据>/项目记忆/`，文件管理器看得到，App 记忆库一条没有，
 * 而且下次导出会把这个"不认识的文件"清掉。修法是把该目录做成双向：导出前收养孤儿 `.md`。
 *
 * 这里只锁**解析**（纯函数）：frontmatter 认不认、空壳要不要、标题从哪来。
 * 收养的写库路径由 MemoryFiles.adoptStray 负责，跑在真实导入里。
 */
class MemoryStrayAdoptTest {

    @Test
    fun `带 frontmatter 的文件按字段还原`() {
        val text = """
            ---
            name: 小说主角设定
            description: 主角是 1970 年博通 Stratos Zero 改装的载具
            type: project
            importance: 3
            ---

            主角外形不可改，内部可拆。
        """.trimIndent()
        val m = MemoryFiles.parseStray("主角.md", text)!!
        assertEquals("小说主角设定", m.name)
        assertEquals("主角是 1970 年博通 Stratos Zero 改装的载具", m.description)
        assertEquals(3, m.importance)
        assertTrue(m.body.contains("内部可拆"))
    }

    @Test
    fun `没有 frontmatter 的纯 markdown 也能收养 标题取文件名`() {
        val m = MemoryFiles.parseStray("写作偏好.md", "第一行就是正文，我希望短句多、形容词少。")!!
        assertEquals("写作偏好", m.name)
        assertEquals("第一行就是正文，我希望短句多、形容词少。", m.description)
        assertTrue(m.body.contains("形容词少"))
    }

    @Test
    fun `空壳文件不收养`() {
        assertNull(MemoryFiles.parseStray("空.md", ""))
        assertNull(MemoryFiles.parseStray("只有头.md", "---\nname: x\n---\n"))
    }

    @Test
    fun `重要性越界会被钳到 1 到 3`() {
        val m = MemoryFiles.parseStray("a.md", "---\nimportance: 9\n---\n正文")!!
        assertEquals(3, m.importance)
        val n = MemoryFiles.parseStray("b.md", "---\nimportance: 0\n---\n正文")!!
        assertEquals(1, n.importance)
    }

    /**
     * **2026-11-26 小说会话事故回归门：删除必须粘住。**
     *
     * 现场：AI 连续 memory_forget 24 条，800ms 后防抖导出 → adoptStray 把软删行的
     * 镜像 .md 当孤儿重新收养 → `@Insert(REPLACE)` 在 (name, projectId) 唯一索引上
     * 把墓碑替换成新 active 行（id 变了），删除全部复活（tools_audit 26 次调用、
     * memory_history 23 条 DELETE 孤儿记录，库里却查无此 id）。
     *
     * 判据：跳过集必须用 allIncludingDeleted()——墓碑也算「已存在」，不许再收养。
     */
    @Test
    fun `软删行的镜像文件不许被重新收养——删除不粘的根因`() {
        val src = java.io.File("src/main/java/io/github/m0use001/planability/data/MemoryFiles.kt")
        assertTrue("MemoryFiles.kt 不在了（cwd 应为 app 模块目录）", src.isFile)
        val text = src.readText(Charsets.UTF_8).replace("\r\n", "\n")
        assertTrue(
            "adoptStray 的跳过集必须含墓碑：val existing = Graph.db.memoryDao().allIncludingDeleted()",
            text.contains("val existing = Graph.db.memoryDao().allIncludingDeleted().filter { it.projectId == projectId }")
        )
        assertTrue(
            "不许再用 all() 建收养跳过集（软删行会被当孤儿复活）",
            !text.contains("val existing = Graph.db.memoryDao().all().filter { it.projectId == projectId }")
        )
    }
}
