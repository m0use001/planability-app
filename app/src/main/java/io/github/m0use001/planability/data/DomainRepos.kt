package io.github.m0use001.planability.data

import io.github.m0use001.planability.ai.rag.WikiLinks
import io.github.m0use001.planability.core.AppTime
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

class FocusRepo(
    private val dao: FocusSessionDao,
    private val version: MutableVersion
) {
    private fun changed() {
        version.bump()
        Graph.appScope.launch { Graph.achievements.checkAll() }
    }

    suspend fun recordSession(plannedMin: Int, completed: Boolean): Long {
        val now = AppTime.nowMillis()
        val id = dao.insert(
            FocusSession(
                startDay = AppTime.dayKeyOf(now),
                start = now - plannedMin * 60_000L,
                end = now,
                plannedMin = plannedMin,
                completed = completed
            )
        )
        if (completed) {
            runCatching { XpCenter.add(plannedMin / 5L + 5) }
        }
        changed()
        return id
    }

    suspend fun sessionsBetween(from: LocalDate, to: LocalDate): List<FocusSession> =
        dao.between(AppTime.dayKey(from), AppTime.dayKey(to))

    suspend fun totalMinutes(): Int = dao.totalMinutes()
    suspend fun completedCount(): Int = dao.completedCount()
}

