package io.github.m0use001.planability.ui.home

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * v74 层序守卫（2026-11-22 用户平板截图「评分页和时间轴叠在一起」）。
 *
 * v68 把 PredictiveBackOverlay 从独立 Dialog 窗口改成页内层之后，Compose 的
 * 「后组合者画在上」让**代码组合顺序 = 绘制层级**。评分页覆盖层（showScore）自
 * v19.2 起写在页面主体 Box **之前**（当时是独立窗口，顺序与层级无关），v68 后
 * 主体的时间轴日程块与「今日概览」右栏就整层盖死评分页——计算框文字从日程块
 * 缝里透出来（用户实锤）。PredictiveBackOverlay 的 KDoc 明写调用点必须
 * 「与页面主体平级、排在其后」。本守卫钉两条：
 *  1. 评分覆盖层在页面主体 Box 之后；
 *  2. 评分覆盖层在编辑器区之前（编辑器是主体之上更深一层，别让它排到覆盖层下面）。
 *
 * 路径安全与 CRLF 归一化：与 TabMemoryGuardTest 同款。
 */
class OverlayZOrderGuardTest {

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
        // CRLF 归一化（worktree/异机检出假红的既有教训）
        return f.readText().replace("\r\n", "\n")
    }

    @Test
    fun `评分覆盖层必须组合在页面主体之后`() {
        val s = read("app/src/main/java/io/github/m0use001/planability/ui/home/HomeScreen.kt")
        val overlay = s.indexOf("PredictiveBackOverlay(onBack = { showScore = false })")
        assertTrue("评分覆盖层调用不见了", overlay >= 0)
        // 主体 Box 开头的第一条注释（根 Box(fillMaxSize) 内部，全文件唯一）
        val body = s.indexOf("状态栏内边距由 M3ETitleBar 自己吃")
        assertTrue("页面主体 Box 锚点不见了", body >= 0)
        assertTrue(
            "评分覆盖层组合在页面主体之前——v68 页内化后 = 被时间轴/右栏整层盖死（用户实锤）",
            overlay > body
        )
        val editors = s.indexOf("// ---------- 编辑器 ----------")
        assertTrue("编辑器锚点不见了", editors >= 0)
        assertTrue("评分覆盖层应排在编辑器之前（编辑器从主体进，是更深一层）", overlay < editors)
    }
}
