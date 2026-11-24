package io.github.m0use001.planability.ui.info

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 平板会话页的**接线**守卫（2026-11-19）。
 *
 * 为什么需要它：这一版把平板会话页从「整窗 overlay（盖住 NavRail）+ 左栏会话列表
 * + 右栏对话」改成「页内常驻工作台树侧栏 + 页内对话」，又把会话正文收成**单一内容列**。
 * 每一条都是**结构性**约定，编译不会拦——退回去照样编译、照样跑，只是用户又会看见
 * 「同一批会话画两遍」「整窗被一块弹窗盖住」「正文/工具行左右缘对不齐」。
 * 这些都是 2026-11-19 截图里点名过的问题，所以钉在源码上，谁改回去谁红。
 *
 * 路径安全沿用 PlanWidePaneGuardTest / TabletAdaptationGuardTest：仓库根靠固定目录名
 * 向上找，源码文件用白名单定位，不接受任何路径参数。
 */
class ChatWideGuardTest {

    private val appDir = "app/src/main"
    private val infoPath = "$appDir/java/io/github/m0use001/planability/ui/info/InfoCenterScreen.kt"
    private val chatPath = "$appDir/java/io/github/m0use001/planability/ui/info/ChatScreen.kt"
    private val layoutPath = "$appDir/java/io/github/m0use001/planability/ui/info/ChatWideLayout.kt"

    private val allowed = setOf(infoPath, chatPath, layoutPath)

