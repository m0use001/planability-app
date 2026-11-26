package io.github.m0use001.planability.ai.ptl

import io.github.m0use001.planability.ai.rag.KbAsk
import io.github.m0use001.planability.ai.rag.KbSweep
import io.github.m0use001.planability.ai.rag.WikiLinks
import io.github.m0use001.planability.core.AppTime
import io.github.m0use001.planability.core.toLocalDate
import io.github.m0use001.planability.data.COVER_SOLID
import io.github.m0use001.planability.data.Graph
import io.github.m0use001.planability.data.NoteEntity
import io.github.m0use001.planability.data.PendingItem
import io.github.m0use001.planability.data.WeeklyRecord
import io.github.m0use001.planability.data.XpCenter
import org.json.JSONObject

/**
 * 「生活数据」第二批工具（2026-10-31 用户指名补齐）：专注计时、周小结、成就/经验、
 * 草稿确认流写入、回收站查询恢复、笔记本管理 + 笔记编辑/删除。
 *
 * 为什么单独一个文件：ToolExecImpls.execute 已三次撞 JVM 单方法 64KB 上限
 * （FsShellTools / LibraryTools / PlanTools 都是这么拆出来的），这批继续分文件。
 *
 * 设计取舍：
 * - 专注**只补记不遥控**：AI 开一个"正在计时的专注"没有意义（用户不在场），
 *   focus_add 补记的是刚结束的一次（start=now-时长），与 FocusRepo.recordSession 同口径。
 * - 草稿确认流（pending_add）是 AI 唯一能"半写"的入口：落到待确认列表，由用户
 *   批准后才真正进日程/待办——比直接 create 多一道用户闸门。
 * - 回收站 restore 只做 todo/schedule/note 三类（与 UI 回收站对齐）。
 * - 笔记本**不给删除工具**：deleteNotebook 连带笔记的走向对 AI 不可见，删错就是事故；
 *   建与查先接，删除留给用户。
 */
internal object LifeTools {

    /** 处理本批工具；不认识的名字返回 null（调用方继续原分发）。 */
    suspend fun run(name: String, a: JSONObject, sessionId: Long): ToolExecResult? {
        return when (name) {
            "focus_query" -> focusQuery(a)
            "focus_add" -> focusAdd(a, sessionId)
            "weekly_query" -> weeklyQuery(a)
            "achievement_query" -> achievementQuery()
            "pending_add" -> pendingAdd(a, sessionId)
            "pending_commit" -> pendingCommit(a, sessionId)
            "pending_ignore" -> pendingIgnore(a, sessionId)
            "pending_suggest" -> pendingSuggest(a, sessionId)
            "pending_update" -> pendingUpdate(a, sessionId)
            "pending_purge" -> pendingPurge(a, sessionId)
            "trash_query" -> trashQuery(a)
            "trash_restore" -> trashRestore(a, sessionId)
            "notebook_list" -> notebookList()
            "notebook_create" -> notebookCreate(a, sessionId)
            "note_edit" -> noteEdit(a, sessionId)
            "note_delete" -> noteDelete(a, sessionId)
            // v49 知识库
            "note_link" -> noteLink(a, sessionId)
            "kb_ask" -> kbAsk(a, sessionId)
            else -> null
        }
    }

    // ---------- 专注 ----------

    private suspend fun focusQuery(a: JSONObject): ToolExecResult {
        val days = (a.optInt("days", 7)).coerceIn(1, 90)
        val today = AppTime.today()
        val sessions = Graph.focus.sessionsBetween(today.minusDays((days - 1).toLong()), today)
        val total = Graph.focus.totalMinutes()
        val done = Graph.focus.completedCount()
        if (sessions.isEmpty()) {
            return ToolExecResult.ok(
                "最近 $days 天没有专注记录（累计：$total 分钟，完成 $done 次）", "无专注记录"
            )
        }
        val byDay = sessions.groupBy { AppTime.dayKeyOf(it.start) }
        val lines = byDay.entries.sortedByDescending { it.key }.take(days).map { (dk, ss) ->
            "  " + ToolExecImpls.fmtDay(dk) + "：" + ss.sumOf { it.plannedMin } + " 分钟 × " + ss.size + " 次" +
                "（完成 " + ss.count { it.completed } + "）"
        }
        return ToolExecResult.ok(
            "最近 $days 天的专注：\n" + lines.joinToString("\n") +
                "\n累计 $total 分钟，完成 $done 次",
            "查专注 " + days + " 天"
        )
    }

    private suspend fun focusAdd(a: JSONObject, sessionId: Long): ToolExecResult {
        val min = a.optInt("planned_min", 0)
        if (min <= 0 || min > 240) return ToolExecResult.fail("planned_min 要是 1-240 的分钟数")
        val completed = a.optBoolean("completed", true)
        val id = Graph.focus.recordSession(min, completed)
        ToolExecutor.registerUndo(
            UndoEntry(sessionId, "删掉这条专注补记") { Graph.db.focusSessionDao().deleteById(id) }
        )
        return ToolExecResult.ok(
            "已补记一次专注：" + min + " 分钟" + (if (completed) "（完成）" else "（未完成）"),
            "记专注 " + min + " 分钟"
        )
    }

