package io.github.m0use001.planability.ai.mcp

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert
import io.github.m0use001.planability.data.Graph
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import org.json.JSONArray
import org.json.JSONObject

// M5 KMP 化：McpServer 实体 + McpServerDao 已迁 `:shared`（shared/.../ai/mcp/McpServer.kt，
// 同包跨模块直解）。本文件只留 McpRepo / McpClient（OkHttp / Graph，Android 侧）。

object McpRepo {
    fun dao() = Graph.db.mcpServerDao()
    suspend fun all() = dao().all()
    suspend fun save(s: McpServer) = dao().upsert(s)
    suspend fun delete(id: Long) = dao().delete(id)
}

/**
 * v11：最小 MCP 客户端（JSON-RPC over HTTP POST，兼容 Streamable HTTP 与以 POST 应答的 SSE 端点）。
 * 不做会话粘滞（很多轻量 MCP 端点无状态），initialize 握手可选项。
 */
object McpClient {
    private val client by lazy {
        okhttp3.OkHttpClient.Builder()
            .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
            .build()
    }

    private fun post(url: String, headersJson: String, body: JSONObject): JSONObject {
        val b = okhttp3.Request.Builder().url(url).post(
            okhttp3.RequestBody.create("application/json".toMediaType(), body.toString())
        )
        runCatching {
            val h = JSONObject(headersJson.ifBlank { "{}" })
            h.keys().forEach { k -> b.header(k, h.optString(k)) }
        }
        b.header("Accept", "application/json, text/event-stream")
        client.newCall(b.build()).execute().use { resp ->
            val text = resp.body?.string() ?: ""
            if (!resp.isSuccessful) throw IllegalStateException("HTTP " + resp.code + " " + text.take(120))
            // Streamable HTTP 可能回 SSE 帧；取所有 data: 行拼 JSON
            val payload = if (text.contains("data:")) {
                text.lines().filter { it.startsWith("data:") }
                    .joinToString("") { it.removePrefix("data:").trim() }
                    .ifBlank { text }
            } else text
            return JSONObject(payload)
        }
    }

    /** tools/list → [ {name, description, inputSchema} ] */
    suspend fun listTools(server: McpServer): List<Pair<String, String>> = withContext(Dispatchers.IO) {
        val resp = post(
            server.url, server.headersJson,
            JSONObject().put("jsonrpc", "2.0").put("id", 1)
                .put("method", "tools/list").put("params", JSONObject())
        )
        val arr = resp.optJSONObject("result")?.optJSONArray("tools") ?: JSONArray()
        (0 until arr.length()).map { i ->
            val t = arr.getJSONObject(i)
            t.optString("name") to t.optString("description")
        }
    }

    /** tools/call → 文本结果（兼容 content 数组与纯文本） */
    suspend fun callTool(server: McpServer, tool: String, argsJson: String): String = withContext(Dispatchers.IO) {
        val args = runCatching { JSONObject(argsJson.ifBlank { "{}" }) }.getOrDefault(JSONObject())
        val resp = post(
            server.url, server.headersJson,
            JSONObject().put("jsonrpc", "2.0").put("id", 2)
                .put("method", "tools/call")
                .put("params", JSONObject().put("name", tool).put("arguments", args))
        )
        val result = resp.optJSONObject("result")
        val content = result?.optJSONArray("content")
        if (content != null && content.length() > 0) {
            (0 until content.length()).mapNotNull { i ->
                val c = content.optJSONObject(i) ?: return@mapNotNull null
                if (c.optString("type") == "text") c.optString("text") else "[" + c.optString("type") + "]"
            }.joinToString("\n").ifBlank { "（空结果）" }
        } else result?.toString() ?: resp.toString()
    }
}

/** v11：MCP 动态工具桥接——把启用服务器的 tools/list 注入 function calling，调用按 mcp<id>__<tool> 路由 */
object McpBridge {
    private data class Cached(val at: Long, val tools: List<Pair<String, String>>)
    private val cache = mutableMapOf<Long, Cached>()

    /** 把启用 MCP 服务器的工具追加进 function calling 数组（5 分钟缓存；失败静默跳过） */
    suspend fun appendTools(toolsJson: JSONArray) {
        runCatching {
            McpRepo.dao().enabled().forEach { s ->
                val c = cache[s.id]
                val tools = if (c != null && System.currentTimeMillis() - c.at < 300_000) c.tools
                else runCatching { McpClient.listTools(s).also { cache[s.id] = Cached(System.currentTimeMillis(), it) } }
                    .getOrNull() ?: return@forEach
                tools.forEach { (n, d) ->
                    toolsJson.put(mcpToolSchema(s.id, s.name, n, d))
                }
            }
        }
    }

    /** 工具名 mcp<serverId>__<tool> → (serverId, tool) */
    fun parse(name: String): Pair<Long, String>? =
        Regex("^mcp(\\d+)__(.+)$").find(name)?.let { it.groupValues[1].toLong() to it.groupValues[2] }

    suspend fun call(serverId: Long, tool: String, argsJson: String): String {
        val s = McpRepo.dao().byId(serverId) ?: throw IllegalStateException("MCP 服务器不存在（id=" + serverId + "）")
        return McpClient.callTool(s, tool, argsJson)
    }

    private fun mcpToolSchema(serverId: Long, serverName: String, tool: String, desc: String): JSONObject =
        JSONObject()
            .put("type", "function")
            .put(
                "function", JSONObject()
                    .put("name", "mcp" + serverId + "__" + tool)
                    .put("description", "[MCP·" + serverName + "] " + tool + "：" + desc)
                    .put("parameters", JSONObject().put("type", "object").put("properties", JSONObject()).put("required", JSONArray()))
            )
}
