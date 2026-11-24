package io.github.m0use001.planability.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** 成就类别 → 勋章中心图案（Canvas 程序化绘制，无图片素材） */
enum class MedalIcon { Tree, Star, Fire, Clock, Bolt, Shield, Moon, Pen, Folder, Doc, Wave, Brush, Building, Lotus, Check, Flag }

data class AchievementDef(
    val key: String,
    val title: String,
    val desc: String,
    val icon: MedalIcon
)

object AchievementDefs {
    val ALL = listOf(
        AchievementDef("first_schedule", "初规划", "创建第一个日程", MedalIcon.Clock),
        AchievementDef("schedule_10", "日程熟手", "累计创建 10 个日程", MedalIcon.Star),
        AchievementDef("schedule_50", "日程大师", "累计创建 50 个日程", MedalIcon.Building),
        AchievementDef("schedule_100", "日程百科", "累计创建 100 个日程", MedalIcon.Flag),
        AchievementDef("first_todo", "待办起步", "创建第一个待办", MedalIcon.Check),
        AchievementDef("todo_create_100", "百事可录", "累计创建 100 个待办", MedalIcon.Folder),
        AchievementDef("todo_done_50", "完成五十", "累计完成 50 个待办", MedalIcon.Fire),
        AchievementDef("todo_done_200", "完成二百", "累计完成 200 个待办", MedalIcon.Bolt),
        AchievementDef("streak_7", "七日不断", "打卡监督连续 7 天", MedalIcon.Fire),
        AchievementDef("streak_21", "廿一不断", "打卡监督连续 21 天", MedalIcon.Shield),
        AchievementDef("streak_66", "六六无穷", "打卡监督连续 66 天", MedalIcon.Lotus),
        AchievementDef("first_focus", "第一颗番茄", "完成第一次专注", MedalIcon.Clock),
        AchievementDef("focus_60", "一小时专注", "累计专注 60 分钟", MedalIcon.Star),
        AchievementDef("focus_600", "十小时专注", "累计专注 600 分钟", MedalIcon.Moon),
        AchievementDef("focus_3000", "五十小时专注", "累计专注 3000 分钟", MedalIcon.Tree),
        AchievementDef("focus_100", "百颗番茄", "完成 100 次专注", MedalIcon.Fire),
        AchievementDef("focus_long_60", "长跑专注", "单次专注满 60 分钟", MedalIcon.Bolt),
        AchievementDef("first_note", "落笔成记", "创建第一篇笔记", MedalIcon.Pen),
        AchievementDef("note_10", "笔记十篇", "累计创建 10 篇笔记", MedalIcon.Doc),
        AchievementDef("note_50", "笔记五十篇", "累计创建 50 篇笔记", MedalIcon.Doc),
        AchievementDef("notebook_5", "笔记本达人", "创建 5 个笔记本", MedalIcon.Folder),
        AchievementDef("note_pdf_1", "PDF 批注首秀", "导入第一个 PDF 批注笔记", MedalIcon.Pen),
        AchievementDef("first_freeform", "无边画布", "完成第一幅手绘", MedalIcon.Brush),
        AchievementDef("first_countdown", "值得纪念", "创建第一个倒数日", MedalIcon.Flag),
        AchievementDef("countdown_5", "倒数日常伴", "创建 5 个倒数日", MedalIcon.Star),
        AchievementDef("first_digest", "信息提纯", "完成第一次群聊提纯", MedalIcon.Wave),
        AchievementDef("digest_20", "提纯熟手", "完成 20 次群聊提纯", MedalIcon.Wave),
        AchievementDef("pending_50", "纷至沓来", "累计接收 50 条待处理条目", MedalIcon.Folder),
        AchievementDef("hourtask_10", "整点任务狂", "完成 10 个整点任务", MedalIcon.Check),
        AchievementDef("timeline_50", "时间轴画家", "累计创建 50 个时间轴事件", MedalIcon.Tree),
        AchievementDef("first_import", "确认入库", "首次确认待处理条目入库", MedalIcon.Folder),
        AchievementDef("screen_limit_set", "屏幕自律", "设置过屏幕使用时间上限", MedalIcon.Shield),
        AchievementDef("reminder_set", "精打细算", "自定义过提醒时间", MedalIcon.Clock),
        AchievementDef("first_sleep", "安睡初记", "采集第一次睡眠数据", MedalIcon.Moon),
        AchievementDef("sleep_7", "安睡者", "累计采集 7 天睡眠数据", MedalIcon.Moon),
        AchievementDef("first_backup", "有备无患", "完成第一次全量备份", MedalIcon.Shield)
    )