    // ---------- 周小结 ----------

    private suspend fun weeklyQuery(a: JSONObject): ToolExecResult {
        val rs = Graph.info.weeklyRecords()
        if (rs.isEmpty()) {
            return ToolExecResult.ok(
                "还没有周小结（信息页「周小结」生成后会存到这里）", "无周小结"
            )
        }
        // v55：总数 + offset 分页 + 截断标注——原来静默 take(8)，两个月前的周报对 AI 隐形
        val offset = a.optInt("offset", 0).coerceIn(0, rs.size)
        val page = rs.drop(offset).take(4)
        return ToolExecResult.ok(
            "共 " + rs.size + " 篇周小结，本页第 " + (offset + 1) + "-" + (offset + page.size) + " 篇：\n\n" +
                page.joinToString("\n\n") { r ->
                    val body = r.text.take(400)
                    "【" + AppTime.dayKeyOf(r.weekStart).toLocalDate() + " 那一周 · " +
                        (if (r.source == "llm") "AI" else "模板") + "】\n" + body +
                        (if (r.text.length > 400) "…（正文已截 400 字）" else "")
                } +
                (if (offset + page.size < rs.size) "\n\n看更早的周报：offset=" + (offset + page.size) else ""),
            "查周小结"
        )
    }

    // ---------- 成就 / 经验 ----------

    private suspend fun achievementQuery(): ToolExecResult {
        val xp = XpCenter.total()
        val lvl = XpCenter.levelOf(xp)
        val (cur, need) = XpCenter.progressOf(xp)
        val unlocked = Graph.achievements.unlocked()
        val medals = io.github.m0use001.planability.data.AchievementDefs.ALL.mapNotNull { def ->
            val at = unlocked[def.key] ?: return@mapNotNull null
            "  " + def.title + "（" + def.desc + "）"
        }
        return ToolExecResult.ok(
            "经验 $xp，等级 $lvl（本级 $cur/$need）\n已解锁徽章 " + medals.size + "/" +
                io.github.m0use001.planability.data.AchievementDefs.ALL.size + "：\n" +
                medals.joinToString("\n"),
            "等级 $lvl"
        )
    }

    // ---------- 草稿确认流 ----------

    private suspend fun pendingAdd(a: JSONObject, sessionId: Long): ToolExecResult {
        val title = a.optString("title").trim()
        if (title.isEmpty()) return ToolExecResult.fail("草稿标题为空")
        val kind = when (a.optString("kind", "todo").lowercase()) {
            "schedule", "日程" -> "schedule"
            "todo", "待办" -> "todo"
            else -> return ToolExecResult.fail("kind 要 todo 或 schedule")
        }
        val createdAt = AppTime.nowMillis()
        // v53：带来源标记——AI 工具在项目会话里调 pending_add，记 session + 项目
        val projId = runCatching {
            Graph.chat.session(sessionId)?.projectId ?: 0L
        }.getOrDefault(0L)
        val item = PendingItem(
            title = title, kind = kind,
            timeText = a.optString("timeText").trim(),
            place = a.optString("place").trim(),
            extra = a.optString("extra").trim(),
            status = "pending", createdAt = createdAt,
            source = "session",
            originProjectId = projId,
            originSessionId = sessionId,
            confidence = a.opt("confidence")?.let {
                (it as? Number)?.toFloat()
            } ?: -1f
        )
        // 2026-11-11 判重入库（用户「待处理审核中一堆重复的」）：重复命中不新插、返回空，
        // 工具明确回「已有」——不能按 createdAt 盲读，否则 undo 会挂到旧草稿上
        val id = Graph.info.addPendingItems(listOf(item)).firstOrNull() ?: -1L
        if (id > 0) {
            ToolExecutor.registerUndo(
                UndoEntry(sessionId, "撤掉草稿「" + title + "」") { Graph.info.deletePending(id) }
            )
            ToolExecutor.registerUndoPersisted(sessionId, "删除草稿「" + title + "」", "pending_add", id.toString())
        } else {
            val exist = Graph.info.pendingItems().firstOrNull {
                io.github.m0use001.planability.data.PendingDedup.isDuplicate(it, item)
            }
            return ToolExecResult.ok(
                "已有同名草稿" + (exist?.let { "（草稿 #" + it.id + "）" } ?: "") + "：「" + title +
                    "」，未重复添加（在待处理审核里确认即可）",
                "已有同名草稿"
            )
        }
        return ToolExecResult.ok(
            "已放进待确认草稿：「" + title + "」（" + (if (kind == "todo") "待办" else "日程") +
                "，用户在信息页批准后才会真正创建）",
            "存草稿「" + title + "」"
        )
    }

    // ---------- v53 助理中枢：草稿确认流操作 ----------

