package io.github.m0use001.planability.ui.files

import io.github.m0use001.planability.data.WORKFLOW_MARKER
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 工作流清单路由判定（FileViewerRoute → OfficePreviewScreen 分支）。
 *
 * 背景：1172daef 把 .md 整体改路由到 Markdown 渲染后，带标记的 md 也进 MarkdownDocScreen，
 * 可打勾视图与 Wavy 进度条成了死代码——路由靠 [isWorkflowMdFile] 接回，这里钉死它的行为。
 */
class WorkflowMdRouteTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun write(name: String, firstLine: String, rest: String = "- [ ] 第一步\n"): File {
        val f = File(tmp.root, name)
        f.writeText(firstLine + "\n" + rest)
        return f
    }

    @Test
    fun `首行带标记的 md 判定为工作流`() {
        assertTrue(isWorkflowMdFile(write("a.md", WORKFLOW_MARKER)))
    }

    @Test
    fun `首行带前后空白的标记也算`() {
        assertTrue(isWorkflowMdFile(write("b.md", "  $WORKFLOW_MARKER  ")))
    }

    @Test
    fun `标记不在首行不算`() {
        assertFalse(isWorkflowMdFile(write("c.md", "# 标题", "$WORKFLOW_MARKER\n")))
    }

    @Test
    fun `普通 md 不算`() {
        assertFalse(isWorkflowMdFile(write("d.md", "# 标题")))
    }

    @Test
    fun `非 md 扩展名不算（即使内容一样）`() {
        assertFalse(isWorkflowMdFile(write("e.txt", WORKFLOW_MARKER)))
        assertFalse(isWorkflowMdFile(write("f.docx", WORKFLOW_MARKER)))
    }

    @Test
    fun `大写扩展名与 markdown 变体都算`() {
        assertTrue(isWorkflowMdFile(write("g.MD", WORKFLOW_MARKER)))
        assertTrue(isWorkflowMdFile(write("h.markdown", WORKFLOW_MARKER)))
    }

    @Test
    fun `文件不存在返回 false 而不是抛异常`() {
        assertFalse(isWorkflowMdFile(File(tmp.root, "missing.md")))
    }
}
