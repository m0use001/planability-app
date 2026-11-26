package io.github.m0use001.planability.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import io.github.m0use001.planability.ai.mcp.McpServer
import io.github.m0use001.planability.ai.mcp.McpServerDao
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

@Database(
    entities = [
        Schedule::class, Todo::class, SupervisionCheck::class, TodoDoneLog::class,
        CheckinRecord::class,
        HourTask::class, TimelineEvent::class, Countdown::class, CategoryEntity::class,
        FocusSession::class, NotebookEntity::class, NoteEntity::class, NoteAudio::class,
        ChatDigest::class, PendingItem::class, WeeklyRecord::class, GroupTarget::class,
        SettingKV::class, AchievementRec::class, Attachment::class,
        ChatSession::class, ChatMessage::class,
        MemoryEntity::class, MemoryFts::class, MemoryLink::class, MemoryHistory::class,
        // v36：记忆后台整理进度（表是新建的，见 MIGRATION_35_36）
        MemoryProgressEntity::class,
        Assistant::class, ToolAudit::class, RagChunk::class, Project::class,
        AiProvider::class, AiModel::class, Folder::class, Automation::class, McpServer::class,
        // v24：ProjectFile 不再是 Room 实体（真文件了，见 data/ProjectFileRepo.kt）
        SleepRecord::class, LabelColor::class,
        // v35：自动化执行日志（表是新建的，见 MIGRATION_31_32）
        AutomationRun::class,
        // v37：压缩标记（表是新建的，见 MIGRATION_36_37）。36 已被 memory_progress 占
        CompactMarkerEntity::class,
        // v43：常驻自主循环任务表（见 MIGRATION_42_43）
        AutoTask::class,
        // v45：记忆的证据边 / L2 场景 / L3 画像（见 MIGRATION_44_45）
        MemoryEvidence::class, MemoryScene::class, MemoryPersona::class,
        // v48：待办百分比进度流水（见 MIGRATION_47_48）
        TodoPercentLog::class,
        // v49：知识库（notes_fts 虚表 + note_links 链接图；notes.tags 列见 MIGRATION_48_49）
        NotesFts::class, NoteLink::class,
        // v51：语义检索的笔记向量（模型按需下载，没下载时这张表为空）
        NoteEmbedding::class,
        // v55：智能体卡挂载的世界书（assistants.card / chat_sessions.authorNote 同版新增）
        LoreEntry::class
    ],
    // M5：实体/DAO 真源已迁 `:shared`（同 package，Room KSP 从 classpath 解析）；
    // @Database 声明式清单与迁移/Graph 仍留 app（构造与升级路径属 Android 侧）。
    // v44：categories.displayNames（地点两级模型：绑定地名 → 多个显示地名）
    // v45：memories 加 priority/scene/validAt/invalidAt/expiredAt/version/supersededBy，
    //      新建 memory_evidence / memory_scenes / memory_persona
    // v49：notes 加 tags，新建 notes_fts / note_links（知识库）
    // v50：补 notes_fts rebuild（v49 漏了，存量笔记索引为空）
    // v51：语义检索 note_embeddings
    // v52：note_embeddings 按模型隔离（主键 noteId+modelId，可切轻量/重型两档模型）
    // v53：pending_items 加 source/originProjectId/originSessionId/suggestedStart/suggestedEnd/confidence（AI 助理中枢）
    // v54：pending_items 加 payload（普通会话创建的待办/日程改为中枢草稿，入库时按 payload 忠实重建）
    // v55：智能体卡——assistants.card（JSON）+ chat_sessions.authorNote/authorNoteDepth + 新表 lore_entries
    // v56：会话级联网开关 chat_sessions.webSearch（'' 跟随全局 / '1' 强制联网 / '0' 强制不联网）
    // v57：记忆项目隔离——memories/memory_scenes 唯一索引从 (name) 改 (name, projectId)，
    //      跨项目同名不再互相覆盖/灌入（2026-11-21 小说助理串味审计，见 MIGRATION_56_57）
    version = 57,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun scheduleDao(): ScheduleDao
    abstract fun todoDao(): TodoDao
    abstract fun supervisionDao(): SupervisionDao
    abstract fun checkinDao(): CheckinDao
    abstract fun percentLogDao(): TodoPercentLogDao
    abstract fun doneLogDao(): DoneLogDao
    abstract fun hourTaskDao(): HourTaskDao
    abstract fun timelineEventDao(): TimelineEventDao
    abstract fun countdownDao(): CountdownDao
    abstract fun categoryDao(): CategoryDao
    abstract fun focusSessionDao(): FocusSessionDao
    abstract fun notebookDao(): NotebookDao
    abstract fun noteDao(): NoteDao
    abstract fun noteKbDao(): NoteKbDao
    abstract fun noteEmbeddingDao(): NoteEmbeddingDao
    abstract fun chatDigestDao(): ChatDigestDao
    abstract fun groupTargetDao(): GroupTargetDao
    abstract fun pendingItemDao(): PendingItemDao
    abstract fun weeklyRecordDao(): WeeklyRecordDao
    abstract fun settingsDao(): SettingsDao
    abstract fun achievementDao(): AchievementDao
    abstract fun backupDao(): BackupDao
    abstract fun noteAudioDao(): NoteAudioDao
    abstract fun attachmentDao(): AttachmentDao
    abstract fun chatDao(): ChatDao
    abstract fun loreDao(): LoreDao
    abstract fun memoryDao(): MemoryDao

    /** v45：记忆证据 / 场景 / 画像 */
    abstract fun memoryEvidenceDao(): MemoryEvidenceDao
    abstract fun memorySceneDao(): MemorySceneDao
    abstract fun memoryPersonaDao(): MemoryPersonaDao

    /** v43：常驻自主循环任务 */
    abstract fun autoTaskDao(): AutoTaskDao

    /** v36：记忆整理进度 */
    abstract fun memoryProgressDao(): MemoryProgressDao
    /** v37：压缩标记 */
    abstract fun compactMarkerDao(): CompactMarkerDao
    abstract fun assistantDao(): AssistantDao
    abstract fun toolAuditDao(): ToolAuditDao
    abstract fun ragDao(): RagDao
    abstract fun projectDao(): ProjectDao
    abstract fun aiProviderDao(): AiProviderDao
    abstract fun aiModelDao(): AiModelDao
    abstract fun folderDao(): FolderDao
    abstract fun automationDao(): AutomationDao

    /** v35：自动化执行日志 */
    abstract fun automationRunDao(): AutomationRunDao
    abstract fun mcpServerDao(): McpServerDao
    // v24：ProjectFileDao 随 project_files 表一起退休（项目文件改存磁盘，见 ProjectFileRepo）
    abstract fun sleepRecordDao(): SleepRecordDao

    /** v28：自由标签的颜色（name → hue），见 LabelColor */
    abstract fun labelColorDao(): LabelColorDao
}

/** 全局单例对象图：Application 初始化。 */
object Graph {
    lateinit var appContext: Context
        private set
    lateinit var db: AppDatabase
        private set

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val dataVersion = MutableVersion()

    lateinit var settings: SettingsRepo
    lateinit var plan: PlanRepo
    lateinit var focus: FocusRepo
    lateinit var notes: NotesRepo
    lateinit var info: InfoRepo
    lateinit var chatTargets: GroupTargetRepo
    lateinit var chat: ChatRepo
    lateinit var memories: MemoryRepo
    lateinit var memoryProgress: MemoryProgressRepo
    lateinit var compactMarkers: CompactRepo
    lateinit var assistants: AssistantRepo
    lateinit var lore: LoreRepo
    lateinit var projects: ProjectRepo
    lateinit var folders: FolderRepo
    lateinit var aiProviders: AiProviderRepo
    lateinit var achievements: AchievementCenter
    lateinit var backup: BackupManager
    lateinit var sleep: SleepRepo

