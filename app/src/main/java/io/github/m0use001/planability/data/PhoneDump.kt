package io.github.m0use001.planability.data

import android.content.Context
import io.github.m0use001.planability.core.AppTime
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * v19.14：跨端 dump v1 生产者（PC 端 `mergeDump` 的对端格式）
 *
 * 契约权威：◆/pc.md 2026-10-04T22:50 + ◆/planability-cross-end-contract.md
 * ```
 * { schemaVersion: 1, exportedAt: <ms>,
 *   entity: { schedules[], todos[], notes[], notebooks[], memories[], projects[], assistants[] },
 *   kv?: { ... } }
 * ```
 * 每条记录公共字段：`id`(字符串) `createdAt` `updatedAt` `deletedAt?`
 *
 * 与手机端现有实体的字段差异（对齐 PC 命名）：
 * - 笔记正文 `content` → `body`
 * - 日程 `start`/`end`(Long) → `startIso`/`endIso`(ISO 字符串)
 * - 待办 `due`(Long) → `time`("HH:mm")
 * - 删除态 `trashed` + `trashedAt` → `deletedAt`
 * - 完成态 `done` → `status`("open"/"done")
 * - 归属项目 `folderId`/`projectId` → `project`
 * - 笔记本 `notebookId`(Long) → `notebookId`(字符串)
 *
 * **v21 已修**：schedules/todos 表原本没有 createdAt/updatedAt 列，这里曾用可推导的稳定时间
 * （日程取 start、待办取 date/due）当两个时间戳 —— 好处是同一份数据每次导出时间戳不变，
 * 坏处是**在 PC 端改过的数据会被手机端下一次导出覆盖回去**（合并规则只能比 mtime/updatedAt，
 * 两边都没有真实 updatedAt 时 PC 的改动必输）。
 * 现已给两张表补 `createdAt`/`updatedAt`（MIGRATION_20_21）并在 PlanRepo 的写入点统一 bump，
 * 导出优先用表里的真实时间；只有迁移前的老行才回落到可推导值。
 */
object PhoneDump {

    /** PC 端约定：手机端把 id 转成字符串 */
    private fun sid(id: Long): String = id.toString()

    private fun dayKeyOf(millis: Long): String =
        if (millis <= 0) "" else Instant.ofEpochMilli(millis).atZone(AppTime.zone).toLocalDate().toString()

    private fun hhmmOf(millis: Long): String {
        if (millis <= 0) return ""
        val t = Instant.ofEpochMilli(millis).atZone(AppTime.zone).toLocalTime()
        return "%02d:%02d".format(t.hour, t.minute)
    }

    private fun isoOf(millis: Long): String =
        if (millis <= 0) "" else Instant.ofEpochMilli(millis).atZone(AppTime.zone)
            .format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss", Locale.US))

    private fun putIf(o: JSONObject, k: String, v: String) { if (v.isNotBlank()) o.put(k, v) }

    /** 待办/日程这类没有时间戳的表：用业务时间当稳定时间戳 */
    private fun stableTs(primary: Long, secondary: Long): Long =
        if (primary > 0) primary else if (secondary > 0) secondary else 0L

