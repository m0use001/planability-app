package io.github.m0use001.planability.core

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

/**
 * Vosk 离线语音：模型下载/解压 + SpeechService 实时识别。
 * 模型：vosk-model-small-cn-0.22（约 45MB，中文）。
 */
object VoskManager {

    const val MODEL_URL = "https://alphacephei.com/vosk/models/vosk-model-small-cn-0.22.zip"
    const val MODEL_DIR_NAME = "vosk-model-small-cn-0.22"

    sealed interface State {
        data object Idle : State
        data class Downloading(val progress: Int) : State
        data class Unpacking(val progress: Int) : State
        data object Ready : State
        data object Listening : State
        data class Error(val message: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state

    /** 识别文本（片段实时更新，结束后为累计全文） */
    private val _partial = MutableStateFlow("")
    val partial: StateFlow<String> = _partial

    private var model: Model? = null
    private var speechService: SpeechService? = null

    fun modelDir(context: Context): File = File(context.filesDir, MODEL_DIR_NAME)

    fun isReady(context: Context): Boolean =
        modelDir(context).let { it.exists() && it.isDirectory && File(it, "am").exists() }

    /** 下载并解压模型（IO 线程），进度写入 state */
    suspend fun downloadModel(context: Context) = withContext(Dispatchers.IO) {
        if (isReady(context)) {
            _state.value = State.Ready
            return@withContext
        }
        val zip = File(context.filesDir, "$MODEL_DIR_NAME.zip")
        try {
            _state.value = State.Downloading(0)
            val conn = (URL(MODEL_URL).openConnection() as HttpURLConnection).apply {
                connectTimeout = 20_000
                readTimeout = 60_000
                instanceFollowRedirects = true
            }
            val total = conn.contentLengthLong
            conn.inputStream.use { input ->
                zip.outputStream().use { output ->
                    val buf = ByteArray(64 * 1024)
                    var read: Int
                    var done = 0L
                    while (input.read(buf).also { read = it } > 0) {
                        output.write(buf, 0, read)
                        done += read
                        if (total > 0) {
                            _state.value = State.Downloading((done * 100 / total).toInt())
                        }
                    }
                }
            }

            _state.value = State.Unpacking(0)
            val target = modelDir(context)
            target.deleteRecursively()
            target.mkdirs()
            ZipInputStream(zip.inputStream().buffered()).use { zis ->
                val rootPrefix = "$MODEL_DIR_NAME/"
                var entry = zis.nextEntry
                var count = 0
                while (entry != null) {
                    val name = entry.name
                    if (!entry.isDirectory && name.startsWith(rootPrefix)) {
                        val out = File(target, name.removePrefix(rootPrefix))
                        out.parentFile?.mkdirs()
                        out.outputStream().use { zis.copyTo(it) }
                        count++
                        if (count % 50 == 0) _state.value = State.Unpacking((count % 500) / 5)
                    }
                    entry = zis.nextEntry
                }
            }
            zip.delete()
            model = null // 重新加载
            _state.value = if (isReady(context)) State.Ready
            else State.Error("模型解压后不完整，请删除后重试")
        } catch (e: Exception) {
            zip.delete()
            _state.value = State.Error("下载失败：${e.message ?: "网络错误"}")
        }
    }

    fun deleteModel(context: Context) {
        stop()
        modelDir(context).deleteRecursively()
        model = null
        _state.value = State.Idle
    }

    /** 开始监听麦克风；识别片段实时写入 partial */
    fun start(context: Context) {
        if (speechService != null) return
        if (!isReady(context)) {
            _state.value = State.Error("语音模型未就绪，请先在「模型管理」下载")
            return
        }
        try {
            val m = model ?: Model(modelDir(context).absolutePath).also { model = it }
            val rec = Recognizer(m, 16000.0f)
            _partial.value = ""
            _state.value = State.Listening
            speechService = SpeechService(rec, 16000.0f).also { svc ->
                svc.startListening(object : RecognitionListener {
                    override fun onPartialResult(hypothesis: String) {
                        val p = extract(hypothesis, "partial")
                        if (p.isNotBlank()) _partial.value = p
                    }

                    override fun onResult(hypothesis: String) {
                        val t = extract(hypothesis, "text")
                        if (t.isNotBlank()) _partial.value = t
                    }

                    override fun onFinalResult(hypothesis: String) {
                        val t = extract(hypothesis, "text")
                        if (t.isNotBlank()) _partial.value = t
                    }

                    override fun onError(exception: Exception) {
                        _state.value = State.Error("识别错误：${exception.message}")
                        speechService = null
                    }

                    override fun onTimeout() {
                        _state.value = State.Ready
                        speechService = null
                    }
                })
            }
        } catch (e: Exception) {
            _state.value = State.Error("识别启动失败：${e.message}")
            stop()
        }
    }

    /** 停止录音；partial 保留累计文本 */
    fun stop() {
        runCatching { speechService?.stop() }
        speechService = null
        if (_state.value == State.Listening) {
            _state.value = if (model != null) State.Ready else State.Idle
        }
    }

    private fun extract(json: String, key: String): String =
        runCatching { JSONObject(json).optString(key, "") }.getOrDefault("")
}
