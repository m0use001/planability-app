package io.github.m0use001.planability.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * v24：抓取分享页原文（ShareLinkImporter 的前置一步）。
 *
 * **已知限制（2026-10-08 实测，必须照实告知用户）**：
 * - `chatgpt.com` 在国内不可达（curl 返回 000/超时），分享链接根本抓不到；
 * - `claude.ai` 可达但分享页要登录态，未登录时多半只返回骨架 HTML；
 * - `chat.deepseek.com` 可达（HTTP 200）。
 *
 * 所以本函数**不做任何"一定能成"的承诺**：失败就把原因原样带回去，
 * 由 UI 提示用户，绝不静默失败。
 */
object ShareFetcher {

    sealed class Fetched {
        data class Got(val html: String, val finalUrl: String) : Fetched()
        data class Bad(val reason: String) : Fetched()
    }

    private const val UA =
        "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/120.0.0.0 Mobile Safari/537.36"

    suspend fun fetch(url: String): Fetched = withContext(Dispatchers.IO) {
        if (url.isBlank()) return@withContext Fetched.Bad("链接为空")
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return@withContext Fetched.Bad("不是 http(s) 链接")
        }
        runCatching {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 12_000
                readTimeout = 20_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", UA)
                setRequestProperty("Accept", "text/html,application/xhtml+xml")
                setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
            }
            conn.connect()
            val code = conn.responseCode
            if (code !in 200..299) {
                conn.disconnect()
                // 302 到登录页是最常见的失败形态，给出人话
                return@withContext Fetched.Bad(
                    when (code) {
                        401, 403 -> "这个页面需要登录才能访问（HTTP $code）。分享方可能没开启「公开可见」。"
                        404 -> "链接不存在或已失效（HTTP 404）。"
                        else -> "抓取失败（HTTP $code）。该平台可能限制访问，或你所在的网络无法连通。"
                    }
                )
            }
            val html = (conn.inputStream.bufferedReader(Charsets.UTF_8)).use { it.readText() }
            val finalUrl = conn.url.toString()
            conn.disconnect()
            if (html.length < 200) {
                Fetched.Bad("页面内容太少（${html.length} 字节），可能是反爬或需要登录。")
            } else {
                Fetched.Got(html, finalUrl)
            }
        }.getOrElse { e ->
            Fetched.Bad(
                when (e) {
                    is java.net.UnknownHostException -> "域名解析失败 —— 该平台在你的网络下可能不可达。"
                    is java.net.SocketTimeoutException -> "连接超时 —— 可能是网络不通或需要代理。"
                    else -> "抓取出错：${e.message?.take(80) ?: e.javaClass.simpleName}"
                }
            )
        }
    }
}