    /** db 参数化（默认 Graph.db）：schema 往返门用自建 in-memory 库喂入，不碰真机/模拟器现役库。 */
    suspend fun buildJson(db: AppDatabase = Graph.db): JSONObject {
        val root = JSONObject()
        // v2（2026-10-30）：追加 memory_evidence / memory_scenes / memory_persona。
        // PC 端同时接受 1 与 2——过渡期两端版本不同步是常态。
        root.put("schemaVersion", 2)
        root.put("exportedAt", System.currentTimeMillis())
        val ent = JSONObject()

        // ---------- schedules ----------
        ent.put("schedules", JSONArray().apply {
            db.scheduleDao().all().forEach { s ->
                // v21：表里已有真实 createdAt/updatedAt，不再用可推导时间占位 ——
                // 占位会让 PC 的改动在下次导出时被判成「手机更新」而覆盖掉（合并必败）
                val c = if (s.createdAt > 0) s.createdAt else stableTs(s.start, s.createdAtLike())
                val u = if (s.updatedAt > 0) s.updatedAt else c
                val o = JSONObject()
                o.put("id", sid(s.id))
                o.put("createdAt", c)
                o.put("updatedAt", u)
                if (s.trashed) o.put("deletedAt", if (s.trashedAt > 0) s.trashedAt else u)
                o.put("title", s.title)
                putIf(o, "date", dayKeyOf(s.start))
                putIf(o, "startIso", isoOf(s.start))
                putIf(o, "endIso", isoOf(s.end))
                putIf(o, "note", s.note)
                if (s.projectId > 0) o.put("project", s.projectId)
                o.put("priority", s.priority)
                put(o)
            }
        })

        // ---------- todos ----------
        ent.put("todos", JSONArray().apply {
            db.todoDao().all().forEach { t ->
                // v21：同上，优先用表里的真实时间
                val c = when {
                    t.createdAt > 0 -> t.createdAt
                    t.due > 0 -> t.due
                    else -> stableTs(t.date, t.due)
                }
                val u = if (t.updatedAt > 0) t.updatedAt else c
                val o = JSONObject()
                o.put("id", sid(t.id))
                o.put("createdAt", c)
                o.put("updatedAt", u)
                if (t.trashed) o.put("deletedAt", if (t.trashedAt > 0) t.trashedAt else u)
                o.put("title", t.title)
                putIf(o, "date", dayKeyOf(t.date))
                putIf(o, "time", hhmmOf(t.due))
                o.put("priority", t.priority)
                o.put("status", if (t.done) "done" else "open")
                if (t.folderId > 0) o.put("project", t.folderId)
                put(o)
            }
        })

        // ---------- notes ----------
        ent.put("notes", JSONArray().apply {
            db.noteDao().allIncludingTrashed().forEach { n ->
                val o = JSONObject()
                o.put("id", sid(n.id))
                o.put("createdAt", n.createdAt)
                o.put("updatedAt", n.updatedAt)
                if (n.trashed) o.put("deletedAt", if (n.trashedAt > 0) n.trashedAt else n.updatedAt)
                o.put("title", n.title)
                o.put("body", n.content)
                o.put("pinned", n.pinned)
                if (n.projectId > 0) o.put("project", n.projectId)
                o.put("notebookId", sid(n.notebookId))
                put(o)
            }
        })

        // ---------- notebooks ----------
        ent.put("notebooks", JSONArray().apply {
            db.notebookDao().all().forEach { b ->
                val o = JSONObject()
                o.put("id", sid(b.id))
                o.put("createdAt", b.createdAt)
                o.put("updatedAt", b.createdAt)
                o.put("name", b.name)
                o.put("coverStyle", b.coverStyle)
                o.put("coverHue", b.coverHue.toDouble())
                o.put("coverText", b.coverText)
                o.put("coverLayout", b.coverLayout)
                put(o)
            }
        })

        // ---------- memories ----------
        ent.put("memories", JSONArray().apply {
            db.memoryDao().all().forEach { m ->
                val o = JSONObject()
                o.put("id", sid(m.id))
                o.put("createdAt", m.createdAt)
                o.put("updatedAt", m.updatedAt)
                if (m.isDeleted) o.put("deletedAt", m.updatedAt)
                o.put("name", m.name)
                o.put("description", m.description)
                o.put("body", m.body)
                putIf(o, "type", m.type)
                //手机端 importance 是 1/2/3，PC 约定 0..1
                o.put("importance", when (m.importance) { 3 -> 1.0; 2 -> 0.6; else -> 0.3 })
                o.put("accessCount", m.accessCount)
                if (m.lastAccessAt > 0) o.put("lastAccessAt", m.lastAccessAt)
                o.put("inIndex", m.inIndex)
                put(o)
            }
        })

        // ---------- projects ----------
        ent.put("projects", JSONArray().apply {
            db.projectDao().all().forEach { p ->
                val o = JSONObject()
                o.put("id", sid(p.id))
                o.put("createdAt", p.createdAt)
                o.put("updatedAt", if (p.updatedAt > 0) p.updatedAt else p.createdAt)
                o.put("name", p.name)
                // v42：绑定的真实文件夹（空 = 默认 synced/projects 行为）
                putIf(o, "dirPath", p.dirPath)
                putIf(o, "emoji", p.emoji)
                put(o)
            }
        })

        // ---------- assistants ----------
        ent.put("assistants", JSONArray().apply {
            db.assistantDao().all().forEach { a ->
                val o = JSONObject()
                o.put("id", a.key)
                o.put("createdAt", a.createdAt)
                o.put("updatedAt", a.createdAt)
                o.put("key", a.key)
                o.put("name", a.name)
                o.put("color", a.emoji)
                putIf(o, "slogan", a.slogan)
                o.put("systemPrompt", a.systemPrompt)
                o.put("temperature", a.temperature)
                o.put("builtin", a.builtin)
                put(o)
            }
        })

        // ---------- dump v2：记忆分层（场景/画像/证据） ----------
        //
        // 证据用**记忆名**关联而不是记忆 id：PC 端 mergeDump 的规则是「赢家保留本地 id」，
        // 手机 id 在第一次合并后就不一定还在；名字在手机端唯一，是两端唯一稳定的身份。
        //
        // 场景 id 用 `scene_<name>`、画像 id 固定 `persona`——只是首轮助记，身份同样靠自然键
        // （见 PC Store.naturalKey），这样 id 对不上也不会重复。
        val memById = runCatching { db.memoryDao().allIncludingDeleted().associateBy { it.id } }
            .getOrDefault(emptyMap())
        ent.put("memory_evidence", JSONArray().apply {
            db.memoryEvidenceDao().all().forEach { ev ->
                val m = memById[ev.memoryId] ?: return@forEach
                if (m.isDeleted) return@forEach
                put(
                    JSONObject()
                        .put("id", "${m.name}#${ev.messageId}")
                        .put("createdAt", ev.createdAt)
                        .put("updatedAt", ev.createdAt)
                        .put("memory", m.name)
                        .put("messageId", ev.messageId)
                        .put("kind", ev.kind)
                )
            }
        })
        ent.put("memory_scenes", JSONArray().apply {
            db.memorySceneDao().all().forEach { sc ->
                put(
                    JSONObject()
                        .put("id", "scene_${sc.name}")
                        .put("createdAt", sc.createdAt)
                        .put("updatedAt", sc.updatedAt)
                        .put("name", sc.name)
                        .put("summary", sc.summary)
                        .put("heat", sc.heat)
                        .apply { if (sc.projectId > 0) put("project", sc.projectId.toString()) }
                )
            }
        })
        ent.put("memory_persona", JSONArray().apply {
            db.memoryPersonaDao().get()?.takeIf { it.body.isNotBlank() }?.let { p ->
                put(
                    JSONObject()
                        .put("id", "persona")
                        .put("createdAt", p.updatedAt)
                        .put("updatedAt", p.updatedAt)
                        .put("body", p.body)
                        .put("version", p.version)
                )
            }
        })

        root.put("entity", ent)
        return root
    }

