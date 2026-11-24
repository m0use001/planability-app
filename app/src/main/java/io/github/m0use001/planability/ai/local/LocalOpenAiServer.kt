package io.github.m0use001.planability.ai.local

import android.content.Context
import android.util.Log
import com.geniex.sdk.bean.ChatMessage
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedWriter
import java.io.InputStream
import java.io.OutputStreamWriter
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger

/**
 * 极简 OpenAI 兼容 server（阶段 1 防腐层）——只实现 AiClient 需要的端点：
 *   GET  /v1/models
 *   POST /v1/chat/completions   （stream=true 走 SSE：`data:{chunk}` / `data:[DONE]`，与 AiStream.kt 对齐）
 * 绑 127.0.0.1（network_security_config 已放行 loopback cleartext），进程内无鉴权。
 * 并发：全局互斥——llama.cpp 一次只跑一路，第二路排队等待（worker 槽调用本来低频串行）。
 * 阶段 1 不支持：tool_calls / cancel / /v1/completions（AiClient 当前不发这些）。
 */
object LocalOpenAiServer {
    private const val TAG = "LocalOpenAiServer"
    const val PORT = 18181

    private var serverSocket: ServerSocket? = null
    private var appContext: Context? = null
    private val genMutex = Mutex()
    private val reqIds = AtomicInteger(0)

    // 阶段 1 验收用：模型拉取状态（GET /v1/local/status 可查）
    private val pullStatus = java.util.concurrent.atomic.AtomicReference("idle")

    val running: Boolean get() = serverSocket?.isClosed == false

    fun start(context: Context) {
        if (running) return
        appContext = context.applicationContext
        try {
            val ss = ServerSocket(PORT, 16, InetAddress.getByName("127.0.0.1"))
            serverSocket = ss
            Thread({
                while (running) {
                    runCatching { ss.accept() }.onSuccess { sock ->
                        Thread({ handle(sock) }, "local-oai-conn").start()
                    }.onFailure { if (running) Log.e(TAG, "accept failed", it) }
                }
            }, "local-oai-accept").apply { isDaemon = true; start() }
            Log.i(TAG, "started 127.0.0.1:$PORT pid=" + android.os.Process.myPid())
        } catch (t: Throwable) {
            Log.e(TAG, "start failed", t)
        }
    }

    fun stop() {
        runCatching { serverSocket?.close() }
        serverSocket = null
    }

    // ---------- 请求处理 ----------

    private fun handle(sock: Socket) {
        Log.i(TAG, "conn accepted from " + sock.inetAddress.hostAddress + ":" + sock.port)
        sock.use { s ->
            // 生成可能很长；AiClient 侧 readTimeout=120s，这里放宽到 10min 兜底
            s.soTimeout = 600_000
            val raw = runCatching { s.getInputStream() }.getOrNull() ?: return
            val output = runCatching {
                OutputStreamWriter(s.getOutputStream(), Charsets.UTF_8).buffered()
            }.getOrNull() ?: return
            val requestLine = readAsciiLine(raw) ?: return
            Log.i(TAG, "request line: $requestLine")
            val parts = requestLine.split(" ")
            if (parts.size < 2) return
            val method = parts[0]
            val path = parts[1].substringBefore("?")
            val headers = mutableMapOf<String, String>()
            var headerLines = 0
            while (true) {
                val line = readAsciiLine(raw)
                if (line == null) {
                    Log.w(TAG, "header read EOF after $headerLines lines")
                    return
                }
                if (line.isEmpty()) break
                headerLines++
                val idx = line.indexOf(':')
                if (idx > 0) headers[line.substring(0, idx).trim().lowercase()] =
                    line.substring(idx + 1).trim()
            }
            // Content-Length 是**字节数**：必须按字节读再整体解码 UTF-8。
            // （旧实现按 char 读 → body 含中文时字节数>字符数，永远等不满 → 死锁；
            //  英文 body 字节==字符，所以 curl 测试正常、App 一发中文系统提示词就卡。）
            val n = headers["content-length"]?.toIntOrNull()?.takeIf { it in 1..4_000_000 } ?: 0
            val body = if (n > 0) {
                val bytes = ByteArray(n)
                var off = 0
                while (off < n) {
                    val r = runCatching { raw.read(bytes, off, n - off) }.getOrNull() ?: break
                    if (r < 0) break
                    off += r
                }
                String(bytes, 0, off, Charsets.UTF_8)
            } else ""
            runCatching {
                when {
                    method == "GET" && path == "/v1/models" -> respondJson(output, modelsJson())
                    method == "POST" && path == "/v1/chat/completions" -> handleChat(output, body)
                    method == "GET" && path == "/v1/local/status" -> respondJson(output, currentStatusJson())
                    method == "POST" && path == "/v1/local/pull" -> { handlePull(); respondJson(output, currentStatusJson()) }
                    method == "POST" && path == "/v1/local/remove" -> { handleRemove(); respondJson(output, currentStatusJson()) }
                    method == "POST" && path == "/v1/local/select" -> handleSelect(output, body)                    else -> respondError(output, 404, "not found: $path")
                }
            }.onFailure { Log.e(TAG, "handle $method $path failed", it) }
        }
    }

