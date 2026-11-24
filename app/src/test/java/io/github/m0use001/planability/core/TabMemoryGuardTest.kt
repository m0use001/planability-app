package io.github.m0use001.planability.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * v68「纸条导航」的记忆地基守卫（2026-11-21，用户三条拍板之三：任意深度可切页 + 页面记忆）。
 *
 * 页面记忆的三个前提，缺一记忆就会静默失效：
 *  1. 子页是**页内层**（不再开独立 Dialog 窗口——窗口会浮在所有 tab 上，逼着每页写
 *     「切走就收层」的 watcher 把记忆抹掉）；
 *  2. 锁**按 tab 记账**（全局计数会让「我的」页开着子页藏掉「专注」页的底栏/Rail）；
 *  3. 页内返回手势**按 LocalTabActive 门控**（七页常驻组合，别的 tab 上的返回层会
 *     替当前页吃掉系统返回）。
 *
 * 路径安全：与 WideBoardLayoutGuardTest 同款——仓库根靠固定目录向上找。
 */
class TabMemoryGuardTest {

    private val appDir = "app/src/main"

    private val repoRoot: File by lazy {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null && !File(dir, appDir).isDirectory) {
            dir = dir.parentFile
        }
        requireNotNull(dir) { "找不到仓库根" }.canonicalFile
    }

    private fun read(relative: String): String {
        val root = repoRoot.canonicalFile
        val f = File(root, relative).canonicalFile
        assertTrue("路径越界：$relative", f.path.startsWith(root.path + File.separator))
        assertTrue("文件不存在：$relative", f.isFile)
        return f.readText()
    }

    // CRLF 归一化（既有教训：源码结构断言先归一化，否则 worktree/异机检出成 CRLF 就假红）
    private fun mainSrc(relative: String) = read("$appDir/$relative").replace("\r\n", "\n")

    @Test
    fun `子页外壳是页内层 不再开独立 Dialog 窗口`() {
        val overlay = mainSrc("java/io/github/m0use001/planability/app/PredictiveBackOverlay.kt")
        assertFalse(
            "PredictiveBackOverlay 又开了独立窗口（Dialog）——子页会浮在别的 tab 上，" +
                "页面记忆与「切走收层」watcher 都会跟着回来",
            overlay.contains("Dialog(")
        )
        assertTrue(
            "PredictiveBackOverlay 缺页内整层铺底（不透明 background）——预测返回收缩期会透出宿主",
            overlay.contains(".background(bg)")
        )
        assertTrue(
            "PredictiveBackOverlay 缺 deferBack 参数（钻入瓦片要把返回手势让给纸条）",
            overlay.contains("deferBack: Boolean = false")
        )
        assertTrue(
            "PredictiveBackOverlay 的预测返回没有按 tab 门控（!deferBack && tabActive && !closing）" +
                "——常驻组合下别的 tab 上的子页会替当前页吃系统返回",
            overlay.contains("enabled = !deferBack && tabActive && !closing")
        )
    }

    @Test
    fun `壳层锁按 tab 记账并有 LocalTabId_LocalTabActive`() {
        val chrome = mainSrc("java/io/github/m0use001/planability/core/ShellChrome.kt")
        assertTrue("ShellChrome 缺 tabLocks（锁还是全局计数，别的 tab 会被藏掉底栏）", chrome.contains("tabLocks"))
        assertTrue("ShellChrome 缺 effectiveLock（NavShell 消费者无从按 tab 汇总）", chrome.contains("fun effectiveLock(currentTab: String)"))
        assertTrue("缺 LocalTabId（页内层无从把锁记到自己那页名下）", chrome.contains("val LocalTabId"))
        assertTrue("缺 LocalTabActive（页内返回手势无从按 tab 门控）", chrome.contains("val LocalTabActive"))
        assertTrue(
            "LockShellChrome 没按 LocalTabId 记账（还是全局 lock()）",
            chrome.contains("ShellChrome.lock(tab)")
        )
    }

    @Test
    fun `壳层 pager 宽屏纵向纸条 手机左右横切 且每页提供 tab 身份`() {
        val shell = mainSrc("java/io/github/m0use001/planability/app/NavShell.kt")
        assertTrue("壳层没有 VerticalPager（宽屏纸条上下抽没落地）", shell.contains("VerticalPager("))
        // v70j（2026-11-21 用户「手机模式下页面切换应该保留左右切换设定」）：纵向抽只属于
        // 宽屏「框不动」物理；手机没有相框，保留 10-29 拍板的左右横切——两个 pager 都要在，
        // 按形态分叉（原来的「禁止 HorizontalPager」是 v68 一刀切留下的旧口径）。
        assertTrue(
            "壳层没有 HorizontalPager（手机左右切页没保留，v70j 拍板）",
            shell.contains("HorizontalPager(")
        )
        assertTrue(
            "两个 pager 没有按宽窄分叉（缺 if (wide) 选轴向）",
            shell.contains("if (wide) {") && shell.contains("pageContent = pageContent")
        )
        assertTrue(
            "壳层没给每页提供 LocalTabId（页内层锁无从按 tab 记账）",
            shell.contains("LocalTabId provides pageTabId")
        )
        assertTrue(
            "壳层没给每页提供 LocalTabActive（页内返回手势无从门控）",
            shell.contains("LocalTabActive provides (ShellNav.currentTab == pageTabId)")
        )
        assertTrue(
            "底栏/Rail 显隐没走 effectiveLock（还是全局 lockCount）",
            shell.contains("effectiveLock(ShellNav.currentTab)")
        )
    }

    @Test
    fun `B线四页的切走收层 watcher 退役`() {
        listOf(
            "java/io/github/m0use001/planability/ui/home/HomeScreen.kt",
            "java/io/github/m0use001/planability/ui/plan/PlanScreen.kt",
            "java/io/github/m0use001/planability/ui/profile/ProfileScreens.kt",
            "java/io/github/m0use001/planability/ui/notes/NotesScreen.kt"
        ).forEach { rel ->
            val src = mainSrc(rel)
            assertFalse(
                "$rel 还有「切走收层」watcher（LaunchedEffect(ShellNav.currentTab)）——" +
                    "页面记忆会被它抹掉；收层的三个根因已在壳层根治",
                src.contains("LaunchedEffect(io.github.m0use001.planability.app.ShellNav.currentTab)")
            )
        }
    }

    @Test
    fun `B线页内返回手势按 LocalTabActive 门控`() {
        val home = mainSrc("java/io/github/m0use001/planability/ui/home/HomeScreen.kt")
        assertTrue(
            "首页的日历/倒数日返回手势没按 tab 门控",
            home.contains("showCountdowns && homeTabActive") &&
                home.contains("showCalendar && homeTabActive")
        )
        val plan = mainSrc("java/io/github/m0use001/planability/ui/plan/PlanScreen.kt")
        // v70d（2026-11-21）：计划页两板改 BoardDrill——返回收纸条由 BoardDrill 自带的
        // PredictiveBackHandler 接管（其内部按 LocalTabActive 门控），页级 BackHandler 已撤。
        assertTrue(
            "计划页没有接 BoardDrill（返回收纸条应由 BoardDrill 的预测返回按 LocalTabActive 门控）",
            plan.contains("BoardDrill(") && plan.contains("onBack = {")
        )
        val notes = mainSrc("java/io/github/m0use001/planability/ui/notes/NotesScreen.kt")
        assertTrue(
            "笔记页的选择模式返回手势没按 tab 门控",
            notes.contains("selectionMode && io.github.m0use001.planability.core.LocalTabActive.current")
        )
        val files = mainSrc("java/io/github/m0use001/planability/ui/files/FilesScreen.kt")
        assertTrue(
            "文件库的返回手势没按 tab 门控（filesTabActive）",
            files.contains("filesTabActive")
        )
    }

    @Test
    fun `我的页二级页收敛为单状态并接入钻入板条`() {
        val profile = mainSrc("java/io/github/m0use001/planability/ui/profile/ProfileScreens.kt")
        assertTrue("我的页没有单一 subPage 状态（24 个 showXxx 布尔又回来了？）", profile.contains("var subPage by remember"))
        assertTrue("我的页缺 ProfileSub 路由表", profile.contains("private enum class ProfileSub(val embeddable: Boolean)"))
        assertTrue(
            "我的页宽屏没把子页交给钻入栈（NavHost + ProfileSubScreen deferBack = true 瓦片）",
            profile.contains("NavHost(") &&
                profile.contains("ProfileSubScreen(") &&
                profile.contains("deferBack = true")
        )
    }
}
