package io.github.m0use001.planability.browser

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * BrowserActivity 底部面板渲染取证（视觉验收用，2026-11-04）。
 * 在模拟器上跑（日用机禁 connected 测试，AGENTS §4.2）：
 * 启动 Activity → 加载中(t≈1.5s) 与 加载完成(t≈9s) 各截一张，
 * 落盘到 app 外部私有目录供 adb pull。
 */
@RunWith(AndroidJUnit4::class)
class BrowserPanelRenderTest {

    private fun shot(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val ctx = instrumentation.targetContext
        val bmp = instrumentation.uiAutomation.takeScreenshot()
        val out = java.io.File(ctx.getExternalFilesDir(null), name)
        java.io.FileOutputStream(out).use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        bmp.recycle()
        println("SHOT=$name bytes=${out.length()}")
        assertTrue(out.length() > 10_000)
    }

    @Test
    fun panelRendersWithGuidanceHeader() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val ctx = instrumentation.targetContext
        val intent = Intent(ctx, BrowserActivity::class.java)
            .putExtra(BrowserActivity.EXTRA_URL, "https://www.bbdc.cn/newword")
        val scenario: ActivityScenario<BrowserActivity> = ActivityScenario.launch(intent)
        scenario.use {
            Thread.sleep(1200)
            shot("panel_loading.png")
            Thread.sleep(4000)
            shot("panel_mid.png")
            Thread.sleep(6000)
            shot("panel_loaded.png")
            Thread.sleep(8000)
            shot("panel_late.png")
        }
    }
}
