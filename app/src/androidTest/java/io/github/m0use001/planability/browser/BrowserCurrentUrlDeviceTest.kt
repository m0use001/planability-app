package io.github.m0use001.planability.browser

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 2026-11-12 真机回执 6261（日用机◆）回归门：`browser_click` 结尾在工具层
 * （Default 协程）调 `BrowserSession.currentUrl()` 读 `web()?.url`——WebView 方法
 * 必须在主线程，修前直接炸
 * 「A WebView method was called on thread 'DefaultDispatcher-worker-N'」→ 工具 [FAIL]，
 * 模型只能告诉用户「点击导航报错」。
 *
 * 本测试从 **Default 线程**走真实 BrowserActivity（非 Main），修前必红、修后必绿。
 * 只对模拟器跑（AGENTS §4.2，日用机禁 instrumentation）。
 */
@RunWith(AndroidJUnit4::class)
class BrowserCurrentUrlDeviceTest {

    @Test
    fun currentUrl_fromDefaultDispatcher_doesNotThrow() = runBlocking {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        // 先确保会话干净（上个用例可能留着窗口）
        runCatching { BrowserSession.close() }
        ctx.startActivity(
            android.content.Intent(ctx, BrowserActivity::class.java)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(BrowserActivity.EXTRA_URL, "https://example.com/")
        )
        // 等 WebView attach（BrowserActivity onCreate 里 BrowserSession.attach）
        val attached = withTimeoutOrNull(10_000) {
            kotlinx.coroutines.delay(100)
            while (!BrowserSession.isActive()) kotlinx.coroutines.delay(100)
            true
        }
        assertTrue("BrowserActivity 10s 内没有 attach WebView", attached == true)
        try {
            // 关键断言：Default 线程读 URL 不许抛 WebView 线程异常（修前在这里红）
            val url = withContext(Dispatchers.Default) { BrowserSession.currentUrl() }
            assertNotNull("currentUrl() 返回 null（WebView 未就绪？）", url)
        } finally {
            withContext(Dispatchers.Main) { runCatching { BrowserSession.close() } }
        }
    }
}
