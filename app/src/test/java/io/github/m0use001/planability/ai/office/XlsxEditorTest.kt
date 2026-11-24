package io.github.m0use001.planability.ai.office

import io.github.m0use001.planability.ai.ptl.OfficeWriter
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipFile

/**
 * 手机端 xlsx 改稿（用户 2026-10-20：「表格处理能不能也实现纯手机端」）。
 *
 * 这个编辑器是**外科手术式**改 XML：只动被点名的单元格/行，其余条目原样写回。
 * 所以测试盯两件事：
 * 1. 改对了（值、行序、增删行后的行号）；
 * 2. 没改坏（其它 zip 条目、workbook 结构还在）——整份重写最容易在这翻车，
 *    而用户感知是"我的表格打开后格式全没了"。
 */
class XlsxEditorTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun sample(): File {
        val f = File(tmp.root, "明细.xlsx")
        OfficeWriter.writeXlsx(f, "科目\t成绩\n工程数学\t88\n英语\t90")
        return f
    }

    private fun grid(f: File): List<List<String>> = XlsxEditor.readGrid(f).second

    @Test
    fun readsWhatWriterWrote() {
        val g = grid(sample())
        assertEquals("科目", g[0][0])
        assertEquals("88", g[1][1])
    }

    @Test
    fun setExistingCell() {
        val src = sample()
        val out = File(tmp.root, "out.xlsx")
        val res = XlsxEditor.apply(src, JSONArray("""[{"op":"set","cell":"B2","value":95}]"""), out)
        assertEquals(1, res.applied.size)
        assertTrue(res.failed.isEmpty())
        assertEquals("95", grid(out)[1][1])
    }

    @Test
    fun setNewCellInExistingRow() {
        val src = sample()
        val out = File(tmp.root, "out.xlsx")
        XlsxEditor.apply(src, JSONArray("""[{"op":"set","cell":"C1","value":"备注"}]"""), out)
        val g = grid(out)
        assertEquals("备注", g[0][2])
    }

    @Test
    fun setTextKeepsOtherCells() {
        val src = sample()
        val out = File(tmp.root, "out.xlsx")
        XlsxEditor.apply(src, JSONArray("""[{"op":"set","row":3,"col":1,"value":"物理"}]"""), out)
        val g = grid(out)
        assertEquals("物理", g[2][0])
        assertEquals("90", g[2][1])   // 同一行另一列不受影响
    }

    @Test
    fun addRowAppendsAtEnd() {
        val src = sample()
        val out = File(tmp.root, "out.xlsx")
        XlsxEditor.apply(src, JSONArray("""[{"op":"addRow","values":["化学",77]}]"""), out)
        val g = grid(out)
        assertEquals(4, g.size)
        assertEquals("化学", g[3][0])
        assertEquals("77", g[3][1])
    }

    @Test
    fun delRowShiftsFollowingRowsUp() {
        val src = sample()
        val out = File(tmp.root, "out.xlsx")
        XlsxEditor.apply(src, JSONArray("""[{"op":"delRow","row":2}]"""), out)
        val g = grid(out)
        assertEquals(2, g.size)
        assertEquals("科目", g[0][0])
        assertEquals("英语", g[1][0])   // 原来的第 3 行上移到第 2 行
    }

    @Test
    fun keepsOtherPartsUntouched() {
        val src = sample()
        val out = File(tmp.root, "out.xlsx")
        XlsxEditor.apply(src, JSONArray("""[{"op":"set","cell":"B2","value":1}]"""), out)
        val before = HashSet<String>()
        ZipFile(src).use { z -> z.entries().asSequence().forEach { before += it.name } }
        val after = HashMap<String, String>()
        ZipFile(out).use { z ->
            z.entries().asSequence().forEach { e ->
                after[e.name] = z.getInputStream(e).readBytes().toString(Charsets.UTF_8)
            }
        }
        assertEquals("条目数变了：整包被重写", before.size, after.size)
        assertTrue("workbook.xml 不该被动", after.containsKey("xl/workbook.xml"))
        assertTrue("样式/关系文件不该丢", after.containsKey("xl/_rels/workbook.xml.rels"))
    }

    @Test
    fun unknownCellOfMissingRowCreatesIt() {
        val src = sample()
        val out = File(tmp.root, "out.xlsx")
        XlsxEditor.apply(src, JSONArray("""[{"op":"set","cell":"A9","value":"尾巴"}]"""), out)
        val g = grid(out)
        assertEquals(4, g.size)          // 原 3 行 + 第 9 行（中间空行不补）
        assertEquals("尾巴", g[3][0])
    }

    @Test
    fun reportsFailedOpsInsteadOfSilentlyDropping() {
        val src = sample()
        val out = File(tmp.root, "out.xlsx")
        val res = XlsxEditor.apply(
            src,
            JSONArray("""[{"op":"delRow","row":99},{"op":"move","cell":"A1"}]"""),
            out
        )
        assertTrue(res.applied.isEmpty())
        assertEquals(2, res.failed.size)
    }
}
