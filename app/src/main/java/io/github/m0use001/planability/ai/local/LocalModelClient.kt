package io.github.m0use001.planability.ai.local

import android.content.Context
import android.content.Intent
import io.github.m0use001.planability.data.Graph
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 主进程侧访问端侧引擎的薄客户端（HTTP → 127.0.0.1:18181）。
 * UI 只通过这里查状态/触发下载/删除/开关引擎，不直接碰 GenieX——它只活在 :localai 进程里。
 *
 * 为什么走 HTTP 而不是 Binder/直接调用：引擎与 UI 故意分进程（原生崩溃隔离），
 * 而引擎本来就对外提供 OpenAI 兼容端点；状态/管理接口挂在同一端口上，零额外 IPC 面。
 */
object LocalModelClient {

    const val KEY_ENGINE = "local.engine"

    private const val BASE = "http://127.0.0.1:18181"

    private val http = OkHttpClient.Builder()
        .connectTimeout(1, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.SECONDS)
        .build()

    data class Status(
        val status: String,
        val ready: Boolean,
        val model: String,
        /** 磁盘上是否已有**当前选中**模型（与"下载事件"无关：进程重启后事件状态是 idle，但文件还在） */
        val downloaded: Boolean,
        /** 目录里已落盘的模型名集合（模型列表逐行状态） */
        val present: Set<String> = emptySet(),
    ) {
        val downloading: Boolean get() = status == "running"
        val pulled: Boolean get() = downloaded || ready || status == "done"
        val failed: Boolean get() = status.startsWith("error") || status.startsWith("pull:")

        /** 给 UI 的一句话状态 */
        val label: String
            get() = when {
                downloading -> "下载中…"
                failed -> "下载失败（检查网络后重试）"
                ready -> "就绪 · 端侧 NPU"
                pulled -> "已下载，首次提问时加载"
                else -> "未下载"
            }
    }

    suspend fun engineEnabled(): Boolean =
        runCatching { Graph.settings.raw(KEY_ENGINE) == "1" }.getOrDefault(false)

    /**
     * 开关端侧引擎：KV 落盘 + 立刻 start/stop 服务。
     * 服务是未导出的（只有本应用能起），从 UI 启动完全合法。
     */
    suspend fun setEngineEnabled(context: Context, on: Boolean) {
        Graph.settings.putRawValue(KEY_ENGINE, if (on) "1" else "0")
        val svc = Intent(context, LocalLlmService::class.java)
        runCatching {
            if (on) context.startService(svc) else context.stopService(svc)
        }
    }

    /** 服务未启动 / 端口不通时返回 null（不回退成"未下载"，两者要分清） */
    suspend fun status(): Status? = withContext(Dispatchers.IO) {
        runCatching {
            val req = Request.Builder().url("$BASE/v1/local/status").build()
            http.newCall(req).execute().use { r ->
                if (!r.isSuccessful) return@withContext null
                val j = JSONObject(r.body?.string().orEmpty())
                val arr = j.optJSONArray("present")
                val present = buildSet {
                    if (arr != null) for (i in 0 until arr.length()) add(arr.optString(i))
                }
                Status(
                    status = j.optString("status"),
                    ready = j.optBoolean("ready"),
                    model = j.optString("model", LocalEngine.DEFAULT_MODEL),
                    downloaded = j.optBoolean("downloaded", false),
                    present = present,
                )
            }
        }.getOrNull()
    }

    /** 触发模型下载（断点续传；382MB 级）。返回是否受理。 */
    suspend fun pull(): Boolean = post("/v1/local/pull")

    /** 删除已下载模型（腾出空间）。返回是否受理。 */
    suspend fun remove(): Boolean = post("/v1/local/remove")

    /**
     * 切换当前模型：先写选择文件（引擎进程重启后按它恢复），再通知运行中的 server 卸下旧实例。
     * 服务没开时只落文件——下次启动自然用新模型。
     */
    suspend fun select(context: Context, name: String) {
        LocalEngine.select(context, name)
        if (engineEnabled()) {
            runCatching { postBody("/v1/local/select", JSONObject().put("model", name).toString()) }
        }
    }

    private suspend fun post(path: String): Boolean = postBody(path, "{}")

    private suspend fun postBody(path: String, json: String): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val req = Request.Builder().url("$BASE$path")
                .post(json.toRequestBody("application/json".toMediaType()))
                .build()
            http.newCall(req).execute().use { it.isSuccessful }
        }.getOrDefault(false)
    }
}
