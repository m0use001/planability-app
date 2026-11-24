package io.github.m0use001.planability.ai.ptl

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.BufferedReader
import java.io.InputStreamReader
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 2026-11-14：DeepSeek 服务端工具（deepseek_web_search）在 **Chat Completions SSE** 下的
 * 真实形状探针（模拟器跑，禁真机——它会花钱且用 App 里的真 key）。
 *
 * 为什么需要探针：公告与指南给的是 Messages/Responses 协议的内容块形状
 * （content_block_start / web_search_tool_result / citations_delta），
 * **Chat Completions 的 delta 形状没有原文**。引用解析要照真形状写，先抓再写，
 * 否则就是照文档猜（上次「猜 shape」的教训）。
 *
 * key 从 App 内的 provider 配置取（Graph.aiProviders，SecretBox 已解）——不需外部注入。
 * 输出：把每行 SSE 的顶层键、delta 内键、出现过的块类型汇总进 logcat（tag=DeepSeekProbe），
 * 断言「拿到至少一个 data 行」即可（网络/鉴权问题会红）。
 */
@RunWith(AndroidJUnit4::class)
class DeepSeekToolsProbeTest {

    @Test
    fun probeServerToolStreamShape() {
        val key = runCatching {
            io.github.m0use001.planability.data.Graph.aiProviders
                .configByBaseUrlHint("deepseek")?.apiKey
        }.getOrNull()
        assertTrue("拿不到 DeepSeek key（模型管理页配一下）", !key.isNullOrBlank())

        val body = JSONObject()
            .put("model", "deepseek-flash")
            .put("stream", true)
            .put("messages", org.json.JSONArray().put(
                JSONObject().put("role", "user").put("content", "北京今天天气怎么样")
            ))
            .put("tools", org.json.JSONArray().put(
                JSONObject().put("type", "deepseek_web_search")
            ))
            .toString()

        val req = okhttp3.Request.Builder()
            .url("https://api.deepseek.com/chat/completions")
            .header("Authorization", "Bearer " + key)
            .header("Accept", "text/event-stream")
            .post(body.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()

        val topKeys = linkedSetOf<String>()
        val deltaKeys = linkedSetOf<String>()
        val blockTypes = linkedSetOf<String>()
        val annotations = linkedSetOf<String>()
        val toolCalls = linkedSetOf<String>()
        var dataLines = 0
        var rawSample = StringBuilder()
        val client = okhttp3.OkHttpClient.Builder()
            .connectTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(120, java.util.concurrent.TimeUnit.SECONDS)
            .build()

        client.newCall(req).execute().use { resp ->
            assertTrue("HTTP ${resp.code}", resp.isSuccessful)
            val stream = resp.body?.byteStream() ?: error("no body")
            BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { br ->
                while (true) {
                    val line = br.readLine() ?: break
                    if (!line.startsWith("data:")) continue
                    val payload = line.removePrefix("data:").trim()
                    if (payload == "[DONE]") break
                    dataLines++
                    if (rawSample.length < 4000) rawSample.append(payload).append('\n')
                    runCatching {
                        val o = JSONObject(payload)
                        o.keys().forEach { topKeys.add(it) }
                        o.optJSONArray("choices")?.let { arr ->
                            for (i in 0 until arr.length()) {
                                val d = arr.optJSONObject(i)?.optJSONObject("delta")
                                    ?: arr.optJSONObject(i)?.optJSONObject("message")
                                if (d != null) d.keys().forEach { deltaKeys.add(it) }
                                // 引用与服务端工具调用的**内容形状**（第二段探针的重点）
                                if (d != null) {
                                    if (d.has("annotations")) {
                                        annotations.add(d.opt("annotations").toString())
                                    }
                                    if (d.has("server_tool_calls")) {
                                        toolCalls.add(d.opt("server_tool_calls").toString())
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        android.util.Log.i("DeepSeekProbe", "dataLines=$dataLines")
        android.util.Log.i("DeepSeekProbe", "topKeys=$topKeys")
        android.util.Log.i("DeepSeekProbe", "deltaKeys=$deltaKeys")
        android.util.Log.i("DeepSeekProbe", "blockTypes=$blockTypes")
        android.util.Log.i("DeepSeekProbe", "annotations=" + annotations.take(3))
        android.util.Log.i("DeepSeekProbe", "serverToolCalls=" + toolCalls.take(3))
        android.util.Log.i("DeepSeekProbe", "sample:\n" + rawSample)
        assertTrue("没拿到任何 SSE data 行（网络/鉴权/协议？）", dataLines > 0)
    }
}
