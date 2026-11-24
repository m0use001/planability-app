package io.github.m0use001.planability

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 2026-11-01 用户：「新建了项目但对应文件夹没有立刻新建项目数据等文件夹」的防回归钉。
 *
 * 根因：「项目数据」四子目录（当前会话/已归档会话/项目记忆/项目文件）原来只在
 * 启动时 `ProjectDirs.ensureAll()` 建一次，`ProjectRepo.create` 只写库行+刷新名字缓存——
 * 运行中新建的项目要等下一次冷启动才有目录结构，文件库与电脑端 Syncthing 都看不到。
 * 修复 = create 在 refreshProjectNames 之后立刻 `ensureStructure(id)`（名字缓存刚刷，
 * 绑定项目的落点此刻是准的）。这条钉住两件事的**先后顺序**与存在性。
 */
class ProjectProvisionTest {

    private fun src(rel: String): String {
        val root = generateSequence(File(System.getProperty("user.dir") ?: ".").absoluteFile) { it.parentFile }
            .firstOrNull { File(it, "app/src/main/java/io/github/m0use001/planability").isDirectory }
        assertTrue("找不到仓库根", root != null)
        val f = File(root, rel)
        assertTrue("源码不在了：$rel", f.isFile)
        return f.readText(Charsets.UTF_8)
    }

    @Test
    fun `新建项目要立刻备齐项目数据目录结构`() {
        val s = src("app/src/main/java/io/github/m0use001/planability/data/ProjectEntities.kt")
        val create = s.substringAfter("suspend fun create(name: String").substringBefore("suspend fun rename(")
        assertTrue("ProjectRepo.create 找不到了（搬家了？这条测试要跟着改）", create.isNotEmpty())
        assertTrue(
            "create 里没有 ensureStructure —— 运行中新建的项目还是要等下次冷启动才有「项目数据」",
            create.contains("ensureStructure(id)")
        )
        // 名字缓存必须先刷：ProjectDirs.data/projectDir 依赖它解析绑定落点，
        // 顺序反了绑定项目会把「项目数据」建到 synced/projects/<id> 旧位置去
        val refreshAt = create.indexOf("refreshProjectNames()")
        val ensureAt = create.indexOf("ensureStructure(id)")
        assertTrue(
            "create 里 ensureStructure 跑在 refreshProjectNames 之前 —— 绑定项目的落点还没刷出来",
            refreshAt in 0 until ensureAt
        )
    }

    @Test
    fun `启动时的全量对账仍然在`() {
        // create 只覆盖"新建"这一条路；备份恢复/异常中断留下的缺口仍靠启动 ensureAll 对账
        val s = src("app/src/main/java/io/github/m0use001/planability/data/ProjectDirs.kt")
        assertTrue("ProjectDirs.ensureAll 不在了——启动时的目录对账丢了", s.contains("suspend fun ensureAll()"))
        val main = src("app/src/main/java/io/github/m0use001/planability/MainActivity.kt")
        assertTrue("MainActivity 不再调用 ensureAll —— 启动对账断了", main.contains("ProjectDirs.ensureAll()"))
    }
}