class NotesRepo(
    private val db: AppDatabase,
    private val version: MutableVersion
) {
    private val notebookDao = db.notebookDao()
    private val noteDao = db.noteDao()

    private fun changed() {
        version.bump()
        Graph.appScope.launch { Graph.achievements.checkAll() }
    }

    suspend fun notebooks(): List<NotebookEntity> = notebookDao.all()
    suspend fun notebookById(id: Long): NotebookEntity? = notebookDao.byId(id)

    suspend fun createNotebook(name: String, coverStyle: Int, coverHue: Float, labels: String = "", coverText: String = "", coverLayout: Int = 0, coverIcon: Int = 0, coverIcons: String = ""): Long {
        val id = notebookDao.upsert(
            NotebookEntity(name = name, coverStyle = coverStyle, coverHue = coverHue, createdAt = AppTime.nowMillis(), labels = labels, coverText = coverText, coverLayout = coverLayout, coverIcon = coverIcon, coverIcons = coverIcons)
        )
        changed()
        return id
    }

    suspend fun renameNotebook(item: NotebookEntity, name: String, coverStyle: Int, coverHue: Float, labels: String? = null, coverText: String? = null, coverLayout: Int? = null, coverIcon: Int? = null, coverIcons: String? = null) {
        notebookDao.upsert(
            item.copy(
                name = name, coverStyle = coverStyle, coverHue = coverHue,
                labels = labels ?: item.labels,
                coverText = coverText ?: item.coverText,
                coverLayout = coverLayout ?: item.coverLayout,
                coverIcon = coverIcon ?: item.coverIcon,
                coverIcons = coverIcons ?: item.coverIcons
            )
        )
        changed()
    }

    /** 编辑自定义封面预设后，同步更新已应用该预设（text+layout 匹配）的笔记本快照 */
    suspend fun applyCustomCoverEdit(oldText: String, oldLayout: Int, newText: String, newLayout: Int) {
        db.notebookDao().all()
            .filter { it.coverStyle == 90 && it.coverText == oldText && it.coverLayout == oldLayout }
            .forEach { notebookDao.upsert(it.copy(coverText = newText, coverLayout = newLayout)) }
        changed()
    }

    suspend fun deleteNotebook(id: Long) {
        // 笔记本内笔记一并进回收站
        noteDao.ofNotebook(id).forEach { noteDao.trash(it.id, AppTime.nowMillis()) }
        notebookDao.delete(id)
        changed()
    }

    suspend fun notesOf(notebookId: Long): List<NoteEntity> = noteDao.ofNotebook(notebookId)
    suspend fun allNotes(): List<NoteEntity> = noteDao.all()
    suspend fun noteCount(): Int = noteDao.count()
    suspend fun trashedNotes(): List<NoteEntity> = noteDao.trashed()
    suspend fun noteById(id: Long): NoteEntity? = noteDao.byId(id)

    // 2026-11-07 P0-2：列表投影行（不含正文，打开笔记再取全文）
    suspend fun noteRowsOf(notebookId: Long): List<NoteRow> = noteDao.rowsOfNotebook(notebookId)
    suspend fun trashedRows(): List<NoteRow> = noteDao.trashedRows()

    /** 笔记本网格「N 篇 · 最近更新」聚合（替代 allNotes() 全量加载，2026-11-07 P0-2） */
    suspend fun notebookStats(): List<NotebookNoteStat> = noteDao.notebookStats()

    /** 2026-11-07 P0-1：`[[` 补全标题候选（去重、按最近更新序、排除自己与手写） */
    suspend fun linkTitleCandidates(selfId: Long, query: String, limit: Int = 5): List<String> =
        noteDao.distinctTitles(selfId, query.trim(), limit)

    suspend fun createNote(notebookId: Long, title: String, kind: String): Long {
        val now = AppTime.nowMillis()
        val id = noteDao.upsert(
            NoteEntity(notebookId = notebookId, title = title, kind = kind, createdAt = now, updatedAt = now)
        )
        runCatching { XpCenter.add(2) }
        changed()
        return id
    }

    suspend fun saveNote(note: NoteEntity) {
        val now = AppTime.nowMillis()
        val saved = note.copy(updatedAt = now)
        noteDao.update(saved)
        rebuildLinks(note.id)
        changed()
        // v51：语义索引跟着笔记走（模型没下载时 indexOne 是空操作，不启动任何推理）
        Graph.appScope.launch { runCatching { io.github.m0use001.planability.ai.embed.NoteVectors.indexOne(Graph.appContext, saved) } }
    }

    /**
     * v49 知识库：按 `[[标题]]` 重建这篇笔记的出链。
     * 悬空引用（目标还不存在）落 `toId=0 + target`，目标笔记创建时由
     * [resolveDangling]/[rebuildLinks] 回填——「先写链接、后建笔记」也成立。
     */
    suspend fun rebuildLinks(noteId: Long) {
        val note = noteDao.byId(noteId) ?: return
        val kb = db.noteKbDao()
        val refs = WikiLinks.parse(note.content)
        kb.clearLinks(noteId)
        val now = AppTime.nowMillis()
        if (refs.isNotEmpty()) {
            kb.insertLinks(refs.map { ref ->
                val target = kb.byTitle(ref.title)
                NoteLink(
                    fromId = noteId,
                    toId = target?.takeIf { it.id != noteId }?.id ?: 0,
                    target = if (target == null || target.id == noteId) ref.title else "",
                    at = now
                )
            })
        }
        // 顺带把别处指向「我刚改成的这个标题」的悬空引用接上
        runCatching { kb.resolveLinks(note.title, noteId) }
    }

    /** 目标出现后回填全部悬空引用（导入/批量建笔记后跑一次） */
    suspend fun resolveDangling() {
        val kb = db.noteKbDao()
        kb.danglingTargets().forEach { t -> kb.byTitle(t)?.let { n -> kb.resolveLinks(n.title, n.id) } }
    }

    /** 反链：哪些笔记 `[[链接]]` 到了这一篇 */
    suspend fun backlinks(noteId: Long): List<NoteEntity> =
        db.noteKbDao().inLinks(noteId).mapNotNull { noteDao.byId(it.fromId) }.filter { !it.trashed }

    /** 出链：本篇 `[[链接]]` 到的笔记 + 悬空目标标题 */
    suspend fun outLinks(noteId: Long): Pair<List<NoteEntity>, List<String>> {
        val links = db.noteKbDao().outLinks(noteId)
        val notes = links.filter { it.toId > 0 }.mapNotNull { noteDao.byId(it.toId) }.filter { !it.trashed }
        val dangling = links.filter { it.toId == 0L && it.target.isNotBlank() }.map { it.target }
        return notes to dangling
    }

    /**
     * v53.6 第 1 期 b：**提及但未链接**的笔记（正文提到本篇标题、却没写 `[[…]]`）。
     *
     * 与反链互补：反链是"已经建了边"，这里是"人写了名字但没连线"——知识库维护里
     * 最容易漏的一类（Logseq/Obsidian 都有这块）。只做**读**，加链是第 2 期的建议队列。
     *
     * 排除：自己、已链接的、回收站里的、以及**标题太短**（<2 字）的目标（会命中满篇）。
     */
    suspend fun unlinkedMentions(noteId: Long): List<NoteEntity> {
        val target = noteDao.byId(noteId) ?: return emptyList()
        val title = target.title.trim()
        if (title.length < 2) return emptyList()
        val alreadyLinked = db.noteKbDao().inLinks(noteId).map { it.fromId }.toSet() + noteId
        return noteDao.all()
            .asSequence()
            .filter { !it.trashed && it.id !in alreadyLinked && it.kind != "freeform" }
            .filter { WikiLinks.mentions(it.content, title) }
            .sortedByDescending { it.updatedAt }
            .take(30)
            .toList()
    }

    /** 标签（CSV）规范化去空白、去重、保序 */
    companion object {
        fun normalizeTags(raw: String): String =
            raw.split(',', '，', ';', '；').map { it.trim() }.filter { it.isNotEmpty() }
                .distinctBy { it.lowercase() }.joinToString(",")
    }

    /** v12 批E：置顶/取消置顶（不改 updatedAt，避免排序跳动） */
    suspend fun setNotePinned(id: Long, pinned: Boolean) {
        noteDao.byId(id)?.let {
            noteDao.update(it.copy(pinned = pinned))
            changed()
        }
    }

    /** v12 批E：移动笔记到另一笔记本（不改 updatedAt） */
    suspend fun moveNote(id: Long, toNotebookId: Long) {
        noteDao.byId(id)?.let {
            noteDao.update(it.copy(notebookId = toNotebookId))
            changed()
        }
    }

    suspend fun trashNote(id: Long) {
        noteDao.trash(id, AppTime.nowMillis())
        changed()
    }

    suspend fun restoreNote(id: Long) {
        noteDao.restore(id)
        changed()
    }

    suspend fun purgeNote(id: Long) {
        noteDao.delete(id)
        changed()
    }

    suspend fun emptyTrash() {
        noteDao.emptyTrash()
        changed()
    }
}