    fun init(context: Context) {
        if (::db.isInitialized) return
        appContext = context.applicationContext
        db = Room.databaseBuilder(context, AppDatabase::class.java, "planability.db")
        .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13, MIGRATION_13_14, MIGRATION_14_15, MIGRATION_15_16, MIGRATION_16_17, MIGRATION_17_18, MIGRATION_18_19, MIGRATION_19_20, MIGRATION_20_21, MIGRATION_21_22, MIGRATION_22_23, MIGRATION_23_24, MIGRATION_24_25, MIGRATION_25_26, MIGRATION_26_27, MIGRATION_27_28, MIGRATION_28_29, MIGRATION_29_30, MIGRATION_30_31, MIGRATION_31_32, MIGRATION_32_33, MIGRATION_33_34, MIGRATION_34_35, MIGRATION_35_36, MIGRATION_36_37, MIGRATION_37_38, MIGRATION_38_39, MIGRATION_39_40, MIGRATION_40_41, MIGRATION_41_42, MIGRATION_42_43, MIGRATION_43_44, MIGRATION_44_45, MIGRATION_45_46, MIGRATION_46_47, MIGRATION_47_48, MIGRATION_48_49, MIGRATION_49_50, MIGRATION_50_51, MIGRATION_51_52, MIGRATION_52_53, MIGRATION_53_54, MIGRATION_54_55, MIGRATION_55_56, MIGRATION_56_57)
        .build()
        settings = SettingsRepo(db.settingsDao())
        plan = PlanRepo(db, dataVersion, appScope)
        focus = FocusRepo(db.focusSessionDao(), dataVersion)
        notes = NotesRepo(db, dataVersion)
        info = InfoRepo(db, dataVersion)
        chatTargets = GroupTargetRepo(db.groupTargetDao(), dataVersion)
        chat = ChatRepo(db, dataVersion)
        memories = MemoryRepo(dataVersion)
        memoryProgress = MemoryProgressRepo(db.memoryProgressDao())
        compactMarkers = CompactRepo(db)
        assistants = AssistantRepo(db.assistantDao())
        lore = LoreRepo(db.loreDao())
        projects = ProjectRepo(db.projectDao(), db.folderDao(), dataVersion)
        folders = FolderRepo(db.folderDao(), dataVersion)
        aiProviders = AiProviderRepo(db.aiProviderDao(), db.aiModelDao(), db.settingsDao(), dataVersion)
        achievements = AchievementCenter(db, appScope)
        backup = BackupManager(db)
        sleep = SleepRepo(db.sleepRecordDao(), dataVersion)
        appScope.launch { settings.load() }
        // 2026-11-04：单行滚期自愈——旧 bug 会把每日重复的行滚进未来（未来日期在当日视图的
        // byDate/overdueRepeating 两个查询里都不命中 → 彻底隐身）。启动时按完成流水拉回今天/明天，
        // 只动每日重复（周/月/隔日的未来日期是正常相位，无法与"改到未来开始"区分）。
        appScope.launch { runCatching { plan.repairRolledPast() } }
        // 2026-11-19：行日期越过系列截止（date > repeatEnd）是模型外状态——那条「11/7 就结束、
        // 却被写到 11/19」的待办就是这么天天赖在今天的。启动时收回系列内（幂等，没这种行不写库）。
        appScope.launch { runCatching { plan.repairDeadSeries() } }
        // v49 知识库：链接图自愈——备份还原/导入后，悬空 [[链接]] 在启动时接上目标笔记。
        // 也保证这条链路**在 R8 眼里的可达性**（debug 也开 R8，只被测试用到的方法会被裁掉）。
        appScope.launch { runCatching { notes.resolveDangling() } }
        appScope.launch { assistants.ensureSeeded() }
        // v7 模型迁移种子：旧 settings.model / modelw → ai_providers / ai_models
        appScope.launch {
            settings.load()
            // v44：先把历史明文 key 封成密文，再播种/补供应商（种子入口本身也加密）
            aiProviders.migrateSecrets()
            aiProviders.seedIfNeeded(settings.snapshot.value.model, settings.snapshot.value.worker)
            // v19.51：DeepSeek / NVIDIA NIM（key 在 local.properties，空串则跳过）
            // 2026-11-02：+ AMD Radeon（MiMo-V2.6-Flash 免费额度；端内多账号池的主通道）
            aiProviders.ensureCatalogProviders(
                io.github.m0use001.planability.BuildConfig.DEEPSEEK_KEY,
                io.github.m0use001.planability.BuildConfig.NVIDIA_KEY,
                io.github.m0use001.planability.BuildConfig.AMD_KEY
            )
        }
    }
}

/** 数据版本号：任何写操作 bump，常驻页 collect 后重拉数据。 */
class MutableVersion(initial: Int = 0) {
    private val _state = kotlinx.coroutines.flow.MutableStateFlow(initial)
    val flow: kotlinx.coroutines.flow.StateFlow<Int> = _state
    fun bump() {
        _state.value += 1
    }
}

/** v1 → v2：日程/待办增加重复例外日字段 */
private val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE schedules ADD COLUMN excludedDates TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE todos ADD COLUMN excludedDates TEXT NOT NULL DEFAULT ''")
    }
}

/** v2 → v3：日程/待办提醒分钟 + 笔记录音表 + 附件表 */
private val MIGRATION_2_3 = object : androidx.room.migration.Migration(2, 3) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE schedules ADD COLUMN remindMin INTEGER NOT NULL DEFAULT 15")
        db.execSQL("ALTER TABLE todos ADD COLUMN remindMin INTEGER NOT NULL DEFAULT 15")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `note_audio` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`noteId` INTEGER NOT NULL, `path` TEXT NOT NULL, `durMs` INTEGER NOT NULL DEFAULT 0, " +
                "`createdAt` INTEGER NOT NULL DEFAULT 0)"
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_note_audio_noteId` ON `note_audio` (`noteId`)")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `attachments` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`ownerKind` TEXT NOT NULL, `ownerId` INTEGER NOT NULL, `name` TEXT NOT NULL, " +
                "`path` TEXT NOT NULL, `mime` TEXT NOT NULL DEFAULT '', `size` INTEGER NOT NULL DEFAULT 0, " +
                "`createdAt` INTEGER NOT NULL DEFAULT 0)"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_attachments_ownerKind_ownerId` ON `attachments` (`ownerKind`, `ownerId`)"
        )
    }
}

/** v3 → v4：待办时间段（endAt 结束时刻） */
private val MIGRATION_3_4 = object : androidx.room.migration.Migration(3, 4) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE todos ADD COLUMN endAt INTEGER NOT NULL DEFAULT 0")
    }
}

/** v4 → v5：AI 会话表 + 消息表（AI 工作台 M1） */
private val MIGRATION_4_5 = object : androidx.room.migration.Migration(4, 5) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `chat_sessions` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`title` TEXT NOT NULL, `model` TEXT NOT NULL, `memoryPrompt` TEXT NOT NULL, " +
                "`lastSummarizeIndex` INTEGER NOT NULL, `clearBefore` INTEGER NOT NULL, " +
                "`pinned` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL)"
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `chat_messages` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`sessionId` INTEGER NOT NULL, `role` TEXT NOT NULL, `content` TEXT NOT NULL, " +
                "`reasoning` TEXT NOT NULL, `model` TEXT NOT NULL, `status` TEXT NOT NULL, " +
                "`error` TEXT NOT NULL, `tokensIn` INTEGER NOT NULL, `tokensOut` INTEGER NOT NULL, " +
                "`createdAt` INTEGER NOT NULL)"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_chat_messages_sessionId` ON `chat_messages` (`sessionId`)"
        )
    }
}

/** v5 → v6：长期记忆（memories+FTS+links+history，M2.5） */
private val MIGRATION_5_6 = object : androidx.room.migration.Migration(5, 6) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `memories` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`name` TEXT NOT NULL, `type` TEXT NOT NULL, `description` TEXT NOT NULL, " +
                "`body` TEXT NOT NULL, `importance` INTEGER NOT NULL, `accessCount` INTEGER NOT NULL, " +
                "`lastAccessAt` INTEGER NOT NULL, `expiration` INTEGER NOT NULL, `inIndex` INTEGER NOT NULL, " +
                "`isDeleted` INTEGER NOT NULL, `originSession` INTEGER NOT NULL, " +
                "`createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL)"
        )
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_memories_name` ON `memories` (`name`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_memories_type` ON `memories` (`type`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_memories_inIndex` ON `memories` (`inIndex`)")
        db.execSQL(
            "CREATE VIRTUAL TABLE IF NOT EXISTS `memories_fts` USING FTS4(" +
                "`name` TEXT NOT NULL, `description` TEXT NOT NULL, `body` TEXT NOT NULL, content=`memories`)"
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `memory_links` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`fromId` INTEGER NOT NULL, `toId` INTEGER NOT NULL)"
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_memory_links_fromId` ON `memory_links` (`fromId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_memory_links_toId` ON `memory_links` (`toId`)")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `memory_history` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`memoryId` INTEGER NOT NULL, `action` TEXT NOT NULL, `detail` TEXT NOT NULL, `at` INTEGER NOT NULL)"
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_memory_history_memoryId` ON `memory_history` (`memoryId`)")
    }
}

/** v6 → v7：助手表 + 会话挂助手（M3） */
private val MIGRATION_6_7 = object : androidx.room.migration.Migration(6, 7) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `assistants` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`key` TEXT NOT NULL, `name` TEXT NOT NULL, `emoji` TEXT NOT NULL, `slogan` TEXT NOT NULL, " +
                "`systemPrompt` TEXT NOT NULL, `temperature` REAL NOT NULL, `builtin` INTEGER NOT NULL, " +
                "`createdAt` INTEGER NOT NULL)"
        )
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_assistants_key` ON `assistants` (`key`)")
        db.execSQL("ALTER TABLE chat_sessions ADD COLUMN assistantId INTEGER NOT NULL DEFAULT 0")
    }
}