    /** pending_commit：把草稿正式入库（日程/待办），可顺带修正字段；成功后草稿标记 imported。
     *  v54 起入库逻辑统一走 PendingImporter（与中枢卡片 ✓ 同一条通道，payload 忠实重建）。 */
    private suspend fun pendingCommit(a: JSONObject, sessionId: Long): ToolExecResult {
        val id = a.optLong("id", -1)
        val item = Graph.info.pendingItems().firstOrNull { it.id == id }
            ?: return ToolExecResult.fail("待处理草稿 id=" + id + " 不存在（编号来自 pending_query 或【待处理清单】）")
        if (item.status != "pending") {
            return ToolExecResult.fail("草稿「" + item.title + "」已处理过（" + item.status + "），别重复入库")
        }
        val (_, text, clash) = try {
            PendingImporter.commit(item, overrides = a, sessionId = sessionId)
        } catch (e: IllegalArgumentException) {
            return ToolExecResult.fail(e.message ?: "入库参数有误")
        }
        return ToolExecResult.ok(text + (clash?.let { "\n$it" } ?: ""), "入库「" + item.title + "」")
    }

    /** pending_ignore：草稿标记忽略 */
    private suspend fun pendingIgnore(a: JSONObject, sessionId: Long): ToolExecResult {
        val id = a.optLong("id", -1)
        val item = Graph.info.pendingItems().firstOrNull { it.id == id }
            ?: return ToolExecResult.fail("待处理草稿 id=" + id + " 不存在")
        Graph.info.markPending(id, "ignored")
        ToolExecutor.registerUndo(
            UndoEntry(sessionId, "恢复草稿「" + item.title + "」") {
                runCatching { Graph.info.updatePending(item.copy(status = "pending")) }
            }
        )
        return ToolExecResult.ok("已忽略「" + item.title + "」（可在待处理的已忽略 tab 找回）", "忽略「" + item.title + "」")
    }

    /** pending_purge：按条件批量忽略草稿（v55，用户反馈「AI 只能说你自己手动删」）。
     *  护栏：至少一个条件（防无条件全清）、单次上限 500 条、全部可 undo 恢复。 */
    private suspend fun pendingPurge(a: JSONObject, sessionId: Long): ToolExecResult {
        val idsRaw = a.optString("ids", "").trim()
        val monthRaw = a.optString("month", "").trim()
        val kind = a.optString("kind", "").trim().lowercase()
        val source = a.optString("source", "").trim().lowercase()
        val hasTime = a.optString("has_time", "").trim().lowercase()
            .let { PendingFilter.normTriState(it) }
        val ids = idsRaw.split(',', '，').asSequence()
            .map { it.trim().removePrefix("#") }.filter { it.isNotEmpty() }
            .mapNotNull { it.toLongOrNull() }.toSet()
        val month = if (monthRaw.isEmpty()) null else PendingFilter.normMonth(monthRaw)
        if (monthRaw.isNotEmpty() && month == null) {
            return ToolExecResult.fail("month 看不懂：" + monthRaw + "（用 10 或 2026-10）")
        }
        // 2026-11-22：日期区间（before_date/after_date）——month 是整月粒度太粗，
        // 用户「11 月上旬已过期、中下旬还有效」要能按日清。
        val today = io.github.m0use001.planability.core.AppTime.today()
        val beforeRaw = a.optString("before_date", "").trim()
        val beforeDate = if (beforeRaw.isEmpty()) null else PendingFilter.parseDay(beforeRaw, today)
        if (beforeRaw.isNotEmpty() && beforeDate == null) {
            return ToolExecResult.fail("before_date 看不懂：$beforeRaw（用 2026-11-15 或 今天）")
        }
        val afterRaw = a.optString("after_date", "").trim()
        val afterDate = if (afterRaw.isEmpty()) null else PendingFilter.parseDay(afterRaw, today)
        if (afterRaw.isNotEmpty() && afterDate == null) {
            return ToolExecResult.fail("after_date 看不懂：$afterRaw（用 2026-11-15 或 今天）")
        }
        val hasCond = ids.isNotEmpty() || month != null ||
            kind in listOf("todo", "schedule") || source in listOf("session", "capture", "manual") ||
            hasTime != null || beforeDate != null || afterDate != null
        if (!hasCond) {
            return ToolExecResult.fail(
                "pending_purge 至少给一个条件（ids / month / before_date / after_date / kind / source / has_time），不允许无条件全清。" +
                    "先用 pending_query 用同样的条件核对命中条数"
            )
        }
        var hit = Graph.info.pendingItems()
        if (ids.isNotEmpty()) hit = hit.filter { it.id in ids }
        if (month != null) hit = hit.filter { PendingFilter.timeTextInMonth(it.timeText, month) }
        if (kind in listOf("todo", "schedule")) hit = hit.filter { it.kind == kind }
        if (source in listOf("session", "capture", "manual")) hit = hit.filter { it.source == source }
        if (hasTime == true) hit = hit.filter { it.timeText.isNotBlank() }
        if (hasTime == false) hit = hit.filter { it.timeText.isBlank() }
        if (beforeDate != null || afterDate != null) {
            hit = hit.filter { p ->
                val yr = runCatching {
                    java.time.Instant.ofEpochMilli(p.createdAt)
                        .atZone(java.time.ZoneId.systemDefault()).year
                }.getOrDefault(today.year)
                val d = PendingFilter.firstDateInText(p.timeText, yr) ?: return@filter false
                (beforeDate == null || d.isBefore(beforeDate)) &&
                    (afterDate == null || d.isAfter(afterDate))
            }
        }
        val condDesc = listOfNotNull(
            if (ids.isNotEmpty()) "编号 ${ids.size} 个" else null,
            if (month != null) month + "月" else null,
            if (beforeDate != null) "早于" + beforeDate else null,
            if (afterDate != null) "晚于" + afterDate else null,
            if (kind in listOf("todo", "schedule")) "类型=" + kind else null,
            if (source in listOf("session", "capture", "manual")) "来源=" + source else null,
            if (hasTime == true) "有时间" else if (hasTime == false) "无时间" else null
        ).joinToString("+")
        if (hit.isEmpty()) {
            return ToolExecResult.ok("没有命中任何待处理草稿（条件：" + condDesc + "）", "无命中")
        }
        if (hit.size > 500) {
            return ToolExecResult.fail(
                "命中 " + hit.size + " 条太多（单次上限 500）：请加月份/类型/来源条件分批，或先用 pending_query 核对"
            )
        }
        hit.forEach { Graph.info.markPending(it.id, "ignored") }
        ToolExecutor.registerUndo(
            UndoEntry(sessionId, "恢复 " + hit.size + " 条被批量忽略的草稿") {
                runCatching { hit.forEach { Graph.info.updatePending(it.copy(status = "pending")) } }
            }
        )
        val sample = hit.take(5).joinToString("\n") { "  #" + it.id + "「" + it.title + "」 " + it.timeText }
        return ToolExecResult.ok(
            "已批量忽略 " + hit.size + " 条（条件：" + condDesc + "；可在待处理「已忽略」tab 或 undo_last_write 恢复）：\n" +
                sample + (if (hit.size > 5) "\n  …其余 " + (hit.size - 5) + " 条" else ""),
            "批量忽略 " + hit.size + " 条"
        )
    }

