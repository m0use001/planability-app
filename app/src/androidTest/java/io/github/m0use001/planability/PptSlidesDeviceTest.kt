package io.github.m0use001.planability

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * 模拟器专用（AGENTS §4.2 禁日用机跑 instrumentation；模拟器允许）：
 * PptSlides 对真实课件的快照渲染验证——把渲染出的 JPEG 落到 files/ppt-verify/ 拉回人审。
 */
@RunWith(AndroidJUnit4::class)
class PptSlidesDeviceTest {

    @Test
    fun renderRealCourseware() {
        val ctx = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        // 模拟器 targetSdk 无外部存储授权：直接读 /data/local/tmp 中转件（chmod 644，惯用通道）
        val src = File("/data/local/tmp/ppt-sample.ppt")
        org.junit.Assert.assertTrue("样本文件不存在：" + src.absolutePath, src.canRead())
        val snaps = io.github.m0use001.planability.ai.office.PptSlides.renderSnapshots(src)
        org.junit.Assert.assertTrue("快照数>0，实际 " + snaps.size, snaps.isNotEmpty())
        val dir = File(ctx.filesDir, "ppt-verify").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        snaps.forEachIndexed { i, b ->
            File(dir, "%02d.jpg".format(i + 1)).writeBytes(b)
        }
        android.util.Log.d("PptSlidesTest", "rendered " + snaps.size + " slides to " + dir)
    }
}