    fun byKey(key: String): AchievementDef? = ALL.firstOrNull { it.key == key }
}

/** 解锁判定分散在各域写入路径（写后 checkAll），集中登记到这里。 */
class AchievementCenter(
    private val db: AppDatabase,
    private val scope: CoroutineScope
) {
    private val dao = db.achievementDao()

    suspend fun unlocked(): Map<String, Long> = dao.all().associate { it.key to it.unlockedAt }

    suspend fun checkAll() {
        val unlocks = mutableListOf<String>()
        suspend fun unlock(key: String) {
            dao.insert(AchievementRec(key, System.currentTimeMillis()))
            unlocks += key
        }
        val scheduleCount = db.scheduleDao().count()
        if (scheduleCount >= 1) unlock("first_schedule")
        if (scheduleCount >= 10) unlock("schedule_10")
        if (scheduleCount >= 50) unlock("schedule_50")
        if (scheduleCount >= 100) unlock("schedule_100")
        val todoCount = db.todoDao().count()
        if (todoCount >= 1) unlock("first_todo")
        if (todoCount >= 100) unlock("todo_create_100")
        if (db.todoDao().doneCount() >= 50) unlock("todo_done_50")
        if (db.todoDao().doneCount() >= 200) unlock("todo_done_200")
        val streak = maxStreak()
        if (streak >= 7) unlock("streak_7")
        if (streak >= 21) unlock("streak_21")
        if (streak >= 66) unlock("streak_66")
        val focusMin = db.focusSessionDao().totalMinutes()
        if (db.focusSessionDao().completedCount() >= 1) unlock("first_focus")
        if (focusMin >= 60) unlock("focus_60")
        if (focusMin >= 600) unlock("focus_600")
        if (focusMin >= 3000) unlock("focus_3000")
        if (db.focusSessionDao().completedCount() >= 100) unlock("focus_100")
        if ((db.focusSessionDao().maxCompletedPlanned() ?: 0) >= 60) unlock("focus_long_60")
        val noteCount = db.noteDao().count()
        if (noteCount >= 1) unlock("first_note")
        if (noteCount >= 10) unlock("note_10")
        if (noteCount >= 50) unlock("note_50")
        if (db.notebookDao().count() >= 5) unlock("notebook_5")
        if (db.noteDao().allIncludingTrashed().any { it.kind == "pdf" }) unlock("note_pdf_1")
        if (db.noteDao().allIncludingTrashed().any { it.kind == "freeform" && it.inkJson.isNotBlank() }) unlock("first_freeform")
        val countdownCount = db.countdownDao().allOrdered().size
        if (countdownCount >= 1) unlock("first_countdown")
        if (countdownCount >= 5) unlock("countdown_5")
        val digestCount = db.chatDigestDao().count()
        if (digestCount >= 1) unlock("first_digest")
        if (digestCount >= 20) unlock("digest_20")
        if (db.pendingItemDao().all().size >= 50) unlock("pending_50")
        if (db.hourTaskDao().doneCount() >= 10) unlock("hourtask_10")
        if (db.timelineEventDao().count() >= 50) unlock("timeline_50")
        if (db.pendingItemDao().importedCount() >= 1) unlock("first_import")
        if (db.settingsDao().get("goal.screenLimit") != null) unlock("screen_limit_set")
        if (db.scheduleDao().customRemindCount() > 0 || db.todoDao().customRemindCount() > 0) unlock("reminder_set")
        val sleepCount = db.sleepRecordDao().count()
        if (sleepCount >= 1) unlock("first_sleep")
        if (sleepCount >= 7) unlock("sleep_7")
    }

    private suspend fun maxStreak(): Int {
        val checks = db.supervisionDao().all().map { it.date }.toSortedSet()
        if (checks.isEmpty()) return 0
        var best = 1
        var run = 1
        val it = checks.iterator()
        var prev = it.next()
        while (it.hasNext()) {
            val cur = it.next()
            run = if (cur - prev == 86_400_000L) run + 1 else 1
            if (run > best) best = run
            prev = cur
        }
        return best
    }
}

/** 首次备份成就在导出成功后手动触发 */
suspend fun unlockBackupAchievement() {
    Graph.db.achievementDao().insert(AchievementRec("first_backup", System.currentTimeMillis()))
}
