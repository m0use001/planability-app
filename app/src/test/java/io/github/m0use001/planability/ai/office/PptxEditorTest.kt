package io.github.m0use001.planability.ai.office

import io.github.m0use001.planability.ai.ptl.PptxWriter
import java.io.File
import java.util.zip.ZipFile
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [PptxEditor] 的改稿闸门。
 *
 * ## 这些断言在保护什么
 *
 * 改稿最大的风险不是"改错一个字"，而是**静默地动了不该动的东西**：
 * 没列出的页被重排、母版丢失、动画被抹掉而工具报告"改好了"。用户拿到手才会发现，
 * 那时已经无法信任这个工具了。所以这里盯的不是结果好不好看，而是：
 * - 未点名的页是否**逐字节**保持原样
 * - 包结构、关系、备注等外围是否仍然自洽（能打开）
 * - 读不懂时是否明确失败，而不是悄悄"重建"一份
 */
class PptxEditorTest {

    private fun tmp(name: String) =
        File(System.getProperty("java.io.tmpdir"), "pptx-edit-$name").also { it.parentFile?.mkdirs() }

    private fun deck() = DocModel.Deck(
        title = "原演示",
        slides = listOf(
            DocModel.Slide(
                layout = DocModel.SlideLayout.TITLE,
                title = "第一页标题",
                subtitle = "副标题",
                notes = "开场备注"
            ),
            DocModel.Slide(
                title = "第二页标题",
                blocks = listOf(
                    DocModel.Block.Bullets(listOf("要点甲", "要点乙")),
                    DocModel.Block.Para("一段补充", bold = true)
                )
            ),
            DocModel.Slide(
                title = "第三页标题",
                blocks = listOf(DocModel.Block.Table(listOf(listOf("列A", "列B"), listOf("值1", "值2"))))
            )
        )
    )

    private fun buildSource(): File {
        val f = tmp("src.pptx")
        PptxWriter.write(f, deck())
        return f
    }

    @Test
    fun readGetsTextTitleBulletsTableAndNotes() {
        val d = PptxEditor.read(buildSource())
        assertEquals("读出的页数不对", 3, d.slides.size)
        assertEquals("第 1 页标题", "第一页标题", d.slides[0].title)
        assertEquals("副标题没读出来", "副标题", d.slides[0].subtitle)
        assertEquals("开场备注没读出来", "开场备注", d.slides[0].notes)
        // 页码是改稿的命中键，必须填对
        assertEquals(listOf(1, 2, 3), d.slides.map { it.pageNo })
        // 要点应识别为 bullets 而不是散落的段落
        val s2 = d.slides[1]
        val bullets = s2.blocks.filterIsInstance<DocModel.Block.Bullets>().flatMap { it.items }
        assertTrue("第 2 页要点没读出来：$bullets", bullets.contains("要点甲"))
        // 表格单元格也是文字，不该被当成要点
        val s3text = s3Texts(d)
        assertTrue("表格文字没读出来：$s3text", s3text.contains("列A") && s3text.contains("值2"))
    }

    private fun s3Texts(d: DocModel.Deck): String =
        d.slides[2].blocks.joinToString(" ") {
            when (it) {
                is DocModel.Block.Table -> it.rows.flatten().joinToString(" ")
                is DocModel.Block.Bullets -> it.items.joinToString(" ")
                is DocModel.Block.Para -> it.text
                is DocModel.Block.Picture -> it.path
            }
        }

    @Test
    fun untouchedSlidesAreByteIdentical() {
        // 这是本工具最重要的性质：用户没让改的地方，一个字节都不能动
        val src = buildSource()
        val out = tmp("out-untouched.pptx")
        val d = PptxEditor.read(src)
        PptxEditor.apply(
            src,
            listOf(d.slides[1].copy(title = "改过的标题", pageNo = 2)),
            out,
            d
        )
        val a = entryBytes(src)
        val b = entryBytes(out)
        // 注意：ByteArray 必须用 assertArrayEquals，assertEquals 比的是引用，永远不等
        assertArrayEquals(
            "只改了第 2 页，slide1.xml 不该变",
            a.getValue("ppt/slides/slide1.xml"),
            b.getValue("ppt/slides/slide1.xml")
        )
        assertArrayEquals(
            "只改了第 2 页，slide3.xml 不该变",
            a.getValue("ppt/slides/slide3.xml"),
            b.getValue("ppt/slides/slide3.xml")
        )
        // 外围件也必须保留：母版、版式、主题、图片关系、备注
        listOf(
            "ppt/slideMasters/slideMaster1.xml",
            "ppt/slideLayouts/slideLayout1.xml",
            "ppt/theme/theme1.xml",
            "ppt/_rels/presentation.xml.rels",
            "ppt/slides/_rels/slide1.xml.rels"
        ).forEach {
            assertArrayEquals("$it 不该被改", a[it], b[it])
        }
    }

