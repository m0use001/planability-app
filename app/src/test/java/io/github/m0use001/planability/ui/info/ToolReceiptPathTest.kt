package io.github.m0use001.planability.ui.info

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 工具回执 → 「打开」按钮的路径解析。
 *
 * 背景（2026-10-20）：用户点 AI 生成的文件卡片，看到的是 docx 的 ZIP 二进制乱码。
 * 修完预览后还有第二个入口问题——入口本身依赖模型自己吐 ```file 块，
 * 模型少写一段用户就没有"打开"。所以从**工具回执**里直接挑路径。
 *
 * 这里盯的是两类错：
 * - 挑错了（多带中文括号/句号）→ 打开时报"文件不存在"
 * - 挑到不能预览的（zip/apk）→ 点开还是弹"不能预览"，按钮等于骗人
 */
class ToolReceiptPathTest {

    @Test
    fun extractsDocxFromExportReceipt() {
        assertEquals(
            "exports/示例文档.docx",
            toolWrittenPath("[OK] 已导出 exports/示例文档.docx（12 KB，由手机端生成（朴素版式）），可在「文件库」里查看或分享")
        )
    }

    @Test
    fun extractsMarkdownFromFileExport() {
        assertEquals(
            "exports/大纲.md",
            toolWrittenPath("[OK] 已导出 exports/大纲.md（120 字），可在文件页查看或分享")
        )
    }

    @Test
    fun ignoresNonPreviewableBinary() {
        assertNull(toolWrittenPath("[OK] 已导出 exports/备份.zip（20 KB）"))
        assertNull(toolWrittenPath("[OK] 已导出 exports/安装包.apk（2 MB）"))
    }

    @Test
    fun acceptsAnyTextFormat() {
        // 用户 2026-10-20：生成了 demo.htm，工具行却只有「撤销」没有「打开」——
        // 因为这里曾写死一小串扩展名。文本类一律要给出口。
        assertEquals("exports/demo.htm", toolWrittenPath("[OK] 已导出 exports/demo.htm（2462 字），可在「文件库」里查看或分享"))
        assertEquals("exports/页面.html", toolWrittenPath("[OK] 已导出 exports/页面.html（1 KB）"))
        assertEquals("exports/配置.yaml", toolWrittenPath("[OK] 已导出 exports/配置.yaml（12 行）"))
    }

    @Test
    fun ignoresFailedReceipts() {
        assertNull(toolWrittenPath("[FAIL] 写入失败：磁盘已满 exports/示例文档.docx"))
        assertNull(toolWrittenPath("[DENIED] 用户拒绝"))
    }

    @Test
    fun ignoresReceiptsWithoutPath() {
        assertNull(toolWrittenPath("[OK] 已创建日程「复习」：10月21日 09:00-10:00（编号 42）"))
    }
}
