package io.github.m0use001.planability.ui.info

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 文件库的源码级断言：层级约定 + 折叠语义 + 导出文件页确实没了。
 *
 * 三条都是"全绿单测也测不到"的那类：
 * - 折叠逻辑错，表现是「收起后目录整行消失，再也点不回来」；
 * - 层级与抽屉不一致，用户在抽屉里认出来的归属到文件库就找不到了；
 * - 页面删没删干净，会以"某个入口点进去白屏"的形式在用户手上出现。
 */
class FileLibraryShapeTest {

    private val lib: String by lazy { read("FileLibraryScreen.kt") }
    private val drawer: String by lazy { read("WorkbenchDrawer.kt") }
    private val infoCenter: String by lazy { read("InfoCenterScreen.kt") }

    private fun read(name: String): String {
        val f = File("src/main/java/io/github/m0use001/planability/ui/info/$name")
        assertTrue("找不到 $name（测试的工作目录不对？）", f.exists())
        return f.readText()
    }

    private fun code(s: String) =
        s.lines().filterNot { it.trimStart().startsWith("//") }.joinToString("\n")

    /**
     * 2026-10-28 用户：「文件库要做成之前『文件』页面的样式，而不是复制右滑菜单的项目与会话」
     * —— 树折叠换成**逐层目录浏览**（面包屑 + 返回逐级退），`visibleRows` 那套整体移除。
     * 这条改钉新语义：必须真有"下钻/回退"两层动作，且顶栏有可点的面包屑。
     */
    @Test
    fun `逐层浏览_有下钻与回退`() {
        val c = code(lib)
        assertTrue("文件库没有逐层下钻（descend/popDir）—— 旧文件页的浏览语义丢了", c.contains("fun descend("))
        assertTrue("文件库没有逐级回退 popDir", c.contains("fun popDir("))
        assertTrue("顶栏没有面包屑（crumbs）—— 用户看不到自己在哪一层", c.contains("crumbs"))
    }

    @Test
    fun `项目数据钉在最前_用户文件在后`() {
        val c = code(lib)
        assertTrue(
            "文件库没有把「项目数据」钉到项目节点最前（用户 2026-10-29：要与自己的文件一眼分开）",
            c.contains("ProjectDirs.DATA")
        )
        assertTrue(
            "项目数据那一行要用数据库形图标（AppIcons.Kind.ProjectData）",
            c.contains("AppIcons.Kind.ProjectData")
        )
    }

    /**
     * 项目数据之外的东西一律只读：`dataRel == null` 的条目不给编辑/改名/删除。
     * 这是 2026-10-29 落盘改造的安全闸 —— app 不写「项目数据」以外的地方，
     * 所以也不许在文件库里删用户的文件。
     */
    @Test
    fun `项目数据之外只读`() {
        val c = code(lib)
        assertTrue("文件库没有 dataRel 判定（项目数据内外的分界）", c.contains("dataRel"))
        assertTrue(
            "用户文件（dataRel 为空）必须走只读分支：点开只能预览，不能进编辑器",
            c.contains("if (fl.projectId >= 0)")
        )
    }

    @Test
    fun `层级照抄抽屉_未分类与项目都要有`() {
        val c = code(lib)
        assertTrue("文件库顶层没有「未分类」（抽屉里的顶层伪节点）", c.contains("\"未分类\""))
        assertTrue(
            "文件库没有按项目分组 —— 用户要求上下级关系参考信息界面右滑菜单。" +
                "2026-10-29 起项目节点读的是整棵项目根（ProjectDirs.root），" +
                "「项目数据」只是它下面的固定子目录",
            c.contains("ProjectDirs.root(p.id)")
        )
        assertTrue(
            "未分类下要按会话分组（只有自己拥有目录的会话才列，用 existingDirFor 且不建目录）",
            // 会话行现在复用目录行样式（不再单独配会话图标），所以这里只钉"按会话分组"这件事
            c.contains("ExportStore.existingDirFor")
        )
        assertFalse(
            "浏览用的必须是 existingDirFor 而不是 dirFor —— 后者会给每个会话都建一个空目录，" +
                "未分类那栏会凭空冒出几十个空文件夹",
            c.contains("ExportStore.dirFor(")
        )
    }

    @Test
    fun `旧导出目录仍然收着`() {
        val c = code(lib)
        assertTrue(
            "文件库里没有「旧导出目录」分组 —— v14.3 之前生成的文件还在旧位置，" +
                "「导出文件」页删了之后它们就没人看得见了（等于告诉用户文件没了）",
            c.contains("\"旧导出目录\"") && c.contains("ExportStore.legacyFiles")
        )
    }

    @Test
    fun `导出文件页与入口都删干净了`() {
        assertFalse(
            "抽屉里还有「导出文件」入口行 —— 用户 2026-10-12 要求删除该功能",
            drawer.contains("\"导出文件\"")
        )
        assertFalse("抽屉还留着 onExports 参数", drawer.contains("onExports"))
        assertFalse(
            "InfoCenterScreen 还接线到导出文件页",
            infoCenter.contains("showExports") || infoCenter.contains("ExportBrowserScreen")
        )
        assertFalse(
            "ExportBrowserScreen.kt 还在（预览与电脑端渲染已搬去 FileLibraryPreview.kt，页面本身应删）",
            File("src/main/java/io/github/m0use001/planability/ui/info/ExportBrowserScreen.kt").exists()
        )
    }

