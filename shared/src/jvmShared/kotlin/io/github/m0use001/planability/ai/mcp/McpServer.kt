package io.github.m0use001.planability.ai.mcp

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert

// M5：McpServer 实体 + McpServerDao 是 @Database 的注册项，随数据层一起迁 `:shared`
// （Room 真源）。McpRepo / McpClient（OkHttp/Graph）留在 app 同包（McpClient.kt）。

/** v11：MCP 服务器（Streamable HTTP / SSE-over-HTTP） */
@Entity(tableName = "mcp_servers")
data class McpServer(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    /** 端点 URL（MCP Streamable HTTP，POST JSON-RPC） */
    val url: String,
    /** 额外请求头 JSON：{"Authorization":"Bearer xxx"} */
    val headersJson: String = "",
    val enabled: Boolean = true
)

@Dao
interface McpServerDao {
    @Query("SELECT * FROM mcp_servers ORDER BY id")
    suspend fun all(): List<McpServer>

    @Query("SELECT * FROM mcp_servers WHERE enabled = 1")
    suspend fun enabled(): List<McpServer>

    @Query("SELECT * FROM mcp_servers WHERE id = :id")
    suspend fun byId(id: Long): McpServer?

    @Upsert
    suspend fun upsert(s: McpServer)

    @Query("DELETE FROM mcp_servers WHERE id = :id")
    suspend fun delete(id: Long)
}