/** v7 → v8：工具层审计表 + 消息表 tool 字段（M4 PTL） */
private val MIGRATION_7_8 = object : androidx.room.migration.Migration(7, 8) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `tools_audit` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`sessionId` INTEGER NOT NULL, `tool` TEXT NOT NULL, `args` TEXT NOT NULL, " +
                "`outcome` TEXT NOT NULL, `ms` INTEGER NOT NULL, `risk` TEXT NOT NULL, " +
                "`detail` TEXT NOT NULL, `at` INTEGER NOT NULL)"
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_tools_audit_sessionId` ON `tools_audit` (`sessionId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_tools_audit_at` ON `tools_audit` (`at`)")
        db.execSQL("ALTER TABLE chat_messages ADD COLUMN toolCallsJson TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE chat_messages ADD COLUMN toolCallId TEXT NOT NULL DEFAULT ''")
    }
}

/** v8 → v9：RAG-lite 分块表（M6） */
private val MIGRATION_8_9 = object : androidx.room.migration.Migration(8, 9) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `rag_chunks` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`sourceType` TEXT NOT NULL, `sourceId` INTEGER NOT NULL, `chunkIndex` INTEGER NOT NULL, " +
                "`text` TEXT NOT NULL, `mtime` INTEGER NOT NULL)"
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_rag_chunks_sourceId` ON `rag_chunks` (`sourceId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_rag_chunks_mtime` ON `rag_chunks` (`mtime`)")
    }
}

/** v10 → v11：多供应商模型体系（ai_providers/ai_models）+ 会话归档 + 项目图标 */
private val MIGRATION_10_11 = object : androidx.room.migration.Migration(10, 11) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `ai_providers` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`name` TEXT NOT NULL, `iconKey` TEXT NOT NULL DEFAULT 'cloud', `baseUrl` TEXT NOT NULL DEFAULT '', " +
                "`apiFormat` TEXT NOT NULL DEFAULT 'openai', `apiKey` TEXT NOT NULL DEFAULT '', " +
                "`authStyle` TEXT NOT NULL DEFAULT 'bearer', `enabled` INTEGER NOT NULL DEFAULT 1, " +
                "`builtin` INTEGER NOT NULL DEFAULT 0, `sort` INTEGER NOT NULL DEFAULT 0)"
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `ai_models` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`providerId` INTEGER NOT NULL, `name` TEXT NOT NULL, `tags` TEXT NOT NULL DEFAULT '', " +
                "`enabled` INTEGER NOT NULL DEFAULT 1)"
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_ai_models_providerId` ON `ai_models` (`providerId`)")
        db.execSQL("ALTER TABLE chat_sessions ADD COLUMN archived INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE projects ADD COLUMN iconKey TEXT NOT NULL DEFAULT 'folder'")
    }
}

/** v11 → v12：项目树（folders 子文件夹 + 会话 folderId）+ 会话思考档/权限 JSON（v8） */
private val MIGRATION_11_12 = object : androidx.room.migration.Migration(11, 12) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `folders` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`projectId` INTEGER NOT NULL, `parentId` INTEGER NOT NULL DEFAULT 0, " +
                "`name` TEXT NOT NULL, `sort` INTEGER NOT NULL DEFAULT 0, `createdAt` INTEGER NOT NULL DEFAULT 0)"
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_folders_projectId` ON `folders` (`projectId`)")
        db.execSQL("ALTER TABLE chat_sessions ADD COLUMN folderId INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE chat_sessions ADD COLUMN thinking TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE chat_sessions ADD COLUMN permJson TEXT NOT NULL DEFAULT ''")
    }
}

/** v12 → v13（v11 批）：日程优先级/坐标，待办地点/坐标，自动化任务表 */
private val MIGRATION_12_13 = object : androidx.room.migration.Migration(12, 13) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE schedules ADD COLUMN priority INTEGER NOT NULL DEFAULT 1")
        db.execSQL("ALTER TABLE schedules ADD COLUMN lat REAL NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE schedules ADD COLUMN lng REAL NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE todos ADD COLUMN place TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE todos ADD COLUMN lat REAL NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE todos ADD COLUMN lng REAL NOT NULL DEFAULT 0")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `automations` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`name` TEXT NOT NULL, `prompt` TEXT NOT NULL, `assistantKey` TEXT NOT NULL DEFAULT 'general', " +
                "`intervalMinutes` INTEGER NOT NULL DEFAULT 0, `dailyHour` INTEGER NOT NULL DEFAULT -1, " +
                "`dailyMinute` INTEGER NOT NULL DEFAULT -1, `enabled` INTEGER NOT NULL DEFAULT 1, " +
                "`lastRun` INTEGER NOT NULL DEFAULT 0, `createdAt` INTEGER NOT NULL DEFAULT 0)"
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `mcp_servers` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`name` TEXT NOT NULL, `url` TEXT NOT NULL, `headersJson` TEXT NOT NULL DEFAULT '', " +
                "`enabled` INTEGER NOT NULL DEFAULT 1)"
        )
    }
}

/** v13 → v14（v12 批）：日程/待办多体系标签 + 项目文件表（项目数据） */
private val MIGRATION_13_14 = object : androidx.room.migration.Migration(13, 14) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE schedules ADD COLUMN labels TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE todos ADD COLUMN labels TEXT NOT NULL DEFAULT ''")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `project_files` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`projectId` INTEGER NOT NULL, `name` TEXT NOT NULL, `kind` TEXT NOT NULL DEFAULT 'text', " +
                "`content` TEXT NOT NULL DEFAULT '', `size` INTEGER NOT NULL DEFAULT 0, " +
                "`trashed` INTEGER NOT NULL DEFAULT 0, `createdAt` INTEGER NOT NULL DEFAULT 0, " +
                "`updatedAt` INTEGER NOT NULL DEFAULT 0)"
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_project_files_projectId` ON `project_files` (`projectId`)")
    }
}

/** v14 → v15（v12 批E）：笔记置顶 */
private val MIGRATION_14_15 = object : androidx.room.migration.Migration(14, 15) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE notes ADD COLUMN pinned INTEGER NOT NULL DEFAULT 0")
    }
}

/** v15 → v16：倒数日每周几型（-1 = 固定日期型） */
private val MIGRATION_15_16 = object : androidx.room.migration.Migration(15, 16) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE countdowns ADD COLUMN weekday INTEGER NOT NULL DEFAULT -1")
    }
}

/** v16 → v17：倒数日每月几号型 + 笔记本标签（全软共享标签体系） */
private val MIGRATION_16_17 = object : androidx.room.migration.Migration(16, 17) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE countdowns ADD COLUMN dayOfMonth INTEGER NOT NULL DEFAULT -1")
        db.execSQL("ALTER TABLE notebooks ADD COLUMN labels TEXT NOT NULL DEFAULT ''")
    }
}

/** v17 → v18：自定义封面（文字 + 排列方式） */
private val MIGRATION_17_18 = object : androidx.room.migration.Migration(17, 18) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE notebooks ADD COLUMN coverText TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE notebooks ADD COLUMN coverLayout INTEGER NOT NULL DEFAULT 0")
    }
}

/** v18 → v19：目标群聊表（一键总结采集对象） */
private val MIGRATION_18_19 = object : androidx.room.migration.Migration(18, 19) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `chat_targets` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`name` TEXT NOT NULL, `platform` INTEGER NOT NULL DEFAULT 0, " +
                "`enabled` INTEGER NOT NULL DEFAULT 1, `createdAt` INTEGER NOT NULL DEFAULT 0)"
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_chat_targets_enabled` ON `chat_targets` (`enabled`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_chat_targets_name` ON `chat_targets` (`name`)")
    }
}

/** v19 → v20：每日睡眠记录（OPPO 健康节点采集，一天一条） */
private val MIGRATION_19_20 = object : androidx.room.migration.Migration(19, 20) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `sleep_records` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`dateEpochDay` INTEGER NOT NULL, `durationMin` INTEGER NOT NULL, " +
                "`fallMin` INTEGER NOT NULL, `wakeMin` INTEGER NOT NULL, " +
                "`dateText` TEXT NOT NULL DEFAULT '', `createdAt` INTEGER NOT NULL DEFAULT 0)"
        )
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_sleep_records_dateEpochDay` ON `sleep_records` (`dateEpochDay`)")
    }
}