    /**
     * pending_update：修改草稿内容（2026-11-12 ◆ 实录：模型两次调 `pending_update`
     * →「未知工具」，然后对用户说「我没有能修改草稿时刻的工具」，只能让用户自己去中枢点）。
     *
     * 关键坑：入库（[PendingImporter.commit]）按 **payload 优先** 重建——只改 PendingItem 的
     * title/timeText 而不动 payload，入库时还是旧标题/旧时间（静默失效，改了等于没改）。
     * 所以这里同步补 payload：title/place 直接覆盖；时间改动则**删掉** payload 里的
     * date/due/time/start/end，并把解析结果写进 suggestedStart/End（commit 的兜底来源）。
     */
    private suspend fun pendingUpdate(a: JSONObject, sessionId: Long): ToolExecResult {
        val id = a.optLong("id", -1)
        val item = Graph.info.pendingItems().firstOrNull { it.id == id }
            ?: return ToolExecResult.fail("待处理草稿 id=" + id + " 不存在（编号来自 pending_query 或【待处理清单】）")
        if (item.status != "pending") {
            return ToolExecResult.fail("草稿「" + item.title + "」已处理过（" + item.status + "），不能再改")
        }
        var nt = item
        var titleEdited = false
        a.optString("title").takeIf { it.isNotBlank() }?.let { nt = nt.copy(title = it.trim()); titleEdited = true }
        a.optString("place").takeIf { it.isNotBlank() }?.let { nt = nt.copy(place = it.trim()) }
        a.optString("kind").takeIf { it.isNotBlank() }?.let { k ->
            if (k != "todo" && k != "schedule") return ToolExecResult.fail("kind 只能是 todo/schedule")
            nt = nt.copy(kind = k)
        }
        var timeEdited = false
        a.optString("time").takeIf { it.isNotBlank() }?.let { raw ->
            val at = ToolExecImpls.parseDateTime(raw)
                ?: return ToolExecResult.fail("时间看不懂：「" + raw + "」（如 17:10 / 周四 16:10 / 11月13日 15:00）")
            val end = at + 3600_000L
            nt = nt.copy(
                timeText = raw.trim(),
                suggestedStart = at,
                suggestedEnd = maxOf(nt.suggestedEnd.takeIf { it > at } ?: end, at + 5 * 60_000L)
            )
            timeEdited = true
        }
        val pb = runCatching { JSONObject(item.payload) }.getOrNull()
        if (pb != null) {
            if (titleEdited) pb.put("title", nt.title)
            if (nt.place != item.place) pb.put("place", nt.place)
            if (timeEdited) {
                // payload 优先于草稿字段——时间键必须删掉，否则 commit 还原旧时间
                listOf("date", "due", "time", "start", "end").forEach { k -> pb.remove(k) }
            }
            nt = nt.copy(payload = pb.toString())
        }
        if (nt == item) return ToolExecResult.fail("没有任何要改的字段（至少传一个）")
        Graph.info.updatePending(nt)
        ToolExecutor.registerUndo(UndoEntry(sessionId, "还原草稿「" + item.title + "」") {
            runCatching { Graph.info.updatePending(item) }
        })
        val diffs = mutableListOf<String>()
        if (nt.title != item.title) diffs += "标题「" + item.title + "」→「" + nt.title + "」"
        if (nt.timeText != item.timeText) diffs += "时间「" + item.timeText.ifBlank { "无" } + "」→「" + nt.timeText + "」"
        if (nt.place != item.place) diffs += "地点→" + nt.place
        if (nt.kind != item.kind) diffs += "类型→" + nt.kind
        return ToolExecResult.ok(
            "已改草稿 #" + id + "：" + diffs.joinToString("；") +
                (if (nt.suggestedStart > 0) "（入库时按 " + ToolExecImpls.fmtDay(nt.suggestedStart) + " " +
                    AppTime.millisToTime(nt.suggestedStart) + " 起算）" else "") +
                "。仍是草稿，pending_commit 后才进时间轴",
            "改草稿「" + nt.title + "」"
        )
    }

