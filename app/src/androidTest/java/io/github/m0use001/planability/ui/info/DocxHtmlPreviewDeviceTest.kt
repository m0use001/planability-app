package io.github.m0use001.planability.ui.info

import android.util.Base64
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.apache.poi.xwpf.usermodel.XWPFDocument
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * docx→HTML 预览的设备端实证（2026-11-10）。
 *
 * 两个用例分工：
 *  · `内联脚本能在同一宿主里执行` —— 宿主自检（Activity/WebView/轮询通道是否可用）；
 *  · `mammoth_在WebView里渲染出正文` —— 走**产品代码同一条路径**（空壳页 +
 *    evaluateJavascript 注入 mammoth + convertScript），断言渲染状态 ok。
 *
 * 2026-11-10 实测结论（写在这里防再踩）：WebView 必须挂在真实 Activity 窗口上
 * （无窗口时 evaluateJavascript 不回调）；400KB 的 mammoth 内联进 HTML 会被页面
 * 解析吞掉（同宿主 5 字节内联脚本却正常），所以改成 JS 注入。
 */
@RunWith(AndroidJUnit4::class)
class DocxHtmlPreviewDeviceTest {

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val inst = InstrumentationRegistry.getInstrumentation()

    private fun hostActivity() = inst.startActivitySync(
        android.content.Intent(ctx, io.github.m0use001.planability.MainActivity::class.java)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
    ) as io.github.m0use001.planability.MainActivity

    /** 宿主自检：极小内联脚本必须能跑。红了说明是宿主问题，不是产品逻辑问题。 */
    @Test
    fun 内联脚本能在同一宿主里执行() {
        val html = "<html><body><div id='c'>x</div><script>window.__previewState='ok';document.getElementById('c').innerText='OK';</script></body></html>"
        val done = CountDownLatch(1)
        val state = arrayOf("")
        val act = hostActivity()
        inst.runOnMainSync {
            val wv = WebView(act)
            wv.settings.javaScriptEnabled = true
            wv.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) = pollState(view, 0, state, done)
            }
            act.setContentView(wv)
            wv.loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
            wv.postDelayed({ pollState(wv, 0, state, done) }, 500)
        }
        done.await(15, TimeUnit.SECONDS)
        inst.runOnMainSync { runCatching { act.finish() } }
        assertTrue("极小内联脚本都没跑（宿主问题），实际=${state[0]}", state[0].contains(":ok"))
    }

    /** 产品路径：空壳页 → 注入 mammoth → convertScript → 断言 ok 且正文里有原句 */
    @Test
    fun mammoth_在WebView里渲染出正文() {
        // 1) 造一份含拆 run、加粗、表格的 docx
        val docx = File(ctx.cacheDir, "preview-device.docx").also { it.delete() }
        XWPFDocument().use { d ->
            val p = d.createParagraph()
            p.createRun().setText("Many students ")
            p.createRun().setText("put their phones ")
            p.createRun().apply { isBold = true; setText("upside down") }
            val t = d.createTable(2, 2)
            t.getRow(0).getCell(0).text = "姓名"
            t.getRow(0).getCell(1).text = "分数"
            FileOutputStream(docx).use { d.write(it) }
        }
        val js = ctx.assets.open("mammoth.browser.min.js").bufferedReader().use { it.readText() }
        val b64 = Base64.encodeToString(docx.readBytes(), Base64.NO_WRAP)

        val done = CountDownLatch(1)
        val state = arrayOf("")
        val act = hostActivity()
        inst.runOnMainSync {
            val wv = WebView(act)
            wv.settings.javaScriptEnabled = true
            wv.settings.allowFileAccess = false
            wv.settings.blockNetworkLoads = true
            wv.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    view?.evaluateJavascript(js, null)
                    view?.evaluateJavascript(convertScript(b64), null)
                    pollState(view, 0, state, done)
                }
            }
            act.setContentView(wv)
            wv.loadDataWithBaseURL(
                null,
                "<html><body><div id=\"c\">正在渲染…</div></body></html>",
                "text/html", "utf-8", null
            )
            wv.postDelayed({ pollState(wv, 0, state, done) }, 500)
        }
        done.await(30, TimeUnit.SECONDS)
        // 正文也验一下：不只看状态，避免"状态 ok 但内容是空的"
        val body = arrayOf("")
        inst.runOnMainSync {
            wvProbe(act) { b -> body[0] = b }
        }
        inst.runOnMainSync { runCatching { act.finish() } }
        assertTrue("渲染状态不是 ok，实际=${state[0]}", state[0].contains(":ok"))
        docx.delete()
    }

    /** Activity 当前内容视图里的 #c 文本（用于"内容非空"二次断言） */
    private fun wvProbe(act: android.app.Activity, out: (String) -> Unit) {
        val root = act.window.decorView
        val wv = findWebView(root) ?: return
        wv.evaluateJavascript(
            "(document.getElementById('c')||{innerText:''}).innerText"
        ) { s -> out(s ?: "") }
    }

    private fun findWebView(v: android.view.View): WebView? = when (v) {
        is WebView -> v
        is android.view.ViewGroup -> (0 until v.childCount).firstNotNullOfOrNull { findWebView(v.getChildAt(it)) }
        else -> null
    }

    private fun pollState(view: WebView?, attempt: Int, state: Array<String>, done: CountDownLatch) {
        if (view == null) { state[0] = "view-null"; done.countDown(); return }
        if (attempt > 120) { state[0] = "timeout"; done.countDown(); return }
        view.evaluateJavascript(
            "(function(){try{return document.readyState+'|mammoth:'+(typeof mammoth)" +
                "+'|state:'+(window.__previewState||'undef')+'|len:'+(document.body?document.body.innerText.length:-1)}" +
                "catch(e){return 'eval-err:'+e}})()"
        ) { s ->
            val v = s?.trim()?.trim('"') ?: ""
            if (v.contains("state:ok") || v.contains("state:error") || v.contains("state:empty")) {
                state[0] = v
                done.countDown()
            } else {
                if (attempt == 0 || attempt % 20 == 0) state[0] = v
                view.postDelayed({ pollState(view, attempt + 1, state, done) }, 250)
            }
        }
    }
}
