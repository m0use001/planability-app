package io.github.m0use001.planability.browser

import android.annotation.SuppressLint
import android.webkit.WebView
import android.webkit.WebViewClient
import io.github.m0use001.planability.core.SafeUrl
import io.github.m0use001.planability.data.Graph
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONTokener
import kotlin.coroutines.resume

/**
 * **无头渲染**（2026-11-08，用户实报「为什么 AI 每次网页抓取都要强制弹出浏览器页面」）。
 *
 * 起因：`web_fetch` 走 okhttp 只拿得到服务端 HTML。wiki / 知乎 / 示例社区这类前端渲染站
 * 返回的 HTML 是空壳 → `[FETCH_EMPTY]` → 模型按提示改用 `browser_open` →
 * **每一次抓取都弹出一个可见窗口**，页面读完还不关。
 *
 * 正确的分层是：
 * 1. okhttp 静态抓取（最快，不占 WebView）；
 * 2. 抓不到正文 → **本对象**：起一个不挂到窗口的 WebView，渲染完 JS 取 innerText，
 *    渲染完立刻销毁。用户什么都看不到，也不会留下窗口；
 * 3. 还是空（登录墙 / 验证码 / 站点拒绝 WebView）→ 才允许 `browser_open` 弹可见窗口，
 *    并且必须**明确请用户登录**，用完 `browser_close` 关掉。
 *
 * Cookie 与可见浏览器共用同一个 `CookieManager`（进程级）：用户在可见窗口登录过一次，
 * 之后无头渲染也能读到登录态——这是"只在需要登录时才打扰用户"能成立的前提。
 *
 * 约束：
 * - 必须主线程创建/使用/销毁 WebView；
 * - **不加载图片**（提正文不需要，省流量省时间）；
 * - 宿主用 application context（不上窗口，所以不会泄漏 Activity）；
 * - 只允许 http/https 且拒绝本机/私网（[SafeUrl]，模型给的 URL 不可信）。
 */
object HeadlessRender {

    private const val TIMEOUT_MS = 15_000L

    /** 桌面 UA：不少中文内容站对 WebView UA 直接给空壳或拒绝渲染 */
    private const val UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Safari/537.36"

    /** 渲染并取正文；失败（超时/被拒/空页）返回 null，不抛 */
    @SuppressLint("SetJavaScriptEnabled")
    suspend fun fetchText(url: String): String? = withContext(Dispatchers.Main) {
        if (!SafeUrl.isAllowed(url)) return@withContext null
        val ctx = runCatching { Graph.appContext }.getOrNull() ?: return@withContext null
        val web = runCatching { WebView(ctx) }.getOrNull() ?: return@withContext null
        try {
            web.settings.javaScriptEnabled = true
            // 正文不需要图片/媒体：关掉能省 90% 的流量与首屏等待
            web.settings.loadsImagesAutomatically = false
            web.settings.blockNetworkImage = true
            web.settings.mediaPlaybackRequiresUserGesture = true
            web.settings.userAgentString = UA
            web.settings.defaultTextEncodingName = "utf-8"

            val loaded = CompletableDeferred<Unit>()
            web.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, u: String?) {
                    if (!loaded.isCompleted) loaded.complete(Unit)
                }
            }
            web.loadUrl(url)
            val ok = withTimeoutOrNull(TIMEOUT_MS) { loaded.await() } != null
            if (!ok) return@withContext null
            // SPA 首屏之后还会补数据：给一小段沉降时间再取文本
            delay(700)
            val raw = suspendCancellableCoroutine<String?> { cont ->
                runCatching {
                    web.evaluateJavascript(
                        "(function(){var b=document.body;return b?b.innerText:''})()"
                    ) { r -> if (cont.isActive) cont.resume(unquote(r)) }
                }.onFailure { if (cont.isActive) cont.resume(null) }
            }
            raw?.trim()?.takeIf { it.length >= 40 }
        } catch (t: Throwable) {
            null
        } finally {
            runCatching { web.stopLoading(); web.destroy() }
        }
    }

    /** evaluateJavascript 回的是 JSON 字面量（带引号与转义），解回原始文本 */
    private fun unquote(jsResult: String?): String? {
        if (jsResult == null || jsResult == "null") return null
        return runCatching { JSONTokener(jsResult).nextValue() as? String }.getOrNull()
    }
}