/** v20 → v21：schedules/todos 补 createdAt/updatedAt。
 *  起因是 dump v1 的已知缺口：这两张表原先没有真实时间列，PhoneDump 只能写可推导的稳定时间占位，
 *  PC 侧改完同步回来时，mtime/updatedAt 合并规则必然判定「手机这条更新」→ 手机下次导出把 PC 的改动盖掉。 */
private val MIGRATION_20_21 = object : androidx.room.migration.Migration(20, 21) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE schedules ADD COLUMN createdAt INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE schedules ADD COLUMN updatedAt INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE todos ADD COLUMN createdAt INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE todos ADD COLUMN updatedAt INTEGER NOT NULL DEFAULT 0")
        // 旧行 createdAt 为 0：用各自可推导的稳定时间回填，别留一排 0（0 会被当成 1970 而排在最前）
        db.execSQL("UPDATE schedules SET createdAt = start WHERE createdAt = 0")
        db.execSQL("UPDATE schedules SET updatedAt = start WHERE updatedAt = 0")
        db.execSQL("UPDATE todos SET createdAt = date WHERE createdAt = 0 AND date > 0")
        db.execSQL("UPDATE todos SET updatedAt = due WHERE updatedAt = 0 AND due > 0")
        db.execSQL("UPDATE todos SET updatedAt = date WHERE updatedAt = 0")
        db.execSQL("UPDATE todos SET createdAt = due WHERE createdAt = 0 AND due > 0")
    }
}

/**
 * v21 → v22：打卡数据记录。
 * todos 加 5 个「记录什么」的列（默认全关/全空，**不写这一版的用户行为完全不变**），
 * 新建 checkin_records 表装每次打卡的载荷。
 */
