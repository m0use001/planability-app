package io.github.m0use001.planability.ai.ptl

import io.github.m0use001.planability.browser.BrowserSession
import io.github.m0use001.planability.core.BrowserJs
import kotlinx.coroutines.delay
import org.json.JSONObject

/**
 * v43：浏览器自动化工具（用户定向「浏览器自动化」线）。
 *
 * 实现拆在这里而不是塞进 ToolExecImpls.execute——那个 when 已逼近 JVM 64KB 上限
 *（文件面与 shell 拆出去时踩过 `Method too large`）。
 *
 * 边界：日常网页操作（打开/点击/填表/取文/后退）；不做多标签、不做反爬对抗。
 * 页面里的登录态由用户在可见窗口里手动建立一次，Cookie 持久化后复用。
 */
internal object BrowserTools {

    suspend fun run(name: String, a: JSONObject): ToolExecResult? {
        when (name) {
            "browser_open" -> {
                val url = a.optString("url").trim()
                if (!url.startsWith("http://") && !url.startsWith("https://")) {
                    return ToolExecResult.fail("只支持 http/https 网址（当前：$url）")
                }
                // 2026-11-08 外链安全前置（与 web_fetch 同一条规则）：模型给的 URL 不可信，
                // 不许把本机服务/内网设备打开给 AI 看
                if (!io.github.m0use001.planability.core.SafeUrl.isAllowed(url)) {
                    return ToolExecResult.fail(
                        "[FETCH_BLOCKED] 该地址指向本机或私有网络，不允许打开。要读公开资料请给公网 http/https 地址。"
                    )
                }
                val landed = BrowserSession.openAndWait(url)
                    ?: return ToolExecResult.fail(
                        "页面加载超时（20 秒）。可能原因：网络慢、站点拒绝应用内 WebView、或页面脚本卡住。" +
                            "窗口已打开，可让用户看一下是否正常显示"
                    )
                delay(800) // 动态页面沉降（SPA 首屏渲染）
                BrowserSession.noteAction("open")
                return summarize(landed, "browser_open")
            }

            "browser_click" -> {
                val sel = a.optString("selector").trim().ifBlank { null }
                val txt = a.optString("text").trim().ifBlank { null }
                if (sel == null && txt == null) return ToolExecResult.fail("selector 与 text 至少给一个")
                if (!BrowserSession.isActive()) return notOpen()
                val r = BrowserSession.eval(BrowserJs.click(sel, txt))?.toString().orEmpty()
                if (r.startsWith("NOT_FOUND")) {
                    return ToolExecResult.fail(
                        "没找到可点击元素（selector=${sel ?: "-"} / text=${txt ?: "-"}）。" +
                            "先用 browser_extract 看正文、或从「可交互元素」里挑一个准确的文字"
                    )
                }
                delay(700) // 点击后可能跳转，等导航起步
                BrowserSession.noteAction("click")
                return summarize(BrowserSession.currentUrl().orEmpty(), "browser_click → $r")
            }

            "browser_fill" -> {
                if (!BrowserSession.isActive()) return notOpen()
                val sel = a.optString("selector").trim()
                if (sel.isEmpty()) return ToolExecResult.fail("selector 不能为空（输入框的 CSS 选择器）")
                val text = a.optString("text")
                val r = BrowserSession.eval(BrowserJs.fill(sel, text))?.toString().orEmpty()
                if (r != "FILLED") {
                    return ToolExecResult.fail("没找到输入框（selector=$sel）。先 browser_extract 确认页面上有哪些输入框")
                }
                BrowserSession.noteAction("fill")
                return ToolExecResult.ok("已填入 $sel（${text.length} 字符）。确认后可 browser_click 提交按钮", "browser_fill")
            }

            "browser_extract" -> {
                if (!BrowserSession.isActive()) return notOpen()
                val sel = a.optString("selector").trim().ifBlank { null }
                val r = BrowserSession.eval(BrowserJs.extract(sel))?.toString()
                if (r == null) return ToolExecResult.fail("取文失败：页面已关闭或脚本异常")
                if (r == "NOT_FOUND") return ToolExecResult.fail("选择器没命中：$sel（先 browser_extract 不带 selector 看整页）")
                // 2026-11-05 分页：网页正文动辄几万字，take(6000) 无续读（summarize 里
                // 「可 browser_extract 取全文」的出口原来也是死的）
                val page = TextWindows.page(r, a.optInt("offset", 0), a.optInt("limit", TextWindows.DEFAULT_LIMIT))
                BrowserSession.noteAction("extract")
                return ToolExecResult.ok(page.render("网页"), "browser_extract " + (sel ?: "整页") + (if (page.hasMore) "（未完）" else ""))
            }

            "browser_back" -> {
                if (!BrowserSession.isActive()) return notOpen()
                val r = BrowserSession.eval(BrowserJs.back())?.toString().orEmpty()
                if (r == "NO_HISTORY") return ToolExecResult.fail("没有可回退的历史（当前就是第一页）")
                delay(700)
                BrowserSession.noteAction("back")
                return summarize(BrowserSession.currentUrl().orEmpty(), "browser_back")
            }

            "browser_close" -> {
                val had = BrowserSession.hostAlive()
                BrowserSession.close()
                if (had) return ToolExecResult.ok("已关闭浏览器窗口", "browser_close")
                return ToolExecResult.ok("浏览器窗口本来就没开着（无头渲染不会开窗）", "browser_close")
            }

            else -> return null
        }
    }

    /** 统一摘要：标题 / URL / 可交互元素 / 正文摘录 */
    private suspend fun summarize(fallbackUrl: String, title: String): ToolExecResult {
        val raw = BrowserSession.eval(BrowserJs.summary())?.toString().orEmpty()
        val o = runCatching { JSONObject(raw) }.getOrNull()
        val sb = StringBuilder()
        sb.append("页面：").append(o?.optString("title").orEmpty().ifBlank { "(无标题)" }).append('\n')
        sb.append("URL：").append(o?.optString("url").orEmpty().ifBlank { fallbackUrl }).append('\n')
        val els = o?.optJSONArray("els")
        if (els != null && els.length() > 0) {
            sb.append("可交互元素（browser_click text=… 点击；也可以给 selector）：\n")
            for (i in 0 until minOf(els.length(), 40)) {
                val e = els.optJSONObject(i) ?: continue
                sb.append("  - [").append(e.optString("tag")).append("] ").append(e.optString("text")).append('\n')
            }
        }
        val text = o?.optString("text").orEmpty()
        if (text.isNotBlank()) {
            sb.append("正文摘录：\n").append(text.take(1500))
            if (text.length > 1500) sb.append("\n…（已截断，可 browser_extract 取全文）")
        }
        return ToolExecResult.ok(sb.toString().take(8000), title)
    }

    private fun notOpen() = ToolExecResult.fail("浏览器未打开：先调 browser_open 打开网页（会弹出可见窗口，登录也在那里由用户完成）")
}
