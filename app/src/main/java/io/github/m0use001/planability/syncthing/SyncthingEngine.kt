package io.github.m0use001.planability.syncthing

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.SecureRandom

/**
 * Syncthing 原生内核引擎：
 * - 内核二进制随 APK 打包（jniLibs/arm64-v8a/libsyncthingnative.so，Syncthing v2 linux-arm64）
 * - 从 nativeLibraryDir 启动子进程，GUI/REST 绑定 127.0.0.1:8384
 * - 通过 REST API（X-API-Key）完成设备配对、文件夹配置、同步状态读取
 * 引擎生命周期跟随应用进程（v1）；配置目录 filesDir/syncthing。
 */
object SyncthingEngine {

    const val GUI_HOST = "127.0.0.1"
    const val GUI_PORT = 8384

    /**
     * 默认同步文件夹 id —— 与电脑端 datasync.ts 的 DATA_FOLDER_ID 同一份契约。
     * 文件夹 id 是**两端共享**的标识：手机界面上把默认 synced 目录加进同步时必须用它，
     * 用时间戳 id 建出来的文件夹永远不可能和电脑端的 planability-data 汇成同一个。
     */
    const val DATA_FOLDER_ID = "planability-data"

    data class State(
        val running: Boolean = false,
        val myId: String = "",
        val error: String? = null,
        val logTail: String = ""
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    private var process: Process? = null
    private var apiKey: String = ""

    fun configDir(context: Context): File = File(context.filesDir, "syncthing").apply { mkdirs() }
    fun logFile(context: Context): File = File(configDir(context), "syncthing.log")

    private fun keyFile(context: Context): File = File(configDir(context), "apikey.txt")

    fun ensureApiKey(context: Context): String {
        val f = keyFile(context)
        if (apiKey.isNotBlank()) return apiKey
        apiKey = if (f.exists()) f.readText().trim() else newKey().also { f.writeText(it) }
        return apiKey
    }

    private fun newKey(): String {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
        val rnd = SecureRandom()
        return buildString { repeat(40) { append(alphabet[rnd.nextInt(alphabet.length)]) } }
    }

    val isRunning: Boolean get() = process?.isAlive == true

    fun start(context: Context) {
        if (isRunning) return
        val cfg = configDir(context)
        val key = ensureApiKey(context)
        val bin = File(context.applicationInfo.nativeLibraryDir, "libsyncthingnative.so")
        if (!bin.exists()) {
            _state.value = State(error = "原生内核缺失（本版本仅含 arm64 内核）")
            return
        }
        try {
            val log = logFile(context)
            val pb = ProcessBuilder(
                bin.absolutePath,
                "serve",
                "--home", cfg.absolutePath,
                "--gui-address", "$GUI_HOST:$GUI_PORT",
                "--gui-apikey", key,
                "--no-browser",
                "--log-file", log.absolutePath
            )
            pb.environment()["HOME"] = cfg.absolutePath
            pb.environment()["STNOUPGRADE"] = "1"
            pb.redirectErrorStream(true)
            process = pb.start()
            _state.value = State(running = true)

            // 排空 stdout 到日志文件，避免管道堵塞
            val proc = process!!
            Thread {
                try {
                    proc.inputStream.use { input ->
                        log.outputStream().use { output -> input.copyTo(output) }
                    }
                } catch (_: Exception) {
                }
            }.start()

            CoroutineScope(Dispatchers.IO).launch {
                var myId = ""
                repeat(90) { // 最多 45 秒等待内核就绪
                    if (process?.isAlive != true) return@launch
                    runCatching {
                        val status = restNonSuspend(context, "GET", "/rest/system/status")
                        myId = JSONObject(status).optString("myID")
                    }
                    if (myId.isNotBlank()) {
                        _state.value = State(running = true, myId = myId)
                        return@launch
                    }
                    Thread.sleep(500)
                }
                if (myId.isBlank()) {
                    _state.value = State(running = process?.isAlive == true, error = "内核启动超时，可查看日志")
                }
            }
        } catch (e: Exception) {
            _state.value = State(error = "启动失败：${e.message}")
        }
    }

    fun stop() {
        runCatching { process?.destroy() }
        process = null
        _state.value = State()
    }

    // ---------- REST ----------

    private fun restNonSuspend(context: Context, method: String, path: String, body: String? = null): String {
        val key = ensureApiKey(context)
        val conn = (URL("http://$GUI_HOST:$GUI_PORT$path").openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 5000
            readTimeout = 15000
            setRequestProperty("X-API-Key", key)
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }
        }
        if (body != null) conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        val code = conn.responseCode
        val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
            ?.bufferedReader()?.use { it.readText() } ?: ""
        if (code !in 200..299) throw Exception("REST $code：${text.take(200)}")
        return text
    }

