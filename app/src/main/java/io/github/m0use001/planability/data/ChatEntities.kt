package io.github.m0use001.planability.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Update
import io.github.m0use001.planability.core.AppTime

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

/** 会话数据仓库：Room 读写。会话级操作 bump dataVersion（信息页计数刷新）。 */
class ChatRepo(private val db: AppDatabase, private val version: MutableVersion) {
    private val dao get() = db.chatDao()

    suspend fun sessions(): List<ChatSession> = dao.sessions()
    suspend fun recentSessions(limit: Int): List<ChatSession> = dao.recentSessions(limit)
    suspend fun sessionCount(): Int = dao.sessionCount()
    suspend fun session(id: Long): ChatSession? = dao.sessionById(id)
    suspend fun messagesOf(sessionId: Long): List<ChatMessage> = dao.messagesOf(sessionId)
    /** 全库含公式的消息正文（2026-11-21 全库公式预热用；见 ChatDao.allMathContents）。 */
    suspend fun allMathContents(): List<String> = dao.allMathContents()
    suspend fun messageById(id: Long): ChatMessage? = dao.messageById(id)

    /**
     * v19.62 严格项目制：**不允许再建 projectId=0 的会话**（「未分类」已在信息界面废除）。
     *
     * 调用方显式传了项目就用它；没传/传 0 时按「唯一项目 → 最近更新的项目」解析；
     * 一个项目都没有就建一个「默认项目」兜底——总比会话建出来却看不见要好。
     * 界面上仍要求用户显式选项目（见 ChatScreen/InfoCenterScreen 的强制选择），
     * 这里只是数据层的最后一道闸，防的是悬浮窗/分享导入这类没有选择界面的路径。
     */
    private suspend fun resolveProjectId(projectId: Long): Long {
        if (projectId > 0L) return projectId
        val ps = Graph.projects.all()
        if (ps.isEmpty()) return Graph.projects.create(name = "默认项目")
        if (ps.size == 1) return ps.first().id
        return ps.maxByOrNull { it.updatedAt }?.id ?: ps.first().id
    }

    suspend fun createSession(
        title: String = "新对话",
        model: String = "",
        assistantId: Long = 0,
        projectId: Long = 0
    ): ChatSession {
        val now = AppTime.nowMillis()
        val pid = resolveProjectId(projectId)
        val s = ChatSession(title = title, model = model, assistantId = assistantId, projectId = pid, createdAt = now, updatedAt = now)
        val id = dao.insertSession(s)
        version.bump()
        // v19.62：**对话本身也要存在于项目文件夹内部**（用户 10-18 要求）。
        // 建会话时就把它的目录建出来（<项目>/<标题__id>/），而不是等第一次写文件才懒创建——
        // 否则文件库里"项目 → 对话"这一层根本不存在，用户看不到自己的对话落在哪。
        // 这条依赖是 data → ai.office，层次上不算漂亮；放这里是因为这是**唯一**的建会话入口
        // （对话页/分享导入/悬浮窗全走它），放三个调用点更容易漏。
        runCatching { io.github.m0use001.planability.ai.office.ExportStore.dirFor(Graph.appContext, id) }
        return s.copy(id = id)
    }

    suspend fun setSessionProject(id: Long, projectId: Long) {
        // v19.62：未分类已废除，0 = 无主会话（界面上没有它的家）。
        // 任何调用方传 0 都收敛到一个真实项目，避免"移出去就看不见了"。
        dao.setSessionProject(id, resolveProjectId(projectId), AppTime.nowMillis())
        version.bump()
    }

    /** v8 项目树：移动到项目内文件夹（folderId=0 挂项目根）；跨项目移动时同时更新 projectId */
    suspend fun setSessionFolder(id: Long, projectId: Long, folderId: Long) {
        // 同上：projectId 必须落到真实项目，folderId=0 才是"项目根"
        dao.setSessionFolder(id, resolveProjectId(projectId), folderId, AppTime.nowMillis())
        version.bump()
    }

    suspend fun setSessionThinking(id: Long, thinking: String) {
        dao.setSessionThinking(id, thinking)
        version.bump()
    }

    /** v56：会话级联网开关（四象限的一半） */
    suspend fun setSessionWebSearch(id: Long, v: String) {
        dao.setSessionWebSearch(id, v)
        version.bump()
    }

    suspend fun setSessionPerm(id: Long, json: String) {
        dao.setSessionPerm(id, json)
        version.bump()
    }

    suspend fun setSessionModel(id: Long, model: String) {
        dao.setSessionModel(id, model, AppTime.nowMillis())
        version.bump()
    }

    /** 会话中途换智能体（2026-10-29 输入区"发送设置"面板） */
    suspend fun setSessionAssistant(id: Long, assistantId: Long) {
        dao.setSessionAssistant(id, assistantId, AppTime.nowMillis())
        version.bump()
    }

    suspend fun setArchived(id: Long, archived: Boolean) {
        val s = dao.sessionById(id)
        dao.setArchived(id, archived, AppTime.nowMillis())
        version.bump()
        // 2026-10-29：归档 ⇄ 取消归档 = 整个会话目录在「当前会话 / 已归档会话」之间搬家。
        // 库是真相、磁盘是产物：搬失败（被占用/权限）只当没搬，不回滚库状态——
        // 列表按库走、文件库按目录走，短暂不一致好过"点了归档却什么都没变"。
        if (s != null) {
            runCatching {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    ProjectDirs.moveSession(s.projectId, id, s.title.ifBlank { "会话" }, archived)
                }
            }
        }
    }

    suspend fun renameSession(id: Long, title: String) {
        dao.renameSession(id, title, AppTime.nowMillis())
        version.bump()
    }

    /** 引擎内部用：直接写回会话行（摘要/游标），不 bump */
    suspend fun updateSessionRaw(s: ChatSession) = dao.updateSession(s)

    suspend fun setPinned(id: Long, pinned: Boolean) {
        dao.setPinned(id, pinned, AppTime.nowMillis())
        version.bump()
    }

    suspend fun deleteSession(id: Long) {
        val s = dao.sessionById(id)
        dao.deleteMessagesOf(id)
        dao.deleteSession(id)
        version.bump()
        // 会话目录跟着删：它的产物本来就是这次对话的，留着只会变成没人认领的孤儿目录
        if (s != null) {
            runCatching {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    ProjectDirs.deleteSession(s.projectId, id)
                }
            }
        }
    }

    // ---- 消息级写入（流式高频更新，不 bump dataVersion）----

    suspend fun insertMessage(m: ChatMessage): Long = dao.insertMessage(m)
    suspend fun updateMessage(m: ChatMessage) = dao.updateMessage(m)
    suspend fun deleteMessage(id: Long) = dao.deleteMessage(id)
    suspend fun deleteFrom(sessionId: Long, fromId: Long) = dao.deleteFrom(sessionId, fromId)

    suspend fun touchSession(id: Long) {
        val s = dao.sessionById(id) ?: return
        dao.updateSession(s.copy(updatedAt = AppTime.nowMillis()))
    }
}
