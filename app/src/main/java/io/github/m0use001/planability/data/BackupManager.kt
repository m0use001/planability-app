package io.github.m0use001.planability.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.io.OutputStream

/** 全量导出/导入：所有 Room 表 → 单个 JSON；zip 包 = data.json + 私有目录文件（PDF/录音/附件）。 */
class BackupManager(private val db: AppDatabase) {

    private val backup = db.backupDao()

    suspend fun exportJson(): String {
        val root = JSONObject()
        root.put("app", "Planability")
        // v3：笔记本封面编号体系变了（素色 0 → -1，新风格从 0 起）。导入端按版本判断要不要归一。
        // 6：chat_sessions / chat_messages / compact_markers 进备份（v39，M8——
        //    之前只恢复 JSON 备份会把会话、消息与滚动摘要整个丢掉）
        // 7：memories + memory_links/history/evidence/scenes/persona 进备份（v45，2026-10-30）
        root.put("version", 7)
        root.put("exportedAt", System.currentTimeMillis())
        val tables = JSONObject()

        tables.put("schedules", JSONArray().apply { db.scheduleDao().all().forEach { put(scheduleJson(it)) } })
        tables.put("todos", JSONArray().apply { db.todoDao().all().forEach { put(todoJson(it)) } })
        tables.put("supervision_checks", JSONArray().apply {
            db.supervisionDao().all().forEach {
                put(JSONObject().put("id", it.id).put("todoId", it.todoId).put("date", it.date)
                    .put("at", it.at))   // v23：打卡时刻，补交判定与完成率扣分都靠它
            }
        })
        tables.put("todo_done_logs", JSONArray().apply {
            backup.allDoneLogs().forEach {
                put(JSONObject().put("id", it.id).put("todoId", it.todoId).put("title", it.title)
                    .put("date", it.date).put("at", it.at))
            }
        })
        // v22：打卡载荷。备份一定要带上 —— 这是用户辛苦记下来的数据，丢了不可恢复
        tables.put("checkin_records", JSONArray().apply {
            backup.allCheckins().forEach { put(checkinJson(it)) }
        })
        // v48：百分比进度流水（同一理由：用户手工记的曲线，丢了不可恢复）
        tables.put("todo_percent_logs", JSONArray().apply {
            backup.allPercentLogs().forEach { put(percentLogJson(it)) }
        })
        tables.put("hour_tasks", JSONArray().apply {
            backup.allHourTasks().forEach {
                put(JSONObject().put("id", it.id).put("date", it.date).put("hour", it.hour)
                    .put("title", it.title).put("done", it.done))
            }
        })
        tables.put("timeline_events", JSONArray().apply {
            backup.allEvents().forEach {
                put(JSONObject().put("id", it.id).put("date", it.date).put("startMin", it.startMin)
                    .put("durMin", it.durMin).put("title", it.title).put("hue", it.hue.toDouble()))
            }
        })
        tables.put("countdowns", JSONArray().apply {
            db.countdownDao().allOrdered().forEach {
                put(JSONObject().put("id", it.id).put("title", it.title).put("target", it.target)
                    .put("repeatYearly", it.repeatYearly).put("note", it.note).put("weekday", it.weekday).put("dayOfMonth", it.dayOfMonth))
            }
        })
        tables.put("categories", JSONArray().apply {
            db.categoryDao().all().forEach {
                put(JSONObject().put("id", it.id).put("name", it.name).put("kind", it.kind)
                    .put("hue", it.hue.toDouble()).put("lat", it.lat).put("lng", it.lng)
                    .put("displayNames", it.displayNames))
            }
        })
        tables.put("focus_sessions", JSONArray().apply {
            db.focusSessionDao().all().forEach {
                put(JSONObject().put("id", it.id).put("startDay", it.startDay).put("start", it.start)
                    .put("end", it.end).put("plannedMin", it.plannedMin).put("completed", it.completed))
            }
        })
        tables.put("notebooks", JSONArray().apply {
            db.notebookDao().all().forEach {
                put(JSONObject().put("id", it.id).put("name", it.name).put("coverStyle", it.coverStyle)
                    .put("coverHue", it.coverHue.toDouble()).put("createdAt", it.createdAt).put("labels", it.labels)
                    .put("coverText", it.coverText).put("coverLayout", it.coverLayout))
            }
        })
        tables.put("notes", JSONArray().apply {
            db.noteDao().allIncludingTrashed().forEach { put(noteJson(it)) }
        })
        tables.put("chat_digests", JSONArray().apply {
            db.chatDigestDao().allOrdered().forEach {
                put(JSONObject().put("id", it.id).put("sourceGroup", it.sourceGroup).put("date", it.date)
                    .put("rawText", it.rawText).put("digest", it.digest).put("summary", it.summary)
                    .put("createdAt", it.createdAt))
            }
        })
        tables.put("chat_targets", JSONArray().apply {
            db.groupTargetDao().all().forEach {
                put(JSONObject().put("id", it.id).put("name", it.name).put("platform", it.platform)
                    .put("enabled", it.enabled).put("createdAt", it.createdAt))
            }
        })
        tables.put("pending_items", JSONArray().apply {
            db.pendingItemDao().all().forEach {
                put(JSONObject().put("id", it.id).put("title", it.title).put("kind", it.kind)
                    .put("timeText", it.timeText).put("place", it.place).put("extra", it.extra)
                    .put("status", it.status).put("createdAt", it.createdAt)
                    .put("source", it.source).put("originProjectId", it.originProjectId)
                    .put("originSessionId", it.originSessionId)
                    .put("suggestedStart", it.suggestedStart).put("suggestedEnd", it.suggestedEnd)
                    .put("confidence", it.confidence).put("payload", it.payload))
            }
        })
        tables.put("weekly_records", JSONArray().apply {
            db.weeklyRecordDao().allOrdered().forEach {
                put(JSONObject().put("id", it.id).put("weekStart", it.weekStart).put("text", it.text)
                    .put("source", it.source).put("createdAt", it.createdAt))
            }
        })
        tables.put("sleep_records", JSONArray().apply {
            backup.allSleepRecords().forEach {
                put(JSONObject().put("id", it.id).put("dateEpochDay", it.dateEpochDay)
                    .put("durationMin", it.durationMin).put("fallMin", it.fallMin).put("wakeMin", it.wakeMin)
                    .put("dateText", it.dateText).put("createdAt", it.createdAt))
            }
        })
        tables.put("settings", JSONArray().apply {
            db.settingsDao().all().forEach { put(JSONObject().put("key", it.key).put("value", it.value)) }
        })
        tables.put("achievements", JSONArray().apply {
            db.achievementDao().all().forEach { put(JSONObject().put("key", it.key).put("unlockedAt", it.unlockedAt)) }
        })
        tables.put("note_audio", JSONArray().apply {
            backup.allNoteAudio().forEach {
                put(JSONObject().put("id", it.id).put("noteId", it.noteId).put("path", it.path)
                    .put("durMs", it.durMs).put("createdAt", it.createdAt))
            }
        })
        tables.put("attachments", JSONArray().apply {
            backup.allAttachments().forEach {
                put(JSONObject().put("id", it.id).put("ownerKind", it.ownerKind).put("ownerId", it.ownerId)
                    .put("name", it.name).put("path", it.path).put("mime", it.mime)
                    .put("size", it.size).put("createdAt", it.createdAt))
            }
        })
        // v39(M8)：AI 会话三件套。导入时先 sessions 再 messages 再 markers（markers 的外键指向 sessions）
        tables.put("chat_sessions", JSONArray().apply { backup.allChatSessions().forEach { put(chatSessionJson(it)) } })
        tables.put("chat_messages", JSONArray().apply { backup.allChatMessages().forEach { put(chatMessageJson(it)) } })
        tables.put("compact_markers", JSONArray().apply { backup.allCompactMarkers().forEach { put(compactMarkerJson(it)) } })
        // v7（2026-10-30）：记忆家族。此前 memories 根本不在 JSON 备份里——只能靠
        // `项目记忆/*.md` 镜像恢复，accessCount/importance/失效时间这些元数据全丢；
        // v45 起还多出证据边、场景、画像三张表，必须一起进备份，否则恢复后记忆与出处对不上。
        tables.put("memories", JSONArray().apply {
            db.memoryDao().allIncludingDeleted().forEach { put(memoryJson(it)) }
        })
        tables.put("memory_links", JSONArray().apply {
            db.memoryDao().allLinks().forEach {
                put(JSONObject().put("fromId", it.fromId).put("toId", it.toId))
            }
        })
        tables.put("memory_history", JSONArray().apply {
            db.memoryDao().allHistory().forEach {
                put(JSONObject().put("id", it.id).put("memoryId", it.memoryId)
                    .put("action", it.action).put("detail", it.detail).put("at", it.at))
            }
        })
        tables.put("memory_evidence", JSONArray().apply {
            db.memoryEvidenceDao().all().forEach { put(memoryEvidenceJson(it)) }
        })
        tables.put("memory_scenes", JSONArray().apply {
            db.memorySceneDao().all().forEach { put(memorySceneJson(it)) }
        })
        tables.put("memory_persona", JSONArray().apply {
            db.memoryPersonaDao().get()?.let { put(memoryPersonaJson(it)) }
        })
        root.put("tables", tables)
        return root.toString(2)
    }

