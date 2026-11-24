package io.github.m0use001.planability.ai.embed

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith

/**
 * v53 诊断（非断言）：在**真机**上试跑向量模型下载，把失败原因完整打出来。
 * 用户实报「向量模型也下载失败了」但对话框里只有笼统文案——这里直接跑底层，
 * 每一步（DNS/HTTPS/重定向/写盘）的真实异常都进 logcat（TAG=WF_DL）。
 */
@RunWith(AndroidJUnit4::class)
class DownloadDiagTest {

    @Test
    fun diagLightDownload() = runBlocking {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val model = EmbeddingModels.ALL.first { it.dim == 512 }
        println("WF_DL: url=" + model.modelUrl)
        // ① 先试最原始的一条 HTTPS GET（不经过 ModelStore），拿到最底层的失败原因
        try {
            val client = okhttp3.OkHttpClient.Builder()
                .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                .build()
            client.newCall(okhttp3.Request.Builder().url(model.modelUrl).build()).execute().use { resp ->
                println("WF_DL: raw HTTP " + resp.code + " len=" + (resp.body?.contentLength() ?: -1))
            }
        } catch (e: Exception) {
            println("WF_DL: raw FAILED " + e.javaClass.name + ": " + e.message)
        }
        // ② 再走 ModelStore 完整下载（含续传/校验/改名），打印最终结果
        // 参数写全：R8 会裁掉只被测试用到的一参默认合成方法（$default → NoSuchMethodError）
        val r = ModelStore.download(
            ctx, model,
            onProgress = { p -> if (p >= 0) println("WF_DL: progress " + (p * 100).toInt() + "%") },
            onLabel = { l -> println("WF_DL: label " + l) }
        )
        println("WF_DL: store result ok=" + r.isSuccess + " err=" + (r.exceptionOrNull()?.let { it.javaClass.name + ": " + it.message } ?: "null"))
        if (r.isSuccess) println("WF_DL: file=" + r.getOrNull()!!.absolutePath + " " + r.getOrNull()!!.length() / 1_048_576 + "MB")
    }
}
