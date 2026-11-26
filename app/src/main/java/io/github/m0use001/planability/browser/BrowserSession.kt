package io.github.m0use001.planability.browser

import android.content.Intent
import android.webkit.WebView
import io.github.m0use001.planability.data.Graph
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONTokener
import java.lang.ref.WeakReference
import kotlin.coroutines.resume

/**
 * 应用内浏览器的会话单例（v43 浏览器自动化线）。
 *
 * ## 为什么用「自己 Activity + WebView」而不是外部浏览器/Chrome 远程调试
 * - 外部浏览器 AI 控制不到（没有可注入的 JS 上下文）；
 * - Chrome 远程调试需要 adb + 桌面端，手机端自用场景不成立。
 * 自己容器里跑 WebView：DOM 可靠、登录态（Cookie）由用户手动登录一次就持久化，
 * 而且用户能实时看到 AI 在点什么——可控、可打断。
 *
 * ## 生命周期
 * [BrowserActivity] 在前台时 WebView 有效；被系统回收/用户返回后 [web] 变 null，
 * 工具会明确提示「先 browser_open」，不会静默失败。
 */
object BrowserSession {

    private var ref: WeakReference<WebView>? = null

    @Volatile
    private var loadSignal: CompletableDeferred<String?>? = null

    fun attach(w: WebView) {
        ref = WeakReference(w)
    }

    /** 宿主 Activity（关闭窗口用；弱引用，Activity 自己走了不会拖住它） */
    private var hostRef: WeakReference<android.app.Activity>? = null

    /**
     * 最近一次浏览器动作（2026-11-08）：`open/extract/back` = 只读看一眼，
     * `click/fill` = 需要用户参与（登录/表单）。回合结束时按它决定要不要自动关窗。
     */
    @Volatile
    var lastAction: String = ""
        private set

    fun noteAction(action: String) {
        lastAction = action
    }

    fun attachHost(a: android.app.Activity) {
        hostRef = WeakReference(a)
    }

    fun hostAlive(): Boolean = hostRef?.get()?.isFinishing == false

    /** 关闭可见浏览器窗口（AI 用完即走） */
    suspend fun close(): Boolean = withContext(Dispatchers.Main) {
        val a = hostRef?.get()
        lastAction = ""
        if (a == null || a.isFinishing) return@withContext false
        runCatching { a.finish() }.isSuccess
    }

    fun detach() {
        ref?.clear()
        ref = null
        loadSignal = null
        hostRef = null
        lastAction = ""
    }

    fun web(): WebView? = ref?.get()

    fun isActive(): Boolean = web() != null

    /** 页面加载完成回调（BrowserActivity 的 WebViewClient 调） */
    fun onPageFinished(url: String?) {
        val d = loadSignal
        loadSignal = null
        d?.complete(url)
    }

    /**
     * 打开 URL 并等到 onPageFinished（或超时）。
     * @return 实际落地的 URL；null = 超时/启动失败
     */
    suspend fun openAndWait(url: String, timeoutMs: Long = 20_000): String? =
        withContext(Dispatchers.Main) {
            val d = CompletableDeferred<String?>()
            loadSignal = d
            val w = web()
            if (w != null) {
                // 已有窗口：直接换页（singleTask 不会重建 Activity）
                runCatching { w.loadUrl(url) }
            } else {
                val i = Intent(Graph.appContext, BrowserActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra(BrowserActivity.EXTRA_URL, url)
                val started = runCatching { Graph.appContext.startActivity(i) }.isSuccess
                if (!started) {
                    loadSignal = null
                    return@withContext null
                }
            }
            withTimeoutOrNull(timeoutMs) { d.await() }
        }

    /**
     * 执行 JS 并返回解析后的值（字符串/数字/对象/null）。
     * 必须在主线程执行——这里统一调度，调用方（工具层跑在 IO）不用关心。
     */
    suspend fun eval(js: String): Any? = withContext(Dispatchers.Main) {
        val w = web() ?: return@withContext null
        val raw = suspendCancellableCoroutine<String?> { cont ->
            runCatching {
                w.evaluateJavascript(js) { v ->
                    if (cont.isActive) cont.resume(v)
                }
            }.onFailure {
                if (cont.isActive) cont.resume(null)
            }
        } ?: return@withContext null
        runCatching { JSONTokener(raw).nextValue() }.getOrNull()
    }

    /**
     * 当前 URL（同步读，不跑 JS）。
     * 2026-11-11 真机回执 6261 实锤：`web()?.url` 也是 WebView 方法，**必须在主线程读**——
     * 工具层跑在 Default 协程，browser_click/back 结尾取落地 URL 时直接炸
     * 「A WebView method was called on thread 'DefaultDispatcher-worker-5'」。
     * 与 openAndWait/eval 同一纪律：WebView 触点统一在本对象内调主线程，调用方不用关心。
     */
    suspend fun currentUrl(): String? = withContext(Dispatchers.Main) { web()?.url }
}
