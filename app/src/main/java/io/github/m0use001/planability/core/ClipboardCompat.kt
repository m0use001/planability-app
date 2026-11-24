package io.github.m0use001.planability.core

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context

/**
 * 剪贴板写入 + **写后读回校验**（2026-11-12 用户「复制点了但实际上没到剪切板」）。
 *
 * 背景：Android 13+/ColorOS 的 ClipboardService 有焦点策略——窗口未获焦点时
 * `setPrimaryClip` **静默拒绝**（logcat 可见 "Denying clipboard access ... application
 * is not in focus"，但用户侧毫无反馈）。会话页是 Dialog 窗口，正是这类拒绝的高发场景：
 * UI 弹「已复制」，用户去别处粘贴却是旧内容。
 *
 * 所以这里写完立刻读回（应用永远可以读自己刚写的那条），不一致就返回人话失败原因，
 * 调用方必须把失败 Toast 出来——宁可显式失败，不让坏结果被当真。
 */
object ClipboardCompat {

    /** 写剪贴板；返回 null = 成功（已读回验证），否则返回给人看的失败原因。 */
    fun copy(ctx: Context, text: String): String? {
        if (text.isEmpty()) return "没有可复制的内容"
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            ?: return "剪贴板服务不可用"
        return try {
            cm.setPrimaryClip(ClipData.newPlainText("text", text))
            // 读回校验：应用读自己刚写的条目不受 Android 10+ 后台读取限制
            val back = runCatching {
                cm.primaryClip?.getItemAt(0)?.coerceToText(ctx)?.toString()
            }.getOrNull()
            if (back == text) null
            else "复制被系统拒绝（窗口未获得焦点或被安全策略拦截），请重试"
        } catch (e: Exception) {
            "复制失败：" + (e.message ?: "未知错误")
        }
    }
}
