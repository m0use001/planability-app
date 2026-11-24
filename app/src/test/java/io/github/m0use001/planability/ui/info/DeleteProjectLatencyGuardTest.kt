package io.github.m0use001.planability.ui.info

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 2026-11-16 用户两条报障的源码守卫：
 *
 * ① 「彻底删除项目有延迟」——根因不是 SQL 慢，是 `ProjectRepo.deleteForce` 里
 *    `ProjectDirs.data(id).deleteRecursively()` 是**裸调**，而调用方是
 *    `rememberCoroutineScope()`（Main）。项目越大（会话目录/项目文件越多）删得越久，
 *    主线程整帧卡住；同时 UI 层点完立刻关弹窗、无任何反馈，用户以为没生效会反复点。
 *    修：递归删目录下沉到 IO；两处入口都在删除期间给出「删除中…」并禁用按钮。
 *
 * ② 「顶部标题栏显示智能体不要加上 emoji，不然顶部放不下」——顶栏副标题是固定宽的
 *    胶囊行，「🤖 通用助手」比「通用助手」多占一个 emoji 宽，几枚胶囊挤不下。
 *    修：顶栏用纯名字 `assistantPlainName`，带 emoji 的 `assistantLabel` 留给
 *    发送设置/运行面板等宽裕处。
 *
 * ③ 归档项目删除留孤儿目录（同日验收时抓到的真实缺陷，非用户报障）——
 *    `ProjectDirs.data(id)` 经名字缓存拼路径，而 `refreshProjectNames()` 的 DAO 是
 *    `WHERE archived = 0`，归档项目永远进不了缓存 → 名字为空 → 退化成纯数字目录名
 *    → 删掉一个不存在的路径，真正的项目目录原封不动留在磁盘（实测 612M 孤儿）。
 *    修：按 `projectIdOfDirName` 扫 root 反查，不依赖任何缓存状态。
 *
 * 这三条都是 Compose 组合期/线程行为，JVM 单测跑不到，按仓库惯例用源码结构钉。
 */
class DeleteProjectLatencyGuardTest {

    private fun read(rel: String) = java.io.File("src/main/$rel").readText()

    /**
     * 取 deleteForce 函数体，并**剥掉行注释**——结构守卫只该对真实代码生效，
     * 注释里引用旧写法（如"不能再用 ProjectDirs.data(id)"）不该触发断言。
     */
    private fun deleteForceBody(): String =
        projectEntities.substringAfter("suspend fun deleteForce(")
            .substringBefore("\n    }")
            .lines()
            .joinToString("\n") { it.substringBefore("//") }

    private val projectEntities by lazy { read("java/io/github/m0use001/planability/data/ProjectEntities.kt") }
    private val drawer by lazy { read("java/io/github/m0use001/planability/ui/info/WorkbenchDrawer.kt") }
    private val dataHub by lazy { read("java/io/github/m0use001/planability/ui/info/DataHub.kt") }
    private val chatScreen by lazy { read("java/io/github/m0use001/planability/ui/info/ChatScreen.kt") }

    // ── ① 删除延迟 ──

    @Test
    fun 递归删项目目录必须下沉到IO() {
        val body = deleteForceBody()
        assertTrue(
            "deleteForce 里的 deleteRecursively 没包在 IO 上下文里（主线程阻塞=用户看到的延迟）",
            body.contains("kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO)") &&
                body.contains("deleteRecursively()")
        )
        // deleteRecursively 必须落在**同一个** withContext(IO) 块内（不能只在别处出现 withContext）
        assertTrue(
            "deleteRecursively 不在 withContext(IO) 块内",
            Regex(
                """withContext\(kotlinx\.coroutines\.Dispatchers\.IO\)\s*\{[\s\S]*deleteRecursively\(\)"""
            ).containsMatchIn(body)
        )
    }

