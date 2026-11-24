package io.github.m0use001.planability.ui.info

import android.annotation.SuppressLint
import android.util.Base64
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import java.io.File

/**
 * docx 的**本机排版预览**（2026-11-09 起，2026-11-10 改为 JS 注入式实现）。
 *
 * ## 为什么不用自绘快照
 *
 * 原来 docx 只能抽纯文字——标题层级、加粗、表格、图片全丢。这里跑 **mammoth.js**
 * （成熟轮子，BSD-2-Clause，vendored 在 assets）：它把 docx 转成语义 HTML，图片以
 * data URL 内联，WebView 负责排版。
 *
 * ## 为什么用 evaluateJavascript 注入，而不是把 mammoth 内联进 HTML
 *
 * 2026-11-10 设备实测：把 400KB 的 mammoth 内联进 HTML（`<script>`）后，
 * 页面里 `mammoth` 已定义、紧随其后的业务脚本却不执行（同宿主下 5 字节的极小内联
 * 脚本一切正常）——大内联脚本被页面解析吞了。改为：先载入空壳页 → onPageFinished
 * 后用 evaluateJavascript 注入 mammoth → 再执行转换脚本。两条通道分开，互不影响。
 *
 * ## 安全
 *
 * 页面零外部请求：禁 file 访问、禁内容访问、禁网络、禁 DOM 存储；文档内容以 base64
 * 字符串注入，全程不出本进程。
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun DocxHtmlPreview(f: File, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current

    Box(modifier.fillMaxWidth()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { c ->
                WebView(c).apply {
                    settings.javaScriptEnabled = true
                    settings.allowFileAccess = false
                    settings.allowFileAccessFromFileURLs = false
                    settings.allowUniversalAccessFromFileURLs = false
                    settings.blockNetworkLoads = true
                    settings.domStorageEnabled = false
                    settings.allowContentAccess = false
                    setBackgroundColor(android.graphics.Color.WHITE)
                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView?, url: String?) {
                            // 空壳载入完成后注入 mammoth，再跑转换（顺序不能反）
                            val js = runCatching {
                                ctx.assets.open("mammoth.browser.min.js")
                                    .bufferedReader().use { it.readText() }
                            }.getOrNull()
                            val b64 = runCatching {
                                Base64.encodeToString(f.readBytes(), Base64.NO_WRAP)
                            }.getOrNull()
                            if (js == null || b64 == null) {
                                view?.evaluateJavascript(renderErrorScript("无法读取资源"), null)
                                return
                            }
                            view?.evaluateJavascript(js, null)
                            view?.evaluateJavascript(convertScript(b64), null)
                        }
                    }
                    loadDataWithBaseURL(
                        null,
                        "<!DOCTYPE html><html><head><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">" +
                            "<style>body{font-family:system-ui,'Noto Sans CJK SC',sans-serif;margin:14px;color:#1a1a1a;" +
                            "background:#fff;line-height:1.65;font-size:15px}h1,h2,h3{margin:.7em 0 .35em}" +
                            "table{border-collapse:collapse}td,th{border:1px solid #d0d0d0;padding:4px 7px}" +
                            "img{max-width:100%;height:auto}ul,ol{padding-left:1.4em}" +
                            "blockquote{margin:.6em 0;padding-left:.8em;border-left:3px solid #ccc;color:#444}</style>" +
                            "</head><body><div id=\"c\">正在渲染…</div></body></html>",
                        "text/html",
                        "utf-8",
                        null
                    )
                }
            }
        )
    }
}

/**
 * 转换脚本：把 base64 的 docx 交给 mammoth，结果写进 #c，并把状态挂到
 * `window.__previewState`（设备端用例轮询它就够，不依赖窗口回调）。
 */
internal fun convertScript(b64: String): String = """
(function(){
  function b64ToBuf(s){var bin=atob(s),n=bin.length,b=new Uint8Array(n);
    for(var i=0;i<n;i++)b[i]=bin.charCodeAt(i);return b.buffer;}
  if (typeof mammoth === 'undefined') { document.getElementById('c').innerText='预览失败：mammoth 未加载'; window.__previewState='error'; return; }
  try {
    mammoth.convertToHtml({arrayBuffer: b64ToBuf('$b64')}, {includeDefaultStyleMap:true,
      convertImage: mammoth.images.imgElement(function(image){
        return image.read("base64").then(function(d){return {src:"data:"+(image.contentType||"image/png")+";base64,"+d};});
      })}).then(function(r){
        var c=document.getElementById('c');
        if (r.value && r.value.trim().length>0){ c.innerHTML=r.value; window.__previewState='ok'; }
        else { c.innerHTML='<p>（文档没有可见文字，可能是整页截图；AI 读取时会走图片 OCR）</p>'; window.__previewState='empty'; }
      }).catch(function(e){ document.getElementById('c').innerText='预览失败：'+e; window.__previewState='error'; });
  } catch(e) { document.getElementById('c').innerText='预览失败：'+e; window.__previewState='error'; }
})();
"""

internal fun renderErrorScript(msg: String): String =
    "document.getElementById('c').innerText='预览失败：$msg';window.__previewState='error';"
