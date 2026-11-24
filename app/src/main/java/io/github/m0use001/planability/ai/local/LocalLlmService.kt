package io.github.m0use001.planability.ai.local

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log

/**
 * 端侧模型 server 的宿主：跑在独立进程 `:localai`——GenieX 原生崩溃不拖垮主进程。
 * KV `local.engine`=1 时由 PlanabilityApp 延迟拉起（缺键=关）。
 * 模型未下载不阻塞启动：引擎在首个请求时懒加载，未就绪返回 503，
 * AiClient 侧表现与「模型没配好」一致。
 */
class LocalLlmService : Service() {

    override fun onCreate() {
        super.onCreate()
        LocalOpenAiServer.start(applicationContext)
        Log.i(TAG, "local OpenAI server up (127.0.0.1:${LocalOpenAiServer.PORT})")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY

    override fun onDestroy() {
        LocalOpenAiServer.stop()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private companion object {
        const val TAG = "LocalLlmService"
    }
}
