package io.github.m0use001.planability.ai.office

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * PPT 每页整体快照守卫（2026-11-11「AI 文件能力」夜）。
 *
 * 用户报障：「读 PPT 没法同时拿到每页整体截屏，即使是用视觉 AI」——老链路只抽文字，
 * 公式全是 WMF 矢量图（Equation Editor 元文件），对文字抽取完全失明。
 *
 * 修复 = PptSlides：Pictures 流 blip（PNG/JPEG + zlib 压缩的 WMF）按 SpContainer 的
 * pib 引用与 ClientAnchor 锚点重画每页；WMF 用 [WmfRenderer] 的 GDI 子集渲染。
 *
 * 二进制事实（真机 3 份课件实测钉死，回归靠这几条）：
 * ①锚点顺序是 **top,left,right,bottom**（按 ChildAnchor 的 l,t,r,b 解释会把级联公式画出页外）；
 * ②锚点记录 8 字节 4×int16（不是 16）；
 * ③EscherOpt 复杂属性的对齐推进必须 4 字节对齐（(v+3) and -4）——
 *   写成 `Int.MAX_VALUE.inv() and 0x3FFFC` 会得 0 → pib 永远收不到（首版真 bug）。
 */
class PptSlidesGuardTest {

    private fun srcOf(rel: String): String {
        val candidates = listOf(File(rel), File("app/$rel"))
        return candidates.firstOrNull { it.exists() }?.readText()
            ?: error("找不到 $rel")
    }

    private val pptSlides by lazy {
        srcOf("src/main/java/io/github/m0use001/planability/ai/office/PptSlides.kt")
    }
    private val wmfRenderer by lazy {
        srcOf("src/main/java/io/github/m0use001/planability/ai/office/WmfRenderer.kt")
    }

    @Test
    fun `锚点按 top-left-right-bottom 解释`() {
        val i = pptSlides.indexOf("RT_CLIENT_ANCHOR ->")
        assertTrue("ClientAnchor 分支不见了", i > 0)
        val body = pptSlides.substring(i, minOf(pptSlides.length, i + 300))
        assertTrue("锚点必须按 (top,left,right,bottom) 装入（4×int16）",
            body.contains("s16(buf, body)") && body.contains("s16(buf, body + 6)"))
        assertTrue("KDoc 必须钉死顺序事实（回归时先看这里）",
            pptSlides.contains("top, left, right, bottom"))
    }

    @Test
    fun `复杂属性对齐必须 4 字节`() {
        val i = pptSlides.indexOf("RT_OPT ->")
        assertTrue("Opt 分支不见了", i > 0)
        val body = pptSlides.substring(i, minOf(pptSlides.length, i + 800))
        assertTrue("对齐推进必须 (v+3) and -4（4 字节对齐）", body.contains("and -4"))
        assertTrue("不得再用 Int.MAX_VALUE.inv() and 0x3FFFC（恒 0，pib 永远收不到）",
            !body.contains("0x3FFFC"))
    }

    @Test
    fun `WMF 渲染必须覆盖公式实际用的 GDI 子集`() {
        for (fn in listOf(
            "FN_EXT_TEXTOUT", "FN_LINETO", "FN_MOVETO", "FN_SELECT_OBJECT",
            "FN_CREATE_FONT", "FN_SET_TEXT_COLOR", "FN_POLYGON", "FN_POLYLINE"
        )) {
            assertTrue("WmfRenderer 缺 $fn（公式里实际出现的记录）", wmfRenderer.contains(fn))
        }
        // 颜色解析的 or/shl 优先级坑（Kotlin 中缀同优先级左结合，必须显式括号）
        assertTrue("COLORREF 组装必须显式括号（or/shl 同优先级左结合）",
            wmfRenderer.contains("((c and 0xFF) shl 16)"))
        // 2026-11-11 撞测三修（公式页从叠印到可读）
        assertTrue("Symbol 字符集映射表必须存在（公式符号是 Symbol 编码字节）",
            wmfRenderer.contains("SYMBOL_MAP"))
        assertTrue("Symbol 判定必须按字体脸名", wmfRenderer.contains("contains(\"Symbol\""))
        assertTrue("dx 数组必须逐字符定位（紧排公式整串画必叠字）",
            wmfRenderer.contains("dxa.size == cnt"))
        assertTrue("文本必须进 bbox（MoveTo 只给基线，字高要上探）",
            wmfRenderer.contains("cpY - curH"))
    }

    @Test
    fun `文件读取链必须挂上快照增强`() {
        val src = srcOf("src/main/java/io/github/m0use001/planability/ai/ptl/ToolExecImpls.kt")
        assertTrue("parseDocText(.ppt) 必须追加 pptSlideNotes（快照+OCR+路径）",
            src.contains("pptSlideNotes(f, sessionId)"))
        assertTrue("file_read_image 结果必须带可引用相对路径（视觉直通靠它）",
            src.contains("imageRelPath("))
        // 2026-11-11 全链撞测：模型对 .jpg 快照连错 11 次调 file_read_document——
        // 报错文案必须点名替代工具，模型才有自救路径
        assertTrue("file_read_document 对图片报错必须指路 file_read_image",
            src.contains("请改用 file_read_image"))
        val asrc = srcOf("src/main/java/io/github/m0use001/planability/ai/AiStream.kt")
        assertTrue("AiStream tool 分支必须实现最近一条 tool 结果的图片直通",
            asrc.contains("isLatestTool"))
        // 2026-11-11 全链撞测 bug#3：exports/ 是虚拟前缀，dataUrl 不认会话导出目录 → 直通静默失效
        assertTrue("ImageAttach.dataUrl 必须支持会话导出目录回退（exports/ 前缀）",
            srcOf("src/main/java/io/github/m0use001/planability/ai/ImageAttach.kt")
                .contains("removePrefix(\"exports/\")"))
        assertTrue("streamChat 必须把会话导出目录传给图片加载器",
            asrc.contains("exportDir: java.io.File?"))
        assertTrue("ChatEngine 工具轮必须解析本会话导出目录并传给 streamChat",
            srcOf("src/main/java/io/github/m0use001/planability/ai/ChatEngine.kt")
                .contains("existingDirFor("))
    }
}
