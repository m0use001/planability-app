package io.github.m0use001.planability.ai.office

import io.github.m0use001.planability.ai.ptl.PptxWriter
import io.github.m0use001.planability.shared.DocModel
import java.io.File
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [PptxWriter] 的结构闸门。
 *
 * ## 为什么这些断言看着"吹毛求疵"，实际全是修复提示的来源
 *
 * pptx 打不开的绝大多数原因不是内容错，而是**包结构**错：少一个 part、引用了不存在的
 * 关系 id、`[Content_Types].xml` 漏声明。PowerPoint 对这些一律弹"需要修复"，而这些错
 * 在手机上**完全看不出来**（文件大小正常、能列出来、能抽出文本）。
 * 所以这里把包结构当合同来测，每条规则钉一个断言。
 *
 * 另外本机装了 `python-pptx` 做第二道校验（它按 OOXML 对象模型严格解析，能打开基本等价于
 * PowerPoint 能打开）；本测试产出 sample.pptx 就是给它吃的。
 */
class PptxWriterTest {

    private fun sampleDeck(withNotes: Boolean = true, withPicture: Boolean = false, picture: File? = null) =
        DocModel.Deck(
            title = "季度复盘",
            theme = DocModel.Theme(name = "暗色", dark = true, accentHex = "4F6BED"),
            slides = listOf(
                DocModel.Slide(
                    layout = DocModel.SlideLayout.TITLE,
                    title = "2026 Q3 复盘",
                    subtitle = "增长与留存",
                    notes = if (withNotes) "开场先讲结论，别从背景讲起" else ""
                ),
                DocModel.Slide(
                    layout = DocModel.SlideLayout.BULLETS,
                    title = "三件主要的事",
                    blocks = listOf(
                        DocModel.Block.Bullets(listOf("新用户 +18%", "次日留存 41%", "付费转化 3.2%")),
                        DocModel.Block.Para("结论：留存是瓶颈", bold = true)
                    )
                ),
                DocModel.Slide(
                    layout = DocModel.SlideLayout.TABLE,
                    title = "分渠道明细",
                    blocks = listOf(
                        DocModel.Block.Table(
                            listOf(
                                listOf("渠道", "新增", "留存"),
                                listOf("自然量", "12,043", "45%"),
                                listOf("投放", "3,210", "31%")
                            )
                        )
                    ),
                    notes = if (withNotes) "被问到投放 ROI 时翻这页" else ""
                )
            ).let { slides ->
                if (withPicture && picture != null) {
                    slides.dropLast(1) + slides.last().copy(
                        blocks = slides.last().blocks + DocModel.Block.Picture(picture.absolutePath, "配图")
                    )
                } else slides
            }
        )

    private fun build(deck: DocModel.Deck, out: File): File {
        out.parentFile?.mkdirs()
        PptxWriter.write(out, deck)
        assertTrue("没生成文件", out.exists() && out.length() > 0)
        return out
    }