private val MIGRATION_21_22 = object : androidx.room.migration.Migration(21, 22) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE todos ADD COLUMN dataRecordEnabled INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE todos ADD COLUMN dataRecordKind TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE todos ADD COLUMN dataRecordLabel TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE todos ADD COLUMN dataRecordUnit TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE todos ADD COLUMN dataRecordOptions TEXT NOT NULL DEFAULT ''")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `checkin_records` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`todoId` INTEGER NOT NULL, `date` INTEGER NOT NULL, `at` INTEGER NOT NULL DEFAULT 0, " +
                "`kind` TEXT NOT NULL DEFAULT 'time', `numValue` REAL NOT NULL DEFAULT 0, " +
                "`unit` TEXT NOT NULL DEFAULT '', `textValue` TEXT NOT NULL DEFAULT '', " +
                "`tags` TEXT NOT NULL DEFAULT '')"
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_checkin_records_todoId` ON `checkin_records` (`todoId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_checkin_records_date` ON `checkin_records` (`date`)")
    }
}

/**
 * v22 → v23：supervision_checks 加打卡时刻 `at`。
 * 「补交」这件事以前根本无法判定 —— 表里只有"哪天打了"，没有"几点打的"，
 * 于是迟 3 天补的和当天打的完全一样，完成率算不出惩罚。
 * 老行 at=0，账本一律按按时算（宁可少扣，不可冤扣）。
 */
private val MIGRATION_22_23 = object : androidx.room.migration.Migration(22, 23) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE supervision_checks ADD COLUMN at INTEGER NOT NULL DEFAULT 0")
    }
}

/**
 * v23 → v24：**项目文件从数据库表搬到真实文件**。
 *
 * 起因：`project_files` 表和 `FolderRepo` 的真实项目目录各管一半、互不知情，
 * 于是「信息页项目文件树」和「文件管理」看到的不是同一批文件，AI 写的东西磁盘上也不存在。
 * 合并后三者共用 `externalFilesDir/synced/projects/<id>/` 一个真相。
 *
 * **这里只 DROP TABLE，不搬数据** —— 目标目录在 `externalFilesDir`（Android/data 下），
 * Room 的迁移层拿不到 Context，拿不到那个路径。真正的搬运由
 * [ProjectFilesMigrator] 在应用层做（能拿到 Context）。
 *
 * 顺序保证：migrate（drop 表）先执行，App 启动后 migrator 再跑；
 * 此时表已经没了，读不到就等于"没有存量数据要搬"，安全。
 * 反过来若先搬后 drop，中途崩溃会留下"表还在、文件已写"的重复状态。
 */
private val MIGRATION_23_24 = object : androidx.room.migration.Migration(23, 24) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL("DROP TABLE IF EXISTS `project_files`")
    }
}

/**
 * v25：自动化加地理围栏字段。
 *
 * 全部给默认值，所以既有的自动化迁移后 `geoTrigger='none'`、`hasGeoTrigger=false`，
 * **行为与迁移前完全一致**——没设位置的自动化不会被唤醒监听（见 `GeoWatch`）。
 */
private val MIGRATION_24_25 = object : androidx.room.migration.Migration(24, 25) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `automations` ADD COLUMN `geoTrigger` TEXT NOT NULL DEFAULT 'none'")
        db.execSQL("ALTER TABLE `automations` ADD COLUMN `geoLat` REAL NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE `automations` ADD COLUMN `geoLng` REAL NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE `automations` ADD COLUMN `geoRadiusM` INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE `automations` ADD COLUMN `geoInside` INTEGER NOT NULL DEFAULT 0")
    }
}

/**
 * v26：自动化加执行方式（AI 指令 / 步骤流水线）两个字段。
 *
 * 存量行默认 `kind='ai'`、`stepsJson='[]'`，迁移后行为与迁移前**完全一致**；
 * 流水线是纯增量能力，旧的 AI 自动化一个字节都不动。
 */
private val MIGRATION_25_26 = object : androidx.room.migration.Migration(25, 26) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `automations` ADD COLUMN `kind` TEXT NOT NULL DEFAULT 'ai'")
        db.execSQL("ALTER TABLE `automations` ADD COLUMN `stepsJson` TEXT NOT NULL DEFAULT '[]'")
    }
}

/**
 * v27：地点分类可绑坐标（配合高德 POI 搜索/逆地理）。
 *
 * 存量行默认 0.0 = 「未绑坐标」，与 schedules/todos 的 lat/lng 判空口径一致，
 * 迁移后行为完全不变；标签/文件夹分类也恒为 0。
 */
private val MIGRATION_26_27 = object : androidx.room.migration.Migration(26, 27) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `categories` ADD COLUMN `lat` REAL NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE `categories` ADD COLUMN `lng` REAL NOT NULL DEFAULT 0")
    }
}

/**
 * v28：日程/待办各加紧急性、重要性两列（0-4，2=中），另建自由标签的颜色表。
 *
 * **默认 2 = 中**，这样存量行迁移后 `importance(2) → priority(1 中)` 与原值逐行相等，
 * 一行都不用回填，旧的三档优先级视图行为不变。
 *
 * label_colors 是新表，空表起步——自由标签的颜色本来就没存过，没有可迁移的东西。
 */
private val MIGRATION_27_28 = object : androidx.room.migration.Migration(27, 28) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `schedules` ADD COLUMN `urgency` INTEGER NOT NULL DEFAULT 2")
        db.execSQL("ALTER TABLE `schedules` ADD COLUMN `importance` INTEGER NOT NULL DEFAULT 2")
        db.execSQL("ALTER TABLE `todos` ADD COLUMN `urgency` INTEGER NOT NULL DEFAULT 2")
        db.execSQL("ALTER TABLE `todos` ADD COLUMN `importance` INTEGER NOT NULL DEFAULT 2")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `label_colors` (`name` TEXT NOT NULL, `hue` REAL NOT NULL, PRIMARY KEY(`name`))"
        )
        // **按旧 priority 回填重要性**：只设 DEFAULT 2 会把存量的「高/低」抹成「中」，
        // 而 PC 侧读 priority 仍是「高/低」——同一条数据两个屏两个说法（真机 8 行里 3 行踩中）。
        // urgency 没有历史信号，保持 2（中）是对的，不要瞎猜。
        for (t in listOf("todos", "schedules")) {
            db.execSQL(
                "UPDATE `$t` SET `importance` = CASE `priority` WHEN 0 THEN 4 WHEN 1 THEN 2 WHEN 2 THEN 0 ELSE 2 END"
            )
        }
    }
}

/**
 * v29：修 27→28 第一版漏掉的回填。
 *
 * 那一版只给存量行设了 `importance = 2`，于是**已经升到 28 的机器**（包括本轮测试机与
 * 期间装过包的其他机）拿不到修正。这条只动「importance 不等于 priority 投影」的行：
 * 手机端写入时两列始终同步，所以命中它的只可能是被漏回填的存量行，**幂等、不碰用户主动设的值**。
 */
/**
 * v29 → v30：新封面从 0 开始编号，素色默认款让位到 [COVER_SOLID](=-1)。
 *
 * 改写区间是 **0..10**：既包括旧的素色编号 0，也包括 v19.9 已下线的 1..10 图案款。
 * 只动 notebooks 一张表的一列，不碰表结构，幂等（重跑也不会二次改写 -1）。
 */
/**
 * v19.36：封面图标下标。默认 0 = 选图标列表里的第一个（不落 null，列声明为 NOT NULL）。
 *
 * 旧版没有这一列，而 `MC/ICON` 两个款式都要它才能记住用户选过哪个图标。
 */
private val MIGRATION_30_31 = object : androidx.room.migration.Migration(30, 31) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `notebooks` ADD COLUMN `coverIcon` INTEGER NOT NULL DEFAULT 0")
    }
}

/**
 * v35：新建 `automations_run`（自动化执行日志）。
 *
 * ## 三个容易写错的地方
 * 1. **别加 DEFAULT**。实体里 `val ok: Boolean = false` 这种 Kotlin 默认值**不会**生成 SQL DEFAULT
 *    （只有 `@ColumnInfo(defaultValue = ...)` 才会）。这里加了 DEFAULT，Room 校验 schema 时
 *    就会因为 `TableInfo` 多出一个 defaultValue 而 **schema 不匹配直接崩**，而且是升级当场崩。
 * 2. **索引也要建**。实体写了 `indices = [Index("automationId", "at")]`，漏掉下面那句
 *    `CREATE INDEX`，Room 同样判定 schema 不符——索引是 schema 的一部分。
 * 3. **列顺序与实体字段顺序一致**。Room 按位置比对，错位一样判不符。
 */
/**
 * v19.37：多图标排列的每个位置各自的图标下标（CSV）。空串 = 未指定，渲染时退回 nearby()。
 * 沿用 MIGRATION_30_31 的写法（NOT NULL + DEFAULT，实体侧不声明 defaultValue）——
 * 那次真机 user_version 已验证过不会触发 Room 的 schema 校验。
 */
/**
 * v19.42：自动化的事件触发（两列）。
 * 沿用 MIGRATION_30_31 / MIGRATION_32_33 的写法（NOT NULL + DEFAULT，实体侧不声明 defaultValue）——
 * 那两次真机 user_version 都验证过不会触发 Room 的 schema 校验。
 */
/** v34 → v35：项目可归档（正常抽屉不可见，「归档项目」里可见）。 */
private val MIGRATION_34_35 = object : androidx.room.migration.Migration(34, 35) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE projects ADD COLUMN archived INTEGER NOT NULL DEFAULT 0")
    }
}

/**
 * v35 → v36：记忆后台整理进度表（B 项）。
 *
 * 沿用 MIGRATION_30_31 / MIGRATION_32_33 的写法（NOT NULL + DEFAULT，实体侧不声明 defaultValue）。
 * 之所以不进 35：35 已经被 phone-b 的 projects.archived 占号，且一次版本只做一件事，
 * 出问题好回退。实体在 data/MemoryProgress.kt。
 *
 * 刻意不加外键：本库全库无外键（见那里注释），会话删除后由 MemoryProgressRepo.deleteOrphans 兜。
 */
private val MIGRATION_35_36 = object : androidx.room.migration.Migration(35, 36) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `memory_progress` (" +
                "`sessionId` INTEGER NOT NULL, " +
                "`consolidatedAt` INTEGER NOT NULL DEFAULT 0, " +
                "PRIMARY KEY(`sessionId`)" +
                ")"
        )
    }
}

/**
 * v36 → v37：压缩标记表（`compact_markers`）。
 *
 * 沿用 MIGRATION_30_31 / MIGRATION_32_33 的写法（NOT NULL + DEFAULT，实体侧不声明 defaultValue）。
 * 之所以不进 36：36 已被 phone-a 的 memory_progress 占号（他们在 09:02 通了 DB 占用），
 * 一次版本只做一件事，出问题好回退。实体在 data/CompactMarkerEntities.kt。
 *
 * 注意：**这里是本库第一个外键**（sessionId → chat_sessions.id，CASCADE）。
 * 加它的理由与代价都写在那文件的类注释里；会话更新走 @Update，删除走 CASCADE，两条都验证过。
 */
private val MIGRATION_36_37 = object : androidx.room.migration.Migration(36, 37) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `compact_markers` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`sessionId` INTEGER NOT NULL, " +
                "`summary` TEXT NOT NULL, " +
                "`lastCompactedMessageId` INTEGER NOT NULL, " +
                "`firstKeptMessageId` INTEGER NOT NULL DEFAULT 0, " +
                "`compactedCount` INTEGER NOT NULL DEFAULT 0, " +
                "`source` TEXT NOT NULL DEFAULT 'manual', " +
                "`createdAt` INTEGER NOT NULL DEFAULT 0, " +
                "`version` INTEGER NOT NULL DEFAULT 1, " +
                "FOREIGN KEY(`sessionId`) REFERENCES `chat_sessions`(`id`) " +
                "ON UPDATE NO ACTION ON DELETE CASCADE" +
                ")"
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_compact_markers_sessionId` ON `compact_markers` (`sessionId`)")
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_compact_markers_lastCompactedMessageId` " +
                "ON `compact_markers` (`lastCompactedMessageId`)"
        )
    }
}

/**
 * v37 → v38：长期计划模块（plans / plan_steps / plan_checkpoints）。
 *
 * ⚠ **该模块已在 v40 删除**（见 [MIGRATION_39_40]：DROP 这三张表）。
 * 这里保留迁移只是为了让「停在 37 的老库」能顺着 37→38→39→40 升上来；
 * 新库不会再创建这三张表，代码里也没有对应实体了。
 *
 * 号段：36 = memory_progress（A），37 = compact_markers（phone-c），38 = 长期计划（已删除）。
 */
private val MIGRATION_37_38 = object : androidx.room.migration.Migration(37, 38) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `plans` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`projectId` INTEGER NOT NULL, " +
                "`title` TEXT NOT NULL, " +
                "`goal` TEXT NOT NULL DEFAULT '', " +
                "`status` TEXT NOT NULL DEFAULT 'proposed', " +
                "`horizon` TEXT NOT NULL DEFAULT 'month', " +
                "`dueAt` INTEGER NOT NULL DEFAULT 0, " +
                "`checkpointEvery` INTEGER NOT NULL DEFAULT 0, " +
                "`estimatedSteps` INTEGER NOT NULL DEFAULT 0, " +
                "`dueScheduleId` INTEGER NOT NULL DEFAULT 0, " +
                "`reviewTodoId` INTEGER NOT NULL DEFAULT 0, " +
                "`createdAt` INTEGER NOT NULL DEFAULT 0, " +
                "`updatedAt` INTEGER NOT NULL DEFAULT 0" +
                ")"
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_plans_projectId` ON `plans` (`projectId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_plans_status` ON `plans` (`status`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_plans_dueAt` ON `plans` (`dueAt`)")

        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `plan_steps` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`planId` INTEGER NOT NULL, " +
                "`order` INTEGER NOT NULL DEFAULT 0, " +
                "`title` TEXT NOT NULL, " +
                "`detail` TEXT NOT NULL DEFAULT '', " +
                "`status` TEXT NOT NULL DEFAULT 'pending', " +
                "`dependsOn` TEXT NOT NULL DEFAULT '', " +
                "`plannedStart` INTEGER NOT NULL DEFAULT 0, " +
                "`plannedEnd` INTEGER NOT NULL DEFAULT 0, " +
                "`actualStart` INTEGER NOT NULL DEFAULT 0, " +
                "`actualEnd` INTEGER NOT NULL DEFAULT 0, " +
                "`linkedScheduleId` INTEGER NOT NULL DEFAULT 0, " +
                "`linkedTodoId` INTEGER NOT NULL DEFAULT 0, " +
                "`evidenceNoteId` INTEGER NOT NULL DEFAULT 0, " +
                "`createdAt` INTEGER NOT NULL DEFAULT 0, " +
                "`updatedAt` INTEGER NOT NULL DEFAULT 0, " +
                "FOREIGN KEY(`planId`) REFERENCES `plans`(`id`) " +
                "ON UPDATE NO ACTION ON DELETE CASCADE" +
                ")"
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_plan_steps_planId` ON `plan_steps` (`planId`)")
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_plan_steps_planId_order` ON `plan_steps` (`planId`, `order`)"
        )

        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `plan_checkpoints` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`planId` INTEGER NOT NULL, " +
                "`atStepId` INTEGER NOT NULL DEFAULT -1, " +
                "`kind` TEXT NOT NULL DEFAULT 'manual', " +
                "`snapshotJson` TEXT NOT NULL DEFAULT '', " +
                "`note` TEXT NOT NULL DEFAULT '', " +
                "`createdAt` INTEGER NOT NULL DEFAULT 0, " +
                "FOREIGN KEY(`planId`) REFERENCES `plans`(`id`) " +
                "ON UPDATE NO ACTION ON DELETE CASCADE" +
                ")"
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_plan_checkpoints_planId` ON `plan_checkpoints` (`planId`)")
    }
}

