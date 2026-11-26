package io.github.m0use001.planability.ai.ptl

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * v56.1 复杂文件「页面截图喂 AI」兜底的源码级守卫。
 *
 * 链路四段：①复杂文件读不出文字 → ②页面快照持久化到 exports/（路径进结果文本）→
 * ③模型调 file_read_image → ④AiStream tool 分支把该图直通视觉模型。
 * 每条断言都可被植入故障打红（删路径头/改回 fail/删 snapDir/删直通分支各对应一条）。
 */
class FileVisionFallbackGuardTest {

    private fun source(rel: String): String {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null && !File(dir, "app/src/main/java/io/github/m0use001/planability").isDirectory) {
            dir = dir.parentFile
        }
        val f = File(requireNotNull(dir) { "找不到仓库根" }, rel)
        assertTrue("$rel 不在了", f.isFile)
        return f.readText(Charsets.UTF_8)
    }

    @Test
    fun `file_read_image OCR 为空仍返回 ok 带路径头（否则直通静默失效）`() {
        val s = source("app/src/main/java/io/github/m0use001/planability/ai/ptl/ToolExecImpls.kt")
        val begin = s.indexOf("private suspend fun readImageTool")
        val end = s.indexOf("private fun pptSlideNotes")
        assertTrue("readImageTool 段定位失败", begin in 0 until end)
        val seg = s.substring(begin, end)
        assertTrue("OCR 空又改回 fail——最需要看图的场景直通反而失效",
            seg.contains("图片已随本结果直通视觉模型"))
        assertTrue("readImageTool 结果丢了路径头（AiStream 按路径附图）", seg.contains("imageRelPath"))
    }

    @Test
    fun `扫描 PDF 逐页快照持久化且结果列路径`() {
        val exec = source("app/src/main/java/io/github/m0use001/planability/ai/ptl/ToolExecImpls.kt")
        assertTrue("pdf 分支没传 snapDir（快照仍用完即删）", exec.contains("Graph.appContext, f, snapDir"))
        assertTrue("pdf 分支没列快照路径头", exec.contains("每页快照已生成"))
        val poi = source("app/src/main/java/io/github/m0use001/planability/ai/office/PoiDocumentReader.kt")
        assertTrue("readPdfByOcr 没有 snapDir 参数", poi.contains("snapDir: File? = null"))
        assertTrue("快照写入逻辑丢了", poi.contains("snapDir.apply { mkdirs() }"))
    }

    @Test
    fun `AiStream 工具结果图片直通分支仍在位`() {
        val s = source("app/src/main/java/io/github/m0use001/planability/ai/AiStream.kt")
        assertTrue("tool 分支的 latest 判定丢了", s.contains("it.role == \"tool\""))
        assertTrue("结果文本图片引用解析丢了", s.contains("imageRefsIn(m.content)"))
    }

    @Test
    fun `file_read_image 登记在位（defs 与 FLOOR 与分发表）`() {
        // M4：ToolDefs/ToolRouter 真实现已迁 :shared，守卫跟着读 shared 源码
        val defs = source("shared/src/jvmShared/kotlin/io/github/m0use001/planability/shared/ToolDefs.kt")
        val router = source("shared/src/jvmShared/kotlin/io/github/m0use001/planability/shared/ToolRouter.kt")
        val exec = source("app/src/main/java/io/github/m0use001/planability/ai/ptl/ToolExecImpls.kt")
        assertTrue(defs.contains("name = \"file_read_image\""))
        assertTrue("FLOOR 没带 file_read_image（词法路由会挤掉它=模型看不见）",
            router.contains("\"file_read_image\""))
        assertTrue("分发表没带 file_read_image", exec.contains("\"file_read_image\" -> readImageTool"))
    }
}
