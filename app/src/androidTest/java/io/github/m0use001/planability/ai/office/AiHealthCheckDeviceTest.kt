package io.github.m0use001.planability.ai.office

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.m0use001.planability.ai.ptl.ToolExecImpls
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 体检新增两组的设备端验收（2026-11-10 夜）。
 *
 * 为什么不整屏跑体检：面板里还有搜索探测/真实语料扫描（网络+全量解析），
 * 在弱网或大文件下能把"30 秒体检"拖成几分钟——那属于旧行为，与本轮新增组无关。
 * 这里只验新增的 ⑤ 附件链路 / ⑥ 网页抓取，且都要能红（空输入/坏 fetch 必须判失败）。
 */
@RunWith(AndroidJUnit4::class)
class AiHealthCheckDeviceTest {

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext

    /** ⑤ 附件链路：真实 attachments 目录跑真实解析，结论不能是 FAIL */
    @Test
    fun 附件链路_真机跑真实附件() {
        val dir = File(ctx.filesDir, "attachments")
        val files = dir.listFiles()?.sortedByDescending { it.lastModified() }?.toList().orEmpty()
        val item = AiHealthCheck.checkAttachments(files) { f ->
            if (f.extension.equals("pdf", true))
                PoiDocumentReader.readPdfText(f) ?: "[扫描件]"
            else ToolExecImpls.parseDocText(f)
        }
        android.util.Log.i("HealthTest", "附件链路 => [${item.level}] ${item.detail}")
        assertTrue("附件链路不该判 FAIL（真机上全是坏文件才该 FAIL）：${item.detail}",
            item.level != AiHealthCheck.Level.FAIL)
        assertTrue("结论不能为空", item.detail.isNotBlank())
    }

    /** ⑥ 网页抓取：坏 fetch 必须判 FAIL（能红灯），好 fetch 必须判 OK */
    @Test
    fun 网页抓取_判据能红也能绿() {
        val bad = AiHealthCheck.checkWebFetch { null }
        assertEquals("fetch 返回 null 必须判 FAIL", AiHealthCheck.Level.FAIL, bad.level)

        // 注意：内容必须 ≥60 字——短于 60 会被判"抓取通道坏了"（FAIL），这是设计如此：
        // 体检的 WARN 专指"抓到了但对不上"，不是"抓回来的东西是截断的"
        val hijacked = AiHealthCheck.checkWebFetch { _ ->
            "<html><body>" + "blocked by proxy ".repeat(8) + "</body></html>"
        }
        assertEquals("内容对不上必须判 WARN", AiHealthCheck.Level.WARN, hijacked.level)

        // 真网络：能通就 OK，不通也是 FAIL（不崩）——两者都算"判据工作正常"
        val real = AiHealthCheck.checkWebFetch { url ->
            runCatching {
                okhttp3.OkHttpClient.Builder()
                    .callTimeout(8, java.util.concurrent.TimeUnit.SECONDS).build()
                    .newCall(okhttp3.Request.Builder().url(url).build())
                    .execute().use { r -> if (r.isSuccessful) r.body?.string()?.take(2000) else null }
            }.getOrNull()
        }
        android.util.Log.i("HealthTest", "网页抓取 => [${real.level}] ${real.detail}")
        assertTrue("真网络探测必须给出 OK 或 FAIL（不能是 SKIP/空）",
            real.level == AiHealthCheck.Level.OK || real.level == AiHealthCheck.Level.FAIL)
        assertFalse("真网络探测的说明不能为空", real.detail.isBlank())
    }
}