/**
 * v38 → v39：摘要游标从"计数"换成"消息 id 锚点"（ref-runtime-study §7.2-M6）。
 *
 * `chat_sessions.lastSummarizeIndex` 是 `effectiveTranscript` 的下标，删过消息（长按删除/重试）
 * 之后它指向别处：物理上从未摘要的消息被当成已摘要丢掉（M5 的根）。锚点用 id 之后，
 * 删消息天然自愈，这一类问题整个消失。
 *
 * 回填规则：给 `lastSummarizeIndex > 0` 的老会话，取"有效消息（同 effectiveTranscript 口径：
 * id > clearBefore、role 收 user/assistant/tool、正文或 toolCalls 非空）里第 N 条"的 id 当锚点；
 * N 比现存条数大（计数虚高/消息被删）就取现存最后一条——那几条本来就已摘要。
 * 取不到（如空会话）回 0 = 下次全量重发。宁可多发一次，不静默丢历史。
 */
/**
 * v40：删掉 v38 的长期计划模块（plans / plan_steps / plan_checkpoints）。
 *
 * 为什么删：这个模块是画蛇添足（用户 2026-10-19 明确要求删除），而且库里只有测试数据。
 * 直接 DROP，不丢用户内容；child 表先删，避免外键顺序问题。
 */
/**
 * v42：项目绑定真实文件夹（用户需求：手机端新建项目像 PC 参照那样选/建真实文件夹，
 * 项目文件与对话镜像都真实落盘在那里）。dirPath 空 = 旧默认 synced/projects/ 行为。
 */
/**
 * v43：常驻自主循环的任务表（用户定向「杀后台仍常驻」的自主循环）。
 *
 * 表结构见 data/AutoEntities.kt；状态机与回收策略见 core/AutoPolicy 与 ai/auto/AutoLoop。
 */
/** v44：地点两级模型——绑定地名（categories.name + 坐标）下面挂多个显示地名 */
private val MIGRATION_43_44 = object : androidx.room.migration.Migration(43, 44) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `categories` ADD COLUMN `displayNames` TEXT NOT NULL DEFAULT ''")
    }
}

/**
 * v44 → v45：记忆系统的冲突四态 / 时间有效性 / 分层 / 证据（2026-10-30）。
 *
 * 设计来源与取值见 `◆/planability-memory-v45-plan.md`：
 * - 列语义照 Graphiti EntityEdge：validAt/invalidAt 是**世界时间**，expiredAt 是**系统时间**；
 * - priority 照 TencentDB L1 的 0..100 分档（-1 = 死命令）；
 * - supersededBy 是 merge 墓碑：被合并掉的记忆**不删行**，只标记被谁取代。
 *
 * 三个容易写错的地方（沿用 MIGRATION_35_36 的教训）：
 * 1. 实体侧不声明 `@ColumnInfo(defaultValue=...)`，这里却必须给 DEFAULT——给已有表加
 *    NOT NULL 列时没有默认值会直接失败；Room 对「实体无默认值、库有默认值」不判不符
 *    （本项目 v30/v34/v35/v43 都是这么干的，真机验证过）。
 * 2. 索引名必须与 Room 默认命名一致（`index_<表>_<列>`），否则 schema 校验当场崩。
 * 3. 新列按实体字段顺序追加；`version` 是列名不是保留字，但要加反引号。
 */
private val MIGRATION_44_45 = object : androidx.room.migration.Migration(44, 45) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `memories` ADD COLUMN `priority` INTEGER NOT NULL DEFAULT 50")
        db.execSQL("ALTER TABLE `memories` ADD COLUMN `scene` TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE `memories` ADD COLUMN `validAt` INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE `memories` ADD COLUMN `invalidAt` INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE `memories` ADD COLUMN `expiredAt` INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE `memories` ADD COLUMN `version` INTEGER NOT NULL DEFAULT 1")
        db.execSQL("ALTER TABLE `memories` ADD COLUMN `supersededBy` INTEGER NOT NULL DEFAULT 0")
        // 回填：旧库里 importance 1..3 → priority 40/60/85；validAt 取 createdAt
        db.execSQL(
            "UPDATE `memories` SET `priority` = CASE `importance` " +
                "WHEN 3 THEN 85 WHEN 2 THEN 60 ELSE 40 END"
        )
        db.execSQL("UPDATE `memories` SET `validAt` = `createdAt` WHERE `validAt` = 0")

        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `memory_evidence` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`memoryId` INTEGER NOT NULL, " +
                "`messageId` INTEGER NOT NULL, " +
                "`kind` TEXT NOT NULL, " +
                "`createdAt` INTEGER NOT NULL" +
                ")"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_memory_evidence_memoryId` " +
                "ON `memory_evidence` (`memoryId`)"
        )
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_memory_evidence_memoryId_messageId` " +
                "ON `memory_evidence` (`memoryId`, `messageId`)"
        )

        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `memory_scenes` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`name` TEXT NOT NULL, " +
                "`summary` TEXT NOT NULL, " +
                "`heat` INTEGER NOT NULL, " +
                "`projectId` INTEGER NOT NULL, " +
                "`validAt` INTEGER NOT NULL, " +
                "`invalidAt` INTEGER NOT NULL, " +
                "`createdAt` INTEGER NOT NULL, " +
                "`updatedAt` INTEGER NOT NULL" +
                ")"
        )
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_memory_scenes_name` " +
                "ON `memory_scenes` (`name`)"
        )

        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `memory_persona` (" +
                "`id` INTEGER NOT NULL, " +
                "`body` TEXT NOT NULL, " +
                "`version` INTEGER NOT NULL, " +
                "`updatedAt` INTEGER NOT NULL, " +
                "PRIMARY KEY(`id`)" +
                ")"
        )
    }
}

/**
 * v45 → v46：待办「百分比完成」（2026-11-02）。
 *
 * `percentDone` 一个字段同时表达开关与数值：
 * -1 = 未启用（默认，旧数据全部落这档）；0..99 = 启用且未完成；打卡完成 = 100。
 * 编辑器按 `>= 0` 显示开关态；完成率计算里未完成且启用 = percent/100 权重。
 * 写法沿用 MIGRATION_35_36 教训：实体侧不声明 defaultValue，这里必须给 DEFAULT。
 */
private val MIGRATION_45_46 = object : androidx.room.migration.Migration(45, 46) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `todos` ADD COLUMN `percentDone` INTEGER NOT NULL DEFAULT -1")
    }
}

/**
 * v46 → v47：会话「按轮选择性压缩」（2026-11-03）。
 *
 * compact_markers 加 firstCompactedMessageId（区间起点，0=旧语义整历史滚动）。
 * 会话界面按区间渲染摘要卡、上下文组装跳过区间并原位注入摘要——引擎侧见
 * ChatEngine.manualCompactRange / ContextEngine.assemble。
 */
private val MIGRATION_46_47 = object : androidx.room.migration.Migration(46, 47) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `compact_markers` ADD COLUMN `firstCompactedMessageId` INTEGER NOT NULL DEFAULT 0")
    }
}

/**
 * v47 → v48：待办百分比进度流水（2026-11-03）。
 *
 * 「每次设置百分比也记录数据；循环/长期待办按每个打卡日记录百分比，展示成列表，AI 可见」。
 * 独立于 checkin_records（后者是"一天一条数据记录"，与百分比历史互相删会打架）。
 */
private val MIGRATION_47_48 = object : androidx.room.migration.Migration(47, 48) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `todo_percent_logs` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`todoId` INTEGER NOT NULL, " +
                "`date` INTEGER NOT NULL, " +
                "`percent` INTEGER NOT NULL, " +
                "`at` INTEGER NOT NULL" +
                ")"
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_todo_percent_logs_todoId` ON `todo_percent_logs` (`todoId`)")
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_todo_percent_logs_todoId_date` " +
                "ON `todo_percent_logs` (`todoId`, `date`)"
        )
    }
}

/**
 * v48 → v49：笔记升级为知识库（2026-11-05）。
 *
 * ① notes.tags（笔记级标签，CSV）② notes_fts 外部内容虚表（标题/正文双路 FTS）
 * ③ note_links 链接图（`[[wikilink]]` + 反链）。
 *
 * 触发器不在这里手写：Room 在 onPostMigrate 里按 @Fts4(contentEntity) 自动建
 * `room_fts_content_sync_notes_fts_*`（与 memories_fts 同一机制，见 MIGRATION_5_6）。
 *
 * **必须 rebuild**：外部内容虚表建出来时索引是空的，触发器只管建表之后的新写入。
 * 存量笔记如果不 rebuild，`notes_fts` 能数出行数（它读主表）但 MATCH 永远零命中——
 * 2026-11-05 真机实测：v48→v49 后 notes_fts_docsize=0，4 条笔记一条都搜不到。
 */
private val MIGRATION_48_49 = object : androidx.room.migration.Migration(48, 49) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `notes` ADD COLUMN `tags` TEXT NOT NULL DEFAULT ''")
        db.execSQL(
            "CREATE VIRTUAL TABLE IF NOT EXISTS `notes_fts` USING FTS4(" +
                "`title` TEXT NOT NULL, `content` TEXT NOT NULL, content=`notes`)"
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `note_links` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`fromId` INTEGER NOT NULL, `toId` INTEGER NOT NULL, " +
                "`target` TEXT NOT NULL DEFAULT '', `at` INTEGER NOT NULL DEFAULT 0)"
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_note_links_fromId` ON `note_links` (`fromId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_note_links_toId` ON `note_links` (`toId`)")
        db.execSQL("INSERT INTO `notes_fts`(`notes_fts`) VALUES('rebuild')")
    }
}

