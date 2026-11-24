package io.github.m0use001.planability

import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import io.github.m0use001.planability.core.OcrHelper
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class OcrHelperTest {

    @Test
    fun recognizeChineseAndEnglish() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val tmp = File(context.cacheDir, "ocr-test.png")
        File("/data/local/tmp/ocr-test.png").inputStream().use { input ->
            tmp.outputStream().use { input.copyTo(it) }
        }
        val text = OcrHelper.recognize(context, Uri.fromFile(tmp))
        assertTrue("识别文本过短: [$text]", text.length >= 6)
        val ok = listOf("开会", "Meeting", "10:00", "设计稿", "笔记").any { text.contains(it, ignoreCase = true) }
        assertTrue("识别内容不匹配: [$text]", ok)
    }
}
