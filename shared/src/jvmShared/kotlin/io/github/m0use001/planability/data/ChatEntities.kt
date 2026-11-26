package io.github.m0use001.planability.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Update

/** AI 会话（AI 工作台） */
@Entity(tableName = "chat_sessions")
data class ChatSession(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String = "新对话",
    /** 生成用主模型名（会话创建时的快照） */
    val model: String = "",
    /** 挂载的助手 id（0 = 通用） */
    val assistantId: Long = 0,
    /** 滚动摘要（M2 写回；开场注入） */
    val memoryPrompt: String = "",
    /**
     * v55 会话作者注：一条短注钉在倒数第 [authorNoteDepth] 条消息后（酒馆 Author's Note 同款）。
     * 空 = 无。改章节基调用它，不动智能体卡。
     */
    val authorNote: String = "",
    /** v55 作者注注入深度：钉在倒数第几条消息后（默认 4） */
    val authorNoteDepth: Int = 4,
    /** 摘要推进到的消息序号（M2）。**v39 起只作兼容列保留，新代码不要再读写**（见下行） */
    val lastSummarizeIndex: Int = 0,
    /**
     * 摘要锚点（消息 id）：摘要覆盖到**此 id 为止（含）**，0 = 没摘要过。v39 引入（M6 根治）。
     *
     * 上一行那个计数在删消息后会指向别处（ref-runtime-study §7.2-M5 的静默丢历史），
     * id 锚点删不删消息都稳。读侧/摘要侧的换算统一走 `ChatContext.summarizeStartIndex`。
     */
    val lastSummarizedMessageId: Long = 0,
    /** 游标：清除之前上下文的分界 id（M2） */
    val clearBefore: Long = 0,
    val pinned: Boolean = false,
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
    /** 所属项目（0 = 未分类，v5 项目大体系） */
    val projectId: Long = 0,
    /** v7 归档：归档会话从主分组消失，归入所属项目的「已归档」子组 */
    val archived: Boolean = false,
    /** v8 项目树：所属项目内子文件夹（0 = 直接挂项目根） */
    val folderId: Long = 0,
    /** v8 思考程度：'' 自适应（不发参数）/ off / on；v54 扩力度档 low / high / max（DeepSeek reasoning_effort） */
    val thinking: String = "",
    /** v8 会话级工具权限 JSON：{"allow":[name...],"deny":[name...]}（空串=全跟随全局） */
    val permJson: String = "",
    /**
     * v56（2026-11-14）：会话级「联网」开关——与 [thinking] 构成四象限
     * （思考关/自适应 × 联网开/关）。取值：
     * - `""`（默认）= 跟随全局云能力开关（cloudSearchEnabled），行为与上云前一致；
     * - `"1"` = 本会话强制带服务端联网工具（DeepSeek 端点才生效，别的端点无效）；
     * - `"0"` = 本会话强制不联网。
     * 存字符串而不是 Boolean 是为了与 [thinking] 同口径，也便于将来加「仅读链接」等档位。
     */
    val webSearch: String = ""
)

/** 会话内消息。role: user/assistant/system/tool；status: streaming/done/stopped/error */
@Entity(tableName = "chat_messages", indices = [Index("sessionId")])
data class ChatMessage(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val role: String,
    val content: String = "",
    /** 思考过程（reasoning_content 聚合） */
    val reasoning: String = "",
    val model: String = "",
    val status: String = "done",
    val error: String = "",
    val tokensIn: Int = 0,
    val tokensOut: Int = 0,
    val createdAt: Long = 0,
    /** assistant 消息携带的工具调用 JSON：[{"id","name","args"}]（M4） */
    val toolCallsJson: String = "",
    /** role=tool 消息对应的调用 id（M4） */
    val toolCallId: String = ""
)

@Dao
interface ChatDao {
    @Query("SELECT * FROM chat_sessions ORDER BY pinned DESC, updatedAt DESC")
    suspend fun sessions(): List<ChatSession>

    @Query("SELECT * FROM chat_sessions ORDER BY pinned DESC, updatedAt DESC LIMIT :limit")
    suspend fun recentSessions(limit: Int): List<ChatSession>