    /**
     * 返回键三层：先关预览 → 再退目录层级 → 最后才关文件库。
     * 2026-10-29 起目录浏览是逐层的，所以中间多了一层 `path.isNotEmpty() -> popDir()`。
     */
    @Test
    fun `返回键要分层_先关预览再退层最后关文件库`() {
        val c = code(lib)
        assertTrue(
            "返回键没有分层 —— 在预览里按返回会把整个文件库一起关掉，" +
                "用户想回列表却直接被弹回上一层（2026-10-12 真机踩到）",
            c.contains("preview != null -> preview = null")
        )
        assertTrue(
            "返回键没有先退目录层级 —— 在子目录里按返回应该回上一层，而不是直接关掉文件库",
            c.contains("path.isNotEmpty() -> popDir()")
        )
        assertTrue("返回键最后一档必须才是关闭文件库", c.contains("else -> onDone()"))
    }

    /**
     * 2026-11-01 用户复报「卡死在返回时的下陷特效」根修后的防回归钉：
     * ①文件库顶栏返回必须与手势同一条分层规则（层级内直接 popDir，不走收缩动画）——
     *   收缩是"关页面"语义，分层 pop 后覆盖层还留着；
     * ②PredictiveBackOverlay.close() 在 onBack() 之后必须复位 closing/fraction——
     *   否则分层返回后整页永久停在下陷收场态（缩 8%+变暗+切角），
     *   预测手势被禁、系统返回也被 if(!closing) 吃掉（2026-10-31 修的嵌套窗口只是诱因之一）。
     */
    @Test
    fun `顶栏返回不走收缩动画_覆盖层关闭后要复位收缩态`() {
        val c = code(lib)
        val nav = c.substringAfter("navigationIcon").substringBefore("actions = {")
        assertTrue(
            "顶栏返回没有分层（预览→层级→才走 overlayClose）——层级内点返回会触发收缩动画，" +
                "叠加分层 pop 就是用户看到的卡死下陷",
            nav.contains("preview = null") && nav.contains("popDir()") && nav.contains("overlayClose()")
        )
        val overlay = File("src/main/java/io/github/m0use001/planability/app/PredictiveBackOverlay.kt")
            .readText().let { s -> s.lines().filterNot { it.trimStart().startsWith("//") }.joinToString("\n") }
        assertTrue(
            "PredictiveBackOverlay.close() 在 onBack() 之后没有复位 closing=false —— " +
                "分层返回的覆盖层会永久卡在下陷收场态",
            overlay.contains("closing = false")
        )
        assertTrue(
            "PredictiveBackOverlay.close() 在 onBack() 之后没有复位 fraction=0 —— 收缩态会留在整页上",
            overlay.contains("fraction = 0f")
        )
    }

    @Test
    fun `预览与电脑端渲染跟着文件库走`() {
        assertTrue(
            "文件库里没有接上文件预览 —— AI 生成的文件点不开就等于存了个占位",
            lib.contains("OfficeFilePreview")
        )
        // 预览必须画在 PredictiveBackOverlay **里面**：画在外面（主窗口）会被文件库这个
        // Dialog 整个盖住，点文件看着像"没反应"（2026-10-12 真机踩到过一次）。
        // v53.3 起结构变了：内容抽进 `shell` lambda，overlay 有两个来源——
        //   独立打开 = 本屏自套 PredictiveBackOverlay；「资料」Hub 打开 = insideOverlay（Hub 套）。
        // 不变的硬性条件是：**预览必须画在 shell 里**（shell 又只在 overlay 的 content 里被调用），
        // 以及独立打开的分支必须真的套 overlay（否则独立路径丢窗）。
        val c = code(lib)
        val shellAt = c.indexOf("val shell: @Composable () -> Unit = shell@{")
        val previewAt = c.indexOf("OfficeFilePreview(")
        assertTrue("没找到 shell 内容块（v53.3 抽层后约定变了？）", shellAt >= 0)
        assertTrue(
            "OfficeFilePreview 不在 shell 里 —— 它会被文件库这个 Dialog 整个盖住，点文件像没反应",
            previewAt > shellAt
        )
        assertTrue(
            "独立打开文件库的分支丢了 PredictiveBackOverlay —— 整页返回手势会失灵",
            c.contains("else PredictiveBackOverlay(")
        )
        assertTrue(
            "文件库里没有「电脑端渲染」开关 —— 它原本长在导出文件页上，删页后必须搬过来",
            lib.contains("PcBridgeSettingCard")
        )
        val preview = read("FileLibraryPreview.kt")
        assertTrue(
            "预览组件丢了 PDF 优先的查找约定（有电脑端 PDF 就翻 PDF）",
            preview.contains("preview.pdf") && preview.contains("nameWithoutExtension")
        )
    }
}