/**
 * v49 → v50：补一次 FTS 重建。
 *
 * v49 的迁移漏了 `rebuild`（见上），已经升到 49 的库（我的真机装过一版）索引是空的。
 * 这里用一条只做 rebuild 的迁移收口：幂等、无 schema 变化。
 */
private val MIGRATION_49_50 = object : androidx.room.migration.Migration(49, 50) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL("INSERT INTO `notes_fts`(`notes_fts`) VALUES('rebuild')")
    }
}

/**
 * v50 → v51：语义检索的笔记向量表。
 *
 * 表里可以一直是空的：模型没下载 / 没算过就查不到，检索自动退回词法。
 * 这也是为什么"装不装模型"不影响升级——迁移只建表，不依赖任何外部文件。
 */
private val MIGRATION_50_51 = object : androidx.room.migration.Migration(50, 51) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `note_embeddings` (" +
                "`noteId` INTEGER NOT NULL, `dim` INTEGER NOT NULL, " +
                "`vec` BLOB NOT NULL, `mtime` INTEGER NOT NULL, " +
                "PRIMARY KEY(`noteId`))"
        )
    }
}

/**
 * v51 → v52：向量表按模型隔离。
 *
 * v51 只有一套向量（主键 noteId），v52 允许轻量/重型两档并存：主键改 (noteId, modelId)。
 * SQLite 改主键要重建表——旧行一律打上 bge-small-zh（它们就是那个模型算的）。
 */
private val MIGRATION_51_52 = object : androidx.room.migration.Migration(51, 52) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `note_embeddings_new` (" +
                "`noteId` INTEGER NOT NULL, `modelId` TEXT NOT NULL, `dim` INTEGER NOT NULL, " +
                "`vec` BLOB NOT NULL, `mtime` INTEGER NOT NULL, PRIMARY KEY(`noteId`, `modelId`))"
        )
        db.execSQL(
            "INSERT OR REPLACE INTO `note_embeddings_new` (`noteId`, `modelId`, `dim`, `vec`, `mtime`) " +
                "SELECT `noteId`, 'bge-small-zh', `dim`, `vec`, `mtime` FROM `note_embeddings`"
        )
        db.execSQL("DROP TABLE `note_embeddings`")
        db.execSQL("ALTER TABLE `note_embeddings_new` RENAME TO `note_embeddings`")
    }
}

/**
 * v53：pending_items 加 6 列（AI 助理中枢）。
 * source 默认 'capture'（存量行都是信息提取来的）；其余数值列默认 0/-1。
 */
private val MIGRATION_52_53 = object : androidx.room.migration.Migration(52, 53) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE pending_items ADD COLUMN source TEXT NOT NULL DEFAULT 'capture'")
        db.execSQL("ALTER TABLE pending_items ADD COLUMN originProjectId INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE pending_items ADD COLUMN originSessionId INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE pending_items ADD COLUMN suggestedStart INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE pending_items ADD COLUMN suggestedEnd INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE pending_items ADD COLUMN confidence REAL NOT NULL DEFAULT -1")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_pending_items_source ON pending_items(source)")
    }
}

/**
 * v54：pending_items 加 payload 列（JSON）。
 * 普通会话的 todo_create/schedule_create 改为产出中枢草稿（统一审核），
 * payload 存创建工具的原始参数，确认入库时忠实重建（重复/优先级/标签全保留）。
 */
private val MIGRATION_53_54 = object : androidx.room.migration.Migration(53, 54) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE pending_items ADD COLUMN payload TEXT NOT NULL DEFAULT ''")
    }
}

