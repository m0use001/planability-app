package io.github.m0use001.planability.data

import io.github.m0use001.planability.core.AppTime

// M5 KMP 化：ChatSession/ChatMessage 实体与 ChatDao 已迁 `:shared`（Room 真源），同包跨模块直解。
// 本文件只留 ChatRepo（Graph 单例 / 项目目录搬迁 ProjectDirs / AppTime）。

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