    @Test
    fun 删除项目目录按id反扫root而不是名字缓存() {
        // 2026-11-16 验收时抓到的真实缺陷：归档项目删完，612M 目录原封不动留在磁盘。
        // 根因 = ProjectDirs.data(id) 经名字缓存拼路径，而 refreshProjectNames() 的 DAO
        // 是 `WHERE archived = 0` → 归档项目永远进不了缓存 → 名字为空 → 退化成纯数字
        // 目录名 `7` → 删掉一个不存在的路径。
        val body = deleteForceBody()
        assertTrue(
            "deleteForce 还在用名字缓存拼目录路径（ProjectDirs.data/root）——归档项目缓存为空会删错路径留孤儿",
            !body.contains("ProjectDirs.data(") && !body.contains("ProjectDirs.root(")
        )
        assertTrue(
            "deleteForce 没有按 projectIdOfDirName 扫 root 反查（改名/legacy 纯数字目录全靠它命中）",
            body.contains("ProjectFiles.projectIdOfDirName") &&
                body.contains("ProjectFiles.root().listFiles()")
        )
        // 绑定项目：只删自己的「项目数据」容器，用户真实文件夹本体绝不碰
        assertTrue(
            "绑定项目没有单独走 <真实文件夹>/项目数据 容器删除",
            body.contains("ProjectDirs.DATA")
        )
    }

    @Test
    fun 抽屉强制删除要等完成再关弹窗并给反馈() {
        assertTrue("抽屉缺 deleting 进行中状态", drawer.contains("var deleting by remember"))
        // 点确认 → 置位 deleting 再 launch；删完才清 deleteTarget（不能在 launch 之后立刻清）
        assertTrue(
            "确认按钮没先置 deleting = true 再启动协程",
            Regex("""deleting = true\s*scope\.launch\s*\{""").containsMatchIn(drawer)
        )
        assertTrue(
            "删除期间必须显示进度指示",
            drawer.contains("CircularProgressIndicator(") && drawer.contains("\"删除中…\"")
        )
        assertTrue(
            "deleteTarget 必须在协程内删除完成后才清空（否则弹窗先消失、行还在）",
            Regex("""deleting = false\s*deleteTarget = null""").containsMatchIn(drawer)
        )
        // 删除中要禁用旁路（取消/归档），否则数据清到一半还能归档
        assertTrue(
            "删除中没禁用取消/归档（旁路会与正在跑的数据清理打架）",
            drawer.contains("enabled = !deleting")
        )
    }

    @Test
    fun 归档页删除也要给反馈() {
        assertTrue("归档页缺 deletingName 状态", dataHub.contains("var deletingName by remember"))
        assertTrue("归档页删除中没显示提示", dataHub.contains("删除中…"))
        assertTrue("归档页删除中没禁用按钮", dataHub.contains("enabled = !isDeleting"))
    }

    // ── ② 顶栏去掉 emoji ──

    @Test
    fun 顶栏智能体胶囊不许带emoji() {
        // 顶栏胶囊必须用纯名字
        assertTrue(
            "顶栏胶囊还在用带 emoji 的 assistantLabel",
            chatScreen.contains("assistantPlainName.ifBlank { \"智能体\" }")
        )
        assertFalse(
            "顶栏又改回 assistantLabel 了",
            chatScreen.contains("assistantLabel.ifBlank { \"智能体\" }")
        )
        // 纯名字必须由三处赋值点都填上（否则切换智能体后顶栏不更新）：
        // 会话加载 / 新建传入 assistantId / 选择器切换
        assertEquals3(
            "assistantPlainName 赋值点不足（会话加载/新建/选择器切换三处都要）",
            3,
            Regex("assistantPlainName = ").findAll(chatScreen).count()
        )
    }

    /** 三处赋值 = 会话加载 + 新建 + 选择器切换；声明 1 处不算 */
    private fun assertEquals3(msg: String, expected: Int, actual: Int) {
        org.junit.Assert.assertEquals(msg, expected, actual)
    }
}