    suspend fun rest(context: Context, method: String, path: String, body: String? = null): String =
        withContext(Dispatchers.IO) { restNonSuspend(context, method, path, body) }

    suspend fun configFolders(context: Context): String = rest(context, "GET", "/rest/config/folders")
    suspend fun configDevices(context: Context): String = rest(context, "GET", "/rest/config/devices")

    suspend fun addDevice(context: Context, deviceId: String, name: String) {
        val body = JSONObject()
            .put("deviceID", deviceId.trim())
            .put("name", name.trim())
            .put("compression", "metadata")
            .put("introducer", false)
            .toString()
        rest(context, "PUT", "/rest/config/devices/${deviceId.trim()}", body)
        applyConfig(context)
    }

    suspend fun addFolder(context: Context, id: String, label: String, path: String, deviceIds: List<String>) {
        // 必须是真正的 org.json.JSONArray：JSONObject.put(String, Object) 对不认识的类型会按
        // toString() 序列化成 JSON **字符串**，Syncthing 直接 400（cannot unmarshal string into
        // []FolderDeviceConfiguration）。曾用自定义 JSONArray2 包装类导致每次添加都失败。
        val devices = org.json.JSONArray()
        deviceIds.forEach { devices.put(JSONObject().put("deviceID", it)) }
        val body = JSONObject()
            .put("id", id.trim())
            .put("label", label.trim())
            .put("path", path.trim())
            .put("type", "sendreceive")
            .put("rescanIntervalS", 60)
            .put("fsWatcherEnabled", true)
            .put("devices", devices)
            .toString()
        rest(context, "PUT", "/rest/config/folders/${id.trim()}", body)
        applyConfig(context)
    }

    private fun enc(s: String) = java.net.URLEncoder.encode(s, "UTF-8")

    /** 读单个文件夹的完整配置 —— 编辑对话框的起点 */
    suspend fun getFolder(context: Context, id: String): JSONObject =
        JSONObject(rest(context, "GET", "/rest/config/folders/${enc(id)}"))

    /**
     * 写回单个文件夹配置。
     *
     * **必须传整份对象**：`PUT /rest/config/folders/<id>` 对未给出的字段一律回落默认值，
     * 本地只拼"要改的那几个字段"会把 devices/ignorePatterns 静默清空。
     * 所以所有编辑的固定套路是 GET 整份 → 在内存对象上改 → PUT 回来（本函数）。
     */
    suspend fun putFolder(context: Context, obj: JSONObject) {
        rest(context, "PUT", "/rest/config/folders/${enc(obj.getString("id"))}", obj.toString())
        applyConfig(context)
    }

    /** 暂停/恢复一个文件夹（paused=1 时不同步也不扫描，配置保留） */
    suspend fun setFolderPaused(context: Context, id: String, paused: Boolean) {
        putFolder(context, getFolder(context, id).put("paused", paused))
    }

    private suspend fun applyConfig(context: Context) {
        runCatching { rest(context, "POST", "/rest/system/config?set=1", rest(context, "GET", "/rest/config")) }
    }

    suspend fun connections(context: Context): String = rest(context, "GET", "/rest/system/connections")

    suspend fun dbStatus(context: Context, folderId: String): String =
        rest(context, "GET", "/rest/db?folder=${java.net.URLEncoder.encode(folderId, "UTF-8")}")

    suspend fun systemLogTail(context: Context): String =
        runCatching { logFile(context).readText().takeLast(3000) }.getOrDefault("")
}