    /** pending_suggest：给草稿写建议时段（不入库），助理中枢卡片上显示 */
    private suspend fun pendingSuggest(a: JSONObject, sessionId: Long): ToolExecResult {
        val id = a.optLong("id", -1)
        val item = Graph.info.pendingItems().firstOrNull { it.id == id }
            ?: return ToolExecResult.fail("待处理草稿 id=" + id + " 不存在")
        if (item.status != "pending") return ToolExecResult.fail("草稿「" + item.title + "」已处理过，不用再建议")
        val start = ToolExecImpls.parseDateTime(a.optString("start"))
            ?: return ToolExecResult.fail("建议开始时间看不懂：" + a.optString("start"))
        val end = a.optString("end").takeIf { it.isNotBlank() }
            ?.let { ToolExecImpls.parseDateTime(it) } ?: (start + 3600_000L)
        Graph.info.updatePending(item.copy(suggestedStart = start, suggestedEnd = maxOf(end, start + 5 * 60_000L)))
        return ToolExecResult.ok(
            "已给「" + item.title + "」标记建议时段：" + ToolExecImpls.fmtDay(start) + " " +
                AppTime.millisToTime(start) + "-" + AppTime.millisToTime(maxOf(end, start + 5 * 60_000L)) +
                "（卡片上会显示，用户认可后用 pending_commit 入库）",
            "建议时段已标"
        )
    }

    // ---------- 回收站 ----------

    /** v55：加 offset 分页与总数——原来每类只吐 10 条且不报总数，第 11 条对 AI 隐形
     *  而 trash_restore 又需要编号，等于「删多了就找不回来」（pending_query 锁死同类）。 */
    private suspend fun trashQuery(a: JSONObject): ToolExecResult {
        val offset = a.optInt("offset", 0).coerceIn(0, 100000)
        val todos = Graph.plan.trashedTodos()
        val schedules = Graph.plan.trashedSchedules()
        val notes = Graph.notes.trashedNotes()
        val out = mutableListOf<String>()
        fun page(name: String, all: List<Pair<Long, String>>, fmt: (Pair<Long, String>) -> String): String? {
            if (all.isEmpty()) return null
            val page = all.drop(offset).take(10)
            if (page.isEmpty()) return null
            val more = all.size - offset - page.size
            return "$name（共 " + all.size + " 条" +
                (if (offset > 0) "，从第 " + (offset + 1) + " 条起" else "") + "）：\n" +
                page.joinToString("\n") { "  " + fmt(it) } +
                (if (more > 0) "\n  …还有 " + more + " 条，用 offset=" + (offset + page.size) + " 翻页" else "")
        }
        page("待办", todos.map { it.id to it.title }) { (id, t) -> t + "（编号 " + id + "）" }
            ?.let { out += it }
        page("日程", schedules.map { it.id to it.title }) { (id, t) -> t + "（编号 " + id + "）" }
            ?.let { out += it }
        page("笔记", notes.map { it.id to it.title }) { (id, t) -> "《" + t + "》（编号 " + id + "）" }
            ?.let { out += it }
        if (out.isEmpty()) {
            return ToolExecResult.ok(
                if (todos.isNotEmpty() || schedules.isNotEmpty() || notes.isNotEmpty())
                    "回收站有内容，但 offset=" + offset + " 已越过各类末尾（本页为空）"
                else "回收站是空的",
                "回收站空"
            )
        }
        return ToolExecResult.ok(out.joinToString("\n\n"), "查回收站")
    }

