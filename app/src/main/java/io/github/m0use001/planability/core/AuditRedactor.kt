package io.github.m0use001.planability.core

import org.json.JSONArray
import org.json.JSONObject

/**
 * v44：工具审计脱敏。
 *
 * ## 为什么要脱敏
 * `tools_audit.args` 是"全留痕"，但里面有用户**输入的内容**与**凭据**：
 * `browser_fill{text:"密码"}`、`fs_write_text{content:"整篇笔记"}`。
 * 留痕的目的是能复盘"谁在什么时候动了哪个路径/命令"，不是把内容再抄一份进库
 * （那还会随整库备份扩散出去）。
 *
 * ## 保留什么
 * - 路径、命令、选择器、URL、查询词：**原样保留**——危险操作要能还原；
 * - 被隐去的值留长度提示 `（隐去 N 字）`：知道"写入了一篇 3000 字的文件"，
 *   但读不到内容。
 *
 * ## 解析不了怎么办
 * 整段隐去、只留长度。宁可审计少一条细节，也不赌"截断到 2000 字就碰不到 key"。
 */
object AuditRedactor {

    private val SECRET_KEYS = setOf(
        "text", "content", "value", "password", "pwd",
        "apikey", "api_key", "token", "secret"
    )

    private const val MAX_LEN = 2000
    private const val MAX_DEPTH = 4

    fun redact(argsJson: String): String {
        val raw = argsJson.trim()
        if (raw.isEmpty()) return ""
        val parsed = runCatching { JSONObject(raw) }.getOrNull()
            ?: return "（无法解析的参数，已隐去 ${raw.length} 字）"
        val masked = runCatching { walk(parsed, 0) }.getOrNull() ?: return "（参数脱敏失败，已隐去 ${raw.length} 字）"
        return masked.toString().take(MAX_LEN)
    }

    private fun walk(obj: JSONObject, depth: Int): JSONObject {
        if (depth >= MAX_DEPTH) return obj
        val out = JSONObject()
        for (k in obj.keys()) {
            val v = obj.opt(k)
            out.put(k, maskValue(k, v, depth))
        }
        return out
    }

    private fun maskValue(key: String, v: Any?, depth: Int): Any? = when {
        v == null || v == JSONObject.NULL -> JSONObject.NULL
        key.lowercase() in SECRET_KEYS -> when (v) {
            // 标记用全角括号而不是 ‹ ›：JSON 序列化会把 U+2000–U+20FF 转成 \uXXXX，
            // 真机 org.json 与单测 JSON-java 行为还会不一致，输出难读
            is String -> "（隐去 ${v.length} 字）"
            // 非字符串的敏感字段（数字/布尔）本身不是秘密，原样留
            else -> v
        }
        v is JSONObject -> walk(v, depth + 1)
        v is JSONArray -> JSONArray().also { arr ->
            for (i in 0 until v.length()) {
                val item = v.opt(i)
                arr.put(
                    when (item) {
                        is JSONObject -> walk(item, depth + 1)
                        is JSONArray -> item
                        else -> item
                    }
                )
            }
        }
        else -> v
    }
}
