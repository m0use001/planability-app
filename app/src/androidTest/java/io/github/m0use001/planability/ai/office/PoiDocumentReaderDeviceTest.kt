package io.github.m0use001.planability.ai.office

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.FileOutputStream
import org.apache.poi.xwpf.usermodel.XWPFDocument
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 设备端实证：POI / PDFBox 在**真机**上能不能加载并解析（2026-11-09 第一刀）。
 *
 * 为什么必须上设备验：这两个库在 JVM 上跑绿不代表 Android 上跑得动——
 * R8 keep 不完整会 NoClassDefFoundError，xmlbeans 的 schema 资源在 APK 里可能被打包漏。
 * 单测（JVM）+ 本测试（设备）两层都过，才算这条链路可信。
 *
 * 运行方式（**不跑 connected 测试**，日用机数据安全）：
 *   ./gradlew :app:assembleDebugAndroidTest
 *   adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
 *   adb shell am instrument -w -e class io.github.m0use001.planability.ai.office.PoiDocumentReaderDeviceTest \
 *       io.github.m0use001.planability.test/androidx.test.runner.AndroidJUnitRunner
 */
@RunWith(AndroidJUnit4::class)
class PoiDocumentReaderDeviceTest {

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext

    private fun tmp(name: String) = File(ctx.cacheDir, "poi-device-$name").also { it.delete() }

    @Test
    fun docx_多run拆分_真机解析空格不丢() {
        val f = tmp("split.docx")
        XWPFDocument().use { docx ->
            val p = docx.createParagraph()
            p.createRun().setText("Many students ")
            p.createRun().setText("put their ")
            p.createRun().setText("phones ")
            p.createRun().setText("upside down")
            FileOutputStream(f).use { docx.write(it) }
        }
        val text = PoiDocumentReader.readWord(f)
        assertNotNull("真机 POI 没读出内容（R8/xmlbeans 资源问题）", text)
        assertTrue("run 拆分处空格被吞：\n$text",
            text!!.contains("Many students put their phones upside down"))
        f.delete()
    }

    @Test
    fun pdf_文字层_真机可抽取() {
        // 造一份有文字层的 PDF（PDFBox 直接写），再抽回来
        val f = tmp("text.pdf")
        com.tom_roush.pdfbox.pdmodel.PDDocument().use { doc ->
            val page = com.tom_roush.pdfbox.pdmodel.PDPage()
            doc.addPage(page)
            com.tom_roush.pdfbox.pdmodel.PDPageContentStream(doc, page).use { cs ->
                cs.beginText()
                cs.setFont(com.tom_roush.pdfbox.pdmodel.font.PDType1Font.HELVETICA, 14f)
                cs.newLineAtOffset(40f, 700f)
                cs.showText("Many students put their phones upside down")
                cs.endText()
            }
            doc.save(f)
        }
        val text = PoiDocumentReader.readPdfText(f)
        assertNotNull("真机 PDFBox 抽不到文字层（资源未初始化或 R8 问题）", text)
        assertTrue("抽出的内容不对：\n$text",
            text!!.contains("Many students put their phones upside down"))
        f.delete()
    }

    /** 2026-11-09：PDF 必须带页码锚点，模型才能回答"第 N 页那句" */
    @Test
    fun pdf_多页_带页码锚点() {
        val f = tmp("pages.pdf")
        com.tom_roush.pdfbox.pdmodel.PDDocument().use { doc ->
            listOf("First page content", "Second page content").forEachIndexed { i, t ->
                val page = com.tom_roush.pdfbox.pdmodel.PDPage()
                doc.addPage(page)
                com.tom_roush.pdfbox.pdmodel.PDPageContentStream(doc, page).use { cs ->
                    cs.beginText()
                    cs.setFont(com.tom_roush.pdfbox.pdmodel.font.PDType1Font.HELVETICA, 14f)
                    cs.newLineAtOffset(40f, 700f)
                    cs.showText(t)
                    cs.endText()
                }
            }
            doc.save(f)
        }
        val text = PoiDocumentReader.readPdfText(f)
        assertNotNull(text)
        assertTrue("缺 [第1页] 锚点：\n$text", text!!.contains("[第1页]"))
        assertTrue("缺 [第2页] 锚点：\n$text", text.contains("[第2页]"))
        // 页码顺序必须正确：第 1 页内容出现在第 2 页锚点之前
        assertTrue("页码顺序错了：\n$text",
            text.indexOf("First page content") < text.indexOf("[第2页]"))
        f.delete()
    }

    /** 2026-11-09：整页截图贴进 Word 时，图片里的文字要能被 OCR 读出来 */
    @Test
    fun docx_内嵌图片_OCR可读() {
        val png = File(ctx.cacheDir, "docx-ocr-src.png")
        val bmp = android.graphics.Bitmap.createBitmap(900, 260, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bmp)
        canvas.drawColor(android.graphics.Color.WHITE)
        val paint = android.graphics.Paint().apply {
            textSize = 56f
            color = android.graphics.Color.BLACK
            isAntiAlias = true
        }
        canvas.drawText("Office OCR 2026", 30f, 140f, paint)
        java.io.FileOutputStream(png).use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        bmp.recycle()

        val docx = tmp("img.docx")
        org.apache.poi.xwpf.usermodel.XWPFDocument().use { d ->
            val run = d.createParagraph().createRun()
            java.io.FileInputStream(png).use { fis ->
                run.addPicture(
                    fis,
                    org.apache.poi.xwpf.usermodel.Document.PICTURE_TYPE_PNG,
                    "img.png",
                    org.apache.poi.util.Units.toEMU(450.0),
                    org.apache.poi.util.Units.toEMU(130.0)
                )
            }
            java.io.FileOutputStream(docx).use { d.write(it) }
        }
        png.delete()

        // 直接验图片 OCR 通道（不走"字太少"启发式：测试文件本身很小）
        val text = kotlinx.coroutines.runBlocking {
            PoiDocumentReader.readWordPictures(ctx, docx) { c, u ->
                io.github.m0use001.planability.core.OcrHelper.recognize(c, u)
            }
        }
        assertNotNull("docx 里没抽出图片或 OCR 返回空", text)
        assertTrue("OCR 没认出画面文字：\n$text", text!!.contains("OCR"))
        assertTrue("缺图片锚点：\n$text", text.contains("[文档内图片 1]"))
        docx.delete()
    }
}