    private suspend fun trashRestore(a: JSONObject, sessionId: Long): ToolExecResult {
        val id = a.optLong("id", -1)
        val kind = when (a.optString("kind", "todo").lowercase()) {
            "todo", "待办" -> "todo"
            "schedule", "日程" -> "schedule"
            "note", "笔记" -> "note"
            else -> return ToolExecResult.fail("kind 要 todo/schedule/note")
        }
        when (kind) {
            "todo" -> {
                val t = Graph.plan.todoById(id) ?: return ToolExecResult.fail("待办 id=" + id + " 不存在")
                if (!t.trashed) return ToolExecResult.fail("「" + t.title + "」不在回收站里（可能已恢复）")
                Graph.plan.restoreTodo(id)
            }
            "schedule" -> {
                val s = Graph.plan.scheduleById(id) ?: return ToolExecResult.fail("日程 id=" + id + " 不存在")
                if (!s.trashed) return ToolExecResult.fail("「" + s.title + "」不在回收站里（可能已恢复）")
                Graph.plan.restoreSchedule(id)
            }
            else -> {
                val n = Graph.notes.noteById(id) ?: return ToolExecResult.fail("笔记 id=" + id + " 不存在")
                if (!n.trashed) return ToolExecResult.fail("《" + n.title + "》不在回收站里（可能已恢复）")
                Graph.notes.restoreNote(id)
            }
        }
        ToolExecutor.registerUndo(
            UndoEntry(sessionId, "重新放回回收站") {
                // 撤销恢复 = 再删一次（软删，同一条数据）
                when (kind) {
                    "todo" -> Graph.plan.deleteTodo(id)
                    "schedule" -> Graph.plan.deleteSchedule(id)
                    else -> Graph.notes.trashNote(id)
                }
            }
        )
        return ToolExecResult.ok("已从回收站恢复（编号 " + id + "）", "已恢复")
    }

    // ---------- 笔记本 + 笔记补全 ----------

    private suspend fun notebookList(): ToolExecResult {
        val nbs = Graph.notes.notebooks()
        if (nbs.isEmpty()) return ToolExecResult.ok("还没有笔记本", "无笔记本")
        val lines = nbs.map { nb ->
            "「" + nb.name + "」：" + Graph.notes.notesOf(nb.id).count { !it.trashed } + " 篇（编号 " + nb.id + "）"
        }
        return ToolExecResult.ok(lines.joinToString("\n"), nbs.size.toString() + " 个笔记本")
    }

    private suspend fun notebookCreate(a: JSONObject, sessionId: Long): ToolExecResult {
        val name = a.optString("name").trim()
        if (name.isEmpty()) return ToolExecResult.fail("笔记本名字为空")
        if (Graph.notes.notebooks().any { it.name == name }) {
            return ToolExecResult.fail("已有同名笔记本「" + name + "」")
        }
        val id = Graph.notes.createNotebook(name, COVER_SOLID, 0f)
        ToolExecutor.registerUndo(
            UndoEntry(sessionId, "删掉笔记本「" + name + "」") { Graph.notes.deleteNotebook(id) }
        )
        return ToolExecResult.ok(
            "已创建笔记本「" + name + "」（编号 " + id + "），note_create 传 notebook=「" + name + "」即可写进来",
            "建笔记本「" + name + "」"
        )
    }

    private suspend fun noteEdit(a: JSONObject, sessionId: Long): ToolExecResult {
        val id = a.optLong("id", -1)
        val old = Graph.notes.noteById(id) ?: return ToolExecResult.fail("笔记 id=" + id + " 不存在")
        var nn = old
        a.optString("title").takeIf { it.isNotBlank() }?.let { nn = nn.copy(title = it) }
        if (a.has("content")) nn = nn.copy(content = a.optString("content"))
        if (a.has("tags")) nn = nn.copy(tags = io.github.m0use001.planability.data.NotesRepo.normalizeTags(a.optString("tags")))
        var moveTo: Long? = null
        a.optString("notebook").takeIf { it.isNotBlank() }?.let { name ->
            moveTo = Graph.notes.notebooks().firstOrNull { it.name == name }?.id
                ?: return ToolExecResult.fail("没有叫「" + name + "」的笔记本（用 notebook_list 查）")
        }
        if (nn == old && moveTo == null) {
            return ToolExecResult.fail("没有任何要改的字段（至少传一个）")
        }
        // 顺序：先内容后移动——moveNote 会按库里最新行写 notebookId，反序会被 saveNote 旧值盖掉
        if (nn != old) Graph.notes.saveNote(nn.copy(updatedAt = AppTime.nowMillis()))
        moveTo?.let { Graph.notes.moveNote(id, it) }
        val undoOld = old
        ToolExecutor.registerUndo(
            UndoEntry(sessionId, "还原笔记《" + old.title + "》") { Graph.notes.saveNote(undoOld) }
        )
        return ToolExecResult.ok("已修改笔记《" + nn.title + "》（编号 " + id + "）", "已改笔记《" + nn.title + "》")
    }