/** 目标群聊管理（一键总结的采集对象，DB v19） */
class GroupTargetRepo(
    private val dao: GroupTargetDao,
    private val version: MutableVersion
) {
    suspend fun all(): List<GroupTarget> = dao.all()
    suspend fun allEnabled(): List<GroupTarget> = dao.allEnabled()
    suspend fun byId(id: Long): GroupTarget? = dao.byId(id)

    suspend fun save(item: GroupTarget): Long {
        val id = dao.upsert(item)
        version.bump()
        return id
    }

    suspend fun setEnabled(id: Long, enabled: Boolean) {
        val item = dao.byId(id) ?: return
        dao.upsert(item.copy(enabled = enabled))
        version.bump()
    }

    suspend fun delete(id: Long) {
        dao.delete(id)
        version.bump()
    }

    /** 按名字取或建（AI 工具与采集合并用） */
    suspend fun ensure(name: String, platform: Int): GroupTarget {
        dao.byName(name)?.let { return it }
        val item = GroupTarget(name = name, platform = platform, createdAt = io.github.m0use001.planability.core.AppTime.nowMillis())
        val id = dao.upsert(item)
        version.bump()
        return item.copy(id = id)
    }
}

class InfoRepo(
    private val db: AppDatabase,
    private val version: MutableVersion
) {
    private val digestDao = db.chatDigestDao()
    private val pendingDao = db.pendingItemDao()

    private fun changed() {
        version.bump()
        Graph.appScope.launch { Graph.achievements.checkAll() }
    }

    suspend fun digests(): List<ChatDigest> = digestDao.allOrdered()
    suspend fun digestsOf(dayKey: Long): List<ChatDigest> = digestDao.byDate(dayKey)
    suspend fun digestCount(): Int = digestDao.count()

    suspend fun saveDigest(item: ChatDigest): Long {
        val id = digestDao.upsert(item)
        runCatching { XpCenter.add(5) }
        changed()
        return id
    }

    suspend fun updateDigest(item: ChatDigest) {
        digestDao.upsert(item)
        changed()
    }

    suspend fun deleteDigest(id: Long) {
        digestDao.delete(id)
        changed()
    }

    /**
     * 清空全部提纯记录（同时重置各群全量采集标记，下次一键总结重新全量采）
     *
     * 2026-11-09 用户「完整清空残缺采集数据与对应提纯会话等内容」：只清 digests 表会留下
     * 一堆孤儿——提纯会话（20 个「信息提取·X」，库里全是空 assistant 轮）、extract.session
     * 会话映射 KV、capture.full 标记、采集来源的待处理草稿、filesDir/信息提取 落盘目录。
     * 完整重置走 [clearAllCaptureData]。
     */
    suspend fun clearAllDigests() {
        digestDao.clearAll()
        db.settingsDao().clearCaptureFullMarks()
        changed()
    }

    /** 采集来源的草稿（待处理）批量清空；session 来源与手动的保留 */
    suspend fun clearCapturePending() {
        pendingDao.clearCapture()
        changed()
    }

    suspend fun pendingItems(): List<PendingItem> = pendingDao.byStatus("pending")
    suspend fun importedItems(): List<PendingItem> = pendingDao.byStatus("imported")
    suspend fun ignoredItems(): List<PendingItem> = pendingDao.byStatus("ignored")
    suspend fun pendingCount(): Int = pendingDao.pendingCount()
    suspend fun importedCount(): Int = pendingDao.importedCount()

    /** 待处理入库（判重后插入；返回新插入行的 id）。
     *  2026-11-11 用户「待处理审核中一堆重复的」：capture 提纯多轮重插同一批事件、
     *  时间措辞每轮略变（「2026年10月10日 17:00」/「10月10日 17:00前」），
     *  精确相等判重兜不住——判重规则见 [PendingDedup]。重复命中不丢信息：旧行空字段用新抽取补。
     *  2026-11-12 用户「老是漏东西，要不就重复一堆」：判重池扩到**三态**——旧池只有 pending，
     *  已忽略/已入库的不在池里，下一轮提纯抽到同一件事照样插回来（清一次来一次）。
     *  只对 capture 来源生效：AI 工具/手动建的草稿是用户主动动作，不能被历史忽略挡住。 */
    suspend fun addPendingItems(items: List<PendingItem>): List<Long> {
        if (items.isEmpty()) return emptyList()
        val existing = pendingDao.byStatus("pending")
        val pool = if (items.all { it.source == "capture" })
            existing + pendingDao.byStatus("ignored") + pendingDao.byStatus("imported")
        else existing
        val fresh = PendingDedup.freshOf(pool, items)
        var filledAny = false
        existing.forEach { old ->
            val hit = items.firstOrNull { PendingDedup.isDuplicate(old, it) } ?: return@forEach
            val filled = PendingDedup.fillBlanks(old, hit)
            if (filled != old) {
                pendingDao.upsert(filled)
                filledAny = true
            }
        }
        val ids = fresh.map { pendingDao.upsert(it) }
        if (ids.isNotEmpty() || filledAny) changed()
        return ids
    }

    /** 存量去重（2026-11-11 用户「待处理审核中一堆重复的」）：同事件多行保留数据最全的一条，
     *  其余置「已忽略」——不删行，已忽略 tab / undo 可找回。返回被清理的行数。 */
    suspend fun healPendingDuplicates(): Int {
        val plan = PendingDedup.planHeal(pendingDao.byStatus("pending"))
        if (plan.updates.isEmpty() && plan.ignoreIds.isEmpty()) return 0
        plan.updates.forEach { pendingDao.upsert(it) }
        // 积压可上千条：分批 UPDATE（单条 IN 撞旧 SQLite 变量上限 999）
        plan.ignoreIds.chunked(500).forEach { pendingDao.batchMarkStatus(it, "ignored") }
        changed()
        return plan.ignoreIds.size
    }

    suspend fun markPending(id: Long, status: String) {
        val item = pendingDao.byId(id) ?: return
        pendingDao.upsert(item.copy(status = status))
        changed()
    }

    suspend fun updatePending(item: PendingItem) {
        pendingDao.upsert(item)
        changed()
    }

    suspend fun deletePending(id: Long) {
        pendingDao.delete(id)
        changed()
    }

    // v53：助理中枢批量操作
    // 2026-11-12：分批 UPDATE——审核页「全选当前筛选」一次可带上千 id，
    // 单条 IN (:ids) 撞旧 SQLite 变量上限 999（与 healPendingDuplicates 同一处理）
    suspend fun batchMarkPending(ids: List<Long>, status: String) {
        if (ids.isEmpty()) return
        ids.chunked(500).forEach { pendingDao.batchMarkStatus(it, status) }
        changed()
    }

    suspend fun pendingBySource(source: String): List<PendingItem> = pendingDao.pendingBySource(source)
    suspend fun pendingByProject(projectId: Long): List<PendingItem> = pendingDao.pendingByProject(projectId)

    // ---------- 周小结 ----------

    suspend fun saveWeekly(record: WeeklyRecord) {
        db.weeklyRecordDao().insert(record)
        changed()
    }

    suspend fun weeklyRecords(): List<WeeklyRecord> = db.weeklyRecordDao().allOrdered()
}