    suspend fun importJson(text: String): Boolean {
        val root = runCatching { JSONObject(text) }.getOrNull() ?: return false
        val tables = root.optJSONObject("tables") ?: return false
        // v3 之前导出的备份里，封面 0 是素色、1..10 是已下线的图案款——这区间整体归一到素色，
        // 否则恢复旧备份会把"素色"变成新风格 0、把已删图案当成新风格。
        val normalizeCovers = root.optInt("version", 1) < 3

        backup.clearSchedules(); backup.clearTodos(); backup.clearSupervisions(); backup.clearCheckins()
        backup.clearPercentLogs()
        backup.clearDoneLogs(); backup.clearHourTasks(); backup.clearEvents()
        backup.clearCountdowns(); backup.clearCategories(); backup.clearFocusSessions()
        backup.clearNotebooks(); backup.clearNotes(); backup.clearChatDigests()
        backup.clearChatTargets()
        backup.clearPendingItems(); backup.clearWeeklyRecords()
        backup.clearSleepRecords()
        backup.clearSettings(); backup.clearAchievements()
        // v39(M8)：会话也要清（备份 = 全量替换，与其它表同语义）。顺序见 BackupDao 的注释
        backup.clearCompactMarkers(); backup.clearChatMessages(); backup.clearChatSessions()

        tables.optJSONArray("sleep_records")?.let { arr ->
            val list = ArrayList<SleepRecord>(arr.length())
            for (i in 0 until arr.length()) list += arr.getJSONObject(i).let { o ->
                SleepRecord(
                    id = o.getLong("id"), dateEpochDay = o.getLong("dateEpochDay"),
                    durationMin = o.optInt("durationMin"), fallMin = o.optInt("fallMin"),
                    wakeMin = o.optInt("wakeMin"), dateText = o.optString("dateText"),
                    createdAt = o.optLong("createdAt")
                )
            }
            backup.insertSleepRecords(list)
        }

        tables.optJSONArray("schedules")?.let { arr ->
            val list = ArrayList<Schedule>(arr.length())
            for (i in 0 until arr.length()) list += arr.getJSONObject(i).let { o ->
                Schedule(
                    id = o.getLong("id"), title = o.getString("title"), start = o.getLong("start"),
                    end = o.getLong("end"), tagId = o.optLong("tagId"), place = o.optString("place"),
                    placePoi = o.optString("placePoi"),
                    note = o.optString("note"), repeatRule = o.optString("repeatRule"),
                    remindMin = o.optInt("remindMin", 15),
                    trashed = o.optBoolean("trashed"), trashedAt = o.optLong("trashedAt"),
                    // v28：老备份没有这两列 → 紧急性 2（中）；重要性从旧 priority 推，
                    // 免得「priority=高」的老备份导进来变成 importance=中
                    urgency = o.optInt("urgency", 2),
                    importance = o.optInt(
                        "importance", PriorityMap.importanceOf(o.optInt("priority", 1))
                    )
                )
            }
            backup.insertSchedules(list)
        }
        tables.optJSONArray("todos")?.let { arr ->
            val list = ArrayList<Todo>(arr.length())
            for (i in 0 until arr.length()) list += arr.getJSONObject(i).let { o ->
                Todo(
                    id = o.getLong("id"), title = o.getString("title"), date = o.getLong("date"),
                    due = o.getLong("due"), priority = o.optInt("priority", 1), tagId = o.optLong("tagId"),
                    urgency = o.optInt("urgency", 2),
                    importance = o.optInt(
                        "importance", PriorityMap.importanceOf(o.optInt("priority", 1))
                    ),
                    folderId = o.optLong("folderId"), done = o.optBoolean("done"), doneAt = o.optLong("doneAt"),
                    repeatType = o.optString("repeatType", "none"), repeatDays = o.optString("repeatDays"),
                    repeatInterval = o.optInt("repeatInterval"), repeatEnd = o.optLong("repeatEnd"),
                    permanent = o.optBoolean("permanent"), supervisionEnabled = o.optBoolean("supervisionEnabled"),
                    note = o.optString("note"), remindMin = o.optInt("remindMin", 15),
                    place = o.optString("place"), placePoi = o.optString("placePoi"),
                    lat = o.optDouble("lat", 0.0), lng = o.optDouble("lng", 0.0),
                    trashed = o.optBoolean("trashed"), trashedAt = o.optLong("trashedAt"),
                    dataRecordEnabled = o.optBoolean("dataRecordEnabled"),
                    dataRecordKind = o.optString("dataRecordKind", ""),
                    dataRecordLabel = o.optString("dataRecordLabel", ""),
                    dataRecordUnit = o.optString("dataRecordUnit", ""),
                    dataRecordOptions = o.optString("dataRecordOptions", ""),
                    percentDone = o.optInt("percentDone", -1)
                )
            }
            backup.insertTodos(list)
        }
        tables.optJSONArray("supervision_checks")?.let { arr ->
            val list = ArrayList<SupervisionCheck>(arr.length())
            for (i in 0 until arr.length()) list += arr.getJSONObject(i).let {
                SupervisionCheck(
                    it.getLong("id"), it.getLong("todoId"), it.getLong("date"),
                    // optLong：老备份没有 at 字段，补 0（按按时处理）
                    it.optLong("at", 0L)
                )
            }
            backup.insertSupervisions(list)
        }
        tables.optJSONArray("checkin_records")?.let { arr ->
            val list = ArrayList<CheckinRecord>(arr.length())
            for (i in 0 until arr.length()) list += arr.getJSONObject(i).let { o ->
                CheckinRecord(
                    id = o.getLong("id"), todoId = o.getLong("todoId"), date = o.getLong("date"),
                    at = o.optLong("at"),
                    kind = o.optString("kind", CheckinKind.TIME),
                    numValue = o.optDouble("numValue", 0.0),
                    unit = o.optString("unit", ""),
                    textValue = o.optString("textValue", ""),
                    tags = o.optString("tags", "")
                )
            }
            backup.insertCheckins(list)
        }
        tables.optJSONArray("todo_percent_logs")?.let { arr ->
            val list = ArrayList<TodoPercentLog>(arr.length())
            for (i in 0 until arr.length()) list += arr.getJSONObject(i).let { o ->
                TodoPercentLog(
                    id = o.getLong("id"), todoId = o.getLong("todoId"), date = o.getLong("date"),
                    percent = o.optInt("percent"), at = o.optLong("at")
                )
            }
            backup.insertPercentLogs(list)
        }
        tables.optJSONArray("todo_done_logs")?.let { arr ->
            val list = ArrayList<TodoDoneLog>(arr.length())
            for (i in 0 until arr.length()) list += arr.getJSONObject(i).let {
                TodoDoneLog(it.getLong("id"), it.getLong("todoId"), it.getString("title"), it.getLong("date"), it.getLong("at"))
            }
            backup.insertDoneLogs(list)
        }
        tables.optJSONArray("hour_tasks")?.let { arr ->
            val list = ArrayList<HourTask>(arr.length())
            for (i in 0 until arr.length()) list += arr.getJSONObject(i).let {
                HourTask(it.getLong("id"), it.getLong("date"), it.getInt("hour"), it.getString("title"), it.optBoolean("done"))
            }
            backup.insertHourTasks(list)
        }
        tables.optJSONArray("timeline_events")?.let { arr ->
            val list = ArrayList<TimelineEvent>(arr.length())
            for (i in 0 until arr.length()) list += arr.getJSONObject(i).let {
                TimelineEvent(it.getLong("id"), it.getLong("date"), it.getInt("startMin"), it.getInt("durMin"),
                    it.getString("title"), it.optDouble("hue", 210.0).toFloat())
            }
            backup.insertEvents(list)
        }
        tables.optJSONArray("countdowns")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                db.countdownDao().upsert(Countdown(o.getLong("id"), o.getString("title"), o.getLong("target"), o.optBoolean("repeatYearly"), o.optString("note"), o.optInt("weekday", -1), o.optInt("dayOfMonth", -1)))
            }
        }
        tables.optJSONArray("categories")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                // 命名参数 + optDouble：老备份里没有 lat/lng（v27 之前），默认 0 = 未绑坐标
                db.categoryDao().upsert(CategoryEntity(
                    id = o.getLong("id"), name = o.getString("name"), kind = o.getInt("kind"),
                    hue = o.optDouble("hue", 210.0).toFloat(),
                    lat = o.optDouble("lat", 0.0), lng = o.optDouble("lng", 0.0),
                    // v44 起：老备份没有 displayNames，默认空 = 无显示地名
                    displayNames = o.optString("displayNames", "")
                ))
            }
        }
        tables.optJSONArray("focus_sessions")?.let { arr ->
            val list = ArrayList<FocusSession>(arr.length())
            for (i in 0 until arr.length()) list += arr.getJSONObject(i).let {
                FocusSession(it.getLong("id"), it.getLong("startDay"), it.getLong("start"), it.getLong("end"), it.getInt("plannedMin"), it.optBoolean("completed"))
            }
            db.focusSessionDao().insertAll(list)
        }
        tables.optJSONArray("notebooks")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val rawStyle = o.optInt("coverStyle")
                db.notebookDao().upsert(NotebookEntity(o.getLong("id"), o.getString("name"), if (normalizeCovers && rawStyle in 0..10) COVER_SOLID else rawStyle, o.optDouble("coverHue", 210.0).toFloat(), o.optLong("createdAt"), o.optString("labels"), o.optString("coverText"), o.optInt("coverLayout", 0)))
            }
        }
        tables.optJSONArray("notes")?.let { arr ->
            val list = ArrayList<NoteEntity>(arr.length())
            for (i in 0 until arr.length()) list += arr.getJSONObject(i).let { o -> noteFromJson(o) }
            db.noteDao().insertAll(list)
        }
        tables.optJSONArray("chat_digests")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                db.chatDigestDao().upsert(ChatDigest(o.getLong("id"), o.getString("sourceGroup"), o.getLong("date"), o.getString("rawText"), o.getString("digest"), o.optString("summary"), o.optLong("createdAt")))
            }
        }
        tables.optJSONArray("chat_targets")?.let { arr ->
            val list = ArrayList<GroupTarget>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                list += GroupTarget(o.getLong("id"), o.getString("name"), o.optInt("platform", 0),
                    o.optBoolean("enabled", true), o.optLong("createdAt"))
            }
            backup.insertChatTargets(list)
        }
        tables.optJSONArray("pending_items")?.let { arr ->
            val list = ArrayList<PendingItem>(arr.length())
            for (i in 0 until arr.length()) list += arr.getJSONObject(i).let {
                PendingItem(it.getLong("id"), it.getString("title"), it.optString("kind", "todo"),
                    it.optString("timeText"), it.optString("place"), it.optString("extra"),
                    it.optString("status", "pending"), it.optLong("createdAt"),
                    it.optString("source", "capture"), it.optLong("originProjectId"),
                    it.optLong("originSessionId"), it.optLong("suggestedStart"),
                    it.optLong("suggestedEnd"), (it.optDouble("confidence", -1.0)).toFloat(),
                    it.optString("payload"))
            }
            db.pendingItemDao().insertAll(list)
        }
        tables.optJSONArray("weekly_records")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                db.weeklyRecordDao().insert(WeeklyRecord(o.getLong("id"), o.getLong("weekStart"), o.getString("text"), o.optString("source", "template"), o.optLong("createdAt")))
            }
        }
        tables.optJSONArray("settings")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                db.settingsDao().put(SettingKV(o.getString("key"), o.getString("value")))
            }
        }
        tables.optJSONArray("achievements")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                db.achievementDao().insert(AchievementRec(o.getString("key"), o.optLong("unlockedAt")))
            }
        }
        tables.optJSONArray("note_audio")?.let { arr ->
            val list = ArrayList<NoteAudio>(arr.length())
            for (i in 0 until arr.length()) list += arr.getJSONObject(i).let {
                NoteAudio(it.getLong("id"), it.getLong("noteId"), it.getString("path"),
                    it.optInt("durMs"), it.optLong("createdAt"))
            }
            backup.insertNoteAudio(list)
        }
        tables.optJSONArray("attachments")?.let { arr ->
            val list = ArrayList<Attachment>(arr.length())
            for (i in 0 until arr.length()) list += arr.getJSONObject(i).let {
                Attachment(it.getLong("id"), it.getString("ownerKind"), it.getLong("ownerId"),
                    it.getString("name"), it.getString("path"), it.optString("mime"),
                    it.optLong("size"), it.optLong("createdAt"))
            }
            backup.insertAttachments(list)
        }
        // v39(M8)：会话三件套。老备份没有这几张表 → optJSONArray 返回 null，整块跳过（不报错）。
        // 顺序必须是 sessions → messages → markers（markers 的外键指向 sessions）
        tables.optJSONArray("chat_sessions")?.let { arr ->
            val list = ArrayList<ChatSession>(arr.length())
            for (i in 0 until arr.length()) list += arr.getJSONObject(i).let { o -> chatSessionFromJson(o) }
            backup.insertChatSessions(list)
        }
        tables.optJSONArray("chat_messages")?.let { arr ->
            val list = ArrayList<ChatMessage>(arr.length())
            for (i in 0 until arr.length()) list += arr.getJSONObject(i).let { o -> chatMessageFromJson(o) }
            backup.insertChatMessages(list)
        }
        tables.optJSONArray("compact_markers")?.let { arr ->
            val list = ArrayList<CompactMarkerEntity>(arr.length())
            for (i in 0 until arr.length()) list += arr.getJSONObject(i).let { o -> compactMarkerFromJson(o) }
            backup.insertCompactMarkers(list)
        }
        // v7：记忆家族。**只在备份真的带 memories 数组时才清空重建**——
        // v6 及更早的备份没有这张表，照清会把用户现有记忆整个抹掉。
        // 顺序：先清子表（evidence/links/history）再清 memories，插入时反过来。
        tables.optJSONArray("memories")?.let { arr ->
            db.memoryDao().clearAllHistory()
            db.memoryDao().clearAllLinks()
            db.memoryEvidenceDao().clearAll()
            db.memoryDao().clearAll()
            val list = ArrayList<MemoryEntity>(arr.length())
            for (i in 0 until arr.length()) list += arr.getJSONObject(i).let { o -> memoryFromJson(o) }
            db.memoryDao().insertAll(list)
            tables.optJSONArray("memory_links")?.let { a ->
                val links = ArrayList<MemoryLink>(a.length())
                for (i in 0 until a.length()) links += a.getJSONObject(i).let {
                    MemoryLink(fromId = it.getLong("fromId"), toId = it.getLong("toId"))
                }
                db.memoryDao().insertAllLinks(links)
            }
            tables.optJSONArray("memory_history")?.let { a ->
                val his = ArrayList<MemoryHistory>(a.length())
                for (i in 0 until a.length()) his += a.getJSONObject(i).let {
                    MemoryHistory(
                        id = it.optLong("id"), memoryId = it.getLong("memoryId"),
                        action = it.optString("action"), detail = it.optString("detail"),
                        at = it.optLong("at")
                    )
                }
                db.memoryDao().insertAllHistory(his)
            }
            tables.optJSONArray("memory_evidence")?.let { a ->
                val ev = ArrayList<MemoryEvidence>(a.length())
                for (i in 0 until a.length()) ev += a.getJSONObject(i).let {
                    MemoryEvidence(
                        memoryId = it.getLong("memoryId"), messageId = it.getLong("messageId"),
                        kind = it.optString("kind", "support"), createdAt = it.optLong("createdAt")
                    )
                }
                db.memoryEvidenceDao().insertAll(ev)
            }
        }
        // 场景与画像是独立资产：不依赖 memories 数组，单独判断
        tables.optJSONArray("memory_scenes")?.let { arr ->
            db.memorySceneDao().clearAll()
            val list = ArrayList<MemoryScene>(arr.length())
            for (i in 0 until arr.length()) list += arr.getJSONObject(i).let { memorySceneFromJson(it) }
            db.memorySceneDao().insertAll(list)
        }
        tables.optJSONArray("memory_persona")?.let { arr ->
            db.memoryPersonaDao().clearAll()
            val list = ArrayList<MemoryPersona>(arr.length())
            for (i in 0 until arr.length()) list += arr.getJSONObject(i).let { memoryPersonaFromJson(it) }
            db.memoryPersonaDao().insertAll(list)
        }
        Graph.settings.load()
        Graph.dataVersion.bump()
        return true
    }

    /**
     * zip 备份包：data.json + files/<pdfs|note_audio|attachments>/<文件名>。
     * 只收白名单子目录，导入时按相对路径还原到私有目录。
     */
    suspend fun exportZip(context: Context, out: OutputStream) {
        val json = exportJson()
        java.util.zip.ZipOutputStream(out).use { zip ->
            zip.putNextEntry(java.util.zip.ZipEntry("data.json"))
            zip.write(json.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            listOf("pdfs", "note_audio", "attachments").forEach { sub ->
                File(context.filesDir, sub).listFiles()?.forEach { f ->
                    if (f.isFile) {
                        zip.putNextEntry(java.util.zip.ZipEntry("files/$sub/${f.name}"))
                        f.inputStream().use { it.copyTo(zip) }
                        zip.closeEntry()
                    }
                }
            }
        }
    }

    /** 导入 zip 包：先还原文件再灌 JSON，返回 false 表示缺 data.json 或格式不对 */
    suspend fun importZip(context: Context, input: InputStream): Boolean {
        var jsonText: String? = null
        val files = mutableListOf<Pair<String, ByteArray>>()
        java.util.zip.ZipInputStream(input).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) {
                    when {
                        entry.name == "data.json" ->
                            jsonText = zip.readBytes().toString(Charsets.UTF_8)
                        entry.name.startsWith("files/") && !entry.name.contains("..") ->
                            files += entry.name.removePrefix("files/") to zip.readBytes()
                    }
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        val text = jsonText ?: return false
        files.forEach { (rel, bytes) ->
            val f = File(context.filesDir, rel)
            f.parentFile?.mkdirs()
            runCatching { f.writeBytes(bytes) }
        }
        return importJson(text)
    }

    private fun scheduleJson(s: Schedule) = JSONObject()
        .put("id", s.id).put("title", s.title).put("start", s.start).put("end", s.end)
        .put("tagId", s.tagId).put("place", s.place).put("placePoi", s.placePoi).put("note", s.note)
        .put("repeatRule", s.repeatRule).put("remindMin", s.remindMin)
        .put("trashed", s.trashed).put("trashedAt", s.trashedAt)
        // v28：紧急性/重要性一起进备份（priority 同时写，老版本导入还能认出高中低）
        .put("urgency", s.urgency).put("importance", s.importance)
        .put("priority", PriorityMap.priorityOf(s.importance))

    private fun todoJson(t: Todo) = JSONObject()
        .put("id", t.id).put("title", t.title).put("date", t.date).put("due", t.due)
        .put("priority", PriorityMap.priorityOf(t.importance)).put("tagId", t.tagId).put("folderId", t.folderId)
        .put("urgency", t.urgency).put("importance", t.importance)
        .put("done", t.done).put("doneAt", t.doneAt).put("repeatType", t.repeatType)
        .put("repeatDays", t.repeatDays).put("repeatInterval", t.repeatInterval).put("repeatEnd", t.repeatEnd)
        .put("permanent", t.permanent).put("supervisionEnabled", t.supervisionEnabled)
        .put("note", t.note).put("remindMin", t.remindMin).put("trashed", t.trashed).put("trashedAt", t.trashedAt)
        // v41：待办也要带地点（含绑定地名与坐标）；旧备份没有这些键，导入时按默认值处理
        .put("place", t.place).put("placePoi", t.placePoi).put("lat", t.lat).put("lng", t.lng)
        // v22：打卡数据记录的设置
        .put("dataRecordEnabled", t.dataRecordEnabled).put("dataRecordKind", t.dataRecordKind)
        .put("dataRecordLabel", t.dataRecordLabel).put("dataRecordUnit", t.dataRecordUnit)
        .put("dataRecordOptions", t.dataRecordOptions)
        // v46：百分比完成（-1=未启用）；旧备份缺键回默认
        .put("percentDone", t.percentDone)

    private fun checkinJson(r: CheckinRecord) = JSONObject()
        .put("id", r.id).put("todoId", r.todoId).put("date", r.date).put("at", r.at)
        .put("kind", r.kind).put("numValue", r.numValue).put("unit", r.unit)
        .put("textValue", r.textValue).put("tags", r.tags)

    private fun percentLogJson(l: TodoPercentLog) = JSONObject()
        .put("id", l.id).put("todoId", l.todoId).put("date", l.date)
        .put("percent", l.percent).put("at", l.at)

    private fun noteJson(n: NoteEntity) = JSONObject()
        .put("id", n.id).put("notebookId", n.notebookId).put("title", n.title).put("kind", n.kind)
        .put("content", n.content).put("inkJson", n.inkJson).put("createdAt", n.createdAt)
        .put("updatedAt", n.updatedAt).put("trashed", n.trashed).put("trashedAt", n.trashedAt)
        // v49 知识库：之前漏了 pinned/projectId（还原后置顶与项目归属丢失），一并补上
        .put("pinned", n.pinned).put("projectId", n.projectId).put("tags", n.tags)

    private fun noteFromJson(o: JSONObject) = NoteEntity(
        id = o.getLong("id"), notebookId = o.getLong("notebookId"), title = o.optString("title"),
        kind = o.optString("kind", "md"), content = o.optString("content"), inkJson = o.optString("inkJson"),
        createdAt = o.optLong("createdAt"), updatedAt = o.optLong("updatedAt"),
        trashed = o.optBoolean("trashed"), trashedAt = o.optLong("trashedAt"),
        pinned = o.optBoolean("pinned", false), projectId = o.optLong("projectId", 0),
        tags = o.optString("tags", "")
    )

    // ---- v39(M8)：AI 会话三件套的 JSON 形状。全字段写显式（与上面各表同风格），
    //      导入端一律 opt*：老备份缺字段回默认值，不抛 ----

    private fun chatSessionJson(s: ChatSession) = JSONObject()
        .put("id", s.id).put("title", s.title).put("model", s.model).put("assistantId", s.assistantId)
        .put("memoryPrompt", s.memoryPrompt).put("lastSummarizeIndex", s.lastSummarizeIndex)
        .put("lastSummarizedMessageId", s.lastSummarizedMessageId)
        .put("clearBefore", s.clearBefore).put("pinned", s.pinned)
        .put("createdAt", s.createdAt).put("updatedAt", s.updatedAt)
        .put("projectId", s.projectId).put("archived", s.archived)
        .put("folderId", s.folderId).put("thinking", s.thinking).put("permJson", s.permJson)

    private fun chatSessionFromJson(o: JSONObject) = ChatSession(
        id = o.getLong("id"), title = o.optString("title", "新对话"), model = o.optString("model"),
        assistantId = o.optLong("assistantId"), memoryPrompt = o.optString("memoryPrompt"),
        // lastSummarizeIndex 是 v39 起的兼容列，只为备份往返保留；锚点才是活的
        lastSummarizeIndex = o.optInt("lastSummarizeIndex"),
        lastSummarizedMessageId = o.optLong("lastSummarizedMessageId"),
        clearBefore = o.optLong("clearBefore"), pinned = o.optBoolean("pinned"),
        createdAt = o.optLong("createdAt"), updatedAt = o.optLong("updatedAt"),
        projectId = o.optLong("projectId"), archived = o.optBoolean("archived"),
        folderId = o.optLong("folderId"), thinking = o.optString("thinking"), permJson = o.optString("permJson")
    )

    private fun chatMessageJson(m: ChatMessage) = JSONObject()
        .put("id", m.id).put("sessionId", m.sessionId).put("role", m.role).put("content", m.content)
        .put("reasoning", m.reasoning).put("model", m.model).put("status", m.status).put("error", m.error)
        .put("tokensIn", m.tokensIn).put("tokensOut", m.tokensOut).put("createdAt", m.createdAt)
        .put("toolCallsJson", m.toolCallsJson).put("toolCallId", m.toolCallId)

    private fun chatMessageFromJson(o: JSONObject) = ChatMessage(
        id = o.getLong("id"), sessionId = o.getLong("sessionId"), role = o.getString("role"),
        content = o.optString("content"), reasoning = o.optString("reasoning"), model = o.optString("model"),
        status = o.optString("status", "done"), error = o.optString("error"),
        tokensIn = o.optInt("tokensIn"), tokensOut = o.optInt("tokensOut"),
        createdAt = o.optLong("createdAt"),
        toolCallsJson = o.optString("toolCallsJson"), toolCallId = o.optString("toolCallId")
    )

    private fun compactMarkerJson(m: CompactMarkerEntity) = JSONObject()
        .put("id", m.id).put("sessionId", m.sessionId).put("summary", m.summary)
        .put("lastCompactedMessageId", m.lastCompactedMessageId)
        .put("firstKeptMessageId", m.firstKeptMessageId).put("compactedCount", m.compactedCount)
        .put("source", m.source).put("createdAt", m.createdAt).put("version", m.version)
        // v47：区间压缩的起点；旧备份缺键回 0（整历史滚动语义）
        .put("firstCompactedMessageId", m.firstCompactedMessageId)

    private fun compactMarkerFromJson(o: JSONObject) = CompactMarkerEntity(
        id = o.getLong("id"), sessionId = o.getLong("sessionId"), summary = o.optString("summary"),
        lastCompactedMessageId = o.optLong("lastCompactedMessageId"),
        firstKeptMessageId = o.optLong("firstKeptMessageId"), compactedCount = o.optInt("compactedCount"),
        source = o.optString("source", "manual"), createdAt = o.optLong("createdAt"),
        version = o.optInt("version", 1),
        firstCompactedMessageId = o.optLong("firstCompactedMessageId", 0)
    )

}

