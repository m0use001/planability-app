package io.github.m0use001.planability.ai.embed

import android.content.Context
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

/**
 * v52 知识库语义检索：向量模型的存放、下载与「当前生效模型」的选择。
 *
 * 按用户拍板走**按需下载**（模型不进 APK）。两档见 [EmbeddingModels]：
 * 轻量 24MB / 重型约 590MB。
 *
 * 安全：下载源是硬编码常量，但仍逐个校验——仅 https、host 必须在白名单内，
 * 且解析出的 IP 不能是环回/私网/链路本地/通配地址（防止以后把 url 改成可配置项时被指向内网）。
 *
 * 大文件必须**断点续传**：写到 `xxx.part`，重下时带 `Range` 接着传（590MB 断一次不从头来）。
 */
object ModelStore {

    private const val PREFS = "kb_embed"
    private const val KEY_MODEL = "model_id"
    private val ALLOWED_HOSTS = setOf("hf-mirror.com", "huggingface.co")

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .build()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun selectedId(context: Context): String =
        prefs(context).getString(KEY_MODEL, EmbeddingModels.DEFAULT_ID) ?: EmbeddingModels.DEFAULT_ID

    fun selected(context: Context): EmbeddingModel = EmbeddingModels.byId(selectedId(context))

    fun select(context: Context, model: EmbeddingModel) {
        prefs(context).edit().putString(KEY_MODEL, model.id).apply()
    }

    fun dir(context: Context, model: EmbeddingModel): File =
        File(context.filesDir, "embed/" + model.id).apply { mkdirs() }

    fun modelFile(context: Context, model: EmbeddingModel): File = File(dir(context, model), "model.onnx")

    /** 体积达标才算可用：不同源的量化版本略有出入，允许 15% 误差 */
    fun isReady(context: Context, model: EmbeddingModel = selected(context)): Boolean {
        val f = modelFile(context, model)
        return f.exists() && f.length() >= minBytes(model)
    }

    fun sizeMb(context: Context, model: EmbeddingModel): Long = modelFile(context, model).length() / 1_048_576

    fun downloadAt(context: Context, model: EmbeddingModel): Long =
        modelFile(context, model).lastModified().takeIf { it > 0 } ?: 0

    private fun minBytes(model: EmbeddingModel) = (model.approxMb.toLong() * 1_048_576L * 0.85).toLong()

    /**
     * 下载分词器（若有）+ 主模型。
     * [onProgress] 0..1（未知总长 -1）；[onLabel] 给 UI 报当前在下什么。
     */
    suspend fun download(
        context: Context,
        model: EmbeddingModel = selected(context),
        onProgress: (Float) -> Unit = {},
        onLabel: (String) -> Unit = {}
    ): Result<File> {
        return runCatching {
            val dir = dir(context, model)
            model.tokenizerFiles.forEach { (url, name) ->
                val dst = File(dir, name)
                if (!dst.exists() || dst.length() < 1024) {
                    onLabel("分词器 " + name)
                    downloadFile(url, dst, onProgress)   // 失败会带 HTTP 码抛出
                }
            }
            val dst = File(dir, "model.onnx")
            if (dst.exists() && dst.length() >= minBytes(model)) {
                onProgress(1f)
                return@runCatching dst
            }
            onLabel("模型 " + model.approxMb + "MB")
            downloadFile(model.modelUrl, dst, onProgress)
            if (dst.length() < minBytes(model)) {
                error("下载不完整（" + dst.length() / 1_048_576 + "MB / 约 " + model.approxMb + "MB），可重试续传")
            }
            dst
        }
    }

    /**
     * 单文件下载，支持续传：数据写 `dst.part`，成功才改名。
     * 返回 false 表示 HTTP 非 2xx（异常信息由调用方给）。
     */
    /** 单文件下载，支持续传；非 2xx 直接抛（带 HTTP 码），网络异常原样上抛 */
    private fun downloadFile(url: String, dst: File, onProgress: (Float) -> Unit) {
        requireSafeUrl(url)
        val part = File(dst.absolutePath + ".part")
        val have = if (part.exists()) part.length() else 0L
        val builder = Request.Builder().url(url)
        if (have > 0) builder.header("Range", "bytes=" + have + "-")

        client.newCall(builder.build()).execute().use { resp ->
            if (!resp.isSuccessful) error(
                "HTTP " + resp.code + "（" + url.substringBefore("/resolve") + "）；" +
                    if (resp.code == 404) "文件在源上不存在" else "可稍后重试（支持续传）"
            )
            val append = resp.code == 206
            val total = resp.body?.contentLength()?.takeIf { it > 0 } ?: -1L
            if (!append && part.exists()) part.delete()
            val base = if (append) have else 0L
            resp.body?.byteStream()?.use { input ->
                FileOutputStream(part, append).use { os ->
                    val buf = ByteArray(256 * 1024)
                    var read = base
                    var lastPct = -1
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        os.write(buf, 0, n)
                        read += n
                        if (total > 0) {
                            val pct = ((read - base) * 100 / total).toInt()
                            if (pct != lastPct) {
                                lastPct = pct
                                onProgress(pct / 100f)
                            }
                        } else {
                            onProgress(-1f)
                        }
                    }
                }
            }
        }
        if (dst.exists()) dst.delete()
        check(part.renameTo(dst)) { "保存失败（" + dst.name + "）" }
    }

    fun delete(context: Context, model: EmbeddingModel = selected(context)): Boolean {
        dir(context, model).listFiles()?.forEach { it.delete() }
        return dir(context, model).delete()
    }

    /** 只允许 https + 白名单 host；拒绝环回/私网/保留地址 */
    internal fun requireSafeUrl(url: String) {
        val uri = runCatching { java.net.URI(url) }.getOrNull()
            ?: throw IllegalArgumentException("非法下载地址")
        require(uri.scheme == "https") { "下载地址必须是 https" }
        val host = uri.host?.lowercase() ?: throw IllegalArgumentException("下载地址缺少 host")
        require(host in ALLOWED_HOSTS) { "不在允许的下载源白名单：" + host }
        require(host != "localhost" && host != "::1" && !host.startsWith("127.")) { "拒绝本机地址" }
        val ip = runCatching { java.net.InetAddress.getByName(host) }.getOrNull() ?: return
        require(
            !ip.isLoopbackAddress && !ip.isSiteLocalAddress &&
                !ip.isLinkLocalAddress && !ip.isAnyLocalAddress
        ) { "拒绝内网/本机地址：" + host }
    }
}
