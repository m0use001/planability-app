package io.github.m0use001.planability.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.m0use001.planability.shared.DumpContract
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * **M5 schema 往返门（会红的门，模拟器仪器化实测）**。
 *
 * 背景：KSP 不支持 AGP9 KMP library 的 android compilation，Room 的 @Database 无法在
 * `:shared` 跑 KSP（见 ◆/phone-c.md 2026-11-25），所以「同一份 schema 两端建库」
 * 的 JVM 端不存在；门改走 **Android 仪器化**——测试进程就在 app 环境里，直接驱动真实
 * 代码路径（Room 建库用 shared 的实体/DAO、dump 用 PhoneDump.buildJson、备份用
 * BackupManager.exportJson/importJson），比抽代码到 shared 再测更贴近生产。
 *
 * 三道断言：
 * - **A 建库结构**：Room（shared 实体）in-memory 建库 → user_version=57、dump 十集合表
 *   全在、两张 FTS4 虚表在、room_fts_content_sync 触发器在。
 * - **B dump 互认**：向 10 个集合各插一条**字段填满**的 fixture → PhoneDump.buildJson(db)
 *   → 逐集合产出字段 == DumpContract（M4 机器可读契约）+ 通用字段，且关键值与输入一致。
 * - **C 备份往返**：exportJson → 新空库 importJson → 再 exportJson → 结构相等（剥
 *   exportedAt 后逐表逐行比较）。
 *
 * 植入故障验证（M5 交付记录，2026-11-25）：① memory_scenes 加 `put("rogue_drift_probe",1)`
 * → B 门红「[memory_scenes] 字段漂移：多出 [rogue_drift_probe]」；② 注释掉 exportJson 的
 * todo_percent_logs 一段 → C 门红「往返后缺表」。两向实测红后撤净。
 *
 * **实体一律全参构造**：debug 也开 R8，默认参数的合成 `$default` 方法会被剥
 * （见 ◆ 教训），androidTest 必须显式传满参数。
 */
@RunWith(AndroidJUnit4::class)
class SchemaRoundtripGateTest {