// ── v7：记忆家族的 JSON 编解码（v45 起）──
//
// 放在类外、标 internal 是为了让纯 JVM 单测能直接做「写出去 → 读回来」的字段对称性断言——
// 这类错（少写一个字段）编译期完全看不出来，只有恢复备份时才会发现某列变默认值。

internal fun memoryJson(m: MemoryEntity) = JSONObject()
        .put("id", m.id).put("name", m.name).put("type", m.type)
        .put("description", m.description).put("body", m.body)
        .put("importance", m.importance).put("accessCount", m.accessCount)
        .put("lastAccessAt", m.lastAccessAt).put("expiration", m.expiration)
        .put("inIndex", m.inIndex).put("isDeleted", m.isDeleted)
        .put("originSession", m.originSession).put("createdAt", m.createdAt)
        .put("updatedAt", m.updatedAt).put("projectId", m.projectId)
        .put("priority", m.priority).put("scene", m.scene)
        .put("validAt", m.validAt).put("invalidAt", m.invalidAt)
        .put("expiredAt", m.expiredAt).put("version", m.version)
        .put("supersededBy", m.supersededBy)

internal fun memoryFromJson(o: JSONObject) = MemoryEntity(
        id = o.getLong("id"), name = o.getString("name"),
        type = o.optString("type", "project"), description = o.optString("description"),
        body = o.optString("body"), importance = o.optInt("importance", 1),
        accessCount = o.optInt("accessCount"), lastAccessAt = o.optLong("lastAccessAt"),
        expiration = o.optLong("expiration"), inIndex = o.optBoolean("inIndex"),
        isDeleted = o.optBoolean("isDeleted"), originSession = o.optLong("originSession"),
        createdAt = o.optLong("createdAt"), updatedAt = o.optLong("updatedAt"),
        projectId = o.optLong("projectId"), priority = o.optInt("priority", 50),
        scene = o.optString("scene", ""), validAt = o.optLong("validAt"),
        invalidAt = o.optLong("invalidAt"), expiredAt = o.optLong("expiredAt"),
        version = o.optInt("version", 1), supersededBy = o.optLong("supersededBy")
    )

