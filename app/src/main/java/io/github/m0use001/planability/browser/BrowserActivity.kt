package io.github.m0use001.planability.browser

import android.annotation.SuppressLint
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.launch
import io.github.m0use001.planability.data.Graph
import io.github.m0use001.planability.ui.theme.PlanabilityTheme

/**
 * 应用内浏览器窗口（v43 浏览器自动化线的承载页）。
 *
 * AI 通过 browser_* 工具驱动这个窗口；用户能实时看到它在点什么——
 * 这是刻意的：网页操作要可观察、可打断（✕ / 返回键即可让会话失效）。
 *
 * ## 2026-11-04 用户反馈整改：从"裸全屏 WebView"改成**底部面板 + 引导顶栏**
 * 原来同意 browser_open 确认卡的瞬间，整个屏幕"啪"地换成网页，什么说明都没有——
 * 用户不知道这是 AI 弹的、也不知道怎么回去（体感像被劫持）。现在：
 * - 窗口只占底部 86%（translucent 主题 + BOTTOM gravity + 压暗），背后还能看到对话页，
 *   一眼知道"这是 App 里弹出来的层，不是整个 App 变成了浏览器"；
 * - 顶栏写明「AI 正在这个窗口读取/操作网页」，✕ 一键关闭（对话不受影响）；
 * - 细进度条：AI 在等页面加载时用户不会以为它卡死了。
 *
 * 登录态：CookieManager 默认持久化，用户在这里手动登录一次，后续步骤复用。
 */
class BrowserActivity : ComponentActivity() {

    private var web: WebView? = null

    /** 页面加载进度 0-100（100 = 完成，进度条收起） */
    private val loadProgress = mutableIntStateOf(100)

    /** 当前页标题（onReceivedTitle 回填，空则显示 URL host） */
    private val pageTitle = mutableStateOf("")