/** v54 → v55：智能体卡（JSON 人格）、会话作者注、智能体世界书（设定卡） */
private val MIGRATION_54_55 = object : androidx.room.migration.Migration(54, 55) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE assistants ADD COLUMN card TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE chat_sessions ADD COLUMN authorNote TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE chat_sessions ADD COLUMN authorNoteDepth INTEGER NOT NULL DEFAULT 4")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `lore_entries` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`assistantId` INTEGER NOT NULL, " +
                "`name` TEXT NOT NULL, " +
                "`keys` TEXT NOT NULL, " +
                "`secondKeys` TEXT NOT NULL, " +
                "`logic` TEXT NOT NULL, " +
                "`content` TEXT NOT NULL, " +
                "`constant` INTEGER NOT NULL, " +
                "`position` TEXT NOT NULL, " +
                "`depth` INTEGER NOT NULL, " +
                "`role` TEXT NOT NULL, " +
                "`order` INTEGER NOT NULL, " +
                "`priority` INTEGER NOT NULL, " +
                "`scanDepth` INTEGER NOT NULL, " +
                "`probability` INTEGER NOT NULL, " +
                "`disabled` INTEGER NOT NULL, " +
                "`createdAt` INTEGER NOT NULL, " +
                "`updatedAt` INTEGER NOT NULL)"
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_lore_entries_assistantId` ON `lore_entries` (`assistantId`)")
    }
}

/**
 * v55 → v56：会话级联网开关（2026-11-14 上云 P1）。
 *
 * 与 `thinking` 构成四象限：思考档（关/自适应）× 联网（开/关）。
 * `''`（默认）= 跟随全局云能力开关，行为与上云前一致；`'1'` 强制联网；`'0'` 强制不联网。
 */
private val MIGRATION_55_56 = object : androidx.room.migration.Migration(55, 56) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE chat_sessions ADD COLUMN webSearch TEXT NOT NULL DEFAULT ''")
    }
}

/**
 * v57（2026-11-21 小说助理串味审计）：记忆与场景的唯一性从「全局同名」放宽到「同项目同名」。
 *
 * 旧的 UNIQUE(name) 逼出两类事故：①`upsert`/`touchScene` 按名全局匹配，A 项目写记忆会把
 * B 项目同名行整个换掉（id 复用=静默数据丢失），任何项目写同名场景都灌进同一行（场景串味）；
 * ②同名条目被迫全局改名，检索污染（「我在和用户做设定卡迭代」出现在线代项目）。
 * 索引名必须与实体注解的 Room 默认名一致，否则启动时 schema 校验直接崩。
 */
private val MIGRATION_56_57 = object : androidx.room.migration.Migration(56, 57) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL("DROP INDEX IF EXISTS `index_memories_name`")
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_memories_name_projectId` " +
                "ON `memories` (`name`, `projectId`)"
        )
        db.execSQL("DROP INDEX IF EXISTS `index_memory_scenes_name`")
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_memory_scenes_name_projectId` " +
                "ON `memory_scenes` (`name`, `projectId`)"
        )
    }
}

private val MIGRATION_42_43 = object : androidx.room.migration.Migration(42, 43) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `auto_tasks` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`kind` TEXT NOT NULL, " +
                "`title` TEXT NOT NULL DEFAULT '', " +
                "`payload` TEXT NOT NULL DEFAULT '', " +
                "`status` TEXT NOT NULL DEFAULT 'queued', " +
                "`attempts` INTEGER NOT NULL DEFAULT 0, " +
                "`checkpoint` TEXT NOT NULL DEFAULT '', " +
                "`lastError` TEXT NOT NULL DEFAULT '', " +
                "`recurring` INTEGER NOT NULL DEFAULT 0, " +
                "`intervalMin` INTEGER NOT NULL DEFAULT 0, " +
                "`nextRunAt` INTEGER NOT NULL DEFAULT 0, " +
                "`createdAt` INTEGER NOT NULL DEFAULT 0, " +
                "`updatedAt` INTEGER NOT NULL DEFAULT 0)"
        )
    }
}

private val MIGRATION_41_42 = object : androidx.room.migration.Migration(41, 42) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE projects ADD COLUMN dirPath TEXT NOT NULL DEFAULT ''")
    }
}

private val MIGRATION_40_41 = object : androidx.room.migration.Migration(40, 41) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        // 2026-10-21：地点拆成「绑定地名（地图 POI）」+「显示地名（自由输入）」。
        // place 继续当显示地名，新增 placePoi 存地图选中的 POI 名（含楼层/房间号的信息写在 place 里）。
        db.execSQL("ALTER TABLE schedules ADD COLUMN placePoi TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE todos ADD COLUMN placePoi TEXT NOT NULL DEFAULT ''")
    }
}

private val MIGRATION_39_40 = object : androidx.room.migration.Migration(39, 40) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL("DROP TABLE IF EXISTS plan_checkpoints")
        db.execSQL("DROP TABLE IF EXISTS plan_steps")
        db.execSQL("DROP TABLE IF EXISTS plans")
    }
}

private val MIGRATION_38_39 = object : androidx.room.migration.Migration(38, 39) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE chat_sessions ADD COLUMN lastSummarizedMessageId INTEGER NOT NULL DEFAULT 0")
        // 位置用相关的 COUNT 子查询算，不依赖 LIMIT/OFFSET 里放列表达式（老 SQLite 上行为不一）
        db.execSQL(
            "UPDATE chat_sessions SET lastSummarizedMessageId = COALESCE((" +
                "SELECT m.id FROM chat_messages m " +
                "WHERE m.sessionId = chat_sessions.id " +
                "AND m.id > chat_sessions.clearBefore " +
                "AND m.role IN ('user','assistant','tool') " +
                "AND (IFNULL(m.content,'') <> '' OR IFNULL(m.toolCallsJson,'') <> '') " +
                "AND (SELECT COUNT(*) FROM chat_messages m2 " +
                "WHERE m2.sessionId = m.sessionId AND m2.id <= m.id " +
                "AND m2.id > chat_sessions.clearBefore " +
                "AND m2.role IN ('user','assistant','tool') " +
                "AND (IFNULL(m2.content,'') <> '' OR IFNULL(m2.toolCallsJson,'') <> '') " +
                ") <= chat_sessions.lastSummarizeIndex " +
                "ORDER BY m.id DESC LIMIT 1), 0) " +
                "WHERE chat_sessions.lastSummarizeIndex > 0"
        )
    }
}

private val MIGRATION_33_34 = object : androidx.room.migration.Migration(33, 34) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        // 列顺序必须与实体字段顺序一致：Room 按位置比对，错位一样判不符
        db.execSQL("ALTER TABLE `automations` ADD COLUMN `triggerKind` TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE `automations` ADD COLUMN `triggerArg` TEXT NOT NULL DEFAULT ''")
    }
}

private val MIGRATION_32_33 = object : androidx.room.migration.Migration(32, 33) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `notebooks` ADD COLUMN `coverIcons` TEXT NOT NULL DEFAULT ''")
    }
}

private val MIGRATION_31_32 = object : androidx.room.migration.Migration(31, 32) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `automations_run` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`automationId` INTEGER NOT NULL, " +
                "`name` TEXT NOT NULL, " +
                "`kind` TEXT NOT NULL, " +
                "`at` INTEGER NOT NULL, " +
                "`ok` INTEGER NOT NULL, " +
                "`summary` TEXT NOT NULL, " +
                "`detail` TEXT NOT NULL, " +
                "`durationMs` INTEGER NOT NULL)"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_automations_run_automationId_at` " +
                "ON `automations_run` (`automationId`, `at`)"
        )
    }
}

private val MIGRATION_29_30 = object : androidx.room.migration.Migration(29, 30) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL("UPDATE `notebooks` SET `coverStyle` = -1 WHERE `coverStyle` BETWEEN 0 AND 10")
    }
}

private val MIGRATION_28_29 = object : androidx.room.migration.Migration(28, 29) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        val proj = "CASE `priority` WHEN 0 THEN 4 WHEN 1 THEN 2 WHEN 2 THEN 0 ELSE 2 END"
        for (t in listOf("todos", "schedules")) {
            db.execSQL("UPDATE `$t` SET `importance` = $proj WHERE `importance` <> $proj")
        }
    }
}

/**
 * 应用层迁移入口：把旧表里的项目文件搬成磁盘文件，用 KV 标记保证只做一次。
 *
 * 为什么必须有这一步：v24 之前的 `project_files` 里可能有用户和 AI 写的内容，
 * 直接 DROP 就等于删数据。这里先把每一行写成文件再让表消失。
 */
object ProjectFilesMigrator {
    private const val FLAG = "project_files.migrated.v24"

    suspend fun runOnce() {
        val dao = Graph.db.settingsDao()
        if (dao.get(FLAG) == "1") return
        migrateLegacyTable()
        dao.put(SettingKV(FLAG, "1"))
        Graph.dataVersion.bump()
    }

    /** 读旧表 → 写磁盘。表已 DROP 时直接返回（runCatching 兜住"表不存在"）。 */
    private suspend fun migrateLegacyTable() {
        val rows = runCatching {
            Graph.db.openHelper.readableDatabase.query(
                "SELECT projectId, name, content FROM project_files WHERE trashed = 0"
            )
        }.getOrNull() ?: return
        val out = ArrayList<Triple<Long, String, String>>()
        runCatching {
            while (rows.moveToNext()) {
                val pid = rows.getLong(0)
                val name = rows.getString(1) ?: continue
                val content = rows.getString(2) ?: ""
                out += Triple(pid, name, content)
            }
        }
        runCatching { rows.close() }
        out.forEach { (pid, name, content) ->
            runCatching {
                val dir = ProjectFileRepo.dirOf(pid)
                if (!dir.isDirectory && !dir.mkdirs()) return@runCatching
                val safe = ProjectFileRepo.sanitize(name)
                val f = java.io.File(dir, safe)
                // 已存在就跳过：用户可能已在新目录改过，不该被旧表覆盖回去
                if (!f.exists()) f.writeText(content, Charsets.UTF_8)
            }
        }
    }
}

/** v9 → v10：项目大体系（projects 表 + 五类数据挂 projectId） */
private val MIGRATION_9_10 = object : androidx.room.migration.Migration(9, 10) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `projects` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`name` TEXT NOT NULL, `emoji` TEXT NOT NULL DEFAULT '📁', `pinned` INTEGER NOT NULL DEFAULT 0, " +
                "`createdAt` INTEGER NOT NULL DEFAULT 0, `updatedAt` INTEGER NOT NULL DEFAULT 0)"
        )
        db.execSQL("ALTER TABLE chat_sessions ADD COLUMN projectId INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE notes ADD COLUMN projectId INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE memories ADD COLUMN projectId INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE schedules ADD COLUMN projectId INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE todos ADD COLUMN projectId INTEGER NOT NULL DEFAULT 0")
    }
}