        /**
         * dump 落地目录：PC 端从「同步文件夹」里读 phone-dump.json，所以必须落在真正的同步目录里。
         * 依次尝试：内核 REST（内核在跑时最准）→ config.xml → 应用默认 synced 目录 → files/dump。
         * 两端共享契约是 folder **id** = planability-data（SyncthingScreen 加 folder 时固定写这个 id）；
         * 2026-11-09 实证不能取「第一个 folder」：配了大学文件后它按字母序排在前面，
         * dump 就会写进个人文件夹、PC 端读不到。所以两处都按 id 精确找。
         */
        suspend fun defaultDir(context: Context): File {
            val fromRest = runCatching {
                val arr = JSONArray(io.github.m0use001.planability.syncthing.SyncthingEngine.configFolders(context))
                (0 until arr.length()).asSequence()
                    .map { arr.getJSONObject(it) }
                    .firstOrNull { it.optString("id") == "planability-data" }
                    ?.optString("path")?.takeIf { it.isNotBlank() }
            }.getOrNull()
            val fromXml = runCatching {
                val xml = File(io.github.m0use001.planability.syncthing.SyncthingEngine.configDir(context), "config.xml")
                    .takeIf { it.exists() }?.readText()
                xml?.let {
                    Regex("<folder[^>]*id=\"planability-data\"[^>]*path=\"([^\"]+)\"").find(it)?.groupValues?.get(1)
                }?.takeIf { it.isNotBlank() }
            }.getOrNull()
            val ext = context.getExternalFilesDir(null) ?: context.filesDir
            return File(
                fromRest ?: fromXml ?: File(ext, "synced").absolutePath
            )
        }

    /**
     * 写出 `<dir>/phone-dump.json`。
     * 先写 .tmp 再 rename —— PC 端按 mtime 判断新 dump，半截文件会被当成新文件读。
     */
    suspend fun write(context: Context, dir: File? = null): File? = runCatching {
        val target0 = dir ?: defaultDir(context)
        if (!target0.exists()) target0.mkdirs()
        val json = buildJson()
        val target = File(target0, "phone-dump.json")
        val tmp = File(target0, "phone-dump.json.tmp")
        tmp.writeText(json.toString())
        if (target.exists()) target.delete()
        tmp.renameTo(target)
        target
    }.getOrNull()
}

/** Schedule 没有 createdAt 列（见文件头说明），这里给一个稳定的兜底 */
private fun Schedule.createdAtLike(): Long = 0L