    private fun buildDb(): AppDatabase =
        Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            AppDatabase::class.java
        ).allowMainThreadQueries().build()

    private fun sqliteObjects(db: AppDatabase): List<Pair<String, String>> {
        val c = db.openHelper.writableDatabase.query("SELECT type, name FROM sqlite_master")
        val out = ArrayList<Pair<String, String>>()
        c.use {
            while (it.moveToNext()) out += it.getString(0) to it.getString(1)
        }
        return out
    }

    // ---------- A：建库结构 ----------

    @Test
    fun `建库到 v57 且 dump 集合表与 FTS 触发器齐全`() {
        // RoomDatabase(KMP) 只实现 AutoCloseable，kotlin 的 Closeable.use 不适用 → 手动 close
        val db = buildDb()
        try {
            val c = db.openHelper.writableDatabase.query("PRAGMA user_version")
            c.use { assertTrue("user_version 应为 57", it.moveToFirst() && it.getInt(0) == 57) }
            val objs = sqliteObjects(db)
            val tables = objs.filter { it.first == "table" }.map { it.second }.toSet()
            val triggers = objs.filter { it.first == "trigger" }.map { it.second }
            // dump 十集合的表全在（表名与集合名同名）
            val missing = DumpContract.COLLECTION_FIELDS.keys.filter { it !in tables }
            assertTrue("dump 集合表缺失：$missing（实体/DAO 迁 shared 后建库漂了）", missing.isEmpty())
            // 两张外部内容 FTS4 虚表
            assertTrue("memories_fts 不在", "memories_fts" in tables)
            assertTrue("notes_fts 不在", "notes_fts" in tables)
            // Room 为外部内容 FTS 自动建的同步触发器
            assertTrue(
                "room_fts_content_sync 触发器缺失（FTS 同步链断了）",
                triggers.count { it.startsWith("room_fts_content_sync_") } >= 4
            )
            assertTrue("room_master_table 不在（identity hash 丢失）", "room_master_table" in tables)
        } finally { db.close() }
    }

    // ---------- fixture：每个集合一条、专属字段全部填满（让 B 门能对齐全集） ----------

    private fun seedFixtures(db: AppDatabase) = runBlocking {
        db.projectDao().insert(
            Project(id = 0, name = "门测试项目", emoji = "🧪", iconKey = "science", pinned = true,
                dirPath = "/测试目录", archived = false, createdAt = 1000, updatedAt = 1100)
        )
        db.scheduleDao().upsert(
            Schedule(id = 0, title = "门测试日程", start = 1_700_000_000_000, end = 1_700_000_360_000,
                tagId = 0, place = "测试地点", note = "门测试备注", repeatRule = "", excludedDates = "",
                remindMin = 15, trashed = false, trashedAt = 0, projectId = 1, priority = 1,
                urgency = 2, importance = 3, lat = 0.0, lng = 0.0, placePoi = "测试POI",
                labels = "工作", createdAt = 1000, updatedAt = 1100)
        )
        db.todoDao().upsert(
            Todo(id = 0, title = "门测试待办", date = 1_700_000_000_000, due = 1_700_003_600_000,
                endAt = 1_700_007_200_000, priority = 1, urgency = 2, importance = 3, tagId = 0,
                folderId = 1, done = false, doneAt = 0, repeatType = "none", repeatDays = "",
                repeatInterval = 0, repeatEnd = 0, permanent = false, supervisionEnabled = false,
                percentDone = -1, dataRecordEnabled = false, dataRecordKind = "", dataRecordLabel = "",
                dataRecordUnit = "", dataRecordOptions = "", excludedDates = "", note = "待办备注",
                remindMin = 15, trashed = false, trashedAt = 0, projectId = 1, place = "", lat = 0.0,
                lng = 0.0, placePoi = "", labels = "工作", createdAt = 1000, updatedAt = 1100)
        )
        db.notebookDao().upsert(
            NotebookEntity(id = 0, name = "门测试笔记本", coverStyle = -1, coverHue = 210f,
                createdAt = 1000, labels = "", coverText = "封", coverLayout = 0, coverIcon = 0,
                coverIcons = "")
        )
        db.noteDao().upsert(
            NoteEntity(id = 0, notebookId = 1, title = "门测试笔记", kind = "md",
                content = "门测试正文", inkJson = "", createdAt = 1000, updatedAt = 1100,
                trashed = false, trashedAt = 0, pinned = true, projectId = 1, tags = "测试")
        )
        val memId = db.memoryDao().upsert(
            MemoryEntity(id = 0, name = "门测试记忆", type = "project", description = "描述",
                body = "正文", importance = 2, accessCount = 3, lastAccessAt = 1200, expiration = 0,
                inIndex = true, isDeleted = false, originSession = 0, createdAt = 1000,
                updatedAt = 1100, projectId = 0, priority = 60, scene = "", validAt = 0,
                invalidAt = 0, expiredAt = 0, version = 1, supersededBy = 0)
        )
        db.memoryEvidenceDao().insert(
            MemoryEvidence(id = 0, memoryId = memId, messageId = 42, kind = "support", createdAt = 1000)
        )
        db.memorySceneDao().upsert(
            MemoryScene(id = 0, name = "门测试场景", summary = "场景摘要", heat = 5, projectId = 1,
                validAt = 0, invalidAt = 0, createdAt = 1000, updatedAt = 1100)
        )
        db.memoryPersonaDao().upsert(
            MemoryPersona(id = 1, body = "画像正文", version = 2, updatedAt = 1100)
        )
        db.assistantDao().upsert(
            Assistant(id = 0, key = "gate-test", name = "门测试助手", emoji = "🤖", slogan = "门测试",
                systemPrompt = "门提示词", card = "", temperature = 0.5, builtin = false, createdAt = 1000)
        )
    }

    // ---------- B：dump 互认 ----------

    @Test
    fun `dump 十集合字段与 shared 契约逐字段一致`() = runBlocking {
        val db = buildDb()
        try {
            seedFixtures(db)
            val dump = PhoneDump.buildJson(db)
            assertEquals("schemaVersion 漂了", DumpContract.SCHEMA_VERSION, dump.getInt("schemaVersion"))
            val ent = dump.getJSONObject("entity")
            assertEquals("集合清单与契约不一致", DumpContract.COLLECTION_FIELDS.keys.sorted(), ent.keys().asSequence().sorted().toList())
            DumpContract.COLLECTION_FIELDS.forEach { (collection, contract) ->
                val arr = ent.getJSONArray(collection)
                assertEquals("[$collection] 应有 1 条 fixture", 1, arr.length())
                val row = arr.getJSONObject(0)
                val emitted = row.keys().asSequence().toSet()
                val extra = emitted - DumpContract.COMMON_FIELDS - contract
                val missing = contract - emitted
                assertTrue(
                    "[$collection] 字段漂移：多出 $extra（PC 端不认识）；缺失 $missing（PC 端拿到残记录）",
                    extra.isEmpty() && missing.isEmpty()
                )
                assertTrue("[$collection] 通用字段不齐", (setOf("id", "createdAt", "updatedAt") - emitted).isEmpty())
            }
            // 关键值与输入一致（跨端命名的转换点逐个钉；date 断言按设备时区动态算，不写死）
            val expectedDay = java.time.Instant.ofEpochMilli(1_700_000_000_000)
                .atZone(java.time.ZoneId.systemDefault()).toLocalDate().toString()
            val sched = ent.getJSONArray("schedules").getJSONObject(0)
            assertEquals("门测试日程", sched.getString("title"))
            assertEquals("dayKey 应按 AppTime 本地时区算", expectedDay, sched.getString("date"))
            assertEquals(1, sched.getInt("project"))
            val todo = ent.getJSONArray("todos").getJSONObject(0)
            assertEquals("open", todo.getString("status"))
            assertEquals(1, todo.getInt("project"))
            val note = ent.getJSONArray("notes").getJSONObject(0)
            assertEquals("门测试正文", note.getString("body"))
            assertEquals("1", note.getString("notebookId"))
            val mem = ent.getJSONArray("memories").getJSONObject(0)
            assertEquals("importance 2 应映射 0.6（手机 1/2/3 → PC 0..1）", 0.6, mem.getDouble("importance"), 1e-9)
            val ev = ent.getJSONArray("memory_evidence").getJSONObject(0)
            assertEquals("门测试记忆#42", ev.getString("id"))
            assertEquals("门测试记忆", ev.getString("memory"))
            val scene = ent.getJSONArray("memory_scenes").getJSONObject(0)
            assertEquals("1", scene.getString("project"))
            val persona = ent.getJSONArray("memory_persona").getJSONObject(0)
            assertEquals(2, persona.getInt("version"))
        } finally { db.close() }
    }

    // ---------- C：备份往返 ----------

    @Test
    fun `备份导出导入再导出结构一致`() = runBlocking {
        val db1 = buildDb()
        seedFixtures(db1)
        val s1 = BackupManager(db1).exportJson()
        db1.close()

        val db2 = buildDb()
        BackupManager(db2).importJson(s1)
        val s2 = BackupManager(db2).exportJson()

        val j1 = JSONObject(s1); j1.remove("exportedAt")
        val j2 = JSONObject(s2); j2.remove("exportedAt")
        assertEquals("顶层键漂了", j1.keys().asSequence().sorted().toList(), j2.keys().asSequence().sorted().toList())
        val t1 = j1.getJSONObject("tables")
        val t2 = j2.getJSONObject("tables")
        assertEquals("备份表清单漂了", t1.keys().asSequence().sorted().toList(), t2.keys().asSequence().sorted().toList())
        t1.keys().asSequence().sorted().forEach { table ->
            val a = t1.getJSONArray(table)
            val b = t2.getJSONArray(table)
            assertEquals("[$table] 往返后行数不一致（丢行/丢表）", a.length(), b.length())
            for (i in 0 until a.length()) {
                // Android org.json 的 JSONObject 未重写 equals（引用相等），改比 toString
                assertEquals("[$table] 第 $i 行往返后不一致", a.getJSONObject(i).toString(), b.getJSONObject(i).toString())
            }
        }
        db2.close()
    }
}