    private suspend fun noteDelete(a: JSONObject, sessionId: Long): ToolExecResult {
        val id = a.optLong("id", -1)
        val n = Graph.notes.noteById(id) ?: return ToolExecResult.fail("笔记 id=" + id + " 不存在")
        Graph.notes.trashNote(id)
        ToolExecutor.registerUndo(
            UndoEntry(sessionId, "从回收站恢复《" + n.title + "》") { Graph.notes.restoreNote(id) }
        )
        ToolExecutor.registerUndoPersisted(sessionId, "恢复笔记《" + n.title + "》", "note_delete", id.toString())
        return ToolExecResult.ok("已删除笔记《" + n.title + "》（进了回收站，可撤销）", "已删笔记《" + n.title + "》")
    }

    // ---------- v49 知识库：链接与问答 ----------

    private suspend fun noteLink(a: JSONObject, sessionId: Long): ToolExecResult {
        val id = a.optLong("id", -1)
        val n = Graph.notes.noteById(id) ?: return ToolExecResult.fail("笔记 id=" + id + " 不存在")
        when (a.optString("action").trim().lowercase()) {
            "backlinks" -> {
                val src = Graph.notes.backlinks(id)
                return if (src.isEmpty()) ToolExecResult.ok("《" + n.title + "》还没有反链（没有笔记用 [[链接]] 指向它）", "无反链")
                else ToolExecResult.ok(
                    "指向《" + n.title + "》的笔记（" + src.size + " 条）：\n" +
                        src.joinToString("\n") { "《" + it.title + "》（编号 " + it.id + "）" },
                    src.size.toString() + " 条反链"
                )
            }
            "outlinks" -> {
                val (out, dangling) = Graph.notes.outLinks(id)
                val lines = mutableListOf<String>()
                out.forEach { lines += "《" + it.title + "》（编号 " + it.id + "）" }
                dangling.forEach { lines += "[[" + it + "]]（目标笔记还不存在）" }
                return if (lines.isEmpty()) ToolExecResult.ok("《" + n.title + "》正文里没有 [[链接]]", "无出链")
                else ToolExecResult.ok("《" + n.title + "》的出链：\n" + lines.joinToString("\n"), lines.size.toString() + " 条出链")
            }
            "link" -> {
                val target = WikiLinks.normalize(a.optString("target"))
                if (target.isEmpty()) return ToolExecResult.fail("target（目标标题）为空")
                if (WikiLinks.parse(n.content).any { it.title.equals(target, ignoreCase = true) }) {
                    return ToolExecResult.ok("《" + n.title + "》已有指向「" + target + "」的链接", "已存在")
                }
                val content = n.content.trimEnd() + "\n\n[[" + target + "]]"
                Graph.notes.saveNote(n.copy(content = content))  // saveNote 内重建链接图
                val undoOld = n
                ToolExecutor.registerUndo(
                    UndoEntry(sessionId, "还原笔记《" + n.title + "》") { Graph.notes.saveNote(undoOld) }
                )
                val exists = Graph.notes.allNotes().any { it.title.equals(target, ignoreCase = true) }
                return ToolExecResult.ok(
                    "已在《" + n.title + "》末尾加链接 [[" + target + "]]" +
                        (if (exists) "" else "（目标笔记暂不存在，标题建好后会自动接上）"),
                    "已加链接 " + target
                )
            }
            "unlink" -> {
                val target = WikiLinks.normalize(a.optString("target"))
                if (target.isEmpty()) return ToolExecResult.fail("target（目标标题）为空")
                val content = WikiLinks.remove(n.content, target)
                if (content == n.content) return ToolExecResult.ok("《" + n.title + "》里没有指向「" + target + "」的链接", "无此链接")
                Graph.notes.saveNote(n.copy(content = content))
                val undoOld = n
                ToolExecutor.registerUndo(
                    UndoEntry(sessionId, "还原笔记《" + n.title + "》") { Graph.notes.saveNote(undoOld) }
                )
                return ToolExecResult.ok("已从《" + n.title + "》删除指向「" + target + "」的链接", "已删链接")
            }
            else -> return ToolExecResult.fail("action 只能是 backlinks / outlinks / link / unlink")
        }
    }

