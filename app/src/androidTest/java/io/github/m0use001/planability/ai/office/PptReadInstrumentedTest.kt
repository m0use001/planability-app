package io.github.m0use001.planability.ai.office

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * `.ppt` 读取的**设备端端到端**验证（2026-11-05）：
 * 样本课件由 adb 推到 app 外部私有目录（`files/ppt_sample.ppt`），本测试
 * 走工具层真实入口 `parseDocText`（与 AI 调 file_read_document 同一条路），
 * 断言能抽出页标题文字并打印前几行。
 *
 * 样本有版权、不入库；测试在样本缺失时跳过（模拟器上临时放一份即可复跑）。
 */
@RunWith(AndroidJUnit4::class)
class PptReadInstrumentedTest {

    @Test
    fun parseDocTextReadsLegacyPpt() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val f = File(ctx.getExternalFilesDir(null), "ppt_sample.ppt")
        if (!f.exists() || f.length() < 1000) return   // 无样本则跳过（模拟器手工准备）
        val text = io.github.m0use001.planability.ai.ptl.ToolExecImpls.parseDocText(f)
        println("PPT_READ_LEN=${text.length}")
        println("PPT_READ_HEAD=" + text.take(400).replace("\n", " | "))
        assertTrue("应含页标记", text.contains("[第 1 页]"))
        assertTrue("应抽出至少 50 字", text.count { it.isLetter() } >= 50)
    }
}
