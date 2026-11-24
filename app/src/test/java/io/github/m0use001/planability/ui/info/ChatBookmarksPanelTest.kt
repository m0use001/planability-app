package io.github.m0use001.planability.ui.info

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 自动车签面板两条实机反馈（2026-11-11）：
 * ①「要加记忆，不然每次都是从顶部 1 开始」——面板列表状态抬到面板外（AnimatedVisibility
 *   收起会卸载内容），展开时滚到当前读到的书签；
 * ②「拉宽一点」——228→272dp。
 * 照 ContextCostTest 的路子钉源码结构（面板是 Compose，JVM 单测跑不了行为）。
 */
class ChatBookmarksPanelTest {

    private fun source(rel: String): String {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null && !File(dir, "app/src/main/java/io/github/m0use001/planability").isDirectory) {
            dir = dir.parentFile
        }
        val f = File(requireNotNull(dir) { "找不到仓库根" }, rel)
        assertTrue("源码不在了：$rel", f.isFile)
        return f.readText(Charsets.UTF_8)
    }

    @Test
    fun `面板状态外抬 + 展开滚到当前书签 + 宽度拉宽`() {
        val bm = source("app/src/main/java/io/github/m0use001/planability/ui/info/ChatBookmarks.kt")
        assertTrue(
            "面板没有 panelListState 参数（收起即丢状态，每次从顶部 1 开始）",
            bm.contains("panelListState: LazyListState")
        )
        assertTrue(
            "LazyColumn 没接 panelListState",
            bm.contains("state = panelListState")
        )
        assertTrue(
            "展开时没滚到当前书签（还是从顶部 1 开始）",
            bm.contains("LaunchedEffect(expanded") && bm.contains("scrollToItem")
        )
        assertTrue(
            "面板没拉宽（仍是 228dp）",
            bm.contains("272.dp") && !bm.contains("228.dp")
        )
    }

    @Test
    fun `调用点传入外抬的面板状态`() {
        val screen = source("app/src/main/java/io/github/m0use001/planability/ui/info/ChatScreen.kt")
        assertTrue(
            "MessageList 没有外抬的面板状态",
            screen.contains("val bookmarkPanelListState")
        )
        assertTrue(
            "ChatBookmarksPanel 调用点没接 panelListState",
            screen.contains("panelListState = bookmarkPanelListState")
        )
    }
}