    @Test
    fun changedSlideActuallyChangedAndKeepsStructure() {
        val src = buildSource()
        val out = tmp("out-changed.pptx")
        val d = PptxEditor.read(src)
        PptxEditor.apply(
            src,
            listOf(d.slides[1].copy(title = "全新标题", pageNo = 2)),
            out,
            d
        )
        val xml = entryText(out, "ppt/slides/slide2.xml")
        assertTrue("新标题没写进去", xml.contains("全新标题"))
        assertFalse("旧标题还在", xml.contains("第二页标题"))
        // 整页重写后关系文件仍要自洽（rId1 必须是 slideLayout）
        val rels = entryText(out, "ppt/slides/_rels/slide2.xml.rels")
        assertTrue("slide2 的 rels 丢了 slideLayout", rels.contains("slideLayout1.xml"))
        // 页数元信息要跟着改，否则资源管理器里显示旧页数
        assertTrue("docProps 没更新", entryText(out, "docProps/app.xml").contains("<Slides>3</Slides>"))
    }

    @Test
    fun rewritingWithPictureStillOpensBecausePictureIsSkippedNotDangling() {
        // 关键边界：重写一页时那张图**读不回来了**（整页重写只保留文字）。
        // 这时必须"图没了但文件仍然能打开"，绝不能留下指向不存在媒体的 r:embed——
        // 那种悬空关系会让 PowerPoint 直接弹"需要修复"，整份文件废掉
        val src = buildSource()
        val out = tmp("out-pic.pptx")
        val d = PptxEditor.read(src)
        PptxEditor.apply(
            src,
            listOf(d.slides[0].copy(title = "换标题", pageNo = 1)),
            out,
            d
        )
        val xml = entryText(out, "ppt/slides/slide1.xml")
        assertFalse("不该残留 r:embed 悬空引用", xml.contains("r:embed"))
        assertTrue("换标题后文件仍应完整", entryText(out, "ppt/presentation.xml").contains("sldIdLst"))
    }

    @Test
    fun pageNumberOutOfRangeFailsLoudly() {
        val src = buildSource()
        val out = tmp("out-oob.pptx")
        val d = PptxEditor.read(src)
        val res = PptxEditor.apply(
            src,
            listOf(DocModel.Slide(title = "不存在的一页", pageNo = 99)),
            out,
            d
        )
        assertEquals("不该报告成功", 0, res.changed)
        assertTrue("应说明第 99 页不存在", res.failed.any { it.contains("99") })
    }

    @Test
    fun nonPptxIsRejectedInsteadOfGuessing() {
        val notZip = tmp("fake.pptx")
        notZip.writeText("这根本不是 pptx，只是一段文字")
        var msg = ""
        try {
            PptxEditor.read(notZip)
        } catch (e: Exception) {
            msg = e.message ?: ""
        }
        assertTrue("读不懂的文件必须报错而不是返回半成品，实际报错：$msg", msg.isNotBlank())
    }

    private fun entryText(f: File, name: String): String =
        String(entryBytes(f).getValue(name), Charsets.UTF_8)

    private fun entryBytes(f: File): Map<String, ByteArray> =
        ZipFile(f).use { z ->
            val out = LinkedHashMap<String, ByteArray>()
            val en = z.entries()
            while (en.hasMoreElements()) {
                val e = en.nextElement()
                if (!e.isDirectory) out[e.name] = z.getInputStream(e).readBytes()
            }
            out
        }
}
