package io.github.m0use001.planability.ui.common

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 「二级+页面必须进主副框钻入框架」守卫（2026-11-22 用户「为什么还有没进主副框动画框架的
 * 页面，二级以后的菜单也要全部纳入这个框架」）。
 *
 * 背景：宽屏的二级页有两条**合法**宿主——① 列表→详情用 [BoardDrill]/[BoardDeck] 钻入；
 * ② 表单/选择页用 `PredictiveBackOverlay`（页内层，可作钻入瓦片或居中模态卡）。
 * **不允许**再出现「裸全屏 `Dialog(usePlatformDefaultWidth = false)`」这种自开窗口、
 * 彻底逃出相框的写法（`TimeRangePicker` 曾就是——用户平板截图「选择时间」弹成裸全屏、
 * 时间轴日程块透过评分内容、右栏浮在上面）。
 *
 * 本守卫只钉「不许再长裸全屏 Dialog」。已迁 [TimeRangePicker]；剩余三处属于后续批次
 * （MapPicker=计划/地点选择器批次、TextDocScreens=文件库 HTML/SVG 预览批次、VoiceInputSheet=
 * 语音输入浮层（瞬时控件），转完各自批次后从白名单删除。白名单只许缩小，不许加长。
 *
 * 路径安全：与 [WideBoardLayoutGuardTest] 同款——仓库根靠固定目录向上找，不接受路径参数；
 * 结构断言先 CRLF 归一化（worktree/异机 false-red）。
 */
class SubPageDrillGuardTest {

    private val appJava = "app/src/main/java"

    private val repoRoot: File by lazy {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null && !File(dir, "app/src/main").isDirectory) {
            dir = dir.parentFile
        }
        requireNotNull(dir) { "找不到仓库根" }.canonicalFile
    }

    /** 裸全屏 Dialog 的判定串（属性层面拿掉宽度限制 + 吃掉系统栏 = 自开整窗）。 */
    private val rawFullscreenDialog = "usePlatformDefaultWidth = false"

    /**
     * 仍允许存在裸全屏 Dialog 的文件（**只许缩小**）。
     *  · MapPicker.kt       —— 地点选择（WebView/Leaflet），随「计划/地点选择器」批次迁。
     *  · TextDocScreens.kt  —— HTML/SVG 预览（WebView），随「文件库」批次迁。
     *  · VoiceInputSheet.kt —— 语音输入浮层（瞬时控件，非二级页面）。
     */
    private val allowlist = setOf(
        "MapPicker.kt",
        "TextDocScreens.kt",
        "VoiceInputSheet.kt"
    )

    @Test
    fun `ui 源码不许再有裸全屏 Dialog（TimeRangePicker 已迁，白名单只许缩小）`() {
        val root = File(repoRoot, appJava)
        assertTrue("app 源码目录不见了：$root", root.isDirectory)
        val offenders = root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { it.name !in allowlist }
            .filter { f ->
                val txt = f.readText().replace("\r\n", "\n")
                // 逐行判、跳过注释行——KDoc/行注释里提到该串的历史说明不算（朴素 contains 会被注释绊倒）。
                txt.lineSequence().any { raw ->
                    val line = raw.trim()
                    !line.startsWith("//") && !line.startsWith("*") && !line.startsWith("/*") &&
                        line.contains(rawFullscreenDialog)
                }
            }
            .map { it.relativeTo(repoRoot).path }
            .toList()
        assertTrue(
            "又长出裸全屏 Dialog(usePlatformDefaultWidth = false) 了（逃出主副框钻入框架）：$offenders\n" +
                "二级+页面请用 BoardDrill/BoardDeck 钻入，或 PredictiveBackOverlay 页内层。",
            offenders.isEmpty()
        )
    }

    @Test
    fun `选择时间器已改页内层 不再自开窗口`() {
        val f = File(repoRoot, "$appJava/io/github/m0use001/planability/ui/edit/TimeRangePicker.kt")
        assertTrue("TimeRangePicker.kt 不见了", f.isFile)
        val s = f.readText().replace("\r\n", "\n")
        assertTrue(
            "TimeRangePickerScreen 没有走 PredictiveBackOverlay 页内层",
            s.contains("PredictiveBackOverlay(")
        )
        assertTrue(
            "TimeRangePickerScreen 仍在 import/调用 androidx.compose.ui.window.Dialog",
            !s.contains("import androidx.compose.ui.window.Dialog")
        )
    }
}