    private val repoRoot: File by lazy {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null && !File(dir, appDir).isDirectory) {
            dir = dir.parentFile
        }
        requireNotNull(dir) { "找不到仓库根" }.canonicalFile
    }

    private fun readRepoFile(relative: String): String {
        require(relative in allowed) { "不在白名单里的文件：$relative" }
        val root = repoRoot.canonicalFile
        val f = File(root, relative).canonicalFile
        assertTrue("路径越界：$relative", f.path.startsWith(root.path + File.separator))
        assertTrue("文件不在了：$relative", f.isFile)
        return f.readText(Charsets.UTF_8)
    }

    /**
     * 去掉注释：解释性注释里会引用旧实现（比如「删掉 fullscreen overlay」），
     * 不剥掉会制造假红/假绿。行注释与块注释都要剥——KDoc 里同样会写旧写法。
     */
    private fun codeOnly(src: String): String =
        src.replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
            .lineSequence().joinToString("\n") { line ->
                val i = line.indexOf("//")
                if (i >= 0) line.substring(0, i) else line
            }

    @Test
    fun `宽屏形态只由 ChatWideLayout 决定 不再散落 if(wide)`() {
        val info = codeOnly(readRepoFile(infoPath))
        assertTrue(
            "信息页没有走纯函数决策机 ChatWideLayout.paneOf——宽度判断散在页面里，改断点必漏",
            info.contains("ChatWideLayout.paneOf(")
        )
        assertFalse(
            "旧的宽屏 overlay 双栏分支还在（if (wide && (showSessions || showChat))）——" +
                "它整块盖住 NavRail，正是用户报的割裂",
            info.contains("if (wide && (showSessions || showChat))")
        )
    }

    @Test
    fun `宽屏不再有盖住整窗的 fullscreen overlay`() {
        val info = codeOnly(readRepoFile(infoPath))
        assertFalse(
            "信息页还有 fullscreen = true 的 overlay——它是一块独立 Dialog 窗口，" +
                "会把 NavRail 与 App 外壳整块盖掉",
            info.contains("fullscreen = true")
        )
        assertFalse(
            "旧的 340dp 宽会话列表左栏还在（那是与抽屉重复的第二份会话列表）",
            info.contains("width(340.dp)")
        )
    }

    @Test
    fun `工作台树只有一份接线 抽屉与常驻侧栏共用`() {
        val info = codeOnly(readRepoFile(infoPath))
        assertEquals(
            "WorkbenchDrawerContent 的接线必须只有一处（接线抄两遍 = 迟早漏一处）",
            1,
            Regex("WorkbenchDrawerContent\\(").findAll(info).count()
        )
        assertTrue(
            "常驻侧栏没有复用抽屉内容（drawerBody）——那就是又画了一份会话树 = 用户报的重复",
            info.contains("drawerBody(")
        )
        assertTrue(
            "常驻侧栏没有把侧栏宽度接到 ChatWideLayout.SidebarWidth——宽度会散在页面里",
            info.contains("ChatWideLayout.SidebarWidth")
        )
        // 2026-11-20 v65 两板推广：侧栏从「HIG 通栏贴边」改成与计划页/首页同一套
        // 定宽圆角板（用户拍板「所有界面都要改成两块圆角板」，旧口径作废）。
        assertTrue(
            "宽屏信息页没有走共享两板骨架 SplitBoards——侧栏还是通栏贴边的旧形态",
            info.contains("SplitBoards(")
        )
    }

    @Test
    fun `宽屏禁用边缘右滑抽屉 且不画汉堡`() {
        val info = codeOnly(readRepoFile(infoPath))
        val gestureLine = info.lineSequence().firstOrNull { it.contains("drawerGestureEnabled =") }
        assertTrue("找不到 drawerGestureEnabled 的定义", gestureLine != null)
        assertTrue(
            "宽屏下边缘右滑还开着——右滑弹出的抽屉和常驻左栏画的是同一批会话 = 重复。" +
                "（条件里必须有 !wide）",
            gestureLine!!.contains("!wide")
        )
        assertTrue(
            "宽屏还画着汉堡钮——左栏已经常驻那棵树了，汉堡是给同一个东西的第二个入口",
            info.contains("if (!wideInfo) {")
        )
    }

    @Test
    fun `会话正文只有一处限宽来源 工具行与正文同列`() {
        val chat = codeOnly(readRepoFile(chatPath))
        assertFalse(
            "MessageBubble 里还留着裸的 760dp 自限宽——会话页会二次限宽，" +
                "正文左缘与同列的工具行错开（用户「会话内容不整齐」）",
            chat.contains("widthIn(max = 760.dp)")
        )
        assertTrue(
            "会话页调用 MessageBubble 时没有关掉自限宽（contentMaxWidth = null）",
            chat.contains("contentMaxWidth = null")
        )
        assertTrue(
            "消息列表没有按 ChatWideLayout.ContentMaxWidth 限宽——限宽必须是全 app 会话区唯一出处",
            chat.contains("adaptiveColumn(ChatWideLayout.ContentMaxWidth)")
        )
    }

    @Test
    fun `输入框内容撑满面板宽_不再套 880dp 限宽`() {
        // 2026-11-22 拍板反转（用户截图「文字与按钮全挤在中间——太窄挤在一起」）：
        // 输入区不是阅读列，原来与消息列共用的 880dp 限宽让模型胶囊/发送键被压在
        // 中间一条窄列里、左右留大片灰底没用上。守卫从「必须同宽」改为「必须不限宽」：
        // InputBar 的内容列必须 fillMaxWidth，且不许再出现 adaptiveColumn(880) 套回输入区。
        val chat = codeOnly(readRepoFile(chatPath))
        assertTrue(
            "InputBar 内容列没有撑满面板宽（fillMaxWidth）",
            chat.contains("Column(Modifier.fillMaxWidth()) {")
        )
        val inputBar = chat.substringAfter("private fun InputBar(")
        assertFalse(
            "输入区又套回了 880dp 限宽——2026-11-22 拍板（输入区撑满面板）被退回",
            inputBar.contains("adaptiveColumn(ChatWideLayout.ContentMaxWidth)")
        )
    }

    @Test
    fun `决策机文件在位 且有 paneOf`() {
        val layout = codeOnly(readRepoFile(layoutPath))
        assertTrue(
            "ChatWideLayout.kt 没有 paneOf——宽度判断又散回页面里了",
            layout.contains("fun paneOf(")
        )
        assertTrue(
            "ChatWideLayout.kt 里没有 ContentMaxWidth——会话区限宽的唯一出处丢了",
            layout.contains("val ContentMaxWidth")
        )
    }
}