/** v18.1：每日睡眠记录（OPPO 健康节点采集）。一天一条按 dateEpochDay 覆盖。 */
class SleepRepo(
    private val dao: SleepRecordDao,
    private val version: MutableVersion
) {
    private fun changed() {
        version.bump()
        Graph.appScope.launch { Graph.achievements.checkAll() }
    }

    suspend fun upsert(record: SleepRecord) {
        dao.upsert(record)
        changed()
    }

    suspend fun latest(): SleepRecord? = dao.latest()

    suspend fun last(n: Int): List<SleepRecord> = dao.last(n)

    suspend fun since(since: Long): List<SleepRecord> = dao.since(since)

    suspend fun byDay(epochDay: Long): SleepRecord? = dao.byDay(epochDay)

    suspend fun count(): Int = dao.count()

    /** 采集入库：时长/入睡/醒来分钟 + 原文日期。返回写入的记录。 */
    suspend fun recordCapture(
        dateEpochDay: Long,
        durationMin: Int,
        fallMin: Int,
        wakeMin: Int,
        dateText: String
    ): SleepRecord {
        val rec = SleepRecord(
            dateEpochDay = dateEpochDay,
            durationMin = durationMin,
            fallMin = fallMin,
            wakeMin = wakeMin,
            dateText = dateText,
            createdAt = AppTime.nowMillis()
        )
        dao.upsert(rec)
        changed()
        return rec
    }
}