    @Query("SELECT * FROM chat_sessions WHERE id = :id")
    suspend fun sessionById(id: Long): ChatSession?

    @Query("SELECT COUNT(*) FROM chat_sessions")
    suspend fun sessionCount(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSession(s: ChatSession): Long

    @Update
    suspend fun updateSession(s: ChatSession)

    @Query("UPDATE chat_sessions SET title = :title, updatedAt = :now WHERE id = :id")
    suspend fun renameSession(id: Long, title: String, now: Long)

    @Query("UPDATE chat_sessions SET pinned = :pinned, updatedAt = :now WHERE id = :id")
    suspend fun setPinned(id: Long, pinned: Boolean, now: Long)

    @Query("UPDATE chat_sessions SET projectId = :projectId, updatedAt = :now WHERE id = :id")
    suspend fun setSessionProject(id: Long, projectId: Long, now: Long)

    @Query("UPDATE chat_sessions SET folderId = :folderId, projectId = :projectId, updatedAt = :now WHERE id = :id")
    suspend fun setSessionFolder(id: Long, projectId: Long, folderId: Long, now: Long)

    @Query("UPDATE chat_sessions SET thinking = :thinking WHERE id = :id")
    suspend fun setSessionThinking(id: Long, thinking: String)

    /** v56 会话级联网开关：'' 跟随全局 / '1' 强制联网 / '0' 强制不联网 */
    @Query("UPDATE chat_sessions SET webSearch = :v WHERE id = :id")
    suspend fun setSessionWebSearch(id: Long, v: String)

    @Query("UPDATE chat_sessions SET permJson = :json WHERE id = :id")
    suspend fun setSessionPerm(id: Long, json: String)

    @Query("UPDATE chat_sessions SET model = :model, updatedAt = :now WHERE id = :id")
    suspend fun setSessionModel(id: Long, model: String, now: Long)

    @Query("UPDATE chat_sessions SET assistantId = :assistantId, updatedAt = :now WHERE id = :id")
    suspend fun setSessionAssistant(id: Long, assistantId: Long, now: Long)

    @Query("UPDATE chat_sessions SET archived = :archived, updatedAt = :now WHERE id = :id")
    suspend fun setArchived(id: Long, archived: Boolean, now: Long)

    @Query("DELETE FROM chat_sessions WHERE id = :id")
    suspend fun deleteSession(id: Long)

    @Query("SELECT * FROM chat_messages WHERE sessionId = :sessionId ORDER BY id")
    suspend fun messagesOf(sessionId: Long): List<ChatMessage>

    /**
     * 全库「含公式」的消息正文（2026-11-21）。
     *
     * 预热原来是**按会话**跑的：切到别的会话、或刚生成的新消息都还没落盘，滑到那里
     * 就是裸 LaTeX（用户「我把所有已经产生的公式都渲染了」）。这条查询服务于
     * 「进程启动后一次性把全库公式渲进磁盘」——已经是磁盘命中的会被 `warmUpFormulas`
     * 直接跳过，所以重跑几乎零成本。
     */
    @Query("SELECT content FROM chat_messages WHERE content LIKE '%\$%'")
    suspend fun allMathContents(): List<String>

    @Query("SELECT * FROM chat_messages WHERE id = :id")
    suspend fun messageById(id: Long): ChatMessage?

    @Insert
    suspend fun insertMessage(m: ChatMessage): Long

    @Update
    suspend fun updateMessage(m: ChatMessage)

    @Query("DELETE FROM chat_messages WHERE id = :id")
    suspend fun deleteMessage(id: Long)

    /** 重生成：删除某条消息及其之后的所有消息 */
    @Query("DELETE FROM chat_messages WHERE sessionId = :sessionId AND id >= :fromId")
    suspend fun deleteFrom(sessionId: Long, fromId: Long)

    @Query("DELETE FROM chat_messages WHERE sessionId = :sessionId")
    suspend fun deleteMessagesOf(sessionId: Long)
}