    /** 1x1 透明 PNG：足够验证"图片能被内嵌且关系自洽"，不必造真图。 */
    private fun tinyPng(): File {
        val f = File(System.getProperty("java.io.tmpdir"), "planability-tiny.png")
        val bytes = java.util.Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg=="
        )
        f.writeBytes(bytes)
        return f
    }

    private fun entriesOf(f: File): Map<String, String> {
        val map = HashMap<String, String>()
        ZipFile(f).use { z ->
            val en = z.entries()
            while (en.hasMoreElements()) {
                val e = en.nextElement()
                if (e.name.endsWith(".xml") || e.name.endsWith(".rels")) {
                    map[e.name] = z.getInputStream(e).readBytes().toString(Charsets.UTF_8)
                }
            }
        }
        return map
    }

    private fun parse(xml: String) {
        DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(xml.byteInputStream())
    }

    @Test
    fun packageHasAllRequiredParts() {
        val out = File("build/pptx-verify/sample.pptx")
        build(sampleDeck(), out)
        val e = entriesOf(out)
        val required = listOf(
            "[Content_Types].xml", "_rels/.rels", "docProps/core.xml", "docProps/app.xml",
            "ppt/presentation.xml", "ppt/_rels/presentation.xml.rels",
            "ppt/slideMasters/slideMaster1.xml", "ppt/slideMasters/_rels/slideMaster1.xml.rels",
            "ppt/slideLayouts/slideLayout1.xml", "ppt/slideLayouts/_rels/slideLayout1.xml.rels",
            "ppt/theme/theme1.xml"
        )
        required.forEach { assertTrue("缺少 part：$it", e.containsKey(it)) }
        for (i in 1..3) {
            assertTrue("缺少 slide$i", e.containsKey("ppt/slides/slide$i.xml"))
            assertTrue("缺少 slide$i 的 rels", e.containsKey("ppt/slides/_rels/slide$i.xml.rels"))
        }
    }

    @Test
    fun everyXmlPartIsWellFormed() {
        val out = File("build/pptx-verify/sample.xml.pptx")
        build(sampleDeck(), out)
        entriesOf(out).forEach { (name, xml) ->
            runCatching { parse(xml) }
                .onFailure { throw AssertionError("XML 解析失败：$name —— ${it.message}") }
        }
    }

    @Test
    fun contentTypesCoversEverySlide() {
        val out = File("build/pptx-verify/sample.ct.pptx")
        build(sampleDeck(), out)
        val ct = entriesOf(out).getValue("[Content_Types].xml")
        for (i in 1..3) {
            assertTrue("Content_Types 没声明 slide$i", ct.contains("/ppt/slides/slide$i.xml"))
        }
        assertTrue("没声明 presentation", ct.contains("/ppt/presentation.xml"))
        assertTrue("没声明 theme", ct.contains("/ppt/theme/theme1.xml"))
    }

    @Test
    fun allRelationshipIdsResolve() {
        val out = File("build/pptx-verify/sample.rels.pptx")
        build(sampleDeck(withPicture = true, picture = tinyPng()), out)
        val e = entriesOf(out)
        // 抽出某个 part 里用到的 r:id / r:embed，逐个去同名 .rels 里核对
        e.forEach { (name, xml) ->
            if (!name.startsWith("ppt/slides/slide")) return@forEach
            val used = Regex("(?:r:id|r:embed)=\"(rId\\d+)\"").findAll(xml).map { it.groupValues[1] }.toSet()
            if (used.isEmpty()) return@forEach
            val relsName = name.replace("ppt/slides/", "ppt/slides/_rels/") + ".rels"
            val relsXml = e[relsName]
            assertNotNull("$name 引用了 ${used.joinToString()} 但没有对应 .rels", relsXml)
            used.forEach { id ->
                assertTrue("$name 里的 $id 在 $relsName 里不存在", relsXml!!.contains("Id=\"$id\""))
            }
        }
        // presentation 的每张 slide 都要有关系
        val presRels = e.getValue("ppt/_rels/presentation.xml.rels")
        for (i in 1..3) assertTrue("presentation.rels 缺 slide$i", presRels.contains("slides/slide$i.xml"))
    }

    @Test
    fun notesSlidesAreWiredWhenNotesExist() {
        val withNotes = File("build/pptx-verify/sample.notes.pptx")
        build(sampleDeck(withNotes = true), withNotes)
        val e = entriesOf(withNotes)
        assertTrue("有备注却没生成 notesSlide", e.keys.any { it.startsWith("ppt/notesSlides/notesSlide") })
        assertTrue("有备注却没生成 notesMaster", e.containsKey("ppt/notesMasters/notesMaster1.xml"))
        // 演讲者备注内容要真的落在文件里（否则等于没写）
        val anyNotes = e.filterKeys { it.startsWith("ppt/notesSlides/notesSlide") }
            .values.firstOrNull { it.contains("开场先讲结论") }
        assertNotNull("备注文字没写进 notesSlide", anyNotes)

        // 关闭备注时不应产生 notes 相关 part
        val noNotes = File("build/pptx-verify/sample.nonotes.pptx")
        build(sampleDeck(withNotes = false), noNotes)
        val e2 = entriesOf(noNotes)
        assertTrue("没备注却生成了 notesSlide", e2.keys.none { it.startsWith("ppt/notesSlides/") })
    }

    @Test
    fun textIsReadableFromTheFile() {
        val out = File("build/pptx-verify/sample.text.pptx")
        build(sampleDeck(), out)
        val e = entriesOf(out)
        val joined = e.values.joinToString("\n")
        listOf("2026 Q3 复盘", "新用户 +18%", "分渠道明细", "投放").forEach {
            assertTrue("文件里读不到「$it」", joined.contains(it))
        }
    }

    @Test
    fun pictureIsEmbeddedAsMedia() {
        val out = File("build/pptx-verify/sample.pic.pptx")
        build(sampleDeck(withPicture = true, picture = tinyPng()), out)
        val names = java.util.zip.ZipFile(out).use { z ->
            z.entries().toList().map { it.name }
        }
        assertTrue("图片没被内嵌成 media", names.any { it.startsWith("ppt/media/") })
    }

    @Test
    fun missingPictureFileIsSkippedRatherThanBreakingTheFile() {
        // 图路径不存在时必须"跳过这张图"，绝不能留一个指向不存在媒体的 r:embed——
        // 那种关系会让 PowerPoint 弹修复提示，整个文件废掉
        val out = File("build/pptx-verify/sample.skip.pptx")
        val report = PptxWriter.write(
            out,
            DocModel.Deck(
                title = "缺图",
                slides = listOf(
                    DocModel.Slide(
                        title = "有图",
                        blocks = listOf(DocModel.Block.Picture("/no/such/file.png"))
                    )
                )
            )
        )
        assertEquals("没跳过的图数不对", 1, report.picturesSkipped.size)
        assertEquals("不该内嵌任何图", 0, report.pictures)
        val e = entriesOf(out)
        assertTrue("缺图时文件仍应完整", e.containsKey("ppt/presentation.xml"))
        val slide = e.getValue("ppt/slides/slide1.xml")
        assertTrue("不该残留 r:embed 引用", !slide.contains("r:embed"))
    }

    @Test
    fun slideSizeIs16x9() {
        val out = File("build/pptx-verify/sample.size.pptx")
        build(sampleDeck(), out)
        val pres = entriesOf(out).getValue("ppt/presentation.xml")
        assertTrue("不是 16:9", pres.contains("cx=\"12192000\" cy=\"6858000\""))
    }
}