    /**
     * v49 知识库问答：多轮召回 + 覆盖度判定，产出带编号的证据包。
     *
     * 刻意**不在工具里生成答案**（不调模型）：答案由主模型基于证据包组织，
     * 这里只负责「找到什么算证据、够不够」——避免一次问答烧两轮完整生成。
     */
    private suspend fun kbAsk(a: JSONObject, sessionId: Long): ToolExecResult {
        val q = a.optString("query").trim()
        if (q.isEmpty()) return ToolExecResult.fail("问题为空")
        val topK = a.optInt("topK", 6).coerceIn(1, 12)
        val tag = a.optString("tag").trim()
        val r = KbAsk.ask(q, topK, tag)
        // 2026-11-11 小说会话实录（session 1293，消息 6160-6173）：用户沉淀的研究常不在
        // **笔记**里——崩3剧情在记忆（◆三派系与人物索引）与项目文件（◆三世界观设定
        // 与时间线.md），kb_ask 当时只搜笔记 → 报「本地笔记里没有」→ 白搜一轮网页
        // （用户：「AI 会查错地方」）。修：笔记证据不足时补扫记忆与文件库（会话所属项目
        // + 全局；只扫 项目文件/项目记忆 下的文本文件，避开对话镜像噪声），
        // 一次调用查对三处，再下「本地没有」的结论。
        val projId = runCatching { Graph.chat.session(sessionId)?.projectId ?: 0L }.getOrDefault(0L)
        val memHits = if (r.evidence.isEmpty()) runCatching {
            Graph.memories.recall(q, 3, projId)
        }.getOrDefault(emptyList()) else emptyList()
        val fileHits = if (r.evidence.isEmpty()) kbFileSweep(q, projId) else emptyList()
        if (r.evidence.isEmpty() && memHits.isEmpty() && fileHits.isEmpty()) {
            return ToolExecResult.ok(
                "【知识库】笔记、记忆、项目文件三处都没有检索到与该问题相关的内容（检索轮次：" +
                    r.queries.joinToString(" | ") + "）。\n" +
                    "结论：本地未覆盖这个问题——请明确告诉用户「本地记录里没有相关内容，以下基于外部资料/通用知识」，不要用通用知识冒充本地记录。",
                "本地三处无覆盖"
            )
        }
        val body = buildString {
            if (r.evidence.isEmpty()) {
                append("【本地证据】笔记没命中，以下来自记忆/项目文件；回答时用 [编号] 标注出处并写明来源类型。\n")
            } else {
                append("【知识库证据】回答时用 [编号] 标注出处；证据没覆盖的部分必须明说没有，不许编。\n")
            }
            if (!r.inDomain) {
                append("（注意：覆盖度偏低 ").append((r.coverage * 100).toInt())
                    .append("%——证据只是间接相关，回答时要如实说明）\n")
            }
            var idx = 0
            r.evidence.forEachIndexed { i, e ->
                idx = i + 1
                append("[").append(idx).append("]《").append(e.title).append("》（编号 ").append(e.noteId)
                append("；来源 笔记/").append(e.source).append("）\n").append(e.snippet).append("\n")
            }
            memHits.forEach { m ->
                idx += 1
                append("[").append(idx).append("]【").append(m.name).append("》（来源 记忆）\n")
                    .append(m.body.take(400)).append("\n")
            }
            fileHits.forEach { (rel, snippet) ->
                idx += 1
                append("[").append(idx).append("]《").append(rel).append("》（来源 项目文件）\n")
                    .append(snippet).append("\n")
            }
            if (r.queries.size > 1) append("（已做扩检索：").append(r.queries.last()).append("）")
        }
        return ToolExecResult.ok(
            body,
            "本地证据 " + (r.evidence.size + memHits.size + fileHits.size) + " 条"
                + (if (memHits.isNotEmpty()) "（含记忆 " + memHits.size + "）" else "")
                + (if (fileHits.isNotEmpty()) "（含项目文件 " + fileHits.size + "）" else "")
        )
    }

    /**
     * 文件库补扫（kb_ask 三库之一）：在 会话所属项目 + 全局 的 项目文件/项目记忆 子树里
     * 找内容含查询词元的文本文件。对话镜像（当前会话/已归档会话）**不扫**——那是聊天记录
     * 的导出副本，任何问题都会在里面"命中"用户自己刚问的话，是纯噪声。
     * 有界：单文件 ≤300KB、命中 ≤4 条；节选 = 首个命中词元前后各 160 字。
     */
    internal suspend fun kbFileSweep(
        q: String,
        projId: Long
    ): List<Pair<String, String>> {
        val terms = io.github.m0use001.planability.data.MemoryRepo.tokenize(q, 10)
            .filter { it.length >= 2 }
        if (terms.isEmpty()) return emptyList()
        val out = mutableListOf<Pair<String, String>>()
        val pids = LinkedHashSet<Long>()
        pids += projId
        pids += 0L   // 全局
        for (pid in pids) {
            val nodes = runCatching {
                io.github.m0use001.planability.data.ProjectFileRepo.tree(pid)
            }.getOrDefault(emptyList())
            for (n in nodes) {
                if (n !is io.github.m0use001.planability.data.ProjectFileRepo.Node.File) continue
                val f = n.f
                if (f.kind == "binary" || f.content.isBlank()) continue
                if (f.size > 300_000) continue
                val rel = if (f.sub.isBlank()) f.name else f.sub + "/" + f.name
                if (!kbSweepScope(rel)) continue
                val hit = KbSweep.firstHitTerm(f.content, terms) ?: continue
                out += rel to KbSweep.snippetAround(f.content, hit)
                if (out.size >= 4) return out
            }
        }
        return out
    }

    /** 补扫范围：只认「项目文件/」「项目记忆/」子树（研究沉淀住处）；其余（对话镜像等）不算证据 */
    internal fun kbSweepScope(rel: String): Boolean =
        rel.startsWith("项目文件/") || rel.startsWith("项目记忆/")
}