    /** 逐字节读一行 ASCII（请求行/头）。不用 BufferedReader：它会预读并吃掉 body 首字节。 */
    private fun readAsciiLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = input.read()
            if (b < 0) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) break
            if (b != '\r'.code) sb.append(b.toChar())
            if (sb.length > 16_384) return null
        }
        return sb.toString()
    }

    private fun handleChat(out: BufferedWriter, body: String) {        val ctx = appContext ?: return respondError(out, 503, "engine not started")
        val json = runCatching { JSONObject(body) }
            .getOrElse { return respondError(out, 400, "bad json: ${it.message}") }
        val stream = json.optBoolean("stream", false)
        val maxTokens = json.optInt("max_tokens", 512)
        val model = json.optString("model", LocalEngine.DEFAULT_MODEL)
        // 2026-11-04 部署组合：请求的 model 名若命中本地目录就按名加载那档
        // （worker 槽跑轻量档、压缩槽跑 4B——引擎单实例，按请求名热切）；
        // 未命中目录的名字（如 provider 表里的旧名）回退引擎当前选择，行为同旧版。
        val pick = LocalEngine.CATALOG.firstOrNull { it.pullName == model }
        // compute 可选：gpu/npu/hybrid/cpu——GPU 路 A/B 与 NPU 忙时降级用
        val compute = json.optString("compute", "").takeIf { it.isNotBlank() }
        val msgsArr = json.optJSONArray("messages")
            ?: return respondError(out, 400, "messages required")
        val messages = buildList {
            for (i in 0 until msgsArr.length()) {
                val m = msgsArr.optJSONObject(i) ?: continue
                val content = m.opt("content")
                // OpenAI content 可能是字符串或分段数组；阶段 1 只取纯文本
                val text = when (content) {
                    is JSONArray -> (0 until content.length()).joinToString("") { j ->
                        content.optJSONObject(j)?.optString("text") ?: ""
                    }
                    else -> content?.toString() ?: ""
                }
                add(ChatMessage(m.optString("role", "user"), text))
            }
        }
        if (messages.isEmpty()) return respondError(out, 400, "messages empty")

        val locked = runBlocking { runCatching { genMutex.tryLock() }.getOrDefault(false) }
        if (!locked) return respondError(out, 503, "engine busy (another generation running)")
        val id = "chatcmpl-local-" + reqIds.incrementAndGet()
        val created = System.currentTimeMillis() / 1000
        Log.i(TAG, "chat request id=$id model=$model stream=$stream msgs=${messages.size} maxTokens=$maxTokens")
        try {
            if (!stream) {
                val text = runBlocking {
                    LocalEngine.chat(ctx, messages, maxTokens, null, pick?.pullName, compute)
                }
                respondJson(out, chatResponseJson(id, created, model, text))
            } else {
                out.write(
                    "HTTP/1.1 200 OK\r\n" +
                        "Content-Type: text/event-stream; charset=utf-8\r\n" +
                        "Cache-Control: no-cache\r\nConnection: close\r\n\r\n"
                )
                val text = runBlocking {
                    LocalEngine.chat(ctx, messages, maxTokens, { tok ->
                        out.write("data: " + chunkJson(id, created, model, tok) + "\n\n")
                        out.flush()
                    }, pick?.pullName, compute)
                }
                out.write("data: [DONE]\n\n")
                out.flush()
                Log.i(TAG, "stream done chars=" + text.length)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "chat failed", t)
            if (stream) {
                // SSE 已发头，只能用流内错误兜底后收尾
                runCatching {
                    out.write("data: {\"error\":{\"message\":${JSONObject.quote(t.message ?: "engine error")}}}\n\n")
                    out.write("data: [DONE]\n\n")
                    out.flush()
                }
            } else {
                respondError(out, 503, "engine error: ${t.message}")
            }
        } finally {
            runCatching { genMutex.unlock() }
        }
    }

    // ---------- 阶段 1 验收：模型拉取 ----------

    private fun handlePull() {
        val cur = pullStatus.get()
        // 只拦"正在下载"；"done" 必须允许重新拉——切换模型后要能下新模型，
        // 而 pullStatus 是进程级静态值，不会因为换模型自动复位。
        if (cur == "running") return
        val ctx = appContext ?: return
        pullStatus.set("running")
        Thread({
            runBlocking {
                runCatching { LocalEngine.pullModel(ctx) { msg -> pullStatus.set("pull: $msg") } }
                    .onSuccess { pullStatus.set("done") }
                    .onFailure { pullStatus.set("error: ${it.message}") }
            }
        }, "local-model-pull").start()
    }

    private fun handleRemove() {
        val ctx = appContext ?: return
        Thread({
            runBlocking {
                runCatching { LocalEngine.removeModel(ctx) }
                    .onSuccess { pullStatus.set("idle") }
                    .onFailure { pullStatus.set("error: ${it.message}") }
            }
        }, "local-model-remove").start()
    }

    private fun statusJson(model: String, downloaded: Boolean, present: List<String>): String = JSONObject()
        .put("status", pullStatus.get())
        .put("ready", LocalEngine.isReady())
        .put("downloaded", downloaded)
        .put("present", JSONArray(present))
        .put("model", model)
        .toString()

    /** 当前选中模型 + 该模型是否已落盘（每 2s 被 UI 轮询，getPaths 很轻） */
    private fun currentStatusJson(): String {
        val ctx = appContext
        val model = ctx?.let { LocalEngine.selectedName(it) } ?: LocalEngine.DEFAULT_MODEL
        val present = ctx?.let { presentModels(it) } ?: emptyList()
        return statusJson(model, present.contains(model), present)
    }

    /** 目录里每个模型是否已落盘（列表逐行状态） */
    private fun presentModels(ctx: Context): List<String> =
        LocalEngine.CATALOG.mapNotNull { s ->
            if (runBlocking { LocalEngine.isModelPresent(ctx, s.pullName) }) s.pullName else null
        }

    private fun handleSelect(out: BufferedWriter, body: String) {
        val ctx = appContext ?: return respondError(out, 503, "engine not started")
        val name = runCatching { JSONObject(body).optString("model") }.getOrDefault("")
        if (name.isBlank()) return respondError(out, 400, "model required")
        val spec = LocalEngine.select(ctx, name)
        Log.i(TAG, "select model -> ${spec.pullName} (${spec.runtime})")
        val present = presentModels(ctx)
        respondJson(out, statusJson(spec.pullName, present.contains(spec.pullName), present))
    }

    // ---------- 响应构造 ----------

    private fun chunkJson(id: String, created: Long, model: String, token: String): String =
        JSONObject()
            .put("id", id).put("object", "chat.completion.chunk")
            .put("created", created).put("model", model)
            .put("choices", JSONArray().put(JSONObject().apply {
                put("index", 0)
                put("delta", JSONObject().put("content", token))
                put("finish_reason", JSONObject.NULL)
            }))
            .toString()

    private fun chatResponseJson(id: String, created: Long, model: String, text: String): String =
        JSONObject()
            .put("id", id).put("object", "chat.completion")
            .put("created", created).put("model", model)
            .put("choices", JSONArray().put(JSONObject().apply {
                put("index", 0)
                put("message", JSONObject().put("role", "assistant").put("content", text))
                put("finish_reason", "stop")
            }))
            .toString()

    private fun modelsJson(): String {
        val m = appContext?.let { LocalEngine.selectedName(it) } ?: LocalEngine.DEFAULT_MODEL
        return JSONObject().put("object", "list").put("data", JSONArray().put(JSONObject().apply {
            put("id", m)
            put("object", "model")
            put("owned_by", "qualcomm-geniex")
        })).toString()
    }

    private fun respondJson(out: BufferedWriter, json: String) {
        val bytes = json.toByteArray(Charsets.UTF_8)
        out.write("HTTP/1.1 200 OK\r\nContent-Type: application/json; charset=utf-8\r\n")
        out.write("Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n")
        out.write(json)
        out.flush()
    }

    private fun respondError(out: BufferedWriter, code: Int, msg: String) {
        val body = JSONObject().put("error", JSONObject().put("message", msg).put("type", "server_error"))
            .toString()
        val status = if (code == 404) "404 Not Found" else if (code == 400) "400 Bad Request" else "503 Service Unavailable"
        val bytes = body.toByteArray(Charsets.UTF_8)
        out.write("HTTP/1.1 $status\r\nContent-Type: application/json; charset=utf-8\r\n")
        out.write("Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n")
        out.write(body)
        out.flush()
    }
}