    /**
     * 页面**真正画出来**了吗。不能用 progress==100 判断：onProgressChanged 在页面
     * 真正渲染前就可能报满（视觉验收第 2 轮实锤：t=1.2s 时 progress 已 100、
     * 网页区还是整片纯白——遮罩条件写 progress in 1..99 会提前收起，白屏露出来）。
     * 以 onPageFinished 为准（SPA 场景够用；再不济还有遮罩兜底到内容出现）。
     */
    private val pageReady = mutableStateOf(false)

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 底部面板窗口：只占屏 72%，背后压暗后仍能看到对话页——一眼知道这是"弹出的层"
        window.apply {
            setGravity(Gravity.BOTTOM)
            setLayout(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (resources.displayMetrics.heightPixels * 0.72f).toInt()
            )
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            attributes = attributes.apply { dimAmount = 0.40f }
        }
        val w = WebView(this)
        w.settings.javaScriptEnabled = true
        w.settings.domStorageEnabled = true
        w.settings.databaseEnabled = true
        // 保留默认 UA：部分站点对"非浏览器 UA"直接拒绝（本项目地图瓦片踩过同类坑）
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(w, true)
        w.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                pageReady.value = false
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                CookieManager.getInstance().flush()
                pageReady.value = true
                BrowserSession.onPageFinished(url)
            }
        }
        w.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                loadProgress.intValue = newProgress
            }

            override fun onReceivedTitle(view: WebView?, title: String?) {
                pageTitle.value = title.orEmpty()
            }
        }
        web = w
        BrowserSession.attach(w)
        BrowserSession.attachHost(this)
        setContent {
            val settings by Graph.settings.snapshot.collectAsState()
            PlanabilityTheme(config = settings.theme) {
                BrowserPanel(
                    webView = w,
                    progress = loadProgress.intValue,
                    title = pageTitle.value,
                    pageReady = pageReady.value
                )
            }
        }
        intent.getStringExtra(EXTRA_URL)?.takeIf { it.isNotBlank() }?.let { w.loadUrl(it) }
    }

    @Composable
    private fun BrowserPanel(webView: WebView, progress: Int, title: String, pageReady: Boolean) {
        val scope = androidx.compose.runtime.rememberCoroutineScope()
        // 网页双语翻译状态（沉浸式翻译的手机形态，见 BrowserTranslate）
        var translating by remember { mutableStateOf(false) }
        var translated by remember { mutableStateOf(false) }
        var trMsg by remember { mutableStateOf("") }
        val panelColor = MaterialTheme.colorScheme.surfaceContainerHigh
        // 面板进场：上滑 + 淡入（补齐"弹出层"的观感——没有它就是"啪"一下硬切）
        val enterAnim = remember { Animatable(0f) }
        LaunchedEffect(Unit) { enterAnim.animateTo(1f, tween(220, easing = FastOutSlowInEasing)) }
        // 加载中 = 页面还没真正画出来（onPageFinished 前），与 progress 解耦
        val loading = !pageReady
        // **必须用 Surface 而不是裸 Column**：Material3 的 LocalContentColor 默认黑色，
        // 只有 Surface/Scaffold 会把它设成 onSurface。第一轮视觉验收实锤：标题与 ✕
        // 渲染成纯黑 (0,0,0) 压在 (53,55,53) 底上，对比度 1.7:1 几乎不可见。
        Surface(
            color = panelColor,
            contentColor = MaterialTheme.colorScheme.onSurface,
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            modifier = Modifier
                .fillMaxWidth()
                .graphicsLayer {
                    val e = enterAnim.value
                    alpha = 0.4f + 0.6f * e
                    translationY = (1f - e) * 56.dp.toPx()
                }
        ) {
            Column(Modifier.fillMaxWidth()) {
                // ---- 把手 ----
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp)
                ) {
                    Box(
                        Modifier
                            .size(width = 40.dp, height = 4.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
                    )
                }
                // ---- 顶栏：✕（命中区 48dp）+ 标题 / 引导句 ----
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 4.dp, end = 12.dp, top = 8.dp, bottom = 10.dp)
                ) {
                    IconButton(onClick = { finish() }, modifier = Modifier.size(48.dp)) {
                        Icon(
                            Icons.Rounded.Close,
                            contentDescription = "关闭并返回对话",
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    IconButton(
                        onClick = {
                            if (translating) return@IconButton
                            if (translated) {
                                // 还原原文
                                translated = false
                                trMsg = ""
                                scope.launch {
                                    runCatching { io.github.m0use001.planability.browser.BrowserSession.eval(BrowserTranslate.clearJs()) }
                                }
                            } else {
                                translating = true
                                trMsg = "翻译中…"
                                scope.launch {
                                    val (n, err) = BrowserTranslate.translatePage(
                                        eval = { js -> io.github.m0use001.planability.browser.BrowserSession.eval(js) },
                                        onProgress = { d, t -> trMsg = "翻译中 " + d + "/" + t }
                                    )
                                    translating = false
                                    if (err != null) {
                                        trMsg = err
                                    } else {
                                        translated = true
                                        trMsg = "已翻译 " + n + " 段"
                                    }
                                }
                            }
                        },
                        modifier = Modifier.size(48.dp)
                    ) {
                        Icon(
                            Icons.Rounded.Translate,
                            contentDescription = if (translated) "还原原文" else "翻译成中文",
                            tint = if (translated) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurface
                        )
                    }
                    Column(Modifier.weight(1f)) {
                        Text(
                            title.ifBlank { "AI 浏览网页" },
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            trMsg.ifBlank { "AI 正在这个窗口读取/操作网页；✕ 关闭后对话不受影响" },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                // 加载进度条（3dp）：进度还没回上来时走不确定态，保证「加载中一定有可见活动」
                AnimatedVisibility(
                    visible = loading,
                    enter = expandVertically() + fadeIn(),
                    exit = shrinkVertically() + fadeOut()
                ) {
                    if (progress in 8..99) {
                        LinearProgressIndicator(
                            progress = { progress / 100f },
                            modifier = Modifier.fillMaxWidth().height(3.dp)
                        )
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(3.dp))
                    }
                }
                // ---- 网页本体 ----
                // 加载期盖一层**面板色遮罩**：WebView 拿到白底 HTML 会先绘出整片白，
                // 只靠 setBackgroundColor 挡不住（第一轮验收实测 loading 图网页区 100% 纯白）。
                Box(Modifier.fillMaxWidth().weight(1f)) {
                    val panelArgb = panelColor.toArgb()
                    AndroidView(
                        factory = { webView.apply {
                            setBackgroundColor(panelArgb)
                            // 关掉"无背景色"优化：某些页面会在合成层补白底
                            setLayerType(android.view.View.LAYER_TYPE_HARDWARE, null)
                        } },
                        update = { it.setBackgroundColor(panelArgb) },
                        modifier = Modifier.fillMaxSize()
                    )
                    androidx.compose.animation.AnimatedVisibility(
                        visible = loading,
                        enter = fadeIn(tween(120)),
                        exit = fadeOut(tween(220))
                    ) {
                        Box(
                            Modifier.fillMaxSize().background(panelColor),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(28.dp),
                                    strokeWidth = 2.5.dp
                                )
                                Spacer(Modifier.height(14.dp))
                                Text(
                                    "正在打开网页…",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
                // 手势横杠避让：面板颜色铺到导航条底下，网页内容不被横杠压住
                Spacer(
                    Modifier
                        .navigationBarsPadding()
                        .fillMaxWidth()
                        .height(2.dp)
                        .background(panelColor)
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent?.getStringExtra(EXTRA_URL)?.takeIf { it.isNotBlank() }?.let {
            loadProgress.intValue = 1
            pageReady.value = false
            web?.loadUrl(it)
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // 旋转不重建（保持会话）；WebView 自己处理重排
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // 返回键 = 关闭面板回对话（可打断 AI 的网页操作，这是刻意设计）
        finish()
    }

    override fun onDestroy() {
        BrowserSession.detach()
        runCatching {
            web?.stopLoading()
            (web?.parent as? ViewGroup)?.removeView(web)
            web?.destroy()
        }
        web = null
        super.onDestroy()
    }

    companion object {
        const val EXTRA_URL = "extra_url"
    }
}
