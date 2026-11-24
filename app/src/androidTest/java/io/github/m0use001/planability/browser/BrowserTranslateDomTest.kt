package io.github.m0use001.planability.browser

import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONTokener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * **网页双语翻译的 DOM 级验证**（2026-11-05）：真 WebView + 真 DOM，
 * 跑 collectJs → parseBlocks → injectJs → clearJs 全链路。
 *
 * 为什么不只断言字符串：注入脚本的坑全在 DOM 语义上——选择器是否命中、
 * 译文本是否插在原文**后面**、重复收集是否去重、还原是否干净。这些只有
 * 在真 WebView 里跑一遍才知道（模拟器即可，无需 AI 配置：翻译文本这里用固定值代替）。
 */
@RunWith(AndroidJUnit4::class)
class BrowserTranslateDomTest {

    private val html = """
        <html><body>
          <h1>Chapter 8 Indeterminate Forms</h1>
          <p>When both the numerator and the denominator approach zero, the limit may still exist.</p>
          <p>L'Hopital's rule applies when the functions are differentiable near the point.</p>
          <p>图 1</p>
          <ul><li>A list item with enough English words to be translated.</li></ul>
        </body></html>
    """.trimIndent()

    @Test
    fun collectInjectClearRoundTrip() {
        val inst = InstrumentationRegistry.getInstrumentation()
        var web: WebView? = null
        inst.runOnMainSync { web = WebView(inst.targetContext) }
        val w = web ?: return
        val loaded = CountDownLatch(1)
        inst.runOnMainSync {
            w.settings.javaScriptEnabled = true
            w.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) { loaded.countDown() }
            }
            w.loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
        }
        assertTrue("页面加载超时", loaded.await(15, TimeUnit.SECONDS))

        fun evalSync(js: String): String? {
            var res: String? = null
            val latch = CountDownLatch(1)
            inst.runOnMainSync {
                w.evaluateJavascript(js) { v -> res = v; latch.countDown() }
            }
            latch.await(15, TimeUnit.SECONDS)
            return res
        }

        // collect：真实浏览器里 eval 会经 JSONTokener 解一层引号，这里模拟同样的解包
        val rawCollected = JSONTokener(evalSync(BrowserTranslate.collectJs())).nextValue()
        val blocks = BrowserTranslate.parseBlocks(rawCollected)
        println("DOM_COLLECT=" + blocks.size + " first=" + blocks.firstOrNull()?.text?.take(40))
        assertTrue("应收集到至少 3 段英文（标题/两段正文/列表项），实际 " + blocks.size, blocks.size >= 3)
        // 中文短句「图 1」不应进入待翻列表
        assertTrue(blocks.none { it.text.trim() == "图 1" })

        // inject：每段插一条译文
        val pairs = blocks.map { it.index to ("译文" + it.index) }
        val injectRet = JSONTokener(evalSync(BrowserTranslate.injectJs(pairs))).nextValue()
        assertEquals("OK", injectRet)
        val count = JSONTokener(evalSync("document.querySelectorAll('.zht-tr').length")).nextValue()
        assertEquals(blocks.size.toString(), count.toString())
        val firstText = JSONTokener(
            evalSync("document.querySelector('.zht-tr').textContent")
        ).nextValue().toString()
        assertTrue("译文本内容应写入，实际：$firstText", firstText.startsWith("译文"))

        // 译文必须插在原文之后（nextSibling），否则对照顺序错乱
        val order = JSONTokener(evalSync(
            "(function(){var p=document.querySelector('[data-zht]');" +
                "return p && p.nextElementSibling && p.nextElementSibling.className==='zht-tr';})()"
        )).nextValue().toString()
        assertEquals("true", order)

        // clear：译文与标记都要清干净
        JSONTokener(evalSync(BrowserTranslate.clearJs())).nextValue()
        val left = JSONTokener(
            evalSync("document.querySelectorAll('.zht-tr').length + ':' + document.querySelectorAll('[data-zht]').length")
        ).nextValue().toString()
        assertEquals("0:0", left)
    }
}