internal fun memoryEvidenceJson(e: MemoryEvidence) = JSONObject()
        .put("memoryId", e.memoryId).put("messageId", e.messageId)
        .put("kind", e.kind).put("createdAt", e.createdAt)

internal fun memorySceneJson(s: MemoryScene) = JSONObject()
        .put("id", s.id).put("name", s.name).put("summary", s.summary).put("heat", s.heat)
        .put("projectId", s.projectId).put("validAt", s.validAt).put("invalidAt", s.invalidAt)
        .put("createdAt", s.createdAt).put("updatedAt", s.updatedAt)

internal fun memorySceneFromJson(o: JSONObject) = MemoryScene(
        id = o.getLong("id"), name = o.getString("name"), summary = o.optString("summary"),
        heat = o.optInt("heat", 1), projectId = o.optLong("projectId"),
        validAt = o.optLong("validAt"), invalidAt = o.optLong("invalidAt"),
        createdAt = o.optLong("createdAt"), updatedAt = o.optLong("updatedAt")
    )

internal fun memoryPersonaJson(p: MemoryPersona) = JSONObject()
        .put("id", p.id).put("body", p.body).put("version", p.version).put("updatedAt", p.updatedAt)

internal fun memoryPersonaFromJson(o: JSONObject) = MemoryPersona(
        id = o.optLong("id", 1), body = o.optString("body"),
        version = o.optInt("version", 1), updatedAt = o.optLong("updatedAt")
)
